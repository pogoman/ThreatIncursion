package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
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
	public static final String MEM_FUEL = "$threatinc_drawFuel";
	public static final String MEM_SUPPLIES = "$threatinc_drawSupplies";
	public static final String MEM_FP0 = "$threatinc_fpAtLaunch";

	/** Distance from the home entity at which a returning fleet counts as arrived. */
	public static final float ARRIVAL_RANGE = 350f;

	/** One fleet on its way home. */
	public static class Return {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String homeMarketId;
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

	/** Surviving strength, 0..1: fleet points now over fleet points at launch. */
	public static float health(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		float fp0 = fleet.getMemoryWithoutUpdate().getFloat(MEM_FP0);
		if (fp0 <= 0f) return 1f;
		return Math.max(0f, Math.min(1f, fleet.getFleetPoints() / fp0));
	}

	/**
	 * Sends the fleet home on a tracked leg. Anything it carries and the
	 * provisions it drew are settled when it arrives. Falls back to a plain
	 * despawn when the home market is gone.
	 */
	public static boolean sendHome(CampaignFleetAPI fleet, String factionId, String homeMarketId) {
		if (fleet == null || !fleet.isAlive()) return false;
		MarketAPI home = homeMarketId != null
				? Global.getSector().getEconomy().getMarket(homeMarketId) : null;
		if (home == null || home.getPrimaryEntity() == null) {
			fleet.clearAssignments();
			Misc.fadeAndExpire(fleet);
			return false;
		}
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, home.getPrimaryEntity(), 1000f,
				"returning to " + home.getName());
		for (Return r : all()) {
			if (r.fleet == fleet) return true; // already tracked
		}
		Return r = new Return();
		r.fleet = fleet;
		r.factionId = factionId;
		r.homeMarketId = homeMarketId;
		r.departedTimestamp = Global.getSector().getClock().getTimestamp();
		all().add(r);
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
			MarketAPI home = Global.getSector().getEconomy().getMarket(r.homeMarketId);
			if (home == null || home.getPrimaryEntity() == null) {
				all().remove(r);
				Misc.fadeAndExpire(fleet);
				continue;
			}
			float days = Global.getSector().getClock().getElapsedDaysSince(r.departedTimestamp);
			if (days > ThreatIncConfig.convoyTimeoutDays()) {
				all().remove(r);
				Misc.fadeAndExpire(fleet);
				ThreatIncConfig.log("Return timed out: " + r.factionId + " fleet bound for "
						+ home.getName());
				continue;
			}
			SectorEntityToken to = home.getPrimaryEntity();
			if (fleet.getContainingLocation() == to.getContainingLocation()
					&& Misc.getDistance(fleet, to) <= ARRIVAL_RANGE) {
				settle(fleet, home);
				all().remove(r);
				Misc.fadeAndExpire(fleet);
			}
		}
	}

	/**
	 * The fleet is home: deposit what is aboard, refund the provisions in
	 * proportion to what survived. Returns [marines, armaments, fuel, supplies]
	 * credited.
	 */
	public static float[] settle(CampaignFleetAPI fleet, MarketAPI home) {
		// home: whatever the capacity ledger held for it is the colony's again
		ThreatAidCapacity.release(fleet);
		CargoAPI cargo = fleet.getCargo();
		float marines = cargo.getMarines();
		float armaments = cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		float fuel = cargo.getCommodityQuantity(Commodities.FUEL);
		float supplies = cargo.getCommodityQuantity(Commodities.SUPPLIES);
		// only the layer's own cargo counts: a factory fleet carries some
		// fuel and supplies of its own, which are the refund's business below
		boolean carriedCargo = fleet.getMemoryWithoutUpdate().getBoolean(ThreatConvoys.CONVOY_FLAG)
				|| marines > 0f || armaments > 0f;
		if (!carriedCargo) {
			fuel = 0f;
			supplies = 0f;
		}
		float mult = ThreatIncConfig.returnRefundMult() * health(fleet);
		float drawFuel = fleet.getMemoryWithoutUpdate().getFloat(MEM_FUEL) * mult;
		float drawSupplies = fleet.getMemoryWithoutUpdate().getFloat(MEM_SUPPLIES) * mult;

		ThreatReserves.deposit(home.getId(), Commodities.MARINES, marines);
		ThreatReserves.deposit(home.getId(), Commodities.HAND_WEAPONS, armaments);
		ThreatReserves.deposit(home.getId(), Commodities.FUEL, fuel + drawFuel);
		ThreatReserves.deposit(home.getId(), Commodities.SUPPLIES, supplies + drawSupplies);
		cargo.clear();
		ThreatIncConfig.log("Return settled at " + home.getName() + ": " + (int) marines
				+ " marines, " + (int) armaments + " armaments, " + (int) (fuel + drawFuel)
				+ " fuel, " + (int) (supplies + drawSupplies) + " supplies (strength "
				+ (int) (health(fleet) * 100f) + "%)");
		return new float[] {marines, armaments, fuel + drawFuel, supplies + drawSupplies};
	}
}
