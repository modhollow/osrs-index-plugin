package com.osrsindex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.runelite.client.events.PluginMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This plugin's membership of the plugin bus (docs/plugin-bus.md): it says {@code hello} at start-up and
 * {@code bye} at shutdown, answers {@code discover}, and keeps a table of the other members present.
 *
 * <p>The EventBus is synchronous, so {@link #refresh()} rebuilds the table completely before it returns.
 * Messages may arrive on any thread; the table is a concurrent map.
 */
final class PluginBus
{
	private static final Logger log = LoggerFactory.getLogger(PluginBus.class);

	static final String NAMESPACE = "osrsindex.bus";
	static final int BUS_VERSION = 1;

	static final String HELLO = "hello";
	static final String DISCOVER = "discover";
	static final String BYE = "bye";

	static final String PLUGIN = "plugin";
	static final String NAME = "name";
	static final String VERSION = "version";
	static final String BUS = "bus";
	static final String ACCEPTS = "accepts";
	static final String POSTS = "posts";
	static final String FROM = "from";

	/** One plugin on the bus, as its {@code hello} described it. */
	static final class Member
	{
		final String plugin;
		final String name;
		final String version;
		final int bus;
		final List<String> accepts;
		final List<String> posts;

		Member(String plugin, String name, String version, int bus, List<String> accepts, List<String> posts)
		{
			this.plugin = plugin;
			this.name = name;
			this.version = version;
			this.bus = bus;
			this.accepts = Collections.unmodifiableList(new ArrayList<>(accepts));
			this.posts = Collections.unmodifiableList(new ArrayList<>(posts));
		}
	}

	private final Consumer<PluginMessage> post;
	private final Member self;
	private final Map<String, Member> members = new ConcurrentHashMap<>();
	private final Set<String> warned = ConcurrentHashMap.newKeySet();
	private volatile boolean running;

	PluginBus(Consumer<PluginMessage> post, Member self)
	{
		this.post = post;
		this.self = self;
	}

	/** Say {@code hello}, so the members present learn of this one, then {@code discover} them. */
	void start()
	{
		members.clear();
		running = true;
		post.accept(hello());
		post.accept(discover());
	}

	/** Say {@code bye} and forget everyone. Messages after this are ignored. */
	void stop()
	{
		if (!running)
		{
			return;
		}
		running = false;
		members.clear();
		post.accept(message(BYE, Collections.singletonMap(PLUGIN, self.plugin)));
	}

	/** Forget everyone and ask again: drops a member that crashed without saying {@code bye}. */
	void refresh()
	{
		if (!running)
		{
			return;
		}
		members.clear();
		post.accept(discover());
	}

	/** The other members present, by plugin id. A snapshot: later messages do not change it. */
	Map<String, Member> members()
	{
		return Collections.unmodifiableMap(new HashMap<>(members));
	}

	/** Whether {@code plugin} is present and says it accepts the command {@code name}. */
	boolean accepts(String plugin, String name)
	{
		Member member = members.get(plugin);
		return member != null && member.accepts.contains(name);
	}

	/** How many distinct warnings were logged: each malformed sender is logged once. */
	int warningsLogged()
	{
		return warned.size();
	}

	void onPluginMessage(PluginMessage message)
	{
		if (!running || !NAMESPACE.equals(message.getNamespace()))
		{
			return;
		}
		// PluginMessage refuses null data, so no null check is needed here.
		Map<String, Object> data = message.getData();
		switch (String.valueOf(message.getName()))
		{
			case DISCOVER:
				Object from = data.get(FROM);
				if (!self.plugin.equals(from))
				{
					post.accept(hello());
				}
				return;
			case HELLO:
				onHello(data);
				return;
			case BYE:
				Object leaving = data.get(PLUGIN);
				if (leaving instanceof String)
				{
					members.remove(leaving);
				}
				else
				{
					warnOnce("bye:" + leaving, "Ignored a plugin bus bye without a plugin id");
				}
				return;
			default:
				// A name this version does not know: rule 6, ignore it.
		}
	}

	private void onHello(Map<String, Object> data)
	{
		Member member = parseHello(data);
		if (member == null)
		{
			Object plugin = data.get(PLUGIN);
			warnOnce("hello:" + plugin, "Ignored a malformed plugin bus hello from {}", plugin);
			return;
		}
		if (member.plugin.equals(self.plugin))
		{
			return;
		}
		if (member.bus != BUS_VERSION)
		{
			warnOnce("bus:" + member.plugin + ":" + member.bus,
				"Ignored {}: it speaks plugin bus {}, this plugin speaks {}", member.plugin, member.bus, BUS_VERSION);
			return;
		}
		members.put(member.plugin, member);
	}

	/** The member a {@code hello} describes, or null if a required key is missing or of the wrong type. */
	static Member parseHello(Map<String, Object> data)
	{
		if (data == null)
		{
			return null;
		}
		Object plugin = data.get(PLUGIN);
		Object name = data.get(NAME);
		Object version = data.get(VERSION);
		Object bus = data.get(BUS);
		List<String> accepts = strings(data.get(ACCEPTS));
		List<String> posts = strings(data.get(POSTS));
		if (!(plugin instanceof String) || ((String) plugin).isEmpty()
			|| !(name instanceof String) || !(version instanceof String) || !(bus instanceof Number)
			|| accepts == null || posts == null)
		{
			return null;
		}
		return new Member((String) plugin, (String) name, (String) version, ((Number) bus).intValue(), accepts,
			posts);
	}

	private static List<String> strings(Object value)
	{
		if (!(value instanceof List))
		{
			return null;
		}
		List<String> strings = new ArrayList<>();
		for (Object element : (List<?>) value)
		{
			if (!(element instanceof String))
			{
				return null;
			}
			strings.add((String) element);
		}
		return strings;
	}

	private PluginMessage hello()
	{
		Map<String, Object> data = new HashMap<>();
		data.put(PLUGIN, self.plugin);
		data.put(NAME, self.name);
		data.put(VERSION, self.version);
		data.put(BUS, self.bus);
		data.put(ACCEPTS, self.accepts);
		data.put(POSTS, self.posts);
		return message(HELLO, data);
	}

	private PluginMessage discover()
	{
		return message(DISCOVER, Collections.singletonMap(FROM, self.plugin));
	}

	private static PluginMessage message(String name, Map<String, Object> data)
	{
		return new PluginMessage(NAMESPACE, name, Collections.unmodifiableMap(new HashMap<>(data)));
	}

	private void warnOnce(String key, String format, Object... arguments)
	{
		if (warned.add(key))
		{
			log.warn(format, arguments);
		}
	}
}
