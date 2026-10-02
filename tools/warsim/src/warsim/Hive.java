package warsim;

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

	@Override public String toString() { return name + " (" + size + ")"; }
}
