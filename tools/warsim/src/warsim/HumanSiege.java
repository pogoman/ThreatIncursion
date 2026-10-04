package warsim;

import threatinc.rules.BattleRules;
import threatinc.rules.PlannerRules;

/**
 * A siege over a hive, a day at a time (ThreatPurgeFGI.dailyDay), its bombardment
 * (ThreatGroundFronts.bombardPlan / abstractSiegeStep) and the ground front it lands
 * (ThreatGroundFronts.GroundFront). Hive.siegeClock is the fortifications' disruption clock
 * and Hive.fortification the condition it gives; the human side runs both.
 */
final class HumanSiege {
	private HumanSiege() {}

	// ---- the defence figure ----

	static float wearDays(State s) { return s.knobs.f("threatinc_defenseWearDays"); }

	static float cond(State s, Hive h) { return BattleRules.condition(h.siegeClock, wearDays(s)); }

	static boolean hasGuns(Hive h) { return h.size >= HumanFit.GROUND_DEFENCES_MIN_SIZE; }

	/** The batteries' bonus: ground defences, heavy batteries from HEAVY_BATTERIES_MIN_SIZE. */
	static float gunBonus(State s, Hive h) {
		if (!hasGuns(h)) return 0f;
		return s.knobs.f(h.size >= HumanFit.HEAVY_BATTERIES_MIN_SIZE ? "threatinc_heavyBatteriesBonus" : "threatinc_groundDefensesBonus");
	}

	/** ThreatGroundFronts.defenderStrength of a hive with its fortifications at `cond` and `held` strata taken. */
	static float defence(State s, Hive h, float cond, int held) {
		float d = s.knobs.f("threatinc_hiveDefensePerSize") * Math.max(0, h.size - held);
		if (h.nexus) d *= 1f + s.knobs.f("threatinc_nexusDefenseBonus") * cond;
		d *= 1f + gunBonus(s, h) * cond;
		d *= HumanFit.TIER_MULT[Math.max(0, Math.min(2, h.tier))];
		d *= HumanFit.UNREST_DEFENCE_FLOOR + (1f - HumanFit.UNREST_DEFENCE_FLOOR) * cond;
		return d * s.knobs.f("threatinc_groundDefenseMult");
	}

	static float defence(State s, Hive h) {
		int held = h.front != null ? h.front.strataHeld : 0;
		return defence(s, h, cond(s, h), held);
	}

	/**
	 * IncursionManager.nexusAnchoredDefense: the size anchor over the strata left, times the Nexus bonus and
	 * the military tier's. A siege is sized on this even while unrest or an earlier siege has the
	 * defence down: pd9a landed 482-934 marines on size-2 hives, 1,282 and 1,585 on size 5.
	 */
	static float anchored(State s, Hive h) {
		int left = Math.max(0, h.size - (h.front != null ? h.front.strataHeld : 0));
		return s.knobs.f("threatinc_hiveDefensePerSize") * left * (1f + s.knobs.f("threatinc_nexusDefenseBonus"))
				* HumanFit.TIER_MULT[Math.max(0, Math.min(2, h.tier))] * s.knobs.f("threatinc_groundDefenseMult");
	}

	/** The batteries' share of the fortification bonus (gunShare): the ground defences against the nexus. */
	static float gunShare(State s, Hive h) {
		if (!hasGuns(h)) return 0f;
		float g = gunBonus(s, h);
		float n = h.nexus ? s.knobs.f("threatinc_nexusDefenseBonus") : 0f;
		return g / Math.max(0.01f, g + n);
	}

	/** IncursionManager.beachheadNeeded: the marines that survive the first counter-attack of a defence `d`. */
	static float troopsToLand(State s, float d) {
		return BattleRules.beachheadNeeded(d, s.knobs.f("threatinc_siegeBeachheadMargin"),
				s.knobs.f("threatinc_groundStrengthExponent"), s.knobs.f("threatinc_frontLandingMult"));
	}

	/** One day of bombardment by `fp` on a clock of `clock` days: {days added, return fire FP, defence taken off}. */
	static float[] bombardDay(State s, Hive h, float fp, float clock, int held) {
		float wear = wearDays(s);
		float c = BattleRules.condition(clock, wear);
		float d = defence(s, h, c, held);
		float rate = BattleRules.suppressionRate(fp, d, s.knobs.f("threatinc_siegeFPWeight"),
				s.knobs.f("threatinc_hiveSiegeSuppressDaysPerDay"));
		float gain = rate * c;
		float next = BattleRules.conditionAfterDay(c, rate, 1f, wear);
		float fire = BattleRules.returnFirePerDay(s.knobs.f("threatinc_bombardReturnFirePerGunDefence"),
				BattleRules.gunDefence(d, gunShare(s, h)));
		return new float[] { gain, fire, d - defence(s, h, next, held) };
	}

	/**
	 * ThreatGroundFronts.bombardPlan: what `fp` would make of the hive in up to `budget` days,
	 * stopping when a day adds less than a day, or costs more ships than it is worth
	 * (bombardFPWorth) unless the troops are still short, or would take the fleet under its
	 * abort line. {days, defence then, fleet left}.
	 */
	static float[] bombardPlan(State s, Hive h, float fp, float budget, float troops) {
		float wear = wearDays(s);
		float clock = h.siegeClock;
		int held = h.front != null ? h.front.strataHeld : 0;
		float worth = s.knobs.f("threatinc_bombardFPWorth");
		float floor = fp * BattleRules.GROUP_ABORT_FRACTION;
		float fleet = fp;
		int day = 0;
		while (day < budget) {
			float[] step = bombardDay(s, h, fleet, clock, held);
			if (step[0] < 1f) break;
			float d = defence(s, h, BattleRules.condition(clock, wear), held);
			boolean shortHanded = troops > 0f && troops < troopsToLand(s, d);
			if ((step[2] < step[1] * worth && !shortHanded) || fleet - step[1] < floor) break;
			clock = Math.min(wear, clock + step[0]);
			fleet -= step[1];
			day++;
		}
		return new float[] { day, defence(s, h, BattleRules.condition(clock, wear), held), fleet };
	}

	// ---- the daily siege ----

	static void setClock(State s, Hive h, float clock) {
		h.siegeClock = Math.max(0f, Math.min(wearDays(s), clock));
		h.fortification = cond(s, h);
	}

	/** Bombardment and a front's wear disrupt the nexus with the rest of the fortifications. */
	static void suppress(State s, Hive h, float days) {
		setClock(s, h, h.siegeClock + days);
		if (h.nexus) h.nexusDown = Math.max(h.nexusDown, h.siegeClock);
	}

	/** The Threat's fleet points at a hive: its garrison and the fleets on station in the system. */
	static float enemyAt(State s, Hive h) {
		// the off-screen fight weighs every Threat fleet in the system (vanilla's autoresolve): "Off-screen fight
		// over Gamma Vucub-Came I-L5 (daily siege): persean 3250 FP vs 2851 FP ... (14 fleets)", the hive's own 4
		float fp = s.holdingFP(h.sys, true);
		for (Hive o : s.hivesIn(h.sys)) fp += o.garrisonFP;
		return fp;
	}

	/** Human fleet points on station in the system besides this parcel. */
	static float friendsOf(State s, Parcel p) { return s.holdingFP(p.to, false) - (p.holding ? p.fp : 0f); }

	/**
	 * warsim_marinesDieWithHulls (round 31): the game's marines and armaments are cargo on the ships, and go down with
	 * the hulls the orbit costs (hw4s, Epsilon Laphirial II: 1150 -> 498 FP, 758 marines drawn, 329 landed). On by default: the
	 * simulator had kept them whole, so its long sieges landed in full.
	 */
	static void hullsLost(State s, Parcel p, float fraction) {
		if (!s.knobs.b("warsim_marinesDieWithHulls", true) || fraction <= 0f) return;
		float kept = Math.max(0f, 1f - Math.min(1f, fraction));
		if (p.marines > 0f) s.count("marinesLostAboard", p.marines * (1f - kept));
		p.marines *= kept;
		p.armaments *= kept;
	}

	/** One day's exchange: the besieger and its friends lose dayShare, the defenders defenderLoss. */
	static void fight(State s, Parcel p, Hive h, float enemy) {
		float friends = friendsOf(s, p);
		float mine = p.fp + friends;
		float share = BattleRules.dayShare(mine, enemy);
		float taken = BattleRules.defenderLoss(mine, enemy);
		for (Parcel q : s.parcels) {
			if (q.done || !q.holding || q.to != p.to) continue;
			if (q.threat()) {
				s.count("threatFPKilled", q.fp * taken);
				q.fp *= 1f - taken;
				if (q.fp < 1f) q.done = true;
			} else {
				s.count("humanFPLost", q.fp * share);
				HumanStance.note(s, s.faction(q.owner), q.fp * share, q == p ? enemy * taken : 0f);
				q.fp *= 1f - share;
				hullsLost(s, q, share);
			}
		}
		for (Hive o : s.hivesIn(h.sys)) {
			s.count("threatFPKilled", o.garrisonFP * taken);
			o.garrisonFP *= 1f - taken;
			if (o.garrisonFP < 1f) o.garrisonFP = 0f;   // the last fleet is sunk, not halved for ever
		}
		s.count("siegeFightDays", 1);
	}

	/** A siege parcel on station: one day. Returns the outcome when the siege of this hive ends, else null. */
	static String orbitDay(State s, Parcel p, HumanOrder o) {
		Hive h = o.target;
		if (h == null || h.dead) return "gone";
		float abort = p.fp0 * BattleRules.GROUP_ABORT_FRACTION;
		if (p.fp < abort) return "beaten";
		boolean front = h.front != null && p.owner.equals(h.front.faction);
		float minMarines = s.knobs.f("threatinc_frontMinMarines");
		if (!front && p.marines < minMarines) return "nothing to land";
		float enemy = enemyAt(s, h);
		float ratio = s.knobs.f("threatinc_siegeBreakOffRatio");
		if (!front && BattleRules.callsOff(enemy, p.fp, friendsOf(s, p), ratio)) {
			// ThreatPurgeFGI.breaksOff posts the bounty on the swarms it met
			HumanPlanner.postBounty(s, h.sys, p.owner);
			return "called off";
		}
		if (enemy >= 1f) {
			fight(s, p, h, enemy);
			o.fights++;
			if (p.fp < abort) return "beaten";
			float left = enemyAt(s, h);
			if (BattleRules.orbitContested(left, p.fp + friendsOf(s, p), s.knobs.f("threatinc_orbitContestFraction"))) {
				if (o.orbitDays >= s.knobs.i("threatinc_siegeOrbitDays")) return "held";
				o.orbitDays++;
				return null;
			}
		}
		if (front) return "front";
		if (o.orbitDays >= s.knobs.i("threatinc_siegeOrbitDays")) return "landed (days)";
		int held = 0;
		float[] step = bombardDay(s, h, p.fp, h.siegeClock, held);
		// gunsWouldBreak: the guns' answer would take the flotilla under its abort line
		if (step[1] > 0f && p.fp - step[1] < abort) return "guns";
		float d = defence(s, h);
		boolean ready = p.marines >= troopsToLand(s, d);
		// orbitDone: the plan from here buys no more days
		if (!ready && bombardPlan(s, h, p.fp, 1f, p.marines)[0] < 1f) ready = true;
		if (ready) return "landed (ready)";
		float perDay = BattleRules.bombardFuelPerDay(p.fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		if (BattleRules.bombardDaysFor(p.fuel, perDay) < 0.5f) return "landed (dry)";
		p.fuel = Math.max(0f, p.fuel - perDay);
		hullsLost(s, p, step[1] / Math.max(1f, p.fp));
		p.fp -= step[1];
		suppress(s, h, step[0]);
		o.orbitDays++;
		s.count("bombardDays", 1);
		return null;
	}

	/** The landing pass (endWorld): a front opened or reinforced with what is aboard. True if troops went down. */
	static boolean land(State s, Parcel p, HumanOrder o, String why) {
		Hive h = o.target;
		if (h == null || h.dead || p.marines < 1f) return false;
		boolean own = h.front != null && p.owner.equals(h.front.faction);
		if (h.front != null && !own) return false;
		if (!own && p.marines < s.knobs.f("threatinc_frontMinMarines")) return false;
		// siegeNoDoomedLanding (round 31, ThreatPurgeFGI.doCustomRaidAction): a first landing short of the beachhead its
		// first counter-attack leaves is not made, whatever ended the siege (orbit done, dry, days, guns)
		if (!own && s.knobs.b("threatinc_siegeNoDoomedLanding", false) && p.marines < troopsToLand(s, defence(s, h))) {
			s.count("landingRefused", 1);
			s.log("Siege of " + h.name + ": no landing - " + (int) p.marines + " marines would not outlast a counter-attack of "
					+ (int) defence(s, h));
			return false;
		}
		if (!own) {
			Front f = new Front();
			f.faction = p.owner;
			f.openedDay = s.day;
			f.adopted = true;
			// the stance: a landing the first counter-attack cannot overrun pushes, anything less digs in
			f.pushing = p.marines * s.knobs.f("threatinc_frontLandingMult")
					* BattleRules.overrunOdds(s.knobs.f("threatinc_groundStrengthExponent")) >= defence(s, h);
			h.front = f;
			s.count("frontsOpened", 1);
		}
		// "Orbit cover over X: the persean flotilla holds it with 1032 FP" (59 in pd9a, 9 later lost)
		h.front.coverFP = Math.max(h.front.coverFP, p.fp);
		h.front.marines += p.marines;
		h.front.armaments += p.armaments;
		s.log("Front deployed at " + h.name + " (" + p.owner + "): " + (int) p.marines + " marines, "
				+ (int) p.armaments + " armaments, " + (h.front.pushing ? "pushing" : "dug in"));
		p.marines = 0f;
		p.armaments = 0f;
		return true;
	}

	// ---- the ground front ----

	static float eff(State s, Front f) {
		float landing = s.knobs.f("threatinc_frontLandingMult");
		float full = s.knobs.f("threatinc_frontEntrenchMaxMult");
		float dug = Math.min(1f, f.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
		float e = f.marines * (landing + (full - landing) * dug);
		if (f.armaments <= 0f) e *= s.knobs.f("threatinc_frontDryEffectivenessMult");
		return e;
	}

	/** ThreatGroundFronts.counterAttackInterval's clock rate on a hive: its body still standing, and the tempo of the force ratio. */
	static float counterRate(State s, Hive h, Front f, float d, float e) {
		float clamp = Math.max(1f, s.knobs.f("threatinc_counterAttackRatioClamp"));
		float ratio = (float) Math.pow(d / Math.max(1f, e), s.knobs.f("threatinc_groundStrengthExponent"));
		float tempo = Math.max(1f / clamp, Math.min(clamp, ratio));
		float body = Math.max(0, h.size - f.strataHeld) / (float) Math.max(1, h.size);
		return Math.max(0.25f, body) * tempo;
	}

	/** paceRatio: defence over strength, clamped 0.5-3 - days a stratum takes over frontPushBaseDays. */
	static float pace(State s, float d, float e) {
		float ratio = (float) Math.pow(d / Math.max(1f, e), s.knobs.f("threatinc_groundStrengthExponent"));
		return Math.max(0.5f, Math.min(3f, ratio));
	}

	/**
	 * ThreatGroundFronts.shouldBrace: a front holding no ground that the next counter-attack would
	 * overrun out of its holes (overrunIfPushing: cover 1, past 2:1) digs in, unless its assault
	 * takes the stratum first. pd9a: 1,000+ "Front at X braces for a counter-attack in N d".
	 */
	static boolean shouldBrace(State s, Hive h, Front f, float d, float e) {
		if (!s.knobs.b("threatinc_frontBraceEnabled", true) || f.strataHeld > 0) return false;
		if (d <= e || d <= e * BattleRules.overrunOdds(s.knobs.f("threatinc_groundStrengthExponent"))) return false;
		float due = Math.max(0f, s.knobs.f("threatinc_frontCounterAttackDays") - f.counterClock) / counterRate(s, h, f, d, e);
		float left = Math.max(0f, s.knobs.f("threatinc_frontPushBaseDays") - f.pushDays) * pace(s, d, e) + f.checkpointLeft;
		return left > due;
	}

	/**
	 * One day of a human front on a hive (ThreatGroundFronts.tickFront, hiveCounterAttack, the NPC
	 * stance AI). Returns "victory", "overrun", "collapsed" or null.
	 */
	static String frontDay(State s, Hive h) {
		Front f = h.front;
		float exponent = s.knobs.f("threatinc_groundStrengthExponent");
		float hold = s.knobs.f("threatinc_frontHoldFraction");
		if (!f.adopted) {
			// a front found in the start state: its stance from its strength
			f.adopted = true;
			f.pushing = f.marines * s.knobs.f("threatinc_frontLandingMult") * BattleRules.overrunOdds(exponent)
					>= defence(s, h);
		}
		boolean dry = f.armaments <= 0f;
		// a dry front breaks off its assault and digs in; one a counter-attack would catch exposed braces
		if (f.pushing && (dry || shouldBrace(s, h, f, defence(s, h), eff(s, f)))) f.pushing = false;
		boolean consolidating = f.checkpointLeft > 0f;
		boolean exposed = f.pushing && !consolidating;
		// armaments burned, marines lost to the fighting and to a swarm holding the orbit
		float burn = s.knobs.f("threatinc_frontArmamentsPerMarinePer30Days") * f.marines / 30f
				* (exposed ? s.knobs.f("threatinc_frontPushUpkeepMult") : 1f);
		f.armaments = Math.max(0f, f.armaments - burn);
		float loss = (exposed ? s.knobs.f("threatinc_frontPushLossPer30Days")
				: s.knobs.f("threatinc_frontMarineLossPer30Days")) / 30f;
		if (dry) loss *= s.knobs.f("threatinc_frontUnsuppliedLossMult");
		float e = eff(s, f);
		// swarmHoldsOrbit: the swarm at the planet in force (swarmOrbitMinFleetFP), no hostile fleet there and
		// no flotilla's cover still outweighing it (swarmOrbitContested; once outweighed the cover is lost for good)
		float over = h.garrisonFP;
		if (f.coverFP > 0f && f.coverFP < over) {
			s.count("coverLost", 1);
			f.coverFP = 0f;
		}
		if (over >= s.knobs.f("threatinc_swarmOrbitMinFleetFP") && f.coverFP <= 0f && s.holdingFP(h.sys, false) <= 0f) {
			loss += s.knobs.f("threatinc_swarmFrontBombardPer30Days") / 30f * over / (over + Math.max(1f, e * cover(s, f, exposed)));
		}
		f.marines -= f.marines * Math.min(1f, loss);
		if (f.marines < s.knobs.f("threatinc_frontMinMarines")) return "collapsed";
		// cover is dug while holding; an assault leaves it behind
		if (!exposed) f.entrenchDays += 1f;

		e = eff(s, f);
		float d = defence(s, h);
		f.state = e >= d * hold ? "holding" : e >= d * s.knobs.f("threatinc_frontGrindFraction") ? "grinding" : "foothold";
		// the front wears the fortifications it faces
		suppress(s, h, s.knobs.f("threatinc_frontWearRate") * e / Math.max(1f, e + d));

		if (f.pushing) {
			if (f.checkpointLeft > 0f) {
				f.checkpointLeft -= 1f;
			} else if (e >= d * hold) {
				f.pushDays += 1f / pace(s, d, e);
				if (f.pushDays >= s.knobs.f("threatinc_frontPushBaseDays")) {
					f.pushDays = 0f;
					f.strataHeld++;
					SwarmOps.stratumTaken(s, f.faction);
					f.checkpointLeft = s.knobs.f("threatinc_frontCheckpointDays");
					if (f.strataHeld >= h.size) return "victory";
					s.log("Front took stratum " + f.strataHeld + "/" + h.size + " at " + h.name);
				}
			}
		}
		// the hive's counter-attack, the sooner the more it outweighs the front
		f.counterClock += counterRate(s, h, f, d, e);
		if (f.counterClock >= s.knobs.f("threatinc_frontCounterAttackDays")) {
			f.counterClock = 0f;
			float guard = e * cover(s, f, f.pushing && f.checkpointLeft <= 0f);
			if (d > guard) {
				s.count("counterAttacks", 1);
				f.marines *= 1f - s.knobs.f("threatinc_frontCounterAttackLossFraction");
				// thrown back or battered, the front's cover is spoilt (hiveCounterAttack)
				f.entrenchDays *= s.knobs.f("threatinc_frontEntrenchKeptFraction");
				if (f.strataHeld > 0) {
					f.strataHeld--;
					f.pushDays = 0f;
					f.pushing = false;
				} else if (d > guard * BattleRules.overrunOdds(exponent)) {
					s.log("Counter-attack at " + h.name + " overran the beachhead (" + (int) d + " vs " + (int) guard + ")");
					return "overrun";
				}
			}
		}
		// the NPC stance AI: push whenever strong enough and supplied, dig in otherwise
		if (!f.pushing && f.armaments > 0f && eff(s, f) >= d * hold && !shouldBrace(s, h, f, d, eff(s, f))) f.pushing = true;
		return null;
	}

	/** coverMult: a dug-in front fights from cover, an assault leaves it behind. */
	static float cover(State s, Front f, boolean exposed) {
		if (exposed) return 1f;
		return 1f + (s.knobs.f("threatinc_frontEntrenchDefenseBonus") - 1f)
				* Math.min(1f, f.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
	}

	/** PlannerRules.leastForGain over this hive: the least fleet whose day of bombardment adds a day. */
	static float leastForGain(final State s, final Hive h) {
		return PlannerRules.leastForGain(new PlannerRules.Gain() {
			public float at(float fp) { return bombardDay(s, h, fp, h.siegeClock, 0)[0]; }
		});
	}
}
