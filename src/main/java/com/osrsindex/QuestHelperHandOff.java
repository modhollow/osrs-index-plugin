package com.osrsindex;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.client.events.PluginMessage;

/**
 * Hands a guide's quest step to the Quest Helper plugin, which guides quests better than a guide's text can. Quest
 * Helper has no way in from another plugin yet, so this works with what it has: it starts its helper by itself
 * when the game says "You've started a new quest" (its "Auto start helper" setting, on by default), and the player
 * can start one from the Quest List. The panel says which, and what to click.
 *
 * <p><b>Follow with Quest Helper</b> also posts {@code questhelper}/{@code start}, the message proposed upstream in
 * https://github.com/Zoinkwiz/quest-helper/pull/2756, so the button starts the helper once Quest Helper reads it.
 * Until then nothing listens, and the manual way stays on screen.
 *
 * <p>Read only: this never changes Quest Helper's settings, reads nothing of it but its public name and config, and
 * never touches its panel or overlays.
 */
final class QuestHelperHandOff
{
	/** Quest Helper's {@code @PluginDescriptor} name. */
	static final String PLUGIN_NAME = "Quest Helper";
	static final String CONFIG_GROUP = "questhelper";
	/** Its "Auto start helper" setting. Absent means on, its default. */
	static final String AUTO_START_KEY = "autostartQuests";
	static final String NAMESPACE = "questhelper";
	static final String START = "start";
	/** Proposed in https://github.com/Zoinkwiz/quest-helper/pull/2846: the helper Quest Helper now shows, or none. */
	static final String SELECTED = "selected";
	static final String SOURCE = "OSRS Index";

	/**
	 * Quests in the game with no helper in Quest Helper (checked at its v4.18.0, commit 5f79193). A step for one is
	 * guided by this plugin, as if Quest Helper were off.
	 */
	static final Set<String> NO_HELPER = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"Learning the Ropes",
		"Into the Tombs",
		"The Frozen Door",
		"A Ruff Situation")));

	/**
	 * Where Quest Helper's name is not the game's. Recipe for Disaster's parts are "RFD - ...", and two of them are
	 * named for where they fall in the quest. Shield of Arrav is split by gang ({@link #helperName}).
	 */
	private static final Map<String, String> RENAMED;

	static
	{
		Map<String, String> renamed = new LinkedHashMap<>();
		renamed.put("Recipe for Disaster - Another Cook's Quest", "RFD - Start");
		renamed.put("Recipe for Disaster - Mountain Dwarf", "RFD - Mountain Dwarf");
		renamed.put("Recipe for Disaster - Wartface & Bentnoze", "RFD - Wartface & Bentnoze");
		renamed.put("Recipe for Disaster - Pirate Pete", "RFD - Pirate Pete");
		renamed.put("Recipe for Disaster - Lumbridge Guide", "RFD - Lumbridge Guide");
		renamed.put("Recipe for Disaster - Evil Dave", "RFD - Evil Dave");
		renamed.put("Recipe for Disaster - King Awowogei", "RFD - King Awowogei");
		renamed.put("Recipe for Disaster - Sir Amik Varze", "RFD - Sir Amik Varze");
		renamed.put("Recipe for Disaster - Skrach Uglogwee", "RFD - Skrach Uglogwee");
		renamed.put("Recipe for Disaster - Culinaromancer", "RFD - Finale");
		RENAMED = Collections.unmodifiableMap(renamed);
	}

	/** The game's quest names ({@link Quest#getName()}), which a guide's {@code quests} uses. */
	private static final Set<String> GAME_QUESTS;

	static
	{
		Set<String> names = new HashSet<>();
		for (Quest quest : Quest.values())
		{
			names.add(quest.getName());
		}
		GAME_QUESTS = Collections.unmodifiableSet(names);
	}

	static final String SHIELD_OF_ARRAV = "Shield of Arrav";
	static final String RECIPE_FOR_DISASTER = "Recipe for Disaster";

	/** Which Shield of Arrav gang the character joined, from the quest's two progress varps. */
	enum Gang
	{
		PHOENIX("Shield of Arrav - Phoenix Gang"),
		BLACK_ARM("Shield of Arrav - Black Arm Gang"),
		/** Not joined yet. Quest Helper picks a gang itself for the plain name. */
		UNKNOWN(SHIELD_OF_ARRAV);

		final String helperName;

		Gang(String helperName)
		{
			this.helperName = helperName;
		}

		/**
		 * The gang from {@code PHOENIXGANG} (varp 145, which also counts the quest's start) and {@code BLACKARMGANG}
		 * (varp 146). Quest Helper's Phoenix helper takes the player to Straven at 4, so 5 and on is in the gang. The
		 * Black Arm helper sends the player to Katrina from 1.
		 */
		static Gang of(int phoenixGang, int blackArmGang)
		{
			if (phoenixGang >= 5)
			{
				return PHOENIX;
			}
			return blackArmGang >= 1 ? BLACK_ARM : UNKNOWN;
		}
	}

	enum Stage
	{
		/** No quest, Quest Helper off or set aside, or no helper for the quest: this plugin guides the step. */
		OURS,
		/** Quest Helper will guide it; the quest has not started. The map point leads to the start. */
		BEFORE_START,
		/** The quest is under way, so Quest Helper guides it, and this plugin's point and arrow stand down. */
		STARTED,
		/** Every quest of the step is finished: the step is done. */
		FINISHED
	}

	final Stage stage;
	/** The game's name of the quest Quest Helper is for, or null. */
	final String quest;
	/** Quest Helper's name for it, or null. */
	final String helperName;
	final boolean autoStart;

	private QuestHelperHandOff(Stage stage, String quest, String helperName, boolean autoStart)
	{
		this.stage = stage;
		this.quest = quest;
		this.helperName = helperName;
		this.autoStart = autoStart;
	}

	static final QuestHelperHandOff OURS = new QuestHelperHandOff(Stage.OURS, null, null, false);

	/** Whether this plugin draws the step's map point and minimap arrow: always, except while Quest Helper has it. */
	boolean ownsThePlace()
	{
		return stage != Stage.STARTED;
	}

	/**
	 * The hand-off for {@code step}. {@code questHelperOn}: Quest Helper is running and the player left "Hand quest
	 * steps to Quest Helper" on. The first quest of the step not finished decides it.
	 */
	static QuestHelperHandOff of(Guide.Step step, boolean questHelperOn, boolean autoStart, Gang gang, GuideFacts facts)
	{
		if (!questHelperOn || step.quests.isEmpty())
		{
			return OURS;
		}
		for (String quest : step.quests)
		{
			QuestState state = facts.quest(quest);
			if (state == QuestState.FINISHED)
			{
				continue;
			}
			String helper = helperName(quest, gang);
			if (helper == null)
			{
				return OURS;
			}
			return new QuestHelperHandOff(state == QuestState.IN_PROGRESS ? Stage.STARTED : Stage.BEFORE_START, quest,
				helper, autoStart);
		}
		return new QuestHelperHandOff(Stage.FINISHED, null, null, autoStart);
	}

	/**
	 * Quest Helper's name for the game's quest, or null when it has no helper for it or the name is not one of the
	 * game's. Its names are the game's, apart from Recipe for Disaster's parts and Shield of Arrav's gangs. The whole
	 * of Recipe for Disaster goes as is: Quest Helper opens its first part.
	 */
	static String helperName(String gameQuest, Gang gang)
	{
		if (gameQuest == null || !GAME_QUESTS.contains(gameQuest) || NO_HELPER.contains(gameQuest))
		{
			return null;
		}
		if (SHIELD_OF_ARRAV.equals(gameQuest))
		{
			return (gang == null ? Gang.UNKNOWN : gang).helperName;
		}
		String renamed = RENAMED.get(gameQuest);
		return renamed != null ? renamed : gameQuest;
	}

	/** The name the quest has in the game's Quest List, where Quest Helper's right-click option is. */
	static String questListName(String gameQuest)
	{
		return gameQuest.startsWith(RECIPE_FOR_DISASTER + " - ") ? RECIPE_FOR_DISASTER : gameQuest;
	}

	/** The message Follow with Quest Helper posts. */
	static PluginMessage startMessage(String helperName)
	{
		Map<String, Object> data = new HashMap<>();
		data.put("quest", helperName);
		data.put("source", SOURCE);
		return new PluginMessage(NAMESPACE, START, Collections.unmodifiableMap(data));
	}

	/** Quest Helper's {@code selected} message, as proposed: the helper it shows, or null when it shows none. */
	static String selected(PluginMessage message)
	{
		Object quest = message.getData() == null ? null : message.getData().get("quest");
		return quest instanceof String ? GuideParser.text((String) quest, GuideParser.MAX_TITLE) : null;
	}

	/** "Auto start helper" from Quest Helper's config, as stored: absent or anything but "false" is on. */
	static boolean autoStart(String stored)
	{
		return !"false".equalsIgnoreCase(stored == null ? "" : stored.trim());
	}

	/** What the panel says about the hand-off, as plain text, or null when this plugin guides the step. */
	String panelText()
	{
		if (stage != Stage.BEFORE_START && stage != Stage.STARTED)
		{
			return null;
		}
		String manual = "open the Quest List, right-click " + questListName(quest) + " and pick Start Quest Helper"
			+ (helperName.equals(questListName(quest)) ? "" : " for " + helperName) + ".";
		switch (stage)
		{
			case BEFORE_START:
				return autoStart
					? "Quest Helper guides this quest. It starts by itself when you start " + quest + ". To start it now, "
					+ manual
					: "Quest Helper guides this quest. To start it, " + manual;
			case STARTED:
				return "Quest Helper guides " + quest + " now. This guide moves on by itself when the quest is finished."
					+ " If Quest Helper is not showing it, " + manual;
			default:
				return null;
		}
	}
}
