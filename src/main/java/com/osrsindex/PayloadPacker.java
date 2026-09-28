package com.osrsindex;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds one sync body from the parts waiting to be sent. The server refuses a body over 131,072
 * bytes whole, and an absent part is left as it was, so a part that does not fit simply waits for
 * the next sync. A single part larger than the budget is still sent alone, because waiting would
 * never make it smaller.
 */
final class PayloadPacker
{
	/** The server's cap is 131,072 bytes; keep some room. */
	static final int DEFAULT_BUDGET_BYTES = 120_000;

	static final class Packed
	{
		final String json;
		final Set<Part> parts;

		Packed(String json, Set<Part> parts)
		{
			this.json = json;
			this.parts = Collections.unmodifiableSet(parts);
		}
	}

	private PayloadPacker()
	{
	}

	/**
	 * @param pending the parts to send and their values, any order
	 * @return the body and the parts it carries, or null when nothing the schema allows is pending
	 */
	static Packed pack(Gson gson, int schema, String character, String capturedAt,
		Map<Part, JsonElement> pending, int budgetBytes)
	{
		JsonObject body = new JsonObject();
		body.addProperty("schema", schema);
		body.addProperty("character", character);
		body.addProperty("captured_at", capturedAt);

		EnumSet<Part> included = EnumSet.noneOf(Part.class);
		for (Part part : Part.values())
		{
			JsonElement value = pending.get(part);
			if (value == null || part.minSchema > schema)
			{
				continue;
			}
			body.add(part.key, value);
			if (!included.isEmpty() && size(gson, body) > budgetBytes)
			{
				body.remove(part.key);
				continue;
			}
			included.add(part);
		}
		if (included.isEmpty())
		{
			return null;
		}
		return new Packed(gson.toJson(body), included);
	}

	static int size(Gson gson, JsonObject body)
	{
		return gson.toJson(body).getBytes(StandardCharsets.UTF_8).length;
	}
}
