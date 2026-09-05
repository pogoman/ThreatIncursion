package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;

/**
 * CAPACITY LEDGER (docs/player-aid.md section 2): what one of the player's
 * colonies can have at sea at once, in fleet points.
 *
 * <p>Capacity is the colony's vanilla fleet-size stat - the "Fleet size %"
 * line on the colony screen (colony size, doctrine, ship-hull shortage,
 * stability) - times aidBaseFP. Every fleet the player launches from the
 * colony through the war board costs points: the escort's combat FP plus the
 * freighter, tanker and transport hulls its cargo needs. Points come back when
 * the fleet is home ({@link ThreatReturns#settle}); a fleet that is lost frees
 * them only after aidRebuildDays. A purge expedition is a fleet group rather
 * than one fleet: its points are held until the group ends.
 *
 * <p>Money never buys capability: a colony that cannot field the hulls cannot
 * send the cargo, whatever the wallet holds. NPC factions are not on the
 * ledger - their navies are their own, sized by vanilla.
 */
public class ThreatAidCapacity {

	public static final String KEY = "threatinc_aidCapacity";

	/** Support hulls a task force sails with, as a multiple of its combat points (see ThreatFleetOrders.buildTaskForce). */
	public static final float TASK_FORCE_HULL_MULT = 1.2f;

	/** One fleet (or fleet group) holding points against a colony. */
	public static class Commitment {
		public String marketId;
		public float fp;
		public CampaignFleetAPI fleet;
		/** A FleetGroupIntel (purge expedition) when the commitment is a group, else null. */
		public Object group;
		public String label;
		/** When the fleet was seen dead; 0 while it lives. */
		public long lostTimestamp;
	}

	@SuppressWarnings("unchecked")
	public static List<Commitment> all() {
		Object val = Global.getSector().getPersistentData().get(KEY);
		if (!(val instanceof List)) {
			val = new ArrayList<Commitment>();
			Global.getSector().getPersistentData().put(KEY, val);
		}
		return (List<Commitment>) val;
	}

	public static boolean enabled() {
		return ThreatWarState.enabled() && ThreatIncConfig.aidEnabled();
	}

	// ------------------------------------------------------------------
	// capacity
	// ------------------------------------------------------------------

	/** Fleet points the colony can have at sea at once; 0 without a military structure. */
	public static float capacityFP(MarketAPI market) {
		if (market == null || !market.isPlayerOwned()) return 0f;
		if (!IncursionManager.hasMilitary(market)) return 0f;
		return ThreatIncConfig.aidBaseFP() * ThreatColonyManager.fleetSizeMult(market);
	}

	/** Points held by fleets out and by losses still rebuilding. */
	public static float committedFP(String marketId) {
		float sum = 0f;
		for (Commitment c : all()) {
			if (marketId.equals(c.marketId)) sum += c.fp;
		}
		return sum;
	}

	public static float freeFP(MarketAPI market) {
		if (market == null) return 0f;
		if (!enabled()) return Float.MAX_VALUE / 4f;
		return capacityFP(market) - committedFP(market.getId());
	}

	public static Commitment commit(MarketAPI market, float fp, CampaignFleetAPI fleet, String label) {
		if (market == null || fp <= 0f || !enabled()) return null;
		Commitment c = new Commitment();
		c.marketId = market.getId();
		c.fp = fp;
		c.fleet = fleet;
		c.label = label;
		all().add(c);
		ThreatIncConfig.log("Capacity: " + market.getName() + " commits " + (int) fp + " FP to "
				+ label + " (" + (int) committedFP(market.getId()) + "/" + (int) capacityFP(market) + ")");
		return c;
	}

	public static Commitment commitGroup(MarketAPI market, float fp, Object group, String label) {
		Commitment c = commit(market, fp, null, label);
		if (c != null) c.group = group;
		return c;
	}

	/** The fleet is home: its points are the colony's again. */
	public static void release(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		for (Commitment c : new ArrayList<Commitment>(all())) {
			if (c.fleet == fleet) {
				all().remove(c);
				ThreatIncConfig.log("Capacity: " + (int) c.fp + " FP back from " + c.label);
			}
		}
	}

	/** Fast poll: losses start the rebuild clock, rebuilt and ended groups drop off. */
	public static void poll() {
		if (all().isEmpty()) return;
		long now = Global.getSector().getClock().getTimestamp();
		float rebuild = ThreatIncConfig.aidRebuildDays();
		for (Commitment c : new ArrayList<Commitment>(all())) {
			if (c.group != null) {
				if (!(c.group instanceof FleetGroupIntel)
						|| ((FleetGroupIntel) c.group).isEnded()) {
					all().remove(c);
					ThreatIncConfig.log("Capacity: " + (int) c.fp + " FP back from " + c.label
							+ " (expedition over)");
				}
				continue;
			}
			if (c.fleet == null) {
				all().remove(c);
				continue;
			}
			boolean dead = !c.fleet.isAlive() || c.fleet.isExpired();
			if (dead && c.lostTimestamp == 0L) {
				c.lostTimestamp = now;
				ThreatIncConfig.log("Capacity: " + c.label + " lost - " + (int) c.fp
						+ " FP rebuilding for " + (int) rebuild + " days");
				continue;
			}
			if (c.lostTimestamp != 0L
					&& Global.getSector().getClock().getElapsedDaysSince(c.lostTimestamp) >= rebuild) {
				all().remove(c);
				ThreatIncConfig.log("Capacity: " + (int) c.fp + " FP rebuilt after " + c.label);
			}
		}
	}

	// ------------------------------------------------------------------
	// what a fleet costs in points
	// ------------------------------------------------------------------

	/** Points a task force of this combat strength holds: the warships plus their support hulls. */
	public static float taskForcePoints(float combatFP) {
		return combatFP * TASK_FORCE_HULL_MULT;
	}

	/**
	 * Points a convoy holds for a load in ThreatReserves.COMMODITIES order
	 * (marines, armaments, fuel, supplies): the escort by cargo value plus
	 * the hulls - the same ratios ThreatConvoys.dispatch builds with.
	 */
	public static float convoyPoints(float[] load) {
		float marines = load[0];
		float cargoUnits = load[1] + load[2] + load[3];
		if (marines <= 0f && cargoUnits <= 0f) return 0f;
		float escort = ThreatIncConfig.convoyEscortFP()
				+ ThreatConvoys.cargoValue(marines, load[1], load[2], load[3]) / 1000f
						* ThreatIncConfig.convoyEscortPerThousand();
		float freighterPts = Math.max(10f, cargoUnits / 60f);
		float tankerPts = load[2] > 0f ? Math.max(5f, load[2] / 100f) : 0f;
		float transportPts = marines > 0f ? Math.max(10f, marines / 40f) : 0f;
		return escort + freighterPts + tankerPts + transportPts;
	}

	/** Points a purge expedition of these vanilla-scale fleet sizes holds. */
	public static float expeditionPoints(List<Integer> fleetSizes) {
		float sum = 0f;
		for (Integer size : fleetSizes) sum += size * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return sum;
	}

	/**
	 * The largest fraction of the load whose convoy fits in the free points,
	 * as whole items; all zeros when not even a picket-sized run fits.
	 */
	public static float[] fitLoad(float free, float[] load) {
		float[] result = new float[load.length];
		if (convoyPoints(load) <= free) {
			for (int i = 0; i < load.length; i++) result[i] = (float) Math.floor(load[i]);
			return result;
		}
		float lo = 0f;
		float hi = 1f;
		for (int i = 0; i < 24; i++) {
			float mid = (lo + hi) / 2f;
			for (int j = 0; j < load.length; j++) result[j] = load[j] * mid;
			if (convoyPoints(result) <= free) lo = mid;
			else hi = mid;
		}
		float total = 0f;
		for (int j = 0; j < load.length; j++) {
			result[j] = (float) Math.floor(load[j] * lo);
			total += result[j];
		}
		if (total < 1f) {
			for (int j = 0; j < load.length; j++) result[j] = 0f;
		}
		return result;
	}

	/**
	 * A player expedition trimmed to what its base can field: fleets are
	 * dropped (never below two), then each remaining fleet shrunk (never
	 * below responseMinDifficulty). What is left may still exceed the free
	 * points - the minimum viable expedition sails and the colony is
	 * over-extended until it returns (docs/player-aid.md section 7, item 5).
	 */
	public static List<Integer> fitExpedition(MarketAPI base, FactionAPI faction,
			List<Integer> fleetSizes) {
		if (faction == null || !faction.isPlayerFaction() || !enabled()) return fleetSizes;
		List<Integer> sizes = new ArrayList<Integer>(fleetSizes);
		float free = freeFP(base);
		int minDiff = ThreatIncConfig.responseMinDifficulty();
		while (sizes.size() > 2 && expeditionPoints(sizes) > free) {
			sizes.remove(sizes.size() - 1);
		}
		boolean changed = true;
		while (changed && expeditionPoints(sizes) > free) {
			changed = false;
			for (int i = sizes.size() - 1; i >= 0; i--) {
				if (sizes.get(i) > minDiff) {
					sizes.set(i, sizes.get(i) - 1);
					changed = true;
					if (expeditionPoints(sizes) <= free) break;
				}
			}
		}
		if (!sizes.equals(fleetSizes)) {
			ThreatIncConfig.log("Capacity: expedition at " + base.getName() + " trimmed from "
					+ fleetSizes + " to " + sizes + " (" + (int) free + " FP free)"
					+ (expeditionPoints(sizes) > free ? " - still over, sailing over-extended" : ""));
		}
		return sizes;
	}

	/** "Fleet capacity 169 FP (fleet size 169%): 120 out, 49 free." for tooltips. */
	public static String describe(MarketAPI market) {
		if (market == null) return "";
		float cap = capacityFP(market);
		if (cap <= 0f) return "No fleet capacity: a Patrol HQ, Military Base or High Command is needed.";
		float out = committedFP(market.getId());
		return "Fleet capacity " + (int) cap + " FP (fleet size "
				+ Math.round(ThreatColonyManager.fleetSizeMult(market) * 100f) + "%): "
				+ (int) out + " out, " + (int) Math.max(0f, cap - out) + " free.";
	}
}
