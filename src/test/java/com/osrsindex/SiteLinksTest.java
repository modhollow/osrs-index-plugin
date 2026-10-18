package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.EnumSet;
import org.junit.Test;

/**
 * Where the panel's buttons send the browser, and what the panel says about the last sync.
 */
public class SiteLinksTest
{
	@Test
	public void theSitesPagesAreOnItsOwnOrigin()
	{
		assertEquals("https://osrsindex.com/tracker", Endpoints.tracker());
		assertEquals("https://osrsindex.com/account#runelite", Endpoints.account());
		assertEquals("https://osrsindex.com/map?x=3222&y=3218&plane=0", Endpoints.map(3222, 3218, 0));
		assertEquals("https://osrsindex.com/map", Endpoints.map());
		assertEquals("https://osrsindex.com/link?code=KXQ4-7MNP", Endpoints.linkApproval("KXQ4-7MNP"));
	}

	@Test
	public void myCharacterOpensTheLoggedInCharacter()
	{
		String tracker = "https://osrsindex.com/tracker";
		assertEquals(tracker + "?character=Zezima", Endpoints.tracker("Zezima"));
		assertEquals(tracker + "?character=Iron%20Man", Endpoints.tracker("Iron Man"));
		assertEquals(tracker + "?character=Lil_Jim-2", Endpoints.tracker("Lil_Jim-2"));
	}

	@Test
	public void aHardSpaceInTheNameIsSentAsTheSpaceTheSiteStores()
	{
		// The game writes the space in a player's name as U+00A0; the site stores a plain space.
		assertEquals("https://osrsindex.com/tracker?character=Iron%20Man", Endpoints.tracker("Iron\u00A0Man"));
		assertEquals("https://osrsindex.com/tracker?character=A%20B%20C", Endpoints.tracker("\u00A0A\u00A0B C\u00A0"));
	}

	@Test
	public void withNoCharacterMyCharacterOpensThePlainPage()
	{
		assertEquals("https://osrsindex.com/tracker", Endpoints.tracker(null));
		assertEquals("https://osrsindex.com/tracker", Endpoints.tracker(""));
		assertEquals("https://osrsindex.com/tracker", Endpoints.tracker(" \u00A0"));
	}

	@Test
	public void theLastSyncLineListsWhatWasStoredInTheContractsOrder()
	{
		LocalTime at = LocalTime.of(14, 2);
		assertEquals("Last sync at 14:02: location, skills and bank.",
			OsrsIndexPlugin.syncSummary(at, EnumSet.of(Part.BANK, Part.SKILLS, Part.LOCATION)));
		assertEquals("Last sync at 14:02: combat achievements.",
			OsrsIndexPlugin.syncSummary(at, EnumSet.of(Part.COMBAT_ACHIEVEMENTS)));
		assertEquals("Last sync at 14:02.", OsrsIndexPlugin.syncSummary(at, EnumSet.noneOf(Part.class)));
	}

	@Test
	public void anUnlinkedClientIsToldHowToLinkAndThatNothingIsSent()
	{
		assertTrue(OsrsIndexPlugin.LINK_HINT.contains("nothing is sent"));
		assertTrue(OsrsIndexPlugin.LINK_HINT.contains("Link with osrsindex.com"));
	}

	@Test
	public void aWaitTheSiteExplainedSaysWhenAndWhy()
	{
		assertEquals("Not synced at 14:02: Back at 19:00 UTC.",
			OsrsIndexPlugin.waitLine(LocalTime.of(14, 2), "Back at 19:00 UTC."));
	}

	@Test
	public void thePanelSaysWhenTheSiteHasPausedDestinations()
	{
		assertEquals("Not listening: osrsindex.com has paused destinations for now.",
			OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.OFF, true, true, true, true));
		assertEquals("the player's own setting is named first",
			"Not listening: \"Receive destinations\" is off in this plugin's settings.",
			OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.OFF, false, true, true, true));
		assertEquals("Not listening.",
			OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.OFF, true, true, true, false));
	}

	@Test
	public void aCharacterNameIsTextInThePanel()
	{
		assertEquals("A&lt;b&gt;&amp;c", OsrsIndexPanel.escape("A<b>&c"));
	}
}
