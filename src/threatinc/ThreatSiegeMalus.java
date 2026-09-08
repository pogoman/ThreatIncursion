package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
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
 * clock - so bombardment is a dial, not a switch. From orbit alone the
 * condition never falls below {@code fortificationOrbitFloor}; once a front
 * stands on the world the floor is 0: boots finish what orbit started. The
 * rule applies to colonies the swarm has besieged
 * ({@link ThreatGroundFronts#BESIEGED_FLAG}) or landed on, not to every raid
 * in the sector.
 *
 * <p><b>Districts.</b> Districts held strip (size - held) / size of the whole
 * figure (the human-world counterpart of the stripping {@link SwarmNexus}
 * does for a hive's strata), and each district held costs the colony
 * stability and accessibility.
 *
 * <p>Installed while a front stands or any defence structure is disrupted,
 * removed otherwise ({@link ThreatGroundFronts#syncSiegeState}). Modelled on
 * {@link WarFootingDemand}: never shown, never buildable, never disrupted,
 * never raided, no upkeep.
 */
public class ThreatSiegeMalus extends BaseIndustry {

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

	/** Whether the orbital floor still protects the structures: no front stands on the world. */
	public static boolean floorApplies(MarketAPI market) {
		return market == null || ThreatGroundFronts.getFront(market.getId()) == null;
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
		return Math.max(0f, Math.min(1f, (demand - deficit) / demand));
	}

	/** 1 intact .. 0 fully suppressed, from the structure's disruption clock. */
	public static float condition(Industry ind, boolean floor) {
		if (ind == null) return 0f;
		if (!ind.isDisrupted()) return 1f;
		float full = Math.max(1f, ThreatIncConfig.fortificationDisruptDays());
		float raw = Math.max(0f, 1f - ind.getDisruptedDays() / full);
		if (floor) raw = Math.max(raw, ThreatIncConfig.fortificationOrbitFloor());
		return Math.min(1f, raw);
	}

	public static float condition(MarketAPI market, Industry ind) {
		return condition(ind, floorApplies(market));
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

	@Override
	public void apply() {
		super.apply(true);
		StatBonus defense = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		int size = Math.max(1, market.getSize());
		// a colony that shrank onto its held districts still defends its last one
		int held = Math.max(0, Math.min(size - 1, ThreatGroundFronts.strataHeld(market.getId())));

		// districts: the garrison's share, and the colony's order
		if (held <= 0) {
			defense.unmodifyMult(getModId());
			market.getStability().unmodifyFlat(getModId());
			market.getAccessibilityMod().unmodifyFlat(getModId());
		} else {
			defense.modifyMult(getModId(), (float) (size - held) / (float) size,
					"Districts lost to the ground front");
			float stability = ThreatIncConfig.districtStabilityPenalty() * held;
			if (stability > 0f) {
				market.getStability().modifyFlat(getModId(), -stability,
						"Districts held by the invaders");
			} else {
				market.getStability().unmodifyFlat(getModId());
			}
			float access = ThreatIncConfig.districtAccessPenalty() * held;
			if (access > 0f) {
				market.getAccessibilityMod().modifyFlat(getModId(), -access,
						"Districts held by the invaders");
			} else {
				market.getAccessibilityMod().unmodifyFlat(getModId());
			}
		}

		// fortification: a suppressed structure keeps its bonus in proportion
		// to its condition (vanilla stripped it whole) and to its inputs, as
		// vanilla scales it when it runs
		boolean floor = floorApplies(market);
		for (String id : FORTIFICATION_IDS) {
			String key = getModId() + "_" + id;
			Industry ind = market.getIndustry(id);
			if (ind == null || !ind.isDisrupted() || (ind.isBuilding() && !ind.isUpgrading())) {
				defense.unmodifyMult(key);
				continue;
			}
			float cond = condition(ind, floor) * deficitMult(ind);
			if (cond <= 0f) {
				defense.unmodifyMult(key);
				continue;
			}
			defense.modifyMult(key, 1f + bonusOf(id) * cond, ind.getCurrentName()
					+ " (suppressed, " + Math.round(cond * 100f) + "% effect)");
		}
	}

	@Override
	public void unapply() {
		super.unapply();
		StatBonus defense = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		for (String id : FORTIFICATION_IDS) {
			defense.unmodifyMult(getModId() + "_" + id);
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
