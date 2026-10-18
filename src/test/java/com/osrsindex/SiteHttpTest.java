package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Test;

/**
 * Every request to osrsindex.com names the plugin's version in {@code X-OSRS-Index-Plugin}: the sync, link
 * start, link poll, the destinations upgrade and the unlink revoke. Each test sends the plugin's own request through the client
 * the plugin uses. A last interceptor records the request and answers in place of the network, so nothing
 * leaves the machine.
 */
public class SiteHttpTest
{
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "a".repeat(43);
	private static final MediaType JSON = MediaType.parse("application/json");
	/** The form the site reads; anything else it ignores. Mirrors the site's plugin-version parser. */
	private static final Pattern SITE_VERSION =
		Pattern.compile("(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})\\.(0|[1-9]\\d{0,3})(-[0-9A-Za-z][0-9A-Za-z.-]*)?");

	private final OkHttpClient shared = new OkHttpClient();
	private final List<Request> seen = Collections.synchronizedList(new ArrayList<>());
	private final Semaphore arrived = new Semaphore(0);
	private final Deque<String> answers = new ArrayDeque<>();

	@After
	public void stopThreads()
	{
		shared.dispatcher().executorService().shutdown();
		shared.connectionPool().evictAll();
	}

	/** {@code client} plus a last interceptor that records each request and answers 200 with the next body. */
	private OkHttpClient recording(OkHttpClient client, String... bodies)
	{
		answers.addAll(Arrays.asList(bodies));
		Interceptor recorder = chain ->
		{
			Request request = chain.request();
			String body;
			synchronized (answers)
			{
				body = answers.isEmpty() ? "{}" : answers.removeFirst();
			}
			seen.add(request);
			arrived.release();
			return new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(JSON, body))
				.build();
		};
		return client.newBuilder().addInterceptor(recorder).build();
	}

	/** The client the plugin builds in startUp, recording. */
	private OkHttpClient site(String... bodies)
	{
		return recording(SiteHttp.derive(shared, OsrsIndexPlugin.VERSION), bodies);
	}

	private void awaitRequests(int count) throws InterruptedException
	{
		assertTrue("expected " + count + " request(s)", arrived.tryAcquire(count, 10, TimeUnit.SECONDS));
	}

	@Test
	public void theSyncNamesThePluginVersion() throws Exception
	{
		site().newCall(OsrsIndexPlugin.syncRequest(TOKEN, "{\"schema\":4}")).execute().close();

		Request request = seen.get(0);
		assertEquals(Endpoints.sync(), request.url().toString());
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		assertEquals(OsrsIndexPlugin.VERSION, request.header(SiteHttp.VERSION_HEADER));
	}

	@Test
	public void theUnlinkRevokeNamesThePluginVersion() throws Exception
	{
		new TokenRevoker(site()).revoke(TOKEN, result ->
		{
		});
		awaitRequests(1);

		Request request = seen.get(0);
		assertEquals("DELETE", request.method());
		assertEquals(Endpoints.revokeToken(), request.url().toString());
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		assertEquals(OsrsIndexPlugin.VERSION, request.header(SiteHttp.VERSION_HEADER));
	}

	@Test
	public void linkStartAndEveryPollNameThePluginVersion() throws Exception
	{
		String started = "{\"device_code\":\"osrsidl_" + "b".repeat(43) + "\",\"user_code\":\"KXQ4-7MNP\","
			+ "\"expires_in\":600,\"interval\":15}";
		long[] now = {1_000_000L};
		Semaphore waiting = new Semaphore(0);
		AccountLinker linker = new AccountLinker(AccountLinker.okHttp(site(started, "{\"status\":\"pending\"}"), new Gson()),
			() -> now[0], new AccountLinker.Listener()
			{
				@Override
				public void stateChanged(AccountLinker.State state, String userCode)
				{
					if (state == AccountLinker.State.WAITING)
					{
						waiting.release();
					}
				}

				@Override
				public void linked(String token)
				{
				}
			});

		linker.start("Zezima");
		assertTrue("the start answer arrives", waiting.tryAcquire(10, TimeUnit.SECONDS));
		now[0] += 15_000L;
		linker.tick();
		awaitRequests(2);

		assertEquals(Endpoints.linkStart(), seen.get(0).url().toString());
		assertEquals(Endpoints.linkPoll(), seen.get(1).url().toString());
		for (Request request : seen)
		{
			assertEquals(OsrsIndexPlugin.VERSION, request.header(SiteHttp.VERSION_HEADER));
		}
	}

	@Test
	public void theDestinationsUpgradeNamesThePluginVersion() throws Exception
	{
		Semaphore refused = new Semaphore(0);
		new OkHttpConnector(site(), Endpoints::destinations).open(TOKEN, new DestinationSocket.Events()
		{
			@Override
			public void opened(DestinationSocket.Connection connection, String protocol)
			{
			}

			@Override
			public void text(DestinationSocket.Connection connection, String text)
			{
			}

			@Override
			public void closed(DestinationSocket.Connection connection, int code)
			{
			}

			@Override
			public void failed(DestinationSocket.Connection connection, int httpStatus, long retryAfterSeconds)
			{
				// The recorder answers 200, not 101, so OkHttp refuses the upgrade; the request is what counts.
				refused.release();
			}
		});
		awaitRequests(1);
		assertTrue("the refused upgrade is reported", refused.tryAcquire(10, TimeUnit.SECONDS));

		Request request = seen.get(0);
		assertEquals("websocket", request.header("Upgrade"));
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		assertEquals(DestinationSocket.SUBPROTOCOL, request.header("Sec-WebSocket-Protocol"));
		assertEquals(OsrsIndexPlugin.VERSION, request.header(SiteHttp.VERSION_HEADER));
	}

	@Test
	public void theSharedClientIsLeftAsItWasAndItsPoolIsShared() throws Exception
	{
		OkHttpClient site = SiteHttp.derive(shared, OsrsIndexPlugin.VERSION);
		assertTrue(shared.interceptors().isEmpty());
		assertEquals(1, site.interceptors().size());
		assertSame(shared.dispatcher(), site.dispatcher());
		assertSame(shared.connectionPool(), site.connectionPool());

		// Another plugin's request through the shared client carries no version header.
		recording(shared).newCall(new Request.Builder().url("https://example.com/").build()).execute().close();
		assertNull(seen.get(0).header(SiteHttp.VERSION_HEADER));
	}

	@Test
	public void theHeaderReplacesAnyValueAlreadyOnTheRequest() throws Exception
	{
		Request request = OsrsIndexPlugin.syncRequest(TOKEN, "{}").newBuilder()
			.header(SiteHttp.VERSION_HEADER, "9.9.9")
			.build();
		site().newCall(request).execute().close();
		assertEquals(Collections.singletonList(OsrsIndexPlugin.VERSION), seen.get(0).headers(SiteHttp.VERSION_HEADER));
	}

	@Test
	public void theVersionHasTheFormTheSiteReads()
	{
		// The site ignores a value outside this form, or over 32 characters, so a release must keep to it.
		assertTrue(OsrsIndexPlugin.VERSION, SITE_VERSION.matcher(OsrsIndexPlugin.VERSION).matches());
		assertTrue(OsrsIndexPlugin.VERSION.length() <= 32);
	}
}
