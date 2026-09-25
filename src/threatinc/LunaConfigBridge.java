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
	static final int MIGRATION_VERSION = 1;

	/**
	 * LunaLib writes every default to its stored file on first launch and
	 * never updates a key already there, so a changed default never reaches a
	 * player upgrading with LunaLib. Once per version, a stored value still
	 * equal to the OLD default is moved to the new one; anything the player
	 * set by hand is left alone. 0.7.0: siegeMaxFleets 10 -> 25,
	 * reserveInitialMonths 3 -> 6 (rc1 review - the release's main balance
	 * change never applied under LunaLib).
	 */
	static void migrateStoredDefaults() {
		SettingsAPI settings = Global.getSettings();
		try {
			if (settings.fileExistsInCommon(MIGRATION_MARKER)
					&& parseVersion(settings.readTextFileFromCommon(MIGRATION_MARKER)) >= MIGRATION_VERSION) {
				return;
			}
			String path = "LunaSettings/" + ThreatIncConfig.MOD_ID + ".json";
			if (settings.fileExistsInCommon(path)) {
				JSONObject json = new JSONObject(settings.readTextFileFromCommon(path));
				boolean changed = bump(json, "threatinc_siegeMaxFleets", 10, 25, true);
				changed |= bump(json, "threatinc_reserveInitialMonths", 3, 6, false);
				if (changed) {
					settings.writeTextFileToCommon(path, json.toString(3));
					// LunaLib re-reads its stored file
					LunaSettings.SettingsCreator.refresh(ThreatIncConfig.MOD_ID);
					Global.getLogger(LunaConfigBridge.class).info(
							"[ThreatInc] LunaLib settings moved to the 0.7.0 defaults where they still held the old ones");
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
}
