package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE FABRICATION CORE IS THE HIVE'S ONE PRODUCER (the user, 2026-10-25,
 * docs/strata-are-the-threat.md 3). It demands METALS at the world's size and FUEL at
 * size - 2 (coreInputPerSize scales both - vanilla's own balanced table, so a refinery and a
 * fuel plant of the world's size meet it in full) and supplies heavy machinery equal to the
 * size (Mining and Refining demand it; the chain stays bootstrapped). What the market sees
 * of each input against that demand is the Core's state (ThreatColonyManager.coreState): met
 * in full the world GROWS coreStrataPerSizeMonth x size a month, one unit short it HOLDS,
 * two or more short it STARVES a size per starveDaysPerSize / (units - 1) days. Hive-only,
 * never disrupted (what hurts the Core is what starves it), never offered in the player's
 * picker. Declared inside apply() to survive the economy's per-recompute wipe.
 */
public class FabricationCore extends BaseIndustry {

	@Override
	public void apply() {
		super.apply(true);
		// MACHINES EAT NOTHING (the user, 2026-10-25: "they should not be short of food and luxury goods"):
		// Population & Infrastructure's human demands are cancelled on every recompute, at any size - the
		// essentials sweep's size-sized flat lagged a size change and the stat is wiped on load
		com.fs.starfarer.api.campaign.econ.Industry pop = market.getIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.POPULATION);
		if (pop != null) pop.getDemandReductionFromOther().modifyFlat("threatinc_machines", 100f);
		int size = market.getSize();
		supply(Commodities.HEAVY_MACHINERY, size);
		int metals = ThreatColonyManager.coreDemand(market, Commodities.METALS); // 0 while a conquered world is consumed (phase E)
		int fuel = ThreatColonyManager.coreDemand(market, Commodities.FUEL);
		if (metals > 0) demand(Commodities.METALS, metals);
		if (fuel > 0) demand(Commodities.FUEL, fuel);
		// THE DEFENCE IS THE STRATA (phase D, docs/strata-are-the-threat.md 4): vanilla's base figure is
		// topped up as a flat to the strata standing (less what a front holds), so what the ground war
		// reads is the strata and no structure's multiplier - applied while the Core builds too
		float targetBase = ThreatStrata.standing(market);
		float vanillaBase = com.fs.starfarer.api.impl.campaign.econ.impl.PopulationAndInfrastructure
				.getBaseGroundDefenses(market.getSize());
		com.fs.starfarer.api.combat.StatBonus defense = market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD);
		if (targetBase - vanillaBase > 0f) {
			defense.modifyFlat(getModId(1), targetBase - vanillaBase, "Swarm");
		} else {
			defense.unmodifyFlat(getModId(1));
		}
		if (!isFunctional()) {
			supply.clear();
			demand.clear();
		}
	}

	@Override
	public void unapply() {
		super.unapply();
		market.getStats().getDynamic().getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD)
				.unmodifyFlat(getModId(1));
	}

	/** Nothing on a hive world is disrupted; what hurts the Core is what starves it. */
	@Override
	public boolean canBeDisrupted() {
		return false;
	}

	// hive-only organ: never offered in the player's construction picker
	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}

	/** One line per fact: the strata, the growth, the Core's inputs, the density (docs/strata-are-the-threat.md 8). */
	@Override
	protected void addPostDescriptionSection(TooltipMakerAPI tooltip, IndustryTooltipMode mode) {
		if (market == null || !ThreatStrata.has(market)) return;
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		ThreatColonyManager.CoreState s = ThreatColonyManager.coreState(market);
		float strata = ThreatStrata.of(market);
		int max = ThreatColonyManager.maxColonySize(market);
		if (market.getSize() >= max) {
			tooltip.addPara("Strata: %s (size %s, the largest)", opad, h, ThreatStrata.fmt(strata), "" + market.getSize());
		} else {
			tooltip.addPara("Strata: %s (size %s at %s)", opad, h, ThreatStrata.fmt(strata),
					"" + (market.getSize() + 1), ThreatStrata.fmt(ThreatStrata.nextSizeAt(market)));
		}
		if (s.coreDown) {
			tooltip.addPara("Growth: %s", 3f, neg, isBuilding() ? "holding while the Core builds" : "holding");
		} else if (s.starving()) {
			tooltip.addPara(market.getSize() <= 1 ? "Starving: %s a month, gone in %s days" : "Starving: %s a month, a size in %s days",
					3f, neg, ThreatStrata.fmt(-s.strataPerDay * 30f),
					"" + Math.max(1, (int) ThreatColonyManager.daysToLoseSize(market)));
		} else if (s.growing()) {
			if (ThreatGroundFronts.hasFront(market)) {
				tooltip.addPara("Growth: %s", 3f, neg, "halted under the front");
			} else if (ThreatRazing.saturated(market)) {
				tooltip.addPara("Growth: %s", 3f, neg, "halted under saturation");
			} else if (strata >= ThreatStrata.cap(market)) {
				tooltip.addPara("Growth: %s", 3f, h, "at its cap");
			} else {
				tooltip.addPara("Growing %s a month", 3f, h, ThreatStrata.fmt(s.strataPerDay * 30f));
			}
		} else {
			tooltip.addPara("Holding: %s", 3f, neg, "a unit short");
		}
		tooltip.addPara("Core: metals %s of %s, fuel %s of %s", 3f, s.shortUnits > 0 ? neg : h,
				"" + s.metalsHave, "" + s.metalsDemand, "" + s.fuelHave, "" + s.fuelDemand);
		if (ThreatColonyManager.hiveBlockaded(market)) {
			tooltip.addPara("Blockaded: %s", 3f, neg, "nothing docks");
		} else {
			tooltip.addPara("Swarm density: %s", 3f, h, ThreatColonyManager.signedPct(ThreatColonyManager.hiveDensityAccess(market)));
		}
	}
}
