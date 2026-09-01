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
 * status, and the decline meter.
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
		Color pos = Misc.getPositiveHighlightColor();

		float fab = ThreatColonyManager.computeFabricationMult(market);
		float supply = ThreatColonyManager.computeSupplyMult(market);
		float health = ThreatColonyManager.computeHealth(market);
		float declineT = ThreatIncConfig.declineHealthThreshold();

		tooltip.addPara("Hive vitality: %s (fabrication %s x supply %s)", opad,
				health < declineT ? neg : h,
				(int) (health * 100f) + "%",
				(int) (fab * 100f) + "%",
				(int) (supply * 100f) + "%");

		addOrganLine(tooltip, market.getIndustry(ThreatColonyManager.FABRICATION_CORE),
				"Fabrication Core (the growth organ - offline means decline)");
		addOrganLine(tooltip, market.getIndustry(ThreatColonyManager.SWARM_NEXUS),
				"Swarm Nexus");
		// the port is logistics, not fabrication: its disruption bites through
		// the supply score (imports collapse - here and at sibling colonies)
		Industry port = market.getIndustry(Industries.MEGAPORT);
		if (port == null) port = market.getIndustry(Industries.SPACEPORT);
		addOrganLine(tooltip, port, "Port (logistics - feeds the supply factor)");

		float decline = ThreatIncData.declineProgress(market.getId());
		if (health < declineT) {
			boolean accelerating = ThreatIncData.declineDays(market.getId())
					> ThreatColonyManager.effectiveTickDays();
			tooltip.addPara("The colony is %s - its strata are dying faster than the hive "
					+ "can regrow them" + (accelerating ? ", and the decay is accelerating"
					: "") + ".", opad, neg, "DECLINING");

			// the strategic readout: how fast, how long to the kill, and
			// whether the current disruptions last long enough to get there
			float tickDays = ThreatColonyManager.effectiveTickDays();
			float ratePerMonth = ThreatColonyManager.declineRatePerTick(market, health)
					/ tickDays * 30f;
			float[] projection = ThreatColonyManager.projectDecline(market, health);
			float nextStep = projection[0];
			float collapse = projection[1];

			tooltip.addPara("Rate: %s of a stratum per month at current vitality"
					+ (accelerating ? " (accelerating)" : "") + ".",
					3f, neg, (int) (ratePerMonth * 100f) + "%");
			if (nextStep > 0f && collapse > 0f) {
				tooltip.addPara("Held at this vitality: next stratum lost in about %s, "
						+ "full collapse (population to size 1) in about %s.",
						3f, neg, (int) nextStep + " days", (int) collapse + " days");
			} else if (nextStep > 0f) {
				tooltip.addPara("Held at this vitality: next stratum lost in about %s.",
						3f, neg, (int) nextStep + " days");
			}

			// what sustains the decline, and for how long
			Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
			float coreDays = core != null && core.isDisrupted() ? core.getDisruptedDays() : -1f;
			if (coreDays > 0f) {
				if (collapse > 0f && coreDays >= collapse) {
					tooltip.addPara("The Fabrication Core stays down for %s - longer than "
							+ "the projected collapse. If its supply state holds, this "
							+ "siege is already enough to kill the colony.", opad, pos,
							(int) coreDays + " more days");
				} else {
					tooltip.addPara("The Fabrication Core recovers in %s - before the "
							+ "projected collapse. Unless it is disrupted again, or the "
							+ "colony is starved by other means, the decline will stall "
							+ "there.", opad, h, (int) coreDays + " days");
				}
			} else {
				tooltip.addPara("The decline is driven by %s - it continues for as long "
						+ "as the supply lines stay cut.", opad, h, "shortages");
			}
		} else {
			float growthMult = ThreatColonyManager.growthMultFor(health);
			if (growthMult <= 0f) {
				tooltip.addPara("Growth is %s - the organs run, but shortages hold "
						+ "expansion at a standstill.", opad, neg, "stalled");
			} else {
				tooltip.addPara("The colony is growing at %s of its full pace.", opad,
						growthMult >= 1f ? h : neg,
						(int) (growthMult * 100f) + "%");
			}
		}
		if (decline > 0f) {
			tooltip.addPara("Accumulated decline: %s of the way to losing a population "
					+ "stratum." + (health >= declineT
							? " It regrows only once every organ is running again."
							: ""),
					opad, neg, (int) (decline * 100f) + "%");
		}
		if (health < declineT || decline > 0f) {
			tooltip.addPara("Decline is the only way a Threat colony dies - no bombardment "
					+ "can reduce its population.", pos, opad);
		}
	}

	protected void addOrganLine(TooltipMakerAPI tooltip, Industry ind, String label) {
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		if (ind == null) {
			tooltip.addPara(BaseIndustryIndent + label + ": %s", 3f, neg, "absent");
			return;
		}
		if (ind.isDisrupted()) {
			tooltip.addPara(BaseIndustryIndent + label + ": %s", 3f, neg,
					"OFFLINE (" + (int) ind.getDisruptedDays() + " days)");
		} else {
			tooltip.addPara(BaseIndustryIndent + label + ": %s", 3f, h, "running");
		}
	}

	protected static final String BaseIndustryIndent = "    ";
}
