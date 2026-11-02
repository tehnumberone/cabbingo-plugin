package com.cabbingo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// A player's kills of one source, sent as one post (extra.kills) instead of one post per kill (2026-11-02): the loot
// worker writes its tally once per batch, which keeps it on the Workers Free plan. A kill a tile or the team's channel
// cares about never joins one; the plugin sends that kill alone, with its screenshot.
class KillBatch
{
	// Short on purpose: a RuneLite crash loses at most this much
	static final long WAIT = 60_000;
	// The server counts at most this many kills in one post
	static final int MAX = 100;

	static class Item
	{
		final int id;
		final String name;
		final long price; // each
		final int quantity;

		Item(int id, String name, long price, int quantity)
		{
			this.id = id;
			this.name = name;
			this.price = price;
			this.quantity = quantity;
		}
	}

	static class Batch
	{
		final String rsn;
		final String source;
		final String category;
		final long first;
		int kills;
		// by item id, the stacks of all kills added up
		final Map<Integer, Item> items = new LinkedHashMap<>();

		Batch(String rsn, String source, String category, long first)
		{
			this.rsn = rsn;
			this.source = source;
			this.category = category;
			this.first = first;
		}
	}

	private Batch open;

	// The kill joins the open batch of its player, source and category. Returns the batch it closed (another source,
	// or a full one) to send now, else null.
	synchronized Batch add(String rsn, String source, String category, List<Item> items, long now)
	{
		Batch closed = null;
		if (open != null && (open.kills >= MAX || !open.rsn.equals(rsn) || !open.source.equals(source) || !open.category.equals(category)))
		{
			closed = open;
			open = null;
		}
		if (open == null)
		{
			open = new Batch(rsn, source, category, now);
		}
		open.kills++;
		for (Item i : items)
		{
			open.items.merge(i.id, i, (a, b) -> new Item(a.id, a.name, a.price, a.quantity + b.quantity));
		}
		return closed;
	}

	// A minute since the open batch's first kill
	synchronized boolean due(long now)
	{
		return open != null && now - open.first >= WAIT;
	}

	// The open batch, to send now; null when there is none
	synchronized Batch take()
	{
		Batch b = open;
		open = null;
		return b;
	}
}
