package threatinc.rules;

/**
 * Passage and spread maths, shared by the mod and the offline simulator (docs/war-sim-swarm.md).
 * Pure: no game types. Knob values are passed in.
 */
public final class SpreadRules {
	private SpreadRules() {}

	/**
	 * Fuel a fleet of {@code fp} burns over {@code ly} (ThreatFuel.passage): fuelPerPointLY a
	 * response point (fpPerPoint fleet points) a light year there and back; one way pays
	 * 1 - returnLegShare of it.
	 */
	public static float passageFuel(float fp, float ly, boolean roundTrip, float fpPerPoint, float fuelPerPointLY,
			float returnLegShare) {
		if (fp <= 0f || ly <= 0f) return 0f;
		float fuel = fp / fpPerPoint * ly * fuelPerPointLY;
		return roundTrip ? fuel : fuel * (1f - returnLegShare);
	}

	/** Days a fleet takes over {@code ly} (ThreatReach.days). */
	public static float days(float ly, float lyPerDay) {
		return Math.max(0f, ly) / lyPerDay;
	}

	/** Days a fleet is away on a trip of {@code ly} (ThreatReach.daysAway). */
	public static float daysAway(float ly, boolean roundTrip, float stay, float lyPerDay) {
		return days(ly, lyPerDay) * (roundTrip ? 2f : 1f) + Math.max(0f, stay);
	}

	/**
	 * A claim's weight under billed reach (IncursionManager.pickSpreadTarget): what the system
	 * is needed for and the share of a siege's days it could be reinforced in, over the days to
	 * the nearest hive and, where a faction at peace stands, the days a strike at it would be away.
	 */
	public static float billedWeight(float need, float holdShare, float daysToHive, float peaceStrikeDays) {
		return (1f + need * 0.01f) * holdShare / (Math.max(1f, daysToHive) * peaceStrikeDays);
	}
}
