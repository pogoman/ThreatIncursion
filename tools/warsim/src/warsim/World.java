package warsim;

/**
 * A human world: a colony, or a forward base (a ThreatFrontlines link). Fields mirror the
 * dump (ThreatSimDump.worlds); the human side owns this class and adds what its rules need.
 */
public final class World {
	/** Index into stock and accrual, in ThreatReserves.COMMODITIES order. */
	public static final int MARINES = 0, ARMAMENTS = 1, FUEL = 2, SUPPLIES = 3;
	public static final String[] COMMODITIES = { "marines", "hand_weapons", "fuel", "supplies" };

	public String id;
	public String name;
	public String faction;
	public StarSys sys;
	public int size;
	public boolean forwardBase;
	/** A base fleets stage from (IncursionManager.isBase). */
	public boolean base;
	public float defence;
	/** The colony's garrison proper (ThreatGroundFronts.colonyGarrison): the dump's defence less its armed marines; -1 unknown. */
	public float garrison = -1f;
	/** What the strike gate reads (the dump's "gate": the system's fleets and the world's station, vanilla units); -1 in a dump older than round 23, where the ground figure stands in. */
	public float gate = -1f;
	/** True once the faction has a reserve here (mobilised). */
	public boolean hasReserve;
	public final float[] stock = new float[4];
	public final float[] accrualPer30 = new float[4];
	/** For a forward base, the hive system it was founded toward. */
	public StarSys facesHive;
	public Front front;
	public int foundedDay;
	public boolean lost;

	// ---- the human side's own ----

	/** IncursionManager.hasMilitary, when the dump says (HumanSide.load); null = unknown, HumanFit guesses. */
	public Boolean military;
	/** The largest months basis the depot has seen (ThreatReserves.noteBasis), per commodity. */
	public final float[] capSeen = new float[4];
	/**
	 * Fleet points guarding a forward base (ThreatFrontlines' garrison). The swarm side reads
	 * this as the base's defenders and takes its losses off it.
	 */
	public float guardFP;
	/**
	 * Round 20 (warsim_reliefGoesHome): the part of guardFP that came as relief against a reported strike
	 * (HumanBases.garrison's relief), what it arrived with, its deposit and the base it sailed from.
	 */
	public float reliefFP, reliefFP0, reliefDeposit;
	public World reliefHome;
	/** Days a front link has stood without the least garrison; days with no found hive in reach. */
	public int unguardedDays, idleDays;
	/** Healthy days toward the next size; days starved toward losing one (ThreatColonyUpkeep). */
	public float healthyDays, starveDays;
	/** The day a garrison was last asked for. */
	/** The day this base last sent a hunting force (ThreatSoftening.resting). */
	public int lastHuntDay = Integer.MIN_VALUE / 2;
	public int guardAskedDay = Integer.MIN_VALUE / 2;
	/** True once the accrual has been modelled or read (HumanPools.ensure). */
	public boolean pooled;
	/**
	 * Round 15 trial a (warsim_siegeReserve): supplies of this depot reserved today for a staging play's siege,
	 * which the forward-base line (links, garrison voyages) cannot spend; set daily by HumanCouncil.reserveSiege.
	 */
	public float siegeReserve;

	@Override public String toString() { return name + " (" + faction + ", " + size + ")"; }
}
