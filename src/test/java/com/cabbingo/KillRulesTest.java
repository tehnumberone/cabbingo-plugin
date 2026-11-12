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
		assertFalse(r.due(KillRules.WAIT - 1));
		assertTrue("WAIT after the first kill", r.due(KillRules.WAIT));

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

		KillRules.Goal big = goal("t5", "Venator", 8, null);
		big.marks = new int[]{6, 4, 2, 0};
		List<KillRules.Goal> bigs = Collections.singletonList(big);
		r.kill(bigs, "Venator", Collections.emptyList(), 0);
		assertFalse("no mark: waits a minute, no screenshot", r.due(1) || r.shot());
		r.kill(bigs, "Venator", Collections.emptyList(), 1);
		assertTrue("6 to go is a mark: at once, with a screenshot", r.due(1) && r.shot());
		r.take();
		assertFalse(r.shot());
	}

	@Test
	public void gearRules()
	{
		KillRules.Rules hat = new KillRules.Rules();
		hat.gear = new java.util.HashMap<>();
		KillRules.Items empty = new KillRules.Items();
		empty.mode = "empty";
		hat.gear.put("head", empty);
		KillRules.Items sand = new KillRules.Items();
		sand.mode = "one";
		sand.items = Arrays.asList("Sandstone (1kg)", "Sandstone (5kg)");
		hat.carry = sand;
		Map<String, java.util.Set<String>> helm = Collections.singletonMap("head", Collections.singleton("Helm of neitiznot"));
		assertEquals(Arrays.asList("No hat allowed", "Carry Sandstone (1kg) or Sandstone (5kg)"), KillRules.broken(hat, helm, Collections.emptyList(), Collections.emptyList()));
		assertTrue(KillRules.broken(hat, Collections.emptyMap(), Collections.singletonList("sandstone (5kg)"), Collections.emptyList()).isEmpty());

		// a kill with Tumeken's shadow worn at any time since the last kill breaks "not"
		KillRules.Goal whisperer = goal("w", "The Whisperer", 5, null);
		whisperer.gear = new java.util.HashMap<>();
		KillRules.Items shadow = new KillRules.Items();
		shadow.mode = "not";
		shadow.items = Collections.singletonList("Tumeken's shadow");
		whisperer.gear.put("weapon", shadow);
		List<KillRules.Goal> goals = Collections.singletonList(whisperer);
		KillRules r = new KillRules();
		r.saw(Collections.singletonMap("weapon", "Tumeken's shadow"), Collections.emptyList());
		Map<KillRules.Goal, String> broke = r.kill(goals, "The Whisperer", Collections.emptyList(), Collections.singletonMap("weapon", "Sanguinesti staff"), 0);
		assertEquals("No Tumeken's shadow allowed", broke.get(whisperer));
		assertTrue("the next kill starts clean", r.kill(goals, "The Whisperer", Collections.emptyList(), Collections.singletonMap("weapon", "Sanguinesti staff"), 1).isEmpty());

		// a running random event's rules hold for the tile too; one that ended does not
		KillRules.Event noHat = new KillRules.Event();
		noHat.gear = hat.gear;
		noHat.from = 0;
		noHat.to = 100;
		List<KillRules.Event> events = Collections.singletonList(noHat);
		Map<String, String> helmed = Collections.singletonMap("head", "Helm of neitiznot");
		assertEquals("Random event: no hat allowed", r.kill(goals, "The Whisperer", Collections.emptyList(), helmed, events, 2).get(whisperer));
		assertEquals(4, whisperer.left);
		assertTrue(r.kill(goals, "The Whisperer", Collections.emptyList(), helmed, events, 100).isEmpty());
		assertEquals(3, whisperer.left);
	}

	@Test
	public void potionsStatsQuiverAndValue()
	{
		// potions by name without the dose, carried or drunk since the last kill
		KillRules.Food prayerOnly = new KillRules.Food();
		prayerOnly.mode = "only";
		prayerOnly.items = Collections.singletonList("Prayer potion(4)");
		KillRules.Carried brew = new KillRules.Carried("Saradomin brew(3)", false, true);
		KillRules.Carried prayer = new KillRules.Carried("Prayer potion(1)", false, true);
		assertNull(KillRules.broken(prayerOnly, Arrays.asList(prayer, RUNES), Collections.emptyList(), true));
		assertEquals("Saradomin brew(3)", KillRules.broken(prayerOnly, Collections.singletonList(prayer), Collections.singletonList("Saradomin brew(3)"), true));
		KillRules.Food none = new KillRules.Food();
		none.mode = "none";
		assertEquals("Saradomin brew(3)", KillRules.broken(none, Arrays.asList(SHARK, brew), Collections.emptyList(), true));
		assertNull("food is no potion", KillRules.broken(none, Collections.singletonList(SHARK), Collections.emptyList(), true));

		// a slot without stats, the quiver's slot, and what the gear may be worth
		KillRules.Rules r = new KillRules.Rules();
		r.gear = new java.util.HashMap<>();
		KillRules.Items noStats = new KillRules.Items();
		noStats.mode = "nostats";
		r.gear.put("head", noStats);
		KillRules.Items dragon = new KillRules.Items();
		dragon.mode = "only";
		dragon.items = Collections.singletonList("Dragon arrow");
		r.gear.put("quiver", dragon);
		r.maxValue = 20_000_000L;
		java.util.Set<String> statless = Collections.singleton("chef's hat");
		Map<String, java.util.Set<String>> worn = new java.util.HashMap<>();
		worn.put("head", Collections.singleton("Chef's hat"));
		worn.put("quiver", Collections.singleton("Dragon arrow"));
		assertTrue(KillRules.broken(r, worn, Collections.emptyList(), Collections.emptyList(), statless, 20_000_000).isEmpty());
		worn.put("head", new java.util.HashSet<>(Arrays.asList("Chef's hat", "Serpentine helm")));
		worn.put("quiver", Collections.singleton("Amethyst arrow"));
		assertEquals(Arrays.asList("Head: an item without stats", "Quiver: only Dragon arrow", "Gear worth at most 20,000,000 gp"),
			KillRules.broken(r, worn, Collections.emptyList(), Collections.emptyList(), statless, 20_000_001));
		worn.remove("head");
		assertTrue("an empty slot is no item without stats", KillRules.broken(r, worn, Collections.emptyList(), Collections.emptyList(), statless, 0).contains("Head: an item without stats"));

		// the most the gear was worth since the last kill counts, not what it is worth at the kill
		KillRules.Goal vork = goal("v", "Vorkath", 5, null);
		vork.maxValue = 1_000L;
		List<KillRules.Goal> goals = Collections.singletonList(vork);
		KillRules k = new KillRules();
		k.worth(5_000);
		k.worth(500);
		assertEquals("Gear worth at most 1,000 gp", k.kill(goals, "Vorkath", Collections.emptyList(), Collections.emptyMap(), 0).get(vork));
		assertTrue("the next kill starts from the worth now", k.kill(goals, "Vorkath", Collections.emptyList(), Collections.emptyMap(), 1).isEmpty());
	}
}
