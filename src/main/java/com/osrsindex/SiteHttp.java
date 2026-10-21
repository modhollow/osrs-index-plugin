package com.osrsindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * The HTTP client for every request this plugin makes to osrsindex.com: the sync, link start, link poll and
 * the destinations socket's upgrade. Each one names this plugin's version in {@value #VERSION_HEADER}, so the
 * site can show which version each linked client runs and tell a player when an update is really needed.
 *
 * <p>The client is derived from RuneLite's shared one with {@code newBuilder()}, so it shares that client's
 * connection pool and threads. The shared client itself is never changed: other plugins use it, and the
 * header means something only to osrsindex.com.
 */
final class SiteHttp
{
	/**
	 * The header the site's plugin contract names. It is optional: a site that does not read it, or reads a
	 * value it cannot use, answers exactly as it would without it.
	 */
	static final String VERSION_HEADER = "X-OSRS-Index-Plugin";

	private SiteHttp()
	{
	}

	/**
	 * A client for osrsindex.com, derived from {@code shared}, that sends {@code version} on every request. It follows
	 * no redirect: a sync redirected elsewhere would arrive as a GET whose answer could read as stored, and every
	 * route the plugin uses answers where it is asked.
	 */
	static OkHttpClient derive(OkHttpClient shared, String version)
	{
		return shared.newBuilder()
			.followRedirects(false)
			.followSslRedirects(false)
			.addInterceptor(new VersionHeader(version))
			.build();
	}

	/** An answer's body as text, or null when it is over {@code maxBytes}: it is never read whole into memory. */
	static String bounded(ResponseBody body, long maxBytes) throws IOException
	{
		if (body.contentLength() > maxBytes)
		{
			return null;
		}
		BufferedSource source = body.source();
		if (source.request(maxBytes + 1L))
		{
			return null;
		}
		return source.getBuffer().readString(StandardCharsets.UTF_8);
	}

	/**
	 * Sets the version header on each request, replacing any value already there. An application
	 * interceptor, so it also runs for a WebSocket's upgrade request.
	 */
	static final class VersionHeader implements Interceptor
	{
		private final String version;

		VersionHeader(String version)
		{
			this.version = version;
		}

		@Override
		public Response intercept(Chain chain) throws IOException
		{
			return chain.proceed(chain.request().newBuilder().header(VERSION_HEADER, version).build());
		}
	}
}
