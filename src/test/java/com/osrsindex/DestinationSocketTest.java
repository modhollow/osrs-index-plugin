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
	/** The relay's first frame on every socket. */
	private static final String HELLO = "{\"type\":\"hello\",\"protocol\":1}";

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
	public void theSiteConfigSetsTheBackoffsFirstAndLongestWait()
	{
		Random random = new Random(2);
		long[] bases = {30_000, 60_000, 120_000, 120_000};
		for (int failures = 0; failures < bases.length; failures++)
		{
			long delay = DestinationSocket.retryDelayMillis(failures, -1, random, 30_000, 120_000);
			assertTrue(delay >= Math.round(bases[failures] * 0.8));
			assertTrue(delay <= Math.round(bases[failures] * 1.2));
		}
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
		// The relay's hello, not the upgrade alone, makes an open successful.
		events.text(last(), HELLO);
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
		events.text(current, HELLO);

		events.closed(stale, DestinationSocket.CLOSE_REVOKED);
		events.text(stale, destinationFrame("stale"));
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
		assertTrue(received.isEmpty());
	}

	// --- the relay's hello ------------------------------------------------------------------------

	/** Open a socket for TOKEN and complete the upgrade, but without the relay's hello yet. */
	private FakeConnection upgraded()
	{
		socket.update(true, TOKEN, true, true);
		FakeConnection connection = last();
		events.opened(connection, DestinationSocket.SUBPROTOCOL);
		return connection;
	}

	private Scheduled lastScheduled()
	{
		return scheduled.get(scheduled.size() - 1);
	}

	@Test
	public void aHelloStartsListeningAndAPlaceRightBehindItIsHandedOn()
	{
		FakeConnection connection = upgraded();
		Scheduled deadline = lastScheduled();
		assertEquals(DestinationSocket.HELLO_TIMEOUT_MILLIS, deadline.delayMillis);
		// The upgrade alone is not listening: the relay has not answered yet.
		assertEquals(DestinationSocket.Status.CONNECTING, socket.status());

		// The site sends a waiting place straight after the hello, so both can arrive together.
		events.text(connection, HELLO);
		events.text(connection, destinationFrame("d-1"));
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
		assertTrue(deadline.cancelled);
		assertEquals(1, received.size());
		assertEquals("d-1", received.get(0).id);

		// A deadline that fires anyway, its cancel having lost the race with the hello, changes nothing.
		deadline.task.run();
		assertNull(connection.closedWith);
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
	}

	@Test
	public void aHelloOfAnotherProtocolIsClosedAndRetried()
	{
		for (String hello : new String[]{"{\"type\":\"hello\",\"protocol\":2}", "{\"type\":\"hello\"}"})
		{
			FakeConnection connection = upgraded();
			Scheduled deadline = lastScheduled();
			events.text(connection, hello);

			assertEquals(hello, Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
			assertEquals(hello, DestinationSocket.Status.RETRYING, socket.status());
			assertTrue(hello, deadline.cancelled);
			assertNotNull(hello, pendingRetry());
			// Whatever the refused socket sends next is not acted on.
			events.text(connection, HELLO);
			events.text(connection, destinationFrame("late"));
			assertEquals(hello, DestinationSocket.Status.RETRYING, socket.status());
			assertTrue(hello, received.isEmpty());

			// Reset for the next frame.
			socket.update(false, TOKEN, true, false);
			scheduled.clear();
		}
		assertTrue(DestinationSocket.isAnyHello("{\"type\":\"hello\",\"protocol\":2}"));
		assertFalse(DestinationSocket.isAnyHello(destinationFrame("d")));
	}

	@Test
	public void noHelloWithin10SecondsIsClosedAndRetried()
	{
		FakeConnection connection = upgraded();
		lastScheduled().task.run();

		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), connection.closedWith);
		assertEquals(DestinationSocket.Status.RETRYING, socket.status());
		Scheduled retry = pendingRetry();
		assertNotNull(retry);
		// A hello that comes too late is not listened to.
		events.text(connection, HELLO);
		assertEquals(DestinationSocket.Status.RETRYING, socket.status());

		// A relay that keeps upgrading without a hello is backed off further each time.
		retry.task.run();
		events.opened(last(), DestinationSocket.SUBPROTOCOL);
		lastScheduled().task.run();
		assertEquals(Integer.valueOf(DestinationSocket.CLOSE_NORMAL), last().closedWith);
		assertTrue(pendingRetry().delayMillis >= 24_000);
	}

	@Test
	public void aPlaceBeforeTheHelloIsNotHandedOn()
	{
		FakeConnection connection = upgraded();
		events.text(connection, destinationFrame("early"));
		events.text(connection, "{\"type\":\"news\"}");
		assertTrue(received.isEmpty());
		assertNull(connection.closedWith);
		assertEquals(DestinationSocket.Status.CONNECTING, socket.status());

		events.text(connection, HELLO);
		assertEquals(DestinationSocket.Status.LISTENING, socket.status());
	}

	@Test
	public void theHelloDeadlineGoesWithItsSocket()
	{
		FakeConnection dropped = upgraded();
		Scheduled deadline = lastScheduled();
		events.closed(dropped, 1006);
		assertTrue(deadline.cancelled);
		Scheduled retry = pendingRetry();

		// Firing late, it neither closes the next socket nor schedules another retry.
		retry.task.run();
		FakeConnection next = last();
		events.opened(next, DestinationSocket.SUBPROTOCOL);
		int before = scheduled.size();
		deadline.task.run();
		assertNull(next.closedWith);
		assertEquals(before, scheduled.size());

		// Logging out before the hello cancels the new socket's deadline too.
		Scheduled nextDeadline = lastScheduled();
		socket.update(true, TOKEN, false, false);
		assertTrue(nextDeadline.cancelled);
		assertEquals(DestinationSocket.Status.OFF, socket.status());
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
	public void unknownFramesAndDestinationsWithoutAStringIdAreIgnored()
	{
		FakeConnection connection = listening();
		events.text(connection, "{\"type\":\"news\",\"id\":\"x\"}");
		events.text(connection, "not json");
		events.text(connection, "[1,2]");
		events.text(connection, "{\"type\":\"destination\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":7,\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		assertTrue(received.isEmpty());
		assertTrue(DestinationSocket.isHello("{\"type\":\"hello\",\"protocol\":1}"));
		assertFalse(DestinationSocket.isHello("{\"type\":\"hello\",\"protocol\":2}"));
	}

	@Test
	public void aMalformedDestinationWithAnIdIsHandedOnAsInvalidSoItCanBeAcked()
	{
		FakeConnection connection = listening();
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":\"3036\",\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1,\"y\":1.5,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"yesterday\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1,\"y\":1,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":7,\"expires_at\":\"2026-09-26T12:02:00Z\"}");

		assertEquals(5, received.size());
		assertEquals(DestinationSocket.UNREADABLE, received.get(0).x);
		assertEquals(DestinationSocket.UNREADABLE, received.get(1).y);
		assertNull(received.get(2).expiresAt);
		assertEquals(DestinationSocket.UNREADABLE, received.get(3).plane);
		assertNull(received.get(4).label);
		for (Destination destination : received)
		{
			assertEquals("d", destination.id);
			assertFalse(RouteDispatcher.valid(destination));
			assertTrue(RouteDispatcher.ackable(destination));
		}
	}

	@Test
	public void anAckIsOneSmallFrameWithItsResultAndOnlyWhileListening()
	{
		assertFalse(socket.ack("d-0", "routed"));
		FakeConnection connection = listening();
		assertTrue(socket.ack("d-1", "routed"));
		assertEquals("{\"type\":\"ack\",\"id\":\"d-1\",\"result\":\"routed\"}", connection.sent.get(0));
		assertTrue(socket.ack(repeat('a', RouteDispatcher.MAX_ID_LENGTH), "no_pathing_plugin"));
		assertTrue(connection.sent.get(1).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1_024);

		socket.update(false, TOKEN, true, false);
		assertFalse(socket.ack("d-2", "routed"));
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
