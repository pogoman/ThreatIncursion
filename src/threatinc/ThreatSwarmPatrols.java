package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * THE SWARM'S PATROLS (2026-10-05, the user: "information is power and should
 * not be instant... both sides have patrols which can also serve as scouts
 * that essentially traverse the perimeter of their owned systems and expand
 * outwards"). Once the war is on, every hive system that can stage a swarm
 * keeps Patrol Swarms out on the systems around it, nearest first, each
 * system left alone for swarmPatrolMemoryDays after a visit - so the ring is
 * walked again and again and widens as more patrols fly. A system under
 * attack keeps its patrols to the nearest few stops.
 *
 * <p>A patrol is the hive's eyes outside its own systems, in place of the
 * distance picket (ThreatSwarmIntel.inPicket, off with patrols on): it sees a
 * human attack force in its system or within patrolSightLY of it in
 * hyperspace ({@link #meet}), and what it saw is known to the hive only when
 * the patrol is back in a hive system (ThreatScoutRoute.carry) - a patrol
 * destroyed takes its sighting with it. Being a real, aggressive fleet it
 * hunts the humans' scouting parties itself, by the game's own fleet AI.
 *
 * <p>Meeting a force that flies as a route (a siege far from the player) is
 * settled by speed and strength (ThreatScoutRoute.meetAbstract): outrun and
 * outweighed it is destroyed; faster and weaker it runs home with the
 * warning; stronger and at least as fast it fights the force a day at a time.
 *
 * <p>How many and how big follows the losses (ThreatScoutRoute.level): a
 * hive system's patrol budget is swarmPatrolFPPerSystem, flown as patrols of
 * swarmPatrolFP doubled once for every patrol lost and not yet forgotten
 * (patrolCalmDays without a loss forgets one) - many small ones in a quiet
 * war, few heavy ones where they keep dying. Paid as a Scouting Swarm is:
 * the hive's fabrication bank, its fuel and its supplies.
 */
public class ThreatSwarmPatrols {

	public static final String KEY_PATROLS = "threatinc_swarmPatrols";
	public static final String KEY_PATROLLED = "threatinc_swarmPatrolled";
	public static final String PATROL_FLAG = "$threatinc_swarmPatrol";

	/** A Patrol Swarm out on its ring. */
	public static class Patrol extends ThreatScoutRoute.Party {
		/** The hive system it flies for. */
		public String systemId;
	}

	/** A human attack force a patrol saw and is carrying home (ThreatScoutRoute.Party.carried). */
	public static class Sighting {
		public String key, factionId, systemId, kind;
		public float fp;
	}

	@SuppressWarnings("unchecked")
	public static List<Patrol> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_PATROLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Patrol>();
			Global.getSector().getPersistentData().put(KEY_PATROLS, val);
		}
		return (List<Patrol>) val;
	}

	/** System id -> when a patrol last stayed there. */
	public static Map<String, Long> patrolled() {
		return ThreatIncData.map(KEY_PATROLLED);
	}

	public static boolean enabled() {
		return ThreatIncConfig.patrolsEnabled() && ThreatSwarmIntel.enabled();
	}

	/** Whether the fleet is a Patrol Swarm or a Scouting Swarm: eyes whose sight must be carried home. */
	public static boolean carrier(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		return mem.getBoolean(PATROL_FLAG) || mem.getBoolean(ThreatSwarmScouts.SCOUT_FLAG);
	}

	public static void poll(Random random) {
		if (!enabled()) {
			if (!all().isEmpty()) ROUTE.recallAll();
			return;
		}
		for (Patrol p : new ArrayList<Patrol>(all())) {
			ROUTE.advance(p);
		}
		logApproaches();
		census();
		// the swarm patrols once it is at war: it has struck, and someone has mobilised
		if (IncursionManager.getPhase() < 2 || ThreatWarState.warFactionIds().isEmpty()) return;
		launchAll(random);
	}

	/** Pair of fleet ids -> {closest distance, whether in hyperspace, the two sizes}; not saved. */
	protected static final Map<String, float[]> APPROACH = new java.util.HashMap<String, float[]>();

	/**
	 * Debug log of how close each Patrol Swarm comes to each human scouting
	 * party or patrol (the user, 2026-10-05: "worth logging how close they get
	 * to each other"): one line when a pair that shared a system, or came
	 * within 2 ly in hyperspace, parts or one of them is gone - the closest
	 * distance (units in a system, light-years in hyperspace), both sizes and
	 * which of the two still flies.
	 */
	protected static void logApproaches() {
		if (!ThreatIncConfig.debugLogging()) return;
		java.util.Set<String> together = new java.util.HashSet<String>();
		Map<String, String> names = new java.util.HashMap<String, String>();
		for (Patrol p : all()) {
			CampaignFleetAPI a = p.fleet;
			if (a == null || !a.isAlive() || a.getContainingLocation() == null) continue;
			for (ThreatScouts.Scout s : ThreatScouts.all()) {
				CampaignFleetAPI b = s.fleet;
				if (b == null || !b.isAlive() || b.getContainingLocation() != a.getContainingLocation()) continue;
				boolean hyper = a.getContainingLocation().isHyperspace();
				float d = hyper ? Misc.getDistanceLY(a.getLocationInHyperspace(), b.getLocationInHyperspace())
						: Misc.getDistance(a.getLocation(), b.getLocation());
				if (hyper && d > 2f) continue;
				String key = a.getId() + "|" + b.getId();
				together.add(key);
				float[] v = APPROACH.get(key);
				if (v == null) {
					v = new float[] { d, hyper ? 1f : 0f, a.getFleetPoints(), b.getFleetPoints() };
					APPROACH.put(key, v);
				} else if (d < v[0] || (!hyper && v[1] > 0f)) {
					// a system's units replace hyperspace light-years: the closer meeting
					v[0] = d;
					v[1] = hyper ? 1f : 0f;
				}
				names.put(key, (s.patrol ? "patrol" : "scouting party") + " of " + s.factionId + " in "
						+ a.getContainingLocation().getNameWithLowercaseType());
				v = APPROACH.get(key);
				if (v.length == 4) APPROACH.put(key, new float[] { v[0], v[1], v[2], v[3], a.getFleetPoints(), b.getFleetPoints() });
				else { v[4] = a.getFleetPoints(); v[5] = b.getFleetPoints(); }
			}
		}
		for (java.util.Iterator<Map.Entry<String, float[]>> it = APPROACH.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, float[]> e = it.next();
			if (together.contains(e.getKey())) continue;
			float[] v = e.getValue();
			it.remove();
			String[] ids = e.getKey().split("\\|");
			// home, lost (ThreatScoutRoute.FATES) or still flying
			String swarmFate = ThreatScoutRoute.FATES.get(ids[0]), humanFate = ThreatScoutRoute.FATES.get(ids[1]);
			ThreatIncConfig.log(String.format(
					"Patrols passed: a Patrol Swarm (%d FP) and a human party (%d FP) came within %s; last together %d FP and %d FP; after: swarm %s, human %s",
					Math.round(v[2]), Math.round(v[3]),
					v[1] > 0f ? String.format("%.2f ly in hyperspace", v[0]) : Math.round(v[0]) + " units in a system",
					Math.round(v.length > 4 ? v[4] : v[2]), Math.round(v.length > 4 ? v[5] : v[3]),
					swarmFate != null ? swarmFate : "flies", humanFate != null ? humanFate : "flies"));
		}
	}

	protected static long censusAt;

	/** Once a month, both sides' patrol census (ThreatScoutRoute.census) with their current levels. */
	protected static void census() {
		if (!ThreatIncConfig.debugLogging()) return;
		long now = Global.getSector().getClock().getTimestamp();
		if (censusAt != 0L && Global.getSector().getClock().getElapsedDaysSince(censusAt) < 30f) return;
		censusAt = now;
		StringBuilder lv = new StringBuilder();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			int l = ThreatScoutRoute.level(levelKey(systemId));
			if (l > 0) lv.append(lv.length() > 0 ? ", " : "").append(systemId).append(' ').append(l);
		}
		ROUTE.census("swarm", lv.length() > 0 ? lv.toString() : "none raised");
		ThreatScouts.census();
	}

	/** RESET War: the patrols out fade and the ring's memory goes. */
	public static void reset() {
		ROUTE.clearAll();
		Global.getSector().getPersistentData().remove(KEY_PATROLS);
		Global.getSector().getPersistentData().remove(KEY_PATROLLED);
	}

	protected static final ThreatScoutRoute<Patrol> ROUTE = new ThreatScoutRoute<Patrol>() {
		protected List<Patrol> all() {
			return ThreatSwarmPatrols.all();
		}
		protected String describe(Patrol p) {
			return "Patrol Swarm of " + p.systemId;
		}
		protected boolean knownStop(String systemId) {
			return false;
		}
		protected boolean onEnter(Patrol p, StarSystemAPI system, long now) {
			return false;
		}
		protected void onStay(Patrol p, StarSystemAPI system, long now) {
			patrolled().put(system.getId(), now);
		}
		protected String stayVerb() {
			return "patrolling";
		}
		protected MarketAPI homeOf(Patrol p) {
			MarketAPI home = ThreatIncData.resolveColonyMarket(p.homeMarketId);
			if (home != null) return home;
			// its colony fell: any other of its system
			for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(p.systemId)) return c;
			return null;
		}
		protected String returnLabel(Patrol p, MarketAPI home) {
			return "returning to the hive";
		}
		protected boolean friendly(Patrol p, StarSystemAPI system) {
			return !ThreatIncData.getLiveColonyMarkets(system.getId()).isEmpty();
		}
		protected void deliver(Patrol p, Object seen) {
			if (!(seen instanceof Sighting)) return;
			Sighting s = (Sighting) seen;
			// known the day it is brought home
			ThreatSwarmIntel.note(s.key, s.factionId, s.systemId, s.fp, ThreatSwarmIntel.PATROL,
					ThreatPosture.today(), s.kind);
		}
		protected void onLost(Patrol p) {
			ThreatScoutRoute.lost(levelKey(p.systemId));
			ThreatIncConfig.log("Patrol Swarm of " + p.systemId + " did not come back: patrols now "
					+ (int) size(p.systemId) + " FP each");
		}
	};

	protected static String levelKey(String systemId) {
		return "h:" + systemId;
	}

	/** The size a hive system's patrols are built at: swarmPatrolFP doubled for every loss not yet forgotten. */
	protected static float size(String systemId) {
		return ThreatIncConfig.swarmPatrolFP() * (float) Math.pow(2, ThreatScoutRoute.level(levelKey(systemId)));
	}

	protected static void launchAll(Random random) {
		List<String> systems = new ArrayList<String>(ThreatIncData.colonyMarkets().keySet());
		Collections.shuffle(systems, random);
		Map<String, float[]> posture = ThreatPosture.state();
		for (String systemId : systems) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			ThreatScoutRoute.calm(levelKey(systemId));
			MarketAPI colony = ThreatSwarmScouts.pickStaging(systemId);
			if (colony == null) continue;
			float size = size(systemId);
			int want = Math.max(1, (int) (ThreatIncConfig.swarmPatrolFPPerSystem() / size));
			int out = 0;
			for (Patrol p : all()) {
				if (systemId.equals(p.systemId)) out++;
			}
			float[] s = posture.get(systemId);
			// under attack the ring is pulled in
			boolean close = s != null && s.length > ThreatPosture.S_ATTACKED && s[ThreatPosture.S_ATTACKED] > 0f;
			while (out < want) {
				List<String> route = planRoute(colony, close ? 3 : ThreatIncConfig.patrolStops());
				if (route.isEmpty() || launch(colony, systemId, route, size, random) == null) break;
				out++;
			}
		}
	}

	/**
	 * The nearest systems around the colony's that no hive and no one else
	 * holds, not on another patrol's route and not patrolled within
	 * swarmPatrolMemoryDays, in the order a patrol flies them.
	 */
	protected static List<String> planRoute(MarketAPI colony, int stops) {
		final StarSystemAPI home = colony.getStarSystem();
		List<String> route = new ArrayList<String>();
		if (home == null || stops <= 0) return route;
		List<String> taken = ROUTE.taken();
		List<StarSystemAPI> candidates = new ArrayList<StarSystemAPI>();
		float memory = ThreatIncConfig.swarmPatrolMemoryDays();
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system == home || system.getCenter() == null || ThreatScouts.unreachable(system)) continue;
			if (taken.contains(system.getId())) continue;
			if (!ThreatIncData.getLiveColonyMarkets(system.getId()).isEmpty() || ThreatScouts.inhabited(system)) continue;
			Long when = patrolled().get(system.getId());
			if (when != null && Global.getSector().getClock().getElapsedDaysSince(when) < memory) continue;
			candidates.add(system);
		}
		Collections.sort(candidates, new Comparator<StarSystemAPI>() {
			public int compare(StarSystemAPI a, StarSystemAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getLocation(), home.getLocation()),
						Misc.getDistanceLY(b.getLocation(), home.getLocation()));
			}
		});
		if (candidates.size() > stops) candidates = new ArrayList<StarSystemAPI>(candidates.subList(0, stops));
		// the nearest few, flown as a chain from home
		Vector2f at = home.getLocation();
		while (!candidates.isEmpty()) {
			StarSystemAPI next = null;
			float best = Float.MAX_VALUE;
			for (StarSystemAPI system : candidates) {
				float d = Misc.getDistanceLY(system.getLocation(), at);
				if (d < best) {
					best = d;
					next = system;
				}
			}
			candidates.remove(next);
			route.add(next.getId());
			at = next.getLocation();
		}
		return route;
	}

	/** Fabricates a Patrol Swarm of about {@code size} FP at the colony and sends it round the route; null when the hive cannot pay or build it. */
	protected static Patrol launch(MarketAPI colony, String systemId, List<String> route, float size, Random random) {
		if (!ThreatColonyManager.canAffordFP(colony, size)) return null;
		float ly = ThreatSwarmScouts.routeLY(colony, route);
		if (!ThreatReach.canSustain(size, ThreatReach.days(ly))) return null;
		if (!ThreatFuel.canPay(ThreatFuel.passage(size, ly, false))) {
			ThreatFuel.held("a patrol from " + colony.getName());
			return null;
		}
		CampaignFleetAPI fleet = ThreatFleetComposer.createScouts(size, new Random(random.nextLong()));
		if (fleet == null || fleet.isEmpty()) return null;
		fleet.setName("Patrol Swarm");
		ThreatColonyManager.chargeFP(colony, fleet.getFleetPoints());
		ThreatFuel.pay(Math.min(ThreatFuel.stock(), ThreatFuel.passage(fleet.getFleetPoints(), ly, false)));
		// what survives is re-banked when it despawns home
		ThreatColonyManager.bindToLedger(fleet, colony.getId());
		colony.getStarSystem().addEntity(fleet);
		fleet.setLocation(colony.getPrimaryEntity().getLocation().x, colony.getPrimaryEntity().getLocation().y);
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(PATROL_FLAG, true);
		// it hunts what it finds on its ring - the humans' scouting parties
		mem.set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		mem.set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);

		Patrol p = new Patrol();
		p.fleet = fleet;
		p.homeMarketId = colony.getId();
		p.systemId = systemId;
		p.route = route;
		all().add(p);
		ROUTE.sendTo(p, ThreatScoutRoute.systemById(route.get(0)));
		ThreatReach.commit(fleet.getFleetPoints());
		ThreatIncConfig.log("Patrol Swarm from " + colony.getName() + ": " + route + " (" + (int) fleet.getFleetPoints()
				+ " FP, " + (int) ly + " ly there and home)");
		return p;
	}

	/**
	 * A human attack force the hive's own eyes do not see is at {@code where}
	 * ({@code hyper} in hyperspace) today (ThreatSwarmIntel.sweepContacts): every
	 * patrol in the same system, or within patrolSightLY of it in hyperspace,
	 * sees it and takes the sighting aboard. Against a force flying as a route
	 * ({@code route} non-null: {@code siege} its expedition) the meeting is
	 * settled by speed and strength; a real fleet the game's own AI meets, and
	 * the patrol turns for home with the warning.
	 */
	public static void meet(String key, String factionId, String systemId, float fp, String kind,
			LocationAPI where, Vector2f hyper, ThreatPurgeFGI siege) {
		if (!enabled() || where == null || fp <= 0f) return;
		for (Patrol p : new ArrayList<Patrol>(all())) {
			CampaignFleetAPI fleet = p.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.getContainingLocation() == null) continue;
			if (!ThreatScoutRoute.near(fleet, where, hyper)) continue;
			Sighting seen = null;
			if (p.carried != null) {
				for (Object o : p.carried) {
					if (o instanceof Sighting && key.equals(((Sighting) o).key)) seen = (Sighting) o;
				}
			}
			boolean first = seen == null;
			if (first) {
				seen = new Sighting();
				seen.key = key;
				ROUTE.carry(p, seen);
			}
			seen.factionId = factionId;
			seen.systemId = systemId;
			seen.kind = kind;
			seen.fp = fp;
			int outcome = ThreatScoutRoute.RUNS;
			if (siege != null && siege.getRoute() != null) {
				outcome = ThreatScoutRoute.meetAbstract(fleet, fp, forceBurn(siege), siege.getRoute(),
						"Patrol Swarm of " + p.systemId, factionId + " " + kind);
			}
			if (outcome == ThreatScoutRoute.DESTROYED) continue; // advance books it lost
			if (outcome == ThreatScoutRoute.RUNS && !p.returning) {
				if (first) {
					ThreatIncConfig.log("Patrol Swarm of " + p.systemId + " saw " + factionId + " " + kind + " of "
							+ (int) fp + " FP and turns for home with it");
				}
				ROUTE.goHome(p);
				// it runs: no fight picked on the way
				fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
				fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
			}
		}
	}

	/** The burn a human expedition flying as a route makes: bombers hauling razing fuel the slowest, a siege with its transports next, a raid the fastest. */
	protected static float forceBurn(ThreatPurgeFGI siege) {
		if (siege.razeFuel > 0f) return ThreatIncConfig.patrolBurnBombers();
		if (siege.marinesAllotted > 0f) return ThreatIncConfig.patrolBurnSiege();
		return ThreatIncConfig.patrolBurnRaid();
	}
}
