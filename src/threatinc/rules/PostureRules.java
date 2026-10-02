package threatinc.rules;

/**
 * The swarm's defensive posture, shared by the mod and the offline simulator (docs/war-sim-swarm.md).
 * Pure: no game types. Knob values are passed in.
 */
public final class PostureRules {
	private PostureRules() {}

	/** What is left of a pressure reading after {@code days} (ThreatPosture.decay). */
	public static float decay(float days, float decayDays) {
		return (float) Math.exp(-Math.max(0f, days) / decayDays);
	}

	/**
	 * The pending claims trySpread may hold with this many free forges (ThreatPosture.claimCap):
	 * all of them while the appetite is unread (below 0), else the appetite's share weighed by
	 * the stance's expansion share - at least one while above 0, none at 0.
	 */
	public static int claimCap(int freeForges, float appetite, float expansionShare) {
		if (appetite < 0f) return freeForges;
		float a = Math.min(1f, appetite) * expansionShare;
		if (a <= 0f || freeForges <= 0) return 0;
		return Math.max(1, (int) Math.ceil(freeForges * a));
	}

	/** Fleet points a colony may give away: what it holds above want by the band and one swarm (ThreatPosture.releasableFP). */
	public static float releasableFP(float heldFP, float wantFP, float band, float oneSwarmFP) {
		return heldFP - wantFP * (1f + band) - oneSwarmFP;
	}

	/** Days a surplus fleet's upkeep takes to cost what recycling it loses (ThreatPosture.breakEvenDays). */
	public static float breakEvenDays(float hullShare, float upkeepPerMonth) {
		if (upkeepPerMonth <= 0f) return Float.MAX_VALUE;
		return Math.max(0f, 1f - hullShare) / upkeepPerMonth * 30f;
	}
}
