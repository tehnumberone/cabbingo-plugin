package com.cabbingo;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// What a player gathered or made per skill until the next send, for Skilling tiles (2026-11-01).
// Woodcutting and Fishing count from the game's message ("You get some yew logs."), as RuneLite's own Woodcutting
// plugin does: logs and fish that go into a log basket or fish barrel never reach the backpack. Every other skill
// counts what the backpack gained in a tick that gave that skill XP.
class SkillCounter
{
	// the same list as SKILLING in Cabbingo's models/bingo.ts
	static final List<String> SKILLS = Arrays.asList(
		"Cooking", "Crafting", "Farming", "Fishing", "Fletching", "Herblore", "Hunter", "Mining", "Runecraft", "Smithing", "Woodcutting");
	private static final List<String> BY_MESSAGE = Arrays.asList("Woodcutting", "Fishing");
	private static final Pattern LOGS = Pattern.compile("You get (?:some|an?) ((?:[\\w' -]+ )?logs?)\\.");
	private static final Pattern FISH = Pattern.compile("You catch (an?|some|\\d+) ([\\w' -]+?)[.!]");

	private final Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
	// Items a tile wants: the counts go out a minute after the last of them was counted, not at the next batch.
	volatile Predicate<String> wanted = name -> false;
	private long wantedAt;

	synchronized void add(String skill, String item, int quantity)
	{
		counts.computeIfAbsent(skill, s -> new LinkedHashMap<>()).merge(item, quantity, Integer::sum);
		if (wanted.test(item))
		{
			wantedAt = System.currentTimeMillis();
		}
	}

	// A minute without a new wanted item since the last one: time to send.
	synchronized boolean due(long now)
	{
		return wantedAt > 0 && now - wantedAt >= 60_000;
	}

	// A game message that names a log or fish: counted, true.
	boolean message(String text)
	{
		Matcher m = LOGS.matcher(text);
		if (m.matches())
		{
			add("Woodcutting", capitalize(m.group(1)), 1);
			return true;
		}
		m = FISH.matcher(text);
		if (m.matches())
		{
			String fish = m.group(2).toLowerCase(Locale.ROOT);
			int quantity = m.group(1).matches("\\d+") ? Integer.parseInt(m.group(1)) : 1;
			// (barbarian fishing's leaping fish have no "Raw" in their name)
			add("Fishing", fish.startsWith("leaping ") ? capitalize(fish) : "Raw " + fish, quantity);
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

	// Everything counted since the last call, per skill; empty again afterwards.
	synchronized Map<String, Map<String, Integer>> take()
	{
		Map<String, Map<String, Integer>> taken = new LinkedHashMap<>(counts);
		counts.clear();
		wantedAt = 0;
		return taken;
	}

	static String capitalize(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
