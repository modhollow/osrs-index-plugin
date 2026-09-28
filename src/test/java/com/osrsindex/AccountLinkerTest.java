package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Linking with one click: the approval page the panel opens, and the early poll
 * when the player comes back from the browser, kept inside the site's 5-a-minute limit.
 */
public class AccountLinkerTest
{
	private static final String CODE = "KXQ4-7MNP";

	private long now = 1_000_000L;
	private final List<String> urls = new ArrayList<>();
	private final List<Long> requestTimes = new ArrayList<>();
	private final List<AccountLinker.Handler> pending = new ArrayList<>();
	private final List<AccountLinker.State> states = new ArrayList<>();
	private final List<String> tokens = new ArrayList<>();

	private final AccountLinker linker = new AccountLinker(
		(url, body, handler) ->
		{
			urls.add(url);
			requestTimes.add(now);
			pending.add(handler);
		},
		() -> now,
		new AccountLinker.Listener()
		{
			@Override
			public void stateChanged(AccountLinker.State state, String userCode)
			{
				states.add(state);
			}

			@Override
			public void linked(String token)
			{
				tokens.add(token);
			}
		});

	private static JsonObject started(String complete)
	{
		JsonObject json = new JsonObject();
		json.addProperty("device_code", "osrsidl_" + "a".repeat(43));
		json.addProperty("user_code", CODE);
		json.addProperty("verification_url", Endpoints.origin() + "/link");
		if (complete != null)
		{
			json.addProperty("verification_url_complete", complete);
		}
		json.addProperty("expires_in", 600);
		json.addProperty("interval", 15);
		return json;
	}

	private static JsonObject status(String status)
	{
		JsonObject json = new JsonObject();
		json.addProperty("status", status);
		return json;
	}

	/** Answer the oldest request. */
	private void answer(int status, JsonObject json)
	{
		pending.remove(0).handle(status, json);
	}

	private void startWaiting(String complete)
	{
		linker.start("Zezima");
		answer(200, started(complete));
		assertEquals(AccountLinker.State.WAITING, linker.state());
	}

	@Test
	public void opensTheSitesCompleteAddressForTheCode()
	{
		String complete = Endpoints.origin() + "/link?code=" + CODE;
		startWaiting(complete);
		assertEquals(complete, linker.approvalUrl());
		assertEquals(CODE, linker.userCode());
	}

	@Test
	public void buildsTheAddressWhenASiteFromBefore741SendsNone()
	{
		startWaiting(null);
		assertEquals("https://osrsindex.com/link?code=KXQ4-7MNP", linker.approvalUrl());
	}

	@Test
	public void neverOpensAnAddressOnAnotherOrigin()
	{
		startWaiting("https://evil.example/link?code=" + CODE);
		assertEquals(Endpoints.linkApproval(CODE), linker.approvalUrl());
		startWaiting("https://osrsindex.com.evil.example/link");
		assertEquals(Endpoints.linkApproval(CODE), linker.approvalUrl());
	}

	@Test
	public void hasNoAddressUnlessACodeIsWaiting()
	{
		assertNull(linker.approvalUrl());
		linker.start("Zezima");
		assertNull(linker.approvalUrl());
		answer(429, null);
		assertEquals(AccountLinker.State.EXPIRED, linker.state());
		assertNull(linker.approvalUrl());
	}

	@Test
	public void pollsAtOnceWhenThePlayerComesBackAndLinks()
	{
		startWaiting(null);
		now += 8_000;
		linker.tick();
		assertEquals(1, urls.size()); // only the start: the first poll is due at 15 s
		linker.pollSoon();
		linker.tick();
		assertEquals(Endpoints.linkPoll(), urls.get(1));
		answer(200, status("pending"));
		assertTrue(tokens.isEmpty());

		JsonObject approved = status("approved");
		approved.addProperty("token", OsrsIndexPlugin.TOKEN_PREFIX + "b".repeat(43));
		now += 15_000;
		linker.tick();
		answer(200, approved);
		assertEquals(1, tokens.size());
		assertEquals(AccountLinker.State.IDLE, linker.state());
		assertNull(linker.approvalUrl());
	}

	@Test
	public void theEarlyPollWaitsFiveSecondsAfterTheLastRequest()
	{
		startWaiting(null);
		now += AccountLinker.EARLY_POLL_GAP_MILLIS - 1;
		linker.pollSoon();
		linker.tick();
		assertEquals(1, urls.size());
		now += 1;
		linker.pollSoon();
		linker.tick();
		assertEquals(2, urls.size());
	}

	@Test
	public void checkingTheCodeInTheClientDoesNotUseUpTheEarlyPoll()
	{
		// Switching back to compare the code must not spend the only early poll, or the link itself waits
		// out the 15 s interval. Every return polls, within the limit.
		startWaiting(null);
		now += 6_000; // back in RuneLite to compare the code
		linker.pollSoon();
		linker.tick();
		answer(200, status("pending"));

		JsonObject approved = status("approved");
		approved.addProperty("token", OsrsIndexPlugin.TOKEN_PREFIX + "c".repeat(43));
		now += 6_000; // approved in the browser, and back again
		linker.pollSoon();
		linker.tick();
		assertEquals(3, urls.size());
		answer(200, approved);
		assertEquals(1, tokens.size());
	}

	@Test
	public void earlyPollsLeaveTheScheduledOneASlot()
	{
		long start = now;
		startWaiting(null); // request 1, at 0 s
		for (int i = 1; i <= 3; i++) // returns at 5, 10 and 15 s
		{
			now = start + i * AccountLinker.EARLY_POLL_GAP_MILLIS;
			linker.pollSoon();
			linker.tick();
			while (!pending.isEmpty())
			{
				answer(200, status("pending"));
			}
		}
		// Early polls took 2 of the minute's 4, then stopped: the third return was dropped.
		assertEquals(AccountLinker.MAX_REQUESTS_PER_MINUTE - 1, urls.size());

		// The scheduled poll, 15 s after the last one (at 10 s), still gets the fourth slot.
		now = start + 25_000;
		linker.tick();
		assertEquals(AccountLinker.MAX_REQUESTS_PER_MINUTE, urls.size());
		answer(200, status("pending"));

		// The next is due at 40 s, but the minute is full until the start is 60 s old.
		now = start + 40_000;
		linker.tick();
		assertEquals(AccountLinker.MAX_REQUESTS_PER_MINUTE, urls.size());
		now = start + 60_000;
		linker.tick();
		assertEquals(AccountLinker.MAX_REQUESTS_PER_MINUTE + 1, urls.size());
	}

	@Test
	public void focusingOverAndOverNeverPassesFourRequestsAMinute()
	{
		// The site counts link requests per address, 5 a minute; the plugin keeps to 4. Focus the client
		// every second for the whole life of a code, and check every 60 s window.
		startWaiting(null);
		long end = now + 600_000;
		while (now < end)
		{
			now += 1_000;
			linker.pollSoon();
			linker.tick();
			while (!pending.isEmpty())
			{
				answer(200, status("pending"));
			}
		}
		for (long start : requestTimes)
		{
			long inWindow = requestTimes.stream().filter(t -> t >= start && t < start + 60_000).count();
			assertTrue("requests in the minute from " + start + ": " + inWindow,
				inWindow <= AccountLinker.MAX_REQUESTS_PER_MINUTE);
		}
	}
}
