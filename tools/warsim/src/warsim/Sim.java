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
		r.months.add(row(s));
		int end = s.startDay + months * 30;
		while (s.day < end) {
			s.day++;
			swarm.daily(s);
			humans.daily(s);
			arrivals(s, swarm, humans);
			if ((s.day - s.startDay) % 30 == 0) r.months.add(row(s));
		}
		return r;
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
		double hives = 0, hiveSize = 0, garrison = 0, bank = 0;
		for (Hive h : s.hives) {
			if (h.dead) continue;
			hives++;
			hiveSize += h.size;
			garrison += h.garrisonFP;
			bank += h.bank;
		}
		double worlds = 0, worldSize = 0, bases = 0;
		for (World w : s.worlds) {
			if (w.lost) continue;
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
		m.put("threatFleetFP", threatMobile);
		m.put("bank", bank);
		m.put("swarmFuel", (double) s.swarm.fuel);
		m.put("swarmSupplies", (double) s.swarm.supplies);
		m.put("stance", (double) s.swarm.stance);
		m.put("worlds", worlds);
		m.put("worldSize", worldSize);
		m.put("basesHeld", bases);
		m.put("humanFleetFP", humanMobile);
		m.put("mobilised", mobilised);
		// the counters every run reports, present even when zero
		for (String c : new String[] { "threatSpread", "hivesFounded", "hiveLevels", "hivesKilled", "worldsLost",
				"basesFounded", "basesDestroyed", "basesAbandoned", "siegesSailed", "siegesLanded", "strikesLaunched" })
			m.put(c, s.counter(c));
		for (Map.Entry<String, Double> e : s.counters.entrySet()) m.put(e.getKey(), e.getValue());
		// the two sides' scores (docs/war-sim.md 7); kills are weighed by the caller
		m.put("humanSpread", bases);
		m.put("threatKills", s.counter("worldsLost") + s.counter("basesDestroyed"));
		m.put("humanKills", s.counter("hivesKilled"));
		return m;
	}
}
