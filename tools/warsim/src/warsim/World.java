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
	/** True once the faction has a reserve here (mobilised). */
	public boolean hasReserve;
	public final float[] stock = new float[4];
	public final float[] accrualPer30 = new float[4];
	/** For a forward base, the hive system it was founded toward. */
	public StarSys facesHive;
	public Front front;
	public int foundedDay;
	public boolean lost;

	@Override public String toString() { return name + " (" + faction + ", " + size + ")"; }
}
