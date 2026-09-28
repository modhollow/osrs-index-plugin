package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The WebSocket osrsindex.com pushes map places down. It holds one socket to the relay while the player is linked, logged in <b>and</b> has
 * turned on "Receive destinations", and none otherwise.
 *
 * <p>The contract's rules, as this class keeps them:
 * <ul>
 *   <li>Reconnect after 15 s, doubling to 5 minutes, with ±20 % jitter, honouring {@code Retry-After}.</li>
 *   <li>After {@code 401} or close {@code 4401} (revoked), stay closed until the token changes: only a
 *   fresh link re-opens it.</li>
 *   <li>After close {@code 4409} (a newer socket from this token replaced this one), stay closed until
 *   the player logs in again, re-enables the setting or relinks.</li>
 *   <li>Ignore a frame of a type it does not know.</li>
 * </ul>
 *
 * <p>The socket itself is behind {@link Connector}, so the state machine is tested without a network.
 * Every {@link Connection} callback names its connection, and one that is no longer current is ignored,
 * so a late event from a replaced socket cannot touch the live one.
 */
final class DestinationSocket
{
	static final String SUBPROTOCOL = "osrsindex.destinations.v1";
	static final int PROTOCOL = 1;

	static final long FIRST_RETRY_MILLIS = 15_000;
	static final long MAX_RETRY_MILLIS = 300_000;
	static final double JITTER = 0.20;

	/** The contract's close codes. */
	static final int CLOSE_NORMAL = 1000;
	static final int CLOSE_REVOKED = 4401;
	static final int CLOSE_REPLACED = 4409;

	/** What the player sees in the panel. */
	enum Status
	{
		/** Not wanted: the setting is off, the client is not linked, or nobody is logged in. */
		OFF,
		CONNECTING,
		LISTENING,
		/** Closed unexpectedly; the next attempt is scheduled. */
		RETRYING,
		/** The site revoked this link. Only a new link re-opens the socket. */
		REVOKED,
		/** Another client with this link took over the socket. */
		REPLACED,
	}

	/** Opens a socket. The production one is OkHttp (see {@link OkHttpConnector}). */
	interface Connector
	{
		Connection open(String token, Events events);
	}

	/** One open, or opening, socket. */
	interface Connection
	{
		boolean send(String text);

		void close(int code, String reason);
	}

	/** What a {@link Connection} reports, from any thread. */
	interface Events
	{
		/** The upgrade succeeded; {@code protocol} is the server's {@code Sec-WebSocket-Protocol}. */
		void opened(Connection connection, String protocol);

		void text(Connection connection, String text);

		/** The socket closed with this code (the server's, or 1006 when it simply dropped). */
		void closed(Connection connection, int code);

		/**
		 * The upgrade was refused or the socket failed. {@code httpStatus} is the refusal's status, or 0
		 * for a network failure; {@code retryAfterSeconds} is its {@code Retry-After}, or -1.
		 */
		void failed(Connection connection, int httpStatus, long retryAfterSeconds);
	}

	/** Runs a task later. The production one is RuneLite's shared executor. */
	interface Scheduler
	{
		Cancellable schedule(Runnable task, long delayMillis);
	}

	interface Cancellable
	{
		void cancel();
	}

	private final Connector connector;
	private final Scheduler scheduler;
	private final Random random;
	private final Consumer<Destination> onDestination;
	private final Consumer<Status> onStatus;
	private final Events events = new ConnectionEvents();

	/** What the plugin last asked for. */
	private boolean wanted;
	private String token = "";

	private Connection connection;
	private Cancellable retry;
	private int failures;
	private Status status = Status.OFF;
	/** The token the site revoked, or that another client took over; null when neither happened. */
	private String stoppedToken;
	/** Why {@link #stoppedToken} stopped: {@link Status#REVOKED} or {@link Status#REPLACED}. */
	private Status stoppedReason;

	DestinationSocket(Connector connector, Scheduler scheduler, Random random, Consumer<Destination> onDestination,
		Consumer<Status> onStatus)
	{
		this.connector = connector;
		this.scheduler = scheduler;
		this.random = random;
		this.onDestination = onDestination;
		this.onStatus = onStatus;
	}

	/**
	 * Whether a socket may be open at all: the contract opens none unless the player opted in, the client
	 * holds a token, and someone is logged in.
	 */
	static boolean shouldListen(boolean optedIn, String token, boolean loggedIn)
	{
		return optedIn && loggedIn && token != null && token.startsWith(OsrsIndexPlugin.TOKEN_PREFIX);
	}

	/**
	 * Apply the plugin's current state. Safe to call on every change: it opens, closes or leaves the
	 * socket as the state asks.
	 *
	 * @param fresh true when the player acted (logged in, turned the setting on, relinked), which lifts a
	 *              "replaced" stop; a revoked token stays stopped until the token itself changes
	 */
	synchronized void update(boolean optedIn, String newToken, boolean loggedIn, boolean fresh)
	{
		String next = newToken == null ? "" : newToken.trim();
		boolean shouldListen = shouldListen(optedIn, next, loggedIn);
		boolean tokenChanged = !next.equals(token);
		token = next;
		wanted = shouldListen;

		if (tokenChanged || (fresh && stoppedReason == Status.REPLACED))
		{
			stoppedToken = null;
			stoppedReason = null;
		}
		if (!wanted)
		{
			disconnect(Status.OFF);
			return;
		}
		if (token.equals(stoppedToken))
		{
			setStatus(stoppedReason);
			return;
		}
		if (tokenChanged)
		{
			// The old socket belongs to the old token.
			disconnect(Status.OFF);
		}
		if (connection == null && retry == null)
		{
			failures = 0;
			connect();
		}
	}

	/** Close for good: the plugin is shutting down. */
	synchronized void stop()
	{
		wanted = false;
		disconnect(Status.OFF);
	}

	/** Acknowledge a destination the plugin accepted. False when there is no socket to send it on. */
	synchronized boolean ack(String id)
	{
		if (connection == null || status != Status.LISTENING)
		{
			return false;
		}
		JsonObject frame = new JsonObject();
		frame.addProperty("type", "ack");
		frame.addProperty("id", id);
		return connection.send(frame.toString());
	}

	synchronized Status status()
	{
		return status;
	}

	// --- internals ---------------------------------------------------------

	private void connect()
	{
		setStatus(failures == 0 ? Status.CONNECTING : Status.RETRYING);
		connection = connector.open(token, events);
	}

	private void disconnect(Status next)
	{
		if (retry != null)
		{
			retry.cancel();
			retry = null;
		}
		if (connection != null)
		{
			Connection closing = connection;
			connection = null;
			closing.close(CLOSE_NORMAL, "closing");
		}
		setStatus(next);
	}

	/** The socket is gone; try again later unless it should stay down. */
	private void scheduleRetry(long retryAfterSeconds)
	{
		connection = null;
		if (!wanted || token.equals(stoppedToken))
		{
			return;
		}
		long delay = retryDelayMillis(failures, retryAfterSeconds, random);
		failures++;
		setStatus(Status.RETRYING);
		retry = scheduler.schedule(this::retryNow, delay);
	}

	private synchronized void retryNow()
	{
		retry = null;
		if (wanted && connection == null && !token.equals(stoppedToken))
		{
			connect();
		}
	}

	private void stopFor(Status reason)
	{
		connection = null;
		stoppedToken = token;
		stoppedReason = reason;
		if (retry != null)
		{
			retry.cancel();
			retry = null;
		}
		setStatus(reason);
	}

	private void setStatus(Status next)
	{
		if (status != next)
		{
			status = next;
			onStatus.accept(next);
		}
	}

	/**
	 * How long to wait before attempt {@code failures + 1}: 15 s doubling to 5 minutes, ±20 %, and never
	 * less than the server's {@code Retry-After}.
	 */
	static long retryDelayMillis(int failures, long retryAfterSeconds, Random random)
	{
		long base = FIRST_RETRY_MILLIS;
		for (int i = 0; i < failures && base < MAX_RETRY_MILLIS; i++)
		{
			base *= 2;
		}
		base = Math.min(base, MAX_RETRY_MILLIS);
		double factor = 1 - JITTER + random.nextDouble() * 2 * JITTER;
		long delay = Math.round(base * factor);
		return retryAfterSeconds > 0 ? Math.max(delay, retryAfterSeconds * 1_000L) : delay;
	}

	/** A {@code destination} frame as a {@link Destination}, or null for any other or malformed frame. */
	static Destination parseDestination(String text)
	{
		JsonObject frame = object(text);
		if (frame == null || !"destination".equals(string(frame, "type")))
		{
			return null;
		}
		String id = string(frame, "id");
		String label = string(frame, "label");
		String expiresAt = string(frame, "expires_at");
		Integer x = integer(frame, "x");
		Integer y = integer(frame, "y");
		Integer plane = integer(frame, "plane");
		if (id == null || label == null || expiresAt == null || x == null || y == null || plane == null)
		{
			return null;
		}
		try
		{
			return new Destination(id, x, y, plane, label, Instant.parse(expiresAt));
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	/** True for the {@code hello} frame of protocol 1. */
	static boolean isHello(String text)
	{
		JsonObject frame = object(text);
		Integer protocol = frame == null ? null : integer(frame, "protocol");
		return frame != null && "hello".equals(string(frame, "type")) && protocol != null && protocol == PROTOCOL;
	}

	private static JsonObject object(String text)
	{
		try
		{
			JsonElement element = new JsonParser().parse(text);
			return element.isJsonObject() ? element.getAsJsonObject() : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	private static String string(JsonObject frame, String name)
	{
		JsonElement value = frame.get(name);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? value.getAsString() : null;
	}

	private static Integer integer(JsonObject frame, String name)
	{
		JsonElement value = frame.get(name);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			return null;
		}
		JsonPrimitive number = value.getAsJsonPrimitive();
		double asDouble = number.getAsDouble();
		return asDouble == Math.rint(asDouble) && Math.abs(asDouble) <= Integer.MAX_VALUE ? (int) asDouble : null;
	}

	private final class ConnectionEvents implements Events
	{
		@Override
		public void opened(Connection from, String protocol)
		{
			synchronized (DestinationSocket.this)
			{
				if (from != connection)
				{
					return;
				}
				if (!SUBPROTOCOL.equals(protocol))
				{
					// A relay that does not speak this contract: give up on this socket and back off.
					connection = null;
					from.close(CLOSE_NORMAL, "unsupported protocol");
					scheduleRetry(-1);
					return;
				}
				failures = 0;
				setStatus(Status.LISTENING);
			}
		}

		@Override
		public void text(Connection from, String text)
		{
			Destination destination;
			synchronized (DestinationSocket.this)
			{
				if (from != connection)
				{
					return;
				}
				destination = parseDestination(text);
			}
			// Outside the lock: the plugin hands it to the client thread, which may ack it.
			if (destination != null)
			{
				onDestination.accept(destination);
			}
		}

		@Override
		public void closed(Connection from, int code)
		{
			synchronized (DestinationSocket.this)
			{
				if (from != connection)
				{
					return;
				}
				if (code == CLOSE_REVOKED)
				{
					stopFor(Status.REVOKED);
				}
				else if (code == CLOSE_REPLACED)
				{
					stopFor(Status.REPLACED);
				}
				else
				{
					scheduleRetry(-1);
				}
			}
		}

		@Override
		public void failed(Connection from, int httpStatus, long retryAfterSeconds)
		{
			synchronized (DestinationSocket.this)
			{
				if (from != connection)
				{
					return;
				}
				if (httpStatus == 401)
				{
					stopFor(Status.REVOKED);
				}
				else
				{
					scheduleRetry(retryAfterSeconds);
				}
			}
		}
	}
}
