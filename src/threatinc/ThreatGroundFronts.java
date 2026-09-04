package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;

/**
 * Persistent ground fronts - the siege mechanic (docs/ground-war.md).
 *
 * <p>A front is marines + heavy armaments committed to a hive world's surface.
 * The colony is the fortress: a size-S hive is S strata deep with the
 * Fabrication Core at the center. The front holds ground, and - only when
 * ORDERED to push (player authority; NPC fronts run a simple stance AI) -
 * assaults the next stratum. Strata held subtract their share of the
 * size-anchored base defense (SwarmNexus) and of the colony's fabrication
 * (computeFabricationMult): each stratum taken weakens the hive. Taking the
 * final stratum destroys the Core and ERADICATES the colony - ground victory
 * is the only way a hive dies; starvation and bombardment only make it
 * cheaper. The hive counter-attacks on a cadence paced by its vitality and
 * can retake strata from a front too weak to hold them.
 *
 * <p>Everything ticks at flat daily rates on the colony poll: armaments burn
 * as upkeep (the stockpile IS the supply countdown, and a dry front fights at
 * reduced effectiveness), marines attrit (worse while pushing or dry), and a
 * strong enough front additionally SUPPRESSES structures by feeding their
 * disruption clocks - the mod's existing wear mechanic.
 *
 * <p>Stateless like ThreatColonyManager - all state lives in persistent data
 * via {@link #fronts()}; IncursionManager drives {@link #poll}.
 */
public class ThreatGroundFronts {

	public static final String KEY_FRONTS = "threatinc_groundFronts";

	/** Market memory flag: saturation fallout, blocks landing ground forces. */
	public static final String FALLOUT_FLAG = "$threatinc_fallout";

	/** Strong enough to suppress every key structure - the siege proper. */
	public static final String STATE_HOLDING = "holding";
	/** Only strong enough to harass the defense structures (reduced rate). */
	public static final String STATE_GRINDING = "grinding";
	/** Dug in and surviving, but suppressing nothing. */
	public static final String STATE_FOOTHOLD = "foothold";

	/** Default stance: dig in, defend what is held. */
	public static final String STANCE_ENTRENCH = "entrench";
	/** Assault the next stratum - ordered explicitly, reverts on completion. */
	public static final String STANCE_PUSH = "push";
	/**
	 * The checkpoint after each stratum falls: the front consolidates, requests
	 * reinforcement, and waits frontCheckpointDays for orders. Told nothing, it
	 * pushes on by doctrine; ordered to entrench it stands fast until told
	 * otherwise; or it can be pulled out entirely (which requires controlling
	 * the space around the planet - the dock gating enforces that physically).
	 */
	public static final String STANCE_CONSOLIDATE = "consolidate";

	/** One deployed front. Serialized into the save via persistent data. */
	public static class GroundFront {
		public String marketId;
		/** Owner faction id; Factions.PLAYER for the player's own front. */
		public String factionId;
		public float marines;
		public float armaments;
		public float entrenchDays;
		public String state = STATE_FOOTHOLD;
		public String stance = STANCE_ENTRENCH;
		/** Strata taken, of the colony's size; the Core falls with the last. */
		public int strataHeld;
		/** Days of push progress toward the next stratum (at ratio-scaled pace). */
		public float pushProgress;
		/** Checkpoint countdown while consolidating; at 0 the front pushes on. */
		public float consolidateDaysLeft;
		public long deployedTimestamp;
		public long lastCounterAttack;
		/** Last state announced, so transitions message exactly once. */
		public String announcedState;
		/** Whether the out-of-supply message has fired for the current dry spell. */
		public boolean announcedDry;
		/** Peak marine strength (landing plus reinforcements) - what supply runs reinforce toward. */
		public float marinesLanded;
		/** The front has asked to be pulled out (NPC stance AI, or the board's Pull out order). */
		public boolean withdrawRequested;

		public boolean isPlayerOwned() {
			return factionId == null || Factions.PLAYER.equals(factionId);
		}
	}

	@SuppressWarnings("unchecked")
	public static Map<String, GroundFront> fronts() {
		Object val = Global.getSector().getPersistentData().get(KEY_FRONTS);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, GroundFront>();
			Global.getSector().getPersistentData().put(KEY_FRONTS, val);
		}
		return (Map<String, GroundFront>) val;
	}

	public static GroundFront getFront(String marketId) {
		if (marketId == null) return null;
		return fronts().get(marketId);
	}

	public static boolean hasFront(MarketAPI market) {
		return market != null && getFront(market.getId()) != null;
	}

	/** Strata the front on this market holds; 0 when no front. */
	public static int strataHeld(String marketId) {
		GroundFront front = getFront(marketId);
		return front != null ? front.strataHeld : 0;
	}

	// ------------------------------------------------------------------
	// deployment lifecycle (player via ThreatincMarketCMD, NPC via ThreatPurgeFGI)
	// ------------------------------------------------------------------

	public static GroundFront deploy(MarketAPI market, String factionId, int marines,
			float armaments) {
		GroundFront front = new GroundFront();
		front.marketId = market.getId();
		front.factionId = factionId;
		front.marines = marines;
		front.armaments = armaments;
		front.marinesLanded = marines;
		front.deployedTimestamp = Global.getSector().getClock().getTimestamp();
		front.lastCounterAttack = front.deployedTimestamp;
		fronts().put(market.getId(), front);
		ThreatIncConfig.log("Front deployed at " + market.getName() + " (" + factionId
				+ "): " + marines + " marines, " + (int) armaments + " armaments");
		return front;
	}

	public static void resupply(GroundFront front, int marines, float armaments) {
		if (front == null) return;
		front.marines += marines;
		front.armaments += armaments;
		front.marinesLanded = Math.max(front.marinesLanded, front.marines);
		front.announcedDry = false;
		front.withdrawRequested = false;
	}

	/** Peak strength, falling back to the current figure for fronts from older saves. */
	public static float landedStrength(GroundFront front) {
		if (front == null) return 0f;
		return Math.max(front.marinesLanded, front.marines);
	}

	/** Removes the front; returns [marines, armaments] recovered. */
	public static int[] withdraw(String marketId) {
		GroundFront front = fronts().remove(marketId);
		if (front == null) return new int[] {0, 0};
		reapply(marketId);
		return new int[] {Math.round(front.marines), (int) Math.floor(front.armaments)};
	}

	/** Total loss - the saturation-bombardment case. */
	public static void destroy(String marketId) {
		fronts().remove(marketId);
		reapply(marketId);
	}

	/** Strata effects live in industry apply(); recompute after they change. */
	protected static void reapply(String marketId) {
		MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
		if (market != null) market.reapplyIndustries();
	}

	/** Orders the assault on the next stratum (also answers a checkpoint). */
	public static void orderPush(GroundFront front) {
		if (front == null || STANCE_PUSH.equals(front.stance)) return;
		front.stance = STANCE_PUSH;
		front.pushProgress = 0f;
		front.consolidateDaysLeft = 0f;
	}

	/** Breaks off the assault (or answers a checkpoint) and digs in. */
	public static void orderEntrench(GroundFront front) {
		if (front == null || STANCE_ENTRENCH.equals(front.stance)) return;
		front.stance = STANCE_ENTRENCH;
		front.pushProgress = 0f;
		front.consolidateDaysLeft = 0f;
	}

	/** Whether saturation fallout currently blocks landings, and for how long. */
	public static float falloutDaysLeft(MarketAPI market) {
		if (market == null) return 0f;
		if (!market.getMemoryWithoutUpdate().getBoolean(FALLOUT_FLAG)) return 0f;
		return Math.max(0f, market.getMemoryWithoutUpdate().getExpire(FALLOUT_FLAG));
	}

	public static void setFallout(MarketAPI market) {
		float days = ThreatIncConfig.falloutDays();
		if (days <= 0f || market == null) return;
		market.getMemoryWithoutUpdate().set(FALLOUT_FLAG, true, days);
	}

	// ------------------------------------------------------------------
	// derived figures (shared by the tick, the dialog and the board)
	// ------------------------------------------------------------------

	/**
	 * Marines x the entrenchment ramp x the supply factor. A dry front fights
	 * at frontDryEffectivenessMult - "not enough heavy armaments" is exactly
	 * the moment to resupply before ordering a push.
	 */
	public static float effectiveStrength(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		float mult = 1f + (ThreatIncConfig.frontEntrenchMaxMult() - 1f)
				* Math.min(1f, front.entrenchDays / days);
		if (front.armaments <= 0f) mult *= ThreatIncConfig.frontDryEffectivenessMult();
		return front.marines * mult;
	}

	/** Heavy armaments the front burns per day at this colony's size. */
	public static float dailyUpkeep(MarketAPI market) {
		if (market == null) return 0f;
		return ThreatIncConfig.frontArmamentsPerSizePer30Days() * market.getSize() / 30f;
	}

	/** Days the armaments stock lasts at the current burn rate (999+ = ample). */
	public static float supplyDaysLeft(GroundFront front, MarketAPI market) {
		float upkeep = dailyUpkeep(market);
		if (front == null) return 0f;
		if (STANCE_PUSH.equals(front.stance)) upkeep *= ThreatIncConfig.frontPushUpkeepMult();
		if (upkeep <= 0f) return 999f;
		return front.armaments / upkeep;
	}

	/** Effective strength needed to HOLD (suppress everything) here right now. */
	public static float holdRequirement(MarketAPI market) {
		return MarketCMD.getDefenderStr(market, true) * ThreatIncConfig.frontHoldFraction();
	}

	/** Effective strength needed to GRIND (harass the defense structures). */
	public static float grindRequirement(MarketAPI market) {
		return MarketCMD.getDefenderStr(market, true) * ThreatIncConfig.frontGrindFraction();
	}

	/**
	 * Push pace: days to take the next stratum at the CURRENT strength ratio.
	 * Base days scaled by defense-over-strength, clamped so an overwhelming
	 * front still fights for each stratum and a marginal one crawls rather
	 * than stalls. As held strata strip base defense away, later pushes
	 * genuinely get faster - momentum is real.
	 */
	public static float pushDaysEstimate(GroundFront front, MarketAPI market) {
		float eff = effectiveStrength(front);
		if (eff <= 0f) return -1f;
		float defender = Math.max(1f, MarketCMD.getDefenderStr(market, true));
		return ThreatIncConfig.frontPushBaseDays() * paceRatio(defender, eff);
	}

	/**
	 * Every strength RATIO the ground war uses goes through
	 * groundStrengthExponent first (docs/design-theory.md 8.4): at 1.0 the
	 * resolution is linear and legible; above it, concentration pays the way
	 * Lanchester's square law says it should. Deterministic, so the dialog's
	 * quotes stay exact.
	 */
	public static float ratioPow(float ratio) {
		float e = ThreatIncConfig.groundStrengthExponent();
		if (ratio <= 0f) return 0f;
		if (e == 1f) return ratio;
		return (float) Math.pow(ratio, e);
	}

	/** Defense-over-strength, exponent applied, clamped 0.5x-3x: the push-pace and push-attrition factor. */
	public static float paceRatio(float defender, float eff) {
		float ratio = ratioPow(Math.max(1f, defender) / Math.max(1f, eff));
		if (ratio < 0.5f) ratio = 0.5f;
		if (ratio > 3f) ratio = 3f;
		return ratio;
	}

	/** Attrition multiplier while pushing: 1 at exponent 1; the weaker side bleeds proportionally more above it. */
	public static float pushLossFactor(float defender, float eff) {
		float e = ThreatIncConfig.groundStrengthExponent();
		if (e == 1f) return 1f;
		float ratio = Math.max(1f, defender) / Math.max(1f, eff);
		if (ratio < 0.5f) ratio = 0.5f;
		if (ratio > 3f) ratio = 3f;
		return (float) Math.pow(ratio, e - 1f);
	}

	/** Estimated marines a push on the next stratum costs at current strength. */
	public static int pushCasualtyEstimate(GroundFront front, MarketAPI market) {
		float days = pushDaysEstimate(front, market);
		if (days <= 0f) return 0;
		float defender = MarketCMD.getDefenderStr(market, true);
		return Math.round(front.marines
				* ThreatIncConfig.frontPushLossPer30Days() / 30f * days
				* pushLossFactor(defender, effectiveStrength(front)));
	}

	/**
	 * The hive's counter-attack strength: its current (worn) ground-defense
	 * figure. Structures suppressed, strata taken, and input deficits all
	 * already flow into it, so a ground-down hive hits softly.
	 */
	public static float counterAttackStrength(MarketAPI market) {
		return MarketCMD.getDefenderStr(market, true);
	}

	/** What the front defends with: dug-in fronts fight from cover (a pushing
	 * front is exposed; consolidating counts as dug in). */
	public static float defenseStrength(GroundFront front) {
		float eff = effectiveStrength(front);
		if (!STANCE_PUSH.equals(front.stance)) {
			eff *= ThreatIncConfig.frontEntrenchDefenseBonus();
		}
		return eff;
	}

	/**
	 * Live Defense Swarms at the colony = the orbit is contested. The dialog's
	 * own raid gating already blocks docking while they will fight, so this is
	 * a readout (board, dialog), not an extra rule.
	 */
	public static boolean orbitContested(String marketId) {
		return ThreatColonyManager.countLiveGarrison(marketId) > 0;
	}

	// ------------------------------------------------------------------
	// the tick
	// ------------------------------------------------------------------

	/**
	 * Driven from IncursionManager's colony poll (~half a day). Deliberately
	 * NOT gated on frontsEnabled: the flag gates new deployments only, so a
	 * front deployed before the setting was turned off keeps ticking (and
	 * stays withdrawable) instead of freezing with marines stranded on it.
	 */
	public static void poll(float elapsedDays) {
		if (elapsedDays <= 0f) return;
		Map<String, GroundFront> fronts = fronts();
		if (fronts.isEmpty()) return;
		for (String marketId : new ArrayList<String>(fronts.keySet())) {
			GroundFront front = fronts.get(marketId);
			if (front == null) {
				fronts.remove(marketId);
				continue;
			}
			MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
			if (market == null) {
				evacuate(front);
				fronts.remove(marketId);
				continue;
			}
			tickFront(front, market, elapsedDays);
		}
	}

	protected static void tickFront(GroundFront front, MarketAPI market, float elapsedDays) {
		boolean pushing = STANCE_PUSH.equals(front.stance);

		// upkeep: the stockpile is the supply countdown; assaults burn more
		boolean supplied = front.armaments > 0f;
		float upkeep = dailyUpkeep(market);
		if (pushing) upkeep *= ThreatIncConfig.frontPushUpkeepMult();
		front.armaments = Math.max(0f, front.armaments - upkeep * elapsedDays);
		if (supplied && front.armaments <= 0f && !front.announcedDry) {
			front.announcedDry = true;
			ThreatColonyManager.announceAlways("The ground forces on " + market.getName()
					+ " have exhausted their heavy armaments - they fight at reduced "
					+ "effectiveness and casualties will mount until they are resupplied "
					+ "or withdrawn.", Misc.getNegativeHighlightColor());
		}

		// attrition: flat fraction per 30 days; pushing bleeds harder, and a
		// dry front harder still
		float lossRate = (pushing ? ThreatIncConfig.frontPushLossPer30Days()
				: ThreatIncConfig.frontMarineLossPer30Days()) / 30f;
		if (!supplied) lossRate *= ThreatIncConfig.frontUnsuppliedLossMult();
		if (pushing) {
			// exponent > 1: an outmatched assault bleeds more than a flat rate
			lossRate *= pushLossFactor(MarketCMD.getDefenderStr(market, true),
					effectiveStrength(front));
		}
		front.marines -= front.marines * lossRate * elapsedDays;
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatColonyManager.announceAlways("The ground front on " + market.getName()
					+ " has collapsed - the surviving positions were overrun.",
					Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Front collapsed at " + market.getName());
			fronts().remove(front.marketId);
			reapply(front.marketId);
			return;
		}

		front.entrenchDays += elapsedDays;

		// suppression state from effective strength vs the CURRENT (worn,
		// strata-stripped) defense figure - a front that holds keeps lowering
		// its own requirement, which is the dig-in-and-grind loop
		float eff = effectiveStrength(front);
		float defender = MarketCMD.getDefenderStr(market, true);
		if (eff >= defender * ThreatIncConfig.frontHoldFraction()) {
			front.state = STATE_HOLDING;
		} else if (eff >= defender * ThreatIncConfig.frontGrindFraction()) {
			front.state = STATE_GRINDING;
		} else {
			front.state = STATE_FOOTHOLD;
		}

		// suppression: feed the disruption clocks. The clock counts down by
		// elapsed naturally, so a rate of 2.0 nets +1 day of clock per day
		// held - and the clock is the wear mechanic, so a held siege grinds
		// the defenses (and a disrupted Core's fabrication) toward nothing.
		float rate = ThreatIncConfig.frontSuppressDaysPerDay();
		boolean suppressedAny = false;
		if (STATE_HOLDING.equals(front.state)) {
			for (Industry ind : keyStructures(market)) {
				suppressedAny |= suppress(ind, rate * elapsedDays);
			}
		} else if (STATE_GRINDING.equals(front.state)) {
			float grindRate = rate * ThreatIncConfig.frontGrindSuppressMult();
			suppressedAny |= suppress(market.getIndustry(
					ThreatColonyManager.THREAT_GROUND_DEFENSES), grindRate * elapsedDays);
			suppressedAny |= suppress(market.getIndustry(
					ThreatColonyManager.THREAT_HEAVY_BATTERIES), grindRate * elapsedDays);
		}
		if (suppressedAny) market.reapplyIndustries();

		// the checkpoint: consolidating troops count down their orders window;
		// told nothing, doctrine says push on - or stand fast if too weak
		if (STANCE_CONSOLIDATE.equals(front.stance)) {
			front.consolidateDaysLeft -= elapsedDays;
			if (front.consolidateDaysLeft <= 0f) {
				front.consolidateDaysLeft = 0f;
				if (eff >= defender * ThreatIncConfig.frontHoldFraction()) {
					front.stance = STANCE_PUSH;
					front.pushProgress = 0f;
					if (front.isPlayerOwned()) {
						ThreatColonyManager.announceAlways("No new orders reached the "
								+ "front on " + market.getName() + " - it resumes the "
								+ "assault on stratum " + (front.strataHeld + 1) + ".",
								Misc.getHighlightColor());
					}
				} else {
					front.stance = STANCE_ENTRENCH;
					if (front.isPlayerOwned()) {
						ThreatColonyManager.announceAlways("The front on "
								+ market.getName() + " is too weak to resume the "
								+ "assault - it entrenches on what it holds, awaiting "
								+ "reinforcement.", Misc.getNegativeHighlightColor());
					}
				}
			}
		}

		// the assault on the next stratum - progress only while strong enough
		// to actually hold what it takes
		if (pushing && eff >= defender * ThreatIncConfig.frontHoldFraction()) {
			float ratio = paceRatio(defender, eff);
			front.pushProgress += elapsedDays / ratio;
			if (front.pushProgress >= ThreatIncConfig.frontPushBaseDays()) {
				takeStratum(front, market);
				if (getFront(front.marketId) == null) return; // core destroyed
			}
		}

		// the hive counter-attacks on a cadence paced by its vitality: a
		// starved hive cannot mount them - which is what strangling its
		// economy buys you now
		hiveCounterAttack(front, market);
		if (getFront(front.marketId) == null) return; // front overrun

		// NPC withdrawal call (docs/design-theory.md 8.3): dry AND too weak
		// even to grind, the campaign is lost - ask for a pickup run rather
		// than wither in place. A supply run that lands first clears the call.
		if (!front.isPlayerOwned() && !front.withdrawRequested && front.armaments <= 0f
				&& eff < defender * ThreatIncConfig.frontGrindFraction()) {
			front.withdrawRequested = true;
			ThreatIncConfig.log("Front at " + market.getName() + " (" + front.factionId
					+ ") requests withdrawal: dry and below grind strength");
		}

		// NPC stance AI: push whenever strong enough and supplied, dig in
		// otherwise. Consolidation paces them like everyone else; player
		// fronts push only when ordered (or by checkpoint doctrine above).
		if (!front.isPlayerOwned() && STANCE_ENTRENCH.equals(front.stance)
				&& front.armaments > 0f
				&& effectiveStrength(front) >= holdRequirement(market)) {
			orderPush(front);
		}

		announceStateChange(front, market);
	}

	/**
	 * A stratum falls; the last one holds the Fabrication Core. Below the Core
	 * the front CONSOLIDATES at the checkpoint: it requests reinforcement and
	 * resupply, waits frontCheckpointDays for orders, and - told nothing -
	 * pushes on by doctrine.
	 */
	protected static void takeStratum(GroundFront front, MarketAPI market) {
		front.pushProgress = 0f;
		front.strataHeld++;
		int total = market.getSize();
		ThreatAlarm.add(front.factionId != null ? front.factionId : Factions.PLAYER,
				ThreatIncConfig.alarmPerStratum(), "stratum taken at " + market.getName());
		if (front.strataHeld >= total) {
			groundVictory(front, market);
			return;
		}
		front.stance = STANCE_CONSOLIDATE;
		front.consolidateDaysLeft = ThreatIncConfig.frontCheckpointDays();
		reapply(front.marketId); // strata strip base defense and fabrication
		if (front.isPlayerOwned()) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " have taken stratum " + front.strataHeld + " of " + total
					+ " and are consolidating. They request reinforcement and "
					+ "resupply - without new orders they will push on in "
					+ (int) front.consolidateDaysLeft + " days.",
					Misc.getPositiveHighlightColor());
		} else {
			ThreatColonyManager.announceAlways("Expeditionary ground forces on "
					+ market.getName() + " have taken stratum " + front.strataHeld
					+ " of " + total + ".", Misc.getPositiveHighlightColor());
		}
		ThreatIncConfig.log("Front took stratum " + front.strataHeld + "/" + total
				+ " at " + market.getName());
	}

	/**
	 * The final stratum is taken and the Fabrication Core destroyed: the
	 * colony is ERADICATED - the only way a hive dies. Player survivors are
	 * lifted off; the vanilla teardown runs and pollColonies reacts next poll.
	 */
	protected static void groundVictory(GroundFront front, MarketAPI market) {
		fronts().remove(front.marketId);
		ThreatColonyManager.announceAlways("The Fabrication Core of " + market.getName()
				+ " has been destroyed - the hive is eradicated. The strata are cold.",
				Misc.getPositiveHighlightColor());
		ThreatIncConfig.log("Ground victory at " + market.getName());
		if (front.isPlayerOwned()) evacuate(front);
		String winner = front.factionId != null ? front.factionId : Factions.PLAYER;
		StarSystemAPI where = market.getStarSystem();
		ThreatColonyManager.eradicate(market);
		// the swarm answers (docs/design-theory.md 8.1): grudge, and a strike
		// at the winner from the nearest hive that can muster one
		ThreatAlarm.add(winner, ThreatIncConfig.alarmPerEradication(),
				"eradication of " + market.getName());
		IncursionManager.retaliate(winner, where);
	}

	/**
	 * Strength contest on the counter-attack cadence: the hive's current
	 * defense figure against the front's (entrenchment-boosted) strength.
	 * Losing costs marines and a held stratum; a beachhead beaten twice over
	 * is destroyed outright.
	 */
	protected static void hiveCounterAttack(GroundFront front, MarketAPI market) {
		float health = Math.max(0.25f, ThreatColonyManager.computeHealth(market));
		float interval = ThreatIncConfig.frontCounterAttackDays() / health;
		float since = Global.getSector().getClock()
				.getElapsedDaysSince(front.lastCounterAttack);
		if (since < interval) return;
		front.lastCounterAttack = Global.getSector().getClock().getTimestamp();

		float attack = counterAttackStrength(market);
		float defense = defenseStrength(front);
		if (attack <= defense) {
			ThreatIncConfig.log("Counter-attack repelled at " + market.getName()
					+ " (" + (int) attack + " vs " + (int) defense + ")");
			return;
		}

		// exponent > 1: a counter-attack that outmatches the front badly costs
		// it more than the flat fraction, and overruns a beachhead sooner
		float odds = ratioPow(attack / Math.max(1f, defense));
		float loss = front.marines * ThreatIncConfig.frontCounterAttackLossFraction()
				* Math.min(2f, (float) Math.pow(Math.max(1f, odds),
						Math.max(0f, ThreatIncConfig.groundStrengthExponent() - 1f)));
		front.marines = Math.max(0f, front.marines - loss);

		if (front.strataHeld > 0) {
			front.strataHeld--;
			front.pushProgress = 0f;
			front.stance = STANCE_ENTRENCH;
			reapply(front.marketId);
			ThreatColonyManager.announceAlways("A hive counter-attack on " + market.getName()
					+ " has retaken a stratum - " + front.strataHeld + " of "
					+ market.getSize() + " still held, " + Math.round(loss)
					+ " marines lost.", Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " retook a stratum ("
					+ (int) attack + " vs " + (int) defense + "): " + front.strataHeld
					+ " held, " + Math.round(loss) + " marines lost");
		} else if (odds > 2f) {
			ThreatColonyManager.announceAlways("A hive counter-attack has overrun the "
					+ "beachhead on " + market.getName() + " - the front is destroyed.",
					Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " overran the beachhead ("
					+ (int) attack + " vs " + (int) defense + ")");
			fronts().remove(front.marketId);
			reapply(front.marketId);
			return;
		} else {
			ThreatColonyManager.announceAlways("A hive counter-attack battered the "
					+ "beachhead on " + market.getName() + " - " + Math.round(loss)
					+ " marines lost.", Misc.getNegativeHighlightColor());
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " battered the beachhead ("
					+ (int) attack + " vs " + (int) defense + "): " + Math.round(loss)
					+ " marines lost");
		}
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatColonyManager.announceAlways("The ground front on " + market.getName()
					+ " has collapsed - the surviving positions were overrun.",
					Misc.getNegativeHighlightColor());
			fronts().remove(front.marketId);
			reapply(front.marketId);
		}
	}

	/** Core, Nexus, port and both defense structures - whatever exists here. */
	protected static java.util.List<Industry> keyStructures(MarketAPI market) {
		java.util.List<Industry> list = new ArrayList<Industry>();
		Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
		if (core != null) list.add(core);
		Industry nexus = market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
		if (nexus != null) list.add(nexus);
		Industry port = ThreatColonyManager.getPort(market);
		if (port != null) list.add(port);
		Industry gd = market.getIndustry(ThreatColonyManager.THREAT_GROUND_DEFENSES);
		if (gd != null) list.add(gd);
		Industry hb = market.getIndustry(ThreatColonyManager.THREAT_HEAVY_BATTERIES);
		if (hb != null) list.add(hb);
		return list;
	}

	/**
	 * Adds days to a structure's disruption clock, capped so the figure stays
	 * sane (wear maxes out at defenseWearDays anyway). Returns whether the
	 * structure was touched.
	 */
	protected static boolean suppress(Industry ind, float addDays) {
		if (ind == null || addDays <= 0f) return false;
		float cap = Math.max(360f, ThreatIncConfig.defenseWearDays() * 1.2f);
		float cur = ind.getDisruptedDays();
		if (cur >= cap) return true; // already pinned at the cap - still "suppressed"
		ind.setDisrupted(Math.min(cap, cur + addDays));
		return true;
	}

	protected static void announceStateChange(GroundFront front, MarketAPI market) {
		if (front.state.equals(front.announcedState)) return;
		String was = front.announcedState;
		front.announcedState = front.state;
		// no message for the very first classification unless it's a hold -
		// the deploy dialog already told the player where they stand
		if (was == null && !STATE_HOLDING.equals(front.state)) return;
		if (!front.isPlayerOwned()) return; // NPC campaigns message on strata only
		if (STATE_HOLDING.equals(front.state)) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " are holding - the hive's organs are suppressed and a push "
					+ "on the next stratum is possible.", Misc.getPositiveHighlightColor());
		} else if (STATE_GRINDING.equals(front.state)) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " have been pushed back to grinding the outer defenses - "
					+ "too weak to hold the deep organs.", Misc.getHighlightColor());
		} else {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " are reduced to a foothold - too weak to suppress anything.",
					Misc.getNegativeHighlightColor());
		}
	}

	/**
	 * The colony died under the front. There is no market left to dock with,
	 * and stranding the survivors punishes winning: they are lifted off by
	 * their own support elements and returned to the player fleet. NPC front
	 * survivors simply leave with their expedition.
	 */
	protected static void evacuate(GroundFront front) {
		if (!front.isPlayerOwned()) return;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		int marines = Math.round(front.marines);
		int armaments = (int) Math.floor(front.armaments);
		if (player != null) {
			if (marines > 0) player.getCargo().addMarines(marines);
			if (armaments > 0) {
				player.getCargo().addCommodity(Commodities.HAND_WEAPONS, armaments);
			}
		}
		ThreatColonyManager.announceAlways("The ground forces have lifted off - "
				+ marines + " marines rejoin the fleet.",
				Misc.getPositiveHighlightColor());
		ThreatIncConfig.log("Front evacuated: " + front.marketId);
	}
}
