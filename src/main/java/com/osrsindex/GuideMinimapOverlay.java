package com.osrsindex;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * The minimap arrow toward the current guide step's place: a dot on the place once it is on the minimap, and an
 * arrow at the minimap's edge pointing to it before then. Only on the place's floor, and not in an instance, whose
 * tiles are not the world's.
 */
final class GuideMinimapOverlay extends Overlay
{
	static final Color COLOR = new Color(0xD9, 0xB9, 0x8C);
	/** Within this many tiles the place itself is drawn; the minimap shows about 19 around the player. */
	static final int DOT_RANGE_TILES = 15;
	/** How far from the minimap's centre the arrow sits, in pixels. */
	private static final int ARROW_RADIUS = 58;

	private final Client client;
	private volatile WorldPoint target;

	GuideMinimapOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** The place to point to, or null for none. */
	void target(WorldPoint place)
	{
		target = place;
	}

	WorldPoint target()
	{
		return target;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		WorldPoint place = target;
		Player player = client.getLocalPlayer();
		WorldView view = client.getTopLevelWorldView();
		if (place == null || player == null || view == null || view.isInstance())
		{
			return null;
		}
		WorldPoint here = player.getWorldLocation();
		LocalPoint local = player.getLocalLocation();
		if (here == null || local == null || here.getPlane() != place.getPlane())
		{
			return null;
		}
		int dx = place.getX() - here.getX();
		int dy = place.getY() - here.getY();
		double distance = Math.hypot(dx, dy);
		if (distance <= DOT_RANGE_TILES)
		{
			LocalPoint placeLocal = LocalPoint.fromWorld(view, place);
			Point dot = placeLocal == null ? null : Perspective.localToMinimap(client, placeLocal);
			if (dot != null)
			{
				OverlayUtil.renderMinimapLocation(graphics, dot, COLOR);
			}
			return null;
		}
		// A tile a few steps toward the place: the minimap's own projection then gives the direction, with its
		// rotation and zoom.
		Point centre = Perspective.localToMinimap(client, local);
		LocalPoint toward = new LocalPoint(local.getX() + (int) Math.round(dx / distance * 4 * Perspective.LOCAL_TILE_SIZE),
			local.getY() + (int) Math.round(dy / distance * 4 * Perspective.LOCAL_TILE_SIZE), local.getWorldView());
		Point ahead = Perspective.localToMinimap(client, toward);
		if (centre == null || ahead == null)
		{
			return null;
		}
		double angle = Math.atan2(ahead.getY() - centre.getY(), ahead.getX() - centre.getX());
		drawArrow(graphics, centre.getX() + ARROW_RADIUS * Math.cos(angle), centre.getY() + ARROW_RADIUS * Math.sin(angle),
			angle);
		return null;
	}

	private static void drawArrow(Graphics2D graphics, double x, double y, double angle)
	{
		double length = 10;
		double half = 6;
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		Polygon arrow = new Polygon();
		arrow.addPoint((int) Math.round(x + cos * length / 2), (int) Math.round(y + sin * length / 2));
		arrow.addPoint((int) Math.round(x - cos * length / 2 - sin * half), (int) Math.round(y - sin * length / 2 + cos * half));
		arrow.addPoint((int) Math.round(x - cos * length / 2 + sin * half), (int) Math.round(y - sin * length / 2 - cos * half));
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(COLOR);
		graphics.fillPolygon(arrow);
		graphics.setColor(Color.BLACK);
		graphics.setStroke(new BasicStroke(1));
		graphics.drawPolygon(arrow);
	}
}
