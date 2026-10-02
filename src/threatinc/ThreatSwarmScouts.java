package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
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
 * delivered, fuel, a working Swarm Nexus - sends Scouting Swarms
 * ({@link ThreatFleetComposer#createScouts}) through the unknown inhabited
 * systems within its fuel reach, nearest-first, as many as those systems
 * need. Scouts are fabricated fresh, not mustered - the Defense Swarms stay
 * home - and paid from the colony's fabrication bank (ThreatColonyManager's
 * ledger): a colony that has not banked a scout's fleet points sends none.
 * They keep the swarm's stealth, pick no fights, and fade out at home.
 *
 * <p>In the swarm's fog (ThreatSwarmIntel, docs/threat-fog.md) a scout reports
 * every human place in each system it enters, and after the unknown systems
 * the charted ones whose reports have gone stale are scouted again.
 *
 * <p>Knob swarmScouting off: the swarm knows every world, as before.
 */
public class ThreatSwarmScouts {

	public static final String KEY_SCOUTS = "threatinc_swarmScouts";
	public static final String KEY_KNOWN = "threatinc_swarmKnownSystems";
	public static final String SCOUT_FLAG = "$threatinc_swarmScout";

	/** A Scouting Swarm out charting; everything it carries is the {@link ThreatScoutRoute.Party}'s. */
	public static class Scout extends ThreatScoutRoute.Party {
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
		if (!enabled()) {
			// switched off mid-game: the swarms out go home rather than park where they are
			if (!all().isEmpty()) ROUTE.recallAll();
			return;
		}
		for (Scout s : new ArrayList<Scout>(all())) {
			ROUTE.advance(s);
		}
		if (IncursionManager.getPhase() < 2) return;
		launchAll(random);
	}

	/** RESET War: the parties out fade and what the swarm charted is forgotten. */
	public static void reset() {
		ROUTE.clearAll();
		Global.getSector().getPersistentData().remove(KEY_SCOUTS);
		Global.getSector().getPersistentData().remove(KEY_KNOWN);
	}

	/** The route walker, with what a Scouting Swarm does at a stop. */
	protected static final ThreatScoutRoute<Scout> ROUTE = new ThreatScoutRoute<Scout>() {
		protected List<Scout> all() {
			return ThreatSwarmScouts.all();
		}
		protected String describe(Scout s) {
			return "Scouting Swarm";
		}
		protected boolean knownStop(String systemId) {
			// in the swarm's fog a charted stop whose reports went stale is a
			// re-scout (planRoute), not skipped
			if (ThreatSwarmIntel.enabled() && ThreatSwarmIntel.stale(systemId)) return false;
			return known().containsKey(systemId);
		}
		protected boolean onEnter(Scout s, StarSystemAPI system, long now) {
			// in the system is enough: the swarm sees what lives there
			if (!known().containsKey(system.getId())) {
				known().put(system.getId(), now);
				ThreatColonyManager.announce(ThreatNotice.titled("System Charted").bad()
						.line("A Scouting Swarm has charted the %s.", system.getNameWithLowercaseType()));
				ThreatIncConfig.log("Scouting Swarm charted " + system.getName());
				ThreatOmens.onSwarmScouted(system);
			}
			// and, in its fog, reports every human place there (ThreatSwarmIntel)
			if (ThreatSwarmIntel.enabled()) ThreatSwarmIntel.scouted(system);
			return false;
		}
		protected String stayVerb() {
			return "charting";
		}
		protected MarketAPI homeOf(Scout s) {
			return ThreatIncData.resolveColonyMarket(s.homeMarketId);
		}
		protected void onReturn(Scout s) {
			s.fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		}
		protected String returnLabel(Scout s, MarketAPI home) {
			return "returning to the hive";
		}
	};

	// ------------------------------------------------------------------
	// sorties
	// ------------------------------------------------------------------

	/**
	 * Sorties from every hive system that can send one, in shuffled order, a
	 * Scouting Swarm per route until nothing unknown is left in its reach
	 * (2026-09-29: one sortie per poll, swarmScoutMax, 2, in the air at once).
	 * Each launch takes its stops off the candidates (ROUTE.taken), so the
	 * loop ends.
	 */
	protected static void launchAll(Random random) {
		List<String> systemIds = new ArrayList<String>(ThreatIncData.colonyMarkets().keySet());
		Collections.shuffle(systemIds, random);
		List<StarSystemAPI> claims = ThreatSwarmIntel.enabled() ? pendingClaims() : null;
		for (String systemId : systemIds) {
			MarketAPI colony = pickStaging(systemId);
			if (colony == null) continue;
			// billed reach (ThreatReach): every uncharted system, a scout's route
			// as long as its own tanks carry it (launch)
			float range = ThreatReach.enabled() ? Float.MAX_VALUE : ThreatColonyManager.fuelRangeLY(colony);
			while (true) {
				List<String> route = planRoute(colony, range);
				if (route.isEmpty() || launch(colony, route, random) == null) break;
			}
			// in the swarm's fog, then the charted systems it has not looked at
			// in a while (ThreatSwarmIntel.stale): its figures there are old. A
			// pass of their own, so an uncharted stop no tank reaches does not
			// hold up the near ones
			if (!ThreatSwarmIntel.enabled()) continue;
			// spreading comes first: a re-scout waits while the stock is short of the
			// dearest pending claim's founding (pd2a: 478 re-scouts drained the fuel a
			// 25-30 ly founding needed, and no outward claim was made for 14 months)
			float reserve = claimReserve(colony, claims);
			while (ThreatFuel.stock() >= reserve) {
				List<String> route = planRoute(colony, range, true);
				if (route.isEmpty() || launch(colony, route, random) == null) break;
			}
		}
	}

	/** The systems of the pending outward claims (SEEDED, not a bootstrap seed). */
	protected static List<StarSystemAPI> pendingClaims() {
		List<StarSystemAPI> out = new ArrayList<StarSystemAPI>();
		Map<String, String> stages = ThreatIncData.stages();
		for (StarSystemAPI s : Global.getSector().getStarSystems()) {
			if (ThreatIncData.STAGE_SEEDED.equals(stages.get(s.getId()))
					&& !ThreatIncData.bootstrapSeeds().contains(s.getId())) out.add(s);
		}
		return out;
	}

	/** The fuel the dearest pending claim's founding would take from the colony (ThreatColonyManager.foundingFuel); 0 with none. */
	protected static float claimReserve(MarketAPI colony, List<StarSystemAPI> claims) {
		float most = 0f;
		if (claims == null) return most;
		for (StarSystemAPI s : claims) most = Math.max(most, ThreatColonyManager.foundingFuel(colony, s));
		return most;
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
	 * A route (ThreatScoutRoute.nearestFirst) through the unknown systems holding
	 * a strikeable world within the colony's fuel reach, none already on another
	 * scout's route.
	 */
	protected static List<String> planRoute(MarketAPI colony, float rangeLY) {
		return planRoute(colony, rangeLY, false);
	}

	/**
	 * As above; {@code rescout}, in the swarm's fog, through the charted non-hive
	 * systems holding a strikeable world whose reports have gone stale
	 * (ThreatSwarmIntel.stale) instead of the unknown ones.
	 */
	protected static List<String> planRoute(MarketAPI colony, float rangeLY, boolean rescout) {
		StarSystemAPI home = colony.getStarSystem();
		if (home == null || rangeLY <= 0f) return new ArrayList<String>();
		List<String> taken = ROUTE.taken();
		List<StarSystemAPI> candidates = new ArrayList<StarSystemAPI>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!IncursionManager.isStrikeableWorld(market)) continue;
			StarSystemAPI system = market.getStarSystem();
			if (system.getCenter() == null || candidates.contains(system)) continue;
			if (taken.contains(system.getId())) continue;
			if (rescout) {
				if (!known().containsKey(system.getId())
						|| !ThreatIncData.getLiveColonyMarkets(system.getId()).isEmpty()
						|| !ThreatSwarmIntel.stale(system.getId())) {
					continue;
				}
			} else if (knowsSystem(system)) {
				continue;
			}
			if (Misc.getDistanceLY(home.getLocation(), system.getLocation()) > rangeLY) continue;
			candidates.add(system);
		}
		return ThreatScoutRoute.nearestFirst(candidates, home.getLocation());
	}

	/** Light-years there and home the last Scouting Swarm built carries in its tanks (billed reach); -1 before the first. */
	protected static float lastTank = -1f;

	/**
	 * Fabricates a Scouting Swarm at the colony and sends it down the route; null
	 * when the colony's nexus has not banked what it costs (the fabrication
	 * ledger, 2026-09-29 - scouts came free), or the fleet could not be built.
	 */
	protected static Scout launch(MarketAPI colony, List<String> route, Random random) {
		float budget = ThreatIncConfig.swarmScoutFleetPoints();
		if (!ThreatColonyManager.canAffordFP(colony, budget)) return null;
		// the supplies it burns away come out of what the colonies leave and the
		// stock (ThreatReach), over its route's days
		if (!ThreatReach.canSustain(budget, ThreatReach.days(routeLY(colony, route)))) return null;
		// the route and home again comes from the hive's fuel (ThreatFuel): the
		// whole path is flown, so it is paid one way along it
		float fuel = ThreatFuel.passage(budget, routeLY(colony, route), false);
		// (billed reach trims the route to what the stock and the tanks carry once the scout is built)
		if (!ThreatReach.enabled() && !ThreatFuel.canPay(fuel)) {
			ThreatFuel.held("a scout from " + colony.getName());
			return null;
		}
		if (ThreatReach.enabled()) {
			// not even the first stop and home: no scout is built to be scrapped
			float firstLY = routeLY(colony, route.subList(0, 1));
			if (lastTank > 0f && firstLY > lastTank) return null;
			if (!ThreatFuel.canPay(ThreatFuel.passage(budget, firstLY, false))) {
				ThreatFuel.held("a scout from " + colony.getName());
				return null;
			}
		}
		CampaignFleetAPI fleet = ThreatFleetComposer.createScouts(budget, new Random(random.nextLong()));
		if (fleet == null || fleet.isEmpty()) return null;
		// billed reach: the route runs as far as the scout's own tanks carry it
		// there and home - its hulls' vanilla fuel over their burn a light-year,
		// 200 ly for the swarm's - and the stock pays; the stops past that wait
		// for the next scout
		if (ThreatReach.enabled()) {
			float fuelCap = 0f, perLY = 0f;
			for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
				fuelCap += m.getHullSpec().getFuel();
				perLY += m.getHullSpec().getFuelPerLY();
			}
			float tank = perLY > 0f ? fuelCap / perLY : Float.MAX_VALUE;
			// the longest tank yet: a small one never latches the pre-check shut
			lastTank = Math.max(lastTank, tank);
			float fp = fleet.getFleetPoints();
			while (route.size() > 1 && (routeLY(colony, route) > tank
					|| !ThreatFuel.canPay(ThreatFuel.passage(fp, routeLY(colony, route), false)))) {
				route = route.subList(0, route.size() - 1);
			}
			route = new ArrayList<String>(route);
			if (routeLY(colony, route) > tank) return null;
			if (!ThreatFuel.canPay(ThreatFuel.passage(fp, routeLY(colony, route), false))) {
				ThreatFuel.held("a scout from " + colony.getName());
				return null;
			}
		}
		ThreatColonyManager.chargeFP(colony, fleet.getFleetPoints());
		ThreatFuel.pay(Math.min(ThreatFuel.stock(),
				ThreatFuel.passage(fleet.getFleetPoints(), routeLY(colony, route), false)));
		// what survives is re-banked when it despawns home (2026-09-29: closed
		// economy - it was charged and never bound, so every scout was spent whole)
		ThreatColonyManager.bindToLedger(fleet, colony.getId());
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
		ROUTE.sendTo(s, ThreatScoutRoute.systemById(route.get(0)));

		ThreatReach.commit(fleet.getFleetPoints());
		ThreatReach.note("scout", routeLY(colony, route));
		ThreatIncConfig.log("Scouting Swarm from " + colony.getName() + ": " + route + " (" + (int) routeLY(colony, route)
				+ " ly there and home)");
		return s;
	}

	/** Light-years from the colony down the route and home again. */
	protected static float routeLY(MarketAPI colony, List<String> route) {
		StarSystemAPI home = colony.getStarSystem();
		if (home == null) return 0f;
		float ly = 0f;
		StarSystemAPI at = home;
		for (String id : route) {
			StarSystemAPI next = ThreatScoutRoute.systemById(id);
			if (next == null) continue;
			ly += ThreatFuel.ly(at, next);
			at = next;
		}
		return ly + ThreatFuel.ly(at, home);
	}
}
