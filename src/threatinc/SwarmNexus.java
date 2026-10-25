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
		// RETIRED (phase D, docs/strata-are-the-threat.md 4): the strata are the hive's military. The class
		// stays so older saves load; ThreatColonyManager.retireMilitaryLayer removes it on their first tick
		super.apply(true);
	}

	@Override
	public void unapply() {
		super.unapply();
		com.fs.starfarer.api.combat.StatBonus defense = market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		defense.unmodifyFlat(getModId(1));
		defense.unmodifyMult(getModId(2));
		ThreatHulls.unapplyPatrols(market, getModId(3));
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
