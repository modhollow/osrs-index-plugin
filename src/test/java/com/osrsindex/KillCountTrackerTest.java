package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import org.junit.Test;

public class KillCountTrackerTest
{
	@Test
	public void readsTheGamesCountLines()
	{
		KillCountTracker tracker = new KillCountTracker();
		assertTrue(tracker.offer("Your Vorkath kill count is: 1,234."));
		assertTrue(tracker.offer("Your completed Theatre of Blood count is: 5."));
		assertTrue(tracker.offer("Your Barrows chest count is: 50."));
		assertTrue(tracker.offer("Your Corrupted Gauntlet completion count is: 10."));
		assertTrue(tracker.offer("Your subdued Wintertodt count is: 7."));
		assertTrue(tracker.offer("Your Canifis Rooftop lap count is: 40."));

		assertEquals(Integer.valueOf(1_234), tracker.get("Vorkath"));
		assertEquals(Integer.valueOf(5), tracker.get("Theatre of Blood"));
		assertEquals(Integer.valueOf(50), tracker.get("Barrows"));
		assertEquals(Integer.valueOf(10), tracker.get("Corrupted Gauntlet"));
		assertEquals(Integer.valueOf(7), tracker.get("Wintertodt"));
		assertEquals(Integer.valueOf(40), tracker.get("Canifis Rooftop"));
	}

	@Test
	public void ignoresEverythingElse()
	{
		KillCountTracker tracker = new KillCountTracker();
		assertFalse(tracker.offer("Welcome to Old School RuneScape."));
		assertFalse(tracker.offer("Fight duration: 1:23. Personal best: 1:10."));
		assertFalse(tracker.offer(null));
		assertNull(tracker.get("Vorkath"));
	}

	@Test
	public void theSameCountTwiceIsNotAChange()
	{
		KillCountTracker tracker = new KillCountTracker();
		assertTrue(tracker.offer("Your Zulrah kill count is: 3."));
		assertFalse(tracker.offer("Your Zulrah kill count is: 3."));
		assertTrue(tracker.offer("Your Zulrah kill count is: 4."));
	}

	@Test
	public void survivesARoundTripThroughTheProfile()
	{
		KillCountTracker tracker = new KillCountTracker();
		tracker.offer("Your Vorkath kill count is: 12.");
		tracker.offer("Your Zulrah kill count is: 3.");

		KillCountTracker restored = new KillCountTracker();
		restored.load(new Gson().toJson(tracker.toJson()));

		assertEquals(tracker.toJson(), restored.toJson());
	}
}
