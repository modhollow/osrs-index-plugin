package com.osrsindex;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

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
		return override("osrsindex.origin", DEFAULT_ORIGIN, "https://", "http://");
	}

	/**
	 * A development override, or {@code fallback}. The token goes to it, so it must be encrypted ({@code secure}) or
	 * this computer ({@code plain} to localhost); anything else is ignored.
	 */
	static String override(String property, String fallback, String secure, String plain)
	{
		String value = System.getProperty(property, fallback).trim();
		while (value.endsWith("/"))
		{
			value = value.substring(0, value.length() - 1);
		}
		boolean local = LOCAL.matcher(value).matches() && value.startsWith(plain);
		boolean encrypted = value.startsWith(secure) && value.length() > secure.length();
		return encrypted || local ? value : fallback;
	}

	/** {@code http://localhost}, {@code 127.0.0.1} or {@code [::1]}, with a port or none. */
	private static final Pattern LOCAL = Pattern.compile("[a-z]+://(localhost|127\\.0\\.0\\.1|\\[::1])(:\\d{1,5})?");

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

	/** What the site asks of this client: a {@code GET} with the token as a bearer (docs/site-config.md). */
	static String pluginConfig()
	{
		return origin() + "/api/v1/tracker/plugin-config";
	}

	/** Every guide the site offers in game: a public {@code GET} (docs/guides.md). */
	static String guides()
	{
		return origin() + "/api/v1/guides";
	}

	/** One guide, whole: a public {@code GET}. {@code id} is a slug ({@link GuideParser#GUIDE_ID}). */
	static String guide(String id)
	{
		return guides() + "/" + encode(id);
	}

	/** Where Unlink revokes this client's token: a {@code DELETE} with the token as a bearer. */
	static String revokeToken()
	{
		return origin() + "/api/v1/tracker/token";
	}

	/** The WebSocket the site pushes map places down. */
	static String destinations()
	{
		return override("osrsindex.relay", DEFAULT_RELAY, "wss://", "ws://") + "/v1/destinations";
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

	/**
	 * One character's page. An account can hold several, and without {@code ?character=} the site shows
	 * the first it lists. The name goes as the site stores it, hard spaces read as spaces, or the site would
	 * not find it. With no name, the plain page.
	 */
	static String tracker(String character)
	{
		String name = character == null ? "" : character.replace('\u00A0', ' ').trim();
		return name.isEmpty() ? tracker() : tracker() + "?character=" + encode(name);
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

	private static String encode(String value)
	{
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
