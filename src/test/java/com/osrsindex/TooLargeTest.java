package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

/**
 * What the plugin does after the site answers 413: a body of one part drops that part, a body of
 * several parts halves the budget, and either way the next send waits.
 */
public class TooLargeTest
{
	private static final Gson GSON = new Gson();

	private static JsonArray items(int count)
	{
		JsonArray out = new JsonArray();
		for (int i = 0; i < count; i++)
		{
			out.add(CharacterReader.itemJson(1_000 + i, 1_000_000 + i));
		}
		return out;
	}

	private static JsonObject vars(int count)
	{
		JsonObject out = new JsonObject();
		for (int i = 0; i < count; i++)
		{
			out.addProperty("VAR_" + i, 2_147_483_647);
		}
		return out;
	}

	private static PayloadPacker.Packed pack(Map<Part, JsonElement> pending, int budgetBytes)
	{
		return PayloadPacker.pack(GSON, 3, "Zezima", "2026-10-07T18:00:00Z", pending, budgetBytes);
	}

	@Test
	public void aTooLargeBodyWaitsOutTheFloorBeforeTheNextSend()
	{
		SyncOutcome outcome = SyncOutcome.of(413, "{\"code\":\"too_large\"}", null);
		assertEquals(SyncOutcome.Kind.TOO_LARGE, outcome.kind);
		assertEquals(SyncOutcome.FLOOR_SECONDS, outcome.waitSeconds);

		// A proxy's own 413 page carries no code, and still waits.
		SyncOutcome proxy = SyncOutcome.of(413, "<html>413 Request Entity Too Large</html>", null);
		assertEquals(SyncOutcome.Kind.TOO_LARGE, proxy.kind);
		assertEquals(SyncOutcome.FLOOR_SECONDS, proxy.waitSeconds);
	}

	@Test
	public void aLongerRetryAfterOnA413IsHonouredButNeverAShorterOne()
	{
		assertEquals(121, SyncOutcome.of(413, "{\"code\":\"too_large\"}", "120").waitSeconds);
		assertEquals(SyncOutcome.FLOOR_SECONDS, SyncOutcome.of(413, "{\"code\":\"too_large\"}", "5").waitSeconds);
		assertEquals(SyncOutcome.FLOOR_SECONDS, SyncOutcome.of(413, "{\"code\":\"too_large\"}", "0").waitSeconds);
	}

	@Test
	public void aLonePartOverTheBudgetIsTheOneDroppedAfterA413()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.VARS, vars(2_000));

		// Sent alone because waiting would never make it smaller...
		PayloadPacker.Packed packed = pack(pending, 1_000);
		assertEquals(EnumSet.of(Part.VARS), packed.parts);

		// ...so when the site refuses it, no smaller budget can help: that part is dropped.
		assertEquals(Part.VARS, PayloadPacker.tooLargeAlone(packed.parts));
	}

	@Test
	public void severalPartsRefusedAsTooLargeDropNothingAndHalveTheBudget()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, items(1_500));
		JsonObject log = new JsonObject();
		log.addProperty("obtained", 1_000);
		log.addProperty("total", 1_699);
		log.add("items", items(1_000));
		pending.put(Part.COLLECTION_LOG, log);

		PayloadPacker.Packed packed = pack(pending, PayloadPacker.DEFAULT_BUDGET_BYTES);
		assertEquals(EnumSet.of(Part.BANK, Part.COLLECTION_LOG), packed.parts);
		assertNull(PayloadPacker.tooLargeAlone(packed.parts));

		int budget = PayloadPacker.halved(PayloadPacker.DEFAULT_BUDGET_BYTES);
		assertEquals(60_000, budget);
		// At half the budget the two go separately, and the later one waits for the next sync.
		assertEquals(EnumSet.of(Part.BANK), pack(pending, budget).parts);
	}

	@Test
	public void theBudgetHalvesDownToItsFloorAndStaysThere()
	{
		assertEquals(30_000, PayloadPacker.halved(60_000));
		assertEquals(PayloadPacker.MIN_BUDGET_BYTES, PayloadPacker.halved(30_000));
		assertEquals(PayloadPacker.MIN_BUDGET_BYTES, PayloadPacker.halved(PayloadPacker.MIN_BUDGET_BYTES));
	}

	@Test
	public void thePanelLineNamesThePartThatWasTooLarge()
	{
		assertEquals("Not synced at 14:02: collection log was too large for the site. It is sent again when it changes.",
			OsrsIndexPlugin.tooLargeLine(LocalTime.of(14, 2), Part.COLLECTION_LOG));
		assertEquals("Not synced at 09:30: vars was too large for the site. It is sent again when it changes.",
			OsrsIndexPlugin.tooLargeLine(LocalTime.of(9, 30), Part.VARS));
	}
}
