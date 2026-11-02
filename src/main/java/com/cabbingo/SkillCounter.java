package com.cabbingo;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What a player gathered or made per skill until the next send, for Skilling tiles (2026-11-01).
// Woodcutting and Fishing count from the game's message ("You get some yew logs."), as RuneLite's own Woodcutting
// plugin does: logs and fish that go into a log basket or fish barrel never reach the backpack. Every other skill
// counts what the backpack gained in a tick that gave that skill XP. Gems that go into an open gem bag count from the
// message that named them just before (2026-11-02, messages as other Plugin Hub plugins read them). Herbs that go into
// an open herb sack come with no message at all (tried in the game 2026-11-02): the plugin counts them from the patch
// (herbAt).
class SkillCounter
{
	// the same list as SKILLING in Cabbingo's models/bingo.ts
	static final List<String> SKILLS = Arrays.asList(
		"Cooking", "Crafting", "Farming", "Fishing", "Fletching", "Herblore", "Hunter", "Mining", "Runecraft", "Smithing", "Woodcutting");
	private static final List<String> BY_MESSAGE = Arrays.asList("Woodcutting", "Fishing");
	private static final Pattern LOGS = Pattern.compile("You get (?:some|an?) ((?:[\\w' -]+ )?logs?)\\.");
	private static final Pattern FISH = Pattern.compile("You catch (an?|some|\\d+) ([\\w' -]+?)[.!]");
	private static final Pattern GEM = Pattern.compile("You just (?:mined|found) (?:a piece of|an?) ([\\w ]+)!");
	// "You put it into your gem bag." (tried in the game 2026-11-02), "... straight into your open gem bag." (other plugins); the gem
	// sack takes opal, jade and red topaz too (user, 2026-11-02)
	private static final Pattern GEM_BAG = Pattern.compile("You put it (?:straight )?into your (?:open )?gem (?:bag|sack)\\.");

	private final Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
	// What Skilling tiles want, from the server: groups of items (lower case) and how many more of them, mixed, finish
	// a side (0: never on their own). The counts go out a minute after the last wanted item, not at the next batch, and
	// at once when a group has its number (2026-11-03).
	static class Goal
	{
		Set<String> items = new HashSet<>();
		int left;
	}

	volatile List<Goal> goals = Collections.emptyList();
	private long wantedAt;
	private boolean finishes;
	// the gem the message before named, for the gem bag message right after it
	private String gem;
	// the fish last caught: an extra one (Rada's blessing and the like) does not say which (tried in the game 2026-11-02)
	private String fish;

	synchronized void add(String skill, String item, int quantity)
	{
		counts.computeIfAbsent(skill, s -> new LinkedHashMap<>()).merge(item, quantity, Integer::sum);
		String key = item.toLowerCase(Locale.ROOT);
		for (Goal goal : goals)
		{
			if (goal.items.contains(key))
			{
				wantedAt = System.currentTimeMillis();
				finishes |= goal.left > 0 && counted(goal.items) >= goal.left;
			}
		}
	}

	private int counted(Set<String> items)
	{
		int sum = 0;
		for (Map<String, Integer> skill : counts.values())
		{
			for (Map.Entry<String, Integer> e : skill.entrySet())
			{
				sum += items.contains(e.getKey().toLowerCase(Locale.ROOT)) ? e.getValue() : 0;
			}
		}
		return sum;
	}

	// A side's last item, or a minute without a new wanted item since the last one: time to send.
	synchronized boolean due(long now)
	{
		return finishes || (wantedAt > 0 && now - wantedAt >= 60_000);
	}

	// A game message that names a log, fish or bagged gem: counted, true.
	boolean message(String text)
	{
		String named = gem;
		gem = null;
		Matcher m = GEM.matcher(text);
		if (m.matches())
		{
			gem = "Uncut " + m.group(1).toLowerCase(Locale.ROOT);
			return false; // a gem in the backpack counts from there
		}
		if (named != null && GEM_BAG.matcher(text).matches())
		{
			add("Mining", named, 1);
			return true;
		}
		m = LOGS.matcher(text);
		if (m.matches())
		{
			add("Woodcutting", capitalize(m.group(1)), 1);
			return true;
		}
		m = FISH.matcher(text);
		if (m.matches())
		{
			String caught = m.group(2).toLowerCase(Locale.ROOT);
			int quantity = m.group(1).matches("\\d+") ? Integer.parseInt(m.group(1)) : 1;
			// (barbarian fishing's leaping fish have no "Raw" in their name)
			fish = caught.startsWith("leaping ") ? capitalize(caught) : "Raw " + caught;
			add("Fishing", fish, quantity);
			return true;
		}
		// "Rada's blessing enabled you to catch an extra fish."
		if (text.endsWith(" enabled you to catch an extra fish.") && fish != null)
		{
			add("Fishing", fish, 1);
			return true;
		}
		return false;
	}

	// The backpack's gains around a tick with XP in these skills: counted for the first of them that counts from the
	// backpack. Woodcutting and Fishing count from their messages instead, so never twice.
	void gained(Collection<String> skills, Map<String, Integer> items)
	{
		for (String skill : SKILLS)
		{
			if (skills.contains(skill) && !BY_MESSAGE.contains(skill))
			{
				items.forEach((name, quantity) -> add(skill, name, quantity));
				return;
			}
		}
	}

	// A herb patch's varbit value while it has herbs to pick: the grimy herb it gives, else null. The ranges are
	// RuneLite's own (Time Tracking's PatchImplementation.HERB): each herb has three harvestable values.
	private static final int[] HERB_VALUES = {8, 15, 22, 29, 36, 43, 50, 57, 64, 72, 79, 86, 93, 100, 107};
	private static final String[] HERBS = {"guam leaf", "marrentill", "tarromin", "harralander", "ranarr weed", "toadflax", "irit leaf",
		"avantoe", "huasca", "kwuarm", "snapdragon", "cadantine", "lantadyme", "dwarf weed", "torstol"};

	static String herbAt(int value)
	{
		for (int i = 0; i < HERB_VALUES.length; i++)
		{
			if (value >= HERB_VALUES[i] && value <= HERB_VALUES[i] + 2)
			{
				return "Grimy " + HERBS[i];
			}
		}
		return null;
	}

	// Everything counted since the last call, per skill; empty again afterwards.
	synchronized Map<String, Map<String, Integer>> take()
	{
		Map<String, Map<String, Integer>> taken = new LinkedHashMap<>(counts);
		counts.clear();
		wantedAt = 0;
		finishes = false;
		return taken;
	}

	static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
