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
 * <p>When a mobilised faction's convoy sails, every hive colony that reaches
 * the route's midpoint before the convoy does (billed reach, ThreatReach;
 * off, within raiderRangeLY of it) that has Defense Swarms above its
 * defensive reserve may detach real garrison swarms to hunt it, until the
 * pack outweighs the convoy (consider): each swarm
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
	 * The margin raiders hunt a convoy with: hives detach swarms until the
	 * pack's fleet points reach the convoy's times this. The margin the NPC
	 * sieges weigh an orbit with (npcSiegeOrbitMargin's default); a code
	 * constant until it has a knob of its own.
	 */
	public static final float RAIDER_MARGIN = 1.5f;

	/**
	 * A convoy just sailed: hive colonies near its route roll to hunt it,
	 * nearest first. Each that rolls detaches swarms above its reserve, one
	 * after another, until the pack outweighs the convoy by RAIDER_MARGIN
	 * (2026-09-29: it was one raider per convoy, one swarm per hive). A swarm
	 * out holds its slot at home (ThreatColonyManager.swarmsAway), so the
	 * reserve test thins itself as swarms leave.
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
		// billed reach (ThreatReach): a hive that reaches the route's middle
		// before the convoy does - half the route, at the one speed the board
		// estimates both at; off, raiderRangeLY
		float range = ThreatReach.enabled() ? Misc.getDistanceLY(a, b) / 2f : ThreatIncConfig.raiderRangeLY();
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
		float need = convoy.fleet.getFleetPoints() * RAIDER_MARGIN;
		for (MarketAPI hive : near) {
			if (packFP(convoy.fleet) >= need) return;
			if (random.nextFloat() >= ThreatIncConfig.raiderChance()) continue;
			// terminates: every detach takes a swarm off this garrison
			while (packFP(convoy.fleet) < need
					&& ThreatColonyManager.countLiveGarrison(hive.getId())
							> ThreatColonyManager.garrisonReserve(hive)) {
				if (detach(hive, convoy) == null) break;
			}
		}
	}

	/** Fleet points of the raiders out hunting this convoy fleet. */
	public static float packFP(CampaignFleetAPI convoyFleet) {
		float fp = 0f;
		for (Raider r : all()) {
			if (!r.returning && r.target == convoyFleet && r.fleet != null && r.fleet.isAlive()) {
				fp += r.fleet.getFleetPoints();
			}
		}
		return fp;
	}

	/**
	 * Takes the largest live hunter-pack swarm off station - the largest
	 * swarm of any kind if the garrison has none - and sends it after the convoy.
	 */
	public static Raider detach(MarketAPI hive, ThreatConvoys.Convoy convoy) {
		List<CampaignFleetAPI> garrison = ThreatIncData.garrisonsFor(hive.getId());
		CampaignFleetAPI best = null;
		boolean bestHunter = false;
		for (CampaignFleetAPI curr : garrison) {
			if (curr == null || !curr.isAlive()) continue;
			boolean hunter = ThreatFleetComposer.HUNTER.equals(ThreatFleetComposer.archetypeOf(curr));
			if (best == null || (hunter && !bestHunter)
					|| (hunter == bestHunter && curr.getFleetPoints() > best.getFleetPoints())) {
				best = curr;
				bestHunter = hunter;
			}
		}
		if (best == null) return null;
		// the supplies it burns away come out of what the colonies leave (ThreatReach)
		if (!ThreatReach.canSustain(best.getFleetPoints())) return null;
		// there and back comes from the hive's fuel (ThreatFuel)
		float ly = hive.getStarSystem() == null ? 0f
				: Misc.getDistanceLY(hive.getStarSystem().getLocation(), convoy.fleet.getLocationInHyperspace());
		float fuel = ThreatFuel.passage(best.getFleetPoints(), ly, true);
		if (!ThreatFuel.pay(fuel)) {
			ThreatFuel.held("a raider from " + hive.getName());
			return null;
		}
		ThreatReach.commit(best.getFleetPoints());
		ThreatReach.note("raid", ly);
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

		ThreatNotice n = ThreatNotice.titled("Convoy Hunted").bad()
				.line("A Defense Swarm has left orbit at %s.", ThreatNotice.market(hive));
		if (convoy.factionId.equals(com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER)) {
			n.line("It hunts your supply convoy bound for %s.", convoy.toName());
		} else {
			n.line("It hunts the %s supply convoy bound for %s.",
					ThreatNotice.faction(Global.getSector().getFaction(convoy.factionId)),
					convoy.toName());
		}
		ThreatColonyManager.announce(n);
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
				// the colony died while it was out: a stray with no station. Its
				// hulls go to the nearest live colony's bank (2026-09-29: closed
				// economy) - bound to it and credited when it leaves the sector,
				// as a dead colony's garrison is, not paid before it is gone. It
				// withdraws the garrison's way (retireFleet: out by the system's
				// jump point, or at once in hyperspace), blinders on so no chase
				// holds it here
				all().remove(r);
				String to = ThreatColonyManager.bindStrayToNearest(fleet);
				ThreatIncConfig.log("Raider from " + r.homeName() + " stood down, its colony gone: "
						+ (int) fleet.getFleetPoints() + " FP "
						+ (to != null ? "bound for the bank at " + to : "lost, no hive left"));
				MemoryAPI mem = fleet.getMemoryWithoutUpdate();
				mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
				mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
				ThreatColonyManager.retireFleet(fleet, fleet.getStarSystem());
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
				// back on station: rejoin the garrison - always, it has no ceiling
				// to be over (2026-09-29); the leash restores its hunting reflexes
				// the moment it sees the blinders
				if (fleet.getBattle() != null) continue;
				fleet.getMemoryWithoutUpdate().unset(RAIDER_FLAG);
				all().remove(r);
				ThreatIncData.garrisonsFor(home.getId()).add(fleet);
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
