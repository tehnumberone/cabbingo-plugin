package com.cabbingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
		assertTrue(c.message("You catch a raw salmon"));
		assertTrue(c.message("You catch 6 karambwanji."));
		assertTrue(c.message("Rada's blessing enabled you to catch an extra fish.")); // one more of the last one
		assertFalse(c.message("You swing your axe at the tree."));
		// a tick with Crafting XP: the backpack's gains count for Crafting
		c.gained(Arrays.asList("Crafting"), Collections.singletonMap("Black d'hide body", 1));
		// a tick with Woodcutting XP alone: its logs came from the message already
		c.gained(Arrays.asList("Woodcutting"), Collections.singletonMap("Yew logs", 1));

		Map<String, Map<String, Integer>> taken = c.take();
		assertEquals("{Yew logs=2, Logs=1}", taken.get("Woodcutting").toString());
		assertEquals("{Raw shark=1, Raw shrimps=1, Leaping trout=1, Raw salmon=1, Raw karambwanji=7}", taken.get("Fishing").toString());
		assertEquals("{Black d'hide body=1}", taken.get("Crafting").toString());
		assertTrue(c.take().isEmpty());
	}

	@Test
	public void readsHerbPatchesAndCountsTheGemBag()
	{
		assertEquals("Grimy ranarr weed", SkillCounter.herbAt(36));
		assertEquals("Grimy ranarr weed", SkillCounter.herbAt(38));
		assertEquals("Grimy huasca", SkillCounter.herbAt(64));
		assertEquals("Grimy torstol", SkillCounter.herbAt(109));
		assertEquals(null, SkillCounter.herbAt(3)); // weeded and empty
		assertEquals(null, SkillCounter.herbAt(35)); // ranarr still growing
		SkillCounter c = new SkillCounter();
		// a gem counts when the bag message follows its own message, never from an older one
		assertFalse(c.message("You just mined a piece of Jade!"));
		assertTrue(c.message("You put it straight into your open gem bag."));
		assertFalse(c.message("You just mined a Sapphire!"));
		assertTrue(c.message("You put it into your gem bag."));
		assertFalse(c.message("You just found a Diamond!"));
		assertFalse(c.message("You manage to mine some coal."));
		assertFalse(c.message("You put it straight into your open gem bag."));
		assertEquals("{Mining={Uncut jade=1, Uncut sapphire=1}}", c.take().toString());
	}

	@Test
	public void wantedItemsAreDueAWaitAfterTheLastOne()
	{
		SkillCounter c = new SkillCounter();
		c.goals(goals(0, "yew logs"), "a");
		long now = System.currentTimeMillis();
		c.message("You get some logs.");
		assertFalse(c.due(now + SkillCounter.WAIT)); // nobody wants plain logs: the five minute batch
		c.message("You get some yew logs.");
		assertFalse(c.due(now + 30_000));
		assertTrue(c.due(System.currentTimeMillis() + SkillCounter.WAIT));
		c.take();
		assertFalse(c.due(now + 2 * SkillCounter.WAIT));
		// the log that finishes a side goes out at once, counted over the side's logs together
		c.goals(goals(3, "yew logs", "logs"), "b");
		c.message("You get some yew logs.");
		assertFalse(c.due(System.currentTimeMillis()));
		c.message("You get some logs.");
		c.take(); // the five minute batch: the server's numbers stay as they were until a side finishes
		c.goals(goals(3, "yew logs", "logs"), "b");
		assertFalse(c.due(System.currentTimeMillis()));
		c.message("You get some logs.");
		assertTrue(c.due(System.currentTimeMillis()));
		c.take();
		c.goals(goals(3, "yew logs", "logs"), "c"); // new numbers: what was sent is in them
		c.message("You get some logs.");
		assertFalse(c.due(System.currentTimeMillis()));
	}

	@Test
	public void aMarkSendsAtOnceWithAScreenshot()
	{
		SkillCounter c = new SkillCounter();
		List<SkillCounter.Goal> goals = goals(13, "grimy ranarr weed");
		goals.get(0).marks = new int[] {10, 5, 0}; // 7 of 20: screenshots at 10, 15 and 20
		c.goals(goals, "a");
		c.add("Farming", "Grimy ranarr weed", 2);
		assertFalse(c.due(System.currentTimeMillis()));
		c.add("Farming", "Grimy ranarr weed", 2); // 11 of 20
		assertTrue(c.due(System.currentTimeMillis()));
		assertEquals(Collections.singleton("Farming"), c.takeShots());
		c.take();
		assertTrue(c.takeShots().isEmpty());
		c.add("Farming", "Grimy ranarr weed", 3); // 14: none, the next is at 15
		assertTrue(c.takeShots().isEmpty());
		c.add("Farming", "Grimy ranarr weed", 6); // 20: two marks at once, one screenshot
		assertEquals(Collections.singleton("Farming"), c.takeShots());
	}

	private static List<SkillCounter.Goal> goals(int left, String... items)
	{
		SkillCounter.Goal goal = new SkillCounter.Goal();
		goal.items.addAll(Arrays.asList(items));
		goal.left = left;
		return Collections.singletonList(goal);
	}
}
