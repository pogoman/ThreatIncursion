package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * PLAYER AID (docs/player-aid.md sections 2 and 3): what the player sends
 * from their own colonies to other factions, and what they earn for it.
 *
 * <p>Every aid is a real fleet launched from a player colony through the war
 * board - a guard task force ({@link ThreatFleetOrders}, flagged aid) or a
 * convoy ({@link ThreatConvoys}, flagged aid) - built from what that colony
 * can field ({@link ThreatAidCapacity}) and stock ({@link ThreatReserves}).
 * The source is picked here: the closest player colony with a military
 * structure in reach that covers the whole need, else the one that can send
 * the most. Credits pay for the fleet (per point and per light-year), never
 * for capability. The player's personal fleet takes no part in board aid; it
 * has its own route, the station commander ({@link ThreatincAidCMD}).
 *
 * <p>Reputation with the receiving faction is earned on arrival: by the
 * goods' value at the receiving market's price for a delivery, flat amounts
 * for a guard on station and a guard that serves its term, and a split among
 * every faction in a hive's strike reach for a task force at its door.
 */
public class ThreatAid {

	/** What one aid button would do right now: source, size, price - or why not. */
	public static class Quote {
		public MarketAPI source;
		/** Combat fleet points of a task force. */
		public float fp;
		/** Ledger points the fleet holds. */
		public float points;
		public String commodityId;
		/** Convoy load in ThreatReserves.COMMODITIES order. */
		public float[] load;
		public int quantity;
		public int need;
		public float distLY;
		public String reason;

		public boolean ok() {
			return reason == null && source != null;
		}
	}

	// ------------------------------------------------------------------
	// who may receive
	// ------------------------------------------------------------------

	/** Why the player cannot aid this faction, or null if they can. */
	public static String canAid(FactionAPI faction) {
		if (faction == null) return "No faction.";
		if (!ThreatAidCapacity.enabled()) return "Player aid is disabled in the mod settings.";
		if (faction.isPlayerFaction()) return "That is your own faction - order its fleets directly.";
		if (!ThreatWarState.isAtWar(faction)) {
			return faction.getDisplayName() + " is not mobilised - the Threat has not struck it.";
		}
		FactionAPI player = Global.getSector().getPlayerFaction();
		if (faction.isHostileTo(player)) {
			return faction.getDisplayName() + " is at war with you: its patrols would fire on "
					+ "anything you sent.";
		}
		if (faction.getRelToPlayer().getRel() < ThreatIncConfig.aidMinRelation()) {
			return faction.getDisplayName() + " will not accept your help at this standing.";
		}
		return null;
	}

	// ------------------------------------------------------------------
	// sources
	// ------------------------------------------------------------------

	/**
	 * Player colonies with a military structure and a Waystation, nearest
	 * the spot first. No range (2026-09-05, the user's call): a far colony
	 * may send, and pays in fuel drawn by the distance and in the time the
	 * fleet takes to get there and back.
	 */
	public static List<MarketAPI> sources(final Vector2f hyperLoc) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (hyperLoc == null) return result;
		for (MarketAPI m : Misc.getPlayerMarkets(false)) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null) continue;
			if (!IncursionManager.isBase(m)) continue;
			result.add(m);
		}
		Collections.sort(result, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), hyperLoc),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), hyperLoc));
			}
		});
		return result;
	}

	/**
	 * Combat points a task force from this colony sails with: EVERYTHING it
	 * has free, staged task forces included (2026-09-05 evening, the user's
	 * call - a task force capped at guardFleetFP "is a pretty shit fleet and
	 * will never have any capitals"). guardFleetFP is now only the NPC size
	 * and the bar for picking a source first (pickTaskForceSource).
	 */
	public static float taskForceFP(MarketAPI base) {
		return taskForceFP(base, false);
	}

	/** As above; {@code ownOnly} sizes by the colony's own free points - a guard over itself, which nothing staged there folds into. */
	public static float taskForceFP(MarketAPI base, boolean ownOnly) {
		if (base == null) return 0f;
		if (!ThreatAidCapacity.enabled()) return ThreatIncConfig.guardFleetFP();
		float free = (ownOnly ? ThreatAidCapacity.ownFreeFP(base) : ThreatAidCapacity.freeFP(base))
				/ ThreatAidCapacity.TASK_FORCE_HULL_MULT;
		return Math.max(0f, free);
	}

	/**
	 * As {@link #taskForceFP} but in whole-fleet points, support hulls
	 * included - the FP column's unit, and the only figure the board quotes
	 * for a task force (a prompt that said 199 beside a cell that said 239
	 * was the combat points, 2026-09-05 evening).
	 */
	public static float taskForcePoints(MarketAPI base, boolean ownOnly) {
		return ThreatAidCapacity.taskForcePoints(taskForceFP(base, ownOnly));
	}

	/**
	 * The colony to send a task force from: the closest with at least
	 * guardFleetFP free (it sails with all it has free), else the one with
	 * the most free points if that makes at least aidGuardMinFP, else null.
	 */
	public static MarketAPI pickTaskForceSource(Vector2f hyperLoc) {
		return pickTaskForceSource(hyperLoc, null);
	}

	/**
	 * As above; {@code self} is the colony being guarded when it is one of
	 * the player's own, sized by its own free points (it is the closest
	 * source to itself, so a colony with own points free guards itself).
	 */
	public static MarketAPI pickTaskForceSource(Vector2f hyperLoc, MarketAPI self) {
		MarketAPI best = null;
		float bestFP = 0f;
		for (MarketAPI m : sources(hyperLoc)) {
			float fp = taskForceFP(m, m == self);
			if (fp >= ThreatIncConfig.guardFleetFP()) return m;
			if (fp > bestFP) {
				bestFP = fp;
				best = m;
			}
		}
		if (best != null && bestFP >= ThreatIncConfig.aidGuardMinFP()) return best;
		return null;
	}

	protected static String noSourceReason(Vector2f hyperLoc, String what) {
		List<MarketAPI> inReach = sources(hyperLoc);
		if (inReach.isEmpty()) {
			return "None of your colonies with a military structure (Patrol HQ, Military Base or "
					+ "High Command) is in reach of " + what + ".";
		}
		StringBuilder sb = new StringBuilder("No colony of yours in reach can field it: ");
		List<String> parts = new ArrayList<String>();
		for (MarketAPI m : inReach) {
			parts.add(m.getName() + " " + (int) Math.max(0f, ThreatAidCapacity.freeFP(m)) + " FP free");
		}
		return sb.append(ThreatWarBoard.join(parts)).append(".").toString();
	}

	// ------------------------------------------------------------------
	// quotes
	// ------------------------------------------------------------------

	public static Quote quoteDefend(MarketAPI target) {
		return quoteDefend(ThreatBases.of(target));
	}

	/** As above for either kind of base: a colony, or the player's outpost. */
	public static Quote quoteDefend(ThreatBases.Base target) {
		Quote q = new Quote();
		if (target == null || target.starSystem() == null) {
			q.reason = "No target.";
			return q;
		}
		return quoteTaskForce(q, target.hyperLoc(), target.name(),
				target.market != null && target.market.isPlayerOwned() ? target.market : null);
	}

	public static Quote quoteStrike(StarSystemAPI hive) {
		Quote q = new Quote();
		if (hive == null) {
			q.reason = "No target.";
			return q;
		}
		return quoteTaskForce(q, hive.getLocation(), "the " + hive.getNameWithLowercaseType(), null);
	}

	protected static Quote quoteTaskForce(Quote q, Vector2f hyperLoc, String what, MarketAPI self) {
		q.source = pickTaskForceSource(hyperLoc, self);
		if (q.source == null) {
			q.reason = noSourceReason(hyperLoc, what) + " A task force needs at least "
					+ (int) (ThreatIncConfig.aidGuardMinFP() * ThreatAidCapacity.TASK_FORCE_HULL_MULT)
					+ " FP.";
			return q;
		}
		q.fp = taskForceFP(q.source, q.source == self);
		q.points = ThreatAidCapacity.taskForcePoints(q.fp);
		q.distLY = Misc.getDistanceLY(q.source.getStarSystem().getLocation(), hyperLoc);
		return q;
	}

	/**
	 * A resupply of what the colony is shortest of: the request's remaining
	 * quantity if one is open, else its deficit in items. Sends the smaller
	 * of the load tier's ask, the source's stock above its floor and what
	 * its free points can carry, from the closest colony that covers the ask
	 * or the one that sends the most.
	 */
	public static Quote quoteResupply(MarketAPI target, int tier) {
		Quote q = new Quote();
		if (target == null || target.getStarSystem() == null) {
			q.reason = "No target.";
			return q;
		}
		q.commodityId = ThreatAidRequests.worstShortage(target);
		if (q.commodityId == null) {
			q.reason = target.getName() + " is not short of marines, armaments, fuel or supplies.";
			return q;
		}
		ThreatAidMissionIntel request = ThreatAidMissionIntel.find(target.getId(),
				ThreatAidMissionIntel.KIND_AID, q.commodityId);
		q.need = request != null && request.remaining() > 0 ? request.remaining()
				: ThreatAidRequests.needItems(target, q.commodityId);
		if (q.need <= 0) {
			q.reason = target.getName() + " is not short of " + ThreatReserves.label(q.commodityId) + ".";
			return q;
		}
		// the board's load tier: what the colony is short of (Min), that times
		// convoyExtraLoadFactor (Med), or everything the source can spare and
		// carry (Max). The need itself is what the board and the prompt quote.
		float ask = tier >= 2 ? Float.MAX_VALUE
				: tier == 1 ? q.need * ThreatIncConfig.convoyExtraLoadFactor() : q.need;
		int idx = index(q.commodityId);
		Vector2f loc = target.getLocationInHyperspace();
		MarketAPI best = null;
		float[] bestLoad = null;
		int bestQty = 0;
		for (MarketAPI m : sources(loc)) {
			float avail = ThreatReserves.available(m, q.commodityId);
			if (avail < 1f) continue;
			float[] load = new float[ThreatReserves.COMMODITIES.length];
			load[idx] = Math.min(ask, avail);
			// a convoy sails on the colony's own hulls: staged warships do not fold into it
			float[] fitted = ThreatAidCapacity.fitLoad(ThreatAidCapacity.ownFreeFP(m), load);
			int qty = (int) fitted[idx];
			if (qty < 1) continue;
			if (qty >= ask) {
				best = m;
				bestLoad = fitted;
				bestQty = qty;
				break;
			}
			if (qty > bestQty) {
				best = m;
				bestLoad = fitted;
				bestQty = qty;
			}
		}
		if (best == null) {
			List<MarketAPI> inReach = sources(loc);
			if (inReach.isEmpty()) {
				q.reason = noSourceReason(loc, target.getName());
			} else {
				List<String> parts = new ArrayList<String>();
				for (MarketAPI m : inReach) {
					parts.add(m.getName() + " " + (int) ThreatReserves.available(m, q.commodityId)
							+ " spare, " + (int) Math.max(0f, ThreatAidCapacity.ownFreeFP(m)) + " FP free");
				}
				q.reason = "No colony of yours in reach can spare and carry "
						+ ThreatReserves.label(q.commodityId) + " for " + target.getName() + ": "
						+ ThreatWarBoard.join(parts) + ".";
			}
			return q;
		}
		q.source = best;
		q.load = bestLoad;
		q.quantity = bestQty;
		q.points = ThreatAidCapacity.convoyPoints(bestLoad);
		q.distLY = Misc.getDistanceLY(best.getStarSystem().getLocation(), loc);
		return q;
	}

	public static int index(String commodityId) {
		for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
			if (ThreatReserves.COMMODITIES[i].equals(commodityId)) return i;
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// dispatch (the board's buttons). No credits change hands: the fleet is
	// paid by the source colony's reserve and its capacity (decided
	// 2026-09-05, docs/player-aid.md section 2).
	// ------------------------------------------------------------------

	public static boolean dispatchDefend(MarketAPI target) {
		if (target == null) return false;
		String blocked = canAid(target.getFaction());
		if (blocked != null) {
			ThreatColonyManager.announceAlways(blocked, Misc.getNegativeHighlightColor());
			return false;
		}
		Quote q = quoteDefend(target);
		if (!q.ok()) {
			ThreatColonyManager.announceAlways(q.reason, Misc.getNegativeHighlightColor());
			return false;
		}
		ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuard(
				Global.getSector().getPlayerFaction(), target, q.source, true);
		if (o == null) {
			ThreatColonyManager.announceAlways("No task force could be raised at " + q.source.getName()
					+ " right now.", Misc.getNegativeHighlightColor());
			return false;
		}
		o.recipientFactionId = target.getFactionId();
		ThreatColonyManager.announceAlways("Your task force from " + q.source.getName() + " ("
				+ (int) q.points + " FP) sails to defend " + target.getName() + " for the "
				+ target.getFaction().getDisplayName() + ".", Misc.getHighlightColor());
		return true;
	}

	public static boolean dispatchStrike(StarSystemAPI hive) {
		if (hive == null) return false;
		Quote q = quoteStrike(hive);
		if (!q.ok()) {
			ThreatColonyManager.announceAlways(q.reason, Misc.getNegativeHighlightColor());
			return false;
		}
		ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchIntercept(
				Global.getSector().getPlayerFaction(), hive, q.source, true);
		if (o == null) {
			ThreatColonyManager.announceAlways("No task force could be raised at " + q.source.getName()
					+ " right now.", Misc.getNegativeHighlightColor());
			return false;
		}
		ThreatColonyManager.announceAlways("Your task force from " + q.source.getName() + " ("
				+ (int) q.points + " FP) sails to hold the door of the " + hive.getNameWithLowercaseType()
				+ ".", Misc.getHighlightColor());
		return true;
	}

	public static boolean dispatchResupply(MarketAPI target, int tier, Random random) {
		if (target == null) return false;
		String blocked = canAid(target.getFaction());
		if (blocked != null) {
			ThreatColonyManager.announceAlways(blocked, Misc.getNegativeHighlightColor());
			return false;
		}
		Quote q = quoteResupply(target, tier);
		if (!q.ok()) {
			ThreatColonyManager.announceAlways(q.reason, Misc.getNegativeHighlightColor());
			return false;
		}
		ThreatConvoys.Convoy c = ThreatConvoys.dispatch(q.source, target,
				Global.getSector().getPlayerFaction(), q.load, random, target.getFactionId(), true);
		if (c == null) {
			ThreatColonyManager.announceAlways("No convoy could be raised at " + q.source.getName()
					+ " right now.", Misc.getNegativeHighlightColor());
			return false;
		}
		ThreatColonyManager.announceAlways("Your convoy from " + q.source.getName() + " sails with "
				+ Misc.getWithDGS(q.quantity) + " " + ThreatReserves.label(q.commodityId) + " for "
				+ target.getName() + " (" + target.getFaction().getDisplayName() + ").",
				Misc.getHighlightColor());
		return true;
	}

	// ------------------------------------------------------------------
	// arrivals: reputation and contracts
	// ------------------------------------------------------------------

	/** One unit's price to the receiving market, times the quantity. */
	public static float valueAt(MarketAPI market, String commodityId, float quantity) {
		if (market == null || commodityId == null || quantity <= 0f) return 0f;
		float perUnit = 0f;
		try {
			perUnit = market.getDemandPrice(commodityId, 1.0, true);
		} catch (Throwable t) {
			perUnit = 0f;
		}
		if (perUnit <= 0f && market.getCommodityData(commodityId) != null) {
			perUnit = market.getCommodityData(commodityId).getCommodity().getBasePrice();
		}
		return perUnit * quantity;
	}

	/** Standing with the faction: points are hundredths of the -1..1 scale. */
	public static void rep(String factionId, float points, TextPanelAPI text) {
		if (factionId == null || points <= 0f) return;
		if (Factions.PLAYER.equals(factionId)) return;
		CustomRepImpact impact = new CustomRepImpact();
		impact.delta = points * 0.01f;
		Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, text != null),
				factionId);
	}

	/**
	 * Goods landed at another faction's colony, by convoy or by hand. The
	 * matching request takes them (accepting itself if only posted); standing
	 * follows the goods' value at the receiving market, doubled when they
	 * answer a contract.
	 */
	public static void onDelivered(MarketAPI base, String recipientFactionId, int marines,
			int armaments, int fuel, int supplies, boolean viaFleet, TextPanelAPI text) {
		if (base == null || recipientFactionId == null) return;
		if (Factions.PLAYER.equals(recipientFactionId)) return;
		int[] qty = {marines, armaments, fuel, supplies};
		float value = 0f;
		boolean credited = false;
		for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
			if (qty[i] <= 0) continue;
			value += valueAt(base, ThreatReserves.COMMODITIES[i], qty[i]);
			if (ThreatAidMissionIntel.creditDelivery(base, ThreatReserves.COMMODITIES[i], qty[i])) {
				credited = true;
			}
		}
		float points = Math.min(ThreatIncConfig.aidRepMaxPerDelivery(),
				value / Math.max(1f, ThreatIncConfig.aidRepPerCredits()));
		if (credited) points *= ThreatIncConfig.missionRepMult();
		rep(recipientFactionId, points, text);
		FactionAPI faction = Global.getSector().getFaction(recipientFactionId);
		String who = faction != null ? faction.getDisplayName() : recipientFactionId;
		if (viaFleet) {
			ThreatColonyManager.announceAlways("Your aid convoy has landed "
					+ ThreatFactionView.cargoText(marines, armaments, fuel, supplies) + " at "
					+ base.getName() + " for the " + who + ".", Misc.getHighlightColor());
		}
		ThreatIncConfig.log("Aid delivered at " + base.getName() + " (" + recipientFactionId + "): "
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, " + supplies
				+ " supplies - value " + (int) value + ", rep +" + points
				+ (credited ? " (contract)" : ""));
	}

	public static void onGuardArrived(ThreatFleetOrders.Order o) {
		if (o == null || o.recipientFactionId == null) return;
		rep(o.recipientFactionId, ThreatIncConfig.aidRepGuardArrived(), null);
		ThreatColonyManager.announceAlways("Your task force is on station over " + o.targetName
				+ " for the " + ThreatWarState.displayName(o.recipientFactionId) + ".",
				Misc.getHighlightColor());
	}

	public static void onGuardCompleted(ThreatFleetOrders.Order o) {
		if (o == null || o.recipientFactionId == null) return;
		rep(o.recipientFactionId, ThreatIncConfig.aidRepGuardCompleted(), null);
		ThreatColonyManager.announceAlways("Your task force has served its term over " + o.targetName
				+ " and is returning home.", Misc.getHighlightColor());
	}

	/** A task force at a hive's door: every faction with a colony in the hive's strike reach shares the credit. */
	public static void onStrikeArrived(ThreatFleetOrders.Order o) {
		if (o == null) return;
		StarSystemAPI hive = ThreatWarBoard.getSystem(o.targetId);
		Set<String> factions = factionsInStrikeReach(hive);
		if (factions.isEmpty()) return;
		float each = ThreatIncConfig.aidRepFrontTotal() / factions.size();
		for (String id : factions) rep(id, each, null);
		ThreatColonyManager.announceAlways("Your task force holds the door of the "
				+ (hive != null ? hive.getNameWithLowercaseType() : "hive system") + " - noted by "
				+ names(factions) + ".", Misc.getHighlightColor());
	}

	/** Non-Threat, non-player factions with a colony within any of the hive system's colonies' strike reach. */
	public static Set<String> factionsInStrikeReach(StarSystemAPI hive) {
		Set<String> result = new LinkedHashSet<String>();
		if (hive == null) return result;
		float range = 0f;
		for (MarketAPI colony : ThreatIncData.getLiveColonyMarkets(hive.getId())) {
			range = Math.max(range, IncursionManager.expeditionRangeLY(colony));
		}
		if (range <= 0f) return result;
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (m.getStarSystem() == null || m.isHidden()) continue;
			if (m.getFaction() == null || m.isPlayerOwned()) continue;
			if (Factions.THREAT.equals(m.getFactionId())) continue;
			if (Misc.getDistanceLY(m.getStarSystem().getLocation(), hive.getLocation()) > range) continue;
			result.add(m.getFactionId());
		}
		return result;
	}

	protected static String names(Set<String> factionIds) {
		List<String> parts = new ArrayList<String>();
		for (String id : factionIds) parts.add(ThreatWarState.displayName(id));
		return ThreatWarBoard.join(parts);
	}

	// ------------------------------------------------------------------
	// presence, for the defence contract
	// ------------------------------------------------------------------

	/** The player's fleet, or a player guard on station, in the colony's system. */
	public static boolean playerAssetIn(MarketAPI market) {
		if (market == null || market.getContainingLocation() == null) return false;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && player.getContainingLocation() == market.getContainingLocation()) {
			return true;
		}
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.aid || !ThreatFleetOrders.KIND_GUARD.equals(o.kind)) continue;
			if (!market.getId().equals(o.targetId)) continue;
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			if (o.fleet.getContainingLocation() == market.getContainingLocation()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// the board's lists
	// ------------------------------------------------------------------

	/** Player aid task forces bound for this faction's colonies (or, with null, the front). */
	public static List<ThreatFleetOrders.Order> aidOrdersFor(String recipientFactionId) {
		List<ThreatFleetOrders.Order> result = new ArrayList<ThreatFleetOrders.Order>();
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.aid) continue;
			if (recipientFactionId == null ? o.recipientFactionId == null
					: recipientFactionId.equals(o.recipientFactionId)) result.add(o);
		}
		return result;
	}

	public static List<ThreatConvoys.Convoy> aidConvoysFor(String recipientFactionId) {
		List<ThreatConvoys.Convoy> result = new ArrayList<ThreatConvoys.Convoy>();
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (!c.aid) continue;
			if (recipientFactionId != null && recipientFactionId.equals(c.recipientFactionId)) result.add(c);
		}
		return result;
	}

	/** Unused-import guard. */
	@SuppressWarnings("unused")
	private static void keep(Commodities c) {
	}
}
