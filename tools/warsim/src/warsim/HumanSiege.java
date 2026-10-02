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
	static float enemyAt(State s, Hive h) { return h.garrisonFP + s.holdingFP(h.sys, true); }

	/** Human fleet points on station in the system besides this parcel. */
	static float friendsOf(State s, Parcel p) { return s.holdingFP(p.to, false) - (p.holding ? p.fp : 0f); }

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
			}
		}
		s.count("threatFPKilled", h.garrisonFP * taken);
		h.garrisonFP *= 1f - taken;
		if (h.garrisonFP < 1f) h.garrisonFP = 0f;   // the last fleet is sunk, not halved for ever
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
		if (!front && BattleRules.callsOff(enemy, p.fp, friendsOf(s, p), ratio)) return "called off";
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

	/** One day of a human front on a hive. Returns "victory", "overrun", "collapsed" or null. */
	static String frontDay(State s, Hive h) {
		Front f = h.front;
		float exponent = s.knobs.f("threatinc_groundStrengthExponent");
		if (!f.adopted) {
			// a front found in the start state: its stance from its strength
			f.adopted = true;
			f.pushing = f.marines * s.knobs.f("threatinc_frontLandingMult") * BattleRules.overrunOdds(exponent)
					>= defence(s, h);
		}
		boolean dry = f.armaments <= 0f;
		// armaments burned, marines lost to the fighting and to a swarm holding the orbit
		float burn = s.knobs.f("threatinc_frontArmamentsPerMarinePer30Days") * f.marines / 30f
				* (f.pushing ? s.knobs.f("threatinc_frontPushUpkeepMult") : 1f);
		f.armaments = Math.max(0f, f.armaments - burn);
		float loss = (f.pushing ? s.knobs.f("threatinc_frontPushLossPer30Days")
				: s.knobs.f("threatinc_frontMarineLossPer30Days")) / 30f;
		if (dry) loss *= s.knobs.f("threatinc_frontUnsuppliedLossMult");
		float e = eff(s, f);
		float over = enemyAt(s, h);
		if (over > 0f && s.holdingFP(h.sys, false) <= 0f) {
			loss += s.knobs.f("threatinc_swarmFrontBombardPer30Days") / 30f * over / (over + Math.max(1f, e));
		}
		f.marines -= f.marines * Math.min(1f, loss);
		if (f.marines < s.knobs.f("threatinc_frontMinMarines")) return "collapsed";
		f.entrenchDays += 1f;

		e = eff(s, f);
		float d = defence(s, h);
		f.state = e >= d * s.knobs.f("threatinc_frontHoldFraction") ? "holding"
				: e >= d * s.knobs.f("threatinc_frontGrindFraction") ? "grinding" : "foothold";
		// the front wears the fortifications it faces
		suppress(s, h, s.knobs.f("threatinc_frontWearRate") * e / Math.max(1f, e + d));

		float pace = (float) Math.pow(d / Math.max(1f, e), exponent);
		pace = Math.max(0.5f, Math.min(3f, pace));
		if (f.pushing) {
			if (f.checkpointLeft > 0f) {
				f.checkpointLeft -= 1f;
			} else {
				f.pushDays += 1f / pace;
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
		f.counterClock += Math.max(0.25f, pace);
		if (f.counterClock >= s.knobs.f("threatinc_frontCounterAttackDays")) {
			f.counterClock = 0f;
			float cover = f.pushing ? 1f : 1f + (s.knobs.f("threatinc_frontEntrenchDefenseBonus") - 1f)
					* Math.min(1f, f.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
			float guard = e * cover;
			if (d > guard) {
				s.count("counterAttacks", 1);
				f.marines *= 1f - s.knobs.f("threatinc_frontCounterAttackLossFraction");
				if (f.strataHeld > 0) {
					f.strataHeld--;
					f.entrenchDays *= s.knobs.f("threatinc_frontEntrenchKeptFraction");
				} else if (d > guard * BattleRules.overrunOdds(exponent)) {
					s.log("Counter-attack at " + h.name + " overran the beachhead (" + (int) d + " vs " + (int) guard + ")");
					return "overrun";
				}
			}
		}
		return null;
	}

	/** PlannerRules.leastForGain over this hive: the least fleet whose day of bombardment adds a day. */
	static float leastForGain(final State s, final Hive h) {
		return PlannerRules.leastForGain(new PlannerRules.Gain() {
			public float at(float fp) { return bombardDay(s, h, fp, h.siegeClock, 0)[0]; }
		});
	}
}
