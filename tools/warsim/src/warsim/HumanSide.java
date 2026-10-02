package warsim;

/** The human factions: pools, mobilisation, the council or the planner, sieges, forward bases. Placeholder until built. */
public final class HumanSide implements Side {
	@Override public void init(State s) {}
	@Override public void daily(State s) {}
	@Override public void arrive(State s, Parcel p) { p.done = true; }
}
