package threatinc.rules;

/**
 * The war reserves' arithmetic (ThreatReserves), shared by the mod and the offline simulator
 * (docs/war-sim-humans.md). Pure: knob values are arguments.
 */
public final class ReserveRules {
	private ReserveRules() {}

	/** ThreatReserves.accrualPer30 past its guards: the baseline plus the banked surplus at the faction's production share. */
	public static float accrualPer30(float baseline, float sizeMultOfSurplus, float econUnit, float surplusMult,
			float productionShare) {
		return baseline + sizeMultOfSurplus * econUnit * surplusMult * productionShare;
	}

	/** ThreatReserves.monthsBasis (unbacked): reserveCapMonths of the colony's own banking. */
	public static float monthsBasis(float accrualPer30, float capMonths) {
		return accrualPer30 * capMonths;
	}

	/**
	 * ThreatReserves.floor's basis: the live basis, or the largest the depot has seen
	 * (recorded at full production share) scaled to the share in force, whichever is more.
	 */
	public static float floorBasis(float basis, float seen, float baselineMonths, float share) {
		float atShare = seen > baselineMonths ? baselineMonths + (seen - baselineMonths) * share : seen;
		return atShare > basis ? atShare : basis;
	}

	/** ThreatReserves.noteBasis: the basis as recorded, its surplus part at full production share. */
	public static float basisAtFullShare(float basis, float baselineMonths, float share) {
		if (share > 0f && basis > baselineMonths) return baselineMonths + (basis - baselineMonths) / share;
		return basis;
	}

	/** ThreatReserves.available past its guards: stock above the floor. */
	public static float available(float stock, float floor) {
		return Math.max(0f, stock - floor);
	}

	/**
	 * ThreatReserves.spendable past its guards: stock above the floor, the donor keep share of
	 * the months basis and the staging bank - what a hunt or a convoy may take.
	 */
	public static float spendable(float stock, float floor, float monthsBasis, float donorKeepFraction, float stagingBank) {
		float keep = Math.max(floor, monthsBasis * donorKeepFraction + stagingBank);
		return Math.max(0f, stock - keep);
	}

	/** ThreatReserves.seed: a colony's stock at mobilisation, never below what it already holds. */
	public static float seeded(float have, float accrualPer30, float initialMonths) {
		float start = accrualPer30 * initialMonths;
		return have < start ? start : have;
	}
}
