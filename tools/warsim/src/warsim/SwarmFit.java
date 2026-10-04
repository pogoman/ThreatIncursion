package warsim;

import java.util.Random;

/**
 * Every constant of the swarm side that is not a knob and not a shared rule: fitted to the
 * dated log of run pd9a (starsector.log, 2026-10-02; "w" is the war day of the line) and the
 * probe dumps, or read off the mod's source where the value is a constant there. Change one
 * only with the line it was fitted from (docs/war-sim-swarm.md lists them).
 */
public final class SwarmFit {
	private SwarmFit() {}

	// ---- constants of the mod (not knobs), copied ----

	/** IncursionManager.FP_PER_RESPONSE_DIFFICULTY. */
	public static final float FP_PER_POINT = 25f;
	/** ThreatReturns.RETURN_LEG_SHARE. */
	public static final float RETURN_LEG_SHARE = 0.5f;
	/** ThreatReach.STRIKE_PREP_DAYS. */
	public static final float STRIKE_PREP_DAYS = 10.5f;
	/** ThreatPosture.DECAY_DAYS and its mode thresholds. */
	public static final float DECAY_DAYS = 30f;
	public static final float WATCHFUL_ENTER = 0.25f, WATCHFUL_LEAVE = 0.15f, THREATENED_ENTER = 1f,
			THREATENED_LEAVE = 0.8f;
	/** ThreatPosture.LAUNCH_STOCK: the rows a forge colony keeps above its reserve to send. */
	public static final int LAUNCH_STOCK = 2;
	/** ThreatStance's constants. */
	public static final float TREND_DAYS = 60f, HIVE_WINDOW_DAYS = 90f, STANCE_LEAVE = 0.8f, SIGNIFICANT_LOSS = 0.05f,
			TARGET_WEIGHT = 10f;
	/** ThreatColonyUpkeep.SHRINK_MARGIN. */
	public static final float SHRINK_MARGIN = 0.5f;
	/** ThreatFuel.SHORT_DAYS, and the producers' build time its trailing demand looks back over. */
	public static final float SHORT_DAYS = 30f, STOCK_TAU_DAYS = 120f;
	/** The founding's FP bill a forge must cover to count as paying: 5 structures at foundingFPPerStructure. */
	public static final int FOUNDING_BILL_STRUCTURES = 5;
	/** A Seeding Swarm's fleet points ("Founding" in ThreatColonyManager.launchColonizationWave; log: 600 FP waves). */
	public static final float WAVE_FP = 600f;
	/** vanilla settings.json maxIndustries by size (1-3: 1, 4: 2, 5: 3, 6+: 4). */
	public static int maxIndustries(int size) { return size <= 3 ? 1 : size == 4 ? 2 : size == 5 ? 3 : 4; }
	/** FabricatorEscortStrength ordinals (NONE 0): the size table's tiers, and what swarmCostEstimate indexes its fallback by. */
	public static final int LOW = 1, MEDIUM = 2, HIGH = 3, MAXIMUM = 4;
	/** vanilla economy units (commodity econUnit) the hive banks per unit of output a month. */
	public static final float SUPPLIES_UNIT = 750f, FUEL_UNIT = 1500f;

	// ---- build prices in supplies and build days: "Build cost: <id> N supplies (spec cost .., D days)", w318-w1066 ----

	public static final float COST_MINING = 1000f, COST_REFINING = 2250f, COST_FORGE = 5000f, COST_FUELPLANT = 4500f,
			COST_GROUND_DEF = 1500f, COST_BATTERIES = 3000f, COST_ORBITAL = 3000f;
	public static final float DAYS_FORGE = 120f, DAYS_FUELPLANT = 120f;
	/** A founding's structures: spaceport 500 + swarm nexus 3000 (w338 Build cost lines); with outpostSupplies 1500 the log's "5000 supplies". */
	public static final float FOUNDING_KIT = 3500f;
	/** Swarm Bastion 3,749 FP (w974), Swarm Command 1,250 FP (w1156); the Bastion stands after a Military Base's 120 days. */
	public static final float BASTION_FP = 3750f, COMMAND_FP = 1250f, BASTION_DAYS = 120f;

	// ---- fitted ----

	/** The tick runs every tickDays plus the fast-forward's overrun: "Spread to" lines w368..w1066, 23 ticks in 698 days. */
	public static final float TICK_OVERRUN_DAYS = 0.35f;
	/** Planets the opening chain lands on: 5 hives at month 5 in both runs (probe dump m5, pd9a w138-w148). */
	public static final int OG_CHAIN = 5;
	/** Days from the opening waves' launch (w126) to each landing (w138, w141, w141, w142, w148). */
	public static int bootstrapTravelDays(Random r) { return 12 + r.nextInt(11); }
	/** The opening wave digs in as the first garrison: 3,618 FP in 5 fleets at month 5 (701-743 each). */
	public static float bootstrapSwarmFP(Random r) { return 700f + 45f * r.nextFloat(); }
	/** Days a paid wave takes beyond its passage: launch to "Colony founded" 11-18 days at 1-4 ly (w489/w500 .. w1035/w1050). */
	public static int waveLandingDays(Random r) { return 8 + r.nextInt(6); }
	/**
	 * What a swarm of a tier comes out at: "Garrison fleet fabricated" and "Posture: .. fabricated"
	 * lines - size-1 swarms 81-100, MEDIUM 104-172, HIGH 289-376; MAXIMUM never seen, the mod's table.
	 */
	public static float builtFP(int fabricators, int tier, Random r) {
		float mean = tier <= LOW ? 90f : tier == MEDIUM ? 140f : tier == HIGH ? 340f : 458f;
		return (mean + 40f * fabricators) * (0.78f + 0.44f * r.nextFloat());
	}
	/**
	 * Supplies a month a fleet point away burns: "Reach: .. fleets away N/mo, 0.73 a FP", the weighted mean of 331 lines of
	 * hw4p, hw4z and hw5b (0.71-0.74 a run; w765-w949 read 0.50-0.57, before strikes burned off-screen). Round 32
	 * (2026-10-04): at 0.5 the simulated swarm burned 0.85M on fleets away by month 120 against hw4z's 1.38M and piled
	 * 850k supplies against the game's 24k. warsim_suppliesPerFPAway overrides it.
	 */
	public static final float SUPPLIES_PER_FP_AWAY = 0.73f;
	/**
	 * A spare planet of a held system carries deposits worth a wave. Of the spare planets of the systems
	 * held at month 60, the swarm had colonised 25 of 33 by pd9a's end and 43 of 44 by pd10a's (0.88). The
	 * pace is the stock's and the forges' (SwarmOps.expandInSystem), not this share: it stood at 0.17, the
	 * 2 in-system waves over 12 spare planets by w1100, while the simulator sent one such wave a pass.
	 */
	public static final float EXPANSION_PLANET_SHARE = 0.88f;
	/**
	 * A strike's strength in the gate's vanilla units: 300 a swarm - "strength 916 / 1200 / 900 / 1500"
	 * for 3, 4, 3, 5 swarms of 391, 611, 422, 754 FP (w641-w914) - or 2.1 a FP for a fleet
	 * loaded in flight, whose swarm count the dump does not carry.
	 */
	public static final float STRIKE_UNITS_PER_SWARM = 300f, STRIKE_UNITS_PER_FP = 2.1f;
	/** A strike's muster before it sails: launchStrike's 7-14 days. */
	public static int strikePrepDays(Random r) { return 7 + r.nextInt(8); }
	/**
	 * Days from a landing to the ground victory: Kanni 50 (w761-w811), Kanta's Den 71 (w843-w914), Qaras 96
	 * (w955-w1051); also Lost Astropolis 71, Kapteyn Starworks 72, Garnir 51, Epiphany 89. All seven
	 * were worlds with no armed reserve: pirates, the Path, or a faction not yet mobilised.
	 */
	public static int groundDays(Random r) { return 50 + r.nextInt(47); }
	/**
	 * Troops a strike lands per fleet point that reached the orbit: "Abstract siege of Salamanca: 0 d,
	 * 700 -> 700 FP" then "Strike pass (landing) vs Salamanca: 560 troops" (w1777); 2075 FP -> 1660
	 * (w1937); 0.80 in 47 of pd9a's 60 landings (strikeTroopsPerPoint 20 a difficulty point of 25 FP).
	 */
	public static final float TROOPS_PER_FP = 0.8f;
	/** IncursionManager's floor: "Strike landing at X aborted: only N troops left aboard for it" under 50. */
	public static final float LANDING_MIN_TROOPS = 50f;
	/**
	 * Days from a landing on a colony at war to "Counter-attack at X overran the beachhead": pd9a's 44
	 * fronts that were never reinforced ran 19-248 days, p10 50, median 136, p90 228 (the garrison
	 * counter-attacks every 2-6 weeks at 1.3-1.9:1 and overruns at 2:1). None of the 56 took a colony.
	 */
	public static int overrunDays(Random r) { return 40 + r.nextInt(200); }
	/** What a reinforcing pass adds to a front's life: the 12 reinforced fronts ran 187-751 days, median 405, on 1-3 passes. */
	/**
	 * warsim_guardLife (round 32, 2026-10-04): days a Threat guard holds Defend over a human colony before the colony's
	 * own orbit defence (vanilla's station and patrols, which the simulator does not hold) grinds it below
	 * defendMinStrength and ThreatSwarmDefend.tick sends it home. Deciles of 247 game guards that stood down with their
	 * front still up ("Strike guard over X" to "Swarm defend over X stands down", hw4s, hw4w, hw4z, hw5b, hw5d), the
	 * same at every guard size: 0, 4, 5, 7, 10, 12, 15, 19, 23, 38, max 128.
	 */
	static final float[] GUARD_LIFE_DECILES = { 0f, 4f, 5f, 7f, 10f, 12f, 15f, 19f, 23f, 38f, 128f };

	public static int guardLifeDays(Random r) {
		float q = r.nextFloat() * 10f;
		int i = Math.min(9, (int) q);
		return Math.round(GUARD_LIFE_DECILES[i] + (GUARD_LIFE_DECILES[i + 1] - GUARD_LIFE_DECILES[i]) * (q - i));
	}

	public static int reinforcedDays(Random r) { return 100 + r.nextInt(100); }
	/**
	 * The share of ticks an invaded colony at war still passes the strike gate (strikeOutweighed
	 * against the defence its relief has raised; the dump's "defence" of Salamanca ran 440 -> 5058,
	 * Tigra City 150 -> 1068 once at war, and pd9a logged 1,000+ "Strike gate: X passed over").
	 * Stands in for a defence model of a colony at war: 28 "Strike pass (reinforce)" for 86 landings.
	 */
	public static final float INVADED_GATE_SHARE = 0.1f;
	/**
	 * warsim_basesHold (hypothesis, round 8): days a strike must hold a forward base's orbit with its guard sunk before
	 * the station falls - the coordinator's "30 days, as sieges on hives work"; nothing in the mod or a run fits it.
	 */
	public static final int STATION_SIEGE_DAYS = 30;
	/**
	 * warsim_coloniesFall (hypothesis, round 8): a colony's own ground defence per size, the vanilla figure the
	 * strike gate ignores (facts.md: Donn's ground defence 55 against a fleet gate of 1,447); the armed marines of
	 * the reserve (w.stock, x reserveDefenseMult) are the rest, at marineCounterAttackMult 0.25 when they counter-attack
	 * (ground-war-defenders.md "Holding a line and going over the top are different jobs").
	 */
	public static final float COLONY_GROUND_PER_SIZE = 15f, MARINE_COUNTER_ATTACK_MULT = 0.25f;
	/** A world taken becomes a hive when it is a planet: Kanni and Qaras did, Kanta's Den (a station) did not. */
	public static final float CONQUEST_HIVE_SHARE = 0.67f;
	/**
	 * Scouting Swarms a tick, per hive system: "Reach: .. scouts N" 1-2 with 2-3 systems (w520-w643),
	 * 2-5 with 4-7 (w673-w856), 2-9 with 8-10 (w888-w1193); they fly a mean 48-77 ly.
	 */
	public static final float SCOUT_SHARE_PER_SYSTEM = 0.6f;
	/** A sighting goes stale: "Swarm intel census: .. oldest 46 d; stale systems 2" against none at 16-17 d. */
	public static final float STALE_DAYS = 30f;
	/** Factions the swarm strikes before they are at war (ThreatWarState.excluded): pd9a's pre-armada strikes were all on pirates. */
	public static boolean neverMobilises(String factionId) {
		return "pirates".equals(factionId) || "luddic_path".equals(factionId);
	}
	/** Spread target need score (systemNeedScore reads deposits the map does not carry): a draw of 0-50 in its place. */
	public static float needScore(Random r) { return 50f * r.nextFloat(); }
}
