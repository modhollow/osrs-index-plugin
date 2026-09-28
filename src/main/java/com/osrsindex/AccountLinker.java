package com.osrsindex;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Links this client to an osrsindex.com account without pasting a token or typing a code.
 *
 * <ol>
 *   <li>{@link #start} asks the site for a code pair. The short user code is shown to the player, and
 *   {@link #approvalUrl} is the site's page with that code already in it.</li>
 *   <li>The player, signed in, approves it: at that page, opened by the panel's button, or by typing the
 *   code at osrsindex.com/link.</li>
 *   <li>{@link #tick} polls with the long device code until the site hands over a token, once.</li>
 * </ol>
 *
 * State changes happen on whatever thread OkHttp answers on; the plugin marshals them to the client
 * thread through the listener.
 */
final class AccountLinker
{
	enum State
	{
		/** No link in progress. */
		IDLE,
		/** Asking the site for a code. */
		STARTING,
		/** A code is showing; waiting for the player to approve it. */
		WAITING,
		/** The code expired or the site forgot it. A new one is needed. */
		EXPIRED,
		/** The site has no link endpoint yet, or it refused. Paste a token instead. */
		UNAVAILABLE,
		/** The account is at its token limit. Revoke one on the account page. */
		TOO_MANY_TOKENS,
	}

	interface Listener
	{
		void stateChanged(State state, String userCode);

		void linked(String token);
	}

	/** One JSON POST, answered with its status (-1 for no response) and its body when it is a JSON object. */
	interface Transport
	{
		void post(String url, JsonObject body, Handler handler);
	}

	interface Handler
	{
		void handle(int status, JsonObject json);
	}

	private static final MediaType JSON = MediaType.parse("application/json");
	private static final int DEFAULT_INTERVAL_SECONDS = 15;
	/** The soonest {@link #pollSoon} polls after the last request. */
	static final long EARLY_POLL_GAP_MILLIS = 5_000L;
	/**
	 * Link requests this plugin sends in any 60 s. The site allows 5 a minute from one address; one short
	 * leaves room for its window not lining up with ours. Every poll, early or on schedule, waits for room.
	 */
	static final int MAX_REQUESTS_PER_MINUTE = 4;
	private static final long MINUTE_MILLIS = 60_000L;

	private final Transport transport;
	private final LongSupplier clock;
	private final Listener listener;
	/** When each recent link request went out, oldest first. */
	private final Deque<Long> recentRequests = new ArrayDeque<>();

	private volatile State state = State.IDLE;
	private volatile String userCode;
	private volatile String approvalUrl;
	private volatile String deviceCode;
	private volatile long expiresAtMillis;
	private volatile long nextPollAtMillis;
	/** The player came back to the client: poll before the schedule, when the gap and the minute allow. */
	private volatile boolean earlyPollWanted;
	private volatile int intervalSeconds = DEFAULT_INTERVAL_SECONDS;
	private volatile boolean inFlight;

	AccountLinker(OkHttpClient http, Gson gson, Listener listener)
	{
		this(okHttp(http, gson), System::currentTimeMillis, listener);
	}

	AccountLinker(Transport transport, LongSupplier clock, Listener listener)
	{
		this.transport = transport;
		this.clock = clock;
		this.listener = listener;
	}

	State state()
	{
		return state;
	}

	String userCode()
	{
		return userCode;
	}

	/** The site's approval page with the waiting code in it, or null when no code is waiting. */
	String approvalUrl()
	{
		return state == State.WAITING ? approvalUrl : null;
	}

	/** Ask for a new code. The character name is shown on the approval page so the player can recognise it. */
	void start(String character)
	{
		if (inFlight || state == State.STARTING)
		{
			return;
		}
		JsonObject body = new JsonObject();
		body.addProperty("character", character);
		set(State.STARTING, null);
		inFlight = true;
		recordRequest(clock.getAsLong());
		transport.post(Endpoints.linkStart(), body, (status, json) ->
		{
			inFlight = false;
			if (status == 200 && json != null && json.has("device_code") && json.has("user_code"))
			{
				long now = clock.getAsLong();
				String code = json.get("user_code").getAsString();
				deviceCode = json.get("device_code").getAsString();
				int expiresIn = json.has("expires_in") ? json.get("expires_in").getAsInt() : 600;
				intervalSeconds = Math.max(5, json.has("interval") ? json.get("interval").getAsInt() : DEFAULT_INTERVAL_SECONDS);
				expiresAtMillis = now + expiresIn * 1_000L;
				nextPollAtMillis = now + intervalSeconds * 1_000L;
				earlyPollWanted = false;
				approvalUrl = approvalUrl(json, code);
				set(State.WAITING, code);
			}
			else if (status == 429)
			{
				set(State.EXPIRED, null);
			}
			else
			{
				set(State.UNAVAILABLE, null);
			}
		});
	}

	/**
	 * The page to open for this code: the site's {@code verification_url_complete} when it is on this
	 * plugin's own origin, else {@code /link?code=} built here, for a site that does not send it.
	 */
	static String approvalUrl(JsonObject json, String userCode)
	{
		String complete = string(json, "verification_url_complete");
		String origin = Endpoints.origin();
		if (complete != null && complete.startsWith(origin + "/"))
		{
			return complete;
		}
		return Endpoints.linkApproval(userCode);
	}

	private static String string(JsonObject json, String key)
	{
		JsonElement value = json == null ? null : json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	/**
	 * Poll before the schedule: the player is back in the client, likely from approving in the browser.
	 * Every time they come back, but {@link #tick} sends it only {@link #EARLY_POLL_GAP_MILLIS} after the
	 * last request and when the minute has room.
	 */
	void pollSoon()
	{
		if (state == State.WAITING)
		{
			earlyPollWanted = true;
		}
	}

	/**
	 * Poll when one is due and the minute has room. Call it every game tick; it does nothing most of the
	 * time. An early poll may take only {@code MAX_REQUESTS_PER_MINUTE - 1} of the minute, so the
	 * scheduled one always has a slot; one the minute has no room for is dropped.
	 */
	void tick()
	{
		long now = clock.getAsLong();
		if (state != State.WAITING || inFlight)
		{
			return;
		}
		if (now > expiresAtMillis)
		{
			set(State.EXPIRED, null);
			return;
		}
		boolean scheduled = now >= nextPollAtMillis;
		boolean early = !scheduled && earlyPollWanted && now - lastRequestAt() >= EARLY_POLL_GAP_MILLIS;
		if (!scheduled && !early)
		{
			return;
		}
		boolean room = roomForRequest(now, scheduled ? MAX_REQUESTS_PER_MINUTE : MAX_REQUESTS_PER_MINUTE - 1);
		if (early && !room)
		{
			earlyPollWanted = false;
		}
		if (!room)
		{
			return;
		}
		earlyPollWanted = false;
		JsonObject body = new JsonObject();
		body.addProperty("device_code", deviceCode);
		inFlight = true;
		recordRequest(now);
		transport.post(Endpoints.linkPoll(), body, (status, json) ->
		{
			inFlight = false;
			nextPollAtMillis = clock.getAsLong() + intervalSeconds * 1_000L;
			String result = json != null && json.has("status") ? json.get("status").getAsString() : "";
			String code = json != null && json.has("code") ? json.get("code").getAsString() : "";
			if (status == 200 && "approved".equals(result) && json.has("token"))
			{
				String token = json.get("token").getAsString();
				deviceCode = null;
				set(State.IDLE, null);
				listener.linked(token);
			}
			else if (status == 200 && "pending".equals(result))
			{
				// Keep waiting.
			}
			else if (status == 409 || "too_many_tokens".equals(code))
			{
				set(State.TOO_MANY_TOKENS, null);
			}
			else if (status == 404 && code.isEmpty())
			{
				// No link endpoint on this site yet.
				set(State.UNAVAILABLE, null);
			}
			else if (status == 429 || status >= 500 || status == -1)
			{
				// Transient: try again at the next interval.
				nextPollAtMillis = clock.getAsLong() + 60_000L;
			}
			else
			{
				set(State.EXPIRED, null);
			}
		});
	}

	void cancel()
	{
		deviceCode = null;
		set(State.IDLE, null);
	}

	private synchronized void recordRequest(long at)
	{
		recentRequests.addLast(at);
	}

	private synchronized long lastRequestAt()
	{
		return recentRequests.isEmpty() ? Long.MIN_VALUE / 2 : recentRequests.peekLast();
	}

	/** Whether one more request keeps the last 60 s at or under {@code limit}. */
	private synchronized boolean roomForRequest(long now, int limit)
	{
		while (!recentRequests.isEmpty() && recentRequests.peekFirst() <= now - MINUTE_MILLIS)
		{
			recentRequests.removeFirst();
		}
		return recentRequests.size() < limit;
	}

	private void set(State next, String code)
	{
		state = next;
		userCode = code;
		if (next != State.WAITING)
		{
			approvalUrl = null;
		}
		listener.stateChanged(next, code);
	}

	private static Transport okHttp(OkHttpClient http, Gson gson)
	{
		return (url, body, handler) ->
		{
			Request request = new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					handler.handle(-1, null);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					JsonObject json = null;
					try (ResponseBody responseBody = response.body())
					{
						JsonElement parsed = new JsonParser().parse(responseBody == null ? "" : responseBody.string());
						json = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
					}
					catch (IOException | RuntimeException ignored)
					{
						// Not JSON (an error page): the status decides.
					}
					handler.handle(response.code(), json);
				}
			});
		};
	}
}
