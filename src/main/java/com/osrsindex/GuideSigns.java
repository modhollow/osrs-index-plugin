package com.osrsindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the game can show happening that ticks a step. The site names it in {@code completes}, as an {@code area}, a
 * {@code talk} or a {@code kill}; for a step with no {@code completes} and no quests, it is read from the title and
 * the step's fields instead: talking to the person it names ("Meet the Mining Instructor"), killing what it names
 * ("Kill a goblin", "Kill 5 cows"), reaching the place it sends you to ("Go to Draynor Village"), or holding the items
 * it tells you to get ("Buy a hammer"). A step whose title matches none of these has no signs, and is ticked by hand.
 *
 * <p>The site's own {@code completes} always wins; these are only for steps it has not described yet. They are
 * checked against the current step alone, so talking to someone a later step names ticks nothing early.
 */
final class GuideSigns
{
	/** How near the place counts as reaching it, in tiles, on its plane. */
	static final int REACH_TILES = 6;

	static final GuideSigns NONE = new GuideSigns(Collections.emptySet(), Collections.emptySet(), 0, null, REACH_TILES,
		Collections.emptyList());

	private static final Pattern TALK = Pattern.compile("^(?:talk to|speak to|speak with|meet|ask)\\s+(.+)$");
	private static final Pattern KILL = Pattern.compile("^(?:kill|defeat|slay)\\s+(?:(\\d+)\\s+)?(.+)$");
	private static final Pattern REACH = Pattern.compile(
		"^(?:go to|walk to|run to|travel to|head to|teleport to|return to|go back to)\\s+.+$");
	private static final Pattern GET = Pattern.compile("^(?:get|buy|collect|pick up|gather|grab|take)\\s+.+$");

	/** Names a dialogue may show for the person to talk to, normalised. */
	final Set<String> talk;
	/** Names of what to kill, normalised. */
	final Set<String> kill;
	/** How many kills tick the step: 1 unless the title counts them. */
	final int kills;
	/** The place to reach, with a tile, or null. */
	final Guide.Place reach;
	/** How near the place counts, in tiles. */
	final int reachTiles;
	/** Items to hold, each with an id. */
	final List<Guide.Item> get;

	private GuideSigns(Set<String> talk, Set<String> kill, int kills, Guide.Place reach, int reachTiles,
		List<Guide.Item> get)
	{
		this.talk = talk;
		this.kill = kill;
		this.kills = kills;
		this.reach = reach;
		this.reachTiles = reachTiles;
		this.get = get;
	}

	static GuideSigns of(Guide.Step step)
	{
		if (!step.quests.isEmpty())
		{
			return NONE;
		}
		Set<String> talkNames = new LinkedHashSet<>();
		Set<String> killNames = new LinkedHashSet<>();
		int killCount = 1;
		Guide.Place area = null;
		int tiles = REACH_TILES;
		for (Guide.Requirement entry : step.completes)
		{
			switch (entry.kind)
			{
				case TALK:
					talkNames.add(name(entry.npc));
					break;
				case KILL:
					killNames.add(name(entry.npc));
					killCount = Math.max(killCount, entry.value);
					break;
				case AREA:
					if (area == null)
					{
						area = entry.place;
						tiles = entry.value;
					}
					break;
				default:
					break;
			}
		}
		if (!talkNames.isEmpty() || !killNames.isEmpty() || area != null)
		{
			// The site said what happens: that, and no guessing.
			return new GuideSigns(Collections.unmodifiableSet(talkNames), Collections.unmodifiableSet(killNames),
				killNames.isEmpty() ? 0 : killCount, area, tiles, Collections.emptyList());
		}
		if (!step.completes.isEmpty())
		{
			return NONE;
		}
		String title = step.title.trim().toLowerCase(Locale.ROOT);
		Matcher talk = TALK.matcher(title);
		if (talk.matches())
		{
			Set<String> names = new LinkedHashSet<>();
			names.add(name(talk.group(1)));
			for (Guide.Place place : step.places)
			{
				names.add(name(place.label));
			}
			return new GuideSigns(Collections.unmodifiableSet(names), Collections.emptySet(), 0, null, REACH_TILES,
				Collections.emptyList());
		}
		Matcher kill = KILL.matcher(title);
		if (kill.matches())
		{
			Set<String> names = new LinkedHashSet<>();
			names.add(name(kill.group(2)));
			for (Guide.Place place : step.places)
			{
				names.add(name(place.label));
			}
			int count = kill.group(1) == null ? 1 : Math.max(1, Math.min(1000, Integer.parseInt(kill.group(1))));
			return new GuideSigns(Collections.emptySet(), Collections.unmodifiableSet(names), count, null, REACH_TILES,
				Collections.emptyList());
		}
		if (REACH.matcher(title).matches() && step.point() != null)
		{
			return new GuideSigns(Collections.emptySet(), Collections.emptySet(), 0, step.point(), REACH_TILES,
				Collections.emptyList());
		}
		if (GET.matcher(title).matches())
		{
			List<Guide.Item> items = new ArrayList<>();
			for (Guide.Item item : step.bring)
			{
				if (item.id >= 0)
				{
					items.add(item);
				}
			}
			for (Guide.Collect collect : step.collect)
			{
				if (collect.item.id >= 0)
				{
					items.add(collect.item);
				}
			}
			if (!items.isEmpty() && items.size() == step.bring.size() + step.collect.size())
			{
				return new GuideSigns(Collections.emptySet(), Collections.emptySet(), 0, null, REACH_TILES,
					Collections.unmodifiableList(items));
			}
		}
		return NONE;
	}

	boolean none()
	{
		return talk.isEmpty() && kill.isEmpty() && reach == null && get.isEmpty();
	}

	/** A dialogue opened with someone of this name. */
	boolean talkedTo(String npc)
	{
		return npc != null && talk.contains(name(npc));
	}

	/** Something of this name died fighting the player. */
	boolean isKill(String npc)
	{
		return npc != null && kill.contains(name(npc));
	}

	boolean reached(int x, int y, int plane)
	{
		return reach != null && plane == reach.plane && Math.abs(x - reach.x) <= reachTiles
			&& Math.abs(y - reach.y) <= reachTiles;
	}

	/** Every item to get is carried, inventory or worn, in the numbers asked. Unknown is never held. */
	boolean held(GuideFacts facts)
	{
		if (get.isEmpty())
		{
			return false;
		}
		for (Guide.Item item : get)
		{
			if (GuideRules.item(item, facts) != GuideRules.Have.CARRIED)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * A name as the signs compare it: lower case, without a leading article or count, and without a plural ending,
	 * so "the Mining Instructor", "Mining Instructor", "a goblin" and "Goblins" meet their game names.
	 */
	static String name(String text)
	{
		String out = text.trim().toLowerCase(Locale.ROOT).replaceAll("<[^>]*>", "").replaceAll("\\s+", " ");
		out = out.replaceFirst("^(?:the|a|an|some|\\d+)\\s+", "");
		if (out.endsWith("es") && (out.endsWith("ches") || out.endsWith("shes") || out.endsWith("xes")))
		{
			out = out.substring(0, out.length() - 2);
		}
		else if (out.endsWith("s") && !out.endsWith("ss") && out.length() > 3)
		{
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}
}
