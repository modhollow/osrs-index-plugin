package com.osrsindex;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Outlines the current guide step's tile in the game world once it is in view, as Quest Helper marks where to go.
 * Only on the tile's floor, and not in an instance, whose tiles are not the world's.
 */
final class GuideTileOverlay extends Overlay
{
	private static final Color FILL = new Color(0xD9, 0xB9, 0x8C, 40);
	private static final BasicStroke STROKE = new BasicStroke(2);

	private final Client client;
	private volatile WorldPoint target;

	GuideTileOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	/** The tile to outline, or null for none. */
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
		if (place == null || player == null || view == null || view.isInstance() || view.getPlane() != place.getPlane())
		{
			return null;
		}
		LocalPoint local = LocalPoint.fromWorld(view, place);
		Polygon tile = local == null ? null : Perspective.getCanvasTilePoly(client, local);
		if (tile != null)
		{
			OverlayUtil.renderPolygon(graphics, tile, GuideMinimapOverlay.COLOR, FILL, STROKE);
		}
		return null;
	}
}
