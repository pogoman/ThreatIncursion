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
	public static int maxInfestedSystems()   { return i("threatinc_maxInfestedSystems"); }
	public static int spreadMinSize()        { return i("threatinc_spreadMinSize"); }

	// ---- colonies ----

	public static float colonyGrowthBaseDays(){ return f("threatinc_colonyGrowthBaseDays"); }
	public static int colonyMaxSize()        { return i("threatinc_colonyMaxSize"); }
	/** Copies of each production-chain link the hive builds as it spreads (one per held system, up to this). */
	public static int chainRedundancy()      { return i("threatinc_chainRedundancy"); }
	public static int colonizationEscort()   { return i("threatinc_colonizationEscort"); }
	public static float garrisonRespawnDays(){ return f("threatinc_garrisonRespawnDays"); }
	public static boolean economyGatesGrowth(){ return b("threatinc_economyGatesGrowth", true); }
	/** Whether the home system's industries carry Domain items (nanoforge, mantle bore...) where its deposits fall short. */
	public static boolean homeRelics()        { return b("threatinc_homeRelics", true); }
	/** Ground (raid) strength one difficulty point of siege fleet lands - measured, about a quarter of crew capacity. */
	public static float siegeRaidStrPerPoint() { return f("threatinc_siegeRaidStrPerPoint"); }
	/** Most fleets a siege expedition grows to while sizing itself to the target's defenses. */
	public static int siegeMaxFleets()       { return i("threatinc_siegeMaxFleets"); }
	/** Chance that a forge outside the home system's Pristine one carries a Corrupted Nanoforge (fixed roll per world). */
	public static float forgeNanoforgeChance() { return f("threatinc_forgeNanoforgeChance"); }
	/** Fraction of the vanilla Core-distance accessibility penalty to cancel for hive colonies (0 = keep it, 1 = remove it). */
	public static float coreDistanceOffset()  { return f("threatinc_coreDistanceOffset"); }
	/** Same-faction shipping units a hive world keeps while its port is disrupted (0 = nothing docks, -1 = vanilla, no effect); vanilla alone lets a disrupted port keep trading at 5+ units. */
	public static int disruptedPortShipping() { return i("threatinc_disruptedPortShipping"); }
	public static boolean convertDecivWorlds(){ return b("threatinc_convertDecivWorlds", true); }

	// ---- missions (defense-board contracts) ----

	public static boolean missionsEnabled()  { return b("threatinc_missionsEnabled", true); }
	public static float missionBaseReward()  { return f("threatinc_missionBaseReward"); }
	/** Days an offer stays posted, unaccepted, before it is withdrawn. */
	public static float missionPostingDays() { return f("threatinc_missionPostingDays"); }
	/** Days the player has to complete a contract once accepted. */
	public static float missionDurationDays() { return f("threatinc_missionDurationDays"); }
	/** How many offers the defense boards keep posted at once; accepted contracts don't count. */
	public static int maxPostedMissions()    { return i("threatinc_maxPostedMissions"); }
	/** How much better a new objective must score to displace a posted offer. */
	public static float missionSupersedeMargin() { return f("threatinc_missionSupersedeMargin"); }
	/** Guaranteed life of a newly posted offer before it can be superseded. */
	public static float missionMinStandDays() { return f("threatinc_missionMinStandDays"); }
	/** How far a target's score is raised for sitting inside a faction navy's reach. */
	public static float missionProximityBonus() {
		return f("threatinc_missionProximityBonus");
	}
	/** Reward premium on the strategic (expensive) tier over the immediate tier. */
	public static float missionStrategicRewardMult() {
		return f("threatinc_missionStrategicRewardMult");
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

	// ---- hive sieges ----

	/** Flat ground-defense points per colony size (before industry multipliers). */
	public static float hiveDefensePerSize()  { return f("threatinc_hiveDefensePerSize"); }
	/** Ground Defenses structure bonus: defense mult = 1 + bonus (x2 at 1.0). */
	public static float groundDefensesBonus() { return f("threatinc_groundDefensesBonus"); }
	/** Heavy Batteries structure bonus: defense mult = 1 + bonus (x3 at 2.0). */
	public static float heavyBatteriesBonus() { return f("threatinc_heavyBatteriesBonus"); }
	/** Swarm Nexus defense bonus: defense mult = 1 + bonus (x1.5 at 0.5). */
	public static float nexusDefenseBonus()   { return f("threatinc_nexusDefenseBonus"); }
	/** Fraction of a defense structure's bonus that survives a fresh disruption. */
	public static float disruptedDefenseFraction() { return f("threatinc_disruptedDefenseFraction"); }
	/** Disruption days on a structure's clock at which its surviving bonus has worn to nothing (0 = no wear). */
	public static float defenseWearDays()     { return f("threatinc_defenseWearDays"); }
	/** Scale on the saturation fuel bill (1.0 = exactly the defense strength). */
	public static float hiveBombardCostMult() { return f("threatinc_hiveBombardCostMult"); }
	/** Tactical bombardment fuel cost as a fraction of the defense strength. */
	public static float hiveTacCostFraction() { return f("threatinc_hiveTacCostFraction"); }
	/** Days of disruption a saturation pass inflicts on hive industries. */
	public static float hiveSatDisruptDays()  { return f("threatinc_hiveSatDisruptDays"); }
	/** Days of disruption a tactical pass inflicts on hive defense structures. */
	public static float hiveTacDisruptDays()  { return f("threatinc_hiveTacDisruptDays"); }	/** Marine-loss multiplier when raiding hive worlds. */
	public static float hiveMarineLossMult()  { return f("threatinc_hiveMarineLossMult"); }

	// ---- colony decline ----

	/** Colony health below which the colony declines (loses population). */
	public static float declineHealthThreshold() { return f("threatinc_declineHealthThreshold"); }
	/** Base decline (size-step fraction) accrued per ~30-day tick while declining. */
	public static float declineBasePerTick()     { return f("threatinc_declineBasePerTick"); }
	/** Acceleration per consecutive declining tick. */
	public static float declineAccelPerTick()    { return f("threatinc_declineAccelPerTick"); }
	/** Cap on the consecutive-decline acceleration multiplier. */
	public static float declineAccelCap()        { return f("threatinc_declineAccelCap"); }
	/** Decline-meter decay per healthy tick (the hive regrows its strata). */
	public static float declineRecoveryPerTick() { return f("threatinc_declineRecoveryPerTick"); }
	/** Colony size at which the decline rate applies as-is (rate x ref/size). */
	public static float declineSizeRef()         { return f("threatinc_declineSizeRef"); }
	/** Fabrication factor while the Core is disrupted (below the threshold = forced decline). */
	public static float coreDownFactor()         { return f("threatinc_coreDownFactor"); }
	/** Fabrication factor while the Nexus is disrupted. */
	public static float nexusDownFactor()        { return f("threatinc_nexusDownFactor"); }
	/** Health at or below which growth stops entirely. */
	public static float growthStallHealth()      { return f("threatinc_growthStallHealth"); }
	/** Health at or above which the colony grows at full pace. */
	public static float growthFullHealth()       { return f("threatinc_growthFullHealth"); }

	// ---- phases ----

	public static boolean phase3Enabled()    { return b("threatinc_phase3Enabled", true); }

	// ---- strikes ----

	/** 0 means unlimited - every system with the means may have a strike in flight. */
	public static int maxConcurrentStrikes() {
		int cap = i("threatinc_maxConcurrentStrikes");
		return cap <= 0 ? Integer.MAX_VALUE : cap;
	}
	public static float strikeLYPerFuel()    { return f("threatinc_strikeLYPerFuel"); }
	/** Fuel units an expedition carries at a fleet-size figure of 100 percent (vanilla's Fleets figure; hive: vitality x size / 4). */
	public static float reachFuelCarry()     { return f("threatinc_reachFuelCarry"); }
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
	public static int   responseMinDifficulty()  { return i("threatinc_responseMinDifficulty"); }
	public static int   responseMaxDifficulty()  { return i("threatinc_responseMaxDifficulty"); }
	public static float responseStrengthDivisor(){ return f("threatinc_responseStrengthDivisor"); }
	public static boolean responsePurgeEnabled() { return b("threatinc_responsePurgeEnabled", true); }
	public static float purgeCooldownDays()      { return f("threatinc_purgeCooldownDays"); }
	/** Max colony size navies preemptively purge while its garrison still lives; 0 disables. */
	public static int purgePreemptMaxSize()      { return i("threatinc_purgePreemptMaxSize"); }
	/** Cooldown multiplier for full assaults on defended entrenched colonies (rarer). */
	public static float purgeDefendedCooldownMult() { return f("threatinc_purgeDefendedCooldownMult"); }
	/** Short cooldown for follow-up expeditions against wounded colonies. */
	public static float purgeFollowUpDays()          { return f("threatinc_purgeFollowUpDays"); }

	// ---- player-commissioned expeditions ----

	/** Whether the player can commission purge expeditions from military colonies. */
	public static boolean commissionEnabled()   { return b("threatinc_commissionEnabled", true); }
	/** Credits per fleet-difficulty point of the commissioned flotilla. */
	public static float commissionCostPerPoint() { return f("threatinc_commissionCostPerPoint"); }
	/** Credits per light-year from the commissioning colony to the target system. */
	public static float commissionCostPerLY()    { return f("threatinc_commissionCostPerLY"); }

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
