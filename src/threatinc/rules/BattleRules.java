package threatinc.rules;

/**
 * Off-screen fight and bombardment maths, shared by the mod and the offline simulator
 * (docs/war-sim.md 2, docs/war-sim-humans.md). Pure: no game types, so tools/warsim compiles
 * this file as it stands. Knob values are arguments; the mod passes ThreatIncConfig's.
 */
public final class BattleRules {
	private BattleRules() {}

	/** Vanilla's cap on what one off-screen fight takes (FGRaidAction.autoresolve). */
	public static final float MAX_LOSS = 0.75f;
	/** Vanilla's loss per unit of the other side's strength over one's own. */
	public static final float LOSS_PER_RATIO = 0.5f;
	/** Vanilla's FleetGroupIntel abort line: an expedition cut below this share of what sailed turns home. */
	public static final float GROUP_ABORT_FRACTION = 0.33f;

	/** The share of its strength a side loses in one off-screen fight against `enemy`. */
	public static float lossShare(float own, float enemy) {
		if (own <= 0f) return MAX_LOSS;
		return Math.min(MAX_LOSS, LOSS_PER_RATIO * enemy / own);
	}

	/** ThreatAbstractBattle.defenderLoss: the share each defender loses; none when either side is nothing. */
	public static float defenderLoss(float attackerStr, float defenderStr) {
		if (attackerStr <= 0f || defenderStr <= 0f) return 0f;
		return Math.min(MAX_LOSS, LOSS_PER_RATIO * attackerStr / defenderStr);
	}

	/** ThreatPurgeFGI.dailyDay: the share the besieger (and the hunts beside it) loses in one day's exchange. */
	public static float dayShare(float mine, float enemy) {
		return Math.min(MAX_LOSS, LOSS_PER_RATIO * enemy / mine);
	}

	/** The daily siege's call-off (and breaksOff's): outweighed by `ratio` with its friends in the system counted. */
	public static boolean callsOff(float enemy, float ours, float friends, float ratio) {
		return ratio > 0f && enemy >= (ours + friends) * ratio;
	}

	/** Whether what is left of the defence still contests the orbit against `now` (orbitContestFraction). */
	public static boolean orbitContested(float left, float now, float contestFraction) {
		return left > 0f && left >= now * Math.max(0f, contestFraction);
	}

	/**
	 * ThreatGroundFronts.suppressionRate: disruption days a day of bombardment adds to a structure
	 * still whole - the theatre's rate x fleet / (fleet + defence), the fleet weighed by siegeFPWeight.
	 */
	public static float suppressionRate(float fp, float defence, float fpWeight, float daysPerDay) {
		if (fp <= 0f) return 0f;
		float weighted = fp * Math.max(0f, fpWeight);
		return daysPerDay * weighted / Math.max(1f, weighted + Math.max(0f, defence));
	}

	/** ThreatGroundFronts.conditionAfterDay: a structure's condition after one more day at `rate`. */
	public static float conditionAfterDay(float cond, float rate, float through, float wearDays) {
		return Math.max(0f, cond - rate * cond * through / wearDays);
	}

	/** A fortification's condition from its disruption clock: 1 intact, 0 at wearDays. */
	public static float condition(float clockDays, float wearDays) {
		return Math.max(0f, Math.min(1f, 1f - clockDays / Math.max(1f, wearDays)));
	}

	/** ThreatGroundFronts.bombardFuelPerDay. */
	public static float bombardFuelPerDay(float fp, float fuelPerFPDay) {
		return Math.max(0f, fuelPerFPDay) * Math.max(0f, fp);
	}

	/** ThreatGroundFronts.bombardDaysFor: days this much fuel buys; with no price, as long as it likes. */
	public static float bombardDaysFor(float fuel, float perDay) {
		if (perDay <= 0f) return Float.MAX_VALUE;
		return Math.max(0f, fuel) / perDay;
	}

	/** ThreatGroundFronts.returnFirePerDay: fleet points the guns take a day, set by the guns' share of the defence. */
	public static float returnFirePerDay(float perGunDefence, float gunDefence) {
		return Math.max(0f, perGunDefence) * gunDefence;
	}

	/** ThreatGroundFronts.gunDefence: the figure times the batteries' share. */
	public static float gunDefence(float defence, float batteryShare) {
		return Math.max(0f, defence) * batteryShare;
	}

	/** Attacker-to-defender odds at which a counter-attack overruns: 2 ^ (1 / groundStrengthExponent). */
	public static float overrunOdds(float exponent) {
		float e = Math.max(0.1f, exponent);
		return (float) Math.pow(2f, 1f / e);
	}

	/**
	 * IncursionManager.beachheadNeeded: the landing that survives the first counter-attack
	 * (siegeBeachheadMargin over the 2:1 line at frontLandingMult). 0 sizes for the raids only.
	 */
	public static float beachheadNeeded(float defenderStr, float margin, float exponent, float landingMult) {
		if (margin <= 0f || defenderStr <= 0f) return 0f;
		return defenderStr / (Math.max(0.05f, landingMult) * overrunOdds(exponent)) * margin;
	}

	/**
	 * IncursionManager.raidStrNeededAt for a defence already worn: vanilla's disruption threshold
	 * (0.25) of raid effectiveness with headroom (1.25), never below the beachhead.
	 */
	public static float raidStrNeeded(float wornDefence, float threshold, float headroom, float beachhead) {
		float raid = wornDefence * threshold / Math.max(0.01f, 1f - threshold) * headroom;
		return Math.max(raid, beachhead);
	}

	/** ThreatFleetOrders.raidOver: the fleet points below which a raid has lost its share (raidLossFraction) and goes home. */
	public static float raidLossLine(float arrivalFP, float lossFraction) {
		return arrivalFP * (1f - Math.max(0f, Math.min(1f, lossFraction)));
	}
}
