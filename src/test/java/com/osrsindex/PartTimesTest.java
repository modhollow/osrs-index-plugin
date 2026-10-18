package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.time.Instant;
import org.junit.Test;

public class PartTimesTest
{
	private static final Instant BANK_AT = Instant.parse("2026-10-07T14:02:11Z");
	private static final Instant STORAGE_AT = Instant.parse("2026-10-07T13:00:00Z");
	private static final Instant KILLS_AT = Instant.parse("2026-10-07T14:40:00Z");

	@Test
	public void keepsEachTimeToTheSecond()
	{
		PartTimes times = new PartTimes();
		times.mark(Part.BANK, Instant.parse("2026-10-07T14:02:11.875Z"));

		assertEquals(BANK_AT, times.asMap().get(Part.BANK));
	}

	@Test
	public void aLaterMarkReplacesTheEarlierOne()
	{
		PartTimes times = new PartTimes();
		times.mark(Part.KILL_COUNTS, STORAGE_AT);
		times.mark(Part.KILL_COUNTS, KILLS_AT);

		assertEquals(KILLS_AT, times.asMap().get(Part.KILL_COUNTS));
	}

	@Test
	public void theProfileKeepsOnlyThePartsItKeeps()
	{
		// The bank is not kept in the profile, so neither is its time.
		PartTimes times = new PartTimes();
		times.mark(Part.BANK, BANK_AT);
		times.mark(Part.STORAGE, STORAGE_AT);
		times.mark(Part.KILL_COUNTS, KILLS_AT);

		JsonObject saved = times.toJson();

		assertEquals(2, saved.size());
		assertEquals("2026-10-07T13:00:00Z", saved.get("storage").getAsString());
		assertEquals("2026-10-07T14:40:00Z", saved.get("kill_counts").getAsString());
		assertFalse(saved.has("bank"));
	}

	@Test
	public void aSavedProfileRestoresTheTimesAndLeavesTheBankAlone()
	{
		PartTimes before = new PartTimes();
		before.mark(Part.STORAGE, STORAGE_AT);
		before.mark(Part.KILL_COUNTS, KILLS_AT);
		String saved = before.toJson().toString();

		// The bank can be read before the profile loads at login.
		PartTimes after = new PartTimes();
		after.mark(Part.BANK, BANK_AT);
		after.mark(Part.STORAGE, Instant.parse("2026-10-01T00:00:00Z"));
		after.load(saved);

		assertEquals(STORAGE_AT, after.asMap().get(Part.STORAGE));
		assertEquals(KILLS_AT, after.asMap().get(Part.KILL_COUNTS));
		assertEquals(BANK_AT, after.asMap().get(Part.BANK));
	}

	@Test
	public void aProfileFromBeforeTimesLeavesTheSavedPartsUndated()
	{
		PartTimes times = new PartTimes();
		times.mark(Part.STORAGE, STORAGE_AT);
		times.load(null);

		assertNull(times.asMap().get(Part.STORAGE));
	}

	@Test
	public void aMalformedProfileValueLoadsWhatItCan()
	{
		PartTimes times = new PartTimes();
		times.load("{\"storage\":\"yesterday\",\"kill_counts\":\"2026-10-07T14:40:00Z\",\"bank\":\"2026-10-07T14:02:11Z\"}");

		assertNull(times.asMap().get(Part.STORAGE));
		assertEquals(KILLS_AT, times.asMap().get(Part.KILL_COUNTS));
		// Only the parts the profile keeps are read from it.
		assertNull(times.asMap().get(Part.BANK));

		times.load("not json");
		assertTrue(times.asMap().isEmpty());
		times.load("[1]");
		assertTrue(times.asMap().isEmpty());
	}

	@Test
	public void clearForgetsEveryTime()
	{
		PartTimes times = new PartTimes();
		times.mark(Part.BANK, BANK_AT);
		times.mark(Part.STORAGE, STORAGE_AT);
		times.clear();

		assertTrue(times.asMap().isEmpty());
	}
}
