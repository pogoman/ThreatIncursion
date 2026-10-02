package warsim;

import java.util.LinkedHashMap;
import java.util.Map;

/** A human faction at war (ThreatWarState.FactionWar and its council). The human side owns this class. */
public final class Faction {
	public String id;
	public boolean mobilised;
	public int mobilisedDay = -1;
	/**
	 * Threat strikes this faction has detected. The swarm side raises it (and may set
	 * lastStrikeFrom, the system the strike sailed from); the first one mobilises the faction.
	 */
	public int strikesSuffered;
	public int lastStruckDay = -1;
	/** HOLD, STARVE, ROLLBACK, DECAPITATE, or "" under the planner. */
	public String strategy = "";

	// ---- the human side's own ----

	/** Where the last detected strike sailed from: the scouts' lead (ThreatHiveIntel). May be null. */
	public StarSys lastStrikeFrom;
	/** Strikes already answered (mobilisation, a scouting lead). */
	public int strikesSeen;
	/** ThreatIntel's reports, one per hive system this faction (or a partner) has seen. */
	public final Map<StarSys, HumanIntel.Report> reports = new LinkedHashMap<StarSys, HumanIntel.Report>();
	/** Clocks: the planner's last pass, the frontline's, the hunts', the scouts', the raids'. */
	public int lastPlanDay = Integer.MIN_VALUE / 2, lastFrontlineDay = Integer.MIN_VALUE / 2,
			lastHuntDay = Integer.MIN_VALUE / 2, lastScoutDay = Integer.MIN_VALUE / 2,
			lastRaidDay = Integer.MIN_VALUE / 2;
	/** ThreatAttackPlanner's news flag: swarms moved, a siege ended, a hunt thinned a garrison. */
	public boolean news;
	/** HumanStance: EXPAND, PRESS or CONSOLIDATE, since when, the last read, and the decayed exchange (FP lost, FP sunk). */
	public int stance, stanceSince = Integer.MIN_VALUE / 2, lastStanceDay = Integer.MIN_VALUE / 2, trendDay;
	public float trendLost, trendKilled;
	/** Timing marks, days since the run began (-1 = not yet): the calibration targets of docs/war-sim-humans.md. */
	/** HumanCouncil.Council, while a war council governs the faction. */
	public Object council;
	public int firstBaseDay = -1, firstSiegeDay = -1, firstLandingDay = -1, firstKillDay = -1;

	@Override public String toString() { return id; }
}
