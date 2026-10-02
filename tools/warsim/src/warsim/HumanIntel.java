package warsim;

import java.util.IdentityHashMap;
import java.util.Map;

import threatinc.rules.PlannerRules;
import threatinc.rules.ReachRules;

/**
 * What the factions know of the swarm (ThreatIntel, ThreatHiveIntel, ThreatScouting), simplified
 * to one report per hive system: the swarms over each hive and loose in the system, dated.
 * Every mobilised faction is taken as a coalition partner of the others and shares reports.
 */
final class HumanIntel {
	private HumanIntel() {}

	static final class Report {
		int day;
		/** Garrison FP over each hive, and the Threat's other fleets on station in the system. */
		final Map<Hive, Float> over = new IdentityHashMap<Hive, Float>();
		float loose;
		boolean radar;

		float total() {
			float t = loose;
			for (Float f : over.values()) t += f;
			return t;
		}

		/** IncursionManager.siegeOrbitNeeded's figure with npcSiegeOrbitPerWorld: the swarms over that hive. */
		float at(Hive h) {
			Float f = over.get(h);
			return (f == null ? 0f : f) + loose;
		}
	}

	static boolean excluded(State s, String faction) {
		if (faction == null || faction.isEmpty() || Parcel.THREAT.equals(faction)) return true;
		if (faction.equals("player") || faction.equals("neutral") || faction.equals("threat")) return true;
		for (String x : s.knobs.s("threatinc_warExcludedFactions", "").split(",")) {
			if (x.trim().equals(faction)) return true;
		}
		return false;
	}

	/** The truth of a system as a report, at radar's precision or exactly. */
	static Report see(State s, StarSys sys, boolean radar) {
		Report r = new Report();
		r.day = s.day;
		r.radar = radar;
		for (Hive h : s.hivesIn(sys)) r.over.put(h, radar ? PlannerRules.twoFigures(h.garrisonFP) : h.garrisonFP);
		float loose = s.holdingFP(sys, true);
		r.loose = radar ? PlannerRules.twoFigures(loose) : loose;
		return r;
	}

	/** Files a report with every faction at war; news to the planner where the swarms moved (ThreatIntel.moved). */
	static void file(State s, StarSys sys, Report r) {
		s.foundHiveSystems.add(sys);
		for (Faction f : s.factions.values()) {
			if (!f.mobilised) continue;
			Report was = f.reports.get(sys);
			if (was == null || PlannerRules.moved(was.total(), r.total()) || was.over.size() != r.over.size()) f.news = true;
			f.reports.put(sys, r);
		}
	}

	/** The daily sweep: eyes (a fleet on station, a front) or radar (a base within radarRangeLY). */
	static void sweep(State s) {
		float radarLY = s.knobs.f("threatinc_radarRangeLY");
		for (StarSys sys : s.systems.values()) {
			if (s.hivesIn(sys).isEmpty()) continue;
			boolean eyes = s.holdingFP(sys, false) > 0f;
			if (!eyes) {
				for (Hive h : s.hivesIn(sys)) if (h.front != null && !Parcel.THREAT.equals(h.front.faction)) eyes = true;
			}
			boolean radar = false;
			if (!eyes) {
				for (World w : s.worlds) {
					if (w.lost || !w.hasReserve || !HumanPools.military(w)) continue;
					Faction f = s.factions.get(w.faction);
					if (f == null || !f.mobilised) continue;
					if (w.sys.ly(sys) <= radarLY) { radar = true; break; }
				}
			}
			if (eyes || radar) file(s, sys, see(s, sys, !eyes));
		}
	}

	/** Sends a recon party (scoutFleetPoints) from the faction's nearest base; false if none can pay. */
	static boolean scout(State s, Faction f, StarSys to) {
		for (Parcel p : s.parcels) {
			if (!p.done && p.kind == Parcel.Kind.SCOUT && p.to == to && !p.threat()) return true;
		}
		World base = HumanPools.nearestBase(s, f.id, to, false);
		if (base == null) return false;
		float fp = s.knobs.f("threatinc_scoutFleetPoints");
		float[] cost = ReachRules.voyageCost(fp, 2f * base.sys.ly(to), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		float[] wants = { 0f, 0f, cost[0], cost[1] };
		if (!HumanPools.pay(s, base, wants, false)) return false;
		Parcel p = s.send(f.id, Parcel.Kind.SCOUT, base.sys, to, fp, 0);
		HumanOrder o = new HumanOrder();
		o.home = base;
		o.deposit = cost[1];
		p.order = o;
		s.count("scoutsSailed", 1);
		return true;
	}

	/** A scout on the spot: the hives of that system and of those within scoutLeadRadiusLY are found. */
	static void scoutArrived(State s, Parcel p) {
		float lead = s.knobs.f("threatinc_scoutLeadRadiusLY");
		for (StarSys sys : s.systems.values()) {
			if (sys.ly(p.to) > lead || s.hivesIn(sys).isEmpty()) continue;
			boolean fresh = !s.foundHiveSystems.contains(sys);
			file(s, sys, see(s, sys, sys != p.to));
			if (fresh) {
				s.count("hiveSystemsFound", 1);
				s.log("Scout of " + p.owner + " found the hives of " + sys);
			}
		}
	}

	/**
	 * The scouting clocks: a lead on every new strike (the system it sailed from, or failing
	 * that the hive system nearest the faction), and every scoutIntervalDays a sweep of the
	 * unfound hive systems within scoutRangeLY of a military world.
	 */
	static void scouting(State s, Faction f) {
		if (f.strikesSuffered > f.strikesSeen) {
			f.strikesSeen = f.strikesSuffered;
			StarSys lead = f.lastStrikeFrom;
			if (lead == null || s.hivesIn(lead).isEmpty()) lead = nearestUnfound(s, f);
			if (lead != null) scout(s, f, lead);
		}
		if (s.day - f.lastScoutDay < s.knobs.i("threatinc_scoutIntervalDays")) return;
		f.lastScoutDay = s.day;
		float range = s.knobs.f("threatinc_scoutRangeLY");
		float half = s.knobs.f("threatinc_intelHalfLifeDays");
		for (StarSys sys : s.systems.values()) {
			if (s.hivesIn(sys).isEmpty()) continue;
			Report r = f.reports.get(sys);
			boolean found = s.foundHiveSystems.contains(sys);
			// a found system is looked at again once its report is two half-lives old
			if (found && r != null && s.day - r.day < 2f * half) continue;
			boolean near = false;
			for (World w : s.worldsOf(f.id)) {
				if (HumanPools.military(w) && w.sys.ly(sys) <= (found ? HumanPools.rangeLY(s, w) : range)) { near = true; break; }
			}
			if (near) scout(s, f, sys);
		}
	}

	static StarSys nearestUnfound(State s, Faction f) {
		StarSys best = null;
		float bestLY = Float.MAX_VALUE;
		for (Hive h : s.liveHives()) {
			if (s.foundHiveSystems.contains(h.sys)) continue;
			for (World w : s.worldsOf(f.id)) {
				float ly = w.sys.ly(h.sys);
				if (ly < bestLY) { bestLY = ly; best = h.sys; }
			}
		}
		return best;
	}
}
