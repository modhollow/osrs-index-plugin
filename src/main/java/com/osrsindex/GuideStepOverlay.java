package com.osrsindex;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.util.Text;

/**
 * The current guide step in a box on the game screen, as Quest Helper shows its current step: the guide and its
 * count, the step's title and text, where it is, and what to bring that the player is not carrying. It shows nothing
 * while Quest Helper guides the step's quest, so the two boxes never show at once.
 */
final class GuideStepOverlay extends OverlayPanel
{
	/** The longest text the box shows, in characters; the panel has the rest. */
	static final int MAX_TEXT = 300;
	/** Lines of items still to bring; the rest are counted, and listed in the panel. */
	static final int MAX_BRING_LINES = 6;
	private static final int WIDTH = 200;

	/** What the box shows: plain text, made at the client thread's last check. */
	static final class Box
	{
		final String guide;
		final String count;
		final String step;
		final String text;
		/** Where the step is, or null. */
		final String where;
		/** Items to bring the player is not carrying. */
		final List<String> missing;
		/** A line about Quest Helper, before the quest starts, or null. */
		final String questHelper;

		Box(String guide, String count, String step, String text, String where, List<String> missing,
			String questHelper)
		{
			// The overlay draws RuneLite's colour tags, and these come from the site: shown as plain text.
			this.guide = plain(guide);
			this.count = plain(count);
			this.step = plain(step);
			this.text = plain(text);
			this.where = plain(where);
			List<String> plainMissing = new ArrayList<>();
			for (String item : missing)
			{
				plainMissing.add(plain(item));
			}
			this.missing = Collections.unmodifiableList(plainMissing);
			this.questHelper = plain(questHelper);
		}
	}

	private static String plain(String text)
	{
		return text == null ? null : Text.removeTags(text);
	}

	private volatile Box box;

	GuideStepOverlay()
	{
		setPosition(OverlayPosition.TOP_LEFT);
	}

	/** The box to show, or null for none. */
	void show(Box next)
	{
		box = next;
	}

	Box shown()
	{
		return box;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Box current = box;
		if (current == null)
		{
			return null;
		}
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		panelComponent.getChildren().add(LineComponent.builder()
			.left(current.guide)
			.leftColor(Color.WHITE)
			.right(current.count)
			.rightColor(ColorScheme.LIGHT_GRAY_COLOR)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left(current.step)
			.leftColor(ColorScheme.BRAND_ORANGE)
			.build());
		if (current.questHelper != null)
		{
			line(current.questHelper, ColorScheme.BRAND_ORANGE);
		}
		if (current.text != null)
		{
			line(current.text, Color.WHITE);
		}
		if (current.where != null)
		{
			line("Where: " + current.where, ColorScheme.LIGHT_GRAY_COLOR);
		}
		for (String item : bringLines(current.missing))
		{
			line(item, ColorScheme.PROGRESS_ERROR_COLOR);
		}
		return super.render(graphics);
	}

	/** The box's Bring lines: at most {@link #MAX_BRING_LINES} items, then how many more the panel lists. */
	static List<String> bringLines(List<String> missing)
	{
		List<String> out = new ArrayList<>();
		for (String item : missing)
		{
			if (out.size() == MAX_BRING_LINES && missing.size() > MAX_BRING_LINES + 1)
			{
				out.add("Bring: and " + (missing.size() - MAX_BRING_LINES) + " more, in the panel");
				break;
			}
			out.add("Bring: " + item);
		}
		return out;
	}

	private void line(String text, Color colour)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(colour).build());
	}

	/** The step's text for the box: the Ironman text for an Ironman, cut to {@link #MAX_TEXT}. */
	static String text(Guide.Step step, boolean ironman)
	{
		String text = ironman && step.ironman != null ? step.ironman : step.text;
		if (text == null || text.length() <= MAX_TEXT)
		{
			return text;
		}
		int cut = text.lastIndexOf(' ', MAX_TEXT - 1);
		return text.substring(0, cut > MAX_TEXT / 2 ? cut : MAX_TEXT - 1) + "...";
	}
}
