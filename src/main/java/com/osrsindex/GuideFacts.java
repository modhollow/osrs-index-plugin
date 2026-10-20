package com.osrsindex;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * What the live client knows about the character, for a guide's requirements, its {@code completes} and its items:
 * a snapshot taken on the client thread, immutable, so the panel can read it on the Swing thread. Anything the
 * client could not read is unknown, never assumed: a requirement on it is shown as unknown, and it ticks nothing.
 */
final class GuideFacts
{
	/** Logged out, or nothing read yet. */
	static final GuideFacts UNKNOWN = new Builder().build();

	private final Map<String, Integer> realLevels;
	private final Map<String, Integer> boostedLevels;
	private final Map<String, QuestState> quests;
	/** Quest points, or -1. */
	final int questPoints;
	/** The account type in the site's words ({@link CharacterReader#ACCOUNT_TYPES}), or null. */
	final String accountType;
	/** Inventory and worn items, by item id. */
	private final Map<Integer, Integer> carried;
	private final Map<Integer, Integer> banked;
	/** The bank was open at least once since login, so {@link #banked} is what it held then. */
	final boolean bankSeen;
	/** The game variables a guide's conditions name, by id, as last read. */
	private final Map<Integer, Integer> varbits;
	private final Map<Integer, Integer> varps;

	private GuideFacts(Builder builder)
	{
		realLevels = Collections.unmodifiableMap(new HashMap<>(builder.realLevels));
		boostedLevels = Collections.unmodifiableMap(new HashMap<>(builder.boostedLevels));
		quests = Collections.unmodifiableMap(new HashMap<>(builder.quests));
		questPoints = builder.questPoints;
		accountType = builder.accountType;
		carried = Collections.unmodifiableMap(new HashMap<>(builder.carried));
		banked = Collections.unmodifiableMap(new HashMap<>(builder.banked));
		bankSeen = builder.bankSeen;
		varbits = Collections.unmodifiableMap(new HashMap<>(builder.varbits));
		varps = Collections.unmodifiableMap(new HashMap<>(builder.varps));
	}

	/**
	 * The client's {@link Skill} for the site's lower-case skill name, or null. The site writes {@code runecraft} as
	 * the game does; {@code runecrafting} is read too.
	 */
	static Skill skill(String name)
	{
		if (name == null)
		{
			return null;
		}
		String upper = name.trim().toUpperCase(Locale.ROOT);
		if ("RUNECRAFTING".equals(upper))
		{
			upper = "RUNECRAFT";
		}
		if ("OVERALL".equals(upper))
		{
			return null;
		}
		try
		{
			return Skill.valueOf(upper);
		}
		catch (IllegalArgumentException unknown)
		{
			return null;
		}
	}

	/** The real (unboosted) level, or null when unknown. */
	Integer realLevel(String skill)
	{
		Skill known = skill(skill);
		return known == null ? null : realLevels.get(known.name());
	}

	Integer boostedLevel(String skill)
	{
		Skill known = skill(skill);
		return known == null ? null : boostedLevels.get(known.name());
	}

	/** The quest's state by the game's name, or null when it was not read or is not a quest. */
	QuestState quest(String name)
	{
		return name == null ? null : quests.get(name);
	}

	int carried(int itemId)
	{
		Integer count = carried.get(itemId);
		return count == null ? 0 : count;
	}

	int banked(int itemId)
	{
		Integer count = banked.get(itemId);
		return count == null ? 0 : count;
	}

	/** A varbit's value, or null when it was not read. */
	Integer varbit(int id)
	{
		return varbits.get(id);
	}

	/** A varp's value, or null when it was not read. */
	Integer varp(int id)
	{
		return varps.get(id);
	}

	/** Any Ironman type, group ones included: the step's Ironman text is for them. */
	boolean ironman()
	{
		return accountType != null && !"normal".equals(accountType);
	}

	Builder toBuilder()
	{
		Builder builder = new Builder();
		builder.realLevels.putAll(realLevels);
		builder.boostedLevels.putAll(boostedLevels);
		builder.quests.putAll(quests);
		builder.questPoints = questPoints;
		builder.accountType = accountType;
		builder.carried.putAll(carried);
		builder.banked.putAll(banked);
		builder.bankSeen = bankSeen;
		builder.varbits.putAll(varbits);
		builder.varps.putAll(varps);
		return builder;
	}

	static final class Builder
	{
		private final Map<String, Integer> realLevels = new HashMap<>();
		private final Map<String, Integer> boostedLevels = new HashMap<>();
		private final Map<String, QuestState> quests = new HashMap<>();
		private int questPoints = -1;
		private String accountType;
		private final Map<Integer, Integer> carried = new HashMap<>();
		private final Map<Integer, Integer> banked = new HashMap<>();
		private boolean bankSeen;
		private final Map<Integer, Integer> varbits = new HashMap<>();
		private final Map<Integer, Integer> varps = new HashMap<>();

		Builder varbit(int id, int value)
		{
			varbits.put(id, value);
			return this;
		}

		Builder varp(int id, int value)
		{
			varps.put(id, value);
			return this;
		}

		Builder level(Skill skill, int real, int boosted)
		{
			realLevels.put(skill.name(), real);
			boostedLevels.put(skill.name(), boosted);
			return this;
		}

		Builder quest(String name, QuestState state)
		{
			quests.put(name, state);
			return this;
		}

		Builder questPoints(int points)
		{
			questPoints = points;
			return this;
		}

		Builder accountType(String type)
		{
			accountType = type;
			return this;
		}

		/** Replace the carried items: inventory and worn, by id. */
		Builder carried(Map<Integer, Integer> items)
		{
			carried.clear();
			carried.putAll(items);
			return this;
		}

		Builder banked(Map<Integer, Integer> items)
		{
			banked.clear();
			banked.putAll(items);
			bankSeen = true;
			return this;
		}

		GuideFacts build()
		{
			return new GuideFacts(this);
		}
	}
}
