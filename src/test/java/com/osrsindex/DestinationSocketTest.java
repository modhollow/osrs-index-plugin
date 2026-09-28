package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * The destinations socket's state machine, against a fake connection and a fake
 * scheduler: when it opens, when it closes, when it comes back, and what it does with each frame.
 */
public class DestinationSocketTest
{
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + repeat('a', 43);
	private static final String OTHER_TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + repeat('b', 43);

	private final List<FakeConnection> opened = new ArrayList<>();
	private final List<Scheduled> scheduled = new ArrayList<>();
	private final List<Destination> received = new ArrayList<>();
	private final List<DestinationSocket.Status> statuses = new ArrayList<>();
	private DestinationSocket.Events events;

	private final DestinationSocket socket = new DestinationSocket(
		(token, events) ->
		{
			this.events = events;
			FakeConnection connection = new FakeConnection(token);
			opened.add(connection);
			return connection;
		},
		(task, delayMillis) ->
		{
			Scheduled entry = new Scheduled(task, delayMillis);
			scheduled.add(entry);
			return () -> entry.cancelled = true;
		},
		new Random(7),
		received::add,
		statuses::add);

	private static final class FakeConnection implements DestinationSocket.Connection
	{
		final String token;
		final List<String> sent = new ArrayList<>();
		Integer closedWith;

		FakeConnection(String token)
		{
			this.token = token;
		}

		@Override
		public boolean send(String text)
		{
			sent.add(text);
			return true;
		}

		@Override
		public void close(int code, String reason)
		{
			closedWith = code;
		}
	}

	private static final class Scheduled
	{
		final Runnable task;
		final long delayMillis;
		boolean cancelled;

		Scheduled(Runnable task, long delayMillis)
		{
			this.task = task;
			this.delayMillis = delayMillis;
		}
	}

	private static String repeat(char c, int n)
	{
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < n; i++)
		{
			builder.append(c);
		}
		return builder.toString();
	}

	/** Open and accept a socket for TOKEN. */
	private FakeConnection listening()
	{
		socket.update(true, TOKEN, true, true);
		FakeConnection connection = last();
		events.opened(connection, DestinationSocket.SUBPROTOCOL);
		events.text(connection, "{\"type\":\"hello\",\"protocol\":1}");
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
		return connection;
	}

	private FakeConnection last()
	{
		return opened.get(opened.size() - 1);
	}

	private Scheduled pendingRetry()
	{
		Scheduled retry = null;
		for (Scheduled entry : scheduled)
		{
			if (!entry.cancelled)
			{
				retry = entry;
			}
		}
		return retry;
	}

	// --- criterion 2: off by default, and nothing opens without every condition --------------------

	@Test
	public void opensNoSocketWhileReceiveDestinationsIsOff()
	{
		socket.update(false, TOKEN, true, true);
		assertTrue(opened.isEmpty());
		assertEquals(DestinationSocket.Status.OFF, socket.status());
	}

	@Test
	public void opensNoSocketWithoutATokenOrWhileLoggedOut()
	{
		socket.update(true, "", true, true);
		socket.update(true, "not-one-of-ours", true, true);
		socket.update(true, TOKEN, false, true);
		assertTrue(opened.isEmpty());
		assertFalse(DestinationSocket.shouldListen(true, null, true));
	}

	@Test
	public void theSettingDefaultsToOn()
	{
		// A linked, logged-in client listens unless turned off.
		// Every setting is a default method, so this is exactly what a new install reads.
		OsrsIndexConfig defaults = new OsrsIndexConfig()
		{
		};
		assertTrue(defaults.receiveDestinations());
	}

	@Test
	public void everySettingHasATypeRuneLitesConfigProxyCanReach()
	{
		// A package-private enum as a setting's type throws IllegalAccessError inside the client.
		for (java.lang.reflect.Method method : OsrsIndexConfig.class.getDeclaredMethods())
		{
			Class<?> type = method.getReturnType();
			assertTrue(method.getName() + " returns " + type, type.isPrimitive()
				|| java.lang.reflect.Modifier.isPublic(type.getModifiers()));
		}
	}

	@Test
	public void opensOneSocketWithTheTokenWhenOptedInLinkedAndLoggedIn()
	{
		socket.update(true, TOKEN, true, true);
		socket.update(true, TOKEN, true, false);
		assertEquals(1, opened.size());
		assertEquals(TOKEN, opened.get(0).token);
		assertEquals(DestinationSocket.Status.CONNECTING, socket.status());
	}

	// --- criterion 4: unlink, revoke, logout and opt-out each close it; only a fresh link reopens ------

	@Test
	public void unlinkingClosesTheSocket()
	{
		FakeConnection connection = listening();
		socket.update(true, "", true, false);
		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
		assertEquals(DestinationSocket.Status.OFF, socket.status());
		assertNull(pendingRetry());
	}

	@Test
	public void loggingOutClosesTheSocket()
	{
		FakeConnection connection = listening();
		socket.update(true, TOKEN, false, false);
		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
		assertEquals(DestinationSocket.Status.OFF, socket.status());
	}

	@Test
	public void optingOutClosesTheSocket()
	{
		FakeConnection connection = listening();
		socket.update(false, TOKEN, true, false);
		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
		assertEquals(DestinationSocket.Status.OFF, socket.status());
	}

	@Test
	public void aRevokedTokenStaysClosedUntilAFreshLink()
	{
		FakeConnection connection = listening();
		events.closed(connection, DestinationSocket.CLOSE_REVOKED);

		assertEquals(DestinationSocket.Status.REVOKED, socket.status());
		assertNull(pendingRetry());
		// Logging in again, or toggling the setting, is not a new link.
		socket.update(true, TOKEN, false, false);
		socket.update(true, TOKEN, true, true);
		socket.update(false, TOKEN, true, true);
		socket.update(true, TOKEN, true, true);
		assertEquals(1, opened.size());
		assertEquals(DestinationSocket.Status.REVOKED, socket.status());

		socket.update(true, OTHER_TOKEN, true, true);
		assertEquals(2, opened.size());
		assertEquals(OTHER_TOKEN, last().token);
	}

	@Test
	public void aRefusedUpgradeWith401IsARevokedLink()
	{
		socket.update(true, TOKEN, true, true);
		events.failed(last(), 401, -1);
		assertEquals(DestinationSocket.Status.REVOKED, socket.status());
		assertNull(pendingRetry());
		socket.update(true, TOKEN, true, true);
		assertEquals(1, opened.size());
	}

	@Test
	public void aReplacedSocketStopsUntilThePlayerActs()
	{
		FakeConnection connection = listening();
		events.closed(connection, DestinationSocket.CLOSE_REPLACED);
		assertEquals(DestinationSocket.Status.REPLACED, socket.status());
		assertNull(pendingRetry());

		// A region load is not the player acting.
		socket.update(true, TOKEN, true, false);
		assertEquals(1, opened.size());

		socket.update(true, TOKEN, true, true);
		assertEquals(2, opened.size());
	}

	// --- reconnecting -----------------------------------------------------------------------------

	@Test
	public void anyOtherCloseReconnectsAfterABackoff()
	{
		for (int code : new int[]{1001, 1006, 1009, 1011, 1012, 4429})
		{
			FakeConnection connection = listening();
			events.closed(connection, code);
			Scheduled retry = pendingRetry();
			assertNotNull("close " + code + " schedules a retry", retry);
			assertEquals(DestinationSocket.Status.RETRYING, socket.status());
			int before = opened.size();
			retry.task.run();
			assertEquals(before + 1, opened.size());
			events.opened(last(), DestinationSocket.SUBPROTOCOL);
			// Reset for the next code.
			socket.update(false, TOKEN, true, false);
			scheduled.clear();
		}
	}

	@Test
	public void aNetworkFailureRetriesAndHonoursRetryAfter()
	{
		socket.update(true, TOKEN, true, true);
		events.failed(last(), 429, 90);
		assertTrue(pendingRetry().delayMillis >= 90_000);

		pendingRetry().task.run();
		events.failed(last(), 0, -1);
		assertNotNull(pendingRetry());
	}

	@Test
	public void theBackoffDoublesFrom15SecondsTo5MinutesWithin20Percent()
	{
		Random random = new Random(1);
		long[] bases = {15_000, 30_000, 60_000, 120_000, 240_000, 300_000, 300_000};
		for (int failures = 0; failures < bases.length; failures++)
		{
			for (int sample = 0; sample < 50; sample++)
			{
				long delay = DestinationSocket.retryDelayMillis(failures, -1, random);
				assertTrue(delay >= Math.round(bases[failures] * 0.8));
				assertTrue(delay <= Math.round(bases[failures] * 1.2));
			}
		}
		assertTrue(DestinationSocket.retryDelayMillis(0, 600, random) >= 600_000);
	}

	@Test
	public void aSuccessfulOpenResetsTheBackoff()
	{
		socket.update(true, TOKEN, true, true);
		events.failed(last(), 0, -1);
		pendingRetry().task.run();
		events.failed(last(), 0, -1);
		long second = pendingRetry().delayMillis;
		assertTrue(second >= 24_000);

		pendingRetry().task.run();
		events.opened(last(), DestinationSocket.SUBPROTOCOL);
		events.closed(last(), 1006);
		assertTrue(pendingRetry().delayMillis <= 18_000);
	}

	@Test
	public void aRelayThatDoesNotEchoTheSubprotocolIsClosedAndRetried()
	{
		socket.update(true, TOKEN, true, true);
		FakeConnection connection = last();
		events.opened(connection, null);
		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
		assertNotNull(pendingRetry());
		assertFalse(socket.status() == DestinationSocket.Status.LISTENING);
	}

	@Test
	public void eventsFromAReplacedConnectionAreIgnored()
	{
		FakeConnection stale = listening();
		socket.update(true, OTHER_TOKEN, true, true);
		FakeConnection current = last();
		events.opened(current, DestinationSocket.SUBPROTOCOL);

		events.closed(stale, DestinationSocket.CLOSE_REVOKED);
		events.text(stale, destinationFrame("stale"));
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
		assertTrue(received.isEmpty());
	}

	// --- frames -----------------------------------------------------------------------------------

	private static String destinationFrame(String id)
	{
		return "{\"type\":\"destination\",\"id\":\"" + id + "\",\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T12:02:00.000Z\",\"future\":true}";
	}

	@Test
	public void aDestinationFrameIsHandedOn()
	{
		FakeConnection connection = listening();
		events.text(connection, destinationFrame("d-1"));
		assertEquals(1, received.size());
		Destination destination = received.get(0);
		assertEquals("d-1", destination.id);
		assertEquals(3036, destination.x);
		assertEquals(3294, destination.y);
		assertEquals(0, destination.plane);
		assertEquals("Sarah", destination.label);
		assertEquals(Instant.parse("2026-09-26T12:02:00Z"), destination.expiresAt);
	}

	@Test
	public void unknownAndMalformedFramesAreIgnored()
	{
		FakeConnection connection = listening();
		events.text(connection, "{\"type\":\"news\",\"id\":\"x\"}");
		events.text(connection, "not json");
		events.text(connection, "[1,2]");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":\"3036\",\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1.5,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"yesterday\"}");
		assertTrue(received.isEmpty());
		assertTrue(DestinationSocket.isHello("{\"type\":\"hello\",\"protocol\":1}"));
		assertFalse(DestinationSocket.isHello("{\"type\":\"hello\",\"protocol\":2}"));
	}

	@Test
	public void anAckIsOneSmallFrameAndOnlyWhileListening()
	{
		assertFalse(socket.ack("d-0"));
		FakeConnection connection = listening();
		assertTrue(socket.ack("d-1"));
		assertEquals("{\"type\":\"ack\",\"id\":\"d-1\"}", connection.sent.get(0));
		assertTrue(connection.sent.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_024);

		socket.update(false, TOKEN, true, false);
		assertFalse(socket.ack("d-2"));
	}

	@Test
	public void shutdownClosesAndCancelsTheRetry()
	{
		socket.update(true, TOKEN, true, true);
		events.failed(last(), 0, -1);
		Scheduled retry = pendingRetry();
		socket.stop();
		assertTrue(retry.cancelled);
		assertEquals(DestinationSocket.Status.OFF, socket.status());
	}
}
