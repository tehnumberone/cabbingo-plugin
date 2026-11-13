package com.cabbingo;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import net.runelite.client.hiscore.HiscoreSkill;
import org.junit.Test;

public class BoardOverlayTest
{
	@Test
	public void readsTheServersBoard()
	{
		String json = "{\"name\":\"B\",\"board\":{\"size\":3,\"rowBonus\":5,\"columnBonus\":7,\"tiles\":[{\"title\":\"Zulrah\",\"points\":5,\"img\":\"item-images/12922.png\",\"have\":1,\"need\":3,\"wait\":true},"
			+ "{\"title\":\"Flip\",\"points\":10,\"img\":\"\",\"have\":0,\"need\":0,\"done\":true,\"flipped\":true}],"
			+ "\"bonus\":[{\"title\":\"Day 2\",\"points\":5,\"img\":\"\",\"have\":1,\"need\":2,\"from\":1767312000000,\"to\":1767398400000}],"
			+ "\"events\":[{\"title\":\"\",\"text\":\"No hats\",\"from\":1767312000000,\"to\":1767398400000}],"
			+ "\"teams\":[{\"name\":\"A\",\"points\":12.5,\"own\":true},{\"name\":\"B\",\"points\":3}]}}";
		Wants w = new Gson().fromJson(json, Wants.class);
		assertEquals(3, w.board.size);
		assertEquals(7, w.board.columnBonus, 0);
		assertEquals("1st", BoardOverlay.ordinal(1));
		assertEquals("12th", BoardOverlay.ordinal(12));
		assertEquals("23rd", BoardOverlay.ordinal(23));
		BoardOverlay.Tile t = w.board.tiles.get(0);
		assertEquals(3, t.need, 0);
		assertTrue(t.wait && !t.done);
		assertTrue(w.board.tiles.get(1).done && w.board.tiles.get(1).flipped);
		assertTrue(w.board.teams.get(0).own);
		assertEquals("12.5", BoardOverlay.number(w.board.teams.get(0).points));
		assertEquals("3", BoardOverlay.number(w.board.teams.get(1).points));
		assertEquals(1767398400000L, w.board.bonus.get(0).to);
		assertEquals(2, w.board.bonus.get(0).need, 0);
		assertEquals("No hats", w.board.events.get(0).text);
		assertEquals("5h 12m", BoardOverlay.left((5 * 60 + 12) * 60_000L + 59_000));
		assertEquals("3m", BoardOverlay.left(3 * 60_000L));
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

	// every TempleOSRS skill and boss the server can send as "hiscore:<name>" (TEMPLE_SKILLS in models/temple.ts)
	@Test
	public void everyTemplePictureIsAGameSprite()
	{
		String[] names = {"Overall", "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking", "Woodcutting", "Fletching",
			"Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving", "Slayer", "Farming", "Runecraft", "Hunter",
			"Construction", "Sailing", "Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio", "Barrows Chests", "Brutus", "Bryophyta",
			"Callisto", "Calvar'ion", "Cerberus", "Chambers of Xeric", "Chambers of Xeric Challenge Mode", "Chaos Elemental", "Chaos Fanatic",
			"Commander Zilyana", "Corporeal Beast", "Crazy Archaeologist", "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme",
			"Deranged Archaeologist", "Doom of Mokhaiotl", "Duke Sucellus", "General Graardor", "Giant Mole", "Grotesque Guardians", "Hespori",
			"Hueycoatl", "Kalphite Queen", "King Black Dragon", "Kraken", "Kree'arra", "K'ril Tsutsaroth", "Lunar Chests", "Mad Angel", "Maggot King",
			"Mimic", "Nex", "Obor", "Phantom Muspah", "Phosani's Nightmare", "Sarachnis", "Scorpia", "Scurrius", "Shellbane Gryphon", "Skotizo",
			"Sol Heredit", "Spindel", "Tempoross", "The Corrupted Gauntlet", "The Fractured Archive", "The Gauntlet", "The Leviathan", "The Nightmare",
			"The Royal Titans", "The Whisperer", "Theatre of Blood", "Theatre of Blood Challenge Mode", "Thermonuclear Smoke Devil", "Tombs of Amascut",
			"Tombs of Amascut Expert", "TzKal-Zuk", "TzTok-Jad", "Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Wintertodt", "Yama", "Zalcano", "Zulrah"};
		for (String n : names)
		{
			assertTrue(n, BoardOverlay.hiscore("hiscore:" + n) != null);
		}
		assertEquals(HiscoreSkill.THEATRE_OF_BLOOD_HARD_MODE, BoardOverlay.hiscore("hiscore:Theatre of Blood Challenge Mode"));
		assertEquals(HiscoreSkill.NIGHTMARE, BoardOverlay.hiscore("hiscore:The Nightmare"));
		assertNull(BoardOverlay.hiscore("hiscore:Nobody"));
		assertNull(BoardOverlay.hiscore("item-images/4151.png"));
	}

	@Test
	public void webpGivesNoPicture() throws Exception
	{
		// a WebP tile picture as the app uploads it (the "Experienced?" XP tile): no reader without the WebP library, so the
		// tile shows its title
		java.awt.image.BufferedImage img = BoardOverlay.read(java.util.Base64.getDecoder().decode("UklGRgYBAABXRUJQVlA4TPoAAAAvGIAFEM/ANgCAJGzdObHxCg/yIujUrZ8wDgDAyNl2tXk7Wm/GkWQr+e5ydEuIMEnP4QZV8x8AwIgzUArCOvn9RUEpYynr6ODZHrSgjWhX+DTOVrYVdBzhUl1W07/598v+vp7rw9fi2JRUF3ZoBodtGznSzuWcc/+dzsz+fgd2RP8nIHyRoumH3JOB5IKIzJF2IOdGci+nIbNu4j6rBfCXOtVURwkJnSCnrBsgDcnxB9kArE4VterDquJ2AOvplDFwapJjCcmhP4b8JzwAXompSHYl5AHw3pIHHgA2T159JyjkIumcJP9TAuitJ2ZPAivEZSEnUvNjnvoD"));
		assertNull(img);
	}

	@Test
	public void cabbageBox()
	{
		// the button centres the item's own pixels, not its 36 x 32 picture
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(36, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		assertNull(BoardButton.opaque(img));
		img.setRGB(4, 10, 0xff00ff00);
		img.setRGB(20, 25, 0xff00ff00);
		assertEquals(new java.awt.Rectangle(4, 10, 17, 16), BoardButton.opaque(img));
	}
}
