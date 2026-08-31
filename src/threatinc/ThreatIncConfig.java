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
	public static boolean startAtGameStart() { return b("threatinc_startAtGameStart", false); }
	public static boolean colonySizeTrigger(){ return b("threatinc_colonySizeTrigger", true); }
	public static int triggerColonySize()    { return i("threatinc_triggerColonySize"); }
	public static float tickDays()           { return f("threatinc_tickDays"); }
	public static int initialSeeds()         { return i("threatinc_initialSeeds"); }
	public static float seedToColonyDays()   { return f("threatinc_seedToColonyDays"); }
	public static float launchDisruptDays()  { return f("threatinc_launchDisruptDays"); }
	public static int maxInfestedSystems()   { return i("threatinc_maxInfestedSystems"); }
	public static int spreadMinSize()        { return i("threatinc_spreadMinSize"); }

	// ---- colonies ----

	public static float colonyGrowthBaseDays(){ return f("threatinc_colonyGrowthBaseDays"); }
	public static int colonyMaxSize()        { return i("threatinc_colonyMaxSize"); }
	/** Colony size at which the founding Spaceport is upgraded to a Megaport. */
	public static int colonyMegaportMinSize(){ return i("threatinc_colonyMegaportMinSize"); }
	public static int colonizationEscort()   { return i("threatinc_colonizationEscort"); }
	public static float garrisonRespawnDays(){ return f("threatinc_garrisonRespawnDays"); }
	public static boolean economyGatesGrowth(){ return b("threatinc_economyGatesGrowth", true); }
	/** Fraction of the vanilla Core-distance accessibility penalty to cancel for hive colonies (0 = keep it, 1 = remove it). */
	public static float coreDistanceOffset()  { return f("threatinc_coreDistanceOffset"); }
	public static boolean convertDecivWorlds(){ return b("threatinc_convertDecivWorlds", true); }

	// ---- bounties ----

	public static boolean bountiesEnabled()  { return b("threatinc_bountiesEnabled", true); }
	public static float bountyBaseReward()   { return f("threatinc_bountyBaseReward"); }
	public static float bountyDurationDays() { return f("threatinc_bountyDurationDays"); }
	/** How many objectives the defense boards keep standing at once. */
	public static int maxActiveBounties()    { return i("threatinc_maxActiveBounties"); }
	/** How much better a new objective must score to displace a standing one. */
	public static float bountySupersedeMargin() { return f("threatinc_bountySupersedeMargin"); }
	/** Guaranteed life of a newly posted objective before it can be superseded. */
	public static float bountyMinStandDays() { return f("threatinc_bountyMinStandDays"); }
	/** How far a target's score is raised for sitting inside a faction navy's reach. */
	public static float bountyProximityBonus() {
		return f("threatinc_bountyProximityBonus");
	}
	/** Reward premium on the strategic (expensive) tier over the immediate tier. */
	public static float bountyStrategicRewardMult() {
		return f("threatinc_bountyStrategicRewardMult");
	}
	/** Whether breaking a staging colony's forge (raid/bombardment/deciv) recalls its in-flight strikes. */
	public static boolean strikeRecallEnabled() {
		return b("threatinc_strikeRecallEnabled", true);
	}
	/** Whether strikes may target and decivilize story-critical worlds (the engine refuses the kill otherwise). */
	public static boolean destroyStoryCritical() {
		return b("threatinc_destroyStoryCritical", true);
	}
	/** Overall scale on hive ground-defense strength (= saturation bombardment fuel cost). */
	public static float groundDefenseMult() {
		return f("threatinc_groundDefenseMult");
	}
	/** Whether the Fragment Fabricator screens Threat colonies from player bombardment until stolen. */
	public static boolean fragmentShieldEnabled() {
		return b("threatinc_fragmentShieldEnabled", true);
	}

	// ---- phases ----

	public static boolean phase3Enabled()    { return b("threatinc_phase3Enabled", true); }

	// ---- strikes ----

	/** 0 means unlimited - every system with the means may have a strike in flight. */
	public static int maxConcurrentStrikes() {
		int cap = i("threatinc_maxConcurrentStrikes");
		return cap <= 0 ? Integer.MAX_VALUE : cap;
	}
	public static float strikeLYPerFuel()    { return f("threatinc_strikeLYPerFuel"); }
	public static int strikeMinSize()        { return i("threatinc_strikeMinSize"); }
	public static float strikeStrengthMult() { return f("threatinc_strikeStrengthMult"); }
	public static float playerGraceDays()    { return f("threatinc_playerGraceDays"); }

	// ---- faction reactive defense ----

	public static boolean responseEnabled()      { return b("threatinc_responseEnabled", true); }
	/** 0 means unlimited, mirroring maxConcurrentStrikes. */
	public static int   responseMaxConcurrent()  {
		int cap = i("threatinc_responseMaxConcurrent");
		return cap <= 0 ? Integer.MAX_VALUE : cap;
	}
	public static float responseRangeLY()        { return f("threatinc_responseRangeLY"); }
	public static int   responseMinDifficulty()  { return i("threatinc_responseMinDifficulty"); }
	public static int   responseMaxDifficulty()  { return i("threatinc_responseMaxDifficulty"); }
	public static float responseStrengthDivisor(){ return f("threatinc_responseStrengthDivisor"); }
	public static boolean responsePurgeEnabled() { return b("threatinc_responsePurgeEnabled", true); }
	public static float purgeCooldownDays()      { return f("threatinc_purgeCooldownDays"); }
	/** Max colony size navies preemptively purge while its garrison still lives; 0 disables. */
	public static int purgePreemptMaxSize()      { return i("threatinc_purgePreemptMaxSize"); }

	// ---- Remnant immune system ----

	public static boolean remnantResists()   { return b("threatinc_remnantResists", true); }
	public static float machineWarWinChance(){ return f("threatinc_machineWarWinChance"); }

	// ---- faction relations ----

	/** Pin the Threat faction to vengeful with every other faction (perma-hostile to all). */
	public static boolean permaHostile()     { return b("threatinc_permaHostile", true); }
	/** Waive the vanilla saturation-bombardment atrocity reputation penalty when the bombed colony is a Threat colony. */
	public static boolean bombardNoAtrocity() { return b("threatinc_bombardNoAtrocity", true); }

	// ---- debug ----

	public static boolean debugMode()        { return b("threatinc_debugMode", false); }
	public static boolean debugLogging()     { return b("threatinc_debugLogging", false); }
	public static boolean debugForceStart()  { return b("threatinc_debugForceStart", false); }
	public static boolean debugFastClock()   { return b("threatinc_debugFastClock", false); }
	public static boolean debugGrantSensorMods() { return b("threatinc_debugGrantSensorMods", false); }
	public static boolean debugReset()       { return b("threatinc_debugReset", false); }

	public static void log(String msg) {
		if (debugLogging()) {
			Global.getLogger(ThreatIncConfig.class).info("[ThreatInc] " + msg);
		}
	}
}
