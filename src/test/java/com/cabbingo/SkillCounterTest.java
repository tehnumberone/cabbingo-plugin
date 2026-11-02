package com.cabbingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class SkillCounterTest
{
	@Test
	public void countsLogsFishAndTheBackpack()
	{
		SkillCounter c = new SkillCounter();
		assertTrue(c.message("You get some yew logs."));
		assertTrue(c.message("You get some yew logs."));
		assertTrue(c.message("You get some logs."));
		assertTrue(c.message("You catch a shark!"));
		assertTrue(c.message("You catch some shrimps."));
		assertTrue(c.message("You catch a leaping trout."));
		assertTrue(c.message("You catch 6 karambwanji."));
		assertFalse(c.message("You swing your axe at the tree."));
		// a tick with Crafting XP: the backpack's gains count for Crafting
		c.gained(Arrays.asList("Crafting"), Collections.singletonMap("Black d'hide body", 1));
		// a tick with Woodcutting XP alone: its logs came from the message already
		c.gained(Arrays.asList("Woodcutting"), Collections.singletonMap("Yew logs", 1));

		Map<String, Map<String, Integer>> taken = c.take();
		assertEquals("{Yew logs=2, Logs=1}", taken.get("Woodcutting").toString());
		assertEquals("{Raw shark=1, Raw shrimps=1, Leaping trout=1, Raw karambwanji=6}", taken.get("Fishing").toString());
		assertEquals("{Black d'hide body=1}", taken.get("Crafting").toString());
		assertTrue(c.take().isEmpty());
	}

	@Test
	public void wantedItemsAreDueAMinuteAfterTheLastOne()
	{
		SkillCounter c = new SkillCounter();
		c.wanted = "Yew logs"::equals;
		long now = System.currentTimeMillis();
		c.message("You get some logs.");
		assertFalse(c.due(now + 60_000)); // nobody wants plain logs: the five minute batch
		c.message("You get some yew logs.");
		assertFalse(c.due(now + 30_000));
		assertTrue(c.due(System.currentTimeMillis() + 60_000));
		c.take();
		assertFalse(c.due(now + 120_000));
	}
}
