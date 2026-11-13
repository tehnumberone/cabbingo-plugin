package com.cabbingo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

// Kill count tiles (2026-11-06): kills of one monster that count only within the tile's food rule. On each kill of a
// goal's monster the plugin checks the backpack and what was eaten since the last kill; a kill that broke the rule is
// told in the chat and never sent. The kills that kept to it go out together, two minutes after the first, or at once when
// they finish the tile.
@Singleton
class KillRules
{
	static final long WAIT = 120_000; // each post that counts syncs the whole team: not more often (2026-11-12)

	/**
	 * Gear and inventory rules (GearRules in models/bingo.ts), of a kill count side or a random event. gear: slot (head,
	 * quiver, cape, neck, ammo, weapon, body, shield, legs, hands, feet, ring) -> empty, nostats, only or not; carry: one,
	 * all or not; maxValue: what everything worn may be worth at most, in gp.
	 */
	static class Rules
	{
		Map<String, Items> gear;
		Items carry;
		Long maxValue;
	}

	static class Items
	{
		String mode;
		List<String> items = new ArrayList<>();
	}

	/** A random event of the team with rules, from..to. */
	static class Event extends Rules
	{
		String title;
		String icon; // the event's picture, item-images/<id>.png for an item
		long from;
		long to;
	}

	// the slot's word in a warning, as gearLines words it
	private static final Map<String, String[]> SLOTS = new LinkedHashMap<>();
	static
	{
		String[][] slots = {{"head", "Head", "hat"}, {"quiver", "Quiver", "quiver ammo"}, {"cape", "Cape", "cape"}, {"neck", "Neck", "amulet"}, {"ammo", "Ammo", "ammo"},
			{"weapon", "Weapon", "weapon"}, {"body", "Body", "body armour"}, {"shield", "Shield", "shield"}, {"legs", "Legs", "leg armour"},
			{"hands", "Hands", "gloves"}, {"feet", "Feet", "boots"}, {"ring", "Ring", "ring"}};
		for (String[] s : slots)
		{
			SLOTS.put(s[0], new String[]{s[1], s[2]});
		}
	}

	private static String list(List<String> items, String word)
	{
		return items.size() < 2 ? String.join("", items)
			: String.join(", ", items.subList(0, items.size() - 1)) + " " + word + " " + items.get(items.size() - 1);
	}

	/**
	 * The rules broken, one line each in gearLines' words. worn: per slot every item it held (during a kill: since the
	 * last one; empty for nothing); bag: the inventory now; bagSeen: everything it held (the same as bag outside a kill);
	 * statless: the names (key) of items without combat stats; worth: the most the gear was worth, in gp.
	 */
	static List<String> broken(Rules r, Map<String, Set<String>> worn, Collection<String> bag, Collection<String> bagSeen, Set<String> statless, long worth)
	{
		List<String> out = new ArrayList<>();
		if (r == null)
		{
			return out;
		}
		for (Map.Entry<String, String[]> slot : SLOTS.entrySet())
		{
			Items s = r.gear == null ? null : r.gear.get(slot.getKey());
			if (s == null)
			{
				continue;
			}
			Set<String> had = worn.getOrDefault(slot.getKey(), Collections.emptySet());
			Set<String> listed = keys(s.items);
			if ("empty".equals(s.mode) && !had.isEmpty())
			{
				out.add("No " + slot.getValue()[1] + " allowed");
			}
			if ("nostats".equals(s.mode) && (had.isEmpty() || had.stream().anyMatch(n -> !statless.contains(key(n)))))
			{
				out.add(slot.getValue()[0] + ": an item without stats");
			}
			if ("only".equals(s.mode) && (had.isEmpty() || had.stream().anyMatch(n -> !listed.contains(key(n)))))
			{
				out.add(slot.getValue()[0] + ": only " + list(s.items, "or"));
			}
			if ("not".equals(s.mode) && had.stream().anyMatch(n -> listed.contains(key(n))))
			{
				out.add("No " + list(s.items, "or") + " allowed");
			}
		}
		Items c = r.carry;
		if (c != null)
		{
			Set<String> listed = keys(c.items);
			Set<String> now = keys(bag);
			if ("one".equals(c.mode) && listed.stream().noneMatch(now::contains))
			{
				out.add("Carry " + list(c.items, "or"));
			}
			if ("all".equals(c.mode) && !now.containsAll(listed))
			{
				out.add("Carry " + list(c.items, "and"));
			}
			if ("not".equals(c.mode) && keys(bagSeen).stream().anyMatch(listed::contains))
			{
				out.add("No " + list(c.items, "or") + " in the inventory");
			}
		}
		if (r.maxValue != null && worth > r.maxValue)
		{
			out.add(String.format(Locale.US, "Gear worth at most %,d gp", r.maxValue));
		}
		return out;
	}

	/** A broken rule of a random event, as the infobox and the chat say it: "Random event: no hat allowed". */
	static String eventLine(String line)
	{
		return "Random event: " + Character.toLowerCase(line.charAt(0)) + line.substring(1);
	}

	private static Set<String> keys(Collection<String> names)
	{
		Set<String> out = new HashSet<>();
		for (String n : names)
		{
			out.add(key(n));
		}
		return out;
	}

	// what was worn and carried since the last kill, for the gear rules (saw, on every game tick)
	private final Map<String, Set<String>> wornSeen = new HashMap<>();
	private final Set<String> bagSeen = new HashSet<>();
	// the items seen worn that have no combat stats (key), as the plugin found them in the game's item stats
	final Set<String> statless = ConcurrentHashMap.newKeySet();
	private long worthNow; // what the gear is worth now, in gp
	private long worthSeen; // the most it was worth since the last kill

	/** What the gear worn is worth now (each game tick). */
	synchronized void worth(long gp)
	{
		worthNow = gp;
		worthSeen = Math.max(worthSeen, gp);
	}

	synchronized long worthNow()
	{
		return worthNow;
	}

	/** What the player wears (slot -> item) and carries now; kept until the next kill. */
	synchronized void saw(Map<String, String> worn, Collection<String> bag)
	{
		worn.forEach((slot, name) -> wornSeen.computeIfAbsent(slot, k -> new HashSet<>()).add(name));
		bagSeen.addAll(bag);
	}

	/** A kill count side the team can still add to (the server's kcGoals). */
	static class Goal extends Rules
	{
		String id; // the tile's id, ':flip' for its flipped side
		String title;
		String source; // the monster, as the loot tracker names it
		int left; // kills still needed
		Food food; // null: any food
		Food potions; // null: any potion
		long from; // a tile of the day: its day, from..to; 0 for a grid tile
		long to;
		int[] marks; // the kills still to go at which a screenshot goes along: the tile's proof (kcMarks)
	}

	static class Food
	{
		String mode; // only, not, none
		List<String> items = new ArrayList<>();
	}

	/** One item of the backpack, or one that was eaten: its name and whether it can be eaten or drunk. */
	static class Carried
	{
		final String name;
		final boolean food;
		final boolean potion;

		Carried(String name, boolean food, boolean potion)
		{
			this.name = name;
			this.food = food;
			this.potion = potion;
		}
	}

	private final List<String> eaten = new ArrayList<>(); // since the last kill
	private final List<String> drunk = new ArrayList<>(); // since the last kill
	private final Map<String, Integer> pending = new LinkedHashMap<>(); // goal id -> kills not sent yet
	private long due; // 0: nothing waiting
	private boolean now; // a goal is finished by what waits, or a mark was hit: send at the next chance
	private boolean shot; // a mark was hit: the send takes a screenshot along

	/** What the player ate (the "Eat" option), until the next kill. */
	synchronized void ate(String name)
	{
		eaten.add(name);
	}

	/** What the player drank (the "Drink" option), until the next kill. */
	synchronized void drank(String name)
	{
		drunk.add(name);
	}

	synchronized void forgetEaten()
	{
		eaten.clear();
		drunk.clear();
	}

	/** Why a kill breaks the food rule, or null when it keeps to it. */
	static String broken(Food food, Collection<Carried> backpack, Collection<String> ate)
	{
		return broken(food, backpack, ate, false);
	}

	/**
	 * The same for food or, with potion, for potions: anything with "Drink", by name without the dose ("Prayer
	 * potion(4)" is "Prayer potion(1)" too).
	 */
	static String broken(Food rule, Collection<Carried> backpack, Collection<String> used, boolean potion)
	{
		if (rule == null)
		{
			return null;
		}
		Set<String> listed = new HashSet<>();
		for (String i : rule.items)
		{
			listed.add(potion ? dose(i) : key(i));
		}
		List<Carried> all = new ArrayList<>(backpack);
		for (String a : used)
		{
			all.add(new Carried(a, !potion, potion));
		}
		for (Carried c : all)
		{
			boolean kind = potion ? c.potion : c.food;
			String k = potion ? dose(c.name) : key(c.name);
			boolean bad = "not".equals(rule.mode) ? listed.contains(k)
				: "only".equals(rule.mode) ? kind && !listed.contains(k)
				: kind; // none
			if (bad)
			{
				return c.name;
			}
		}
		return null;
	}

	private static String dose(String name)
	{
		return key(name).replaceFirst("\\(\\d\\)$", "");
	}

	/**
	 * A kill of source: each goal of that monster takes it when it keeps to that goal's food, potion and gear rules (worn:
	 * slot -> item now). A kill that broke the rules of a random event running at the kill counts for no tile either
	 * (user, 2026-11-07). Returns the goals whose rule it broke, with what broke it, for the chat warning.
	 */
	synchronized Map<Goal, String> kill(List<Goal> goals, String source, Collection<Carried> backpack, Map<String, String> worn, List<Event> events, long time)
	{
		List<String> eventLines = eventsBroken(events, worn, names(backpack), time);
		Map<Goal, String> broke = new LinkedHashMap<>();
		for (Goal g : goals)
		{
			if (g.left <= 0 || g.source == null || !g.source.trim().equalsIgnoreCase(source.trim()) || time < g.from || (g.to > 0 && time >= g.to))
			{
				continue;
			}
			String why = broken(g.food, backpack, eaten);
			why = why != null ? why : broken(g.potions, backpack, drunk, true);
			if (why == null)
			{
				List<String> gear = broken(g, wornSeen, names(backpack), bagSeen, statless, worthSeen);
				gear.addAll(eventLines);
				why = gear.isEmpty() ? null : String.join(", ", gear);
			}
			if (why != null)
			{
				broke.put(g, why);
				continue;
			}
			pending.merge(g.id, 1, Integer::sum);
			g.left--;
			for (int mark : g.marks == null ? new int[0] : g.marks)
			{
				shot |= mark == g.left;
			}
			now |= g.left <= 0 || shot;
			if (due == 0)
			{
				due = time + WAIT;
			}
		}
		eaten.clear();
		drunk.clear();
		next(worn, names(backpack));
		return broke;
	}

	/**
	 * The rules of the random events running at time that the kill broke, with what is worn and carried now: one line
	 * each, as eventLine says it. They hold for every tile, drops and kill counts alike (user, 2026-11-07).
	 */
	synchronized List<String> eventsBroken(List<Event> events, Map<String, String> worn, Collection<String> bag, long time)
	{
		saw(worn, bag);
		List<String> out = new ArrayList<>();
		for (Event e : events)
		{
			if (time >= e.from && time < e.to)
			{
				broken(e, wornSeen, bag, bagSeen, statless, worthSeen).forEach(l -> out.add(eventLine(l)));
			}
		}
		return out;
	}

	/** After a kill: the next one starts from what is worn and carried now. */
	synchronized void next(Map<String, String> worn, Collection<String> bag)
	{
		wornSeen.clear();
		bagSeen.clear();
		worthSeen = worthNow;
		saw(worn, bag);
	}

	private static List<String> names(Collection<Carried> backpack)
	{
		List<String> out = new ArrayList<>();
		for (Carried c : backpack)
		{
			out.add(c.name);
		}
		return out;
	}

	synchronized boolean due(long time)
	{
		return now || (due != 0 && time >= due);
	}

	/** The kills of a goal not sent yet: a refetched goal's left does not have them (Kills.goals). */
	synchronized int pending(String id)
	{
		return pending.getOrDefault(id, 0);
	}

	/** The kills waiting, per goal id; empties them. */
	synchronized Map<String, Integer> take()
	{
		Map<String, Integer> out = new LinkedHashMap<>(pending);
		pending.clear();
		due = 0;
		now = false;
		shot = false;
		return out;
	}

	/** Whether the next send takes a screenshot along; ask before take(). */
	synchronized boolean shot()
	{
		return shot;
	}

	static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
