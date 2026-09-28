package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.EnumSet;
import java.util.Set;
import org.junit.Test;

public class RouteWithTest
{
	private static final Set<PathingPlugin> BOTH = EnumSet.allOf(PathingPlugin.class);
	private static final Set<PathingPlugin> FARM_ONLY = EnumSet.of(PathingPlugin.FARM_ROUTE_PLANNER);
	private static final Set<PathingPlugin> SHORTEST_ONLY = EnumSet.of(PathingPlugin.SHORTEST_PATH);
	private static final Set<PathingPlugin> NONE = EnumSet.noneOf(PathingPlugin.class);

	@Test
	public void autoPrefersFarmRoutePlannerWhenBothAreOn()
	{
		assertEquals(PathingPlugin.FARM_ROUTE_PLANNER, RouteWith.AUTO.choose(BOTH::contains));
	}

	@Test
	public void autoFallsBackToShortestPath()
	{
		assertEquals(PathingPlugin.SHORTEST_PATH, RouteWith.AUTO.choose(SHORTEST_ONLY::contains));
	}

	@Test
	public void autoChoosesNothingWhenNeitherIsOn()
	{
		assertNull(RouteWith.AUTO.choose(NONE::contains));
	}

	@Test
	public void anExplicitChoiceNeverFallsBackToTheOther()
	{
		assertNull(RouteWith.SHORTEST_PATH.choose(FARM_ONLY::contains));
		assertNull(RouteWith.FARM_ROUTE_PLANNER.choose(SHORTEST_ONLY::contains));
		assertEquals(PathingPlugin.SHORTEST_PATH, RouteWith.SHORTEST_PATH.choose(BOTH::contains));
		assertEquals(PathingPlugin.FARM_ROUTE_PLANNER, RouteWith.FARM_ROUTE_PLANNER.choose(BOTH::contains));
	}

	@Test
	public void theNamespacesAreTheOnesEachPluginListensOn()
	{
		// Farm Route Planner's PLUGIN_MESSAGE_NAMESPACE (its ADR-038), and upstream Shortest Path's.
		assertEquals("farmrouteplanner", PathingPlugin.FARM_ROUTE_PLANNER.namespace);
		assertEquals("shortestpath", PathingPlugin.SHORTEST_PATH.namespace);
	}
}
