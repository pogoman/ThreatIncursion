package warsim;

/** A ground front on a hive or a human world (ThreatGroundFronts.GroundFront). */
public final class Front {
	public String faction;
	public float marines;
	public float armaments;
	public String state = "foothold";
	public int strataHeld;
	public int openedDay;
	public float pushDays;

	// ---- the human side's own ----

	/** Days dug in (entrenchment), days of consolidation left, the counter-attack clock. */
	public float entrenchDays, checkpointLeft, counterClock;
	/** Pushing (true) or dug in: decided at landing and kept (ThreatGroundFronts' stance). */
	public boolean pushing;
	/** True once the human side has set the stance of a front it found in the start state. */
	public boolean adopted;
	/** The day the last reinforcement was sent for. */
	public int lastRunDay = Integer.MIN_VALUE / 2;
}
