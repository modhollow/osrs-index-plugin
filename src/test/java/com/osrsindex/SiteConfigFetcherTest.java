package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

/**
 * When the plugin asks osrsindex.com for its config, and what each answer leaves it with (docs/site-config.md),
 * with a fake transport and clock.
 */
public class SiteConfigFetcherTest
{
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "a".repeat(43);
	private static final String OTHER_TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "b".repeat(43);

	private long now = 1_000_000L;
	private final List<String> urls = new ArrayList<>();
	private final List<String> tokens = new ArrayList<>();
	private final List<SiteConfigFetcher.Handler> pending = new ArrayList<>();
	private final List<SiteConfig> delivered = new ArrayList<>();

	private final SiteConfigFetcher fetcher = new SiteConfigFetcher((url, token, handler) ->
	{
		urls.add(url);
		tokens.add(token);
		pending.add(handler);
	}, () -> now, delivered::add);

	private void answer(int status, String body)
	{
		pending.remove(0).handle(status, body);
	}

	@Test
	public void asksNothingWithoutAToken()
	{
		fetcher.tick("");
		fetcher.tick(null);
		fetcher.tick("not-a-site-token");
		assertTrue(urls.isEmpty());
		assertFalse(fetcher.awaitingFirstAnswer());
	}

	@Test
	public void asksTheConfigRouteWithTheTokenOnTheFirstTick()
	{
		fetcher.tick(TOKEN);
		assertEquals(List.of("https://osrsindex.com/api/v1/tracker/plugin-config"), urls);
		assertEquals(List.of(TOKEN), tokens);
		fetcher.tick(TOKEN);
		assertEquals("one ask at a time", 1, urls.size());
	}

	@Test
	public void aConfigIsUsedAndAskedForAgainAfterItsRefresh()
	{
		fetcher.tick(TOKEN);
		answer(200, "{\"version\":3,\"refresh_seconds\":600,\"parts\":{\"bank\":false}}");
		assertEquals(1, delivered.size());
		assertEquals(3, fetcher.current().version);
		assertFalse(fetcher.current().allows(Part.BANK));

		now += 599_000;
		fetcher.tick(TOKEN);
		assertEquals(1, urls.size());
		now += 1_000;
		fetcher.tick(TOKEN);
		assertEquals(2, urls.size());
	}

	@Test
	public void aSiteWithoutTheRouteMeansTheBuiltInConfigAndAnotherAskInAnHour()
	{
		fetcher.tick(TOKEN);
		answer(404, "");
		assertSame(SiteConfig.DEFAULTS, fetcher.current());
		now += SiteConfigFetcher.NOT_SERVED_RETRY_MILLIS - 1;
		fetcher.tick(TOKEN);
		assertEquals(1, urls.size());
		now += 1;
		fetcher.tick(TOKEN);
		assertEquals(2, urls.size());
	}

	@Test
	public void anErrorOrNoAnswerKeepsTheConfigItHasAndAsksAgainInFiveMinutes()
	{
		fetcher.tick(TOKEN);
		answer(200, "{\"version\":4}");
		SiteConfig kept = fetcher.current();
		for (int status : new int[]{401, 429, 500, 503, -1, 200})
		{
			now += SiteConfig.DEFAULT_REFRESH_SECONDS * 1_000L;
			fetcher.tick(TOKEN);
			// The 200 is a proxy's or a fallback route's HTML page: not a config, so no answer.
			answer(status, "<html>error</html>");
			assertSame("status " + status, kept, fetcher.current());
			now += SiteConfigFetcher.RETRY_MILLIS - 1;
			int asked = urls.size();
			fetcher.tick(TOKEN);
			assertEquals(asked, urls.size());
			now += 1;
		}
	}

	@Test
	public void aNewOrClearedTokenGoesBackToTheBuiltInConfigAtOnce()
	{
		fetcher.tick(TOKEN);
		answer(200, "{\"version\":5,\"destinations\":false}");
		assertFalse(fetcher.current().destinations);

		fetcher.tick(OTHER_TOKEN);
		assertSame(SiteConfig.DEFAULTS, fetcher.current());
		assertSame(SiteConfig.DEFAULTS, delivered.get(delivered.size() - 1));
		assertEquals("the new token asks at once", List.of(TOKEN, OTHER_TOKEN), tokens);

		answer(200, "{\"version\":6}");
		fetcher.tick("");
		assertSame(SiteConfig.DEFAULTS, fetcher.current());
		assertEquals(2, urls.size());
	}

	@Test
	public void anAnswerForAnOldTokenIsIgnored()
	{
		fetcher.tick(TOKEN);
		SiteConfigFetcher.Handler late = pending.remove(0);
		fetcher.tick(OTHER_TOKEN);
		late.handle(200, "{\"destinations\":false}");
		assertTrue(fetcher.current().destinations);
	}

	@Test
	public void aNewerVersionNamedWhileAnAskIsOutIsAskedForAfterIt()
	{
		fetcher.tick(TOKEN);
		fetcher.changed(8);
		answer(200, "{\"version\":7}");
		now += SiteConfigFetcher.SOONEST_REFRESH_MILLIS;
		fetcher.tick(TOKEN);
		assertEquals(2, urls.size());

		answer(200, "{\"version\":7}");
		fetcher.changed(8);
		now += SiteConfig.DEFAULT_REFRESH_SECONDS * 1_000L - 1;
		fetcher.tick(TOKEN);
		assertEquals("a site still serving 7 is not asked every minute", 2, urls.size());
	}

	@Test
	public void aSyncNamingANewerConfigAsksAgainButAtMostOnceAMinute()
	{
		fetcher.tick(TOKEN);
		answer(200, "{\"version\":2}");
		fetcher.changed(2);
		now += SiteConfigFetcher.SOONEST_REFRESH_MILLIS;
		fetcher.tick(TOKEN);
		assertEquals("the same version asks nothing", 1, urls.size());

		fetcher.changed(3);
		fetcher.tick(TOKEN);
		assertEquals(2, urls.size());
		answer(200, "{\"version\":3}");

		fetcher.changed(4);
		now += SiteConfigFetcher.SOONEST_REFRESH_MILLIS - 1;
		fetcher.tick(TOKEN);
		assertEquals("not within a minute of the last ask", 2, urls.size());
		now += 1;
		fetcher.tick(TOKEN);
		assertEquals(3, urls.size());
		answer(200, "{\"version\":3}");

		fetcher.changed(4);
		now += SiteConfigFetcher.SOONEST_REFRESH_MILLIS;
		fetcher.tick(TOKEN);
		assertEquals("the same version named again asks nothing more", 3, urls.size());
	}

	@Test
	public void syncsWaitForAConfigOrA404ButNotForLong()
	{
		fetcher.tick(TOKEN);
		assertTrue(fetcher.awaitingFirstAnswer());
		answer(503, "");
		assertTrue("an error does not end the wait, so a paused part does not go out", fetcher.awaitingFirstAnswer());
		now += SiteConfigFetcher.FIRST_ANSWER_WAIT_MILLIS;
		assertFalse("thirty seconds is the longest syncs wait", fetcher.awaitingFirstAnswer());

		fetcher.tick(OTHER_TOKEN);
		assertTrue(fetcher.awaitingFirstAnswer());
		answer(404, "");
		assertFalse("a site without the route has answered", fetcher.awaitingFirstAnswer());
	}

	@Test
	public void aConfigEndsTheWait()
	{
		fetcher.tick(TOKEN);
		answer(200, "{}");
		assertFalse(fetcher.awaitingFirstAnswer());
	}

	@Test
	public void theRequestIsAGetWithTheBearerAndThePluginVersion() throws Exception
	{
		BlockingQueue<Request> seen = new ArrayBlockingQueue<>(1);
		// After the plugin's own interceptor, so the request is seen as it would leave.
		OkHttpClient offline = SiteHttp.derive(new OkHttpClient(), OsrsIndexPlugin.VERSION).newBuilder()
			.addInterceptor(chain ->
			{
				seen.add(chain.request());
				return new Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(ResponseBody.create(MediaType.parse("application/json"), "{\"version\":9}"))
					.build();
			})
			.build();
		BlockingQueue<SiteConfig> configs = new ArrayBlockingQueue<>(1);
		SiteConfigFetcher real = new SiteConfigFetcher(offline, configs::add);
		real.tick(TOKEN);

		Request request = seen.poll(5, TimeUnit.SECONDS);
		assertEquals("GET", request.method());
		assertEquals("https://osrsindex.com/api/v1/tracker/plugin-config", request.url().toString());
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		assertEquals(OsrsIndexPlugin.VERSION, request.header(SiteHttp.VERSION_HEADER));
		SiteConfig config = configs.poll(5, TimeUnit.SECONDS);
		assertEquals(9, config.version);
	}

	@Test
	public void aBodyFarTooLargeToBeAConfigCountsAsNoAnswer() throws IOException, InterruptedException
	{
		String huge = "{\"x\":\"" + "a".repeat(SiteConfigFetcher.MAX_BODY_BYTES) + "\"}";
		OkHttpClient offline = new OkHttpClient.Builder()
			.addInterceptor(chain -> new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), huge))
				.build())
			.build();
		BlockingQueue<int[]> statuses = new ArrayBlockingQueue<>(1);
		SiteConfigFetcher.okHttp(offline).get(Endpoints.pluginConfig(), TOKEN,
			(status, body) -> statuses.add(new int[]{status, body.length()}));
		int[] answered = statuses.poll(5, TimeUnit.SECONDS);
		assertEquals(-1, answered[0]);
		assertEquals(0, answered[1]);
	}

	@Test
	public void aBodyWithinTheLimitIsReadWhole() throws IOException
	{
		String body = "{\"version\":1,\"x\":\"" + "\u00e9".repeat(1_000) + "\"}";
		assertEquals(body, SiteConfigFetcher.bounded(ResponseBody.create(MediaType.parse("application/json"), body)));
	}
}
