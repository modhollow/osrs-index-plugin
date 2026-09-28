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
			+ " this client on the site. You can also paste one from osrsindex.com/account. Nothing is sent until"
			+ " this is set.",
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
		name = "Look up on osrsindex.com",
		description = "Open the site from the game's right-click menu.",
		position = 9
	)
	String lookUp = "lookUp";

	@ConfigItem(
		keyName = "lookupMenu",
		name = "Right-click lookups",
		description = "Add \"OSRS Index\" beside Examine on items and NPCs. It opens osrsindex.com's search for that"
			+ " name in your browser. Nothing is sent from the client.",
		section = lookUp,
		position = 10
	)
	default boolean lookupMenu()
	{
		return true;
	}
}
