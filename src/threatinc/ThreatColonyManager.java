package threatinc;

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
 * resource-bearing planet it can reach, a wave per planet.
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
	/** How many swarms of its spec a garrison fleet embodies (absent: one); see growGarrisonFleet. */
	public static final String SWARM_COUNT_KEY = "$threatinc_swarmCount";

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
	 * places (econ group before addMarket, planet faction after). Its
	 * structures are paid for elsewhere: a Seeding Swarm's source at launch
	 * (settleFounding), or nobody on the debug and save-migration paths.
	 */
	public static MarketAPI foundColony(PlanetAPI planet, int initialSize) {
		return foundColony(planet, initialSize, null);
	}

	/**
	 * foundColony, its structures bought from payerId's bank at
	 * foundingFPPerStructure each (null: paid for elsewhere). The four a hive
	 * needs to exist and earn at all - Population, Spaceport, Fabrication Core,
	 * Swarm Nexus: without them its nexus draws nothing and its bank never
	 * fills - are charged whatever that bank holds, a debt its production pays
	 * off; the planner's first build only if the bank covers it, else the
	 * colony buys it later from its own (buyWaitingStructures).
	 */
	public static MarketAPI foundColony(PlanetAPI planet, int initialSize, String payerId) {
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

		addEssentialStructure(market, Industries.POPULATION, payerId);
		// no spaceport means -100% accessibility, no in-group shipping, and no
		// supply convoys - the hive economy needs its ports
		addEssentialStructure(market, Industries.SPACEPORT, payerId);

		// what the player knew of the planet, for the map while the hive is unfound (ThreatMapFog)
		market.getMemoryWithoutUpdate().set(ThreatMapFog.KEY_PRIOR_SURVEY, market.getSurveyLevel().name());
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
		// keeps bar and contact missions from pointing at hive worlds; the
		// generic survey/analyze/procurement missions ignore this flag and
		// are filtered by ThreatMissionFilter instead
		market.setInvalidMissionTarget(true);

		market.getMemoryWithoutUpdate().set(DecivTracker.NO_DECIV_KEY, true);
		market.getMemoryWithoutUpdate().set(COLONY_FLAG, true);

		// keep vanilla ambient/trade fleet spawners off hive colonies - academy
		// shuttles, pilgrims, mercs, generic trade convoys. The internal hive
		// economy still simulates fully (availability is computed regardless of
		// whether convoys actually fly); this just stops out-of-character
		// civilian fleets sourcing from a machine-swarm world
		SharedData.getData().getMarketsWithoutTradeFleetSpawn().add(market.getId());

		// machine order: stability held at 10 less bombardment unrest, re-pinned
		// every poll (applyHiveOrder) - shortages still bite through the industry
		// deficit multipliers and the ship-hull fleet size mult
		market.getStability().modifyFlat(STABILITY_MOD_ID, 10f, "Machine hive-order");

		pinMaxSize(market);

		Global.getSector().getEconomy().addMarket(market, true);

		// after addMarket: planet ownership/map color
		planet.setFaction(Factions.THREAT);
		// DecivTracker.decivilize silently no-ops on discoverable entities, and
		// fringe procgen planets start discoverable - clear it or the colony
		// could never be bombarded out of existence
		planet.setDiscoverable(null);
		planet.setDiscoveryXP(null);

		ensureFabricationCore(market, payerId);
		planHiveEconomy(market, payerId);

		return market;
	}

	/**
	 * The largest size a market can reach: vanilla defines population_1 through
	 * population_10 and no more, so a hive grows to 10 (2026-09-29: the
	 * colonyMaxSize knob, 8, is gone - the engine's own ceiling is the only one).
	 */
	public static final int HIVE_MAX_SIZE = 10;

	/**
	 * Lifts a hive's vanilla max market size (Misc.MAX_COLONY_SIZE, the player's
	 * 6) to HIVE_MAX_SIZE. Re-pinned every poll (updateColonyVitality) so hives
	 * founded under the old knob catch up.
	 */
	public static void pinMaxSize(MarketAPI market) {
		if (market == null) return;
		com.fs.starfarer.api.combat.StatBonus mod = market.getStats().getDynamic().getMod(Stats.MAX_MARKET_SIZE);
		if (HIVE_MAX_SIZE > Misc.MAX_COLONY_SIZE) {
			mod.modifyFlat("threatinc", HIVE_MAX_SIZE - Misc.MAX_COLONY_SIZE);
		} else {
			mod.unmodifyFlat("threatinc");
		}
	}

	/** How big this hive can grow: vanilla's max market size, never past the last population condition. */
	public static int maxColonySize(MarketAPI market) {
		if (market == null) return HIVE_MAX_SIZE;
		return Math.min(HIVE_MAX_SIZE, Misc.getMaxMarketSize(market));
	}

	/**
	 * A Threat ground victory over a human colony (2026-09-06, docs/ground-war.md
	 * "Sieges against human factions"): the world is a hive at once. Vanilla's
	 * own teardown strips the population and industries, then the hive is
	 * founded on the ruin at {@code conquestHiveSize}, as a wave would found it.
	 * Returns the hive, or null when the world cannot carry one (the caller
	 * falls back to the deciv path).
	 */
	public static MarketAPI convertConquered(PlanetAPI planet, MarketAPI market) {
		if (planet == null || market == null) return null;
		String name = market.getName();
		DecivTracker.decivilize(market, false);
		MarketAPI ruin = planet.getMarket();
		if (ruin == null || !ThreatMapFog.conditionOnly(ruin)) {
			ThreatIncConfig.log("Conquest of " + name + ": no condition market to seed a hive on");
			// the ruin is the swarm's kill all the same: it comes back for it
			market.getMemoryWithoutUpdate().set(ThreatGroundFronts.KILLED_BY_FLAG, Factions.THREAT, 60f);
			return null;
		}
		int size = Math.max(1, Math.min(HIVE_MAX_SIZE, ThreatIncConfig.conquestHiveSize()));
		// (2026-09-29: founding is paid) the conquering hive pays for the
		// structures: the nearest colony whose bank covers the whole founding,
		// else the nearest at all, for the four the hive cannot exist without
		String payer = conquestPayer(planet, ruin.getId());
		float had = fpBank(payer);
		MarketAPI hive = foundColony(planet, size, payer);
		if (hive == null) {
			ruin.getMemoryWithoutUpdate().set(ThreatGroundFronts.KILLED_BY_FLAG, Factions.THREAT, 60f);
			return null;
		}
		registerConquest(planet, hive);
		ThreatIncConfig.log("Conquest: " + name + " seeded as a size-" + size + " hive, "
				+ hive.getIndustries().size() + " structures, " + (int) (had - fpBank(payer)) + " FP paid by "
				+ (ThreatIncData.resolveColonyMarket(payer) != null ? ThreatIncData.resolveColonyMarket(payer).getName() : payer)
				+ (buildWaiting().containsKey(hive.getId()) ? " (the rest waits on its own bank)" : ""));
		return hive;
	}

	/**
	 * The bank a conquest's founding is charged to: the live colony nearest
	 * the planet that can pay the core structures and a first build, else the
	 * nearest live colony, else the new hive itself (fallbackId) - a debt.
	 */
	protected static String conquestPayer(PlanetAPI planet, String fallbackId) {
		float bill = foundingFP(FOUNDING_CORE_STRUCTURES + 1);
		MarketAPI nearest = null;
		MarketAPI payer = null;
		float nearestDist = Float.MAX_VALUE;
		float payerDist = Float.MAX_VALUE;
		for (MarketAPI curr : ThreatIncData.getAllLiveColonyMarkets()) {
			if (curr.getId().equals(fallbackId)) continue;
			float d = Misc.getDistanceLY(planet.getLocationInHyperspace(), curr.getLocationInHyperspace());
			if (d < nearestDist) {
				nearest = curr;
				nearestDist = d;
			}
			if (bankedFP(curr) >= bill && d < payerDist) {
				payer = curr;
				payerDist = d;
			}
		}
		if (payer != null) return payer.getId();
		return nearest != null ? nearest.getId() : fallbackId;
	}

	/**
	 * Books a conquered hive the way a wave landing books its colony, so the
	 * board, the siege picker and the scouts can see it. Found on the spot: the
	 * siege that took it was fought in plain sight.
	 */
	protected static void registerConquest(PlanetAPI planet, MarketAPI hive) {
		String systemId = planet.getContainingLocation().getId();
		if (!ThreatIncData.STAGE_COLONY.equals(ThreatIncData.stages().get(systemId))) {
			ThreatIncData.setStage(systemId, ThreatIncData.STAGE_COLONY);
		}
		List<String> colonies = ThreatIncData.colonyMarketsFor(systemId);
		if (!colonies.contains(hive.getId())) colonies.add(hive.getId());
		ThreatIncData.setGrowthTime(hive.getId());
		ThreatIncData.decivTargets().remove(planet.getId());
		ThreatIncData.bootstrapSeeds().remove(systemId);
		ThreatIncData.markDiscovered(systemId);
	}

	/**
	 * Saves from before conquests were booked: a hive seeded on a conquered
	 * world ran outside the registry, unlisted and never besieged. Adopt it.
	 */
	public static void adoptUnbookedConquests() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!Factions.THREAT.equals(market.getFactionId())) continue;
			if (!market.getMemoryWithoutUpdate().getBoolean(COLONY_FLAG)) continue;
			if (!(market.getPrimaryEntity() instanceof PlanetAPI)) continue;
			PlanetAPI planet = (PlanetAPI) market.getPrimaryEntity();
			if (!(planet.getContainingLocation() instanceof StarSystemAPI)) continue;
			if (ThreatIncData.colonyIdsIn(planet.getContainingLocation().getId())
					.contains(market.getId())) continue;
			registerConquest(planet, market);
			ThreatIncConfig.log("Adopted unbooked conquest hive: " + market.getName());
		}
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
		return ThreatMapFog.conditionOnly(planet.getMarket());
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
	 * richest ore world, the richest volatiles world, and every other
	 * colonisable planet to host refining and heavy industry, distinct
	 * (2026-09-29: no longer cut at five - the waves and garrisons that must
	 * take and hold each world are the bound).
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
			if (planet.getMarket() != null && !ThreatMapFog.conditionOnly(planet.getMarket())) continue;
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
		planHiveEconomy(market, market.getId());
	}

	/** planHiveEconomy paid for by nobody: the debug war's stand-up and save heals. */
	public static void planHiveEconomyFree(MarketAPI market) {
		planHiveEconomy(market, null);
	}

	/**
	 * planHiveEconomy, its build bought from payerId's bank (buyStructure; null:
	 * paid for elsewhere). A build the bank cannot pay for waits - the planner
	 * spends its turn on it and the colony retries each poll
	 * (buyWaitingStructures) - so no structure is ever added unpaid.
	 */
	public static void planHiveEconomy(MarketAPI market, String payerId) {
		int size = market.getSize();
		// the flag stands only while a build is waiting on the bank (buyStructure)
		buildWaiting().remove(market.getId());

		// the port stays a Spaceport for life (see ensureSpaceport)
		ensureSpaceport(market);

		// defensive structures don't take industry slots. The hive builds its
		// own marine-free variants (ThreatGroundDefenses: machinery+metals) -
		// but only guns it can feed (defensesAffordable): a colony has one
		// industry slot until size 4 and Mining holds it, so batteries built
		// at size 3 demanded metals no refinery could yet exist to make, and
		// that unmet demand held vitality on the stall floor for good - every
		// hive frozen at five size-3 worlds, 0.4.0 through 0.6.0 (found
		// 2026-09-09; docs/hive-economy.md). The hive arms once it can pay.
		if (size >= 6 && market.hasIndustry(THREAT_GROUND_DEFENSES) && defensesAffordable(market)) {
			// the upgrade is a new structure: paid before the old one comes down
			if (!affordStructure(market, payerId)) return;
			market.removeIndustry(THREAT_GROUND_DEFENSES, null, true);
			buyStructure(market, THREAT_HEAVY_BATTERIES, payerId);
			return;
		}
		if (size >= 3 && !market.hasIndustry(THREAT_GROUND_DEFENSES)
				&& !market.hasIndustry(THREAT_HEAVY_BATTERIES) && defensesAffordable(market)) {
			// a world already past size 6 arms with the heavy batteries at once,
			// not a tick later (saves whose hives never armed catch up in one tick)
			buyStructure(market, size >= 6 ? THREAT_HEAVY_BATTERIES : THREAT_GROUND_DEFENSES, payerId);
			return;
		}

		if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) return;

		// mine what the planet offers (Mining supplies nothing without deposits)
		if (hasMiningDeposits(market) && !market.hasIndustry(Industries.MINING)) {
			if (!buyStructure(market, Industries.MINING, payerId)) return;
			markEconomyDirty();
			ThreatIncConfig.log("Hive planner: MINING at " + market.getName());
			return;
		}

		// bootstrap: the first copy of each link, wherever there is room
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (countLink(link) == 0 && tryBuildLink(market, link, "first", false, payerId)) return;
		}

		// upgrade an established forge to orbital works for better hulls - improves
		// output/quality without changing the forge count
		if (size >= 6 && market.hasIndustry(Industries.HEAVYINDUSTRY)) {
			if (!affordStructure(market, payerId)) return;
			market.removeIndustry(Industries.HEAVYINDUSTRY, null, true);
			buyStructure(market, Industries.ORBITALWORKS, payerId);
			markEconomyDirty();
			announce(ThreatNotice.titled("Forge World").bad()
					.line("%s has restructured into a forge world", ThreatNotice.market(market))
					.line("Hull output there is accelerating"));
			ThreatIncConfig.log("Hive planner: ORBITALWORKS at " + market.getName());
			return;
		}

		// redundancy: every link at two copies before any at three, spare copies
		// steered to the system holding the fewest
		int target = redundancyTarget();
		for (int level = 1; level < target; level++) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				if (countLink(link) <= level && tryBuildLink(market, link, "spare", true, payerId)) return;
			}
		}

		// the chain is complete: a bigger copy of any link whose largest producer
		// no longer covers the hive's largest consumer of its output
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (outputCovered(link) || size <= largestLinkSize(link)) continue;
			if (tryBuildLink(market, link, "bigger", false, payerId)) return;
		}
	}

	/**
	 * Builds the link's industry here if the colony lacks it and the hive
	 * market carries its input. A refinery wants ore, a fuel plant volatiles;
	 * forges take metals, which the first refinery already covers.
	 *
	 * @param spread whether the copy must go to a lean system (spreadAllows)
	 * @param payerId the bank that buys it (buyStructure; null: paid elsewhere)
	 * @return true if it placed the industry, or it waits on the bank (the
	 *         planner's turn is spent either way)
	 */
	protected static boolean tryBuildLink(MarketAPI market, int link, String label, boolean spread, String payerId) {
		if (hasLink(market, link)) return false;
		String industry = CHAIN_LINKS[link];
		if (Industries.REFINING.equals(industry) && !groupHasIndustry(Industries.MINING)) return false;
		if (Industries.FUELPROD.equals(industry) && !groupHasVolatiles()) return false;
		if (spread && !spreadAllows(market, link)) return false;
		if (!buyStructure(market, industry, payerId)) return true;
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
						|| other.getSize() < maxColonySize(other)) return false;
			}
		}
		return true;
	}

	/**
	 * Copies of each chain link the hive wants: one per system it holds. What
	 * bounds it is industry slots (planHiveEconomy builds only into a free one,
	 * Misc.getMaxIndustries) - 2026-09-29: the chainRedundancy knob, 3, is gone.
	 */
	protected static int redundancyTarget() {
		return Math.max(1, liveColonySystemIds().size());
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

	/**
	 * Whether the colony can feed the batteries it would build: the machinery
	 * and metals Ground Defenses / Heavy Batteries demand (size - 2 each,
	 * ThreatGroundDefenses.apply) are at least half-met on the hive market -
	 * the same bar a growth input must clear (STALL_MET_FRACTION), so arming
	 * can never be what stalls the world.
	 */
	public static boolean defensesAffordable(MarketAPI market) {
		int need = market.getSize() - 2;
		if (need <= 0) return true;
		for (String commodityId : new String[] { Commodities.HEAVY_MACHINERY, Commodities.METALS }) {
			CommodityOnMarketAPI com = market.getCommodityData(commodityId);
			// what it could draw, not only what it shows: vanilla imports only up
			// to demand, so a world with no batteries yet shows no metals however
			// much the hive's refineries make - read locally, 35 of 41 hives never
			// armed (2026-09-28 run). The hive's largest producer is what it would
			// draw from (availability is per source, as outputCovered reads it);
			// before any refinery exists that is still nothing, so the 0.6.1
			// stall stays fixed
			float available = Math.max(com != null ? com.getAvailable() : 0f, hiveMaxSupply(commodityId));
			if (available < need * STALL_MET_FRACTION) return false;
		}
		return true;
	}

	/** The most any one hive world makes of a commodity: what another hive world could draw of it. */
	protected static int hiveMaxSupply(String commodityId) {
		int supply = 0;
		for (MarketAPI other : ThreatIncData.getAllLiveColonyMarkets()) {
			CommodityOnMarketAPI com = other.getCommodityData(commodityId);
			if (com != null) supply = Math.max(supply, com.getMaxSupply());
		}
		return supply;
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
	 * however far the hive fell below target. Re-plans capped colonies and
	 * STALLED ones with a free industry slot - a colony that is still growing
	 * builds on its growth steps - one industry per colony per tick, which is
	 * about the pace of a vanilla construction anyway.
	 *
	 * The stalled case is the one that used to deadlock (found 2026-09-09 in a
	 * 467-day save whose home hive sat at five size-3 worlds): a colony below
	 * the cap builds only on its growth steps, but health IS its inputs, so a
	 * chain link the hive has not built anywhere holds it at or under the stall
	 * floor and it has no growth steps left to build on. In that save the whole
	 * hive had Mining and nothing else - metals read 0 against Ground Defenses'
	 * demand, heavy machinery 1 off the Fabrication Core, and computeSupplyMult
	 * averaged the two to exactly 0.5, growthMultFor's stall value, so the pace
	 * was a hard zero and the planner was never called again. The bootstrap must
	 * therefore not depend on growth: growth paces redundancy and bigger copies,
	 * never the first copy of a link that vitality is itself made of.
	 */
	public static void maintainHiveEconomy() {
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			// a world that can now feed the batteries it lacks (or the heavy
			// batteries it has outgrown) arms on this tick, not on its next
			// growth step a season away - structures need no slot
			int size = market.getSize();
			int cap = maxColonySize(market);
			boolean gd = market.hasIndustry(THREAT_GROUND_DEFENSES);
			boolean hb = market.hasIndustry(THREAT_HEAVY_BATTERIES);
			boolean arms = ((size >= 3 && !gd && !hb) || (size >= 6 && gd)) && defensesAffordable(market);
			if (!arms) {
				if (size < cap && growthMultFor(computeHealth(market)) > 0f) continue;
				if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) continue;
			}
			planHiveEconomy(market);
		}
	}

	/**
	 * Save heal for the 0.4.0-0.6.0 freeze (see planHiveEconomy's batteries
	 * rule), run once on load from IncursionManager's first poll. The
	 * fingerprint is exact and cannot arise under the fixed rule: a hive with
	 * NO refinery anywhere, and colonies at size 3+ carrying batteries that
	 * stored a health on the stall floor last session with their only slot
	 * full. Each such colony lost its growth to the bug for as long as it
	 * stood there, so it gets the one size the freeze cost it - the slot the
	 * refinery needs - through the normal growth step, and the chain is then
	 * stood up to a fixed point with recomputes between rounds (the instant
	 * war's loop), so the hive is fed the moment the save opens. A save this
	 * never applied to pays one cheap scan. Returns the colonies grown.
	 */
	public static int healStalledBootstrap() {
		float stall = ThreatIncConfig.growthStallHealth();
		if (countLink(0) > 0) return 0;
		List<MarketAPI> frozen = new ArrayList<MarketAPI>();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < 3) continue;
			if (!market.hasIndustry(THREAT_GROUND_DEFENSES) && !market.hasIndustry(THREAT_HEAVY_BATTERIES)) continue;
			if (!ThreatIncData.lastHealth().containsKey(market.getId())) continue;
			if (ThreatIncData.lastHealth(market.getId()) > stall) continue;
			if (Misc.getNumIndustries(market) < Misc.getMaxIndustries(market)) continue;
			frozen.add(market);
		}
		if (frozen.isEmpty()) return 0;
		int grown = 0;
		for (MarketAPI market : frozen) {
			int cap = maxColonySize(market);
			if (market.getSize() < cap && growColony(market, cap)) grown++;
		}
		for (int round = 0; round < 12; round++) {
			boolean changed = false;
			for (MarketAPI market : frozen) {
				int had = market.getIndustries().size();
				// what the freeze cost it, restored - not bought
				planHiveEconomyFree(market);
				if (market.getIndustries().size() > had) changed = true;
			}
			flushEconomy();
			if (!changed) break;
		}
		Global.getLogger(ThreatColonyManager.class).info("[ThreatInc] Bootstrap heal: " + grown
				+ " of " + frozen.size() + " frozen colonies grown a size; chain links now "
				+ countLink(0) + "/" + countLink(1) + "/" + countLink(2));
		return grown;
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
			if (ThreatMapFog.hidden(market) || ThreatMapFog.conditionOnly(market)) continue;
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
		ensureFabricationCore(market, market.getId());
	}

	/**
	 * ensureFabricationCore, a missing organ bought from payerId's bank (null:
	 * paid for elsewhere). Organs are essential (addEssentialStructure): without
	 * them the colony earns nothing, so waiting on its bank would never end -
	 * they are charged as a debt instead.
	 */
	public static void ensureFabricationCore(MarketAPI market, String payerId) {
		if (market == null) return;
		if (!market.hasIndustry(FABRICATION_CORE)) {
			addEssentialStructure(market, FABRICATION_CORE, payerId);
			ThreatIncConfig.log("Fabrication Core added at " + market.getName());
		}
		// the colony-UI vitality readout; idempotent, and doubles as the
		// migration path for colonies founded before the condition existed
		if (!market.hasCondition(HIVE_VITALITY_CONDITION)) {
			market.addCondition(HIVE_VITALITY_CONDITION);
		}
		if (!market.hasIndustry(SWARM_NEXUS)) {
			addEssentialStructure(market, SWARM_NEXUS, payerId);
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
			for (String marketId : new ArrayList<String>(ThreatIncData.colonyIdsIn(systemId))) {
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
			// a strike musters at least two Defense Swarms above the reserves -
			// the system's colonies pool theirs (garrisonAvailableForLaunch,
			// 2026-09-29), each at full garrison or adding nothing
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
		boolean saturated = market.getSize() >= maxColonySize(market);
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

	/** Fleet points of Seeding Swarm per day its forge spends retooled after the launch (retoolForge). */
	public static final float RETOOL_FP_PER_DAY = 10f;

	/**
	 * The cooldown hasReadyForge reads: a forge that launches a Seeding Swarm
	 * is disrupted a day per RETOOL_FP_PER_DAY of the wave (vanilla's industry
	 * disruption, never shortening a longer one). Its hulls stop while it
	 * retools, so the hive's income (forgeOutput) pauses with them. Returns
	 * the days set, 0 if there was no forge to retool.
	 */
	protected static float retoolForge(MarketAPI source, float waveFP) {
		Industry forge = getForge(source);
		if (forge == null || !forge.canBeDisrupted() || waveFP <= 0f) return 0f;
		float days = waveFP / RETOOL_FP_PER_DAY;
		forge.setDisrupted(days, true);
		return days;
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
	 * The garrison FLOOR this colony's nexus always rebuilds to: the nominal
	 * size table, degraded by the hive economy's real hull supply. It is the
	 * "full garrison" bar for launches, and must match what the nexus can
	 * actually deliver, or a strained hive would wait forever for fleets that
	 * never come. Since 2026-09-29 it is no ceiling: above it the nexus keeps
	 * building for as long as its production pays (the fabrication ledger).
	 */
	public static int desiredGarrisonCount(MarketAPI market) {
		if (market == null) return 0;
		int nominal = desiredGarrison(market.getSize()).length;
		int desired = Math.max(1, Math.round(nominal * shipSupplyMult(market)));
		return Math.min(desired, nominal);
	}

	/**
	 * The garrison a colony's SIZE calls for - the size table, untouched by the
	 * hull economy. This is what reinforcement fills a colony toward: the
	 * hull-shortage cap (desiredGarrisonCount) bounds what the colony's own
	 * nexus can build, but a sibling's already-built swarm needs none of the
	 * receiver's hulls to fly over and defend it.
	 */
	public static int nominalGarrison(MarketAPI market) {
		if (market == null) return 0;
		return desiredGarrison(market.getSize()).length;
	}

	/**
	 * The swarm count a colony's garrison fills toward when nothing presses
	 * it: its size table's (desiredGarrisonCount), or under posture its base
	 * (ThreatPosture.baseCount - its reserve, and a forge's launch stock).
	 */
	public static int garrisonTargetCount(MarketAPI market) {
		return ThreatPosture.enabled() ? ThreatPosture.baseCount(market) : desiredGarrisonCount(market);
	}

	/** Defense Swarms a colony always keeps home; it never musters these. */
	public static int garrisonReserve(MarketAPI market) {
		return Math.max(1, desiredGarrisonCount(market) / 2);
	}

	/**
	 * Defense Swarms an expedition staged at this colony can muster: what every
	 * colony of its system (launchPool) has above its own reserve. Pooled
	 * 2026-09-29 - the staging colony used to muster alone, so a system of
	 * several mid-sized worlds could hold a fleet no one of them could send.
	 */
	public static int garrisonAvailableForLaunch(MarketAPI market) {
		int n = 0;
		for (MarketAPI curr : launchPool(market)) n += ownAvailableForLaunch(curr);
		return n;
	}

	/**
	 * Defense Swarms ONE colony is willing to send out: only what stands above
	 * its reserve, and only once the garrison is at full (economy-scaled)
	 * strength - a colony still regrowing its swarms sends nothing. Where
	 * its system is pressed (ThreatPosture.launchSpareFP) only the fleets
	 * that fit inside what it holds above the pressure's need, largest first
	 * as musterFrom takes them. Under posture "full strength" is the colony's
	 * want, not its size table: a quiet colony at its reserve and launch stock
	 * is full, and one short of it by less than a swarm (ThreatPosture.regrowing).
	 */
	public static int ownAvailableForLaunch(MarketAPI market) {
		if (market == null) return 0;
		int live = countLiveGarrison(market.getId());
		if (ThreatPosture.enabled()) {
			if (ThreatPosture.regrowing(market, ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId())))) {
				return 0;
			}
		} else if (live < desiredGarrisonCount(market)) {
			return 0;
		}
		int n = Math.max(0, live - garrisonReserve(market));
		float spare = ThreatPosture.launchSpareFP(market);
		if (n <= 0 || spare == Float.MAX_VALUE) return n;
		List<Float> fps = new ArrayList<Float>();
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr != null && curr.isAlive()) fps.add((float) curr.getFleetPoints());
		}
		java.util.Collections.sort(fps, java.util.Collections.reverseOrder());
		int fit = 0;
		for (int i = 0; i < n && i < fps.size(); i++) {
			spare -= fps.get(i);
			if (spare < 0f) break;
			fit++;
		}
		return fit;
	}

	/**
	 * The colonies an expedition staged here draws swarms from: the staging
	 * colony first, then the others of its system - a sublight hop away, and
	 * each keeps its own reserve home.
	 */
	public static List<MarketAPI> launchPool(MarketAPI market) {
		List<MarketAPI> pool = new ArrayList<MarketAPI>();
		if (market == null) return pool;
		pool.add(market);
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return pool;
		for (MarketAPI curr : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			if (!curr.getId().equals(market.getId())) pool.add(curr);
		}
		return pool;
	}

	/** The expedition sizes consumeGarrison would muster now, without mustering them (the largest swarms first). */
	public static List<Integer> peekGarrison(MarketAPI market, int count) {
		return musterPool(market, count, false, null, null);
	}

	/** peekGarrison, adding the swarms' fleet points into fpOut[0]. */
	public static List<Integer> peekGarrison(MarketAPI market, int count, float[] fpOut) {
		return musterPool(market, count, false, fpOut, null);
	}

	/** One garrison fleet as a muster takes it: the expedition size of every swarm it embodies, and its fleet points. */
	public static class MusterFleet {
		public final List<Integer> sizes;
		public final float fp;

		MusterFleet(List<Integer> sizes, float fp) {
			this.sizes = sizes;
			this.fp = fp;
		}
	}

	/**
	 * peekGarrison fleet by fleet, in the order consumeGarrison takes them: a
	 * muster of n fleets takes exactly the first n entries (for n up to
	 * garrisonAvailableForLaunch), so every n can be weighed from one sorted
	 * walk (IncursionManager.launchStrike).
	 */
	public static List<MusterFleet> peekMuster(MarketAPI market, int count) {
		List<MusterFleet> out = new ArrayList<MusterFleet>();
		musterPool(market, count, false, null, out);
		return out;
	}

	/**
	 * Musters up to count Defense Swarm fleets as the substance of an expedition:
	 * the fleets leave the garrison (despawned here; the expedition machinery
	 * re-embodies them as its own fleets). Sends the LARGEST swarms - the
	 * reserve that stays is the smaller ones. Returns each mustered swarm's
	 * expedition fleet size (see expeditionSizeFor), so the expedition fields
	 * exactly the fleets that left orbit.
	 *
	 * The swarms come from the staging colony's launch pool (launchPool): its
	 * own above its reserve first, then each sibling's above that sibling's
	 * reserve. A count beyond the pool is made up from the staging colony's own
	 * garrison, as before pooling.
	 */
	public static List<Integer> consumeGarrison(MarketAPI market, int count) {
		return musterPool(market, count, true, null, null);
	}

	/**
	 * consumeGarrison, adding the mustered swarms' fleet points into fpOut[0]:
	 * what an expedition re-embodying them has already paid for (settle the
	 * difference with chargeFP / creditFP on the staging colony).
	 */
	public static List<Integer> consumeGarrison(MarketAPI market, int count, float[] fpOut) {
		return musterPool(market, count, true, fpOut, null);
	}

	/**
	 * consumeGarrison's walk; despawn false only peeks. count is in garrison
	 * fleets, the list a size per swarm: a fleet grown past one swarm
	 * (growGarrisonFleet) musters as every swarm it embodies.
	 */
	protected static List<Integer> musterPool(MarketAPI market, int count, boolean despawn, float[] fpOut,
			List<MusterFleet> fleetsOut) {
		List<Integer> mustered = new ArrayList<Integer>();
		if (market == null || count <= 0) return mustered;
		int taken = 0;
		int ownTaken = 0;
		for (MarketAPI curr : launchPool(market)) {
			int share = Math.min(count - taken, ownAvailableForLaunch(curr));
			if (share > 0) {
				int got = musterFrom(curr, share, 0, despawn, mustered, fpOut, fleetsOut);
				taken += got;
				if (curr == market) ownTaken = got;
			}
			if (taken >= count) return mustered;
		}
		// a peek has not removed what it counted from the staging colony: skip those
		musterFrom(market, count - taken, despawn ? 0 : ownTaken, despawn, mustered, fpOut, fleetsOut);
		return mustered;
	}

	/**
	 * Takes up to n of the colony's largest live garrison fleets, after the
	 * first skip: a size per swarm into out, a MusterFleet per fleet into
	 * fleetsOut, their fleet points into fpOut[0] (despawned when despawn).
	 * Returns the fleets taken.
	 */
	protected static int musterFrom(MarketAPI market, int n, int skip, boolean despawn, List<Integer> out,
			float[] fpOut, List<MusterFleet> fleetsOut) {
		if (n <= 0) return 0;
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
		int taken = 0;
		int swarms = 0;
		for (int i = skip; i < alive.size(); i++) {
			if (taken >= n) break;
			CampaignFleetAPI curr = alive.get(i);
			// a grown fleet is swarms of one spec (growGarrisonFleet): each re-embodies alike
			int size = expeditionSizeFor(curr);
			int k = swarmCount(curr);
			List<Integer> sizes = new ArrayList<Integer>(k);
			for (int j = 0; j < k; j++) sizes.add(size);
			out.addAll(sizes);
			// read before despawn: a despawned fleet reports 0 FP
			float fp = curr.getFleetPoints();
			if (fpOut != null) fpOut[0] += fp;
			if (fleetsOut != null) fleetsOut.add(new MusterFleet(sizes, fp));
			taken++;
			swarms += k;
			if (!despawn) continue;
			fleets.remove(curr);
			curr.despawn();
		}
		// (the nexus's rebuild clock this used to restart is gone: production
		// banks continuously now, the fabrication ledger)
		if (!despawn || taken <= 0) return taken;
		ThreatPosture.noteConsumed(swarms);
		ThreatIncConfig.log(swarms + " Defense Swarm(s) in " + taken + " fleet(s) mustered from "
				+ market.getName() + " (" + countLiveGarrison(market.getId())
				+ " remain on station)");
		return taken;
	}

	/** How many swarms a garrison fleet embodies (SWARM_COUNT_KEY): one unless the nexus grew it. */
	public static int swarmCount(CampaignFleetAPI fleet) {
		if (fleet == null) return 0;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		return mem.contains(SWARM_COUNT_KEY) ? Math.max(1, mem.getInt(SWARM_COUNT_KEY)) : 1;
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
	 * consolidation (a strained hive may still fix itself locally). Under
	 * posture (ThreatPosture) a colony of a pressed system keeps its forge
	 * home, and the colony with the most swarms to spare goes first, the
	 * nearest breaking a tie.
	 */
	public static MarketAPI pickForgeSource(StarSystemAPI target, boolean requireStable) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		int bestSpare = -1;
		boolean bestPays = false;
		boolean posture = ThreatPosture.enabled();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.spreadMinSize()) continue;
			if (!hasReadyForge(market)) continue;
			if (!hasOperationalNexus(market)) continue;
			StarSystemAPI system = market.getStarSystem();
			if (system == null) continue;
			if (posture && ThreatPosture.pressed(system)) continue;
			// the wave's substance is a mustered Defense Swarm: the colony (or a
			// sibling in its system, launchPool) must have one to spare above
			// its defensive reserve
			int spare = garrisonAvailableForLaunch(market);
			if (spare < 1) continue;
			if (requireStable ? !isStableForExpansion(market) : !canProjectFleets(market)) continue;
			float d = Misc.getDistanceLY(system.getLocation(), target.getLocation());
			// colonization is fuel-bound exactly like strikes: a wave can only
			// be sent as far as the fuel the hive network delivers to this
			// colony will carry it. Cut their fuel and the swarm stops seeding.
			if (d > fuelRangeLY(market)) continue;
			// a forge whose system can pay the founding goes before one that
			// cannot (2026-09-29, ti-h8e: the swarm-richest forge was picked,
			// failed its bill, and the claim waited months with 70k FP banked)
			boolean pays = poolableFP(market) >= foundingFP(FOUNDING_CORE_STRUCTURES + 1);
			boolean better;
			if (best != null && pays != bestPays) better = pays;
			else better = posture && spare != bestSpare ? spare > bestSpare : d < bestDist;
			if (better) {
				bestDist = d;
				bestSpare = spare;
				bestPays = pays;
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
	//
	// (2026-09-29: founding is paid, not transferred.) A Seeding Swarm used to
	// dig in as its colony's first garrison, so a founding moved its fleet
	// points from one bank's garrison to another's and the colony itself came
	// free - hives went 36 to 90. Now the wave's hulls are consumed into the
	// colony, the launching colony's bank pays for the structures it is founded
	// with (foundingFPPerStructure each), the forge retools (retoolForge), and
	// the new hive builds its garrison from an empty bank of its own.

	/** Fleet memory: the fleet points of structures a Seeding Swarm's source paid for its colony. Absent on waves launched before founding was paid, which land as garrisons. */
	public static final String FOUNDING_FP_KEY = "$threatinc_foundingFP";
	/** Fleet memory: the market id whose bank paid FOUNDING_FP_KEY. */
	public static final String FOUNDING_SOURCE_KEY = "$threatinc_foundingSource";

	/** The structures foundColony gives every hive: Population, Spaceport, Fabrication Core, Swarm Nexus. */
	public static final int FOUNDING_CORE_STRUCTURES = 4;

	/** Fleet points this many founding structures cost (threatinc_foundingFPPerStructure each). */
	public static float foundingFP(int structures) {
		return Math.max(0, structures) * Math.max(0f, ThreatIncConfig.foundingFPPerStructure());
	}

	/**
	 * The structures a colony founded on this planet will have, as a launch
	 * books them: the four every hive gets, and Mining where the planner will
	 * put it (planHiveEconomy). A landing settles the real count (settleFounding).
	 */
	protected static int foundingStructuresEstimate(PlanetAPI planet) {
		MarketAPI market = planet != null ? planet.getMarket() : null;
		return FOUNDING_CORE_STRUCTURES + (market != null && hasMiningDeposits(market) ? 1 : 0);
	}

	// every structure the hive adds is bought the same way (2026-09-29: the
	// planner's builds and a conquest's founding came free). payerId is the
	// bank that pays; null means it was paid for elsewhere (a Seeding Swarm's
	// source at launch) or by nobody (the debug war, save heals)

	/** Market id -> true while its planner's build waits on a bank that cannot pay (buyWaitingStructures). */
	public static final String KEY_BUILD_WAITING = "threatinc_buildWaiting";

	protected static Map<String, Boolean> buildWaiting() {
		return ThreatIncData.map(KEY_BUILD_WAITING);
	}

	/** Whether payerId's bank covers one structure; if not, the colony's build waits (flagged). */
	protected static boolean affordStructure(MarketAPI market, String payerId) {
		float cost = foundingFP(1);
		if (payerId == null || cost <= 0f || fpBank(payerId) >= cost) return true;
		buildWaiting().put(market.getId(), true);
		return false;
	}

	/** Adds the structure if payerId's bank pays for it (affordStructure); false if it waits. */
	protected static boolean buyStructure(MarketAPI market, String industryId, String payerId) {
		if (!affordStructure(market, payerId)) return false;
		if (payerId != null) drawFP(payerId, foundingFP(1));
		market.addIndustry(industryId);
		return true;
	}

	/**
	 * Adds a structure the colony cannot exist or earn without, charged to
	 * payerId's bank whatever it holds: a debt its production pays off before
	 * it builds anything else, never a structure for free.
	 */
	protected static void addEssentialStructure(MarketAPI market, String industryId, String payerId) {
		if (payerId != null) drawFP(payerId, foundingFP(1));
		market.addIndustry(industryId);
	}

	/**
	 * Poll: every colony whose planner build waits on its bank tries again,
	 * paying from its own. One planner turn each; the flag clears when a
	 * turn finds nothing it cannot pay for.
	 */
	public static void buyWaitingStructures() {
		if (buildWaiting().isEmpty()) return;
		for (String id : new ArrayList<String>(buildWaiting().keySet())) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(id);
			if (market == null) {
				buildWaiting().remove(id);
				continue;
			}
			if (fpBank(id) < foundingFP(1)) continue;
			int had = market.getIndustries().size();
			planHiveEconomy(market);
			if (market.getIndustries().size() != had) {
				ThreatIncConfig.log("Hive planner: waiting build bought at " + market.getName() + " ("
						+ (int) fpBank(id) + " FP banked)");
			}
		}
	}

	/**
	 * A wave that founded its colony: the structures it really stands with
	 * against what its source paid at launch, the difference settled with
	 * that bank (the new colony's own, the source gone).
	 */
	protected static void settleFounding(CampaignFleetAPI fleet, MarketAPI market) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float booked = mem.getFloat(FOUNDING_FP_KEY);
		String source = mem.getString(FOUNDING_SOURCE_KEY);
		mem.unset(FOUNDING_FP_KEY);
		mem.unset(FOUNDING_SOURCE_KEY);
		// its supplies and fuel went into the colony
		ThreatFuel.unloadFounding(fleet, false);
		int structures = market.getIndustries().size();
		float diff = foundingFP(structures) - booked;
		if (diff > 0f) {
			if (source != null && ThreatIncData.resolveColonyMarket(source) != null) drawFP(source, diff);
			else chargeFP(market, diff);
		} else if (diff < 0f) {
			creditHome(source, -diff, fleet);
		}
		ThreatIncConfig.log("Founded " + market.getName() + ": " + (int) fleet.getFleetPoints() + " FP of hulls and "
				+ structures + " structures (" + (int) (booked + diff) + " FP) consumed");
	}

	/**
	 * A wave that will not found its colony: the structures its source paid
	 * for go back (to the nearest live colony, the source gone). Its hulls
	 * follow the ledger - re-banked if it withdraws, lost if it was shot down.
	 * Returns the fleet points refunded.
	 */
	protected static float refundFounding(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		// its supplies and fuel ride it: home with a wave that withdraws, lost with one shot down
		float[] cargo = ThreatFuel.unloadFounding(fleet, true);
		if (cargo[0] + cargo[1] > 0f) {
			ThreatIncConfig.log("Founding cargo " + (fleet.isAlive() ? "back in the hive's stock" : "lost with the wave")
					+ ": " + (int) cargo[0] + " supplies, " + (int) cargo[1] + " fuel");
		}
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (!mem.contains(FOUNDING_FP_KEY)) return 0f;
		float fp = mem.getFloat(FOUNDING_FP_KEY);
		String source = mem.getString(FOUNDING_SOURCE_KEY);
		mem.unset(FOUNDING_FP_KEY);
		mem.unset(FOUNDING_SOURCE_KEY);
		String to = creditHome(source, fp, fleet);
		if (fp > 0f) {
			ThreatIncConfig.log("Founding refunded: " + (int) fp + " FP of structures"
					+ (to == null ? " lost, no hive left" : " to " + to));
		}
		return to != null ? fp : 0f;
	}

	/**
	 * A wave dropped from waveFleets with no colony to found (ThreatIncData.clearSystem):
	 * its founding is refunded and it withdraws, where untracked it would
	 * have orbited its target for good.
	 */
	public static void abandonWave(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		refundFounding(fleet);
		if (fleet.isAlive()) retireFleet(fleet, fleet.getStarSystem());
	}

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
		int[] spec = { 1, escortIdx };

		// the expedition is paid for in real fleets: one Defense Swarm leaves
		// the source's garrison to become the seeding wave's substance, and the
		// source's bank pays whatever the wave weighs beyond it (the fabrication
		// ledger, 2026-09-29) plus the structures the colony will be founded
		// with (2026-09-29: founding is paid, not transferred) - a wave it
		// cannot pay for waits
		float structuresFP = 0f;
		if (source != null) {
			// a forge still retooled around its last wave builds no other
			// (pickForgeSource checks it; this holds every caller to it)
			if (!hasReadyForge(source)) return false;
			float[] swarmFP = { 0f };
			peekGarrison(source, 1, swarmFP);
			structuresFP = foundingFP(foundingStructuresEstimate(targetPlanet));
			float bill = swarmCostEstimate(ThreatFleetComposer.JOB_SEEDING, spec) - swarmFP[0] + structuresFP;
			// the way out comes from the hive's fuel (ThreatFuel); the wave stays.
			// It carries what a faction's forward base costs, supplies and fuel
			// (ThreatFuel.foundingCost), from the hive's stocks
			if (!ThreatFuel.canFound(ThreatFuel.passage(swarmCostEstimate(ThreatFleetComposer.JOB_SEEDING, spec),
					ThreatFuel.ly(source.getStarSystem(), targetSystem), false))) {
				ThreatFuel.held("a Seeding Swarm from " + source.getName());
				return false;
			}
			if (bill > 0f && !poolSystemBanks(source, bill)) return false;
		}

		CampaignFleetAPI fleet = ThreatFleetComposer.create(ThreatFleetComposer.JOB_SEEDING,
				1, escort, random);
		if (fleet == null) return false;

		if (source != null) {
			float[] paid = { 0f };
			consumeGarrison(source, 1, paid);
			float diff = fleet.getFleetPoints() - paid[0];
			if (diff > 0f) chargeFP(source, diff);
			else creditFP(source, -diff);
			// its own job's mean: a seeding wave is not composed as a garrison
			// swarm, and {1, tier} is also a garrison row's key
			learnSwarmCost(ThreatFleetComposer.JOB_SEEDING, spec, fleet.getFleetPoints());
			// (2026-09-29: closed economy - a wave that withdraws flies its hulls
			// back into the source's bank; one that lands is consumed into its
			// colony, checkWaveArrivals)
			bindToLedger(fleet, source.getId());
			// the colony's structures, paid now so an unaffordable founding
			// never sails; refunded if the wave never founds (refundFounding).
			// The key's presence marks a wave launched under these rules
			chargeFP(source, structuresFP);
			fleet.getMemoryWithoutUpdate().set(FOUNDING_FP_KEY, structuresFP);
			fleet.getMemoryWithoutUpdate().set(FOUNDING_SOURCE_KEY, source.getId());
			float fuel = ThreatFuel.passage(fleet.getFleetPoints(), ThreatFuel.ly(source.getStarSystem(), targetSystem), false);
			ThreatFuel.pay(Math.min(ThreatFuel.stock(), fuel));
			ThreatFuel.loadFounding(fleet);
			float retool = retoolForge(source, fleet.getFleetPoints());
			ThreatIncConfig.log("Seeding Swarm from " + source.getName() + ": " + (int) fleet.getFleetPoints()
					+ " FP of hulls, " + (int) structuresFP + " FP of structures, "
					+ (int) fleet.getMemoryWithoutUpdate().getFloat(ThreatFuel.MEM_FOUND_SUPPLIES) + " supplies and "
					+ (int) (fleet.getMemoryWithoutUpdate().getFloat(ThreatFuel.MEM_FOUND_FUEL) + fuel)
					+ " fuel, forge retooling "
					+ (int) retool + " days (" + (int) bankedFP(source) + " FP banked)");
		}
		fleet.setName("Seeding Swarm");
		// tier tag rides the fleet: when a wave from an old save digs in as its
		// new colony's first garrison, later musters know exactly what it is
		fleet.getMemoryWithoutUpdate().set(SWARM_TIER_KEY, escortIdx);
		fleet.getMemoryWithoutUpdate().set(SWARM_FABS_KEY, 1);
		fleet.getMemoryWithoutUpdate().set(WAVE_FLAG, targetSystem.getId());
		// out of the Abyss: its colony lands with a stockpile (endowSeed)
		if (source == null) fleet.getMemoryWithoutUpdate().set(BOOTSTRAP_WAVE_FLAG, true);
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
				abandonWave(fleet);
				continue;
			}
			boolean firstColony = ThreatIncData.getLiveColonyMarkets(systemId).isEmpty();

			if (fleet == null || !fleet.isAlive()) {
				// wave destroyed: the claim survives but the colony doesn't. Its
				// hulls are lost; the structures its source paid for were never built
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				refundFounding(fleet);
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
				ThreatNotice notice = ThreatNotice.titled("Seeding Swarm Destroyed").good()
						.line("The swarm bound for %s is gone",
								planetName != null ? planetName : "the " + system.getNameWithLowercaseType());
				if (otherInbound > 0) {
					notice.line("%s more still inbound to the %s", ThreatNotice.red(otherInbound),
							system.getNameWithLowercaseType());
				}
				announce(notice);
				ThreatIncConfig.log("Wave destroyed: " + system.getName());
				continue;
			}

			SectorEntityToken target = Global.getSector().getEntityById(planetId);
			if (!(target instanceof PlanetAPI)) {
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				refundFounding(fleet);
				if (firstColony) ThreatIncData.clearSystem(systemId);
				retireFleet(fleet, system);
				continue;
			}
			PlanetAPI planet = (PlanetAPI) target;

			// an outpost stands over it: no colony can be founded until the
			// station falls - the wave stays and fights (ThreatOutposts)
			if (ThreatOutposts.holds(planet)) continue;

			// someone colonized it mid-flight: withdraw
			MarketAPI existing = planet.getMarket();
			if (existing == null || (!ThreatMapFog.conditionOnly(existing)
					&& !Factions.NEUTRAL.equals(existing.getFactionId()))) {
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				refundFounding(fleet);
				if (firstColony) ThreatIncData.clearSystem(systemId);
				retireFleet(fleet, system);
				ThreatIncConfig.log("Wave withdrew from claimed planet at " + system.getName());
				continue;
			}

			if (fleet.getContainingLocation() == system
					&& Misc.getDistance(fleet, planet) < 300f + planet.getRadius()) {
				// a paid founding consumes the fleet: not out from under a battle
				boolean paidFounding = fleet.getMemoryWithoutUpdate().contains(FOUNDING_FP_KEY);
				if (paidFounding && fleet.getBattle() != null) continue;
				boolean conversion = existing.hasCondition(Conditions.DECIVILIZED);
				MarketAPI market = foundColony(planet, 1);
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				if (market == null) {
					refundFounding(fleet);
					if (firstColony) ThreatIncData.clearSystem(systemId);
					retireFleet(fleet, system);
					continue;
				}

				ThreatIncData.setStage(systemId, ThreatIncData.STAGE_COLONY);
				ThreatIncData.colonyMarketsFor(systemId).add(market.getId());
				ThreatIncData.setGrowthTime(market.getId());
				ThreatIncData.decivTargets().remove(planet.getId());
				// the beachhead is established; the Abyss sends no more
				ThreatIncData.bootstrapSeeds().remove(systemId);

				if (fleet.getMemoryWithoutUpdate().getBoolean(BOOTSTRAP_WAVE_FLAG)) {
					fleet.getMemoryWithoutUpdate().unset(BOOTSTRAP_WAVE_FLAG);
					endowSeed(market);
				}

				if (paidFounding) {
					// the seeding swarm's hulls ARE the colony: consumed, not
					// re-banked (unbound before the despawn the ledger would
					// credit), and no garrison - the new hive fabricates its
					// floor from its own bank, which starts empty
					settleFounding(fleet, market);
					unbindLedger(fleet);
					fleet.clearAssignments();
					fleet.despawn();
				} else {
					// a wave launched before founding was paid (or out of the
					// Abyss) digs in as the first garrison, as it was booked
					fleet.clearAssignments();
					fleet.setName("Defense Swarm");
					fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, market.getId());
					// its hulls are the new colony's garrison now, not a debt home
					unbindLedger(fleet);
					fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
					ThreatIncData.garrisonsFor(market.getId()).add(fleet);
				}

				if (conversion) {
					announce(ThreatNotice.titled("Ruins Claimed").bad()
							.line("Fabrication strata spread through the ruins of %s", planet.getName())
							.line("The swarm has claimed what it killed"));
				} else if (firstColony) {
					announce(ThreatNotice.titled("Hive Takes Root").bad()
							.line("The swarm has taken root on %s", planet.getName())
							.line("In the %s", system.getNameWithLowercaseType()));
				} else {
					announce(ThreatNotice.titled("Hive Spreads").bad()
							.line("%s is being converted to fabrication strata", planet.getName())
							.line("Another world in the %s", system.getNameWithLowercaseType()));
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
	 * Whether any colonization wave is in flight - the board's "the war is not
	 * over yet" test. No longer a gate (2026-09-29): the swarm used to run one
	 * colonization attempt at a time sector-wide; now each target planet may
	 * have its own wave (waveFleets is keyed by planet), each paid for with a
	 * mustered Defense Swarm.
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

	/**
	 * The swarm consolidates before it reaches outward: every colonized system
	 * that still has a resource-bearing planet unclaimed gets waves, each
	 * fabricated (and paid for) by the nearest ready forge - not necessarily a
	 * local one, so a young frontier system is consolidated by the core's
	 * forges until it can build its own. A wave per unclaimed planet, as many
	 * as the hive has Defense Swarms to spare above its reserves (2026-09-29:
	 * it was one wave per pass, and none while any wave flew anywhere). A
	 * strained hive still claims only the planets that relieve its shortfalls
	 * (pickExpansionPlanet's anyNominalColony gate). Returns true if any wave
	 * launched.
	 */
	public static boolean tryExpandInSystem(Random random) {
		boolean any = false;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			StarSystemAPI system = getSystem(systemId);
			if (system == null) continue;
			// only a system the swarm actually holds grows in place
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;

			// terminates: each launch books its planet in waveFleets, which
			// pickExpansionPlanet skips, and spends a swarm pickForgeSource counts
			while (true) {
				PlanetAPI planet = pickExpansionPlanet(system);
				if (planet == null) break;
				MarketAPI source = pickForgeSource(system, false);
				if (source == null) break;
				if (!launchColonizationWave(source, system, planet, random)) break;
				any = true;
			}
		}
		return any;
	}

	// ------------------------------------------------------------------
	// vitality: health-scaled growth, and decline under siege
	// ------------------------------------------------------------------

	/**
	 * How much of a defensive organ's bonus still fires while it is disrupted:
	 * its CONDITION, the hive's half of the fortification rule
	 * (docs/ground-war.md "Sieges from orbit", 2026-09-06). Machines do not
	 * rout, so the guns keep firing - but they wear: the bonus falls in a
	 * straight line with the disruption days on the structure's clock,
	 * reaching zero at defenseWearDays. Disruption stacks (every siege slice and every
	 * successful raid adds its own), so a structure carrying 300 days has been
	 * hit again and again, and by then it is scrap. The size-anchored base
	 * (hiveDefensePerSize x size) is untouched - the strata below the crust
	 * do not stop existing - so bombardment never gets free, only cheaper as
	 * the war-strata are ground down. Shared by ThreatGroundDefenses
	 * (batteries too) and SwarmNexus.
	 */
	public static float disruptedDefenseResilience(Industry ind) {
		if (ind == null || !ind.isDisrupted()) return 1f;
		float wear = ThreatIncConfig.defenseWearDays();
		if (wear <= 0f) return 1f;
		return Math.min(1f, Math.max(0f, 1f - ind.getDisruptedDays() / wear));
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
	 * The fabrication half of colony health: FABRICATION CORE condition. A
	 * hive's growth is its Fabrication Core's ability to grow new population
	 * strata, and a disrupted Core fabricates in proportion to how deep its
	 * disruption runs: coreDownFactor of full output the moment it is hit
	 * (1.0 by default - the days on the clock alone decide), falling in a
	 * straight line to nothing at defenseWearDays (decided 2026-09-05: a
	 * front that held one day used to quarter the hive's health and quadruple
	 * its counter-attack interval at a stroke). A missing Core, or one still
	 * building, fabricates nothing. The Core is the ONLY fabrication organ in
	 * this figure. The Swarm Nexus is deliberately
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
		if (core == null || (!core.isDisrupted() && !core.isFunctional())) {
			// no Core, or one still building: nothing to fabricate with
			// (ensureFabricationCores heals a missing one on the next sweep)
			mult = 0f;
		} else if (core.isDisrupted()) {
			mult *= wornDownFactor(core, ThreatIncConfig.coreDownFactor());
		}
		// strata held by a ground front are strata not fabricating: each one
		// taken strips its share of the colony's output (docs/ground-war.md)
		int held = ThreatGroundFronts.strataHeld(market.getId());
		if (held > 0 && market.getSize() > 0) {
			mult *= Math.max(0f, (market.getSize() - held) / (float) market.getSize());
		}
		return mult;
	}

	/**
	 * A disrupted organ's fabrication factor: base x the same linear wear the
	 * defensive bonuses take (defenseWearDays). With the default base of 1.0 a
	 * Core carrying 60 days (one tactical pass) fabricates at 80 percent, one
	 * carrying 150 days at half, and at 300 days nothing fabricates at all -
	 * only ground forces and raids drive a clock that far. An undisrupted
	 * organ returns the base.
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
	 * Colony health in [0..1] = fabrication (the Core, worn by its disruption
	 * days) x supply (inputs, reduced capacity). Multiplicative, and a disrupted Fabrication Core also
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
	 * The health figure below which the UI grades a colony as failing (red
	 * vitality, "collapsing" labels). Purely presentational since the siege
	 * rework: low health weakens a colony - stalled growth, thin garrisons,
	 * feeble counter-attacks - but only a ground victory kills it
	 * (docs/ground-war.md).
	 */
	public static final float CRITICAL_HEALTH = 0.35f;

	/**
	 * Called from the fast-cadence poll (~half-day), NOT the 30-day tick:
	 * health and growth accrue CONTINUOUSLY, pro-rated from the per-30-day
	 * config rates. Since the ground-war rework there is no decline engine
	 * here - starvation only weakens (stalls growth, thins everything scaled
	 * by health); colonies die exclusively to ground victory
	 * (ThreatGroundFronts.groundVictory).
	 *
	 * @param elapsedDays campaign days since the previous poll
	 */
	public static void updateColonyVitality(float elapsedDays) {
		if (elapsedDays <= 0f) return;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			String id = market.getId();
			float health = computeHealth(market);
			ThreatIncData.setLastHealth(id, health);

			applyHiveOrder(market);
			pinMaxSize(market);

			// no growth while a ground front is on the surface - a colony
			// fighting inside its own strata builds no new ones - or while
			// saturation falls on it (docs/suppression-balance.md v2)
			if (ThreatGroundFronts.hasFront(market) || ThreatRazing.saturated(market)) continue;

			int size = market.getSize();
			int cap = maxColonySize(market);
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

			growColony(market, cap);
		}
	}

	/**
	 * Days until a hive grows its next size at the rate it grows now
	 * (updateColonyVitality's rule); Float.MAX_VALUE for a world that is not a
	 * hive, is at its cap, starving, or held by a front or saturation.
	 */
	public static float daysToNextSize(MarketAPI market) {
		if (market == null || !Factions.THREAT.equals(market.getFactionId())) return Float.MAX_VALUE;
		if (ThreatGroundFronts.hasFront(market) || ThreatRazing.saturated(market)) return Float.MAX_VALUE;
		int size = market.getSize();
		if (size >= maxColonySize(market)) return Float.MAX_VALUE;
		float growthMult = growthMultFor(computeHealth(market));
		if (growthMult <= 0f) return Float.MAX_VALUE;
		float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size * IncursionManager.timeScale();
		return Math.max(0f, daysPerLevel - ThreatIncData.growthProgressDays(market.getId())) / growthMult;
	}

	/**
	 * Machine hive-order (docs/suppression-balance.md v2 section 5): a hive's
	 * stability is held at 10 less the unrest bombardment has raised
	 * (vanilla's RecentUnrest), so no other vanilla source reaches it - the
	 * shortages that once rioted it no more than anything that would lift it -
	 * and that unrest cuts its ground defence as it cuts a human colony's.
	 * Re-pinned every poll and after each bombardment.
	 */
	public static void applyHiveOrder(MarketAPI market) {
		if (market == null) return;
		com.fs.starfarer.api.combat.MutableStat stability = market.getStability();
		stability.unmodifyFlat(STABILITY_MOD_ID);
		float target = 10f - com.fs.starfarer.api.impl.campaign.econ.RecentUnrest.getPenalty(market);
		stability.modifyFlat(STABILITY_MOD_ID, target - stability.getModifiedValue(), "Machine hive-order");
	}

	/**
	 * One growth step: size up, plan one industry, announce the milestones.
	 * The vitality engine's step, and the debug floor's (ThreatDebugWar.
	 * pollFloor). @return whether the size actually rose.
	 */
	public static boolean growColony(MarketAPI market, int cap) {
		String id = market.getId();
		int old = market.getSize();
		CoreImmigrationPluginImpl.increaseMarketSize(market);
		ThreatIncData.setGrowthProgressDays(id, 0f);
		if (market.getSize() <= old) return false;
		ListenerUtil.reportColonySizeChanged(market, old);
		ThreatIncData.setGrowthTime(id);
		// the Fabrication Core's machinery output tracks size by itself
		// (its apply() reads market size on every econ recompute)
		planHiveEconomy(market);

		int newSize = market.getSize();
		if (newSize == 4 || newSize == 6 || newSize >= cap) {
			ThreatNotice notice = ThreatNotice.titled("Hive Grows").bad()
					.line("%s has grown to size %s", ThreatNotice.market(market), newSize);
			if (newSize >= cap) notice.line("Its growth has reached saturation");
			announce(notice);
		}
		ThreatIncConfig.log("Colony grew to " + newSize + ": " + market.getName());
		return true;
	}

	/**
	 * ERADICATION: the teardown of a Threat colony, by one of the two ways it
	 * dies - a ground victory, or saturation razed to its last stratum
	 * (docs/suppression-balance.md v2) - never a timer. Vitality bookkeeping
	 * cleared, then the vanilla decivilization teardown; pollColonies reacts
	 * on the next poll.
	 */
	public static void eradicate(MarketAPI market) {
		if (market == null) return;
		ThreatIncConfig.log("Colony eradicated: " + market.getName());
		ThreatIncData.clearVitality(market.getId());
		// a purged world: somebody may fortify it before the swarm returns
		ThreatOutposts.recordPurged(market);
		// fullDestroy bypasses NO_DECIV_KEY (verified against 0.98a source)
		DecivTracker.decivilize(market, true);
	}

	// ------------------------------------------------------------------
	// garrisons
	// ------------------------------------------------------------------

	/**
	 * Garrison composition by colony size; each row is one fleet as
	 * {numFabricators, FabricatorEscortStrength ordinal}. The table is the
	 * garrison's FLOOR (2026-09-29: it was also its ceiling); swarms grown past
	 * it take its rows in turn (maintainGarrisons).
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
	 * Prunes dead garrison fleets, banks the nexus's production (the
	 * fabrication ledger, accrueFabrication) and fabricates a swarm whenever
	 * the bank pays for it, one per colony per poll: the floor's unheld slots first, then past the
	 * floor with no ceiling (2026-09-29: the size table capped every garrison,
	 * and a swarm came free every interval). Past the whole table a swarm grows
	 * a standing fleet of its spec up to maxShipsInAIFleet before it takes orbit
	 * as a fleet of its own (growGarrisonFleet). The garrison orbits its own colony
	 * planet - it is both the "defenders want to fight" gate on bombardment and
	 * the visible face of the colony's strength. Hull shortages lower the
	 * floor and the income that pays for it; with no hulls nothing is built.
	 */
	public static void maintainGarrisons(Random random) {
		float hiveOutput = hiveShipOutput();
		float hiveDraw = hiveNexusDraw();
		endowSave(hiveOutput, hiveDraw);
		ThreatFuel.accrue();
		// the fleets each bank paid for that are out in space (upkeep)
		Map<String, Float> ledgerFP = ledgerFleetFP();
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

			// production accrues (or, organs down, does not) whatever happens next,
			// and the fleets the colony keeps cost their upkeep over the same days
			float income = fabricationRatePerDay(market, hiveOutput, hiveDraw);
			Float outFP = ledgerFP.get(marketId);
			float upkeep = upkeepPerDay(ownedFleetFP(market, fleets) + (outFP != null ? outFP : 0f));
			accrueFabrication(market, income, upkeep);
			// a bank that cannot carry its fleets gives some back (organs up or not)
			recycleForUpkeep(market, fleets, income, upkeep);

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

			// the floor: a hull-starved colony's is a fraction of its nominal
			// garrison (same figure the launch gates read - see
			// desiredGarrisonCount). What counts against it is slots PROPERLY
			// HELD (countFitGarrison), not fleets parked: a weak holder - a swarm
			// shot under strength, or one too light for its slot (a small
			// colony's swarm sent here to help) - keeps fighting but does not
			// stop the nexus growing the real thing
			int desired = desiredGarrisonCount(market);
			// a swarm out raiding or on its way in holds its slot
			int away = swarmsAway(marketId);
			int fit = countFitGarrison(market);
			boolean belowFloor = fit + away < desired;
			// under posture the want is the floor (ThreatPosture: the reserve and
			// a forge's launch stock, or the war's need) and the table only
			// shapes what is built: what the colony holds and has inbound against
			// it, never below one swarm
			boolean posture = ThreatPosture.enabled();
			if (posture) {
				belowFloor = fleets.size() + away < 1
						|| ThreatPosture.wantsGrowth(market, ownedFleetFP(market, fleets));
				desired = ThreatPosture.baseCount(market);
			}

			// an unheld floor slot first, most demanding first; past the floor
			// the table's rows in turn, so the garrison keeps its mix (counted in
			// swarms: past the table they grow fleets, growGarrisonFleet)
			int swarms = 0;
			for (CampaignFleetAPI curr : fleets) swarms += swarmCount(curr);
			int[] spec = table[fit < table.length ? fit : (swarms + away) % table.length];
			// production pays for the garrison: the bank must hold what the swarm
			// will cost (a strained hive banks slower; a starved one, nothing)
			if (bankedFP(market) < swarmCostEstimate(spec)) continue;
			// a structure waiting on the bank comes before growth past the floor,
			// or swarms cheaper than it would spend every FP as it banked
			if (!belowFloor && buildWaiting().containsKey(marketId)) continue;
			// nothing past the want: the bank keeps the rest for waves, strikes
			// and foundings
			if (posture && !belowFloor) continue;
			// (2026-09-29: closed economy - the bank is the only bound. The nexus
			// used to spend it at one swarm per garrisonRespawnDays, which the
			// alarm quickened: a second, arbitrary cap on top of production. Now
			// it builds whenever the bank pays, one swarm per colony per poll
			// (this loop's cadence, ~half a day) - so a large endowment comes
			// out over days, never as a frame full of fleets)

			// the head-count is at the floor but a slot is only weakly held: the
			// fresh swarm REPLACES the weakest weak holder not in a battle, and
			// its hulls go back into the bank. Retired only after the fresh one
			// is built, so a failed spawn costs nothing
			CampaignFleetAPI recycled = null;
			if (belowFloor && fleets.size() + away >= desired) recycled = weakestWeakHolder(market);

			CampaignFleetAPI fleet = buildGarrisonSwarm(market, spec, random);
			if (fleet == null) continue;
			float cost = fleet.getFleetPoints();
			chargeFP(market, cost);
			learnSwarmCost(spec, cost);

			// every row of the table properly held: the swarm's hulls join a
			// standing fleet of its spec with room for them, up to
			// maxShipsInAIFleet, and only a swarm no fleet has room for takes
			// orbit as a fleet of its own (2026-09-29 review: growth past the
			// floor added a fleet a swarm, dozens to hundreds over a rich hive).
			// The same hulls at the same price - fewer, fuller fleets
			CampaignFleetAPI host = fit >= table.length ? garrisonHostFor(fleets, planet, spec, fleet) : null;
			if (host != null) {
				growGarrisonFleet(host, fleet);
				ThreatIncConfig.log("Garrison swarm fabricated into a standing fleet at " + market.getName()
						+ " (" + swarmCount(host) + " swarms, " + host.getFleetData().getNumMembers() + " ships; "
						+ (int) cost + " FP, " + (int) bankedFP(market) + " FP banked)");
				continue;
			}
			placeGarrisonSwarm(market, fleet, random);

			if (recycled != null) {
				// read before despawn: a despawned fleet reports 0 FP
				int oldTier = swarmTier(recycled);
				float oldFP = recycled.getFleetPoints();
				fleets.remove(recycled);
				recycled.despawn();
				creditFP(market, oldFP);
				ThreatIncConfig.log("Recycled weak Defense Swarm at " + market.getName()
						+ " (tier " + oldTier + ", " + (int) oldFP + " FP) for a fresh one");
			}

			fleets.add(fleet);
			ThreatIncConfig.log("Garrison fleet fabricated at " + market.getName()
					+ " (" + fleets.size() + ", floor " + desired + ", " + (int) cost + " FP, "
					+ (int) bankedFP(market) + " FP banked)");
		}
	}

	/** The colony poll's cadence (IncursionManager's 0.4-0.6 day interval). */
	public static final float GARRISON_POLL_DAYS = 0.5f;

	// ------------------------------------------------------------------
	// the fabrication ledger: every Threat fleet is paid for
	// ------------------------------------------------------------------
	//
	// (2026-09-29, the closed economy.) Vanilla's hull availability is a rate
	// the whole econ group reads at once, consumed by nothing - so the swarm's
	// fleets used to come free, one per respawn interval. Now the hive's forges
	// make fleet points from the hulls they really produce, each colony's
	// Swarm Nexus BANKS its share (fabricationRatePerDay), and every Threat
	// fleet it fabricates is PAID from that bank: garrison swarms, the wave
	// fleet's difference over the swarm it is made of and the structures of
	// the colony it founds (foundingFP), Scouting Swarms, a
	// recycled swarm's replacement less the hulls it gives back. No forges, no
	// production, no fleets. Strength goes where it is needed by moving swarms
	// (reinforcement, pooled musters), which costs nothing new.

	public static final String KEY_FAB_BANK = "threatinc_fabBank";
	public static final String KEY_FAB_ACCRUED_AT = "threatinc_fabAccruedAt";
	public static final String KEY_FAB_COSTS = "threatinc_fabCosts";

	/** Market id -> fleet points banked by its nexus (may dip below 0: a fleet built on the last of it). */
	public static Map<String, Float> fabBank() {
		return ThreatIncData.map(KEY_FAB_BANK);
	}

	/** Market id -> when its production was last banked. */
	protected static Map<String, Long> fabAccruedAt() {
		return ThreatIncData.map(KEY_FAB_ACCRUED_AT);
	}

	/** costKey ("tier:fabricators" for a garrison swarm, "job/tier:fabricators" else) -> the fleet points such a fleet has come out at (running mean). */
	protected static Map<String, Float> fabCosts() {
		return ThreatIncData.map(KEY_FAB_COSTS);
	}

	public static float bankedFP(MarketAPI market) {
		if (market == null) return 0f;
		Float v = fabBank().get(market.getId());
		return v != null ? v : 0f;
	}

	/** Takes fleet points out of the colony's bank (a fleet it fabricated). */
	public static void chargeFP(MarketAPI market, float fp) {
		if (market == null || fp <= 0f) return;
		fabBank().put(market.getId(), bankedFP(market) - fp);
	}

	/** Puts fleet points back in the colony's bank (hulls recycled). */
	public static void creditFP(MarketAPI market, float fp) {
		if (market == null || fp <= 0f) return;
		fabBank().put(market.getId(), bankedFP(market) + fp);
	}

	/**
	 * Whether the source's bank, topped up from its system's other colonies,
	 * pays the bill; tops it up when it does. A sibling gives only while it
	 * holds its garrison (ThreatPosture.regrowing) and only what it has banked.
	 * Its Defense Swarms already pool for a launch (launchPool); its bank did
	 * not, and a founding waited on one colony's bank while 42k FP sat in the
	 * others (2026-09-29, ti-h8d).
	 */
	public static boolean poolSystemBanks(MarketAPI source, float bill) {
		if (source == null) return false;
		float short0 = bill - bankedFP(source);
		if (short0 <= 0f) return true;
		if (source.getStarSystem() == null) return false;
		List<MarketAPI> givers = new ArrayList<MarketAPI>();
		float can = 0f;
		for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(source.getStarSystem().getId())) {
			if (m == source || bankedFP(m) <= 0f) continue;
			if (ThreatPosture.regrowing(m, ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId())))) continue;
			givers.add(m);
			can += bankedFP(m);
		}
		if (can < short0) return false;
		float left = short0;
		StringBuilder from = new StringBuilder();
		for (MarketAPI m : givers) {
			if (left <= 0f) break;
			float take = Math.min(left, bankedFP(m));
			chargeFP(m, take);
			creditFP(source, take);
			left -= take;
			if (from.length() > 0) from.append(", ");
			from.append(m.getName()).append(' ').append((int) take);
		}
		ThreatIncConfig.log("Founding bill at " + source.getName() + ": " + (int) bill + " FP, "
				+ (int) short0 + " pooled from " + from);
		return true;
	}

	/** What poolSystemBanks could put toward a bill at the source: its bank and its siblings' that give. */
	public static float poolableFP(MarketAPI source) {
		if (source == null) return 0f;
		float fp = bankedFP(source);
		if (source.getStarSystem() == null) return fp;
		for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(source.getStarSystem().getId())) {
			if (m == source || bankedFP(m) <= 0f) continue;
			if (ThreatPosture.regrowing(m, ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId())))) continue;
			fp += bankedFP(m);
		}
		return fp;
	}

	/** Whether the colony's bank pays for a fleet of this many points. */
	public static boolean canAffordFP(MarketAPI market, float fp) {
		return market != null && bankedFP(market) >= fp;
	}

	/** The bank by market id (the hooks' API: strikes, their survivors). */
	public static float fpBank(String marketId) {
		if (marketId == null) return 0f;
		Float v = fabBank().get(marketId);
		return v != null ? v : 0f;
	}

	/** Takes fleet points out of a colony's bank by market id. */
	public static void drawFP(String marketId, float fp) {
		if (marketId == null || fp <= 0f) return;
		fabBank().put(marketId, fpBank(marketId) - fp);
	}

	/** Puts fleet points back in a colony's bank by market id (survivors home, hulls recycled). */
	public static void creditFP(String marketId, float fp) {
		if (marketId == null || fp <= 0f) return;
		fabBank().put(marketId, fpBank(marketId) + fp);
	}

	// ------------------------------------------------------------------
	// homecoming: a fleet the bank paid for pays back what comes home
	// ------------------------------------------------------------------
	//
	// (2026-09-29: closed economy - strike fleets and withdrawn waves despawned
	// on return with nothing re-banked, so the damage healed for free.) A fleet
	// bound to the ledger carries its paying colony's id; when it leaves the
	// sector by any road but battle, its fleet points go back to that bank -
	// or, the colony gone, to the hive's nearest live colony. The id is unset
	// on the first settle, so each fleet is credited once, whatever listener
	// or path reaches it again. A fleet that has joined a garrison is the
	// garrison's (musterFrom and recycling account for it) and is not credited,
	// nor is a Seeding Swarm consumed into the colony it founded.

	/** Fleet memory: the market id whose bank a homebound fleet pays back. */
	public static final String LEDGER_HOME_KEY = "$threatinc_ledgerHome";
	/** Fleet memory: the ledger's despawn listener is on the fleet (added once). */
	public static final String LEDGER_BOUND_KEY = "$threatinc_ledgerBound";

	/** Binds the fleet to the colony's bank: what survives of it is credited there when it despawns. */
	public static void bindToLedger(CampaignFleetAPI fleet, String homeMarketId) {
		if (fleet == null || homeMarketId == null) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(LEDGER_HOME_KEY, homeMarketId);
		if (mem.getBoolean(LEDGER_BOUND_KEY)) return;
		mem.set(LEDGER_BOUND_KEY, true);
		fleet.addEventListener(new LedgerReturn());
	}

	/** The fleet's strength is now accounted elsewhere (a garrison, another ledger): nothing to credit. */
	public static void unbindLedger(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		fleet.getMemoryWithoutUpdate().unset(LEDGER_HOME_KEY);
	}

	/** Whether the fleet still owes its bank a homecoming credit. */
	public static boolean ledgerBound(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getString(LEDGER_HOME_KEY) != null;
	}

	/**
	 * Credits what survives of a bound fleet to its bank and unbinds it; 0 if
	 * it was not bound, sits in a garrison, or was destroyed. Called by the
	 * despawn listener, and by a fleet group ending with fleets that never
	 * took to space (ThreatStrikeFGI).
	 */
	public static float settleLedger(CampaignFleetAPI fleet, boolean destroyed) {
		if (fleet == null) return 0f;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		String home = mem.getString(LEDGER_HOME_KEY);
		if (home == null) return 0f;
		mem.unset(LEDGER_HOME_KEY);
		if (destroyed || mem.contains(GARRISON_FLAG)) return 0f;
		float fp = fleet.getFleetPoints();
		if (fp <= 0f) return 0f;
		String to = creditHome(home, fp, fleet);
		ThreatIncConfig.log("Ledger: " + fleet.getName() + " home, " + (int) fp + " FP re-banked"
				+ (to == null ? " - no hive left, lost" : home.equals(to) ? "" : " at " + to));
		return to != null ? fp : 0f;
	}

	/**
	 * Credits fleet points to the colony's bank, or - the colony gone - to the
	 * live colony nearest the fleet (any live colony without one). Returns the
	 * market id credited, null if no colony is left to take them.
	 */
	public static String creditHome(String marketId, float fp, SectorEntityToken near) {
		if (fp <= 0f) return null;
		if (marketId != null && ThreatIncData.resolveColonyMarket(marketId) != null) {
			creditFP(marketId, fp);
			return marketId;
		}
		MarketAPI best = nearestLiveColony(near);
		if (best == null) return null;
		creditFP(best.getId(), fp);
		return best.getId();
	}

	/** The live colony nearest the entity (any live colony without one); null if the hive is gone. */
	public static MarketAPI nearestLiveColony(SectorEntityToken near) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI curr : ThreatIncData.getAllLiveColonyMarkets()) {
			float d = near != null ? Misc.getDistanceLY(near.getLocationInHyperspace(),
					curr.getLocationInHyperspace()) : 0f;
			if (best == null || d < bestDist) {
				best = curr;
				bestDist = d;
			}
		}
		return best;
	}

	/**
	 * A garrison swarm with no colony left to hold (its colony dead, or a
	 * raider come back to none) leaves as a fleet bound for the nearest live
	 * colony's bank: it is a garrison no longer, and what reaches the edge of
	 * the sector is credited as it despawns (LedgerReturn) - what is shot down
	 * on the way is lost, as any fleet's. Returns the colony bound to, null if
	 * no hive is left (nothing to bind; it is lost).
	 */
	public static String bindStrayToNearest(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		fleet.getMemoryWithoutUpdate().unset(GARRISON_FLAG);
		MarketAPI to = nearestLiveColony(fleet);
		if (to == null) {
			unbindLedger(fleet);
			return null;
		}
		bindToLedger(fleet, to.getId());
		return to.getId();
	}

	/** On a ledger-bound fleet: settles it when it despawns (settleLedger). */
	public static class LedgerReturn implements com.fs.starfarer.api.campaign.listeners.FleetEventListener {
		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason reason, Object param) {
			settleLedger(fleet, reason == com.fs.starfarer.api.campaign.CampaignEventListener
					.FleetDespawnReason.DESTROYED_BY_BATTLE);
		}

		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
				com.fs.starfarer.api.campaign.BattleAPI battle) {
		}
	}

	/**
	 * Fleet points a forge ship-unit makes a month - the income knob
	 * (threatinc_fabFPPerShipUnit, default 100). 100 puts a mid-war hive (a
	 * forge per held system, about 0.4 hull units made per unit its nexuses
	 * demand) at 160 FP a month for a size-4 world, 240 at 6 and 320 at 8, the
	 * swarm output the old respawn timer gave (100 / 260 / 335).
	 */
	public static float fpPerShipUnit30d() {
		return Math.max(0f, ThreatIncConfig.fabFPPerShipUnit());
	}

	/** Days of income a save from before the ledger, or the Abyss's first seeds, start with. */
	public static final float FAB_ENDOWMENT_DAYS = 180f;

	/** Set once the ledger has endowed the colonies a save already had. */
	public static final String KEY_FAB_INIT = "threatinc_fabLedgerInit";

	/** Market ids of a pre-ledger save's colonies not yet endowed: each waits for a rate above 0 (endowSave). */
	public static final String KEY_FAB_ENDOW_PENDING = "threatinc_fabEndowPending";

	/** Memory flag on a Seeding Swarm out of the Abyss: its colony is endowed. */
	public static final String BOOTSTRAP_WAVE_FLAG = "$threatinc_bootstrapWave";

	/**
	 * Hull units a forge colony makes: its SHIPS supply (vanilla's own figure,
	 * already cut by input shortages and raised by nanoforges), no more than
	 * the hive structurally has of it (ThreatReserves.structuralAvailable, as
	 * productionShare reads a faction's production). 0 without a working forge.
	 */
	public static float forgeOutput(MarketAPI market) {
		Industry forge = getForge(market);
		if (forge == null || forge.isDisrupted() || !forge.isFunctional()) return 0f;
		CommodityOnMarketAPI ships = market.getCommodityData(Commodities.SHIPS);
		if (ships == null) return 0f;
		return Math.max(0f, Math.min(ships.getMaxSupply(), ThreatReserves.structuralAvailable(ships)));
	}

	/** Hull units every forge of the hive makes together. */
	public static float hiveShipOutput() {
		float units = 0f;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) units += forgeOutput(market);
		return units;
	}

	/**
	 * Hull units this colony's nexus draws: its demand as far as vanilla
	 * delivers it (available, capped by the demand - no 0.25 floor, that floor
	 * is vanilla fleets built from nothing). 0 while the Fabrication Core or
	 * the Swarm Nexus is down, or no forge reaches it.
	 */
	public static float nexusDraw(MarketAPI market) {
		if (market == null) return 0f;
		if (organDown(market.getIndustry(FABRICATION_CORE)) || !hasOperationalNexus(market)) return 0f;
		CommodityOnMarketAPI ships = market.getCommodityData(Commodities.SHIPS);
		if (ships == null) return 0f;
		return Math.max(0f, Math.min(ships.getAvailable(), ships.getMaxDemand()));
	}

	/** Hull units every nexus of the hive draws together. */
	public static float hiveNexusDraw() {
		float units = 0f;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) units += nexusDraw(market);
		return units;
	}

	/**
	 * Fleet points a day this colony's nexus banks. Vanilla broadcasts one
	 * forge's output to every world that reaches it, using none of it up, so
	 * the income is the hive's real forge output shared out: the hulls the
	 * hive's forges make (hiveShipOutput) go to the nexuses in proportion to
	 * what each draws (nexusDraw), never more than a nexus draws, at
	 * fpPerShipUnit30d a unit a month. More nexuses split the same hulls;
	 * only more forges raise the total. The alarm does not raise it.
	 */
	public static float fabricationRatePerDay(MarketAPI market) {
		return fabricationRatePerDay(market, hiveShipOutput(), hiveNexusDraw());
	}

	/** fabricationRatePerDay with the hive totals already summed (once a poll). */
	protected static float fabricationRatePerDay(MarketAPI market, float hiveOutput, float hiveDraw) {
		float draw = nexusDraw(market);
		if (draw <= 0f || hiveDraw <= 0f || hiveOutput <= 0f) return 0f;
		float share = Math.min(1f, hiveOutput / hiveDraw);
		return draw * share * fpPerShipUnit30d() / 30f;
	}

	/**
	 * Banks the production since the colony was last banked, less the upkeep
	 * of its fleets over the same days (upkeepPerDay); once a poll. The bank
	 * may go below 0 on upkeep - recycleForUpkeep answers that.
	 */
	protected static void accrueFabrication(MarketAPI market, float ratePerDay, float upkeepPerDay) {
		if (market == null) return;
		String id = market.getId();
		long now = Global.getSector().getClock().getTimestamp();
		Long last = fabAccruedAt().get(id);
		fabAccruedAt().put(id, now);
		// the first sight of a colony starts its clock; it does not back-pay
		// the time before (a save's colonies are endowed once, endowSave)
		if (last == null) return;
		float days = Global.getSector().getClock().getElapsedDaysSince(last);
		if (days <= 0f) return;
		float fp = days * ratePerDay;
		if (fp > 0f) creditFP(market, fp);
		float owed = days * upkeepPerDay;
		if (owed > 0f) {
			chargeFP(market, owed);
			upkeepLog(id).charged += owed;
		}
	}

	// ------------------------------------------------------------------
	// upkeep: a standing fleet costs its bank while it exists
	// ------------------------------------------------------------------
	//
	// (2026-09-29, closed economy.) Paying for a fleet once let a rich hive's
	// garrisons pile up forever; the human side's forward-base guards pay
	// supplies to stand. Now every fleet a colony owns - its garrison, its
	// raiders, reinforcements inbound to it, and the strikes, waves and
	// scouts bound to its ledger - costs garrisonUpkeepPerMonth of its FP
	// every 30 days. A bank that cannot carry them stops building (the bank
	// gate) and recycles its weakest swarms, so a garrison settles where its
	// income meets its upkeep: income / rate.

	/** Upkeep a day on this many fleet points (threatinc_garrisonUpkeepPerMonth per 30 days). */
	public static float upkeepPerDay(float fleetFP) {
		return Math.max(0f, fleetFP) * Math.max(0f, ThreatIncConfig.garrisonUpkeepPerMonth()) / 30f;
	}

	/**
	 * Fleet points of the fleets the colony keeps near home: its garrison list
	 * (a grown fleet at its real FP), raiders it sent out, and reinforcements
	 * flying in to join it. Ledger-bound fleets out in space are ledgerFleetFP's.
	 */
	public static float ownedFleetFP(MarketAPI market, List<CampaignFleetAPI> fleets) {
		float fp = 0f;
		for (CampaignFleetAPI curr : fleets) {
			if (curr != null && curr.isAlive()) fp += curr.getFleetPoints();
		}
		String id = market.getId();
		for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(id)) {
			if (r.fleet != null && r.fleet.isAlive()) fp += r.fleet.getFleetPoints();
		}
		for (CampaignFleetAPI curr : ThreatIncData.reinforcementFleets().values()) {
			if (curr == null || !curr.isAlive()) continue;
			if (id.equals(curr.getMemoryWithoutUpdate().getString(REINFORCE_TARGET_KEY))) fp += curr.getFleetPoints();
		}
		return fp;
	}

	/**
	 * Market id -> fleet points of every live fleet in space bound to its
	 * ledger (strikes, waves, scouts, strays withdrawing): one sweep a poll.
	 * A fleet still flagged a garrison is counted by ownedFleetFP instead.
	 */
	protected static Map<String, Float> ledgerFleetFP() {
		Map<String, Float> out = new java.util.HashMap<String, Float>();
		for (com.fs.starfarer.api.campaign.LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI curr : loc.getFleets()) {
				if (!curr.isAlive()) continue;
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem = curr.getMemoryWithoutUpdate();
				String home = mem.getString(LEDGER_HOME_KEY);
				if (home == null || mem.contains(GARRISON_FLAG)) continue;
				Float had = out.get(home);
				out.put(home, (had != null ? had : 0f) + curr.getFleetPoints());
			}
		}
		return out;
	}

	/**
	 * The bank has gone below 0 and the colony's income does not cover its
	 * upkeep: it recycles standing swarms, weakest first - a weak holder
	 * (weakestWeakHolder), else the smallest on station - each crediting
	 * ThreatReturns.hullShare of its FP, until the bank is back to 0 or the
	 * upkeep left is one its income pays. It may go below the garrison floor:
	 * a floor the bank cannot carry is not one. Out of battle only; at most
	 * one pass over the garrison, so it always ends.
	 */
	protected static void recycleForUpkeep(MarketAPI market, List<CampaignFleetAPI> fleets, float income,
			float upkeep) {
		if (bankedFP(market) >= 0f || income >= upkeep) return;
		float rate = upkeepPerDay(1f);
		float share = ThreatReturns.hullShare();
		int budget = fleets.size();
		for (int i = 0; i < budget; i++) {
			if (bankedFP(market) >= 0f || income >= upkeep) return;
			CampaignFleetAPI victim = weakestWeakHolder(market);
			if (victim == null || !fleets.contains(victim)) victim = smallestOnStation(fleets);
			if (victim == null) return;
			// read before despawn: a despawned fleet reports 0 FP
			float fp = victim.getFleetPoints();
			fleets.remove(victim);
			victim.despawn();
			creditFP(market, fp * share);
			upkeep -= fp * rate;
			UpkeepLog log = upkeepLog(market.getId());
			log.recycled++;
			log.recycledFP += fp;
			ThreatIncConfig.log("Upkeep: " + market.getName() + " recycled a " + (int) fp + " FP swarm ("
					+ (int) (fp * share) + " FP back, " + (int) bankedFP(market) + " FP banked, "
					+ fleets.size() + " on station)");
		}
	}

	/** The garrison's smallest live fleet out of battle; null if none. */
	protected static CampaignFleetAPI smallestOnStation(List<CampaignFleetAPI> fleets) {
		CampaignFleetAPI best = null;
		for (CampaignFleetAPI curr : fleets) {
			if (curr == null || !curr.isAlive() || curr.getBattle() != null) continue;
			if (best == null || curr.getFleetPoints() < best.getFleetPoints()) best = curr;
		}
		return best;
	}

	/** A colony's upkeep this month, for the log (transient: a reload starts a fresh month). */
	protected static class UpkeepLog {
		float charged;
		int recycled;
		float recycledFP;
		float lastLogged = -1f;
	}

	protected static final Map<String, UpkeepLog> UPKEEP_LOG = new java.util.HashMap<String, UpkeepLog>();

	protected static UpkeepLog upkeepLog(String marketId) {
		UpkeepLog log = UPKEEP_LOG.get(marketId);
		if (log == null) {
			log = new UpkeepLog();
			UPKEEP_LOG.put(marketId, log);
		}
		return log;
	}

	/**
	 * Every 30 days: a line per colony whose month changed something - swarms
	 * recycled, or its upkeep moved a tenth or more since the line before.
	 */
	public static void flushUpkeepMonth() {
		ThreatPosture.logMonth();
		for (Map.Entry<String, UpkeepLog> entry : UPKEEP_LOG.entrySet()) {
			UpkeepLog log = entry.getValue();
			boolean moved = log.lastLogged < 0f ? log.charged > 0f
					: Math.abs(log.charged - log.lastLogged) >= 0.1f * Math.max(1f, log.lastLogged);
			if (log.recycled > 0 || moved) {
				MarketAPI market = ThreatIncData.resolveColonyMarket(entry.getKey());
				ThreatIncConfig.log("Upkeep month: " + (market != null ? market.getName() : entry.getKey())
						+ " paid " + (int) log.charged + " FP"
						+ (log.recycled > 0 ? ", recycled " + log.recycled + " swarm(s) of " + (int) log.recycledFP
								+ " FP" : "")
						+ " (" + (int) fpBank(entry.getKey()) + " FP banked)");
				log.lastLogged = log.charged;
			}
			log.charged = 0f;
			log.recycled = 0;
			log.recycledFP = 0f;
		}
	}

	/**
	 * The hive's fleet economy in one line for the census: standing fleet FP
	 * (garrisons, raiders, inbound reinforcements, ledger-bound fleets), and
	 * its income and upkeep a month.
	 */
	public static String hiveLedgerSummary() {
		float hiveOutput = hiveShipOutput();
		float hiveDraw = hiveNexusDraw();
		Map<String, Float> ledgerFP = ledgerFleetFP();
		float fleetFP = 0f, income = 0f, banked = 0f;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			fleetFP += ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId()));
			Float away = ledgerFP.get(market.getId());
			if (away != null) fleetFP += away;
			income += fabricationRatePerDay(market, hiveOutput, hiveDraw) * 30f;
			banked += bankedFP(market);
		}
		return "fleets " + (int) fleetFP + " FP, income " + (int) income + " FP/mo, upkeep "
				+ (int) (upkeepPerDay(fleetFP) * 30f) + " FP/mo, banked " + (int) banked + " FP";
	}

	/**
	 * The first poll of a save from before the ledger: every colony starts
	 * with FAB_ENDOWMENT_DAYS of what it makes, read at the first poll that
	 * rate is known (KEY_FAB_ENDOW_PENDING), so a loaded war does not
	 * stall while the banks fill. Once per save; a new game has no colonies
	 * yet and endows nothing (its seeds are endowed on landing, endowSeed).
	 */
	protected static void endowSave(float hiveOutput, float hiveDraw) {
		Map<String, Boolean> pending = ThreatIncData.map(KEY_FAB_ENDOW_PENDING);
		if (!Boolean.TRUE.equals(Global.getSector().getPersistentData().get(KEY_FAB_INIT))) {
			Global.getSector().getPersistentData().put(KEY_FAB_INIT, true);
			for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) pending.put(market.getId(), true);
		}
		if (pending.isEmpty()) return;
		// each colony is endowed at the first poll its rate reads above 0, not
		// at the first poll after the load: vanilla's SHIPS figures an idle
		// nexus reads may be empty until its industries apply again, and an
		// endowment of 180 days of nothing was 0 FP (2026-09-29 review). A
		// colony whose organs never come up is never endowed; one that dies,
		// forgotten
		float total = 0f;
		int endowed = 0;
		for (String id : new ArrayList<String>(pending.keySet())) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(id);
			if (market == null) {
				pending.remove(id);
				continue;
			}
			float rate = fabricationRatePerDay(market, hiveOutput, hiveDraw);
			if (rate <= 0f) continue;
			pending.remove(id);
			float fp = FAB_ENDOWMENT_DAYS * rate;
			creditFP(market, fp);
			total += fp;
			endowed++;
		}
		if (endowed > 0) {
			ThreatIncConfig.log("Fabrication ledger opened for " + endowed + " colony(ies): " + (int) total
					+ " FP endowed" + (pending.isEmpty() ? "" : ", " + pending.size() + " still waiting on a rate"));
		}
	}

	/**
	 * A colony founded by a Seeding Swarm out of the Abyss (the war's opening,
	 * source null) lands with FAB_ENDOWMENT_DAYS of a full nexus's income at
	 * its size - what the swarm brought with it, not production.
	 */
	protected static void endowSeed(MarketAPI market) {
		float fp = FAB_ENDOWMENT_DAYS * market.getSize() * fpPerShipUnit30d() / 30f;
		creditFP(market, fp);
		ThreatIncConfig.log("Seed endowed at " + market.getName() + ": " + (int) fp + " FP");
	}

	/** The colony's ledger is gone with it. */
	public static void clearFabrication(String marketId) {
		fabBank().remove(marketId);
		fabAccruedAt().remove(marketId);
	}

	/**
	 * What a swarm of this spec ({fabricators, escort tier}) costs: the mean of
	 * what such swarms have come out at, or before the first, the simulated
	 * vanilla strengths (docs/fleet-archetypes.md: 46 / 134 / 347 / 458 FP at
	 * LOW / MEDIUM / HIGH / MAXIMUM) plus 40 a fabricator.
	 */
	public static float swarmCostEstimate(int[] spec) {
		return swarmCostEstimate(ThreatFleetComposer.JOB_GARRISON, spec);
	}

	/**
	 * swarmCostEstimate for a fleet of this job (ThreatFleetComposer.JOB_*):
	 * each job is composed from its own archetypes, so each keeps its own
	 * mean; one not yet learned reads the garrison's, then the table.
	 */
	public static float swarmCostEstimate(String job, int[] spec) {
		Float learned = fabCosts().get(costKey(job, spec));
		if (learned != null && learned > 0f) return learned;
		if (!ThreatFleetComposer.JOB_GARRISON.equals(job)) {
			learned = fabCosts().get(costKey(ThreatFleetComposer.JOB_GARRISON, spec));
			if (learned != null && learned > 0f) return learned;
		}
		float[] byTier = { 46f, 134f, 347f, 458f };
		float base = byTier[Math.max(0, Math.min(byTier.length - 1, spec[1]))];
		return base + 40f * Math.max(0, spec[0]);
	}

	/**
	 * The fabCosts key: "tier:fabricators" for a garrison swarm - the key every
	 * cost was learned under before jobs kept their own, so a save's learned
	 * garrison costs carry over - and "job/tier:fabricators" for the rest.
	 */
	protected static String costKey(String job, int[] spec) {
		String key = spec[1] + ":" + spec[0];
		return job == null || ThreatFleetComposer.JOB_GARRISON.equals(job) ? key : job + "/" + key;
	}

	/** Folds a fabricated garrison swarm's real cost into its spec's mean (a quarter weight). */
	protected static void learnSwarmCost(int[] spec, float fp) {
		learnSwarmCost(ThreatFleetComposer.JOB_GARRISON, spec, fp);
	}

	/** Folds a fleet of this job's real cost into its job and spec's mean (a quarter weight). */
	public static void learnSwarmCost(String job, int[] spec, float fp) {
		if (fp <= 0f) return;
		String key = costKey(job, spec);
		Float had = fabCosts().get(key);
		fabCosts().put(key, had == null || had <= 0f ? fp : had + 0.25f * (fp - had));
	}

	/**
	 * Fabricates one Defense Swarm of the given garrison-table slot
	 * ({numFabricators, escort-strength ordinal}) and parks it in orbit over
	 * the colony; null if the fleet could not be built. The caller adds it to
	 * the colony's garrison list and stamps the spawn time.
	 */
	protected static CampaignFleetAPI fabricateGarrisonSwarm(MarketAPI market, int[] spec, Random random) {
		CampaignFleetAPI fleet = buildGarrisonSwarm(market, spec, random);
		if (fleet != null) placeGarrisonSwarm(market, fleet, random);
		return fleet;
	}

	/** fabricateGarrisonSwarm's fleet, built and tagged but not yet in space (placeGarrisonSwarm, or growGarrisonFleet). */
	protected static CampaignFleetAPI buildGarrisonSwarm(MarketAPI market, int[] spec, Random random) {
		if (market.getPrimaryEntity() == null || market.getStarSystem() == null) return null;
		CampaignFleetAPI fleet = ThreatFleetComposer.create(ThreatFleetComposer.JOB_GARRISON,
				spec[0], FabricatorEscortStrength.values()[spec[1]], random);
		if (fleet == null) return null;
		fleet.setName("Defense Swarm");
		fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, market.getId());
		// remember what this swarm IS, so an expedition mustered from it
		// re-embodies the same fleet - not an FP-estimated bigger one
		fleet.getMemoryWithoutUpdate().set(SWARM_TIER_KEY, spec[1]);
		fleet.getMemoryWithoutUpdate().set(SWARM_FABS_KEY, spec[0]);
		// ...and how strong it was born, so battle damage can be measured
		// against it (isUnderStrength)
		fleet.getMemoryWithoutUpdate().set(SWARM_SPAWN_FP, fleet.getFleetPoints());
		makeDetectable(fleet);
		return fleet;
	}

	/** Parks a built swarm in orbit over its colony (buildGarrisonSwarm checked the planet and system). */
	protected static void placeGarrisonSwarm(MarketAPI market, CampaignFleetAPI fleet, Random random) {
		SectorEntityToken planet = market.getPrimaryEntity();
		market.getStarSystem().addEntity(fleet);
		Vector2f loc = Misc.getPointAtRadius(planet.getLocation(),
				planet.getRadius() + 400f + random.nextFloat() * 300f);
		fleet.setLocation(loc.x, loc.y);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
	}

	/** The engine's ship limit for an AI fleet (settings maxShipsInAIFleet, vanilla's 30). */
	public static int maxShipsPerFleet() {
		return Global.getSettings().getInt("maxShipsInAIFleet");
	}

	/** The fabricator count a swarm was fabricated with (0 for untagged legacy swarms). */
	protected static int swarmFabs(CampaignFleetAPI swarm) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = swarm.getMemoryWithoutUpdate();
		return mem.contains(SWARM_FABS_KEY) ? mem.getInt(SWARM_FABS_KEY) : 0;
	}

	/**
	 * The standing garrison fleet a freshly built swarm of this spec joins:
	 * the fullest of its spec on station, out of battle and at strength, with
	 * room for the swarm's ships under maxShipsInAIFleet; null if none has
	 * room. Same spec only, so a grown fleet musters as that many swarms of
	 * it (musterFrom) and its tier still means what it says.
	 */
	protected static CampaignFleetAPI garrisonHostFor(List<CampaignFleetAPI> fleets, SectorEntityToken planet,
			int[] spec, CampaignFleetAPI swarm) {
		int room = maxShipsPerFleet() - swarm.getFleetData().getNumMembers();
		CampaignFleetAPI best = null;
		for (CampaignFleetAPI curr : fleets) {
			if (curr == null || !curr.isAlive() || curr.getBattle() != null) continue;
			if (swarmTier(curr) != spec[1] || swarmFabs(curr) != spec[0]) continue;
			// battle damage is the recycler's to see, not to be topped up out of sight
			if (isUnderStrength(curr)) continue;
			// hulls join a fleet in orbit, not one off chasing something
			if (curr.getContainingLocation() != planet.getContainingLocation()
					|| Misc.getDistance(curr, planet) > GARRISON_LEASH_RADIUS) continue;
			int ships = curr.getFleetData().getNumMembers();
			if (ships > room) continue;
			if (best == null || ships > best.getFleetData().getNumMembers()) best = curr;
		}
		return best;
	}

	/**
	 * Moves a built, unplaced swarm's ships into a standing garrison fleet of
	 * its spec (garrisonHostFor): the fleet now embodies one swarm more
	 * (SWARM_COUNT_KEY) and was born that much stronger (SWARM_SPAWN_FP). The
	 * bank paid for the swarm as for any other; nothing is added or lost.
	 */
	protected static void growGarrisonFleet(CampaignFleetAPI host, CampaignFleetAPI swarm) {
		float fp = swarm.getFleetPoints();
		mergeInto(host, swarm);
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = host.getMemoryWithoutUpdate();
		mem.set(SWARM_COUNT_KEY, swarmCount(host) + 1);
		if (mem.contains(SWARM_SPAWN_FP)) mem.set(SWARM_SPAWN_FP, mem.getFloat(SWARM_SPAWN_FP) + fp);
	}

	/**
	 * Moves every ship of a built fleet that never took to space into host
	 * (the garrison's growth, a strike's packed fleets). The emptied fleet was
	 * never added to a location and is simply dropped.
	 */
	public static void mergeInto(CampaignFleetAPI host, CampaignFleetAPI from) {
		for (com.fs.starfarer.api.fleet.FleetMemberAPI m : from.getFleetData().getMembersListCopy()) {
			from.getFleetData().removeFleetMember(m);
			host.getFleetData().addFleetMember(m);
		}
		host.getFleetData().setSyncNeeded();
		host.getFleetData().syncIfNeeded();
		host.getFleetData().sort();
	}

	/**
	 * Debug (ThreatDebugWar): stands the colony's whole nominal garrison up at
	 * once - every slot its size table calls for, the respawn cadence and the
	 * hull economy's cap skipped. Returns how many swarms it fabricated.
	 */
	public static int fillGarrisonNow(MarketAPI market, Random random) {
		if (market == null) return 0;
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(market.getId());
		int[][] table = desiredGarrison(market.getSize());
		int made = 0;
		while (fleets.size() < table.length) {
			CampaignFleetAPI fleet = fabricateGarrisonSwarm(market, table[fleets.size()], random);
			if (fleet == null) break;
			fleets.add(fleet);
			made++;
		}
		return made;
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
		// raiders out hold their slots here too, or a sibling filled the slot and the
		// raider came home to a full garrison and was recycled
		return countLiveGarrison(market.getId()) + swarmsAway(market.getId());
	}

	/**
	 * Defense Swarms out of orbit that are coming back to this colony: its
	 * raiders on a hunt and reinforcements inbound. They hold their slots, so
	 * the nexus never refills a slot a swarm is coming home to - refilled, the
	 * returning swarm had its hulls merged into the standing swarms, ~7,000 FP of
	 * them into Tetra's garrison in one session, inflating every siege and hunt
	 * gate (rc1 review).
	 */
	public static int swarmsAway(String marketId) {
		int n = inboundReinforcements(marketId);
		for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(marketId)) {
			if (r.fleet != null && r.fleet.isAlive()) n++;
		}
		return n;
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
	 * lowest garrison fill ratio (swarms on station plus inbound, over the
	 * NOMINAL garrison its size calls for) that is below strength, then the
	 * best donor that can reach it: same-system first, then the highest fill
	 * ratio, then the nearest. The ratio deliberately runs against the nominal
	 * table, NOT desiredGarrisonCount's hull-shortage cap. That cap bounds what
	 * a colony's own nexus can BUILD (so launch gates never wait for hulls that
	 * will not come), but a sibling's already-built swarm needs none of the
	 * receiver's hulls to fly over and defend it - so a "1/2 of 4" world, its
	 * own fabrication capped at 2 by a hull shortage, is still reinforced to 4.
	 * A hull-starved DONOR is likewise measured against its nominal, so a world
	 * sitting at its own build cap does not read as fully covered and bleed
	 * itself out to a sibling. One
	 * swarm is dispatched per pairing, pairing after pairing until none is
	 * left; every one of them flies there and can be intercepted.
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
		// under posture each colony's want replaces the size table as what a
		// garrison is filled toward (ThreatPosture): the nominal pass would
		// shuffle swarms toward tables no colony is meant to reach
		if (ThreatPosture.enabled()) redistributeByPressure(colonies);
		else redistributeNominal(colonies);
	}

	/** redistributeGarrisons' pass toward the nominal size tables. */
	protected static void redistributeNominal(List<MarketAPI> colonies) {
		// every pairing there is, each poll (2026-09-29: reinforceMaxPerPoll, 2,
		// is gone). The strict donor test is what ends the loop; the bound
		// below is only a guard against a bug looping it forever - no knob:
		// every colony handing on every swarm the hive has
		int swarms = 0;
		for (MarketAPI curr : colonies) swarms += effectiveGarrison(curr);
		int guard = colonies.size() * (swarms + 1);
		for (int n = 0; ; n++) {
			if (n >= guard) {
				ThreatIncConfig.log("Reinforcement: stopped after " + n
						+ " dispatches in one poll (loop guard) - the balance did not converge");
				return;
			}
			// the receivers: colonies below strength, lowest fill ratio first; the
			// neediest one a donor can serve is served (one no donor can reach no
			// longer stops the rest)
			List<MarketAPI> receivers = new ArrayList<MarketAPI>();
			final Map<String, Float> ratios = new java.util.HashMap<String, Float>();
			for (MarketAPI curr : colonies) {
				if (curr.getPrimaryEntity() == null) continue;
				int desired = nominalGarrison(curr);
				if (desired <= 0) continue;
				int eff = effectiveGarrison(curr);
				if (eff >= desired) continue;
				receivers.add(curr);
				ratios.put(curr.getId(), eff / (float) desired);
			}
			if (receivers.isEmpty()) return;
			java.util.Collections.sort(receivers, new java.util.Comparator<MarketAPI>() {
				public int compare(MarketAPI a, MarketAPI b) {
					return Float.compare(ratios.get(a.getId()), ratios.get(b.getId()));
				}
			});

			boolean dispatched = false;
			for (MarketAPI receiver : receivers) {
				// the weight of fleet this slot wants: only a swarm that can genuinely
				// hold it is worth sending - a tiny colony's swarm would just park in
				// a big colony's slot and block the real thing
				int needTier = nextSlotTier(receiver);
				MarketAPI donor = pickDonor(colonies, receiver, needTier);
				if (donor != null && dispatchReinforcement(donor, receiver, needTier)) {
					dispatched = true;
					break;
				}
			}
			if (!dispatched) return;
		}
	}

	/**
	 * The pressure pass (ThreatPosture): a colony holding less than its want
	 * (inbound included), the neediest first, draws whole fleets from siblings
	 * holding more than their own want by the band and a swarm - and, for a
	 * colony of a system under real attack (ThreatPosture.underAttack), from a
	 * quiet system's colonies down to their reserve - same system first, then
	 * the most to spare, then the nearest. A fleet goes only if the donor's
	 * surplus covers it whole and it leaves the receiver no surplus to send
	 * back: the largest that fits the deficit, else the smallest. The held
	 * figures are kept here as fleets leave, and every dispatch takes a fleet
	 * off a garrison, so the loop ends within the hive's fleet count.
	 *
	 * <p>No circuits (ti8c: 89 fleets sent in a month, round Sun Wukong - Zeta
	 * Ranau II - Gamma Shevar II and back): a donor gives only from what is on
	 * station, never while below its own want nor within DECAY_DAYS of being
	 * sent a transfer, and a fleet sent is not sent again for as long.
	 */
	protected static void redistributeByPressure(List<MarketAPI> colonies) {
		if (!ThreatPosture.enabled()) return;
		final Map<String, Float> held = new java.util.HashMap<String, Float>();
		final Map<String, Float> inbound = new java.util.HashMap<String, Float>();
		int fleetsTotal = 0;
		for (MarketAPI curr : colonies) {
			held.put(curr.getId(), ownedFleetFP(curr, ThreatIncData.garrisonsFor(curr.getId())));
			inbound.put(curr.getId(), ThreatPosture.inboundFP(curr.getId()));
			fleetsTotal += countLiveGarrison(curr.getId());
		}
		float band = Math.max(0f, ThreatIncConfig.postureBand());
		for (int n = 0; n <= fleetsTotal; n++) {
			List<MarketAPI> receivers = new ArrayList<MarketAPI>();
			final Map<String, Float> shortfall = new java.util.HashMap<String, Float>();
			for (MarketAPI curr : colonies) {
				if (curr.getPrimaryEntity() == null) continue;
				float want = ThreatPosture.wantFP(curr);
				if (want <= 0f) continue;
				float deficit = want - held.get(curr.getId());
				if (deficit <= 0f) continue;
				receivers.add(curr);
				shortfall.put(curr.getId(), deficit / want);
			}
			if (receivers.isEmpty()) return;
			java.util.Collections.sort(receivers, new java.util.Comparator<MarketAPI>() {
				public int compare(MarketAPI a, MarketAPI b) {
					return Float.compare(shortfall.get(b.getId()), shortfall.get(a.getId()));
				}
			});

			boolean dispatched = false;
			for (MarketAPI receiver : receivers) {
				float want = ThreatPosture.wantFP(receiver);
				float deficit = want - held.get(receiver.getId());
				float accept = deficit + want * band + ThreatPosture.oneSwarmFP(receiver);
				// consolidating (ThreatStance), a merely THREATENED system is fed too
				boolean attacked = ThreatPosture.underAttack(receiver.getStarSystem())
						|| (ThreatStance.feedsPressed() && ThreatPosture.pressed(receiver.getStarSystem()));
				MarketAPI donor = null;
				CampaignFleetAPI pick = null;
				boolean donorSame = false;
				float donorSpare = 0f, donorDist = Float.MAX_VALUE;
				for (MarketAPI curr : colonies) {
					if (curr == receiver || curr.getPrimaryEntity() == null) continue;
					if (!canRebuildGarrison(curr)) continue;
					if (countLiveGarrison(curr.getId()) < 2) continue;
					if (ThreatPosture.recentlyReceived(curr)) continue;
					// what is on station, not what is flying in
					float onStation = held.get(curr.getId()) - inbound.get(curr.getId());
					if (onStation < ThreatPosture.wantFP(curr)) continue;
					float spare = ThreatPosture.releasableFP(curr, onStation);
					// a quiet system thins to its colonies' reserves for one under attack
					if (attacked && !ThreatPosture.pressed(curr.getStarSystem())) {
						spare = Math.max(spare, ThreatPosture.thinnableFP(curr, onStation));
					}
					if (spare <= 0f) continue;
					if (!canReinforce(curr, receiver)) continue;
					CampaignFleetAPI fleet = pressureFleet(curr, Math.min(spare, accept), deficit);
					if (fleet == null) continue;
					boolean same = curr.getStarSystem() == receiver.getStarSystem();
					float dist = same ? 0f : Misc.getDistanceLY(curr.getStarSystem().getLocation(),
							receiver.getStarSystem().getLocation());
					// the way there comes from the hive's fuel (ThreatFuel)
					if (!ThreatFuel.canPay(ThreatFuel.passage(fleet.getFleetPoints(), dist, false))) continue;
					boolean better;
					if (donor == null) better = true;
					else if (same != donorSame) better = same;
					else if (spare != donorSpare) better = spare > donorSpare;
					else better = dist < donorDist;
					if (better) {
						donor = curr;
						pick = fleet;
						donorSame = same;
						donorSpare = spare;
						donorDist = dist;
					}
				}
				if (donor == null) {
					// no fleet to spare anywhere in reach: a colony whose bank lies
					// idle past its own want builds a swarm for the receiver instead
					donor = fabricatorFor(colonies, receiver, held);
					if (donor == null) continue;
					pick = fabricateFor(donor, receiver);
					if (pick == null) continue;
					held.put(donor.getId(), held.get(donor.getId()) + pick.getFleetPoints());
				}
				// read before it leaves: the garrison list no longer holds it after
				float fp = pick.getFleetPoints();
				if (!sendReinforcement(donor, receiver, pick)) continue;
				held.put(donor.getId(), held.get(donor.getId()) - fp);
				held.put(receiver.getId(), held.get(receiver.getId()) + fp);
				inbound.put(receiver.getId(), inbound.get(receiver.getId()) + fp);
				ThreatPosture.noteTransfer(receiver, pick);
				ThreatPosture.noteSent(fp);
				ThreatIncConfig.log("Posture: " + donor.getName() + " sent " + (int) fp + " FP to "
						+ receiver.getName() + " (" + held.get(receiver.getId()).intValue() + " of "
						+ (int) want + " FP wanted)");
				dispatched = true;
				break;
			}
			if (!dispatched) return;
		}
	}

	/**
	 * A colony that can build a swarm for a receiver no garrison can spare one
	 * for: it holds its own want, can fabricate and reach the receiver, and its
	 * bank holds one rebuild of its own want plus the swarm. The richest such
	 * bank goes first, same-system first. (2026-09-29, ti-h8f: 60-80k FP lay
	 * banked at colonies at their want - a bank past the want is spent only by
	 * the forges' waves and strikes - while pressed systems held 5-10k short.)
	 */
	protected static MarketAPI fabricatorFor(List<MarketAPI> colonies, MarketAPI receiver, Map<String, Float> held) {
		int[] spec = cheapestRow(receiver);
		if (spec == null) return null;
		float cost = swarmCostEstimate(spec);
		MarketAPI best = null;
		boolean bestSame = false;
		float bestIdle = 0f;
		for (MarketAPI curr : colonies) {
			if (curr == receiver || curr.getPrimaryEntity() == null || curr.getStarSystem() == null) continue;
			if (!canRebuildGarrison(curr)) continue;
			float want = ThreatPosture.wantFP(curr);
			Float h = held.get(curr.getId());
			if (h == null || h < want) continue;
			float idle = bankedFP(curr) - want - cost;
			if (idle < 0f) continue;
			if (!canReinforce(curr, receiver)) continue;
			boolean same = curr.getStarSystem() == receiver.getStarSystem();
			// a swarm the hive cannot fuel to the receiver is not built for it
			if (!ThreatFuel.canPay(ThreatFuel.passage(cost,
					ThreatFuel.ly(curr.getStarSystem(), receiver.getStarSystem()), false))) continue;
			boolean better = best == null || (same != bestSame ? same : idle > bestIdle);
			if (better) {
				best = curr;
				bestSame = same;
				bestIdle = idle;
			}
		}
		return best;
	}

	/** The receiver's cheapest garrison row (its table's lowest tier), or null. */
	protected static int[] cheapestRow(MarketAPI market) {
		int[][] table = desiredGarrison(market.getSize());
		int[] best = null;
		for (int[] row : table) {
			if (best == null || swarmCostEstimate(row) < swarmCostEstimate(best)) best = row;
		}
		return best;
	}

	/** Builds the receiver's cheapest swarm at the fabricator, paid from its bank, into its garrison to be sent on. */
	protected static CampaignFleetAPI fabricateFor(MarketAPI fabricator, MarketAPI receiver) {
		int[] spec = cheapestRow(receiver);
		if (spec == null) return null;
		Random random = new Random();
		CampaignFleetAPI fleet = buildGarrisonSwarm(fabricator, spec, random);
		if (fleet == null) return null;
		float cost = fleet.getFleetPoints();
		chargeFP(fabricator, cost);
		learnSwarmCost(spec, cost);
		placeGarrisonSwarm(fabricator, fleet, random);
		ThreatIncData.garrisonsFor(fabricator.getId()).add(fleet);
		ThreatIncConfig.log("Posture: " + fabricator.getName() + " fabricated a " + (int) cost + " FP swarm for "
				+ receiver.getName() + " (" + (int) bankedFP(fabricator) + " FP banked)");
		return fleet;
	}

	/**
	 * The donor's fleet for the pressure pass: out of battle, no more than
	 * {@code max} FP - the largest within the deficit, else the smallest.
	 */
	protected static CampaignFleetAPI pressureFleet(MarketAPI donor, float max, float deficit) {
		CampaignFleetAPI fits = null, smallest = null;
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(donor.getId())) {
			if (curr == null || !curr.isAlive() || curr.getBattle() != null) continue;
			if (ThreatPosture.recentlyMoved(curr)) continue;
			float fp = curr.getFleetPoints();
			if (fp <= 0f || fp > max) continue;
			if (fp <= deficit && (fits == null || fp > fits.getFleetPoints())) fits = curr;
			if (smallest == null || fp < smallest.getFleetPoints()) smallest = curr;
		}
		return fits != null ? fits : smallest;
	}

	/**
	 * The donor for a receiver: can reach, can regrow, keeps one home, fields a
	 * swarm heavy enough for the slot, and stays at least as covered as the
	 * receiver after giving one up. Same-system first, then the best covered,
	 * then the nearest; null if none.
	 */
	protected static MarketAPI pickDonor(List<MarketAPI> colonies, MarketAPI receiver, int needTier) {
		int rEff = effectiveGarrison(receiver);
		int rDesired = nominalGarrison(receiver);
		MarketAPI donor = null;
		boolean donorSameSystem = false;
		float donorRatio = -1f;
		float donorDist = Float.MAX_VALUE;
		for (MarketAPI curr : colonies) {
			if (curr == receiver || curr.getPrimaryEntity() == null) continue;
			if (!canRebuildGarrison(curr)) continue;
			if (countLiveGarrison(curr.getId()) < 2) continue;
			if (!hasSwarmOfTier(curr, needTier)) continue;
			int dDesired = nominalGarrison(curr);
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
		return donor;
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
		return sendReinforcement(source, target, smallestOfTier(source, minTier));
	}

	/** The colony's smallest live garrison fleet of at least this tier; null if none. */
	protected static CampaignFleetAPI smallestOfTier(MarketAPI market, int minTier) {
		CampaignFleetAPI pick = null;
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr == null || !curr.isAlive()) continue;
			if (swarmTier(curr) < minTier) continue;
			if (pick == null || curr.getFleetPoints() < pick.getFleetPoints()) pick = curr;
		}
		return pick;
	}

	/** dispatchReinforcement with the fleet chosen: it leaves source's garrison and flies to target. */
	protected static boolean sendReinforcement(MarketAPI source, MarketAPI target, CampaignFleetAPI pick) {
		SectorEntityToken planet = target.getPrimaryEntity();
		if (planet == null || pick == null) return false;
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(source.getId());
		// the way there comes from the hive's fuel (ThreatFuel): a swarm it cannot
		// fuel stays home
		float fuel = ThreatFuel.passage(pick.getFleetPoints(),
				ThreatFuel.ly(source.getStarSystem(), target.getStarSystem()), false);
		if (!ThreatFuel.canPay(fuel)) {
			ThreatFuel.held("a reinforcement from " + source.getName());
			return false;
		}
		if (!fleets.remove(pick)) return false;
		ThreatFuel.pay(fuel);

		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = pick.getMemoryWithoutUpdate();
		mem.unset(GARRISON_FLAG);
		mem.set(REINFORCE_TARGET_KEY, target.getId());
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		makeDetectable(pick);

		pick.clearAssignments();
		// no hive's name on the fleet: under the fog it may be one nobody has found
		pick.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 365f,
				"reinforcing the hive");
		// fallback so the fleet doesn't wander if arrival detection ever misses
		pick.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
		ThreatIncData.reinforcementFleets().put(pick.getId(), pick);

		// the source regrows what it sent from its own production (the
		// fabrication ledger); the move itself cost its fuel
		ThreatIncConfig.log("Reinforcement: Defense Swarm " + source.getName() + " -> "
				+ target.getName() + " (" + countLiveGarrison(source.getId())
				+ " remain at source; " + effectiveGarrison(target) + "/"
				+ nominalGarrison(target) + " covered at destination)");
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
				// (2026-09-29: closed economy - disbanded, its hulls go to the
				// nearest live colony's bank rather than vanish; read before
				// despawn: a despawned fleet reports 0 FP)
				float fp = fleet.getFleetPoints();
				String to = creditHome(null, fp, fleet);
				fleet.despawn();
				ThreatIncConfig.log("Reinforcement disbanded, its colony gone: " + (int) fp + " FP "
						+ (to != null ? "re-banked at " + to : "lost, no hive left"));
				continue;
			}
			SectorEntityToken planet = target.getPrimaryEntity();
			boolean arrived = fleet.getContainingLocation() == planet.getContainingLocation()
					&& Misc.getDistance(fleet, planet) <= GARRISON_LEASH_RADIUS;
			if (!arrived) continue;

			if (fleet.getBattle() != null) continue;

			inTransit.remove(fleetId);
			mem.unset(REINFORCE_TARGET_KEY);
			// always seated: a garrison has no ceiling to be over (2026-09-29,
			// absorbSurplus despawned a swarm arriving at a full table)
			mem.set(GARRISON_FLAG, targetId);
			// blinders off: on station, hunting reflexes back on (as the leash does)
			mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
			ThreatIncData.garrisonsFor(targetId).add(fleet);
			ThreatIncConfig.log("Reinforcement arrived at " + target.getName() + " ("
					+ countLiveGarrison(targetId) + "/" + nominalGarrison(target) + ")");
		}
	}

	/**
	 * A strike's landing fleet over the world it just took (ThreatSwarmDefend,
	 * the front gone): it digs in as the new hive's first garrison rather than
	 * flying home (2026-09-29, ti-h8d: Asharu was seeded in Corvus with no
	 * swarm over it while its conquerors flew away, and razed by 275 FP days
	 * later). Its hulls leave the source's ledger for the garrison - the upkeep
	 * now charges them where they stand. False when no live hive of the swarm's
	 * lies under the fleet.
	 */
	public static boolean digInAtConquest(CampaignFleetAPI fleet) {
		if (fleet == null || !fleet.isAlive() || fleet.getBattle() != null) return false;
		if (!(fleet.getContainingLocation() instanceof StarSystemAPI)) return false;
		MarketAPI hive = null;
		float best = GARRISON_LEASH_RADIUS * 2f;
		for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(fleet.getContainingLocation().getId())) {
			if (m.getPrimaryEntity() == null) continue;
			float d = Misc.getDistance(fleet, m.getPrimaryEntity());
			if (d <= best) {
				best = d;
				hive = m;
			}
		}
		if (hive == null) return false;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		unbindLedger(fleet);
		mem.unset(Misc.FLEET_RETURNING_TO_DESPAWN);
		mem.set(GARRISON_FLAG, hive.getId());
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS);
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(), 1000000f);
		ThreatIncData.garrisonsFor(hive.getId()).add(fleet);
		ThreatIncConfig.log("Conquest garrison: " + fleet.getName() + " (" + fleet.getFleetPoints()
				+ " FP) digs in over " + hive.getName() + " (" + countLiveGarrison(hive.getId()) + "/"
				+ nominalGarrison(hive) + ")");
		return true;
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
		for (String marketId : ThreatIncData.colonyIdsIn(systemId)) {
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
					// (2026-09-29: closed economy) the survivors withdraw: their
					// hulls go to the nearest live colony's bank rather than vanish
					// with the colony - bound to it, credited when they leave the
					// sector (2026-09-29 review: credited up front, a withdrawing
					// swarm shot down on its way out had already been paid back)
					if (curr != null && curr.isAlive() && !curr.isExpired()) {
						String to = bindStrayToNearest(curr);
						ThreatIncConfig.log("Garrison of a dead colony withdraws: " + (int) curr.getFleetPoints()
								+ " FP " + (to != null ? "bound for the bank at " + to : "lost, no hive left"));
					}
					retireFleet(curr, system);
				}
				ThreatIncData.garrisons().remove(marketId);
				clearFabrication(marketId);
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
				announce(ThreatNotice.titled("System Cleansed").good()
						.line("The last Threat colony in %s has been burned away", name)
						.line("Every surviving hive feels the loss"));
				ThreatIncConfig.log("System cleansed: " + name);
			} else {
				announce(ThreatNotice.titled("Hive Burned").good()
						.line("A Threat colony in %s has been burned away", name)
						.line("The swarm still holds other worlds there"));
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

			for (String marketId : new ArrayList<String>(ThreatIncData.colonyIdsIn(systemId))) {
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
		// the fabrication ledger (what each nexus banked; the learned costs stay
		// true of the fleets and are kept)
		Global.getSector().getPersistentData().remove(KEY_FAB_BANK);
		Global.getSector().getPersistentData().remove(KEY_FAB_ACCRUED_AT);

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
		// both sides' scouting starts over with the war: parties out fade, charts and leads go
		ThreatScouts.reset();
		ThreatSwarmScouts.reset();
		ThreatOmens.reset();
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

		announce(ThreatNotice.titled("Abyssal War Reset")
				.line("The swarm will return as if for the first time"));
		ThreatIncConfig.log("Abyssal War fully reset.");
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
			converted++;
		}
		ThreatIncData.hives().clear();

		if (converted > 0) {
			announce(ThreatNotice.titled("Hives Consolidated").bad()
					.line("%s hive fleets have dug into the planets below", converted));
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
	 * commentary for playtesting. Always-shown notices call
	 * {@link ThreatNotice#send} themselves.
	 */
	public static void announce(ThreatNotice notice) {
		if (!ThreatIncConfig.debugMode()) return;
		notice.send();
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
		for (String marketId : new ArrayList<String>(ThreatIncData.colonyIdsIn(systemId))) {
			for (CampaignFleetAPI curr : new ArrayList<CampaignFleetAPI>(
					ThreatIncData.garrisonsFor(marketId))) {
				if (curr != null && curr.isAlive()) curr.despawn();
			}
			clearFabrication(marketId);
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
