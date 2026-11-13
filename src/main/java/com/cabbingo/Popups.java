package com.cabbingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.util.Text;

/**
 * The game's own popup (the collection log's "New item") for each tile side, row, column or diagonal the team finished,
 * and the welcome at the first login of a bingo, one at a time, each with its sound and a chat message.
 */
@Slf4j
@Singleton
class Popups
{
	// The game's own popup (the collection log's "New item"): interface 660 over the game view, started by script 3343.
	private static final int POPUP = 660;
	private static final int POPUP_SCRIPT = 3343;
	private static final String SEEN = "seen";

	static class Popup
	{
		final String title;
		final String text;
		final String chat; // the chatbox message that goes with it; null for none
		final boolean flip; // a flipped side: the flipped tile sound
		final String id; // the finished side; null for the welcome

		Popup(String title, String text, String chat, boolean flip, String id)
		{
			this.title = title;
			this.text = text;
			this.chat = chat;
			this.flip = flip;
			this.id = id;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final CabbingoConfig config;
	private final ConfigManager configManager;
	private final AudioPlayer audioPlayer;
	private final ScheduledExecutorService executor;

	// The finished tile sides already known (by id); null until the first answer, so a login shows no old ones.
	private Set<String> finishedSeen;
	// when each side last got its popup: a post's X-Finished must not bring back one the live line's fetch already
	// showed while the post was on its way (the same tile popped up twice, 2026-11-10)
	private final Map<String, Long> poppedAt = new HashMap<>();
	// Titles waiting for the game's popup, one at a time.
	private final Queue<Popup> popups = new ConcurrentLinkedQueue<>();
	private long soundUntil; // client thread only
	// our popup while it is open, and since when the next one waits (0: none waits)
	private WidgetNode popupNode;
	private long popupWaiting;

	@Inject
	Popups(Client client, ClientThread clientThread, CabbingoConfig config, ConfigManager configManager, AudioPlayer audioPlayer, ScheduledExecutorService executor)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.configManager = configManager;
		this.audioPlayer = audioPlayer;
		this.executor = executor;
	}

	/** The sides already popped up, kept over restarts: one finished while the player was away pops up at the next login. */
	synchronized void start()
	{
		String seen = configManager.getConfiguration(CabbingoConfig.GROUP, SEEN);
		finishedSeen = seen == null ? null : seen.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(seen.split(",")));
	}

	/** Popups not shown yet come again at the next start. */
	void stop()
	{
		unsee(popups.stream().map(p -> p.id).filter(Objects::nonNull).toArray(String[]::new));
		synchronized (this)
		{
			finishedSeen = null;
		}
		popups.clear();
	}

	/** Another team (a new Team URL): its finished tiles are not news. */
	void forget()
	{
		synchronized (this)
		{
			finishedSeen = null;
		}
		configManager.unsetConfiguration(CabbingoConfig.GROUP, SEEN);
	}

	// The first login after a bingo starts: a welcome. Only when logged in, or the welcome could go to a login nobody sees.
	void welcome(Wants w)
	{
		long now = System.currentTimeMillis();
		String start = String.valueOf(w.start);
		if (client.getGameState() == GameState.LOGGED_IN && now >= w.start && now <= w.end && !start.equals(config.welcomed()))
		{
			String name = w.name == null || w.name.isEmpty() ? "Your bingo" : w.name;
			String mode = w.mode == null || w.mode.isEmpty() ? "Bingo" : w.mode;
			popups.add(new Popup(Text.escapeJagex(name), "<col=ffffff>" + Text.escapeJagex(mode) + "</col> has started.<br>Good luck and enjoy!", null, false, null));
			configManager.setConfiguration(CabbingoConfig.GROUP, "welcomed", start);
		}
	}

	// A tile side, row, column or diagonal (id row:i, col:i, diag:i) the team finished since the last answer gets the game's popup, as
	// League tasks do, and a chat message with the tile's description. While a captain still has to approve the drops
	// that finish it, the points read "on approval". Approved later, it shows no second popup.
	synchronized void finished(List<Finished> finished)
	{
		Set<String> ids = new HashSet<>();
		for (Finished f : finished)
		{
			ids.add(f.id);
			if (finishedSeen != null && !finishedSeen.contains(f.id))
			{
				String kind = f.id.startsWith("row:") ? "Row" : f.id.startsWith("col:") ? "Column" : f.id.startsWith("diag:") ? "Diagonal" : "Tile";
				String name = Text.escapeJagex(f.title);
				String text = kind + " completed: <col=ffffff>" + name + "</col><br>"
					+ (f.wait ? "Points on approval: " : "Points earned: ") + "<col=ffffff>" + f.points + "</col>";
				String chat = "<col=ef1020>" + name + "</col> completed (" + f.points + (f.points == 1 ? " point" : " points")
					+ (f.wait ? " once approved" : "") + ")"
					+ (f.description == null || f.description.isBlank() ? "" : ": " + Text.escapeJagex(f.description.replaceAll("\\s+", " ").trim()));
				popups.add(new Popup(kind + " Complete!", text, chat, f.id.endsWith(":flip"), f.id));
				poppedAt.put(f.id, System.currentTimeMillis());
			}
		}
		// the welcome first, then tiles, then rows, then columns, queued ones too (user, 2026-11-03)
		List<Popup> queued = new ArrayList<>(popups);
		queued.sort(Comparator.comparingInt(p -> p.id == null ? 0 : p.id.startsWith("row:") ? 2 : p.id.startsWith("col:") ? 3 : p.id.startsWith("diag:") ? 4 : 1));
		popups.clear();
		popups.addAll(queued);
		log.debug("Cabbingo: finished {}, seen before {}", ids, finishedSeen);
		if (!ids.equals(finishedSeen))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", ids));
		}
		finishedSeen = ids;
	}

	private void unsee(String[] ids)
	{
		unsee(ids, Long.MAX_VALUE);
	}

	// only the sides that got no popup since before: the time a post was sent
	synchronized void unsee(String[] ids, long before)
	{
		List<String> old = Arrays.stream(ids).filter(id -> poppedAt.getOrDefault(id, 0L) < before).collect(Collectors.toList());
		if (finishedSeen != null && finishedSeen.removeAll(old))
		{
			configManager.setConfiguration(CabbingoConfig.GROUP, SEEN, String.join(",", finishedSeen));
		}
	}

	// The next popup, once the game shows none: the same one the collection log uses. Once a tick.
	void tick()
	{
		if (popups.isEmpty())
		{
			popupWaiting = 0;
			return;
		}
		// one after another, each with its sound; not over the game's own popup (collection log, League task) nor behind
		// the "Click here to play" screen, nor while a bank or other window is open (it would not show, only its sound:
		// tried 2026-11-02). Only what shows counts: an interface can stay loaded once it is gone (a
		// Blast Furnace popup waited for minutes, 2026-11-02).
		long now = System.currentTimeMillis();
		String busy = popupNode != null ? "ours" : showing(POPUP, 1) ? "game popup" : showing(InterfaceID.WELCOME_SCREEN, 0) ? "welcome screen"
			: modalOpen(client) ? "open window" : now < soundUntil ? "sound" : null;
		if (busy != null)
		{
			if (popupWaiting == 0)
			{
				popupWaiting = now;
			}
			else if (now - popupWaiting > 10_000 && (now - popupWaiting) % 10_000 < 600)
			{
				log.debug("Cabbingo: popup waits for {}", busy);
			}
			return;
		}
		popupWaiting = 0;
		Popup next = popups.poll();
		log.debug("Cabbingo: popup {}", next.text);
		int parent = !client.isResized() ? WidgetUtil.packComponentId(548, 42)
			: client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1 ? WidgetUtil.packComponentId(164, 13)
			: WidgetUtil.packComponentId(161, 13);
		WidgetNode node = client.openInterface(parent, POPUP, WidgetModalMode.MODAL_CLICKTHROUGH);
		Widget popup = client.getWidget(POPUP, 1);
		client.runScript(POPUP_SCRIPT, next.title, next.text, -1);
		if (next.chat != null)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", next.chat, null);
		}
		playSound(next.flip ? config.flipSound() : config.sound());
		// closed again once it has played (or after 20 s at most), or the next one could not open
		popupNode = node;
		clientThread.invokeLater(() ->
		{
			if (popup != null && popup.getWidth() > 0 && System.currentTimeMillis() - now < 20_000)
			{
				return false;
			}
			try
			{
				client.closeInterface(node, true);
			}
			catch (IllegalArgumentException ex)
			{
				// the game closed it first ("WidgetNode is no longer valid"): nothing left to close
			}
			popupNode = null;
			return true;
		});
	}

	// a window in the middle of the screen (bank, shop, Blast Furnace bars): the game's own windows the player closes.
	// Client thread only.
	static boolean modalOpen(Client client)
	{
		for (WidgetNode node : client.getComponentTable())
		{
			if (node.getModalMode() == WidgetModalMode.MODAL_NOCLICKTHROUGH)
			{
				return true;
			}
		}
		return false;
	}

	private boolean showing(int group, int child)
	{
		Widget w = client.getWidget(group, child);
		return w != null && !w.isHidden() && w.getWidth() > 0;
	}

	/** On the client thread. */
	void playSound(Sound sound)
	{
		int volume = config.volume();
		long now = System.currentTimeMillis();
		if (volume <= 0 || now < soundUntil)
		{
			return; // the last sound still plays: no pile-up from double clicks
		}
		soundUntil = now + sound.ms;
		if (sound.id > 0)
		{
			client.playSoundEffect(sound.id, volume * 127 / 100);
		}
		else if (sound.file != null)
		{
			executor.execute(() ->
			{
				try
				{
					audioPlayer.play(CabbingoPlugin.class, sound.file, 20f * (float) Math.log10(volume / 100.0));
				}
				catch (Exception ex)
				{
					log.debug("Cabbingo sound {} did not play", sound.file, ex);
				}
			});
		}
	}
}
