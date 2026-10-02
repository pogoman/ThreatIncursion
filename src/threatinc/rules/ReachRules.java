package threatinc.rules;

/**
 * What a voyage costs and how far a depot reaches, shared by the mod and the offline simulator
 * (docs/war-sim-humans.md). Pure: knob values are arguments.
 */
public final class ReachRules {
	private ReachRules() {}

	/** IncursionManager.FP_PER_RESPONSE_DIFFICULTY: fleet points a difficulty point of flotilla stands for. */
	public static final float FP_PER_POINT = 25f;
	/** ThreatReach.DEFAULT_FACTION_SUPPLIES_PER_FP: supplies a month a fleet point of a human fleet burns. */
	public static final float DEFAULT_SUPPLIES_PER_FP = 0.94f;
	/** ThreatReach.SMALLEST_FLOTILLA_POINTS: what a base's reach is measured with. */
	public static final int SMALLEST_FLOTILLA_POINTS = 10;

	/** ThreatReach.days: days to cross `ly` at the board's estimated speed. */
	public static float days(float ly, float lyPerDay) {
		return Math.max(0f, ly) / lyPerDay;
	}

	/** IncursionManager.razeArrivalDays: the longest preparation (14 d), the passage, and a day. */
	public static float siegeArrivalDays(float ly, float lyPerDay) {
		return 14f + (float) Math.ceil(ly / lyPerDay) + 1f;
	}

	/** IncursionManager.expeditionPassage: difficulty points x light-years x expeditionFuelPerPointLY. */
	public static float passageFuel(float points, float ly, float fuelPerPointLY) {
		return points * ly * fuelPerPointLY;
	}

	/**
	 * {fuel, supplies} a force of `fp` is provisioned with to sail `ly`: ThreatFleetOrders.sortieWants,
	 * ThreatFrontlines.voyageCost and ThreatConvoys.escortRate (fp = 1).
	 */
	public static float[] voyageCost(float fp, float ly, float fuelPerPointLY, float suppliesPerPoint) {
		float points = fp / FP_PER_POINT;
		return new float[] { points * ly * fuelPerPointLY, points * suppliesPerPoint };
	}

	/**
	 * Difficulty points a stock of fuel and supplies provisions at these prices per point;
	 * Float.MAX_VALUE when a point costs nothing (the payableFP family).
	 */
	public static float payablePoints(float fuel, float supplies, float fuelPerPoint, float suppliesPerPoint) {
		float points = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) points = Math.min(points, fuel / fuelPerPoint);
		if (suppliesPerPoint > 0f) points = Math.min(points, supplies / suppliesPerPoint);
		return points;
	}

	/** ThreatReach.tripSupplies: supplies a fleet of `fp` burns away for `days`. */
	public static float tripSupplies(float fp, float suppliesPerFPMonth, float days) {
		return Math.max(0f, fp) * suppliesPerFPMonth * Math.max(0f, days) / 30f;
	}

	/** IncursionManager.siegeSuppliesPerPoint: the hulls' deposit plus the ships' supplies for the whole trip. */
	public static float siegeSuppliesPerPoint(float depositPerPoint, float suppliesPerFPMonth, float tripDays) {
		return depositPerPoint + tripSupplies(FP_PER_POINT, suppliesPerFPMonth, tripDays);
	}

	/** IncursionManager.siegeTripDays: muster and passage out, the stay, the passage home. */
	public static float siegeTripDays(float ly, float stay, float lyPerDay) {
		return siegeArrivalDays(ly, lyPerDay) + Math.max(0f, stay) + days(ly, lyPerDay);
	}

	/**
	 * ThreatReach.rangeOn: how far pooled fuel and supplies send the smallest siege flotilla -
	 * the passage's fuel, and above the hulls' deposit its ships' supplies for a strike's days
	 * there and back.
	 */
	public static float baseRangeLY(float fuel, float supplies, float fuelPerPointLY, float suppliesPerPoint,
			float suppliesPerFPMonth, float prepDays, float lyPerDay) {
		int points = SMALLEST_FLOTILLA_POINTS;
		float fp = points * FP_PER_POINT;
		float perLY = points * fuelPerPointLY;
		float byFuel = perLY > 0f ? fuel / perLY : Float.MAX_VALUE;
		float deposit = points * suppliesPerPoint;
		float perMonth = fp * suppliesPerFPMonth;
		float days = perMonth > 0f ? (supplies - deposit) * 30f / perMonth : Float.MAX_VALUE;
		float bySupplies = days >= Float.MAX_VALUE ? Float.MAX_VALUE : Math.max(0f, days - prepDays) * lyPerDay / 2f;
		return Math.max(0f, Math.min(byFuel, bySupplies));
	}

	/** ThreatConvoys.haulFuel: fuel a voyage of `ly` burns, the base escort's whatever escort sails. */
	public static float haulFuel(float escortFP, float ly, float fuelPerPointLY) {
		return Math.max(0f, escortFP) * voyageCost(1f, Math.max(0f, ly), fuelPerPointLY, 0f)[0];
	}

	/** ThreatConvoys.haulPerUnit: fuel a unit pooled across a voyage burns, as if it sailed in reference loads. */
	public static float haulPerUnit(float haulFuel, float load) {
		return load > 0f ? haulFuel / load : 0f;
	}

	/**
	 * ThreatConvoys.netOfHaul: of `have`, what arrives paid for at `perUnit` - fuel pays its own
	 * passage out of the load, anything else as much as `fuelHave` pays for.
	 */
	public static float netOfHaul(boolean isFuel, float have, float fuelHave, float perUnit) {
		if (have <= 0f) return 0f;
		if (perUnit <= 0f) return have;
		if (isFuel) return have / (1f + perUnit);
		return Math.min(have, Math.max(0f, fuelHave) / perUnit);
	}
}
