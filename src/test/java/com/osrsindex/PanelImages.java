package com.osrsindex;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
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
		System.exit(0);
	}

	private interface State
	{
		void apply(OsrsIndexPanel panel);
	}

	private static void draw(File out, String name, State state) throws Exception
	{
		OsrsIndexPanel[] holder = new OsrsIndexPanel[1];
		JFrame[] frame = new JFrame[1];
		SwingUtilities.invokeAndWait(() ->
		{
			holder[0] = new OsrsIndexPanel(new OsrsIndexPanel.Actions()
			{
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
			});
		});
		// The panel's own updates go through invokeLater; queue them, then let them run.
		state.apply(holder[0]);
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
