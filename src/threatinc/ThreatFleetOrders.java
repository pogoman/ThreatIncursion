package threatinc;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * FLEET ORDERS issued remotely from the war board (docs/strategy-layer.md):
 * sorties a mobilised faction's military world can be told to fly. Every
 * order is a real fleet built at a base and provisioned from that base's
 * reserve, given vanilla assignments and tracked here so the board can show
 * it and recall it.
 *
 * <ul>
 * <li>GUARD - orbit superiority over a colony for guardDays (a task force on
 * ORBIT_AGGRESSIVE at the world), then home. Over one of the player's OWN
 * colonies it is also STAGING (2026-09-05): the task force stays for
 * guardOwnDays (0 = until recalled), and while it is on station its points
 * are the host colony's to send out - a sortie from the host folds it in
 * ({@link ThreatAidCapacity#commitSortie}) and it goes back on station when
 * the sortie is home ({@link #restation}). A guard the colony raises over
 * itself is its own ships made real; nothing staged folds into that.</li>
 * <li>INTERCEPT - a task force on ORBIT_AGGRESSIVE at the hive system's
 * jump-point nearest its colony for interceptDays: it meets the swarm's
 * reinforcements and expeditions at the door.</li>
 * <li>SUPPORT (Escort until 2026-09-06; the persisted kind string stays
 * "escort") - orbit superiority over a besieged world for supportDays,
 * clearing it of whatever contests it so front runs can land, and besieging
 * it: on station its fleet points suppress the world's fortifications each
 * poll like any siege fleet's ({@link ThreatGroundFronts#tickSupport}).
 * While an orbit is contested and nothing friendly holds it, supply and
 * evacuation runs are refused outright ({@link ThreatConvoys#canRunTo}).</li>
 * <li>DEFEND (2026-09-07) - Support that keeps its ships: the same sortie
 * over the same world, holding its orbit and fighting whatever contests it,
 * but bombarding only while the faction's own front on the ground cannot
 * hold ({@link ThreatGroundFronts#defendBombards}). An expedition fleet that
 * has landed or reinforced a front takes an indefinite Defend over it by
 * default ({@link #adoptLandingDefend}).</li>
 * </ul>
 *
 * <p>Only the player's OWN faction takes the player's orders (docs/player-aid.md):
 * NPC navies answer their governors. The same sorties flown as AID - a
 * player task force sent to another faction's colony or to a hive's door -
 * are orders flagged {@link Order#aid}, paid in credits, held on the
 * capacity ledger ({@link ThreatAidCapacity}) and earning standing on arrival
 * ({@link ThreatAid}). NPC factions use the same code to guard an ally's
 * colony ({@link ThreatCoalition}), with {@link Order#recipientFactionId} set.
 *
 * Sieges (a full purge expedition) and staging convoys are orders too, but
 * they reuse IncursionManager.launchSiegeExpedition and ThreatConvoys
 * directly; see ThreatFactionView.
 */
public class ThreatFleetOrders {

	public static final String KEY_ORDERS = "threatinc_fleetOrders";
	public static final String ORDER_FLAG = "$threatinc_ordered";

	public static final String KIND_GUARD = "guard";
	public static final String KIND_INTERCEPT = "intercept";
	/** The persisted value is the order's old name, so saves made before the rename keep their orders. */
	public static final String KIND_SUPPORT = "escort";
	/** Holds a world's orbit; bombards only while the faction's own front there cannot hold (2026-09-07). */
	public static final String KIND_DEFEND = "defend";

	/** How close a warship has to be to a planet to count as holding its orbit. */
	public static final float ORBIT_HOLD_RANGE = 1500f;

	/** The orbit assignment's length for an order with no term (on station until recalled). */
	public static final float NO_TERM_DAYS = 100000f;

	/**
	 * How long a pursuit flag lasts before it has to be renewed. Vanilla's own
	 * raid actions refresh $doNotGetSidetracked on this cadence (FGRaidAction,
	 * FGTravelAction), so a flag set here lapses about as fast as the condition
	 * that set it changes.
	 */
	public static final float PURSUIT_FLAG_DAYS = 0.4f;

	/**
	 * THE ORBIT IS CONTESTED AGAIN (2026-09-08, the user: the Threat held the
	 * space over a core world it was sieging, "my destroyed/disrupted star
	 * fortress came back online and multiplied my ground defence - the threat
	 * ignored it"). Hold station ({@link #leash}) blinds a Defend or Support
	 * fleet so that a defender which runs cannot draw it off the world it is
	 * standing over. A station cannot run: when its repairs finish it respawns
	 * on top of the fleet and puts its multiplier back on the colony's ground
	 * defence, and the blinders make the one hostile that is certainly still
	 * there the one thing the fleet will not fight. So the poll stamps this
	 * flag while the orbit of the fleet's own world is held against it and the
	 * leash gives the reflexes back for as long as it is set - at the planet,
	 * never in pursuit. It lapses on its own if the poll stops.
	 */
	public static final String ORBIT_FIGHT_KEY = "$threatinc_orbitFight";

	/** A colony poll runs every 0.4-0.6 d (IncursionManager): a day outlives one poll and little else. */
	public static final float ORBIT_FIGHT_DAYS = 1f;

	/** Stamped each poll by {@link ThreatGroundFronts#tickSupport} and {@link ThreatSwarmDefend#tick}. */
	public static void fightOrbit(CampaignFleetAPI fleet, boolean fighting) {
		if (fleet == null) return;
		if (fighting) {
			fleet.getMemoryWithoutUpdate().set(ORBIT_FIGHT_KEY, true, ORBIT_FIGHT_DAYS);
		} else {
			fleet.getMemoryWithoutUpdate().unset(ORBIT_FIGHT_KEY);
		}
	}

	public static boolean fightingOrbit(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(ORBIT_FIGHT_KEY);
	}

	/** One standing order and the fleet flying it. */
	public static class Order {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String kind;
		public String baseMarketId;
		/** Reserve key of the base guarded - a market id, or an outpost's station entity id - or a system id (intercept). */
		public String targetId;
		public String targetName;
		public long issuedTimestamp;
		public float days;
		/** The faction whose colony is guarded when it is not the sender's own; null otherwise. */
		public String recipientFactionId;
		/** A player aid sortie: paid in credits, on the capacity ledger, earning standing on arrival. */
		public boolean aid;
		/** Whether the sortie has reached its station. */
		public boolean arrived;

		/** No term: on station until recalled (a guard over one of the player's own colonies, guardOwnDays 0). */
		public boolean indefinite() {
			return days <= 0f;
		}

		public float daysLeft() {
			if (indefinite()) return Float.MAX_VALUE;
			return Math.max(0f, days - Global.getSector().getClock()
					.getElapsedDaysSince(issuedTimestamp));
		}

		public String task() {
			if (KIND_GUARD.equals(kind)) return "guarding " + targetName;
			if (KIND_INTERCEPT.equals(kind)) return "intercepting at " + targetName;
			if (KIND_SUPPORT.equals(kind) || KIND_DEFEND.equals(kind)) {
				// fighting for the orbit first; then besieging (Support), or
				// holding it and covering a front that cannot hold (Defend)
				MarketAPI world = targetId != null
						? Global.getSector().getEconomy().getMarket(targetId) : null;
				if (world != null && ThreatGroundFronts.orbitContestedFor(factionId, world)) {
					return "clearing the orbit of " + targetName;
				}
				if (KIND_SUPPORT.equals(kind)) return "supporting the siege of " + targetName;
				if (world != null && ThreatGroundFronts.defendBombards(factionId, world)) {
					return "covering the front on " + targetName;
				}
				if (world != null && ThreatGroundFronts.defendFabricates(factionId, world)) {
					return "breaking up for the front on " + targetName;
				}
				return "defending the orbit of " + targetName;
			}
			return kind;
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Order> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_ORDERS);
		if (!(val instanceof List)) {
			val = new ArrayList<Order>();
			Global.getSector().getPersistentData().put(KEY_ORDERS, val);
		}
		return (List<Order>) val;
	}

	public static List<Order> ordersFor(String factionId) {
		List<Order> result = new ArrayList<Order>();
		for (Order o : all()) {
			if (factionId == null || factionId.equals(o.factionId)) result.add(o);
		}
		return result;
	}

	/**
	 * Orders whose fleet is dead, or whose time ran out, are dropped (the
	 * fleet's own queue takes it home). Every order notes its arrival on
	 * station (a guard over an own colony is staged from then on); aid
	 * sorties also report it and their term served, for the standing they earn.
	 */
	public static void poll() {
		if (all().isEmpty()) return;
		for (Order o : new ArrayList<Order>(all())) {
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) {
				all().remove(o);
				if (o.aid) {
					ThreatColonyManager.announceAlways("Your task force " + o.task()
							+ " has been lost.", Misc.getNegativeHighlightColor());
				}
				continue;
			}
			if (!o.arrived && atStation(o)) {
				o.arrived = true;
				if (o.aid) {
					if (KIND_GUARD.equals(o.kind)) ThreatAid.onGuardArrived(o);
					else ThreatAid.onStrikeArrived(o);
				}
			}
			// a guard whose outpost died (or was scuttled) has nothing left to hold
			boolean stationGone = KIND_GUARD.equals(o.kind) && o.targetId != null
					&& ThreatBases.of(o.targetId) == null;
			// a landing's Defend (no term) stays until the front it covers is
			// gone, or the batteries have ground it below defendMinStrength
			// a fleet feeding its front its own hulls is exempt from the
			// strength stand-down (2026-09-08): it commits until the front
			// stands or falls, never home because fabrication cost it ships
			boolean frontGone = KIND_DEFEND.equals(o.kind) && o.indefinite()
					&& (ThreatGroundFronts.getFront(o.targetId) == null
					|| (ThreatReturns.health(o.fleet) < ThreatIncConfig.defendMinStrength()
					&& !ThreatGroundFronts.defendCommitted(o.fleet, o.factionId, o.targetId)));
			if (o.daysLeft() <= 0f || stationGone || frontGone) {
				all().remove(o);
				if (o.aid && o.arrived && KIND_GUARD.equals(o.kind)) ThreatAid.onGuardCompleted(o);
				// time served: home on the tracked leg, refund on arrival
				ThreatReturns.sendHome(o.fleet, o.factionId, o.baseMarketId);
				ThreatIncConfig.log("Order " + (stationGone ? "void - station gone: "
						: frontGone ? "stood down: " : "complete: ")
						+ o.factionId + " " + o.task());
			}
		}
	}

	/** Whether the fleet is within arrival range of its station. */
	protected static boolean atStation(Order o) {
		SectorEntityToken station = null;
		if (KIND_GUARD.equals(o.kind) || KIND_SUPPORT.equals(o.kind) || KIND_DEFEND.equals(o.kind)) {
			ThreatBases.Base b = ThreatBases.of(o.targetId);
			station = b != null ? b.entity() : null;
		} else {
			station = interceptPoint(ThreatWarBoard.getSystem(o.targetId));
		}
		if (station == null || o.fleet == null) return false;
		return o.fleet.getContainingLocation() == station.getContainingLocation()
				&& Misc.getDistance(o.fleet, station) <= ORBIT_HOLD_RANGE;
	}

	// ------------------------------------------------------------------
	// staging: the player's guards over their own colonies
	// ------------------------------------------------------------------

	/**
	 * The player's task forces staged at one of their colonies: own-faction
	 * guard orders over it whose fleet is alive and on station, oldest first.
	 * Their points are the host's to send out (ThreatAidCapacity.freeFP).
	 */
	public static List<Order> stagedAt(MarketAPI host) {
		List<Order> result = new ArrayList<Order>();
		if (host == null) return result;
		String player = Global.getSector().getPlayerFaction().getId();
		for (Order o : all()) {
			if (!KIND_GUARD.equals(o.kind) || o.aid || o.recipientFactionId != null) continue;
			if (!player.equals(o.factionId) || !host.getId().equals(o.targetId)) continue;
			if (!o.arrived || o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			result.add(o);
		}
		return result;
	}

	/**
	 * A staged task force folds into a sortie from its host: the order ends,
	 * what the fleet carries and drew is settled at the host (the ships are
	 * here), and the fleet leaves the sector - its ledger entry already rides
	 * the sortie (ThreatAidCapacity.foldStaged).
	 */
	public static void fold(Order o, MarketAPI host) {
		if (o == null) return;
		all().remove(o);
		CampaignFleetAPI fleet = o.fleet;
		if (fleet == null || !fleet.isAlive()) return;
		ThreatReturns.settle(fleet, host);
		if (!ThreatReturns.kept(fleet)) Misc.fadeAndExpire(fleet);
	}

	/**
	 * A fleet carrying ships folded in from staging is back at their host:
	 * it STAYS - the same hulls, on station as a guard of the host until
	 * recalled (2026-09-06). Every entry the fleet holds becomes an entry of
	 * that guard, still charged to its sender; Recall takes the fleet home to
	 * the sender charged most. Nothing is kept - the points are simply the
	 * senders' - when the host is no longer the player's or the fleet is
	 * below aidGuardMinFP. Until 2026-09-06 a fresh task force was built at
	 * the host the moment the sortie ENDED, while the real ships were still
	 * sailing home, and those dissolved on arrival with nothing to hand back
	 * (seen: three "back on station" over Sun Wukong for one recalled fleet).
	 */
	public static boolean restation(CampaignFleetAPI fleet, List<ThreatAidCapacity.Commitment> mine,
			MarketAPI host) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return false;
		if (mine == null || mine.isEmpty()) return false;
		if (host == null || !host.isPlayerOwned() || host.getPrimaryEntity() == null
				|| host.getStarSystem() == null) {
			return false;
		}
		float combat = ThreatAidCapacity.liveFP(fleet) / ThreatAidCapacity.TASK_FORCE_HULL_MULT;
		if (combat < ThreatIncConfig.aidGuardMinFP()) return false;
		FactionAPI player = Global.getSector().getPlayerFaction();
		MarketAPI source = null;
		float sourceFP = 0f;
		for (ThreatAidCapacity.Commitment c : mine) {
			MarketAPI m = Global.getSector().getEconomy().getMarket(c.marketId);
			if (m == null || !m.isPlayerOwned() || m.getPrimaryEntity() == null) continue;
			if (source == null || c.fp > sourceFP) {
				source = m;
				sourceFP = c.fp;
			}
		}
		if (source == null) source = host;
		ThreatPurgeFGI.cutLoose(fleet);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		// settled already: what it drew was refunded on arrival
		ThreatReturns.provision(fleet, source.getId(), 0f, 0f);
		float days = ThreatIncConfig.guardOwnDays();
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, host.getPrimaryEntity(),
				days > 0f ? days : NO_TERM_DAYS, "guarding " + host.getName());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, source.getPrimaryEntity(),
				1000f, "returning to " + source.getName());
		Order o = record(fleet, player, KIND_GUARD, source, host.getId(), host.getName(), days);
		o.arrived = true;
		String label = "guard of " + host.getName();
		for (ThreatAidCapacity.Commitment c : mine) {
			c.fleet = fleet;
			c.group = null;
			c.hostMarketId = null;
			c.foldedFP = 0f;
			c.lostTimestamp = 0L;
			c.label = label;
		}
		ThreatColonyManager.announceAlways(fleet.getName() + " is back on station over "
				+ host.getName() + ".", Misc.getHighlightColor());
		return true;
	}

	/**
	 * The abstract case: an expedition that never became real has ended, and
	 * ships folded into it go back on station over the host as a fresh task
	 * force at the route's surviving strength, charged to the colony that
	 * first sent them. Real fleets settle themselves (the overload above).
	 */
	public static boolean restation(ThreatAidCapacity.Commitment c, float health) {
		if (c == null || c.hostMarketId == null) return false;
		MarketAPI host = Global.getSector().getEconomy().getMarket(c.hostMarketId);
		if (host == null || !host.isPlayerOwned() || host.getPrimaryEntity() == null
				|| host.getStarSystem() == null) {
			return false;
		}
		// the ships that folded in, at what they were worth then, times what the
		// sortie brought home; the sender's charge (c.fp) is what sailed from it,
		// which can be more than what reached the host
		float folded = c.foldedFP > 0f ? c.foldedFP : c.fp;
		float combat = folded * Math.max(0f, Math.min(1f, health)) / ThreatAidCapacity.TASK_FORCE_HULL_MULT;
		if (combat < ThreatIncConfig.aidGuardMinFP()) return false;
		FactionAPI player = Global.getSector().getPlayerFaction();
		MarketAPI source = Global.getSector().getEconomy().getMarket(c.marketId);
		if (source == null || !source.isPlayerOwned() || source.getPrimaryEntity() == null) {
			source = host;
		}
		// already provisioned: the sortie it came home with drew and refunded its own
		CampaignFleetAPI fleet = buildTaskForce(host, player, combat, host.getLocationInHyperspace(),
				false);
		if (fleet == null) return false;
		float points = builtPoints(fleet, combat);
		ThreatReturns.provision(fleet, source.getId(), 0f, 0f);
		float days = ThreatIncConfig.guardOwnDays();
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, host.getPrimaryEntity(),
				days > 0f ? days : NO_TERM_DAYS, "guarding " + host.getName());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, source.getPrimaryEntity(),
				1000f, "returning to " + source.getName());
		Order o = record(fleet, player, KIND_GUARD, source, host.getId(), host.getName(), days);
		o.arrived = true;
		ThreatAidCapacity.commit(source, points, fleet, "guard of " + host.getName());
		ThreatColonyManager.announceAlways("Your task force from " + source.getName()
				+ " is back on station over " + host.getName() + ".", Misc.getHighlightColor());
		return true;
	}

	// ------------------------------------------------------------------
	// who may order whom
	// ------------------------------------------------------------------

	/** The player commands their own mobilised faction outright, and nobody else's. */
	public static boolean canPlayerOrder(FactionAPI faction) {
		if (faction == null || !ThreatWarState.enabled() || !ThreatIncConfig.ordersEnabled()) {
			return false;
		}
		return faction.isPlayerFaction() && ThreatWarState.isAtWar(faction);
	}

	/** Why the player cannot order this faction's fleets, or null if they can. */
	public static String orderBlockReason(FactionAPI faction) {
		if (faction == null) return "No faction.";
		if (!ThreatWarState.enabled()) return "The strategy layer is disabled in the mod settings.";
		if (!ThreatIncConfig.ordersEnabled()) return "Fleet orders are disabled in the mod settings.";
		if (!faction.isPlayerFaction()) {
			return faction.getDisplayName() + "'s navy is its own: it answers its governors, not "
					+ "you. Send aid from your colonies instead.";
		}
		if (!ThreatWarState.isAtWar(faction)) {
			return "Your faction is not mobilised - the Threat has not struck it.";
		}
		return null;
	}

	// ------------------------------------------------------------------
	// relief: a faction answers a Threat army on its own world
	// ------------------------------------------------------------------

	/** Whether a live guard task force of any faction is bound for or over the colony. */
	public static boolean guardBoundFor(String marketId) {
		if (marketId == null) return false;
		for (Order o : all()) {
			if (!KIND_GUARD.equals(o.kind) || !marketId.equals(o.targetId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) return true;
		}
		return false;
	}

	/**
	 * RELIEF (docs/ground-war.md "How the defender fights back"): a Threat
	 * army on one of the faction's own worlds gets a Guard task force over it
	 * - the orbit denied, no further wave can land - one per world at a time,
	 * from the nearest base. NPC navies only: the player's own faction takes
	 * the player's orders from the board. Marines follow by convoy
	 * (ThreatConvoys.planRelief).
	 */
	public static void planRelief() {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.ordersEnabled()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				if (market.getPrimaryEntity() == null) continue;
				if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()))) {
					continue;
				}
				if (guardBoundFor(market.getId())) continue;
				Order o = dispatchGuard(faction, market);
				if (o != null) {
					ThreatIncConfig.log("Relief: " + factionId + " guards invaded "
							+ market.getName());
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// building sorties
	// ------------------------------------------------------------------

	/**
	 * The faction's nearest military world within expedition range of a
	 * hyperspace location, or null. The player's nearest base at any range
	 * (2026-09-05, the user's call: distance costs fuel and time, never
	 * permission); NPC navies keep their fuel-based reach.
	 */
	public static MarketAPI pickBase(FactionAPI faction, Vector2f hyperLoc) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (market.getStarSystem() == null || !IncursionManager.isBase(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), hyperLoc);
			if (!faction.isPlayerFaction() && d > IncursionManager.expeditionRangeLY(market)) continue;
			if (d < bestDist) {
				bestDist = d;
				best = market;
			}
		}
		return best;
	}

	/**
	 * A task force at the base, provisioned from its reserve (fuel for the
	 * distance, supplies for the hulls). A player fleet is built at exactly
	 * the points asked for - the capacity ledger already sized it to the
	 * colony - so vanilla's own fleet-size scaling is switched off for it;
	 * an NPC fleet keeps vanilla's scaling, which is its navy's size.
	 */
	public static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper) {
		return buildTaskForce(base, faction, fp, destinationHyper, true);
	}

	/** As above; {@code provision} false builds the hulls without drawing on the base (a restationed detachment). */
	public static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper, boolean provision) {
		StarSystemAPI system = base.getStarSystem();
		SectorEntityToken entity = base.getPrimaryEntity();
		if (system == null || entity == null || fp <= 0f) return null;
		FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(),
				faction.getId(), null, FleetTypes.TASK_FORCE,
				fp, fp * 0.1f, fp * 0.1f, 0f, 0f, 0f, 0f);
		if (faction.isPlayerFaction()) params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;
		system.addEntity(fleet);
		fleet.setLocation(entity.getLocation().x, entity.getLocation().y);
		fleet.setName("Task Force");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		// under orders: no response script (a raid's, or a system's fight for
		// its objectives) may borrow it - a war fleet without this sat on the
		// hive's comm relay instead of the jump-point it was sent to
		fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		if (!provision) {
			ThreatReturns.provision(fleet, base.getId(), 0f, 0f);
			return fleet;
		}

		float points = fp / IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		float dist = Misc.getDistanceLY(system.getLocation(), destinationHyper);
		float fuel = ThreatReserves.drawAbove(base, Commodities.FUEL,
				points * dist * ThreatIncConfig.expeditionFuelPerPointLY());
		float supplies = ThreatReserves.drawAbove(base, Commodities.SUPPLIES,
				points * ThreatIncConfig.expeditionSuppliesPerPoint());
		ThreatIncConfig.log("Order draw at " + base.getName() + ": " + (int) fuel + " fuel, "
				+ (int) supplies + " supplies");
		// remembered on the fleet so the return leg can refund what survives
		ThreatReturns.provision(fleet, base.getId(), fuel, supplies);
		return fleet;
	}

	/** Combat points a sortie from this base sails with: the NPC guard size, or everything a player colony has free (staged task forces included). */
	protected static float sortieFP(FactionAPI faction, MarketAPI base) {
		if (faction.isPlayerFaction()) return ThreatAid.taskForceFP(base);
		return ThreatIncConfig.guardFleetFP();
	}

	/**
	 * The points a task force actually sailed with, for the ledger: the
	 * combat points asked for plus their support hulls
	 * (ThreatAidCapacity.taskForcePoints), or less when vanilla's factory
	 * could not fit that many hulls - a colony sends everything it has free,
	 * which can be more than one fleet holds. Both sides are whole-fleet
	 * points (vanilla's getFleetPoints, freighters and tankers included),
	 * the unit every figure on the board is in. Before 2026-09-05 evening the
	 * combat points were compared with the whole fleet, so the ledger always
	 * charged the full ask (239) for a fleet the board then showed at 226.
	 */
	protected static float builtPoints(CampaignFleetAPI fleet, float combat) {
		float asked = ThreatAidCapacity.taskForcePoints(combat);
		if (fleet == null) return asked;
		return Math.max(0f, Math.min(asked, fleet.getFleetPoints()));
	}

	/** Whether the colony is the faction's own (a player guard over it is staging). */
	protected static boolean ownColony(FactionAPI faction, MarketAPI target) {
		return faction != null && target != null && faction.getId().equals(target.getFactionId());
	}

	/** Whether the base - colony or outpost - is the faction's own. */
	protected static boolean ownBase(FactionAPI faction, ThreatBases.Base target) {
		return faction != null && target != null && faction.getId().equals(target.factionId());
	}

	/**
	 * Orbit superiority over a colony from the sender's best base. For the
	 * player that is the nearest colony fielding a full guard - the colony
	 * itself first when it has own points free (ThreatAid.pickTaskForceSource).
	 */
	public static Order dispatchGuard(FactionAPI faction, MarketAPI target) {
		return dispatchGuard(faction, ThreatBases.of(target));
	}

	/** As above over either kind of base: a colony, or the player's outpost (held until recalled). */
	public static Order dispatchGuard(FactionAPI faction, ThreatBases.Base target) {
		if (faction == null || target == null || target.hyperLoc() == null) return null;
		MarketAPI base = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(target.hyperLoc(),
						ownBase(faction, target) ? target.market : null)
				: pickBase(faction, target.hyperLoc());
		return dispatchGuard(faction, target, base, false);
	}

	/**
	 * Orbit superiority over a colony from a given base: guardDays, or over
	 * one of the player's own colonies guardOwnDays (0 = until recalled) as a
	 * staged task force. With {@code aid} the sortie is player aid (announced
	 * and paid by ThreatAid); a guard of another faction's colony records
	 * that faction either way.
	 */
	public static Order dispatchGuard(FactionAPI faction, MarketAPI target, MarketAPI base,
			boolean aid) {
		return dispatchGuard(faction, ThreatBases.of(target), base, aid);
	}

	/** As above over either kind of base. */
	public static Order dispatchGuard(FactionAPI faction, ThreatBases.Base target, MarketAPI base,
			boolean aid) {
		if (faction == null || target == null || target.entity() == null) return null;
		if (base == null) return null;
		boolean own = faction.isPlayerFaction() && !aid && ownBase(faction, target);
		// a colony's guard over itself is its own ships made real: sized by its
		// own free points, and nothing staged there folds into it
		boolean self = own && target.market != null && base == target.market;
		float days = own ? ThreatIncConfig.guardOwnDays() : ThreatIncConfig.guardDays();
		float fp = faction.isPlayerFaction() ? ThreatAid.taskForceFP(base, self)
				: ThreatIncConfig.guardFleetFP();
		if (faction.isPlayerFaction() && fp < ThreatIncConfig.aidGuardMinFP()) return null;
		CampaignFleetAPI fleet = buildTaskForce(base, faction, fp, target.hyperLoc());
		if (fleet == null) return null;
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.entity(),
				days > 0f ? days : NO_TERM_DAYS, "guarding " + target.name());
		// the home leg is GO_TO_LOCATION, not a despawn: poll() hands the fleet
		// to ThreatReturns when the order runs out, and that settles the refund
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_GUARD, base, target.id(), target.name(), days);
		o.aid = aid;
		if (!faction.getId().equals(target.factionId())) o.recipientFactionId = target.factionId();
		if (faction.isPlayerFaction()) {
			float points = builtPoints(fleet, fp);
			String label = "guard of " + target.name();
			if (self) ThreatAidCapacity.commit(base, points, fleet, label);
			else ThreatAidCapacity.commitSortie(base, points, fleet, label);
		}
		if (!aid) {
			announce(faction, "task force from " + base.getName() + " is moving to guard "
					+ target.name() + (days > 0f ? " for " + (int) days + " days." : "."));
		}
		return o;
	}

	/** The jump-point of a hive system nearest its colonies, or any jump-point, or null. */
	public static SectorEntityToken interceptPoint(StarSystemAPI system) {
		if (system == null) return null;
		SectorEntityToken anchor = null;
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			if (hive.getPrimaryEntity() != null) {
				anchor = hive.getPrimaryEntity();
				break;
			}
		}
		SectorEntityToken best = null;
		float bestDist = Float.MAX_VALUE;
		for (SectorEntityToken jp : system.getJumpPoints()) {
			if (jp == null) continue;
			float d = anchor != null ? Misc.getDistance(jp, anchor) : 0f;
			if (d < bestDist) {
				bestDist = d;
				best = jp;
			}
		}
		return best;
	}

	/** A task force on the hive system's door for interceptDays, from the sender's best base. */
	public static Order dispatchIntercept(FactionAPI faction, StarSystemAPI hive) {
		if (faction == null || hive == null) return null;
		MarketAPI base = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(hive.getLocation())
				: pickBase(faction, hive.getLocation());
		return dispatchIntercept(faction, hive, base, false);
	}

	/** A task force on the hive system's door for interceptDays, from a given base. */
	public static Order dispatchIntercept(FactionAPI faction, StarSystemAPI hive, MarketAPI base,
			boolean aid) {
		if (faction == null || hive == null || base == null) return null;
		SectorEntityToken point = interceptPoint(hive);
		if (point == null) return null;
		float days = ThreatIncConfig.interceptDays();
		float fp = sortieFP(faction, base);
		if (faction.isPlayerFaction() && fp < ThreatIncConfig.aidGuardMinFP()) return null;
		CampaignFleetAPI fleet = buildTaskForce(base, faction, fp, hive.getLocation());
		if (fleet == null) return null;
		String where = point.getName() != null ? point.getName()
				: "the " + hive.getNameWithLowercaseTypeShort() + " jump-point";
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, point, days,
				"intercepting at " + where);
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_INTERCEPT, base, hive.getId(), where, days);
		o.aid = aid;
		if (faction.isPlayerFaction()) {
			ThreatAidCapacity.commitSortie(base, builtPoints(fleet, fp), fleet,
					"intercept at " + where);
		}
		if (!aid) {
			announce(faction, "task force from " + base.getName() + " is moving to intercept the "
					+ "swarm at " + where + " for " + (int) days + " days.");
		}
		return o;
	}

	// ------------------------------------------------------------------
	// support: holding the orbit over a besieged world, and besieging it
	// ------------------------------------------------------------------

	/**
	 * SUPPORT - a combat sortie that holds the orbit over a world this
	 * faction has a ground front on, clears it of whatever contests it so
	 * supply and evacuation runs can land, and suppresses its defences from
	 * orbit while it is there. Without one, a run to a contested orbit is
	 * refused outright ({@link ThreatConvoys#canRunTo}). Player and NPC
	 * alike; sized like a guard.
	 */
	public static Order dispatchSupport(FactionAPI faction, MarketAPI hive) {
		return dispatchOrbit(faction, hive, KIND_SUPPORT);
	}

	/** A Support sortie over the world from a given base. */
	public static Order dispatchSupport(FactionAPI faction, MarketAPI hive, MarketAPI base) {
		return dispatchOrbit(faction, hive, base, KIND_SUPPORT);
	}

	/**
	 * DEFEND (2026-09-07) - Support that keeps its ships: the same sortie
	 * over the same world, holding its orbit and fighting whatever contests
	 * it, but it does NOT bombard while the faction's front on the ground
	 * holds; only while the front cannot hold and orbit can still push the
	 * fortifications ({@link ThreatGroundFronts#defendBombards}) does it
	 * deliver siege slices and pay the batteries. It is what an expedition's
	 * landing fleet does by default ({@link #adoptLandingDefend}).
	 */
	public static Order dispatchDefend(FactionAPI faction, MarketAPI hive) {
		return dispatchOrbit(faction, hive, KIND_DEFEND);
	}

	/** A Defend sortie over the world from a given base. */
	public static Order dispatchDefend(FactionAPI faction, MarketAPI hive, MarketAPI base) {
		return dispatchOrbit(faction, hive, base, KIND_DEFEND);
	}

	/** A Support or Defend sortie: the player's nearest fleet already out takes it, else a task force from the nearest base. */
	public static Order dispatchOrbit(FactionAPI faction, MarketAPI hive, String kind) {
		if (faction == null || hive == null) return null;
		if (faction.isPlayerFaction()) {
			// the nearest fleet already under orders takes it (2026-09-06, the
			// user: "escort should repurpose the nearest fleet"); a task force
			// is raised only when none is out
			Reassignable near = nearestReassignable(faction, hive, kind);
			if (near != null) return adoptOrbit(near.fleet, faction, hive, near.duty, kind);
		}
		MarketAPI base = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(hive.getLocationInHyperspace())
				: pickBase(faction, hive.getLocationInHyperspace());
		return dispatchOrbit(faction, hive, base, kind);
	}

	/** A Support or Defend sortie over the world from a given base. */
	public static Order dispatchOrbit(FactionAPI faction, MarketAPI hive, MarketAPI base, String kind) {
		if (!orbitEnabled(kind)) return null;
		if (faction == null || hive == null || hive.getPrimaryEntity() == null) return null;
		if (base == null) return null;
		float days = orbitDays(kind);
		float fp = sortieFP(faction, base);
		if (faction.isPlayerFaction() && fp < ThreatIncConfig.aidGuardMinFP()) return null;
		CampaignFleetAPI fleet = buildTaskForce(base, faction, fp, hive.getLocationInHyperspace());
		if (fleet == null) return null;
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(),
				days > 0f ? days : NO_TERM_DAYS, orbitTask(kind, hive.getName()));
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, kind, base, hive.getId(), hive.getName(), days);
		if (faction.isPlayerFaction()) {
			ThreatAidCapacity.commitSortie(base, builtPoints(fleet, fp), fleet,
					orbitName(kind).toLowerCase() + " over " + hive.getName());
		}
		announce(faction, "task force from " + base.getName() + " is moving to " + orbitVerb(kind)
				+ " " + hive.getName() + " for " + (int) days + " days.");
		return o;
	}

	/** "Support" or "Defend". */
	public static String orbitName(String kind) {
		return KIND_DEFEND.equals(kind) ? "Defend" : "Support";
	}

	/** "support the siege of" or "defend the orbit of". */
	public static String orbitVerb(String kind) {
		return KIND_DEFEND.equals(kind) ? "defend the orbit of" : "support the siege of";
	}

	/** The orbit assignment's text. */
	protected static String orbitTask(String kind, String worldName) {
		return (KIND_DEFEND.equals(kind) ? "defending the orbit of " : "supporting the siege of ")
				+ worldName;
	}

	public static boolean orbitEnabled(String kind) {
		return KIND_DEFEND.equals(kind) ? ThreatIncConfig.defendEnabled()
				: ThreatIncConfig.supportEnabled();
	}

	public static float orbitDays(String kind) {
		return KIND_DEFEND.equals(kind) ? ThreatIncConfig.defendDays() : ThreatIncConfig.supportDays();
	}

	/** The button and confirm text for a sortie of the kind ({@link #supportEffect} / {@link #defendEffect}). */
	public static String orbitEffect(MarketAPI world, float fp, String kind) {
		return KIND_DEFEND.equals(kind) ? defendEffect(world, fp) : supportEffect(world, fp);
	}

	/**
	 * What a Support sortie of {@code fp} points does over the world, for the
	 * button and the confirm (the user, 2026-09-06: the button must say what
	 * they will do, when they bombard, and what it costs). One line per fact.
	 */
	public static String supportEffect(MarketAPI world, float fp) {
		if (world == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append("Holds the orbit for ").append((int) ThreatIncConfig.supportDays())
				.append(" days: runs can land, and a fleet or Defense Swarms contesting it are fought.");
		sb.append("\n").append(ThreatGroundFronts.siegeClockLine(world));
		if (ThreatShield.present(world)) sb.append("\n").append(ThreatShield.line(world));
		float[] est = ThreatGroundFronts.siegeSliceEstimate(fp, world, 1f);
		int floor = Math.round(Math.max(0f, Math.min(1f, ThreatIncConfig.fortificationOrbitFloor())) * 100f);
		sb.append("\nWhile the orbit is clear it bombards the defences: about ")
				.append(String.format("%.1f", est[0]))
				.append(" disruption days per day, to no lower than ").append(floor).append("% effect.");
		sb.append("\nThe batteries answer: about ").append(String.format("%.1f", est[1]))
				.append(" fleet points of ships lost per day, smallest first.");
		sb.append("\nIt bombards whether or not the front could hold on its own; Defend bombards "
				+ "only while it cannot, and an expedition lands as soon as its troops can hold, "
				+ "to spare its ships.");
		return sb.toString();
	}

	/**
	 * What a Defend sortie of {@code fp} points does over the world, for the
	 * button and the confirm. One line per fact; that the bombardment is
	 * conditional is the whole difference from Support, so it is the second
	 * line.
	 */
	public static String defendEffect(MarketAPI world, float fp) {
		if (world == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append("Holds the orbit for ").append((int) ThreatIncConfig.defendDays())
				.append(" days: runs can land, and a fleet or Defense Swarms contesting it are fought.");
		sb.append("\n").append(ThreatGroundFronts.siegeClockLine(world));
		if (ThreatShield.present(world)) sb.append("\n").append(ThreatShield.line(world));
		sb.append("\nIt does not bombard while the front holds, so the batteries cost it nothing.");
		float[] est = ThreatGroundFronts.siegeSliceEstimate(fp, world, 1f);
		int floor = Math.round(Math.max(0f, Math.min(1f, ThreatIncConfig.fortificationOrbitFloor())) * 100f);
		sb.append("\nWhile the front cannot hold it bombards the defences until it can: about ")
				.append(String.format("%.1f", est[0]))
				.append(" disruption days per day, to no lower than ").append(floor).append("% effect.");
		sb.append("\nOnly then do the batteries answer: about ").append(String.format("%.1f", est[1]))
				.append(" fleet points of ships lost per day, smallest first.");
		if (ThreatIncConfig.fabricateEnabled()) {
			sb.append("\nWith the defences at the floor and the front still short, it stops bombarding "
					+ "and breaks up its own hulls into troops instead - only as many as the front "
					+ "needs to hold, and it stops as soon as it does.");
			sb.append("\nThe batteries charge about ")
					.append(String.format("%.1f", ThreatGroundFronts.fabricateCost(fp, world, 1f)))
					.append(" fleet points a day for the drop, as though they were undisrupted.");
			sb.append("\nIt does not go home while it is doing that.");
		}
		return sb.toString();
	}

	/** Whether this faction already has a live Support sortie ordered over the world. */
	public static boolean hasSupport(String factionId, String hiveMarketId) {
		return hasOrder(KIND_SUPPORT, factionId, hiveMarketId);
	}

	/** Whether this faction already has a live Defend sortie ordered over the world. */
	public static boolean hasDefend(String factionId, String hiveMarketId) {
		return hasOrder(KIND_DEFEND, factionId, hiveMarketId);
	}

	/** Whether this faction has a live order of the kind on the target. */
	public static boolean hasOrder(String kind, String factionId, String targetId) {
		if (kind == null || factionId == null || targetId == null) return false;
		for (Order o : all()) {
			if (!kind.equals(o.kind)) continue;
			if (!factionId.equals(o.factionId) || !targetId.equals(o.targetId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) return true;
		}
		return false;
	}

	/**
	 * Whether a friendly combat fleet actually holds the world's orbit: a
	 * Support, Defend or Guard order fleet of the faction within
	 * ORBIT_HOLD_RANGE of the planet, or - for the player's faction - the
	 * player's own fleet.
	 */
	public static boolean friendlyOrbit(String factionId, String hiveMarketId) {
		if (factionId == null || hiveMarketId == null) return false;
		MarketAPI hive = Global.getSector().getEconomy().getMarket(hiveMarketId);
		SectorEntityToken planet = hive != null ? hive.getPrimaryEntity() : null;
		if (planet == null) return false;
		for (Order o : all()) {
			if (!KIND_SUPPORT.equals(o.kind) && !KIND_GUARD.equals(o.kind)
					&& !KIND_DEFEND.equals(o.kind)) {
				continue;
			}
			if (!factionId.equals(o.factionId)) continue;
			if (nearPlanet(o.fleet, planet)) return true;
		}
		if (Factions.PLAYER.equals(factionId)
				&& nearPlanet(Global.getSector().getPlayerFleet(), planet)) {
			return true;
		}
		return false;
	}

	public static boolean nearPlanet(CampaignFleetAPI fleet, SectorEntityToken planet) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return false;
		if (fleet.getContainingLocation() != planet.getContainingLocation()) return false;
		return Misc.getDistance(fleet, planet) <= ORBIT_HOLD_RANGE;
	}

	/** Why this faction cannot send Support over the world now, or null if it can. */
	public static String supportBlockReason(FactionAPI faction, MarketAPI hive) {
		return orbitBlockReason(faction, hive, KIND_SUPPORT);
	}

	/** Why this faction cannot send Defend over the world now, or null if it can. */
	public static String defendBlockReason(FactionAPI faction, MarketAPI hive) {
		return orbitBlockReason(faction, hive, KIND_DEFEND);
	}

	/** Why this faction cannot send a Support or Defend sortie over the world now, or null if it can. */
	public static String orbitBlockReason(FactionAPI faction, MarketAPI hive, String kind) {
		if (!orbitEnabled(kind)) return orbitName(kind) + " sorties are disabled in the mod settings.";
		if (faction == null || hive == null) return "No target.";
		if (hasOrder(kind, faction.getId(), hive.getId())) return "A task force is already on its way there.";
		if (faction.isPlayerFaction()) {
			if (nearestReassignable(faction, hive, kind) != null) return null;
			ThreatAid.Quote q = ThreatAid.quoteDefend(hive);
			return q.ok() ? null : q.reason;
		}
		return pickBase(faction, hive.getLocationInHyperspace()) == null ? "No base in reach." : null;
	}

	protected static Order record(CampaignFleetAPI fleet, FactionAPI faction, String kind,
			MarketAPI base, String targetId, String targetName, float days) {
		Order o = new Order();
		o.fleet = fleet;
		o.factionId = faction.getId();
		o.kind = kind;
		o.baseMarketId = base.getId();
		o.targetId = targetId;
		o.targetName = targetName;
		o.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		o.days = days;
		all().add(o);
		ThreatIncConfig.log("Order issued: " + faction.getId() + " " + o.task() + " from "
				+ base.getName());
		return o;
	}

	/**
	 * Puts an EXISTING fleet on the hive system's door for interceptDays
	 * (the board's per-fleet Intercept on an expedition or task force,
	 * 2026-09-06): no new hull is built and nothing is charged - the fleet
	 * was paid for when it sailed - it is simply retasked, and goes home to
	 * {@code base} on the tracked leg when the order runs out or is recalled.
	 */
	public static Order adoptIntercept(CampaignFleetAPI fleet, FactionAPI faction,
			StarSystemAPI hive, MarketAPI base) {
		if (fleet == null || !fleet.isAlive() || faction == null || hive == null) return null;
		if (base == null) base = pickBase(faction, hive.getLocation());
		if (base == null) return null;
		SectorEntityToken point = interceptPoint(hive);
		if (point == null) return null;
		float days = ThreatIncConfig.interceptDays();
		String where = point.getName() != null ? point.getName()
				: "the " + hive.getNameWithLowercaseTypeShort() + " jump-point";
		fleet.clearAssignments();
		// nothing of its old group or task force moves it any more
		ThreatPurgeFGI.cutLoose(fleet);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, point, days,
				"intercepting at " + where);
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_INTERCEPT, base, hive.getId(), where, days);
		announce(faction, fleet.getName() + " is detached to intercept the swarm at " + where
				+ " for " + (int) days + " days.");
		return o;
	}

	// ------------------------------------------------------------------
	// repurposing: an order given to a fleet already out
	// ------------------------------------------------------------------

	/** A player fleet under the layer's orders that a new order could take over. */
	public static class Reassignable {
		public CampaignFleetAPI fleet;
		/** What it is doing now: "besieging the Gamma Hero system", "intercepting at ...", "returning to ...". */
		public String duty;
		public boolean inSystem;
		public float distLY;

		/** "in the system" or "N ly away", for the prompt. */
		public String where() {
			return inSystem ? "in the system" : Math.round(distLY) + " ly away";
		}
	}

	/**
	 * The player's fleet nearest a hive world among those the layer has out:
	 * fleets on tracked legs home, intercepts and Support elsewhere, guards
	 * of other factions' colonies, expedition fleets in the open, task
	 * forces. Fleets in the world's system come first, closest to it first;
	 * then by hyperspace distance. Staged guards over the player's own
	 * colonies are not taken - they are the pool a new task force is raised
	 * from. Null when nothing is out.
	 */
	public static Reassignable nearestReassignable(FactionAPI faction, MarketAPI hive) {
		return nearestReassignable(faction, hive, KIND_SUPPORT);
	}

	/**
	 * As above, for an order of {@code kind} over the world: a fleet already
	 * flying that order there is not taken; one on the other orbit order
	 * there is - Support and Defend swap a fleet's doctrine.
	 */
	public static Reassignable nearestReassignable(FactionAPI faction, MarketAPI hive, String kind) {
		if (faction == null || !faction.isPlayerFaction() || hive == null
				|| hive.getPrimaryEntity() == null) {
			return null;
		}
		String fid = faction.getId();
		List<Reassignable> found = new ArrayList<Reassignable>();
		java.util.Set<CampaignFleetAPI> seen = new java.util.HashSet<CampaignFleetAPI>();
		// tracked legs first: an expedition fleet already withdrawing reads as such
		for (ThreatReturns.Return r : ThreatReturns.all()) {
			if (!fid.equals(r.factionId)) continue;
			ThreatBases.Base home = ThreatBases.of(r.homeMarketId);
			consider(found, seen, r.fleet, "returning to " + (home != null ? home.name() : "base"),
					hive);
		}
		for (Order o : all()) {
			if (!fid.equals(o.factionId) || o.aid || o.recipientFactionId != null) continue;
			if (KIND_GUARD.equals(o.kind) && o.targetId != null) {
				ThreatBases.Base t = ThreatBases.of(o.targetId);
				if (t != null && fid.equals(t.factionId())) continue; // staging, or holding an outpost
			}
			if (kind != null && kind.equals(o.kind) && hive.getId().equals(o.targetId)) continue;
			// a landing's cover is not the nearest spare fleet for some other
			// world; over its own world the other orbit order may still take it
			if (KIND_DEFEND.equals(o.kind) && o.indefinite() && !hive.getId().equals(o.targetId)) continue;
			consider(found, seen, o.fleet, o.task(), hive);
		}
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI p = (ThreatPurgeFGI) curr;
			if (p.getFaction() == null || !fid.equals(p.getFaction().getId())) continue;
			if (!p.isSpawnedFleets()) continue;
			StarSystemAPI where = p.getParams() != null && p.getParams().raidParams != null
					? p.getParams().raidParams.where : null;
			String duty = "besieging the "
					+ (where != null ? where.getNameWithLowercaseTypeShort() : "hive");
			for (CampaignFleetAPI fleet : p.getFleets()) consider(found, seen, fleet, duty, hive);
		}
		for (Object curr : IncursionManager.getResponseList()) {
			if (!(curr instanceof ThreatResponseIntel)) continue;
			ThreatResponseIntel r = (ThreatResponseIntel) curr;
			if (r.getFaction() == null || !fid.equals(r.getFaction().getId())) continue;
			String duty = r.getTargetColonyName() != null ? "attacking " + r.getTargetColonyName()
					: "standing down";
			for (CampaignFleetAPI fleet : r.livingFleets()) consider(found, seen, fleet, duty, hive);
		}
		Reassignable best = null;
		for (Reassignable c : found) {
			if (best == null || c.distLY < best.distLY) best = c;
		}
		return best;
	}

	protected static void consider(List<Reassignable> found, java.util.Set<CampaignFleetAPI> seen,
			CampaignFleetAPI fleet, String duty, MarketAPI hive) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired() || fleet.getFleetPoints() <= 0) return;
		if (seen.contains(fleet)) return;
		seen.add(fleet);
		SectorEntityToken planet = hive.getPrimaryEntity();
		Reassignable c = new Reassignable();
		c.fleet = fleet;
		c.duty = duty;
		c.inSystem = fleet.getContainingLocation() == planet.getContainingLocation();
		// in-system fleets rank under a light-year, closest to the world first
		c.distLY = c.inSystem ? Misc.getDistance(fleet, planet) / 1000000f
				: Misc.getDistanceLY(fleet.getLocationInHyperspace(), hive.getLocationInHyperspace());
		found.add(c);
	}

	/**
	 * Frees a fleet from whatever the layer had it doing - its expedition or
	 * task force (detached, the group's baseline adjusted), its standing
	 * order, its tracked leg home - so a new order can take it. Its ledger
	 * entries ride the fleet regardless. Returns the base it was to go home
	 * to, or null.
	 */
	public static MarketAPI takeOver(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		MarketAPI base = null;
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI p = (ThreatPurgeFGI) curr;
			if (!p.getFleets().contains(fleet)) continue;
			if (p.sourceBase() != null) base = p.sourceBase();
			p.detach(fleet);
		}
		for (Object curr : IncursionManager.getResponseList()) {
			if (curr instanceof ThreatResponseIntel) ((ThreatResponseIntel) curr).detach(fleet);
		}
		for (Order o : new ArrayList<Order>(all())) {
			if (o.fleet != fleet) continue;
			all().remove(o);
			MarketAPI m = Global.getSector().getEconomy().getMarket(o.baseMarketId);
			if (m != null) base = m;
		}
		for (ThreatReturns.Return r : new ArrayList<ThreatReturns.Return>(ThreatReturns.all())) {
			if (r.fleet != fleet) continue;
			ThreatReturns.all().remove(r);
			MarketAPI m = Global.getSector().getEconomy().getMarket(r.homeMarketId);
			if (m != null) base = m;
		}
		if (base == null && ThreatReturns.homeOf(fleet) != null) {
			base = Global.getSector().getEconomy().getMarket(ThreatReturns.homeOf(fleet));
		}
		ThreatSwarmDefend.release(fleet);
		ThreatPurgeFGI.cutLoose(fleet);
		return base;
	}

	/**
	 * Puts an EXISTING fleet over a besieged world for supportDays
	 * (2026-09-06): nothing built or charged, the fleet taken from its
	 * current duty ({@link #takeOver}) and sent home on the tracked leg to
	 * the base it was already bound for when the order runs out or is
	 * recalled. {@code duty} is what it was doing, for the announcement.
	 */
	public static Order adoptSupport(CampaignFleetAPI fleet, FactionAPI faction, MarketAPI hive,
			String duty) {
		return adoptOrbit(fleet, faction, hive, duty, KIND_SUPPORT);
	}

	/** As {@link #adoptSupport}, for Defend. */
	public static Order adoptDefend(CampaignFleetAPI fleet, FactionAPI faction, MarketAPI hive,
			String duty) {
		return adoptOrbit(fleet, faction, hive, duty, KIND_DEFEND);
	}

	/** An existing fleet takes a Support or Defend order over the world. */
	public static Order adoptOrbit(CampaignFleetAPI fleet, FactionAPI faction, MarketAPI hive,
			String duty, String kind) {
		if (!orbitEnabled(kind)) return null;
		if (fleet == null || !fleet.isAlive() || faction == null || hive == null
				|| hive.getPrimaryEntity() == null) {
			return null;
		}
		// somewhere to go home to, before the fleet is cut from its owners
		MarketAPI fallback = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(hive.getLocationInHyperspace())
				: pickBase(faction, hive.getLocationInHyperspace());
		if (fallback == null && ThreatReturns.homeOf(fleet) == null) return null;
		MarketAPI base = takeOver(fleet);
		if (base == null || base.getPrimaryEntity() == null) base = fallback;
		if (base == null || base.getPrimaryEntity() == null) return null;
		float days = orbitDays(kind);
		fleet.clearAssignments();
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		if (ThreatReturns.homeOf(fleet) == null) ThreatReturns.provision(fleet, base.getId(), 0f, 0f);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(),
				days > 0f ? days : NO_TERM_DAYS, orbitTask(kind, hive.getName()));
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, kind, base, hive.getId(), hive.getName(), days);
		announce(faction, fleet.getName() + (duty != null ? " breaks off " + duty : "")
				+ " to " + orbitVerb(kind) + " " + hive.getName() + " for " + (int) days + " days.");
		return o;
	}

	/**
	 * An expedition fleet that has just landed or reinforced a front stays
	 * over the world on DEFEND until the front is gone (2026-09-07, the user:
	 * "after a siege expedition has dropped off its marines it should hold
	 * the space above the sieged planet"): a real order, so the board shows
	 * it with Recall and runs can land under it, with no term - {@link #poll}
	 * ends it when the front is gone or the batteries have ground the fleet
	 * below defendMinStrength, and sends it home on the tracked leg. It
	 * bombards only while the front cannot hold. The caller has already
	 * taken the fleet out of its expedition. The swarm's own fleets do the
	 * same through {@link ThreatSwarmDefend}.
	 */
	public static Order adoptLandingDefend(CampaignFleetAPI fleet, FactionAPI faction,
			MarketAPI world, MarketAPI base) {
		if (!ThreatIncConfig.landingDefendEnabled()) return null;
		if (fleet == null || faction == null || world == null || base == null) return null;
		if (world.getPrimaryEntity() == null || !fleet.isAlive() || fleet.isExpired()) return null;
		for (Order o : new ArrayList<Order>(all())) {
			if (o.fleet == fleet) all().remove(o);
		}
		ThreatSwarmDefend.release(fleet);
		ThreatPurgeFGI.cutLoose(fleet);
		fleet.clearAssignments();
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		if (ThreatReturns.homeOf(fleet) == null) ThreatReturns.provision(fleet, base.getId(), 0f, 0f);
		// the strength baseline the health floor reads is what arrived over
		// the world, whatever an earlier order stamped at its launch
		ThreatReturns.rebaseline(fleet);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, world.getPrimaryEntity(), NO_TERM_DAYS,
				orbitTask(KIND_DEFEND, world.getName()));
		Order o = record(fleet, faction, KIND_DEFEND, base, world.getId(), world.getName(), 0f);
		announce(faction, fleet.getName() + " stays over " + world.getName()
				+ " to defend the landing.");
		return o;
	}

	/**
	 * THE LEASH (2026-09-07, per frame from IncursionManager.advance, the
	 * garrison swarms' own rule mirrored): a Support or Defend fleet beyond
	 * ORBIT_HOLD_RANGE of its world, and not battle-locked, drops whatever it
	 * was chasing and returns. Assignments alone cannot bring it back -
	 * MAKE_AGGRESSIVE's pursuit AI overrides any travel order - so blinders
	 * go on for the trip.
	 *
	 * HOLD STATION (the same evening): by default the blinders never come off.
	 * A fleet under a Support or Defend order, or a swarm's station, occupies
	 * the orbit, fights what attacks it or meets it there, and never pursues;
	 * orbitFleetsHunt restores the hunting reflexes at the planet. With
	 * hunting on, four Defend fleets over Gamma Hero II had at most one at
	 * the planet on any day: an arrival put its reflexes back, the next swarm
	 * that ran drew it off again, and a fleet locked in that fight or on the
	 * long way back is one the leash cannot touch and the siege cannot count.
	 */
	public static void enforceLeash() {
		for (Order o : all()) {
			if (!KIND_SUPPORT.equals(o.kind) && !KIND_DEFEND.equals(o.kind)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			MarketAPI world = o.targetId != null
					? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (world == null || world.getPrimaryEntity() == null) continue;
			MarketAPI base = o.baseMarketId != null
					? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
			float days = o.indefinite() ? NO_TERM_DAYS : Math.max(1f, o.daysLeft());
			leash(o.fleet, world, days, orbitTask(o.kind, world.getName()),
					base != null ? base.getPrimaryEntity() : null, orbitName(o.kind));
		}
	}

	/**
	 * One fleet's leash to the orbit of a world: home within ORBIT_HOLD_RANGE
	 * (hunting reflexes restored), beyond it and out of battle a blinkered
	 * GO_TO_LOCATION back, the orbit re-queued behind it and the return leg
	 * to {@code home} behind that. Shared with {@link ThreatSwarmDefend}.
	 */
	public static void leash(CampaignFleetAPI fleet, MarketAPI world, float orbitDays, String orbitText,
			SectorEntityToken home, String label) {
		if (fleet == null || world == null || world.getPrimaryEntity() == null) return;
		SectorEntityToken planet = world.getPrimaryEntity();
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		// Hold station - unless the orbit itself is contested. A fleet that
		// will not fight what is sitting on top of it is not defending
		// anything, and the thing most likely to be sitting there is a station
		// back from its repairs (ORBIT_FIGHT_KEY). The anti-chase rule is kept
		// whole: aggression at the planet only, never long pursuit, and the
		// leash below still walks it back from anything it follows.
		boolean fight = fightingOrbit(fleet);
		boolean hunt = ThreatIncConfig.orbitFleetsHunt() || fight;
		if (fight) {
			mem.unset(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT);
			mem.unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
			if (nearPlanet(fleet, planet)) {
				mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
				mem.set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true, PURSUIT_FLAG_DAYS);
			}
		}
		if (!hunt && !mem.getBoolean(MemFlags.FLEET_IGNORES_OTHER_FLEETS)) {
			// holding station: no pursuit for the whole order (the order's
			// own MAKE_AGGRESSIVE stamp is undone here, the frame after)
			mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
			mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		}
		if (nearPlanet(fleet, planet)) {
			if (hunt && mem.getBoolean(MemFlags.FLEET_IGNORES_OTHER_FLEETS)) {
				mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
				mem.set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
			}
			return;
		}
		if (fleet.getBattle() != null) return;
		mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
		com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI curr = fleet.getCurrentAssignment();
		if (curr != null && curr.getTarget() == planet
				&& curr.getAssignment() == FleetAssignment.GO_TO_LOCATION) {
			return; // already on the way back
		}
		float dist = fleet.getContainingLocation() == planet.getContainingLocation()
				? Misc.getDistance(fleet, planet) : -1f;
		// what it was doing instead names the other writer when one keeps
		// re-tasking the fleet (a Support fleet at Gamma Brador IV was
		// recalled every poll all the way in)
		ThreatIncConfig.log(label + " over " + world.getName() + ": " + fleet.getName()
				+ " strayed " + (dist < 0f ? "out of the system" : (int) dist + " units")
				+ " (was: " + describe(curr) + ") - recalled to the orbit");
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 30f,
				"returning to the orbit of " + world.getName());
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet, orbitDays, orbitText);
		if (home != null && orbitDays < NO_TERM_DAYS) {
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, home, 1000f,
					"returning to " + home.getName());
		}
	}

	/**
	 * SIEGE LEASH (2026-09-07): an expedition fleet's tether to the world it
	 * was sent to besiege, and the only thing that decides whether it hunts.
	 *
	 * Vanilla's raid fleets never go looking for a fight - FGRaidAction
	 * refreshes $doNotGetSidetracked on every fleet every PURSUIT_FLAG_DAYS,
	 * and that is the whole of how a human expedition stays on its target. A
	 * Threat fleet is born MAKE_AGGRESSIVE with ALLOW_LONG_PURSUIT
	 * (DisposableThreatFleetManager), and the fight for the orbit used to
	 * strip vanilla's blinkers off every expedition fleet in the system for as
	 * long as any target's orbit was contested. One defender that ran drew the
	 * swarm out after it; with nothing friendly left near the world "contested"
	 * then read true forever, so the aggression renewed itself daily and the
	 * expedition never came back. A Thanatos strike spent three weeks at 0/3
	 * passes chasing one fleet across the sector.
	 *
	 * Per fleet, per tick:
	 * <ul>
	 * <li>Within siegeHuntRange of a world whose orbit is contested - and no
	 * further. The fight for the orbit is bounded by the range the contest
	 * itself is measured over, so a defender that breaks off is one there is
	 * nothing left to fight for. Aggression is granted here and nowhere else,
	 * and never long pursuit: it fights over the world, it does not follow.</li>
	 * <li>Anywhere else it is on the job - vanilla's blinkers back on, pursuit
	 * off. Inside the target system that is all it takes: the raid's own
	 * MilitaryResponseScript walks a fleet back to the target once nothing is
	 * overriding its heading.</li>
	 * <li>Beyond siegeLeashRange, or out of the system entirely where no
	 * response script reaches, it drops what it is doing and is sent back.</li>
	 * </ul>
	 *
	 * @param contested worlds whose orbit is held against the besieger
	 * @param anchor    the target this fleet should be over ({@link #nearestWorld})
	 * @param mayHunt   whether the doctrine fights for the orbit at all
	 */
	public static void siegeLeash(CampaignFleetAPI fleet, List<MarketAPI> contested,
			MarketAPI anchor, boolean mayHunt, String label) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		// an expedition never follows a runner out of the orbit it is fighting
		// for - stripped here as well as at spawn so a fleet already in flight
		// in an old save lets go of the chase it is on
		mem.unset(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT);

		if (mayHunt && contested != null) {
			for (MarketAPI world : contested) {
				if (world == null || world.getPrimaryEntity() == null) continue;
				SectorEntityToken planet = world.getPrimaryEntity();
				if (fleet.getContainingLocation() != planet.getContainingLocation()) continue;
				if (Misc.getDistance(fleet, planet) > ThreatIncConfig.siegeHuntRange()) continue;
				mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
				mem.unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
				mem.set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true, PURSUIT_FLAG_DAYS);
				return;
			}
		}

		// On the job. The blinkers are vanilla's to set, not ours: FGRaidAction
		// puts them back every interval, and withholds them on purpose while an
		// ally of this expedition is fighting within 1,000 units so the fleet
		// may still go and help. Undoing that was the whole bug - all that is
		// needed here is to stop undoing it.
		mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);

		if (anchor == null || anchor.getPrimaryEntity() == null) return;
		SectorEntityToken planet = anchor.getPrimaryEntity();
		boolean inSystem = fleet.getContainingLocation() == planet.getContainingLocation();
		float dist = inSystem ? Misc.getDistance(fleet, planet) : -1f;
		if (inSystem && dist <= ThreatIncConfig.siegeLeashRange()) {
			mem.unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			return;
		}
		if (fleet.getBattle() != null) return;
		// blinders for the trip back: a fleet that has let one chase go picks
		// up the next thing it passes otherwise
		mem.set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI curr = fleet.getCurrentAssignment();
		if (curr != null && curr.getTarget() == planet
				&& curr.getAssignment() == FleetAssignment.GO_TO_LOCATION) {
			return; // already on the way back
		}
		ThreatIncConfig.log(label + " on " + anchor.getName() + ": " + fleet.getName()
				+ " strayed " + (dist < 0f ? "out of the system" : (int) dist + " units")
				+ " (was: " + describe(curr) + ") - recalled to the siege");
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 60f,
				"returning to " + anchor.getName());
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, planet,
				Math.max(1f, ThreatIncConfig.siegeOrbitDays()), "besieging " + anchor.getName());
	}

	/**
	 * The target a fleet is nearest to - the one world of an expedition's
	 * several its leash is measured against. Anything in another location is
	 * further than anything in this one.
	 */
	public static MarketAPI nearestWorld(CampaignFleetAPI fleet, List<MarketAPI> worlds) {
		MarketAPI best = null;
		float bestDist = 0f;
		for (MarketAPI world : worlds) {
			if (world == null || world.getPrimaryEntity() == null) continue;
			SectorEntityToken planet = world.getPrimaryEntity();
			float dist = fleet != null
					&& fleet.getContainingLocation() == planet.getContainingLocation()
							? Misc.getDistance(fleet, planet) : Float.MAX_VALUE;
			if (best == null || dist < bestDist) {
				best = world;
				bestDist = dist;
			}
		}
		return best;
	}

	/** "ORBIT_AGGRESSIVE -> Gamma Hero II 'defending the orbit of Gamma Hero II'", or "no assignment". */
	public static String describe(com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI a) {
		if (a == null) return "no assignment";
		String target = a.getTarget() != null ? a.getTarget().getName() : "-";
		String text = a.getActionText() != null ? " '" + a.getActionText() + "'" : "";
		return a.getAssignment() + " -> " + target + text;
	}

	/** Once a day per fleet: the station report's memory key, shared with the slice line so a fleet writes one line a day. */
	public static final String STATION_LOG_KEY = "$threatinc_stationLogged";

	/**
	 * Once a day, where a fleet that is NOT at its world is and what it is
	 * doing: distance or system, its current assignment, whether it is in a
	 * battle, blinkered or hunting. The slice line covers a fleet at the
	 * planet; without this the log was silent on the rest (2026-09-07: three
	 * of four Defend fleets logged nothing for days).
	 */
	public static void stationReport(CampaignFleetAPI fleet, MarketAPI world, String label) {
		if (fleet == null || world == null || world.getPrimaryEntity() == null) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (mem.getBoolean(STATION_LOG_KEY)) return;
		mem.set(STATION_LOG_KEY, true, 1f);
		SectorEntityToken planet = world.getPrimaryEntity();
		String where;
		if (fleet.getContainingLocation() == planet.getContainingLocation()) {
			where = (int) Misc.getDistance(fleet, planet) + " units out";
		} else {
			where = "out of the system, in "
					+ (fleet.getContainingLocation() != null ? fleet.getContainingLocation().getName() : "?");
		}
		ThreatIncConfig.log(label + " over " + world.getName() + ": " + fleet.getName() + " at "
				+ (int) fleet.getFleetPoints() + " FP is " + where + ", " + describe(fleet.getCurrentAssignment())
				+ (fleet.getBattle() != null ? ", in a battle" : "")
				+ (mem.getBoolean(MemFlags.FLEET_IGNORES_OTHER_FLEETS) ? ", blinkered" : ", hunting"));
	}

	/** Once a day, a Defend fleet at its world that is not bombarding, and why. */
	public static void idleReport(CampaignFleetAPI fleet, MarketAPI world, String label, String why) {
		if (fleet == null || world == null) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (mem.getBoolean(STATION_LOG_KEY)) return;
		mem.set(STATION_LOG_KEY, true, 1f);
		ThreatIncConfig.log(label + " over " + world.getName() + ": " + fleet.getName() + " at "
				+ (int) fleet.getFleetPoints() + " FP holds the orbit - " + why);
	}

	/** Sends the fleet home (refund on arrival, see ThreatReturns) and forgets the order. */
	public static void recall(Order o) {
		if (o == null) return;
		all().remove(o);
		CampaignFleetAPI fleet = o.fleet;
		if (fleet == null || !fleet.isAlive()) return;
		ThreatReturns.sendHome(fleet, o.factionId, o.baseMarketId);
		ThreatIncConfig.log("Order recalled: " + o.factionId + " " + o.task());
	}

	protected static void announce(FactionAPI faction, String what) {
		String who = faction.isPlayerFaction() ? "Your"
				: Misc.ucFirst(faction.getDisplayNameWithArticle());
		ThreatColonyManager.announceAlways(who + " " + what, Misc.getHighlightColor());
	}
}
