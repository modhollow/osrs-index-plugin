package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** What the game can show that a step without {@code completes} or quests is done, and item ids filled by name. */
public class GuideSignsTest
{
	private final Guide guide = GuideFixtures.guide();

	private static Guide.Step step(String title, List<Guide.Place> places, List<Guide.Item> bring)
	{
		return new Guide.Step("s", title, -1, null, null, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), Collections.<String>emptyList(), places, Collections.<Guide.Place>emptyList(),
			bring, Collections.<Guide.Collect>emptyList(), Collections.<Guide.Requirement>emptyList(),
			Collections.<Guide.Requirement>emptyList(), null);
	}

	@Test
	public void aStepTheSiteDescribesOrAQuestStepHasNoSigns()
	{
		assertTrue("completes and quests tick it already", GuideSigns.of(guide.step("cooks-assistant")).none());
		assertTrue("a quest step", GuideSigns.of(guide.step("sheep-shearer")).none());
		assertTrue("completes: a level", GuideSigns.of(guide.step("cooking-10")).none());
		assertTrue("no verb the signs know", GuideSigns.of(step("Learn to fish", Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Item>emptyList())).none());
	}

	@Test
	public void meetingSomeoneIsTalkingToThem()
	{
		GuideSigns signs = GuideSigns.of(guide.step("mining-instructor"));
		assertTrue(signs.talkedTo("Mining Instructor"));
		assertTrue("a dialogue's name can carry colour tags", signs.talkedTo("<col=ffff00>Mining Instructor</col>"));
		assertFalse(signs.talkedTo("Mining tutor"));
		assertFalse(signs.talkedTo(null));
		assertFalse("talking is not killing", signs.isKill("Mining Instructor"));
	}

	@Test
	public void killingWhatTheStepNames()
	{
		GuideSigns goblin = GuideSigns.of(guide.step("risky-step"));
		assertTrue(goblin.isKill("Goblin"));
		assertEquals(1, goblin.kills);
		assertFalse(goblin.isKill("Man"));

		GuideSigns cows = GuideSigns.of(step("Kill 5 cows", Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Item>emptyList()));
		assertTrue(cows.isKill("Cow"));
		assertEquals(5, cows.kills);
	}

	@Test
	public void reachingThePlaceTheStepSendsYouTo()
	{
		GuideSigns signs = GuideSigns.of(step("Go to Draynor Village",
			Collections.singletonList(new Guide.Place("Draynor Village", null, 3093, 3244, 0)),
			Collections.<Guide.Item>emptyList()));
		assertTrue(signs.reached(3093, 3244, 0));
		assertTrue(signs.reached(3093 + GuideSigns.REACH_TILES, 3244 - GuideSigns.REACH_TILES, 0));
		assertFalse(signs.reached(3093 + GuideSigns.REACH_TILES + 1, 3244, 0));
		assertFalse("another plane", signs.reached(3093, 3244, 1));
		assertTrue("a place with no tile cannot be reached", GuideSigns.of(step("Go to the Mining Instructor",
			Collections.singletonList(new Guide.Place("Mining Instructor", null, -1, -1, -1)),
			Collections.<Guide.Item>emptyList())).none());
	}

	@Test
	public void gettingItemsIsHoldingThemAll()
	{
		GuideSigns signs = GuideSigns.of(step("Buy a hammer and a chisel", Collections.<Guide.Place>emptyList(),
			java.util.Arrays.asList(new Guide.Item("Hammer", 2347, 1), new Guide.Item("Chisel", 1755, 1))));
		Map<Integer, Integer> carried = new HashMap<>();
		carried.put(2347, 1);
		assertFalse("one of two", signs.held(new GuideFacts.Builder().carried(carried).build()));
		carried.put(1755, 1);
		assertTrue(signs.held(new GuideFacts.Builder().carried(carried).build()));
		assertFalse("nothing read yet", signs.held(GuideFacts.UNKNOWN));
		assertTrue("an item with no id cannot be held, so the step is ticked by hand", GuideSigns.of(step("Buy a pot",
			Collections.<Guide.Place>emptyList(), Collections.singletonList(new Guide.Item("Pot", -1, 1)))).none());
	}

	@Test
	public void namesMeetTheGamesNames()
	{
		assertEquals("mining instructor", GuideSigns.name("the Mining Instructor"));
		assertEquals("goblin", GuideSigns.name("Goblins"));
		assertEquals("goblin", GuideSigns.name("a goblin"));
		assertEquals("cow", GuideSigns.name("5 cows"));
		assertEquals("boss", GuideSigns.name("Boss"));
		assertEquals("witch", GuideSigns.name("Witches"));
		assertEquals("cook", GuideSigns.name("<col=ffff00>Cook</col>"));
	}

	@Test
	public void itemIdsTheSiteLeftOutAreFilledByName()
	{
		Guide filled = guide.withItemIds(name -> name.equals("Pot") ? 1931 : name.equals("Bucket") ? 9999 : -1);
		Guide.Step step = filled.step("cooks-assistant");
		assertEquals("the site's id is kept", 1925, step.bring.get(0).id);
		assertEquals(1931, step.bring.get(1).id);
		assertEquals("Pot", step.bring.get(1).name);
		assertEquals(1944, step.collect.get(0).item.id);
		assertEquals("progress keys and revision are untouched", guide.revision, filled.revision);
		assertEquals(guide.steps.size(), filled.steps.size());
		assertSame("nothing to fill: the same guide", guide, guide.withItemIds(name -> -1));
		assertNull(guide.step("missing"));
	}
}
