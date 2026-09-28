package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/**
 * Every destination the socket delivers ends in a chat line and an {@code ack}
 * frame on that socket, and the route goes to the pathing plugin. Wired as the plugin wires it.
 */
public class DestinationReceiverTest
{
	private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "abcdefghijabcdefghijabcdefghijabcdefghijabc";

	private final List<PluginMessage> posted = new ArrayList<>();
	private final List<String> chat = new ArrayList<>();
	private final List<String> sent = new ArrayList<>();
	private boolean shortestPathOn = true;
	private boolean farmOn = true;
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
			plugin -> plugin == PathingPlugin.SHORTEST_PATH ? shortestPathOn : farmOn, Clock.fixed(NOW, ZoneOffset.UTC));
		// As the plugin wires it: always Shortest Path, never Farm Route Planner.
		receiver = new DestinationReceiver(dispatcher, () -> RouteWith.SHORTEST_PATH, chat::add, socket::ack);
		socket.update(true, TOKEN, true, true);
		events.opened(connection, DestinationSocket.SUBPROTOCOL);
	}

	private void deliver(String id, String label)
	{
		events.text(connection, "{\"type\":\"destination\",\"id\":\"" + id + "\",\"x\":3036,\"y\":3294,\"plane\":0,"
			+ "\"label\":\"" + label + "\",\"expires_at\":\"2026-09-26T12:02:00Z\"}");
	}

	@Test
	public void everyDestinationGetsTheChatLineAndAnAck()
	{
		listen();
		deliver("d-1", "Sarah");
		deliver("d-2", "Tile 3100, 3650, 0");

		assertEquals(2, posted.size());
		// Farm Route Planner is on too, and still gets nothing.
		assertEquals("shortestpath", posted.get(0).getNamespace());
		assertEquals("shortestpath", posted.get(1).getNamespace());
		assertEquals("path", posted.get(0).getName());
		assertEquals(new WorldPoint(3036, 3294, 0), posted.get(0).getData().get("target"));
		assertEquals("osrsindex.com: routing to Sarah (3036, 3294, 0) with Shortest Path.", chat.get(0));
		assertEquals("{\"type\":\"ack\",\"id\":\"d-1\"}", sent.get(0));
		assertEquals("{\"type\":\"ack\",\"id\":\"d-2\"}", sent.get(1));
		assertEquals(2, chat.size());
	}

	@Test
	public void aRepeatedIdIsAckedAgainButRoutedOnce()
	{
		listen();
		deliver("d-1", "Sarah");
		deliver("d-1", "Sarah");
		assertEquals(1, posted.size());
		assertEquals(1, chat.size());
		assertEquals(2, sent.size());
	}

	@Test
	public void withShortestPathOffThePlayerIsToldAndNothingIsAcked()
	{
		shortestPathOn = false;
		listen();
		deliver("d-1", "Sarah");
		assertTrue(posted.isEmpty());
		assertEquals(1, chat.size());
		assertEquals("osrsindex.com sent Sarah (3036, 3294, 0), but Shortest Path is not on.", chat.get(0));
		assertTrue(sent.isEmpty());
	}

	@Test
	public void theLabelIsEscapedForChat()
	{
		listen();
		deliver("d-1", "<col=ff0000>Bank");
		assertTrue(chat.get(0).contains("<lt>col=ff0000<gt>Bank"));
	}
}
