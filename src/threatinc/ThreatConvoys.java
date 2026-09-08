package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.util.Misc;

/**
 * CONVOYS - the physical staging layer (docs/strategy-layer.md). A mobilised
 * faction's expedition draws only from the colony it sails from, so the rest
 * of the faction ships war materiel to that STAGING BASE by convoy: a real
 * fleet with the marines, armaments, fuel and supplies aboard, moving through
 * hyperspace like any other. Lose the convoy and the stock is gone; the
 * Threat hunts them, and so can the player.
 *
 * <p>Logistics AI runs on the slow tick, per mobilised faction: every
 * military world within expedition range of a live hive is a staging base;
 * when one is short of what an expedition against its nearest hive would
 * draw, the same-faction colony with the most spare stock within convoy range
 * sends a convoy. Arrivals and losses resolve on the fast poll.
 */
public class ThreatConvoys {

	public static final String KEY_CONVOYS = "threatinc_convoys";
	/** Fleet memory flag marking a war-materiel convoy. */
	public static final String CONVOY_FLAG = "$threatinc_convoy";
	/** The smallest load worth a front run or a relief convoy; below it the sailing waits. */
	public static final float FRONT_RUN_MIN_MARINES = 50f;
	public static final float FRONT_RUN_MIN_ARMAMENTS = 20f;

	/** One convoy in flight. The cargo is also physically aboard the fleet. */
	public static class Convoy {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String fromMarketId;
		public String toMarketId;
		public float marines;
		public float armaments;
		public float fuel;
		public float supplies;
		public long departedTimestamp;
		/** Set for a FRONT RUN: the hive world whose friendly front this convoy supplies or picks up. */
		public String frontMarketId;
		/** A withdrawal run: lands empty, lifts the front off, carries it home. */
		public boolean pickup;
		/** Whether the run has been ordered from the jump-point in to the planet. */
		public boolean runningIn;
		/** When it started waiting at the jump-point for the orbit to clear; 0 = not waiting. */
		public long waitSinceTimestamp;
		/** The faction whose colony receives the cargo when it is not the sender's own; null otherwise. */
		public String recipientFactionId;
		/** A player aid convoy: paid in credits, on the capacity ledger, earning standing on landing. */
		public boolean aid;

		public boolean isFrontRun() {
			return frontMarketId != null;
		}

		/** The sender: a colony, or - for a front run out of one - an outpost. */
		public String fromName() {
			return ThreatBases.nameOf(fromMarketId);
		}

		/** The destination: a colony, a hive world (front run), or an outpost. */
		public String toName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(toMarketId);
			return m != null ? m.getName() : ThreatBases.nameOf(toMarketId);
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Convoy> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CONVOYS);
		if (!(val instanceof List)) {
			val = new ArrayList<Convoy>();
			Global.getSector().getPersistentData().put(KEY_CONVOYS, val);
		}
		return (List<Convoy>) val;
	}

	public static boolean isConvoy(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(CONVOY_FLAG);
	}

	public static List<Convoy> convoysFor(String factionId) {
		List<Convoy> result = new ArrayList<Convoy>();
		for (Convoy c : all()) {
			if (factionId == null || factionId.equals(c.factionId)) result.add(c);
		}
		return result;
	}

	/** Whether this fleet is a convoy the layer is still tracking (not arrived, lost or recalled). */
	public static boolean isTracked(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		for (Convoy c : all()) {
			if (c.fleet == fleet) return true;
		}
		return false;
	}

	/** Cargo value for escort sizing: marines weigh most, provisions least. */
	public static float cargoValue(float marines, float armaments, float fuel, float supplies) {
		return marines * 1f + armaments * 0.5f + fuel * 0.1f + supplies * 0.1f;
	}

	protected static boolean convoyBoundFor(String marketId) {
		for (Convoy c : all()) {
			if (marketId.equals(c.toMarketId)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// logistics AI (slow tick)
	// ------------------------------------------------------------------

	/**
	 * A STAGING BASE is the military world a siege sails from: the faction's
	 * nearest base in expedition range of a live hive (the same pick the
	 * Siege button makes, ThreatFleetOrders.pickBase). Returns the nearest
	 * hive this world is the staging base for, or null - a military world
	 * that is in range of hives but never the nearest base for any is not a
	 * staging base, it is a donor. Before 2026-09-05 every military world in
	 * range of any hive counted, so with one hive cluster all five of the
	 * player's colonies were "staging", all wanted 4,700 marines, nobody had
	 * spare, and nothing ever concentrated anywhere. The player's base stages
	 * for the nearest hive the PLAYER HAS FOUND, at any range (the Siege
	 * button has none for the player): until 2026-09-05 evening it stocked
	 * for the nearest hive full stop, and the board named a system the
	 * player could not find on the map.
	 */
	public static StarSystemAPI stagingHive(MarketAPI base) {
		if (base == null || base.getStarSystem() == null || base.getFaction() == null) return null;
		if (!IncursionManager.isBase(base)) return null;
		boolean player = base.isPlayerOwned();
		boolean debug = ThreatIncConfig.debugMode();
		float range = player ? Float.MAX_VALUE : IncursionManager.expeditionRangeLY(base);
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			if (player && !debug && !ThreatIncData.discoveredSystems().contains(systemId)) continue;
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
			if (d > range || d >= bestDist) continue;
			if (ThreatFleetOrders.pickBase(base.getFaction(), system.getLocation()) != base) continue;
			bestDist = d;
			best = system;
		}
		return best;
	}

	/**
	 * The faction's nearest staging base within convoy range of this colony
	 * (not itself), or null. The player's colonies feed a base at any range
	 * (2026-09-05 evening: Diggers, a fuel world 20 ly out, showed a dash
	 * while the Supplies button could sail from it - "distance costs time,
	 * never permission" holds for the planner too).
	 */
	public static MarketAPI stagingBaseFor(MarketAPI colony) {
		if (colony == null || colony.getStarSystem() == null || colony.getFaction() == null) return null;
		float range = colony.getFaction().isPlayerFaction() ? Float.MAX_VALUE
				: ThreatIncConfig.convoyRangeLY();
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(colony.getFaction().getId())) {
			if (m == colony || m.getStarSystem() == null) continue;
			float d = Misc.getDistanceLY(colony.getStarSystem().getLocation(),
					m.getStarSystem().getLocation());
			if (d > range || d >= bestDist) continue;
			if (stagingHive(m) == null) continue;
			bestDist = d;
			best = m;
		}
		return best;
	}

	/**
	 * Target stock of each reserve commodity at a staging base, in
	 * ThreatReserves.COMMODITIES order: what the siege the Siege button would
	 * launch from here against its hive draws (IncursionManager.siegeWants -
	 * the same figures the button and its prompt show), times
	 * stagingTargetMult. All zeros for a world that is not a staging base.
	 */
	public static float[] stagingTargets(MarketAPI base) {
		StarSystemAPI hive = stagingHive(base);
		if (hive == null) return new float[] {0f, 0f, 0f, 0f};
		float[] wants = IncursionManager.siegeWants(base, base.getFaction(), hive);
		float mult = ThreatIncConfig.stagingTargetMult();
		for (int i = 0; i < wants.length; i++) wants[i] *= mult;
		return wants;
	}

	public static float capacityFor(String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return ThreatIncConfig.convoyMarineCapacity();
		return ThreatIncConfig.convoyCargoCapacity();
	}

	/**
	 * One planning pass: for each mobilised faction, each staging base with
	 * no convoy already inbound, find the commodity it is shortest of (as a
	 * fraction of a convoy load) and the donor colony with the most spare
	 * stock of it, then send one convoy carrying that plus whatever else the
	 * donor can spare that the base also wants.
	 */
	public static void planLogistics(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.convoyEnabled()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null) continue;
			List<MarketAPI> markets = ThreatReserves.marketsOf(factionId);
			// the bases short of the most (in convoy loads) sail first; at most
			// convoyMaxPerTick sailings per faction per tick, so a mobilised
			// navy does not flood hyperspace with a dozen convoys at once
			// (seen in-game 2026-09-04: eight in one tick, two of them
			// shipping the same goods past each other)
			List<Object[]> wants = new ArrayList<Object[]>();
			for (MarketAPI base : markets) {
				if (convoyBoundFor(base.getId())) continue;
				float[] targets = stagingTargets(base);
				int worst = -1;
				float worstShort = 0f;
				for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
					String c = ThreatReserves.COMMODITIES[i];
					float shortBy = targets[i] - ThreatReserves.stock(base.getId(), c);
					// worth a sailing: minLoadFraction of a load, or of the whole
					// target when the target is smaller than a load
					if (shortBy < ThreatIncConfig.convoyMinLoadFraction()
							* Math.min(capacityFor(c), targets[i])) continue;
					float loads = shortBy / Math.max(1f, capacityFor(c));
					if (loads > worstShort) {
						worstShort = loads;
						worst = i;
					}
				}
				if (worst < 0) continue;
				wants.add(new Object[] {base, targets, Integer.valueOf(worst), Float.valueOf(worstShort)});
			}
			java.util.Collections.sort(wants, new java.util.Comparator<Object[]>() {
				public int compare(Object[] a, Object[] b) {
					return Float.compare((Float) b[3], (Float) a[3]);
				}
			});
			int maxPerTick = Math.max(1, ThreatIncConfig.convoyMaxPerTick());
			// fronts first: an army in the field outranks a depot (seen in-game
			// 2026-09-04 - staging traffic took every slot and the front starved)
			int sailed = planFrontRuns(faction, random, 0, maxPerTick);
			// then an own world under Threat invasion, then outposts shipping a
			// purged system's stock home - all before any depot
			sailed = planRelief(faction, random, sailed, maxPerTick);
			sailed = planOutpostReturns(faction, random, sailed, maxPerTick);
			for (Object[] w : wants) {
				if (sailed >= maxPerTick) break;
				MarketAPI base = (MarketAPI) w[0];
				float[] targets = (float[]) w[1];
				String need = ThreatReserves.COMMODITIES[(Integer) w[2]];
				MarketAPI donor = pickDonor(markets, base, need);
				if (donor == null) continue;
				float[] load = new float[ThreatReserves.COMMODITIES.length];
				for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
					String c = ThreatReserves.COMMODITIES[i];
					float shortBy = targets[i] - ThreatReserves.stock(base.getId(), c);
					if (shortBy <= 0f) continue;
					load[i] = Math.min(shortBy, sendable(donor, base, c));
				}
				if (dispatch(donor, base, faction, load, random) != null) sailed++;
			}
		}
	}

	/**
	 * RELIEF: an own colony with a Threat army on its surface comes before
	 * every depot. Its banked marines already fight
	 * (ThreatGroundFronts.defenderStrength), so every marine landed there is
	 * defence: one convoy of marines from the colony in convoy range that can
	 * spare the most, one at a time per invaded world.
	 */
	protected static int planRelief(FactionAPI faction, Random random, int sailed, int maxPerTick) {
		List<MarketAPI> markets = ThreatReserves.marketsOf(faction.getId());
		float range = faction.isPlayerFaction() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		for (MarketAPI besieged : markets) {
			if (sailed >= maxPerTick) break;
			if (besieged.getStarSystem() == null || besieged.getPrimaryEntity() == null) continue;
			if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(besieged.getId()))) {
				continue;
			}
			if (convoyBoundFor(besieged.getId())) continue;
			MarketAPI donor = null;
			float best = 0f;
			for (MarketAPI d : markets) {
				if (d == besieged || d.getStarSystem() == null || d.getPrimaryEntity() == null) continue;
				if (Misc.getDistanceLY(d.getStarSystem().getLocation(),
						besieged.getStarSystem().getLocation()) > range) continue;
				float s = spare(d, Commodities.MARINES);
				if (s > best) {
					best = s;
					donor = d;
				}
			}
			if (donor == null || best < FRONT_RUN_MIN_MARINES) continue;
			float[] load = new float[ThreatReserves.COMMODITIES.length];
			load[0] = Math.min(best, capacityFor(Commodities.MARINES));
			Convoy c = dispatch(donor, besieged, faction, load, random);
			if (c == null) continue;
			sailed++;
			String who = faction.isPlayerFaction() ? "Your"
					: Misc.ucFirst(faction.getDisplayNameWithArticle());
			ThreatColonyManager.announceAlways(who + " relief convoy carries "
					+ Misc.getWithDGS((int) c.marines) + " marines from " + donor.getName()
					+ " to the defence of " + besieged.getName() + ".", Misc.getHighlightColor());
		}
		return sailed;
	}

	/**
	 * An outpost's stockpile is the forward base for front runs in its own
	 * system; once that system holds no hive there is nothing left to run to,
	 * so the stock ships home to the faction's nearest base - the outflow that
	 * keeps a ground victory's survivors in the war instead of in a station
	 * nothing can draw from. One convoy per outpost at a time, against the
	 * per-tick cap.
	 */
	protected static int planOutpostReturns(FactionAPI faction, Random random, int sailed,
			int maxPerTick) {
		for (ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(faction.getId())) {
			if (sailed >= maxPerTick) break;
			if (!o.alive() || o.entity == null || !ThreatOutposts.hasStock(o)) continue;
			if (!ThreatIncData.getLiveColonyMarkets(o.systemId).isEmpty()) continue; // still a forward base
			ThreatBases.Base from = ThreatBases.of(o);
			if (from == null || convoyFrom(from.id())) continue;
			MarketAPI home = outpostHome(faction, o);
			if (home == null) continue;
			float[] load = new float[ThreatReserves.COMMODITIES.length];
			for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
				String c = ThreatReserves.COMMODITIES[i];
				load[i] = Math.min(ThreatReserves.stock(from.id(), c), capacityFor(c));
			}
			Convoy c = dispatch(from, home, faction, load, random, null, false);
			if (c == null) continue;
			sailed++;
			ThreatIncConfig.log("Outpost stock shipping home: " + from.name() + " -> "
					+ home.getName());
		}
		return sailed;
	}

	/** Where an outpost's stock ships home once its system holds no hive: the nearest base, else the nearest colony. */
	public static MarketAPI outpostHome(FactionAPI faction, ThreatOutposts.Outpost o) {
		if (faction == null || o == null || o.entity == null) return null;
		MarketAPI home = ThreatFleetOrders.pickBase(faction, o.entity.getLocationInHyperspace());
		if (home == null) home = nearestColony(faction, o.entity.getLocationInHyperspace());
		return home;
	}

	protected static boolean convoyFrom(String baseId) {
		for (Convoy c : all()) {
			if (baseId.equals(c.fromMarketId)) return true;
		}
		return false;
	}

	/** The faction's nearest colony to a hyperspace location, military or not. */
	protected static MarketAPI nearestColony(FactionAPI faction, Vector2f hyperLoc) {
		if (hyperLoc == null) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), hyperLoc);
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// front runs: supply and withdrawal (docs/design-theory.md 8.3)
	// ------------------------------------------------------------------

	protected static boolean convoyBoundForFront(String hiveMarketId) {
		for (Convoy c : all()) {
			if (hiveMarketId.equals(c.frontMarketId)) return true;
		}
		return false;
	}

	/** What a friendly front wants brought: [marines, armaments]; a withdrawal call wants a pickup instead. */
	public static float[] frontWants(ThreatGroundFronts.GroundFront front, MarketAPI hive) {
		float marines = ThreatIncConfig.frontReinforceFraction()
				* ThreatGroundFronts.landedStrength(front) - front.marines;
		// armaments for the army the run leaves behind, not the depleted one:
		// a front reinforced back toward its peak burns at the peak's rate
		float strength = Math.max(front.marines, ThreatIncConfig.frontReinforceFraction()
				* ThreatGroundFronts.landedStrength(front));
		float armaments = ThreatIncConfig.frontResupplyDays()
				* ThreatGroundFronts.dailyUpkeep(strength) - front.armaments;
		return new float[] {Math.max(0f, marines), Math.max(0f, armaments)};
	}

	/**
	 * Where a front run sails from. An OUTPOST of the faction in the hive's
	 * own system is the forward base: it is already there, and a ground
	 * victory next door leaves its garrison stockpile behind, so a run that
	 * the outpost can actually cover loads there instead of crossing
	 * light-years - and a pickup lands the front in it rather than shipping it
	 * home. An outpost too thin to cover the run falls through to the nearest
	 * military colony in reach, as before.
	 */
	protected static ThreatBases.Base pickFrontBase(FactionAPI faction, MarketAPI hive,
			boolean pickup, float[] wants) {
		ThreatOutposts.Outpost o = ThreatOutposts.outpostIn(faction.getId(), hive.getStarSystem());
		if (o != null) {
			if (pickup) return ThreatBases.of(o);
			float marines = Math.min(wants[0], ThreatOutposts.stock(o, Commodities.MARINES));
			float armaments = Math.min(wants[1],
					ThreatOutposts.stock(o, Commodities.HAND_WEAPONS));
			if (marines >= FRONT_RUN_MIN_MARINES || armaments >= FRONT_RUN_MIN_ARMAMENTS) {
				return ThreatBases.of(o);
			}
		}
		MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
		return ThreatBases.of(base);
	}

	/**
	 * Every friendly front of a mobilised faction with no run already bound
	 * for it: a withdrawal call gets a pickup, a hungry front gets a supply
	 * run from the faction's forward outpost or nearest base in reach, out of
	 * that base's stock (above a colony's floor). Counts against the per-tick cap.
	 */
	protected static int planFrontRuns(FactionAPI faction, Random random, int sailed, int maxPerTick) {
		if (!ThreatIncConfig.frontRunsEnabled()) return sailed;
		for (ThreatGroundFronts.GroundFront front
				: new ArrayList<ThreatGroundFronts.GroundFront>(ThreatGroundFronts.fronts().values())) {
			if (sailed >= maxPerTick) return sailed;
			String owner = front.factionId != null ? front.factionId
					: com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER;
			if (!faction.getId().equals(owner)) continue;
			if (convoyBoundForFront(front.marketId)) continue;
			MarketAPI hive = ThreatIncData.resolveColonyMarket(front.marketId);
			if (hive == null || hive.getStarSystem() == null) continue;
			// a contested orbit is a closed door: the navy clears it first
			// (one Support sortie per world at a time), and next tick's run goes in
			if (canRunTo(faction, hive) != null) {
				if (supportFor(faction, hive)) sailed++;
				continue;
			}
			if (front.withdrawRequested) {
				ThreatBases.Base to = pickFrontBase(faction, hive, true, new float[] {0f, 0f});
				if (to == null) continue;
				if (dispatchFrontRun(to, hive, front, faction, new float[] {0f, 0f}, true, random)
						!= null) sailed++;
				continue;
			}
			float[] wants = frontWants(front, hive);
			float upkeepDays = ThreatGroundFronts.dailyUpkeep(front) > 0f
					? wants[1] / ThreatGroundFronts.dailyUpkeep(front) : 0f;
			// not worth a sailing for less than a few days of armaments or a handful of marines
			if (upkeepDays < 10f && wants[0] < 100f) continue;
			ThreatBases.Base base = pickFrontBase(faction, hive, false, wants);
			if (base == null) continue;
			float[] load = new float[] {
					Math.min(wants[0], Math.min(ThreatBases.available(base, Commodities.MARINES),
						ThreatIncConfig.convoyMarineCapacity())),
					Math.min(wants[1], Math.min(ThreatBases.available(base, Commodities.HAND_WEAPONS),
						ThreatIncConfig.convoyCargoCapacity()))};
			if (load[0] < FRONT_RUN_MIN_MARINES && load[1] < FRONT_RUN_MIN_ARMAMENTS) continue;
			if (dispatchFrontRun(base, hive, front, faction, load, false, random) != null) sailed++;
		}
		return sailed;
	}

	/**
	 * Why no run can sail to this world now, or null. A run is REFUSED
	 * outright while Defense Swarms hold the orbit and nothing friendly is
	 * there to clear it - the fleet does not sail to idle at the jump-point.
	 * The in-flight wait in {@link #pollFrontRun} stays as the fallback for
	 * runs already at sea when the orbit closes behind them.
	 */
	public static String canRunTo(FactionAPI faction, MarketAPI hive) {
		if (faction == null || hive == null) return "No front there.";
		if (!ThreatGroundFronts.orbitContested(hive.getId())) return null;
		if (ThreatFleetOrders.friendlyOrbit(faction.getId(), hive.getId())) return null;
		return "Orbit contested - send Support or Defend first.";
	}

	/** Sends one Support sortie to clear a contested orbit, if none is out already (a Defend on its way there counts). */
	protected static boolean supportFor(FactionAPI faction, MarketAPI hive) {
		if (!ThreatIncConfig.supportEnabled()) return false;
		if (ThreatFleetOrders.hasSupport(faction.getId(), hive.getId())) return false;
		if (ThreatFleetOrders.hasDefend(faction.getId(), hive.getId())) return false;
		return ThreatFleetOrders.dispatchSupport(faction, hive) != null;
	}

	/**
	 * What the board's Supply order asks the base for at a load tier: what
	 * the front wants, floored to a worthwhile run (Min); that times
	 * convoyExtraLoadFactor (Med); a full hull load, wanted or not (Max).
	 * The ask is then capped by the base's stock above its floor and by the
	 * convoy's capacity, so a tier raises the ceiling, never the base's
	 * ability to fill it.
	 */
	public static float[] supplyAsk(ThreatGroundFronts.GroundFront front, MarketAPI hive, int tier) {
		if (tier >= 2) {
			return new float[] {ThreatIncConfig.convoyMarineCapacity(),
					ThreatIncConfig.convoyCargoCapacity()};
		}
		float[] wants = frontWants(front, hive);
		// a hand order always sends a worthwhile load, wanted or not
		float[] asked = new float[] {Math.max(wants[0], 200f),
				Math.max(wants[1], ThreatGroundFronts.dailyUpkeep(front) * 30f)};
		if (tier == 1) {
			float mult = ThreatIncConfig.convoyExtraLoadFactor();
			asked[0] *= mult;
			asked[1] *= mult;
		}
		return asked;
	}

	/**
	 * The board's Supply order: a run to this front now, from the faction's
	 * outpost in the hive's system or the nearest base, ignoring the per-tick
	 * cap, carrying what the load tier asks for. Null if the orbit is
	 * contested with nothing friendly holding it, no base is in reach, or it
	 * has nothing above its floor to send.
	 */
	public static Convoy supplyFront(MarketAPI hive, FactionAPI faction, int tier, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		if (canRunTo(faction, hive) != null) return null;
		float[] asked = supplyAsk(front, hive, tier);
		ThreatBases.Base base = pickFrontBase(faction, hive, false, asked);
		if (base == null) return null;
		float[] load = new float[] {
				Math.min(asked[0], Math.min(ThreatBases.available(base, Commodities.MARINES),
						ThreatIncConfig.convoyMarineCapacity())),
				Math.min(asked[1], Math.min(ThreatBases.available(base, Commodities.HAND_WEAPONS),
						ThreatIncConfig.convoyCargoCapacity()))};
		if (load[0] <= 0f && load[1] <= 0f) return null;
		return dispatchFrontRun(base, hive, front, faction, load, false, random);
	}

	/** Why the board's Supply order at this load tier would send nothing now, or null if a run would sail. */
	public static String supplyBlockReason(FactionAPI faction, MarketAPI hive, int tier) {
		ThreatGroundFronts.GroundFront front = hive != null
				? ThreatGroundFronts.getFront(hive.getId()) : null;
		if (front == null || faction == null) return "No front there.";
		if (!ThreatIncConfig.frontRunsEnabled()) return "Front runs are disabled in the mod settings.";
		if (convoyBoundForFront(hive.getId())) return "A run is already on its way there.";
		String refused = canRunTo(faction, hive);
		if (refused != null) return refused;
		float[] wants = frontWants(front, hive);
		// a run only sails for what the front is short of: marines back toward
		// its peak, armaments up to frontResupplyDays. Max is the player asking
		// for a hull load whether the front wants one or not, so it always sails.
		if (tier < 2 && wants[0] <= 0f && wants[1] <= 0f) {
			return "The front wants nothing: at strength and stocked for "
					+ (int) ThreatIncConfig.frontResupplyDays() + " days.";
		}
		float[] asked = supplyAsk(front, hive, tier);
		ThreatBases.Base base = pickFrontBase(faction, hive, false, asked);
		boolean marinesOk = asked[0] > 0f && base != null
				&& ThreatBases.available(base, Commodities.MARINES) > 0f;
		boolean armsOk = asked[1] > 0f && base != null
				&& ThreatBases.available(base, Commodities.HAND_WEAPONS) > 0f;
		if (!marinesOk && !armsOk) {
			return "No base in reach has marines or heavy armaments above its floor to send.";
		}
		return null;
	}

	/** Why the board's Pull out order would send nothing now, or null if a run would sail. */
	public static String pullOutBlockReason(FactionAPI faction, MarketAPI hive) {
		ThreatGroundFronts.GroundFront front = hive != null
				? ThreatGroundFronts.getFront(hive.getId()) : null;
		if (front == null || faction == null) return "No front there.";
		if (!ThreatIncConfig.frontRunsEnabled()) return "Front runs are disabled in the mod settings.";
		if (convoyBoundForFront(hive.getId())) return "A run is already on its way there.";
		String refused = canRunTo(faction, hive);
		if (refused != null) return refused;
		if (pickFrontBase(faction, hive, true, new float[] {0f, 0f}) == null) return "No base in reach.";
		return null;
	}

	/** The board's Pull out order: a pickup run for this front now. Refused on a contested orbit. */
	public static Convoy pullOutFront(MarketAPI hive, FactionAPI faction, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		if (canRunTo(faction, hive) != null) return null;
		ThreatBases.Base base = pickFrontBase(faction, hive, true, new float[] {0f, 0f});
		if (base == null) return null;
		front.withdrawRequested = true;
		return dispatchFrontRun(base, hive, front, faction, new float[] {0f, 0f}, true, random);
	}

	/**
	 * Builds the run at the base: transports and freighters sized to the load
	 * (or, for a pickup, to the front it will lift), escorted by cargo value.
	 * Sails for the hive system's jump-point; poll() takes it from there. The
	 * base may be a colony or an outpost - a run out of an outpost spawns at
	 * the station and draws from its stockpile, which has no floor.
	 */
	protected static Convoy dispatchFrontRun(ThreatBases.Base base, MarketAPI hive,
			ThreatGroundFronts.GroundFront front, FactionAPI faction, float[] load, boolean pickup,
			Random random) {
		StarSystemAPI system = base.starSystem();
		SectorEntityToken from = base.entity();
		SectorEntityToken door = ThreatFleetOrders.interceptPoint(hive.getStarSystem());
		if (system == null || from == null || door == null) return null;

		float marinesForHulls = pickup ? Math.max(100f, front.marines) : load[0];
		float cargoForHulls = pickup ? Math.max(100f, front.armaments) : load[1];
		float escort = ThreatIncConfig.convoyEscortFP()
				+ cargoValue(marinesForHulls, cargoForHulls, 0f, 0f) / 1000f
						* ThreatIncConfig.convoyEscortPerThousand();
		float freighterPts = Math.max(10f, cargoForHulls / 60f);
		float transportPts = Math.max(10f, marinesForHulls / 40f);
		FleetParamsV3 params = new FleetParamsV3(base.sourceMarket(), base.hyperLoc(),
				faction.getId(), null, FleetTypes.SUPPLY_FLEET,
				escort, freighterPts, 0f, transportPts, 0f, 0f, 0f);
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName(pickup ? "Evacuation Convoy" : "Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = base.id();
		c.toMarketId = hive.getId();
		c.frontMarketId = hive.getId();
		c.pickup = pickup;
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();
		if (!pickup) {
			CargoAPI cargo = fleet.getCargo();
			int m = (int) Math.min(load[0], cargo.getFreeCrewSpace());
			if (m > 0) {
				int taken = (int) ThreatBases.draw(base, Commodities.MARINES, m);
				if (taken > 0) cargo.addMarines(taken);
				c.marines = taken;
			}
			int a = (int) Math.min(load[1], cargo.getSpaceLeft());
			if (a > 0) {
				int taken = (int) ThreatBases.draw(base, Commodities.HAND_WEAPONS, a);
				if (taken > 0) cargo.addCommodity(Commodities.HAND_WEAPONS, taken);
				c.armaments = taken;
			}
			if (c.marines <= 0f && c.armaments <= 0f) {
				Misc.fadeAndExpire(fleet);
				return null;
			}
		}
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, door, 1000f,
				(pickup ? "withdrawal run to " : "supply run to ") + hive.getName());
		all().add(c);
		ThreatIncConfig.log((pickup ? "Withdrawal run dispatched: " : "Supply run dispatched: ")
				+ faction.getId() + " " + base.name() + " -> " + hive.getName() + " ("
				+ (int) c.marines + " marines, " + (int) c.armaments + " armaments; escort "
				+ (int) escort + " FP)");
		ThreatRaiders.consider(c, random);
		return c;
	}

	/**
	 * A front run inside the hive system: wait at the door while Defense
	 * Swarms hold the orbit (up to frontRunWaitDays, then home), run in when
	 * it clears, and on arrival either land the cargo on the front or lift
	 * the front off. Returns true when the convoy was handled (or removed).
	 */
	protected static void pollFrontRun(Convoy c) {
		CampaignFleetAPI fleet = c.fleet;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(c.frontMarketId);
		MarketAPI hive = ThreatIncData.resolveColonyMarket(c.frontMarketId);
		if (front == null || hive == null || hive.getPrimaryEntity() == null) {
			// the front died, won, or was pulled out by hand: nothing to do there
			returnHome(c);
			return;
		}
		SectorEntityToken planet = hive.getPrimaryEntity();
		if (fleet.getContainingLocation() != planet.getContainingLocation()) return; // en route
		if (!c.runningIn) {
			if (ThreatGroundFronts.orbitContested(hive.getId())) {
				long now = Global.getSector().getClock().getTimestamp();
				if (c.waitSinceTimestamp == 0L) {
					c.waitSinceTimestamp = now;
					ThreatIncConfig.log("Front run waiting at the door of " + hive.getName()
							+ ": orbit contested");
				} else if (Global.getSector().getClock().getElapsedDaysSince(c.waitSinceTimestamp)
						> ThreatIncConfig.frontRunWaitDays()) {
					ThreatIncConfig.log("Front run gave up at " + hive.getName()
							+ ": orbit still contested after " + (int) ThreatIncConfig.frontRunWaitDays()
							+ " days");
					returnHome(c);
				}
				return;
			}
			c.runningIn = true;
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 1000f,
					"running in to " + hive.getName());
			return;
		}
		if (!ThreatReturns.onLeg(fleet, planet, "running in to " + hive.getName())) return;
		CargoAPI cargo = fleet.getCargo();
		String who = ThreatWarState.displayName(c.factionId);
		if (c.pickup) {
			// the level has to be read before the front is taken apart, and rides
			// home on the fleet so the base it lands at is seasoned by it
			float level = ThreatMarineXP.frontLevel(front);
			int[] rec = ThreatGroundFronts.withdraw(hive.getId());
			if (rec[0] > 0) {
				cargo.addMarines(rec[0]);
				fleet.getMemoryWithoutUpdate().set(ThreatReturns.MEM_MARINE_LEVEL, level);
			}
			if (rec[1] > 0) cargo.addCommodity(Commodities.HAND_WEAPONS, rec[1]);
			ThreatColonyManager.announceAlways(who + " ground forces have been lifted off "
					+ hive.getName() + " - " + rec[0] + " marines and " + rec[1]
					+ " heavy armaments are on their way home.", Misc.getHighlightColor());
			ThreatIncConfig.log("Front withdrawn to fleet at " + hive.getName() + ": " + rec[0]
					+ " marines, " + rec[1] + " armaments");
		} else {
			int marines = cargo.getMarines();
			int armaments = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
			if (marines > 0) cargo.removeMarines(marines);
			if (armaments > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, armaments);
			ThreatGroundFronts.resupply(front, marines, armaments);
			ThreatColonyManager.announceAlways("A " + who + " supply run has landed on "
					+ hive.getName() + ": " + marines + " marines and " + armaments
					+ " heavy armaments reach the front.", Misc.getPositiveHighlightColor());
			ThreatIncConfig.log("Supply run delivered at " + hive.getName() + ": " + marines
					+ " marines, " + armaments + " armaments");
		}
		all().remove(c);
		// home on the tracked leg: survivors and any undelivered cargo return to the base
		ThreatReturns.sendHome(fleet, c.factionId, c.fromMarketId);
	}

	/**
	 * A player-ordered staging run (the board's Supplies button): fill a
	 * convoy for this colony from the same-faction colony that can spare the
	 * most, at the board's load tier ({@link #stageLoad}), regardless of
	 * whether the destination is a staging base. Returns the convoy or null.
	 */
	public static Convoy stageTo(MarketAPI target, FactionAPI faction, int tier, Random random) {
		return stageTo(ThreatBases.of(target), faction, tier, random);
	}

	/** As above to either kind of base - a colony, or the player's outpost (its station is the depot). */
	public static Convoy stageTo(ThreatBases.Base target, FactionAPI faction, int tier, Random random) {
		MarketAPI donor = stageDonor(target, faction, tier);
		if (donor == null) return null;
		return dispatch(ThreatBases.of(donor), target, faction, stageLoad(donor, target, tier),
				random, null, false);
	}

	/**
	 * What a hand-ordered convoy from this donor carries to this target at a
	 * load tier: what the target is short of its staging target (Min), that
	 * times convoyExtraLoadFactor (Med), or a hull load of everything the
	 * donor can spare (Max - the 2026-09-05 rule, kept as the override the
	 * player reaches for when the shortfall is not the point). A run to an
	 * OUTPOST is always a hull load: it banks nothing, so nothing there is
	 * short of anything. Every tier is capped by what the donor holds above
	 * its sortie floor (ThreatReserves.available) and by the convoy's
	 * capacity. The planner's rules (the donor keep, a worthwhile load,
	 * donors that are not staging bases, convoy range) are for the automatic
	 * traffic, not the override. A player donor's load is fitted to its own
	 * free hulls here, so the board quotes exactly what dispatch will carry.
	 */
	public static float[] stageLoad(MarketAPI donor, MarketAPI target, int tier) {
		return stageLoad(donor, ThreatBases.of(target), tier);
	}

	/** As above to either kind of base. */
	public static float[] stageLoad(MarketAPI donor, ThreatBases.Base target, int tier) {
		float[] load = new float[ThreatReserves.COMMODITIES.length];
		if (donor == null || target == null) return load;
		float[] targets = target.market != null ? stagingTargets(target.market)
				: new float[] {0f, 0f, 0f, 0f};
		for (int i = 0; i < load.length; i++) {
			String c = ThreatReserves.COMMODITIES[i];
			float want;
			if (tier >= 2 || target.isOutpost()) {
				want = capacityFor(c);
			} else {
				float toward = targets[i] > 0f ? targets[i] : ThreatReserves.cap(target.market, c);
				float shortBy = Math.max(0f, toward - ThreatReserves.stock(target.id(), c));
				if (tier == 1) shortBy *= ThreatIncConfig.convoyExtraLoadFactor();
				want = Math.min(capacityFor(c), shortBy);
			}
			load[i] = Math.min(want, ThreatReserves.available(donor, c));
		}
		if (donor.isPlayerOwned()) {
			load = ThreatAidCapacity.fitLoad(ThreatAidCapacity.ownFreeFP(donor), load);
		}
		return load;
	}

	/**
	 * The colony a Stage order to this target would load at: the same-faction
	 * colony whose {@link #stageLoad} is worth the most (by cargo value -
	 * marines first), preferring colonies that are not staging bases
	 * themselves; null when nothing anywhere can be spared. The board's
	 * Stage button greys out on null instead of the order failing after
	 * Confirm. No range for the player's hand orders (2026-09-05, the user's
	 * call: fuel reach is too many variables to model; distance costs time
	 * and fuel, not permission) - convoyRangeLY bounds only NPC hand orders,
	 * which no longer exist, and the planner.
	 */
	public static MarketAPI stageDonor(MarketAPI target, FactionAPI faction, int tier) {
		return stageDonor(ThreatBases.of(target), faction, tier);
	}

	/** As above to either kind of base. */
	public static MarketAPI stageDonor(ThreatBases.Base target, FactionAPI faction, int tier) {
		if (target == null || faction == null || !ThreatIncConfig.convoyEnabled()) return null;
		if (target.starSystem() == null) return null;
		if (!ThreatReserves.hasDepot(target)) return null; // nowhere to land it
		float range = faction.isPlayerFaction() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		MarketAPI best = null;
		float bestValue = 0f;
		boolean bestIsStaging = true;
		for (MarketAPI donor : ThreatReserves.marketsOf(faction.getId())) {
			if (donor == target.market || donor.getStarSystem() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					target.starSystem().getLocation()) > range) continue;
			float[] load = stageLoad(donor, target, tier);
			float value = cargoValue(load[0], load[1], load[2], load[3]);
			if (value <= 0f) continue;
			boolean staging = stagingHive(donor) != null;
			// a donor that is not itself a staging base beats one that is
			if (best != null && staging && !bestIsStaging) continue;
			if (best != null && staging == bestIsStaging && value <= bestValue) continue;
			best = donor;
			bestValue = value;
			bestIsStaging = staging;
		}
		return best;
	}

	/** Stock a colony can spare: what it holds above its keep fraction of its own cap. */
	public static float spare(MarketAPI donor, String commodityId) {
		if (ThreatReserves.committed(donor, commodityId)) return 0f; // its marines are fighting
		float keep = ThreatReserves.cap(donor, commodityId) * ThreatIncConfig.donorKeepFraction();
		return Math.max(0f, ThreatReserves.stock(donor.getId(), commodityId) - keep);
	}

	/**
	 * What a donor may send a base of one commodity: what it can spare, up to
	 * a hull load. The old EQUALISATION rule (never more than half the gap
	 * between the two stocks, 2026-09-04) is gone: it existed because every
	 * military world was a staging base and they shipped the same goods past
	 * each other, and it made concentrating at one base impossible - the
	 * receiver could never hold more than its donors. Now only the faction's
	 * nearest base for a hive stages ({@link #stagingHive}) and staging
	 * bases never donate to depots ({@link #pickDonor}), so the traffic has
	 * one direction and needs no damping.
	 */
	public static float sendable(MarketAPI donor, MarketAPI base, String commodityId) {
		return Math.min(spare(donor, commodityId), capacityFor(commodityId));
	}

	/**
	 * The least worth a sailing from this donor: convoyMinLoadFraction of a
	 * hull load, or of what the donor could ever spare when full (its cap
	 * above its keep) when that is smaller. Before 2026-09-05 it was the
	 * fraction of a hull load alone - 1,000 marines or 3,000 units at the
	 * defaults - which no colony's reserve ever reached, so nothing sailed.
	 */
	public static float minLoad(MarketAPI donor, String commodityId) {
		float fullSpare = ThreatReserves.cap(donor, commodityId)
				* (1f - ThreatIncConfig.donorKeepFraction());
		return ThreatIncConfig.convoyMinLoadFraction()
				* Math.min(capacityFor(commodityId), Math.max(0f, fullSpare));
	}

	/** The planner's donor: the colony in convoy range (the player's at any range) with the most to send that is not a staging base itself. */
	protected static MarketAPI pickDonor(List<MarketAPI> markets, MarketAPI base, String commodityId) {
		MarketAPI best = null;
		float bestSend = 0f;
		float range = base.isPlayerOwned() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		for (MarketAPI donor : markets) {
			if (donor == base || donor.getStarSystem() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					base.getStarSystem().getLocation()) > range) continue;
			if (stagingHive(donor) != null) continue; // its stock is for its own siege
			float s = sendable(donor, base, commodityId);
			if (s > bestSend) {
				bestSend = s;
				best = donor;
			}
		}
		// not worth a sailing
		if (best != null && bestSend < minLoad(best, commodityId)) return null;
		return best;
	}

	/**
	 * Builds the convoy fleet at the donor, loads the cargo aboard (clamped to
	 * what the hulls can actually carry), draws exactly that from the donor's
	 * reserve, and sends it to the base.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random) {
		return dispatch(donor, base, faction, load, random, null, false);
	}

	/**
	 * As above, bound for another faction's colony: an ally's help, or the
	 * player's aid (docs/player-aid.md). A player convoy is fitted to the
	 * donor's free capacity first and held on the ledger once loaded.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		return dispatch(ThreatBases.of(donor), ThreatBases.of(base), faction, load, random,
				recipientFactionId, aid);
	}

	public static Convoy dispatch(ThreatBases.Base donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		return dispatch(donor, ThreatBases.of(base), faction, load, random, recipientFactionId, aid);
	}

	/**
	 * The convoy itself, from either kind of base: a colony, or an outpost
	 * shipping its stockpile home ({@link #planOutpostReturns}). An outpost has
	 * no capacity ledger and no floor, so its whole stock is loadable, and no
	 * economy, so its hulls are built without a market's quality or size.
	 */
	public static Convoy dispatch(ThreatBases.Base donor, ThreatBases.Base base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		if (donor == null || base == null || faction == null) return null;
		if (faction.isPlayerFaction() && donor.market != null) {
			// the colony's own hulls: staged warships never fold into a convoy
			load = ThreatAidCapacity.fitLoad(ThreatAidCapacity.ownFreeFP(donor.market), load);
		}
		StarSystemAPI system = donor.starSystem();
		SectorEntityToken from = donor.entity();
		SectorEntityToken to = base.entity();
		if (system == null || from == null || to == null) return null;

		float marines = load[0];
		float cargoUnits = load[1] + load[2] + load[3];
		if (marines <= 0f && cargoUnits <= 0f) return null;

		// escort by cargo value (docs/design-theory.md 8.2, Blackett): a rich
		// convoy is a real fleet, a trickle sails with a picket
		float escort = ThreatIncConfig.convoyEscortFP()
				+ cargoValue(marines, load[1], load[2], load[3]) / 1000f
						* ThreatIncConfig.convoyEscortPerThousand();
		// freighters sized to the cargo, transports to the troops: roughly one
		// point of hull per 60 units / 40 marines, so the load actually fits
		float freighterPts = Math.max(10f, cargoUnits / 60f);
		float tankerPts = load[2] > 0f ? Math.max(5f, load[2] / 100f) : 0f;
		float transportPts = marines > 0f ? Math.max(10f, marines / 40f) : 0f;
		FleetParamsV3 params = new FleetParamsV3(donor.sourceMarket(), donor.hyperLoc(),
				faction.getId(), null, FleetTypes.SUPPLY_FLEET,
				escort, freighterPts, tankerPts, transportPts, 0f, 0f, 0f);
		// a player convoy is exactly the hulls the ledger sized; vanilla's own
		// fleet-size scaling stays on for NPC navies
		if (faction.isPlayerFaction()) params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName(aid ? "Aid Convoy" : "Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		// load what fits; draw only what was loaded
		CargoAPI cargo = fleet.getCargo();
		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = donor.id();
		c.toMarketId = base.id();
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();
		c.recipientFactionId = recipientFactionId;
		c.aid = aid;

		int m = (int) Math.min(marines, cargo.getFreeCrewSpace());
		if (m > 0) {
			cargo.addMarines(m);
			c.marines = ThreatReserves.draw(donor.id(), Commodities.MARINES, m);
		}
		c.armaments = loadCommodity(cargo, donor.id(), Commodities.HAND_WEAPONS, load[1]);
		c.fuel = loadCommodity(cargo, donor.id(), Commodities.FUEL, load[2]);
		c.supplies = loadCommodity(cargo, donor.id(), Commodities.SUPPLIES, load[3]);
		if (c.marines <= 0f && c.armaments <= 0f && c.fuel <= 0f && c.supplies <= 0f) {
			Misc.fadeAndExpire(fleet);
			return null;
		}

		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f,
				"carrying war materiel to " + base.name());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, from, 1000f,
				"returning to " + donor.name());
		all().add(c);
		if (faction.isPlayerFaction() && donor.market != null) {
			float[] loaded = {c.marines, c.armaments, c.fuel, c.supplies};
			ThreatAidCapacity.commit(donor.market, ThreatAidCapacity.convoyPoints(loaded), fleet,
					"convoy to " + base.name());
		}

		ThreatIncConfig.log("Convoy dispatched: " + faction.getId() + " " + donor.name()
				+ " -> " + base.name() + " (" + (int) c.marines + " marines, "
				+ (int) c.armaments + " armaments, " + (int) c.fuel + " fuel, "
				+ (int) c.supplies + " supplies; escort " + (int) escort + " FP)");
		// the hive may come for it
		ThreatRaiders.consider(c, random);
		return c;
	}

	/**
	 * After an off-screen fight vanilla removes hulls but not their cargo:
	 * trim what is aboard to what the surviving ships can carry, so losses
	 * are a fraction of the load (Blackett's constant loss per attack), not
	 * nothing-or-everything. Marines beyond the berths, then provisions
	 * beyond the holds, cheapest first.
	 */
	protected static void trimToHulls(Convoy c) {
		CargoAPI cargo = c.fleet.getCargo();
		int overCrew = -cargo.getFreeCrewSpace();
		if (overCrew > 0) {
			int drop = Math.min(cargo.getMarines(), overCrew);
			if (drop > 0) {
				cargo.removeMarines(drop);
				ThreatIncConfig.log("Convoy mauled: " + drop + " marines lost with their transports ("
						+ c.fromName() + " -> " + c.toName() + ")");
			}
		}
		float over = cargo.getSpaceUsed() - cargo.getMaxCapacity();
		if (over <= 0f) return;
		String[] order = {Commodities.SUPPLIES, Commodities.FUEL, Commodities.HAND_WEAPONS};
		for (String id : order) {
			if (over <= 0f) break;
			float have = cargo.getCommodityQuantity(id);
			float drop = Math.min(have, over);
			if (drop > 0f) {
				cargo.removeCommodity(id, drop);
				over -= drop;
				ThreatIncConfig.log("Convoy mauled: " + (int) drop + " " + id + " lost with their "
						+ "freighters (" + c.fromName() + " -> " + c.toName() + ")");
			}
		}
	}

	protected static float loadCommodity(CargoAPI cargo, String donorId, String commodityId,
			float wanted) {
		if (wanted <= 0f) return 0f;
		float fits = Math.max(0f, Math.min(wanted, cargo.getSpaceLeft()));
		int units = (int) fits;
		if (units <= 0) return 0f;
		float taken = ThreatReserves.draw(donorId, commodityId, units);
		if (taken > 0f) cargo.addCommodity(commodityId, (int) taken);
		return (int) taken;
	}

	// ------------------------------------------------------------------
	// resolution (fast poll)
	// ------------------------------------------------------------------

	/** Distance from the destination entity at which a convoy counts as arrived (ThreatReturns.arrived). */
	public static final float ARRIVAL_RANGE = ThreatReturns.ARRIVAL_RANGE;

	public static void poll() {
		if (all().isEmpty()) return;
		for (Convoy c : new ArrayList<Convoy>(all())) {
			CampaignFleetAPI fleet = c.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
				lost(c);
				continue;
			}
			float days = Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
			if (days > ThreatIncConfig.convoyTimeoutDays()) {
				Misc.fadeAndExpire(fleet);
				lost(c);
				continue;
			}
			trimToHulls(c);
			if (c.isFrontRun()) {
				pollFrontRun(c);
				continue;
			}
			ThreatBases.Base base = ThreatBases.of(c.toMarketId);
			if (base == null || base.entity() == null || !boundFor(c, base)) {
				// the destination fell or changed hands: turn around, stock stays aboard
				// until the fleet despawns home, where it is returned to the donor
				returnHome(c);
				continue;
			}
			SectorEntityToken to = base.entity();
			// vanilla ends the leg at the entity's edge and the queue falls
			// through to the despawning return: the convoy is kept on it
			if (ThreatReturns.onLeg(fleet, to, "carrying war materiel to " + base.name())) {
				arrived(c, base);
			}
		}
	}

	protected static void arrived(Convoy c, ThreatBases.Base base) {
		CampaignFleetAPI fleet = c.fleet;
		CargoAPI cargo = fleet.getCargo();
		// whatever is still aboard - losses in transit are real losses
		int marines = cargo.getMarines();
		int armaments = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		int fuel = (int) cargo.getCommodityQuantity(Commodities.FUEL);
		int supplies = (int) cargo.getCommodityQuantity(Commodities.SUPPLIES);
		if (marines > 0) cargo.removeMarines(marines);
		if (armaments > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, armaments);
		if (fuel > 0) cargo.removeCommodity(Commodities.FUEL, fuel);
		if (supplies > 0) cargo.removeCommodity(Commodities.SUPPLIES, supplies);
		ThreatReserves.deposit(base.id(), Commodities.MARINES, marines);
		ThreatReserves.deposit(base.id(), Commodities.HAND_WEAPONS, armaments);
		ThreatReserves.deposit(base.id(), Commodities.FUEL, fuel);
		ThreatReserves.deposit(base.id(), Commodities.SUPPLIES, supplies);
		// rule 5 (docs/economy-coherence.md): the colony screen sees the shipment
		// land the way a player's sale would - a trade modifier on availability
		// for vanilla's own trade-impact duration. Not counted as surplus by the
		// accrual (ThreatReserves.ownModUnits), so a shipment never banks itself.
		// An outpost has no colony screen: its storage simply holds more.
		if (base.market != null) {
			landed(base.market, Commodities.MARINES, marines);
			landed(base.market, Commodities.HAND_WEAPONS, armaments);
			landed(base.market, Commodities.FUEL, fuel);
			landed(base.market, Commodities.SUPPLIES, supplies);
		}
		all().remove(c);

		// another faction's colony: the player's standing, an ally's word
		FactionAPI sender = Global.getSector().getFaction(c.factionId);
		if (c.recipientFactionId != null && sender != null && base.market != null) {
			if (sender.isPlayerFaction()) {
				ThreatAid.onDelivered(base.market, c.recipientFactionId, marines, armaments, fuel,
						supplies, true, null);
			} else {
				ThreatCoalition.onAllyDelivered(c, base.market);
			}
		}

		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		if (sender != null && sender.isPlayerFaction() && donor != null
				&& donor.getPrimaryEntity() != null) {
			// a player fleet comes home on the tracked leg: the ledger gets its points back
			ThreatReturns.sendHome(fleet, c.factionId, donor.getId());
		} else {
			SectorEntityToken home = donor != null ? donor.getPrimaryEntity() : base.entity();
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 1000f,
					"returning to " + (donor != null ? donor.getName() : base.name()));
		}
		ThreatIncConfig.log("Convoy arrived: " + c.factionId + " at " + base.name() + " ("
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, "
				+ supplies + " supplies)");
	}

	/** Whether the convoy's destination still belongs to whom it was sent to. */
	protected static boolean boundFor(Convoy c, ThreatBases.Base base) {
		if (base.isOutpost() && !base.outpost.alive()) return false; // the station died
		if (c.factionId.equals(base.factionId())) return true;
		return c.recipientFactionId != null && c.recipientFactionId.equals(base.factionId());
	}

	/** The helper's colony within convoy range of another faction's colony that can spare the most of a commodity, or null. */
	public static MarketAPI pickAllyDonor(FactionAPI helper, MarketAPI needy, String commodityId) {
		if (helper == null || needy == null || needy.getStarSystem() == null) return null;
		MarketAPI best = null;
		float bestSpare = 0f;
		float range = helper.isPlayerFaction() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		for (MarketAPI donor : ThreatReserves.marketsOf(helper.getId())) {
			if (donor.getStarSystem() == null || donor.getPrimaryEntity() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					needy.getStarSystem().getLocation()) > range) continue;
			float s = spare(donor, commodityId);
			if (s > bestSpare) {
				bestSpare = s;
				best = donor;
			}
		}
		if (best != null && bestSpare < capacityFor(commodityId)
				* ThreatIncConfig.convoyMinLoadFraction()) return null;
		return best;
	}

	/** A landed quantity raises the base's vanilla availability as a sale of it would. */
	protected static void landed(MarketAPI base, String commodityId, int quantity) {
		if (quantity <= 0 || base == null) return;
		CommodityOnMarketAPI com = base.getCommodityData(commodityId);
		if (com == null) return;
		com.addTradeModPlus(ThreatReserves.CONVOY_SOURCE_PREFIX + Misc.genUID(), quantity,
				BaseSubmarketPlugin.TRADE_IMPACT_DAYS);
	}

	protected static void lost(Convoy c) {
		all().remove(c);
		FactionAPI faction = Global.getSector().getFaction(c.factionId);
		String who = faction == null ? c.factionId : faction.isPlayerFaction() ? "Your"
				: Misc.ucFirst(faction.getDisplayNameWithArticle());
		ThreatColonyManager.announce(who + " supply convoy from " + c.fromName() + " to "
				+ c.toName() + " has been lost with its cargo.", Misc.getNegativeHighlightColor());
		ThreatIncConfig.log("Convoy lost: " + c.factionId + " " + c.fromName() + " -> " + c.toName());
	}

	/**
	 * Recalled, or its destination is gone: the convoy turns for the donor
	 * with the cargo still aboard, and whatever survives the trip home goes
	 * back into the donor's reserve on arrival (ThreatReturns).
	 */
	protected static void returnHome(Convoy c) {
		all().remove(c);
		CampaignFleetAPI fleet = c.fleet;
		// the donor may be an outpost (a front run out of one): still theirs, still there
		ThreatBases.Base donor = ThreatBases.of(c.fromMarketId);
		if (donor != null && donor.entity() != null
				&& c.factionId.equals(donor.factionId())) {
			ThreatReturns.sendHome(fleet, c.factionId, donor.id());
		} else if (fleet != null) {
			Misc.fadeAndExpire(fleet);
		}
		ThreatIncConfig.log("Convoy recalled: " + c.factionId + " " + c.fromName()
				+ " -> " + c.toName());
	}
}
