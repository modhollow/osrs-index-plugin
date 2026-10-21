package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * When each part that is held between sends was read, so the site can date it by that time rather than by the
 * send. The bank counts from when it was last read while open. Item stores and kill counts are built from several
 * reads, and count from when they last changed. Every other part is read when the body is built, so it needs no time.
 */
final class PartTimes
{
	/**
	 * The parts kept in the RuneLite profile between sessions. Their times are kept there too, because a time lost
	 * on a restart would leave them dated by their next send, which is the mistake this class exists to avoid.
	 */
	static final Set<Part> SAVED = Collections.unmodifiableSet(EnumSet.of(Part.STORAGE, Part.KILL_COUNTS));

	private final Map<Part, Instant> times = new EnumMap<>(Part.class);

	/** The part was read, or changed, at this moment. Kept to the second, as the body sends it. */
	void mark(Part part, Instant at)
	{
		times.put(part, at.truncatedTo(ChronoUnit.SECONDS));
	}

	/** The time of each part marked so far. */
	Map<Part, Instant> asMap()
	{
		return Collections.unmodifiableMap(times);
	}

	void clear()
	{
		times.clear();
	}

	/** The {@link #SAVED} parts' times, for the profile: {@code {"storage": "2026-10-07T14:02:11Z"}}. */
	JsonObject toJson()
	{
		JsonObject out = new JsonObject();
		for (Part part : SAVED)
		{
			Instant time = times.get(part);
			if (time != null)
			{
				out.addProperty(part.key, time.toString());
			}
		}
		return out;
	}

	/**
	 * Replace the {@link #SAVED} parts' times with the profile's. The bank's time is left alone, since the bank can
	 * be read before the profile loads. A profile saved before the plugin kept times has none, and its stores and
	 * kill counts go out undated until they next change.
	 */
	void load(String saved)
	{
		for (Part part : SAVED)
		{
			times.remove(part);
		}
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		try
		{
			JsonElement parsed = new JsonParser().parse(saved);
			if (!parsed.isJsonObject())
			{
				return;
			}
			JsonObject object = parsed.getAsJsonObject();
			for (Part part : SAVED)
			{
				JsonElement value = object.get(part.key);
				if (value != null && value.isJsonPrimitive())
				{
					try
					{
						Instant at = Instant.parse(value.getAsString());
						// A time after now is from a damaged profile or a wrong clock: as good as none.
						if (!at.isAfter(Instant.now()))
						{
							mark(part, at);
						}
					}
					catch (DateTimeParseException ignored)
					{
						// A malformed time is as good as none.
					}
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// A malformed profile value loads as empty.
		}
	}
}
