package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * POSTURE - the hive weighs the war around each of its systems and holds
 * the garrison that war calls for, no more (2026-09-29). Until now every
 * nexus built past its size table whenever its bank paid, and only upkeep
 * stopped it: 90-100k FP of mostly idle standing fleets.
 *
 * <p>Every postureDays each hive system's PRESSURE is read, in fleet points:
 * the fleets attacking it now (sieges booked on its worlds, hunts, sorties
 * aimed at it), what the bases staging for it could pay a force there
 * (ThreatSoftening.payableFP - capacity, not a siege's sizing, which reads
 * the garrison and would chase its own tail), Threat ships lost there
 * lately, hostile fleets in the system and forward bases' guards in reach.
 * The larger of attacks and staged threat, plus the rest; it rises at once
 * and decays over a month. Each colony WANTS its base - the reserve a
 * muster never takes (garrisonReserve), and at a forge colony the launch
 * stock above it - or its share of what the pressure needs by the siege's
 * own orbit margin and postureMargin, whichever is more. The size table only
 * shapes what is built, no longer how much (2026-09-29 test: the full table
 * cost more than upkeep let the hive hold, so the want never left a
 * surplus). Nothing above the want is built; what stands above it by
 * postureBand and a swarm is let go - to a sibling short of its want first
 * (ThreatColonyManager.redistributeByPressure; a quiet colony gives a
 * pressed one down to its reserve), then to waves and strikes, and once it
 * has stood idle past the upkeep's break-even it is recycled. A system
 * under attack and short of its need also calls home the strikes that are
 * convenient (recallStrikes). State is primitive maps only
 * (threatinc_posture, _postureLoss).
 *
 * <p>In the swarm's fog (ThreatSwarmIntel, docs/threat-fog.md) the attacks,
 * the staged threat and the forward guards are what it has seen, the last two
 * by how far each sighting is still trusted; losses, hostiles in the system
 * and wounds are its own and stay live.
 */
public class ThreatPosture {

	/** System id -> float[] (the S_* fields). */
	public static final String KEY_STATE = "threatinc_posture";
	/** System id -> float[]{Threat FP lost, day noted}; decays over DECAY_DAYS. */
	public static final String KEY_LOSS = "threatinc_postureLoss";

	public static final int QUIET = 0, WATCHFUL = 1, THREATENED = 2, BESIEGED = 3;
	protected static final String[] MODE_NAMES = { "QUIET", "WATCHFUL", "THREATENED", "BESIEGED" };

	// S_ATTACKED (2026-09-29): 1 while fleets fight the system or it lost ships
	// in the last DECAY_DAYS. A state of the six-field layout before it reads
	// as unread, so the first pass after a load starts its pressure afresh -
	// every earlier one had frozen (today(), below)
	protected static final int S_PRESSURE = 0, S_WANT = 1, S_SURPLUS_SINCE = 2, S_DAY = 3, S_MODE = 4,
			S_NEED = 5, S_ATTACKED = 6, S_LEN = 7;

	/** Days over which pressure and losses fall by a factor of e; also how long a transfer rests (redistributeByPressure). */
	public static final float DECAY_DAYS = 30f;
	/**
	 * The garrison the pressure needs by the siege's margin over the garrison
	 * held: a system enters WATCHFUL at the first figure, THREATENED at the
	 * third, and leaves each only below the lower figure beside it. One
	 * threshold each flipped a system every few days as transfers moved its
	 * held FP across it (ti8c). THREATENED is entered when the hive is
	 * outmatched at the siege's own margin (1.0) and left once it holds its
	 * want (1 / postureMargin, 0.8 at the default): at 0.75 a system that
	 * had built to its want read 0.8 and stayed THREATENED, forges home,
	 * for as long as anything stood staged against it.
	 */
	protected static final float WATCHFUL_ENTER = 0.25f, WATCHFUL_LEAVE = 0.15f,
			THREATENED_ENTER = 1.0f, THREATENED_LEAVE = 0.8f;

	/** Market id -> when it was last sent a transfer (redistributeByPressure): it gives none for DECAY_DAYS. */
	public static final String KEY_RECEIVED = "threatinc_postureReceived";
	/** Fleet memory: when the fleet was last sent as a transfer; it is not sent again for DECAY_DAYS. */
	public static final String MOVED_KEY = "$threatinc_postureMoved";

	/**
	 * Swarms a forge colony keeps above its reserve for launches: the fewest a
	 * strike musters (ThreatColonyManager.pickStrikeStaging), which also
	 * covers a Seeding Swarm's one.
	 */
	public static final int LAUNCH_STOCK = 2;

	/** Market id -> {want, need}: its base or its share of the need by its floor; rebuilt each pass. Not saved. */
	protected static final Map<String, float[]> COLONY = new HashMap<String, float[]>();
	/** The last pass's calls, each less what its recalls sent (recallStrikes): a launch reads them (strikeCapFP). Not saved. */
	protected static final List<Call> CALLS = new ArrayList<Call>();
	protected static long lastPoll = Long.MIN_VALUE;
	/** Expansion appetite from the last pass; below 0 before the first (trySpread then acts as before). */
	protected static float appetite = -1f;
	/** The last pass's sector totals, for the monthly line: held, want, surplus, quiet systems, pressed systems. */
	protected static float sumHeld, sumWant, sumSurplus;
	protected static int nQuiet, nPressed;
	/** Hive systems the last pass wrote off (triage in poll); transient, for the change log. */
	protected static final Set<String> INDEFENSIBLE = new HashSet<String>();
	/** The last pass's forges (those of quiet systems alone under posturePressedForgesHome), and those whose bank pays a founding. */
	protected static int nForges, nForgesCovered;
	/** This month's releases, for the monthly line. */
	protected static int sentFleets, consumedSwarms, recycledFleets;
	protected static float sentFP, recycledFP;
	/** Battle -> ids of the Threat ships already booked: an autoresolve reports every round. */
	protected static final Map<BattleAPI, Set<String>> LOSSES_SEEN = new java.util.WeakHashMap<BattleAPI, Set<String>>();

	public static boolean enabled() {
		return ThreatIncConfig.postureEnabled();
	}

	/** Called on load: nothing of the session left behind carries into the loaded game. */
	public static void forget() {
		COLONY.clear();
		CALLS.clear();
		INDEFENSIBLE.clear();
		LOSSES_SEEN.clear();
		lastPoll = Long.MIN_VALUE;
		appetite = -1f;
		sentFleets = consumedSwarms = recycledFleets = 0;
		sentFP = recycledFP = 0f;
	}

	public static Map<String, float[]> state() {
		return ThreatIncData.map(KEY_STATE);
	}

	protected static Map<String, float[]> losses() {
		return ThreatIncData.map(KEY_LOSS);
	}

	/**
	 * The campaign clock in days, for the saved day stamps: days since
	 * timestamp 1, at vanilla's own conversion. NOT since 0 - vanilla reads a
	 * timestamp of 0 as "never" and returns Float.MAX_VALUE, so every stamp
	 * was the same day, nothing decayed, and a system's first pressure stood
	 * for a year (ti8c: Gamma Shevar at 6442 with nothing near it).
	 */
	protected static float today() {
		return Global.getSector().getClock().getElapsedDaysSince(1L);
	}

	protected static Map<String, Long> received() {
		return ThreatIncData.map(KEY_RECEIVED);
	}

	/** Books a transfer to the colony and marks the fleet sent: neither moves another for DECAY_DAYS. */
	public static void noteTransfer(MarketAPI receiver, CampaignFleetAPI fleet) {
		long now = Global.getSector().getClock().getTimestamp();
		if (receiver != null) received().put(receiver.getId(), now);
		if (fleet != null) fleet.getMemoryWithoutUpdate().set(MOVED_KEY, now);
	}

	/** Whether the colony was sent a transfer in the last DECAY_DAYS: it gives none. */
	public static boolean recentlyReceived(MarketAPI market) {
		Long when = market != null ? received().get(market.getId()) : null;
		return when != null && within(when);
	}

	/** Whether the fleet was sent as a transfer in the last DECAY_DAYS: it is not sent again. */
	public static boolean recentlyMoved(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		Object when = fleet.getMemoryWithoutUpdate().get(MOVED_KEY);
		return when instanceof Long && within((Long) when);
	}

	/** Less than DECAY_DAYS since the stamp (a stamp from a timeline loaded over reads as past). */
	protected static boolean within(long stamp) {
		float days = Global.getSector().getClock().getElapsedDaysSince(stamp);
		return days >= 0f && days < DECAY_DAYS;
	}

	/**
	 * Whether the system is under real attack - BESIEGED, or fleets fighting
	 * it, or ships lost there in the last DECAY_DAYS: only such a system draws
	 * a quiet colony down to its minimum. A THREATENED one only faces a threat.
	 */
	public static boolean underAttack(StarSystemAPI system) {
		if (!enabled() || system == null) return false;
		float[] s = state().get(system.getId());
		if (s == null || s.length < S_LEN) return false;
		return (int) s[S_MODE] == BESIEGED || s[S_ATTACKED] > 0f;
	}

	// ------------------------------------------------------------------
	// the gates the rest of the hive reads
	// ------------------------------------------------------------------

	/** The system's posture; QUIET when posture is off or the system is not yet read. */
	public static int mode(StarSystemAPI system) {
		if (!enabled() || system == null) return QUIET;
		float[] s = state().get(system.getId());
		return s != null && s.length >= S_LEN ? (int) s[S_MODE] : QUIET;
	}

	/** Whether the system is pressed hard enough to keep its forges home (THREATENED or BESIEGED). */
	public static boolean pressed(StarSystemAPI system) {
		return mode(system) >= THREATENED;
	}

	/**
	 * The garrison the colony wants: its base, or its share of the pressure's
	 * need; its base before the first pass. Pressing (ThreatStance), a staging
	 * colony also wants the strike its stance's target calls for.
	 */
	public static float wantFP(MarketAPI market) {
		float[] c = market != null ? COLONY.get(market.getId()) : null;
		return (c != null ? c[0] : baseFP(market)) + ThreatStance.extraWantFP(market);
	}

	/** The colony's share of what the pressure alone needs held; 0 unread. */
	public static float needFP(MarketAPI market) {
		float[] c = market != null ? COLONY.get(market.getId()) : null;
		return c != null ? c[1] : 0f;
	}

	/**
	 * Whether a launch finds the colony still regrowing: short of its want by
	 * more than its cheapest swarm. Held against the want itself, a colony a
	 * few FP short - a learned swarm cost crept up, a scratch from a fight,
	 * a bank a swarm short - sent nothing at all.
	 */
	public static boolean regrowing(MarketAPI market, float heldFP) {
		if (!enabled() || market == null) return false;
		return heldFP < wantFP(market) - rowsFP(market, 0, 1);
	}

	/** maintainGarrisons: true while the colony holds (inbound included) less than it wants. */
	public static boolean wantsGrowth(MarketAPI market, float heldFP) {
		if (!enabled() || market == null) return true;
		return heldFP < wantFP(market);
	}

	/**
	 * Fleet points of garrison a launch may take from the colony: what it
	 * holds above what the pressure needs held (never below its reserve -
	 * ownAvailableForLaunch counts that). Float.MAX_VALUE when posture is off
	 * or nothing presses the system: a quiet hive thins to its reserve to spread.
	 */
	public static float launchSpareFP(MarketAPI market) {
		if (!enabled() || market == null) return Float.MAX_VALUE;
		float need = needFP(market);
		if (need <= 0f) return Float.MAX_VALUE;
		return ThreatColonyManager.ownedFleetFP(market, ThreatIncData.garrisonsFor(market.getId())) - need;
	}

	/** What the colony holds above want by postureBand and one swarm: what it lets go. */
	public static float releasableFP(MarketAPI market, float heldFP) {
		if (!enabled() || market == null) return 0f;
		float band = Math.max(0f, ThreatIncConfig.postureBand());
		return threatinc.rules.PostureRules.releasableFP(heldFP, wantFP(market), band, oneSwarmFP(market));
	}

	/**
	 * The pending claims trySpread may hold with this many free forges: all of
	 * them while posture is off or unread, else the appetite's share of them
	 * (at least one while it is above 0, none at 0), weighed by the share of
	 * the surplus the stance gives expansion (ThreatStance.expansionShare).
	 */
	public static int claimCap(int freeForges) {
		if (!enabled() || appetite < 0f) return freeForges;
		return threatinc.rules.PostureRules.claimCap(freeForges, appetite, ThreatStance.expansionShare());
	}

	public static float appetite() {
		return appetite;
	}

	/**
	 * What a colony of a quiet system gives one under attack (underAttack) may
	 * leave it with: its minimum (ThreatColonyManager.redistributeByPressure).
	 */
	public static float thinnableFP(MarketAPI market, float heldFP) {
		if (!enabled() || market == null) return 0f;
		return heldFP - minimumFP(market);
	}

	/** Fleet points of the colony's size table (swarmCostEstimate of every row): how the need is split. */
	public static float floorFP(MarketAPI market) {
		if (market == null) return 0f;
		float fp = 0f;
		for (int[] row : ThreatColonyManager.desiredGarrison(market.getSize())) {
			fp += ThreatColonyManager.swarmCostEstimate(row);
		}
		return fp;
	}

	/** The swarms baseFP stands for: the reserve, and a launching colony's stock. */
	public static int baseCount(MarketAPI market) {
		if (market == null) return 0;
		return ThreatColonyManager.garrisonReserve(market) + (stockFP(market) > 0f ? LAUNCH_STOCK : 0);
	}

	/** What a colony wants with nothing pressing it: its minimum, and its launch stock. */
	public static float baseFP(MarketAPI market) {
		return minimumFP(market) + stockFP(market);
	}

	/**
	 * The garrison no launch takes (ThreatColonyManager.garrisonReserve swarms)
	 * at the table's cheapest rows - musters send the largest, so the reserve
	 * that stays home is the smallest. At least one swarm.
	 */
	public static float minimumFP(MarketAPI market) {
		if (market == null) return 0f;
		return rowsFP(market, 0, ThreatColonyManager.garrisonReserve(market));
	}

	/**
	 * A colony that can launch (a forge, and the size for a wave or a strike)
	 * keeps LAUNCH_STOCK swarms above its reserve, the table's next rows: the
	 * substance of its next wave or strike, rebuilt from its bank once spent.
	 */
	public static float stockFP(MarketAPI market) {
		if (market == null || ThreatColonyManager.getForge(market) == null) return 0f;
		int min = Math.min(ThreatIncConfig.spreadMinSize(), ThreatIncConfig.strikeMinSize());
		if (market.getSize() < min) return 0f;
		return rowsFP(market, ThreatColonyManager.garrisonReserve(market), LAUNCH_STOCK);
	}

	/** The cost of n rows of the colony's size table from the cheapest, the from-th on (in turn past the table, as maintainGarrisons builds). */
	protected static float rowsFP(MarketAPI market, int from, int n) {
		int[][] table = ThreatColonyManager.desiredGarrison(market.getSize());
		if (table.length == 0 || n <= 0) return 0f;
		float[] costs = new float[table.length];
		for (int i = 0; i < table.length; i++) costs[i] = ThreatColonyManager.swarmCostEstimate(table[i]);
		java.util.Arrays.sort(costs);
		float fp = 0f;
		for (int i = from; i < from + n; i++) fp += costs[i % costs.length];
		return fp;
	}

	/** The heaviest swarm of the colony's size table: the band's one swarm. */
	public static float oneSwarmFP(MarketAPI market) {
		if (market == null) return 0f;
		float fp = 0f;
		for (int[] row : ThreatColonyManager.desiredGarrison(market.getSize())) {
			fp = Math.max(fp, ThreatColonyManager.swarmCostEstimate(row));
		}
		return fp;
	}

	public static void noteSent(float fp) {
		sentFleets++;
		sentFP += fp;
	}

	public static void noteConsumed(int swarms) {
		consumedSwarms += swarms;
	}

	// ------------------------------------------------------------------
	// losses (ThreatSwarmBountyIntel.Kills)
	// ------------------------------------------------------------------

	/**
	 * Books a battle the swarm fought, each ship once: the Threat ships it
	 * lost in a hive system into that system's loss ledger, and - wherever it
	 * was - the Threat ships lost and the enemy ships its side sank into the
	 * stance's attrition ledger (ThreatStance.noteTrend).
	 */
	public static void noteBattle(BattleAPI battle) {
		if (battle == null || !enabled()) return;
		List<CampaignFleetAPI> one = battle.getSnapshotSideOne();
		List<CampaignFleetAPI> two = battle.getSnapshotSideTwo();
		if (one == null || two == null) return;
		// the swarm's side; the other is its enemy
		List<CampaignFleetAPI> enemies = hasThreat(one) ? two : hasThreat(two) ? one : null;
		if (enemies == null) return;
		Set<String> seen = LOSSES_SEEN.get(battle);
		if (seen == null) {
			seen = new HashSet<String>();
			LOSSES_SEEN.put(battle, seen);
		}
		float lost = 0f, killed = 0f;
		// each enemy faction's losses and its weight on the field, for its own
		// exchange (ThreatFactionStance): the swarm's losses are shared by weight
		java.util.Map<String, float[]> byFaction = new java.util.HashMap<String, float[]>();
		for (CampaignFleetAPI fleet : battle.getSnapshotBothSides()) {
			if (fleet == null || fleet.getFaction() == null) continue;
			boolean ours = Factions.THREAT.equals(fleet.getFaction().getId());
			if (!ours && !enemies.contains(fleet)) continue;
			float fp = 0f;
			for (FleetMemberAPI m : Misc.getSnapshotMembersLost(fleet)) {
				if (!seen.add(m.getId())) continue;
				fp += m.getFleetPointCost();
			}
			if (!ours) {
				float[] f = byFaction.get(fleet.getFaction().getId());
				if (f == null) {
					f = new float[2];
					byFaction.put(fleet.getFaction().getId(), f);
				}
				f[0] += fp;
				f[1] += fleet.getFleetPoints() + fp;
			}
			if (fp <= 0f) continue;
			if (!ours) {
				killed += fp;
				continue;
			}
			lost += fp;
			if (!(fleet.getContainingLocation() instanceof StarSystemAPI)) continue;
			String systemId = ((StarSystemAPI) fleet.getContainingLocation()).getId();
			if (ThreatIncData.colonyMarkets().containsKey(systemId)) addLoss(systemId, fp);
		}
		ThreatStance.noteTrend(lost, killed);
		float weight = 0f;
		for (float[] f : byFaction.values()) weight += f[1];
		for (java.util.Map.Entry<String, float[]> e : byFaction.entrySet()) {
			float share = weight > 0f ? e.getValue()[1] / weight : 0f;
			ThreatFactionStance.noteTrend(e.getKey(), e.getValue()[0], lost * share);
		}
	}

	protected static boolean hasThreat(List<CampaignFleetAPI> side) {
		for (CampaignFleetAPI f : side) {
			if (f != null && f.getFaction() != null && Factions.THREAT.equals(f.getFaction().getId())) return true;
		}
		return false;
	}

	protected static void addLoss(String systemId, float fp) {
		float day = today();
		losses().put(systemId, new float[] { lossFP(systemId, day) + fp, day });
	}

	/** The system's loss ledger decayed to this day; 0 for one stamped after it (the frozen clock's, or a timeline loaded over). */
	protected static float lossFP(String systemId, float day) {
		float[] l = losses().get(systemId);
		if (l == null || l.length < 2 || l[1] > day) return 0f;
		return l[0] * decay(day - l[1]);
	}

	/** Whether the system lost ships in the last DECAY_DAYS. */
	protected static boolean lostLately(String systemId, float day) {
		float[] l = losses().get(systemId);
		return l != null && l.length >= 2 && l[1] <= day && day - l[1] < DECAY_DAYS;
	}

	protected static float decay(float days) {
		return threatinc.rules.PostureRules.decay(days, DECAY_DAYS);
	}

	// ------------------------------------------------------------------
	// the pass
	// ------------------------------------------------------------------

	/** One pass's reading of a system, for the log. */
	protected static class Reading {
		float attacks, staged, losses, hostiles, forward;
		String stagedBy;
		boolean wounded;
	}

	/** A system under attack and short of its need (poll): what recallStrikes may call strikes home for. */
	protected static class Call {
		StarSystemAPI system;
		/** Each colony's need less what it holds, inbound included. */
		Map<MarketAPI, Float> shortBy = new java.util.LinkedHashMap<MarketAPI, Float>();
		/** The system's need, what it is short of it, and its cheapest swarm - a gap smaller than that calls nothing. */
		float need, shortFP, swarm = Float.MAX_VALUE;
	}

	/**
	 * Every postureDays (IncursionManager.advance, before maintainGarrisons):
	 * reads every hive system's pressure and want, logs the systems whose
	 * posture changed, sets the expansion appetite and recycles surplus that
	 * has stood past the upkeep's break-even or that a waiting bill needs.
	 */
	public static void poll() {
		if (!enabled()) {
			COLONY.clear();
			appetite = -1f;
			return;
		}
		long now = Global.getSector().getClock().getTimestamp();
		if (lastPoll != Long.MIN_VALUE
				&& Global.getSector().getClock().getElapsedDaysSince(lastPoll) < ThreatIncConfig.postureDays()) {
			return;
		}
		lastPoll = now;
		float day = today();

		List<String> systemIds = new ArrayList<String>();
		for (String id : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (!ThreatIncData.getLiveColonyMarkets(id).isEmpty()) systemIds.add(id);
		}
		Set<String> hive = new HashSet<String>(systemIds);
		Set<CampaignFleetAPI> counted = new HashSet<CampaignFleetAPI>();
		// the stance reads the same pass (ThreatStance.evaluate)
		ThreatStance.Pass pass = new ThreatStance.Pass();
		Map<String, Float> attacks = attacksBySystem(hive, counted, pass);
		Map<String, StarSystemAPI> stagingMemo = new HashMap<String, StarSystemAPI>();
		List<ThreatFrontlines.Outpost> outposts = new ArrayList<ThreatFrontlines.Outpost>(ThreatFrontlines.all());

		float margin = ThreatIncConfig.npcSiegeOrbitMargin();
		if (margin <= 0f) margin = 1f;
		float postureMargin = Math.max(0f, ThreatIncConfig.postureMargin());
		float band = Math.max(0f, ThreatIncConfig.postureBand());
		float bill = ThreatColonyManager.foundingFP(ThreatColonyManager.FOUNDING_CORE_STRUCTURES + 1);
		// the swarm's own restraints, removed 2026-10-04 (docs/game-runs.md 4); each knob puts one back
		float stagedShare = Math.max(0f, ThreatIncConfig.postureStagedShare());
		boolean triage = ThreatIncConfig.postureTriage();
		boolean forgesHome = ThreatIncConfig.posturePressedForgesHome();
		Map<String, Float> sieges = ThreatIncConfig.postureNeedAtAttack() ? siegesOver() : null;
		boolean mass = ThreatIncConfig.postureMass();
		List<Call> calls = mass || ThreatIncConfig.postureRecallLY() > 0f ? new ArrayList<Call>() : null;

		COLONY.clear();
		CALLS.clear();
		sumHeld = sumWant = sumSurplus = 0f;
		nQuiet = nPressed = 0;
		float appetiteWant = 0f, appetiteSurplus = 0f;
		int forges = 0, forgesCovered = 0;

		// what the whole hive holds above its bases: all it could gather in one
		// system by stripping every other to its base (triage, below)
		float sectorHeld = 0f, sectorBase = 0f;
		for (String systemId : systemIds) {
			for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) {
				sectorHeld += ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
				sectorBase += baseFP(c);
			}
		}

		for (String systemId : systemIds) {
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			List<MarketAPI> colonies = ThreatIncData.getLiveColonyMarkets(systemId);
			Reading r = read(system, colonies, attacks, counted, stagingMemo, outposts, day, pass);
			// forces seen, not what a depot could pay (postureStagedShare, 0 since 2026-10-04): a
			// siege is sized to the swarm reported at its world, and the capacity read wrote
			// systems off against forces nobody sent (hw6b: Yma at 37,550 "staged")
			float raw = Math.max(r.attacks, r.staged * stagedShare) + r.losses + r.hostiles + r.forward;

			float[] prev = state().get(systemId);
			// a reading stamped after today is not this timeline's: start afresh
			boolean hadPrev = prev != null && prev.length >= S_LEN && prev[S_DAY] <= day;
			float pressure = raw;
			if (hadPrev) pressure = Math.max(raw, prev[S_PRESSURE] * decay(day - prev[S_DAY]));

			float floor = 0f, base = 0f, held = 0f, inbound = 0f, bank = 0f;
			float[] floors = new float[colonies.size()];
			float[] bases = new float[colonies.size()];
			float[] helds = new float[colonies.size()];
			for (int i = 0; i < colonies.size(); i++) {
				MarketAPI c = colonies.get(i);
				floors[i] = floorFP(c);
				floor += floors[i];
				bases[i] = baseFP(c);
				base += bases[i];
				helds[i] = ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
				held += helds[i];
				inbound += inboundFP(c.getId());
				bank += ThreatColonyManager.bankedFP(c);
			}
			float need = pressure / margin * postureMargin;
			// TRIAGE (postureTriage, off since 2026-10-04): a system the whole hive
			// could not hold even stripping every other to its base is not fed - its
			// want falls back to its base and the swarms it would have drained go on
			// elsewhere (ti-h8d: Corvus read a 37k want against a 40k-FP hive;
			// transfers sort the emptiest receiver first and would have stripped the
			// quiet hives into it). Off: hw6 wrote off every attacked system, the home
			// system and its 12.9k FP included, and each fell with what stood over it
			float gatherable = held + bank + Math.max(0f, (sectorHeld - held) - (sectorBase - base));
			boolean hopeless = triage && pressure / margin > gatherable;
			if (hopeless != INDEFENSIBLE.contains(systemId)) {
				if (hopeless) INDEFENSIBLE.add(systemId);
				else INDEFENSIBLE.remove(systemId);
				ThreatIncConfig.log("Posture: " + system.getName() + (hopeless ? " written off" : " defensible again")
						+ " - pressure " + (int) pressure + " needs " + (int) (pressure / margin)
						+ " FP, the hive could gather " + (int) gatherable);
			}
			if (hopeless) need = 0f;
			// each colony its base or its share of the need: where the attack has come
			// down, the worlds it is over (overWorlds), else split by the tables
			float[] over = sieges != null ? overWorlds(colonies, sieges, pressure) : null;
			float[] wants = new float[colonies.size()];
			float[] needs = new float[colonies.size()];
			float want = 0f;
			for (int i = 0; i < colonies.size(); i++) {
				float share = over != null ? over[i] : floor > 0f ? floors[i] / floor : 1f / colonies.size();
				needs[i] = need * share;
				wants[i] = Math.max(bases[i], needs[i]);
				want += wants[i];
			}

			// each mode entered at its higher figure and left only below its
			// lower one; a wound healed leaves a BESIEGED system THREATENED
			// until the ratio falls through that band too
			int was = hadPrev ? (int) prev[S_MODE] : QUIET;
			int mode;
			float ratio = held > 0f ? (pressure / margin) / held : (pressure > 0f ? Float.MAX_VALUE : 0f);
			if (r.wounded) mode = BESIEGED;
			else if (ratio >= THREATENED_ENTER || (was >= THREATENED && ratio >= THREATENED_LEAVE)) mode = THREATENED;
			else if (ratio >= WATCHFUL_ENTER || (was >= WATCHFUL && ratio >= WATCHFUL_LEAVE)) mode = WATCHFUL;
			else mode = QUIET;
			boolean attacked = r.attacks > 0f || r.hostiles > 0f || lostLately(systemId, day);
			// under attack and short of its need: the strikes that are convenient come home
			// (recallStrikes); massing, a world short of its own need calls too (massWithin)
			if (calls != null && attacked && (mass ? need > 0f : need > held)) {
				Call call = new Call();
				call.system = system;
				call.need = need;
				call.shortFP = need - held;
				for (int i = 0; i < colonies.size(); i++) {
					MarketAPI c = colonies.get(i);
					if (c.getPrimaryEntity() == null) continue;
					call.shortBy.put(c, needs[i] - helds[i]);
					call.swarm = Math.min(call.swarm, rowsFP(c, 0, 1));
				}
				boolean worldShort = false;
				if (mass) {
					for (Float gap : call.shortBy.values()) worldShort |= gap > call.swarm;
				}
				if (!call.shortBy.isEmpty() && (call.shortFP > call.swarm || worldShort)) calls.add(call);
			}

			float surplus = 0f;
			for (int i = 0; i < colonies.size(); i++) {
				MarketAPI c = colonies.get(i);
				COLONY.put(c.getId(), new float[] { wants[i], needs[i] });
				surplus += Math.max(0f, helds[i] - wants[i] * (1f + band) - oneSwarmFP(c));
			}
			float since = -1f;
			if (surplus > 0f) since = hadPrev && prev[S_SURPLUS_SINCE] >= 0f ? prev[S_SURPLUS_SINCE] : day;

			if (mode != was) {
				ThreatIncConfig.log("Posture: " + system.getName() + " " + MODE_NAMES[was] + "->" + MODE_NAMES[mode]
						+ " pressure " + (int) pressure + " (staged " + (int) r.staged
						+ (r.stagedBy != null ? " " + r.stagedBy + " g" + String.format("%.1f",
								ThreatAlarm.grudge(r.stagedBy)) : "")
						+ ", attacks " + (int) r.attacks + ", losses30d " + (int) r.losses
						+ ", hostiles " + (int) r.hostiles + ", forward " + (int) r.forward
						+ (r.wounded ? ", wounded" : "")
						// staged, attacks and forward are the swarm's reports, by trust (ThreatSwarmIntel)
						+ (ThreatSwarmIntel.enabled() ? ", as seen" : "")
						+ ") want " + (int) want + " (base " + (int) base
						+ ", need " + (int) need + ") held " + (int) held
						+ " (+" + (int) inbound + " inbound) bank " + (int) bank);
			}
			state().put(systemId, new float[] { pressure, want, since, day, mode, need, attacked ? 1f : 0f });
			pass.addSystem(systemId, held, pressure, mode, attacked);

			sumHeld += held;
			sumWant += want;
			sumSurplus += surplus;
			if (mode <= WATCHFUL) {
				nQuiet++;
				appetiteWant += want;
				// a bank past one rebuild of the system's want is idle strength
				// too (2026-09-29, ti-h8e: 71k FP banked against 47k held while
				// appetite read the fleets alone and sat at 0.1-0.3)
				appetiteSurplus += Math.max(0f, held - want) + Math.max(0f, bank - want);
			} else {
				nPressed++;
			}
			// a pressed system's forges count too unless they stay home (posturePressedForgesHome)
			if (mode <= WATCHFUL || !forgesHome) {
				for (MarketAPI c : colonies) {
					if (ThreatColonyManager.getForge(c) == null) continue;
					forges++;
					if (ThreatColonyManager.poolableFP(c) >= bill) forgesCovered++;
				}
			}
		}
		// a system the hive lost forgets its reading, and a ledger long quiet its losses
		for (String id : new ArrayList<String>(state().keySet())) {
			if (!hive.contains(id)) state().remove(id);
		}
		for (String id : new ArrayList<String>(losses().keySet())) {
			if (!hive.contains(id) || lossFP(id, day) < 1f) losses().remove(id);
		}
		for (String id : new ArrayList<String>(received().keySet())) {
			if (!within(received().get(id))) received().remove(id);
		}

		// what the quiet hives hold above their want, and banks that pay the next founding
		appetite = appetiteWant > 0f ? appetiteSurplus / appetiteWant : 0f;
		if (forges > 0) appetite += forgesCovered / (float) forges;
		nForges = forges;
		nForgesCovered = forgesCovered;

		// where the surplus goes (PRESS, EXPAND, CONSOLIDATE)
		ThreatStance.evaluate(pass, day, sumHeld);
		recycleSurplus(systemIds, day);
		if (calls != null) {
			// the system's own spare first, then the strikes that can turn, then its neighbours' spare
			if (mass) {
				for (Call call : calls) massWithin(call);
			}
			if (ThreatIncConfig.postureRecallLY() > 0f) recallStrikes(calls);
			if (mass) massFromNeighbours(calls, systemIds);
			CALLS.addAll(calls);
		}
	}

	/**
	 * Massing the defence, a system under attack sends the spare swarms of its
	 * own colonies (ThreatColonyManager.spareFleets, what a launch would
	 * muster) to its worlds short of their need by more than a swarm. The
	 * shortest world first, each taking the lightest fleet that covers its
	 * gap, else the heaviest. The call then stands at what its worlds still
	 * lack: a reserve on a sibling world is not over the world attacked.
	 * (2026-10-04, the user: "If you recommend that then do it". hw8 massed by
	 * launching a strike and recalling it days later, its fuel spent.)
	 * Knob: postureMass.
	 */
	protected static void massWithin(Call call) {
		Map<CampaignFleetAPI, MarketAPI> pool = new java.util.LinkedHashMap<CampaignFleetAPI, MarketAPI>();
		for (MarketAPI c : call.shortBy.keySet()) {
			for (CampaignFleetAPI f : ThreatColonyManager.spareFleets(c)) pool.put(f, c);
		}
		while (!pool.isEmpty()) {
			MarketAPI to = shortest(call);
			if (to == null || call.shortBy.get(to) <= call.swarm) break;
			float gap = call.shortBy.get(to);
			CampaignFleetAPI pick = pickFor(pool.keySet(), gap);
			MarketAPI donor = pool.remove(pick);
			float fp = pick.getFleetPoints();
			if (donor == to || !ThreatColonyManager.sendReinforcement(donor, to, pick)) continue;
			noteTransfer(to, pick);
			noteSent(fp);
			call.shortBy.put(to, gap - fp);
			call.shortBy.put(donor, call.shortBy.get(donor) + fp);
			ThreatIncConfig.log("Posture: " + donor.getName() + " massed " + (int) fp + " FP at " + to.getName()
					+ " (" + (int) gap + " FP short of " + (int) needFP(to) + ")");
		}
		float left = 0f;
		for (Float gap : call.shortBy.values()) left += Math.max(0f, gap);
		call.shortFP = left;
	}

	/**
	 * A system still short once its own spare is massed and the strikes in
	 * reach have turned (massWithin, recallStrikes) draws the spare swarms of
	 * the hive systems within postureRecallLY that are not calling themselves,
	 * the nearest first, each paying its passage (sendReinforcement) - the
	 * swarms hw8's recall took from the neighbours' strikes.
	 */
	protected static void massFromNeighbours(List<Call> calls, List<String> systemIds) {
		final float range = ThreatIncConfig.postureRecallLY();
		if (range <= 0f) return;
		Set<StarSystemAPI> calling = new HashSet<StarSystemAPI>();
		for (Call call : calls) calling.add(call.system);
		for (final Call call : calls) {
			if (call.shortFP <= call.swarm) continue;
			List<StarSystemAPI> near = new ArrayList<StarSystemAPI>();
			for (String id : systemIds) {
				StarSystemAPI s = Global.getSector().getStarSystem(id);
				if (s == null || calling.contains(s)) continue;
				if (Misc.getDistanceLY(s.getLocation(), call.system.getLocation()) <= range) near.add(s);
			}
			java.util.Collections.sort(near, new java.util.Comparator<StarSystemAPI>() {
				public int compare(StarSystemAPI a, StarSystemAPI b) {
					return Float.compare(Misc.getDistanceLY(a.getLocation(), call.system.getLocation()),
							Misc.getDistanceLY(b.getLocation(), call.system.getLocation()));
				}
			});
			for (StarSystemAPI s : near) {
				if (call.shortFP <= call.swarm) break;
				Map<CampaignFleetAPI, MarketAPI> pool = new java.util.LinkedHashMap<CampaignFleetAPI, MarketAPI>();
				for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(s.getId())) {
					if (c.getPrimaryEntity() == null) continue;
					for (CampaignFleetAPI f : ThreatColonyManager.spareFleets(c)) pool.put(f, c);
				}
				float ly = Misc.getDistanceLY(s.getLocation(), call.system.getLocation());
				while (!pool.isEmpty() && call.shortFP > call.swarm) {
					MarketAPI to = shortest(call);
					if (to == null || call.shortBy.get(to) <= 0f) break;
					float gap = call.shortBy.get(to);
					CampaignFleetAPI pick = pickFor(pool.keySet(), gap);
					MarketAPI donor = pool.remove(pick);
					float fp = pick.getFleetPoints();
					if (!ThreatColonyManager.canReinforce(donor, to)) continue;
					if (!ThreatColonyManager.sendReinforcement(donor, to, pick)) continue;
					noteTransfer(to, pick);
					noteSent(fp);
					ThreatReach.note("send", ly);
					call.shortBy.put(to, gap - fp);
					call.shortFP -= fp;
					ThreatIncConfig.log("Posture: " + donor.getName() + " massed " + (int) fp + " FP at "
							+ to.getName() + ", " + String.format("%.1f", ly) + " ly (" + (int) gap + " FP short of "
							+ (int) needFP(to) + ")");
				}
			}
		}
	}

	/** The call's world shortest of its need; null with none on the map. */
	protected static MarketAPI shortest(Call call) {
		MarketAPI to = null;
		float most = -Float.MAX_VALUE;
		for (Map.Entry<MarketAPI, Float> e : call.shortBy.entrySet()) {
			if (e.getKey().getPrimaryEntity() == null || e.getValue() <= most) continue;
			most = e.getValue();
			to = e.getKey();
		}
		return to;
	}

	/** The lightest fleet that covers the gap, else the heaviest. */
	protected static CampaignFleetAPI pickFor(java.util.Collection<CampaignFleetAPI> fleets, float gap) {
		CampaignFleetAPI covers = null, heaviest = null;
		for (CampaignFleetAPI f : fleets) {
			if (heaviest == null || f.getFleetPoints() > heaviest.getFleetPoints()) heaviest = f;
			if (f.getFleetPoints() >= gap && (covers == null || f.getFleetPoints() < covers.getFleetPoints())) covers = f;
		}
		return covers != null ? covers : heaviest;
	}

	/**
	 * Fleet points a strike staged in this system may muster while the defence
	 * is massed (postureMass): a strike the next pass would call home does not
	 * sail. Nothing while its own system, or one in recall range nearer than
	 * the target, was still short at the last pass (massWithin); from its own
	 * system, attacked, only what it holds above its need. Float.MAX_VALUE
	 * with neither. hw8: every strike launched after contact came home 0-5
	 * days later, its fuel spent.
	 *
	 * @param why takes the reason in [0] when the figure is not Float.MAX_VALUE; may be null
	 */
	public static float strikeCapFP(StarSystemAPI source, MarketAPI target, String[] why) {
		if (!enabled() || source == null || !ThreatIncConfig.postureMass()) return Float.MAX_VALUE;
		float range = ThreatIncConfig.postureRecallLY();
		float on = target != null ? Misc.getDistanceLY(source.getLocation(), target.getLocationInHyperspace())
				: Float.MAX_VALUE;
		for (Call call : CALLS) {
			if (call.system == null || call.shortFP <= call.swarm) continue;
			if (call.system != source) {
				float ly = Misc.getDistanceLY(source.getLocation(), call.system.getLocation());
				if (ly > range || on <= ly) continue;
			}
			if (why != null) why[0] = call.system.getName() + " is " + (int) call.shortFP + " FP short of " + (int) call.need;
			return 0f;
		}
		float[] s = state().get(source.getId());
		if (s == null || s.length < S_LEN || s[S_ATTACKED] <= 0f || s[S_NEED] <= 0f) return Float.MAX_VALUE;
		float held = 0f;
		for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(source.getId())) {
			held += ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
		}
		if (why != null) why[0] = source.getName() + " holds " + (int) held + " FP against a need of " + (int) s[S_NEED];
		return Math.max(0f, held - s[S_NEED]);
	}

	/**
	 * STRIKES COME HOME (2026-10-04, the user: "should be able to deviate
	 * those that are convenient", not "a strike that is already mid siege or
	 * close to its target"). A system under attack and short of its need calls
	 * in the strikes within postureRecallLY of it that can still be turned
	 * (ThreatStrikeFGI.recallable: preparing or travelling out) and are nearer
	 * it than their target - whichever hive launched them - the nearest first,
	 * the shortest system first, until it holds its need. Each comes whole:
	 * its fleets join the garrisons of the worlds shortest
	 * (ThreatStrikeFGI.recallTo). Knob: postureRecallLY, 0 = off.
	 */
	protected static void recallStrikes(List<Call> calls) {
		if (calls.isEmpty()) return;
		float range = ThreatIncConfig.postureRecallLY();
		java.util.Collections.sort(calls, new java.util.Comparator<Call>() {
			public int compare(Call a, Call b) {
				return Float.compare(b.shortFP, a.shortFP);
			}
		});
		Set<ThreatStrikeFGI> tried = new HashSet<ThreatStrikeFGI>();
		for (Call call : calls) {
			while (call.shortFP > call.swarm) {
				ThreatStrikeFGI best = null;
				float bestLY = Float.MAX_VALUE, bestOn = 0f;
				for (Object curr : new ArrayList<Object>(IncursionManager.getStrikeList())) {
					if (!(curr instanceof ThreatStrikeFGI)) continue;
					ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
					if (tried.contains(strike) || !strike.recallable()) continue;
					org.lwjgl.util.vector.Vector2f at = strike.hyperLocation();
					MarketAPI target = strike.firstTarget();
					if (at == null || target == null) continue;
					float ly = Misc.getDistanceLY(at, call.system.getLocation());
					if (ly > range || ly >= bestLY) continue;
					// nearer its target than the hive: it flies on
					float on = Misc.getDistanceLY(at, target.getLocationInHyperspace());
					if (on <= ly) continue;
					best = strike;
					bestLY = ly;
					bestOn = on;
				}
				if (best == null) break;
				tried.add(best);
				MarketAPI target = best.firstTarget();
				int fleets = best.isSpawnedFleets() ? best.getFleets().size() : best.getParams().fleetSizes.size();
				float sent = best.recallTo(call.shortBy);
				if (sent <= 0f) continue;
				ThreatIncConfig.log("Posture: strike recalled to " + call.system.getName() + " - " + (int) sent
						+ " FP in " + fleets + " fleet(s), " + String.format("%.1f", bestLY) + " ly out and "
						+ String.format("%.1f", bestOn) + " ly from " + target.getName() + "; the system was "
						+ (int) call.shortFP + " FP short of " + (int) call.need);
				call.shortFP -= sent;
			}
		}
	}

	/** Fleet points of reinforcements flying to this colony. */
	protected static float inboundFP(String marketId) {
		float fp = 0f;
		for (CampaignFleetAPI f : ThreatIncData.reinforcementFleets().values()) {
			if (f == null || !f.isAlive()) continue;
			if (marketId.equals(f.getMemoryWithoutUpdate().getString(ThreatColonyManager.REINFORCE_TARGET_KEY))) {
				fp += f.getFleetPoints();
			}
		}
		return fp;
	}

	/**
	 * Running attacks by hive system, in warship points: the fleets of every
	 * live siege booked on its worlds, and every sortie working it (hunts by
	 * their system, Support and Defend by their hive world). Each fleet once,
	 * into {@code counted}, so the hostiles-present sweep does not count it again.
	 *
	 * <p>In the swarm's fog (ThreatSwarmIntel, docs/threat-fog.md) the figure is
	 * the attacks it has seen bound for each system lately (contactsOn), at the
	 * fleet points it saw; a live force's fleets still go into {@code counted}
	 * while its contact is in sight (ThreatSwarmIntel.inSight) - the hostiles
	 * sweep reads only fleets in the system, which the hive's own eyes see, so
	 * it must not count a sighted attack a second time, nor miss an unsighted one.
	 */
	protected static Map<String, Float> attacksBySystem(Set<String> hive, Set<CampaignFleetAPI> counted,
			ThreatStance.Pass pass) {
		Map<String, Float> out = new HashMap<String, Float>();
		boolean fog = ThreatSwarmIntel.enabled();
		// fogged, the live forces below only mark their fleets counted (add, tally null)
		Map<String, Float> tally = fog ? null : out;
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			String systemId = null;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target != null && target.getStarSystem() != null && hive.contains(target.getStarSystem().getId())) {
					systemId = target.getStarSystem().getId();
					break;
				}
			}
			if (systemId == null) continue;
			// a booked siege counts from dispatch whether its fleets are spawned or not (user,
			// 2026-10-01: the swarm answers it the same wherever the player is): while its route
			// is abstract, by the flotilla it sails with, until its own fleets take over
			if (purge instanceof ThreatPurgeFGI && countsAbstract((ThreatPurgeFGI) purge)) {
				float fp = ((ThreatPurgeFGI) purge).abstractNow();
				if (fp > 0f && tally != null) {
					Float had = out.get(systemId);
					out.put(systemId, (had != null ? had : 0f) + fp);
					pass.addForce(purge.getFaction().getId(), systemId, fp);
				}
				continue;
			}
			// fogged, a force not yet in sight (no contact - the sweep is daily, this
			// pass may fall later the same day) leaves its fleets to the hostiles sweep
			if (fog && !ThreatSwarmIntel.inSight(ThreatSwarmIntel.siegeKey(purge))) continue;
			for (CampaignFleetAPI f : purge.getFleets()) {
				add(tally, systemId, f, counted, purge.getFaction().getId(), pass);
			}
		}
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			String systemId = null;
			if (ThreatFleetOrders.KIND_HUNT.equals(o.kind)) {
				systemId = ThreatSoftening.huntSystemId(o);
			} else if (ThreatFleetOrders.KIND_SUPPORT.equals(o.kind) || ThreatFleetOrders.KIND_DEFEND.equals(o.kind)) {
				MarketAPI world = ThreatIncData.resolveColonyMarket(o.targetId);
				if (world != null && world.getStarSystem() != null) systemId = world.getStarSystem().getId();
			}
			if (systemId == null || !hive.contains(systemId)) continue;
			if (fog && !ThreatSwarmIntel.inSight(ThreatSwarmIntel.orderKey(o.fleet))) continue;
			add(tally, systemId, o.fleet, counted, o.factionId, pass);
		}
		if (!fog) return out;
		// what the swarm has seen coming at each system, each force under its faction
		for (String systemId : hive) {
			for (ThreatSwarmIntel.Contact c : ThreatSwarmIntel.contactsOn(systemId)) {
				if (c == null || c.fp <= 0f) continue;
				Float had = out.get(systemId);
				out.put(systemId, (had != null ? had : 0f) + c.fp);
				pass.addForce(c.factionId, systemId, c.fp);
			}
		}
		return out;
	}

	/**
	 * Whether a booked siege counts by its abstract flotilla (abstractNow): not
	 * aborted or going home, and its fleets not yet all spawned - a spawn is
	 * one-way, and from then on its fleets count. The abstract figure carries its
	 * freighters and transports, so the count steps down a little at the spawn.
	 */
	protected static boolean countsAbstract(ThreatPurgeFGI purge) {
		if (!ThreatIncConfig.threatSeesBookedSieges() || purge.isAborted()) return false;
		if (purge.isSpawnedFleets() && !purge.isSpawning() && !purge.getFleets().isEmpty()) return false;
		return purge.getCurrentAction() == null
				|| !GenericRaidFGI.RETURN_ACTION.equals(purge.getCurrentAction().getId());
	}

	/**
	 * Hive world -> warship points of the sieges over it whose fleets have not
	 * spawned (abstractNow): they fight the swarms at the world off-screen
	 * (ThreatPurgeFGI's daily siege) with no fleet in the system for the
	 * hostiles sweep or overWorlds to read. A spawned siege's fleets are read
	 * where they are.
	 */
	protected static Map<String, Float> siegesOver() {
		Map<String, Float> out = new HashMap<String, Float>();
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI purge = (ThreatPurgeFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.isAborted()) continue;
			if (purge.isSpawnedFleets() && !purge.isSpawning() && !purge.getFleets().isEmpty()) continue;
			if (!purge.isCurrent(GenericRaidFGI.PAYLOAD_ACTION)) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			float fp = purge.abstractNow();
			if (fp <= 0f) continue;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target == null) continue;
				Float had = out.get(target.getId());
				out.put(target.getId(), (had != null ? had : 0f) + fp);
			}
		}
		return out;
	}

	/**
	 * A siege has come down on a hive world: the next pass reads the hive at
	 * once rather than up to postureDays later (ThreatPurgeFGI.takeDaily - an
	 * outnumbered garrison loses three quarters of itself a day).
	 */
	public static void alarm() {
		if (ThreatIncConfig.postureNeedAtAttack()) lastPoll = Long.MIN_VALUE;
	}

	/** A siege first seen bound for a hive system (ThreatSwarmIntel.note): the next pass reads the hive at once, so a strike it calls home turns that day. */
	public static void sighted() {
		if (ThreatIncConfig.postureRecallLY() > 0f) lastPoll = Long.MIN_VALUE;
	}

	/** A world is singled out only by a force of at least this share of its system's pressure: a scout passing moves nothing. */
	protected static final float OVER_MIN_SHARE = 0.1f;

	/**
	 * Each colony's share of its system's need once the attack has come down
	 * (2026-10-04, postureNeedAtAttack): by the warship points over each world -
	 * hostile fleets at it and the unspawned sieges on it (siegesOver) - a world
	 * with an army on it and nothing over it weighing as the average of those.
	 * Null while no world is singled out: the need is then split by the tables,
	 * as it always was. The orbit is contested at the world
	 * (ThreatGroundFronts.orbitHeld), not across the system, so split by the
	 * tables under attack the world a siege came down on fought with its own
	 * garrison while its siblings kept theirs (hw6b, the home system holding
	 * 12.9k FP: Alpha Laphirial II met a siege of 1,925 FP with 1,432 and
	 * Alpha Laphirial IV one of 1,968 with 956, each garrison gone in three days).
	 */
	protected static float[] overWorlds(List<MarketAPI> colonies, Map<String, Float> sieges, float pressure) {
		float[] w = new float[colonies.size()];
		float sum = 0f;
		int n = 0;
		for (int i = 0; i < w.length; i++) {
			MarketAPI c = colonies.get(i);
			Float siege = sieges.get(c.getId());
			w[i] = ThreatGroundFronts.hostilePointsNear(Factions.THREAT, c) + (siege != null ? siege : 0f);
			if (w[i] <= 0f) continue;
			sum += w[i];
			n++;
		}
		if (sum < pressure * OVER_MIN_SHARE) {
			java.util.Arrays.fill(w, 0f);
			sum = 0f;
			n = 0;
		}
		float each = n > 0 ? sum / n : 1f;
		for (int i = 0; i < w.length; i++) {
			if (w[i] > 0f || !ThreatGroundFronts.hasFront(colonies.get(i))) continue;
			w[i] = each;
			sum += each;
		}
		if (sum <= 0f) return null;
		for (int i = 0; i < w.length; i++) w[i] /= sum;
		return w;
	}

	/** Counts one attacking fleet toward its system, and toward its faction's force in reach (ThreatStance); with {@code out} null, only marks it counted. */
	protected static void add(Map<String, Float> out, String systemId, CampaignFleetAPI f, Set<CampaignFleetAPI> counted,
			String factionId, ThreatStance.Pass pass) {
		if (f == null || !f.isAlive() || !counted.add(f)) return;
		if (out == null) return;
		float fp = ThreatSoftening.combatFP(f);
		Float had = out.get(systemId);
		out.put(systemId, (had != null ? had : 0f) + fp);
		pass.addForce(factionId, systemId, fp);
	}

	/** One system's pressure, component by component. */
	protected static Reading read(StarSystemAPI system, List<MarketAPI> colonies, Map<String, Float> attacks,
			Set<CampaignFleetAPI> counted, Map<String, StarSystemAPI> stagingMemo,
			List<ThreatFrontlines.Outpost> outposts, float day, ThreatStance.Pass pass) {
		Reading r = new Reading();
		String systemId = system.getId();
		Float a = attacks.get(systemId);
		r.attacks = a != null ? a : 0f;

		// staged: what each base staging for this hive could pay a force here -
		// the most any one of a faction's bases could, faction by faction. Its
		// capacity, not the grudge (2026-10-01): capacity is already the worst a
		// faction can bring, and need = capacity / siege margin x postureMargin
		// out-holds it. Weighed by ThreatAlarm.targetMult the want rode the
		// grudge's ratchet - h38a held x5.5 on average (x14.7 once), 243 swarms
		// at a size-2 world, and sends burned half the fuel feeding frontier
		// worlds that fell anyway. The grudge picks strike targets (TARGETING)
		Map<String, Float> byFaction = new HashMap<String, Float>();
		boolean fog = ThreatSwarmIntel.enabled();
		if (fog) {
			// in the swarm's fog, what it last saw each base stage for this
			// system, by how far that sighting is still to be trusted
			for (ThreatSwarmIntel.Place p : ThreatSwarmIntel.places()) {
				if (p == null || p.factionId == null || !systemId.equals(p.stagesFor)) continue;
				float cap = p.stagedFP * ThreatSwarmIntel.trust(p);
				if (cap <= 0f) continue;
				Float had = byFaction.get(p.factionId);
				if (had == null || cap > had) byFaction.put(p.factionId, cap);
			}
		} else {
			for (MarketAPI base : IncursionManager.siegeBasesFor(system)) {
				StarSystemAPI staging;
				if (stagingMemo.containsKey(base.getId())) {
					staging = stagingMemo.get(base.getId());
				} else {
					staging = ThreatConvoys.stagingHive(base);
					stagingMemo.put(base.getId(), staging);
				}
				if (staging != system) continue;
				FactionAPI faction = base.getFaction();
				if (faction == null) continue;
				float cap = stagedCapacity(base, system);
				if (cap <= 0f) continue;
				Float had = byFaction.get(faction.getId());
				if (had == null || cap > had) byFaction.put(faction.getId(), cap);
			}
		}
		float top = 0f;
		for (Map.Entry<String, Float> e : byFaction.entrySet()) {
			pass.addForce(e.getKey(), systemId, e.getValue());
			float v = e.getValue();
			r.staged += v;
			if (v > top) {
				top = v;
				r.stagedBy = e.getKey();
			}
		}

		r.losses = lossFP(systemId, day);

		// hostile fleets in the system that no attack above already counted
		for (CampaignFleetAPI f : system.getFleets()) {
			if (f == null || !f.isAlive() || f.isStationMode() || counted.contains(f)) continue;
			if (f.getFaction() == null || Factions.THREAT.equals(f.getFaction().getId())) continue;
			if (!f.getFaction().isHostileTo(Factions.THREAT)) continue;
			r.hostiles += ThreatSoftening.combatFP(f);
		}

		// forward bases' guards facing this system (those inside it are hostiles
		// above) - each base counts toward its nearest hive system only
		// (ThreatFrontlines.hiveNear); counted toward every system in reach, one
		// guard pool read as 4,867 FP of pressure on a dozen systems at once
		if (fog) {
			// in the swarm's fog, the guards it last saw at each forward base, by trust
			for (ThreatSwarmIntel.Place p : ThreatSwarmIntel.places()) {
				if (p == null || p.guardsFP <= 0f) continue;
				MarketAPI m = Global.getSector().getEconomy().getMarket(p.marketId);
				if (m == null || !m.isInEconomy() || m.getStarSystem() == system) continue;
				if (ThreatFrontlines.hiveNear(m.getPrimaryEntity()) != system) continue;
				float fp = p.guardsFP * ThreatSwarmIntel.trust(p);
				if (fp <= 0f) continue;
				r.forward += fp;
				pass.addForce(p.factionId, systemId, fp);
			}
		} else {
			for (ThreatFrontlines.Outpost o : outposts) {
				MarketAPI m = ThreatFrontlines.marketOf(o);
				if (m == null || m.getStarSystem() == system) continue;
				if (ThreatFrontlines.hiveNear(m.getPrimaryEntity()) != system) continue;
				for (CampaignFleetAPI g : ThreatFrontlines.liveGuards(o)) {
					if (g.getContainingLocation() == system || counted.contains(g)) continue;
					float fp = ThreatSoftening.combatFP(g);
					r.forward += fp;
					pass.addForce(o.factionId, systemId, fp);
				}
			}
		}

		// wounds: a front on a world, an organ down, a hunt that just thinned it
		r.wounded = IncursionManager.recentlyThinned(systemId);
		for (MarketAPI c : colonies) {
			if (r.wounded) break;
			r.wounded = ThreatGroundFronts.hasFront(c) || ThreatColonyManager.anyOrganDisrupted(c);
		}
		return r;
	}

	// ------------------------------------------------------------------
	// recycling: the last road for surplus
	// ------------------------------------------------------------------

	/**
	 * Days a surplus fleet must stand idle before recycling it is cheaper than
	 * keeping it: (1 - hullShare) / garrisonUpkeepPerMonth months, 5 at the
	 * defaults. Never with no upkeep.
	 */
	public static float breakEvenDays() {
		return threatinc.rules.PostureRules.breakEvenDays(ThreatReturns.hullShare(),
				ThreatIncConfig.garrisonUpkeepPerMonth());
	}

	/**
	 * Recycles what each colony holds above want by the band and one swarm:
	 * one fleet a colony a pass once the system's surplus has stood past
	 * breakEvenDays, and at once, as much as it takes, where a bill waits on
	 * the colony's bank (a planner build, or the founding of a pending claim
	 * it would source). Weakest first, out of battle, only a fleet the surplus
	 * covers whole; a pass over the garrison at most, so it always ends.
	 */
	protected static void recycleSurplus(List<String> systemIds, float day) {
		Map<String, Float> bills = waitingBills();
		float breakEven = breakEvenDays();
		float share = ThreatReturns.hullShare();
		for (String systemId : systemIds) {
			float[] s = state().get(systemId);
			boolean stale = s != null && s[S_SURPLUS_SINCE] >= 0f && day - s[S_SURPLUS_SINCE] >= breakEven;
			for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) {
				Float owed = bills.get(c.getId());
				if (!stale && owed == null) continue;
				List<CampaignFleetAPI> fleets = ThreatIncData.garrisonsFor(c.getId());
				// the bill as much as it takes; past break-even, one fleet more
				int idle = stale ? 1 : 0;
				int budget = fleets.size();
				for (int i = 0; i < budget; i++) {
					if (owed == null || ThreatColonyManager.bankedFP(c) >= owed) {
						if (idle <= 0) break;
						idle--;
					}
					float spare = releasableFP(c, ThreatColonyManager.ownedFleetFP(c, fleets));
					if (spare <= 0f) break;
					CampaignFleetAPI victim = null;
					for (CampaignFleetAPI f : fleets) {
						if (f == null || !f.isAlive() || f.getBattle() != null) continue;
						if (f.getFleetPoints() > spare) continue;
						if (victim == null || f.getFleetPoints() < victim.getFleetPoints()) victim = f;
					}
					if (victim == null) break;
					boolean forBill = owed != null && ThreatColonyManager.bankedFP(c) < owed;
					// read before despawn: a despawned fleet reports 0 FP
					float fp = victim.getFleetPoints();
					fleets.remove(victim);
					victim.despawn();
					ThreatColonyManager.creditFP(c, fp * share);
					recycledFleets++;
					recycledFP += fp;
					ThreatIncConfig.log("Posture: " + c.getName() + " recycled a " + (int) fp + " FP surplus fleet ("
							+ (forBill ? "a bill waits" : "idle past break-even") + "; "
							+ (int) (fp * share) + " FP back, " + (int) ThreatColonyManager.bankedFP(c) + " FP banked)");
				}
			}
		}
	}

	/**
	 * Market id -> the bank each colony must reach for a bill waiting on it:
	 * one structure for a planner build that waits (buildWaiting), the next
	 * founding for the source a pending claim would draw its wave from.
	 */
	protected static Map<String, Float> waitingBills() {
		Map<String, Float> out = new HashMap<String, Float>();
		for (String id : ThreatColonyManager.buildWaiting().keySet()) {
			out.put(id, ThreatColonyManager.foundingFP(1));
		}
		float founding = ThreatColonyManager.foundingFP(ThreatColonyManager.FOUNDING_CORE_STRUCTURES + 1);
		for (Map.Entry<String, String> e : ThreatIncData.stages().entrySet()) {
			if (!ThreatIncData.STAGE_SEEDED.equals(e.getValue())) continue;
			if (ThreatIncData.bootstrapSeeds().contains(e.getKey())) continue;
			StarSystemAPI target = Global.getSector().getStarSystem(e.getKey());
			if (target == null) continue;
			MarketAPI source = ThreatColonyManager.pickForgeSource(target, true);
			if (source == null || ThreatColonyManager.poolableFP(source) >= founding) continue;
			Float had = out.get(source.getId());
			out.put(source.getId(), had != null ? Math.max(had, founding) : founding);
		}
		return out;
	}

	/**
	 * The staged threat one base puts on the hive system as the swarm sees it
	 * there - B's per-base figure (read) on the base's own stock alone, its
	 * donors' depots elsewhere and unseen: 0 when it does not stage for that
	 * system (ThreatConvoys.stagingHive). The swarm's sweep records it as it
	 * sees the base (ThreatSwarmIntel.record).
	 */
	public static float stagedBy(MarketAPI base, String hiveSystemId) {
		if (base == null || hiveSystemId == null) return 0f;
		StarSystemAPI staging = ThreatConvoys.stagingHive(base);
		if (staging == null || !hiveSystemId.equals(staging.getId())) return 0f;
		return stagedCapacity(base, staging, null);
	}

	/**
	 * What a base staging against {@code system} could pay a force there: its
	 * siege capacity, or half what it could pay a hunt for a base that stages
	 * for one (ThreatConvoys.stagesForHunt). 0 for none, or an unreadable figure.
	 */
	protected static float stagedCapacity(MarketAPI base, StarSystemAPI system) {
		FactionAPI faction = base.getFaction();
		if (faction == null) return 0f;
		return stagedCapacity(base, system, ThreatSoftening.huntDonors(faction, base));
	}

	/** stagedCapacity with the donors pooled into it ({@code null}: the base's own stock alone). */
	protected static float stagedCapacity(MarketAPI base, StarSystemAPI system, List<MarketAPI> donors) {
		if (base.getFaction() == null) return 0f;
		float cap = ThreatConvoys.stagesForHunt(base)
				? 0.5f * ThreatSoftening.payableFP(base, system, donors)
				: siegeCapacityFP(base, system, donors);
		if (Float.isNaN(cap) || Float.isInfinite(cap) || cap <= 0f) return 0f;
		return cap;
	}

	/**
	 * Fleet points a base staging a siege against {@code system} could send:
	 * its stock above the floor, staging bank included (ThreatReserves.available),
	 * plus what the donors can spare, at the siege's rates per point. Not
	 * ThreatSoftening.payableFP - that keeps the staging bank back, so every
	 * base stocking for its siege read 0 and no system ever felt it.
	 */
	protected static float siegeCapacityFP(MarketAPI base, StarSystemAPI system, List<MarketAPI> donors) {
		if (base.getStarSystem() == null) return 0f;
		float dist = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
		float fuelPerPoint = dist * ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float points = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) {
			points = Math.min(points, (ThreatReserves.available(base, Commodities.FUEL)
					+ ThreatSoftening.donorsSpendable(donors, base, Commodities.FUEL)) / fuelPerPoint);
		}
		if (suppliesPerPoint > 0f) {
			points = Math.min(points, (ThreatReserves.available(base, Commodities.SUPPLIES)
					+ ThreatSoftening.donorsSpendable(donors, base, Commodities.SUPPLIES)) / suppliesPerPoint);
		}
		return points >= Float.MAX_VALUE ? 0f : points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
	}

	// ------------------------------------------------------------------
	// the monthly line (ThreatColonyManager.flushUpkeepMonth, from the census)
	// ------------------------------------------------------------------

	public static void logMonth() {
		if (!enabled() || appetite < 0f) return;
		int claims = 0;
		for (Map.Entry<String, String> e : ThreatIncData.stages().entrySet()) {
			if (ThreatIncData.STAGE_SEEDED.equals(e.getValue()) && !ThreatIncData.bootstrapSeeds().contains(e.getKey())) {
				claims++;
			}
		}
		ThreatIncConfig.log("Posture sector: held " + k(sumHeld) + " want " + k(sumWant) + " surplus " + k(sumSurplus)
				+ " (" + nQuiet + " quiet/" + nPressed + " threatened); " + ThreatColonyManager.hiveLedgerSummary()
				+ "; appetite " + String.format("%.2f", appetite) + "; claims " + claims
				+ "; sent " + sentFleets + " (" + k(sentFP) + "), consumed " + consumedSwarms
				+ ", recycled " + recycledFleets + " (" + k(recycledFP) + ")" + ThreatStance.monthLine()
				+ "; forges paying " + nForgesCovered + "/" + nForges + "; banks " + topBanks(4));
		sentFleets = consumedSwarms = recycledFleets = 0;
		sentFP = recycledFP = 0f;
	}

	/** The n largest colony banks, "name fp" each, for the monthly line. */
	protected static String topBanks(int n) {
		List<MarketAPI> all = new ArrayList<MarketAPI>(ThreatIncData.getAllLiveColonyMarkets());
		java.util.Collections.sort(all, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(ThreatColonyManager.bankedFP(b), ThreatColonyManager.bankedFP(a));
			}
		});
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(n, all.size()); i++) {
			if (i > 0) sb.append(", ");
			sb.append(all.get(i).getName()).append(' ').append((int) ThreatColonyManager.bankedFP(all.get(i)));
		}
		return sb.toString();
	}

	protected static String k(float fp) {
		return String.format("%.1fk", fp / 1000f);
	}
}
