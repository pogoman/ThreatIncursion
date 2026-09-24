package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * The swarm scouts before it strikes (2026-09-24, docs/strategy-layer.md
 * "Finding the hive") - the mirror of {@link ThreatScouts}.
 *
 * <p>A strike may only target a world in a system the swarm KNOWS
 * ({@link #swarmKnows}): one a Scouting Swarm has entered, one a hive colony
 * shares, or a world a Threat ground front stands on. From phase 2 a hive
 * colony that could stage a strike on the economy alone - size, hulls
 * delivered, fuel, a working Swarm Nexus - sends a Scouting Swarm
 * ({@link ThreatFleetComposer#createScouts}) through the unknown inhabited
 * systems within its fuel reach, nearest-first, scoutStops per sortie. Scouts
 * are fabricated fresh, not mustered: the Defense Swarms stay home. At most
 * swarmScoutMax fly at once. They keep the swarm's stealth, pick no fights,
 * and fade out at home.
 *
 * <p>Knob swarmScouting off: the swarm knows every world, as before.
 */
public class ThreatSwarmScouts {

	public static final String KEY_SCOUTS = "threatinc_swarmScouts";
	public static final String KEY_KNOWN = "threatinc_swarmKnownSystems";
	public static final String SCOUT_FLAG = "$threatinc_swarmScout";

	public static class Scout {
		public CampaignFleetAPI fleet;
		public String homeMarketId;
		public List<String> route = new ArrayList<String>();
		public int leg;
		/** When the current leg's system was entered; 0 while in transit. */
		public long enteredTimestamp;
		public boolean returning;
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

	/** System id -> when the swarm first scouted it. */
	public static Map<String, Long> known() {
		return ThreatIncData.map(KEY_KNOWN);
	}

	public static boolean enabled() {
		return ThreatIncConfig.swarmScouting();
	}

	/** Whether the swarm may strike this world: its system scouted or shared, or a Threat front on it. */
	public static boolean swarmKnows(MarketAPI market) {
		if (!enabled()) return true;
		if (market == null || market.getStarSystem() == null) return false;
		String systemId = market.getStarSystem().getId();
		if (known().containsKey(systemId)) return true;
		if (!ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) return true;
		return ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()));
	}

	protected static boolean knowsSystem(StarSystemAPI system) {
		return known().containsKey(system.getId())
				|| !ThreatIncData.getLiveColonyMarkets(system.getId()).isEmpty();
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	public static void poll(Random random) {
		if (!enabled()) return;
		for (Scout s : new ArrayList<Scout>(all())) {
			advance(s);
		}
		if (IncursionManager.getPhase() < 2) return;
		if (countOut() >= ThreatIncConfig.swarmScoutMax()) return;
		launchOne(random);
	}

	protected static void advance(Scout s) {
		CampaignFleetAPI fleet = s.fleet;
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
			all().remove(s);
			ThreatIncConfig.log("Scouting Swarm " + (s.returning ? "home" : "lost"));
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
			// in the system is enough: the swarm sees what lives there
			s.enteredTimestamp = now;
			if (!known().containsKey(target.getId())) {
				known().put(target.getId(), now);
				ThreatColonyManager.announce("A Scouting Swarm has charted the "
						+ target.getNameWithLowercaseType() + ".", Misc.getNegativeHighlightColor());
				ThreatIncConfig.log("Scouting Swarm charted " + target.getName());
			}
		}
		if (Global.getSector().getClock().getElapsedDaysSince(s.enteredTimestamp)
				< ThreatIncConfig.scoutStayDays()) return;
		nextLeg(s);
	}

	protected static void nextLeg(Scout s) {
		s.leg++;
		while (s.leg < s.route.size() && known().containsKey(s.route.get(s.leg))) s.leg++;
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
				"charting the " + system.getNameWithLowercaseTypeShort());
	}

	protected static void goHome(Scout s) {
		s.returning = true;
		s.fleet.clearAssignments();
		MarketAPI home = ThreatIncData.resolveColonyMarket(s.homeMarketId);
		if (home == null || home.getPrimaryEntity() == null) {
			Misc.fadeAndExpire(s.fleet);
			return;
		}
		s.fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		s.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
				"returning to the hive");
	}

	protected static int countOut() {
		int n = 0;
		for (Scout s : all()) {
			if (!s.returning) n++;
		}
		return n;
	}

	// ------------------------------------------------------------------
	// sorties
	// ------------------------------------------------------------------

	/** One sortie from the first hive colony, in shuffled order, that can send one and has somewhere to look. */
	protected static void launchOne(Random random) {
		List<String> systemIds = new ArrayList<String>(ThreatIncData.colonyMarkets().keySet());
		Collections.shuffle(systemIds, random);
		for (String systemId : systemIds) {
			MarketAPI colony = pickStaging(systemId);
			if (colony == null) continue;
			List<String> route = planRoute(colony, ThreatColonyManager.fuelRangeLY(colony));
			if (route.isEmpty()) continue;
			launch(colony, route, random);
			return;
		}
	}

	/**
	 * pickStrikeStaging's economy gates without the garrison one: a scout is
	 * fabricated, not mustered. The largest colony that passes.
	 */
	protected static MarketAPI pickStaging(String systemId) {
		MarketAPI best = null;
		for (MarketAPI curr : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (curr.getPrimaryEntity() == null) continue;
			if (curr.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (ThreatColonyManager.shipsAvailable(curr) <= 0f) continue;
			if (!ThreatColonyManager.hasOperationalFuel(curr)) continue;
			if (!ThreatColonyManager.hasOperationalNexus(curr)) continue;
			if (best == null || curr.getSize() > best.getSize()) best = curr;
		}
		return best;
	}

	/**
	 * Up to scoutStops unknown systems holding a strikeable world within the
	 * colony's fuel reach, nearest-first, none already on another scout's route.
	 */
	protected static List<String> planRoute(MarketAPI colony, float rangeLY) {
		List<String> route = new ArrayList<String>();
		StarSystemAPI home = colony.getStarSystem();
		if (home == null || rangeLY <= 0f) return route;
		List<String> taken = new ArrayList<String>();
		for (Scout s : all()) {
			if (s.returning) continue;
			for (int i = s.leg; i < s.route.size(); i++) taken.add(s.route.get(i));
		}
		List<StarSystemAPI> candidates = new ArrayList<StarSystemAPI>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!IncursionManager.isStrikeableWorld(market)) continue;
			StarSystemAPI system = market.getStarSystem();
			if (system.getCenter() == null || candidates.contains(system)) continue;
			if (knowsSystem(system) || taken.contains(system.getId())) continue;
			if (Misc.getDistanceLY(home.getLocation(), system.getLocation()) > rangeLY) continue;
			candidates.add(system);
		}
		Vector2f at = home.getLocation();
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

	protected static Scout launch(MarketAPI colony, List<String> route, Random random) {
		CampaignFleetAPI fleet = ThreatFleetComposer.createScouts(ThreatIncConfig.swarmScoutFleetPoints(),
				new Random(random.nextLong()));
		if (fleet == null || fleet.isEmpty()) return null;
		colony.getStarSystem().addEntity(fleet);
		fleet.setLocation(colony.getPrimaryEntity().getLocation().x, colony.getPrimaryEntity().getLocation().y);
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(SCOUT_FLAG, true);
		// it looks, it does not hunt: no chases off the route, no fights picked,
		// and it keeps the swarm's stealth (no makeDetectable)
		mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		mem.unset(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT);
		mem.set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
		mem.set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);

		Scout s = new Scout();
		s.fleet = fleet;
		s.homeMarketId = colony.getId();
		s.route = route;
		all().add(s);
		sendTo(s, ThreatWarBoard.getSystem(route.get(0)));

		ThreatIncConfig.log("Scouting Swarm from " + colony.getName() + ": " + route);
		return s;
	}
}
