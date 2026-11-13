package com.cabbingo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The server's GET /plugin/key answer. Names and sources in lower case. */
class Wants
{
	long start;
	long end;
	String name; // the bingo's title
	String mode; // the game mode; none (an older server) is Bingo
	Set<String> items = new HashSet<>();
	// the items of open Skilling sides, in groups with how many more finish a side (SkillCounter.Goal). Only skill
	// counts go to them, never a kill (2026-11-03).
	List<SkillCounter.Goal> skill = new ArrayList<>();
	Set<String> uniques = new HashSet<>(); // every boss's uniques: a kill with one never joins a batch
	Map<String, Double> sources;
	double big;
	List<Finished> finished = new ArrayList<>(); // the tile sides the team finished
	List<KillRules.Goal> kc = new ArrayList<>(); // kill count sides still open, with their food rules (2026-11-06)
	List<KillRules.Event> events = new ArrayList<>(); // the team's random events with gear or inventory rules (2026-11-07)
	BoardOverlay.Board board; // the in-game board; null for snakes and ladders or an older server (2026-11-12)
	String rev; // the server's fingerprint of all this; every post's answer carries the current one (X-Rev)
}

/** A tile side, row, column or diagonal the team finished (Wants.finished). */
class Finished
{
	String id;
	String title;
	int points; // what it gives: a flipped side the tile's double, a row or column its bonus
	String description;
	boolean wait; // only drops that wait for a captain's approval finish it
}
