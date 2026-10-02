package warsim;

/**
 * One side's AI and economy. daily() runs once a game day, the swarm's first; a side keeps
 * its own slower clocks (5, 7, 30 days) inside it, as the mod does.
 */
public interface Side {
	/** After the start state is loaded. */
	void init(State s);

	void daily(State s);

	/** One of this side's parcels has reached its destination. */
	void arrive(State s, Parcel p);
}
