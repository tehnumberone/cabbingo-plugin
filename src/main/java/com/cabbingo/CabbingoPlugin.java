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
import java.util.HashMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.WidgetNode;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
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
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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
	@Inject private OkHttpClient http;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;
	@Inject private ConfigManager configManager;
	@Inject private AudioPlayer audioPlayer;

	// What the team wants, from the server. Null until it answered (no key, a wrong key, offline).
	private volatile Wants wants;
	// A pet message waits a few ticks for the collection log line that names the pet.
	private int petTicks = -1;
	private String petName;
	private boolean petDuplicate;
	// Skilling tiles: what the player gathered or made, sent every few minutes (SkillCounter). XP per skill as last seen,
	// the skills that gained XP this tick and last, and the backpack as it was and what it gained a tick ago.
	private final SkillCounter skills = new SkillCounter();
	{
		skills.wanted = name ->
		{
			Wants w = wants;
			return w != null && w.items.contains(name.toLowerCase(Locale.ROOT));
		};
	}
	// Kills of one source go out together, a minute after the first (KillBatch)
	private final KillBatch kills = new KillBatch();
	private final Map<String, Integer> skillXp = new HashMap<>();
	private Set<String> xpNow = new HashSet<>();
	private Set<String> xpBefore = new HashSet<>();
	private Map<Integer, Integer> backpack;
	private Map<String, Integer> backpackGained = new HashMap<>();
	private volatile String skillRsn;

	// For the TempleOSRS update on logout: who played and their total XP when the session started (-1: none yet).
	private String templeName;
	private long templeXp = -1;
	// The finished tile sides already known (by id); null until the first answer, so a login shows no old ones.
	private Set<String> finishedSeen;
	// Titles waiting for the game's popup, one at a time.
	private final Queue<Popup> popups = new ConcurrentLinkedQueue<>();
	private long soundUntil; // client thread only

	// The game's own popup (the collection log's "New item"): interface 660 over the game view, started by script 3343.
	private static final int POPUP = 660;
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
		Map<String, Double> sources;
		double big;
		List<Finished> finished = new ArrayList<>(); // the tile sides the team finished
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
		wants = null;
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
			sendKills(kills.take());
			sendSkills();
			backpack = null;
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
		Map<String, Map<String, Integer>> taken = skills.take();
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
			send(payload("SKILL", rsn, "**" + rsn + "**: " + skill + lines, extra), false);
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
		Map<Integer, Integer> now = backpack();
		Set<String> around = new HashSet<>(xpBefore);
		around.addAll(xpNow);
		if (!backpackGained.isEmpty() && !around.isEmpty())
		{
			skills.gained(around, backpackGained);
		}
		backpackGained = new HashMap<>();
		if (backpack != null)
		{
			now.forEach((id, quantity) ->
			{
				int more = quantity - backpack.getOrDefault(id, 0);
				if (more > 0 && itemManager.getItemComposition(id).getNote() == -1)
				{
					backpackGained.merge(itemManager.getItemComposition(id).getName(), more, Integer::sum);
				}
			});
		}
		backpack = now;
		xpBefore = xpNow;
		xpNow = new HashSet<>();
	}

	// ponytail: a poll a minute per logged-in player, so a captain's approval pops up within a minute. On the loot
	// account that is ~1.5k requests a day per player; a push channel if that ever matters.
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshWants()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			fetchWants();
		}
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
			return;
		}
		Request request = new Request.Builder().url(url).build();
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
	public void onChatMessage(ChatMessage e)
	{
		// logs and fish: their messages are SPAM (shown with the game filter off)
		if ((e.getType() == ChatMessageType.SPAM || e.getType() == ChatMessageType.GAMEMESSAGE) && playing() != null)
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

	// A tile side, row or column (id row:i, col:i) the team finished since the last answer gets the game's popup, as
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
				String kind = f.id.startsWith("row:") ? "Row" : f.id.startsWith("col:") ? "Column" : "Tile";
				String name = Text.escapeJagex(f.title);
				String text = kind + " completed: <col=ffffff>" + name + "</col><br>"
					+ (f.wait ? "Points on approval: " : "Points earned: ") + "<col=ffffff>" + f.points + "</col>";
				String chat = "<col=ef1020>" + name + "</col> completed (" + f.points + (f.points == 1 ? " point" : " points")
					+ (f.wait ? " once approved" : "") + ")"
					+ (f.description == null || f.description.isBlank() ? "" : ": " + Text.escapeJagex(f.description.replaceAll("\\s+", " ").trim()));
				popups.add(new Popup(kind + " Complete!", text, chat, f.id.endsWith(":flip"), f.id));
			}
		}
		log.debug("Cabbingo: finished {}, seen before {}", ids, finishedSeen);
		if (!ids.equals(finishedSeen))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", ids));
		}
		finishedSeen = ids;
	}

	private synchronized void unsee(String[] ids)
	{
		if (finishedSeen != null && finishedSeen.removeAll(Arrays.asList(ids)))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", finishedSeen));
		}
	}

	// The next popup, once the game shows none: the same one the collection log uses.
	private void showPopup()
	{
		// not behind the "Click here to play" screen
		// one after another, each with its sound
		if (popups.isEmpty() || client.getWidget(POPUP, 1) != null || client.getWidget(InterfaceID.WELCOME_SCREEN, 0) != null
			|| System.currentTimeMillis() < soundUntil)
		{
			return;
		}
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
		// closed again once it has played, or the next one could not open
		clientThread.invokeLater(() ->
		{
			if (popup != null && popup.getWidth() > 0)
			{
				return false;
			}
			client.closeInterface(node, true);
			return true;
		});
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
		// a minute after its first kill, or once the bingo window has closed
		if (kills.due(System.currentTimeMillis()) || !inBingo())
		{
			sendKills(kills.take());
		}
		String player = playing();
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
		if (rsn == null || source == null || stacks.isEmpty())
		{
			return;
		}
		Wants w = wants;
		List<KillBatch.Item> items = new ArrayList<>();
		long value = 0;
		boolean wanted = false;
		for (ItemStack stack : stacks)
		{
			String name = itemManager.getItemComposition(stack.getId()).getName();
			long price = itemManager.getItemPrice(stack.getId());
			items.add(new KillBatch.Item(stack.getId(), name, price, stack.getQuantity()));
			value += price * stack.getQuantity();
			wanted |= w.items.contains(name.toLowerCase(Locale.ROOT));
		}
		// A screenshot where the server sends the post to the team's channel, or a tile counts its value.
		String sourceKey = category.equals("PLAYER") ? "pvp" : source.trim().toLowerCase(Locale.ROOT);
		Double min = w.sources.get(sourceKey);
		boolean shot = wanted || value >= w.big || (min != null && value >= min);
		// Such a kill, and PvP, goes alone, after the open batch; any other joins the batch of its source.
		if (shot || category.equals("PLAYER"))
		{
			sendKills(kills.take());
			sendLoot(rsn, source, category, items, 1, shot);
		}
		else
		{
			sendKills(kills.add(rsn, source, category, items, System.currentTimeMillis()));
		}
	}

	private void sendKills(KillBatch.Batch b)
	{
		if (b != null)
		{
			sendLoot(b.rsn, b.source, b.category, new ArrayList<>(b.items.values()), b.kills, false);
		}
	}

	// extra.kills: how many kills the items are from, their stacks added up (1 for a single kill)
	private void sendLoot(String rsn, String source, String category, List<KillBatch.Item> items, int n, boolean shot)
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
					unsee(added.split(","));
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
