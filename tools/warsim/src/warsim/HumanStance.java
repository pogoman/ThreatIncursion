package warsim;

import threatinc.rules.ReachRules;

/**
 * A faction's stance (ThreatFactionStance, the planner's - a war council would set it instead):
 * CONSOLIDATE when pressed and losing or outweighed (no new links, sieges only at systems facing
 * it), PRESS when unpressed with stancePressRatio over the swarm facing it and a siege it can pay
 * for (a link on stanceSecondaryShare of passes), else EXPAND. Read every EVAL_DAYS.
 */
final class HumanStance {
	private HumanStance() {}

	static final int EXPAND = 0, PRESS = 1, CONSOLIDATE = 2;
	static final String[] NAMES = { "EXPAND", "PRESS", "CONSOLIDATE" };
	/** ThreatFactionStance.EVAL_DAYS, PRESSURE_DAYS, TREND_DAYS and ThreatStance.LEAVE. */
	static final int EVAL_DAYS = 7;
	static final float PRESSURE_DAYS = 60f, TREND_DAYS = 60f, LEAVE = 0.8f;

	/** ThreatReach.facedFaction: the owner of the world a hive system would strike first - the nearest. */
	static String faced(State s, StarSys sys) {
		if (s.facedDay != s.day) {
			s.faced.clear();
			s.facedDay = s.day;
		}
		if (s.faced.containsKey(sys)) return s.faced.get(sys);
		World best = null;
		for (World w : s.worlds) {
			if (w.lost) continue;
			if (best == null || w.sys.ly(sys) < best.sys.ly(sys)) best = w;
		}
		String id = best == null ? null : best.faction;
		s.faced.put(sys, id);
		return id;
	}

	/** ThreatFactionStance.noteTrend: a fight's fleet points lost and sunk. */
	static void note(State s, Faction f, float lost, float killed) {
		decay(s, f);
		f.trendLost += Math.max(0f, lost);
		f.trendKilled += Math.max(0f, killed);
	}

	private static void decay(State s, Faction f) {
		float k = (float) Math.exp(-Math.max(0, s.day - f.trendDay) / TREND_DAYS);
		f.trendLost *= k;
		f.trendKilled *= k;
		f.trendDay = s.day;
	}

	/** ThreatFactionStance.forceFP: the fleets out, and what the supplies above the floors pay for at a siege's price. */
	static float forceFP(State s, Faction f) {
		float fp = 0f;
		for (Parcel p : s.parcels) if (!p.done && p.owner.equals(f.id)) fp += p.fp;
		float perFP = s.knobs.f("threatinc_expeditionSuppliesPerPoint") / ReachRules.FP_PER_POINT
				+ HumanPlanner.suppliesPerFP(s) * s.knobs.f("threatinc_siegeOrbitDays") / 30f;
		float supplies = 0f;
		for (World w : s.worldsOf(f.id)) if (w.hasReserve) supplies += HumanPools.available(s, w, World.SUPPLIES);
		return fp + supplies / perFP;
	}

	static void evaluate(State s, Faction f) {
		f.lastStanceDay = s.day;
		if (!s.knobs.b("threatinc_stanceEnabled", true)) { f.stance = EXPAND; return; }
		boolean pressed = f.lastStruckDay > Integer.MIN_VALUE / 4 && f.strikesSuffered > 0
				&& s.day - f.lastStruckDay < PRESSURE_DAYS;
		for (World w : s.worldsOf(f.id)) if (s.swarm.landings.containsKey(w.id)) pressed = true;
		decay(s, f);
		float ours = forceFP(s, f);
		boolean losing = f.trendLost > 0f && f.trendLost > f.trendKilled && f.trendLost >= 0.05f * Math.max(1f, ours);
		float theirs = 0f;
		for (StarSys sys : s.foundHiveSystems) {
			if (!s.hasHive(sys) || !f.id.equals(faced(s, sys))) continue;
			HumanIntel.Report r = f.reports.get(sys);
			if (r != null) theirs += r.total();
		}
		float ratio = theirs > 0f ? ours / theirs : (ours > 0f ? Float.MAX_VALUE : 0f);
		boolean target = false;
		for (HumanPlanner.Option o : HumanPlanner.options(s, f)) if (o.affordable) { target = true; break; }
		float enter = s.knobs.f("threatinc_stancePressRatio");
		float need = f.stance == PRESS ? enter * LEAVE : enter;
		int next = pressed && (losing || ratio < 1f) ? CONSOLIDATE
				: !pressed && !losing && target && ratio >= need ? PRESS : EXPAND;
		if (next != f.stance && next != CONSOLIDATE && s.day - f.stanceSince < s.knobs.i("threatinc_stanceDwellDays")) {
			next = f.stance;
		}
		if (next != f.stance) {
			s.log("Faction stance: " + f.id + " " + NAMES[f.stance] + "->" + NAMES[next] + " - "
					+ (pressed ? "pressed" : "not pressed") + "; exchange lost " + (int) f.trendLost + " sank "
					+ (int) f.trendKilled + "; force " + (int) ours + " vs " + (int) theirs + " FP reported facing it");
			f.stance = next;
			f.stanceSince = s.day;
		}
		s.count("stanceDays." + NAMES[f.stance], EVAL_DAYS);
	}

	/** ThreatFactionStance.foundsLinks. */
	static boolean foundsLinks(State s, Faction f) {
		if (f.stance == CONSOLIDATE) return false;
		if (f.stance == PRESS) return s.rng.nextFloat() < s.knobs.f("threatinc_stanceSecondaryShare");
		return true;
	}

	/** ThreatFactionStance.siegeAllowed: always, but consolidating only a system that faces the faction. */
	static boolean siegeAllowed(State s, Faction f, StarSys sys) {
		return f.stance != CONSOLIDATE || f.id.equals(faced(s, sys));
	}
}
