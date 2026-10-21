package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.util.regex.Matcher;
import org.junit.After;
import org.junit.Test;

/** Limits on what the site, a relay or a damaged profile can put in front of the player or into a sync. */
public class SecurityLimitsTest
{
	@After
	public void clearOverrides()
	{
		System.clearProperty("osrsindex.origin");
		System.clearProperty("osrsindex.relay");
	}

	@Test
	public void aRefusalCodeShowsOnlyAsAPlainWord()
	{
		assertEquals("invalid_location", SyncOutcome.plainCode("invalid_location"));
		assertEquals("unknown", SyncOutcome.plainCode("<col=ff0000>admin<img=1>"));
		assertEquals("unknown", SyncOutcome.plainCode("invalid\nforged log line"));
		assertEquals("unknown", SyncOutcome.plainCode(null));
	}

	@Test
	public void anOriginOverrideMustBeEncryptedOrThisComputer()
	{
		System.setProperty("osrsindex.origin", "http://example.com");
		assertEquals(Endpoints.DEFAULT_ORIGIN, Endpoints.origin());
		System.setProperty("osrsindex.origin", "javascript:alert(1)");
		assertEquals(Endpoints.DEFAULT_ORIGIN, Endpoints.origin());
		System.setProperty("osrsindex.origin", "http://localhost:5173/");
		assertEquals("http://localhost:5173", Endpoints.origin());
		System.setProperty("osrsindex.origin", "https://staging.example.com");
		assertEquals("https://staging.example.com", Endpoints.origin());
		System.setProperty("osrsindex.relay", "ws://relay.example.com");
		assertEquals(Endpoints.DEFAULT_RELAY + "/v1/destinations", Endpoints.destinations());
		System.setProperty("osrsindex.relay", "ws://127.0.0.1:8787");
		assertEquals("ws://127.0.0.1:8787/v1/destinations", Endpoints.destinations());
	}

	@Test
	public void aSavedStoreKeepsOnlyItemsAsTheReaderWritesThem()
	{
		JsonArray saved = new JsonParser().parse("[{\"id\":4151,\"quantity\":1},{\"id\":-1,\"quantity\":1},"
			+ "{\"id\":995,\"quantity\":0},{\"id\":1.5,\"quantity\":2},{\"id\":\"995\",\"quantity\":2},"
			+ "{\"id\":995,\"quantity\":2,\"extra\":\"<script>\"},\"text\",{\"id\":300000,\"quantity\":1}]")
			.getAsJsonArray();
		assertEquals("[{\"id\":4151,\"quantity\":1},{\"id\":995,\"quantity\":2}]",
			StorageTracker.items(saved).toString());
	}

	@Test
	public void aTooltipFromTheSiteShowsAsText()
	{
		assertEquals("<html>&lt;html&gt;&lt;img src='https://example.com/p.png'&gt;</html>",
			GuidesPanel.tip("<html><img src='https://example.com/p.png'>"));
		assertNull(GuidesPanel.tip(""));
		assertNull(GuidesPanel.tip(null));
	}

	@Test
	public void aKillCountTooLongForANumberIsNotOne()
	{
		// Before, Integer.parseInt threw on every tick for such a title, and stopped every sync.
		Matcher kill = GuideSigns.KILL.matcher("kill 99999999999 goblins");
		assertTrue(kill.matches());
		assertNull("too long to be a count, so it is part of the name", kill.group(1));
		Matcher count = GuideSigns.KILL.matcher("kill 25 goblins");
		assertTrue(count.matches());
		assertEquals("25", count.group(1));
	}
}
