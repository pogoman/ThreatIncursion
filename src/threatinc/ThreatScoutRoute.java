package threatinc;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The route a scouting party walks - one walker for the sector's parties
 * ({@link ThreatScouts}) and the swarm's ({@link ThreatSwarmScouts}), which
 * differ only in what they do at a stop (2026-09-24 review).
 *
 * <p>A party is sent to each stop in turn. Once it is in the system it looks
 * ({@link #onEnter}: reveal a hive, chart the system), stays scoutStayDays,
 * records the stay ({@link #onStay}) and moves on, skipping stops its side
 * has learned of meanwhile ({@link #knownStop}) and ones that no longer
 * exist. Off the end of its route, or told to by onEnter, it reports home
 * ({@link #homeOf}) and despawns there; with no home left it fades where it
 * is. A side builds its own candidate list for a new route; the nearest-first
 * order ({@link #nearestFirst}) and the stops other parties already hold
 * ({@link #taken}) are shared.
 *
 * <p>{@link Party} is the persisted record each side's Scout extends. Each
 * side holds one walker as a constant ({@code ROUTE}), the way
 * {@link ThreatGroundFronts.Theatre} is held.
 */
public abstract class ThreatScoutRoute<S extends ThreatScoutRoute.Party> {

	/**
	 * One party out on a route. Saves hold these by field name under each
	 * side's Scout class: do not rename them.
	 */
	public static abstract class Party {
		public CampaignFleetAPI fleet;
		public String homeMarketId;
		public List<String> route = new ArrayList<String>();
		public int leg;
		/** When the current leg's system was entered; 0 while in transit. */
		public long enteredTimestamp;
		public boolean returning;
		/** When the party was sent toward the current leg; 0 on saves before 2026-09-24. */
		public long legSince;
	}

	/** A star system by id, null for a null id: the direct lookup, this runs per party per poll. */
	public static StarSystemAPI systemById(String systemId) {
		return systemId == null ? null : Global.getSector().getStarSystem(systemId);
	}

	// ------------------------------------------------------------------
	// what a side decides
	// ------------------------------------------------------------------

	/** Every party of this side, out or returning: the persisted list. */
	protected abstract List<S> all();
	/** The party in a log line: "Scout of hegemony", "Scouting Swarm". */
	protected abstract String describe(S s);
	/** Whether the side already knows the system, so a party skips the stop. */
	protected abstract boolean knownStop(String systemId);
	/** What the party does on entering a stop; true = the sortie is over, report home. */
	protected abstract boolean onEnter(S s, StarSystemAPI system, long now);
	/** The party has stayed its days at a stop with nothing to report. */
	protected void onStay(S s, StarSystemAPI system, long now) {}
	/** What the party is doing in a system, for its assignment: "sweeping", "charting". */
	protected abstract String stayVerb();
	/** Where the party reports back to; null = nowhere left, it fades. */
	protected abstract MarketAPI homeOf(S s);
	/** The party is about to sail home. */
	protected void onReturn(S s) {}
	/** The return leg, for its assignment. */
	protected abstract String returnLabel(S s, MarketAPI home);

	// ------------------------------------------------------------------
	// the walk
	// ------------------------------------------------------------------

	/** One poll's step for a party: arrive, look, stay, move on or report home. */
	public void advance(S s) {
		CampaignFleetAPI fleet = s.fleet;
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
			all().remove(s);
			ThreatIncConfig.log(describe(s) + (s.returning ? " home" : " lost"));
			return;
		}
		if (s.returning) return; // GO_TO_LOCATION_AND_DESPAWN does the rest
		if (s.leg >= s.route.size()) {
			goHome(s);
			return;
		}
		StarSystemAPI target = systemById(s.route.get(s.leg));
		if (target == null) {
			nextLeg(s);
			return;
		}
		long now = Global.getSector().getClock().getTimestamp();
		if (fleet.getContainingLocation() != target) {
			// a stop the fleet cannot reach is given up, not waited on forever
			if (s.legSince == 0L) s.legSince = now;
			if (s.enteredTimestamp == 0L && Global.getSector().getClock().getElapsedDaysSince(s.legSince)
					>= ThreatIncConfig.scoutLegMaxDays()) {
				ThreatIncConfig.log(describe(s) + " gave up on " + target.getName());
				nextLeg(s);
			}
			return;
		}
		if (s.enteredTimestamp == 0L) {
			s.enteredTimestamp = now;
			if (onEnter(s, target, now)) {
				goHome(s);
				return;
			}
		}
		if (Global.getSector().getClock().getElapsedDaysSince(s.enteredTimestamp)
				< ThreatIncConfig.scoutStayDays()) return;
		onStay(s, target, now);
		nextLeg(s);
	}

	protected void nextLeg(S s) {
		s.leg++;
		// a stop the side learned of meanwhile is skipped
		while (s.leg < s.route.size() && knownStop(s.route.get(s.leg))) s.leg++;
		if (s.leg >= s.route.size()) {
			goHome(s);
			return;
		}
		StarSystemAPI next = systemById(s.route.get(s.leg));
		if (next == null) {
			nextLeg(s);
			return;
		}
		sendTo(s, next);
	}

	public void sendTo(S s, StarSystemAPI system) {
		s.enteredTimestamp = 0L;
		s.legSince = Global.getSector().getClock().getTimestamp();
		s.fleet.clearAssignments();
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, system.getCenter(), 1000f,
				"scouting the " + system.getNameWithLowercaseTypeShort());
		s.fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, system.getCenter(), 1000f,
				stayVerb() + " the " + system.getNameWithLowercaseTypeShort());
	}

	protected void goHome(S s) {
		s.returning = true;
		s.fleet.clearAssignments();
		MarketAPI home = homeOf(s);
		if (home == null || home.getPrimaryEntity() == null) {
			Misc.fadeAndExpire(s.fleet);
			return;
		}
		onReturn(s);
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
				returnLabel(s, home));
	}

	// ------------------------------------------------------------------
	// planning
	// ------------------------------------------------------------------

	/** The stops every party of this side still has ahead of it: no two sweep the same system. */
	public List<String> taken() {
		List<String> taken = new ArrayList<String>();
		for (S s : all()) {
			if (s.returning) continue;
			for (int i = s.leg; i < s.route.size(); i++) taken.add(s.route.get(i));
		}
		return taken;
	}

	/**
	 * Up to scoutStops of the candidates as a route, nearest-first from where
	 * the party starts, each leg measured from the stop before. Empties the list.
	 */
	public static List<String> nearestFirst(List<StarSystemAPI> candidates, Vector2f from) {
		List<String> route = new ArrayList<String>();
		Vector2f at = from;
		int stops = Math.max(1, ThreatIncConfig.scoutStops());
		while (!candidates.isEmpty() && route.size() < stops) {
			StarSystemAPI next = null;
			float bestDist = Float.MAX_VALUE;
			for (StarSystemAPI system : candidates) {
				float d = Misc.getDistanceLY(system.getLocation(), at);
				if (d < bestDist) {
					bestDist = d;
					next = system;
				}
			}
			candidates.remove(next);
			route.add(next.getId());
			at = next.getLocation();
		}
		return route;
	}
}
