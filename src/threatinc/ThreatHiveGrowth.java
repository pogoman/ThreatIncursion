package threatinc;

import java.util.ArrayList;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketImmigrationModifier;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;

/**
 * The colony screen's Growth figure on a hive world reads the strata (the user, 2026-10-25:
 * "would be nice if it reflected the actual growth"). Vanilla shows the incoming weight over the
 * size's weight span (300 x 2^(size - 3), doubling as the strata do; Surgat's -1.88% was hazard
 * -50 + accessibility +5 over 2,400) and never resizes an NPC market from it
 * (CoreImmigrationPluginImpl.increaseMarketSize is player-only), so the figure is cosmetic.
 * This transient modifier - re-added every sweep, the set is not saved - drops vanilla's lines
 * (hazard, accessibility, instability, the larger neighbour) and lands one: the strata the world
 * gains a month as a share of the next size, the vitality tooltip's own figure ("+9.4%" is a size
 * in ten and a half months; starving reads negative, held reads 0). The population weight is
 * pinned to the strata's progress through the size so the bar says the same.
 */
public class ThreatHiveGrowth implements MarketImmigrationModifier {

	public static final String MOD_ID = "threatinc_strataGrowth";
	private static final ThreatHiveGrowth INSTANCE = new ThreatHiveGrowth();

	/** Sweep hook (ThreatColonyManager.ensureFabricationCore): the modifier on the market, the population weight at the strata's progress. */
	public static void apply(MarketAPI market) {
		if (market == null || !ThreatStrata.has(market)) return;
		market.addTransientImmigrationModifier(INSTANCE);
		int size = Math.max(1, market.getSize());
		float min = CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(size);
		float max = CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(size + 1);
		float span = Math.max(1f, ThreatStrata.span(size));
		float progress = Math.max(0f, Math.min(1f, (ThreatStrata.of(market) - ThreatStrata.sizeAt(size)) / span));
		market.getPopulation().setWeight(min + (max - min) * progress);
	}

	/** Strata a day the world moves as the vitality tooltip reads it: starving negative, held by a front or saturation 0, consuming or growing positive, at the cap 0. */
	public static float perDay(MarketAPI market) {
		ThreatColonyManager.CoreState s = ThreatColonyManager.coreState(market);
		if (s.starving()) return s.strataPerDay;
		if (ThreatGroundFronts.getFront(market.getId()) != null) return 0f;
		if (ThreatRazing.saturated(market)) return 0f;
		if (s.consuming) return s.strataPerDay;
		if (s.coreDown) return 0f;
		if (s.growing() && ThreatStrata.of(market) >= ThreatStrata.cap(market)) return 0f;
		return s.growing() ? s.strataPerDay : 0f;
	}

	@Override
	public void modifyIncoming(MarketAPI market, PopulationComposition incoming) {
		if (market == null || !ThreatStrata.has(market)) return;
		for (String id : new ArrayList<String>(incoming.getWeight().getFlatMods().keySet())) {
			incoming.getWeight().unmodifyFlat(id);
		}
		for (String id : new ArrayList<String>(incoming.getWeight().getMultMods().keySet())) {
			incoming.getWeight().unmodifyMult(id);
		}
		for (String id : new ArrayList<String>(incoming.getWeight().getPercentMods().keySet())) {
			incoming.getWeight().unmodifyPercent(id);
		}
		incoming.getComp().clear();
		incoming.add(market.getFactionId(), 1f);
		int size = Math.max(1, market.getSize());
		float min = CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(size);
		float max = CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(size + 1);
		float span = Math.max(1f, ThreatStrata.span(size));
		float weight = perDay(market) * 30f / span * (max - min);
		incoming.getWeight().modifyFlat(MOD_ID, weight, "Strata a month, as a share of the next size");
	}
}
