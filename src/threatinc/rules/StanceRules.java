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

	/**
	 * Whether enough of the hive is pressed for CONSOLIDATE (ThreatStance.evaluate): the share of its systems
	 * pressed reaches {@code need}, and at least {@code minPressed} systems are pressed - or every one of them is.
	 * A swarm of two systems with one besieged is not "half pressed" (hw4s, 2026-10-04: one starved system of two
	 * held it in CONSOLIDATE, which founds nothing, for 65 months). minPressed 0: the share alone, as before.
	 */
	public static boolean pressedEnough(int pressed, int systems, float need, int minPressed) {
		float share = systems > 0 ? pressed / (float) systems : 0f;
		if (share < need) return false;
		return pressed >= Math.min(Math.max(0, minPressed), systems);
	}

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

	/**
	 * How far the swarm is losing the war, 0-1 (ThreatStance.losingPressure; the user, 2026-10-07: "a series of
	 * worlds falling not just a one off"): the larger of two trends over the losing window. Hives: {@code fallen}
	 * below the window's {@code peak}, nothing for one (the cost of war), full at {@code hiveShare} of the peak.
	 * The exchange: what it lost beyond what it sank, against what its forges {@code made} over the window,
	 * full at {@code exchangeShare} of it; nothing while it sinks as much as it loses.
	 */
	public static float losingPressure(int fallen, int peak, float lost, float killed, float made,
			float hiveShare, float exchangeShare) {
		float hives = 0f;
		if (fallen >= 2 && peak > 0) hives = fallen / Math.max(1f, peak * Math.max(0.01f, hiveShare));
		float exchange = 0f;
		if (lost > killed) exchange = (lost - killed) / Math.max(1f, made * Math.max(0.01f, exchangeShare));
		return Math.max(0f, Math.min(1f, Math.max(hives, exchange)));
	}

	/** The spread weight's lean away from the strongest rival, {@code ly} to its nearest world (ThreatStance.spreadMult). */
	public static float spreadMult(float ly) {
		return (float) Math.sqrt(1f + ly);
	}
}
