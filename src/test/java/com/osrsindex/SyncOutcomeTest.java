package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class SyncOutcomeTest
{
	@Test
	public void storedWaitsOutTheFloor()
	{
		SyncOutcome outcome = SyncOutcome.of(200, "{\"stored\":[\"bank\"]}", null);
		assertEquals(SyncOutcome.Kind.STORED, outcome.kind);
		assertEquals(SyncOutcome.FLOOR_SECONDS, outcome.waitSeconds);
	}

	@Test
	public void unsupportedSchemaMeansAnOlderServer()
	{
		assertEquals(SyncOutcome.Kind.OLD_SERVER,
			SyncOutcome.of(400, "{\"code\":\"unsupported_schema\",\"message\":\"…\"}", null).kind);
	}

	@Test
	public void unknownFieldAlsoMeansAnOlderServer()
	{
		// A schema 1 server checks fields before the schema number, so a schema 2 body gets unknown_field.
		assertEquals(SyncOutcome.Kind.OLD_SERVER,
			SyncOutcome.of(400, "{\"code\":\"unknown_field\"}", null).kind);
	}

	@Test
	public void refusedPartTimesAreTheirOwnOutcome()
	{
		// captured is not a body part, so the times alone are at fault: nothing is dropped, nothing waits.
		SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"invalid_captured\",\"message\":\"…\"}", null);
		assertEquals(SyncOutcome.Kind.PART_TIMES_REFUSED, outcome.kind);
		assertEquals("invalid_captured", outcome.code);
		assertEquals(0, outcome.waitSeconds);
		assertNull(outcome.refused);
		// The envelope's own time is a different code, and still a refusal of the body.
		assertEquals(SyncOutcome.Kind.REJECTED, SyncOutcome.of(400, "{\"code\":\"invalid_captured_at\"}", null).kind);
		// Only a 400 carries a reason code the plugin reads.
		assertEquals(SyncOutcome.Kind.REJECTED, SyncOutcome.of(415, "{\"code\":\"invalid_captured\"}", null).kind);
	}

	@Test
	public void anyOtherContractBreakIsRejectedWithItsCode()
	{
		SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"invalid_location\"}", null);
		assertEquals(SyncOutcome.Kind.REJECTED, outcome.kind);
		assertEquals("invalid_location", outcome.code);
	}

	@Test
	public void eachSectionCodeNamesItsPart()
	{
		// The site's own codes, written out rather than built from Part.key, so a renamed key fails here. A bad
		// item answers its container's code too: equipment, inventory, bank, collection log or storage.
		Map<Part, String> codes = new EnumMap<>(Part.class);
		codes.put(Part.LOCATION, "invalid_location");
		codes.put(Part.ACCOUNT, "invalid_account");
		codes.put(Part.EQUIPMENT, "invalid_equipment");
		codes.put(Part.INVENTORY, "invalid_inventory");
		codes.put(Part.SKILLS, "invalid_skills");
		codes.put(Part.DIARIES, "invalid_diaries");
		codes.put(Part.COMBAT_ACHIEVEMENTS, "invalid_combat_achievements");
		codes.put(Part.QUESTS, "invalid_quests");
		codes.put(Part.GRAND_EXCHANGE, "invalid_grand_exchange");
		codes.put(Part.KILL_COUNTS, "invalid_kill_counts");
		codes.put(Part.STORAGE, "invalid_storage");
		codes.put(Part.BANK, "invalid_bank");
		codes.put(Part.COLLECTION_LOG, "invalid_collection_log");
		codes.put(Part.VARS, "invalid_vars");
		assertEquals(EnumSet.allOf(Part.class), codes.keySet());

		for (Map.Entry<Part, String> entry : codes.entrySet())
		{
			SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"" + entry.getValue() + "\",\"message\":\"…\"}", null);
			assertEquals(entry.getValue(), SyncOutcome.Kind.REJECTED, outcome.kind);
			assertEquals(entry.getValue(), entry.getKey(), outcome.refused);
		}
	}

	@Test
	public void anOlderSitesInvalidItemNamesNone()
	{
		// A site from before item faults took their container's code: invalid_item, from any item container,
		// and the code alone does not say which. It can still answer, so it is still read.
		SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"invalid_item\",\"message\":\"…\"}", null);
		assertEquals(SyncOutcome.Kind.REJECTED, outcome.kind);
		assertNull(outcome.refused);
	}

	@Test
	public void aPartFieldNamesTheContainer()
	{
		// No site sends part now. One that does names the part outright, whatever the code.
		for (Part part : EnumSet.of(Part.EQUIPMENT, Part.INVENTORY, Part.BANK, Part.COLLECTION_LOG, Part.STORAGE))
		{
			SyncOutcome outcome = SyncOutcome.of(400,
				"{\"code\":\"invalid_item\",\"message\":\"…\",\"part\":\"" + part.key + "\"}", null);
			assertEquals(part.key, SyncOutcome.Kind.REJECTED, outcome.kind);
			assertEquals(part.key, part, outcome.refused);
		}
	}

	@Test
	public void aPartFieldWinsOverTheCodeUnlessItNamesNoKnownPart()
	{
		assertEquals(Part.STORAGE,
			SyncOutcome.of(400, "{\"code\":\"invalid_bank\",\"part\":\"storage\"}", null).refused);
		assertEquals(Part.VARS,
			SyncOutcome.of(400, "{\"code\":\"invalid_vars\",\"part\":\"a_later_part\"}", null).refused);
		assertNull(SyncOutcome.of(400, "{\"code\":\"invalid_item\",\"part\":\"a_later_part\"}", null).refused);
		assertNull(SyncOutcome.of(400, "{\"code\":\"invalid_item\",\"part\":[\"bank\"]}", null).refused);
	}

	@Test
	public void codesThatNameNoPartNameNone()
	{
		String[] codes = {"invalid_json", "not_an_object", "invalid_character", "invalid_captured_at", "no_containers",
			"invalid_something_new", "a_future_code"};
		for (String code : codes)
		{
			SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"" + code + "\"}", null);
			assertEquals(code, SyncOutcome.Kind.REJECTED, outcome.kind);
			assertNull(code, outcome.refused);
		}
		assertNull(SyncOutcome.of(400, "<html>bad request</html>", null).refused);
		// Only a 400 names a part: a 415 is about the request, not one part of the body.
		assertNull(SyncOutcome.of(415, "{\"code\":\"invalid_bank\",\"part\":\"bank\"}", null).refused);
		// At schema 1 an older server's codes fall through to a refusal of everything sent.
		assertNull(SyncOutcome.of(400, "{\"code\":\"unknown_field\"}", null).refused);
	}

	@Test
	public void aNamedRefusalDropsOnlyThatPart()
	{
		Set<Part> sent = EnumSet.of(Part.LOCATION, Part.QUESTS, Part.BANK, Part.VARS);
		SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"invalid_vars\"}", null);
		assertEquals(EnumSet.of(Part.VARS), outcome.dropped(sent));

		// A bad bank item answers the bank's own code, so only the bank waits.
		SyncOutcome item = SyncOutcome.of(400, "{\"code\":\"invalid_bank\"}", null);
		assertEquals(EnumSet.of(Part.BANK), item.dropped(sent));

		SyncOutcome named = SyncOutcome.of(400, "{\"code\":\"invalid_item\",\"part\":\"bank\"}", null);
		assertEquals(EnumSet.of(Part.BANK), named.dropped(sent));
	}

	@Test
	public void aRefusalThatNamesNoPartDropsEverythingSent()
	{
		Set<Part> sent = EnumSet.of(Part.LOCATION, Part.QUESTS, Part.BANK, Part.VARS);
		// An older site's invalid_item names no part.
		assertEquals(sent, SyncOutcome.of(400, "{\"code\":\"invalid_item\"}", null).dropped(sent));
		assertEquals(sent, SyncOutcome.of(400, "{\"code\":\"a_future_code\"}", null).dropped(sent));
		assertEquals(sent, SyncOutcome.of(415, "{\"code\":\"unsupported_media_type\"}", null).dropped(sent));
	}

	@Test
	public void aRefusalOfAPartThatWasNotSentDropsEverythingSent()
	{
		// Dropping nothing would send the same body again, and have it refused again.
		Set<Part> sent = EnumSet.of(Part.LOCATION, Part.QUESTS);
		assertEquals(sent, SyncOutcome.of(400, "{\"code\":\"invalid_bank\"}", null).dropped(sent));
	}

	@Test
	public void unauthorizedStopsUntilTheTokenChanges()
	{
		assertEquals(SyncOutcome.Kind.BAD_TOKEN, SyncOutcome.of(401, "{\"code\":\"unauthorized\"}", null).kind);
	}

	@Test
	public void tooLargeShrinksTheBudget()
	{
		assertEquals(SyncOutcome.Kind.TOO_LARGE, SyncOutcome.of(413, "{\"code\":\"too_large\"}", null).kind);
	}

	@Test
	public void aFullAccountWaitsFifteenMinutesInsteadOfRetryingEveryMinute()
	{
		SyncOutcome outcome = SyncOutcome.of(409, "{\"code\":\"too_many_characters\",\"message\":\"…\"}", null);
		assertEquals(SyncOutcome.Kind.ACCOUNT_FULL, outcome.kind);
		assertEquals(SyncOutcome.ACCOUNT_FULL_RETRY_SECONDS, outcome.waitSeconds);
		assertEquals("too_many_characters", outcome.code);
	}

	@Test
	public void anyOther409IsAnOrdinaryRetry()
	{
		assertEquals(SyncOutcome.Kind.RETRY, SyncOutcome.of(409, "{\"code\":\"something_else\"}", null).kind);
		assertEquals(SyncOutcome.Kind.RETRY, SyncOutcome.of(409, "<html>conflict</html>", null).kind);
	}

	@Test
	public void tooFrequentHonoursRetryAfter()
	{
		SyncOutcome outcome = SyncOutcome.of(429, "{\"code\":\"too_frequent\"}", "17");
		assertEquals(SyncOutcome.Kind.RETRY, outcome.kind);
		assertEquals(18, outcome.waitSeconds);
	}

	@Test
	public void aServerErrorPageWithoutRetryAfterWaitsTheDefault()
	{
		SyncOutcome outcome = SyncOutcome.of(502, "<html>bad gateway</html>", null);
		assertEquals(SyncOutcome.Kind.RETRY, outcome.kind);
		assertEquals(SyncOutcome.DEFAULT_RETRY_SECONDS, outcome.waitSeconds);
	}

	// --- the site config and player messages (docs/site-config.md) ----------

	private static final SiteConfig.Timing TUNED = SiteConfig.parse("{\"timing\":{\"sync_floor_seconds\":45,"
		+ "\"retry_seconds\":120,\"account_full_retry_seconds\":3600}}").timing;

	@Test
	public void theWaitsAreTheSiteConfigs()
	{
		assertEquals(45, SyncOutcome.of(200, "{}", null, TUNED).waitSeconds);
		assertEquals(120, SyncOutcome.of(503, "", null, TUNED).waitSeconds);
		assertEquals(120, SyncOutcome.networkFailure(TUNED).waitSeconds);
		assertEquals(3_600, SyncOutcome.of(409, "{\"code\":\"too_many_characters\"}", null, TUNED).waitSeconds);
		assertEquals("a 413 waits at least the floor", 45, SyncOutcome.of(413, "", null, TUNED).waitSeconds);
		assertEquals("Retry-After still wins", 18, SyncOutcome.of(429, "", "17", TUNED).waitSeconds);
	}

	@Test
	public void aPlayerMessageIsKeptMadeSafeAndTheDeveloperMessageNever()
	{
		SyncOutcome refused = SyncOutcome.of(400, "{\"code\":\"invalid_bank\",\"message\":\"bank[3].qty < 0\","
			+ "\"player_message\":\"  Your bank could not be read.\\nTry opening it again.  \"}", null);
		assertEquals("Your bank could not be read. Try opening it again.", refused.playerMessage);
		assertNull(SyncOutcome.of(400, "{\"code\":\"invalid_bank\",\"message\":\"bank[3].qty < 0\"}", null).playerMessage);
		assertNull(SyncOutcome.of(400, "{\"code\":\"invalid_bank\",\"player_message\":7}", null).playerMessage);
		assertNull(SyncOutcome.of(503, "<html>down</html>", null).playerMessage);
		assertEquals("Back at 19:00 UTC.",
			SyncOutcome.of(503, "{\"code\":\"maintenance\",\"player_message\":\"Back at 19:00 UTC.\"}", null).playerMessage);
	}

	@Test
	public void aStoredSyncMayNameTheConfigVersion()
	{
		assertEquals(8, SyncOutcome.of(200, "{\"stored\":[\"bank\"],\"config_version\":8}", null).configVersion);
		assertEquals(-1, SyncOutcome.of(200, "{\"stored\":[\"bank\"]}", null).configVersion);
		assertEquals(-1, SyncOutcome.of(200, "{\"config_version\":\"8\"}", null).configVersion);
		assertEquals(-1, SyncOutcome.of(200, "{\"config_version\":-2}", null).configVersion);
		assertEquals(-1, SyncOutcome.of(200, "{\"config_version\":1.5}", null).configVersion);
		assertEquals(-1, SyncOutcome.of(200, "", null).configVersion);
	}
}
