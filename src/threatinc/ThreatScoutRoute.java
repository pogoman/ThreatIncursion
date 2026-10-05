package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
		/**
		 * What it has seen and not yet brought home (2026-10-05, the user:
		 * "information travels by ship"): each side's own records, filed
		 * when the party is next in a friendly system ({@link #friendly},
		 * {@link #deliver}) and lost with it. Null on older saves.
		 */
		public List<Object> carried;
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
	/** Whether what the party carries is known to its side once it is in this system: a relay is in reach. */
	protected boolean friendly(S s, StarSystemAPI system) { return false; }
	/** The party is in a friendly system: one thing it carried is filed. */
	protected void deliver(S s, Object seen) {}

	/** The party did not come back: destroyed, or faded with no home left. */
	protected void onLost(S s) {}

	/** Takes what the party saw aboard; it is known when the party is next in a friendly system. */
	protected void carry(S s, Object seen) {
		if (s.carried == null) s.carried = new ArrayList<Object>();
		s.carried.add(seen);
	}

	/** Files everything the party carries (deliver), once. */
	protected void report(S s) {
		if (s.carried == null || s.carried.isEmpty()) return;
		List<Object> seen = new ArrayList<Object>(s.carried);
		s.carried.clear();
		for (Object o : seen) deliver(s, o);
	}

	// ------------------------------------------------------------------
	// the walk
	// ------------------------------------------------------------------

	/** One poll's step for a party: arrive, look, stay, move on or report home. */
	public void advance(S s) {
		CampaignFleetAPI fleet = s.fleet;
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
			all().remove(s);
			int unfiled = s.carried != null ? s.carried.size() : 0;
			// home: it despawned at its world (HomeMark), not in a fight on the way
			boolean home = s.returning && fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(HOME_FLAG);
			if (unfiled > 0 && home) {
				report(s);
				unfiled = 0;
			}
			if (!home) onLost(s);
			ThreatIncConfig.log(describe(s) + (home ? " home" : " lost")
					+ (unfiled > 0 ? ", and " + unfiled + " sighting(s) with it" : ""));
			return;
		}
		// what it carries is known the day it is in a friendly system
		if (s.carried != null && !s.carried.isEmpty() && fleet.getContainingLocation() instanceof StarSystemAPI
				&& friendly(s, (StarSystemAPI) fleet.getContainingLocation())) {
			report(s);
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

	/**
	 * The side stopped scouting (its knob turned off): every party still out
	 * turns for home, and the ones gone drop off the books. Left alone, parties
	 * sat on their 1,000-day patrol, swarms parked in human systems.
	 */
	public void recallAll() {
		for (S s : new ArrayList<S>(all())) {
			if (s.fleet == null || !s.fleet.isAlive() || s.fleet.isExpired()) {
				all().remove(s);
				continue;
			}
			if (!s.returning) goHome(s);
		}
	}

	/** RESET War: every party out fades and the side's list empties. */
	public void clearAll() {
		for (S s : all()) {
			if (s.fleet != null && s.fleet.isAlive()) Misc.fadeAndExpire(s.fleet);
		}
		all().clear();
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
		s.fleet.addEventListener(new HomeMark());
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
				returnLabel(s, home));
	}

	/** Set on a party that despawned at its home rather than died on the way (HomeMark). */
	public static final String HOME_FLAG = "$threatinc_scoutHome";

	/** Marks a returning party that reached its world, so advance can tell home from lost. */
	public static class HomeMark implements com.fs.starfarer.api.campaign.listeners.FleetEventListener {
		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason reason, Object param) {
			if (reason == com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.REACHED_DESTINATION) {
				fleet.getMemoryWithoutUpdate().set(HOME_FLAG, true);
			}
		}
		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
				com.fs.starfarer.api.campaign.BattleAPI battle) {
		}
	}

	// ------------------------------------------------------------------
	// patrols: how many and how big, and meeting a force (2026-10-05)
	// ------------------------------------------------------------------

	public static final String KEY_LEVEL = "threatinc_patrolLevel";
	public static final String KEY_LOSS = "threatinc_patrolLastLoss";

	/** Losses a side's patrols have not yet forgotten: each doubles their size and halves their number. */
	public static int level(String key) {
		Map<String, Integer> levels = ThreatIncData.map(KEY_LEVEL);
		Integer v = levels.get(key);
		return v != null ? Math.max(0, v.intValue()) : 0;
	}

	/** A patrol of this side did not come back. */
	public static void lost(String key) {
		Map<String, Integer> levels = ThreatIncData.map(KEY_LEVEL);
		Map<String, Long> last = ThreatIncData.map(KEY_LOSS);
		levels.put(key, level(key) + 1);
		last.put(key, Global.getSector().getClock().getTimestamp());
	}

	/** patrolCalmDays without a loss forgets one. */
	public static void calm(String key) {
		int level = level(key);
		if (level <= 0) return;
		Map<String, Long> last = ThreatIncData.map(KEY_LOSS);
		Long when = last.get(key);
		if (when != null && Global.getSector().getClock().getElapsedDaysSince(when) < ThreatIncConfig.patrolCalmDays()) return;
		Map<String, Integer> levels = ThreatIncData.map(KEY_LEVEL);
		levels.put(key, level - 1);
		last.put(key, Global.getSector().getClock().getTimestamp());
	}

	/** Whether the fleet sees what is at {@code where}: in the same system, or within patrolSightLY in hyperspace. */
	public static boolean near(CampaignFleetAPI fleet, com.fs.starfarer.api.campaign.LocationAPI where, Vector2f hyper) {
		if (fleet == null || where == null || fleet.getContainingLocation() == null) return false;
		if (!where.isHyperspace()) return fleet.getContainingLocation() == where;
		if (!fleet.getContainingLocation().isHyperspace() || hyper == null) return false;
		return Misc.getDistanceLY(fleet.getLocationInHyperspace(), hyper) <= ThreatIncConfig.patrolSightLY();
	}

	public static final int DESTROYED = 0, RUNS = 1, FIGHTS = 2;

	/**
	 * A patrol meets a force that flies as a route, of {@code forceFP} at
	 * {@code forceBurn} (the user, 2026-10-05: "depending on the speed of the
	 * incoming fleet it might be able to eliminate the patrol. Or the patrol
	 * might be big enough to eliminate the incoming fleet"). Slower and no
	 * stronger, the patrol is destroyed; stronger and at least as fast, the
	 * two fight a day - the patrol loses ships, the force route damage, each
	 * by BattleRules.defenderLoss; otherwise one of them gets away and the
	 * patrol runs with what it saw.
	 */
	public static int meetAbstract(CampaignFleetAPI patrol, float forceFP, float forceBurn,
			com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData route, String who, String what) {
		float fp = patrol.getFleetPoints();
		float burn = patrol.getFleetData().getBurnLevel();
		boolean stronger = fp > forceFP;
		boolean faster = burn >= forceBurn;
		if (!stronger && !faster) {
			ThreatIncConfig.log(who + " (" + (int) fp + " FP, burn " + (int) burn + ") was caught by " + what + " of "
					+ (int) forceFP + " FP (burn " + (int) forceBurn + ") and destroyed");
			patrol.despawn(com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE, null);
			return DESTROYED;
		}
		if (stronger && faster) {
			float forceShare = threatinc.rules.BattleRules.defenderLoss(fp, forceFP);
			float ownShare = threatinc.rules.BattleRules.defenderLoss(forceFP, fp);
			float lostOwn = ThreatAbstractBattle.removeShare(patrol, ownShare, new java.util.Random());
			if (route != null && route.getExtra() != null && forceShare > 0f) {
				float had = route.getExtra().damage != null ? route.getExtra().damage : 0f;
				route.getExtra().damage = Math.min(1f, 1f - (1f - had) * (1f - Math.min(1f, forceShare)));
			}
			ThreatIncConfig.log(who + " (" + (int) fp + " FP, burn " + (int) burn + ") caught " + what + " of "
					+ (int) forceFP + " FP (burn " + (int) forceBurn + "): the force lost " + Math.round(forceShare * 100f)
					+ "%, the patrol " + (int) lostOwn + " FP");
			return patrol.isAlive() && patrol.getFleetPoints() > 0 ? FIGHTS : DESTROYED;
		}
		return RUNS;
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
	 * The candidates as a route, nearest-first from where the party starts,
	 * each leg measured from the stop before. After the first, a stop joins
	 * only while it lies nearer the last stop than the party's start: a
	 * neighbour is the party's to sweep on, one back toward home a fresh
	 * party's (2026-09-29: routes were cut at scoutStops, 4, and a side fielded
	 * at most two parties - a side now sends as many as its candidates need).
	 * Takes the stops it uses out of the list.
	 */
	public static List<String> nearestFirst(List<StarSystemAPI> candidates, Vector2f from) {
		List<String> route = new ArrayList<String>();
		Vector2f at = from;
		while (!candidates.isEmpty()) {
			StarSystemAPI next = null;
			float bestDist = Float.MAX_VALUE;
			for (StarSystemAPI system : candidates) {
				float d = Misc.getDistanceLY(system.getLocation(), at);
				if (!route.isEmpty() && d >= Misc.getDistanceLY(system.getLocation(), from)) continue;
				if (d < bestDist) {
					bestDist = d;
					next = system;
				}
			}
			if (next == null) break;
			candidates.remove(next);
			route.add(next.getId());
			at = next.getLocation();
		}
		return route;
	}
}
