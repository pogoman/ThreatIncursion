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
	/** The day (ThreatPosture.today) an in-transit reinforcement left; overdue reads it. */
	public static final String REINFORCE_DAY_KEY = "$threatinc_reinforceDay";
	/** The day an overdue reinforcement was last ordered on again. */
	protected static final String REINFORCE_KICK_KEY = "$threatinc_reinforceKick";
	/** How many times an overdue reinforcement has been set on its way. */
	protected static final String REINFORCE_KICKS_KEY = "$threatinc_reinforceKicks";
	/** Where a reinforcement stood when it was sent (the overdue read's "moved"). */
	protected static final String REINFORCE_AT_KEY = "$threatinc_reinforceAt";
	/** The hive a reinforcement was sent from (the overdue read's "from"). */
	protected static final String REINFORCE_FROM_KEY = "$threatinc_reinforceFrom";
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
	 * population_10 and no more.
	 */
	public static final int HIVE_MAX_SIZE = 10;

	/**
	 * How big a hive may grow: colonyMaxSize (8), within the engine's
	 * HIVE_MAX_SIZE. The knob was dropped for the engine's ceiling on
	 * 2026-09-29 and restored on 2026-09-30 (user's call): at month 62 of
	 * h26a, 27 of the Threat's ~46 worlds were size 10, and razing one cost
	 * ~1M fuel.
	 */
	public static int hiveMaxSize() {
		return Math.max(1, Math.min(HIVE_MAX_SIZE, ThreatIncConfig.colonyMaxSize()));
	}

	/**
	 * Sets a hive's vanilla max market size (Misc.MAX_COLONY_SIZE, the player's
	 * 6) to {@link #hiveMaxSize}. Re-pinned every poll (updateColonyVitality),
	 * and a hive grown past it under an older rule loses a size a poll until it
	 * is back under it.
	 */
	public static void pinMaxSize(MarketAPI market) {
		if (market == null) return;
		int cap = hiveMaxSize();
		com.fs.starfarer.api.combat.StatBonus mod = market.getStats().getDynamic().getMod(Stats.MAX_MARKET_SIZE);
		if (cap != Misc.MAX_COLONY_SIZE) {
			mod.modifyFlat("threatinc", cap - Misc.MAX_COLONY_SIZE);
		} else {
			mod.unmodifyFlat("threatinc");
		}
		if (market.getSize() > cap) {
			ThreatFrontlines.shrink(market);
			ThreatIncConfig.log("Hive " + market.getName() + " shrinks to size " + market.getSize()
					+ ", over the hive size cap of " + cap);
		}
	}

	/** How big this hive can grow: vanilla's max market size, never past {@link #hiveMaxSize}. */
	public static int maxColonySize(MarketAPI market) {
		if (market == null) return hiveMaxSize();
		return Math.min(hiveMaxSize(), Misc.getMaxMarketSize(market));
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
		int size = Math.max(1, Math.min(hiveMaxSize(), ThreatIncConfig.conquestHiveSize()));
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
	 * terran world. What its deposits would add to the hive's supply
	 * (needBonus) counts on top.
	 */
	public static PlanetAPI pickColonyPlanet(StarSystemAPI system) {
		Map<String, MineableNeed> needs = mineableNeeds();
		PlanetAPI best = null;
		float bestScore = -Float.MAX_VALUE;
		for (PlanetAPI planet : system.getPlanets()) {
			if (!isColonizable(planet)) continue;

			float score = depositScore(planet) + needBonus(planet, needs);
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
	 * Planets whose deposits would add to the hive's full-grown supply score
	 * far higher (needBonus) - a hive whose best rare-ore world would make less
	 * than its refineries want grabs a richer one first.
	 */
	public static PlanetAPI pickExpansionPlanet(StarSystemAPI system) {
		// a strained hive claims only planets that relieve its shortfalls -
		// no generic land-grabs while every colony is starving
		boolean strainedHive = !anyNominalColony();
		Map<String, MineableNeed> needs = mineableNeeds();
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
	 * One mineable input across the hive at full growth, for the deposit pull. The economy plans
	 * on potential (the user, 2026-10-10: "prioritising based on potential ... assuming all
	 * colonies are size 8 it ideally wants a perfect balance"); blockades, sieges and capped ports
	 * are the war planner's. A colony draws a commodity from its single best source (vanilla's
	 * broadcast; at size 8 no hive output reaches shipping's cap), so an input is balanced once the
	 * hive's best full-grown source makes what a full-grown consumer wants, and only a bigger
	 * source than that best adds anything.
	 */
	public static final class MineableNeed {
		public final String commodityId;
		/** The largest output any live hive would make at full growth (hiveOutput). */
		public int best;
		/** What a full-grown consumer wants (fullGrownDemand). */
		public final int wanted;
		/** Live hives that could mine it, and that consume it today. */
		public int sources, consumers;
		/** Units short today across the colonies - the census line's comparison, not the pull. */
		public int shortToday;

		MineableNeed(String commodityId) {
			this.commodityId = commodityId;
			this.wanted = fullGrownDemand(commodityId);
		}

		/**
		 * Units a new full-grown source making `output` would add: every consumer's draw rises from
		 * the best to min(wanted, output). With no consumer yet it counts one - the planner builds
		 * every chain link once.
		 */
		public int relief(int output) {
			return Math.max(1, consumers) * Math.max(0, Math.min(wanted, output) - best);
		}
	}

	/** Each mineable input's MineableNeed across the live hives. */
	public static Map<String, MineableNeed> mineableNeeds() {
		Map<String, MineableNeed> needs = new LinkedHashMap<String, MineableNeed>();
		List<MarketAPI> hives = ThreatIncData.getAllLiveColonyMarkets();
		for (String commodityId : MINEABLE_INPUTS) {
			MineableNeed need = new MineableNeed(commodityId);
			for (MarketAPI market : hives) {
				int made = hiveOutput(market, commodityId);
				if (made > 0) need.sources++;
				need.best = Math.max(need.best, made);
				if (deficitOf(market, commodityId) > 0) need.shortToday += deficitOf(market, commodityId);
				CommodityOnMarketAPI com = market.getCommodityData(commodityId);
				if (com != null && com.getMaxDemand() > 0) need.consumers++;
			}
			needs.put(commodityId, need);
		}
		return needs;
	}

	/** Mining's output against the colony's size: ore at size, rare ore and volatiles 2 less (read in the hw137 saves). */
	protected static int miningOffset(String commodityId) {
		return Commodities.ORE.equals(commodityId) ? 0 : -2;
	}

	/** What a full-grown consumer wants of an input: Refining ore at size + 2 and rare ore at size, Fuel Production volatiles at size. */
	public static int fullGrownDemand(String commodityId) {
		return hiveMaxSize() + (Commodities.ORE.equals(commodityId) ? 2 : 0);
	}

	/** What a market's deposit of an input would mine at the given size, richness included (0: no deposit). */
	protected static int depositOutput(MarketAPI market, String commodityId, int size) {
		int out = 0;
		for (MarketConditionAPI cond : market.getConditions()) {
			if (!commodityId.equals(ResourceDepositsCondition.COMMODITY.get(cond.getId()))) continue;
			Integer mod = ResourceDepositsCondition.MODIFIER.get(cond.getId());
			out = Math.max(out, size + miningOffset(commodityId) + (mod != null ? mod : 0));
		}
		return out;
	}

	/**
	 * What a live hive would make of an input full grown (hiveMaxSize, or its size if larger): its
	 * deposit, or today's output grown with it where a relic or item lifts it above the deposit
	 * (Unhcegila's Plasma Dynamo). A disrupted or blockaded mine counts at its potential.
	 */
	protected static int hiveOutput(MarketAPI market, String commodityId) {
		int full = Math.max(hiveMaxSize(), market.getSize());
		int out = depositOutput(market, commodityId, full);
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com != null && com.getMaxSupply() > 0) {
			out = Math.max(out, com.getMaxSupply() + full - market.getSize());
		}
		return out;
	}

	/** Score a unit of relief adds: a deposit's base score in depositScore, so a unit gained weighs like one more deposit. */
	protected static final float RELIEF_SCORE = 30f;

	/**
	 * What this planet's deposits would add to the hive's full-grown supply, scored: RELIEF_SCORE a
	 * unit of MineableNeed.relief. A deposit no richer than the hive's best source adds nothing.
	 */
	public static float needBonus(PlanetAPI planet, Map<String, MineableNeed> needs) {
		if (planet.getMarket() == null) return 0f;
		float bonus = 0f;
		for (MineableNeed need : needs.values()) {
			bonus += need.relief(depositOutput(planet.getMarket(), need.commodityId, hiveMaxSize())) * RELIEF_SCORE;
		}
		return bonus;
	}

	/**
	 * What a system's colonizable planets would add to the hive's supply: per input, its best
	 * planet's relief, scored - two deposits of one input add no more than the larger.
	 */
	public static float systemNeedScore(StarSystemAPI system, Map<String, MineableNeed> needs) {
		float score = 0f;
		for (MineableNeed need : needs.values()) {
			int units = 0;
			for (PlanetAPI planet : system.getPlanets()) {
				if (!isColonizable(planet)) continue;
				units = Math.max(units, need.relief(depositOutput(planet.getMarket(), need.commodityId, hiveMaxSize())));
			}
			score += units * RELIEF_SCORE;
		}
		return score;
	}

	/** The census log's line: each mineable input's best full-grown source against what a full-grown consumer wants, and today's shortfall. */
	public static String mineableNeedsLine() {
		StringBuilder sb = new StringBuilder("Mineable needs:");
		for (MineableNeed need : mineableNeeds().values()) {
			sb.append(' ').append(need.commodityId).append(" best ").append(need.best).append(" of ").append(need.wanted)
					.append(", sources ").append(need.sources).append(", consumers ").append(need.consumers)
					.append(", short today ").append(need.shortToday).append(';');
		}
		return sb.toString();
	}

	protected static boolean isColonizable(PlanetAPI planet) {
		if (planet.isStar()) return false;
		if (planet.getMarket() == null) return false;
		return ThreatMapFog.conditionOnly(planet.getMarket());
	}

	/** A planet's pull as a colony site: 30 + 10 x richness a deposit. */
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
	 *
	 * Enough planets is homeWorlds (user's decision 2026-10-02: how many worlds
	 * the swarm starts on is a setting, not the luck of the system drawn).
	 */
	public static boolean canSupportFullChain(StarSystemAPI system) {
		return canSupportChain(system, ThreatIncConfig.homeWorlds());
	}

	/** canSupportFullChain for a chain of the given number of worlds (IncursionManager.pickOGSystem steps down when no system has homeWorlds). */
	public static boolean canSupportChain(StarSystemAPI system, int worlds) {
		return countColonizablePlanets(system) >= worlds
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
	 * richest ore world, the richest volatiles world, and the leanest other
	 * colonisable planets to host refining and heavy industry, distinct,
	 * homeWorlds of them (2026-10-02; the system's other planets are taken
	 * later by tryExpandInSystem, paid for like any wave).
	 */
	public static List<PlanetAPI> pickChainPlanets(StarSystemAPI system) {
		List<PlanetAPI> all = pickChainPlanetsUncut(system);
		int worlds = Math.max(1, ThreatIncConfig.homeWorlds());
		return all.size() > worlds ? new ArrayList<PlanetAPI>(all.subList(0, worlds)) : all;
	}

	/** Every colonisable planet of the system in chain order: the best ore, rare ore and volatiles worlds, then the rest leanest first. */
	protected static List<PlanetAPI> pickChainPlanetsUncut(StarSystemAPI system) {
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
		List<PlanetAPI> chain = pickChainPlanets(system);
		PlanetAPI forge = seedForgePlanet(system, chain);
		for (PlanetAPI planet : chain) {
			// skip planets already colonized or already inbound
			if (planet.getMarket() != null && !ThreatMapFog.conditionOnly(planet.getMarket())) continue;
			if (ThreatIncData.waveFleets().containsKey(planet.getId())) continue;
			if (planet == forge && planet.getMarket() != null) {
				planet.getMarket().getMemoryWithoutUpdate().set(SEED_FORGE_KEY, true);
			}
			launchColonizationWave(null, system, planet, random);
		}
	}

	/**
	 * Market memory on the chain planet that lands with the hive's first forge
	 * (2026-09-30). Each landing's one free build is Mining wherever there are
	 * deposits, and a chain may be deposit worlds alone; with structures paid in
	 * supplies and supplies made only by forges, such a hive could never buy
	 * its first forge. planHiveEconomy builds Heavy Industry here instead, free,
	 * while the hive has none.
	 */
	public static final String SEED_FORGE_KEY = "$threatinc_seedForge";

	/** The chain planet the first forge lands on: the leanest that is not the chain's best ore, rare ore or volatiles world, else the leanest. */
	protected static PlanetAPI seedForgePlanet(StarSystemAPI system, List<PlanetAPI> chain) {
		if (chain.isEmpty()) return null;
		List<PlanetAPI> sources = new ArrayList<PlanetAPI>();
		for (String deposit : new String[] { Commodities.ORE, Commodities.RARE_ORE, Commodities.VOLATILES }) {
			PlanetAPI best = bestPlanetForDeposit(system, deposit);
			if (best != null) sources.add(best);
		}
		PlanetAPI leanest = null;
		for (PlanetAPI planet : chain) {
			if (!sources.contains(planet)) return planet;
			if (leanest == null || depositScore(planet) < depositScore(leanest)) leanest = planet;
		}
		return leanest;
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

	/** Whether every commodity the colony's Mining would dig is dug already by another hive world's Mining. */
	protected static boolean minesNothingNew(MarketAPI market) {
		java.util.Set<String> dug = new java.util.HashSet<String>();
		for (MarketAPI other : ThreatIncData.getAllLiveColonyMarkets()) {
			if (other == market || !other.hasIndustry(Industries.MINING)) continue;
			for (MarketConditionAPI cond : other.getConditions()) {
				String commodity = ResourceDepositsCondition.COMMODITY.get(cond.getId());
				if (commodity != null && Industries.MINING.equals(ResourceDepositsCondition.INDUSTRY.get(commodity))) {
					dug.add(commodity);
				}
			}
		}
		for (MarketConditionAPI cond : market.getConditions()) {
			String commodity = ResourceDepositsCondition.COMMODITY.get(cond.getId());
			if (commodity == null || !Industries.MINING.equals(ResourceDepositsCondition.INDUSTRY.get(commodity))) continue;
			if (!dug.contains(commodity)) return false;
		}
		return true;
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
			if (!affordStructure(market, payerId, THREAT_HEAVY_BATTERIES)) return;
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

		// the opening chain's first forge (launchOGChain), before its Mining
		if (market.getMemoryWithoutUpdate().getBoolean(SEED_FORGE_KEY)) {
			market.getMemoryWithoutUpdate().unset(SEED_FORGE_KEY);
			if (countLink(1) == 0 && getForge(market) == null
					&& buyStructure(market, Industries.HEAVYINDUSTRY, payerId)) {
				markEconomyDirty();
				ThreatIncConfig.log("Hive planner: HEAVYINDUSTRY at " + market.getName() + " (seed forge)");
				return;
			}
		}

		// mine what the planet offers (Mining supplies nothing without deposits) -
		// but while a link of the chain has no first copy, a world whose deposits
		// the hive already digs builds that link instead: availability is the best
		// source, so a second mine of the same ore adds nothing (2026-10-01, ng1b:
		// five of the six bootstrap worlds took Mining in their one slot, and the
		// refinery and fuel plant waited 12 months for size 3 while 28k supplies
		// sat idle - the new hive flew nothing for its first 16 months)
		boolean mines = hasMiningDeposits(market) && !market.hasIndustry(Industries.MINING);
		if (mines && minesNothingNew(market)) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				// the forge keeps its own road (the seed forge, then the bootstrap below)
				if (Industries.HEAVYINDUSTRY.equals(CHAIN_LINKS[link])) continue;
				if (countLink(link) == 0 && tryBuildLink(market, link, "first", false, payerId)) return;
			}
		}
		if (mines) {
			if (!buyStructure(market, Industries.MINING, payerId)) return;
			markEconomyDirty();
			ThreatIncConfig.log("Hive planner: MINING at " + market.getName());
			return;
		}

		// bootstrap: the first copy of each link, wherever there is room
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (countLink(link) == 0 && tryBuildLink(market, link, "first", false, payerId)) return;
		}

		// what the hive's stocks would run dry of (2026-09-30; 2026-10-01 on the
		// trailing demand, ThreatFuel.runsDry): another fuel plant or forge while
		// the stock and production cannot cover the demand over a producer's build
		// time, another forge while the colonies' sustenance goes unpaid. h26a
		// held 470 sends for fuel and built no plant for them; on any held send
		// the overnight tests built 59, never retired. One at a time, each given
		// a month to show (ThreatFuel.mayAnswer)
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			String stock = linkStock(link);
			if (stock == null) continue;
			if (!ThreatFuel.mayAnswer(stock)) continue;
			boolean placed = !hasLink(market, link);
			if (tryBuildLink(market, link, stock + " short", false, payerId)) {
				if (placed && hasLink(market, link)) ThreatFuel.answered(stock);
				return;
			}
		}

		// idle supplies build a fuel plant while fuel is tight (2026-10-03, run hw4n,
		// threatinc_investFuelWhenTight): losing the biggest plant would run the stock
		// dry (ThreatFuel.wantsSpare) but it is not yet running dry, so no shortage
		// answer comes, and the spare step stops at the redundancy target - while the
		// invest step below put a forge in every free slot. Forges never retire: hw4n
		// sat fuel-starved on 209k supplies with no slot left for a plant. One a
		// SHORT_DAYS, paced with the shortage answers (ThreatFuel.mayInvest)
		if (ThreatIncConfig.investFuelWhenTight() && ThreatBuildCost.enabled() && ThreatFuel.enabled()
				&& ThreatFuel.mayInvest(Commodities.FUEL) && ThreatFuel.wantsSpare(Commodities.FUEL)
				&& ThreatFuel.stock(Commodities.SUPPLIES) >= ThreatBuildCost.supplies(Industries.FUELPROD)
						+ ThreatFuel.foundingCost()[0]) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				if (!Commodities.FUEL.equals(linkStock(link))) continue;
				// (2026-10-03, run hw4o: with one plant losing it always runs fuel dry, and
				// the step took a home world's forge slot at month 24)
				if (countLink(link) < ThreatIncConfig.investFuelMinPlants()) continue;
				boolean placed = !hasLink(market, link);
				if (tryBuildLink(market, link, "fuel tight", false, payerId)) {
					if (placed && hasLink(market, link)) ThreatFuel.answered(Commodities.FUEL);
					return;
				}
			}
		}

		// idle supplies are invested (2026-10-01): every forge's whole output is
		// the hive's stock - size - 2 units of 750 supplies and 100 FP a month -
		// so a stock that pays a forge and a founding kit besides builds one on a
		// world of output size that lacks it. The planner added forges only for a
		// noted shortage or a spare per hive system: ng1b's one-system hive held
		// one forge for its first two years, 28k supplies idle, and never grew
		// past 13 worlds
		if (size >= 3 && getForge(market) == null && ThreatBuildCost.enabled() && ThreatFuel.enabled()
				&& ThreatFuel.stock(Commodities.SUPPLIES) >= ThreatBuildCost.supplies(Industries.HEAVYINDUSTRY)
						+ ThreatFuel.foundingCost()[0]) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				if (!Industries.HEAVYINDUSTRY.equals(CHAIN_LINKS[link])) continue;
				if (tryBuildLink(market, link, "invest", false, payerId)) return;
			}
		}

		// upgrade an established forge to orbital works for better hulls - improves
		// output/quality without changing the forge count
		// Paid in supplies it is vanilla's upgrade: the forge runs on while the
		// works are built (startUpgrading), and is skipped while it is building
		Industry heavy = market.getIndustry(Industries.HEAVYINDUSTRY);
		if (size >= 6 && heavy != null && !heavy.isBuilding()) {
			if (!affordStructure(market, payerId, Industries.ORBITALWORKS)) return;
			if (payerId != null && ThreatBuildCost.enabled() && heavy.getSpec().getUpgrade() != null) {
				ThreatFuel.pay(Commodities.SUPPLIES, ThreatBuildCost.supplies(Industries.ORBITALWORKS), "build");
				heavy.startUpgrading();
			} else {
				market.removeIndustry(Industries.HEAVYINDUSTRY, null, true);
				buyStructure(market, Industries.ORBITALWORKS, payerId);
			}
			markEconomyDirty();
			announce(ThreatNotice.titled("Forge World").bad()
					.line("%s has restructured into a forge world", ThreatNotice.market(market))
					.line("Hull output there is accelerating"));
			ThreatIncConfig.log("Hive planner: ORBITALWORKS at " + market.getName());
			return;
		}

		// redundancy: every link at two copies before any at three, spare copies
		// steered to the system holding the fewest. A link that fills a stock
		// (fuel plants, forges) adds a spare only while losing the hive's biggest
		// producer would run it dry (2026-10-01, ThreatFuel.wantsSpare): the
		// overnight tests put up 26 spare fuel plants, ng5a ending on 538k fuel
		int target = redundancyTarget();
		boolean[] spares = new boolean[CHAIN_LINKS.length];
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			String stock = linkStock(link);
			spares[link] = stock == null || ThreatFuel.wantsSpare(stock);
		}
		for (int level = 1; level < target; level++) {
			for (int link = 0; link < CHAIN_LINKS.length; link++) {
				if (!spares[link]) continue;
				if (countLink(link) <= level && tryBuildLink(market, link, "spare", true, payerId)) return;
			}
		}

		// the chain is complete: a bigger copy of any link whose largest producer
		// no longer covers the hive's largest consumer of its output - never of a
		// stock in surplus (ThreatFuel.surplus)
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (outputCovered(link) || size <= largestLinkSize(link)) continue;
			String stock = linkStock(link);
			if (stock != null && ThreatFuel.surplus(stock)) continue;
			if (tryBuildLink(market, link, "bigger", false, payerId)) return;
		}
	}

	/** The hive stock a chain link fills (ThreatFuel): fuel for a fuel plant, supplies for a forge; null for a refinery. */
	protected static String linkStock(int link) {
		String out = CHAIN_OUTPUTS[link];
		if (Commodities.FUEL.equals(out)) return Commodities.FUEL;
		if (Commodities.SHIPS.equals(out)) return Commodities.SUPPLIES;
		return null;
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
		if (!buyStructure(market, industry, payerId)) {
			// a first copy or a shortage's answer the stock cannot pay is demand on
			// it; an optional build (invest, spare, bigger) that waits is not
			// (2026-10-01: every unpaid build noted supplies short). Booked by the
			// industry, not the world: one need, however many worlds ask for it
			if (payerId != null && ThreatBuildCost.enabled() && ("first".equals(label) || label.endsWith(" short"))) {
				ThreatFuel.heldBuild(industry, ThreatBuildCost.supplies(industry));
			}
			return true;
		}
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
	 * however far the hive fell below target. Re-plans every colony with a free
	 * industry slot, growing or not (2026-10-01: under size upkeep a growth step
	 * takes months) - one industry per colony per tick, which is about the pace
	 * of a vanilla construction anyway.
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
		// one surplus fuel plant a month, hive-wide, turned into what the hive
		// lacks (2026-10-01) - before the sweep, so the slot it frees is filled
		// by the conversion's own build and no other step sees it empty
		List<MarketAPI> converted = convertSurplus();
		// and one military structure a month gives its slot back to production
		// when the hive needs a producer and has no free slot (retireMilitary)
		MarketAPI retired = converted.isEmpty() ? retireMilitary() : null;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (converted.contains(market) || market == retired) continue;
			// a world that can now feed the batteries it lacks (or the heavy
			// batteries it has outgrown) arms on this tick, not on its next
			// growth step a season away - structures need no slot
			int size = market.getSize();
			int cap = maxColonySize(market);
			boolean gd = market.hasIndustry(THREAT_GROUND_DEFENSES);
			boolean hb = market.hasIndustry(THREAT_HEAVY_BATTERIES);
			boolean arms = ((size >= 3 && !gd && !hb) || (size >= 6 && gd)) && defensesAffordable(market);
			// Swarm Command needs no slot either: a Bastion grows into it in place
			boolean command = militaryUpgradeDue(market);
			// a growing world plans on this tick too (2026-10-01): under size upkeep
			// a growth step takes months, and a world that built only on its steps
			// left its free slot empty that long - ng1b's bootstrap worlds reached
			// size 3 at month 11 and built their refinery and fuel plant at 16.
			// What a build costs in supplies and time is the pace now. Size upkeep
			// off, growth is quick and builds on its steps as before
			if (!arms && !command) {
				if (!ThreatColonyUpkeep.enabled() && size < cap && growing(market)) continue;
				if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) continue;
			}
			int before = buildSignature(market);
			planHiveEconomy(market);
			// the military tier comes last: only where the chain built and wants
			// nothing this tick (maintainMilitaryTier)
			if (buildSignature(market) == before && !buildWaiting().containsKey(market.getId())) {
				maintainMilitaryTier(market);
			}
		}
	}

	/** What the planner changed on a colony shows here: its industries and how many of them are building. */
	protected static int buildSignature(MarketAPI market) {
		int building = 0;
		for (Industry ind : market.getIndustries()) {
			if (ind.isBuilding()) building++;
		}
		return market.getIndustries().size() * 100 + building;
	}

	// ------------------------------------------------------------------
	// idle stock: surplus plants converted, idle fleet points garrisoned
	// (2026-10-01, user's call; docs/hive-economy.md "Idle stock")
	// ------------------------------------------------------------------

	/** The chain link of this industry (CHAIN_LINKS), -1 for none. */
	protected static int linkIndex(String industryId) {
		for (int link = 0; link < CHAIN_LINKS.length; link++) {
			if (CHAIN_LINKS[link].equals(industryId)) return link;
		}
		return -1;
	}

	/**
	 * CONVERSION: a SHORT_DAYS apart hive-wide (ThreatFuel.mayConvert), every fuel plant
	 * the stock can spare (ThreatFuel.surplusProducer: production without it
	 * still covers the trailing demand, and the stock covers that demand over a
	 * plant's build time) is torn down on a world with no free slot, and the
	 * slot given to what the hive lacks (conversionFor): a forge while supplies
	 * are not in surplus and the world has none, else a chain link it is
	 * missing, else a Swarm Bastion. The new structure is bought and grown like
	 * any other; one that cannot be paid tears nothing down. Never the last
	 * fuel plant nor the hive's biggest, never one carrying a relic, never on a
	 * world under a front or saturation. Slot-full worlds only: a world with a
	 * free slot builds there without tearing anything down. Forges are never
	 * retired: they make the fabrication bank's hulls as well as supplies, and
	 * the invest rule builds them from idle supplies on purpose. ng5a ended on
	 * 538k fuel, its plants making 82k a month against ~30k spent. Returns the
	 * world converted, or null.
	 */
	public static List<MarketAPI> convertSurplus() {
		List<MarketAPI> done = new ArrayList<MarketAPI>();
		if (!ThreatIncConfig.hiveConvertSurplus() || !ThreatFuel.planned() || !ThreatFuel.mayConvert()) return done;
		String fuel = Commodities.FUEL;
		int plants = linkIndex(Industries.FUELPROD);
		if (plants < 0 || countLink(plants) <= 1 || !ThreatFuel.surplus(fuel)) {
			ThreatFuel.reserve(RESERVE_CONVERSION, 0f);
			return done;
		}
		// the hive's biggest plant stays
		MarketAPI biggest = null;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			if (!m.hasIndustry(Industries.FUELPROD)) continue;
			if (biggest == null || ThreatFuel.outputOf(m, fuel) > ThreatFuel.outputOf(biggest, fuel)) biggest = m;
		}
		final Map<MarketAPI, String> builds = new java.util.HashMap<MarketAPI, String>();
		List<MarketAPI> picks = new ArrayList<MarketAPI>();
		float unpaidForge = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			if (m == biggest) continue;
			Industry plant = m.getIndustry(Industries.FUELPROD);
			if (plant == null || plant.isBuilding()) continue;
			if (Misc.getNumIndustries(m) < Misc.getMaxIndustries(m)) continue;
			if (plant.getSpecialItem() != null || plant.getAICoreId() != null) continue;
			if (ThreatGroundFronts.hasFront(m) || ThreatRazing.saturated(m)) continue;
			if (!ThreatFuel.surplusProducer(m, fuel)) continue;
			String build = conversionFor(m);
			if (build == null) continue;
			if (!conversionPayable(m, build)) {
				// a forge the stock cannot pay is the shortage's answer: its price is set aside (ThreatFuel.reserved)
				if (Industries.HEAVYINDUSTRY.equals(build) && unpaidForge <= 0f) unpaidForge = ThreatBuildCost.supplies(build);
				continue;
			}
			builds.put(m, build);
			picks.add(m);
		}
		ThreatFuel.reserve(RESERVE_CONVERSION, picks.isEmpty() ? unpaidForge : 0f);
		if (picks.isEmpty()) return done;
		// the slot worth most first: a forge, then a chain link, then the
		// military tier; the bigger world (more output, more swarms), then the
		// plant that makes least
		java.util.Collections.sort(picks, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				int c = conversionRank(builds.get(a)) - conversionRank(builds.get(b));
				if (c != 0) return c;
				c = b.getSize() - a.getSize();
				if (c != 0) return c;
				return Float.compare(ThreatFuel.outputOf(a, Commodities.FUEL), ThreatFuel.outputOf(b, Commodities.FUEL));
			}
		});
		float stock = ThreatFuel.stock(fuel);
		float demand = ThreatFuel.demandPerMonth(fuel);
		float made = ThreatFuel.perMonth(fuel);
		// AS MANY AS THE SURPLUS SPARES (2026-10-07, after hw60: one a month gave five conversions in 77 months
		// with 2.5M fuel banked at 70-130 months of demand while supplies bound every seeding and campaign):
		// each plant in turn while the production left still covers the trailing demand, the stock's cover
		// standing for all of them (surplusProducer's test with the plants already gone taken off)
		float madeLeft = made;
		for (MarketAPI m : picks) {
			String build = builds.get(m);
			float output = ThreatFuel.outputOf(m, fuel);
			if (madeLeft - output < demand) break;
			if (!conversionPayable(m, build)) continue;
			// paid before anything comes down
			String price;
			if (SwarmBastion.BASTION.equals(build)) {
				float fp = ThreatBuildCost.fleetPoints(build);
				if (!payMilitary(m, build)) continue;
				m.removeIndustry(Industries.FUELPROD, null, false);
				addMilitary(m);
				price = Misc.getWithDGS((int) fp) + " FP";
			} else {
				float supplies = ThreatBuildCost.supplies(build);
				// bought first (addIndustry asks no slot): a refusal tears nothing down
				if (!buyStructure(m, build, m.getId())) continue;
				m.removeIndustry(Industries.FUELPROD, null, false);
				price = Misc.getWithDGS((int) supplies) + " supplies";
			}
			madeLeft -= output;
			done.add(m);
			ThreatIncConfig.log("Converted Fuel Production on " + m.getName() + " to " + structureName(build) + ": fuel "
					+ (int) stock + " covers " + (demand > 0f ? String.format("%.1f", stock / demand) : "all")
					+ " months of demand (" + (int) demand + "/mo trailing, " + (int) made + "/mo made, " + (int) output
					+ "/mo from this plant, " + (int) madeLeft + "/mo left); " + price);
		}
		if (done.isEmpty()) return done;
		markEconomyDirty();
		ThreatFuel.converted();
		return done;
	}

	/** The reservation key of a forge conversion the stock cannot pay yet (convertSurplus, ThreatFuel.reserve). */
	protected static final String RESERVE_CONVERSION = "threatinc_conversion";

	/** What a fuel plant's slot on this world becomes (convertSurplus), or null for nothing worth tearing it down for. */
	protected static String conversionFor(MarketAPI m) {
		// a forge while the hive could use every unit of supplies it makes
		if (m.getSize() >= 3 && getForge(m) == null && !ThreatFuel.surplus(Commodities.SUPPLIES)) {
			return Industries.HEAVYINDUSTRY;
		}
		// a chain link it is missing: Mining on deposits nobody digs, a first
		// refinery, or one bigger than the hive's largest once that no longer
		// covers its largest consumer of metals
		if (hasMiningDeposits(m) && !m.hasIndustry(Industries.MINING) && !minesNothingNew(m)) return Industries.MINING;
		int refinery = linkIndex(Industries.REFINING);
		if (refinery >= 0 && !m.hasIndustry(Industries.REFINING) && groupHasIndustry(Industries.MINING)
				&& (countLink(refinery) == 0 || (!outputCovered(refinery) && m.getSize() > largestLinkSize(refinery)))) {
			return Industries.REFINING;
		}
		// else the military tier, on a world with a Nexus and none yet
		if (ThreatIncConfig.hiveMilitaryTier() && m.hasIndustry(SWARM_NEXUS) && SwarmBastion.of(m) == null) {
			return SwarmBastion.BASTION;
		}
		return null;
	}

	/** Whether the conversion's build can be paid now: the supplies in stock, or for the military tier an idle bank (militaryIdle). */
	protected static boolean conversionPayable(MarketAPI m, String build) {
		if (SwarmBastion.BASTION.equals(build)) return militaryIdle(m, build);
		return ThreatFuel.stock(Commodities.SUPPLIES) >= ThreatBuildCost.supplies(build);
	}

	/** Lower first: a forge, a chain link, the military tier. */
	protected static int conversionRank(String build) {
		if (Industries.HEAVYINDUSTRY.equals(build)) return 0;
		if (SwarmBastion.BASTION.equals(build)) return 2;
		return 1;
	}

	/**
	 * RETIREMENT (2026-10-01, user's call): the military tier gives its slot
	 * back to production. Once a month hive-wide, when the hive needs a
	 * producer - a chain link it has none of, or a stock's answer
	 * (ThreatFuel.mayAnswer: the stock runs dry) - and no world of the hive has
	 * a free slot to build it in, a standing Swarm Bastion or Swarm Command is
	 * torn down for it: a Bastion before a Command, the bigger world first. The
	 * producer is bought first (a refusal tears nothing down, and its price is
	 * demand on the stock, heldBuild) and takes its vanilla build time, as the
	 * Bastion took its own, so the swap is never instant either way; while it
	 * builds, the stock counts it as coming (comingPerMonth) and is not answered
	 * twice. A structure still growing or upgrading stays, as does one on a
	 * world under a front or saturation. Nothing of its price comes back; the
	 * swarms it kept home stay, free to launch. Returns the world, or null.
	 */
	public static MarketAPI retireMilitary() {
		List<MarketAPI> picks = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			// a free slot anywhere builds it without tearing anything down
			if (Misc.getNumIndustries(m) < Misc.getMaxIndustries(m)) return null;
			Industry tier = SwarmBastion.of(m);
			if (tier == null || tier.isBuilding()) continue;
			if (ThreatGroundFronts.hasFront(m) || ThreatRazing.saturated(m)) continue;
			picks.add(m);
		}
		if (picks.isEmpty()) return null;
		int need = -1;
		String label = null;
		for (int link = 0; link < CHAIN_LINKS.length && need < 0; link++) {
			String industry = CHAIN_LINKS[link];
			if (Industries.REFINING.equals(industry) && !groupHasIndustry(Industries.MINING)) continue;
			if (Industries.FUELPROD.equals(industry) && !groupHasVolatiles()) continue;
			String stock = linkStock(link);
			if (countLink(link) == 0) {
				need = link;
				label = "first";
			} else if (stock != null && ThreatFuel.mayAnswer(stock)) {
				need = link;
				label = stock + " short";
			}
		}
		if (need < 0) return null;
		java.util.Collections.sort(picks, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				int c = SwarmBastion.tier(a) - SwarmBastion.tier(b);
				return c != 0 ? c : b.getSize() - a.getSize();
			}
		});
		String industry = CHAIN_LINKS[need];
		for (MarketAPI m : picks) {
			if (hasLink(m, need)) continue;
			if (ThreatBuildCost.enabled() && ThreatFuel.stock(Commodities.SUPPLIES) < ThreatBuildCost.supplies(industry)) {
				ThreatFuel.heldBuild(industry, ThreatBuildCost.supplies(industry));
				return null;
			}
			String retired = SwarmBastion.of(m).getId();
			if (!buyStructure(m, industry, m.getId())) return null;
			m.removeIndustry(retired, null, false);
			if (!"first".equals(label)) ThreatFuel.answered(linkStock(need));
			markEconomyDirty();
			ThreatIncConfig.log("Hive planner: " + structureName(retired) + " at " + m.getName() + " retired for "
					+ industry + " (" + label + "), " + (int) ThreatFuel.stock(Commodities.SUPPLIES) + " supplies left");
			return m;
		}
		return null;
	}

	/** The structure's name from its spec, for the log. */
	protected static String structureName(String industryId) {
		try {
			return Global.getSettings().getIndustrySpec(industryId).getName();
		} catch (RuntimeException e) {
			return industryId;
		}
	}

	/**
	 * THE MILITARY TIER (SwarmBastion): a colony whose chain built and wants
	 * nothing this tick, and whose bank its garrison does not need
	 * (militaryIdle), grows a Swarm Bastion in a free slot - unless a stock is
	 * wanted (ThreatFuel.wanted) whose producer the world lacks, a free slot
	 * being the chain's first - and later grows it into Swarm Command in place. Paid in fleet points from
	 * the colony's bank, its system's topping it up (poolSystemBanks), at the
	 * vanilla structure's price (ThreatBuildCost.fleetPoints); vanilla's build
	 * time. The swarms the tier keeps home are then fabricated from the bank and
	 * pay the garrison's upkeep: the sink for fleet points the hive banks and
	 * has no other use for (ng4a: 297k FP banked).
	 */
	protected static void maintainMilitaryTier(MarketAPI market) {
		if (!ThreatIncConfig.hiveMilitaryTier() || !market.hasIndustry(SWARM_NEXUS)) return;
		Industry standing = SwarmBastion.of(market);
		if (standing != null) {
			if (!militaryUpgradeDue(market)) return;
			float fp = ThreatBuildCost.fleetPoints(SwarmBastion.COMMAND);
			if (!payMilitary(market, SwarmBastion.COMMAND)) return;
			if (ThreatBuildCost.enabled() && ThreatBuildCost.buildDays(SwarmBastion.COMMAND) > 0f
					&& standing.getSpec().getUpgrade() != null) {
				standing.startUpgrading();
			} else {
				market.removeIndustry(SwarmBastion.BASTION, null, true);
				market.addIndustry(SwarmBastion.COMMAND);
			}
			markEconomyDirty();
			logMilitary(market, SwarmBastion.COMMAND, fp);
			return;
		}
		if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) return;
		// a free slot is the chain's first: held for a forge while supplies are
		// wanted and the world has none, for a fuel plant while fuel is and it
		// has none (a build the planner already chose waits in buildWaiting).
		// Held while either stock was short anywhere, it was held for good under
		// size upkeep: h40a ran short of supplies all run, 98k FP banked, no Bastion
		if (ThreatFuel.wanted(Commodities.SUPPLIES) && getForge(market) == null) return;
		if (ThreatFuel.wanted(Commodities.FUEL) && !market.hasIndustry(Industries.FUELPROD)) return;
		if (!militaryIdle(market, SwarmBastion.BASTION)) return;
		float fp = ThreatBuildCost.fleetPoints(SwarmBastion.BASTION);
		if (!payMilitary(market, SwarmBastion.BASTION)) return;
		addMilitary(market);
		logMilitary(market, SwarmBastion.BASTION, fp);
	}

	/** Whether the colony's standing Swarm Bastion grows into Swarm Command now: not disrupted or building, and the bank idle for it. */
	protected static boolean militaryUpgradeDue(MarketAPI market) {
		if (!ThreatIncConfig.hiveMilitaryTier()) return false;
		Industry bastion = market.getIndustry(SwarmBastion.BASTION);
		if (bastion == null || bastion.isBuilding() || bastion.isDisrupted()) return false;
		return militaryIdle(market, SwarmBastion.COMMAND);
	}

	/**
	 * Whether the colony's fleet points sit idle enough for the tier's
	 * structure: its garrison holds its want, its income carries the upkeep of
	 * that garrison and of the swarms the structure adds (militaryExtraFP), and
	 * its bank with its system's (poolableFP) holds the price, those swarms and
	 * that upkeep over the structure's build time. No number of its own: the
	 * price is the vanilla structure's, the rest the garrison's own rows and
	 * upkeep rate.
	 */
	protected static boolean militaryIdle(MarketAPI market, String industryId) {
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(market.getId());
		float held = ownedFleetFP(market, fleets);
		if (ThreatPosture.enabled()) {
			if (held < ThreatPosture.wantFP(market)) return false;
		} else if (countFitGarrison(market) + swarmsAway(market.getId()) < desiredGarrisonCount(market)) {
			return false;
		}
		float extra = militaryExtraFP(market, industryId);
		float upkeepMonth = upkeepPerDay(held + extra) * 30f;
		if (fabricationRatePerDay(market) * 30f < upkeepMonth) return false;
		float months = ThreatBuildCost.enabled() ? ThreatBuildCost.buildDays(industryId) / 30f : 0f;
		return poolableFP(market) >= ThreatBuildCost.fleetPoints(industryId) + extra + upkeepMonth * months;
	}

	/** Fleet points of the swarms the structure adds to the colony's reserve: a base reserve's worth of the size table's next rows. */
	protected static float militaryExtraFP(MarketAPI market, String industryId) {
		int base = baseReserve(market);
		int from = SwarmBastion.COMMAND.equals(industryId) ? 2 * base : base;
		return ThreatPosture.rowsFP(market, from, base);
	}

	/** Pays the tier's structure from the colony's bank, its system's topping it up; false, nothing paid, if they cannot. */
	protected static boolean payMilitary(MarketAPI market, String industryId) {
		float price = ThreatBuildCost.fleetPoints(industryId);
		if (!poolSystemBanks(market, price, structureName(industryId))) return false;
		chargeFP(market, price);
		return true;
	}

	/** Adds the Swarm Bastion to the colony, grown over vanilla's build time while structures take one. */
	protected static void addMilitary(MarketAPI market) {
		market.addIndustry(SwarmBastion.BASTION);
		Industry ind = market.getIndustry(SwarmBastion.BASTION);
		if (ind != null && ThreatBuildCost.enabled() && ThreatBuildCost.buildDays(SwarmBastion.BASTION) > 0f) {
			ind.startBuilding();
		}
		markEconomyDirty();
	}

	protected static void logMilitary(MarketAPI market, String industryId, float fp) {
		int reserve = baseReserve(market) * (SwarmBastion.COMMAND.equals(industryId) ? 3 : 2);
		ThreatIncConfig.log("Hive planner: " + structureName(industryId) + " at " + market.getName() + " for "
				+ (int) fp + " FP, " + reserve + " swarms home once it stands ("
				+ (int) militaryExtraFP(market, industryId) + " FP more); " + (int) bankedFP(market) + " FP banked");
		announce(ThreatNotice.titled(structureName(industryId)).bad()
				.line("%s is growing a " + structureName(industryId), ThreatNotice.market(market))
				.line("It will keep %s Defense Swarms at home", ThreatNotice.hl("" + reserve)));
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
		// the fingerprint is a stored vitality: under size upkeep growth reads
		// the supplies paid and the stored figure means nothing
		if (ThreatColonyUpkeep.enabled()) return 0;
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
		int trickle = ThreatIncConfig.disruptedPortShipping();
		boolean down = trickle >= 0 && port != null && port.isDisrupted();
		// a human blockade over it (ThreatBlockade.syncHive): half holds shipping
		// at the dead port's trickle, all at nothing - vanilla's own accessibility
		// modifier barely moves same-faction shipping (5+ units at 0%)
		float cut = market.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY);
		int units = Integer.MAX_VALUE;
		String why = null;
		if (down || (cut > 0f && cut < 1f && trickle >= 0)) {
			units = Math.max(0, trickle);
			why = down ? "Port disrupted - skeleton docking only" : "Blockaded - skeleton docking only";
		}
		if (cut >= 1f) {
			units = 0;
			why = "Blockaded - nothing docks";
		}
		if (why == null) {
			if (had) ThreatIncConfig.log("Shipping back up at " + market.getName());
			return;
		}
		float target = shippingAccessibility(units);
		float current = market.getAccessibilityMod().computeEffective(0f);
		if (current > target) {
			market.getAccessibilityMod().modifyFlat(PORT_DOWN_MOD_ID, target - current, why);
		}
		// a verdict line: a port already below the trickle's figure never takes
		// the modifier, so "had" never held and it logged every poll (Qaras,
		// 106 times in ng7a)
		ThreatIncConfig.logOnChange("port:" + market.getId(), why, why + " at " + market.getName() + ": accessibility "
				+ Math.round(current * 100f) + "% -> " + Math.round(target * 100f)
				+ "%, shipping " + Misc.getShippingCapacity(market, true) + " units");
	}

	/** The accessibility that gives {@code units} of same-faction shipping (portDownAccessibility's rule). */
	public static float shippingAccessibility(int units) {
		return (Math.max(0, units) + 0.5f) * Misc.PER_UNIT_SHIPPING - Misc.SAME_FACTION_BONUS;
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
		// billed reach (ThreatReach): the fleets fly on the hive's banked fuel,
		// wherever its plants made it - grounded only once the stock is spent
		if (ThreatReach.enabled()) return ThreatFuel.stock() > 0f;
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
	 * How far expeditions from this colony reach, in light-years - the rule
	 * for faction military worlds and the player's colonies, and for hive
	 * worlds only with billedReach off (the hive's reach is its bill,
	 * ThreatReach):
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
			// (a staged strike is built from the banks: no garrison is asked of it)
			if (requireReadyForge && !ThreatIncConfig.strikeStaged() && garrisonAvailableForLaunch(curr) < 2) continue;
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

	/**
	 * Defense Swarms a colony always keeps home; it never musters these. Half
	 * its size table (baseReserve), doubled under a Swarm Bastion and tripled
	 * under Swarm Command (2026-10-01, SwarmBastion): the posture's minimum and
	 * base and the launch reserve all read it, so the swarms the tier adds are
	 * built from the bank and stay home whatever the posture wants.
	 */
	public static int garrisonReserve(MarketAPI market) {
		return baseReserve(market) * (1 + SwarmBastion.tier(market));
	}

	/** The reserve without a military tier: half the (hull-scaled) size table, at least one swarm. */
	public static int baseReserve(MarketAPI market) {
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
		// posture: the fleets a launch may take (launchOrder - what the colony holds above its
		// reserve's FP, in the builder's unit; the fleet count minus garrisonReserve, a swarm
		// count from the size table, gated it before and no quiet colony ever had a swarm to
		// spare under the hull pool's FP want, hw133/hw134 2026-10-09)
		if (ThreatPosture.enabled()) return launchOrder(market, false).size();
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
	 * The garrison fleets a launch may take from the colony, in the muster's order: every live
	 * fleet, largest first, with {@code all} (an earmarked prong, or posture off); under posture
	 * each fleet fitted into the spare - what the colony holds above its reserve's FP
	 * (ThreatPosture.minimumFP) and, pressed, above the pressure's need (launchSpareFP) - one too
	 * big for it skipped, never the last fleet. ownAvailableForLaunch counts it, musterFrom
	 * walks it, so the two agree (hw135: the count fitted largest first and broke at the first
	 * fleet too big - the founding seed at 5x the reserve - while the muster would have taken it).
	 */
	protected static List<CampaignFleetAPI> launchOrder(MarketAPI market, boolean all) {
		List<CampaignFleetAPI> alive = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr != null && curr.isAlive()) alive.add(curr);
		}
		java.util.Collections.sort(alive, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				return Float.compare(b.getFleetPoints(), a.getFleetPoints());
			}
		});
		if (all || !ThreatPosture.enabled()) return alive;
		float held = ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId()));
		float spare = Math.min(held - ThreatPosture.minimumFP(market), ThreatPosture.launchSpareFP(market));
		List<CampaignFleetAPI> out = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI curr : alive) {
			if (out.size() >= alive.size() - 1) break;
			float fp = curr.getFleetPoints();
			if (fp > spare) continue;
			spare -= fp;
			out.add(curr);
		}
		return out;
	}

	/**
	 * A forge colony that holds a swarm's worth above its reserve's FP but no fleet a launch can
	 * take (launchOrder empty: its fleets too big for the spare, or one fleet - the founding seed
	 * holds ~5x the reserve) carves one off its largest fleet in orbit
	 * (ThreatFleetComposer.splitOff): the size table's first launch-stock row, within the spare.
	 * The piece is a garrison swarm of the host's spec; the host's spawn strength drops by it so
	 * neither reads under strength. Once a poll, from maintainGarrisons (hw136, 2026-10-09).
	 */
	protected static void splitLaunchSwarm(MarketAPI market, List<CampaignFleetAPI> fleets, Random random) {
		if (market == null || market.getStarSystem() == null || market.getPrimaryEntity() == null) return;
		if (getForge(market) == null || ThreatPosture.underAttack(market.getStarSystem())) return;
		float held = ownedFleetFP(market, fleets);
		if (ThreatPosture.regrowing(market, held)) return;
		float spare = Math.min(held - ThreatPosture.minimumFP(market), ThreatPosture.launchSpareFP(market));
		int[][] table = desiredGarrison(market.getSize());
		float target = swarmCostEstimate(table[Math.min(garrisonReserve(market), table.length - 1)]);
		if (target <= 0f || spare < target) return;
		CampaignFleetAPI host = null;
		for (CampaignFleetAPI curr : fleets) {
			if (curr == null || !curr.isAlive() || curr.getBattle() != null) continue;
			if (curr.getContainingLocation() != market.getStarSystem()) continue;
			if (curr.getFleetData().getNumMembers() < 2 || curr.getFleetPoints() <= target) continue;
			if (host == null || curr.getFleetPoints() > host.getFleetPoints()) host = curr;
		}
		if (host == null) return;
		CampaignFleetAPI piece = ThreatFleetComposer.splitOff(host, target, spare, random);
		if (piece == null) return;
		float fp = piece.getFleetPoints();
		com.fs.starfarer.api.campaign.rules.MemoryAPI hm = host.getMemoryWithoutUpdate();
		com.fs.starfarer.api.campaign.rules.MemoryAPI pm = piece.getMemoryWithoutUpdate();
		pm.set(GARRISON_FLAG, market.getId());
		pm.set(SWARM_TIER_KEY, swarmTier(host));
		pm.set(SWARM_FABS_KEY, swarmFabs(host));
		pm.set(SWARM_SPAWN_FP, fp);
		if (hm.contains(SWARM_SPAWN_FP)) hm.set(SWARM_SPAWN_FP, Math.max(0f, hm.getFloat(SWARM_SPAWN_FP) - fp));
		if (swarmCount(host) > 1) hm.set(SWARM_COUNT_KEY, swarmCount(host) - 1);
		makeDetectable(piece);
		placeGarrisonSwarm(market, piece, random);
		fleets.add(piece);
		ThreatIncConfig.log("Launch swarm split at " + market.getName() + ": " + (int) fp + " FP off a "
				+ (int) (host.getFleetPoints() + fp) + " FP fleet (spare " + (int) spare + ", target " + (int) target
				+ ", " + fleets.size() + " fleets)");
	}

	/**
	 * The garrison fleets a colony gives a world under attack (ThreatPosture's
	 * massing): what a launch would muster of them (ownAvailableForLaunch) -
	 * the largest above its reserve, within what it holds above its own need -
	 * whether or not it is at its want. On station and out of battle.
	 */
	public static List<CampaignFleetAPI> spareFleets(MarketAPI market) {
		return spareFleets(market, true);
	}

	/**
	 * As above; with {@code withinNeed} false, every fleet above the reserve
	 * count whatever the colony's own need (ThreatPosture.rally: a need split
	 * by the tables holds nothing at a world no force is over).
	 */
	public static List<CampaignFleetAPI> spareFleets(MarketAPI market, boolean withinNeed) {
		List<CampaignFleetAPI> out = new ArrayList<CampaignFleetAPI>();
		if (market == null) return out;
		List<CampaignFleetAPI> live = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI curr : ThreatIncData.garrisonsFor(market.getId())) {
			if (curr != null && curr.isAlive()) live.add(curr);
		}
		int n = live.size() - garrisonReserve(market);
		if (n <= 0) return out;
		java.util.Collections.sort(live, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				return b.getFleetPoints() - a.getFleetPoints();
			}
		});
		float spare = withinNeed ? ThreatPosture.launchSpareFP(market) : Float.MAX_VALUE;
		for (int i = 0; i < n; i++) {
			CampaignFleetAPI curr = live.get(i);
			if (spare != Float.MAX_VALUE) {
				spare -= curr.getFleetPoints();
				if (spare < 0f) break;
			}
			if (curr.getBattle() == null) out.add(curr);
		}
		return out;
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

	/**
	 * ONE colony's live garrison fleets as a muster takes them, largest first, up to count, with no
	 * availability gate (IncursionManager.stagedSpares weighs every fleet of the hive itself).
	 */
	public static List<MusterFleet> peekColony(MarketAPI market, int count) {
		List<MusterFleet> out = new ArrayList<MusterFleet>();
		musterFrom(market, count, 0, false, new ArrayList<Integer>(), null, out, true);
		return out;
	}

	/**
	 * Musters count of ONE colony's largest live garrison fleets whatever its availability reads (a staged
	 * plan's entry, IncursionManager.launchStrike: the plan weighed them, peekColony): the expedition size
	 * of every swarm taken, their fleet points into fpOut[0].
	 */
	public static List<Integer> consumeFromColony(MarketAPI market, int count, float[] fpOut) {
		List<Integer> out = new ArrayList<Integer>();
		musterFrom(market, count, 0, true, out, fpOut, null, true);
		return out;
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
		// a held prong's earmarked fleets are its from every colony of the system, whatever each one's own
		// availability reads today (IncursionManager.launchingEarmarkCovers)
		boolean earmarked = IncursionManager.launchingEarmarkCovers(market);
		for (MarketAPI curr : launchPool(market)) {
			int share = Math.min(count - taken, earmarked ? countLiveGarrison(curr.getId()) : ownAvailableForLaunch(curr));
			if (share > 0) {
				int got = musterFrom(curr, share, 0, despawn, mustered, fpOut, fleetsOut, earmarked);
				taken += got;
				if (curr == market) ownTaken = got;
			}
			if (taken >= count) return mustered;
		}
		// a peek has not removed what it counted from the staging colony: skip those
		musterFrom(market, count - taken, despawn ? 0 : ownTaken, despawn, mustered, fpOut, fleetsOut, earmarked);
		return mustered;
	}

	/**
	 * Takes up to n of the colony's largest live garrison fleets, after the
	 * first skip: a size per swarm into out, a MusterFleet per fleet into
	 * fleetsOut, their fleet points into fpOut[0] (despawned when despawn).
	 * Returns the fleets taken.
	 */
	protected static int musterFrom(MarketAPI market, int n, int skip, boolean despawn, List<Integer> out,
			float[] fpOut, List<MusterFleet> fleetsOut, boolean all) {
		if (n <= 0) return 0;
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(market.getId());
		// the fleets a launch may take, largest first (launchOrder: all of them for an earmarked
		// prong, else those that fit the spare - the same list ownAvailableForLaunch counted)
		List<CampaignFleetAPI> alive = launchOrder(market, all);
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
		// billed reach (ThreatReach): any distance, the nearest forge first - the
		// cheapest passage and the shortest days away
		boolean billed = ThreatReach.enabled();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.spreadMinSize()) continue;
			if (!hasReadyForge(market)) continue;
			if (!hasOperationalNexus(market)) continue;
			StarSystemAPI system = market.getStarSystem();
			if (system == null) continue;
			if (posture && ThreatIncConfig.posturePressedForgesHome() && ThreatPosture.pressed(system)) continue;
			// the wave's substance is a mustered Defense Swarm: the colony (or a
			// sibling in its system, launchPool) must have one to spare above
			// its defensive reserve
			int spare = garrisonAvailableForLaunch(market);
			if (spare < 1) continue;
			if (requireStable ? !isStableForExpansion(market) : !canProjectFleets(market)) continue;
			float d = Misc.getDistanceLY(system.getLocation(), target.getLocation());
			// the old radius: a wave only as far as the fuel the hive network
			// delivers to this colony carries it. Billed, the stocks pay the way
			// (launchColonizationWave)
			if (!billed && d > fuelRangeLY(market)) continue;
			// a forge whose system can pay the founding goes before one that
			// cannot (2026-09-29, ti-h8e: the swarm-richest forge was picked,
			// failed its bill, and the claim waited months with 70k FP banked)
			boolean pays = poolableFP(market) >= foundingFP(FOUNDING_CORE_STRUCTURES + 1);
			boolean better;
			if (best != null && pays != bestPays) better = pays;
			else if (billed) better = d != bestDist ? d < bestDist : spare > bestSpare;
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
	 * Why a colony can send no wave now - pickForgeSource's gates in its order, with
	 * the figures of the one that failed - or null when it could: the spread log.
	 */
	public static String forgeSourceBlock(MarketAPI market, boolean requireStable) {
		if (market.getSize() < ThreatIncConfig.spreadMinSize()) return "size " + market.getSize();
		if (!hasReadyForge(market)) return "no ready forge";
		if (!hasOperationalNexus(market)) return "nexus down";
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return "no system";
		if (ThreatPosture.enabled() && ThreatIncConfig.posturePressedForgesHome() && ThreatPosture.pressed(system)) {
			return "pressed";
		}
		if (garrisonAvailableForLaunch(market) < 1) {
			float held = ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId()));
			float spare = ThreatPosture.launchSpareFP(market);
			return "no swarm to spare (garrison " + countLiveGarrison(market.getId())
					+ ", reserve " + garrisonReserve(market)
					+ ", held " + (int) held + " of want " + (int) ThreatPosture.wantFP(market)
					+ (ThreatPosture.regrowing(market, held) ? ", regrowing" : "")
					+ ", reserve FP " + (int) ThreatPosture.minimumFP(market) + ", fleets " + fleetFPs(market)
					+ ", pressure spare " + (spare == Float.MAX_VALUE ? "none" : String.valueOf((int) spare)) + ")";
		}
		if (requireStable ? !isStableForExpansion(market) : !canProjectFleets(market)) return "unstable";
		return null;
	}

	/** The colony's live garrison fleets' FP, largest first, for a log line. */
	protected static String fleetFPs(MarketAPI market) {
		StringBuilder b = new StringBuilder("[");
		for (CampaignFleetAPI curr : launchOrder(market, true)) {
			b.append(b.length() > 1 ? " " : "").append((int) curr.getFleetPoints());
		}
		return b.append("]").toString();
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

	/**
	 * Fleet points this many founding structures cost (threatinc_foundingFPPerStructure
	 * each); none while structures cost supplies (ThreatBuildCost) - fleet
	 * points are for hulls, and a founding's structures come with what a
	 * Seeding Swarm carries (ThreatFuel.loadFounding), as a forward base's do.
	 */
	public static float foundingFP(int structures) {
		if (ThreatBuildCost.enabled()) return 0f;
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

	/** Market id -> the supplies its waiting build costs (buyWaitingStructures tries again once the stock holds them). */
	public static final String KEY_BUILD_WAITING_SUPPLIES = "threatinc_buildWaitingSupplies";

	/**
	 * Whether the structure can be paid for: from the hive's supplies stock
	 * while structures cost supplies (ThreatBuildCost), else from payerId's FP
	 * bank. If not, the colony's build waits (flagged). It notes no shortage
	 * (2026-10-01): an optional build the stock cannot pay - Orbital Works,
	 * Heavy Batteries - is no reason for a forge; tryBuildLink books the
	 * needed ones as demand (ThreatFuel.heldBuild). A null payer builds free.
	 */
	protected static boolean affordStructure(MarketAPI market, String payerId, String industryId) {
		if (payerId == null) return true;
		if (ThreatBuildCost.enabled()) {
			float cost = ThreatBuildCost.supplies(industryId);
			if (ThreatFuel.stock(Commodities.SUPPLIES) >= cost) return true;
			buildWaiting().put(market.getId(), true);
			ThreatIncData.map(KEY_BUILD_WAITING_SUPPLIES).put(market.getId(), cost);
			// the shortage's answer is paid first (ThreatFuel.reserved): a forge the stock cannot pay while
			// supplies are not in surplus has its price set aside from the fleets' and colonies' draws
			if (industryId.equals(ThreatFuel.producerId(Commodities.SUPPLIES)) && !ThreatFuel.surplus(Commodities.SUPPLIES)) {
				ThreatFuel.reserve(market.getId(), cost);
			}
			// passage off: the old rule, an unpaid build is a month's shortage
			if (!ThreatFuel.planned()) ThreatFuel.noteShort(Commodities.SUPPLIES);
			return false;
		}
		float cost = foundingFP(1);
		if (cost <= 0f || fpBank(payerId) >= cost) return true;
		buildWaiting().put(market.getId(), true);
		return false;
	}

	/**
	 * Adds the structure if it is paid for (affordStructure); false if it
	 * waits. Paid in supplies it is built over its vanilla build time
	 * (startBuilding); a free one (null payer: save heals, the debug war)
	 * stands at once.
	 */
	protected static boolean buyStructure(MarketAPI market, String industryId, String payerId) {
		if (!affordStructure(market, payerId, industryId)) return false;
		boolean supplies = payerId != null && ThreatBuildCost.enabled();
		if (supplies) {
			ThreatFuel.pay(Commodities.SUPPLIES, ThreatBuildCost.supplies(industryId), "build");
		} else if (payerId != null) {
			drawFP(payerId, foundingFP(1));
		}
		market.addIndustry(industryId);
		Industry ind = market.getIndustry(industryId);
		if (supplies && ind != null && ThreatBuildCost.buildDays(industryId) > 0f) ind.startBuilding();
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
		// a reservation outlives its wait only until here (ThreatFuel.reserve): a world bought, lost or planned away
		for (String id : new ArrayList<String>(ThreatFuel.reservedMap().keySet())) {
			if (!RESERVE_CONVERSION.equals(id) && !buildWaiting().containsKey(id)) ThreatFuel.reserve(id, 0f);
		}
		if (buildWaiting().isEmpty()) return;
		for (String id : new ArrayList<String>(buildWaiting().keySet())) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(id);
			if (market == null) {
				buildWaiting().remove(id);
				ThreatFuel.reserve(id, 0f);
				continue;
			}
			if (ThreatBuildCost.enabled()) {
				Object cost = ThreatIncData.map(KEY_BUILD_WAITING_SUPPLIES).get(id);
				if (cost instanceof Float && ThreatFuel.stock(Commodities.SUPPLIES) < (Float) cost) continue;
			} else if (fpBank(id) < foundingFP(1)) {
				continue;
			}
			int had = market.getIndustries().size();
			planHiveEconomy(market);
			if (market.getIndustries().size() != had) {
				ThreatFuel.reserve(id, 0f);
				ThreatIncConfig.log("Hive planner: waiting build bought at " + market.getName() + " ("
						+ (int) fpBank(id) + " FP banked, " + (int) ThreatFuel.stock(Commodities.SUPPLIES)
						+ " supplies in stock)");
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
	 * The escort tier of a wave the colony fabricates: a fabricated wave is as
	 * strong as the colony that built it - tier scales with the source's size,
	 * upgraded by a thriving hull economy, downgraded by a strained or starved
	 * one, the same shipSupplyMult lever that throttles garrisons and strikes.
	 * A strained hive's expeditions genuinely suck: below-nominal drops a tier,
	 * badly starved drops two.
	 */
	protected static int seedingEscort(MarketAPI source) {
		int size = source.getSize();
		int escortIdx = size >= 8 ? 2 : size >= 6 ? 1 : 0;
		float mult = shipSupplyMult(source);
		if (mult >= 0.9f && size >= 8) escortIdx = 3;
		if (mult < STABLE_SHIP_SUPPLY_MULT && escortIdx > 0) escortIdx--;
		if (mult < 0.4f && escortIdx > 0) escortIdx--;
		return Math.max(0, Math.min(3, escortIdx));
	}

	/**
	 * Fuel a founding from the colony at the target system takes from the
	 * stock: the founding's own (ThreatFuel.foundingCost) and the wave's way
	 * out, as launchColonizationWave bills it.
	 */
	public static float foundingFuel(MarketAPI source, StarSystemAPI target) {
		int[] spec = { 1, seedingEscort(source) };
		return ThreatFuel.foundingCost()[1] + ThreatFuel.passage(swarmCostEstimate(ThreatFleetComposer.JOB_SEEDING, spec),
				ThreatFuel.ly(source.getStarSystem(), target), false);
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
			escortIdx = seedingEscort(source);
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
			// ...and the supplies it burns on the way out the colonies' spare and the
			// stock above its own kit (ThreatReach)
			float waveDays = ThreatReach.daysAway(ThreatFuel.ly(source.getStarSystem(), targetSystem), false, 0f);
			if (!ThreatReach.canSustain(swarmCostEstimate(ThreatFleetComposer.JOB_SEEDING, spec), waveDays)) {
				ThreatIncConfig.logQuiet("wavewait:" + source.getId(), "Seeding Swarm from " + source.getName()
						+ " held: the colonies leave " + (int) ThreatReach.spare() + " supplies a month and "
						+ (int) ThreatReach.freeStock() + " in stock for fleets away");
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
			float waveLY = ThreatFuel.ly(source.getStarSystem(), targetSystem);
			float fuel = ThreatFuel.passage(fleet.getFleetPoints(), waveLY, false);
			ThreatFuel.pay(Math.min(ThreatFuel.stock(), fuel));
			ThreatFuel.loadFounding(fleet);
			ThreatReach.commit(fleet.getFleetPoints());
			ThreatReach.note("wave", waveLY);
			// under size upkeep a wave's price is its cargo and its swarm: the
			// forge that sends it keeps working (docs/hive-economy.md "Size upkeep")
			float retool = ThreatColonyUpkeep.enabled() ? 0f : retoolForge(source, fleet.getFleetPoints());
			ThreatIncConfig.log("Seeding Swarm from " + source.getName() + " to " + targetSystem.getName()
					+ " (" + (int) waveLY + " ly): " + (int) fleet.getFleetPoints()
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
		// the stall clock (checkWaveArrivals): a bootstrap wave crosses its 3-5 ly of fringe
		fleet.getMemoryWithoutUpdate().set(WAVE_LAUNCH_KEY, Global.getSector().getClock().getTimestamp());
		fleet.getMemoryWithoutUpdate().set(WAVE_LY_KEY, source != null && source.getStarSystem() != null
				? ThreatFuel.ly(source.getStarSystem(), targetSystem) : 5f);

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
	/** When the wave sailed (checkWaveArrivals reads the stall from it; stamped at launch, or at first sight for a wave from an older save). */
	public static final String WAVE_LAUNCH_KEY = "$threatinc_waveLaunch";
	/** Light years the wave had to cross, stamped at launch. */
	public static final String WAVE_LY_KEY = "$threatinc_waveLY";
	/** Set once the wave has been re-steered after a stall. */
	public static final String WAVE_RESTEERED_KEY = "$threatinc_waveResteered";

	/** Days a wave may take: seedingWaveStallDays plus 30 a light year of its passage. */
	protected static float waveStallDays(CampaignFleetAPI fleet) {
		float ly = fleet.getMemoryWithoutUpdate().contains(WAVE_LY_KEY)
				? fleet.getMemoryWithoutUpdate().getFloat(WAVE_LY_KEY) : 5f;
		return ThreatIncConfig.seedingWaveStallDays() + 30f * ly;
	}

	/** Whether the wave has been out longer than its passage allows (waveStallDays) without founding; stamps a wave never stamped. */
	protected static boolean waveStalled(CampaignFleetAPI fleet, PlanetAPI planet) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (!mem.contains(WAVE_LAUNCH_KEY)) {
			mem.set(WAVE_LAUNCH_KEY, Global.getSector().getClock().getTimestamp());
			return false;
		}
		return Global.getSector().getClock().getElapsedDaysSince(mem.getLong(WAVE_LAUNCH_KEY)) > waveStallDays(fleet);
	}

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

			// a wave neither arrived nor dead past its passage (hw34b, 2026-10-06:
			// the bootstrap wave to Blue sat in the Ala system from war day 159
			// to 3579 with every hull, and the hive it would have given its
			// volatiles never built a fuel plant - ten years with nothing able to
			// sail): re-steered once; stalled again, a bootstrap wave is moved
			// into its system beside its planet (the Abyss's incursion, before
			// anyone could see it), a colony's wave is lost and refunded
			if (fleet.getBattle() == null && waveStalled(fleet, planet)) {
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
				if (!mem.getBoolean(WAVE_RESTEERED_KEY)) {
					mem.set(WAVE_RESTEERED_KEY, true);
					mem.set(WAVE_LAUNCH_KEY, Global.getSector().getClock().getTimestamp());
					fleet.clearAssignments();
					fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 365f,
							"seeding " + system.getNameWithLowercaseTypeShort());
					fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
					ThreatIncConfig.log("Wave re-steered: the wave to " + planet.getName() + " (" + system.getName()
							+ ") had not arrived in " + (int) waveStallDays(fleet) + " days"
							+ (fleet.getContainingLocation() == system ? ", in the system" : ", in hyperspace"));
					continue;
				}
				if (mem.getBoolean(BOOTSTRAP_WAVE_FLAG)) {
					mem.unset(WAVE_RESTEERED_KEY);
					mem.set(WAVE_LAUNCH_KEY, Global.getSector().getClock().getTimestamp());
					if (fleet.getContainingLocation() != system) {
						fleet.getContainingLocation().removeEntity(fleet);
						system.addEntity(fleet);
					}
					Vector2f at = planet.getLocation();
					fleet.setLocation(at.x + planet.getRadius() + 200f, at.y);
					fleet.clearAssignments();
					fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
					ThreatIncConfig.log("Wave unstuck: the bootstrap wave to " + planet.getName()
							+ " moved to its orbit after stalling twice");
					continue;
				}
				ThreatIncData.waveFleets().remove(planetId);
				ThreatIncData.waveTargets().remove(planetId);
				refundFounding(fleet);
				if (firstColony) ThreatIncData.clearSystem(systemId);
				retireFleet(fleet, system);
				ThreatIncConfig.log("Wave stalled: the wave to " + planet.getName() + " (" + system.getName()
						+ ") withdrawn after stalling twice");
				continue;
			}

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
				// the four structures a colony is founded with came in the wave's
				// cargo (ThreatBuildCost.foundingKit); under size upkeep its first
				// build is bought from the stock like any other (planHiveEconomy)
				String payer = paidFounding && ThreatColonyUpkeep.enabled() && ThreatBuildCost.enabled()
						? existing.getId() : null;
				MarketAPI market = foundColony(planet, 1, payer);
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

			if (ThreatColonyUpkeep.enabled()) {
				advanceFedGrowth(market, elapsedDays);
				continue;
			}

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
	 * Growth under size upkeep (ThreatColonyUpkeep, 2026-09-30): the colony's
	 * progress moves at the pace the share of its upkeep paid gives - up toward
	 * its next size at the old pace (colonyGrowthBaseDays x size) paid in full,
	 * held at the break-even share, a level starved through per
	 * starveDaysPerSize paid nothing. Progress is continuous across a size
	 * lost, so a short siege costs progress and a long one sizes, and what was
	 * lost regrows at the growth pace; the size goes SHRINK_MARGIN of a level
	 * below it, and a world at its cap banks a full level while fed. A front on
	 * the surface or saturation holds its growth, never its hunger; the
	 * Fabrication Core no longer gates it.
	 */
	protected static void advanceFedGrowth(MarketAPI market, float elapsedDays) {
		String id = market.getId();
		int size = market.getSize();
		float rate = ThreatColonyUpkeep.growthRate(ThreatColonyUpkeep.fedShare(market));
		float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size * IncursionManager.timeScale();
		float progress = ThreatIncData.growthProgressDays(id);
		if (rate > 0f) {
			if (ThreatGroundFronts.hasFront(market) || ThreatRazing.saturated(market)) return;
			int cap = maxColonySize(market);
			progress += elapsedDays * rate;
			if (size >= cap) {
				// nothing left to grow into: the fed world banks its top level
				ThreatIncData.setGrowthProgressDays(id, Math.min(daysPerLevel, progress));
				return;
			}
			if (progress >= daysPerLevel) {
				growColony(market, cap);
				return;
			}
			ThreatIncData.setGrowthProgressDays(id, progress);
			return;
		}
		if (rate == 0f) return;
		progress += elapsedDays * rate * daysPerLevel / (ThreatColonyUpkeep.starveDays() * IncursionManager.timeScale());
		float floor = -ThreatColonyUpkeep.SHRINK_MARGIN * daysPerLevel;
		if (progress >= floor || size <= 1) {
			ThreatIncData.setGrowthProgressDays(id, Math.max(size <= 1 ? 0f : floor, progress));
			return;
		}
		float below = progress / daysPerLevel;
		shrinkColony(market);
		float lower = ThreatIncConfig.colonyGrowthBaseDays() * market.getSize() * IncursionManager.timeScale();
		ThreatIncData.setGrowthProgressDays(id, Math.max(0f, (1f + below) * lower));
	}

	/** A size off a hive its upkeep starves (ThreatRazing.reduceSize's steps). */
	protected static void shrinkColony(MarketAPI market) {
		ThreatRazing.reduceSize(market, "Starved a level of");
		announce(ThreatNotice.titled("Hive Starving").good()
				.line("%s has shrunk to size %s", ThreatNotice.market(market), market.getSize()));
	}

	/** Whether the colony grows now: by the share of its upkeep paid under size upkeep, else by vitality. */
	public static boolean growing(MarketAPI market) {
		if (ThreatColonyUpkeep.enabled()) {
			return ThreatColonyUpkeep.growthRate(ThreatColonyUpkeep.fedShare(market)) > 0f;
		}
		return growthMultFor(computeHealth(market)) > 0f;
	}

	/** The colony's growth pace now, of the full pace: paid share under size upkeep (negative while it starves), else vitality's. */
	public static float growthPace(MarketAPI market) {
		if (ThreatColonyUpkeep.enabled()) return ThreatColonyUpkeep.growthRate(ThreatColonyUpkeep.fedShare(market));
		return growthMultFor(computeHealth(market));
	}

	/**
	 * Days until a starving hive loses its size at the rate it starves now;
	 * Float.MAX_VALUE for one that is not starving.
	 */
	public static float daysToLoseSize(MarketAPI market) {
		if (market == null || !ThreatColonyUpkeep.enabled() || market.getSize() <= 1) return Float.MAX_VALUE;
		float rate = growthPace(market);
		if (rate >= 0f) return Float.MAX_VALUE;
		float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * market.getSize() * IncursionManager.timeScale();
		float perDay = -rate * daysPerLevel / (ThreatColonyUpkeep.starveDays() * IncursionManager.timeScale());
		float left = ThreatIncData.growthProgressDays(market.getId()) + ThreatColonyUpkeep.SHRINK_MARGIN * daysPerLevel;
		return Math.max(0f, left) / perDay;
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
		float growthMult = growthPace(market);
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
	 * ERADICATION: the teardown of a Threat colony, by the one way it dies - a
	 * ground victory - never a timer, and never from orbit (saturation takes
	 * no size off a hive, ThreatRazing.razes). Vitality bookkeeping
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
		return threatinc.rules.HiveRules.desiredGarrison(size, low, med, high, max);
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
		boolean suppliesUpkeep = ThreatIncConfig.threatSuppliesUpkeep();
		Map<String, Float> ledgerSupplies = suppliesUpkeep ? ledgerFleetSupplies() : null;
		// the colonies' sustenance is held back from the fleets and the navy (sustenanceDue): the forge is the
		// income, the navy the expense (2026-10-08)
		float hold = suppliesUpkeep ? ThreatColonyUpkeep.sustenanceDue() : 0f;
		float fleetsSupplies = maintainColonyGarrisons(random, hiveOutput, hiveDraw, ledgerFP, suppliesUpkeep,
				ledgerSupplies, hold);
		// the colonies' size upkeep: its break-even first out of the whole stock, growth out of the production the
		// fleets away leave (their supplies a month come off the share it is taken from)
		ThreatColonyUpkeep.feed(fleetsSupplies);
		if (suppliesUpkeep) fitNavyToSpare();
	}

	/**
	 * THE NAVY FITS THE SPARE (the user, 2026-10-08, after hw73a: "it shouldnt
	 * maintain more than it can supply and use"): the hive keeps no standing
	 * navy its supplies flow cannot carry with the sends it wants. Each poll,
	 * after the feed, the spare a month (ThreatReach.spare: production less the
	 * colonies' upkeep, the fleets away and the navy at home) must cover what a
	 * send held this month on supplies needs - one founding's supplies
	 * (ThreatFuel.foundingCost) while any send was held, else 0. Short of it,
	 * standing swarms are recycled into the bank at ThreatReturns.hullShare,
	 * the smallest on station first, from the colony with the most garrison
	 * above its floor (the patrols, ThreatPosture.minimumFP, or the pressure's
	 * need, needFP, whichever is more), never a colony under attack nor one the
	 * offensive's held prongs will muster on (earmarked), until the saved
	 * charge (rate x FP a month, payNavySupplies' rate) fills the gap or
	 * nothing is above a floor. Banked FP pays no upkeep and the nexus rebuilds
	 * from it when the flow grows (maintainGarrisons' build), so the hulls are
	 * kept as FP, not fed as fleets. hw73a / hw75a (ck2): the swarm sat a year
	 * at a stock of 0 with 29-31 sends held, 145 seedings held, the home navy
	 * 13k of 69-78k a month and the fund 54-75k FP, while the humans ground it
	 * from 50 hives to 34. Knob navyFitsSpare. While the fit binds (fitBinding)
	 * the nexus builds to the same floor (maintainGarrisons), never the
	 * posture's want, or it refabricates what the fit recycled (hw77a).
	 */
	protected static void fitNavyToSpare() {
		if (!fitBinding()) return;
		float spare = ThreatReach.spare();
		float want = fitWant();
		float rate = ThreatReach.suppliesPerFP() * ThreatIncConfig.standingUpkeepMult();
		if (rate <= 0f) return;
		float gap = want - spare;
		float share = ThreatReturns.hullShare();
		int budget = 0;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) budget += countLiveGarrison(m.getId());
		for (int i = 0; i < budget && gap > 0f; i++) {
			// the colony with the most above its floor gives
			MarketAPI pick = null;
			float pickAbove = 0f;
			List<CampaignFleetAPI> pickFleets = null;
			for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
				StarSystemAPI system = m.getStarSystem();
				if (system == null || ThreatPosture.underAttack(system)) continue;
				if (ThreatOffensive.earmarked(system.getId()) > 0) continue;
				// a colony the pressure pass just reinforced is not where the fit cuts (the donors' own rule,
				// DECAY_DAYS; hw81a: swarms recycled the poll they arrived)
				if (ThreatPosture.recentlyReceived(m)) continue;
				List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(m.getId());
				float above = garrisonFP(fleets) - fitFloor(m);
				if (above <= 0f || above <= pickAbove) continue;
				// never the launch stock's fleets (launchStockFleets)
				List<CampaignFleetAPI> loose = new ArrayList<CampaignFleetAPI>(fleets);
				loose.removeAll(launchStockFleets(m));
				CampaignFleetAPI victim = smallestOnStation(loose);
				if (victim == null || victim.getFleetPoints() > above) continue;
				pick = m; pickAbove = above; pickFleets = fleets;
			}
			if (pick == null) break;
			List<CampaignFleetAPI> spareFleets = new ArrayList<CampaignFleetAPI>(pickFleets);
			spareFleets.removeAll(launchStockFleets(pick));
			CampaignFleetAPI victim = smallestOnStation(spareFleets);
			float fp = victim.getFleetPoints();
			pickFleets.remove(victim);
			victim.despawn();
			creditFP(pick, fp * share);
			gap -= fp * rate;
			Global.getSector().getPersistentData().put(KEY_FIT_TIMESTAMP, Global.getSector().getClock().getTimestamp());
			UpkeepLog log = upkeepLog(pick.getId());
			log.recycled++;
			log.recycledFP += fp;
			ThreatIncConfig.log("Navy: " + pick.getName() + " recycled a " + (int) fp + " FP swarm into the bank ("
					+ (int) (fp * share) + " FP back; spare " + (int) spare + " of " + (int) want + " a month wanted, "
					+ (int) ThreatFuel.heldThisMonth() + " send(s) held, " + (int) Math.max(0f, gap) + " still to save)");
		}
	}

	/** maintainGarrisons' loop over the colonies; returns the supplies a month of their fleets away. */
	protected static float maintainColonyGarrisons(Random random, float hiveOutput, float hiveDraw,
			Map<String, Float> ledgerFP, boolean suppliesUpkeep, Map<String, Float> ledgerSupplies, float hold) {
		float fleetsSupplies = 0f;
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
			// with supplies upkeep on, only the garrison at home pays FP; the
			// fleets away pay supplies (paySupplies)
			float upkeep = upkeepPerDay(suppliesUpkeep ? garrisonFP(fleets)
					: ownedFleetFP(market, fleets) + (outFP != null ? outFP : 0f));
			accrueFabrication(market, income, upkeep);
			// a bank that cannot carry its fleets gives some back (organs up or not)
			recycleForUpkeep(market, fleets, income, upkeep);
			if (suppliesUpkeep) {
				Float outSupplies = ledgerSupplies.get(marketId);
				float away = awayFleetSupplies(market) + (outSupplies != null ? outSupplies : 0f);
				fleetsSupplies += away;
				float days = paySupplies(market, away, hold);
				// the navy above the patrols pays its supplies, as a faction's built hulls do
				payNavySupplies(market, fleets, days, hold);
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
				// while the navy fits the spare (fitNavyToSpare, 2026-10-08) the want is that fit's floor,
				// not the posture's: hw77a rebuilt from the bank what the fit had just recycled, month for
				// month (149 recycled / 156 fabricated, 158 / 136), 4,600 swarms churned through the bank
				// (hw79a: gated on the fit binding NOW it still churned a build and a recycle a poll - the feed
				// records the spare before the fit recycles, so the next poll read it above the want - hence
				// fitActive: the floor holds for a month after the fit last recycled)
				belowFloor = fleets.size() + away < 1
						|| (fitActive() ? ownedFleetFP(market, fleets) < fitFloor(market)
								: ThreatPosture.wantsGrowth(market, ownedFleetFP(market, fleets)));
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
			// (not while structures cost supplies: they wait on the hive's stock, not this bank)
			if (!belowFloor && !ThreatBuildCost.enabled() && buildWaiting().containsKey(marketId)) continue;
			// nothing past the want: the bank keeps the rest for waves, strikes
			// and foundings
			if (posture && !belowFloor) {
				if (ownAvailableForLaunch(market) == 0) splitLaunchSwarm(market, fleets, random);
				continue;
			}
			// (2026-10-06: the garrison is vanilla's patrols - the Nexus's table,
			// shrinking with the market's shortages as a human world's does - and
			// pays no supplies; one evening charged it and gated its growth on the
			// hive's supplies surplus, hw36-37, docs/hive-stocks-and-upkeep.md)
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
		return fleetsSupplies;
	}

	/** The spare a month the navy's fit wants (fitNavyToSpare): one founding's supplies while any send was held this month, else 0. */
	protected static float fitWant() {
		// a send held on fuel alone asks nothing of the navy (hw136: 49 sends held on an empty fuel stock
		// had the fit recycle the launch swarms the colonies had just carved for their waves)
		return ThreatFuel.heldOnSuppliesThisMonth() > 0 ? ThreatFuel.foundingCost()[0] : 0f;
	}

	/** Whether the navy's fit binds now: the knob on, upkeep on, and the spare a month short of fitWant. */
	protected static boolean fitBinding() {
		return ThreatIncConfig.navyFitsSpare() && ThreatColonyUpkeep.enabled() && ThreatReach.spare() < fitWant();
	}

	/** Game-clock timestamp of the fit's last recycle (fitNavyToSpare). */
	public static final String KEY_FIT_TIMESTAMP = "threatinc_fitTimestamp";

	/** Days the nexus builds only to the fit's floor after the fit last recycled (fitActive). */
	public static final float FIT_HOLD_DAYS = 30f;

	/**
	 * Whether the nexus builds only to the fit's floor (maintainGarrisons): the knob and upkeep on, and
	 * a send held this month or the fit recycled within FIT_HOLD_DAYS. Hysteresis, not the binding
	 * itself: the feed records the spare before the fit recycles, so gated on fitBinding alone the
	 * builder read the spare above the want next poll and grew what the fit had recycled, a build and
	 * a recycle a poll at the same hive (hw79a: 255 recycled / 260 built in a month).
	 */
	public static boolean fitActive() {
		if (!ThreatIncConfig.navyFitsSpare() || !ThreatColonyUpkeep.enabled()) return false;
		if (fitWant() > 0f) return true;
		Object ts = Global.getSector().getPersistentData().get(KEY_FIT_TIMESTAMP);
		return ts instanceof Long && Global.getSector().getClock().getElapsedDaysSince((Long) ts) < FIT_HOLD_DAYS;
	}

	/**
	 * The garrison the navy's fit leaves a colony, and the colony's whole want while the fit is active
	 * (ThreatPosture.wantFP, so the nexus, the pressure pass and the launch gates all read it): the
	 * patrols (ThreatPosture.minimumFP) or the pressure's need (needFP), whichever is more, plus the
	 * strike a pressing stance stages there (ThreatStance.extraWantFP - a strike staged is the navy used).
	 */
	public static float fitFloor(MarketAPI market) {
		// a forge colony's launch stock is navy about to be used - its next wave (hw136: the fit recycled
		// the swarm splitLaunchSwarm had carved, the smallest on station, the poll after)
		return Math.max(ThreatPosture.minimumFP(market), ThreatPosture.needFP(market)) + ThreatStance.extraWantFP(market)
				+ ThreatPosture.stockFP(market);
	}

	/**
	 * The fleets that are a colony's launch stock in substance: launchOrder's, largest first, until
	 * their fleet points reach ThreatPosture.stockFP. Never the navy fit's victim (fitNavyToSpare).
	 */
	protected static List<CampaignFleetAPI> launchStockFleets(MarketAPI market) {
		List<CampaignFleetAPI> out = new ArrayList<CampaignFleetAPI>();
		float stock = ThreatPosture.stockFP(market);
		if (stock <= 0f) return out;
		float fp = 0f;
		for (CampaignFleetAPI curr : launchOrder(market, false)) {
			if (fp >= stock) break;
			out.add(curr);
			fp += curr.getFleetPoints();
		}
		return out;
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
		return poolSystemBanks(source, bill, "Founding");
	}

	/** poolSystemBanks for a bill of this kind, named in the log ("Founding bill at ...", "Swarm Bastion bill at ..."). */
	public static boolean poolSystemBanks(MarketAPI source, float bill, String what) {
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
		ThreatIncConfig.log(what + " bill at " + source.getName() + ": " + (int) bill + " FP, "
				+ (int) short0 + " pooled from " + from);
		return true;
	}

	public static final String KEY_STRIKE_FUND = "threatinc_strikeFund";

	/**
	 * THE STRIKE FUND (a trial of 2026-10-05, after hw20: the banks hold
	 * nothing, production becoming garrison as it is banked, so a strike paid
	 * from them stayed two swarms): strikeFundShare of every colony's
	 * production is set aside, hive-wide, and staged strikes are built from
	 * it alone (IncursionManager.stagedPlan). Fleet points.
	 */
	public static float strikeFund() {
		Object v = Global.getSector().getPersistentData().get(KEY_STRIKE_FUND);
		return v instanceof Float ? (Float) v : 0f;
	}

	public static void addStrikeFund(float fp) {
		Global.getSector().getPersistentData().put(KEY_STRIKE_FUND, Math.max(0f, strikeFund() + fp));
	}

	/** Fleet points a month the strike fund gains at today's fabrication: strikeFundShare of every live colony's rate (ThreatOffensive's saving horizon). */
	public static float strikeFundPerMonth() {
		float share = Math.max(0f, Math.min(1f, ThreatIncConfig.strikeFundShare()));
		if (share <= 0f) return 0f;
		return hiveFabricationPerMonth() * share;
	}

	/** Fleet points a month every live colony fabricates at today's rate: what the forges replace (ThreatStance.losingPressure). */
	public static float hiveFabricationPerMonth() {
		float output = hiveShipOutput(), draw = hiveNexusDraw(), perDay = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			perDay += Math.max(0f, fabricationRatePerDay(m, output, draw));
		}
		return perDay * 30f;
	}

	/** Moves the bill from the strike fund to the staging colony's bank, which the launch then draws it from; false when the fund is short. */
	public static boolean spendStrikeFund(MarketAPI staging, float bill) {
		if (staging == null || bill > strikeFund() + 0.5f) return false;
		addStrikeFund(-bill);
		creditFP(staging, bill);
		return true;
	}

	/** Every live colony of the hive that gives to a staged strike's bill: banked, and holding its garrison (ThreatPosture.regrowing). */
	protected static List<MarketAPI> hiveGivers(MarketAPI source) {
		List<MarketAPI> givers = new ArrayList<MarketAPI>();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(systemId)) {
				if (m == source || bankedFP(m) <= 0f) continue;
				if (ThreatPosture.regrowing(m, ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId())))) continue;
				givers.add(m);
			}
		}
		return givers;
	}

	/** What the whole hive could put toward a staged strike at the source: its bank and every giver's (hiveGivers). */
	public static float hivePoolableFP(MarketAPI source) {
		if (source == null) return 0f;
		float fp = Math.max(0f, bankedFP(source));
		for (MarketAPI m : hiveGivers(source)) fp += bankedFP(m);
		return fp;
	}

	/**
	 * poolSystemBanks across the whole hive, for a staged strike (the user,
	 * 2026-10-05: built from the whole hive's bank, as the humans pay a siege
	 * from their pooled means): the richest givers first.
	 */
	public static boolean poolHiveBanks(MarketAPI source, float bill, String what) {
		if (source == null) return false;
		float short0 = bill - bankedFP(source);
		if (short0 <= 0f) return true;
		List<MarketAPI> givers = hiveGivers(source);
		float can = 0f;
		for (MarketAPI m : givers) can += bankedFP(m);
		if (can < short0) return false;
		java.util.Collections.sort(givers, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(bankedFP(b), bankedFP(a));
			}
		});
		float left = short0;
		int n = 0;
		for (MarketAPI m : givers) {
			if (left <= 0f) break;
			float take = Math.min(left, bankedFP(m));
			chargeFP(m, take);
			creditFP(source, take);
			left -= take;
			n++;
		}
		ThreatIncConfig.log(what + " bill at " + source.getName() + ": " + (int) bill + " FP, "
				+ (int) short0 + " pooled from " + n + " colonies of the hive");
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
		return threatinc.rules.HiveRules.fabricationRatePerDay(nexusDraw(market), hiveOutput, hiveDraw,
				fpPerShipUnit30d());
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
		// the strike fund's share of the production never reaches the colony's bank (strikeFundShare)
		float fund = fp > 0f ? fp * Math.max(0f, Math.min(1f, ThreatIncConfig.strikeFundShare())) : 0f;
		if (fund > 0f) addStrikeFund(fund);
		if (fp - fund > 0f) creditFP(market, fp - fund);
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
		return threatinc.rules.HiveRules.upkeepPerDay(fleetFP, ThreatIncConfig.garrisonUpkeepPerMonth());
	}

	/** Fleet points of the garrison on station (its list alone). */
	public static float garrisonFP(List<CampaignFleetAPI> fleets) {
		float fp = 0f;
		for (CampaignFleetAPI curr : fleets) {
			if (curr != null && curr.isAlive()) fp += curr.getFleetPoints();
		}
		return fp;
	}

	// (2026-09-30, user's call.) Threat fleets away from home - raiders,
	// reinforcements in transit, strikes and everything else on the ledger -
	// burn their hulls' vanilla supplies a month from the hive's stock
	// (ThreatFuel), as the factions' fleets on the war's orders do (ThreatUpkeep).
	// A garrison at home is the hive's patrol: vanilla feeds patrols from the
	// market's own supplies demand, which a hive's structures already take
	// (h30a: 208 units demanded of 178 made), so it keeps the FP upkeep above.
	// h29a charged garrisons too: 50k FP burned 29-39k a month against 4.5-6.75k
	// banked and recycling cut the swarm to 8-17k FP. What the stock cannot pay
	// turns the colony's raiders home and its strikes back (starveAway).

	/** Supplies a month of the colony's fleets away but not on the ledger: raiders and reinforcements in transit. */
	public static float awayFleetSupplies(MarketAPI market) {
		float s = 0f;
		String id = market.getId();
		for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(id)) {
			if (r.fleet != null && r.fleet.isAlive()) s += ThreatFrontlines.maintenancePerMonth(r.fleet);
		}
		for (CampaignFleetAPI curr : ThreatIncData.reinforcementFleets().values()) {
			if (curr == null || !curr.isAlive()) continue;
			if (id.equals(curr.getMemoryWithoutUpdate().getString(REINFORCE_TARGET_KEY))) {
				s += ThreatFrontlines.maintenancePerMonth(curr);
			}
		}
		return s;
	}

	/**
	 * The census's snapshot of the hive's fleets away by kind - every live
	 * fleet on a ledger that is not a garrison (ledgerFleetSupplies' set), the
	 * abstract strikes, the raiders and the reinforcements in transit - as
	 * "Away fleets: kind n, F FP, S/mo; ..." (2026-10-08: what the supplies
	 * away went on could not be read from the log).
	 */
	public static String awayFleetsLine() {
		Map<String, float[]> by = new java.util.TreeMap<String, float[]>();
		java.util.Set<String> seen = new java.util.HashSet<String>();
		for (com.fs.starfarer.api.campaign.LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI curr : loc.getFleets()) {
				if (!curr.isAlive()) continue;
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem = curr.getMemoryWithoutUpdate();
				if (mem.getString(LEDGER_HOME_KEY) == null || mem.contains(GARRISON_FLAG)) continue;
				seen.add(curr.getId());
				tally(by, curr.getName(), curr.getFleetPoints(), ThreatFrontlines.maintenancePerMonth(curr));
			}
		}
		for (Object o : IncursionManager.getStrikeList()) {
			if (!(o instanceof ThreatStrikeFGI)) continue;
			float fp = ((ThreatStrikeFGI) o).abstractFP();
			if (fp > 0f) tally(by, "strike (abstract)", fp, ThreatReach.suppliesPerMonth(fp));
		}
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(market.getId())) {
				if (r.fleet == null || !r.fleet.isAlive() || !seen.add(r.fleet.getId())) continue;
				tally(by, "raider", r.fleet.getFleetPoints(), ThreatFrontlines.maintenancePerMonth(r.fleet));
			}
		}
		for (CampaignFleetAPI curr : ThreatIncData.reinforcementFleets().values()) {
			if (curr == null || !curr.isAlive() || !seen.add(curr.getId())) continue;
			tally(by, "reinforcement", curr.getFleetPoints(), ThreatFrontlines.maintenancePerMonth(curr));
		}
		StringBuilder s = new StringBuilder("Away fleets:");
		float fp = 0f, sup = 0f;
		for (Map.Entry<String, float[]> e : by.entrySet()) {
			float[] v = e.getValue();
			fp += v[1]; sup += v[2];
			s.append(' ').append(e.getKey()).append(' ').append((int) v[0]).append(", ").append((int) v[1])
					.append(" FP, ").append((int) v[2]).append("/mo;");
		}
		s.append(" total ").append((int) fp).append(" FP, ").append((int) sup).append("/mo");
		return s.toString();
	}

	private static void tally(Map<String, float[]> by, String kind, float fp, float perMonth) {
		if (kind == null) kind = "?";
		float[] v = by.get(kind);
		if (v == null) by.put(kind, v = new float[3]);
		v[0]++; v[1] += fp; v[2] += perMonth;
	}

	/** Market id -> supplies a month of the fleets bound to its ledger (ledgerFleetFP's set). */
	protected static Map<String, Float> ledgerFleetSupplies() {
		Map<String, Float> out = new java.util.HashMap<String, Float>();
		for (com.fs.starfarer.api.campaign.LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI curr : loc.getFleets()) {
				if (!curr.isAlive()) continue;
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem = curr.getMemoryWithoutUpdate();
				String home = mem.getString(LEDGER_HOME_KEY);
				if (home == null || mem.contains(GARRISON_FLAG)) continue;
				Float had = out.get(home);
				out.put(home, (had != null ? had : 0f) + ThreatFrontlines.maintenancePerMonth(curr));
			}
		}
		// a strike far from the player flies as an abstract route with no fleets
		// to weigh (2026-09-30: it burned nothing): its swarms burn what the hive's
		// hulls burn a fleet point (ThreatReach.suppliesPerFP) from its muster on
		for (Object o : IncursionManager.getStrikeList()) {
			if (!(o instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) o;
			float fp = strike.abstractFP();
			String home = strike.ledgerHome;
			if (fp <= 0f || home == null) continue;
			Float had = out.get(home);
			out.put(home, (had != null ? had : 0f) + ThreatReach.suppliesPerMonth(fp));
		}
		return out;
	}

	public static final String KEY_SUPPLIES_AT = "threatinc_hiveSuppliesAt";

	public static final String KEY_SUPPLIES_OWED = "threatinc_hiveSuppliesOwed";

	/**
	 * Pays the supplies of the colony's fleets away ({@code perMonth}) for the
	 * days since its last payment from the hive's stock. What the stock cannot
	 * pay is demand it did not meet (ThreatFuel.noteDemand: the planner builds a
	 * forge once the stock would run dry, ThreatFuel.runsDry) and owed; once a
	 * month's worth is owed the colony's raiders turn home and its strikes back
	 * (starveAway), as a faction's fleets out of supplies do (ThreatUpkeep.starve).
	 * A month paid in full clears the debt. Returns the days charged (0 on the
	 * first call).
	 */
	protected static float paySupplies(MarketAPI market, float perMonth, float hold) {
		String id = market.getId();
		Map<String, Object> at = ThreatIncData.map(KEY_SUPPLIES_AT);
		Object last = at.get(id);
		long now = Global.getSector().getClock().getTimestamp();
		at.put(id, now);
		if (!(last instanceof Long)) return 0f;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) last);
		if (days <= 0f || perMonth <= 0f) return days;
		float want = perMonth * days / 30f;
		// a commitment pays from the whole stock, the planner's reserve included (hw61b: drawn on free, 30 swarms
		// were lost to unpaid upkeep for a forge's 5k; the reserve bites sustenance, growth, foundings and new trips)
		// - less the colonies' sustenance due (hold, ThreatColonyUpkeep.sustenanceDue), which is paid before it
		float paid = Math.min(want, Math.max(0f, ThreatFuel.stock(Commodities.SUPPLIES) - hold));
		ThreatFuel.pay(Commodities.SUPPLIES, paid, "away");
		float unpaid = want - paid;
		Map<String, Object> owedMap = ThreatIncData.map(KEY_SUPPLIES_OWED);
		if (unpaid <= 0f) {
			owedMap.remove(id);
			return days;
		}
		ThreatFuel.noteDemand(Commodities.SUPPLIES, unpaid);
		if (!ThreatFuel.planned()) ThreatFuel.noteShort(Commodities.SUPPLIES);
		Object had = owedMap.get(id);
		float owed = (had instanceof Float ? (Float) had : 0f) + unpaid;
		if (owed < perMonth) {
			owedMap.put(id, owed);
			return days;
		}
		owedMap.remove(id);
		starveAway(market, owed);
		return days;
	}

	/** Persistent: market id -> supplies its navy above the patrols owes (payNavySupplies). */
	public static final String KEY_NAVY_OWED = "threatinc_hiveNavyOwed";

	/** The colony's garrison FP above its patrols (ThreatPosture.minimumFP): its launch stock and spare, the hive's built navy. */
	public static float navyFP(MarketAPI market, List<CampaignFleetAPI> fleets) {
		return Math.max(0f, garrisonFP(fleets) - ThreatPosture.minimumFP(market));
	}

	/**
	 * The navy above the patrols pays its supplies (the user, 2026-10-07: "might
	 * as well charge them supplies if threat has surplus anyway") - the hive's
	 * counterpart of a faction's built hulls at home (ThreatHulls.maintain):
	 * navyFP at the swarm's maintenance per FP x standingUpkeepMult, for the
	 * days paySupplies charged, from the hive stock. The unpaid is demand for
	 * the planner and owed; once the owed reaches the smallest swarm's month
	 * that swarm is lost, never below the patrols. The patrols themselves are
	 * vanilla's to keep (one evening charged them, hw36-37).
	 */
	protected static void payNavySupplies(MarketAPI market, List<CampaignFleetAPI> fleets, float days, float hold) {
		float rate = ThreatReach.suppliesPerFP() * ThreatIncConfig.standingUpkeepMult();
		float navy = navyFP(market, fleets);
		if (days <= 0f || rate <= 0f || navy <= 0f) return;
		String id = market.getId();
		float want = navy * rate * days / 30f;
		// a commitment: the whole stock less the colonies' sustenance due (paySupplies)
		float paid = Math.min(want, Math.max(0f, ThreatFuel.stock(Commodities.SUPPLIES) - hold));
		ThreatFuel.pay(Commodities.SUPPLIES, paid, "navy");
		UpkeepLog log = upkeepLog(id);
		log.navyWanted += want;
		log.navyPaid += paid;
		float unpaid = want - paid;
		Map<String, Object> owedMap = ThreatIncData.map(KEY_NAVY_OWED);
		if (unpaid <= 0f) {
			owedMap.remove(id);
			return;
		}
		ThreatFuel.noteDemand(Commodities.SUPPLIES, unpaid);
		if (!ThreatFuel.planned()) ThreatFuel.noteShort(Commodities.SUPPLIES);
		Object had = owedMap.get(id);
		float owed = (had instanceof Float ? (Float) had : 0f) + unpaid;
		int budget = fleets.size();
		for (int i = 0; i < budget; i++) {
			CampaignFleetAPI victim = smallestOnStation(fleets);
			if (victim == null) break;
			float fp = victim.getFleetPoints();
			if (fp <= 0f || owed < fp * rate || fp > navyFP(market, fleets)) break;
			owed -= fp * rate;
			fleets.remove(victim);
			victim.despawn();
			log.starved++;
			log.starvedFP += fp;
			ThreatIncConfig.log("Upkeep: " + market.getName() + " lost a " + (int) fp + " FP swarm for " + (int) (fp * rate)
					+ " supplies of navy upkeep unpaid (" + (int) owed + " still owed, " + fleets.size() + " on station)");
		}
		if (owed > 0f) owedMap.put(id, owed);
		else owedMap.remove(id);
	}

	/** The colony's fleets away owe a month of supplies: its raiders turn home, its strikes back. */
	protected static void starveAway(MarketAPI market, float owed) {
		String id = market.getId();
		int raiders = 0, strikes = 0;
		for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(id)) {
			if (r.fleet == null || !r.fleet.isAlive() || r.returning) continue;
			SectorEntityToken planet = market.getPrimaryEntity();
			if (planet == null) continue;
			ThreatRaiders.sendHome(r, planet);
			raiders++;
		}
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted() || strike.isSucceeded()) continue;
			if (strike.getParams() == null || strike.getParams().source == null
					|| !id.equals(strike.getParams().source.getId())) continue;
			strike.abort();
			strikes++;
		}
		if (strikes > 0) {
			announce(ThreatNotice.titled("Swarm Out of Supplies").good()
					.line("The Threat strike from %s turns back", ThreatNotice.market(market))
					.line("Its fleets owe %s supplies", ThreatNotice.hl(Misc.getWithDGS((int) owed))));
		}
		ThreatIncConfig.log("Upkeep: " + market.getName() + " owes " + (int) owed + " supplies for its fleets away; "
				+ raiders + " raiders home, " + strikes + " strikes back");
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
		// the fleets a held prong of the offensive will muster are its, not the bank's to scrap (earmarked)
		if (market.getStarSystem() != null && ThreatOffensive.earmarked(market.getStarSystem().getId()) > 0) return;
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
		int starved;
		float starvedFP;
		float navyWanted, navyPaid;
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
			if (log.recycled > 0 || log.starved > 0 || moved) {
				MarketAPI market = ThreatIncData.resolveColonyMarket(entry.getKey());
				ThreatIncConfig.log("Upkeep month: " + (market != null ? market.getName() : entry.getKey())
						+ " paid " + (int) log.charged + " FP"
						+ (log.recycled > 0 ? ", recycled " + log.recycled + " swarm(s) of " + (int) log.recycledFP
								+ " FP" : "")
						+ (log.navyWanted > 0f ? ", navy upkeep " + (int) log.navyPaid + " of " + (int) log.navyWanted
								+ " supplies" : "")
						+ (log.starved > 0 ? ", lost " + log.starved + " swarm(s) of " + (int) log.starvedFP
								+ " FP to unpaid navy upkeep" : "")
						+ " (" + (int) fpBank(entry.getKey()) + " FP banked)");
				log.lastLogged = log.charged;
			}
			log.charged = 0f;
			log.recycled = 0;
			log.recycledFP = 0f;
			log.starved = 0;
			log.starvedFP = 0f;
			log.navyWanted = 0f;
			log.navyPaid = 0f;
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
		float homeFP = 0f;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			homeFP += garrisonFP(ThreatIncData.garrisonsFor(market.getId()));
			fleetFP += ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId()));
			Float away = ledgerFP.get(market.getId());
			if (away != null) fleetFP += away;
			income += fabricationRatePerDay(market, hiveOutput, hiveDraw) * 30f;
			banked += bankedFP(market);
		}
		return "fleets " + (int) fleetFP + " FP, income " + (int) income + " FP/mo, upkeep "
				+ (int) (upkeepPerDay(ThreatIncConfig.threatSuppliesUpkeep() ? homeFP : fleetFP) * 30f)
				+ " FP/mo, banked " + (int) banked + " FP";
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
		float fp = threatinc.rules.HiveRules.seedEndowment(FAB_ENDOWMENT_DAYS, market.getSize(), fpPerShipUnit30d());
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
		return threatinc.rules.HiveRules.swarmCostFallback(spec[0], spec[1]);
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
		// billed reach (ThreatReach): any distance whose passage the stock pays
		// (the callers' own test) - a garrison moving house is not a trip away,
		// so no supplies gate
		if (ThreatReach.enabled()) return true;
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
		boolean atAttack = ThreatIncConfig.postureNeedAtAttack();
		// receivers the force over which nothing this pass could gather would outweigh: read once a pass
		final java.util.Set<String> outweighed = new java.util.HashSet<String>();
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
				// (2026-10-08, the navy fits the spare) a transfer in transit pays the away rate: none the flow
				// cannot carry to a receiver nobody attacks (hw75a: 179 fleets, 25k FP, 16k supplies a month in
				// transit between hives with the stock at 0 and 145 seedings held)
				if (!attacked && ThreatIncConfig.navyFitsSpare() && ThreatReach.enabled() && ThreatReach.spare() < 0f) {
					ThreatIncConfig.logQuiet("navy-transit:" + receiver.getId(), "Posture: no transfer to "
							+ receiver.getName() + " - the flow is short (spare " + (int) ThreatReach.spare() + " a month)");
					continue;
				}
				// (2026-10-08) what cannot outweigh the force over an attacked world is not fed into it a
				// swarm at a time - the rally's rule (systemDefenceOnlyIfEnough), which the ordinary transfers
				// and the fabrication for a receiver had sat outside: hw83a's Nomios, under a 4,450 FP siege
				// with 800 FP standing, was sent 91 swarms of 40-90 FP in three months, Epiphany's bank
				// fabricating one a dispatch from 1,668 FP down to 74, each hunted down as it arrived;
				// 4,501 swarms, 470k FP, fabricated for attacked hives in a game, forty of them eradicated
				if (outweighed.contains(receiver.getId())) continue;
				if (ThreatIncConfig.systemDefenceOnlyIfEnough() && ThreatPosture.underAttack(receiver.getStarSystem())) {
					float force = ThreatPosture.forceOver(receiver);
					float stands = force > 0f ? ThreatPosture.standsFor(receiver) : 0f;
					if (force > 0f && stands < force) {
						float gather = 0f;
						for (MarketAPI curr : colonies) {
							if (curr == receiver) continue;
							gather += pressureSpare(curr, receiver, held, inbound, attacked, atAttack);
							gather += fabricableFor(curr, receiver, held);
						}
						// what the pass would send this poll is at most the receiver's accept (its deficit, the band
						// and a swarm), whatever the sector's banks hold: hw87a gated on the banks alone refused 49
						// times and fabricated 4,489 swarms for attacked hives, hw83a's 4,501 - a want of 1,000
						// under a 4,450 FP siege was fed 400 FP a poll from banks that "could" have stood 60k
						float could = stands + Math.min(accept, gather);
						if (could < force) {
							outweighed.add(receiver.getId());
							ThreatIncConfig.logQuiet("navy-enough:" + receiver.getId(), "Posture: no transfer to "
									+ receiver.getName() + " - " + (int) force + " FP over it, " + (int) could
									+ " FP could stand (" + (int) stands + " standing, " + (int) accept
									+ " FP more wanted, " + (int) gather + " FP to gather)");
							continue;
						}
					}
				}
				MarketAPI donor = null;
				CampaignFleetAPI pick = null;
				boolean donorSame = false;
				float donorSpare = 0f, donorDist = Float.MAX_VALUE;
				for (MarketAPI curr : colonies) {
					if (curr == receiver) continue;
					float spare = pressureSpare(curr, receiver, held, inbound, attacked, atAttack);
					if (spare <= 0f) continue;
					CampaignFleetAPI fleet = pressureFleet(curr, Math.min(spare, accept), deficit);
					if (fleet == null) continue;
					boolean same = curr.getStarSystem() == receiver.getStarSystem();
					float dist = same ? 0f : Misc.getDistanceLY(curr.getStarSystem().getLocation(),
							receiver.getStarSystem().getLocation());
					// the way there comes from the hive's fuel (ThreatFuel)
					if (!ThreatFuel.canPay(ThreatFuel.passage(fleet.getFleetPoints(), dist, false))) continue;
					// billed reach: the nearest donor first - no radius keeps the
					// richest garrison across the sector from being the one that sails
					boolean better;
					if (donor == null) better = true;
					else if (same != donorSame) better = same;
					else if (ThreatReach.enabled() && dist != donorDist) better = dist < donorDist;
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
					// (2026-10-08, the navy fits the spare) while the fit is active nothing is fabricated for a
					// receiver nobody attacks: hw81a fabricated and sent Gream nine swarms a month against a
					// want that moved with the pressure, the fit recycled them on arrival, the pass sent again
					if (!attacked && fitActive()) {
						ThreatIncConfig.logQuiet("navy-fab:" + receiver.getId(), "Posture: nothing fabricated for "
								+ receiver.getName() + " - the navy fits the spare and nobody attacks it");
						continue;
					}
					// no fleet to spare anywhere in reach: a colony whose bank lies
					// idle past its own want builds a swarm for the receiver instead
					donor = fabricatorFor(colonies, receiver, held);
					if (donor == null) continue;
					pick = fabricateFor(donor, receiver, deficit);
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
				if (donor.getStarSystem() != receiver.getStarSystem()) {
					ThreatReach.note("send", ThreatFuel.ly(donor.getStarSystem(), receiver.getStarSystem()));
				}
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
	 * What the colony can give the receiver in the pressure pass, from what is
	 * on station: 0 unless it can rebuild, is not earmarked by the offensive,
	 * holds two fleets, was not sent a transfer within DECAY_DAYS, and can
	 * reach the receiver. Its releasable surplus - or, for a receiver under
	 * attack, what a colony the war asks nothing of may thin to its reserve
	 * (postureNeedAtAttack: hw6, the stock was a founding's or a strike's,
	 * never the defence's, and a colony short of its want gave nothing).
	 */
	protected static float pressureSpare(MarketAPI curr, MarketAPI receiver, Map<String, Float> held,
			Map<String, Float> inbound, boolean attacked, boolean atAttack) {
		if (curr == receiver || curr.getPrimaryEntity() == null) return 0f;
		if (!canRebuildGarrison(curr)) return 0f;
		// a system whose spare the offensive's held prongs will muster on their day keeps it (hw50c: eight
		// sends drained a staging system between a launch and its prongs' days, and every one was refused)
		if (curr.getStarSystem() != null && ThreatOffensive.earmarked(curr.getStarSystem().getId()) > 0) return 0f;
		if (countLiveGarrison(curr.getId()) < 2) return 0f;
		if (ThreatPosture.recentlyReceived(curr)) return 0f;
		// what is on station, not what is flying in
		float onStation = held.get(curr.getId()) - inbound.get(curr.getId());
		boolean thin = attacked && (atAttack ? ThreatPosture.needFP(curr) <= 0f
				: !ThreatPosture.pressed(curr.getStarSystem()));
		if (!(thin && atAttack) && onStation < ThreatPosture.wantFP(curr)) return 0f;
		float spare = ThreatPosture.releasableFP(curr, onStation);
		if (thin) spare = Math.max(spare, ThreatPosture.thinnableFP(curr, onStation));
		if (spare <= 0f) return 0f;
		if (!canReinforce(curr, receiver)) return 0f;
		return spare;
	}

	/**
	 * Fleet points the colony's bank could build for the receiver as
	 * fabricatorFor would have it: its idle bank past its own want, 0 unless it
	 * holds its want, can rebuild and reach the receiver.
	 */
	protected static float fabricableFor(MarketAPI curr, MarketAPI receiver, Map<String, Float> held) {
		if (curr == receiver || curr.getPrimaryEntity() == null || curr.getStarSystem() == null) return 0f;
		if (!canRebuildGarrison(curr)) return 0f;
		float want = ThreatPosture.wantFP(curr);
		Float h = held.get(curr.getId());
		if (h == null || h < want) return 0f;
		float idle = bankedFP(curr) - want;
		if (idle <= 0f) return 0f;
		if (!canReinforce(curr, receiver)) return 0f;
		return idle;
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
		float bestIdle = 0f, bestLY = Float.MAX_VALUE;
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
			float ly = ThreatFuel.ly(curr.getStarSystem(), receiver.getStarSystem());
			if (!ThreatFuel.canPay(ThreatFuel.passage(cost, ly, false))) continue;
			// billed reach: the nearest idle bank first, as the donors go
			boolean better = best == null || (same != bestSame ? same
					: ThreatReach.enabled() && ly != bestLY ? ly < bestLY : idle > bestIdle);
			if (better) {
				best = curr;
				bestSame = same;
				bestIdle = idle;
				bestLY = ly;
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

	/**
	 * The swarm fabricateFor builds: the dearest garrison row of any size's
	 * table whose estimated cost the budget pays - the receiver's deficit, the
	 * fabricator's bank past its own want, and the fuel to the receiver - else
	 * the receiver's cheapest row. (2026-10-09, hw93a: Culann, wanting 4,582 FP
	 * under a human siege, was fabricated 57 swarms of 52-97 FP - its size-1
	 * table's cheapest row - one a dispatch, and 151 reinforcements were in
	 * flight at a time, 20k FP paying 15k supplies a month.)
	 */
	protected static int[] rowFor(MarketAPI fabricator, MarketAPI receiver, float deficit) {
		int[] best = cheapestRow(receiver);
		if (best == null) return null;
		float budget = Math.min(deficit, bankedFP(fabricator) - ThreatPosture.wantFP(fabricator));
		float ly = ThreatFuel.ly(fabricator.getStarSystem(), receiver.getStarSystem());
		float bestCost = swarmCostEstimate(best);
		for (int size = 2; size <= 8; size++) {
			for (int[] row : desiredGarrison(size)) {
				float cost = swarmCostEstimate(row);
				if (cost <= bestCost || cost > budget) continue;
				if (!ThreatFuel.canPay(ThreatFuel.passage(cost, ly, false))) continue;
				best = row;
				bestCost = cost;
			}
		}
		return best;
	}

	/** Builds the swarm rowFor sizes to the receiver's deficit at the fabricator, paid from its bank, into its garrison to be sent on. */
	protected static CampaignFleetAPI fabricateFor(MarketAPI fabricator, MarketAPI receiver, float deficit) {
		int[] spec = rowFor(fabricator, receiver, deficit);
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
			// a system whose spare the offensive's held prongs will muster on their day keeps it (hw50c: eight
			// sends drained a staging system between a launch and its prongs' days, and every one was refused)
			if (curr.getStarSystem() != null && ThreatOffensive.earmarked(curr.getStarSystem().getId()) > 0) continue;
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
	 * The frame pulse (hw113/hw114 read): a script on a reinforcement swarm that
	 * notes the day and place of the last frame the engine advanced the fleet,
	 * so the overdue read can tell a fleet the engine no longer advances (frozen
	 * at the same unit of position for 90 days with its velocity at full burn,
	 * hw111a: 137 such reads, every one in an asteroid belt or ring, the belt
	 * key set) from one it advances but which does not move. Not saved across
	 * a game load - a test-run instrument.
	 */
	public static class FramePulse implements com.fs.starfarer.api.EveryFrameScript {
		public CampaignFleetAPI fleet;
		public float day = -1f;
		public float x, y;
		public int frames;
		/** Calls with a positive frame time, their sum and largest (s), the distance between the last two such calls. */
		public int live;
		public float sum, max, step;
		/** The last RING live calls: the containing location's name and the position (hw126: a fleet flipping between two points). */
		public static final int RING = 6;
		public String[] ringLoc = new String[RING];
		public int[] ringX = new int[RING], ringY = new int[RING];
		public FramePulse(CampaignFleetAPI fleet) { this.fleet = fleet; }
		public boolean isDone() { return fleet == null || !fleet.isAlive(); }
		public boolean runWhilePaused() { return false; }
		public void advance(float amount) {
			frames++;
			day = ThreatPosture.today();
			float nx = fleet.getLocation().x, ny = fleet.getLocation().y;
			if (amount > 0f) {
				if (live > 0) step = (float) Math.hypot(nx - x, ny - y);
				int i = live % RING;
				ringLoc[i] = fleet.getContainingLocation() == null ? "null" : fleet.getContainingLocation().getName();
				ringX[i] = (int) nx;
				ringY[i] = (int) ny;
				live++;
				sum += amount;
				max = Math.max(max, amount);
			}
			x = nx;
			y = ny;
		}

		/** The ring oldest first, "location x/y; ...". */
		public String ring() {
			StringBuilder b = new StringBuilder();
			int n = Math.min(live, RING);
			for (int k = 0; k < n; k++) {
				int i = (live - n + k) % RING;
				if (b.length() > 0) b.append("; ");
				b.append(ringLoc[i]).append(' ').append(ringX[i]).append('/').append(ringY[i]);
			}
			return b.toString();
		}
	}

	protected static final Map<String, FramePulse> PULSES = new java.util.HashMap<String, FramePulse>();

	/**
	 * The overdue read's engine check (the 2026-10-09 decompile: the location
	 * moves every entity in its CampaignEntity list by its velocity each tick,
	 * so a listed fleet frozen at full burn is either missing from that list
	 * or not in an advanced location): whether the fleet is in the list the
	 * engine iterates (the core interface com.fs.starfarer.campaign.CampaignEntity,
	 * found by name up the fleet's class tree) and in getFleets(), how many
	 * times each, whether its location is a star system or hyperspace the
	 * sector holds, and the expiry of $ai_moveDest - the fleet AI renews it
	 * every tick and memory counts down only while the fleet is advanced, so
	 * the same figure at two reads is a fleet not advanced between them.
	 */
	protected static void engineLists(CampaignFleetAPI fleet, StringBuilder b) {
		com.fs.starfarer.api.campaign.LocationAPI loc = fleet.getContainingLocation();
		if (loc == null) {
			b.append(", engine no location");
			return;
		}
		Class<?> entity = null;
		for (Class<?> c = fleet.getClass(); c != null && entity == null; c = c.getSuperclass()) {
			for (Class<?> i : c.getInterfaces()) {
				if ("com.fs.starfarer.campaign.CampaignEntity".equals(i.getName())) { entity = i; break; }
			}
		}
		if (entity == null) {
			b.append(", engine list unknown");
		} else {
			List<?> ents = loc.getEntities(entity);
			b.append(", engine list x").append(java.util.Collections.frequency(ents, fleet));
		}
		b.append(", fleets x").append(java.util.Collections.frequency(loc.getFleets(), fleet));
		boolean held = loc == Global.getSector().getHyperspace()
				|| Global.getSector().getStarSystems().contains(loc);
		b.append(", location held ").append(held);
		// every location that lists the fleet (hw126): the location loop sets an entity's containing
		// location to itself before advancing it, so a fleet listed in two is advanced twice a tick
		int listed = 0;
		StringBuilder where = new StringBuilder();
		List<com.fs.starfarer.api.campaign.LocationAPI> all = new ArrayList<com.fs.starfarer.api.campaign.LocationAPI>(
				Global.getSector().getStarSystems());
		all.add(Global.getSector().getHyperspace());
		for (com.fs.starfarer.api.campaign.LocationAPI l : all) {
			if (l == null || !l.getFleets().contains(fleet)) continue;
			listed++;
			if (where.length() > 0) where.append(" + ");
			where.append(l.getName());
		}
		b.append(", listed in ").append(listed).append(" (").append(where).append(")");
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		b.append(", moveDest ").append(mem.contains("$ai_moveDest")
				? String.format("%.4f", mem.getExpire("$ai_moveDest")) : "none");
	}

	/** Attaches the frame pulse to a reinforcement swarm once. */
	protected static void pulse(CampaignFleetAPI fleet) {
		if (fleet == null || PULSES.containsKey(fleet.getId())) return;
		FramePulse p = new FramePulse(fleet);
		PULSES.put(fleet.getId(), p);
		fleet.addScript(p);
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
		mem.set(REINFORCE_DAY_KEY, ThreatPosture.today());
		mem.set(REINFORCE_AT_KEY, new Vector2f(pick.getLocation()));
		mem.set(REINFORCE_FROM_KEY, source.getId());
		mem.unset(REINFORCE_KICK_KEY);
		mem.unset(REINFORCE_KICKS_KEY);
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		makeDetectable(pick);
		ThreatFleetComposer.beltGuard(pick);
		pulse(pick);

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
	 * A fleet from outside the garrisons - a recalled strike's
	 * (ThreatStrikeFGI.recallTo) - flies to the colony and joins its garrison
	 * on arrival, as a reinforcement does (checkReinforcementArrivals), with a
	 * reinforcement's blinders. Its hulls leave the ledger that paid for them:
	 * the garrison's upkeep charges them where they stand (digInAtConquest).
	 * Its passage was paid at launch, there and back.
	 */
	public static boolean sendToGarrison(CampaignFleetAPI fleet, MarketAPI target) {
		SectorEntityToken planet = target != null ? target.getPrimaryEntity() : null;
		if (planet == null || fleet == null || !fleet.isAlive()) return false;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		unbindLedger(fleet);
		mem.unset(Misc.FLEET_RETURNING_TO_DESPAWN);
		mem.set(REINFORCE_TARGET_KEY, target.getId());
		mem.set(REINFORCE_DAY_KEY, ThreatPosture.today());
		mem.set(REINFORCE_AT_KEY, new Vector2f(fleet.getLocation()));
		mem.unset(REINFORCE_KICK_KEY);
		mem.unset(REINFORCE_KICKS_KEY);
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		makeDetectable(fleet);
		ThreatFleetComposer.beltGuard(fleet);

		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 365f,
				"returning to the hive");
		// fallback so the fleet doesn't wander if arrival detection ever misses
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
		ThreatIncData.reinforcementFleets().put(fleet.getId(), fleet);
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
			if (!arrived) {
				overdue(fleet, target, planet, mem);
				continue;
			}

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
			com.fs.starfarer.api.campaign.rules.MemoryAPI am = fleet.getMemoryWithoutUpdate();
			ThreatIncConfig.log("Reinforcement arrived at " + target.getName() + " ("
					+ countLiveGarrison(targetId) + "/" + nominalGarrison(target) + "), id " + fleet.getId() + ", "
					+ (am.contains(REINFORCE_DAY_KEY) ? (int) (ThreatPosture.today() - am.getFloat(REINFORCE_DAY_KEY)) : -1) + " d out, "
					+ (int) fleet.getFleetPoints() + " FP");
		}
	}

	/**
	 * A reinforcement out longer than reinforcementOverdueDays (90) is logged
	 * where it stands (whereabouts) and given its travel order afresh, once a
	 * span (blinders on, the 365-day GO_TO_LOCATION and the orbit fallback).
	 * From hw119 to hw131 the second read carried it to 1,500 units from its
	 * target; removed (the user, 2026-10-09) once the belt guard
	 * (ThreatFleetComposer.beltGuard) fixed the freeze behind it - overdue
	 * reads 1 / 3 / 0 a game, no carry. The read stays as the alarm
	 * (2026-10-09, hw101a: 361 swarms, 56k FP, sat 120+ days in flight, most
	 * inside Thule and Beta Cormoran, while their targets fell - a 724 FP swarm
	 * for Zipacna stood in Beta Cormoran from its send on war day 2690 to
	 * Zipacna's fall at 3418, and the rally found 196 FP to stand against the
	 * 520 FP siege; hw105a: swarms inside their target's own system drifted
	 * away from it at burn 9 with the order active and no battle, 8,777 to
	 * 16,342 units from Blue in 90 days). One in a battle is read and left to
	 * finish it.
	 */
	protected static void overdue(CampaignFleetAPI fleet, MarketAPI target, SectorEntityToken planet,
			com.fs.starfarer.api.campaign.rules.MemoryAPI mem) {
		float span = ThreatIncConfig.reinforcementOverdueDays();
		if (span <= 0f) return;
		float day = ThreatPosture.today();
		if (!mem.contains(REINFORCE_DAY_KEY)) {
			mem.set(REINFORCE_DAY_KEY, day);
			return;
		}
		float sent = mem.getFloat(REINFORCE_DAY_KEY);
		float since = mem.contains(REINFORCE_KICK_KEY) ? mem.getFloat(REINFORCE_KICK_KEY) : sent;
		if (day - since < span) return;
		mem.set(REINFORCE_KICK_KEY, day);
		int kicks = (mem.contains(REINFORCE_KICKS_KEY) ? mem.getInt(REINFORCE_KICKS_KEY) : 0) + 1;
		mem.set(REINFORCE_KICKS_KEY, kicks);
		boolean battle = fleet.getBattle() != null;
		String where = whereabouts(fleet, planet);
		String remedy;
		if (battle) {
			remedy = "left to its battle";
		} else {
			remedy = "ordered on again (kick " + kicks + ")";
		}
		ThreatIncConfig.log("Reinforcement overdue: " + (int) fleet.getFleetPoints() + " FP -> " + target.getName()
				+ ", " + (int) (day - sent) + " d out, id " + fleet.getId() + ", " + where + ", battle " + battle
				+ ", ships " + fleet.getFleetData().getNumMembers() + " - " + remedy);
		if (battle) return;
		pulse(fleet);
		ThreatFleetComposer.beltGuard(fleet);
		mem.set(com.fs.starfarer.api.impl.campaign.ids.MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 365f, "reinforcing the hive");
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, 1000000f);
	}

	/**
	 * Where a fleet stands and what it is doing, for the overdue read: its
	 * distance from the planet (same location), from the nearest jump point
	 * (another system) or in light-years (hyperspace); its position from the
	 * centre; its order, heading and speed; whether its AI is fleeing and what
	 * it targets; the terrain it is in; the nearest hostile armed fleet.
	 */
	protected static String whereabouts(CampaignFleetAPI fleet, SectorEntityToken planet) {
		StringBuilder b = new StringBuilder();
		com.fs.starfarer.api.campaign.LocationAPI loc = fleet.getContainingLocation();
		if (loc == planet.getContainingLocation()) {
			b.append((int) Misc.getDistance(fleet, planet)).append(" units from ").append(planet.getName());
		} else if (loc instanceof StarSystemAPI) {
			float jump = Float.MAX_VALUE;
			for (SectorEntityToken jp : loc.getJumpPoints()) jump = Math.min(jump, Misc.getDistance(fleet, jp));
			b.append("in ").append(loc.getName()).append(", ")
					.append(jump < Float.MAX_VALUE ? (int) jump + " units from a jump point" : "no jump point");
		} else {
			b.append("in hyperspace, ").append((int) Misc.getDistanceLY(fleet.getLocationInHyperspace(),
					planet.getLocationInHyperspace())).append(" ly out");
		}
		b.append(", at ").append((int) fleet.getLocation().x).append("/").append((int) fleet.getLocation().y)
				.append(" (").append((int) fleet.getLocation().length()).append(" from the centre)");
		com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI a = fleet.getCurrentAssignment();
		b.append(", ").append(a == null ? "no order" : a.getAssignment()
				+ (a.getTarget() != null ? " -> " + a.getTarget().getName() : "")
				+ (a.getActionText() != null ? " '" + a.getActionText() + "'" : ""));
		org.lwjgl.util.vector.Vector2f dest = fleet.getMoveDestination();
		b.append(", heading ").append(dest == null ? "nowhere"
				: (int) Misc.getDistance(fleet.getLocation(), dest) + " units to " + (int) dest.x + "/" + (int) dest.y);
		b.append(", velocity ").append((int) fleet.getVelocity().x).append("/").append((int) fleet.getVelocity().y)
				.append(", facing ").append((int) fleet.getFacing());
		b.append(", speed ").append((int) fleet.getVelocity().length()).append(", burn ").append((int) fleet.getCurrBurnLevel());
		com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI ai = fleet.getAI();
		if (ai instanceof com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI) {
			com.fs.starfarer.api.campaign.ai.TacticalModulePlugin t =
					((com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI) ai).getTacticalModule();
			b.append(", fleeing ").append(t != null && t.isFleeing());
			b.append(", tactical target ").append(t != null && t.getTarget() != null ? t.getTarget().getName() : "none");
		} else {
			b.append(", ai ").append(ai == null ? "none" : ai.getClass().getSimpleName());
		}
		b.append(", transition ").append(fleet.isInHyperspaceTransition());
		// the movement itself (2026-10-09, hw107: swarms stood frozen at full speed, others crawled at burn 2)
		b.append(", listed ").append(loc != null && loc.getFleets().contains(fleet));
		b.append(", orbit ").append(fleet.getOrbit() != null).append(", expired ").append(fleet.isExpired());
		b.append(", travel ").append((int) fleet.getTravelSpeed()).append(" (burn min ")
				.append((int) fleet.getFleetData().getMinBurnLevel()).append(" / ")
				.append((int) fleet.getFleetData().getBurnLevel());
		com.fs.starfarer.api.combat.StatBonus mod = fleet.getStats().getFleetwideMaxBurnMod();
		StringBuilder mods = new StringBuilder();
		for (java.util.Map.Entry<String, com.fs.starfarer.api.combat.MutableStat.StatMod> e : mod.getFlatBonuses().entrySet())
			mods.append(mods.length() == 0 ? "" : " ").append(e.getKey()).append(" flat ").append(e.getValue().value);
		for (java.util.Map.Entry<String, com.fs.starfarer.api.combat.MutableStat.StatMod> e : mod.getMultBonuses().entrySet())
			mods.append(mods.length() == 0 ? "" : " ").append(e.getKey()).append(" x").append(e.getValue().value);
		for (java.util.Map.Entry<String, com.fs.starfarer.api.combat.MutableStat.StatMod> e : mod.getPercentBonuses().entrySet())
			mods.append(mods.length() == 0 ? "" : " ").append(e.getKey()).append(" ").append(e.getValue().value).append("%");
		b.append(mods.length() == 0 ? "" : ", mods " + mods).append(")");
		StringBuilder ab = new StringBuilder();
		for (com.fs.starfarer.api.characters.AbilityPlugin ap : fleet.getAbilities().values())
			if (ap != null && ap.isActive()) ab.append(ab.length() == 0 ? "" : "+").append(ap.getId());
		b.append(", abilities ").append(ab.length() == 0 ? "none" : ab.toString());
		b.append(", slow ").append(Misc.isSlowMoving(fleet));
		com.fs.starfarer.api.campaign.rules.MemoryAPI fm = fleet.getMemoryWithoutUpdate();
		b.append(", impact ").append(fm.contains("$asteroidImpactTimeout")).append("/").append(fm.contains("$recentImpact"));
		b.append(", listeners ").append(fleet.getEventListeners().size());
		Object sentAt = fleet.getMemoryWithoutUpdate().get(REINFORCE_AT_KEY);
		Object fromId = fleet.getMemoryWithoutUpdate().get(REINFORCE_FROM_KEY);
		MarketAPI fromMarket = fromId instanceof String ? Global.getSector().getEconomy().getMarket((String) fromId) : null;
		if (fromMarket != null && fromMarket.getPrimaryEntity() != null) {
			SectorEntityToken fp = fromMarket.getPrimaryEntity();
			b.append(", from ").append(fromMarket.getName()).append(fp.getContainingLocation() == loc
					? " " + (int) Misc.getDistance(fleet, fp) + " units off (radius " + (int) fp.getRadius() + ")" : " (another location)");
		} else {
			b.append(", from unknown");
		}
		b.append(", moved ").append(sentAt instanceof Vector2f
				? (int) Misc.getDistance(fleet.getLocation(), (Vector2f) sentAt) + " units since the send" : "unknown");
		b.append(", alive ").append(fleet.isAlive()).append(", current ").append(fleet.isInCurrentLocation())
				.append(", station ").append(fleet.isStationMode()).append(", aimode ").append(fleet.isAIMode());
		FramePulse p = PULSES.get(fleet.getId());
		if (p == null) {
			b.append(", pulse none");
		} else if (p.frames == 0) {
			// today() is negative on the sector clock, so the frame count tells "never ran"
			// (until 2026-10-09 it read p.day < 0 and every read said never)
			b.append(", pulse never ran");
		} else {
			b.append(", pulse ").append(p.frames).append(" frames, last ")
					.append(String.format("%.1f", ThreatPosture.today() - p.day)).append(" d ago at ")
					.append((int) p.x).append("/").append((int) p.y)
					.append(" (live ").append(p.live).append(", ").append(String.format("%.1f", p.sum)).append(" s, max ")
					.append(String.format("%.3f", p.max)).append(" s, step ").append((int) p.step).append(")")
					.append(", ring [").append(p.ring()).append("]");
		}
		// the go-slow trap's guard (ThreatFleetComposer.beltGuard): frames it met an overshooting brake
		String guard = "none";
		for (com.fs.starfarer.api.EveryFrameScript s : fleet.getScripts()) {
			if (s instanceof ThreatFleetComposer.BeltGuard) guard = String.valueOf(((ThreatFleetComposer.BeltGuard) s).trips);
		}
		b.append(", belt guard ").append(guard).append(", go slow ").append(fleet.getGoSlowOneFrame());
		engineLists(fleet, b);
		if (loc != null) {
			StringBuilder terrain = new StringBuilder();
			for (com.fs.starfarer.api.campaign.CampaignTerrainAPI t : loc.getTerrainCopy()) {
				if (t.getPlugin() == null || !t.getPlugin().containsEntity(fleet)) continue;
				terrain.append(terrain.length() == 0 ? "" : "+").append(t.getPlugin().getTerrainName());
			}
			b.append(", terrain ").append(terrain.length() == 0 ? "none" : terrain.toString());
			CampaignFleetAPI foe = null;
			float best = Float.MAX_VALUE;
			for (CampaignFleetAPI f : loc.getFleets()) {
				if (f == null || f == fleet || !f.isAlive() || f.getFaction() == null || f.getFleetPoints() <= 0f) continue;
				if (!f.getFaction().isHostileTo(fleet.getFaction())) continue;
				float d = Misc.getDistance(fleet, f);
				if (d < best) {
					best = d;
					foe = f;
				}
			}
			b.append(", nearest hostile ").append(foe == null ? "none"
					: (int) foe.getFleetPoints() + " FP " + foe.getFaction().getId() + " at " + (int) best + " units");
		}
		return b.toString();
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

				ThreatFleetComposer.beltGuard(fleet);
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
			// the military tier is the hive's alone (SwarmBastion)
			if (market.hasIndustry(SwarmBastion.BASTION)) {
				market.removeIndustry(SwarmBastion.BASTION, null, false);
			}
			if (market.hasIndustry(SwarmBastion.COMMAND)) {
				market.removeIndustry(SwarmBastion.COMMAND, null, false);
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
		ThreatSwarmPatrols.reset();
		Global.getSector().getPersistentData().remove(KEY_STRIKE_FUND);
		ThreatOmens.reset();
		ThreatIntel.reset();
		ThreatSwarmIntel.reset();
		ThreatAttackPlanner.reset();
		ThreatWarCouncil.reset();
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
