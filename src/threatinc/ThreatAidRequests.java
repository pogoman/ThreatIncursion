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
 * REQUESTS FOR AID (docs/war-effort-rework.md section 3, the user 2026-10-23): the player calls the
 * sector to the defence of one of their own worlds under a Threat army, from its row on the fronts
 * table at the fronts' Marines, Arms and Fleet levers (2026-10-29: "a general call for the
 * combination of levers specified"; the colony rows' Request went), and every faction at
 * Favourable or better with them, not hostile, answers after aidRequestAnswerDays - the levers'
 * share of what its colonies in reach spare, a guard of that share of its free hulls from its
 * nearest base - LESS ITS BESETNESS: the share of its own worlds under a Threat army, blockade or
 * strike, and nothing at all while that share outweighs the player's standing with it
 * ({@link #willingness}; "it really shouldnt be guaranteed and instant, as other factions might
 * need all their resources / be busy with their own defences"). EACH SENDER'S PRICE IS
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
		/** A task force is asked for too (the fronts' Fleet lever above None; before 2026-10-29, a fleets need stood when asked). */
		public boolean fleets;
		/** The fronts' Fleet lever when asked (2026-10-29): each helper sends that share of its free hulls as a guard; 0 in an older request (read as Med). */
		public int fleetTier;
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

	/** Why the player cannot call for aid for this world at the fronts table's levers {marines, arms, fleet} now, or null when they can. */
	public static String requestOffReason(MarketAPI market, int[] levers) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return "Requests for aid are disabled in the mod settings.";
		if (market == null || !market.isPlayerOwned()) return "Not your colony.";
		if (!ThreatWarState.isAtWar(Factions.PLAYER)) return "Your faction is not mobilised.";
		if (!underArmy(market)) return "No Threat army stands on " + market.getName() + ".";
		if (ThreatConvoys.allNone(levers)) return "Every lever is at None.";
		float left = cooldownLeft(market);
		if (left > 0f) return "Asked " + (int) (ThreatIncConfig.aidRequestCooldownDays() - left) + " days ago; again in "
				+ (int) Math.ceil(left) + ".";
		if (candidates().isEmpty()) return "No faction is Favourable or better with you.";
		return null;
	}

	/**
	 * The player calls for aid from a sieged world's row at the fronts table's levers {marines,
	 * arms, fleet} (the user, 2026-10-29: "a general call for the combination of levers specified.
	 * need extra lever for fleet"; 2026-10-28: "zero or half or max of what they currently have,
	 * above a min threshold like 100"). The call stands aidRequestAnswerDays, then the slow tick
	 * answers it ({@link #answer} -> {@link #answerNow}): every candidate sends each lever's share
	 * of what its colonies in reach spare of that good, counting only colonies with
	 * aidRequestMinSpare or more, and the Fleet lever's share of its free hulls as a guard, all
	 * less its besetness ({@link #willingness}); the answers post as one intel entry
	 * ({@link ThreatAidAnswersIntel}). Cancel withdraws the call while it stands. From 2026-10-28
	 * to then a request was the colonies table's levers, answered at once and in full.
	 */
	public static boolean request(MarketAPI market, int[] levers) {
		if (requestOffReason(market, levers) != null) return false;
		ThreatNeeds.Need need = ThreatNeeds.mostUrgent(market);
		Request r = new Request();
		r.marketId = market.getId();
		r.purpose = need != null ? need.purpose : "the defence of " + market.getName();
		r.tiers = frontTiers(levers);
		r.fleetTier = levers.length > 2 ? Math.max(0, Math.min(2, levers[2])) : 0;
		r.fleets = r.fleetTier > 0;
		r.since = Global.getSector().getClock().getTimestamp();
		cancel(market);
		requests().add(r);
		askedAt().put(market.getId(), r.since);
		ThreatIncConfig.log("Aid requested for " + market.getName() + ": " + askText(r.tiers, r.fleetTier)
				+ ", answered in " + (int) answerDays() + " days");
		ThreatNotice.titled("Call For Aid").icon(market.getFaction())
				.line("%s calls the sector for %s", ThreatNotice.market(market), askText(r.tiers, r.fleetTier))
				.line("Answered in %s days", ThreatNotice.hl("" + (int) answerDays()))
				.send();
		return true;
	}

	/** The fronts' levers {marines, arms, fleet} as AID_COMMODITIES tiers: marines and arms, the rest None. */
	public static int[] frontTiers(int[] levers) {
		int[] tiers = new int[ThreatReserves.AID_COMMODITIES.length];
		if (levers != null) {
			for (int i = 0; i < 2 && i < levers.length && i < tiers.length; i++) {
				tiers[i] = Math.max(0, Math.min(2, levers[i]));
			}
		}
		return tiers;
	}

	/** Days a call stands before the sector answers it (aidRequestAnswerDays). */
	public static float answerDays() {
		return Math.max(0f, ThreatIncConfig.aidRequestAnswerDays());
	}

	/** Days until this call is answered, 0 when the next slow tick answers it. */
	public static float answerIn(Request r) {
		if (r == null) return 0f;
		return Math.max(0f, answerDays() - Global.getSector().getClock().getElapsedDaysSince(r.since));
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
	 * What of the levers' share a faction sends (the user, 2026-10-29: "really needs to depend on
	 * factions current level of besetness by the threat cross referenced with your overall
	 * standing"): 1 - its besetness x aidRequestBesetWeight, and nothing while that beset share
	 * outweighs the player's standing with it - a faction with half its worlds under the Threat
	 * sends half, and only to a player half way from Favourable to Cooperative.
	 */
	public static float willingness(FactionAPI helper) {
		float beset = Math.max(0f, Math.min(1f, besetness(helper) * ThreatIncConfig.aidRequestBesetWeight()));
		if (beset <= 0f) return 1f;
		if (standing(helper) < beset) return 0f;
		return 1f - beset;
	}

	/** "Hegemony: 2 of 7 worlds beset, standing 48 of 100 - sends x0.7 of the share, 12.3 standing to spend" - a faction's line on the Request tooltip. */
	public static String standLine(FactionAPI helper) {
		int[] c = besetCount(helper);
		float will = willingness(helper);
		return ThreatWarState.displayName(helper.getId()) + ": " + c[0] + " of " + c[1] + " worlds beset, standing "
				+ Math.round(standing(helper) * 100f) + " of 100 - "
				+ (will <= 0f ? "sends nothing"
						: (will >= 1f ? "sends the share, " : "sends x" + String.format("%.1f", will) + " of the share, ")
								+ String.format("%.1f", budget(helper)) + " standing to spend");
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

	/**
	 * Answers one request now, every candidate in turn: of each good at a lever, the lever's
	 * share (ThreatConvoys.share) of what the faction's colonies in reach spare of it in all
	 * ({@link #donors}: those at aidRequestMinSpare or more), filled from the colony sparing the
	 * most, each convoy priced in standing (pricePerUnit at the lever's tier) and cut to the
	 * budget that keeps the player at Favourable; a guard from the nearest base for a fleets
	 * need. Every faction gets a line a good in the intel - what sailed, or why nothing did.
	 */
	public static ThreatAidAnswersIntel answerNow(Request r, MarketAPI market, Random random) {
		migrate(r);
		int fleetTier = r.fleets ? Math.max(1, r.fleetTier) : 0;
		ThreatAidAnswersIntel intel = new ThreatAidAnswersIntel(market, askText(r.tiers, fleetTier));
		for (FactionAPI helper : candidates()) {
			// BESET (2026-10-29): a faction looks to its own worlds first - its share is cut by
			// their beset share, and it sends nothing while that outweighs the player's standing
			float will = willingness(helper);
			if (will <= 0f) {
				int[] c = besetCount(helper);
				String why = c[0] + " of " + c[1] + " worlds beset by the Threat, past your standing";
				for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length && i < r.tiers.length; i++) {
					if (r.tiers[i] > 0) intel.add(refusal(helper, ThreatReserves.AID_COMMODITIES[i], why));
				}
				if (r.fleets) intel.add(refusal(helper, ThreatNeeds.FLEETS, why));
				continue;
			}
			for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length && i < r.tiers.length; i++) {
				int tier = r.tiers[i];
				if (tier <= 0) continue;
				String good = ThreatReserves.AID_COMMODITIES[i];
				float per = pricePerUnit(market, good, tier);
				List<MarketAPI> donors = donors(helper, market, good);
				if (donors.isEmpty()) {
					intel.add(refusal(helper, good, "no colony in reach with " + Misc.getWithDGS((int) minSpare(good))
							+ " or more to spare"));
					continue;
				}
				float total = 0f;
				for (MarketAPI d : donors) total += ThreatConvoys.spare(d, good);
				float ask = (float) Math.floor(ThreatConvoys.share(tier) * total * will);
				boolean sailed = false;
				boolean budgetOut = false;
				for (MarketAPI donor : donors) {
					if (ask < 1f) break;
					float budget = budget(helper);
					float amount = Math.min(ask, ThreatConvoys.spare(donor, good));
					if (per > 0f && amount * per > budget) {
						amount = (float) Math.floor(budget / per);
						budgetOut = true;
					}
					amount = (float) Math.floor(amount);
					if (amount < 1f) break;
					float[] load = new float[ThreatReserves.AID_COMMODITIES.length];
					load[i] = amount;
					ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, market, helper, load, random,
							Factions.PLAYER, false);
					if (convoy == null) continue;
					float carried = Commodities.SHIPS.equals(good) ? convoy.ships : amount;
					if (carried < 1f) continue;
					float price = Math.min(budget, carried * per);
					debit(helper.getId(), price);
					ThreatAidAnswersIntel.Answer a = new ThreatAidAnswersIntel.Answer();
					a.factionId = helper.getId();
					a.good = good;
					a.amount = carried;
					a.fromName = donor.getName();
					a.rep = price;
					intel.add(a);
					ThreatIncConfig.log("Aid request answered: " + helper.getId() + " sends " + (int) carried + " "
							+ good + " from " + donor.getName() + " to " + market.getName() + ", rep -" + price);
					ask -= carried;
					sailed = true;
				}
				if (!sailed) {
					intel.add(refusal(helper, good, budgetOut ? "would take you below Favourable"
							: "no convoy could be raised"));
				}
			}
			if (r.fleets) {
				// the Fleet lever's share of the faction's free hulls (the hull pool; what the world
				// needs with the pool off), less its besetness, cut to the budget
				int tier = fleetTier;
				float per = pricePerUnit(market, ThreatNeeds.FLEETS, tier);
				float budget = budget(helper);
				MarketAPI base = ThreatFleetOrders.pickBase(helper, market.getLocationInHyperspace());
				float spare = ThreatHulls.enabled() ? ThreatHulls.freeFP(helper.getId())
						: ThreatFleetOrders.guardNeed(market);
				float fp = (float) Math.floor(ThreatConvoys.share(tier) * spare * will);
				boolean budgetOut = false;
				if (per > 0f && fp * per > budget) {
					fp = (float) Math.floor(budget / per);
					budgetOut = true;
				}
				if (base == null) {
					intel.add(refusal(helper, ThreatNeeds.FLEETS, "no base in reach"));
				} else if (fp < 1f) {
					intel.add(refusal(helper, ThreatNeeds.FLEETS, budgetOut ? "would take you below Favourable"
							: "no hulls to spare"));
				} else {
					float before = ThreatFleetOrders.guardPointsFor(market.getId());
					ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuardPoints(helper, market, base, fp);
					if (o == null) {
						intel.add(refusal(helper, ThreatNeeds.FLEETS, "nothing could be raised"));
					} else {
						float sailed = Math.max(0f, ThreatFleetOrders.guardPointsFor(market.getId()) - before);
						if (sailed <= 0f && o.fleet != null) sailed = o.fleet.getFleetPoints();
						float price = Math.min(budget, sailed * per);
						debit(helper.getId(), price);
						ThreatAidAnswersIntel.Answer a = new ThreatAidAnswersIntel.Answer();
						a.factionId = helper.getId();
						a.good = ThreatNeeds.FLEETS;
						a.amount = sailed;
						a.fromName = base.getName();
						a.rep = price;
						intel.add(a);
						ThreatIncConfig.log("Aid request answered: " + helper.getId() + " sends " + (int) sailed
								+ " FP from " + base.getName() + " to " + market.getName() + ", rep -" + price);
					}
				}
			}
		}
		return intel;
	}

	protected static ThreatAidAnswersIntel.Answer refusal(FactionAPI helper, String good, String why) {
		ThreatAidAnswersIntel.Answer a = new ThreatAidAnswersIntel.Answer();
		a.factionId = helper.getId();
		a.good = good;
		a.reason = why;
		return a;
	}

	/** "half of their spare marines and heavy armaments, and all of the hulls their navy has free" - what a request asks each faction for: AID_COMMODITIES tiers and the Fleet lever. */
	public static String askText(int[] tiers, int fleetTier) {
		List<String> parts = new ArrayList<String>();
		for (int tier = 2; tier >= 1; tier--) {
			List<String> goods = new ArrayList<String>();
			for (int i = 0; tiers != null && i < tiers.length && i < ThreatReserves.AID_COMMODITIES.length; i++) {
				if (tiers[i] == tier) goods.add(ThreatReserves.label(ThreatReserves.AID_COMMODITIES[i]));
			}
			if (!goods.isEmpty()) parts.add((tier == 2 ? "all" : "half") + " of their spare " + ThreatWarBoard.join(goods));
		}
		if (fleetTier > 0) parts.add((fleetTier >= 2 ? "all" : "half") + " of the hulls their navy has free");
		return parts.isEmpty() ? "nothing" : ThreatWarBoard.join(parts);
	}

	/** As above from the fronts table's levers {marines, arms, fleet}. */
	public static String askText(int[] frontLevers) {
		return askText(frontTiers(frontLevers), frontLevers != null && frontLevers.length > 2 ? frontLevers[2] : 0);
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

	/** The player withdraws the call before it is answered: nothing has sailed, and the cooldown is lifted with it. */
	public static boolean cancel(MarketAPI market) {
		Request r = requestAt(market);
		if (r == null) return false;
		requests().remove(r);
		askedAt().remove(market.getId());
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

	/** Slow tick: a call that has stood aidRequestAnswerDays is answered ({@link #answerNow}) and posted, then gone; one whose world is lost or sold is dropped. */
	protected static void answer(Random random) {
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		for (Request r : new ArrayList<Request>(requests())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(r.marketId);
			if (market == null || !market.isPlayerOwned()) {
				requests().remove(r);
				continue;
			}
			if (Global.getSector().getClock().getElapsedDaysSince(r.since) < answerDays()) continue;
			requests().remove(r);
			Global.getSector().getIntelManager().addIntel(answerNow(r, market, random));
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
