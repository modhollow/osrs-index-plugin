package com.osrsindex;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Where the site is. Production unless `-Dosrsindex.origin=http://localhost:5173` points it at a local
 * copy (`gradlew run -Posrsindex.origin=…`). The destinations relay is a service of its own, so it has its
 * own override: `-Dosrsindex.relay=ws://localhost:8787` for a local relay.
 */
final class Endpoints
{
	static final String DEFAULT_ORIGIN = "https://osrsindex.com";
	static final String DEFAULT_RELAY = "wss://relay.osrsindex.com";

	private Endpoints()
	{
	}

	static String origin()
	{
		String origin = System.getProperty("osrsindex.origin", DEFAULT_ORIGIN).trim();
		while (origin.endsWith("/"))
		{
			origin = origin.substring(0, origin.length() - 1);
		}
		return origin.isEmpty() ? DEFAULT_ORIGIN : origin;
	}

	static String sync()
	{
		return origin() + "/api/v1/tracker/sync";
	}

	static String linkStart()
	{
		return origin() + "/api/v1/tracker/link/start";
	}

	static String linkPoll()
	{
		return origin() + "/api/v1/tracker/link/poll";
	}

	/** The WebSocket the site pushes map places down. */
	static String destinations()
	{
		String relay = System.getProperty("osrsindex.relay", DEFAULT_RELAY).trim();
		while (relay.endsWith("/"))
		{
			relay = relay.substring(0, relay.length() - 1);
		}
		return (relay.isEmpty() ? DEFAULT_RELAY : relay) + "/v1/destinations";
	}

	/** The page a player opens to approve a link code. */
	static String linkPage()
	{
		return origin() + "/link";
	}

	/** The approval page with the code in it (the site's {@code verification_url_complete}). */
	static String linkApproval(String userCode)
	{
		return linkPage() + "?code=" + encode(userCode);
	}

	/** The signed-in player's character page, where everything this plugin sends is shown. */
	static String tracker()
	{
		return origin() + "/tracker";
	}

	/** The RuneLite section of the account page: linked clients, Revoke, and deleting what was sent. */
	static String account()
	{
		return origin() + "/account#runelite";
	}

	/** The site's world map, marking one tile. */
	static String map(int x, int y, int plane)
	{
		return origin() + "/map?x=" + x + "&y=" + y + "&plane=" + plane;
	}

	/** The site's world map. */
	static String map()
	{
		return origin() + "/map";
	}

	/**
	 * The Old School RuneScape Wiki's search for a name, as a player would type it. An exact page name
	 * opens that page; anything else opens the wiki's results. Always the real wiki, never this site,
	 * whatever origin is set for development.
	 */
	static String wikiSearch(String query)
	{
		return WIKI_SEARCH + encode(query);
	}

	static final String WIKI_SEARCH = "https://oldschool.runescape.wiki/w/Special:Search?search=";

	private static String encode(String value)
	{
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
