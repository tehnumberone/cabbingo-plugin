package com.cabbingo;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The player's team on the server: the Team URL, what the team wants (GET /plugin/key) and the live line that says
 * when to ask again. Each answer goes to the plugin (onWants), on the client thread: the popups, the board window
 * and the goals are all changed there, never by two answers at once (a welcome showed twice, a popup could too).
 */
@Slf4j
@Singleton
class Team
{
	static final long HOUR = 60 * 60 * 1000L;

	private final Client client;
	private final ClientThread clientThread;
	private final CabbingoConfig config;
	private final OkHttpClient http;
	private final Gson gson;

	// What the team wants, from the server. Null until it answered (no key, a wrong key, offline).
	private volatile Wants wants;
	private volatile Consumer<Wants> onWants = w -> { };
	// The team's live line (the server's TeamPush): "finished" when the team finishes a side, so a teammate's popup comes
	// at once (user, 2026-11-02). Open while logged in during the bingo; the minute poll stays for when it is down.
	private OkHttpClient pushHttp;
	private volatile WebSocket push;
	private long pushAgain; // the next try after it dropped or could not open
	private long pushWait = 60_000; // doubles per failed try, up to half an hour
	// The poll, for what the live line (openPush) does not bring: every minute while it is down, so a finish still pops
	// up within a minute; every 5 minutes while it is up (it brings finishes and board changes); every 30 minutes outside
	// the bingo (a date can still move); never for a key the server no longer knows, until a login or another URL asks.
	// It was every minute always, ~1.5k requests a day per logged-in player, also long after the bingo (2026-11-12).
	private volatile long lastFetch;
	private volatile boolean gone;

	@Inject
	Team(Client client, ClientThread clientThread, CabbingoConfig config, OkHttpClient http, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.http = http;
		this.gson = gson;
	}

	Wants wants()
	{
		return wants;
	}

	/** Where each answer goes; null for none (the plugin is off), or an older one to forget. */
	void onWants(Consumer<Wants> onWants)
	{
		this.onWants = onWants;
	}

	void clear()
	{
		wants = null;
	}

	boolean inBingo()
	{
		Wants w = wants;
		long now = System.currentTimeMillis();
		return w != null && now >= w.start - HOUR && now <= w.end + HOUR;
	}

	// The local player's name from an hour before the bingo to an hour after it, else null. The server checks the team and
	// the exact dates on every post, as they are at that moment, and ignores a kill outside them: a player added to the
	// team never loses a kill to this plugin's older copy, and the hour covers a date change until the next refresh.
	String playing()
	{
		if (!inBingo() || client.getLocalPlayer() == null)
		{
			return null;
		}
		return name(client.getLocalPlayer().getName());
	}

	// (the game puts a no-break space in some names; the server and the team list use a plain one)
	static String name(String rsn)
	{
		return rsn != null ? rsn.replace(' ', ' ') : null;
	}

	// The URL the player pasted, like a webhook: this plugin sends nowhere else. Null when it is
	// empty or not a URL.
	HttpUrl url()
	{
		return HttpUrl.parse(config.url().trim());
	}

	// Tiles of the day and team changes reach the plugin through the live line, else this poll (once a minute).
	void poll()
	{
		if (client.getGameState() != GameState.LOGGED_IN || gone)
		{
			return;
		}
		Wants w = wants;
		long now = System.currentTimeMillis();
		boolean during = w == null || (now >= w.start - HOUR && now <= w.end + 24 * HOUR);
		long every = !during ? 30 * 60_000 : push != null ? 5 * 60_000 : 60_000;
		// (a few seconds early: the schedule and the last answer drift a little)
		if (now - lastFetch >= every - 5_000)
		{
			fetch();
		}
	}

	void fetch()
	{
		HttpUrl url = url();
		if (url == null)
		{
			wants = null;
			clientThread.invoke(() -> onWants.accept(null));
			return;
		}
		// never into RuneLite's disk cache: two clients on one computer asking at the same moment (a live line's
		// "finished") failed to rename the same cache file, and that answer was lost (2026-11-03)
		Request request = new Request.Builder().url(url).cacheControl(new CacheControl.Builder().noStore().build()).build();
		lastFetch = System.currentTimeMillis();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: no team settings", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (response)
				{
					Wants w = response.isSuccessful() ? gson.fromJson(response.body().charStream(), Wants.class) : null;
					gone = response.code() == 404; // the team's key is gone: no more polls
					clientThread.invoke(() ->
					{
						wants = w;
						onWants.accept(w);
					});
				}
			}
		});
	}

	synchronized void openPush()
	{
		long now = System.currentTimeMillis();
		HttpUrl url = url();
		if (push != null || now < pushAgain || url == null)
		{
			return;
		}
		if (pushHttp == null)
		{
			pushHttp = http.newBuilder().pingInterval(30, TimeUnit.SECONDS).build();
		}
		Request request = new Request.Builder().url(url.newBuilder().addPathSegment("push").build()).build();
		push = pushHttp.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket ws, Response response)
			{
				pushWait = 60_000;
			}

			@Override
			public void onMessage(WebSocket ws, String text)
			{
				if ("finished".equals(text) || "board".equals(text))
				{
					fetch();
				}
			}

			@Override
			public void onClosed(WebSocket ws, int code, String reason)
			{
				dropped(ws);
			}

			@Override
			public void onFailure(WebSocket ws, Throwable t, Response response)
			{
				log.debug("Cabbingo: live line dropped", t);
				dropped(ws);
			}
		});
	}

	private synchronized void dropped(WebSocket ws)
	{
		if (push == ws)
		{
			push = null;
			pushAgain = System.currentTimeMillis() + pushWait;
			pushWait = Math.min(pushWait * 2, 30 * 60_000);
		}
	}

	synchronized void closePush()
	{
		if (push != null)
		{
			push.close(1000, null);
			push = null;
		}
		pushAgain = 0;
	}
}
