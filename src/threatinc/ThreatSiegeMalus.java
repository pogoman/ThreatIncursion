package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketImmigrationModifier;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import com.fs.starfarer.api.util.Pair;

/**
 * The SIEGE STATE of a non-Threat colony (docs/ground-war.md "Sieges against
 * human factions", 2026-09-06): the hidden carrier of everything a siege does
 * to a human world's ground defence that vanilla has no organ for.
 * Defenders = garrison x fortification.
 *
 * <p><b>Fortification.</b> Vanilla's defence structures apply their
 * multiplier while functional and strip it entirely while disrupted. This
 * restores the multiplier scaled by the structure's CONDITION - 1 intact,
 * falling in a straight line to 0 at {@code fortificationDisruptDays} on its
 * clock - so bombardment is a dial, not a switch. The rule applies to colonies the swarm has besieged
 * ({@link ThreatGroundFronts#BESIEGED_FLAG}) or landed on, not to every raid
 * in the sector.
 *
 * <p><b>Districts.</b> Districts held strip (size - held) / size of the whole
 * figure (the human-world counterpart of the stripping {@link SwarmNexus}
 * does for a hive's strata). That is the whole cost: the stability (2026-10-24, "applied
 * twice") and accessibility (2026-10-24, the user, hives alike) penalties per district went.
 *
 * <p><b>Saturation.</b> A colony under saturation does not grow
 * ({@link ThreatRazing#saturated}): its immigration is held at nothing while
 * the bombs fall.
 *
 * <p>Installed while a front stands, any defence structure is disrupted (siege or
 * no siege, 2026-10-24) or saturation falls, removed otherwise ({@link ThreatGroundFronts#syncSiegeState}). Modelled on
 * {@link WarFootingDemand}: never shown, never buildable, never disrupted,
 * never raided, no upkeep.
 */
public class ThreatSiegeMalus extends BaseIndustry implements MarketImmigrationModifier {

	public static final String ID = "threatinc_siege_malus";

	/** The vanilla defence structures a siege suppresses. */
	public static final String[] FORTIFICATION_IDS = {
			Industries.GROUNDDEFENSES, Industries.HEAVYBATTERIES,
			Industries.PATROLHQ, Industries.MILITARYBASE, Industries.HIGHCOMMAND };

	/** The ground-defence bonus vanilla gives each (GroundDefenses / MilitaryBase constants). */
	public static float bonusOf(String industryId) {
		if (Industries.GROUNDDEFENSES.equals(industryId)) return 1f;
		if (Industries.HEAVYBATTERIES.equals(industryId)) return 2f;
		if (Industries.PATROLHQ.equals(industryId)) return 0.1f;
		if (Industries.MILITARYBASE.equals(industryId)) return 0.2f;
		if (Industries.HIGHCOMMAND.equals(industryId)) return 0.3f;
		return 0f;
	}

	/** The structures that literally fire on ships in orbit. */
	public static boolean isBattery(String industryId) {
		return Industries.GROUNDDEFENSES.equals(industryId)
				|| Industries.HEAVYBATTERIES.equals(industryId);
	}

	/** The colony's built defence structures. */
	public static List<Industry> fortifications(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		for (String id : FORTIFICATION_IDS) {
			Industry ind = market.getIndustry(id);
			if (ind == null) continue;
			if (ind.isBuilding() && !ind.isUpgrading()) continue;
			list.add(ind);
		}
		return list;
	}

	/**
	 * Vanilla's own input-deficit factor on the structure's bonus
	 * (BaseIndustry.getDeficitMult, replicated from its public parts): a
	 * starving battery gives less, suppressed or not.
	 */
	public static float deficitMult(Industry ind) {
		if (ind == null) return 1f;
		String[] commodities = isBattery(ind.getId())
				? new String[] { Commodities.SUPPLIES, Commodities.MARINES, Commodities.HAND_WEAPONS }
				: new String[] { Commodities.SUPPLIES };
		return deficitMult(ind, commodities);
	}

	/** Vanilla's raw deficit factor, weighed: a full deficit takes defenseDeficitWeight of the bonus, a partial one its share (2026-10-12, after a blockade's zero shipping zeroed every besieged hive's guns). */
	public static float weighDeficit(float raw) {
		return Math.max(0f, Math.min(1f, raw)); // v3: vanilla's own factor, unweighted
	}

	/** The same factor on any structure's own inputs (a hive's batteries run on machinery and metals). */
	public static float deficitMult(Industry ind, String... commodities) {
		if (ind == null) return 1f;
		float deficit = 0f;
		float demand = 0f;
		try {
			Pair<String, Integer> max = ind.getMaxDeficit(commodities);
			if (max != null && max.two != null) deficit = max.two;
			for (String id : commodities) {
				MutableCommodityQuantity q = ind.getDemand(id);
				if (q != null) demand = Math.max(demand, q.getQuantity().getModifiedInt());
			}
		} catch (Throwable t) {
			return 1f;
		}
		if (deficit < 0f) deficit = 0f;
		if (demand < 1f) return 1f;
		return weighDeficit((demand - deficit) / demand);
	}

	/** v3 (the user, 2026-10-27, docs/suppression-balance.md 10): vanilla's switch - 1 while the structure runs, 0 while it is disrupted. */
	public static float condition(Industry ind) {
		if (ind == null) return 0f;
		return ind.isDisrupted() ? 0f : 1f;
	}

	public static float condition(MarketAPI market, Industry ind) {
		return condition(ind);
	}


	/** Whether any defence structure is disrupted at all. */
	public static boolean anyDisrupted(MarketAPI market) {
		for (Industry ind : fortifications(market)) {
			if (ind.isDisrupted()) return true;
		}
		return false;
	}

	/** What the fortification multiplies the garrison by right now: the product of the structures' effective multipliers. */
	public static float fortificationMult(MarketAPI market) {
		float mult = 1f;
		for (Industry ind : fortifications(market)) {
			mult *= 1f + bonusOf(ind.getId()) * condition(market, ind) * deficitMult(ind);
		}
		return mult;
	}

	/**
	 * The batteries' share with every gun INTACT - the toll a fleet in orbit
	 * would pay if it had never suppressed anything. Only the fabrication of
	 * ground troops from the hulls reads it ({@link ThreatGroundFronts#fabricateCost});
	 * the duel itself always pays on the batteries' real condition.
	 */
	public static float intactBatteryShare(MarketAPI market) {
		float mult = 1f;
		for (Industry ind : fortifications(market)) {
			if (!isBattery(ind.getId())) continue;
			mult *= 1f + bonusOf(ind.getId()) * deficitMult(ind);
		}
		return mult <= 1f ? 0f : 1f - 1f / mult;
	}

	/** The batteries' share of the defence figure: 1 - 1 / their multiplier (0 without batteries). */
	public static float batteryShare(MarketAPI market) {
		float mult = 1f;
		for (Industry ind : fortifications(market)) {
			if (!isBattery(ind.getId())) continue;
			mult *= 1f + bonusOf(ind.getId()) * condition(market, ind) * deficitMult(ind);
		}
		return mult <= 1f ? 0f : 1f - 1f / mult;
	}

	/** "Heavy Batteries 40%" for every suppressed structure, the planetary shield included. */
	public static List<String> suppressedLines(MarketAPI market) {
		List<String> lines = new ArrayList<String>();
		if (market != null) return lines; // v3: nothing of ours suppresses a structure
		for (Industry ind : fortifications(market)) {
			if (!ind.isDisrupted()) continue;
			lines.add(ind.getCurrentName() + " " + Math.round(condition(market, ind) * 100f) + "%");
		}
		// the shield wears on the same clock but is no fortification (it buys
		// cover, not ground defence), so it is not in the list above
		Industry shield = ThreatShield.present(market) ? ThreatShield.get(market) : null;
		if (shield != null && shield.isDisrupted()) {
			lines.add(shield.getCurrentName() + " "
					+ Math.round(condition(market, shield) * 100f) + "%");
		}
		return lines;
	}

	/** No growth while saturation falls: the colony's incoming population held at nothing. */
	public void modifyIncoming(MarketAPI market, PopulationComposition incoming) {
		if (ThreatRazing.saturated(market)) {
			incoming.getWeight().modifyMult(getModId(), 0f, "Saturation bombardment");
		}
	}

	@Override
	public void apply() {
		super.apply(true);
		market.addTransientImmigrationModifier(this);
		// v3 (the user, 2026-10-27, docs/suppression-balance.md 10): a human colony's defence is
		// vanilla's - no districts multiplier, no condition-scaled bonus, no stability or
		// accessibility flat; everything an older jar wrote is unmodified so a save clears
		StatBonus defense = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		market.getStability().unmodifyFlat(getModId());
		market.getAccessibilityMod().unmodifyFlat(getModId());
		for (String id : FORTIFICATION_IDS) {
			defense.unmodifyMult(getModId() + "_" + id);
			market.getStability().unmodifyFlat(getModId() + "_" + id);
		}
	}

	/** The base stability vanilla gives each while it runs (GroundDefenses / MilitaryBase getBaseStabilityMod); not applied since 2026-10-24 (vanilla's on/off rules), kept for the record. */
	public static float stabilityOf(String industryId) {
		if (Industries.GROUNDDEFENSES.equals(industryId)) return 1f;
		if (Industries.HEAVYBATTERIES.equals(industryId)) return 1f;
		if (Industries.PATROLHQ.equals(industryId)) return 1f;
		if (Industries.MILITARYBASE.equals(industryId)) return 2f;
		if (Industries.HIGHCOMMAND.equals(industryId)) return 2f;
		return 0f;
	}

	@Override
	public void unapply() {
		super.unapply();
		market.removeTransientImmigrationModifier(this);
		StatBonus defense = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		for (String id : FORTIFICATION_IDS) {
			defense.unmodifyMult(getModId() + "_" + id);
			market.getStability().unmodifyFlat(getModId() + "_" + id);
		}
		market.getStability().unmodifyFlat(getModId());
		market.getAccessibilityMod().unmodifyFlat(getModId());
	}

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
	public float getPatherInterest() {
		return 0f;
	}
}
