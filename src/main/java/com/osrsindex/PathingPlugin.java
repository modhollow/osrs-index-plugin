package com.osrsindex;

/**
 * A pathing plugin a site destination can be handed to, over RuneLite's {@code PluginMessage} bus. Hub
 * plugins load in separate class loaders and cannot call one another, so a message is the only way in.
 *
 * <p>Both answer {@code <namespace>/path} with {@code data.target} a {@code WorldPoint}, and start from the
 * player's tile when {@code data.start} is absent. Farm Route Planner took its own namespace in its ADR-038,
 * so a message to Shortest Path does not also reach it.
 */
enum PathingPlugin
{
	FARM_ROUTE_PLANNER("farmrouteplanner", "Farm Route Planner"),
	SHORTEST_PATH("shortestpath", "Shortest Path");

	/** The {@code PluginMessage} namespace the plugin listens on. */
	final String namespace;
	/** Its {@code @PluginDescriptor} name: what {@code Plugin.getName()} returns, and what the player sees. */
	final String displayName;

	PathingPlugin(String namespace, String displayName)
	{
		this.namespace = namespace;
		this.displayName = displayName;
	}
}
