package com.cabbingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Every kill, PvP kill and pet of a bingo player: kills of one source go out together (KillBatch), a kill a tile or
 * the team's channel cares about alone with its screenshot, and kill count tiles' kills within their rules
 * (KillRules). On the EventBus while the plugin runs.
 */
@Slf4j
@Singleton
class Kills
{
	private static final String[] PET_MESSAGES = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed",
	};
	private static final String COLLECTION_LOG = "New item added to your collection log: ";

	private final Client client;
	private final ItemManager itemManager;
	private final Gson gson;
	private final Team team;
	private final Gear gear;
	private final Sender sender;
	private final KillRules killRules;
	private final BoardOverlay boardOverlay;

	// Kills of one source go out together, two minutes after the first (KillBatch)
	private final KillBatch kills = new KillBatch();
	// who made the kills that kept to a kill count tile's rules (KillRules)
	private volatile String kcRsn;
	// A pet message waits a few ticks for the collection log line that names the pet.
	private int petTicks = -1;
	private String petName;
	private boolean petDuplicate;

	@Inject
	Kills(Client client, ItemManager itemManager, Gson gson, Team team, Gear gear, Sender sender, KillRules killRules, BoardOverlay boardOverlay)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.gson = gson;
		this.team = team;
		this.gear = gear;
		this.sender = sender;
		this.killRules = killRules;
		this.boardOverlay = boardOverlay;
	}

	/** Sends what waits: the open batch and the kill count kills (logout, hop, shutDown). */
	void flush()
	{
		sendKills(kills.take());
		sendKc();
	}

	/** Logout or hop: what waits goes out, and the next kill starts with nothing eaten. */
	void logout()
	{
		flush();
		killRules.forgetEaten();
	}

	void reset()
	{
		petTicks = -1;
	}

	/** Once a tick: the batch two minutes after its first kill, or once the bingo window has closed. */
	void tick()
	{
		if (kills.due(System.currentTimeMillis()) || !team.inBingo())
		{
			sendKills(kills.take());
		}
		if (killRules.due(System.currentTimeMillis()))
		{
			sendKc();
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
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
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

	/** Once a tick: a pet goes out once its collection log line came, or a few ticks after its message. */
	void petTick()
	{
		if (petTicks < 0 || petTicks-- > 0 && petName == null)
		{
			return;
		}
		petTicks = -1;
		String rsn = team.playing();
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
		List<String> ruleLines = gear.ruleLines();
		if (!ruleLines.isEmpty())
		{
			extra.add("broke", gson.toJsonTree(ruleLines));
			content += "\nFor no tile: " + String.join(", ", ruleLines);
		}
		sender.send(Sender.payload("PET", rsn, content, extra), true);
	}

	private void loot(String source, String category, Collection<ItemStack> stacks)
	{
		String rsn = team.playing();
		if (rsn == null || source == null)
		{
			return;
		}
		Wants w = team.wants();
		// a running random event's rules hold for every tile (user, 2026-11-07): a kill that broke one still goes to the
		// gp lists and Discord, with the rules it broke (extra.broke), and the server counts it for no tile
		Map<String, String> worn = gear.worn();
		List<String> bag = gear.bag();
		List<String> broke = killRules.eventsBroken(Gear.events(w), worn, bag, System.currentTimeMillis());
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
			ItemComposition c = itemManager.getItemComposition(item.getId());
			List<String> actions = Arrays.asList(c.getInventoryActions());
			backpack.add(new KillRules.Carried(c.getName(), actions.contains("Eat"), actions.contains("Drink")));
		}
		kcRsn = rsn;
		Map<KillRules.Goal, Integer> left = new HashMap<>();
		goals.forEach(g -> left.put(g, g.left));
		Map<KillRules.Goal, String> broke = killRules.kill(goals, source, backpack, gear.worn(), Gear.events(team.wants()), System.currentTimeMillis());
		// the kills that counted show on the board window now; the server has them a minute later (KillBatch)
		goals.stream().filter(g -> g.left < left.get(g)).forEach(g -> boardOverlay.counted(g.id));
		log.debug("Cabbingo: kill of {}, kill count tiles {}, broke {}", source, goals.stream().map(g -> g.source + "/" + g.left).collect(Collectors.toList()), broke.values());
		broke.forEach((g, why) ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
				"<col=ff0000>The kill is invalidated for the tile: " + g.title + "</col> (" + why + ")", null));
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
		Wants w = team.wants();
		for (KillRules.Goal g : w == null ? Collections.<KillRules.Goal>emptyList() : w.kc)
		{
			if (taken.containsKey(g.id))
			{
				lines.append('\n').append(taken.get(g.id)).append(" x ").append(g.source).append(" for ").append(g.title)
					.append(" (").append(Math.max(0, g.left)).append(" to go)");
			}
		}
		// a screenshot at kcMarks: it goes to the team's channel, the tile's proof
		sender.send(Sender.payload("KC", rsn, lines.toString(), extra), shot);
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
		sender.send(Sender.payload("LOOT", rsn, content, extra), shot);
	}
}
