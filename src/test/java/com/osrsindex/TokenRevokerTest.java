package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

/**
 * Unlink revoking the token on the site: what each answer means and what chat says, with a fake transport,
 * and the request itself, through an OkHttp client that never reaches the network.
 */
public class TokenRevokerTest
{
	private static final String TOKEN = OsrsIndexPlugin.TOKEN_PREFIX + "a".repeat(43);

	private final List<String> urls = new ArrayList<>();
	private final List<String> tokens = new ArrayList<>();
	private final List<IntConsumer> pending = new ArrayList<>();
	private final List<TokenRevoker.Result> results = new ArrayList<>();

	private final TokenRevoker revoker = new TokenRevoker((url, token, status) ->
	{
		urls.add(url);
		tokens.add(token);
		pending.add(status);
	});

	/** Revoke, answer with {@code status}, and return what came of it. */
	private TokenRevoker.Result revokeAnswering(int status)
	{
		revoker.revoke(TOKEN, results::add);
		pending.remove(0).accept(status);
		return results.get(results.size() - 1);
	}

	@Test
	public void sendsTheTokenToTheSitesRevokeRouteOnce()
	{
		revoker.revoke(TOKEN, results::add);
		assertEquals(List.of("https://osrsindex.com/api/v1/tracker/token"), urls);
		assertEquals(List.of(TOKEN), tokens);
		assertTrue("nothing is decided before the site answers", results.isEmpty());
	}

	@Test
	public void aRevokeTheSiteConfirmedSaysSo()
	{
		assertEquals(TokenRevoker.Result.CONFIRMED, revokeAnswering(204));
		assertEquals("osrsindex.com confirmed that this client's link is revoked.",
			TokenRevoker.chatLine(TokenRevoker.Result.CONFIRMED));
	}

	@Test
	public void aTokenTheSiteNoLongerKnowsIsAlsoConfirmed()
	{
		// 401: unknown, or already revoked on the account page. Either way it no longer works.
		assertEquals(TokenRevoker.Result.CONFIRMED, revokeAnswering(401));
	}

	@Test
	public void anyOtherAnswerIsNotConfirmedAndIsNeverRetried()
	{
		// 404 is a site without the route; 429 its per-address limit; 503 no tracker storage.
		for (int status : new int[]{404, 405, 429, 500, 503, 200, 400})
		{
			assertEquals("status " + status, TokenRevoker.Result.NOT_CONFIRMED, revokeAnswering(status));
		}
		assertEquals("one request per revoke, none sent again", 7, urls.size());
		assertTrue(pending.isEmpty());
	}

	@Test
	public void noResponseIsNotConfirmedEither()
	{
		assertEquals(TokenRevoker.Result.NO_RESPONSE, revokeAnswering(-1));
		assertEquals(1, urls.size());
	}

	@Test
	public void chatTellsThePlayerToRevokeOnTheSiteWhenItWasNotConfirmed()
	{
		String advice = "Revoke this client on your osrsindex.com account page to be sure.";
		assertEquals("osrsindex.com did not confirm that this client's link is revoked. " + advice,
			TokenRevoker.chatLine(TokenRevoker.Result.NOT_CONFIRMED));
		assertEquals("osrsindex.com could not be reached to revoke this client's link. " + advice,
			TokenRevoker.chatLine(TokenRevoker.Result.NO_RESPONSE));
	}

	@Test
	public void theRequestIsADeleteWithTheBearerTokenAndNoBody() throws Exception
	{
		BlockingQueue<Request> sent = new ArrayBlockingQueue<>(1);
		OkHttpClient http = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				sent.add(chain.request());
				return answer(chain.request(), 204);
			})
			.build();
		BlockingQueue<TokenRevoker.Result> answered = new ArrayBlockingQueue<>(1);

		new TokenRevoker(http).revoke(TOKEN, answered::add);

		assertEquals(TokenRevoker.Result.CONFIRMED, answered.poll(10, TimeUnit.SECONDS));
		Request request = sent.poll(10, TimeUnit.SECONDS);
		assertNotNull(request);
		assertEquals("DELETE", request.method());
		assertEquals("https://osrsindex.com/api/v1/tracker/token", request.url().toString());
		assertEquals("Bearer " + TOKEN, request.header("Authorization"));
		assertTrue("no body", request.body() == null || request.body().contentLength() == 0);
	}

	@Test
	public void aRequestThatNeverGetsAnAnswerIsNoResponse() throws Exception
	{
		OkHttpClient http = new OkHttpClient.Builder()
			.addInterceptor(chain ->
			{
				throw new IOException("offline");
			})
			.build();
		BlockingQueue<TokenRevoker.Result> answered = new ArrayBlockingQueue<>(1);

		new TokenRevoker(http).revoke(TOKEN, answered::add);

		assertEquals(TokenRevoker.Result.NO_RESPONSE, answered.poll(10, TimeUnit.SECONDS));
	}

	@Test
	public void aRefusalThroughOkHttpIsNotConfirmed() throws Exception
	{
		OkHttpClient http = new OkHttpClient.Builder()
			.addInterceptor(chain -> answer(chain.request(), 429))
			.build();
		BlockingQueue<TokenRevoker.Result> answered = new ArrayBlockingQueue<>(1);

		new TokenRevoker(http).revoke(TOKEN, answered::add);

		assertEquals(TokenRevoker.Result.NOT_CONFIRMED, answered.poll(10, TimeUnit.SECONDS));
	}

	private static Response answer(Request request, int code)
	{
		String body = code == 204 ? "" : "{\"code\":\"rate_limited\"}";
		return new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message("test")
			.body(ResponseBody.create(MediaType.parse("application/json"), body))
			.build();
	}
}
