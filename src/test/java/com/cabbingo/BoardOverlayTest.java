package com.cabbingo;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BoardOverlayTest
{
	@Test
	public void readsTheServersBoard()
	{
		String json = "{\"name\":\"B\",\"board\":{\"size\":3,\"tiles\":[{\"title\":\"Zulrah\",\"points\":5,\"img\":\"item-images/12922.png\",\"have\":1,\"need\":3,\"wait\":true},"
			+ "{\"title\":\"Flip\",\"points\":10,\"img\":\"\",\"have\":0,\"need\":0,\"done\":true,\"flipped\":true}],"
			+ "\"teams\":[{\"name\":\"A\",\"points\":12.5,\"own\":true},{\"name\":\"B\",\"points\":3}]}}";
		CabbingoPlugin.Wants w = new Gson().fromJson(json, CabbingoPlugin.Wants.class);
		assertEquals(3, w.board.size);
		BoardOverlay.Tile t = w.board.tiles.get(0);
		assertEquals(3, t.need, 0);
		assertTrue(t.wait && !t.done);
		assertTrue(w.board.tiles.get(1).done && w.board.tiles.get(1).flipped);
		assertTrue(w.board.teams.get(0).own);
		assertEquals("12.5", BoardOverlay.number(w.board.teams.get(0).points));
		assertEquals("3", BoardOverlay.number(w.board.teams.get(1).points));
	}

	@Test
	public void picturesOnlyFromOurHosts()
	{
		assertEquals("https://www.cabbingo.com/item-images/12922.png", BoardOverlay.link("item-images/12922.png"));
		assertEquals("https://cabbingo.bingoboard.workers.dev/img/5", BoardOverlay.link("https://cabbingo.bingoboard.workers.dev/img/5"));
		assertNull(BoardOverlay.link("https://evil.example/x.png"));
		assertNull(BoardOverlay.link(""));
		assertNull(BoardOverlay.link(null));
	}
}
