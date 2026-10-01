package threatinc;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import lunalib.lunaSettings.LunaSettings;

/**
 * Thin wrapper around LunaLib's settings API. This class references LunaLib
 * types directly, so it must ONLY be loaded when LunaLib is present - callers
 * gate every use behind {@link ThreatIncConfig#lunaAvailable()}.
 */
class LunaConfigBridge {

	static Integer getInt(String key) {
		return LunaSettings.getInt(ThreatIncConfig.MOD_ID, key);
	}

	static Float getFloat(String key) {
		return LunaSettings.getFloat(ThreatIncConfig.MOD_ID, key);
	}

	static String getString(String key) {
		return LunaSettings.getString(ThreatIncConfig.MOD_ID, key);
	}

	static Boolean getBoolean(String key) {
		return LunaSettings.getBoolean(ThreatIncConfig.MOD_ID, key);
	}

	/** Common-data file recording which stored-default migration last ran; kept apart from LunaLib's own file. */
	static final String MIGRATION_MARKER = "threatinc_lunaSettingsVersion";
	static final int MIGRATION_VERSION = 9;

	/**
	 * LunaLib writes every default to its stored file on first launch and
	 * never updates a key already there, so a changed default never reaches a
	 * player upgrading with LunaLib. Once per version, a stored value still
	 * equal to the OLD default is moved to the new one; anything the player
	 * set by hand is left alone. 0.7.0 (marker 1): siegeMaxFleets 10 -> 25,
	 * reserveInitialMonths 3 -> 6 (rc1 review - the release's main balance
	 * change never applied under LunaLib). Versions 2 and 3 (2026-09-26, run
	 * 7) only ever ran on dev installs: siegeMaxFleets 25 -> 40 -> 50, and
	 * frontlineGarrisonFP 400 -> 200, a knob 0.7.0 did not have. A released
	 * store therefore goes 25 -> 50 in one step (the bumps chain, so one still
	 * at 10 lands on 50) and a 40 set by hand is left alone; only a store the
	 * version-2 migration itself moved to 40 goes on to 50. Version 4 (run
	 * 17): expeditionSuppliesPerPoint 100 -> 30 - Hegemony's sieges waited 16
	 * months on 32,600-supply bills. Version 5 (bombardment v2 run 4):
	 * bombardReturnFirePerGunDefence 0.008 -> 0.0008. Version 6 (bombardment
	 * v2): frontDangerCloseLossFraction 0.05 -> 0.005 per day of bombardment.
	 * Version 7: coreDownFactor 1.0 -> 0.8 (bombardment v2), and
	 * responseStrengthDivisor 20 -> 30 - its menu maximum was 20 under a
	 * default of 30 until 0.7.0, so LunaLib stored the clamp. Version 8 (the
	 * attack planner): npcSiegeOrbitSystem true -> false. Version 9:
	 * npcSiegeOrbitMargin back to 1.5 where version 8, on dev installs, had
	 * moved it to 1.0.
	 */
	static void migrateStoredDefaults() {
		SettingsAPI settings = Global.getSettings();
		try {
			int from = settings.fileExistsInCommon(MIGRATION_MARKER)
					? parseVersion(settings.readTextFileFromCommon(MIGRATION_MARKER)) : 0;
			if (from >= MIGRATION_VERSION) return;
			String path = "LunaSettings/" + ThreatIncConfig.MOD_ID + ".json";
			if (settings.fileExistsInCommon(path)) {
				JSONObject json = new JSONObject(settings.readTextFileFromCommon(path));
				// each version's bumps only once: a value set back by hand after
				// an earlier migration is the player's
				boolean changed = false;
				// (2026-09-29: the siegeMaxFleets bumps went with the knob - sieges have no fleet cap)
				if (from < 1) {
					changed |= bump(json, "threatinc_reserveInitialMonths", 3, 6, false);
				}
				if (from < 4) {
					changed |= bump(json, "threatinc_expeditionSuppliesPerPoint", 100, 30, false);
				}
				if (from < 5) {
					changed |= bump(json, "threatinc_bombardReturnFirePerGunDefence", 0.008, 0.0008, false);
				}
				if (from < 6) {
					changed |= bump(json, "threatinc_frontDangerCloseLossFraction", 0.05, 0.005, false);
				}
				if (from < 7) {
					changed |= bump(json, "threatinc_coreDownFactor", 1.0, 0.8, false);
					changed |= bump(json, "threatinc_responseStrengthDivisor", 20, 30, false);
				}
				// 2026-10-01, the attack planner: a siege weighs the strongest world it
				// takes (docs/attack-planner.md)
				if (from < 8) {
					changed |= bumpBoolean(json, "threatinc_npcSiegeOrbitSystem", true, false);
				}
				// version 8 also took the margin 1.5 -> 1.0, on dev installs only; h50a
				// landed half the sieges, so 1.5 is back per world (user, 2026-10-01).
				// Only a store version 8 moved returns; a released one never left 1.5
				if (from == 8) {
					changed |= bump(json, "threatinc_npcSiegeOrbitMargin", 1.0, 1.5, false);
				}
				if (changed) {
					settings.writeTextFileToCommon(path, json.toString(3));
					// LunaLib re-reads its stored file
					LunaSettings.SettingsCreator.refresh(ThreatIncConfig.MOD_ID);
					Global.getLogger(LunaConfigBridge.class).info(
							"[ThreatInc] LunaLib settings moved to the current defaults where they still held the old ones");
				}
			}
			settings.writeTextFileToCommon(MIGRATION_MARKER, String.valueOf(MIGRATION_VERSION));
		} catch (Throwable t) {
			Global.getLogger(LunaConfigBridge.class).warn("[ThreatInc] LunaLib settings migration skipped", t);
		}
	}

	protected static int parseVersion(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (Throwable t) {
			return 0;
		}
	}

	/**
	 * Moves a stored value still at {@code oldDefault} to {@code newDefault};
	 * true when it did. An Int setting is written as an integer: LunaLib parses
	 * its stored text, and "25.0" is no int.
	 */
	protected static boolean bump(JSONObject json, String key, double oldDefault, double newDefault,
			boolean asInt) throws Exception {
		if (!json.has(key)) return false;
		if (Math.abs(json.getDouble(key) - oldDefault) > 0.001) return false;
		if (asInt) json.put(key, (int) Math.round(newDefault));
		else json.put(key, newDefault);
		return true;
	}

	/**
	 * The Boolean twin of bump: getDouble on a stored JSON boolean throws,
	 * and that would abort the whole migration on every launch.
	 */
	protected static boolean bumpBoolean(JSONObject json, String key, boolean oldDefault, boolean newDefault)
			throws Exception {
		if (!json.has(key)) return false;
		Object stored = json.get(key);
		boolean value;
		if (stored instanceof Boolean) value = (Boolean) stored;
		else if (stored instanceof String) value = Boolean.parseBoolean(((String) stored).trim());
		else return false;
		if (value != oldDefault) return false;
		json.put(key, newDefault);
		return true;
	}
}
