package com.osrsindex;

import java.util.regex.Pattern;

/**
 * The link token's form. A token goes into an {@code Authorization} header, and OkHttp refuses a header value with
 * any character outside printable ASCII by throwing an exception whose message holds the whole value. So no request
 * is ever built with a token that has not passed {@link #wellFormed}.
 */
final class Tokens
{
	static final String PREFIX = "osrsidx_";

	/** The site issues {@code osrsidx_} and 43 base64url characters; this allows some room either way. */
	private static final Pattern FORM = Pattern.compile("osrsidx_[A-Za-z0-9_-]{16,256}");

	/** Spaces of every kind, invisible format characters and control characters, which a copy and paste can carry. */
	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Z}\\p{Cf}\\p{Cc}]");

	private Tokens()
	{
	}

	/** The token as set, with every space, invisible and control character removed; empty for none. */
	static String normalize(String raw)
	{
		return raw == null ? "" : INVISIBLE.matcher(raw).replaceAll("");
	}

	/** Whether {@code token} has the site's form, and so is safe to send. */
	static boolean wellFormed(String token)
	{
		return token != null && FORM.matcher(token).matches();
	}
}
