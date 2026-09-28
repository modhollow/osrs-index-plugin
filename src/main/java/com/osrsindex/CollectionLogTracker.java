package com.osrsindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The collection log items this plugin has seen. RuneLite can read a log item only while its page is
 * drawn, so the list grows as the player opens pages, and it is kept in the RuneLite profile so it
 * survives a restart. The game's own obtained/total counters are read separately and are right even
 * before any page has been opened.
 */
final class CollectionLogTracker
{
	/** The contract's cap on `collection_log.items`. */
	static final int MAX_ITEMS = 3_000;

	private final Map<Integer, Integer> items = new LinkedHashMap<>();

	/** Record an obtained item. Returns true when the list changed. */
	boolean record(int id, int quantity)
	{
		if (id < 0 || id > CharacterReader.MAX_ITEM_ID || quantity <= 0)
		{
			return false;
		}
		Integer previous = items.get(id);
		if (previous != null && previous == quantity)
		{
			return false;
		}
		if (previous == null && items.size() >= MAX_ITEMS)
		{
			return false;
		}
		items.put(id, quantity);
		return true;
	}

	int size()
	{
		return items.size();
	}

	void clear()
	{
		items.clear();
	}

	JsonArray toJson()
	{
		JsonArray out = new JsonArray();
		for (Map.Entry<Integer, Integer> entry : items.entrySet())
		{
			out.add(CharacterReader.itemJson(entry.getKey(), entry.getValue()));
		}
		return out;
	}

	/** Replace the list with one saved by {@link #toJson()}. Anything malformed is skipped, never thrown. */
	void load(String saved)
	{
		items.clear();
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		JsonElement parsed;
		try
		{
			parsed = new JsonParser().parse(saved);
		}
		catch (RuntimeException e)
		{
			return;
		}
		if (!parsed.isJsonArray())
		{
			return;
		}
		for (JsonElement element : parsed.getAsJsonArray())
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			try
			{
				record(item.get("id").getAsInt(), item.get("quantity").getAsInt());
			}
			catch (RuntimeException ignored)
			{
				// A hand-edited or truncated profile value; skip the entry.
			}
		}
	}
}
