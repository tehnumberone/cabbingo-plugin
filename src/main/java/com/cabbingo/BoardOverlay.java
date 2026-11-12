package com.cabbingo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The team's bingo board in a window over the game (view only). Dragging the top bar moves it; the left and right
 * edges and the two bottom corners resize it. Where it was and how big are kept in the config. The window takes the
 * mouse only inside itself; RuneLite's own Alt-drag does not move it (setMovable(false)), the top bar does.
 */
@Singleton
class BoardOverlay extends Overlay implements MouseListener
{
	/** The board in the server's GET /plugin/key answer (pluginBoard in models/bingo.ts). */
	static class Board
	{
		int size;
		List<Tile> tiles = new ArrayList<>();
		List<Standing> teams = new ArrayList<>();
	}

	static class Tile
	{
		String title;
		double points;
		String img; // a path inside the app (item-images/...) or an https link; empty for none
		double have;
		double need; // 0: ticked by hand, no count
		boolean done;
		boolean wait; // only drops that wait for a captain finish it
		boolean flipped;
	}

	static class Standing
	{
		String name;
		double points;
		boolean own;
	}

	private static final int TOP = 24; // the top bar: title and close
	private static final int PAD = 6;
	private static final int SIDE = 120; // the standings column
	private static final int DETAIL = 34; // the strip under the grid: the picked tile
	private static final int GRIP = 6; // how close to an edge a press resizes
	private static final int MIN_TILE = 24;
	private static final String SITE = "https://www.cabbingo.com/";
	// tile pictures come only from our own hosts: a board's owner must not be able to make every player's client call theirs
	private static final Set<String> HOSTS = Set.of("www.cabbingo.com", "cabbingo.com", "cabbingo.bingoboard.workers.dev");

	private static final Color FILL = new Color(62, 53, 41, 240);
	private static final Color EDGE_DARK = new Color(30, 25, 20);
	private static final Color EDGE_LIGHT = new Color(90, 80, 64);
	private static final Color CELL = new Color(43, 37, 28);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color YELLOW = new Color(255, 255, 0);
	private static final Color DONE = new Color(0, 170, 0, 115);
	private static final Color WAIT = new Color(255, 153, 51, 153);
	private static final Color BAND = new Color(0, 0, 0, 150);

	// what a press grabbed
	private enum Grab { NONE, MOVE, LEFT, RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

	private final ConfigManager configManager;
	private final ClientUI clientUI;
	private final OkHttpClient http;

	private volatile Board board;
	private volatile String title = "";
	private boolean open;
	private int x, y, w, h;
	private int picked = -1;
	private Grab grab = Grab.NONE;
	private java.awt.Point grabAt; // the mouse at the press
	private Rectangle grabBounds; // the window at the press
	private boolean cursorSet;

	// tile pictures by link; a link that failed holds null and is not asked again
	private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
	private final Set<String> asked = ConcurrentHashMap.newKeySet();
	private final Map<String, Image> scaled = new ConcurrentHashMap<>();
	private int scaledSize;

	@Inject
	BoardOverlay(ConfigManager configManager, ClientUI clientUI, OkHttpClient http)
	{
		this.configManager = configManager;
		this.clientUI = clientUI;
		this.http = http;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		x = saved("boardX", 80);
		y = saved("boardY", 60);
		w = saved("boardW", 520);
		h = saved("boardH", 470);
		getBounds().setBounds(x, y, w, h);
	}

	private int saved(String key, int fallback)
	{
		Integer v = configManager.getConfiguration(CabbingoConfig.GROUP, key, Integer.class);
		return v == null ? fallback : v;
	}

	void show(Board board, String title)
	{
		this.board = board;
		this.title = title == null ? "" : title;
		if (board == null || picked >= board.tiles.size())
		{
			picked = -1;
		}
	}

	boolean hasBoard()
	{
		return board != null;
	}

	void toggle()
	{
		open = !open && board != null;
	}

	private boolean shown()
	{
		return open && board != null && board.size > 0;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Board b = board;
		if (!shown())
		{
			return null;
		}
		int n = b.size;
		w = Math.max(w, minWidth(n));
		h = Math.max(h, minHeight(n));
		if (grab == Grab.NONE)
		{
			// where the renderer put it, kept on screen
			x = getBounds().x;
			y = getBounds().y;
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		// frame
		g.setColor(FILL);
		g.fillRect(0, 0, w, h);
		g.setColor(EDGE_DARK);
		g.drawRect(0, 0, w - 1, h - 1);
		g.setColor(EDGE_LIGHT);
		g.drawRect(1, 1, w - 3, h - 3);
		g.drawLine(2, TOP, w - 3, TOP);

		// top bar
		g.setFont(FontManager.getRunescapeBoldFont());
		text(g, fit(g, title.isEmpty() ? "Bingo" : title, w - 40), PAD + 2, 17, ORANGE);
		text(g, "x", w - 16, 16, ORANGE);

		// grid
		int tile = tileSize(n);
		if (tile != scaledSize)
		{
			scaled.clear();
			scaledSize = tile;
		}
		int gx = PAD, gy = TOP + PAD;
		for (int i = 0; i < b.tiles.size() && i < n * n; i++)
		{
			drawTile(g, b.tiles.get(i), gx + (i % n) * tile, gy + (i / n) * tile, tile, i == picked);
		}

		// standings
		int sx = gx + n * tile + PAD;
		g.setFont(FontManager.getRunescapeSmallFont());
		List<Standing> teams = new ArrayList<>(b.teams);
		teams.sort(Comparator.comparingDouble((Standing s) -> -s.points));
		int ty = gy + 10;
		for (int i = 0; i < teams.size() && ty < h - DETAIL; i++, ty += 14)
		{
			Standing s = teams.get(i);
			String pts = number(s.points);
			Color c = s.own ? YELLOW : Color.WHITE;
			text(g, fit(g, (i + 1) + ". " + s.name, w - sx - PAD - g.getFontMetrics().stringWidth(pts) - 6), sx, ty, c);
			text(g, pts, w - PAD - g.getFontMetrics().stringWidth(pts), ty, c);
		}

		// picked tile
		if (picked >= 0 && picked < b.tiles.size())
		{
			Tile t = b.tiles.get(picked);
			int dy = gy + n * tile + 14;
			g.setFont(FontManager.getRunescapeBoldFont());
			text(g, fit(g, t.title, w - 2 * PAD), PAD, dy, ORANGE);
			g.setFont(FontManager.getRunescapeSmallFont());
			String line = (t.need > 0 ? count(t.have) + " / " + count(t.need) + "  ·  " : "") + number(t.points) + " points"
				+ (t.done ? "  ·  Done" : t.wait ? "  ·  Needs approval" : "") + (t.flipped ? "  ·  Flipped" : "");
			text(g, line, PAD, dy + 14, Color.WHITE);
		}
		return new Dimension(w, h);
	}

	private void drawTile(Graphics2D g, Tile t, int tx, int ty, int size, boolean picked)
	{
		g.setColor(CELL);
		g.fillRect(tx + 1, ty + 1, size - 2, size - 2);
		Image img = picture(t.img, size - 8);
		if (img != null)
		{
			g.drawImage(img, tx + (size - img.getWidth(null)) / 2, ty + (size - img.getHeight(null)) / 2, null);
		}
		else
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			wrap(g, t.title, tx + 3, ty + 12, size - 6, ty + size - 4);
		}
		if (t.done || t.wait)
		{
			Composite c = g.getComposite();
			g.setColor(t.done ? DONE : WAIT);
			g.fillRect(tx + 1, ty + 1, size - 2, size - 2);
			g.setComposite(c);
		}
		else if (t.need > 0 && size >= 40)
		{
			// the count, on a band at the bottom
			g.setFont(FontManager.getRunescapeSmallFont());
			String c = count(t.have) + "/" + count(t.need);
			g.setColor(BAND);
			g.fillRect(tx + 1, ty + size - 13, size - 2, 12);
			text(g, c, tx + (size - g.getFontMetrics().stringWidth(c)) / 2, ty + size - 3, Color.WHITE);
		}
		if (t.flipped)
		{
			// dog-ear, as on the board
			g.setColor(ORANGE);
			int e = Math.max(6, size / 6);
			g.fillPolygon(new Polygon(new int[]{tx + size - 1 - e, tx + size - 1, tx + size - 1}, new int[]{ty + 1, ty + 1, ty + 1 + e}, 3));
		}
		g.setColor(picked ? YELLOW : EDGE_DARK);
		g.setStroke(new BasicStroke(picked ? 2 : 1));
		g.drawRect(tx, ty, size - 1, size - 1);
		g.setStroke(new BasicStroke(1));
	}

	// A tile's picture scaled to fit box, or null while it loads or when there is none
	private Image picture(String img, int box)
	{
		String link = link(img);
		if (link == null || box <= 0)
		{
			return null;
		}
		Image s = scaled.get(link);
		if (s != null)
		{
			return s;
		}
		BufferedImage full = images.get(link);
		if (full == null)
		{
			load(link);
			return null;
		}
		double k = Math.min((double) box / full.getWidth(), (double) box / full.getHeight());
		s = full.getScaledInstance(Math.max(1, (int) (full.getWidth() * k)), Math.max(1, (int) (full.getHeight() * k)), Image.SCALE_SMOOTH);
		scaled.put(link, s);
		return s;
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
					BufferedImage img = response.isSuccessful() ? ImageIO.read(response.body().byteStream()) : null;
					if (img != null)
					{
						images.put(link, img);
					}
				}
			}
		});
	}

	private int tileSize(int n)
	{
		return Math.max(MIN_TILE, Math.min((w - 3 * PAD - SIDE) / n, (h - TOP - 2 * PAD - DETAIL) / n));
	}

	private static int minWidth(int n)
	{
		return 3 * PAD + SIDE + n * MIN_TILE;
	}

	private static int minHeight(int n)
	{
		return TOP + 2 * PAD + DETAIL + n * MIN_TILE;
	}

	private static void text(Graphics2D g, String s, int tx, int ty, Color c)
	{
		g.setColor(Color.BLACK);
		g.drawString(s, tx + 1, ty + 1);
		g.setColor(c);
		g.drawString(s, tx, ty);
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
				text(g, line, tx, ty, Color.WHITE);
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

	private static String count(double v)
	{
		return QuantityFormatter.quantityToStackSize((long) v);
	}

	// Mouse: canvas coordinates, the same as getBounds()

	private Grab grabAt(java.awt.Point p)
	{
		Rectangle r = new Rectangle(x, y, w, h);
		if (!shown() || !r.contains(p))
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
		Grab at = grabAt(e.getPoint());
		if (at == null || e.isAltDown())
		{
			return e;
		}
		e.consume();
		if (!SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		int lx = e.getX() - x, ly = e.getY() - y;
		if (at == Grab.MOVE && lx >= w - 20)
		{
			open = false; // the x
			return e;
		}
		if (at == Grab.NONE)
		{
			picked = tileAt(lx, ly);
			return e;
		}
		grab = at;
		grabAt = e.getPoint();
		grabBounds = new Rectangle(x, y, w, h);
		return e;
	}

	private int tileAt(int lx, int ly)
	{
		Board b = board;
		int n = b.size, tile = tileSize(n);
		int cx = (lx - PAD) / tile, cy = (ly - TOP - PAD) / tile;
		boolean in = lx >= PAD && ly >= TOP + PAD && cx < n && cy < n;
		int i = cy * n + cx;
		return in && i != picked ? i : -1;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e)
	{
		if (grab == Grab.NONE || grab == null)
		{
			return e;
		}
		e.consume();
		int n = board == null ? 1 : board.size;
		int dx = e.getX() - grabAt.x, dy = e.getY() - grabAt.y;
		Rectangle r = grabBounds;
		switch (grab)
		{
			case MOVE:
				x = r.x + dx;
				y = r.y + dy;
				break;
			case RIGHT:
				w = Math.max(minWidth(n), r.width + dx);
				break;
			case BOTTOM_RIGHT:
				w = Math.max(minWidth(n), r.width + dx);
				h = Math.max(minHeight(n), r.height + dy);
				break;
			case LEFT:
			case BOTTOM_LEFT:
				w = Math.max(minWidth(n), r.width - dx);
				x = r.x + r.width - w;
				if (grab == Grab.BOTTOM_LEFT)
				{
					h = Math.max(minHeight(n), r.height + dy);
				}
				break;
			default:
		}
		getBounds().setLocation(x, y);
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		if (grab != Grab.NONE)
		{
			grab = Grab.NONE;
			e.consume();
			configManager.setConfiguration(CabbingoConfig.GROUP, "boardX", x);
			configManager.setConfiguration(CabbingoConfig.GROUP, "boardY", y);
			configManager.setConfiguration(CabbingoConfig.GROUP, "boardW", w);
			configManager.setConfiguration(CabbingoConfig.GROUP, "boardH", h);
		}
		else if (grabAt(e.getPoint()) != null)
		{
			e.consume();
		}
		return e;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		if (grabAt(e.getPoint()) != null)
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
