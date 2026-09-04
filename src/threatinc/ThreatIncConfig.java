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
	/** Whether colonies redistribute Defense Swarms to reinforce worn-down siblings. */
	public static boolean reinforceEnabled()  { return b("threatinc_reinforceEnabled", true); }
	/** Max reinforcement swarms dispatched per colony poll (~half a day). */
	public static int reinforceMaxPerPoll()   { return i("threatinc_reinforceMaxPerPoll"); }
	/** A garrison swarm below this fraction of its fabricated fleet points no longer holds its slot. */
	public static float garrisonUnderStrengthFraction() { return f("threatinc_garrisonUnderStrengthFraction"); }
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
	public static float hiveTacDisruptDays()  { return f("threatinc_hiveTacDisruptDays"); }
	/** Marine-loss multiplier when raiding hive worlds. */
	public static float hiveMarineLossMult()  { return f("threatinc_hiveMarineLossMult"); }
	/** Resilience above which an NPC siege keeps tactical-bombing before it raids. */
	public static float siegeDefenseSoftenFloor() { return f("threatinc_siegeDefenseSoftenFloor"); }

	// ---- ground fronts (docs/ground-war.md) ----

	/** Master switch for the ground-front siege mechanic. */
	public static boolean frontsEnabled()     { return b("threatinc_frontsEnabled", true); }
	/** Effective strength (marines x entrenchment) as a fraction of the defense figure needed to HOLD (suppress every key structure). */
	public static float frontHoldFraction()   { return f("threatinc_frontHoldFraction"); }
	/** Fraction of the defense figure needed to GRIND (harass only the defense structures, at the reduced rate below). */
	public static float frontGrindFraction()  { return f("threatinc_frontGrindFraction"); }
	/** Days added to a suppressed structure's disruption clock per day held (the clock counts down 1/day naturally, so 2.0 nets +1). */
	public static float frontSuppressDaysPerDay() { return f("threatinc_frontSuppressDaysPerDay"); }
	/** Suppression-rate multiplier while only grinding. */
	public static float frontGrindSuppressMult() { return f("threatinc_frontGrindSuppressMult"); }
	/** Fraction of the front's current marines lost per 30 days while supplied. */
	public static float frontMarineLossPer30Days() { return f("threatinc_frontMarineLossPer30Days"); }
	/** Attrition multiplier once the heavy armaments run out. */
	public static float frontUnsuppliedLossMult() { return f("threatinc_frontUnsuppliedLossMult"); }
	/** Heavy armaments consumed per colony size per 30 days - the front's upkeep. */
	public static float frontArmamentsPerSizePer30Days() { return f("threatinc_frontArmamentsPerSizePer30Days"); }
	/** Days of entrenchment to reach the full effectiveness multiplier. */
	public static float frontEntrenchDays()   { return f("threatinc_frontEntrenchDays"); }
	/** Effectiveness multiplier of a fully entrenched front. */
	public static float frontEntrenchMaxMult() { return f("threatinc_frontEntrenchMaxMult"); }
	/** Marines below which a front collapses outright. */
	public static float frontMinMarines()     { return f("threatinc_frontMinMarines"); }
	/** Whether a tactical pass with a front deployed costs front marines (and cracks the deep organs in exchange). */
	public static boolean frontDangerCloseEnabled() { return b("threatinc_frontDangerCloseEnabled", true); }
	/** Fraction of the front's marines lost to a danger-close tactical pass. */
	public static float frontDangerCloseLossFraction() { return f("threatinc_frontDangerCloseLossFraction"); }
	/** Days saturation fallout blocks landing ground forces (keep >= hiveSatDisruptDays or sat bombing becomes the best siege opener). */
	public static float falloutDays()         { return f("threatinc_falloutDays"); }

	// ---- stratum campaign: pushes, counter-attacks, eradication ----

	/** Base days per stratum push at even strength (scaled by defense/strength, clamped 0.5x-3x). */
	public static float frontPushBaseDays()   { return f("threatinc_frontPushBaseDays"); }
	/** Fraction of the front's marines lost per 30 days while pushing (replaces the entrenched rate). */
	public static float frontPushLossPer30Days() { return f("threatinc_frontPushLossPer30Days"); }
	/** Armaments-upkeep multiplier while pushing. */
	public static float frontPushUpkeepMult() { return f("threatinc_frontPushUpkeepMult"); }
	/** Effectiveness multiplier of a dry (no armaments) front. */
	public static float frontDryEffectivenessMult() { return f("threatinc_frontDryEffectivenessMult"); }
	/** Defense multiplier of an entrenched (non-pushing) front against counter-attacks. */
	public static float frontEntrenchDefenseBonus() { return f("threatinc_frontEntrenchDefenseBonus"); }
	/** Days a front consolidates at each stratum checkpoint before pushing on by doctrine. */
	public static float frontCheckpointDays() { return f("threatinc_frontCheckpointDays"); }
	/** Base days between hive counter-attacks (divided by colony health - starved hives barely attack). */
	public static float frontCounterAttackDays() { return f("threatinc_frontCounterAttackDays"); }
	/** Fraction of the front's marines lost to a counter-attack it fails to repel. */
	public static float frontCounterAttackLossFraction() { return f("threatinc_frontCounterAttackLossFraction"); }
	/** Days of armaments supply an NPC expedition's landing force carries. */
	public static float npcFrontSupplyDays()  { return f("threatinc_npcFrontSupplyDays"); }

	// ---- colony vitality ----

	/** Fabrication factor while the Core is disrupted (weakens the colony; only ground victory kills it). */
	public static float coreDownFactor()         { return f("threatinc_coreDownFactor"); }
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

	// ---- strategy layer: war mode, reserves, convoys (docs/strategy-layer.md) ----

	/** Master switch for war mode, per-colony reserves, convoys and fleet orders. */
	public static boolean strategyEnabled()  { return b("threatinc_strategyEnabled", true); }
	/** Days after its last strike a faction stands down (if no hive is in reach); 0 = never. */
	public static float warModeStandDownDays() { return f("threatinc_warModeStandDownDays"); }
	/** Reserve marines accrued per unit of vanilla marine production per 30 days. */
	public static float reserveMarinesPerUnit() { return f("threatinc_reserveMarinesPerUnit"); }
	/** Reserve heavy armaments per unit of hand-weapon production per 30 days. */
	public static float reserveArmamentsPerUnit() { return f("threatinc_reserveArmamentsPerUnit"); }
	/** Reserve fuel per unit of fuel production per 30 days. */
	public static float reserveFuelPerUnit()  { return f("threatinc_reserveFuelPerUnit"); }
	/** Reserve supplies per unit of supply production per 30 days. */
	public static float reserveSuppliesPerUnit() { return f("threatinc_reserveSuppliesPerUnit"); }
	/** Months of its own production a colony stockpiles at most. */
	public static float reserveCapMonths()    { return f("threatinc_reserveCapMonths"); }
	/** Months of production each colony holds the moment its faction mobilises. */
	public static float reserveInitialMonths() { return f("threatinc_reserveInitialMonths"); }
	/** Fraction of a colony's cap that expeditions and sorties never draw it below (the home garrison's stock). */
	public static float reserveFloorFraction() { return f("threatinc_reserveFloorFraction"); }
	/** Militia marines every colony accrues per size per 30 days, regardless of industry. */
	public static float reserveBaselinePerSize() { return f("threatinc_reserveBaselinePerSize"); }
	/** Exponent on every ground-war strength ratio: 1 = linear (default), 2 = Lanchester square law. */
	public static float groundStrengthExponent() { return f("threatinc_groundStrengthExponent"); }
	/** Fraction of the marines an expedition wants that its base must hold, or it waits. */
	public static float expeditionMinMarinesFraction() { return f("threatinc_expeditionMinMarinesFraction"); }
	/** Fuel an expedition draws per fleet point per light-year. */
	public static float expeditionFuelPerPointLY() { return f("threatinc_expeditionFuelPerPointLY"); }
	/** Supplies an expedition draws per fleet point. */
	public static float expeditionSuppliesPerPoint() { return f("threatinc_expeditionSuppliesPerPoint"); }
	/** Troop-transport share of a cargo-carrying expedition fleet (vanilla composition multiplier). */
	public static float expeditionTransportMult() { return f("threatinc_expeditionTransportMult"); }
	/** Whether mobilised factions run supply convoys between their colonies. */
	public static boolean convoyEnabled()     { return b("threatinc_convoyEnabled", true); }
	/** Light-years a donor colony will ship to a staging base. */
	public static float convoyRangeLY()       { return f("threatinc_convoyRangeLY"); }
	/** Marines one convoy carries at most. */
	public static float convoyMarineCapacity() { return f("threatinc_convoyMarineCapacity"); }
	/** Cargo units (armaments, fuel, supplies) one convoy carries at most. */
	public static float convoyCargoCapacity() { return f("threatinc_convoyCargoCapacity"); }
	/** Base combat fleet points escorting a convoy. */
	public static float convoyEscortFP()      { return f("threatinc_convoyEscortFP"); }
	/** Extra escort fleet points per 1,000 of cargo value (marines 1, armaments 0.5, fuel/supplies 0.1). */
	public static float convoyEscortPerThousand() { return f("threatinc_convoyEscortPerThousand"); }
	/** Whether hive colonies detach Defense Swarms to hunt convoys passing near them. */
	public static boolean raiderEnabled()     { return b("threatinc_raiderEnabled", true); }
	/** Light-years from a convoy route's midpoint within which a hive may send a raider. */
	public static float raiderRangeLY()       { return f("threatinc_raiderRangeLY"); }
	/** Chance each eligible hive colony detaches a raider at a convoy (nearest rolls first, one raider per convoy). */
	public static float raiderChance()        { return f("threatinc_raiderChance"); }
	/** Days a raider hunts before turning home. */
	public static float raiderDays()          { return f("threatinc_raiderDays"); }
	/** Shortfall (in convoy loads) below which no convoy sails. */
	public static float convoyMinLoadFraction() { return f("threatinc_convoyMinLoadFraction"); }
	/** Convoys one faction dispatches per slow tick at most (the neediest bases first). */
	public static int convoyMaxPerTick()      { return i("threatinc_convoyMaxPerTick"); }
	/** Whether mobilised factions run supply and withdrawal convoys to their ground fronts. */
	public static boolean frontRunsEnabled()  { return b("threatinc_frontRunsEnabled", true); }
	/** Days of armaments a supply run tops a front up to. */
	public static float frontResupplyDays()   { return f("threatinc_frontResupplyDays"); }
	/** Fraction of a front's peak strength a supply run reinforces it back toward. */
	public static float frontReinforceFraction() { return f("threatinc_frontReinforceFraction"); }
	/** Days a front run waits at the hive system's jump-point for the orbit to clear before turning home. */
	public static float frontRunWaitDays()    { return f("threatinc_frontRunWaitDays"); }
	/** Fraction of its own cap a donor colony keeps back. */
	public static float donorKeepFraction()   { return f("threatinc_donorKeepFraction"); }
	/** Staging target as a multiple of one expedition's draw. */
	public static float stagingTargetMult()   { return f("threatinc_stagingTargetMult"); }
	/** Days after which a convoy that has not arrived is written off. */
	public static float convoyTimeoutDays()   { return f("threatinc_convoyTimeoutDays"); }
	/** Whether the war board's fleet orders (guard, stage, intercept, siege, recall) are offered. */
	public static boolean ordersEnabled()     { return b("threatinc_ordersEnabled", true); }
	/** Relationship (-1..1) with an allied faction at which it takes the player's orders. */
	public static float orderMinRelation()    { return f("threatinc_orderMinRelation"); }
	/** Combat fleet points of a guard or intercept task force. */
	public static float guardFleetFP()        { return f("threatinc_guardFleetFP"); }
	/** Days a guard task force holds a colony's orbit. */
	public static float guardDays()           { return f("threatinc_guardDays"); }
	/** Days an intercept task force holds a hive system's jump-point. */
	public static float interceptDays()       { return f("threatinc_interceptDays"); }
	/** Fraction of the fuel and supplies drawn at launch refunded when a fleet returns home at full strength. */
	public static float returnRefundMult()    { return f("threatinc_returnRefundMult"); }

	// ---- escalation: grudge and alarm (docs/design-theory.md 8.1) ----

	/** Master switch for grudge, alarm and retaliation. */
	public static boolean alarmEnabled()      { return b("threatinc_alarmEnabled", true); }
	/** Grudge points a faction earns per stratum its front takes. */
	public static float alarmPerStratum()     { return f("threatinc_alarmPerStratum"); }
	/** Grudge points per hive eradicated. */
	public static float alarmPerEradication() { return f("threatinc_alarmPerEradication"); }
	/** Grudge points per successful raid or tactical pass on a hive world. */
	public static float alarmPerRaid()        { return f("threatinc_alarmPerRaid"); }
	/** Fraction of every grudge that fades per 30 days. */
	public static float alarmDecayPer30()     { return f("threatinc_alarmDecayPer30"); }
	/** Fabrication-speed bonus per point of alarm (0.05 = alarm 10 is x1.5). */
	public static float alarmTempoMult()      { return f("threatinc_alarmTempoMult"); }
	/** Cap on the alarm fabrication multiplier. */
	public static float alarmTempoMax()       { return f("threatinc_alarmTempoMax"); }
	/** Strike-target weight bonus per point of a faction's grudge (0.2 = grudge 10 is x3). */
	public static float alarmTargetMult()     { return f("threatinc_alarmTargetMult"); }
	/** Whether a ground victory draws an immediate strike at the winner. */
	public static boolean retaliationEnabled() { return b("threatinc_retaliationEnabled", true); }

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
