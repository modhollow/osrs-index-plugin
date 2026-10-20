package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;

/**
 * The guide rules: requirements met, missing or unknown; each {@code completes} kind; the step status; the
 * "N/total" count with skipped steps left out; the current step; and items carried, banked or missing.
 */
public class GuideRulesTest
{
	private final Guide guide = GuideFixtures.guide();

	private static GuideFacts.Builder character()
	{
		GuideFacts.Builder builder = new GuideFacts.Builder()
			.questPoints(0)
			.accountType("normal");
		for (Skill skill : Skill.values())
		{
			builder.level(skill, 1, 1);
		}
		return builder;
	}

	private static Guide.Requirement skill(String skill, int level, boolean boostable)
	{
		return new Guide.Requirement(Guide.Requirement.Kind.SKILL, skill, level, boostable, null, 0,
			Collections.<String>emptyList());
	}

	private static Guide.Requirement quest(String quest)
	{
		return new Guide.Requirement(Guide.Requirement.Kind.QUEST, null, 0, false, quest, 0, Collections.<String>emptyList());
	}

	private static Guide.Requirement questPoints(int points)
	{
		return new Guide.Requirement(Guide.Requirement.Kind.QUEST_POINTS, null, 0, false, null, points,
			Collections.<String>emptyList());
	}

	private static Guide.Requirement mode(String... modes)
	{
		return new Guide.Requirement(Guide.Requirement.Kind.MODE, null, 0, false, null, 0, Arrays.asList(modes));
	}

	@Test
	public void aSkillUsesTheRealLevelAndABoostableOneIsMetWithinFive()
	{
		GuideFacts facts = character().level(Skill.COOKING, 40, 45).build();
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(skill("cooking", 40, false), facts));
		assertEquals("a boost shown on the stats does not count unless boostable", GuideRules.Met.MISSING,
			GuideRules.requirement(skill("cooking", 41, false), facts));
		assertEquals(GuideRules.Met.BOOST, GuideRules.requirement(skill("cooking", 45, true), facts));
		assertTrue(GuideRules.Met.BOOST.met());
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(skill("cooking", 46, true), facts));
		assertEquals("a skill the game does not have", GuideRules.Met.UNKNOWN,
			GuideRules.requirement(skill("sorcery", 1, false), facts));
		assertEquals(GuideRules.Met.MET,
			GuideRules.requirement(skill("runecrafting", 1, false), character().build()));
	}

	@Test
	public void questQuestPointsAndAccountType()
	{
		GuideFacts facts = character().quest("Cook's Assistant", QuestState.FINISHED)
			.quest("Sheep Shearer", QuestState.IN_PROGRESS).questPoints(12).accountType("hardcore_ironman").build();
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(quest("Cook's Assistant"), facts));
		assertEquals("started is not finished", GuideRules.Met.MISSING, GuideRules.requirement(quest("Sheep Shearer"), facts));
		assertEquals("not read", GuideRules.Met.UNKNOWN, GuideRules.requirement(quest("Dragon Slayer I"), facts));
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(questPoints(12), facts));
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(questPoints(13), facts));
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(mode("ironman", "hardcore_ironman"), facts));
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(mode("ironman"), facts));
		assertTrue(facts.ironman());
		assertFalse(character().build().ironman());
	}

	@Test
	public void loggedOutEverythingIsUnknown()
	{
		GuideFacts unknown = GuideFacts.UNKNOWN;
		assertEquals(GuideRules.Met.UNKNOWN, GuideRules.requirement(skill("cooking", 1, false), unknown));
		assertEquals(GuideRules.Met.UNKNOWN, GuideRules.requirement(quest("Cook's Assistant"), unknown));
		assertEquals(GuideRules.Met.UNKNOWN, GuideRules.requirement(questPoints(0), unknown));
		assertEquals(GuideRules.Met.UNKNOWN, GuideRules.requirement(mode("normal"), unknown));
		for (Guide.Step step : guide.steps)
		{
			assertFalse(step.id, GuideRules.completed(step, unknown));
		}
	}

	@Test
	public void eachCompletesKindTicksTheStep()
	{
		Guide.Step quest = guide.step("cooks-assistant");
		assertFalse(GuideRules.completed(quest, character().quest("Cook's Assistant", QuestState.IN_PROGRESS).build()));
		assertTrue(GuideRules.completed(quest, character().quest("Cook's Assistant", QuestState.FINISHED).build()));

		Guide.Step level = guide.step("cooking-10");
		assertFalse(GuideRules.completed(level, character().level(Skill.COOKING, 9, 12).build()));
		assertTrue(GuideRules.completed(level, character().level(Skill.COOKING, 10, 10).build()));

		Guide.Step points = stepCompleting(questPoints(32));
		assertFalse(GuideRules.completed(points, character().questPoints(31).build()));
		assertTrue(GuideRules.completed(points, character().questPoints(32).build()));

		Guide.Step account = stepCompleting(mode("ironman"));
		assertFalse(GuideRules.completed(account, character().build()));
		assertTrue(GuideRules.completed(account, character().accountType("ironman").build()));

		Guide.Step both = stepCompleting(questPoints(1), skill("attack", 5, false));
		assertFalse("every entry must be met", GuideRules.completed(both, character().questPoints(1).build()));
		assertFalse("a boostable entry still needs the real level to finish a step",
			GuideRules.completed(stepCompleting(skill("attack", 5, true)), character().level(Skill.ATTACK, 4, 4).build()));
		assertFalse("a kind this version does not know never ticks",
			GuideRules.completed(stepCompleting(new Guide.Requirement(Guide.Requirement.Kind.UNKNOWN, null, 0, false, null, 0,
				Collections.<String>emptyList())), character().build()));
		assertFalse("nothing to complete: only the player ticks it", GuideRules.completed(guide.step("risky-step"),
			character().build()));
	}

	@Test
	public void aQuestStepWithoutCompletesIsDoneWhenItsQuestsAreFinished()
	{
		Guide.Step step = guide.step("sheep-shearer");
		assertTrue(step.completes.isEmpty());
		assertFalse(GuideRules.completed(step, character().quest("Sheep Shearer", QuestState.IN_PROGRESS).build()));
		assertTrue(GuideRules.completed(step, character().quest("Sheep Shearer", QuestState.FINISHED).build()));
	}

	@Test
	public void skippedWinsThenDoneThenOpen()
	{
		Guide.Step step = guide.step("cooks-assistant");
		GuideFacts finished = character().quest("Cook's Assistant", QuestState.FINISHED).build();
		GuideProgress progress = new GuideProgress();
		assertEquals(GuideRules.Status.OPEN, GuideRules.status(step, progress, character().build()));
		assertEquals("ticked by the character", GuideRules.Status.DONE, GuideRules.status(step, progress, finished));
		progress.set(step.id, GuideProgress.Mark.DONE);
		assertEquals(GuideRules.Status.DONE, GuideRules.status(step, progress, GuideFacts.UNKNOWN));
		progress.set(step.id, GuideProgress.Mark.SKIPPED);
		assertEquals(GuideRules.Status.SKIPPED, GuideRules.status(step, progress, finished));
	}

	@Test
	public void theCountLeavesSkippedStepsOutAndTheCurrentStepIsTheFirstOpenOneAfterThePlayersPlace()
	{
		GuideProgress progress = new GuideProgress();
		GuideRules.Count fresh = GuideRules.count(guide, progress, character().build());
		assertEquals("0/5", fresh.toString());
		assertEquals("a new player starts at the first step", "cooks-assistant", fresh.current.id);

		progress.set("cooks-assistant", GuideProgress.Mark.DONE);
		progress.set("sheep-shearer", GuideProgress.Mark.SKIPPED);
		GuideRules.Count count = GuideRules.count(guide, progress, character().level(Skill.COOKING, 10, 10).build());
		assertEquals("done, ticked, and the Mining Instructor passed, out of the four not skipped", "3/4", count.toString());
		assertEquals("risky-step", count.current.id);

		for (Guide.Step step : guide.steps)
		{
			progress.set(step.id, GuideProgress.Mark.DONE);
		}
		GuideRules.Count complete = GuideRules.count(guide, progress, character().build());
		assertEquals("5/5", complete.toString());
		assertNull(complete.current);
	}

	private static Guide.Step step(String id, List<String> quests, List<Guide.Collect> collect)
	{
		return new Guide.Step(id, id, -1, null, null, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), quests, Collections.<Guide.Place>emptyList(), Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Item>emptyList(), collect, Collections.<Guide.Requirement>emptyList(),
			Collections.<Guide.Requirement>emptyList(), null);
	}

	private static Guide.Step questStep(String id, String quest)
	{
		return step(id, Collections.singletonList(quest), Collections.<Guide.Collect>emptyList());
	}

	private static Guide.Step plainStep(String id)
	{
		return step(id, Collections.<String>emptyList(), Collections.<Guide.Collect>emptyList());
	}

	/** Two sections: five quests and a walk, then two quests around a walk. */
	private static Guide route()
	{
		return new Guide("route", "Route", null, null, "r", null, Arrays.asList(
			new Guide.Section("a", "A", null, Arrays.asList(questStep("a1", "Q1"), questStep("a2", "Q2"), plainStep("a3"),
				questStep("a4", "Q3"), questStep("a5", "Q4"), questStep("a6", "Q5"))),
			new Guide.Section("b", "B", null, Arrays.asList(questStep("b1", "Q6"), plainStep("b2"),
				questStep("b3", "Q7")))));
	}

	@Test
	public void theFirstSectionNotMostlyDoneIsWhereThePlayerIs()
	{
		Guide route = route();
		// Four of the first section's five quests, and one far ahead, out of the guide's order.
		GuideFacts facts = character().quest("Q1", QuestState.FINISHED).quest("Q2", QuestState.FINISHED)
			.quest("Q3", QuestState.FINISHED).quest("Q4", QuestState.NOT_STARTED).quest("Q5", QuestState.FINISHED)
			.quest("Q6", QuestState.NOT_STARTED).quest("Q7", QuestState.FINISHED).build();
		GuideRules.Standing standing = GuideRules.standing(route, new GuideProgress(), facts);
		assertEquals("nothing to check, and the player is past it", GuideRules.Status.PASSED, standing.of(route.step("a3")));
		assertEquals("a quest the player never did", GuideRules.Status.BEHIND, standing.of(route.step("a5")));
		assertEquals("b1", standing.count.current.id);
		assertEquals("after the player's place, a walk is still to do", GuideRules.Status.OPEN, standing.of(route.step("b2")));
		assertEquals("a quest done far ahead stays done, and does not move the place", GuideRules.Status.DONE,
			standing.of(route.step("b3")));
		assertEquals("6/9", standing.count.toString());
		assertEquals(Collections.singletonList(route.step("a5")), standing.behind);

		GuideProgress notDone = new GuideProgress();
		notDone.set("a3", GuideProgress.Mark.OPEN);
		GuideRules.Standing said = GuideRules.standing(route, notDone, facts);
		assertEquals("the player said not done", GuideRules.Status.BEHIND, said.of(route.step("a3")));
		assertEquals("5/9", said.count.toString());
	}

	@Test
	public void aDoneStepFarAheadDoesNotMoveThePlayersPlace()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("risky-step", GuideProgress.Mark.DONE);
		GuideRules.Standing standing = GuideRules.standing(guide, progress, character().build());
		assertEquals("cooks-assistant", standing.count.current.id);
		assertTrue(standing.behind.isEmpty());
		assertEquals(GuideRules.Status.OPEN, standing.of(guide.step("mining-instructor")));
		assertEquals(GuideRules.Status.DONE, standing.of(guide.step("risky-step")));
		assertEquals("1/5", standing.count.toString());
	}

	@Test
	public void aPickUpStepIsDoneOnceItsQuestsAreFinishedOrItsUntradeableItemIsOwned()
	{
		Guide.Step fetch = step("fetch", Collections.<String>emptyList(), Collections.singletonList(
			new Guide.Collect(new Guide.Item("Rope", 954, 1), Collections.singletonList("Q9"), "General store")));
		assertFalse(GuideRules.completed(fetch, character().quest("Q9", QuestState.IN_PROGRESS).build()));
		assertTrue("fetched and used", GuideRules.completed(fetch, character().quest("Q9", QuestState.FINISHED).build()));
		assertTrue("its quests are read", GuideRules.quests(fetch).contains("Q9"));

		Map<Integer, Integer> amulet = new HashMap<>();
		amulet.put(552, 1);
		Guide.Step bound = step("bound", Collections.<String>emptyList(), Collections.singletonList(
			new Guide.Collect(new Guide.Item("Ghostspeak amulet", 552, 1, true), Collections.<String>emptyList(), null)));
		assertTrue("an untradeable item only this step gives", GuideRules.completed(bound,
			character().carried(amulet).build()));
		assertTrue("banked counts once the bank is seen", GuideRules.completed(bound,
			character().carried(Collections.<Integer, Integer>emptyMap()).banked(amulet).build()));
		assertFalse(GuideRules.completed(bound, character().carried(Collections.<Integer, Integer>emptyMap()).build()));

		Map<Integer, Integer> rope = new HashMap<>();
		rope.put(954, 1);
		assertFalse("a tradeable item proves nothing", GuideRules.completed(fetch, character().carried(rope).build()));
	}

	@Test
	public void startHereMovesThePlayersPlaceToTheStepBefore()
	{
		GuideProgress progress = new GuideProgress();
		progress.start("cooking-10");
		GuideRules.Standing standing = GuideRules.standing(guide, progress, character().build());
		assertEquals("cooking-10", standing.count.current.id);
		assertEquals(GuideRules.Status.PASSED, standing.of(guide.step("mining-instructor")));
		assertEquals(GuideRules.Status.BEHIND, standing.of(guide.step("cooks-assistant")));
		assertEquals("1/5", standing.count.toString());

		progress.set("risky-step", GuideProgress.Mark.DONE);
		assertEquals("a step done far ahead leaves the start where it is", "cooking-10",
			GuideRules.count(guide, progress, character().build()).current.id);
	}

	@Test
	public void aNewPlayerHasNothingBehind()
	{
		GuideRules.Standing standing = GuideRules.standing(guide, new GuideProgress(), character().build());
		assertTrue(standing.behind.isEmpty());
		for (Guide.Step step : guide.steps)
		{
			assertEquals(GuideRules.Status.OPEN, standing.of(step));
		}
	}

	@Test
	public void marksForStepsTheGuideNoLongerHasAreKeptButNotCounted()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("a-step-the-site-removed", GuideProgress.Mark.DONE);
		progress.set("cooks-assistant", GuideProgress.Mark.DONE);
		assertEquals("1/5", GuideRules.count(guide, progress, character().build()).toString());
		assertSame(GuideProgress.Mark.DONE, GuideProgress.load(progress.toJson().toString()).mark("a-step-the-site-removed"));
	}

	@Test
	public void tickingStoresDoneStepsAndLeavesSkippedOnesSkipped()
	{
		GuideProgress progress = new GuideProgress();
		progress.set("cooking-10", GuideProgress.Mark.SKIPPED);
		GuideFacts facts = character().quest("Cook's Assistant", QuestState.FINISHED).level(Skill.COOKING, 20, 20).build();
		assertTrue(GuideRules.tickCompleted(guide, progress, facts));
		assertSame(GuideProgress.Mark.DONE, progress.mark("cooks-assistant"));
		assertSame(GuideProgress.Mark.SKIPPED, progress.mark("cooking-10"));
		assertFalse("nothing more to tick", GuideRules.tickCompleted(guide, progress, facts));
	}

	@Test
	public void anItemIsCarriedBankedMissingOrUnchecked()
	{
		Guide.Item bucket = new Guide.Item("Bucket", 1925, 2);
		Map<Integer, Integer> one = new HashMap<>();
		one.put(1925, 1);
		Map<Integer, Integer> two = new HashMap<>();
		two.put(1925, 2);
		assertEquals(GuideRules.Have.CARRIED, GuideRules.item(bucket, character().carried(two).build()));
		assertEquals("the bank was not open since login", GuideRules.Have.BANK_UNSEEN,
			GuideRules.item(bucket, character().carried(one).build()));
		assertEquals("one carried and one banked", GuideRules.Have.BANKED,
			GuideRules.item(bucket, character().carried(one).banked(one).build()));
		assertEquals(GuideRules.Have.MISSING, GuideRules.item(bucket, character().banked(one).build()));
		assertEquals(GuideRules.Have.NO_ID, GuideRules.item(new Guide.Item("Pot", -1, 1), character().build()));
	}

	@Test
	public void theQuestsToReadAreEveryOneTheGuideNames()
	{
		assertEquals(new LinkedHashSet<>(Arrays.asList("Cook's Assistant", "Sheep Shearer")), GuideRules.quests(guide));
	}

	private static Guide.Step stepCompleting(Guide.Requirement... completes)
	{
		return new Guide.Step("s", "S", -1, null, null, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), Collections.<String>emptyList(), Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Place>emptyList(), Collections.<Guide.Item>emptyList(), Collections.<Guide.Collect>emptyList(),
			Collections.<Guide.Requirement>emptyList(), Arrays.asList(completes), null);
	}
}
