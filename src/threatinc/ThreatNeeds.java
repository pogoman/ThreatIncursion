package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * THE LEDGER OF NEEDS (docs/war-effort-rework.md section 1, the user 2026-10-23: "do away with the
 * arbitrary mission system ... a clearer picture on each faction's board of what they are short of
 * at any given planet"): what a colony is short of, by PURPOSE, computed live from the engine's
 * own figures - never posted, accepted, failed or expired, and nothing pays credits. A need is
 * (colony, purpose, good, min / med / max); the purpose names why and is the word the board's
 * Needs column, the dock and the war comms all use. The player's Aid button serves the most urgent
 * one ({@link ThreatAid#quoteAid}), the dock lists them all ({@link ThreatincAidCMD}), allies
 * answer them by willingness ({@link ThreatCoalition#allyAid}), the player asks for them at a rep
 * price ({@link ThreatAidRequests}), and standing for giving is weighted by them
 * ({@link #purposeWeights}).
 *
 * <p>A world the swarm holds the orbit over has one need that can be answered: fleets. Its goods
 * needs are SUSPENDED (the blockade door holds every convoy): shown grey on the board, asked of
 * nobody, still listed at the dock for the player who fought their way in.
 *
 * <p>{@link #tick} is the war comms: one voiced notice when a need appears, one when it
 * escalates, one when it is met ({@link ThreatComms}), throttled per world and purpose.
 */
public class ThreatNeeds {

	public static final String HOLD = "hold the line";
	public static final String PUSH_BACK = "push back";
	public static final String ARM = "arm the defenders";
	public static final String ORBIT = "lift the orbit";
	public static final String DEPOT = "keep the depot";
	public static final String NAVY = "rebuild the navy";
	/** A player colony short of the hulls to field one task force (the user, 2026-10-24: Request "doesnt consider a lack of hulls to be a shortage"); the hulls land and stay there (ThreatConvoys, ThreatHulls.moveHome). */
	public static final String FLEET = "field a task force";
	public static final String EXPEDITION = "provision the expedition";
	/** The good of a fleets need: warships over the world, in fleet points. */
	public static final String FLEETS = "fleets";

	public static final int ROUTINE = 0;
	public static final int URGENT = 1;
	public static final int CRITICAL = 2;

	/** Persistent: base market id -> "systemId|fuelShort|fuelFull|suppliesShort|suppliesFull|timestamp" (a postponed siege). */
	public static final String KEY_POSTPONED = "threatinc_needPostponed";
	/** Persistent: "marketId:purpose" -> the stage word the comms last spoke for it ("", "overrun", "exhausted", "met"). */
	public static final String KEY_COMMS_STAGE = "threatinc_needCommsStage";
	/** Persistent: "marketId:purpose" -> when the comms last spoke of it. */
	public static final String KEY_COMMS_AT = "threatinc_needCommsAt";

	/** One need of one colony. Figures are net of what will land (convoys not held off) or is bound (guards). */
	public static class Need {
		public MarketAPI market;
		public String purpose;
		/** A commodity id, or {@link #FLEETS}. */
		public String good;
		public float min;
		public float med;
		public float max;
		/** The swarm holds the orbit: no convoy lands, nobody is asked; the dock still lists it. */
		public boolean suspended;
		public int urgency;
		/** A fleets need: points already bound for or over the world. */
		public float bound;
		/** A fleets need raised by a strike in flight rather than a swarm over the world. */
		public boolean strike;
		/** An expedition need: the system the siege sails against. */
		public String target;
		/** A hold need: days to the next district at this pace, -1 unknown. */
		public int days = -1;
		/** A hold need with the line collapsing (the world-side word OVERRUN). */
		public boolean overrun;
		/** A depot need standing exhausted. */
		public boolean exhausted;
		/** A hold / push back need: the Threat troops the world fights. */
		public float troops;

		/** The ask at a board tier (0 Min, 1 Med, 2 Max): the first tier from it with a figure. */
		public float tier(int t) {
			if (t <= 0 && min > 0f) return min;
			if (t <= 1 && med > 0f) return med;
			return max;
		}

		public boolean isFleets() {
			return FLEETS.equals(good);
		}

		public String goodLabel() {
			return isFleets() ? "fleet points" : ThreatReserves.label(good);
		}

		public Color color() {
			return ThreatNeeds.color(urgency, suspended);
		}

		/** "hold the line - marines - 320 / 410 / 560" (" - blockaded" when suspended; a fleets need adds the bound points). */
		public String line() {
			String s = purpose + " - " + goodLabel() + " - " + figures();
			if (isFleets() && bound > 0f) s += ", " + Misc.getWithDGS(Math.round(bound)) + " bound";
			if (suspended) s += " - blockaded";
			return s;
		}

		/**
		 * What a tier buys, as "N to <this>" (0 Min, 1 Med, 2 Max): the user, 2026-10-24
		 * ("Sindria's depot is out of supplies. Supplies: 6,000 / 12,000 / 12,000 - what
		 * the fuck does that even mean").
		 */
		public String tierWord(int t) {
			String[] w;
			if (HOLD.equals(purpose)) w = new String[] { "hold the line", "push back", "overrun" };
			else if (PUSH_BACK.equals(purpose)) w = new String[] { "push back", "overrun", "overrun with a reserve" };
			else if (ARM.equals(purpose)) w = new String[] { "arm them 30 days", "arm them 60 days", "fill the depot" };
			else if (ORBIT.equals(purpose)) w = new String[] { "lift the orbit", "add a margin", "add a reserve" };
			else if (NAVY.equals(purpose)) w = new String[] { "rebuild the navy", "add a margin", "add a reserve" };
			else if (FLEET.equals(purpose)) w = new String[] { "field a task force", "add a margin", "add a reserve" };
			else if (EXPEDITION.equals(purpose)) w = new String[] { "sail", "sail with a margin", "sail the full voyage" };
			else w = new String[] { "cover the gap", "add a margin", "fill the depot" };
			return w[Math.max(0, Math.min(2, t))];
		}

		/** "320 to hold the line, 410 to push back, 560 to overrun": a tier already met reads "enough to ...", equal tiers read once. */
		public String figures() {
			float[] tiers = { min, med, max };
			StringBuilder sb = new StringBuilder();
			long last = -1;
			for (int t = 0; t < 3; t++) {
				long v = Math.round(tiers[t]);
				if (v == last) continue;
				last = v;
				if (sb.length() > 0) sb.append(", ");
				sb.append(v <= 0 ? "enough" : shown(tiers[t])).append(" to ").append(tierWord(t));
			}
			return sb.toString();
		}

		public String shown(float v) {
			if (isFleets()) return Misc.getWithDGS(Math.round(v)) + " FP";
			return Misc.getWithDGS(ThreatHulls.shown(good, Math.round(v)));
		}
	}

	/** The colour of an urgency: critical red, urgent yellow, routine white; grey while suspended. */
	public static Color color(int urgency, boolean suspended) {
		if (suspended) return Misc.getGrayColor();
		if (urgency >= CRITICAL) return Misc.getNegativeHighlightColor();
		if (urgency == URGENT) return Misc.getHighlightColor();
		return Misc.getTextColor();
	}

	/** Standing weight for giving toward a purpose (docs/war-effort-rework.md section 4); null = a good nobody needs. */
	public static float weight(String purpose) {
		if (purpose == null) return ThreatIncConfig.aidRepWeightSurplus();
		if (HOLD.equals(purpose) || ORBIT.equals(purpose)) return ThreatIncConfig.aidRepWeightCritical();
		if (PUSH_BACK.equals(purpose) || ARM.equals(purpose) || EXPEDITION.equals(purpose)) {
			return ThreatIncConfig.aidRepWeightUrgent();
		}
		return ThreatIncConfig.aidRepWeightRoutine();
	}

	// ------------------------------------------------------------------
	// the ledger
	// ------------------------------------------------------------------

	/** Every need of the colony, most urgent first within its kind: the line, arms, the orbit, the depot, the navy, the expedition. Nothing for a hive or a world of the swarm's. */
	public static List<Need> of(MarketAPI m) {
		List<Need> out = new ArrayList<Need>();
		if (m == null || m.getStarSystem() == null || m.getPrimaryEntity() == null) return out;
		if (m.getFaction() == null || Factions.THREAT.equals(m.getFactionId())) return out;
		if (ThreatGroundFronts.isHiveTarget(m)) return out;
		String id = m.getId();
		boolean army = ThreatAidRequests.underArmy(m);
		boolean blockaded = army && Factions.THREAT.equals(ThreatGroundFronts.spaceHolder(m));
		// what will land: no convoy lands while the swarm holds the orbit (the blockade door), and a held-off one never counts
		float[] atSea = blockaded ? new float[ThreatReserves.COMMODITIES.length] : ThreatConvoys.inbound(id, true);
		float extra = Math.max(1f, ThreatIncConfig.convoyExtraLoadFactor());
		boolean marinesAsked = false;
		boolean armsAsked = false;
		if (army) {
			ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(id);
			String word = ThreatGroundFronts.lineState(front, m, false);
			float hold = Math.max(0f, ThreatGroundFronts.reliefNeed(m, 0f) - atSea[0]);
			float back = Math.max(hold, ThreatGroundFronts.reliefNeed(m, ThreatIncConfig.frontPushBackRate()) - atSea[0]);
			float over = Math.max(back, ThreatGroundFronts.reliefNeed(m, ThreatIncConfig.frontOverrunRate()) - atSea[0]);
			boolean losing = ThreatGroundFronts.LINE_LOSING.equals(word) || ThreatGroundFronts.LINE_OVERRUN.equals(word);
			boolean held = ThreatGroundFronts.LINE_GRINDING.equals(word) || ThreatGroundFronts.LINE_STALLED.equals(word);
			if (losing && over > 0f) {
				Need n = need(m, HOLD, Commodities.MARINES, hold, back, over, CRITICAL, blockaded);
				n.days = Math.round(ThreatGroundFronts.daysToLineChange(front, m));
				n.overrun = ThreatGroundFronts.LINE_OVERRUN.equals(word);
				n.troops = ThreatGroundFronts.effectiveStrength(front);
				out.add(n);
				marinesAsked = true;
			} else if (held && over > 0f) {
				Need n = need(m, PUSH_BACK, Commodities.MARINES, back, over, over * extra, URGENT, blockaded);
				n.troops = ThreatGroundFronts.effectiveStrength(front);
				out.add(n);
				marinesAsked = true;
			}
			float arms = Math.max(0f, ThreatGroundFronts.defenderArmsNeed(m) - atSea[1]);
			if (arms > 0f) {
				// 30 days of the defenders' burn (defenderArmsNeed: frontResupplyDays), 60, the stockpile's capacity
				float standing = ThreatReserves.stock(id, Commodities.MARINES) + ThreatGroundFronts.reliefNeed(m);
				float burn = ThreatGroundFronts.dailyUpkeep(standing);
				float have = ThreatReserves.stock(id, Commodities.HAND_WEAPONS) + atSea[1];
				float med = Math.max(arms, burn * 2f * ThreatIncConfig.frontResupplyDays() - have);
				float max = Math.max(med, ThreatReserves.cap(m, Commodities.HAND_WEAPONS) - have);
				Need n = need(m, ARM, Commodities.HAND_WEAPONS, arms, med, max, URGENT, blockaded);
				n.troops = burn;
				out.add(n);
				armsAsked = true;
			}
		}
		boolean strike = ThreatAidRequests.needsDefence(m);
		if (blockaded || strike) {
			float need = ThreatFleetOrders.guardNeed(m);
			float bound = ThreatFleetOrders.guardPointsFor(id) + ThreatFleetOrders.playerPointsFor(m);
			if (need * 1.25f - bound > 0f) {
				Need n = need(m, ORBIT, FLEETS, Math.max(0f, need - bound), Math.max(0f, need * 1.25f - bound),
						Math.max(0f, need * 1.5f - bound), CRITICAL, false);
				n.bound = bound;
				n.strike = strike && !blockaded;
				out.add(n);
			}
		}
		for (String c : ThreatReserves.AID_COMMODITIES) {
			if (Commodities.SHIPS.equals(c)) continue;
			if (Commodities.MARINES.equals(c) && marinesAsked) continue;
			if (Commodities.HAND_WEAPONS.equals(c) && armsAsked) continue;
			if (!ThreatAidRequests.shortageStanding(m, c)) continue;
			int idx = ThreatAid.index(c);
			float min = ThreatAidRequests.requestItems(m, c) - atSea[idx];
			if (min < 1f) continue;
			float med = min * extra;
			float max = Math.max(med, ThreatReserves.cap(m, c) - ThreatReserves.stock(id, c) - atSea[idx]);
			Need n = need(m, DEPOT, c, min, med, max, ROUTINE, blockaded);
			ThreatReserves.CommodityStatus s = ThreatReserves.status(m, c);
			n.exhausted = s != null && s.exhausted;
			out.add(n);
		}
		String f = m.getFactionId();
		if (ThreatHulls.enabled() && m == ThreatHulls.hullPort(f) && ThreatFactionStock.hullsShort(f)) {
			float unitFP = Math.max(1f, ThreatHulls.unitFP());
			float units = Math.max(ThreatHulls.shortUnits(f),
					(float) Math.ceil(ThreatFactionStock.hullShortFP(f) / unitFP)) - ThreatConvoys.inboundShips(id);
			if (units >= 1f) {
				out.add(need(m, NAVY, Commodities.SHIPS, (float) Math.ceil(units), (float) Math.ceil(units * 1.25f),
						(float) Math.ceil(units * 1.5f), ROUTINE, false));
			}
		}
		// a colony of the player's short of the hulls to field one task force (the user, 2026-10-24: Request
		// "doesnt consider a lack of hulls to be a shortage"): the hull port asks for the navy, every other
		// colony for its own hulls - what lands there stays (ThreatConvoys, ThreatHulls.moveHome)
		if (ThreatHulls.enabled() && m.isPlayerOwned() && ThreatAidCapacity.enabled() && ThreatAidCapacity.applies(m)
				&& m != ThreatHulls.hullPort(f)) {
			float unitFP = Math.max(1f, ThreatHulls.unitFP());
			float own = Math.max(0f, ThreatAidCapacity.ownFreeFP(m));
			float lack = ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP()) - own
					- ThreatConvoys.inboundShips(id) * unitFP;
			if (lack >= unitFP * 0.5f) {
				float units = (float) Math.ceil(lack / unitFP);
				out.add(need(m, FLEET, Commodities.SHIPS, units, (float) Math.ceil(units * 1.25f),
						(float) Math.ceil(units * 1.5f), own <= 0f ? URGENT : ROUTINE, false));
			}
		}
		float[] p = postponed(id);
		if (p != null) {
			String target = postponedTarget(id);
			if (p[0] > 0f) {
				float min = p[0] - atSea[2];
				float full = p[1] - atSea[2];
				if (min >= 1f) {
					Need n = need(m, EXPEDITION, Commodities.FUEL, min, Math.min(full, min * 1.25f), full, URGENT, false);
					n.target = target;
					out.add(n);
				}
			}
			if (p[2] > 0f) {
				float min = p[2] - atSea[3];
				float full = p[3] - atSea[3];
				if (min >= 1f) {
					Need n = need(m, EXPEDITION, Commodities.SUPPLIES, min, Math.min(full, min * 1.25f), full, URGENT, false);
					n.target = target;
					out.add(n);
				}
			}
		}
		return out;
	}

	protected static Need need(MarketAPI m, String purpose, String good, float min, float med, float max,
			int urgency, boolean suspended) {
		Need n = new Need();
		n.market = m;
		n.purpose = purpose;
		n.good = good;
		n.min = Math.max(0f, min);
		n.med = Math.max(n.min, med);
		n.max = Math.max(n.med, max);
		n.urgency = urgency;
		n.suspended = suspended;
		return n;
	}

	/** The need the Aid button and the Needs cell serve: highest urgency, one that can be answered before a suspended one, in ledger order. Null with none. */
	public static Need mostUrgent(MarketAPI m) {
		return mostUrgent(of(m), false);
	}

	/** As above among the goods needs (a convoy's: never fleets). */
	public static Need mostUrgentGoods(MarketAPI m) {
		return mostUrgent(of(m), true);
	}

	public static Need mostUrgent(List<Need> needs, boolean goodsOnly) {
		Need best = null;
		for (Need n : needs) {
			if (goodsOnly && n.isFleets()) continue;
			if (best == null) {
				best = n;
				continue;
			}
			if (best.suspended != n.suspended) {
				if (best.suspended) best = n;
				continue;
			}
			if (n.urgency > best.urgency) best = n;
		}
		return best;
	}

	/** The colony's fleets need, or null. */
	public static Need fleets(MarketAPI m) {
		for (Need n : of(m)) {
			if (n.isFleets()) return n;
		}
		return null;
	}

	/** The colony's need of this purpose (the first, for an expedition short of both), or null. */
	public static Need find(MarketAPI m, String purpose) {
		return find(of(m), purpose, null);
	}

	public static Need find(List<Need> needs, String purpose, String good) {
		for (Need n : needs) {
			if (purpose != null && !purpose.equals(n.purpose)) continue;
			if (good != null && !good.equals(n.good)) continue;
			return n;
		}
		return null;
	}

	/** The colony's marines need (hold the line or push back), or null. */
	public static Need marines(MarketAPI m) {
		Need n = find(m, HOLD);
		return n != null ? n : find(m, PUSH_BACK);
	}

	/** The Needs cell: the most urgent purpose, "blockaded" when every need the swarm's orbit suspends is all there is, "-" with none. */
	public static String cellText(List<Need> needs) {
		Need n = mostUrgent(needs, false);
		if (n == null) return "-";
		if (n.suspended) return "blockaded";
		return n.purpose;
	}

	public static Color cellColor(List<Need> needs) {
		Need n = mostUrgent(needs, false);
		if (n == null) return Misc.getGrayColor();
		return n.color();
	}

	/**
	 * STANDING WEIGHTS for a delivery (section 4), in ThreatReserves.AID_COMMODITIES order, read
	 * BEFORE the goods land: each good's quantity up to the best need's Max earns the purpose's
	 * weight, the surplus - or a good nobody needs - the surplus weight. The player's own fleet
	 * handing over at a blockaded dock serves the suspended need in full.
	 */
	public static float[] purposeWeights(MarketAPI m, float[] qty) {
		float[] w = new float[ThreatReserves.AID_COMMODITIES.length];
		float surplus = ThreatIncConfig.aidRepWeightSurplus();
		List<Need> needs = of(m);
		for (int i = 0; i < w.length; i++) {
			w[i] = surplus;
			if (qty == null || i >= qty.length || qty[i] <= 0f) continue;
			Need best = null;
			for (Need n : needs) {
				if (!ThreatReserves.AID_COMMODITIES[i].equals(n.good)) continue;
				if (best == null || weight(n.purpose) > weight(best.purpose)) best = n;
			}
			if (best == null || best.max <= 0f) continue;
			float served = Math.min(qty[i], best.max);
			w[i] = (served * weight(best.purpose) + (qty[i] - served) * surplus) / qty[i];
		}
		return w;
	}

	// ------------------------------------------------------------------
	// postponed expeditions (IncursionManager.launchSiegeExpedition)
	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	protected static Map<String, String> postponedAll() {
		Object val = Global.getSector().getPersistentData().get(KEY_POSTPONED);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, String>();
			Global.getSector().getPersistentData().put(KEY_POSTPONED, val);
		}
		return (Map<String, String>) val;
	}

	/**
	 * A siege at the base against the system waits for provisions: what it has and what the voyage
	 * wants of fuel and supplies, and the fraction it must hold to sail (expeditionMinProvisionsFraction).
	 * Stands aidExpeditionNoteDays, refreshed while the siege keeps waiting.
	 */
	public static void notePostponed(MarketAPI base, StarSystemAPI system, float haveFuel, float wantFuel,
			float haveSupplies, float wantSupplies, float minFraction) {
		if (base == null || system == null) return;
		float fuelShort = Math.max(0f, wantFuel * minFraction - haveFuel);
		float suppliesShort = Math.max(0f, wantSupplies * minFraction - haveSupplies);
		if (fuelShort < 1f && suppliesShort < 1f) return;
		postponedAll().put(base.getId(), system.getId() + "|" + (int) fuelShort + "|"
				+ (int) Math.max(fuelShort, wantFuel - haveFuel) + "|" + (int) suppliesShort + "|"
				+ (int) Math.max(suppliesShort, wantSupplies - haveSupplies) + "|"
				+ Global.getSector().getClock().getTimestamp());
	}

	/** {fuelShort, fuelFull, suppliesShort, suppliesFull} of the base's waiting siege, or null. */
	public static float[] postponed(String baseId) {
		String s = postponedAll().get(baseId);
		if (s == null) return null;
		String[] parts = s.split("\\|");
		if (parts.length < 6) return null;
		try {
			long at = Long.parseLong(parts[5]);
			if (Global.getSector().getClock().getElapsedDaysSince(at) > ThreatIncConfig.aidExpeditionNoteDays()) {
				postponedAll().remove(baseId);
				return null;
			}
			return new float[] { Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
					Float.parseFloat(parts[3]), Float.parseFloat(parts[4]) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** The name of the system the base's waiting siege sails against; null when none is known. */
	public static String postponedTarget(String baseId) {
		String s = postponedAll().get(baseId);
		if (s == null) return null;
		StarSystemAPI system = ThreatScoutRoute.systemById(s.split("\\|")[0]);
		return system != null ? system.getNameWithNoType() : null;
	}

	/** The siege sailed or was dropped: its note goes. */
	public static void clearPostponed(String baseId) {
		if (baseId != null) postponedAll().remove(baseId);
	}

	// ------------------------------------------------------------------
	// the war comms (section 6)
	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	protected static Map<String, String> commsStage() {
		Object val = Global.getSector().getPersistentData().get(KEY_COMMS_STAGE);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, String>();
			Global.getSector().getPersistentData().put(KEY_COMMS_STAGE, val);
		}
		return (Map<String, String>) val;
	}

	@SuppressWarnings("unchecked")
	protected static Map<String, Long> commsAt() {
		Object val = Global.getSector().getPersistentData().get(KEY_COMMS_AT);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Long>();
			Global.getSector().getPersistentData().put(KEY_COMMS_AT, val);
		}
		return (Map<String, Long>) val;
	}

	protected static String key(String marketId, String purpose, String good) {
		return marketId + ":" + purpose + (EXPEDITION.equals(purpose) || DEPOT.equals(purpose) ? ":" + good : "");
	}

	protected static boolean throttled(String key) {
		Long at = commsAt().get(key);
		return at != null && Global.getSector().getClock().getElapsedDaysSince(at) < ThreatIncConfig.aidCommsMinDays();
	}

	protected static void spoke(String key, String stage) {
		commsStage().put(key, stage);
		commsAt().put(key, Global.getSector().getClock().getTimestamp());
	}

	/**
	 * Slow tick: the dock's flag on every mobilised NPC colony, then the comms - a need that is new
	 * speaks once, one that escalates (hold the line -> overrun, a depot -> exhausted) once, one
	 * that is gone once (the line holds / pushes back / the world is clear / the sky is clear /
	 * resupplied / sails); aidCommsMinDays between notices for one world and purpose, one met
	 * notice a world a tick. Nothing on a figure changing.
	 */
	public static void tick(Random random) {
		if (!ThreatWarState.enabled()) return;
		Map<String, String> stage = commsStage();
		List<String> live = new ArrayList<String>();
		List<String> metSpoken = new ArrayList<String>();
		boolean comms = ThreatIncConfig.aidCommsEnabled();
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null) continue;
			for (MarketAPI m : ThreatReserves.marketsOf(factionId)) {
				if (m.getPrimaryEntity() == null || m.getStarSystem() == null) continue;
				if (!faction.isPlayerFaction()) {
					// the station commander takes a hand-over at any mobilised colony (rules.csv)
					m.getMemoryWithoutUpdate().set(ThreatAidMissionIntel.MEM_REQUEST, true, 2f);
				}
				List<Need> needs = of(m);
				for (Need n : needs) {
					if (n.suspended) continue;
					String k = key(m.getId(), n.purpose, n.good);
					live.add(k);
					String was = stage.get(k);
					String now = n.overrun ? "overrun" : n.exhausted ? "exhausted" : "";
					if (!comms) {
						stage.put(k, now);
						continue;
					}
					if (was == null || "met".equals(was)) {
						if (throttled(k)) continue;
						ThreatComms.appears(faction, m, n);
						spoke(k, now);
					} else if (!now.isEmpty() && !now.equals(was)) {
						if (throttled(k)) continue;
						ThreatComms.appears(faction, m, n);
						spoke(k, now);
					}
				}
				if (!comms) continue;
				// needs gone since the last tick
				for (String k : new ArrayList<String>(stage.keySet())) {
					if (!k.startsWith(m.getId() + ":") || live.contains(k) || "met".equals(stage.get(k))) continue;
					String[] parts = k.split(":");
					String purpose = parts[1];
					String good = parts.length > 2 ? parts[2] : null;
					boolean spokenHere = metSpoken.contains(m.getId());
					if (!spokenHere && ThreatComms.met(faction, m, purpose, good, needs)) metSpoken.add(m.getId());
					spoke(k, "met");
				}
			}
		}
		// worlds lost or gone: their entries end quietly (the colony-lost notice is the engine's)
		for (String k : new ArrayList<String>(stage.keySet())) {
			if (live.contains(k) || "met".equals(stage.get(k))) continue;
			String marketId = k.split(":")[0];
			MarketAPI m = Global.getSector().getEconomy().getMarket(marketId);
			if (m == null || !ThreatWarState.isAtWar(m.getFactionId())) stage.put(k, "met");
		}
	}
}
