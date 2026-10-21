package com.osrsindex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads the site's guide routes (docs/guides.md) into {@link Guide}. Fields it does not know are ignored, so the
 * site can add one without a plugin release. A {@code version} other than {@link Guide#VERSION} is
 * {@link Problem#UPDATE_PLUGIN}: the site bumps it when it removes, renames or changes a field, and the plugin then
 * shows nothing of the guide rather than guess.
 *
 * <p>Everything kept is made safe to show: text is plain, without control characters, and bounded; a tile outside
 * the game's ranges is dropped, leaving the label; a link off the site is dropped. A step without an id cannot keep
 * progress, so it is left out, as is a second step with an id already used.
 */
final class GuideParser
{
	enum Problem
	{
		NONE,
		/** A {@code version} this plugin does not read. */
		UPDATE_PLUGIN,
		/** Not a guide, or not a list: not JSON, or missing what the plugin needs. */
		UNREADABLE
	}

	static final class Result<T>
	{
		final T value;
		final Problem problem;

		private Result(T value, Problem problem)
		{
			this.value = value;
			this.problem = problem;
		}

		static <T> Result<T> ok(T value)
		{
			return new Result<>(value, Problem.NONE);
		}

		static <T> Result<T> failed(Problem problem)
		{
			return new Result<>(null, problem);
		}
	}

	/** A guide id: the site's slugs. It is also part of a config key and a URL path, so nothing else is taken. */
	static final Pattern GUIDE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
	static final int MAX_STEP_ID = 100;
	static final int MAX_REVISION = 128;
	static final int MAX_TITLE = 200;
	static final int MAX_SUMMARY = 1_000;
	static final int MAX_TEXT = 4_000;
	static final int MAX_LINE = 1_000;
	static final int MAX_GUIDES = 200;
	static final int MAX_SECTIONS = 200;
	static final int MAX_STEPS = 3_000;
	static final int MAX_LIST = 50;
	/**
	 * Items in one list: a step's bring, collect or rewards. A shopping step stocks up for many quests at once: the
	 * site's quest route sends 108 on one, so 50 was too few.
	 */
	static final int MAX_ITEMS = 500;
	static final int MAX_ITEM_ID = 65_535;
	/** Above any varbit or varp the game has. */
	static final int MAX_VAR_ID = 50_000;
	/** An area wider than this is not a place. */
	static final int MAX_AREA_RADIUS = 50;
	static final java.util.Set<String> VAR_OPS = new java.util.HashSet<>(java.util.Arrays.asList("==", "!=", ">=", ">",
		"<=", "<"));

	private GuideParser()
	{
	}

	static Result<Guide.Listing> listing(String body)
	{
		JsonObject json = object(body);
		if (json == null)
		{
			return Result.failed(Problem.UNREADABLE);
		}
		Problem version = version(json);
		if (version != Problem.NONE)
		{
			return Result.failed(version);
		}
		JsonArray array = array(json, "guides");
		if (array == null)
		{
			return Result.failed(Problem.UNREADABLE);
		}
		List<Guide.Summary> guides = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonObject entry : objects(array, MAX_GUIDES))
		{
			String id = guideId(entry);
			String title = text(entry, "title", MAX_TITLE);
			if (id == null || title == null || !seen.add(id))
			{
				continue;
			}
			guides.add(new Guide.Summary(id, title, text(entry, "summary", MAX_SUMMARY), word(entry, "membership"),
				modes(entry), (int) number(entry, "steps", -1, 0, MAX_STEPS), (int) number(entry, "minutes", -1, 0, 1_000_000),
				revision(entry)));
		}
		return Result.ok(new Guide.Listing(guides));
	}

	static Result<Guide> guide(String body)
	{
		JsonObject json = object(body);
		if (json == null)
		{
			return Result.failed(Problem.UNREADABLE);
		}
		Problem version = version(json);
		if (version != Problem.NONE)
		{
			return Result.failed(version);
		}
		String id = guideId(json);
		String title = text(json, "title", MAX_TITLE);
		JsonArray sectionArray = array(json, "sections");
		if (id == null || title == null || sectionArray == null)
		{
			return Result.failed(Problem.UNREADABLE);
		}
		List<Guide.Section> sections = new ArrayList<>();
		Set<String> stepIds = new HashSet<>();
		for (JsonObject section : objects(sectionArray, MAX_SECTIONS))
		{
			List<Guide.Step> steps = new ArrayList<>();
			for (JsonObject step : objects(array(section, "steps"), MAX_STEPS - stepIds.size()))
			{
				Guide.Step parsed = step(step);
				if (parsed != null && stepIds.add(parsed.id))
				{
					steps.add(parsed);
				}
			}
			if (!steps.isEmpty())
			{
				String sectionTitle = text(section, "title", MAX_TITLE);
				sections.add(new Guide.Section(orEmpty(text(section, "id", MAX_STEP_ID)), sectionTitle == null ? "" : sectionTitle,
					text(section, "summary", MAX_SUMMARY), steps));
			}
		}
		return Result.ok(new Guide(id, title, text(json, "summary", MAX_SUMMARY), text(json, "attribution", MAX_SUMMARY),
			revision(json), modes(json), sections));
	}

	private static Guide.Step step(JsonObject json)
	{
		String id = text(json, "id", MAX_STEP_ID);
		if (id == null)
		{
			return null;
		}
		String title = text(json, "title", MAX_TITLE);
		Set<String> tags = new LinkedHashSet<>();
		for (String tag : strings(json, "tags", 30))
		{
			tags.add(tag.toLowerCase(Locale.ROOT));
		}
		List<Guide.Place> travel = new ArrayList<>();
		for (JsonObject entry : objects(array(json, "travel"), MAX_LIST))
		{
			Guide.Place place = place(entry, text(entry, "by", 30));
			if (place != null)
			{
				travel.add(place);
			}
		}
		List<Guide.Place> places = new ArrayList<>();
		for (JsonObject entry : objects(array(json, "places"), MAX_LIST))
		{
			Guide.Place place = place(entry, null);
			if (place != null)
			{
				places.add(place);
			}
		}
		List<Guide.Collect> collect = new ArrayList<>();
		for (JsonObject entry : objects(array(json, "collect"), MAX_ITEMS))
		{
			JsonObject item = object(entry, "item");
			Guide.Item parsed = item == null ? null : item(item);
			if (parsed != null)
			{
				collect.add(new Guide.Collect(parsed, strings(entry, "for", MAX_TITLE), text(entry, "from", MAX_TITLE)));
			}
		}
		JsonObject rewards = object(json, "rewards");
		return new Guide.Step(id, title == null ? id : title, (int) number(json, "minutes", -1, 0, 1_000_000),
			SiteConfig.siteUrl(SiteConfig.string(json, "url")), text(json, "text", MAX_TEXT), strings(json, "how", MAX_LINE),
			text(json, "tip", MAX_TEXT), text(json, "ironman", MAX_TEXT), tags, strings(json, "quests", MAX_TITLE), places,
			travel, items(array(json, "bring")), collect, requirements(array(json, "requires")),
			requirements(array(json, "completes")), rewards == null ? null : rewards(rewards));
	}

	/** A place or a travel entry. Without a label it says nothing, so it is dropped; a bad tile leaves the label. */
	private static Guide.Place place(JsonObject json, String by)
	{
		String label = text(json, "label", RouteDispatcher.MAX_LABEL_CODE_POINTS);
		if (label == null)
		{
			return null;
		}
		Integer x = wholeNumber(json.get("x"));
		Integer y = wholeNumber(json.get("y"));
		JsonElement planeValue = json.get("plane");
		Integer plane = planeValue == null ? Integer.valueOf(0) : wholeNumber(planeValue);
		boolean tile = x != null && y != null && plane != null && inRange(x, RouteDispatcher.MAX_COORDINATE)
			&& inRange(y, RouteDispatcher.MAX_COORDINATE) && inRange(plane, RouteDispatcher.MAX_PLANE);
		return tile ? new Guide.Place(label, by, x, y, plane) : new Guide.Place(label, by, -1, -1, -1);
	}

	private static List<Guide.Item> items(JsonArray array)
	{
		List<Guide.Item> out = new ArrayList<>();
		for (JsonObject entry : objects(array, MAX_ITEMS))
		{
			Guide.Item item = item(entry);
			if (item != null)
			{
				out.add(item);
			}
		}
		return out;
	}

	/** An item: a name always, and the game's id when the site knows it. An id of 0 or less is no id. */
	private static Guide.Item item(JsonObject json)
	{
		String name = text(json, "name", MAX_TITLE);
		if (name == null)
		{
			return null;
		}
		Integer id = wholeNumber(json.get("id"));
		return new Guide.Item(name, id == null || id <= 0 || id > MAX_ITEM_ID ? -1 : id,
			(int) number(json, "qty", 1, 1, Integer.MAX_VALUE));
	}

	private static List<Guide.Requirement> requirements(JsonArray array)
	{
		List<Guide.Requirement> out = new ArrayList<>();
		for (JsonObject entry : objects(array, MAX_LIST))
		{
			out.add(requirement(entry));
		}
		return out;
	}

	/** One requirement. One this version cannot read is kept as {@link Guide.Requirement.Kind#UNKNOWN}. */
	static Guide.Requirement requirement(JsonObject json)
	{
		String kind = word(json, "kind");
		Guide.Requirement unknown = new Guide.Requirement(Guide.Requirement.Kind.UNKNOWN, null, 0, false, null, 0,
			Collections.<String>emptyList());
		if (kind == null)
		{
			return unknown;
		}
		switch (kind)
		{
			case "skill":
			{
				String skill = word(json, "skill");
				Integer level = wholeNumber(json.get("level"));
				if (skill == null || level == null || level < 1 || level > 99)
				{
					return unknown;
				}
				JsonElement boostable = json.get("boostable");
				return new Guide.Requirement(Guide.Requirement.Kind.SKILL, skill, level, boostable != null
					&& boostable.isJsonPrimitive() && boostable.getAsJsonPrimitive().isBoolean() && boostable.getAsBoolean(),
					null, 0, Collections.<String>emptyList());
			}
			case "quest":
			{
				String quest = text(json, "quest", MAX_TITLE);
				return quest == null ? unknown : new Guide.Requirement(Guide.Requirement.Kind.QUEST, null, 0, false, quest, 0,
					Collections.<String>emptyList());
			}
			case "quest_points":
			{
				Integer points = wholeNumber(json.get("points"));
				return points == null || points < 0 || points > 1_000 ? unknown
					: new Guide.Requirement(Guide.Requirement.Kind.QUEST_POINTS, null, 0, false, null, points,
					Collections.<String>emptyList());
			}
			case "mode":
			{
				List<String> modes = modes(json);
				return modes == null || modes.isEmpty() ? unknown
					: new Guide.Requirement(Guide.Requirement.Kind.MODE, null, 0, false, null, 0, modes);
			}
			case "varbit":
			case "varp":
			{
				Integer id = wholeNumber(json.get("id"));
				Integer value = wholeNumber(json.get("value"));
				String op = json.has("op") ? SiteConfig.string(json, "op") : ">=";
				if (id == null || id < 0 || id > MAX_VAR_ID || value == null || op == null || !VAR_OPS.contains(op))
				{
					return unknown;
				}
				return Guide.Requirement.of(kind.equals("varbit") ? Guide.Requirement.Kind.VARBIT : Guide.Requirement.Kind.VARP,
					id, op, value, Collections.<Guide.Item>emptyList(), null, null, text(json, "label", MAX_TITLE));
			}
			case "items":
			{
				List<Guide.Item> items = items(array(json, "items"));
				String where = json.has("where") ? word(json, "where") : "owned";
				if (items.isEmpty() || !("owned".equals(where) || "carried".equals(where)))
				{
					return unknown;
				}
				return Guide.Requirement.of(Guide.Requirement.Kind.ITEMS, -1, where, 0, items, null, null,
					text(json, "label", MAX_TITLE));
			}
			case "area":
			{
				String label = text(json, "label", RouteDispatcher.MAX_LABEL_CODE_POINTS);
				JsonObject centre = json.deepCopy();
				if (label == null)
				{
					centre.addProperty("label", "the place");
				}
				Guide.Place place = place(centre, null);
				int radius = (int) number(json, "radius", GuideSigns.REACH_TILES, 0, MAX_AREA_RADIUS);
				return place == null || !place.hasPoint() ? unknown : Guide.Requirement.of(Guide.Requirement.Kind.AREA, -1,
					null, radius, Collections.<Guide.Item>emptyList(), place, null, label);
			}
			case "talk":
			case "kill":
			{
				String npc = text(json, "npc", MAX_TITLE);
				int count = (int) number(json, "count", 1, 1, 1_000);
				return npc == null ? unknown : Guide.Requirement.of(kind.equals("talk") ? Guide.Requirement.Kind.TALK
					: Guide.Requirement.Kind.KILL, -1, null, count, Collections.<Guide.Item>emptyList(), null, npc,
					text(json, "label", MAX_TITLE));
			}
			default:
				return unknown;
		}
	}

	private static Guide.Rewards rewards(JsonObject json)
	{
		List<Guide.Xp> xp = new ArrayList<>();
		for (JsonObject entry : objects(array(json, "xp"), MAX_LIST))
		{
			String skill = word(entry, "skill");
			long amount = number(entry, "amount", 0, 0, 200_000_000);
			if (skill != null && amount > 0)
			{
				xp.add(new Guide.Xp(skill, amount));
			}
		}
		return new Guide.Rewards(xp, (int) number(json, "quest_points", 0, 0, 1_000),
			number(json, "coins", 0, 0, Integer.MAX_VALUE), items(array(json, "items")), strings(json, "unlocks", MAX_LINE));
	}

	// --- reading the body ----------------------------------------------------

	private static JsonObject object(String body)
	{
		try
		{
			JsonElement parsed = body == null ? null : new JsonParser().parse(body);
			return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (RuntimeException notJson)
		{
			return null;
		}
	}

	private static Problem version(JsonObject json)
	{
		Integer version = wholeNumber(json.get("version"));
		if (version == null)
		{
			return json.has("version") ? Problem.UPDATE_PLUGIN : Problem.UNREADABLE;
		}
		return version == Guide.VERSION ? Problem.NONE : Problem.UPDATE_PLUGIN;
	}

	private static String guideId(JsonObject json)
	{
		String id = SiteConfig.string(json, "id");
		return id != null && GUIDE_ID.matcher(id).matches() ? id : null;
	}

	private static String revision(JsonObject json)
	{
		String revision = SiteConfig.string(json, "revision");
		return revision != null && !revision.isEmpty() && revision.length() <= MAX_REVISION
			&& revision.chars().allMatch(c -> c > ' ' && c < 0x7F) ? revision : null;
	}

	/** The account types listed, lower case, or null when the field is absent or not a list (any type). */
	private static List<String> modes(JsonObject json)
	{
		if (array(json, "modes") == null)
		{
			return null;
		}
		List<String> out = new ArrayList<>();
		for (String mode : strings(json, "modes", 40))
		{
			out.add(mode.toLowerCase(Locale.ROOT));
		}
		return out;
	}

	/** A short lower-case word such as a kind or a skill: letters, digits and underscores only, or null. */
	private static String word(JsonObject json, String key)
	{
		String value = SiteConfig.string(json, key);
		if (value == null)
		{
			return null;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.matches("[a-z0-9_]{1,40}") ? lower : null;
	}

	private static JsonObject object(JsonObject json, String key)
	{
		JsonElement value = json.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject json, String key)
	{
		JsonElement value = json == null ? null : json.get(key);
		return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
	}

	/** The objects in {@code array}, at most {@code limit}; anything else in it is skipped. */
	private static List<JsonObject> objects(JsonArray array, int limit)
	{
		List<JsonObject> out = new ArrayList<>();
		if (array == null)
		{
			return out;
		}
		for (JsonElement element : array)
		{
			if (out.size() >= limit)
			{
				break;
			}
			if (element != null && element.isJsonObject())
			{
				out.add(element.getAsJsonObject());
			}
		}
		return out;
	}

	/** The plain strings in the list {@code key}, each made safe ({@link #text(String, int)}), at most {@link #MAX_LIST}. */
	private static List<String> strings(JsonObject json, String key, int max)
	{
		List<String> out = new ArrayList<>();
		JsonArray array = array(json, key);
		if (array == null)
		{
			return out;
		}
		for (JsonElement element : array)
		{
			if (out.size() >= MAX_LIST)
			{
				break;
			}
			if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
			{
				String text = text(element.getAsString(), max);
				if (text != null)
				{
					out.add(text);
				}
			}
		}
		return out;
	}

	private static String text(JsonObject json, String key, int max)
	{
		return text(SiteConfig.string(json, key), max);
	}

	/**
	 * Text made safe to show: control characters dropped, runs of whitespace made one space, at most {@code max} code
	 * points. Null when nothing is left. Markup stays text: the panel and chat each escape it where they show it.
	 */
	static String text(String raw, int max)
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
				if (count[0] < max && !(space && (out.length() == 0 || out.charAt(out.length() - 1) == ' ')))
				{
					out.appendCodePoint(codePoint);
					count[0]++;
				}
			});
		String text = out.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private static String orEmpty(String value)
	{
		return value == null ? "" : value;
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

	private static boolean inRange(int value, int max)
	{
		return value >= 0 && value <= max;
	}
}
