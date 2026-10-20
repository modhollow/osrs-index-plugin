package com.osrsindex;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
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
 * The panel's Guides view: osrsindex.com's guides, and the one the player follows, step by step, as Quest Helper
 * shows a quest.
 *
 * <p>A guide opens with its count over a strip of one segment per step. Under it, every step sits on a route line:
 * a node says where the step stands (a tick when done, a lit node at the current step, a ring still to do, a dash when
 * skipped), and badges at the end of the row say whether it is a quest, what you carry for it, and whether it has a
 * map point. The current step hangs off the line as a card: what to do, the items to bring and to pick up on the way
 * as short lines with their game sprites, where to go, what it needs, and a large Mark done button. Any other
 * step opens the same way with a click.
 *
 * <p>Everything shown comes in a {@link View}, built on the client thread; this class only draws it, on the Swing
 * thread. What is folded is the panel's own state.
 */
final class GuidesPanel extends JPanel
{
	interface Actions
	{
		/** Try again after the list or a guide failed to load. */
		void retryGuides();

		void openGuide(String guideId);

		/** Back to the list. The guide stays the one followed until another is opened. */
		void showGuideList();

		/** Mark a step done or skipped, or reopen it with null. */
		void markStep(String stepId, GuideProgress.Mark mark);

		/** Send the step's place to Shortest Path. */
		void walkTo(String stepId);

		void followWithQuestHelper(String stepId);

		/** The player starts from this step: every step before it is behind them, done or passed. */
		void startHere(String stepId);
	}

	/** Puts an item's game sprite on a label, when it loads. The plugin answers with RuneLite's item manager. */
	interface ItemImages
	{
		ItemImages NONE = (itemId, target) ->
		{
		};

		void show(int itemId, JLabel target);
	}

	enum Load
	{
		NONE,
		LOADING,
		OK,
		NOT_SERVED,
		UPDATE_PLUGIN,
		FAILED
	}

	/** Everything the view shows, at one moment. */
	static final class View
	{
		static final View EMPTY = new View(Load.NONE, null, false, Load.NONE, null, new GuideProgress(), GuideFacts.UNKNOWN,
			false, false, false, QuestHelperHandOff.Gang.UNKNOWN, null);

		final Load listLoad;
		final Guide.Listing listing;
		/** The guide is open in the view, rather than the list. */
		final boolean guideOpen;
		final Load guideLoad;
		final Guide guide;
		final GuideProgress progress;
		final GuideFacts facts;
		/** Logged in, so progress can be read and kept. */
		final boolean loggedIn;
		final boolean questHelperOn;
		final boolean autoStart;
		final QuestHelperHandOff.Gang gang;
		/** The helper Quest Helper says it shows, when it says, or null. */
		final String questHelperSelected;

		View(Load listLoad, Guide.Listing listing, boolean guideOpen, Load guideLoad, Guide guide, GuideProgress progress,
			GuideFacts facts, boolean loggedIn, boolean questHelperOn, boolean autoStart, QuestHelperHandOff.Gang gang,
			String questHelperSelected)
		{
			this.listLoad = listLoad;
			this.listing = listing;
			this.guideOpen = guideOpen;
			this.guideLoad = guideLoad;
			this.guide = guide;
			this.progress = progress;
			this.facts = facts;
			this.loggedIn = loggedIn;
			this.questHelperOn = questHelperOn;
			this.autoStart = autoStart;
			this.gang = gang;
			this.questHelperSelected = questHelperSelected;
		}

		QuestHelperHandOff handOff(Guide.Step step)
		{
			return QuestHelperHandOff.of(step, questHelperOn, autoStart, gang, facts);
		}
	}

	/** The sidebar's width inside {@link OsrsIndexPanel}'s 10 px border. */
	private static final int CONTENT = PluginPanel.PANEL_WIDTH - 20;
	/** The route line's column, on the left. */
	private static final int GUTTER = 24;
	private static final int LINE_X = 10;
	/** Everything right of the route line. */
	private static final int RIGHT = CONTENT - GUTTER;
	private static final int CARD_PAD = 7;
	/** The text width inside a step's card. */
	private static final int CARD_TEXT = RIGHT - 2 * CARD_PAD;
	/** The text beside a dot, a pin or a slot in that card. */
	private static final int ROW_TEXT = CARD_TEXT - 14;
	/** Item lines a card shows before Show all. */
	private static final int FOLDED_ITEMS = 10;

	private static final Color PANEL = ColorScheme.DARK_GRAY_COLOR;
	private static final Color PANEL_HOVER = new Color(48, 48, 48);
	private static final Color BOX = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color BOX_HOVER = new Color(44, 44, 44);
	private static final Color CALLOUT = new Color(38, 38, 38);
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color FAINT = new Color(120, 120, 120);
	private static final Color LINE = new Color(70, 70, 70);
	private static final Color TEXT = ColorScheme.TEXT_COLOR;
	private static final Color ORANGE = ColorScheme.BRAND_ORANGE;
	private static final Color DONE = GuideIcons.DONE;
	private static final Color MISSING = new Color(232, 72, 60);
	private static final Color PARTLY = ColorScheme.PROGRESS_INPROGRESS_COLOR;
	private static final Color IRON = new Color(150, 160, 170);
	private static final Color MARK_DONE = new Color(36, 128, 54);
	private static final Color PILL = new Color(64, 46, 18);
	/** A step counted as passed: the done green, dimmed. */
	private static final Color PASSED_GREEN = new Color(36, 104, 50);

	/** Icons RuneLite ships for its world map and hiscores; each is left out if a RuneLite update moves it. */
	private static final BufferedImage QUEST_ICON = resource("worldmap/quest_started_icon.png");
	private static final BufferedImage TRAVEL_ICON = resource("worldmap/transportation_icon.png");
	private static final BufferedImage IRONMAN_ICON = resource("hiscore/ironman.png");

	private final Actions actions;
	/** Read and written on the Swing thread, as is what is folded. */
	private View view = View.EMPTY;
	private ItemImages itemImages = ItemImages.NONE;
	private final Set<String> openSections = new HashSet<>();
	private final Set<String> openSteps = new HashSet<>();
	/** Steps whose long item lists are shown whole. */
	private final Set<String> allItems = new HashSet<>();
	private String shownGuide;
	private String shownCurrent;
	/** Where the player is in the guide shown, worked out once per build. */
	private GuideRules.Standing standing;

	GuidesPanel(Actions actions)
	{
		this.actions = actions;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(PANEL);
		rebuild();
	}

	void show(View next)
	{
		SwingUtilities.invokeLater(() ->
		{
			view = next;
			rebuild();
		});
	}

	/** Opens a step on the route, and its section, as a click on its row does. */
	void openStep(String stepId)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (view.guide != null && view.guide.step(stepId) != null)
			{
				openSteps.add(stepId);
				openSections.add(sectionOf(view.guide, stepId).id);
				rebuild();
			}
		});
	}

	/** Item sprites for the steps' item lines; without it, a line leaves the sprite's space blank. */
	void itemImages(ItemImages images)
	{
		SwingUtilities.invokeLater(() ->
		{
			itemImages = images == null ? ItemImages.NONE : images;
			rebuild();
		});
	}

	private void rebuild()
	{
		removeAll();
		if (view.guideOpen)
		{
			buildGuide();
		}
		else
		{
			buildList();
		}
		revalidate();
		repaint();
	}

	// --- the list ------------------------------------------------------------

	private void buildList()
	{
		add(text("Guides", bold(), Color.WHITE, CONTENT));
		add(text("Follow an osrsindex.com guide here, step by step.", small(), MUTED, CONTENT));
		add(gap(10));
		switch (view.listLoad)
		{
			case NONE:
			case LOADING:
				add(text("Loading the guides from osrsindex.com...", small(), MUTED, CONTENT));
				return;
			case NOT_SERVED:
				add(text("osrsindex.com does not offer its guides in RuneLite yet.", regular(), TEXT, CONTENT));
				add(gap(8));
				add(button("Try again", actions::retryGuides));
				return;
			case UPDATE_PLUGIN:
				add(text("Update the plugin to read osrsindex.com's guides.", regular(), ORANGE, CONTENT));
				return;
			case FAILED:
				add(text("Could not load the guides from osrsindex.com.", regular(), TEXT, CONTENT));
				add(gap(8));
				add(button("Try again", actions::retryGuides));
				return;
			case OK:
			default:
				break;
		}
		if (view.listing == null || view.listing.guides.isEmpty())
		{
			add(text("osrsindex.com offers no guides in RuneLite right now.", regular(), TEXT, CONTENT));
			return;
		}
		String followed = view.guide == null ? null : view.guide.id;
		for (Guide.Summary summary : view.listing.guides)
		{
			add(guideCard(summary, summary.id.equals(followed)));
			add(gap(6));
		}
		add(gap(4));
		add(text("Progress stays on this computer, in your RuneLite profile.", small(), FAINT, CONTENT));
	}

	private JComponent guideCard(Guide.Summary summary, boolean following)
	{
		JPanel card = clickable(BOX, BOX_HOVER, () -> actions.openGuide(summary.id));
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(BorderFactory.createCompoundBorder(following ? BorderFactory.createMatteBorder(0, 3, 0, 0, ORANGE)
			: BorderFactory.createEmptyBorder(0, 3, 0, 0), BorderFactory.createEmptyBorder(7, 8, 8, 8)));
		int width = CONTENT - 19;
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel chip = following ? chip("FOLLOWING", ORANGE, BOX) : null;
		int chipWidth = chip == null ? 0 : chip.getPreferredSize().width + 6;
		top.add(text(summary.title, bold(), Color.WHITE, width - chipWidth), BorderLayout.CENTER);
		if (chip != null)
		{
			top.add(top(chip), BorderLayout.EAST);
		}
		card.add(fit(top));
		if (summary.summary != null)
		{
			card.add(gap(3));
			card.add(text(summary.summary, regular(), TEXT, width));
		}
		String about = about(summary);
		if (!about.isEmpty())
		{
			card.add(gap(4));
			card.add(text(about, small(), MUTED, width));
		}
		return fit(card);
	}

	/** "43 steps, about 9 h, members." */
	static String about(Guide.Summary summary)
	{
		List<String> parts = new ArrayList<>();
		if (summary.steps >= 0)
		{
			parts.add(summary.steps + (summary.steps == 1 ? " step" : " steps"));
		}
		if (summary.minutes > 0)
		{
			parts.add("about " + duration(summary.minutes));
		}
		if ("free".equals(summary.membership))
		{
			parts.add("free to play");
		}
		else if ("members".equals(summary.membership))
		{
			parts.add("members");
		}
		return parts.isEmpty() ? "" : capitalised(String.join(", ", parts)) + ".";
	}

	static String duration(int minutes)
	{
		if (minutes < 60)
		{
			return minutes + " min";
		}
		int hours = (minutes + 30) / 60;
		return hours + " h";
	}

	// --- one guide -----------------------------------------------------------

	private void buildGuide()
	{
		add(backLink());
		add(gap(8));
		Guide guide = view.guide;
		if (guide == null || view.guideLoad != Load.OK)
		{
			switch (view.guideLoad)
			{
				case NOT_SERVED:
					// Taken off the list, or listed but not served: either way, nothing to show until the site has it.
					add(text("osrsindex.com does not offer this guide right now.", regular(), TEXT, CONTENT));
					add(gap(8));
					add(button("Try again", actions::retryGuides));
					break;
				case UPDATE_PLUGIN:
					add(text("Update the plugin to read this guide.", regular(), ORANGE, CONTENT));
					break;
				case FAILED:
					add(text("Could not load this guide from osrsindex.com.", regular(), TEXT, CONTENT));
					add(gap(8));
					add(button("Try again", actions::retryGuides));
					break;
				default:
					add(text("Loading the guide from osrsindex.com...", small(), MUTED, CONTENT));
					break;
			}
			return;
		}

		standing = GuideRules.standing(guide, view.progress, view.facts);
		GuideRules.Count count = standing.count;
		String currentId = count.current == null ? null : count.current.id;
		if (!guide.id.equals(shownGuide) || !Objects.equals(currentId, shownCurrent))
		{
			// A new guide, or the guide moved on: open the current step's section, and fold the step it left.
			if (!guide.id.equals(shownGuide))
			{
				openSections.clear();
				openSteps.clear();
			}
			else if (shownCurrent != null)
			{
				openSteps.remove(shownCurrent);
			}
			shownGuide = guide.id;
			shownCurrent = currentId;
			if (currentId != null)
			{
				openSections.add(sectionOf(guide, currentId).id);
			}
			else if (!guide.sections.isEmpty() && openSections.isEmpty())
			{
				openSections.add(guide.sections.get(0).id);
			}
		}

		List<Guide.Step> all = new ArrayList<>();
		List<Mark> marks = new ArrayList<>();
		for (Guide.Section section : guide.sections)
		{
			for (Guide.Step step : section.steps)
			{
				all.add(step);
				marks.add(mark(step, step.id.equals(currentId)));
			}
		}

		add(header(guide, count, all, marks));
		add(gap(12));
		if (count.current == null && view.loggedIn && count.total > 0)
		{
			add(callout("GUIDE COMPLETE", DONE, "Every step of " + guide.title + " is done or skipped.", CONTENT - 15));
			add(gap(10));
		}

		Route route = new Route();
		int number = 0;
		for (Guide.Section section : guide.sections)
		{
			int done = 0;
			int total = 0;
			for (Guide.Step step : section.steps)
			{
				GuideRules.Status status = standing.of(step);
				if (status != GuideRules.Status.SKIPPED)
				{
					total++;
					done += status == GuideRules.Status.DONE || status == GuideRules.Status.PASSED ? 1 : 0;
				}
			}
			boolean open = openSections.contains(section.id);
			route.add(this, stationRow(section, open, done, total), null);
			for (Guide.Step step : section.steps)
			{
				Mark mark = marks.get(number);
				number++;
				if (!open)
				{
					route.pass(mark);
					continue;
				}
				boolean current = step.id.equals(currentId);
				boolean expanded = current || openSteps.contains(step.id);
				route.add(this, stepRow(step, mark, expanded), mark);
				if (expanded)
				{
					route.add(this, cardRow(step, current), Mark.CARD);
				}
			}
		}
		route.end();
	}

	private JComponent backLink()
	{
		JPanel row = clickable(PANEL, PANEL_HOVER, actions::showGuideList);
		row.setLayout(new BorderLayout());
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		JLabel label = new JLabel("All guides", GuideIcons.back(MUTED), SwingConstants.LEFT);
		label.setIconTextGap(6);
		label.setFont(small());
		label.setForeground(MUTED);
		row.add(label, BorderLayout.WEST);
		return fit(row);
	}

	/** The title and count, a strip of one segment per step, and where the current step is. */
	private JComponent header(Guide guide, GuideRules.Count count, List<Guide.Step> all, List<Mark> marks)
	{
		JPanel out = column();
		JPanel top = new JPanel(new BorderLayout(8, 0));
		top.setOpaque(false);
		JLabel tally = new JLabel(view.loggedIn ? count.done + "/" + count.total : "");
		tally.setFont(bold());
		tally.setForeground(DONE);
		top.add(top(tally), BorderLayout.EAST);
		top.add(text(guide.title, bold(), Color.WHITE, CONTENT - 8 - tally.getPreferredSize().width), BorderLayout.CENTER);
		out.add(fit(top));
		if (guide.attribution != null)
		{
			out.add(gap(2));
			out.add(text(guide.attribution, small(), FAINT, CONTENT));
		}
		out.add(gap(7));
		out.add(fit(new Strip(marks)));
		out.add(gap(4));
		JPanel under = new JPanel(new BorderLayout(6, 0));
		under.setOpaque(false);
		String left;
		String right;
		if (!view.loggedIn)
		{
			left = "Log in to see and keep your progress.";
			right = "";
		}
		else if (count.current == null)
		{
			left = count.total > 0 ? "Guide complete" : "";
			right = "";
		}
		else
		{
			left = sectionOf(guide, count.current.id).title;
			right = "step " + (all.indexOf(count.current) + 1) + " of " + all.size();
		}
		JLabel rightLabel = new JLabel(right);
		rightLabel.setFont(small());
		rightLabel.setForeground(MUTED);
		under.add(top(rightLabel), BorderLayout.EAST);
		under.add(text(left, small(), count.current == null && view.loggedIn ? DONE : MUTED,
			CONTENT - 6 - rightLabel.getPreferredSize().width), BorderLayout.CENTER);
		out.add(fit(under));
		if (view.loggedIn && !standing.behind.isEmpty())
		{
			int behind = standing.behind.size();
			Guide.Step first = standing.behind.get(0);
			out.add(gap(5));
			out.add(link((behind == 1 ? "1 earlier step is" : behind + " earlier steps are") + " not done: show",
				PARTLY, Color.WHITE, () ->
				{
					openSteps.add(first.id);
					openSections.add(sectionOf(guide, first.id).id);
					rebuild();
				}));
		}
		return fit(out);
	}

	private static Guide.Section sectionOf(Guide guide, String stepId)
	{
		for (Guide.Section section : guide.sections)
		{
			for (Guide.Step step : section.steps)
			{
				if (step.id.equals(stepId))
				{
					return section;
				}
			}
		}
		return guide.sections.get(0);
	}

	private Mark mark(Guide.Step step, boolean current)
	{
		if (current)
		{
			return Mark.CURRENT;
		}
		switch (standing.of(step))
		{
			case DONE:
				return Mark.DONE;
			case SKIPPED:
				return Mark.SKIPPED;
			case PASSED:
				return Mark.PASSED;
			case BEHIND:
				return Mark.BEHIND;
			case OPEN:
			default:
				return Mark.OPEN;
		}
	}

	// --- the route -----------------------------------------------------------

	/** A section on the route: a diamond on the line, its name, and how many of its steps are done. */
	private JComponent stationRow(Guide.Section section, boolean open, int done, int total)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		JLabel arrow = new JLabel(GuideIcons.chevron(open, FAINT));
		arrow.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		JLabel tally = new JLabel(done + "/" + total);
		tally.setFont(small());
		tally.setForeground(total > 0 && done == total ? DONE : FAINT);
		JPanel east = new JPanel();
		east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
		east.setOpaque(false);
		east.add(tally);
		east.add(Box.createRigidArea(new Dimension(6, 0)));
		east.add(arrow);
		row.add(top(east), BorderLayout.EAST);
		row.add(text(section.title.toUpperCase(Locale.ROOT), small(), MUTED, RIGHT - 8 - east.getPreferredSize().width),
			BorderLayout.CENTER);
		row.putClientProperty(Route.ACTION, (Runnable) () ->
		{
			if (!openSections.remove(section.id))
			{
				openSections.add(section.id);
			}
			rebuild();
		});
		return row;
	}

	/** A step on the route: its title, how long it takes, and its badges. */
	private JComponent stepRow(Guide.Step step, Mark mark, boolean expanded)
	{
		boolean current = mark == Mark.CURRENT;
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));
		String title = OsrsIndexPanel.escape(step.title);
		Color colour;
		switch (mark)
		{
			case CURRENT:
				colour = ORANGE;
				break;
			case DONE:
				colour = new Color(140, 140, 140);
				break;
			case SKIPPED:
				colour = new Color(100, 100, 100);
				title = "<s>" + title + "</s>";
				break;
			case PASSED:
				colour = new Color(120, 130, 120);
				break;
			case BEHIND:
				colour = TEXT;
				title += " <font color='" + hex(PARTLY) + "'>not done</font>";
				break;
			default:
				colour = TEXT;
				break;
		}
		if (step.minutes > 0 && (mark == Mark.CURRENT || mark == Mark.OPEN))
		{
			title += " <font color='" + hex(FAINT) + "'>" + duration(step.minutes) + "</font>";
		}
		JComponent badges = badges(step);
		row.add(top(badges), BorderLayout.EAST);
		row.add(html(title, current ? bold() : regular(), colour, RIGHT - 8 - badges.getPreferredSize().width),
			BorderLayout.CENTER);
		if (!current)
		{
			row.putClientProperty(Route.ACTION, (Runnable) () ->
			{
				if (!openSteps.remove(step.id))
				{
					openSteps.add(step.id);
				}
				rebuild();
			});
		}
		return row;
	}

	/** At a row's end: the quest icon on a quest step, a bag for the items to bring, a pin when it has a map point. */
	private JComponent badges(Guide.Step step)
	{
		JPanel out = new JPanel();
		out.setLayout(new BoxLayout(out, BoxLayout.X_AXIS));
		out.setOpaque(false);
		out.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
		List<JComponent> parts = new ArrayList<>();
		if (QUEST_ICON != null && !step.quests.isEmpty())
		{
			JLabel quest = new JLabel(new ImageIcon(QUEST_ICON.getScaledInstance(12, 12, java.awt.Image.SCALE_SMOOTH)));
			quest.setToolTipText("A quest step");
			parts.add(quest);
		}
		if (!step.bring.isEmpty())
		{
			Color colour = bagColour(step);
			JLabel bag = new JLabel(GuideIcons.bag(colour));
			bag.setToolTipText(bagWords(step));
			parts.add(bag);
		}
		if (step.point() != null)
		{
			JLabel pin = new JLabel(GuideIcons.pin(true, 12));
			pin.setToolTipText("Has a map point");
			parts.add(pin);
		}
		for (int i = 0; i < parts.size(); i++)
		{
			if (i > 0)
			{
				out.add(Box.createRigidArea(new Dimension(3, 0)));
			}
			out.add(parts.get(i));
		}
		return out;
	}

	/** Green when you carry every item, orange when the rest are in the bank, red when one is missing, grey unknown. */
	private Color bagColour(Guide.Step step)
	{
		if (!view.loggedIn)
		{
			return FAINT;
		}
		boolean banked = false;
		boolean unknown = false;
		for (Guide.Item item : step.bring)
		{
			switch (GuideRules.item(item, view.facts))
			{
				case MISSING:
					return MISSING;
				case BANKED:
					banked = true;
					break;
				case CARRIED:
					break;
				default:
					unknown = true;
					break;
			}
		}
		return banked ? PARTLY : unknown ? FAINT : DONE;
	}

	private String bagWords(Guide.Step step)
	{
		List<String> names = new ArrayList<>();
		for (Guide.Item item : step.bring)
		{
			String word = haveWord(GuideRules.item(item, view.facts), view.loggedIn);
			names.add(itemText(item) + (word.isEmpty() ? "" : " (" + word + ")"));
		}
		return "Bring: " + String.join(", ", names);
	}

	/** A step's card, hanging off the route line. The current step's card has the large Mark done button. */
	private JComponent cardRow(Guide.Step step, boolean current)
	{
		GuideRules.Status status = standing.of(step);
		JPanel card = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g0)
			{
				Graphics2D g = smooth(g0);
				g.setColor(BOX);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
				g.dispose();
			}
		};
		card.setOpaque(false);
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBorder(BorderFactory.createEmptyBorder(8, CARD_PAD, 9, CARD_PAD));
		boolean any = false;

		List<JLabel> chips = new ArrayList<>();
		for (String tag : step.tags)
		{
			switch (tag)
			{
				case "risk":
					chips.add(chip("RISKY: YOU CAN DIE", MISSING, BOX));
					break;
				case "solo":
					chips.add(chip("SOLO", MUTED, BOX));
					break;
				case "optional":
					chips.add(chip("OPTIONAL", MUTED, BOX));
					break;
				default:
					chips.add(chip(tag.replace('_', ' ').toUpperCase(Locale.ROOT), MUTED, BOX));
					break;
			}
		}
		if (!chips.isEmpty())
		{
			card.add(chipRows(chips, CARD_TEXT));
			any = true;
		}

		if (!current && status == GuideRules.Status.DONE)
		{
			card.add(gap(any ? 7 : 0));
			card.add(callout("DONE", DONE, GuideRules.completed(step, view.facts) ? "Your character has done this."
				: "You marked this step done.", CARD_TEXT - 15));
			any = true;
		}
		else if (!current && status == GuideRules.Status.SKIPPED)
		{
			card.add(gap(any ? 7 : 0));
			card.add(callout("SKIPPED", MUTED, "Left out of the count.", CARD_TEXT - 15));
			any = true;
		}
		else if (status == GuideRules.Status.PASSED)
		{
			card.add(gap(any ? 7 : 0));
			card.add(callout("COUNTED AS DONE", PASSED_GREEN, "You are past this step: a later one is done, and nothing in"
				+ " the game shows this one either way.", CARD_TEXT - 15));
			any = true;
		}
		else if (status == GuideRules.Status.BEHIND)
		{
			card.add(gap(any ? 7 : 0));
			card.add(callout("NOT DONE YET", PARTLY, GuideRules.checkable(step)
				? "A later step is done, but your character has not done this one."
				: "You marked it not done.", CARD_TEXT - 15));
			any = true;
		}

		// A quest step Quest Helper guides says so first, with its button, as the way to follow it.
		String handOffText = view.handOff(step).panelText();
		if (handOffText != null)
		{
			card.add(gap(any ? 7 : 0));
			card.add(questHelper(step, handOffText));
			any = true;
		}
		if (step.text != null)
		{
			card.add(gap(any ? 7 : 0));
			card.add(text(step.text, regular(), Color.WHITE, CARD_TEXT));
			any = true;
		}
		if (!step.how.isEmpty())
		{
			card.add(gap(any ? 6 : 0));
			for (int i = 0; i < step.how.size(); i++)
			{
				if (i > 0)
				{
					card.add(gap(3));
				}
				card.add(numbered(i + 1, step.how.get(i)));
			}
			any = true;
		}
		if (step.ironman != null && view.facts.ironman())
		{
			card.add(gap(7));
			card.add(callout("IRONMAN", IRON, step.ironman, CARD_TEXT - 15));
		}
		if (step.tip != null)
		{
			card.add(gap(7));
			card.add(callout("TIP", MUTED, step.tip, CARD_TEXT - 15));
		}

		if (!step.bring.isEmpty())
		{
			int carried = 0;
			int checked = 0;
			List<JComponent> lines = new ArrayList<>();
			for (Guide.Item item : step.bring)
			{
				GuideRules.Have have = GuideRules.item(item, view.facts);
				if (view.loggedIn && have != GuideRules.Have.NO_ID)
				{
					checked++;
					carried += have == GuideRules.Have.CARRIED ? 1 : 0;
				}
				lines.add(itemLine(item, haveTag(have, view.loggedIn, false), haveTagColour(have, view.loggedIn, false),
					null));
			}
			String tally = !view.loggedIn ? "log in to check" : checked == 0 ? null : carried + " of " + checked
				+ " in inventory";
			card.add(heading("BRING", tally, !view.loggedIn ? FAINT : carried == checked ? DONE : PARTLY));
			addLines(card, folded(step.id, lines));
		}
		if (!step.collect.isEmpty())
		{
			card.add(heading("PICK UP ON THE WAY", null, null));
			List<JComponent> lines = new ArrayList<>();
			for (Guide.Collect collect : step.collect)
			{
				GuideRules.Have have = GuideRules.item(collect.item, view.facts);
				List<String> about = new ArrayList<>();
				if (collect.from != null)
				{
					about.add("from " + collect.from);
				}
				if (!collect.forQuests.isEmpty())
				{
					about.add("for " + String.join(" and ", collect.forQuests));
				}
				lines.add(itemLine(collect.item, haveTag(have, view.loggedIn, true), haveTagColour(have, view.loggedIn, true),
					about.isEmpty() ? null : String.join(", ", about)));
			}
			addLines(card, lines);
		}
		if (!step.places.isEmpty() || !step.travel.isEmpty())
		{
			card.add(heading("WHERE", null, null));
			boolean first = true;
			for (Guide.Place place : step.places)
			{
				card.add(gap(first ? 0 : 3));
				first = false;
				card.add(row(GuideIcons.pin(place.hasPoint(), 16), html(OsrsIndexPanel.escape(place.label)
					+ (place.hasPoint() ? "" : status("no map point yet", FAINT)), regular(), TEXT, ROW_TEXT)));
			}
			for (Guide.Place travel : step.travel)
			{
				card.add(gap(first ? 0 : 3));
				first = false;
				Icon icon = TRAVEL_ICON != null ? new ImageIcon(TRAVEL_ICON) : GuideIcons.pin(travel.hasPoint(), 16);
				card.add(row(icon, html(OsrsIndexPanel.escape(travel.label), regular(), TEXT,
					CARD_TEXT - icon.getIconWidth() - 7)));
			}
		}
		if (!step.requires.isEmpty())
		{
			card.add(heading("NEEDS", null, null));
			List<JLabel> needs = new ArrayList<>();
			for (Guide.Requirement requirement : step.requires)
			{
				GuideRules.Met met = GuideRules.requirement(requirement, view.facts);
				String name = requirementName(requirement);
				JLabel need = dotChip(name == null ? "Check on osrsindex.com" : name, metColour(met));
				need.setToolTipText(requirementText(requirement, met));
				needs.add(need);
			}
			card.add(chipRows(needs, CARD_TEXT));
		}
		if (step.rewards != null && !step.rewards.isEmpty())
		{
			card.add(heading("REWARDS", null, null));
			card.add(text(rewardsText(step.rewards), small(), MUTED, CARD_TEXT));
		}

		card.add(gap(10));
		if (current && view.loggedIn)
		{
			card.add(fit(new MarkDone(() -> actions.markStep(step.id, GuideProgress.Mark.DONE)), 32));
			card.add(gap(6));
		}
		else if (!view.loggedIn)
		{
			card.add(text("Log in to mark steps.", small(), MUTED, CARD_TEXT));
			card.add(gap(6));
		}
		List<JButton> buttons = new ArrayList<>();
		if (view.loggedIn && (status == GuideRules.Status.OPEN || status == GuideRules.Status.BEHIND))
		{
			if (!current)
			{
				JButton done = button("Done", () -> actions.markStep(step.id, GuideProgress.Mark.DONE));
				done.setForeground(DONE);
				buttons.add(done);
			}
			buttons.add(button("Skip", () -> actions.markStep(step.id, GuideProgress.Mark.SKIPPED)));
		}
		else if (view.loggedIn && status == GuideRules.Status.PASSED)
		{
			buttons.add(button("Not done", () -> actions.markStep(step.id, GuideProgress.Mark.OPEN)));
		}
		else if (view.loggedIn && !(status == GuideRules.Status.DONE && GuideRules.completed(step, view.facts)))
		{
			buttons.add(button("Reopen", () -> actions.markStep(step.id, null)));
		}
		if (step.point() != null)
		{
			buttons.add(button(buttons.size() >= 2 ? "Walk" : "Walk there", () -> actions.walkTo(step.id)));
		}
		if (!buttons.isEmpty())
		{
			JPanel row = new JPanel(new GridLayout(1, buttons.size(), 5, 0));
			row.setOpaque(false);
			for (JButton b : buttons)
			{
				row.add(b);
			}
			card.add(fit(row, 28));
			card.add(gap(6));
		}
		if (view.loggedIn && !current && status == GuideRules.Status.OPEN)
		{
			// For a player who followed the guide by hand: everything before this step is behind them.
			JButton start = button("Start here", () -> actions.startHere(step.id));
			start.setForeground(ORANGE);
			start.setToolTipText("Every step before this one counts as done, unless the game shows it is not.");
			card.add(fit(start, 28));
			card.add(gap(6));
		}
		if (step.url != null)
		{
			card.add(link("Open on osrsindex.com", MUTED, Color.WHITE, () -> LinkBrowser.browse(step.url)));
		}
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setBorder(BorderFactory.createEmptyBorder(3, 0, 6, 0));
		holder.add(card, BorderLayout.CENTER);
		return holder;
	}

	private JComponent questHelper(Guide.Step step, String words)
	{
		JPanel pill = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g0)
			{
				Graphics2D g = smooth(g0);
				g.setColor(PILL);
				g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
				g.dispose();
			}
		};
		pill.setOpaque(false);
		pill.setLayout(new BoxLayout(pill, BoxLayout.Y_AXIS));
		pill.setBorder(BorderFactory.createEmptyBorder(6, 7, 7, 7));
		int width = CARD_TEXT - 14;
		pill.add(QUEST_ICON == null ? text("QUEST HELPER", small(), ORANGE, width)
			: row(new ImageIcon(QUEST_ICON), text("QUEST HELPER", small(), ORANGE, width - 21)));
		pill.add(gap(3));
		pill.add(text(words, small(), new Color(255, 205, 130), width));
		if (view.questHelperSelected != null)
		{
			pill.add(gap(3));
			pill.add(text("It shows: " + view.questHelperSelected + ".", small(), MUTED, width));
		}
		pill.add(gap(6));
		pill.add(button("Follow with Quest Helper", () -> actions.followWithQuestHelper(step.id)));
		return fit(pill);
	}

	/**
	 * One item, as a line: its game sprite at half size, its name, and a tag at the right end saying where it is:
	 * INVENTORY, BANK, DON'T HAVE. A second line, when given, says where a pick-up is from and what it is for.
	 */
	private JComponent itemLine(Guide.Item item, String tag, Color tagColour, String second)
	{
		Sprite sprite = new Sprite();
		sprite.setToolTipText(itemText(item));
		if (item.id > 0)
		{
			itemImages.show(item.id, sprite);
		}
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(top(sprite), BorderLayout.WEST);
		int tagWidth = 0;
		if (tag != null)
		{
			JLabel label = chip(tag, tagColour, BOX);
			tagWidth = label.getPreferredSize().width + 6;
			JPanel east = new JPanel(new BorderLayout());
			east.setOpaque(false);
			east.setBorder(BorderFactory.createEmptyBorder(1, 0, 0, 0));
			east.add(label, BorderLayout.NORTH);
			row.add(east, BorderLayout.EAST);
		}
		row.add(text(itemText(item), regular(), TEXT, CARD_TEXT - Sprite.W - 6 - tagWidth), BorderLayout.CENTER);
		if (second == null)
		{
			return fit(row);
		}
		// Where a pick-up is from runs under its name, the width of the line, not squeezed beside the tag.
		JPanel out = column();
		out.add(fit(row));
		JLabel under = text(second, small(), FAINT, CARD_TEXT - Sprite.W - 6);
		under.setBorder(BorderFactory.createEmptyBorder(0, Sprite.W + 6, 0, 0));
		out.add(under);
		return fit(out);
	}

	/** A long item list: its first {@link #FOLDED_ITEMS} lines and a link to the rest, until the player opens it. */
	private List<JComponent> folded(String stepId, List<JComponent> lines)
	{
		if (lines.size() <= FOLDED_ITEMS + 2 || allItems.contains(stepId))
		{
			return lines;
		}
		List<JComponent> out = new ArrayList<>(lines.subList(0, FOLDED_ITEMS));
		out.add(link("Show all " + lines.size() + " items", MUTED, Color.WHITE, () ->
		{
			allItems.add(stepId);
			rebuild();
		}));
		return out;
	}

	private static void addLines(JPanel card, List<JComponent> lines)
	{
		for (int i = 0; i < lines.size(); i++)
		{
			if (i > 0)
			{
				card.add(gap(3));
			}
			card.add(lines.get(i));
		}
	}

	/**
	 * Lays the route out: each row gets the line's column on its left, with the node for its step, the line above it
	 * coloured by the step before and below it by this one. Folded steps still pass their colour on.
	 */
	private static final class Route
	{
		static final String ACTION = "osrsindex.route.action";

		private Mark previous;
		private boolean first = true;
		private RouteRow last;

		void add(JPanel into, JComponent content, Mark mark)
		{
			RouteRow row = new RouteRow(content, mark, first ? null : previous, first);
			into.add(fit(row));
			last = row;
			first = false;
			if (mark != null && mark != Mark.CARD)
			{
				previous = mark;
			}
		}

		void pass(Mark mark)
		{
			previous = mark;
		}

		void end()
		{
			if (last != null)
			{
				last.last = true;
			}
		}
	}

	/** Where a step stands, as its node on the route draws it. CARD is a step's card, which only carries the line. */
	enum Mark
	{
		DONE,
		CURRENT,
		OPEN,
		SKIPPED,
		/** Counted as done: the player is past it, and nothing shows it either way. */
		PASSED,
		/** Before the player's place, and not done. */
		BEHIND,
		CARD
	}

	private static Color lineBelow(Mark mark)
	{
		if (mark == null)
		{
			return LINE;
		}
		switch (mark)
		{
			case DONE:
			case PASSED:
			case BEHIND:
				// The way the player has come: green up to their place.
				return DONE;
			case CURRENT:
				return ORANGE;
			default:
				return LINE;
		}
	}

	/** One row of the route: the line and its node in the left column, the content on the right. */
	private static final class RouteRow extends JPanel
	{
		private final Mark mark;
		private final Mark above;
		private final boolean first;
		boolean last;
		private boolean hover;

		RouteRow(JComponent content, Mark mark, Mark above, boolean first)
		{
			super(new BorderLayout());
			this.mark = mark;
			this.above = above;
			this.first = first;
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(mark == null && !first ? 8 : 3, GUTTER, 3, 0));
			if (mark == Mark.CARD)
			{
				setBorder(BorderFactory.createEmptyBorder(0, GUTTER, 0, 0));
			}
			add(content, BorderLayout.CENTER);
			Object action = content.getClientProperty(Route.ACTION);
			if (action instanceof Runnable)
			{
				setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						hover = true;
						repaint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hover = false;
						repaint();
					}

					@Override
					public void mouseReleased(MouseEvent e)
					{
						if (SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint()))
						{
							((Runnable) action).run();
						}
					}
				});
			}
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = smooth(g0);
			int h = getHeight();
			if (hover)
			{
				g.setColor(PANEL_HOVER);
				g.fillRoundRect(GUTTER - 4, 0, getWidth() - GUTTER + 4, h, 6, 6);
			}
			int cy = mark == null ? 13 : 11;
			if (mark == Mark.CARD)
			{
				// The current step's line runs on beside its card.
				g.setStroke(new BasicStroke(2f));
				g.setColor(lineBelow(above));
				if (!last)
				{
					g.drawLine(LINE_X, 0, LINE_X, h);
				}
				g.dispose();
				return;
			}
			g.setStroke(new BasicStroke(2f));
			if (!first)
			{
				g.setColor(lineBelow(above));
				g.drawLine(LINE_X, 0, LINE_X, cy);
			}
			if (!last)
			{
				Mark carried = mark == null ? above : mark;
				if (carried == Mark.SKIPPED)
				{
					g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{3f, 3f}, 0f));
				}
				g.setColor(lineBelow(carried));
				g.drawLine(LINE_X, cy, LINE_X, h);
			}
			g.setStroke(new BasicStroke(2f));
			node(g, mark, cy);
			g.dispose();
		}

		private static void node(Graphics2D g, Mark mark, int cy)
		{
			int x = LINE_X;
			if (mark == null)
			{
				Path2D diamond = new Path2D.Float();
				diamond.moveTo(x, cy - 5);
				diamond.lineTo(x + 5, cy);
				diamond.lineTo(x, cy + 5);
				diamond.lineTo(x - 5, cy);
				diamond.closePath();
				g.setColor(MUTED);
				g.fill(diamond);
				return;
			}
			switch (mark)
			{
				case CURRENT:
					g.setColor(new Color(220, 138, 0, 70));
					g.fillOval(x - 11, cy - 11, 22, 22);
					g.setColor(ORANGE);
					g.fillOval(x - 7, cy - 7, 14, 14);
					g.setColor(GuideIcons.INK);
					g.fillOval(x - 2, cy - 2, 4, 4);
					break;
				case DONE:
					g.setColor(DONE);
					g.fillOval(x - 7, cy - 7, 14, 14);
					g.setColor(GuideIcons.INK);
					g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					Path2D tick = new Path2D.Float();
					tick.moveTo(x - 3.5, cy);
					tick.lineTo(x - 1, cy + 2.5);
					tick.lineTo(x + 3.5, cy - 2.5);
					g.draw(tick);
					break;
				case SKIPPED:
					g.setColor(PANEL);
					g.fillOval(x - 6, cy - 6, 12, 12);
					g.setColor(new Color(100, 100, 100));
					g.drawOval(x - 6, cy - 6, 12, 12);
					g.drawLine(x - 3, cy, x + 3, cy);
					break;
				case PASSED:
				{
					g.setColor(PANEL);
					g.fillOval(x - 7, cy - 7, 14, 14);
					g.setColor(PASSED_GREEN.brighter());
					g.setStroke(new BasicStroke(1.5f));
					g.drawOval(x - 6, cy - 6, 12, 12);
					g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					Path2D passed = new Path2D.Float();
					passed.moveTo(x - 3, cy);
					passed.lineTo(x - 1, cy + 2);
					passed.lineTo(x + 3, cy - 2);
					g.draw(passed);
					break;
				}
				case BEHIND:
					g.setColor(PANEL);
					g.fillOval(x - 7, cy - 7, 14, 14);
					g.setColor(PARTLY);
					g.setStroke(new BasicStroke(1.8f));
					g.drawOval(x - 6, cy - 6, 12, 12);
					g.fillRect(x - 1, cy - 4, 2, 5);
					g.fillRect(x - 1, cy + 2, 2, 2);
					break;
				case OPEN:
				default:
					g.setColor(PANEL);
					g.fillOval(x - 6, cy - 6, 12, 12);
					g.setColor(new Color(140, 140, 140));
					g.drawOval(x - 6, cy - 6, 12, 12);
					break;
			}
		}
	}

	/** One segment per step: green done, orange current, grey to do, hatched skipped; one bar when there are many. */
	private static final class Strip extends JComponent
	{
		private final List<Mark> marks;

		Strip(List<Mark> marks)
		{
			this.marks = marks;
			setPreferredSize(new Dimension(CONTENT, 8));
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = smooth(g0);
			int n = marks.size();
			int w = getWidth();
			int h = getHeight();
			int gap = n <= 40 ? 2 : 0;
			double each = n == 0 ? w : (w - gap * (n - 1)) / (double) n;
			if (gap == 0)
			{
				g.setColor(BOX);
				g.fillRoundRect(0, 0, w, h, 3, 3);
			}
			for (int i = 0; i < n; i++)
			{
				int x = (int) Math.round(i * (each + gap));
				int x2 = (int) Math.round(i * (each + gap) + each);
				Mark mark = marks.get(i);
				Color colour = mark == Mark.DONE ? DONE : mark == Mark.CURRENT ? ORANGE : mark == Mark.PASSED ? PASSED_GREEN
					: mark == Mark.BEHIND ? PARTLY : mark == Mark.SKIPPED ? null : BOX;
				if (colour == null)
				{
					g.setColor(BOX);
					g.fillRect(x, 0, Math.max(1, x2 - x), h);
					g.setColor(new Color(85, 85, 85));
					g.setClip(x, 0, Math.max(1, x2 - x), h);
					for (int k = x - h; k < x2; k += 4)
					{
						g.drawLine(k, h, k + h, 0);
					}
					g.setClip(null);
				}
				else if (gap > 0 || colour != BOX)
				{
					g.setColor(colour);
					g.fillRect(x, 0, Math.max(gap > 0 ? 1 : 2, x2 - x), h);
				}
			}
			g.dispose();
		}
	}

	// --- text ----------------------------------------------------------------

	/** "About 10 min. Optional, risky." */
	static String stepAbout(Guide.Step step)
	{
		List<String> tags = new ArrayList<>();
		for (String tag : step.tags)
		{
			switch (tag)
			{
				case "risk":
					tags.add("risky: you can die");
					break;
				case "solo":
					tags.add("solo");
					break;
				case "optional":
					tags.add("optional");
					break;
				default:
					tags.add(tag.replace('_', ' '));
					break;
			}
		}
		String out = step.minutes > 0 ? "About " + duration(step.minutes) + "." : "";
		if (!tags.isEmpty())
		{
			out += (out.isEmpty() ? "" : " ") + capitalised(String.join(", ", tags)) + ".";
		}
		return out;
	}

	static String itemText(Guide.Item item)
	{
		return item.qty > 1 ? item.name + " x" + item.qty : item.name;
	}

	static String haveText(GuideRules.Have have, boolean loggedIn)
	{
		if (!loggedIn)
		{
			return "";
		}
		switch (have)
		{
			case CARRIED:
				return ": with you";
			case BANKED:
				return ": in your bank";
			case MISSING:
				return ": missing";
			case BANK_UNSEEN:
				return ": not with you. Open your bank to check it";
			case NO_ID:
			default:
				return "";
		}
	}

	/** An item's state, in the bag badge's tooltip: "in inventory", "in bank", "don't have". */
	static String haveWord(GuideRules.Have have, boolean loggedIn)
	{
		if (!loggedIn)
		{
			return "";
		}
		switch (have)
		{
			case CARRIED:
				return "in inventory";
			case BANKED:
				return "in bank";
			case MISSING:
				return "don't have";
			case BANK_UNSEEN:
				return "open your bank to check";
			case NO_ID:
			default:
				return "";
		}
	}

	/**
	 * The tag at the end of an item's line: INVENTORY, BANK, DON'T HAVE (TO GET for a pick-up), CHECK BANK while the
	 * bank is unseen since login, CAN'T CHECK when the site gives no item id. None while logged out.
	 */
	static String haveTag(GuideRules.Have have, boolean loggedIn, boolean pickUp)
	{
		if (!loggedIn)
		{
			return null;
		}
		switch (have)
		{
			case CARRIED:
				return "INVENTORY";
			case BANKED:
				return "BANK";
			case MISSING:
				return pickUp ? "TO GET" : "DON'T HAVE";
			case BANK_UNSEEN:
				return "CHECK BANK";
			case NO_ID:
			default:
				return "CAN'T CHECK";
		}
	}

	private static Color haveTagColour(GuideRules.Have have, boolean loggedIn, boolean pickUp)
	{
		Color colour = haveColour(have, loggedIn);
		if (have == GuideRules.Have.MISSING && pickUp)
		{
			return ORANGE;
		}
		return colour == null ? MUTED : colour;
	}

	private static Color haveColour(GuideRules.Have have, boolean loggedIn)
	{
		if (!loggedIn)
		{
			return null;
		}
		switch (have)
		{
			case CARRIED:
				return DONE;
			case BANKED:
				return PARTLY;
			case MISSING:
				return MISSING;
			default:
				return null;
		}
	}

	static String collectText(Guide.Collect collect)
	{
		String out = itemText(collect.item);
		if (!collect.forQuests.isEmpty())
		{
			out += " for " + String.join(" and ", collect.forQuests);
		}
		if (collect.from != null)
		{
			out += ", from " + collect.from;
		}
		return out;
	}

	/** A requirement's name, "Cooking 10 (boostable)", or null for a kind this version cannot check. */
	static String requirementName(Guide.Requirement requirement)
	{
		switch (requirement.kind)
		{
			case SKILL:
				return capitalised(requirement.skill) + " " + requirement.level + (requirement.boostable ? " (boostable)" : "");
			case QUEST:
				return "Quest: " + requirement.quest;
			case QUEST_POINTS:
				return requirement.points + (requirement.points == 1 ? " quest point" : " quest points");
			case MODE:
				List<String> modes = new ArrayList<>();
				for (String mode : requirement.modes)
				{
					modes.add(modeName(mode));
				}
				return "Account: " + String.join(" or ", modes);
			case VARBIT:
			case VARP:
				return requirement.label != null ? requirement.label : "In-game progress";
			case ITEMS:
			{
				if (requirement.label != null)
				{
					return requirement.label;
				}
				List<String> names = new ArrayList<>();
				for (Guide.Item item : requirement.items)
				{
					names.add(itemText(item));
				}
				return ("carried".equals(requirement.op) ? "Carry: " : "Have: ") + String.join(", ", names);
			}
			case AREA:
				return requirement.label != null ? "Be at " + requirement.label : "Be at " + requirement.place.label;
			case TALK:
				return requirement.label != null ? requirement.label : "Talk to " + requirement.npc;
			case KILL:
				return requirement.label != null ? requirement.label
					: "Kill " + (requirement.value > 1 ? requirement.value + " " : "") + requirement.npc;
			case UNKNOWN:
			default:
				return null;
		}
	}

	/** The word after a requirement: "met", "met with a boost" or "not yet". */
	static String metWord(GuideRules.Met met)
	{
		switch (met)
		{
			case MET:
				return "met";
			case BOOST:
				return "met with a boost";
			case MISSING:
				return "not yet";
			case UNKNOWN:
			default:
				return "";
		}
	}

	static String requirementText(Guide.Requirement requirement, GuideRules.Met met)
	{
		String name = requirementName(requirement);
		if (name == null)
		{
			return "A requirement this version of the plugin cannot check. See it on osrsindex.com.";
		}
		String word = metWord(met);
		return word.isEmpty() ? name : name + ": " + word;
	}

	private static Color metColour(GuideRules.Met met)
	{
		switch (met)
		{
			case MET:
				return DONE;
			case BOOST:
				return PARTLY;
			case MISSING:
				return MISSING;
			default:
				return FAINT;
		}
	}

	/** Each reward but the unlocks: "300 Cooking xp", "1 quest point". */
	static List<String> rewardParts(Guide.Rewards rewards)
	{
		List<String> parts = new ArrayList<>();
		for (Guide.Xp xp : rewards.xp)
		{
			parts.add(String.format(Locale.ROOT, "%,d %s xp", xp.amount, capitalised(xp.skill)));
		}
		if (rewards.questPoints > 0)
		{
			parts.add(rewards.questPoints + (rewards.questPoints == 1 ? " quest point" : " quest points"));
		}
		if (rewards.coins > 0)
		{
			parts.add(String.format(Locale.ROOT, "%,d coins", rewards.coins));
		}
		for (Guide.Item item : rewards.items)
		{
			parts.add(itemText(item));
		}
		return parts;
	}

	static String rewardsText(Guide.Rewards rewards)
	{
		String out = String.join(", ", rewardParts(rewards));
		if (!rewards.unlocks.isEmpty())
		{
			out += (out.isEmpty() ? "" : ". ") + "Unlocks: " + String.join(" ", rewards.unlocks);
		}
		return out;
	}

	/** {@code hardcore_group_ironman} as "Hardcore group ironman". */
	static String modeName(String mode)
	{
		return capitalised(mode.replace('_', ' '));
	}

	static String capitalised(String text)
	{
		return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
	}

	// --- drawn parts ---------------------------------------------------------

	/** The big green button that marks the current step done. */
	private static final class MarkDone extends JButton
	{
		MarkDone(Runnable action)
		{
			super("Mark done");
			addActionListener(e -> action.run());
			setFont(bold());
			setForeground(Color.WHITE);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			setPreferredSize(new Dimension(CARD_TEXT, 32));
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = smooth(g0);
			Color fill = getModel().isPressed() ? MARK_DONE.darker() : getModel().isRollover() ? MARK_DONE.brighter()
				: MARK_DONE;
			g.setColor(fill);
			g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
			g.setColor(new Color(255, 255, 255, 40));
			g.drawLine(3, 1, getWidth() - 4, 1);
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/**
	 * An item's game sprite at half its size, once it loads: small enough to sit in a line of text. Until it loads, or
	 * when the site gives no id, it is blank space, which keeps the names lined up.
	 */
	private static final class Sprite extends JLabel
	{
		static final int W = 18;
		static final int H = 16;

		Sprite()
		{
			Dimension size = new Dimension(W, H);
			setMinimumSize(size);
			setMaximumSize(size);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(W, H);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			Icon icon = getIcon();
			if (icon instanceof ImageIcon && icon.getIconWidth() > 0)
			{
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.drawImage(((ImageIcon) icon).getImage(), 0, 0, W, H, this);
			}
			g.dispose();
		}
	}

	// --- components ----------------------------------------------------------

	private static Font regular()
	{
		return FontManager.getRunescapeFont();
	}

	private static Font small()
	{
		return FontManager.getRunescapeSmallFont();
	}

	private static Font bold()
	{
		return FontManager.getRunescapeBoldFont();
	}

	private static Graphics2D smooth(Graphics g0)
	{
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	/** One of the icons RuneLite's own plugins ship, by its path under their package, or null when it is gone. */
	private static BufferedImage resource(String path)
	{
		try (InputStream in = GuidesPanel.class.getResourceAsStream("/net/runelite/client/plugins/" + path))
		{
			return in == null ? null : ImageIO.read(in);
		}
		catch (IOException e)
		{
			return null;
		}
	}

	/**
	 * Wrapped text {@code width} pixels wide. Swing's HTML reads a CSS pixel as 4/3 of a screen pixel, so the width
	 * is given in CSS as three quarters of it.
	 */
	private static JLabel html(String html, Font font, Color colour, int width)
	{
		JLabel label = new JLabel("<html><body style='width: " + (width * 3 / 4) + "px'>" + html + "</body></html>");
		label.setFont(font);
		label.setForeground(colour);
		label.setVerticalAlignment(SwingConstants.TOP);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel text(String text, Font font, Color colour, int width)
	{
		return html(OsrsIndexPanel.escape(text), font, colour, width);
	}

	/** A coloured word after a name on the same line: "Anvils  no map point yet". */
	private static String status(String word, Color colour)
	{
		return "&nbsp; <font color='" + hex(colour) + "'>" + OsrsIndexPanel.escape(word) + "</font>";
	}

	private static String hex(Color colour)
	{
		return String.format(Locale.ROOT, "#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
	}

	/** A part's heading in a step's card, with an optional tally after it: "BRING  1 of 2 with you". */
	private static JComponent heading(String label, String tally, Color tallyColour)
	{
		JPanel out = column();
		out.add(gap(9));
		out.add(html(OsrsIndexPanel.escape(label) + (tally == null ? "" : "&nbsp; <font color='" + hex(tallyColour) + "'>"
			+ OsrsIndexPanel.escape(tally) + "</font>"), small(), FAINT, CARD_TEXT));
		out.add(gap(4));
		return fit(out);
	}

	/** A small capitalised tag on a tinted ground: "SOLO", "OPTIONAL", "FOLLOWING". */
	private static JLabel chip(String text, Color colour, Color ground)
	{
		JLabel chip = new JLabel(text);
		chip.setFont(small());
		chip.setForeground(colour);
		chip.setOpaque(true);
		chip.setBackground(blend(colour, ground, 0.16));
		chip.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
		return chip;
	}

	/** A requirement as a chip with its dot: green met, orange met with a boost, red not yet, grey unknown. */
	private static JLabel dotChip(String text, Color dot)
	{
		JLabel chip = new JLabel(text, GuideIcons.dot(dot, 12), SwingConstants.LEFT);
		chip.setIconTextGap(4);
		chip.setFont(small());
		chip.setForeground(TEXT);
		chip.setOpaque(true);
		chip.setBackground(new Color(46, 46, 46));
		chip.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 6));
		return chip;
	}

	/** Chips laid out in rows that fit {@code width}, since a flow layout would not report its wrapped height. */
	private static JComponent chipRows(List<JLabel> chips, int width)
	{
		JPanel out = column();
		JPanel line = null;
		int used = 0;
		for (JLabel chip : chips)
		{
			int w = Math.min(chip.getPreferredSize().width, width);
			if (line == null || used + 4 + w > width)
			{
				if (line != null)
				{
					out.add(fit(line));
					out.add(gap(4));
				}
				line = new JPanel();
				line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
				line.setOpaque(false);
				used = 0;
			}
			else
			{
				line.add(Box.createRigidArea(new Dimension(4, 0)));
				used += 4;
			}
			line.add(chip);
			used += w;
		}
		if (line != null)
		{
			out.add(fit(line));
		}
		return fit(out);
	}

	/** An icon, then a component beside it, the icon on the first line. */
	private static JComponent row(Icon icon, JComponent beside)
	{
		return beside(new JLabel(icon), beside);
	}

	private static JComponent beside(JComponent left, JComponent right)
	{
		JPanel row = new JPanel(new BorderLayout(7, 0));
		row.setOpaque(false);
		row.add(top(left), BorderLayout.WEST);
		row.add(right, BorderLayout.CENTER);
		return fit(row);
	}

	/** "1" and the text, hanging. */
	private static JComponent numbered(int number, String text)
	{
		JPanel row = new JPanel(new BorderLayout(5, 0));
		row.setOpaque(false);
		JLabel mark = new JLabel(Integer.toString(number));
		mark.setFont(regular());
		mark.setForeground(ORANGE);
		mark.setPreferredSize(new Dimension(9, mark.getPreferredSize().height));
		row.add(top(mark), BorderLayout.WEST);
		row.add(text(text, regular(), TEXT, CARD_TEXT - 14), BorderLayout.CENTER);
		return fit(row);
	}

	/** A note set off by a coloured bar: "TIP", "IRONMAN", "DONE", "GUIDE COMPLETE". */
	private static JPanel callout(String label, Color colour, String text, int width)
	{
		JPanel out = column();
		out.setOpaque(true);
		out.setBackground(CALLOUT);
		out.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 2, 0, 0, colour),
			BorderFactory.createEmptyBorder(5, 7, 6, 6)));
		JLabel heading = text(label, small(), colour, width);
		if (label.equals("IRONMAN") && IRONMAN_ICON != null)
		{
			out.add(row(new ImageIcon(IRONMAN_ICON), text(label, small(), colour, width - 20)));
		}
		else
		{
			out.add(heading);
		}
		out.add(gap(2));
		out.add(text(text, regular(), TEXT, width));
		return (JPanel) fit(out);
	}

	private static Color blend(Color top, Color ground, double amount)
	{
		return new Color(
			(int) Math.round(ground.getRed() + (top.getRed() - ground.getRed()) * amount),
			(int) Math.round(ground.getGreen() + (top.getGreen() - ground.getGreen()) * amount),
			(int) Math.round(ground.getBlue() + (top.getBlue() - ground.getBlue()) * amount));
	}

	/** A panel that reacts to the mouse as a button does: it lights on hover and acts on a left click. */
	private static JPanel clickable(Color ground, Color hover, Runnable action)
	{
		JPanel panel = new JPanel();
		panel.setOpaque(true);
		panel.setBackground(ground);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		panel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				panel.setBackground(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				panel.setBackground(ground);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && panel.contains(e.getPoint()))
				{
					action.run();
				}
			}
		});
		return panel;
	}

	/** A small text link: {@code colour}, lit to {@code hover} under the mouse. */
	private static JComponent link(String text, Color colour, Color hover, Runnable action)
	{
		JLabel label = new JLabel(text);
		label.setFont(small());
		label.setForeground(colour);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(hover);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(colour);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && label.contains(e.getPoint()))
				{
					action.run();
				}
			}
		});
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JButton button(String text, Runnable action)
	{
		JButton button = new JButton(text);
		button.addActionListener(e -> action.run());
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		// Narrow margins, so two buttons side by side in a card keep their whole labels.
		button.setMargin(new Insets(2, 4, 2, 4));
		button.setFocusPainted(false);
		return button;
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setOpaque(false);
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	/** Holds a component at the top of a border layout's side, so it lines up with the first line of text. */
	private static JComponent top(JComponent component)
	{
		JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.add(component, BorderLayout.NORTH);
		return holder;
	}

	/** Left-aligned, as wide as the column, and no taller than it wants to be. */
	private static JComponent fit(JComponent component)
	{
		return fit(component, component.getPreferredSize().height);
	}

	private static JComponent fit(JComponent component, int height)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		return component;
	}

	private static Component gap(int height)
	{
		JComponent gap = (JComponent) Box.createRigidArea(new Dimension(0, height));
		gap.setAlignmentX(Component.LEFT_ALIGNMENT);
		return gap;
	}
}
