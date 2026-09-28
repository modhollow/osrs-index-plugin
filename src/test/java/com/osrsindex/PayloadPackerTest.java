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
}
