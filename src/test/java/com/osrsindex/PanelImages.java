package com.osrsindex;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.laf.RuneLiteLAF;

/**
 * Draws the README's panel images from the plugin's own {@link OsrsIndexPanel}, in RuneLite's look, with a sample
 * character and code, so no image carries anyone's account: {@code gradlew panelImages}. Each image is the panel at
 * the sidebar's width, doubled pixel for pixel so it stays sharp.
 */
public class PanelImages
{
	private static final String CHARACTER = "Zezima";
	private static final String CODE = "KXQ4-7MNP";
	private static final GuidesPanel.Actions NO_GUIDE_ACTIONS = new GuidesPanel.Actions()
	{
		@Override
		public void retryGuides()
		{
		}

		@Override
		public void openGuide(String guideId)
		{
		}

		@Override
		public void showGuideList()
		{
		}

		@Override
		public void markStep(String stepId, GuideProgress.Mark mark)
		{
		}

		@Override
		public void walkTo(String stepId)
		{
		}

		@Override
		public void followWithQuestHelper(String stepId)
		{
		}

		@Override
		public void startHere(String stepId)
		{
		}
	};

	public static void main(String[] args) throws Exception
	{
		File out = new File(args.length > 0 ? args[0] : "images");
		if (!out.isDirectory() && !out.mkdirs())
		{
			throw new IOException("Cannot make " + out);
		}
		SwingUtilities.invokeAndWait(RuneLiteLAF::setup);

		draw(out, "panel-not-linked", panel ->
		{
			panel.showNotLinked(null);
			panel.showDestinations(OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.OFF, true, false, true,
				false));
		});
		draw(out, "panel-link-code", panel ->
		{
			panel.showCode(CODE);
			panel.showDestinations(OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.OFF, true, false, true,
				false));
		});
		draw(out, "panel-linked", panel ->
		{
			panel.showLinked(CHARACTER, "Last sync at 14:02: location, skills and bank.");
			panel.showDestinations(OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.LISTENING, true, true,
				true, false));
		});
		draw(out, "panel-notice", panel ->
		{
			panel.showNotice(new SiteConfig.Notice("maintenance", "osrsindex.com is down for maintenance on"
				+ " 20 October from 18:00 to 19:00 UTC.", "https://osrsindex.com/", false));
			panel.showLinked(CHARACTER, OsrsIndexPlugin.waitLine(java.time.LocalTime.of(18, 4),
				"osrsindex.com is down for maintenance until 19:00 UTC."));
			panel.showDestinations(OsrsIndexPlugin.destinationStatusText(DestinationSocket.Status.RETRYING, true, true,
				true, false));
		});
		draw(out, "panel-guides", (panel, guides) ->
		{
			panel.selectGuidesTab();
			guides.show(new GuidesPanel.View(GuidesPanel.Load.OK, GuideFixtures.listing(), false, GuidesPanel.Load.NONE,
				null, new GuideProgress(), GuideFacts.UNKNOWN, true, false, true, QuestHelperHandOff.Gang.UNKNOWN, null));
		});
		draw(out, "panel-guide-step", (panel, guides) ->
		{
			panel.selectGuidesTab();
			Map<Integer, Integer> carried = new HashMap<>();
			carried.put(1925, 1);
			GuideFacts.Builder facts = new GuideFacts.Builder().questPoints(0).accountType("normal").carried(carried)
				.quest("Cook's Assistant", QuestState.NOT_STARTED).quest("Sheep Shearer", QuestState.NOT_STARTED);
			for (Skill skill : Skill.values())
			{
				facts.level(skill, skill == Skill.HITPOINTS ? 10 : 1, skill == Skill.HITPOINTS ? 10 : 1);
			}
			guides.show(new GuidesPanel.View(GuidesPanel.Load.OK, GuideFixtures.listing(), true, GuidesPanel.Load.OK,
				GuideFixtures.guide(), new GuideProgress(), facts.build(), true, true, true, QuestHelperHandOff.Gang.UNKNOWN,
				null));
		});
		draw(out, "panel-guide-progress", (panel, guides) ->
		{
			panel.selectGuidesTab();
			// An account that is not new: Cook's Assistant finished and 10 Cooking reached, Sheep Shearer never done, and
			// Start here on the goblin. The Mining Instructor, with nothing to check, is passed; Sheep Shearer is behind.
			GuideProgress progress = new GuideProgress();
			progress.start("risky-step");
			GuideFacts.Builder facts = new GuideFacts.Builder().questPoints(1).accountType("ironman")
				.carried(Collections.<Integer, Integer>emptyMap()).banked(Collections.singletonMap(1925, 1))
				.quest("Cook's Assistant", QuestState.FINISHED)
				.quest("Sheep Shearer", QuestState.NOT_STARTED);
			for (Skill skill : Skill.values())
			{
				int level = skill == Skill.HITPOINTS || skill == Skill.COOKING ? 10 : 1;
				facts.level(skill, level, level);
			}
			guides.show(new GuidesPanel.View(GuidesPanel.Load.OK, GuideFixtures.listing(), true, GuidesPanel.Load.OK,
				GuideFixtures.guide(), progress, facts.build(), true, false, true, QuestHelperHandOff.Gang.UNKNOWN, null));
			guides.openStep("cooks-assistant");
			guides.openStep("mining-instructor");
		});
		drawStepBox(out);
		System.exit(0);
	}

	/** The step box the plugin draws on the game screen, over a plain background, doubled like the panels. */
	private static void drawStepBox(File out) throws IOException
	{
		GuideStepOverlay overlay = new GuideStepOverlay();
		overlay.show(new GuideStepOverlay.Box("Beginner walkthrough", "0/5", "Cook's Assistant",
			"Help the Lumbridge cook bake a cake.", "Lumbridge Castle kitchen", Collections.singletonList("Bucket"),
			"Quest Helper takes over when you start Cook's Assistant."));
		// Drawn on a large canvas, then cut out with a margin. A panel sizes itself from the frame before, as it does
		// in the client every frame, so the first frame only measures.
		BufferedImage canvas = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = canvas.createGraphics();
		g.setFont(FontManager.getRunescapeFont());
		overlay.render(g);
		g.setColor(new Color(0x3E, 0x35, 0x29));
		g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
		g.translate(8, 8);
		Dimension size = overlay.render(g);
		g.dispose();
		BufferedImage flat = canvas.getSubimage(0, 0, size.width + 16, size.height + 16);
		BufferedImage doubled = new BufferedImage(flat.getWidth() * 2, flat.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
		Graphics2D big = doubled.createGraphics();
		big.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		big.drawImage(flat, 0, 0, doubled.getWidth(), doubled.getHeight(), null);
		big.dispose();
		File file = new File(out, "step-box.png");
		ImageIO.write(doubled, "png", file);
		System.out.println("wrote " + file.getPath() + " (" + doubled.getWidth() + "x" + doubled.getHeight() + ")");
	}

	private interface State
	{
		void apply(OsrsIndexPanel panel);
	}

	private interface GuidesState
	{
		void apply(OsrsIndexPanel panel, GuidesPanel guides);
	}

	private static void draw(File out, String name, State state) throws Exception
	{
		draw(out, name, (GuidesState) (panel, guides) -> state.apply(panel));
	}

	private static void draw(File out, String name, GuidesState state) throws Exception
	{
		OsrsIndexPanel[] holder = new OsrsIndexPanel[1];
		GuidesPanel[] guides = new GuidesPanel[1];
		JFrame[] frame = new JFrame[1];
		SwingUtilities.invokeAndWait(() ->
		{
			guides[0] = new GuidesPanel(NO_GUIDE_ACTIONS);
			holder[0] = new OsrsIndexPanel(new OsrsIndexPanel.Actions()
			{
				@Override
				public void guidesOpened()
				{
				}

				@Override
				public void linkWithBrowser()
				{
				}

				@Override
				public void requestNewCode()
				{
				}

				@Override
				public void unlink()
				{
				}

				@Override
				public void openMapHere()
				{
				}
			}, guides[0]);
		});
		// The panel's own updates go through invokeLater; queue them, then let them run.
		state.apply(holder[0], guides[0]);
		SwingUtilities.invokeAndWait(() ->
		{
		});
		BufferedImage[] image = new BufferedImage[1];
		SwingUtilities.invokeAndWait(() ->
		{
			OsrsIndexPanel panel = holder[0];
			// Never shown: packing gives the components a peer, so they lay out and paint as in the client.
			frame[0] = new JFrame();
			frame[0].add(panel);
			panel.setSize(new Dimension(PluginPanel.PANEL_WIDTH, 10));
			int height = panel.getPreferredSize().height;
			panel.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, height));
			frame[0].pack();
			layout(panel);
			BufferedImage flat = new BufferedImage(PluginPanel.PANEL_WIDTH, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = flat.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			panel.paint(g);
			g.dispose();
			BufferedImage doubled = new BufferedImage(flat.getWidth() * 2, flat.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
			Graphics2D big = doubled.createGraphics();
			big.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			big.drawImage(flat, 0, 0, doubled.getWidth(), doubled.getHeight(), null);
			big.dispose();
			image[0] = doubled;
			frame[0].dispose();
		});
		File file = new File(out, name + ".png");
		ImageIO.write(image[0], "png", file);
		System.out.println("wrote " + file.getPath() + " (" + image[0].getWidth() + "x" + image[0].getHeight() + ")");
	}

	private static void layout(Component component)
	{
		component.doLayout();
		if (component instanceof Container)
		{
			for (Component child : ((Container) component).getComponents())
			{
				layout(child);
			}
		}
	}
}
