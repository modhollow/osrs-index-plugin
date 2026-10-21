package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What osrsindex.com asks of this plugin, so the site can change it without a plugin release: timings and
 * limits, parts to pause, which vars to send, and a notice for the player (docs/site-config.md).
 *
 * <p>It is data only. Every field is optional, clamped to a safe range or ignored when unusable, and the site
 * can only narrow what the player chose: a part the player turned off stays off. {@link #DEFAULTS} is the
 * plugin's built-in behaviour, which is what a site without the config route gets.
 */
final class SiteConfig
{
	static final int DEFAULT_REFRESH_SECONDS = 3_600;
	static final int MIN_REFRESH_SECONDS = 300;
	static final int MAX_REFRESH_SECONDS = 86_400;

	/** The longest player-facing text the site may send, in code points. */
	static final int MAX_TEXT = 300;
	static final int MAX_NOTICE_ID = 64;

	static final int MAX_SITE_VARBITS = 4_000;
	static final int MAX_SITE_VARPS = 1_000;
	static final int MAX_VAR_ID = 65_535;
	/**
	 * The vars part's estimated size limit, counting each entry as its name plus 8 bytes. The built-in list is about
	 * 74,500 by that count. The site's entries stop where they would pass this, so the part stays under the site's
	 * 131,072-byte body cap even when values run long.
	 */
	static final int MAX_VARS_BYTES = 100_000;
	/** RuneLite's gameval names: capitals, digits and underscores, never a digit first, which the sync refuses. */
	private static final Pattern VAR_NAME = Pattern.compile("[A-Z_][A-Z0-9_]{0,79}");
	/**
	 * Names the vars part keeps for others, so a site var never takes one: the site's own {@code __SITE_} names, and
	 * the schema 4 Slayer text this plugin writes itself ({@code SLAYER_TASK_NAME}, {@code SLAYER_TASK_AREA}). The
	 * sync refuses the whole vars part over any of them.
	 */
	static final Pattern RESERVED_VAR = Pattern.compile("^__SITE_|^SLAYER_TASK_NAME$|^SLAYER_TASK_AREA$");
	/**
	 * The names scripts/generate_gamevars.py leaves out: never interface settings, the POH, leagues or debug. Keep it
	 * the same as the generator's {@code EXCLUDE}. The IDs of every name it matches are refused as well
	 * ({@link GameVars#EXCLUDED_VARBIT_IDS}), so the site cannot send one under another name.
	 */
	static final Pattern EXCLUDED_VAR = Pattern.compile("^LEAGUE|_LEAGUE|^POH_|SETTINGS|_TEMP$|_TMP$|DEBUG|TOGGLE|HIDE"
		+ "|FILTER|SELECTED|SCROLL|_MENU|_UI_|_INTERFACE|_TAB$|_TAB_"
		+ "|^OPTION_|QUESTLIST_|REQUESTEDQUANTITY|WORLDSWITCHER|COLOUR|_PROMPT|BANNED|BANKPIN|^ACCOUNT_|LOGIN"
		+ "|DISPLAYNAME|BOND|PURGEIGNORED|^CHAT_", Pattern.CASE_INSENSITIVE);

	static final SiteConfig DEFAULTS = new SiteConfig(0, DEFAULT_REFRESH_SECONDS, Part.LATEST_SCHEMA, Timing.DEFAULTS,
		EnumSet.noneOf(Part.class), true, Vars.BUILT_IN, null);

	/** The config's revision; a stored sync naming a higher {@code config_version} asks for it again. */
	final int version;
	final int refreshSeconds;
	/** The highest sync schema to send. */
	final int maxSchema;
	final Timing timing;
	/** Parts the site paused. Never more than a pause: a part the player turned off stays off. */
	final Set<Part> paused;
	/** False when the site paused the destinations socket. */
	final boolean destinations;
	final Vars vars;
	/** One line for the panel, or null. */
	final Notice notice;

	private SiteConfig(int version, int refreshSeconds, int maxSchema, Timing timing, Set<Part> paused,
		boolean destinations, Vars vars, Notice notice)
	{
		this.version = version;
		this.refreshSeconds = refreshSeconds;
		this.maxSchema = maxSchema;
		this.timing = timing;
		this.paused = Collections.unmodifiableSet(paused);
		this.destinations = destinations;
		this.vars = vars;
		this.notice = notice;
	}

	/** Whether the site lets {@code part} go out. The player's own setting is checked as well, elsewhere. */
	boolean allows(Part part)
	{
		return !paused.contains(part);
	}

	/** The largest body budget the site may set: under its own 131,072-byte cap. */
	static final int MAX_BUDGET_BYTES = 125_000;

	/** The waits and limits the site may tune, each clamped to a range the plugin trusts. */
	static final class Timing
	{
		static final Timing DEFAULTS = new Timing(SyncOutcome.FLOOR_SECONDS, SyncOutcome.DEFAULT_RETRY_SECONDS,
			SyncOutcome.ACCOUNT_FULL_RETRY_SECONDS, PayloadPacker.DEFAULT_BUDGET_BYTES, 5, 25,
			DestinationSocket.FIRST_RETRY_MILLIS, DestinationSocket.MAX_RETRY_MILLIS);

		final int syncFloorSeconds;
		final int retrySeconds;
		final int accountFullRetrySeconds;
		final int budgetBytes;
		final int pollTicks;
		final int pollVarsTicks;
		final long relayRetryMillis;
		final long relayRetryMaxMillis;

		Timing(int syncFloorSeconds, int retrySeconds, int accountFullRetrySeconds, int budgetBytes, int pollTicks,
			int pollVarsTicks, long relayRetryMillis, long relayRetryMaxMillis)
		{
			this.syncFloorSeconds = syncFloorSeconds;
			this.retrySeconds = retrySeconds;
			this.accountFullRetrySeconds = accountFullRetrySeconds;
			this.budgetBytes = budgetBytes;
			this.pollTicks = pollTicks;
			this.pollVarsTicks = pollVarsTicks;
			this.relayRetryMillis = relayRetryMillis;
			this.relayRetryMaxMillis = relayRetryMaxMillis;
		}

		static Timing of(JsonObject json)
		{
			if (json == null)
			{
				return DEFAULTS;
			}
			// Never quicker than the built-in waits where quicker would cost the site: the floor is its own per-token
			// limit, and every linked client reconnects to the relay at once after an outage.
			long relayFirst = number(json, "relay_retry_seconds", DEFAULTS.relayRetryMillis / 1_000, 15, 600);
			long relayMax = number(json, "relay_retry_max_seconds", Math.max(relayFirst, DEFAULTS.relayRetryMaxMillis / 1_000),
				Math.max(relayFirst, 60), 3_600);
			return new Timing(
				(int) number(json, "sync_floor_seconds", DEFAULTS.syncFloorSeconds, SyncOutcome.FLOOR_SECONDS, 3_600),
				(int) number(json, "retry_seconds", DEFAULTS.retrySeconds, 30, 3_600),
				(int) number(json, "account_full_retry_seconds", DEFAULTS.accountFullRetrySeconds, 60, 86_400),
				(int) number(json, "budget_bytes", DEFAULTS.budgetBytes, PayloadPacker.MIN_BUDGET_BYTES, MAX_BUDGET_BYTES),
				(int) number(json, "poll_ticks", DEFAULTS.pollTicks, 1, 100),
				(int) number(json, "poll_vars_ticks", DEFAULTS.pollVarsTicks, 5, 1_000),
				relayFirst * 1_000L,
				relayMax * 1_000L);
		}
	}

	/** The varbits and varps the vars part reads, by gameval name, as parallel arrays. */
	static final class Vars
	{
		static final Vars BUILT_IN = new Vars(GameVars.VARBIT_NAMES, GameVars.VARBIT_IDS, GameVars.VARP_NAMES,
			GameVars.VARP_IDS);

		final String[] varbitNames;
		final int[] varbitIds;
		final String[] varpNames;
		final int[] varpIds;

		private Vars(String[] varbitNames, int[] varbitIds, String[] varpNames, int[] varpIds)
		{
			this.varbitNames = varbitNames;
			this.varbitIds = varbitIds;
			this.varpNames = varpNames;
			this.varpIds = varpIds;
		}

		boolean sameAs(Vars other)
		{
			return other != null && Arrays.equals(varbitNames, other.varbitNames) && Arrays.equals(varbitIds, other.varbitIds)
				&& Arrays.equals(varpNames, other.varpNames) && Arrays.equals(varpIds, other.varpIds);
		}

		static Vars of(JsonObject json)
		{
			if (json == null)
			{
				return BUILT_IN;
			}
			boolean replace = "replace".equals(string(json, "mode"));
			Map<String, Integer> varbits = replace ? new LinkedHashMap<>() : map(GameVars.VARBIT_NAMES, GameVars.VARBIT_IDS);
			Map<String, Integer> varps = replace ? new LinkedHashMap<>() : map(GameVars.VARP_NAMES, GameVars.VARP_IDS);
			int[] size = {estimate(varbits) + estimate(varps)};
			addFrom(object(json, "varbits"), MAX_SITE_VARBITS, GameVars.EXCLUDED_VARBIT_IDS, GameVars.VARBIT_NAMES,
				GameVars.VARBIT_IDS, GameVars.MAX_KNOWN_VARBIT_ID, varbits, size);
			addFrom(object(json, "varps"), MAX_SITE_VARPS, GameVars.EXCLUDED_VARP_IDS, GameVars.VARP_NAMES,
				GameVars.VARP_IDS, GameVars.MAX_KNOWN_VARP_ID, varps, size);
			List<String> varbitNames = new ArrayList<>(varbits.keySet());
			List<String> varpNames = new ArrayList<>(varps.keySet());
			Vars vars = new Vars(varbitNames.toArray(new String[0]), ids(varbits), varpNames.toArray(new String[0]), ids(varps));
			return vars.sameAs(BUILT_IN) ? BUILT_IN : vars;
		}

		private static Map<String, Integer> map(String[] names, int[] ids)
		{
			Map<String, Integer> out = new LinkedHashMap<>();
			for (int i = 0; i < names.length; i++)
			{
				out.put(names[i], ids[i]);
			}
			return out;
		}

		/** The vars part's size by {@link #MAX_VARS_BYTES}'s count: each name plus 8 bytes. */
		private static int estimate(Map<String, Integer> entries)
		{
			int size = 0;
			for (String name : entries.keySet())
			{
				size += name.length() + 8;
			}
			return size;
		}

		/**
		 * The site's usable entries, at most {@code limit}, while the part stays within {@link #MAX_VARS_BYTES}: a
		 * name it may ask for, neither excluded nor reserved, and a whole ID in range that no excluded name has and
		 * that {@link #allowed} lets through.
		 */
		private static void addFrom(JsonObject site, int limit, int[] excludedIds, String[] knownNames, int[] knownIds,
			int maxKnownId, Map<String, Integer> into, int[] size)
		{
			if (site == null)
			{
				return;
			}
			int taken = 0;
			for (Map.Entry<String, JsonElement> entry : site.entrySet())
			{
				if (taken == limit)
				{
					break;
				}
				String name = entry.getKey();
				Integer id = wholeNumber(entry.getValue());
				if (!VAR_NAME.matcher(name).matches() || EXCLUDED_VAR.matcher(name).find() || RESERVED_VAR.matcher(name).find()
					|| id == null || id < 0 || id > MAX_VAR_ID || Arrays.binarySearch(excludedIds, id) >= 0
					|| !allowed(name, id, knownNames, knownIds, maxKnownId))
				{
					continue;
				}
				int grows = into.containsKey(name) ? 0 : name.length() + 8;
				if (size[0] + grows > MAX_VARS_BYTES)
				{
					break;
				}
				into.put(name, id);
				size[0] += grows;
				taken++;
			}
		}

		/**
		 * A var this plugin's game data names goes only under that name, and only when the built-in list has it: the
		 * site cannot read an account or settings value by giving its ID a progress name. A var newer than that data
		 * goes only under a progress name, so a new quest or minigame can still be added from the site.
		 */
		static boolean allowed(String name, int id, String[] knownNames, int[] knownIds, int maxKnownId)
		{
			if (id > maxKnownId)
			{
				return GameVars.PROGRESS_NAME.matcher(name).find();
			}
			// The IDs are sorted; one ID may carry more than one name, so look either side of the match.
			int at = Arrays.binarySearch(knownIds, id);
			if (at < 0)
			{
				return false;
			}
			int first = at;
			while (first > 0 && knownIds[first - 1] == id)
			{
				first--;
			}
			for (int i = first; i < knownIds.length && knownIds[i] == id; i++)
			{
				if (knownNames[i].equals(name))
				{
					return true;
				}
			}
			return false;
		}

		private static int[] ids(Map<String, Integer> entries)
		{
			int[] out = new int[entries.size()];
			int i = 0;
			for (int id : entries.values())
			{
				out[i++] = id;
			}
			return out;
		}
	}

	/** One line for the player. {@link #url} is on the plugin's own origin, or null. */
	static final class Notice
	{
		final String id;
		final String text;
		final String url;
		/** Also put it in game chat, once per id. */
		final boolean chat;

		Notice(String id, String text, String url, boolean chat)
		{
			this.id = id;
			this.text = text;
			this.url = url;
			this.chat = chat;
		}

		static Notice of(JsonObject json)
		{
			String text = playerText(string(json, "text"));
			if (text == null)
			{
				return null;
			}
			String id = playerText(string(json, "id"));
			id = id == null || id.length() > MAX_NOTICE_ID ? text : id;
			JsonElement chat = json.get("chat");
			return new Notice(id, text, siteUrl(string(json, "url")), chat != null && isBoolean(chat) && chat.getAsBoolean());
		}
	}

	/** The config in a 200's body, or the built-in values when it is not a JSON object. */
	static SiteConfig parse(String body)
	{
		SiteConfig config = parseOrNull(body);
		return config == null ? DEFAULTS : config;
	}

	/**
	 * The config in a 200's body, or null when it is not a JSON object: an HTML page from a proxy or a fallback route
	 * is no answer, and the config already in use stays.
	 */
	static SiteConfig parseOrNull(String body)
	{
		JsonObject json = null;
		try
		{
			JsonElement parsed = body == null ? null : new JsonParser().parse(body);
			json = parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (RuntimeException ignored)
		{
			// Not JSON: the built-in values.
		}
		if (json == null)
		{
			return null;
		}
		JsonObject notice = object(json, "notice");
		JsonElement destinations = json.get("destinations");
		return new SiteConfig(
			(int) number(json, "version", 0, 0, Integer.MAX_VALUE),
			(int) number(json, "refresh_seconds", DEFAULT_REFRESH_SECONDS, MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS),
			(int) number(json, "max_schema", Part.LATEST_SCHEMA, 1, Part.LATEST_SCHEMA),
			Timing.of(object(json, "timing")),
			paused(object(json, "parts")),
			destinations == null || !isBoolean(destinations) || destinations.getAsBoolean(),
			Vars.of(object(json, "vars")),
			notice == null ? null : Notice.of(notice));
	}

	private static Set<Part> paused(JsonObject parts)
	{
		Set<Part> out = EnumSet.noneOf(Part.class);
		if (parts == null)
		{
			return out;
		}
		for (Part part : Part.values())
		{
			JsonElement value = parts.get(part.key);
			if (value != null && isBoolean(value) && !value.getAsBoolean())
			{
				out.add(part);
			}
		}
		return out;
	}

	/**
	 * Text the site wrote for the player, made safe to show: control characters dropped, runs of whitespace made
	 * one space, at most {@link #MAX_TEXT} code points. Null when nothing is left. Markup stays text: chat and the
	 * panel each escape it where they show it.
	 */
	static String playerText(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		StringBuilder out = new StringBuilder();
		int[] count = {0};
		raw.codePoints()
			.map(codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ? ' ' : codePoint)
			.filter(codePoint -> !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT)
			.forEach(codePoint ->
			{
				boolean space = codePoint == ' ';
				if (count[0] < MAX_TEXT && !(space && (out.length() == 0 || out.charAt(out.length() - 1) == ' ')))
				{
					out.appendCodePoint(codePoint);
					count[0]++;
				}
			});
		String text = out.toString().trim();
		return text.isEmpty() ? null : text;
	}

	/** A player-facing string field of a response body, made safe ({@link #playerText}), or null. */
	static String playerMessage(JsonObject json)
	{
		return json == null ? null : playerText(string(json, "player_message"));
	}

	/** {@code url} when it is a page on the plugin's own origin, else null. */
	static String siteUrl(String url)
	{
		return url != null && url.startsWith(Endpoints.origin() + "/") && url.length() <= 2_000
			&& url.chars().noneMatch(c -> c <= ' ' || c == 0x7F) ? url : null;
	}

	// --- reading the body ----------------------------------------------------

	private static JsonObject object(JsonObject json, String key)
	{
		JsonElement value = json.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	static String string(JsonObject json, String key)
	{
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static boolean isBoolean(JsonElement value)
	{
		return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
	}

	/** A number field clamped to {@code min}..{@code max}, or {@code fallback} when absent or not a number. */
	private static long number(JsonObject json, String key, long fallback, long min, long max)
	{
		JsonElement value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			return fallback;
		}
		double number = value.getAsDouble();
		if (Double.isNaN(number))
		{
			return fallback;
		}
		return (long) Math.max(min, Math.min(max, number));
	}

	/** A JSON number with no fraction that fits an int, or null. */
	private static Integer wholeNumber(JsonElement value)
	{
		if (value == null || !value.isJsonPrimitive())
		{
			return null;
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isNumber())
		{
			return null;
		}
		double number = primitive.getAsDouble();
		return number == Math.rint(number) && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (int) number : null;
	}
}
