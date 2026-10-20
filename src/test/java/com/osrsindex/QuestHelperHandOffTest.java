package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

/**
 * The hand-off to Quest Helper: its names for the game's quests, and what the plugin does on a quest step before
 * the quest starts, once it has started, once it is finished, and with Quest Helper off.
 */
public class QuestHelperHandOffTest
{
	/** Quest Helper's names that are not the game's (Zoinkwiz/quest-helper v4.18.0, QuestHelperQuest). */
	private static final Set<String> SPECIAL = new HashSet<>(Arrays.asList(
		"Shield of Arrav",
		"Recipe for Disaster - Another Cook's Quest",
		"Recipe for Disaster - Mountain Dwarf",
		"Recipe for Disaster - Wartface & Bentnoze",
		"Recipe for Disaster - Pirate Pete",
		"Recipe for Disaster - Lumbridge Guide",
		"Recipe for Disaster - Evil Dave",
		"Recipe for Disaster - King Awowogei",
		"Recipe for Disaster - Sir Amik Varze",
		"Recipe for Disaster - Skrach Uglogwee",
		"Recipe for Disaster - Culinaromancer"));

	@Test
	public void everyOtherQuestKeepsTheGamesName()
	{
		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			String helper = QuestHelperHandOff.helperName(name, QuestHelperHandOff.Gang.UNKNOWN);
			if (QuestHelperHandOff.NO_HELPER.contains(name))
			{
				assertNull(name, helper);
			}
			else if (!SPECIAL.contains(name))
			{
				assertEquals(name, name, helper);
			}
		}
	}

	@Test
	public void recipeForDisastersPartsAreQuestHelpersRfdNames()
	{
		assertEquals("RFD - Start", QuestHelperHandOff.helperName("Recipe for Disaster - Another Cook's Quest", null));
		assertEquals("RFD - Mountain Dwarf", QuestHelperHandOff.helperName("Recipe for Disaster - Mountain Dwarf", null));
		assertEquals("RFD - Wartface & Bentnoze",
			QuestHelperHandOff.helperName("Recipe for Disaster - Wartface & Bentnoze", null));
		assertEquals("RFD - Finale", QuestHelperHandOff.helperName("Recipe for Disaster - Culinaromancer", null));
		assertEquals("the whole quest: Quest Helper opens its first part", "Recipe for Disaster",
			QuestHelperHandOff.helperName("Recipe for Disaster", null));
		assertEquals("Recipe for Disaster", QuestHelperHandOff.questListName("Recipe for Disaster - Pirate Pete"));
		for (String name : SPECIAL)
		{
			assertTrue(name + " is a game quest", Arrays.stream(Quest.values()).anyMatch(q -> q.getName().equals(name)));
		}
	}

	@Test
	public void shieldOfArravGoesByTheGangThePlayerJoined()
	{
		assertEquals("Shield of Arrav - Phoenix Gang",
			QuestHelperHandOff.helperName("Shield of Arrav", QuestHelperHandOff.Gang.PHOENIX));
		assertEquals("Shield of Arrav - Black Arm Gang",
			QuestHelperHandOff.helperName("Shield of Arrav", QuestHelperHandOff.Gang.BLACK_ARM));
		assertEquals("not joined: the plain name", "Shield of Arrav",
			QuestHelperHandOff.helperName("Shield of Arrav", QuestHelperHandOff.Gang.UNKNOWN));

		assertSame(QuestHelperHandOff.Gang.UNKNOWN, QuestHelperHandOff.Gang.of(0, 0));
		assertSame("the start of the quest counts on the Phoenix varp", QuestHelperHandOff.Gang.UNKNOWN,
			QuestHelperHandOff.Gang.of(4, 0));
		assertSame(QuestHelperHandOff.Gang.PHOENIX, QuestHelperHandOff.Gang.of(8, 0));
		assertSame(QuestHelperHandOff.Gang.BLACK_ARM, QuestHelperHandOff.Gang.of(4, 1));
	}

	@Test
	public void noHelperOrNotAGameQuestIsOurs()
	{
		assertNull(QuestHelperHandOff.helperName("Learning the Ropes", null));
		assertNull(QuestHelperHandOff.helperName("Into the Tombs", null));
		assertNull(QuestHelperHandOff.helperName("The Frozen Door", null));
		assertNull(QuestHelperHandOff.helperName("Cooks Assistant", null));
		assertNull(QuestHelperHandOff.helperName(null, null));
	}

	@Test
	public void beforeTheQuestStartsQuestHelperGuidesItAndOurPointLeadsToTheStart()
	{
		Guide.Step step = questStep("Cook's Assistant");
		QuestHelperHandOff handOff = QuestHelperHandOff.of(step, true, true, QuestHelperHandOff.Gang.UNKNOWN,
			facts("Cook's Assistant", QuestState.NOT_STARTED));
		assertSame(QuestHelperHandOff.Stage.BEFORE_START, handOff.stage);
		assertEquals("Cook's Assistant", handOff.helperName);
		assertTrue(handOff.ownsThePlace());
		assertTrue(handOff.panelText().contains("starts by itself when you start Cook's Assistant"));
		assertTrue("the manual way stays on screen", handOff.panelText().contains("right-click Cook's Assistant"));

		QuestHelperHandOff manual = QuestHelperHandOff.of(step, true, false, QuestHelperHandOff.Gang.UNKNOWN,
			facts("Cook's Assistant", QuestState.NOT_STARTED));
		assertFalse(manual.panelText().contains("by itself"));
		assertTrue(manual.panelText().contains("open the Quest List, right-click Cook's Assistant and pick Start Quest"
			+ " Helper."));
		assertSame("not read yet counts as not started", QuestHelperHandOff.Stage.BEFORE_START,
			QuestHelperHandOff.of(step, true, true, QuestHelperHandOff.Gang.UNKNOWN, GuideFacts.UNKNOWN).stage);
	}

	@Test
	public void onceStartedOurPointAndArrowStandDown()
	{
		QuestHelperHandOff handOff = QuestHelperHandOff.of(questStep("Cook's Assistant"), true, true,
			QuestHelperHandOff.Gang.UNKNOWN, facts("Cook's Assistant", QuestState.IN_PROGRESS));
		assertSame(QuestHelperHandOff.Stage.STARTED, handOff.stage);
		assertFalse(handOff.ownsThePlace());
		assertTrue(handOff.panelText().contains("moves on by itself when the quest is finished"));
	}

	@Test
	public void finishedMeansTheStepIsDoneAndTheGuideMovesOn()
	{
		Guide.Step step = questStep("Cook's Assistant");
		GuideFacts finished = facts("Cook's Assistant", QuestState.FINISHED);
		assertSame(QuestHelperHandOff.Stage.FINISHED,
			QuestHelperHandOff.of(step, true, true, QuestHelperHandOff.Gang.UNKNOWN, finished).stage);
		assertTrue(GuideRules.completed(step, finished));
	}

	@Test
	public void withQuestHelperOffOrNoHelperTheStepIsOurs()
	{
		GuideFacts started = facts("Cook's Assistant", QuestState.IN_PROGRESS);
		QuestHelperHandOff off = QuestHelperHandOff.of(questStep("Cook's Assistant"), false, true,
			QuestHelperHandOff.Gang.UNKNOWN, started);
		assertSame(QuestHelperHandOff.Stage.OURS, off.stage);
		assertTrue("we keep pointing the way", off.ownsThePlace());
		assertNull(off.panelText());
		assertSame(QuestHelperHandOff.Stage.OURS, QuestHelperHandOff.of(questStep("Learning the Ropes"), true, true,
			QuestHelperHandOff.Gang.UNKNOWN, facts("Learning the Ropes", QuestState.IN_PROGRESS)).stage);
		assertSame("no quest", QuestHelperHandOff.Stage.OURS, QuestHelperHandOff.of(questStep(), true, true,
			QuestHelperHandOff.Gang.UNKNOWN, started).stage);
	}

	@Test
	public void aTwoQuestStepGoesOneQuestAtATime()
	{
		Guide.Step step = questStep("Imp Catcher", "Witch's Potion");
		GuideFacts first = new GuideFacts.Builder().quest("Imp Catcher", QuestState.FINISHED)
			.quest("Witch's Potion", QuestState.NOT_STARTED).build();
		QuestHelperHandOff handOff = QuestHelperHandOff.of(step, true, true, QuestHelperHandOff.Gang.UNKNOWN, first);
		assertSame(QuestHelperHandOff.Stage.BEFORE_START, handOff.stage);
		assertEquals("Witch's Potion", handOff.quest);
		assertFalse("one quest is not the step", GuideRules.completed(step, first));
	}

	@Test
	public void followWithQuestHelperPostsTheProposedStartMessage()
	{
		PluginMessage message = QuestHelperHandOff.startMessage("RFD - Mountain Dwarf");
		assertEquals("questhelper", message.getNamespace());
		assertEquals("start", message.getName());
		assertEquals("RFD - Mountain Dwarf", message.getData().get("quest"));
		assertEquals("OSRS Index", message.getData().get("source"));
		assertEquals(2, message.getData().size());
	}

	@Test
	public void questHelpersSelectedMessageAndAutoStartSetting()
	{
		assertEquals("Cook's Assistant", QuestHelperHandOff.selected(new PluginMessage("questhelper", "selected",
			Collections.<String, Object>singletonMap("quest", "Cook's Assistant"))));
		assertNull("cleared", QuestHelperHandOff.selected(new PluginMessage("questhelper", "selected",
			Collections.<String, Object>emptyMap())));
		assertTrue("absent is its default, on", QuestHelperHandOff.autoStart(null));
		assertTrue(QuestHelperHandOff.autoStart("true"));
		assertFalse(QuestHelperHandOff.autoStart("false"));
	}

	@Test
	public void theManualWayNamesTheGangOrPartOption()
	{
		QuestHelperHandOff handOff = QuestHelperHandOff.of(questStep("Recipe for Disaster - Mountain Dwarf"), true, false,
			QuestHelperHandOff.Gang.UNKNOWN, GuideFacts.UNKNOWN);
		assertTrue(handOff.panelText(), handOff.panelText().contains(
			"right-click Recipe for Disaster and pick Start Quest Helper for RFD - Mountain Dwarf."));
	}

	private static GuideFacts facts(String quest, QuestState state)
	{
		return new GuideFacts.Builder().quest(quest, state).build();
	}

	private static Guide.Step questStep(String... quests)
	{
		return new Guide.Step("q", "Quest", -1, null, null, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), Arrays.asList(quests), Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Place>emptyList(), Collections.<Guide.Item>emptyList(), Collections.<Guide.Collect>emptyList(),
			Collections.<Guide.Requirement>emptyList(), Collections.<Guide.Requirement>emptyList(), null);
	}
}
