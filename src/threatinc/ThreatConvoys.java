package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
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

		public boolean isFrontRun() {
			return frontMarketId != null;
		}

		public String fromName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(fromMarketId);
			return m != null ? m.getName() : fromMarketId;
		}

		public String toName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(toMarketId);
			return m != null ? m.getName() : toMarketId;
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
	 * A staging base is a military world within expedition range of a live
	 * hive; returns the nearest such hive system, or null if the world is not
	 * a staging base.
	 */
	public static StarSystemAPI nearestHiveInRange(MarketAPI base) {
		if (base == null || base.getStarSystem() == null) return null;
		if (!IncursionManager.hasMilitary(base)) return null;
		float range = IncursionManager.expeditionRangeLY(base);
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
			if (d > range || d >= bestDist) continue;
			bestDist = d;
			best = system;
		}
		return best;
	}

	/** Target stock of each reserve commodity at a staging base, in ThreatReserves.COMMODITIES order. */
	public static float[] stagingTargets(MarketAPI base) {
		StarSystemAPI hive = nearestHiveInRange(base);
		if (hive == null) return new float[] {0f, 0f, 0f, 0f};
		float[] wants = IncursionManager.stagingWants(base, hive);
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
					float loads = shortBy / Math.max(1f, capacityFor(c));
					if (loads > worstShort) {
						worstShort = loads;
						worst = i;
					}
				}
				if (worst < 0 || worstShort < ThreatIncConfig.convoyMinLoadFraction()) continue;
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
		float armaments = ThreatIncConfig.frontResupplyDays()
				* ThreatGroundFronts.dailyUpkeep(hive) - front.armaments;
		float marines = ThreatIncConfig.frontReinforceFraction()
				* ThreatGroundFronts.landedStrength(front) - front.marines;
		return new float[] {Math.max(0f, marines), Math.max(0f, armaments)};
	}

	/**
	 * Every friendly front of a mobilised faction with no run already bound
	 * for it: a withdrawal call gets a pickup, a hungry front gets a supply
	 * run from the faction's nearest base in reach, out of that base's
	 * reserve (above its floor). Counts against the per-tick cap.
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
			MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
			if (base == null) continue;
			if (front.withdrawRequested) {
				if (dispatchFrontRun(base, hive, front, faction, new float[] {0f, 0f}, true, random)
						!= null) sailed++;
				continue;
			}
			float[] wants = frontWants(front, hive);
			float upkeepDays = ThreatGroundFronts.dailyUpkeep(hive) > 0f
					? wants[1] / ThreatGroundFronts.dailyUpkeep(hive) : 0f;
			// not worth a sailing for less than a few days of armaments or a handful of marines
			if (upkeepDays < 10f && wants[0] < 100f) continue;
			float[] load = new float[] {
					Math.min(wants[0], Math.min(ThreatReserves.available(base, Commodities.MARINES),
							ThreatIncConfig.convoyMarineCapacity())),
					Math.min(wants[1], Math.min(ThreatReserves.available(base, Commodities.HAND_WEAPONS),
							ThreatIncConfig.convoyCargoCapacity()))};
			if (load[0] < 50f && load[1] < 20f) continue;
			if (dispatchFrontRun(base, hive, front, faction, load, false, random) != null) sailed++;
		}
		return sailed;
	}

	/**
	 * The board's Supply order: a run to this front now, from the nearest
	 * base, ignoring the per-tick cap. Null if no base is in reach or the
	 * base has nothing above its floor to send.
	 */
	public static Convoy supplyFront(MarketAPI hive, FactionAPI faction, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
		if (base == null) return null;
		float[] wants = frontWants(front, hive);
		float[] load = new float[] {
				Math.min(Math.max(wants[0], 200f), Math.min(ThreatReserves.available(base, Commodities.MARINES),
						ThreatIncConfig.convoyMarineCapacity())),
				Math.min(Math.max(wants[1], ThreatGroundFronts.dailyUpkeep(hive) * 30f),
						Math.min(ThreatReserves.available(base, Commodities.HAND_WEAPONS),
								ThreatIncConfig.convoyCargoCapacity()))};
		if (load[0] <= 0f && load[1] <= 0f) return null;
		return dispatchFrontRun(base, hive, front, faction, load, false, random);
	}

	/** The board's Pull out order: a pickup run for this front now. */
	public static Convoy pullOutFront(MarketAPI hive, FactionAPI faction, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
		if (base == null) return null;
		front.withdrawRequested = true;
		return dispatchFrontRun(base, hive, front, faction, new float[] {0f, 0f}, true, random);
	}

	/**
	 * Builds the run at the base: transports and freighters sized to the load
	 * (or, for a pickup, to the front it will lift), escorted by cargo value.
	 * Sails for the hive system's jump-point; poll() takes it from there.
	 */
	protected static Convoy dispatchFrontRun(MarketAPI base, MarketAPI hive,
			ThreatGroundFronts.GroundFront front, FactionAPI faction, float[] load, boolean pickup,
			Random random) {
		StarSystemAPI system = base.getStarSystem();
		SectorEntityToken from = base.getPrimaryEntity();
		SectorEntityToken door = ThreatFleetOrders.interceptPoint(hive.getStarSystem());
		if (system == null || from == null || door == null) return null;

		float marinesForHulls = pickup ? Math.max(100f, front.marines) : load[0];
		float cargoForHulls = pickup ? Math.max(100f, front.armaments) : load[1];
		float escort = ThreatIncConfig.convoyEscortFP()
				+ cargoValue(marinesForHulls, cargoForHulls, 0f, 0f) / 1000f
						* ThreatIncConfig.convoyEscortPerThousand();
		float freighterPts = Math.max(10f, cargoForHulls / 60f);
		float transportPts = Math.max(10f, marinesForHulls / 40f);
		FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(),
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
		c.fromMarketId = base.getId();
		c.toMarketId = hive.getId();
		c.frontMarketId = hive.getId();
		c.pickup = pickup;
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();
		if (!pickup) {
			CargoAPI cargo = fleet.getCargo();
			int m = (int) Math.min(load[0], cargo.getFreeCrewSpace());
			if (m > 0) {
				int taken = (int) ThreatReserves.drawAbove(base, Commodities.MARINES, m);
				if (taken > 0) cargo.addMarines(taken);
				c.marines = taken;
			}
			int a = (int) Math.min(load[1], cargo.getSpaceLeft());
			if (a > 0) {
				int taken = (int) ThreatReserves.drawAbove(base, Commodities.HAND_WEAPONS, a);
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
				+ faction.getId() + " " + base.getName() + " -> " + hive.getName() + " ("
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
		if (Misc.getDistance(fleet, planet) > ARRIVAL_RANGE) return;
		CargoAPI cargo = fleet.getCargo();
		String who = ThreatWarState.displayName(c.factionId);
		if (c.pickup) {
			int[] rec = ThreatGroundFronts.withdraw(hive.getId());
			if (rec[0] > 0) cargo.addMarines(rec[0]);
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
	 * A player-ordered staging run (the board's Stage button): fill a convoy
	 * for this colony from the same-faction colony that can spare the most
	 * marines (or, failing marines, the most of anything), regardless of
	 * whether the destination is a staging base. Returns the convoy or null.
	 */
	public static Convoy stageTo(MarketAPI target, FactionAPI faction, Random random) {
		if (target == null || faction == null || !ThreatIncConfig.convoyEnabled()) return null;
		List<MarketAPI> markets = ThreatReserves.marketsOf(faction.getId());
		MarketAPI donor = null;
		String best = null;
		float bestSpare = 0f;
		for (String c : ThreatReserves.COMMODITIES) {
			MarketAPI d = pickDonor(markets, target, c);
			if (d == null) continue;
			float loads = spare(d, c) / Math.max(1f, capacityFor(c));
			// marines first when any colony can spare a real load of them
			if (Commodities.MARINES.equals(c) && loads >= ThreatIncConfig.convoyMinLoadFraction()) {
				donor = d;
				best = c;
				break;
			}
			if (loads > bestSpare) {
				bestSpare = loads;
				donor = d;
				best = c;
			}
		}
		if (donor == null || best == null) return null;
		float[] load = new float[ThreatReserves.COMMODITIES.length];
		for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
			String c = ThreatReserves.COMMODITIES[i];
			load[i] = sendable(donor, target, c);
		}
		return dispatch(donor, target, faction, load, random);
	}

	/** Stock a colony can spare: what it holds above its keep fraction of its own cap. */
	public static float spare(MarketAPI donor, String commodityId) {
		float keep = ThreatReserves.cap(donor, commodityId) * ThreatIncConfig.donorKeepFraction();
		return Math.max(0f, ThreatReserves.stock(donor.getId(), commodityId) - keep);
	}

	/**
	 * EQUALISATION: what a donor may send a base of one commodity - never
	 * more than half the difference between their stocks, so the donor is
	 * never left poorer than the base it just supplied. When every colony is
	 * a staging base (a core faction facing one hive), this is what keeps
	 * convoys flowing from the rich worlds to the poor ones instead of the
	 * two shipping the same goods past each other (seen in-game 2026-09-04).
	 */
	public static float sendable(MarketAPI donor, MarketAPI base, String commodityId) {
		float donorStock = ThreatReserves.stock(donor.getId(), commodityId);
		float baseStock = ThreatReserves.stock(base.getId(), commodityId);
		float gap = (donorStock - baseStock) / 2f;
		if (gap <= 0f) return 0f;
		return Math.min(gap, Math.min(spare(donor, commodityId), capacityFor(commodityId)));
	}

	protected static MarketAPI pickDonor(List<MarketAPI> markets, MarketAPI base, String commodityId) {
		MarketAPI best = null;
		float bestSend = 0f;
		float range = ThreatIncConfig.convoyRangeLY();
		for (MarketAPI donor : markets) {
			if (donor == base || donor.getStarSystem() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					base.getStarSystem().getLocation()) > range) continue;
			float s = sendable(donor, base, commodityId);
			if (s > bestSend) {
				bestSend = s;
				best = donor;
			}
		}
		// not worth a sailing
		if (best != null && bestSend < capacityFor(commodityId)
				* ThreatIncConfig.convoyMinLoadFraction()) return null;
		return best;
	}

	/**
	 * Builds the convoy fleet at the donor, loads the cargo aboard (clamped to
	 * what the hulls can actually carry), draws exactly that from the donor's
	 * reserve, and sends it to the base.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random) {
		if (donor == null || base == null || faction == null) return null;
		StarSystemAPI system = donor.getStarSystem();
		SectorEntityToken from = donor.getPrimaryEntity();
		SectorEntityToken to = base.getPrimaryEntity();
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
		FleetParamsV3 params = new FleetParamsV3(donor, donor.getLocationInHyperspace(),
				faction.getId(), null, FleetTypes.SUPPLY_FLEET,
				escort, freighterPts, tankerPts, transportPts, 0f, 0f, 0f);
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName("Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		// load what fits; draw only what was loaded
		CargoAPI cargo = fleet.getCargo();
		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = donor.getId();
		c.toMarketId = base.getId();
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();

		int m = (int) Math.min(marines, cargo.getFreeCrewSpace());
		if (m > 0) {
			cargo.addMarines(m);
			c.marines = ThreatReserves.draw(donor.getId(), Commodities.MARINES, m);
		}
		c.armaments = loadCommodity(cargo, donor, Commodities.HAND_WEAPONS, load[1]);
		c.fuel = loadCommodity(cargo, donor, Commodities.FUEL, load[2]);
		c.supplies = loadCommodity(cargo, donor, Commodities.SUPPLIES, load[3]);
		if (c.marines <= 0f && c.armaments <= 0f && c.fuel <= 0f && c.supplies <= 0f) {
			Misc.fadeAndExpire(fleet);
			return null;
		}

		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f,
				"carrying war materiel to " + base.getName());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, from, 1000f,
				"returning to " + donor.getName());
		all().add(c);

		ThreatIncConfig.log("Convoy dispatched: " + faction.getId() + " " + donor.getName()
				+ " -> " + base.getName() + " (" + (int) c.marines + " marines, "
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

	protected static float loadCommodity(CargoAPI cargo, MarketAPI donor, String commodityId,
			float wanted) {
		if (wanted <= 0f) return 0f;
		float fits = Math.max(0f, Math.min(wanted, cargo.getSpaceLeft()));
		int units = (int) fits;
		if (units <= 0) return 0f;
		float taken = ThreatReserves.draw(donor.getId(), commodityId, units);
		if (taken > 0f) cargo.addCommodity(commodityId, (int) taken);
		return (int) taken;
	}

	// ------------------------------------------------------------------
	// resolution (fast poll)
	// ------------------------------------------------------------------

	/** Distance from the destination entity at which a convoy counts as arrived. */
	public static final float ARRIVAL_RANGE = 350f;

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
			MarketAPI base = Global.getSector().getEconomy().getMarket(c.toMarketId);
			if (base == null || base.getPrimaryEntity() == null
					|| !c.factionId.equals(base.getFactionId())) {
				// the destination fell or changed hands: turn around, stock stays aboard
				// until the fleet despawns home, where it is returned to the donor
				returnHome(c);
				continue;
			}
			SectorEntityToken to = base.getPrimaryEntity();
			if (fleet.getContainingLocation() == to.getContainingLocation()
					&& Misc.getDistance(fleet, to) <= ARRIVAL_RANGE) {
				arrived(c, base);
			}
		}
	}

	protected static void arrived(Convoy c, MarketAPI base) {
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
		ThreatReserves.deposit(base.getId(), Commodities.MARINES, marines);
		ThreatReserves.deposit(base.getId(), Commodities.HAND_WEAPONS, armaments);
		ThreatReserves.deposit(base.getId(), Commodities.FUEL, fuel);
		ThreatReserves.deposit(base.getId(), Commodities.SUPPLIES, supplies);
		all().remove(c);

		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		SectorEntityToken home = donor != null ? donor.getPrimaryEntity() : base.getPrimaryEntity();
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 1000f,
				"returning to " + (donor != null ? donor.getName() : base.getName()));
		ThreatIncConfig.log("Convoy arrived: " + c.factionId + " at " + base.getName() + " ("
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, "
				+ supplies + " supplies)");
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
		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		if (donor != null && donor.getPrimaryEntity() != null
				&& c.factionId.equals(donor.getFactionId())) {
			ThreatReturns.sendHome(fleet, c.factionId, donor.getId());
		} else if (fleet != null) {
			Misc.fadeAndExpire(fleet);
		}
		ThreatIncConfig.log("Convoy recalled: " + c.factionId + " " + c.fromName()
				+ " -> " + c.toName());
	}
}
