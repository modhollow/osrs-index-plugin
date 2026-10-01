package com.osrsindex;

import static org.junit.Assert.assertEquals;

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
	public void anyOtherContractBreakIsRejectedWithItsCode()
	{
		SyncOutcome outcome = SyncOutcome.of(400, "{\"code\":\"invalid_location\"}", null);
		assertEquals(SyncOutcome.Kind.REJECTED, outcome.kind);
		assertEquals("invalid_location", outcome.code);
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
}
