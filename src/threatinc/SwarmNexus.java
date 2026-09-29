package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;

/**
 * The military organ of a Threat colony - the hive's answer to a Patrol HQ or
 * Military Base: the growth-vats and command strata from which the colony's
 * Defense Swarms are fabricated and its expeditions staged. It produces no
 * commodity; what it supplies is FLEETS, paid for from the fleet points it
 * banks from the hulls it is delivered (ThreatColonyManager's fabrication
 * ledger). ThreatColonyManager gates garrison
 * respawn on it ({@code hasOperationalNexus}) and strike staging requires it
 * ({@code pickStrikeStaging}), so disrupting it - a raid or bombardment -
 * silences the colony militarily until it recovers: no new Defense Swarms,
 * no expeditions launched. Fleets already fabricated are unaffected; hulls
 * already grown keep fighting.
 *
 * Same architecture as {@link FabricationCore}: a real structure so it shows
 * in the colony UI and is raidable/disruptable through every vanilla surface,
 * hidden from the player's construction picker.
 */
public class SwarmNexus extends BaseIndustry {

	// The nexus's ground-defense contribution - the hive's stand-in for the
	// orbital-station and high-command multipliers vanilla colonies stack
	// (hive worlds have neither, which left even large colonies absurdly
	// cheap to bombard). Fire coordination by the hive-order: x1.5 intact at
	// the default nexusDefenseBonus, degrading while disrupted - a colony
	// whose war-strata have been raided is genuinely softer.

	@Override
	public void apply() {
		super.apply(true);
		// the nexus is the hive's military CONSUMER: growing Defense Swarms
		// eats the forge chain's hull output and machinery. It demands them
		// always (2026-09-29): the garrison has no capacity to reach, so there
		// is no Idle state, and a demand that does not move with the garrison
		// is what keeps the hulls it is delivered - the production it banks
		// (ThreatColonyManager.nexusDraw, the fabrication ledger) - from
		// oscillating with the deficit its own demand makes. Under vanilla's
		// economy a demand takes nothing from other worlds (docs/hive-economy.md)
		demand(com.fs.starfarer.api.impl.campaign.ids.Commodities.SHIPS,
				market.getSize());
		demand(com.fs.starfarer.api.impl.campaign.ids.Commodities.HEAVY_MACHINERY,
				Math.max(1, market.getSize() - 2));
		// wears down with the disruption days on the clock, like the batteries
		float resilience = ThreatColonyManager.disruptedDefenseResilience(this);
		com.fs.starfarer.api.combat.StatBonus defense = market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD);
		defense.modifyMult(getModId(),
				(1f + ThreatIncConfig.nexusDefenseBonus() * resilience)
						* ThreatIncConfig.groundDefenseMult(),
				getNameForModifier() + (isDisrupted() ? " (in refit)" : ""));

		// The deep defenses: hive ground strength is anchored to COLONY SIZE
		// (hiveDefensePerSize per size, default 500 - a size-8 hive fields a
		// 4000-point base before industry multipliers), not vanilla's shallow
		// base table. Topped up as a flat so the vanilla base + this = the
		// anchor, then the industry mults stack on top. Applied even while the
		// nexus is disrupted - the strata below the crust don't stop existing.
		// EXCEPT the strata a ground front has taken (docs/ground-war.md): the
		// war-strata ARE the base defense, so each stratum held strips one
		// size-worth of it - a front fighting inward gains real momentum, and
		// a hive counter-attack that retakes a stratum wins it back.
		int strataLeft = Math.max(0,
				market.getSize() - ThreatGroundFronts.strataHeld(market.getId()));
		float targetBase = ThreatIncConfig.hiveDefensePerSize() * strataLeft;
		float vanillaBase = com.fs.starfarer.api.impl.campaign.econ.impl
				.PopulationAndInfrastructure.getBaseGroundDefenses(market.getSize());
		float topUp = targetBase - vanillaBase;
		if (topUp > 0) {
			defense.modifyFlat(getModId(1), topUp, "Deep fabrication strata");
		} else {
			defense.unmodifyFlat(getModId(1));
		}

		// bombardment unrest cuts the hive's defence through vanilla's stability
		// multiplier, as it does a colony's (docs/suppression-balance.md v2):
		// the guns' return fire is what prices the spiral now. The cancel this
		// once carried is stripped from old saves here.
		defense.unmodifyMult(getModId(2));
	}

	@Override
	public void unapply() {
		super.unapply();
		com.fs.starfarer.api.combat.StatBonus defense = market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		defense.unmodifyFlat(getModId(1));
		defense.unmodifyMult(getModId(2));
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
}
