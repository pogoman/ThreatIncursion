package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;

/**
 * The military organ of a Threat colony - the hive's answer to a Patrol HQ or
 * Military Base: the growth-vats and command strata from which the colony's
 * Defense Swarms are fabricated and its expeditions staged. It produces no
 * commodity; what it supplies is FLEETS. ThreatColonyManager gates garrison
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

	/**
	 * Whether the nexus has nothing to build: the garrison stands at its full
	 * NOMINAL strength (the size table, not the economy-scaled figure - a
	 * strained colony still WANTS more swarms and keeps consuming to get
	 * them). An idle nexus draws nothing from the hive economy; the moment
	 * swarms are mustered for an expedition or killed, it wakes and consumes
	 * again. Deliberately based on the static table so the state can't
	 * oscillate with the very deficits its own demand creates.
	 */
	public boolean isIdleAtCapacity() {
		if (market == null || isDisrupted()) return false;
		int nominal = ThreatColonyManager.desiredGarrison(market.getSize()).length;
		return ThreatColonyManager.countLiveGarrison(market.getId()) >= nominal;
	}

	@Override
	public String getCurrentName() {
		if (isIdleAtCapacity()) return super.getCurrentName() + " - Idle";
		return super.getCurrentName();
	}

	/**
	 * The nexus's ground-defense contribution - the hive's stand-in for the
	 * orbital-station and high-command multipliers vanilla colonies stack
	 * (hive worlds have neither, which left even large colonies absurdly
	 * cheap to bombard). Fire coordination by the hive-order: x1.5 intact,
	 * halved to x1.25 while the nexus is disrupted by enemy action - a
	 * colony whose war-strata have been raided is genuinely softer.
	 */
	public static final float DEFENSE_BONUS = 0.5f;

	@Override
	public void apply() {
		super.apply(true);
		// the nexus is the hive's military CONSUMER: growing Defense Swarms
		// eats the forge chain's hull output and machinery. At capacity it
		// goes cold - no demand, "Idle" in the colony UI - so a rear-echelon
		// world with a full garrison stops drawing on the network's resources
		// while frontier colonies rebuild theirs
		if (!isIdleAtCapacity()) {
			demand(com.fs.starfarer.api.impl.campaign.ids.Commodities.SHIPS,
					market.getSize());
			demand(com.fs.starfarer.api.impl.campaign.ids.Commodities.HEAVY_MACHINERY,
					Math.max(1, market.getSize() - 2));
		}
		float resilience = isDisrupted() ? ThreatGroundDefenses.DISRUPTED_DEFENSE_FRACTION : 1f;
		market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD)
				.modifyMult(getModId(),
						(1f + DEFENSE_BONUS * resilience) * ThreatIncConfig.groundDefenseMult(),
						getNameForModifier() + (isDisrupted() ? " (in refit)" : ""));
	}

	@Override
	public void unapply() {
		super.unapply();
		market.getStats().getDynamic()
				.getMod(com.fs.starfarer.api.impl.campaign.ids.Stats.GROUND_DEFENSES_MOD)
				.unmodifyMult(getModId());
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
