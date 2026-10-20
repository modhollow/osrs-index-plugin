package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Progress as the RuneLite profile keeps it: marks round-trip, and a damaged value starts afresh, never fails. */
public class GuideProgressTest
{
	@Test
	public void marksRoundTripThroughTheProfile()
	{
		GuideProgress progress = new GuideProgress();
		assertTrue(progress.set("a", GuideProgress.Mark.DONE));
		assertTrue(progress.set("b", GuideProgress.Mark.SKIPPED));
		assertFalse("the same mark again changes nothing", progress.set("a", GuideProgress.Mark.DONE));
		String saved = progress.toJson().toString();
		assertEquals("{\"a\":\"done\",\"b\":\"skipped\"}", saved);

		GuideProgress loaded = GuideProgress.load(saved);
		assertSame(GuideProgress.Mark.DONE, loaded.mark("a"));
		assertSame(GuideProgress.Mark.SKIPPED, loaded.mark("b"));
		assertNull(loaded.mark("c"));
	}

	@Test
	public void notDoneAndTheStartStepRoundTripToo()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("mining-instructor", GuideProgress.Mark.OPEN);
		assertTrue(progress.start("cooking-10"));
		assertFalse("the same start again changes nothing", progress.start("cooking-10"));
		String saved = progress.toJson().toString();
		assertEquals("{\"mining-instructor\":\"open\",\"@start\":\"cooking-10\"}", saved);

		GuideProgress loaded = GuideProgress.load(saved);
		assertSame(GuideProgress.Mark.OPEN, loaded.mark("mining-instructor"));
		assertEquals("cooking-10", loaded.start());
		assertEquals("the start is not a mark", 1, loaded.size());
		assertEquals("cooking-10", loaded.copy().start());
		assertTrue(loaded.start(null));
		assertNull(loaded.start());
	}

	@Test
	public void reopeningClearsTheMark()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("a", GuideProgress.Mark.DONE);
		assertTrue(progress.set("a", null));
		assertNull(progress.mark("a"));
		assertFalse(progress.set("a", null));
	}

	@Test
	public void aDamagedValueStartsAfresh()
	{
		assertEquals(0, GuideProgress.load(null).size());
		assertEquals(0, GuideProgress.load("").size());
		assertEquals(0, GuideProgress.load("not json {").size());
		assertEquals(0, GuideProgress.load("[1,2]").size());
		GuideProgress partly = GuideProgress.load("{\"a\":\"done\",\"b\":\"half\",\"c\":7,\"d\":\"skipped\"}");
		assertEquals(2, partly.size());
		assertSame(GuideProgress.Mark.SKIPPED, partly.mark("d"));
	}

	@Test
	public void aCopyIsItsOwn()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("a", GuideProgress.Mark.DONE);
		GuideProgress copy = progress.copy();
		progress.set("a", null);
		assertSame(GuideProgress.Mark.DONE, copy.mark("a"));
	}
}
