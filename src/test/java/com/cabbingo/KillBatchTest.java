package com.cabbingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class KillBatchTest
{
	private static List<KillBatch.Item> drop(int id, String name, int quantity)
	{
		return Collections.singletonList(new KillBatch.Item(id, name, 100, quantity));
	}

	@Test
	public void sameSourceJoinsAnotherSourceSends()
	{
		KillBatch b = new KillBatch();
		assertNull(b.add("Bob", "Zulrah", "NPC", drop(1, "Zulrah's scales", 100), 0));
		assertNull(b.add("Bob", "Zulrah", "NPC", Arrays.asList(new KillBatch.Item(1, "Zulrah's scales", 100, 50), new KillBatch.Item(2, "Coal", 100, 5)), 1000));
		KillBatch.Batch closed = b.add("Bob", "Vorkath", "NPC", drop(3, "Dragon bones", 2), 2000);
		assertEquals(2, closed.kills);
		assertEquals("Zulrah", closed.source);
		assertEquals(150, closed.items.get(1).quantity);
		assertEquals(5, closed.items.get(2).quantity);
		// another category of the same name is another batch too
		assertEquals("Vorkath", b.add("Bob", "Vorkath", "EVENT", drop(3, "Dragon bones", 2), 3000).source);
		assertEquals(1, b.take().kills);
		assertNull(b.take());
	}

	@Test
	public void dueAMinuteAfterTheFirstKill()
	{
		KillBatch b = new KillBatch();
		assertFalse(b.due(0));
		b.add("Bob", "Zulrah", "NPC", drop(1, "Zulrah's scales", 100), 1000);
		b.add("Bob", "Zulrah", "NPC", drop(1, "Zulrah's scales", 100), 50_000);
		assertFalse(b.due(60_999));
		assertTrue(b.due(61_000));
		assertEquals(2, b.take().kills);
		assertFalse(b.due(200_000));
	}

	@Test
	public void aFullBatchIsSent()
	{
		KillBatch b = new KillBatch();
		for (int i = 0; i < KillBatch.MAX; i++)
		{
			assertNull(b.add("Bob", "Man", "NPC", drop(1, "Coins", 3), i));
		}
		assertEquals(KillBatch.MAX, b.add("Bob", "Man", "NPC", drop(1, "Coins", 3), 100).kills);
		assertEquals(1, b.take().kills);
	}
}
