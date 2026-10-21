package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.Text;

/**
 * Follows one osrsindex.com guide in the client: what the Guides view shows, the progress kept in the RuneLite
 * profile, the checks against the live client, the current step's map point, minimap arrow, tile outline and step
 * box, Walk there, and the hand-off to Quest Helper. Everything here runs on the client thread.
 *
 * <p>Guides are fetched only on the player's own action ({@link #opened()}, {@link #openGuide}). The guide being
 * followed is remembered per character, but after a restart it is fetched again only when the player opens the
 * Guides view.
 */
final class GuideTracker implements GuidesPanel.Actions
{
	/** The guide this character follows, by id, in the RuneLite profile. */
	static final String FOLLOWED_KEY = "guideFollowed";
	/** A guide's marks, in the RuneLite profile: this prefix and the guide's id. */
	static final String PROGRESS_KEY_PREFIX = "guide.";
	/**
	 * The bank as last seen, in the RuneLite profile, so a guide can tell what the player owns before they open it
	 * this session: a JSON object of item id to quantity.
	 */
	static final String BANK_KEY = "guideBank";
	/** Re-checks run at most this often, in game ticks, however often stats and varbits change. */
	static final int CHECK_EVERY_TICKS = 2;
	/** How long Walk there's place lasts for the dispatcher: it is routed at once, so a little is plenty. */
	private static final long WALK_EXPIRES_SECONDS = 60;

	private static final Map<String, Quest> QUESTS = new HashMap<>();

	static
	{
		for (Quest quest : Quest.values())
		{
			QUESTS.put(quest.getName(), quest);
		}
	}

	/** What the tracker needs from RuneLite beyond the client: kept small, so the tests can stand in for it. */
	interface Host
	{
		/** A value of this plugin's in the RuneLite profile, or null. */
		String profile(String key);

		void saveProfile(String key, String value);

		/** Quest Helper is loaded and running. Read through the plugin list only. */
		boolean questHelperRunning();

		/** Quest Helper's stored "Auto start helper" ({@code questhelper.autostartQuests}), or null. Read only. */
		String questHelperAutoStart();

		/** The quest's state: runs a client script, so only on the client thread. */
		QuestState questState(Quest quest);

		/** Post on RuneLite's event bus, to another plugin. */
		void post(PluginMessage message);

		/** An "OSRS Index:" line in chat. */
		void chat(String message);
	}

	/** The overlays the tracker points at the current step. */
	static final class Overlays
	{
		final GuideMinimapOverlay minimap;
		final GuideTileOverlay tile;
		final GuideStepOverlay step;

		Overlays(GuideMinimapOverlay minimap, GuideTileOverlay tile, GuideStepOverlay step)
		{
			this.minimap = minimap;
			this.tile = tile;
			this.step = step;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final Host host;
	private final OsrsIndexConfig config;
	private final GuideFetcher fetcher;
	private final RouteDispatcher dispatcher;
	private final WorldMapPointManager worldMap;
	private final Overlays overlays;
	private final BufferedImage mapIcon;
	private final GuidesPanel panel;
	/** The last view sent to the panel. */
	private GuidesPanel.View lastView = GuidesPanel.View.EMPTY;

	private GuidesPanel.Load listLoad = GuidesPanel.Load.NONE;
	private Guide.Listing listing;
	/** The guide followed, by id, or null; {@link #guide} once it has loaded. */
	private String guideId;
	private Guide guide;
	private GuidesPanel.Load guideLoad = GuidesPanel.Load.NONE;
	/** The view shows the guide, not the list. */
	private boolean guideOpen;
	private GuideProgress progress = new GuideProgress();

	private boolean loggedIn;
	private GuideFacts facts = GuideFacts.UNKNOWN;
	private QuestHelperHandOff.Gang gang = QuestHelperHandOff.Gang.UNKNOWN;
	private final Map<Integer, Integer> bank = new HashMap<>();
	private boolean bankSeen;
	private boolean skillsDirty;
	private boolean questsDirty;
	private boolean currentQuestsDirty;
	private boolean itemsDirty;
	private boolean accountDirty;
	/** A varbit or varp changed: re-read the ones the guide's conditions name. */
	private boolean varsDirty;
	private int ticks;
	private int lastCheckTick = Integer.MIN_VALUE / 2;
	/** What Quest Helper says it shows, when it says so, or null. */
	private String questHelperSelected;
	private WorldMapPoint mapPoint;
	private int walks;
	/** Item ids by name, for items the site gives none; the plugin answers from RuneLite's item prices. */
	private ToIntFunction<String> itemIds = name -> -1;
	/** Which items cannot be traded; the plugin answers from RuneLite's item definitions, on the client thread. */
	private IntPredicate untradeable = id -> false;
	/** The step whose kills are being counted, and how many so far. */
	private String killsFor;
	private int killsSoFar;

	GuideTracker(Client client, ClientThread clientThread, Host host, OsrsIndexConfig config, GuideFetcher fetcher,
		RouteDispatcher dispatcher, WorldMapPointManager worldMap, Overlays overlays, BufferedImage mapIcon)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.host = host;
		this.config = config;
		this.fetcher = fetcher;
		this.dispatcher = dispatcher;
		this.worldMap = worldMap;
		this.overlays = overlays;
		this.mapIcon = mapIcon;
		this.panel = new GuidesPanel(this);
	}

	/** The Guides view, for the panel's Guides tab. */
	GuidesPanel panel()
	{
		return panel;
	}

	/** The last view sent to the panel. */
	GuidesPanel.View lastView()
	{
		return lastView;
	}

	/** The world map point shown now, or null. */
	WorldMapPoint mapPoint()
	{
		return mapPoint;
	}

	// --- the player's actions ------------------------------------------------

	/** The player opened the Guides view: the list, at most hourly, and the guide they follow. */
	void opened()
	{
		clientThread.invoke(() ->
		{
			if (!config.showGuides())
			{
				return;
			}
			if (listing == null)
			{
				listLoad = GuidesPanel.Load.LOADING;
			}
			if (guideId != null && guide == null)
			{
				guideOpen = true;
				guideLoad = GuidesPanel.Load.LOADING;
			}
			show();
			fetcher.listing(answer -> clientThread.invoke(() -> listingArrived(answer)));
		});
	}

	@Override
	public void retryGuides()
	{
		// The list comes first: a guide not yet loaded is asked for once the list says it is still offered.
		fetcher.forgetFailures();
		opened();
	}

	@Override
	public void openGuide(String id)
	{
		clientThread.invoke(() ->
		{
			if (!id.equals(guideId))
			{
				guideId = id;
				guide = null;
				questHelperSelected = null;
				clearMarkers();
				if (loggedIn)
				{
					host.saveProfile(FOLLOWED_KEY, id);
				}
				loadProgress();
			}
			guideOpen = true;
			fetchGuide();
		});
	}

	@Override
	public void showGuideList()
	{
		clientThread.invoke(() ->
		{
			guideOpen = false;
			show();
		});
	}

	@Override
	public void markStep(String stepId, GuideProgress.Mark mark)
	{
		clientThread.invoke(() ->
		{
			if (!loggedIn || guide == null || guide.step(stepId) == null)
			{
				return;
			}
			if (progress.set(stepId, mark))
			{
				saveProgress();
			}
			check();
		});
	}

	@Override
	public void startHere(String stepId)
	{
		clientThread.invoke(() ->
		{
			if (!loggedIn || guide == null || guide.step(stepId) == null)
			{
				return;
			}
			if (progress.start(stepId))
			{
				saveProgress();
			}
			check();
		});
	}

	@Override
	public void walkTo(String stepId)
	{
		clientThread.invoke(() ->
		{
			Guide.Step step = guide == null ? null : guide.step(stepId);
			Guide.Place place = step == null ? null : step.point();
			if (place == null)
			{
				return;
			}
			// A fresh id per click: the dispatcher routes an id once, and the player may well walk twice.
			Destination destination = new Destination("guide-" + (++walks), place.x, place.y, place.plane, place.label,
				Instant.now().plusSeconds(WALK_EXPIRES_SECONDS));
			RouteDispatcher.Result result = dispatcher.dispatch(destination, RouteWith.SHORTEST_PATH);
			host.chat(walkLine(result.kind, place));
		});
	}

	/** The chat line after Walk there. */
	static String walkLine(RouteDispatcher.Kind kind, Guide.Place place)
	{
		String where = RouteDispatcher.chatSafe(place.label) + " (" + place.x + ", " + place.y + ", " + place.plane + ")";
		switch (kind)
		{
			case ROUTED:
				return "Routing to " + where + " with Shortest Path.";
			case NO_PATHING_PLUGIN:
				return "Shortest Path is not on, so the way to " + where + " cannot be drawn. Install or turn it on, then"
					+ " click Walk there again.";
			default:
				return "Could not route to " + where + ".";
		}
	}

	@Override
	public void followWithQuestHelper(String stepId)
	{
		clientThread.invoke(() ->
		{
			Guide.Step step = guide == null ? null : guide.step(stepId);
			if (step == null)
			{
				return;
			}
			QuestHelperHandOff handOff = QuestHelperHandOff.of(step, questHelperOn(), autoStart(), gang, facts);
			if (handOff.helperName == null)
			{
				return;
			}
			host.post(QuestHelperHandOff.startMessage(handOff.helperName));
			host.chat(RouteDispatcher.chatSafe("Asked Quest Helper to show " + handOff.helperName + ". If it does not,"
				+ " open the Quest List, right-click " + QuestHelperHandOff.questListName(handOff.quest)
				+ " and pick Start Quest Helper."));
		});
	}

	// --- answers -------------------------------------------------------------

	private void fetchGuide()
	{
		String id = guideId;
		Guide.Summary summary = listing == null ? null : listing.find(id);
		if (guide == null)
		{
			guideLoad = GuidesPanel.Load.LOADING;
		}
		show();
		fetcher.guide(id, summary == null ? null : summary.revision,
			answer -> clientThread.invoke(() -> guideArrived(id, answer)));
	}

	private void listingArrived(GuideFetcher.Answer<Guide.Listing> answer)
	{
		listLoad = load(answer.status);
		if (answer.value != null)
		{
			listing = answer.value;
		}
		if (guideId != null && guide == null && listLoad != GuidesPanel.Load.OK)
		{
			// No list, so no guide either: say why.
			guideLoad = listLoad;
		}
		if (guideId != null && listing != null && listLoad == GuidesPanel.Load.OK)
		{
			Guide.Summary summary = listing.find(guideId);
			if (summary == null)
			{
				// No longer offered in game.
				guide = null;
				guideLoad = GuidesPanel.Load.NOT_SERVED;
				clearMarkers();
			}
			else if (guide == null || (summary.revision != null && !summary.revision.equals(guide.revision)))
			{
				fetchGuide();
				return;
			}
		}
		show();
	}

	private void guideArrived(String id, GuideFetcher.Answer<Guide> answer)
	{
		if (stopped || !id.equals(guideId))
		{
			return;
		}
		GuidesPanel.Load load = load(answer.status);
		if (answer.value != null)
		{
			guide = answer.value.withItemFacts(itemIds, untradeable);
			guideLoad = GuidesPanel.Load.OK;
			markAllDirty();
			check();
			return;
		}
		if (guide == null || load != GuidesPanel.Load.FAILED)
		{
			// A failed refresh keeps the guide it has; anything else says why there is none.
			guide = null;
			guideLoad = load;
			clearMarkers();
		}
		show();
	}

	private static GuidesPanel.Load load(GuideFetcher.Status status)
	{
		switch (status)
		{
			case OK:
				return GuidesPanel.Load.OK;
			case NOT_SERVED:
				return GuidesPanel.Load.NOT_SERVED;
			case UPDATE_PLUGIN:
				return GuidesPanel.Load.UPDATE_PLUGIN;
			case FAILED:
			default:
				return GuidesPanel.Load.FAILED;
		}
	}

	// --- the game ------------------------------------------------------------

	/** Logged in, a few ticks after login: the character's progress and facts can be read. */
	void loggedIn()
	{
		loggedIn = true;
		bank.clear();
		bankSeen = loadBank();
		facts = GuideFacts.UNKNOWN;
		if (guideId == null)
		{
			guideId = host.profile(FOLLOWED_KEY);
			if (guideId != null && !GuideParser.GUIDE_ID.matcher(guideId).matches())
			{
				guideId = null;
			}
		}
		loadProgress();
		markAllDirty();
		check();
	}

	/** The bank as last seen by this character, from the profile. True when there was one. */
	private boolean loadBank()
	{
		String saved = host.profile(BANK_KEY);
		if (saved == null || saved.isEmpty())
		{
			return false;
		}
		try
		{
			JsonElement parsed = new JsonParser().parse(saved);
			if (parsed == null || !parsed.isJsonObject())
			{
				return false;
			}
			for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet())
			{
				int id = Integer.parseInt(entry.getKey());
				int qty = entry.getValue().getAsInt();
				if (id >= 0 && id <= GuideParser.MAX_ITEM_ID && qty > 0)
				{
					bank.put(id, qty);
				}
			}
			return true;
		}
		catch (RuntimeException unreadable)
		{
			bank.clear();
			return false;
		}
	}

	private void saveBank()
	{
		JsonObject out = new JsonObject();
		for (Map.Entry<Integer, Integer> entry : bank.entrySet())
		{
			out.addProperty(Integer.toString(entry.getKey()), entry.getValue());
		}
		host.saveProfile(BANK_KEY, out.toString());
	}

	void loggedOut()
	{
		loggedIn = false;
		facts = GuideFacts.UNKNOWN;
		progress = new GuideProgress();
		bank.clear();
		bankSeen = false;
		gang = QuestHelperHandOff.Gang.UNKNOWN;
		clearMarkers();
		show();
	}

	void statChanged()
	{
		skillsDirty = true;
	}

	void varbitChanged(int varpId, int varbitId)
	{
		if (varpId == VarPlayerID.QP)
		{
			questsDirty = true;
		}
		else
		{
			currentQuestsDirty = true;
		}
		if (varbitId == VarbitID.IRONMAN)
		{
			accountDirty = true;
		}
		varsDirty = true;
	}

	/** A game message: a quest started or finished re-reads every quest the guide names. */
	void gameMessage(String text)
	{
		if (text.contains("Quest complete") || text.contains("started a new quest") || text.contains("completed a quest"))
		{
			questsDirty = true;
		}
	}

	void itemsChanged(int containerId)
	{
		if (containerId == InventoryID.BANK)
		{
			ItemContainer container = client.getItemContainer(InventoryID.BANK);
			if (container != null)
			{
				Map<Integer, Integer> seen = new HashMap<>();
				add(seen, container);
				if (!bankSeen || !seen.equals(bank))
				{
					bank.clear();
					bank.putAll(seen);
					saveBank();
				}
				bankSeen = true;
			}
		}
		if (containerId == InventoryID.BANK || containerId == InventoryID.INV || containerId == InventoryID.WORN)
		{
			itemsDirty = true;
		}
	}

	void pluginMessage(PluginMessage message)
	{
		if (QuestHelperHandOff.NAMESPACE.equals(message.getNamespace())
			&& QuestHelperHandOff.SELECTED.equals(message.getName()))
		{
			String selected = QuestHelperHandOff.selected(message);
			clientThread.invoke(() ->
			{
				questHelperSelected = selected;
				show();
			});
		}
	}

	/** A setting changed: ours, Quest Helper's auto-start, or a plugin turned on or off. */
	void settingsChanged()
	{
		clientThread.invoke(() ->
		{
			if (!config.showGuides())
			{
				clearMarkers();
				return;
			}
			check();
		});
	}

	/** Fills in the item ids a guide leaves out, by name, so those items get a picture and are checked too. */
	void itemIds(ToIntFunction<String> lookup)
	{
		itemIds = lookup == null ? name -> -1 : lookup;
	}

	/** Which items cannot be traded, so owning one shows the step that gives it is done. Read on the client thread. */
	void untradeable(IntPredicate lookup)
	{
		untradeable = lookup == null ? id -> false : lookup;
	}

	/** A dialogue opened with someone: the current step is done if it is to talk to them ({@link GuideSigns}). */
	void npcTalkedTo(String name)
	{
		Guide.Step step = currentStep();
		if (step != null && GuideSigns.of(step).talkedTo(name) && GuideRules.statesMet(step, facts))
		{
			signed(step);
		}
	}

	/** Something died fighting the player: the current step is done once it has killed what the step names. */
	void npcKilled(String name)
	{
		Guide.Step step = currentStep();
		if (step == null)
		{
			return;
		}
		GuideSigns signs = GuideSigns.of(step);
		if (!signs.isKill(name))
		{
			return;
		}
		if (!step.id.equals(killsFor))
		{
			killsFor = step.id;
			killsSoFar = 0;
		}
		killsSoFar++;
		if (killsSoFar >= signs.kills && GuideRules.statesMet(step, facts))
		{
			signed(step);
		}
	}

	/** Where the player stands: the current step is done once the player reaches the place it sends them to. */
	void playerAt(int x, int y, int plane)
	{
		Guide.Step step = currentStep();
		if (step != null && GuideSigns.of(step).reached(x, y, plane) && GuideRules.statesMet(step, facts))
		{
			signed(step);
		}
	}

	private Guide.Step currentStep()
	{
		return guide == null || !loggedIn ? null : GuideRules.count(guide, progress, facts).current;
	}

	/** The game showed the current step done: keep the mark, and move on to the next step. */
	private void signed(Guide.Step step)
	{
		if (progress.set(step.id, GuideProgress.Mark.DONE))
		{
			saveProgress();
			check();
		}
	}

	void tick()
	{
		ticks++;
		if (guide != null && loggedIn && (skillsDirty || questsDirty || currentQuestsDirty || itemsDirty || accountDirty
			|| varsDirty)
			&& ticks - lastCheckTick >= CHECK_EVERY_TICKS)
		{
			check();
		}
	}

	/** Var ids a guide named that the game would not let RuneLite read: not read again this session. */
	private final Set<Integer> unreadableVarbits = new HashSet<>();
	private final Set<Integer> unreadableVarps = new HashSet<>();

	/** Set once the plugin stops; every later task does nothing. */
	private volatile boolean stopped;

	/**
	 * The plugin stopped. A download or client-thread task already queued may still arrive; {@link #stopped} keeps it
	 * from putting a marker back, which nothing would remove.
	 */
	void shutDown()
	{
		stopped = true;
		clearMarkers();
	}

	private void markAllDirty()
	{
		skillsDirty = true;
		questsDirty = true;
		itemsDirty = true;
		accountDirty = true;
		varsDirty = true;
	}

	/** Read what changed, tick the steps the character has done, and point the markers at the current step. */
	private void check()
	{
		if (stopped)
		{
			return;
		}
		lastCheckTick = ticks;
		if (guide != null && loggedIn)
		{
			facts = readFacts();
			gang = QuestHelperHandOff.Gang.of(client.getVarpValue(VarPlayerID.PHOENIXGANG),
				client.getVarpValue(VarPlayerID.BLACKARMGANG));
			boolean changed = GuideRules.tickCompleted(guide, progress, facts);
			// A step to get items is done once they are all carried; the next may be held already too.
			for (int i = 0; i < guide.steps.size(); i++)
			{
				Guide.Step current = GuideRules.count(guide, progress, facts).current;
				if (current == null || !GuideSigns.of(current).held(facts)
					|| !progress.set(current.id, GuideProgress.Mark.DONE))
				{
					break;
				}
				changed = true;
			}
			if (changed)
			{
				saveProgress();
			}
		}
		updateMarkers();
		show();
	}

	private GuideFacts readFacts()
	{
		GuideFacts.Builder builder = facts.toBuilder();
		if (skillsDirty)
		{
			for (Skill skill : Skill.values())
			{
				builder.level(skill, client.getRealSkillLevel(skill), client.getBoostedSkillLevel(skill));
			}
		}
		if (accountDirty)
		{
			int type = client.getVarbitValue(VarbitID.IRONMAN);
			builder.accountType(type >= 0 && type < CharacterReader.ACCOUNT_TYPES.length ? CharacterReader.ACCOUNT_TYPES[type]
				: null);
		}
		builder.questPoints(client.getVarpValue(VarPlayerID.QP));
		if (varsDirty)
		{
			// A guide may name an id the game does not have, by mistake or not: RuneLite throws for it, so it is
			// skipped for the rest of the session and its condition stays unknown.
			for (int id : GuideRules.varbits(guide))
			{
				if (!unreadableVarbits.contains(id))
				{
					try
					{
						builder.varbit(id, client.getVarbitValue(id));
					}
					catch (RuntimeException unreadable)
					{
						unreadableVarbits.add(id);
					}
				}
			}
			for (int id : GuideRules.varps(guide))
			{
				if (!unreadableVarps.contains(id))
				{
					try
					{
						builder.varp(id, client.getVarpValue(id));
					}
					catch (RuntimeException unreadable)
					{
						unreadableVarps.add(id);
					}
				}
			}
		}
		if (questsDirty)
		{
			readQuests(builder, GuideRules.quests(guide));
		}
		else if (currentQuestsDirty)
		{
			GuideRules.Count count = GuideRules.count(guide, progress, facts);
			if (count.current != null)
			{
				readQuests(builder, GuideRules.quests(count.current));
			}
		}
		if (itemsDirty)
		{
			Map<Integer, Integer> carried = new HashMap<>();
			add(carried, client.getItemContainer(InventoryID.INV));
			add(carried, client.getItemContainer(InventoryID.WORN));
			builder.carried(carried);
			if (bankSeen)
			{
				builder.banked(bank);
			}
		}
		skillsDirty = false;
		questsDirty = false;
		currentQuestsDirty = false;
		itemsDirty = false;
		accountDirty = false;
		return builder.build();
	}

	/** Runs one client script per quest, so only for the quests asked about. */
	private void readQuests(GuideFacts.Builder builder, Collection<String> names)
	{
		for (String name : names)
		{
			Quest quest = QUESTS.get(name);
			if (quest != null)
			{
				builder.quest(name, host.questState(quest));
			}
		}
	}

	private static void add(Map<Integer, Integer> into, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				into.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
	}

	private boolean questHelperOn()
	{
		return config.guideQuestHelper() && host.questHelperRunning();
	}

	private boolean autoStart()
	{
		return QuestHelperHandOff.autoStart(host.questHelperAutoStart());
	}

	// --- progress --------------------------------------------------------------

	private void loadProgress()
	{
		progress = guideId == null || !loggedIn ? new GuideProgress()
			: GuideProgress.load(host.profile(PROGRESS_KEY_PREFIX + guideId));
	}

	private void saveProgress()
	{
		if (loggedIn && guideId != null)
		{
			host.saveProfile(PROGRESS_KEY_PREFIX + guideId, progress.toJson().toString());
		}
	}

	// --- the map point, the arrow, the tile and the box ---------------------------

	private void updateMarkers()
	{
		if (stopped)
		{
			clearMarkers();
			return;
		}
		GuideRules.Count count = guide == null || !loggedIn || !config.showGuides() ? null
			: GuideRules.count(guide, progress, facts);
		Guide.Step current = count == null ? null : count.current;
		QuestHelperHandOff handOff = current == null ? QuestHelperHandOff.OURS
			: QuestHelperHandOff.of(current, questHelperOn(), autoStart(), gang, facts);
		// While Quest Helper guides the quest under way, this plugin points at nothing: never two at once.
		boolean ours = current != null && handOff.ownsThePlace();
		Guide.Place place = ours ? current.point() : null;
		WorldPoint point = place == null ? null : new WorldPoint(place.x, place.y, place.plane);
		overlays.minimap.target(point != null && config.guideMinimapArrow() ? point : null);
		overlays.tile.target(point != null && config.guideTileHighlight() ? point : null);
		overlays.step.show(ours && config.guideStepBox() ? box(count, current, handOff) : null);

		WorldPoint shown = mapPoint == null ? null : mapPoint.getWorldPoint();
		boolean wanted = point != null && config.guideMapMarker();
		if (wanted && point.equals(shown) && current.title.equals(mapPoint.getName()))
		{
			return;
		}
		if (mapPoint != null)
		{
			worldMap.remove(mapPoint);
			mapPoint = null;
		}
		if (wanted)
		{
			mapPoint = WorldMapPoint.builder()
				.worldPoint(point)
				.image(mapIcon)
				// RuneLite puts the name into the world map's menu, which reads tags: shown as text.
				.name(Text.escapeJagex(current.title))
				.tooltip("OSRS Index: " + current.title)
				.jumpOnClick(true)
				.snapToEdge(true)
				.build();
			worldMap.add(mapPoint);
		}
	}

	/** The step box: the step, where, what to bring that is not carried, and Quest Helper's part before the quest. */
	private GuideStepOverlay.Box box(GuideRules.Count count, Guide.Step step, QuestHelperHandOff handOff)
	{
		List<String> missing = new ArrayList<>();
		for (Guide.Item item : step.bring)
		{
			GuideRules.Have have = GuideRules.item(item, facts);
			if (have != GuideRules.Have.CARRIED && have != GuideRules.Have.NO_ID)
			{
				missing.add(GuidesPanel.itemText(item) + (have == GuideRules.Have.BANKED ? " (in your bank)" : ""));
			}
		}
		Guide.Place where = step.point() != null ? step.point() : step.places.isEmpty() ? null : step.places.get(0);
		String questHelper = handOff.stage != QuestHelperHandOff.Stage.BEFORE_START ? null : handOff.autoStart
			? "Quest Helper takes over when you start " + handOff.quest + "."
			: "Start Quest Helper for " + handOff.quest + " from the Quest List.";
		return new GuideStepOverlay.Box(guide.title, count.toString(), step.title,
			GuideStepOverlay.text(step, facts.ironman()), where == null ? null : where.label, missing, questHelper);
	}

	private void clearMarkers()
	{
		overlays.minimap.target(null);
		overlays.tile.target(null);
		overlays.step.show(null);
		if (mapPoint != null)
		{
			worldMap.remove(mapPoint);
			mapPoint = null;
		}
	}

	private void show()
	{
		lastView = new GuidesPanel.View(listLoad, listing, guideOpen, guideLoad, guide, progress.copy(), facts, loggedIn,
			questHelperOn(), autoStart(), gang, questHelperSelected);
		panel.show(lastView);
	}
}
