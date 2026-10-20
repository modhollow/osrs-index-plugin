package com.osrsindex;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.Icon;
import net.runelite.client.ui.ColorScheme;

/**
 * The small marks the Guides view draws, painted rather than loaded, so they stay sharp at any scale: a status dot,
 * a map pin, a bag for a step's items, and fold and back arrows. The route's nodes are drawn by the view itself.
 */
final class GuideIcons
{
	/** The green of a done step, a met requirement and a carried item, calmer than RuneLite's progress green. */
	static final Color DONE = new Color(46, 160, 67);
	static final Color INK = new Color(20, 20, 20);

	private GuideIcons()
	{
	}

	/** A status dot, set on the first line of the text beside it. */
	static Icon dot(Color colour, int lineHeight)
	{
		return new Painted(8, lineHeight, g ->
		{
			g.setColor(colour);
			int y = Math.max(0, (Math.min(lineHeight, 16) - 7) / 2);
			g.fillOval(0, y, 7, 7);
		});
	}

	/** A map pin, filled when the place has a map point and hollow when it has only a label. */
	static Icon pin(boolean hasPoint, int lineHeight)
	{
		Color colour = hasPoint ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR;
		return new Painted(8, lineHeight, g ->
		{
			int y = Math.max(0, (Math.min(lineHeight, 16) - 11) / 2);
			Path2D pin = new Path2D.Float();
			pin.moveTo(4, y + 11);
			pin.curveTo(0, y + 6, 0, y + 4, 0, y + 4);
			pin.curveTo(0, y + 1.5, 1.8, y, 4, y);
			pin.curveTo(6.2, y, 8, y + 1.5, 8, y + 4);
			pin.curveTo(8, y + 4, 8, y + 6, 4, y + 11);
			pin.closePath();
			g.setColor(colour);
			if (hasPoint)
			{
				g.fill(pin);
				g.setColor(INK);
				g.fillOval(3, y + 3, 2, 2);
			}
			else
			{
				g.setStroke(new BasicStroke(1.2f));
				g.draw(pin);
			}
		});
	}

	/** A fold arrow: pointing right when folded, down when open. */
	static Icon chevron(boolean open, Color colour)
	{
		return new Painted(8, 8, g ->
		{
			g.setColor(colour);
			Path2D arrow = new Path2D.Float();
			if (open)
			{
				arrow.moveTo(0, 2);
				arrow.lineTo(8, 2);
				arrow.lineTo(4, 7);
			}
			else
			{
				arrow.moveTo(2, 0);
				arrow.lineTo(7, 4);
				arrow.lineTo(2, 8);
			}
			arrow.closePath();
			g.fill(arrow);
		});
	}

	/** A sack, for the items a step needs: coloured by whether you carry them. */
	static Icon bag(Color colour)
	{
		return new Painted(12, 12, g ->
		{
			Path2D sack = new Path2D.Float();
			sack.moveTo(4, 3);
			sack.curveTo(0.5, 5, 0.5, 11.5, 6, 11.5);
			sack.curveTo(11.5, 11.5, 11.5, 5, 8, 3);
			sack.closePath();
			g.setColor(colour);
			g.fill(sack);
			g.fillRect(4, 1, 4, 2);
			g.setColor(INK);
			g.drawLine(4, 3, 8, 3);
		});
	}

	/** "Back": an arrow pointing left. */
	static Icon back(Color colour)
	{
		return new Painted(8, 8, g ->
		{
			g.setColor(colour);
			Path2D arrow = new Path2D.Float();
			arrow.moveTo(6, 0);
			arrow.lineTo(1, 4);
			arrow.lineTo(6, 8);
			arrow.closePath();
			g.fill(arrow);
		});
	}

	private interface Paint
	{
		void paint(Graphics2D g);
	}

	private static final class Painted implements Icon
	{
		private final int width;
		private final int height;
		private final Paint paint;

		Painted(int width, int height, Paint paint)
		{
			this.width = width;
			this.height = height;
			this.paint = paint;
		}

		@Override
		public void paintIcon(Component c, Graphics graphics, int x, int y)
		{
			Graphics2D g = (Graphics2D) graphics.create();
			try
			{
				g.translate(x, y);
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
				paint.paint(g);
			}
			finally
			{
				g.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return width;
		}

		@Override
		public int getIconHeight()
		{
			return height;
		}
	}
}
