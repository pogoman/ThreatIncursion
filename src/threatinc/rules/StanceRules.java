package threatinc.rules;

/**
 * The swarm's sector stance, shared by the mod and the offline simulator (docs/war-sim-swarm.md).
 * Pure: no game types. Knob values are passed in.
 */
public final class StanceRules {
	private StanceRules() {}

	public static final int EXPAND = 0;
	public static final int PRESS = 1;
	public static final int CONSOLIDATE = 2;

	/** The share of the surplus the stance gives expansion (ThreatStance.expansionShare). */
	public static float expansionShare(int stance, float secondaryShare) {
		if (stance == CONSOLIDATE) return 0f;
		if (stance == PRESS) return Math.max(0f, Math.min(1f, secondaryShare));
		return 1f;
	}

	/** The share of the supplies' net the colonies' growth may take, by stance (ThreatColonyUpkeep.feedShare). */
	public static float feedShare(int stance, float expand, float press, float consolidate) {
		float share = stance == PRESS ? press : stance == CONSOLIDATE ? consolidate : expand;
		return Math.max(0f, Math.min(1f, share));
	}

	/** The spread weight's lean away from the strongest rival, {@code ly} to its nearest world (ThreatStance.spreadMult). */
	public static float spreadMult(float ly) {
		return (float) Math.sqrt(1f + ly);
	}
}
