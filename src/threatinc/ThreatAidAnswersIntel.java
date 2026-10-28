package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE ANSWERS TO A REQUEST FOR AID (the user, 2026-10-28: "i requested aid and nothing really
 * happened. is it possible to get instant feedback from factions? like one intel thats
 * clickable/openeable listing responses from each faction and reason why accepted/rejected and
 * impact on standing"): one intel entry a request, posted the moment the player asks
 * (ThreatAidRequests.request answers at once), with a section a faction - what it sent from where
 * and the standing it cost, or why it sent nothing of a good - and the standing spent in all.
 * Opens from its notification. Driven by IncursionManager.advanceModIntel; expires after
 * {@link #LIFETIME_DAYS}.
 */
public class ThreatAidAnswersIntel extends BaseIntelPlugin {

	/** Days the answers stay in the intel screen. */
	public static final float LIFETIME_DAYS = 120f;

	/** One faction's answer about one good: what it sent, or why nothing. */
	public static class Answer {
		public String factionId;
		/** A commodity id, ThreatNeeds.FLEETS, or null for a refusal that names no good. */
		public String good;
		/** Units (or fleet points) sent; 0 for a refusal. */
		public float amount;
		/** The colony or base it sailed from; null for a refusal. */
		public String fromName;
		/** Standing it cost, in points. */
		public float rep;
		/** Why nothing was sent; null when something sailed. */
		public String reason;
	}

	protected String marketId;
	protected String marketName;
	protected String asked;
	protected long timestamp;
	protected List<Answer> answers = new ArrayList<Answer>();
	protected float daysActive = 0f;

	public ThreatAidAnswersIntel(MarketAPI market, String asked) {
		this.marketId = market != null ? market.getId() : null;
		this.marketName = market != null ? market.getName() : "a colony";
		this.asked = asked;
		this.timestamp = Global.getSector().getClock().getTimestamp();
	}

	public void add(Answer a) {
		if (a != null) answers.add(a);
	}

	public List<Answer> answers() {
		return answers;
	}

	/** Factions that sent something. */
	public int senders() {
		Set<String> ids = new LinkedHashSet<String>();
		for (Answer a : answers) if (a.reason == null && a.amount > 0f) ids.add(a.factionId);
		return ids.size();
	}

	/** Standing spent in all. */
	public float repSpent() {
		float sum = 0f;
		for (Answer a : answers) sum += Math.max(0f, a.rep);
		return sum;
	}

	@Override
	protected void advanceImpl(float amount) {
		if (isEnding() || isEnded()) return;
		daysActive += Global.getSector().getClock().convertToDays(amount);
		if (daysActive > LIFETIME_DAYS) endAfterDelay();
	}

	@Override
	public String getName() {
		return (senders() > 0 ? "Aid Answers - " : "No Aid For ") + marketName;
	}

	@Override
	public String getIcon() {
		return Global.getSector().getPlayerFaction().getCrest();
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return Global.getSector().getPlayerFaction();
	}

	@Override
	public Color getTitleColor(ListInfoMode mode) {
		return senders() > 0 ? super.getTitleColor(mode) : Misc.getNegativeHighlightColor();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MILITARY);
		tags.add(Factions.PLAYER);
		return tags;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		info.addPara(getName(), getTitleColor(mode), 0f);
		addBulletPoints(info, mode);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getBulletColorForMode(mode);
		float initPad = mode == ListInfoMode.IN_DESC ? 10f : 3f;
		int sent = senders();
		int asked = factions().size();
		bullet(info);
		info.addPara("%s of %s factions sent something", initPad, tc, Misc.getHighlightColor(), "" + sent, "" + asked);
		if (repSpent() > 0f) {
			info.addPara("Standing spent %s", 0f, tc, Misc.getNegativeHighlightColor(), String.format("-%.1f", repSpent()));
		}
		unindent(info);
	}

	/** The factions answered, in the order asked. */
	protected List<String> factions() {
		List<String> ids = new ArrayList<String>();
		for (Answer a : answers) if (!ids.contains(a.factionId)) ids.add(a.factionId);
		return ids;
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color gray = Misc.getGrayColor();
		int daysAgo = (int) Global.getSector().getClock().getElapsedDaysSince(timestamp);
		info.addPara(marketName + " asked every faction at Favourable or better for %s, %s.", opad, h,
				asked != null ? asked : "aid", daysAgo <= 0 ? "today" : daysAgo + " days ago");
		if (answers.isEmpty()) {
			info.addPara("No faction is Favourable or better with you.", opad, gray);
			return;
		}
		for (String factionId : factions()) {
			FactionAPI f = Global.getSector().getFaction(factionId);
			String name = ThreatWarState.displayName(factionId);
			if (f != null) {
				info.addSectionHeading(name, f.getBaseUIColor(), f.getDarkUIColor(), Alignment.MID, opad);
			} else {
				info.addSectionHeading(name, Alignment.MID, opad);
			}
			float first = opad;
			for (Answer a : answers) {
				if (!factionId.equals(a.factionId)) continue;
				if (a.reason == null && a.amount > 0f) {
					String what = ThreatNeeds.FLEETS.equals(a.good)
							? Misc.getWithDGS(Math.round(a.amount)) + " fleet points"
							: Misc.getWithDGS(ThreatHulls.shown(a.good, a.amount)) + " " + ThreatReserves.label(a.good);
					info.addPara(BULLET + "%s from %s, standing %s", first, new Color[] { h, h, neg },
							what, a.fromName != null ? a.fromName : "its port", String.format("-%.1f", a.rep));
				} else {
					String good = a.good == null ? "" : (ThreatNeeds.FLEETS.equals(a.good)
							? "task force" : ThreatReserves.label(a.good)) + ": ";
					info.addPara(BULLET + good + (a.reason != null ? a.reason : "nothing"), gray, first);
				}
				first = 3f;
			}
		}
		if (repSpent() > 0f) {
			info.addPara("Standing spent in all: %s.", opad, neg, String.format("-%.1f", repSpent()));
		}
	}
}
