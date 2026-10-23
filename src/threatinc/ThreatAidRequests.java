package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;

/**
 * HELP REQUESTS (docs/player-aid.md section 4): what a mobilised faction's
 * colony needs, and the board that asks the sector for it.
 *
 * <p>On the slow tick every mobilised NPC faction's colonies are checked for
 * two needs. A DEFENCE need: a Threat strike is staged or in flight against
 * the colony and the defenders are outmatched (the strike's strength beats
 * the faction's strength in the system plus the station, times
 * defendRequestRatio). A SUPPLY need: the colony is short of its peacetime
 * demand and the depot cannot cover it - exhausted now, or the gap has stood
 * uncovered for missionAidShortageDays. A gap only in the War footing's share
 * is the war's supply not arriving, which the depot itself answers; it asks
 * nobody. Each need with no request already open posts one
 * ({@link ThreatAidMissionIntel}), up to aidRequestMaxPosted at once. The
 * same tests drive what allies send each other ({@link ThreatCoalition}).
 */
public class ThreatAidRequests {

	/** Persistent: "marketId:commodityId" -> when its deficit was first seen. */
	public static final String KEY_SHORTAGE = "threatinc_aidShortageSince";

	@SuppressWarnings("unchecked")
	public static Map<String, Long> shortageSince() {
		Object val = Global.getSector().getPersistentData().get(KEY_SHORTAGE);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(KEY_SHORTAGE, val);
		}
		return (Map<String, Long>) val;
	}

	protected static String key(MarketAPI market, String commodityId) {
		return market.getId() + ":" + commodityId;
	}

	/** Slow tick: remember when shortages began, then post what the sector should hear about. */
	public static void tick(Random random) {
		if (!ThreatWarState.enabled()) return;
		updateShortages();
		if (!ThreatIncConfig.aidRequestsEnabled()) return;
		int cap = Math.max(1, ThreatIncConfig.aidRequestMaxPosted());
		int posted = ThreatAidMissionIntel.countPosted();
		// BATTLE REQUESTS first (the user, 2026-10-23): a colony under a Threat army asks for the marines its
		// counter-attack wants and the armaments they burn, outside the posted cap - the war's own asks
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				if (market.getPrimaryEntity() == null || market.getStarSystem() == null || !underArmy(market)) continue;
				for (String c : new String[] { Commodities.MARINES, Commodities.HAND_WEAPONS }) {
					int need = battleItems(market, c);
					if (need <= 0) continue;
					ThreatAidMissionIntel open = ThreatAidMissionIntel.find(market.getId(), ThreatAidMissionIntel.KIND_AID, c);
					if (open != null) { open.raiseNeed(need); continue; }
					ThreatAidMissionIntel.postAid(market, c, need);
				}
			}
		}
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				if (posted >= cap) return;
				if (market.getPrimaryEntity() == null || market.getStarSystem() == null) continue;
				if (needsDefence(market) && ThreatAidMissionIntel.find(market.getId(),
						ThreatAidMissionIntel.KIND_DEFEND, null) == null) {
					if (ThreatAidMissionIntel.postDefend(market) != null) posted++;
				}
				for (String c : ThreatReserves.AID_COMMODITIES) {
					if (posted >= cap) return;
					// (2026-10-10: a colony without a Waystation asked for nothing - a convoy lands anywhere)
					if (!shortageStanding(market, c)) continue;
					if (ThreatAidMissionIntel.find(market.getId(),
							ThreatAidMissionIntel.KIND_AID, c) != null) continue;
					int need = missionItems(market, c);
					if (need <= 0) continue;
					if (ThreatAidMissionIntel.postAid(market, c, need) != null) posted++;
				}
			}
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

	/** What a request asks for: the peacetime gap in items. The War footing's share is not asked for. */
	public static int requestItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.localDeficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.localDeficit * s.econUnit));
	}

	/** Whether a Threat army stands on the colony: its requests are then the battle's (marines, armaments), not the depot's. */
	public static boolean underArmy(MarketAPI market) {
		return market != null && ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()));
	}

	/**
	 * BATTLE REQUESTS (the user, 2026-10-23, Sindria asking for fuel and armaments while its last district fell:
	 * "any colony that is under siege and losing should ask for help ... if its front needs marines then that
	 * should be the mission. if marines need armaments then that. supplies and fuel should be a not under attack
	 * mission ... currently i have no way to give sindria marines"): what a colony under a Threat army asks the
	 * board for - the marines its counter-attack still wants (ThreatGroundFronts.reliefNeed) and the armaments
	 * they burn (defenderArmsNeed), net of what is at sea to it (ThreatConvoys.inbound). 0 for any other good or
	 * with no army. The dock's hand-over and the board's Aid serve these as any request; the swarm bounty covers
	 * the orbit (ThreatSwarmBountyIntel.postRelief). Knob aidBattleRequests.
	 */
	public static int battleItems(MarketAPI market, String commodityId) {
		if (!ThreatIncConfig.aidBattleRequests() || !underArmy(market)) return 0;
		// (2026-10-23, the user's test: Sindria asked for nothing - a 200-marine convoy held off by the blockade counted as
		// at sea while the last district fell in 9 days) only what will land is netted out: no convoy lands while the
		// swarm holds the orbit (the blockade door holds every one), and a held-off convoy never counts
		float[] atSea = Factions.THREAT.equals(ThreatGroundFronts.spaceHolder(market))
				? new float[ThreatReserves.COMMODITIES.length] : ThreatConvoys.inbound(market.getId(), true);
		float need = 0f;
		if (Commodities.MARINES.equals(commodityId)) need = ThreatGroundFronts.reliefNeed(market) - atSea[0];
		else if (Commodities.HAND_WEAPONS.equals(commodityId)) need = ThreatGroundFronts.defenderArmsNeed(market) - atSea[1];
		return Math.max(0, Math.round(need));
	}

	/**
	 * What a request on the board asks for: under a Threat army the battle's need (battleItems, or the peacetime
	 * gap if larger; fuel and supplies ask nobody - those are not-under-attack missions, and a posted one withdraws),
	 * else the peacetime gap (requestItems). Allies' convoys keep reading requestItems (ThreatCoalition).
	 */
	public static int missionItems(MarketAPI market, String commodityId) {
		if (ThreatIncConfig.aidBattleRequests() && underArmy(market)) {
			if (Commodities.FUEL.equals(commodityId) || Commodities.SUPPLIES.equals(commodityId)) return 0;
			if (Commodities.MARINES.equals(commodityId) || Commodities.HAND_WEAPONS.equals(commodityId)) {
				return Math.max(battleItems(market, commodityId), requestItems(market, commodityId));
			}
		}
		return requestItems(market, commodityId);
	}

	/** The deficit in items: vanilla's units short times the commodity's econ unit. */
	public static int needItems(MarketAPI market, String commodityId) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, commodityId);
		if (s == null || s.deficit <= 0 || s.econUnit <= 0f) return 0;
		return Math.max((int) s.econUnit, Math.round(s.deficit * s.econUnit));
	}

	/**
	 * The commodity the colony is shortest of, in items - an open request's
	 * commodity first, else the largest deficit. Null when nothing is short.
	 */
	public static String worstShortage(MarketAPI market) {
		if (market == null) return null;
		for (ThreatAidMissionIntel m : ThreatAidMissionIntel.getOpen()) {
			if (m.getKind() != ThreatAidMissionIntel.KIND_AID) continue;
			if (!market.getId().equals(m.getMarketId())) continue;
			if (m.remaining() > 0) return m.getCommodityId();
		}
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

	/** A strike is coming and the defenders are outmatched. */
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
