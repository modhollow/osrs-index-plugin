package com.osrsindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.QuestState;

/**
 * The guide rules, kept apart from the client so they can be tested: whether a requirement is met, when a step ticks
 * itself, a step's status, the "N/total" count and the current step, and whether the player has an item. Each reads
 * only a {@link GuideFacts} snapshot and the player's marks.
 *
 * <p>Where the player is in a guide ({@link #standing}) goes past the site's evaluator, so an account that is not new
 * does not start at step one: the player's place is the end of the sections they have mostly finished, and an
 * earlier step that nothing in the game can show done or not counts as passed. An earlier step the game shows is
 * not done stays behind the player, listed, rather than becoming the current step. A step done far ahead, such as a
 * quest done out of the guide's order, does not move the place.
 */
final class GuideRules
{
	/** A boostable skill requirement is met this many levels short, as on the site. */
	static final int BOOST_ALLOWANCE = 5;
	/** A section is finished, for the player's place, once this share of the steps the game can check in it is done. */
	static final double SECTION_FINISHED = 0.8;

	enum Met
	{
		MET,
		/** Short, but within {@link #BOOST_ALLOWANCE} of a boostable level: shown as met, with "boost". */
		BOOST,
		MISSING,
		/** The client cannot tell: logged out, not read yet, or a kind this version does not know. */
		UNKNOWN;

		boolean met()
		{
			return this == MET || this == BOOST;
		}
	}

	enum Status
	{
		OPEN,
		DONE,
		SKIPPED,
		/** Before the player's place, and nothing in the game shows it done or not: counted as done. */
		PASSED,
		/** Before the player's place, and the game shows it is not done, or the player said so. */
		BEHIND
	}

	/** Where an item to bring is: carried (inventory or worn), in the bank, or neither. */
	enum Have
	{
		CARRIED,
		BANKED,
		MISSING,
		/** Not carried, and the bank has not been open since login. */
		BANK_UNSEEN,
		/** The site gives no item id, so it is shown by name only. */
		NO_ID
	}

	/** The guide's "N/total": done steps over every step not skipped, and the current step, or null when none is open. */
	static final class Count
	{
		final int done;
		final int total;
		final Guide.Step current;

		Count(int done, int total, Guide.Step current)
		{
			this.done = done;
			this.total = total;
			this.current = current;
		}

		@Override
		public String toString()
		{
			return done + "/" + total;
		}
	}

	private GuideRules()
	{
	}

	static Met requirement(Guide.Requirement requirement, GuideFacts facts)
	{
		switch (requirement.kind)
		{
			case SKILL:
			{
				Integer level = facts.realLevel(requirement.skill);
				if (level == null)
				{
					return Met.UNKNOWN;
				}
				if (level >= requirement.level)
				{
					return Met.MET;
				}
				return requirement.boostable && level + BOOST_ALLOWANCE >= requirement.level ? Met.BOOST : Met.MISSING;
			}
			case QUEST:
			{
				QuestState state = facts.quest(requirement.quest);
				return state == null ? Met.UNKNOWN : state == QuestState.FINISHED ? Met.MET : Met.MISSING;
			}
			case QUEST_POINTS:
				return facts.questPoints < 0 ? Met.UNKNOWN : facts.questPoints >= requirement.points ? Met.MET : Met.MISSING;
			case MODE:
				return facts.accountType == null ? Met.UNKNOWN
					: requirement.modes.contains(facts.accountType) ? Met.MET : Met.MISSING;
			case VARBIT:
			case VARP:
			{
				Integer value = requirement.kind == Guide.Requirement.Kind.VARBIT ? facts.varbit(requirement.varId)
					: facts.varp(requirement.varId);
				return value == null ? Met.UNKNOWN : compare(value, requirement.op, requirement.value) ? Met.MET : Met.MISSING;
			}
			case ITEMS:
			{
				boolean unknown = false;
				for (Guide.Item item : requirement.items)
				{
					if (item.id < 0)
					{
						unknown = true;
						continue;
					}
					if (facts.carried(item.id) >= item.qty)
					{
						continue;
					}
					if ("carried".equals(requirement.op))
					{
						return Met.MISSING;
					}
					if (!facts.bankSeen)
					{
						unknown = true;
						continue;
					}
					if (facts.carried(item.id) + facts.banked(item.id) < item.qty)
					{
						return Met.MISSING;
					}
				}
				return unknown ? Met.UNKNOWN : Met.MET;
			}
			case AREA:
			case TALK:
			case KILL:
			case UNKNOWN:
			default:
				// Something that happens, not a state: the tracker sees it when it does ({@link GuideSigns}).
				return Met.UNKNOWN;
		}
	}

	static boolean compare(int value, String op, int target)
	{
		switch (op == null ? ">=" : op)
		{
			case "==":
				return value == target;
			case "!=":
				return value != target;
			case ">":
				return value > target;
			case "<=":
				return value <= target;
			case "<":
				return value < target;
			case ">=":
			default:
				return value >= target;
		}
	}

	/**
	 * Whether the character has done the step, from the live client: every {@code completes} entry met, with a skill
	 * at its real level (a boost does not finish a step). A quest step with no {@code completes} is done when all its
	 * quests are finished, so the guide moves on when Quest Helper's quest ends. A step with neither that picks items
	 * up is done when every quest those items are for is finished (they were fetched and used), or when the player owns
	 * an untradeable item it picks up, carried or banked (only that step gives it). Unknown is never done.
	 */
	static boolean completed(Guide.Step step, GuideFacts facts)
	{
		if (!step.completes.isEmpty())
		{
			// With something that happens among them (an area, a talk, a kill), the step is done when it happens, with
			// the states met then: the tracker marks it ({@link GuideSigns}).
			for (Guide.Requirement entry : step.completes)
			{
				if (entry.kind.event() || requirement(entry, facts) != Met.MET)
				{
					return false;
				}
			}
			return true;
		}
		if (step.quests.isEmpty())
		{
			return pickedUp(step, facts);
		}
		for (String quest : step.quests)
		{
			if (facts.quest(quest) != QuestState.FINISHED)
			{
				return false;
			}
		}
		return true;
	}

	private static boolean pickedUp(Guide.Step step, GuideFacts facts)
	{
		boolean anyFor = false;
		boolean allFinished = true;
		for (Guide.Collect collect : step.collect)
		{
			if (collect.item.untradeable && owned(collect.item, facts))
			{
				return true;
			}
			for (String quest : collect.forQuests)
			{
				anyFor = true;
				allFinished &= facts.quest(quest) == QuestState.FINISHED;
			}
			allFinished &= !collect.forQuests.isEmpty();
		}
		return anyFor && allFinished;
	}

	/** At least one carried or, once the bank has been seen, banked. */
	static boolean owned(Guide.Item item, GuideFacts facts)
	{
		return item.id >= 0 && facts.carried(item.id) + (facts.bankSeen ? facts.banked(item.id) : 0) >= 1;
	}

	/** Skipped wins over done: a skipped step stays out of the count until the player reopens it. */
	static Status status(Guide.Step step, GuideProgress progress, GuideFacts facts)
	{
		GuideProgress.Mark mark = progress.mark(step.id);
		if (mark == GuideProgress.Mark.SKIPPED)
		{
			return Status.SKIPPED;
		}
		return mark == GuideProgress.Mark.DONE || completed(step, facts) ? Status.DONE : Status.OPEN;
	}

	/** Every step's status where it sits in the guide, the count, and the steps left behind the player's place. */
	static final class Standing
	{
		private final Map<String, Status> statuses;
		final Count count;
		/** Steps before the player's place that are not done, in guide order. */
		final List<Guide.Step> behind;

		Standing(Map<String, Status> statuses, Count count, List<Guide.Step> behind)
		{
			this.statuses = statuses;
			this.count = count;
			this.behind = Collections.unmodifiableList(behind);
		}

		Status of(Guide.Step step)
		{
			Status status = statuses.get(step.id);
			return status == null ? Status.OPEN : status;
		}
	}

	/**
	 * Something the plugin can read at any time shows the step done or not: a state in its {@code completes}, or its
	 * quests.
	 */
	static boolean checkable(Guide.Step step)
	{
		if (!step.quests.isEmpty())
		{
			return true;
		}
		for (Guide.Requirement entry : step.completes)
		{
			if (entry.kind.state())
			{
				return true;
			}
		}
		return false;
	}

	/** Every state in the step's {@code completes} is met: what an event needs as well, to tick it. */
	static boolean statesMet(Guide.Step step, GuideFacts facts)
	{
		for (Guide.Requirement entry : step.completes)
		{
			if (entry.kind.state() && requirement(entry, facts) != Met.MET)
			{
				return false;
			}
		}
		return true;
	}

	/** The varbit ids the guide's conditions name, to read. */
	static Set<Integer> varbits(Guide guide)
	{
		return vars(guide, Guide.Requirement.Kind.VARBIT);
	}

	/** The varp ids the guide's conditions name, to read. */
	static Set<Integer> varps(Guide guide)
	{
		return vars(guide, Guide.Requirement.Kind.VARP);
	}

	private static Set<Integer> vars(Guide guide, Guide.Requirement.Kind kind)
	{
		Set<Integer> out = new LinkedHashSet<>();
		for (Guide.Step step : guide.steps)
		{
			for (List<Guide.Requirement> list : java.util.Arrays.asList(step.requires, step.completes))
			{
				for (Guide.Requirement entry : list)
				{
					if (entry.kind == kind)
					{
						out.add(entry.varId);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Where the player is. Their place is found by sections: the first section whose checkable steps are less than
	 * {@link #SECTION_FINISHED} done is where they are, and their place is the last done step before its first open
	 * checkable step, in it or earlier. When every section is mostly done, it is the furthest
	 * done step. Start here moves it on to the step before the one named. An open step at or before the place is passed
	 * when nothing in the game can show it either way, and behind when the game shows it is not done or the player
	 * said so. The current step is the first open one after the place; when there is none, the first one behind. A
	 * player with nothing done starts at the first step, and a step done far ahead does not move the place.
	 */
	static Standing standing(Guide guide, GuideProgress progress, GuideFacts facts)
	{
		List<Guide.Step> steps = guide.steps;
		Status[] base = new Status[steps.size()];
		int furthest = -1;
		for (int i = 0; i < steps.size(); i++)
		{
			base[i] = status(steps.get(i), progress, facts);
			if (base[i] == Status.DONE)
			{
				furthest = i;
			}
		}
		int place = furthest;
		int first = 0;
		int lastDone = -1;
		for (Guide.Section section : guide.sections)
		{
			int checkable = 0;
			int done = 0;
			for (int i = first; i < first + section.steps.size(); i++)
			{
				if (checkable(steps.get(i)) && base[i] != Status.SKIPPED)
				{
					checkable++;
					done += base[i] == Status.DONE ? 1 : 0;
				}
			}
			if (checkable > 0 && done < SECTION_FINISHED * checkable)
			{
				// The player is in this section: their place is the last done step before its first open checkable one.
				place = lastDone;
				for (int i = first; i < first + section.steps.size(); i++)
				{
					if (base[i] == Status.DONE)
					{
						place = i;
					}
					else if (base[i] == Status.OPEN && checkable(steps.get(i)))
					{
						break;
					}
				}
				break;
			}
			for (int i = first; i < first + section.steps.size(); i++)
			{
				if (base[i] == Status.DONE)
				{
					lastDone = i;
				}
			}
			first += section.steps.size();
		}
		String start = progress.start();
		if (start != null)
		{
			for (int i = 0; i < steps.size(); i++)
			{
				if (steps.get(i).id.equals(start))
				{
					place = Math.max(place, i - 1);
					break;
				}
			}
		}
		Map<String, Status> statuses = new HashMap<>();
		List<Guide.Step> behind = new ArrayList<>();
		int done = 0;
		int total = 0;
		Guide.Step current = null;
		for (int i = 0; i < steps.size(); i++)
		{
			Guide.Step step = steps.get(i);
			Status status = base[i];
			if (status == Status.OPEN && i <= place)
			{
				status = checkable(step) || progress.mark(step.id) == GuideProgress.Mark.OPEN ? Status.BEHIND : Status.PASSED;
			}
			statuses.put(step.id, status);
			if (status == Status.SKIPPED)
			{
				continue;
			}
			total++;
			if (status == Status.DONE || status == Status.PASSED)
			{
				done++;
			}
			else if (status == Status.BEHIND)
			{
				behind.add(step);
			}
			else if (current == null)
			{
				current = step;
			}
		}
		if (current == null && !behind.isEmpty())
		{
			current = behind.get(0);
		}
		return new Standing(statuses, new Count(done, total, current), behind);
	}

	/** Done, ticked and passed steps, out of every step that is not skipped, and the current step ({@link #standing}). */
	static Count count(Guide guide, GuideProgress progress, GuideFacts facts)
	{
		return standing(guide, progress, facts).count;
	}

	/**
	 * Mark every open step the character has completed as done, so it stays done in the profile. True when any
	 * changed. A step the player skipped is left skipped.
	 */
	static boolean tickCompleted(Guide guide, GuideProgress progress, GuideFacts facts)
	{
		boolean changed = false;
		for (Guide.Step step : guide.steps)
		{
			if (progress.mark(step.id) == null && completed(step, facts))
			{
				changed |= progress.set(step.id, GuideProgress.Mark.DONE);
			}
		}
		return changed;
	}

	static Have item(Guide.Item item, GuideFacts facts)
	{
		if (item.id < 0)
		{
			return Have.NO_ID;
		}
		if (facts.carried(item.id) >= item.qty)
		{
			return Have.CARRIED;
		}
		if (!facts.bankSeen)
		{
			return Have.BANK_UNSEEN;
		}
		return facts.carried(item.id) + facts.banked(item.id) >= item.qty ? Have.BANKED : Have.MISSING;
	}

	/** Every quest the guide names, in its requirements, its {@code completes} and its quest steps: what to read. */
	static Set<String> quests(Guide guide)
	{
		Set<String> out = new LinkedHashSet<>();
		for (Guide.Step step : guide.steps)
		{
			out.addAll(quests(step));
		}
		return out;
	}

	static Set<String> quests(Guide.Step step)
	{
		Set<String> out = new LinkedHashSet<>(step.quests);
		for (Guide.Requirement requirement : step.requires)
		{
			if (requirement.kind == Guide.Requirement.Kind.QUEST)
			{
				out.add(requirement.quest);
			}
		}
		for (Guide.Requirement requirement : step.completes)
		{
			if (requirement.kind == Guide.Requirement.Kind.QUEST)
			{
				out.add(requirement.quest);
			}
		}
		for (Guide.Collect collect : step.collect)
		{
			out.addAll(collect.forQuests);
		}
		return out;
	}
}
