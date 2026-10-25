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

	private static java.util.Set<String> lunaKeys = null;

	/**
	 * Whether LunaLib holds this knob: its row is in our LunaSettings.csv. A
	 * settings.json-only knob (most of the war council's) is never asked of
	 * LunaLib, which logs an error per read of a key it lacks (34k in ng2a).
	 */
	private static boolean luna(String key) {
		if (!lunaAvailable()) return false;
		if (lunaKeys == null) {
			java.util.Set<String> keys = new java.util.HashSet<String>();
			try {
				org.json.JSONArray rows = Global.getSettings().loadCSV("data/config/LunaSettings.csv", MOD_ID);
				for (int r = 0; r < rows.length(); r++) keys.add(rows.getJSONObject(r).optString("fieldID"));
			} catch (Throwable t) {
				return true; // unreadable: ask LunaLib as before
			}
			lunaKeys = keys;
		}
		return lunaKeys.contains(key);
	}

	private static int i(String key) {
		if (luna(key)) {
			Integer v = LunaConfigBridge.getInt(key);
			if (v != null) return v;
		}
		return (int) Global.getSettings().getFloat(key);
	}

	private static float f(String key) {
		if (luna(key)) {
			Float v = LunaConfigBridge.getFloat(key);
			if (v != null) return v;
		}
		return Global.getSettings().getFloat(key);
	}

	private static String s(String key, String def) {
		if (luna(key)) {
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
		if (luna(key)) {
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
	/** Worlds the opening chain lands on, and the planets the home system must have (pickOGSystem, pickChainPlanets); at least the three a chain needs. */
	public static int homeWorlds()           { return Math.max(3, i("threatinc_homeWorlds")); }
	public static float seedToColonyDays()   { return f("threatinc_seedToColonyDays"); }
	public static int spreadMinSize()        { return i("threatinc_spreadMinSize"); }

	// ---- colonies ----

	public static float colonyGrowthBaseDays(){ return f("threatinc_colonyGrowthBaseDays"); }
	public static int colonizationEscort()   { return i("threatinc_colonizationEscort"); }
	/** Fleet points a hive's FP bank gains per ship unit its forges produce, per 30 days - the Threat's whole fleet income (closed economy, 2026-09-29). */
	public static float fabFPPerShipUnit()   { return f("threatinc_fabFPPerShipUnit"); }
	/** Fraction of a colony's standing fleets' FP its bank pays in upkeep every 30 days (garrison, raiders, inbound reinforcements, its ledger-bound fleets). */
	public static float garrisonUpkeepPerMonth() { return f("threatinc_garrisonUpkeepPerMonth"); }
	/** Military options menu: how far (su) from a hive's world a defending swarm fleet can be engaged from its orbit; any swarm fleet inside also counts as a defender. */
	public static float defendRadius()      { return f("threatinc_defendRadius"); }
	/** Units from a hive's world within which a swarm fleet bound for it counts as a defender inbound (military options); farther out it is not in the fight. 0 = any distance. */
	public static float defendInboundRange() { return f("threatinc_defendInboundRange"); }
	/** Whether colonies redistribute Defense Swarms to reinforce worn-down siblings. */
	public static boolean reinforceEnabled()  { return b("threatinc_reinforceEnabled", true); }
	/** Whether each hive system holds the garrison the war around it calls for and lets the rest go (ThreatPosture); off builds past the floor whenever the bank pays. */
	public static boolean postureEnabled()    { return b("threatinc_postureEnabled", true); }
	/** A system wants this much garrison over what its pressure needs by the siege's orbit margin. */
	public static float postureMargin()       { return f("threatinc_postureMargin"); }
	/** Garrison above want by this fraction (and one swarm) is let go: reinforcement, waves, strikes, recycling. */
	public static float postureBand()         { return f("threatinc_postureBand"); }
	/** Days between readings of every hive system's posture. */
	public static float postureDays()         { return f("threatinc_postureDays"); }
	/** The share of what a base staging for a hive system could pay that counts as pressure on it (0: only forces seen). */
	public static float postureStagedShare()  { return f("threatinc_postureStagedShare"); }
	/** DEFENCE IN DEPTH (the user, 2026-10-07): a quiet hive system's base want is scaled by its exposure (ThreatPosture.exposure) - 1 within postureExposureNearLY of a human world the swarm has seen or while hostiles were seen in it within postureExposureSeenDays, down to one swarm a colony at postureExposureFarLY and beyond, or with no human world known. Off: every quiet system wants its base. */
	public static boolean postureExposure()   { return b("threatinc_postureExposure", true); }
	public static float postureExposureNearLY() { return fd("threatinc_postureExposureNearLY", 10f); }
	public static float postureExposureFarLY()  { return fd("threatinc_postureExposureFarLY", 30f); }
	public static float postureExposureSeenDays() { return fd("threatinc_postureExposureSeenDays", 180f); }
	/** THE LINE (S1, 2026-10-10): an exposed colony needs at least the strongest faction's median siege seen in postureLineDays x break-off x margin. Off since hw150 (the supplies could not keep it and the spread died: b 35 hives against 71 without it). */
	public static boolean postureLine()       { return b("threatinc_postureLine", false); }
	public static float postureLineDays()     { return fd("threatinc_postureLineDays", 365f); }
	/** Whether a system the whole hive could not hold above its bases is written off (its need 0). */
	public static boolean postureTriage()     { return b("threatinc_postureTriage", false); }
	/** Whether a system's need stands at the worlds a force is over, and colonies the war asks nothing of give down to their reserve for one under attack. */
	public static boolean postureNeedAtAttack() { return b("threatinc_postureNeedAtAttack", true); }
	/** Whether a forge of a THREATENED or BESIEGED system sends no wave and counts toward no claim, whatever it holds above the need. */
	public static boolean posturePressedForgesHome() { return b("threatinc_posturePressedForgesHome", false); }
	/** Range (LY) at which a hive system under attack and short of its need calls home a strike not yet at its work and nearer it than its target (ThreatPosture.recallStrikes); 0 = off. */
	/** Whether a detected strike gives the factions it sweeps a scouting lead to its origin system (ThreatScouts.addLead); off, it only mobilises them. */
	public static boolean strikeLeads() { return b("threatinc_strikeLeads", false); }
	/** Whether a scout's sighting is known only once the scout is back in a friendly system, and lost with it (ThreatScoutRoute.carry); off, it is known the moment it is seen. */
	public static boolean carriedIntel() { return b("threatinc_carriedIntel", true); }
	private static float fd(String key, float def) {
		try {
			return f(key);
		} catch (Throwable t) {
			return def;
		}
	}
	/** Whether both sides keep patrols once at war, in place of the humans' 60-day sweeps and both 4 ly sighting rules (ThreatScouts.launchPatrols, ThreatSwarmPatrols). */
	public static boolean patrolsEnabled() { return b("threatinc_patrolsEnabled", true); }
	/** Light-years at which a patrol sees a force in hyperspace. */
	public static float patrolSightLY() { return fd("threatinc_patrolSightLY", 1f); }
	/** Stops on one patrol's route. */
	public static int patrolStops() { return (int) fd("threatinc_patrolStops", 6f); }
	/** Fleet points of patrols a human faction keeps out for each of its military worlds. */
	public static float patrolFPPerBase() { return fd("threatinc_patrolFPPerBase", 40f); }
	/** Days without losing a patrol after which a side forgets one loss (its patrols halve in size and double in number). */
	public static float patrolCalmDays() { return fd("threatinc_patrolCalmDays", 120f); }
	/** Fleet points of one Patrol Swarm before any loss. */
	public static float swarmPatrolFP() { return fd("threatinc_swarmPatrolFP", 30f); }
	/** Fleet points of Patrol Swarms a hive system keeps out. */
	public static float swarmPatrolFPPerSystem() { return fd("threatinc_swarmPatrolFPPerSystem", 120f); }
	/** Days a system is left alone after a Patrol Swarm's visit. */
	public static float swarmPatrolMemoryDays() { return fd("threatinc_swarmPatrolMemoryDays", 45f); }
	/** The burn of a human expedition flying as a route when a patrol meets it: bombers hauling razing fuel, a siege with transports, a raid; and of a Threat strike. */
	public static float patrolBurnBombers() { return fd("threatinc_patrolBurnBombers", 6f); }
	public static float patrolBurnSiege() { return fd("threatinc_patrolBurnSiege", 7f); }
	public static float patrolBurnRaid() { return fd("threatinc_patrolBurnRaid", 8f); }
	public static float patrolBurnStrike() { return fd("threatinc_patrolBurnStrike", 9f); }
	public static float postureRecallLY() { return f("threatinc_postureRecallLY"); }
	/** Whether a recalled strike sends home only the fleets that cover what the system is short and sails on with the rest (ThreatStrikeFGI.recallTo); off, it comes home whole. */
	/** A strike is built for its target from the whole hive's bank at a staging world, the garrisons stay home and nothing is recalled (IncursionManager.stagedSizes, pickStagedTarget; the user, 2026-10-05). Off: the system's spare swarms muster, and the recall. */
	public static boolean strikeStaged() { return b("threatinc_strikeStaged", true); }
	/** A staged strike outweighs the defence last seen at its target by this (IncursionManager.stagedSizes). */
	public static float strikeStagedMargin() { return fd("threatinc_strikeStagedMargin", 1.5f); }
	/** A staged strike takes the spare Defense Swarms of the hive's systems first, the banks building only what is missing (IncursionManager.stagedPlan). Off: built from the banks alone. */
	public static boolean strikeStagedGarrisons() { return b("threatinc_strikeStagedGarrisons", false); }
	/** Light-years from the staging system within which a staged strike gathers spare swarms; 0 = the whole hive. */
	public static float strikeStagedGatherLY() { return fd("threatinc_strikeStagedGatherLY", 0f); }
	/** The distance part of a colony's score in the staged gather order (IncursionManager.stagedSpares); the rest is its fleet count. */
	public static float spareGatherDistanceShare() { return fd("threatinc_spareGatherDistanceShare", 0.55f); }
	/** Share of every hive colony's fabrication set aside in the hive's strike fund, which alone pays staged strikes (ThreatColonyManager.strikeFund); 0 = no fund, the pooled banks pay. */
	public static float strikeFundShare() { return fd("threatinc_strikeFundShare", 0f); }
	/** The least fleet points the war's OPENING strike (no faction mobilised yet) sails with; every later strike is sized to its target (the user, 2026-10-06). */
	public static float strikeStagedMinFP() { return fd("threatinc_strikeStagedMinFP", 0f); }
	/** With a strike fund, the war's first strike (no faction at war yet) waits until the fund holds this many fleet points; 0 = no wait. */
	public static float strikeStagedOpenFP() { return fd("threatinc_strikeStagedOpenFP", 0f); }
	/** The offensive (ThreatOffensive; the user, 2026-10-07): the hive saves its strike fund and strikes every known target system at once, the campaign sized to what the fund pays by its deadline. Off: the closest payable target, one a pass. */
	public static boolean swarmOffensive() { return b("threatinc_swarmOffensive", true); }
	/** Months a campaign may save before the fund pays what it can and a new one starts. */
	public static float offensiveHorizonMonths() { return fd("threatinc_offensiveHorizonMonths", 12f); }
	/** The horizon at full losing pressure (ThreatStance.losingPressure); between, it shrinks from offensiveHorizonMonths by degree. */
	public static float offensiveLosingMonths() { return fd("threatinc_offensiveLosingMonths", 3f); }
	/** LOSING HOLDS THE NAVY (the user, 2026-10-10, D1): from this losing pressure the campaign prices only a month's fund and the rest pays the pressed hives (ThreatOffensive.pass); 0 = never. */
	public static float offensiveHoldLosing() { return fd("threatinc_offensiveHoldLosing", 0.3f); }
	/** At full losing pressure only targets within this many light-years of a hive system that can stage a strike are campaigned against; between, the reach shrinks from every known target by degree; 0 = no limit. */
	public static float offensiveNearLY() { return fd("threatinc_offensiveNearLY", 10f); }
	/** Forward bases within this of their nearest staging hive are the campaign's approaches: priced first, whatever the focus (the user, 2026-10-11); 0 = none. */
	public static float offensiveApproachLY() { return fd("threatinc_offensiveApproachLY", 10f); }
	/** A strike at a forward base under size 4 is sized for the base one size up - the station it grows into (the user, 2026-10-11). */
	public static boolean strikeBaseGrowth() { return b("threatinc_strikeBaseGrowth", true); }
	/** A forward base's kit sails to the site in a real convoy and the base is founded when it lands (ThreatConvoys.dispatchFounding, 2026-10-11); off, it is paid and stands up on the spot. */
	public static boolean foundingConvoy() { return b("threatinc_foundingConvoy", true); }
	/** DEFEAT IN DETAIL (the user, 2026-10-07): a campaign masses on one faction - every known system of the focus - until its worlds answer the strikes with under offensiveBrokenAnswer of what they were sized for, or nothing known of it is left to strike; then the next (ThreatOffensive.chooseFocus). Off: every known target at once. */
	public static boolean offensiveFocus() { return b("threatinc_offensiveFocus", true); }
	/** The focus is broken when the faction's worlds met the swarm's last strikes with under this share of what they were sized for (ThreatSwarmIntel.answerShare). */
	public static float offensiveBrokenAnswer() { return fd("threatinc_offensiveBrokenAnswer", 0.5f); }
	/** Days over which the losing pressure reads the swarm's hives and exchange (ThreatStance.losingPressure; the user, 2026-10-07: losing is a trend, not a one-off). */
	public static float losingWindowDays() { return fd("threatinc_losingWindowDays", 365f); }
	/** The share of the window's peak hive count whose fall is full losing pressure; one fallen hive is none (the cost of war). */
	public static float losingHiveShare() { return fd("threatinc_losingHiveShare", 0.5f); }
	/** The exchange lost beyond what was sunk over the window, as a share of what the forges made over it, that is full losing pressure. */
	public static float losingExchangeShare() { return fd("threatinc_losingExchangeShare", 1f); }
	public static boolean postureRecallPartial() { return b("threatinc_postureRecallPartial", true); }
	/** Whether a system under attack masses its spare swarms, then its neighbours', at its worlds short of their need, and a launch leaves the need home (ThreatPosture.massWithin, strikeCapFP). */
	public static boolean postureMass() { return b("threatinc_postureMass", false); }
	/** Whether a hive system defends as one: a force over one of its worlds draws the spare swarms of the others, and a siege seen coming fights them from its first day (ThreatPosture.rally, defenders). */
	public static boolean systemDefence() { return b("threatinc_systemDefence", true); }
	/** How far the swarms standing for a world must outweigh the force over it before its system sends no more; 0 = every spare swarm. */
	public static float systemDefenceMargin() { return f("threatinc_systemDefenceMargin"); }
	/** Whether a system rallies to a world only when the swarms there, those bound for it and its spare together outweigh the force over it; short of that none is sent (ThreatPosture.rally). */
	public static boolean systemDefenceOnlyIfEnough() { return b("threatinc_systemDefenceOnlyIfEnough", true); }
	/** Whether a world its own system cannot defend draws the spare swarms of the hives in fuel reach, nearest first, sent only if all of it together outweighs the force (ThreatPosture.regionalPool). */
	public static boolean regionalRelief() { return b("threatinc_regionalRelief", true); }
	/** Losing pressure (ThreatStance.losingPressure) at which the swarm consolidates its garrisons on keystone hives and concedes the rest (ThreatPosture.consolidate); 0 = never. */
	public static float consolidateLosing() { return fd("threatinc_consolidateLosing", 0.5f); }
	/** Days over which the largest force seen over a hive world sizes a keystone's stand (ThreatPosture.consolidate). */
	public static float consolidateWindowDays() { return fd("threatinc_consolidateWindowDays", 120f); }
	/** The percentile (0-1) of the forces seen over hive worlds in the window that sizes a keystone's stand (ThreatPosture.consolidate); 1 = the largest. */
	public static float consolidateStandPercentile() { return fd("threatinc_consolidateStandPercentile", 0.75f); }
	/** Days a reinforcement may be in flight before it is logged where it stands and ordered on again (ThreatColonyManager.overdue); 0 = never. */
	public static float reinforcementOverdueDays() { return f("threatinc_reinforcementOverdueDays"); }
	/** Whether the humans read a seen strike as bound for its system, not for its worlds: guards, help and mobilisation key off the system (ThreatFrontlines.boundFor). */
	public static boolean strikeSeenBySystem() { return b("threatinc_strikeSeenBySystem", true); }
	/** Whether a sector stance (ThreatStance) decides where the surplus goes: pressing weak rivals, expanding, or consolidating. */
	public static boolean stanceEnabled()     { return b("threatinc_stanceEnabled", true); }
	/** The hive presses a rival it holds this many times the force of, in reach of it. */
	public static float stancePressRatio()    { return f("threatinc_stancePressRatio"); }
	/** A known world is weak when its defence is at most this share of what could be mustered against it (by siegeBreakOffRatio). */
	public static float stanceWeakOdds()      { return f("threatinc_stanceWeakOdds"); }
	/** The hive consolidates when this share of its systems is THREATENED or BESIEGED. */
	public static float stanceConsolidateShare() { return f("threatinc_stanceConsolidateShare"); }
	/** ...and at least this many systems are, or every one (0 = the share alone). */
	public static int stanceConsolidateMinPressed() { return i("threatinc_stanceConsolidateMinPressed"); }
	/** Days a stance holds before it may change (consolidating never waits). */
	public static float stanceDwellDays()     { return f("threatinc_stanceDwellDays"); }
	/** Pressing, the share of the posture's appetite expansion still gets. */
	public static float stanceSecondaryShare() { return f("threatinc_stanceSecondaryShare"); }
	/** Consolidating, the share of the posture's appetite expansion still gets (0: no new claims). */
	public static float stanceConsolidateSpreadShare() { return f("threatinc_stanceConsolidateSpreadShare"); }
	/** Whether a consolidating hive strikes any world by its weakness, spoiling blows first (off: spoiling blows only). */
	public static boolean stanceConsolidateStrikes() { return b("threatinc_stanceConsolidateStrikes", true); }
	/** A garrison swarm below this fraction of its fabricated fleet points no longer holds its slot. */
	public static float garrisonUnderStrengthFraction() { return f("threatinc_garrisonUnderStrengthFraction"); }
	public static boolean economyGatesGrowth(){ return b("threatinc_economyGatesGrowth", true); }
	/** Ground (raid) strength one difficulty point of siege fleet lands - measured, about a quarter of crew capacity. */
	public static float siegeRaidStrPerPoint() { return f("threatinc_siegeRaidStrPerPoint"); }
	/** A siege lands this much over the 2:1 odds at which the hive overruns a fresh beachhead; 0 sizes for the raids only. */
	public static float siegeBeachheadMargin() { return f("threatinc_siegeBeachheadMargin"); }
	/** The beachhead line is the REPEL line (2026-10-18, the user: "size to repel"): the landing holds the first counter-attack at 1:1 x the margin, not merely outlives it at the 2:1 overrun odds. */
	public static boolean beachheadRepels() { return b("threatinc_beachheadRepels", true); }
	/** A siege carries its landing's marines times this for those lost with the hulls on the way in, while a Defense Swarm is seen over the world; else only the guns' planned toll (IncursionManager.marineHeadroom). */
	public static float siegeMarineHeadroom() { return f("threatinc_siegeMarineHeadroom"); }
	/** An NPC first landing short of the beachhead is not made, even once orbit is done. */
	public static boolean siegeNoDoomedLanding() { return b("threatinc_siegeNoDoomedLanding", false); }
	/** Whether an NPC siege short of marines (and armaments) at its base draws them from its faction's other markets in reach, each above its floor. */
	public static boolean siegePoolMarines()  { return b("threatinc_siegePoolMarines", true); }
	/** Whether an NPC siege's fuel and supplies gate and draw pool its faction's other markets in reach the same way (2026-09-26). */
	public static boolean siegePoolProvisions() { return b("threatinc_siegePoolProvisions", true); }
	public static boolean siegeFightsForOrbit() { return b("threatinc_siegeFightsForOrbit", true); }
	/** How far from a contested world an expedition fleet may hunt for its orbit. */
	public static float siegeHuntRange()     { return f("threatinc_siegeHuntRange"); }
	/** How far from its nearest target an expedition fleet may stray before it is recalled. */
	public static float siegeLeashRange()    { return f("threatinc_siegeLeashRange"); }
	/** Chance that a forge outside the home system's Pristine one carries a Corrupted Nanoforge (fixed roll per world). */
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
	/** The troop pool a strike carries, per difficulty point of the swarms it musters - fixed at launch. */
	public static float strikeTroopsPerPoint() { return f("threatinc_strikeTroopsPerPoint"); }
	/** A world's share of the pool is never below this: a small strike lands fewer worlds, not token forces. */
	public static int strikeFrontMinTroops()  { return i("threatinc_strikeFrontMinTroops"); }
	/** Days of armaments a Threat landing carries (it is never resupplied from a base). */
	public static float strikeFrontSupplyDays() { return f("threatinc_strikeFrontSupplyDays"); }
	/** How much of a colony's banked reserve marines defend the ground against a front. */
	public static float reserveDefenseMult()  { return f("threatinc_reserveDefenseMult"); }
	/** Troops of ground strength that count as one fleet point in a faction's Strength score (ThreatFactionView.strengthOf; the user, 2026-10-24). */
	public static float scoreTroopsPerPoint() { return f("threatinc_scoreTroopsPerPoint"); }
	/** Light-years a siege is priced over when a faction's Strength score reads what its fuel pays (ThreatFactionView.strengthOf). */
	public static float scoreSiegeRangeLY()   { return f("threatinc_scoreSiegeRangeLY"); }

	// ---- sieges from orbit (docs/ground-war.md "Sieges from orbit") ----

	/** Disruption days at which a colony's defence structure contributes nothing (its bonus scales down linearly to it). */
	public static float fortificationDisruptDays() { return f("threatinc_fortificationDisruptDays"); }
	/** How much of a defence structure's bonus a full input deficit takes - 1 is vanilla's rule (a starved battery gives nothing), 0 ignores the deficit; a partial deficit takes its share of this. Both theatres (ThreatSiegeMalus.weighDeficit). */
	public static float defenseDeficitWeight() { return f("threatinc_defenseDeficitWeight"); }
	/** Marines lost raiding a hive's Fabrication Core grow as (tokens on it) ^ this: 0 is vanilla (depth free), 1 costs the same as shallow raids, above 1 one deep raid costs more. */
	public static float coreRaidDepthLoss() { return f("threatinc_coreRaidDepthLoss"); }
	/** A Fabrication Core's weight in the raid depth toll, Ground Defenses intact = 1 (the Nexus is weighted by its defence bonus). */
	public static float coreRaidDepthWeight() { return f("threatinc_coreRaidDepthWeight"); }
	/** Disruption days per day an unopposed siege fleet adds to a colony's fortifications at overwhelming strength, scaled by fleet / (fleet + defence). */
	public static float siegeSuppressDaysPerDay() { return f("threatinc_siegeSuppressDaysPerDay"); }
	/** Days before the player's fleet can organize another bombardment; tactical and saturation share the lock. */
	public static float bombardCooldownDays() { return f("threatinc_bombardCooldownDays"); }
	/** Fuel a day of tactical bombardment burns per fleet point (an AI fleet's from its provisions). */
	public static float bombardFuelPerFPDay() { return f("threatinc_bombardFuelPerFPDay"); }
	/** Fleet points a bombarding fleet loses per day per point of defence the batteries add. */
	public static float bombardReturnFirePerGunDefence() { return f("threatinc_bombardReturnFirePerGunDefence"); }
	/** Defence points a day of bombardment must take off per fleet point the guns take: a hull's price in marines. */
	public static float bombardFPWorth() { return f("threatinc_bombardFPWorth"); }
	/** Marines a landing loses on the drop per point of defence a colony's batteries still add (2026-10-18, the user: the guns tax the drop as they tax the bombardment). */
	public static float dropLossPerGunDefence() { return f("threatinc_dropLossPerGunDefence"); }
	/** The most of a landing the drop can take, as a fraction of it. */
	public static float dropLossMaxFraction() { return f("threatinc_dropLossMaxFraction"); }
	/** Unrest a bombardment raises a world to: this x (1 - fortification condition), tactical and saturation alike. */
	public static float bombardUnrestMax() { return f("threatinc_bombardUnrestMax"); }
	/** Fuel a day of saturation pours onto a world per fleet point. */
	public static float satFuelPerFPDay() { return f("threatinc_satFuelPerFPDay"); }
	/** Fuel to raze a size-4 colony to nothing; each size up x sqrt(10). */
	public static float satFuelSize4() { return f("threatinc_satFuelSize4"); }
	/** Whether NPC factions raze a hive from orbit where that is cheaper than landing (IncursionManager.razeWorlds). */
	public static boolean npcRazeEnabled() {
		return b("threatinc_npcRazeEnabled", true);
	}

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
	/** Days a strike expedition holds orbit over a system before giving its siege up. */
	public static float siegeOrbitDays()      { return f("threatinc_siegeOrbitDays"); }
	/** An NPC siege expedition, and a swarm strike under the ground doctrine, off-screen runs its siege a day at a time (ThreatPurgeFGI and ThreatStrikeFGI advanceDaily), not in one frame on arrival. */
	public static boolean abstractSiegeDaily() { return b("threatinc_abstractSiegeDaily", true); }
	/** Strike-target weight multiplier for a world whose Threat front is dry and signalling for the next expedition. */
	public static float strikeReinforceWeight() { return f("threatinc_strikeReinforceWeight"); }
	/** Days a dry Threat front holds for the next expedition before its final push. */
	public static float frontDryFinalPushDays() { return f("threatinc_frontDryFinalPushDays"); }
	/** Accessibility a colony loses while Threat warships hold its orbit; half while the fight is even. */
	public static float blockadeAccessPenalty() { return f("threatinc_blockadeAccessPenalty"); }
	/** Disruption days a seized civilian industry is pinned at while the invader holds its district. */
	public static float districtSeizeDays()   { return f("threatinc_districtSeizeDays"); }
	/** Whether a Threat ground victory seeds a hive on the spot (off: the world decivilises). */
	public static boolean conquestConverts()  { return b("threatinc_conquestConverts", true); }
	/** Size of the hive seeded on a conquered world. */
	public static int conquestHiveSize()      { return i("threatinc_conquestHiveSize"); }
	public static int colonyMaxSize()         { return i("threatinc_colonyMaxSize"); }

	// ---- hive sieges ----

	// ---- THE STRATA ARE THE THREAT (2026-10-25, docs/strata-are-the-threat.md) ----

	/** Strata a size-1 hive begins at, and the stratum a ground front holds (ThreatStrata.sizeAt / perSize). */
	public static float strataSizeBase()      { return f("threatinc_strataSizeBase"); }
	/** The size table's ratio: a size is this many times the one below (2: 400, 800, 1,600 ... 51,200 at 8). */
	public static float strataSizeRatio()     { return f("threatinc_strataSizeRatio"); }
	/** Strata a fleet point: what a ship buds from and breaks up into, one exchange both ways (was fabricateTroopsPerFP). */
	public static float strataPerFP()         { return f("threatinc_strataPerFP"); }
	/** Strata a month a size the Fabrication Core grows a world fed in full (ThreatColonyManager.coreState). */
	public static float coreStrataPerSizeMonth() { return f("threatinc_coreStrataPerSizeMonth"); }
	/** Scale on the Core's demand: METALS at the size x this, FUEL at (size - 2) x this. */
	public static float coreInputPerSize()    { return f("threatinc_coreInputPerSize"); }
	/** The fastest a starving world loses a size, in days (starveDaysPerSize / (units short - 1), never under this). */
	public static float starveMinDays()       { return f("threatinc_starveMinDays"); }
	/** Strata density: the light-years at which another hive world's strata count half (1 / (1 + (d / this)^2)). */
	public static float densityLY()           { return f("threatinc_densityLY"); }
	/** Strata density: the density that is 0% accessibility. */
	public static float densityRef()          { return f("threatinc_densityRef"); }
	/** Strata density: accessibility a doubling of the density over densityRef. */
	public static float densityAccessPerDoubling() { return f("threatinc_densityAccessPerDoubling"); }
	public static float densityAccessMin()    { return f("threatinc_densityAccessMin"); }
	public static float densityAccessMax()    { return f("threatinc_densityAccessMax"); }
	/** Strata density: the weight of the world's own strata (0: only the worlds around it count). */
	public static float densitySelfWeight()   { return f("threatinc_densitySelfWeight"); }
	/** Strata a world keeps from the hive's strikes and shifts (800: a seed's worth - never below size 2 by choice). */
	public static float shiftFloorStrata()    { return f("threatinc_shiftFloorStrata"); }
	/** Strata a structure the hive planner builds costs the building world (750: 150 FP at strataPerFP 5). */
	public static float structureStrata()     { return f("threatinc_structureStrata"); }
	/** Strata a seed is founded with, paid by its source beyond the Seeding Swarm's hulls (400: size 1; the carrier absorbed on landing makes 800, size 2). */
	public static float seedStrata()          { return f("threatinc_seedStrata"); }
	/** Strata a hive world moves a day, budding or absorbing, at least (400: 80 FP, the flow of a size-6 world or smaller). */
	public static float strataFlowPerDay()    { return f("threatinc_strataFlowPerDay"); }
	/** The flow as a fraction of the world's strata a day (0.02: a size-7 world moves 512, a fortress 1,024). */
	public static float strataFlowFractionPerDay() { return f("threatinc_strataFlowFractionPerDay"); }
	/** Days a Defend fleet lingers over its world after the last hostile left the orbit before it is absorbed. */
	public static float defendLingerDays()    { return f("threatinc_defendLingerDays"); }
	/** Strata a day of bombardment by one fleet point razes off a hive (1: 1,000 FP raze a size-5 hive's 6,400 in 7 days). */
	public static float bombardStrataPerFPDay() { return f("threatinc_bombardStrataPerFPDay"); }
	/** Fuel a stratum razed burns (2: a size-5 hive is 12,800 fuel, a fortress 102k). */
	public static float bombardFuelPerStratum() { return f("threatinc_bombardFuelPerStratum"); }
	/** The Attack's Med share of the staging colony's fuel above the passage and of its marines (0.5). */
	public static float attackMedShare()      { return f("threatinc_attackMedShare"); }
	/** Disruption days on a structure's clock at which its bonus has worn to nothing (0 = no wear); the hive's fortification clock. */
	public static float defenseWearDays()     { return f("threatinc_defenseWearDays"); }
	/** Marine-loss multiplier when raiding hive worlds. */
	public static float hiveMarineLossMult()  { return f("threatinc_hiveMarineLossMult"); }

	// ---- ground fronts (docs/ground-war.md) ----

	/** Master switch for the ground-front siege mechanic. */
	public static boolean frontsEnabled()     { return b("threatinc_frontsEnabled", true); }
	/** Troops that land push: a front at holding strength assaults as soon as it is on the ground. */
	public static boolean frontAutoPush()     { return b("threatinc_frontAutoPush", true); }
	/** Disruption days a front adds per day to what it holds, times troops / (troops + defence); the clock runs down 1/day. */
	public static float frontWearRate()       { return f("threatinc_frontWearRate"); }
	/** THE ATTRITION RULE's one knob (ThreatGroundFronts.attritionPerDay): a side loses its troops / this x (enemy force / own force) x 1 / own cover x veteran a day. */
	public static float attritionTroopsPer() { return f("threatinc_attritionTroopsPer"); }
	/** Heavy armaments consumed per marine per 30 days - the front's upkeep. */
	public static float frontArmamentsPerMarinePer30Days() { return f("threatinc_frontArmamentsPerMarinePer30Days"); }
	/** Days of entrenchment to reach the full effectiveness multiplier. */
	public static float frontEntrenchDays()   { return f("threatinc_frontEntrenchDays"); }
	/** Effectiveness multiplier of a fully entrenched front. */
	public static float frontEntrenchMaxMult() { return f("threatinc_frontEntrenchMaxMult"); }
	/** Effectiveness multiplier of a fresh landing, before any entrenchment. */
	public static float frontLandingMult()    { return f("threatinc_frontLandingMult"); }
	/** Troops under which a landing is not attempted, a raid not crewed, a drop not worth making. */
	public static float frontMinMarines()     { return f("threatinc_frontMinMarines"); }
	/** Troops below which a standing front is ground down and removed; 0 = it fights to the last troop (the user, 2026-10-24). */
	public static float frontCollapseTroops() { return f("threatinc_frontCollapseTroops"); }
	/** THE LINE (2026-10-23, the user): days of net rate 1 that move a district across the line either way (ThreatGroundFronts.lineRate). */
	public static float frontLineBaseDays()   { return f("threatinc_frontLineBaseDays"); }
	/** The line's rate inside which the front reads grinding (both pushing) or stalled, and the landing holds the defence structures rather than wearing everything. */
	public static float frontGrindBand()      { return f("threatinc_frontGrindBand"); }
	/** The line's rate past which a side reads overrunning (the other overrun). */
	public static float frontOverrunRate()    { return f("threatinc_frontOverrunRate"); }
	/** The rate toward the defenders a colony's marines ask is sized to (ThreatGroundFronts.reliefNeed): push the army back. */
	public static float frontPushBackRate()   { return f("threatinc_frontPushBackRate"); }

	// ---- marines: garrison, casualties and veterancy (docs/ground-war.md) ----

	/** Weight the colony's stockpiled marines enter a COUNTER-ATTACK at; they defend at full weight either way. */
	public static float marineCounterAttackMult() { return f("threatinc_marineCounterAttackMult"); }
	/** Days a colony takes to call up, arm and post its whole marine stockpile; marines shipped in mid-siege ramp in over this. */
	public static float marineArmingDays()    { return f("threatinc_marineArmingDays"); }
	/** Whether a colony's armed marines burn heavy armaments from its stockpile while an army stands on it (frontArmamentsPerMarinePer30Days per engaged marine), fighting at frontDryEffectivenessMult with none in stock (user, 2026-10-17). */
	public static boolean defenderArmsEnabled() { return b("threatinc_defenderArmsEnabled", true); }
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
	/** Fraction of the front's marines lost to each day of danger-close tactical bombardment. */
	public static float frontDangerCloseLossFraction() { return f("threatinc_frontDangerCloseLossFraction"); }
	/** Fraction of an enemy front's troops the swarm kills per 30 days while it holds the orbit over its own hive unopposed. */
	public static float swarmFrontBombardPer30Days() { return f("threatinc_swarmFrontBombardPer30Days"); }
	/** Marines an enemy front on a hive loses a day per fleet point the swarm holds the orbit with (ThreatGroundFronts.swarmBombardPer30Days); 0 falls back to swarmFrontBombardPer30Days. */
	public static float swarmBombardMarinesPerFPDay() { return f("threatinc_swarmBombardMarinesPerFPDay"); }
	/** Fuel a day of swarm bombardment draws from the swarm's stock per fleet point over the planet (ThreatGroundFronts.tickSwarmBombard); 0 free. */
	public static float swarmBombardFuelPerFPDay() { return f("threatinc_swarmBombardFuelPerFPDay"); }
	/** Days of ordnance a swarm fleet carries (ThreatGroundFronts.bombardHoldFull), refilled only at a hive whose port arms it (portArms); 0 no hold, the stock pays every day. */
	public static float swarmBombardHoldDays() { return f("threatinc_swarmBombardHoldDays"); }
	/** Months of the hive's fuel income the stock keeps before it arms a bombard hold (ThreatGroundFronts.bombardReserve, on top of the founding kit): under it a dry swarm over its besieged hive breaks up for the ground instead; 0 arms from any stock. */
	public static float swarmBombardReserveMonths() { return f("threatinc_swarmBombardReserveMonths"); }
	/** Whether a swarm fleet over its own besieged hive that the stock would not arm breaks up its ships into the hive's ground troops (ThreatGroundFronts.breakUpForHive). */
	public static boolean hiveBreakUpEnabled() { return fabricateEnabled() && b("threatinc_hiveBreakUpEnabled", true); }
	/** Threat troops a relief force over its invaded world bombards per 30 days while the defenders are losing (ThreatGroundFronts.tickReliefBombard); 0 off. */
	public static float reliefBombardPer30Days() { return f("threatinc_reliefBombardPer30Days"); }
	/** Whether a relief force holds its world's orbit until the Threat army there is gone (ThreatFleetOrders.sendRelief); off, guardDays and home. */
	public static boolean reliefStays() { return b("threatinc_reliefStays", true); }
	/** Minimum total Threat fleet points in orbit for the swarm to hold it (a bombardment is a fleet operation, not a lone frigate). */
	public static float swarmOrbitMinFleetFP() { return f("threatinc_swarmOrbitMinFleetFP"); }

	// ---- stratum campaign: pushes, counter-attacks, eradication ----

	/** Armaments-upkeep multiplier while pushing. */
	public static float frontPushUpkeepMult() { return f("threatinc_frontPushUpkeepMult"); }
	/** Effectiveness multiplier of a dry (no armaments) front. */
	public static float frontDryEffectivenessMult() { return f("threatinc_frontDryEffectivenessMult"); }
	/** Whether the SWARM's own ground fronts burn heavy armaments and take the dry penalties. False (2026-09-08): nothing can resupply them, so they do not fight on armaments at all. */
	public static boolean threatFrontNeedsArms() { return b("threatinc_threatFrontNeedsArms", false); }
	/** Defense multiplier of an entrenched (non-pushing) front against counter-attacks. */
	public static float frontEntrenchDefenseBonus() { return f("threatinc_frontEntrenchDefenseBonus"); }
	/** Days a front consolidates at each stratum checkpoint before pushing on by doctrine. */
	public static float frontCheckpointDays() { return f("threatinc_frontCheckpointDays"); }
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

	public static float strikeLYPerFuel()    { return f("threatinc_strikeLYPerFuel"); }
	/** Fuel units an expedition carries at a fleet-size figure of 100 percent (vanilla's Fleets figure; hive: vitality x size / 4). */
	public static float reachFuelCarry()     { return f("threatinc_reachFuelCarry"); }
	public static int strikeMinSize()        { return i("threatinc_strikeMinSize"); }
	public static float strikeStrengthMult() { return f("threatinc_strikeStrengthMult"); }
	public static boolean fleetArchetypes()  { return b("threatinc_fleetArchetypes", true); }
	public static float playerGraceDays()    { return f("threatinc_playerGraceDays"); }

	// ---- faction reactive defense ----

	public static boolean responseEnabled()      { return b("threatinc_responseEnabled", true); }
	public static int   responseMinDifficulty()  { return i("threatinc_responseMinDifficulty"); }
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
	/** Every mobilised NPC faction stands down once the swarm is eradicated (ThreatIncursionIntel.isEradicated); the player's faction stands down by order only. */
	public static boolean warStandDownOnEradication() { return b("threatinc_warStandDownOnEradication", true); }
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
	/** Every faction not excluded mobilises once IncursionManager.getPhase reaches this, struck or not (IncursionManager.mobiliseAtPhase); 0 = only when struck. */
	public static int mobiliseAtPhase()      { return i("threatinc_mobiliseAtPhase"); }
	/** Reserve banked per 30 days per unit of vanilla SURPLUS (availability above demand): surplus units x the commodity's econ unit x this (docs/economy-coherence.md rule 1). */
	public static float reserveSurplusMult() { return f("threatinc_reserveSurplusMult"); }
	public static float reserveTroopSurplusMult() { return f("threatinc_reserveTroopSurplusMult"); }
	/** The hive's banking rate per unit its plants and forges make (ThreatFuel.perMonth, ThreatColonyUpkeep): split from reserveSurplusMult 2026-10-02, when the war simulator's round 18 showed the shared knob at 1.5 fed the swarm more than the humans (docs/war-sim-rounds.md 13). */
	public static float hiveSurplusMult() { return f("threatinc_hiveSurplusMult"); }
	/** Whether idle supplies build a fuel plant instead of a forge while fuel is tight (ThreatColonyManager's invest step, ThreatFuel.wantsSpare). */
	public static boolean investFuelWhenTight() { return b("threatinc_investFuelWhenTight", true); }
	/** The fuel plants the hive needs before investFuelWhenTight applies: with one, ThreatFuel.wantsSpare is always true. */
	public static int investFuelMinPlants() { return i("threatinc_investFuelMinPlants"); }
	/** Days a seeding wave may take beyond 30 a light year before it counts as stalled: re-steered once, then moved in (bootstrap) or lost (ThreatColonyManager.checkWaveArrivals). */
	public static float seedingWaveStallDays() { return f("threatinc_seedingWaveStallDays"); }
	/** A strike muster every world in reach of which waits on fuel books what the stock is short of the cheapest passage as demand (IncursionManager.pickStrikeTarget, ThreatFuel.heldShort). */
	public static boolean strikeWaitBooksFuel() { return b("threatinc_strikeWaitBooksFuel", true); }
	/** A frontline link is founded only with a garrison to hold it, which stays as long as the link stands. */
	public static boolean frontlineGarrisonEnabled() { return b("threatinc_frontlineGarrisonEnabled", true); }
	public static boolean frontlineHeavyIndustry()   { return b("threatinc_frontlineHeavyIndustry", true); }
	/** The core builds up (2026-10-18, replaces yards at home): mobilised factions answer shortages and add military structures at their core colonies, one start a faction a month, at vanilla cost in supplies (ThreatCoreBuild). */
	public static boolean coreBuild()                { return b("threatinc_coreBuild", true); }
	/** YARDS FIRST (the user, 2026-10-19): the wartime economy builds hull production before anything else - a Heavy Industry at every core colony that can site one, then Orbital Works, unpaced, and while one is held for its price no kit sails, no siege is provisioned and nothing else is built (ThreatCoreBuild.yardStep, ThreatFactionStock.yardWant). */
	public static boolean yardsFirst()               { return b("threatinc_yardsFirst", true); }
	public static boolean marineStep()               { return b("threatinc_marineStep", true); }
	/** WAR WANTS (2026-10-20): refused fuel and supplies wants are noted and read as shortage (ThreatFactionStock.noteShort). */
	public static boolean warWants()                 { return b("threatinc_warWants", true); }
	/** A human faction converts a surplus Fuel Production to the producer of its short stock (ThreatCoreBuild.convertStep). */
	public static boolean convertSurplusHuman()      { return b("threatinc_convertSurplusHuman", true); }
	/** Idle built hulls the supplies cannot keep are scrapped into supplies while a supplies want stands (ThreatHulls.scrapIdle). */
	public static boolean scrapIdleHulls()           { return b("threatinc_scrapIdleHulls", true); }
	/** MARINES FOLLOW THE WAR FOOTING (the user, 2026-10-19 18:50): a marine-bound faction whose hulls out-cover its marines raises the shortfall from its supplies. */
	public static boolean marineLevy()               { return b("threatinc_marineLevy", true); }
	/** Supplies one levied marine costs (vanilla: marines 200 credits, supplies 100). */
	public static float marineLevySuppliesEach()     { return f("threatinc_marineLevySuppliesEach"); }
	/** Days a levy trains before its marines stand at the colonies that paid. */
	public static float marineLevyDays()             { return f("threatinc_marineLevyDays"); }
	/** HOME FIRST (2026-10-20): a mobilised faction holds CONSOLIDATE, founds nothing and plans no play until its core colonies' military ladders and yards are complete. OFF since 13:10 (the user: the offence waits on worlds under attack, not on structures - HOME UNDER ATTACK is the gate). */
	public static boolean homeFirst()                { return b("threatinc_homeFirst", false); }
	/** Days after which an unfinished HOME FIRST hold ends anyway (0 = never): a deadlock hatch, logged. */
	public static float homeFirstMaxDays()           { return f("threatinc_homeFirstMaxDays"); }
	/** HOME UNDER ATTACK (2026-10-20): while an own world is under a Threat army the faction relieves it before any staging convoy, play or front run, every colony giving down to its floor (ThreatGroundFronts.homeUnderAttack). */
	public static boolean homeUnderAttack()          { return b("threatinc_homeUnderAttack", true); }
	/** A siege this far (0-1) along its voyage from base to target commits when home comes under attack; under it, it turns home (the user 2026-10-20: "offence is still a form of defence and time wasted is a net loss"). */
	public static float recallSiegeMaxProgress()     { return f("threatinc_recallSiegeMaxProgress"); }
	/** A marine relief sails only when the donors in reach can meet the world's whole need in one body this pass (hw66's rule for hulls; hw240c Jangala took 19 convoys of 100-500 marines and fell). */
	public static boolean reliefOnlyIfEnough()       { return b("threatinc_reliefMarinesOnlyIfEnough", true); }
	public static boolean alliedStaging()            { return b("threatinc_alliedStaging", true); }
	public static boolean invadeStaging()            { return b("threatinc_invadeStaging", true); }
	/** S1 (the user, 2026-10-19 15:30): the invade pass keeps at most this many hammers staging at once a faction (one a base). */
	public static int invadeStagingMax()             { return i("threatinc_invadeStagingMax"); }
	/** S1: a play's staging outlives the play as the base's commitment (ThreatConvoys.PlayStaging.committed) until a siege sails from the base or stagingCommitDays pass. */
	public static boolean stagingCommitment()        { return b("threatinc_stagingCommitment", true); }
	public static float stagingCommitDays()          { return f("threatinc_stagingCommitDays"); }
	/** S1: a hammer waiting on stock fails at the end of a wait window only if the refused figure has not risen (ThreatPlays.strike). */
	public static boolean councilWaitWhileRising()   { return b("threatinc_councilWaitWhileRising", true); }
	public static boolean frontlineFuelProduction()  { return b("threatinc_frontlineFuelProduction", true); }
	/** Smallest garrison a link gets, in fleet points, whatever the strikes in reach. */
	public static float frontlineGarrisonFP() { return f("threatinc_frontlineGarrisonFP"); }
	/** Garrison plus station must weigh this times the strongest Threat strike in reach (vanilla's raid strength). */
	public static float frontlineGarrisonMargin() { return f("threatinc_frontlineGarrisonMargin"); }
	public static float frontlineUpkeepStockMonths() { return f("threatinc_frontlineUpkeepStockMonths"); }
	/** A faction banks at most what its own markets produce above their own demand, shared across its markets (ThreatReserves.productionShare). */
	public static boolean reserveBankFromProduction() { return b("threatinc_reserveBankFromProduction", true); }
	/** With banking by production: the share of the sector's best single exporter a faction may bank of what it does not make. */
	public static float reserveBankImportsMult() { return f("threatinc_reserveBankImportsMult"); }
	public static boolean reserveWartimeFuel()     { return b("threatinc_reserveWartimeFuel", true); }
	/** The share of a colony's peacetime supplies demand that banks on top of its surplus in wartime: 0 the surplus rule, 1 its whole availability as fuel (ThreatReserves.wartimeShare). */
	public static float reserveWartimeSuppliesShare() { return f("threatinc_reserveWartimeSuppliesShare"); }
	/** Vanilla demand units the War footing condition adds at colony size 5 (scaled by size / 5, rounded up); 0 = none (rule 2). */
	public static float warFootingDemandUnits() { return f("threatinc_warFootingDemandUnits"); }
	/** Most of the stock at hand the depot spends per issue covering the colony's own shortage (rule 3). */
	public static float reserveShortageCoverFraction() { return f("threatinc_reserveShortageCoverFraction"); }
	/** Days one issue from the depot holds the colony's availability up; 0 = no covering (rule 3). */
	public static float reserveShortageCoverDays() { return f("threatinc_reserveShortageCoverDays"); }
	/** Months of its own production a colony's floor and donor keep are measured against - not a ceiling (2026-09-29). */
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
	/** An NPC siege waits unless its base can pay this fraction of the fuel and supplies its flotilla burns. */
	public static float expeditionMinProvisionsFraction() { return f("threatinc_expeditionMinProvisionsFraction"); }
	/** The board's Support or Defend at Med brings this multiple of the swarm over the world (ThreatAid.orbitAskFP); Min matches it. */
	public static float orbitTierMedMargin() { return f("threatinc_orbitTierMedMargin"); }
	/** The board's Support or Defend at Max brings this multiple of the swarm over the world. */
	public static float orbitTierMaxMargin() { return f("threatinc_orbitTierMaxMargin"); }
	/** Fuel an expedition draws per fleet point per light-year. */
	public static float expeditionFuelPerPointLY() { return f("threatinc_expeditionFuelPerPointLY"); }
	public static boolean threatPaysPassage()      { return b("threatinc_threatPaysPassage", true); }
	/** The hive's fleets go any distance whose trip it can pay (ThreatReach); off, the old fuel radius. */
	public static boolean billedReach()            { return b("threatinc_billedReach", true); }
	/** The factions' reach is their bill too: no radius, a base reaching as far as its stock pays (ThreatReach.baseRangeLY). */
	public static boolean humanBilledReach()       { return b("threatinc_humanBilledReach", true); }
	/** Each mobilised faction keeps its own stance - PRESS, EXPAND, CONSOLIDATE - as the hive does (ThreatFactionStance). */
	public static boolean factionStanceEnabled()   { return b("threatinc_factionStanceEnabled", true); }
	/** Standing upkeep (the user, 2026-10-06): the hulls a faction's yards built beyond vanilla's patrol table pay their ships' maintenance a month times this while at home; vanilla's patrols and the hive's garrison are vanilla's to keep. 0 turns it off (ThreatHulls.maintain). */
	public static float standingUpkeepMult()       { return f("threatinc_standingUpkeepMult"); }
	public static boolean structuresCostSupplies() { return b("threatinc_structuresCostSupplies", true); }
	public static float structureSuppliesMult()    { return f("threatinc_structureSuppliesMult"); }
	/** The hive planner turns a fuel plant its stock can spare into what it lacks, one a month (ThreatColonyManager.convertSurplus). */
	public static boolean hiveConvertSurplus()     { return b("threatinc_hiveConvertSurplus", true); }
	/** The swarm's bombardment draws its fuel from the hive's stock, at the rate everyone pays (ThreatGroundFronts.payOrdnance). */
	public static boolean threatPaysOrdnance()     { return b("threatinc_threatPaysOrdnance", true); }
	/** The factions' forward bases pay a supplies upkeep by size (ThreatColonyUpkeep); hive worlds pay none since 2026-10-25. */
	public static boolean sizeUpkeep()             { return b("threatinc_sizeUpkeep", true); }
	public static float sizeUpkeepAt3()            { return f("threatinc_sizeUpkeepAt3"); }
	public static float sizeUpkeepRatio()          { return f("threatinc_sizeUpkeepRatio"); }
	public static float upkeepBreakEven()          { return f("threatinc_upkeepBreakEven"); }
	public static float starveDaysPerSize()        { return f("threatinc_starveDaysPerSize"); }
	/** TANGIBLE FEEDING (the user, 2026-10-18): size upkeep arrives by a feeding swarm / convoy, absorbed on arrival (ThreatFeeding). */
	public static boolean feedConvoys()            { return b("threatinc_feedConvoys", true); }
	/** Months of a world's imported bill one feeding sailing carries. */
	public static float feedConvoyLoadMonths()     { return f("threatinc_feedConvoyLoadMonths"); }
	/** A feeding sailing is called when the larder (and what is at sea to it) holds under this many months of the bill. */
	public static float feedConvoyReserveMonths()  { return f("threatinc_feedConvoyReserveMonths"); }
	/** Size upkeep on the humans' core colonies (2026-10-18): the hive's table billed to every mobilised faction's colony, not the forward bases alone (ThreatCoreUpkeep). */
	public static boolean coreSizeUpkeep()         { return b("threatinc_coreSizeUpkeep", false); }
	/** Whether the player's own colonies pay it too. */
	public static boolean coreSizeUpkeepPlayer()   { return b("threatinc_coreSizeUpkeepPlayer", false); }
	/** Supplies an expedition draws per fleet point. */
	public static float expeditionSuppliesPerPoint() { return f("threatinc_expeditionSuppliesPerPoint"); }
	public static boolean fleetUpkeep()            { return b("threatinc_fleetUpkeep", true); }
	/** Days of its ships' maintenance a task force, hunt or raid carries at launch beside the voyage (docs/fleet-provisions.md). */
	public static float sortieStayDays()           { return f("threatinc_sortieStayDays"); }
	/** Days at the target the player's siege carries its ships' maintenance for beyond the voyage (the user, 2026-10-12: a siege is a long affair; player only, NPC sieges restock at their forward bases). */
	public static float siegeHoldDays()            { return f("threatinc_siegeHoldDays"); }
	/** Days of upkeep a task force carries to a world in its base's own system, where it restocks from the base (the user, 2026-10-11: "why does it take so many supplies to fly from one planet to another in the same system"). */
	public static float sortieHomeStayDays()       { return f("threatinc_sortieHomeStayDays"); }
	/** Whether a fleet low on supplies restocks from a base of its faction in its system before turning home. */
	public static boolean fleetResupply()          { return b("threatinc_fleetResupply", true); }
	/** Troop-transport share of a cargo-carrying expedition fleet (vanilla composition multiplier). */
	public static float expeditionTransportMult() { return f("threatinc_expeditionTransportMult"); }
	/** Whether mobilised factions run supply convoys between their colonies. */
	public static boolean convoyEnabled()     { return b("threatinc_convoyEnabled", true); }
	/** The reference marine load a worthwhile sailing is measured in, and a pooled haul billed per (ThreatConvoys.haulPerUnit) - not a cap (2026-09-29). */
	public static float convoyMarineCapacity() { return f("threatinc_convoyMarineCapacity"); }
	/** The reference cargo load (armaments, fuel, supplies) a worthwhile sailing is measured in, and a pooled haul billed per - not a cap (2026-09-29). */
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
	/** Hives detach swarms at a convoy until the pack's fleet points reach the convoy's times this (ThreatRaiders.consider; 1.5 until 2026-10-11, when no founding convoy of 24 freighters and a 30 FP escort died in 135). */
	public static float raiderMargin()        { return fd("threatinc_raiderMargin", 3f); }
	/** Strikes hide their origin; the sector acts only on hives someone has found (ThreatScouts). Off: the old omniscient rule. */
	public static boolean hiveFogOfWar()      { return b("threatinc_hiveFogOfWar", true); }
	/** Foreboding messages until anyone finds a hive (ThreatOmens). */
	public static boolean omensEnabled()      { return b("threatinc_omensEnabled", true); }
	/** Light-years from an unfound hive colony at which the player's fleet hears it. */
	public static float omenStaticLY()        { return f("threatinc_omenStaticLY"); }
	/** Days between omens heard by the player's fleet. */
	public static float omenStaticDays()      { return f("threatinc_omenStaticDays"); }
	/** Days between sector-wide omens (spread, swarm scouts, unseen strikes). */
	public static float omenSectorDays()      { return f("threatinc_omenSectorDays"); }
	/** Light-years around a strike's origin a struck faction's scouts sweep. */
	public static float scoutLeadRadiusLY()   { return f("threatinc_scoutLeadRadiusLY"); }
	/** Light-years around a military world a mobilised faction's routine sweep covers. */
	public static float scoutRangeLY()        { return f("threatinc_scoutRangeLY"); }
	/** Days between a mobilised faction's routine sweeps (while it has no lead). */
	public static float scoutIntervalDays()   { return f("threatinc_scoutIntervalDays"); }
	/** Days a scout sweeps an empty system before moving on. */
	public static float scoutStayDays()       { return f("threatinc_scoutStayDays"); }
	/** Days a scout may spend reaching one stop before it skips it. */
	public static float scoutLegMaxDays()     { return f("threatinc_scoutLegMaxDays"); }
	/** Days a system swept clear is left alone by routine sweeps. */
	public static float scoutMemoryDays()     { return f("threatinc_scoutMemoryDays"); }
	/** Combat fleet points of a scouting party. */
	public static float scoutFleetPoints()    { return f("threatinc_scoutFleetPoints"); }
	/** Fog of war on the swarm: factions and the player read reports, never the live swarm (ThreatIntel). */
	public static boolean intelFogOfWar()     { return b("threatinc_intelFogOfWar", true); }
	/** Days in which a report's trust halves. */
	public static float intelHalfLifeDays()   { return f("threatinc_intelHalfLifeDays"); }
	/** The swarm's own fog of war: it knows humans only from its reports (ThreatSwarmIntel, docs/threat-fog.md); off = today's live reads. */
	public static boolean swarmFogOfWar()     { return b("threatinc_swarmFogOfWar", true); }
	/** Days a sighted human attack still counts toward a hive system's pressure after it was last seen. */
	public static float swarmContactDays()    { return f("threatinc_swarmContactDays"); }
	/** Hyperspace range (LY) at which a live hive sees a human attack force in flight (ThreatSwarmIntel.sees); 0 = off. */
	public static float swarmPicketLY()       { return f("threatinc_swarmPicketLY"); }
	/** Mobilised factions plan sieges, raids and recon (ThreatAttackPlanner); off = the monthly per-base pick. */
	public static boolean attackPlanner()     { return b("threatinc_attackPlanner", true); }
	/** Mobilised factions hold a strategy and run plays (ThreatWarCouncil, docs/war-council.md); it replaces the attack planner. Off = the planner as built. */
	public static boolean warCouncil()        { return b("threatinc_warCouncil", true); }
	/** Days between a council's assessments of what it knows. */
	public static float councilAssessDays()   { return f("threatinc_councilAssessDays"); }
	/** Days a council holds its strategy before it reviews it (jittered). */
	public static float councilReviewDays()   { return f("threatinc_councilReviewDays"); }
	/** How random a council's choices are: 0 always the best score, 1 in proportion to the scores, higher flatter. */
	public static float councilTemperature()  { return f("threatinc_councilTemperature"); }
	/** A challenger strategy must outscore the held one by this share. */
	public static float councilSwitchMargin() { return f("threatinc_councilSwitchMargin"); }
	/** Our weight over the known swarm's at which a council reads the war as even. */
	public static float councilEvenRatio()    { return f("threatinc_councilEvenRatio"); }
	/** Our weight over the known swarm's at which a council reads itself ahead. */
	public static float councilAheadRatio()   { return f("threatinc_councilAheadRatio"); }
	/** The band changes only once the ratio is past its edge by this share. */
	public static float councilBandHysteresis() { return f("threatinc_councilBandHysteresis"); }
	/** Light-years within which a known hive threatens a colony. */
	public static float councilThreatLY()     { return f("threatinc_councilThreatLY"); }
	/** Phase and review timings vary by up to this share either way. */
	public static float councilJitter()       { return f("threatinc_councilJitter"); }
	/** Factions learn which plays work (weights x1.25 on success, x0.8 on failure). */
	public static boolean councilLearning()   { return b("threatinc_councilLearning", true); }
	/** Share of the faction's means a hammer musters. */
	public static float councilHammerShare()  { return f("threatinc_councilHammerShare"); }
	/** A play's muster below this share of what it was sent with disbands instead of going in. */
	public static float councilMusterFloor()  { return f("threatinc_councilMusterFloor"); }
	/** Share of a hammer's fleet points that sails in its siege expedition; the rest hunts the orbits. */
	/** Share of the faction's fuel a bombing campaign may burn. */
	public static float councilStarveShare()  { return f("threatinc_councilStarveShare"); }
	/** Share of the faction's means a feint sails with. */
	public static float councilFeintShare()   { return f("threatinc_councilFeintShare"); }
	/** Share of the faction's fuel bombers of opportunity may burn a month. */
	public static float councilOpportunityShare() { return f("threatinc_councilOpportunityShare"); }
	/** Fleet points of a doctrine bombing squadron. */
	public static float councilSquadronFP()   { return f("threatinc_councilSquadronFP"); }
	/** A play's escort: the swarm its faction's report shows in the system times this (at least its sieges' own fleet points). */
	public static float councilEscortMargin() { return f("threatinc_councilEscortMargin"); }
	/** Days between a faction's invasion passes (ThreatPlays.invade). */
	public static float councilInvadeDays()   { return f("threatinc_councilInvadeDays"); }
	/** Days a strike short of stock for any siege waits past its day before the play fails. */
	public static float councilStrikeWaitDays() { return f("threatinc_councilStrikeWaitDays"); }
	/** Days a play stages and scouts before it musters. */
	public static float councilPrepareDays()  { return f("threatinc_councilPrepareDays"); }
	/** Days a play's muster waits for its force before it goes with what it has. */
	public static float councilMusterDays()   { return f("threatinc_councilMusterDays"); }
	/** Days after the strike a play judges the damage done. */
	public static float councilExploitDays()  { return f("threatinc_councilExploitDays"); }
	/** Days between a bombing campaign's checks. */
	public static float councilStarveCheckDays() { return f("threatinc_councilStarveCheckDays"); }
	/** Raids driven off in a row that end a bombing campaign. */
	public static int councilStarveAbortRaids() { return i("threatinc_councilStarveAbortRaids"); }
	/** Days a target's Nexus must be down before a bombing campaign hands over to an invasion. */
	public static float councilInvadeNexusDays() { return f("threatinc_councilInvadeNexusDays"); }
	/** Days a feint has to draw the swarm before the strike goes anyway. */
	public static float councilFeintWatchDays() { return f("threatinc_councilFeintWatchDays"); }
	/** Days after the feint draws in which the strike must land. */
	public static float councilFeintWindowDays() { return f("threatinc_councilFeintWindowDays"); }
	/** Most days' sail from a feint's striking base to its target. */
	public static float councilStrikeMaxDays() { return f("threatinc_councilStrikeMaxDays"); }
	/** Days between a faction's plans, besides re-plans on news. */
	public static float planIntervalDays()    { return f("threatinc_planIntervalDays"); }
	/** The chance that at least one prong lands, reached by adding prongs. */
	public static float planConfidence()      { return f("threatinc_planConfidence"); }
	/** A raid goes home once it has lost this share of the fleet points it arrived with. */
	public static float raidLossFraction()    { return f("threatinc_raidLossFraction"); }
	/** The swarm counts a booked siege as an attack from dispatch, its fleets spawned or not (ThreatPosture.attacksBySystem). */
	public static boolean threatSeesBookedSieges() { return b("threatinc_threatSeesBookedSieges", true); }
	/** NPC sieges sail only with the marines and fleets their target needs; off = send what they can (trimmed to two fleets). */
	public static boolean npcSiegeFullStrength() { return b("threatinc_npcSiegeFullStrength", true); }
	/** NPC sieges sail only when their flotilla outweighs the target system's Defense Swarms; off = the garrison is not weighed. */
	public static boolean npcSiegeOrbitGate() { return b("threatinc_npcSiegeOrbitGate", true); }
	/** Fleet points an NPC flotilla brings per point of Defense Swarm its faction last saw over the strongest world it takes. */
	public static float npcSiegeOrbitMargin() { return f("threatinc_npcSiegeOrbitMargin"); }
	/** An NPC siege not yet landed turns home when hostile fleets over a world it is taking reach this x its own (0: fights to vanilla's abort line). */
	public static float siegeBreakOffRatio() { return f("threatinc_siegeBreakOffRatio"); }
	/** An off-screen fight costs the defending fleets too: min(0.75, half the attacker's strength over theirs) (ThreatAbstractBattle). */
	public static boolean abstractDefendersFight() { return b("threatinc_abstractDefendersFight", true); }
	/** Months of its garrison's supply upkeep a forward base keeps back when a sibling's siege pools its stock. */
	public static float siegeOutpostKeepMonths() { return f("threatinc_siegeOutpostKeepMonths"); }
	/** The orbit gate also weighs every Defense Swarm reported in the target system (off: the strongest world it takes). */
	public static boolean npcSiegeOrbitSystem() { return b("threatinc_npcSiegeOrbitSystem", false); }
	/** A Threat strike relieves a front of its own that is losing ground, in reach, before it opens a new one. */
	public static boolean strikeReliefFirst() { return b("threatinc_strikeReliefFirst", true); }
	/** DEFENCE FIRST (the user, 2026-10-10, C1): while any hive is short of what today's pressure needs held, the strike fund pays that defence and no prong is priced or sails (ThreatOffensive.payDefence). */
	public static boolean defenceFirst() { return b("threatinc_defenceFirst", true); }
	/** An unspawned expedition resolves a day after reaching its target, not at the end of vanilla's payload segment. */
	public static boolean abstractResolveOnArrival() { return b("threatinc_abstractResolveOnArrival", true); }
	/** The swarm passes over a world whose system's defence outweighs the strike it can muster. */
	public static boolean strikeDefenceGate() { return b("threatinc_strikeDefenceGate", true); }
	/** The orbit gate weighs the strongest single world's swarms, not the whole system's. */
	public static boolean npcSiegeOrbitPerWorld() { return b("threatinc_npcSiegeOrbitPerWorld", true); }
	/** Whether an outweighed NPC siege posts a bounty on the hive system's swarms. */
	public static boolean swarmBountiesEnabled() { return b("threatinc_swarmBountiesEnabled", true); }
	/** Credits a swarm bounty pays per frigate destroyed (destroyer x2, cruiser x3, capital x5, vanilla's). */
	public static float swarmBountyPerFrigate() { return f("threatinc_swarmBountyPerFrigate"); }
	/** Days a swarm bounty runs. */
	public static float swarmBountyDays()     { return f("threatinc_swarmBountyDays"); }
	/** Whether mobilised NPC bases with no siege of their own send hunting forces against bountied hives (ThreatSoftening). */
	public static boolean softenEnabled()     { return b("threatinc_softenEnabled", true); }
	/** Most combat FP in one fleet of a hunting force. */
	public static float softenFleetFP()       { return f("threatinc_softenFleetFP"); }
	/** Days a hunt order - an NPC hunting force, a coalition answer, or the player's Hunt - stays on the hunt. */
	public static float softenDays()          { return f("threatinc_softenDays"); }
	/** Days a base waits between hunting forces. */
	public static float softenIntervalDays()  { return f("threatinc_softenIntervalDays"); }
	/** A hunting force below this share of its strength at launch (or its last move-on) goes home whole. */
	public static float softenRetreatStrength() { return f("threatinc_softenRetreatStrength"); }
	/** Whether a hunting force draws fleets from every base of the faction in reach, not just the nearest. */
	public static boolean softenPool()        { return b("threatinc_softenPool", true); }
	/** STAGED LAUNCHES (the user, 2026-10-11): an NPC task force, hunt or raid sails only with its full provisions - fuel, deposit and hold - from the base it launches at (ThreatFleetOrders.buildTaskForce), and the faction's other markets no longer pay toward it remotely (ThreatSoftening.huntDonors): the base is staged by convoys first. */
	public static boolean stagedLaunch()      { return b("threatinc_stagedLaunch", true); }
	/** Days a hunting force waits at its muster point for stragglers. */
	public static float softenMusterDays()    { return f("threatinc_softenMusterDays"); }
	/** Muster spells a force short at the muster waits for stragglers that would carry it. */
	public static float softenMusterStragglerMult() { return f("threatinc_softenMusterStragglerMult"); }
	/** Whether a hunting force's fleets fold into its lead once gathered, to sail and fight as one fleet. */
	public static boolean softenMerge()       { return b("threatinc_softenMerge", true); }
	/** Most ships a hunting force's lead grows to by merging; the rest follow it. */
	public static int softenMergeMaxShips()   { return i("threatinc_softenMergeMaxShips"); }
	/** The swarm strikes only systems its Scouting Swarms have charted (ThreatSwarmScouts). Off: it knows every world. */
	public static boolean swarmScouting()     { return b("threatinc_swarmScouting", true); }
	/** Fleet points of a Scouting Swarm. */
	public static float swarmScoutFleetPoints() { return f("threatinc_swarmScoutFleetPoints"); }
	/** Shortfall (in convoy loads) below which no convoy sails. */
	public static float convoyMinLoadFraction() { return f("threatinc_convoyMinLoadFraction"); }
	/** Whether mobilised factions run supply and withdrawal convoys to their ground fronts. */
	public static boolean frontRunsEnabled()  { return b("threatinc_frontRunsEnabled", true); }
	/** Days of armaments a supply run tops a front up to. */
	public static float frontResupplyDays()   { return f("threatinc_frontResupplyDays"); }
	/** Days a front run waits at the hive system's jump-point for the orbit to clear before turning home. */
	public static float frontRunWaitDays()    { return f("threatinc_frontRunWaitDays"); }
	/** Days a convoy waits off a colony the Threat blockades (ThreatBlockade.penalty) before turning home with its cargo (user, 2026-10-17). */
	public static float convoyBlockadeWaitDays() { return f("threatinc_convoyBlockadeWaitDays"); }
	/** Fraction of its own cap a donor colony keeps back. */
	public static float donorKeepFraction()   { return f("threatinc_donorKeepFraction"); }
	/** Staging target as a multiple of one expedition's draw. */
	public static float stagingTargetMult()   { return f("threatinc_stagingTargetMult"); }
	/** Months of the faction's banking, on top of its stock in reach, a siege's fuel and supplies must fit in for a base to stage for it rather than for a hunt. */
	public static float stagingHorizonMonths() { return f("threatinc_stagingHorizonMonths"); }
	/** Days after which a convoy that has not arrived is written off. */
	public static float convoyTimeoutDays()   { return f("threatinc_convoyTimeoutDays"); }
	/** Whether the war board's fleet orders (guard, stage, intercept, siege, recall) are offered. */
	public static boolean ordersEnabled()     { return b("threatinc_ordersEnabled", true); }
	/** Least combat fleet points of an NPC guard or intercept task force (it sails sized to the need), and the free points a player colony needs to be picked first as a source; a player task force sails with everything its colony has free. */
	public static float guardFleetFP()        { return f("threatinc_guardFleetFP"); }
	/** Combat points of each fleet a relief force is built from (merged into one before it sails). */
	public static float reliefFleetFP()       { return f("threatinc_reliefFleetFP"); }
	/** Fleet points a relief brings per fleet point of swarm over an invaded world (2026-10-22, the user, after the siege's 1.5 held every relief for Sindria): the goal, the enough gate, the one-body build and a guard over a swarm-held orbit all read it. */
	public static float reliefOrbitMargin()   { return f("threatinc_reliefOrbitMargin"); }
	/** THE PLAYER AT THE WALL (2026-10-23, the user, Sindria): while the player's own guard or Defend fleets (or the player's fleet) stand over an invaded world, its owner and partners send what relief they can build, enough or not (ThreatFleetOrders.playerAtTheWall). */
	public static boolean reliefFollowsPlayer() { return b("threatinc_reliefFollowsPlayer", true); }
	/** RELIEF GOES WHERE THE SWARM IS (2026-10-23, the user, the Hegemony relief idling over Volturn while Sindria burned): a relief on station over a world with no Threat fleet moves to the owner's nearest invaded world where the swarm holds the orbit and it can outweigh it with the guards there, or the player is at the wall (ThreatFleetOrders.retaskReliefs). */
	public static boolean reliefRetask() { return b("threatinc_reliefRetask", true); }
	/** HOME WHEN THE SYSTEM IS QUIET (2026-10-24, the user): days a relief's system must hold no Threat fleet before the relief goes home; 0 holds it until the army on the ground is gone (ThreatFleetOrders.reliefQuiet). */
	public static float reliefQuietDays() { return f("threatinc_reliefQuietDays"); }
	/** Days a guard task force holds a colony's orbit. */
	public static float guardDays()           { return f("threatinc_guardDays"); }
	/** Days a task force guarding one of the player's own colonies stays; 0 = until recalled, staged there with its points the colony's to send out. */
	public static float guardOwnDays()        { return f("threatinc_guardOwnDays"); }
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
	/** Whether an unspawned strike leaves its share of its fleets over each landing, all of them over the last (off: one fleet a landing, the rest go home). */
	public static boolean strikeGuardWhole()  { return b("threatinc_strikeGuardWhole", true); }
	/** A landing's Defend stands down once the batteries have ground the fleet below this fraction of its strength. */
	public static float defendMinStrength()   { return f("threatinc_defendMinStrength"); }
	/** Whether a Defend fleet with nothing left to bombard breaks up its own hulls into troops for its front (docs/ground-war.md "Fabricating troops from the fleet"). */
	public static boolean fabricateEnabled()  { return b("threatinc_fabricateEnabled", true); }
	/**
	 * Whether a swarm Defend fleet over its own failing front breaks up hulls into troops
	 * (fabricateEnabled must be on too). Off, only a strike short of its beachhead does
	 * (ThreatStrikeFGI.beachheadLanding); the guard holds orbit and bombards.
	 */
	public static boolean fabricateDefendEnabled() { return fabricateEnabled() && b("threatinc_fabricateDefendEnabled", true); }
	/** Troops landed per fleet point of hulls broken up: strataPerFP (the name kept for its readers). */
	public static float fabricateTroopsPerFP(){ return strataPerFP(); }
	/** How far past the hold line fabrication aims, so the front does not oscillate on the boundary. */
	public static float fabricateHoldMargin() { return f("threatinc_fabricateHoldMargin"); }
	/** Days of armaments a batch of fabricated troops brings down with it. */
	public static float fabricateSupplyDays() { return f("threatinc_fabricateSupplyDays"); }
	public static boolean orbitFleetsHunt()   { return b("threatinc_orbitFleetsHunt", false); }
	/** Defenders at a world hold its orbit against a faction while their points are at least this fraction of that faction's warships there; nothing friendly there, and any defender holds it. */
	public static float orbitContestFraction() { return f("threatinc_orbitContestFraction"); }
	/** Fraction of the fuel and supplies drawn at launch refunded when a fleet returns home at full strength. */
	public static float returnRefundMult()    { return f("threatinc_returnRefundMult"); }
	/** Share of an NPC fleet's supplies draw that paid for its hulls: refunded at surviving strength in full on return (closed economy, 2026-09-29). */
	public static float returnHullShare()     { return f("threatinc_returnHullShare"); }

	// ---- player aid (docs/player-aid.md) ----

	/** One hull economy from vanilla's figures, every side (ThreatHulls; the user, 2026-10-06): standing hulls from patrol counts, losses rebuilt from ships output only. Off: the player's aidBaseFP allowance and free NPC hulls. */
	public static boolean hullPool()          { return b("threatinc_hullPool", true); }
	/** Multiplies every market's standing hulls (1 = vanilla's patrol figure). */
	public static float hullPoolMult()        { return f("threatinc_hullPoolMult"); }
	/** The share of its built navy's free hulls a faction keeps when an ally is short of hulls (ThreatHulls.spareUnits). */
	public static float hullAidKeepFraction() { return f("threatinc_hullAidKeepFraction"); }
	/** Credits a fleet point of ship hulls is worth to the player's aid contracts and standing; 0 = priced as the ships good, 200 items a unit (ThreatAid.valueAt). */
	public static float hullCreditsPerFP()   { return f("threatinc_hullCreditsPerFP"); }
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
	/** REQUEST AID (2026-10-23, docs/war-effort-rework.md 3): whether the player may ask the sector for a colony's need (ThreatAidRequests). */
	public static boolean aidRequestsEnabled(){ return b("threatinc_aidRequestsEnabled", true); }
	/** Days between the player's requests for one colony. */
	public static float aidRequestCooldownDays() { return f("threatinc_aidRequestCooldownDays"); }
	/** Price multipliers of asking at Min / Med / Max, on the giving rate (aidRepPerCredits, aidRepGuardArrived). */
	public static float aidAskPriceMin()      { return f("threatinc_aidAskPriceMin"); }
	public static float aidAskPriceMed()      { return f("threatinc_aidAskPriceMed"); }
	public static float aidAskPriceMax()      { return f("threatinc_aidAskPriceMax"); }
	/** WAR COMMS (ThreatComms): voiced notices when a need appears, escalates or is met. */
	public static boolean aidCommsEnabled()   { return b("threatinc_aidCommsEnabled", true); }
	/** Days between comms notices for one world and purpose. */
	public static float aidCommsMinDays()     { return f("threatinc_aidCommsMinDays"); }
	/** Days a postponed siege's provisions need stands on the ledger (ThreatNeeds.notePostponed). */
	public static float aidExpeditionNoteDays() { return f("threatinc_aidExpeditionNoteDays"); }
	/** Standing weights for giving, by purpose (ThreatNeeds.weight): hold the line / lift the orbit; push back / arm / provision; the depot / the navy; surplus or a good nobody needs. */
	public static float aidRepWeightCritical(){ return f("threatinc_aidRepWeightCritical"); }
	public static float aidRepWeightUrgent()  { return f("threatinc_aidRepWeightUrgent"); }
	public static float aidRepWeightRoutine() { return f("threatinc_aidRepWeightRoutine"); }
	public static float aidRepWeightSurplus() { return f("threatinc_aidRepWeightSurplus"); }
	/** A defence is requested when the strike's strength exceeds the defenders' times this. */
	public static float defendRequestRatio()  { return f("threatinc_defendRequestRatio"); }
	/** Days a vanilla deficit must stand before a colony's depot need goes on the ledger (ThreatAidRequests.shortageStanding). */
	public static float missionAidShortageDays() { return f("threatinc_missionAidShortageDays"); }
	/** Whether allied mobilised factions guard and resupply each other's colonies. */
	public static boolean allyAidEnabled()    { return b("threatinc_allyAidEnabled", true); }
	/** Chance per tick per need that a fully willing ally sends aid; scaled down by standing. */
	public static float allyAidChance()       { return f("threatinc_allyAidChance"); }
	/** Whether a faction and its partners relieve an invaded world together when none is enough alone (ThreatCoalition.jointRelief). */
	public static boolean coalitionRelief()   { return b("threatinc_coalitionRelief", true); }
	/** ONE BODY OR NONE (2026-10-20): a relief built short of the owed points stands down (ThreatFleetOrders.sendRelief, ThreatCoalition.jointRelief); off, it sails what was built. */
	public static boolean reliefOneBody()     { return b("threatinc_reliefOneBody", true); }
	/** A SIEGE IS PAID FROM THE STOCK (2026-10-21): months over which a faction with an own world under a Threat army may keep hulls from its supplies stock as well as its income (ThreatHulls.suppliesKeepFP); 0 = income only. */
	public static float siegeStockMonths()     { return f("threatinc_siegeStockMonths"); }
	/** Hull cap once a faction in joint relief (2026-10-20, ThreatCoalition.capHulls); off, every sender of a faction promises the faction's whole free pool. */
	public static boolean coalitionHullCap()  { return b("threatinc_coalitionHullCap", true); }
	/** A: the partners relieve an invaded world its owner has no base to relieve from, HOME UNDER ATTACK or not (ThreatFleetOrders.planRelief). */
	public static boolean reliefCoalitionNoBase() { return b("threatinc_reliefCoalitionNoBase", true); }
	/** B: a willing ally's relief of an NPC world is the coalition's joint relief, not its one base's (ThreatCoalition.allyAid). */
	public static boolean allyAidJointRelief()    { return b("threatinc_allyAidJointRelief", true); }

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
	/** Station tier: 1 orbital station, 2 battlestation, 3 star fortress. */
	public static int outpostTier()           { return i("threatinc_outpostTier"); }
	/** Credits the player pays for an outpost. */
	public static float outpostCredits()      { return f("threatinc_outpostCredits"); }
	/** Supplies an NPC faction's base pays for an outpost. */
	public static float outpostSupplies()     { return f("threatinc_outpostSupplies"); }
	/** Fuel an NPC faction's base pays for an outpost. */
	public static float outpostFuel()         { return f("threatinc_outpostFuel"); }

	// ---- frontline outposts and strike warning (docs/frontlines.md) ----

	public static boolean frontlinesEnabled()        { return b("threatinc_frontlinesEnabled", true); }
	public static float frontlinePlanDays()          { return f("threatinc_frontlinePlanDays"); }
	public static float frontlineLinkLY()            { return f("threatinc_frontlineLinkLY"); }
	public static float frontlineReachLY()           { return f("threatinc_frontlineReachLY"); }
	public static float frontlineKeepLY()            { return f("threatinc_frontlineKeepLY"); }
	public static float frontlineGrowDays()          { return f("threatinc_frontlineGrowDays"); }
	public static float frontlineStarveDays()        { return f("threatinc_frontlineStarveDays"); }
	public static float frontlineAbandonDays()       { return f("threatinc_frontlineAbandonDays"); }
	public static float frontlineRearGraceDays()     { return f("threatinc_frontlineRearGraceDays"); }
	public static boolean pathTithes()               { return b("threatinc_pathTithes", true); }
	public static float pathTitheSuppliesPerSize()   { return f("threatinc_pathTitheSuppliesPerSize"); }
	public static float pathTitheFuelPerSize()       { return f("threatinc_pathTitheFuelPerSize"); }
	public static float pathTitheMarinesPerSize()    { return f("threatinc_pathTitheMarinesPerSize"); }
	public static float pathTitheSleeperFraction()   { return f("threatinc_pathTitheSleeperFraction"); }
	public static float pathMilitiaMult()            { return f("threatinc_pathMilitiaMult"); }
	public static boolean pathZealotMarines()        { return b("threatinc_pathZealotMarines", true); }
	public static float frontlineRelayAccess()       { return f("threatinc_frontlineRelayAccess"); }
	public static float frontlineStrikeWeight()      { return f("threatinc_frontlineStrikeWeight"); }
	public static boolean frontlineReliefEnabled()   { return b("threatinc_frontlineReliefEnabled", true); }
	public static boolean strikeDetection()          { return b("threatinc_strikeDetection", true); }
	public static float strikeDetectLY()             { return f("threatinc_strikeDetectLY"); }

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
	public static boolean debugPlayerIgnored() { return b("threatinc_debugPlayerIgnored", false); }
	public static boolean debugSimDump() { return b("threatinc_debugSimDump", false); }
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
	public static int debugHiveFloorSize()         { return i("threatinc_debugHiveFloorSize"); }
	public static int debugInstantWarColonySize()  { return i("threatinc_debugInstantWarColonySize"); }

	public static void log(String msg) {
		if (debugLogging()) {
			Global.getLogger(ThreatIncConfig.class).info("[ThreatInc] " + msg);
		}
	}

	/** key -> {shape of the last line, when it was logged}; not saved, a reload logs each once more. */
	private static final java.util.Map<String, Object[]> QUIET = new java.util.HashMap<String, Object[]>();
	private static final float QUIET_DAYS = 30f;

	/**
	 * A line a poll repeats: logged when its wording changes (numbers aside),
	 * or once a month while it does not.
	 */
	public static void logQuiet(String key, String msg) {
		if (!debugLogging() || Global.getSector() == null) return;
		String shape = msg.replaceAll("[0-9]+", "#");
		long now = Global.getSector().getClock().getTimestamp();
		Object[] last = QUIET.get(key);
		if (last != null && shape.equals(last[0])) {
			float days = Global.getSector().getClock().getElapsedDaysSince((Long) last[1]);
			if (days >= 0f && days < QUIET_DAYS) return;
		}
		QUIET.put(key, new Object[] { shape, now });
		log(msg);
	}

	/**
	 * A line a poll repeats, logged only when {@code state} changes - a verdict,
	 * not the figures behind it - and no more than once in ten days: a verdict
	 * that flips every pass (Goodfellow from Gilead, 90 times) logs its latest.
	 */
	public static void logOnChange(String key, String state, String msg) {
		if (!debugLogging() || Global.getSector() == null) return;
		Object[] last = QUIET.get(key);
		if (last != null && state.equals(last[0])) return;
		if (last != null && Global.getSector().getClock().getElapsedDaysSince((Long) last[1]) < 10f) return;
		QUIET.put(key, new Object[] { state, Global.getSector().getClock().getTimestamp() });
		log(msg);
	}

	/** Called on load: every quiet line logs once more in the loaded game. */
	public static void forgetQuiet() {
		QUIET.clear();
	}
}
