package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * REQUESTS FOR AID (reworked 2026-10-29 evening, the user: "i want to rework the request and aid
 * system"; docs/war-effort-rework.md section 3). A request is logged from the war board when a
 * Transfer or Deploy at the colonies table's levers cannot meet what the player's colony needs
 * ({@link #transferShortfall}, {@link #deployShortfall}; the yes / no prompt is
 * the confirm box {@link ThreatIncursionIntel#buttonPressConfirmed} opens): one request a world, a {@link Part} a good - or the task force,
 * {@link ThreatNeeds#FLEETS}, a Deploy's relief - at the lever's tier. It stands until every part
 * has landed or the player withdraws it ({@link #withdraw}), however long that takes. Every
 * candidate faction (Favourable or better, not hostile, {@link #candidates}) looks at it once a
 * day ({@link #check}, aidRequestCheckDays apart, on its own clock) and ACCEPTS a part it has the
 * capacity for ({@link #tryAccept}): Max is all that ONE colony of its spares of the good, Med
 * half of it (the colony sparing the most in reach, {@link #donors}); the task force is that share
 * of the hulls its navy has free, as a guard from its nearest base. The convoy or fleet sails at
 * once with an ETA ({@link #eta}). Nothing while the share of its worlds the Threat besets
 * outweighs the player's standing with it ({@link #willingness}). The price is standing, a WHOLE
 * number of points charged on receipt ({@link #charge}: the goods' value at the player's market
 * over aidRepPerCredits, a guard's points x aidRepGuardArrived / 100, times the tier's price,
 * aidAskPriceMed / Max), never below Favourable, set straight on the relationship so vanilla plays
 * neither its message nor its sound. A convoy or fleet lost at sea costs nothing and the part
 * reopens ({@link #settleLost}). The faction boards list the open requests
 * (ThreatFactionView.addRequests), the player's tab with every answer, a faction's tab with its
 * own. Before this (2026-10-23 to 2026-10-29 noon) a Request was a button on a sieged world's row,
 * answered once after a fixed wait by every faction at a willingness; its answers were one intel
 * entry (ThreatAidAnswersIntel, retired).
 *
 * <p>The mission board is gone since 2026-10-23: until then this class posted vanilla missions
 * ({@link ThreatAidMissionIntel}) for the same shortages. Its readers of a colony's state -
 * {@link #shortageStanding}, {@link #requestItems}, {@link #needsDefence} - feed the ledger.
 */
public class ThreatAidRequests {

	/** Persistent: "marketId:commodityId" -> when its deficit was first seen. */
	public static final String KEY_SHORTAGE = "threatinc_aidShortageSince";
	/** Persistent: the player's requests. */
	public static final String KEY_CALLS = "threatinc_aidCalls";
	/** Persistent until 2026-10-29 evening (the one-request-a-world cooldown); cleared on the first tick. */
	public static final String KEY_ASKED = "threatinc_aidAskedAt";

	/** One part of a request: one good, or the task force ({@link ThreatNeeds#FLEETS}), at a lever's tier. */
	public static class Part {
		/** A commodity id (ThreatReserves.AID_COMMODITIES), or ThreatNeeds.FLEETS. */
		public String good;
		/** 1 Med - half of what the answering colony spares; 2 Max - all of it. */
		public int tier;
		/** The faction that accepted it; null while it is open. */
		public String factionId;
		/** Units (fleet points for the task force) sailing; before acceptance, the shortfall the prompt quoted. */
		public float amount;
		/** The colony or base it sails from. */
		public String fromName;
		public String fromMarketId;
		public long acceptedAt;
		/** Days the passage was estimated at when it was accepted ({@link ThreatAidRequests#eta}). */
		public float etaDays;
		public boolean delivered;
		/** Standing points charged on receipt - a whole number. */
		public int price;
		/** factionId -> why it could not take the part at its last check. */
		public Map<String, String> reasons = new HashMap<String, String>();

		public boolean open() { return factionId == null; }
		public boolean atSea() { return factionId != null && !delivered; }
		public boolean isFleets() { return ThreatNeeds.FLEETS.equals(good); }
		public Map<String, String> reasons() {
			if (reasons == null) reasons = new HashMap<String, String>();
			return reasons;
		}
	}

	/** One request of the player's for one of their colonies. */
	public static class Request {
		/** Unique: the market id and the clock when first logged (a convoy or order carries it, requestId). */
		public String id;
		public String marketId;
		public String purpose;
		/** Until 2026-10-28 the one good asked for; read only to migrate an older save's request ({@link #migrate}). */
		public String good;
		/** Until 2026-10-28 its tier; read only to migrate. */
		public int tier;
		/** 2026-10-28 to 2026-10-29 noon: the levers when asked, AID_COMMODITIES order; read only to migrate. */
		public int[] tiers;
		/** Until 2026-10-29 noon: a task force was asked for too; read only to migrate. */
		public boolean fleets;
		/** Until 2026-10-29 noon: the Fleet lever when asked; read only to migrate. */
		public int fleetTier;
		public boolean goodsDone;
		public boolean fleetsDone;
		public long since;
		public boolean noAnswerNotified;
		public float sent;
		public int senders;
		/** The parts (2026-10-29 evening). */
		public List<Part> parts;
		/** factionId -> when it last looked at the request. */
		public Map<String, Long> checkedAt;
		/** Withdrawn by the player with parts still at sea: hidden, checked no more, gone when the last lands or is lost. */
		public boolean withdrawn;

		public List<Part> parts() {
			if (parts == null) parts = new ArrayList<Part>();
			return parts;
		}

		public Map<String, Long> checkedAt() {
			if (checkedAt == null) checkedAt = new LinkedHashMap<String, Long>();
			return checkedAt;
		}

		/** Every part has landed. */
		public boolean done() {
			for (Part p : parts()) if (!p.delivered) return false;
			return true;
		}

		public boolean anyAtSea() {
			for (Part p : parts()) if (p.atSea()) return true;
			return false;
		}

		public boolean anyOpen() {
			for (Part p : parts()) if (p.open()) return true;
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Long> shortageSince() {
		Object val = Global.getSector().getPersistentData().get(KEY_SHORTAGE);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(KEY_SHORTAGE, val);
		}
		return (Map<String, Long>) val;
	}

	@SuppressWarnings("unchecked")
	public static List<Request> requests() {
		Object val = Global.getSector().getPersistentData().get(KEY_CALLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Request>();
			Global.getSector().getPersistentData().put(KEY_CALLS, val);
		}
		return (List<Request>) val;
	}

	protected static String key(MarketAPI market, String commodityId) {
		return market.getId() + ":" + commodityId;
	}

	/** Slow tick (IncursionManager.tick): remember when shortages began. */
	public static void tickShortages() {
		if (!ThreatWarState.enabled()) return;
		updateShortages();
		Global.getSector().getPersistentData().remove(KEY_ASKED);
	}

	/**
	 * Every half day (IncursionManager.advance's interval, 2026-10-29 night - on the 30-day tick no faction ever looked):
	 * each faction's look at the player's requests once aidRequestCheckDays have passed since its last, and what was lost at sea.
	 */
	public static void tick(Random random) {
		if (!ThreatWarState.enabled()) return;
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		for (Request r : new ArrayList<Request>(requests())) {
			migrate(r);
			MarketAPI market = Global.getSector().getEconomy().getMarket(r.marketId);
			if (market == null || !market.isPlayerOwned()) {
				requests().remove(r);
				continue;
			}
			settleLost(r, market);
			if (!r.withdrawn) check(r, market, random);
			if (r.withdrawn && !r.anyAtSea()) requests().remove(r);
		}
	}

	/** Every mobilised colony (the player's included - allies watch those too): note deficits as they start and clear. */
	protected static void updateShortages() {
		Map<String, Long> since = shortageSince();
		long now = Global.getSector().getClock().getTimestamp();
		List<String> live = new ArrayList<String>();
		for (String factionId : ThreatWarState.warFactionIds()) {
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				for (String c : ThreatReserves.AID_COMMODITIES) {
					ThreatReserves.CommodityStatus s = ThreatReserves.status(market, c);
					String k = key(market, c);
					if (s != null && s.localDeficit > 0) {
						live.add(k);
						if (!since.containsKey(k)) since.put(k, now);
					}
				}
			}
		}
		for (String k : new ArrayList<String>(since.keySet())) {
			if (!live.contains(k)) since.remove(k);
		}
	}

	// ------------------------------------------------------------------
	// the player's requests
	// ------------------------------------------------------------------

	/** The standing request for the player's colony, or null (a withdrawn one with parts still at sea is not one). */
	public static Request requestAt(MarketAPI market) {
		if (market == null) return null;
		for (Request r : requests()) {
			if (!r.withdrawn && market.getId().equals(r.marketId)) {
				migrate(r);
				return r;
			}
		}
		return null;
	}

	/** Every standing request, oldest first. */
	public static List<Request> open() {
		List<Request> out = new ArrayList<Request>();
		for (Request r : requests()) {
			if (r.withdrawn) continue;
			migrate(r);
			out.add(r);
		}
		return out;
	}

	protected static Request byId(String id) {
		if (id == null) return null;
		for (Request r : requests()) {
			migrate(r);
			if (id.equals(r.id)) return r;
		}
		return null;
	}

	/** The request's part of the good not yet landed, or null. */
	protected static Part find(Request r, String good) {
		for (Part p : r.parts()) if (!p.delivered && good != null && good.equals(p.good)) return p;
		return null;
	}

	/** Why the player cannot call for aid for this world now, or null when they can. */
	public static String requestOffReason(MarketAPI market) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return "Requests for aid are disabled in the mod settings.";
		if (market == null || !market.isPlayerOwned()) return "Not your colony.";
		if (!ThreatWarState.isAtWar(Factions.PLAYER)) return "Your faction is not mobilised.";
		return null;
	}

	/**
	 * Logs the request for the world, or adds to the one standing: a part for each short good at
	 * its lever's tier - an open part of the same good is retiered, one already accepted left as it
	 * is. Every faction looks at it on the next tick, then a day apart. Null when nothing was asked.
	 */
	public static Request request(MarketAPI market, List<Part> shorts) {
		if (requestOffReason(market) != null || shorts == null || shorts.isEmpty()) return null;
		Request r = requestAt(market);
		if (r == null) {
			r = new Request();
			r.since = Global.getSector().getClock().getTimestamp();
			r.id = market.getId() + ":" + r.since;
			r.marketId = market.getId();
			r.purpose = "the defence of " + market.getName();
			r.parts = new ArrayList<Part>();
			requests().add(r);
		}
		List<String> asked = new ArrayList<String>();
		for (Part s : shorts) {
			if (s == null || s.good == null) continue;
			int tier = Math.max(1, Math.min(2, s.tier));
			Part have = find(r, s.good);
			if (have != null && !have.open()) continue;
			if (have != null) {
				have.tier = tier;
				have.reasons().clear();
				asked.add(askLabel(have));
			} else {
				Part p = new Part();
				p.good = s.good;
				p.tier = tier;
				r.parts().add(p);
				asked.add(askLabel(p));
			}
		}
		if (asked.isEmpty()) return r;
		r.checkedAt().clear();
		ThreatIncConfig.log("Aid requested for " + market.getName() + ": " + ThreatWarBoard.join(asked));
		// an SOS (the user, 2026-10-29 night: "you dont know if anyone is coming"): the call, nothing of how
		// it is answered; a call a good (2026-10-30: "a request for each resource type ... so different
		// factions can answer each"), one line each
		ThreatNotice n = ThreatNotice.titled("Distress Call").icon(market.getFaction());
		for (String a : asked) n.line("%s calls for %s", ThreatNotice.market(market), a);
		n.send();
		return r;
	}

	/**
	 * The player withdraws the call for one good (null: every open one): the open part is dropped; a
	 * part already sailing still lands, and is still charged on receipt. The request goes when no
	 * part is left open or at sea. False with nothing open to withdraw.
	 */
	public static boolean withdraw(MarketAPI market, String good) {
		Request r = requestAt(market);
		if (r == null) return false;
		Part dropped = null;
		for (Iterator<Part> it = r.parts().iterator(); it.hasNext();) {
			Part p = it.next();
			if (!p.open()) continue;
			if (good != null && !good.equals(p.good)) continue;
			dropped = p;
			it.remove();
		}
		if (dropped == null) return false;
		if (!r.anyOpen()) r.withdrawn = true;
		boolean sailing = r.anyAtSea();
		if (!r.anyOpen() && !sailing) requests().remove(r);
		ThreatNotice.titled("Call Withdrawn").icon(market.getFaction())
				.line("%s withdraws its call for %s", ThreatNotice.market(market),
						good != null ? goodLabel(dropped) : "aid").send();
		ThreatIncConfig.log("Aid request withdrawn for " + market.getName() + (good != null ? " (" + good + ")" : "")
				+ (sailing ? " (parts at sea)" : ""));
		return true;
	}

	// ------------------------------------------------------------------
	// the shortfall a Transfer or Deploy leaves (the prompt)
	// ------------------------------------------------------------------

	/**
	 * What a Transfer at the colonies table's levers leaves short at the player's colony: a part a
	 * good whose convoy load is under what the colony needs of it at the lever's tier
	 * (ThreatNeeds.of, the largest need of the good; Med the med figure, Max the max). Empty for an
	 * outpost, another faction's colony, or a lever at None. {@code load} is what the convoy would
	 * carry, AID_COMMODITIES order; null when nothing sails. Each part's amount is the shortfall.
	 */
	public static List<Part> transferShortfall(MarketAPI target, int[] levers, float[] load) {
		List<Part> out = new ArrayList<Part>();
		if (target == null || !target.isPlayerOwned() || levers == null) return out;
		if (!ThreatIncConfig.aidRequestsEnabled()) return out;
		List<ThreatNeeds.Need> needs = ThreatNeeds.of(target);
		for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length && i < levers.length; i++) {
			int tier = levers[i];
			if (tier <= 0) continue;
			String good = ThreatReserves.AID_COMMODITIES[i];
			float need = needOf(needs, good, tier);
			float sent = load != null && i < load.length ? load[i] : 0f;
			// short: under the colony's need of it, or nothing of it to send at all from any colony of the
			// player's (2026-10-29 night: a world with no need on the ledger could never ask - "once i request
			// marines at haven i cant request marines at arkoman")
			boolean short_ = need > 0f ? sent + 0.5f < need : sent < 1f;
			if (!short_) continue;
			Part p = new Part();
			p.good = good;
			p.tier = tier;
			p.amount = Math.max(0f, need - sent);
			out.add(p);
		}
		return out;
	}

	/**
	 * What a Deploy leaves short: the task force, when the fleet the Hulls lever makes (what it adds
	 * over what is deployed already) is under the colony's fleets need at the lever's tier - the
	 * relief a request's task force is. Empty with no fleets need, the lever at None, or an outpost.
	 */
	public static List<Part> deployShortfall(ThreatDeploy.Plan d) {
		List<Part> out = new ArrayList<Part>();
		if (d == null || d.target == null || d.target.market == null || !d.target.market.isPlayerOwned()) return out;
		if (!ThreatIncConfig.aidRequestsEnabled()) return out;
		int tier = ThreatDeploy.hullsTier(d.levers);
		if (tier <= 0) return out;
		ThreatNeeds.Need n = ThreatNeeds.fleets(d.target.market);
		if (n == null) return out;
		float need = n.tier(tier);
		float adds = d.ok() ? Math.max(0f, d.targetPts - d.livePts) : 0f;
		if (need <= 0f || adds + 0.5f >= need) return out;
		Part p = new Part();
		p.good = ThreatNeeds.FLEETS;
		p.tier = tier;
		p.amount = need - adds;
		out.add(p);
		return out;
	}

	/** The colony's largest need of the good at the tier (1 Med, 2 Max), 0 with none. */
	public static float needOf(List<ThreatNeeds.Need> needs, String good, int tier) {
		float most = 0f;
		if (needs == null || good == null) return 0f;
		for (ThreatNeeds.Need n : needs) {
			if (!good.equals(n.good)) continue;
			most = Math.max(most, n.tier(tier));
		}
		return most;
	}

	/** "marines, fuel and a task force" - what the parts are, no figures (the user, 2026-10-29 night: "keep all the text simple"). */
	public static String shortText(List<Part> parts) {
		List<String> bits = new ArrayList<String>();
		if (parts != null) for (Part p : parts) bits.add(goodLabel(p));
		return bits.isEmpty() ? "nothing" : ThreatWarBoard.join(bits);
	}

	/** "any marines that can be spared and some spare fuel" - what the parts call for. */
	public static String askText(List<Part> parts) {
		List<String> bits = new ArrayList<String>();
		if (parts != null) for (Part p : parts) bits.add(askLabel(p));
		return bits.isEmpty() ? "nothing" : ThreatWarBoard.join(bits);
	}

	/** "marines" / "a task force" - the part's good alone. */
	public static String goodLabel(Part p) {
		if (p == null) return "";
		if (p.isFleets()) return "a task force";
		return ThreatReserves.label(p.good);
	}

	/**
	 * What one part calls for, in the tier's words (the user, 2026-10-30: "when you call for max
	 * marines the request should be like Call for any marines that can be spared ... if med then
	 * ... call for some spare reserves"): Max "any marines that can be spared", Med "some spare
	 * marines"; the task force "any task force that can be spared" / "a modest task force".
	 */
	public static String askLabel(Part p) {
		if (p == null) return "";
		if (p.isFleets()) return p.tier >= 2 ? "any task force that can be spared" : "a modest task force";
		String good = ThreatReserves.label(p.good);
		return p.tier >= 2 ? "any " + good + " that can be spared" : "some spare " + good;
	}

	/** "320 marines" / "900 fleet points" - the part's amount. */
	public static String amountText(Part p) {
		if (p == null) return "";
		if (p.isFleets()) return Misc.getWithDGS(Math.round(p.amount)) + " fleet points";
		return Misc.getWithDGS(ThreatHulls.shown(p.good, p.amount)) + " " + ThreatReserves.label(p.good);
	}

	// ------------------------------------------------------------------
	// the daily check
	// ------------------------------------------------------------------

	/** Days between one faction's looks at a request (aidRequestCheckDays, at least half a day). */
	public static float checkDays() {
		return Math.max(0.5f, ThreatIncConfig.aidRequestCheckDays());
	}

	/** Days until the faction looks at the request again; 0 when it has never looked. */
	public static float nextCheckIn(Request r, FactionAPI helper) {
		if (r == null || helper == null) return 0f;
		Long last = r.checkedAt().get(helper.getId());
		if (last == null) return 0f;
		return Math.max(0f, checkDays() - Global.getSector().getClock().getElapsedDaysSince(last));
	}

	/** Each candidate faction whose day has come looks at every open part ({@link #tryAccept}); the reason it could not is kept for the board. */
	protected static void check(Request r, MarketAPI market, Random random) {
		if (!r.anyOpen()) return;
		long now = Global.getSector().getClock().getTimestamp();
		for (FactionAPI helper : candidates()) {
			Long last = r.checkedAt().get(helper.getId());
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last) < checkDays()) continue;
			r.checkedAt().put(helper.getId(), now);
			for (Part p : new ArrayList<Part>(r.parts())) {
				if (!p.open()) continue;
				String why = tryAccept(r, market, p, helper, random);
				if (why != null) {
					// the reason is the log's and debug mode's, never the player's (an SOS, 2026-10-29 night)
					p.reasons().put(helper.getId(), why);
					ThreatIncConfig.log("Aid request: " + helper.getId() + " cannot take " + p.good + " for "
							+ market.getName() + " - " + why);
				}
			}
		}
	}

	/**
	 * One faction's look at one open part: it accepts when it has the capacity - the tier's share
	 * of what its colony sparing the most of the good spares (Max all, Med half), at least
	 * {@link #minSpare}; the task force that share of its free hulls, at least a fleet - and the
	 * whole-number price fits the standing it can take without dropping the player below
	 * Favourable (an ask over the budget is cut to it). The convoy or guard sails now. Returns why it
	 * could not, or null on acceptance.
	 */
	protected static String tryAccept(Request r, MarketAPI market, Part p, FactionAPI helper, Random random) {
		if (willingness(helper) <= 0f) {
			int[] c = besetCount(helper);
			return c[0] + " of " + c[1] + " worlds beset by the Threat, past your standing";
		}
		float budget = (float) Math.floor(budget(helper));
		float per = pricePerUnit(market, p.good, p.tier);
		if (p.isFleets()) {
			MarketAPI base = ThreatFleetOrders.pickBase(helper, market.getLocationInHyperspace());
			if (base == null) return "no base in reach";
			float spare = ThreatHulls.enabled() ? ThreatHulls.freeFP(helper.getId())
					: ThreatFleetOrders.guardNeed(market);
			float fp = (float) Math.floor(ThreatConvoys.share(p.tier) * spare);
			float min = (float) Math.ceil(ThreatDeploy.minPoints());
			if (fp < min) {
				return (p.tier >= 2 ? "the " : "half of the ") + Misc.getWithDGS((int) spare)
						+ " fleet points its navy has free is under a fleet's " + Misc.getWithDGS((int) min);
			}
			if (priceOf(fp, per) > budget) {
				fp = per > 0f ? (float) Math.floor(budget / per) : fp;
				if (fp < min) return "would take you below Favourable";
			}
			float before = ThreatFleetOrders.guardPointsFor(market.getId());
			ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuardPoints(helper, market, base, fp);
			if (o == null) return "no fleet could be raised at " + base.getName();
			o.requestId = r.id;
			float sailed = Math.max(0f, ThreatFleetOrders.guardPointsFor(market.getId()) - before);
			if (sailed <= 0f && o.fleet != null) sailed = o.fleet.getFleetPoints();
			accept(r, market, p, helper, sailed, base, priceOf(sailed, per));
			return null;
		}
		int idx = ThreatAid.index(p.good);
		if (idx < 0) return "not a good a convoy carries";
		List<MarketAPI> donors = donors(helper, market, p.good);
		if (donors.isEmpty()) {
			return "no colony in reach spares " + Misc.getWithDGS((int) minSpare(p.good)) + " or more";
		}
		MarketAPI donor = donors.get(0);
		float spare = ThreatConvoys.spare(donor, p.good);
		float amount = (float) Math.floor(ThreatConvoys.share(p.tier) * spare);
		if (amount < minSpare(p.good)) {
			return "half of the " + Misc.getWithDGS(ThreatHulls.shown(p.good, spare)) + " " + donor.getName()
					+ " spares is under " + Misc.getWithDGS((int) minSpare(p.good));
		}
		if (priceOf(amount, per) > budget) {
			amount = per > 0f ? (float) Math.floor(budget / per) : amount;
			if (amount < minSpare(p.good)) return "would take you below Favourable";
		}
		float[] load = new float[ThreatReserves.AID_COMMODITIES.length];
		load[idx] = amount;
		ThreatConvoys.Convoy c = ThreatConvoys.dispatch(donor, market, helper, load, random, Factions.PLAYER, false);
		if (c == null) return "no convoy could be raised at " + donor.getName();
		float carried = Commodities.SHIPS.equals(p.good) ? c.ships : ThreatConvoys.carried(c)[idx];
		if (carried < 1f) return "nothing could be loaded at " + donor.getName();
		c.requestId = r.id;
		accept(r, market, p, helper, carried, donor, priceOf(carried, per));
		return null;
	}

	/** The whole-number standing price of an amount at a rate a unit: at least 1 for anything sent. */
	protected static int priceOf(float amount, float per) {
		if (amount <= 0f || per <= 0f) return 0;
		return Math.max(1, Math.round(amount * per));
	}

	/** The part is the faction's: what sails, from where, the ETA and the price it will cost on receipt; the notice. */
	protected static void accept(Request r, MarketAPI market, Part p, FactionAPI helper, float amount, MarketAPI from, int price) {
		p.factionId = helper.getId();
		p.amount = amount;
		p.fromName = from.getName();
		p.fromMarketId = from.getId();
		p.acceptedAt = Global.getSector().getClock().getTimestamp();
		p.etaDays = eta(from, market);
		p.price = price;
		p.reasons().clear();
		ThreatIncConfig.log("Aid request accepted: " + helper.getId() + " sends " + (int) amount + " " + p.good
				+ " from " + from.getName() + " to " + market.getName() + ", ETA " + (int) Math.ceil(p.etaDays)
				+ " d, standing -" + price + " on receipt");
		ThreatNotice.titled("Call Answered").icon(helper)
				.line("%s sends %s from %s to %s", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(p)),
						ThreatNotice.market(from), ThreatNotice.market(market))
				.line("ETA %s days", ThreatNotice.hl("" + (int) Math.ceil(p.etaDays)))
				.line("Standing with %s %s on receipt", ThreatNotice.faction(helper), ThreatNotice.hl("-" + price))
				.send();
	}

	/** Days a convoy or fleet from one colony to another is estimated at: the passage at the board's pace (ThreatReach.days) and a day in-system. */
	public static float eta(MarketAPI from, MarketAPI to) {
		if (from == null || to == null || from.getStarSystem() == null || to.getStarSystem() == null) return 1f;
		float ly = Misc.getDistanceLY(from.getStarSystem().getLocation(), to.getStarSystem().getLocation());
		return ThreatReach.days(ly) + 1f;
	}

	/** Days until an accepted part is due, 0 once overdue. */
	public static float etaLeft(Part p) {
		if (p == null || p.open()) return 0f;
		return Math.max(0f, p.etaDays - Global.getSector().getClock().getElapsedDaysSince(p.acceptedAt));
	}

	// ------------------------------------------------------------------
	// receipt, loss, the price
	// ------------------------------------------------------------------

	/** An answering faction's convoy landed at the player's colony: the part is delivered and its price charged. True when the convoy was one. */
	public static boolean onConvoyLanded(ThreatConvoys.Convoy c, MarketAPI base) {
		if (c == null || c.requestId == null || base == null) return false;
		Request r = byId(c.requestId);
		if (r == null) return false;
		Part p = partAtSea(r, c.factionId, goodOf(c));
		if (p == null) return false;
		deliver(r, p, base);
		return true;
	}

	/** An answering faction's guard reached the player's colony: the part is delivered and its price charged. */
	public static void onGuardArrived(ThreatFleetOrders.Order o) {
		if (o == null || o.requestId == null) return;
		Request r = byId(o.requestId);
		if (r == null) return;
		Part p = partAtSea(r, o.factionId, ThreatNeeds.FLEETS);
		if (p == null) return;
		deliver(r, p, Global.getSector().getEconomy().getMarket(r.marketId));
	}

	/** The good a request's convoy carries: the one aboard. */
	protected static String goodOf(ThreatConvoys.Convoy c) {
		if (c.ships > 0f) return Commodities.SHIPS;
		if (c.marines > 0f) return Commodities.MARINES;
		if (c.armaments > 0f) return Commodities.HAND_WEAPONS;
		if (c.fuel > 0f) return Commodities.FUEL;
		if (c.supplies > 0f) return Commodities.SUPPLIES;
		return null;
	}

	protected static Part partAtSea(Request r, String factionId, String good) {
		for (Part p : r.parts()) {
			if (p.atSea() && p.factionId.equals(factionId) && good != null && good.equals(p.good)) return p;
		}
		return null;
	}

	/** The part landed: its price is charged, the notice sent; a request whose last part is in is done. */
	protected static void deliver(Request r, Part p, MarketAPI base) {
		p.delivered = true;
		FactionAPI helper = Global.getSector().getFaction(p.factionId);
		charge(helper, p.price);
		ThreatIncConfig.log("Aid request delivered: " + p.factionId + " " + (int) p.amount + " " + p.good + " at "
				+ (base != null ? base.getName() : r.marketId) + ", standing -" + p.price);
		ThreatNotice n = ThreatNotice.titled("Aid Landed").icon(helper);
		if (base != null) {
			n.line("%s from %s at %s", ThreatNotice.hl(amountText(p)), ThreatNotice.faction(helper), ThreatNotice.market(base));
		} else {
			n.line("%s from %s", ThreatNotice.hl(amountText(p)), ThreatNotice.faction(helper));
		}
		if (p.price > 0) n.line("Standing with %s %s", ThreatNotice.faction(helper), ThreatNotice.hl("-" + p.price));
		n.send();
		if (r.done()) {
			requests().remove(r);
			if (!r.withdrawn && base != null) {
				ThreatNotice.titled("Call Answered In Full").icon(base.getFaction())
						.line("Every part of %s's call has landed", ThreatNotice.market(base)).send();
			}
		}
	}

	/**
	 * Standing charged for a part received: a whole number of points off the relationship, never
	 * below Favourable (a player already under it loses nothing more), set straight on the faction
	 * so vanilla shows no message and plays no sound (the user, 2026-10-29: "whole number amount
	 * only! and remove the decrement faction rep sound").
	 */
	public static void charge(FactionAPI helper, int price) {
		if (helper == null || price <= 0 || helper.isPlayerFaction()) return;
		float floor = RepLevel.FAVORABLE.getMin();
		float before = helper.getRelToPlayer().getRel();
		if (before < floor) return;
		float after = Math.max(floor, before - price * 0.01f);
		// on the hundredth the relationship screen rounds to, so the drop reads as the whole number
		after = Math.round(after * 100f) / 100f;
		helper.setRelationship(Factions.PLAYER, after);
	}

	/** A part accepted whose convoy or fleet is no longer at sea and never landed: nothing is charged, the part reopens. */
	protected static void settleLost(Request r, MarketAPI market) {
		for (Part p : r.parts()) {
			if (!p.atSea()) continue;
			boolean found = p.isFleets() ? orderFor(r.id, p.factionId) != null : convoyFor(r.id, p.factionId) != null;
			if (found) continue;
			FactionAPI helper = Global.getSector().getFaction(p.factionId);
			ThreatIncConfig.log("Aid request part lost: " + p.factionId + " " + (int) p.amount + " " + p.good + " for "
					+ market.getName());
			ThreatNotice.titled("Aid Lost").bad().icon(market.getFaction())
					.line("%s's %s for %s was lost on the way", ThreatNotice.faction(helper), ThreatNotice.hl(amountText(p)),
							ThreatNotice.market(market))
					.send();
			p.factionId = null;
			p.amount = 0f;
			p.fromName = null;
			p.fromMarketId = null;
			p.acceptedAt = 0L;
			p.etaDays = 0f;
			p.price = 0;
			if (r.withdrawn) r.parts().remove(p);
		}
	}

	protected static ThreatConvoys.Convoy convoyFor(String requestId, String factionId) {
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (requestId.equals(c.requestId) && factionId.equals(c.factionId)) return c;
		}
		return null;
	}

	protected static ThreatFleetOrders.Order orderFor(String requestId, String factionId) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (requestId.equals(o.requestId) && factionId.equals(o.factionId)) return o;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// candidates, prices, standing
	// ------------------------------------------------------------------

	/** The price multiplier of a tier: Min 1.0, Med 1.25, Max 1.5 (decision 3). */
	public static float askPrice(int tier) {
		return tier >= 2 ? ThreatIncConfig.aidAskPriceMax() : tier == 1 ? ThreatIncConfig.aidAskPriceMed()
				: ThreatIncConfig.aidAskPriceMin();
	}

	/** Standing points one unit of the good costs when a faction sends it to the player's market at the tier. */
	public static float pricePerUnit(MarketAPI market, String good, int tier) {
		if (ThreatNeeds.FLEETS.equals(good)) return ThreatIncConfig.aidRepGuardArrived() / 100f * askPrice(tier);
		return ThreatAid.valueAt(market, good, 1f) / Math.max(1f, ThreatIncConfig.aidRepPerCredits()) * askPrice(tier);
	}

	/** Standing points the player can spend with the faction before dropping below Favourable. */
	public static float budget(FactionAPI helper) {
		if (helper == null) return 0f;
		return Math.max(0f, (helper.getRelToPlayer().getRel() - RepLevel.FAVORABLE.getMin()) / 0.01f);
	}

	/** War factions at Favourable or better with the player, not hostile, best standing first. */
	public static List<FactionAPI> candidates() {
		List<FactionAPI> out = new ArrayList<FactionAPI>();
		FactionAPI player = Global.getSector().getPlayerFaction();
		for (String id : ThreatWarState.warFactionIds()) {
			if (Factions.PLAYER.equals(id) || ThreatWarState.excluded(id)) continue;
			FactionAPI f = Global.getSector().getFaction(id);
			if (f == null || f.isPlayerFaction()) continue;
			if (f.isHostileTo(player) || player.isHostileTo(f)) continue;
			if (!f.getRelToPlayer().isAtWorst(RepLevel.FAVORABLE)) continue;
			out.add(f);
		}
		Collections.sort(out, new Comparator<FactionAPI>() {
			public int compare(FactionAPI a, FactionAPI b) {
				return Float.compare(b.getRelToPlayer().getRel(), a.getRelToPlayer().getRel());
			}
		});
		return out;
	}

	/** {beset, all}: the faction's worlds under a Threat army, Threat warships over the orbit (ThreatBlockade.penalty) or strikes bound for them (ThreatFrontlines.strikesWeight), over all its worlds. */
	public static int[] besetCount(FactionAPI helper) {
		int all = 0, beset = 0;
		if (helper == null) return new int[] { 0, 0 };
		for (MarketAPI m : ThreatReserves.marketsOf(helper.getId())) {
			all++;
			if (underArmy(m) || ThreatBlockade.penalty(m) > 0f || ThreatFrontlines.strikesWeight(m) > 0f) beset++;
		}
		return new int[] { beset, all };
	}

	/** The share of the faction's worlds the Threat besets, 0..1 ({@link #besetCount}). */
	public static float besetness(FactionAPI helper) {
		int[] c = besetCount(helper);
		return c[1] > 0 ? c[0] / (float) c[1] : 0f;
	}

	/** The player's standing with the faction from Favourable's floor (0) to Cooperative's (1). */
	public static float standing(FactionAPI helper) {
		if (helper == null) return 0f;
		float lo = RepLevel.FAVORABLE.getMin(), hi = RepLevel.COOPERATIVE.getMin();
		return Math.max(0f, Math.min(1f, (helper.getRelToPlayer().getRel() - lo) / Math.max(0.01f, hi - lo)));
	}

	/**
	 * Whether a faction answers at all (the user, 2026-10-29: "really needs to depend on factions
	 * current level of besetness by the threat cross referenced with your overall standing"): 1
	 * while its besetness x aidRequestBesetWeight is under the player's standing with it, else 0 -
	 * a faction with half its worlds under the Threat answers only a player half way from
	 * Favourable to Cooperative. What it sends is its capacity ({@link #tryAccept}), not a share.
	 */
	public static float willingness(FactionAPI helper) {
		float beset = Math.max(0f, Math.min(1f, besetness(helper) * ThreatIncConfig.aidRequestBesetWeight()));
		if (beset <= 0f) return 1f;
		return standing(helper) < beset ? 0f : 1f;
	}

	/** "Hegemony: 2 of 7 worlds beset, standing 48 of 100 - answers, 12 standing to spend" - a faction's line on the board. */
	public static String standLine(FactionAPI helper) {
		int[] c = besetCount(helper);
		return ThreatWarState.displayName(helper.getId()) + ": " + c[0] + " of " + c[1] + " worlds beset, standing "
				+ Math.round(standing(helper) * 100f) + " of 100 - "
				+ (willingness(helper) <= 0f ? "answers nothing"
						: "answers, " + (int) Math.floor(budget(helper)) + " standing to spend");
	}

	/** The least a colony must spare of a good to answer a request with it: aidRequestMinSpare, one unit for ship hulls. */
	public static float minSpare(String good) {
		if (Commodities.SHIPS.equals(good)) return 1f;
		return Math.max(0f, ThreatIncConfig.aidRequestMinSpare());
	}

	/**
	 * The helper's colonies in reach of the player's colony that spare {@link #minSpare} or more
	 * of the good, most spare first. An NPC helper reaches as far as its colony's fuel pays
	 * (ThreatConvoys.stockReachLY).
	 */
	protected static List<MarketAPI> donors(FactionAPI helper, MarketAPI needy, String good) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (helper == null || needy == null || needy.getStarSystem() == null) return out;
		final String g = good;
		for (MarketAPI donor : ThreatReserves.marketsOf(helper.getId())) {
			if (donor.getStarSystem() == null || donor.getPrimaryEntity() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					needy.getStarSystem().getLocation()) > ThreatConvoys.stockReachLY(donor)) continue;
			if (ThreatConvoys.spare(donor, good) >= minSpare(good)) out.add(donor);
		}
		Collections.sort(out, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(ThreatConvoys.spare(b, g), ThreatConvoys.spare(a, g));
			}
		});
		return out;
	}

	/** An older save's request read as parts: one good at a tier (2026-10-23), or levers and a Fleet lever (2026-10-28 / 29). */
	protected static void migrate(Request r) {
		if (r == null) return;
		if (r.id == null) r.id = r.marketId + ":" + r.since;
		if (r.parts != null) return;
		r.parts = new ArrayList<Part>();
		int[] tiers = r.tiers;
		boolean fleets = r.fleets;
		if (tiers == null) {
			tiers = new int[ThreatReserves.AID_COMMODITIES.length];
			if (ThreatNeeds.FLEETS.equals(r.good)) {
				fleets = true;
			} else {
				int idx = ThreatAid.index(r.good);
				if (idx >= 0) tiers[idx] = Math.max(1, Math.min(2, r.tier));
			}
		}
		for (int i = 0; i < tiers.length && i < ThreatReserves.AID_COMMODITIES.length; i++) {
			if (tiers[i] <= 0) continue;
			Part p = new Part();
			p.good = ThreatReserves.AID_COMMODITIES[i];
			p.tier = Math.max(1, Math.min(2, tiers[i]));
			r.parts.add(p);
		}
		if (fleets) {
			Part p = new Part();
			p.good = ThreatNeeds.FLEETS;
			p.tier = Math.max(1, Math.min(2, r.fleetTier));
			r.parts.add(p);
		}
	}

	protected static ThreatNotice.Reason reason(ThreatNotice.Reason why, FactionAPI helper, String what) {
		if (why == null) return ThreatNotice.Reason.of("%s: " + what, ThreatNotice.faction(helper));
		return why.line("%s: " + what, ThreatNotice.faction(helper));
	}

	// supply needs
	// ------------------------------------------------------------------

	/** Short of peacetime demand with the depot unable to cover it: exhausted now, or uncovered for missionAidShortageDays. */
	public static boolean shortageStanding(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null) return false;
		if (s.exhausted) return true;
		if (s.localDeficit <= 0 || s.covering) return false;
		Long since = shortageSince().get(key(market, commodityId));
		if (since == null) return false;
		return Global.getSector().getClock().getElapsedDaysSince(since)
				>= ThreatIncConfig.missionAidShortageDays();
	}

	/** What a depot need asks for: the peacetime gap in items. The War footing's share is not asked for. */
	public static int requestItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.localDeficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.localDeficit * s.econUnit));
	}

	/** Whether a Threat army stands on the colony: its needs are then the battle's (marines, armaments) before the depot's. */
	public static boolean underArmy(MarketAPI market) {
		return market != null && ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()));
	}

	/** The deficit in items: vanilla's units short times the commodity's econ unit. */
	public static int needItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.deficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.deficit * s.econUnit));
	}

	/** The commodity the colony is shortest of, in items - the largest deficit. Null when nothing is short. */
	public static String worstShortage(MarketAPI market) {
		if (market == null) return null;
		String best = null;
		int bestNeed = 0;
		for (String c : ThreatReserves.AID_COMMODITIES) {
			int need = needItems(market, c);
			if (need > bestNeed) {
				bestNeed = need;
				best = c;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// defence needs
	// ------------------------------------------------------------------

	/** Live Threat strikes with this colony among their targets. */
	public static List<GenericRaidFGI> strikesAgainst(MarketAPI market) {
		List<GenericRaidFGI> result = new ArrayList<GenericRaidFGI>();
		if (market == null) return result;
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI fgi = (GenericRaidFGI) curr;
			if (fgi.isEnded() || fgi.isEnding()) continue;
			// nobody asks for help against a strike nobody has seen (docs/frontlines.md)
			if (fgi.isHidden()) continue;
			if (ThreatFrontlines.boundFor(fgi, market)) result.add(fgi);
		}
		return result;
	}

	/** Whether the strike is in the colony's system: real fleets there, or its payload action running in route mode. */
	public static boolean strikeAtTarget(GenericRaidFGI fgi, MarketAPI market) {
		if (fgi == null || market == null || market.getContainingLocation() == null) return false;
		if (fgi.isSpawnedFleets()) {
			for (CampaignFleetAPI fleet : fgi.getFleets()) {
				if (fleet == null || !fleet.isAlive()) continue;
				if (fleet.getContainingLocation() == market.getContainingLocation()) return true;
			}
			return false;
		}
		FGAction action = fgi.getCurrentAction();
		return action != null && GenericRaidFGI.PAYLOAD_ACTION.equals(action.getId());
	}

	/** Fleet points of the strike: the real fleets when spawned, else its planned sizes. */
	public static float strikeStrength(GenericRaidFGI fgi) {
		float sum = 0f;
		if (fgi.isSpawnedFleets()) {
			for (CampaignFleetAPI fleet : fgi.getFleets()) {
				if (fleet != null && fleet.isAlive()) sum += fleet.getFleetPoints();
			}
		}
		if (sum <= 0f && fgi.getParams() != null) {
			for (Integer size : fgi.getParams().fleetSizes) {
				sum += size * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
			}
		}
		return sum;
	}

	/** The faction's strength in the colony's system plus its station. */
	public static float defenderStrength(MarketAPI market) {
		StarSystemAPI system = market != null ? market.getStarSystem() : null;
		FactionAPI faction = market != null ? market.getFaction() : null;
		if (system == null || faction == null) return 0f;
		float strength = WarSimScript.getFactionStrength(faction, system);
		if (market.getPrimaryEntity() != null) {
			strength += WarSimScript.getStationStrength(faction, system, market.getPrimaryEntity());
		}
		return strength;
	}

	/** A strike is coming and the defenders are outmatched (defendRequestRatio). */
	public static boolean needsDefence(MarketAPI market) {
		List<GenericRaidFGI> strikes = strikesAgainst(market);
		if (strikes.isEmpty()) return false;
		float defence = defenderStrength(market) * ThreatIncConfig.defendRequestRatio();
		for (GenericRaidFGI s : strikes) {
			if (strikeStrength(s) > defence) return true;
		}
		return false;
	}
}
