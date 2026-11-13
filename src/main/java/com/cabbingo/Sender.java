package com.cabbingo;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Posts to the team's URL, shaped like a Dink post, with a screenshot when it matters. The answer says when the
 * plugin's copy of the team is out of date (X-Rev), which sides the post finished (X-Finished), and when a post
 * needed a screenshot after all (409).
 */
@Slf4j
@Singleton
class Sender
{
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final MediaType JPEG = MediaType.parse("image/jpeg");
	private static final float JPEG_QUALITY = 0.85f;

	private final Team team;
	private final Popups popups;
	private final OkHttpClient http;
	private final Gson gson;
	private final DrawManager drawManager;
	private final ScheduledExecutorService executor;

	@Inject
	Sender(Team team, Popups popups, OkHttpClient http, Gson gson, DrawManager drawManager, ScheduledExecutorService executor)
	{
		this.team = team;
		this.popups = popups;
		this.http = http;
		this.gson = gson;
		this.drawManager = drawManager;
		this.executor = executor;
	}

	// Shaped like a Dink post, which the server already reads; Discord shows content and ignores the rest.
	static JsonObject payload(String type, String rsn, String content, JsonObject extra)
	{
		JsonObject p = new JsonObject();
		p.addProperty("type", type);
		p.addProperty("playerName", rsn);
		p.addProperty("content", content);
		p.add("embeds", new JsonArray());
		p.add("extra", extra);
		return p;
	}

	void send(JsonObject payload, boolean shot)
	{
		HttpUrl url = team.url();
		if (url == null)
		{
			return;
		}
		if (!shot)
		{
			post(url, RequestBody.create(JSON, gson.toJson(payload)), payload);
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
			post(url, body.build(), null);
		}));
	}

	// again: the post without a screenshot, sent once more with one when the server asks for it (409)
	private void post(HttpUrl url, RequestBody body, JsonObject again)
	{
		Request request = new Request.Builder().url(url).post(body).build();
		long sent = System.currentTimeMillis();
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
				// The board changed since this plugin's copy of it: ask for the new one now, not in ten minutes.
				String rev = response.header("X-Rev");
				Wants w = team.wants();
				log.debug("Cabbingo: post answered {}, rev {} (had {})", response.code(), rev, w == null ? null : w.rev);
				// sides this post finished: news even when this plugin still had them as finished
				String added = response.header("X-Finished");
				if (added != null)
				{
					popups.unsee(added.split(","), sent);
					// the answer carries the team's finished list: the popup comes now, not after another fetch
					// (an older server sends none, and the fetch below brings it)
					try
					{
						Wants f = gson.fromJson(response.body().charStream(), Wants.class);
						if (f != null && f.finished != null && !f.finished.isEmpty())
						{
							popups.finished(f.finished);
						}
					}
					catch (RuntimeException e)
					{
						log.debug("Cabbingo: no finished list in the answer", e);
					}
				}
				response.close();
				if (rev != null && w != null && (!rev.equals(w.rev) || added != null))
				{
					w.rev = rev; // (one ask, not one per post while it is on its way)
					team.fetch();
				}
				// The server knows this drop needed a screenshot (a tile item the copy did not have yet). The loot is
				// still on the screen a moment after the kill.
				if (response.code() == 409 && again != null)
				{
					send(again, true);
				}
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
}
