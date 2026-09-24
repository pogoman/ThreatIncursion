package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * Scouting parties - how the sector finds the hive (2026-09-24,
 * docs/strategy-layer.md "Finding the hive").
 *
 * <p>A Threat strike does not give away where it came from; it gives the
 * struck faction a LEAD. The faction sends a small scouting party to sweep
 * the uninhabited systems within scoutLeadRadiusLY of the strike's true
 * origin, nearest-first from home, a few stops per sortie, until the origin
 * is found. A mobilised faction with no lead sweeps the uninhabited systems
 * within scoutRangeLY of one of its military worlds every scoutIntervalDays.
 *
 * <p>A scout that jumps into a system with a live hive colony reveals it -
 * to the whole sector, player included: {@link ThreatIncData#discoveredSystems}
 * is one shared list, and NPC war efforts act only on what it holds
 * ({@link #sectorKnows}). A hive in a system someone else lives in is seen by
 * the neighbours. A system swept clear is not swept again for
 * scoutMemoryDays; a lead ignores sweeps older than itself.
 *
 * <p>Knob hiveFogOfWar off restores the old rule: every strike names its
 * origin, NPC factions know every hive, no scouts fly.
 */
public class ThreatScouts {

	public static final String KEY_SCOUTS = "threatinc_scouts";
	public static final String KEY_SWEPT = "threatinc_sweptSystems";
	public static final String KEY_LEADS = "threatinc_scoutLeads";
	public static final String KEY_LAST_SWEEP = "threatinc_scoutLastSweep";
	public static final String SCOUT_FLAG = "$threatinc_scout";

	public static class Scout {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String homeMarketId;
		public List<String> route = new ArrayList<String>();
		public int leg;
		/** When the current leg's system was entered; 0 while in transit. */
		public long enteredTimestamp;
		public boolean returning;
		/** The origin this sortie is following up; null for a routine sweep. */
		public String leadSystemId;
	}

	/** A strike's origin a faction is looking for, and since when. */
	public static class Lead {
		public String systemId;
		public long timestamp;
	}

	@SuppressWarnings("unchecked")
	public static List<Scout> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_SCOUTS);
		if (!(val instanceof List)) {
			val = new ArrayList<Scout>();
			Global.getSector().getPersistentData().put(KEY_SCOUTS, val);
		}
		return (List<Scout>) val;
	}

	/** System id -> when a scout last swept it and found no hive. */
	public static Map<String, Long> swept() {
		return ThreatIncData.map(KEY_SWEPT);
	}

	/** Faction id -> the strike origins it is looking for. */
	public static Map<String, List<Lead>> leads() {
		return ThreatIncData.map(KEY_LEADS);
	}

	protected static Map<String, Long> lastSweep() {
		return ThreatIncData.map(KEY_LAST_SWEEP);
	}

	public static boolean enabled() {
		return ThreatIncConfig.hiveFogOfWar();
	}

	/**
	 * Whether the sector knows of the hive in this system - the gate on every
	 * NPC war effort against it. Not debug-bypassed: debug mode shows the
	 * player everything, but the NPC war still plays by the fog.
	 */
	public static boolean sectorKnows(String systemId) {
		if (!enabled()) return true;
		return systemId != null && ThreatIncData.discoveredSystems().contains(systemId);
	}

	public static boolean sectorKnows(MarketAPI hive) {
		return hive != null && hive.getStarSystem() != null && sectorKnows(hive.getStarSystem().getId());
	}

	/**
	 * Marks the system known to everyone; the first time, tells the player who
	 * found it. finderFactionId null = no one in particular.
	 */
	public static void reveal(String systemId, String finderFactionId) {
		if (systemId == null || ThreatIncData.discoveredSystems().contains(systemId)) return;
		ThreatIncData.markDiscovered(systemId);
		StarSystemAPI system = ThreatWarBoard.getSystem(systemId);
		String where = system != null ? system.getNameWithLowercaseType() : systemId;
		String who = finderFactionId == null ? "The sector"
				: Factions.PLAYER.equals(finderFactionId) ? "Your faction"
				: ThreatWarState.displayName(finderFactionId);
		ThreatColonyManager.announceAlways(who + " has found a Threat hive in the " + where + ".",
				Misc.getNegativeHighlightColor());
		ThreatIncConfig.log("Hive found in " + where + " by " + finderFactionId);
	}

	/** A strike from originSystemId has hit this faction: it goes looking. */
	public static void addLead(String factionId, String originSystemId) {
		if (!enabled() || !mayScout(factionId) || originSystemId == null) return;
		if (sectorKnows(originSystemId)) return;
		List<Lead> list = leads().get(factionId);
		if (list == null) {
			list = new ArrayList<Lead>();
			leads().put(factionId, list);
		}
		for (Lead lead : list) {
			if (originSystemId.equals(lead.systemId)) return;
		}
		Lead lead = new Lead();
		lead.systemId = originSystemId;
		lead.timestamp = Global.getSector().getClock().getTimestamp();
		list.add(lead);
		ThreatIncConfig.log("Scout lead for " + factionId + ": strike origin " + originSystemId);
	}

	protected static boolean mayScout(String factionId) {
		if (factionId == null || Factions.PLAYER.equals(factionId) || Factions.THREAT.equals(factionId)) {
			return false;
		}
		if (ThreatWarState.excluded(factionId)) return false;
		FactionAPI faction = Global.getSector().getFaction(factionId);
		return faction != null && !faction.isNeutralFaction();
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	public static void poll(Random random) {
		if (!enabled()) return;
		revealNeighbours();
		for (Scout s : new ArrayList<Scout>(all())) {
			advance(s);
		}
		pruneLeads();
		launchSorties(random);
	}

	/** A hive in a system where anyone else keeps a colony is no secret. */
	protected static void revealNeighbours() {
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.discoveredSystems().contains(systemId)) continue;
			if (!hasLiveHive(systemId)) continue;
			StarSystemAPI system = ThreatWarBoard.getSystem(systemId);
			if (system == null) continue;
			for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
				if (market.isHidden() || market.getPrimaryEntity() == null) continue;
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
				reveal(systemId, market.getFactionId());
				break;
			}
		}
	}

	protected static boolean hasLiveHive(String systemId) {
		return !ThreatIncData.getLiveColonyMarkets(systemId).isEmpty();
	}

	protected static void advance(Scout s) {
		CampaignFleetAPI fleet = s.fleet;
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
			all().remove(s);
			ThreatIncConfig.log("Scout of " + s.factionId + (s.returning ? " home" : " lost"));
			return;
		}
		if (s.returning) return; // GO_TO_LOCATION_AND_DESPAWN does the rest
		if (s.leg >= s.route.size()) {
			goHome(s);
			return;
		}
		StarSystemAPI target = ThreatWarBoard.getSystem(s.route.get(s.leg));
		if (target == null) {
			nextLeg(s);
			return;
		}
		if (fleet.getContainingLocation() != target) return;
		long now = Global.getSector().getClock().getTimestamp();
		if (s.enteredTimestamp == 0L) {
			s.enteredTimestamp = now;
			if (hasLiveHive(target.getId())) {
				reveal(target.getId(), s.factionId);
				goHome(s); // the report is what counts
				return;
			}
		}
		if (Global.getSector().getClock().getElapsedDaysSince(s.enteredTimestamp)
				< ThreatIncConfig.scoutStayDays()) return;
		swept().put(target.getId(), now);
		nextLeg(s);
	}

	protected static void nextLeg(Scout s) {
		s.leg++;
		// a stop someone else found meanwhile is skipped
		while (s.leg < s.route.size() && ThreatIncData.discoveredSystems().contains(s.route.get(s.leg))) {
			s.leg++;
		}
		if (s.leg >= s.route.size()) {
			goHome(s);
			return;
		}
		StarSystemAPI next = ThreatWarBoard.getSystem(s.route.get(s.leg));
		if (next == null) {
			nextLeg(s);
			return;
		}
		sendTo(s, next);
	}

	protected static void sendTo(Scout s, StarSystemAPI system) {
		s.enteredTimestamp = 0L;
		s.fleet.clearAssignments();
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, system.getCenter(), 1000f,
				"scouting the " + system.getNameWithLowercaseTypeShort());
		s.fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, system.getCenter(), 1000f,
				"sweeping the " + system.getNameWithLowercaseTypeShort());
	}

	protected static void goHome(Scout s) {
		s.returning = true;
		s.fleet.clearAssignments();
		MarketAPI home = Global.getSector().getEconomy().getMarket(s.homeMarketId);
		if (home == null || home.getPrimaryEntity() == null || !s.factionId.equals(home.getFactionId())) {
			Misc.fadeAndExpire(s.fleet);
			return;
		}
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
				"returning to " + home.getName());
	}

	/** A lead ends when its origin is known or its hive is gone. */
	protected static void pruneLeads() {
		for (String factionId : new ArrayList<String>(leads().keySet())) {
			List<Lead> list = leads().get(factionId);
			if (list != null) {
				for (Lead lead : new ArrayList<Lead>(list)) {
					if (sectorKnows(lead.systemId) || !hasLiveHive(lead.systemId) || !mayScout(factionId)) {
						list.remove(lead);
					}
				}
			}
			if (list == null || list.isEmpty()) leads().remove(factionId);
		}
	}

	// ------------------------------------------------------------------
	// sorties
	// ------------------------------------------------------------------

	protected static void launchSorties(Random random) {
		List<String> factions = new ArrayList<String>(leads().keySet());
		for (String id : ThreatWarState.warFactionIds()) {
			if (!factions.contains(id)) factions.add(id);
		}
		long now = Global.getSector().getClock().getTimestamp();
		for (String factionId : factions) {
			if (!mayScout(factionId)) continue;
			if (countFor(factionId) >= ThreatIncConfig.scoutMaxPerFaction()) continue;
			List<Lead> list = leads().get(factionId);
			if (list != null && !list.isEmpty()) {
				for (Lead lead : new ArrayList<Lead>(list)) {
					if (launchLead(factionId, lead)) break;
					// nothing left to sweep and no one out looking: the lead is spent
					if (!leadInFlight(factionId, lead.systemId)) list.remove(lead);
				}
				continue;
			}
			if (!ThreatWarState.isAtWar(factionId)) continue;
			Long last = lastSweep().get(factionId);
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
					< ThreatIncConfig.scoutIntervalDays()) continue;
			lastSweep().put(factionId, now);
			launchRoutine(factionId, random);
		}
	}

	protected static int countFor(String factionId) {
		int n = 0;
		for (Scout s : all()) {
			if (!s.returning && factionId.equals(s.factionId)) n++;
		}
		return n;
	}

	protected static boolean leadInFlight(String factionId, String systemId) {
		for (Scout s : all()) {
			if (!s.returning && factionId.equals(s.factionId) && systemId.equals(s.leadSystemId)) return true;
		}
		return false;
	}

	/** Sweeps the area around a strike's origin from the faction's nearest military world. */
	protected static boolean launchLead(String factionId, Lead lead) {
		StarSystemAPI origin = ThreatWarBoard.getSystem(lead.systemId);
		if (origin == null) return false;
		MarketAPI home = nearestBase(factionId, origin.getLocation());
		if (home == null) return false;
		List<String> route = planRoute(home, origin.getLocation(),
				ThreatIncConfig.scoutLeadRadiusLY(), lead.timestamp);
		if (route.isEmpty()) return false;
		return launch(factionId, home, route, lead.systemId) != null;
	}

	/** Sweeps the unexplored space around one of the faction's military worlds. */
	protected static void launchRoutine(String factionId, Random random) {
		List<MarketAPI> bases = new ArrayList<MarketAPI>();
		for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
			if (market.getStarSystem() != null && IncursionManager.hasMilitary(market)) bases.add(market);
		}
		Collections.shuffle(bases, random);
		for (MarketAPI home : bases) {
			List<String> route = planRoute(home, home.getStarSystem().getLocation(),
					ThreatIncConfig.scoutRangeLY(), 0L);
			if (route.isEmpty()) continue;
			launch(factionId, home, route, null);
			return;
		}
	}

	protected static MarketAPI nearestBase(String factionId, Vector2f where) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		boolean bestMil = false;
		for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
			if (market.getStarSystem() == null) continue;
			boolean mil = IncursionManager.hasMilitary(market);
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), where);
			// a military world first; any colony if the faction has none
			if (best == null || (mil && !bestMil) || (mil == bestMil && d < bestDist)) {
				best = market;
				bestDist = d;
				bestMil = mil;
			}
		}
		return best;
	}

	/**
	 * Up to scoutStops systems within radius of the centre, nearest-first from
	 * home: unknown, uninhabited, with a planet, not on another scout's route,
	 * and not swept clear lately - since the lead began (leadSince), else
	 * within scoutMemoryDays.
	 */
	protected static List<String> planRoute(MarketAPI home, Vector2f centre, float radiusLY, long leadSince) {
		List<String> taken = new ArrayList<String>();
		for (Scout s : all()) {
			if (s.returning) continue;
			for (int i = s.leg; i < s.route.size(); i++) taken.add(s.route.get(i));
		}
		List<StarSystemAPI> candidates = new ArrayList<StarSystemAPI>();
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			String id = system.getId();
			if (system.getCenter() == null) continue;
			if (Misc.getDistanceLY(system.getLocation(), centre) > radiusLY) continue;
			if (ThreatIncData.discoveredSystems().contains(id) || taken.contains(id)) continue;
			if (sweptLately(id, leadSince)) continue;
			if (!hasPlanet(system) || inhabited(system)) continue;
			candidates.add(system);
		}
		List<String> route = new ArrayList<String>();
		Vector2f at = home.getStarSystem().getLocation();
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

	protected static boolean sweptLately(String systemId, long leadSince) {
		Long when = swept().get(systemId);
		if (when == null) return false;
		if (leadSince != 0L) return when >= leadSince;
		return Global.getSector().getClock().getElapsedDaysSince(when) < ThreatIncConfig.scoutMemoryDays();
	}

	protected static boolean hasPlanet(StarSystemAPI system) {
		for (PlanetAPI planet : system.getPlanets()) {
			if (!planet.isStar()) return true;
		}
		return false;
	}

	/** Anyone but the swarm keeps a colony there - revealNeighbours covers those. */
	protected static boolean inhabited(StarSystemAPI system) {
		for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
			if (market.isHidden() || market.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			return true;
		}
		return false;
	}

	protected static Scout launch(String factionId, MarketAPI home, List<String> route, String leadSystemId) {
		StarSystemAPI homeSystem = home.getStarSystem();
		if (homeSystem == null || home.getPrimaryEntity() == null) return null;
		float fp = ThreatIncConfig.scoutFleetPoints();
		FleetParamsV3 params = new FleetParamsV3(
				home,
				home.getLocationInHyperspace(),
				factionId,
				null,
				FleetTypes.PATROL_SMALL,
				fp,        // combat
				0f,        // freighters
				fp * 0.2f, // tankers: it goes a long way
				0f, 0f, 0f,
				0f);
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		homeSystem.addEntity(fleet);
		fleet.setLocation(home.getPrimaryEntity().getLocation().x, home.getPrimaryEntity().getLocation().y);
		fleet.setName("Scouting Party");
		fleet.setNoFactionInName(false);
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(SCOUT_FLAG, true);
		// it looks, it does not fight: no picking battles, no borrowing it
		mem.set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
		mem.set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);

		Scout s = new Scout();
		s.fleet = fleet;
		s.factionId = factionId;
		s.homeMarketId = home.getId();
		s.route = route;
		s.leg = 0;
		s.leadSystemId = leadSystemId;
		all().add(s);
		sendTo(s, ThreatWarBoard.getSystem(route.get(0)));

		ThreatIncConfig.log("Scouting party of " + factionId + " from " + home.getName()
				+ (leadSystemId != null ? " (lead)" : " (sweep)") + ": " + route);
		return s;
	}
}
