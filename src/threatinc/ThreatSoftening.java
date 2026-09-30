package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;

/**
 * HUNTING FORCES (2026-09-24, docs/strategy-layer.md "Hunting forces"): a
 * mobilised NPC faction softens a bountied hive (ThreatSwarmBountyIntel)
 * with its own ships. For each hive system with a running swarm bounty, every
 * mobilised NPC faction raises a hunting force at its best-paid base in reach
 * ({@link #huntBases}) - never at one with a hive of its own to siege
 * (IncursionManager.hasSiegeableHive): the siege always comes first.
 *
 * <p>A hunting force carries no marines and lands nothing, so every point is a
 * warship: it is sized to beat the whole system's Defense Swarms by the
 * siege's orbit margin ({@link #margin}), as big as its depots can pay for (no ceiling, 2026-09-29),
 * split into fleets of at most softenFleetFP, and paid for as a siege is, in fuel and supplies. With
 * softenPool every base of the faction in reach chips in, nearest first. The
 * odds are counted on the warships the yards actually build (vanilla scales an
 * NPC fleet by its market), and a force that cannot beat its target's garrison
 * as it stands now by the margin does not sail ({@link #musterFloorFP}).
 *
 * <p>The fleets MUSTER (2026-09-24 review): each flies blinkered to the hive
 * system's hyperspace anchor and waits there until the whole force is in, or
 * softenMusterDays after the first arrival, then all go in together. Before
 * this every fleet flew on its own and met the whole garrison alone - 69 of a
 * 21-month run's hunting fleets broke off badly hurt. The force AIMS AT THE
 * GATE (2026-09-26, {@link #strongest}): its target is the world the siege's
 * orbit gate reads - the strongest garrison among the worlds the siege is
 * fighting (a siege takes a subset of the system since 2026-09-27: the
 * caller's purge targets for a coalition answer, the poster's siegeTargets
 * for a bounty hunt, {@link Force#targetIds}; the whole system while its
 * swarms are what the gate weighs, {@link #gateWorlds}) - or it does not sail; it
 * moves on to the next strongest of them only while what is left of it still
 * beats that garrison by the margin, and goes home whole when they are clear
 * or it falls below softenRetreatStrength of its strength when it went in or
 * last moved on.
 * Weakest first, Run 5's forces beat Loka (25 FP) and Aigor (14) while the
 * gate read the 5-6.7k FP world beside them, which refilled them in days:
 * ~277k supplies and ~497k fuel gross for no gate opened in 2.4 years. The
 * player's Hunt (no force) keeps the single-fleet rules, weakest first. One
 * force per faction per system; a base waits softenIntervalDays after sending
 * fleets to one.
 *
 * <p>{@link #tick} raises forces on the strategy tick; {@link #advanceHunts}
 * runs on the fleet-order poll (every half day) - on the monthly tick a
 * battered hunt could fight on for 30 days before anything looked.
 */
public class ThreatSoftening {

	public static final String KEY_LAST = "threatinc_softenLast";
	public static final String KEY_FORCES = "threatinc_huntForces";
	/** How close to its muster point a fleet counts as mustered. */
	protected static final float MUSTER_RANGE = 1500f;
	/** How far from the hive system's hyperspace anchor each faction's muster point lies. */
	protected static final float MUSTER_OFFSET = 3000f;
	/** How close to its target the lead fleet takes the force's blinkers off. */
	protected static final float CLOSE_RANGE = 2500f;

	/** One NPC hunting force: the fleets of every hunt order carrying its id. */
	public static class Force {
		public String id;
		public String factionId;
		public String systemId;
		/** Gathered and sent in; false while its fleets muster. */
		public boolean engaged;
		public long launchedTimestamp;
		/** When the first fleet reached the muster point; 0 before. */
		public long firstArrivalTimestamp;
		/** Warship FP when it went in or last moved on - what "badly hurt" is measured from. */
		public float baseFP;
		/** The muster point in hyperspace (unused when {@link #musterInSystem}). */
		public float musterX, musterY;
		/** The primary base is inside the hive system: the force musters there, not in hyperspace. */
		public boolean musterInSystem;
		/** The entity an in-system force musters at (the primary base's planet). */
		public String musterEntityId;
		/** Fleet ids counted as the force's strength since it went in; null before, and on older saves. */
		public java.util.Set<String> inForce;
		/** The fleet the rest FOLLOW in (the slowest), so the force arrives as one. */
		public String leadFleetId;
		/** The lead is over its target and has its blinkers off (the followers keep theirs). */
		public boolean close;
		/** Ids of the worlds the siege the force covers is fighting; null (and on older saves) for the whole system. */
		public java.util.List<String> targetIds;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Force> forces() {
		Object val = Global.getSector().getPersistentData().get(KEY_FORCES);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Force>();
			Global.getSector().getPersistentData().put(KEY_FORCES, val);
		}
		return (Map<String, Force>) val;
	}

	/** Base market id -> when it last sent fleets to a hunting force. */
	public static Map<String, Long> lastSent() {
		return ThreatIncData.map(KEY_LAST);
	}

	/** Whether the order's fleet is still mustering with its force. */
	public static boolean mustering(ThreatFleetOrders.Order o) {
		if (o == null || o.forceId == null || Global.getSector() == null) return false;
		Force f = forces().get(o.forceId);
		return f != null && !f.engaged;
	}

	public static void tick(Random random) {
		if (!ThreatIncConfig.softenEnabled()) return;
		List<ThreatSwarmBountyIntel> bounties = ThreatSwarmBountyIntel.running();
		if (bounties.isEmpty()) return;
		Collections.shuffle(bounties, random);
		for (ThreatSwarmBountyIntel bounty : bounties) {
			StarSystemAPI system = bounty.getSystemId() != null
					? Global.getSector().getStarSystem(bounty.getSystemId()) : null;
			if (system == null) continue;
			for (String factionId : ThreatWarState.warFactionIds()) {
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue;
				if (hunting(factionId, system.getId())) continue;
				if (hostileAt(faction, system.getId())) continue;
				// against what the poster's siege weighs (gateWorlds), from the best
				// paid base first until one force sails (huntBases)
				List<MarketAPI> worlds = gateWorlds(bounty.getFaction(), system, bounty.siegeTargets());
				for (MarketAPI base : huntBases(faction, system)) {
					if (send(faction, base, system, worlds)) break;
				}
			}
		}
	}

	/**
	 * The bases a hunting force may be raised at, the best paid first
	 * (2026-09-29): every base of the faction in fuel reach of the hive (the
	 * rule ThreatFleetOrders.pickBase applies) that is not resting and has no
	 * siege of its own to spend on (IncursionManager.hasSiegeableHive), ranked
	 * by the fleet points it and its donors can pay a force there for
	 * ({@link #payableFP}); the nearest breaks a tie. The nearest base alone,
	 * whatever its stock and never passed over, launched no hunt at all in a
	 * test where the Threat grew from 36 hives to 66.
	 */
	protected static List<MarketAPI> huntBases(FactionAPI faction, StarSystemAPI system) {
		final Map<MarketAPI, Float> pays = new java.util.HashMap<MarketAPI, Float>();
		final Vector2f loc = system.getLocation();
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			if (Misc.getDistanceLY(m.getStarSystem().getLocation(), loc) > IncursionManager.expeditionRangeLY(m)) continue;
			if (resting(m)) continue;
			float p = payableFP(m, system, huntDonors(faction, m));
			if (p <= 0f) continue;
			// the costly gate last, on the bases that could pay anything
			if (IncursionManager.hasSiegeableHive(m)) continue;
			pays.put(m, p);
			out.add(m);
		}
		Collections.sort(out, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				int c = Float.compare(pays.get(b), pays.get(a));
				if (c != 0) return c;
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), loc),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), loc));
			}
		});
		return out;
	}

	/**
	 * Whether a faction hostile to this one has fleets under orders or a siege
	 * in the system: its force would fight theirs, not the swarms. Every force
	 * went at the same weakest colony, and all 9 badly-hurt stand-downs of Run 7
	 * came after a battle with another faction's force (rc1 review).
	 */
	public static boolean hostileAt(FactionAPI faction, String systemId) {
		if (faction == null || systemId == null) return false;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (o.factionId == null || o.factionId.equals(faction.getId())) continue;
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			if (!systemId.equals(huntSystemId(o))) continue;
			if (faction.isHostileTo(o.factionId)) return true;
		}
		for (String other : IncursionManager.siegeFactionsIn(systemId)) {
			if (!other.equals(faction.getId()) && faction.isHostileTo(other)) return true;
		}
		return false;
	}

	/** Whether the base sent fleets to a hunting force less than softenIntervalDays ago. */
	protected static boolean resting(MarketAPI base) {
		Long last = lastSent().get(base.getId());
		return last != null && Global.getSector().getClock().getElapsedDaysSince(last)
				< ThreatIncConfig.softenIntervalDays();
	}

	/** Whether the faction already has a hunting force in the system. */
	protected static boolean hunting(String factionId, String systemId) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind) || !factionId.equals(o.factionId)) continue;
			if (systemId.equals(huntSystemId(o))) return true;
		}
		return false;
	}

	/**
	 * The hive system a hunt works: recorded on the order, so it survives the
	 * target colony leaving the economy; an order from before it was recorded
	 * reads its target colony's system while that colony stands.
	 */
	protected static String huntSystemId(ThreatFleetOrders.Order o) {
		if (o.systemId != null) return o.systemId;
		MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		return hive != null && hive.getStarSystem() != null ? hive.getStarSystem().getId() : null;
	}

	/** Defense Swarm points over one colony. */
	protected static float garrisonFP(MarketAPI hive) {
		return IncursionManager.siegeOrbitFP(Collections.singletonList(hive));
	}

	/** Where the player's single-fleet hunt in this system starts: the colony with the weakest standing garrison, or null when no colony has one. */
	public static MarketAPI huntTarget(String systemId) {
		return weakest(systemId);
	}

	/** The colony of the system with the weakest standing garrison, or null when none has one. */
	protected static MarketAPI weakest(String systemId) {
		MarketAPI best = null;
		float bestFP = Float.MAX_VALUE;
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (hive.getPrimaryEntity() == null) continue;
			float fp = garrisonFP(hive);
			if (fp <= 0f) continue;
			if (fp < bestFP) {
				bestFP = fp;
				best = hive;
			}
		}
		return best;
	}

	/**
	 * A hunting force's target: the colony the siege's orbit gate reads - the
	 * strongest standing garrison (IncursionManager.siegeOrbitFaced with
	 * npcSiegeOrbitPerWorld; summed, the gate still falls fastest here), or null
	 * when no colony has one.
	 */
	protected static MarketAPI strongest(String systemId) {
		return strongest(systemId, null);
	}

	/** As above among the worlds with these ids - the siege's targets - or the whole system for null. */
	protected static MarketAPI strongest(String systemId, java.util.Collection<String> among) {
		MarketAPI best = null;
		float bestFP = 0f;
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (hive.getPrimaryEntity() == null) continue;
			if (among != null && !among.contains(hive.getId())) continue;
			float fp = garrisonFP(hive);
			if (fp > bestFP) {
				bestFP = fp;
				best = hive;
			}
		}
		return best;
	}

	/**
	 * The worlds a hunt that is to open a siege's orbit gate goes after
	 * (2026-09-29): the siege's own ({@code targets}) while the strongest of
	 * their garrisons is what the gate weighs; the whole system (null) while
	 * the system's swarms, or what a called-off siege met there, outweigh it
	 * (IncursionManager.siegeOrbitWeighed). Aimed at the siege's one world,
	 * Thrial's forces beat Surgat's 75 FP over and over and stood down with it
	 * clear, while the gate read 11k FP over its five siblings.
	 */
	protected static List<MarketAPI> gateWorlds(FactionAPI siegeFaction, StarSystemAPI system,
			List<MarketAPI> targets) {
		if (system == null || targets == null || targets.isEmpty()) return null;
		return IncursionManager.siegeOrbitWeighed(siegeFaction, targets)
				> IncursionManager.siegeOrbitFaced(targets) ? null : targets;
	}

	/** The ids of the worlds a force is sent against; null for the whole system. */
	protected static List<String> idsOf(List<MarketAPI> targets) {
		if (targets == null || targets.isEmpty()) return null;
		List<String> ids = new ArrayList<String>();
		for (MarketAPI t : targets) {
			if (t != null && !ids.contains(t.getId())) ids.add(t.getId());
		}
		return ids;
	}

	/** Why a force stands down with no garrison left to fight. */
	protected static String cleared(Force f) {
		return f.targetIds != null ? "the siege's worlds are clear" : "the swarms are gone";
	}

	/**
	 * Combat points the base's reserve can pay a force to this system for (fuel
	 * for the distance, supplies for the hulls) - out of what a hunt there may
	 * take (huntSpendable), so a hunt never spends what the base banked for a
	 * siege that can sail.
	 */
	protected static float payableFP(MarketAPI base, StarSystemAPI system) {
		return payableFP(base, system, null);
	}

	/** As above, with what the hunt's {@code donors} (huntDonors) can give toward the base's fleets. */
	protected static float payableFP(MarketAPI base, StarSystemAPI system, List<MarketAPI> donors) {
		float dist = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
		float fuelPerPoint = dist * ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float points = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) {
			points = Math.min(points, (huntSpendable(base, system, Commodities.FUEL)
					+ donorsSpendable(donors, Commodities.FUEL)) / fuelPerPoint);
		}
		if (suppliesPerPoint > 0f) {
			points = Math.min(points, (huntSpendable(base, system, Commodities.SUPPLIES)
					+ donorsSpendable(donors, Commodities.SUPPLIES)) / suppliesPerPoint);
		}
		return points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
	}

	/**
	 * The markets that pay toward the primary base's hunting fleets without
	 * sailing (2026-09-29): every market of the faction whose stock reaches the
	 * base (IncursionManager.marketsReaching - the convoys' reach, as a siege's
	 * donors) that fields no hunting fleets of its own - no base, no link -
	 * nearest the base first. Each gives what a hunt may take
	 * (ThreatReserves.spendable). Until now only bases paid, each for its own
	 * fleets, and a faction's depots never paid for a hunt at all. A forward
	 * base that fields no fleets gives too (2026-09-29), less its garrison's
	 * upkeep ({@link #donorSpendable}), as it gives a sibling's siege.
	 */
	protected static List<MarketAPI> huntDonors(FactionAPI faction, MarketAPI primary) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (!ThreatIncConfig.softenPool() || primary.getStarSystem() == null) return out;
		for (MarketAPI m : IncursionManager.marketsReaching(faction, primary)) {
			if (m == primary || IncursionManager.isBase(m)) continue;
			out.add(m);
		}
		final Vector2f at = primary.getStarSystem().getLocation();
		Collections.sort(out, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), at),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), at));
			}
		});
		return out;
	}

	/** What the donors can give a hunt of one commodity ({@link #donorSpendable}). */
	protected static float donorsSpendable(List<MarketAPI> donors, String commodityId) {
		if (donors == null) return 0f;
		float sum = 0f;
		for (MarketAPI m : donors) sum += donorSpendable(m, commodityId);
		return sum;
	}

	/** What one market can give a hunt: ThreatReserves.spendable, less a forward base's garrison upkeep ({@link #outpostKeep}). */
	protected static float donorSpendable(MarketAPI m, String commodityId) {
		return Math.max(0f, ThreatReserves.spendable(m, commodityId) - outpostKeep(m, commodityId));
	}

	/**
	 * The supplies a forward base keeps back from a hunt: siegeOutpostKeepMonths
	 * of its garrison's upkeep (ThreatFrontlines.garrisonUpkeepAt), the keep it
	 * holds against a sibling's siege (IncursionManager.donorAvailable), so a
	 * hunt never recalls a garrison. 0 for anything else.
	 */
	protected static float outpostKeep(MarketAPI m, String commodityId) {
		if (m == null || !Commodities.SUPPLIES.equals(commodityId) || !ThreatFrontlines.isOutpost(m)) return 0f;
		return ThreatFrontlines.garrisonUpkeepAt(m) * Math.max(0f, ThreatIncConfig.siegeOutpostKeepMonths());
	}

	/**
	 * Pays the rest of a hunting fleet's provisions from the donors, nearest the
	 * base first: the fleet is built at the points asked (buildTaskForce), and
	 * the base paid what it could of them. What the donors give is added to
	 * what the fleet carries (ThreatReturns.MEM_FUEL, MEM_SUPPLIES), so it
	 * comes home to the base with the rest, as a siege's pooled stock does.
	 */
	protected static void payFromDonors(CampaignFleetAPI fleet, MarketAPI base, List<MarketAPI> donors,
			StarSystemAPI system, float fp) {
		if (fleet == null || donors == null || donors.isEmpty()) return;
		float[] wants = ThreatFleetOrders.sortieWants(base, fp, system.getLocation());
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float fuel = mem.getFloat(ThreatReturns.MEM_FUEL);
		float supplies = mem.getFloat(ThreatReturns.MEM_SUPPLIES);
		List<String> from = new ArrayList<String>();
		float moreFuel = drawDonors(donors, Commodities.FUEL, wants[0] - fuel, from);
		float moreSupplies = drawDonors(donors, Commodities.SUPPLIES, wants[1] - supplies, from);
		if (moreFuel <= 0f && moreSupplies <= 0f) return;
		mem.set(ThreatReturns.MEM_FUEL, fuel + moreFuel);
		mem.set(ThreatReturns.MEM_SUPPLIES, supplies + moreSupplies);
		ThreatIncConfig.log("Hunting fleet from " + base.getName() + " pooled " + (int) moreFuel + " fuel, "
				+ (int) moreSupplies + " supplies: " + Misc.getAndJoined(from));
	}

	/** Draws up to {@code want} from the donors in turn; names each that gave into {@code from}. */
	protected static float drawDonors(List<MarketAPI> donors, String commodityId, float want, List<String> from) {
		float got = 0f;
		for (MarketAPI m : donors) {
			if (got >= want) break;
			float g = ThreatReserves.draw(m.getId(), commodityId, Math.min(want - got, donorSpendable(m, commodityId)));
			got += g;
			if (g >= 1f) from.add(m.getName() + " " + (int) g + " " + commodityId);
		}
		return got;
	}

	/**
	 * Whether the base's own siege waits on the hunt in this system: the
	 * system is the hive it stages for (ThreatConvoys.stagingHive) and the
	 * siege cannot sail (IncursionManager.hasSiegeableHive) - its orbit gate is
	 * the very swarm the hunt thins.
	 */
	protected static boolean siegeWaitsOnHunt(MarketAPI base, StarSystemAPI system) {
		if (base == null || system == null) return false;
		return ThreatConvoys.stagingHive(base) == system && !IncursionManager.hasSiegeableHive(base);
	}

	/**
	 * Stock a hunt in this system may take from the base: ThreatReserves.spendable,
	 * except where the base's own siege there waits on the hunt
	 * (siegeWaitsOnHunt) - then that siege's staging bank is spendable too, and
	 * the base keeps only its floor and donor keep (2026-09-29). The siege cannot
	 * sail until the swarm is thinned, and the staging bank starved the one force
	 * that would thin it. A base staging for a hunt, its siege past the
	 * faction's means (ThreatConvoys.stagesForHunt), gives its bank to a hunt
	 * in any system: it holds nothing for a siege that could sail.
	 */
	protected static float huntSpendable(MarketAPI base, StarSystemAPI system, String commodityId) {
		if (base == null) return 0f;
		// a forward base keeps its garrison's upkeep back (outpostKeep) either way;
		// the memoised staging verdict first, the siege sweep only without it
		if (!ThreatConvoys.stagesForHunt(base) && !siegeWaitsOnHunt(base, system)) {
			return donorSpendable(base, commodityId);
		}
		if (ThreatReserves.committed(base, commodityId)) return 0f;
		float keep = Math.max(ThreatReserves.floor(base, commodityId),
				ThreatReserves.monthsCap(base, commodityId) * ThreatIncConfig.donorKeepFraction());
		return Math.max(0f, ThreatReserves.stock(base.getId(), commodityId) - keep - outpostKeep(base, commodityId));
	}

	/** Takes up to {@code amount} of {@link #huntSpendable} stock; returns what was taken. */
	public static float drawHunt(MarketAPI base, StarSystemAPI system, String commodityId, float amount) {
		if (base == null || amount <= 0f) return 0f;
		return ThreatReserves.draw(base.getId(), commodityId, Math.min(amount, huntSpendable(base, system, commodityId)));
	}

	/** A fleet built below this share of the points asked was pruned: see {@link #FLEET_CAP}. */
	protected static final float BUILT_SHORT = 0.8f;
	/** Faction id -> the biggest fleet its yards were seen to build whole; not saved, relearned after a load. */
	protected static final Map<String, Float> FLEET_CAP = new java.util.HashMap<String, Float>();

	protected static float fleetCap(String factionId) {
		Float cap = FLEET_CAP.get(factionId);
		return cap != null ? cap : Float.MAX_VALUE;
	}

	/** Called on load: the caps are relearned in the loaded game, not carried over from another. */
	public static void forgetFleetCaps() {
		FLEET_CAP.clear();
	}

	/** Whether vanilla pruned the fleet to its ship cap - the only short build that teaches a smaller fleet size. */
	protected static boolean pruned(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getFleetData().getNumMembers()
				>= Global.getSettings().getInt("maxShipsInAIFleet");
	}

	/**
	 * Learns the faction's fleet size from a fleet vanilla built short of the
	 * {@code asked} combat points and pruned to its ship cap: every fleet
	 * builder that sizes fleets by softenFleetFP asks no more from then on
	 * (ThreatFrontlines.spawnForce, ThreatFleetOrders.buildSortie).
	 */
	protected static void learnFleetCap(String factionId, CampaignFleetAPI fleet, float asked) {
		float got = combatFP(fleet);
		if (got < asked * BUILT_SHORT && pruned(fleet)) FLEET_CAP.put(factionId, Math.max(100f, got * 1.1f));
	}

	/**
	 * Fleet points a hunt brings per point of Defense Swarm it fights: the
	 * siege's own orbit margin (npcSiegeOrbitMargin, 2026-09-29). softenMargin
	 * 2.0 on top of a headroom 1.5 and a garrison refilled to its nominal size
	 * asked 15,790 FP of a force against Alpha Mesh I's 3,158.
	 */
	public static float margin() {
		return Math.max(0f, ThreatIncConfig.npcSiegeOrbitMargin());
	}

	/**
	 * The smallest force that sails against this colony: the garrison over it
	 * now ({@link #garrisonNowFP}) by the {@link #margin}. No projection of
	 * regrowth (2026-09-30): the Threat's posture moves its banks and swarms
	 * wherever the pressure is, so a hive's own bank and income over an 86-day
	 * passage said little about the garrison a hunt would meet, and asked 8-13k
	 * FP of hunts that then waited on their fuel for months while the projection
	 * grew. A garrison reinforced during the muster is met by the go-in check,
	 * which stands the force down (advanceForce).
	 */
	protected static float musterFloorFP(MarketAPI colony) {
		return margin() * garrisonNowFP(colony);
	}

	/**
	 * The Defense Swarms a colony owns now: ThreatColonyManager.ownedFleetFP -
	 * its garrison, its raiders out and the reinforcements flying in.
	 */
	protected static float garrisonNowFP(MarketAPI colony) {
		return Math.max(garrisonFP(colony),
				ThreatColonyManager.ownedFleetFP(colony, ThreatIncData.garrisonsFor(colony.getId())));
	}

	/**
	 * What the smallest hunting force from the base against the hive draws, in
	 * ThreatReserves.COMMODITIES order: the fuel and supplies
	 * (ThreatFleetOrders.sortieWants) of {@link #musterFloorFP} against the
	 * world the base's siege's orbit gate reads ({@link #strongest} of its
	 * {@link #gateWorlds}). No marines or armaments: it lands
	 * nothing. What a base stages while its siege is past the faction's means
	 * (ThreatConvoys.siegeStock).
	 */
	public static float[] stagingWants(MarketAPI base, StarSystemAPI hive) {
		float[] out = new float[ThreatReserves.COMMODITIES.length];
		if (base == null || hive == null || base.getFaction() == null) return out;
		MarketAPI first = strongest(hive.getId(), idsOf(gateWorlds(base.getFaction(), hive,
				IncursionManager.siegeTargets(base, base.getFaction(), hive))));
		if (first == null) return out;
		float[] w = ThreatFleetOrders.sortieWants(base, musterFloorFP(first),
				hive.getLocation());
		int fuel = ThreatAid.index(Commodities.FUEL), supplies = ThreatAid.index(Commodities.SUPPLIES);
		if (fuel >= 0) out[fuel] = w[0];
		if (supplies >= 0) out[supplies] = w[1];
		return out;
	}

	/** Returns the share of a fleet's provisions the points it lost to pruning were drawn for. */
	protected static void refundShort(CampaignFleetAPI fleet, MarketAPI base, float frac) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		String[][] keys = { { ThreatReturns.MEM_FUEL, Commodities.FUEL }, { ThreatReturns.MEM_SUPPLIES, Commodities.SUPPLIES } };
		for (String[] k : keys) {
			if (!mem.contains(k[0])) continue;
			float drawn = mem.getFloat(k[0]);
			float back = drawn * frac;
			ThreatReserves.deposit(base.getId(), k[1], back);
			mem.set(k[0], drawn - back);
		}
	}

	/** A fleet's warship points - the freighters and tankers it sails with fight nothing. */
	public static float combatFP(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		float fp = 0f;
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (!m.isCivilian()) fp += m.getFleetPointCost();
		}
		return fp;
	}

	/**
	 * The bases a force draws on: the nearest, then (softenPool) every other
	 * base free to that reaches the hive or the primary base, nearest the hive
	 * first. A base in fuel range of the primary chips in too (2026-09-29): its
	 * fleets pay fuel for the whole way (payableFP); the hive's range alone kept
	 * every depot behind the primary out of the hunt. In range of the primary is
	 * the convoys' reach between two markets (ThreatConvoys.stockReachLY), as a
	 * siege's donors: at least convoyRangeLY, whatever the base's own fuel.
	 * The markets that field no fleets pay toward the primary's (huntDonors).
	 */
	protected static List<MarketAPI> contributors(FactionAPI faction, MarketAPI primary, StarSystemAPI system) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		result.add(primary);
		if (!ThreatIncConfig.softenPool()) return result;
		final Vector2f loc = system.getLocation();
		Vector2f hub = primary.getStarSystem() != null ? primary.getStarSystem().getLocation() : loc;
		List<MarketAPI> others = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m == primary || m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			float range = IncursionManager.expeditionRangeLY(m);
			Vector2f at = m.getStarSystem().getLocation();
			if (Misc.getDistanceLY(at, loc) > range
					&& Misc.getDistanceLY(at, hub) > ThreatConvoys.stockReachLY(m)) continue;
			if (resting(m) || IncursionManager.hasSiegeableHive(m)) continue;
			others.add(m);
		}
		Collections.sort(others, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), loc),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), loc));
			}
		});
		result.addAll(others);
		return result;
	}

	/** Raises a hunting force against the system; true when one sailed for the muster. */
	protected static boolean send(FactionAPI faction, MarketAPI base, StarSystemAPI system) {
		return send(faction, base, system, null);
	}

	/**
	 * As above against the worlds a siege is fighting ({@code targets}; null or
	 * empty for the whole system): the force aims at the strongest of their
	 * garrisons, is sized to their swarms and remembers them
	 * ({@link Force#targetIds}). Sent at the system's strongest world, the
	 * Tri-Tachyon answer to the Church's siege of Epsilon Qades I went over I-B.
	 */
	protected static boolean send(FactionAPI faction, MarketAPI base, StarSystemAPI system,
			List<MarketAPI> targets) {
		List<String> among = idsOf(targets);
		// the world the siege's orbit gate reads, or nothing: a force that cannot
		// be fielded or paid against it does not go for a weaker world instead
		MarketAPI first = strongest(system.getId(), among);
		if (first == null) return false;
		String key = "huntwait:" + faction.getId() + ":" + system.getId();
		// sized to the garrison over the world now (musterFloorFP); if the swarms
		// reinforce while it gathers, the go-in check stands it down
		// no ceiling on the force (user's rule 2026-09-29): what the depots can pay
		// bounds it. softenMaxFP (12,000) and 30 fleets left every hive over ~4k FP
		// a world unhunted for the 3.7-year test
		float floor = musterFloorFP(first);
		float want = IncursionManager.siegeOrbitFP(among != null ? targets
				: IncursionManager.collectSiegeTargets(system)) * margin();
		want = Math.max(floor, want);
		List<MarketAPI> bases = contributors(faction, base, system);
		// the markets that field no fleets pay toward the primary's
		List<MarketAPI> donors = huntDonors(faction, base);
		float perFleet = Math.max(50f, ThreatIncConfig.softenFleetFP());
		// what the depots can pay for is what is built (2026-09-29: closed economy -
		// a fleet is built at the points it is paid for, ThreatFleetOrders.buildTaskForce;
		// the market's fleet-size multiplier on top was unpaid)
		float builds = 0f;
		for (MarketAPI b : bases) builds += payableFP(b, system, b == base ? donors : null);
		if (builds < floor) {
			ThreatIncConfig.logQuiet(key, "Hunting force waits at " + base.getName() + ": " + bases.size()
					+ (bases.size() == 1 ? " base" : " bases") + (donors.isEmpty() ? "" : " and " + donors.size()
					+ (donors.size() == 1 ? " donor" : " donors")) + " pay for " + (int) builds + " FP, "
					+ first.getName() + "'s swarms need " + (int) floor);
			return false;
		}

		long now = Global.getSector().getClock().getTimestamp();
		Force force = new Force();
		force.id = faction.getId() + ":" + system.getId() + ":" + now;
		force.factionId = faction.getId();
		force.systemId = system.getId();
		force.launchedTimestamp = now;
		force.targetIds = among;
		SectorEntityToken muster;
		if (base.getStarSystem() == system) {
			muster = base.getPrimaryEntity();
			force.musterInSystem = true;
			force.musterEntityId = muster.getId();
		} else {
			Vector2f at = musterPoint(system, faction.getId());
			force.musterX = at.x;
			force.musterY = at.y;
			muster = Global.getSector().getHyperspace().createToken(at.x, at.y);
		}
		float built = 0f;
		List<ThreatFleetOrders.Order> sent = new ArrayList<ThreatFleetOrders.Order>();
		List<String> from = new ArrayList<String>();
		for (MarketAPI b : bases) {
			if (built >= want) break;
			// asked in the points the depot pays for, at most perFleet
			float budget = payableFP(b, system, b == base ? donors : null);
			boolean any = false;
			while (built < want) {
				float size = Math.min(perFleet, fleetCap(faction.getId()));
				float ask = Math.min(Math.min(size, want - built), budget);
				// a remainder under the smallest fleet is rounded up, not dropped: dropped,
				// the force came in a few points under its floor and folded (9430 of 9434)
				if (ask < 25f) ask = Math.min(25f, budget);
				if (ask < 25f) break;
				ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchHunt(faction, b, first, ask, force.id, muster);
				if (o == null) break;
				float got = combatFP(o.fleet);
				if (got < 1f) {
					// nothing built: the depot keeps its provisions, and the loop has no
					// fleet cap to end it
					refundShort(o.fleet, b, 1f);
					ThreatFleetOrders.fold(o, b);
					break;
				}
				// the base paid what it could of the ask; the donors pay the rest
				if (b == base) payFromDonors(o.fleet, b, donors, system, ask);
				float share = Math.min(1f, got / Math.max(1f, ask));
				if (share < BUILT_SHORT) {
					// vanilla prunes a fleet to maxShipsInAIFleet: a navy without the
					// big hulls to hold the points loses them (Nortia built 304 FP of
					// a 1,683 ask). Pay only for what was built; ask smaller from now
					// only when it was the ship cap that cut it (a small round-up ask
					// can come in short for other reasons)
					refundShort(o.fleet, b, 1f - share);
					if (pruned(o.fleet)) FLEET_CAP.put(faction.getId(), Math.max(100f, got * 1.1f));
				}
				budget -= ask * share;
				built += got;
				sent.add(o);
				any = true;
			}
			if (any) {
				lastSent().put(b.getId(), now);
				from.add(b.getName());
			}
		}
		if (sent.isEmpty()) return false;
		if (built < floor) {
			// the yards built short of the numbers: back into the depot, provisions refunded
			// in full - the fleets never sailed, so the return rule's refund cut does not apply
			float backFuel = 0f, backSupplies = 0f;
			for (ThreatFleetOrders.Order o : sent) {
				MarketAPI home = baseOf(o);
				if (home != null && o.fleet != null) {
					backFuel += o.fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_FUEL);
					backSupplies += o.fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_SUPPLIES);
					refundShort(o.fleet, home, 1f);
				}
				ThreatFleetOrders.fold(o, home);
			}
			ThreatIncConfig.log("Hunting force from " + base.getName() + " built " + (int) built + " FP of the "
					+ (int) floor + " " + first.getName() + "'s swarms need - stood down, " + (int) backFuel
					+ " fuel and " + (int) backSupplies + " supplies back in the depots");
			return false;
		}
		forces().put(force.id, force);
		ThreatNotice n = ThreatNotice.titled("Hunting Force Musters").icon(faction);
		if (faction.isPlayerFaction()) {
			n.line("Your hunting force musters against the Defense Swarms in the %s",
					system.getNameWithLowercaseTypeShort());
		} else {
			n.line("%s musters a hunting force against the Defense Swarms in the %s",
					ThreatNotice.faction(faction), system.getNameWithLowercaseTypeShort());
		}
		n.send();
		ThreatIncConfig.log("Hunting force from " + Misc.getAndJoined(from) + " to " + system.getName() + ": "
				+ sent.size() + " fleets (" + shape(sent) + "), " + (int) built + " FP built (wanted " + (int) want + ", pays for "
				+ (int) builds + ") against " + first.getName() + " first (" + (int) garrisonFP(first)
				+ " FP), mustering");
		return true;
	}

	/** How close a follower must be to its lead to be merged into it. */
	protected static final float MERGE_RANGE = 1000f;

	/**
	 * Folds a follower into the force's lead fleet (softenMerge): its ships,
	 * and what it was provisioned with and launched at, so the one fleet is
	 * refunded and judged as the two were. Vanilla builds an AI fleet to at
	 * most 30 ships - 250-500 FP for most navies - and its AI never keeps
	 * separate fleets together in a fight: every battle of a 16-fleet force
	 * over Alpha Novy Tayvay I was one fleet against an 876-1,037 FP swarm.
	 * The cap prunes only at spawn, so the gathered force sails as one fleet.
	 * The merged force goes home to the lead's base, which takes the refund.
	 */
	protected static boolean mergeInto(ThreatFleetOrders.Order lead, ThreatFleetOrders.Order o) {
		return mergeInto(lead, o, MERGE_RANGE);
	}

	/**
	 * As above within {@code range}. Stops at softenMergeMaxShips: one merged
	 * fleet reached 900 ships (Marad, Run 6); what is beyond the cap follows
	 * the lead and fights beside it.
	 */
	protected static boolean mergeInto(ThreatFleetOrders.Order lead, ThreatFleetOrders.Order o, float range) {
		CampaignFleetAPI to = lead.fleet, from = o.fleet;
		if (from.getContainingLocation() != to.getContainingLocation()) return false;
		if (Misc.getDistance(from, to) > range) return false;
		if (from.getBattle() != null || to.getBattle() != null) return false;
		if (to.getFleetData().getNumMembers() + from.getFleetData().getNumMembers()
				> ThreatIncConfig.softenMergeMaxShips()) {
			return false;
		}
		ThreatFleetOrders.all().remove(o);
		ThreatFleetOrders.absorb(to, from);
		ThreatIncConfig.log("Hunting fleet merged into the lead over " + o.targetName + ": now "
				+ to.getFleetData().getNumMembers() + " ships, " + (int) combatFP(to) + " FP");
		return true;
	}

	/** "250-1480 FP, up to 30 ships": what the yards built, for the log. */
	protected static String shape(List<ThreatFleetOrders.Order> sent) {
		int lo = Integer.MAX_VALUE, hi = 0, ships = 0;
		for (ThreatFleetOrders.Order o : sent) {
			int fp = (int) combatFP(o.fleet);
			lo = Math.min(lo, fp);
			hi = Math.max(hi, fp);
			ships = Math.max(ships, o.fleet.getFleetData().getNumMembers());
		}
		return lo + "-" + hi + " FP, up to " + ships + " ships";
	}

	/**
	 * Forces muster, go in, move on and go home together; a hunt with no force
	 * (the player's, or one from an older save) keeps the single-fleet rules.
	 */
	public static void advanceHunts() {
		if (ThreatFleetOrders.all().isEmpty() && forces().isEmpty()) return;
		Map<String, List<ThreatFleetOrders.Order>> byForce = new LinkedHashMap<String, List<ThreatFleetOrders.Order>>();
		for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			logBattle(o);
			if (o.forceId == null || !forces().containsKey(o.forceId)) {
				advanceSingle(o);
				continue;
			}
			List<ThreatFleetOrders.Order> list = byForce.get(o.forceId);
			if (list == null) {
				list = new ArrayList<ThreatFleetOrders.Order>();
				byForce.put(o.forceId, list);
			}
			list.add(o);
		}
		// a force with no fleet left on the books is over
		for (Iterator<String> it = forces().keySet().iterator(); it.hasNext();) {
			if (!byForce.containsKey(it.next())) it.remove();
		}
		for (Map.Entry<String, List<ThreatFleetOrders.Order>> e : byForce.entrySet()) {
			advanceForce(forces().get(e.getKey()), e.getValue());
		}
	}

	/** Battles already logged; not saved. */
	protected static final Map<Object, Boolean> LOGGED_BATTLES = new java.util.WeakHashMap<Object, Boolean>();

	/**
	 * What a hunting fleet actually fights: every fleet on the other side of its
	 * battle, once per battle, with the colony it garrisons and how far it is
	 * from the hunt's target - the garrison figure the odds are read from
	 * counts one colony's swarms only.
	 */
	protected static void logBattle(ThreatFleetOrders.Order o) {
		com.fs.starfarer.api.campaign.BattleAPI battle = o.fleet.getBattle();
		if (battle == null || !ThreatIncConfig.debugLogging() || LOGGED_BATTLES.containsKey(battle)) return;
		LOGGED_BATTLES.put(battle, Boolean.TRUE);
		MarketAPI target = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		SectorEntityToken planet = target != null ? target.getPrimaryEntity() : null;
		float ours = 0f;
		for (CampaignFleetAPI f : battle.getSideFor(o.fleet)) ours += combatFP(f);
		StringBuilder them = new StringBuilder();
		float theirs = 0f;
		for (CampaignFleetAPI f : battle.getOtherSideFor(o.fleet)) {
			float fp = f.getFleetPoints();
			theirs += fp;
			String home = f.getMemoryWithoutUpdate().getString(ThreatColonyManager.GARRISON_FLAG);
			MarketAPI homeMarket = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
			int dist = planet != null && f.getContainingLocation() == planet.getContainingLocation()
					? (int) Misc.getDistance(f, planet) : -1;
			them.append(them.length() > 0 ? "; " : "").append(f.getName()).append(f.isStationMode() ? " [station]" : "")
					.append(homeMarket != null ? " of " + homeMarket.getName() : "")
					.append(" ").append((int) fp).append(" FP @").append(dist);
		}
		ThreatIncConfig.log("Hunt battle near " + o.targetName + ": " + o.factionId + " side " + battle.getSideFor(o.fleet).size() + " fleets " + (int) ours
				+ " FP vs " + (int) theirs + " FP (" + (target != null ? (int) garrisonFP(target) : 0)
				+ " counted as the garrison): " + them);
	}

	protected static void advanceForce(Force f, List<ThreatFleetOrders.Order> orders) {
		StarSystemAPI system = f.systemId != null ? Global.getSector().getStarSystem(f.systemId) : null;
		if (system == null) {
			standDownAll(f, orders, "the system is gone");
			return;
		}
		CampaignClockAPI clock = Global.getSector().getClock();
		float margin = margin();

		if (!f.engaged) {
			float fp = 0f;
			for (ThreatFleetOrders.Order o : orders) fp += combatFP(o.fleet);
			List<ThreatFleetOrders.Order> mustered = new ArrayList<ThreatFleetOrders.Order>();
			float presentFP = 0f;
			for (ThreatFleetOrders.Order o : orders) {
				// the hunt's term starts when it goes in, not while it gathers
				o.issuedTimestamp = clock.getTimestamp();
				if (atMuster(f, o, system)) {
					mustered.add(o);
					presentFP += combatFP(o.fleet);
				}
			}
			int present = mustered.size();
			if (present > 0 && f.firstArrivalTimestamp == 0L) f.firstArrivalTimestamp = clock.getTimestamp();
			if (present == 0 && clock.getElapsedDaysSince(f.launchedTimestamp) >= ThreatIncConfig.softenDays()) {
				standDownAll(f, orders, "never mustered");
				return;
			}
			boolean all = present == orders.size();
			boolean waited = f.firstArrivalTimestamp != 0L
					&& clock.getElapsedDaysSince(f.firstArrivalTimestamp) >= ThreatIncConfig.softenMusterDays();
			if (!all && !waited) return;
			MarketAPI target = strongest(f.systemId, f.targetIds);
			if (target == null) {
				standDownAll(f, orders, cleared(f));
				return;
			}
			float need = garrisonFP(target) * margin;
			// the stragglers would carry it: wait for them, up to softenMusterStragglerMult muster spells
			if (presentFP < need && fp >= need && clock.getElapsedDaysSince(f.firstArrivalTimestamp)
					< Math.max(1f, ThreatIncConfig.softenMusterStragglerMult()) * ThreatIncConfig.softenMusterDays()) {
				return;
			}
			if (presentFP < need) {
				standDownAll(f, orders, "outmatched at the muster, " + (int) presentFP + " FP against "
						+ (int) need + " over " + target.getName());
				return;
			}
			f.engaged = true;
			sendIn(f, orders, mustered, target);
			// the mustered fleets fold into the lead before it sails. Merged only on
			// the lead's heels, the lead reached the garrison first and fought it
			// alone: every one of Run 7's 214 hunt battles had one hunter fleet
			ThreatFleetOrders.Order lead = leadOf(f, orders);
			if (lead != null && ThreatIncConfig.softenMerge()) {
				for (ThreatFleetOrders.Order o : mustered) {
					if (o != lead && mergeInto(lead, o, 2f * MUSTER_RANGE)) orders.remove(o);
				}
			}
			f.baseFP = presentFP;
			// the fleets that went in are the force's strength wherever they are (a
			// follower a jump behind the lead has not left the fight); stragglers join
			// it once they reach the lead
			f.inForce = new java.util.HashSet<String>();
			for (ThreatFleetOrders.Order o : mustered) {
				if (orders.contains(o)) f.inForce.add(o.fleet.getId());
			}
			ThreatIncConfig.log("Hunting force " + f.factionId + " goes in over " + target.getName() + ": "
					+ present + "/" + (orders.size() + present - countIn(orders, mustered)) + " fleets mustered, "
					+ (int) presentFP + " FP against " + (int) garrisonFP(target));
			return;
		}

		ThreatFleetOrders.Order lead = leadOf(f, orders);
		MarketAPI hive = orders.get(0).targetId != null
				? Global.getSector().getEconomy().getMarket(orders.get(0).targetId) : null;
		if (lead != null && ThreatIncConfig.softenMerge()) {
			for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(orders)) {
				if (o != lead && mergeInto(lead, o)) orders.remove(o);
			}
		}
		// strength is what is at the fight: the fleets that went in and any
		// straggler that has reached the lead. Summed over every order, stragglers
		// that never came in kept a force ground down to 41 FP at Rhesh reading 53%
		// and fighting on
		if (lead != null && f.inForce != null) {
			for (ThreatFleetOrders.Order o : orders) {
				if (o == lead || withLead(lead, o)) f.inForce.add(o.fleet.getId());
			}
		}
		float fp = presentFP(f, orders, lead, system);
		if (f.baseFP > 0f && fp / f.baseFP < ThreatIncConfig.softenRetreatStrength()) {
			standDownAll(f, orders, "badly hurt, " + (int) fp + " of " + (int) f.baseFP + " FP");
			return;
		}
		// the lead is gone (or a force from before leads): the slowest fleet already in the system leads on
		if (lead == null && hive != null && hive.getPrimaryEntity() != null) {
			sendIn(f, orders, inLocation(orders, system), hive);
			return;
		}
		if (lead != null && (f.close || lead.fleet.getBattle() != null)) {
			// a follower on the lead's heels fights beside it: blinkered, vanilla
			// never pulls it into the lead's battle (fleets past the merge cap,
			// or with softenMerge off)
			for (ThreatFleetOrders.Order o : orders) {
				if (o != lead && withLead(lead, o)) o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			}
		} else if (lead != null) {
			// a fight on the way is over: blinkers back on until the target
			for (ThreatFleetOrders.Order o : orders) {
				if (o != lead) o.fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
			}
		}
		if (!f.close && lead != null && hive != null && hive.getPrimaryEntity() != null
				&& lead.fleet.getContainingLocation() == hive.getPrimaryEntity().getContainingLocation()
				&& Misc.getDistance(lead.fleet, hive.getPrimaryEntity()) <= CLOSE_RANGE) {
			f.close = true;
			// the lead picks the fights, with the fleets on its heels; stragglers
			// stay blinkered until they catch up. Unblinkered everywhere, each
			// fleet chased a swarm of its own and fought it alone (16 fleets over
			// Alpha Novy Tayvay I, one per battle)
			lead.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			for (ThreatFleetOrders.Order o : orders) {
				if (o != lead && withLead(lead, o)) o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			}
			ThreatIncConfig.log("Hunting force " + f.factionId + " closes on " + hive.getName() + ": "
					+ orders.size() + " fleets, " + (int) fp + " FP present against " + (int) garrisonFP(hive));
		}
		if (hive != null && isHive(hive) && garrisonFP(hive) > 0f) return;
		// on to what the gate reads now
		MarketAPI next = strongest(f.systemId, f.targetIds);
		if (next == null) {
			standDownAll(f, orders, cleared(f));
			return;
		}
		float need = garrisonFP(next) * margin;
		if (fp < need) {
			standDownAll(f, orders, "outmatched by " + next.getName() + ", " + (int) fp + " FP against " + (int) need);
			return;
		}
		f.baseFP = fp;
		IncursionManager.huntThinned(f.systemId);
		sendIn(f, orders, lead != null ? Collections.singletonList(lead) : inLocation(orders, system), next);
		ThreatIncConfig.log("Hunting force " + f.factionId + " moves on to " + next.getName() + " with "
				+ (int) fp + " FP against " + (int) garrisonFP(next));
	}

	/** How close to the lead a fleet counts as with it: at the fight, and fighting beside it. */
	protected static final float WITH_RANGE = 2000f;

	protected static ThreatFleetOrders.Order leadOf(Force f, List<ThreatFleetOrders.Order> orders) {
		for (ThreatFleetOrders.Order o : orders) {
			if (o.fleet.getId().equals(f.leadFleetId)) return o;
		}
		return null;
	}

	protected static boolean withLead(ThreatFleetOrders.Order lead, ThreatFleetOrders.Order o) {
		return o.fleet.getContainingLocation() == lead.fleet.getContainingLocation()
				&& Misc.getDistance(o.fleet, lead.fleet) <= WITH_RANGE;
	}

	/**
	 * Warship points in the fight: the fleets that went in ({@link Force#inForce})
	 * and the lead, wherever they are; on a force from before that was recorded,
	 * the lead and the fleets with it, or - no lead - every fleet in the system.
	 */
	protected static float presentFP(Force f, List<ThreatFleetOrders.Order> orders, ThreatFleetOrders.Order lead,
			StarSystemAPI system) {
		float fp = 0f;
		for (ThreatFleetOrders.Order o : orders) {
			boolean here;
			if (f.inForce != null) here = o == lead || f.inForce.contains(o.fleet.getId());
			else here = lead != null ? o == lead || withLead(lead, o) : o.fleet.getContainingLocation() == system;
			if (here) fp += combatFP(o.fleet);
		}
		return fp;
	}

	protected static List<ThreatFleetOrders.Order> inLocation(List<ThreatFleetOrders.Order> orders,
			StarSystemAPI system) {
		List<ThreatFleetOrders.Order> result = new ArrayList<ThreatFleetOrders.Order>();
		for (ThreatFleetOrders.Order o : orders) {
			if (o.fleet.getContainingLocation() == system) result.add(o);
		}
		return result;
	}

	protected static int countIn(List<ThreatFleetOrders.Order> orders, List<ThreatFleetOrders.Order> of) {
		int n = 0;
		for (ThreatFleetOrders.Order o : of) {
			if (orders.contains(o)) n++;
		}
		return n;
	}

	/**
	 * Where a faction's forces muster outside a hive system: off its hyperspace
	 * anchor, each faction on its own bearing. One shared point had hostile
	 * factions' forces fighting each other there (a Diktat force against a
	 * League one, both hunting Isirah).
	 */
	protected static Vector2f musterPoint(StarSystemAPI system, String factionId) {
		List<String> ids = new ArrayList<String>(ThreatWarState.warFactionIds());
		Collections.sort(ids);
		int slot = Math.max(0, ids.indexOf(factionId));
		float angle = 360f * slot / Math.max(1, ids.size());
		Vector2f dir = Misc.getUnitVectorAtDegreeAngle(angle);
		Vector2f at = system.getLocation();
		return new Vector2f(at.x + dir.x * MUSTER_OFFSET, at.y + dir.y * MUSTER_OFFSET);
	}

	/**
	 * At the force's muster point, as the force recorded it: near the primary
	 * base's planet for a base inside the hive system, otherwise in hyperspace
	 * near (musterX, musterY). Judged per fleet by its own base before, so a
	 * contributor from another system never counted at an in-system muster.
	 */
	protected static boolean atMuster(Force f, ThreatFleetOrders.Order o, StarSystemAPI system) {
		if (f.musterInSystem) {
			if (o.fleet.getContainingLocation() != system) return false;
			SectorEntityToken at = f.musterEntityId != null ? system.getEntityById(f.musterEntityId) : null;
			return at == null || Misc.getDistance(o.fleet, at) <= MUSTER_RANGE;
		}
		if (!f.musterInSystem && f.musterX == 0f && f.musterY == 0f) {
			// a force saved before its muster was recorded: the old per-fleet rule
			MarketAPI base = baseOf(o);
			if (base != null && base.getStarSystem() == system) return o.fleet.getContainingLocation() == system;
			return o.fleet.isInHyperspace()
					&& Misc.getDistance(o.fleet.getLocation(), system.getLocation()) <= MUSTER_RANGE;
		}
		if (!o.fleet.isInHyperspace()) return false;
		return Misc.getDistance(o.fleet.getLocation(), new Vector2f(f.musterX, f.musterY)) <= MUSTER_RANGE;
	}

	/**
	 * Sends the force at a colony AS ONE: the slowest fleet of {@code leadFrom}
	 * (the mustered fleets at go-in; any fleet when none) leads with the hunt
	 * orders, the rest FOLLOW it, and all stay blinkered until the lead is over
	 * the target. Sent in each on its own heading, the fleets strung out by burn
	 * speed and route and fought the garrisons they passed alone (999 FP of
	 * Independents met Fjalar's 746 FP with 271 FP of their own, 5,000 units
	 * short of Corb). A straggler picked as lead dragged the mustered fleets
	 * back to it for 15 days (Gilead, Run 7).
	 */
	protected static void sendIn(Force f, List<ThreatFleetOrders.Order> orders,
			List<ThreatFleetOrders.Order> leadFrom, MarketAPI target) {
		ThreatFleetOrders.Order lead = null;
		float slowest = Float.MAX_VALUE;
		for (ThreatFleetOrders.Order o : leadFrom.isEmpty() ? orders : leadFrom) {
			float burn = o.fleet.getFleetData().getMinBurnLevel();
			if (burn < slowest) {
				slowest = burn;
				lead = o;
			}
		}
		if (lead == null) return;
		f.leadFleetId = lead.fleet.getId();
		f.close = false;
		for (ThreatFleetOrders.Order o : orders) {
			o.targetId = target.getId();
			o.targetName = target.getName();
			// en route again until it is over the colony (the board reads arrived)
			if (o.arrived) o.credited = true;
			o.arrived = false;
			MarketAPI base = baseOf(o);
			if (o == lead) {
				ThreatFleetOrders.engageHunt(o.fleet, base, target, o.daysLeft());
			} else {
				o.fleet.clearAssignments();
				o.fleet.addAssignment(FleetAssignment.FOLLOW, lead.fleet, Math.max(1f, o.daysLeft()),
						"hunting the swarms over " + target.getName());
				if (base != null && base.getPrimaryEntity() != null) {
					o.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(), 1000f,
							"returning to " + base.getName());
				}
			}
			o.fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		}
	}

	protected static MarketAPI baseOf(ThreatFleetOrders.Order o) {
		return o.baseMarketId != null ? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
	}

	protected static void standDownAll(Force f, List<ThreatFleetOrders.Order> orders, String why) {
		forces().remove(f.id);
		// a force that fought leaves the swarms thinner than the monthly siege tick will find them
		if (f.engaged) IncursionManager.huntThinned(f.systemId);
		for (ThreatFleetOrders.Order o : orders) {
			o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			// a force that never went in spent nothing: provisions back in full now, so the
			// return rule's refund cut finds nothing left to cut
			MarketAPI home = baseOf(o);
			if (!f.engaged && home != null) refundShort(o.fleet, home, 1f);
			ThreatFleetOrders.standDown(o, why);
		}
	}

	/**
	 * A hunt with no force: moves on when its colony's swarms are gone (an NPC
	 * one only while it still beats the next garrison by the margin), and goes
	 * home when the whole system is clear or it is badly hurt.
	 */
	protected static void advanceSingle(ThreatFleetOrders.Order o) {
		boolean player = Global.getSector().getPlayerFaction().getId().equals(o.factionId);
		MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		if (ThreatReturns.orderHealth(o.fleet) < ThreatIncConfig.softenRetreatStrength()) {
			if (player) {
				ThreatNotice.titled("Hunt Broken Off").bad().icon(Global.getSector().getPlayerFaction())
						.line("%s is badly hurt", o.fleet.getName())
						.line("It breaks off the hunt over %s",
								hive != null ? ThreatNotice.market(hive) : o.targetName).send();
			}
			ThreatFleetOrders.standDown(o, "badly hurt");
			return;
		}
		if (hive != null && isHive(hive) && garrisonFP(hive) > 0f) return;
		// the system from the order: a colony a siege destroyed meanwhile
		// is out of the economy, and its siblings may still field swarms
		String systemId = huntSystemId(o);
		MarketAPI next = systemId != null ? weakest(systemId) : null;
		if (next == null) {
			if (player) {
				ThreatNotice.titled("Swarms Hunted Down").good().icon(Global.getSector().getPlayerFaction())
						.line("%s has hunted down the Defense Swarms", o.fleet.getName())
						.line("Heading home").send();
			}
			ThreatFleetOrders.standDown(o, "the swarms are gone");
			IncursionManager.huntThinned(systemId);
			return;
		}
		IncursionManager.huntThinned(systemId);
		if (!player && combatFP(o.fleet) < garrisonFP(next) * margin()) {
			ThreatFleetOrders.standDown(o, "outmatched by " + next.getName());
			return;
		}
		if (player) {
			ThreatNotice.titled("Hunt Moves On").icon(Global.getSector().getPlayerFaction())
					.line("%s moves on to the swarms over %s", o.fleet.getName(), ThreatNotice.market(next))
					.send();
		}
		ThreatFleetOrders.retargetHunt(o, next);
		ThreatIncConfig.log("Hunting fleet moves on to " + next.getName());
	}

	protected static boolean isHive(MarketAPI market) {
		return Factions.THREAT.equals(market.getFactionId());
	}
}
