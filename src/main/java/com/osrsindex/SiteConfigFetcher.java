package com.osrsindex;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keeps this client's {@link SiteConfig} from osrsindex.com (docs/site-config.md). It asks only while the client is
 * linked and the plugin ticks it, which it does only while logged in: one {@code GET} with the token as a bearer
 * on the first tick, then every {@code refresh_seconds}, or sooner when a sync says the config changed.
 *
 * <p>An answer it cannot use never costs the plugin anything: a site without the route ({@code 404}) means the
 * built-in values, and an error, a body that is not a JSON object, or no answer keeps the config it already has.
 * A new or cleared token goes back to the built-in values at once, because the config may be the account's own.
 */
final class SiteConfigFetcher
{
	/** One {@code GET} with the token as a bearer, answered with its status (-1 for no response) and body. */
	interface Transport
	{
		void get(String url, String token, Handler handler);
	}

	interface Handler
	{
		void handle(int status, String body);
	}

	/** How long syncs wait, after the first ask for a token, for a config or a 404 when neither has come. */
	static final long FIRST_ANSWER_WAIT_MILLIS = 30_000;
	/** After an error, or no answer: keep what we have, and ask again in 5 minutes. */
	static final long RETRY_MILLIS = 300_000;
	/** A site without the route: ask again in an hour. */
	static final long NOT_SERVED_RETRY_MILLIS = 3_600_000;
	/** A sync's {@code config_version} asks again, but never sooner than this after the last ask. */
	static final long SOONEST_REFRESH_MILLIS = 60_000;
	/** The largest body read as a config, in bytes. */
	static final int MAX_BODY_BYTES = 2_000_000;

	private final Transport transport;
	private final LongSupplier clock;
	/** Gets each config the plugin should now use, on whichever thread answered. */
	private final Consumer<SiteConfig> onConfig;

	private String token = "";
	private SiteConfig current = SiteConfig.DEFAULTS;
	/** Bumped on every token change, so an answer for the old token is ignored. */
	private int generation;
	private boolean inFlight;
	private long nextAskAtMillis;
	private long lastAskAtMillis = Long.MIN_VALUE / 2;
	/** When the first ask for this token went out, or -1. */
	private long firstAskAtMillis = -1;
	/** A config or a 404 has come for this token: what the site wants is known. */
	private boolean settled;
	/** The highest {@code config_version} a sync named for this token, or -1. */
	private int namedVersion = -1;
	/** {@link #namedVersion} when the ask in flight went out, so a newer one named meanwhile is asked for after it. */
	private int namedVersionAtAsk = -1;

	SiteConfigFetcher(OkHttpClient http, Consumer<SiteConfig> onConfig)
	{
		this(okHttp(http), System::currentTimeMillis, onConfig);
	}

	SiteConfigFetcher(Transport transport, LongSupplier clock, Consumer<SiteConfig> onConfig)
	{
		this.transport = transport;
		this.clock = clock;
		this.onConfig = onConfig;
	}

	synchronized SiteConfig current()
	{
		return current;
	}

	/** Call it every game tick with the current token. It asks when an ask is due, and does nothing otherwise. */
	void tick(String newToken)
	{
		String next = newToken == null ? "" : newToken.trim();
		SiteConfig reset = null;
		int askGeneration;
		synchronized (this)
		{
			if (!next.equals(token))
			{
				token = next;
				generation++;
				inFlight = false;
				settled = false;
				firstAskAtMillis = -1;
				nextAskAtMillis = 0;
				lastAskAtMillis = Long.MIN_VALUE / 2;
				namedVersion = -1;
				namedVersionAtAsk = -1;
				if (current != SiteConfig.DEFAULTS)
				{
					current = SiteConfig.DEFAULTS;
					reset = current;
				}
			}
			long now = clock.getAsLong();
			boolean ask = Tokens.wellFormed(token) && !inFlight && now >= nextAskAtMillis;
			if (ask)
			{
				inFlight = true;
				lastAskAtMillis = now;
				namedVersionAtAsk = namedVersion;
				if (firstAskAtMillis < 0)
				{
					firstAskAtMillis = now;
				}
			}
			askGeneration = ask ? generation : -1;
		}
		if (reset != null)
		{
			onConfig.accept(reset);
		}
		if (askGeneration >= 0)
		{
			transport.get(Endpoints.pluginConfig(), next, (status, body) -> answer(askGeneration, status, body));
		}
	}

	/**
	 * A stored sync named {@code version} as the config the site serves now. A version higher than any named before,
	 * and than the config's, asks again soon. Repeating the same version asks nothing more, so a site whose syncs and
	 * config disagree costs one ask, not one a minute.
	 */
	synchronized void changed(int version)
	{
		if (version <= namedVersion)
		{
			return;
		}
		namedVersion = version;
		if (version > current.version && !inFlight)
		{
			nextAskAtMillis = Math.min(nextAskAtMillis, lastAskAtMillis + SOONEST_REFRESH_MILLIS);
		}
	}

	/**
	 * True while syncs for this token should wait for the config: asked, with no config or 404 yet, and not for long.
	 * An error ends the wait only when the time is up, so a paused part does not go out because one ask failed.
	 */
	synchronized boolean awaitingFirstAnswer()
	{
		return !settled && firstAskAtMillis >= 0 && clock.getAsLong() - firstAskAtMillis < FIRST_ANSWER_WAIT_MILLIS;
	}

	private void answer(int askGeneration, int status, String body)
	{
		SiteConfig use;
		synchronized (this)
		{
			if (askGeneration != generation)
			{
				return;
			}
			inFlight = false;
			long now = clock.getAsLong();
			SiteConfig served = status == 200 ? SiteConfig.parseOrNull(body) : null;
			if (served != null)
			{
				current = served;
				settled = true;
				nextAskAtMillis = now + current.refreshSeconds * 1_000L;
			}
			else if (status == 404)
			{
				current = SiteConfig.DEFAULTS;
				settled = true;
				nextAskAtMillis = now + NOT_SERVED_RETRY_MILLIS;
			}
			else
			{
				nextAskAtMillis = now + RETRY_MILLIS;
			}
			if (namedVersion > namedVersionAtAsk && namedVersion > current.version)
			{
				// A sync named a newer config while this ask was out, and this answer is older: ask once more soon.
				nextAskAtMillis = Math.min(nextAskAtMillis, lastAskAtMillis + SOONEST_REFRESH_MILLIS);
			}
			use = current;
		}
		onConfig.accept(use);
	}

	/** The transport the plugin uses: the {@code GET} through {@code http}, which names the plugin's version. */
	static Transport okHttp(OkHttpClient http)
	{
		return (url, token, handler) ->
		{
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				// Always from the site: the shared client's disk cache keys by address, not by token.
				.cacheControl(CacheControl.FORCE_NETWORK)
				.get()
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					handler.handle(-1, "");
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					int status = response.code();
					String body;
					try (ResponseBody responseBody = response.body())
					{
						body = responseBody == null ? "" : bounded(responseBody);
					}
					catch (IOException e)
					{
						body = null;
					}
					if (body == null)
					{
						handler.handle(-1, "");
						return;
					}
					handler.handle(status, body);
				}
			});
		};
	}

	/**
	 * The body as text, or null when it is larger than {@link #MAX_BODY_BYTES}. A config is a few hundred kilobytes
	 * at most, so anything far larger is not one, and it is never read whole into memory.
	 */
	static String bounded(ResponseBody body) throws IOException
	{
		return SiteHttp.bounded(body, MAX_BODY_BYTES);
	}
}
