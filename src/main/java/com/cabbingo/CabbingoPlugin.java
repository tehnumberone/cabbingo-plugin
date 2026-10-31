package com.cabbingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.events.PlayerLootReceived;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.DrawManager;
import net.runelite.http.api.loottracker.LootRecordType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends every kill, PvP kill and pet of a bingo player to their Cabbingo team. The server counts it for the
 * team's gp lists and its tiles, and passes it on to the team's Discord channel. A screenshot goes along only
 * when it matters: a tile's item, a pet, a big drop, or a drop a tile counts the value of.
 */
@Slf4j
@PluginDescriptor(
	name = "Cabbingo",
	description = "Sends your bingo drops, kills and pets to your Cabbingo team",
	tags = {"bingo", "clan", "loot", "discord"}
)
public class CabbingoPlugin extends Plugin
{
	// The Loot Logger copy of Cabbingo's Dink worker (dink-worker/ in the Cabbingo repo). -Dcabbingo.url=... for a
	// local test.
	static final String SERVER = System.getProperty("cabbingo.url", "https://cabbingo-loot.databackup.workers.dev");
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");
	private static final float JPEG_QUALITY = 0.85f;
	private static final String[] PET_MESSAGES = {
		"You have a funny feeling like you're being followed",
		"You feel something weird sneaking into your backpack",
		"You have a funny feeling like you would have been followed",
	};
	private static final String COLLECTION_LOG = "New item added to your collection log: ";

	@Inject private Client client;
	@Inject private CabbingoConfig config;
	@Inject private ItemManager itemManager;
	@Inject private DrawManager drawManager;
	@Inject private OkHttpClient http;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;

	// What the team wants, from the server. Null until it answered (no key, a wrong key, offline).
	private volatile Wants wants;
	// A pet message waits a few ticks for the collection log line that names the pet.
	private int petTicks = -1;
	private String petName;
	private boolean petDuplicate;

	/** The server's GET /plugin/key answer. Names and sources in lower case. */
	static class Wants
	{
		long start;
		long end;
		List<String> players;
		Set<String> items = new HashSet<>();
		Map<String, Double> sources;
		double big;
	}

	@Override
	protected void startUp()
	{
		fetchWants();
	}

	@Override
	protected void shutDown()
	{
		wants = null;
		petTicks = -1;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (CabbingoConfig.GROUP.equals(e.getGroup()))
		{
			fetchWants();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			fetchWants();
		}
	}

	// Tiles of the day and team changes reach the plugin within ten minutes.
	@Schedule(period = 10, unit = ChronoUnit.MINUTES, asynchronous = true)
	public void refreshWants()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			fetchWants();
		}
	}

	private void fetchWants()
	{
		String key = config.teamKey().trim();
		if (key.isEmpty())
		{
			wants = null;
			return;
		}
		Request request = new Request.Builder().url(SERVER + "/plugin/" + key).build();
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
					wants = response.isSuccessful() ? gson.fromJson(response.body().charStream(), Wants.class) : null;
				}
			}
		});
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		loot(e.getNpc().getName(), "NPC", e.getItems());
	}

	// Loot that goes straight into the inventory (Royal Titans, Araxxor, Maggot King): no NpcLootReceived for it.
	@Subscribe
	public void onServerNpcLoot(ServerNpcLoot e)
	{
		loot(e.getComposition().getName(), "NPC", e.getItems());
	}

	@Subscribe
	public void onPlayerLootReceived(PlayerLootReceived e)
	{
		loot(e.getPlayer().getName(), "PLAYER", e.getItems());
	}

	// Raids, chests, clues and the rest the loot tracker sees. Its NPC and PLAYER kills come in above already.
	@Subscribe
	public void onLootReceived(LootReceived e)
	{
		if (e.getType() == LootRecordType.EVENT || e.getType() == LootRecordType.PICKPOCKET)
		{
			loot(e.getName(), e.getType().name(), e.getItems());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		String text = e.getMessage();
		for (String pet : PET_MESSAGES)
		{
			if (text.startsWith(pet))
			{
				petTicks = 3;
				petName = null;
				petDuplicate = text.contains("would have been");
				return;
			}
		}
		if (petTicks >= 0 && text.startsWith(COLLECTION_LOG))
		{
			petName = text.substring(COLLECTION_LOG.length()).replaceAll("<[^>]*>", "").trim();
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (petTicks < 0 || petTicks-- > 0 && petName == null)
		{
			return;
		}
		petTicks = -1;
		String rsn = playing();
		if (rsn == null)
		{
			return;
		}
		JsonObject extra = new JsonObject();
		if (petName != null)
		{
			extra.addProperty("petName", petName);
		}
		extra.addProperty("duplicate", petDuplicate);
		String content = "**" + rsn + "** got a pet" + (petName != null ? ": " + petName : "") + (petDuplicate ? " (duplicate)" : "");
		send(payload("PET", rsn, content, extra), true);
	}

	// The local player's name while they play for the team during the bingo, else null.
	private String playing()
	{
		Wants w = wants;
		long now = System.currentTimeMillis();
		if (w == null || now < w.start || now > w.end || client.getLocalPlayer() == null)
		{
			return null;
		}
		// (the game puts a no-break space in some names; the server and the team list use a plain one)
		String rsn = client.getLocalPlayer().getName();
		return rsn != null && w.players.contains(rsnKey(rsn)) ? rsn.replace('\u00a0', ' ') : null;
	}

	private void loot(String source, String category, Collection<ItemStack> stacks)
	{
		String rsn = playing();
		if (rsn == null || source == null || stacks.isEmpty())
		{
			return;
		}
		Wants w = wants;
		JsonArray items = new JsonArray();
		StringBuilder lines = new StringBuilder();
		long value = 0;
		boolean wanted = false;
		for (ItemStack stack : stacks)
		{
			String name = itemManager.getItemComposition(stack.getId()).getName();
			long price = itemManager.getItemPrice(stack.getId());
			JsonObject item = new JsonObject();
			item.addProperty("id", stack.getId());
			item.addProperty("name", name);
			item.addProperty("quantity", stack.getQuantity());
			item.addProperty("priceEach", price);
			items.add(item);
			value += price * stack.getQuantity();
			wanted |= w.items.contains(name.toLowerCase(Locale.ROOT));
			lines.append('\n').append(stack.getQuantity()).append(" x ").append(name);
		}
		JsonObject extra = new JsonObject();
		extra.addProperty("source", source);
		extra.addProperty("category", category);
		extra.add("items", items);
		// A screenshot where the server sends the post to the team's channel, or a tile counts its value.
		String sourceKey = category.equals("PLAYER") ? "pvp" : source.trim().toLowerCase(Locale.ROOT);
		Double min = w.sources.get(sourceKey);
		boolean shot = wanted || value >= w.big || (min != null && value >= min);
		String content = "**" + rsn + "**: " + source + " (" + String.format(Locale.ROOT, "%,d", value) + " gp)" + lines;
		// (Discord takes at most 2,000 characters)
		if (content.length() > 1900)
		{
			content = content.substring(0, 1900) + "\n...";
		}
		send(payload("LOOT", rsn, content, extra), shot);
	}

	// Shaped like a Dink post, which the server already reads; Discord shows content and ignores the rest.
	private JsonObject payload(String type, String rsn, String content, JsonObject extra)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("playerName", rsn);
		p.addProperty("content", content);
		p.add("embeds", new JsonArray());
		p.add("extra", extra);
		return p;
	}

	private void send(JsonObject payload, boolean shot)
	{
		String key = config.teamKey().trim();
		if (!shot)
		{
			post(key, RequestBody.create(JSON, gson.toJson(payload)));
			return;
		}
		drawManager.requestNextFrameListener(frame -> executor.execute(() ->
		{
			MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM)
				.addFormDataPart("payload_json", gson.toJson(payload));
			try
			{
				body.addFormDataPart("file", "screenshot.jpg", RequestBody.create(JPEG, jpeg(frame)));
			}
			catch (IOException e)
			{
				log.debug("Cabbingo: no screenshot", e);
			}
			post(key, body.build());
		}));
	}

	private void post(String key, RequestBody body)
	{
		Request request = new Request.Builder().url(SERVER + "/plugin/" + key).post(body).build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Cabbingo: post failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
			}
		});
	}

	// JPEG instead of PNG: about 200 KB for a game screenshot instead of 1.5 to 2 MB.
	private static byte[] jpeg(Image frame) throws IOException
	{
		BufferedImage rgb = new BufferedImage(frame.getWidth(null), frame.getHeight(null), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.drawImage(frame, 0, 0, null);
		g.dispose();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(JPEG_QUALITY);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream stream = ImageIO.createImageOutputStream(out))
		{
			writer.setOutput(stream);
			writer.write(null, new IIOImage(rgb, null, null), param);
		}
		finally
		{
			writer.dispose();
		}
		return out.toByteArray();
	}

	// The same as rsnKey in Cabbingo's models/bingo.ts
	static String rsnKey(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT).replaceAll("[_\\-\u00a0]", " ");
	}

	@Provides
	CabbingoConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CabbingoConfig.class);
	}
}
