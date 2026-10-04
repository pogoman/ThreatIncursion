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
	/** ThreatSwarmIntel.Contact: an attack seen bound for a hive system, by parcel id. */
	public static final class Contact {
		public StarSys sys;
		public int day;
		public float fp;
	}
	public final Map<Integer, Contact> contacts = new HashMap<Integer, Contact>();
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
	/** Send -> the day its hold was last booked as demand (ThreatFuel.KEY_HELD, SwarmEconomy.bookHold). */
	public final java.util.Map<String, Integer> heldBooked = new java.util.HashMap<String, Integer>();
	public int suppliesShortDay = Integer.MIN_VALUE;
	/** Supplies a month the last feed left for trips (ThreatColonyUpkeep.spareSupplies), and what fleets away burn. */
	public float spare, awayPerMonth, awayOwed;

	/** Mean FP a swarm of a spec came out at, by "fabricators:tier" (ThreatColonyManager.fabCosts): {sum, count}. */
	public final Map<String, float[]> learned = new HashMap<String, float[]>();

	/** Resource-bearing planets left to claim in a held system (tryExpandInSystem). */
	public final Map<String, Integer> expandable = new HashMap<String, Integer>();

	// fog (ThreatSwarmIntel, simplified): world id -> {day seen, defence seen}
	public final Map<String, float[]> seen = new HashMap<String, float[]>();
	/**
	 * Staged credibility (round 30, threatinc_postureStagedHalfLifeDays): faction|system -> {first day its staging was read
	 * there, last day it was, last day one of its attacks was seen bound there}.
	 */
	public final Map<String, int[]> staging = new HashMap<String, int[]>();
	/** A Threat front on a human world (ThreatGroundFronts, a Threat-owned GroundFront), as SwarmOps.landing models it. */
	public static final class Landing {
		public float troops;
		public int landedDay;
		/** The day the front ends, and how: the garrison overruns it, or it takes the last district. */
		public int endDay = Integer.MIN_VALUE;
		public boolean falls;
		/** Whether the strike gate passes the invaded world this tick (SwarmFit.INVADED_GATE_SHARE). */
		public boolean gateOpen;
		/** warsim_coloniesFall: the front engine's state (as Front holds it for a human front on a hive). */
		public boolean engine, pushing;
		public float entrenchDays, pushDays, checkpointLeft, counterClock;
		public int strataHeld;
		/** The front holds (frontCanHold): its key structures are down, and the garrison with them (SwarmOps.suppressed). */
		public boolean holding;
		/** warsim_wearClock: the key structures' disruption clock in days (ThreatGroundFronts.suppress), -1 until set. */
		public float wear = -1f;
		/** warsim_wearClock: the front's state with the game's hysteresis - 2 holding, 1 grinding, 0 foothold. */
		public int state = 2;
		/** warsim_veterancy: the front's level (ThreatMarineXP.frontLevel): npcLandingVeterancy at landing, raised by each counter-attack. */
		public float level;
		/** Counter-attacks the colony has made on the front (the first comes later: SwarmOps.threatFrontDay). */
		public int counterAttacks;
	}

	/** ThreatAlarm's grudge per faction: raised by strata taken and hives eradicated, fading by the month. */
	public final Map<String, Float> grudge = new HashMap<String, Float>();
	/** Threat fronts by world id. */
	public final Map<String, Landing> landings = new LinkedHashMap<String, Landing>();
	/** Worlds a strike is out against (one strike a world). */
	public final Set<String> struck = new HashSet<String>();
	/** Human parcels' FP as last seen holding in a hive system, for the kills ledger. */
	public final Map<Integer, Float> hostileFP = new HashMap<Integer, Float>();
}
