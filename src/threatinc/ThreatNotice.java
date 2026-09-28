package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
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
 * Clicked, it opens the war board on its faction's tab
 * ({@link ThreatNoticeClickDialog}); while the board is off the intel list
 * (no hive found yet) the message takes no click. A refusal's facts come
 * as a {@link Reason}, one bullet each.
 */
public class ThreatNotice extends BaseIntelPlugin {

	/** A highlighted fragment of a line. */
	public static class Hl {
		public final String text;
		public final Color color;
		/** The faction this fragment names or belongs to, for the notice's board tab; may be null. */
		public String factionId;

		public Hl(String text, Color color) {
			this.text = text;
			this.color = color;
		}
	}

	protected static class Line {
		String format;
		String[] highlights;
		Color[] colors;
		/** The faction the first {@link Hl} names or belongs to, for the notice's board tab; may be null. */
		String factionId;
	}

	/**
	 * Why an order is refused (2026-09-27): one fact per line, built exactly
	 * like a notice's lines, so a refusal notice shows the facts as bullets
	 * with the names and figures highlighted ({@link ThreatNotice#lines}) and
	 * a button's tooltip or a prompt shows the same facts as plain text, one
	 * per line ({@link #toString}).
	 */
	public static class Reason {
		protected final List<Line> lines = new ArrayList<Line>();

		public static Reason of(String format, Object... args) {
			return new Reason().line(format, args);
		}

		/** A further fact, on the rules of {@link ThreatNotice#line}. */
		public Reason line(String format, Object... args) {
			lines.add(toLine(format, args));
			return this;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			for (Line l : lines) {
				if (sb.length() > 0) sb.append('\n');
				sb.append(plain(l));
			}
			return sb.toString();
		}
	}

	/** A reason's plain text for a tooltip or prompt, or null for none. */
	public static String text(Reason reason) {
		return reason == null ? null : reason.toString();
	}

	protected String title;
	protected boolean bad;
	protected boolean good;
	/** The faction whose crest the notice bears and whose board tab a click opens; null for the Threat's. */
	protected String crestFactionId;
	protected String mentionedFactionId;
	protected final List<Line> lines = new ArrayList<Line>();

	public static ThreatNotice titled(String title) {
		ThreatNotice n = new ThreatNotice();
		n.title = title;
		return n;
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

	/** The crest of this faction instead of the Threat's; a click then opens its board tab. */
	public ThreatNotice icon(FactionAPI faction) {
		if (faction != null) crestFactionId = faction.getId();
		return this;
	}

	/** A bullet line in body text; each %s is an argument, highlighted. No arguments: the text is literal. */
	public ThreatNotice line(String format, Object... args) {
		return add(toLine(format, args));
	}

	/** A reason's facts, one bullet each. */
	public ThreatNotice lines(Reason reason) {
		if (reason != null) {
			for (Line l : reason.lines) add(l);
		}
		return this;
	}

	/** Facts as plain text on separate lines (a reason built as a string), one bullet each. */
	public ThreatNotice lines(String facts) {
		if (facts != null) {
			for (String fact : facts.split("\n")) {
				if (!fact.trim().isEmpty()) line(fact);
			}
		}
		return this;
	}

	protected ThreatNotice add(Line l) {
		if (mentionedFactionId == null) mentionedFactionId = l.factionId;
		lines.add(l);
		return this;
	}

	/** A line from its format and arguments, the highlights resolved. */
	protected static Line toLine(String format, Object... args) {
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
		l.highlights = new String[args.length];
		l.colors = new Color[args.length];
		for (int i = 0; i < args.length; i++) {
			Object a = args[i];
			if (a instanceof Hl) {
				if (l.factionId == null) l.factionId = ((Hl) a).factionId;
				l.highlights[i] = ((Hl) a).text;
				l.colors[i] = ((Hl) a).color;
			} else {
				l.highlights[i] = String.valueOf(a);
				l.colors[i] = Misc.getHighlightColor();
			}
		}
		return l;
	}

	/** A line's text without its colours. */
	protected static String plain(Line l) {
		return l.highlights.length == 0 ? l.format : String.format(l.format, (Object[]) l.highlights);
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
		// clicked, it opens the board on its faction's tab (ThreatNoticeClickDialog).
		// No click while the board is off the intel list (no hive found yet - the
		// omens, the war's start): vanilla would open an empty Major events list
		ThreatIncursionIntel board = ThreatIncursionIntel.get();
		boolean clickable = board != null && !board.isHidden();
		String tab = crestFactionId != null ? crestFactionId
				: mentionedFactionId != null ? mentionedFactionId : ThreatFactionView.VIEW_THREAT;
		Global.getSector().getCampaignUI().addMessage(this,
				clickable ? MessageClickAction.INTERACTION_DIALOG : MessageClickAction.NOTHING,
				clickable ? ThreatNoticeClickDialog.token(tab) : null);
		ThreatIncConfig.log("Notice: " + plain());
	}

	/** Title and lines as one plain string, for the log. */
	public String plain() {
		StringBuilder sb = new StringBuilder(title);
		for (Line l : lines) sb.append(" | ").append(plain(l));
		return sb.toString();
	}

	// ---- highlight helpers ----

	/** A faction's display name in its own colour. */
	public static Hl faction(FactionAPI faction) {
		if (faction == null) return new Hl("Unknown", Misc.getGrayColor());
		Hl h = new Hl(ThreatWarState.displayName(faction.getId()), faction.getBaseUIColor());
		h.factionId = faction.getId();
		return h;
	}

	/** A market's name in its owner's colour. */
	public static Hl market(MarketAPI market) {
		if (market == null) return new Hl("Unknown", Misc.getGrayColor());
		FactionAPI owner = market.getFaction();
		Hl h = new Hl(market.getName(), owner != null ? owner.getBaseUIColor() : Misc.getHighlightColor());
		if (owner != null) h.factionId = owner.getId();
		return h;
	}

	/** A base's name: a colony in its owner's colour, the player's outpost in the highlight colour. */
	public static Hl base(ThreatBases.Base base) {
		if (base == null) return new Hl("Unknown", Misc.getGrayColor());
		return base.market != null ? market(base.market) : hl(base.name());
	}

	/** A plain name in the highlight colour. */
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
		if (good) return super.getTitleColor(mode);
		// the Threat's crest: the swarm acted
		if (crestFactionId == null) return threatColor();
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
			if (l.highlights.length == 0) {
				// no arguments: literal text, so a stray % in a reason is harmless
				info.addPara(l.format, tc, initPad);
			} else {
				LabelAPI label = info.addPara(l.format, initPad, tc, Misc.getHighlightColor(), l.highlights);
				label.setHighlightColors(l.colors);
			}
			initPad = 0f;
		}
		unindent(info);
	}

	@Override
	public String getIcon() {
		try {
			String crest = Global.getSector().getFaction(
					crestFactionId != null ? crestFactionId : Factions.THREAT).getCrest();
			if (crest != null) return crest;
		} catch (Throwable t) {
			// no such faction: vanilla's default icon
		}
		return super.getIcon();
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
