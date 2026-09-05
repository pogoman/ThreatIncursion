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
 * ORBIT_AGGRESSIVE at the world), then home.</li>
 * <li>INTERCEPT - a task force on ORBIT_AGGRESSIVE at the hive system's
 * jump-point nearest its colony for interceptDays: it meets the swarm's
 * reinforcements and expeditions at the door.</li>
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

	/** One standing order and the fleet flying it. */
	public static class Order {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String kind;
		public String baseMarketId;
		/** Market id (guard) or system id (intercept). */
		public String targetId;
		public String targetName;
		public long issuedTimestamp;
		public float days;
		/** The faction whose colony is guarded when it is not the sender's own; null otherwise. */
		public String recipientFactionId;
		/** A player aid sortie: paid in credits, on the capacity ledger, earning standing on arrival. */
		public boolean aid;
		/** Whether an aid sortie has reached its station. */
		public boolean arrived;

		public float daysLeft() {
			return Math.max(0f, days - Global.getSector().getClock()
					.getElapsedDaysSince(issuedTimestamp));
		}

		public String task() {
			if (KIND_GUARD.equals(kind)) return "guarding " + targetName;
			if (KIND_INTERCEPT.equals(kind)) return "intercepting at " + targetName;
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
	 * fleet's own queue takes it home). Aid sorties report their arrival on
	 * station and their term served, for the standing they earn.
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
			if (o.aid && !o.arrived && atStation(o)) {
				o.arrived = true;
				if (KIND_GUARD.equals(o.kind)) ThreatAid.onGuardArrived(o);
				else ThreatAid.onStrikeArrived(o);
			}
			if (o.daysLeft() <= 0f) {
				all().remove(o);
				if (o.aid && o.arrived && KIND_GUARD.equals(o.kind)) ThreatAid.onGuardCompleted(o);
				// time served: home on the tracked leg, refund on arrival
				ThreatReturns.sendHome(o.fleet, o.factionId, o.baseMarketId);
				ThreatIncConfig.log("Order complete: " + o.factionId + " " + o.task());
			}
		}
	}

	/** Whether the fleet is within arrival range of its station. */
	protected static boolean atStation(Order o) {
		SectorEntityToken station = null;
		if (KIND_GUARD.equals(o.kind)) {
			MarketAPI m = Global.getSector().getEconomy().getMarket(o.targetId);
			station = m != null ? m.getPrimaryEntity() : null;
		} else {
			station = interceptPoint(ThreatWarBoard.getSystem(o.targetId));
		}
		if (station == null || o.fleet == null) return false;
		return o.fleet.getContainingLocation() == station.getContainingLocation()
				&& Misc.getDistance(o.fleet, station) <= ThreatReturns.ARRIVAL_RANGE;
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
	// building sorties
	// ------------------------------------------------------------------

	/** The faction's nearest military world within expedition range of a hyperspace location, or null. */
	public static MarketAPI pickBase(FactionAPI faction, Vector2f hyperLoc) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (market.getStarSystem() == null || !IncursionManager.hasMilitary(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), hyperLoc);
			if (d > IncursionManager.expeditionRangeLY(market)) continue;
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
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);

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

	/** Combat points a sortie from this base sails with: the guard size, or what a player colony's free points allow. */
	protected static float sortieFP(FactionAPI faction, MarketAPI base) {
		if (faction.isPlayerFaction()) return ThreatAid.taskForceFP(base);
		return ThreatIncConfig.guardFleetFP();
	}

	/** Orbit superiority over a colony for guardDays, from the sender's best base. */
	public static Order dispatchGuard(FactionAPI faction, MarketAPI target) {
		if (faction == null || target == null) return null;
		MarketAPI base = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(target.getLocationInHyperspace())
				: pickBase(faction, target.getLocationInHyperspace());
		return dispatchGuard(faction, target, base, false);
	}

	/**
	 * Orbit superiority over a colony for guardDays, from a given base. With
	 * {@code aid} the sortie is player aid (announced and paid by ThreatAid);
	 * a guard of another faction's colony records that faction either way.
	 */
	public static Order dispatchGuard(FactionAPI faction, MarketAPI target, MarketAPI base,
			boolean aid) {
		if (faction == null || target == null || target.getPrimaryEntity() == null) return null;
		if (base == null) return null;
		float days = ThreatIncConfig.guardDays();
		float fp = sortieFP(faction, base);
		if (faction.isPlayerFaction() && fp < ThreatIncConfig.aidGuardMinFP()) return null;
		CampaignFleetAPI fleet = buildTaskForce(base, faction, fp, target.getLocationInHyperspace());
		if (fleet == null) return null;
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.getPrimaryEntity(), days,
				"guarding " + target.getName());
		// the home leg is GO_TO_LOCATION, not a despawn: poll() hands the fleet
		// to ThreatReturns when the order runs out, and that settles the refund
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_GUARD, base, target.getId(), target.getName(), days);
		o.aid = aid;
		if (!faction.getId().equals(target.getFactionId())) o.recipientFactionId = target.getFactionId();
		if (faction.isPlayerFaction()) {
			ThreatAidCapacity.commit(base, ThreatAidCapacity.taskForcePoints(fp), fleet,
					"guard of " + target.getName());
		}
		if (!aid) {
			announce(faction, "task force from " + base.getName() + " is moving to guard "
					+ target.getName() + " for " + (int) days + " days.");
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
			ThreatAidCapacity.commit(base, ThreatAidCapacity.taskForcePoints(fp), fleet,
					"intercept at " + where);
		}
		if (!aid) {
			announce(faction, "task force from " + base.getName() + " is moving to intercept the "
					+ "swarm at " + where + " for " + (int) days + " days.");
		}
		return o;
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
