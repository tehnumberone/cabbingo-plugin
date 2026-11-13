package com.cabbingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

/**
 * What the player wears and carries, for the gear and inventory rules of kill count tiles and random events, and the
 * random event infobox while a rule is broken.
 */
@Singleton
class Gear
{
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
	// an event's icon that is an item picture: the infobox shows that item
	private static final Pattern ITEM_ICON = Pattern.compile("item-images/(\\d+)\\.png$");
	private static final int MYSTERY_BOX = ItemID.MACRO_QUIZ_MYSTERY_BOX;

	private final Client client;
	private final ItemManager itemManager;
	private final InfoBoxManager infoBoxManager;
	private final CabbingoPlugin plugin;
	private final KillRules killRules;
	private final Team team;

	// The random event infobox: shown while the player breaks a rule of a running random event (user, 2026-11-07),
	// so they know before they start.
	private RuleBox ruleBox;
	// the rules of running random events broken right now (watch): skilling and a pet then count for no tile
	private volatile List<String> ruleLines = Collections.emptyList();

	@Inject
	Gear(Client client, ItemManager itemManager, InfoBoxManager infoBoxManager, CabbingoPlugin plugin, KillRules killRules, Team team)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.infoBoxManager = infoBoxManager;
		this.plugin = plugin;
		this.killRules = killRules;
		this.team = team;
	}

	/** The broken rules of running random events, as of the last tick; empty while none is broken. */
	List<String> ruleLines()
	{
		return ruleLines;
	}

	static List<KillRules.Event> events(Wants w)
	{
		return w == null || w.events == null ? Collections.emptyList() : w.events;
	}

	/** What the player wears now: slot -> item name, empty slots left out. */
	Map<String, String> worn()
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

	/** The backpack's item names. */
	List<String> bag()
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

	// Every game tick: gear and inventory are noted for the kill count rules, and the infobox shows the broken rules of
	// running random events.
	void watch(String player)
	{
		Wants w = team.wants();
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

	void clear()
	{
		ruleLines = Collections.emptyList();
		setRuleBox(null, ruleLines);
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
		Matcher m = ITEM_ICON.matcher(ev.icon == null ? "" : ev.icon);
		ruleBox = new RuleBox(itemManager.getImage(m.find() ? Integer.parseInt(m.group(1)) : MYSTERY_BOX), plugin, ev, tip);
		infoBoxManager.addInfoBox(ruleBox);
	}
}
