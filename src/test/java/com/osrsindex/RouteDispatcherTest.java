package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

public class RouteDispatcherTest
{
	private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
	private static final Instant LATER = NOW.plusSeconds(120);

	private final List<PluginMessage> posted = new ArrayList<>();
	private final Set<PathingPlugin> active = EnumSet.allOf(PathingPlugin.class);
	private final RouteDispatcher dispatcher = new RouteDispatcher(
		posted::add, active::contains, Clock.fixed(NOW, ZoneOffset.UTC));

	private static Destination sarah(String id)
	{
		return new Destination(id, 3036, 3294, 0, "Sarah", LATER);
	}

	@Test
	public void aValidDestinationPostsExactlyOnePathMessageWithAWorldPointTarget()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.AUTO);

		assertEquals(RouteDispatcher.Kind.ROUTED, result.kind);
		assertEquals(PathingPlugin.FARM_ROUTE_PLANNER, result.plugin);
		assertEquals("a", result.ackId);
		assertEquals(1, posted.size());
		PluginMessage message = posted.get(0);
		assertEquals("farmrouteplanner", message.getNamespace());
		assertEquals("path", message.getName());
		assertEquals(new WorldPoint(3036, 3294, 0), message.getData().get("target"));
		// No start: the route begins at the player.
		assertEquals(1, message.getData().size());
		assertEquals("osrsindex.com: routing to Sarah (3036, 3294, 0) with Farm Route Planner.",
			result.chatMessage);
	}

	@Test
	public void shortestPathGetsTheSameMessageOnItsOwnNamespace()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);

		assertEquals(RouteDispatcher.Kind.ROUTED, result.kind);
		assertEquals("shortestpath", posted.get(0).getNamespace());
		assertEquals("path", posted.get(0).getName());
		assertEquals(new WorldPoint(3036, 3294, 0), posted.get(0).getData().get("target"));
		assertTrue(result.chatMessage.endsWith(" with Shortest Path."));
	}

	@Test
	public void theEdgesOfTheMapAreValid()
	{
		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("low", 0, 0, 0, "Corner", LATER), RouteWith.AUTO).kind);
		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("high", 16_383, 16_383, 3, "Corner", LATER), RouteWith.AUTO).kind);
	}

	@Test
	public void anOutOfRangeTileIsDroppedWithoutAMessageOrAnAck()
	{
		int[][] tiles = {{-1, 0, 0}, {16_384, 0, 0}, {0, -1, 0}, {0, 16_384, 0}, {0, 0, -1}, {0, 0, 4}};
		for (int[] tile : tiles)
		{
			RouteDispatcher.Result result = dispatcher.dispatch(
				new Destination("t", tile[0], tile[1], tile[2], "Bad", LATER), RouteWith.AUTO);
			assertEquals(RouteDispatcher.Kind.INVALID, result.kind);
			assertNull(result.ackId);
			assertNull(result.chatMessage);
		}
		assertTrue(posted.isEmpty());
	}

	@Test
	public void aMissingOrOversizedIdOrLabelIsDropped()
	{
		String longId = new String(new char[RouteDispatcher.MAX_ID_LENGTH + 1]).replace('\0', 'a');
		String longLabel = new String(new char[RouteDispatcher.MAX_LABEL_CODE_POINTS + 1]).replace('\0', 'x');
		Destination[] bad = {
			null,
			new Destination(null, 1, 1, 0, "Sarah", LATER),
			new Destination("", 1, 1, 0, "Sarah", LATER),
			new Destination(longId, 1, 1, 0, "Sarah", LATER),
			new Destination("a", 1, 1, 0, null, LATER),
			new Destination("a", 1, 1, 0, "", LATER),
			new Destination("a", 1, 1, 0, longLabel, LATER),
			new Destination("a", 1, 1, 0, "Sarah", null),
		};
		for (Destination destination : bad)
		{
			assertEquals(RouteDispatcher.Kind.INVALID, dispatcher.dispatch(destination, RouteWith.AUTO).kind);
		}
		assertTrue(posted.isEmpty());
	}

	@Test
	public void theLabelLimitCountsCodePointsNotUtf16Units()
	{
		// 100 emoji are 200 UTF-16 units, and still within the site's 100 code points.
		String emoji = new String(Character.toChars(0x1F955));
		StringBuilder label = new StringBuilder();
		for (int i = 0; i < RouteDispatcher.MAX_LABEL_CODE_POINTS; i++)
		{
			label.append(emoji);
		}
		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("e", 1, 1, 0, label.toString(), LATER), RouteWith.AUTO).kind);
	}

	@Test
	public void expiryAllowsThirtySecondsOfClockSkewAndNoMore()
	{
		Instant justInside = NOW.minus(RouteDispatcher.CLOCK_SKEW);
		Instant justOutside = justInside.minusSeconds(1);

		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("in", 1, 1, 0, "In", justInside), RouteWith.AUTO).kind);
		RouteDispatcher.Result expired = dispatcher.dispatch(
			new Destination("out", 1, 1, 0, "Out", justOutside), RouteWith.AUTO);
		assertEquals(RouteDispatcher.Kind.EXPIRED, expired.kind);
		assertNull(expired.ackId);
		assertEquals(1, posted.size());
	}

	@Test
	public void aRepeatedIdIsRoutedOnceButAckedAgain()
	{
		dispatcher.dispatch(sarah("same"), RouteWith.AUTO);
		RouteDispatcher.Result again = dispatcher.dispatch(sarah("same"), RouteWith.AUTO);

		assertEquals(RouteDispatcher.Kind.ALREADY_ROUTED, again.kind);
		assertEquals("same", again.ackId);
		assertNull(again.chatMessage);
		assertEquals(1, posted.size());
	}

	@Test
	public void withNoPathingPluginOnItSaysSoAndDoesNotAck()
	{
		active.clear();
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.AUTO);

		assertEquals(RouteDispatcher.Kind.NO_PATHING_PLUGIN, result.kind);
		assertNull(result.ackId);
		assertTrue(posted.isEmpty());
		assertEquals("osrsindex.com sent Sarah (3036, 3294, 0), but no pathing plugin is on. Turn on"
			+ " Farm Route Planner or Shortest Path.", result.chatMessage);
	}

	@Test
	public void aPlaceThatFoundNoPluginIsNotRememberedSoARedeliveryRoutes()
	{
		active.clear();
		dispatcher.dispatch(sarah("a"), RouteWith.AUTO);
		active.add(PathingPlugin.SHORTEST_PATH);

		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.AUTO);
		assertEquals(RouteDispatcher.Kind.ROUTED, result.kind);
		assertEquals(PathingPlugin.SHORTEST_PATH, result.plugin);
	}

	@Test
	public void anExplicitChoiceThatIsOffNamesItAndDoesNotFallBack()
	{
		active.remove(PathingPlugin.SHORTEST_PATH);
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);

		assertEquals(RouteDispatcher.Kind.NO_PATHING_PLUGIN, result.kind);
		assertTrue(posted.isEmpty());
		assertEquals("osrsindex.com sent Sarah (3036, 3294, 0), but Shortest Path is not on.",
			result.chatMessage);
	}

	@Test
	public void theLabelCannotCarryChatMarkupOrControlCharacters()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(
			new Destination("m", 1, 1, 0, "<col=ff0000>Sa\u0007rah</col>@red@", LATER), RouteWith.AUTO);

		assertFalse(result.chatMessage.contains("<col"));
		assertFalse(result.chatMessage.contains("\u0007"));
		assertTrue(result.chatMessage.startsWith(
			"osrsindex.com: routing to <lt>col=ff0000<gt>Sarah<lt>/col<gt><at>red<at> (1, 1, 0)"));
	}

	@Test
	public void aLabelOfOnlyControlCharactersReadsAsATile()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(
			new Destination("c", 1, 2, 0, "\u0007\u0008", LATER), RouteWith.AUTO);
		assertEquals("osrsindex.com: routing to a tile (1, 2, 0) with Farm Route Planner.", result.chatMessage);
	}

	@Test
	public void rememberedIdsAreBoundedSoTheOldestIsForgotten()
	{
		for (int i = 0; i <= RouteDispatcher.REMEMBERED_IDS; i++)
		{
			dispatcher.dispatch(sarah("id-" + i), RouteWith.AUTO);
		}
		// id-0 was evicted by the 65th; id-64 is still remembered.
		assertEquals(RouteDispatcher.Kind.ROUTED, dispatcher.dispatch(sarah("id-0"), RouteWith.AUTO).kind);
		assertEquals(RouteDispatcher.Kind.ALREADY_ROUTED,
			dispatcher.dispatch(sarah("id-" + RouteDispatcher.REMEMBERED_IDS), RouteWith.AUTO).kind);
	}
}
