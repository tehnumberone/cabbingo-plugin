package com.cabbingo;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;

// The random event infobox: shown while the player breaks a rule of a running random event (Gear.watch)
class RuleBox extends InfoBox
{
	final KillRules.Event event;
	volatile String tip;

	RuleBox(BufferedImage image, Plugin plugin, KillRules.Event event, String tip)
	{
		super(image, plugin);
		this.event = event;
		this.tip = tip;
	}

	@Override
	public String getText()
	{
		return "!";
	}

	@Override
	public Color getTextColor()
	{
		return Color.RED;
	}

	@Override
	public String getTooltip()
	{
		return tip;
	}
}
