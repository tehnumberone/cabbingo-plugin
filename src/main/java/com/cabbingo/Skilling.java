package com.cabbingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Skilling tiles: what the player gathered or made (SkillCounter), sent every few minutes, sooner when a tile wants
 * it. XP per skill as last seen, the skills that gained XP this tick and last, and the backpack as it was and what it
 * gained a tick ago. On the EventBus while the plugin runs.
 */
@Slf4j
@Singleton
class Skilling
{
	// Motherlode Mine sack and Blast Furnace dispenser (2026-11-02): ore taken from the sack and bars made in the furnace
	// come without XP in that tick, so they count from the game's own counters (as RuneLite's own plugins read them).
	private static final Set<Integer> MOTHERLODE = Set.of(14679, 14680, 14681, 14935, 14936, 14937, 15191, 15192, 15193);
	private static final int BLAST_FURNACE = 7757;
	private static final Map<Integer, String> FURNACE_BARS = Map.of(
		VarbitID.BLAST_FURNACE_BRONZE_BARS, "Bronze bar", VarbitID.BLAST_FURNACE_IRON_BARS, "Iron bar",
		VarbitID.BLAST_FURNACE_STEEL_BARS, "Steel bar", VarbitID.BLAST_FURNACE_MITHRIL_BARS, "Mithril bar",
		VarbitID.BLAST_FURNACE_ADAMANTITE_BARS, "Adamantite bar", VarbitID.BLAST_FURNACE_RUNITE_BARS, "Runite bar",
		VarbitID.BLAST_FURNACE_GOLD_BARS, "Gold bar", VarbitID.BLAST_FURNACE_SILVER_BARS, "Silver bar");

	private final Client client;
	private final ItemManager itemManager;
	private final Team team;
	private final Gear gear;
	private final Sender sender;

	private final SkillCounter skills = new SkillCounter();
	private final Map<String, Integer> skillXp = new HashMap<>();
	private Set<String> xpNow = new HashSet<>();
	private Set<String> xpBefore = new HashSet<>();
	private Map<Integer, Integer> backpack;
	private Map<String, Integer> backpackGained = new HashMap<>();
	private volatile String skillRsn;
	private int sackBefore = -1;
	private int sackTicks;
	private final Map<Integer, Integer> barsBefore = new HashMap<>();
	// Herb patches (herb sack): the varbit of the herb patch last picked, the herb it had a tick ago, and the herb picked
	// on the last tick with Farming XP (checked a tick later, once the backpack had its chance to get it).
	private int herbVarbit = -1;
	private String herbBefore;
	private String herbOnXp;

	@Inject
	Skilling(Client client, ItemManager itemManager, Team team, Gear gear, Sender sender)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.team = team;
		this.gear = gear;
		this.sender = sender;
	}

	/** What the team's Skilling tiles want, from a server answer (null: nothing). */
	void goals(Wants w)
	{
		skills.goals(w == null || w.skill == null ? Collections.emptyList() : w.skill, w == null ? null : w.rev);
	}

	/** After a logout or hop: the next login starts from scratch. */
	void reset()
	{
		backpack = null;
		sackBefore = -1;
		barsBefore.clear();
		herbVarbit = -1;
		herbBefore = null;
		herbOnXp = null;
		skillXp.clear();
	}

	// What was gathered or made goes to the server every five minutes (the plugin's schedule), in one post per skill;
	// two minutes after the last item a tile wants, when sooner (tick).
	void send()
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
			sender.send(Sender.payload("SKILL", rsn, "**" + rsn + "**: " + skill + lines, extra), shots.contains(skill));
		});
	}

	/** Once a tick while the player plays in the bingo. */
	void tick(String player)
	{
		skillRsn = player;
		countSkilling();
		if (skills.due(System.currentTimeMillis()))
		{
			send();
		}
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

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// logs and fish: their messages are SPAM (shown with the game filter off)
		// (none while a random event's rule is broken: countSkilling)
		if ((e.getType() == ChatMessageType.SPAM || e.getType() == ChatMessageType.GAMEMESSAGE) && team.playing() != null && gear.ruleLines().isEmpty())
		{
			skills.message(Text.removeTags(e.getMessage()));
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
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

	// The backpack, by item id and quantity (noted items as their own item: never a skilling gain)
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
		boolean ok = gear.ruleLines().isEmpty();
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
				if (more <= 0)
				{
					return;
				}
				ItemComposition item = itemManager.getItemComposition(id);
				if (item.getNote() != -1)
				{
					return;
				}
				if (sackTicks > 0)
				{
					if (ok)
					{
						skills.add("Mining", item.getName(), more);
					}
				}
				else
				{
					backpackGained.merge(item.getName(), more, Integer::sum);
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
}
