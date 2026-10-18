package com.osrsindex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RouteWithTest
{
	@Test
	public void shortestPathIsChosenWhenItIsOn()
	{
		assertEquals(PathingPlugin.SHORTEST_PATH, RouteWith.SHORTEST_PATH.choose(plugin -> true));
	}

	@Test
	public void nothingIsChosenWhenShortestPathIsOff()
	{
		assertNull(RouteWith.SHORTEST_PATH.choose(plugin -> false));
	}

	@Test
	public void theNamespaceIsTheOneShortestPathListensOn()
	{
		// Upstream Shortest Path's PluginMessage namespace.
		assertEquals("shortestpath", PathingPlugin.SHORTEST_PATH.namespace);
	}
}
