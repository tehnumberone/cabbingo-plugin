package com.cabbingo;

import java.util.ArrayList;
import java.util.Collection;
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

	/** A kill count side the team can still add to (the server's kcGoals). */
	static class Goal
	{
		String id; // the tile's id, ':flip' for its flipped side
		String title;
		String source; // the monster, as the loot tracker names it
		int left; // kills still needed
		Food food; // null: any food
		long from; // a tile of the day: its day, from..to; 0 for a grid tile
		long to;
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
	private boolean now; // a goal is finished by what waits: send at the next chance

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
		Map<Goal, String> broke = new LinkedHashMap<>();
		for (Goal g : goals)
		{
			if (g.left <= 0 || g.source == null || !g.source.trim().equalsIgnoreCase(source.trim()) || time < g.from || (g.to > 0 && time >= g.to))
			{
				continue;
			}
			String why = broken(g.food, backpack, eaten);
			if (why != null)
			{
				broke.put(g, why);
				continue;
			}
			pending.merge(g.id, 1, Integer::sum);
			g.left--;
			now |= g.left <= 0;
			if (due == 0)
			{
				due = time + WAIT;
			}
		}
		eaten.clear();
		return broke;
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
		return out;
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
