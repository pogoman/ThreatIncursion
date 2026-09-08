package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * RETURNS - what comes back when a fleet comes home (docs/strategy-layer.md).
 *
 * <p>Every fleet the strategy layer sends out (task forces, guard and
 * intercept sorties, convoys) is provisioned from a base's reserve. When it
 * is recalled - or its order simply runs out - it is sent home on a tracked
 * leg instead of despawning, and on arrival the base gets back:
 *
 * <ul>
 * <li>everything still physically aboard: marines, heavy armaments, fuel and
 * supplies in the cargo - so losses in transit or in battle are simply what
 * did not come back;</li>
 * <li>a refund of the abstract provisions drawn at launch (fuel, supplies),
 * scaled by the fleet's surviving strength (fleet points now over fleet
 * points at launch) and by returnRefundMult - the sortie consumed the rest.</li>
 * </ul>
 *
 * A fleet destroyed on the way home returns nothing. Provisioning data rides
 * the fleet's memory so any fleet the layer built can be settled here.
 */
public class ThreatReturns {

	public static final String KEY_RETURNS = "threatinc_fleetReturns";

	/** Fleet memory: the base that provisioned it, and what it drew. */
	public static final String MEM_HOME = "$threatinc_homeMarket";
	/** Veterancy level of marines a pickup convoy is carrying home, so the base they land at inherits it. */
	public static final String MEM_MARINE_LEVEL = "$threatinc_marineLevel";
	public static final String MEM_FUEL = "$threatinc_drawFuel";
	public static final String MEM_SUPPLIES = "$threatinc_drawSupplies";
	public static final String MEM_FP0 = "$threatinc_fpAtLaunch";
	/** Fleet memory: the last settle kept the fleet on station (ThreatAidCapacity.release). */
	public static final String MEM_KEPT = "$threatinc_keptOnStation";

	/**
	 * Distance past the two hulls' radii at which a fleet counts as arrived
	 * at an entity ({@link #arrived}). Vanilla ends a GO_TO_LOCATION the
	 * moment the fleet reaches the entity's edge, so the range must reach
	 * that far out for the largest planet, or the fleet stops - idle, its leg
	 * done - just outside it while the planet orbits away (2026-09-06: a
	 * detachment sat 46 days in Sun Wukong's own system, no assignment, the
	 * planet 7000 units on).
	 */
	public static final float ARRIVAL_RANGE = 350f;

	/** Whether the fleet is at the entity: same location, within ARRIVAL_RANGE past both radii. */
	public static boolean arrived(CampaignFleetAPI fleet, SectorEntityToken to) {
		if (fleet == null || to == null) return false;
		if (fleet.getContainingLocation() != to.getContainingLocation()) return false;
		return Misc.getDistance(fleet, to) <= ARRIVAL_RANGE + to.getRadius() + fleet.getRadius();
	}

	/**
	 * Keeps a fleet on its leg to an entity. True once it is there
	 * ({@link #arrived}). Otherwise, when its current assignment is no longer
	 * that leg - vanilla ended it short of the range, or something cleared
	 * the queue - the leg is issued again, so the fleet chases a planet that
	 * has moved on instead of drifting where it stopped.
	 */
	public static boolean onLeg(CampaignFleetAPI fleet, SectorEntityToken to, String text) {
		if (arrived(fleet, to)) return true;
		if (fleet == null || to == null) return false;
		FleetAssignmentDataAPI a = fleet.getCurrentAssignment();
		if (a == null || a.getTarget() != to) {
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f, text);
		}
		return false;
	}

	/**
	 * Days until a fleet on a tracked leg is home, at the board's rough
	 * hyperspace pace (ThreatWarBoard.EST_LY_PER_DAY) plus a day for the
	 * in-system leg; a day once it is in the home's own system. 0 with no home.
	 */
	public static float etaDays(Return r) {
		if (r == null || r.fleet == null) return 0f;
		ThreatBases.Base home = ThreatBases.of(r.homeMarketId);
		SectorEntityToken to = home != null ? home.entity() : null;
		if (to == null) return 0f;
		if (r.fleet.getContainingLocation() == to.getContainingLocation()) return 1f;
		float ly = Misc.getDistanceLY(r.fleet.getLocationInHyperspace(), to.getLocationInHyperspace());
		return (float) Math.ceil(ly / ThreatWarBoard.EST_LY_PER_DAY) + 1f;
	}

	/** One fleet on its way home. */
	public static class Return {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String homeMarketId;
		/**
		 * The system the fleet is returning FROM - where it stood when it was
		 * sent home. Lets the board turn a returning fleet back around to hold
		 * that hive's door (ThreatFactionView Intercept), even once it is out in
		 * hyperspace, instead of only while it still sits in the system. Null if
		 * it was already in hyperspace when recalled - then there is no origin to
		 * go back to.
		 */
		public String fromSystemId;
		public long departedTimestamp;
	}

	@SuppressWarnings("unchecked")
	public static List<Return> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_RETURNS);
		if (!(val instanceof List)) {
			val = new ArrayList<Return>();
			Global.getSector().getPersistentData().put(KEY_RETURNS, val);
		}
		return (List<Return>) val;
	}

	/** Records on the fleet what it was provisioned with, for the refund on return. */
	public static void provision(CampaignFleetAPI fleet, String baseMarketId, float fuel,
			float supplies) {
		if (fleet == null) return;
		fleet.getMemoryWithoutUpdate().set(MEM_HOME, baseMarketId);
		fleet.getMemoryWithoutUpdate().set(MEM_FUEL, fuel);
		fleet.getMemoryWithoutUpdate().set(MEM_SUPPLIES, supplies);
		fleet.getMemoryWithoutUpdate().set(MEM_FP0, fleet.getFleetPoints());
	}

	public static String homeOf(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		return fleet.getMemoryWithoutUpdate().getString(MEM_HOME);
	}

	/** Restamps the strength baseline {@link #health} reads at the fleet's points now. */
	public static void rebaseline(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		fleet.getMemoryWithoutUpdate().set(MEM_FP0, fleet.getFleetPoints());
	}

	/** Surviving strength, 0..1: fleet points now over fleet points at launch. */
	public static float health(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		float fp0 = fleet.getMemoryWithoutUpdate().getFloat(MEM_FP0);
		if (fp0 <= 0f) return 1f;
		return Math.max(0f, Math.min(1f, fleet.getFleetPoints() / fp0));
	}

	/**
	 * Sends the fleet home on a tracked leg. Anything it carries and the
	 * provisions it drew are settled when it arrives. The home is a reserve
	 * key, so it may be a colony OR an outpost ({@link ThreatBases}); a front
	 * run out of the outpost in the hive's own system comes back to it. Falls
	 * back to a plain despawn when the home is gone.
	 */
	public static boolean sendHome(CampaignFleetAPI fleet, String factionId, String homeMarketId) {
		if (fleet == null || !fleet.isAlive()) return false;
		ThreatBases.Base home = ThreatBases.of(homeMarketId);
		SectorEntityToken to = home != null ? home.entity() : null;
		if (to == null) {
			fleet.clearAssignments();
			Misc.fadeAndExpire(fleet);
			return false;
		}
		fleet.clearAssignments();
		// its own road home: not to be pulled off it by a raid's response
		// scripts or a system's fight for its objectives, which vanilla's
		// despawning return is immune to and this tracked one was not
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
		fleet.getMemoryWithoutUpdate().unset(MEM_KEPT);
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f,
				"returning to " + home.name());
		Return r = null;
		for (Return t : all()) {
			if (t.fleet == fleet) r = t; // already tracked: the record follows the new leg
		}
		if (r == null) {
			r = new Return();
			r.fleet = fleet;
			all().add(r);
		}
		r.factionId = factionId;
		r.homeMarketId = homeMarketId;
		// where it is standing as it turns for home - the hive it just left. The
		// fleet has not moved yet (only its assignment changed), so its current
		// system is the origin. Null in hyperspace: nothing to turn back to.
		r.fromSystemId = fleet.getStarSystem() != null ? fleet.getStarSystem().getId() : null;
		r.departedTimestamp = Global.getSector().getClock().getTimestamp();
		return true;
	}

	public static void poll() {
		if (all().isEmpty()) return;
		for (Return r : new ArrayList<Return>(all())) {
			CampaignFleetAPI fleet = r.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
				all().remove(r);
				ThreatIncConfig.log("Return lost: " + r.factionId + " fleet bound for "
						+ r.homeMarketId);
				continue;
			}
			ThreatBases.Base home = ThreatBases.of(r.homeMarketId);
			SectorEntityToken to = home != null ? home.entity() : null;
			if (to == null) {
				all().remove(r);
				Misc.fadeAndExpire(fleet);
				continue;
			}
			float days = Global.getSector().getClock().getElapsedDaysSince(r.departedTimestamp);
			if (days > ThreatIncConfig.convoyTimeoutDays()) {
				all().remove(r);
				Misc.fadeAndExpire(fleet);
				ThreatIncConfig.log("Return timed out: " + r.factionId + " fleet bound for "
						+ home.name());
				continue;
			}
			if (onLeg(fleet, to, "returning to " + home.name())) {
				settle(fleet, home);
				all().remove(r);
				// ships restationed over the host stay (ThreatAidCapacity.release)
				if (!kept(fleet)) Misc.fadeAndExpire(fleet);
			}
		}
	}

	/**
	 * The fleet is home: deposit what is aboard, refund the provisions in
	 * proportion to what survived, then hand its capacity-ledger entries
	 * back - which may keep the fleet on station over the host
	 * ({@link #kept}). Returns [marines, armaments, fuel, supplies] credited.
	 */
	public static float[] settle(CampaignFleetAPI fleet, MarketAPI home) {
		return settle(fleet, ThreatBases.of(home));
	}

	/** Whether the last settle kept the fleet on station: the caller must not expire it. */
	public static boolean kept(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(MEM_KEPT);
	}

	/** As above, for a home that may be a colony or an outpost. */
	public static float[] settle(CampaignFleetAPI fleet, ThreatBases.Base home) {
		if (fleet == null || home == null) return new float[] {0f, 0f, 0f, 0f};
		CargoAPI cargo = fleet.getCargo();
		float marines = cargo.getMarines();
		float armaments = cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		float fuel = cargo.getCommodityQuantity(Commodities.FUEL);
		float supplies = cargo.getCommodityQuantity(Commodities.SUPPLIES);
		// only the layer's own cargo counts: a factory fleet carries some
		// fuel and supplies of its own, which are the refund's business below
		// (an expedition or task force bringing troops home still only banks the
		// troops: the fuel and supplies in its hold are the factory's, not drawn)
		boolean carriedCargo = fleet.getMemoryWithoutUpdate().getBoolean(ThreatConvoys.CONVOY_FLAG);
		if (!carriedCargo) {
			fuel = 0f;
			supplies = 0f;
		}
		float mult = ThreatIncConfig.returnRefundMult() * health(fleet);
		float drawFuel = fleet.getMemoryWithoutUpdate().getFloat(MEM_FUEL) * mult;
		float drawSupplies = fleet.getMemoryWithoutUpdate().getFloat(MEM_SUPPLIES) * mult;

		ThreatBases.deposit(home, Commodities.MARINES, marines);
		// veterans lifted off a front season the garrison they land in, and are
		// posted at once rather than walking the arming ramp (2026-09-08)
		float marineLevel = fleet.getMemoryWithoutUpdate().getFloat(MEM_MARINE_LEVEL);
		if (marineLevel > 0f && marines > 0f) {
			MarketAPI seasoned = Global.getSector().getEconomy().getMarket(home.id());
			if (seasoned != null) ThreatReserves.armReturning(seasoned, marines, marineLevel);
			fleet.getMemoryWithoutUpdate().unset(MEM_MARINE_LEVEL);
		}
		ThreatBases.deposit(home, Commodities.HAND_WEAPONS, armaments);
		ThreatBases.deposit(home, Commodities.FUEL, fuel + drawFuel);
		ThreatBases.deposit(home, Commodities.SUPPLIES, supplies + drawSupplies);
		cargo.clear();
		ThreatIncConfig.log("Return settled at " + home.name() + ": " + (int) marines
				+ " marines, " + (int) armaments + " armaments, " + (int) (fuel + drawFuel)
				+ " fuel, " + (int) (supplies + drawSupplies) + " supplies (strength "
				+ (int) (health(fleet) * 100f) + "%)");
		// home: whatever the capacity ledger held for it is the colony's again -
		// or, ships folded in from staging here, the fleet stays on station
		// (after the refund: restationing re-baselines the provisions to nil)
		MarketAPI host = Global.getSector().getEconomy().getMarket(home.id());
		boolean kept = ThreatAidCapacity.release(fleet, host);
		fleet.getMemoryWithoutUpdate().set(MEM_KEPT, kept);
		return new float[] {marines, armaments, fuel + drawFuel, supplies + drawSupplies};
	}
}
