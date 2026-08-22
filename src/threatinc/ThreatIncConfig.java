package threatinc;

import com.fs.starfarer.api.Global;

/**
 * Central settings accessor: LunaLib in-game menu when available, bundled
 * data/config/settings.json as the standalone fallback.
 */
public class ThreatIncConfig {

	public static final String MOD_ID = "threatinc";

	private static Boolean lunaEnabled = null;

	public static boolean lunaAvailable() {
		if (lunaEnabled == null) {
			lunaEnabled = Global.getSettings().getModManager().isModEnabled("lunalib");
		}
		return lunaEnabled;
	}

	private static int i(String key) {
		if (lunaAvailable()) {
			Integer v = LunaConfigBridge.getInt(key);
			if (v != null) return v;
		}
		return (int) Global.getSettings().getFloat(key);
	}

	private static float f(String key) {
		if (lunaAvailable()) {
			Float v = LunaConfigBridge.getFloat(key);
			if (v != null) return v;
		}
		return Global.getSettings().getFloat(key);
	}

	private static boolean b(String key, boolean def) {
		if (lunaAvailable()) {
			Boolean v = LunaConfigBridge.getBoolean(key);
			if (v != null) return v;
		}
		try {
			return Global.getSettings().getBoolean(key);
		} catch (Throwable t) {
			return def;
		}
	}

	// ---- start trigger & pacing ----

	public static boolean enabled()          { return b("threatinc_enabled", true); }
	public static boolean colonySizeTrigger(){ return b("threatinc_colonySizeTrigger", true); }
	public static int triggerColonySize()    { return i("threatinc_triggerColonySize"); }
	public static float tickDays()           { return f("threatinc_tickDays"); }
	public static int initialSeeds()         { return i("threatinc_initialSeeds"); }
	public static float seedToHiveDays()     { return f("threatinc_seedToHiveDays"); }
	public static float hiveToSaturatedDays(){ return f("threatinc_hiveToSaturatedDays"); }
	public static float spreadChancePerHive(){ return f("threatinc_spreadChancePerHive"); }
	public static float spreadChanceCap()    { return f("threatinc_spreadChanceCap"); }
	public static int maxInfestedSystems()   { return i("threatinc_maxInfestedSystems"); }

	// ---- phases ----

	public static float phase2DelayDays()    { return f("threatinc_phase2DelayDays"); }
	public static int phase2MinHives()       { return i("threatinc_phase2MinHives"); }
	public static boolean phase3Enabled()    { return b("threatinc_phase3Enabled", true); }
	public static float phase3DelayDays()    { return f("threatinc_phase3DelayDays"); }
	public static int phase3MinHives()       { return i("threatinc_phase3MinHives"); }

	// ---- strikes ----

	public static float strikeIntervalDays() { return f("threatinc_strikeIntervalDays"); }
	public static int maxConcurrentStrikes() { return i("threatinc_maxConcurrentStrikes"); }
	public static float strikeRangeLY()      { return f("threatinc_strikeRangeLY"); }
	public static float strikeStrength()     { return f("threatinc_strikeStrength"); }
	public static float playerGraceDays()    { return f("threatinc_playerGraceDays"); }

	// ---- Remnant immune system ----

	public static boolean remnantResists()   { return b("threatinc_remnantResists", true); }
	public static float machineWarWinChance(){ return f("threatinc_machineWarWinChance"); }

	// ---- debug ----

	public static boolean debugLogging()     { return b("threatinc_debugLogging", false); }
	public static boolean debugForceStart()  { return b("threatinc_debugForceStart", false); }
	public static boolean debugFastClock()   { return b("threatinc_debugFastClock", false); }
	public static boolean debugGrantSensorMods() { return b("threatinc_debugGrantSensorMods", false); }

	public static void log(String msg) {
		if (debugLogging()) {
			Global.getLogger(ThreatIncConfig.class).info("[ThreatInc] " + msg);
		}
	}
}
