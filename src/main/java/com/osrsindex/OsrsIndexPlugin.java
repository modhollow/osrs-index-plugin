package com.osrsindex;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the logged-in character to the player's osrsindex.com account.
 *
 * <p>Nothing leaves the client until it is linked to an account: by a link code the player approves on
 * the site, which the panel's Link button opens with the code already in it, or by a pasted
 * token. Linked, the panel shows the last sync and opens the site's pages for the character, and items
 * and NPCs gain a right-click "OSRS Index" that opens the site's search ({@link SiteLookup}). Changes mark a part "dirty"; at most once every 30 seconds (the
 * server's per-token floor) the dirty parts are read fresh, packed under the body cap, and posted. A part
 * stays dirty until a response confirms it was stored, so a failed or refused sync loses nothing.
 *
 * <p>With "Receive destinations" on, the default, it also holds a socket to osrsindex.com's relay: a place the
 * player sends from the site's map is handed to Shortest Path at once ({@link DestinationSocket},
 * {@link DestinationReceiver}).
 */
@PluginDescriptor(
	name = "OSRS Index",
	description = "Syncs your character to your osrsindex.com account",
	tags = {"tracker", "sync", "bank", "collection log", "quests", "diary", "osrsindex"}
)
public class OsrsIndexPlugin extends Plugin implements AccountLinker.Listener, OsrsIndexPanel.Actions
{
	private static final Logger log = LoggerFactory.getLogger(OsrsIndexPlugin.class);

	static final String TOKEN_PREFIX = "osrsidx_";
	/** This release, as build.gradle's {@code version} says; the plugin bus {@code hello} carries it. */
	static final String VERSION = "0.1.0";
	/** This plugin's id on the plugin bus (docs/plugin-bus.md), which is also its namespace there. */
	static final String BUS_ID = "osrsindex";
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final String CLOG_ITEMS_KEY = "collectionLogItems";
	private static final String KILL_COUNTS_KEY = "killCounts";
	private static final String STORAGE_KEY = "storage";

	/** Client script run once per item as a collection log page draws: (_, itemId, quantity, ...). */
	static final int COLLECTION_LOG_DRAW_ITEM_SCRIPT = 4100;

	/** Varbit-backed parts are cheap to read; compare them this often. */
	private static final int POLL_EVERY_TICKS = 5;

	/** The var allowlist is ~2,200 reads; compare it less often. */
	private static final int POLL_VARS_EVERY_TICKS = 25;

	/** Let stats, varps and the player name arrive before the first read after login. */
	private static final int LOGIN_SETTLE_TICKS = 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OsrsIndexConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CharacterReader reader;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	private final CollectionLogTracker collectionLog = new CollectionLogTracker();
	private final KillCountTracker killCounts = new KillCountTracker();
	private final StorageTracker storage = new StorageTracker();

	/** Part -> change stamp. A part is removed only when a response stored that exact stamp. */
	private final Map<Part, Long> dirty = new EnumMap<>(Part.class);
	/** The last value seen for the polled parts, to notice when one changes. */
	private final Map<Part, JsonElement> lastSeen = new EnumMap<>(Part.class);

	private AccountLinker linker;
	private OsrsIndexPanel panel;
	private NavigationButton navigationButton;
	private PluginBus pluginBus;
	private EventBus.Subscriber pluginBusSubscriber;
	private DestinationSocket destinationSocket;
	private DestinationReceiver destinationReceiver;
	/** Whether someone is logged in; hopping and loading keep it. Read off the client thread. */
	private volatile boolean loggedIn;

	private JsonElement capturedBank;
	private long stamp;
	private int ticksSinceLogin = -1;
	private int tickCount;
	private boolean profileLoaded;
	private boolean profileUnsaved;
	private boolean linkOfferedThisSession;
	/** The player clicked Link: open the approval page once the code arrives. Set on the client thread, read on OkHttp's. */
	private volatile boolean openWhenCodeArrives;
	/** What the linked panel shows: the logged-in character and the last sync, or null. */
	private volatile String panelCharacter;
	private volatile String lastSync;

	private int schema = Part.LATEST_SCHEMA;
	private int budgetBytes = PayloadPacker.DEFAULT_BUDGET_BYTES;
	private boolean inFlight;
	private long nextSendAtMillis;
	private String rejectedToken;
	private boolean announcedFirstSync;

	@Provides
	OsrsIndexConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsIndexConfig.class);
	}

	@Override
	protected void startUp()
	{
		linker = new AccountLinker(httpClient, gson, this);
		panel = new OsrsIndexPanel(this);
		navigationButton = NavigationButton.builder()
			.tooltip("OSRS Index")
			.icon(icon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		refreshPanel();

		pluginBus = new PluginBus(eventBus::post, new PluginBus.Member(BUS_ID, "OSRS Index", VERSION,
			PluginBus.BUS_VERSION, Collections.emptyList(), Collections.emptyList()));
		pluginBusSubscriber = eventBus.register(PluginMessage.class, pluginBus::onPluginMessage, 0);
		pluginBus.start();

		RouteDispatcher dispatcher = new RouteDispatcher(eventBus::post, RouteDispatcher.activeIn(pluginManager),
			Clock.systemUTC());
		destinationSocket = new DestinationSocket(new OkHttpConnector(httpClient, Endpoints::destinations), this::schedule,
			new Random(), destination -> clientThread.invokeLater(() -> destinationReceiver.accept(destination)),
			this::showDestinationStatus);
		// Always Shortest Path, never Farm Route Planner.
		destinationReceiver = new DestinationReceiver(dispatcher, () -> RouteWith.SHORTEST_PATH, this::gameMessage,
			destinationSocket::ack);
		showDestinationStatus(DestinationSocket.Status.OFF);

		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				ticksSinceLogin = 0;
				loggedIn = true;
			}
			updateDestinations(true);
		});
	}

	@Override
	protected void shutDown()
	{
		if (destinationSocket != null)
		{
			destinationSocket.stop();
			destinationSocket = null;
		}
		loggedIn = false;
		if (pluginBus != null)
		{
			pluginBus.stop();
			eventBus.unregister(pluginBusSubscriber);
			pluginBus = null;
		}
		saveProfile();
		resetSession();
		if (linker != null)
		{
			linker.cancel();
		}
		clientToolbar.removeNavigation(navigationButton);
	}

	private String token()
	{
		return config.token() == null ? "" : config.token().trim();
	}

	// --- linking -----------------------------------------------------------

	@Override
	public void stateChanged(AccountLinker.State state, String userCode)
	{
		boolean open = state == AccountLinker.State.WAITING && openWhenCodeArrives;
		if (state != AccountLinker.State.STARTING)
		{
			openWhenCodeArrives = false;
		}
		switch (state)
		{
			case WAITING:
				panel.showCode(userCode);
				if (open)
				{
					LinkBrowser.browse(linker.approvalUrl());
					chat("Approve this client in your browser. The page shows the code " + userCode + ". After you "
						+ "approve, linking takes up to about 15 seconds.");
				}
				else
				{
					chat("Link this client to your osrsindex.com account: click Link with osrsindex.com in the "
						+ "OSRS Index panel, then approve it on the site. Nothing to type, and it links within about "
						+ "15 seconds of approving.");
				}
				break;
			case EXPIRED:
				panel.showNotLinked("The link code expired. Click Link with osrsindex.com to get a new one.");
				break;
			case UNAVAILABLE:
				panel.showNotLinked("osrsindex.com cannot link by code right now. Create a token on your "
					+ "account page and paste it into this plugin's settings instead.");
				break;
			case TOO_MANY_TOKENS:
				panel.showNotLinked("Your account already has 5 linked clients. Revoke one on your account page, "
					+ "then get a new code.");
				break;
			case IDLE:
			case STARTING:
			default:
				refreshPanel();
				break;
		}
	}

	@Override
	public void linked(String token)
	{
		// Saving the token fires ConfigChanged, which resends everything.
		configManager.setConfiguration(OsrsIndexConfig.GROUP, "token", token);
		chat("Linked to your osrsindex.com account.");
		refreshPanel();
	}

	@Override
	public void linkWithBrowser()
	{
		String url = linker.approvalUrl();
		if (url != null)
		{
			LinkBrowser.browse(url);
			return;
		}
		startLink();
	}

	@Override
	public void requestNewCode()
	{
		startLink();
	}

	/** Ask for a code, and open the site's approval page as soon as it arrives: the player asked for both. */
	private void startLink()
	{
		clientThread.invoke(() ->
		{
			String character = reader.characterName();
			if (character == null)
			{
				panel.showNotLinked("Log in to the game first, then click Link with osrsindex.com.");
				return;
			}
			openWhenCodeArrives = true;
			linker.start(character);
		});
	}

	@Override
	public void openMapHere()
	{
		clientThread.invoke(() ->
		{
			JsonObject location = reader.location();
			LinkBrowser.browse(location == null ? Endpoints.map() : Endpoints.map(location.get("x").getAsInt(),
				location.get("y").getAsInt(), location.get("plane").getAsInt()));
		});
	}

	@Override
	public void unlink()
	{
		lastSync = null;
		configManager.unsetConfiguration(OsrsIndexConfig.GROUP, "token");
		chat("Unlinked. Nothing more is sent until you link again. To remove what was sent, use "
			+ "\"Delete tracker data\" on your account page.");
		refreshPanel();
	}

	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		if (!token().isEmpty())
		{
			panel.showLinked(panelCharacter, lastSync);
		}
		else if (linker != null && linker.state() == AccountLinker.State.WAITING)
		{
			panel.showCode(linker.userCode());
		}
		else
		{
			panel.showNotLinked(null);
		}
	}

	/** Offer a code once per session when the client is not linked, so linking needs no setup at all. */
	private void maybeOfferLink()
	{
		if (!token().isEmpty() || linkOfferedThisSession || linker.state() != AccountLinker.State.IDLE)
		{
			return;
		}
		String character = reader.characterName();
		if (character != null)
		{
			linkOfferedThisSession = true;
			linker.start(character);
		}
	}

	// --- events ------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				if (ticksSinceLogin < 0)
				{
					ticksSinceLogin = 0;
				}
				if (!loggedIn)
				{
					// A real login, not a region load: a fresh start for the destinations socket.
					loggedIn = true;
					updateDestinations(true);
				}
				break;
			case LOGIN_SCREEN:
				// The next login may be a different account.
				saveProfile();
				resetSession();
				loggedIn = false;
				updateDestinations(false);
				panelCharacter = null;
				refreshPanel();
				break;
			default:
				// HOPPING and LOADING keep the session: same account, same data.
				break;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrsIndexConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("token".equals(event.getKey()))
		{
			// A new token deserves a fresh start: resend everything and forget the old refusal.
			rejectedToken = null;
			nextSendAtMillis = 0;
			schema = Part.LATEST_SCHEMA;
			clientThread.invoke(this::markAllDirty);
			refreshPanel();
			updateDestinations(true);
		}
		else if ("receiveDestinations".equals(event.getKey()))
		{
			updateDestinations(true);
		}
		else if (event.getKey().startsWith("sync"))
		{
			// A category switched back on sends its current state. Profile keys such as the saved
			// collection log land here too, and must not trigger a resend.
			clientThread.invoke(this::markAllDirty);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.WORN)
		{
			markDirty(Part.EQUIPMENT);
		}
		else if (id == InventoryID.INV)
		{
			markDirty(Part.INVENTORY);
		}
		else if (id == InventoryID.BANK)
		{
			// Readable only while open, so capture it now rather than at send time.
			capturedBank = reader.bank();
			if (capturedBank != null)
			{
				markDirty(Part.BANK);
			}
		}
		else
		{
			String store = StorageTracker.keyFor(id);
			if (store != null)
			{
				JsonArray items = reader.items(id, CharacterReader.MAX_BANK_ITEMS);
				if (profileLoaded && storage.put(store, items))
				{
					profileUnsaved = true;
					markDirty(Part.STORAGE);
				}
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		markDirty(Part.SKILLS);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarpId() == VarPlayerID.QP)
		{
			markDirty(Part.QUESTS);
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		markDirty(Part.GRAND_EXCHANGE);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		if (profileLoaded && killCounts.offer(Text.removeTags(event.getMessage())))
		{
			profileUnsaved = true;
			markDirty(Part.KILL_COUNTS);
		}
	}

	/** Back from approving in the browser: collect the token now rather than at the next 15 s poll. */
	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (event.isFocused() && linker != null)
		{
			linker.pollSoon();
		}
	}

	/** An item or NPC's Examine gains "OSRS Index", which opens the site's search for it. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.lookupMenu())
		{
			return;
		}
		MenuEntry entry = event.getMenuEntry();
		int itemId = event.getItemId();
		if (itemId <= 0 && entry.getWidget() != null)
		{
			itemId = entry.getWidget().getItemId();
		}
		String name;
		switch (SiteLookup.kindOf(event.getOption(), entry.getType(), itemId))
		{
			case ITEM:
				int id = entry.getType() == MenuAction.EXAMINE_ITEM_GROUND ? event.getIdentifier() : itemId;
				name = SiteLookup.cleanName(itemManager.getItemComposition(itemManager.canonicalize(id)).getMembersName());
				break;
			case NPC:
				NPC npc = entry.getNpc();
				name = npc == null ? null : SiteLookup.cleanName(npc.getName());
				break;
			case NONE:
			default:
				return;
		}
		if (name == null)
		{
			return;
		}
		String url = Endpoints.search(name);
		client.getMenu().createMenuEntry(-1)
			.setOption(SiteLookup.OPTION)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.setDeprioritized(true)
			.onClick(clicked -> LinkBrowser.browse(url));
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_DRAW_ITEM_SCRIPT || event.getScriptEvent() == null
			|| !viewingOwnCollectionLog())
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		recordCollectionLogItem((Integer) args[1], (Integer) args[2]);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (ticksSinceLogin < 0 || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		ticksSinceLogin++;
		if (ticksSinceLogin < LOGIN_SETTLE_TICKS)
		{
			return;
		}
		if (ticksSinceLogin == LOGIN_SETTLE_TICKS)
		{
			loadProfile();
			markAllDirty();
			panelCharacter = reader.characterName();
			refreshPanel();
		}

		maybeOfferLink();
		linker.tick();
		readOpenCollectionLogPage();
		tickCount++;
		if (tickCount % POLL_EVERY_TICKS == 0)
		{
			pollForChanges();
		}
		if (tickCount % POLL_VARS_EVERY_TICKS == 0)
		{
			poll(Part.VARS, reader.vars());
		}
		if (profileUnsaved && tickCount % 100 == 0)
		{
			saveProfile();
		}
		maybeSend();
	}

	// --- change tracking ---------------------------------------------------

	private void markDirty(Part part)
	{
		dirty.put(part, ++stamp);
	}

	private void markAllDirty()
	{
		lastSeen.clear();
		for (Part part : Part.values())
		{
			if (part != Part.BANK || capturedBank != null)
			{
				markDirty(part);
			}
		}
	}

	/** Mark a polled part dirty when its value moved since it was last seen. */
	private void pollForChanges()
	{
		poll(Part.LOCATION, reader.location());
		poll(Part.ACCOUNT, reader.account());
		poll(Part.DIARIES, reader.diaries());
		poll(Part.COMBAT_ACHIEVEMENTS, reader.combatAchievements());
		poll(Part.COLLECTION_LOG, reader.collectionLog(collectionLog));
	}

	private void poll(Part part, JsonElement value)
	{
		if (value != null && !value.equals(lastSeen.get(part)))
		{
			lastSeen.put(part, value);
			markDirty(part);
		}
	}

	private boolean enabled(Part part)
	{
		switch (part)
		{
			case EQUIPMENT:
			case INVENTORY:
				return config.syncGear();
			case BANK:
			case STORAGE:
			case GRAND_EXCHANGE:
				return config.syncBank();
			case ACCOUNT:
			case SKILLS:
			case QUESTS:
			case DIARIES:
			case COMBAT_ACHIEVEMENTS:
			case KILL_COUNTS:
			case VARS:
				return config.syncProgress();
			case COLLECTION_LOG:
				return config.syncCollectionLog();
			case LOCATION:
				return config.syncLocation();
			default:
				return false;
		}
	}

	private JsonElement read(Part part)
	{
		switch (part)
		{
			case EQUIPMENT:
				return reader.equipment();
			case INVENTORY:
				return reader.inventory();
			case BANK:
				return capturedBank;
			case ACCOUNT:
				return reader.account();
			case SKILLS:
				return reader.skills();
			case QUESTS:
				return reader.quests();
			case DIARIES:
				return reader.diaries();
			case COMBAT_ACHIEVEMENTS:
				return reader.combatAchievements();
			case COLLECTION_LOG:
				return reader.collectionLog(collectionLog);
			case LOCATION:
				return reader.location();
			case VARS:
				return reader.vars();
			case STORAGE:
				return storage.size() == 0 ? null : storage.toJson();
			case KILL_COUNTS:
				return killCounts.size() == 0 ? null : killCounts.toJson();
			case GRAND_EXCHANGE:
				return reader.grandExchange();
			default:
				return null;
		}
	}

	// --- collection log ----------------------------------------------------

	private void recordCollectionLogItem(int id, int quantity)
	{
		if (profileLoaded && collectionLog.record(id, quantity))
		{
			profileUnsaved = true;
			markDirty(Part.COLLECTION_LOG);
		}
	}

	/** Read the items of the log page on screen: an obtained item is drawn fully opaque. */
	private void readOpenCollectionLogPage()
	{
		if (!viewingOwnCollectionLog())
		{
			return;
		}
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (items == null || items.isHidden() || items.getDynamicChildren() == null)
		{
			return;
		}
		for (Widget item : items.getDynamicChildren())
		{
			if (item != null && item.getItemId() > 0 && item.getOpacity() == 0)
			{
				recordCollectionLogItem(item.getItemId(), Math.max(1, item.getItemQuantity()));
			}
		}
	}

	/**
	 * True while the player's own collection log is open. The POH Adventure Log shows another player's
	 * log in the same interface, with that player's name in the header, and those items are not ours.
	 */
	private boolean viewingOwnCollectionLog()
	{
		Widget frame = client.getWidget(InterfaceID.Collection.FRAME);
		if (frame == null || frame.isHidden())
		{
			return false;
		}
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		String name = reader.characterName();
		if (header == null || name == null)
		{
			return header == null;
		}
		String title = Text.removeTags(header.getText() == null ? "" : header.getText()).replace(' ', ' ');
		int dash = title.indexOf(" - ");
		return dash < 0 || title.substring(dash + 3).trim().equalsIgnoreCase(name.replace(' ', ' '));
	}

	// --- profile persistence -----------------------------------------------

	private void loadProfile()
	{
		if (!profileLoaded)
		{
			collectionLog.load(configManager.getRSProfileConfiguration(OsrsIndexConfig.GROUP, CLOG_ITEMS_KEY));
			killCounts.load(configManager.getRSProfileConfiguration(OsrsIndexConfig.GROUP, KILL_COUNTS_KEY));
			storage.load(configManager.getRSProfileConfiguration(OsrsIndexConfig.GROUP, STORAGE_KEY));
			profileLoaded = true;
		}
	}

	private void saveProfile()
	{
		if (profileLoaded && profileUnsaved)
		{
			configManager.setRSProfileConfiguration(OsrsIndexConfig.GROUP, CLOG_ITEMS_KEY, gson.toJson(collectionLog.toJson()));
			configManager.setRSProfileConfiguration(OsrsIndexConfig.GROUP, KILL_COUNTS_KEY, gson.toJson(killCounts.toJson()));
			configManager.setRSProfileConfiguration(OsrsIndexConfig.GROUP, STORAGE_KEY, gson.toJson(storage.toJson()));
			profileUnsaved = false;
		}
	}

	// --- sending -----------------------------------------------------------

	private void maybeSend()
	{
		String token = token();
		if (inFlight || dirty.isEmpty() || token.isEmpty() || token.equals(rejectedToken)
			|| System.currentTimeMillis() < nextSendAtMillis)
		{
			return;
		}
		if (!token.startsWith(TOKEN_PREFIX))
		{
			rejectedToken = token;
			chat("That token does not look like one from osrsindex.com/account (it should start with "
				+ TOKEN_PREFIX + ").");
			return;
		}
		String character = reader.characterName();
		if (character == null)
		{
			return;
		}

		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		Map<Part, Long> stamps = new EnumMap<>(Part.class);
		for (Map.Entry<Part, Long> entry : dirty.entrySet())
		{
			Part part = entry.getKey();
			if (!enabled(part) || part.minSchema > schema)
			{
				continue;
			}
			JsonElement value = read(part);
			if (value != null)
			{
				pending.put(part, value);
				stamps.put(part, entry.getValue());
			}
		}
		String capturedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		PayloadPacker.Packed packed = PayloadPacker.pack(gson, schema, character, capturedAt, pending, budgetBytes);
		if (packed == null)
		{
			return;
		}

		inFlight = true;
		post(token, packed, stamps);
	}

	private void post(String token, PayloadPacker.Packed packed, Map<Part, Long> stamps)
	{
		Request request = new Request.Builder()
			.url(Endpoints.sync())
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, packed.json))
			.build();
		int sentSchema = schema;
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS Index sync failed to send", e);
				clientThread.invoke(() -> settle(SyncOutcome.networkFailure(), token, packed.parts, stamps, sentSchema));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				SyncOutcome outcome;
				try (ResponseBody body = response.body())
				{
					outcome = SyncOutcome.of(response.code(), body == null ? "" : body.string(),
						response.header("Retry-After"));
				}
				catch (IOException e)
				{
					outcome = SyncOutcome.networkFailure();
				}
				SyncOutcome settled = outcome;
				clientThread.invoke(() -> settle(settled, token, packed.parts, stamps, sentSchema));
			}
		});
	}

	/** Apply a response on the client thread. */
	private void settle(SyncOutcome outcome, String token, Set<Part> parts, Map<Part, Long> stamps, int sentSchema)
	{
		inFlight = false;
		long now = System.currentTimeMillis();
		switch (outcome.kind)
		{
			case STORED:
				for (Part part : parts)
				{
					dirty.remove(part, stamps.get(part));
				}
				nextSendAtMillis = now + outcome.waitSeconds * 1_000L;
				if (!announcedFirstSync)
				{
					announcedFirstSync = true;
					chat("Synced to your osrsindex.com account.");
				}
				lastSync = syncSummary(LocalTime.now(), parts);
				refreshPanel();
				break;
			case OLD_SERVER:
				// Step down one schema at a time: a site on schema 2 still takes every schema 2 section.
				if (sentSchema > 1)
				{
					if (schema == sentSchema)
					{
						schema = sentSchema - 1;
						log.info("OSRS Index: the site does not accept schema {} yet; trying {}", sentSchema, schema);
					}
					break;
				}
				// Schema 1 has nowhere lower to go: this is a real contract break.
				// fall through
			case REJECTED:
				for (Part part : parts)
				{
					dirty.remove(part, stamps.get(part));
				}
				log.warn("OSRS Index refused a sync of {}: {}", parts, outcome.code);
				chat("The site refused part of a sync (" + outcome.code + "). It will try again when that data changes.");
				break;
			case BAD_TOKEN:
				rejectedToken = token;
				chat("osrsindex.com did not accept this client's link. Open the OSRS Index panel to link it again.");
				configManager.unsetConfiguration(OsrsIndexConfig.GROUP, "token");
				linkOfferedThisSession = false;
				break;
			case TOO_LARGE:
				budgetBytes = Math.max(16_000, budgetBytes / 2);
				break;
			case RETRY:
			default:
				nextSendAtMillis = now + outcome.waitSeconds * 1_000L;
				break;
		}
	}

	private void resetSession()
	{
		dirty.clear();
		lastSeen.clear();
		capturedBank = null;
		collectionLog.clear();
		killCounts.clear();
		storage.clear();
		profileLoaded = false;
		profileUnsaved = false;
		ticksSinceLogin = -1;
		schema = Part.LATEST_SCHEMA;
		budgetBytes = PayloadPacker.DEFAULT_BUDGET_BYTES;
		announcedFirstSync = false;
		linkOfferedThisSession = false;
	}

	/** The panel's last-sync line: "Last sync at 14:02: location, skills and bank." in the contract's order. */
	static String syncSummary(LocalTime at, Set<Part> parts)
	{
		List<String> names = new ArrayList<>();
		for (Part part : Part.values())
		{
			if (parts.contains(part))
			{
				names.add(part.key.replace('_', ' '));
			}
		}
		String when = "Last sync at " + at.format(DateTimeFormatter.ofPattern("HH:mm"));
		if (names.isEmpty())
		{
			return when + ".";
		}
		String last = names.remove(names.size() - 1);
		return when + ": " + (names.isEmpty() ? last : String.join(", ", names) + " and " + last) + ".";
	}

	private void chat(String message)
	{
		gameMessage("OSRS Index: " + message);
	}

	/** A line in game chat, as given. A destination's line names osrsindex.com itself. */
	private void gameMessage(String message)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	// --- destinations ------------------------------------------------------

	private void updateDestinations(boolean fresh)
	{
		DestinationSocket socket = destinationSocket;
		if (socket != null)
		{
			socket.update(config.receiveDestinations(), token(), loggedIn, fresh);
		}
	}

	private DestinationSocket.Cancellable schedule(Runnable task, long delayMillis)
	{
		ScheduledFuture<?> future = executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
		return () -> future.cancel(false);
	}

	private void showDestinationStatus(DestinationSocket.Status status)
	{
		if (panel != null)
		{
			panel.showDestinations(destinationStatusText(status, config.receiveDestinations(), !token().isEmpty(),
				loggedIn));
		}
	}

	/** The panel's status line for the destinations socket. */
	static String destinationStatusText(DestinationSocket.Status status, boolean optedIn, boolean linked,
		boolean loggedIn)
	{
		switch (status)
		{
			case LISTENING:
				return "Listening for osrsindex.com destinations.";
			case CONNECTING:
				return "Not listening yet: connecting to osrsindex.com...";
			case RETRYING:
				return "Not listening: the connection dropped. Reconnecting shortly.";
			case REVOKED:
				return "Not listening: osrsindex.com revoked this link. Link again to receive places.";
			case REPLACED:
				return "Not listening: another RuneLite client with this link is listening instead.";
			case OFF:
			default:
				if (!optedIn)
				{
					return "Not listening: \"Receive destinations\" is off in this plugin's settings.";
				}
				if (!linked)
				{
					return "Not listening: link this client first.";
				}
				return loggedIn ? "Not listening." : "Not listening: log in to the game first.";
		}
	}

	/** A small orange roundel; drawn rather than shipped so the plugin carries no image asset. */
	private static BufferedImage icon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xDC, 0x8A, 0x00));
		g.fillOval(1, 1, 14, 14);
		g.setColor(new Color(0x1E, 0x1E, 0x1E));
		g.fillOval(5, 5, 6, 6);
		g.dispose();
		return image;
	}
}
