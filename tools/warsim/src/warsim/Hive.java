package warsim;

import java.util.ArrayList;
import java.util.List;

/**
 * A Threat colony. Fields mirror the dump (ThreatSimDump.hives); the swarm side owns this
 * class and adds what its rules need.
 */
public final class Hive {
	public String id;
	public String name;
	public StarSys sys;
	public int size;
	public float growthDays;
	/** FP bank (may go negative). */
	public float bank;
	/** Defense Swarms on station, in FP and in fleets. */
	public float garrisonFP;
	public int garrisonFleets;
	public float wantFP, needFP;
	/** Organs: present, and days still disrupted (0 = working). */
	public boolean forge, fuelPlant, nexus, core;
	public float forgeDown, fuelPlantDown, nexusDown, coreDown;
	/** Military tier (SwarmBastion.tier): 0, 1 or 2. */
	public int tier;
	/** Fortification condition 0-1 and the siege clock in days. */
	public float fortification = 1f;
	public float siegeClock;
	public float defence;
	public Front front;
	public int foundedDay;
	public boolean dead;

	public boolean nexusUp() { return nexus && nexusDown <= 0f; }
	public boolean forgeUp() { return forge && forgeDown <= 0f; }

	// ---- the swarm side's own (docs/war-sim-swarm.md) ----

	/** Builds the planner can leave waiting on the supplies stock (waiting). */
	public static final int NONE = 0, MINING = 1, REFINING = 2, FORGE = 3, FUELPLANT = 4, GROUND_DEF = 5,
			BATTERIES = 6, ORBITAL = 7;

	/** The garrison fleet by fleet; garrisonFP and garrisonFleets are its sum and count (SwarmEconomy.sync). */
	public final List<Float> swarms = new ArrayList<Float>();
	public boolean mining, refining, groundDef, batteries, orbitalWorks;
	/** Days until a bought forge or fuel plant produces (vanilla's build time). */
	public float forgeBuilding, fuelPlantBuilding;
	/** Hull units a nanoforge adds to the forge: 3 pristine, 1 corrupted. */
	public int nanoUnits;
	public boolean nanoRolled;
	/** The build chosen and not yet paid, its price in supplies, and the stock it answers (-1 none, 0 fuel, 1 supplies). */
	public int waiting;
	public float waitingCost;
	public int waitingAnswers = -1;
	/** Share of its upkeep paid at the last feed (ThreatColonyUpkeep.MEM_FED). */
	public float fed = 1f;
	public int lastReceivedDay = Integer.MIN_VALUE;
	/** The day it first sent a Seeding Swarm, for the timing table. */
	public int firstWaveDay = Integer.MIN_VALUE;
	/** A Swarm Bastion or Command paid for and building: the tier it stands at, and the days left. */
	public int tierPending;
	public float tierDays;

	/**
	 * The size the fuel plant's output was last read at: its volatiles reach it a tick late
	 * (probe dumps: fuelPerMonth still 1500 ten days into size 4, suppliesPerMonth up at once),
	 * so a level gained shows in the fuel stock from the next 30-day tick. -1: unread, its size.
	 */
	public int outputSize = -1;

	public boolean forgeBuilt() { return forge && forgeBuilding <= 0f; }

	/** Hull units the forge makes: vanilla's size - 2, at least 1, plus its nanoforge; 0 while down or building. */
	public int forgeUnits() {
		if (!forgeUp() || forgeBuilding > 0f) return 0;
		return Math.max(1, size - 2) + nanoUnits;
	}

	/** Fuel units the plant makes: size - 2 as last read; 0 while down or building. */
	public int fuelUnits() {
		if (!fuelPlant || fuelPlantDown > 0f || fuelPlantBuilding > 0f) return 0;
		return Math.max(0, (outputSize < 0 ? size : Math.min(size, outputSize)) - 2);
	}

	/** Industry slots in use (the Bastion takes one). */
	public int industries() {
		return (mining ? 1 : 0) + (refining ? 1 : 0) + (forge ? 1 : 0) + (fuelPlant ? 1 : 0)
				+ (tier > 0 || tierPending > 0 ? 1 : 0);
	}

	public float swarmSum() {
		float fp = 0f;
		for (Float f : swarms) fp += f;
		return fp;
	}

	/** After the swarm side changes the fleet list. */
	public void book() {
		garrisonFP = swarmSum();
		garrisonFleets = swarms.size();
	}

	@Override public String toString() { return name + " (" + size + ")"; }
}
