package com.cabbingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class KillRulesTest
{
	private static KillRules.Goal goal(String id, String source, int left, String mode, String... items)
	{
		KillRules.Goal g = new KillRules.Goal();
		g.id = id;
		g.title = "Tile " + id;
		g.source = source;
		g.left = left;
		if (mode != null)
		{
			g.food = new KillRules.Food();
			g.food.mode = mode;
			g.food.items = Arrays.asList(items);
		}
		return g;
	}

	private static final KillRules.Carried KARAMBWAN = new KillRules.Carried("Cooked karambwan", true);
	private static final KillRules.Carried SHARK = new KillRules.Carried("Shark", true);
	private static final KillRules.Carried RUNES = new KillRules.Carried("Rune pouch", false);

	@Test
	public void foodRules()
	{
		KillRules.Food not = goal("a", "x", 1, "not", "Cooked karambwan").food;
		assertEquals("Cooked karambwan", KillRules.broken(not, Arrays.asList(RUNES, KARAMBWAN), Collections.emptyList()));
		assertNull(KillRules.broken(not, Arrays.asList(RUNES, SHARK), Collections.emptyList()));
		assertEquals("eaten counts like carried", "Cooked karambwan", KillRules.broken(not, Collections.emptyList(), Collections.singletonList("Cooked karambwan")));
		KillRules.Food none = goal("a", "x", 1, "none").food;
		assertEquals("Shark", KillRules.broken(none, Arrays.asList(RUNES, SHARK), Collections.emptyList()));
		assertNull("no food at all keeps to it", KillRules.broken(none, Collections.singletonList(RUNES), Collections.emptyList()));
		KillRules.Food only = goal("a", "x", 1, "only", "shark").food;
		assertNull("names without case", KillRules.broken(only, Arrays.asList(SHARK, RUNES), Collections.emptyList()));
		assertEquals("Cooked karambwan", KillRules.broken(only, Arrays.asList(SHARK, KARAMBWAN), Collections.emptyList()));
		assertNull("no rule", KillRules.broken(null, Collections.singletonList(KARAMBWAN), Collections.emptyList()));
	}

	@Test
	public void killsCountPerGoalAndBrokenOnesAreSaid()
	{
		KillRules r = new KillRules();
		KillRules.Goal noKarambwan = goal("t1", "Venator", 2, "not", "Cooked karambwan");
		KillRules.Goal anyFood = goal("t2", "Venator", 5, null);
		KillRules.Goal other = goal("t3", "Vorkath", 5, null);
		List<KillRules.Goal> goals = Arrays.asList(noKarambwan, anyFood, other);

		Map<KillRules.Goal, String> broke = r.kill(goals, "venator", Collections.singletonList(KARAMBWAN), 0);
		assertEquals(Collections.singletonMap(noKarambwan, "Cooked karambwan"), broke);
		assertFalse(r.due(59_999));
		assertTrue("a minute after the first kill", r.due(60_000));

		r.ate("Cooked karambwan");
		assertEquals("eaten since the last kill", 1, r.kill(goals, "Venator", Collections.emptyList(), 1000).size());
		assertTrue("the next kill starts clean", r.kill(goals, "Venator", Collections.emptyList(), 2000).isEmpty());
		r.kill(goals, "Venator", Collections.emptyList(), 3000);
		assertTrue("t1 finished: send now", r.due(3000));
		Map<String, Integer> taken = r.take();
		assertEquals(Integer.valueOf(2), taken.get("t1"));
		assertEquals(Integer.valueOf(4), taken.get("t2"));
		assertNull(taken.get("t3"));
		assertTrue(r.kill(goals, "Venator", Collections.emptyList(), 4000).isEmpty());
		assertNull("a finished goal takes no more", r.take().get("t1"));
		assertFalse(r.due(Long.MAX_VALUE));

		KillRules.Goal tomorrow = goal("t4", "Venator", 5, null);
		tomorrow.from = 10_000;
		tomorrow.to = 20_000;
		List<KillRules.Goal> day = Collections.singletonList(tomorrow);
		r.kill(day, "Venator", Collections.emptyList(), 9_999);
		r.kill(day, "Venator", Collections.emptyList(), 20_000);
		assertTrue("a tile of the day counts during its day only", r.take().isEmpty());
		r.kill(day, "Venator", Collections.emptyList(), 10_000);
		assertEquals(Integer.valueOf(1), r.take().get("t4"));
	}
}
