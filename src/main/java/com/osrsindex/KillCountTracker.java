package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Boss and activity counts, gathered from the game's own chat lines. No var holds them. Kept in the
 * RuneLite profile so they accumulate across sessions.
 */
final class KillCountTracker
{
	static final int MAX_ENTRIES = 500;
	static final int MAX_NAME_LENGTH = 60;
	static final int MAX_COUNT = 10_000_000;

	/**
	 * "Your Vorkath kill count is: 123.", "Your completed Theatre of Blood count is: 5.",
	 * "Your Barrows chest count is: 50.", "Your Gauntlet completion count is: 10.",
	 * "Your subdued Wintertodt count is: 7.", "Your Canifis Rooftop lap count is: 40."
	 */
	private static final Pattern COUNT = Pattern.compile(
		"^Your (?:completed |subdued )?(.+?) (?:kill |chest |completion |harvest |lap |success |reward |rift search )?count is: ([\\d,]+)\\.?$");

	private final Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	/** Record a count from a chat line with its tags already removed. Returns true when a count changed. */
	boolean offer(String message)
	{
		if (message == null)
		{
			return false;
		}
		Matcher matcher = COUNT.matcher(message.replace(' ', ' ').trim());
		if (!matcher.matches())
		{
			return false;
		}
		String name = matcher.group(1).trim();
		int count;
		try
		{
			count = Integer.parseInt(matcher.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return false;
		}
		return record(name, count);
	}

	boolean record(String name, int count)
	{
		if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || count < 0 || count > MAX_COUNT)
		{
			return false;
		}
		Integer previous = counts.get(name);
		if (previous != null && previous == count)
		{
			return false;
		}
		if (previous == null && counts.size() >= MAX_ENTRIES)
		{
			return false;
		}
		counts.put(name, count);
		return true;
	}

	int size()
	{
		return counts.size();
	}

	Integer get(String name)
	{
		return counts.get(name);
	}

	void clear()
	{
		counts.clear();
	}

	JsonObject toJson()
	{
		JsonObject out = new JsonObject();
		counts.forEach(out::addProperty);
		return out;
	}

	void load(String saved)
	{
		counts.clear();
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
			for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet())
			{
				try
				{
					record(entry.getKey(), entry.getValue().getAsInt());
				}
				catch (RuntimeException ignored)
				{
					// Skip a malformed entry.
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// A malformed profile value loads as empty.
		}
	}
}
