package threatinc;

import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Omens (2026-09-26, docs/omens.md): until anyone finds a hive the sector
 * only hears the swarm. Five kinds of message, all silent for good once any
 * infested system is discovered:
 * <ul>
 * <li>static - the player's fleet within omenStaticLY of an unfound hive
 * colony (two tiers, with a compass bearing; omenStaticDays apart);</li>
 * <li>spread - a system is newly seeded, named by the nearest inhabited system;</li>
 * <li>scout - a Scouting Swarm charts an inhabited system;</li>
 * <li>strike - a strike launches unseen, named by its target system;</li>
 * <li>reach - once, the first time the swarm can strike (phase 2).</li>
 * </ul>
 * Spread, scout and strike share one sector clock, omenSectorDays apart.
 * Lines play in order (they escalate), then repeat from the pool's latter half.
 * Off with omensEnabled, or with hiveFogOfWar (every hive is known).
 */
public class ThreatOmens {

	public static final String KEY_DONE = "threatinc_omensDone";
	/** Infested system ids already heralded (or present when omens first ran). */
	public static final String KEY_HERALDED = "threatinc_omenHeralded";
	/** Clock -> timestamp of its last omen. */
	public static final String KEY_TIMES = "threatinc_omenTimes";
	/** Kind -> lines shown. */
	public static final String KEY_COUNTS = "threatinc_omenCounts";

	protected static final String CLOCK_STATIC = "static";
	protected static final String CLOCK_SECTOR = "sector";

	protected static final String[] STATIC_FAR = {
		"Your comms officer reports a pattern in the static to the %s - too regular to be noise, too dense to be any signal they know.",
		"Long-range sensors keep resolving a ghost contact to the %s, then losing it. It does not drift.",
		"Something to the %s is transmitting in bursts the fleet's systems cannot parse. The crew have started calling it the choir.",
		"The sensor officer flags a faint, rhythmic interference on the long-range bands, strongest to the %s.",
	};

	protected static final String[] STATIC_NEAR = {
		"The static has a voice now. Comms picks up fragments of machine code on every band, strongest to the %s, and the crew are not sleeping well.",
		"Ship systems are logging handshake requests from something to the %s - thousands a second, all refused.",
		"Your sensor officer has stopped calling it interference. Something to the %s is building, and it is close.",
	};

	protected static final String[] SPREAD = {
		"Survey beacons out past the %s have stopped answering. Maintenance crews blame solar weather.",
		"Prospectors working the dark beyond the %s have gone quiet. Their last transmission was mostly static.",
		"A salvage crew back from beyond the %s swears the derelicts out there were being taken apart - by something that was not them.",
		"Listening posts log another burst of fabrication noise from beyond the %s. It is louder than the last.",
	};

	protected static final String[] SCOUT = {
		"Pickets in the %s logged a contact that matched no known hull. It ignored hails, and it did not stay.",
		"Traffic control in the %s reports a sensor ghost that shadowed the inbound lanes for a day, then vanished.",
		"Something passed through the %s without a transponder. The patrol sent after it found nothing - not even a wake.",
	};

	protected static final String[] STRIKE = {
		"Freighters bound for the %s are arriving late, or not at all. Insurers have started asking questions.",
		"A convoy out of the %s sent a distress call that cut off mid-sentence. No wreckage has been found.",
		"Captains on the lanes near the %s report dead comm buoys, and running lights that answer no hail.",
		"Ships have started disappearing around the %s. The patrols have stopped calling it piracy.",
	};

	protected static final String REACH =
		"Spacers on the fringe routes are refusing contracts. Ships have started going missing, "
		+ "and the ones that come back tell of lights in the dark that follow them home.";

	protected static final String[] COMPASS = {
		"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east",
	};

	protected static final Random RANDOM = new Random();

	protected static Map<String, Long> times() {
		return ThreatIncData.map(KEY_TIMES);
	}

	protected static Map<String, Integer> counts() {
		return ThreatIncData.map(KEY_COUNTS);
	}

	/** RESET War: the next war's omens start over. */
	public static void reset() {
		Map<String, Object> data = Global.getSector().getPersistentData();
		data.remove(KEY_DONE);
		data.remove(KEY_HERALDED);
		data.remove(KEY_TIMES);
		data.remove(KEY_COUNTS);
	}

	/** Omens still play: the war is on and nobody has found a hive yet. */
	public static boolean active() {
		if (!ThreatIncConfig.omensEnabled() || !ThreatIncConfig.hiveFogOfWar()) return false;
		if (!ThreatIncData.isStarted()) return false;
		if (Boolean.TRUE.equals(Global.getSector().getPersistentData().get(KEY_DONE))) return false;
		List<String> discovered = ThreatIncData.discoveredSystems();
		for (String id : ThreatIncData.stages().keySet()) {
			if (discovered.contains(id)) {
				Global.getSector().getPersistentData().put(KEY_DONE, true);
				return false;
			}
		}
		return true;
	}

	/** Half-day poll from IncursionManager.advance. */
	public static void poll() {
		if (!active()) return;
		heraldSpread();
		if (!counts().containsKey("reach") && IncursionManager.getPhase() >= 2) {
			counts().put("reach", 1);
			ThreatNotice.titled("The Fringe Goes Dark").bad().line(REACH).send();
		}
		staticNearPlayer();
	}

	/** A Scouting Swarm has just charted an inhabited system. */
	public static void onSwarmScouted(StarSystemAPI system) {
		if (system == null || !active() || !clockReady(CLOCK_SECTOR, ThreatIncConfig.omenSectorDays())) return;
		postPlace("scout", "Unknown Contact", SCOUT, place(system), false);
		stamp(CLOCK_SECTOR);
	}

	/** A strike has launched and nobody saw it go. */
	public static void onStrikeLaunched(MarketAPI target) {
		if (target == null || target.getStarSystem() == null) return;
		if (!active() || !clockReady(CLOCK_SECTOR, ThreatIncConfig.omenSectorDays())) return;
		postPlace("strike", "Ships Missing", STRIKE, place(target.getStarSystem()), true);
		stamp(CLOCK_SECTOR);
	}

	protected static void heraldSpread() {
		boolean first = !Global.getSector().getPersistentData().containsKey(KEY_HERALDED);
		List<String> heralded = ThreatIncData.list(KEY_HERALDED);
		for (String id : ThreatIncData.stages().keySet()) {
			if (heralded.contains(id)) continue;
			heralded.add(id);
			// the war's opening seeds are the start message's
			if (first) continue;
			if (!clockReady(CLOCK_SECTOR, ThreatIncConfig.omenSectorDays())) continue;
			StarSystemAPI near = nearestInhabited(ThreatScoutRoute.systemById(id));
			if (near == null) continue;
			postPlace("spread", "Beacons Gone Quiet", SPREAD, place(near), false);
			stamp(CLOCK_SECTOR);
		}
	}

	protected static void staticNearPlayer() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return;
		float range = ThreatIncConfig.omenStaticLY();
		if (range <= 0f || !clockReady(CLOCK_STATIC, ThreatIncConfig.omenStaticDays())) return;
		Vector2f here = player.getLocationInHyperspace();
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (String id : ThreatIncData.colonyMarkets().keySet()) {
			if (ThreatIncData.getLiveColonyMarkets(id).isEmpty()) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(id);
			if (system == null || system == player.getContainingLocation()) continue;
			float dist = Misc.getDistanceLY(here, system.getLocation());
			if (dist < bestDist) {
				bestDist = dist;
				best = system;
			}
		}
		if (best == null || bestDist > range) return;
		String bearing = COMPASS[Math.round(Misc.getAngleInDegrees(here, best.getLocation()) / 45f + 8f) % 8];
		if (bestDist <= range * 0.5f) {
			postPlace("near", "Something Close", STATIC_NEAR, bearing, true);
		} else {
			postPlace("far", "Strange Static", STATIC_FAR, bearing, false);
		}
		stamp(CLOCK_STATIC);
	}

	/** The nearest system with a living non-Threat colony - the one that would notice. */
	protected static StarSystemAPI nearestInhabited(StarSystemAPI from) {
		if (from == null) return null;
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.getStarSystem() == null) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
			float dist = Misc.getDistanceLY(from.getLocation(), market.getStarSystem().getLocation());
			if (dist < bestDist) {
				bestDist = dist;
				best = market.getStarSystem();
			}
		}
		return best;
	}

	protected static String place(StarSystemAPI system) {
		return system.getNameWithLowercaseType();
	}

	protected static boolean clockReady(String clock, float days) {
		Long last = times().get(clock);
		return last == null || Global.getSector().getClock().getElapsedDaysSince(last) >= days;
	}

	protected static void stamp(String clock) {
		times().put(clock, Global.getSector().getClock().getTimestamp());
	}

	/** The kind's next line: in order the first time through, then the pool's latter half at random. */
	protected static String nextLine(String kind, String[] pool) {
		Integer shown = counts().get(kind);
		int n = shown == null ? 0 : shown;
		counts().put(kind, n + 1);
		if (n < pool.length) return pool[n];
		int from = pool.length / 2;
		return pool[from + RANDOM.nextInt(pool.length - from)];
	}

	protected static void postPlace(String kind, String title, String[] pool, String place, boolean bad) {
		ThreatNotice notice = ThreatNotice.titled(title).line(nextLine(kind, pool), place);
		if (bad) notice.bad();
		notice.send();
	}
}