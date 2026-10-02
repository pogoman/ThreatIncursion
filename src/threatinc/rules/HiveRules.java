package threatinc.rules;

/**
 * A hive colony's economy, shared by the mod and the offline simulator (docs/war-sim-swarm.md).
 * Pure: no game types, so tools/warsim compiles this file as it stands. Knob values are passed in.
 */
public final class HiveRules {
	private HiveRules() {}

	/** Days of income a seed colony, or a save's first sight of a stock, is endowed with. */
	public static final float ENDOWMENT_DAYS = 180f;

	/**
	 * Fleet points a day a nexus banks (ThreatColonyManager.fabricationRatePerDay): its draw's
	 * share of the hive's forge output, never more than it draws, at fpPerUnit30d a unit a month.
	 */
	public static float fabricationRatePerDay(float draw, float hiveOutput, float hiveDraw, float fpPerUnit30d) {
		if (draw <= 0f || hiveDraw <= 0f || hiveOutput <= 0f) return 0f;
		float share = Math.min(1f, hiveOutput / hiveDraw);
		return draw * share * fpPerUnit30d / 30f;
	}

	/** Upkeep a day on this many fleet points (garrisonUpkeepPerMonth per 30 days). */
	public static float upkeepPerDay(float fleetFP, float upkeepPerMonth) {
		return Math.max(0f, fleetFP) * Math.max(0f, upkeepPerMonth) / 30f;
	}

	/** What a seed colony out of the Abyss lands with (ThreatColonyManager.endowSeed). */
	public static float seedEndowment(float days, int size, float fpPerUnit30d) {
		return days * size * fpPerUnit30d / 30f;
	}

	/** Supplies a month a colony of this size takes (ThreatColonyUpkeep.perMonth): 0 below size 3. */
	public static float sizeUpkeepPerMonth(int size, float at3, float ratio) {
		if (size < 3) return 0f;
		return Math.max(0f, at3) * (float) Math.pow(Math.max(1f, ratio), size - 3);
	}

	/** The share of its upkeep that holds a colony at its size, from the knob. */
	public static float breakEven(float knob) {
		return Math.max(0.05f, Math.min(0.95f, knob));
	}

	/**
	 * Growth pace for the share of its upkeep a colony was paid (ThreatColonyUpkeep.growthRate):
	 * 1 paid in full, 0 at the break-even share {@code t}, -1 paid nothing.
	 */
	public static float growthRate(float fed, float t) {
		if (fed >= t) return Math.min(1f, (fed - t) / (1f - t));
		return -Math.min(1f, (t - fed) / t);
	}

	/**
	 * Garrison composition by colony size (ThreatColonyManager.desiredGarrison): each row one
	 * fleet as {fabricators, escort tier}, tiers 0 LOW, 1 MEDIUM, 2 HIGH, 3 MAXIMUM.
	 */
	public static int[][] desiredGarrison(int size, int low, int med, int high, int max) {
		if (size <= 2) return new int[][] {{0, low}};
		if (size == 3) return new int[][] {{0, med}, {0, med}};
		if (size == 4) return new int[][] {{0, med}, {0, med}, {0, med}};
		if (size == 5) return new int[][] {{0, med}, {0, med}, {0, high}, {1, med}};
		if (size == 6) return new int[][] {{0, high}, {0, high}, {0, high}, {1, high}};
		if (size == 7) return new int[][] {{0, high}, {0, high}, {0, high}, {0, high}, {1, high}};
		return new int[][] {{0, high}, {0, high}, {0, max}, {1, max}, {2, high}};
	}

	/** What a swarm of this spec costs before one has been built (ThreatColonyManager.swarmCostEstimate's table). */
	public static float swarmCostFallback(int fabricators, int tier) {
		float[] byTier = { 46f, 134f, 347f, 458f };
		float base = byTier[Math.max(0, Math.min(byTier.length - 1, tier))];
		return base + 40f * Math.max(0, fabricators);
	}

	/** Days a full level takes at full pace (colonyGrowthBaseDays a size). */
	public static float daysPerLevel(float baseDays, int size, float timeScale) {
		return baseDays * size * timeScale;
	}
}
