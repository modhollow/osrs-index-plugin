package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

public class StorageTrackerTest
{
	private static JsonArray items(int... idQuantityPairs)
	{
		JsonArray out = new JsonArray();
		for (int i = 0; i < idQuantityPairs.length; i += 2)
		{
			out.add(CharacterReader.itemJson(idQuantityPairs[i], idQuantityPairs[i + 1]));
		}
		return out;
	}

	@Test
	public void mapsContainersToStoreKeys()
	{
		assertEquals("looting_bag", StorageTracker.keyFor(InventoryID.LOOTING_BAG));
		assertEquals("group_storage", StorageTracker.keyFor(InventoryID.INV_GROUP_TEMP));
		assertNull(StorageTracker.keyFor(InventoryID.BANK));
	}

	@Test
	public void onlyAChangedStoreCounts()
	{
		StorageTracker tracker = new StorageTracker();
		assertTrue(tracker.put("seed_vault", items(5295, 10)));
		assertFalse(tracker.put("seed_vault", items(5295, 10)));
		assertTrue(tracker.put("seed_vault", items(5295, 11)));
	}

	@Test
	public void survivesARoundTripAndDropsUnknownStores()
	{
		StorageTracker tracker = new StorageTracker();
		tracker.put("looting_bag", items(995, 5_000));
		com.google.gson.JsonObject withJunk = tracker.toJson();
		withJunk.add("not_a_store", new JsonArray());
		String saved = new Gson().toJson(withJunk);

		StorageTracker restored = new StorageTracker();
		restored.load(saved);

		assertEquals(tracker.toJson(), restored.toJson());
	}
}
