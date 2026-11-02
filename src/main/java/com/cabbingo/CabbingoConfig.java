package com.cabbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(CabbingoConfig.GROUP)
public interface CabbingoConfig extends Config
{
	String GROUP = "cabbingo-bingo";

	// Empty until a player pastes their team's URL, as with a Dink or Loot Logger webhook: nothing is sent before that,
	// and nowhere else after it.
	@ConfigItem(
		keyName = "url",
		position = 0,
		name = "Team URL",
		description = "Your team's URL, from Drop setup on your bingo's Edit progress page",
		secret = true,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default String url()
	{
		return "";
	}

	@ConfigItem(
		keyName = "sound",
		name = "Sound",
		description = "Plays when one of your team's tiles is completed",
		position = 1
	)
	default Sound sound()
	{
		return Sound.LEAGUES_TASK;
	}

	@ConfigItem(
		keyName = "flipSound",
		name = "Flipped tile sound",
		description = "Plays when the flipped side of one of your team's tiles is completed",
		position = 2
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
		position = 3
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
		position = 4
	)
	default boolean test()
	{
		return false;
	}

	@ConfigItem(
		keyName = "testFlip",
		name = "Test flipped tile sound",
		description = "Plays the flipped tile sound",
		position = 5
	)
	default boolean testFlip()
	{
		return false;
	}

	// the start of the last bingo the player was welcomed to
	@ConfigItem(keyName = "welcomed", name = "", description = "", hidden = true)
	default String welcomed()
	{
		return "";
	}
}
