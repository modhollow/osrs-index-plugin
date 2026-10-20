package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import org.junit.Test;

/** The words the Guides view and chat use for a guide, a step, its items, requirements and rewards. */
public class GuideTextTest
{
	private final Guide guide = GuideFixtures.guide();

	@Test
	public void aGuideInTheList()
	{
		assertEquals("5 steps, about 9 h, free to play.", GuidesPanel.about(GuideFixtures.listing().find("beginner")));
		assertEquals("120 steps, about 50 h, members.", GuidesPanel.about(GuideFixtures.listing().find("ironman")));
		assertEquals("45 min", GuidesPanel.duration(45));
	}

	@Test
	public void aStepsLengthAndTags()
	{
		assertEquals("About 10 min. Solo.", GuidesPanel.stepAbout(guide.step("cooks-assistant")));
		assertEquals("Risky: you can die.", GuidesPanel.stepAbout(guide.step("risky-step")));
		assertEquals("Optional, a tag from a later site.", GuidesPanel.stepAbout(guide.step("mining-instructor")));
	}

	@Test
	public void itemsAndRequirements()
	{
		Guide.Step step = guide.step("cooks-assistant");
		assertEquals("Bucket", GuidesPanel.itemText(step.bring.get(0)));
		assertEquals("Bucket x3", GuidesPanel.itemText(new Guide.Item("Bucket", 1925, 3)));
		assertEquals(": in your bank", GuidesPanel.haveText(GuideRules.Have.BANKED, true));
		assertEquals("logged out, nothing is checked", "", GuidesPanel.haveText(GuideRules.Have.MISSING, false));
		assertEquals("Egg for Cook's Assistant, from Chicken farm", GuidesPanel.collectText(step.collect.get(0)));
		assertEquals("Cooking 1 (boostable): met", GuidesPanel.requirementText(step.requires.get(0), GuideRules.Met.MET));

		Guide.Step level = guide.step("cooking-10");
		assertEquals("Quest: Cook's Assistant: not yet",
			GuidesPanel.requirementText(level.requires.get(0), GuideRules.Met.MISSING));
		assertEquals("1 quest point", GuidesPanel.requirementText(level.requires.get(1), GuideRules.Met.UNKNOWN));
		assertEquals("Account: Ironman or Hardcore ironman",
			GuidesPanel.requirementText(level.requires.get(2), GuideRules.Met.UNKNOWN));
		assertEquals("A requirement this version of the plugin cannot check. See it on osrsindex.com.",
			GuidesPanel.requirementText(level.requires.get(3), GuideRules.Met.UNKNOWN));
	}

	@Test
	public void rewards()
	{
		assertEquals("300 Cooking xp, 1 quest point. Unlocks: Use of the cook's range.",
			GuidesPanel.rewardsText(guide.step("cooks-assistant").rewards));
		assertEquals("12,500 coins", GuidesPanel.rewardsText(new Guide.Rewards(Collections.<Guide.Xp>emptyList(), 0, 12_500,
			Collections.<Guide.Item>emptyList(), Collections.<String>emptyList())));
	}

	@Test
	public void theStepBoxCutsLongTextAtAWord()
	{
		Guide.Step step = guide.step("cooks-assistant");
		assertEquals("Help the Lumbridge cook bake a cake.", GuideStepOverlay.text(step, false));
		assertEquals("Fill the bucket yourself at the dairy cow.", GuideStepOverlay.text(step, true));
		assertEquals("no Ironman text: the step's own", "Shear sheep and spin the wool.",
			GuideStepOverlay.text(guide.step("sheep-shearer"), true));

		String words = "word ".repeat(100).trim();
		Guide.Step longStep = new Guide.Step("l", "L", -1, null, words, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), Collections.<String>emptyList(), Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Place>emptyList(), Collections.<Guide.Item>emptyList(), Collections.<Guide.Collect>emptyList(),
			Collections.<Guide.Requirement>emptyList(), Collections.<Guide.Requirement>emptyList(), null);
		String cut = GuideStepOverlay.text(longStep, false);
		assertTrue(cut, cut.length() <= GuideStepOverlay.MAX_TEXT + 3);
		assertTrue(cut, cut.endsWith("word..."));
	}

	@Test
	public void walkThereSaysWhatBecameOfThePlace()
	{
		Guide.Place kitchen = guide.step("cooks-assistant").point();
		assertEquals("Routing to Lumbridge Castle kitchen (3208, 3214, 0) with Shortest Path.",
			GuideTracker.walkLine(RouteDispatcher.Kind.ROUTED, kitchen));
		assertEquals("Shortest Path is not on, so the way to Lumbridge Castle kitchen (3208, 3214, 0) cannot be drawn."
			+ " Install or turn it on, then click Walk there again.",
			GuideTracker.walkLine(RouteDispatcher.Kind.NO_PATHING_PLUGIN, kitchen));
		assertEquals("a label cannot open chat markup", "Routing to a<lt>col=ff0000<gt>b (1, 2, 0) with Shortest Path.",
			GuideTracker.walkLine(RouteDispatcher.Kind.ROUTED, new Guide.Place("a<col=ff0000>b", null, 1, 2, 0)));
	}
}
