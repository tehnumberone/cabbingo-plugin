package com.cabbingo;

/**
 * The sound a finished tile plays: a jingle file in resources, or a game sound effect by id (the wiki's List of sound IDs).
 * The jingle files are game audio, turned into 22 kHz mono WAV: the Leagues and combat achievement ones from
 * hootisman/muted_level_jingles (BSD-2-Clause), the Karamja ones from the OSRS Wiki's Jingles page.
 */
public enum Sound
{
	OFF("Off", null, 0, 0),
	LEAGUES_TASK("Leagues task", "leagues-task.wav", 0, 4400),
	LEAGUES_RELIC("Leagues relic", "leagues-relic.wav", 0, 6500),
	COMBAT_ACHIEVEMENT("Combat achievement", "combat-achievement.wav", 0, 4400),
	KARAMJA_HARD_DIARY("Karamja hard diary", "karamja-hard-diary.wav", 0, 11400),
	KARAMJA_HARD_TASK("Karamja hard task", "karamja-hard-task.wav", 0, 6600),
	LEAGUE_TROPHY("League trophy", null, 4218, 2000),
	RELIC_UNLOCK("Relic unlock", null, 4217, 2000),
	TRAILBLAZER_UNLOCK("Trailblazer unlock", null, 2331, 2000),
	FANFARE("Fanfare", null, 2930, 2000),
	FIREWORKS("Level up fireworks", null, 2396, 2000),
	PUZZLE_COMPLETE("Puzzle complete", null, 3283, 2000),
	GE_DING("Grand Exchange ding", null, 3925, 2000),
	COINS("Coins", null, 2119, 2000);

	private final String label;
	final String file;
	final int id;
	// how long it plays; nothing else plays meanwhile. ponytail: game sound effects are guessed at 2 s
	final int ms;

	Sound(String label, String file, int id, int ms)
	{
		this.label = label;
		this.file = file;
		this.id = id;
		this.ms = ms;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
