package warsim;

import java.util.ArrayList;
import java.util.List;

import threatinc.rules.ReachRules;

/**
 * Forward bases (ThreatFrontlines): one link a faction a pass toward the nearest found hive
 * it does not yet reach, its garrison, its growth, and the two ways it is given up. Holding
 * them is the human score.
 */
final class HumanBases {
	private HumanBases() {}

	static List<World> anchors(State s, Faction f) {
		List<World> out = new ArrayList<World>();
		for (World w : s.worldsOf(f.id)) if (w.hasReserve) out.add(w);
		return out;
	}

	/** ThreatFrontlines.plan for one faction: found one link, or say why not. */
	static void plan(State s, Faction f) {
		if (!s.knobs.b("threatinc_frontlinesEnabled", true) || s.foundHiveSystems.isEmpty()) return;
		if (!HumanStance.foundsLinks(s, f)) return;
		// round 15 trial c (warsim_maxLinks): at most this many forward bases held at once; 0 = no cap
		int cap = (int) s.knobs.f("warsim_maxLinks", 0f);
		if (cap > 0) {
			int held = 0;
			for (World w : s.worldsOf(f.id)) if (w.forwardBase) held++;
			if (held >= cap) { s.count("linkCapped", 1); return; }
		}
		// round 15 trial e (warsim_linkWaitsForSiege): no new link while the faction's own staging siege is unpaid
		if (s.knobs.b("warsim_linkWaitsForSiege", false) && f.siegeUnpaid) { s.count("linkHeldForSiege", 1); return; }
		float reach = s.knobs.f("threatinc_frontlineReachLY");
		float hop = s.knobs.f("threatinc_frontlineLinkLY");
		List<World> anchors = anchors(s, f);
		if (anchors.isEmpty()) return;

		// the found hive system nearest an anchor that no base of ours reaches yet
		StarSys goal = null;
		float goalLY = Float.MAX_VALUE;
		for (StarSys sys : s.foundHiveSystems) {
			if (!s.hasHive(sys)) continue;
			float near = Float.MAX_VALUE;
			boolean reached = false;
			for (World a : anchors) {
				float ly = a.sys.ly(sys);
				near = Math.min(near, ly);
				if (ly <= reach && HumanPools.military(a)) reached = true;
			}
			if (!reached && near < goalLY) { goalLY = near; goal = sys; }
		}
		if (goal == null) return;

		// the site: a hop from an anchor, closer to the hive than that anchor, the closest of all
		StarSys site = null;
		float siteLY = Float.MAX_VALUE;
		for (StarSys sys : s.systems.values()) {
			if (sys.planets <= 0 || sys == goal || s.hasHive(sys)) continue;
			float left = sys.ly(goal);
			if (left >= siteLY) continue;
			boolean ok = false;
			for (World a : anchors) {
				if (a.sys == sys || a.sys.ly(sys) > hop) continue;
				if (left <= reach || left <= a.sys.ly(goal) - 1f) { ok = true; break; }
			}
			if (!ok) continue;
			boolean taken = false;
			for (World w : s.worlds) if (!w.lost && w.sys == sys) { taken = true; break; }
			if (taken) continue;
			site = sys;
			siteLY = left;
		}
		if (site == null) return;

		float[] wants = { 0f, 0f, s.knobs.f("threatinc_outpostFuel"),
				s.knobs.f("threatinc_outpostSupplies") + HumanFit.LINK_KIT_SUPPLIES };
		World payer = null;
		for (World b : HumanPools.donors(s, anchorNearest(anchors, site))) {
			if (b.base && !b.forwardBase && HumanPools.canPay(s, b, wants, false, "link")) { payer = b; break; }
		}
		if (payer == null || !HumanPools.pay(s, payer, wants, false, "link")) {
			s.count("linkCannotPay", 1);
			s.log("Frontline: " + f.id + " cannot pay for a link at " + site + " - no base holds the stock");
			return;
		}
		// no paper bases: a front link is founded only with a garrison the faction can send and keep (garrisonBase)
		String why = cannotGuard(s, f, site, guardWanted(s, f, site));
		if (why != null) {
			HumanPools.deposit(payer, wants);
			s.count("linkCannotGuard", 1);
			s.log("Frontline: " + f.id + " cannot garrison a link at " + site + " - " + why);
			return;
		}
		World w = s.foundForwardBase(f.id, site, goal);
		HumanPools.ensure(s, w);
		if (f.firstBaseDay < 0) f.firstBaseDay = s.day - s.startDay;
		garrison(s, f, w);
	}

	private static World anchorNearest(List<World> anchors, StarSys site) {
		World best = anchors.get(0);
		for (World a : anchors) if (a.sys.ly(site) < best.sys.ly(site)) best = a;
		return best;
	}

	/**
	 * ThreatFrontlines.strikeAt, in fleet points: the strongest strike a found hive of strikeMinSize could send
	 * at a link standing at the faction's front toward it (the faction's nearest world to that hive, within
	 * FRONT_TOLERANCE_LY), weighed from the faction's report of its garrison. -1 for a rear link.
	 */
	static float strikeAt(State s, Faction f, World w) { return strikeAt(s, f, w.sys); }

	static float strikeAt(State s, Faction f, StarSys at) {
		float worst = -1f;
		int min = s.knobs.i("threatinc_strikeMinSize");
		List<World> ours = s.worldsOf(f.id);
		// warsim_frontFacedOnly: billed reach (ThreatFrontlines.strikeAt / frontOf), only a hive that would strike this
		// faction first. On since round 32 (2026-10-04): off, 68% of the swarm's readings of a forward base saw a garrison
		// (median 1,607 defence units) against the game's ~20-30% (median 190-330), so the simulated swarm aimed at
		// colonies 2:1 where hw5b's aimed at bases 2:1. Its cost: bases held run 2-5x the game's (the spared garrison
		// budget founds links; the founding gap lies in the pools' late stock, war-sim-calibration-r29.md)
		boolean faced = s.knobs.b("warsim_frontFacedOnly", true) && s.knobs.b("threatinc_billedReach", true);
		for (Hive h : s.hives) {
			if (h.dead || h.size < min || !s.foundHiveSystems.contains(h.sys)) continue;
			if (faced && !f.id.equals(HumanStance.faced(s, h.sys))) continue;
			float d = at.ly(h.sys), best = d;
			for (World m : ours) best = Math.min(best, m.sys.ly(h.sys));
			if (d > best + 0.5f) continue;
			HumanIntel.Report r = f.reports.get(h.sys);
			worst = Math.max(worst, (r == null ? 0f : r.at(h)) * HumanFit.STRIKE_SEND_SHARE);
		}
		return worst;
	}

	/** ThreatFrontlines.guardNeed in fleet points: a front link's strike in reach x frontlineGarrisonMargin, at least frontlineGarrisonFP; 0 in the rear. */
	static float guardWanted(State s, Faction f, World w) { return guardWanted(s, f, w.sys); }

	static float guardWanted(State s, Faction f, StarSys at) {
		float strike = strikeAt(s, f, at);
		if (strike < 0f) return 0f;
		return Math.max(s.knobs.f("threatinc_frontlineGarrisonFP"), strike * s.knobs.f("threatinc_frontlineGarrisonMargin"));
	}

	/** A guard's supplies a month per FP (HumanFit.GUARD_UPKEEP_PER_FP), x warsim_guardUpkeepMult (round 14 trial b). */
	static float guardUpkeepPerFP(State s) { return HumanFit.GUARD_UPKEEP_PER_FP * s.knobs.f("warsim_guardUpkeepMult", 1f); }

	/**
	 * ThreatFrontlines.garrisonBase: why a standing garrison of needFP cannot be sent to a site, or null. The
	 * garrisons' upkeep must stay within upkeepBudget (the supplies income plus the stock above the floors over
	 * frontlineUpkeepStockMonths), and the nearest colony base must pay the voyage.
	 */
	static String cannotGuard(State s, Faction f, StarSys site, float needFP) {
		if (needFP <= 0f || !s.knobs.b("threatinc_frontlineGarrisonEnabled", true)) return null;
		float upkeep = needFP * guardUpkeepPerFP(s), income = 0f, spare = 0f;
		for (World w : s.worldsOf(f.id)) {
			if (w.forwardBase && w.sys != site) upkeep += Math.max(w.guardFP, guardWanted(s, f, w)) * guardUpkeepPerFP(s);
			if (!w.hasReserve) continue;
			income += HumanPools.refRate(s, w, World.SUPPLIES);
			spare += HumanPools.spareFor(s, w, World.SUPPLIES, "guardUpkeep");
		}
		float months = s.knobs.f("threatinc_frontlineUpkeepStockMonths");
		float budget = income + (months > 0f ? spare / months : 0f);
		if (upkeep > budget) return "upkeep would be " + (int) upkeep + " of " + (int) budget + " supplies a month";
		World from = HumanPools.nearestBase(s, f.id, site, true);
		if (from == null) return "no base";
		float[] cost = ReachRules.voyageCost(needFP, from.sys.ly(site), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		if (!HumanPools.canPay(s, from, new float[] { 0f, 0f, cost[0], cost[1] }, false, "guardVoyage")) {
			return "cannot pay the voyage of " + (int) needFP + " FP from " + from.name;
		}
		return null;
	}

	/**
	 * Round 16 trial r (warsim_reliefToBesiegers): the Threat strike bearing on or besieging a forward base that the
	 * faction has a report of - arrived (the base's own eyes), or in flight from a hive system in Faction.reports - in the
	 * strike's defence units (SwarmOps.strength); 0 for none.
	 */
	static float besiegers(State s, Faction f, World w) {
		if (!s.knobs.b("warsim_reliefToBesiegers", false)) return 0f;
		float worst = 0f;
		for (Parcel p : s.parcels) {
			if (p.done || !p.threat() || p.kind != Parcel.Kind.STRIKE || p.to != w.sys) continue;
			if (!p.arrived && !f.reports.containsKey(p.from)) continue;
			worst = Math.max(worst, SwarmOps.strength(p));
		}
		return worst;
	}

	/** The guard a reported strike at the base calls for: its strength x frontlineGarrisonMargin over the base's own defence, in guard FP. */
	static float guardAgainst(State s, World w, float strength) {
		if (strength <= 0f) return 0f;
		return Math.max(0f, (strength * s.knobs.f("threatinc_frontlineGarrisonMargin") - w.defence) / SwarmFit.STRIKE_UNITS_PER_FP);
	}

	/** Asks the nearest colony base for the garrison the link lacks: a RELIEF parcel, paid like any voyage. */
	static void garrison(State s, Faction f, World w) { garrison(s, f, w, guardWanted(s, f, w), false); }

	/** The same for a given guard; `relief` (trial r) is a relief against a reported strike, which the upkeep budget does not hold back. */
	static void garrison(State s, Faction f, World w, float wanted, boolean relief) {
		if (!s.knobs.b("threatinc_frontlineGarrisonEnabled", true)) return;
		float want = wanted - w.guardFP;
		for (Parcel p : s.parcels) {
			if (!p.done && p.kind == Parcel.Kind.RELIEF && p.owner.equals(f.id) && p.to == w.sys
					&& !(p.order instanceof HumanOrder && ((HumanOrder) p.order).returning)) want -= p.fp;
		}
		if (want < 1f) return;
		w.guardAskedDay = s.day;
		String why = cannotGuard(s, f, w.sys, want + w.guardFP);
		if (why != null && why.startsWith("upkeep") && !relief) {
			s.count("guardOverBudget", 1);
			return;
		}
		if (relief) s.count(w.forwardBase ? "reliefToBesiegers.asked" : "reliefInvaded.asked", 1);
		// ThreatFleetOrders.sendRelief builds relief from whole fleets of reliefFleetFP until the owed is covered (Sindria,
		// hw5b: "sends 359 FP ... (owed 100)"); until round 32 (2026-10-04) the simulator sent the bare shortfall, top-ups of
		// 27-45 FP, and its relief ran a median 150-165 FP against the game's 357-360 (hw4p, hw4z, hw5b).
		// warsim_reliefWholeFleets=false restores that
		if (relief && !w.forwardBase && s.knobs.b("warsim_reliefWholeFleets", true)) {
			float fleet = s.knobs.f("threatinc_reliefFleetFP", 300f) * HumanFit.FLEET_POINTS_PER_COMBAT_FP;
			if (fleet >= 1f) want = (float) Math.ceil(want / fleet) * fleet;
		}
		World from = HumanPools.nearestBase(s, f.id, w.sys, true);
		if (from == null) return;
		float[] cost = ReachRules.voyageCost(want, from.sys.ly(w.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		if (!HumanPools.pay(s, from, new float[] { 0f, 0f, cost[0], cost[1] }, false, "guardVoyage")) {
			s.count("guardCannotPay", 1);
			s.log("Frontline: " + f.id + " cannot garrison " + w.name + " (" + (int) want + " FP)");
			return;
		}
		Parcel p = s.send(f.id, Parcel.Kind.RELIEF, from.sys, w.sys, want, 0);
		HumanOrder o = new HumanOrder();
		o.home = from;
		o.guards = w;
		o.deposit = cost[1];
		o.relief = relief;
		p.order = o;
		s.count("guardsSailed", 1);
		if (relief && !w.forwardBase) {
			s.count("reliefInvaded.sentFP", want);
			s.log("Relief: " + f.id + " sends " + (int) want + " FP from " + from.name + " to invaded " + w.name);
		}
	}

	/**
	 * Round 20 trial (warsim_reliefGoesHome, on top of warsim_reliefToBesiegers): with no reported strike left bearing
	 * on the base, the relief that answered one sails for the base it came from and settles there (HumanSide.settle:
	 * its deposit by health), so it is not a garrison with upkeep. The standing guard stays. The way home is paid in
	 * fuel like the way out; unpaid, it waits a day.
	 */
	static void reliefHome(State s, Faction f, World w) {
		float leave = Math.min(w.reliefFP, w.guardFP);
		World home = w.reliefHome != null && !w.reliefHome.lost ? w.reliefHome : HumanPools.nearestBase(s, f.id, w.sys, true);
		if (home != null && leave >= 1f) {
			float[] cost = ReachRules.voyageCost(leave, w.sys.ly(home.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"), 0f);
			if (!HumanPools.pay(s, home, new float[] { 0f, 0f, cost[0], 0f }, false, "guardVoyage")) {
				s.count("reliefToBesiegers.homeUnpaid", 1);
				return;
			}
			Parcel p = s.send(f.id, Parcel.Kind.RELIEF, w.sys, home.sys, leave, 0);
			p.fp0 = Math.max(leave, w.reliefFP0);
			HumanOrder o = new HumanOrder();
			o.home = home;
			o.returning = true;
			o.relief = true;
			o.deposit = w.reliefDeposit;
			p.order = o;
			s.count("reliefToBesiegers.wentHome", 1);
			s.count("reliefToBesiegers.wentHomeFP", leave);
		}
		w.guardFP -= leave;
		w.reliefFP = w.reliefFP0 = w.reliefDeposit = 0f;
		w.reliefHome = null;
	}

	/**
	 * warsim_reliefToInvaded: ThreatFleetOrders.planRelief. A Threat army on one of a mobilised faction's colonies gets a
	 * task force over it sized to break the swarm's hold (reliefGoal) less the guards already there, from the nearest
	 * base that pays the voyage; it fights the swarm's guard on arrival (SwarmOps.reliefFight), pays its upkeep while out
	 * (a month unpaid sends it home, ThreatUpkeep.starve) and goes home with the front. The game sent 293-566 such fleets a
	 * run, 126k-210k FP (hw4d, hw4h, hw4g); the simulator sent relief to forward bases only.
	 */
	static void reliefInvaded(State s, Faction f) {
		if (!s.knobs.b("warsim_reliefToInvaded", true)) return;
		for (World w : s.worldsOf(f.id)) {
			if (w.forwardBase || w.lost) continue;
			if (!s.swarm.landings.containsKey(w.id) || !f.mobilised) {
				if (w.reliefFP >= 1f) reliefHome(s, f, w);
				continue;
			}
			// threatinc_reliefStays false (the mod before 2026-10-03): relief left guardDays after it came, and the next was asked
			if (w.reliefFP >= 1f && !s.knobs.b("threatinc_reliefStays", true)
					&& s.day - w.reliefSinceDay >= s.knobs.i("threatinc_guardDays")) {
				s.count("reliefInvaded.termEnded", 1);
				reliefHome(s, f, w);
				continue;
			}
			if (w.reliefFP >= 1f && s.day % 30 == 0) {
				float due = w.reliefFP * guardUpkeepPerFP(s);
				if (!HumanPools.pay(s, w, new float[] { 0f, 0f, 0f, due }, false, "guardUpkeep")) {
					s.count("reliefInvaded.unpaid", 1);
					reliefHome(s, f, w);
					continue;
				}
			}
			if (w.reliefFP >= 1f) {
				// relief-days over a landing; on the overrun clock (no front engine) nothing it does reaches the army
				s.count("reliefInvaded.days", 1);
				if (!s.swarm.landings.get(w.id).engine) s.count("reliefInvaded.clockDays", 1);
				// warsim_reliefFightDays: a relief on station keeps fighting the guard (ThreatGroundFronts.tickRelief -> fightOrbit:
				// the game's aggressive orbit engages every Threat fleet over the world); 0, only on arrival (HumanSide.arrive)
				int every = (int) s.knobs.f("warsim_reliefFightDays", 0f);
				if (every > 0 && s.knobs.b("warsim_reliefFights", true) && s.day - w.reliefFightDay >= every
						&& !SwarmOps.guards(s, w).isEmpty()) {
					w.reliefFightDay = s.day;
					s.count("reliefInvaded.fights", 1);
					SwarmOps.reliefFight(s, w);
				}
			}
			if (w.guardFP >= reliefGoal(s, w) || s.day - w.guardAskedDay < HumanFit.GUARD_RETRY_DAYS) continue;
			garrison(s, f, w, reliefGoal(s, w), true);
		}
	}

	/**
	 * ThreatFleetOrders.reliefGoal: what lifting the swarm's hold over the world takes - the Threat guard's FP over it x
	 * npcSiegeOrbitMargin, or one guardFleetFP to deny the next landing with none overhead.
	 */
	static float reliefGoal(State s, World w) {
		float threat = 0f;
		for (Parcel p : SwarmOps.guards(s, w)) threat += p.fp;
		return threat <= 0f ? s.knobs.f("threatinc_guardFleetFP", 100f) : threat * Math.max(1f, s.knobs.f("threatinc_npcSiegeOrbitMargin", 1.5f));
	}

	/** One day of every forward base of a faction: garrison, upkeep, growth, giving up. */
	static void daily(State s, Faction f) {
		int abandon = s.knobs.i("threatinc_frontlineAbandonDays");
		float keepLY = s.knobs.f("threatinc_frontlineKeepLY");
		for (World w : s.worldsOf(f.id)) {
			if (!w.forwardBase) continue;
			HumanPools.ensure(s, w);
			int age = s.day - w.foundedDay;
			float wanted = guardWanted(s, f, w);
			float strike = besiegers(s, f, w);
			float against = guardAgainst(s, w, strike);
			if (w.reliefFP > w.guardFP) w.reliefFP = w.guardFP;
			// warsim_reliefRearGoesHome is the mod's rule (ThreatFrontlines.garrison: a rear link's called guard goes home
			// once no seen strike is bound for it); warsim_reliefGoesHome sends the front's called guard home too
			if (w.reliefFP > 0f && strike <= 0f && (s.knobs.b("warsim_reliefGoesHome", false)
					|| (wanted <= 0f && s.knobs.b("warsim_reliefRearGoesHome", false)))) reliefHome(s, f, w);
			if (against > wanted && w.guardFP < against) {
				// trial r: relief sized to the reported strike, asked at once (the retry gate is for the standing guard)
				s.count("reliefToBesiegers.days", 1);
				garrison(s, f, w, against, true);
			} else if (w.guardFP < wanted * 0.8f && s.day - w.guardAskedDay >= HumanFit.GUARD_RETRY_DAYS) garrison(s, f, w);

			if (age > 0 && age % 30 == 0) {
				// the garrison's month of supplies, from the base and then the pool; under half paid, it goes home
				float due = w.guardFP * guardUpkeepPerFP(s);
				if (due > 0f && !HumanPools.pay(s, w, new float[] { 0f, 0f, 0f, due }, false, "guardUpkeep")) {
					float have = HumanPools.payable(s, w, World.SUPPLIES, false, "guardUpkeep");
					if (have < due * s.knobs.f("threatinc_upkeepBreakEven")) {
						s.log("Frontline: garrison of " + w.name + " recalled, upkeep unpaid");
						s.count("guardsRecalled", 1);
						w.guardFP = 0f;
					} else {
						HumanPools.pay(s, w, new float[] { 0f, 0f, 0f, have }, false, "guardUpkeep");
					}
				}
				// the colony's own upkeep from size 3, and growth on healthy days (ThreatColonyUpkeep)
				float upkeep = w.size >= 3 ? s.knobs.f("threatinc_sizeUpkeepAt3")
						* (float) Math.pow(s.knobs.f("threatinc_sizeUpkeepRatio"), w.size - 3) : 0f;
				boolean fed = upkeep <= 0f || HumanPools.pay(s, w, new float[] { 0f, 0f, 0f, upkeep }, false, "baseUpkeep");
				if (fed) {
					w.healthyDays += 30f;
					w.starveDays = 0f;
				} else {
					w.starveDays += 30f;
				}
				if (w.healthyDays >= s.knobs.f("threatinc_frontlineGrowDays") && w.size < HumanFit.BASE_MAX_SIZE) {
					w.size++;
					w.healthyDays = 0f;
					s.count("baseLevels", 1);
				} else if (w.starveDays >= s.knobs.f("threatinc_frontlineStarveDays") && w.size > 1) {
					w.size--;
					w.starveDays = 0f;
				}
			}

			w.unguardedDays = wanted <= 0f || w.guardFP >= HumanFit.MIN_GUARD_FP ? 0 : w.unguardedDays + 1;
			boolean hive = false;
			for (StarSys sys : s.foundHiveSystems) {
				if (sys.ly(w.sys) <= keepLY && s.hasHive(sys)) { hive = true; break; }
			}
			w.idleDays = hive ? 0 : w.idleDays + 1;
			String why = w.unguardedDays >= abandon ? "no garrison to hold it"
					: w.idleDays >= abandon ? "no found hive in reach" : null;
			if (why != null) {
				// dismantled: the stock is carried home
				HumanPools.deposit(HumanPools.nearestBase(s, f.id, w.sys, true), w.stock);
				s.loseWorld(w, false, why);
			}
		}
	}
}
