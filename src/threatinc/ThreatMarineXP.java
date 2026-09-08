package threatinc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.PlayerFleetPersonnelTracker;
import com.fs.starfarer.api.impl.PlayerFleetPersonnelTracker.PersonnelData;
import com.fs.starfarer.api.impl.PlayerFleetPersonnelTracker.PersonnelRank;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * Marine veterancy, on vanilla's own shape (2026-09-08).
 *
 * <p>Vanilla tracks one XP pool for the player fleet's marines
 * ({@link PlayerFleetPersonnelTracker}): an absolute {@code xp} clamped to the
 * headcount, so {@code level = xp / num} in [0, 1], and the rank name is only a
 * label on that ramp - Regular below 0.25, Experienced below 0.5, Veteran below
 * 0.75, Elite above. The effects are continuous, not stepped. This class holds
 * the same model for the two pools vanilla has no opinion about: a ground
 * front's marines, and a colony's stockpiled marines.
 *
 * <p>Three rules are carried over from vanilla deliberately:
 * <ul>
 * <li><b>A hard fight teaches; a curbstomp teaches nothing.</b> XP is
 *     {@code (1 - effectiveness) x headcount x mult}, so the side that was
 *     outmatched learns the most ({@link #xpGain}).
 * <li><b>Losses preserve the level.</b> Casualties scale the pool down in
 *     proportion; they do not promote the survivors ({@link #scaleForLosses}).
 * <li><b>Reinforcement dilutes.</b> Nothing to do - the headcount rises while
 *     the pool does not, so the level falls out of the arithmetic for free.
 *     This is what stops a mass of raw marines being worth its headcount.
 * </ul>
 *
 * <p>The rank NAME comes from vanilla's own enum, so a label here always reads
 * the same as the one on the player's cargo stack.
 *
 * <p>Hives have no marines and no veterancy: hive strength is structural
 * (strata x structures) and erodes that way instead. Everything here is the
 * COLONY theatre and the fronts standing on either kind of world.
 */
public class ThreatMarineXP {

	/** Vanilla's ramp: level 1.0 is a fully elite pool. */
	public static final float MAX_LEVEL = 1f;

	/**
	 * The source id vanilla's own tracker writes its marine-veterancy percent
	 * under, on the shared PLANETARY_OPERATIONS_MOD StatBonus
	 * ({@code PlayerFleetPersonnelTracker.update}, {@code String id = "marineXP"}).
	 * Needed so {@link #playerGroundSkillMult} can read the SKILLS alone.
	 */
	public static final String VANILLA_MARINE_XP_SOURCE = "marineXP";

	// ------------------------------------------------------------------
	// the ramp
	// ------------------------------------------------------------------

	/** Vanilla's PersonnelData.getXPLevel: the pool over the headcount, clamped. */
	public static float level(float xp, float num) {
		if (num <= 0f || xp <= 0f) return 0f;
		float f = xp / Math.max(1f, num);
		if (f < 0f) f = 0f;
		if (f > MAX_LEVEL) f = MAX_LEVEL;
		return f;
	}

	/**
	 * Vanilla's own name for this level - Regular / Experienced / Veteran /
	 * Elite - for every pool, the swarm's included.
	 *
	 * <p>A swarm-specific ladder (Fresh / Adapting / Adapted / Apex) was tried
	 * on 2026-09-08 and REVERTED the same evening: "fresh troops" reads as
	 * rested rather than inexperienced, which is the opposite of what the
	 * bottom rung means (user). One scale, read the same way everywhere, beats
	 * a second vocabulary that has to be learned.
	 */
	public static String rankName(float level) {
		try {
			return PersonnelRank.getRankForXP(level).name;
		} catch (Throwable t) {
			return "Regular"; // vanilla's floor tier, not "Green" - there is no green marine
		}
	}

	/** Strength multiplier of a pool at this level: 1.0 raw, up to 1 + marineVeterancyEffectMax. */
	public static float effectMult(float level) {
		return 1f + level * Math.max(0f, ThreatIncConfig.marineVeterancyEffectMax());
	}

	/** Casualty multiplier of a pool at this level: 1.0 raw, down to 1 - marineVeterancyLossReduction. */
	public static float lossMult(float level) {
		float cut = level * Math.max(0f, ThreatIncConfig.marineVeterancyLossReduction());
		return Math.max(0.05f, 1f - cut);
	}

	/** The two effects as whole percents, for the tooltips that quote them. */
	public static int effectPercent(float level) {
		return Math.round(level * Math.max(0f, ThreatIncConfig.marineVeterancyEffectMax()) * 100f);
	}

	public static int lossPercent(float level) {
		return Math.round(level * Math.max(0f, ThreatIncConfig.marineVeterancyLossReduction()) * 100f);
	}

	// ------------------------------------------------------------------
	// earning and losing
	// ------------------------------------------------------------------

	/**
	 * XP a body of this size earns from one action, on vanilla's shape: what it
	 * learns scales with how badly it was outmatched, so a side that wins
	 * easily learns nothing. {@code own} and {@code enemy} are the two strength
	 * figures the action was actually resolved on.
	 */
	public static float xpGain(float own, float enemy, float num) {
		if (num <= 0f) return 0f;
		float mine = Math.max(0f, own);
		float theirs = Math.max(0f, enemy);
		float effectiveness = mine / Math.max(1f, mine + theirs);
		float gain = (1f - effectiveness) * num * Math.max(0f, ThreatIncConfig.marineXpPerBattle());
		return Math.max(0f, gain);
	}

	/** Vanilla's PersonnelData.addXP: add, then clamp to the headcount. */
	public static float addXp(float xp, float gain, float num) {
		float sum = Math.max(0f, xp) + Math.max(0f, gain);
		return Math.max(0f, Math.min(sum, Math.max(0f, num)));
	}

	/**
	 * Casualties preserve the level: the pool scales down with the headcount
	 * (vanilla's removeXP path). Survivors are not promoted for surviving -
	 * that is what {@link #xpGain} is for.
	 */
	public static float scaleForLosses(float xp, float oldNum, float newNum) {
		if (xp <= 0f || oldNum <= 0f || newNum <= 0f) return 0f;
		if (newNum >= oldNum) return Math.min(xp, newNum);
		return Math.min(xp * (newNum / Math.max(1f, oldNum)), newNum);
	}

	// ------------------------------------------------------------------
	// a front's pool
	// ------------------------------------------------------------------

	public static float frontLevel(ThreatGroundFronts.GroundFront front) {
		if (front == null) return 0f;
		return level(front.xp, front.marines);
	}

	public static String frontRank(ThreatGroundFronts.GroundFront front) {
		return rankName(frontLevel(front));
	}

	/** The front's strength multiplier from experience. */
	public static float frontEffectMult(ThreatGroundFronts.GroundFront front) {
		return effectMult(frontLevel(front));
	}

	/** The front's casualty multiplier from experience. */
	public static float frontLossMult(ThreatGroundFronts.GroundFront front) {
		return lossMult(frontLevel(front));
	}

	/** Grants the front XP for an action resolved at these two strengths. */
	public static void frontEarn(ThreatGroundFronts.GroundFront front, float own, float enemy) {
		if (front == null || front.marines <= 0f) return;
		front.xp = addXp(front.xp, xpGain(own, enemy, front.marines), front.marines);
	}

	/**
	 * Applies casualties to a front, keeping its level. Call this instead of
	 * writing {@code front.marines} directly, or the pool is left dangling
	 * above the headcount and the survivors read as better than they are.
	 */
	public static void frontLose(ThreatGroundFronts.GroundFront front, float loss) {
		if (front == null || loss <= 0f) return;
		float before = front.marines;
		float after = Math.max(0f, before - loss);
		front.marines = after;
		front.xp = scaleForLosses(front.xp, before, after);
	}

	// ------------------------------------------------------------------
	// a colony's pool
	// ------------------------------------------------------------------

	public static float colonyLevel(MarketAPI market) {
		if (market == null) return 0f;
		return level(ThreatReserves.marineXp(market), ThreatReserves.armedMarines(market));
	}

	public static String colonyRank(MarketAPI market) {
		return rankName(colonyLevel(market));
	}

	/** Grants the colony's armed marines XP for an action resolved at these two strengths. */
	public static void colonyEarn(MarketAPI market, float own, float enemy) {
		if (market == null) return;
		float num = ThreatReserves.armedMarines(market);
		if (num <= 0f) return;
		ThreatReserves.addMarineXp(market, xpGain(own, enemy, num));
	}

	// ------------------------------------------------------------------
	// the player fleet: vanilla's pool, read and written
	// ------------------------------------------------------------------

	/**
	 * The player fleet's current marine level. Vanilla only refreshes its
	 * headcount on player-facing events (cargo screen, market, transaction,
	 * raid), so this asks for an update first - as vanilla's own MiscCMD and
	 * MercsOnTheRun do before touching the pool.
	 */
	public static float fleetLevel() {
		if (!ThreatIncConfig.marineFleetXpTransfer()) return 0f;
		try {
			PlayerFleetPersonnelTracker tracker = PlayerFleetPersonnelTracker.getInstance();
			if (tracker == null) return 0f;
			tracker.update();
			PersonnelData data = tracker.getMarineData();
			return data == null ? 0f : data.getXPLevel();
		} catch (Throwable t) {
			return 0f; // no tracker (a very old save): the landing goes in green
		}
	}

	public static String fleetRank() {
		return rankName(fleetLevel());
	}

	/**
	 * Writes a returning body of marines back into the player fleet's pool at
	 * the level it earned in the field. The marines themselves must ALREADY be
	 * in the fleet's cargo: vanilla clamps the pool to the headcount, so the
	 * bodies have to be counted before their experience is.
	 *
	 * <p>Green survivors coming home therefore dilute a veteran fleet, and
	 * veterans coming home lift a green one. That is the same arithmetic
	 * vanilla applies when the player recruits.
	 */
	public static void fleetReturn(float marines, float level) {
		if (!ThreatIncConfig.marineFleetXpTransfer()) return;
		if (marines <= 0f || level <= 0f) return;
		try {
			PlayerFleetPersonnelTracker tracker = PlayerFleetPersonnelTracker.getInstance();
			if (tracker == null) return;
			tracker.update(); // count the bodies first, or addXP clamps them away
			PersonnelData data = tracker.getMarineData();
			if (data == null) return;
			data.addXP(marines * level);
		} catch (Throwable t) {
			// vanilla's tracker is not essential to the siege; never fail a withdrawal over it
			ThreatIncConfig.log("Marine XP: could not write back to the fleet pool (" + t + ")");
		}
	}

	/**
	 * Vanilla's ground-operations bonus for the player, as a multiplier - the
	 * Planetary Operations and Tactical Drills skills. Nothing in the
	 * ground-front engine read this before 2026-09-08, so a player with the
	 * skills fought exactly as well as one without.
	 *
	 * <p>SKILLS ONLY. Vanilla's own marine tracker writes the FLEET's current
	 * marine veterancy into this same StatBonus as a percent mod under the
	 * source "marineXP" ({@code PlayerFleetPersonnelTracker.update}), so
	 * reading the stat whole would count veterancy twice - once here and once
	 * through {@link #frontEffectMult} - and would make a landed front's
	 * strength swing with whatever marines are sitting in the hold back home
	 * (review, 2026-09-08). That term is subtracted out.
	 *
	 * <p>{@code computeEffective(1)} is {@code (1 + percentMod/100 + flatBonus)
	 * * mult}, so one additive percent term comes out as {@code value/100 x
	 * mult}. Calling computeEffective first forces the recompute that makes the
	 * public fields current.
	 */
	public static float playerGroundSkillMult() {
		if (!ThreatIncConfig.marineFleetXpTransfer()) return 1f;
		try {
			CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
			if (fleet == null) return 1f;
			StatBonus stat = fleet.getStats().getDynamic().getMod(Stats.PLANETARY_OPERATIONS_MOD);
			float total = stat.computeEffective(1f);
			MutableStat.StatMod marine = stat.getPercentBonus(VANILLA_MARINE_XP_SOURCE);
			float skillsOnly = marine == null ? total
					: total - marine.value / 100f * stat.mult;
			return skillsOnly <= 0f ? 1f : skillsOnly;
		} catch (Throwable t) {
			return 1f;
		}
	}
}
