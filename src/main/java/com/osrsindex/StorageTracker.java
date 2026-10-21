package com.osrsindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.InventoryID;

/**
 * Item stores the client can read only while they are open or checked, as with the bank. The last
 * copy of each is kept in the RuneLite profile, so a sync always carries every store seen so far.
 */
final class StorageTracker
{
	/** Store key -> the container id it is read from. */
	static final Map<String, Integer> STORES = new LinkedHashMap<>();

	static
	{
		STORES.put("looting_bag", InventoryID.LOOTING_BAG);
		STORES.put("seed_vault", InventoryID.SEED_VAULT);
		STORES.put("group_storage", InventoryID.INV_GROUP_TEMP);
		STORES.put("quiver", InventoryID.DIZANAS_QUIVER_AMMO);
		STORES.put("poh_costumes", InventoryID.POH_COSTUMES);
		STORES.put("forestry_kit", InventoryID.FORESTRY_KIT);
		STORES.put("huntsmans_kit", InventoryID.HUNTSMANS_KIT);
	}

	private final Map<String, JsonArray> stores = new LinkedHashMap<>();

	/** The store key for a container id, or null when it is not one of ours. */
	static String keyFor(int containerId)
	{
		for (Map.Entry<String, Integer> entry : STORES.entrySet())
		{
			if (entry.getValue() == containerId)
			{
				return entry.getKey();
			}
		}
		return null;
	}

	/** Returns true when the store changed. */
	boolean put(String key, JsonArray items)
	{
		if (key == null || items == null || items.equals(stores.get(key)))
		{
			return false;
		}
		stores.put(key, items);
		return true;
	}

	int size()
	{
		return stores.size();
	}

	void clear()
	{
		stores.clear();
	}

	JsonObject toJson()
	{
		JsonObject out = new JsonObject();
		stores.forEach(out::add);
		return out;
	}

	void load(String saved)
	{
		stores.clear();
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
				if (STORES.containsKey(entry.getKey()) && entry.getValue().isJsonArray())
				{
					stores.put(entry.getKey(), items(entry.getValue().getAsJsonArray()));
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// A malformed profile value loads as empty.
		}
	}

	/**
	 * A saved store's items as the reader writes them, and nothing else: an edited or damaged profile cannot put
	 * anything else into the sync.
	 */
	static JsonArray items(JsonArray saved)
	{
		JsonArray out = new JsonArray();
		for (JsonElement element : saved)
		{
			if (out.size() >= CharacterReader.MAX_BANK_ITEMS)
			{
				break;
			}
			if (!element.isJsonObject())
			{
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			JsonElement id = item.get("id");
			JsonElement quantity = item.get("quantity");
			if (id == null || quantity == null || !id.isJsonPrimitive() || !quantity.isJsonPrimitive()
				|| !id.getAsJsonPrimitive().isNumber() || !quantity.getAsJsonPrimitive().isNumber())
			{
				continue;
			}
			double idValue = id.getAsDouble();
			double quantityValue = quantity.getAsDouble();
			if (idValue >= 0 && idValue <= CharacterReader.MAX_ITEM_ID && idValue == Math.rint(idValue)
				&& quantityValue > 0 && quantityValue <= Integer.MAX_VALUE && quantityValue == Math.rint(quantityValue))
			{
				out.add(CharacterReader.itemJson((int) idValue, (int) quantityValue));
			}
		}
		return out;
	}
}
