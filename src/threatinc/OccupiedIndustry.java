package threatinc;

/**
 * An industry plugin of the occupation (the Occupied* classes): its vanilla building state, reachable
 * without going through the overridden {@code isBuilding} - which reads {@link ThreatOccupation#consuming},
 * which reads {@link ThreatOccupation#producers}, which must not read {@code isBuilding} back (fix 4,
 * 2026-10-28: that cycle, with the StackOverflowError swallowed, hung hw306a and hw308b for good).
 */
public interface OccupiedIndustry {
	/** Vanilla's building flag: the industry is under construction, the swarm's consumption aside. */
	boolean underConstruction();
}
