package threatinc.rules;

/**
 * Strike gates, shared by the mod and the offline simulator (docs/war-sim-swarm.md).
 * Pure: no game types. Knob values are passed in.
 */
public final class StrikeRules {
	private StrikeRules() {}

	/**
	 * Whether the hive can pay a fleet away for {@code days} (ThreatReach.canSustain): its
	 * supplies a month out of the spare flow, else the whole bill out of the flow over the
	 * trip and the stock above one founding kit.
	 */
	public static boolean canSustain(float needPerMonth, float flowPerMonth, float days, float freeStock) {
		if (needPerMonth <= flowPerMonth) return true;
		float months = Math.max(1f, days) / 30f;
		return needPerMonth * months <= flowPerMonth * months + freeStock;
	}

	/** The most fleet points canSustain lets away for {@code days} (ThreatReach.sustainableFP). */
	public static float sustainableFP(float flowPerMonth, float days, float freeStock, float suppliesPerFP) {
		float months = Math.max(1f, days) / 30f;
		return Math.max(0f, flowPerMonth + freeStock / months) / suppliesPerFP;
	}

	/** A world's worth to a strike before the alarm's grudge (IncursionManager.strikeValue): its size squared. */
	public static float sizeValue(int size) {
		return size * size;
	}
}
