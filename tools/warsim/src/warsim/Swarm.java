package warsim;

import java.util.LinkedHashMap;
import java.util.Map;

/** The swarm as a whole (ThreatFuel's two stocks, ThreatStance). The swarm side owns this class. */
public final class Swarm {
	public static final int EXPAND = 0, PRESS = 1, CONSOLIDATE = 2;
	public float fuel, supplies;
	public int stance = EXPAND;
	/** Posture per hive system id: pressure and mode (QUIET 0, WATCHFUL 1, THREATENED 2, BESIEGED 3). */
	public final Map<String, float[]> posture = new LinkedHashMap<String, float[]>();
}
