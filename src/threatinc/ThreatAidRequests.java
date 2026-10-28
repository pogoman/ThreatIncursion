package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * REQUESTS FOR AID (docs/war-effort-rework.md section 3, the user 2026-10-23): the player asks the
 * sector for one of their own colony's needs ({@link ThreatNeeds}) at a board tier, and every
 * faction at Favourable or better with them, not hostile, answers on the next slow tick - goods
 * from the colony that can spare them ({@link ThreatConvoys#pickAllyDonor}), fleets as a guard
 * from its nearest base - until the ask is covered net of what is at sea. EACH SENDER'S PRICE IS
 * STANDING, debited the day it sails: the goods' value at the player's market over aidRepPerCredits
 * (the scale the player earns at), a guard's points x aidRepGuardArrived / 100, times the tier
 * (aidAskPriceMin / Med / Max). A sender never takes the player below Favourable (decision 2):
 * it sends up to the budget that keeps it there. One request a colony per aidRequestCooldownDays;
 * a request stands until the need is gone; nothing is refunded for a convoy lost at sea.
 *
 * <p>The mission board is gone with it: until 2026-10-23 this class posted vanilla missions
 * ({@link ThreatAidMissionIntel}) for the same shortages. Its readers of a colony's state -
 * {@link #shortageStanding}, {@link #requestItems}, {@link #needsDefence} - now feed the ledger.
 */
public class ThreatAidRequests {

	/** Persistent: "marketId:commodityId" -> when its deficit was first seen. */
	public static final String KEY_SHORTAGE = "threatinc_aidShortageSince";
	/** Persistent: the player's open requests. */
	public static final String KEY_CALLS = "threatinc_aidCalls";
	/** Persistent: marketId -> when the player last asked for it (the cooldown). */
	public static final String KEY_ASKED = "threatinc_aidAskedAt";

	/** One request of the player's for one of their colonies. */
	public static class Request {
		public String marketId;
		public String purpose;
		/** Until 2026-10-28 the one good asked for; read only to migrate an older save's open request ({@link #migrate}). */
		public String good;
		/** Until 2026-10-28 its tier; read only to migrate. */
		public int tier;
		/** The colonies table's levers when asked, AID_COMMODITIES order (2026-10-28): each helper sends that share of its spare. */
		public int[] tiers;
		/** A task force is asked for too (a fleets need stood when asked). */
		public boolean fleets;
		/** The goods have had their poll (each candidate answered once). */
		public boolean goodsDone;
		/** The task force has had its poll. */
		public boolean fleetsDone;
		public long since;
		/** The "No Answer" notice has been sent once. */
		public boolean noAnswerNotified;
		/** Units (or fleet points) sent so far, and by how many factions. */
		public float sent;
		public int senders;
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

	@SuppressWarnings("unchecked")
	protected static Map<String, Long> askedAt() {
		Object val = Global.getSector().getPersistentData().get(KEY_ASKED);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(KEY_ASKED, val);
		}
		return (Map<String, Long>) val;
	}

	protected static String key(MarketAPI market, String commodityId) {
		return market.getId() + ":" + commodityId;
	}

	/** Slow tick: remember when shortages began, then answer the player's requests. */
	public static void tick(Random random) {
		if (!ThreatWarState.enabled()) return;
		updateShortages();
		answer(random);
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

	public static Request requestAt(MarketAPI market) {
		if (market == null) return null;
		for (Request r : requests()) {
			if (market.getId().equals(r.marketId)) return r;
		}
		return null;
	}

	/** Days of the cooldown still to run for this colony, 0 when it may ask. */
	public static float cooldownLeft(MarketAPI market) {
		if (market == null) return 0f;
		Long at = askedAt().get(market.getId());
		if (at == null) return 0f;
		return Math.max(0f, ThreatIncConfig.aidRequestCooldownDays()
				- Global.getSector().getClock().getElapsedDaysSince(at));
	}

	/** The price multiplier of a board tier: Min 1.0, Med 1.25, Max 1.5 (decision 3). */
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

	/** "Hegemony: 12.3 standing to spend before Favourable" - the faction's budget for the player's request. */
	public static String budgetLine(FactionAPI helper) {
		return ThreatWarState.displayName(helper.getId()) + ": " + String.format("%.1f", budget(helper))
				+ " standing to spend before Favourable";
	}

	/** Why the player cannot request aid for this colony at the colonies table's levers now, or null when they can. */
	public static String requestOffReason(MarketAPI market, int[] levers) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return "Requests for aid are disabled in the mod settings.";
		if (market == null || !market.isPlayerOwned()) return "Not your colony.";
		if (!ThreatWarState.isAtWar(Factions.PLAYER)) return "Your faction is not mobilised.";
		if (ThreatConvoys.allNone(levers) && ThreatNeeds.fleets(market) == null) {
			return "Every lever is at None, and no task force is needed.";
		}
		float left = cooldownLeft(market);
		if (left > 0f) return "Asked " + (int) (ThreatIncConfig.aidRequestCooldownDays() - left) + " days ago; again in "
				+ (int) Math.ceil(left) + ".";
		if (candidates().isEmpty()) return "No faction is Favourable or better with you.";
		return null;
	}

	/**
	 * The player asks at the colonies table's levers (the user, 2026-10-28: "aid and request etc
	 * uses all these levers as well"): each candidate sends, once, each lever's share of what its
	 * colony spares of that good - and a task force when a fleets need stands - from the next slow
	 * tick. Until 2026-10-28 a request asked for the most urgent need's figure at a tier and stood
	 * until the need was gone.
	 */
	public static boolean request(MarketAPI market, int[] levers) {
		if (requestOffReason(market, levers) != null) return false;
		ThreatNeeds.Need need = ThreatNeeds.mostUrgent(market);
		Request r = new Request();
		r.marketId = market.getId();
		r.purpose = need != null ? need.purpose : "war materiel";
		r.tiers = levers.clone();
		r.fleets = ThreatNeeds.fleets(market) != null;
		r.since = Global.getSector().getClock().getTimestamp();
		cancel(market);
		requests().add(r);
		askedAt().put(market.getId(), r.since);
		ThreatNotice.titled("Aid Requested").icon(Global.getSector().getPlayerFaction())
				.line("%s asks for %s", ThreatNotice.market(market), askText(r.tiers, r.fleets))
				.line("Every faction at Favourable or better answers on the next poll; each sender's price is standing")
				.send();
		ThreatIncConfig.log("Aid requested for " + market.getName() + ": " + askText(r.tiers, r.fleets));
		return true;
	}

	/** "half of their spare marines and heavy armaments, all of their spare fuel, and a task force" - what a request asks each faction for. */
	public static String askText(int[] levers, boolean fleets) {
		List<String> parts = new ArrayList<String>();
		for (int tier = 2; tier >= 1; tier--) {
			List<String> goods = new ArrayList<String>();
			for (int i = 0; levers != null && i < levers.length && i < ThreatReserves.AID_COMMODITIES.length; i++) {
				if (levers[i] == tier) goods.add(ThreatReserves.label(ThreatReserves.AID_COMMODITIES[i]));
			}
			if (!goods.isEmpty()) parts.add((tier == 2 ? "all" : "half") + " of their spare " + ThreatWarBoard.join(goods));
		}
		if (fleets) parts.add("a task force");
		return parts.isEmpty() ? "nothing" : ThreatWarBoard.join(parts);
	}

	/** The highest lever of a request, for a task force's price (ThreatAidRequests.askPrice): 0 with none set. */
	protected static int topTier(int[] levers) {
		int top = 0;
		if (levers != null) for (int t : levers) top = Math.max(top, t);
		return top;
	}

	/** An older save's open request (one good at a tier) read as levers: that good at Med for its Min, else its tier; a fleets request as the task force. */
	protected static void migrate(Request r) {
		if (r.tiers != null) return;
		r.tiers = new int[ThreatReserves.AID_COMMODITIES.length];
		if (ThreatNeeds.FLEETS.equals(r.good)) {
			r.fleets = true;
		} else {
			int idx = ThreatAid.index(r.good);
			if (idx >= 0) r.tiers[idx] = Math.max(1, Math.min(2, r.tier));
		}
	}

	/** The player withdraws the request (what has sailed still comes, and was paid for). */
	public static boolean cancel(MarketAPI market) {
		Request r = requestAt(market);
		if (r == null) return false;
		requests().remove(r);
		return true;
	}

	/** Standing with the faction debited: points are hundredths of the -1..1 scale, floored at Favourable. */
	public static void debit(String factionId, float points) {
		if (factionId == null || points <= 0f || Factions.PLAYER.equals(factionId)) return;
		CustomRepImpact impact = new CustomRepImpact();
		impact.delta = -points * 0.01f;
		impact.limit = RepLevel.FAVORABLE;
		Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.CUSTOM, impact, null, null, true, false), factionId);
	}

	/**
	 * Every open request: each candidate sends, once, each lever's share of what its colony
	 * spares of that good (goods wait out a blockade), and a guard for a fleets need; the request
	 * ends once every part has had its poll, with one "answered" notice a good or a "no answer".
	 */
	protected static void answer(Random random) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		for (Request r : new ArrayList<Request>(requests())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(r.marketId);
			if (market == null || !market.isPlayerOwned()) {
				requests().remove(r);
				continue;
			}
			migrate(r);
			ThreatNeeds.Need urgent = ThreatNeeds.mostUrgent(ThreatNeeds.of(market), true);
			boolean blockaded = urgent != null && urgent.suspended;
			ThreatNotice.Reason why = null;
			// good -> {units sent, senders}
			Map<String, float[]> sent = new LinkedHashMap<String, float[]>();
			if (!r.goodsDone && !blockaded) {
				for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length && i < r.tiers.length; i++) {
					int tier = r.tiers[i];
					if (tier <= 0) continue;
					String good = ThreatReserves.AID_COMMODITIES[i];
					for (FactionAPI helper : candidates()) {
						float budget = budget(helper);
						float per = pricePerUnit(market, good, tier);
						MarketAPI donor = ThreatConvoys.pickAllyDonor(helper, market, good);
						if (donor == null) {
							why = reason(why, helper, "no colony in reach with " + ThreatReserves.label(good) + " to spare");
							continue;
						}
						float amount = ThreatConvoys.share(tier) * ThreatConvoys.spare(donor, good);
						if (per > 0f) amount = Math.min(amount, budget / per);
						amount = (float) Math.floor(amount);
						if (amount < 1f) {
							why = reason(why, helper, per > 0f && budget / per < 1f ? "would take you below Favourable"
									: "nothing to spare");
							continue;
						}
						float[] load = new float[ThreatReserves.AID_COMMODITIES.length];
						load[i] = amount;
						ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, market, helper, load, random,
								Factions.PLAYER, false);
						if (convoy == null) {
							why = reason(why, helper, "no convoy could be raised at " + donor.getName());
							continue;
						}
						float carried = Commodities.SHIPS.equals(good) ? convoy.ships : amount;
						float price = Math.min(budget, carried * per);
						debit(helper.getId(), price);
						ThreatComms.answers(helper, donor, market, Math.round(carried), good, price);
						ThreatIncConfig.log("Aid request answered: " + helper.getId() + " sends " + (int) carried + " "
								+ good + " from " + donor.getName() + " to " + market.getName() + ", rep -" + price);
						float[] tally = sent.get(good);
						if (tally == null) sent.put(good, tally = new float[2]);
						tally[0] += carried;
						tally[1]++;
						r.sent += carried;
						r.senders++;
					}
				}
				r.goodsDone = true;
			}
			if (r.fleets && !r.fleetsDone) {
				int tier = topTier(r.tiers);
				for (FactionAPI helper : candidates()) {
					float budget = budget(helper);
					float per = pricePerUnit(market, ThreatNeeds.FLEETS, tier);
					MarketAPI base = ThreatFleetOrders.pickBase(helper, market.getLocationInHyperspace());
					if (base == null) {
						why = reason(why, helper, "no base in reach");
						continue;
					}
					float fp = ThreatFleetOrders.guardNeed(market);
					if (per > 0f && fp * per > budget) {
						why = reason(why, helper, "would take you below Favourable");
						continue;
					}
					float before = ThreatFleetOrders.guardPointsFor(market.getId());
					ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuard(helper, market, base, false);
					if (o == null) {
						why = reason(why, helper, "nothing to spare");
						continue;
					}
					float sailed = Math.max(0f, ThreatFleetOrders.guardPointsFor(market.getId()) - before);
					if (sailed <= 0f && o.fleet != null) sailed = o.fleet.getFleetPoints();
					float price = Math.min(budget, sailed * per);
					debit(helper.getId(), price);
					int days = o.fleet != null && base.getStarSystem() != null ? (int) Math.ceil(
							Misc.getDistanceLY(base.getStarSystem().getLocation(), market.getLocationInHyperspace()) * 2f) : 0;
					ThreatComms.sailsFor(helper, base, market, Math.round(sailed), days, price);
					ThreatIncConfig.log("Aid request answered: " + helper.getId() + " sends " + (int) sailed
							+ " FP from " + base.getName() + " to " + market.getName() + ", rep -" + price);
					float[] tally = sent.get(ThreatNeeds.FLEETS);
					if (tally == null) sent.put(ThreatNeeds.FLEETS, tally = new float[2]);
					tally[0] += sailed;
					tally[1]++;
					r.sent += sailed;
					r.senders++;
				}
				r.fleetsDone = true;
			}
			for (Map.Entry<String, float[]> e : sent.entrySet()) {
				ThreatComms.callAnswered(market, Math.round(e.getValue()[0]), e.getKey(), (int) e.getValue()[1]);
			}
			boolean done = (r.goodsDone || ThreatConvoys.allNone(r.tiers)) && (!r.fleets || r.fleetsDone);
			if (done && r.sent <= 0f && !r.noAnswerNotified) {
				ThreatComms.noAnswer(market, why);
				r.noAnswerNotified = true;
			}
			if (done) requests().remove(r);
		}
	}

	protected static ThreatNotice.Reason reason(ThreatNotice.Reason why, FactionAPI helper, String what) {
		if (why == null) return ThreatNotice.Reason.of("%s: " + what, ThreatNotice.faction(helper));
		return why.line("%s: " + what, ThreatNotice.faction(helper));
	}

	// ------------------------------------------------------------------
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
