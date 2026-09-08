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

	private static String s(String key, String def) {
		if (lunaAvailable()) {
			try {
				String v = LunaConfigBridge.getString(key);
				if (v != null) return v;
			} catch (Throwable t) {
				// an older LunaLib without string fields: fall through
			}
		}
		try {
			String v = Global.getSettings().getString(key);
			return v != null ? v : def;
		} catch (Throwable t) {
			return def;
		}
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
	public static boolean startAtGameStart() { return b("threatinc_startAtGameStart", true); }
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
	/** Military options menu: how far (su) from a hive's world a defending swarm fleet can be engaged from its orbit; any swarm fleet inside also counts as a defender. */
	public static float defendRadius()      { return f("threatinc_defendRadius"); }
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
	/** Siege passes (tactical bombardment / landing / commando raid) an expedition may run per colony. */
	public static int siegePassesPerColony() { return i("threatinc_siegePassesPerColony"); }
	public static boolean siegeFightsForOrbit() { return b("threatinc_siegeFightsForOrbit", true); }
	/** How far from a contested world an expedition fleet may hunt for its orbit. */
	public static float siegeHuntRange()     { return f("threatinc_siegeHuntRange"); }
	/** How far from its nearest target an expedition fleet may stray before it is recalled. */
	public static float siegeLeashRange()    { return f("threatinc_siegeLeashRange"); }
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

	// ---- Threat strike doctrine (docs/ground-war.md "Threat ground assaults") ----

	/** Whether Threat strikes still saturation-bomb (the pre-ground-front doctrine). Default OFF. */
	public static boolean strikeSaturationEnabled() {
		return b("threatinc_strikeSaturationEnabled", false);
	}
	/** Whether a Threat strike lands a Threat-owned ground front once the defenses are softened. */
	public static boolean strikeGroundFrontsEnabled() {
		return b("threatinc_strikeGroundFrontsEnabled", true);
	}
	/** Passes a Threat expedition delivers per world: one softens, one lands the world's share of the pool, any more top it up. */
	public static int strikePassesPerColony() { return i("threatinc_strikePassesPerColony"); }
	/** The troop pool a strike carries, per difficulty point of the swarms it musters - fixed at launch. */
	public static float strikeTroopsPerPoint() { return f("threatinc_strikeTroopsPerPoint"); }
	/** A world's share of the pool is never below this: a small strike lands fewer worlds, not token forces. */
	public static int strikeFrontMinTroops()  { return i("threatinc_strikeFrontMinTroops"); }
	/** Days of armaments a Threat landing carries (it is never resupplied from a base). */
	public static float strikeFrontSupplyDays() { return f("threatinc_strikeFrontSupplyDays"); }
	/** How much of a colony's banked reserve marines defend the ground against a front. */
	public static float reserveDefenseMult()  { return f("threatinc_reserveDefenseMult"); }
	/** Counter-attack cadence bonus for a colony with a military industry. */
	public static float colonyCounterAttackMilitaryMult() {
		return f("threatinc_colonyCounterAttackMilitaryMult");
	}

	// ---- sieges from orbit (docs/ground-war.md "Sieges from orbit") ----

	/** Disruption days at which a colony's defence structure contributes nothing (its bonus scales down linearly to it). */
	public static float fortificationDisruptDays() { return f("threatinc_fortificationDisruptDays"); }
	/** Fraction of a suppressed structure's bonus orbit alone cannot take away, hive or colony; 0 once a front stands on the world. */
	public static float fortificationOrbitFloor() { return f("threatinc_fortificationOrbitFloor"); }
	/** Disruption days per day an unopposed siege fleet adds to a colony's fortifications at overwhelming strength, scaled by fleet / (fleet + defence). */
	public static float siegeSuppressDaysPerDay() { return f("threatinc_siegeSuppressDaysPerDay"); }
	/** The same, over a hive's war-strata (their clock is defenseWearDays). */
	public static float hiveSiegeSuppressDaysPerDay() { return f("threatinc_hiveSiegeSuppressDaysPerDay"); }
	/** Days of siege one tactical bombardment by the player stands for. */
	public static float siegeBombardSliceDays() { return f("threatinc_siegeBombardSliceDays"); }

	/** Whether a planetary shield absorbs part of the disruption a bombardment lands on everything else. */
	public static boolean shieldAbsorbEnabled() { return b("threatinc_shieldAbsorbEnabled", true); }
	/** Fraction of incoming disruption a fully intact planetary shield turns aside; scales down with its own disruption clock. */
	public static float shieldAbsorbMax()     { return f("threatinc_shieldAbsorbMax"); }
	/** Disruption a planetary shield takes itself, as a multiple of what a fortification would take from the same pass. */
	public static float shieldSoakMult()      { return f("threatinc_shieldSoakMult"); }
	/** What a planetary shield adds to the garrison at full condition; 0 removes vanilla's x3 outright. */
	public static float shieldDefenseBonus()  { return f("threatinc_shieldDefenseBonus"); }

	/** Fleet points are multiplied by this before being weighed against the ground-defence figure. */
	public static float siegeFPWeight()       { return f("threatinc_siegeFPWeight"); }
	/** Fraction of an orbiting fleet's points the colony's batteries destroy per day at even odds. */
	public static float siegeBatteryAttritionPerDay() { return f("threatinc_siegeBatteryAttritionPerDay"); }
	/** Days a strike expedition holds orbit over a system before giving its siege up. */
	public static float siegeOrbitDays()      { return f("threatinc_siegeOrbitDays"); }
	/** Strike-target weight multiplier for a world whose Threat front is dry and signalling for the next expedition. */
	public static float strikeReinforceWeight() { return f("threatinc_strikeReinforceWeight"); }
	/** Days a dry Threat front holds for the next expedition before its final push. */
	public static float frontDryFinalPushDays() { return f("threatinc_frontDryFinalPushDays"); }
	/** Stability lost per district an invader holds. */
	public static float districtStabilityPenalty() { return f("threatinc_districtStabilityPenalty"); }
	/** Accessibility lost per district an invader holds. */
	public static float districtAccessPenalty() { return f("threatinc_districtAccessPenalty"); }
	/** Disruption days a seized civilian industry is pinned at while the invader holds its district. */
	public static float districtSeizeDays()   { return f("threatinc_districtSeizeDays"); }
	/** Whether a Threat ground victory seeds a hive on the spot (off: the world decivilises). */
	public static boolean conquestConverts()  { return b("threatinc_conquestConverts", true); }
	/** Size of the hive seeded on a conquered world. */
	public static int conquestHiveSize()      { return i("threatinc_conquestHiveSize"); }

	// ---- hive sieges ----

	/** Flat ground-defense points per colony size (before industry multipliers). */
	public static float hiveDefensePerSize()  { return f("threatinc_hiveDefensePerSize"); }
	/** Ground Defenses structure bonus: defense mult = 1 + bonus (x2 at 1.0). */
	public static float groundDefensesBonus() { return f("threatinc_groundDefensesBonus"); }
	/** Heavy Batteries structure bonus: defense mult = 1 + bonus (x3 at 2.0). */
	public static float heavyBatteriesBonus() { return f("threatinc_heavyBatteriesBonus"); }
	/** Swarm Nexus defense bonus: defense mult = 1 + bonus (x1.5 at 0.5). */
	public static float nexusDefenseBonus()   { return f("threatinc_nexusDefenseBonus"); }
	/** Disruption days on a structure's clock at which its bonus has worn to nothing (0 = no wear); the hive's fortification clock. */
	public static float defenseWearDays()     { return f("threatinc_defenseWearDays"); }
	/** Scale on the saturation fuel bill (1.0 = exactly the defense strength). */
	public static float hiveBombardCostMult() { return f("threatinc_hiveBombardCostMult"); }
	/** Tactical bombardment fuel cost as a fraction of the defense strength. */
	public static float hiveTacCostFraction() { return f("threatinc_hiveTacCostFraction"); }
	/** Days of disruption a saturation pass inflicts on hive industries. */
	public static float hiveSatDisruptDays()  { return f("threatinc_hiveSatDisruptDays"); }
	/** Days of disruption a danger-close tactical pass writes to a hive's Core and port (the war-strata take the siege slice). */
	public static float hiveTacDisruptDays()  { return f("threatinc_hiveTacDisruptDays"); }
	/** Marine-loss multiplier when raiding hive worlds. */
	public static float hiveMarineLossMult()  { return f("threatinc_hiveMarineLossMult"); }

	// ---- ground fronts (docs/ground-war.md) ----

	/** Master switch for the ground-front siege mechanic. */
	public static boolean frontsEnabled()     { return b("threatinc_frontsEnabled", true); }
	/** Troops that land push: a front at holding strength assaults as soon as it is on the ground. */
	public static boolean frontAutoPush()     { return b("threatinc_frontAutoPush", true); }
	/** Whether a front holding no ground breaks off its assault and digs in when an assault would get it overrun. */
	public static boolean frontBraceEnabled() { return b("threatinc_frontBraceEnabled", true); }
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
	/** Heavy armaments consumed per marine per 30 days - the front's upkeep. */
	public static float frontArmamentsPerMarinePer30Days() { return f("threatinc_frontArmamentsPerMarinePer30Days"); }
	/** Days of entrenchment to reach the full effectiveness multiplier. */
	public static float frontEntrenchDays()   { return f("threatinc_frontEntrenchDays"); }
	/** Effectiveness multiplier of a fully entrenched front. */
	public static float frontEntrenchMaxMult() { return f("threatinc_frontEntrenchMaxMult"); }
	/** Effectiveness multiplier of a fresh landing, before any entrenchment. */
	public static float frontLandingMult()    { return f("threatinc_frontLandingMult"); }
	/** Fraction of its entrenchment a front keeps when it takes a stratum or is battered by a counter-attack. */
	public static float frontEntrenchKeptFraction() { return f("threatinc_frontEntrenchKeptFraction"); }
	/** Marines below which a front collapses outright. */
	public static float frontMinMarines()     { return f("threatinc_frontMinMarines"); }
	/** Band a front must fall below a threshold before it gives the state back up - stops flapping now the defence figure bleeds. */
	public static float frontStateHysteresis() { return f("threatinc_frontStateHysteresis"); }

	// ---- marines: garrison, casualties and veterancy (docs/ground-war.md) ----

	/** Weight the colony's stockpiled marines enter a COUNTER-ATTACK at; they defend at full weight either way. */
	public static float marineCounterAttackMult() { return f("threatinc_marineCounterAttackMult"); }
	/** Most the force ratio may speed up (or slow) a colony's counter-attack cadence; 1 disables the tempo term. */
	public static float counterAttackRatioClamp() { return f("threatinc_counterAttackRatioClamp"); }
	/** Days a colony takes to call up, arm and post its whole marine stockpile; marines shipped in mid-siege ramp in over this. */
	public static float marineArmingDays()    { return f("threatinc_marineArmingDays"); }
	/** Fraction of a colony's armed marines lost each time it counter-attacks, win or lose. */
	public static float defenderCounterAttackLossFraction() { return f("threatinc_defenderCounterAttackLossFraction"); }
	/** Fraction of a colony's armed marines lost per 30 days while a front presses it at full strength. */
	public static float defenderLossPer30Days() { return f("threatinc_defenderLossPer30Days"); }
	/** Strength bonus of a fully elite pool (vanilla's own figure is 1.0, ie +100%). */
	public static float marineVeterancyEffectMax() { return f("threatinc_marineVeterancyEffectMax"); }
	/** Casualty reduction of a fully elite pool (vanilla's own figure is 0.5, ie half losses). */
	public static float marineVeterancyLossReduction() { return f("threatinc_marineVeterancyLossReduction"); }
	/** Experience multiplier per ground action, on vanilla's raid figure - what a fight teaches the side that was outmatched. */
	public static float marineXpPerBattle()   { return f("threatinc_marineXpPerBattle"); }
	/** Veterancy level an NPC or Threat landing musters at (0 = raw, 1 = elite). */
	public static float npcLandingVeterancy() { return f("threatinc_npcLandingVeterancy"); }
	/** Whether landings inherit the player fleet's marine rank, write it back on withdrawal, and read the ground skills. */
	public static boolean marineFleetXpTransfer() { return b("threatinc_marineFleetXpTransfer", true); }
	/** Whether a tactical pass with a front deployed costs front marines (and cracks the deep organs in exchange). */
	public static boolean frontDangerCloseEnabled() { return b("threatinc_frontDangerCloseEnabled", true); }
	/** Fraction of the front's marines lost to a danger-close tactical pass. */
	public static float frontDangerCloseLossFraction() { return f("threatinc_frontDangerCloseLossFraction"); }
	/** Days saturation fallout blocks landing ground forces (keep >= hiveSatDisruptDays or sat bombing becomes the best siege opener). */
	public static float falloutDays()         { return f("threatinc_falloutDays"); }
	/** Fraction of an enemy front's troops the swarm kills per 30 days while it holds the orbit over its own hive unopposed. */
	public static float swarmFrontBombardPer30Days() { return f("threatinc_swarmFrontBombardPer30Days"); }
	/** Minimum total Threat fleet points in orbit for the swarm to hold it (a bombardment is a fleet operation, not a lone frigate). */
	public static float swarmOrbitMinFleetFP() { return f("threatinc_swarmOrbitMinFleetFP"); }

	// ---- stratum campaign: pushes, counter-attacks, eradication ----

	/** Base days per stratum push at even strength (scaled by defense/strength, clamped 0.5x-3x). */
	public static float frontPushBaseDays()   { return f("threatinc_frontPushBaseDays"); }
	/** Fraction of the front's marines lost per 30 days while pushing (replaces the entrenched rate). */
	public static float frontPushLossPer30Days() { return f("threatinc_frontPushLossPer30Days"); }
	/** Armaments-upkeep multiplier while pushing. */
	public static float frontPushUpkeepMult() { return f("threatinc_frontPushUpkeepMult"); }
	/** Effectiveness multiplier of a dry (no armaments) front. */
	public static float frontDryEffectivenessMult() { return f("threatinc_frontDryEffectivenessMult"); }
	/** Whether the SWARM's own ground fronts burn heavy armaments and take the dry penalties. False (2026-09-08): nothing can resupply them, so they do not fight on armaments at all. */
	public static boolean threatFrontNeedsArms() { return b("threatinc_threatFrontNeedsArms", false); }
	/** Casualty multiplier on a Threat front while it is PUSHING - what it pays instead of starving, now that it can fabricate replacements. */
	public static float threatPushLossMult()  { return f("threatinc_threatPushLossMult"); }
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

	/** Whether the player can commission purge expeditions from military colonies (paid by the base's reserve and capacity, no credits). */

	// ---- Remnant immune system ----

	public static boolean remnantResists()   { return b("threatinc_remnantResists", true); }
	public static float machineWarWinChance(){ return f("threatinc_machineWarWinChance"); }

	// ---- strategy layer: war mode, reserves, convoys (docs/strategy-layer.md) ----

	/** Master switch for war mode, per-colony reserves, convoys and fleet orders. */
	public static boolean strategyEnabled()  { return b("threatinc_strategyEnabled", true); }
	/** Days after its last strike a faction stands down (if no hive is in reach); 0 = never. */
	public static float warModeStandDownDays() { return f("threatinc_warModeStandDownDays"); }
	/**
	 * Faction ids that never mobilise (comma-separated; "pirates" by default):
	 * the Threat still strikes their worlds and they stay hostile to it, but
	 * they raise no task forces, sieges, convoys or fronts and keep no war
	 * reserve - vanilla raiders, not a navy (decided 2026-09-05).
	 */
	public static java.util.List<String> warExcludedFactions() {
		java.util.List<String> ids = new java.util.ArrayList<String>();
		for (String part : s("threatinc_warExcludedFactions", "pirates").split(",")) {
			String id = part.trim();
			if (id.length() > 0) ids.add(id);
		}
		return ids;
	}
	/** Reserve banked per 30 days per unit of vanilla SURPLUS (availability above demand): surplus units x the commodity's econ unit x this (docs/economy-coherence.md rule 1). */
	public static float reserveSurplusMult() { return f("threatinc_reserveSurplusMult"); }
	/** Vanilla demand units the War footing condition adds at colony size 5 (scaled by size / 5, rounded up); 0 = none (rule 2). */
	public static float warFootingDemandUnits() { return f("threatinc_warFootingDemandUnits"); }
	/** Most of the stock at hand the depot spends per issue covering the colony's own shortage (rule 3). */
	public static float reserveShortageCoverFraction() { return f("threatinc_reserveShortageCoverFraction"); }
	/** Days one issue from the depot holds the colony's availability up; 0 = no covering (rule 3). */
	public static float reserveShortageCoverDays() { return f("threatinc_reserveShortageCoverDays"); }
	/** Months of its own production a colony stockpiles at most. */
	public static float reserveCapMonths()    { return f("threatinc_reserveCapMonths"); }
	/** Months of production each colony holds the moment its faction mobilises. */
	public static float reserveInitialMonths() { return f("threatinc_reserveInitialMonths"); }
	/** Fraction of an NPC colony's cap that expeditions and sorties never draw it below (the home garrison's stock). */
	public static float reserveFloorFraction() { return f("threatinc_reserveFloorFraction"); }
	/** The same floor for the player's own colonies; 0 (default) lets the player's orders commit everything. */
	public static float playerReserveFloorFraction() { return f("threatinc_playerReserveFloorFraction"); }
	/** Militia marines every colony accrues per size per 30 days, regardless of industry. */
	public static float reserveBaselinePerSize() { return f("threatinc_reserveBaselinePerSize"); }
	/** A base needs a functional Waystation (a hive world its Swarm Nexus) to stage, sail, send or receive. */
	public static boolean baseRequiresWaystation() { return b("threatinc_baseRequiresWaystation", true); }
	/** An NPC faction's mobilisation builds a Waystation at every military world with a spaceport. */
	public static boolean mobilisationBuildsWaystation() { return b("threatinc_mobilisationBuildsWaystation", true); }
	/** Exponent on every ground-war strength ratio: 1 = linear (default), 2 = Lanchester square law. */
	public static float groundStrengthExponent() { return f("threatinc_groundStrengthExponent"); }
	/** Fraction of the marines an expedition wants that its base must hold, or it waits. */
	public static float expeditionMinMarinesFraction() { return f("threatinc_expeditionMinMarinesFraction"); }
	/** Marines the player's "Extra" siege tier commits, as a multiple of the computed landing need (the "Siege" tier). */
	public static float siegeExtraMarinesFactor() { return f("threatinc_siegeExtraMarinesFactor"); }
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
	/** Load the board's "Med" tier asks for, as a multiple of what the front or colony is short of. */
	public static float convoyExtraLoadFactor() { return f("threatinc_convoyExtraLoadFactor"); }
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
	/** Combat fleet points of an NPC guard or intercept task force, and the free points a player colony needs to be picked first as a source; a player task force sails with everything its colony has free. */
	public static float guardFleetFP()        { return f("threatinc_guardFleetFP"); }
	/** Days a guard task force holds a colony's orbit. */
	public static float guardDays()           { return f("threatinc_guardDays"); }
	/** Days a task force guarding one of the player's own colonies stays; 0 = until recalled, staged there with its points the colony's to send out. */
	public static float guardOwnDays()        { return f("threatinc_guardOwnDays"); }
	/** Days an intercept task force holds a hive system's jump-point. */
	public static float interceptDays()       { return f("threatinc_interceptDays"); }
	/** Whether Support sorties (holding a besieged world's orbit and suppressing it) are offered and flown. Key kept from the order's old name. */
	public static boolean supportEnabled()    { return b("threatinc_escortEnabled", true); }
	/** Days a Support task force holds a besieged world's orbit. */
	public static float supportDays()         { return f("threatinc_escortDays"); }
	/** Whether Defend sorties (holding a world's orbit, bombarding only while the faction's front there cannot hold) are offered and flown. */
	public static boolean defendEnabled()     { return b("threatinc_defendEnabled", true); }
	/** Days a Defend task force holds a world's orbit. */
	public static float defendDays()          { return f("threatinc_defendDays"); }
	/** Whether an expedition fleet that has landed or reinforced a front stays over it on Defend (and one with nothing left to land joins it). */
	public static boolean landingDefendEnabled() { return b("threatinc_landingDefendEnabled", true); }
	/** A landing's Defend stands down once the batteries have ground the fleet below this fraction of its strength. */
	public static float defendMinStrength()   { return f("threatinc_defendMinStrength"); }
	/** Whether a Defend fleet with nothing left to bombard breaks up its own hulls into troops for its front (docs/ground-war.md "Fabricating troops from the fleet"). */
	public static boolean fabricateEnabled()  { return b("threatinc_fabricateEnabled", true); }
	/** Troops landed per fleet point of hulls broken up. */
	public static float fabricateTroopsPerFP(){ return f("threatinc_fabricateTroopsPerFP"); }
	/** How far past the hold line fabrication aims, so the front does not oscillate on the boundary. */
	public static float fabricateHoldMargin() { return f("threatinc_fabricateHoldMargin"); }
	/** Days of armaments a batch of fabricated troops brings down with it. */
	public static float fabricateSupplyDays() { return f("threatinc_fabricateSupplyDays"); }
	public static boolean orbitFleetsHunt()   { return b("threatinc_orbitFleetsHunt", false); }
	/** Defenders at a world hold its orbit against a faction while their points are at least this fraction of that faction's warships there; nothing friendly there, and any defender holds it. */
	public static float orbitContestFraction() { return f("threatinc_orbitContestFraction"); }
	/** Fraction of the fuel and supplies drawn at launch refunded when a fleet returns home at full strength. */
	public static float returnRefundMult()    { return f("threatinc_returnRefundMult"); }

	// ---- player aid (docs/player-aid.md) ----

	/** Whether the player can send aid from their colonies and the capacity ledger applies. */
	public static boolean aidEnabled()        { return b("threatinc_aidEnabled", true); }
	/** Fleet points a player colony can have at sea at 100% fleet size. */
	public static float aidBaseFP()           { return f("threatinc_aidBaseFP"); }
	/** Days before a lost fleet's points return to its colony. */
	public static float aidRebuildDays()      { return f("threatinc_aidRebuildDays"); }
	/** Smallest task force a player colony will send. */
	public static float aidGuardMinFP()       { return f("threatinc_aidGuardMinFP"); }
	/** Relationship (-1..1) below which a faction refuses the player's aid; hostility always refuses. */
	public static float aidMinRelation()      { return f("threatinc_aidMinRelation"); }
	/** Credits of delivered goods, at the receiving market's price, per point of standing. */
	public static float aidRepPerCredits()    { return f("threatinc_aidRepPerCredits"); }
	/** Most standing points one delivery earns. */
	public static float aidRepMaxPerDelivery(){ return f("threatinc_aidRepMaxPerDelivery"); }
	/** Standing points for a guard arriving on station. */
	public static float aidRepGuardArrived()  { return f("threatinc_aidRepGuardArrived"); }
	/** Standing points for a guard serving its full term. */
	public static float aidRepGuardCompleted(){ return f("threatinc_aidRepGuardCompleted"); }
	/** Standing points split among every faction in a hive's strike reach for a task force at its door. */
	public static float aidRepFrontTotal()    { return f("threatinc_aidRepFrontTotal"); }
	/** Whether mobilised factions post requests for help on the mission board. */
	public static boolean aidRequestsEnabled(){ return b("threatinc_aidRequestsEnabled", true); }
	/** Requests posted at once, unaccepted. */
	public static int aidRequestMaxPosted()   { return i("threatinc_aidRequestMaxPosted"); }
	/** Days a defence contract runs. */
	public static float missionDefendDays()   { return f("threatinc_missionDefendDays"); }
	/** Credits per colony size a defence contract pays. */
	public static float missionDefendCredits(){ return f("threatinc_missionDefendCredits"); }
	/** A defence is requested when the strike's strength exceeds the defenders' times this. */
	public static float defendRequestRatio()  { return f("threatinc_defendRequestRatio"); }
	/** Days a vanilla deficit must stand before a colony asks for the commodity. */
	public static float missionAidShortageDays() { return f("threatinc_missionAidShortageDays"); }
	/** An aid contract pays the goods' value at the receiving market times this. */
	public static float missionAidPayMult()   { return f("threatinc_missionAidPayMult"); }
	/** Delivery standing is multiplied by this when it answers a contract. */
	public static float missionRepMult()      { return f("threatinc_missionRepMult"); }
	/** Whether allied mobilised factions guard and resupply each other's colonies. */
	public static boolean allyAidEnabled()    { return b("threatinc_allyAidEnabled", true); }
	/** Chance per tick per need that a fully willing ally sends aid; scaled down by standing. */
	public static float allyAidChance()       { return f("threatinc_allyAidChance"); }

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

	// ---- coalition (docs/design-theory.md 8.7) ----

	/** Whether a mobilised faction's siege calls other mobilised factions to intercept at the door. */
	public static boolean coalitionEnabled()  { return b("threatinc_coalitionEnabled", true); }
	/** Days a coalition call stays open. */
	public static float coalitionCallDays()   { return f("threatinc_coalitionCallDays"); }
	/** Chance per tick that an eligible ally answers a call with an Intercept task force. */
	public static float coalitionSupportChance() { return f("threatinc_coalitionSupportChance"); }

	// ---- outposts on purged worlds (docs/design-theory.md 8.8) ----

	/** Whether outposts can be built on purged worlds. */
	public static boolean outpostsEnabled()   { return b("threatinc_outpostsEnabled", true); }
	/** Whether a ground victory raises a free outpost over the dead world for the winner. */
	public static boolean outpostOnVictory()  { return b("threatinc_outpostOnVictory", true); }
	/** Station tier: 1 orbital station, 2 battlestation, 3 star fortress. */
	public static int outpostTier()           { return i("threatinc_outpostTier"); }
	/** Credits the player pays for an outpost. */
	public static float outpostCredits()      { return f("threatinc_outpostCredits"); }
	/** Supplies an NPC faction's base pays for an outpost. */
	public static float outpostSupplies()     { return f("threatinc_outpostSupplies"); }
	/** Fuel an NPC faction's base pays for an outpost. */
	public static float outpostFuel()         { return f("threatinc_outpostFuel"); }
	/** Chance per tick a mobilised NPC faction fortifies an open purged world in reach. */
	public static float outpostChance()       { return f("threatinc_outpostChance"); }

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
	// instant war (ThreatDebugWar): a connected network of mature hive systems
	// founded at once and every faction mobilised, to test the war on a fresh save
	public static boolean debugInstantWar()        { return b("threatinc_debugInstantWar", false); }
	public static int debugInstantWarSystemsMin()  { return i("threatinc_debugInstantWarSystemsMin"); }
	public static int debugInstantWarSystemsMax()  { return i("threatinc_debugInstantWarSystemsMax"); }
	public static int debugInstantWarCoreMin()     { return i("threatinc_debugInstantWarCoreMin"); }
	public static int debugInstantWarCoreMax()     { return i("threatinc_debugInstantWarCoreMax"); }
	public static float debugInstantWarLinkLY()    { return f("threatinc_debugInstantWarLinkLY"); }
	public static float debugInstantWarCoreLY()    { return f("threatinc_debugInstantWarCoreLY"); }
	public static int debugInstantWarHomeSize()    { return i("threatinc_debugInstantWarHomeSize"); }
	public static int debugInstantWarColonySize()  { return i("threatinc_debugInstantWarColonySize"); }

	public static void log(String msg) {
		if (debugLogging()) {
			Global.getLogger(ThreatIncConfig.class).info("[ThreatInc] " + msg);
		}
	}
}
