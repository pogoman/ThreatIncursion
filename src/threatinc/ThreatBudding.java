package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * THE STRATA BUD, SHIFT AND ARE ABSORBED (docs/strata-are-the-threat.md, phase C, 2026-10-25). A hive
 * world keeps no standing garrison: it holds its strata on the ground and buds a fleet only to do one
 * thing. A world's flow is {@link #flowPerDay}: strataFlowFractionPerDay of its strata a day, at least
 * strataFlowPerDay. Each poll it moves at most that much one way:
 * <ul>
 * <li><b>Budding.</b> An order ({@link #KEY_KIND}: defend, shift) fixes a want in fleet points. Strata
 * leave the ground into the world's pool at the flow rate (the rising glows on the map); when the pool
 * holds the want the fleet is built whole ({@link ThreatFleetComposer#createSized}) and spawned over
 * the world as its Defend fleet, or sent to the receiver of a shift ({@link ThreatColonyManager#sendReinforcement}).
 * A pool is neither on the ground nor in orbit: a bombardment cannot raze it, a landing does not meet
 * it. A defence whose attacker leaves drains back at the flow rate; a defence of a doomed world spawns
 * short ({@link #doomed}).</li>
 * <li><b>Absorbing.</b> A fleet over its own hive world with no hostile holding the orbit for
 * defendLingerDays (a shift on arrival at once) is absorbed at the flow rate, smallest hulls first,
 * up to the world's cap ({@link ThreatStrata#room}); what the cap cannot take stands.</li>
 * <li><b>Strikes.</b> A strike's bill is budded into the staging colony's staged points
 * ({@link ThreatColonyManager#staged}) at EVERY giver in parallel, each at its own flow (the user,
 * 2026-10-30, "all the strata is just sitting idle"): the staging system's worlds straight into the
 * staged points, every other giver by a shift order flagged for the strike whose swarm flies in and
 * is staged whole on arrival ({@link #advanceStrike}, {@link #stageArrivals}); the offensive launches
 * it once staged (IncursionManager.launchStrike). The preparation is the budding.</li>
 * <li><b>Forward staging.</b> While the offensive saves for a campaign, the shift fills its prongs'
 * staging systems with the campaign's bill whatever the stance ({@link #shift},
 * ThreatOffensive.savingStagings), so the front holds the mass before the launch.</li>
 * </ul>
 * Triggers (section 6): any hostile weight holding ORBIT_HOLD_RANGE over a hive world
 * (ThreatGroundFronts.hostilePointsNear) - a bomber, a blockade, a landing's cover - calls the
 * posture's rally, which sends what stands spare and buds the rest ({@link #defend}); a landing alone
 * triggers nothing. SHIFT ({@link #shift}) replaces the pressure pass: an attacked world short of
 * enough, or under PRESS the front system and under CONSOLIDATE the densest, draws strata from quiet
 * worlds above shiftFloorStrata. Standing garrisons of an older save are Defend fleets of their world
 * and are absorbed. Knobs: strataFlowPerDay, strataFlowFractionPerDay, defendLingerDays.
 */
public class ThreatBudding {

	/** Market memory: fleet points in the world's budding pool. */
	public static final String KEY_POOL = "$threatinc_budPool";
	/** Market memory: fleet points the order wants. */
	public static final String KEY_WANT = "$threatinc_budWant";
	/** Market memory: the order's kind, {@link #DEFEND} or {@link #SHIFT}. */
	public static final String KEY_KIND = "$threatinc_budKind";
	/** Market memory: a shift's receiver market id. */
	public static final String KEY_TARGET = "$threatinc_budTarget";
	/** Market memory: the day the order was placed. */
	public static final String KEY_SINCE = "$threatinc_budSince";
	/** Market memory: the day a hostile last held the orbit. */
	public static final String KEY_QUIET = "$threatinc_contestedDay";
	/** Market memory: strata lost to the enemy (bombs, a front's attrition), accumulated by ThreatStrata.add. */
	public static final String KEY_LOSS = "$threatinc_strataLoss";
	/** Market memory: the accumulator as the last poll read it. */
	public static final String KEY_LOSS_SEEN = "$threatinc_strataLossSeen";
	/** Market memory: strata a day the world lost to the enemy over the last poll. */
	public static final String KEY_LOSS_RATE = "$threatinc_strataLossRate";
	/** Market memory: a strike order at the staging colony - its fleet points wanted. */
	public static final String KEY_STRIKE_WANT = "$threatinc_strikeBudWant";
	/** Market memory: the strike's target market id. */
	public static final String KEY_STRIKE_TARGET = "$threatinc_strikeBudTarget";
	/** Market memory: the day the strike order was last refreshed. */
	public static final String KEY_STRIKE_SINCE = "$threatinc_strikeBudSince";
	/** Fleet memory: the applyFleetLosses bank absorbing uses. */
	public static final String ABSORB_KEY = "$threatinc_absorbBank";
	/** Fleet memory: a shift's fleet is absorbed the moment it is seated, no linger. */
	public static final String ABSORB_ON_ARRIVAL = "$threatinc_absorbOnArrival";
	/** Market memory: a shift order budding a strike's share - the staging colony's market id. */
	public static final String KEY_FOR_STRIKE = "$threatinc_budForStrike";
	/** Fleet memory: a shift's fleet flying a strike's share - the staging colony's market id; staged whole on arrival. */
	public static final String FOR_STRIKE = "$threatinc_forStrike";

	public static final String DEFEND = "defend", SHIFT = "shift";

	/** Days a strike order stands unrefreshed before it lapses (the fund starved, the target gone). */
	public static final float STRIKE_ORDER_DAYS = 60f;

	// ------------------------------------------------------------------
	// the flow
	// ------------------------------------------------------------------

	/** Strata the world moves a day, either way: strataFlowFractionPerDay of its strata, at least strataFlowPerDay. */
	public static float flowPerDay(MarketAPI market) {
		if (market == null) return 0f;
		return Math.max(ThreatIncConfig.strataFlowPerDay(), ThreatIncConfig.strataFlowFractionPerDay() * ThreatStrata.of(market));
	}

	/** {@link #flowPerDay} in fleet points. */
	public static float flowFP(MarketAPI market) {
		return ThreatStrata.fpOf(flowPerDay(market));
	}

	/** Strata a day the system's worlds move together (a strike buds at every world of the staging system). */
	public static float flowPerDay(StarSystemAPI system) {
		float flow = 0f;
		if (system == null) return 0f;
		for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(system.getId())) flow += flowPerDay(c);
		return flow;
	}

	/**
	 * Fleet points the world can bud: its staged points and its strata above the floor - a stratum
	 * (sizeAt(1)) for its own defence, keepStrata (shiftFloorStrata, a critical producer's size) for
	 * a shift or a strike.
	 */
	public static float buddableFP(MarketAPI market, boolean forDefence) {
		if (market == null || market.getPrimaryEntity() == null) return 0f;
		float floor = forDefence ? ThreatStrata.sizeAt(1) : ThreatColonyManager.keepStrata(market);
		return ThreatColonyManager.staged(market.getId()) + ThreatStrata.fpOf(Math.max(0f, ThreatStrata.of(market) - floor));
	}

	/** Fleet points the system's worlds can bud for the defence of one of them (the only-if-enough rule's figure). */
	public static float buddableFP(StarSystemAPI system) {
		float fp = 0f;
		if (system == null) return 0f;
		for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			if (hasOrder(c)) continue;
			fp += buddableFP(c, true);
		}
		return fp;
	}

	// ------------------------------------------------------------------
	// orders
	// ------------------------------------------------------------------

	public static boolean hasOrder(MarketAPI market) {
		return market != null && market.getMemoryWithoutUpdate().contains(KEY_KIND);
	}

	public static String kind(MarketAPI market) {
		return market != null ? market.getMemoryWithoutUpdate().getString(KEY_KIND) : null;
	}

	public static float pool(MarketAPI market) {
		return market != null ? Math.max(0f, market.getMemoryWithoutUpdate().getFloat(KEY_POOL)) : 0f;
	}

	public static float want(MarketAPI market) {
		return market != null ? Math.max(0f, market.getMemoryWithoutUpdate().getFloat(KEY_WANT)) : 0f;
	}

	/** Fleet points budding toward the world: its own defence order's remainder and every shift ordered to it. */
	public static float pendingFP(MarketAPI world) {
		if (world == null) return 0f;
		float fp = 0f;
		if (DEFEND.equals(kind(world))) fp += want(world);
		for (MarketAPI c : ThreatIncData.getAllLiveColonyMarkets()) {
			if (c == world || !SHIFT.equals(kind(c))) continue;
			if (world.getId().equals(c.getMemoryWithoutUpdate().getString(KEY_TARGET))) fp += want(c);
		}
		return fp;
	}

	/** Places (or raises) an order at the world: {@code wantFP} more than it already holds in its pool. */
	protected static void order(MarketAPI market, String kind, float wantFP, MarketAPI target, String why) {
		if (market == null || wantFP <= 0f) return;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float pool = pool(market);
		float want = Math.max(want(market), pool + wantFP);
		boolean fresh = !mem.contains(KEY_KIND);
		mem.set(KEY_KIND, kind);
		mem.set(KEY_WANT, want);
		if (target != null) mem.set(KEY_TARGET, target.getId());
		else mem.unset(KEY_TARGET);
		if (fresh) mem.set(KEY_SINCE, ThreatPosture.today());
		if (fresh || want > pool + wantFP + 0.5f) {
			ThreatIncConfig.log("Budding: " + market.getName() + " buds " + (int) want + " FP (" + ThreatStrata.fmt(ThreatStrata.strataOf(want))
					+ " strata) to " + kind + (target != null ? " to " + target.getName() : "") + ", " + (int) Math.ceil(ThreatStrata.strataOf(want - pool) / Math.max(1f, flowPerDay(market)))
					+ " d at " + (int) flowPerDay(market) + " a day" + (why != null ? "; " + why : ""));
		}
	}

	protected static void clear(MarketAPI market) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		mem.unset(KEY_KIND);
		mem.unset(KEY_WANT);
		mem.unset(KEY_TARGET);
		mem.unset(KEY_SINCE);
		mem.unset(KEY_POOL);
		mem.unset(KEY_FOR_STRIKE);
	}

	/**
	 * The posture's rally found the world short (ThreatPosture.rally, after what stood spare was
	 * sent): the world buds its own strata toward the want, its system's quiet siblings shift the
	 * rest to it. Only if the system can match the force ({@code attackFP}) at all - the rally
	 * checked that with {@link #buddableFP(StarSystemAPI)} in its gather. Returns the fleet points ordered.
	 */
	public static float defend(MarketAPI world, float attackFP, float haveFP, float wantFP) {
		if (world == null || world.getStarSystem() == null || attackFP <= 0f) return 0f;
		if (wantFP >= Float.MAX_VALUE) wantFP = attackFP;
		float need = wantFP - haveFP - pendingFP(world);
		if (need <= 0f) return 0f;
		float ordered = 0f;
		float own = Math.min(need, buddableFP(world, true) - (DEFEND.equals(kind(world)) ? want(world) - pool(world) : 0f));
		if (own > 0f && !SHIFT.equals(kind(world))) {
			order(world, DEFEND, own, null, (int) attackFP + " FP over it, " + (int) haveFP + " FP standing");
			ordered += own;
		}
		float rest = need - Math.max(0f, own);
		if (rest <= 0f) return ordered;
		List<MarketAPI> siblings = ThreatIncData.getLiveColonyMarkets(world.getStarSystem().getId());
		for (MarketAPI c : siblings) {
			if (rest <= 0f) break;
			if (c == world || c.getPrimaryEntity() == null || hasOrder(c)) continue;
			if (ThreatGroundFronts.hostilePointsNear(Factions.THREAT, c) > 0f || ThreatGroundFronts.hasFront(c)) continue;
			float give = Math.min(rest, buddableFP(c, false));
			if (give < ThreatStrata.fpOf(ThreatStrata.sizeAt(1))) continue;
			order(c, SHIFT, give, world, "rallying to " + world.getName() + " against " + (int) attackFP + " FP");
			ordered += give;
			rest -= give;
		}
		return ordered;
	}

	/**
	 * A strike's bill buds into the staging colony's staged points at its system's flow
	 * (IncursionManager.launchStrike holds the launch until {@link #strikeStaged}). Refreshed each
	 * time the offensive asks; lapses after STRIKE_ORDER_DAYS unasked.
	 */
	public static void orderStrike(MarketAPI colony, MarketAPI target, float wantFP) {
		if (colony == null || wantFP <= 0f) return;
		MemoryAPI mem = colony.getMemoryWithoutUpdate();
		boolean fresh = !mem.contains(KEY_STRIKE_WANT) || Math.abs(mem.getFloat(KEY_STRIKE_WANT) - wantFP) > 0.5f;
		mem.set(KEY_STRIKE_WANT, wantFP);
		mem.set(KEY_STRIKE_SINCE, ThreatPosture.today());
		if (target != null) mem.set(KEY_STRIKE_TARGET, target.getId());
		if (fresh) {
			float staged = ThreatColonyManager.staged(colony.getId());
			ThreatIncConfig.log("Budding: a strike of " + (int) wantFP + " FP buds at " + colony.getName()
					+ (target != null ? " for " + target.getName() : "") + " (" + (int) staged + " FP staged, "
					+ (int) Math.ceil(ThreatStrata.strataOf(Math.max(0f, wantFP - staged)) / Math.max(1f, flowPerDay(colony.getStarSystem())))
					+ " d at " + (int) flowPerDay(colony.getStarSystem()) + " a day)");
		}
	}

	/** Whether the colony's staged points hold the strike's bill: the launch may draw. */
	public static boolean strikeStaged(MarketAPI colony, float billFP) {
		return colony != null && ThreatColonyManager.staged(colony.getId()) >= billFP - 0.5f;
	}

	public static void clearStrike(MarketAPI colony) {
		if (colony == null) return;
		MemoryAPI mem = colony.getMemoryWithoutUpdate();
		mem.unset(KEY_STRIKE_WANT);
		mem.unset(KEY_STRIKE_TARGET);
		mem.unset(KEY_STRIKE_SINCE);
	}

	public static boolean hasStrikeOrder(MarketAPI colony) {
		return colony != null && colony.getMemoryWithoutUpdate().contains(KEY_STRIKE_WANT);
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	/** IncursionManager's poll, after maintainGarrisons: every hive world moves its flow one way. */
	public static void poll(float elapsedDays, Random random) {
		if (elapsedDays <= 0f) return;
		if (random == null) random = new Random();
		float today = ThreatPosture.today();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getPrimaryEntity() == null || market.getStarSystem() == null) continue;
			try {
				MemoryAPI mem = market.getMemoryWithoutUpdate();
				readLoss(market, mem, elapsedDays);
				float hostile = ThreatGroundFronts.hostilePointsNear(Factions.THREAT, market);
				boolean contested = hostile > 0f;
				if (contested) mem.set(KEY_QUIET, today);
				// the trigger: a hostile weight holding the orbit, nothing yet budding or standing against it
				if (contested && !hasOrder(market) && ThreatGroundFronts.friendlyPointsNear(Factions.THREAT, market) < hostile) {
					ThreatPosture.rally(market, hostile);
				}
				advanceStrike(market, mem, elapsedDays, today);
				if (hasOrder(market)) advance(market, mem, elapsedDays, contested, random);
				else absorbLingering(market, mem, elapsedDays, contested, today);
			} catch (Throwable t) {
				ThreatIncConfig.log("Budding: " + market.getName() + " threw " + t);
			}
		}
	}

	/** Strata a day the world lost to the enemy over the last poll, from the accumulator ThreatStrata.add keeps. */
	protected static void readLoss(MarketAPI market, MemoryAPI mem, float elapsedDays) {
		float loss = mem.getFloat(KEY_LOSS);
		float seen = mem.getFloat(KEY_LOSS_SEEN);
		mem.set(KEY_LOSS_RATE, Math.max(0f, loss - seen) / elapsedDays);
		mem.set(KEY_LOSS_SEEN, loss);
	}

	public static float lossRate(MarketAPI market) {
		return market != null ? Math.max(0f, market.getMemoryWithoutUpdate().getFloat(KEY_LOSS_RATE)) : 0f;
	}

	/** Called by ThreatStrata.add: strata the enemy took from the world (a bombardment, a front's attrition). */
	public static void noteLoss(MarketAPI market, float strata) {
		if (market == null || strata <= 0f) return;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		mem.set(KEY_LOSS, mem.getFloat(KEY_LOSS) + strata);
	}

	/**
	 * Whether the world's defence should spawn short now: the strata left on the ground, falling at
	 * the last poll's rate, run out before the pool fills (ground / loss a day < (want - pool) / flow).
	 */
	protected static boolean doomed(MarketAPI market, float wantFP, float poolFP) {
		float rate = lossRate(market);
		if (rate <= 0f || poolFP <= 0f) return false;
		float ground = ThreatStrata.of(market);
		float flow = Math.max(1f, flowPerDay(market));
		return ground / rate < ThreatStrata.strataOf(wantFP - poolFP) / flow;
	}

	/** One poll of a budding order: drain if the attacker left, else fill at the flow and spawn when full (or doomed). */
	protected static void advance(MarketAPI market, MemoryAPI mem, float elapsedDays, boolean contested, Random random) {
		String kind = kind(market);
		float want = want(market);
		float pool = pool(market);
		float bite = flowFP(market) * elapsedDays;
		if (DEFEND.equals(kind) && !contested && ThreatPosture.forceOver(market) <= 0f) {
			// the attacker left before the spawn: the pool drains back at the flow rate
			float back = Math.min(pool, Math.min(bite, ThreatStrata.fpOf(ThreatStrata.room(market))));
			if (back > 0f) {
				ThreatStrata.add(market, ThreatStrata.strataOf(back), "Absorbed into", null);
				pool -= back;
				visual(market, "Swarm settling", back, false);
			}
			if (pool <= 0.5f) {
				ThreatIncConfig.log("Budding: " + market.getName() + " stands down, the force over it gone (" + (int) want + " FP wanted)");
				clear(market);
			} else {
				mem.set(KEY_POOL, pool);
			}
			return;
		}
		if (SHIFT.equals(kind)) {
			MarketAPI to = receiverOf(market);
			if (to == null) {
				ThreatIncConfig.log("Budding: " + market.getName() + " drops its shift, the receiver gone");
				ThreatStrata.add(market, ThreatStrata.strataOf(pool), "Absorbed into", null);
				clear(market);
				return;
			}
			// the passage sizes the swarm (fuelAffordFP): the pool fills only to what the free fuel pays one way today, and a
			// shift the fuel cannot pay a seed's worth of settles back instead of standing as a swarm that cannot sail
			float afford = fuelAffordFP(market, to);
			float floor = ThreatStrata.fpOf(Math.max(ThreatStrata.sizeAt(1), ThreatIncConfig.shiftFloorStrata()));
			if (afford < Math.min(floor, want)) {
				ThreatIncConfig.log("Budding: " + market.getName() + " drops its shift to " + to.getName() + ", the fuel pays the passage of "
						+ (int) afford + " FP (" + (int) want + " FP wanted, " + (int) pool + " pooled)");
				ThreatStrata.add(market, ThreatStrata.strataOf(pool), "Absorbed into", null);
				clear(market);
				return;
		}
			if (afford < want) {
				want = afford;
				mem.set(KEY_WANT, want);
			}
		}
		float short_ = want - pool;
		float took = 0f;
		if (short_ > 0.5f) {
			float can = Math.min(bite, Math.min(short_, buddableFP(market, DEFEND.equals(kind))));
			if (can > 0f) {
				took = take(market, can, DEFEND.equals(kind));
				pool += took;
				mem.set(KEY_POOL, pool);
				visual(market, "Swarm budding", took, true);
			}
		}
		boolean full = pool >= want - 0.5f;
		boolean dry = took <= 0f && short_ > 0.5f;
		if (full || (DEFEND.equals(kind) && doomed(market, want, pool))) {
			spawn(market, mem, kind, pool, full ? "the pool full" : "the world doomed", random);
		} else if (dry) {
			// nothing more can come: what is pooled goes, or the order lapses
			if (pool > 0.5f) spawn(market, mem, kind, pool, "no strata left to bud", random);
			else clear(market);
		}
	}

	/** Takes fleet points off the world into its pool: staged first, then strata to the floor (a stratum for its own defence, shiftFloorStrata else). */
	protected static float take(MarketAPI market, float fp, boolean forDefence) {
		if (fp <= 0f) return 0f;
		if (!forDefence) {
			float before = ThreatColonyManager.bankedFP(market);
			ThreatColonyManager.chargeFP(market, fp);
			return Math.max(0f, before - ThreatColonyManager.bankedFP(market));
		}
		String id = market.getId();
		float have = ThreatColonyManager.staged(id);
		float fromStaged = Math.min(have, fp);
		if (fromStaged > 0f) ThreatColonyManager.chargeFP(market, fromStaged);
		float rest = fp - fromStaged;
		if (rest <= 0f) return fromStaged;
		float take = Math.min(ThreatStrata.strataOf(rest), Math.max(0f, ThreatStrata.of(market) - ThreatStrata.sizeAt(1)));
		if (take > 0f) ThreatStrata.add(market, -take, "Budded from", null);
		return fromStaged + ThreatStrata.fpOf(take);
	}

	/**
	 * Fleet points whose one-way passage from {@code from} to {@code to} the hive's free fuel pays today (ThreatFuel.passage is
	 * linear in FP; unbounded with no fuel rule or in the same system). THE PASSAGE SIZES THE SHIFT (2026-10-30, hw322: the
	 * richest donor budded one 5.4k-FP swarm of 320 ships whose passage ThreatFuel.canPay refused, and it stood as defence).
	 */
	protected static float fuelAffordFP(MarketAPI from, MarketAPI to) {
		if (from == null || to == null || from.getStarSystem() == null || to.getStarSystem() == null) return Float.MAX_VALUE;
		float perPoint = ThreatFuel.passage(1f, ThreatFuel.ly(from.getStarSystem(), to.getStarSystem()), false);
		if (perPoint <= 0f) return Float.MAX_VALUE;
		return Math.max(0f, ThreatFuel.free(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL)) / perPoint;
	}

	protected static MarketAPI receiverOf(MarketAPI market) {
		String id = market.getMemoryWithoutUpdate().getString(KEY_TARGET);
		MarketAPI to = id != null ? ThreatIncData.resolveColonyMarket(id) : null;
		return to != null && to.getPrimaryEntity() != null ? to : null;
	}

	/** The pool becomes a fleet, whole, over the world: its Defend fleet, or a shift sent on its way. */
	protected static void spawn(MarketAPI market, MemoryAPI mem, String kind, float poolFP, String why, Random random) {
		MarketAPI to = SHIFT.equals(kind) ? receiverOf(market) : null;
		String forStrike = to != null ? mem.getString(KEY_FOR_STRIKE) : null;
		clear(market);
		if (poolFP <= 0.5f) return;
		CampaignFleetAPI fleet = ThreatFleetComposer.createSized(ThreatFleetComposer.JOB_GARRISON, poolFP, random);
		if (fleet == null || fleet.getFleetData().getNumMembers() == 0) {
			ThreatStrata.add(market, ThreatStrata.strataOf(poolFP), "Absorbed into", null);
			ThreatIncConfig.log("Budding: no fleet could be built at " + market.getName() + " for " + (int) poolFP + " FP; the pool settles");
			return;
		}
		fleet.setName(to != null ? "Shifting Swarm" : "Defense Swarm");
		MemoryAPI fm = fleet.getMemoryWithoutUpdate();
		fm.set(ThreatColonyManager.GARRISON_FLAG, market.getId());
		fm.set(ThreatColonyManager.SWARM_SPAWN_FP, fleet.getFleetPoints());
		ThreatColonyManager.makeDetectable(fleet);
		ThreatColonyManager.placeGarrisonSwarm(market, fleet, random);
		ThreatIncData.garrisonsFor(market.getId()).add(fleet);
		// the archetype re-spends the pool: what it could not place settles back, what it overspent is the world's
		float diff = poolFP - fleet.getFleetPoints();
		if (diff > 0.5f) ThreatStrata.add(market, ThreatStrata.strataOf(diff), "Absorbed into", null);
		ThreatIncConfig.log("Budding: " + market.getName() + " spawns " + fleet.getName() + ", " + (int) fleet.getFleetPoints() + " FP ("
				+ why + "; " + fleet.getFleetData().getNumMembers() + " ships; strata " + (int) ThreatStrata.of(market) + ", size " + market.getSize() + ")");
		if (to != null) {
			fm.set(ABSORB_ON_ARRIVAL, true);
			if (forStrike != null) fm.set(FOR_STRIKE, forStrike);
			if (!ThreatColonyManager.sendReinforcement(market, to, fleet)) {
				// the passage unpaid: it stands as a Defend fleet, absorbed when the orbit is quiet
				fm.unset(ABSORB_ON_ARRIVAL);
				ThreatIncConfig.log("Budding: the shift from " + market.getName() + " to " + to.getName() + " cannot sail; the swarm stands");
			} else {
				ThreatPosture.noteTransfer(to, fleet);
			}
		}
	}

	// ------------------------------------------------------------------
	// absorbing
	// ------------------------------------------------------------------

	/** A fleet over its own hive world with the orbit quiet for defendLingerDays (a shift at once) is absorbed at the flow rate, to the cap. */
	protected static void absorbLingering(MarketAPI market, MemoryAPI mem, float elapsedDays, boolean contested, float today) {
		List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(market.getId());
		if (fleets.isEmpty() || contested) return;
		float quiet = mem.contains(KEY_QUIET) ? today - mem.getFloat(KEY_QUIET) : Float.MAX_VALUE;
		boolean lingered = quiet >= ThreatIncConfig.defendLingerDays();
		CampaignFleetAPI pick = null;
		for (CampaignFleetAPI f : fleets) {
			if (f == null || !f.isAlive() || f.isExpired() || f.getBattle() != null) continue;
			if (!ThreatGroundFronts.nearWorld(f, market)) continue;
			if (!lingered && !f.getMemoryWithoutUpdate().getBoolean(ABSORB_ON_ARRIVAL)) continue;
			if (pick == null || f.getFleetPoints() < pick.getFleetPoints()) pick = f;
		}
		if (pick == null) return;
		float room = ThreatStrata.room(market);
		if (room < 1f) {
			ThreatIncConfig.logQuiet("budfull:" + market.getId(), "Budding: " + market.getName() + " is at its cap; " + pick.getName()
					+ " (" + (int) pick.getFleetPoints() + " FP) stands until shifted");
			return;
		}
		float bite = Math.min(flowFP(market) * elapsedDays, ThreatStrata.fpOf(room));
		absorbHulls(market, pick, bite, "Swarm absorbed");
	}

	/**
	 * Absorbs up to {@code fp} of the fleet's hulls into the world's strata: smallest first
	 * (ThreatGroundFronts.applyFleetLosses), the whole fleet when it is down to the bite or one ship.
	 * Returns the fleet points absorbed.
	 */
	public static float absorbHulls(MarketAPI market, CampaignFleetAPI fleet, float fp, String label) {
		if (market == null || fleet == null || !fleet.isAlive() || fp <= 0f) return 0f;
		float have = fleet.getFleetPoints();
		float got;
		if (have <= fp + 0.5f || fleet.getFleetData().getNumMembers() <= 1) {
			got = have;
			ThreatIncData.garrisonsFor(market.getId()).remove(fleet);
			ThreatSwarmDefend.release(fleet);
			fleet.despawn();
		} else {
			got = ThreatGroundFronts.applyFleetLosses(fleet, fp, null, ABSORB_KEY);
			if (got <= 0f) return 0f;
		}
		float strata = Math.min(ThreatStrata.strataOf(got), ThreatStrata.room(market));
		if (strata > 0f) ThreatStrata.add(market, strata, "Absorbed into", null);
		visual(market, label, got, false);
		ThreatIncConfig.logQuiet("absorb:" + market.getId() + ":" + fleet.getId(), "Budding: " + market.getName() + " absorbs " + (int) got + " FP of "
				+ fleet.getName() + (fleet.isAlive() ? " (" + (int) fleet.getFleetPoints() + " FP left)" : " (the whole fleet)")
				+ "; strata " + (int) ThreatStrata.of(market) + ", size " + market.getSize());
		return got;
	}

	// ------------------------------------------------------------------
	// strikes
	// ------------------------------------------------------------------

	/**
	 * One poll of a strike order: the bill buds at EVERY giver in parallel, each at its own flow (the
	 * user, 2026-10-30: "i thought they would be staging strata at frontline worlds in order to do big
	 * strikes, but all the strata is just sitting idle"). The staging system's worlds bud their share
	 * straight into the staged points (a same-system move is free); every other giver gets a shift
	 * order for its share of what is still open, flagged for the strike, and its swarm flies to the
	 * staging and is staged whole when it arrives ({@link #stageArrivals}). Shares are by
	 * ThreatColonyManager.strikeGivable (above the floor, less the defence's shortfall). Before: the whole
	 * bill at the STAGING system's flow - 400 strata a day at a frontier staging, so 95 days for a
	 * 7,549-FP relief strike at Ogre and years for an eight-prong campaign, with the mass at the back
	 * making the front's pipe small. With the hive banks (strikeFundShare 0) the old path stands.
	 */
	protected static void advanceStrike(MarketAPI colony, MemoryAPI mem, float elapsedDays, float today) {
		if (!mem.contains(KEY_STRIKE_WANT)) return;
		float want = mem.getFloat(KEY_STRIKE_WANT);
		if (today - mem.getFloat(KEY_STRIKE_SINCE) > STRIKE_ORDER_DAYS) {
			ThreatIncConfig.log("Budding: the strike order at " + colony.getName() + " lapses unasked (" + (int) want + " FP)");
			clearStrike(colony);
			return;
		}
		stageArrivals(colony);
		float staged = ThreatColonyManager.staged(colony.getId());
		if (staged >= want - 0.5f) return;
		if (ThreatIncConfig.strikeFundShare() <= 0f) {
			float bite = Math.min(want - staged, ThreatStrata.fpOf(flowPerDay(colony.getStarSystem()) * elapsedDays));
			if (bite > 0.5f && ThreatColonyManager.poolHiveBanks(colony, bite, "Strike")) visual(colony, "Swarm rising", bite, true);
			return;
		}
		float inbound = strikeInbound(colony);
		float open = want - staged - inbound;
		if (open <= 0.5f) return;
		// the givers: the staging system's worlds bud in place, the rest fly; a far giver's share is a
		// whole swarm of at least a seed's worth (shiftFloorStrata), no drip of tiny fleets across the sector
		float farMin = ThreatStrata.fpOf(Math.max(ThreatStrata.sizeAt(1), ThreatIncConfig.shiftFloorStrata()));
		List<MarketAPI> near = new ArrayList<MarketAPI>(), far = new ArrayList<MarketAPI>();
		float can = 0f;
		for (MarketAPI g : ThreatIncData.getAllLiveColonyMarkets()) {
			if (g.getPrimaryEntity() == null || g.getStarSystem() == null) continue;
			float give = ThreatColonyManager.strikeGivable(g);
			if (give <= 0f) continue;
			if (g.getStarSystem() == colony.getStarSystem()) {
				near.add(g);
			} else {
				if (hasOrder(g) || ThreatPosture.underAttack(g.getStarSystem())
						|| ThreatGroundFronts.hostilePointsNear(Factions.THREAT, g) > 0f) continue;
				far.add(g);
			}
			can += give;
		}
		if (can <= 0f) return;
		// a far giver whose share would be under a swarm's worth drops out, and the rest share what it leaves
		for (MarketAPI g : new ArrayList<MarketAPI>(far)) {
			if (open * ThreatColonyManager.strikeGivable(g) / can < farMin) {
				far.remove(g);
				can -= ThreatColonyManager.strikeGivable(g);
			}
		}
		if (can <= 0f) return;
		float took = 0f;
		for (MarketAPI g : near) {
			float give = ThreatColonyManager.strikeGivable(g);
			float bite = Math.min(open * give / can, flowFP(g) * elapsedDays);
			if (bite <= 0.5f) continue;
			float got = ThreatColonyManager.takeStrikeFP(g, bite);
			if (got <= 0f) continue;
			ThreatColonyManager.stage(colony, got);
			took += got;
			visual(g, "Swarm rising", got, true);
		}
		int orders = 0;
		float ordered = 0f;
		for (MarketAPI g : far) {
			float give = ThreatColonyManager.strikeGivable(g);
			float share = Math.min(open * give / can, give);
			if (share < farMin) continue;
			g.getMemoryWithoutUpdate().set(KEY_FOR_STRIKE, colony.getId());
			order(g, SHIFT, share, colony, "the strike staging at " + colony.getName());
			orders++;
			ordered += share;
		}
		if (took > 0.5f || orders > 0) {
			ThreatIncConfig.logQuiet("strikebud:" + colony.getId(), "Budding: the strike at " + colony.getName() + " (" + (int) want
					+ " FP) buds at " + near.size() + " world(s) of its system and " + orders + " beyond: " + (int) took
					+ " FP staged this poll, " + (int) ordered + " FP ordered to fly in; " + (int) (staged + took) + " staged, "
					+ (int) (inbound + ordered) + " on the way");
		}
	}

	/**
	 * A swarm flagged for the strike (FOR_STRIKE) seated over the staging colony with the orbit quiet is
	 * staged whole: its fleet points join the staged points and the fleet goes - never absorbed into the
	 * strata at the staging's flow, which would put the pipe back. Returns the fleet points staged.
	 */
	protected static float stageArrivals(MarketAPI colony) {
		float got = 0f;
		for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(ThreatIncData.garrisonsFor(colony.getId()))) {
			if (f == null || !f.isAlive() || f.isExpired() || f.getBattle() != null) continue;
			if (!colony.getId().equals(f.getMemoryWithoutUpdate().getString(FOR_STRIKE))) continue;
			if (!ThreatGroundFronts.nearWorld(f, colony)) continue;
			float fp = f.getFleetPoints();
			ThreatIncData.garrisonsFor(colony.getId()).remove(f);
			ThreatSwarmDefend.release(f);
			f.despawn();
			ThreatColonyManager.stage(colony, fp);
			got += fp;
			visual(colony, "Swarm staged", fp, false);
			ThreatIncConfig.log("Budding: " + colony.getName() + " stages " + (int) fp + " FP of " + f.getName() + " for its strike; "
					+ (int) ThreatColonyManager.staged(colony.getId()) + " FP staged");
		}
		return got;
	}

	/** Fleet points on their way to the staging colony for its strike: shift orders flagged for it (their whole want) and flagged swarms in flight. */
	public static float strikeInbound(MarketAPI colony) {
		if (colony == null) return 0f;
		float fp = 0f;
		for (MarketAPI c : ThreatIncData.getAllLiveColonyMarkets()) {
			if (c == colony || !SHIFT.equals(kind(c))) continue;
			if (colony.getId().equals(c.getMemoryWithoutUpdate().getString(KEY_FOR_STRIKE))) fp += want(c);
		}
		for (CampaignFleetAPI f : ThreatIncData.reinforcementFleets().values()) {
			if (f == null || !f.isAlive()) continue;
			if (colony.getId().equals(f.getMemoryWithoutUpdate().getString(FOR_STRIKE))) fp += f.getFleetPoints();
		}
		return fp;
	}

	/**
	 * Fleet points already budded toward the hive's strikes: the staged points at every colony with a
	 * strike order and what is on its way to each (strikeInbound). Out of the strata, so out of the
	 * strike fund, yet the campaign's: the offensive reads its means as the fund plus this, else a
	 * campaign whose bill is budding reads itself poorer every pass and never asks again (its orders
	 * lapse at STRIKE_ORDER_DAYS with the bill half staged).
	 */
	public static float strikeBound() {
		float fp = 0f;
		for (MarketAPI c : ThreatIncData.getAllLiveColonyMarkets()) {
			if (!hasStrikeOrder(c)) continue;
			fp += ThreatColonyManager.staged(c.getId()) + strikeInbound(c);
		}
		return fp;
	}

	// ------------------------------------------------------------------
	// the shift
	// ------------------------------------------------------------------

	/**
	 * The hive's one lever, in place of the pressure pass (ThreatColonyManager.redistributeGarrisons):
	 * one shift order a poll. The receiver: an attacked world its system could not answer (the
	 * posture's forceOver less what stands and buds for it); else, while the offensive saves for a
	 * campaign, the staging system of its prongs most short of its bill (forward staging, the user
	 * 2026-10-30); else under PRESS the front system - the hive system nearest a human world the swarm
	 * has seen - and under CONSOLIDATE the densest system (the most strata); under EXPAND nothing
	 * moves. The receiving world is the system's with the most room. The donor: the nearest quiet world
	 * of another system (never a campaign's staging system) holding a stratum above shiftFloorStrata,
	 * giving what it has above the floor up to the receiver's room.
	 */
	public static void shift() {
		if (!ThreatIncConfig.reinforceEnabled()) return;
		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		if (colonies.size() < 2) return;
		MarketAPI receiver = null;
		float amount = 0f;
		String why = null;
		boolean defence = false;
		// an attacked world short of an answer
		for (MarketAPI c : colonies) {
			if (c.getPrimaryEntity() == null || c.getStarSystem() == null) continue;
			float force = ThreatPosture.forceOver(c);
			if (force <= 0f) continue;
			float margin = ThreatIncConfig.systemDefenceMargin();
			float want = margin > 0f ? force * margin : force;
			float short_ = want - ThreatPosture.standsFor(c) - pendingFP(c) - buddableFP(c.getStarSystem());
			if (short_ <= 0f) continue;
			float room = ThreatStrata.fpOf(ThreatStrata.room(c));
			if (room <= 0f) continue;
			if (receiver == null || short_ > amount) {
				receiver = c;
				amount = Math.min(short_, room);
				why = (int) force + " FP over it, " + (int) short_ + " FP short";
				defence = true;
			}
		}
		// FORWARD STAGING (the user, 2026-10-30: "i thought they would be staging strata at frontline worlds in
		// order to do big strikes"): while the offensive saves for a campaign, the shift fills its prongs' staging
		// systems with the campaign's bill whatever the stance - the staging most short first, its world with the
		// most room - so the front holds the mass before the launch and its own flow and defence rise with it;
		// the staging systems never donate
		Set<String> stagingSystems = new HashSet<String>();
		if (receiver == null) {
			Map<String, Float> saving = ThreatOffensive.savingStagings();
			float worst = 0f;
			MarketAPI stagingShort = null;
			for (Map.Entry<String, Float> e : saving.entrySet()) {
				MarketAPI s = ThreatIncData.resolveColonyMarket(e.getKey());
				if (s == null || s.getStarSystem() == null) continue;
				stagingSystems.add(s.getStarSystem().getId());
				// held: the system's spare strata, staged points, the swarms standing over its worlds or flying to
				// them (ownedFleetFP - a shifted swarm waits in orbit to be absorbed at the flow, and the launch
				// takes standing swarms first), and the orders budding toward them
				float held = 0f;
				for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(s.getStarSystem().getId())) {
					held += ThreatColonyManager.strikeSpareFP(c) + ThreatColonyManager.staged(c.getId())
							+ ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId())) + pendingFP(c);
				}
				float short_ = e.getValue() - held;
				if (short_ > worst) {
					worst = short_;
					stagingShort = s;
				}
			}
			if (stagingShort != null) {
				for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(stagingShort.getStarSystem().getId())) {
					if (c.getPrimaryEntity() == null) continue;
					float room = ThreatStrata.fpOf(ThreatStrata.room(c)) - ThreatPosture.inboundFP(c.getId()) - pendingFP(c);
					if (room > amount) {
						receiver = c;
						amount = room;
					}
				}
				if (receiver != null) {
					amount = Math.min(amount, worst);
					why = "staging for the campaign at " + stagingShort.getName() + ", " + (int) worst + " FP short";
				}
			}
		}
		if (receiver == null) {
			int stance = ThreatStance.stance();
			StarSystemAPI into = null;
			if (stance == ThreatStance.PRESS) {
				float best = Float.MAX_VALUE;
				for (String systemId : ThreatIncData.colonyMarkets().keySet()) {
					if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
					StarSystemAPI s = ThreatScoutRoute.systemById(systemId);
					if (s == null) continue;
					float ly = ThreatPosture.nearestKnownHumanLY(s);
					if (ly < best) {
						best = ly;
						into = s;
					}
				}
				why = "PRESS: the front system";
			} else if (stance == ThreatStance.CONSOLIDATE) {
				float best = 0f;
				for (String systemId : ThreatIncData.colonyMarkets().keySet()) {
					float strata = 0f;
					for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) strata += ThreatStrata.of(c);
					if (strata > best) {
						best = strata;
						into = ThreatScoutRoute.systemById(systemId);
					}
				}
				why = "CONSOLIDATE: the densest system";
			}
			if (into == null) return;
			for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(into.getId())) {
				if (c.getPrimaryEntity() == null) continue;
				float room = ThreatStrata.fpOf(ThreatStrata.room(c)) - ThreatPosture.inboundFP(c.getId()) - pendingFP(c);
				if (room > amount) {
					receiver = c;
					amount = room;
				}
			}
		}
		// a defence takes a stratum's worth from the nearest donor (speed); a staging or stance shift takes a
		// swarm of at least a seed's worth from the donor that can give the MOST, nearest at a tie (the user,
		// 2026-10-30 14:10, Pelephanar's size-8 worlds beside 80-FP shifts from Gamma Darien: "why is it focusing
		// on the furthest colony?" - nearest-first never drew the back line down, so its mass only grew)
		float min = defence ? ThreatStrata.fpOf(ThreatStrata.sizeAt(1))
				: ThreatStrata.fpOf(Math.max(ThreatStrata.sizeAt(1), ThreatIncConfig.shiftFloorStrata()));
		if (receiver == null || amount < min) return;
		MarketAPI donor = null;
		float near = Float.MAX_VALUE, most = 0f;
		for (MarketAPI c : colonies) {
			if (c == receiver || c.getPrimaryEntity() == null || c.getStarSystem() == null) continue;
			if (c.getStarSystem() == receiver.getStarSystem() || hasOrder(c)) continue;
			if (stagingSystems.contains(c.getStarSystem().getId())) continue;
			if (ThreatPosture.underAttack(c.getStarSystem()) || ThreatGroundFronts.hostilePointsNear(Factions.THREAT, c) > 0f) continue;
			// what it can give: its buddable mass, at most the swarm whose passage the fuel pays (fuelAffordFP)
			float can = Math.min(buddableFP(c, false), fuelAffordFP(c, receiver));
			if (can < min) continue;
			float ly = ThreatFuel.ly(c.getStarSystem(), receiver.getStarSystem());
			float gives = Math.min(amount, can);
			boolean better = defence ? ly < near : gives > most + 0.5f || (gives > most - 0.5f && ly < near);
			if (better) {
				near = ly;
				most = gives;
				donor = c;
			}
		}
		if (donor == null) return;
		float give = Math.min(amount, Math.min(buddableFP(donor, false), fuelAffordFP(donor, receiver)));
		order(donor, SHIFT, give, receiver, why + ", " + (int) near + " ly");
	}

	// ------------------------------------------------------------------
	// readouts
	// ------------------------------------------------------------------

	/** The board's flow cell for a world: "budding 1,250 (2 d)", "shifting 640 to X", "absorbing 400 (1 d)", "settling"; null when nothing moves. */
	public static String flowText(MarketAPI market) {
		if (market == null) return null;
		if (hasStrikeOrder(market)) {
			float want = market.getMemoryWithoutUpdate().getFloat(KEY_STRIKE_WANT);
			float left = Math.max(0f, want - ThreatColonyManager.staged(market.getId()));
			if (left > 0.5f) return "strike " + ThreatStrata.fmt(ThreatStrata.strataOf(want)) + " (" + days(ThreatStrata.strataOf(left), flowPerDay(market.getStarSystem())) + ")";
		}
		String kind = kind(market);
		if (kind != null) {
			float want = want(market), pool = pool(market);
			float left = ThreatStrata.strataOf(Math.max(0f, want - pool));
			if (SHIFT.equals(kind)) {
				MarketAPI to = receiverOf(market);
				return "shifting " + ThreatStrata.fmt(ThreatStrata.strataOf(want)) + (to != null ? " to " + to.getName() : "");
			}
			boolean draining = ThreatGroundFronts.hostilePointsNear(Factions.THREAT, market) <= 0f && ThreatPosture.forceOver(market) <= 0f;
			if (draining) return "settling " + ThreatStrata.fmt(ThreatStrata.strataOf(pool));
			return "budding " + ThreatStrata.fmt(ThreatStrata.strataOf(want)) + " (" + days(left, flowPerDay(market)) + ")";
		}
		float standing = 0f;
		for (CampaignFleetAPI f : ThreatIncData.garrisonsFor(market.getId())) {
			if (f != null && f.isAlive() && ThreatGroundFronts.nearWorld(f, market)) standing += f.getFleetPoints();
		}
		if (standing > 0f && ThreatGroundFronts.hostilePointsNear(Factions.THREAT, market) <= 0f) {
			float strata = Math.min(ThreatStrata.strataOf(standing), ThreatStrata.room(market));
			if (strata < 1f) return "at its cap";
			return "absorbing " + ThreatStrata.fmt(strata) + " (" + days(strata, flowPerDay(market)) + ")";
		}
		return null;
	}

	protected static String days(float strata, float flow) {
		return (int) Math.ceil(strata / Math.max(1f, flow)) + " d";
	}

	/** The rising or falling glows over the world, for a player in the system: a fragment a few fleet points. */
	protected static void visual(MarketAPI market, String label, float fp, boolean rising) {
		if (market == null || market.getPrimaryEntity() == null || fp <= 0f) return;
		Color colour = Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();
		int fragments = Math.max(2, Math.min(16, (int) (fp / 8f)));
		ThreatFabricationVisual.play(market.getPrimaryEntity(), colour, label, fragments, rising);
	}

	/** Fleets over hive worlds that are the strata in space: every live garrison fleet, for the hull census. */
	public static float standingFP() {
		float fp = 0f;
		for (MarketAPI c : ThreatIncData.getAllLiveColonyMarkets()) {
			for (CampaignFleetAPI f : ThreatIncData.garrisonsFor(c.getId())) {
				if (f != null && f.isAlive()) fp += f.getFleetPoints();
			}
		}
		return fp;
	}

}
