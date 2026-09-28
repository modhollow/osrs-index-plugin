package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

public class CollectionLogTrackerTest
{
	@Test
	public void recordsObtainedItemsAndIgnoresUnobtainedOnes()
	{
		CollectionLogTracker tracker = new CollectionLogTracker();
		assertTrue(tracker.record(4151, 3));
		assertFalse(tracker.record(11286, 0));
		assertFalse(tracker.record(4151, 3));
		assertTrue(tracker.record(4151, 4));
		assertEquals(1, tracker.size());
	}

	@Test
	public void survivesARoundTripThroughTheProfile()
	{
		CollectionLogTracker tracker = new CollectionLogTracker();
		tracker.record(4151, 3);
		tracker.record(11286, 1);
		String saved = new Gson().toJson(tracker.toJson());

		CollectionLogTracker restored = new CollectionLogTracker();
		restored.load(saved);

		assertEquals(tracker.toJson(), restored.toJson());
	}

	@Test
	public void aMalformedSavedValueLoadsAsEmptyInsteadOfThrowing()
	{
		CollectionLogTracker tracker = new CollectionLogTracker();
		tracker.load("{not json");
		assertEquals(0, tracker.size());
		tracker.load("[{\"id\":\"x\"},{\"id\":4151,\"quantity\":2}]");
		assertEquals(1, tracker.size());
	}

	@Test
	public void stopsAtTheContractCap()
	{
		CollectionLogTracker tracker = new CollectionLogTracker();
		for (int id = 0; id < CollectionLogTracker.MAX_ITEMS + 10; id++)
		{
			tracker.record(id, 1);
		}
		assertEquals(CollectionLogTracker.MAX_ITEMS, tracker.size());
	}
}
