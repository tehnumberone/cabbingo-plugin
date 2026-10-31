package com.cabbingo;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(CabbingoConfig.GROUP)
public interface CabbingoConfig extends Config
{
	String GROUP = "cabbingo-bingo";

	// Empty until a player pastes their team's key: nothing is sent before that.
	@ConfigItem(
		keyName = "teamKey",
		name = "Team key",
		description = "The key your captain copies from Cabbingo's Edit progress page",
		secret = true,
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers"
	)
	default String teamKey()
	{
		return "";
	}
}
