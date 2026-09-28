package com.osrsindex;

import java.util.regex.Pattern;
import net.runelite.api.MenuAction;
import net.runelite.client.util.Text;

/**
 * The right-click "OSRS Index" entry: an item or NPC's Examine gains a sibling that opens the
 * site's search for that name in the browser. Nothing is sent from the client; the browser opens a public
 * page.
 */
final class SiteLookup
{
	/** The menu option, shown before the item or NPC's own coloured name. */
	static final String OPTION = "OSRS Index";

	private static final Pattern LEVEL = Pattern.compile("\\s*\\((?:level|skill)-\\d+\\)\\s*$", Pattern.CASE_INSENSITIVE);

	/** What an Examine entry names. */
	enum Kind
	{
		ITEM,
		NPC,
		NONE,
	}

	private SiteLookup()
	{
	}

	/** Which kind of thing an entry examines: an item (in any interface, or on the ground), an NPC, or neither. */
	static Kind kindOf(String option, MenuAction type, int itemId)
	{
		if (!"Examine".equals(option) || type == null)
		{
			return Kind.NONE;
		}
		if (type == MenuAction.EXAMINE_NPC)
		{
			return Kind.NPC;
		}
		if (type == MenuAction.EXAMINE_ITEM_GROUND
			|| (itemId > 0 && (type == MenuAction.CC_OP || type == MenuAction.CC_OP_LOW_PRIORITY)))
		{
			return Kind.ITEM;
		}
		return Kind.NONE;
	}

	/**
	 * A name as the game shows it, made fit for the site's search: no colour tags, no combat level, no
	 * hard spaces. Null when nothing useful is left, including the game's placeholder "null".
	 */
	static String cleanName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String clean = LEVEL.matcher(Text.removeTags(name).replace(' ', ' ')).replaceAll("").trim();
		return clean.isEmpty() || "null".equalsIgnoreCase(clean) ? null : clean;
	}
}
