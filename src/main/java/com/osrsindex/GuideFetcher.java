package com.osrsindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Downloads osrsindex.com's guides (docs/guides.md), only when the player asks: the list when they open the Guides
 * view, and a guide when they open it. Guides are public, so a request carries no token, and works unlinked. It
 * goes through the plugin's site client, so it names the plugin's version, and that is all it says.
 *
 * <p>Answers are kept in memory: the list for an hour, and each guide until the list gives it a new
 * {@code revision}. A request after that sends {@code If-None-Match}, so an unchanged answer costs a {@code 304}.
 * Nothing is fetched on a timer, and a failure is asked again only when the player opens the view again or clicks
 * Try again.
 */
final class GuideFetcher
{
	/** One {@code GET}, with {@code If-None-Match} when {@code etag} is not null, answered with the status (-1 for none). */
	interface Transport
	{
		void get(String url, String etag, Handler handler);
	}

	interface Handler
	{
		void handle(int status, String body, String etag);
	}

	enum Status
	{
		OK,
		/** {@code 404}: a site without the guides routes, or a guide it no longer offers. */
		NOT_SERVED,
		/** A {@code version} this plugin does not read. */
		UPDATE_PLUGIN,
		/** Anything else, or no answer. */
		FAILED
	}

	static final class Answer<T>
	{
		final Status status;
		/** The value for {@link Status#OK}, else null. */
		final T value;

		Answer(Status status, T value)
		{
			this.status = status;
			this.value = value;
		}
	}

	/** The list is asked for at most once an hour. */
	static final long LIST_MAX_AGE_MILLIS = 3_600_000;
	/** A site without the routes is asked again after this, when the player opens the view. */
	static final long NOT_SERVED_MAX_AGE_MILLIS = 900_000;
	/** After a failure, Try again asks at once, but never sooner than this after the last ask. */
	static final long RETRY_MILLIS = 10_000;
	/** The largest body read, in bytes. A long guide is a few hundred kilobytes. */
	static final int MAX_BODY_BYTES = 4_000_000;
	/** Guides kept in memory at once. */
	static final int CACHED_GUIDES = 8;

	private final Transport transport;
	private final LongSupplier clock;

	private Answer<Guide.Listing> listing;
	private String listingEtag;
	private long listingAt;
	private long listingMaxAge;
	private boolean listingInFlight;
	private final List<Consumer<Answer<Guide.Listing>>> listingWaiters = new ArrayList<>();

	private final Map<String, Cached> guides = new LinkedHashMap<String, Cached>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest)
		{
			return size() > CACHED_GUIDES;
		}
	};
	private final Map<String, List<Consumer<Answer<Guide>>>> guideWaiters = new HashMap<>();
	private final Map<String, Long> guideFailedAt = new HashMap<>();

	private static final class Cached
	{
		final Guide guide;
		final String etag;

		Cached(Guide guide, String etag)
		{
			this.guide = guide;
			this.etag = etag;
		}
	}

	GuideFetcher(OkHttpClient http)
	{
		this(okHttp(http), System::currentTimeMillis);
	}

	GuideFetcher(Transport transport, LongSupplier clock)
	{
		this.transport = transport;
		this.clock = clock;
	}

	/**
	 * The guide list, for the player opening the Guides view. A list from the last hour is answered at once, on this
	 * thread; otherwise it is asked for, and answered on the thread that gets the response.
	 */
	void listing(Consumer<Answer<Guide.Listing>> done)
	{
		Answer<Guide.Listing> known;
		String etag;
		synchronized (this)
		{
			long now = clock.getAsLong();
			known = listing != null && now - listingAt < listingMaxAge ? listing : null;
			if (known == null)
			{
				listingWaiters.add(done);
				if (listingInFlight)
				{
					return;
				}
				listingInFlight = true;
				listingAt = now;
			}
			etag = listing != null && listing.value != null ? listingEtag : null;
		}
		if (known != null)
		{
			done.accept(known);
			return;
		}
		transport.get(Endpoints.guides(), etag, this::listingAnswered);
	}

	private void listingAnswered(int status, String body, String etag)
	{
		Answer<Guide.Listing> answer;
		List<Consumer<Answer<Guide.Listing>>> waiting;
		synchronized (this)
		{
			listingInFlight = false;
			long maxAge = RETRY_MILLIS;
			if (status == 304 && listing != null && listing.value != null)
			{
				answer = listing;
				maxAge = LIST_MAX_AGE_MILLIS;
			}
			else if (status == 200)
			{
				GuideParser.Result<Guide.Listing> parsed = GuideParser.listing(body);
				answer = answer(parsed);
				if (answer.status == Status.OK)
				{
					listingEtag = etag;
				}
				maxAge = answer.status == Status.FAILED ? RETRY_MILLIS : LIST_MAX_AGE_MILLIS;
			}
			else if (status == 404)
			{
				answer = new Answer<>(Status.NOT_SERVED, null);
				maxAge = NOT_SERVED_MAX_AGE_MILLIS;
			}
			else
			{
				answer = new Answer<>(Status.FAILED, null);
			}
			if (answer.status == Status.OK || listing == null || listing.value == null)
			{
				listing = answer;
				listingMaxAge = maxAge;
			}
			else
			{
				// A failed refresh keeps the list it has, and asks again on the next open after a short wait.
				answer = listing;
				listingMaxAge = RETRY_MILLIS;
			}
			waiting = new ArrayList<>(listingWaiters);
			listingWaiters.clear();
		}
		for (Consumer<Answer<Guide.Listing>> done : waiting)
		{
			done.accept(answer);
		}
	}

	/** Try again after a failure: the next {@link #listing} asks, unless it asked moments ago. */
	synchronized void forgetFailures()
	{
		if (listing != null && listing.status != Status.OK)
		{
			listingMaxAge = Math.min(listingMaxAge, RETRY_MILLIS);
		}
		guideFailedAt.clear();
	}

	/**
	 * One guide, for the player opening it. The copy in memory is answered at once when {@code revision} is null or
	 * its own; otherwise the guide is asked for, with {@code If-None-Match}.
	 */
	void guide(String id, String revision, Consumer<Answer<Guide>> done)
	{
		Cached cached;
		boolean ask;
		synchronized (this)
		{
			cached = guides.get(id);
			if (cached != null && (revision == null || revision.equals(cached.guide.revision)))
			{
				ask = false;
			}
			else
			{
				Long failedAt = guideFailedAt.get(id);
				if (failedAt != null && clock.getAsLong() - failedAt < RETRY_MILLIS)
				{
					cached = null;
					ask = false;
				}
				else
				{
					List<Consumer<Answer<Guide>>> waiting = guideWaiters.get(id);
					if (waiting != null)
					{
						waiting.add(done);
						return;
					}
					waiting = new ArrayList<>();
					waiting.add(done);
					guideWaiters.put(id, waiting);
					ask = true;
				}
			}
		}
		if (!ask)
		{
			done.accept(cached != null ? new Answer<>(Status.OK, cached.guide) : new Answer<>(Status.FAILED, null));
			return;
		}
		String etag = cached == null ? null : cached.etag;
		transport.get(Endpoints.guide(id), etag, (status, body, newEtag) -> guideAnswered(id, status, body, newEtag));
	}

	private void guideAnswered(String id, int status, String body, String etag)
	{
		Answer<Guide> answer;
		List<Consumer<Answer<Guide>>> waiting;
		synchronized (this)
		{
			Cached cached = guides.get(id);
			if (status == 304 && cached != null)
			{
				answer = new Answer<>(Status.OK, cached.guide);
			}
			else if (status == 200)
			{
				answer = answer(GuideParser.guide(body));
				if (answer.status == Status.OK && !id.equals(answer.value.id))
				{
					answer = new Answer<>(Status.FAILED, null);
				}
				if (answer.status == Status.OK)
				{
					guides.put(id, new Cached(answer.value, etag));
				}
			}
			else if (status == 404)
			{
				guides.remove(id);
				answer = new Answer<>(Status.NOT_SERVED, null);
			}
			else
			{
				answer = new Answer<>(Status.FAILED, null);
			}
			if (answer.status == Status.FAILED)
			{
				guideFailedAt.put(id, clock.getAsLong());
			}
			else
			{
				guideFailedAt.remove(id);
			}
			waiting = guideWaiters.remove(id);
		}
		if (waiting != null)
		{
			for (Consumer<Answer<Guide>> done : waiting)
			{
				done.accept(answer);
			}
		}
	}

	private static <T> Answer<T> answer(GuideParser.Result<T> parsed)
	{
		switch (parsed.problem)
		{
			case NONE:
				return new Answer<>(Status.OK, parsed.value);
			case UPDATE_PLUGIN:
				return new Answer<>(Status.UPDATE_PLUGIN, null);
			case UNREADABLE:
			default:
				return new Answer<>(Status.FAILED, null);
		}
	}

	/** The plugin's own {@code GET}: through {@code http}, which names the plugin's version, and with no token. */
	static Transport okHttp(OkHttpClient http)
	{
		return (url, etag, handler) -> http.newCall(request(url, etag)).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				handler.handle(-1, "", null);
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
					handler.handle(-1, "", null);
					return;
				}
				handler.handle(status, body, response.header("ETag"));
			}
		});
	}

	static Request request(String url, String etag)
	{
		Request.Builder request = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.get();
		if (etag != null && !etag.isEmpty() && etag.length() <= 200)
		{
			request.header("If-None-Match", etag);
		}
		return request.build();
	}

	/** The body as text, or null when it is larger than {@link #MAX_BODY_BYTES}; never read whole when it is. */
	static String bounded(ResponseBody body) throws IOException
	{
		if (body.contentLength() > MAX_BODY_BYTES)
		{
			return null;
		}
		BufferedSource source = body.source();
		if (source.request(MAX_BODY_BYTES + 1L))
		{
			return null;
		}
		return source.getBuffer().readString(StandardCharsets.UTF_8);
	}
}
