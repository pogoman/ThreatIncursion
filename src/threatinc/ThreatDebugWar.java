package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.RemnantHostileActivityFactor;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * Debug: the Instant War switch (threatinc_debugInstantWar). Latched like the
 * RESET switch - fires once per toggle-on, on the first incursion poll after
 * the incursion has started (the switch is itself a start trigger) - and
 * stands up the war a fresh save would otherwise take years to reach:
 *
 *  - a connected network of hive systems: a home system with its full
 *    production chain founded at once, plus single-colony footholds, every
 *    system within linkLY of another hive system and a quota of them within
 *    coreLY of a core world (a size 6+ colony, ThreatWarBoard.distanceToCore);
 *  - each colony founded mature (configurable sizes), its industries planned
 *    to completion and its garrison fabricated in full;
 *  - every faction that owns a colony mobilised (ThreatWarState.mobilise);
 *    the player's faction excepted - that stays their own button on the board.
 *
 * Placement is a greedy growth from a home chosen on the fringe, but near
 * enough to reach the core zone in the hops the plan has to spare: each step
 * takes a candidate within linkLY of the network, heading for the core until
 * the near-core quota is met and stretching outward at random after. Several
 * homes are tried and the plan meeting the most of its targets is kept.
 * Hive systems already founded (an older save) count toward the totals and
 * seed the network; a seeded-but-unfounded system is dropped and re-placed.
 *
 * docs/testing-harness.md, "Instant war".
 */
public class ThreatDebugWar {

	public static final String KEY_LATCH = "threatinc_instantWarLatched";
	/** The floor size last applied by pollFloor, so a setting fires once. */
	public static final String KEY_FLOOR_APPLIED = "threatinc_hiveFloorApplied";

	/** Home candidates tried before settling for the best plan found. */
	protected static final int PLAN_ATTEMPTS = 12;
	/** Planner passes per colony when standing its industries up at once. */
	protected static final int PLAN_ROUNDS = 12;

	/** From IncursionManager.advance, once the incursion has started. */
	public static void poll(IncursionManager manager, Random random) {
		if (!ThreatIncConfig.debugInstantWar()) {
			Global.getSector().getPersistentData().remove(KEY_LATCH);
			return;
		}
		if (Boolean.TRUE.equals(Global.getSector().getPersistentData().get(KEY_LATCH))) return;
		Global.getSector().getPersistentData().put(KEY_LATCH, true);
		try {
			apply(manager, random);
		} catch (Throwable t) {
			Global.getLogger(ThreatDebugWar.class).error("[ThreatInc] Instant war failed", t);
			MessageIntel msg = new MessageIntel("Debug: instant war failed - see starsector.log.",
					Misc.getNegativeHighlightColor());
			IncursionManager.setThreatIcon(msg);
			Global.getSector().getCampaignUI().addMessage(msg);
		}
	}

	/**
	 * Hive Floor Size (threatinc_debugHiveFloorSize, 0 = off): every live hive
	 * colony below the floor is grown up to it through the vitality engine's
	 * own growth step (ThreatColonyManager.growColony - size, planner, the
	 * announcements), then the new industries are stood up together the way
	 * the instant war does. Fires once per value: a floor of 4 applied once
	 * stays applied, and a later 5 fires again. Added 2026-09-09 to catch a
	 * save up after the bootstrap deadlock froze its hive for a year.
	 */
	public static void pollFloor() {
		int floor = ThreatIncConfig.debugHiveFloorSize();
		if (floor <= 0) {
			Global.getSector().getPersistentData().remove(KEY_FLOOR_APPLIED);
			return;
		}
		Object applied = Global.getSector().getPersistentData().get(KEY_FLOOR_APPLIED);
		if (applied instanceof Integer && (Integer) applied == floor) return;
		Global.getSector().getPersistentData().put(KEY_FLOOR_APPLIED, floor);
		try {
			int grown = 0;
			List<MarketAPI> touched = new ArrayList<MarketAPI>();
			for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
				int cap = Math.min(ThreatIncConfig.colonyMaxSize(), Misc.getMaxMarketSize(market));
				boolean any = false;
				while (market.getSize() < floor && market.getSize() < cap) {
					if (!ThreatColonyManager.growColony(market, cap)) break;
					grown++;
					any = true;
				}
				if (any) touched.add(market);
			}
			for (int round = 0; round < PLAN_ROUNDS && !touched.isEmpty(); round++) {
				boolean changed = false;
				for (MarketAPI market : touched) {
					Set<String> before = industryIds(market);
					ThreatColonyManager.planHiveEconomy(market);
					if (!industryIds(market).equals(before)) changed = true;
				}
				ThreatColonyManager.flushEconomy();
				if (!changed) break;
			}
			Global.getLogger(ThreatDebugWar.class).info("[ThreatInc] Hive floor " + floor + ": "
					+ grown + " growth steps over " + touched.size() + " colonies");
			MessageIntel msg = new MessageIntel("Debug: hive floor " + floor + " - " + grown
					+ " growth steps over " + touched.size() + " colonies.",
					Misc.getNegativeHighlightColor());
			IncursionManager.setThreatIcon(msg);
			Global.getSector().getCampaignUI().addMessage(msg);
		} catch (Throwable t) {
			Global.getLogger(ThreatDebugWar.class).error("[ThreatInc] Hive floor failed", t);
		}
	}

	protected static void apply(IncursionManager manager, Random random) {
		int total = between(random, ThreatIncConfig.debugInstantWarSystemsMin(),
				ThreatIncConfig.debugInstantWarSystemsMax());
		int nearCore = Math.min(total, between(random, ThreatIncConfig.debugInstantWarCoreMin(),
				ThreatIncConfig.debugInstantWarCoreMax()));
		int nearCoreCap = Math.max(nearCore, ThreatIncConfig.debugInstantWarCoreMax());
		float linkLY = ThreatIncConfig.debugInstantWarLinkLY();
		float coreLY = ThreatIncConfig.debugInstantWarCoreLY();

		// what stands already: founded systems (and any with a swarm in
		// flight) seed the network; bare seeds are this plan's to re-place,
		// the never-founded home included
		List<StarSystemAPI> held = new ArrayList<StarSystemAPI>();
		for (String systemId : new ArrayList<String>(ThreatIncData.stages().keySet())) {
			StarSystemAPI system = manager.getSystem(systemId);
			List<String> colonies = ThreatIncData.colonyMarkets().get(systemId);
			boolean founded = colonies != null && !colonies.isEmpty();
			boolean inbound = ThreatIncData.waveTargets().containsValue(systemId);
			if (system == null || (!founded && !inbound)) {
				ThreatIncData.clearSystem(systemId);
				ThreatIncData.bootstrapSeeds().remove(systemId);
				if (ThreatIncData.isOGSystem(systemId)) {
					Global.getSector().getPersistentData().remove(ThreatIncData.KEY_OG_SYSTEM);
				}
				continue;
			}
			held.add(system);
		}

		Plan plan;
		if (ThreatIncData.getOGSystem() == null) {
			plan = bestPlan(manager, random, held, total, nearCore, nearCoreCap, linkLY, coreLY);
		} else {
			plan = grow(manager, random, held, null, total, nearCore, nearCoreCap, linkLY, coreLY);
		}
		if (plan == null) {
			// no system can host the chain: nothing to found, but the factions
			// still mobilise
			Global.getLogger(ThreatDebugWar.class).info("[ThreatInc] Instant war: no viable home system");
			plan = new Plan();
		}

		// found - the home chain first, its refinery and forge feed everyone
		int homeSize = ThreatIncConfig.debugInstantWarHomeSize();
		int size = ThreatIncConfig.debugInstantWarColonySize();
		List<MarketAPI> founded = new ArrayList<MarketAPI>();
		for (StarSystemAPI system : plan.systems) {
			boolean home = system == plan.home;
			if (home) {
				ThreatIncData.setOGSystem(system.getId());
				ThreatIncData.setUsesRareEconomy(
						ThreatColonyManager.systemHasDeposit(system, Commodities.RARE_ORE));
			}
			List<PlanetAPI> planets = new ArrayList<PlanetAPI>();
			if (home) {
				planets.addAll(ThreatColonyManager.pickChainPlanets(system));
			} else {
				PlanetAPI planet = ThreatColonyManager.pickColonyPlanet(system);
				if (planet != null) planets.add(planet);
			}
			int before = founded.size();
			for (PlanetAPI planet : planets) {
				if (planet.getMarket() == null || !planet.getMarket().isPlanetConditionMarketOnly()) continue;
				MarketAPI market = ThreatColonyManager.foundColony(planet, home ? homeSize : size);
				if (market == null) continue;
				// the bookkeeping a Seeding Swarm's planetfall does
				// (ThreatColonyManager.checkWaveArrivals), minus the swarm
				ThreatIncData.setStage(system.getId(), ThreatIncData.STAGE_COLONY);
				ThreatIncData.colonyMarketsFor(system.getId()).add(market.getId());
				ThreatIncData.setGrowthTime(market.getId());
				ThreatIncData.decivTargets().remove(planet.getId());
				founded.add(market);
			}
			if (founded.size() > before) {
				ThreatIncData.bootstrapSeeds().remove(system.getId());
				ThreatIncData.markDiscovered(system.getId());
			}
			Global.getLogger(ThreatDebugWar.class).info("[ThreatInc] Instant war: "
					+ (home ? "home " : "") + system.getName() + ", "
					+ (founded.size() - before) + " colonies, core "
					+ Math.round(ThreatWarBoard.distanceToCore(system)) + " LY");
		}

		// stand the industries up at once: the planner adds one per call and
		// reads the hive it can see, so alternate planning with recomputes
		for (int round = 0; round < PLAN_ROUNDS; round++) {
			boolean changed = false;
			for (MarketAPI market : founded) {
				Set<String> before = industryIds(market);
				ThreatColonyManager.planHiveEconomy(market);
				if (!industryIds(market).equals(before)) changed = true;
			}
			ThreatColonyManager.flushEconomy();
			if (!changed) break;
		}
		ThreatColonyManager.applyHiveAccessibility();
		ThreatColonyManager.markEconomyDirty();
		ThreatColonyManager.flushEconomy();

		int swarms = 0;
		for (MarketAPI market : founded) {
			swarms += ThreatColonyManager.fillGarrisonNow(market, random);
		}

		int mobilised = mobiliseAll();
		ThreatIncursionIntel.ensureAdded();

		String text = "Debug: instant war. " + founded.size() + " hive colonies in "
				+ plan.systems.size() + " systems (" + plan.nearCore + " near the core), "
				+ swarms + " Defense Swarms, " + mobilised + " factions mobilised.";
		Global.getLogger(ThreatDebugWar.class).info("[ThreatInc] " + text
				+ " Phase " + IncursionManager.getPhase() + ".");
		MessageIntel msg = new MessageIntel(text, Misc.getNegativeHighlightColor());
		IncursionManager.setThreatIcon(msg);
		Global.getSector().getCampaignUI().addMessage(msg);
	}

	// ------------------------------------------------------------------
	// placement
	// ------------------------------------------------------------------

	protected static class Plan {
		StarSystemAPI home;
		/** Systems to found, in order; includes the home when one is placed. */
		List<StarSystemAPI> systems = new ArrayList<StarSystemAPI>();
		/** Hive systems near the core once the plan stands, held ones included. */
		int nearCore;
		/** Hive systems once the plan stands, held ones included. */
		int size;

		int score(int quota) {
			return Math.min(nearCore, quota) * 1000 + size;
		}
	}

	/**
	 * Tries several homes and keeps the plan meeting the most of its targets.
	 * A home must be able to support the full chain, and must sit close enough
	 * to the core zone that the hops beyond the near-core quota can bridge it;
	 * within that budget the deep fringe is preferred, as for a normal start.
	 */
	protected static Plan bestPlan(IncursionManager manager, Random random, List<StarSystemAPI> held,
			int total, int nearCore, int nearCoreCap, float linkLY, float coreLY) {
		int hops = Math.max(1, total - nearCore);
		float budgetLY = coreLY + hops * linkLY;
		WeightedRandomPicker<StarSystemAPI> homes = new WeightedRandomPicker<StarSystemAPI>(random);
		WeightedRandomPicker<StarSystemAPI> anyHome = new WeightedRandomPicker<StarSystemAPI>(random);
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (!isCandidate(manager, system)) continue;
			if (!ThreatColonyManager.canSupportFullChain(system)) continue;
			float d = manager.distanceToNearestInhabited(system);
			if (d <= 0) continue;
			anyHome.add(system, d * d);
			float dCore = ThreatWarBoard.distanceToCore(system);
			if (dCore >= 0 && dCore <= budgetLY) homes.add(system, d * d);
		}
		if (homes.isEmpty()) homes = anyHome;

		Plan best = null;
		for (int attempt = 0; attempt < PLAN_ATTEMPTS && !homes.isEmpty(); attempt++) {
			StarSystemAPI home = homes.pickAndRemove();
			Plan plan = grow(manager, random, held, home, total, nearCore, nearCoreCap, linkLY, coreLY);
			if (best == null || plan.score(nearCore) > best.score(nearCore)) best = plan;
			if (best.nearCore >= nearCore && best.size >= total) break;
		}
		return best;
	}

	/**
	 * Greedy growth from the held systems plus the home: each step adds a
	 * candidate within linkLY of the network - toward the core until the
	 * near-core quota is met, then outward (the farther from the network the
	 * likelier, so the hive spreads instead of clumping), keeping out of the
	 * core zone once its ceiling is reached unless nothing else is reachable.
	 */
	protected static Plan grow(IncursionManager manager, Random random, List<StarSystemAPI> held,
			StarSystemAPI home, int total, int nearCore, int nearCoreCap, float linkLY, float coreLY) {
		Plan plan = new Plan();
		plan.home = home;
		List<StarSystemAPI> network = new ArrayList<StarSystemAPI>(held);
		if (home != null) {
			network.add(home);
			plan.systems.add(home);
		}
		int coreCount = 0;
		for (StarSystemAPI system : network) {
			if (nearCore(system, coreLY)) coreCount++;
		}

		while (network.size() < total) {
			List<StarSystemAPI> frontier = new ArrayList<StarSystemAPI>();
			for (StarSystemAPI system : Global.getSector().getStarSystems()) {
				if (network.contains(system) || !isCandidate(manager, system)) continue;
				if (network.isEmpty() || distanceTo(network, system) <= linkLY) frontier.add(system);
			}
			if (frontier.isEmpty()) break;

			StarSystemAPI pick = null;
			if (coreCount < nearCore) {
				// head for the core: a system inside the zone if one is in
				// reach, else the step that closes the most distance
				List<StarSystemAPI> inZone = new ArrayList<StarSystemAPI>();
				for (StarSystemAPI system : frontier) {
					if (nearCore(system, coreLY)) inZone.add(system);
				}
				if (!inZone.isEmpty()) {
					pick = inZone.get(random.nextInt(inZone.size()));
				} else {
					float best = Float.MAX_VALUE;
					for (StarSystemAPI system : frontier) {
						float d = ThreatWarBoard.distanceToCore(system);
						if (d >= 0 && d < best) {
							best = d;
							pick = system;
						}
					}
				}
			} else {
				WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>(random);
				boolean keepOut = coreCount >= nearCoreCap;
				for (StarSystemAPI system : frontier) {
					if (keepOut && nearCore(system, coreLY)) continue;
					float d = distanceTo(network, system);
					picker.add(system, (1f + d) * (1f + d));
				}
				if (!picker.isEmpty()) pick = picker.pick();
			}
			if (pick == null) pick = frontier.get(random.nextInt(frontier.size()));

			network.add(pick);
			plan.systems.add(pick);
			if (nearCore(pick, coreLY)) coreCount++;
		}
		plan.nearCore = coreCount;
		plan.size = network.size();
		return plan;
	}

	/** The spread's own candidate rule, minus systems a live Remnant Nexus defends. */
	protected static boolean isCandidate(IncursionManager manager, StarSystemAPI system) {
		if (!manager.isValidSpreadCandidate(system)) return false;
		if (ThreatIncConfig.remnantResists()
				&& RemnantHostileActivityFactor.getRemnantNexus(system) != null) return false;
		return true;
	}

	protected static boolean nearCore(StarSystemAPI system, float coreLY) {
		float d = ThreatWarBoard.distanceToCore(system);
		return d >= 0 && d <= coreLY;
	}

	/** Light-years from the system to the nearest member of the network. */
	protected static float distanceTo(List<StarSystemAPI> network, StarSystemAPI system) {
		float best = Float.MAX_VALUE;
		for (StarSystemAPI other : network) {
			float d = Misc.getDistanceLY(other.getLocation(), system.getLocation());
			if (d < best) best = d;
		}
		return best;
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/**
	 * Every faction owning a colony goes to war footing, the player's excepted
	 * (mobilising is their choice, on the board). Returns how many did.
	 */
	protected static int mobiliseAll() {
		Set<String> owners = new LinkedHashSet<String>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (market.isPlayerOwned() || market.getFactionId() == null) continue;
			owners.add(market.getFactionId());
		}
		int count = 0;
		for (String id : owners) {
			FactionAPI faction = Global.getSector().getFaction(id);
			if (faction == null || faction.isPlayerFaction() || faction.isNeutralFaction()) continue;
			if (Factions.THREAT.equals(id) || ThreatWarState.isAtWar(id)) continue;
			if (ThreatWarState.mobilise(faction, "debug: instant war") != null) count++;
		}
		return count;
	}

	protected static Set<String> industryIds(MarketAPI market) {
		Set<String> ids = new LinkedHashSet<String>();
		for (Industry industry : market.getIndustries()) {
			ids.add(industry.getId());
		}
		return ids;
	}

	protected static int between(Random random, int min, int max) {
		int lo = Math.min(min, max);
		int hi = Math.max(min, max);
		return lo + random.nextInt(hi - lo + 1);
	}
}
