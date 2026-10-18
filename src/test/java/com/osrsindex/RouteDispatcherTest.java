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
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);

		assertEquals(RouteDispatcher.Kind.ROUTED, result.kind);
		assertEquals(PathingPlugin.SHORTEST_PATH, result.plugin);
		assertEquals("a", result.ackId);
		assertEquals("routed", result.kind.ackResult);
		assertEquals(1, posted.size());
		PluginMessage message = posted.get(0);
		assertEquals("shortestpath", message.getNamespace());
		assertEquals("path", message.getName());
		assertEquals(new WorldPoint(3036, 3294, 0), message.getData().get("target"));
		// No start: the route begins at the player.
		assertEquals(1, message.getData().size());
		assertEquals("osrsindex.com: routing to Sarah (3036, 3294, 0) with Shortest Path.",
			result.chatMessage);
	}

	@Test
	public void theEdgesOfTheMapAreValid()
	{
		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("low", 0, 0, 0, "Corner", LATER), RouteWith.SHORTEST_PATH).kind);
		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("high", 16_383, 16_383, 3, "Corner", LATER),
				RouteWith.SHORTEST_PATH).kind);
	}

	@Test
	public void anOutOfRangeTileIsAckedInvalidWithoutAMessage()
	{
		int[][] tiles = {{-1, 0, 0}, {16_384, 0, 0}, {0, -1, 0}, {0, 16_384, 0}, {0, 0, -1}, {0, 0, 4}};
		for (int[] tile : tiles)
		{
			RouteDispatcher.Result result = dispatcher.dispatch(
				new Destination("t", tile[0], tile[1], tile[2], "Bad", LATER), RouteWith.SHORTEST_PATH);
			assertEquals(RouteDispatcher.Kind.INVALID, result.kind);
			assertEquals("t", result.ackId);
			assertEquals("invalid", result.kind.ackResult);
			assertNull(result.chatMessage);
		}
		assertTrue(posted.isEmpty());
	}

	@Test
	public void aMissingOrOversizedLabelOrExpiryIsAckedInvalid()
	{
		String longLabel = new String(new char[RouteDispatcher.MAX_LABEL_CODE_POINTS + 1]).replace('\0', 'x');
		Destination[] bad = {
			new Destination("a", 1, 1, 0, null, LATER),
			new Destination("a", 1, 1, 0, "", LATER),
			new Destination("a", 1, 1, 0, longLabel, LATER),
			new Destination("a", 1, 1, 0, "Sarah", null),
		};
		for (Destination destination : bad)
		{
			RouteDispatcher.Result result = dispatcher.dispatch(destination, RouteWith.SHORTEST_PATH);
			assertEquals(RouteDispatcher.Kind.INVALID, result.kind);
			assertEquals("a", result.ackId);
		}
		assertTrue(posted.isEmpty());
	}

	@Test
	public void aPlaceWithNoIdTheSiteCanMatchIsDroppedUnacked()
	{
		String longId = new String(new char[RouteDispatcher.MAX_ID_LENGTH + 1]).replace('\0', 'a');
		Destination[] bad = {
			null,
			new Destination(null, 1, 1, 0, "Sarah", LATER),
			new Destination("", 1, 1, 0, "Sarah", LATER),
			new Destination(longId, 1, 1, 0, "Sarah", LATER),
			// Out of range as well: still no id to ack it by.
			new Destination(longId, 16_384, 1, 0, "Sarah", LATER),
		};
		for (Destination destination : bad)
		{
			RouteDispatcher.Result result = dispatcher.dispatch(destination, RouteWith.SHORTEST_PATH);
			assertEquals(RouteDispatcher.Kind.INVALID, result.kind);
			assertNull(result.ackId);
		}
		assertTrue(posted.isEmpty());

		String longest = longId.substring(1);
		assertEquals(longest, dispatcher.dispatch(new Destination(longest, 16_384, 1, 0, "Sarah", LATER),
			RouteWith.SHORTEST_PATH).ackId);
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
			dispatcher.dispatch(new Destination("e", 1, 1, 0, label.toString(), LATER), RouteWith.SHORTEST_PATH).kind);
	}

	@Test
	public void expiryAllowsThirtySecondsOfClockSkewAndNoMore()
	{
		Instant justInside = NOW.minus(RouteDispatcher.CLOCK_SKEW);
		Instant justOutside = justInside.minusSeconds(1);

		assertEquals(RouteDispatcher.Kind.ROUTED,
			dispatcher.dispatch(new Destination("in", 1, 1, 0, "In", justInside), RouteWith.SHORTEST_PATH).kind);
		RouteDispatcher.Result expired = dispatcher.dispatch(
			new Destination("out", 1, 1, 0, "Out", justOutside), RouteWith.SHORTEST_PATH);
		assertEquals(RouteDispatcher.Kind.EXPIRED, expired.kind);
		assertEquals("out", expired.ackId);
		assertEquals("expired", expired.kind.ackResult);
		assertNull(expired.chatMessage);
		assertEquals(1, posted.size());
	}

	@Test
	public void aRepeatedIdIsRoutedOnceButAckedRoutedAgain()
	{
		dispatcher.dispatch(sarah("same"), RouteWith.SHORTEST_PATH);
		RouteDispatcher.Result again = dispatcher.dispatch(sarah("same"), RouteWith.SHORTEST_PATH);

		assertEquals(RouteDispatcher.Kind.ALREADY_ROUTED, again.kind);
		assertEquals("same", again.ackId);
		assertEquals("routed", again.kind.ackResult);
		assertNull(again.chatMessage);
		assertEquals(1, posted.size());
	}

	@Test
	public void aRoutedIdIsAckedRoutedAgainEvenPastItsExpiry()
	{
		dispatcher.dispatch(sarah("late"), RouteWith.SHORTEST_PATH);
		// The routed check comes before expiry: a place routed once is never reported to the site as expired.
		RouteDispatcher.Result again = dispatcher.dispatch(
			new Destination("late", 3036, 3294, 0, "Sarah", NOW.minusSeconds(3_600)), RouteWith.SHORTEST_PATH);
		assertEquals(RouteDispatcher.Kind.ALREADY_ROUTED, again.kind);
		assertEquals("late", again.ackId);
		assertEquals(1, posted.size());
	}

	@Test
	public void eachKindAcksWithTheSiteContractsResult()
	{
		assertEquals("routed", RouteDispatcher.Kind.ROUTED.ackResult);
		assertEquals("routed", RouteDispatcher.Kind.ALREADY_ROUTED.ackResult);
		assertEquals("invalid", RouteDispatcher.Kind.INVALID.ackResult);
		assertEquals("expired", RouteDispatcher.Kind.EXPIRED.ackResult);
		assertEquals("no_pathing_plugin", RouteDispatcher.Kind.NO_PATHING_PLUGIN.ackResult);
	}

	@Test
	public void aPlaceThatFoundNoPluginIsNotRememberedSoARedeliveryRoutes()
	{
		active.clear();
		dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);
		active.add(PathingPlugin.SHORTEST_PATH);

		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);
		assertEquals(RouteDispatcher.Kind.ROUTED, result.kind);
		assertEquals(PathingPlugin.SHORTEST_PATH, result.plugin);
	}

	@Test
	public void withShortestPathOffItSaysSoAndAcksNoPathingPlugin()
	{
		active.remove(PathingPlugin.SHORTEST_PATH);
		RouteDispatcher.Result result = dispatcher.dispatch(sarah("a"), RouteWith.SHORTEST_PATH);

		assertEquals(RouteDispatcher.Kind.NO_PATHING_PLUGIN, result.kind);
		assertEquals("a", result.ackId);
		assertEquals("no_pathing_plugin", result.kind.ackResult);
		assertTrue(posted.isEmpty());
		assertEquals("osrsindex.com sent Sarah (3036, 3294, 0), but Shortest Path is not on.",
			result.chatMessage);
	}

	@Test
	public void theLabelCannotCarryChatMarkupOrControlCharacters()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(
			new Destination("m", 1, 1, 0, "<col=ff0000>Sa\u0007rah</col>@red@", LATER), RouteWith.SHORTEST_PATH);

		assertFalse(result.chatMessage.contains("<col"));
		assertFalse(result.chatMessage.contains("\u0007"));
		assertTrue(result.chatMessage.startsWith(
			"osrsindex.com: routing to <lt>col=ff0000<gt>Sarah<lt>/col<gt><at>red<at> (1, 1, 0)"));
	}

	@Test
	public void aLabelOfOnlyControlCharactersReadsAsATile()
	{
		RouteDispatcher.Result result = dispatcher.dispatch(
			new Destination("c", 1, 2, 0, "\u0007\u0008", LATER), RouteWith.SHORTEST_PATH);
		assertEquals("osrsindex.com: routing to a tile (1, 2, 0) with Shortest Path.", result.chatMessage);
	}

	@Test
	public void rememberedIdsAreBoundedSoTheOldestIsForgotten()
	{
		for (int i = 0; i <= RouteDispatcher.REMEMBERED_IDS; i++)
		{
			dispatcher.dispatch(sarah("id-" + i), RouteWith.SHORTEST_PATH);
		}
		// id-0 was evicted by the 65th; id-64 is still remembered.
		assertEquals(RouteDispatcher.Kind.ROUTED, dispatcher.dispatch(sarah("id-0"), RouteWith.SHORTEST_PATH).kind);
		assertEquals(RouteDispatcher.Kind.ALREADY_ROUTED,
			dispatcher.dispatch(sarah("id-" + RouteDispatcher.REMEMBERED_IDS), RouteWith.SHORTEST_PATH).kind);
	}
}
