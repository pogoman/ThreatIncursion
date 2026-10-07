package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * THE SWARM'S FOG OF WAR (2026-10-01, user's call; docs/threat-fog.md) - the
 * mirror of {@link ThreatIntel}. The hive no longer reads human attacks,
 * staging depots, forward guards and a target's defence live from any
 * distance: it keeps REPORTS, made only where it can see.
 *
 * <p>EYES - a system with a live hive colony, any live Threat fleet
 * (garrisons, Scouting Swarms, raiders, Defend stations, spawned strike
 * fleets), an unspawned strike route passing through it, or a Threat ground
 * front: exact. SCOUT - a Scouting Swarm entering a system looks at every
 * human place there ({@link #scouted}), exact. PICKET (user, 2026-10-04) -
 * a human attack force in hyperspace within swarmPicketLY of a live hive,
 * exact: the humans' strike picket (ThreatFrontlines.detectedAt), mirrored.
 * It sees forces in flight only. No radar otherwise (user, 2026-10-02): a
 * place is real time while a Threat ship is in its system, and from the day
 * the last one leaves it stands and ages. Battles need nothing new: a Threat
 * fleet in a fight is in the system.
 *
 * <p>Two records. A CONTACT per human attack force seen - a siege, a hunt, a
 * support or defend sortie, a raid - bound for a hive system, at the fleet
 * points ThreatPosture.attacksBySystem counted live; it counts toward that
 * system for swarmContactDays after it was last seen and goes when its force
 * ends (the swarm sees it leave). A PLACE per human base or world seen, with
 * what the swarm's readers need from it: the staged threat and its target
 * (ThreatPosture B, ThreatStance's "staging against us"), a forward base's
 * guards (F), a military base's reach (IncursionManager.siegeBases) and the
 * defence a strike there meets (IncursionManager.targetDefence). A place's
 * figures never decay; its TRUST does, 0.5 ^ (age / intelHalfLifeDays), the
 * humans' formula and knob.
 *
 * <p>A fleet's own judgement on the spot stays live (the orbit contest, strike
 * passes, the leashes), as do world facts in a charted system. Knob
 * swarmFogOfWar off: every reader reads live, as before.
 */
public final class ThreatSwarmIntel {

	private ThreatSwarmIntel() {
	}

	/** "contacts" -> key -> {@link Contact}; "places" -> market id -> {@link Place}. */
	public static final String KEY = "threatinc_swarmIntel";
	public static final String KEY_LAST_CENSUS = "threatinc_swarmIntelLastCensus";
	protected static final String CONTACTS = "contacts";
	protected static final String PLACES = "places";
	/** Store flag: this save's charted systems are seeded (seedCharted); cleared while the fog is off. */
	protected static final String SEEDED = "seeded";
	/** System id -> day: systems seeded with scouting off (seedUnscouted), once each; cleared while the fog is off. */
	protected static final String UNSCOUTED = "unscouted";

	public static final String EYES = "eyes", PICKET = "picket", SCOUT = "scout", PATROL = "patrol";

	/** One human attack force the swarm has seen coming. */
	public static class Contact {
		/** "siege:" + faction + ":" + the siege's route seed, or "order:" + the order's fleet id. */
		public String key, factionId;
		/** The hive system it is bound for. */
		public String systemId;
		/** {@link #EYES} or {@link #PICKET}: how it was last seen ("radar" in a save from before 2026-10-02). */
		public String source;
		/** Fleet points as ThreatPosture.attacksBySystem counts them, when last seen. */
		public float fp;
		/** {@link ThreatPosture#today()} of its first and its last sighting. */
		public float firstDay, day;
	}

	/** One human base or world the swarm has seen, and what it saw there. */
	public static class Place {
		public String marketId, systemId, factionId;
		/** {@link #EYES} or {@link #SCOUT} ("radar" in a save from before 2026-10-02). */
		public String source;
		/** The hive system it was staging for (ThreatConvoys.stagingHive); null for none. */
		public String stagesFor;
		/** {@link ThreatPosture#today()} when seen. */
		public float day;
		/** ThreatPosture.stagedBy toward stagesFor; 0 for any but a mobilised faction's siege base (siegeBasesFor). */
		public float stagedFP;
		/** A forward base's live guards outside the hive system it faces, as ThreatPosture.read's F counts them. */
		public float guardsFP;
		/** A military base's reach as the spread weighs it (IncursionManager.liveSiegeBaseReachLY); 0 for any other world. */
		public float reachLY;
		/** The defence a strike there meets, in vanilla strength units (IncursionManager.liveTargetDefence). */
		public float defenceFP;
		/** {@link ThreatPosture#today()} its figures were last logged (record); 0 for never. */
		public float loggedDay;
	}

	/** A place's moved figures are logged at most this often; a new staging target at once. */
	protected static final float RELOG_DAYS = 30f;

	public static boolean enabled() {
		return ThreatIncConfig.swarmFogOfWar();
	}

	protected static float today() {
		return ThreatPosture.today();
	}

	// ------------------------------------------------------------------
	// the store
	// ------------------------------------------------------------------

	protected static Map<String, Object> store() {
		return ThreatIncData.<Object>map(KEY);
	}

	@SuppressWarnings("unchecked")
	protected static <T> Map<String, T> section(String name) {
		Map<String, Object> store = store();
		Object val = store.get(name);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, T>();
			store.put(name, val);
		}
		return (Map<String, T>) val;
	}

	protected static Map<String, Contact> contactMap() {
		return ThreatSwarmIntel.<Contact>section(CONTACTS);
	}

	protected static Map<String, Place> placeMap() {
		return ThreatSwarmIntel.<Place>section(PLACES);
	}

	// ------------------------------------------------------------------
	// seeing
	// ------------------------------------------------------------------

	/** The calendar day (ThreatReach.today) the senses below were read for; not saved. */
	private static long senseDay = Long.MIN_VALUE;
	/** Systems with standing eyes: a live hive colony, a Threat ground front, an unspawned strike route. */
	private static final Set<String> STANDING = new HashSet<String>();
	/** System id -> whether a live Threat fleet was in it when first asked today. */
	private static final Map<String, Boolean> FLEET_EYES = new HashMap<String, Boolean>();
	/** Where the hive picket stands: each system with a live hive colony, in hyperspace. */
	private static final List<Vector2f> PICKET_SITES = new ArrayList<Vector2f>();

	/** Reads the standing eyes once a day ({@code force}: now, for the sweep). */
	protected static void senses(boolean force) {
		long day = ThreatReach.today();
		if (!force && day == senseDay) return;
		senseDay = day;
		STANDING.clear();
		FLEET_EYES.clear();
		PICKET_SITES.clear();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			List<MarketAPI> hives = ThreatIncData.getLiveColonyMarkets(systemId);
			if (hives.isEmpty()) continue;
			STANDING.add(systemId);
			// a system's hives share one place in hyperspace
			for (MarketAPI hive : hives) {
				if (hive == null || hive.getPrimaryEntity() == null) continue;
				PICKET_SITES.add(new Vector2f(hive.getLocationInHyperspace()));
				break;
			}
		}
		for (ThreatGroundFronts.GroundFront front : ThreatGroundFronts.fronts().values()) {
			if (!ThreatGroundFronts.isThreatOwned(front)) continue;
			MarketAPI m = ThreatGroundFronts.resolveMarket(front.marketId);
			if (m != null && m.getStarSystem() != null) STANDING.add(m.getStarSystem().getId());
		}
		// a strike far from the player flies as a route (IncursionManager.detectStrikes)
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isSpawnedFleets()) continue;
			LocationAPI loc = routeLocation(strike.getRoute());
			if (loc instanceof StarSystemAPI) STANDING.add(loc.getId());
		}
	}

	/** Where an abstract route is now, or null. */
	protected static LocationAPI routeLocation(RouteManager.RouteData route) {
		if (route == null || route.getCurrent() == null) return null;
		try {
			return route.getCurrent().getCurrentContainingLocation();
		} catch (RuntimeException e) {
			// a segment with neither end: nowhere to see it
			return null;
		}
	}

	/** Where an abstract route is now in hyperspace, or null. */
	protected static Vector2f routeHyper(RouteManager.RouteData route) {
		if (route == null || route.getCurrent() == null) return null;
		try {
			return route.getInterpolatedHyperLocation();
		} catch (RuntimeException e) {
			return null;
		}
	}

	protected static boolean threatFleet(CampaignFleetAPI f) {
		return f != null && f.isAlive() && f.getFaction() != null
				&& Factions.THREAT.equals(f.getFaction().getId()) && f.getFleetPoints() > 0f;
	}

	/** Whether the swarm has eyes in the system: standing ones, or a live Threat fleet there. */
	protected static boolean eyesIn(StarSystemAPI system) {
		if (STANDING.contains(system.getId())) return true;
		Boolean fleet = FLEET_EYES.get(system.getId());
		if (fleet == null) {
			fleet = Boolean.FALSE;
			boolean carried = ThreatIncConfig.carriedIntel();
			for (CampaignFleetAPI f : system.getFleets()) {
				// a patrol or a scout out there sees, and must bring it home (ThreatSwarmPatrols)
				if (carried && ThreatSwarmPatrols.carrier(f)) continue;
				if (threatFleet(f)) {
					fleet = Boolean.TRUE;
					break;
				}
			}
			FLEET_EYES.put(system.getId(), fleet);
		}
		return fleet.booleanValue();
	}

	/**
	 * Whether the swarm sees what is at {@code where}: {@link #EYES} in a
	 * system it has eyes in, else null (a place or convoy is never seen from
	 * hyperspace; an attack force is, by the picket - the two-argument sees).
	 */
	public static String sees(LocationAPI where) {
		senses(false);
		return where instanceof StarSystemAPI && eyesIn((StarSystemAPI) where) ? EYES : null;
	}

	/** Whether the hyperspace point is within swarmPicketLY of a live hive. */
	protected static boolean inPicket(Vector2f at) {
		// patrols are the hive's eyes in hyperspace now (ThreatSwarmPatrols, 2026-10-05)
		if (ThreatSwarmPatrols.enabled()) return false;
		if (at == null || PICKET_SITES.isEmpty()) return false;
		float range = ThreatIncConfig.swarmPicketLY();
		if (range <= 0f) return false;
		for (Vector2f site : PICKET_SITES) {
			if (Misc.getDistanceLY(site, at) <= range) return true;
		}
		return false;
	}

	/**
	 * Whether the swarm sees a human attack force at {@code where}:
	 * {@link #EYES} in a system it has eyes in; {@link #PICKET} in hyperspace
	 * at {@code hyper}, within swarmPicketLY of a live hive; else null.
	 */
	public static String sees(LocationAPI where, Vector2f hyper) {
		String eyes = sees(where);
		if (eyes != null) return eyes;
		return where != null && where.isHyperspace() && inPicket(hyper) ? PICKET : null;
	}

	/** The better of two sightings: eyes, then the picket, over none. */
	protected static String better(String a, String b) {
		if (EYES.equals(a) || EYES.equals(b)) return EYES;
		return a != null ? a : b;
	}

	// ------------------------------------------------------------------
	// the day
	// ------------------------------------------------------------------

	/** The calendar day the sweep last ran; not saved - a reload sweeps once more, which only re-sees. */
	private static long sweptDay = Long.MIN_VALUE;

	/**
	 * From the war poll: the daily sweep, once per calendar day. With the fog
	 * off, the seeding flags go (fogOff), so switching it back on seeds again.
	 */
	public static void poll() {
		if (!enabled()) {
			fogOff();
			return;
		}
		long day = ThreatReach.today();
		if (day == sweptDay) return;
		sweptDay = day;
		sweep();
	}

	/** The fog is off: the seeding flags go, without creating the store. */
	@SuppressWarnings("rawtypes")
	protected static void fogOff() {
		Object val = Global.getSector().getPersistentData().get(KEY);
		if (!(val instanceof Map)) return;
		Map store = (Map) val;
		store.remove(SEEDED);
		store.remove(UNSCOUTED);
	}

	/**
	 * Once a day: the attacks the swarm sees coming, the human places it sees,
	 * the charted systems on the save's first sweep (seedCharted), every
	 * inhabited system once with scouting off (seedUnscouted), the convoys it
	 * sees (ThreatRaiders.sweep), and the monthly census.
	 */
	protected static void sweep() {
		senses(true);
		float day = today();
		Set<String> hive = new HashSet<String>();
		for (String id : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (!ThreatIncData.getLiveColonyMarkets(id).isEmpty()) hive.add(id);
		}
		sweepContacts(hive, day);
		sweepPlaces(day);
		// once per save (and once each time the fog is switched back on), not per load
		if (!Boolean.TRUE.equals(store().get(SEEDED))) {
			store().put(SEEDED, Boolean.TRUE);
			seedCharted(day);
		}
		if (!ThreatSwarmScouts.enabled()) seedUnscouted(day);
		ThreatRaiders.sweep();
		census();
	}

	/**
	 * Every live human attack force, enumerated as ThreatPosture.attacksBySystem
	 * does (each fleet once, sieges first): one the swarm sees has its contact
	 * written at the figure that read counts. A siege still abstract is seen
	 * where its route is, and counts only while threatSeesBookedSieges
	 * (countsAbstract); an order mustering at its base where its fleet is. In
	 * hyperspace the hive picket sees them (sees). A force that ended, or
	 * counts nothing now, loses its contact.
	 */
	protected static void sweepContacts(Set<String> hive, float day) {
		Set<String> live = new HashSet<String>();
		Set<CampaignFleetAPI> counted = new HashSet<CampaignFleetAPI>();
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			String systemId = null;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target != null && target.getStarSystem() != null && hive.contains(target.getStarSystem().getId())) {
					systemId = target.getStarSystem().getId();
					break;
				}
			}
			if (systemId == null) continue;
			float fp = 0f;
			String source = null;
			if (purge instanceof ThreatPurgeFGI && ThreatPosture.countsAbstract((ThreatPurgeFGI) purge)) {
				fp = ((ThreatPurgeFGI) purge).abstractNow();
				RouteManager.RouteData route = purge.getRoute();
				source = sees(routeLocation(route), routeHyper(route));
				// fleets already spawning near the player are seen where they are
				for (CampaignFleetAPI f : purge.getFleets()) {
					if (f == null || !f.isAlive()) continue;
					source = better(source, sees(f.getContainingLocation(), f.getLocationInHyperspace()));
				}
			} else {
				for (CampaignFleetAPI f : purge.getFleets()) {
					if (f == null || !f.isAlive() || !counted.add(f)) continue;
					fp += ThreatSoftening.combatFP(f);
					source = better(source, sees(f.getContainingLocation(), f.getLocationInHyperspace()));
				}
			}
			if (fp <= 0f) continue;
			String key = siegeKey(purge);
			live.add(key);
			if (source != null) note(key, purge.getFaction().getId(), systemId, fp, source, day, "siege");
			else if (purge instanceof ThreatPurgeFGI && ThreatPosture.countsAbstract((ThreatPurgeFGI) purge)
					&& purge.getFleets().isEmpty()) {
				// unseen by the hive: a patrol out there may meet it
				RouteManager.RouteData route = purge.getRoute();
				ThreatSwarmPatrols.meet(key, purge.getFaction().getId(), systemId, fp, "siege",
						routeLocation(route), routeHyper(route), (ThreatPurgeFGI) purge);
			} else {
				for (CampaignFleetAPI f : purge.getFleets()) {
					if (f == null || !f.isAlive()) continue;
					ThreatSwarmPatrols.meet(key, purge.getFaction().getId(), systemId, fp, "siege",
							f.getContainingLocation(), f.getLocationInHyperspace(), null);
					break;
				}
			}
		}
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			String systemId = null;
			String kind;
			if (ThreatFleetOrders.KIND_HUNT.equals(o.kind)) {
				systemId = ThreatSoftening.huntSystemId(o);
				kind = "hunt";
			} else if (ThreatFleetOrders.KIND_SUPPORT.equals(o.kind) || ThreatFleetOrders.KIND_DEFEND.equals(o.kind)) {
				MarketAPI world = ThreatIncData.resolveColonyMarket(o.targetId);
				if (world != null && world.getStarSystem() != null) systemId = world.getStarSystem().getId();
				kind = o.raid ? "raid" : ThreatFleetOrders.KIND_DEFEND.equals(o.kind) ? "defend sortie" : "support sortie";
			} else {
				continue;
			}
			if (systemId == null || !hive.contains(systemId) || !counted.add(o.fleet)) continue;
			float fp = ThreatSoftening.combatFP(o.fleet);
			if (fp <= 0f) continue;
			String key = orderKey(o.fleet);
			live.add(key);
			String source = sees(o.fleet.getContainingLocation(), o.fleet.getLocationInHyperspace());
			if (source != null) note(key, o.factionId, systemId, fp, source, day, kind);
			else ThreatSwarmPatrols.meet(key, o.factionId, systemId, fp, kind,
					o.fleet.getContainingLocation(), o.fleet.getLocationInHyperspace(), null);
		}
		// a force that ended is seen to go (docs/threat-fog.md, decision 4)
		contactMap().keySet().retainAll(live);
	}

	/** A siege's key: its faction and its route's seed, which the save keeps (the intel itself has no id). */
	protected static String siegeKey(GenericRaidFGI purge) {
		RouteManager.RouteData route = purge.getRoute();
		Long seed = route != null ? route.getSeed() : null;
		return "siege:" + purge.getFaction().getId() + ":"
				+ (seed != null ? seed.toString() : "i" + System.identityHashCode(purge));
	}

	/** An order's key: its fleet's id. */
	protected static String orderKey(CampaignFleetAPI fleet) {
		return "order:" + fleet.getId();
	}

	/** Writes a sighting into the force's contact; its first is logged. */
	protected static void note(String key, String factionId, String systemId, float fp, String source, float day,
			String kind) {
		Map<String, Contact> contacts = contactMap();
		Contact c = contacts.get(key);
		if (c == null) {
			c = new Contact();
			c.key = key;
			c.firstDay = day;
			contacts.put(key, c);
			ThreatIncConfig.log("Swarm intel: sees " + factionId + " " + kind + " of " + (int) fp + " FP bound for "
					+ systemName(systemId) + " by " + source);
			if ("siege".equals(kind)) ThreatPosture.sighted();
		}
		c.factionId = factionId;
		c.systemId = systemId;
		c.source = source;
		c.fp = fp;
		c.day = day;
	}

	/**
	 * Every human base and every strikeable world of a charted system the swarm
	 * sees today has its place written from a live read (record). Places of
	 * worlds gone from the economy go.
	 */
	protected static void sweepPlaces(float day) {
		Map<String, float[]> memo = new HashMap<String, float[]>();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!humanPlace(m)) continue;
			// what the readers ask about: bases (staged threat, guards, reach) and
			// the worlds a strike may go to (defence)
			boolean wanted = IncursionManager.hasMilitary(m) || ThreatFrontlines.isOutpost(m)
					|| (IncursionManager.isStrikeableWorld(m) && ThreatSwarmScouts.swarmKnows(m));
			if (!wanted) continue;
			String source = sees(m.getStarSystem());
			if (source != null) record(m, source, day, memo, true);
		}
		prune();
	}

	/** A human world a place can be kept for. */
	protected static boolean humanPlace(MarketAPI m) {
		if (m == null || m.getStarSystem() == null || m.getPrimaryEntity() == null) return false;
		if (ThreatMapFog.conditionOnly(m) || Factions.THREAT.equals(m.getFactionId())) return false;
		return !m.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG);
	}

	/** What a scout's look records in a system: its bases and its strikeable worlds. */
	protected static boolean scoutWorthy(MarketAPI m) {
		return IncursionManager.hasMilitary(m) || ThreatFrontlines.isOutpost(m) || IncursionManager.isStrikeableWorld(m);
	}

	/** A military base as the spread weighs one (IncursionManager.siegeBases). */
	protected static boolean spreadBase(MarketAPI m) {
		if (m.getStarSystem() == null || ThreatMapFog.hidden(m)) return false;
		if (Factions.THREAT.equals(m.getFactionId()) || m.isPlayerOwned()) return false;
		return IncursionManager.hasMilitary(m);
	}

	/** A forward base's live guards outside the hive system it faces (those inside are that system's hostiles). */
	protected static float guardsOf(MarketAPI m) {
		ThreatFrontlines.Outpost o = ThreatFrontlines.find(m);
		if (o == null) return 0f;
		StarSystemAPI facing = ThreatFrontlines.hiveNear(m.getPrimaryEntity());
		float fp = 0f;
		for (CampaignFleetAPI g : ThreatFrontlines.liveGuards(o)) {
			if (facing != null && g.getContainingLocation() == facing) continue;
			fp += ThreatSoftening.combatFP(g);
		}
		return fp;
	}

	/**
	 * Writes the world's place from what is there now: what it stages for and
	 * what B would count of it (only a mobilised faction's siege base,
	 * siegeBasesFor, as the live read), its guards, its reach and its defence -
	 * the defence for every place, so a place never reads as undefended for
	 * want of a figure (IncursionManager.targetDefence). The staged threat and
	 * the reach on the base's own stock alone: its donors' depots are elsewhere,
	 * unseen (ThreatPosture.stagedBy, IncursionManager.seenSiegeBaseReachLY).
	 * A first sighting, or a figure moved by half, is logged when {@code log}.
	 */
	protected static Place record(MarketAPI m, String source, float day, Map<String, float[]> memo, boolean log) {
		return file(snapshot(m, source, day, memo), log);
	}

	/** The place as it stands now, read where the swarm has eyes or a scout; not filed. */
	protected static Place snapshot(MarketAPI m, String source, float day, Map<String, float[]> memo) {
		StarSystemAPI staging = ThreatConvoys.stagingHive(m);
		Place s = new Place();
		s.marketId = m.getId();
		s.systemId = m.getStarSystem().getId();
		s.factionId = m.isPlayerOwned() ? Factions.PLAYER : m.getFactionId();
		s.source = source;
		s.day = day;
		s.stagesFor = staging != null ? staging.getId() : null;
		s.stagedFP = staging != null && IncursionManager.siegeBasesFor(staging).contains(m)
				? ThreatPosture.stagedBy(m, s.stagesFor) : 0f;
		s.guardsFP = guardsOf(m);
		s.reachLY = spreadBase(m) ? IncursionManager.seenSiegeBaseReachLY(m) : 0f;
		s.defenceFP = IncursionManager.liveTargetDefence(m, memo);
		return s;
	}

	/** Files a snapshot as the swarm's place for that world (record's second half); one older than the place held is dropped. */
	protected static Place file(Place s, boolean log) {
		String stagesFor = s.stagesFor;
		float staged = s.stagedFP, guards = s.guardsFP, reach = s.reachLY, defence = s.defenceFP, day = s.day;
		String source = s.source;
		Map<String, Place> places = placeMap();
		Place p = places.get(s.marketId);
		if (p != null && p.day > day) return p;
		String was = p != null ? changed(p, stagesFor, staged, guards, defence) : null;
		boolean first = p == null;
		// a defence that swings as fleets come and go was re-logged daily (3,024 lines in pd2a)
		boolean retarget = !first && (stagesFor == null ? p.stagesFor != null : !stagesFor.equals(p.stagesFor));
		if (was != null && !retarget && day - p.loggedDay < RELOG_DAYS) was = null;
		if (first) {
			p = new Place();
			p.marketId = s.marketId;
			places.put(s.marketId, p);
		}
		p.systemId = s.systemId;
		p.factionId = s.factionId;
		p.source = source;
		p.day = day;
		p.stagesFor = stagesFor;
		p.stagedFP = staged;
		p.guardsFP = guards;
		p.reachLY = reach;
		p.defenceFP = defence;
		if (log && (first || was != null)) {
			p.loggedDay = day;
			MarketAPI m = Global.getSector().getEconomy().getMarket(s.marketId);
			ThreatIncConfig.log("Swarm intel: " + source + " on " + (m != null ? m.getName() : s.marketId) + " (" + p.factionId + "): staged "
					+ (int) staged + " for " + systemName(stagesFor) + ", guards " + (int) guards + ", defence "
					+ (int) defence + (was != null ? " (was " + was + ")" : ""));
		}
		return p;
	}

	/** The place's old figures when its target changed or a figure moved by half; else null. */
	protected static String changed(Place p, String stagesFor, float staged, float guards, float defence) {
		boolean target = stagesFor == null ? p.stagesFor != null : !stagesFor.equals(p.stagesFor);
		if (!target && !half(p.stagedFP, staged) && !half(p.guardsFP, guards) && !half(p.defenceFP, defence)) {
			return null;
		}
		return "staged " + (int) p.stagedFP + " for " + systemName(p.stagesFor) + ", guards " + (int) p.guardsFP
				+ ", defence " + (int) p.defenceFP + ", " + days(age(p)) + " d old";
	}

	/** Whether a figure moved by half or more either way, and by a whole point. */
	protected static boolean half(float a, float b) {
		float d = Math.abs(b - a);
		return d >= 1f && d >= 0.5f * Math.max(a, b);
	}

	/** A place of a world gone from the economy, or taken by the hive, goes. */
	protected static void prune() {
		for (Iterator<Place> it = placeMap().values().iterator(); it.hasNext();) {
			Place p = it.next();
			MarketAPI m = p != null && p.marketId != null ? Global.getSector().getEconomy().getMarket(p.marketId) : null;
			if (m == null || !m.isInEconomy() || Factions.THREAT.equals(m.getFactionId())) it.remove();
		}
	}

	/**
	 * Old saves, and the fog switched on mid-war: the save's first sweep under
	 * the fog (the store's SEEDED flag, not a per-load latch) gives every
	 * charted system (ThreatSwarmScouts.known) with no place at all a place for
	 * each of its human worlds from a live read, dated the day it was charted -
	 * already old, so the scouts go back (ThreatSwarmScouts.planRoute).
	 */
	protected static void seedCharted(float now) {
		Map<String, Long> known = ThreatSwarmScouts.known();
		if (known.isEmpty()) return;
		Set<String> placed = placedSystems();
		Map<String, float[]> memo = new HashMap<String, float[]>();
		for (Map.Entry<String, Long> e : new ArrayList<Map.Entry<String, Long>>(known.entrySet())) {
			if (placed.contains(e.getKey())) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(e.getKey());
			if (system == null) continue;
			float day = chartedDay(e.getValue(), now);
			int n = 0;
			for (MarketAPI m : Misc.getMarketsInLocation(system)) {
				if (!humanPlace(m) || !scoutWorthy(m)) continue;
				record(m, SCOUT, day, memo, false);
				n++;
			}
			if (n > 0) {
				ThreatIncConfig.log("Swarm intel: seeded " + n + " place(s) in " + system.getName()
						+ " from its charting, " + days(Math.max(0f, now - day)) + " d ago");
			}
		}
	}

	/** The systems holding at least one place. */
	protected static Set<String> placedSystems() {
		Set<String> placed = new HashSet<String>();
		for (Place p : placeMap().values()) {
			if (p != null && p.systemId != null) placed.add(p.systemId);
		}
		return placed;
	}

	/**
	 * Scouting off (ThreatSwarmScouts.enabled false - "the swarm knows every
	 * world", as swarmKnows reads it): every system holding a strikeable human
	 * world counts as charted. Once per system per save (UNSCOUTED), one with no
	 * place yet gets a place for each of its bases and strikeable worlds from a
	 * live read, dated today; from then on only eyes refresh it.
	 */
	protected static void seedUnscouted(float day) {
		Map<String, Float> done = ThreatSwarmIntel.<Float>section(UNSCOUTED);
		Set<String> placed = null;
		Map<String, float[]> memo = null;
		int systems = 0, n = 0;
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!humanPlace(m) || !IncursionManager.isStrikeableWorld(m)) continue;
			StarSystemAPI system = m.getStarSystem();
			if (done.containsKey(system.getId())) continue;
			done.put(system.getId(), day);
			if (placed == null) {
				placed = placedSystems();
				memo = new HashMap<String, float[]>();
			}
			// a system its eyes or scouts already saw keeps what they saw
			if (placed.contains(system.getId())) continue;
			systems++;
			for (MarketAPI w : Misc.getMarketsInLocation(system)) {
				if (!humanPlace(w) || !scoutWorthy(w)) continue;
				record(w, SCOUT, day, memo, false);
				n++;
			}
		}
		if (n > 0) {
			ThreatIncConfig.log("Swarm intel: scouting off - seeded " + n + " place(s) in " + systems + " system(s)");
		}
	}

	/** A clock timestamp (ThreatSwarmScouts.known) on the today() day scale; never (-Float.MAX_VALUE) if unreadable. */
	protected static float chartedDay(Long stamp, float now) {
		if (stamp == null || stamp.longValue() <= 0L) return -Float.MAX_VALUE;
		float ago = Global.getSector().getClock().getElapsedDaysSince(stamp.longValue());
		if (Float.isNaN(ago) || Float.isInfinite(ago) || ago >= Float.MAX_VALUE) return -Float.MAX_VALUE;
		// a stamp after today (a timeline loaded over) reads as today
		return now - Math.max(0f, ago);
	}

	/**
	 * A Scouting Swarm entered the system (ThreatSwarmScouts.ROUTE.onEnter): it
	 * looks at every human base and strikeable world there, exact.
	 */
	public static void scouted(StarSystemAPI system) {
		for (Place p : looked(system)) file(p, true);
	}

	/**
	 * What a Scouting Swarm sees in the system now, not yet filed: it carries
	 * the places home (ThreatSwarmScouts, 2026-10-05) and they are filed there
	 * (file), dated the day they were seen.
	 */
	public static List<Place> looked(StarSystemAPI system) {
		List<Place> out = new ArrayList<Place>();
		if (system == null || !enabled()) return out;
		float day = today();
		Map<String, float[]> memo = new HashMap<String, float[]>();
		for (MarketAPI m : Misc.getMarketsInLocation(system)) {
			if (!humanPlace(m) || !scoutWorthy(m)) continue;
			out.add(snapshot(m, SCOUT, day, memo));
		}
		return out;
	}

	/** Once a month, the swarm's picture in one line: contacts, places, the oldest, stale systems. */
	protected static void census() {
		if (!ThreatIncConfig.debugLogging()) return;
		Map<String, Long> last = ThreatIncData.map(KEY_LAST_CENSUS);
		Long when = last.get("all");
		if (when != null && Global.getSector().getClock().getElapsedDaysSince(when) < 30f) return;
		last.put("all", Global.getSector().getClock().getTimestamp());
		float now = today();
		float contactDays = Math.max(0f, ThreatIncConfig.swarmContactDays());
		int inSight = 0;
		float inSightFP = 0f;
		for (Contact c : contactMap().values()) {
			if (c == null || Math.max(0f, now - c.day) > contactDays) continue;
			inSight++;
			inSightFP += c.fp;
		}
		Set<String> systems = new HashSet<String>();
		int eyes = 0, scout = 0;
		float oldest = 0f;
		for (Place p : placeMap().values()) {
			if (p == null) continue;
			if (p.systemId != null) systems.add(p.systemId);
			oldest = Math.max(oldest, age(p));
			if (EYES.equals(p.source)) eyes++;
			else scout++;
		}
		int stale = 0;
		for (String id : systems) {
			if (stale(id)) stale++;
		}
		int unseen = 0;
		for (String id : ThreatSwarmScouts.known().keySet()) {
			if (!systems.contains(id)) unseen++;
		}
		ThreatIncConfig.log("Swarm intel census: contacts " + contactMap().size() + " (" + inSight + " in sight, "
				+ (int) inSightFP + " FP); places " + placeMap().size() + " in " + systems.size() + " systems (eyes "
				+ eyes + ", scout " + scout + "), oldest " + days(oldest) + " d; stale systems "
				+ stale + ", charted unseen " + unseen);
	}

	// ------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------

	/** The attacks seen bound for the hive system and last seen within swarmContactDays. */
	public static List<Contact> contactsOn(String hiveSystemId) {
		List<Contact> out = new ArrayList<Contact>();
		if (hiveSystemId == null) return out;
		float now = today();
		float days = Math.max(0f, ThreatIncConfig.swarmContactDays());
		for (Contact c : contactMap().values()) {
			if (c == null || !hiveSystemId.equals(c.systemId)) continue;
			if (Math.max(0f, now - c.day) > days) continue;
			out.add(c);
		}
		return out;
	}

	/**
	 * Whether the force's contact ({@link #siegeKey}, {@link #orderKey}) counts
	 * in contactsOn now: seen within swarmContactDays. ThreatPosture keeps a
	 * force's fleets out of the hostiles sweep only then.
	 */
	public static boolean inSight(String key) {
		Contact c = key != null ? contactMap().get(key) : null;
		if (c == null || c.fp <= 0f) return false;
		return Math.max(0f, today() - c.day) <= Math.max(0f, ThreatIncConfig.swarmContactDays());
	}

	/** The world's place, or null when the swarm has never seen it. */
	public static Place place(String marketId) {
		return marketId != null ? placeMap().get(marketId) : null;
	}

	/** Every place (a copy). */
	public static List<Place> places() {
		return new ArrayList<Place>(placeMap().values());
	}

	// ------------------------------------------------------------------
	// what a faction can answer a strike with, as the swarm has seen it (the user, 2026-10-07:
	// "the threat wouldn't know what that is unless it knew all worlds" - it knows what it has seen)
	// ------------------------------------------------------------------

	/**
	 * The faction's navy the swarm has seen anywhere but the system: every place of the faction's
	 * holds the defence, the guards and the staged force seen there (ThreatOffensive sizes a prong
	 * for the day's defence plus the share of this that could answer).
	 */
	public static float knownNavyFP(String factionId, String exceptSystemId) {
		if (!enabled() || factionId == null) return 0f;
		float fp = 0f;
		for (Place p : placeMap().values()) {
			if (!factionId.equals(p.factionId)) continue;
			if (exceptSystemId != null && exceptSystemId.equals(p.systemId)) continue;
			fp += Math.max(0f, p.defenceFP) + Math.max(0f, p.guardsFP) + Math.max(0f, p.stagedFP);
		}
		return fp;
	}

	/** Faction id -> {expected FP summed, met FP summed}, decayed by MET_KEEP a strike: what its strikes met against what they were sized for. */
	protected static final String KEY_MET = "threatinc_swarmMet";
	protected static final float MET_KEEP = 0.7f;

	@SuppressWarnings("unchecked")
	protected static Map<String, float[]> metMap() {
		Object v = Global.getSector().getPersistentData().get(KEY_MET);
		if (v instanceof Map) return (Map<String, float[]>) v;
		Map<String, float[]> m = new HashMap<String, float[]>();
		Global.getSector().getPersistentData().put(KEY_MET, m);
		return m;
	}

	/** A strike at the faction has ended: it was sized for {@code expected} and met {@code met} at most (ThreatStrikeFGI). */
	public static void noteMet(String factionId, float expected, float met) {
		if (!enabled() || factionId == null || expected <= 0f) return;
		float[] m = metMap().get(factionId);
		if (m == null) m = new float[] { 0f, 0f };
		m[0] = m[0] * MET_KEEP + expected;
		m[1] = m[1] * MET_KEEP + Math.max(0f, met);
		metMap().put(factionId, m);
		ThreatIncConfig.log("Swarm intel: a strike at " + factionId + " sized for " + (int) expected + " met " + (int) met
				+ "; its answer is now " + String.format("%.2f", responseRatio(factionId)) + "x what is seen");
	}

	/** What the faction's worlds met the swarm's strikes with, over what they were sized for; 1 until a strike has ended there, never below 1. */
	public static float responseRatio(String factionId) {
		if (!enabled() || factionId == null) return 1f;
		float[] m = metMap().get(factionId);
		if (m == null || m[0] <= 0f) return 1f;
		return Math.max(1f, m[1] / m[0]);
	}

	/** What the faction's worlds met the swarm's last strikes with over what they were sized for, unfloored (ThreatOffensive's broken focus); 1 until a strike has ended there. */
	public static float answerShare(String factionId) {
		if (!enabled() || factionId == null) return 1f;
		float[] m = metMap().get(factionId);
		if (m == null || m[0] <= 0f) return 1f;
		return Math.max(0f, m[1] / m[0]);
	}

	/** Days since the place was seen. */
	public static float age(Place p) {
		return p != null ? Math.max(0f, today() - p.day) : Float.MAX_VALUE;
	}

	/** How far the place is to be trusted: 0.5 ^ (age / intelHalfLifeDays); 0 for none. */
	public static float trust(Place p) {
		if (p == null) return 0f;
		float half = Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
		return (float) Math.pow(0.5, age(p) / half);
	}

	/** Whether the system's newest place is older than intelHalfLifeDays, or it has none: a scout should look again. */
	public static boolean stale(String systemId) {
		if (systemId == null) return true;
		float newest = -Float.MAX_VALUE;
		boolean any = false;
		for (Place p : placeMap().values()) {
			if (p == null || !systemId.equals(p.systemId)) continue;
			any = true;
			newest = Math.max(newest, p.day);
		}
		if (!any) return true;
		return today() - newest > Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
	}

	protected static String systemName(String systemId) {
		if (systemId == null) return "none";
		StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
		return system != null ? system.getName() : systemId;
	}

	/** Days for a log line: a "never" age as 99999. */
	protected static int days(float d) {
		return (int) Math.min(99999f, Math.max(0f, d));
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	/** Called on load: the not-saved senses and latches go, and a pre-fog save's convoys are marked rolled for. */
	public static void forget() {
		clearSenses();
		migrateConvoys();
	}

	/** The not-saved senses and latches. */
	protected static void clearSenses() {
		sweptDay = Long.MIN_VALUE;
		senseDay = Long.MIN_VALUE;
		STANDING.clear();
		FLEET_EYES.clear();
	}

	/**
	 * A save from before the swarm's fog - no store under KEY, which any sweep
	 * creates: its convoys at sea were rolled for at dispatch (the old
	 * ThreatConvoys.sail, sailFrontRun) without CONSIDERED_FLAG, so they are
	 * marked, or ThreatRaiders.sweep would roll for each again on sight. A save
	 * made under the fog has the store, and its convoys not yet seen keep
	 * their roll; one made with the fog off flagged every convoy at dispatch.
	 */
	protected static void migrateConvoys() {
		if (Global.getSector().getPersistentData().get(KEY) instanceof Map) return;
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (c != null && c.fleet != null) c.fleet.getMemoryWithoutUpdate().set(ThreatRaiders.CONSIDERED_FLAG, true);
		}
	}

	/** A new campaign (ThreatColonyManager.resetIncursion): every contact and place goes. */
	public static void reset() {
		Global.getSector().getPersistentData().remove(KEY);
		Global.getSector().getPersistentData().remove(KEY_LAST_CENSUS);
		clearSenses();
	}

	/**
	 * A system left the war (ThreatIncData.clearSystem): the attacks seen bound
	 * for it go, and no place stages for it any more.
	 */
	public static void drop(String systemId) {
		if (systemId == null) return;
		for (Iterator<Contact> it = contactMap().values().iterator(); it.hasNext();) {
			Contact c = it.next();
			if (c == null || systemId.equals(c.systemId)) it.remove();
		}
		for (Place p : placeMap().values()) {
			if (p != null && systemId.equals(p.stagesFor)) {
				p.stagesFor = null;
				p.stagedFP = 0f;
			}
		}
		// the hive's eyes there are read afresh
		senseDay = Long.MIN_VALUE;
	}
}
