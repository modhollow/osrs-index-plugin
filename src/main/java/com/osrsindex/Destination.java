package com.osrsindex;

import java.time.Instant;

/**
 * One place the player sent from osrsindex.com's map, as the site's {@code destination} frame carries it
 * Nothing here is checked yet:
 * {@link RouteDispatcher} decides whether it is valid.
 */
final class Destination
{
	final String id;
	final int x;
	final int y;
	final int plane;
	final String label;
	final Instant expiresAt;

	Destination(String id, int x, int y, int plane, String label, Instant expiresAt)
	{
		this.id = id;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.label = label;
		this.expiresAt = expiresAt;
	}
}
