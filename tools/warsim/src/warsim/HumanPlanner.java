package warsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import threatinc.rules.BattleRules;
import threatinc.rules.PlannerRules;
import threatinc.rules.ReachRules;

/**
 * The attack planner (ThreatAttackPlanner over IncursionManager's siege sizing): every
 * planIntervalDays, or on news, a faction adds sieges, easiest first, until the chance that
 * one in flight lands as planned reaches planConfidence; a raid where no siege can be paid;
 * and every softenIntervalDays a hunting force against a garrison no siege can face.
 */
final class HumanPlanner {
	private HumanPlanner() {}

	/** A siege as sized against one hive from one base. */
	static final class Option {
		Hive hive;
		World base;
		float fp, ly, trust, reported, days;
		float[] wants;
		boolean affordable;
		String shortOf;
	}

	static float roundUp(float fp) { return (float) Math.ceil(fp / ReachRules.FP_PER_POINT) * ReachRules.FP_PER_POINT; }

	static boolean booked(State s, Faction f, Hive h, Parcel.Kind... kinds) {
		for (Parcel p : s.parcels) {
			// f == null: booked by any faction (IncursionManager.siegeTargets skips a hive an expedition is already out for)
			if (p.done || p.threat() || (f != null && !p.owner.equals(f.id)) || !(p.order instanceof HumanOrder)) continue;
			HumanOrder o = (HumanOrder) p.order;
			if (o.returning || o.target != h) continue;
			Parcel.Kind k = p.kind == Parcel.Kind.MUSTER ? o.sailAs : p.kind;
			for (Parcel.Kind want : kinds) if (k == want) return true;
		}
		return false;
	}

	/** IncursionManager's sizing: the orbit (siegeOrbitNeeded), the marines (needAndWear), the provisions (expeditionWants). */
	static Option size(State s, Faction f, Hive h, World base) {
		Option o = new Option();
		o.hive = h;
		o.base = base;
		o.ly = base.sys.ly(h.sys);
		HumanIntel.Report r = f.reports.get(h.sys);
		o.reported = r == null ? 0f : r.at(h);
		float travel = ReachRules.siegeArrivalDays(o.ly, State.LY_PER_DAY);
		o.trust = r == null ? 0f : PlannerRules.trust(s.day - r.day, travel, s.knobs.f("threatinc_intelHalfLifeDays"));
		float orbit = o.reported * s.knobs.f("threatinc_npcSiegeOrbitMargin");
		float perPoint = s.knobs.f("threatinc_siegeRaidStrPerPoint");
		float budget = s.knobs.f("threatinc_siegeOrbitDays");
		boolean front = h.front != null && f.id.equals(h.front.faction);
		float fp = Math.max(HumanFit.MIN_SIEGE_FP, roundUp(orbit));
		float marines = 0f;
		float[] plan = null;
		// needAndWear: the least fleet that carries the marines its own bombardment leaves needed
		for (;; fp += ReachRules.FP_PER_POINT) {
			plan = HumanSiege.bombardPlan(s, h, fp, budget, 0f);
			// raidStrNeededAt: sized on the larger of the defence as it stands and the Nexus anchor
			// (nexusAnchoredDefense), worn by the share this plan's bombardment takes off what stands
			float now = HumanSiege.defence(s, h);
			float def = Math.max(now, HumanSiege.anchored(s, h)) * (now > 0f ? Math.min(1f, plan[1] / now) : 1f);
			float beach = HumanSiege.troopsToLand(s, def);
			marines = BattleRules.raidStrNeeded(def, 0.25f, 1.25f, beach);
			if (front) marines = Math.max(s.knobs.f("threatinc_frontMinMarines"), beach - h.front.marines);
			if (marines <= fp / ReachRules.FP_PER_POINT * perPoint || fp >= HumanFit.MAX_SIEGE_FP) break;
		}
		o.fp = fp;
		o.days = front ? 0f : plan[0];
		float points = fp / ReachRules.FP_PER_POINT;
		float fuelLY = s.knobs.f("threatinc_expeditionFuelPerPointLY");
		float ordnance = o.days * BattleRules.bombardFuelPerDay(fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		float trip = ReachRules.siegeTripDays(o.ly, o.days, State.LY_PER_DAY);
		float supplies = points * ReachRules.siegeSuppliesPerPoint(s.knobs.f("threatinc_expeditionSuppliesPerPoint"),
				ReachRules.DEFAULT_SUPPLIES_PER_FP, trip);
		// landingSupply: armaments for npcFrontSupplyDays of a pushing front
		float arms = marines * s.knobs.f("threatinc_frontArmamentsPerMarinePer30Days")
				* s.knobs.f("threatinc_frontPushUpkeepMult") * s.knobs.f("threatinc_npcFrontSupplyDays") / 30f / 2f;
		o.wants = new float[] { marines, arms, ReachRules.passageFuel(points, o.ly, fuelLY) + ordnance, supplies };
		o.affordable = true;
		for (int c = 0; c < 4; c++) {
			if (HumanPools.payable(s, base, c, true) < o.wants[c]) {
				o.affordable = false;
				o.shortOf = World.COMMODITIES[c];
				break;
			}
		}
		return o;
	}

	/**
	 * IncursionManager.onPurgeCooldown: a hive's last siege is too recent for the next. purgeCooldownDays, twice
	 * that for a world over purgePreemptMaxSize reported defended, purgeFollowUpDays for a wounded one (an organ down).
	 */
	static boolean onCooldown(State s, Faction f, Hive h) {
		Integer last = s.lastSiegeDay.get(h);
		if (last == null) return false;
		HumanIntel.Report r = f.reports.get(h.sys);
		boolean defended = r != null && r.at(h) > 0f;
		float cooldown = s.knobs.f("threatinc_purgeCooldownDays");
		if (defended && h.size > s.knobs.i("threatinc_purgePreemptMaxSize")) cooldown *= s.knobs.f("threatinc_purgeDefendedCooldownMult");
		if (h.front != null || h.nexusDown > 0f || h.forgeDown > 0f || h.coreDown > 0f) cooldown = Math.min(cooldown, s.knobs.f("threatinc_purgeFollowUpDays"));
		return s.day - last < cooldown;
	}

	/** Every found hive this faction could besiege, sized from its nearest base in range, easiest first. */
	static List<Option> options(State s, Faction f) {
		List<Option> out = new ArrayList<Option>();
		List<World> bases = new ArrayList<World>();
		List<Float> ranges = new ArrayList<Float>();
		for (World w : s.worldsOf(f.id)) {
			if (!w.base || !w.hasReserve) continue;
			bases.add(w);
			ranges.add(HumanPools.rangeLY(s, w));
		}
		for (Hive h : s.liveHives()) {
			if (!s.foundHiveSystems.contains(h.sys) || f.reports.get(h.sys) == null) continue;
			if (h.front != null && !f.id.equals(h.front.faction)) continue;
			if (!HumanStance.siegeAllowed(s, f, h.sys)) continue;
			// a front of ours is fed by supply runs (frontRuns), not by another siege
			if (h.front != null) continue;
			if (onCooldown(s, f, h)) continue;
			World base = null;
			for (int i = 0; i < bases.size(); i++) {
				World w = bases.get(i);
				if (w.sys.ly(h.sys) > ranges.get(i)) continue;
				if (base == null || w.sys.ly(h.sys) < base.sys.ly(h.sys)) base = w;
			}
			if (base == null) continue;
			out.add(size(s, f, h, base));
		}
		Collections.sort(out, new Comparator<Option>() {
			public int compare(Option a, Option b) { return Float.compare(a.fp, b.fp); }
		});
		return out;
	}

	/** The planner's pass for one faction. */
	static void plan(State s, Faction f) {
		f.lastPlanDay = s.day;
		f.news = false;
		float miss = 1f;
		for (Parcel p : s.parcels) {
			if (p.done || !p.owner.equals(f.id) || !(p.order instanceof HumanOrder)) continue;
			HumanOrder o = (HumanOrder) p.order;
			boolean siege = p.kind == Parcel.Kind.SIEGE || (p.kind == Parcel.Kind.MUSTER && o.sailAs == Parcel.Kind.SIEGE);
			// in flight: mustering, under way or in orbit
			if (siege && !o.returning) miss = PlannerRules.miss(miss, o.trust);
		}
		float confidence = s.knobs.f("threatinc_planConfidence");
		Option unpaid = null;
		boolean sailed = false;
		for (Option o : options(s, f)) {
			if (1f - miss >= confidence) break;
			if (booked(s, null, o.hive, Parcel.Kind.SIEGE)) continue;
			if (!o.affordable) {
				s.count("siegesPostponed", 1);
				s.count("postponed." + o.shortOf, 1);
				if (unpaid == null) unpaid = o;
				continue;
			}
			// siegeCanPay prices the whole trip; only the hulls' deposit is drawn now, ThreatUpkeep bills the rest as it goes
			float[] draw = o.wants.clone();
			draw[World.SUPPLIES] = o.fp / ReachRules.FP_PER_POINT * s.knobs.f("threatinc_expeditionSuppliesPerPoint");
			if (!HumanPools.pay(s, o.base, draw, true)) continue;
			launch(s, f, o);
			sailed = true;
			miss = PlannerRules.miss(miss, o.trust);
		}
		if (!sailed && unpaid != null) raid(s, f, unpaid);
		frontRuns(s, f);
	}

	static Parcel muster(State s, Faction f, World base, Hive target, float fp, int prepDays, Parcel.Kind as) {
		Parcel p = s.send(f.id, Parcel.Kind.MUSTER, base.sys, base.sys, fp, 0);
		p.against = target.sys;
		p.targetId = target.id;
		HumanOrder o = new HumanOrder();
		o.home = base;
		o.target = target;
		o.sailDay = s.day + prepDays;
		o.sailAs = as;
		p.order = o;
		return p;
	}

	/**
	 * ThreatGroundFronts' front runs: a convoy of marines and armaments (convoyEscortFP) from the nearest base
	 * to a front of ours that a counter-attack could overrun, no sooner than frontRunWaitDays after the last.
	 */
	static void frontRuns(State s, Faction f) {
		for (Hive h : s.liveHives()) {
			if (h.front == null || !f.id.equals(h.front.faction) || !frontWants(s, h)) continue;
			World base = HumanPools.nearestBase(s, f.id, h.sys, false);
			if (base == null) continue;
			float marines = Math.max(s.knobs.f("threatinc_frontMinMarines"),
					HumanSiege.troopsToLand(s, HumanSiege.defence(s, h)) - h.front.marines);
			marines = Math.min(marines, s.knobs.f("threatinc_convoyMarineCapacity"));
			float arms = marines * s.knobs.f("threatinc_frontArmamentsPerMarinePer30Days") * s.knobs.f("threatinc_frontResupplyDays") / 30f;
			float escort = s.knobs.f("threatinc_convoyEscortFP");
			float[] cost = ReachRules.voyageCost(escort, 2f * base.sys.ly(h.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
					s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
			h.front.lastRunDay = s.day;
			if (!HumanPools.pay(s, base, new float[] { marines, arms, cost[0], cost[1] }, false)) {
				s.count("frontRunWaits", 1);
				continue;
			}
			Parcel p = s.send(f.id, Parcel.Kind.CONVOY, base.sys, h.sys, escort, 0);
			p.targetId = h.id;
			p.marines = marines;
			p.armaments = arms;
			HumanOrder o = new HumanOrder();
			o.home = base;
			o.target = h;
			o.deposit = cost[1];
			p.order = o;
			s.count("frontRuns", 1);
		}
	}

	/** A front is sent more only when a counter-attack could overrun it, and no sooner than frontRunWaitDays after the last run. */
	static boolean frontWants(State s, Hive h) {
		Front fr = h.front;
		if (s.day - fr.lastRunDay < s.knobs.i("threatinc_frontRunWaitDays")) return false;
		return fr.marines * s.knobs.f("threatinc_frontLandingMult")
				* BattleRules.overrunOdds(s.knobs.f("threatinc_groundStrengthExponent")) < HumanSiege.defence(s, h);
	}

	static void launch(State s, Faction f, Option opt) {
		int prep = (int) (HumanFit.PREP_MIN_DAYS + HumanFit.PREP_SPAN_DAYS * s.rng.nextFloat());
		Parcel p = muster(s, f, opt.base, opt.hive, opt.fp, prep, Parcel.Kind.SIEGE);
		s.lastSiegeDay.put(opt.hive, s.day);
		HumanOrder o = (HumanOrder) p.order;
		o.trust = opt.trust;
		o.deposit = opt.fp / ReachRules.FP_PER_POINT * s.knobs.f("threatinc_expeditionSuppliesPerPoint");
		p.marines = opt.wants[World.MARINES];
		p.armaments = opt.wants[World.ARMAMENTS];
		// aboard: the ordnance (the passage is burned on the way)
		p.fuel = opt.days * BattleRules.bombardFuelPerDay(opt.fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		s.log("Plan " + f.id + ": sieges " + opt.hive.name + " from " + opt.base.name + ", trust "
				+ String.format("%.2f", opt.trust) + ", " + (int) opt.reported + " FP reported, " + (int) opt.fp
				+ " FP sent, " + (int) p.marines + " marines");
	}

	/**
	 * ThreatAttackPlanner.raidOption against the hive no siege could be paid for: a squadron that
	 * holds the orbit and bombs for RAID_STAY_DAYS. One a faction per softenIntervalDays.
	 */
	static void raid(State s, Faction f, Option opt) {
		if (s.day - f.lastRaidDay < s.knobs.i("threatinc_softenIntervalDays")) return;
		if (booked(s, f, opt.hive, Parcel.Kind.SQUADRON, Parcel.Kind.SATURATION)) return;
		float least = HumanSiege.leastForGain(s, opt.hive);
		if (least >= Float.MAX_VALUE) return;
		float orbit = PlannerRules.orbitToContest(opt.reported, s.knobs.f("threatinc_orbitContestFraction"));
		float fp = roundUp(PlannerRules.raidFP(least, orbit, s.knobs.f("threatinc_guardFleetFP")));
		float[] cost = ReachRules.voyageCost(fp, 2f * opt.ly, s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		float ordnance = HumanFit.RAID_STAY_DAYS * BattleRules.bombardFuelPerDay(fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		if (!HumanPools.pay(s, opt.base, new float[] { 0f, 0f, cost[0] + ordnance, cost[1] }, false)) return;
		f.lastRaidDay = s.day;
		Parcel p = s.send(f.id, Parcel.Kind.SQUADRON, opt.base.sys, opt.hive.sys, fp, 0);
		p.targetId = opt.hive.id;
		p.fuel = ordnance;
		HumanOrder o = new HumanOrder();
		o.home = opt.base;
		o.target = opt.hive;
		o.trust = opt.trust;
		o.deposit = cost[1];
		p.order = o;
		s.count("squadronsSailed", 1);
		s.log("Plan " + f.id + ": raids " + opt.hive.name + " " + (int) fp + " FP from " + opt.base.name);
	}

	/**
	 * ThreatSoftening.tick: one hunting force a faction a tick, against the nearest found hive
	 * system whose reported swarms no affordable siege of ours faces, sized at the orbit margin
	 * over what is reported; it musters softenMusterDays, then sails.
	 */
	static void hunts(State s, Faction f) {
		f.lastHuntDay = s.day;
		if (!s.knobs.b("threatinc_softenEnabled", true)) return;
		float margin = s.knobs.f("threatinc_npcSiegeOrbitMargin");
		int rest = s.knobs.i("threatinc_softenIntervalDays");
		// hasSiegeableHive: a base with a siege it could sail today spends on that, not on hunting forces
		java.util.Set<World> sieging = new java.util.HashSet<World>();
		for (Option o : options(s, f)) {
			if (o.affordable && !booked(s, null, o.hive, Parcel.Kind.SIEGE)) sieging.add(o.base);
		}
		for (StarSys sys : s.foundHiveSystems) {
			HumanIntel.Report r = f.reports.get(sys);
			if (r == null || r.total() < 1f) continue;
			// the strongest garrison reported is worked first (ThreatSoftening.gateWorlds)
			Hive target = null;
			for (Hive h : s.hivesIn(sys)) if (target == null || r.at(h) > r.at(target)) target = h;
			if (target == null) continue;
			// ThreatSoftening.hunting: one hunting force a faction a system
			boolean hunted = false;
			for (Parcel p : s.parcels) {
				if (p.done || !p.owner.equals(f.id) || !(p.order instanceof HumanOrder) || ((HumanOrder) p.order).returning) continue;
				if (p.kind == Parcel.Kind.HUNT && p.to == sys || p.kind == Parcel.Kind.MUSTER && p.against == sys
						&& ((HumanOrder) p.order).sailAs == Parcel.Kind.HUNT) hunted = true;
			}
			if (hunted) continue;
			// huntBases: a base in fuel reach, not resting, with no siege of its own to spend on; the nearest
			World base = null;
			for (World w : s.worldsOf(f.id)) {
				if (!w.base || !w.hasReserve || sieging.contains(w) || s.day - w.lastHuntDay < rest) continue;
				if (w.sys.ly(sys) > HumanPools.rangeLY(s, w)) continue;
				if (base == null || w.sys.ly(sys) < base.sys.ly(sys)) base = w;
			}
			if (base == null) continue;
			hunt(s, f, base, target, roundUp(r.total() * margin));
		}
	}

	private static void hunt(State s, Faction f, World base, Hive target, float fp) {
		float[] cost = ReachRules.voyageCost(fp, 2f * base.sys.ly(target.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		// the voyage is paid at the muster; the ships' supplies are billed as they go (ThreatUpkeep)
		if (!HumanPools.pay(s, base, new float[] { 0f, 0f, cost[0], cost[1] }, false)) {
			s.count("huntWaits", 1);
			return;
		}
		base.lastHuntDay = s.day;
		Parcel p = muster(s, f, base, target, fp, s.knobs.i("threatinc_softenMusterDays"), Parcel.Kind.HUNT);
		((HumanOrder) p.order).deposit = cost[1];
		s.log("Hunting force from " + base.name + " to " + target.sys + ": " + (int) fp + " FP, mustering");
	}
}
