package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * The hive's RAIDER role - guerre de course (docs/design-theory.md 8.2).
 *
 * <p>When a mobilised faction's convoy sails, every hive colony within
 * raiderRangeLY of the route's midpoint that has Defense Swarms above its
 * defensive reserve may detach ONE real garrison swarm to hunt it: the swarm
 * leaves the garrison list (so the leash does not recall it), gets an
 * INTERCEPT on the convoy fleet for raiderDays, and then comes home the way
 * the leash brings strays home - blinders on, aggression off - and rejoins
 * the garrison on arrival. A hive that raids is thinner at home while it does.
 *
 * <p>Convoys are ordinary faction fleets, so the fight itself is vanilla's;
 * ThreatConvoys.poll trims the cargo to the surviving hulls afterward, which
 * is Blackett's constant-loss-per-attack rather than all-or-nothing.
 */
public class ThreatRaiders {

	public static final String KEY_RAIDERS = "threatinc_raiders";
	public static final String RAIDER_FLAG = "$threatinc_raider";

	/** Distance from home at which a returning raider is back on station. */
	public static final float HOME_RANGE = 600f;

	public static class Raider {
		public CampaignFleetAPI fleet;
		public String homeMarketId;
		public CampaignFleetAPI target;
		public String targetFactionId;
		public long sinceTimestamp;
		public float days;
		public boolean returning;

		public String homeName() {
			MarketAPI m = ThreatIncData.resolveColonyMarket(homeMarketId);
			return m != null ? m.getName() : homeMarketId;
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Raider> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_RAIDERS);
		if (!(val instanceof List)) {
			val = new ArrayList<Raider>();
			Global.getSector().getPersistentData().put(KEY_RAIDERS, val);
		}
		return (List<Raider>) val;
	}

	public static boolean isRaider(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(RAIDER_FLAG);
	}

	/** Raiders currently hunting this convoy fleet. */
	public static int huntersOf(CampaignFleetAPI convoyFleet) {
		int n = 0;
		for (Raider r : all()) {
			if (!r.returning && r.target == convoyFleet && r.fleet != null && r.fleet.isAlive()) n++;
		}
		return n;
	}

	/** Raiders detached from a hive colony (for the board). */
	public static List<Raider> raidersFrom(String marketId) {
		List<Raider> result = new ArrayList<Raider>();
		for (Raider r : all()) {
			if (marketId.equals(r.homeMarketId)) result.add(r);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// detaching
	// ------------------------------------------------------------------

	/**
	 * A convoy just sailed: hive colonies near its route roll to hunt it. At
	 * most one raider per convoy.
	 */
	public static void consider(ThreatConvoys.Convoy convoy, Random random) {
		if (!ThreatIncConfig.raiderEnabled() || convoy == null || convoy.fleet == null) return;
		// either end may be an outpost (a front run's or a return's, or a hand
		// order to the player's)
		ThreatBases.Base from = ThreatBases.of(convoy.fromMarketId);
		ThreatBases.Base to = ThreatBases.of(convoy.toMarketId);
		if (from == null || to == null || from.starSystem() == null || to.starSystem() == null) {
			return;
		}
		Vector2f a = from.starSystem().getLocation();
		Vector2f b = to.starSystem().getLocation();
		Vector2f mid = new Vector2f((a.x + b.x) / 2f, (a.y + b.y) / 2f);
		float range = ThreatIncConfig.raiderRangeLY();
		List<MarketAPI> near = new ArrayList<MarketAPI>();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI system = hive.getStarSystem();
			if (system == null || hive.getPrimaryEntity() == null) continue;
			if (Misc.getDistanceLY(system.getLocation(), mid) > range) continue;
			// a raider needs only a swarm above the defensive reserve, not a
			// full garrison (expeditions demand full strength; a hunt is a
			// cheaper commitment - and garrisons are rarely full in a war)
			if (ThreatColonyManager.countLiveGarrison(hive.getId())
					<= ThreatColonyManager.garrisonReserve(hive)) continue;
			near.add(hive);
		}
		if (near.isEmpty()) return;
		// nearest hive rolls first; the first success takes it
		final Vector2f m = mid;
		java.util.Collections.sort(near, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI x, MarketAPI y) {
				return Float.compare(Misc.getDistanceLY(x.getStarSystem().getLocation(), m),
						Misc.getDistanceLY(y.getStarSystem().getLocation(), m));
			}
		});
		for (MarketAPI hive : near) {
			if (random.nextFloat() >= ThreatIncConfig.raiderChance()) continue;
			if (detach(hive, convoy) != null) return;
		}
	}

	/** Takes the largest live garrison swarm off station and sends it after the convoy. */
	public static Raider detach(MarketAPI hive, ThreatConvoys.Convoy convoy) {
		List<CampaignFleetAPI> garrison = ThreatIncData.garrisonsFor(hive.getId());
		CampaignFleetAPI best = null;
		for (CampaignFleetAPI curr : garrison) {
			if (curr == null || !curr.isAlive()) continue;
			if (best == null || curr.getFleetPoints() > best.getFleetPoints()) best = curr;
		}
		if (best == null) return null;
		garrison.remove(best);

		MemoryAPI mem = best.getMemoryWithoutUpdate();
		mem.set(RAIDER_FLAG, true);
		mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
		mem.set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		float days = ThreatIncConfig.raiderDays();
		best.clearAssignments();
		best.addAssignment(FleetAssignment.INTERCEPT, convoy.fleet, days,
				"hunting a supply convoy");
		best.addAssignment(FleetAssignment.GO_TO_LOCATION, hive.getPrimaryEntity(), 1000f,
				"returning to the hive");

		Raider r = new Raider();
		r.fleet = best;
		r.homeMarketId = hive.getId();
		r.target = convoy.fleet;
		r.targetFactionId = convoy.factionId;
		r.sinceTimestamp = Global.getSector().getClock().getTimestamp();
		r.days = days;
		all().add(r);

		String who = ThreatWarState.displayName(convoy.factionId);
		ThreatColonyManager.announce("A Defense Swarm has left orbit at " + hive.getName()
				+ " to hunt " + (convoy.factionId.equals(com.fs.starfarer.api.impl.campaign.ids
						.Factions.PLAYER) ? "your" : who + "'s") + " supply convoy bound for "
				+ convoy.toName() + ".", Misc.getNegativeHighlightColor());
		ThreatIncConfig.log("Raider detached from " + hive.getName() + " vs " + convoy.factionId
				+ " convoy " + convoy.fromName() + " -> " + convoy.toName());
		return r;
	}

	// ------------------------------------------------------------------
	// the tick
	// ------------------------------------------------------------------

	public static void poll() {
		if (all().isEmpty()) return;
		for (Raider r : new ArrayList<Raider>(all())) {
			CampaignFleetAPI fleet = r.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
				all().remove(r);
				ThreatIncConfig.log("Raider lost from " + r.homeName());
				continue;
			}
			MarketAPI home = ThreatIncData.resolveColonyMarket(r.homeMarketId);
			if (home == null || home.getPrimaryEntity() == null) {
				// the colony died while it was out: a stray with no station
				all().remove(r);
				Misc.fadeAndExpire(fleet);
				continue;
			}
			SectorEntityToken planet = home.getPrimaryEntity();
			if (!r.returning) {
				boolean targetGone = r.target == null || !r.target.isAlive() || r.target.isExpired()
						|| !ThreatConvoys.isTracked(r.target);
				float elapsed = Global.getSector().getClock().getElapsedDaysSince(r.sinceTimestamp);
				if (targetGone || elapsed >= r.days) sendHome(r, planet);
				continue;
			}
			if (fleet.getContainingLocation() == planet.getContainingLocation()
					&& Misc.getDistance(fleet, planet) <= HOME_RANGE) {
				// back on station: rejoin the garrison; the leash restores its
				// hunting reflexes the moment it sees the blinders
				fleet.getMemoryWithoutUpdate().unset(RAIDER_FLAG);
				ThreatIncData.garrisonsFor(home.getId()).add(fleet);
				all().remove(r);
				ThreatIncConfig.log("Raider home at " + home.getName());
			}
		}
	}

	/** Blinders on, aggression off, plain travel home - the leash's own recall recipe. */
	protected static void sendHome(Raider r, SectorEntityToken planet) {
		r.returning = true;
		MemoryAPI mem = r.fleet.getMemoryWithoutUpdate();
		mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		r.fleet.clearAssignments();
		r.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 1000f,
				"returning to the hive");
	}
}
