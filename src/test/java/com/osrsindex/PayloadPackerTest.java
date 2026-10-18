package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.Test;

public class PayloadPackerTest
{
	private static final Gson GSON = new Gson();

	private static JsonArray bank(int items)
	{
		JsonArray out = new JsonArray();
		for (int i = 0; i < items; i++)
		{
			out.add(CharacterReader.itemJson(1_000 + i, 1_000_000 + i));
		}
		return out;
	}

	private static JsonObject location()
	{
		JsonObject out = new JsonObject();
		out.addProperty("x", 3222);
		out.addProperty("y", 3218);
		out.addProperty("plane", 0);
		return out;
	}

	@Test
	public void carriesTheHeaderAndEveryPartThatFits()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.LOCATION, location());
		pending.put(Part.BANK, bank(3));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 2, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		JsonObject body = new JsonParser().parse(packed.json).getAsJsonObject();
		assertEquals(2, body.get("schema").getAsInt());
		assertEquals("Zezima", body.get("character").getAsString());
		assertEquals("2026-09-24T18:00:00Z", body.get("captured_at").getAsString());
		assertEquals(3222, body.getAsJsonObject("location").get("x").getAsInt());
		assertEquals(3, body.getAsJsonArray("bank").size());
		assertEquals(EnumSet.of(Part.LOCATION, Part.BANK), packed.parts);
	}

	@Test
	public void schemaOneDropsEverySection()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.LOCATION, location());
		pending.put(Part.BANK, bank(1));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 1, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		JsonObject body = new JsonParser().parse(packed.json).getAsJsonObject();
		assertFalse(body.has("location"));
		assertTrue(body.has("bank"));
		assertEquals(EnumSet.of(Part.BANK), packed.parts);
	}

	@Test
	public void nothingToSendUnderSchemaOneIsNull()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.SKILLS, new JsonObject());

		assertNull(PayloadPacker.pack(GSON, 1, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000));
	}

	@Test
	public void aPartThatWouldBreakTheBudgetWaitsForTheNextSync()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, bank(2_000));
		JsonObject log = new JsonObject();
		log.addProperty("obtained", 3_000);
		log.addProperty("total", 3_000);
		log.add("items", bank(3_000));
		pending.put(Part.COLLECTION_LOG, log);

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 2, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		assertEquals(EnumSet.of(Part.BANK), packed.parts);
		assertTrue(packed.json.getBytes(StandardCharsets.UTF_8).length <= 120_000);
	}

	@Test
	public void aSinglePartLargerThanTheBudgetIsStillSentAlone()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, bank(2_000));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 2, "Zezima", "2026-09-24T18:00:00Z", pending, 1_000);

		assertEquals(EnumSet.of(Part.BANK), packed.parts);
	}

	@Test
	public void inventoryKeepsEmptySlotsSoIndexesStaySlots()
	{
		JsonArray inventory = new JsonArray();
		inventory.add(CharacterReader.itemJson(995, 1_000));
		inventory.add(JsonNull.INSTANCE);
		inventory.add(CharacterReader.itemJson(385, 1));
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.INVENTORY, inventory);

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 2, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		JsonArray sent = new JsonParser().parse(packed.json).getAsJsonObject().getAsJsonArray("inventory");
		assertEquals(3, sent.size());
		assertTrue(sent.get(1).isJsonNull());
	}

	@Test
	public void schemaTwoHoldsBackTheSchemaThreeParts()
	{
		JsonObject vars = new JsonObject();
		vars.addProperty("SLAYER_POINTS", 420);
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.VARS, vars);
		pending.put(Part.LOCATION, location());

		PayloadPacker.Packed two = PayloadPacker.pack(GSON, 2, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);
		PayloadPacker.Packed three = PayloadPacker.pack(GSON, 3, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		assertEquals(EnumSet.of(Part.LOCATION), two.parts);
		assertEquals(EnumSet.of(Part.LOCATION, Part.VARS), three.parts);
	}

	@Test
	public void theFullVarAllowlistFitsInOneSyncOnItsOwn()
	{
		JsonObject vars = new JsonObject();
		for (String name : GameVars.VARBIT_NAMES)
		{
			vars.addProperty(name, 2_147_483_647);
		}
		for (String name : GameVars.VARP_NAMES)
		{
			vars.addProperty(name, -2_147_483_648);
		}
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.VARS, vars);

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 3, "Zezima", "2026-09-24T18:00:00Z", pending, 120_000);

		assertTrue(packed.json.getBytes(StandardCharsets.UTF_8).length <= 131_072);
		assertEquals(GameVars.VARBIT_NAMES.length, GameVars.VARBIT_IDS.length);
		assertEquals(GameVars.VARP_NAMES.length, GameVars.VARP_IDS.length);
	}

	// --- part times (schema 5) ------------------------------------------------

	private static final String SENT_AT = "2026-10-07T15:00:00Z";

	private static JsonObject killCounts()
	{
		JsonObject out = new JsonObject();
		out.addProperty("Vorkath", 1234);
		return out;
	}

	@Test
	public void schemaFiveDatesThePartsReadBeforeTheSend()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.LOCATION, location());
		pending.put(Part.BANK, bank(3));
		pending.put(Part.KILL_COUNTS, killCounts());
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.BANK, Instant.parse("2026-10-07T14:02:11Z"));
		times.put(Part.KILL_COUNTS, Instant.parse("2026-10-07T14:40:00Z"));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, times, 120_000);

		JsonObject body = new JsonParser().parse(packed.json).getAsJsonObject();
		assertEquals(5, body.get("schema").getAsInt());
		assertEquals(SENT_AT, body.get("captured_at").getAsString());
		JsonObject captured = body.getAsJsonObject("captured");
		assertEquals(2, captured.size());
		assertEquals("2026-10-07T14:02:11Z", captured.get("bank").getAsString());
		assertEquals("2026-10-07T14:40:00Z", captured.get("kill_counts").getAsString());
		// Location is read at the send, so captured_at dates it.
		assertFalse(captured.has("location"));
		assertEquals(EnumSet.of(Part.LOCATION, Part.KILL_COUNTS, Part.BANK), packed.parts);
	}

	@Test
	public void anOlderSchemaSendsTheSamePartsUndated()
	{
		// After the site answers schema 5 with unsupported_schema, the plugin steps down and drops the times.
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, bank(3));
		pending.put(Part.KILL_COUNTS, killCounts());
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.BANK, Instant.parse("2026-10-07T14:02:11Z"));
		times.put(Part.KILL_COUNTS, Instant.parse("2026-10-07T14:40:00Z"));

		for (int schema = 1; schema < Part.PART_TIMES_SCHEMA; schema++)
		{
			PayloadPacker.Packed packed = PayloadPacker.pack(GSON, schema, "Zezima", SENT_AT, pending, times, 120_000);

			JsonObject body = new JsonParser().parse(packed.json).getAsJsonObject();
			assertEquals(schema, body.get("schema").getAsInt());
			assertFalse("schema " + schema, body.has("captured"));
			assertEquals(schema >= 3 ? EnumSet.of(Part.KILL_COUNTS, Part.BANK) : EnumSet.of(Part.BANK), packed.parts);
		}
	}

	@Test
	public void aBodyWithNothingToDateCarriesNoCapturedField()
	{
		// The site refuses an empty captured, so none is sent.
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.LOCATION, location());
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.BANK, Instant.parse("2026-10-07T14:02:11Z"));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, times, 120_000);

		assertFalse(new JsonParser().parse(packed.json).getAsJsonObject().has("captured"));
		PayloadPacker.Packed undated = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, 120_000);
		assertFalse(new JsonParser().parse(undated.json).getAsJsonObject().has("captured"));
	}

	@Test
	public void aTimeNotBeforeTheSendIsLeftOut()
	{
		// The site refuses a part time later than captured_at; one equal to it says nothing captured_at does not.
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, bank(3));
		pending.put(Part.KILL_COUNTS, killCounts());
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.BANK, Instant.parse(SENT_AT));
		times.put(Part.KILL_COUNTS, Instant.parse("2026-10-07T15:00:01Z"));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, times, 120_000);

		assertFalse(new JsonParser().parse(packed.json).getAsJsonObject().has("captured"));
		assertEquals(EnumSet.of(Part.KILL_COUNTS, Part.BANK), packed.parts);
	}

	@Test
	public void aTimeIsSentToTheSecond()
	{
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		pending.put(Part.BANK, bank(1));
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.BANK, Instant.parse("2026-10-07T14:02:11.875Z"));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, times, 120_000);

		JsonObject captured = new JsonParser().parse(packed.json).getAsJsonObject().getAsJsonObject("captured");
		assertEquals("2026-10-07T14:02:11Z", captured.get("bank").getAsString());
	}

	@Test
	public void aPartThatWaitsForTheNextSyncTakesItsTimeWithIt()
	{
		// The site refuses a time for a part the body does not carry.
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		JsonObject stores = new JsonObject();
		stores.add("seed_vault", bank(2_000));
		pending.put(Part.STORAGE, stores);
		pending.put(Part.BANK, bank(2_000));
		Map<Part, Instant> times = new EnumMap<>(Part.class);
		times.put(Part.STORAGE, Instant.parse("2026-10-07T13:00:00Z"));
		times.put(Part.BANK, Instant.parse("2026-10-07T14:02:11Z"));

		PayloadPacker.Packed packed = PayloadPacker.pack(GSON, 5, "Zezima", SENT_AT, pending, times, 120_000);

		assertEquals(EnumSet.of(Part.STORAGE), packed.parts);
		JsonObject captured = new JsonParser().parse(packed.json).getAsJsonObject().getAsJsonObject("captured");
		assertEquals(1, captured.size());
		assertEquals("2026-10-07T13:00:00Z", captured.get("storage").getAsString());
		assertTrue(packed.json.getBytes(StandardCharsets.UTF_8).length <= 120_000);
	}
}
