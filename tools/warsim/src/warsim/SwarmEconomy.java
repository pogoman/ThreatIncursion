package warsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import threatinc.rules.HiveRules;
import threatinc.rules.StanceRules;

/**
 * The hive's economy, a day at a time: fleet point banks and garrisons
 * (ThreatColonyManager.maintainColonyGarrisons), the two stocks and their trailing demand
 * (ThreatFuel), size upkeep and growth (ThreatColonyUpkeep.feed, advanceFedGrowth) and the
 * planner (planHiveEconomy). The formulas shared with the mod are threatinc.rules calls;
 * the rest is modelled (docs/war-sim-swarm.md).
 */
final class SwarmEconomy {
	private SwarmEconomy() {}

	static final int FUEL = Swarm.FUEL, SUPPLIES = Swarm.SUPPLIES;

	// ------------------------------------------------------------------
	// garrisons: tables, costs, what a colony holds and may send
	// ------------------------------------------------------------------

	/** Brings the fleet list in line with garrisonFP, which the human side lowers in a fight; returns the FP lost. */
	static float sync(Hive h) {
		float sum = h.swarmSum();
		float lost = 0f;
		if (h.swarms.isEmpty() && h.garrisonFP > 0.5f) {
			int n = Math.max(1, h.garrisonFleets);
			for (int i = 0; i < n; i++) h.swarms.add(h.garrisonFP / n);
		} else if (h.garrisonFP < sum - 0.5f) {
			lost = sum - Math.max(0f, h.garrisonFP);
			float keep = sum > 0f ? Math.max(0f, h.garrisonFP) / sum : 0f;
			List<Float> left = new ArrayList<Float>();
			for (Float f : h.swarms) if (f * keep >= 1f) left.add(f * keep);
			h.swarms.clear();
			h.swarms.addAll(left);
		} else if (h.garrisonFP > sum + 0.5f) {
			h.swarms.add(h.garrisonFP - sum);
		}
		h.book();
		return lost;
	}

	static float inbound(State s, Hive h) {
		float fp = 0f;
		for (Parcel p : s.parcels) {
			if (!p.done && p.threat() && p.kind == Parcel.Kind.REINFORCEMENT && h.id.equals(p.targetId)) fp += p.fp;
		}
		return fp;
	}

	/** ownedFleetFP: the garrison and the reinforcements flying in. */
	static float held(State s, Hive h) { return h.garrisonFP + inbound(s, h); }

	/** inbound for every hive in one parcel scan, by hive id (round 9: the posture poll read it per hive per comparison). */
	static java.util.Map<String, Float> inboundMap(State s) {
		java.util.Map<String, Float> m = new java.util.HashMap<String, Float>();
		for (Parcel p : s.parcels) {
			if (p.done || !p.threat() || p.kind != Parcel.Kind.REINFORCEMENT || p.targetId == null) continue;
			Float was = m.get(p.targetId);
			m.put(p.targetId, (was == null ? 0f : was) + p.fp);
		}
		return m;
	}

	static float held(Hive h, java.util.Map<String, Float> inbound) {
		Float in = inbound.get(h.id);
		return h.garrisonFP + (in == null ? 0f : in);
	}

	static int[][] table(Hive h) {
		return HiveRules.desiredGarrison(h.size, SwarmFit.LOW, SwarmFit.MEDIUM, SwarmFit.HIGH, SwarmFit.MAXIMUM);
	}

	/** swarmCostEstimate: the mean such swarms came out at, else the mod's table (indexed by the tier's ordinal). */
	static float estimate(State s, int[] spec) {
		float[] l = s.swarm.learned.get(spec[0] + ":" + spec[1]);
		if (l != null && l[1] > 0f) return l[0] / l[1];
		return HiveRules.swarmCostFallback(spec[0], spec[1]);
	}

	static void learn(State s, int[] spec, float fp) {
		String key = spec[0] + ":" + spec[1];
		float[] l = s.swarm.learned.get(key);
		if (l == null) s.swarm.learned.put(key, l = new float[2]);
		l[0] += fp;
		l[1] += 1f;
	}

	static float[] sortedRowCosts(State s, Hive h) {
		int[][] t = table(h);
		float[] c = new float[t.length];
		for (int i = 0; i < t.length; i++) c[i] = estimate(s, t[i]);
		Arrays.sort(c);
		return c;
	}

	/** ThreatPosture.rowsFP: n rows of the size table from the from-th cheapest, wrapping. */
	static float rowsFP(State s, Hive h, int from, int n) {
		float[] c = sortedRowCosts(s, h);
		float fp = 0f;
		for (int i = 0; i < n; i++) fp += c[(from + i) % c.length];
		return fp;
	}

	static int baseReserve(Hive h) { return Math.max(1, table(h).length / 2); }

	static int reserve(Hive h) { return baseReserve(h) * (1 + h.tier); }

	static float minimumFP(State s, Hive h) { return rowsFP(s, h, 0, reserve(h)); }

	static float baseFP(State s, SwarmKnobs k, Hive h) {
		float fp = minimumFP(s, h);
		if (h.forge && h.size >= Math.min(k.spreadMinSize, k.strikeMinSize)) {
			fp += rowsFP(s, h, reserve(h), SwarmFit.LAUNCH_STOCK);
		}
		return fp;
	}

	static float floorFP(State s, Hive h) {
		float fp = 0f;
		for (int[] row : table(h)) fp += estimate(s, row);
		return fp;
	}

	static float oneSwarmFP(State s, Hive h) {
		float[] c = sortedRowCosts(s, h);
		return c[c.length - 1];
	}

	static float want(State s, SwarmKnobs k, Hive h) { return h.wantFP > 0f ? h.wantFP : baseFP(s, k, h); }

	static boolean regrowing(State s, SwarmKnobs k, Hive h) {
		return held(s, h) < want(s, k, h) - rowsFP(s, h, 0, 1);
	}

	static boolean pressed(State s, StarSys sys) {
		Swarm.Post p = s.swarm.post.get(sys.id);
		return p != null && p.mode >= 2;
	}

	/** ownAvailableForLaunch: the swarms above the reserve, none while regrowing or while its system is pressed. */
	static int available(State s, SwarmKnobs k, Hive h) {
		if (regrowing(s, k, h) || pressed(s, h.sys)) return 0;
		return Math.max(0, h.swarms.size() - reserve(h));
	}

	static int poolAvailable(State s, SwarmKnobs k, StarSys sys) {
		int n = 0;
		for (Hive h : s.hivesIn(sys)) n += available(s, k, h);
		return n;
	}

	/** Musters one swarm from the system's pool, the largest spare first (musterFrom); its FP, or 0 with none. */
	static float takeSpare(State s, SwarmKnobs k, StarSys sys) {
		Hive from = null;
		int at = -1;
		float best = 0f;
		for (Hive h : s.hivesIn(sys)) {
			if (available(s, k, h) <= 0) continue;
			for (int i = 0; i < h.swarms.size(); i++) {
				if (h.swarms.get(i) > best) {
					best = h.swarms.get(i);
					from = h;
					at = i;
				}
			}
		}
		if (from == null) return 0f;
		from.swarms.remove(at);
		from.book();
		return best;
	}

	static float poolable(State s, SwarmKnobs k, Hive h) {
		float fp = h.bank;
		for (Hive o : s.hivesIn(h.sys)) if (o != h && o.bank > 0f && !regrowing(s, k, o)) fp += o.bank;
		return fp;
	}

	/** poolSystemBanks: tops the colony's bank up to the bill from its system's; false, nothing moved, if they cannot. */
	static boolean pool(State s, SwarmKnobs k, Hive h, float bill) {
		float shortBy = bill - h.bank;
		if (shortBy <= 0f) return true;
		if (poolable(s, k, h) < bill) return false;
		for (Hive o : s.hivesIn(h.sys)) {
			if (shortBy <= 0f) break;
			if (o == h || o.bank <= 0f || regrowing(s, k, o)) continue;
			float take = Math.min(shortBy, o.bank);
			o.bank -= take;
			h.bank += take;
			shortBy -= take;
		}
		return true;
	}

	// ------------------------------------------------------------------
	// the two stocks (ThreatFuel)
	// ------------------------------------------------------------------

	static float stock(State s, int c) { return c == FUEL ? s.swarm.fuel : s.swarm.supplies; }

	static float perMonth(State s, SwarmKnobs k, int c) {
		float units = 0f;
		for (Hive h : s.hives) if (!h.dead) units += c == FUEL ? h.fuelUnits() : h.forgeUnits();
		return units * (c == FUEL ? SwarmFit.FUEL_UNIT : SwarmFit.SUPPLIES_UNIT) * k.surplusMult;
	}

	static float comingPerMonth(State s, SwarmKnobs k, int c) {
		float units = 0f;
		for (Hive h : s.hives) {
			if (h.dead) continue;
			boolean building = c == FUEL ? h.fuelPlant && h.fuelPlantBuilding > 0f : h.forge && h.forgeBuilding > 0f;
			if (building) units += Math.max(0, h.size - 2);
		}
		return units * (c == FUEL ? SwarmFit.FUEL_UNIT : SwarmFit.SUPPLIES_UNIT) * k.surplusMult;
	}

	static float largestOutput(State s, SwarmKnobs k, int c) {
		float best = 0f;
		for (Hive h : s.hives) if (!h.dead) best = Math.max(best, c == FUEL ? h.fuelUnits() : h.forgeUnits());
		return best * (c == FUEL ? SwarmFit.FUEL_UNIT : SwarmFit.SUPPLIES_UNIT) * k.surplusMult;
	}

	static void noteDemand(State s, int c, float amount) {
		s.swarm.demand[c] = Math.max(0f, s.swarm.demand[c] + amount);
	}

	/**
	 * ThreatFuel.bookHold: whether a send's hold is booked as demand now - once a SHORT_DAYS a send, however many
	 * polls hold it. Before round 26 every held poll booked its bill, and the planner answered with fuel plants the
	 * game never builds (49 against 18 at month 108 of hw4); warsim_holdsBookMonthly false restores that.
	 */
	static boolean bookHold(State s, SwarmKnobs k, String what) {
		if (!k.holdsBookMonthly) return true;
		Integer at = s.swarm.heldBooked.get(what);
		if (at != null && s.day - at >= 0 && s.day - at < SwarmFit.SHORT_DAYS) return false;
		s.swarm.heldBooked.put(what, s.day);
		return true;
	}

	static boolean canPay(State s, int c, float amount) { return amount <= 0f || stock(s, c) >= amount; }

	/** Draws from the stock, booking the demand; false, nothing drawn, if it is short. */
	static boolean pay(State s, int c, float amount) {
		if (amount <= 0f) return true;
		if (stock(s, c) < amount) return false;
		if (c == FUEL) s.swarm.fuel -= amount; else s.swarm.supplies -= amount;
		noteDemand(s, c, amount);
		s.count(c == FUEL ? "swarmFuelSpent" : "swarmSuppliesSpent", amount);
		return true;
	}

	static float demandPerMonth(State s, int c) {
		return s.swarm.demandDays[c] > 0f ? s.swarm.demand[c] / s.swarm.demandDays[c] * 30f : 0f;
	}

	static boolean observed(State s, float days) {
		return s.swarm.demandSince != Integer.MIN_VALUE && s.day - s.swarm.demandSince >= days;
	}

	static boolean runsDryWithout(State s, SwarmKnobs k, int c, float lost) {
		if (!observed(s, SwarmFit.SHORT_DAYS)) return false;
		float months = SwarmFit.STOCK_TAU_DAYS / 30f;
		float production = perMonth(s, k, c) + comingPerMonth(s, k, c) - lost;
		return stock(s, c) < (demandPerMonth(s, c) - production) * months;
	}

	static boolean wanted(State s, SwarmKnobs k, int c) {
		if (c == SUPPLIES && s.swarm.suppliesShortDay != Integer.MIN_VALUE
				&& s.day - s.swarm.suppliesShortDay < SwarmFit.SHORT_DAYS) return true;
		return runsDryWithout(s, k, c, 0f);
	}

	static boolean mayAnswer(State s, SwarmKnobs k, int c) {
		if (!wanted(s, k, c)) return false;
		return s.swarm.answeredDay[c] == Integer.MIN_VALUE || s.day - s.swarm.answeredDay[c] >= SwarmFit.SHORT_DAYS;
	}

	static boolean wantsSpare(State s, SwarmKnobs k, int c) {
		return runsDryWithout(s, k, c, largestOutput(s, k, c));
	}

	/** ThreatReach.canSustain(fp, days): the trip's supplies out of the spare flow, else the flow and the free stock over it. */
	static boolean canSustain(State s, SwarmKnobs k, float fp, float days) {
		float need = fp * SwarmFit.SUPPLIES_PER_FP_AWAY;
		if (need <= s.swarm.spare) return true;
		float free = Math.max(0f, s.swarm.supplies - k.foundSupplies());
		return threatinc.rules.StrikeRules.canSustain(need, s.swarm.spare, days, free);
	}

	// ------------------------------------------------------------------
	// the day
	// ------------------------------------------------------------------

	static void daily(State s, SwarmKnobs k) {
		Swarm sw = s.swarm;
		List<Hive> live = s.liveHives();
		for (Hive h : live) {
			h.forgeDown = Math.max(0f, h.forgeDown - 1f);
			h.fuelPlantDown = Math.max(0f, h.fuelPlantDown - 1f);
			h.nexusDown = Math.max(0f, h.nexusDown - 1f);
			h.coreDown = Math.max(0f, h.coreDown - 1f);
			h.forgeBuilding = Math.max(0f, h.forgeBuilding - 1f);
			h.fuelPlantBuilding = Math.max(0f, h.fuelPlantBuilding - 1f);
			if (h.tierPending > 0) {
				h.tierDays -= 1f;
				if (h.tierDays <= 0f) {
					h.tier = h.tierPending;
					h.tierPending = 0;
				}
			}
			float lost = sync(h);
			if (lost > 0f) SwarmPosture.noteLoss(s, h.sys, lost);
		}

		// fleet point banks: the forges' hulls shared among the nexuses by their draw
		float out = 0f, best = 0f, hiveDraw = 0f;
		for (Hive h : live) {
			int u = h.forgeUnits();
			out += u;
			best = Math.max(best, u);
		}
		float[] draws = new float[live.size()];
		for (int i = 0; i < live.size(); i++) {
			Hive h = live.get(i);
			draws[i] = h.core && h.coreDown <= 0f && h.nexusUp() ? Math.min(best, h.size) : 0f;
			hiveDraw += draws[i];
		}
		for (int i = 0; i < live.size(); i++) {
			Hive h = live.get(i);
			float income = HiveRules.fabricationRatePerDay(draws[i], out, hiveDraw, k.fpUnit);
			float upkeep = HiveRules.upkeepPerDay(h.garrisonFP, k.upkeepPerMonth);
			h.bank += income - upkeep;
			recycleForUpkeep(s, k, h, income, upkeep);
			if (h.coreDown > 0f || !h.nexusUp()) continue;
			// one swarm a poll, two polls a day (GARRISON_POLL_DAYS 0.5)
			for (int poll = 0; poll < 2; poll++) if (!buildSwarm(s, k, h)) break;
		}

		// the stocks, and the trailing demand they are planned by
		if (sw.demandSince == Integer.MIN_VALUE) {
			sw.demandSince = s.day;
			for (int c = 0; c < 2; c++) {
				sw.demand[c] = perMonth(s, k, c) / 30f * SwarmFit.STOCK_TAU_DAYS;
				sw.demandDays[c] = SwarmFit.STOCK_TAU_DAYS;
			}
		} else {
			float decay = (float) Math.exp(-1f / SwarmFit.STOCK_TAU_DAYS);
			for (int c = 0; c < 2; c++) {
				sw.demand[c] *= decay;
				sw.demandDays[c] = sw.demandDays[c] * decay + SwarmFit.STOCK_TAU_DAYS * (1f - decay);
			}
		}
		sw.fuel += perMonth(s, k, FUEL) / 30f;
		sw.supplies += perMonth(s, k, SUPPLIES) / 30f;

		// fleets away burn supplies (ThreatColonyManager.paySupplies)
		float away = 0f;
		for (Parcel p : s.parcels) if (SwarmOps.burns(s, p)) away += p.fp;
		sw.awayPerMonth = away * SwarmFit.SUPPLIES_PER_FP_AWAY;
		float wantAway = sw.awayPerMonth / 30f;
		if (wantAway > 0f) {
			float paid = Math.min(wantAway, sw.supplies);
			pay(s, SUPPLIES, paid);
			s.count("swarmSupplies.away", paid);
			float unpaid = wantAway - paid;
			if (unpaid <= 0f) {
				sw.awayOwed = 0f;
			} else {
				noteDemand(s, SUPPLIES, unpaid);
				sw.awayOwed += unpaid;
				if (sw.awayOwed >= sw.awayPerMonth) {
					sw.awayOwed = 0f;
					SwarmOps.starveAway(s);
				}
			}
		}

		feed(s, k, live);
		for (Hive h : live) grow(s, k, h);
		for (Hive h : live) if (!h.dead && h.waiting != Hive.NONE) buyWaiting(s, k, h);
	}

	/** recycleForUpkeep: a bank below 0 whose income does not cover its upkeep recycles its smallest swarms. */
	static void recycleForUpkeep(State s, SwarmKnobs k, Hive h, float income, float upkeep) {
		float rate = HiveRules.upkeepPerDay(1f, k.upkeepPerMonth);
		int budget = h.swarms.size();
		for (int i = 0; i < budget; i++) {
			if (h.bank >= 0f || income >= upkeep || h.swarms.isEmpty()) break;
			int at = 0;
			for (int j = 1; j < h.swarms.size(); j++) if (h.swarms.get(j) < h.swarms.get(at)) at = j;
			float fp = h.swarms.remove(at);
			h.bank += fp * k.hullShare;
			upkeep -= fp * rate;
			s.count("swarmsRecycled", 1);
		}
		h.book();
	}

	/** maintainColonyGarrisons' build: one swarm of the table's next row while the colony holds less than it wants and the bank pays. */
	static boolean buildSwarm(State s, SwarmKnobs k, Hive h) {
		if (held(s, h) >= want(s, k, h)) return false;
		int[][] t = table(h);
		int n = h.swarms.size();
		int[] spec = t[n < t.length ? n : n % t.length];
		if (h.bank < estimate(s, spec)) return false;
		float fp = SwarmFit.builtFP(spec[0], spec[1], s.rng);
		h.bank -= fp;
		learn(s, spec, fp);
		h.swarms.add(fp);
		h.book();
		s.count("swarmsBuilt", 1);
		return true;
	}

	// ------------------------------------------------------------------
	// size upkeep (ThreatColonyUpkeep.feed) and growth (advanceFedGrowth)
	// ------------------------------------------------------------------

	private static final class Need {
		Hive h;
		int size;
		float want, cap, sustain, paid, frontLY;
		boolean forge, forgeStepPays, grows, seed;
	}

	static boolean saturated(State s, StarSys sys) {
		for (Parcel p : s.parcels) {
			if (!p.done && p.holding && !p.threat() && p.to == sys && p.kind == Parcel.Kind.SATURATION) return true;
		}
		return false;
	}

	static float frontLY(State s, Hive h) {
		float best = Float.MAX_VALUE;
		for (World w : s.worlds) if (!w.lost) best = Math.min(best, h.sys.ly(w.sys));
		return best;
	}

	static void feed(State s, SwarmKnobs k, List<Hive> live) {
		Swarm sw = s.swarm;
		final float days = 1f;
		float t = k.breakEven;
		List<Need> needs = new ArrayList<Need>();
		for (Hive h : live) {
			float perMonth = k.sizeUpkeep(h.size);
			float local = h.forgeUnits() * SwarmFit.SUPPLIES_UNIT * k.surplusMult * days / 30f;
			boolean seed = perMonth <= 0f && h.size == 2 && !h.forge;
			if (perMonth <= 0f && !seed) {
				h.fed = 1f;
				continue;
			}
			Need n = new Need();
			n.h = h;
			n.size = h.size;
			n.seed = seed;
			n.want = (seed ? k.sizeUpkeep(3) : perMonth) * days / 30f;
			float own = seed ? 0f : Math.min(n.want, local);
			n.cap = own + (n.want - own);
			n.sustain = seed ? 0f : Math.min(t * n.want, n.cap);
			n.forge = local > 0f && local >= n.sustain;
			n.forgeStepPays = n.forge && SwarmFit.SUPPLIES_UNIT * k.surplusMult
					>= t * (k.sizeUpkeep(h.size + 1) - k.sizeUpkeep(h.size));
			n.grows = h.size < k.maxSize && h.front == null && !saturated(s, h.sys);
			n.frontLY = frontLY(s, h);
			needs.add(n);
		}
		float sustainMonth = 0f;
		for (Need n : needs) sustainMonth += n.sustain;
		sustainMonth *= 30f / days;
		// ThreatColonyUpkeep.sustainShare: the largest stance share; warsim_sustainShare (round 20) sets it apart from them
		float sustainCap = k.sustainShare > 0f ? Math.min(1f, k.sustainShare)
				: Math.max(0f, Math.min(1f, Math.max(k.feedExpand, Math.max(k.feedPress, k.feedConsolidate))));
		float madeMonth = perMonth(s, k, SUPPLIES);
		sw.spare = madeMonth - sw.awayPerMonth - (sustainCap > 0f ? sustainMonth / sustainCap : sustainMonth);
		if (needs.isEmpty()) return;

		float stock = sw.supplies;
		float net = Math.max(0f, (madeMonth - sw.awayPerMonth) * days / 30f);
		Collections.sort(needs, new Comparator<Need>() {
			public int compare(Need a, Need b) {
				if (a.forge != b.forge) return a.forge ? -1 : 1;
				int c = Float.compare(a.frontLY, b.frontLY);
				return c != 0 ? c : b.size - a.size;
			}
		});
		float pool = Math.min(stock, sustainCap * net);
		float sustenance = 0f;
		for (Need n : needs) sustenance += n.sustain;
		if (sustenance > pool) pool += Math.max(0f, Math.min(sustenance - pool, stock - pool - k.foundSupplies()));
		float sustained = 0f, shortBy = 0f;
		for (Need n : needs) {
			float pay = Math.min(n.sustain, pool);
			n.paid = pay;
			pool -= pay;
			stock -= pay;
			sustained += pay;
			if (pay < n.sustain) shortBy += n.sustain - pay;
		}
		final int stance = sw.stance;
		float budget = Math.max(0f, StanceRules.feedShare(stance, k.feedExpand, k.feedPress, k.feedConsolidate) * net
				- sustained);
		Collections.sort(needs, new Comparator<Need>() {
			public int compare(Need a, Need b) {
				if (a.forgeStepPays != b.forgeStepPays) return a.forgeStepPays ? -1 : 1;
				int c = stance == Swarm.PRESS ? Float.compare(a.frontLY, b.frontLY) : a.size - b.size;
				return c != 0 ? c : Float.compare(a.frontLY, b.frontLY);
			}
		});
		float grown = 0f;
		for (Need n : needs) {
			if (budget <= 0f || stock <= 0f) break;
			if (!n.grows) continue;
			float next = t * (k.sizeUpkeep(n.size + 1) - k.sizeUpkeep(n.size)) * days / 30f;
			if (budget < next) continue;
			float extra = Math.min(n.cap - n.paid, Math.min(budget, stock));
			if (extra <= 0f) continue;
			n.paid += extra;
			budget -= extra;
			stock -= extra;
			grown += extra;
		}
		float draw = Math.min(sustained + grown, sw.supplies);
		pay(s, SUPPLIES, draw);
		s.count("swarmSupplies.sustenance", Math.min(draw, sustained));
		s.count("swarmSupplies.growth", Math.max(0f, draw - sustained));
		for (Need n : needs) {
			float share = n.want > 0f ? n.paid / n.want : 1f;
			if (n.seed) share = t + (1f - t) * share;
			n.h.fed = share;
		}
		if (shortBy > 0f) {
			sw.suppliesShortDay = s.day;
			noteDemand(s, SUPPLIES, shortBy);
		}
	}

	static void grow(State s, SwarmKnobs k, Hive h) {
		float rate = HiveRules.growthRate(h.fed, k.breakEven);
		float perLevel = HiveRules.daysPerLevel(k.growthBaseDays, h.size, 1f);
		float progress = h.growthDays;
		if (rate > 0f) {
			if (h.front != null || saturated(s, h.sys)) return;
			progress += rate;
			if (h.size >= k.maxSize) {
				h.growthDays = Math.min(perLevel, progress);
				return;
			}
			if (progress >= perLevel) {
				s.growHive(h);
				s.log("Colony grew to " + h.size + ": " + h.name);
				plan(s, k, h);
				return;
			}
			h.growthDays = progress;
			return;
		}
		if (rate == 0f) return;
		progress += rate * perLevel / k.starveDays;
		float floor = -SwarmFit.SHRINK_MARGIN * perLevel;
		if (progress >= floor || h.size <= 1) {
			h.growthDays = Math.max(h.size <= 1 ? 0f : floor, progress);
			return;
		}
		float below = progress / perLevel;
		s.shrinkHive(h);
		h.growthDays = Math.max(0f, (1f + below) * HiveRules.daysPerLevel(k.growthBaseDays, h.size, 1f));
	}

	// ------------------------------------------------------------------
	// the planner (ThreatColonyManager.planHiveEconomy), one build a call
	// ------------------------------------------------------------------

	static float cost(int build) {
		switch (build) {
		case Hive.MINING: return SwarmFit.COST_MINING;
		case Hive.REFINING: return SwarmFit.COST_REFINING;
		case Hive.FORGE: return SwarmFit.COST_FORGE;
		case Hive.FUELPLANT: return SwarmFit.COST_FUELPLANT;
		case Hive.GROUND_DEF: return SwarmFit.COST_GROUND_DEF;
		case Hive.BATTERIES: return SwarmFit.COST_BATTERIES;
		case Hive.ORBITAL: return SwarmFit.COST_ORBITAL;
		default: return 0f;
		}
	}

	static boolean has(Hive h, int link) {
		return link == Hive.REFINING ? h.refining : link == Hive.FORGE ? h.forge : link == Hive.FUELPLANT ? h.fuelPlant
				: link == Hive.MINING && h.mining;
	}

	static int count(State s, int link) {
		int n = 0;
		for (Hive h : s.hives) if (!h.dead && has(h, link)) n++;
		return n;
	}

	static int countIn(State s, StarSys sys, int link) {
		int n = 0;
		for (Hive h : s.hives) if (!h.dead && h.sys == sys && has(h, link)) n++;
		return n;
	}

	static List<StarSys> hiveSystems(State s) {
		java.util.Set<StarSys> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<StarSys, Boolean>());
		List<StarSys> out = new ArrayList<StarSys>();
		for (Hive h : s.hives) if (!h.dead && seen.add(h.sys)) out.add(h.sys);
		return out;
	}

	/** spreadAllows: a spare copy goes to a system among the leanest, unless no leaner one could ever take it. */
	static boolean spreadAllows(State s, SwarmKnobs k, Hive h, int link) {
		// the count of the link per hive system in one pass (round 9: was countIn per system per call)
		java.util.Map<StarSys, int[]> counts = new java.util.IdentityHashMap<StarSys, int[]>();
		List<StarSys> systems = new ArrayList<StarSys>();
		for (Hive o : s.hives) {
			if (o.dead) continue;
			int[] n = counts.get(o.sys);
			if (n == null) { counts.put(o.sys, n = new int[1]); systems.add(o.sys); }
			if (has(o, link)) n[0]++;
		}
		int min = Integer.MAX_VALUE;
		for (StarSys sys : systems) min = Math.min(min, counts.get(sys)[0]);
		int[] own = counts.get(h.sys);
		if ((own == null ? 0 : own[0]) <= min) return true;
		for (StarSys sys : systems) {
			if (counts.get(sys)[0] > min) continue;
			for (Hive o : s.hives) {
				if (o.dead || o.sys != sys || has(o, link)) continue;
				if (o.industries() < SwarmFit.maxIndustries(o.size) || o.size < k.maxSize) return false;
			}
		}
		return true;
	}

	static void plan(State s, SwarmKnobs k, Hive h) {
		h.waiting = Hive.NONE;
		h.waitingAnswers = -1;
		int build = choose(s, k, h);
		if (build == Hive.NONE) return;
		h.waiting = build;
		h.waitingCost = cost(build);
		buyWaiting(s, k, h);
	}

	private static int choose(State s, SwarmKnobs k, Hive h) {
		if (h.size >= 6 && h.groundDef && !h.batteries) return Hive.BATTERIES;
		if (h.size >= 3 && !h.groundDef && !h.batteries) return h.size >= 6 ? Hive.BATTERIES : Hive.GROUND_DEF;
		if (h.industries() >= SwarmFit.maxIndustries(h.size)) return Hive.NONE;
		if (!h.mining) return Hive.MINING;
		// the first copy of each link
		if (count(s, Hive.REFINING) == 0) return Hive.REFINING;
		if (count(s, Hive.FORGE) == 0) return Hive.FORGE;
		if (count(s, Hive.FUELPLANT) == 0) return Hive.FUELPLANT;
		// what a stock would run dry of, one answer a month
		if (!h.forge && mayAnswer(s, k, SUPPLIES)) {
			h.waitingAnswers = SUPPLIES;
			return Hive.FORGE;
		}
		if (!h.fuelPlant && mayAnswer(s, k, FUEL)) {
			h.waitingAnswers = FUEL;
			return Hive.FUELPLANT;
		}
		// threatinc_investFuelWhenTight (2026-10-03, hw4n): idle supplies build a fuel plant while fuel is tight
		// (wantsSpare), one a SHORT_DAYS - with fuel at "holds, wants a spare" forges took every free slot, and forges
		// never retire
		if (k.investFuelWhenTight && !h.fuelPlant && wantsSpare(s, k, FUEL)
				&& (s.swarm.answeredDay[FUEL] == Integer.MIN_VALUE || s.day - s.swarm.answeredDay[FUEL] >= SwarmFit.SHORT_DAYS)
				&& s.swarm.supplies >= SwarmFit.COST_FUELPLANT + k.foundSupplies()) {
			h.waitingAnswers = FUEL;
			return Hive.FUELPLANT;
		}
		// idle supplies are invested in a forge
		if (h.size >= 3 && !h.forge && s.swarm.supplies >= SwarmFit.COST_FORGE + k.foundSupplies()) return Hive.FORGE;
		if (h.size >= 6 && h.forgeBuilt() && !h.orbitalWorks) return Hive.ORBITAL;
		// redundancy: spare copies, to the leanest system
		int target = Math.max(1, hiveSystems(s).size());
		boolean[] spare = { true, wantsSpare(s, k, SUPPLIES), wantsSpare(s, k, FUEL) };
		int[] links = { Hive.REFINING, Hive.FORGE, Hive.FUELPLANT };
		for (int level = 1; level < target; level++) {
			for (int i = 0; i < links.length; i++) {
				if (!spare[i] || has(h, links[i])) continue;
				if (count(s, links[i]) <= level && spreadAllows(s, k, h, links[i])) return links[i];
			}
		}
		return Hive.NONE;
	}

	/** buyStructure / buyWaitingStructures: the chosen build, once the supplies stock pays it. */
	static void buyWaiting(State s, SwarmKnobs k, Hive h) {
		if (h.waiting == Hive.NONE) return;
		if (!pay(s, SUPPLIES, h.waitingCost)) return;
		s.count("swarmSupplies.structures", h.waitingCost);
		place(s, h, h.waiting);
		s.log("Hive planner: " + NAMES[h.waiting] + (h.waitingAnswers >= 0 ? " (short)" : "") + " at " + h.name);
		if (h.waitingAnswers >= 0) {
			s.swarm.answeredDay[h.waitingAnswers] = s.day;
			// one answer a month: the other worlds waiting on the same shortage stand down
			for (Hive o : s.hives) {
				if (o != h && o.waitingAnswers == h.waitingAnswers) {
					o.waiting = Hive.NONE;
					o.waitingAnswers = -1;
				}
			}
		}
		h.waiting = Hive.NONE;
		h.waitingAnswers = -1;
	}

	static final String[] NAMES = { "", "MINING", "refining", "heavyindustry", "fuelprod", "ground defenses",
			"heavy batteries", "ORBITALWORKS" };

	static void place(State s, Hive h, int build) {
		switch (build) {
		case Hive.MINING: h.mining = true; break;
		case Hive.REFINING: h.refining = true; break;
		case Hive.FORGE:
			h.forge = true;
			h.forgeBuilding = SwarmFit.DAYS_FORGE;
			break;
		case Hive.FUELPLANT:
			h.fuelPlant = true;
			h.fuelPlantBuilding = SwarmFit.DAYS_FUELPLANT;
			break;
		case Hive.GROUND_DEF: h.groundDef = true; break;
		case Hive.BATTERIES:
			h.batteries = true;
			h.groundDef = false;
			break;
		case Hive.ORBITAL: h.orbitalWorks = true; break;
		default: break;
		}
	}

	/** maintainNanoforges: the pristine relic on the first system's forge, a corrupted one on others by forgeNanoforgeChance. */
	static void nanoforges(State s, SwarmKnobs k) {
		for (Hive h : s.hives) {
			if (h.dead || !h.forgeBuilt() || h.nanoRolled) continue;
			h.nanoRolled = true;
			if (!s.swarm.pristinePlaced) {
				s.swarm.pristinePlaced = true;
				h.nanoUnits = 3;
				s.log("Relic: pristine_nanoforge at " + h.name);
			} else if (s.rng.nextFloat() < k.nanoChance) {
				h.nanoUnits = 1;
				s.log("Relic: corrupted_nanoforge at " + h.name);
			}
		}
	}

	/** maintainMilitaryTier: a Swarm Bastion, then Swarm Command, from fleet points the garrison does not need. */
	static void militaryTier(State s, SwarmKnobs k, Hive h, float incomePerMonth) {
		if (!k.militaryTier || !h.nexus || h.tierPending > 0 || h.tier >= 2) return;
		boolean command = h.tier == 1;
		if (!command) {
			if (h.industries() >= SwarmFit.maxIndustries(h.size)) return;
			if (wanted(s, k, SUPPLIES) && !h.forge) return;
			if (wanted(s, k, FUEL) && !h.fuelPlant) return;
		}
		float held = held(s, h);
		if (held < want(s, k, h)) return;
		int base = baseReserve(h);
		float extra = rowsFP(s, h, command ? 2 * base : base, base);
		float upkeepMonth = HiveRules.upkeepPerDay(held + extra, k.upkeepPerMonth) * 30f;
		if (incomePerMonth < upkeepMonth) return;
		float price = command ? SwarmFit.COMMAND_FP : SwarmFit.BASTION_FP;
		float months = SwarmFit.BASTION_DAYS / 30f;
		if (poolable(s, k, h) < price + extra + upkeepMonth * months) return;
		if (!pool(s, k, h, price)) return;
		h.bank -= price;
		h.tierPending = h.tier + 1;
		h.tierDays = SwarmFit.BASTION_DAYS;
		s.count("swarmFPOnStructures", price);
		s.log("Hive planner: Swarm " + (command ? "Command" : "Bastion") + " at " + h.name + " for " + (int) price + " FP");
	}

	/** The tick's planner pass (maintainHiveEconomy): every colony plans one build, the military tier after it. */
	static void tick(State s, SwarmKnobs k) {
		List<Hive> live = s.liveHives();
		float out = 0f, best = 0f, hiveDraw = 0f;
		for (Hive h : live) {
			out += h.forgeUnits();
			best = Math.max(best, h.forgeUnits());
		}
		for (Hive h : live) if (h.core && h.coreDown <= 0f && h.nexusUp()) hiveDraw += Math.min(best, h.size);
		for (Hive h : live) {
			plan(s, k, h);
			if (h.waiting != Hive.NONE) continue;
			float draw = h.core && h.coreDown <= 0f && h.nexusUp() ? Math.min(best, h.size) : 0f;
			militaryTier(s, k, h, HiveRules.fabricationRatePerDay(draw, out, hiveDraw, k.fpUnit) * 30f);
		}
		nanoforges(s, k);
	}
}
