package com.osrsindex;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds one sync body from the parts waiting to be sent. The server refuses a body over 131,072
 * bytes whole, and an absent part is left as it was, so a part that does not fit simply waits for
 * the next sync. A single part larger than the budget is still sent alone, because waiting would
 * never make it smaller. If the server refuses that lone part as too large, it is dropped until it
 * changes (see {@link #tooLargeAlone}).
 */
final class PayloadPacker
{
	/** The server's cap is 131,072 bytes; keep some room. */
	static final int DEFAULT_BUDGET_BYTES = 120_000;

	/** Halving stops here, so the small parts can still go out together. */
	static final int MIN_BUDGET_BYTES = 16_000;

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
		return pack(gson, schema, character, capturedAt, pending, Collections.emptyMap(), budgetBytes);
	}

	/**
	 * @param pending the parts to send and their values, any order
	 * @param partTimes when the parts held between sends were read ({@link PartTimes}). From
	 *     {@link Part#PART_TIMES_SCHEMA} on, the body dates each such part it carries in {@code captured}. The site
	 *     refuses a time later than {@code captured_at}, so a time not before it is left out, and the site then
	 *     dates that part at {@code captured_at}, as it does every part an older schema sends.
	 * @return the body and the parts it carries, or null when nothing the schema allows is pending
	 */
	static Packed pack(Gson gson, int schema, String character, String capturedAt,
		Map<Part, JsonElement> pending, Map<Part, Instant> partTimes, int budgetBytes)
	{
		JsonObject body = new JsonObject();
		body.addProperty("schema", schema);
		body.addProperty("character", character);
		body.addProperty("captured_at", capturedAt);
		// Added before the parts so it is counted against the budget with them, and removed below if it stays
		// empty: the site refuses an empty one.
		JsonObject captured = new JsonObject();
		Instant sentAt = schema >= Part.PART_TIMES_SCHEMA ? instant(capturedAt) : null;
		if (sentAt != null)
		{
			body.add("captured", captured);
		}

		EnumSet<Part> included = EnumSet.noneOf(Part.class);
		for (Part part : Part.values())
		{
			JsonElement value = pending.get(part);
			if (value == null || part.minSchema > schema)
			{
				continue;
			}
			body.add(part.key, value);
			Instant time = partTimes.get(part);
			if (sentAt != null && time != null && time.isBefore(sentAt))
			{
				captured.addProperty(part.key, time.truncatedTo(ChronoUnit.SECONDS).toString());
			}
			if (!included.isEmpty() && size(gson, body) > budgetBytes)
			{
				// A part that waits for the next sync takes its time with it: the site refuses a time for a part
				// the body does not carry.
				body.remove(part.key);
				captured.remove(part.key);
				continue;
			}
			included.add(part);
		}
		if (included.isEmpty())
		{
			return null;
		}
		if (sentAt != null && captured.size() == 0)
		{
			body.remove("captured");
		}
		return new Packed(gson.toJson(body), included);
	}

	/** The send time as an instant, or null when it does not parse, which leaves the body undated. */
	private static Instant instant(String capturedAt)
	{
		try
		{
			return Instant.parse(capturedAt);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}

	static int size(Gson gson, JsonObject body)
	{
		return gson.toJson(body).getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * The part to drop after the server refused a body as too large. A body of one part was refused
	 * for that part's own size, and no budget makes a lone part smaller, so sending it again would only
	 * be refused again.
	 *
	 * @param parts the parts the refused body carried
	 * @return the body's only part, or null for a body of several parts, which {@link #halved} splits up
	 */
	static Part tooLargeAlone(Set<Part> parts)
	{
		return parts.size() == 1 ? parts.iterator().next() : null;
	}

	/**
	 * The budget after the server refused a body of several parts as too large: half, so fewer parts go
	 * together next time, but never below {@link #MIN_BUDGET_BYTES}.
	 */
	static int halved(int budgetBytes)
	{
		return Math.max(MIN_BUDGET_BYTES, budgetBytes / 2);
	}
}
