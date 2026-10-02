package warsim;

/**
 * A fleet as the simulator sees it: FP and cargo travelling between two systems, or holding
 * at its destination. State.send() launches one; when it arrives, State hands it to its
 * owner's Side.arrive(), which may hold it (holding = true) or end it (done = true).
 */
public final class Parcel {
	public static final String THREAT = "threat";

	/** What it is for. The owning side reads these; the other side may read kind, fp and place. */
	public enum Kind {
		// the swarm's
		REINFORCEMENT, STRIKE, WAVE, SWARM_SCOUT, RAIDER,
		// the humans'
		SIEGE, SATURATION, HUNT, SQUADRON, SCOUT, CONVOY, MUSTER, FOUNDING, RELIEF, GUARD
	}

	public int id;
	/** A faction id, or THREAT. */
	public String owner;
	public Kind kind;
	public StarSys from, to;
	/** The hive or world id aimed at, if any. */
	public String targetId;
	/** For a force staged at a base (MUSTER): the hive system it is staged against. */
	public StarSys against;
	public float fp;
	/** FP at departure, for loss shares. */
	public float fp0;
	public float fuel, supplies, marines, armaments;
	public int departDay, arriveDay;
	public boolean arrived;
	/** On station at `to` (a siege in orbit, a force at its muster). */
	public boolean holding;
	public boolean done;
	/** The owning side's own record (a play, a prong, a strike plan). */
	public Object order;

	public boolean threat() { return THREAT.equals(owner); }

	@Override public String toString() { return owner + " " + kind + " " + (int) fp + " FP -> " + to; }
}
