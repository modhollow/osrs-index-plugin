package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Reading the site's guide routes: every field of the fixture, fields from a later site ignored, an unknown
 * {@code version} refused as "update the plugin", and anything unsafe or unusable dropped.
 */
public class GuideParserTest
{
	@Test
	public void theGuideReadsInTheSitesOrder()
	{
		GuideParser.Result<Guide> result = GuideParser.guide(GuideFixtures.read(GuideFixtures.GUIDE));
		assertEquals(GuideParser.Problem.NONE, result.problem);
		Guide guide = result.value;
		assertEquals("beginner", guide.id);
		assertEquals("Beginner walkthrough", guide.title);
		assertEquals("Sample attribution line.", guide.attribution);
		assertEquals("3f2a9c1e", guide.revision);
		assertNull("null modes: any account type", guide.modes);
		assertEquals(2, guide.sections.size());
		assertEquals("Lumbridge", guide.sections.get(0).title);
		assertEquals(Arrays.asList("cooks-assistant", "sheep-shearer", "mining-instructor", "cooking-10", "risky-step"),
			ids(guide));
	}

	@Test
	public void aStepCarriesEveryField()
	{
		Guide.Step step = GuideFixtures.guide().step("cooks-assistant");
		assertEquals("Cook's Assistant", step.title);
		assertEquals(10, step.minutes);
		assertEquals("https://osrsindex.com/guides/beginner/cooks-assistant", step.url);
		assertEquals("Help the Lumbridge cook bake a cake.", step.text);
		assertEquals(2, step.how.size());
		assertEquals("The dairy cow is north of the castle.", step.tip);
		assertEquals("Fill the bucket yourself at the dairy cow.", step.ironman);
		assertEquals(Collections.singleton("solo"), step.tags);
		assertEquals(Collections.singletonList("Cook's Assistant"), step.quests);

		assertEquals(2, step.places.size());
		Guide.Place kitchen = step.places.get(0);
		assertTrue(kitchen.hasPoint());
		assertEquals(3208, kitchen.x);
		assertEquals(3214, kitchen.y);
		assertEquals(0, kitchen.plane);
		assertFalse("a place with only a label", step.places.get(1).hasPoint());
		assertEquals("Anvils", step.places.get(1).label);
		assertEquals(kitchen, step.point());

		assertEquals("teleport", step.travel.get(0).by);
		assertEquals(1925, step.bring.get(0).id);
		assertEquals("an item without an id is shown by name", -1, step.bring.get(1).id);
		assertEquals("Egg", step.collect.get(0).item.name);
		assertEquals(Collections.singletonList("Cook's Assistant"), step.collect.get(0).forQuests);
		assertEquals("Chicken farm", step.collect.get(0).from);

		Guide.Requirement cooking = step.requires.get(0);
		assertEquals(Guide.Requirement.Kind.SKILL, cooking.kind);
		assertEquals("cooking", cooking.skill);
		assertEquals(1, cooking.level);
		assertTrue(cooking.boostable);
		assertEquals(Guide.Requirement.Kind.QUEST, step.completes.get(0).kind);
		assertEquals("Cook's Assistant", step.completes.get(0).quest);

		assertEquals(300, step.rewards.xp.get(0).amount);
		assertEquals(1, step.rewards.questPoints);
		assertEquals(Collections.singletonList("Use of the cook's range."), step.rewards.unlocks);
	}

	@Test
	public void everyRequirementKindAndOneFromALaterSite()
	{
		Guide.Step step = GuideFixtures.guide().step("cooking-10");
		assertEquals(Guide.Requirement.Kind.QUEST, step.requires.get(0).kind);
		assertEquals(Guide.Requirement.Kind.QUEST_POINTS, step.requires.get(1).kind);
		assertEquals(1, step.requires.get(1).points);
		assertEquals(Guide.Requirement.Kind.MODE, step.requires.get(2).kind);
		assertEquals(Arrays.asList("ironman", "hardcore_ironman"), step.requires.get(2).modes);
		assertEquals("kept, so the step is never ticked on a guess", Guide.Requirement.Kind.UNKNOWN,
			step.requires.get(3).kind);
		assertEquals(10, step.completes.get(0).level);
		assertFalse(step.completes.get(0).boostable);
	}

	@Test
	public void anUnknownVersionAsksForAnUpdateAndShowsNothing()
	{
		String later = GuideFixtures.read(GuideFixtures.GUIDE).replace("\"version\": 1", "\"version\": 2");
		GuideParser.Result<Guide> result = GuideParser.guide(later);
		assertEquals(GuideParser.Problem.UPDATE_PLUGIN, result.problem);
		assertNull(result.value);
		String listing = GuideFixtures.read(GuideFixtures.LISTING).replace("\"version\": 1", "\"version\": 7");
		assertEquals(GuideParser.Problem.UPDATE_PLUGIN, GuideParser.listing(listing).problem);
		assertEquals(GuideParser.Problem.UPDATE_PLUGIN, GuideParser.guide("{\"version\":\"1\"}").problem);
	}

	@Test
	public void notAGuideIsUnreadable()
	{
		assertEquals(GuideParser.Problem.UNREADABLE, GuideParser.guide("<html>Not found</html>").problem);
		assertEquals(GuideParser.Problem.UNREADABLE, GuideParser.guide("[]").problem);
		assertEquals(GuideParser.Problem.UNREADABLE, GuideParser.guide(null).problem);
		assertEquals("no version", GuideParser.Problem.UNREADABLE,
			GuideParser.guide("{\"id\":\"a\",\"title\":\"A\",\"sections\":[]}").problem);
		assertEquals("an id that is not a slug", GuideParser.Problem.UNREADABLE,
			GuideParser.guide("{\"version\":1,\"id\":\"../x\",\"title\":\"A\",\"sections\":[]}").problem);
		assertEquals(GuideParser.Problem.UNREADABLE, GuideParser.listing("{\"version\":1}").problem);
	}

	@Test
	public void stepsWithoutAnIdOrWithOneAlreadyUsedAreLeftOut()
	{
		String body = "{\"version\":1,\"id\":\"g\",\"title\":\"G\",\"sections\":["
			+ "{\"id\":\"s\",\"title\":\"S\",\"steps\":[{\"title\":\"no id\"},{\"id\":\"a\",\"title\":\"First\"},"
			+ "{\"id\":\"a\",\"title\":\"Again\"},\"not an object\",{\"id\":\"b\"}]},"
			+ "{\"id\":\"empty\",\"title\":\"Empty\",\"steps\":[]}]}";
		Guide guide = GuideParser.guide(body).value;
		assertEquals(Arrays.asList("a", "b"), ids(guide));
		assertEquals("First", guide.step("a").title);
		assertEquals("a step without a title shows its id", "b", guide.step("b").title);
		assertEquals("a section with no steps is left out", 1, guide.sections.size());
	}

	@Test
	public void unsafeOrUnusableFieldsAreDropped()
	{
		String body = "{\"version\":1,\"id\":\"g\",\"title\":\"G\",\"sections\":[{\"id\":\"s\",\"title\":\"S\",\"steps\":["
			+ "{\"id\":\"a\",\"title\":\"T\\u0007itle\",\"url\":\"https://example.com/phish\",\"text\":\"  two\\n\\nlines  \","
			+ "\"places\":[{\"label\":\"Off the map\",\"x\":20000,\"y\":3200,\"plane\":0},"
			+ "{\"label\":\"High\",\"x\":3200,\"y\":3200,\"plane\":4},{\"label\":\"Surface\",\"x\":3200,\"y\":3200},"
			+ "{\"x\":1,\"y\":1}],"
			+ "\"bring\":[{\"name\":\"Zero\",\"id\":0},{\"name\":\"Huge\",\"id\":99999999},{\"id\":5}],"
			+ "\"requires\":[{\"kind\":\"skill\",\"skill\":\"cooking\",\"level\":150},{\"kind\":\"mode\",\"modes\":[]}]}]}]}";
		Guide.Step step = GuideParser.guide(body).value.step("a");
		assertEquals("control characters dropped", "Title", step.title);
		assertNull("a link off the site is dropped", step.url);
		assertEquals("whitespace made one space", "two lines", step.text);
		assertEquals("a place without a label says nothing", 3, step.places.size());
		assertFalse("off the map: label only", step.places.get(0).hasPoint());
		assertFalse("no such plane: label only", step.places.get(1).hasPoint());
		assertTrue("no plane is the surface", step.places.get(2).hasPoint());
		assertEquals(0, step.places.get(2).plane);
		assertEquals(2, step.bring.size());
		assertEquals(-1, step.bring.get(0).id);
		assertEquals(-1, step.bring.get(1).id);
		assertEquals(Guide.Requirement.Kind.UNKNOWN, step.requires.get(0).kind);
		assertEquals(Guide.Requirement.Kind.UNKNOWN, step.requires.get(1).kind);
	}

	@Test
	public void theListReadsEveryGuideItCanUse()
	{
		Guide.Listing listing = GuideFixtures.listing();
		assertEquals(2, listing.guides.size());
		Guide.Summary beginner = listing.find("beginner");
		assertEquals("Beginner walkthrough", beginner.title);
		assertEquals("free", beginner.membership);
		assertNull(beginner.modes);
		assertEquals(5, beginner.steps);
		assertEquals(540, beginner.minutes);
		assertEquals("3f2a9c1e", beginner.revision);
		assertEquals(Arrays.asList("ironman", "hardcore_ironman"), listing.find("ironman").modes);

		String body = "{\"version\":1,\"guides\":[{\"id\":\"Bad Id\",\"title\":\"x\"},{\"id\":\"a\"},"
			+ "{\"id\":\"b\",\"title\":\"B\"},{\"id\":\"b\",\"title\":\"Again\"}]}";
		Guide.Listing filtered = GuideParser.listing(body).value;
		assertEquals(1, filtered.guides.size());
		assertEquals("B", filtered.guides.get(0).title);
		assertNull("no revision given", filtered.guides.get(0).revision);
	}

	private static List<String> ids(Guide guide)
	{
		List<String> out = new ArrayList<>();
		for (Guide.Step step : guide.steps)
		{
			out.add(step.id);
		}
		return out;
	}
}
