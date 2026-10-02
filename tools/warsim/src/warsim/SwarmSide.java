package warsim;

/** The swarm: economy, garrisons, stance, spread and strikes. Placeholder until built. */
public final class SwarmSide implements Side {
	@Override public void init(State s) {}
	@Override public void daily(State s) {}
	@Override public void arrive(State s, Parcel p) { p.done = true; }
}
