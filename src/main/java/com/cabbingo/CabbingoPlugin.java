package com.cabbingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.WidgetNode;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Sends every kill, PvP kill and pet of a bingo player to their Cabbingo team. The server counts it for the
 * team's gp lists and its tiles, and passes it on to the team's Discord channel. A screenshot goes along only
 * when it matters: a tile's item, a pet, a big drop, or a drop a tile counts the value of.
 */
@Slf4j
@PluginDescriptor(
	name = "Cabbingo",
	description = "Sends your bingo drops, kills and pets to your Cabbingo team",
	tags = {"bingo", "clan", "loot", "discord"}
)
public class CabbingoPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");
	private static final float JPEG_QUALITY = 0.85f;
	private static final long HOUR = 60 * 60 * 1000L;
	private static final String[] PET_MESSAGES = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed",
	};
	private static final String COLLECTION_LOG = "New item added to your collection log: ";

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private CabbingoConfig config;
	@Inject private ItemManager itemManager;
	@Inject private DrawManager drawManager;
	@Inject private InfoBoxManager infoBoxManager;
	@Inject private OkHttpClient http;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;
	@Inject private ConfigManager configManager;
	@Inject private AudioPlayer audioPlayer;

	// What the team wants, from the server. Null until it answered (no key, a wrong key, offline).
	private volatile Wants wants;
	// The team's live line (the server's TeamPush): "finished" when the team finishes a side, so a teammate's popup comes
	// at once (user, 2026-11-02). Open while logged in during the bingo; the minute poll stays for when it is down.
	private OkHttpClient pushHttp;
	private WebSocket push;
	private long pushAgain; // the next try after it dropped or could not open
	private long pushWait = 60_000; // doubles per failed try, up to half an hour
	// A pet message waits a few ticks for the collection log line that names the pet.
	private int petTicks = -1;
	private String petName;
	private boolean petDuplicate;
	// Skilling tiles: what the player gathered or made, sent every few minutes (SkillCounter). XP per skill as last seen,
	// the skills that gained XP this tick and last, and the backpack as it was and what it gained a tick ago.
	private final SkillCounter skills = new SkillCounter();
	// Kills of one source go out together, a minute after the first (KillBatch)
	private final KillBatch kills = new KillBatch();
	// Kill count tiles: the kills that kept to a tile's food rule (KillRules), and who made them
	private final KillRules killRules = new KillRules();
	private volatile String kcRsn;
	private final Map<String, Integer> skillXp = new HashMap<>();
	private Set<String> xpNow = new HashSet<>();
	private Set<String> xpBefore = new HashSet<>();
	private Map<Integer, Integer> backpack;
	private Map<String, Integer> backpackGained = new HashMap<>();
	private volatile String skillRsn;
	// Motherlode Mine sack and Blast Furnace dispenser (2026-11-02): ore taken from the sack and bars made in the furnace
	// come without XP in that tick, so they count from the game's own counters (as RuneLite's own plugins read them).
	private static final Set<Integer> MOTHERLODE = Set.of(14679, 14680, 14681, 14935, 14936, 14937, 15191, 15192, 15193);
	private static final int BLAST_FURNACE = 7757;
	private static final Map<Integer, String> FURNACE_BARS = Map.of(
		VarbitID.BLAST_FURNACE_BRONZE_BARS, "Bronze bar", VarbitID.BLAST_FURNACE_IRON_BARS, "Iron bar",
		VarbitID.BLAST_FURNACE_STEEL_BARS, "Steel bar", VarbitID.BLAST_FURNACE_MITHRIL_BARS, "Mithril bar",
		VarbitID.BLAST_FURNACE_ADAMANTITE_BARS, "Adamantite bar", VarbitID.BLAST_FURNACE_RUNITE_BARS, "Runite bar",
		VarbitID.BLAST_FURNACE_GOLD_BARS, "Gold bar", VarbitID.BLAST_FURNACE_SILVER_BARS, "Silver bar");
	private int sackBefore = -1;
	private int sackTicks;
	private final Map<Integer, Integer> barsBefore = new HashMap<>();
	// Herb patches (herb sack): the varbit of the herb patch last picked, the herb it had a tick ago, and the herb picked
	// on the last tick with Farming XP (checked a tick later, once the backpack had its chance to get it).
	private int herbVarbit = -1;
	private String herbBefore;
	private String herbOnXp;

	// For the TempleOSRS update on logout: who played and their total XP when the session started (-1: none yet).
	private String templeName;
	private long templeXp = -1;
	// The finished tile sides already known (by id); null until the first answer, so a login shows no old ones.
	private Set<String> finishedSeen;
	// when each side last got its popup: a post's X-Finished must not bring back one the live line's fetch already
	// showed while the post was on its way (the same tile popped up twice, 2026-11-10)
	private final Map<String, Long> poppedAt = new HashMap<>();
	// Titles waiting for the game's popup, one at a time.
	private final Queue<Popup> popups = new ConcurrentLinkedQueue<>();
	private long soundUntil; // client thread only

	// The game's own popup (the collection log's "New item"): interface 660 over the game view, started by script 3343.
	private static final int POPUP = 660;
	// our popup while it is open, and since when the next one waits (0: none waits)
	private WidgetNode popupNode;
	private long popupWaiting;
	private static final int POPUP_SCRIPT = 3343;
	private static final String SEEN = "seen";
	private static final Set<String> SOUND_KEYS = Set.of("sound", "flipSound", "volume", "test", "testFlip");

	/** The server's GET /plugin/key answer. Names and sources in lower case. */
	static class Wants
	{
		long start;
		long end;
		String name; // the bingo's title
		String mode; // the game mode; none (an older server) is Bingo
		Set<String> items = new HashSet<>();
		// the items of open Skilling sides, in groups with how many more finish a side (SkillCounter.Goal). Only skill
		// counts go to them, never a kill (2026-11-03).
		List<SkillCounter.Goal> skill = new ArrayList<>();
		Set<String> uniques = new HashSet<>(); // every boss's uniques: a kill with one never joins a batch
		Map<String, Double> sources;
		double big;
		List<Finished> finished = new ArrayList<>(); // the tile sides the team finished
		List<KillRules.Goal> kc = new ArrayList<>(); // kill count sides still open, with their food rules (2026-11-06)
		List<KillRules.Event> events = new ArrayList<>(); // the team's random events with gear or inventory rules (2026-11-07)
		String rev; // the server's fingerprint of all this; every post's answer carries the current one (X-Rev)
	}

	static class Popup
	{
		final String title;
		final String text;
		final String chat; // the chatbox message that goes with it; null for none
		final boolean flip; // a flipped side: the flipped tile sound
		final String id; // the finished side; null for the welcome

		Popup(String title, String text, String chat, boolean flip, String id)
		{
			this.title = title;
			this.text = text;
			this.chat = chat;
			this.flip = flip;
			this.id = id;
		}
	}

	static class Finished
	{
		String id;
		String title;
		int points; // what it gives: a flipped side the tile's double, a row or column its bonus
		String description;
		boolean wait; // only drops that wait for a captain's approval finish it
	}

	@Override
	protected void startUp()
	{
		// the sides already popped up, kept over restarts: one finished while the player was away pops up at the next login
		String seen = configManager.getConfiguration(CabbingoConfig.GROUP, SEEN);
		finishedSeen = seen == null ? null : seen.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(seen.split(",")));
		fetchWants();
	}

	@Override
	protected void shutDown()
	{
		sendKills(kills.take());
		sendKc();
		closePush();
		setRuleBox(null, Collections.emptyList());
		wants = null;
		skills.goals(Collections.emptyList(), null);
		petTicks = -1;
		// popups not shown yet come again at the next start
		unsee(popups.stream().map(p -> p.id).filter(Objects::nonNull).toArray(String[]::new));
		finishedSeen = null;
		popups.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!CabbingoConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("url".equals(e.getKey()))
		{
			// another team: its finished tiles are not news
			synchronized (this)
			{
				finishedSeen = null;
			}
			configManager.unsetConfiguration(CabbingoConfig.GROUP, SEEN);
			closePush();
			fetchWants();
		}
		else if (SOUND_KEYS.contains(e.getKey()))
		{
			// a try of the picked sound
			boolean flip = "flipSound".equals(e.getKey()) || "testFlip".equals(e.getKey());
			clientThread.invoke(() -> playSound(flip ? config.flipSound() : config.sound()));
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			fetchWants();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			closePush();
			sendKills(kills.take());
			sendKc();
			killRules.forgetEaten();
			sendSkills();
			backpack = null;
			sackBefore = -1;
			barsBefore.clear();
			herbVarbit = -1;
			herbBefore = null;
			herbOnXp = null;
			skillXp.clear();
			updateTemple();
		}
	}

	// TempleOSRS: the bingo's XP and kill count tiles count from its copy of the hiscores, which it only refreshes when
	// asked. Asked here on logout (and world hop) during the bingo, as RuneLite's own XP Updater does, unless that one
	// already asks TempleOSRS. Not from a world whose XP is not on the normal hiscores.
	private void updateTemple()
	{
		String name = templeName;
		long gained = client.getOverallExperience() - templeXp;
		templeName = null;
		if (name == null || templeXp < 0 || !inBingo())
		{
			return;
		}
		// the server checks the skilling counts up to here against TempleOSRS once it has this update (cheat mark)
		send(payload("LOGOUT", name, "", new JsonObject()), false);
		if (gained <= 0)
		{
			return;
		}
		if ("true".equals(configManager.getConfiguration("runelite", "xpupdaterplugin"))
			&& !"false".equals(configManager.getConfiguration("xpupdater", "templeosrs")))
		{
			return;
		}
		HttpUrl url = new HttpUrl.Builder().scheme("https").host("templeosrs.com").addPathSegment("php")
			.addPathSegment("add_datapoint.php").addQueryParameter("player", name).build();
		http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: TempleOSRS update failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	private boolean inBingo()
	{
		Wants w = wants;
		long now = System.currentTimeMillis();
		return w != null && now >= w.start - HOUR && now <= w.end + HOUR;
	}

	// Tiles of the day and team changes reach the plugin within a minute (refreshWants).
	// What was gathered or made goes to the server every five minutes, in one post per skill; a minute after the last
	// item a tile wants, when sooner (onGameTick).
	@Schedule(period = 5, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void sendSkills()
	{
		String rsn = skillRsn;
		Map<String, Map<String, Integer>> taken;
		Set<String> shots;
		synchronized (skills)
		{
			shots = skills.takeShots();
			taken = skills.take();
		}
		if (rsn == null)
		{
			return;
		}
		taken.forEach((skill, items) ->
		{
			JsonArray list = new JsonArray();
			StringBuilder lines = new StringBuilder();
			items.forEach((name, quantity) ->
			{
				JsonObject item = new JsonObject();
				item.addProperty("name", name);
				item.addProperty("quantity", quantity);
				list.add(item);
				lines.append('\n').append(quantity).append(" x ").append(name);
			});
			JsonObject extra = new JsonObject();
			extra.addProperty("skill", skill);
			extra.add("items", list);
			// a screenshot at 25, 50, 75 and 100% of a tile: it goes to the team's channel, the tile's proof
			send(payload("SKILL", rsn, "**" + rsn + "**: " + skill + lines, extra), shots.contains(skill));
		});
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		String skill = e.getSkill().getName();
		Integer before = skillXp.put(skill, e.getXp());
		if (before != null && e.getXp() > before && SkillCounter.SKILLS.contains(skill))
		{
			xpNow.add(skill);
		}
	}

	// The backpack, by item name and quantity (noted items as their own item: never a skilling gain)
	private Map<Integer, Integer> backpack()
	{
		Map<Integer, Integer> held = new HashMap<>();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv != null)
		{
			for (Item item : inv.getItems())
			{
				if (item.getId() > 0)
				{
					held.merge(item.getId(), item.getQuantity(), Integer::sum);
				}
			}
		}
		return held;
	}

	// Once a tick: the backpack's gains a tick ago count for a skill that gained XP then or now (the two can come a
	// tick apart).
	private void countSkilling()
	{
		// gains while a random event's rule is broken count for no tile (user, 2026-11-07); the infobox says so
		boolean ok = ruleLines.isEmpty();
		Map<Integer, Integer> now = backpack();
		Set<String> around = new HashSet<>(xpBefore);
		around.addAll(xpNow);
		int region = client.getLocalPlayer() == null ? -1 : client.getLocalPlayer().getWorldLocation().getRegionID();
		if (region == BLAST_FURNACE)
		{
			// bars count as the furnace makes them (with Smithing XP), so bars taken from the dispenser never count
			boolean smithing = around.remove("Smithing");
			FURNACE_BARS.forEach((varbit, bar) ->
			{
				int bars = client.getVarbitValue(varbit);
				Integer before = barsBefore.put(varbit, bars);
				if (before != null && bars > before && smithing && ok)
				{
					skills.add("Smithing", bar, bars - before);
				}
			});
		}
		else
		{
			barsBefore.clear();
		}
		// the sack emptied a little: what the backpack gains this tick and the next came from it
		int sack = client.getVarbitValue(VarbitID.MOTHERLODE_SACK_TRANSMIT);
		if (MOTHERLODE.contains(region) && sackBefore >= 0 && sack < sackBefore)
		{
			sackTicks = 2;
		}
		sackBefore = sack;
		if (!backpackGained.isEmpty() && !around.isEmpty() && ok)
		{
			skills.gained(around, backpackGained);
		}
		Map<String, Integer> gainedBefore = backpackGained;
		backpackGained = new HashMap<>();
		if (backpack != null)
		{
			now.forEach((id, quantity) ->
			{
				int more = quantity - backpack.getOrDefault(id, 0);
				if (more > 0 && itemManager.getItemComposition(id).getNote() == -1)
				{
					String name = itemManager.getItemComposition(id).getName();
					if (sackTicks > 0)
					{
						if (ok)
						{
							skills.add("Mining", name, more);
						}
					}
					else
					{
						backpackGained.merge(name, more, Integer::sum);
					}
				}
			});
		}
		sackTicks = Math.max(0, sackTicks - 1);
		// A herb that went into an open herb sack: Farming XP a tick ago while the patch still had herbs, and nothing new in
		// the backpack then or now (a grimy herb there counts from the backpack). The game says nothing (tried 2026-11-02).
		// ponytail: one herb per tick with Farming XP; a tick-manipulated pick of two counts one
		if (herbOnXp != null && gainedBefore.isEmpty() && backpackGained.isEmpty() && ok)
		{
			log.debug("Cabbingo: {} into the herb sack", herbOnXp);
			skills.add("Farming", herbOnXp, 1);
		}
		herbOnXp = xpNow.contains("Farming") ? herbBefore : null;
		herbBefore = herbVarbit < 0 ? null : SkillCounter.herbAt(client.getVarbitValue(herbVarbit));
		backpack = now;
		xpBefore = xpNow;
		xpNow = new HashSet<>();
	}

	// A poll a minute per logged-in player, for when the live line (openPush) is down: a finish still pops up within a
	// minute. On the loot account that is ~1.5k requests a day per player.
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshWants()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			fetchWants();
		}
	}

	private synchronized void openPush()
	{
		long now = System.currentTimeMillis();
		HttpUrl url = url();
		if (push != null || now < pushAgain || url == null)
		{
			return;
		}
		if (pushHttp == null)
		{
			pushHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build();
		}
		Request request = new Request.Builder().url(url.newBuilder().addPathSegment("push").build()).build();
		push = pushHttp.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket ws, Response response)
			{
				pushWait = 60_000;
			}

			@Override
			public void onMessage(WebSocket ws, String text)
			{
				if ("finished".equals(text))
				{
					fetchWants();
				}
			}

			@Override
			public void onClosed(WebSocket ws, int code, String reason)
			{
				dropped(ws);
			}

			@Override
			public void onFailure(WebSocket ws, Throwable t, Response response)
			{
				log.debug("Cabbingo: live line dropped", t);
				dropped(ws);
			}
		});
	}

	private synchronized void dropped(WebSocket ws)
	{
		if (push == ws)
		{
			push = null;
			pushAgain = System.currentTimeMillis() + pushWait;
			pushWait = Math.min(pushWait * 2, 30 * 60_000);
		}
	}

	private synchronized void closePush()
	{
		if (push != null)
		{
			push.close(1000, null);
			push = null;
		}
		pushAgain = 0;
	}

	// The URL the player pasted, as Dink and Loot Logger take a webhook: this plugin sends nowhere else. Null when it is
	// empty or not a URL.
	private HttpUrl url()
	{
		return HttpUrl.parse(config.url().trim());
	}

	private void fetchWants()
	{
		HttpUrl url = url();
		if (url == null)
		{
			wants = null;
			skills.goals(Collections.emptyList(), null);
			return;
		}
		// never into RuneLite's disk cache: two clients on one computer asking at the same moment (a live line's
		// "finished") failed to rename the same cache file, and that answer was lost (2026-11-03)
		Request request = new Request.Builder().url(url).cacheControl(new CacheControl.Builder().noStore().build()).build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: no team settings", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					Wants w = response.isSuccessful() ? gson.fromJson(response.body().charStream(), Wants.class) : null;
					wants = w;
					skills.goals(w == null || w.skill == null ? Collections.emptyList() : w.skill, w == null ? null : w.rev);
					if (w != null)
					{
						welcome(w);
						finished(w.finished);
					}
				}
			}
		});
	}

	// The first login after a bingo starts: a welcome. Only when logged in, or the welcome could go to a login nobody sees.
	private void welcome(Wants w)
	{
		long now = System.currentTimeMillis();
		String start = String.valueOf(w.start);
		if (client.getGameState() == GameState.LOGGED_IN && now >= w.start && now <= w.end && !start.equals(config.welcomed()))
		{
			String name = w.name == null || w.name.isEmpty() ? "Your bingo" : w.name;
			String mode = w.mode == null || w.mode.isEmpty() ? "Bingo" : w.mode;
			popups.add(new Popup(Text.escapeJagex(name), "<col=ffffff>" + Text.escapeJagex(mode) + "</col> has started.<br>Good luck and enjoy!", null, false, null));
			configManager.setConfiguration(CabbingoConfig.GROUP, "welcomed", start);
		}
	}

	// Every NPC kill and pickpocket, as the server reports it. RuneLite also sends most kills as NpcLootReceived (loot
	// seen on the ground); RuneLite's own Loot Tracker only takes this one, and so does this plugin, or a kill posts twice.
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot e)
	{
		loot(e.getComposition().getName(), "NPC", e.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived e)
	{
		loot(e.getPlayer().getName(), "PLAYER", e.getItems());
	}

	// Raids, chests, clues and the rest the Loot Tracker sees. Its NPC, PLAYER and PICKPOCKET loot comes in above already.
	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		if (e.getType() == LootRecordType.EVENT)
		{
			loot(e.getName(), e.getType().name(), e.getItems());
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		// food eaten during a kill counts for a kill count tile's food rule, like food in the backpack
		if ("Eat".equals(e.getMenuOption()))
		{
			killRules.ate(Text.removeTags(e.getMenuTarget()));
		}
		// and potions drunk for its potion rule
		if ("Drink".equals(e.getMenuOption()))
		{
			killRules.drank(Text.removeTags(e.getMenuTarget()));
		}
		// "Pick Herbs": the patch's varbit says which herb it has (SkillCounter.herbAt). Any other click in the world (a
		// walk, compost, a seed, another patch) forgets it: farming varbits are shared between areas and patches.
		String action = e.getMenuAction().name();
		if (!action.startsWith("GAME_OBJECT_") && !action.startsWith("NPC_") && !action.startsWith("GROUND_ITEM_")
			&& !action.startsWith("WIDGET_TARGET_ON_") && !action.equals("WALK"))
		{
			return;
		}
		ObjectComposition patch = "Pick".equals(e.getMenuOption()) && action.startsWith("GAME_OBJECT_") ? patchAt(e) : null;
		herbVarbit = patch == null ? -1 : patch.getVarbitId();
		if (patch != null)
		{
			int value = client.getVarbitValue(herbVarbit);
			log.debug("Cabbingo: pick {} (object {}), varbit {} = {}: {}", Text.removeTags(e.getMenuTarget()), e.getId(), herbVarbit, value, SkillCounter.herbAt(value));
		}
	}

	// The patch behind a click, with its varbit: the menu may name the patch itself or what it shows now (a herb patch
	// turns into "Herbs"), so the scene's object on that tile is asked as well.
	private ObjectComposition patchAt(MenuOptionClicked e)
	{
		ObjectComposition def = client.getObjectDefinition(e.getId());
		if (def != null && def.getVarbitId() != -1)
		{
			return def;
		}
		WorldView view = client.getTopLevelWorldView();
		int x = e.getParam0(), y = e.getParam1();
		if (view == null || x < 0 || y < 0 || x >= 104 || y >= 104)
		{
			return null;
		}
		Tile tile = view.getScene().getTiles()[view.getPlane()][x][y];
		for (GameObject object : tile == null ? new GameObject[0] : tile.getGameObjects())
		{
			ObjectComposition c = object == null ? null : client.getObjectDefinition(object.getId());
			if (c != null && c.getVarbitId() != -1)
			{
				return c;
			}
		}
		return null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// logs and fish: their messages are SPAM (shown with the game filter off)
		// (none while a random event's rule is broken: countSkilling)
		if ((e.getType() == ChatMessageType.SPAM || e.getType() == ChatMessageType.GAMEMESSAGE) && playing() != null && ruleLines.isEmpty())
		{
			skills.message(Text.removeTags(e.getMessage()));
		}
		if (e.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String text = e.getMessage();
		for (String pet : PET_MESSAGES)
		{
			if (text.startsWith(pet))
			{
				petTicks = 3;
				petName = null;
				petDuplicate = text.contains("would have been");
				return;
			}
		}
		if (petTicks >= 0 && text.startsWith(COLLECTION_LOG))
		{
			petName = text.substring(COLLECTION_LOG.length()).replaceAll("<[^>]*>", "").trim();
		}
	}

	// A tile side, row, column or diagonal (id row:i, col:i, diag:i) the team finished since the last answer gets the game's popup, as
	// League tasks do, and a chat message with the tile's description. While a captain still has to approve the drops
	// that finish it, the points read "on approval". Approved later, it shows no second popup.
	private synchronized void finished(List<Finished> finished)
	{
		Set<String> ids = new HashSet<>();
		for (Finished f : finished)
		{
			ids.add(f.id);
			if (finishedSeen != null && !finishedSeen.contains(f.id))
			{
				String kind = f.id.startsWith("row:") ? "Row" : f.id.startsWith("col:") ? "Column" : f.id.startsWith("diag:") ? "Diagonal" : "Tile";
				String name = Text.escapeJagex(f.title);
				String text = kind + " completed: <col=ffffff>" + name + "</col><br>"
					+ (f.wait ? "Points on approval: " : "Points earned: ") + "<col=ffffff>" + f.points + "</col>";
				String chat = "<col=ef1020>" + name + "</col> completed (" + f.points + (f.points == 1 ? " point" : " points")
					+ (f.wait ? " once approved" : "") + ")"
					+ (f.description == null || f.description.isBlank() ? "" : ": " + Text.escapeJagex(f.description.replaceAll("\\s+", " ").trim()));
				popups.add(new Popup(kind + " Complete!", text, chat, f.id.endsWith(":flip"), f.id));
				poppedAt.put(f.id, System.currentTimeMillis());
			}
		}
		// the welcome first, then tiles, then rows, then columns, queued ones too (user, 2026-11-03)
		List<Popup> queued = new ArrayList<>(popups);
		queued.sort(Comparator.comparingInt(p -> p.id == null ? 0 : p.id.startsWith("row:") ? 2 : p.id.startsWith("col:") ? 3 : p.id.startsWith("diag:") ? 4 : 1));
		popups.clear();
		popups.addAll(queued);
		log.debug("Cabbingo: finished {}, seen before {}", ids, finishedSeen);
		if (!ids.equals(finishedSeen))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", ids));
		}
		finishedSeen = ids;
	}

	private void unsee(String[] ids)
	{
		unsee(ids, Long.MAX_VALUE);
	}

	// only the sides that got no popup since before: the time a post was sent
	private synchronized void unsee(String[] ids, long before)
	{
		List<String> old = Arrays.stream(ids).filter(id -> poppedAt.getOrDefault(id, 0L) < before).collect(Collectors.toList());
		if (finishedSeen != null && finishedSeen.removeAll(old))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", finishedSeen));
		}
	}

	// The next popup, once the game shows none: the same one the collection log uses.
	private void showPopup()
	{
		if (popups.isEmpty())
		{
			popupWaiting = 0;
			return;
		}
		// one after another, each with its sound; not over the game's own popup (collection log, League task) nor behind
		// the "Click here to play" screen, nor while a bank or other window is open (it would not show, only its sound:
		// tried 2026-11-02). Only what shows counts: an interface can stay loaded once it is gone (a
		// Blast Furnace popup waited for minutes, 2026-11-02).
		long now = System.currentTimeMillis();
		String busy = popupNode != null ? "ours" : showing(POPUP, 1) ? "game popup" : showing(InterfaceID.WELCOME_SCREEN, 0) ? "welcome screen"
			: modalOpen() ? "open window" : now < soundUntil ? "sound" : null;
		if (busy != null)
		{
			if (popupWaiting == 0)
			{
				popupWaiting = now;
			}
			else if (now - popupWaiting > 10_000 && (now - popupWaiting) % 10_000 < 600)
			{
				log.debug("Cabbingo: popup waits for {}", busy);
			}
			return;
		}
		popupWaiting = 0;
		Popup next = popups.poll();
		log.debug("Cabbingo: popup {}", next.text);
		int parent = !client.isResized() ? WidgetUtil.packComponentId(548, 42)
			: client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1 ? WidgetUtil.packComponentId(164, 13)
			: WidgetUtil.packComponentId(161, 13);
		WidgetNode node = client.openInterface(parent, POPUP, WidgetModalMode.MODAL_CLICKTHROUGH);
		Widget popup = client.getWidget(POPUP, 1);
		client.runScript(POPUP_SCRIPT, next.title, next.text, -1);
		if (next.chat != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", next.chat, null);
		}
		playSound(next.flip ? config.flipSound() : config.sound());
		// closed again once it has played (or after 20 s at most), or the next one could not open
		popupNode = node;
		clientThread.invokeLater(() ->
		{
			if (popup != null && popup.getWidth() > 0 && System.currentTimeMillis() - now < 20_000)
			{
				return false;
			}
			try
			{
				client.closeInterface(node, true);
			}
			catch (IllegalArgumentException ex)
			{
				// the game closed it first ("WidgetNode is no longer valid"): nothing left to close
			}
			popupNode = null;
			return true;
		});
	}

	// a window in the middle of the screen (bank, shop, Blast Furnace bars): the game's own windows the player closes
	private boolean modalOpen()
	{
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getModalMode() == WidgetModalMode.MODAL_NOCLICKTHROUGH)
			{
				return true;
			}
		}
		return false;
	}

	private boolean showing(int group, int child)
	{
		Widget w = client.getWidget(group, child);
		return w != null && !w.isHidden() && w.getWidth() > 0;
	}

	/** On the client thread. */
	private void playSound(Sound sound)
	{
		int volume = config.volume();
		long now = System.currentTimeMillis();
		if (volume <= 0 || now < soundUntil)
		{
			return; // the last sound still plays: no pile-up from double clicks
		}
		soundUntil = now + sound.ms;
		if (sound.id > 0)
		{
			client.playSoundEffect(sound.id, volume * 127 / 100);
		}
		else if (sound.file != null)
		{
			executor.execute(() ->
			{
				try
				{
					audioPlayer.play(CabbingoPlugin.class, sound.file, 20f * (float) Math.log10(volume / 100.0));
				}
				catch (Exception ex)
				{
					log.debug("Cabbingo sound {} did not play", sound.file, ex);
				}
			});
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		showPopup();
		if (inBingo())
		{
			openPush();
		}
		// a minute after its first kill, or once the bingo window has closed
		if (kills.due(System.currentTimeMillis()) || !inBingo())
		{
			sendKills(kills.take());
		}
		if (killRules.due(System.currentTimeMillis()))
		{
			sendKc();
		}
		String player = playing();
		watchGear(player);
		if (player != null)
		{
			skillRsn = player;
			countSkilling();
			if (skills.due(System.currentTimeMillis()))
			{
				sendSkills();
			}
		}
		if (templeName == null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			java.util.EnumSet<WorldType> world = client.getWorldType();
			boolean normal = !world.contains(WorldType.SEASONAL) && !world.contains(WorldType.DEADMAN) && !world.contains(WorldType.BETA_WORLD)
				&& !world.contains(WorldType.TOURNAMENT_WORLD) && !world.contains(WorldType.NOSAVE_MODE) && !world.contains(WorldType.FRESH_START_WORLD)
				&& !world.contains(WorldType.QUEST_SPEEDRUNNING);
			templeName = normal ? client.getLocalPlayer().getName().replace(' ', ' ') : null;
			templeXp = client.getOverallExperience();
		}
		if (petTicks < 0 || petTicks-- > 0 && petName == null)
		{
			return;
		}
		petTicks = -1;
		String rsn = playing();
		if (rsn == null)
		{
			return;
		}
		JsonObject extra = new JsonObject();
		if (petName != null)
		{
			extra.addProperty("petName", petName);
		}
		extra.addProperty("duplicate", petDuplicate);
		String content = "**" + rsn + "** got a pet" + (petName != null ? ": " + petName : "") + (petDuplicate ? " (duplicate)" : "");
		if (!ruleLines.isEmpty())
		{
			extra.add("broke", gson.toJsonTree(ruleLines));
			content += "\nFor no tile: " + String.join(", ", ruleLines);
		}
		send(payload("PET", rsn, content, extra), true);
	}

	// The local player's name from an hour before the bingo to an hour after it, else null. The server checks the team and
	// the exact dates on every post, as they are at that moment, and ignores a kill outside them: a player added to the
	// team never loses a kill to this plugin's older copy, and the hour covers a date change until the next refresh.
	private String playing()
	{
		if (!inBingo() || client.getLocalPlayer() == null)
		{
			return null;
		}
		// (the game puts a no-break space in some names; the server and the team list use a plain one)
		String rsn = client.getLocalPlayer().getName();
		return rsn != null ? rsn.replace('\u00a0', ' ') : null;
	}

	private void loot(String source, String category, Collection<ItemStack> stacks)
	{
		String rsn = playing();
		if (rsn == null || source == null)
		{
			return;
		}
		Wants w = wants;
		// a running random event's rules hold for every tile (user, 2026-11-07): a kill that broke one still goes to the
		// gp lists and Discord, with the rules it broke (extra.broke), and the server counts it for no tile
		Map<String, String> worn = worn();
		List<String> bag = bag();
		List<String> broke = killRules.eventsBroken(events(w), worn, bag, System.currentTimeMillis());
		if (w != null && w.kc != null && !w.kc.isEmpty() && !category.equals("PLAYER"))
		{
			checkKill(rsn, source, w.kc);
		}
		else
		{
			killRules.next(worn, bag);
		}
		if (stacks.isEmpty())
		{
			return;
		}
		List<KillBatch.Item> items = new ArrayList<>();
		long value = 0;
		boolean wanted = false;
		boolean unique = false;
		for (ItemStack stack : stacks)
		{
			String name = itemManager.getItemComposition(stack.getId()).getName();
			long price = itemManager.getItemPrice(stack.getId());
			items.add(new KillBatch.Item(stack.getId(), name, price, stack.getQuantity()));
			value += price * stack.getQuantity();
			wanted |= w.items.contains(name.toLowerCase(Locale.ROOT).replaceFirst(" \\(uncharged\\)$", "")); // charged or not, as the server keys items
			unique |= w.uniques.contains(name.toLowerCase(Locale.ROOT));
		}
		// A screenshot where the server sends the post to the team's channel, or a tile counts its value.
		String sourceKey = category.equals("PLAYER") ? "pvp" : source.trim().toLowerCase(Locale.ROOT);
		Double min = w.sources.get(sourceKey);
		boolean shot = wanted || value >= w.big || (min != null && value >= min);
		if (!broke.isEmpty() && (wanted || (min != null && value >= min)))
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>The drop is invalidated for the tiles</col> (" + String.join(", ", broke) + ")", null);
		}
		// Such a kill, a unique, PvP and one that broke a random event go alone (the whole drop, its own proof), after the
		// open batch; any other kill joins the batch of its source.
		if (shot || unique || category.equals("PLAYER") || !broke.isEmpty())
		{
			sendKills(kills.take());
			sendLoot(rsn, source, category, items, 1, shot, broke);
		}
		else
		{
			sendKills(kills.add(rsn, source, category, items, System.currentTimeMillis()));
		}
	}

	// A kill of a kill count tile's monster: the backpack now and what was eaten since the last kill, against each tile's
	// food rule. A kill that broke one is said in the chat and never sent for that tile (user, 2026-11-06).
	private void checkKill(String rsn, String source, List<KillRules.Goal> goals)
	{
		List<KillRules.Carried> backpack = new ArrayList<>();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		for (Item item : inv == null ? new Item[0] : inv.getItems())
		{
			if (item.getId() <= 0)
			{
				continue;
			}
			net.runelite.api.ItemComposition c = itemManager.getItemComposition(item.getId());
			List<String> actions = Arrays.asList(c.getInventoryActions());
			backpack.add(new KillRules.Carried(c.getName(), actions.contains("Eat"), actions.contains("Drink")));
		}
		kcRsn = rsn;
		Map<KillRules.Goal, String> broke = killRules.kill(goals, source, backpack, worn(), events(wants), System.currentTimeMillis());
		log.debug("Cabbingo: kill of {}, kill count tiles {}, broke {}", source, goals.stream().map(g -> g.source + "/" + g.left).collect(Collectors.toList()), broke.values());
		broke.forEach((g, why) ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>The kill is invalidated for the tile: " + g.title + "</col> (" + why + ")", null));
	}

	// The game's equipment slots by the names the rules use (KillRules.SLOTS)
	private static final Map<String, EquipmentInventorySlot> SLOT_OF = new LinkedHashMap<>();
	static
	{
		SLOT_OF.put("head", EquipmentInventorySlot.HEAD);
		SLOT_OF.put("cape", EquipmentInventorySlot.CAPE);
		SLOT_OF.put("neck", EquipmentInventorySlot.AMULET);
		SLOT_OF.put("ammo", EquipmentInventorySlot.AMMO);
		SLOT_OF.put("weapon", EquipmentInventorySlot.WEAPON);
		SLOT_OF.put("body", EquipmentInventorySlot.BODY);
		SLOT_OF.put("shield", EquipmentInventorySlot.SHIELD);
		SLOT_OF.put("legs", EquipmentInventorySlot.LEGS);
		SLOT_OF.put("hands", EquipmentInventorySlot.GLOVES);
		SLOT_OF.put("feet", EquipmentInventorySlot.BOOTS);
		SLOT_OF.put("ring", EquipmentInventorySlot.RING);
	}

	/** What the player wears now: slot -> item name, empty slots left out. */
	private Map<String, String> worn()
	{
		Map<String, String> out = new HashMap<>();
		ItemContainer c = client.getItemContainer(InventoryID.WORN);
		if (c == null)
		{
			return out;
		}
		SLOT_OF.forEach((slot, s) ->
		{
			Item item = c.getItem(s.getSlotIdx());
			if (item != null && item.getId() > 0)
			{
				out.put(slot, itemManager.getItemComposition(item.getId()).getName());
			}
		});
		int quiver = quiverAmmo(out.get("cape"));
		if (quiver > 0)
		{
			out.put("quiver", itemManager.getItemComposition(quiver).getName());
		}
		return out;
	}

	// The ammo in Dizana's quiver's own slot, only while a quiver (or the max cape with one) is worn; 0 for none
	private int quiverAmmo(String cape)
	{
		int id = client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO);
		return cape != null && cape.toLowerCase(Locale.ROOT).contains("dizana") && id > 0 ? id : 0;
	}

	// For the gear rules, every game tick: what the worn gear is worth by RuneLite's prices (ammo by its whole stack,
	// untradeables 0) and which worn items have no combat stats.
	private void lookAtGear(Map<String, String> worn)
	{
		List<int[]> items = new ArrayList<>(); // item id, quantity
		ItemContainer c = client.getItemContainer(InventoryID.WORN);
		for (Item item : c == null ? new Item[0] : c.getItems())
		{
			if (item.getId() > 0)
			{
				items.add(new int[]{item.getId(), item.getQuantity()});
			}
		}
		int quiver = quiverAmmo(worn.get("cape"));
		if (quiver > 0)
		{
			items.add(new int[]{quiver, client.getVarpValue(VarPlayerID.DIZANAS_QUIVER_TEMP_AMMO_AMOUNT)});
		}
		long gp = 0;
		for (int[] i : items)
		{
			gp += itemManager.getItemPrice(i[0]) * i[1];
			ItemStats stats = itemManager.getItemStats(i[0]);
			ItemEquipmentStats e = stats == null ? null : stats.getEquipment();
			if (e == null || (e.getAstab() == 0 && e.getAslash() == 0 && e.getAcrush() == 0 && e.getAmagic() == 0 && e.getArange() == 0
				&& e.getDstab() == 0 && e.getDslash() == 0 && e.getDcrush() == 0 && e.getDmagic() == 0 && e.getDrange() == 0
				&& e.getStr() == 0 && e.getRstr() == 0 && e.getMdmg() == 0 && e.getPrayer() == 0))
			{
				killRules.statless.add(KillRules.key(itemManager.getItemComposition(i[0]).getName()));
			}
		}
		killRules.worth(gp);
	}

	private List<String> bag()
	{
		List<String> out = new ArrayList<>();
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		for (Item item : inv == null ? new Item[0] : inv.getItems())
		{
			if (item.getId() > 0)
			{
				out.add(itemManager.getItemComposition(item.getId()).getName());
			}
		}
		return out;
	}

	// The random event infobox: shown while the player breaks a rule of a running random event (user, 2026-11-07),
	// so they know before they start. Gear and inventory are also noted every tick for the kill count rules.
	private RuleBox ruleBox;

	private void watchGear(String player)
	{
		Wants w = wants;
		boolean kcRules = w != null && w.kc.stream().anyMatch(g -> g.gear != null || g.carry != null || g.maxValue != null);
		boolean events = w != null && w.events != null && !w.events.isEmpty();
		if (player == null || (!kcRules && !events))
		{
			ruleLines = Collections.emptyList();
			setRuleBox(null, ruleLines);
			return;
		}
		Map<String, String> worn = worn();
		List<String> bag = bag();
		lookAtGear(worn);
		// a random event's rules hold for every kill too
		killRules.saw(worn, bag);
		Map<String, Set<String>> now = new HashMap<>();
		worn.forEach((slot, name) -> now.put(slot, Collections.singleton(name)));
		long time = System.currentTimeMillis();
		List<String> lines = new ArrayList<>();
		KillRules.Event first = null;
		for (KillRules.Event ev : events ? w.events : Collections.<KillRules.Event>emptyList())
		{
			if (time < ev.from || time >= ev.to)
			{
				continue;
			}
			for (String line : KillRules.broken(ev, now, bag, bag, killRules.statless, killRules.worthNow()))
			{
				lines.add(KillRules.eventLine(line));
				first = first == null ? ev : first;
			}
		}
		ruleLines = lines;
		setRuleBox(first, lines);
	}

	// the rules of running random events broken right now (watchGear): skilling and a pet then count for no tile
	private List<String> ruleLines = Collections.emptyList();

	private static List<KillRules.Event> events(Wants w)
	{
		return w == null || w.events == null ? Collections.emptyList() : w.events;
	}

	private void setRuleBox(KillRules.Event ev, List<String> lines)
	{
		if (lines.isEmpty())
		{
			if (ruleBox != null)
			{
				infoBoxManager.removeInfoBox(ruleBox);
				ruleBox = null;
			}
			return;
		}
		String tip = String.join("</br>", lines);
		if (ruleBox != null && ruleBox.event == ev)
		{
			ruleBox.tip = tip;
			return;
		}
		if (ruleBox != null)
		{
			infoBoxManager.removeInfoBox(ruleBox);
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("item-images/(\\d+)\\.png$").matcher(ev.icon == null ? "" : ev.icon);
		ruleBox = new RuleBox(itemManager.getImage(m.find() ? Integer.parseInt(m.group(1)) : 6199), this, ev, tip); // else a mystery box
		infoBoxManager.addInfoBox(ruleBox);
	}

	static class RuleBox extends InfoBox
	{
		final KillRules.Event event;
		volatile String tip;

		RuleBox(java.awt.image.BufferedImage image, Plugin plugin, KillRules.Event event, String tip)
		{
			super(image, plugin);
			this.event = event;
			this.tip = tip;
		}

		@Override
		public String getText()
		{
			return "!";
		}

		@Override
		public java.awt.Color getTextColor()
		{
			return java.awt.Color.RED;
		}

		@Override
		public String getTooltip()
		{
			return tip;
		}
	}

	// The kills that kept to their tile's food rule, per tile side: the server adds them to the tiles
	private void sendKc()
	{
		boolean shot;
		Map<String, Integer> taken;
		synchronized (killRules)
		{
			shot = killRules.shot();
			taken = killRules.take();
		}
		String rsn = kcRsn;
		if (taken.isEmpty() || rsn == null)
		{
			return;
		}
		JsonObject counts = new JsonObject();
		taken.forEach(counts::addProperty);
		JsonObject extra = new JsonObject();
		extra.add("kills", counts);
		// the message with the screenshot: who, and per tile how many kills and how many are left
		StringBuilder lines = new StringBuilder("**" + rsn + "**");
		Wants w = wants;
		for (KillRules.Goal g : w == null ? Collections.<KillRules.Goal>emptyList() : w.kc)
		{
			if (taken.containsKey(g.id))
			{
				lines.append('\n').append(taken.get(g.id)).append(" x ").append(g.source).append(" for ").append(g.title)
					.append(" (").append(Math.max(0, g.left)).append(" to go)");
			}
		}
		// a screenshot at kcMarks: it goes to the team's channel, the tile's proof
		send(payload("KC", rsn, lines.toString(), extra), shot);
	}

	private void sendKills(KillBatch.Batch b)
	{
		if (b != null)
		{
			sendLoot(b.rsn, b.source, b.category, new ArrayList<>(b.items.values()), b.kills, false, Collections.emptyList());
		}
	}

	// extra.kills: how many kills the items are from, their stacks added up (1 for a single kill)
	private void sendLoot(String rsn, String source, String category, List<KillBatch.Item> items, int n, boolean shot, List<String> broke)
	{
		JsonArray list = new JsonArray();
		StringBuilder lines = new StringBuilder();
		long value = 0;
		for (KillBatch.Item i : items)
		{
			JsonObject item = new JsonObject();
			item.addProperty("id", i.id);
			item.addProperty("name", i.name);
			item.addProperty("quantity", i.quantity);
			item.addProperty("priceEach", i.price);
			list.add(item);
			value += i.price * i.quantity;
			lines.append('\n').append(i.quantity).append(" x ").append(i.name);
		}
		JsonObject extra = new JsonObject();
		extra.addProperty("source", source);
		extra.addProperty("category", category);
		extra.addProperty("kills", n);
		extra.add("items", list);
		if (!broke.isEmpty())
		{
			extra.add("broke", gson.toJsonTree(broke));
			lines.append("\nFor no tile: ").append(String.join(", ", broke));
		}
		String content = "**" + rsn + "**: " + source + (n > 1 ? " x" + n : "") + " (" + String.format(Locale.ROOT, "%,d", value) + " gp)" + lines;
		// (Discord takes at most 2,000 characters)
		if (content.length() > 1900)
		{
			content = content.substring(0, 1900) + "\n...";
		}
		send(payload("LOOT", rsn, content, extra), shot);
	}

	// Shaped like a Dink post, which the server already reads; Discord shows content and ignores the rest.
	private JsonObject payload(String type, String rsn, String content, JsonObject extra)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("playerName", rsn);
		p.addProperty("content", content);
		p.add("embeds", new JsonArray());
		p.add("extra", extra);
		return p;
	}

	private void send(JsonObject payload, boolean shot)
	{
		HttpUrl url = url();
		if (url == null)
		{
			return;
		}
		if (!shot)
		{
			post(url, RequestBody.create(JSON, gson.toJson(payload)), payload);
			return;
		}
		drawManager.requestNextFrameListener(frame -> executor.execute(() ->
		{
			MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", gson.toJson(payload));
			try
			{
				body.addFormDataPart("file", "screenshot.jpg", RequestBody.create(JPEG, jpeg(frame)));
			}
			catch (IOException e)
			{
				log.debug("Cabbingo: no screenshot", e);
			}
			post(url, body.build(), null);
		}));
	}

	// again: the post without a screenshot, sent once more with one when the server asks for it (409)
	private void post(HttpUrl url, RequestBody body, JsonObject again)
	{
		Request request = new Request.Builder().url(url).post(body).build();
		long sent = System.currentTimeMillis();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: post failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
				// The board changed since this plugin's copy of it: ask for the new one now, not in ten minutes.
				String rev = response.header("X-Rev");
				Wants w = wants;
				log.debug("Cabbingo: post answered {}, rev {} (had {})", response.code(), rev, w == null ? null : w.rev);
				// sides this post finished: news even when this plugin still had them as finished
				String added = response.header("X-Finished");
				if (added != null)
				{
					unsee(added.split(","), sent);
				}
				if (rev != null && w != null && (!rev.equals(w.rev) || added != null))
				{
					w.rev = rev; // (one ask, not one per post while it is on its way)
					fetchWants();
				}
				// The server knows this drop needed a screenshot (a tile item the copy did not have yet). The loot is
				// still on the screen a moment after the kill.
				if (response.code() == 409 && again != null)
				{
					send(again, true);
				}
			}
		});
	}

	// JPEG instead of PNG: about 200 KB for a game screenshot instead of 1.5 to 2 MB.
	private static byte[] jpeg(Image frame) throws IOException
	{
		BufferedImage rgb = new BufferedImage(frame.getWidth(null), frame.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(JPEG_QUALITY);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out))
		{
			writer.setOutput(stream);
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		finally
		{
			writer.dispose();
		}
		return out.toByteArray();
	}

	@Provides
	CabbingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CabbingoConfig.class);
	}
}
