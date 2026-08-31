package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI.SurveyLevel;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.impl.campaign.econ.ResourceDepositsCondition;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.impl.campaign.population.PopulationComposition;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;
import com.fs.starfarer.api.util.Misc;

/**
 * Colony-side machinery of the incursion: founding real Threat markets on
 * planets, growing them, planning the shared hive economy (all colonies live
 * in one isolated econ group and genuinely trade with each other), and
 * maintaining the Defense Swarm garrisons that gate bombardment.
 *
 * A system can hold several colonies - the swarm expands onto every
 * resource-bearing planet it can reach, one wave at a time.
 *
 * Stateless - all state lives in ThreatIncData; IncursionManager drives these
 * from its poll/tick cadence.
 */
public class ThreatColonyManager {

	/** Shared econ group: the hive's closed internal economy. */
	public static final String ECON_GROUP = "threatinc_hive";

	public static final String COLONY_FLAG = "$threatinc_colony";
	public static final String GARRISON_FLAG = "$threatinc_garrison";
	public static final String WAVE_FLAG = "$threatinc_colonyFleet";

	public static final String STABILITY_MOD_ID = "threatinc_machine";

	/**
	 * Supply-mod id of the RETIRED population-machinery mechanism, kept only so
	 * ensureFabricationCore can strip it from markets in older saves.
	 */
	public static final String MACHINERY_SUPPLY_ID = "threatinc_pop_machinery";

	/** Industry id of the Fabrication Core structure (data/campaign/industries.csv). */
	public static final String FABRICATION_CORE = "threatinc_fabricationcore";
	/** The military structure: fabricates Defense Swarms, stages expeditions. */
	public static final String SWARM_NEXUS = "threatinc_swarmnexus";
	/** Marine-free ground defenses running on machinery+metals (ThreatGroundDefenses). */
	public static final String THREAT_GROUND_DEFENSES = "threatinc_grounddefenses";
	public static final String THREAT_HEAVY_BATTERIES = "threatinc_heavybatteries";

	// ------------------------------------------------------------------
	// founding
	// ------------------------------------------------------------------

	/**
	 * Converts a planet's dormant condition-only market into a live Threat
	 * fabrication colony. The verified vanilla recipe; order matters in a few
	 * places (econ group before addMarket, planet faction after).
	 */
	public static MarketAPI foundColony(PlanetAPI planet, int initialSize) {
		MarketAPI market = planet.getMarket();
		if (market == null) return null;

		market.setPlanetConditionMarketOnly(false);
		market.setFactionId(Factions.THREAT);
		market.setSize(initialSize);
		for (int i = 0; i <= 10; i++) {
			market.removeCondition("population_" + i);
		}
		market.addCondition("population_" + initialSize);
		// without a fresh incoming population the colony can insta-grow on the
		// next economy pass
		market.setIncoming(new PopulationComposition());

		market.addIndustry(Industries.POPULATION);
		// no spaceport means -100% accessibility, no in-group shipping, and no
		// supply convoys - the hive economy needs its ports
		market.addIndustry(Industries.SPACEPORT);

		market.setSurveyLevel(SurveyLevel.FULL);
		for (MarketConditionAPI cond : market.getConditions()) {
			cond.setSurveyed(true);
		}
		// conversion case: the machines don't inherit the ruins' status, though
		// the ruins themselves (and any leftover hazards) remain
		market.removeCondition(Conditions.DECIVILIZED);

		// one shared group = a real, closed hive economy: colonies supply each
		// other and starve together when the network is cut
		market.setEconGroup(ECON_GROUP);
		// no stockpile cushioning: severed supply chains bite immediately
		market.setUseStockpilesForShortages(false);
		// keep procurement/analysis missions from pointing at hive worlds
		market.setInvalidMissionTarget(true);

		market.getMemoryWithoutUpdate().set(DecivTracker.NO_DECIV_KEY, true);
		market.getMemoryWithoutUpdate().set(COLONY_FLAG, true);

		// keep vanilla ambient/trade fleet spawners off hive colonies - academy
		// shuttles, pilgrims, mercs, generic trade convoys. The internal hive
		// economy still simulates fully (availability is computed regardless of
		// whether convoys actually fly); this just stops out-of-character
		// civilian fleets sourcing from a machine-swarm world
		SharedData.getData().getMarketsWithoutTradeFleetSpawn().add(market.getId());

		// machine order: no unrest, ever - shortages still bite through the
		// industry deficit multipliers and the ship-hull fleet size mult
		market.getStability().modifyFlat(STABILITY_MOD_ID, 10f, "Machine hive-order");

		int cap = ThreatIncConfig.colonyMaxSize();
		if (cap > Misc.MAX_COLONY_SIZE) {
			market.getStats().getDynamic().getMod(Stats.MAX_MARKET_SIZE)
					.modifyFlat("threatinc", cap - Misc.MAX_COLONY_SIZE);
		}

		Global.getSector().getEconomy().addMarket(market, true);

		// after addMarket: planet ownership/map color
		planet.setFaction(Factions.THREAT);
		// DecivTracker.decivilize silently no-ops on discoverable entities, and
		// fringe procgen planets start discoverable - clear it or the colony
		// could never be bombarded out of existence
		planet.setDiscoverable(null);
		planet.setDiscoveryXP(null);

		ensureFabricationCore(market);
		planHiveEconomy(market);

		return market;
	}

	/**
	 * Best planet for a system's first colony. Machines don't care about
	 * hazard, weather, or farmland - only what can be fed into the
	 * fabricators. Score is purely the planet's resource deposits (count and
	 * richness); a gas giant dripping with volatiles is as good a home as any
	 * terran world.
	 */
	public static PlanetAPI pickColonyPlanet(StarSystemAPI system) {
		PlanetAPI best = null;
		float bestScore = -Float.MAX_VALUE;
		for (PlanetAPI planet : system.getPlanets()) {
			if (!isColonizable(planet)) continue;

			float score = depositScore(planet);
			if (score > bestScore) {
				bestScore = score;
				best = planet;
			}
		}
		return best;
	}

	/**
	 * A further planet worth claiming in an already-colonized system: it must
	 * actually have resource deposits (the swarm doesn't waste waves on barren
	 * rock it already effectively controls) and no wave already inbound.
	 * Planets bearing what the hive is actually SHORT of score far higher -
	 * a rare-ore-starved hive grabs the rare world first.
	 */
	public static PlanetAPI pickExpansionPlanet(StarSystemAPI system) {
		Map<String, Integer> needs = groupMineableDeficits();
		// a strained hive claims only planets that relieve its shortfalls -
		// no generic land-grabs while every colony is starving
		boolean strainedHive = !anyNominalColony();
		PlanetAPI best = null;
		float bestScore = 0f; // strictly positive: deposits required
		for (PlanetAPI planet : system.getPlanets()) {
			if (!isColonizable(planet)) continue;
			if (ThreatIncData.waveFleets().containsKey(planet.getId())) continue;

			float need = needBonus(planet, needs);
			if (strainedHive && need <= 0f) continue;

			float score = depositScore(planet) + need;
			if (score > bestScore) {
				bestScore = score;
				best = planet;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// deficit-driven expansion: the hive colonizes what it lacks
	// ------------------------------------------------------------------

	/** The raw inputs the swarm can chase by colonizing deposit worlds. */
	protected static final String[] MINEABLE_INPUTS = {
			Commodities.ORE, Commodities.RARE_ORE, Commodities.VOLATILES };

	/**
	 * The hive's sector-wide shortfall of each mineable input, in econ units
	 * (vanilla availability vs demand, summed across all colonies). Any
	 * surplus a new deposit colony mines flows to the starved colonies through
	 * the shared econ group - accessibility-mediated, pure vanilla trade - so
	 * these deficits are exactly what new colonization can actually fix.
	 */
	public static Map<String, Integer> groupMineableDeficits() {
		Map<String, Integer> needs = new LinkedHashMap<String, Integer>();
		for (String commodityId : MINEABLE_INPUTS) {
			int total = 0;
			for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
				total += deficitOf(market, commodityId);
			}
			if (total > 0) needs.put(commodityId, total);
		}
		return needs;
	}

	/**
	 * How much this planet's deposits would relieve the hive's current
	 * shortfalls: deficit units times deposit richness, per matching deposit.
	 */
	public static float needBonus(PlanetAPI planet, Map<String, Integer> needs) {
		if (planet.getMarket() == null || needs.isEmpty()) return 0f;
		float bonus = 0f;
		for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
			String commodity = ResourceDepositsCondition.COMMODITY.get(cond.getId());
			if (commodity == null) continue;
			Integer need = needs.get(commodity);
			if (need == null) continue;
			Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
			bonus += need * (3f + (mod != null ? mod : 0)) * 10f;
		}
		return bonus;
	}

	/** Total need-relief a system's colonizable planets offer the hive. */
	public static float systemNeedScore(StarSystemAPI system, Map<String, Integer> needs) {
		if (needs.isEmpty()) return 0f;
		float score = 0f;
		for (PlanetAPI planet : system.getPlanets()) {
			if (!isColonizable(planet)) continue;
			score += needBonus(planet, needs);
		}
		return score;
	}

	protected static boolean isColonizable(PlanetAPI planet) {
		if (planet.isStar()) return false;
		if (planet.getMarket() == null) return false;
		return planet.getMarket().isPlanetConditionMarketOnly();
	}

	protected static float depositScore(PlanetAPI planet) {
		float score = 0f;
		for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
			if (!ResourceDepositsCondition.COMMODITY.containsKey(cond.getId())) continue;
			Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
			score += 30f + (mod != null ? mod * 10f : 0f);
		}
		return score;
	}

	public static boolean systemHasColonizablePlanet(StarSystemAPI system) {
		for (PlanetAPI planet : system.getPlanets()) {
			if (isColonizable(planet)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// the OG home system: a genuinely self-sufficient production base
	// ------------------------------------------------------------------

	/** True if some planet in the system carries a deposit of the commodity. */
	protected static boolean systemHasDeposit(StarSystemAPI system, String commodityId) {
		for (PlanetAPI planet : system.getPlanets()) {
			if (planet.getMarket() == null) continue;
			for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
				if (commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) {
					return true;
				}
			}
		}
		return false;
	}

	/** Total deposit richness across every planet in the system (abundance). */
	protected static float systemDepositWealth(StarSystemAPI system) {
		float wealth = 0f;
		for (PlanetAPI planet : system.getPlanets()) {
			if (planet.getMarket() == null) continue;
			for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
				if (!ResourceDepositsCondition.COMMODITY.containsKey(cond.getId())) continue;
				Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
				wealth += 3f + (mod != null ? mod : 0);
			}
		}
		return wealth;
	}

	public static int countColonizablePlanets(StarSystemAPI system) {
		int count = 0;
		for (PlanetAPI planet : system.getPlanets()) {
			if (isColonizable(planet)) count++;
		}
		return count;
	}

	/**
	 * A system can host the full chain if it has ore, rare ore, and volatiles
	 * deposits and enough planets to spread mining, refining, and heavy industry
	 * across. Rare ore is required so the OG refinery produces rare metals for
	 * the whole faction - every later colony draws them via in-group trade.
	 *
	 * Deposits must be MODERATE or better: a sparse/trace deposit supplies at
	 * size-3 against demands of size or size+2, a home economy that can never
	 * stabilize no matter how it develops - a degenerate start, not a viable OG.
	 */
	public static boolean canSupportFullChain(StarSystemAPI system) {
		return countColonizablePlanets(system) >= 3
				&& bestDepositMod(system, Commodities.ORE) >= 0
				&& bestDepositMod(system, Commodities.RARE_ORE) >= 0
				&& bestDepositMod(system, Commodities.VOLATILES) >= 0;
	}

	/**
	 * Richest deposit modifier for the commodity anywhere in the system
	 * (-1 sparse .. +3 ultrarich), or MIN_VALUE if no deposit at all.
	 */
	protected static int bestDepositMod(StarSystemAPI system, String commodityId) {
		int best = Integer.MIN_VALUE;
		for (PlanetAPI planet : system.getPlanets()) {
			if (planet.getMarket() == null) continue;
			for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
				if (!commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) continue;
				Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
				int m = mod != null ? mod : 0;
				if (m > best) best = m;
			}
		}
		return best;
	}

	/**
	 * The planets to colonize to stand up a complete production chain: the
	 * richest ore world, the richest volatiles world, and additional planets
	 * to host refining and heavy industry - up to four, distinct.
	 */
	public static List<PlanetAPI> pickChainPlanets(StarSystemAPI system) {
		List<PlanetAPI> chosen = new ArrayList<PlanetAPI>();

		// cover every deposit the chain needs; one planet often carries several,
		// so this may resolve to a single rich world or several specialized ones
		for (String deposit : new String[] {
				Commodities.ORE, Commodities.RARE_ORE, Commodities.VOLATILES }) {
			PlanetAPI best = bestPlanetForDeposit(system, deposit);
			if (best != null && !chosen.contains(best)) chosen.add(best);
		}

		// industrial worlds for refining + heavy industry: prefer bare planets
		// so rich deposit worlds aren't spent as forge sites
		List<PlanetAPI> byLeanFirst = new ArrayList<PlanetAPI>();
		for (PlanetAPI planet : system.getPlanets()) {
			if (isColonizable(planet)) byLeanFirst.add(planet);
		}
		sortByDepositScoreAscending(byLeanFirst);
		for (PlanetAPI planet : byLeanFirst) {
			if (chosen.size() >= 5) break;
			if (!chosen.contains(planet)) chosen.add(planet);
		}
		return chosen;
	}

	protected static PlanetAPI bestPlanetForDeposit(StarSystemAPI system, String commodityId) {
		PlanetAPI best = null;
		float bestMod = -Float.MAX_VALUE;
		for (PlanetAPI planet : system.getPlanets()) {
			if (!isColonizable(planet)) continue;
			for (MarketConditionAPI cond : planet.getMarket().getConditions()) {
				if (!commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) continue;
				Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
				float m = mod != null ? mod : 0;
				if (m > bestMod) {
					bestMod = m;
					best = planet;
				}
			}
		}
		return best;
	}

	protected static void sortByDepositScoreAscending(List<PlanetAPI> planets) {
		java.util.Collections.sort(planets, new java.util.Comparator<PlanetAPI>() {
			public int compare(PlanetAPI a, PlanetAPI b) {
				return Float.compare(depositScore(a), depositScore(b));
			}
		});
	}

	/** Launches the whole OG chain at once: one bootstrap swarm per chain planet. */
	public static void launchOGChain(StarSystemAPI system, Random random) {
		for (PlanetAPI planet : pickChainPlanets(system)) {
			// skip planets already colonized or already inbound
			if (planet.getMarket() != null && !planet.getMarket().isPlanetConditionMarketOnly()) continue;
			if (ThreatIncData.waveFleets().containsKey(planet.getId())) continue;
			launchColonizationWave(null, system, planet, random);
		}
	}

	protected static boolean hasMiningDeposits(MarketAPI market) {
		for (MarketConditionAPI cond : market.getConditions()) {
			String commodity = ResourceDepositsCondition.COMMODITY.get(cond.getId());
			if (commodity == null) continue;
			if (Industries.MINING.equals(ResourceDepositsCondition.INDUSTRY.get(commodity))) {
				return true;
			}
		}
		return false;
	}

	protected static boolean hasVolatilesDeposits(MarketAPI market) {
		for (MarketConditionAPI cond : market.getConditions()) {
			if (Commodities.VOLATILES.equals(
					ResourceDepositsCondition.COMMODITY.get(cond.getId()))) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// the hive planner
	// ------------------------------------------------------------------

	/**
	 * Adds at most one industry/structure to the colony, chosen by what the
	 * hive economy as a whole is missing. Called at founding and after each
	 * growth step, so the network develops organically: mining worlds where
	 * the rocks are, one refinery chain, a forge world building the ships.
	 */
	public static void planHiveEconomy(MarketAPI market) {
		int size = market.getSize();

		// accessibility IS the swarm's supply line: it gates every in-group
		// import and the fuel range every wave and strike is launched on, so a
		// Megaport lifts the whole hive at once - a starving frontier colony
		// fed better, and the network's reach extended. Upgrade the founding
		// Spaceport as soon as the colony is established enough to warrant it,
		// ahead of any production build (structures don't take industry slots).
		if (tryUpgradeMegaport(market)) return;

		// defensive structures don't take industry slots. The hive builds its
		// own marine-free variants (ThreatGroundDefenses: machinery+metals)
		if (size >= 6 && market.hasIndustry(THREAT_GROUND_DEFENSES)) {
			market.removeIndustry(THREAT_GROUND_DEFENSES, null, true);
			market.addIndustry(THREAT_HEAVY_BATTERIES);
			return;
		}
		if (size >= 3 && !market.hasIndustry(THREAT_GROUND_DEFENSES)
				&& !market.hasIndustry(THREAT_HEAVY_BATTERIES)) {
			market.addIndustry(THREAT_GROUND_DEFENSES);
			return;
		}

		if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) return;

		// mine what the planet offers (Mining supplies nothing without deposits)
		if (hasMiningDeposits(market) && !market.hasIndustry(Industries.MINING)) {
			market.addIndustry(Industries.MINING);
			ThreatIncConfig.log("Hive planner: MINING at " + market.getName());
			return;
		}

		int refineries = groupCountIndustry(Industries.REFINING);
		int forges = groupCountIndustry(Industries.HEAVYINDUSTRY)
				+ groupCountIndustry(Industries.ORBITALWORKS);

		// bootstrap the basic chain: first refinery, first forge, first fuel plant
		if (refineries == 0 && groupHasIndustry(Industries.MINING)) {
			if (!market.hasIndustry(Industries.REFINING)) {
				market.addIndustry(Industries.REFINING);
				ThreatIncConfig.log("Hive planner: REFINING (first) at " + market.getName());
			}
			return;
		}
		if (forges == 0) {
			if (!market.hasIndustry(Industries.HEAVYINDUSTRY)) {
				market.addIndustry(Industries.HEAVYINDUSTRY);
				ThreatIncConfig.log("Hive planner: HEAVYINDUSTRY (first) at " + market.getName());
			}
			return;
		}
		if (!groupHasIndustry(Industries.FUELPROD) && groupHasVolatiles()) {
			if (!market.hasIndustry(Industries.FUELPROD)) {
				market.addIndustry(Industries.FUELPROD);
				ThreatIncConfig.log("Hive planner: FUELPROD at " + market.getName());
			}
			return;
		}

		// upgrade an established forge to orbital works for better hulls - improves
		// output/quality without changing the forge count (keeps the balance intact)
		if (size >= 6 && market.hasIndustry(Industries.HEAVYINDUSTRY)) {
			market.removeIndustry(Industries.HEAVYINDUSTRY, null, true);
			market.addIndustry(Industries.ORBITALWORKS);
			announce("The fabrication colony on " + market.getName()
					+ " has restructured itself into a forge world. Hull output from the "
					+ "swarm's shipyards there is accelerating.",
					Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Hive planner: ORBITALWORKS at " + market.getName());
			return;
		}

		// scale the chain, keeping refineries >= forges. A size-N refinery feeds a
		// size-N forge, so a forge added without a matching refinery just starves
		// the whole group of metals. When the two are balanced, upscale the
		// resource side (refining) FIRST - metals supply leads, never trails, demand.
		if (forges >= refineries) {
			if (!market.hasIndustry(Industries.REFINING)) {
				market.addIndustry(Industries.REFINING);
				ThreatIncConfig.log("Hive planner: REFINING (scale) at " + market.getName());
			}
		} else {
			if (!market.hasIndustry(Industries.HEAVYINDUSTRY)
					&& !market.hasIndustry(Industries.ORBITALWORKS)) {
				market.addIndustry(Industries.HEAVYINDUSTRY);
				ThreatIncConfig.log("Hive planner: HEAVYINDUSTRY (scale) at " + market.getName());
			}
		}
	}

	/**
	 * Upgrades a colony's founding Spaceport to a Megaport once it reaches the
	 * configured size, for the accessibility it buys the whole hive economy.
	 * Idempotent: a no-op below the gate size, or once the Megaport is in place.
	 * Gated by size on purpose - the accessibility ramp on young colonies is the
	 * mod's throttle on expansion, so fringe seeds still ramp naturally.
	 *
	 * @return true if it performed the upgrade this call
	 */
	public static boolean tryUpgradeMegaport(MarketAPI market) {
		if (market == null) return false;
		if (market.getSize() < ThreatIncConfig.colonyMegaportMinSize()) return false;
		if (market.hasIndustry(Industries.MEGAPORT)) return false;
		if (!market.hasIndustry(Industries.SPACEPORT)) return false;
		market.removeIndustry(Industries.SPACEPORT, null, true);
		market.addIndustry(Industries.MEGAPORT);
		ThreatIncConfig.log("Hive planner: MEGAPORT at " + market.getName());
		return true;
	}

	/**
	 * Sweep every live colony for a pending Megaport upgrade. The planner only
	 * runs at founding and on growth steps, so without this an already-grown
	 * colony - especially a size-capped one that never grows again - would never
	 * upgrade after the setting changed or the feature was added mid-save.
	 */
	public static void maintainMegaports() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			tryUpgradeMegaport(market);
		}
	}

	// ------------------------------------------------------------------
	// hive accessibility: the swarm doesn't trade through the human Core
	// ------------------------------------------------------------------

	public static final String ACCESS_MOD_ID = "threatinc_coredist";

	/**
	 * Vanilla docks a market's accessibility by its distance from the economy's
	 * centre of mass - a size-weighted centroid that the many large Core worlds
	 * pull to the Core. That penalty models dependence on the Core trade hub:
	 * the further out you are, the harder imports are to come by.
	 *
	 * The hive has no such dependence. It is a closed econ group, hostile to
	 * everyone, pulled from the trade-fleet network - every commodity it
	 * receives comes from its own colonies, never from the Core. Charging it a
	 * Core-distance penalty models a supply line that does not exist, and it
	 * cripples exactly the fringe colonies the swarm is built to seed. So we
	 * cancel that one component, restoring it as a flat accessibility bonus.
	 *
	 * Only the Core-distance term is cancelled. The same-faction proximity /
	 * isolation term stays untouched (it is folded into the same base value but
	 * computed the opposite way), so the hive's OWN internal geometry still
	 * matters: colonies clustered near their siblings stay well-supplied, a lone
	 * seed flung far from the rest of the swarm still starves. What changes is
	 * only that the yardstick is the hive's network, not the human Core's.
	 */
	public static void applyHiveAccessibility() {
		float fraction = ThreatIncConfig.coreDistanceOffset();
		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		if (colonies.isEmpty()) return;

		if (fraction <= 0f) {
			// feature off: make sure no stale bonus lingers from a prior setting
			for (MarketAPI market : colonies) {
				market.getAccessibilityMod().unmodifyFlat(ACCESS_MOD_ID);
			}
			return;
		}

		Vector2f com = economyCenterOfMass();
		if (com == null) return;
		// vanilla: accessibility loses 1.0 per this many LY from the COM
		float lyPerUnit = Global.getSettings().getFloat("accessibilityDistFromCOM");
		if (lyPerUnit <= 0f) return;

		for (MarketAPI market : colonies) {
			float dist = Misc.getDistanceLY(market.getLocationInHyperspace(), com);
			float penalty = dist / lyPerUnit;
			// clamp so a COM estimate that drifts from vanilla's can never turn
			// this into a runaway accessibility fountain
			float offset = Math.max(0f, Math.min(2f, penalty * fraction));
			market.getAccessibilityMod().modifyFlat(ACCESS_MOD_ID, offset,
					"Hive network (Core distance not applicable)");
		}
	}

	/**
	 * Size-weighted centroid of the sector's inhabited markets, in hyperspace
	 * coordinates - our reconstruction of the figure vanilla docks accessibility
	 * against. Dominated by the numerous large Core worlds, so it lands in the
	 * Core regardless of small weighting differences from vanilla's own.
	 */
	protected static Vector2f economyCenterOfMass() {
		float sx = 0f, sy = 0f, weight = 0f;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.isPlanetConditionMarketOnly()) continue;
			Vector2f loc = market.getLocationInHyperspace();
			if (loc == null) continue;
			float w = market.getSize();
			sx += loc.x * w;
			sy += loc.y * w;
			weight += w;
		}
		if (weight <= 0f) return null;
		return new Vector2f(sx / weight, sy / weight);
	}

	protected static boolean groupHasIndustry(String industryId) {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.hasIndustry(industryId)) return true;
		}
		return false;
	}

	protected static int groupCountIndustry(String industryId) {
		int count = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.hasIndustry(industryId)) count++;
		}
		return count;
	}

	protected static boolean groupHasVolatiles() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.hasIndustry(Industries.MINING) && hasVolatilesDeposits(market)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// native machinery: the population IS the machines
	// ------------------------------------------------------------------

	/**
	 * A hive colony's population supplies heavy machinery natively, scaling
	 * with colony size. The swarm's "population" is fabricator strata - it IS
	 * machinery - so this is thematic, but it's also what makes the economy
	 * solvable: in the vanilla chain forges are the only machinery source
	 * while mining, refining, and fuel production all consume it, so the chain
	 * is machinery-negative in every configuration and any internal-shortage
	 * growth gate perma-stalls. With population as the base machinery source
	 * the chain becomes acyclic (pop -> machinery -> mining -> ore -> metals ->
	 * hulls), so every other input can still deficit - and gate growth - without
	 * ever deadlocking: a starved consumer's supplier always recovers on its own.
	 *
	 * The supply lives in the Fabrication Core structure (FabricationCore),
	 * whose apply() runs inside every economy recompute - the only place a
	 * supply declaration survives, since BaseIndustry.updateSupplyAndDemand-
	 * Modifiers wipes the supply/demand stats at the start of each pass. (The
	 * previous implementation, a supply mod pushed onto Population from
	 * advance(), lost that race and left the whole hive machinery-starved.)
	 * As a real structure it's also visible in the colony UI and disruptable
	 * by raids like anything else. This ensure is idempotent and doubles as
	 * the migration path for older saves: it also strips the retired
	 * population supply mod.
	 */
	public static void ensureFabricationCore(MarketAPI market) {
		if (market == null) return;
		if (!market.hasIndustry(FABRICATION_CORE)) {
			market.addIndustry(FABRICATION_CORE);
			ThreatIncConfig.log("Fabrication Core added at " + market.getName());
		}
		if (!market.hasIndustry(SWARM_NEXUS)) {
			market.addIndustry(SWARM_NEXUS);
			ThreatIncConfig.log("Swarm Nexus added at " + market.getName());
		}
		// The Fragment Fabricator lives in the FABRICATION CORE - it is the
		// fabrication organ's crown, not the military nexus's. Migrate any
		// nexus-installed fabricator from earlier builds across first.
		Industry coreInd = market.getIndustry(FABRICATION_CORE);
		Industry nexusInd = market.getIndustry(SWARM_NEXUS);
		if (nexusInd != null && nexusInd.getSpecialItem() != null
				&& com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR
						.equals(nexusInd.getSpecialItem().getId())) {
			nexusInd.setSpecialItem(null);
			if (coreInd != null && coreInd.getSpecialItem() == null) {
				coreInd.setSpecialItem(new com.fs.starfarer.api.campaign.SpecialItemData(
						com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR, null));
			}
			ThreatIncConfig.log("Fragment Fabricator moved to Fabrication Core at "
					+ market.getName());
		}
		// seed exactly ONCE per colony (flag survives the theft): once raided
		// out, the colony is permanently bombable
		if (coreInd != null && coreInd.getSpecialItem() == null
				&& !market.getMemoryWithoutUpdate().getBoolean(FABRICATOR_SEEDED_FLAG)) {
			coreInd.setSpecialItem(new com.fs.starfarer.api.campaign.SpecialItemData(
					com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR, null));
			market.getMemoryWithoutUpdate().set(FABRICATOR_SEEDED_FLAG, true);
			ThreatIncConfig.log("Fragment Fabricator installed at " + market.getName());
		}
		// migrate vanilla defensive structures (marine/supplies demands make no
		// sense on a machine hive) to the machinery+metals variants
		if (market.hasIndustry(Industries.GROUNDDEFENSES)) {
			market.removeIndustry(Industries.GROUNDDEFENSES, null, false);
			if (!market.hasIndustry(THREAT_GROUND_DEFENSES)
					&& !market.hasIndustry(THREAT_HEAVY_BATTERIES)) {
				market.addIndustry(THREAT_GROUND_DEFENSES);
			}
			ThreatIncConfig.log("Ground defenses migrated to hive variant at " + market.getName());
		}
		if (market.hasIndustry(Industries.HEAVYBATTERIES)) {
			market.removeIndustry(Industries.HEAVYBATTERIES, null, false);
			if (!market.hasIndustry(THREAT_HEAVY_BATTERIES)) {
				if (market.hasIndustry(THREAT_GROUND_DEFENSES)) {
					market.removeIndustry(THREAT_GROUND_DEFENSES, null, false);
				}
				market.addIndustry(THREAT_HEAVY_BATTERIES);
			}
			ThreatIncConfig.log("Heavy batteries migrated to hive variant at " + market.getName());
		}
		// legacy saves: remove the old population-machinery supply mod
		// (quantity 0 unmodifies; a no-op once gone)
		Industry pop = market.getIndustry(Industries.POPULATION);
		if (pop != null) pop.supply(MACHINERY_SUPPLY_ID, Commodities.HEAVY_MACHINERY, 0, null);

		// Machines eat nothing: suppress ALL of Population & Infrastructure's
		// human demands (food, domestic/luxury goods...) via the mechanism
		// admin skills use - demandReductionFromOther is folded into the
		// industry's OWN apply(), which wins the recompute race an outside
		// write can never win (each industry's declarations bake into the
		// market aggregates as it applies; editing pop's demand store after
		// the fact was tried and changed nothing). The stat is transient -
		// wiped on save load - so this sweep re-asserts it; modifyFlat with a
		// fixed id is idempotent. Sized to the market: pop's largest demand is
		// its size. Crew SUPPLY is untouched - the megaport consumes it.
		if (pop != null) {
			pop.getDemandReductionFromOther().modifyFlat("threatinc_machines",
					market.getSize());
		}
	}

	/** Set once a colony's nexus has received its Fragment Fabricator - the
	 * item is seeded exactly once, so a stolen fabricator stays stolen and the
	 * colony stays permanently bombable. */
	public static final String FABRICATOR_SEEDED_FLAG = "$threatinc_fabricatorSeeded";

	/**
	 * Whether any industry here still holds the Fragment Fabricator - the
	 * organ that screens the colony against orbital bombardment
	 * (ThreatincMarketCMD.bombardMenu). Stolen via ground raid (EXTREME
	 * danger, see SwarmNexus.adjustItemDangerLevel) is the only way it leaves.
	 */
	public static boolean hasFragmentFabricator(MarketAPI market) {
		if (market == null) return false;
		for (Industry ind : market.getIndustries()) {
			if (ind.getSpecialItem() != null
					&& com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR
							.equals(ind.getSpecialItem().getId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the colony's military organ is up: present, undisrupted,
	 * functional. Everything that FABRICATES fleets gates on this - garrison
	 * respawn and strike staging - so raiding or bombing the nexus silences
	 * the colony militarily until it recovers. Fleets already fabricated are
	 * deliberately unaffected.
	 */
	public static boolean hasOperationalNexus(MarketAPI market) {
		if (market == null) return false;
		Industry nexus = market.getIndustry(SWARM_NEXUS);
		return nexus != null && !nexus.isDisrupted() && nexus.isFunctional();
	}

	/** Fast-cadence sweep: migrates older saves and heals any removed core. */
	public static void ensureFabricationCores() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			ensureFabricationCore(market);
		}
	}

	// ------------------------------------------------------------------
	// economic health
	// ------------------------------------------------------------------

	/**
	 * Industrial inputs of the production chain - the raw and intermediate
	 * materiel the swarm's factories actually consume. Deliberately NOT the
	 * population-consumption goods (food, drugs, supplies): the machines don't
	 * grow on colonist comforts, so those permanent frontier shortages must
	 * not throttle expansion. Only a starved *factory* stalls a colony.
	 */
	// core production inputs, always gated. Every one is a real interdiction
	// point - cut any and the colonies that consume it stall, with the damage
	// cascading through the shared economy. None can deadlock the bootstrap:
	// heavy machinery is population-supplied (always locally satisfied, listed
	// here only as a safety net), and volatiles is only ever demanded by a fuel
	// plant that sits on the volatiles world mining it. (Fuel itself can't be
	// gated directly - a spaceport needs fuel from size 3 but a fuel plant needs
	// a size-4 industry slot - so fuel is interdicted at its input, volatiles,
	// and via fleet projection below.)
	protected static final String[] CORE_INPUTS = {
			Commodities.ORE, Commodities.METALS, Commodities.HEAVY_MACHINERY,
			Commodities.VOLATILES };

	// rare-earth branch (rare ore -> rare metals). Real, permanent inputs for
	// any incursion whose OG economy was built around rare ore (i.e. normal
	// starts). They keep gating growth even after the last rare mine is gone -
	// so wiping out rare ore mining genuinely strangles expansion until the hive
	// re-establishes it. Rare metals only bite above forge size 3, so the OG
	// still bootstraps with no deadlock. Only a degenerate rare-free start skips
	// this (see ThreatIncData.usesRareEconomy).
	protected static final String[] RARE_INPUTS = {
			Commodities.RARE_ORE, Commodities.RARE_METALS };

	/**
	 * A colony is starved when its own industries can't get their inputs - a
	 * refinery with no ore, a forge with no metals, a mine with no machinery.
	 * That happens when the hive's supply lines are cut or a link colony is
	 * destroyed (or a colony is isolated by distance, via low accessibility),
	 * exactly the intended siege pressure. A frontier colony whose factories are
	 * fed is healthy even if its population goods run short.
	 *
	 * All input availability is the vanilla economy's own figure, aggregated
	 * across the shared hive econ group and mediated by accessibility - we never
	 * move commodities ourselves.
	 */
	/**
	 * A core input chokes growth when less than this fraction of its demand is
	 * actually met. Any-deficit gating is too strict: the vanilla chain runs
	 * small STRUCTURAL deficits by design - Refining demands ore at size+2
	 * while a moderate deposit supplies only size; every refinery demands
	 * rare_ore at full size but only the rare-ore world mines it; a forge world
	 * imports all its metals - and NPC colonies simply live with the reduced
	 * output. Those built-in one-or-two-unit gaps must not read as a siege.
	 * Genuine interdiction (bombing the mines, severing the supply lines)
	 * craters availability toward zero and trips this threshold hard.
	 */
	public static final float STALL_MET_FRACTION = 0.5f;

	/**
	 * Whether a colony can still grow: every core production input must be at
	 * least half-fed. This is safe to gate on now that population supplies the
	 * base heavy machinery (see FabricationCore) - the input chain
	 * is acyclic, so a choked input is either transient (a supplier colony
	 * still scaling up, which resolves itself) or genuine siege pressure: the
	 * player cut the hive's mining, refining, or the supply lines carrying
	 * their output, and the starved colonies freeze until the hive re-establishes
	 * the flow. Isolation stalls growth through this same check - a colony far
	 * from the network, or whose link colonies were destroyed, loses in-group
	 * imports to collapsed accessibility and starves.
	 */
	public static boolean isEconomicallyHealthy(MarketAPI market) {
		if (!ThreatIncConfig.economyGatesGrowth()) return true;
		if (market == null) return false;
		if (chokedInput(market, CORE_INPUTS) != null) return false;
		// the rare branch is gated differently: rare deposits are structurally
		// scarce (one rare world supplies every refinery, each demanding full
		// size), so the half-fed bar would freeze refinery worlds forever even
		// in peacetime - and rare_metals output, slashed by that same rare_ore
		// deficit, would read as a permanent zero at the forge worlds. Scarcity
		// is vanilla-normal here and already bites through crushed ship
		// output/quality (shipSupplyMult). Growth freezes only on TOTAL rare_ore
		// cutoff - the player wiping out rare mining entirely - which stops
		// every refinery world dead and, as forges outgrow the frozen
		// refineries, cascades into a metals choke for the rest.
		if (ThreatIncData.usesRareEconomy()) {
			CommodityOnMarketAPI rare = market.getCommodityData(Commodities.RARE_ORE);
			if (rare != null && rare.getMaxDemand() > 0 && rare.getAvailable() <= 0) {
				return false;
			}
		}
		return true;
	}

	/** The first input in the list below the half-fed bar, or null if none. */
	protected static String chokedInput(MarketAPI market, String[] commodities) {
		for (String commodityId : commodities) {
			CommodityOnMarketAPI com = market.getCommodityData(commodityId);
			if (com == null) continue;
			int demand = com.getMaxDemand();
			if (demand <= 0) continue;
			if (com.getAvailable() < demand * STALL_MET_FRACTION) return commodityId;
		}
		return null;
	}

	/**
	 * Debug: one line of the colony's economic vitals - accessibility (with
	 * its component modifiers, exposing e.g. the distance-from-center penalty)
	 * and available/demand for every gated input - so a stall in the log is
	 * diagnosable without opening the game.
	 */
	public static String econDebugSummary(MarketAPI market) {
		StringBuilder sb = new StringBuilder();
		sb.append("access ").append(String.format("%.2f",
				market.getAccessibilityMod().computeEffective(0f)));
		sb.append(" {");
		for (Map.Entry<String, com.fs.starfarer.api.combat.MutableStat.StatMod> entry
				: market.getAccessibilityMod().getFlatBonuses().entrySet()) {
			sb.append(entry.getKey()).append("=")
					.append(String.format("%.2f", entry.getValue().value)).append(" ");
		}
		sb.append("}");
		List<String> inputs = new ArrayList<String>();
		for (String commodityId : CORE_INPUTS) inputs.add(commodityId);
		for (String commodityId : RARE_INPUTS) inputs.add(commodityId);
		inputs.add(Commodities.SHIPS);
		for (String commodityId : inputs) {
			CommodityOnMarketAPI com = market.getCommodityData(commodityId);
			if (com == null) continue;
			int demand = com.getMaxDemand();
			int available = com.getAvailable();
			if (demand <= 0 && available <= 0) continue;
			sb.append(" ").append(commodityId).append(" ")
					.append(available).append("/").append(demand);
		}
		return sb.toString();
	}

	/**
	 * Whether a colony's fuel economy is intact enough to fabricate and launch
	 * expedition fleets. Cutting the hive's fuel (destroy the fuel plants or the
	 * volatiles mining that feeds them) leaves colonies fuel-starved and grounds
	 * their colonization waves and strikes - fuel is mobility, as vital as ore is
	 * to production, just a different lever.
	 */
	public static boolean hasOperationalFuel(MarketAPI market) {
		if (!ThreatIncConfig.economyGatesGrowth()) return true;
		if (market == null) return false;
		// gate on the hive ACTUALLY PRODUCING fuel (fuel reaching this colony via
		// the group), not on a deficit: fuel demand is size-2, so a small colony
		// demands ~0 fuel and would pass a deficit check with no fuel plant at all.
		CommodityOnMarketAPI fuel = market.getCommodityData(Commodities.FUEL);
		return fuel != null && fuel.getAvailable() > 0;
	}

	/** Growth-healthy AND fuelled: the bar to source an outward fleet. */
	public static boolean canProjectFleets(MarketAPI market) {
		return isEconomicallyHealthy(market) && hasOperationalFuel(market);
	}

	/**
	 * How far this colony's strike expeditions reach, in light-years: its
	 * actual fuel availability times LY-per-fuel. Availability is the vanilla
	 * economy's group-wide figure - local fuel production PLUS fuel shipped in
	 * from sibling colonies, mediated by accessibility - NOT local production
	 * alone. So a well-connected colony projects the whole hive's fuel reach,
	 * an isolated one is grounded, and cutting fuel production (or the network
	 * carrying it) visibly shrinks the swarm's reach everywhere at once.
	 */
	public static float fuelRangeLY(MarketAPI market) {
		if (market == null) return 0f;
		CommodityOnMarketAPI fuel = market.getCommodityData(Commodities.FUEL);
		if (fuel == null) return 0f;
		return fuel.getAvailable() * ThreatIncConfig.strikeLYPerFuel();
	}

	/**
	 * The system's strike staging colony: its biggest fueled colony of strike
	 * size. The hive is one economy, so staging does NOT require a shipyard
	 * in-system: it requires the hive network to actually DELIVER hulls to the
	 * staging colony (shipsAvailable - group-wide forge output, mediated by
	 * accessibility). Destroying the hive's forges anywhere still grounds
	 * strikes everywhere, but a mature forge-less system can stage from
	 * shipped-in hulls. requireReadyForge=true for actually launching; false
	 * when only asking about the system's REACH (fuel range exists even while
	 * the hull supply is choked).
	 */
	public static MarketAPI pickStrikeStaging(String systemId, boolean requireReadyForge) {
		MarketAPI best = null;
		for (MarketAPI curr : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (curr.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (requireReadyForge && shipsAvailable(curr) <= 0f) continue;
			if (!hasOperationalFuel(curr)) continue;
			// expeditions are staged by the military organ, vanilla-style: a
			// disrupted Swarm Nexus launches nothing (see MilitaryBase's own
			// !isFunctional() patrol gate)
			if (!hasOperationalNexus(curr)) continue;
			if (best == null || curr.getSize() > best.getSize()) best = curr;
		}
		return best;
	}

	/** Ship-output bar for seeding NEW systems: near-nominal forge economy. */
	public static final float STABLE_SHIP_SUPPLY_MULT = 0.75f;

	/**
	 * The bar to found colonies in NEW systems, deliberately higher than
	 * canProjectFleets: the swarm doesn't reach outward until it is STABLE. A
	 * merely un-choked ("strained") economy consolidates at home instead -
	 * in-system expansion stays on the lower bar precisely because claiming a
	 * better local deposit world is how a strained hive fixes itself.
	 *
	 * Stable means the forge chain is actually delivering hulls to this market
	 * at near-nominal rates - or the colony has hit its size cap with a
	 * healthy, fuelled economy, i.e. it is as stable as its system's deposits
	 * will ever allow (a hive seeded on lean rocks still eventually reaches
	 * carrying capacity and pushes outward, just late and weakly). This also
	 * self-throttles the frontier: a freshly-seeded system has poor access to
	 * the distant hive's hull output, so it can't become a spread platform
	 * until the network matures around it - expansion is logistic, not
	 * exponential.
	 */
	public static boolean isStableForExpansion(MarketAPI market) {
		if (!canProjectFleets(market)) return false;
		if (!ThreatIncConfig.economyGatesGrowth()) return true;
		boolean nominalForge = shipsAvailable(market) > 0f
				&& shipSupplyMult(market) >= STABLE_SHIP_SUPPLY_MULT;
		boolean saturated = market.getSize() >= Math.min(ThreatIncConfig.colonyMaxSize(),
				Misc.getMaxMarketSize(market));
		return nominalForge || saturated;
	}

	// ------------------------------------------------------------------
	// forge fabrication: every expedition is paid for
	// ------------------------------------------------------------------

	/** The colony's forge, if it has one: Heavy Industry or Orbital Works. */
	public static Industry getForge(MarketAPI market) {
		if (market == null) return null;
		Industry forge = market.getIndustry(Industries.HEAVYINDUSTRY);
		if (forge == null) forge = market.getIndustry(Industries.ORBITALWORKS);
		return forge;
	}

	/**
	 * Whether this colony can fabricate an expedition right now: it has a
	 * forge and that forge isn't already retooled around a previous launch.
	 * There is no launch timer anywhere - the forge's disruption state IS the
	 * cooldown, and it is visible on the colony screen and attackable.
	 */
	public static boolean hasReadyForge(MarketAPI market) {
		Industry forge = getForge(market);
		return forge != null && !forge.isDisrupted();
	}

	/**
	 * The fabrication cost of any expedition - colonization wave or strike:
	 * the source colony's forge is disrupted while it retools. While down it
	 * supplies no ship hulls (or machinery, supplies, weapons), so the whole
	 * hive's shipSupplyMult sags and garrisons thin - expanding or raiding
	 * genuinely weakens the swarm for a window, and a colony can't launch
	 * again until its forge recovers. No dice, no timers: launch throughput
	 * is exactly the number of stable forges the hive actually runs.
	 */
	public static void payLaunchCost(MarketAPI market) {
		// the launch exhausts the MILITARY organ, not the economic one: the
		// Swarm Nexus that assembled and launched the expedition goes into
		// refit, during which the colony stages nothing further and grows no
		// replacement Defense Swarms (maintainGarrisons gates on it) - a
		// colony that just threw its punch is at its most exposed. The forge
		// keeps supplying hulls to the hive economy; it is only disrupted by
		// enemy action (raids, bombardment).
		Industry nexus = market.getIndustry(SWARM_NEXUS);
		if (nexus == null) return;
		float days = ThreatIncConfig.launchDisruptDays() * IncursionManager.timeScale();
		nexus.setDisrupted(days, true);
		ThreatIncConfig.log("Swarm Nexus at " + market.getName() + " in post-launch refit for "
				+ (int) days + "d (expedition fabricated and away)");
	}

	/**
	 * Nearest colony that can fabricate and dispatch a wave at the target:
	 * big enough, forge ready, and past the required economic bar - the
	 * stability bar for claiming NEW systems, the lower projection bar for
	 * consolidation (a strained hive may still fix itself locally).
	 */
	public static MarketAPI pickForgeSource(StarSystemAPI target, boolean requireStable) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.spreadMinSize()) continue;
			if (!hasReadyForge(market)) continue;
			// the launch cooldown now lives on the nexus (payLaunchCost), so
			// wave sourcing must respect it like strike staging does
			if (!hasOperationalNexus(market)) continue;
			if (requireStable ? !isStableForExpansion(market) : !canProjectFleets(market)) continue;
			StarSystemAPI system = market.getStarSystem();
			if (system == null) continue;
			float d = Misc.getDistanceLY(system.getLocation(), target.getLocation());
			// colonization is fuel-bound exactly like strikes: a wave can only
			// be sent as far as the fuel the hive network delivers to this
			// colony will carry it. Cut their fuel and the swarm stops seeding.
			if (d > fuelRangeLY(market)) continue;
			if (d < bestDist) {
				bestDist = d;
				best = market;
			}
		}
		return best;
	}

	/**
	 * Ship-hull availability in the hive economy as seen from this market. Ships
	 * are a group-scoped commodity, so this reflects the whole hive's forge
	 * output - zero until a Heavy Industry/Orbital Works colony is actually
	 * producing hulls. This is the swarm's real "fabrication output".
	 */
	public static float shipsAvailable(MarketAPI market) {
		if (market == null) return 0f;
		CommodityOnMarketAPI ships = market.getCommodityData(Commodities.SHIPS);
		return ships != null ? ships.getAvailable() : 0f;
	}

	/** Whether the recorded OG home system carries rare-ore deposits. */
	public static boolean ogSystemHasRareOre() {
		String ogId = ThreatIncData.getOGSystem();
		if (ogId == null) return true; // no OG recorded (old save): assume normal
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(ogId)) {
				return systemHasDeposit(system, Commodities.RARE_ORE);
			}
		}
		return true;
	}

	/**
	 * The real supply-vs-demand shortfall for a commodity at this market, in
	 * econ units: total demand minus what the vanilla economy makes available
	 * (own production + in-group imports, accessibility-mediated). NOT
	 * getDeficitQuantity(), which is a player-trade figure and reads ~0 on these
	 * untraded NPC colonies. This is the same available/demand pairing vanilla's
	 * own ship-deficit fleet-size formula uses.
	 */
	public static int deficitOf(MarketAPI market, String commodityId) {
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return 0;
		return Math.max(0, com.getMaxDemand() - com.getAvailable());
	}

	/**
	 * How badly the hive is short of ship hulls at this colony: the same mult
	 * vanilla applies to faction fleet sizes (1 = fully supplied, floor 0.25).
	 * This is the economy's grip on garrison and strike strength.
	 */
	public static float shipSupplyMult(MarketAPI market) {
		if (market == null) return 1f;
		return FleetFactoryV3.getShipDeficitFleetSizeMult(market);
	}

	// ------------------------------------------------------------------
	// colonization waves
	// ------------------------------------------------------------------

	/**
	 * Dispatches a Seeding Swarm at the target planet. Source may be null only
	 * for the initial incursion from the Abyss (bootstrap seeds); every later
	 * wave launches from an established colony.
	 */
	public static boolean launchColonizationWave(MarketAPI source, StarSystemAPI targetSystem,
			PlanetAPI targetPlanet, Random random) {
		if (targetPlanet == null) return false;

		int escortIdx;
		if (source == null) {
			// the one-time bootstrap from the Abyss: strength set by config
			escortIdx = ThreatIncConfig.colonizationEscort();
		} else {
			// a fabricated wave is as strong as the colony that built it: tier
			// scales with the source's size, upgraded by a thriving hull
			// economy, downgraded by a strained or starved one - the same
			// shipSupplyMult lever that throttles garrisons and strikes. A
			// strained hive's expeditions genuinely suck: below-nominal drops
			// a tier, badly starved drops two
			int size = source.getSize();
			escortIdx = size >= 8 ? 2 : size >= 6 ? 1 : 0;
			float mult = shipSupplyMult(source);
			if (mult >= 0.9f && size >= 8) escortIdx = 3;
			if (mult < STABLE_SHIP_SUPPLY_MULT && escortIdx > 0) escortIdx--;
			if (mult < 0.4f && escortIdx > 0) escortIdx--;
		}
		if (escortIdx < 0) escortIdx = 0;
		if (escortIdx > 3) escortIdx = 3;
		FabricatorEscortStrength escort = FabricatorEscortStrength.values()[escortIdx];

		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				1, 0, 0, escort, random);
		if (fleet == null) return false;

		// the expedition is paid for: the source's forge retools around it
		if (source != null) payLaunchCost(source);
		fleet.setName("Seeding Swarm");
		fleet.getMemoryWithoutUpdate().set(WAVE_FLAG, targetSystem.getId());
		makeDetectable(fleet);

		if (source != null && source.getPrimaryEntity() != null
				&& source.getStarSystem() != null) {
			// launched from an existing colony; vanilla fleet AI handles any
			// hyperspace transit to the target on its own
			SectorEntityToken home = source.getPrimaryEntity();
			source.getStarSystem().addEntity(fleet);
			fleet.setLocation(home.getLocation().x, home.getLocation().y);
		} else {
			// the one-time incursion from the Abyss: emerges from the deep
			// fringe, a few LY beyond the target system on the side facing
			// away from the sector core
			Vector2f sysLoc = targetSystem.getLocation();
			Vector2f dir = new Vector2f(sysLoc);
			if (dir.length() < 1f) dir.set(0f, 1f);
			dir.normalise();
			float depth = (3f + random.nextFloat() * 2f) * Misc.getUnitsPerLightYear();
			Vector2f loc = new Vector2f(sysLoc.x + dir.x * depth, sysLoc.y + dir.y * depth);
			Global.getSector().getHyperspace().addEntity(fleet);
			fleet.setLocation(loc.x, loc.y);
		}

		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, targetPlanet, 365f,
				"seeding " + targetSystem.getNameWithLowercaseTypeShort());
		// fallback so the fleet doesn't wander if arrival detection ever misses
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, targetPlanet, 1000000f);

		// a system receiving its first colony shows as "colonizing"; in-system
		// expansion of an established colony system doesn't change its stage
		if (ThreatIncData.getLiveColonyMarkets(targetSystem.getId()).isEmpty()) {
			ThreatIncData.setStage(targetSystem.getId(), ThreatIncData.STAGE_COLONIZING);
		}
		ThreatIncData.waveFleets().put(targetPlanet.getId(), fleet);
		ThreatIncData.waveTargets().put(targetPlanet.getId(), targetSystem.getId());

		// No transit intel and no auto-discovery. The player is not meant to be
		// able to watch the swarm spread, nor to learn a system exists merely
		// because a wave is bound for it. A system becomes "known" only by being
		// visited in person, named in a bounty, or launching a strike.

		ThreatIncConfig.log("Colonization wave launched at " + targetPlanet.getName()
				+ ", " + targetSystem.getName()
				+ (source != null ? " from " + source.getName() : " (bootstrap)"));
		return true;
	}

	/**
	 * Polls in-transit waves: founds the colony on arrival, reverts/clears on
	 * wave death, withdraws if someone claimed the planet first.
	 */
	public static void checkWaveArrivals(Random random) {
		for (String planetId : new ArrayList<String>(ThreatIncData.waveFleets().keySet())) {
			CampaignFleetAPI fleet = ThreatIncData.waveFleets().get(planetId);
			String systemId = ThreatIncData.waveTargets().get(planetId);
			StarSystemAPI system = systemId != null ? getSystem(systemId) : null;
			if (system == null) {
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				continue;
			}
			boolean firstColony = ThreatIncData.getLiveColonyMarkets(systemId).isEmpty();

			if (fleet == null || !fleet.isAlive()) {
				// wave destroyed: the claim survives but the colony doesn't
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				if (firstColony) {
					ThreatIncData.setStage(systemId, ThreatIncData.STAGE_SEEDED);
				}
				// name the exact target planet, and be honest about any sibling
				// swarms still inbound (the OG chain launches several at once) -
				// killing one wave stops one colony, not the colonization effort
				String planetName = null;
				SectorEntityToken deadTarget = Global.getSector().getEntityById(planetId);
				if (deadTarget != null) planetName = deadTarget.getName();
				int otherInbound = 0;
				for (Map.Entry<String, String> wave : ThreatIncData.waveTargets().entrySet()) {
					if (systemId.equals(wave.getValue()) && !planetId.equals(wave.getKey())) {
						otherInbound++;
					}
				}
				String msg = "The Seeding Swarm bound for "
						+ (planetName != null ? planetName : ("the " + system.getNameWithLowercaseType()))
						+ " has been destroyed.";
				if (otherInbound == 1) {
					msg += " Another swarm is still inbound to the system.";
				} else if (otherInbound > 1) {
					msg += " " + otherInbound + " more swarms are still inbound to the system.";
				}
				announce(msg, Misc.getPositiveHighlightColor());
				ThreatIncConfig.log("Wave destroyed: " + system.getName());
				continue;
			}

			SectorEntityToken target = Global.getSector().getEntityById(planetId);
			if (!(target instanceof PlanetAPI)) {
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				if (firstColony) ThreatIncData.clearSystem(systemId);
				retireFleet(fleet, system);
				continue;
			}
			PlanetAPI planet = (PlanetAPI) target;

			// someone colonized it mid-flight: withdraw
			MarketAPI existing = planet.getMarket();
			if (existing == null || (!existing.isPlanetConditionMarketOnly()
					&& !Factions.NEUTRAL.equals(existing.getFactionId()))) {
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				if (firstColony) ThreatIncData.clearSystem(systemId);
				retireFleet(fleet, system);
				ThreatIncConfig.log("Wave withdrew from claimed planet at " + system.getName());
				continue;
			}

			if (fleet.getContainingLocation() == system
					&& Misc.getDistance(fleet, planet) < 300f + planet.getRadius()) {
				boolean conversion = existing.hasCondition(Conditions.DECIVILIZED);
				MarketAPI market = foundColony(planet, 1);
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				if (market == null) {
					if (firstColony) ThreatIncData.clearSystem(systemId);
					retireFleet(fleet, system);
					continue;
				}

				ThreatIncData.setStage(systemId, ThreatIncData.STAGE_COLONY);
				ThreatIncData.colonyMarketsFor(systemId).add(market.getId());
				ThreatIncData.setGrowthTime(market.getId());
				ThreatIncData.garrisonSpawnTimes().put(market.getId(),
						Global.getSector().getClock().getTimestamp());
				ThreatIncData.decivTargets().remove(planet.getId());
				// the beachhead is established; the Abyss sends no more
				ThreatIncData.bootstrapSeeds().remove(systemId);

				// the seeding swarm digs in as the first garrison
				fleet.clearAssignments();
				fleet.setName("Defense Swarm");
				fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, market.getId());
				fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
				ThreatIncData.garrisonsFor(market.getId()).add(fleet);

				if (conversion) {
					announce("The dead world of " + planet.getName() + " is dead no longer. "
							+ "Fabrication strata are spreading through the ruins - the swarm "
							+ "has claimed what it killed.", Misc.getNegativeHighlightColor());
				} else if (firstColony) {
					announce("The swarm has taken root on " + planet.getName() + " in the "
							+ system.getNameWithLowercaseType() + ". Fabrication strata are "
							+ "spreading across its surface.", Misc.getNegativeHighlightColor());
				} else {
					announce("The swarm has spread to a second world: " + planet.getName()
							+ " in the " + system.getNameWithLowercaseType()
							+ " is being converted to fabrication strata.",
							Misc.getNegativeHighlightColor());
				}
				ThreatIncConfig.log("Colony founded: " + planet.getName()
						+ (conversion ? " (converted deciv world)" : ""));
			}
		}
	}

	/**
	 * Seeding-swarm transit intel was removed - the player must not be able to
	 * watch the swarm spread. This purges any such intel every poll: both the
	 * lingering "Planetfall" / "Swarm Destroyed" entries left behind in existing
	 * saves (which otherwise never clear) and anything an older jar created, so it
	 * can never accumulate again.
	 */
	public static void clearSeedingSwarmIntel() {
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr
				: new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>(
						Global.getSector().getIntelManager().getIntel(SeedingSwarmIntel.class))) {
			Global.getSector().getIntelManager().removeIntel(curr);
		}
	}

	/**
	 * Vanilla's procedural mission generators treat any faction with markets
	 * in the economy as a legitimate mission poster - so the moment the hive
	 * economy went live, the THREAT began offering survey commissions and
	 * derelict-analysis rewards through the sector's job boards. Purge every
	 * still-posted vanilla mission credited to the machines; anything the
	 * player already accepted is left to complete rather than yanked.
	 */
	public static void clearThreatMissionIntel() {
		com.fs.starfarer.api.campaign.comm.IntelManagerAPI intelManager =
				Global.getSector().getIntelManager();
		Class<?>[] classes = {
				com.fs.starfarer.api.impl.campaign.intel.AnalyzeEntityMissionIntel.class,
				com.fs.starfarer.api.impl.campaign.intel.SurveyPlanetMissionIntel.class,
				com.fs.starfarer.api.impl.campaign.intel.ProcurementMissionIntel.class };
		for (Class<?> intelClass : classes) {
			for (Object curr : new ArrayList<Object>(intelManager.getIntel(
					(Class<? extends com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>) intelClass))) {
				com.fs.starfarer.api.impl.campaign.intel.BaseMissionIntel mission =
						(com.fs.starfarer.api.impl.campaign.intel.BaseMissionIntel) curr;
				if (!mission.isPosted()) continue;
				com.fs.starfarer.api.campaign.FactionAPI faction = mission.getFactionForUIColors();
				if (faction == null || !Factions.THREAT.equals(faction.getId())) continue;
				intelManager.removeIntel(mission);
				ThreatIncConfig.log("Removed Threat-posted vanilla mission: "
						+ mission.getSmallDescriptionTitle());
			}
		}
	}

	protected static void retireFleet(CampaignFleetAPI fleet, StarSystemAPI system) {
		if (fleet == null || !fleet.isAlive()) return;
		fleet.clearAssignments();
		SectorEntityToken exit = system != null ? system.getHyperspaceAnchor() : null;
		if (exit != null) {
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, exit, 1000f,
					"withdrawing");
		} else {
			fleet.despawn();
		}
	}

	// ------------------------------------------------------------------
	// in-system expansion
	// ------------------------------------------------------------------

	/**
	 * The swarm consolidates before it reaches outward: every colonized system
	 * that still has a resource-bearing planet unclaimed gets a wave, each
	 * fabricated (and paid for) by the nearest ready forge - not necessarily a
	 * local one, so a young frontier system is consolidated by the core's
	 * forges until it can build its own. One wave per system per pass; total
	 * throughput is bounded by how many forges the hive can actually spare.
	 * Returns true if any wave launched.
	 */
	/**
	 * Whether any colonization wave is currently in flight. Outside the
	 * one-time OG bootstrap, the swarm runs ONE colonization attempt at a
	 * time, sector-wide: expansion is a deliberate, interceptable operation,
	 * not a flood - a player who hunts down THE seeding swarm has genuinely
	 * stopped the spread until the next one is fabricated.
	 */
	public static boolean anyWaveInFlight() {
		return !ThreatIncData.waveFleets().isEmpty();
	}

	/**
	 * Whether ANY hive colony runs a nominal forge economy. A hive with none -
	 * every world strained - does not stretch itself thinner with land-grabs:
	 * it expands only toward deposits that would FIX its shortfalls (see the
	 * strained gates in spread/expansion/conversion), because each new colony
	 * is another mouth on the same starved supply chain.
	 */
	public static boolean anyNominalColony() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (shipsAvailable(market) > 0f
					&& shipSupplyMult(market) >= STABLE_SHIP_SUPPLY_MULT) {
				return true;
			}
		}
		return false;
	}

	public static boolean tryExpandInSystem(Random random) {
		if (anyWaveInFlight()) return false;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			StarSystemAPI system = getSystem(systemId);
			if (system == null) continue;

			PlanetAPI planet = pickExpansionPlanet(system);
			if (planet == null) continue;

			MarketAPI source = pickForgeSource(system, false);
			if (source == null) continue;

			// one wave, then done - the next claim waits for the next pass
			return launchColonizationWave(source, system, planet, random);
		}
		return false;
	}

	// ------------------------------------------------------------------
	// growth
	// ------------------------------------------------------------------

	/** Called from the ~30-day tick. */
	public static void growColonies() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			int size = market.getSize();
			int cap = Math.min(ThreatIncConfig.colonyMaxSize(), Misc.getMaxMarketSize(market));
			if (size >= cap) continue;

			float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size
					* IncursionManager.timeScale();
			if (ThreatIncData.daysSinceGrowth(market.getId()) < daysPerLevel) continue;

			if (!isEconomicallyHealthy(market)) {
				// starved: growth resumes promptly once the shortages clear
				ThreatIncConfig.log("Growth stalled (shortages) at " + market.getName()
						+ " - " + econDebugSummary(market));
				continue;
			}

			int old = market.getSize();
			CoreImmigrationPluginImpl.increaseMarketSize(market);
			if (market.getSize() <= old) continue;
			ListenerUtil.reportColonySizeChanged(market, old);
			ThreatIncData.setGrowthTime(market.getId());
			// the Fabrication Core's machinery output tracks size by itself
			// (its apply() reads market size on every econ recompute)
			planHiveEconomy(market);

			int newSize = market.getSize();
			if (newSize == 4 || newSize == 6 || newSize >= cap) {
				announce("The fabrication colony on " + market.getName()
						+ " has expanded to size " + newSize + "."
						+ (newSize >= cap ? " Its growth has reached saturation." : ""),
						Misc.getNegativeHighlightColor());
			}
			ThreatIncConfig.log("Colony grew to " + newSize + ": " + market.getName());
		}
	}

	// ------------------------------------------------------------------
	// garrisons
	// ------------------------------------------------------------------

	/**
	 * Desired garrison composition by colony size; each row is one fleet as
	 * {numFabricators, FabricatorEscortStrength ordinal}.
	 */
	protected static int[][] desiredGarrison(int size) {
		int low = FabricatorEscortStrength.LOW.ordinal();
		int med = FabricatorEscortStrength.MEDIUM.ordinal();
		int high = FabricatorEscortStrength.HIGH.ordinal();
		int max = FabricatorEscortStrength.MAXIMUM.ordinal();

		// Sized against the strike budget (size^2 x strikeStrengthMult): a
		// colony's garrison should be in the same weight class as the
		// expedition it can send, not a fifth of it - force projection is the
		// expensive posture, defense the cheap one.
		if (size <= 2) return new int[][] {{0, low}};
		if (size <= 4) return new int[][] {{0, med}, {0, med}, {0, med}};
		if (size == 5) return new int[][] {{0, med}, {0, med}, {0, high}, {1, med}};
		if (size == 6) return new int[][] {{0, high}, {0, high}, {0, high}, {1, high}};
		if (size == 7) return new int[][] {{0, high}, {0, high}, {0, high}, {0, high}, {1, high}};
		return new int[][] {{0, high}, {0, high}, {0, max}, {1, max}, {2, high}};
	}

	/**
	 * Prunes dead garrison fleets and fabricates at most one replacement per
	 * respawn interval, per colony. The garrison orbits its own colony planet -
	 * it is both the "defenders want to fight" gate on bombardment and the
	 * visible face of the colony's strength. Hull shortages in the hive
	 * economy shrink the garrison a colony can field.
	 */
	public static void maintainGarrisons(Random random) {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			SectorEntityToken planet = market.getPrimaryEntity();
			StarSystemAPI system = market.getStarSystem();
			if (planet == null || system == null) continue;
			String marketId = market.getId();

			// backfill the ambient/trade-fleet suppression for colonies founded
			// before it was added (idempotent)
			SharedData.getData().getMarketsWithoutTradeFleetSpawn().add(marketId);

			List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(marketId);
			for (int i = fleets.size() - 1; i >= 0; i--) {
				CampaignFleetAPI curr = fleets.get(i);
				if (curr == null || !curr.isAlive()) fleets.remove(i);
			}

			// fleets are fabricated by the Swarm Nexus, vanilla-military-base
			// style: while it is disrupted no NEW Defense Swarms are grown
			// (existing ones keep fighting) - so a raid or bombardment that
			// silences the nexus genuinely thins the colony over time
			if (!hasOperationalNexus(market)) continue;

			int[][] table = desiredGarrison(market.getSize());
			// the economy is the difficulty: a hull-starved colony fields a
			// fraction of its nominal garrison
			int desired = Math.max(1, Math.round(table.length * shipSupplyMult(market)));
			if (desired > table.length) desired = table.length;
			if (fleets.size() >= desired) continue;

			Long last = ThreatIncData.garrisonSpawnTimes().get(marketId);
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
					< ThreatIncConfig.garrisonRespawnDays() * IncursionManager.timeScale()) {
				continue;
			}

			int[] spec = table[fleets.size() < table.length ? fleets.size() : table.length - 1];
			CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
					spec[0], 0, 0, FabricatorEscortStrength.values()[spec[1]], random);
			if (fleet == null) continue;
			fleet.setName("Defense Swarm");
			fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, marketId);
			makeDetectable(fleet);

			system.addEntity(fleet);
			Vector2f loc = Misc.getPointAtRadius(planet.getLocation(),
					planet.getRadius() + 400f + random.nextFloat() * 300f);
			fleet.setLocation(loc.x, loc.y);
			fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);

			fleets.add(fleet);
			ThreatIncData.garrisonSpawnTimes().put(marketId,
					Global.getSector().getClock().getTimestamp());
			ThreatIncConfig.log("Garrison fleet fabricated at " + market.getName()
					+ " (" + fleets.size() + "/" + desired + ")");
		}
	}

	/**
	 * How far a Defense Swarm may stray from its colony before being ordered
	 * home. Sized well inside the battle-join radius the bombardment dialog
	 * pulls defenders from (~2000), so a leashed garrison ALWAYS counts as
	 * defending: it cannot be lured out of position and left behind while the
	 * attacker circles back to bombard an "undefended" world - the vanilla
	 * exploit an orbital station would normally prevent, and the hive has no
	 * stations.
	 */
	public static final float GARRISON_LEASH_RADIUS = 700f;

	/**
	 * Per-frame leash enforcement (called from IncursionManager.advance):
	 * a swarm beyond the leash, and not currently battle-locked, drops
	 * whatever it was chasing and returns to orbit. The return leg uses
	 * plain GO_TO_LOCATION so it cannot be re-baited on the way home.
	 */
	public static void enforceGarrisonLeash() {
		for (String marketId : new ArrayList<String>(ThreatIncData.garrisons().keySet())) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
			if (market == null || market.getPrimaryEntity() == null) continue;
			SectorEntityToken planet = market.getPrimaryEntity();
			for (CampaignFleetAPI fleet : ThreatIncData.garrisonsFor(marketId)) {
				if (fleet == null || !fleet.isAlive()) continue;

				boolean home = fleet.getContainingLocation() == planet.getContainingLocation()
						&& Misc.getDistance(fleet, planet) <= GARRISON_LEASH_RADIUS;
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();

				if (home) {
					// back on station: hunting reflexes back on
					if (mem.getBoolean(com.fs.starfarer.api.impl.campaign.ids.MemFlags
							.FLEET_IGNORES_OTHER_FLEETS)) {
						mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags
								.FLEET_IGNORES_OTHER_FLEETS);
						mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags
								.MEMORY_KEY_MAKE_AGGRESSIVE, true);
					}
					continue;
				}
				if (fleet.getBattle() != null) continue;

				// Beyond the leash. Assignments alone CANNOT bring these fleets
				// home: createThreatFleet stamps every Threat fleet with
				// MEMORY_KEY_MAKE_AGGRESSIVE, whose pursuit AI overrides any
				// travel order (observed: garrisons chasing the player through
				// wormholes over a GO_TO_LOCATION recall). So put blinders on -
				// ignore other fleets, aggression off - and the return order
				// actually governs; both are restored on arrival. The fleet
				// still defends itself if attacked en route.
				mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags
						.FLEET_IGNORES_OTHER_FLEETS, true);
				mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags
						.MEMORY_KEY_MAKE_AGGRESSIVE);

				// already on the way home: don't spam assignments every frame
				com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI curr =
						fleet.getCurrentAssignment();
				if (curr != null && curr.getTarget() == planet
						&& curr.getAssignment() == FleetAssignment.GO_TO_LOCATION) continue;
				fleet.clearAssignments();
				fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 30f,
						"returning to the hive");
				fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
			}
		}
	}

	public static int countLiveGarrison(String marketId) {
		int count = 0;
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(marketId)) {
			if (curr != null && curr.isAlive()) count++;
		}
		return count;
	}

	public static int countLiveGarrisonInSystem(String systemId) {
		int count = 0;
		for (String marketId : ThreatIncData.colonyMarketsFor(systemId)) {
			count += countLiveGarrison(marketId);
		}
		return count;
	}

	// ------------------------------------------------------------------
	// colony death
	// ------------------------------------------------------------------

	/**
	 * Detects colonies that no longer exist (bombarded to decivilization, or
	 * otherwise removed) and clears them. The teardown itself is vanilla's -
	 * this only reacts to it. A system is cleansed only when its last colony
	 * dies.
	 */
	public static void pollColonies() {
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (!ThreatIncData.STAGE_COLONY.equals(ThreatIncData.stages().get(systemId))) continue;
			List<String> ids = ThreatIncData.colonyMarketsFor(systemId);
			boolean lostOne = false;

			for (String marketId : new ArrayList<String>(ids)) {
				if (ThreatIncData.resolveColonyMarket(marketId) != null) continue;
				// this colony is gone: strip our mods off the surviving husk,
				// disperse its garrison, drop its bookkeeping - and recall any
				// expedition it was sustaining
				StarSystemAPI system = getSystem(systemId);
				MarketAPI husk = findMarketAnywhere(marketId, system);
				IncursionManager.abortStrikesFrom(marketId,
						husk != null ? husk.getName() : "a destroyed colony",
						"the colony's destruction");
				IncursionManager.abortPurgesAgainst(marketId,
						husk != null ? husk.getName() : "a destroyed colony",
						"the colony's destruction");
				if (husk != null) cleanColonyMods(husk);
				for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
						ThreatIncData.garrisonsFor(marketId))) {
					retireFleet(curr, system);
				}
				ThreatIncData.garrisons().remove(marketId);
				ThreatIncData.garrisonSpawnTimes().remove(marketId);
				ThreatIncData.growthTimes().remove(marketId);
				ThreatIncData.lastPurgeTimes().remove(marketId);
				ids.remove(marketId);
				lostOne = true;
			}

			if (!lostOne) continue;

			StarSystemAPI system = getSystem(systemId);
			String name = system != null ? system.getName() : "an infested system";
			if (ids.isEmpty()) {
				ThreatIncData.clearSystem(systemId);
				ThreatIncData.incrCleansedCount();
				announce("The last Threat fabrication colony in " + name + " has been burned "
						+ "away. The hive's network is diminished - and every surviving colony "
						+ "feels the loss.", Misc.getPositiveHighlightColor());
				ThreatIncConfig.log("System cleansed: " + name);
			} else {
				announce("A Threat fabrication colony in " + name + " has been burned away, "
						+ "though the swarm still holds other worlds there.",
						Misc.getPositiveHighlightColor());
				ThreatIncConfig.log("Colony destroyed (system still held): " + name);
			}
		}
	}

	/**
	 * Strips everything this mod applied to a market, so the husk left behind
	 * (the planet's condition-only market) is indistinguishable from a never-
	 * colonized world - critically the econ group, or a later player colony on
	 * this planet would be trapped inside the hive economy.
	 */
	public static void cleanColonyMods(MarketAPI market) {
		try {
			if (market.hasIndustry(FABRICATION_CORE)) {
				market.removeIndustry(FABRICATION_CORE, null, false);
			}
			if (market.hasIndustry(SWARM_NEXUS)) {
				market.removeIndustry(SWARM_NEXUS, null, false);
			}
			if (market.hasIndustry(THREAT_GROUND_DEFENSES)) {
				market.removeIndustry(THREAT_GROUND_DEFENSES, null, false);
			}
			if (market.hasIndustry(THREAT_HEAVY_BATTERIES)) {
				market.removeIndustry(THREAT_HEAVY_BATTERIES, null, false);
			}
			Industry pop = market.getIndustry(Industries.POPULATION);
			// quantity 0 removes the (legacy) supply mod
			if (pop != null) {
				pop.supply(MACHINERY_SUPPLY_ID, Commodities.HEAVY_MACHINERY, 0, null);
				pop.getDemandReductionFromOther().unmodifyFlat("threatinc_machines");
			}
			market.getStability().unmodifyFlat(STABILITY_MOD_ID);
			market.getStats().getDynamic().getMod(Stats.MAX_MARKET_SIZE).unmodifyFlat("threatinc");
			market.getMemoryWithoutUpdate().unset(COLONY_FLAG);
			market.getMemoryWithoutUpdate().unset(DecivTracker.NO_DECIV_KEY);
			market.setInvalidMissionTarget(null);
			market.setEconGroup(null);
			market.setUseStockpilesForShortages(true);
			SharedData.getData().getMarketsWithoutTradeFleetSpawn().remove(market.getId());
		} catch (Throwable t) {
			// husk in a weird state; nothing to clean
		}
	}

	/**
	 * Finds a colony's market even after vanilla removed it from the economy
	 * (post-deciv the object survives, attached to its planet).
	 */
	protected static MarketAPI findMarketAnywhere(String marketId, StarSystemAPI system) {
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market != null) return market;
		if (system == null) return null;
		for (PlanetAPI planet : system.getPlanets()) {
			if (planet.getMarket() != null && marketId.equals(planet.getMarket().getId())) {
				return planet.getMarket();
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// full reset (debug)
	// ------------------------------------------------------------------

	/**
	 * Tears the entire incursion out of the save: every colony reverts to a
	 * pristine uncolonized planet, every Threat fleet this mod spawned
	 * despawns, and all state is wiped. The incursion then restarts from
	 * scratch the next time a start trigger fires.
	 */
	public static void resetIncursion() {
		for (String systemId : new ArrayList<String>(ThreatIncData.stages().keySet())) {
			StarSystemAPI system = getSystem(systemId);

			for (String marketId : new ArrayList<String>(ThreatIncData.colonyMarketsFor(systemId))) {
				for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
						ThreatIncData.garrisonsFor(marketId))) {
					if (curr != null && curr.isAlive()) curr.despawn();
				}
				MarketAPI market = findMarketAnywhere(marketId, system);
				if (market != null) {
					boolean inEconomy = market.isInEconomy();
					cleanColonyMods(market);
					if (inEconomy) {
						// full vanilla teardown: industries, conditions,
						// submarkets, economy removal - no ruins left behind
						DecivTracker.removeColony(market, false);
					}
					if (market.getPrimaryEntity() != null) {
						market.getPrimaryEntity().setFaction(Factions.NEUTRAL);
					}
				}
			}

			CampaignFleetAPI hive = ThreatIncData.hives().get(systemId);
			if (hive != null && hive.isAlive()) hive.despawn();

			ThreatIncData.clearSystem(systemId);
		}

		// waves in transit anywhere
		for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
				ThreatIncData.waveFleets().values())) {
			if (curr != null && curr.isAlive()) curr.despawn();
		}
		ThreatIncData.waveFleets().clear();
		ThreatIncData.waveTargets().clear();

		ThreatIncData.decivTargets().clear();
		ThreatIncData.pendingDecivChecks().clear();
		ThreatIncData.bootstrapSeeds().clear();
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_OG_SYSTEM);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_RARE_ECONOMY);

		// clear all incursion intel: the per-system markers, transit trackers,
		// bounties, and the summary (which re-adds itself fresh on restart)
		for (Class<?> intelClass : new Class<?>[] {
				InfestedSystemIntel.class, SeedingSwarmIntel.class, ThreatBountyIntel.class }) {
			for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr
					: new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>(
							Global.getSector().getIntelManager().getIntel(intelClass))) {
				Global.getSector().getIntelManager().removeIntel(curr);
			}
		}
		ThreatIncData.discoveredSystems().clear();
		Global.getSector().getPersistentData().remove(IncursionManager.KEY_BOUNTY_ROTATION);
		ThreatIncursionIntel summary = ThreatIncursionIntel.get();
		if (summary != null) {
			Global.getSector().getIntelManager().removeIntel(summary);
			Global.getSector().getMemoryWithoutUpdate().unset(ThreatIncursionIntel.KEY);
		}

		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_STARTED);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_START_TIMESTAMP);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_PLAYER_STRUCK_AT);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_SYSTEMS_CLEANSED);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_ANNOUNCED_PHASE3);

		announce("The Threat incursion has been reset. The swarm will return to the sector "
				+ "as if for the first time.", Misc.getHighlightColor());
		ThreatIncConfig.log("Incursion fully reset.");
	}

	// ------------------------------------------------------------------
	// legacy save migration
	// ------------------------------------------------------------------

	/** One-time conversions for saves made under older data layouts. */
	public static void migrateLegacyData(Random random) {
		int version = ThreatIncData.getDataVersion();
		if (version >= ThreatIncData.CURRENT_DATA_VERSION) return;

		if (version < 2) migrateHivesToColonies(random);
		if (version < 3) migrateToMultiColony();

		ThreatIncData.setDataVersion(ThreatIncData.CURRENT_DATA_VERSION);
	}

	/** v1 -> v2: hive-fleet systems become real colonies; hive fleets despawn. */
	protected static void migrateHivesToColonies(Random random) {
		int converted = 0;
		for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(
				ThreatIncData.stages().entrySet())) {
			String systemId = entry.getKey();
			String stage = entry.getValue();

			boolean legacyHive = ThreatIncData.STAGE_HIVE.equals(stage);
			boolean legacySaturated = ThreatIncData.STAGE_SATURATED.equals(stage);
			if (!legacyHive && !legacySaturated) continue;

			CampaignFleetAPI hive = ThreatIncData.hives().get(systemId);
			if (hive != null && hive.isAlive()) hive.despawn();

			StarSystemAPI system = getSystem(systemId);
			PlanetAPI planet = system != null ? pickColonyPlanet(system) : null;
			if (planet == null) {
				ThreatIncData.clearSystem(systemId);
				continue;
			}

			MarketAPI market = foundColony(planet, legacySaturated ? 4 : 2);
			if (market == null) {
				ThreatIncData.clearSystem(systemId);
				continue;
			}
			ThreatIncData.setStage(systemId, ThreatIncData.STAGE_COLONY);
			ThreatIncData.colonyMarketsFor(systemId).add(market.getId());
			ThreatIncData.setGrowthTime(market.getId());
			ThreatIncData.garrisonSpawnTimes().put(market.getId(),
					Global.getSector().getClock().getTimestamp());
			converted++;
		}
		ThreatIncData.hives().clear();

		if (converted > 0) {
			announce("The swarm has consolidated its holdings: its fabrication hives have "
					+ "dug into the planets below, becoming true colonies of the machine.",
					Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Migrated " + converted + " legacy hive systems to colonies.");
		}
	}

	/**
	 * v2 -> v3: single-colony-per-system layout becomes lists; per-colony maps
	 * re-key from system id to market id; waves re-key from system id to
	 * target planet id; existing seeds are grandfathered as bootstrap seeds so
	 * an early-game save without colonies can't deadlock.
	 */
	@SuppressWarnings("unchecked")
	protected static void migrateToMultiColony() {
		Map<String, Object> colonyMap = ThreatIncData.map(ThreatIncData.KEY_COLONY_MARKETS);
		for (Map.Entry<String, Object> entry : new ArrayList<Map.Entry<String, Object>>(
				colonyMap.entrySet())) {
			if (!(entry.getValue() instanceof String)) continue;
			String systemId = entry.getKey();
			String marketId = (String) entry.getValue();

			List<String> ids = new ArrayList<String>();
			ids.add(marketId);
			colonyMap.put(systemId, ids);

			moveKey(ThreatIncData.KEY_GARRISONS, systemId, marketId);
			moveKey(ThreatIncData.KEY_GARRISON_SPAWN_TIMES, systemId, marketId);
			moveKey(ThreatIncData.KEY_GROWTH_TIMES, systemId, marketId);
			moveKey(ThreatIncData.KEY_LAST_PURGE_TIMES, systemId, marketId);
		}

		// waves: old shape fleet[systemId], target[systemId]=planetId
		Map<String, Object> fleets = ThreatIncData.map(ThreatIncData.KEY_WAVE_FLEETS);
		Map<String, Object> targets = ThreatIncData.map(ThreatIncData.KEY_WAVE_TARGETS);
		Map<String, Object> oldTargets = new LinkedHashMap<String, Object>(targets);
		for (Map.Entry<String, Object> entry : oldTargets.entrySet()) {
			String systemId = entry.getKey();
			if (!(entry.getValue() instanceof String)) continue;
			String planetId = (String) entry.getValue();
			Object fleet = fleets.remove(systemId);
			targets.remove(systemId);
			if (fleet != null) fleets.put(planetId, fleet);
			targets.put(planetId, systemId);
		}

		// pre-v3 seeds predate the one-time-event rule; let them hatch
		for (Map.Entry<String, String> entry : ThreatIncData.stages().entrySet()) {
			if (ThreatIncData.STAGE_SEEDED.equals(entry.getValue())
					&& !ThreatIncData.bootstrapSeeds().contains(entry.getKey())) {
				ThreatIncData.bootstrapSeeds().add(entry.getKey());
			}
		}
		ThreatIncConfig.log("Migrated data layout to multi-colony (v3).");
	}

	protected static void moveKey(String mapKey, String fromKey, String toKey) {
		Map<String, Object> raw = ThreatIncData.map(mapKey);
		Object val = raw.remove(fromKey);
		if (val != null) raw.put(toKey, val);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	protected static StarSystemAPI getSystem(String systemId) {
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	/**
	 * Removes the heavy Threat sensor-stealth penalty from one of our fleets.
	 * Vanilla Threat fleets are near-invisible (0.1x detection) and rely on
	 * ThreatFleetBehaviorScript to restore normal range when the player has the
	 * sensor mods - a script createThreatFleet does not attach. Our garrisons
	 * and waves are tied to colonies already visible on the map, so they should
	 * simply be seen; strip the stealth mult rather than fight that machinery.
	 */
	public static void makeDetectable(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		fleet.getDetectedRangeMod().unmodifyMult(
				DisposableThreatFleetManager.THREAT_DETECTED_RANGE_MULT_ID);
	}

	/**
	 * Debug-only narration: colony growth, forge restructuring, wave outcomes,
	 * and so on. In normal play the swarm is silent - the player learns of it
	 * through discovery (visiting infested space), through travel/raid intel,
	 * and through the sector's own bounties. Debug mode restores the running
	 * commentary for playtesting.
	 */
	public static void announce(String text, Color color) {
		if (!ThreatIncConfig.debugMode()) return;
		announceAlways(text, color);
	}

	/** The rare always-shown message: initial incursion flavor and the like. */
	public static void announceAlways(String text, Color color) {
		MessageIntel msg = new MessageIntel(text, color);
		IncursionManager.setThreatIcon(msg);
		Global.getSector().getCampaignUI().addMessage(msg);
	}

	/**
	 * Debug tool: erases every Threat colony in the system on the spot -
	 * garrisons despawn, markets decivilize through the full vanilla teardown,
	 * bookkeeping clears. Exists so the ripple effects of losing a system
	 * (accessibility drops, shortages cascading through the hive network) can
	 * be observed on demand.
	 */
	public static void purgeSystemDebug(String systemId) {
		StarSystemAPI system = getSystem(systemId);
		for (String marketId : new ArrayList<String>(ThreatIncData.colonyMarketsFor(systemId))) {
			for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
					ThreatIncData.garrisonsFor(marketId))) {
				if (curr != null && curr.isAlive()) curr.despawn();
			}
			MarketAPI market = findMarketAnywhere(marketId, system);
			if (market != null) {
				boolean inEconomy = market.isInEconomy();
				cleanColonyMods(market);
				if (inEconomy) DecivTracker.removeColony(market, false);
				if (market.getPrimaryEntity() != null) {
					market.getPrimaryEntity().setFaction(Factions.NEUTRAL);
				}
			}
		}
		// waves in transit to this system withdraw
		for (String planetId : new ArrayList<String>(ThreatIncData.waveTargets().keySet())) {
			if (!systemId.equals(ThreatIncData.waveTargets().get(planetId))) continue;
			CampaignFleetAPI fleet = ThreatIncData.waveFleets().remove(planetId);
			ThreatIncData.waveTargets().remove(planetId);
			if (fleet != null && fleet.isAlive()) fleet.despawn();
		}
		ThreatIncData.clearSystem(systemId);
		ThreatIncConfig.log("DEBUG purge: system " + systemId + " wiped.");
	}
}
