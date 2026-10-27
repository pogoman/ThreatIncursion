package threatinc;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import threatinc.ThreatGroundFronts.GroundFront;

/**
 * THE INFESTATION CARD (the user, 2026-10-27, docs/occupation.md): the structure a
 * human world shows in its industry grid while the swarm's landing has claimed any of
 * its industries ({@link ThreatOccupation}). Display only: the output cut is the ground
 * war condition's, the growth the front's. Installed and removed by
 * {@link ThreatGroundFronts#syncSiegeState}; never buildable, never disrupted, never
 * raided, no upkeep.
 */
public class ThreatInfestation extends BaseIndustry {

	public static final String ID = "threatinc_infestation";

	@Override
	public void apply() {
		super.apply(true);
	}

	@Override
	public void unapply() {
		super.unapply();
	}

	@Override
	protected void addRightAfterDescriptionSection(TooltipMakerAPI tooltip, IndustryTooltipMode mode) {
		if (market == null) return;
		GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null) return;
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		List<Industry> claimed = ThreatOccupation.claimed(market, front);
		StringBuilder names = new StringBuilder();
		for (Industry ind : claimed) {
			if (names.length() > 0) names.append(", ");
			names.append(ind.getCurrentName());
		}
		tooltip.addPara("Claimed: %s.", opad, neg, names.length() > 0 ? names.toString() : "nothing yet");
		Industry next = ThreatOccupation.next(market, front);
		if (next != null && front.strataHeld > 0) {
			tooltip.addPara("Next: %s in %s.", 3f, h, next.getCurrentName(),
					days(ThreatOccupation.daysToNextClaim(front)));
		}
		float grow = ThreatOccupation.growthPerDay(front);
		tooltip.addPara("Eaten: %s units a day, %s strata a day to the swarm.", 3f, neg,
				ThreatStrata.fmt(front.eatenUnits), ThreatStrata.fmt(grow));
		if (ThreatOccupation.holdsAll(front, market)) {
			float budFP = ThreatIncConfig.occupationBudFP();
			tooltip.addPara("Every district held: %s of %s fleet points budding over the world.", 3f, neg,
					"" + Math.round(ThreatStrata.fpOf(front.budPool)),
					budFP > 0f ? "" + Math.round(budFP) : "no");
		} else {
			tooltip.addPara("Swarm on the ground: %s troops.", 3f, h,
					Misc.getWithDGS(Math.round(front.marines)));
		}
	}

	protected static String days(float d) {
		int n = (int) Math.ceil(d);
		return n + (n == 1 ? " day" : " days");
	}

	/** Retired 2026-10-27 (the user, Haven: not the marker they meant); hidden, and removed from saves by syncSiegeState. */
	@Override
	public boolean isHidden() {
		return true;
	}

	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}

	@Override
	public boolean canBeDisrupted() {
		return false;
	}

	@Override
	public boolean showShutDown() {
		return false;
	}

	@Override
	public boolean canShutDown() {
		return false;
	}

	@Override
	public boolean canImprove() {
		return false;
	}

	@Override
	public boolean canInstallAICores() {
		return false;
	}

	@Override
	public float getPatherInterest() {
		return 0f;
	}
}
