package warsim;

/**
 * The human side's fitted constants: figures the simulator cannot compute from the shared
 * rules or the knobs, each with the log line or dump field it was read from. Run pd7a
 * (%TEMP%\threatinc-tests\ti-pd7a.txt) unless said otherwise. docs/war-sim-humans.md lists them.
 */
final class HumanFit {
	private HumanFit() {}

	/**
	 * Reserve accrual per 30 days by colony size 3..8 {marines, armaments, fuel, supplies}: the
	 * medians of 2966 "Reserve ledger: Coatl (hegemony, size 5) | marines 319 [6/6 surplus 1 +51/30d] ..."
	 * lines. Used only for a world the dump gives no accrualPer30 (a faction not yet mobilised).
	 */
	static final float[][] ACCRUAL_BY_SIZE = {
			{ 45f, 60f, 545f, 482f }, { 53f, 56f, 678f, 519f }, { 53f, 56f, 1140f, 482f },
			{ 83f, 105f, 1267f, 900f }, { 35f, 88f, 1038f, 0f }, { 40f, 180f, 1560f, 675f } };

	/**
	 * A forward base's accrual: rate per surplus unit {marines, armaments, fuel, supplies} from the
	 * ledger of a persean size-2 base (marines 33, arms 42, fuel 521 = 3 units, supplies 375);
	 * surplus units are 1 (2 at size 6) and, for fuel, size + 1.
	 */
	static final float[] BASE_RATE = { 33f, 42f, 174f, 375f };

	/**
	 * A task force's whole-fleet points per combat point (ThreatAidCapacity.taskForcePoints): a relief fleet of reliefFleetFP
	 * 300 logs "sends 357-360 FP" (hw4p, hw4z, hw5b), support hulls included.
	 */
	static final float FLEET_POINTS_PER_COMBAT_FP = 1.195f;

	/**
	 * Round 32 (2026-10-04) refit of ACCRUAL_BY_SIZE: the pd7a table predates war-footing banking and
	 * runs about half the game's fuel. Medians of the first 48 prints per world of "Reserve ledger" lines,
	 * fuel and supplies divided by the runs' reserveSurplusMult 1.5 (HumanPools.daily puts it back).
	 * Colony sizes 3..8 at reserveWartimeSuppliesShare 0 (hw4m, hw4p) and 1 (hw4s, hw5b, hw5d);
	 * HumanPools.ensure interpolates by the share. warsim_accrualFitPd7a=true keeps the old tables.
	 */
	static final float[][] ACCRUAL_SHARE0 = {
			{ 48f, 69f, 420f, 545f }, { 53f, 61f, 723f, 535f }, { 53f, 56f, 1285f, 482f },
			{ 80f, 100f, 1369f, 833f }, { 60f, 111f, 1846f, 600f }, { 40f, 190f, 1443f, 833f } };
	static final float[][] ACCRUAL_SHARE1 = {
			{ 41f, 56f, 369f, 411f }, { 46f, 55f, 720f, 529f }, { 51f, 52f, 1235f, 711f },
			{ 80f, 105f, 1548f, 893f }, { 63f, 173f, 2333f, 1304f }, { 40f, 190f, 1692f, 1167f } };

	/** The same refit for forward bases by market size 1..6 (BASE_RATE's successor). */
	static final float[][] BASE_SHARE0 = {
			{ 31f, 50f, 402f, 461f }, { 38f, 52f, 596f, 469f }, { 41f, 52f, 785f, 477f },
			{ 46f, 50f, 937f, 421f }, { 53f, 56f, 1055f, 469f }, { 110f, 173f, 1978f, 833f } };
	static final float[][] BASE_SHARE1 = {
			{ 35f, 55f, 512f, 517f }, { 39f, 52f, 667f, 500f }, { 45f, 50f, 800f, 476f },
			{ 50f, 52f, 1043f, 617f }, { 55f, 55f, 1224f, 686f }, { 98f, 186f, 2011f, 1133f } };

	/**
	 * IncursionManager.hasMilitary for a world whose dump carries no "military" flag: the
	 * dump's "base" is false until the faction mobilises. "Hunting force waits at Mazalot: 7 bases"
	 * against persean's 9 colonies of size 5 and up.
	 */
	static final int MILITARY_MIN_SIZE = 5;

	/** Hive ground defences from size 3: dump "defence" 600 (size 1), 1200 (2), 3600 (3), 6000 (5). */
	static final int GROUND_DEFENCES_MIN_SIZE = 3;

	/** Heavy batteries (heavyBatteriesBonus) from size 6: pd9a dump "defence" 400 x size x 4.5 at sizes 6-8, x 3 at 3-5. */
	static final int HEAVY_BATTERIES_MIN_SIZE = 6;

	/** SwarmBastion's defence multiplier by tier (Bastion 1.2, Command 1.3): ThreatGroundFronts.defenderStrength. */
	static final float[] TIER_MULT = { 1f, 1.2f, 1.3f };

	/**
	 * The share of its defence a hive keeps at full bombardment unrest (vanilla's stability
	 * term in MarketCMD.getDefenderStr, bombardUnrestMax): "siegeSlice ... defence=" first -> last,
	 * 1200 -> 419 with only a nexus, 4800 -> 746 and 1923 -> 409 with ground defences.
	 */
	static final float UNREST_DEFENCE_FLOOR = 0.5f;

	/** The smallest siege that sailed ("... 240 FP reported over the strongest, 425 FP sent": least 225). */
	static final float MIN_SIEGE_FP = 225f;

	/** The sizing search stops here; pd9a's largest postponed expedition wanted 200 points. */
	static final float MAX_SIEGE_FP = 6000f;

	/**
	 * Days between a hunting force's battles: 464 "Hunt battle near Qaras: persean side 1 fleets
	 * 1297 FP vs 163 FP" lines over 248 forces of up to softenDays; each against one Defense Swarm.
	 */
	static final int HUNT_BATTLE_DAYS = 20;

	/** ThreatFrontlines' link kit over outpostSupplies (pd9a: "Frontline: paid 5500 supplies and 800 fuel from Salamanca"). */
	static final float LINK_KIT_SUPPLIES = 4000f;

	/**
	 * ThreatFrontlines.strikeOf as a share of the reported garrison: pd9a "Salamanca garrisons Beta Blost
	 * Dantalion I Forward Base with 3 fleet(s), 1502 FP, strength 2553 of 2130 (front; strike in reach 1800)"
	 * against size-7 hives reported at 2264-3986 FP. A weak fit: one line.
	 */
	static final float STRIKE_SEND_SHARE = 0.4f;

	/** ThreatFrontlines.STRENGTH_PER_FP and its garrison's UPKEEP_PER_FP (supplies a month). */
	static final float STRENGTH_PER_FP = 1.4f, GUARD_UPKEEP_PER_FP = 1.2f;

	/** ThreatFrontlines: the least garrison a front link is held with, in fleet points, and the days between asks. */
	static final float MIN_GUARD_FP = 30f;
	static final int GUARD_RETRY_DAYS = 30;

	/** A forward base grows no further than this (the ledger's largest base). */
	static final int BASE_MAX_SIZE = 6;

	/** Preparation of a siege: IncursionManager's prepDays = 7 + 7 x rand. */
	static final float PREP_MIN_DAYS = 7f, PREP_SPAN_DAYS = 7f;

	/** A raid's planned stay over a hive (ThreatAttackPlanner.RaidOption: "raids Kanni 1075 FP", median task force 120 FP). */
	static final float RAID_STAY_DAYS = 10f;

	/**
	 * ThreatCoalition.partners: mobilised factions that would help each other by vanilla's standing at a new game.
	 * The council runs' pictures name only these as allies: "weight 24 + allies 4 (luddic_path)" (luddic_church,
	 * pd4a-pd8a) and "weight 19 + allies 49 (persean)" (sindrian_diktat, pd5a, pd6a, pd8a); every other faction's
	 * picture has none (66 of 280 pictures with allies in pd4a, 54 of 338 in pd8a).
	 */
	static final String[][] COALITION_PAIRS = { { "luddic_church", "luddic_path" }, { "persean", "sindrian_diktat" } };

	/**
	 * A start state with factions at war but no intel in the dump: every hive standing is taken
	 * as found and reported as of the start day. A default, not a fit.
	 */
	static final boolean START_KNOWS_HIVES = true;
}
