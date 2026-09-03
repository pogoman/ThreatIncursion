package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Persistent incursion state. Stage strings per system id, colony market ids
 * (multiple per system), garrison/colonization fleet references, timestamps,
 * and counters - all in sector persistent data.
 *
 * Keying: systems carry a stage and a list of colony markets; growth timers,
 * garrisons, and purge cooldowns are per-market; in-transit colonization
 * waves are keyed by their target planet.
 */
public class ThreatIncData {

	public static final String STAGE_SEEDED = "seeded";
	public static final String STAGE_COLONIZING = "colonizing";
	public static final String STAGE_COLONY = "colony";

	/** Pre-colony-rework stages, kept only so old saves can be migrated. */
	public static final String STAGE_HIVE = "hive";
	public static final String STAGE_SATURATED = "saturated";

	/** Bumped when the persistent-data layout changes; drives one-time migration. */
	public static final int CURRENT_DATA_VERSION = 5;

	public static final String KEY_STARTED = "threatinc_started";
	public static final String KEY_START_TIMESTAMP = "threatinc_startTimestamp";
	public static final String KEY_STAGES = "threatinc_stages";
	public static final String KEY_STAGE_TIMES = "threatinc_stageTimes";
	public static final String KEY_HIVES = "threatinc_hiveFleets";
	public static final String KEY_LAST_STRIKE_TIMES = "threatinc_lastStrikeTimes";
	public static final String KEY_PLAYER_STRUCK_AT = "threatinc_playerStruckAt";
	public static final String KEY_SYSTEMS_CLEANSED = "threatinc_systemsCleansed";
	public static final String KEY_ANNOUNCED_PHASE3 = "threatinc_announcedPhase3";

	public static final String KEY_COLONY_MARKETS = "threatinc_colonyMarkets";
	public static final String KEY_WAVE_FLEETS = "threatinc_colonyFleets";
	public static final String KEY_WAVE_TARGETS = "threatinc_colonyTargets";
	public static final String KEY_GARRISONS = "threatinc_garrisons";
	public static final String KEY_REINFORCEMENTS = "threatinc_reinforcements";
	public static final String KEY_GARRISON_SPAWN_TIMES = "threatinc_garrisonSpawnTimes";
	public static final String KEY_GROWTH_TIMES = "threatinc_growthTimes";
	public static final String KEY_GROWTH_PROGRESS = "threatinc_growthProgressDays";
	public static final String KEY_DECLINE_PROGRESS = "threatinc_declineProgress";
	public static final String KEY_DECLINE_TICKS = "threatinc_declineTicks";
	public static final String KEY_DECLINE_DAYS = "threatinc_declineDays";
	public static final String KEY_LAST_HEALTH = "threatinc_lastHealth";
	public static final String KEY_LAST_PURGE_TIMES = "threatinc_lastPurgeTimes";
	public static final String KEY_DECIV_TARGETS = "threatinc_decivTargets";
	public static final String KEY_PENDING_DECIV = "threatinc_pendingDecivChecks";
	public static final String KEY_BOOTSTRAP_SEEDS = "threatinc_bootstrapSeeds";
	public static final String KEY_OG_SYSTEM = "threatinc_ogSystem";
	public static final String KEY_DATA_VERSION = "threatinc_dataVersion";

	@SuppressWarnings("unchecked")
	static <T> Map<String, T> map(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, T>();
			Global.getSector().getPersistentData().put(key, val);
		}
		return (Map<String, T>) val;
	}

	@SuppressWarnings("unchecked")
	static <T> List<T> list(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (!(val instanceof List)) {
			val = new ArrayList<T>();
			Global.getSector().getPersistentData().put(key, val);
		}
		return (List<T>) val;
	}

	public static Map<String, String> stages() {
		return map(KEY_STAGES);
	}

	public static Map<String, Long> stageTimes() {
		return map(KEY_STAGE_TIMES);
	}

	/** Legacy pre-rework hive fleets; only read by save migration. */
	public static Map<String, CampaignFleetAPI> hives() {
		return map(KEY_HIVES);
	}

	public static Map<String, Long> lastStrikeTimes() {
		return map(KEY_LAST_STRIKE_TIMES);
	}

	/** systemId -> colony market ids (a system can hold several colonies). */
	public static Map<String, List<String>> colonyMarkets() {
		return map(KEY_COLONY_MARKETS);
	}

	public static List<String> colonyMarketsFor(String systemId) {
		List<String> ids = colonyMarkets().get(systemId);
		if (ids == null) {
			ids = new ArrayList<String>();
			colonyMarkets().put(systemId, ids);
		}
		return ids;
	}

	/** target planet id -> in-transit colonization ("Seeding Swarm") fleet. */
	public static Map<String, CampaignFleetAPI> waveFleets() {
		return map(KEY_WAVE_FLEETS);
	}

	/** target planet id -> system id of the wave's destination. */
	public static Map<String, String> waveTargets() {
		return map(KEY_WAVE_TARGETS);
	}

	public static boolean hasWaveTargetingSystem(String systemId) {
		return waveTargets().containsValue(systemId);
	}

	/** colony market id -> live garrison ("Defense Swarm") fleets. */
	public static Map<String, List<CampaignFleetAPI>> garrisons() {
		return map(KEY_GARRISONS);
	}

	/**
	 * In-transit reinforcement fleets, keyed by fleet id. Each is a Defense Swarm
	 * that left one colony's garrison to reinforce another (its target colony's
	 * market id rides the fleet memory); on arrival it joins the target garrison.
	 */
	public static Map<String, CampaignFleetAPI> reinforcementFleets() {
		return map(KEY_REINFORCEMENTS);
	}

	public static List<CampaignFleetAPI> garrisonsFor(String marketId) {
		List<CampaignFleetAPI> fleets = garrisons().get(marketId);
		if (fleets == null) {
			fleets = new ArrayList<CampaignFleetAPI>();
			garrisons().put(marketId, fleets);
		}
		return fleets;
	}

	/** colony market id -> last garrison fabrication time. */
	public static Map<String, Long> garrisonSpawnTimes() {
		return map(KEY_GARRISON_SPAWN_TIMES);
	}

	/** colony market id -> last size-up (or founding) time. */
	public static Map<String, Long> growthTimes() {
		return map(KEY_GROWTH_TIMES);
	}

	/** colony market id -> last time an NPC purge bombardment targeted it. */
	public static Map<String, Long> lastPurgeTimes() {
		return map(KEY_LAST_PURGE_TIMES);
	}

	// ------------------------------------------------------------------
	// colony vitality: health-scaled growth and the decline meter
	// ------------------------------------------------------------------

	/** colony market id -> accumulated effective growth days (health-scaled). */
	public static Map<String, Float> growthProgress() {
		return map(KEY_GROWTH_PROGRESS);
	}

	public static float growthProgressDays(String marketId) {
		Float v = growthProgress().get(marketId);
		return v != null ? v : 0f;
	}

	public static void setGrowthProgressDays(String marketId, float days) {
		growthProgress().put(marketId, days);
	}

	/**
	 * colony market id -> decline meter: accumulated fraction of the NEXT
	 * population stratum lost. Fed by low colony health (shortages, disrupted
	 * organs) and by saturation-bombardment population damage; at 1.0 the
	 * colony loses a size.
	 */
	public static Map<String, Float> declineProgress() {
		return map(KEY_DECLINE_PROGRESS);
	}

	public static float declineProgress(String marketId) {
		Float v = declineProgress().get(marketId);
		return v != null ? v : 0f;
	}

	public static void setDeclineProgress(String marketId, float progress) {
		declineProgress().put(marketId, Math.max(0f, progress));
	}

	public static void addDeclineProgress(String marketId, float amount) {
		setDeclineProgress(marketId, declineProgress(marketId) + amount);
	}

	/** LEGACY (pre-v5): consecutive declining ticks; read only by migration. */
	public static Map<String, Integer> declineTicks() {
		return map(KEY_DECLINE_TICKS);
	}

	/**
	 * colony market id -> consecutive days spent in decline (drives the
	 * acceleration ramp). Continuous - accrued every poll, not per tick.
	 */
	public static Map<String, Float> declineDays() {
		return map(KEY_DECLINE_DAYS);
	}

	public static float declineDays(String marketId) {
		Float v = declineDays().get(marketId);
		return v != null ? v : 0f;
	}

	public static void setDeclineDays(String marketId, float days) {
		declineDays().put(marketId, Math.max(0f, days));
	}

	/** colony market id -> health computed on the last vitality tick (for UI). */
	public static Map<String, Float> lastHealth() {
		return map(KEY_LAST_HEALTH);
	}

	public static float lastHealth(String marketId) {
		Float v = lastHealth().get(marketId);
		return v != null ? v : 1f;
	}

	public static void setLastHealth(String marketId, float health) {
		lastHealth().put(marketId, health);
	}

	/** Drops every vitality entry for one colony (death/system cleanup). */
	public static void clearVitality(String marketId) {
		growthProgress().remove(marketId);
		declineProgress().remove(marketId);
		declineTicks().remove(marketId);
		declineDays().remove(marketId);
		lastHealth().remove(marketId);
	}

	/** Planet entity ids of Threat-decivilized worlds awaiting conversion. */
	public static List<String> decivTargets() {
		return list(KEY_DECIV_TARGETS);
	}

	/**
	 * Market ids of worlds decivilized recently whose cause (Threat bombardment
	 * or not) hasn't been confirmed yet - the RECENTLY_BOMBARDED flag is set
	 * after the deciv listener fires, so the check has to happen on a later poll.
	 */
	public static List<String> pendingDecivChecks() {
		return list(KEY_PENDING_DECIV);
	}

	/**
	 * Systems seeded by the initial incursion from the Abyss. Only these may
	 * receive bootstrap waves from deep space - the one-time arrival event.
	 * Every later wave must launch from an established colony.
	 */
	public static List<String> bootstrapSeeds() {
		return list(KEY_BOOTSTRAP_SEEDS);
	}

	public static final String KEY_DISCOVERED_SYSTEMS = "threatinc_discoveredSystems";

	/**
	 * Systems whose infestation the player actually knows about: visited in
	 * person, targeted by a publicly-tracked swarm, or the origin of a strike.
	 * Outside debug mode, only these get map markers.
	 */
	public static List<String> discoveredSystems() {
		return list(KEY_DISCOVERED_SYSTEMS);
	}

	public static void markDiscovered(String systemId) {
		List<String> discovered = discoveredSystems();
		if (!discovered.contains(systemId)) discovered.add(systemId);
	}

	public static final String KEY_RARE_ECONOMY = "threatinc_rareEconomy";

	/**
	 * Whether this incursion's economy is built around rare ore (true whenever
	 * the OG home system had rare-ore deposits, i.e. essentially always). When
	 * true, rare ore / rare metals are hard growth inputs, so cutting off rare
	 * ore mining genuinely strangles expansion - it does NOT stop mattering just
	 * because the last rare mine was destroyed. Only a degenerate start with no
	 * rare ore anywhere leaves this false, to avoid a bootstrap deadlock.
	 */
	public static boolean usesRareEconomy() {
		Object val = Global.getSector().getPersistentData().get(KEY_RARE_ECONOMY);
		return val instanceof Boolean && (Boolean) val;
	}

	public static void setUsesRareEconomy(boolean value) {
		Global.getSector().getPersistentData().put(KEY_RARE_ECONOMY, value);
	}

	/** The original, resource-complete home system the incursion started from. */
	public static String getOGSystem() {
		Object val = Global.getSector().getPersistentData().get(KEY_OG_SYSTEM);
		return val instanceof String ? (String) val : null;
	}

	public static void setOGSystem(String systemId) {
		Global.getSector().getPersistentData().put(KEY_OG_SYSTEM, systemId);
	}

	public static boolean isOGSystem(String systemId) {
		return systemId != null && systemId.equals(getOGSystem());
	}

	public static int getDataVersion() {
		Object val = Global.getSector().getPersistentData().get(KEY_DATA_VERSION);
		if (val instanceof Integer) return (Integer) val;
		return 1;
	}

	public static void setDataVersion(int version) {
		Global.getSector().getPersistentData().put(KEY_DATA_VERSION, version);
	}

	public static boolean isStarted() {
		Object val = Global.getSector().getPersistentData().get(KEY_STARTED);
		return val instanceof Boolean && (Boolean) val;
	}

	public static void setStarted() {
		Global.getSector().getPersistentData().put(KEY_STARTED, true);
		Global.getSector().getPersistentData().put(KEY_START_TIMESTAMP,
				Global.getSector().getClock().getTimestamp());
	}

	public static float daysSinceStart() {
		Object val = Global.getSector().getPersistentData().get(KEY_START_TIMESTAMP);
		if (!(val instanceof Long)) return 0f;
		return Global.getSector().getClock().getElapsedDaysSince((Long) val);
	}

	public static void setStage(String systemId, String stage) {
		stages().put(systemId, stage);
		stageTimes().put(systemId, Global.getSector().getClock().getTimestamp());
	}

	public static void clearSystem(String systemId) {
		for (String marketId : new ArrayList<String>(colonyMarketsFor(systemId))) {
			garrisons().remove(marketId);
			garrisonSpawnTimes().remove(marketId);
			growthTimes().remove(marketId);
			lastPurgeTimes().remove(marketId);
			clearVitality(marketId);
		}
		colonyMarkets().remove(systemId);

		for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(
				waveTargets().entrySet())) {
			if (systemId.equals(entry.getValue())) {
				waveFleets().remove(entry.getKey());
				waveTargets().remove(entry.getKey());
			}
		}

		stages().remove(systemId);
		stageTimes().remove(systemId);
		hives().remove(systemId);
		lastStrikeTimes().remove(systemId);
	}

	public static float daysInStage(String systemId) {
		Long t = stageTimes().get(systemId);
		if (t == null) return 0f;
		return Global.getSector().getClock().getElapsedDaysSince(t);
	}

	public static void setGrowthTime(String marketId) {
		growthTimes().put(marketId, Global.getSector().getClock().getTimestamp());
	}

	public static float daysSinceGrowth(String marketId) {
		Long t = growthTimes().get(marketId);
		if (t == null) return 0f;
		return Global.getSector().getClock().getElapsedDaysSince(t);
	}

	public static int countStage(String stage) {
		int count = 0;
		for (String s : stages().values()) {
			if (s.equals(stage)) count++;
		}
		return count;
	}

	public static int countInfested() {
		return stages().size();
	}

	/** Number of live colony markets across the whole sector. */
	public static int countColonies() {
		int count = 0;
		for (String systemId : colonyMarkets().keySet()) {
			count += getLiveColonyMarkets(systemId).size();
		}
		return count;
	}

	/** Resolves a colony market id to a live, still-Threat-held market, or null. */
	public static MarketAPI resolveColonyMarket(String marketId) {
		if (marketId == null) return null;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market == null) return null;
		if (!Factions.THREAT.equals(market.getFactionId())) return null;
		if (market.isPlanetConditionMarketOnly()) return null;
		return market;
	}

	/** All live colony markets in a system. */
	public static List<MarketAPI> getLiveColonyMarkets(String systemId) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		for (String marketId : colonyMarketsFor(systemId)) {
			MarketAPI market = resolveColonyMarket(marketId);
			if (market != null) result.add(market);
		}
		return result;
	}

	/** The largest live colony in a system - its strike staging point. */
	public static MarketAPI getPrimaryColonyMarket(String systemId) {
		MarketAPI best = null;
		for (MarketAPI market : getLiveColonyMarkets(systemId)) {
			if (best == null || market.getSize() > best.getSize()) best = market;
		}
		return best;
	}

	/** All live colony markets sector-wide. */
	public static List<MarketAPI> getAllLiveColonyMarkets() {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		for (String systemId : new ArrayList<String>(colonyMarkets().keySet())) {
			result.addAll(getLiveColonyMarkets(systemId));
		}
		return result;
	}

	/** Total size of all live Threat colonies - the swarm's "biomass". */
	public static int totalColonySize() {
		int total = 0;
		for (MarketAPI market : getAllLiveColonyMarkets()) {
			total += market.getSize();
		}
		return total;
	}

	public static void setPlayerStruck() {
		Global.getSector().getPersistentData().put(KEY_PLAYER_STRUCK_AT,
				Global.getSector().getClock().getTimestamp());
	}

	public static float daysSincePlayerStruck() {
		Object val = Global.getSector().getPersistentData().get(KEY_PLAYER_STRUCK_AT);
		if (!(val instanceof Long)) return Float.MAX_VALUE;
		return Global.getSector().getClock().getElapsedDaysSince((Long) val);
	}

	public static int getCleansedCount() {
		Object val = Global.getSector().getPersistentData().get(KEY_SYSTEMS_CLEANSED);
		if (val instanceof Integer) return (Integer) val;
		return 0;
	}

	public static void incrCleansedCount() {
		Global.getSector().getPersistentData().put(KEY_SYSTEMS_CLEANSED, getCleansedCount() + 1);
	}

	public static boolean isPhase3Announced() {
		Object val = Global.getSector().getPersistentData().get(KEY_ANNOUNCED_PHASE3);
		return val instanceof Boolean && (Boolean) val;
	}

	public static void setPhase3Announced() {
		Global.getSector().getPersistentData().put(KEY_ANNOUNCED_PHASE3, true);
	}
}
