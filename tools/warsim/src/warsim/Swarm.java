package warsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The swarm as a whole (ThreatFuel's two stocks, ThreatStance). The swarm side owns this class. */
public final class Swarm {
	public static final int EXPAND = 0, PRESS = 1, CONSOLIDATE = 2;
	public static final int FUEL = 0, SUPPLIES = 1;
	public float fuel, supplies;
	public int stance = EXPAND;
	/** Posture per hive system id: pressure and mode (QUIET 0, WATCHFUL 1, THREATENED 2, BESIEGED 3). */
	public final Map<String, float[]> posture = new LinkedHashMap<String, float[]>();

	// ---- the swarm side's own (docs/war-sim-swarm.md) ----

	/** A system the swarm has claimed and not yet sent its wave to (ThreatIncData.STAGE_SEEDED). */
	public static final class Claim {
		public StarSys sys;
		public int day;
		/** The war's opening: the chain out of the Abyss, free. */
		public boolean bootstrap;
	}

	public final List<Claim> claims = new ArrayList<Claim>();
	/**
	 * The system the opening chain lands in, if the start state names it; else SwarmOps picks
	 * one by IncursionManager.pickOGSystem's rule. Start may set it from the dump.
	 */
	public String ogSystemId;
	public boolean pristinePlaced;

	/** Posture's fuller reading per hive system (ThreatPosture.state). */
	public static final class Post {
		public float pressure, want, need, held, bank;
		public int mode;
		public float surplusSince = Float.NaN;
		public int day = Integer.MIN_VALUE;
		public boolean attacked;
	}

	public final Map<String, Post> post = new HashMap<String, Post>();
	/** Garrison FP lost per hive system, decaying: {fp, day}. */
	public final Map<String, float[]> losses = new HashMap<String, float[]>();
	public float appetite = -1f;
	public int nextPostureDay = Integer.MIN_VALUE;
	public float nextTickDay = Float.NaN;

	// stance (ThreatStance)
	public int stanceSince = Integer.MIN_VALUE;
	public float lastPressure = -1f;
	/** The attrition ledger: Threat FP lost, enemy FP sunk, the day it was read. */
	public float trendLost, trendKilled;
	public int trendDay;
	/** Live hive count per tick, newest last, for the stance's 90-day window. */
	public final List<int[]> hiveHistory = new ArrayList<int[]>();
	/** The world the stance picked to press, or null. */
	public String pressTarget;

	// stocks (ThreatFuel's trailing demand), indexed FUEL, SUPPLIES
	public final float[] demand = new float[2], demandDays = new float[2];
	public final int[] answeredDay = { Integer.MIN_VALUE, Integer.MIN_VALUE };
	public int demandSince = Integer.MIN_VALUE;
	public int suppliesShortDay = Integer.MIN_VALUE;
	/** Supplies a month the last feed left for trips (ThreatColonyUpkeep.spareSupplies), and what fleets away burn. */
	public float spare, awayPerMonth, awayOwed;

	/** Mean FP a swarm of a spec came out at, by "fabricators:tier" (ThreatColonyManager.fabCosts): {sum, count}. */
	public final Map<String, float[]> learned = new HashMap<String, float[]>();

	/** Resource-bearing planets left to claim in a held system (tryExpandInSystem). */
	public final Map<String, Integer> expandable = new HashMap<String, Integer>();

	// fog (ThreatSwarmIntel, simplified): world id -> {day seen, defence seen}
	public final Map<String, float[]> seen = new HashMap<String, float[]>();
	/** Worlds a strike is out against (one strike a world). */
	public final Set<String> struck = new HashSet<String>();
	/** Human parcels' FP as last seen holding in a hive system, for the kills ledger. */
	public final Map<Integer, Float> hostileFP = new HashMap<Integer, Float>();
}
