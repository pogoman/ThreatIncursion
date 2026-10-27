package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * THE SAT BOMB (the user, 2026-10-27, docs/suppression-balance.md 10): one act over a hive, for
 * the player and for NPC fleets alike. It razes {@code satBombStrataDays} days of the tac raze
 * (ThreatGroundFronts.hiveRaze) at once and disrupts every vanilla industry the hive runs for
 * {@code satBombDisruptDays} - never the Fabrication Core, the Nexus or any structure of ours -
 * for the tac raze's fuel x {@code satBombFuelMult}. No atrocity, no stability penalty, no
 * return fire: the hive has no guns and nobody mourns it. The day-by-day raze that stands is the
 * tac bomb; this is the dearer, broader stroke that also takes the hive's production offline.
 */
public class ThreatSatBomb {

	public static boolean enabled() {
		return ThreatIncConfig.satBombEnabled();
	}

	/** The hive's vanilla industries the bomb disrupts: producers (vanilla's industry tag) that are not ours. */
	public static List<Industry> targets(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getId() == null || ind.getId().startsWith("threatinc_")) continue;
			if (!ind.isIndustry()) continue;
			if (ind.isBuilding() && !ind.isUpgrading()) continue;
			list.add(ind);
		}
		return list;
	}

	/** Days of the tac raze the bomb delivers at once. */
	public static float strataDays() {
		return Math.max(0f, ThreatIncConfig.satBombStrataDays());
	}

	/** Strata the bomb razes off the hive for a fleet of fp: satBombStrataDays of the tac raze, never more than stand. */
	public static float strata(MarketAPI market, float fp) {
		if (!ThreatGroundFronts.strataTarget(market)) return 0f;
		return Math.min(ThreatGroundFronts.hiveRazePerDay(fp) * strataDays(), Math.max(0f, ThreatStrata.of(market)));
	}

	/** Fuel the bomb costs a fleet of fp here: the strata's tac price x satBombFuelMult. */
	public static float price(MarketAPI market, float fp) {
		return strata(market, fp) * ThreatGroundFronts.fuelPerStratum() * Math.max(1f, ThreatIncConfig.satBombFuelMult());
	}

	/** Days the hive's industries are disrupted for. */
	public static float disruptDays() {
		return Math.max(0f, ThreatIncConfig.satBombDisruptDays());
	}

	/**
	 * Drops it: the strata razed (unpriced here - the caller has paid {@link #price}) and the
	 * industries disrupted. Returns {strata razed, industries disrupted, 1 when the hive is gone}.
	 */
	public static float[] drop(MarketAPI market, float fp, String razerFactionId, String label) {
		float[] out = new float[3];
		if (market == null || fp <= 0f || !ThreatGroundFronts.strataTarget(market)) return out;
		String name = market.getName();
		float[] razed = ThreatGroundFronts.hiveRaze(market, fp, strataDays(), Float.MAX_VALUE,
				razerFactionId != null ? razerFactionId : Factions.PLAYER);
		out[0] = razed[0];
		out[2] = razed[3];
		if (out[2] <= 0f && market.isInEconomy()) {
			float days = disruptDays();
			StringBuilder names = new StringBuilder();
			for (Industry ind : targets(market)) {
				if (days > 0f) ind.setDisrupted(Math.max(ind.getDisruptedDays(), days));
				out[1]++;
				if (names.length() > 0) names.append(", ");
				names.append(ind.getCurrentName());
			}
			if (out[1] > 0) market.reapplyIndustries();
			ThreatIncConfig.log(label + ": sat bomb on " + name + " by " + (int) fp + " FP razes " + (int) out[0]
					+ " strata (" + (int) ThreatStrata.of(market) + " stand) and disrupts " + (int) out[1]
					+ " industries for " + (int) days + " d" + (names.length() > 0 ? ": " + names : ""));
		} else {
			ThreatIncConfig.log(label + ": sat bomb on " + name + " by " + (int) fp + " FP razes " + (int) out[0]
					+ " strata; the hive is gone");
		}
		return out;
	}

	/** "Mining, Refining, Fuel Production" - the targets by name, for a prompt. */
	public static String targetNames(MarketAPI market) {
		StringBuilder names = new StringBuilder();
		for (Industry ind : targets(market)) {
			if (names.length() > 0) names.append(", ");
			names.append(ind.getCurrentName());
		}
		return names.toString();
	}
}
