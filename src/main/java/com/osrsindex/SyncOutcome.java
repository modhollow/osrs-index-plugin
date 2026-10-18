package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.EnumSet;
import java.util.Set;

/**
 * What one sync response means for the plugin, decided from the status, the reason code (and, for a
 * refusal, the part it names) and `Retry-After` alone. The waits are the site config's
 * ({@link SiteConfig.Timing}). The body may also carry a {@code player_message} to show in place of the
 * plugin's own line, and a stored sync a {@code config_version} (docs/site-config.md).
 */
final class SyncOutcome
{
	enum Kind
	{
		/** 200: the parts sent are stored. */
		STORED,
		/**
		 * 400 unsupported_schema, or unknown_field: the server predates this schema; step down one. A
		 * server from before schema 2 checks fields before the schema number, so it says unknown_field.
		 */
		OLD_SERVER,
		/**
		 * 400 invalid_captured: the site refused the times in {@code captured}, not the parts they date. Nothing
		 * was stored. Send the same parts again at once without times, which the site then dates at
		 * {@code captured_at}, rather than drop data over a timing fault.
		 */
		PART_TIMES_REFUSED,
		/**
		 * Any other 400, or 415: the body broke the contract. Drop the part the site refused, or every
		 * part sent when it does not say which, rather than loop ({@link #dropped}).
		 */
		REJECTED,
		/** 401: the token is missing, unknown or revoked; stop until it changes. */
		BAD_TOKEN,
		/**
		 * 413: the body was too large. Several parts shrink the budget; a lone part is dropped, since no
		 * budget makes it smaller. Either way, wait: the site checks size before its rate limit.
		 */
		TOO_LARGE,
		/**
		 * 409 too_many_characters: the account already tracks as many characters as the site allows,
		 * and this is a new one. Nothing was stored. Try again only rarely, in case the player frees a
		 * slot on the site.
		 */
		ACCOUNT_FULL,
		/** 429, 503, 5xx or no response: wait, then send the latest state. */
		RETRY,
	}

	/** One sync per token per 30 seconds; a second more keeps clear of the boundary. */
	static final int FLOOR_SECONDS = 31;
	static final int DEFAULT_RETRY_SECONDS = 60;
	/** After ACCOUNT_FULL: fifteen minutes, so a full account costs the site four requests an hour, not sixty. */
	static final int ACCOUNT_FULL_RETRY_SECONDS = 900;

	final Kind kind;
	final String code;
	final int waitSeconds;
	/** REJECTED only: the part the site said it refused, or null when it named none this plugin knows. */
	final Part refused;
	/** The site's text for the player about this answer, already made safe ({@link SiteConfig#playerText}), or null. */
	final String playerMessage;
	/** STORED only: the site config revision the site serves now, or -1 when it did not say. */
	final int configVersion;

	private SyncOutcome(Kind kind, String code, int waitSeconds, Part refused, String playerMessage, int configVersion)
	{
		this.kind = kind;
		this.code = code;
		this.waitSeconds = waitSeconds;
		this.refused = refused;
		this.playerMessage = playerMessage;
		this.configVersion = configVersion;
	}

	static SyncOutcome networkFailure()
	{
		return networkFailure(SiteConfig.Timing.DEFAULTS);
	}

	static SyncOutcome networkFailure(SiteConfig.Timing timing)
	{
		return new SyncOutcome(Kind.RETRY, "network", timing.retrySeconds, null, null, -1);
	}

	static SyncOutcome of(int status, String body, String retryAfter)
	{
		return of(status, body, retryAfter, SiteConfig.Timing.DEFAULTS);
	}

	static SyncOutcome of(int status, String body, String retryAfter, SiteConfig.Timing timing)
	{
		JsonObject json = object(body);
		String code = field(json, "code");
		String message = SiteConfig.playerMessage(json);
		if (status == 200)
		{
			return new SyncOutcome(Kind.STORED, "stored", timing.syncFloorSeconds, null, null, configVersion(json));
		}
		if (status == 400 && ("unsupported_schema".equals(code) || "unknown_field".equals(code)))
		{
			return new SyncOutcome(Kind.OLD_SERVER, code, 0, null, message, -1);
		}
		if (status == 400 && "invalid_captured".equals(code))
		{
			return new SyncOutcome(Kind.PART_TIMES_REFUSED, code, 0, null, message, -1);
		}
		if (status == 400 || status == 415)
		{
			Part refused = status == 400 ? refusedPart(code, field(json, "part")) : null;
			return new SyncOutcome(Kind.REJECTED, code, 0, refused, message, -1);
		}
		if (status == 401)
		{
			return new SyncOutcome(Kind.BAD_TOKEN, code, 0, null, message, -1);
		}
		if (status == 413)
		{
			// The site answers 413 before its rate limiter sees the request, so without a wait here the
			// same body would go out again on the next game tick. A longer Retry-After still counts.
			return new SyncOutcome(Kind.TOO_LARGE, code, Math.max(timing.syncFloorSeconds, seconds(retryAfter) + 1),
				null, message, -1);
		}
		if (status == 409 && "too_many_characters".equals(code))
		{
			return new SyncOutcome(Kind.ACCOUNT_FULL, code, timing.accountFullRetrySeconds, null, message, -1);
		}
		int wait = seconds(retryAfter);
		return new SyncOutcome(Kind.RETRY, code, wait > 0 ? wait + 1 : timing.retrySeconds, null, message, -1);
	}

	/**
	 * The parts of a refused body to stop sending until they change. When the site named the part it
	 * refused, and that part was in the body, only that one: the rest were not at fault, so they stay
	 * dirty and go out on the next send. Otherwise every part sent, since sending the same body again
	 * would only be refused again.
	 */
	Set<Part> dropped(Set<Part> sent)
	{
		if (refused != null && sent.contains(refused))
		{
			return EnumSet.of(refused);
		}
		return sent;
	}

	/** What every section's refusal code starts with: {@code invalid_bank}, {@code invalid_vars} and so on. */
	private static final String INVALID_PREFIX = "invalid_";

	/**
	 * The part a 400 refusal names, or null when it names none this plugin knows. A fault inside a part answers
	 * that part's own code, {@code invalid_} plus its body key, and a bad item is no exception: it answers the
	 * code of the container it sits in, such as {@code invalid_bank} or {@code invalid_storage}. The envelope's
	 * codes ({@code invalid_character}, {@code invalid_captured_at}, {@code invalid_json}) match no part.
	 *
	 * <p>An older site answered every item fault {@code invalid_item}, which names no part, so from that site a
	 * bad item still drops everything sent. A {@code part} field wins over the code. No site sends one now, but
	 * reading it costs nothing and lets a site name the part outright, so it stays.
	 */
	static Part refusedPart(String code, String part)
	{
		Part named = byKey(part);
		if (named == null && code.startsWith(INVALID_PREFIX))
		{
			named = byKey(code.substring(INVALID_PREFIX.length()));
		}
		return named;
	}

	private static Part byKey(String key)
	{
		for (Part part : Part.values())
		{
			if (part.key.equals(key))
			{
				return part;
			}
		}
		return null;
	}

	static String code(String body)
	{
		return field(body, "code");
	}

	/** A string field of a JSON object body, or "" when the body is not one or does not carry it. */
	static String field(String body, String name)
	{
		return field(object(body), name);
	}

	private static String field(JsonObject object, String name)
	{
		return object != null && object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
	}

	/** The body as a JSON object, or null when it is not one. */
	private static JsonObject object(String body)
	{
		if (body == null || body.isEmpty())
		{
			return null;
		}
		try
		{
			JsonElement parsed = new JsonParser().parse(body);
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		}
		catch (RuntimeException ignored)
		{
			// Not JSON: a proxy error page. The status alone decides.
			return null;
		}
	}

	/** A stored sync's {@code config_version}: a whole number of 0 or more, else -1. */
	private static int configVersion(JsonObject json)
	{
		JsonElement value = json == null ? null : json.get("config_version");
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			return -1;
		}
		double number = value.getAsDouble();
		return number >= 0 && number <= Integer.MAX_VALUE && number == Math.rint(number) ? (int) number : -1;
	}

	static int seconds(String retryAfter)
	{
		if (retryAfter == null)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Math.min(3_600, Integer.parseInt(retryAfter.trim())));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
