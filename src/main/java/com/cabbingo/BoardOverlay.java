package com.cabbingo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The team's bingo board in a window over the game (view only): row markers on the left, column markers under the
 * grid, details on the right. Dragging the top bar moves it; the left and right edges and the two bottom corners
 * scale it (the tile size, kept in the config with the position). RuneLite's own Alt-drag does not move it.
 * Escape closes it. Over the window only its own tooltip shows (the tile's name); the game's menu keeps its entries and
 * the plugin adds Hide / Show details (covers).
 */
@Singleton
class BoardOverlay extends Overlay implements MouseListener, MouseWheelListener, KeyListener
{
	/** The board in the server's GET /plugin/key answer (pluginBoard in models/bingo.ts). */
	static class Board
	{
		int size;
		double rowBonus;
		double columnBonus;
		List<Tile> tiles = new ArrayList<>();
		List<Bonus> bonus = new ArrayList<>(); // tiles of the day, each shown from..to (ms)
		List<Event> events = new ArrayList<>(); // the team's random events, each shown from..to (ms)
		List<Standing> teams = new ArrayList<>();
	}

	static class Bonus extends Tile
	{
		long from;
		long to;
	}

	static class Event
	{
		String title;
		String text;
		long from;
		long to;
	}

	static class Tile
	{
		String id; // the side's id, as a kill count goal names it (":flip" for a flipped side)
		String title;
		double points;
		String img; // a path inside the app (item-images/...) or an https link; empty for none
		double have;
		double need; // 0: ticked by hand, no count
		boolean done;
		boolean wait; // only drops that wait for a captain finish it
		boolean flipped;
		String description;
		List<String> rules;
	}

	static class Standing
	{
		String name;
		double points;
		boolean own;
	}

	private static final int TOP = 30; // the top bar: title and close
	private static final int PAD = 8;
	private static final int GAP = 6; // between tiles
	private static final int SIDE = 220; // the details column
	private static final int GRIP = 6; // how close to an edge a press scales
	// MIN_TILE: a 10 x 10 board fits; under the item sprite's size (36 x 32) pictures are scaled down
	private static final int MIN_TILE = 16, MAX_TILE = 96, DEFAULT_TILE = 46;
	private static final int MIN_COLUMN = 200; // the details column's least height in px, before it scrolls
	private static final int BAR = 6; // room the scrollbar takes at the column's right, out of the content's way
	private static final int ROW_H = 22; // a label/value line in the details column
	private static final int HEAD_H = 26; // a section header in it
	private static final String SITE = "https://www.cabbingo.com/";
	// tile pictures come only from our own hosts: a board's owner must not be able to make every player's client call theirs
	private static final Set<String> HOSTS = Set.of("www.cabbingo.com", "cabbingo.com", "cabbingo.bingoboard.workers.dev");

	// the window's colours, all shades of the Background colour setting (set each frame, theme)
	private Color fill, edgeDark, edgeLight, inset, head;
	private static final Color SLATE = new Color(66, 72, 72);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color CREAM = new Color(255, 232, 190);
	private static final Color YELLOW = new Color(255, 255, 0);
	private static final Color GREEN = new Color(0, 200, 0);
	private static final Color WAIT = new Color(255, 153, 51);
	private static final Color GOLD = new Color(255, 200, 0);
	private static final Color RED = new Color(255, 48, 48); // a random event's title: it must be followed

	// what a press grabbed
	private enum Grab { NONE, MOVE, LEFT, RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, SCROLL }

	private final Client client;
	private final ConfigManager configManager;
	private final ClientUI clientUI;
	private final OkHttpClient http;
	private final TooltipManager tooltipManager;
	private final ItemManager itemManager;
	private final CabbingoConfig config;
	private final SpriteManager spriteManager;
	// a resource pack's or interface style's sprites, made drawable once each
	private final Map<SpritePixels, BufferedImage> themed = new WeakHashMap<>();
	private final Map<BufferedImage, BufferedImage> trimmed = new WeakHashMap<>(); // sprites without their clear rim (border)

	private volatile Board board;
	private volatile String title = "";
	private volatile long end;
	private volatile Map<String, Double> lines = Collections.emptyMap(); // done rows and columns (row:i, col:i) -> points
	// Kills counted in the game that the server's board may not have yet (kills go out a minute late, KillBatch):
	// side id -> {server's count at the first kill, kills since, time of the last}. Shown on top of the server's
	// count until it reaches base + kills; forgotten 5 minutes after the last kill (a kill the server refused).
	private final Map<String, double[]> counted = new HashMap<>();
	private static final long COUNTED_FOR = 5 * 60_000;
	private boolean open;
	private int x, y, tile;
	private int drawn = DEFAULT_TILE; // the tile size last drawn: tile, or less when the game is too small (largestTile)
	private boolean ownPress; // the last press was taken here: its release and click are too, no other
	private int lastW, lastH; // the size last drawn: the details column sets the height
	// text cut with "..." as last drawn (window coordinates) -> the whole text, for its tooltip
	private final Map<Rectangle, String> cuts = new LinkedHashMap<>();
	// the details column's section headers as last drawn (window coordinates) -> their section; a press folds it
	private final Map<Rectangle, String> heads = new LinkedHashMap<>();
	private final Set<String> collapsed = ConcurrentHashMap.newKeySet(); // folded sections, until the client restarts
	private Rectangle column; // the details column as last drawn (window coordinates); null while hidden
	private int scroll, maxScroll; // how far the column is scrolled, and how far it can be
	// the details column is laid out once on this to learn its height before the frame is drawn
	private final Graphics2D measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
	private static final Pattern ITEM = Pattern.compile("^/?item-images/(\\d+)\\.png$");
	private int picked = -1;
	private Grab grab = Grab.NONE;
	private java.awt.Point grabAt; // the mouse at the press
	private Rectangle grabBounds; // the window at the press
	private int grabTile; // the tile size at the press
	private Rectangle bar; // the scrollbar's track as last drawn (window coordinates, a little wider to grab); null for none
	private int thumb, grabScroll; // the thumb's height; the scroll at the press
	private boolean cursorSet;

	// tile pictures by link; a link that failed is not asked again
	private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
	private final Set<String> asked = ConcurrentHashMap.newKeySet();
	private final Map<String, Image> scaled = new ConcurrentHashMap<>();
	private int scaledSize;

	@Inject
	BoardOverlay(Client client, ConfigManager configManager, ClientUI clientUI, OkHttpClient http, TooltipManager tooltipManager, ItemManager itemManager, CabbingoConfig config, SpriteManager spriteManager)
	{
		this.spriteManager = spriteManager;
		this.config = config;
		this.itemManager = itemManager;
		this.client = client;
		this.configManager = configManager;
		this.clientUI = clientUI;
		this.http = http;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		x = saved("boardX", 80);
		y = saved("boardY", 60);
		tile = Math.max(MIN_TILE, Math.min(MAX_TILE, saved("boardTile", DEFAULT_TILE)));
		getBounds().setLocation(x, y);
	}

	private int saved(String key, int fallback)
	{
		Integer v = configManager.getConfiguration(CabbingoConfig.GROUP, key, Integer.class);
		return v == null ? fallback : v;
	}

	void show(Board board, String title, long end, List<Finished> finished)
	{
		Map<String, Double> done = new HashMap<>();
		if (finished != null)
		{
			for (Finished f : finished)
			{
				if (f.id != null && (f.id.startsWith("row:") || f.id.startsWith("col:")) && !f.wait)
				{
					done.put(f.id, (double) f.points);
				}
			}
		}
		this.lines = done;
		this.end = end;
		this.title = title == null ? "" : title;
		synchronized (counted)
		{
			long now = System.currentTimeMillis();
			counted.entrySet().removeIf(e ->
			{
				double[] c = e.getValue();
				boolean keep = board != null && now - c[2] < COUNTED_FOR;
				for (Tile t : keep ? tiles(board, e.getKey()) : Collections.<Tile>emptyList())
				{
					keep &= !t.done && t.have < c[0] + c[1];
					t.have = Math.max(t.have, Math.min(t.need, c[0] + c[1]));
				}
				return !keep;
			});
		}
		this.board = board;
		if (board == null || picked >= board.tiles.size())
		{
			picked = -1;
		}
	}

	boolean hasBoard()
	{
		return board != null;
	}

	// The grid tile and the bonus tile showing a side id (a kill count goal's)
	private static List<Tile> tiles(Board b, String id)
	{
		List<Tile> out = new ArrayList<>();
		b.tiles.stream().filter(t -> id.equals(t.id)).forEach(out::add);
		b.bonus.stream().filter(t -> id.equals(t.id)).forEach(out::add);
		return out;
	}

	/** A kill that counts for a kill count side: shown on its tile now, before the server has it. */
	void counted(String id)
	{
		Board b = board;
		if (b == null)
		{
			return;
		}
		List<Tile> shown = tiles(b, id);
		if (shown.isEmpty())
		{
			return;
		}
		synchronized (counted)
		{
			double[] c = counted.computeIfAbsent(id, k -> new double[]{shown.get(0).have, 0, 0});
			c[1]++;
			c[2] = System.currentTimeMillis();
			shown.forEach(t -> t.have = Math.max(t.have, Math.min(t.need, c[0] + c[1])));
		}
	}

	boolean isOpen()
	{
		return shown();
	}

	void toggle()
	{
		open = !open && board != null;
	}

	private boolean shown()
	{
		return open && board != null && board.size > 0;
	}

	// The window's size for a board of n by n at tile size t: the markers take one more row and column
	private int width(int n, int t)
	{
		return PAD + (n + 1) * (t + GAP) + (details() ? SIDE : -GAP) + PAD;
	}

	// the details column shows: switched by the top bar's fold or the window's right-click menu
	boolean details()
	{
		return config.showDetails();
	}

	void toggleDetails()
	{
		configManager.setConfiguration(CabbingoConfig.GROUP, "showDetails", !details());
	}


	@Override
	public Dimension render(Graphics2D g)
	{
		Board b = board;
		if (!shown())
		{
			return null;
		}
		keepOut = sidePanel();
		gameWindow = Popups.modalOpen(client);
		// drawn at the saved size, smaller while the game is too small for it (largestTile); a bigger game grows it back
		int n = b.size, t = Math.min(tile, largestTile(b.size));
		drawn = t;
		int sx = PAD + (n + 1) * (t + GAP), sy = TOP + PAD;
		boolean details = details();
		// the column is as tall as the grid, at least MIN_COLUMN (or its content, if shorter); the rest scrolls
		int gridBottom = sy + (n + 1) * (t + GAP) - GAP;
		int content = details ? side(measure, b, sx, sy, SIDE) : 0;
		int bottom = details ? Math.max(gridBottom, Math.min(content, sy + MIN_COLUMN)) : gridBottom;
		if (content > bottom)
		{
			content = side(measure, b, sx, sy, SIDE - BAR); // it scrolls: narrower, so the scrollbar has its own room
		}
		maxScroll = Math.max(0, content - bottom);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int w = width(n, t), h = bottom + PAD;
		lastW = w;
		lastH = h;
		if (grab == Grab.NONE)
		{
			// where the renderer put it, kept on screen
			x = getBounds().x;
			y = getBounds().y;
		}
		// never over the side panel: its clicks stay the game's (backpack, gear, prayer, spells)
		Rectangle side = keepOut;
		if (side != null && new Rectangle(x, y, w, h).intersects(side))
		{
			x = Math.max(0, side.x - w);
			getBounds().setLocation(x, y);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		// frame and top bar
		theme();
		g.setColor(fill);
		g.fillRect(0, 0, w, h);
		g.setColor(edgeDark);
		g.drawLine(2, TOP - 1, w - 3, TOP - 1);
		g.setColor(edgeLight);
		g.drawLine(2, TOP, w - 3, TOP);
		if (!border(g, w, h))
		{
			edge(g, 0, 0, w, h);
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		String name = fit(g, title.isEmpty() ? "Bingo" : title, w - 120);
		text(g, name, (w - g.getFontMetrics().stringWidth(name)) / 2, 20, ORANGE);
		// close and the details column's fold, each lit while the mouse is over it (the areas a press takes, topButton)
		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		int over = mouse == null || grab != Grab.NONE ? 0 : topButton(mouse.getX() - x, mouse.getY() - y);
		int cx = w - PAD - 22, fx = cx - 26, cy = 4;
		BufferedImage close = sprite(over == 1 ? SpriteID.SteelborderCloseButton._1 : SpriteID.SteelborderCloseButton._0);
		if (close != null)
		{
			java.awt.Shape clip = g.getClip();
			g.clip(new RoundRectangle2D.Float(cx, cy, 22, 22, 6, 6));
			g.drawImage(close, cx + 11 - close.getWidth() / 2, cy + 11 - close.getHeight() / 2, null);
			g.setClip(clip);
		}
		else
		{
			button(g, cx, cy, over == 1);
			g.drawLine(cx + 6, cy + 6, cx + 15, cy + 15);
			g.drawLine(cx + 15, cy + 6, cx + 6, cy + 15);
		}
		button(g, fx, cy, over == 2);
		// ">" pushes the column away, "<" pulls it out
		int point = details ? fx + 14 : fx + 8, back = details ? fx + 8 : fx + 14;
		g.drawPolyline(new int[]{back, point, back}, new int[]{cy + 5, cy + 11, cy + 17}, 3);
		g.setStroke(new BasicStroke(1));

		// grid, with a marker per row on the left and per column under it
		if (t != scaledSize)
		{
			scaled.clear();
			scaledSize = t;
		}
		int gx = PAD + t + GAP, gy = TOP + PAD;
		for (int i = 0; i < n; i++)
		{
			marker(g, PAD, gy + i * (t + GAP), t, lines.get("row:" + i), b.rowBonus);
			marker(g, gx + i * (t + GAP), gy + n * (t + GAP), t, lines.get("col:" + i), b.columnBonus);
		}
		for (int i = 0; i < b.tiles.size() && i < n * n; i++)
		{
			drawTile(g, b.tiles.get(i), gx + (i % n) * (t + GAP), gy + (i / n) * (t + GAP), t, i == picked);
		}
		// details column: the team, the picked tile, the standings
		cuts.clear();
		heads.clear();
		column = details ? new Rectangle(sx, sy, SIDE, bottom - sy) : null;
		bar = null;
		if (details)
		{
			g.setColor(inset);
			g.fillRect(sx, sy, SIDE, bottom - sy);
			java.awt.Shape clip = g.getClip();
			g.clipRect(sx + 2, sy + 2, SIDE - 4, bottom - sy - 4);
			side(g, b, sx, sy - scroll, maxScroll > 0 ? SIDE - BAR : SIDE);
			g.setClip(clip);
			edge(g, sx, sy, SIDE, bottom - sy);
			if (maxScroll > 0)
			{
				// scrollbar against the column's right edge, in the room the content leaves it (BAR)
				int track = bottom - sy - 4;
				thumb = Math.max(16, track * track / (track + maxScroll));
				bar = new Rectangle(sx + SIDE - 10, sy + 2, 10, track);
				g.setColor(edgeDark);
				g.fillRect(sx + SIDE - 6, sy + 2, 4, track);
				g.setColor(edgeLight);
				g.fillRect(sx + SIDE - 6, sy + 2 + (track - thumb) * scroll / maxScroll, 4, thumb);
			}
		}

		// over the window: no tooltip of what lies behind it, only the hovered tile's name or a cut text in full
		if (mouse != null && new Rectangle(x, y, w, h).contains(mouse.getX(), mouse.getY()))
		{
			tooltipManager.clear();
			int mx = mouse.getX() - x, my = mouse.getY() - y;
			int hover = tileAt(mx, my);
			String tip = hover >= 0 && hover < b.tiles.size() ? b.tiles.get(hover).title
				: column == null || !column.contains(mx, my) ? null : cuts.entrySet().stream().filter(c -> c.getKey().contains(mx, my)).map(Map.Entry::getValue).findFirst().orElse(null);
			if (tip != null)
			{
				tooltipManager.add(new Tooltip(tip));
			}
		}
		return new Dimension(w, h);
	}

	// The details column from sy down; returns its bottom
	private int side(Graphics2D g, Board b, int sx, int sy, int sw)
	{
		List<Standing> teams = new ArrayList<>(b.teams);
		teams.sort(Comparator.comparingDouble((Standing s) -> -s.points));
		Standing own = teams.stream().filter(s -> s.own).findFirst().orElse(null);
		// each section folds to its header (a press on it, mousePressed)
		int ly = header(g, "Bingo Details", "details", sx, sy, sw, ORANGE);
		if (!collapsed.contains("details"))
		{
			if (own != null)
			{
				ly = line(g, "Points", number(own.points), sx, ly, sw);
				long ahead = teams.stream().filter(s -> s.points > own.points).count();
				ly = line(g, "Place", ordinal((int) ahead + 1) + " of " + teams.size(), sx, ly, sw);
			}
			if (end > 0)
			{
				ly = line(g, "End Date", new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH).format(new Date(end)), sx, ly, sw);
			}
		}
		if (picked >= 0 && picked < b.tiles.size())
		{
			Tile p = b.tiles.get(picked);
			ly = header(g, p.title, "tile", sx, ly + 4, sw, ORANGE);
			if (!collapsed.contains("tile"))
			{
				ly = tileLines(g, p, sx, ly, sw, 0);
			}
		}
		long now = System.currentTimeMillis();
		for (Bonus p : b.bonus == null ? Collections.<Bonus>emptyList() : b.bonus)
		{
			if (p.from <= now && now < p.to)
			{
				ly = header(g, "Bonus Tile", "bonus", sx, ly + 4, sw, ORANGE);
				if (!collapsed.contains("bonus"))
				{
					g.setFont(FontManager.getRunescapeBoldFont());
					text(g, cut(g, p.title, sw - 16, new Rectangle(sx, ly, sw, ROW_H)), sx + 8, ly + 15, CREAM);
					ly = tileLines(g, p, sx, ly + ROW_H, sw, p.to - now);
				}
			}
		}
		for (Event ev : b.events == null ? Collections.<Event>emptyList() : b.events)
		{
			if (ev.from <= now && now < ev.to)
			{
				// in red: a random event must be followed
				String key = "event:" + ev.from + ":" + ev.title;
				ly = header(g, ev.title == null || ev.title.isEmpty() ? "Random Event" : ev.title, key, sx, ly + 4, sw, RED);
				if (!collapsed.contains(key))
				{
					ly = paragraph(g, ev.text == null ? "" : ev.text, sx + 8, ly + 2, sw - 16, false);
					ly = line(g, "Time Left", left(ev.to - now), sx, ly, sw);
				}
			}
		}
		ly = header(g, "Standings", "standings", sx, ly + 4, sw, ORANGE);
		for (int i = 0; i < teams.size() && !collapsed.contains("standings"); i++)
		{
			Standing s = teams.get(i);
			ly = line(g, (i + 1) + ". " + s.name, number(s.points), sx, ly, sw, s.own ? YELLOW : ORANGE);
		}
		return ly + 4;
	}

	// A tile's progress, points, status, time left (a bonus tile's; 0: none), description and rules from ly down
	private int tileLines(Graphics2D g, Tile p, int sx, int ly, int sw, long timeLeft)
	{
		if (p.need > 0)
		{
			ly = line(g, "Progress", count(p.have) + " / " + count(p.need), sx, ly, sw);
		}
		ly = line(g, "Points", number(p.points), sx, ly, sw);
		ly = line(g, "Status", p.done ? "Done" : p.wait ? "Needs approval" : p.flipped ? "Flipped" : "Open", sx, ly, sw);
		if (timeLeft > 0)
		{
			ly = line(g, "Time Left", left(timeLeft), sx, ly, sw);
		}
		if (p.description != null && !p.description.isEmpty())
		{
			ly = paragraph(g, p.description, sx + 8, ly + 4, sw - 16, false);
		}
		if (p.rules != null && !p.rules.isEmpty())
		{
			g.setFont(FontManager.getRunescapeBoldFont());
			text(g, "Rules", sx + 8, ly + 16, ORANGE);
			ly += 20;
			for (String r : p.rules)
			{
				ly = paragraph(g, r, sx + 8, ly, sw - 16, true);
			}
		}
		return ly;
	}

	// "5h 12m", "3m"
	static String left(long ms)
	{
		long m = Math.max(0, ms) / 60_000;
		return m >= 60 ? m / 60 + "h " + m % 60 + "m" : m + "m";
	}

	// Text wrapped to width, a line per newline too; bullet: a small square before it and the lines indented
	private static int paragraph(Graphics2D g, String s, int px, int py, int width, boolean bullet)
	{
		g.setFont(FontManager.getRunescapeFont());
		FontMetrics m = g.getFontMetrics();
		int indent = bullet ? 10 : 0;
		if (bullet)
		{
			g.setColor(ORANGE);
			g.fillRect(px + 1, py + 8, 4, 4);
		}
		for (String para : s.split("\n"))
		{
			String line = "";
			for (String word : para.split(" "))
			{
				String next = line.isEmpty() ? word : line + " " + word;
				if (m.stringWidth(next) > width - indent && !line.isEmpty())
				{
					text(g, fit(g, line, width - indent), px + indent, py + 14, CREAM);
					py += 15;
					line = word;
				}
				else
				{
					line = next;
				}
			}
			text(g, fit(g, line, width - indent), px + indent, py + 14, CREAM);
			py += 15;
		}
		return py + 2;
	}

	private void marker(Graphics2D g, int mx, int my, int size, Double done, double bonus)
	{
		g.setColor(inset);
		g.fillRect(mx, my, size, size);
		frame(g, mx, my, size, done != null ? GREEN : null);
		double pts = done != null ? done : bonus;
		if (pts > 0 && size >= 24)
		{
			g.setFont(size >= 40 ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
			String s = "+" + number(pts);
			text(g, s, mx + (size - g.getFontMetrics().stringWidth(s)) / 2, my + size / 2 + 5, done != null ? GOLD : CREAM);
		}
	}

	private void drawTile(Graphics2D g, Tile t, int tx, int ty, int size, boolean picked)
	{
		g.setColor(SLATE);
		g.fillRect(tx, ty, size, size);
		// p: the inside margin, less on a small tile; a title without a picture only where it can be read
		int p = size >= 40 ? 4 : 2, bar = Math.max(p, size / 12);
		if (!drawPicture(g, t.img, tx + p, ty + p, size - 2 * p, size - bar - 2 * p - 2) && size >= 30)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			wrap(g, t.title, tx + 4, ty + 13, size - 8, ty + size - bar - 6);
		}
		// progress bar along the bottom: green as it fills, orange for drops that wait for a captain
		int bx = tx + p, by = ty + size - bar - p, bw = size - 2 * p;
		double part = t.done || t.wait ? 1 : t.need > 0 ? Math.min(1, t.have / t.need) : 0;
		g.setColor(Color.BLACK);
		g.fillRect(bx, by, bw, bar);
		g.setColor(t.wait && !t.done ? WAIT : GREEN);
		g.fillRect(bx, by, (int) (bw * part), bar);
		if (t.flipped)
		{
			// dog-ear, as on the board
			g.setColor(ORANGE);
			int e = Math.max(6, size / 6);
			g.fillPolygon(new Polygon(new int[]{tx + size - 2 - e, tx + size - 2, tx + size - 2}, new int[]{ty + 2, ty + 2, ty + 2 + e}, 3));
		}
		frame(g, tx, ty, size, picked ? YELLOW : t.done ? GREEN : t.wait ? WAIT : null);
	}

	// A square's edge: the details column's two-tone edge, or a 2 px line in a state's colour
	private void frame(Graphics2D g, int fx, int fy, int size, Color state)
	{
		if (state == null)
		{
			edge(g, fx, fy, size, size);
			return;
		}
		g.setColor(state);
		g.setStroke(new BasicStroke(2));
		g.drawRect(fx + 1, fy + 1, size - 2, size - 2);
		g.setStroke(new BasicStroke(1));
	}

	// a section header in the details column, with a fold mark (down: open, right: folded); returns where the next line goes
	private int header(Graphics2D g, String s, String key, int sx, int sy, int sw, Color color)
	{
		Rectangle at = new Rectangle(sx + 3, sy + 3, sw - 6, HEAD_H - 3);
		heads.put(at, key);
		g.setColor(head);
		g.fillRect(at.x, at.y, at.width, at.height);
		edge(g, at.x, at.y, at.width, at.height);
		g.setColor(CREAM);
		int mx = sx + 10, my = sy + 10;
		g.fillPolygon(collapsed.contains(key) ? new Polygon(new int[]{mx, mx + 6, mx}, new int[]{my, my + 5, my + 10}, 3)
			: new Polygon(new int[]{mx, mx + 10, mx + 5}, new int[]{my + 2, my + 2, my + 8}, 3));
		g.setFont(FontManager.getRunescapeBoldFont());
		String shown = cut(g, s, sw - 48, at);
		text(g, shown, sx + (sw - g.getFontMetrics().stringWidth(shown)) / 2, sy + 19, color);
		return sy + HEAD_H + 2;
	}

	// A button in the top bar (22 x 22), lit while the mouse is over it; leaves the stroke and colour set for its icon
	private void button(Graphics2D g, int bx, int by, boolean lit)
	{
		g.setColor(lit ? edgeLight : inset);
		g.fillRoundRect(bx, by, 22, 22, 6, 6);
		g.setColor(edgeDark);
		g.drawRoundRect(bx, by, 21, 21, 6, 6);
		g.setColor(edgeLight);
		g.drawRoundRect(bx + 1, by + 1, 19, 19, 5, 5);
		g.setColor(lit ? Color.WHITE : CREAM);
		g.setStroke(new BasicStroke(2));
	}

	// The top bar button at this window point: 1 close, 2 the details column's fold, 0 none
	private int topButton(int lx, int ly)
	{
		int right = lastW - PAD;
		return ly < 0 || ly > TOP || lx < right - 50 || lx >= lastW ? 0 : lx >= right - 24 ? 1 : 2;
	}

	private int line(Graphics2D g, String label, String value, int sx, int sy, int sw)
	{
		return line(g, label, value, sx, sy, sw, ORANGE);
	}

	private int line(Graphics2D g, String label, String value, int sx, int sy, int sw, Color labelColor)
	{
		g.setFont(FontManager.getRunescapeFont());
		FontMetrics m = g.getFontMetrics();
		int vw = m.stringWidth(value);
		text(g, cut(g, label, sw - vw - 24, new Rectangle(sx, sy, sw, ROW_H)), sx + 8, sy + 15, labelColor);
		text(g, value, sx + sw - 8 - vw, sy + 15, CREAM);
		g.setColor(new Color(0, 0, 0, 60));
		g.drawLine(sx + 6, sy + ROW_H - 1, sx + sw - 6, sy + ROW_H - 1);
		return sy + ROW_H;
	}

	// The window's colours from the Background colour setting: its edges and insets are darker and lighter shades of it
	private void theme()
	{
		Color c = config.background();
		if (!c.equals(fill))
		{
			fill = c;
			edgeDark = shade(c, 0.48);
			edgeLight = shade(c, 1.55);
			inset = shade(c, 0.74);
			head = shade(c, 0.93);
		}
	}

	private static Color shade(Color c, double f)
	{
		return new Color((int) Math.min(255, c.getRed() * f + (f > 1 ? 8 : 0)), (int) Math.min(255, c.getGreen() * f + (f > 1 ? 8 : 0)),
			(int) Math.min(255, c.getBlue() * f + (f > 1 ? 8 : 0)));
	}

	// A game sprite, or what a resource pack or interface style puts in its place, so the window wears the player's
	// theme; null until the game has it
	BufferedImage sprite(int id)
	{
		SpritePixels over = client.getSpriteOverrides().get(id);
		return over != null ? themed.computeIfAbsent(over, SpritePixels::toBufferedImage) : spriteManager.getSprite(id, 0);
	}

	// The sprite cut to its visible pixels: the game's border sprites carry clear space around the line
	private BufferedImage trim(BufferedImage img)
	{
		if (img == null)
		{
			return null;
		}
		return trimmed.computeIfAbsent(img, i ->
		{
			Rectangle box = BoardButton.opaque(i);
			return box == null ? i : i.getSubimage(box.x, box.y, box.width, box.height);
		});
	}

	// The game's steel window border, drawn as the game does: four corners, the top and right edges repeated, the bottom
	// and left their mirror images. False while the sprites are not there (drawn plain then, edge).
	private boolean border(Graphics2D g, int w, int h)
	{
		BufferedImage tl = trim(sprite(SpriteID.Steelborder.TOP_LEFT)), tr = trim(sprite(SpriteID.Steelborder.TOP_RIGHT)),
			bl = trim(sprite(SpriteID.Steelborder.BOTTOM_LEFT)), br = trim(sprite(SpriteID.Steelborder.BOTTOM_RIGHT)),
			top = trim(sprite(SpriteID.Steelborder2.EDGE_TOP)), right = trim(sprite(SpriteID.Steelborder2.EDGE_RIGHT));
		if (tl == null || tr == null || bl == null || br == null || top == null || right == null)
		{
			return false;
		}
		java.awt.Shape clip = g.getClip();
		g.clipRect(tl.getWidth(), 0, w - tl.getWidth() - tr.getWidth(), h);
		for (int ex = tl.getWidth(); ex < w - tr.getWidth(); ex += top.getWidth())
		{
			g.drawImage(top, ex, 0, null);
			g.drawImage(top, ex, h - top.getHeight(), ex + top.getWidth(), h, 0, top.getHeight(), top.getWidth(), 0, null); // upside down
		}
		g.setClip(clip);
		g.clipRect(0, tl.getHeight(), w, h - tl.getHeight() - bl.getHeight());
		for (int ey = tl.getHeight(); ey < h - bl.getHeight(); ey += right.getHeight())
		{
			g.drawImage(right, w - right.getWidth(), ey, null);
			g.drawImage(right, 0, ey, right.getWidth(), ey + right.getHeight(), right.getWidth(), 0, 0, right.getHeight(), null); // mirrored
		}
		g.setClip(clip);
		g.drawImage(tl, 0, 0, null);
		g.drawImage(tr, w - tr.getWidth(), 0, null);
		g.drawImage(bl, 0, h - bl.getHeight(), null);
		g.drawImage(br, w - br.getWidth(), h - br.getHeight(), null);
		return true;
	}

	// the game's two-tone window edge
	private void edge(Graphics2D g, int ex, int ey, int ew, int eh)
	{
		g.setColor(edgeDark);
		g.drawRect(ex, ey, ew - 1, eh - 1);
		g.setColor(edgeLight);
		g.drawRect(ex + 1, ey + 1, ew - 3, eh - 3);
	}

	// A tile's picture centred in the box; false while it loads or when there is none. An item picture is the game's own
	// sprite, at its size or a whole multiple of it, so it stays sharp; any other is only ever scaled down.
	private boolean drawPicture(Graphics2D g, String img, int bx, int by, int bw, int bh)
	{
		Matcher item = img == null ? null : ITEM.matcher(img);
		if (item != null && item.matches())
		{
			BufferedImage sprite = itemManager.getImage(Integer.parseInt(item.group(1)));
			int k = Math.min(bw / sprite.getWidth(), bh / sprite.getHeight());
			if (k < 1 && bw > 0 && bh > 0)
			{
				// a tile smaller than the sprite: scaled down smooth
				double f = Math.min((double) bw / sprite.getWidth(), (double) bh / sprite.getHeight());
				int sw = Math.max(1, (int) (sprite.getWidth() * f)), sh = Math.max(1, (int) (sprite.getHeight() * f));
				g.drawImage(sprite, bx + (bw - sw) / 2, by + (bh - sh) / 2, sw, sh, null);
				return true;
			}
			k = Math.max(1, k);
			int sw = sprite.getWidth() * k, sh = sprite.getHeight() * k;
			Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(sprite, bx + (bw - sw) / 2, by + (bh - sh) / 2, sw, sh, null);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
			return true;
		}
		String link = link(img);
		if (link == null || bw <= 0 || bh <= 0)
		{
			return false;
		}
		Image s = scaled.get(link);
		if (s == null)
		{
			BufferedImage full = images.get(link);
			if (full == null)
			{
				load(link);
				return false;
			}
			double k = Math.min(1, Math.min((double) bw / full.getWidth(), (double) bh / full.getHeight()));
			s = k == 1 ? full : full.getScaledInstance(Math.max(1, (int) (full.getWidth() * k)), Math.max(1, (int) (full.getHeight() * k)), Image.SCALE_SMOOTH);
			scaled.put(link, s);
		}
		g.drawImage(s, bx + (bw - s.getWidth(null)) / 2, by + (bh - s.getHeight(null)) / 2, null);
		return true;
	}

	// The full link of a tile picture, or null for none or one off our own hosts
	static String link(String img)
	{
		if (img == null || img.isEmpty())
		{
			return null;
		}
		HttpUrl url = HttpUrl.parse(img.startsWith("https://") ? img : SITE + (img.startsWith("/") ? img.substring(1) : img));
		return url != null && "https".equals(url.scheme()) && HOSTS.contains(url.host()) ? url.toString() : null;
	}

	private void load(String link)
	{
		if (!asked.add(link))
		{
			return;
		}
		http.newCall(new Request.Builder().url(link).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					BufferedImage img = response.isSuccessful() ? read(response.body().bytes()) : null;
					if (img != null)
					{
						images.put(link, img);
					}
				}
			}
		});
	}

	// A picture ImageIO reads (PNG, JPEG, GIF); null for others, such as WebP: the tile then shows its title. WebP needs a
	// library the Plugin Hub has to review by hand (left out for the first release, user 2026-11-13).
	static BufferedImage read(byte[] bytes) throws IOException
	{
		return ImageIO.read(new ByteArrayInputStream(bytes));
	}

	private static void text(Graphics2D g, String s, int tx, int ty, Color c)
	{
		g.setColor(Color.BLACK);
		g.drawString(s, tx + 1, ty + 1);
		g.setColor(c);
		g.drawString(s, tx, ty);
	}

	// fit, and a cut text remembered with where it shows, for its tooltip
	private String cut(Graphics2D g, String s, int width, Rectangle at)
	{
		String shown = fit(g, s, width);
		if (!shown.equals(s))
		{
			cuts.put(at, s);
		}
		return shown;
	}

	// s cut with "..." to fit width
	private static String fit(Graphics2D g, String s, int width)
	{
		FontMetrics m = g.getFontMetrics();
		if (m.stringWidth(s) <= width)
		{
			return s;
		}
		while (!s.isEmpty() && m.stringWidth(s + "...") > width)
		{
			s = s.substring(0, s.length() - 1);
		}
		return s + "...";
	}

	// the title over a tile without a picture, word by word, as many lines as fit
	private static void wrap(Graphics2D g, String s, int tx, int ty, int width, int bottom)
	{
		FontMetrics m = g.getFontMetrics();
		String line = "";
		for (String word : s.split(" "))
		{
			String next = line.isEmpty() ? word : line + " " + word;
			if (m.stringWidth(next) > width && !line.isEmpty())
			{
				if (ty + 11 > bottom)
				{
					text(g, fit(g, line + "...", width), tx, ty, Color.WHITE);
					return;
				}
				text(g, fit(g, line, width), tx, ty, Color.WHITE);
				ty += 11;
				line = word;
			}
			else
			{
				line = next;
			}
		}
		if (!line.isEmpty())
		{
			text(g, fit(g, line, width), tx, ty, Color.WHITE);
		}
	}

	static String number(double v)
	{
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

	static String ordinal(int i)
	{
		int tens = i % 100;
		String end = tens >= 11 && tens <= 13 ? "th" : i % 10 == 1 ? "st" : i % 10 == 2 ? "nd" : i % 10 == 3 ? "rd" : "th";
		return i + end;
	}

	private static String count(double v)
	{
		return QuantityFormatter.quantityToStackSize((long) v);
	}

	// Mouse: canvas coordinates, the same as getBounds()

	// The side panel with its tab stones, in all three layouts: Jagex's rules forbid changing click zones over the
	// backpack, gear, prayer and spellbook, so the window never lies over it (user, 2026-11-13). Null when none shows.
	private static final int[] SIDE_PARTS = {
		InterfaceID.Toplevel.SIDE_TOP_CONTAINER, InterfaceID.Toplevel.SIDE_BOTTOM_CONTAINER, InterfaceID.Toplevel.SIDE_PANELS,
		InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER, InterfaceID.ToplevelOsrsStretch.SIDE_TOP, InterfaceID.ToplevelOsrsStretch.SIDE_BOTTOM,
		InterfaceID.ToplevelOsrsStretch.SIDE_PANELS,
		InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, InterfaceID.ToplevelPreEoc.SIDE_STATIC, InterfaceID.ToplevelPreEoc.SIDE_MOVABLE,
		InterfaceID.ToplevelPreEoc.SIDE_PANELS,
	};
	private volatile Rectangle keepOut;
	// a game window (bank, shop, settings) is open: Escape closes that one first, the board only after (user, 2026-11-13)
	private volatile boolean gameWindow;

	private Rectangle sidePanel()
	{
		Rectangle out = null;
		for (int id : SIDE_PARTS)
		{
			Widget w = client.getWidget(id);
			if (w != null && !w.isHidden() && w.getWidth() > 0)
			{
				Rectangle r = w.getBounds();
				out = out == null ? r : out.union(r);
			}
		}
		return out;
	}

	private Grab grabAt(java.awt.Point p)
	{
		Board b = board;
		if (!shown())
		{
			return null;
		}
		int w = lastW, h = lastH;
		Rectangle side = keepOut;
		if (!new Rectangle(x, y, w, h).contains(p) || (side != null && side.contains(p)))
		{
			return null;
		}
		int lx = p.x - x, ly = p.y - y;
		boolean bottom = ly >= h - GRIP * 2;
		if (lx < GRIP)
		{
			return bottom ? Grab.BOTTOM_LEFT : ly > TOP ? Grab.LEFT : Grab.MOVE;
		}
		if (lx >= w - GRIP)
		{
			return bottom ? Grab.BOTTOM_RIGHT : ly > TOP ? Grab.RIGHT : Grab.MOVE;
		}
		return ly <= TOP ? Grab.MOVE : Grab.NONE;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		// an open right-click menu over the window gets the click: its entries, the board's or the game's (user, 2026-11-12)
		Grab at = client.isMenuOpen() ? null : grabAt(e.getPoint());
		ownPress = at != null && !e.isAltDown() && SwingUtilities.isLeftMouseButton(e);
		if (!ownPress)
		{
			return e;
		}
		e.consume();
		int lx = e.getX() - x, ly = e.getY() - y;
		int button = at == Grab.MOVE ? topButton(lx, ly) : 0;
		if (button == 1)
		{
			open = false;
			return e;
		}
		if (button == 2)
		{
			toggleDetails();
			return e;
		}
		String head = column == null || !column.contains(lx, ly) ? null
			: heads.entrySet().stream().filter(h -> h.getKey().contains(lx, ly)).map(Map.Entry::getValue).findFirst().orElse(null);
		if (at == Grab.NONE && head != null)
		{
			if (!collapsed.remove(head))
			{
				collapsed.add(head);
			}
			return e;
		}
		Rectangle track = bar; // (the renderer sets it anew each frame)
		int most = maxScroll;
		if (at == Grab.NONE && track != null && most > 0 && track.contains(lx, ly))
		{
			// the scrollbar: a press off the thumb first moves the thumb's middle there, then it drags
			int top = track.y + (track.height - thumb) * scroll / most;
			if (ly < top || ly >= top + thumb)
			{
				scroll = Math.max(0, Math.min(most, (ly - track.y - thumb / 2) * most / Math.max(1, track.height - thumb)));
			}
			grab = Grab.SCROLL;
			grabAt = e.getPoint();
			grabScroll = scroll;
			return e;
		}
		if (at == Grab.NONE)
		{
			int i = tileAt(lx, ly);
			// a second click on it puts it back; a click outside a tile does too, unless turned off in the config
			if (i >= 0 || config.unpickOutside())
			{
				picked = i == picked ? -1 : i;
			}
			return e;
		}
		grab = at;
		grabAt = e.getPoint();
		grabBounds = new Rectangle(x, y, lastW, lastH);
		grabTile = drawn;
		return e;
	}

	private int tileAt(int lx, int ly)
	{
		int n = board.size, step = drawn + GAP;
		int gx = lx - (PAD + step), gy = ly - (TOP + PAD);
		int cx = Math.floorDiv(gx, step), cy = Math.floorDiv(gy, step);
		boolean in = gx >= 0 && gy >= 0 && cx < n && cy < n && gx % step < drawn && gy % step < drawn;
		return in ? cy * n + cx : -1;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (grab == Grab.NONE)
		{
			return e;
		}
		e.consume();
		int n = board == null ? 1 : board.size;
		int dx = e.getX() - grabAt.x, dy = e.getY() - grabAt.y;
		if (grab == Grab.SCROLL)
		{
			Rectangle track = bar;
			int room = track == null ? 0 : track.height - thumb;
			scroll = room <= 0 ? scroll : Math.max(0, Math.min(maxScroll, grabScroll + dy * maxScroll / room));
			return e;
		}
		Rectangle r = grabBounds;
		if (grab == Grab.MOVE)
		{
			x = r.x + dx;
			y = r.y + dy;
		}
		else if (!new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight()).contains(e.getPoint()))
		{
			// the mouse left the game: scaling stops there, as at a release
			endGrab();
			return e;
		}
		else
		{
			// scaling: the edge or corner moves by about one tile step per n + 1 pixels of drag, never past largestTile
			int pull = grab == Grab.LEFT ? -dx : grab == Grab.RIGHT ? dx : grab == Grab.BOTTOM_LEFT ? (dy - dx) / 2 : (dx + dy) / 2;
			tile = Math.max(MIN_TILE, Math.min(largestTile(n), grabTile + pull / (n + 1)));
			if (grab == Grab.LEFT || grab == Grab.BOTTOM_LEFT)
			{
				x = r.x + r.width - width(n, tile); // the right edge stays
			}
		}
		getBounds().setLocation(x, y);
		return e;
	}

	// A move or scale ends: the place and tile size are kept
	private void endGrab()
	{
		grab = Grab.NONE;
		configManager.setConfiguration(CabbingoConfig.GROUP, "boardX", x);
		configManager.setConfiguration(CabbingoConfig.GROUP, "boardY", y);
		configManager.setConfiguration(CabbingoConfig.GROUP, "boardTile", tile);
	}

	// The tile size from the plugin's settings (Board tile size); a drag in progress keeps its own
	void tileSize(int size)
	{
		if (grab == Grab.NONE)
		{
			tile = Math.max(MIN_TILE, Math.min(MAX_TILE, size));
		}
	}

	// The largest tile at which the window takes at most 4/5 of the game's width and height (the grid; the details
	// column scrolls past 2/3 of the height), so an edge or corner is always left on screen to scale it back
	private int largestTile(int n)
	{
		int cw = client.getCanvasWidth() * 4 / 5, ch = client.getCanvasHeight() * 4 / 5;
		Rectangle side = keepOut;
		if (side != null)
		{
			cw = Math.min(cw, side.x); // so it always fits left of the side panel
		}
		int byWidth = (cw - 2 * PAD - (details() ? SIDE : -GAP)) / (n + 1) - GAP;
		int byHeight = (ch - TOP - 2 * PAD + GAP) / (n + 1) - GAP;
		return Math.max(MIN_TILE, Math.min(MAX_TILE, Math.min(byWidth, byHeight)));
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (grab == Grab.SCROLL)
		{
			grab = Grab.NONE;
			e.consume();
		}
		else if (grab != Grab.NONE)
		{
			e.consume();
			endGrab();
		}
		else if (ownPress && SwingUtilities.isLeftMouseButton(e))
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (ownPress && SwingUtilities.isLeftMouseButton(e))
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e)
	{
		Grab at = e.isAltDown() ? null : grabAt(e.getPoint());
		int cursor = at == null ? -1 : at == Grab.LEFT ? Cursor.W_RESIZE_CURSOR : at == Grab.RIGHT ? Cursor.E_RESIZE_CURSOR
			: at == Grab.BOTTOM_LEFT ? Cursor.SW_RESIZE_CURSOR : at == Grab.BOTTOM_RIGHT ? Cursor.SE_RESIZE_CURSOR : -1;
		if (cursor >= 0)
		{
			clientUI.setCursor(Cursor.getPredefinedCursor(cursor));
			cursorSet = true;
		}
		else if (cursorSet)
		{
			clientUI.setCursor(clientUI.getDefaultCursor());
			cursorSet = false;
		}
		return e;
	}

	// The wheel over the window scrolls the details column, never the game's camera
	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
	{
		if (!shown() || e.isAltDown() || !new Rectangle(x, y, lastW, lastH).contains(e.getPoint()))
		{
			return e;
		}
		e.consume();
		if (column != null && column.contains(e.getX() - x, e.getY() - y))
		{
			scroll = Math.max(0, Math.min(maxScroll, scroll + e.getWheelRotation() * 30));
		}
		return e;
	}

	// whether the open window covers this canvas point: the plugin then adds Hide / Show details to the menu there
	boolean covers(int px, int py)
	{
		return grabAt(new java.awt.Point(px, py)) != null;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_ESCAPE && shown() && !gameWindow)
		{
			open = false;
			e.consume();
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e)
	{
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e)
	{
		return e;
	}
}
