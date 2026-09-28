package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalTime;
import java.util.EnumSet;
import net.runelite.api.MenuAction;
import org.junit.Test;

/**
 * Where the panel's buttons and the right-click entry send the browser, and what the panel says
 * about the last sync.
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
	public void aSearchEscapesTheName()
	{
		assertEquals("https://osrsindex.com/search?q=Dragon%20scimitar", Endpoints.search("Dragon scimitar"));
		assertEquals("https://osrsindex.com/search?q=Black%20d%27hide%20body", Endpoints.search("Black d'hide body"));
		assertEquals("https://osrsindex.com/search?q=Fish%20%26%20chips%3F%23x", Endpoints.search("Fish & chips?#x"));
	}

	@Test
	public void onlyAnItemOrNpcExamineGetsTheEntry()
	{
		assertEquals(SiteLookup.Kind.NPC, SiteLookup.kindOf("Examine", MenuAction.EXAMINE_NPC, -1));
		assertEquals(SiteLookup.Kind.ITEM, SiteLookup.kindOf("Examine", MenuAction.EXAMINE_ITEM_GROUND, -1));
		assertEquals(SiteLookup.Kind.ITEM, SiteLookup.kindOf("Examine", MenuAction.CC_OP, 4151));
		assertEquals(SiteLookup.Kind.ITEM, SiteLookup.kindOf("Examine", MenuAction.CC_OP_LOW_PRIORITY, 4151));
		// A widget's Examine with no item, another option, and objects get nothing.
		assertEquals(SiteLookup.Kind.NONE, SiteLookup.kindOf("Examine", MenuAction.CC_OP, -1));
		assertEquals(SiteLookup.Kind.NONE, SiteLookup.kindOf("Wield", MenuAction.CC_OP, 4151));
		assertEquals(SiteLookup.Kind.NONE, SiteLookup.kindOf("Examine", MenuAction.EXAMINE_OBJECT, -1));
		assertEquals(SiteLookup.Kind.NONE, SiteLookup.kindOf(null, MenuAction.EXAMINE_NPC, -1));
	}

	@Test
	public void aNameLosesItsTagsLevelAndHardSpaces()
	{
		assertEquals("Goblin", SiteLookup.cleanName("<col=ffff00>Goblin<col=ff00>  (level-2)"));
		assertEquals("Abyssal whip", SiteLookup.cleanName("Abyssal whip"));
		assertEquals("Tzhaar-Ket", SiteLookup.cleanName("Tzhaar-Ket"));
		assertNull(SiteLookup.cleanName("null"));
		assertNull(SiteLookup.cleanName("<col=ff9040></col>"));
		assertNull(SiteLookup.cleanName(null));
	}

	@Test
	public void theLookupSettingDefaultsToOn()
	{
		assertTrue(new OsrsIndexConfig()
		{
		}.lookupMenu());
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
	public void aCharacterNameIsTextInThePanel()
	{
		assertEquals("A&lt;b&gt;&amp;c", OsrsIndexPanel.escape("A<b>&c"));
	}
}
