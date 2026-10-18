package com.osrsindex;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.Text;

/**
 * Hands a site destination to a pathing plugin, following what the contract says the plugin must do with
 * one. Every destination whose {@code id} can be acked is acked once, with what became of it, and the first
 * of these that applies decides the ack's {@code result}:
 * <ol>
 *   <li>a place outside the ranges is {@code invalid}; one with no usable {@code id} cannot be acked at
 *   all;</li>
 *   <li>an {@code id} already routed is {@code routed} again, but not posted twice, because the site may
 *   deliver a place again after a reconnect when the first ack was lost;</li>
 *   <li>a place past {@code expires_at}, allowing 30 seconds of clock skew, is {@code expired};</li>
 *   <li>with Shortest Path off, the player is told in chat and it is {@code no_pathing_plugin};</li>
 *   <li>otherwise one {@code PluginMessage} is posted, {@code <namespace>/path} with {@code data.target} a
 *   {@code WorldPoint} and no {@code start}, so the route begins at the player. The player is told in chat,
 *   with the label escaped, since RuneLite chat markup is the client's, and it is {@code routed}.</li>
 * </ol>
 *
 * <p>The caller sends the {@code ack} and shows the chat line. This class touches no socket and no
 * client state, so the rules are testable on their own. Run it on the client thread: Shortest Path
 * reads the message on the thread that posts it.
 */
final class RouteDispatcher
{
	static final int MAX_COORDINATE = 16_383;
	static final int MAX_PLANE = 3;
	static final int MAX_LABEL_CODE_POINTS = 100;
	static final int MAX_ID_LENGTH = 64;
	static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
	/** Ids live 2 minutes on the site; this covers far more sends than fit in that window. */
	static final int REMEMBERED_IDS = 64;

	static final String PATH = "path";
	static final String TARGET = "target";

	/** What became of one destination, and the {@code result} its ack carries. */
	enum Kind
	{
		/** Posted to a pathing plugin. Ack it {@code routed} and show the chat line. */
		ROUTED("routed"),
		/**
		 * This id was routed before. Ack it {@code routed} again, since the first ack may be lost, but post
		 * nothing.
		 */
		ALREADY_ROUTED("routed"),
		/** It breaks the contract's ranges. Ack it {@code invalid} if its id can be acked, and post nothing. */
		INVALID("invalid"),
		/** Past {@code expires_at}, plus the skew. Ack it {@code expired}, and post nothing. */
		EXPIRED("expired"),
		/**
		 * Shortest Path is not on. Show the chat line and ack it {@code no_pathing_plugin}, so the site's map
		 * says why too. The id is not remembered, so a redelivery once Shortest Path is on still routes.
		 */
		NO_PATHING_PLUGIN("no_pathing_plugin");

		/** The ack's {@code result}, in the site contract's words. */
		final String ackResult;

		Kind(String ackResult)
		{
			this.ackResult = ackResult;
		}
	}

	static final class Result
	{
		final Kind kind;
		/** The plugin it went to, for {@link Kind#ROUTED} only. */
		final PathingPlugin plugin;
		/** The line for game chat, already escaped, or null for none. */
		final String chatMessage;
		/** The id to ack with {@code kind}'s result, or null when the id cannot be acked. */
		final String ackId;

		private Result(Kind kind, PathingPlugin plugin, String chatMessage, String ackId)
		{
			this.kind = kind;
			this.plugin = plugin;
			this.chatMessage = chatMessage;
			this.ackId = ackId;
		}
	}

	private final Consumer<PluginMessage> post;
	private final Predicate<PathingPlugin> active;
	private final Clock clock;
	private final Set<String> routedIds = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>()
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest)
		{
			return size() > REMEMBERED_IDS;
		}
	});

	RouteDispatcher(Consumer<PluginMessage> post, Predicate<PathingPlugin> active, Clock clock)
	{
		this.post = post;
		this.active = active;
		this.clock = clock;
	}

	/**
	 * Whether a pathing plugin is running, by its {@code @PluginDescriptor} name. That name is the one
	 * thing another Hub plugin exposes without reflection.
	 */
	static Predicate<PathingPlugin> activeIn(PluginManager pluginManager)
	{
		return plugin ->
		{
			for (Plugin loaded : pluginManager.getPlugins())
			{
				if (plugin.displayName.equals(loaded.getName()) && pluginManager.isPluginActive(loaded))
				{
					return true;
				}
			}
			return false;
		};
	}

	synchronized Result dispatch(Destination destination, RouteWith routeWith)
	{
		if (!valid(destination))
		{
			// The site sends only places in range, so this is a frame this version cannot read. Acking it
			// stops the site sending it again, and lets the map say what to do.
			return new Result(Kind.INVALID, null, null, ackable(destination) ? destination.id : null);
		}
		if (routedIds.contains(destination.id))
		{
			return new Result(Kind.ALREADY_ROUTED, null, null, destination.id);
		}
		if (clock.instant().isAfter(destination.expiresAt.plus(CLOCK_SKEW)))
		{
			return new Result(Kind.EXPIRED, null, null, destination.id);
		}

		String label = chatSafe(destination.label);
		String place = (label.isEmpty() ? "a tile" : label) + " (" + destination.x + ", " + destination.y + ", "
			+ destination.plane + ")";
		PathingPlugin plugin = routeWith.choose(active);
		if (plugin == null)
		{
			return new Result(Kind.NO_PATHING_PLUGIN, null,
				"osrsindex.com sent " + place + ", but " + routeWith + " is not on.", destination.id);
		}

		Map<String, Object> data = new HashMap<>();
		data.put(TARGET, new WorldPoint(destination.x, destination.y, destination.plane));
		post.accept(new PluginMessage(plugin.namespace, PATH, data));
		routedIds.add(destination.id);
		return new Result(Kind.ROUTED, plugin,
			"osrsindex.com: routing to " + place + " with " + plugin.displayName + ".", destination.id);
	}

	static boolean valid(Destination destination)
	{
		if (!ackable(destination) || destination.label == null || destination.expiresAt == null)
		{
			return false;
		}
		int labelLength = destination.label.codePointCount(0, destination.label.length());
		return inRange(destination.x, MAX_COORDINATE)
			&& inRange(destination.y, MAX_COORDINATE)
			&& inRange(destination.plane, MAX_PLANE)
			&& labelLength >= 1
			&& labelLength <= MAX_LABEL_CODE_POINTS;
	}

	/**
	 * Whether the site can match an ack to this destination: an {@code id} of 1 to 64 characters. The site
	 * ignores an ack whose id is empty or longer, so a place without such an id is dropped unacked.
	 */
	static boolean ackable(Destination destination)
	{
		return destination != null && destination.id != null && !destination.id.isEmpty()
			&& destination.id.length() <= MAX_ID_LENGTH;
	}

	/**
	 * The label as chat can show it. {@code escapeJagex} turns {@code <}, {@code >} and {@code @} into
	 * their escapes, so the site's plain text cannot open a colour or image tag. Control characters are
	 * dropped: the site removes them, but a label reaches chat only through this.
	 */
	static String chatSafe(String label)
	{
		StringBuilder printable = new StringBuilder(label.length());
		label.codePoints()
			.filter(codePoint -> !Character.isISOControl(codePoint))
			.forEach(printable::appendCodePoint);
		return Text.escapeJagex(printable.toString());
	}

	private static boolean inRange(int value, int max)
	{
		return value >= 0 && value <= max;
	}
}
