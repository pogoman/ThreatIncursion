package threatinc.rules;

/**
 * The attack planner's arithmetic (ThreatAttackPlanner, ThreatIntel), shared by the mod and
 * the offline simulator (docs/war-sim-humans.md). Pure: knob values are arguments.
 */
public final class PlannerRules {
	private PlannerRules() {}

	/** ThreatAttackPlanner.RAID_SLACK_DAYS. */
	public static final float RAID_SLACK_DAYS = 10f;

	/** ThreatIntel.trust: how far a picture `age` days old is trusted after `moreDays` more - 0.5 ^ (days / half-life). */
	public static float trust(float age, float moreDays, float halfLifeDays) {
		float half = Math.max(1f, halfLifeDays);
		return (float) Math.pow(0.5, (age + Math.max(0f, moreDays)) / half);
	}

	/** ThreatAttackPlanner.chance, one prong at a time: the miss so far times this prong's (1 - trust). */
	public static float miss(float missSoFar, float trust) {
		return missSoFar * (1f - Math.max(0f, Math.min(1f, trust)));
	}

	/** The chance at least one siege in flight lands as planned: 1 - the product of (1 - trust). */
	public static float chance(float[] trusts) {
		float miss = 1f;
		for (float t : trusts) miss = miss(miss, t);
		return 1f - miss;
	}

	/** Whether a plan is due: news, or the interval run out (a last plan "after" today is an old sentinel: due). */
	public static boolean planDue(boolean news, float today, float lastPlanned, float intervalDays) {
		return news || today < lastPlanned || today - lastPlanned >= Math.max(1f, intervalDays);
	}

	/** ThreatAttackPlanner.raidOption: the fleet points that hold an orbit against `reported` (the contest rule), 0 for none seen. */
	public static float orbitToContest(float reported, float contestFraction) {
		return reported > 0f ? reported / Math.max(0.01f, contestFraction) + 1f : 0f;
	}

	/** A raid's size: the least fleet whose day buys a day down, the contest's, and never below guardFleetFP. */
	public static float raidFP(float leastForGain, float orbit, float guardFleetFP) {
		return Math.max(Math.max(leastForGain, orbit), Math.max(1f, guardFleetFP));
	}

	/** A raid's score, lower the better: its cost at base prices per day down bought times trust. */
	public static float raidScore(float cost, float daysDown, float trust) {
		return cost / Math.max(0.01f, daysDown * Math.max(0.01f, trust));
	}

	/** A raid's term: there and back, its planned stay, and the slack to fight for the orbit and settle. */
	public static float raidDays(float travel, float stay) {
		return 2f * travel + stay + RAID_SLACK_DAYS;
	}

	/** ThreatIntel.moved, one world: swarms that changed by half or more of the larger figure, and by a point. */
	public static boolean moved(float was, float now) {
		return !(Math.abs(now - was) < 0.5f * Math.max(was, now) || Math.abs(now - was) < 1f);
	}

	/** ThreatIntel.twoFigures: radar's precision (3,412 -> 3,400; 63 -> 63). */
	public static float twoFigures(float x) {
		if (x <= 0f) return 0f;
		double mag = Math.pow(10, Math.floor(Math.log10(x)) - 1);
		return (float) (Math.round(x / mag) * mag);
	}

	/**
	 * ThreatAttackPlanner.leastForGainNow's search over a monotone gain: the least fleet points
	 * whose day buys a day down, by bisection between 1 and 1,000,000; Float.MAX_VALUE when none.
	 */
	public interface Gain {
		float at(float fp);
	}

	public static float leastForGain(Gain gain) {
		float hi = 1000000f;
		if (gain.at(hi) < 1f) return Float.MAX_VALUE;
		float lo = 1f;
		if (gain.at(lo) >= 1f) return lo;
		for (int i = 0; i < 40 && hi - lo > 1f; i++) {
			float mid = (lo + hi) * 0.5f;
			if (gain.at(mid) >= 1f) hi = mid;
			else lo = mid;
		}
		return (float) Math.ceil(hi);
	}
}
