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
 * <li>HUNT (2026-09-24, replacing INTERCEPT) - a task force on
 * ORBIT_AGGRESSIVE over the hive colony with the weakest standing garrison
 * for softenDays, moved on to the next as each garrison falls and sent home
 * when the system is clear or it is badly hurt ({@link ThreatSoftening}).
 * NPC hunting forces fly the same order. Intercept (holding the hive's
 * jump-point) is no longer given; orders already out in a save still run.</li>
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
 * player task force sent to another faction's colony or to hunt a hive -
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
	/** Hunts the Defense Swarms of a bountied hive system, colony by colony (ThreatSoftening, 2026-09-24). */
	public static final String KIND_HUNT = "hunt";

	/** How close a warship has to be to a planet to count as holding its orbit. */
	public static final float ORBIT_HOLD_RANGE = 1500f;
	/**
	 * The leash sits at LEASH_MULT x ORBIT_HOLD_RANGE, not the hold range itself: an
	 * ORBIT_AGGRESSIVE fleet rides at 1,500-1,700 units of its planet, and a leash at
	 * 1,500 recalled and re-tasked it every poll (hw191: 1,005 Relief and 391 Swarm-defend
	 * recalls in three games at a median 1,670 units; the reinforcement leash had the same
	 * defect, ThreatColonyManager.REINFORCEMENT_LEASH_MULT, hw186c). 2026-10-18.
	 */
	public static final float LEASH_MULT = 2f;

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

	/** Whether the fleet is flying a Hunt order now. */
	public static boolean onHunt(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		for (Order o : all()) {
			if (o.fleet == fleet && KIND_HUNT.equals(o.kind)) return true;
		}
		return false;
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
		/** An aid sortie's arrival standing has been earned - once, however many stations a hunt goes on to. */
		public boolean credited;
		/**
		 * The hive system a hunt works (KIND_HUNT): when its colony's swarms are
		 * gone it goes on to the system's next, even once that colony has left
		 * the economy and no longer says where it was. Null on older orders.
		 */
		public String systemId;
		/**
		 * The NPC hunting force this fleet sails with (ThreatSoftening.Force):
		 * its fleets muster, go in, move on and go home together. Null for the
		 * player's Hunt and on orders from before 2026-09-24.
		 */
		public String forceId;
		/**
		 * The player's Hunt press this fleet sails in (2026-10-11): every fleet of one
		 * dispatchHunt shares it, so the group speaks once (ThreatSoftening.speaks).
		 * Null for NPC hunts and on older orders.
		 */
		public String groupId;
		/** The loose swarm fleet a player hunt is chasing (chaseLoose); null while it holds an orbit. */
		public String chasingId;
		/** The player's request for aid this guard answers (ThreatAidRequests.Request.id, 2026-10-29): charged on arrival, the part reopened if lost. Null otherwise. */
		public String requestId;
		/**
		 * A RAID (2026-10-01, ThreatAttackPlanner): an NPC Support sortie over a
		 * hive with no front of its own - it holds the orbit and bombs it, sized
		 * by the planner. Never stood down for want of a front (supportLost);
		 * leaves on the contest, the commander's stop, or a third of it lost.
		 */
		public boolean raid;
		/** A raid's fleet points when it reached its world (0 until then). */
		public float arrivalFP;
		/** When a raid reached its world; 0 until then. */
		public long arrivedTimestamp;
		/** When a raid at its world first found the orbit contested; 0 while it is not. */
		public long contestedSince;
		/** The world a raid turned back on arrival strikes instead, if its fuel pays the detour; null none. */
		public String fallbackId;
		/** The raid this fleet sails in: every fleet of one dispatchRaid shares it. */
		public String raidId;
		/**
		 * A relief force (sendRelief) over an invaded world: it bombards the Threat
		 * army there while the defenders are losing (ThreatGroundFronts.tickReliefBombard)
		 * and, with no term (reliefStays), holds the orbit until the army is gone.
		 */
		public boolean relief;
		/**
		 * When a relief on station last found its world's system clear of Threat fleets
		 * (reliefQuiet); 0 while one is there, or until it arrives. Home after reliefQuietDays of it.
		 */
		public long quietSince;

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
			if (KIND_GUARD.equals(kind)) return (relief ? "relieving " : "guarding ") + targetName;
			if (KIND_INTERCEPT.equals(kind)) return "intercepting at " + targetName;
			if (KIND_HUNT.equals(kind)) {
				return (ThreatSoftening.mustering(this) ? "mustering to hunt the swarms over "
						: "hunting the swarms over ") + targetName;
			}
			if (KIND_SUPPORT.equals(kind) || KIND_DEFEND.equals(kind)) {
				// fighting for the orbit first; then besieging (Support), or
				// holding it and covering a front that cannot hold (Defend)
				MarketAPI world = targetId != null
						? Global.getSector().getEconomy().getMarket(targetId) : null;
				if (world != null && ThreatGroundFronts.orbitContestedFor(factionId, world)) {
					return "clearing the orbit of " + targetName;
				}
				if (raid) return "raiding " + targetName;
				if (KIND_SUPPORT.equals(kind)) return "supporting the siege of " + targetName;
				if (world != null && ThreatGroundFronts.defendRazes(factionId, world, fleet)) {
					return "razing " + targetName;
				}
				if (world != null && ThreatGroundFronts.defendBombards(factionId, world, fleet)) {
					return "covering the front on " + targetName;
				}
				if (world != null && ThreatGroundFronts.defendFabricates(factionId, world, fleet)) {
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
					ThreatNotice.titled("Task Force Lost").bad().icon(Global.getSector().getPlayerFaction())
							.line("Your task force was %s", o.task()).send();
				}
				ThreatIncConfig.log("Order lost (fleet destroyed): " + o.factionId + " " + o.task());
				if (o.raid) {
					ThreatAttackPlanner.news(o.factionId, "a raid on " + o.targetName + " was destroyed");
					ThreatPlays.raidEnded(o, "destroyed");
				}
				continue;
			}
			if (!o.arrived && atStation(o)) {
				o.arrived = true;
				if (o.aid && !o.credited) {
					o.credited = true;
					if (KIND_GUARD.equals(o.kind)) ThreatAid.onGuardArrived(o);
					else ThreatAid.onStrikeArrived(o);
				}
				// an NPC guard answering the player's request for aid: received, charged (ThreatAidRequests)
				if (o.requestId != null) ThreatAidRequests.onGuardArrived(o);
				if (o.raid) raidArrived(o);
			}
			// the player's guard over their own colony: its hulls are the host's (every poll - a
			// guard already on station when this rule came in is caught here too)
			rehome(o);
			if (o.raid) {
				// a raid keeps its own rules (raidOver); the rest of the poll is for
				// fronts' Support, which it is not
				String why = raidOver(o);
				if (why != null && !divertRaid(o, why)) endRaid(o, why);
				continue;
			}
			// a guard whose outpost died (or was scuttled) has nothing left to hold
			boolean stationGone = KIND_GUARD.equals(o.kind) && o.targetId != null
					&& ThreatBases.of(o.targetId) == null;
			// a landing's Defend (no term) stays until the front it covers is
			// gone, or it is below defendMinStrength and not committed
			// (defendCommitted: the swarm feeding its front its own hulls; a
			// navy over its own front until worn AND outweighed, 2026-09-28)
			boolean frontGone = KIND_DEFEND.equals(o.kind) && o.indefinite()
					&& (ThreatGroundFronts.getFront(o.targetId) == null
					|| (ThreatReturns.orderHealth(o.fleet) < ThreatIncConfig.defendMinStrength()
					&& !ThreatGroundFronts.defendCommitted(o.fleet, o.factionId, o.targetId)));
			// an NPC Support sortie outweighed over its own front goes home intact
			// like a navy's Defend, not on to die (run 6: 111 FP against 4,800)
			boolean supportLost = KIND_SUPPORT.equals(o.kind) && !o.aid && !Factions.PLAYER.equals(o.factionId)
					&& !ThreatGroundFronts.navyHoldsOver(o.fleet, o.factionId, o.targetId != null
							? Global.getSector().getEconomy().getMarket(o.targetId) : null);
			// a relief with no term holds until the Threat army on its world is gone,
			// or it is worn AND outweighed (2026-10-03, the user: relief stays until
			// the invaders are defeated)
			boolean reliefDone = KIND_GUARD.equals(o.kind) && o.relief && o.indefinite()
					&& ThreatGroundFronts.reliefDone(o.fleet, o.targetId);
			// ... or its system has held no Threat fleet for reliefQuietDays (2026-10-24, the user)
			boolean reliefQuiet = KIND_GUARD.equals(o.kind) && o.relief && o.indefinite() && !reliefDone
					&& reliefQuiet(o);
			if (reliefQuiet) noteReliefQuiet(o);
			if (o.daysLeft() <= 0f || stationGone || frontGone || supportLost || reliefDone || reliefQuiet) {
				all().remove(o);
				if (o.aid && o.arrived && KIND_GUARD.equals(o.kind)) ThreatAid.onGuardCompleted(o);
				// a hunting force's follower still wears the force's blinkers; home unable to react otherwise
				if (KIND_HUNT.equals(o.kind)) o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
				// time served: home on the tracked leg, refund on arrival
				ThreatReturns.sendHome(o.fleet, o.factionId, o.baseMarketId);
				ThreatIncConfig.log("Order " + (stationGone ? "void - station gone: "
						: reliefQuiet ? "home - system quiet: " : frontGone || supportLost || reliefDone ? "stood down: " : "complete: ")
						+ o.factionId + " " + o.task());
			}
		}
	}

	/** Whether the fleet is within arrival range of its station. */
	protected static boolean atStation(Order o) {
		SectorEntityToken station = null;
		if (KIND_HUNT.equals(o.kind)) {
			MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			station = hive != null ? hive.getPrimaryEntity() : null;
		} else if (KIND_GUARD.equals(o.kind) || KIND_SUPPORT.equals(o.kind) || KIND_DEFEND.equals(o.kind)) {
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
	 * Hulls belong where they land (the user, 2026-10-12: a fleet returned to Haven read half at
	 * Arkoman's Hulls cell, twice): a task force of the player's on station over one of their
	 * colonies is that colony's from then on - every ledger entry it holds charged to another
	 * colony moves to the host ({@link ThreatHulls#moveHome}: the host's share up, the sender's
	 * down), and Recall takes it home to the host. Nothing to do when every entry is the host's.
	 */
	public static void rehome(Order o) {
		// the board's Guard is an aid order (o.aid: paid, on the capacity ledger) - until 2026-10-12
		// 12:40 this returned on o.aid and so never ran for the very orders it was written for
		if (o == null || !KIND_GUARD.equals(o.kind) || o.recipientFactionId != null || !o.arrived) return;
		if (!ThreatHulls.enabled() || o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) return;
		if (!Global.getSector().getPlayerFaction().getId().equals(o.factionId)) return;
		MarketAPI host = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		if (host == null || !host.isPlayerOwned()) return;
		for (ThreatAidCapacity.Commitment c : ThreatAidCapacity.heldAllBy(o.fleet)) {
			if (c.marketId == null || host.getId().equals(c.marketId)) continue;
			if (ThreatHulls.moveHome(c.marketId, host, c.fp)) c.marketId = host.getId();
		}
		o.baseMarketId = host.getId();
	}

	/**
	 * A staged task force folds into a sortie from its host: the order ends,
	 * what the fleet carries and drew is settled at the host (the ships are
	 * here), and the fleet leaves the sector - its ledger entry already rides
	 * the sortie (ThreatAidCapacity.foldStaged).
	 */
	public static void fold(Order o, MarketAPI host) {
		fold(o, host, false);
	}

	/**
	 * As above; {@code keep} true leaves the fleet where it stands, alive and untouched, for the
	 * sortie to take as it is ({@link #retask}): the player's task force on station sails itself
	 * (the user, 2026-10-11: "rather than staging fleet being used the game deletes them then
	 * respawns them").
	 */
	public static void fold(Order o, MarketAPI host, boolean keep) {
		if (o == null) return;
		all().remove(o);
		CampaignFleetAPI fleet = o.fleet;
		if (fleet == null || !fleet.isAlive()) return;
		// settled where it stands: it never flies its leg home (ThreatReturns.fuelBack)
		ThreatReturns.settle(fleet, host, false);
		if (keep) return;
		if (!ThreatReturns.kept(fleet)) Misc.fadeAndExpire(fleet);
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
			return "Your faction is not mobilised.";
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

	/** Fleet points of the live guard task forces of any faction bound for or over the colony. */
	public static float guardPointsFor(String marketId) {
		float fp = 0f;
		if (marketId == null) return fp;
		for (Order o : all()) {
			if (!KIND_GUARD.equals(o.kind) || !marketId.equals(o.targetId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) fp += o.fleet.getFleetPoints();
		}
		return fp;
	}

	/** Fleet points of the player's own live guard and Defend fleets bound for or over the colony, plus the player's fleet when it is over the world. */
	public static float playerPointsFor(MarketAPI market) {
		float fp = 0f;
		if (market == null || Global.getSector().getPlayerFaction() == null) return fp;
		String pid = Global.getSector().getPlayerFaction().getId();
		for (Order o : all()) {
			if (!market.getId().equals(o.targetId) || !pid.equals(o.factionId)) continue;
			if (!KIND_GUARD.equals(o.kind) && !KIND_DEFEND.equals(o.kind)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) fp += o.fleet.getFleetPoints();
		}
		CampaignFleetAPI own = Global.getSector().getPlayerFleet();
		if (own != null && own.isAlive() && market.getPrimaryEntity() != null && nearPlanet(own, market.getPrimaryEntity())) fp += own.getFleetPoints();
		return fp;
	}

	/**
	 * THE PLAYER AT THE WALL (the user, 2026-10-23, Sindria: "i clicked the defend button and sent loads of my own
	 * fleets to help them. Perhaps that should trigger them to realise if they send fleets with my fleets we can
	 * win"): the player's own fleets over an invaded world are the commitment the enough gates wait for. While any
	 * stand there, the owner and its partners send what relief they can build - reliefEnough,
	 * ThreatCoalition.jointEnough / jointRelief and the one-body stand-down are off for that world. The player
	 * judges the odds and withdraws to stop them. Knob reliefFollowsPlayer.
	 */
	public static boolean playerAtTheWall(MarketAPI market) {
		if (market == null || !ThreatIncConfig.reliefFollowsPlayer()) return false;
		if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()))) return false;
		return playerPointsFor(market) > 0f;
	}

	/**
	 * What lifting the Threat's hold over the colony takes: the swarm's points
	 * over it times reliefOrbitMargin (1.0 since 2026-10-22; the siege's own 1.5 until then, which held
	 * every relief for Sindria while its last district fell). With no
	 * swarm overhead, one guard of guardFleetFP to deny the next landing.
	 */
	public static float reliefGoal(MarketAPI market) {
		float threat = ThreatGroundFronts.pointsNear(market, Factions.THREAT, true);
		// no swarm over the world, no relief: the ground war is the marines' (the user, Sindria 2026-10-24:
		// "sindria keeps sending relief fleets to its worlds under siege, despite the worlds having no threat
		// over them. it only needs to send them when the threat are occupying space"; before, one guard of
		// guardFleetFP went anyway)
		if (threat <= 0f) return 0f;
		return threat * Math.max(0.5f, ThreatIncConfig.reliefOrbitMargin());
	}

	/** Points the colony's relief is still short: 0 unless a Threat army stands on it AND the swarm holds its orbit (reliefGoal). */
	public static float reliefShort(MarketAPI market) {
		if (market == null) return 0f;
		if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()))) return 0f;
		return Math.max(0f, reliefGoal(market) - guardPointsFor(market.getId()));
	}

	/** Points a relief must outweigh over the colony: the swarm's over it x reliefOrbitMargin (the goal's own margin, 2026-10-22), or one guard of guardFleetFP with none. */
	public static float reliefNeed(MarketAPI market) {
		float threat = ThreatGroundFronts.pointsNear(market, Factions.THREAT, true);
		return threat > 0f ? threat * Math.max(0.5f, ThreatIncConfig.reliefOrbitMargin()) : ThreatIncConfig.guardFleetFP();
	}

	/**
	 * Whether a relief from the base would be enough: what the base can field
	 * (sortieFirstPayableFP - its stock and the faction's free hulls) with the
	 * guards already bound there outweighs the army over the colony. The
	 * humans' mirror of the swarm's rally rule (systemDefenceOnlyIfEnough; the
	 * user, 2026-10-05: "if the fleets that can be gathered aren't strong enough
	 * to defend then they shouldn't bother"), under the same switch. hw65
	 * (2026-10-08): once the yards were gone each poll sent whatever the base
	 * could pay - 4-10 FP - at armies of 1-6k, 1,800-3,400 sends and 34-61k FP
	 * a game fed in piecemeal and destroyed, every hull the yards made.
	 */
	public static boolean reliefEnough(FactionAPI faction, MarketAPI market, MarketAPI base) {
		if (!ThreatIncConfig.systemDefenceOnlyIfEnough()) return true;
		if (faction == null || market == null || base == null) return false;
		if (playerAtTheWall(market)) return true; // the player's fleets over the world are the commitment
		float can = sortieFirstPayableFP(base, market.getLocationInHyperspace());
		return can + guardPointsFor(market.getId()) >= reliefNeed(market);
	}

	/**
	 * Whether one of the faction's own invaded worlds is still owed relief a
	 * base of the faction can provision, at any distance. A siege waits for it:
	 * relief draws on the depots first (user, 2026-09-27).
	 */
	public static boolean reliefOwed(FactionAPI faction) {
		if (faction == null || faction.isPlayerFaction()) return false;
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (market.getPrimaryEntity() == null || reliefShort(market) <= 0f) continue;
			// a relief that would be held as not enough (reliefEnough) is not owed: a siege
			// does not wait on hulls the faction will not send
			MarketAPI base = pickReliefBase(faction, market);
			if (base != null && (reliefEnough(faction, market, base) || ThreatCoalition.jointEnough(faction, market, base))) return true;
		}
		return false;
	}

	/**
	 * RELIEF (docs/ground-war.md "Relief"): a Threat army on one of the
	 * faction's own worlds gets a task force over it sized to break the
	 * swarm's hold - reliefGoal, less the guards already bound there - from
	 * the nearest base, as far as its depot can provision it. Checked on the
	 * ground-front poll. NPC navies only: the player's own faction takes the
	 * player's orders from the board. While the swarm holds the orbit the
	 * owner also posts a swarm bounty on the system; allies top up a relief
	 * the owner could not pay for (ThreatCoalition.allyAid). Marines follow
	 * by convoy (ThreatConvoys.planRelief).
	 */
	public static void planRelief() {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.ordersEnabled()) return;
		retaskReliefs();
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			// HOME UNDER ATTACK (the user, 2026-10-20): the sieges come home - the navy defends the home worlds first
			if (ThreatGroundFronts.homeUnderAttack(factionId)) {
				IncursionManager.recallSiegesOf(factionId, "The fleets come home to defend the invaded worlds");
			}
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				if (market.getPrimaryEntity() == null) continue;
				if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()))) {
					continue;
				}
				if (Factions.THREAT.equals(ThreatGroundFronts.spaceHolder(market))) {
					ThreatSwarmBountyIntel.postRelief(market);
				}
				float owed = reliefShort(market);
				if (owed <= 0f) continue;
				MarketAPI base = pickReliefBase(faction, market);
				if (base == null) {
					// HOME UNDER ATTACK (2026-10-20): no base of its own can provision a relief - its partners' can
					// (ThreatCoalition.jointRelief with no owner base); hw242 refused 35-108 reliefs a game here
					// COALITION FOR THE BASE-LESS (the user, 2026-10-20 22:30, option A - Sindria under siege for a year and
					// no one came: the Diktat's only base is the invaded world, drained): the partners are asked for every
					// invaded world the owner cannot relieve itself, not only under HOME UNDER ATTACK (which is off)
					float joint = ThreatGroundFronts.homeUnderAttack(factionId) || ThreatIncConfig.reliefCoalitionNoBase()
							? ThreatCoalition.jointRelief(faction, market, null, owed) : 0f;
					if (joint > 0f) {
						ThreatIncConfig.log("Relief: " + factionId + " sends " + (int) joint + " FP of its partners' to invaded "
								+ market.getName() + " (owed " + (int) owed + "; no base of its own can provision it)");
						continue;
					}
					ThreatIncConfig.logQuiet("relief-none:" + market.getId(), "Relief: " + factionId
							+ " cannot relieve invaded " + market.getName() + " - no base can provision it (owed "
							+ (int) owed + ")");
					continue;
				}				// (2026-10-08, coalition relief) the owner alone short of the army: the owner and its partners together
				float sent = reliefEnough(faction, market, base) ? sendRelief(faction, market, base, owed) : 0f;
				// the base's own build short of the owed points stood down (one body or none): the coalition's turn
				if (sent <= 0f) sent = ThreatCoalition.jointRelief(faction, market, base, owed);
				if (sent > 0f) {
					ThreatIncConfig.log("Relief: " + factionId + " sends " + (int) sent + " FP from "
							+ base.getName() + " to invaded " + market.getName() + " (owed "
							+ (int) owed + ")");
				}
			}
		}
	}

	/**
	 * RELIEF GOES WHERE THE SWARM IS (the user, 2026-10-23: "theres a hegemony relief force sitting right over
	 * volturn where there are no threat fleets, while sindria is being burned to the ground by the threat. wtf is
	 * the heg relief force doing?" - bombarding Volturn's army at 7 troops a day on two weeks of fuel, bound to
	 * the world it was sent to until the army there was gone). A relief with no term on station over a world
	 * with no Threat fleet over it moves to the nearest other invaded world of the same owner where the swarm
	 * holds the orbit and its points with the guards already bound there outweigh the swarm (reliefNeed), or
	 * the player is at the wall (playerAtTheWall). In-system the move is free; across systems it must carry the
	 * voyage's fuel above its passage home from the new world. The world it leaves is owed a guard again on the
	 * same poll (planRelief). Knob reliefRetask.
	 */
	protected static void retaskReliefs() {
		if (!ThreatIncConfig.reliefRetask()) return;
		for (Order o : new ArrayList<Order>(all())) {
			if (!o.relief || !o.indefinite() || !KIND_GUARD.equals(o.kind)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			MarketAPI here = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (here == null || here.getPrimaryEntity() == null || !nearPlanet(o.fleet, here.getPrimaryEntity())) continue;
			if (ThreatGroundFronts.pointsNear(here, Factions.THREAT, true) > 0f) continue;
			String owner = o.recipientFactionId != null ? o.recipientFactionId : o.factionId;
			float fp = o.fleet.getFleetPoints();
			MarketAPI best = null;
			float bestDist = Float.MAX_VALUE;
			for (MarketAPI there : ThreatReserves.marketsOf(owner)) {
				if (there == here || there.getPrimaryEntity() == null || there.getStarSystem() == null) continue;
				if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(there.getId()))) continue;
				if (ThreatGroundFronts.pointsNear(there, Factions.THREAT, true) <= 0f) continue;
				if (fp + guardPointsFor(there.getId()) < reliefNeed(there) && !playerAtTheWall(there)) continue;
				float d = Misc.getDistanceLY(here.getLocationInHyperspace(), there.getLocationInHyperspace());
				if (d >= bestDist || !reliefCanReach(o.fleet, d, there)) continue;
				best = there;
				bestDist = d;
			}
			if (best == null) continue;
			String from = here.getName();
			o.fleet.clearAssignments();
			o.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, best.getPrimaryEntity(), 1000f, "relieving " + best.getName());
			o.fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, best.getPrimaryEntity(), NO_TERM_DAYS, "relieving " + best.getName());
			o.targetId = best.getId();
			o.targetName = best.getName();
			o.arrived = false;
			o.quietSince = 0L;
			o.recipientFactionId = o.factionId.equals(best.getFactionId()) ? null : best.getFactionId();
			ThreatIncConfig.log("Relief moves: " + o.factionId + "'s " + (int) fp + " FP leaves quiet " + from + " for invaded "
					+ best.getName() + ", where the swarm holds the orbit (" + (int) ThreatGroundFronts.pointsNear(best, Factions.THREAT, true)
					+ " FP against " + (int) guardPointsFor(best.getId()) + " bound" + (playerAtTheWall(best) ? ", the player at the wall" : "") + ")");
			ThreatNotice.titled("Relief Moves").icon(o.fleet.getFaction()).fleetOp(o.fleet.getFaction(), here, best)
					.line("%s moves %s FP from %s to %s", ThreatNotice.faction(o.fleet.getFaction()), Misc.getWithDGS((int) fp),
							ThreatNotice.market(here), ThreatNotice.market(best)).send();
		}
	}

	/**
	 * HOME WHEN THE SYSTEM IS QUIET (the user, 2026-10-24: relief that had cleared a system of Threat
	 * fleets "seem to stay there indefinitely"): a relief with no term on station whose world's system
	 * has held no Threat fleet for reliefQuietDays running goes home (poll); a Threat fleet entering the
	 * system restarts the count, and so does a move (retaskReliefs runs first on each planRelief, so a
	 * relief that can help a neighbour moves before its count runs out). Knob 0 keeps the old hold,
	 * until the army on the ground is gone (ThreatGroundFronts.reliefDone).
	 */
	protected static boolean reliefQuiet(Order o) {
		float days = ThreatIncConfig.reliefQuietDays();
		if (days <= 0f || o.fleet == null) return false;
		MarketAPI here = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		if (here == null || here.getPrimaryEntity() == null) return false;
		if (!o.arrived || ThreatGroundFronts.threatInSystem(here)) {
			o.quietSince = 0L;
			return false;
		}
		long now = Global.getSector().getClock().getTimestamp();
		if (o.quietSince == 0L) {
			o.quietSince = now;
			return false;
		}
		return Global.getSector().getClock().getElapsedDaysSince(o.quietSince) >= days;
	}

	/** The log line and notice for a relief going home on a quiet system (#reliefQuiet). */
	protected static void noteReliefQuiet(Order o) {
		MarketAPI here = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		if (here == null || o.fleet == null) return;
		int days = (int) ThreatIncConfig.reliefQuietDays();
		String system = here.getStarSystem() != null ? here.getStarSystem().getNameWithLowercaseType() : here.getName();
		ThreatIncConfig.log("Relief home: " + o.factionId + "'s " + (int) o.fleet.getFleetPoints() + " FP leaves " + here.getName()
				+ " - no Threat fleet in " + system + " for " + days + " d");
		ThreatNotice.titled("Relief Returns").icon(o.fleet.getFaction()).fleetOp(o.fleet.getFaction(), here)
				.line("%s's %s FP leaves %s, no Threat fleet in %s for %s days", ThreatNotice.faction(o.fleet.getFaction()),
						Misc.getWithDGS((int) o.fleet.getFleetPoints()), ThreatNotice.market(here), system, String.valueOf(days)).send();
	}

	/** Whether the relief carries the fuel for a voyage of ly light-years above its passage home from the new world; an in-system move is free. */
	protected static boolean reliefCanReach(CampaignFleetAPI fleet, float ly, MarketAPI there) {
		if (ly <= 0.01f) return true;
		float voyage = fleet.getFleetPoints() / IncursionManager.FP_PER_RESPONSE_DIFFICULTY * ly * ThreatIncConfig.expeditionFuelPerPointLY();
		return fleet.getCargo().getFuel() >= voyage + ThreatGroundFronts.reliefFuelHome(fleet, there);
	}

	/**
	 * The base a relief sails from: the nearest whose depot can provision one
	 * relief fleet at its distance (canProvisionRelief prices the voyage by the
	 * light-year). The nearest alone is often the invaded world itself, drained
	 * by its front, and relief never sailed while a stocked base sat 3 ly away
	 * (Nachiketa, 2026-09-29). No radius (2026-10-03, the user): it was a
	 * siege expedition's reach (expeditionRangeLY), so a base that could pay
	 * for relief but not a siege counted as out of reach - 11 of the 30 worlds
	 * taken in hw4q got no relief at all.
	 */
	public static MarketAPI pickReliefBase(FactionAPI faction, MarketAPI target) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		Vector2f hyperLoc = target.getLocationInHyperspace();
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (market.getStarSystem() == null || !IncursionManager.isBase(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), hyperLoc);
			if (d >= bestDist) continue;
			if (!canProvisionRelief(market, target)) continue;
			bestDist = d;
			best = market;
		}
		return best;
	}

	/** Fuel and supplies one relief fleet asks of the base: {fuel, supplies}. */
	protected static float[] reliefWants(MarketAPI base, MarketAPI target) {
		float points = ThreatIncConfig.reliefFleetFP() / IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		float dist = base.getStarSystem() == null ? 0f
				: Misc.getDistanceLY(base.getStarSystem().getLocation(), target.getLocationInHyperspace());
		return new float[] {points * dist * ThreatIncConfig.expeditionFuelPerPointLY(),
				points * (ThreatIncConfig.expeditionSuppliesPerPoint()
						+ ThreatReach.sortieHoldPerPoint(base.getFactionId(), dist))};
	}

	/** Whether the base's depot can provision one more relief fleet: expeditionMinProvisionsFraction of its wants, a siege's own rule. */
	protected static boolean canProvisionRelief(MarketAPI base, MarketAPI target) {
		float[] wants = reliefWants(base, target);
		float min = ThreatIncConfig.expeditionMinProvisionsFraction();
		return ThreatReserves.available(base, Commodities.FUEL) >= wants[0] * min
				&& ThreatReserves.available(base, Commodities.SUPPLIES) >= wants[1] * min;
	}

	/**
	 * Builds relief at the base until its fleets reach {@code owed} points or
	 * the depot cannot provision another (2026-09-29: no siegeMaxFleets cap, 50
	 * fleets of reliefFleetFP held a relief to ~15,000 FP), then
	 * folds them into as few fleets as fleetMaxFP takes - vanilla's AI never
	 * keeps separate fleets together, and a guard a tenth the swarm's size
	 * only ever kept its distance (Coatl, 2026-09-27). Each fleet left over
	 * guards beside the lead. Returns the points sent.
	 */
	public static float sendRelief(FactionAPI faction, MarketAPI target, MarketAPI base, float owed) {
		return sendRelief(faction, target, base, owed, false);
	}

	/** As above; {@code coalitionEnough} skips the enough gate: the sender is one of a joint relief that is enough together (ThreatCoalition.jointRelief). */
	public static float sendRelief(FactionAPI faction, MarketAPI target, MarketAPI base, float owed, boolean coalitionEnough) {
		if (faction == null || target == null || base == null || owed <= 0f) return 0f;
		// (2026-10-08, hw65) only if enough: the base keeps its hulls and provisions
		// rather than feed them in piecemeal (reliefEnough)
		if (!coalitionEnough && !reliefEnough(faction, target, base)) {
			ThreatIncConfig.logQuiet("relief-held:" + faction.getId() + ":" + target.getId(), "Relief held: "
					+ faction.getId() + " can field " + (int) sortieFirstPayableFP(base, target.getLocationInHyperspace())
					+ " FP from " + base.getName() + " against " + (int) reliefNeed(target) + " over " + target.getName()
					+ " (" + (int) guardPointsFor(target.getId()) + " bound) - not enough, holds");
			return 0f;
		}
		List<CampaignFleetAPI> sailing = buildRelief(faction, target, base, owed);
		float sent = reliefPoints(sailing);
		// ONE BODY OR NONE (the user, 2026-10-20, the fleets as the marines since 08:56): a relief that comes out
		// of the build short of the owed points stays home - the gate above priced a paper figure. hw244a: the
		// coalition's 'fieldable' promised 704k FP over the game and built 131k, 161 of 193 joint reliefs under
		// half of it, and the fleets that did sail bled in at 29-356 FP against 1,300-4,600 FP swarms.
		if (ThreatIncConfig.reliefOneBody() && !coalitionEnough && sent > 0f && sent + 0.5f < owed && !playerAtTheWall(target)) {
			standDownUnsailed(sailing, base);
			noteReliefShort(faction, base, target, owed - sent);
			ThreatIncConfig.log("Relief held at " + base.getName() + ": " + faction.getId() + " built " + (int) sent
					+ " FP of the " + (int) owed + " owed over " + target.getName() + " - not enough, the hulls stay home");
			return 0f;
		}
		return sailRelief(faction, target, base, sailing);
	}

	/**
	 * The sendRelief build: fleets at the base until {@code owed} points or the depot
	 * cannot provision another, folded into as few as fleetMaxFP allows. Built
	 * and provisioned, not yet ordered anywhere: {@link #sailRelief} or
	 * {@link #standDownUnsailed} takes them.
	 */
	protected static List<CampaignFleetAPI> buildRelief(FactionAPI faction, MarketAPI target, MarketAPI base, float owed) {
		List<CampaignFleetAPI> sailing = new ArrayList<CampaignFleetAPI>();
		if (faction == null || target == null || base == null || owed <= 0f) return sailing;
		List<CampaignFleetAPI> built = new ArrayList<CampaignFleetAPI>();
		float sent = 0f;
		// ends: every fleet adds at least a point toward a finite owed and draws
		// on the depot, or the loop breaks
		while (sent < owed && canProvisionRelief(base, target)) {
			// (2026-09-29: closed economy) each fleet is what the depot pays for in
			// full: a depot at expeditionMinProvisionsFraction of one fleet's
			// provisions sent the whole reliefFleetFP half paid
			float ask = Math.min(ThreatIncConfig.reliefFleetFP(),
					sortieFirstPayableFP(base, target.getLocationInHyperspace()));
			if (ask < 1f) break;
			CampaignFleetAPI fleet = buildTaskForce(base, faction, ask, target.getLocationInHyperspace());
			if (fleet == null) break;
			if (fleet.getFleetPoints() < 1) { // built nothing: the depot keeps its provisions
				ThreatSoftening.refundShort(fleet, base, 1f);
				fleet.despawn();
				break;
			}
			// vanilla prunes a fleet to maxShipsInAIFleet: pay only for what was
			// built (2026-09-29: closed economy, as buildSortie)
			float share = Math.min(1f, ThreatSoftening.combatFP(fleet) / Math.max(1f, ask));
			if (share < ThreatSoftening.BUILT_SHORT) ThreatSoftening.refundShort(fleet, base, 1f - share);
			built.add(fleet);
			sent += fleet.getFleetPoints();
		}
		if (built.isEmpty()) return sailing;
		return foldUnderCap(built);
	}

	/**
	 * Built fleets folded into as few as the fleet cap takes (ThreatIncConfig.fleetMaxFP):
	 * each into the first that has room for its points ({@link #absorb}), else it sails
	 * beside them. Lead first.
	 */
	public static List<CampaignFleetAPI> foldUnderCap(List<CampaignFleetAPI> built) {
		List<CampaignFleetAPI> sailing = new ArrayList<CampaignFleetAPI>();
		if (built == null) return sailing;
		for (CampaignFleetAPI f : built) {
			if (f == null) continue;
			CampaignFleetAPI into = null;
			for (CampaignFleetAPI lead : sailing) {
				if (ThreatIncConfig.fitsFleet(lead.getFleetPoints(), f.getFleetPoints())) {
					into = lead;
					break;
				}
			}
			if (into == null) {
				sailing.add(f);
			} else {
				absorb(into, f);
			}
		}
		return sailing;
	}

	/** Fleet points of built relief fleets ({@link #buildRelief}). */
	protected static float reliefPoints(List<CampaignFleetAPI> fleets) {
		float sum = 0f;
		if (fleets != null) for (CampaignFleetAPI f : fleets) if (f != null) sum += f.getFleetPoints();
		return sum;
	}

	/**
	 * Built relief fleets that do not sail (one body or none): the voyage's fuel and
	 * supplies, the hold and the hulls go back to the base and the fleets are gone.
	 */
	protected static void standDownUnsailed(List<CampaignFleetAPI> fleets, MarketAPI base) {
		if (fleets == null || base == null) return;
		for (CampaignFleetAPI f : fleets) {
			if (f == null) continue;
			ThreatSoftening.refundShort(f, base, 1f);
			ThreatReturns.settle(f, base, false);
			if (!ThreatReturns.kept(f)) f.despawn();
		}
	}

	/**
	 * WAR WANTS (the user, 2026-10-20): the fuel and supplies a relief of {@code shortFP}
	 * more points would have drawn at the base, less what the base holds above its floor -
	 * noted as the faction's want (ThreatFactionStock.noteShort) so the core build answers
	 * it with a producer, a conversion or scrapped hulls rather than reading the draw that
	 * never happened.
	 */
	protected static void noteReliefShort(FactionAPI faction, MarketAPI base, MarketAPI target, float shortFP) {
		if (faction == null || base == null || target == null || shortFP <= 0f) return;
		float[] wants = sortieWants(base, shortFP, target.getLocationInHyperspace());
		// the hulls too (hw246: the Hegemony owed 8,112 over Chicomoztoc with 1,373 free): a want above the free
		// pool is a hull shortage the yards answer (ThreatFactionStock.hullsShort reads hullShortFP)
		if (ThreatHulls.enabled() && ThreatHulls.freeFP(faction.getId()) < shortFP) {
			ThreatFactionStock.noteHullShort(faction.getId(), shortFP - ThreatHulls.freeFP(faction.getId()));
		}
		ThreatFactionStock.noteShort(faction.getId(), Commodities.FUEL,
				wants[0] - ThreatReserves.available(base, Commodities.FUEL));
		ThreatFactionStock.noteShort(faction.getId(), Commodities.SUPPLIES,
				wants[1] - ThreatReserves.available(base, Commodities.SUPPLIES));
	}

	/**
	 * Sails built relief fleets ({@link #buildRelief}) from the base to the invaded
	 * target: the orders and the ledger. Returns the points sent.
	 */
	protected static float sailRelief(FactionAPI faction, MarketAPI target, MarketAPI base, List<CampaignFleetAPI> sailing) {
		if (faction == null || target == null || base == null || sailing == null || sailing.isEmpty()) return 0f;
		// (2026-10-03, the user: relief holds the orbit until the invaders are
		// defeated) with no term it stays until the poll stands it down
		// (ThreatGroundFronts.reliefDone) and goes home on the tracked leg
		boolean stays = ThreatIncConfig.reliefStays();
		float days = stays ? 0f : ThreatIncConfig.guardDays();
		for (CampaignFleetAPI fleet : sailing) {
			fleet.setName("Relief Force");
			if (stays) {
				fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, target.getPrimaryEntity(), 1000f,
						"relieving " + target.getName());
				fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.getPrimaryEntity(), NO_TERM_DAYS,
						"relieving " + target.getName());
			} else {
				fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.getPrimaryEntity(), days,
						"relieving " + target.getName());
				fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
						1000f, "returning to " + base.getName());
			}
			Order o = record(fleet, faction, KIND_GUARD, base, target.getId(), target.getName(), days);
			o.relief = true;
			if (!faction.getId().equals(target.getFactionId())) o.recipientFactionId = target.getFactionId();
		}
		return reliefPoints(sailing);
	}

	/**
	 * Folds {@code from} into {@code to}: its ships, and what it was
	 * provisioned with and launched at, so the one fleet is refunded and
	 * judged as the two were (ThreatReturns). Despawns {@code from}; the
	 * caller settles any order it carried.
	 */
	public static void absorb(CampaignFleetAPI to, CampaignFleetAPI from) {
		for (com.fs.starfarer.api.fleet.FleetMemberAPI m : from.getFleetData().getMembersListCopy()) {
			from.getFleetData().removeFleetMember(m);
			m.setFlagship(false);
			to.getFleetData().addFleetMember(m);
		}
		com.fs.starfarer.api.campaign.rules.MemoryAPI a = to.getMemoryWithoutUpdate(), b = from.getMemoryWithoutUpdate();
		// the holds too (2026-10-11, hw157: a merged relief force lost the absorbed fleets' holds with their
		// fleet and stood down "supplies low, 0 aboard" over the world it relieved)
		for (String k : new String[] { ThreatReturns.MEM_FUEL, ThreatReturns.MEM_SUPPLIES, ThreatReturns.MEM_FP0,
				ThreatReturns.MEM_HOLD, ThreatReturns.MEM_HOLD_FULL, ThreatReturns.MEM_FUEL_ABOARD,
				ThreatReturns.MEM_FP_ORDER }) {
			if (!a.contains(k) && !b.contains(k)) continue;
			a.set(k, (a.contains(k) ? a.getFloat(k) : 0f) + (b.contains(k) ? b.getFloat(k) : 0f));
		}
		// the cargo too (2026-10-29, the fleet cap - every fold is whole): the crew that mans the ships, the
		// marines and arms aboard, the supplies and fuel - until now a merged fleet sailed on the lead's alone
		to.getCargo().addAll(from.getCargo());
		from.getCargo().clear();
		to.getFleetData().sort();
		to.getFleetData().setSyncNeeded();
		to.getFleetData().syncIfNeeded();
		to.forceSync();
		from.despawn();
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
	 * distance, supplies for the hulls). Every fleet is built at exactly the
	 * points asked for, which are the points it is paid for: vanilla's own
	 * fleet-size scaling (the market's COMBAT_FLEET_SIZE_MULT) is switched off.
	 * (2026-09-29: closed economy - an NPC fleet kept that scaling, so a 1.5
	 * multiplier sailed half again what its depot paid for.)
	 */
	public static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper) {
		return buildTaskForce(base, faction, fp, destinationHyper, true);
	}

	/** As above; {@code provision} false builds the hulls without drawing on the base (a restationed detachment). */
	public static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper, boolean provision) {
		return buildTaskForce(base, faction, fp, destinationHyper, provision, false);
	}

	/**
	 * As above; {@code spareOnly} draws only the base's spendable stock
	 * (ThreatReserves.spendable) - a hunting force's fleets, which must not
	 * spend what the base banked for its own siege.
	 */
	public static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper, boolean provision, boolean spareOnly) {
		return buildTaskForce(base, faction, fp, destinationHyper, provision, spareOnly, null);
	}

	/**
	 * As above; with {@code hunted}, a hunting force's fleet draws what a hunt
	 * in that system may take (ThreatSoftening.drawHunt): a staging base whose
	 * own siege there waits on the hunt spends that siege's bank too, the same
	 * stock the force was sized on (ThreatSoftening.payableFP).
	 */
	protected static CampaignFleetAPI buildTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper, boolean provision, boolean spareOnly, StarSystemAPI hunted) {
		StarSystemAPI system = base.getStarSystem();
		SectorEntityToken entity = base.getPrimaryEntity();
		if (system == null || entity == null || fp <= 0f) return null;
		FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(),
				faction.getId(), null, FleetTypes.TASK_FORCE,
				fp, fp * 0.1f, fp * 0.1f, 0f, 0f, 0f, 0f);
		// the points paid for, player and NPC alike (2026-09-29: closed economy)
		params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;
		ThreatFleetComposer.beltGuard(fleet);
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
		if (!provisionTaskForce(base, faction, fp, destinationHyper, fleet, spareOnly, hunted, null)) {
			Misc.fadeAndExpire(fleet);
			return null;
		}
		return fleet;
	}

	/**
	 * Provisions a task force of {@code fp} combat points at the base for the trip to
	 * {@code destinationHyper}: the voyage's fuel, the hull deposit and the hold, drawn from the
	 * base (the {@link #buildTaskForce} draw). The fleet is trimmed to the hold the base paid, its
	 * {@code keep} members never (a task force on station re-tasked by {@link #retask}: real
	 * ships, not hulls the yards just built). False when the base cannot provision it (the staged
	 * launch): what it drew is back in the reserve and the fleet is as it was, for the caller to
	 * expire or stand down.
	 */
	protected static boolean provisionTaskForce(MarketAPI base, FactionAPI faction, float fp,
			Vector2f destinationHyper, CampaignFleetAPI fleet, boolean spareOnly, StarSystemAPI hunted,
			java.util.Set<com.fs.starfarer.api.fleet.FleetMemberAPI> keep) {
		float ly = sortieLY(base, destinationHyper);
		float[] wants = threatinc.rules.ReachRules.voyageCost(fp, ly, ThreatIncConfig.expeditionFuelPerPointLY(),
				ThreatIncConfig.expeditionSuppliesPerPoint());
		// the HOLD (docs/fleet-provisions.md): the ships actually built, their
		// maintenance for the voyage there and back and sortieStayDays there
		float holdWant = ThreatIncConfig.fleetUpkeep() ? ThreatFrontlines.maintenancePerMonth(fleet)
				* ThreatReach.daysAway(ly, true, ThreatReach.sortieStay(ly)) / 30f : 0f;
		float fuel, supplies, hold;
		if (hunted != null) {
			fuel = ThreatSoftening.drawHunt(base, hunted, Commodities.FUEL, wants[0]);
			supplies = ThreatSoftening.drawHunt(base, hunted, Commodities.SUPPLIES, wants[1]);
			hold = ThreatSoftening.drawHunt(base, hunted, Commodities.SUPPLIES, holdWant);
		} else if (spareOnly) {
			fuel = ThreatReserves.drawSpendable(base, Commodities.FUEL, wants[0]);
			supplies = ThreatReserves.drawSpendable(base, Commodities.SUPPLIES, wants[1]);
			hold = ThreatReserves.drawSpendable(base, Commodities.SUPPLIES, holdWant);
		} else {
			fuel = ThreatReserves.drawAbove(base, Commodities.FUEL, wants[0]);
			supplies = ThreatReserves.drawAbove(base, Commodities.SUPPLIES, wants[1]);
			hold = ThreatReserves.drawAbove(base, Commodities.SUPPLIES, holdWant);
		}
		// the gate priced the hold per point at the faction's measured rate (ThreatReach.sortieHold); the
		// built hulls want their own maintenance, 3-75% more in hw157 - fuel and deposit paid, the fleet
		// leaves its smallest warships home until the hold the base paid feeds the rest (TRIM_FLOOR).
		// The player's fleets too since 2026-10-11 evening (the user: the quoted figure is the NPC
		// navies' and the draw must match it)
		if (ThreatIncConfig.stagedLaunch() && hold < holdWant - 0.5f
				&& fuel >= wants[0] - 0.5f && supplies >= wants[1] - 0.5f) {
			float days = ThreatReach.daysAway(ly, true, ThreatReach.sortieStay(ly));
			float before = fleet.getFleetPoints(), floor = before * TRIM_FLOOR;
			java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
			java.util.Collections.sort(members, new java.util.Comparator<com.fs.starfarer.api.fleet.FleetMemberAPI>() {
				public int compare(com.fs.starfarer.api.fleet.FleetMemberAPI a, com.fs.starfarer.api.fleet.FleetMemberAPI b) {
					return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
				}
			});
			float need = holdWant;
			for (com.fs.starfarer.api.fleet.FleetMemberAPI m : members) {
				if (need <= hold + 0.5f) break;
				if (m.isCivilian() || fleet.getFleetData().getNumMembers() <= 1) continue;
				if (keep != null && keep.contains(m)) continue;
				if (fleet.getFleetPoints() - m.getFleetPointCost() < floor) break;
				fleet.getFleetData().removeFleetMember(m);
				need = ThreatFrontlines.maintenancePerMonth(fleet) * days / 30f;
			}
			if (need <= hold + 0.5f && need < holdWant) {
				float after = fleet.getFleetPoints(), frac = before > 0f ? 1f - after / before : 0f;
				// the deposit of the hulls staying home, and the hold the rest do not need, go back
				float fuelBack = fuel * frac, suppliesBack = supplies * frac, holdBack = Math.max(0f, hold - need);
				if (fuelBack > 0f) ThreatReserves.deposit(base.getId(), Commodities.FUEL, fuelBack);
				if (suppliesBack + holdBack > 0f) ThreatReserves.deposit(base.getId(), Commodities.SUPPLIES, suppliesBack + holdBack);
				fuel -= fuelBack; supplies -= suppliesBack; hold -= holdBack;
				// the want shrinks with the hulls, or the staged check below holds every trimmed fleet (hw158:
				// 728 trimmed, 728 held "short" of the untrimmed deposit)
				wants[0] *= after / Math.max(1f, before); wants[1] *= after / Math.max(1f, before);
				ThreatIncConfig.log("Order trimmed at " + base.getName() + ": " + (int) before + " -> " + (int) after
						+ " FP for a hold of " + (int) need + " (the hulls wanted " + (int) holdWant + ", the base paid " + (int) (hold + holdBack) + ")");
				holdWant = need;
			}
		}
		// STAGED LAUNCHES (the user, 2026-10-11, after hw156 / gs1: forward bases launched hunting fleets
		// with an empty hold - "hold 0 of 1545" - that stood down at once, 105 of 207 stand-downs in gs1a):
		// a fleet sails with its full provisions from this base or stays home; what it drew goes back.
		// The player's too since 2026-10-11 evening (their orders are gated on the same figure at the
		// board, so this holds only a fleet whose built hulls outgrew the paid hold past TRIM_FLOOR)
		if (ThreatIncConfig.stagedLaunch()
				&& (fuel < wants[0] - 0.5f || supplies < wants[1] - 0.5f || hold < holdWant - 0.5f)) {
			if (fuel > 0f) ThreatReserves.deposit(base.getId(), Commodities.FUEL, fuel);
			if (supplies + hold > 0f) ThreatReserves.deposit(base.getId(), Commodities.SUPPLIES, supplies + hold);
			// WAR WANTS (2026-10-20): what the order asked and the base could not pay
			ThreatFactionStock.noteShort(faction.getId(), Commodities.FUEL, wants[0] - fuel);
			ThreatFactionStock.noteShort(faction.getId(), Commodities.SUPPLIES, wants[1] + holdWant - supplies - hold);
			ThreatIncConfig.log("Order held at " + base.getName() + ": "
					+ (int) fuel + " of " + (int) wants[0] + " fuel, " + (int) supplies + " of " + (int) wants[1]
					+ " supplies, hold " + (int) hold + " of " + (int) holdWant + " - stays home until the base is staged");
			return false;
		}
		// remembered on the fleet so the return leg can refund what survives
		ThreatReturns.provision(fleet, base.getId(), fuel, supplies);
		ThreatReturns.setHold(fleet, hold);
		ThreatIncConfig.log("Order draw at " + base.getName() + ": " + (int) fuel + " fuel, "
				+ (int) supplies + " supplies, hold " + (int) hold + " of " + (int) holdWant + " supplies ("
				+ (int) ThreatReturns.holdDays(fleet) + " days; " + (int) fleet.getFleetPoints() + " FP of " + (int) fp + " asked, "
				+ String.format("%.2f", ThreatFrontlines.maintenancePerMonth(fleet) / Math.max(1f, fleet.getFleetPoints()))
				+ " supplies/FP/mo vs " + String.format("%.2f", ThreatReach.suppliesPerFP(faction.getId())) + " measured)");
		return true;
	}

	/**
	 * A task force on station over the base sails as it is on a sortie from it (the user,
	 * 2026-10-11: the fleets staged over Haven vanished and respawned out of the planet when
	 * ordered on; until then {@link ThreatAidCapacity#foldStaged} expired the staged fleet and
	 * the yards built its points again). Its guard ends and what it carries settles at the base
	 * ({@link #fold} keeping the fleet), its ledger entries stay charged to their senders and are
	 * marked to go back on station here when the sortie is home (the fold's bookkeeping); when it
	 * is short of {@code ask} and the base's own free hulls ({@code ownLeft[0]}, whole-fleet
	 * points, reduced by what is used) cover at least a task force of the gap, those are built and
	 * join it; then it is provisioned for the trip as a built fleet would be
	 * ({@link #provisionTaskForce}: the draw is what it needs less what it carried in). Returns the
	 * fleet, its assignments cleared for the caller's, or null when the base cannot provision it -
	 * then nothing has changed: the guard stands as before, restocked from the base.
	 */
	protected static CampaignFleetAPI retask(Order pick, MarketAPI base, FactionAPI faction, float ask,
			Vector2f destinationHyper, float[] ownLeft, String label) {
		if (pick == null || base == null || faction == null) return null;
		CampaignFleetAPI fleet = pick.fleet;
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return null;
		List<ThreatAidCapacity.Commitment> held = ThreatAidCapacity.heldAllBy(fleet);
		if (held.isEmpty()) return null;
		// the undo, should the base not provision it: the entries as they were, the guard's home
		String homeBefore = ThreatReturns.homeOf(fleet);
		String[] hostBefore = new String[held.size()];
		float[] foldedBefore = new float[held.size()];
		String[] labelBefore = new String[held.size()];
		long[] lostBefore = new long[held.size()];
		float live = ThreatAidCapacity.liveFP(fleet), charged = 0f;
		for (ThreatAidCapacity.Commitment c : held) charged += c.fp;
		for (int i = 0; i < held.size(); i++) {
			ThreatAidCapacity.Commitment c = held.get(i);
			hostBefore[i] = c.hostMarketId;
			foldedBefore[i] = c.foldedFP;
			labelBefore[i] = c.label;
			lostBefore[i] = c.lostTimestamp;
			// detached while it settles: release() would put it straight back on station
			c.fleet = null;
			c.hostMarketId = base.getId();
			c.foldedFP = charged > 0f ? live * c.fp / charged : live / held.size();
			c.lostTimestamp = 0L;
			c.label = label;
			ThreatIncConfig.log("Capacity: task force from " + ThreatAidCapacity.name(c.marketId) + " staged at "
					+ base.getName() + " (" + (int) c.foldedFP + " FP live, " + (int) c.fp + " charged to "
					+ ThreatAidCapacity.name(c.marketId) + ") sails as " + label);
		}
		fold(pick, base, true);
		for (ThreatAidCapacity.Commitment c : held) c.fleet = fleet;
		// the real ships, never trimmed for the hold
		java.util.Set<com.fs.starfarer.api.fleet.FleetMemberAPI> keep =
				new java.util.HashSet<com.fs.starfarer.api.fleet.FleetMemberAPI>(fleet.getFleetData().getMembersListCopy());
		// the base's own free hulls top it up to the ask
		ThreatAidCapacity.Commitment topUp = null;
		float gap = ask - ThreatSoftening.combatFP(fleet);
		float ownCombat = ownLeft != null ? ownLeft[0] / ThreatAidCapacity.TASK_FORCE_HULL_MULT : 0f;
		float freshAsk = Math.min(gap, ownCombat);
		if (freshAsk >= ThreatIncConfig.aidGuardMinFP()) {
			CampaignFleetAPI fresh = buildTaskForce(base, faction, freshAsk, destinationHyper, false);
			if (fresh != null) {
				float points = builtPoints(fresh, freshAsk);
				absorb(fleet, fresh);
				topUp = ThreatAidCapacity.commit(base, points, fleet, null, label, false);
				if (ownLeft != null) ownLeft[0] = Math.max(0f, ownLeft[0] - points);
			}
		}
		if (!provisionTaskForce(base, faction, ThreatSoftening.combatFP(fleet), destinationHyper, fleet, false, null, keep)) {
			// held: the hulls that joined go back to the yards, the guard stands as it did
			for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
				if (!keep.contains(m)) fleet.getFleetData().removeFleetMember(m);
			}
			if (topUp != null) {
				ThreatAidCapacity.all().remove(topUp);
				if (ownLeft != null) ownLeft[0] += topUp.fp;
			}
			for (int i = 0; i < held.size(); i++) {
				ThreatAidCapacity.Commitment c = held.get(i);
				c.hostMarketId = hostBefore[i];
				c.foldedFP = foldedBefore[i];
				c.label = labelBefore[i];
				c.lostTimestamp = lostBefore[i];
			}
			all().add(pick);
			// settled already: what it carried is in the base's reserve; it restocks from there (restation)
			ThreatReturns.provision(fleet, homeBefore != null ? homeBefore : base.getId(), 0f, 0f);
			ThreatUpkeep.restockFleet(fleet, faction.getId());
			fleet.getFleetData().setSyncNeeded();
			fleet.getFleetData().syncIfNeeded();
			return null;
		}
		fleet.clearAssignments();
		// this order's stand-down rule reads from what sails now, not from the guard's launch
		ThreatReturns.rebaseline(fleet);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		return fleet;
	}

	/**
	 * A task force built as {@link #buildTaskForce} builds one, never placed in a system or
	 * provisioned: a probe of what vanilla builds for an ask (ThreatSoftening.fleetCapFor).
	 */
	protected static CampaignFleetAPI dryTaskForce(MarketAPI base, String factionId, float fp) {
		if (base == null || factionId == null || fp <= 0f) return null;
		FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(), factionId, null,
				FleetTypes.TASK_FORCE, fp, fp * 0.1f, fp * 0.1f, 0f, 0f, 0f, 0f);
		params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		return fleet == null || fleet.isEmpty() ? null : fleet;
	}

	/**
	 * {fuel, supplies} a task force of {@code fp} combat points from the base to
	 * {@code destinationHyper} is provisioned with: the voyage's fuel, the hull
	 * deposit, and the HOLD - its ships' supplies for the voyage and sortieStayDays
	 * there (ThreatReach.sortieHold, docs/fleet-provisions.md).
	 */
	protected static float[] sortieWants(MarketAPI base, float fp, Vector2f destinationHyper) {
		float dist = base.getStarSystem() == null || destinationHyper == null ? 0f
				: Misc.getDistanceLY(base.getStarSystem().getLocation(), destinationHyper);
		float[] wants = threatinc.rules.ReachRules.voyageCost(fp, dist, ThreatIncConfig.expeditionFuelPerPointLY(),
				ThreatIncConfig.expeditionSuppliesPerPoint());
		wants[1] += ThreatReach.sortieHold(base.getFactionId(), fp, dist);
		return wants;
	}

	/**
	 * The least of a task force's built fleet points that may sail when the hold the base paid feeds fewer
	 * hulls than were built (buildTaskForce, 2026-10-11): below it the order is held, not trimmed. The gate
	 * sized the force to win; a quarter off is the measured rate's error, not a different force.
	 */
	public static final float TRIM_FLOOR = 0.75f;

	/** Light-years from the base to a destination; 0 with either unknown. */
	protected static float sortieLY(MarketAPI base, Vector2f destinationHyper) {
		return base == null || base.getStarSystem() == null || destinationHyper == null ? 0f
				: Misc.getDistanceLY(base.getStarSystem().getLocation(), destinationHyper);
	}

	/** Combat points the base's spendable stock (ThreatReserves.spendable) can provision a sortie to {@code destinationHyper} for. */
	public static float sortiePayableFP(MarketAPI base, Vector2f destinationHyper) {
		return payableFP(base, destinationHyper, true);
	}

	/**
	 * Combat points the stock a sortie's first fleet draws (ThreatReserves.available:
	 * above the floor, as a sortie always drew) can provision it to {@code destinationHyper} for.
	 */
	public static float sortieFirstPayableFP(MarketAPI base, Vector2f destinationHyper) {
		return payableFP(base, destinationHyper, false);
	}

	/** Combat points the base's spendable ({@code spareOnly}) or available stock provisions a sortie for; unbounded when a sortie costs nothing. */
	protected static float payableFP(MarketAPI base, Vector2f destinationHyper, boolean spareOnly) {
		if (base == null) return 0f;
		float[] per = sortieWants(base, IncursionManager.FP_PER_RESPONSE_DIFFICULTY, destinationHyper);
		float fuel = spareOnly ? ThreatReserves.spendable(base, Commodities.FUEL)
				: ThreatReserves.available(base, Commodities.FUEL);
		float supplies = spareOnly ? ThreatReserves.spendable(base, Commodities.SUPPLIES)
				: ThreatReserves.available(base, Commodities.SUPPLIES);
		float points = threatinc.rules.ReachRules.payablePoints(fuel, supplies, per[0], per[1]);
		float fp = points >= Float.MAX_VALUE ? Float.MAX_VALUE : points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return ThreatHulls.cap(base, fp); // and no more hulls than the faction's pool holds free (ThreatHulls)
	}

	/**
	 * Warship points an NPC sortie from the base can bring against a need: the
	 * guardFleetFP minimum, paid as a sortie always was, or what its spendable
	 * stock provisions, whichever is more - a fleet is built at the points it is
	 * paid for (buildTaskForce). What buildSortie builds at most. Nothing when the
	 * depot cannot pay the minimum (2026-09-29: closed economy - no fleet the
	 * depot cannot pay for).
	 */
	public static float sortieReachFP(MarketAPI base, Vector2f destinationHyper) {
		if (base == null) return 0f;
		float min = Math.max(1f, ThreatIncConfig.guardFleetFP());
		if (sortieFirstPayableFP(base, destinationHyper) < min) return 0f;
		return Math.max(min, sortiePayableFP(base, destinationHyper));
	}

	/** Combat points a player sortie from this base sails with: everything the colony has free (staged task forces included). */
	protected static float sortieFP(FactionAPI faction, MarketAPI base) {
		return ThreatAid.taskForceFP(base);
	}

	/** How many fleets a player force may be built as in one order: a guard against a runaway loop, never a size. */
	protected static final int PLAYER_FORCE_MAX_FLEETS = 40;

	/**
	 * {fuel, supplies} a player force of {@code combatFP} draws from its base for a sortie to
	 * {@code destinationHyper}: the voyage's deposit (ReachRules.voyageCost) and its ships' hold
	 * for the trip and the stay (ThreatReach.sortieHold) - what buildTaskForce draws, priced per
	 * point before the hulls are built. The board quotes it and gates on it (the user, 2026-10-11:
	 * "needs to say exactly how many supplies fuel etc is required, and of course if not enough
	 * then button is greyed out").
	 */
	public static float[] forceProvisions(MarketAPI base, float combatFP, Vector2f destinationHyper) {
		if (base == null || combatFP <= 0f) return new float[] {0f, 0f};
		float ly = sortieLY(base, destinationHyper);
		float[] wants = threatinc.rules.ReachRules.voyageCost(combatFP, ly, ThreatIncConfig.expeditionFuelPerPointLY(),
				ThreatIncConfig.expeditionSuppliesPerPoint());
		float hold = ThreatReach.sortieHold(base.getFactionId(), combatFP, ly);
		return new float[] {wants[0], wants[1] + hold};
	}

	/**
	 * As {@link #forceProvisions} for a force the task forces on station at the base sail with
	 * ({@code carried} non-null: what they bring, ThreatAidCapacity.foldedProvisions; null for a
	 * colony's own guard, which nothing folds into): those fleets priced as the draw prices them
	 * ({@link #stagedProvisionsGap}), less what they carry - what the base's reserve is actually
	 * drawn for, never below zero.
	 */
	public static float[] forceProvisions(MarketAPI base, float combatFP, Vector2f destinationHyper, float[] carried) {
		float[] need = forceProvisions(base, combatFP, destinationHyper);
		if (carried == null) return need;
		float[] gap = stagedProvisionsGap(base, ThreatAidCapacity.taskForcePoints(combatFP), destinationHyper);
		return new float[] {Math.max(0f, need[0] + gap[0] - Math.max(0f, carried[0])),
				Math.max(0f, need[1] + gap[1] - Math.max(0f, carried[1]))};
	}

	/**
	 * {fuel, supplies} the task forces on station at the base that a force of {@code points}
	 * whole-fleet points sails with (ThreatAidCapacity.stagedToFold, past the base's own free
	 * hulls) want for the trip beyond what the per-point price counted for them. Each sails as it
	 * is ({@link #retask}) and is provisioned as its own ships are ({@link #provisionTaskForce}):
	 * the voyage's deposit on its warship points and its hold at their maintenance for the days
	 * away, never trimmed - where the price (ThreatReach.sortieHold) counted its live points over
	 * TASK_FORCE_HULL_MULT at the faction's measured rate. The gate's figure is the draw's since
	 * 2026-10-29: Haven's Fleet button, enabled at the quote, was refused at the order four times
	 * over a hold 6-55 supplies short at Arkoman, the staged fleet's hold settled into the reserve
	 * and restocked out of it at each press, the figure on the button never moving. A part below
	 * zero stands (a fleet cheaper than its price lowers the draw).
	 */
	public static float[] stagedProvisionsGap(MarketAPI base, float points, Vector2f destinationHyper) {
		float[] gap = new float[] {0f, 0f};
		if (base == null || !ThreatAidCapacity.enabled()) return gap;
		float remaining = points - Math.max(0f, ThreatAidCapacity.ownFreeFP(base));
		if (remaining <= 0f) return gap;
		float ly = sortieLY(base, destinationHyper);
		float days = ThreatReach.daysAway(ly, true, ThreatReach.sortieStay(ly));
		float fuelPerPointLY = ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		for (Order o : ThreatAidCapacity.stagedToFold(base, remaining, null)) {
			CampaignFleetAPI fleet = o.fleet;
			float live = ThreatAidCapacity.liveFP(fleet);
			// what the per-point price counted for this fleet: its share of the points asked
			float counted = Math.min(live, Math.max(0f, remaining)) / ThreatAidCapacity.TASK_FORCE_HULL_MULT;
			float[] priced = threatinc.rules.ReachRules.voyageCost(counted, ly, fuelPerPointLY, suppliesPerPoint);
			priced[1] += ThreatReach.sortieHold(base.getFactionId(), counted, ly);
			float[] real = threatinc.rules.ReachRules.voyageCost(ThreatSoftening.combatFP(fleet), ly,
					fuelPerPointLY, suppliesPerPoint);
			if (ThreatIncConfig.fleetUpkeep()) real[1] += ThreatFrontlines.maintenancePerMonth(fleet) * days / 30f;
			gap[0] += real[0] - priced[0];
			gap[1] += real[1] - priced[1];
			remaining -= live;
		}
		return gap;
	}

	/** Why the base cannot provision a player force of {@code combatFP} to {@code destinationHyper} ({@link #forceProvisions}), one fact per line, or null when it can. */
	public static ThreatNotice.Reason provisionsShort(MarketAPI base, float combatFP, Vector2f destinationHyper) {
		return provisionsShort(base, combatFP, destinationHyper, null);
	}

	/** As above for a force the staged task forces bring {@code carried} fuel and supplies to. */
	public static ThreatNotice.Reason provisionsShort(MarketAPI base, float combatFP, Vector2f destinationHyper,
			float[] carried) {
		if (base == null) return null;
		float[] need = forceProvisions(base, combatFP, destinationHyper, carried);
		float fuel = ThreatReserves.available(base, Commodities.FUEL);
		float supplies = ThreatReserves.available(base, Commodities.SUPPLIES);
		if (fuel >= need[0] - 0.5f && supplies >= need[1] - 0.5f) return null;
		ThreatNotice.Reason reason = ThreatNotice.Reason.of("%s has %s fuel and %s supplies", ThreatNotice.market(base),
				Misc.getWithDGS((int) fuel), Misc.getWithDGS((int) supplies))
				.line("The force takes %s fuel and %s supplies", Misc.getWithDGS((int) Math.ceil(need[0])),
						Misc.getWithDGS((int) Math.ceil(need[1])));
		if (carried != null && (carried[0] > 0.5f || carried[1] > 0.5f)) {
			reason.line("The fleets at %s already carry %s", ThreatNotice.market(base), carriedText(carried));
		}
		return reason;
	}

	/** "14,219 supplies" / "1,200 fuel and 14,219 supplies" - what the fleets at a base already carry, zeros left out (the user, 2026-10-11: plain words, no "folding in"). */
	public static String carriedText(float[] carried) {
		boolean fuel = carried[0] > 0.5f, supplies = carried[1] > 0.5f;
		String f = Misc.getWithDGS((int) carried[0]) + " fuel";
		String s = Misc.getWithDGS((int) carried[1]) + " supplies";
		return fuel && supplies ? f + " and " + s : fuel ? f : s;
	}

	/**
	 * The fleets of the player's force at the board's Force tier (ThreatAid.forceFP: one fleet,
	 * half the hulls free, all of them), each built at one fleet's size (ThreatSoftening.fleetCapFor;
	 * vanilla builds at most maxShipsInAIFleet ships a fleet) and committed to the ledger at what
	 * it sailed with ({@code self}: a colony's own staging, else a sortie). The user, 2026-10-11:
	 * "min med max should apply to all"; Min is exactly one fleet, however short of the ask it
	 * came back. Null when nothing could be raised.
	 */
	protected static List<CampaignFleetAPI> buildPlayerForce(MarketAPI base, FactionAPI faction, boolean self,
			int tier, Vector2f destinationHyper, String label) {
		if (base == null || faction == null) return null;
		return buildPlayerForce(base, faction, self, ThreatAid.forceFP(base, self, tier), tier > 0, destinationHyper, label);
	}

	/**
	 * As above for a force of {@code total} combat points - an outcome's size (ThreatAid.huntAskFP,
	 * orbitAskFP; the user, 2026-10-24: tiers sized for outcomes) - built as fleets of one fleet's
	 * size until it is met ({@code several}), or as exactly one fleet.
	 */
	protected static List<CampaignFleetAPI> buildPlayerForce(MarketAPI base, FactionAPI faction, boolean self,
			float total, boolean several, Vector2f destinationHyper, String label) {
		if (base == null || faction == null) return null;
		if (total < ThreatIncConfig.aidGuardMinFP()) return null;
		// the board's gate, again at the order: a force the base cannot provision does not sail. The
		// task forces staged here that sail with it bring their own fuel, deposit and hold, back in the
		// reserve as each is re-tasked (retask, ahead of its own draw) - the draw is the difference
		float[] carried = self ? null : ThreatAidCapacity.foldedProvisions(base, ThreatAidCapacity.taskForcePoints(total));
		ThreatNotice.Reason unpaid = provisionsShort(base, total, destinationHyper, carried);
		if (unpaid != null) {
			ThreatNotice.titled("Order Refused").bad().icon(faction).lines(unpaid).send();
			return null;
		}
		List<CampaignFleetAPI> built = new ArrayList<CampaignFleetAPI>();
		float got = 0f;
		// the colony's own free points build fleets first; past them, the task forces on station here
		// sail themselves, one a fleet (retask - the user, 2026-10-11: the fleets staged over Haven
		// vanished and respawned out of the planet; until then each folded fleet was expired and its
		// points built again, and a Med hunt of 1,451 built five fleets and folded nine guards)
		float[] ownLeft = new float[] { self ? Float.MAX_VALUE : Math.max(0f, ThreatAidCapacity.ownFreeFP(base)) };
		while (got < total - 1f && built.size() < PLAYER_FORCE_MAX_FLEETS) {
			float left = total - got;
			float ask = Math.min(left, ThreatSoftening.fleetCapFor(base, faction.getId(), left));
			// a remainder under the smallest task force is not a fleet
			if (!built.isEmpty() && ask < ThreatIncConfig.aidGuardMinFP()) break;
			if (!self && ThreatAidCapacity.taskForcePoints(ask) > ownLeft[0] + 0.5f) {
				List<Order> staged = ThreatAidCapacity.stagedToFold(base,
						ThreatAidCapacity.taskForcePoints(ask) - ownLeft[0], null);
				if (!staged.isEmpty()) {
					CampaignFleetAPI fleet = retask(staged.get(0), base, faction, ask, destinationHyper, ownLeft, label);
					if (fleet == null) {
						if (built.isEmpty()) {
							ThreatNotice.titled("Order Refused").bad().icon(faction)
									.line("%s cannot provision the task force on station for the trip",
											ThreatNotice.market(base))
									.send();
						}
						break;
					}
					built.add(fleet);
					got += ThreatSoftening.combatFP(fleet);
					if (!several) break;
					continue;
				}
			}
			CampaignFleetAPI fleet = buildTaskForce(base, faction, ask, destinationHyper);
			if (fleet == null) {
				// held at the launch (the hulls built want a hold the base cannot pay, past the trim):
				// the first fleet refused is the order refused, and the player hears why
				if (built.isEmpty()) {
					ThreatNotice.titled("Order Refused").bad().icon(faction)
							.line("The hulls built at %s want more supplies than it can hold them for",
									ThreatNotice.market(base))
							.send();
				}
				break;
			}
			float combat = ThreatSoftening.combatFP(fleet);
			if (combat < 1f) {
				Misc.fadeAndExpire(fleet);
				break;
			}
			ThreatSoftening.learnFleetCap(faction.getId(), fleet, ask);
			float points = builtPoints(fleet, ask);
			// the colony's own hulls, no fold: a task force on station sails as itself (above)
			if (self) ThreatAidCapacity.commit(base, points, fleet, label);
			else ThreatAidCapacity.commit(base, points, fleet, null, label, false);
			if (!self) ownLeft[0] = Math.max(0f, ownLeft[0] - points);
			built.add(fleet);
			got += combat;
			if (!several) break;
		}
		return built.isEmpty() ? null : built;
	}

	/**
	 * The fleets of an NPC sortie sized to what it faces (2026-09-29): until
	 * their warships bring {@code need} fleet points, fleets of up to
	 * softenFleetFP (or what the faction's yards were seen to build whole,
	 * ThreatSoftening.fleetCap). The first is at least guardFleetFP and drawn
	 * as a sortie always was, and only if the depot pays it in full
	 * (2026-09-29); everything above that minimum is only what the
	 * base's spendable stock pays for (ThreatReserves.spendable), so what
	 * convoys banked for a siege stays. It stops when the next fleet cannot be
	 * paid or a fleet adds nothing. Before, every NPC Support, Defend, guard
	 * and single hunt sailed at exactly guardFleetFP (100) whatever it faced.
	 * Folded into as few fleets as fleetMaxFP takes (vanilla's AI never keeps
	 * separate fleets together), the rest sailing beside it. With {@code whole},
	 * a sortie short of the need does not sail: its provisions go back and it
	 * returns null. Lead first; null when nothing was built.
	 */
	protected static List<CampaignFleetAPI> buildSortie(MarketAPI base, FactionAPI faction, float need,
			Vector2f destinationHyper, boolean whole, String what) {
		if (base == null || faction == null) return null;
		List<CampaignFleetAPI> built = new ArrayList<CampaignFleetAPI>();
		float min = Math.max(1f, ThreatIncConfig.guardFleetFP());
		// asked in the points the depot pays for, and built at them (buildTaskForce;
		// 2026-09-29: closed economy - it was asked need / the market's fleet-size
		// multiplier and built at need, the difference unpaid)
		float got = 0f;
		// ends: each pass builds a fleet that adds at least a point toward a finite
		// need, or breaks (nothing payable, nothing built)
		while (built.isEmpty() || got < need) {
			boolean first = built.isEmpty();
			float perFleet = Math.max(min, Math.min(ThreatIncConfig.softenFleetFP(),
					ThreatSoftening.fleetCap(faction.getId())));
			float ask = Math.min(perFleet, Math.max(0f, need - got));
			float payable = sortiePayableFP(base, destinationHyper);
			if (first) {
				// (2026-09-29: closed economy - payment is a hard gate) the minimum is
				// the smallest sortie, not a free one: a depot that cannot pay it sends
				// nothing. It sailed at guardFleetFP on an empty depot before.
				float firstPayable = sortieFirstPayableFP(base, destinationHyper);
				if (firstPayable < min) {
					ThreatIncConfig.logQuiet("sortie_unpaid:" + faction.getId() + ":" + what, "Sortie from "
							+ base.getName() + " for " + what + " stays home: its depot pays " + (int) firstPayable
							+ " of the " + (int) min + " FP minimum");
					break;
				}
				ask = Math.min(Math.max(min, Math.min(ask, payable)), firstPayable);
			} else {
				ask = Math.min(Math.max(ask, 30f), payable); // a remainder under the smallest fleet is rounded up
				if (ask < 30f) break;
			}
			CampaignFleetAPI fleet = buildTaskForce(base, faction, ask, destinationHyper, true, !first);
			if (fleet == null) break;
			float fp = ThreatSoftening.combatFP(fleet);
			if (fp < 1f) {
				ThreatSoftening.refundShort(fleet, base, 1f);
				fleet.despawn();
				break;
			}
			// vanilla prunes a fleet to maxShipsInAIFleet: pay only for what was built
			float share = Math.min(1f, fp / Math.max(1f, ask));
			if (share < ThreatSoftening.BUILT_SHORT) {
				ThreatSoftening.refundShort(fleet, base, 1f - share);
				ThreatSoftening.learnFleetCap(faction.getId(), fleet, ask);
			}
			built.add(fleet);
			got += fp;
		}
		if (built.isEmpty()) return null;
		if (whole && got < need) {
			// never sailed: provisions back in full
			for (CampaignFleetAPI f : built) {
				ThreatSoftening.refundShort(f, base, 1f);
				f.despawn();
			}
			ThreatIncConfig.logQuiet("sortie_short:" + faction.getId() + ":" + what, "Sortie from " + base.getName()
					+ " for " + what + " stood down: built " + (int) got + " FP of the " + (int) need + " it needs");
			return null;
		}
		List<CampaignFleetAPI> sailing = foldUnderCap(built);
		if (built.size() > 1 || need > min) {
			ThreatIncConfig.log("Sortie from " + base.getName() + " for " + what + ": " + (int) got + " FP in "
					+ sailing.size() + " fleet(s) against a need of " + (int) need);
		}
		return sailing;
	}

	/**
	 * Fleet points an NPC guard over the colony must bring: the Threat's points
	 * over it x reliefOrbitMargin (reliefGoal's rule), or the seen strikes
	 * bound for it x frontlineGarrisonMargin, in task-force points
	 * (ThreatFrontlines.STRENGTH_PER_FP) - whichever is more; 0 with neither.
	 */
	public static float guardNeed(MarketAPI market) {
		if (market == null) return 0f;
		float swarm = ThreatGroundFronts.pointsNear(market, Factions.THREAT, true)
				* Math.max(0.5f, ThreatIncConfig.reliefOrbitMargin());
		float strikes = ThreatFrontlines.strikesWeight(market) * ThreatIncConfig.frontlineGarrisonMargin()
				/ ThreatFrontlines.STRENGTH_PER_FP;
		return Math.max(0f, Math.max(swarm, strikes));
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
		return dispatchGuard(faction, target, 0);
	}

	/** As above at the board's Force tier (the player's force: one fleet, half the hulls free, all). */
	public static Order dispatchGuard(FactionAPI faction, ThreatBases.Base target, int tier) {
		if (faction == null || target == null || target.hyperLoc() == null) return null;
		MarketAPI base = faction.isPlayerFaction()
				? ThreatAid.pickTaskForceSource(target.hyperLoc(),
						ownBase(faction, target) ? target.market : null)
				: pickBase(faction, target.hyperLoc());
		return dispatchGuard(faction, target, base, false, tier);
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
		return dispatchGuard(faction, target, base, aid, 0);
	}

	/** As above at the board's Force tier. */
	public static Order dispatchGuard(FactionAPI faction, ThreatBases.Base target, MarketAPI base,
			boolean aid, int tier) {
		if (faction == null || target == null || target.entity() == null) return null;
		if (base == null) return null;
		boolean own = faction.isPlayerFaction() && !aid && ownBase(faction, target);
		// a colony's guard over itself is its own ships made real: sized by its
		// own free points, and nothing staged there folds into it
		boolean self = own && target.market != null && base == target.market;
		float days = own ? ThreatIncConfig.guardOwnDays() : ThreatIncConfig.guardDays();
		if (!faction.isPlayerFaction()) {
			// an NPC guard is sized to what threatens the colony (buildSortie), and
			// sails with what the depot can pay toward it
			List<CampaignFleetAPI> fleets = buildSortie(base, faction, guardNeed(target.market), target.hyperLoc(),
					false, "a guard of " + target.name());
			if (fleets == null) return null;
			Order lead = null;
			for (CampaignFleetAPI f : fleets) {
				Order o = guardOrder(f, faction, target, base, days, aid);
				if (lead == null) lead = o;
			}
			return lead;
		}
		List<CampaignFleetAPI> fleets = buildPlayerForce(base, faction, self, tier, target.hyperLoc(),
				"guard of " + target.name());
		if (fleets == null) return null;
		Order o = null;
		for (CampaignFleetAPI fleet : fleets) {
			Order each = guardOrder(fleet, faction, target, base, days, aid);
			if (o == null) o = each;
		}
		// the player's own order is news; an NPC navy's relief or coalition guard
		// is its routine (no progress notices): record's log line is all it gets
		if (!aid) {
			ThreatNotice n = fleets.size() > 1
					? notice(faction, "Task Force Sails", "%s fleets from %s move to guard %s",
							"" + fleets.size(), ThreatNotice.market(base), ThreatNotice.base(target))
					: notice(faction, "Task Force Sails", "task force from %s moves to guard %s",
							ThreatNotice.market(base), ThreatNotice.base(target));
			if (days > 0f) n.line("For %s days", (int) days);
			n.send();
		}
		return o;
	}

	/**
	 * An NPC faction's guard of {@code points} fleet points from the base over the player's colony
	 * (a Request's Fleet lever, ThreatAidRequests.answerNow): built by buildSortie at the points the
	 * depot pays for, guardDays over the world, then home. Null when nothing could be built.
	 */
	public static Order dispatchGuardPoints(FactionAPI faction, MarketAPI market, MarketAPI base, float points) {
		if (faction == null || market == null || base == null || points <= 0f) return null;
		ThreatBases.Base target = ThreatBases.of(market);
		if (target == null || target.entity() == null || target.hyperLoc() == null) return null;
		List<CampaignFleetAPI> fleets = buildSortie(base, faction, points, target.hyperLoc(), false,
				"a guard of " + target.name());
		if (fleets == null) return null;
		Order lead = null;
		for (CampaignFleetAPI f : fleets) {
			Order o = guardOrder(f, faction, target, base, ThreatIncConfig.guardDays(), false);
			if (lead == null) lead = o;
		}
		return lead;
	}

	/** Puts a built fleet on guard over the base's target: ORBIT_AGGRESSIVE for {@code days}, then home; the recorded order. */
	protected static Order guardOrder(CampaignFleetAPI fleet, FactionAPI faction, ThreatBases.Base target,
			MarketAPI base, float days, boolean aid) {
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.entity(),
				days > 0f ? days : NO_TERM_DAYS, "guarding " + target.name());
		// the home leg is GO_TO_LOCATION, not a despawn: poll() hands the fleet
		// to ThreatReturns when the order runs out, and that settles the refund
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_GUARD, base, target.id(), target.name(), days);
		o.aid = aid;
		if (!faction.getId().equals(target.factionId())) o.recipientFactionId = target.factionId();
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

	/**
	 * THE PLAYER'S HUNT (2026-09-24, replacing Intercept): a task force from
	 * the sender's best base hunts the hive system's Defense Swarms, weakest
	 * garrison first, for softenDays - the same order an NPC hunting force
	 * flies (ThreatSoftening moves it on and brings it home).
	 */
	public static Order dispatchHunt(FactionAPI faction, StarSystemAPI hive) {
		return dispatchHunt(faction, hive, 0);
	}

	/** As above at the board's Force tier. */
	public static Order dispatchHunt(FactionAPI faction, StarSystemAPI hive, int tier) {
		if (faction == null || hive == null) return null;
		MarketAPI base;
		if (faction.isPlayerFaction()) {
			// sized for the outcome (ThreatAid.huntAskFP), from the colony that can field it, or refused with the figures
			ThreatAid.Quote q = ThreatAid.quoteStrike(hive, tier);
			if (!q.ok()) {
				ThreatNotice.titled("Hunt Refused").bad().icon(faction).lines(q.reason).send();
				return null;
			}
			base = q.source;
		} else {
			base = pickBase(faction, hive.getLocation());
		}
		return dispatchHunt(faction, hive, base, false, tier);
	}

	/** As above from a given base; {@code aid} marks the player's aid sortie (the board's hunt for another faction). */
	public static Order dispatchHunt(FactionAPI faction, StarSystemAPI hive, MarketAPI base, boolean aid) {
		return dispatchHunt(faction, hive, base, aid, 0);
	}

	/** As above at the board's Force tier (the player's force: one fleet, half the hulls free, all). */
	public static Order dispatchHunt(FactionAPI faction, StarSystemAPI hive, MarketAPI base, boolean aid, int tier) {
		if (faction == null || hive == null || base == null) return null;
		MarketAPI target = ThreatSoftening.huntTarget(ThreatIntel.observerOf(faction), hive.getId());
		if (target == null || target.getPrimaryEntity() == null) return null;
		Order o;
		int count = 1;
		if (faction.isPlayerFaction()) {
			// the outcome's size (ThreatAid.huntAskFP: the weakest swarm by the margin, the swarms seen, those by the margin)
			List<CampaignFleetAPI> fleets = buildPlayerForce(base, faction, false,
					Math.max(ThreatAid.huntAskFP(hive, tier), ThreatIncConfig.aidGuardMinFP()), true, hive.getLocation(),
					"hunt in the " + hive.getNameWithLowercaseTypeShort());
			if (fleets == null) return null;
			o = null;
			// one press is one group: its fleets reach the same conclusion within days of
			// each other and the player hears it once (ThreatSoftening.speaks)
			String groupId = faction.getId() + ":hunt:" + hive.getId() + ":" + Global.getSector().getClock().getTimestamp();
			for (CampaignFleetAPI f : fleets) {
				Order each = huntOrder(f, faction, base, target, null, null);
				each.groupId = groupId;
				if (o == null) o = each;
			}
			count = fleets.size();
		} else {
			// sized to beat the garrison it starts on, as it stands now, by the
			// siege's margin - the rule it moves on by (ThreatSoftening.advanceSingle) -
			// or it does not sail
			float need = ThreatSoftening.musterFloorFP(ThreatIntel.observerOf(faction), target);
			List<CampaignFleetAPI> fleets = buildSortie(base, faction, need, hive.getLocation(), true,
					"a hunt in the " + hive.getNameWithLowercaseTypeShort());
			if (fleets == null) return null;
			o = null;
			for (CampaignFleetAPI f : fleets) {
				Order each = huntOrder(f, faction, base, target, null, null);
				each.aid = aid;
				if (o == null) o = each;
			}
		}
		o.aid = aid;
		if (!aid) {
			(count > 1
					? notice(faction, "Task Force Sails", "%s fleets from %s move to hunt the Defense Swarms in the %s",
							"" + count, ThreatNotice.market(base), hive.getNameWithLowercaseTypeShort())
					: notice(faction, "Task Force Sails", "task force from %s moves to hunt the Defense Swarms in the %s",
							ThreatNotice.market(base), hive.getNameWithLowercaseTypeShort()))
					.line("For %s days", (int) ThreatIncConfig.softenDays()).send();
		}
		return o;
	}

	/**
	 * One fleet of a hunting force (ThreatSoftening): {@code fp} combat points
	 * from the base, provisioned from its reserve, fighting the Defense Swarms
	 * over the hive colony for softenDays, then home on the tracked leg.
	 */
	public static Order dispatchHunt(FactionAPI faction, MarketAPI base, MarketAPI hive, float fp) {
		return dispatchHunt(faction, base, hive, fp, null, null);
	}

	/**
	 * As above; a fleet of an NPC hunting force ({@code forceId}) first flies
	 * blinkered to the force's {@code muster} point with the rest of it, and
	 * is sent in by ThreatSoftening once the force has gathered.
	 */
	public static Order dispatchHunt(FactionAPI faction, MarketAPI base, MarketAPI hive, float fp,
			String forceId, SectorEntityToken muster) {
		if (faction == null || base == null || hive == null || hive.getPrimaryEntity() == null) return null;
		StarSystemAPI system = hive.getStarSystem();
		if (system == null) return null;
		// a force's fleets spend what a hunt there may take (ThreatSoftening.huntSpendable):
		// never a siege's bank, unless that siege waits on this very hunt
		CampaignFleetAPI fleet = buildTaskForce(base, faction, fp, system.getLocation(), true, forceId != null,
				forceId != null ? system : null);
		if (fleet == null) return null;
		return huntOrder(fleet, faction, base, hive, forceId, muster);
	}

	/** Puts a built fleet on a hunt of the hive's system (mustering first for a force); the recorded order. */
	protected static Order huntOrder(CampaignFleetAPI fleet, FactionAPI faction, MarketAPI base, MarketAPI hive,
			String forceId, SectorEntityToken muster) {
		StarSystemAPI system = hive.getStarSystem();
		float days = ThreatIncConfig.softenDays();
		fleet.setName("Hunting Force");
		// the player's hunting fleets earn swarm bounties on their own
		if (faction.isPlayerFaction()) ThreatSwarmBountyIntel.HunterPay.attach(fleet);
		if (forceId != null && muster != null) {
			// a fleet that picks a fight on the way reaches the muster without the strength it was counted at
			fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, muster, 1000f,
					"mustering to hunt the swarms in the " + system.getNameWithLowercaseTypeShort());
			fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, muster, 1000f,
					"mustering to hunt the swarms in the " + system.getNameWithLowercaseTypeShort());
		} else {
			engageHunt(fleet, base, hive, days);
		}
		Order o = record(fleet, faction, KIND_HUNT, base, hive.getId(), hive.getName(), days);
		o.systemId = system.getId();
		o.forceId = forceId;
		return o;
	}

	/** A hunting fleet's orders once it goes in: the swarms over the hive for {@code days}, then home. */
	public static void engageHunt(CampaignFleetAPI fleet, MarketAPI base, MarketAPI hive, float days) {
		fleet.clearAssignments();
		fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(), Math.max(1f, days),
				"hunting the swarms over " + hive.getName());
		if (base != null && base.getPrimaryEntity() != null) {
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
					1000f, "returning to " + base.getName());
		}
	}

	/** Moves a hunting fleet on to another colony of the same system, for what is left of its term. */
	public static void retargetHunt(Order o, MarketAPI hive) {
		if (o == null || o.fleet == null || hive == null || hive.getPrimaryEntity() == null) return;
		MarketAPI base = o.baseMarketId != null ? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
		o.targetId = hive.getId();
		o.targetName = hive.getName();
		o.chasingId = null;
		// en route again until it is over the next colony (the board reads
		// arrived); an aid hunt's standing was earned at its first station
		if (o.arrived) o.credited = true;
		o.arrived = false;
		// "badly hurt" is measured from the strength it moved on with
		ThreatReturns.rebaseline(o.fleet);
		engageHunt(o.fleet, base, hive, o.daysLeft());
	}

	/**
	 * A player hunt with every orbit of the system clear goes after a loose swarm
	 * (ThreatSoftening.advanceSingle, 2026-10-11): intercepts it for what is left of
	 * the term, then home. Nothing to re-issue while it chases the same fleet.
	 */
	public static void chaseLoose(Order o, CampaignFleetAPI swarm) {
		if (o == null || o.fleet == null || swarm == null) return;
		if (swarm.getId().equals(o.chasingId)) return;
		MarketAPI base = o.baseMarketId != null ? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
		o.chasingId = swarm.getId();
		o.fleet.clearAssignments();
		o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
		String where = o.fleet.getContainingLocation() != null
				? o.fleet.getContainingLocation().getNameWithLowercaseTypeShort() : "the system";
		o.fleet.addAssignment(FleetAssignment.INTERCEPT, swarm, Math.max(1f, o.daysLeft()),
				"hunting the swarms in the " + where);
		if (base != null && base.getPrimaryEntity() != null) {
			o.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
					1000f, "returning to " + base.getName());
		}
	}

	/** Ends an order now: its fleet goes home on the tracked leg (refund on arrival). */
	public static void standDown(Order o, String why) {
		if (o == null) return;
		all().remove(o);
		if (o.fleet != null && o.fleet.isAlive()) ThreatReturns.sendHome(o.fleet, o.factionId, o.baseMarketId);
		ThreatIncConfig.log("Order stood down (" + why + "): " + o.factionId + " " + o.task());
		if (o.raid) {
			ThreatAttackPlanner.news(o.factionId, "a raid on " + o.targetName + " stood down");
			ThreatPlays.raidEnded(o, "stood down: " + why);
		}
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
	 * alike; an NPC one sized to take the orbit (buildSortie).
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
		return dispatchOrbit(faction, hive, kind, 0);
	}

	/** As above at the board's Force tier (the player's force: one fleet, half the hulls free, all). */
	public static Order dispatchOrbit(FactionAPI faction, MarketAPI hive, String kind, int tier) {
		if (faction == null || hive == null) return null;
		if (faction.isPlayerFaction()) {
			// the nearest fleet already under orders takes it (2026-09-06, the
			// user: "escort should repurpose the nearest fleet"); a task force
			// is raised only when none is out
			Reassignable near = nearestReassignable(faction, hive, kind);
			if (near != null) return adoptOrbit(near.fleet, faction, hive, near.duty, kind);
		}
		MarketAPI base;
		if (faction.isPlayerFaction()) {
			// sized for the outcome (ThreatAid.orbitAskFP), from the colony that can field it (the gate: orbitRefusal)
			ThreatAid.Quote q = ThreatAid.quoteOrbit(hive, kind, tier);
			if (!q.ok()) return null;
			base = q.source;
		} else {
			base = pickBase(faction, hive.getLocationInHyperspace());
		}
		return dispatchOrbit(faction, hive, base, kind, tier);
	}

	/** A Support or Defend sortie over the world from a given base. */
	public static Order dispatchOrbit(FactionAPI faction, MarketAPI hive, MarketAPI base, String kind) {
		return dispatchOrbit(faction, hive, base, kind, 0);
	}

	/** As above at the board's Force tier. */
	public static Order dispatchOrbit(FactionAPI faction, MarketAPI hive, MarketAPI base, String kind, int tier) {
		if (!orbitEnabled(kind)) return null;
		if (faction == null || hive == null || hive.getPrimaryEntity() == null) return null;
		if (base == null) return null;
		float days = orbitDays(kind);
		if (!faction.isPlayerFaction()) {
			// sized to take the orbit, the siege's own rule (siegeOrbitNeeded); a
			// sortie that cannot clear it only feeds the swarm and holds the door open
			// for convoys to be mauled in it (run 6: 111 FP against 4,800), so it
			// does not sail
			float need = IncursionManager.siegeOrbitNeeded(faction, java.util.Collections.singletonList(hive));
			List<CampaignFleetAPI> fleets = buildSortie(base, faction, need, hive.getLocationInHyperspace(), true,
					orbitName(kind).toLowerCase() + " over " + hive.getName());
			if (fleets == null) return null;
			Order lead = null;
			for (CampaignFleetAPI f : fleets) {
				Order o = orbitOrder(f, faction, hive, base, kind, days);
				if (lead == null) lead = o;
			}
			return lead;
		}
		// the outcome's size (ThreatAid.orbitAskFP: the swarm over the world, by the tier's margin)
		List<CampaignFleetAPI> fleets = buildPlayerForce(base, faction, false,
				Math.max(ThreatAid.orbitAskFP(hive, base, tier), ThreatIncConfig.aidGuardMinFP()), true,
				hive.getLocationInHyperspace(), orbitName(kind).toLowerCase() + " over " + hive.getName());
		if (fleets == null) return null;
		Order o = null;
		for (CampaignFleetAPI fleet : fleets) {
			Order each = orbitOrder(fleet, faction, hive, base, kind, days);
			if (o == null) o = each;
		}
		// the player's own order is news; an NPC navy's sortie is automatic
		// (ThreatConvoys.supportFor, every ~60 days per contested front) -
		// progress, not news, and record's log line is all it gets
		(fleets.size() > 1
				? notice(faction, "Task Force Sails", "%s fleets from %s move to " + orbitVerb(kind) + " %s",
						"" + fleets.size(), ThreatNotice.market(base), ThreatNotice.market(hive))
				: notice(faction, "Task Force Sails", "task force from %s moves to " + orbitVerb(kind) + " %s",
						ThreatNotice.market(base), ThreatNotice.market(hive)))
				.line("For %s days", (int) days).send();
		return o;
	}

	/** Puts a built fleet on a Support or Defend sortie over the world for {@code days}, then home; the recorded order. */
	protected static Order orbitOrder(CampaignFleetAPI fleet, FactionAPI faction, MarketAPI hive, MarketAPI base,
			String kind, float days) {
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(),
				days > 0f ? days : NO_TERM_DAYS, orbitTask(kind, hive.getName()));
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		return record(fleet, faction, kind, base, hive.getId(), hive.getName(), days);
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
	public static String orbitEffect(MarketAPI world, float fp, String kind, CampaignFleetAPI fleet) {
		return KIND_DEFEND.equals(kind) ? defendEffect(world, fp, fleet) : supportEffect(world, fp, fleet);
	}

	/**
	 * A day of the sortie's bombardment in the world's current numbers, one
	 * fact a line: the defences a day moves, the fuel it burns, and the return
	 * fire - the ships by name when the fleet is known
	 * ({@link ThreatGroundFronts#lossLines}), else the fleet points.
	 */
	protected static void bombardLines(StringBuilder sb, MarketAPI world, float fp, CampaignFleetAPI fleet,
			String when) {
		ThreatGroundFronts.BombardDay day = ThreatGroundFronts.bombardDay(fp, world, false);
		sb.append("\n").append(when).append(": defences ").append(Math.round(day.conditionNow * 100f))
				.append("% to ").append(Math.round(day.conditionAfter * 100f)).append("% a day.");
		sb.append("\nBurns ").append(Misc.getWithDGS(Math.round(day.fuel))).append(" fuel a day.");
		if (day.returnFire < 0.05f) return;
		java.util.List<String> lost = fleet != null
				? ThreatGroundFronts.lossLines(fleet, day.returnFire) : new java.util.ArrayList<String>();
		if (lost.isEmpty()) {
			sb.append("\nReturn fire: about ").append(String.format("%.1f", day.returnFire))
					.append(" fleet points of ships a day, smallest first.");
		} else {
			for (String line : lost) sb.append("\n").append(line);
		}
	}

	/**
	 * What a Support sortie of {@code fp} points does over the world, for the
	 * button and the confirm (the user, 2026-09-06: the button must say what
	 * they will do, when they bombard, and what it costs). One line per fact.
	 */
	public static String supportEffect(MarketAPI world, float fp, CampaignFleetAPI fleet) {
		if (world == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append("Holds the orbit for ").append((int) ThreatIncConfig.supportDays())
				.append(" days; fights anything contesting it.");
		sb.append("\n").append(ThreatGroundFronts.siegeClockLine(world));
		if (ThreatShield.present(world)) sb.append("\n").append(ThreatShield.line(world));
		bombardLines(sb, world, fp, fleet, "Bombards whether or not the front holds");
		return sb.toString();
	}

	/**
	 * What a Defend sortie of {@code fp} points does over the world, for the
	 * button and the confirm. One line per fact; that the bombardment is
	 * conditional is the whole difference from Support.
	 */
	public static String defendEffect(MarketAPI world, float fp, CampaignFleetAPI fleet) {
		if (world == null) return "";
		StringBuilder sb = new StringBuilder();
		sb.append("Holds the orbit for ").append((int) ThreatIncConfig.defendDays())
				.append(" days; fights anything contesting it.");
		sb.append("\n").append(ThreatGroundFronts.siegeClockLine(world));
		if (ThreatShield.present(world)) sb.append("\n").append(ThreatShield.line(world));
		bombardLines(sb, world, fp, fleet, "Bombards only while the front cannot hold");
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

	/** Whether this faction has a live order of the kind on the target; a raid is not a Support here ({@link #hasRaid}). */
	public static boolean hasOrder(String kind, String factionId, String targetId) {
		if (kind == null || factionId == null || targetId == null) return false;
		for (Order o : all()) {
			if (!kind.equals(o.kind) || o.raid) continue;
			if (!factionId.equals(o.factionId) || !targetId.equals(o.targetId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) return true;
		}
		return false;
	}

	/** Whether this faction has a live raid on the world, sailing or on station. */
	public static boolean hasRaid(String factionId, String hiveMarketId) {
		if (factionId == null || hiveMarketId == null) return false;
		for (Order o : all()) {
			if (!o.raid || !factionId.equals(o.factionId) || !hiveMarketId.equals(o.targetId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// raids (ThreatAttackPlanner, 2026-10-01; docs/attack-planner.md section 4)
	// ------------------------------------------------------------------

	/** Days a raid's orbit may stay contested before it leaves: a poll or two, since the hive refills its garrison list every poll. */
	public static final float RAID_CONTEST_GRACE_DAYS = 1f;

	/** Days a raid's first fleet at the world waits for the rest of its raid before the contest and loss rules judge it. */
	public static final float RAID_GATHER_DAYS = 3f;

	/** The planner's fallback for every fleet of the raid the lead order belongs to. */
	public static void setRaidFallback(Order lead, String fallbackId) {
		if (lead == null) return;
		for (Order o : all()) {
			if (o == lead || (lead.raidId != null && lead.raidId.equals(o.raidId))) o.fallbackId = fallbackId;
		}
	}

	/**
	 * Days this fleet of a raid waits at the world for the rest of it: until its
	 * last fleet arrived, at most RAID_GATHER_DAYS while one is still on its way.
	 * A lone fleet's raid waits none.
	 */
	protected static float gatherDays(Order o) {
		if (o.raidId == null || o.arrivedTimestamp == 0L) return 0f;
		com.fs.starfarer.api.campaign.CampaignClockAPI clock = Global.getSector().getClock();
		float mine = clock.getElapsedDaysSince(o.arrivedTimestamp);
		float waited = 0f;
		for (Order s : all()) {
			if (s == o || !s.raid || !o.raidId.equals(s.raidId)) continue;
			if (s.fleet == null || !s.fleet.isAlive() || s.fleet.isExpired()) continue;
			if (!s.arrived || s.arrivedTimestamp == 0L) return RAID_GATHER_DAYS;
			waited = Math.max(waited, mine - clock.getElapsedDaysSince(s.arrivedTimestamp));
		}
		return Math.min(RAID_GATHER_DAYS, waited);
	}

	/** Fleet points of the raid's fleets at its world: the order's own and those of the same raid that arrived. */
	protected static float raidFP(Order o) {
		float fp = o.fleet.getFleetPoints();
		if (o.raidId == null) return fp;
		for (Order s : all()) {
			if (s == o || !s.raid || !o.raidId.equals(s.raidId) || !s.arrived) continue;
			if (s.fleet != null && s.fleet.isAlive() && !s.fleet.isExpired()) fp += s.fleet.getFleetPoints();
		}
		return fp;
	}

	/**
	 * A RAID on the hive world from the base: fleets of {@code need} points
	 * (the planner's size - its world's guns and twice the swarms the faction
	 * last saw over it), on a Support order flagged raid for {@code days},
	 * then home. Paid as any NPC sortie (buildSortie, whole: short of the need
	 * it does not sail). The lead order, or null.
	 */
	public static Order dispatchRaid(FactionAPI faction, MarketAPI hive, MarketAPI base, float need, float days,
			String fallbackId) {
		if (faction == null || faction.isPlayerFaction() || hive == null || hive.getPrimaryEntity() == null) return null;
		if (base == null || base.getPrimaryEntity() == null || need <= 0f || days <= 0f) return null;
		List<CampaignFleetAPI> fleets = buildSortie(base, faction, need, hive.getLocationInHyperspace(), true,
				"raid on " + hive.getName());
		if (fleets == null) return null;
		String raidId = faction.getId() + ":" + hive.getId() + ":" + Global.getSector().getClock().getTimestamp();
		Order lead = null;
		for (CampaignFleetAPI f : fleets) {
			f.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, hive.getPrimaryEntity(), days, "raiding " + hive.getName());
			f.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(), 1000f,
					"returning to " + base.getName());
			Order o = record(f, faction, KIND_SUPPORT, base, hive.getId(), hive.getName(), days, true);
			o.fallbackId = fallbackId;
			o.raidId = raidId;
			if (lead == null) lead = o;
		}
		return lead;
	}

	/** A raid reached its world: what it arrived with is its loss line's measure. */
	protected static void raidArrived(Order o) {
		o.arrivalFP = o.fleet.getFleetPoints();
		o.arrivedTimestamp = Global.getSector().getClock().getTimestamp();
		MarketAPI world = Global.getSector().getEconomy().getMarket(o.targetId);
		ThreatIncConfig.log("Raid arrived: " + o.factionId + " " + o.fleet.getName() + " at " + (int) o.arrivalFP
				+ " FP over " + o.targetName + ", " + (int) ThreatGroundFronts.hostilePointsNear(o.factionId, world)
				+ " FP of swarms here, " + (int) ThreatIntel.worldFP(o.factionId, world) + " reported");
	}

	/**
	 * Why the raid is over now, or null: its world is gone or no longer the
	 * swarm's; at its world, the orbit contested past RAID_CONTEST_GRACE_DAYS,
	 * a day that would buy less than a day down (the commander's stop), or a
	 * third of it lost (raidLossFraction).
	 */
	protected static String raidOver(Order o) {
		if (o.daysLeft() <= 0f) return "its term is served";
		MarketAPI world = Global.getSector().getEconomy().getMarket(o.targetId);
		if (world == null || !world.isInEconomy() || !Factions.THREAT.equals(world.getFactionId())) {
			return "its world is no longer the swarm's";
		}
		if (!o.arrived) return null;
		// the rest of its raid is on its way: judged when the raid is all there
		if (Global.getSector().getClock().getElapsedDaysSince(o.arrivedTimestamp) < gatherDays(o)) return null;
		long now = Global.getSector().getClock().getTimestamp();
		// the raid's own fleets count while the leash walks one back from a chase (h52a: 295 FP
		// sent home by 114 "against 0" after a week of bombing)
		float hostile = ThreatGroundFronts.hostilePointsNear(o.factionId, world);
		float friendly = Math.max(ThreatGroundFronts.friendlyPointsNear(o.factionId, world), raidFP(o));
		if (ThreatGroundFronts.orbitContestedFor(o.factionId, world)
				&& hostile >= friendly * Math.max(0f, ThreatIncConfig.orbitContestFraction())) {
			if (o.contestedSince == 0L) o.contestedSince = now;
			if (Global.getSector().getClock().getElapsedDaysSince(o.contestedSince) >= RAID_CONTEST_GRACE_DAYS) {
				return "orbit contested, " + (int) hostile + " fleet points of swarms against " + (int) friendly;
			}
			return null;
		}
		o.contestedSince = 0L;
		float fp = o.fleet.getFleetPoints();
		// nothing left to bomb with: its own fuel and the stock that reaches it (supportSlice would idle)
		if (ThreatGroundFronts.ordnanceAvailable(o.fleet, o.factionId) < ThreatGroundFronts.bombardFuelPerDay(world, fp)) {
			return "out of ordnance";
		}
		// the day bombards with every fleet of the faction over the world, as the slice does (orbitPoints)
		if (ThreatGroundFronts.dailyGain(world, ThreatGroundFronts.orbitPoints(o.factionId, world, fp)) < 1f) {
			return "a day buys less than a day down";
		}
		float lossLine = threatinc.rules.BattleRules.raidLossLine(o.arrivalFP, ThreatIncConfig.raidLossFraction());
		if (fp < lossLine) return "lost " + (int) (o.arrivalFP - fp) + " of " + (int) o.arrivalFP + " fleet points";
		return null;
	}

	/**
	 * A raid turned back within its first days at the world - it met swarms it
	 * was not sized for - strikes its fallback instead, if the faction's report
	 * of that world says it can (twice the swarms, and a day still buys a day
	 * down) and its base's spendable fuel pays the detour
	 * (ThreatSoftening.detourFuel). True when it went.
	 */
	protected static boolean divertRaid(Order o, String why) {
		if (o.fallbackId == null || !o.arrived) return false;
		// only a verdict on arrival diverts - once the raid is all there; a raid that has been bombing goes home
		if (Global.getSector().getClock().getElapsedDaysSince(o.arrivedTimestamp)
				> gatherDays(o) + RAID_CONTEST_GRACE_DAYS + 1f) {
			return false;
		}
		MarketAPI from = Global.getSector().getEconomy().getMarket(o.targetId);
		MarketAPI to = Global.getSector().getEconomy().getMarket(o.fallbackId);
		o.fallbackId = null;
		if (from == null || to == null || to == from || to.getPrimaryEntity() == null || !to.isInEconomy()) return false;
		if (!Factions.THREAT.equals(to.getFactionId()) || raidedByAnother(o, to.getId())) return false;
		// the raid's fleets at the world reach the same verdict and go together
		float fp = raidFP(o);
		float reported = ThreatIntel.worldFP(o.factionId, to);
		if (fp <= reported / Math.max(0.01f, ThreatIncConfig.orbitContestFraction())) return false;
		if (ThreatGroundFronts.dailyGain(to, fp) < 1f) return false;
		MarketAPI base = Global.getSector().getEconomy().getMarket(o.baseMarketId);
		float cost = ThreatSoftening.detourFuel(o, from.getStarSystem(), to.getStarSystem());
		if (cost > 0f) {
			if (base == null || ThreatReserves.spendable(base, Commodities.FUEL) < cost) return false;
			float got = ThreatReserves.drawSpendable(base, Commodities.FUEL, cost);
			// carried as the rest of its fuel, so a lost hull's share stays lost (ThreatReturns.fuelBack)
			com.fs.starfarer.api.campaign.rules.MemoryAPI mem = o.fleet.getMemoryWithoutUpdate();
			mem.set(ThreatReturns.MEM_FUEL, mem.getFloat(ThreatReturns.MEM_FUEL) + got);
		}
		o.targetId = to.getId();
		o.targetName = to.getName();
		o.arrived = false;
		o.arrivalFP = 0f;
		o.arrivedTimestamp = 0L;
		o.contestedSince = 0L;
		o.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		o.fleet.clearAssignments();
		o.fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, to.getPrimaryEntity(), Math.max(1f, o.days),
				"raiding " + to.getName());
		if (base != null && base.getPrimaryEntity() != null) {
			o.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(), 1000f,
					"returning to " + base.getName());
		}
		ThreatIncConfig.log("Raid diverted: " + o.factionId + " " + o.fleet.getName() + " turns from " + from.getName()
				+ " (" + why + ") to " + to.getName() + ", " + (int) fp + " FP against " + (int) reported
				+ " reported, detour " + (int) cost + " fuel");
		ThreatAttackPlanner.news(o.factionId, "a raid turned from " + from.getName());
		return true;
	}

	/** Whether another raid of the order's faction - not one of its own fleets - is on the world. */
	protected static boolean raidedByAnother(Order mine, String hiveMarketId) {
		for (Order o : all()) {
			if (o == mine || !o.raid || !mine.factionId.equals(o.factionId) || !hiveMarketId.equals(o.targetId)) continue;
			if (mine.raidId != null && mine.raidId.equals(o.raidId)) continue;
			if (o.fleet != null && o.fleet.isAlive() && !o.fleet.isExpired()) return true;
		}
		return false;
	}

	/** Whether a raid of the faction is on its way to, or over, a world of the system. */
	public static boolean raidInSystem(String factionId, String systemId) {
		if (factionId == null || systemId == null) return false;
		for (Order o : all()) {
			if (!o.raid || !factionId.equals(o.factionId) || o.fleet == null || !o.fleet.isAlive()) continue;
			MarketAPI world = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (world != null && world.getStarSystem() != null && systemId.equals(world.getStarSystem().getId())) {
				return true;
			}
		}
		return false;
	}

	/** The raid goes home on the tracked leg (refund on arrival), its days at the world in the log. */
	protected static void endRaid(Order o, String why) {
		all().remove(o);
		ThreatReturns.sendHome(o.fleet, o.factionId, o.baseMarketId);
		float days = o.arrivedTimestamp != 0L
				? Global.getSector().getClock().getElapsedDaysSince(o.arrivedTimestamp) : 0f;
		ThreatIncConfig.log("Raid over: " + o.factionId + " " + o.fleet.getName() + " over " + o.targetName
				+ " (" + why + "), " + String.format("%.1f", days) + " days at the world, "
				+ (int) o.arrivalFP + " -> " + (int) o.fleet.getFleetPoints() + " FP");
		ThreatAttackPlanner.news(o.factionId, "a raid on " + o.targetName + " ended");
		ThreatPlays.raidEnded(o, why);
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

	/** Within LEASH_MULT x ORBIT_HOLD_RANGE of the planet, in its system: the leash leaves it alone. */
	public static boolean withinLeash(CampaignFleetAPI fleet, SectorEntityToken planet) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return false;
		if (fleet.getContainingLocation() != planet.getContainingLocation()) return false;
		return Misc.getDistance(fleet, planet) <= ORBIT_HOLD_RANGE * LEASH_MULT;
	}

	/** Why this faction cannot send Support over the world now, or null if it can. */
	public static String supportBlockReason(FactionAPI faction, MarketAPI hive) {
		return orbitBlockReason(faction, hive, KIND_SUPPORT);
	}

	/** As above at the board's Force tier. */
	public static String supportBlockReason(FactionAPI faction, MarketAPI hive, int tier) {
		return orbitBlockReason(faction, hive, KIND_SUPPORT, tier);
	}

	/** Why this faction cannot send Defend over the world now, or null if it can. */
	public static String defendBlockReason(FactionAPI faction, MarketAPI hive) {
		return orbitBlockReason(faction, hive, KIND_DEFEND);
	}

	/** As above at the board's Force tier. */
	public static String defendBlockReason(FactionAPI faction, MarketAPI hive, int tier) {
		return orbitBlockReason(faction, hive, KIND_DEFEND, tier);
	}

	/** Why this faction cannot send a Support or Defend sortie over the world now, or null if it can. */
	public static String orbitBlockReason(FactionAPI faction, MarketAPI hive, String kind) {
		return ThreatNotice.text(orbitRefusal(faction, hive, kind));
	}

	/** As above at the board's Force tier. */
	public static String orbitBlockReason(FactionAPI faction, MarketAPI hive, String kind, int tier) {
		return ThreatNotice.text(orbitRefusal(faction, hive, kind, tier));
	}

	/** As above, one fact per line for the refusal notice. */
	public static ThreatNotice.Reason orbitRefusal(FactionAPI faction, MarketAPI hive, String kind) {
		return orbitRefusal(faction, hive, kind, 0);
	}

	/** As above at the board's Force tier: the gate quotes the force the order sails (it quoted one fleet and sailed the tier's). */
	public static ThreatNotice.Reason orbitRefusal(FactionAPI faction, MarketAPI hive, String kind, int tier) {
		if (!orbitEnabled(kind)) {
			return ThreatNotice.Reason.of(orbitName(kind) + " sorties are disabled in the mod settings");
		}
		if (faction == null || hive == null) return ThreatNotice.Reason.of("No target");
		if (hasOrder(kind, faction.getId(), hive.getId())) {
			return ThreatNotice.Reason.of("A task force is already on its way there");
		}
		if (faction.isPlayerFaction()) {
			if (nearestReassignable(faction, hive, kind) != null) return null;
			ThreatAid.Quote q = ThreatAid.quoteOrbit(hive, kind, tier);
			return q.ok() ? null : q.reason;
		}
		return pickBase(faction, hive.getLocationInHyperspace()) == null
				? ThreatNotice.Reason.of("No base in reach") : null;
	}

	protected static Order record(CampaignFleetAPI fleet, FactionAPI faction, String kind,
			MarketAPI base, String targetId, String targetName, float days) {
		return record(fleet, faction, kind, base, targetId, targetName, days, false);
	}

	/** As above; {@code raid} flags a Support as a raid ({@link Order#raid}). */
	protected static Order record(CampaignFleetAPI fleet, FactionAPI faction, String kind,
			MarketAPI base, String targetId, String targetName, float days, boolean raid) {
		Order o = new Order();
		o.fleet = fleet;
		o.factionId = faction.getId();
		o.kind = kind;
		o.baseMarketId = base.getId();
		o.targetId = targetId;
		o.targetName = targetName;
		o.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		o.days = days;
		o.raid = raid;
		all().add(o);
		ThreatIncConfig.log("Order issued: " + faction.getId() + " " + o.task() + " from "
				+ base.getName());
		return o;
	}

	/**
	 * Puts an EXISTING fleet on the hunt in the hive system for softenDays
	 * (the board's per-fleet Hunt on an expedition, task force or fleet going
	 * home - Intercept until 2026-09-24): no new hull is built and nothing is
	 * charged - the fleet was paid for when it sailed - it is simply
	 * retasked, and goes home to {@code base} on the tracked leg when the
	 * order ends or is recalled.
	 */
	public static Order adoptHunt(CampaignFleetAPI fleet, FactionAPI faction,
			StarSystemAPI hive, MarketAPI base) {
		if (fleet == null || !fleet.isAlive() || faction == null || hive == null) return null;
		// a convoy - an evacuation carrying a front's marines among them - never hunts
		if (ThreatConvoys.isConvoy(fleet)) return null;
		if (base == null) base = pickBase(faction, hive.getLocation());
		if (base == null || base.getPrimaryEntity() == null) return null;
		MarketAPI target = ThreatSoftening.huntTarget(ThreatIntel.observerOf(faction), hive.getId());
		if (target == null || target.getPrimaryEntity() == null) return null;
		float days = ThreatIncConfig.softenDays();
		fleet.clearAssignments();
		// nothing of its old group or task force moves it any more
		ThreatPurgeFGI.cutLoose(fleet);
		// a hunt has to engage: a Support/Defend leash or a siege's hold-station
		// blinkers left on it, it orbited the garrison and never fought
		fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
		fleet.getMemoryWithoutUpdate().set(ORDER_FLAG, true);
		// its strength from here: the retreat rule reads what it hunts with
		// (the order baseline only - the launch baseline still scales its refund)
		ThreatReturns.rebaseline(fleet);
		// one listener however often it is re-hunted: two would pay each bounty twice
		if (faction.isPlayerFaction()) ThreatSwarmBountyIntel.HunterPay.attach(fleet);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, target.getPrimaryEntity(), days,
				"hunting the swarms over " + target.getName());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(),
				1000f, "returning to " + base.getName());
		Order o = record(fleet, faction, KIND_HUNT, base, target.getId(), target.getName(), days);
		o.systemId = hive.getId();
		notice(faction, "Fleet Detached", "%s is detached to hunt the Defense Swarms in the %s",
				fleet.getName(), hive.getNameWithLowercaseTypeShort())
				.line("For %s days", (int) days).send();
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
		ThreatNotice n = duty != null
				? notice(faction, "Fleet Retasked", "%s breaks off %s to " + orbitVerb(kind) + " %s",
						fleet.getName(), duty, ThreatNotice.market(hive))
				: notice(faction, "Fleet Retasked", "%s moves to " + orbitVerb(kind) + " %s",
						fleet.getName(), ThreatNotice.market(hive));
		n.line("For %s days", (int) days).send();
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
		// one notice per landing, the player's own only: the first of the
		// expedition's fleets to stay says so; the rest (ThreatPurgeFGI.stayOnDefend
		// empties every fleet in the system) and
		// every NPC navy's are progress, and record's log line is all they get
		boolean first = faction.isPlayerFaction() && !hasDefend(faction.getId(), world.getId());
		Order o = record(fleet, faction, KIND_DEFEND, base, world.getId(), world.getName(), 0f);
		if (first) {
			notice(faction, "Fleet Holds Orbit", "%s stays over %s to defend the landing",
					fleet.getName(), ThreatNotice.market(world)).send();
		}
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
			// a relief holding its world until the army is gone is a station too (2026-10-03)
			boolean relief = KIND_GUARD.equals(o.kind) && o.relief && o.indefinite();
			if (!KIND_SUPPORT.equals(o.kind) && !KIND_DEFEND.equals(o.kind) && !relief) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			MarketAPI world = o.targetId != null
					? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (world == null || world.getPrimaryEntity() == null) continue;
			MarketAPI base = o.baseMarketId != null
					? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
			float days = o.indefinite() ? NO_TERM_DAYS : Math.max(1f, o.daysLeft());
			String task = o.raid ? "raiding " + world.getName()
					: relief ? "relieving " + world.getName() : orbitTask(o.kind, world.getName());
			leash(o.fleet, world, days, task, base != null ? base.getPrimaryEntity() : null,
					o.raid ? "Raid" : relief ? "Relief" : orbitName(o.kind));
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
		// riding its aggressive orbit out to the leash: on station, not strayed (LEASH_MULT)
		if (withinLeash(fleet, planet)) return;
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
	 * @param anchor    the target this fleet should be over ({@link #anchorWorld})
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
		if (curr != null && curr.getTarget() == planet) {
			return; // already bound for the world, on our order or the sweep's
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
	 * The one world of an expedition's several its leash is measured against:
	 * the one the raid's sweep has the fleet heading for, else the nearest.
	 * Anchoring a fleet the sweep had sent on to its next target to the one it
	 * left recalled it every poll (a strike at Earth, 9 recalls in 2.6 s).
	 * Anything in another location is further than anything in this one.
	 */
	public static MarketAPI anchorWorld(CampaignFleetAPI fleet, List<MarketAPI> worlds) {
		com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI curr =
				fleet != null ? fleet.getCurrentAssignment() : null;
		if (curr != null && curr.getTarget() != null) {
			for (MarketAPI world : worlds) {
				if (world != null && world.getPrimaryEntity() == curr.getTarget()) return world;
			}
		}
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
		// once a month while nothing changes: run 18 logged 11,000 of these
		ThreatIncConfig.logQuiet("idle:" + fleet.getId(), label + " over " + world.getName() + ": " + fleet.getName()
				+ " at " + (int) fleet.getFleetPoints() + " FP holds the orbit - " + why);
	}

	/** Sends the fleet home (refund on arrival, see ThreatReturns) and forgets the order. */
	public static void recall(Order o) {
		if (o == null) return;
		all().remove(o);
		CampaignFleetAPI fleet = o.fleet;
		if (fleet == null || !fleet.isAlive()) return;
		if (Global.getSector().getPlayerFaction().getId().equals(o.factionId)) {
			ThreatAidCapacity.logHulls("before recall of " + fleet.getName() + " to " + o.baseMarketId);
		}
		ThreatReturns.sendHome(fleet, o.factionId, o.baseMarketId);
		ThreatIncConfig.log("Order recalled: " + o.factionId + " " + o.task());
	}

	/**
	 * A notice on an order, its first line "Your {what}" for the player or
	 * "{Faction} {what}" in the faction's colour otherwise, under the faction's
	 * crest. The caller adds any further lines and sends it.
	 */
	protected static ThreatNotice notice(FactionAPI faction, String title, String what, Object... args) {
		ThreatNotice n = ThreatNotice.titled(title).icon(faction).fleetOp(faction);
		if (faction.isPlayerFaction()) return n.line("Your " + what, args);
		Object[] withWho = new Object[args.length + 1];
		withWho[0] = ThreatNotice.faction(faction);
		System.arraycopy(args, 0, withWho, 1, args.length);
		return n.line("%s " + what, withWho);
	}
}
