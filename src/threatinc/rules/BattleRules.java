package threatinc.rules;

/**
 * Off-screen fight maths, shared by the mod and the offline simulator (docs/war-sim.md 2).
 * Pure: no game types, so tools/warsim compiles this file as it stands.
 */
public final class BattleRules {
	private BattleRules() {}

	public static final float MAX_LOSS = 0.75f;
	public static final float LOSS_PER_RATIO = 0.5f;

	/** The share of its strength a side loses in one off-screen fight against `enemy`. */
	public static float lossShare(float own, float enemy) {
		if (own <= 0f) return MAX_LOSS;
		return Math.min(MAX_LOSS, LOSS_PER_RATIO * enemy / own);
	}
}
