package com.osrsindex;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OsrsIndexConfig.GROUP)
public interface OsrsIndexConfig extends Config
{
	String GROUP = "osrsindex";

	@ConfigItem(
		keyName = "token",
		name = "Account token",
		description = "Filled in for you when you click \"Link with osrsindex.com\" in the OSRS Index panel and approve"
			+ " this client on the site. You can also paste one from osrsindex.com/account. Until this is set,"
			+ " nothing is sent but the link request that button starts.",
		secret = true,
		position = 0
	)
	default String token()
	{
		return "";
	}

	@ConfigSection(
		name = "What to sync",
		description = "Turn off anything you do not want on your osrsindex.com account.",
		position = 1
	)
	String whatToSync = "whatToSync";

	@ConfigItem(
		keyName = "syncGear",
		name = "Worn items and inventory",
		description = "Your equipment and the 28 inventory slots.",
		section = whatToSync,
		position = 2
	)
	default boolean syncGear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncBank",
		name = "Bank",
		description = "Your bank, sent when it changes while open.",
		section = whatToSync,
		position = 3
	)
	default boolean syncBank()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncProgress",
		name = "Skills, quests and diaries",
		description = "Account type, combat level, skills, quests, achievement diaries and combat achievement counts.",
		section = whatToSync,
		position = 4
	)
	default boolean syncProgress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncCollectionLog",
		name = "Collection log",
		description = "Obtained/total, plus the items on every log page you open.",
		section = whatToSync,
		position = 5
	)
	default boolean syncCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "syncLocation",
		name = "Location",
		description = "Your current in-game position and world. The site keeps the latest point only.",
		section = whatToSync,
		position = 6
	)
	default boolean syncLocation()
	{
		return true;
	}

	@ConfigSection(
		name = "Walk here from the site",
		description = "Places you send from osrsindex.com's map, routed by Shortest Path.",
		position = 7
	)
	String walkHere = "walkHere";

	@ConfigItem(
		keyName = "receiveDestinations",
		name = "Receive destinations",
		description = "Keep a connection open to osrsindex.com so a place you pick on its map with \"Walk here in"
			+ " RuneLite\" is routed here at once, by Shortest Path. It receives only the places you send, and"
			+ " only while this client is linked and you are logged in.",
		section = walkHere,
		position = 8
	)
	default boolean receiveDestinations()
	{
		return true;
	}

	@ConfigSection(
		name = "Guides",
		description = "osrsindex.com's step-by-step guides, followed in the OSRS Index panel.",
		position = 9
	)
	String guides = "guides";

	@ConfigItem(
		keyName = "showGuides",
		name = "Show guides",
		description = "A Guides tab in the OSRS Index panel. Guides are downloaded from osrsindex.com only when you"
			+ " open that tab or a guide, and need no link. Your progress stays on this computer.",
		section = guides,
		position = 10
	)
	default boolean showGuides()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideMapMarker",
		name = "World map marker",
		description = "Mark the place of the guide's current step on the world map.",
		section = guides,
		position = 11
	)
	default boolean guideMapMarker()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideMinimapArrow",
		name = "Minimap arrow",
		description = "Point the way to the current step's place on the minimap, when it is on your floor.",
		section = guides,
		position = 12
	)
	default boolean guideMinimapArrow()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideTileHighlight",
		name = "Tile highlight",
		description = "Outline the current step's tile in the game world when it is in view.",
		section = guides,
		position = 13
	)
	default boolean guideTileHighlight()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideStepBox",
		name = "Step box",
		description = "Show the current step in a box on the game screen, as Quest Helper does. It hides while Quest"
			+ " Helper guides the step's quest.",
		section = guides,
		position = 14
	)
	default boolean guideStepBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideQuestHelper",
		name = "Hand quest steps to Quest Helper",
		description = "When the Quest Helper plugin is on, let it guide a guide's quest steps. The guide moves on by"
			+ " itself when the quest is finished.",
		section = guides,
		position = 15
	)
	default boolean guideQuestHelper()
	{
		return true;
	}
}
