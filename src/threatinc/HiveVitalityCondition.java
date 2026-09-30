package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The player-facing readout of the hive growth/decline engine, shown as a
 * market condition on every Threat colony. The vanilla colony UI's growth
 * number is meaningless for hive worlds (their growth is driven entirely by
 * {@link ThreatColonyManager#updateColonyVitality}); this tooltip shows the
 * numbers that actually matter - vitality (fabrication x supply), each organ's
 * status, and the growth pace.
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

		float fab = ThreatColonyManager.computeFabricationMult(market);
		float supply = ThreatColonyManager.computeSupplyMult(market);
		float health = ThreatColonyManager.computeHealth(market);
		boolean sized = ThreatColonyUpkeep.enabled();

		if (sized) {
			float upkeep = ThreatColonyUpkeep.perMonth(market.getSize());
			if (upkeep <= 0f) {
				tooltip.addPara("Upkeep: %s", opad, h, "none below size 3");
			} else {
				float fed = ThreatColonyUpkeep.fedShare(market);
				tooltip.addPara("Supplies: %s of %s a month", opad,
						fed < ThreatColonyUpkeep.breakEven() ? neg : h,
						pct(fed), Misc.getWithDGS((int) upkeep));
			}
			float cut = ThreatColonyUpkeep.importCut(market);
			if (cut > 0f) tooltip.addPara(INDENT + "Imports cut: %s", 3f, neg, pct(cut));
		}
		tooltip.addPara("Vitality: %s (fabrication %s x supply %s)", sized ? 3f : opad,
				health < ThreatColonyManager.CRITICAL_HEALTH ? neg : h,
				pct(health), pct(fab), pct(supply));

		// the Core wears with its clock rather than switching off, so it shows
		// its output; the port is on/off (applyPortDisruption zeroes shipping)
		Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
		if (core == null) {
			tooltip.addPara(INDENT + "Fabrication Core: %s", 3f, neg, "absent");
		} else if (core.isDisrupted()) {
			tooltip.addPara(INDENT + "Fabrication Core: %s (%s days)", 3f, neg,
					pct(ThreatColonyManager.wornDownFactor(core,
							ThreatIncConfig.coreDownFactor())),
					"" + (int) core.getDisruptedDays());
		} else {
			tooltip.addPara(INDENT + "Fabrication Core: %s", 3f, h, "running");
		}
		Industry port = market.getIndustry(Industries.MEGAPORT);
		if (port == null) port = market.getIndustry(Industries.SPACEPORT);
		if (port == null) {
			tooltip.addPara(INDENT + "Port: %s", 3f, neg, "absent");
		} else if (port.isDisrupted()) {
			tooltip.addPara(INDENT + "Port: %s (%s days)", 3f, neg,
					"offline", "" + (int) port.getDisruptedDays());
		} else {
			tooltip.addPara(INDENT + "Port: %s", 3f, h, "running");
		}

		ThreatGroundFronts.GroundFront front =
				ThreatGroundFronts.getFront(market.getId());
		// under size upkeep a front or saturation holds a colony's growth, never its hunger
		float pace = sized ? ThreatColonyManager.growthPace(market) : 0f;
		if (front != null) {
			tooltip.addPara("Strata held: %s of %s", opad, h,
					"" + front.strataHeld, "" + market.getSize());
		}
		if (sized && pace < 0f) {
			tooltip.addPara("Growth: %s, a size in %s days", front != null ? 3f : opad, neg, "starving",
					"" + Math.max(1, (int) ThreatColonyManager.daysToLoseSize(market)));
		} else if (front != null) {
			tooltip.addPara("Growth: %s", 3f, neg, "halted");
		} else if (ThreatRazing.saturated(market)) {
			tooltip.addPara("Growth: %s", opad, neg, "halted under saturation");
		} else if (sized) {
			if (market.getSize() >= ThreatColonyManager.maxColonySize(market)) {
				tooltip.addPara("Growth: %s", opad, h, "at its largest");
			} else if (pace > 0f) {
				tooltip.addPara("Growth: %s of full pace", opad, pace >= 1f ? h : neg, pct(pace));
			} else {
				tooltip.addPara("Growth: %s", opad, neg, "holding");
			}
		} else {
			float growthMult = ThreatColonyManager.growthMultFor(health);
			tooltip.addPara("Growth: %s", opad, growthMult >= 1f ? h : neg,
					growthMult <= 0f ? "stalled" : pct(growthMult));
		}
	}

	protected static String pct(float f) {
		return (int) (f * 100f) + "%";
	}

	protected static final String INDENT = "    ";
}
