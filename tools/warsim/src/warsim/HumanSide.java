package warsim;

import java.util.ArrayList;
import java.util.Map;

import threatinc.rules.BattleRules;
import threatinc.rules.PlannerRules;
import threatinc.rules.ReachRules;

/**
 * The human factions: pools (HumanPools), mobilisation, intel (HumanIntel), forward bases
 * (HumanBases), the attack planner (HumanPlanner) and the sieges, raids, hunts and ground
 * fronts it sends (HumanSiege). With threatinc_warCouncil on, the war council and its plays
 * (HumanCouncil) stand in for the planner and the stance. docs/war-sim-humans.md.
 */
public final class HumanSide implements Side {
	/** -Dwarsim.humanTestSwarm=true: HumanTestSwarm stands in for the swarm side (calibration runs). */
	private boolean testSwarm;

	/**
	 * The hook for Start.fill, after the factions are read: the human side's optional dump
	 * fields. Worlds: "military" (IncursionManager.hasMilitary), "guardFP". Factions:
	 * "mobilisedDay", "lastStruckDay". Top level: "foundHiveSystems" [system id], and
	 * "reports" [{faction, system, day, loose, hives: [{id, fp}]}]. All may be absent.
	 */
	public static void load(State s, Map<String, Object> dump) {
		for (Object o : Json.arr(dump.get("worlds"))) {
			Map<String, Object> j = Json.obj(o);
			World w = s.world(Json.str(j.get("id"), ""));
			if (w == null) continue;
			if (j.get("military") != null) w.military = Json.bool(j.get("military"), false);
			w.guardFP = Json.num(j.get("guardFP"), 0f);
		}
		for (Object o : Json.arr(dump.get("factions"))) {
			Map<String, Object> j = Json.obj(o);
			Faction f = s.faction(Json.str(j.get("id"), ""));
			if (j.get("mobilisedDay") != null) f.mobilisedDay = (int) Json.num(j.get("mobilisedDay"), s.day);
			if (j.get("lastStruckDay") != null) f.lastStruckDay = (int) Json.num(j.get("lastStruckDay"), -1f);
		}
		for (Object o : Json.arr(dump.get("foundHiveSystems"))) {
			StarSys sys = s.systems.get(String.valueOf(o));
			if (sys != null) s.foundHiveSystems.add(sys);
		}
		for (Object o : Json.arr(dump.get("reports"))) {
			Map<String, Object> j = Json.obj(o);
			StarSys sys = s.systems.get(Json.str(j.get("system"), ""));
			if (sys == null) continue;
			HumanIntel.Report r = new HumanIntel.Report();
			r.day = (int) Json.num(j.get("day"), s.day);
			r.loose = Json.num(j.get("loose"), 0f);
			for (Object ho : Json.arr(j.get("hives"))) {
				Map<String, Object> hj = Json.obj(ho);
				Hive h = s.hive(Json.str(hj.get("id"), ""));
				if (h != null) r.over.put(h, Json.num(hj.get("fp"), 0f));
			}
			s.faction(Json.str(j.get("faction"), "")).reports.put(sys, r);
			s.foundHiveSystems.add(sys);
		}
	}

	@Override public void init(State s) {
		testSwarm = Boolean.getBoolean("warsim.humanTestSwarm");
		boolean atWar = false;
		for (Faction f : s.factions.values()) {
			f.strikesSeen = f.strikesSuffered;
			if (!f.mobilised) continue;
			atWar = true;
			for (World w : s.worldsOf(f.id)) {
				// a faction at war in the start state: every world has its depot, as the mod's has
				HumanPools.ensure(s, w);
				w.hasReserve = true;
				if (HumanPools.military(w)) w.base = true;
			}
		}
		// an established war with no intel in the dump: the hives standing are known as of today
		if (atWar && s.foundHiveSystems.isEmpty() && HumanFit.START_KNOWS_HIVES) {
			for (Hive h : s.liveHives()) HumanIntel.file(s, h.sys, HumanIntel.see(s, h.sys, true));
		}
		// a front on a hive is a hive found, and seen
		for (Hive h : s.liveHives()) {
			if (h.front != null && !Parcel.THREAT.equals(h.front.faction)) s.foundHiveSystems.add(h.sys);
			HumanSiege.setClock(s, h, h.siegeClock > 0f ? h.siegeClock
					: (1f - Math.max(0f, Math.min(1f, h.fortification))) * HumanSiege.wearDays(s));
		}
		for (Parcel p : s.parcels) if (!p.threat() && !p.done) order(s, p);
		for (Faction f : s.factions.values()) f.news = f.mobilised;
	}

	/** The parcel's order; one loaded from a save (order == null) gets the default of its kind. */
	static HumanOrder order(State s, Parcel p) {
		if (p.order instanceof HumanOrder) return (HumanOrder) p.order;
		HumanOrder o = new HumanOrder();
		o.home = HumanPools.nearestBase(s, p.owner, p.from != null ? p.from : p.to, false);
		StarSys where = p.kind == Parcel.Kind.MUSTER && p.against != null ? p.against : p.to;
		o.target = p.targetId != null ? s.hive(p.targetId) : null;
		if (o.target == null || o.target.dead) {
			for (Hive h : s.hivesIn(where)) if (o.target == null || o.target.dead || h.garrisonFP > o.target.garrisonFP) o.target = h;
		}
		if (p.fp0 <= 0f) p.fp0 = p.fp;
		o.trust = 1f;
		o.deposit = p.fp0 / ReachRules.FP_PER_POINT * s.knobs.f("threatinc_expeditionSuppliesPerPoint");
		switch (p.kind) {
		case MUSTER:
			// a staged force sails when the shortest preparation is up; with marines aboard it is a siege
			o.sailAs = p.marines >= s.knobs.f("threatinc_frontMinMarines") ? Parcel.Kind.SIEGE : Parcel.Kind.HUNT;
			o.sailDay = s.day + (int) HumanFit.PREP_MIN_DAYS;
			p.holding = true;
			p.arrived = true;
			if (o.target == null) o.returning = true;
			break;
		case RELIEF:
			for (World w : s.worldsOf(p.owner)) if (w.forwardBase && w.sys == p.to) o.guards = w;
			break;
		case SIEGE:
		case SATURATION:
		case SQUADRON:
			// ordnance for what is left of a planned stay, if the dump gave none
			if (p.fuel <= 0f) {
				p.fuel = BattleRules.bombardFuelPerDay(p.fp, s.knobs.f("threatinc_bombardFuelPerFPDay"))
						* (p.kind == Parcel.Kind.SIEGE ? s.knobs.f("threatinc_siegeOrbitDays") : HumanFit.RAID_STAY_DAYS);
			}
			break;
		default:
			break;
		}
		if (p.holding && o.arrivedDay < 0) o.arrivedDay = s.day;
		p.order = o;
		return o;
	}

	@Override public void daily(State s) {
		if (testSwarm) HumanTestSwarm.daily(s);
		// the disruption clocks run down a day (vanilla's), and a nexus down is a day scored
		for (Hive h : s.hives) {
			if (h.dead) continue;
			if (h.siegeClock > 0f) HumanSiege.setClock(s, h, h.siegeClock - 1f);
			if (h.nexus && h.nexusDown > 0f) s.count("nexusDownDays", 1);
		}
		// IncursionManager.mobiliseAtPhase (threatinc_mobiliseAtPhase, 3; 0 = off): every faction the war does not
		// exclude mobilises once the swarm reaches that phase, struck or not (round 27's trial, built 2026-10-02 -
		// hw4c: the Diktat, first struck in phase 3, lost Sindria to the strike that mobilised it)
		int alarmPhase = (int) s.knobs.f("threatinc_mobiliseAtPhase");
		boolean alarm = alarmPhase > 0 && SwarmPosture.phase(s) >= alarmPhase;
		for (Faction f : new ArrayList<Faction>(s.factions.values())) {
			if (!f.mobilised && (f.strikesSuffered > 0 || alarm) && !HumanIntel.excluded(s, f.id)
					&& !s.worldsOf(f.id).isEmpty()) {
				HumanPools.mobilise(s, f);
				f.strikesSeen = 0;
				f.news = true;
				mark(s, f, "mobilised", s.day - s.startDay);
			}
		}
		HumanPools.daily(s);
		HumanIntel.sweep(s);
		int planDays = s.knobs.i("threatinc_planIntervalDays");
		// ThreatAttackPlanner.active: the planner is off while the council is on; ThreatFactionStance.refresh skips a governed faction
		boolean council = HumanCouncil.on(s);
		for (Faction f : s.factions.values()) {
			if (!f.mobilised || HumanIntel.excluded(s, f.id)) continue;
			HumanIntel.scouting(s, f);
			if (council) HumanCouncil.daily(s, f);
			else if (s.day - f.lastStanceDay >= HumanStance.EVAL_DAYS) HumanStance.evaluate(s, f);
			if (s.day - f.lastFrontlineDay >= s.knobs.i("threatinc_frontlinePlanDays")) {
				f.lastFrontlineDay = s.day;
				int had = f.firstBaseDay;
				HumanBases.plan(s, f);
				if (had < 0 && f.firstBaseDay >= 0) mark(s, f, "firstBase", f.firstBaseDay);
			}
			HumanBases.daily(s, f);
			HumanBases.reliefInvaded(s, f);
			if (!council) {
				if (PlannerRules.planDue(f.news, s.day, f.lastPlanDay, planDays)) HumanPlanner.plan(s, f);
			} else if (s.day - f.lastPlanDay >= planDays) {
				// the front runs are ThreatGroundFronts' own, council or planner
				f.lastPlanDay = s.day;
				HumanPlanner.frontRuns(s, f);
			}
			if (s.day - f.lastHuntDay >= s.knobs.i("threatinc_softenIntervalDays")) HumanPlanner.hunts(s, f);
		}
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (p.done || p.threat() || !p.holding) continue;
			station(s, p, order(s, p));
		}
		fronts(s);
		if ((s.day - s.startDay) % 5 == 0) upkeep(s);
		if (s.verbose && (s.day - s.startDay) % 180 == 0) census(s);
	}

	/** The mod's "Census: hegemony colonies ..." line, for reading a verbose run against its log. */
	private static void census(State s) {
		for (Faction f : s.factions.values()) {
			if (!f.mobilised) continue;
			float[] sum = new float[4];
			int colonies = 0, links = 0, bases = 0;
			float guard = 0f;
			for (World w : s.worldsOf(f.id)) {
				if (w.forwardBase) { links++; guard += w.guardFP; } else colonies++;
				if (w.base) bases++;
				for (int c = 0; c < 4; c++) sum[c] += w.stock[c];
			}
			s.log("Census: " + f.id + " colonies " + colonies + ", links " + links + " (" + (int) guard + " FP), bases " + bases
					+ ", reserve marines " + (int) sum[0] + ", arms " + (int) sum[1] + ", fuel " + (int) sum[2] + ", supplies "
					+ (int) sum[3] + ", found " + s.foundHiveSystems.size() + " systems");
		}
	}

	/** A timing mark, as a counter (days since the run began) and a log line. */
	private static void mark(State s, Faction f, String what, int day) {
		s.count("day." + f.id + "." + what, day);
		s.log("Timing: " + f.id + " " + what + " on day " + day);
	}

	/** One day of a parcel on station: a muster waiting to sail, a siege, a raid or a hunt in orbit. */
	private void station(State s, Parcel p, HumanOrder o) {
		Faction f = s.faction(p.owner);
		switch (p.kind) {
		case MUSTER:
			if (s.day < o.sailDay) return;
			if (o.target == null || o.target.dead || p.fp < 1f) { settle(s, p, o); return; }
			sail(s, p, o, f);
			return;
		case SIEGE: {
			String end = HumanSiege.orbitDay(s, p, o);
			if (end == null) return;
			boolean landed = (end.startsWith("landed") || end.equals("front") || end.equals("guns"))
					&& HumanSiege.land(s, p, o, end);
			if (landed) {
				s.count("siegesLanded", 1);
				HumanCouncil.landed(o);
				if (f.firstLandingDay < 0) mark(s, f, "firstLanding", f.firstLandingDay = s.day - s.startDay);
			}
			String key = end.equals("guns") ? (landed ? "guns" : "guns, no front") : end;
			s.count("siege." + key, 1);
			s.log("Daily siege of " + (o.target != null ? o.target.name : p.to.name) + ": " + (s.day - o.arrivedDay) + " d, "
					+ (int) p.fp0 + " -> " + (int) p.fp + " FP, " + o.fights + " fight days, " + key);
			f.news = true;
			home(s, p, o);
			return;
		}
		case SQUADRON:
		case SATURATION: {
			Hive h = o.target;
			if (h == null || h.dead) { home(s, p, o); return; }
			float enemy = HumanSiege.enemyAt(s, h);
			if (enemy > 0f) {
				if (BattleRules.callsOff(enemy, p.fp, HumanSiege.friendsOf(s, p), s.knobs.f("threatinc_siegeBreakOffRatio"))) {
					s.count("raidsCalledOff", 1);
					if (p.kind == Parcel.Kind.SATURATION) s.count("saturationsCalledOff", 1);
					o.drivenOff = true;
					home(s, p, o);
					return;
				}
				HumanSiege.fight(s, p, h, enemy);
				o.fights++;
			}
			float perDay = BattleRules.bombardFuelPerDay(p.fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
			// the game's saturation (round 23): this world's whole price poured over the stay, then the next world of the raze set
			boolean priced = p.kind == Parcel.Kind.SATURATION && o.satFuelSize2 > 0f;
			if (priced) perDay = HumanCouncil.saturationFuel(h, o.satFuelSize2) / Math.max(1, o.stayDays);
			if (priced && o.orbitDays >= o.stayDays && o.razeNext != null) {
				Hive next = null;
				while (!o.razeNext.isEmpty() && next == null) {
					Hive x = o.razeNext.remove(0);
					if (!x.dead) next = x;
				}
				if (next != null && p.fp >= BattleRules.raidLossLine(p.fp0, s.knobs.f("threatinc_raidLossFraction"))) {
					o.target = next;
					p.targetId = next.id;
					o.orbitDays = 0;
					return;
				}
			}
			boolean over = p.fp < BattleRules.raidLossLine(p.fp0, s.knobs.f("threatinc_raidLossFraction"))
					|| o.orbitDays >= (o.stayDays > 0 ? o.stayDays : HumanFit.RAID_STAY_DAYS) || BattleRules.bombardDaysFor(p.fuel, perDay) < 0.5f;
			boolean contested = BattleRules.orbitContested(HumanSiege.enemyAt(s, h), p.fp + HumanSiege.friendsOf(s, p),
					s.knobs.f("threatinc_orbitContestFraction"));
			if (!over && contested && o.play != null && p.kind == Parcel.Kind.SQUADRON) {
				// ThreatFleetOrders.endRaid "orbit contested": a play's squadron is driven off, it does not wait the swarms out
				o.drivenOff = true;
				s.count("raidsDrivenOff", 1);
				home(s, p, o);
				return;
			}
			if (!over && !contested) {
				// a raid's bombing falls on the organs: the forge and the nexus go down with the clock
				float[] step = HumanSiege.bombardDay(s, h, p.fp, h.siegeClock, 0);
				p.fuel = Math.max(0f, p.fuel - perDay);
				p.fp -= step[1];
				HumanSiege.suppress(s, h, step[0]);
				if (h.forge) h.forgeDown = Math.max(h.forgeDown, h.siegeClock);
				s.count("raidBombDays", 1);
			}
			o.orbitDays++;
			if (over) { f.news = true; home(s, p, o); }
			return;
		}
		case HUNT: {
			int stayed = s.day - o.arrivedDay;
			float enemy = s.garrisonFP(p.to) + s.holdingFP(p.to, true);
			// softenDays at the strongest garrison, then on to each further one it still outweighs
			int works = 0;
			for (Hive x : s.hivesIn(p.to)) if (x.garrisonFP >= 1f && x.garrisonFP < p.fp) works++;
			if (enemy < 1f || p.fp < p.fp0 * s.knobs.f("threatinc_softenRetreatStrength")
					|| stayed >= s.knobs.i("threatinc_softenDays") * Math.max(1, works)) {
				home(s, p, o);
				return;
			}
			if (stayed % HumanFit.HUNT_BATTLE_DAYS != 0) return;
			huntBattle(s, p, f);
			return;
		}
		default:
			return;
		}
	}

	/** A hunt battle: the force against one Defense Swarm (the strongest garrison's average fleet) or the loose fleets. */
	private static void huntBattle(State s, Parcel p, Faction f) {
		Hive h = null;
		for (Hive x : s.hivesIn(p.to)) if (h == null || x.garrisonFP > h.garrisonFP) h = x;
		float loose = s.holdingFP(p.to, true);
		if (h != null && h.garrisonFP >= 1f) {
			float fleet = h.garrisonFP / Math.max(1, h.garrisonFleets);
			float lost = fleet * BattleRules.defenderLoss(p.fp, fleet);
			float mine = p.fp * BattleRules.lossShare(p.fp, fleet);
			h.garrisonFP = Math.max(0f, h.garrisonFP - lost);
			p.fp -= mine;
			s.count("threatFPKilled", lost);
			s.count("humanFPLost", mine);
			HumanStance.note(s, f, mine, lost);
		} else if (loose >= 1f) {
			float taken = BattleRules.defenderLoss(p.fp, loose);
			float mine = p.fp * BattleRules.lossShare(p.fp, loose);
			for (Parcel q : s.parcels) {
				if (q.done || !q.holding || q.to != p.to || !q.threat()) continue;
				s.count("threatFPKilled", q.fp * taken);
				q.fp *= 1f - taken;
				if (q.fp < 1f) q.done = true;
			}
			p.fp -= mine;
			s.count("humanFPLost", mine);
		}
		s.count("huntBattles", 1);
		f.news = true;
	}

	/** A muster's day to sail: the same parcel leaves its base as what it was staged to be. */
	private static void sail(State s, Parcel p, HumanOrder o, Faction f) {
		p.kind = o.sailAs;
		p.holding = false;
		p.from = p.to;
		p.to = o.target.sys;
		p.departDay = s.day;
		p.arriveDay = s.day + State.travelDays(p.from.ly(p.to));
		p.arrived = false;
		p.against = null;
		if (p.kind == Parcel.Kind.SIEGE) {
			s.count("siegesSailed", 1);
			if (f.firstSiegeDay < 0) mark(s, f, "firstSiege", f.firstSiegeDay = s.day - s.startDay);
		} else if (p.kind == Parcel.Kind.HUNT) {
			s.count("huntsSailed", 1);
		}
	}

	/** Turns a parcel for the base it settles at. */
	static void home(State s, Parcel p, HumanOrder o) {
		if (o.home == null || o.home.lost) o.home = HumanPools.nearestBase(s, p.owner, p.to, false);
		if (o.home == null || p.fp < 1f) { p.done = true; return; }
		o.returning = true;
		p.holding = false;
		p.from = p.to;
		p.to = o.home.sys;
		p.departDay = s.day;
		p.arriveDay = s.day + State.travelDays(p.from.ly(p.to));
		p.arrived = false;
	}

	/** ThreatReturns.settle: the deposit by health, the cargo aboard in full, unburned ordnance at returnRefundMult. */
	static void settle(State s, Parcel p, HumanOrder o) {
		p.done = true;
		World at = o.home != null && !o.home.lost ? o.home : HumanPools.nearestBase(s, p.owner, p.to, false);
		if (at == null) return;
		float health = p.fp0 > 0f ? Math.max(0f, Math.min(1f, p.fp / p.fp0)) : 0f;
		float[] back = { p.marines, p.armaments, p.fuel * s.knobs.f("threatinc_returnRefundMult"),
				o.deposit * health + Math.max(0f, p.supplies) };
		HumanPools.deposit(at, back);
		s.count("suppliesRefunded", back[World.SUPPLIES]);
	}

	@Override public void arrive(State s, Parcel p) {
		HumanOrder o = order(s, p);
		if (o.returning) { settle(s, p, o); return; }
		switch (p.kind) {
		case MUSTER:
			p.holding = true;
			return;
		case SIEGE:
		case SATURATION:
		case SQUADRON:
		case HUNT:
			if (o.target == null || o.target.dead) {
				// the hive is gone; another in the system takes its place, or the force turns back
				o.target = null;
				for (Hive h : s.hivesIn(p.to)) if (o.target == null || h.garrisonFP > o.target.garrisonFP) o.target = h;
				if (o.target == null) { home(s, p, o); return; }
			}
			p.holding = true;
			o.arrivedDay = s.day;
			HumanIntel.file(s, p.to, HumanIntel.see(s, p.to, false), p.owner);
			if (p.kind == Parcel.Kind.SIEGE) s.count("siegesArrived", 1);
			if (p.kind == Parcel.Kind.SQUADRON) s.count("squadronsArrived", 1);
			return;
		case SCOUT:
			HumanIntel.scoutArrived(s, p);
			settle(s, p, o);
			return;
		case RELIEF:
			if (o.guards != null && !o.guards.lost) {
				o.guards.guardFP += p.fp;
				if (o.relief) {
					o.guards.reliefFP += p.fp;
					o.guards.reliefFP0 += p.fp;
					o.guards.reliefDeposit += o.deposit;
					o.guards.reliefHome = o.home;
				}
				// warsim_reliefToInvaded: over a Threat front it meets the swarm's guard (warsim_reliefFights: the game logs no such fight off screen;
				// on, the simulator's relief drove off 35 guards a run and hw4d stopped collapsing - 39.5 worlds lost against 51)
				if (!o.guards.forwardBase && s.knobs.b("warsim_reliefToInvaded", false) && s.knobs.b("warsim_reliefFights", false)) SwarmOps.reliefFight(s, o.guards);
			} else if (o.relief && s.knobs.b("warsim_reliefGoesHome", false)) {
				// round 20: the base fell before its relief came; the relief turns for home
				s.count("reliefToBesiegers.tooLate", 1);
				home(s, p, o);
				return;
			}
			p.done = true;
			return;
		case CONVOY: {
			Hive fed = o.target;
			if (fed != null) {
				// a front run: what it carries goes down to the front if it still stands, else home again
				if (!fed.dead && fed.front != null && p.owner.equals(fed.front.faction)) {
					fed.front.marines += p.marines;
					fed.front.armaments += p.armaments;
					p.marines = 0f;
					p.armaments = 0f;
				}
				home(s, p, o);
				return;
			}
			World at = HumanPools.nearestBase(s, p.owner, p.to, false);
			if (at != null && at.sys == p.to) HumanPools.deposit(at, new float[] { p.marines, p.armaments, p.fuel, p.supplies });
			p.done = true;
			return;
		}
		default:
			p.done = true;
		}
	}

	/** Every human front on a hive, a day; a victory eradicates the hive and sends the survivors home. */
	private static void fronts(State s) {
		for (Hive h : s.liveHives()) {
			Front fr = h.front;
			if (fr == null || Parcel.THREAT.equals(fr.faction)) continue;
			String end = HumanSiege.frontDay(s, h);
			if (end == null) continue;
			Faction f = s.faction(fr.faction);
			f.news = true;
			h.front = null;
			if (end.equals("victory")) {
				s.log("Ground victory at " + h.name);
				s.killHive(h, fr.faction);
				SwarmOps.hiveLost(s, fr.faction, h.sys);
				World at = HumanPools.nearestBase(s, fr.faction, h.sys, false);
				HumanPools.deposit(at, new float[] { fr.marines, fr.armaments, 0f, 0f });
				if (f.firstKillDay < 0) mark(s, f, "firstKill", f.firstKillDay = s.day - s.startDay);
			} else {
				s.count(end.equals("overrun") ? "frontsOverrun" : "frontsCollapsed", 1);
				if (end.equals("collapsed")) s.log("Front collapsed at " + h.name);
			}
		}
	}

	/** ThreatUpkeep.poll, every 5 days: a fleet away eats its supplies; a month owed and it stands down. */
	private static void upkeep(State s) {
		if (!s.knobs.b("threatinc_fleetUpkeep", true)) return;
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (p.done || p.threat() || !(p.order instanceof HumanOrder)) continue;
			HumanOrder o = (HumanOrder) p.order;
			if (p.kind == Parcel.Kind.RELIEF || p.kind == Parcel.Kind.CONVOY || p.kind == Parcel.Kind.SCOUT) continue;
			float due = ReachRules.tripSupplies(p.fp, HumanPlanner.suppliesPerFP(s), 5f);
			s.count("upkeepWanted", due);
			boolean siege = p.kind == Parcel.Kind.SIEGE || (p.kind == Parcel.Kind.MUSTER && o.sailAs == Parcel.Kind.SIEGE);
			if (siege) s.count("siegeTrip.burned", due);
			// what it carries first (a siege is provisioned for its trip), then the faction's depots
			float aboard = Math.min(due, Math.max(0f, p.supplies));
			p.supplies -= aboard;
			due -= aboard;
			if (due > 0f && o.home != null && !o.home.lost) due -= HumanPools.drain(s, o.home, World.SUPPLIES, due);
			o.owed += Math.max(0f, due);
			s.count("upkeepOwed", Math.max(0f, due));
			if (siege) s.count("siegeTrip.owed", Math.max(0f, due));
			if (!o.returning && o.owed >= ReachRules.tripSupplies(p.fp, HumanPlanner.suppliesPerFP(s), 30f)) {
				s.count("stoodDownUnpaid", 1);
				if (p.kind == Parcel.Kind.MUSTER) settle(s, p, o);
				else home(s, p, o);
			}
		}
	}
}
