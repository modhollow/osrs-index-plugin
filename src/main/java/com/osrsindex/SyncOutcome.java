package com.osrsindex;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What one sync response means for the plugin, decided from the status, the reason code and
 * `Retry-After` alone.
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
		/** Any other 400, or 415: the body broke the contract; drop those parts rather than loop. */
		REJECTED,
		/** 401: the token is missing, unknown or revoked; stop until it changes. */
		BAD_TOKEN,
		/** 413: the body was too large; shrink the budget. */
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

	private SyncOutcome(Kind kind, String code, int waitSeconds)
	{
		this.kind = kind;
		this.code = code;
		this.waitSeconds = waitSeconds;
	}

	static SyncOutcome networkFailure()
	{
		return new SyncOutcome(Kind.RETRY, "network", DEFAULT_RETRY_SECONDS);
	}

	static SyncOutcome of(int status, String body, String retryAfter)
	{
		String code = code(body);
		if (status == 200)
		{
			return new SyncOutcome(Kind.STORED, "stored", FLOOR_SECONDS);
		}
		if (status == 400 && ("unsupported_schema".equals(code) || "unknown_field".equals(code)))
		{
			return new SyncOutcome(Kind.OLD_SERVER, code, 0);
		}
		if (status == 400 || status == 415)
		{
			return new SyncOutcome(Kind.REJECTED, code, 0);
		}
		if (status == 401)
		{
			return new SyncOutcome(Kind.BAD_TOKEN, code, 0);
		}
		if (status == 413)
		{
			return new SyncOutcome(Kind.TOO_LARGE, code, 0);
		}
		if (status == 409 && "too_many_characters".equals(code))
		{
			return new SyncOutcome(Kind.ACCOUNT_FULL, code, ACCOUNT_FULL_RETRY_SECONDS);
		}
		int wait = seconds(retryAfter);
		return new SyncOutcome(Kind.RETRY, code, wait > 0 ? wait + 1 : DEFAULT_RETRY_SECONDS);
	}

	static String code(String body)
	{
		if (body == null || body.isEmpty())
		{
			return "";
		}
		try
		{
			JsonElement parsed = new JsonParser().parse(body);
			if (parsed.isJsonObject())
			{
				JsonObject object = parsed.getAsJsonObject();
				if (object.has("code") && object.get("code").isJsonPrimitive())
				{
					return object.get("code").getAsString();
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// Not JSON: a proxy error page. The status alone decides.
		}
		return "";
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
