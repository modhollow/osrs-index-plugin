package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import org.junit.After;
import org.junit.Test;

public class PluginBusTest
{
	private final List<Throwable> thrown = new ArrayList<>();
	private final EventBus eventBus = new EventBus(thrown::add);
	/** Every lifecycle message, in the order it was posted. */
	private final List<PluginMessage> seen = new ArrayList<>();

	{
		// A higher priority runs first, so a reply posted from inside a member is recorded after its cause.
		eventBus.register(PluginMessage.class, message ->
		{
			if (PluginBus.NAMESPACE.equals(message.getNamespace()))
			{
				seen.add(message);
			}
		}, 100);
	}

	@After
	public void nothingWasThrownIntoTheEventBus()
	{
		assertTrue("subscribers threw: " + thrown, thrown.isEmpty());
	}

	private PluginBus member(String id, String... accepts)
	{
		PluginBus bus = new PluginBus(eventBus::post, new PluginBus.Member(id, "Name of " + id, "1.2.3",
			PluginBus.BUS_VERSION, Arrays.asList(accepts), Collections.singletonList("news")));
		eventBus.register(PluginMessage.class, bus::onPluginMessage, 0);
		return bus;
	}

	private List<String> names()
	{
		return seen.stream().map(PluginMessage::getName).collect(Collectors.toList());
	}

	private void post(String name, Map<String, Object> data)
	{
		eventBus.post(new PluginMessage(PluginBus.NAMESPACE, name, data));
	}

	private static Map<String, Object> hello(String plugin, Object bus)
	{
		Map<String, Object> data = new HashMap<>();
		data.put(PluginBus.PLUGIN, plugin);
		data.put(PluginBus.NAME, "Other");
		data.put(PluginBus.VERSION, "9.9.9");
		data.put(PluginBus.BUS, bus);
		data.put(PluginBus.ACCEPTS, Collections.singletonList("path"));
		data.put(PluginBus.POSTS, Collections.emptyList());
		return data;
	}

	@Test
	public void startSaysHelloThenDiscovers()
	{
		member("osrsindex").start();

		assertEquals(Arrays.asList("hello", "discover"), names());
		Map<String, Object> hello = seen.get(0).getData();
		assertEquals("osrsindex", hello.get(PluginBus.PLUGIN));
		assertEquals("Name of osrsindex", hello.get(PluginBus.NAME));
		assertEquals("1.2.3", hello.get(PluginBus.VERSION));
		assertEquals(1, hello.get(PluginBus.BUS));
		assertEquals(Collections.emptyList(), hello.get(PluginBus.ACCEPTS));
		assertEquals(Collections.singletonList("news"), hello.get(PluginBus.POSTS));
		assertEquals("osrsindex", seen.get(1).getData().get(PluginBus.FROM));
	}

	@Test
	public void aDiscoverFromAnotherPluginGetsExactlyOneHello()
	{
		member("osrsindex").start();
		seen.clear();

		post(PluginBus.DISCOVER, Collections.singletonMap(PluginBus.FROM, "farmrouteplanner"));

		assertEquals(Arrays.asList("discover", "hello"), names());
		assertEquals("osrsindex", seen.get(1).getData().get(PluginBus.PLUGIN));
	}

	@Test
	public void anAnonymousDiscoverIsAnsweredToo()
	{
		member("osrsindex").start();
		seen.clear();

		post(PluginBus.DISCOVER, Collections.emptyMap());

		assertEquals(Arrays.asList("discover", "hello"), names());
	}

	@Test
	public void twoMembersFindEachOtherWhicheverStartsFirst()
	{
		PluginBus index = member("osrsindex");
		PluginBus farm = member("farmrouteplanner", "path", "clear");

		index.start();
		farm.start();

		assertEquals(Collections.singleton("farmrouteplanner"), index.members().keySet());
		assertEquals(Collections.singleton("osrsindex"), farm.members().keySet());
		assertTrue(index.accepts("farmrouteplanner", "path"));
		assertFalse(index.accepts("farmrouteplanner", "transports"));
		assertFalse(index.accepts("slayerrouteplanner", "path"));

		PluginBus.Member seenFarm = index.members().get("farmrouteplanner");
		assertEquals("Name of farmrouteplanner", seenFarm.name);
		assertEquals("1.2.3", seenFarm.version);
		assertEquals(Collections.singletonList("news"), seenFarm.posts);
	}

	@Test
	public void theOtherStartOrderGivesTheSameTables()
	{
		PluginBus index = member("osrsindex");
		PluginBus farm = member("farmrouteplanner", "path");

		farm.start();
		index.start();

		assertEquals(Collections.singleton("farmrouteplanner"), index.members().keySet());
		assertEquals(Collections.singleton("osrsindex"), farm.members().keySet());
	}

	@Test
	public void aHelloIsNeverAnsweredWithAHello()
	{
		member("osrsindex").start();
		seen.clear();

		post(PluginBus.HELLO, hello("farmrouteplanner", 1));

		assertEquals(Collections.singletonList("hello"), names());
	}

	@Test
	public void stopSaysByeAndTheOthersForgetIt()
	{
		PluginBus index = member("osrsindex");
		PluginBus farm = member("farmrouteplanner");
		index.start();
		farm.start();
		seen.clear();

		farm.stop();

		assertEquals(Collections.singletonList("bye"), names());
		assertEquals("farmrouteplanner", seen.get(0).getData().get(PluginBus.PLUGIN));
		assertTrue(index.members().isEmpty());
		assertTrue(farm.members().isEmpty());
	}

	@Test
	public void aStoppedMemberIgnoresEverything()
	{
		PluginBus index = member("osrsindex");
		index.start();
		index.stop();
		seen.clear();

		post(PluginBus.DISCOVER, Collections.singletonMap(PluginBus.FROM, "farmrouteplanner"));
		post(PluginBus.HELLO, hello("farmrouteplanner", 1));

		assertEquals(Arrays.asList("discover", "hello"), names());
		assertTrue(index.members().isEmpty());
	}

	@Test
	public void stoppingTwiceSaysByeOnce()
	{
		PluginBus index = member("osrsindex");
		index.start();
		seen.clear();

		index.stop();
		index.stop();

		assertEquals(Collections.singletonList("bye"), names());
	}

	@Test
	public void refreshDropsAMemberThatCrashedWithoutBye()
	{
		PluginBus index = member("osrsindex");
		PluginBus crashing = new PluginBus(eventBus::post, new PluginBus.Member("slayerrouteplanner", "Slayer",
			"0.1.0", 1, Collections.emptyList(), Collections.emptyList()));
		EventBus.Subscriber crashingSubscriber = eventBus.register(PluginMessage.class, crashing::onPluginMessage, 0);
		index.start();
		crashing.start();
		assertTrue(index.members().containsKey("slayerrouteplanner"));

		// A crash: it stops listening and never says bye.
		eventBus.unregister(crashingSubscriber);
		index.refresh();

		assertFalse(index.members().containsKey("slayerrouteplanner"));
	}

	@Test
	public void itNeverListsItself()
	{
		PluginBus index = member("osrsindex");
		index.start();
		post(PluginBus.HELLO, hello("osrsindex", 1));

		assertTrue(index.members().isEmpty());
	}

	@Test
	public void aHelloOnAnotherBusVersionIsIgnoredAndLoggedOnce()
	{
		PluginBus index = member("osrsindex");
		index.start();

		post(PluginBus.HELLO, hello("future", 2));
		post(PluginBus.HELLO, hello("future", 2));

		assertTrue(index.members().isEmpty());
		assertEquals(1, index.warningsLogged());
	}

	@Test
	public void malformedLifecycleMessagesAreIgnoredLoggedOnceAndNeverThrown()
	{
		PluginBus index = member("osrsindex");
		index.start();

		Map<String, Object> noPlugin = hello("x", 1);
		noPlugin.remove(PluginBus.PLUGIN);
		Map<String, Object> busAsString = hello("stringbus", "1");
		Map<String, Object> badAccepts = hello("badaccepts", 1);
		badAccepts.put(PluginBus.ACCEPTS, Arrays.asList("path", 7));
		Map<String, Object> noPosts = hello("noposts", 1);
		noPosts.remove(PluginBus.POSTS);
		Map<String, Object> emptyPlugin = hello("", 1);

		for (int i = 0; i < 2; i++)
		{
			post(PluginBus.HELLO, noPlugin);
			post(PluginBus.HELLO, busAsString);
			post(PluginBus.HELLO, badAccepts);
			post(PluginBus.HELLO, noPosts);
			post(PluginBus.HELLO, emptyPlugin);
			post(PluginBus.HELLO, Collections.emptyMap());
			post(PluginBus.BYE, Collections.emptyMap());
		}

		assertTrue(index.members().isEmpty());
		// noPlugin and the empty hello share one key (no sender); the empty bye has its own. Each once.
		assertEquals(6, index.warningsLogged());
	}

	@Test
	public void unknownKeysAndUnknownNamesAreIgnored()
	{
		PluginBus index = member("osrsindex");
		index.start();

		Map<String, Object> withExtra = hello("farmrouteplanner", 1L);
		withExtra.put("colour", "green");
		post(PluginBus.HELLO, withExtra);
		post("ping", Collections.emptyMap());

		assertTrue(index.members().containsKey("farmrouteplanner"));
		assertEquals(0, index.warningsLogged());
	}

	@Test
	public void messagesOnOtherNamespacesAreNotTheBusBusiness()
	{
		PluginBus index = member("osrsindex");
		index.start();
		seen.clear();

		eventBus.post(new PluginMessage("farmrouteplanner", PluginBus.DISCOVER, Collections.emptyMap()));
		eventBus.post(new PluginMessage("osrsindex", PluginBus.HELLO, hello("sneaky", 1)));

		assertTrue(seen.isEmpty());
		assertTrue(index.members().isEmpty());
	}

	@Test
	public void theDataItPostsIsReadOnly()
	{
		member("osrsindex").start();

		for (PluginMessage message : seen)
		{
			try
			{
				message.getData().put("x", "y");
				fail(message.getName() + " data was writable");
			}
			catch (UnsupportedOperationException expected)
			{
				// Rule 4.
			}
		}
		try
		{
			@SuppressWarnings("unchecked")
			List<String> posts = (List<String>) seen.get(0).getData().get(PluginBus.POSTS);
			posts.add("x");
			fail("posts was writable");
		}
		catch (UnsupportedOperationException expected)
		{
			// Rule 4.
		}
	}

	@Test
	public void theMembersSnapshotDoesNotChangeUnderTheCaller()
	{
		PluginBus index = member("osrsindex");
		index.start();
		post(PluginBus.HELLO, hello("farmrouteplanner", 1));

		Map<String, PluginBus.Member> snapshot = index.members();
		post(PluginBus.BYE, Collections.singletonMap(PluginBus.PLUGIN, "farmrouteplanner"));

		assertTrue(snapshot.containsKey("farmrouteplanner"));
		assertNull(index.members().get("farmrouteplanner"));
	}

	@Test
	public void theVersionItsHelloCarriesMatchesBuildGradle() throws Exception
	{
		String gradle = new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8);
		Matcher version = Pattern.compile("(?m)^version = '([^']+)'").matcher(gradle);
		assertTrue(version.find());
		assertEquals(version.group(1), OsrsIndexPlugin.VERSION);
	}
}
