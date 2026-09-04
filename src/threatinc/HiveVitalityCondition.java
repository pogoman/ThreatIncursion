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
		float declineT = ThreatColonyManager.CRITICAL_HEALTH;

		tooltip.addPara("Hive vitality: %s (fabrication %s x supply %s)", opad,
				health < declineT ? neg : h,
				(int) (health * 100f) + "%",
				(int) (fab * 100f) + "%",
				(int) (supply * 100f) + "%");

		addOrganLine(tooltip, market.getIndustry(ThreatColonyManager.FABRICATION_CORE),
				"Fabrication Core (the growth organ - offline means decline)");
		// the Swarm Nexus is NOT a vitality factor: it fabricates fleets, not
		// population. Its disruption thins the garrison (shown on the war board),
		// not the colony's vitality, so it is deliberately absent here.
		// the port is logistics, not fabrication: its disruption bites through
		// the supply score (ThreatColonyManager.applyPortDisruption zeroes the
		// world's shipping: no imports here, its exports reach no sibling)
		Industry port = market.getIndustry(Industries.MEGAPORT);
		if (port == null) port = market.getIndustry(Industries.SPACEPORT);
		addOrganLine(tooltip, port, "Port (logistics - feeds the supply factor)");

		// make the split roles explicit: vitality is Core + supply; the nexus is
		// purely military, and only ever freezes an existing decline, never causes it
		tooltip.addPara("Vitality is the Fabrication Core and Port only. The Swarm Nexus is the "
				+ "military organ - disrupting it thins the Defense Swarm garrison (see the war "
				+ "board), never the colony's vitality.", opad);

		// the ground war, when one is being fought on this world
		ThreatGroundFronts.GroundFront front =
				ThreatGroundFronts.getFront(market.getId());
		if (front != null) {
			int total = market.getSize();
			String owner = front.isPlayerOwned() ? "Your ground forces"
					: "An expeditionary ground force";
			tooltip.addPara(owner + " hold %s of this hive's %s strata - the "
					+ "Fabrication Core lies at the center, and taking the final "
					+ "stratum destroys the colony. Each stratum held strips its "
					+ "share of the base defenses and of the hive's output.", opad,
					pos, "" + front.strataHeld, "" + total);
		}

		if (health < declineT) {
			tooltip.addPara("The colony is %s - shortages and suppressed organs have "
					+ "its vitality critically low. Everything it does runs on that "
					+ "figure: growth stalls, garrisons rebuild slowly, and its "
					+ "counter-attacks against ground forces come rarely and weakly. "
					+ "A weakened hive is a cheap conquest.", opad, neg, "FAILING");
		} else if (front == null) {
			float growthMult = ThreatColonyManager.growthMultFor(health);
			if (growthMult <= 0f) {
				tooltip.addPara("Growth is %s - the organs run, but shortages hold "
						+ "expansion at a standstill.", opad, neg, "stalled");
			} else {
				tooltip.addPara("The colony is growing at %s of its full pace.", opad,
						growthMult >= 1f ? h : neg,
						(int) (growthMult * 100f) + "%");
			}
		} else {
			tooltip.addPara("Growth is %s - no colony builds new strata while an army "
					+ "fights inside its old ones.", opad, neg, "halted");
		}

		tooltip.addPara("Only a ground victory - taking every stratum and destroying "
				+ "the Fabrication Core - kills a Threat colony. Bombardment and "
				+ "starvation weaken it; boots on the ground end it.", pos, opad);
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

	/** Human-readable join: "A", "A and B", "A, B and C". */
	protected static String joinNames(java.util.List<String> names) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < names.size(); i++) {
			if (i > 0) sb.append(i == names.size() - 1 ? " and " : ", ");
			sb.append(names.get(i));
		}
		return sb.toString();
	}

	protected static final String BaseIndustryIndent = "    ";
}
