package warsim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The whole war at one moment, and the only place worlds are founded, grown or destroyed,
 * so the score (docs/war-sim.md 7) is counted in one place.
 */
public final class State {
	/** IncursionManager's EST_LY_PER_DAY: every fleet's speed in hyperspace. */
	public static final float LY_PER_DAY = 0.5f;

	public Knobs knobs;
	public Random rng;
	/** Absolute game day (ThreatReach.today()), and the day the run began. */
	public int day, startDay;

	public final Map<String, StarSys> systems = new LinkedHashMap<String, StarSys>();
	public final List<Hive> hives = new ArrayList<Hive>();
	public final List<World> worlds = new ArrayList<World>();
	public final Map<String, Faction> factions = new LinkedHashMap<String, Faction>();
	public final List<Parcel> parcels = new ArrayList<Parcel>();
	public final Swarm swarm = new Swarm();
	/** The start dump as read (ThreatSimDump), for what Start does not load into fields: knowledge, strategy, war. */
	public Map<String, Object> dump = new LinkedHashMap<String, Object>();

	/** Cumulative counters, by name; every one becomes a column of the monthly table. */
	public final Map<String, Double> counters = new TreeMap<String, Double>();
	/** Score weights: reaching size s scores s^sizeExponent. */
	public float sizeExponent = 1f;
	public boolean verbose;
	private int nextParcel = 1, nextId = 1;

	// human side
	/** Hive systems the sector has found (ThreatHiveIntel.sectorKnows): nothing is planned against the rest. */
	public final java.util.Set<StarSys> foundHiveSystems = new java.util.LinkedHashSet<StarSys>();
	/** Round 33: ThreatScouts.swept - the day a sector party last swept each system (warsim_scoutRoutes). */
	public final Map<StarSys, Integer> swept = new java.util.HashMap<StarSys, Integer>();
	/** HumanStance.faced for the day: the faction each hive system would strike first, cached by facedDay. */
	public final Map<StarSys, String> faced = new java.util.HashMap<StarSys, String>();
	public int facedDay = Integer.MIN_VALUE;
	/**
	 * Swarm bounties running, by hive system, to the day each ends (ThreatSwarmBountyIntel: posted when a
	 * siege is outweighed in orbit, one a system, swarmBountyDays long). Hunting forces are raised only
	 * against these (ThreatSoftening.tick).
	 */
	public final java.util.Map<StarSys, Integer> bounties = new java.util.LinkedHashMap<StarSys, Integer>();
	/** The day the last siege was launched at a hive (ThreatIncData.lastPurgeTimes): the siege cooldown's clock. Looked up, never iterated. */
	public final java.util.Map<Hive, Integer> lastSiegeDay = new java.util.HashMap<Hive, Integer>();

	// ---- time and travel ----

	public int month() { return (day - startDay) / 30; }

	public static int travelDays(float ly) { return (int) Math.ceil(ly / LY_PER_DAY); }

	/** Launch a parcel from `from` to `to`, arriving after the passage (plus any delay in days). */
	public Parcel send(String owner, Parcel.Kind kind, StarSys from, StarSys to, float fp, int delayDays) {
		Parcel p = new Parcel();
		p.id = nextParcel++;
		p.owner = owner;
		p.kind = kind;
		p.from = from;
		p.to = to;
		p.fp = fp;
		p.fp0 = fp;
		p.departDay = day + delayDays;
		p.arriveDay = p.departDay + travelDays(from.ly(to));
		parcels.add(p);
		return p;
	}

	// ---- lookups ----

	public List<Hive> hivesIn(StarSys sys) {
		List<Hive> out = new ArrayList<Hive>();
		for (Hive h : hives) if (!h.dead && h.sys == sys) out.add(h);
		return out;
	}

	/** HumanPlanner.size's sizing loop by its input key - every figure the loop reads, so it holds for the run. */
	public final Map<String, float[]> sizeMemo = new java.util.HashMap<String, float[]>();

	/** Whether a live hive stands in the system (hivesIn(sys).isEmpty() without the list). */
	public boolean hasHive(StarSys sys) {
		for (Hive h : hives) if (!h.dead && h.sys == sys) return true;
		return false;
	}

	public List<Hive> liveHives() {
		List<Hive> out = new ArrayList<Hive>();
		for (Hive h : hives) if (!h.dead) out.add(h);
		return out;
	}

	public List<World> liveWorlds() {
		List<World> out = new ArrayList<World>();
		for (World w : worlds) if (!w.lost) out.add(w);
		return out;
	}

	public List<World> worldsOf(String faction) {
		List<World> out = new ArrayList<World>();
		for (World w : worlds) if (!w.lost && w.faction.equals(faction)) out.add(w);
		return out;
	}

	public Hive hive(String id) {
		for (Hive h : hives) if (h.id.equals(id)) return h;
		return null;
	}

	public World world(String id) {
		for (World w : worlds) if (w.id.equals(id)) return w;
		return null;
	}

	public Faction faction(String id) {
		Faction f = factions.get(id);
		if (f == null) {
			f = new Faction();
			f.id = id;
			factions.put(id, f);
		}
		return f;
	}

	/** FP of parcels on station in a system: the Threat's (threat = true) or everyone else's. */
	public float holdingFP(StarSys sys, boolean threat) {
		float fp = 0f;
		for (Parcel p : parcels) if (!p.done && p.holding && p.to == sys && p.threat() == threat) fp += p.fp;
		return fp;
	}

	/** Garrison FP of every live hive in a system. */
	public float garrisonFP(StarSys sys) {
		float fp = 0f;
		for (Hive h : hives) if (!h.dead && h.sys == sys) fp += h.garrisonFP;
		return fp;
	}

	// ---- events: the score is counted here ----

	public void count(String name, double n) {
		Double v = counters.get(name);
		counters.put(name, (v == null ? 0d : v) + n);
	}

	public double counter(String name) {
		Double v = counters.get(name);
		return v == null ? 0d : v;
	}

	public double sizeWeight(int size) { return Math.pow(Math.max(1, size), sizeExponent); }

	public Hive foundHive(StarSys sys, String name, int size) {
		Hive h = new Hive();
		h.id = "hive" + (nextId++);
		h.name = name != null ? name : sys.name + " " + h.id;
		h.sys = sys;
		h.size = size;
		h.core = true;
		h.nexus = true;
		h.foundedDay = day;
		hives.add(h);
		count("hivesFounded", 1);
		count("threatSpread", sizeWeight(size));
		log("Colony founded: " + h);
		return h;
	}

	public void growHive(Hive h) {
		h.size++;
		h.growthDays = 0f;
		count("hiveLevels", 1);
		count("threatSpread", sizeWeight(h.size));
	}

	public void shrinkHive(Hive h) {
		if (h.size > 1) h.size--;
		count("hiveLevelsLost", 1);
	}

	/** A hive eradicated by a human faction's ground victory. */
	public void killHive(Hive h, String byFaction) {
		if (h.dead) return;
		h.dead = true;
		count("hivesKilled", 1);
		count("hiveSizeKilled", sizeWeight(h.size));
		log("Colony eradicated: " + h + " by " + byFaction);
	}

	public World foundForwardBase(String faction, StarSys sys, StarSys facesHive) {
		World w = new World();
		w.id = "fb" + (nextId++);
		w.name = sys.name + " Forward Base";
		w.faction = faction;
		w.sys = sys;
		w.size = 1;
		w.forwardBase = true;
		w.base = true;
		w.hasReserve = true;
		w.facesHive = facesHive;
		w.foundedDay = day;
		worlds.add(w);
		count("basesFounded", 1);
		log("Frontline: " + faction + " founded " + w.name + (facesHive != null ? " toward " + facesHive : ""));
		return w;
	}

	/** A human world gone: destroyed or taken by the Threat (byThreat), or given up. */
	public void loseWorld(World w, boolean byThreat, String why) {
		if (w.lost) return;
		w.lost = true;
		if (w.forwardBase) count(byThreat ? "basesDestroyed" : "basesAbandoned", 1);
		else count(byThreat ? "worldsLost" : "worldsAbandoned", 1);
		log((w.forwardBase ? "Frontline: " + w.faction + " dismantled " : "Colony lost: ") + w.name + " (" + why + ")");
	}

	public void log(String line) {
		if (verbose) System.out.println("d" + (day - startDay) + " " + line);
	}
}
