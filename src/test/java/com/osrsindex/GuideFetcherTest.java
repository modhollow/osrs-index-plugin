package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Guide requests: only when asked, the list kept an hour, a guide kept until its revision changes,
 * {@code If-None-Match} on a refresh, and what each answer means. A recording transport stands in for the network.
 */
public class GuideFetcherTest
{
	private final long[] now = {1_000_000L};
	private final List<String[]> asked = new ArrayList<>();
	private final List<GuideFetcher.Handler> pending = new ArrayList<>();
	private final GuideFetcher fetcher = new GuideFetcher((url, etag, handler) ->
	{
		asked.add(new String[]{url, etag});
		pending.add(handler);
	}, () -> now[0]);

	private final List<GuideFetcher.Answer<Guide.Listing>> listings = new ArrayList<>();
	private final List<GuideFetcher.Answer<Guide>> guides = new ArrayList<>();

	private void answer(int status, String body, String etag)
	{
		pending.remove(0).handle(status, body, etag);
	}

	@Test
	public void nothingIsAskedUntilThePlayerOpensGuides()
	{
		assertTrue(asked.isEmpty());
		fetcher.listing(listings::add);
		assertEquals(1, asked.size());
		assertEquals(Endpoints.guides(), asked.get(0)[0]);
		assertNull(asked.get(0)[1]);
	}

	@Test
	public void theListIsKeptForAnHourThenAskedAgainWithItsEtag()
	{
		fetcher.listing(listings::add);
		answer(200, GuideFixtures.read(GuideFixtures.LISTING), "\"list-1\"");
		assertSame(GuideFetcher.Status.OK, listings.get(0).status);
		assertEquals(2, listings.get(0).value.guides.size());

		now[0] += GuideFetcher.LIST_MAX_AGE_MILLIS - 1;
		fetcher.listing(listings::add);
		assertEquals("answered from memory", 1, asked.size());
		assertSame(listings.get(0).value, listings.get(1).value);

		now[0] += 1;
		fetcher.listing(listings::add);
		assertEquals(2, asked.size());
		assertEquals("\"list-1\"", asked.get(1)[1]);
		answer(304, "", null);
		assertSame("unchanged", listings.get(0).value, listings.get(2).value);
	}

	@Test
	public void twoOpensWhileAskingShareOneRequest()
	{
		fetcher.listing(listings::add);
		fetcher.listing(listings::add);
		assertEquals(1, asked.size());
		answer(200, GuideFixtures.read(GuideFixtures.LISTING), null);
		assertEquals(2, listings.size());
	}

	@Test
	public void aSiteWithoutTheRoutesIsNotServed()
	{
		fetcher.listing(listings::add);
		answer(404, "<html>", null);
		assertSame(GuideFetcher.Status.NOT_SERVED, listings.get(0).status);
		now[0] += 60_000;
		fetcher.listing(listings::add);
		assertEquals("not asked again at once", 1, asked.size());
		fetcher.forgetFailures();
		fetcher.listing(listings::add);
		assertEquals("Try again asks", 2, asked.size());
	}

	@Test
	public void anUnknownVersionAsksForAnUpdate()
	{
		fetcher.listing(listings::add);
		answer(200, "{\"version\":2,\"guides\":[]}", null);
		assertSame(GuideFetcher.Status.UPDATE_PLUGIN, listings.get(0).status);
		assertNull(listings.get(0).value);
	}

	@Test
	public void aFailedRefreshKeepsTheListItHas()
	{
		fetcher.listing(listings::add);
		answer(200, GuideFixtures.read(GuideFixtures.LISTING), "\"e\"");
		now[0] += GuideFetcher.LIST_MAX_AGE_MILLIS;
		fetcher.listing(listings::add);
		answer(503, "", null);
		assertSame(GuideFetcher.Status.OK, listings.get(1).status);
		assertSame(listings.get(0).value, listings.get(1).value);
		fetcher.listing(listings::add);
		assertEquals("asked again on the next open, after a short wait", 2, asked.size());
		now[0] += GuideFetcher.RETRY_MILLIS;
		fetcher.listing(listings::add);
		assertEquals(3, asked.size());
	}

	@Test
	public void aGuideIsKeptUntilItsRevisionChanges()
	{
		fetcher.guide("beginner", "3f2a9c1e", guides::add);
		assertEquals(Endpoints.guide("beginner"), asked.get(0)[0]);
		answer(200, GuideFixtures.read(GuideFixtures.GUIDE), "\"g-1\"");
		assertSame(GuideFetcher.Status.OK, guides.get(0).status);

		fetcher.guide("beginner", "3f2a9c1e", guides::add);
		fetcher.guide("beginner", null, guides::add);
		assertEquals("the same revision, from memory", 1, asked.size());

		fetcher.guide("beginner", "new-revision", guides::add);
		assertEquals(2, asked.size());
		assertEquals("\"g-1\"", asked.get(1)[1]);
		answer(304, "", null);
		assertSame(guides.get(0).value, guides.get(3).value);
	}

	@Test
	public void aGuideTheSiteNoLongerOffersOrThatIsNotTheOneAskedFor()
	{
		fetcher.guide("gone", null, guides::add);
		answer(404, "{\"code\":\"unknown_guide\"}", null);
		assertSame(GuideFetcher.Status.NOT_SERVED, guides.get(0).status);

		fetcher.guide("other", null, guides::add);
		answer(200, GuideFixtures.read(GuideFixtures.GUIDE), null);
		assertSame("the body is another guide", GuideFetcher.Status.FAILED, guides.get(1).status);

		fetcher.guide("other", null, guides::add);
		assertEquals("a failure is not asked again at once", 2, asked.size());
		assertSame(GuideFetcher.Status.FAILED, guides.get(2).status);
		fetcher.forgetFailures();
		fetcher.guide("other", null, guides::add);
		assertEquals(3, asked.size());
	}

	@Test
	public void theRequestCarriesNoTokenAndAsksForJson()
	{
		okhttp3.Request request = GuideFetcher.request(Endpoints.guide("beginner"), "\"g-1\"");
		assertNull(request.header("Authorization"));
		assertEquals("application/json", request.header("Accept"));
		assertEquals("\"g-1\"", request.header("If-None-Match"));
		assertNull(GuideFetcher.request(Endpoints.guides(), null).header("If-None-Match"));
		assertEquals("GET", request.method());
	}
}
