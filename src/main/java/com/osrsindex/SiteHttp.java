package com.osrsindex;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

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

	/** A client for osrsindex.com, derived from {@code shared}, that sends {@code version} on every request. */
	static OkHttpClient derive(OkHttpClient shared, String version)
	{
		return shared.newBuilder().addInterceptor(new VersionHeader(version)).build();
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
