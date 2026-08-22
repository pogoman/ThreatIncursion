package threatinc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;

/**
 * Persistent incursion state. Stage strings per system id, hive fleet
 * references, timestamps, and counters - all in sector persistent data.
 */
public class ThreatIncData {

	public static final String STAGE_SEEDED = "seeded";
	public static final String STAGE_HIVE = "hive";
	public static final String STAGE_SATURATED = "saturated";

	public static final String KEY_STARTED = "threatinc_started";
	public static final String KEY_START_TIMESTAMP = "threatinc_startTimestamp";
	public static final String KEY_STAGES = "threatinc_stages";
	public static final String KEY_STAGE_TIMES = "threatinc_stageTimes";
	public static final String KEY_HIVES = "threatinc_hiveFleets";
	public static final String KEY_LAST_STRIKE_TIMES = "threatinc_lastStrikeTimes";
	public static final String KEY_PLAYER_STRUCK_AT = "threatinc_playerStruckAt";
	public static final String KEY_SYSTEMS_CLEANSED = "threatinc_systemsCleansed";
	public static final String KEY_ANNOUNCED_PHASE3 = "threatinc_announcedPhase3";

	@SuppressWarnings("unchecked")
	private static <T> Map<String, T> map(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, T>();
			Global.getSector().getPersistentData().put(key, val);
		}
		return (Map<String, T>) val;
	}

	public static Map<String, String> stages() {
		return map(KEY_STAGES);
	}

	public static Map<String, Long> stageTimes() {
		return map(KEY_STAGE_TIMES);
	}

	public static Map<String, CampaignFleetAPI> hives() {
		return map(KEY_HIVES);
	}

	public static Map<String, Long> lastStrikeTimes() {
		return map(KEY_LAST_STRIKE_TIMES);
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

	public static int countStage(String stage) {
		int count = 0;
		for (String s : stages().values()) {
			if (s.equals(stage)) count++;
		}
		return count;
	}

	public static int countHivesAndSaturated() {
		return countStage(STAGE_HIVE) + countStage(STAGE_SATURATED);
	}

	public static int countInfested() {
		return stages().size();
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
