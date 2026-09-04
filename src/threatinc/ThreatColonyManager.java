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
import com.fs.starfarer.api.impl.campaign.ids.Items;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.econ.impl.InstallableItemEffect;
import com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo;
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
	/** The FabricatorEscortStrength ordinal a swarm was fabricated at. */
	public static final String SWARM_TIER_KEY = "$threatinc_swarmTier";
	/** How many fabricator ships a swarm was fabricated with. */
	public static final String SWARM_FABS_KEY = "$threatinc_swarmFabs";
	public static final String WAVE_FLAG = "$threatinc_colonyFleet";
	/** Market id of the colony an in-transit reinforcement swarm is flying to join. */
	public static final String REINFORCE_TARGET_KEY = "$threatinc_reinforceTarget";
	/** Fleet points a garrison swarm had the moment it was fabricated (under-strength baseline). */
	public static final String SWARM_SPAWN_FP = "$threatinc_swarmSpawnFP";

	public static final String STABILITY_MOD_ID = "threatinc_machine";

	/** Colony-UI readout of the growth/decline engine (HiveVitalityCondition). */
	public static final String HIVE_VITALITY_CONDITION = "threatinc_hive_vitality";

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
	 * The production chain beyond mining, in bootstrap order: the industry of
	 * each link (a forge is Heavy Industry or its Orbital Works upgrade) and
	 * the commodity it puts on the hive market.
	 */
	protected static final String[] CHAIN_LINKS = {
			Industries.REFINING, Industries.HEAVYINDUSTRY, Industries.FUELPROD };
	protected static final String[] CHAIN_OUTPUTS = {
			Commodities.METALS, Commodities.SHIPS, Commodities.FUEL };

	/**
	 * Adds at most one industry/structure to the colony, chosen by what the
	 * hive economy as a whole is missing. Called at founding and after each
	 * growth step, so the network develops organically: mining worlds where
	 * the rocks are, then the chain, then spare copies of it.
	 *
	 * The vanilla economy the hive runs on is not a flow of goods. What a
	 * colony can draw of a commodity is the output of the single best source
	 * it can reach (capped by shipping capacity, see docs/hive-economy.md),
	 * and demand elsewhere never subtracts from it: one refinery feeds any
	 * number of forges, and a second, equal refinery adds nothing until the
	 * first is lost. So the planner never balances producer counts against
	 * demand. It builds every link once, then spreads spare copies across
	 * systems - a siege takes a whole system - so that cutting the hive's
	 * supply of anything means cutting several worlds; and, since a source
	 * only feeds a consumer up to its own output, a bigger copy wherever the
	 * hive's biggest consumer has outgrown its biggest producer.
	 */
	public static void planHiveEconomy(MarketAPI market) {
		int size = market.getSize();

		// the port stays a Spaceport for life (see ensureSpaceport)
		ensureSpaceport(market);

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
			markEconomyDirty();
			ThreatIncConfig.log("Hive planner: MINING at " + market.getName());
			return;
		}

		// bootstrap: the first copy of each link, wherever there is room
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (countLink(link) == 0 && tryBuildLink(market, link, "first", false)) return;
		}

		// upgrade an established forge to orbital works for better hulls - improves
		// output/quality without changing the forge count
		if (size >= 6 && market.hasIndustry(Industries.HEAVYINDUSTRY)) {
			market.removeIndustry(Industries.HEAVYINDUSTRY, null, true);
			market.addIndustry(Industries.ORBITALWORKS);
			markEconomyDirty();
			announce("The fabrication colony on " + market.getName()
					+ " has restructured itself into a forge world. Hull output from the "
					+ "swarm's shipyards there is accelerating.",
					Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Hive planner: ORBITALWORKS at " + market.getName());
			return;
		}

		// redundancy: every link at two copies before any at three, spare copies
		// steered to the system holding the fewest
		int target = redundancyTarget();
		for (int level = 1; level < target; level++) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				if (countLink(link) <= level && tryBuildLink(market, link, "spare", true)) return;
			}
		}

		// the chain is complete: a bigger copy of any link whose largest producer
		// no longer covers the hive's largest consumer of its output
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (outputCovered(link) || size <= largestLinkSize(link)) continue;
			if (tryBuildLink(market, link, "bigger", false)) return;
		}
	}

	/**
	 * Builds the link's industry here if the colony lacks it and the hive
	 * market carries its input. A refinery wants ore, a fuel plant volatiles;
	 * forges take metals, which the first refinery already covers.
	 *
	 * @param spread whether the copy must go to a lean system (spreadAllows)
	 * @return true if it placed the industry
	 */
	protected static boolean tryBuildLink(MarketAPI market, int link, String label, boolean spread) {
		if (hasLink(market, link)) return false;
		String industry = CHAIN_LINKS[link];
		if (Industries.REFINING.equals(industry) && !groupHasIndustry(Industries.MINING)) return false;
		if (Industries.FUELPROD.equals(industry) && !groupHasVolatiles()) return false;
		if (spread && !spreadAllows(market, link)) return false;
		market.addIndustry(industry);
		markEconomyDirty();
		ThreatIncConfig.log("Hive planner: " + industry + " (" + label + ") at " + market.getName());
		return true;
	}

	protected static boolean hasLink(MarketAPI market, int link) {
		if (Industries.HEAVYINDUSTRY.equals(CHAIN_LINKS[link])) return getForge(market) != null;
		return market.hasIndustry(CHAIN_LINKS[link]);
	}

	/** Copies of the link across the hive, built or building. */
	protected static int countLink(int link) {
		int count = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (hasLink(market, link)) count++;
		}
		return count;
	}

	protected static int countLinkIn(String systemId, int link) {
		int count = 0;
		for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (hasLink(market, link)) count++;
		}
		return count;
	}

	/** Size of the largest colony holding the link, built or building; 0 without one. */
	protected static int largestLinkSize(int link) {
		int best = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (hasLink(market, link)) best = Math.max(best, market.getSize());
		}
		return best;
	}

	/**
	 * Whether the hive's largest producer of the link's output covers its
	 * largest single consumer. Availability is per source, so this - not the
	 * hive's total output against its total demand - is what decides whether
	 * every consumer is fed. A copy still under construction supplies nothing
	 * yet; largestLinkSize counts it, which keeps the planner from stacking
	 * bigger copies while one is building.
	 */
	protected static boolean outputCovered(int link) {
		String id = CHAIN_OUTPUTS[link];
		int supply = 0;
		int demand = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			CommodityOnMarketAPI com = market.getCommodityData(id);
			if (com == null) continue;
			supply = Math.max(supply, com.getMaxSupply());
			demand = Math.max(demand, com.getMaxDemand());
		}
		return supply >= demand;
	}

	/**
	 * Spare copies go to the system holding the fewest, so a single siege can't
	 * take the hive's whole supply of anything. A colony may host one if its
	 * system is among the leanest - or if no leaner system has a world that
	 * could ever take it (every one full and size-capped), so a stunted seed
	 * system can't hold the rest of the hive's redundancy hostage.
	 */
	protected static boolean spreadAllows(MarketAPI market, int link) {
		List<String> systems = liveColonySystemIds();
		String here = null;
		int min = Integer.MAX_VALUE;
		for (String systemId : systems) {
			min = Math.min(min, countLinkIn(systemId, link));
			if (here != null) continue;
			for (MarketAPI curr : ThreatIncData.getLiveColonyMarkets(systemId)) {
				if (curr.getId().equals(market.getId())) here = systemId;
			}
		}
		if (here == null || countLinkIn(here, link) <= min) return true;
		for (String systemId : systems) {
			if (countLinkIn(systemId, link) > min) continue;
			for (MarketAPI other : ThreatIncData.getLiveColonyMarkets(systemId)) {
				if (hasLink(other, link)) continue;
				if (Misc.getNumIndustries(other) < Misc.getMaxIndustries(other)
						|| other.getSize() < ThreatIncConfig.colonyMaxSize()) return false;
			}
		}
		return true;
	}

	/** Copies of each chain link the hive wants: one per system it holds, capped by config. */
	protected static int redundancyTarget() {
		return Math.max(1, Math.min(ThreatIncConfig.chainRedundancy(), liveColonySystemIds().size()));
	}

	protected static List<String> liveColonySystemIds() {
		List<String> result = new ArrayList<String>();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (!ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) result.add(systemId);
		}
		return result;
	}

	/**
	 * A hive world's port is a Spaceport, never a Megaport. The hive used to
	 * upgrade to Megaports for the accessibility, on the theory that
	 * accessibility was its supply line; it is not (same-faction shipping is
	 * 5+ units at 0% and no hive producer makes more than 8 - see
	 * docs/hive-economy.md), so the Megaport bought nothing and cost the one
	 * thing that showed: it demands fuel at colony size, where a Spaceport
	 * demands size-2, which is exactly what a fuel plant of the same size
	 * makes. On Megaports every hive world read a permanent fuel shortage by
	 * construction. Retired Sept 2026; this also migrates saves that already
	 * built them. Idempotent.
	 *
	 * @return true if it swapped a Megaport out this call
	 */
	public static boolean ensureSpaceport(MarketAPI market) {
		if (market == null) return false;
		if (!market.hasIndustry(Industries.MEGAPORT)) return false;
		market.removeIndustry(Industries.MEGAPORT, null, true);
		if (!market.hasIndustry(Industries.SPACEPORT)) market.addIndustry(Industries.SPACEPORT);
		markEconomyDirty();
		ThreatIncConfig.log("Hive planner: Megaport retired for a Spaceport at " + market.getName());
		return true;
	}

	/** Tick sweep: every live colony back on a Spaceport (older saves built Megaports). */
	public static void maintainPorts() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			ensureSpaceport(market);
		}
	}

	// ------------------------------------------------------------------
	// home relics: the first hive carries what it woke up with
	// ------------------------------------------------------------------

	/** A deposit this rich (vanilla "rich"/"plentiful", +2) feeds a same-size consumer at size+2. */
	public static final int IDEAL_DEPOSIT_MOD = 2;

	/**
	 * The home system's industries carry Domain-era items, the way the swarm
	 * that woke in the Abyss would have salvaged them. Vanilla's chain only
	 * balances at the top on rich deposits and nanoforges - a size-8 forge
	 * makes 6 hulls against a Nexus wanting 8, Refining wants ore at size+2 -
	 * so the seed system, which every later colony draws on, gets the items
	 * vanilla uses to close those gaps, and only where its own rocks fall
	 * short: a Corrupted Nanoforge on the forge always, a Mantle Bore or
	 * Plasma Dynamo on a mine whose deposit is below rich, a Catalytic Core on
	 * a refinery short of ore, a Synchrotron on a fuel plant short of
	 * volatiles. Each item goes in only where vanilla's own requirements for it
	 * are met (ItemEffectsRepo), so nothing sits installed and inert. Later
	 * colonies get nothing: the home hive is the hub worth taking, and the
	 * items are the loot for taking it. Idempotent tick sweep; also equips
	 * older saves.
	 */
	public static void maintainHomeRelics() {
		if (!ThreatIncConfig.homeRelics()) return;
		String ogId = ThreatIncData.getOGSystem();
		if (ogId != null) {
			for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(ogId)) {
				for (Industry ind : market.getIndustries()) {
					if (ind.getSpecialItem() != null || ind.isBuilding()) continue;
					String item = relicFor(market, ind);
					if (item == null) continue;
					installRelic(market, ind, item);
				}
			}
		}
		maintainNanoforges(ogId);
	}

	/**
	 * Nanoforges: one Pristine on the home system's largest forge - the hive's
	 * hull ceiling, 6 + 3 = 9 against a Nexus wanting 8 at size 8, so a fed
	 * home forge fills every garrison in the hive - and, on every other forge,
	 * a one-in-five find of a Corrupted one (threatinc_forgeNanoforgeChance).
	 * The find is a fixed roll per world (its id hashed), so the answer never
	 * changes from tick to tick and a rebuilt forge gets the same luck.
	 */
	protected static void maintainNanoforges(String ogId) {
		if (ogId != null) {
			boolean havePristine = false;
			Industry best = null;
			int bestSize = 0;
			for (MarketAPI market : ThreatIncData.getLiveColonyMarkets(ogId)) {
				Industry forge = getForge(market);
				if (forge == null || forge.isBuilding()) continue;
				if (forge.getSpecialItem() != null) {
					if (Items.PRISTINE_NANOFORGE.equals(forge.getSpecialItem().getId())) havePristine = true;
					continue;
				}
				if (best == null || market.getSize() > bestSize) {
					best = forge;
					bestSize = market.getSize();
				}
			}
			if (!havePristine && best != null) installRelic(best.getMarket(), best, Items.PRISTINE_NANOFORGE);
		}
		float chance = ThreatIncConfig.forgeNanoforgeChance();
		if (chance <= 0f) return;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			Industry forge = getForge(market);
			if (forge == null || forge.isBuilding() || forge.getSpecialItem() != null) continue;
			float roll = (Math.abs(market.getId().hashCode()) % 1000) / 1000f;
			if (roll >= chance) continue;
			installRelic(market, forge, Items.CORRUPTED_NANOFORGE);
		}
	}

	/** Installs the item if vanilla's requirements for it hold here; logs it. */
	protected static boolean installRelic(MarketAPI market, Industry ind, String item) {
		if (!relicFits(item, ind)) return false;
		ind.setSpecialItem(new SpecialItemData(item, null));
		markEconomyDirty();
		ThreatIncConfig.log("Relic: " + item + " installed in " + ind.getCurrentName()
				+ " at " + market.getName());
		return true;
	}

	/** The deposit-covering item that closes this industry's gap here, or null when nothing is short. */
	protected static String relicFor(MarketAPI market, Industry ind) {
		String id = ind.getId();
		if (Industries.MINING.equals(id)) {
			boolean gasGiant = market.getPlanetEntity() != null && market.getPlanetEntity().isGasGiant();
			if (gasGiant) {
				return depositMod(market, Commodities.VOLATILES) < IDEAL_DEPOSIT_MOD ? Items.PLASMA_DYNAMO : null;
			}
			boolean ore = hasDeposit(market, Commodities.ORE) && depositMod(market, Commodities.ORE) < IDEAL_DEPOSIT_MOD;
			boolean rare = hasDeposit(market, Commodities.RARE_ORE)
					&& depositMod(market, Commodities.RARE_ORE) < IDEAL_DEPOSIT_MOD;
			return ore || rare ? Items.MANTLE_BORE : null;
		}
		if (Industries.REFINING.equals(id)) {
			return inputShort(market, ind, Commodities.ORE) ? Items.CATALYTIC_CORE : null;
		}
		if (Industries.FUELPROD.equals(id)) {
			return inputShort(market, ind, Commodities.VOLATILES) ? Items.SYNCHROTRON : null;
		}
		return null;
	}

	/** Whether vanilla would let this item work in this industry (planet type, atmosphere...). */
	protected static boolean relicFits(String itemId, Industry ind) {
		try {
			InstallableItemEffect effect = ItemEffectsRepo.ITEM_EFFECTS.get(itemId);
			if (effect == null) return false;
			List<String> unmet = effect.getUnmetRequirements(ind);
			return unmet == null || unmet.isEmpty();
		} catch (Throwable t) {
			return false;
		}
	}

	/** The industry wants more of the input than this world can get. */
	protected static boolean inputShort(MarketAPI market, Industry ind, String commodityId) {
		MutableCommodityQuantity q = ind.getDemand(commodityId);
		if (q == null || q.getQuantity().getModifiedInt() <= 0) return false;
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		return com == null || com.getAvailable() < q.getQuantity().getModifiedInt();
	}

	protected static boolean hasDeposit(MarketAPI market, String commodityId) {
		for (MarketConditionAPI cond : market.getConditions()) {
			if (commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) return true;
		}
		return false;
	}

	/** The world's deposit modifier for a commodity (-1 sparse .. +3 ultrarich), 0 without one. */
	protected static int depositMod(MarketAPI market, String commodityId) {
		for (MarketConditionAPI cond : market.getConditions()) {
			if (!commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) continue;
			Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
			return mod != null ? mod : 0;
		}
		return 0;
	}

	/**
	 * Vanilla recomputes market availability - what each world can draw from
	 * the others - on its own monthly economy step, while an industry's local
	 * supply updates the moment it is (re)applied. So after the tick builds an
	 * industry, swaps a port or installs a relic, the producing world reads the
	 * new figure at once and every importer lags up to a month behind: a
	 * Pristine Nanoforge showing 9 hulls at home and 6 everywhere else. Set by
	 * every structural change the tick makes; flushEconomy then runs vanilla's
	 * own full recompute (tripleStep, what sector generation uses) once, so
	 * the board and the planner see one consistent economy.
	 */
	protected static boolean economyDirty = false;

	public static void markEconomyDirty() {
		economyDirty = true;
	}

	/** End of tick: one full economy recompute if anything structural changed. */
	public static void flushEconomy() {
		if (!economyDirty) return;
		economyDirty = false;
		try {
			Global.getSector().getEconomy().tripleStep();
			ThreatIncConfig.log("Economy recomputed after hive structural changes");
		} catch (Throwable t) {
			ThreatIncConfig.log("Economy recompute failed: " + t);
		}
	}

	/**
	 * Tick sweep for the planner's hive-wide rules. planHiveEconomy runs at
	 * founding and on each growth step, which is where a colony's OWN needs
	 * are decided; but redundancy and bigger-copy targets move as the rest of
	 * the hive changes (a system lost, a consumer grown), and a size-capped
	 * colony never grows again, so on its own it would never fill a free slot
	 * however far the hive fell below target. Re-plans only capped colonies
	 * with a free industry slot - growing ones still build on their growth
	 * steps - one industry per colony per tick, which is about the pace of a
	 * vanilla construction anyway.
	 */
	public static void maintainHiveEconomy() {
		int cap = ThreatIncConfig.colonyMaxSize();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < cap) continue;
			if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) continue;
			planHiveEconomy(market);
		}
	}

	// ------------------------------------------------------------------
	// hive accessibility: the swarm doesn't trade through the human Core
	// ------------------------------------------------------------------

	public static final String ACCESS_MOD_ID = "threatinc_coredist";
	public static final String PORT_DOWN_MOD_ID = "threatinc_portdown";

	/**
	 * The accessibility that gives exactly the configured same-faction
	 * shipping capacity while a port is disrupted. Misc.getShippingCapacity is
	 * (accessibility + SAME_FACTION_BONUS) / PER_UNIT_SHIPPING units, truncated
	 * (vanilla: +0.5, 0.1 per unit), so this aims at the middle of the unit's
	 * band - 3 units is -15% - to stay clear of float edges. 0 units is -50%,
	 * nothing docks at all. Read from vanilla's constants so a settings change
	 * tracks.
	 */
	public static float portDownAccessibility() {
		int units = Math.max(0, ThreatIncConfig.disruptedPortShipping());
		return (units + 0.5f) * Misc.PER_UNIT_SHIPPING - Misc.SAME_FACTION_BONUS;
	}

	/**
	 * The two accessibility modifiers the hive applies, re-asserted every poll
	 * so they track the economy and survive save load: the Core-distance
	 * refund and the disrupted-port cut. Everything else about hive trade is
	 * vanilla's - see docs/hive-economy.md.
	 *
	 * Core distance: vanilla docks a market's accessibility by its distance
	 * from the economy's centre of mass - a size-weighted centroid that the
	 * many large Core worlds pull to the Core. That penalty models dependence
	 * on the Core trade hub. The hive has no such dependence: it is a closed
	 * econ group, hostile to everyone, pulled from the trade-fleet network -
	 * every commodity it receives comes from its own colonies. Charging it a
	 * Core-distance penalty models a supply line that does not exist, and it
	 * cripples exactly the fringe colonies the swarm is built to seed. So that
	 * one component is cancelled, restored as a flat bonus. Note that this
	 * leaves nothing that falls with distance: vanilla's same-faction proximity
	 * term is only ever a bonus, so a hive colony's imports and reach do not
	 * depend on how far it sits from its siblings.
	 */
	public static void applyHiveAccessibility() {
		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		if (colonies.isEmpty()) return;

		float fraction = ThreatIncConfig.coreDistanceOffset();
		Vector2f com = fraction > 0f ? economyCenterOfMass() : null;
		// vanilla: accessibility loses 1.0 per this many LY from the COM
		float lyPerUnit = com != null
				? Global.getSettings().getFloat("accessibilityDistFromCOM") : 0f;

		for (MarketAPI market : colonies) {
			if (com == null || lyPerUnit <= 0f) {
				// feature off: make sure no stale bonus lingers from a prior setting
				market.getAccessibilityMod().unmodifyFlat(ACCESS_MOD_ID);
			} else {
				float dist = Misc.getDistanceLY(market.getLocationInHyperspace(), com);
				float penalty = dist / lyPerUnit;
				// clamp so a COM estimate that drifts from vanilla's can never turn
				// this into a runaway accessibility fountain
				float offset = Math.max(0f, Math.min(2f, penalty * fraction));
				market.getAccessibilityMod().modifyFlat(ACCESS_MOD_ID, offset,
						"Hive network (Core distance not applicable)");
			}
			applyPortDisruption(market);
		}
	}

	/**
	 * A disrupted port is a skeleton port. Vanilla deliberately keeps a
	 * disrupted Spaceport flagged as present (Spaceport.apply re-asserts
	 * hasSpaceport even while non-functional), so a Core world only loses the
	 * port's own bonus and its shipping barely moves - and for the hive,
	 * whose producers never make more than 6 units, that meant a disrupted
	 * Megaport changed nothing (same-faction shipping is 5+ units down to 0%
	 * accessibility). So while a hive world's port is disrupted its
	 * accessibility is held at portDownAccessibility(): shipping capped at
	 * threatinc_disruptedPortShipping units both ways (default 3). A mature
	 * world's factories run on a trickle - a size-8 forge fed 3 of 8 metals -
	 * growth stalls, and what the world makes reaches its siblings only as
	 * that trickle (the hive falls back to its next-best source of it).
	 *
	 * Deliberately NOT zero. Vitality is fabrication x supply, and the siege
	 * raids the Nexus, then the Core, then the port; with the Core down a
	 * colony's machinery is imported, so a zero-shipping port on top drove
	 * supply to 0 and the decline rate to its maximum - a port cut worse than
	 * the Core cut, which is backwards. The port is logistics: it slows a
	 * colony, the Core kills it.
	 *
	 * Computed against the stat's current value rather than a fixed penalty:
	 * the Core-distance refund and vanilla's proximity bonus can hold a well
	 * placed colony above 0% even after vanilla's own -100% no-spaceport
	 * figure. Re-applied every poll (the stat changes as vanilla's own
	 * modifiers move) and lifted the moment the port is back.
	 */
	protected static void applyPortDisruption(MarketAPI market) {
		boolean had = market.getAccessibilityMod().getFlatBonuses().containsKey(PORT_DOWN_MOD_ID);
		market.getAccessibilityMod().unmodifyFlat(PORT_DOWN_MOD_ID);
		Industry port = getPort(market);
		boolean down = ThreatIncConfig.disruptedPortShipping() >= 0
				&& port != null && port.isDisrupted();
		if (!down) {
			if (had) ThreatIncConfig.log("Port back up at " + market.getName() + ": shipping restored");
			return;
		}
		float target = portDownAccessibility();
		float current = market.getAccessibilityMod().computeEffective(0f);
		if (current > target) {
			market.getAccessibilityMod().modifyFlat(PORT_DOWN_MOD_ID, target - current,
					"Port disrupted - skeleton docking only");
		}
		if (!had) {
			ThreatIncConfig.log("Port disrupted at " + market.getName() + ": accessibility "
					+ Math.round(current * 100f) + "% -> " + Math.round(target * 100f)
					+ "%, shipping " + Misc.getShippingCapacity(market, true) + " units");
		}
	}

	/** The colony's port: its Spaceport, or a Megaport an older save has not yet swapped out. */
	public static Industry getPort(MarketAPI market) {
		if (market == null) return null;
		Industry port = market.getIndustry(Industries.MEGAPORT);
		if (port == null) port = market.getIndustry(Industries.SPACEPORT);
		return port;
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
		// the colony-UI vitality readout; idempotent, and doubles as the
		// migration path for colonies founded before the condition existed
		if (!market.hasCondition(HIVE_VITALITY_CONDITION)) {
			market.addCondition(HIVE_VITALITY_CONDITION);
		}
		if (!market.hasIndustry(SWARM_NEXUS)) {
			market.addIndustry(SWARM_NEXUS);
			ThreatIncConfig.log("Swarm Nexus added at " + market.getName());
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

	/**
	 * RETIRED mechanic cleanup: removes the Fragment Fabricator item (and its
	 * seeded flag) from every hive colony and husk. The item used to screen
	 * colonies from player bombardment; with the siege rework it no longer
	 * exists, and its InstallableItemEffect stub is gone - any industry still
	 * holding one would crash the colony UI. Idempotent; run at load for old
	 * saves (before any UI can render) and again by the v4 data migration.
	 */
	public static void stripFragmentFabricators() {
		String itemId = com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			StarSystemAPI system = getSystem(systemId);
			for (String marketId : new ArrayList<String>(ThreatIncData.colonyMarketsFor(systemId))) {
				MarketAPI market = findMarketAnywhere(marketId, system);
				if (market == null) continue;
				for (Industry ind : market.getIndustries()) {
					if (ind.getSpecialItem() != null
							&& itemId.equals(ind.getSpecialItem().getId())) {
						ind.setSpecialItem(null);
						ThreatIncConfig.log("Fragment Fabricator stripped from "
								+ market.getName() + " (retired mechanic).");
					}
				}
				market.getMemoryWithoutUpdate().unset("$threatinc_fabricatorSeeded");
			}
		}
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

	// rare-earth branch (rare ore -> rare metals). Ordinary growth inputs, same
	// rules as the core four, for any incursion whose OG economy was built
	// around rare ore (i.e. normal starts); they keep counting after the last
	// rare mine is gone, so wiping out rare mining genuinely strangles
	// expansion until the hive re-establishes it. Only a degenerate rare-free
	// start skips them (see ThreatIncData.usesRareEconomy). They used to be
	// special-cased - excluded from the supply average, biting only on total
	// cutoff - on the theory that rare deposits are structurally short and
	// would drag vitality forever. Dropped Sept 2026: a shortage is a shortage.
	// A hive seeded on a poor rare deposit is a little short for good, and the
	// board should say so rather than read 100% beside a red icon.
	protected static final String[] RARE_INPUTS = {
			Commodities.RARE_ORE, Commodities.RARE_METALS };

	/** The inputs that gate growth and set the supply half of vitality. */
	public static String[] growthInputs() {
		if (!ThreatIncData.usesRareEconomy()) return CORE_INPUTS;
		String[] all = new String[CORE_INPUTS.length + RARE_INPUTS.length];
		System.arraycopy(CORE_INPUTS, 0, all, 0, CORE_INPUTS.length);
		System.arraycopy(RARE_INPUTS, 0, all, CORE_INPUTS.length, RARE_INPUTS.length);
		return all;
	}

	/**
	 * A colony is starved when its own industries can't get their inputs - a
	 * refinery with no ore, a forge with no metals, a mine with no machinery.
	 * That happens when the hive's supply lines are cut - a link colony
	 * destroyed, or this colony's own port disrupted (applyPortDisruption) -
	 * exactly the intended siege pressure. A frontier colony whose factories are
	 * fed is healthy even if its population goods run short. Distance from the
	 * rest of the hive is not a factor: accessibility never falls with it (see
	 * docs/hive-economy.md).
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
	 * the flow. A colony whose port is disrupted stalls through this same check:
	 * its shipping capacity is zero, so it imports nothing and lives on what it
	 * makes itself. (Isolation by distance does not: vanilla's same-faction
	 * proximity term is only ever a bonus, and the Core-distance penalty is
	 * refunded by applyHiveAccessibility.)
	 */
	public static boolean isEconomicallyHealthy(MarketAPI market) {
		if (!ThreatIncConfig.economyGatesGrowth()) return true;
		if (market == null) return false;
		// rare ore and rare metals included (growthInputs): a hive whose rare
		// mine is far smaller than its refineries and forges stalls those worlds
		// until the mine grows - it can, its own growth never depends on rare
		// metals unless it also hosts a forge, and at equal sizes on a moderate
		// deposit the rare branch balances. No deadlock, just honest pacing.
		return chokedInput(market, growthInputs()) == null;
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
		for (String commodityId : growthInputs()) inputs.add(commodityId);
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
	 * How far expeditions from this colony reach, in light-years - one rule
	 * for hive worlds, faction military worlds and the player's colonies:
	 *
	 *   reach = strikeLYPerFuel x min(fuel available, fuel the fleets can carry)
	 *
	 * Fuel available is the vanilla economy's figure: the output of the single
	 * best fuel source this colony can reach, capped by its shipping capacity -
	 * not a sum, not local production alone (docs/hive-economy.md). Fuel the
	 * fleets can carry is {@link #expeditionFuelCapacity}: a fixed load per
	 * 100 percent of fleet size, scaled by vanilla's own fleet-size figure for
	 * faction and player worlds and by vitality x size / 4 for hive worlds.
	 * All the fuel in the sector is no use to a colony that only fields small
	 * fleets, so a young or besieged hive world reaches a fraction of what a
	 * size-8 world does on the same fuel. Cutting fuel still grounds everyone;
	 * cutting hulls, organs or inputs now also shortens the leash.
	 */
	public static float fuelRangeLY(MarketAPI market) {
		if (market == null) return 0f;
		CommodityOnMarketAPI fuel = market.getCommodityData(Commodities.FUEL);
		if (fuel == null) return 0f;
		float carried = Math.min(fuel.getAvailable(), expeditionFuelCapacity(market));
		return Math.max(0f, carried) * ThreatIncConfig.strikeLYPerFuel();
	}

	/**
	 * Vanilla's fleet-size multiplier for a market (Stats.COMBAT_FLEET_SIZE_MULT,
	 * read the way FleetFactoryV3 reads it): colony size x faction doctrine x
	 * hull-shortage mult x stability, plus any alpha-core bonus. The "Fleets"
	 * percentage on the colony screen.
	 */
	public static float fleetSizeMult(MarketAPI market) {
		if (market == null) return 0f;
		return Math.max(0f, market.getStats().getDynamic()
				.getMod(Stats.COMBAT_FLEET_SIZE_MULT).computeEffective(0f));
	}

	/**
	 * Units of fuel the colony's expeditions can carry: reachFuelCarry (what a
	 * fleet at 100 percent size lifts) times the colony's fleet-size figure.
	 * For faction and player worlds that figure is vanilla's own, untouched
	 * ({@link #fleetSizeMult}: colony size, doctrine, hull shortage, stability,
	 * alpha core, skills - the "Fleets" percentage on the colony screen). For
	 * a hive world it is vitality x size / 4: a healthy size-4 hive lifts a
	 * full load, size 8 twice that, a size-2 foothold half, and a colony under
	 * siege - organs down, inputs cut - loses reach with its health. A faction
	 * world with no military structure carries nothing; it sends no
	 * expeditions anyway.
	 */
	public static float expeditionFuelCapacity(MarketAPI market) {
		if (market == null) return 0f;
		float mult;
		if (ThreatIncData.resolveColonyMarket(market.getId()) != null) {
			mult = computeHealth(market) * market.getSize() / 4f;
		} else if (market.hasIndustry(Industries.HIGHCOMMAND)
				|| market.hasIndustry(Industries.MILITARYBASE)
				|| market.hasIndustry(Industries.PATROLHQ)) {
			mult = fleetSizeMult(market);
		} else {
			return 0f;
		}
		return ThreatIncConfig.reachFuelCarry() * mult;
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
			// a strike musters at least two Defense Swarms above the reserve:
			// the colony launches from strength, at full garrison, or not at all
			if (requireReadyForge && garrisonAvailableForLaunch(curr) < 2) continue;
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
	 * Everything comes from somewhere: an expedition's fleets ARE the colony's
	 * Defense Swarms, mustered off their orbits and sent out. The Swarm Nexus
	 * never pauses - it keeps growing replacement swarms at its usual cadence
	 * (maintainGarrisons) - so launch tempo is bought with real standing
	 * forces, not a disruption timer. The counterplay follows naturally:
	 * killing a colony's swarms IS disrupting it - a thinned garrison can't
	 * muster an expedition until the nexus regrows it.
	 */

	/**
	 * Defense Swarms this colony's nexus actually builds toward: the nominal
	 * size table, degraded by the hive economy's real hull supply. This is
	 * the SAME number maintainGarrisons grows to - the "full garrison" bar
	 * for launches must match what the nexus can actually deliver, or a
	 * strained hive would wait forever for fleets that never come.
	 */
	public static int desiredGarrisonCount(MarketAPI market) {
		if (market == null) return 0;
		int nominal = desiredGarrison(market.getSize()).length;
		int desired = Math.max(1, Math.round(nominal * shipSupplyMult(market)));
		return Math.min(desired, nominal);
	}

	/** Defense Swarms a colony always keeps home; it never musters these. */
	public static int garrisonReserve(MarketAPI market) {
		return Math.max(1, desiredGarrisonCount(market) / 2);
	}

	/**
	 * Defense Swarms the colony is willing to send out: only what stands above
	 * its reserve, and only once the garrison is at full (economy-scaled)
	 * strength - a colony still regrowing its swarms launches nothing.
	 */
	public static int garrisonAvailableForLaunch(MarketAPI market) {
		if (market == null) return 0;
		int desired = desiredGarrisonCount(market);
		int live = countLiveGarrison(market.getId());
		if (live < desired) return 0;
		return Math.max(0, live - garrisonReserve(market));
	}

	/**
	 * Musters up to count Defense Swarms as the substance of an expedition:
	 * the fleets leave the garrison (despawned here; the expedition machinery
	 * re-embodies them as its own fleets). Sends the LARGEST swarms - the
	 * reserve that stays is the smaller ones. Returns each mustered swarm's
	 * expedition fleet size (see expeditionSizeFor), so the expedition fields
	 * exactly the fleets that left orbit.
	 */
	public static List<Integer> consumeGarrison(MarketAPI market, int count) {
		List<Integer> mustered = new ArrayList<Integer>();
		if (market == null || count <= 0) return mustered;
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(market.getId());
		List<CampaignFleetAPI> alive = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI curr : fleets) {
			if (curr != null && curr.isAlive()) alive.add(curr);
		}
		java.util.Collections.sort(alive, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				return Float.compare(b.getFleetPoints(), a.getFleetPoints());
			}
		});
		for (CampaignFleetAPI curr : alive) {
			if (mustered.size() >= count) break;
			mustered.add(expeditionSizeFor(curr));
			fleets.remove(curr);
			curr.despawn();
		}
		if (!mustered.isEmpty()) {
			// mustering from an idle nexus STARTS the rebuild clock: finished
			// swarms are not banked while the garrison stands at capacity, so
			// the first replacement takes a full interval from this moment. A
			// build already in progress (timer running) is left to finish.
			Long last = ThreatIncData.garrisonSpawnTimes().get(market.getId());
			float interval = ThreatIncConfig.garrisonRespawnDays() * IncursionManager.timeScale();
			if (last == null || Global.getSector().getClock().getElapsedDaysSince(last) >= interval) {
				ThreatIncData.garrisonSpawnTimes().put(market.getId(),
						Global.getSector().getClock().getTimestamp());
			}
			ThreatIncConfig.log(mustered.size() + " Defense Swarm(s) mustered from "
					+ market.getName() + " (" + countLiveGarrison(market.getId())
					+ " remain on station)");
		}
		return mustered;
	}

	/**
	 * The expedition fleet size tier that re-embodies this garrison swarm as
	 * EXACTLY the fleet that left orbit: read from the tier it was fabricated
	 * at (SWARM_TIER_KEY / SWARM_FABS_KEY; see ThreatStrikeFGI.createFleet
	 * for the size-to-tier thresholds). Swarms from saves that predate the
	 * tags fall back to an FP estimate.
	 */
	protected static int expeditionSizeFor(CampaignFleetAPI swarm) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = swarm.getMemoryWithoutUpdate();
		if (mem.contains(SWARM_TIER_KEY)) {
			if (mem.getInt(SWARM_FABS_KEY) > 0) return 9;
			int tier = mem.getInt(SWARM_TIER_KEY);
			if (tier <= FabricatorEscortStrength.LOW.ordinal()) return 4;
			if (tier == FabricatorEscortStrength.MEDIUM.ordinal()) return 6;
			if (tier == FabricatorEscortStrength.HIGH.ordinal()) return 8;
			return 9;
		}
		// last-resort FP estimate, deliberately conservative: Threat fleet FP
		// runs high, so err SMALL and never conjure a fabricator armada (9)
		// out of an untagged garrison swarm
		float fp = swarm.getFleetPoints();
		if (fp < 120f) return 4;
		if (fp < 220f) return 6;
		return 8;
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
			if (!hasOperationalNexus(market)) continue;
			// the wave's substance is a mustered Defense Swarm: the colony must
			// have one to spare above its defensive reserve
			if (garrisonAvailableForLaunch(market) < 1) continue;
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

		// the expedition is paid for in real fleets: one Defense Swarm leaves
		// the source's garrison to become the seeding wave's substance
		if (source != null) consumeGarrison(source, 1);
		fleet.setName("Seeding Swarm");
		// tier tag rides the fleet: when this wave digs in as its new colony's
		// first garrison, later musters know exactly what it is
		fleet.getMemoryWithoutUpdate().set(SWARM_TIER_KEY, escortIdx);
		fleet.getMemoryWithoutUpdate().set(SWARM_FABS_KEY, 1);
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
		// visited in person, named in a contract, or launching a strike.

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
	// vitality: health-scaled growth, and decline under siege
	// ------------------------------------------------------------------

	/**
	 * How much of a defensive organ's bonus still fires while it is disrupted.
	 * Machines do not rout, so a fresh disruption leaves disruptedDefenseFraction
	 * of the bonus working - but the guns wear: the surviving fraction falls
	 * linearly with the disruption days on the structure's clock, reaching
	 * zero at defenseWearDays. Disruption stacks (tactical passes take the
	 * larger duration, every successful raid adds its own), so a structure
	 * carrying 300 days has been hit again and again, and by then it is scrap.
	 * At the defaults (0.5, 300): one tactical pass (60 d) leaves 40 percent
	 * of the bonus, two stacked raids on top (~150 d) 25 percent, 300+ d none.
	 * The size-anchored base (hiveDefensePerSize x size) is untouched - the
	 * strata below the crust do not stop existing - so bombardment never gets
	 * free, only cheaper as the war-strata are ground down. Shared by
	 * ThreatGroundDefenses (batteries too) and SwarmNexus.
	 */
	public static float disruptedDefenseResilience(Industry ind) {
		if (ind == null || !ind.isDisrupted()) return 1f;
		float fraction = ThreatIncConfig.disruptedDefenseFraction();
		float wear = ThreatIncConfig.defenseWearDays();
		if (wear <= 0f) return fraction;
		float worn = Math.max(0f, 1f - ind.getDisruptedDays() / wear);
		return fraction * worn;
	}

	/**
	 * Keeps the worn defense figure current. Vanilla only reapplies a disrupted
	 * industry when its disruption ends (BaseIndustry.advance ->
	 * disruptionFinished) or on the monthly economy step, so without this the
	 * wear in disruptedDefenseResilience would step once a month and a siege's
	 * raid odds and bombardment bill would lag the clock they are supposed to
	 * follow. Reapplying a market with a disrupted organ at the fast poll
	 * cadence is cheap - a handful of worlds, only while under siege.
	 */
	public static void refreshWornDefenses() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			boolean anyDown = false;
			for (Industry ind : market.getIndustries()) {
				if (ind.isDisrupted()) { anyDown = true; break; }
			}
			if (anyDown) market.reapplyIndustries();
		}
	}

	/** An organ counts as down when missing, disrupted, or non-functional. */
	protected static boolean organDown(Industry ind) {
		return ind == null || ind.isDisrupted() || !ind.isFunctional();
	}

	/**
	 * Whether any of the colony's key organs (Core, Nexus, port) is currently
	 * disrupted - the "under active siege" test. While true, the decline meter
	 * does not regrow, and navies press follow-up expeditions on a short
	 * cooldown instead of waiting out the full purge interval.
	 */
	public static boolean anyOrganDisrupted(MarketAPI market) {
		return !disruptedOrganNames(market).isEmpty();
	}

	/**
	 * The display names of the colony's key organs (Fabrication Core, Swarm
	 * Nexus, Port) that are currently disrupted, in that order. The single
	 * source of truth for {@link #anyOrganDisrupted}, so the vitality tooltip's
	 * "held open by" list can never drift from the actual decline-heal gate.
	 */
	public static java.util.List<String> disruptedOrganNames(MarketAPI market) {
		java.util.List<String> names = new java.util.ArrayList<String>();
		if (market == null) return names;
		Industry core = market.getIndustry(FABRICATION_CORE);
		if (core != null && core.isDisrupted()) names.add("Fabrication Core");
		Industry nexus = market.getIndustry(SWARM_NEXUS);
		if (nexus != null && nexus.isDisrupted()) names.add("Swarm Nexus");
		Industry port = getPort(market);
		if (port != null && port.isDisrupted()) names.add("Port");
		return names;
	}

	/**
	 * The ON/OFF half of colony health: FABRICATION CORE functionality. A hive's
	 * growth is literally its Fabrication Core's ability to grow new population
	 * strata - a disrupted Core isn't producing at reduced capacity, it isn't
	 * producing AT ALL (worn further by its disruption days). The Core is the
	 * ONLY fabrication organ in this figure. The Swarm Nexus is deliberately
	 * absent: it fabricates FLEETS, not population, so its disruption halts new
	 * swarm fabrication (maintainGarrisons), never the colony's vitality. The
	 * PORT is likewise absent: it is logistics, not fabrication - its disruption
	 * bites through the supply score (applyPortDisruption zeroes the world's
	 * shipping capacity: no imports here, and its exports reach no sibling),
	 * so counting it here would double-charge it and wrongly punish colonies
	 * that make their own inputs.
	 */
	public static float computeFabricationMult(MarketAPI market) {
		if (market == null) return 0f;
		float mult = 1f;
		Industry core = market.getIndustry(FABRICATION_CORE);
		if (organDown(core)) mult *= wornDownFactor(core, ThreatIncConfig.coreDownFactor());
		return mult;
	}

	/**
	 * A downed organ's fabrication factor, worn further by the disruption
	 * days on its clock - the same linear wear the defensive bonuses take
	 * (defenseWearDays). A Core freshly knocked out runs at coreDownFactor;
	 * one carrying 150 days, hit again and again, at half that; at 300 days
	 * nothing fabricates at all. A missing organ, or one under construction,
	 * sits at the base factor.
	 */
	protected static float wornDownFactor(Industry ind, float base) {
		if (ind == null || !ind.isDisrupted()) return base;
		float wear = ThreatIncConfig.defenseWearDays();
		if (wear <= 0f) return base;
		return base * Math.max(0f, 1f - ind.getDisruptedDays() / wear);
	}

	/**
	 * The REDUCED-CAPACITY half: input satisfaction. Availability is the
	 * vanilla economy's figure - the best single source this colony can reach,
	 * capped by its shipping capacity - so killing or starving a producer, or
	 * disrupting the port of the world that hosts it, starves every colony it
	 * fed. (Piracy and other small accessibility maluses do not: same-faction
	 * shipping stays at 5+ units down to 0% accessibility.) Working organs on
	 * thin supply run slower; they don't stop.
	 */
	public static float computeSupplyMult(MarketAPI market) {
		if (market == null) return 0f;
		float inputScore = 1f;
		if (ThreatIncConfig.economyGatesGrowth()) {
			float total = 0f;
			int counted = 0;
			// every growth input this colony demands, rare branch included, each
			// weighted equally by how much of its demand is met: a size-8 forge
			// world fed 4 of 6 rare metals reads two-thirds on that input, not
			// 100% beside a red icon
			for (String commodityId : growthInputs()) {
				CommodityOnMarketAPI com = market.getCommodityData(commodityId);
				if (com == null) continue;
				int demand = com.getMaxDemand();
				if (demand <= 0) continue;
				total += Math.min(1f, com.getAvailable() / (float) demand);
				counted++;
			}
			if (counted > 0) inputScore = total / counted;
		}
		return inputScore;
	}

	/**
	 * Colony health in [0..1] = fabrication (organs, ON/OFF) x supply (inputs,
	 * reduced capacity). Multiplicative, and a disrupted Fabrication Core also
	 * zeroes its machinery supply so shortages compound the disruption.
	 */
	public static float computeHealth(MarketAPI market) {
		if (market == null) return 0f;
		float health = computeFabricationMult(market) * computeSupplyMult(market);
		if (health < 0f) health = 0f;
		if (health > 1f) health = 1f;
		return health;
	}

	/** Growth pace [0..1] for a health value - shared by the tick and the UI. */
	public static float growthMultFor(float health) {
		float full = ThreatIncConfig.growthFullHealth();
		float stall = ThreatIncConfig.growthStallHealth();
		if (health >= full) return 1f;
		if (health <= stall || full <= stall) return 0f;
		return (health - stall) / (full - stall);
	}

	/** The effective tick length the vitality engine runs at (fast-clock aware). */
	public static float effectiveTickDays() {
		float tickDays = ThreatIncConfig.tickDays();
		if (ThreatIncConfig.debugFastClock()) tickDays = Math.max(1f, tickDays / 10f);
		return tickDays;
	}

	/**
	 * The decline rate (fraction of a stratum per 30-day-tick equivalent).
	 * FIXED once health is below the threshold: no severity, duration, or
	 * colony-size scaling. Disrupting a bigger colony costs more up front -
	 * that is the cost; the rate afterwards is the same everywhere, so the
	 * projected timelines are exact and plannable.
	 */
	public static float declineRatePerTick(MarketAPI market, float health) {
		if (market == null || health >= ThreatIncConfig.declineHealthThreshold()) return 0f;
		return ThreatIncConfig.declineBasePerTick();
	}

	/**
	 * Projected decline timeline if health HOLDS below the threshold:
	 * [0] days until the next population stratum is lost, [1] days until the
	 * population falls to size 1 and the colony collapses. The rate is fixed,
	 * so this is exact closed-form arithmetic, not a simulation. Both -1 when
	 * the colony is not declining.
	 */
	public static float[] projectDecline(MarketAPI market, float health) {
		if (market == null || health >= ThreatIncConfig.declineHealthThreshold()) {
			return new float[] {-1f, -1f};
		}
		float base = ThreatIncConfig.declineBasePerTick();
		if (base <= 0f) return new float[] {-1f, -1f};
		float daysPerStratum = effectiveTickDays() / base;
		float meter = ThreatIncData.declineProgress(market.getId());
		float nextStep = (1f - meter) * daysPerStratum;
		// strata left before the population reaches size 1
		int strata = Math.max(1, market.getSize() - 1);
		float collapse = nextStep + (strata - 1) * daysPerStratum;
		return new float[] {nextStep, collapse};
	}

	/**
	 * Called from the fast-cadence poll (~half-day), NOT the 30-day tick:
	 * decline, meter recovery, and growth all accrue CONTINUOUSLY, pro-rated
	 * from the per-30-day config rates. Tick-quantized accrual made the
	 * decline meter stale for up to a month and let a 30-day disruption
	 * window contribute one decline step or zero depending on pure phase
	 * luck - the forecast promised a rate the engine only delivered in lumps.
	 *
	 * @param elapsedDays campaign days since the previous poll
	 */
	public static void updateColonyVitality(float elapsedDays) {
		if (elapsedDays <= 0f) return;
		float declineT = ThreatIncConfig.declineHealthThreshold();
		float tickLen = effectiveTickDays();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			String id = market.getId();
			float health = computeHealth(market);
			ThreatIncData.setLastHealth(id, health);

			if (health < declineT) {
				float daysIn = ThreatIncData.declineDays(id);
				boolean entering = daysIn <= 0f;
				daysIn += elapsedDays;
				ThreatIncData.setDeclineDays(id, daysIn);
				// fixed rate below the threshold - how deep the health sits,
				// how long it has been there, and the colony's size all change
				// nothing, so the forecast the UI shows is exact
				float before = ThreatIncData.declineProgress(id);
				float amount = ThreatIncConfig.declineBasePerTick()
						* (elapsedDays / tickLen);
				ThreatIncData.addDeclineProgress(id, amount);
				if (entering) {
					announce("The fabrication colony on " + market.getName() + " is failing - "
							+ "its strata are dying faster than the hive can regrow them.",
							Misc.getPositiveHighlightColor());
				}
				// log at 10%-meter boundaries, not every half-day poll
				float after = ThreatIncData.declineProgress(id);
				if ((int) (before * 10f) != (int) (after * 10f)) {
					ThreatIncConfig.log("Colony declining at " + market.getName() + ": health "
							+ String.format("%.2f", health) + ", decline "
							+ String.format("%.2f", after) + " - " + econDebugSummary(market));
				}
				applyDeclineSteps(market);
				continue;
			}

			// the pressure has lifted: the hive regrows what it lost, slowly -
			// but NOT while any key organ is still disrupted. A besieged colony
			// that scrapes above the decline threshold holds its wounds open,
			// so successive expeditions accumulate damage instead of watching
			// the meter heal between visits.
			ThreatIncData.setDeclineDays(id, 0f);
			float meter = ThreatIncData.declineProgress(id);
			if (meter > 0f && !anyOrganDisrupted(market)) {
				ThreatIncData.setDeclineProgress(id, meter
						- ThreatIncConfig.declineRecoveryPerTick() * (elapsedDays / tickLen));
			}

			int size = market.getSize();
			int cap = Math.min(ThreatIncConfig.colonyMaxSize(), Misc.getMaxMarketSize(market));
			if (size >= cap) continue;

			float growthMult = growthMultFor(health);
			if (growthMult <= 0f) continue; // starved: resumes once supply recovers

			float accrued = ThreatIncData.growthProgressDays(id) + elapsedDays * growthMult;
			float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size
					* IncursionManager.timeScale();
			if (accrued < daysPerLevel) {
				ThreatIncData.setGrowthProgressDays(id, accrued);
				continue;
			}

			int old = market.getSize();
			CoreImmigrationPluginImpl.increaseMarketSize(market);
			ThreatIncData.setGrowthProgressDays(id, 0f);
			if (market.getSize() <= old) continue;
			ListenerUtil.reportColonySizeChanged(market, old);
			ThreatIncData.setGrowthTime(id);
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

	/**
	 * Cashes in whole size steps from the decline meter. Decline is THE ONLY
	 * way a Threat colony dies: no bombardment touches its population. When a
	 * colony's population would fall to size 1, the hive is no longer viable
	 * and it COLLAPSES outright - the vanilla teardown runs and pollColonies
	 * reacts on the next poll.
	 */
	public static void applyDeclineSteps(MarketAPI market) {
		if (market == null) return;
		String id = market.getId();
		while (ThreatIncData.declineProgress(id) >= 1f) {
			ThreatIncData.addDeclineProgress(id, -1f);
			int old = market.getSize();

			if (old <= 2) {
				// falling to size 1: the strata can no longer sustain themselves
				announce("The fabrication colony on " + market.getName() + " has collapsed - "
						+ "the strata are cold.", Misc.getPositiveHighlightColor());
				ThreatIncConfig.log("Colony collapsed from decline: " + market.getName());
				ThreatIncData.clearVitality(id);
				// fullDestroy bypasses NO_DECIV_KEY (verified against 0.98a source)
				DecivTracker.decivilize(market, true);
				return;
			}

			reduceSizeByOne(market);
			if (market.getSize() >= old) break; // safety: no step happened
			ListenerUtil.reportColonySizeChanged(market, old);
			ThreatIncData.setGrowthProgressDays(id, 0f);
			ThreatIncData.setGrowthTime(id);
			announce("The fabrication colony on " + market.getName()
					+ " has withered to size " + market.getSize() + ".",
					Misc.getPositiveHighlightColor());
			ThreatIncConfig.log("Colony declined to " + market.getSize() + ": "
					+ market.getName());
		}
	}

	/**
	 * -1 size, mirroring vanilla {@code CoreImmigrationPluginImpl.reduceMarketSize}
	 * but WITHOUT its size-3 floor - decline must be able to grind a hive all
	 * the way down to collapse.
	 */
	protected static void reduceSizeByOne(MarketAPI market) {
		int size = market.getSize();
		if (size <= 1) return;
		market.removeCondition("population_" + size);
		market.addCondition("population_" + (size - 1));
		market.setSize(size - 1);
		market.getPopulation().setWeight(
				CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(market.getSize()));
		market.getPopulation().normalize();
		market.reapplyConditions();
		market.reapplyIndustries();
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
		if (size == 3) return new int[][] {{0, med}, {0, med}};
		if (size == 4) return new int[][] {{0, med}, {0, med}, {0, med}};
		if (size == 5) return new int[][] {{0, med}, {0, med}, {0, high}, {1, med}};
		if (size == 6) return new int[][] {{0, high}, {0, high}, {0, high}, {1, high}};
		if (size == 7) return new int[][] {{0, high}, {0, high}, {0, high}, {0, high}, {1, high}};
		return new int[][] {{0, high}, {0, high}, {0, max}, {1, max}, {2, high}};
	}

	// ------------------------------------------------------------------
	// slot fitness: a garrison slot is a promise of a certain weight of defense
	// ------------------------------------------------------------------

	/** The escort tier a swarm was fabricated at (LOW for untagged legacy swarms). */
	protected static int swarmTier(CampaignFleetAPI swarm) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = swarm.getMemoryWithoutUpdate();
		return mem.contains(SWARM_TIER_KEY) ? mem.getInt(SWARM_TIER_KEY) : 0;
	}

	/**
	 * Whether a swarm has been shot down to a shell of itself: below
	 * garrisonUnderStrengthFraction of the fleet points it was fabricated with.
	 * Swarms from saves that predate the stamp read as full strength.
	 */
	protected static boolean isUnderStrength(CampaignFleetAPI swarm) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = swarm.getMemoryWithoutUpdate();
		if (!mem.contains(SWARM_SPAWN_FP)) return false;
		float spawn = mem.getFloat(SWARM_SPAWN_FP);
		if (spawn <= 0f) return false;
		return swarm.getFleetPoints() < spawn * ThreatIncConfig.garrisonUnderStrengthFraction();
	}

	/**
	 * Whether a swarm genuinely HOLDS a garrison slot: its tier meets the slot's
	 * and it is not under strength. A fleet that cannot keep the slot's promise
	 * - a small colony's swarm parked in a big colony's slot, or a swarm
	 * shredded in battle - is a weak holder. It keeps fighting, but it must
	 * not block the nexus from growing the real thing.
	 */
	protected static boolean isFitForSlot(CampaignFleetAPI swarm, int[] slot) {
		return swarmTier(swarm) >= slot[1] && !isUnderStrength(swarm);
	}

	/**
	 * How many of a colony's garrison slots are properly held. Slots are matched
	 * most-demanding-first against the strongest live swarms, so the count
	 * answers "how many of the slots this size of colony wants are covered by a
	 * fleet fit to cover them" - not merely how many fleets are parked here.
	 */
	public static int countFitGarrison(MarketAPI market) {
		int[][] table = desiredGarrison(market.getSize());
		List<CampaignFleetAPI> live = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr != null && curr.isAlive()) live.add(curr);
		}
		// strongest first: tier, then fleet points
		java.util.Collections.sort(live, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				int t = Integer.compare(swarmTier(b), swarmTier(a));
				return t != 0 ? t : Float.compare(b.getFleetPoints(), a.getFleetPoints());
			}
		});
		// most demanding slots first
		List<int[]> slots = new ArrayList<int[]>(java.util.Arrays.asList(table));
		java.util.Collections.sort(slots, new java.util.Comparator<int[]>() {
			public int compare(int[] a, int[] b) { return Integer.compare(b[1], a[1]); }
		});
		int fit = 0;
		for (int i = 0; i < live.size() && i < slots.size(); i++) {
			if (isFitForSlot(live.get(i), slots.get(i))) fit++;
		}
		return fit;
	}

	/**
	 * The escort tier the colony's NEXT open slot demands - the bar a
	 * reinforcement must clear to be worth sending here. A size-2 colony's LOW
	 * swarm cannot hold a size-8 colony's HIGH slot; it would only park in it
	 * and block the real thing. This is what limits which colonies can
	 * reinforce which: fleets must be worth sending.
	 */
	public static int nextSlotTier(MarketAPI market) {
		int[][] table = desiredGarrison(market.getSize());
		int idx = Math.min(countFitGarrison(market), table.length - 1);
		return table[idx][1];
	}

	/** Whether the colony has a live swarm fabricated at or above this tier. */
	protected static boolean hasSwarmOfTier(MarketAPI market, int tier) {
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr != null && curr.isAlive() && swarmTier(curr) >= tier) return true;
		}
		return false;
	}

	/**
	 * The weakest unambiguous weak holder on station - below even the colony's
	 * least demanding slot's tier, or under strength - that is not currently in
	 * a battle. This is the swarm the nexus recycles when it grows a proper
	 * replacement. Null if every swarm holds its slot, or the weak ones are all
	 * mid-fight (never yank a fleet out of a battle).
	 */
	protected static CampaignFleetAPI weakestWeakHolder(MarketAPI market) {
		int[][] table = desiredGarrison(market.getSize());
		int minTier = Integer.MAX_VALUE;
		for (int[] slot : table) minTier = Math.min(minTier, slot[1]);
		CampaignFleetAPI weakest = null;
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr == null || !curr.isAlive() || curr.getBattle() != null) continue;
			if (swarmTier(curr) >= minTier && !isUnderStrength(curr)) continue;
			if (weakest == null
					|| swarmTier(curr) < swarmTier(weakest)
					|| (swarmTier(curr) == swarmTier(weakest)
							&& curr.getFleetPoints() < weakest.getFleetPoints())) {
				weakest = curr;
			}
		}
		return weakest;
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
			boolean lostFleets = false;
			for (int i = fleets.size() - 1; i >= 0; i--) {
				CampaignFleetAPI curr = fleets.get(i);
				if (curr == null || !curr.isAlive()) {
					fleets.remove(i);
					lostFleets = true;
				}
			}

			// two hard on/off gates on fabrication. The Fabrication Core is the
			// master switch for all growth: while it is down (missing, disrupted or
			// still building) NOTHING is grown, Defense Swarms included. The Swarm
			// Nexus is the military organ: while IT is disrupted no new swarms spawn
			// either - existing swarms keep fighting, and sibling colonies send
			// reinforcements to cover the gap. Both organs must be up to rebuild.
			if (organDown(market.getIndustry(FABRICATION_CORE))) continue;
			if (!hasOperationalNexus(market)) continue;

			int[][] table = desiredGarrison(market.getSize());

			// backfill fabrication-tier tags on swarms from saves that predate
			// them, assuming each swarm sits in its table slot - an untagged
			// legacy swarm otherwise re-embodies from an FP estimate, and real
			// Threat fleet FP runs high enough to inflate a MEDIUM garrison
			// swarm into a fabricator armada at muster
			for (int i = 0; i < fleets.size(); i++) {
				CampaignFleetAPI curr = fleets.get(i);
				if (curr.getMemoryWithoutUpdate().contains(SWARM_TIER_KEY)) continue;
				int[] slot = table[Math.min(i, table.length - 1)];
				curr.getMemoryWithoutUpdate().set(SWARM_TIER_KEY, slot[1]);
				curr.getMemoryWithoutUpdate().set(SWARM_FABS_KEY, slot[0]);
			}

			// the economy is the difficulty: a hull-starved colony fields a
			// fraction of its nominal garrison (same figure the launch gates
			// read - see desiredGarrisonCount). What counts against it is slots
			// PROPERLY HELD (countFitGarrison), not fleets parked: a weak holder
			// - a swarm shot under strength, or one too light for its slot (a
			// small colony's swarm sent here to help) - keeps fighting but does
			// not stop the nexus growing the real thing
			int desired = desiredGarrisonCount(market);
			int fit = countFitGarrison(market);
			if (fit >= desired) continue;

			Long last = ThreatIncData.garrisonSpawnTimes().get(marketId);
			// a strained hive builds SLOWER, not just smaller: the replacement
			// cadence stretches as hull supply sags, up to 4x at a deep deficit.
			// Since expeditions are mustered from the garrison, this throttles
			// the hive's entire military tempo through its economy.
			float pace = Math.max(0.25f, shipSupplyMult(market));
			float interval = ThreatIncConfig.garrisonRespawnDays()
					* IncursionManager.timeScale() / pace;
			boolean timerExpired = last == null
					|| Global.getSector().getClock().getElapsedDaysSince(last) >= interval;
			if (lostFleets && timerExpired) {
				// an IDLE nexus does not bank finished swarms: the timer expired
				// while the garrison stood at capacity, so this loss (kill in
				// battle, or a stale despawn) STARTS a build rather than
				// completing one - the replacement arrives a full interval from
				// now. A colony already mid-build (timer running) is untouched:
				// the swarm in the growth-vat is not the one that died.
				ThreatIncData.garrisonSpawnTimes().put(marketId,
						Global.getSector().getClock().getTimestamp());
				continue;
			}
			if (!timerExpired) continue;

			// the garrison is at its head-count but a slot is only weakly held:
			// the fresh swarm REPLACES the weakest weak holder (the hive recycles
			// the shot-up or undersized hulls) so the count never overshoots.
			// Never yank a fleet out of a battle - if every weak holder is
			// fighting, wait for the next poll. Chosen before fabrication and
			// retired only after it succeeds, so a failed spawn costs nothing.
			CampaignFleetAPI recycled = null;
			if (fleets.size() >= desired) {
				recycled = weakestWeakHolder(market);
				if (recycled == null) continue;
			}

			int[] spec = table[fit < table.length ? fit : table.length - 1];
			CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
					spec[0], 0, 0, FabricatorEscortStrength.values()[spec[1]], random);
			if (fleet == null) continue;
			if (recycled != null) {
				fleets.remove(recycled);
				recycled.despawn();
				ThreatIncConfig.log("Recycled weak Defense Swarm at " + market.getName()
						+ " (tier " + swarmTier(recycled) + ", "
						+ (int) recycled.getFleetPoints() + " FP) for a fresh one");
			}
			fleet.setName("Defense Swarm");
			fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, marketId);
			// remember what this swarm IS, so an expedition mustered from it
			// re-embodies the same fleet - not an FP-estimated bigger one
			fleet.getMemoryWithoutUpdate().set(SWARM_TIER_KEY, spec[1]);
			fleet.getMemoryWithoutUpdate().set(SWARM_FABS_KEY, spec[0]);
			// ...and how strong it was born, so battle damage can be measured
			// against it (isUnderStrength)
			fleet.getMemoryWithoutUpdate().set(SWARM_SPAWN_FP, fleet.getFleetPoints());
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

	// ------------------------------------------------------------------
	// garrison redistribution: colonies reinforce each other
	// ------------------------------------------------------------------

	/**
	 * Whether a colony can regrow swarms it sends away: both fabrication organs
	 * up - the same two gates maintainGarrisons spawns behind. Only such
	 * colonies DONATE, so a colony that cannot replace a swarm never bleeds its
	 * irreplaceable garrison out to a sibling.
	 */
	protected static boolean canRebuildGarrison(MarketAPI market) {
		return market != null
				&& !organDown(market.getIndustry(FABRICATION_CORE))
				&& hasOperationalNexus(market);
	}

	/** Reinforcement swarms currently in transit toward this colony. */
	public static int inboundReinforcements(String marketId) {
		int count = 0;
		for (CampaignFleetAPI fleet : ThreatIncData.reinforcementFleets().values()) {
			if (fleet == null || !fleet.isAlive()) continue;
			if (marketId.equals(fleet.getMemoryWithoutUpdate().getString(REINFORCE_TARGET_KEY))) {
				count++;
			}
		}
		return count;
	}

	/**
	 * A colony's garrison for balancing purposes: swarms on station plus those
	 * already flying to join it. Counting inbound swarms is what stops the
	 * balancer re-sending to the same deficit every poll while help is en route.
	 */
	protected static int effectiveGarrison(MarketAPI market) {
		return countLiveGarrison(market.getId()) + inboundReinforcements(market.getId());
	}

	/**
	 * Whether a swarm from source can reach target. Same-system moves are
	 * sublight and always allowed. Cross-system moves are fuel-bound exactly
	 * like strikes and colonization waves: the source needs a working fuel
	 * economy and the hyperspace distance must sit within its fuelRangeLY.
	 */
	protected static boolean canReinforce(MarketAPI source, MarketAPI target) {
		StarSystemAPI from = source.getStarSystem();
		StarSystemAPI to = target.getStarSystem();
		if (from == null || to == null) return false;
		if (from == to) return true;
		if (!hasOperationalFuel(source)) return false;
		float d = Misc.getDistanceLY(from.getLocation(), to.getLocation());
		return d <= fuelRangeLY(source);
	}

	/**
	 * The swarm redistributes its Defense Swarms so no colony is left bare while
	 * a sibling sits at full strength. Each poll it finds the colony with the
	 * lowest garrison fill ratio (swarms on station plus inbound, over what it
	 * wants) that is below strength, then the best donor that can reach it:
	 * same-system first, then the highest fill ratio, then the nearest. One
	 * swarm is dispatched per pairing, up to reinforceMaxPerPoll per poll, so
	 * help trickles in over days rather than teleporting in a burst.
	 *
	 * A donor must (a) be able to regrow what it sends (canRebuildGarrison),
	 * (b) keep at least one swarm on station, and (c) still be at least as well
	 * covered as the receiver AFTER giving one up. That last test is the strict
	 * inequality (donor.eff - 1) / donor.desired > receiver.eff / receiver.desired;
	 * strictness is what makes the balance converge and then HOLD, instead of
	 * two colonies handing a swarm back and forth forever at the boundary.
	 *
	 * Because donors regrow, a healthy colony feeds a besieged sibling
	 * continuously at its own production rate. To actually strip a colony's
	 * garrison an attacker must outpace every colony that can reach it, or cut
	 * the fuel range that connects them - the coordinated machine menace, made
	 * concrete.
	 */
	public static void redistributeGarrisons() {
		if (!ThreatIncConfig.reinforceEnabled()) return;
		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		if (colonies.size() < 2) return;

		int budget = ThreatIncConfig.reinforceMaxPerPoll();
		for (int n = 0; n < budget; n++) {
			// the receiver: lowest fill ratio among colonies below strength
			MarketAPI receiver = null;
			float receiverRatio = Float.MAX_VALUE;
			for (MarketAPI curr : colonies) {
				if (curr.getPrimaryEntity() == null) continue;
				int desired = desiredGarrisonCount(curr);
				if (desired <= 0) continue;
				int eff = effectiveGarrison(curr);
				if (eff >= desired) continue;
				float ratio = eff / (float) desired;
				if (ratio < receiverRatio) {
					receiverRatio = ratio;
					receiver = curr;
				}
			}
			if (receiver == null) return;

			int rEff = effectiveGarrison(receiver);
			int rDesired = desiredGarrisonCount(receiver);
			// the weight of fleet this slot wants: only a swarm that can genuinely
			// hold it is worth sending - a tiny colony's swarm would just park in
			// a big colony's slot and block the real thing
			int needTier = nextSlotTier(receiver);

			// the donor: can reach, can regrow, keeps one home, fields a swarm
			// heavy enough for the slot, and stays at least as covered as the
			// receiver after giving one up
			MarketAPI donor = null;
			boolean donorSameSystem = false;
			float donorRatio = -1f;
			float donorDist = Float.MAX_VALUE;
			for (MarketAPI curr : colonies) {
				if (curr == receiver || curr.getPrimaryEntity() == null) continue;
				if (!canRebuildGarrison(curr)) continue;
				if (countLiveGarrison(curr.getId()) < 2) continue;
				if (!hasSwarmOfTier(curr, needTier)) continue;
				int dDesired = desiredGarrisonCount(curr);
				if (dDesired <= 0) continue;
				int dEff = effectiveGarrison(curr);
				// strict (dEff - 1) / dDesired > rEff / rDesired, cross-multiplied
				if ((dEff - 1) * rDesired <= rEff * dDesired) continue;
				if (!canReinforce(curr, receiver)) continue;

				boolean same = curr.getStarSystem() == receiver.getStarSystem();
				float ratio = dEff / (float) dDesired;
				float dist = same ? 0f : Misc.getDistanceLY(
						curr.getStarSystem().getLocation(),
						receiver.getStarSystem().getLocation());
				boolean better;
				if (donor == null) better = true;
				else if (same != donorSameSystem) better = same;
				else if (ratio != donorRatio) better = ratio > donorRatio;
				else better = dist < donorDist;
				if (better) {
					donor = curr;
					donorSameSystem = same;
					donorRatio = ratio;
					donorDist = dist;
				}
			}
			if (donor == null) return;
			if (!dispatchReinforcement(donor, receiver, needTier)) return;
		}
	}

	/**
	 * Sends one Defense Swarm from source to reinforce target. It is the SAME
	 * fleet: it leaves the source garrison and flies to the target planet
	 * (vanilla fleet AI handles any hyperspace transit, exactly as colonization
	 * waves travel), joining the target garrison when it lands
	 * (checkReinforcementArrivals). Of the swarms heavy enough for the slot
	 * (minTier), the smallest goes and the biggest stay home. Battle damage is
	 * deliberately NOT a bar: a shot-up swarm is still emergency help, and the
	 * receiver's nexus recycles it for a fresh one once it can fabricate again
	 * (see maintainGarrisons). Real and interceptable the whole way.
	 *
	 * Blinders on for the journey, as enforceGarrisonLeash does for recalls:
	 * every Threat fleet carries MEMORY_KEY_MAKE_AGGRESSIVE, whose pursuit AI
	 * would override the travel order and send the reinforcement off chasing
	 * the player instead. Restored on arrival.
	 */
	protected static boolean dispatchReinforcement(MarketAPI source, MarketAPI target, int minTier) {
		SectorEntityToken planet = target.getPrimaryEntity();
		if (planet == null) return false;
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(source.getId());
		CampaignFleetAPI pick = null;
		for (CampaignFleetAPI curr : fleets) {
			if (curr == null || !curr.isAlive()) continue;
			if (swarmTier(curr) < minTier) continue;
			if (pick == null || curr.getFleetPoints() < pick.getFleetPoints()) pick = curr;
		}
		if (pick == null) return false;
		fleets.remove(pick);

		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = pick.getMemoryWithoutUpdate();
		mem.unset(GARRISON_FLAG);
		mem.set(REINFORCE_TARGET_KEY, target.getId());
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		makeDetectable(pick);

		pick.clearAssignments();
		pick.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 365f,
				"reinforcing " + target.getName());
		// fallback so the fleet doesn't wander if arrival detection ever misses
		pick.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
		ThreatIncData.reinforcementFleets().put(pick.getId(), pick);

		// the source regrows what it sent: start its rebuild clock the way a
		// strike muster does, unless a build is already in progress
		Long last = ThreatIncData.garrisonSpawnTimes().get(source.getId());
		float interval = ThreatIncConfig.garrisonRespawnDays() * IncursionManager.timeScale();
		if (last == null || Global.getSector().getClock().getElapsedDaysSince(last) >= interval) {
			ThreatIncData.garrisonSpawnTimes().put(source.getId(),
					Global.getSector().getClock().getTimestamp());
		}
		ThreatIncConfig.log("Reinforcement: Defense Swarm " + source.getName() + " -> "
				+ target.getName() + " (" + countLiveGarrison(source.getId())
				+ " remain at source; " + effectiveGarrison(target) + "/"
				+ desiredGarrisonCount(target) + " covered at destination)");
		return true;
	}

	/**
	 * Polls in-transit reinforcements: a swarm that reaches its target planet
	 * joins that colony's garrison (flag, orbit and hunting reflexes restored);
	 * one whose target colony has meanwhile died is disbanded; one killed en
	 * route simply drops off the books, reopening the deficit for the next poll.
	 */
	public static void checkReinforcementArrivals() {
		java.util.Map<String, CampaignFleetAPI> inTransit = ThreatIncData.reinforcementFleets();
		for (String fleetId : new ArrayList<String>(inTransit.keySet())) {
			CampaignFleetAPI fleet = inTransit.get(fleetId);
			if (fleet == null || !fleet.isAlive()) {
				inTransit.remove(fleetId);
				continue;
			}
			com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
			String targetId = mem.getString(REINFORCE_TARGET_KEY);
			MarketAPI target = targetId != null ? ThreatIncData.resolveColonyMarket(targetId) : null;
			if (target == null || target.getPrimaryEntity() == null) {
				inTransit.remove(fleetId);
				fleet.despawn();
				continue;
			}
			SectorEntityToken planet = target.getPrimaryEntity();
			boolean arrived = fleet.getContainingLocation() == planet.getContainingLocation()
					&& Misc.getDistance(fleet, planet) <= GARRISON_LEASH_RADIUS;
			if (!arrived) continue;

			mem.unset(REINFORCE_TARGET_KEY);
			mem.set(GARRISON_FLAG, targetId);
			// blinders off: on station, hunting reflexes back on (as the leash does)
			mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
			ThreatIncData.garrisonsFor(targetId).add(fleet);
			inTransit.remove(fleetId);
			ThreatIncConfig.log("Reinforcement arrived at " + target.getName() + " ("
					+ countLiveGarrison(targetId) + "/" + desiredGarrisonCount(target) + ")");
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
				ThreatIncData.clearVitality(marketId);
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
			market.removeCondition(HIVE_VITALITY_CONDITION);
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

		// reinforcements in transit anywhere
		for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
				ThreatIncData.reinforcementFleets().values())) {
			if (curr != null && curr.isAlive()) curr.despawn();
		}
		ThreatIncData.reinforcementFleets().clear();

		ThreatIncData.decivTargets().clear();
		ThreatIncData.pendingDecivChecks().clear();
		ThreatIncData.bootstrapSeeds().clear();
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_OG_SYSTEM);
		Global.getSector().getPersistentData().remove(ThreatIncData.KEY_RARE_ECONOMY);

		// clear all incursion intel: the per-system markers, transit trackers,
		// defense-board contracts (received or still queued in the comm
		// network), and the summary (which re-adds itself fresh on restart)
		for (Class<?> intelClass : new Class<?>[] {
				InfestedSystemIntel.class, SeedingSwarmIntel.class, ThreatMissionIntel.class,
				ThreatBountyIntel.class }) {
			for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr
					: new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>(
							Global.getSector().getIntelManager().getIntel(intelClass))) {
				Global.getSector().getIntelManager().removeIntel(curr);
			}
			for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr
					: new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>(
							Global.getSector().getIntelManager().getCommQueue(intelClass))) {
				Global.getSector().getIntelManager().unqueueIntel(curr);
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
		if (version < 4) migrateToSiegeRework();
		if (version < 5) migrateToContinuousDecline();

		ThreatIncData.setDataVersion(ThreatIncData.CURRENT_DATA_VERSION);
	}

	/**
	 * v4 -> v5: decline accrual went continuous - consecutive-tick counters
	 * become days-in-decline.
	 */
	protected static void migrateToContinuousDecline() {
		float tickLen = effectiveTickDays();
		for (Map.Entry<String, Integer> entry : new ArrayList<Map.Entry<String, Integer>>(
				ThreatIncData.declineTicks().entrySet())) {
			if (entry.getValue() == null) continue;
			ThreatIncData.setDeclineDays(entry.getKey(), entry.getValue() * tickLen);
		}
		ThreatIncData.declineTicks().clear();
		ThreatIncConfig.log("Migrated decline counters to continuous accrual (v5).");
	}

	/**
	 * v3 -> v4 (siege rework): the Fragment Fabricator gate is retired - strip
	 * the item everywhere (the onGameLoad early strip already ran for UI
	 * safety; this is the durable, versioned pass) - and seed the health-scaled
	 * growth accumulator from the old growth timestamps so no colony loses
	 * accrued growth time. Decline maps start empty.
	 */
	protected static void migrateToSiegeRework() {
		stripFragmentFabricators();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			String id = market.getId();
			float cap = ThreatIncConfig.colonyGrowthBaseDays() * market.getSize()
					* IncursionManager.timeScale();
			float accrued = ThreatIncData.daysSinceGrowth(id);
			if (accrued < 0f) accrued = 0f;
			if (accrued > cap) accrued = cap;
			ThreatIncData.setGrowthProgressDays(id, accrued);
		}
		ThreatIncConfig.log("Migrated data layout to siege rework (v4).");
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
	 * and through the sector's own contracts. Debug mode restores the running
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
