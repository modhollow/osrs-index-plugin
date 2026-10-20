package com.osrsindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/**
 * One osrsindex.com guide, as {@code GET /api/v1/guides/{id}} serves it (docs/guides.md): its sections, and in each
 * the steps in the site's order. {@link GuideParser} builds it, and everything here has been checked and made safe
 * to show: text is plain, coordinates are in range, and an optional field the site left out is null or empty.
 */
final class Guide
{
	/** The {@code version} of the guide routes this plugin reads. Any other is "update the plugin". */
	static final int VERSION = 1;

	final String id;
	final String title;
	final String summary;
	/** Who the guide adapts, shown under its title, or null. */
	final String attribution;
	/** Changes whenever anything in the guide changes, so a cached copy is kept until it does. */
	final String revision;
	/** The account types the guide is written for, or null for any. */
	final List<String> modes;
	final List<Section> sections;
	/** Every step, in order. */
	final List<Step> steps;

	Guide(String id, String title, String summary, String attribution, String revision, List<String> modes,
		List<Section> sections)
	{
		this.id = id;
		this.title = title;
		this.summary = summary;
		this.attribution = attribution;
		this.revision = revision;
		this.modes = modes == null ? null : Collections.unmodifiableList(modes);
		this.sections = Collections.unmodifiableList(sections);
		List<Step> all = new ArrayList<>();
		for (Section section : sections)
		{
			all.addAll(section.steps);
		}
		this.steps = Collections.unmodifiableList(all);
	}

	/** {@link #withItemFacts} with no item known to be untradeable. */
	Guide withItemIds(ToIntFunction<String> lookup)
	{
		return withItemFacts(lookup, id -> false);
	}

	/**
	 * This guide with what the game knows about its items: the ids the site left out, filled in by name from
	 * {@code lookup} (the game's id, or -1 when it knows none), and which items cannot be traded, from
	 * {@code untradeable}. An id the site gave is kept. Returns this guide when nothing changes.
	 */
	Guide withItemFacts(ToIntFunction<String> lookup, IntPredicate untradeable)
	{
		boolean changed = false;
		List<Section> filled = new ArrayList<>();
		for (Section section : sections)
		{
			List<Step> steps = new ArrayList<>();
			for (Step step : section.steps)
			{
				List<Item> bring = items(step.bring, lookup, untradeable);
				List<Collect> collect = new ArrayList<>();
				for (Collect entry : step.collect)
				{
					Item item = item(entry.item, lookup, untradeable);
					collect.add(item == entry.item ? entry : new Collect(item, entry.forQuests, entry.from));
				}
				Rewards rewards = step.rewards;
				if (rewards != null)
				{
					List<Item> items = items(rewards.items, lookup, untradeable);
					rewards = items == rewards.items ? rewards
						: new Rewards(rewards.xp, rewards.questPoints, rewards.coins, items, rewards.unlocks);
				}
				boolean same = bring == step.bring && rewards == step.rewards;
				for (int i = 0; same && i < collect.size(); i++)
				{
					same = collect.get(i) == step.collect.get(i);
				}
				changed |= !same;
				steps.add(same ? step : new Step(step.id, step.title, step.minutes, step.url, step.text, step.how, step.tip,
					step.ironman, step.tags, step.quests, step.places, step.travel, bring, collect, step.requires,
					step.completes, rewards));
			}
			filled.add(new Section(section.id, section.title, section.summary, steps));
		}
		return changed ? new Guide(id, title, summary, attribution, revision, modes, filled) : this;
	}

	/** The same list when no item in it changes. */
	private static List<Item> items(List<Item> items, ToIntFunction<String> lookup, IntPredicate untradeable)
	{
		List<Item> out = new ArrayList<>();
		boolean changed = false;
		for (Item item : items)
		{
			Item filled = item(item, lookup, untradeable);
			changed |= filled != item;
			out.add(filled);
		}
		return changed ? out : items;
	}

	private static Item item(Item item, ToIntFunction<String> lookup, IntPredicate untradeable)
	{
		int id = item.id >= 0 ? item.id : lookup.applyAsInt(item.name);
		boolean bound = id >= 0 && untradeable.test(id);
		return id == item.id && bound == item.untradeable ? item : new Item(item.name, id, item.qty, bound);
	}

	Step step(String stepId)
	{
		for (Step step : steps)
		{
			if (step.id.equals(stepId))
			{
				return step;
			}
		}
		return null;
	}

	static final class Section
	{
		final String id;
		final String title;
		final String summary;
		final List<Step> steps;

		Section(String id, String title, String summary, List<Step> steps)
		{
			this.id = id;
			this.title = title;
			this.summary = summary;
			this.steps = Collections.unmodifiableList(steps);
		}
	}

	static final class Step
	{
		/** The site's stable tick key: progress is kept by it. */
		final String id;
		final String title;
		/** About how long it takes, or -1. */
		final int minutes;
		/** The step's page on osrsindex.com, or null. */
		final String url;
		final String text;
		final List<String> how;
		final String tip;
		/** What an Ironman does instead, when it differs, or null. */
		final String ironman;
		/** {@code risk}, {@code solo}, {@code optional}, and any the plugin does not know yet. */
		final Set<String> tags;
		/** The game's names of the quests this step is, for Quest Helper. */
		final List<String> quests;
		final List<Place> places;
		final List<Place> travel;
		final List<Item> bring;
		final List<Collect> collect;
		final List<Requirement> requires;
		/** What ticks the step by itself once the character meets all of it. */
		final List<Requirement> completes;
		/** Null when the step names none. */
		final Rewards rewards;

		Step(String id, String title, int minutes, String url, String text, List<String> how, String tip,
			String ironman, Set<String> tags, List<String> quests, List<Place> places, List<Place> travel,
			List<Item> bring, List<Collect> collect, List<Requirement> requires, List<Requirement> completes,
			Rewards rewards)
		{
			this.id = id;
			this.title = title;
			this.minutes = minutes;
			this.url = url;
			this.text = text;
			this.how = Collections.unmodifiableList(how);
			this.tip = tip;
			this.ironman = ironman;
			this.tags = Collections.unmodifiableSet(tags);
			this.quests = Collections.unmodifiableList(quests);
			this.places = Collections.unmodifiableList(places);
			this.travel = Collections.unmodifiableList(travel);
			this.bring = Collections.unmodifiableList(bring);
			this.collect = Collections.unmodifiableList(collect);
			this.requires = Collections.unmodifiableList(requires);
			this.completes = Collections.unmodifiableList(completes);
			this.rewards = rewards;
		}

		/** The first place with a tile, which the map point, the minimap arrow and Walk there use, or null. */
		Place point()
		{
			for (Place place : places)
			{
				if (place.hasPoint())
				{
					return place;
				}
			}
			return null;
		}
	}

	/** A place, or a way to travel ({@code by}). Some have only a label: the site knows no tile for them yet. */
	static final class Place
	{
		final String label;
		/** How to travel there, such as {@code teleport}, for a travel entry; null for a place. */
		final String by;
		final int x;
		final int y;
		final int plane;

		Place(String label, String by, int x, int y, int plane)
		{
			this.label = label;
			this.by = by;
			this.x = x;
			this.y = y;
			this.plane = plane;
		}

		boolean hasPoint()
		{
			return x >= 0 && y >= 0 && plane >= 0;
		}
	}

	static final class Item
	{
		final String name;
		/** The game's item id, or -1 when the site gives none: the item is then shown by name only. */
		final int id;
		final int qty;
		/** The game says it cannot be traded, so owning it shows the player did what gives it. */
		final boolean untradeable;

		Item(String name, int id, int qty)
		{
			this(name, id, qty, false);
		}

		Item(String name, int id, int qty, boolean untradeable)
		{
			this.name = name;
			this.id = id;
			this.qty = qty;
			this.untradeable = untradeable;
		}
	}

	/** An item to gather on the way, for the quests in {@code forQuests}, and where it comes from. */
	static final class Collect
	{
		final Item item;
		final List<String> forQuests;
		final String from;

		Collect(Item item, List<String> forQuests, String from)
		{
			this.item = item;
			this.forQuests = Collections.unmodifiableList(forQuests);
			this.from = from;
		}
	}

	/**
	 * One requirement, or one condition of {@code completes}. Beside levels, quests, quest points and account type, the
	 * site can name the game's own state, so a new kind of step needs no plugin update: a game variable (a varbit or a
	 * varp, which hold quest stages, diaries, unlocks and minigame progress), items held, or something that happens in
	 * the game (standing in an area, talking to someone, killing something).
	 */
	static final class Requirement
	{
		enum Kind
		{
			SKILL,
			QUEST,
			QUEST_POINTS,
			MODE,
			/** A varbit compared with {@link #value} by {@link #op}. */
			VARBIT,
			/** A varp compared with {@link #value} by {@link #op}. */
			VARP,
			/** Every item in {@link #items}, carried ({@link #op} {@code carried}) or owned, banked included. */
			ITEMS,
			/** Standing within {@link #value} tiles of {@link #place}. */
			AREA,
			/** A dialogue with the NPC named {@link #npc}. */
			TALK,
			/** {@link #value} kills of the NPC named {@link #npc}. */
			KILL,
			/** A kind this version does not know: never met, never missing. */
			UNKNOWN;

			/** Shown by something happening in the game, not by a state the plugin can read at any time. */
			boolean event()
			{
				return this == AREA || this == TALK || this == KILL;
			}

			/** A state the plugin can read at any time. */
			boolean state()
			{
				return !event() && this != UNKNOWN;
			}
		}

		final Kind kind;
		/** The site's lower-case skill name, for {@link Kind#SKILL}. */
		final String skill;
		final int level;
		/** A temporary boost counts, within 5 levels, as on the site. */
		final boolean boostable;
		final String quest;
		final int points;
		final List<String> modes;
		/** The varbit or varp id. */
		final int varId;
		/**
		 * How a variable compares with {@link #value}: {@code ==}, {@code !=}, {@code >=}, {@code >}, {@code <=} or
		 * {@code <}; for {@link Kind#ITEMS}, {@code carried} or {@code owned}.
		 */
		final String op;
		/** The variable's value to compare with, the kills to make, or the area's reach in tiles. */
		final int value;
		final List<Item> items;
		/** The area's centre, with a tile. */
		final Place place;
		final String npc;
		/** What the site calls the condition, shown to the player, or null. */
		final String label;

		Requirement(Kind kind, String skill, int level, boolean boostable, String quest, int points, List<String> modes)
		{
			this(kind, skill, level, boostable, quest, points, modes, -1, null, 0, Collections.<Item>emptyList(), null, null,
				null);
		}

		Requirement(Kind kind, String skill, int level, boolean boostable, String quest, int points, List<String> modes,
			int varId, String op, int value, List<Item> items, Place place, String npc, String label)
		{
			this.kind = kind;
			this.skill = skill;
			this.level = level;
			this.boostable = boostable;
			this.quest = quest;
			this.points = points;
			this.modes = Collections.unmodifiableList(modes);
			this.varId = varId;
			this.op = op;
			this.value = value;
			this.items = Collections.unmodifiableList(items);
			this.place = place;
			this.npc = npc;
			this.label = label;
		}

		/** One of the kinds the site describes with game state or events, built by the parser. */
		static Requirement of(Kind kind, int varId, String op, int value, List<Item> items, Place place, String npc,
			String label)
		{
			return new Requirement(kind, null, 0, false, null, 0, Collections.<String>emptyList(), varId, op, value, items,
				place, npc, label);
		}
	}

	static final class Rewards
	{
		final List<Xp> xp;
		final int questPoints;
		final long coins;
		final List<Item> items;
		final List<String> unlocks;

		Rewards(List<Xp> xp, int questPoints, long coins, List<Item> items, List<String> unlocks)
		{
			this.xp = Collections.unmodifiableList(xp);
			this.questPoints = questPoints;
			this.coins = coins;
			this.items = Collections.unmodifiableList(items);
			this.unlocks = Collections.unmodifiableList(unlocks);
		}

		boolean isEmpty()
		{
			return xp.isEmpty() && questPoints == 0 && coins == 0 && items.isEmpty() && unlocks.isEmpty();
		}
	}

	static final class Xp
	{
		final String skill;
		final long amount;

		Xp(String skill, long amount)
		{
			this.skill = skill;
			this.amount = amount;
		}
	}

	/** {@code GET /api/v1/guides}: every guide the site offers in game. */
	static final class Listing
	{
		final List<Summary> guides;

		Listing(List<Summary> guides)
		{
			this.guides = Collections.unmodifiableList(guides);
		}

		Summary find(String guideId)
		{
			for (Summary summary : guides)
			{
				if (summary.id.equals(guideId))
				{
					return summary;
				}
			}
			return null;
		}
	}

	/** One guide in the list. */
	static final class Summary
	{
		final String id;
		final String title;
		final String summary;
		/** {@code free} or {@code members}, or null. */
		final String membership;
		final List<String> modes;
		/** How many steps it has, or -1. */
		final int steps;
		final int minutes;
		final String revision;

		Summary(String id, String title, String summary, String membership, List<String> modes, int steps, int minutes,
			String revision)
		{
			this.id = id;
			this.title = title;
			this.summary = summary;
			this.membership = membership;
			this.modes = modes == null ? null : Collections.unmodifiableList(modes);
			this.steps = steps;
			this.minutes = minutes;
			this.revision = revision;
		}
	}
}
