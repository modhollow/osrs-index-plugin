package com.osrsindex;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The sidebar panel. Not linked, it offers <b>Link with osrsindex.com</b>, which opens the site with this
 * client's code already in it. While a code waits, it shows the code so the player can check the
 * site shows the same one. Linked, it shows the character, the last sync, and buttons to the site's pages
 * for it: the character page, the map at the player's position, and the account page.
 */
class OsrsIndexPanel extends PluginPanel
{
	interface Actions
	{
		/** Open the site's approval page for this client, getting a code first if none is waiting. */
		void linkWithBrowser();

		void requestNewCode();

		void unlink();

		/** Open the site's map at the player's position, or the whole map when it is unknown. */
		void openMapHere();
	}

	private final JLabel status = new JLabel();
	private final JLabel code = new JLabel(" ", SwingConstants.LEFT);
	private final JLabel detail = new JLabel();
	/** The last sync: when, and what it stored. */
	private final JLabel sync = new JLabel();
	/** Whether this client is listening for places sent from the site. */
	private final JLabel destinations = new JLabel();
	private final JButton link = new JButton("Link with osrsindex.com");
	private final JButton newCode = new JButton("Get a new code");
	private final JButton character = new JButton("My character");
	private final JButton map = new JButton("Map at my location");
	private final JButton account = new JButton("Account and linked clients");
	private final JButton unlink = new JButton("Unlink this client");

	OsrsIndexPanel(Actions actions)
	{
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("OSRS Index");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		code.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 20f));
		code.setForeground(ColorScheme.BRAND_ORANGE);
		code.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		sync.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		destinations.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		link.addActionListener(e -> actions.linkWithBrowser());
		newCode.addActionListener(e -> actions.requestNewCode());
		character.addActionListener(e -> LinkBrowser.browse(Endpoints.tracker()));
		map.addActionListener(e -> actions.openMapHere());
		account.addActionListener(e -> LinkBrowser.browse(Endpoints.account()));
		unlink.addActionListener(e -> actions.unlink());

		for (Component component : new Component[]{title, gap(), status, code, detail, gap(), link, gap(), newCode,
			gap(), sync, gap(), character, gap(), map, gap(), account, gap(), unlink, gap(), destinations})
		{
			if (component instanceof JComponent)
			{
				((JComponent) component).setAlignmentX(Component.LEFT_ALIGNMENT);
			}
			if (component instanceof JButton)
			{
				component.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
			}
			body.add(component);
		}
		add(body, BorderLayout.NORTH);
		showNotLinked(null);
	}

	private static Component gap()
	{
		return Box.createRigidArea(new Dimension(0, 6));
	}

	private static String wrap(String text)
	{
		return "<html><body style='width: 180px'>" + text + "</body></html>";
	}

	/** Linked. {@code characterName} and {@code lastSync} may be null until the player logs in and a sync lands. */
	void showLinked(String characterName, String lastSync)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(wrap("Linked. Your character syncs to your osrsindex.com account"
				+ (characterName == null ? "." : " as <b>" + escape(characterName) + "</b>.")));
			code.setText(" ");
			code.setVisible(false);
			detail.setText(wrap("Only you can see it, at osrsindex.com/tracker."));
			sync.setText(wrap(lastSync == null ? "Nothing synced yet this session." : lastSync));
			show(false, false, true);
		});
	}

	void showNotLinked(String reason)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(wrap(reason == null
				? "Not linked. Log in to the game, then click <b>Link with osrsindex.com</b> and approve this"
				+ " client on the site. Nothing to type."
				: reason));
			code.setText(" ");
			code.setVisible(false);
			detail.setText(wrap("Or paste a token from your account page into this plugin's settings."));
			show(true, false, false);
		});
	}

	/** A code waits for approval: the browser is open at it, or one click away. */
	void showCode(String userCode)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(wrap("Approve this client on osrsindex.com. Your browser opens at the page when you"
				+ " click below; sign in there if you need to. Check it shows this code:"));
			code.setText(userCode);
			code.setVisible(true);
			detail.setText(wrap("<b>After you approve, linking takes up to about 15 seconds.</b> This client collects its"
				+ " link by itself, sooner when you switch back to RuneLite. The code works for 10 minutes."));
			show(true, true, false);
		});
	}

	void showDestinations(String text)
	{
		SwingUtilities.invokeLater(() -> destinations.setText(wrap(text)));
	}

	/** Show the buttons that fit: linking ones, or the linked client's links to the site. */
	private void show(boolean linking, boolean codeWaiting, boolean linked)
	{
		link.setVisible(linking);
		newCode.setVisible(codeWaiting);
		sync.setVisible(linked);
		character.setVisible(linked);
		map.setVisible(linked);
		account.setVisible(linked);
		unlink.setVisible(linked);
	}

	/** A player name is plain text; the label renders HTML. */
	static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
