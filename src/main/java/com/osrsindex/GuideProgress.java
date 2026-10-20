package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which steps of one guide the player marked, kept in the RuneLite profile, so per character, by the site's stable
 * step id. A step is done, skipped, not done ("open", said by the player, so a later done step does not count it as
 * passed), or unmarked. The player may also name the step they start from, with Start here. Marks for step ids the guide no longer has are kept, not
 * reset: they are ignored while the guide lacks the step, and count again if it comes back.
 *
 * <p>Stored as a JSON object of step id to {@code "done"}, {@code "skipped"} or {@code "open"}, with the start step
 * under {@code "@start"} (no step id starts with {@code @}). Not thread-safe: the plugin uses
 * it on the client thread, and the panel gets a {@link #copy()}.
 */
final class GuideProgress
{
	enum Mark
	{
		DONE("done"),
		SKIPPED("skipped"),
		/** Not done, in the player's words: a step with nothing to check, which a later done step would count as passed. */
		OPEN("open");

		final String key;

		Mark(String key)
		{
			this.key = key;
		}

		static Mark of(String key)
		{
			for (Mark mark : values())
			{
				if (mark.key.equals(key))
				{
					return mark;
				}
			}
			return null;
		}
	}

	/** More marks than any guide has steps: a profile value past this is not ours to grow. */
	static final int MAX_MARKS = 5_000;

	/** Where the start step is kept in the stored object. */
	static final String START_KEY = "@start";

	private final Map<String, Mark> marks = new LinkedHashMap<>();
	/** The step the player said they start from, or null. */
	private String start;

	Mark mark(String stepId)
	{
		return marks.get(stepId);
	}

	/** Mark a step, or clear its mark with null. True when that changed anything. */
	boolean set(String stepId, Mark mark)
	{
		if (mark == null)
		{
			return marks.remove(stepId) != null;
		}
		if (marks.size() >= MAX_MARKS && !marks.containsKey(stepId))
		{
			return false;
		}
		return marks.put(stepId, mark) != mark;
	}

	/** The step the player said they start from (Start here), or null. */
	String start()
	{
		return start;
	}

	/** Name the step the player starts from, or clear it with null. True when that changed anything. */
	boolean start(String stepId)
	{
		if (stepId != null && stepId.length() > GuideParser.MAX_STEP_ID * 2)
		{
			return false;
		}
		boolean changed = stepId == null ? start != null : !stepId.equals(start);
		start = stepId;
		return changed;
	}

	int size()
	{
		return marks.size();
	}

	GuideProgress copy()
	{
		GuideProgress copy = new GuideProgress();
		copy.marks.putAll(marks);
		copy.start = start;
		return copy;
	}

	/** The marks saved in the profile; anything unreadable in it is left out. */
	static GuideProgress load(String saved)
	{
		GuideProgress progress = new GuideProgress();
		if (saved == null || saved.isEmpty())
		{
			return progress;
		}
		try
		{
			JsonElement parsed = new JsonParser().parse(saved);
			if (parsed == null || !parsed.isJsonObject())
			{
				return progress;
			}
			for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet())
			{
				JsonElement value = entry.getValue();
				if (START_KEY.equals(entry.getKey()))
				{
					if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
					{
						progress.start(value.getAsString());
					}
					continue;
				}
				Mark mark = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? Mark.of(value.getAsString())
					: null;
				if (mark != null && entry.getKey().length() <= GuideParser.MAX_STEP_ID * 2)
				{
					progress.set(entry.getKey(), mark);
				}
			}
		}
		catch (RuntimeException unreadable)
		{
			// A damaged value: start this guide afresh rather than fail.
		}
		return progress;
	}

	JsonObject toJson()
	{
		JsonObject out = new JsonObject();
		for (Map.Entry<String, Mark> entry : marks.entrySet())
		{
			out.addProperty(entry.getKey(), entry.getValue().key);
		}
		if (start != null)
		{
			out.addProperty(START_KEY, start);
		}
		return out;
	}
}
