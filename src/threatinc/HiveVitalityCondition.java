package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The player-facing readout of a hive world, shown as a market condition on every Threat
 * colony: the strata and the next size's, the Fabrication Core's inputs and its state (growing
 * / holding / starving, ThreatColonyManager.coreState), the strata density that is its
 * accessibility, and what holds its growth. One line per fact (docs/strata-are-the-threat.md
 * 8). The condition id keeps its old name.
 */
public class HiveVitalityCondition extends BaseMarketConditionPlugin {

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		ThreatColonyManager.CoreState s = ThreatColonyManager.coreState(market);
		float strata = ThreatStrata.of(market);
		int max = ThreatColonyManager.maxColonySize(market);
		if (market.getSize() >= max) {
			tooltip.addPara("Strata: %s", opad, h, ThreatStrata.fmt(strata));
		} else {
			tooltip.addPara("Strata: %s, size %s at %s", opad, h, ThreatStrata.fmt(strata),
					"" + (market.getSize() + 1), ThreatStrata.fmt(ThreatStrata.nextSizeAt(market)));
		}

		Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
		if (core == null) {
			tooltip.addPara("Fabrication Core: %s", opad, neg, "absent");
		} else if (core.isBuilding()) {
			tooltip.addPara("Fabrication Core: %s", opad, neg, "building");
		} else {
			tooltip.addPara("Core: metals %s of %s, fuel %s of %s", opad, s.shortUnits > 0 ? neg : h,
					"" + s.metalsHave, "" + s.metalsDemand, "" + s.fuelHave, "" + s.fuelDemand);
		}
		if (ThreatColonyManager.hiveBlockaded(market)) {
			tooltip.addPara("Blockaded: %s", 3f, neg, "nothing docks");
		} else {
			tooltip.addPara("Strata density: %s", 3f, h,
					ThreatColonyManager.signedPct(ThreatColonyManager.hiveDensityAccess(market)));
		}

		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front != null) {
			tooltip.addPara("Strata held: %s of %s", opad, h, "" + front.strataHeld, "" + market.getSize());
		}
		// the Core's hunger is never held; its growth is, by a front or saturation
		if (s.starving()) {
			tooltip.addPara(market.getSize() <= 1 ? "Growth: %s, gone in %s days" : "Growth: %s, a size in %s days",
					front != null ? 3f : opad, neg, "starving",
					"" + Math.max(1, (int) ThreatColonyManager.daysToLoseSize(market)));
		} else if (front != null) {
			tooltip.addPara("Growth: %s", 3f, neg, "halted");
		} else if (ThreatRazing.saturated(market)) {
			tooltip.addPara("Growth: %s", opad, neg, "halted under saturation");
		} else if (s.coreDown) {
			tooltip.addPara("Growth: %s", opad, neg, "holding");
		} else if (s.growing()) {
			if (strata >= ThreatStrata.cap(market)) {
				tooltip.addPara("Growth: %s", opad, h, "at its largest");
			} else {
				tooltip.addPara("Growth: %s a month, size %s in %s days", opad, h, ThreatStrata.fmt(s.strataPerDay * 30f),
						"" + Math.min(max, market.getSize() + 1),
						"" + Math.max(1, (int) Math.min(9999f, ThreatColonyManager.daysToNextSize(market))));
			}
		} else {
			tooltip.addPara("Growth: %s", opad, neg, "holding, a unit short");
		}
	}
}
