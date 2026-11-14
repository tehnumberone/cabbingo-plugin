package com.cabbingo;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(CabbingoConfig.GROUP)
public interface CabbingoConfig extends Config
{
	String GROUP = "cabbingo-bingo";

	// Empty until a player pastes their team's URL, as with a webhook: nothing is sent before that,
	// and nowhere else after it.
	@ConfigItem(
		keyName = "url",
		position = 0,
		name = "Team URL",
		description = "Your team's URL, from Drop setup on your bingo's Captains' board",
		secret = true,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default String url()
	{
		return "";
	}

	// TempleOSRS is a second 3rd-party server: off until the player turns it on (RuneLite's rule for such features)
	@ConfigItem(
		keyName = "templeUpdate",
		position = 1,
		name = "Update TempleOSRS on logout",
		description = "Asks TempleOSRS to update your XP and kill counts when you log out during a bingo, for its XP and kill count tiles",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean templeUpdate()
	{
		return false;
	}

	@ConfigSection(
		name = "Sounds",
		description = "Sounds for completed tiles",
		position = 1
	)
	String sounds = "sounds";

	@ConfigItem(
		keyName = "sound",
		name = "Sound",
		description = "Plays when one of your team's tiles is completed",
		position = 1,
		section = sounds
	)
	default Sound sound()
	{
		return Sound.LEAGUES_TASK;
	}

	@ConfigItem(
		keyName = "flipSound",
		name = "Flipped tile sound",
		description = "Plays when the flipped side of one of your team's tiles is completed",
		position = 2,
		section = sounds
	)
	default Sound flipSound()
	{
		return Sound.KARAMJA_HARD_TASK;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "volume",
		name = "Volume",
		description = "Game sounds use your game's sound effect volume, unless it is muted",
		position = 3,
		section = sounds
	)
	default int volume()
	{
		return 20;
	}

	// a checkbox as a button: each click plays the sound, ticked or not
	@ConfigItem(
		keyName = "test",
		name = "Test sound",
		description = "Plays the sound",
		position = 4,
		section = sounds
	)
	default boolean test()
	{
		return false;
	}

	@ConfigItem(
		keyName = "testFlip",
		name = "Test flipped tile sound",
		description = "Plays the flipped tile sound",
		position = 5,
		section = sounds
	)
	default boolean testFlip()
	{
		return false;
	}

	@ConfigSection(
		name = "Board",
		description = "You can configure the appearance and behavior of your bingo board",
		position = 2
	)
	String board = "board";

	@ConfigItem(
		keyName = "boardButton",
		name = "Board button",
		description = "Shows the button that opens your bingo board",
		position = 6,
		section = board
	)
	default boolean boardButton()
	{
		return true;
	}

	// a left click goes to the game under the button; the board opens from its right-click menu only
	@ConfigItem(
		keyName = "boardRightClick",
		name = "Open board on right-click only",
		description = "Left clicks go through the button to the game",
		position = 7,
		section = board
	)
	default boolean boardRightClick()
	{
		return false;
	}

	// off: a picked tile's details stay until that tile is clicked again
	@ConfigItem(
		keyName = "unpickOutside",
		name = "Hide tile details on outside click",
		description = "A click on the board outside a tile hides the picked tile's details",
		position = 8,
		section = board
	)
	default boolean unpickOutside()
	{
		return true;
	}

	// on: ESC closes the board and an open game window (bank, shop, settings) together; off: ESC closes the game
	// window first, the board on the next press
	@ConfigItem(
		keyName = "escapeCloses",
		name = "Always close board on ESC",
		description = "ESC closes the bingo board even while a game window is open",
		position = 9,
		section = board
	)
	default boolean escapeCloses()
	{
		return true;
	}

	// the board's tile size: dragging the board's edges and corners sets it too; drawn smaller while the game is too
	// small for it (BoardOverlay.largestTile)
	@Range(min = 16, max = 96)
	@Units(Units.PIXELS)
	@ConfigItem(
		keyName = "boardTile",
		name = "Board tile size",
		description = "The size of a tile on your bingo board; dragging the board's edges and corners changes it too",
		position = 10,
		section = board
	)
	default int boardTile()
	{
		return 46;
	}

	@Alpha
	@ConfigItem(
		keyName = "background",
		name = "Board background",
		description = "The colour of your bingo board and its button",
		position = 11,
		section = board
	)
	default Color background()
	{
		return new Color(62, 53, 41, 245);
	}

	// off: a tile with a picture the owner uploaded shows its title; items, bosses and skills always use game sprites
	@ConfigItem(
		keyName = "downloadImages",
		name = "Download bingo board images",
		description = "Shows the pictures the bingo owner uploaded on your board's tiles. These are uploaded by the bingo owner and can contain any imagery. Off: those tiles show their name",
		position = 12,
		section = board,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default boolean downloadImages()
	{
		return false;
	}

	// the board's details column shows (the fold in its top bar, or Hide details / Show details on its right-click)
	@ConfigItem(keyName = "showDetails", name = "", description = "", hidden = true)
	default boolean showDetails()
	{
		return true;
	}

	// the start of the last bingo the player was welcomed to
	@ConfigItem(keyName = "welcomed", name = "", description = "", hidden = true)
	default String welcomed()
	{
		return "";
	}
}
