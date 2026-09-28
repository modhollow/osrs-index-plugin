package com.osrsindex;

import java.util.function.Predicate;

/** Which pathing plugin a site destination goes to. */
enum RouteWith
{
	/** Farm Route Planner when it is on, otherwise Shortest Path when that is on. */
	AUTO("Whichever is on"),
	FARM_ROUTE_PLANNER(PathingPlugin.FARM_ROUTE_PLANNER.displayName),
	SHORTEST_PATH(PathingPlugin.SHORTEST_PATH.displayName);

	private final String label;

	RouteWith(String label)
	{
		this.label = label;
	}

	/** The plugin to use, or null when the chosen one (or, for {@link #AUTO}, every one) is off. */
	PathingPlugin choose(Predicate<PathingPlugin> active)
	{
		switch (this)
		{
			case FARM_ROUTE_PLANNER:
				return active.test(PathingPlugin.FARM_ROUTE_PLANNER) ? PathingPlugin.FARM_ROUTE_PLANNER : null;
			case SHORTEST_PATH:
				return active.test(PathingPlugin.SHORTEST_PATH) ? PathingPlugin.SHORTEST_PATH : null;
			default:
				if (active.test(PathingPlugin.FARM_ROUTE_PLANNER))
				{
					return PathingPlugin.FARM_ROUTE_PLANNER;
				}
				return active.test(PathingPlugin.SHORTEST_PATH) ? PathingPlugin.SHORTEST_PATH : null;
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
