package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * THE SWARM'S DEFEND STATIONS (2026-09-07, the user: "after a siege expedition
 * has dropped off its marines it should hold the space above the sieged
 * planet"): a strike fleet that lands or reinforces a Threat ground front
 * leaves its expedition and stays over the world - ORBIT_AGGRESSIVE at the
 * planet, holding the orbit against whatever contests it - until the front
 * is gone (the world taken, or the front destroyed) or the batteries have
 * ground it below {@code defendMinStrength} of its arrival strength, then
 * returns to its staging colony and despawns. It does NOT bombard while the
 * front holds: only while the front cannot hold and orbit can still push the
 * fortifications ({@link ThreatGroundFronts#defendBombards}) does it deliver
 * siege slices and pay the batteries for them - and once orbit has nothing
 * left to suppress it feeds the front its own hulls instead
 * ({@link ThreatGroundFronts#defendFabricates}), which is also what stops it
 * standing down on strength. The rest of the expedition
 * sweeps on. A faction's landing fleet does the same as a real Defend order
 * ({@link ThreatFleetOrders#adoptLandingDefend}), so the board shows it; the
 * swarm has no orders layer, so its fleets live here. Persistent list
 * threatinc_swarmDefend. Knobs: landingDefendEnabled, defendMinStrength.
 */
public class ThreatSwarmDefend {

	public static final String KEY = "threatinc_swarmDefend";

	public static class Entry {
		public CampaignFleetAPI fleet;
		public String marketId;
		public String marketName;
		public String factionId;
		public String homeMarketId;
		public long since;
		/** Fleet points on arrival: the station ends when the batteries have ground it below defendMinStrength of this. */
		public float fp0;
	}

	@SuppressWarnings("unchecked")
	public static List<Entry> all() {
		Object val = Global.getSector().getPersistentData().get(KEY);
		if (!(val instanceof List)) {
			val = new ArrayList<Entry>();
			Global.getSector().getPersistentData().put(KEY, val);
		}
		return (List<Entry>) val;
	}

	public static Entry of(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		for (Entry e : all()) {
			if (e.fleet == fleet) return e;
		}
		return null;
	}

	/** Puts the fleet over the world on Defend; the caller has already taken it out of its expedition. */
	public static Entry attach(CampaignFleetAPI fleet, MarketAPI world, String factionId,
			MarketAPI home) {
		if (fleet == null || world == null || world.getPrimaryEntity() == null) return null;
		if (!fleet.isAlive() || fleet.isExpired()) return null;
		if (!ThreatIncConfig.landingDefendEnabled()) return null;
		release(fleet);
		ThreatPurgeFGI.cutLoose(fleet);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, world.getPrimaryEntity(),
				ThreatFleetOrders.NO_TERM_DAYS, "defending the orbit of " + world.getName());
		Entry e = new Entry();
		e.fleet = fleet;
		e.marketId = world.getId();
		e.marketName = world.getName();
		e.factionId = factionId != null ? factionId : Factions.THREAT;
		e.homeMarketId = home != null ? home.getId() : null;
		e.since = Global.getSector().getClock().getTimestamp();
		e.fp0 = fleet.getFleetPoints();
		all().add(e);
		ThreatIncConfig.log("Swarm defend: " + fleet.getName() + " stays over " + world.getName());
		return e;
	}

	/** Forgets the fleet without moving it (something else has taken it). */
	public static boolean release(CampaignFleetAPI fleet) {
		Entry e = of(fleet);
		if (e == null) return false;
		all().remove(e);
		return true;
	}

	/**
	 * Keeps a defending fleet on its station: a battle or another script can
	 * empty its assignment queue, and an idle fleet drifts off the planet.
	 * Re-issued whenever the current assignment is not the orbit over this
	 * world. Shared with a faction's indefinite Defend order
	 * ({@link ThreatGroundFronts#tickSupport}).
	 */
	public static void holdOrbit(CampaignFleetAPI fleet, MarketAPI world, String text) {
		if (fleet == null || world == null || world.getPrimaryEntity() == null) return;
		FleetAssignmentDataAPI a = fleet.getCurrentAssignment();
		if (a != null && a.getTarget() == world.getPrimaryEntity()) return;
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, world.getPrimaryEntity(),
				ThreatFleetOrders.NO_TERM_DAYS, text);
	}

	/** Per frame (IncursionManager.advance): a station fleet beyond the hold range, out of battle, is recalled to its orbit ({@link ThreatFleetOrders#leash}). */
	public static void enforceLeash() {
		List<Entry> all = all();
		if (all.isEmpty()) return;
		for (Entry e : all) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired()) continue;
			MarketAPI market = Global.getSector().getEconomy().getMarket(e.marketId);
			if (market == null) continue;
			ThreatFleetOrders.leash(e.fleet, market, ThreatFleetOrders.NO_TERM_DAYS,
					"defending the orbit of " + market.getName(), null, "Swarm defend");
		}
	}

	/** Driven from {@link ThreatGroundFronts#poll}: holds while the front stands, covers it while it cannot hold, home when it is gone. */
	public static void tick(float elapsedDays) {
		List<Entry> all = all();
		if (all.isEmpty()) return;
		for (Entry e : new ArrayList<Entry>(all)) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired()) {
				all.remove(e);
				continue;
			}
			MarketAPI market = Global.getSector().getEconomy().getMarket(e.marketId);
			boolean done = market == null || !market.isInEconomy() || market.getPrimaryEntity() == null
					|| ThreatGroundFronts.getFront(e.marketId) == null
					|| e.factionId.equals(market.getFactionId());
			// ground down by the batteries: nothing left to hold the orbit with.
			// A fleet feeding the front its own hulls is exempt (2026-09-08):
			// it never gives up because of what fabrication costs it, and
			// leaves only when the front is gone.
			if (!done && e.fp0 > 0f && e.fleet.getFleetPoints()
					< e.fp0 * ThreatIncConfig.defendMinStrength()
					&& !ThreatGroundFronts.defendCommitted(e.fleet, e.factionId, e.marketId)) {
				done = true;
			}
			if (done) {
				all.remove(e);
				sendHome(e);
				continue;
			}
			holdOrbit(e.fleet, market, "defending the orbit of " + market.getName());
			// a station back from its repairs re-contests the space the swarm
			// is holding: the leash gives it its reflexes back while this stands
			ThreatFleetOrders.fightOrbit(e.fleet,
					ThreatGroundFronts.fightsForOrbit(e.factionId, market));
			if (!ThreatFleetOrders.nearPlanet(e.fleet, market.getPrimaryEntity())) {
				ThreatFleetOrders.stationReport(e.fleet, market, "Swarm defend");
			} else if (ThreatGroundFronts.defendBombards(e.factionId, market)) {
				ThreatGroundFronts.supportSlice(e.fleet, market, e.factionId, elapsedDays, "Swarm defend");
			} else if (ThreatGroundFronts.defendFabricates(e.factionId, market)) {
				// the guns are as quiet as orbit can make them and the front is
				// still short: the swarm breaks up its own ships for the ground
				ThreatGroundFronts.fabricateTroops(e.fleet, market, e.factionId, elapsedDays,
						"Swarm defend");
			} else {
				ThreatFleetOrders.idleReport(e.fleet, market, "Swarm defend",
						ThreatGroundFronts.idleReason(e.factionId, market));
			}
		}
	}

	/** The front is gone: the swarm's fleet goes back to its staging colony and despawns; a faction's on the tracked leg. */
	protected static void sendHome(Entry e) {
		CampaignFleetAPI fleet = e.fleet;
		if (fleet == null || !fleet.isAlive()) return;
		if (!Factions.THREAT.equals(e.factionId)) {
			ThreatReturns.sendHome(fleet, e.factionId, e.homeMarketId);
			return;
		}
		fleet.clearAssignments();
		fleet.getMemoryWithoutUpdate().set(Misc.FLEET_RETURNING_TO_DESPAWN, true);
		MarketAPI home = e.homeMarketId != null
				? Global.getSector().getEconomy().getMarket(e.homeMarketId) : null;
		SectorEntityToken to = home != null ? home.getPrimaryEntity() : null;
		if (to != null) {
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, to, 1000f,
					"returning to " + home.getName());
		} else {
			// the staging colony is gone: out of sight into hyperspace, not a
			// fade in front of the player (vanilla's own fallback)
			SectorEntityToken token = Global.getSector().getHyperspace().createToken(0f, 0f);
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, token, 1000f);
		}
		ThreatIncConfig.log("Swarm defend over " + e.marketName + " stands down: " + fleet.getName());
	}
}
