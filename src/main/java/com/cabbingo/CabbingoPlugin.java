package com.cabbingo;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ColorUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Sends every kill, PvP kill and pet of a bingo player to their Cabbingo team. The server counts it for the
 * team's gp lists and its tiles, and passes it on to the team's Discord channel. A screenshot goes along only
 * when it matters: a tile's item, a pet, a big drop, or a drop a tile counts the value of.
 *
 * The parts: Team (the server's copy of the team, the live line), Sender (posts), Popups (finished tiles), Kills,
 * Skilling, Gear (rules and the random event infobox), and the board window (BoardOverlay, BoardButton).
 */
@Slf4j
@PluginDescriptor(
	name = "Cabbingo",
	description = "Sends your bingo drops, kills and pets to your Cabbingo team",
	tags = {"bingo", "clan", "loot", "discord"}
)
public class CabbingoPlugin extends Plugin
{
	private static final Set<String> SOUND_KEYS = Set.of("sound", "flipSound", "volume", "test", "testFlip");
	// worlds whose XP is not on the normal hiscores: no TempleOSRS update from them
	private static final Set<WorldType> OFF_HISCORES = EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN, WorldType.BETA_WORLD,
		WorldType.TOURNAMENT_WORLD, WorldType.NOSAVE_MODE, WorldType.FRESH_START_WORLD, WorldType.QUEST_SPEEDRUNNING);

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ConfigManager configManager;
	@Inject private EventBus eventBus;
	@Inject private OkHttpClient http;
	@Inject private OverlayManager overlayManager;
	@Inject private MouseManager mouseManager;
	@Inject private KeyManager keyManager;
	@Inject private CabbingoConfig config;
	@Inject private Team team;
	@Inject private Sender sender;
	@Inject private Popups popups;
	@Inject private Kills kills;
	@Inject private Skilling skilling;
	@Inject private Gear gear;
	// the board window and its button (2026-11-12)
	@Inject private BoardOverlay boardOverlay;
	@Inject private BoardButton boardButton;

	// For the TempleOSRS update on logout: who played and their total XP when the session started (-1: none yet).
	private String templeName;
	private long templeXp = -1;

	@Override
	protected void startUp()
	{
		popups.start();
		team.onWants(this::apply);
		eventBus.register(kills);
		eventBus.register(skilling);
		overlayManager.add(boardOverlay);
		overlayManager.add(boardButton);
		mouseManager.registerMouseListener(boardOverlay);
		mouseManager.registerMouseWheelListener(boardOverlay);
		mouseManager.registerMouseListener(boardButton);
		keyManager.registerKeyListener(boardOverlay);
		team.fetch();
	}

	@Override
	protected void shutDown()
	{
		kills.flush();
		team.closePush();
		team.onWants(w -> { });
		gear.clear();
		eventBus.unregister(kills);
		eventBus.unregister(skilling);
		overlayManager.remove(boardOverlay);
		overlayManager.remove(boardButton);
		mouseManager.unregisterMouseListener(boardOverlay);
		mouseManager.unregisterMouseWheelListener(boardOverlay);
		mouseManager.unregisterMouseListener(boardButton);
		keyManager.unregisterKeyListener(boardOverlay);
		boardOverlay.show(null, null, 0, null);
		team.clear();
		skilling.goals(null);
		skilling.reset(); // (turned on again while logged in: nothing gained meanwhile counts)
		kills.reset();
		popups.stop();
	}

	// A server answer (null: no team): the board window, the Skilling goals, the welcome and finished tiles
	private void apply(Wants w)
	{
		kills.goals(w);
		showBoard(w);
		skilling.goals(w);
		if (w != null)
		{
			popups.welcome(w);
			popups.finished(w.finished);
		}
	}

	// The board window and its button: while logged in, from the bingo's start until a day after its end
	private void showBoard(Wants w)
	{
		long now = System.currentTimeMillis();
		boolean on = w != null && client.getGameState() == GameState.LOGGED_IN && now >= w.start && now <= w.end + 24 * Team.HOUR;
		boardOverlay.show(on ? w.board : null, w == null ? null : w.name, w == null ? 0 : w.end, w == null ? null : w.finished);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!CabbingoConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("url".equals(e.getKey()))
		{
			// another team: its finished tiles are not news
			popups.forget();
			team.closePush();
			team.fetch();
		}
		else if ("boardTile".equals(e.getKey()))
		{
			boardOverlay.tileSize(config.boardTile());
		}
		else if (SOUND_KEYS.contains(e.getKey()))
		{
			// a try of the picked sound
			boolean flip = "flipSound".equals(e.getKey()) || "testFlip".equals(e.getKey());
			clientThread.invoke(() -> popups.playSound(flip ? config.flipSound() : config.sound()));
		}
	}

	// The board window's and button's menus. Over the window: Hide details / Show details. The game's own entries stay
	// as they are (Jagex's rules forbid removing attack or player options; the window takes its clicks itself,
	// BoardOverlay, 2026-11-12). Over the button: Open / Close Bingo board, with or without shift (RuneLite shows an
	// overlay's own entries only with shift), unless an interface covers it.
	@Subscribe
	public void onPostMenuSort(PostMenuSort e)
	{
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || client.isMenuOpen())
		{
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (boardOverlay.covers(mouse.getX(), mouse.getY()))
		{
			menuEntry(boardOverlay.details() ? "Hide details" : "Show details", boardOverlay::toggleDetails);
			return;
		}
		boardButton.covered = Arrays.stream(entries).anyMatch(m -> m.getWidget() != null);
		if (boardButton.hovered(mouse.getX(), mouse.getY()))
		{
			menuEntry(boardOverlay.isOpen() ? "Close" : "Open", boardOverlay::toggle);
		}
	}

	// one of ours, below the game's left-click option
	private void menuEntry(String option, Runnable action)
	{
		client.getMenu().createMenuEntry(-1)
			.setOption(option)
			.setTarget(ColorUtil.wrapWithColorTag("Bingo board", JagexColors.MENU_TARGET))
			.setType(MenuAction.RUNELITE)
			.setDeprioritized(true)
			.onClick(m -> action.run());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			team.fetch();
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			team.closePush();
			if (e.getGameState() == GameState.LOGIN_SCREEN)
			{
				boardOverlay.show(null, null, 0, null);
			}
			kills.logout();
			skilling.send();
			skilling.reset();
			updateTemple();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		popups.tick();
		if (team.inBingo())
		{
			team.openPush();
		}
		kills.tick();
		String player = team.playing();
		gear.watch(player);
		if (player != null)
		{
			skilling.tick(player);
		}
		if (templeName == null && client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			boolean normal = client.getWorldType().stream().noneMatch(OFF_HISCORES::contains);
			templeName = normal ? Team.name(client.getLocalPlayer().getName()) : null;
			templeXp = client.getOverallExperience();
		}
		kills.petTick();
	}

	// Skilling counts every five minutes (Skilling.send); RuneLite only schedules the plugin's own methods
	@Schedule(period = 5, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void sendSkills()
	{
		skilling.send();
	}

	// The poll for the team's copy (Team.poll)
	@Schedule(period = 1, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshWants()
	{
		team.poll();
	}

	// TempleOSRS: the bingo's XP and kill count tiles count from its copy of the hiscores, which it only refreshes when
	// asked. Asked here on logout (and world hop) during the bingo, as RuneLite's own XP Updater does, unless that one
	// already asks TempleOSRS. Not from a world whose XP is not on the normal hiscores.
	private void updateTemple()
	{
		String name = templeName;
		long gained = client.getOverallExperience() - templeXp;
		templeName = null;
		if (name == null || templeXp < 0 || !team.inBingo())
		{
			return;
		}
		// the server checks the skilling counts up to here against TempleOSRS once it has this update (cheat mark)
		sender.send(Sender.payload("LOGOUT", name, "", new JsonObject()), false);
		if (gained <= 0)
		{
			return;
		}
		// (XP Updater's TempleOSRS box is off by default, and it sends only past 10,000 XP gained)
		if ("true".equals(configManager.getConfiguration("runelite", "xpupdaterplugin"))
			&& "true".equals(configManager.getConfiguration("xpupdater", "templeosrs")) && gained > 10_000)
		{
			return;
		}
		HttpUrl url = new HttpUrl.Builder().scheme("https").host("templeosrs.com").addPathSegment("php")
			.addPathSegment("add_datapoint.php").addQueryParameter("player", name).build();
		http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: TempleOSRS update failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	@Provides
	CabbingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CabbingoConfig.class);
	}
}
