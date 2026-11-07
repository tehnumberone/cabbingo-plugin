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

// Kill count tiles (2026-11-06): kills of one monster that count only within the tile's food rule. On each kill of a
// goal's monster the plugin checks the backpack and what was eaten since the last kill; a kill that broke the rule is
// told in the chat and never sent. The kills that kept to it go out together, a minute after the first, or at once when
// they finish the tile.
class KillRules
{
	static final long WAIT = 60_000;

	/**
	 * Gear and inventory rules (GearRules in models/bingo.ts), of a kill count side or a random event. gear: slot (head,
	 * cape, neck, ammo, weapon, body, shield, legs, hands, feet, ring) -> empty, only or not; carry: one, all or not.
	 */
	static class Rules
	{
		Map<String, Items> gear;
		Items carry;
	}

	static class Items
	{
		String mode;
		List<String> items = new ArrayList<>();
	}

	/** A random event of the team with rules, from..to (DinkTeam.events). */
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
		String[][] slots = {{"head", "Head", "hat"}, {"cape", "Cape", "cape"}, {"neck", "Neck", "amulet"}, {"ammo", "Ammo", "ammo"},
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
	 * last one; empty for nothing); bag: the inventory now; bagSeen: everything it held (the same as bag outside a kill).
	 */
	static List<String> broken(Rules r, Map<String, Set<String>> worn, Collection<String> bag, Collection<String> bagSeen)
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
		long from; // a tile of the day: its day, from..to; 0 for a grid tile
		long to;
		int[] marks; // the kills still to go at which a screenshot goes along: the tile's proof (kcMarks)
	}

	static class Food
	{
		String mode; // only, not, none
		List<String> items = new ArrayList<>();
	}

	/** One item of the backpack, or one that was eaten: its name and whether it can be eaten. */
	static class Carried
	{
		final String name;
		final boolean food;

		Carried(String name, boolean food)
		{
			this.name = name;
			this.food = food;
		}
	}

	private final List<String> eaten = new ArrayList<>(); // since the last kill
	private final Map<String, Integer> pending = new LinkedHashMap<>(); // goal id -> kills not sent yet
	private long due; // 0: nothing waiting
	private boolean now; // a goal is finished by what waits, or a mark was hit: send at the next chance
	private boolean shot; // a mark was hit: the send takes a screenshot along

	/** What the player ate (the "Eat" option), until the next kill. */
	synchronized void ate(String name)
	{
		eaten.add(name);
	}

	synchronized void forgetEaten()
	{
		eaten.clear();
	}

	/** Why a kill breaks the food rule, or null when it keeps to it. */
	static String broken(Food food, Collection<Carried> backpack, Collection<String> ate)
	{
		if (food == null)
		{
			return null;
		}
		Set<String> listed = new HashSet<>();
		for (String i : food.items)
		{
			listed.add(key(i));
		}
		List<Carried> all = new ArrayList<>(backpack);
		for (String a : ate)
		{
			all.add(new Carried(a, true));
		}
		for (Carried c : all)
		{
			boolean bad = "not".equals(food.mode) ? listed.contains(key(c.name))
				: "only".equals(food.mode) ? c.food && !listed.contains(key(c.name))
				: c.food; // none
			if (bad)
			{
				return c.name;
			}
		}
		return null;
	}

	/**
	 * A kill of source: each goal of that monster takes it when it keeps to that goal's food rule. Returns the goals whose
	 * rule it broke, with the food that broke it, for the chat warning.
	 */
	synchronized Map<Goal, String> kill(List<Goal> goals, String source, Collection<Carried> backpack, long time)
	{
		return kill(goals, source, backpack, Collections.emptyMap(), time);
	}

	/** The same, with what is worn now (slot -> item) for the gear rules. */
	synchronized Map<Goal, String> kill(List<Goal> goals, String source, Collection<Carried> backpack, Map<String, String> worn, long time)
	{
		return kill(goals, source, backpack, worn, Collections.emptyList(), time);
	}

	/**
	 * The same, with the team's random events: a kill that broke the rules of one running at the kill counts for no tile,
	 * like a broken food rule (user, 2026-11-07).
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
			if (why == null)
			{
				List<String> gear = broken(g, wornSeen, names(backpack), bagSeen);
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
				broken(e, wornSeen, bag, bagSeen).forEach(l -> out.add(eventLine(l)));
			}
		}
		return out;
	}

	/** After a kill: the next one starts from what is worn and carried now. */
	synchronized void next(Map<String, String> worn, Collection<String> bag)
	{
		wornSeen.clear();
		bagSeen.clear();
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

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
