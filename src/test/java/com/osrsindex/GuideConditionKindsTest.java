package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The condition kinds that let the site describe a new kind of step without a plugin update: game variables, items
 * held, and what happens in the game (an area reached, a talk, a kill). Each is parsed, checked or seen, and an
 * unknown kind stays harmless.
 */
public class GuideConditionKindsTest
{
	private static Guide.Requirement parse(String json)
	{
		return GuideParser.requirement(new JsonParser().parse(json).getAsJsonObject());
	}

	private static Guide.Step step(String title, List<Guide.Requirement> completes)
	{
		return new Guide.Step("s", title, -1, null, null, Collections.<String>emptyList(), null, null,
			Collections.<String>emptySet(), Collections.<String>emptyList(), Collections.<Guide.Place>emptyList(),
			Collections.<Guide.Place>emptyList(), Collections.<Guide.Item>emptyList(), Collections.<Guide.Collect>emptyList(),
			Collections.<Guide.Requirement>emptyList(), completes, null);
	}

	@Test
	public void eachKindIsRead()
	{
		Guide.Requirement varbit = parse("{\"kind\":\"varbit\",\"id\":4458,\"value\":1,\"op\":\">=\",\"label\":\"Easy Varrock diary\"}");
		assertEquals(Guide.Requirement.Kind.VARBIT, varbit.kind);
		assertEquals(4458, varbit.varId);
		assertEquals(">=", varbit.op);
		assertEquals(1, varbit.value);
		assertEquals("Easy Varrock diary", varbit.label);

		Guide.Requirement varp = parse("{\"kind\":\"varp\",\"id\":29,\"value\":5}");
		assertEquals(Guide.Requirement.Kind.VARP, varp.kind);
		assertEquals("the comparison defaults to at least", ">=", varp.op);
		assertNull(varp.label);

		Guide.Requirement items = parse("{\"kind\":\"items\",\"where\":\"carried\",\"items\":[{\"name\":\"Bucket\",\"id\":1925}]}");
		assertEquals(Guide.Requirement.Kind.ITEMS, items.kind);
		assertEquals("carried", items.op);
		assertEquals(1925, items.items.get(0).id);
		assertEquals("owned by default", "owned", parse("{\"kind\":\"items\",\"items\":[{\"name\":\"Pot\"}]}").op);

		Guide.Requirement area = parse("{\"kind\":\"area\",\"x\":3093,\"y\":3244,\"plane\":0,\"radius\":3,\"label\":\"Draynor\"}");
		assertEquals(Guide.Requirement.Kind.AREA, area.kind);
		assertEquals(3093, area.place.x);
		assertEquals(3, area.value);
		assertEquals("Draynor", area.label);
		assertEquals("the reach defaults to the signs' tiles", GuideSigns.REACH_TILES,
			parse("{\"kind\":\"area\",\"x\":1,\"y\":2}").value);

		Guide.Requirement talk = parse("{\"kind\":\"talk\",\"npc\":\"Mining Instructor\"}");
		assertEquals(Guide.Requirement.Kind.TALK, talk.kind);
		assertEquals("Mining Instructor", talk.npc);

		Guide.Requirement kill = parse("{\"kind\":\"kill\",\"npc\":\"Cow\",\"count\":5}");
		assertEquals(Guide.Requirement.Kind.KILL, kill.kind);
		assertEquals(5, kill.value);
	}

	@Test
	public void aKindThatCannotBeReadIsUnknownAndHarmless()
	{
		for (String json : new String[]{
			"{\"kind\":\"varbit\",\"id\":4458,\"value\":1,\"op\":\"~\"}",
			"{\"kind\":\"varbit\",\"value\":1}",
			"{\"kind\":\"varp\",\"id\":-1,\"value\":1}",
			"{\"kind\":\"items\",\"items\":[]}",
			"{\"kind\":\"items\",\"where\":\"nearby\",\"items\":[{\"name\":\"Pot\"}]}",
			"{\"kind\":\"area\",\"label\":\"Nowhere\"}",
			"{\"kind\":\"talk\"}",
			"{\"kind\":\"a_kind_from_a_later_site\"}"})
		{
			Guide.Requirement requirement = parse(json);
			assertEquals(json, Guide.Requirement.Kind.UNKNOWN, requirement.kind);
			assertEquals(json, GuideRules.Met.UNKNOWN, GuideRules.requirement(requirement, GuideFacts.UNKNOWN));
		}
	}

	@Test
	public void aGameVariableIsComparedAsTheSiteSays()
	{
		Guide.Requirement atLeast = parse("{\"kind\":\"varbit\",\"id\":100,\"value\":3}");
		assertEquals("not read yet", GuideRules.Met.UNKNOWN, GuideRules.requirement(atLeast, GuideFacts.UNKNOWN));
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(atLeast, new GuideFacts.Builder().varbit(100, 2).build()));
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(atLeast, new GuideFacts.Builder().varbit(100, 3).build()));
		assertEquals("a varp is read from varps, not varbits", GuideRules.Met.UNKNOWN,
			GuideRules.requirement(parse("{\"kind\":\"varp\",\"id\":100,\"value\":3}"), new GuideFacts.Builder().varbit(100, 9).build()));
		assertTrue(GuideRules.compare(5, "==", 5));
		assertTrue(GuideRules.compare(4, "!=", 5));
		assertTrue(GuideRules.compare(6, ">", 5));
		assertTrue(GuideRules.compare(5, "<=", 5));
		assertTrue(GuideRules.compare(4, "<", 5));
		assertFalse(GuideRules.compare(5, "<", 5));
	}

	@Test
	public void itemsAreCarriedOrOwned()
	{
		Guide.Requirement owned = parse("{\"kind\":\"items\",\"items\":[{\"name\":\"Bucket\",\"id\":1925,\"qty\":2}]}");
		Guide.Requirement carried = parse("{\"kind\":\"items\",\"where\":\"carried\",\"items\":[{\"name\":\"Bucket\",\"id\":1925}]}");
		Map<Integer, Integer> one = new HashMap<>();
		one.put(1925, 1);
		GuideFacts unseen = new GuideFacts.Builder().carried(one).build();
		assertEquals(GuideRules.Met.MET, GuideRules.requirement(carried, unseen));
		assertEquals("one carried, the bank unseen", GuideRules.Met.UNKNOWN, GuideRules.requirement(owned, unseen));
		GuideFacts banked = new GuideFacts.Builder().carried(one).banked(one).build();
		assertEquals("one carried and one banked", GuideRules.Met.MET, GuideRules.requirement(owned, banked));
		GuideFacts none = new GuideFacts.Builder().carried(Collections.<Integer, Integer>emptyMap())
			.banked(Collections.<Integer, Integer>emptyMap()).build();
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(owned, none));
		assertEquals(GuideRules.Met.MISSING, GuideRules.requirement(carried, none));
	}

	@Test
	public void aStateTicksTheStepAndSomethingThatHappensIsLeftToTheTracker()
	{
		Guide.Step diary = step("Finish the easy Varrock diary",
			Collections.singletonList(parse("{\"kind\":\"varbit\",\"id\":4458,\"value\":1}")));
		assertTrue(GuideRules.checkable(diary));
		assertFalse(GuideRules.completed(diary, new GuideFacts.Builder().varbit(4458, 0).build()));
		assertTrue(GuideRules.completed(diary, new GuideFacts.Builder().varbit(4458, 1).build()));

		Guide.Step talkWithState = step("Speak to the guide", java.util.Arrays.asList(
			parse("{\"kind\":\"talk\",\"npc\":\"Gielinor Guide\"}"), parse("{\"kind\":\"varp\",\"id\":281,\"value\":3}")));
		assertTrue("it has a state to check", GuideRules.checkable(talkWithState));
		assertFalse("the talk has to happen", GuideRules.completed(talkWithState, new GuideFacts.Builder().varp(281, 9).build()));
		assertTrue(GuideRules.statesMet(talkWithState, new GuideFacts.Builder().varp(281, 3).build()));
		assertFalse(GuideRules.statesMet(talkWithState, new GuideFacts.Builder().varp(281, 1).build()));

		Guide.Step onlyTalk = step("Anything", Collections.singletonList(parse("{\"kind\":\"talk\",\"npc\":\"Hans\"}")));
		assertFalse("nothing to read at any time", GuideRules.checkable(onlyTalk));
		assertTrue(GuideRules.statesMet(onlyTalk, GuideFacts.UNKNOWN));

		Guide.Step unknown = step("Anything", Collections.singletonList(parse("{\"kind\":\"a_kind_from_a_later_site\"}")));
		assertFalse("a kind this version does not know is not checkable, so it never sits behind the player",
			GuideRules.checkable(unknown));
		assertFalse(GuideRules.completed(unknown, GuideFacts.UNKNOWN));
	}

	@Test
	public void whatTheSiteSaysHappensWinsOverTheTitle()
	{
		GuideSigns talk = GuideSigns.of(step("Kill a goblin", Collections.singletonList(parse("{\"kind\":\"talk\",\"npc\":\"Hans\"}"))));
		assertTrue("the site's talk, not the title's kill", talk.talkedTo("Hans"));
		assertFalse(talk.isKill("Goblin"));

		GuideSigns kill = GuideSigns.of(step("Anything", Collections.singletonList(parse("{\"kind\":\"kill\",\"npc\":\"Cow\",\"count\":3}"))));
		assertTrue(kill.isKill("Cow"));
		assertEquals(3, kill.kills);

		GuideSigns area = GuideSigns.of(step("Anything",
			Collections.singletonList(parse("{\"kind\":\"area\",\"x\":3000,\"y\":3000,\"radius\":2}"))));
		assertTrue(area.reached(3002, 2998, 0));
		assertFalse("the site's radius, not the default", area.reached(3003, 3000, 0));

		assertTrue("a varbit is a state, no sign", GuideSigns.of(step("Talk to Hans",
			Collections.singletonList(parse("{\"kind\":\"varbit\",\"id\":1,\"value\":1}")))).none());
	}

	@Test
	public void aLongShoppingListIsKeptWholeAndTheStepBoxCountsTheRest()
	{
		JsonObject guide = new JsonParser().parse(GuideFixtures.read(GuideFixtures.GUIDE)).getAsJsonObject();
		com.google.gson.JsonArray bring = new com.google.gson.JsonArray();
		for (int i = 0; i < 108; i++)
		{
			JsonObject item = new JsonObject();
			item.addProperty("name", "Item " + i);
			item.addProperty("id", 1000 + i);
			bring.add(item);
		}
		guide.getAsJsonArray("sections").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject()
			.add("bring", bring);
		Guide parsed = GuideParser.guide(guide.toString()).value;
		assertEquals("the quest route's largest stock-up, 108 items, is kept whole", 108,
			parsed.step("cooks-assistant").bring.size());

		java.util.List<String> missing = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++)
		{
			missing.add("Item " + i);
		}
		java.util.List<String> lines = GuideStepOverlay.bringLines(missing);
		assertEquals(GuideStepOverlay.MAX_BRING_LINES + 1, lines.size());
		assertEquals("Bring: Item 0", lines.get(0));
		assertEquals("Bring: and 4 more, in the panel", lines.get(lines.size() - 1));
		assertEquals("one more than fits is listed, not counted", GuideStepOverlay.MAX_BRING_LINES + 1,
			GuideStepOverlay.bringLines(missing.subList(0, GuideStepOverlay.MAX_BRING_LINES + 1)).size());
	}

	@Test
	public void theVariablesToReadAreTheOnesTheGuideNames()
	{
		JsonObject guide = new JsonParser().parse(GuideFixtures.read(GuideFixtures.GUIDE)).getAsJsonObject();
		guide.getAsJsonArray("sections").get(0).getAsJsonObject().getAsJsonArray("steps").get(0).getAsJsonObject()
			.add("completes", new JsonParser().parse("[{\"kind\":\"varbit\",\"id\":4458,\"value\":1},"
				+ "{\"kind\":\"varp\",\"id\":29,\"value\":2}]"));
		Guide parsed = GuideParser.guide(guide.toString()).value;
		assertEquals(Collections.singleton(4458), GuideRules.varbits(parsed));
		assertEquals(Collections.singleton(29), GuideRules.varps(parsed));
	}
}
