package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/**
 * Every destination the socket delivers with an id the site can match ends in one {@code ack} frame on that
 * socket, carrying what became of it: {@code routed}, {@code no_pathing_plugin}, {@code expired} or
 * {@code invalid}. A routed one also goes to the pathing plugin, with a chat line. Wired as the plugin wires it.
 */
public class DestinationReceiverTest
{
	private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "abcdefghijabcdefghijabcdefghijabcdefghijabc";

	private final List<PluginMessage> posted = new ArrayList<>();
	private final List<String> chat = new ArrayList<>();
	private final List<String> sent = new ArrayList<>();
	private boolean shortestPathOn = true;
	private DestinationSocket.Events events;
	private DestinationSocket.Connection connection;
	private DestinationReceiver receiver;

	private final DestinationSocket socket = new DestinationSocket(
		(token, events) ->
		{
			this.events = events;
			connection = new DestinationSocket.Connection()
			{
				@Override
				public boolean send(String text)
				{
					return sent.add(text);
				}

				@Override
				public void close(int code, String reason)
				{
				}
			};
			return connection;
		},
		(task, delay) -> () -> { },
		new Random(1),
		destination -> receiver.accept(destination),
		status -> { });

	private void listen()
	{
		RouteDispatcher dispatcher = new RouteDispatcher(posted::add,
			plugin -> plugin == PathingPlugin.SHORTEST_PATH && shortestPathOn, Clock.fixed(NOW, ZoneOffset.UTC));
		// As the plugin wires it: always Shortest Path, never Farm Route Planner.
		receiver = new DestinationReceiver(dispatcher, () -> RouteWith.SHORTEST_PATH, chat::add, socket::ack);
		socket.update(true, TOKEN, true, true);
		events.opened(connection, DestinationSocket.SUBPROTOCOL);
		events.text(connection, "{\"type\":\"hello\",\"protocol\":1}");
	}

	private void deliver(String id, String label)
	{
		events.text(connection, "{\"type\":\"destination\",\"id\":\"" + id + "\",\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"" + label + "\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
	}

	private static String ack(String id, String result)
	{
		return "{\"type\":\"ack\",\"id\":\"" + id + "\",\"result\":\"" + result + "\"}";
	}

	@Test
	public void aRoutedDestinationGetsTheChatLineAndIsAckedRouted()
	{
		listen();
		deliver("d-1", "Sarah");
		deliver("d-2", "Tile 3100, 3650, 0");

		assertEquals(2, posted.size());
		assertEquals("shortestpath", posted.get(0).getNamespace());
		assertEquals("shortestpath", posted.get(1).getNamespace());
		assertEquals("path", posted.get(0).getName());
		assertEquals(new WorldPoint(3036, 3294, 0), posted.get(0).getData().get("target"));
		assertEquals("osrsindex.com: routing to Sarah (3036, 3294, 0) with Shortest Path.", chat.get(0));
		assertEquals(Arrays.asList(ack("d-1", "routed"), ack("d-2", "routed")), sent);
		assertEquals(2, chat.size());
	}

	@Test
	public void aRepeatedIdIsAckedRoutedAgainButRoutedOnce()
	{
		listen();
		deliver("d-1", "Sarah");
		deliver("d-1", "Sarah");
		assertEquals(1, posted.size());
		assertEquals(1, chat.size());
		assertEquals(Arrays.asList(ack("d-1", "routed"), ack("d-1", "routed")), sent);
	}

	@Test
	public void withShortestPathOffThePlayerIsToldAndItIsAckedNoPathingPlugin()
	{
		shortestPathOn = false;
		listen();
		deliver("d-1", "Sarah");
		assertTrue(posted.isEmpty());
		assertEquals(1, chat.size());
		assertEquals("osrsindex.com sent Sarah (3036, 3294, 0), but Shortest Path is not on.", chat.get(0));
		assertEquals(Collections.singletonList(ack("d-1", "no_pathing_plugin")), sent);
	}

	@Test
	public void onceShortestPathIsOnARedeliveredPlaceRoutesAndIsAckedRouted()
	{
		shortestPathOn = false;
		listen();
		deliver("d-1", "Sarah");
		shortestPathOn = true;
		deliver("d-1", "Sarah");
		assertEquals(1, posted.size());
		assertEquals(Arrays.asList(ack("d-1", "no_pathing_plugin"), ack("d-1", "routed")), sent);
	}

	@Test
	public void anExpiredPlaceIsAckedExpiredWithoutARouteOrAChatLine()
	{
		listen();
		// 31 s past expiry by the client's clock: one second beyond the allowed skew.
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-1\",\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T11:59:29Z\"}");
		assertTrue(posted.isEmpty());
		assertTrue(chat.isEmpty());
		assertEquals(Collections.singletonList(ack("d-1", "expired")), sent);
	}

	@Test
	public void anOutOfRangePlaceIsAckedInvalidWithoutARouteOrAChatLine()
	{
		listen();
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-1\",\"x\":16384,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-2\",\"x\":3036,\"y\":3294,\"plane\":4,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		assertTrue(posted.isEmpty());
		assertTrue(chat.isEmpty());
		assertEquals(Arrays.asList(ack("d-1", "invalid"), ack("d-2", "invalid")), sent);
	}

	@Test
	public void aPlaceThisVersionCannotReadIsAckedInvalid()
	{
		listen();
		// A coordinate as text, a fractional one, an unreadable time, and no label: each still has an id.
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-1\",\"x\":\"3036\",\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-2\",\"x\":1.5,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-3\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"yesterday\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"d-4\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		assertTrue(posted.isEmpty());
		assertTrue(chat.isEmpty());
		assertEquals(Arrays.asList(ack("d-1", "invalid"), ack("d-2", "invalid"), ack("d-3", "invalid"),
			ack("d-4", "invalid")), sent);
	}

	@Test
	public void aPlaceWithNoIdTheSiteCanMatchIsDroppedUnacked()
	{
		listen();
		String tooLong = repeat('a', RouteDispatcher.MAX_ID_LENGTH + 1);
		events.text(connection, "{\"type\":\"destination\",\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":7,\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"Sarah\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		deliver("", "Sarah");
		deliver(tooLong, "Sarah");
		assertTrue(posted.isEmpty());
		assertTrue(chat.isEmpty());
		assertTrue(sent.isEmpty());

		// 64 characters is the longest id the site matches.
		String longest = repeat('a', RouteDispatcher.MAX_ID_LENGTH);
		deliver(longest, "Sarah");
		assertEquals(Collections.singletonList(ack(longest, "routed")), sent);
	}

	@Test
	public void eachOutcomeIsAckedOnceAndEveryAckFitsTheSitesFrameLimit()
	{
		listen();
		String id = repeat('a', RouteDispatcher.MAX_ID_LENGTH);
		deliver(id, "Sarah");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"e\",\"x\":1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T11:00:00Z\"}");
		events.text(connection, "{\"type\":\"destination\",\"id\":\"i\",\"x\":-1,\"y\":1,\"plane\":0,"
			+ "\"label\":\"a\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
		shortestPathOn = false;
		deliver("n", "Sarah");

		assertEquals(Arrays.asList(ack(id, "routed"), ack("e", "expired"), ack("i", "invalid"),
			ack("n", "no_pathing_plugin")), sent);
		for (String frame : sent)
		{
			assertTrue(frame.getBytes(StandardCharsets.UTF_8).length <= 1_024);
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

	@Test
	public void theLabelIsEscapedForChat()
	{
		listen();
		deliver("d-1", "<col=ff0000>Bank");
		assertTrue(chat.get(0).contains("<lt>col=ff0000<gt>Bank"));
	}
}
