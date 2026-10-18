package com.osrsindex;

import java.util.function.Predicate;

/**
 * Which pathing plugin a site destination goes to. There is one choice: destinations always go to Shortest Path.
 * Farm Route Planner is on the plugin bus, but it is never handed a destination.
 */
enum RouteWith
{
	SHORTEST_PATH(PathingPlugin.SHORTEST_PATH);

	private final PathingPlugin plugin;

	RouteWith(PathingPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** The plugin to use, or null when it is off. Nothing else is tried in its place. */
	PathingPlugin choose(Predicate<PathingPlugin> active)
	{
		return active.test(plugin) ? plugin : null;
	}

	@Override
	public String toString()
	{
		return plugin.displayName;
	}
}
