package com.osrsindex;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Opens the destinations socket with OkHttp, as the contract asks: the relay's URL, the link token as a
 * bearer, the {@code osrsindex.destinations.v1} subprotocol, and a WebSocket ping every 30 s. The plugin
 * passes its {@link SiteHttp} client, and {@code newBuilder()} keeps its interceptor, so the upgrade request
 * names the plugin's version too.
 */
final class OkHttpConnector implements DestinationSocket.Connector
{
	/** The longest relay frame read, in characters. */
	static final int MAX_FRAME_CHARS = 16_384;
	/** WebSocket close code 1009: a message too big to process. */
	static final int CLOSE_TOO_BIG = 1009;
	static final long MAX_RETRY_AFTER_SECONDS = 3_600;

	static final long PING_SECONDS = 30;

	private final OkHttpClient client;
	private final Supplier<String> url;

	OkHttpConnector(OkHttpClient base, Supplier<String> url)
	{
		this.client = base.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).build();
		this.url = url;
	}

	@Override
	public DestinationSocket.Connection open(String token, DestinationSocket.Events events)
	{
		Request request = new Request.Builder()
			.url(url.get())
			.header("Authorization", "Bearer " + token)
			.header("Sec-WebSocket-Protocol", DestinationSocket.SUBPROTOCOL)
			.build();
		OkHttpConnection connection = new OkHttpConnection();
		connection.socket = client.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket webSocket, Response response)
			{
				events.opened(connection, response.header("Sec-WebSocket-Protocol"));
			}

			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				// A frame is a few hundred bytes. A far larger one is not a frame this plugin reads: drop the socket,
				// which reconnects as after any drop.
				if (text.length() > MAX_FRAME_CHARS)
				{
					webSocket.close(CLOSE_TOO_BIG, null);
					return;
				}
				events.text(connection, text);
			}

			@Override
			public void onClosing(WebSocket webSocket, int code, String reason)
			{
				// Answer the server's close, so onClosed follows with its code.
				webSocket.close(DestinationSocket.CLOSE_NORMAL, null);
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				events.closed(connection, code);
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable failure, Response response)
			{
				events.failed(connection, response == null ? 0 : response.code(), retryAfterSeconds(response));
			}
		});
		return connection;
	}

	/** A numeric {@code Retry-After}, or -1. */
	static long retryAfterSeconds(Response response)
	{
		String header = response == null ? null : response.header("Retry-After");
		if (header == null)
		{
			return -1;
		}
		try
		{
			// At most an hour, as for a sync: a wait of years would leave Walk here off until the next login.
			return Math.min(MAX_RETRY_AFTER_SECONDS, Math.max(0, Long.parseLong(header.trim())));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static final class OkHttpConnection implements DestinationSocket.Connection
	{
		private volatile WebSocket socket;

		@Override
		public boolean send(String text)
		{
			WebSocket current = socket;
			return current != null && current.send(text);
		}

		@Override
		public void close(int code, String reason)
		{
			WebSocket current = socket;
			if (current != null)
			{
				current.close(code, reason);
			}
		}
	}
}
