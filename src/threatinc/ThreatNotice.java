package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A one-off notification laid out like vanilla's (2026-09-26): a short title,
 * then bullet lines in body text with the names and numbers highlighted - a
 * faction in its own colour, a place or figure in the highlight colour, a
 * loss in red. Replaces MessageIntel, which draws every line as one flat
 * paragraph in one colour.
 *
 * <pre>
 * ThreatNotice.titled("Hive found")
 *         .line("%s found a Threat hive in the %s.", ThreatNotice.faction(f), system.getNameWithLowercaseType())
 *         .send();
 * </pre>
 *
 * A line argument that is not a {@link Hl} is highlighted in the highlight
 * colour. Never added to the intel manager: shown once, like MessageIntel.
 */
public class ThreatNotice extends BaseIntelPlugin {

	/** A highlighted fragment of a line. */
	public static class Hl {
		public final String text;
		public final Color color;

		public Hl(String text, Color color) {
			this.text = text;
			this.color = color;
		}
	}

	protected static class Line {
		String format;
		Color color;
		String[] highlights;
		Color[] colors;
	}

	protected String title;
	protected Color titleColor;
	protected boolean bad;
	protected boolean good;
	protected String icon;
	protected final List<Line> lines = new ArrayList<Line>();

	public static ThreatNotice titled(String title) {
		ThreatNotice n = new ThreatNotice();
		n.title = title;
		return n;
	}

	/** Title in this colour instead of the player's UI colour. */
	public ThreatNotice titleColor(Color color) {
		titleColor = color;
		return this;
	}

	/** Title in the Threat's colour: the swarm did something. */
	public ThreatNotice threat() {
		return titleColor(threatColor());
	}

	/**
	 * Bad news. Under the Threat's crest (the swarm acting) the title takes
	 * the Threat's colour; under a faction's crest (our side losing) red.
	 */
	public ThreatNotice bad() {
		bad = true;
		return this;
	}

	/** Good news: the title stays the player's UI colour, even under the Threat's crest. */
	public ThreatNotice good() {
		good = true;
		return this;
	}

	/** The Threat faction's colour. */
	public static Color threatColor() {
		return Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();
	}

	/** The mod's colour for a good outcome or state: the player's UI blue, never green (user's rule). */
	public static Color goodColor() {
		return Misc.getBasePlayerColor();
	}

	/** The crest of this faction instead of the Threat's. */
	public ThreatNotice icon(FactionAPI faction) {
		if (faction != null) icon = faction.getCrest();
		return this;
	}

	/** A bullet line in body text; each %s is an argument, highlighted. No arguments: the text is literal. */
	public ThreatNotice line(String format, Object... args) {
		return lineIn(null, format, args);
	}

	/** A bullet line in this colour rather than body text. */
	public ThreatNotice lineIn(Color color, String format, Object... args) {
		// vanilla bullets carry no full stop; a lore line of several sentences keeps its own
		if (format.endsWith(".") && !format.endsWith("..") && !format.contains(". ")) {
			format = format.substring(0, format.length() - 1);
		}
		// the word Threat takes the Threat's colour wherever a line says it
		Object[] threatArgs = colourThreat(args.length == 0 ? format.replace("%", "%%") : format, args);
		if (threatArgs != null) {
			format = (String) threatArgs[0];
			args = (Object[]) threatArgs[1];
		}
		Line l = new Line();
		l.format = format;
		l.color = color;
		l.highlights = new String[args.length];
		l.colors = new Color[args.length];
		for (int i = 0; i < args.length; i++) {
			Object a = args[i];
			if (a instanceof Hl) {
				l.highlights[i] = ((Hl) a).text;
				l.colors[i] = ((Hl) a).color;
			} else {
				l.highlights[i] = String.valueOf(a);
				l.colors[i] = Misc.getHighlightColor();
			}
		}
		lines.add(l);
		return this;
	}

	protected static final Pattern THREAT_WORD = Pattern.compile("%%|%s|\\bThreat\\b");

	/**
	 * The format with each literal Threat turned into a highlighted argument,
	 * as {new format, new args}; null when the line never says Threat.
	 */
	protected static Object[] colourThreat(String format, Object[] args) {
		Matcher m = THREAT_WORD.matcher(format);
		StringBuilder sb = new StringBuilder();
		List<Object> out = new ArrayList<Object>();
		int last = 0;
		int next = 0;
		boolean found = false;
		while (m.find()) {
			sb.append(format, last, m.start());
			String g = m.group();
			if ("%%".equals(g)) {
				sb.append("%%");
			} else if ("%s".equals(g)) {
				sb.append("%s");
				out.add(next < args.length ? args[next] : "");
				next++;
			} else {
				sb.append("%s");
				out.add(new Hl("Threat", threatColor()));
				found = true;
			}
			last = m.end();
		}
		if (!found) return null;
		sb.append(format.substring(last));
		for (; next < args.length; next++) out.add(args[next]);
		return new Object[] { sb.toString(), out.toArray() };
	}

	public void send() {
		Global.getSector().getCampaignUI().addMessage(this);
		ThreatIncConfig.log("Notice: " + plain());
	}

	/** Title and lines as one plain string, for the log. */
	public String plain() {
		StringBuilder sb = new StringBuilder(title);
		for (Line l : lines) {
			sb.append(" | ").append(l.highlights.length == 0 ? l.format : String.format(l.format, (Object[]) l.highlights));
		}
		return sb.toString();
	}

	// ---- highlight helpers ----

	/** A faction's display name in its own colour. */
	public static Hl faction(FactionAPI faction) {
		if (faction == null) return new Hl("Unknown", Misc.getGrayColor());
		return new Hl(ThreatWarState.displayName(faction.getId()), faction.getBaseUIColor());
	}

	/** A market's name in its owner's colour. */
	public static Hl market(MarketAPI market) {
		if (market == null) return new Hl("Unknown", Misc.getGrayColor());
		FactionAPI owner = market.getFaction();
		return new Hl(market.getName(), owner != null ? owner.getBaseUIColor() : Misc.getHighlightColor());
	}

	public static Hl hl(Object text) {
		return new Hl(String.valueOf(text), Misc.getHighlightColor());
	}

	public static Hl red(Object text) {
		return new Hl(String.valueOf(text), Misc.getNegativeHighlightColor());
	}

	/** A gain, in the good colour (blue, not green). */
	public static Hl green(Object text) {
		return new Hl(String.valueOf(text), goodColor());
	}

	public static Hl gray(Object text) {
		return new Hl(String.valueOf(text), Misc.getGrayColor());
	}

	// ---- rendering ----

	@Override
	public String getName() {
		return title;
	}

	@Override
	public Color getTitleColor(ListInfoMode mode) {
		if (titleColor != null) return titleColor;
		if (good) return super.getTitleColor(mode);
		// the Threat's crest: the swarm acted
		if (icon == null) return threatColor();
		if (bad) return Misc.getNegativeHighlightColor();
		return super.getTitleColor(mode);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getBulletColorForMode(mode);
		float pad = 3f;
		float opad = 10f;
		float initPad = mode == ListInfoMode.IN_DESC ? opad : pad;
		bullet(info);
		for (Line l : lines) {
			Color c = l.color != null ? l.color : tc;
			if (l.highlights.length == 0) {
				// no arguments: literal text, so a stray % in a reason is harmless
				info.addPara(l.format, c, initPad);
			} else {
				LabelAPI label = info.addPara(l.format, initPad, c, Misc.getHighlightColor(), l.highlights);
				label.setHighlightColors(l.colors);
			}
			initPad = 0f;
		}
		unindent(info);
	}

	@Override
	public String getIcon() {
		if (icon != null) return icon;
		try {
			return Global.getSector().getFaction(Factions.THREAT).getCrest();
		} catch (Throwable t) {
			return super.getIcon();
		}
	}

	@Override
	public String getCommMessageSound() {
		return getSoundMinorMessage();
	}

	@Override
	public boolean hasSmallDescription() {
		return false;
	}
}
