package warsim;

/** A human faction at war (ThreatWarState.FactionWar and its council). The human side owns this class. */
public final class Faction {
	public String id;
	public boolean mobilised;
	public int mobilisedDay = -1;
	public int strikesSuffered;
	public int lastStruckDay = -1;
	/** HOLD, STARVE, ROLLBACK, DECAPITATE, or "" under the planner. */
	public String strategy = "";

	@Override public String toString() { return id; }
}
