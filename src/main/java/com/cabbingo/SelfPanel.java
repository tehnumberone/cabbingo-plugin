package com.cabbingo;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.hiscore.HiscoreSkill;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The side panel for a self-hosted bingo (Board canvas, row "Self-hosted bingo: side panel", v244). The start screen
 * lists the owner's bingos on their own worker (GET /self, owner passcode) with New bingo and a delete per bingo; a
 * bingo opens in the editor (Bingo, Tiles, Teams; Back returns to the list). A captain's Review tab, on the Team URL's
 * worker with the captain passcode, approves or denies the team's drops and adds proof by link for a player without
 * the plugin (a phone). Looks like the Cabbingo site: its brown panels, texture, stone button and colours.
 * Only shown when the player turns it on, or has a captain passcode (CabbingoPlugin.sidebar).
 */
@Slf4j
class SelfPanel extends PluginPanel
{
	static final String[] SKILLS = SkillCounter.SKILLS.toArray(new String[0]);
	// every skill an XP tile can count (TempleOSRS's skills, Overall too)
	static final String[] XP_SKILLS = {"Overall", "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting",
		"Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft",
		"Hunter", "Construction", "Sailing"};
	private static final String[] TYPES = {"drops", "kc", "skill", "xp"};
	private static final String[] TYPE_NAMES = {"Drops", "Kill count", "Skilling", "XP"};
	private static final String GAINED = "Gained"; // an XP tile's one count
	private static final String TOTAL = "Obtained"; // a kill count tile's one count (TOTAL_ENTRY in models/bingo.ts)
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ENGLISH); // (a card is too narrow for two full years)
	private static final MediaType JSON = MediaType.parse("application/json");
	// the site's theme (src/styles.css :root and .osrs-border)
	static final Color PANEL = new Color(57, 50, 40);
	static final Color PANEL_2 = new Color(62, 53, 41);
	static final Color PANEL_0 = new Color(51, 43, 33);
	static final Color SURFACE = new Color(72, 62, 51);
	static final Color SURFACE_HI = new Color(100, 85, 70);
	static final Color EDGE = new Color(46, 43, 35);
	static final Color EDGE_HI = new Color(86, 75, 61);
	static final Color FRAME = new Color(66, 67, 65);
	static final Color FRAME_OUT = new Color(14, 14, 12);
	static final Color GOLD = new Color(255, 215, 0);
	static final Color ORANGE = new Color(255, 153, 51);
	static final Color TAN = new Color(200, 185, 154);
	static final Color DIM = new Color(138, 125, 106);
	static final Color GREEN = new Color(125, 219, 138);
	static final Color RED = new Color(255, 154, 154);
	// the worker's check for a hiscores picture (self.ts formError)
	private static final Pattern HISCORE_NAME = Pattern.compile("^[A-Za-z' -]{1,40}$");
	// the site's stone button and panel texture (src/assets)
	private static final BufferedImage BUTTON = ImageUtil.loadImageResource(SelfPanel.class, "button.png");
	private static final BufferedImage BACKDROP = ImageUtil.loadImageResource(SelfPanel.class, "tiles-backdrop.png");

	// The worker's shapes (drop-worker/src/self.ts)
	static class Form
	{
		String name = "";
		int size = 5;
		long start;
		long end;
		int rowBonus = 5;
		String temple; // a TempleOSRS competition id: XP tiles count from TempleOSRS instead of the plugin
		boolean skillShots; // Skilling screenshots to the team's Discord channel (Board.skillShots)
		boolean kcShots = true; // Kill count screenshots to the team's Discord channel (Board.kcShots)
		int columnBonus = 5;
		List<Tile> tiles = new ArrayList<>();
		List<SelfTeam> teams = new ArrayList<>();
	}

	static class Tile
	{
		String type = "drops";
		String title = "";
		List<String> items;
		String monster;
		String skill;
		int count = 1;
		int points = 1;
		String img;
	}

	static class SelfTeam
	{
		String id;
		String name = "";
		List<String> players = new ArrayList<>();
		String webhook;
	}

	static class Link
	{
		String teamId;
		String key;
		String passcode;
	}

	static class Saved
	{
		Integer id;
		Form form;
		List<Link> links;
	}

	static class Summary
	{
		int id;
		String name;
		int size;
		long start;
		long end;
		int teams;
	}

	static class Listing
	{
		List<Summary> bingos;
	}

	static class Names
	{
		Map<String, Integer> items;
		List<String> monsters;
	}

	static class Item
	{
		String name;
		int quantity;
	}

	static class TileRef
	{
		String id;
		String title;
	}

	static class Drop
	{
		long id;
		String rsn;
		List<Item> items;
		String image;
		long created;
		String status;
		List<List<TileRef>> tiles;
		String tileId; // a reviewed drop's tile: the one it counted for, else the first it fits; null for none
	}

	static class ProofTile
	{
		String id;
		String title;
		String type;
		List<String> items;
		int count;
		Map<String, Integer> got;
		boolean done;
	}

	static class Review
	{
		String team;
		List<String> players;
		List<ProofTile> tiles;
		List<Drop> pending;
		List<Drop> reviewed;
	}

	private final CabbingoConfig config;
	private final ConfigManager configManager;
	private final OkHttpClient http;
	private final Gson gson;
	private final Team team;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;

	private final Text heading = new Text("", GOLD);
	private final Text sizeLabel = new Text("", TAN);
	private final Btn back = new Btn("", ORANGE, null, null);
	private final JPanel tabs = new JPanel();
	private final Text status = new Text("", RED);
	// a save's answer, under the save button where the eye is (user, 2026-11-15); saving: fail() writes here
	private final Text saveStatus = new Text("", GREEN);
	private boolean saving;
	private final JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 8));

	// where the panel is: the start (Bingos, Review) or a bingo's editor (Bingo, Tiles, Teams)
	private boolean editing;
	private String tab = "Bingos";
	private List<Summary> bingos;
	private Integer bingoId; // null: a new bingo, not created yet
	private Form form = blank();
	private String savedJson = ""; // the form as last loaded or saved, for Back's "leave without saving?"
	private List<Link> links = new ArrayList<>();
	private int tileAt = -1;
	private int teamAt = 0;
	private Btn[] grid = new Btn[0];
	// the site's lists (/self/names): item name (lower case) -> id, the names as the site spells them, the monsters
	private Map<String, Integer> itemIds = Map.of();
	private List<String> itemNames = List.of();
	private List<String> monsters = List.of();
	private boolean namesAsked;
	// the captain's side
	private Review review;
	private final Map<String, String> picks = new HashMap<>(); // drop id + item name -> picked tile id
	private final Set<String> openGroups = new HashSet<>(); // Reviewed rows opened (tile id, "" for No tile)
	// Review keeps itself up to date while it shows (a teammate's drop, another captain's decision); a refresh that
	// brings nothing new changes nothing on the screen
	private final Timer refresh = new Timer(30_000, e ->
	{
		if (!editing && "Review".equals(tab) && !this.proofOpen && isShowing())
		{
			loadReview();
		}
	});
	private boolean proofOpen;
	private String proofTile;
	private String proofPlayer;
	private String proofLink = "";
	private final Map<String, Integer> proofCounts = new LinkedHashMap<>();

	@Inject
	SelfPanel(CabbingoConfig config, ConfigManager configManager, OkHttpClient http, Gson gson, Team team, ItemManager itemManager, SpriteManager spriteManager)
	{
		this.config = config;
		this.configManager = configManager;
		this.http = http;
		this.gson = gson;
		this.team = team;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		setBorder(new EmptyBorder(0, 0, 10, 0));

		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setBackground(PANEL_0);
		head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, FRAME_OUT), new EmptyBorder(8, 10, 8, 10)));
		heading.setFont(FontManager.getRunescapeBoldFont());
		back.setIcon(new Glyph(Glyph.BACK, ORANGE));
		back.getAccessibleContext().setAccessibleName("Back to your bingos");
		back.setPreferredSize(new Dimension(18, 18));
		back.addActionListener(e -> leave());
		head.add(back, BorderLayout.WEST);
		head.add(heading, BorderLayout.CENTER);
		head.add(sizeLabel, BorderLayout.EAST);
		add(head);
		tabs.setOpaque(false);
		tabs.setBorder(new EmptyBorder(6, 6, 0, 6));
		add(tabs);
		status.setBorder(new EmptyBorder(6, 10, 0, 10));
		add(status);
		body.setOpaque(false);
		body.setBorder(new EmptyBorder(6, 10, 0, 10));
		add(body);
		render();
	}

	// the site's panel: brown with its tile texture
	@Override
	protected void paintComponent(Graphics g)
	{
		g.setColor(PANEL);
		g.fillRect(0, 0, getWidth(), getHeight());
		for (int y = 0; y < getHeight(); y += BACKDROP.getHeight())
		{
			for (int x = 0; x < getWidth(); x += BACKDROP.getWidth())
			{
				g.drawImage(BACKDROP, x, y, null);
			}
		}
	}

	// The sidebar icon: a small bingo board
	static BufferedImage icon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		for (int i = 0; i < 9; i++)
		{
			g.setColor(i == 4 ? GOLD : TAN);
			g.fillRect(1 + (i % 3) * 5, 1 + (i / 3) * 5, 4, 4);
		}
		g.dispose();
		return img;
	}

	private static Form blank()
	{
		Form f = new Form();
		f.start = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.DAYS).toEpochMilli();
		f.end = f.start + 14 * 24 * Team.HOUR;
		for (int i = 0; i < 25; i++)
		{
			f.tiles.add(null);
		}
		SelfTeam t = new SelfTeam();
		t.name = "Team 1";
		f.teams.add(t);
		return f;
	}

	@Override
	public void onActivate()
	{
		if (!editing && "Bingos".equals(tab) && bingos == null && !worker().isEmpty() && !owner().isEmpty())
		{
			loadList();
		}
		if (!editing && "Review".equals(tab) && !proofOpen)
		{
			loadReview();
		}
		refresh.start();
	}

	@Override
	public void onDeactivate()
	{
		refresh.stop();
	}

	private String worker()
	{
		return config.selfWorker().trim().replaceAll("/+$", "");
	}

	private String owner()
	{
		return config.selfOwner();
	}

	// Rebuilds the panel for where it is (after a click; typing changes the form without a rebuild, so focus stays)
	void render()
	{
		boolean owns = config.selfPanel();
		boolean captain = !config.captainPass().isBlank();
		List<String> names = editing ? List.of("Bingo", "Tiles", "Teams")
			: owns && captain ? List.of("Bingos", "Review") : captain ? List.of("Review") : List.of("Bingos");
		if (!names.contains(tab))
		{
			tab = names.get(0);
		}
		back.setVisible(editing);
		heading.setText(editing ? (form.name.isEmpty() ? "New bingo" : form.name) : "Review".equals(tab) ? "Captain" : "Your bingos");
		sizeLabel.setText(editing && bingoId != null ? form.size + " x " + form.size : "");
		tabs.removeAll();
		tabs.setVisible(names.size() > 1);
		tabs.setLayout(new GridLayout(1, names.size(), 3, 0));
		for (String n : names)
		{
			tabs.add(choice(n, n.equals(tab), 30, () ->
			{
				tab = n;
				status.setText("");
				saveStatus.setText("");
				if ("Review".equals(n))
				{
					proofOpen = false;
					loadReview();
				}
				if ("Bingos".equals(n) && bingos == null && !worker().isEmpty() && !owner().isEmpty())
				{
					loadList();
				}
			}));
		}
		body.removeAll();
		switch (tab)
		{
			case "Bingo":
				bingoView();
				break;
			case "Tiles":
				tilesView();
				saveButton();
				break;
			case "Teams":
				teamsView();
				saveButton();
				break;
			case "Review":
				if (proofOpen)
				{
					proofView();
				}
				else
				{
					reviewView();
				}
				break;
			default:
				listView();
		}
		revalidate();
		repaint();
	}

	// ---- the start: the owner's bingos

	private void listView()
	{
		JTextField url = text(worker(), v -> { });
		JPasswordField pass = new JPasswordField(owner());
		style(pass);
		FocusAdapter linked = new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				String u = url.getText().trim(), p = new String(pass.getPassword());
				if (!u.equals(worker()) || !p.equals(owner()))
				{
					configManager.setConfiguration(CabbingoConfig.GROUP, "selfWorker", u);
					configManager.setConfiguration(CabbingoConfig.GROUP, "selfOwner", p);
					bingos = null;
					namesAsked = false;
					if (!u.isEmpty() && !p.isEmpty())
					{
						loadList();
					}
				}
			}
		};
		url.addFocusListener(linked);
		pass.addFocusListener(linked);
		body.add(field("Worker URL", url));
		body.add(field("Owner passcode", pass));
		if (bingos == null)
		{
			return;
		}
		body.add(bold("Your bingos", GOLD));
		for (Summary b : bingos)
		{
			JPanel card = new JPanel(new BorderLayout());
			card.setBackground(PANEL_2);
			card.setBorder(frame(0));
			Btn open = new Btn("", Color.WHITE, null, null);
			open.setLayout(new DynamicGridLayout(0, 1, 0, 2));
			open.setBorder(new EmptyBorder(7, 8, 7, 8));
			Text name = new Text(b.name, Color.WHITE);
			name.setFont(FontManager.getRunescapeBoldFont());
			open.add(name);
			open.add(new Text(b.size + " x " + b.size + " · " + (b.teams == 1 ? "1 team" : b.teams + " teams"), TAN));
			open.add(new Text(day(b.start) + " – " + day(b.end), TAN));
			open.addActionListener(e -> open(b.id));
			card.add(open, BorderLayout.CENTER);
			Btn trash = new Btn("", RED, null, null);
			trash.setIcon(new Glyph(Glyph.TRASH, RED));
			trash.getAccessibleContext().setAccessibleName("Delete " + b.name);
			trash.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, EDGE));
			trash.setPreferredSize(new Dimension(36, 0));
			trash.addActionListener(e ->
			{
				if (confirm("Delete " + b.name + "? Its Team URLs stop working and all its progress is deleted.", "Delete bingo"))
				{
					call(owned(worker() + "/self/" + b.id).delete(), JsonObject.class, ok -> loadList());
				}
			});
			card.add(trash, BorderLayout.EAST);
			body.add(card);
		}
		Btn create = main("New bingo");
		create.addActionListener(e -> edit(null, blank(), new ArrayList<>()));
		body.add(create);
	}

	private static String day(long at)
	{
		return DAY.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()));
	}

	// Back to the list; changes not saved yet are asked about first
	private void leave()
	{
		if (!gson.toJson(form).equals(savedJson) && !confirm("Leave without saving your changes?", "Back"))
		{
			return;
		}
		editing = false;
		tab = "Bingos";
		status.setText("");
		loadList();
		render();
	}

	// ---- the editor

	private void bingoView()
	{
		body.add(field("Name", text(form.name, v ->
		{
			form.name = v.trim();
			heading.setText(form.name.isEmpty() ? "New bingo" : form.name);
		})));
		JComboBox<String> size = new JComboBox<>(new String[]{"3 x 3", "4 x 4", "5 x 5", "6 x 6", "7 x 7"});
		style(size);
		size.setSelectedIndex(form.size - 3);
		size.addActionListener(e -> resize(size.getSelectedIndex() + 3));
		body.add(field("Board size", size));
		body.add(field("Start", date(form.start, v -> form.start = v)));
		body.add(field("End", date(form.end, v -> form.end = v)));
		body.add(new Text("dd/mm/yyyy hh:mm", TAN));
		body.add(pair(field("Row bonus", number(form.rowBonus, v -> form.rowBonus = v)), field("Column bonus", number(form.columnBonus, v -> form.columnBonus = v))));
		// XP tiles: the plugin counts them, or TempleOSRS when the bingo has a competition (never both, the worker)
		JLabel note = new JLabel("<html>Not required. More precise: TempleOSRS also counts XP gained on mobile or without the plugin.</html>");
		note.setForeground(TAN);
		note.setFont(FontManager.getRunescapeSmallFont());
		body.add(note);
		body.add(field("TempleOSRS competition ID", text(form.temple == null ? "" : form.temple, v -> form.temple = v.trim().isEmpty() ? null : v.trim())));
		body.add(field("Skilling screenshots in Discord", onOff(form.skillShots, v -> form.skillShots = v)));
		body.add(field("KC screenshots in Discord", onOff(form.kcShots, v -> form.kcShots = v)));
		saveButton();
		if (links.isEmpty())
		{
			return;
		}
		body.add(bold("Team links", GOLD));
		for (Link l : links)
		{
			JPanel card = card();
			Text name = new Text(form.teams.stream().filter(t -> l.teamId.equals(t.id)).map(t -> t.name).findFirst().orElse(""), Color.WHITE);
			name.setFont(FontManager.getRunescapeBoldFont());
			card.add(name);
			card.add(new Text("Team URL", ORANGE));
			card.add(copyRow(worker() + "/plugin/" + l.key));
			card.add(new Text("Captain passcode", ORANGE));
			card.add(copyRow(l.passcode));
			body.add(card);
		}
	}

	// A new size keeps each tile at its row and column
	private void resize(int n)
	{
		if (n == form.size)
		{
			return;
		}
		List<Tile> tiles = new ArrayList<>();
		for (int i = 0; i < n * n; i++)
		{
			int r = i / n, c = i % n;
			tiles.add(r < form.size && c < form.size ? form.tiles.get(r * form.size + c) : null);
		}
		form.size = n;
		form.tiles = tiles;
		tileAt = -1;
	}

	// on every editor tab, so nobody takes a change for saved (user, 2026-11-15)
	private void saveButton()
	{
		Btn save = main(bingoId == null ? "Create bingo" : "Save changes");
		save.addActionListener(e -> save());
		body.add(save);
		saveStatus.setBorder(new EmptyBorder(2, 0, 0, 0));
		body.add(saveStatus);
	}

	private void tilesView()
	{
		int n = form.size;
		JPanel board = new JPanel(new GridLayout(n, n, 3, 3));
		board.setOpaque(false);
		grid = new Btn[n * n];
		for (int i = 0; i < n * n; i++)
		{
			int at = i;
			Tile t = form.tiles.get(i);
			boolean picked = i == tileAt;
			Btn b = new Btn(gridLabel(i), t != null && !t.title.isEmpty() || picked ? Color.WHITE : DIM, picked ? SURFACE_HI : SURFACE, EDGE);
			b.outline = picked ? GOLD : null;
			b.setFont(FontManager.getRunescapeSmallFont());
			b.setPreferredSize(new Dimension(0, 42));
			picture(t, b);
			b.addActionListener(e ->
			{
				tileAt = at;
				render();
			});
			grid[i] = b;
			board.add(b);
		}
		body.add(board);
		if (tileAt < 0)
		{
			return;
		}
		if (form.tiles.get(tileAt) == null)
		{
			form.tiles.set(tileAt, new Tile());
		}
		Tile t = form.tiles.get(tileAt);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(bold("Tile " + (tileAt + 1), GOLD), BorderLayout.WEST);
		Btn clear = new Btn("Clear tile", RED, null, null);
		clear.addActionListener(e ->
		{
			form.tiles.set(tileAt, null);
			tileAt = -1;
			render();
		});
		top.add(clear, BorderLayout.EAST);
		body.add(top);
		JPanel types = new JPanel(new GridLayout(2, 2, 3, 3));
		types.setOpaque(false);
		for (int k = 0; k < TYPES.length; k++)
		{
			String type = TYPES[k];
			types.add(choice(TYPE_NAMES[k], type.equals(t.type), 28, () -> t.type = type));
		}
		body.add(types);
		body.add(field("Title", text(t.title, v ->
		{
			t.title = v.trim();
			grid[tileAt].setText(gridLabel(tileAt));
		})));
		if ("xp".equals(t.type))
		{
			skillBox(t, XP_SKILLS, "Overall");
		}
		else if ("kc".equals(t.type))
		{
			JTextField monster = text(t.monster == null ? "" : t.monster, v -> t.monster = v.trim());
			Suggest.attach(monster, () -> monsters, monster::setText);
			body.add(field("Monster", monster));
		}
		else
		{
			if ("skill".equals(t.type))
			{
				skillBox(t, SKILLS, SKILLS[SKILLS.length - 1]);
			}
			if (t.items == null)
			{
				t.items = new ArrayList<>();
			}
			body.add(new Text("Items", ORANGE));
			for (String s : new ArrayList<>(t.items))
			{
				body.add(itemRow(s, () -> t.items.remove(s)));
			}
			body.add(addRow("Item name", () -> itemNames, v ->
			{
				if (t.items.stream().anyMatch(i -> i.equalsIgnoreCase(v)))
				{
					return v + " is on this tile already";
				}
				t.items.add(v);
				return null;
			}));
		}
		body.add(pair(field("kc".equals(t.type) ? "Kills" : "xp".equals(t.type) ? "XP" : "Count", number(t.count, v -> t.count = v)), field("Points", number(t.points, v -> t.points = v))));
	}

	// the tile's Skill dropdown; a skill not in skills becomes fallback
	private void skillBox(Tile t, String[] skills, String fallback)
	{
		JComboBox<String> skill = new JComboBox<>(skills);
		style(skill);
		if (t.skill == null || !List.of(skills).contains(t.skill))
		{
			t.skill = fallback;
		}
		skill.setSelectedItem(t.skill);
		skill.addActionListener(e -> t.skill = (String) skill.getSelectedItem());
		body.add(field("Skill", skill));
	}

	private String gridLabel(int i)
	{
		Tile t = form.tiles.get(i);
		return t == null || t.title.isEmpty() ? String.valueOf(i + 1) : t.title.split(" ")[0];
	}

	// A tile's picture on a button, from the game: the boss's or skill's hiscores sprite, a Drops tile's first item
	private void picture(Tile t, JButton b)
	{
		String img = t == null ? null : img(t);
		if (img == null)
		{
			return;
		}
		HiscoreSkill hs = BoardOverlay.hiscore(img);
		if (hs != null)
		{
			spriteManager.getSpriteAsync(hs.getSpriteId(), 0, s -> SwingUtilities.invokeLater(() ->
			{
				b.setIcon(new ImageIcon(s));
				b.setText("");
			}));
		}
		else
		{
			itemManager.getImage(Integer.parseInt(img.replaceAll("\\D", ""))).addTo(b);
			b.setText("");
		}
	}

	// Each tile's picture on the in-game board: a Kill count tile the boss's hiscores picture, a Skilling tile the
	// skill's, a Drops tile its first item's (the site's item ids). None: the tile shows its title.
	private String img(Tile t)
	{
		String name = "kc".equals(t.type) ? t.monster : "skill".equals(t.type) || "xp".equals(t.type) ? t.skill : null;
		if (name != null && HISCORE_NAME.matcher(name.trim()).matches() && BoardOverlay.hiscore("hiscore:" + name.trim()) != null)
		{
			return "hiscore:" + name.trim();
		}
		if ("drops".equals(t.type) && t.items != null)
		{
			return t.items.stream().map(i -> itemIds.get(i.toLowerCase(Locale.ROOT))).filter(Objects::nonNull).findFirst()
				.map(id -> "item-images/" + id + ".png").orElse(null);
		}
		return null;
	}

	private void teamsView()
	{
		if (teamAt >= form.teams.size())
		{
			teamAt = 0;
		}
		for (int i = 0; i < form.teams.size(); i++)
		{
			SelfTeam t = form.teams.get(i);
			if (i != teamAt)
			{
				int at = i;
				Btn b = new Btn("", Color.WHITE, PANEL_2, null);
				b.setLayout(new BorderLayout());
				b.setBorder(BorderFactory.createCompoundBorder(frame(0), new EmptyBorder(0, 8, 0, 8)));
				b.add(new Text(t.name, Color.WHITE), BorderLayout.WEST);
				b.add(new Text(t.players.size() == 1 ? "1 player" : t.players.size() + " players", TAN), BorderLayout.EAST);
				b.setPreferredSize(new Dimension(0, 32));
				b.addActionListener(e ->
				{
					teamAt = at;
					render();
				});
				body.add(b);
				continue;
			}
			JPanel card = card();
			card.add(field("Team name", text(t.name, v -> t.name = v.trim())));
			card.add(new Text("Players", ORANGE));
			// (rows straight in the card: DynamicGridLayout gives a panel of rows only their own width, none)
			for (String p : new ArrayList<>(t.players))
			{
				card.add(itemRow(p, () -> t.players.remove(p)));
			}
			card.add(addRow("RuneScape name", null, v ->
			{
				String name = v.length() > 12 ? v.substring(0, 12) : v;
				SelfTeam on = form.teams.stream().filter(o -> o.players.stream().anyMatch(n -> rsn(n).equals(rsn(name)))).findFirst().orElse(null);
				if (on != null)
				{
					return name + " is on " + on.name + " already";
				}
				t.players.add(name);
				return null;
			}));
			card.add(field("Discord webhook (optional)", text(t.webhook == null ? "" : t.webhook, v -> t.webhook = v.trim().isEmpty() ? null : v.trim())));
			Btn remove = new Btn("Remove team", RED, null, null);
			remove.setHorizontalAlignment(SwingConstants.LEFT);
			remove.addActionListener(e ->
			{
				if (confirm("Remove " + t.name + "? Its Team URL stops working when you save.", "Remove team"))
				{
					form.teams.remove(t);
					teamAt = 0;
					render();
				}
			});
			card.add(remove);
			body.add(card);
		}
		Btn add = new Btn("Add team", Color.WHITE, SURFACE, EDGE_HI);
		add.setPreferredSize(new Dimension(0, 32));
		add.addActionListener(e ->
		{
			SelfTeam t = new SelfTeam();
			t.name = "Team " + (form.teams.size() + 1);
			form.teams.add(t);
			teamAt = form.teams.size() - 1;
			render();
		});
		body.add(add);
	}

	// ---- the captain

	private void reviewView()
	{
		Review r = review;
		if (r == null || r.pending == null)
		{
			return;
		}
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(bold(r.team, GOLD), BorderLayout.WEST);
		top.add(new Text(r.pending.size() + " waiting", ORANGE), BorderLayout.EAST);
		body.add(top);
		if (r.tiles != null && !r.tiles.isEmpty())
		{
			Btn proof = new Btn("Add proof", GOLD, SURFACE, EDGE_HI);
			proof.setPreferredSize(new Dimension(0, 32));
			proof.addActionListener(e ->
			{
				proofOpen = true;
				ProofTile first = r.tiles.stream().filter(t -> !t.done).findFirst().orElse(r.tiles.get(0));
				proofTile = first.id;
				proofPlayer = r.players == null || r.players.isEmpty() ? null : r.players.get(0);
				proofLink = "";
				proofCounts.clear();
				status.setText("");
				render();
			});
			body.add(proof);
		}
		for (Drop d : r.pending)
		{
			JPanel card = card();
			JPanel line = new JPanel(new BorderLayout());
			line.setOpaque(false);
			Text what = new Text(items(d), Color.WHITE);
			what.setFont(FontManager.getRunescapeBoldFont());
			line.add(what, BorderLayout.CENTER);
			line.add(new Text(ago(d.created), TAN), BorderLayout.EAST);
			card.add(line);
			card.add(new Text(d.rsn, TAN));
			List<String> one = new ArrayList<>();
			for (int i = 0; i < d.items.size(); i++)
			{
				List<TileRef> fits = d.tiles.get(i);
				if (fits.size() == 1)
				{
					one.add(fits.get(0).title);
				}
				else if (fits.size() > 1)
				{
					String key = d.id + ":" + d.items.get(i).name;
					JComboBox<String> pick = new JComboBox<>();
					style(pick);
					pick.addItem("Pick a tile");
					fits.forEach(f -> pick.addItem(f.title));
					fits.stream().filter(f -> f.id.equals(picks.get(key))).findFirst().ifPresent(f -> pick.setSelectedItem(f.title));
					pick.addActionListener(e ->
					{
						int k = pick.getSelectedIndex();
						if (k > 0)
						{
							picks.put(key, fits.get(k - 1).id);
						}
						else
						{
							picks.remove(key);
						}
					});
					card.add(pick);
				}
			}
			if (!one.isEmpty())
			{
				JPanel tile = new JPanel(new BorderLayout(4, 0));
				tile.setOpaque(false);
				tile.add(new Text("Tile:", ORANGE), BorderLayout.WEST);
				tile.add(new Text(one.stream().distinct().collect(Collectors.joining(", ")), Color.WHITE), BorderLayout.CENTER);
				card.add(tile);
			}
			if (d.image != null)
			{
				Btn shot = new Btn("Screenshot", ORANGE, null, null);
				shot.setHorizontalAlignment(SwingConstants.LEFT);
				shot.addActionListener(e -> LinkBrowser.browse(d.image));
				card.add(shot);
			}
			JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
			buttons.setOpaque(false);
			Btn approve = new Btn("Approve", GREEN, SURFACE, EDGE_HI);
			approve.setPreferredSize(new Dimension(0, 28));
			approve.addActionListener(e -> decide(d, "approve"));
			Btn deny = new Btn("Deny", RED, SURFACE, EDGE_HI);
			deny.addActionListener(e -> decide(d, "deny"));
			buttons.add(approve);
			buttons.add(deny);
			card.add(buttons);
			body.add(card);
		}
		if (r.reviewed == null || r.reviewed.isEmpty())
		{
			return;
		}
		// one row per tile in board order, closed until clicked (the site's Drops by tile), "No tile" last
		body.add(bold("Reviewed", GOLD));
		Map<String, String> titles = new LinkedHashMap<>();
		(r.tiles == null ? List.<ProofTile>of() : r.tiles).forEach(t -> titles.put(t.id, t.title));
		titles.put("", "No tile");
		Map<String, List<Drop>> groups = new LinkedHashMap<>();
		titles.keySet().forEach(k -> groups.put(k, new ArrayList<>()));
		for (Drop d : r.reviewed)
		{
			groups.get(d.tileId != null && titles.containsKey(d.tileId) ? d.tileId : "").add(d);
		}
		groups.forEach((key, drops) ->
		{
			if (drops.isEmpty())
			{
				return;
			}
			boolean open = openGroups.contains(key);
			// (BorderLayout and GridLayout: DynamicGridLayout gives rows whose preferred width is 0 no width at all)
			JPanel card = new JPanel(new BorderLayout(0, 3));
			card.setBackground(PANEL_2);
			card.setBorder(frame(0));
			Btn head = new Btn("", Color.WHITE, null, null);
			head.setLayout(new BorderLayout(6, 0));
			head.setBorder(new EmptyBorder(0, 8, 0, 8));
			head.setPreferredSize(new Dimension(0, 32));
			head.getAccessibleContext().setAccessibleName(titles.get(key) + (open ? ", open" : ", closed"));
			JLabel arrow = new JLabel(new Glyph(open ? Glyph.OPEN : Glyph.CLOSED, ORANGE));
			head.add(arrow, BorderLayout.WEST);
			head.add(new Text(titles.get(key), Color.WHITE), BorderLayout.CENTER);
			head.add(new Text(String.valueOf(drops.size()), TAN), BorderLayout.EAST);
			head.addActionListener(e ->
			{
				if (!openGroups.remove(key))
				{
					openGroups.add(key);
				}
				render();
			});
			card.add(head, BorderLayout.NORTH);
			if (open)
			{
				JPanel rows = new JPanel(new GridLayout(0, 1, 0, 3));
				rows.setOpaque(false);
				rows.setBorder(new EmptyBorder(0, 6, 6, 6));
				for (Drop d : drops)
				{
					JPanel row = new JPanel(new BorderLayout(4, 0));
					row.setBackground(EDGE);
					row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE_HI), new EmptyBorder(0, 6, 0, 2)));
					row.setPreferredSize(new Dimension(0, 26));
					boolean ok = "approved".equals(d.status);
					row.add(new Text(items(d), Color.WHITE), BorderLayout.CENTER);
					JPanel end = new JPanel(new BorderLayout(6, 0));
					end.setOpaque(false);
					end.add(new Text(ok ? "Approved" : "Denied", ok ? GREEN : RED), BorderLayout.WEST);
					Btn undo = new Btn("Undo", ORANGE, null, null);
					undo.addActionListener(e -> decide(d, "undo"));
					end.add(undo, BorderLayout.EAST);
					row.add(end, BorderLayout.EAST);
					rows.add(row);
				}
				card.add(rows, BorderLayout.CENTER);
			}
			body.add(card);
		});
	}

	// A captain's proof for a player without the plugin: the tile, the player, a screenshot link and what it shows
	private void proofView()
	{
		Review r = review;
		ProofTile tile = r == null || r.tiles == null ? null : r.tiles.stream().filter(t -> t.id.equals(proofTile)).findFirst().orElse(null);
		if (tile == null)
		{
			proofOpen = false;
			reviewView();
			return;
		}
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(bold("Add proof", GOLD), BorderLayout.WEST);
		Btn cancel = new Btn("Cancel", ORANGE, null, null);
		cancel.addActionListener(e ->
		{
			proofOpen = false;
			status.setText("");
			render();
		});
		top.add(cancel, BorderLayout.EAST);
		body.add(top);
		JComboBox<String> tiles = new JComboBox<>();
		style(tiles);
		r.tiles.forEach(t -> tiles.addItem(t.title));
		tiles.setSelectedItem(tile.title);
		tiles.addActionListener(e ->
		{
			proofTile = r.tiles.get(tiles.getSelectedIndex()).id;
			proofCounts.clear();
			render();
		});
		body.add(field("Tile", tiles));
		if (r.players != null && !r.players.isEmpty())
		{
			JComboBox<String> player = new JComboBox<>(r.players.toArray(new String[0]));
			style(player);
			player.setSelectedItem(proofPlayer);
			player.addActionListener(e -> proofPlayer = (String) player.getSelectedItem());
			body.add(field("Player", player));
		}
		body.add(field("Screenshot link", text(proofLink, v -> proofLink = v.trim())));
		body.add(new Text("Obtained", ORANGE));
		List<String> items = tile.items == null ? List.of() : tile.items;
		for (String item : items)
		{
			proofCounts.putIfAbsent(item, items.size() == 1 ? 1 : 0);
			JPanel card = card();
			Text name = new Text(TOTAL.equals(item) ? "Kills" : GAINED.equals(item) ? "XP" : item, Color.WHITE);
			Integer id = itemIds.get(item.toLowerCase(Locale.ROOT));
			if (id != null)
			{
				itemManager.getImage(id).addTo(name);
			}
			card.add(name);
			JPanel line = new JPanel(new BorderLayout(4, 0));
			line.setOpaque(false);
			int got = tile.got == null ? 0 : tile.got.getOrDefault(item, 0);
			line.add(new Text("Has " + got + " / " + tile.count, TAN), BorderLayout.CENTER);
			JPanel step = new JPanel(new BorderLayout(4, 0));
			step.setOpaque(false);
			JTextField n = number(proofCounts.get(item), v -> proofCounts.put(item, Math.max(0, v)));
			n.setHorizontalAlignment(JTextField.CENTER);
			n.setPreferredSize(new Dimension(40, 26));
			Btn less = new Btn("-", Color.WHITE, SURFACE, EDGE_HI);
			less.getAccessibleContext().setAccessibleName("One less " + item);
			less.setPreferredSize(new Dimension(26, 26));
			less.addActionListener(e -> n.setText(String.valueOf(Math.max(0, proofCounts.get(item) - 1))));
			Btn more = new Btn("+", Color.WHITE, SURFACE, EDGE_HI);
			more.getAccessibleContext().setAccessibleName("One more " + item);
			more.setPreferredSize(new Dimension(26, 26));
			more.addActionListener(e -> n.setText(String.valueOf(proofCounts.get(item) + 1)));
			step.add(less, BorderLayout.WEST);
			step.add(n, BorderLayout.CENTER);
			step.add(more, BorderLayout.EAST);
			line.add(step, BorderLayout.EAST);
			card.add(line);
			body.add(card);
		}
		Btn add = main("Add proof");
		add.addActionListener(e -> addProof(tile));
		body.add(add);
	}

	private void addProof(ProofTile tile)
	{
		String base = captainWorker();
		if (base == null)
		{
			return;
		}
		JsonObject counts = new JsonObject();
		proofCounts.forEach((name, n) ->
		{
			if (n > 0 && tile.items != null && tile.items.contains(name))
			{
				counts.addProperty(name, n);
			}
		});
		JsonObject payload = new JsonObject();
		payload.addProperty("tileId", tile.id);
		payload.addProperty("link", proofLink);
		if (proofPlayer != null)
		{
			payload.addProperty("rsn", proofPlayer);
		}
		payload.add("counts", counts);
		status.setText("");
		call(captained(base + "/self/proof").post(RequestBody.create(JSON, gson.toJson(payload))), JsonObject.class, ok ->
		{
			proofOpen = false;
			status.setForeground(GREEN);
			status.setText("Proof added");
			loadReview();
		});
	}

	// RuneScape names compare without case, and with "_" and "-" as a space (rsnKey in models/bingo.ts)
	private static String rsn(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT).replaceAll("[_-]", " ");
	}

	private static String items(Drop d)
	{
		return d.items.stream().map(i -> i.quantity > 1 ? i.name + " x " + i.quantity : i.name).collect(Collectors.joining(", "));
	}

	private static String ago(long at)
	{
		long m = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
		return m < 60 ? m + "m" : m < 24 * 60 ? m / 60 + "h" : m / (24 * 60) + "d";
	}

	// ---- the worker

	private Request.Builder owned(String url)
	{
		return new Request.Builder().url(url).header("Authorization", "Bearer " + owner());
	}

	private Request.Builder captained(String url)
	{
		return new Request.Builder().url(url).header("Authorization", "Bearer " + config.captainPass().trim());
	}

	private void loadList()
	{
		if (worker().isEmpty() || owner().isEmpty())
		{
			return;
		}
		call(owned(worker() + "/self"), Listing.class, l ->
		{
			bingos = l.bingos;
			render();
		});
	}

	private void open(int id)
	{
		call(owned(worker() + "/self/" + id), Saved.class, s -> edit(s.id, s.form, s.links));
	}

	// the editor on this bingo (id null: a new one, not created yet)
	private void edit(Integer id, Form f, List<Link> l)
	{
		bingoId = id;
		form = f;
		links = l;
		savedJson = gson.toJson(form);
		tileAt = -1;
		teamAt = 0;
		editing = true;
		tab = "Bingo";
		status.setText("");
		loadNames(worker());
		render();
	}

	// the site's item and monster lists, once
	private void loadNames(String base)
	{
		if (namesAsked || base == null || base.isEmpty())
		{
			return;
		}
		namesAsked = true;
		call(new Request.Builder().url(base + "/self/names"), Names.class, n ->
		{
			if (n.items != null)
			{
				Map<String, Integer> ids = new HashMap<>();
				n.items.forEach((name, id) -> ids.put(name.toLowerCase(Locale.ROOT), id));
				itemIds = ids;
				itemNames = n.items.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
			}
			monsters = n.monsters == null ? List.of() : n.monsters;
			render();
		});
	}

	private void save()
	{
		saving = true; // its errors show under the button too
		if (worker().isEmpty() || owner().isEmpty() || HttpUrl.parse(worker()) == null)
		{
			fail("Fill in the worker URL and owner passcode");
			return;
		}
		// a slot left without a title and nothing on it is no tile
		Form send = gson.fromJson(gson.toJson(form), Form.class);
		send.tiles = send.tiles.stream()
			.map(t -> t == null || t.title.isEmpty() && (t.items == null || t.items.isEmpty()) && (t.monster == null || t.monster.isEmpty()) ? null : t)
			.collect(Collectors.toList());
		for (Tile t : send.tiles)
		{
			if (t != null)
			{
				// only what the tile's type uses
				t.items = "drops".equals(t.type) || "skill".equals(t.type) ? t.items : null;
				t.monster = "kc".equals(t.type) ? t.monster : null;
				t.skill = "skill".equals(t.type) || "xp".equals(t.type) ? t.skill : null;
				t.img = img(t);
			}
		}
		JsonObject payload = new JsonObject();
		payload.add("form", gson.toJsonTree(send));
		RequestBody json = RequestBody.create(JSON, gson.toJson(payload));
		Request.Builder req = bingoId == null ? owned(worker() + "/self").post(json) : owned(worker() + "/self/" + bingoId).put(json);
		status.setText("");
		saveStatus.setText("");
		call(req, Saved.class, s ->
		{
			saving = false;
			bingoId = s.id;
			form = s.form;
			links = s.links;
			savedJson = gson.toJson(form);
			bingos = null;
			saveStatus.setForeground(GREEN);
			saveStatus.setText("Saved");
			render();
		});
	}

	// The Team URL's worker, for a captain
	private String captainWorker()
	{
		HttpUrl url = team.url();
		if (url == null)
		{
			return null;
		}
		String s = url.toString();
		int at = s.indexOf("/plugin/");
		return at < 0 ? null : s.substring(0, at);
	}

	private void loadReview()
	{
		String base = captainWorker();
		if (base == null)
		{
			fail("Paste your Team URL in the plugin's settings");
			return;
		}
		loadNames(base);
		call(captained(base + "/self/review"), Review.class, r ->
		{
			if (review != null && gson.toJson(r).equals(gson.toJson(review)))
			{
				return;
			}
			review = r;
			render();
		});
	}

	private void decide(Drop d, String action)
	{
		String base = captainWorker();
		if (base == null)
		{
			return;
		}
		JsonObject payload = new JsonObject();
		payload.addProperty("id", d.id);
		payload.addProperty("action", action);
		if ("approve".equals(action))
		{
			JsonObject tiles = new JsonObject();
			d.items.forEach(i ->
			{
				String id = picks.get(d.id + ":" + i.name);
				if (id != null)
				{
					tiles.addProperty(i.name, id);
				}
			});
			payload.add("tiles", tiles);
		}
		status.setText("");
		call(captained(base + "/self/review").post(RequestBody.create(JSON, gson.toJson(payload))), JsonObject.class, ok -> loadReview());
	}

	// On OkHttp's threads; the answer (or the worker's error) back on Swing's
	private <T> void call(Request.Builder req, Class<T> type, Consumer<T> done)
	{
		Request request;
		try
		{
			request = req.build();
		}
		catch (IllegalArgumentException e)
		{
			fail("Not a URL");
			return;
		}
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: self-hosted call failed", e);
				SwingUtilities.invokeLater(() -> fail("No answer from the worker"));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					String text = response.body() == null ? "" : response.body().string();
					if (!response.isSuccessful())
					{
						String error;
						try
						{
							JsonObject o = gson.fromJson(text, JsonObject.class);
							error = o != null && o.has("error") ? o.get("error").getAsString() : "Error " + response.code();
						}
						catch (RuntimeException e)
						{
							error = "Error " + response.code();
						}
						String msg = error;
						SwingUtilities.invokeLater(() -> fail(msg));
						return;
					}
					T value = gson.fromJson(text, type);
					SwingUtilities.invokeLater(() -> done.accept(value));
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Cabbingo: self-hosted answer unreadable", e);
					SwingUtilities.invokeLater(() -> fail("Not a Cabbingo worker"));
				}
			}
		});
	}

	private void fail(String message)
	{
		Text to = saving ? saveStatus : status;
		saving = false;
		to.setForeground(RED);
		to.setText("<html>" + message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</html>");
		revalidate();
	}

	private boolean confirm(String message, String title)
	{
		return JOptionPane.showConfirmDialog(this, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
	}

	// ---- controls in the site's look

	// the site's .osrs-border: a light line inside a black one
	private static Border frame(int pad)
	{
		return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(FRAME_OUT),
			BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(FRAME), new EmptyBorder(pad, pad, pad, pad)));
	}

	private static JPanel card()
	{
		JPanel c = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		c.setBackground(PANEL_2);
		c.setBorder(frame(7));
		return c;
	}

	private static Btn main(String text)
	{
		Btn b = new Btn(text, Color.WHITE, null, null);
		b.image = BUTTON;
		b.setFont(FontManager.getRunescapeBoldFont());
		b.setPreferredSize(new Dimension(0, 40));
		return b;
	}

	private static Text bold(String text, Color color)
	{
		Text t = new Text(text, color);
		t.setFont(FontManager.getRunescapeBoldFont());
		return t;
	}

	private static void style(JTextField f)
	{
		f.setBackground(EDGE);
		f.setForeground(Color.WHITE);
		f.setCaretColor(Color.WHITE);
		f.setFont(FontManager.getRunescapeFont());
		Border idle = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE_HI), new EmptyBorder(0, 6, 0, 6));
		Border on = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(GOLD), new EmptyBorder(0, 6, 0, 6));
		f.setBorder(idle);
		f.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				f.setBorder(on);
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				f.setBorder(idle);
			}
		});
		f.setPreferredSize(new Dimension(0, 28));
	}

	private static void style(JComboBox<?> c)
	{
		c.setBackground(EDGE);
		c.setForeground(Color.WHITE);
		c.setFont(FontManager.getRunescapeFont());
		c.setBorder(BorderFactory.createLineBorder(EDGE_HI));
		c.setPreferredSize(new Dimension(0, 28));
	}

	private static JPanel field(String name, JComponent input)
	{
		JPanel p = new JPanel(new BorderLayout(0, 3));
		p.setOpaque(false);
		p.add(new Text(name, ORANGE), BorderLayout.NORTH);
		p.add(input, BorderLayout.CENTER);
		return p;
	}

	// On / Off, drawn like the tile type buttons
	private JPanel onOff(boolean value, Consumer<Boolean> set)
	{
		JPanel row = new JPanel(new GridLayout(1, 2, 3, 3));
		row.setOpaque(false);
		for (boolean v : new boolean[]{true, false})
		{
			row.add(choice(v ? "On" : "Off", value == v, 28, () -> set.accept(v)));
		}
		return row;
	}

	// a button of a pick-one row (tabs, tile types, On / Off), gold while picked; a click picks it and redraws the panel
	private Btn choice(String label, boolean on, int height, Runnable picked)
	{
		Btn b = new Btn(label, on ? GOLD : Color.WHITE, on ? SURFACE_HI : SURFACE, on ? GOLD : EDGE);
		b.setPreferredSize(new Dimension(0, height));
		b.addActionListener(e ->
		{
			picked.run();
			render();
		});
		return b;
	}

	private static JPanel pair(JComponent a, JComponent b)
	{
		JPanel p = new JPanel(new GridLayout(1, 2, 8, 0));
		p.setOpaque(false);
		p.add(a);
		p.add(b);
		return p;
	}

	// a text field whose every change goes to the form
	private static JTextField text(String value, Consumer<String> changed)
	{
		JTextField f = new JTextField(value);
		style(f);
		f.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				changed.accept(f.getText());
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				changed.accept(f.getText());
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				changed.accept(f.getText());
			}
		});
		return f;
	}

	private static JTextField number(int value, Consumer<Integer> changed)
	{
		return text(String.valueOf(value), v ->
		{
			try
			{
				changed.accept(Integer.parseInt(v.trim()));
			}
			catch (NumberFormatException e)
			{
				// (kept as it was until it is a number again)
			}
		});
	}

	// "01/12/2026 18:00" in the player's own time zone
	private static JTextField date(long value, Consumer<Long> changed)
	{
		return text(DATE.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneId.systemDefault())), v ->
		{
			try
			{
				changed.accept(LocalDateTime.parse(v.trim(), DATE).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
			}
			catch (DateTimeParseException e)
			{
				// (kept as it was until it is a date again)
			}
		});
	}

	private JPanel copyRow(String value)
	{
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setOpaque(false);
		JTextField f = new JTextField(value);
		style(f);
		f.setEditable(false);
		p.add(f, BorderLayout.CENTER);
		Btn copy = new Btn("Copy", Color.WHITE, SURFACE, EDGE_HI);
		copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null));
		p.add(copy, BorderLayout.EAST);
		return p;
	}

	private JPanel itemRow(String name, Runnable remove)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(EDGE);
		row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE_HI), new EmptyBorder(0, 6, 0, 2)));
		row.setPreferredSize(new Dimension(0, 26));
		row.add(new Text(name, Color.WHITE), BorderLayout.CENTER);
		Btn x = new Btn("x", RED, null, null);
		x.getAccessibleContext().setAccessibleName("Remove " + name);
		x.addActionListener(e ->
		{
			remove.run();
			render();
		});
		row.add(x, BorderLayout.EAST);
		return row;
	}

	// a field and Add (Enter or a picked suggestion adds too); added answers why a name was not added, or null
	private JPanel addRow(String placeholder, Supplier<List<String>> suggestions, Function<String, String> added)
	{
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.setOpaque(false);
		JTextField f = new JTextField();
		style(f);
		f.getAccessibleContext().setAccessibleName(placeholder);
		p.add(f, BorderLayout.CENTER);
		Btn add = new Btn("Add", Color.WHITE, SURFACE, EDGE_HI);
		Runnable go = () ->
		{
			String v = f.getText().trim();
			if (v.isEmpty())
			{
				return;
			}
			String error = added.apply(v);
			if (error != null)
			{
				fail(error);
				return;
			}
			status.setText("");
			render();
		};
		add.addActionListener(e -> go.run());
		f.addActionListener(e -> go.run());
		if (suggestions != null)
		{
			Suggest.attach(f, suggestions, v ->
			{
				f.setText(v);
				go.run();
			});
		}
		p.add(add, BorderLayout.EAST);
		return p;
	}

	// A label with the site's black text shadow (one line; cut with "..." when too long)
	static class Text extends JLabel
	{
		Text(String text, Color color)
		{
			super(text);
			setForeground(color);
			setFont(FontManager.getRunescapeFont());
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			String s = getText();
			if (s == null || s.startsWith("<html>"))
			{
				super.paintComponent(g0);
				return;
			}
			Graphics2D g = (Graphics2D) g0.create();
			Insets in = getInsets();
			int x = in.left;
			Icon icon = getIcon();
			if (icon != null)
			{
				icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
				x += icon.getIconWidth() + getIconTextGap();
			}
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			String t = BoardOverlay.fit(g, s, getWidth() - in.right - x);
			if (getHorizontalAlignment() == SwingConstants.RIGHT || getHorizontalAlignment() == SwingConstants.TRAILING)
			{
				x = getWidth() - in.right - fm.stringWidth(t);
			}
			int y = in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent();
			BoardOverlay.shadowed(g, t, x, y, getForeground(), 1);
			g.dispose();
		}
	}

	// A button in the site's look: a fill and an edge (lighter on hover), the stone button picture for the main ones,
	// a gold outline for the picked one, shadowed text and an optional icon
	static class Btn extends JButton
	{
		Color fill;
		Color line;
		Color outline;
		BufferedImage image;

		Btn(String text, Color color, Color fill, Color line)
		{
			super(text);
			this.fill = fill;
			this.line = line;
			setForeground(color);
			setFont(FontManager.getRunescapeFont());
			setContentAreaFilled(false);
			setFocusPainted(false);
			setBorderPainted(false);
			setOpaque(false);
			setBorder(new EmptyBorder(0, 6, 0, 6));
			setRolloverEnabled(true);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			int w = getWidth(), h = getHeight();
			boolean hover = getModel().isRollover() || getModel().isPressed();
			if (image != null)
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(image, 0, 0, w, h, null);
			}
			else if (fill != null)
			{
				g.setColor(hover ? SURFACE_HI : fill);
				g.fillRect(0, 0, w, h);
			}
			if (line != null)
			{
				g.setColor(line);
				g.drawRect(0, 0, w - 1, h - 1);
			}
			if (outline != null)
			{
				g.setColor(outline);
				g.setStroke(new BasicStroke(2));
				g.drawRect(1, 1, w - 2, h - 2);
			}
			Icon icon = getIcon();
			if (icon != null)
			{
				icon.paintIcon(this, g, (w - icon.getIconWidth()) / 2, (h - icon.getIconHeight()) / 2);
			}
			// laid-out children (a card's lines) paint themselves
			String s = getText();
			if (s != null && !s.isEmpty())
			{
				g.setFont(getFont());
				FontMetrics fm = g.getFontMetrics();
				Insets in = getInsets();
				String t = BoardOverlay.fit(g, s, w - in.left - in.right);
				int x = getHorizontalAlignment() == SwingConstants.LEFT ? in.left : (w - fm.stringWidth(t)) / 2;
				int y = (h - fm.getHeight()) / 2 + fm.getAscent();
				Color color = hover && image == null && fill == null && !Color.WHITE.equals(getForeground()) ? GOLD : getForeground();
				BoardOverlay.shadowed(g, t, x, y, image != null && hover ? GOLD : color, image != null ? 2 : 1);
			}
			g.dispose();
		}

		@Override
		public Dimension getPreferredSize()
		{
			if (isPreferredSizeSet() || getComponentCount() > 0)
			{
				return super.getPreferredSize();
			}
			FontMetrics fm = getFontMetrics(getFont());
			Insets in = getInsets();
			int text = getText() == null ? 0 : fm.stringWidth(getText());
			int icon = getIcon() == null ? 0 : getIcon().getIconWidth();
			return new Dimension(Math.max(text, icon) + in.left + in.right + 2, 26);
		}
	}

	// Drawn icons: the Back chevron, the delete trashcan, a Reviewed row's closed and open arrow
	static class Glyph implements Icon
	{
		static final int BACK = 0, TRASH = 1, CLOSED = 2, OPEN = 3;
		private final int kind;
		private final Color color;

		Glyph(int kind, Color color)
		{
			this.kind = kind;
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g0, int x, int y)
		{
			Graphics2D g = (Graphics2D) g0.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.translate(x, y);
			g.setColor(color);
			if (kind == BACK)
			{
				g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawPolyline(new int[]{11, 5, 11}, new int[]{3, 9, 15}, 3);
			}
			else if (kind == CLOSED || kind == OPEN)
			{
				g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				if (kind == CLOSED)
				{
					g.drawPolyline(new int[]{6, 11, 6}, new int[]{4, 9, 14}, 3);
				}
				else
				{
					g.drawPolyline(new int[]{4, 9, 14}, new int[]{6, 11, 6}, 3);
				}
			}
			else
			{
				g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.drawLine(2, 4, 16, 4);
				g.drawRect(4, 4, 10, 12);
				g.drawLine(7, 4, 7, 2);
				g.drawLine(7, 2, 11, 2);
				g.drawLine(11, 2, 11, 4);
				g.drawLine(7, 8, 7, 13);
				g.drawLine(11, 8, 11, 13);
			}
			g.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 18;
		}

		@Override
		public int getIconHeight()
		{
			return 18;
		}
	}
}
