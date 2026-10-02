package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * FOG OF WAR ON THE SWARM (2026-10-01, user's call; docs/attack-planner.md
 * section 1). Nobody reads the swarm's strength remotely and live - not an NPC
 * faction, not the player. Each mobilised faction, and the player, holds a
 * REPORT of each Threat system: the Defense Swarms over each hive world (the
 * Threat fleet points within the contest radius, ORBIT_HOLD_RANGE, the same
 * count {@code ThreatGroundFronts.pointsNear} makes), those elsewhere in the
 * system, and the day it was seen and how.
 *
 * <p>Reports are made only where the observer is present: EYES - any fleet of
 * its (siege, hunt, convoy, scout, guard, relief, a passing trader), its
 * colony, its ground army or an unspawned route of its in the system (exact,
 * every day while there, and on arrival); a SCOUT sent to look (exact, on
 * arrival). No radar on either side (user, 2026-10-02): the picture is real
 * time while a ship is there, and from the day the last one leaves it stands
 * and ages. Coalition partners pool what they see; the player also gets the
 * reports of factions at Cooperative (user's answer 2a). A report keeps its
 * figures as it ages; what falls is its TRUST, 0.5 ^ (age / intelHalfLifeDays),
 * and the planner spreads its blows by it (ThreatAttackPlanner).
 *
 * <p>Planet facts - size, structures, the defence figure - stay live: they
 * change slowly and mostly by the observer's own hand. What moves is the
 * swarm. A fleet's own judgement on the spot stays live too (the contest
 * rule, a siege's break-off, the leash): that is the fleet's own eyes.
 *
 * <p>The Threat's own remote reads of human forces are a separate, later
 * change (user, 2026-10-01).
 */
public final class ThreatIntel {

	private ThreatIntel() {
	}

	public static final String KEY_REPORTS = "threatinc_intelReports";
	public static final String KEY_LAST_CENSUS = "threatinc_intelLastCensus";

	public static final String EYES = "eyes";
	public static final String SCOUT = "scout";

	/** One observer's picture of one Threat system. */
	public static class Report {
		public String systemId;
		/** The faction whose eyes made it (an ally's, when shared). */
		public String seenBy;
		/** {@link #EYES} or {@link #SCOUT} ("radar" in a save from before 2026-10-02: it stands and ages like any other). */
		public String source;
		/** {@link ThreatPosture#today()} when seen. */
		public float day;
		/**
		 * Hive market id -> {Threat fleet points within the contest radius, fleets}:
		 * what the contest at that world would count, so a fleet within reach of
		 * two worlds counts at both.
		 */
		public Map<String, float[]> worlds = new LinkedHashMap<String, float[]>();
		/** Threat fleet points within the contest radius of any hive world, each fleet once. */
		public float nearFP;
		public int nearFleets;
		/** Threat fleet points in the system and near none of its hive worlds. */
		public float looseFP;
		public int looseFleets;

		public float worldFP(String marketId) {
			float[] w = worlds.get(marketId);
			return w != null ? w[0] : 0f;
		}

		public int worldFleets(String marketId) {
			float[] w = worlds.get(marketId);
			return w != null ? (int) w[1] : 0;
		}

		/** The swarms over the strongest world it saw. */
		public float strongestFP() {
			float most = 0f;
			for (float[] w : worlds.values()) most = Math.max(most, w[0]);
			return most;
		}

		public float totalFP() {
			return nearFP + looseFP;
		}

		public int totalFleets() {
			return nearFleets + looseFleets;
		}

		public float age() {
			return Math.max(0f, today() - day);
		}
	}

	public static boolean enabled() {
		return ThreatIncConfig.intelFogOfWar();
	}

	/** Observer -> system id -> its own report. */
	static Map<String, Map<String, Report>> store() {
		return ThreatIncData.map(KEY_REPORTS);
	}

	static Map<String, Report> own(String observer) {
		Map<String, Report> mine = store().get(observer);
		if (mine == null) {
			mine = new LinkedHashMap<String, Report>();
			store().put(observer, mine);
		}
		return mine;
	}

	protected static float today() {
		return ThreatPosture.today();
	}

	/** The observer id a faction reports under: its id ("player" for the player's own). */
	public static String observerOf(FactionAPI faction) {
		if (faction == null) return null;
		return faction.isPlayerFaction() ? Factions.PLAYER : faction.getId();
	}

	/** Who keeps reports: the player, and every mobilised faction. */
	public static List<String> observers() {
		List<String> out = new ArrayList<String>();
		out.add(Factions.PLAYER);
		for (String id : ThreatWarState.warFactionIds()) {
			if (!out.contains(id) && !Factions.THREAT.equals(id)) out.add(id);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// seeing
	// ------------------------------------------------------------------

	/** A Threat fleet the contest would count: armed, not a trader, smuggler or scavenger. */
	protected static boolean swarmFleet(CampaignFleetAPI fleet) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return false;
		if (fleet.getFaction() == null || !Factions.THREAT.equals(fleet.getFaction().getId())) return false;
		if (fleet.getFleetPoints() <= 0f) return false;
		return !Misc.isTrader(fleet) && !Misc.isSmuggler(fleet) && !Misc.isScavenger(fleet);
	}

	/**
	 * The system as it stands now: over each hive world, every Threat fleet the
	 * contest there would count (within ORBIT_HOLD_RANGE); the rest loose.
	 */
	protected static Report picture(StarSystemAPI system, String observer, String source) {
		Report r = new Report();
		r.systemId = system.getId();
		r.seenBy = observer;
		r.source = source;
		r.day = today();
		List<MarketAPI> hives = ThreatIncData.getLiveColonyMarkets(system.getId());
		for (MarketAPI hive : hives) r.worlds.put(hive.getId(), new float[] { 0f, 0f });
		for (CampaignFleetAPI fleet : system.getFleets()) {
			if (!swarmFleet(fleet)) continue;
			float fp = fleet.getFleetPoints();
			boolean near = false;
			for (MarketAPI hive : hives) {
				if (hive.getPrimaryEntity() == null || !ThreatGroundFronts.nearWorld(fleet, hive)) continue;
				float[] w = r.worlds.get(hive.getId());
				w[0] += fp;
				w[1] += 1f;
				near = true;
			}
			if (near) {
				r.nearFP += fp;
				r.nearFleets++;
			} else {
				r.looseFP += fp;
				r.looseFleets++;
			}
		}
		return r;
	}

	/**
	 * Writes the observer's report of the system from what is there now.
	 * Called only where the observer has eyes on it. A world whose swarms
	 * moved by half or more since the observer's last look is news to its
	 * planner (and its partners').
	 */
	public static Report see(String observer, StarSystemAPI system, String source) {
		if (observer == null || system == null || !enabled()) return null;
		Report r = picture(system, observer, source);
		Map<String, Report> mine = own(observer);
		Report old = mine.get(system.getId());
		mine.put(system.getId(), r);
		String moved = moved(old, r);
		if (moved != null) {
			ThreatIncConfig.log("Intel: " + observer + " sees the " + system.getName() + " by " + source
					+ ": " + moved);
			ThreatAttackPlanner.news(observer, "swarms moved in the " + system.getName());
			for (String partner : partnersOf(observer)) {
				ThreatAttackPlanner.news(partner, "an ally saw the swarms move in the " + system.getName());
			}
		} else if (old == null) {
			ThreatIncConfig.log("Intel: " + observer + " first sees the " + system.getName() + " by " + source
					+ ": " + (int) r.totalFP() + " FP (" + describe(r) + ")");
			// a first picture - a recon party's, a fleet passing through - is news as a move is
			ThreatAttackPlanner.news(observer, "a first look at the " + system.getName());
			for (String partner : partnersOf(observer)) {
				ThreatAttackPlanner.news(partner, "an ally's first look at the " + system.getName());
			}
		} else if (SCOUT.equals(source)) {
			// the plans that wait on a recon party (a stale report) go when it reports
			ThreatAttackPlanner.news(observer, "a scout's look at the " + system.getName());
		}
		return r;
	}

	/** Whether any world's swarms moved by half or more either way since {@code old}; the change, or null. */
	protected static String moved(Report old, Report now) {
		if (old == null) return null;
		StringBuilder sb = null;
		Set<String> ids = new HashSet<String>(old.worlds.keySet());
		ids.addAll(now.worlds.keySet());
		for (String id : ids) {
			float a = old.worldFP(id);
			float b = now.worldFP(id);
			if (!threatinc.rules.PlannerRules.moved(a, b)) continue;
			MarketAPI m = Global.getSector().getEconomy().getMarket(id);
			if (sb == null) sb = new StringBuilder();
			else sb.append(", ");
			sb.append(m != null ? m.getName() : id).append(' ').append((int) a).append(" -> ").append((int) b)
					.append(" FP (").append((int) old.age()).append(" d old)");
		}
		return sb != null ? sb.toString() : null;
	}

	protected static String describe(Report r) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, float[]> e : r.worlds.entrySet()) {
			MarketAPI m = Global.getSector().getEconomy().getMarket(e.getKey());
			if (sb.length() > 0) sb.append(", ");
			sb.append(m != null ? m.getName() : e.getKey()).append(' ').append((int) e.getValue()[0]);
		}
		if (r.looseFP > 0f) sb.append(sb.length() > 0 ? ", " : "").append("loose ").append((int) r.looseFP);
		return sb.toString();
	}

	/**
	 * A fleet, colony or army of the observer is in the system now: it looks
	 * (exact). The report, or null when it has no eyes there or the system is
	 * not found (finding a hive stays sector-wide news, ThreatScouts.reveal).
	 */
	public static Report look(String observer, StarSystemAPI system) {
		if (observer == null || system == null || !enabled()) return null;
		if (!ThreatScouts.sectorKnows(system.getId())) return null;
		if (!eyesIn(system).contains(observer)) return null;
		return see(observer, system, EYES);
	}

	/** The observer id a market reports under. */
	protected static String observerOf(MarketAPI market) {
		if (market == null) return null;
		return market.isPlayerOwned() ? Factions.PLAYER : market.getFactionId();
	}

	/**
	 * The observers with eyes in the system: a fleet of theirs there (any
	 * fleet of the faction - siege, hunt, convoy, scout, guard, relief, a
	 * passing freighter), a colony there, a ground army on one of its worlds,
	 * or an unspawned route of theirs passing through. The only way a report is
	 * made (user, 2026-10-02: no radar).
	 */
	protected static Set<String> eyesIn(StarSystemAPI system) {
		Set<String> out = new HashSet<String>();
		for (CampaignFleetAPI fleet : system.getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.getFaction() == null) continue;
			if (fleet.isPlayerFleet()) {
				out.add(Factions.PLAYER);
				continue;
			}
			String id = observerOf(fleet.getFaction());
			if (id != null && !Factions.THREAT.equals(id)) out.add(id);
		}
		for (MarketAPI market : Misc.getMarketsInLocation(system)) {
			if (market.getPrimaryEntity() == null || ThreatMapFog.hidden(market)) continue;
			String id = observerOf(market);
			if (id == null || Factions.THREAT.equals(id)) continue;
			if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
			out.add(id);
		}
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
			if (front != null && !ThreatGroundFronts.isThreatOwned(front)) {
				String owner = ThreatGroundFronts.ownerOf(front);
				if (owner != null) out.add(owner);
			}
		}
		out.addAll(routesIn(system));
		return out;
	}

	/** Factions with an unspawned vanilla route in the system now (an off-screen siege, patrol or convoy). */
	protected static Set<String> routesIn(StarSystemAPI system) {
		Set<String> out = new HashSet<String>();
		try {
			com.fs.starfarer.api.impl.campaign.fleets.RouteManager rm =
					com.fs.starfarer.api.impl.campaign.fleets.RouteManager.getInstance();
			if (rm == null) return out;
			for (com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData route : rm.getRoutesInLocation(system)) {
				if (route == null || route.getActiveFleet() != null) continue;
				String id = route.getFactionId();
				if (id != null && !Factions.THREAT.equals(id)) out.add(id);
			}
		} catch (RuntimeException e) {
			// no route manager in this context: no route eyes
		}
		return out;
	}

	// ------------------------------------------------------------------
	// the day
	// ------------------------------------------------------------------

	/** The calendar day the sweep last ran; not saved - a reload sweeps once more, which only re-sees. */
	private static long sweptDay = Long.MIN_VALUE;

	/** From the war poll: the daily sweep, once per calendar day. */
	public static void poll() {
		if (!enabled()) return;
		long day = ThreatReach.today();
		if (day == sweptDay) return;
		sweptDay = day;
		advanceDay();
	}

	/**
	 * Once a day: every observer with eyes in a found Threat system sees it
	 * exactly; every other report stands and ages.
	 */
	public static void advanceDay() {
		if (!enabled()) return;
		List<String> observers = observers();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			if (!ThreatScouts.sectorKnows(systemId)) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
			if (system == null) continue;
			Set<String> eyes = eyesIn(system);
			for (String o : observers) {
				if (eyes.contains(o)) see(o, system, EYES);
			}
		}
		pruneDead();
		census();
	}

	/** Reports of systems with no hive left go: there is nothing there to plan against. */
	protected static void pruneDead() {
		for (Map<String, Report> mine : store().values()) {
			for (String systemId : new ArrayList<String>(mine.keySet())) {
				if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) mine.remove(systemId);
			}
		}
	}

	/** Once a month, each observer's picture in one line per observer: system, figure, age, source. */
	protected static void census() {
		if (!ThreatIncConfig.debugLogging()) return;
		Map<String, Long> last = ThreatIncData.map(KEY_LAST_CENSUS);
		Long when = last.get("all");
		if (when != null && Global.getSector().getClock().getElapsedDaysSince(when) < 30f) return;
		last.put("all", Global.getSector().getClock().getTimestamp());
		for (String o : observers()) {
			StringBuilder sb = new StringBuilder();
			for (String systemId : ThreatIncData.colonyMarkets().keySet()) {
				if (!ThreatScouts.sectorKnows(systemId)) continue;
				Report r = report(o, systemId);
				StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
				String name = system != null ? system.getName() : systemId;
				if (sb.length() > 0) sb.append("; ");
				if (r == null) {
					sb.append(name).append(" unknown");
				} else {
					sb.append(name).append(' ').append((int) r.totalFP()).append(" FP ")
							.append((int) r.age()).append(" d ").append(r.source)
							.append(o.equals(r.seenBy) ? "" : " from " + r.seenBy);
				}
			}
			if (sb.length() > 0) ThreatIncConfig.log("Intel census " + o + ": " + sb);
		}
	}

	// ------------------------------------------------------------------
	// sharing
	// ------------------------------------------------------------------

	/** Whose reports the observer reads besides its own: coalition partners; for the player, factions at Cooperative. */
	public static List<String> sharersOf(String observer) {
		List<String> out = new ArrayList<String>();
		if (observer == null) return out;
		if (Factions.PLAYER.equals(observer)) {
			FactionAPI player = Global.getSector().getPlayerFaction();
			for (String id : store().keySet()) {
				if (Factions.PLAYER.equals(id)) continue;
				if (player.getRelationshipLevel(id).isAtWorst(RepLevel.COOPERATIVE)) out.add(id);
			}
			return out;
		}
		out.addAll(partnersOf(observer));
		return out;
	}

	/** The NPC factions that pool reports with this one as they are made: its coalition partners (ThreatCoalition.partners). */
	public static List<String> partnersOf(String observer) {
		if (observer == null || Factions.PLAYER.equals(observer)) return new ArrayList<String>();
		return ThreatCoalition.partners(observer);
	}

	// ------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------

	/** Clock instant -> system id -> the live picture, when the fog is off; not saved. */
	private static final Map<String, Report> LIVE = new HashMap<String, Report>();
	private static long liveStamp = Long.MIN_VALUE;

	/**
	 * The observer's freshest picture of the system - its own, or a sharer's
	 * newer one - or null when it has never been seen. With the fog off, the
	 * system as it stands (the old exact read).
	 */
	public static Report report(String observer, String systemId) {
		if (systemId == null) return null;
		if (!enabled()) {
			long now = Global.getSector().getClock().getTimestamp();
			if (now != liveStamp) {
				LIVE.clear();
				liveStamp = now;
			}
			Report r = LIVE.get(systemId);
			if (r == null) {
				StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
				if (system == null) return null;
				r = picture(system, observer, EYES);
				LIVE.put(systemId, r);
			}
			return r;
		}
		if (observer == null) return null;
		Map<String, Report> mine = store().get(observer);
		Report best = mine != null ? mine.get(systemId) : null;
		for (String sharer : sharersOf(observer)) {
			Map<String, Report> theirs = store().get(sharer);
			Report r = theirs != null ? theirs.get(systemId) : null;
			if (r != null && (best == null || r.day > best.day)) best = r;
		}
		return best;
	}

	public static Report report(String observer, StarSystemAPI system) {
		return system != null ? report(observer, system.getId()) : null;
	}

	public static boolean known(String observer, String systemId) {
		return report(observer, systemId) != null;
	}

	/** Fleet points the observer last saw over the hive world (0 when never seen or none). */
	public static float worldFP(String observer, MarketAPI hive) {
		if (hive == null || hive.getStarSystem() == null) return 0f;
		Report r = report(observer, hive.getStarSystem().getId());
		return r != null ? r.worldFP(hive.getId()) : 0f;
	}

	/** Defense Swarm fleets the observer last saw over the hive world. */
	public static int worldFleets(String observer, MarketAPI hive) {
		if (hive == null || hive.getStarSystem() == null) return 0;
		Report r = report(observer, hive.getStarSystem().getId());
		return r != null ? r.worldFleets(hive.getId()) : 0;
	}

	/** Every Threat fleet point the observer last saw in the system. */
	public static float systemFP(String observer, String systemId) {
		Report r = report(observer, systemId);
		return r != null ? r.totalFP() : 0f;
	}

	/** Days since the observer's picture of the system was seen; Float.MAX_VALUE when never. */
	public static float age(String observer, String systemId) {
		Report r = report(observer, systemId);
		return r != null ? r.age() : Float.MAX_VALUE;
	}

	/** How far the picture is to be trusted after {@code moreDays} (travel, preparation): 0.5 ^ (age / half-life); 0 never seen. */
	public static float trust(String observer, String systemId, float moreDays) {
		Report r = report(observer, systemId);
		if (r == null) return 0f;
		return threatinc.rules.PlannerRules.trust(r.age(), moreDays, ThreatIncConfig.intelHalfLifeDays());
	}

	/** The board's figure for a report: "3,400 (41 d)", the age left off when seen today; "Unknown" when never seen. */
	public static String figure(Report r, float fp) {
		if (r == null) return "Unknown";
		String n = Misc.getWithDGS(Math.round(fp));
		int age = (int) r.age();
		return age < 1 ? n : n + " (" + age + " d)";
	}

	/** The age alone, for a line that prints its own figure: "today", "41 days ago". */
	public static String when(Report r) {
		if (r == null) return "never";
		int age = (int) r.age();
		return age < 1 ? "today" : age == 1 ? "yesterday" : age + " days ago";
	}

	/** Called on load: the not-saved live pictures go. */
	public static void forget() {
		LIVE.clear();
		liveStamp = Long.MIN_VALUE;
		sweptDay = Long.MIN_VALUE;
	}

	/** A new campaign (ThreatColonyManager.resetIncursion): every report goes. */
	public static void reset() {
		Global.getSector().getPersistentData().remove(KEY_REPORTS);
		Global.getSector().getPersistentData().remove(KEY_LAST_CENSUS);
		forget();
	}

	/** A system left the war (ThreatIncData.clearSystem): every observer's report of it goes. */
	public static void drop(String systemId) {
		if (systemId == null) return;
		for (Map<String, Report> mine : store().values()) mine.remove(systemId);
		LIVE.remove(systemId);
	}
}
