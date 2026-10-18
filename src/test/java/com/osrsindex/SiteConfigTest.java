package com.osrsindex;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The site config (docs/site-config.md): what the site may change, and that anything it sends out of range, of the
 * wrong type or unknown leaves the plugin on its built-in values rather than breaking it.
 */
public class SiteConfigTest
{
	private static SiteConfig parse(String json)
	{
		return SiteConfig.parse(json);
	}

	@Test
	public void anythingButAJsonObjectIsTheBuiltInConfig()
	{
		for (String body : new String[]{null, "", "not json", "[]", "42", "\"text\"", "<html>bad gateway</html>"})
		{
			assertSame(String.valueOf(body), SiteConfig.DEFAULTS, parse(body));
			assertNull("no answer, so the fetcher keeps what it has", SiteConfig.parseOrNull(body));
		}
	}

	@Test
	public void anEmptyObjectKeepsEveryBuiltInValue()
	{
		SiteConfig config = parse("{}");
		assertEquals(0, config.version);
		assertEquals(SiteConfig.DEFAULT_REFRESH_SECONDS, config.refreshSeconds);
		assertEquals(Part.LATEST_SCHEMA, config.maxSchema);
		assertSame(SiteConfig.Timing.DEFAULTS, config.timing);
		assertTrue(config.paused.isEmpty());
		assertTrue(config.destinations);
		assertSame(SiteConfig.Vars.BUILT_IN, config.vars);
		assertNull(config.notice);
	}

	@Test
	public void theBuiltInTimingIsWhatThePluginDidBefore()
	{
		SiteConfig.Timing timing = SiteConfig.Timing.DEFAULTS;
		assertEquals(31, timing.syncFloorSeconds);
		assertEquals(60, timing.retrySeconds);
		assertEquals(900, timing.accountFullRetrySeconds);
		assertEquals(120_000, timing.budgetBytes);
		assertEquals(5, timing.pollTicks);
		assertEquals(25, timing.pollVarsTicks);
		assertEquals(15_000, timing.relayRetryMillis);
		assertEquals(300_000, timing.relayRetryMaxMillis);
	}

	@Test
	public void theSiteTunesEachTimingWithinItsRange()
	{
		SiteConfig.Timing timing = parse("{\"timing\":{\"sync_floor_seconds\":45,\"retry_seconds\":90,"
			+ "\"account_full_retry_seconds\":1800,\"budget_bytes\":100000,\"poll_ticks\":10,\"poll_vars_ticks\":50,"
			+ "\"relay_retry_seconds\":30,\"relay_retry_max_seconds\":600}}").timing;
		assertEquals(45, timing.syncFloorSeconds);
		assertEquals(90, timing.retrySeconds);
		assertEquals(1_800, timing.accountFullRetrySeconds);
		assertEquals(100_000, timing.budgetBytes);
		assertEquals(10, timing.pollTicks);
		assertEquals(50, timing.pollVarsTicks);
		assertEquals(30_000, timing.relayRetryMillis);
		assertEquals(600_000, timing.relayRetryMaxMillis);
	}

	@Test
	public void timingsOutOfRangeAreClampedSoTheSiteCannotMakeThePluginHammerIt()
	{
		SiteConfig.Timing low = parse("{\"timing\":{\"sync_floor_seconds\":0,\"retry_seconds\":-5,"
			+ "\"account_full_retry_seconds\":1,\"budget_bytes\":10,\"poll_ticks\":0,\"poll_vars_ticks\":1,"
			+ "\"relay_retry_seconds\":0,\"relay_retry_max_seconds\":0}}").timing;
		assertEquals("never under the site's own per-token limit", SyncOutcome.FLOOR_SECONDS, low.syncFloorSeconds);
		assertEquals(30, low.retrySeconds);
		assertEquals(60, low.accountFullRetrySeconds);
		assertEquals(PayloadPacker.MIN_BUDGET_BYTES, low.budgetBytes);
		assertEquals(1, low.pollTicks);
		assertEquals(5, low.pollVarsTicks);
		assertEquals("never quicker than the built-in first reconnect", 15_000, low.relayRetryMillis);
		assertEquals("the back-off always grows to at least a minute", 60_000, low.relayRetryMaxMillis);

		SiteConfig.Timing high = parse("{\"timing\":{\"sync_floor_seconds\":1e12,\"retry_seconds\":99999,"
			+ "\"account_full_retry_seconds\":9999999,\"budget_bytes\":9999999999,\"poll_ticks\":1000,"
			+ "\"poll_vars_ticks\":100000,\"relay_retry_seconds\":100000,\"relay_retry_max_seconds\":100000}}").timing;
		assertEquals(3_600, high.syncFloorSeconds);
		assertEquals(3_600, high.retrySeconds);
		assertEquals(86_400, high.accountFullRetrySeconds);
		assertEquals("under the site's 131,072-byte cap", SiteConfig.MAX_BUDGET_BYTES, high.budgetBytes);
		assertEquals(100, high.pollTicks);
		assertEquals(1_000, high.pollVarsTicks);
		assertEquals(600_000, high.relayRetryMillis);
		assertEquals(3_600_000, high.relayRetryMaxMillis);
	}

	@Test
	public void aFieldOfTheWrongTypeIsIgnored()
	{
		SiteConfig config = parse("{\"version\":\"7\",\"refresh_seconds\":true,\"max_schema\":[3],"
			+ "\"timing\":{\"sync_floor_seconds\":\"60\"},\"destinations\":\"no\",\"parts\":[\"bank\"],"
			+ "\"vars\":\"replace\",\"notice\":\"hello\"}");
		assertEquals(0, config.version);
		assertEquals(SiteConfig.DEFAULT_REFRESH_SECONDS, config.refreshSeconds);
		assertEquals(Part.LATEST_SCHEMA, config.maxSchema);
		assertEquals(31, config.timing.syncFloorSeconds);
		assertTrue(config.destinations);
		assertTrue(config.paused.isEmpty());
		assertSame(SiteConfig.Vars.BUILT_IN, config.vars);
		assertNull(config.notice);
	}

	@Test
	public void refreshAndSchemaStayInRange()
	{
		assertEquals(SiteConfig.MIN_REFRESH_SECONDS, parse("{\"refresh_seconds\":1}").refreshSeconds);
		assertEquals(SiteConfig.MAX_REFRESH_SECONDS, parse("{\"refresh_seconds\":1e9}").refreshSeconds);
		assertEquals(3, parse("{\"max_schema\":3}").maxSchema);
		assertEquals(1, parse("{\"max_schema\":0}").maxSchema);
		assertEquals("never above what this plugin speaks", Part.LATEST_SCHEMA, parse("{\"max_schema\":99}").maxSchema);
		assertEquals(7, parse("{\"version\":7}").version);
	}

	@Test
	public void theSiteCanPausePartsButNeverTurnOneOn()
	{
		SiteConfig config = parse("{\"parts\":{\"bank\":false,\"grand_exchange\":false,\"location\":true,"
			+ "\"no_such_part\":false,\"vars\":\"false\"}}");
		assertEquals(List.of(Part.GRAND_EXCHANGE, Part.BANK), List.copyOf(config.paused));
		assertFalse(config.allows(Part.BANK));
		assertFalse(config.allows(Part.GRAND_EXCHANGE));
		assertTrue("true leaves the player's setting as it is", config.allows(Part.LOCATION));
		assertTrue("only a JSON false pauses", config.allows(Part.VARS));
	}

	@Test
	public void theSiteCanPauseDestinations()
	{
		assertFalse(parse("{\"destinations\":false}").destinations);
		assertTrue(parse("{\"destinations\":true}").destinations);
	}

	// --- vars ----------------------------------------------------------------

	@Test
	public void addKeepsTheBuiltInListAndAppendsTheSitesVars()
	{
		SiteConfig.Vars vars = parse("{\"vars\":{\"varbits\":{\"SOME_NEW_QUEST\":20512},"
			+ "\"varps\":{\"SOME_NEW_MINIGAME_POINTS\":4100}}}").vars;
		assertEquals(GameVars.VARBIT_IDS.length + 1, vars.varbitIds.length);
		assertEquals(GameVars.VARP_IDS.length + 1, vars.varpIds.length);
		assertEquals("SOME_NEW_QUEST", vars.varbitNames[vars.varbitNames.length - 1]);
		assertEquals(20_512, vars.varbitIds[vars.varbitIds.length - 1]);
		assertEquals("SOME_NEW_MINIGAME_POINTS", vars.varpNames[vars.varpNames.length - 1]);
		assertEquals(4_100, vars.varpIds[vars.varpIds.length - 1]);
		assertArrayEquals(GameVars.VARBIT_NAMES, Arrays.copyOf(vars.varbitNames, GameVars.VARBIT_NAMES.length));
	}

	@Test
	public void aNameInBothListsTakesTheSitesId()
	{
		String name = GameVars.VARBIT_NAMES[0];
		SiteConfig.Vars vars = parse("{\"vars\":{\"varbits\":{\"" + name + "\":12345}}}").vars;
		assertEquals(GameVars.VARBIT_IDS.length, vars.varbitIds.length);
		assertEquals(name, vars.varbitNames[0]);
		assertEquals(12_345, vars.varbitIds[0]);
	}

	@Test
	public void replaceSendsOnlyTheSitesVars()
	{
		SiteConfig.Vars vars = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"QUEST_A\":1,\"QUEST_B\":2}}}").vars;
		assertArrayEquals(new String[]{"QUEST_A", "QUEST_B"}, vars.varbitNames);
		assertArrayEquals(new int[]{1, 2}, vars.varbitIds);
		assertEquals(0, vars.varpIds.length);
	}

	@Test
	public void anAddThatListsNothingNewIsTheBuiltInList()
	{
		assertSame(SiteConfig.Vars.BUILT_IN, parse("{\"vars\":{\"mode\":\"add\"}}").vars);
		assertSame(SiteConfig.Vars.BUILT_IN, parse("{\"vars\":{\"varbits\":{\"" + GameVars.VARBIT_NAMES[0] + "\":"
			+ GameVars.VARBIT_IDS[0] + "}}}").vars);
	}

	@Test
	public void namesAndIdsTheSiteMayNotAskForAreRefused()
	{
		SiteConfig.Vars vars = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{"
			+ "\"OK_QUEST\":1,"
			+ "\"SETTINGS_SOUND\":2,\"POH_THRONE\":3,\"LEAGUE_POINTS\":4,\"QUEST_DEBUG\":5,\"BANK_TAB_2\":6,"
			+ "\"lower case ok?\":7,\"\":8,\"" + "A".repeat(81) + "\":9,"
			+ "\"NEGATIVE\":-1,\"TOO_BIG\":65536,\"FRACTION\":1.5,\"TEXT_ID\":\"12\","
			+ "\"OK_TOO\":65535}}}").vars;
		assertArrayEquals(new String[]{"OK_QUEST", "OK_TOO"}, vars.varbitNames);
		assertArrayEquals(new int[]{1, 65_535}, vars.varbitIds);
	}

	@Test
	public void lowerCaseNamesAreRefusedSoTheExclusionsCannotBeSidestepped()
	{
		SiteConfig.Vars vars = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"poh_layout\":30001,"
			+ "\"Settings_Sound\":30002,\"quest_new\":30003}}}").vars;
		assertEquals(0, vars.varbitIds.length);
		assertTrue(SiteConfig.EXCLUDED_VAR.matcher("poh_layout").find());
	}

	@Test
	public void namesTheSyncRefusesOrThatAreKeptForOthersAreRefused()
	{
		SiteConfig.Vars vars = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"1ST_QUEST\":30001,"
			+ "\"__SITE_FLAG\":30002,\"SLAYER_TASK_NAME\":30003,\"SLAYER_TASK_AREA\":30004,\"SLAYER_TASK_NAME_X\":30005,"
			+ "\"_100_PIRATE_QUEST_VAR\":30006}}}").vars;
		assertArrayEquals("a leading underscore is RuneLite's own, and a longer Slayer name is not the plugin's",
			new String[]{"SLAYER_TASK_NAME_X", "_100_PIRATE_QUEST_VAR"}, vars.varbitNames);
	}

	@Test
	public void noBuiltInNameIsReservedOrStartsWithADigit()
	{
		for (String[] names : new String[][]{GameVars.VARBIT_NAMES, GameVars.VARP_NAMES})
		{
			for (String name : names)
			{
				assertFalse(name, SiteConfig.RESERVED_VAR.matcher(name).find());
				assertFalse(name, Character.isDigit(name.charAt(0)));
			}
		}
	}

	@Test
	public void anExcludedVarCannotBeSentUnderAnAllowedName()
	{
		int excludedVarbit = GameVars.EXCLUDED_VARBIT_IDS[0];
		int excludedVarp = GameVars.EXCLUDED_VARP_IDS[0];
		SiteConfig.Vars vars = parse("{\"vars\":{\"varbits\":{\"QUEST_X\":" + excludedVarbit + ","
			+ "\"" + GameVars.VARBIT_NAMES[0] + "\":" + excludedVarbit + "},\"varps\":{\"QUEST_Y\":" + excludedVarp + "}}}").vars;
		assertSame("nothing taken, so the built-in list as it was", SiteConfig.Vars.BUILT_IN, vars);
	}

	@Test
	public void theGeneratedExclusionsAreSortedAndNeverABuiltInId()
	{
		for (int[] ids : new int[][]{GameVars.EXCLUDED_VARBIT_IDS, GameVars.EXCLUDED_VARP_IDS})
		{
			assertTrue(ids.length > 0);
			for (int i = 1; i < ids.length; i++)
			{
				assertTrue("sorted, for the binary search", ids[i - 1] < ids[i]);
			}
		}
		for (int id : GameVars.VARBIT_IDS)
		{
			assertTrue(Arrays.binarySearch(GameVars.EXCLUDED_VARBIT_IDS, id) < 0);
		}
		for (int id : GameVars.VARP_IDS)
		{
			assertTrue(Arrays.binarySearch(GameVars.EXCLUDED_VARP_IDS, id) < 0);
		}
	}

	@Test
	public void theSitesVarsStopWhereThePartWouldGrowPastItsSizeLimit()
	{
		StringBuilder varbits = new StringBuilder();
		for (int i = 0; i < SiteConfig.MAX_SITE_VARBITS; i++)
		{
			varbits.append(i == 0 ? "" : ",").append("\"A_FAIRLY_LONG_NEW_QUEST_PROGRESS_NAME_").append(i).append("\":")
				.append(30_000 + i);
		}
		SiteConfig.Vars vars = parse("{\"vars\":{\"varbits\":{" + varbits + "}}}").vars;
		int size = 0;
		for (String name : vars.varbitNames)
		{
			size += name.length() + 8;
		}
		for (String name : vars.varpNames)
		{
			size += name.length() + 8;
		}
		assertTrue("some were added", vars.varbitIds.length > GameVars.VARBIT_IDS.length);
		assertTrue("not all of them", vars.varbitIds.length < GameVars.VARBIT_IDS.length + SiteConfig.MAX_SITE_VARBITS);
		assertTrue(size <= SiteConfig.MAX_VARS_BYTES);
	}

	@Test
	public void onlyTheFirstFourThousandVarbitsAndThousandVarpsAreTaken()
	{
		StringBuilder varbits = new StringBuilder();
		for (int i = 0; i < SiteConfig.MAX_SITE_VARBITS + 10; i++)
		{
			varbits.append(i == 0 ? "" : ",").append("\"V").append(i).append("\":").append(30_000 + i);
		}
		StringBuilder varps = new StringBuilder();
		for (int i = 0; i < SiteConfig.MAX_SITE_VARPS + 10; i++)
		{
			varps.append(i == 0 ? "" : ",").append("\"P").append(i).append("\":").append(10_000 + i);
		}
		SiteConfig.Vars vars = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{" + varbits + "},\"varps\":{"
			+ varps + "}}}").vars;
		assertEquals(SiteConfig.MAX_SITE_VARBITS, vars.varbitIds.length);
		assertEquals(SiteConfig.MAX_SITE_VARPS, vars.varpIds.length);
		assertEquals("V0", vars.varbitNames[0]);
	}

	@Test
	public void sameAsComparesTheWholeSelection()
	{
		SiteConfig.Vars a = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"Q\":1}}}").vars;
		SiteConfig.Vars b = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"Q\":1}}}").vars;
		SiteConfig.Vars c = parse("{\"vars\":{\"mode\":\"replace\",\"varbits\":{\"Q\":2}}}").vars;
		assertTrue(a.sameAs(b));
		assertFalse(a.sameAs(c));
		assertFalse(a.sameAs(SiteConfig.Vars.BUILT_IN));
	}

	// --- notices and player text -----------------------------------------------

	@Test
	public void aNoticeCarriesItsTextIdLinkAndChatChoice()
	{
		SiteConfig.Notice notice = parse("{\"notice\":{\"id\":\"maintenance-1\",\"text\":\"Down at 18:00 UTC.\","
			+ "\"url\":\"https://osrsindex.com/status\",\"chat\":true}}").notice;
		assertNotNull(notice);
		assertEquals("maintenance-1", notice.id);
		assertEquals("Down at 18:00 UTC.", notice.text);
		assertEquals("https://osrsindex.com/status", notice.url);
		assertTrue(notice.chat);
	}

	@Test
	public void aNoticeWithoutAnIdUsesItsTextAndStaysOutOfChatUnlessAsked()
	{
		SiteConfig.Notice notice = parse("{\"notice\":{\"text\":\"Hello.\"}}").notice;
		assertEquals("Hello.", notice.id);
		assertFalse(notice.chat);
		assertNull(notice.url);
		assertEquals("Hello.", parse("{\"notice\":{\"id\":\"" + "x".repeat(65) + "\",\"text\":\"Hello.\"}}").notice.id);
	}

	@Test
	public void aNoticeWithoutUsableTextIsNoNotice()
	{
		assertNull(parse("{\"notice\":{\"id\":\"a\"}}").notice);
		assertNull(parse("{\"notice\":{\"text\":\"   \\n\\t \"}}").notice);
		assertNull(parse("{\"notice\":{\"text\":5}}").notice);
	}

	@Test
	public void aNoticeLinksOnlyToThePluginsOwnOrigin()
	{
		for (String url : new String[]{"https://evil.example/osrsindex.com", "https://osrsindex.com.evil.example/x",
			"http://osrsindex.com/x", "javascript:alert(1)", "https://osrsindex.com", "https://osrsindex.com/a b"})
		{
			assertNull(url, parse("{\"notice\":{\"text\":\"t\",\"url\":\"" + url + "\"}}").notice.url);
		}
		assertEquals("https://osrsindex.com/news?id=3",
			parse("{\"notice\":{\"text\":\"t\",\"url\":\"https://osrsindex.com/news?id=3\"}}").notice.url);
	}

	@Test
	public void playerTextDropsControlsJoinsSpacesAndKeepsMarkupAsText()
	{
		assertEquals("Line one line two", SiteConfig.playerText("  Line one\n\n line\u0000 two\t "));
		assertEquals("<col=ff0000>red</col> & <b>bold</b>", SiteConfig.playerText("<col=ff0000>red</col> & <b>bold</b>"));
		assertEquals("a b", SiteConfig.playerText("a b"));
		assertNull(SiteConfig.playerText(" \u0007 "));
		assertNull(SiteConfig.playerText(null));
	}

	@Test
	public void playerTextIsCutAtThreeHundredCodePoints()
	{
		String text = SiteConfig.playerText("😀".repeat(400));
		assertEquals(SiteConfig.MAX_TEXT, text.codePointCount(0, text.length()));
	}

	@Test
	public void markupInANoticeIsEscapedWhereItIsShown()
	{
		String text = parse("{\"notice\":{\"text\":\"<img src=x> & <col=ff0000>\"}}").notice.text;
		assertEquals("&lt;img src=x&gt; &amp; &lt;col=ff0000&gt;", OsrsIndexPanel.escape(text));
		assertFalse(RouteDispatcher.chatSafe(text).contains("<col"));
	}
}
