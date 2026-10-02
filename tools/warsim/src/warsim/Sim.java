package warsim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** One run: load the start state, step the days, record a row a month. */
public final class Sim {

	/** A run's monthly table: one map of column -> value per month, month 0 first. */
	public static final class Result {
		public long seed;
		public final List<Map<String, Double>> months = new ArrayList<Map<String, Double>>();

		public Map<String, Double> last() { return months.get(months.size() - 1); }

		/** The row at a month, or the last row if the run ended sooner. */
		public Map<String, Double> at(int month) { return months.get(Math.min(month, months.size() - 1)); }
	}

	public static Result run(Start start, Knobs knobs, long seed, int months, float sizeExponent, boolean verbose) {
		State s = new State();
		s.knobs = knobs;
		s.rng = new Random(seed);
		s.sizeExponent = sizeExponent;
		s.verbose = verbose;
		start.fill(s);
		Side swarm = new SwarmSide();
		Side humans = new HumanSide();
		swarm.init(s);
		humans.init(s);

		Result r = new Result();
		r.seed = seed;
		Map<StarSys, int[]> hands = new java.util.IdentityHashMap<StarSys, int[]>();
		hands(s, hands, true);
		r.months.add(row(s));
		int end = s.startDay + months * 30;
		while (s.day < end) {
			s.day++;
			swarm.daily(s);
			humans.daily(s);
			arrivals(s, swarm, humans);
			hands(s, hands, false);
			if ((s.day - s.startDay) % 30 == 0) r.months.add(row(s));
		}
		return r;
	}

	/**
	 * docs/war-sim.md 7, `contested`: the systems that changed hands more than once. Per system and side (the
	 * swarm's hives, the humans' worlds and bases), a presence lost is one change and one won back after a loss
	 * another; a system at its second change is counted once. {swarm there, humans there, swarm lost, humans lost, changes}
	 */
	private static void hands(State s, Map<StarSys, int[]> hands, boolean first) {
		java.util.Set<StarSys> swarm = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<StarSys, Boolean>());
		java.util.Set<StarSys> humans = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<StarSys, Boolean>());
		for (Hive h : s.hives) if (!h.dead) swarm.add(h.sys);
		for (World w : s.worlds) if (!w.lost) humans.add(w.sys);
		for (StarSys sys : swarm) if (!hands.containsKey(sys)) hands.put(sys, new int[5]);
		for (StarSys sys : humans) if (!hands.containsKey(sys)) hands.put(sys, new int[5]);
		for (Map.Entry<StarSys, int[]> e : hands.entrySet()) {
			int[] h = e.getValue();
			boolean[] now = { swarm.contains(e.getKey()), humans.contains(e.getKey()) };
			for (int side = 0; side < 2 && !first; side++) {
				boolean was = h[side] != 0;
				int changes = h[4];
				if (was && !now[side]) { h[2 + side] = 1; h[4]++; }
				else if (!was && now[side] && h[2 + side] != 0) h[4]++;
				if (changes < 2 && h[4] >= 2) s.count("contested", 1);
			}
			h[0] = now[0] ? 1 : 0;
			h[1] = now[1] ? 1 : 0;
		}
	}

	private static void arrivals(State s, Side swarm, Side humans) {
		// a side may send new parcels while handling an arrival: walk a copy
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (p.done || p.arrived || p.arriveDay > s.day) continue;
			p.arrived = true;
			(p.threat() ? swarm : humans).arrive(s, p);
		}
		for (Iterator<Parcel> it = s.parcels.iterator(); it.hasNext();) if (it.next().done) it.remove();
	}

	/** The state as one row: what stands now, then every cumulative counter. */
	static Map<String, Double> row(State s) {
		Map<String, Double> m = new LinkedHashMap<String, Double>();
		double hives = 0, hiveSize = 0, garrison = 0, bank = 0, want = 0, fuelUnits = 0, fuelPlants = 0, forges = 0;
		for (Hive h : s.hives) {
			if (h.dead) continue;
			hives++;
			fuelUnits += h.fuelUnits();
			if (h.fuelPlant) fuelPlants++;
			if (h.forge) forges++;
			hiveSize += h.size;
			garrison += h.garrisonFP;
			want += h.wantFP;
			bank += h.bank;
		}
		double worlds = 0, worldSize = 0, bases = 0, marines = 0;
		for (World w : s.worlds) {
			if (w.lost) continue;
			// the defenders' reserve: the stockpiled marines of every colony and base whose faction keeps one (the dumps' stock)
			if (w.hasReserve) marines += w.stock[World.MARINES];
			if (w.forwardBase) bases++;
			else { worlds++; worldSize += w.size; }
		}
		double threatMobile = 0, humanMobile = 0;
		for (Parcel p : s.parcels) {
			if (p.done) continue;
			if (p.threat()) threatMobile += p.fp; else humanMobile += p.fp;
		}
		double mobilised = 0;
		for (Faction f : s.factions.values()) if (f.mobilised) mobilised++;
		m.put("month", (double) s.month());
		m.put("hives", hives);
		m.put("hiveSize", hiveSize);
		m.put("meanHiveSize", hives > 0 ? hiveSize / hives : 0);
		m.put("garrisonFP", garrison);
		// the game's monthly "Posture sector: held Xk want Yk": the hives' wants summed, and what they hold of it
		m.put("wantFP", want);
		m.put("heldOfWant", want > 0 ? garrison / want : 0);
		m.put("threatFleetFP", threatMobile);
		m.put("bank", bank);
		m.put("swarmFuel", (double) s.swarm.fuel);
		// the dump's swarm.fuelPerMonth at hiveSurplusMult 1: the fuel plants' output a month
		m.put("swarmFuelPerMonth", fuelUnits * SwarmFit.FUEL_UNIT);
		m.put("fuelPlants", fuelPlants);
		m.put("forges", forges);
		m.put("swarmSupplies", (double) s.swarm.supplies);
		m.put("stance", (double) s.swarm.stance);
		m.put("worlds", worlds);
		m.put("worldSize", worldSize);
		m.put("basesHeld", bases);
		m.put("humanMarines", marines);
		m.put("humanFleetFP", humanMobile);
		m.put("mobilised", mobilised);
		// the counters every run reports, present even when zero
		for (String c : new String[] { "threatSpread", "hivesFounded", "hiveLevels", "hivesKilled", "worldsLost",
				"basesFounded", "basesDestroyed", "basesAbandoned", "siegesSailed", "siegesLanded", "strikesLaunched", "contested" })
			m.put(c, s.counter(c));
		for (Map.Entry<String, Double> e : s.counters.entrySet()) m.put(e.getKey(), e.getValue());
		// the two sides' scores (docs/war-sim.md 7); kills are weighed by the caller
		m.put("humanSpread", bases);
		m.put("threatKillCount", s.counter("worldsLost") + s.counter("basesDestroyed"));
		m.put("humanKillCount", s.counter("hivesKilled"));
		return m;
	}
}
