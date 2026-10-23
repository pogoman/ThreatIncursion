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
		public String good;
		public int tier;
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

	/** "Hegemony: up to 1,200 marines before Favourable" - what the faction can send at the tier within the budget. */
	public static String candidateLine(FactionAPI helper, MarketAPI market, ThreatNeeds.Need need, int tier) {
		float per = pricePerUnit(market, need.good, tier);
		float upTo = per > 0f ? budget(helper) / per : Float.MAX_VALUE;
		String amount = upTo >= 1e8f ? "any amount" : "up to " + need.shown(upTo) + " " + need.goodLabel();
		return ThreatWarState.displayName(helper.getId()) + ": " + amount + " before Favourable";
	}

	/** Why the player cannot request aid for this colony at the tier now, or null when they can. */
	public static String requestOffReason(MarketAPI market, int tier) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return "Requests for aid are disabled in the mod settings.";
		if (market == null || !market.isPlayerOwned()) return "Not your colony.";
		if (!ThreatWarState.isAtWar(Factions.PLAYER)) return "Your faction is not mobilised.";
		ThreatNeeds.Need need = ThreatNeeds.mostUrgent(market);
		if (need == null) return "Nothing is short here.";
		if (need.suspended) return "The swarm holds the orbit: only fleets can be asked for.";
		float left = cooldownLeft(market);
		if (left > 0f) return "Asked " + (int) (ThreatIncConfig.aidRequestCooldownDays() - left) + " days ago; again in "
				+ (int) Math.ceil(left) + ".";
		if (candidates().isEmpty()) return "No faction is Favourable or better with you.";
		return null;
	}

	/** The player asks: the request stands from the next slow tick. */
	public static boolean request(MarketAPI market, int tier) {
		if (requestOffReason(market, tier) != null) return false;
		ThreatNeeds.Need need = ThreatNeeds.mostUrgent(market);
		Request r = new Request();
		r.marketId = market.getId();
		r.purpose = need.purpose;
		r.good = need.good;
		r.tier = Math.max(0, Math.min(2, tier));
		r.since = Global.getSector().getClock().getTimestamp();
		cancel(market);
		requests().add(r);
		askedAt().put(market.getId(), r.since);
		ThreatNotice.titled("Aid Requested").icon(Global.getSector().getPlayerFaction())
				.line("%s asks for %s %s: %s", ThreatNotice.market(market), need.shown(need.tier(r.tier)),
						need.goodLabel(), need.purpose)
				.line("Every faction at Favourable or better answers on the next poll; each sender's price is standing")
				.send();
		ThreatIncConfig.log("Aid requested for " + market.getName() + ": " + need.purpose + " " + need.good
				+ " tier " + r.tier);
		return true;
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

	/** Every open request: the need's figure at its tier net of what is at sea, answered by the candidates in turn. */
	protected static void answer(Random random) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		for (Request r : new ArrayList<Request>(requests())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(r.marketId);
			if (market == null || !market.isPlayerOwned()) {
				requests().remove(r);
				continue;
			}
			List<ThreatNeeds.Need> needs = ThreatNeeds.of(market);
			ThreatNeeds.Need need = ThreatNeeds.find(needs, r.purpose, r.good);
			if (need == null) {
				// the need is gone: covered, or the war moved on
				if (r.sent > 0f) ThreatComms.callAnswered(market, Math.round(r.sent), r.good, r.senders);
				requests().remove(r);
				continue;
			}
			if (need.suspended) continue; // the blockade: it waits
			float ask = need.tier(r.tier);
			if (ask < 1f) continue;
			ThreatNotice.Reason why = null;
			boolean sentNow = false;
			for (FactionAPI helper : candidates()) {
				if (ask < 1f) break;
				float budget = budget(helper);
				float per = pricePerUnit(market, r.good, r.tier);
				if (need.isFleets()) {
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
					ask -= sailed;
					r.sent += sailed;
					r.senders++;
					sentNow = true;
					continue;
				}
				MarketAPI donor = ThreatConvoys.pickAllyDonor(helper, market, r.good, ask);
				if (donor == null) {
					why = reason(why, helper, "no colony in reach with " + ThreatReserves.label(r.good) + " to spare");
					continue;
				}
				float amount = Math.min(ask, ThreatConvoys.spare(donor, r.good));
				if (per > 0f) amount = Math.min(amount, budget / per);
				if (amount < 1f) {
					why = reason(why, helper, per > 0f && budget / per < 1f ? "would take you below Favourable"
							: "nothing to spare");
					continue;
				}
				if (Commodities.SHIPS.equals(r.good)) amount = (float) Math.floor(amount);
				float[] load = new float[ThreatReserves.AID_COMMODITIES.length];
				load[ThreatAid.index(r.good)] = amount;
				ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, market, helper, load, random,
						Factions.PLAYER, false);
				if (convoy == null) {
					why = reason(why, helper, "no convoy could be raised at " + donor.getName());
					continue;
				}
				float carried = Commodities.SHIPS.equals(r.good) ? convoy.ships : amount;
				float price = Math.min(budget, carried * per);
				debit(helper.getId(), price);
				ThreatComms.answers(helper, donor, market, Math.round(carried), r.good, price);
				ThreatIncConfig.log("Aid request answered: " + helper.getId() + " sends " + (int) carried + " "
						+ r.good + " from " + donor.getName() + " to " + market.getName() + ", rep -" + price);
				ask -= carried;
				r.sent += carried;
				r.senders++;
				sentNow = true;
			}
			if (!sentNow && r.sent <= 0f && !r.noAnswerNotified) {
				ThreatComms.noAnswer(market, why);
				r.noAnswerNotified = true;
			}
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
