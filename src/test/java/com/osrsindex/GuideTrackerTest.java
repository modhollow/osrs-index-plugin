package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Before;
import org.junit.Test;

/**
 * The guide tracker end to end, with the client, RuneLite and the network faked: nothing is fetched until the
 * player opens the tab, marks are kept per character, the guide moves on by itself, and the markers follow the
 * current step and stand aside while Quest Helper guides a quest.
 */
public class GuideTrackerTest
{
	private static final WorldPoint KITCHEN = new WorldPoint(3208, 3214, 0);
	private static final WorldPoint FRED = new WorldPoint(3190, 3273, 0);

	private final Map<String, Function<Object[], Object>> client = new HashMap<>();
	private final Map<String, Object> settings = new HashMap<>();
	private final Map<String, String> profile = new HashMap<>();
	private final Map<Quest, QuestState> quests = new HashMap<>();
	private final Map<Integer, Integer> varps = new HashMap<>();
	private final List<PluginMessage> posted = new ArrayList<>();
	private final List<String> chat = new ArrayList<>();
	private final List<String> asked = new ArrayList<>();
	private final List<GuideFetcher.Handler> pending = new ArrayList<>();
	private final long[] now = {1_000_000L};
	private boolean questHelperRunning;
	private String autoStart;
	private boolean shortestPathOn = true;

	private GuideTracker.Overlays overlays;
	private GuideTracker tracker;

	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers)
	{
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
		{
			Function<Object[], Object> answer = answers.get(method.getName());
			if (answer != null)
			{
				return answer.apply(args);
			}
			Class<?> returns = method.getReturnType();
			if (returns == boolean.class)
			{
				return false;
			}
			if (returns == int.class)
			{
				return 0;
			}
			if (returns == long.class)
			{
				return 0L;
			}
			return null;
		});
	}

	@Before
	public void setUp()
	{
		client.put("getRealSkillLevel", args -> args[0] == Skill.HITPOINTS ? 10 : 1);
		client.put("getBoostedSkillLevel", args -> args[0] == Skill.HITPOINTS ? 10 : 1);
		client.put("getVarpValue", args -> varps.getOrDefault((Integer) args[0], 0));
		Client fakeClient = fake(Client.class, client);

		OsrsIndexConfig config = (OsrsIndexConfig) Proxy.newProxyInstance(OsrsIndexConfig.class.getClassLoader(),
			new Class<?>[]{OsrsIndexConfig.class}, (proxy, method, args) ->
				method.getReturnType() == boolean.class ? settings.getOrDefault(method.getName(), true) : null);

		ClientThread immediate = new ClientThread()
		{
			@Override
			public void invoke(Runnable runnable)
			{
				runnable.run();
			}

			@Override
			public void invokeLater(Runnable runnable)
			{
				runnable.run();
			}
		};

		GuideTracker.Host host = new GuideTracker.Host()
		{
			@Override
			public String profile(String key)
			{
				return profile.get(key);
			}

			@Override
			public void saveProfile(String key, String value)
			{
				profile.put(key, value);
			}

			@Override
			public boolean questHelperRunning()
			{
				return questHelperRunning;
			}

			@Override
			public String questHelperAutoStart()
			{
				return autoStart;
			}

			@Override
			public QuestState questState(Quest quest)
			{
				return quests.getOrDefault(quest, QuestState.NOT_STARTED);
			}

			@Override
			public void post(PluginMessage message)
			{
				posted.add(message);
			}

			@Override
			public void chat(String message)
			{
				chat.add(message);
			}
		};

		GuideFetcher fetcher = new GuideFetcher((url, etag, handler) ->
		{
			asked.add(url);
			pending.add(handler);
		}, () -> now[0]);
		RouteDispatcher dispatcher = new RouteDispatcher(posted::add, plugin -> shortestPathOn, Clock.systemUTC());
		overlays = new GuideTracker.Overlays(new GuideMinimapOverlay(fakeClient), new GuideTileOverlay(fakeClient),
			new GuideStepOverlay());
		tracker = new GuideTracker(fakeClient, immediate, host, config, fetcher, dispatcher, new WorldMapPointManager(), overlays,
			new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
	}

	private void answer(String body)
	{
		pending.remove(0).handle(200, body, null);
	}

	private void answer(int status)
	{
		pending.remove(0).handle(status, "", null);
	}

	/** Logged in, the Guides tab opened and the fixture guide opened from the list. */
	private void followTheFixture()
	{
		tracker.loggedIn();
		tracker.opened();
		answer(GuideFixtures.read(GuideFixtures.LISTING));
		tracker.openGuide("beginner");
		answer(GuideFixtures.read(GuideFixtures.GUIDE));
	}

	private WorldPoint mapPoint()
	{
		WorldMapPoint point = tracker.mapPoint();
		return point == null ? null : point.getWorldPoint();
	}

	@Test
	public void nothingIsFetchedUntilThePlayerOpensTheTab()
	{
		profile.put(GuideTracker.FOLLOWED_KEY, "beginner");
		tracker.loggedIn();
		tracker.tick();
		assertTrue("not at login, even with a guide followed", asked.isEmpty());

		tracker.opened();
		assertEquals(List.of(Endpoints.guides()), asked);
		answer(GuideFixtures.read(GuideFixtures.LISTING));
		assertEquals("the followed guide comes back once the list says it is offered", Endpoints.guide("beginner"),
			asked.get(1));
		answer(GuideFixtures.read(GuideFixtures.GUIDE));
		GuidesPanel.View view = tracker.lastView();
		assertTrue(view.guideOpen);
		assertSame(GuidesPanel.Load.OK, view.guideLoad);
		assertEquals("beginner", view.guide.id);
	}

	@Test
	public void showGuidesOffFetchesNothing()
	{
		settings.put("showGuides", false);
		tracker.opened();
		assertTrue(asked.isEmpty());
	}

	@Test
	public void openingAGuideFollowsItAndPointsAtItsCurrentStep()
	{
		followTheFixture();
		assertEquals("beginner", profile.get(GuideTracker.FOLLOWED_KEY));
		assertEquals(KITCHEN, mapPoint());
		assertEquals("Cook's Assistant", tracker.mapPoint().getName());
		assertEquals(KITCHEN, overlays.minimap.target());
		assertEquals(KITCHEN, overlays.tile.target());
		GuideStepOverlay.Box box = overlays.step.shown();
		assertEquals("Beginner walkthrough", box.guide);
		assertEquals("0/5", box.count);
		assertEquals("Cook's Assistant", box.step);
		assertEquals("Help the Lumbridge cook bake a cake.", box.text);
		assertEquals("Lumbridge Castle kitchen", box.where);
		assertEquals("the bucket: not carried, bank not seen", List.of("Bucket"), box.missing);
		assertNull("Quest Helper is not running", box.questHelper);
	}

	@Test
	public void marksAreKeptAndTheGuideMovesOn()
	{
		followTheFixture();
		tracker.markStep("cooks-assistant", GuideProgress.Mark.DONE);
		assertEquals("{\"cooks-assistant\":\"done\"}", profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner"));
		assertEquals(FRED, mapPoint());
		assertEquals("1/5", overlays.step.shown().count);

		tracker.markStep("sheep-shearer", GuideProgress.Mark.SKIPPED);
		assertNull("the next step has only a label", mapPoint());
		assertNull(overlays.minimap.target());
		assertEquals("Mining Instructor", overlays.step.shown().where);
		assertEquals("1/4", overlays.step.shown().count);

		tracker.markStep("sheep-shearer", null);
		tracker.markStep("cooks-assistant", null);
		assertEquals(KITCHEN, mapPoint());
		assertEquals("{}", profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner"));
	}

	@Test
	public void marksArePerCharacterAndNeedALogin()
	{
		profile.put(GuideTracker.PROGRESS_KEY_PREFIX + "beginner", "{\"cooks-assistant\":\"done\"}");
		followTheFixture();
		assertEquals(FRED, mapPoint());

		tracker.loggedOut();
		assertNull("logged out, nothing points anywhere", mapPoint());
		assertNull(overlays.minimap.target());
		assertNull(overlays.step.shown());
		tracker.markStep("sheep-shearer", GuideProgress.Mark.DONE);
		assertEquals("no mark without a character", "{\"cooks-assistant\":\"done\"}",
			profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner"));
		assertFalse(tracker.lastView().loggedIn);

		// Another character: its own marks, here none.
		profile.remove(GuideTracker.PROGRESS_KEY_PREFIX + "beginner");
		tracker.loggedIn();
		assertEquals(KITCHEN, mapPoint());
	}

	@Test
	public void talkingToTheCurrentStepsPersonTicksItAndMovesOn()
	{
		followTheFixture();
		tracker.npcTalkedTo("Mining Instructor");
		assertEquals("a later step's person ticks nothing early", "Cook's Assistant", overlays.step.shown().step);

		tracker.markStep("cooks-assistant", GuideProgress.Mark.DONE);
		tracker.markStep("sheep-shearer", GuideProgress.Mark.SKIPPED);
		assertEquals("Meet the Mining Instructor", overlays.step.shown().step);
		tracker.npcTalkedTo("Mining Instructor");
		assertTrue(profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner").contains("\"mining-instructor\":\"done\""));
		assertEquals("the guide moved on", "Reach 10 Cooking", overlays.step.shown().step);
	}

	@Test
	public void killingWhatTheCurrentStepNamesTicksIt()
	{
		followTheFixture();
		tracker.markStep("cooks-assistant", GuideProgress.Mark.DONE);
		tracker.markStep("sheep-shearer", GuideProgress.Mark.DONE);
		tracker.markStep("mining-instructor", GuideProgress.Mark.DONE);
		tracker.markStep("cooking-10", GuideProgress.Mark.SKIPPED);
		assertEquals("Kill a goblin", overlays.step.shown().step);

		tracker.npcKilled("Man");
		assertFalse(profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner").contains("risky-step"));
		tracker.npcKilled("Goblin");
		assertTrue(profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner").contains("\"risky-step\":\"done\""));
	}

	@Test
	public void startHereMovesTheGuideToThatStepAndKeepsIt()
	{
		followTheFixture();
		tracker.startHere("mining-instructor");
		assertEquals("Meet the Mining Instructor", overlays.step.shown().step);
		assertTrue(profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner").contains("\"@start\":\"mining-instructor\""));
		assertEquals("two steps behind, the quests not finished", 2,
			GuideRules.standing(tracker.lastView().guide, tracker.lastView().progress, tracker.lastView().facts).behind.size());
	}

	@Test
	public void theBankAsLastSeenCountsBeforeItIsOpened()
	{
		profile.put(GuideTracker.BANK_KEY, "{\"1925\":1}");
		followTheFixture();
		assertTrue(tracker.lastView().facts.bankSeen);
		assertEquals(1, tracker.lastView().facts.banked(1925));
		assertSame(GuideRules.Have.BANKED,
			GuideRules.item(tracker.lastView().guide.step("cooks-assistant").bring.get(0), tracker.lastView().facts));
	}

	@Test
	public void itemIdsTheSiteLeftOutAreFilledSoTheyAreChecked()
	{
		tracker.itemIds(name -> name.equals("Pot") ? 1931 : -1);
		followTheFixture();
		assertEquals(1931, tracker.lastView().guide.step("cooks-assistant").bring.get(1).id);
		assertEquals("the site's id is kept", 1925, tracker.lastView().guide.step("cooks-assistant").bring.get(0).id);
	}

	@Test
	public void aFinishedQuestTicksItsStepByItself()
	{
		quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		followTheFixture();
		assertEquals("{\"cooks-assistant\":\"done\"}", profile.get(GuideTracker.PROGRESS_KEY_PREFIX + "beginner"));
		assertEquals(FRED, mapPoint());

		quests.put(Quest.SHEEP_SHEARER, QuestState.FINISHED);
		tracker.gameMessage("Congratulations! Quest complete!");
		tracker.tick();
		tracker.tick();
		assertEquals("the guide moved on", "Meet the Mining Instructor", overlays.step.shown().step);
	}

	@Test
	public void questHelperGuidesTheQuestOnceStartedAndOurMarkersStandAside()
	{
		questHelperRunning = true;
		followTheFixture();
		assertEquals("before the start, the point leads to it", KITCHEN, mapPoint());
		assertEquals("Quest Helper takes over when you start Cook's Assistant.", overlays.step.shown().questHelper);

		quests.put(Quest.COOKS_ASSISTANT, QuestState.IN_PROGRESS);
		tracker.gameMessage("You've started a new quest: Cook's Assistant");
		tracker.tick();
		tracker.tick();
		assertNull(mapPoint());
		assertNull(overlays.minimap.target());
		assertNull(overlays.tile.target());
		assertNull("Quest Helper shows its own box", overlays.step.shown());

		quests.put(Quest.COOKS_ASSISTANT, QuestState.FINISHED);
		tracker.gameMessage("Congratulations! Quest complete!");
		tracker.tick();
		tracker.tick();
		assertEquals("finished: the guide moves on, and points again", FRED, mapPoint());
	}

	@Test
	public void withQuestHelperOffOrSetAsideTheStepIsOurs()
	{
		quests.put(Quest.COOKS_ASSISTANT, QuestState.IN_PROGRESS);
		followTheFixture();
		assertEquals(KITCHEN, mapPoint());

		questHelperRunning = true;
		settings.put("guideQuestHelper", false);
		tracker.settingsChanged();
		assertEquals(KITCHEN, mapPoint());

		settings.put("guideQuestHelper", true);
		tracker.settingsChanged();
		assertNull(mapPoint());
	}

	@Test
	public void manualStartWhenQuestHelperDoesNotStartByItself()
	{
		questHelperRunning = true;
		autoStart = "false";
		followTheFixture();
		assertEquals("Start Quest Helper for Cook's Assistant from the Quest List.", overlays.step.shown().questHelper);
	}

	@Test
	public void eachMarkerHasItsOwnSetting()
	{
		settings.put("guideMapMarker", false);
		settings.put("guideStepBox", false);
		followTheFixture();
		assertNull(mapPoint());
		assertNull(overlays.step.shown());
		assertEquals(KITCHEN, overlays.minimap.target());
		assertEquals(KITCHEN, overlays.tile.target());

		settings.put("showGuides", false);
		tracker.settingsChanged();
		assertNull(overlays.minimap.target());
		assertNull(overlays.tile.target());
	}

	@Test
	public void walkThereRoutesWithShortestPathEveryClick()
	{
		followTheFixture();
		tracker.walkTo("cooks-assistant");
		tracker.walkTo("cooks-assistant");
		assertEquals(2, posted.size());
		for (PluginMessage message : posted)
		{
			assertEquals("shortestpath", message.getNamespace());
			assertEquals("path", message.getName());
			assertEquals(KITCHEN, message.getData().get("target"));
		}
		assertEquals("Routing to Lumbridge Castle kitchen (3208, 3214, 0) with Shortest Path.", chat.get(0));

		shortestPathOn = false;
		tracker.walkTo("cooks-assistant");
		assertEquals(2, posted.size());
		assertTrue(chat.get(2).startsWith("Shortest Path is not on"));
		tracker.walkTo("mining-instructor");
		assertEquals("a place with only a label cannot be walked to", 3, chat.size());
	}

	@Test
	public void followWithQuestHelperPostsTheStart()
	{
		questHelperRunning = true;
		followTheFixture();
		tracker.followWithQuestHelper("cooks-assistant");
		assertEquals(1, posted.size());
		assertEquals("questhelper", posted.get(0).getNamespace());
		assertEquals("start", posted.get(0).getName());
		assertEquals("Cook's Assistant", posted.get(0).getData().get("quest"));
		assertTrue(chat.get(0).contains("right-click Cook's Assistant and pick Start Quest Helper"));

		questHelperRunning = false;
		tracker.followWithQuestHelper("cooks-assistant");
		assertEquals("Quest Helper off: nothing to ask", 1, posted.size());
	}

	@Test
	public void questHelpersSelectedMessageReachesTheView()
	{
		followTheFixture();
		tracker.pluginMessage(new PluginMessage("questhelper", "selected", Map.of("quest", "Cook's Assistant")));
		assertEquals("Cook's Assistant", tracker.lastView().questHelperSelected);
		tracker.pluginMessage(new PluginMessage("questhelper", "selected", Map.of()));
		assertNull(tracker.lastView().questHelperSelected);
	}

	@Test
	public void carriedAndBankedItemsLeaveTheBox()
	{
		Map<Integer, Integer> bank = new HashMap<>();
		client.put("getItemContainer", args ->
		{
			int id = (Integer) args[0];
			if (id != InventoryID.BANK || bank.isEmpty())
			{
				return null;
			}
			Map<String, Function<Object[], Object>> container = new HashMap<>();
			container.put("getItems", none -> new Item[]{new Item(1925, bank.get(1925))});
			return fake(ItemContainer.class, container);
		});
		followTheFixture();
		assertEquals(List.of("Bucket"), overlays.step.shown().missing);

		bank.put(1925, 1);
		tracker.itemsChanged(InventoryID.BANK);
		tracker.tick();
		tracker.tick();
		assertEquals(List.of("Bucket (in your bank)"), overlays.step.shown().missing);
	}

	@Test
	public void aListFailureDoesNotLeaveTheGuideLoading()
	{
		profile.put(GuideTracker.FOLLOWED_KEY, "beginner");
		tracker.loggedIn();
		tracker.opened();
		assertSame(GuidesPanel.Load.LOADING, tracker.lastView().guideLoad);
		answer(503);
		assertSame(GuidesPanel.Load.FAILED, tracker.lastView().listLoad);
		assertSame(GuidesPanel.Load.FAILED, tracker.lastView().guideLoad);
	}

	@Test
	public void aGuideTheSiteNoLongerOffersIsDropped()
	{
		followTheFixture();
		assertNotNull(mapPoint());
		tracker.opened();
		assertTrue("the list is kept for an hour", pending.isEmpty());

		now[0] += GuideFetcher.LIST_MAX_AGE_MILLIS;
		tracker.opened();
		answer("{\"version\":1,\"guides\":[{\"id\":\"ironman\",\"title\":\"Ironman route\"}]}");
		assertSame(GuidesPanel.Load.NOT_SERVED, tracker.lastView().guideLoad);
		assertNull(mapPoint());
		assertNull(overlays.step.shown());
	}

	@Test
	public void aNewRevisionIsFetchedWhenTheTabOpens()
	{
		followTheFixture();
		now[0] += GuideFetcher.LIST_MAX_AGE_MILLIS;
		tracker.opened();
		answer(GuideFixtures.read(GuideFixtures.LISTING).replace("3f2a9c1e", "new-revision"));
		assertEquals(Endpoints.guide("beginner"), asked.get(asked.size() - 1));
	}

	@Test
	public void theIronmanTextIsForAnIronman()
	{
		client.put("getVarbitValue", args -> 1);
		followTheFixture();
		assertEquals("Fill the bucket yourself at the dairy cow.", overlays.step.shown().text);
	}

	@Test
	public void theGangDecidesShieldOfArravsHelper()
	{
		varps.put(VarPlayerID.PHOENIXGANG, 8);
		followTheFixture();
		assertSame(QuestHelperHandOff.Gang.PHOENIX, tracker.lastView().gang);
	}
}
