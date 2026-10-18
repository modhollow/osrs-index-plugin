package com.osrsindex;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
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
import java.util.EnumSet;
import java.util.HashSet;
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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
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
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
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
 * token. Linked, the panel shows the last sync and opens the site's pages for the character. Changes mark a part
 * "dirty"; at most once every 30 seconds (the server's per-token floor) the dirty parts are read fresh, packed under
 * the body cap, and posted. A part stays dirty until a response confirms it was stored, so a failed or refused sync
 * loses nothing.
 *
 * <p>With "Receive destinations" on, the default, it also holds a socket to osrsindex.com's relay: a place the
 * player sends from the site's map is handed to Shortest Path at once ({@link DestinationSocket},
 * {@link DestinationReceiver}).
 *
 * <p>Linked, it also reads what osrsindex.com asks of it ({@link SiteConfig}, docs/site-config.md): waits and
 * limits, parts to pause, which vars to send and a notice for the panel, so the site can change those without a
 * plugin release. Without that config, or before it arrives, the plugin keeps its built-in values.
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
	static final String VERSION = "0.4.0";
	/** This plugin's id on the plugin bus (docs/plugin-bus.md), which is also its namespace there. */
	static final String BUS_ID = "osrsindex";
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final String CLOG_ITEMS_KEY = "collectionLogItems";
	private static final String KILL_COUNTS_KEY = "killCounts";
	private static final String STORAGE_KEY = "storage";
	/** When the stores and kill counts above last changed ({@link PartTimes}). Not "sync...": that prefix resends. */
	private static final String PART_TIMES_KEY = "partTimes";

	/** Client script run once per item as a collection log page draws: (_, itemId, quantity, ...). */
	static final int COLLECTION_LOG_DRAW_ITEM_SCRIPT = 4100;

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

	/** RuneLite's shared client. Other plugins use it too, so requests go through {@link #siteHttp}, derived from it. */
	@Inject
	private OkHttpClient httpClient;

	/** Every request to osrsindex.com goes through this, so each one names this plugin's version ({@link SiteHttp}). */
	private OkHttpClient siteHttp;

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

	private final CollectionLogTracker collectionLog = new CollectionLogTracker();
	private final KillCountTracker killCounts = new KillCountTracker();
	private final StorageTracker storage = new StorageTracker();
	/** When the bank was read and the stores and kill counts last changed, for schema 5's {@code captured}. */
	private final PartTimes partTimes = new PartTimes();

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

	/**
	 * What osrsindex.com asks of this client: the built-in values until it answers. Varbit-backed parts are cheap
	 * to read and are compared every {@code pollTicks}; the vars, ~2,200 reads, every {@code pollVarsTicks}.
	 */
	private volatile SiteConfig siteConfig = SiteConfig.DEFAULTS;
	private SiteConfigFetcher siteConfigFetcher;
	/** The notices already put in chat while the client runs, by id. */
	private final Set<String> chattedNotices = new HashSet<>();
	/** The last site message chat showed for a wait, so the same one is not repeated on every retry. */
	private String lastWaitMessage;

	/** The highest schema the site has not refused this session; {@link #sendSchema} also caps it by the config. */
	private int schema = Part.LATEST_SCHEMA;
	private int budgetBytes = PayloadPacker.DEFAULT_BUDGET_BYTES;
	/** False once the site refused the part times this session (invalid_captured): bodies then go out undated. */
	private boolean sendPartTimes = true;
	private boolean inFlight;
	private long nextSendAtMillis;
	private String rejectedToken;
	private boolean announcedFirstSync;
	private boolean announcedAccountFull;
	/** Parts the site refused alone as too large this session, so each is logged once, not on every change. */
	private final Set<Part> warnedTooLarge = EnumSet.noneOf(Part.class);

	@Provides
	OsrsIndexConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsIndexConfig.class);
	}

	@Override
	protected void startUp()
	{
		siteHttp = SiteHttp.derive(httpClient, VERSION);
		siteConfig = SiteConfig.DEFAULTS;
		siteConfigFetcher = new SiteConfigFetcher(siteHttp, next -> clientThread.invoke(() -> applySiteConfig(next)));
		linker = new AccountLinker(siteHttp, gson, this);
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
		destinationSocket = new DestinationSocket(new OkHttpConnector(siteHttp, Endpoints::destinations), this::schedule,
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
				// A code comes only from the player's own click, which also asked for the approval page.
				if (open)
				{
					LinkBrowser.browse(linker.approvalUrl());
				}
				chat("Approve this client in your browser. The page shows the code " + userCode + ". After you "
					+ "approve, linking takes up to about 15 seconds.");
				break;
			case EXPIRED:
				panel.showNotLinked(linkLine("The link code expired. Click Link with osrsindex.com to get a new one."));
				break;
			case UNAVAILABLE:
				panel.showNotLinked(linkLine("osrsindex.com cannot link by code right now. Create a token on your "
					+ "account page and paste it into this plugin's settings instead."));
				break;
			case TOO_MANY_TOKENS:
				panel.showNotLinked(linkLine("Your account already has 5 linked clients. Revoke one on your account "
					+ "page, then get a new code."));
				break;
			case IDLE:
			case STARTING:
			default:
				refreshPanel();
				break;
		}
	}

	/** The site's own line for the link's last answer, escaped for the panel, or this plugin's {@code fallback}. */
	private String linkLine(String fallback)
	{
		String site = linker.playerMessage();
		return site == null ? fallback : OsrsIndexPanel.escape(site);
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

	/**
	 * Unlink this client: ask the site to revoke the token, so it stops counting toward the account's 5
	 * linked clients, then forget it here without waiting for the answer, so unlinking works offline.
	 * A second chat line says whether the site confirmed it ({@link TokenRevoker}).
	 */
	@Override
	public void unlink()
	{
		String token = token();
		lastSync = null;
		// This line first, so it is never queued behind the answer's line.
		chat("Unlinked. Nothing more is sent until you link again. To remove what was sent, use "
			+ "\"Delete tracker data\" on your account page.");
		// A token not in the site's form was never sent, so the site has nothing to revoke.
		if (token.startsWith(TOKEN_PREFIX))
		{
			new TokenRevoker(siteHttp).revoke(token, result -> chat(TokenRevoker.chatLine(result)));
		}
		configManager.unsetConfiguration(OsrsIndexConfig.GROUP, "token");
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

	/**
	 * Once per session, when the client is not linked, say in chat how to link it. This sends nothing: an unlinked
	 * client makes no request to osrsindex.com until the player clicks Link with osrsindex.com in the panel.
	 */
	private void maybeOfferLink()
	{
		if (!token().isEmpty() || linkOfferedThisSession || linker.state() != AccountLinker.State.IDLE)
		{
			return;
		}
		if (reader.characterName() != null)
		{
			linkOfferedThisSession = true;
			chat(LINK_HINT);
		}
	}

	/** The once-a-session line for an unlinked client. */
	static final String LINK_HINT = "This client is not linked to osrsindex.com, so nothing is sent. To link it, click"
		+ " Link with osrsindex.com in the OSRS Index panel.";

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
			lastWaitMessage = null;
			if (token().isEmpty())
			{
				// Unlinked: the site's config was for that link. A new token asks for its own on the next tick.
				siteConfigFetcher.tick("");
			}
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
			// Readable only while open, so capture it now rather than at send time, and keep when.
			capturedBank = reader.bank();
			if (capturedBank != null)
			{
				partTimes.mark(Part.BANK, Instant.now());
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
					partTimes.mark(Part.STORAGE, Instant.now());
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
			partTimes.mark(Part.KILL_COUNTS, Instant.now());
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
		siteConfigFetcher.tick(token());
		readOpenCollectionLogPage();
		tickCount++;
		SiteConfig site = siteConfig;
		if (tickCount % site.timing.pollTicks == 0)
		{
			pollForChanges();
		}
		if (tickCount % site.timing.pollVarsTicks == 0)
		{
			poll(Part.VARS, reader.vars(sendSchema(), site.vars));
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

	/** The player has the part on, and the site has not paused it: the site can only ever pause more. */
	private boolean enabled(Part part)
	{
		return siteConfig.allows(part) && playerEnabled(part);
	}

	private boolean playerEnabled(Part part)
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

	private JsonElement read(Part part, int sendSchema)
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
				return reader.vars(sendSchema, siteConfig.vars);
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
			partTimes.load(configManager.getRSProfileConfiguration(OsrsIndexConfig.GROUP, PART_TIMES_KEY));
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
			configManager.setRSProfileConfiguration(OsrsIndexConfig.GROUP, PART_TIMES_KEY, gson.toJson(partTimes.toJson()));
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
		if (character == null || siteConfigFetcher.awaitingFirstAnswer() || siteConfig != siteConfigFetcher.current())
		{
			// The first sync waits briefly for the site's config, so a part it paused does not go out first. A config
			// that has come but is not applied yet is applied on this thread before the next tick.
			return;
		}

		int sendSchema = sendSchema();
		Map<Part, JsonElement> pending = new EnumMap<>(Part.class);
		Map<Part, Long> stamps = new EnumMap<>(Part.class);
		for (Map.Entry<Part, Long> entry : dirty.entrySet())
		{
			Part part = entry.getKey();
			if (!enabled(part) || part.minSchema > sendSchema)
			{
				continue;
			}
			JsonElement value = read(part, sendSchema);
			if (value != null)
			{
				pending.put(part, value);
				stamps.put(part, entry.getValue());
			}
		}
		String capturedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		Map<Part, Instant> times = sendPartTimes ? partTimes.asMap() : Collections.<Part, Instant>emptyMap();
		PayloadPacker.Packed packed = PayloadPacker.pack(gson, sendSchema, character, capturedAt, pending, times,
			budgetBytes);
		if (packed == null)
		{
			return;
		}

		inFlight = true;
		post(token, packed, stamps, sendSchema);
	}

	/** The schema to send: the highest the site has not refused, and no higher than its config allows. */
	private int sendSchema()
	{
		return Math.min(schema, siteConfig.maxSchema);
	}

	/** One sync: the body to the sync endpoint, with the token as a bearer. {@link #siteHttp} adds the version. */
	static Request syncRequest(String token, String json)
	{
		return new Request.Builder()
			.url(Endpoints.sync())
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, json))
			.build();
	}

	private void post(String token, PayloadPacker.Packed packed, Map<Part, Long> stamps, int sentSchema)
	{
		Request request = syncRequest(token, packed.json);
		SiteConfig.Timing timing = siteConfig.timing;
		siteHttp.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS Index sync failed to send", e);
				clientThread.invoke(() -> settle(SyncOutcome.networkFailure(timing), token, packed.parts, stamps,
					sentSchema));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				SyncOutcome outcome;
				try (ResponseBody body = response.body())
				{
					outcome = SyncOutcome.of(response.code(), body == null ? "" : body.string(),
						response.header("Retry-After"), timing);
				}
				catch (IOException e)
				{
					outcome = SyncOutcome.networkFailure(timing);
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
				lastWaitMessage = null;
				lastSync = syncSummary(LocalTime.now(), parts);
				refreshPanel();
				if (outcome.configVersion >= 0)
				{
					siteConfigFetcher.changed(outcome.configVersion);
				}
				break;
			case OLD_SERVER:
				// Step down one schema at a time: a site on schema 2 still takes every schema 2 section.
				if (sentSchema > 1)
				{
					// The config may have capped what was sent below schema: step down from what was sent.
					if (schema >= sentSchema)
					{
						schema = sentSchema - 1;
						log.info("OSRS Index: the site does not accept schema {} yet; trying {}", sentSchema, schema);
					}
					break;
				}
				// Schema 1 has nowhere lower to go: this is a real contract break.
				// fall through
			case PART_TIMES_REFUSED:
				if (outcome.kind == SyncOutcome.Kind.PART_TIMES_REFUSED && sendPartTimes)
				{
					// The site refused the times, not the parts: they stay dirty and go out again at once, undated,
					// for the rest of the session. A refusal does not start the site's floor.
					sendPartTimes = false;
					log.warn("OSRS Index: the site refused the part times in a sync of {}; sending without them", parts);
					break;
				}
				// The body already carried no times, so the refusal is about something else.
				// fall through
			case REJECTED:
				// When the site names the part it refused, only that part waits for its next change. The
				// others stay dirty and go out on the next send: a refusal does not start the site's floor.
				Set<Part> dropped = outcome.dropped(parts);
				for (Part part : dropped)
				{
					dirty.remove(part, stamps.get(part));
				}
				log.warn("OSRS Index refused a sync of {}: {}; dropped {}", parts, outcome.code, dropped);
				chat(outcome.playerMessage != null ? RouteDispatcher.chatSafe(outcome.playerMessage)
					: "The site refused part of a sync (" + outcome.code + "). It will try again when that data changes.");
				break;
			case BAD_TOKEN:
				if (!token.equals(token()))
				{
					// The player unlinked while this sync was out, and Unlink revoked the token it carried; or
					// they have linked again since. Either way the refusal is not about the current link.
					break;
				}
				rejectedToken = token;
				chat(outcome.playerMessage != null ? RouteDispatcher.chatSafe(outcome.playerMessage)
					: "osrsindex.com did not accept this client's link. Open the OSRS Index panel to link it again.");
				configManager.unsetConfiguration(OsrsIndexConfig.GROUP, "token");
				linkOfferedThisSession = false;
				break;
			case TOO_LARGE:
				nextSendAtMillis = now + outcome.waitSeconds * 1_000L;
				Part tooLarge = PayloadPacker.tooLargeAlone(parts);
				if (tooLarge == null)
				{
					budgetBytes = PayloadPacker.halved(budgetBytes);
					if (outcome.playerMessage != null)
					{
						lastSync = siteWait(outcome.playerMessage);
						refreshPanel();
					}
					break;
				}
				// A smaller budget cannot help a lone part: drop it as a refused part is, until it changes.
				dirty.remove(tooLarge, stamps.get(tooLarge));
				if (warnedTooLarge.add(tooLarge))
				{
					log.warn("OSRS Index: the site refused {} as too large; it is sent again when it changes",
						tooLarge.key);
				}
				lastSync = outcome.playerMessage != null ? siteWait(outcome.playerMessage) : tooLargeLine(LocalTime.now(),
					tooLarge);
				refreshPanel();
				break;
			case ACCOUNT_FULL:
				// Nothing was stored. Keep the changes, and ask again rarely: the player may free a slot.
				nextSendAtMillis = now + outcome.waitSeconds * 1_000L;
				if (!announcedAccountFull)
				{
					announcedAccountFull = true;
					chat(outcome.playerMessage != null ? RouteDispatcher.chatSafe(outcome.playerMessage)
						: "Your osrsindex.com account already tracks the most characters it can, so this one was not"
						+ " synced. Delete tracker data at osrsindex.com/account to make room.");
				}
				lastSync = outcome.playerMessage != null ? OsrsIndexPanel.escape(outcome.playerMessage) : ACCOUNT_FULL_LINE;
				refreshPanel();
				break;
			case RETRY:
			default:
				nextSendAtMillis = now + outcome.waitSeconds * 1_000L;
				if (outcome.playerMessage != null)
				{
					lastSync = siteWait(outcome.playerMessage);
					refreshPanel();
				}
				break;
		}
	}

	/**
	 * The panel's line for a wait the site explained, "Not synced at 14:02: <its message>", escaped for the panel.
	 * Chat gets the message too, once for each different one, so a maintenance notice is seen but not repeated.
	 */
	private String siteWait(String message)
	{
		if (!message.equals(lastWaitMessage))
		{
			lastWaitMessage = message;
			chat(RouteDispatcher.chatSafe(message));
		}
		return OsrsIndexPanel.escape(waitLine(LocalTime.now(), message));
	}

	/** "Not synced at 14:02: " and the site's message about why. */
	static String waitLine(LocalTime at, String message)
	{
		return "Not synced at " + at.format(DateTimeFormatter.ofPattern("HH:mm")) + ": " + message;
	}

	// --- site config -------------------------------------------------------

	/** Use what the site asks of this client now. On the client thread. */
	private void applySiteConfig(SiteConfig next)
	{
		SiteConfigFetcher fetcher = siteConfigFetcher;
		if (fetcher == null || next != fetcher.current())
		{
			// Overtaken by a later answer, or from a fetcher before the plugin restarted: that one applies instead.
			return;
		}
		SiteConfig previous = siteConfig;
		siteConfig = next;
		if (next.timing.budgetBytes != previous.timing.budgetBytes)
		{
			budgetBytes = next.timing.budgetBytes;
		}
		// A part the site un-paused sends its current state, as one the player switches back on does.
		for (Part part : previous.paused)
		{
			if (!next.paused.contains(part) && (part != Part.BANK || capturedBank != null))
			{
				markDirty(part);
			}
		}
		if (!next.vars.sameAs(previous.vars))
		{
			lastSeen.remove(Part.VARS);
			markDirty(Part.VARS);
		}
		DestinationSocket socket = destinationSocket;
		if (socket != null)
		{
			socket.retryWaits(next.timing.relayRetryMillis, next.timing.relayRetryMaxMillis);
			if (next.destinations != previous.destinations)
			{
				updateDestinations(false);
				showDestinationStatus(socket.status());
			}
		}
		if (panel != null)
		{
			panel.showNotice(next.notice);
		}
		if (next.notice != null && next.notice.chat && chattedNotices.add(next.notice.id))
		{
			chat(RouteDispatcher.chatSafe(next.notice.text));
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
		partTimes.clear();
		profileLoaded = false;
		profileUnsaved = false;
		ticksSinceLogin = -1;
		schema = Part.LATEST_SCHEMA;
		budgetBytes = siteConfig.timing.budgetBytes;
		sendPartTimes = true;
		lastWaitMessage = null;
		reader.forgetUnreadableVars();
		warnedTooLarge.clear();
		announcedFirstSync = false;
		announcedAccountFull = false;
		linkOfferedThisSession = false;
	}

	/** The panel's line while the account has no room for this character. */
	static final String ACCOUNT_FULL_LINE = "Not synced: your account already tracks the most characters it can.";

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

	/**
	 * The panel's line after the site refused a lone part as too large:
	 * "Not synced at 14:02: vars was too large for the site. It is sent again when it changes."
	 */
	static String tooLargeLine(LocalTime at, Part part)
	{
		return "Not synced at " + at.format(DateTimeFormatter.ofPattern("HH:mm")) + ": " + part.key.replace('_', ' ')
			+ " was too large for the site. It is sent again when it changes.";
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
			socket.update(config.receiveDestinations() && siteConfig.destinations, token(), loggedIn, fresh);
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
				loggedIn, !siteConfig.destinations));
		}
	}

	/** The panel's status line for the destinations socket. {@code sitePaused}: the site config turned it off. */
	static String destinationStatusText(DestinationSocket.Status status, boolean optedIn, boolean linked,
		boolean loggedIn, boolean sitePaused)
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
				if (sitePaused)
				{
					return "Not listening: osrsindex.com has paused destinations for now.";
				}
				return loggedIn ? "Not listening." : "Not listening: log in to the game first.";
		}
	}

	/** The compass rose from osrsindex.com's favicon, drawn by hand at 16 px. */
	private static BufferedImage icon()
	{
		return ImageUtil.loadImageResource(OsrsIndexPlugin.class, "sidebar.png");
	}
}
