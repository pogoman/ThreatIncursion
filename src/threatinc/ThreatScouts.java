package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.Misc;

/**
 * Scouting parties - how the sector finds the hive (2026-09-24,
 * docs/strategy-layer.md "Finding the hive").
 *
 * <p>A Threat strike does not give away where it came from; it gives the
 * struck faction a LEAD. The faction sends a small scouting party to sweep
 * the uninhabited systems within scoutLeadRadiusLY of the strike's true
 * origin, nearest-first from home, as many parties as the sweep needs, until
 * the origin is found. A mobilised faction with no lead sweeps the uninhabited
 * systems within scoutRangeLY of its military worlds every scoutIntervalDays.
 *
 * <p>A scout that jumps into a system with a live hive colony reveals it -
 * to the whole sector, player included: {@link ThreatIncData#discoveredSystems}
 * is one shared list, and NPC war efforts act only on what it holds
 * ({@link #sectorKnows}). A hive in a system someone else lives in is seen by
 * the neighbours. A system swept clear is not swept again for
 * scoutMemoryDays; a lead ignores sweeps older than itself.
 *
 * <p>Knob hiveFogOfWar off restores the old rule: every strike names its
 * origin, NPC factions know every hive, no scouts fly.
 */
public class ThreatScouts {

	public static final String KEY_SCOUTS = "threatinc_scouts";
	public static final String KEY_SWEPT = "threatinc_sweptSystems";
	public static final String KEY_LEADS = "threatinc_scoutLeads";
	public static final String KEY_LAST_SWEEP = "threatinc_scoutLastSweep";
	public static final String SCOUT_FLAG = "$threatinc_scout";

	/** A party out sweeping; the route and fleet are the {@link ThreatScoutRoute.Party}'s. */
	public static class Scout extends ThreatScoutRoute.Party {
		public String factionId;
		/** The origin this sortie is following up; null for a routine sweep. */
		public String leadSystemId;
		/** A recon sortie (ThreatAttackPlanner, 2026-10-01): sent to look again at a found Threat system, leadSystemId. */
		public boolean recon;
		/** A patrol of its faction's budget (launchPatrols, 2026-10-05) rather than a lead's or a recon party. */
		public boolean patrol;
	}

	/** A Threat strike in flight a party saw and is carrying home: the strike's key (strikeKey). */
	public static class StrikeSeen {
		public String key;
	}

	/** A hive system a party saw and is carrying home (ThreatScoutRoute.Party.carried): the find and its picture. */
	public static class Find {
		public String systemId;
		public ThreatIntel.Report report;
	}

	/** A strike's origin a faction is looking for, and since when. */
	public static class Lead {
		public String systemId;
		public long timestamp;
	}

	@SuppressWarnings("unchecked")
	public static List<Scout> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_SCOUTS);
		if (!(val instanceof List)) {
			val = new ArrayList<Scout>();
			Global.getSector().getPersistentData().put(KEY_SCOUTS, val);
		}
		return (List<Scout>) val;
	}

	/** System id -> when a scout last swept it and found no hive. */
	public static Map<String, Long> swept() {
		return ThreatIncData.map(KEY_SWEPT);
	}

	/** Faction id -> the strike origins it is looking for. */
	public static Map<String, List<Lead>> leads() {
		return ThreatIncData.map(KEY_LEADS);
	}

	protected static Map<String, Long> lastSweep() {
		return ThreatIncData.map(KEY_LAST_SWEEP);
	}

	public static boolean enabled() {
		return ThreatIncConfig.hiveFogOfWar();
	}

	/**
	 * Whether the sector knows of the hive in this system - the gate on every
	 * NPC war effort against it. Not debug-bypassed: debug mode shows the
	 * player everything, but the NPC war still plays by the fog.
	 */
	public static boolean sectorKnows(String systemId) {
		if (!enabled()) return true;
		return systemId != null && ThreatIncData.discoveredSystems().contains(systemId);
	}

	public static boolean sectorKnows(MarketAPI hive) {
		return hive != null && hive.getStarSystem() != null && sectorKnows(hive.getStarSystem().getId());
	}

	/**
	 * Marks the system known to everyone; the first time, tells the player who
	 * found it. finderFactionId null = no one in particular.
	 */
	public static void reveal(String systemId, String finderFactionId) {
		if (systemId == null || ThreatIncData.discoveredSystems().contains(systemId)) return;
		ThreatIncData.markDiscovered(systemId);
		StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
		String where = system != null ? system.getNameWithLowercaseType() : systemId;
		FactionAPI finder = finderFactionId == null ? null : Global.getSector().getFaction(finderFactionId);
		ThreatNotice n = ThreatNotice.titled("Hive Found").good().icon(finder);
		if (finder == null) {
			n.line("The sector has found a Threat hive in the %s.", where);
		} else {
			n.line("%s has found a Threat hive in the %s.", ThreatNotice.faction(finder), where);
		}
		n.send();
		ThreatIncConfig.log("Hive found in " + where + " by " + finderFactionId);
	}

	/**
	 * A strike from originSystemId has hit this faction: it goes looking.
	 * Not since 2026-10-05 (the user; strikeLeads false): a strike mobilises
	 * and tells nothing of where it came from - a hive is found by a party
	 * that enters its system (onEnter), a colony beside it or an army on it
	 * (revealNeighbours). In hw15 and hw17 the first strike's lead found the
	 * home cluster within 0-5 months in six runs of six.
	 */
	public static void addLead(String factionId, String originSystemId) {
		if (!ThreatIncConfig.strikeLeads()) return;
		if (!enabled() || !mayScout(factionId) || originSystemId == null) return;
		if (sectorKnows(originSystemId)) return;
		List<Lead> list = leads().get(factionId);
		if (list == null) {
			list = new ArrayList<Lead>();
			leads().put(factionId, list);
		}
		for (Lead lead : list) {
			if (originSystemId.equals(lead.systemId)) return;
		}
		Lead lead = new Lead();
		lead.systemId = originSystemId;
		lead.timestamp = Global.getSector().getClock().getTimestamp();
		list.add(lead);
		ThreatIncConfig.log("Scout lead for " + factionId + ": strike origin " + originSystemId);
	}

	protected static boolean mayScout(String factionId) {
		if (factionId == null || Factions.PLAYER.equals(factionId) || Factions.THREAT.equals(factionId)) {
			return false;
		}
		if (ThreatWarState.excluded(factionId)) return false;
		FactionAPI faction = Global.getSector().getFaction(factionId);
		return faction != null && !faction.isNeutralFaction();
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	/** The humans' patrol census line (ThreatScoutRoute.census), with each mobilised faction's level. */
	public static void census() {
		StringBuilder lv = new StringBuilder();
		for (String f : ThreatWarState.warFactionIds()) {
			int l = ThreatScoutRoute.level(levelKey(f));
			if (l > 0) lv.append(lv.length() > 0 ? ", " : "").append(f).append(' ').append(l);
		}
		ROUTE.census("humans", lv.length() > 0 ? lv.toString() : "none raised");
	}

	public static void poll(Random random) {
		if (!enabled()) {
			// switched off mid-game: the parties out go home rather than sit on their patrol
			if (!all().isEmpty()) ROUTE.recallAll();
			return;
		}
		revealNeighbours();
		// leads an older save still holds are dropped with the rule
		if (!ThreatIncConfig.strikeLeads() && !leads().isEmpty()) leads().clear();
		for (Scout s : new ArrayList<Scout>(all())) {
			ROUTE.advance(s);
		}
		pruneLeads();
		launchSorties(random);
	}

	/** RESET War: the parties out fade, and every sweep, lead and sweep clock is forgotten. */
	public static void reset() {
		ROUTE.clearAll();
		for (String key : new String[] { KEY_SCOUTS, KEY_SWEPT, KEY_LEADS, KEY_LAST_SWEEP }) {
			Global.getSector().getPersistentData().remove(key);
		}
	}

	/**
	 * A hive in a system where anyone else keeps a colony is no secret; nor is
	 * one another faction already has a ground front on - a 0.6.2 save carries
	 * fronts on hives the fog of war never marked found, and they had no
	 * follow-up sieges until a scout came by (rc1 review).
	 */
	protected static void revealNeighbours() {
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.discoveredSystems().contains(systemId)) continue;
			if (!hasLiveHive(systemId)) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
			if (system == null) continue;
			String fronted = null;
			for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(systemId)) {
				ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
				if (front != null && !ThreatGroundFronts.isThreatOwned(front)) fronted = ThreatGroundFronts.ownerOf(front);
			}
			if (fronted != null) {
				reveal(systemId, fronted);
				continue;
			}
			for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
				if (ThreatMapFog.hidden(market) || market.getPrimaryEntity() == null) continue;
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
				reveal(systemId, market.getFactionId());
				break;
			}
		}
	}

	protected static boolean hasLiveHive(String systemId) {
		return !ThreatIncData.getLiveColonyMarkets(systemId).isEmpty();
	}

	/** The route walker, with what the sector's parties do at a stop. */
	protected static final ThreatScoutRoute<Scout> ROUTE = new ThreatScoutRoute<Scout>() {
		protected List<Scout> all() {
			return ThreatScouts.all();
		}
		protected String describe(Scout s) {
			return "Scout of " + s.factionId;
		}
		protected boolean knownStop(String systemId) {
			// a stop someone else found meanwhile
			return ThreatIncData.discoveredSystems().contains(systemId);
		}
		protected boolean onEnter(Scout s, StarSystemAPI system, long now) {
			if (!hasLiveHive(system.getId())) return s.recon; // a recon's one stop is done either way
			if (ThreatIncConfig.carriedIntel()) {
				// seen, not yet known: the find and its picture sail home with
				// the party and are lost with it (2026-10-05, the user)
				Find find = new Find();
				find.systemId = system.getId();
				find.report = ThreatIntel.look(s.factionId, system, ThreatIntel.SCOUT);
				carry(s, find);
				ThreatIncConfig.log("Scout of " + s.factionId + " saw a hive in the " + system.getName()
						+ " and turns for home with it");
				return true;
			}
			reveal(system.getId(), s.factionId);
			// what it saw is its faction's report (ThreatIntel, the fog of war)
			ThreatIntel.see(s.factionId, system, ThreatIntel.SCOUT);
			return true; // the report is what counts
		}
		/** A system where its own faction, or one not hostile to it, keeps a colony: the relay carries the find to all. */
		protected boolean friendly(Scout s, StarSystemAPI system) {
			FactionAPI mine = Global.getSector().getFaction(s.factionId);
			for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
				if (ThreatMapFog.hidden(market) || market.getPrimaryEntity() == null) continue;
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
				if (s.factionId.equals(market.getFactionId())) return true;
				if (mine != null && !mine.isHostileTo(market.getFactionId())) return true;
			}
			return false;
		}
		protected void onLost(Scout s) {
			if (!s.patrol) return;
			ThreatScoutRoute.lost(levelKey(s.factionId));
			ThreatIncConfig.log("Patrol of " + s.factionId + " did not come back: patrols now "
					+ (int) patrolSize(s.factionId) + " FP each");
		}
		protected void deliver(Scout s, Object seen) {
			if (seen instanceof StrikeSeen) {
				// the warning is home: the strike is seen, if it still flies unseen
				IncursionManager.strikeReported(((StrikeSeen) seen).key, "a patrol of " + s.factionId);
				return;
			}
			if (!(seen instanceof Find)) return;
			Find find = (Find) seen;
			StarSystemAPI system = ThreatScoutRoute.systemById(find.systemId);
			if (system == null) return;
			if (hasLiveHive(find.systemId)) reveal(find.systemId, s.factionId);
			ThreatIntel.file(s.factionId, system, find.report, ThreatIntel.SCOUT);
		}
		protected void onStay(Scout s, StarSystemAPI system, long now) {
			swept().put(system.getId(), now);
		}
		protected String stayVerb() {
			return "sweeping";
		}
		protected MarketAPI homeOf(Scout s) {
			MarketAPI home = Global.getSector().getEconomy().getMarket(s.homeMarketId);
			// a home that changed hands is no home
			return home != null && s.factionId.equals(home.getFactionId()) ? home : null;
		}
		protected String returnLabel(Scout s, MarketAPI home) {
			return "returning to " + home.getName();
		}
	};

	/** A lead ends when its origin is known or its hive is gone. */
	protected static void pruneLeads() {
		for (String factionId : new ArrayList<String>(leads().keySet())) {
			List<Lead> list = leads().get(factionId);
			if (list != null) {
				for (Lead lead : new ArrayList<Lead>(list)) {
					if (sectorKnows(lead.systemId) || !hasLiveHive(lead.systemId) || !mayScout(factionId)) {
						list.remove(lead);
					}
				}
			}
			if (list == null || list.isEmpty()) leads().remove(factionId);
		}
	}

	// ------------------------------------------------------------------
	// sorties
	// ------------------------------------------------------------------

	protected static void launchSorties(Random random) {
		List<String> factions = new ArrayList<String>(leads().keySet());
		for (String id : ThreatWarState.warFactionIds()) {
			if (!factions.contains(id)) factions.add(id);
		}
		long now = Global.getSector().getClock().getTimestamp();
		// as many parties as there are systems to sweep (2026-09-29: a faction
		// flew at most scoutMaxPerFaction, 2, and launched one per poll). Each
		// launch takes its stops off the candidates (ROUTE.taken), so the loops
		// end when nothing is left to sweep
		for (String factionId : factions) {
			if (!mayScout(factionId)) continue;
			List<Lead> list = leads().get(factionId);
			if (list != null && !list.isEmpty()) {
				for (Lead lead : new ArrayList<Lead>(list)) {
					Boolean launched;
					do {
						launched = launchLead(factionId, lead);
					} while (Boolean.TRUE.equals(launched));
					// nothing left to sweep and no one out looking: the lead is spent.
					// A party the depot cannot pay for yet (null) keeps the lead
					if (launched != null && !leadInFlight(factionId, lead.systemId)) list.remove(lead);
				}
				continue;
			}
			if (!ThreatWarState.isAtWar(factionId)) continue;
			if (ThreatIncConfig.patrolsEnabled()) {
				launchPatrols(factionId, random);
				continue;
			}
			Long last = lastSweep().get(factionId);
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
					< ThreatIncConfig.scoutIntervalDays()) continue;
			lastSweep().put(factionId, now);
			launchRoutine(factionId, random);
		}
	}

	/**
	 * RECON (2026-10-01, ThreatAttackPlanner): one party from the faction's
	 * nearest military world to a Threat system already found, to see it
	 * again (ThreatIntel.see on arrival) and come home. Paid as any party; the
	 * party, or null when scouting is off, the faction may not scout, one is
	 * already on its way there, the system is one a GO_TO never reaches
	 * (unreachable), or the depot cannot pay.
	 */
	public static Scout recon(String factionId, StarSystemAPI system) {
		if (!enabled() || system == null || !mayScout(factionId) || unreachable(system)) return null;
		if (reconInFlight(factionId, system.getId())) return null;
		MarketAPI home = nearestBase(factionId, system.getLocation());
		if (home == null) return null;
		List<String> route = new ArrayList<String>();
		route.add(system.getId());
		Scout s = launch(factionId, home, route, system.getId());
		if (s != null) {
			s.recon = true;
			ThreatIncConfig.log("Recon: " + factionId + " sends a party from " + home.getName() + " to look at the "
					+ system.getName() + " again (report " + ThreatIntel.when(ThreatIntel.report(factionId, system))
					+ ")");
		}
		return s;
	}

	/** Whether a recon party of the faction is on its way to the system. */
	public static boolean reconInFlight(String factionId, String systemId) {
		for (Scout s : all()) {
			if (s.recon && !s.returning && factionId.equals(s.factionId) && systemId.equals(s.leadSystemId)) return true;
		}
		return false;
	}

	protected static boolean leadInFlight(String factionId, String systemId) {
		for (Scout s : all()) {
			if (!s.returning && factionId.equals(s.factionId) && systemId.equals(s.leadSystemId)) return true;
		}
		return false;
	}

	/**
	 * Sweeps the area around a strike's origin from the faction's nearest
	 * military world. True launched; false nothing (left) to sweep; null a
	 * route waits on a depot that cannot pay for the party yet.
	 */
	protected static Boolean launchLead(String factionId, Lead lead) {
		StarSystemAPI origin = ThreatScoutRoute.systemById(lead.systemId);
		if (origin == null) return false;
		MarketAPI home = nearestBase(factionId, origin.getLocation());
		if (home == null) return false;
		List<String> route = planRoute(home, origin.getLocation(),
				ThreatIncConfig.scoutLeadRadiusLY(), lead.timestamp);
		if (route.isEmpty()) return false;
		return launch(factionId, home, route, lead.systemId) != null ? Boolean.TRUE : null;
	}

	/**
	 * Sweeps the unexplored space around every one of the faction's military
	 * worlds (2026-09-29: one world per sweep), a party per route until each
	 * has nothing left in range.
	 */
	protected static void launchRoutine(String factionId, Random random) {
		List<MarketAPI> bases = new ArrayList<MarketAPI>();
		for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
			if (market.getStarSystem() != null && IncursionManager.hasMilitary(market)) bases.add(market);
		}
		Collections.shuffle(bases, random);
		for (MarketAPI home : bases) {
			while (true) {
				List<String> route = planRoute(home, home.getStarSystem().getLocation(),
						ThreatIncConfig.scoutRangeLY(), 0L);
				if (route.isEmpty() || launch(factionId, home, route, null) == null) break;
			}
		}
	}

	protected static String levelKey(String factionId) {
		return "f:" + factionId;
	}

	/** The size a faction's patrols are built at: scoutFleetPoints doubled for every loss not yet forgotten. */
	protected static float patrolSize(String factionId) {
		return ThreatIncConfig.scoutFleetPoints() * (float) Math.pow(2, ThreatScoutRoute.level(levelKey(factionId)));
	}

	/**
	 * PATROLS (2026-10-05, the user): a mobilised faction keeps patrols out
	 * instead of sweeping a radius every scoutIntervalDays. Its budget is
	 * patrolFPPerBase a military world, flown as patrols of patrolSize - many
	 * small ones until they start going missing, then fewer and heavier
	 * (ThreatScoutRoute.level). Each takes the nearest patrolStops systems not
	 * swept within scoutMemoryDays from its base, with no radius: the ring
	 * widens as the near systems are swept and comes round again as the
	 * memory lapses. A faction that is consolidating keeps them within
	 * scoutRangeLY.
	 */
	protected static void launchPatrols(String factionId, Random random) {
		ThreatScoutRoute.calm(levelKey(factionId));
		List<MarketAPI> bases = new ArrayList<MarketAPI>();
		for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
			if (market.getStarSystem() != null && IncursionManager.hasMilitary(market)) bases.add(market);
		}
		if (bases.isEmpty()) return;
		float size = patrolSize(factionId);
		int want = Math.max(1, (int) (ThreatIncConfig.patrolFPPerBase() * bases.size() / size));
		int out = 0;
		for (Scout s : all()) {
			if (s.patrol && factionId.equals(s.factionId)) out++;
		}
		boolean close = ThreatFactionStance.enabled()
				&& ThreatFactionStance.stance(factionId) == ThreatFactionStance.CONSOLIDATE;
		float radius = close ? ThreatIncConfig.scoutRangeLY() : Float.MAX_VALUE;
		int stops = Math.max(1, ThreatIncConfig.patrolStops());
		Collections.shuffle(bases, random);
		boolean any = true;
		while (out < want && any) {
			any = false;
			for (MarketAPI home : bases) {
				if (out >= want) break;
				List<String> route = planRoute(home, home.getStarSystem().getLocation(), radius, 0L);
				if (route.isEmpty()) continue;
				if (route.size() > stops) route = new ArrayList<String>(route.subList(0, stops));
				Scout s = launch(factionId, home, route, null, size, true);
				if (s == null) continue;
				out++;
				any = true;
			}
		}
	}

	/** A strike's key for a sighting: its route's seed, which the save keeps. */
	protected static String strikeKey(ThreatStrikeFGI strike) {
		Long seed = strike.getRoute() != null ? strike.getRoute().getSeed() : null;
		return "strike:" + (seed != null ? seed.toString() : "i" + System.identityHashCode(strike));
	}

	/**
	 * A Threat strike no world of the humans sees is at {@code where} today
	 * (IncursionManager.detectStrikes): every party in the same system, or
	 * within patrolSightLY of it in hyperspace, sees it. Against a strike
	 * flying as a route the meeting is settled by speed and strength
	 * (ThreatScoutRoute.meetAbstract); a party that gets away turns for home,
	 * and the strike is seen when it is in a friendly system (deliver).
	 */
	public static void sight(ThreatStrikeFGI strike, com.fs.starfarer.api.campaign.LocationAPI where, Vector2f hyper,
			float fp, boolean abstractForce) {
		if (!ThreatIncConfig.patrolsEnabled() || where == null) return;
		String key = strikeKey(strike);
		for (Scout s : new ArrayList<Scout>(all())) {
			CampaignFleetAPI fleet = s.fleet;
			if (fleet == null || !fleet.isAlive() || !ThreatScoutRoute.near(fleet, where, hyper)) continue;
			boolean first = true;
			if (s.carried != null) {
				for (Object o : s.carried) {
					if (o instanceof StrikeSeen && key.equals(((StrikeSeen) o).key)) first = false;
				}
			}
			if (first) {
				StrikeSeen seen = new StrikeSeen();
				seen.key = key;
				ROUTE.carry(s, seen);
			}
			int outcome = ThreatScoutRoute.RUNS;
			if (abstractForce && strike.getRoute() != null) {
				outcome = ThreatScoutRoute.meetAbstract(fleet, fp, ThreatIncConfig.patrolBurnStrike(), strike.getRoute(),
						"Scout of " + s.factionId, "a Threat strike");
			}
			if (outcome == ThreatScoutRoute.DESTROYED) continue; // advance books it lost
			if (outcome == ThreatScoutRoute.RUNS && !s.returning) {
				if (first) {
					ThreatIncConfig.log("Scout of " + s.factionId + " saw a Threat strike of " + (int) fp
							+ " FP and turns for home with it");
				}
				ROUTE.goHome(s);
			}
		}
	}

	protected static MarketAPI nearestBase(String factionId, Vector2f where) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		boolean bestMil = false;
		for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
			if (market.getStarSystem() == null) continue;
			boolean mil = IncursionManager.hasMilitary(market);
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), where);
			// a military world first; any colony if the faction has none
			if (best == null || (mil && !bestMil) || (mil == bestMil && d < bestDist)) {
				best = market;
				bestDist = d;
				bestMil = mil;
			}
		}
		return best;
	}

	/**
	 * A route (ThreatScoutRoute.nearestFirst) through the systems within radius
	 * of the centre, from home: unknown, uninhabited, with a planet, not on another scout's route,
	 * and not swept clear lately - since the lead began (leadSince), else
	 * within scoutMemoryDays.
	 */
	protected static List<String> planRoute(MarketAPI home, Vector2f centre, float radiusLY, long leadSince) {
		List<String> taken = ROUTE.taken();
		List<StarSystemAPI> candidates = new ArrayList<StarSystemAPI>();
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			String id = system.getId();
			if (system.getCenter() == null || unreachable(system)) continue;
			if (Misc.getDistanceLY(system.getLocation(), centre) > radiusLY) continue;
			if (ThreatIncData.discoveredSystems().contains(id) || taken.contains(id)) continue;
			if (sweptLately(id, leadSince)) continue;
			if (!hasPlanet(system) || inhabited(system)) continue;
			candidates.add(system);
		}
		return ThreatScoutRoute.nearestFirst(candidates, home.getStarSystem().getLocation());
	}

	protected static boolean sweptLately(String systemId, long leadSince) {
		Long when = swept().get(systemId);
		if (when == null) return false;
		if (leadSince != 0L) return when >= leadSince;
		return Global.getSector().getClock().getElapsedDaysSince(when) < ThreatIncConfig.scoutMemoryDays();
	}

	/** Abyssal pockets and hidden-theme systems: a fleet's GO_TO never arrives (a scout sat 132 days on one). */
	protected static boolean unreachable(StarSystemAPI system) {
		return system.hasTag(Tags.SYSTEM_ABYSSAL) || system.hasTag(Tags.THEME_HIDDEN)
				|| system.hasTag(Tags.SYSTEM_CUT_OFF_FROM_HYPER);
	}

	protected static boolean hasPlanet(StarSystemAPI system) {
		for (PlanetAPI planet : system.getPlanets()) {
			if (!planet.isStar()) return true;
		}
		return false;
	}

	/** Anyone but the swarm keeps a colony there - revealNeighbours covers those. */
	protected static boolean inhabited(StarSystemAPI system) {
		for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
			if (ThreatMapFog.hidden(market) || market.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			return true;
		}
		return false;
	}

	protected static Scout launch(String factionId, MarketAPI home, List<String> route, String leadSystemId) {
		return launch(factionId, home, route, leadSystemId, ThreatIncConfig.scoutFleetPoints(), false);
	}

	/** As above, a party of {@code fp} fleet points (a patrol's size, patrolSize). */
	protected static Scout launch(String factionId, MarketAPI home, List<String> route, String leadSystemId, float fp, boolean patrol) {
		StarSystemAPI homeSystem = home.getStarSystem();
		if (homeSystem == null || home.getPrimaryEntity() == null) return null;
		// (2026-09-29: closed economy - a party sailed for free.) It pays what
		// any NPC sortie pays, from the home's spendable reserve: supplies at
		// the voyage rate per point (a point is FP_PER_RESPONSE_DIFFICULTY
		// fleet points) and fuel per point per light-year of its route. Paid
		// in full or it does not sail; what survives is refunded home
		// (ScoutReturn, ThreatReturns.settle)
		float[] cost = voyageCost(fp, routeLY(home, route));
		if (ThreatReserves.spendable(home, Commodities.FUEL) < cost[0]
				|| ThreatReserves.spendable(home, Commodities.SUPPLIES) < cost[1]) {
			return null;
		}
		// and the hulls: a party of warships draws on the faction's pool (ThreatHulls);
		// without the hulls free it does not sail (hw30: scouts committed unchecked
		// put Tri-Tachyon 2,300 FP out on a 200 FP pool)
		if (ThreatHulls.enabled() && !Factions.THREAT.equals(factionId)
				&& ThreatHulls.freeFP(factionId) < fp) {
			return null;
		}
		FleetParamsV3 params = new FleetParamsV3(
				home,
				home.getLocationInHyperspace(),
				factionId,
				null,
				FleetTypes.PATROL_SMALL,
				fp,        // combat
				0f,        // freighters
				fp * 0.2f, // tankers: it goes a long way
				0f, 0f, 0f,
				0f);
		// the points paid for, not the market's fleet-size multiplier on top
		// (2026-09-29: closed economy)
		params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		homeSystem.addEntity(fleet);
		fleet.setLocation(home.getPrimaryEntity().getLocation().x, home.getPrimaryEntity().getLocation().y);
		fleet.setName("Scouting Party");
		fleet.setNoFactionInName(false);
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(SCOUT_FLAG, true);
		// it looks, it does not fight: no picking battles, no borrowing it
		mem.set(MemFlags.MEMORY_KEY_MAKE_NON_AGGRESSIVE, true);
		mem.set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
		float fuel = ThreatReserves.drawSpendable(home, Commodities.FUEL, cost[0]);
		float supplies = ThreatReserves.drawSpendable(home, Commodities.SUPPLIES, cost[1]);
		ThreatReturns.provision(fleet, home.getId(), fuel, supplies);
		fleet.addEventListener(new ScoutReturn());

		Scout s = new Scout();
		s.fleet = fleet;
		s.factionId = factionId;
		s.homeMarketId = home.getId();
		s.route = route;
		s.leg = 0;
		s.leadSystemId = leadSystemId;
		s.patrol = patrol;
		all().add(s);
		ROUTE.sendTo(s, ThreatScoutRoute.systemById(route.get(0)));

		ThreatIncConfig.log((s.patrol ? "Patrol of " : "Scouting party of ") + factionId + " from " + home.getName()
				+ (leadSystemId != null ? " (lead)" : s.patrol ? " (" + (int) fp + " FP)" : " (sweep)") + ": " + route
				+ " (" + (int) fuel + " fuel, " + (int) supplies + " supplies drawn)");
		return s;
	}

	/** [fuel, supplies] a party of this many fleet points pays to sail this far, as a task force pays. */
	protected static float[] voyageCost(float fp, float ly) {
		float points = fp / IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return new float[] { points * ly * ThreatIncConfig.expeditionFuelPerPointLY(),
				points * ThreatIncConfig.expeditionSuppliesPerPoint() };
	}

	/** Light-years of the route out: home to its first stop, then stop to stop. */
	protected static float routeLY(MarketAPI home, List<String> route) {
		float ly = 0f;
		Vector2f at = home.getLocationInHyperspace();
		for (String id : route) {
			StarSystemAPI system = ThreatScoutRoute.systemById(id);
			if (system == null) continue;
			ly += Misc.getDistanceLY(at, system.getLocation());
			at = system.getLocation();
		}
		return ly;
	}

	/**
	 * On a scouting party: home again, what it drew is settled as any
	 * sortie's is (ThreatReturns.settle - the hulls' share back at the
	 * strength that survived). A party whose home changed hands, or that was
	 * destroyed, gets nothing back.
	 */
	public static class ScoutReturn implements com.fs.starfarer.api.campaign.listeners.FleetEventListener {
		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason reason, Object param) {
			if (reason != com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.REACHED_DESTINATION) {
				return;
			}
			String homeId = ThreatReturns.homeOf(fleet);
			MarketAPI home = homeId != null ? Global.getSector().getEconomy().getMarket(homeId) : null;
			if (home == null || fleet.getFaction() == null
					|| !fleet.getFaction().getId().equals(home.getFactionId())) {
				return;
			}
			ThreatReturns.settle(fleet, home);
			// settled once: a second despawn report finds nothing to refund
			fleet.getMemoryWithoutUpdate().unset(ThreatReturns.MEM_HOME);
		}

		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
				com.fs.starfarer.api.campaign.BattleAPI battle) {
		}
	}
}
