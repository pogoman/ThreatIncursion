package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;

/**
 * HUNTING FORCES (2026-09-24, docs/strategy-layer.md "Hunting forces"): a
 * mobilised NPC faction softens a bountied hive (ThreatSwarmBountyIntel)
 * with its own ships. For each hive system with a running swarm bounty, every
 * mobilised NPC faction's nearest base in reach (ThreatFleetOrders.pickBase)
 * raises a hunting force - unless that base has a hive of its own to siege
 * (IncursionManager.hasSiegeableHive): the siege always comes first.
 *
 * <p>A hunting force carries no marines and lands nothing, so every point is a
 * warship: it is sized to beat the whole system's Defense Swarms
 * (softenMargin), up to softenMaxFP, split into fleets of at most
 * softenFleetFP, and paid for as a siege is, in fuel and supplies. With
 * softenPool every base of the faction in reach chips in, nearest first. The
 * odds are counted on the warships the yards actually build (vanilla scales an
 * NPC fleet by its market), and a force that cannot beat the weakest colony's
 * garrison by the margin does not sail.
 *
 * <p>The fleets MUSTER (2026-09-24 review): each flies blinkered to the hive
 * system's hyperspace anchor and waits there until the whole force is in, or
 * softenMusterDays after the first arrival, then all go in together. Before
 * this every fleet flew on its own and met the whole garrison alone - 69 of a
 * 21-month run's hunting fleets broke off badly hurt. The force works the
 * system's colonies weakest garrison first, moves on only while what is left
 * of it still beats the next garrison by the margin, and goes home whole when
 * it falls below softenRetreatStrength of its strength when it went in or last
 * moved on. The player's Hunt (no force) keeps the single-fleet rules. One
 * force per faction per system; a base waits softenIntervalDays after sending
 * fleets to one.
 *
 * <p>{@link #tick} raises forces on the strategy tick; {@link #advanceHunts}
 * runs on the fleet-order poll (every half day) - on the monthly tick a
 * battered hunt could fight on for 30 days before anything looked.
 */
public class ThreatSoftening {

	public static final String KEY_LAST = "threatinc_softenLast";
	public static final String KEY_FORCES = "threatinc_huntForces";
	/** Most fleets one force is built from. */
	protected static final int MAX_FLEETS = 30;
	/** How close to its muster point a fleet counts as mustered. */
	protected static final float MUSTER_RANGE = 1500f;
	/** How far from the hive system's hyperspace anchor each faction's muster point lies. */
	protected static final float MUSTER_OFFSET = 3000f;
	/** How close to its target the lead fleet takes the force's blinkers off. */
	protected static final float CLOSE_RANGE = 2500f;

	/** One NPC hunting force: the fleets of every hunt order carrying its id. */
	public static class Force {
		public String id;
		public String factionId;
		public String systemId;
		/** Gathered and sent in; false while its fleets muster. */
		public boolean engaged;
		public long launchedTimestamp;
		/** When the first fleet reached the muster point; 0 before. */
		public long firstArrivalTimestamp;
		/** Warship FP when it went in or last moved on - what "badly hurt" is measured from. */
		public float baseFP;
		/** The muster point in hyperspace; 0,0 on forces from before it was recorded (the anchor). */
		public float musterX, musterY;
		/** The fleet the rest FOLLOW in (the slowest), so the force arrives as one. */
		public String leadFleetId;
		/** The lead is over its target and has its blinkers off (the followers keep theirs). */
		public boolean close;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Force> forces() {
		Object val = Global.getSector().getPersistentData().get(KEY_FORCES);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Force>();
			Global.getSector().getPersistentData().put(KEY_FORCES, val);
		}
		return (Map<String, Force>) val;
	}

	/** Base market id -> when it last sent fleets to a hunting force. */
	public static Map<String, Long> lastSent() {
		return ThreatIncData.map(KEY_LAST);
	}

	/** Whether the order's fleet is still mustering with its force. */
	public static boolean mustering(ThreatFleetOrders.Order o) {
		if (o == null || o.forceId == null || Global.getSector() == null) return false;
		Force f = forces().get(o.forceId);
		return f != null && !f.engaged;
	}

	public static void tick(Random random) {
		if (!ThreatIncConfig.softenEnabled()) return;
		List<ThreatSwarmBountyIntel> bounties = ThreatSwarmBountyIntel.running();
		if (bounties.isEmpty()) return;
		Collections.shuffle(bounties, random);
		for (ThreatSwarmBountyIntel bounty : bounties) {
			StarSystemAPI system = bounty.getSystemId() != null
					? Global.getSector().getStarSystem(bounty.getSystemId()) : null;
			if (system == null) continue;
			for (String factionId : ThreatWarState.warFactionIds()) {
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue;
				if (hunting(factionId, system.getId())) continue;
				MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
				if (base == null || resting(base)) continue;
				if (IncursionManager.hasSiegeableHive(base)) continue;
				send(faction, base, system);
			}
		}
	}

	/** Whether the base sent fleets to a hunting force less than softenIntervalDays ago. */
	protected static boolean resting(MarketAPI base) {
		Long last = lastSent().get(base.getId());
		return last != null && Global.getSector().getClock().getElapsedDaysSince(last)
				< ThreatIncConfig.softenIntervalDays();
	}

	/** Whether the faction already has a hunting force in the system. */
	protected static boolean hunting(String factionId, String systemId) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind) || !factionId.equals(o.factionId)) continue;
			if (systemId.equals(huntSystemId(o))) return true;
		}
		return false;
	}

	/**
	 * The hive system a hunt works: recorded on the order, so it survives the
	 * target colony leaving the economy; an order from before it was recorded
	 * reads its target colony's system while that colony stands.
	 */
	protected static String huntSystemId(ThreatFleetOrders.Order o) {
		if (o.systemId != null) return o.systemId;
		MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		return hive != null && hive.getStarSystem() != null ? hive.getStarSystem().getId() : null;
	}

	/** Defense Swarm points over one colony. */
	protected static float garrisonFP(MarketAPI hive) {
		return IncursionManager.siegeOrbitFP(Collections.singletonList(hive));
	}

	/** Where a hunt in this system starts: the colony with the weakest standing garrison, or null when no colony has one. */
	public static MarketAPI huntTarget(String systemId) {
		return weakest(systemId);
	}

	/** The colony of the system with the weakest standing garrison, or null when none has one. */
	protected static MarketAPI weakest(String systemId) {
		MarketAPI best = null;
		float bestFP = Float.MAX_VALUE;
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (hive.getPrimaryEntity() == null) continue;
			float fp = garrisonFP(hive);
			if (fp <= 0f) continue;
			if (fp < bestFP) {
				bestFP = fp;
				best = hive;
			}
		}
		return best;
	}

	/** Combat points the base's reserve can pay a force to this system for (fuel for the distance, supplies for the hulls). */
	protected static float payableFP(MarketAPI base, StarSystemAPI system) {
		float dist = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
		float fuelPerPoint = dist * ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float points = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) points = Math.min(points, ThreatReserves.available(base, Commodities.FUEL) / fuelPerPoint);
		if (suppliesPerPoint > 0f) {
			points = Math.min(points, ThreatReserves.available(base, Commodities.SUPPLIES) / suppliesPerPoint);
		}
		return points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
	}

	/** A fleet built below this share of the points asked was pruned: see {@link #FLEET_CAP}. */
	protected static final float BUILT_SHORT = 0.8f;
	/** Faction id -> the biggest fleet its yards were seen to build whole; not saved, relearned after a load. */
	protected static final Map<String, Float> FLEET_CAP = new java.util.HashMap<String, Float>();

	protected static float fleetCap(String factionId) {
		Float cap = FLEET_CAP.get(factionId);
		return cap != null ? cap : Float.MAX_VALUE;
	}

	/** Returns the share of a fleet's provisions the points it lost to pruning were drawn for. */
	protected static void refundShort(CampaignFleetAPI fleet, MarketAPI base, float frac) {
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		String[][] keys = { { ThreatReturns.MEM_FUEL, Commodities.FUEL }, { ThreatReturns.MEM_SUPPLIES, Commodities.SUPPLIES } };
		for (String[] k : keys) {
			if (!mem.contains(k[0])) continue;
			float drawn = mem.getFloat(k[0]);
			float back = drawn * frac;
			ThreatReserves.deposit(base.getId(), k[1], back);
			mem.set(k[0], drawn - back);
		}
	}

	/** Vanilla's fleet-size multiplier at the base: what FleetFactoryV3 scales an NPC fleet's points by. */
	protected static float sizeMult(MarketAPI base) {
		return Math.max(0f, base.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT).computeEffective(0f));
	}

	/** A fleet's warship points - the freighters and tankers it sails with fight nothing. */
	public static float combatFP(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		float fp = 0f;
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (!m.isCivilian()) fp += m.getFleetPointCost();
		}
		return fp;
	}

	/** The bases a force draws on: the nearest, then (softenPool) every other base in reach free to, nearest first. */
	protected static List<MarketAPI> contributors(FactionAPI faction, MarketAPI primary, StarSystemAPI system) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		result.add(primary);
		if (!ThreatIncConfig.softenPool()) return result;
		final Vector2f loc = system.getLocation();
		List<MarketAPI> others = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m == primary || m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			if (Misc.getDistanceLY(m.getStarSystem().getLocation(), loc) > IncursionManager.expeditionRangeLY(m)) continue;
			if (resting(m) || IncursionManager.hasSiegeableHive(m)) continue;
			others.add(m);
		}
		Collections.sort(others, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), loc),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), loc));
			}
		});
		result.addAll(others);
		return result;
	}

	/** Raises a hunting force against the system; true when one sailed for the muster. */
	protected static boolean send(FactionAPI faction, MarketAPI base, StarSystemAPI system) {
		MarketAPI first = weakest(system.getId());
		if (first == null) return false;
		String key = "huntwait:" + faction.getId() + ":" + system.getId();
		float margin = Math.max(0f, ThreatIncConfig.softenMargin());
		// the swarms reinforce while the force gathers (433 -> 1,329 FP over Zendar, Run 6):
		// it sails with headroom over what the muster will ask of it
		float floor = garrisonFP(first) * margin * Math.max(1f, ThreatIncConfig.softenHeadroom());
		float max = ThreatIncConfig.softenMaxFP();
		if (floor > max) {
			ThreatIncConfig.logQuiet(key, "Hunting force stays at " + base.getName() + ": " + first.getName()
					+ "'s swarms need " + (int) floor + " FP, above softenMaxFP");
			return false;
		}
		float want = IncursionManager.siegeOrbitFP(IncursionManager.collectSiegeTargets(null, system)) * margin;
		want = Math.max(floor, Math.min(want, max));
		List<MarketAPI> bases = contributors(faction, base, system);
		// what the yards would build for what the depots can pay
		float builds = 0f;
		for (MarketAPI b : bases) builds += payableFP(b, system) * sizeMult(b);
		if (builds < floor) {
			ThreatIncConfig.logQuiet(key, "Hunting force waits at " + base.getName() + ": " + bases.size()
					+ (bases.size() == 1 ? " base pays for " : " bases pay for ") + (int) builds + " FP, " + first.getName() + "'s swarms need " + (int) floor);
			return false;
		}

		long now = Global.getSector().getClock().getTimestamp();
		Force force = new Force();
		force.id = faction.getId() + ":" + system.getId() + ":" + now;
		force.factionId = faction.getId();
		force.systemId = system.getId();
		force.launchedTimestamp = now;
		SectorEntityToken muster;
		if (base.getStarSystem() == system) {
			muster = base.getPrimaryEntity();
		} else {
			Vector2f at = musterPoint(system, faction.getId());
			force.musterX = at.x;
			force.musterY = at.y;
			muster = Global.getSector().getHyperspace().createToken(at.x, at.y);
		}
		float perFleet = Math.max(50f, ThreatIncConfig.softenFleetFP());
		float built = 0f;
		List<ThreatFleetOrders.Order> sent = new ArrayList<ThreatFleetOrders.Order>();
		List<String> from = new ArrayList<String>();
		for (MarketAPI b : bases) {
			if (built >= want || sent.size() >= MAX_FLEETS) break;
			float mult = sizeMult(b);
			if (mult <= 0f) continue;
			// asked in the points the depot pays for, sized so what is built is at most perFleet
			float budget = payableFP(b, system);
			boolean any = false;
			while (built < want && sent.size() < MAX_FLEETS) {
				float size = Math.min(perFleet, fleetCap(faction.getId()));
				float ask = Math.min(Math.min(size, want - built) / mult, budget);
				// a remainder under the smallest fleet is rounded up, not dropped: dropped,
				// the force came in a few points under its floor and folded (9430 of 9434)
				if (ask < 25f) ask = Math.min(25f, budget);
				if (ask < 25f) break;
				ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchHunt(faction, b, first, ask, force.id, muster);
				if (o == null) break;
				float got = combatFP(o.fleet);
				float share = Math.min(1f, got / Math.max(1f, ask * mult));
				if (share < BUILT_SHORT) {
					// vanilla prunes a fleet to maxShipsInAIFleet: a navy without the
					// big hulls to hold the points loses them (Nortia built 304 FP of
					// a 1,683 ask). Pay only for what was built, ask smaller from now.
					refundShort(o.fleet, b, 1f - share);
					FLEET_CAP.put(faction.getId(), Math.max(100f, got * 1.1f));
				}
				budget -= ask * share;
				built += got;
				sent.add(o);
				any = true;
			}
			if (any) {
				lastSent().put(b.getId(), now);
				from.add(b.getName());
			}
		}
		if (sent.isEmpty()) return false;
		if (built < floor) {
			// the yards built short of the numbers: back into the depot, provisions refunded
			// in full - the fleets never sailed, so the return rule's refund cut does not apply
			float backFuel = 0f, backSupplies = 0f;
			for (ThreatFleetOrders.Order o : sent) {
				MarketAPI home = baseOf(o);
				if (home != null && o.fleet != null) {
					backFuel += o.fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_FUEL);
					backSupplies += o.fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_SUPPLIES);
					refundShort(o.fleet, home, 1f);
				}
				ThreatFleetOrders.fold(o, home);
			}
			ThreatIncConfig.log("Hunting force from " + base.getName() + " built " + (int) built + " FP of the "
					+ (int) floor + " " + first.getName() + "'s swarms need - stood down, " + (int) backFuel
					+ " fuel and " + (int) backSupplies + " supplies back in the depots");
			return false;
		}
		forces().put(force.id, force);
		ThreatColonyManager.announceAlways(Misc.ucFirst(faction.getDisplayNameWithArticle())
				+ " is mustering a hunting force against the Defense Swarms in the "
				+ system.getNameWithLowercaseTypeShort() + ".", Misc.getHighlightColor());
		ThreatIncConfig.log("Hunting force from " + Misc.getAndJoined(from) + " to " + system.getName() + ": "
				+ sent.size() + " fleets (" + shape(sent) + "), " + (int) built + " FP built (wanted " + (int) want + ", pays for "
				+ (int) builds + ") against " + first.getName() + " first (" + (int) garrisonFP(first)
				+ " FP), mustering");
		return true;
	}

	/** How close a follower must be to its lead to be merged into it. */
	protected static final float MERGE_RANGE = 1000f;

	/**
	 * Folds a follower into the force's lead fleet (softenMerge): its ships,
	 * and what it was provisioned with and launched at, so the one fleet is
	 * refunded and judged as the two were. Vanilla builds an AI fleet to at
	 * most 30 ships - 250-500 FP for most navies - and its AI never keeps
	 * separate fleets together in a fight: every battle of a 16-fleet force
	 * over Alpha Novy Tayvay I was one fleet against an 876-1,037 FP swarm.
	 * The cap prunes only at spawn, so the gathered force sails as one fleet.
	 * The merged force goes home to the lead's base, which takes the refund.
	 */
	protected static boolean mergeInto(ThreatFleetOrders.Order lead, ThreatFleetOrders.Order o) {
		CampaignFleetAPI to = lead.fleet, from = o.fleet;
		if (from.getContainingLocation() != to.getContainingLocation()) return false;
		if (Misc.getDistance(from, to) > MERGE_RANGE) return false;
		if (from.getBattle() != null || to.getBattle() != null) return false;
		for (FleetMemberAPI m : from.getFleetData().getMembersListCopy()) {
			from.getFleetData().removeFleetMember(m);
			m.setFlagship(false);
			to.getFleetData().addFleetMember(m);
		}
		com.fs.starfarer.api.campaign.rules.MemoryAPI a = to.getMemoryWithoutUpdate(), b = from.getMemoryWithoutUpdate();
		for (String k : new String[] { ThreatReturns.MEM_FUEL, ThreatReturns.MEM_SUPPLIES, ThreatReturns.MEM_FP0,
				ThreatReturns.MEM_FP_ORDER }) {
			if (!a.contains(k) && !b.contains(k)) continue;
			a.set(k, (a.contains(k) ? a.getFloat(k) : 0f) + (b.contains(k) ? b.getFloat(k) : 0f));
		}
		to.getFleetData().sort();
		to.getFleetData().setSyncNeeded();
		to.getFleetData().syncIfNeeded();
		to.forceSync();
		ThreatFleetOrders.all().remove(o);
		from.despawn();
		ThreatIncConfig.log("Hunting fleet merged into the lead over " + o.targetName + ": now "
				+ to.getFleetData().getNumMembers() + " ships, " + (int) combatFP(to) + " FP");
		return true;
	}

	/** "250-1480 FP, up to 30 ships": what the yards built, for the log. */
	protected static String shape(List<ThreatFleetOrders.Order> sent) {
		int lo = Integer.MAX_VALUE, hi = 0, ships = 0;
		for (ThreatFleetOrders.Order o : sent) {
			int fp = (int) combatFP(o.fleet);
			lo = Math.min(lo, fp);
			hi = Math.max(hi, fp);
			ships = Math.max(ships, o.fleet.getFleetData().getNumMembers());
		}
		return lo + "-" + hi + " FP, up to " + ships + " ships";
	}

	/**
	 * Forces muster, go in, move on and go home together; a hunt with no force
	 * (the player's, or one from an older save) keeps the single-fleet rules.
	 */
	public static void advanceHunts() {
		if (ThreatFleetOrders.all().isEmpty() && forces().isEmpty()) return;
		Map<String, List<ThreatFleetOrders.Order>> byForce = new LinkedHashMap<String, List<ThreatFleetOrders.Order>>();
		for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			logBattle(o);
			if (o.forceId == null || !forces().containsKey(o.forceId)) {
				advanceSingle(o);
				continue;
			}
			List<ThreatFleetOrders.Order> list = byForce.get(o.forceId);
			if (list == null) {
				list = new ArrayList<ThreatFleetOrders.Order>();
				byForce.put(o.forceId, list);
			}
			list.add(o);
		}
		// a force with no fleet left on the books is over
		for (Iterator<String> it = forces().keySet().iterator(); it.hasNext();) {
			if (!byForce.containsKey(it.next())) it.remove();
		}
		for (Map.Entry<String, List<ThreatFleetOrders.Order>> e : byForce.entrySet()) {
			advanceForce(forces().get(e.getKey()), e.getValue());
		}
	}

	/** Battles already logged; not saved. */
	protected static final Map<Object, Boolean> LOGGED_BATTLES = new java.util.WeakHashMap<Object, Boolean>();

	/**
	 * What a hunting fleet actually fights: every fleet on the other side of its
	 * battle, once per battle, with the colony it garrisons and how far it is
	 * from the hunt's target - the garrison figure the odds are read from
	 * counts one colony's swarms only.
	 */
	protected static void logBattle(ThreatFleetOrders.Order o) {
		com.fs.starfarer.api.campaign.BattleAPI battle = o.fleet.getBattle();
		if (battle == null || !ThreatIncConfig.debugLogging() || LOGGED_BATTLES.containsKey(battle)) return;
		LOGGED_BATTLES.put(battle, Boolean.TRUE);
		MarketAPI target = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		SectorEntityToken planet = target != null ? target.getPrimaryEntity() : null;
		float ours = 0f;
		for (CampaignFleetAPI f : battle.getSideFor(o.fleet)) ours += combatFP(f);
		StringBuilder them = new StringBuilder();
		float theirs = 0f;
		for (CampaignFleetAPI f : battle.getOtherSideFor(o.fleet)) {
			float fp = f.getFleetPoints();
			theirs += fp;
			String home = f.getMemoryWithoutUpdate().getString(ThreatColonyManager.GARRISON_FLAG);
			MarketAPI homeMarket = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
			int dist = planet != null && f.getContainingLocation() == planet.getContainingLocation()
					? (int) Misc.getDistance(f, planet) : -1;
			them.append(them.length() > 0 ? "; " : "").append(f.getName()).append(f.isStationMode() ? " [station]" : "")
					.append(homeMarket != null ? " of " + homeMarket.getName() : "")
					.append(" ").append((int) fp).append(" FP @").append(dist);
		}
		ThreatIncConfig.log("Hunt battle near " + o.targetName + ": " + o.factionId + " side " + battle.getSideFor(o.fleet).size() + " fleets " + (int) ours
				+ " FP vs " + (int) theirs + " FP (" + (target != null ? (int) garrisonFP(target) : 0)
				+ " counted as the garrison): " + them);
	}

	protected static void advanceForce(Force f, List<ThreatFleetOrders.Order> orders) {
		StarSystemAPI system = f.systemId != null ? Global.getSector().getStarSystem(f.systemId) : null;
		if (system == null) {
			standDownAll(f, orders, "the system is gone");
			return;
		}
		CampaignClockAPI clock = Global.getSector().getClock();
		float margin = Math.max(0f, ThreatIncConfig.softenMargin());
		float fp = 0f;
		for (ThreatFleetOrders.Order o : orders) fp += combatFP(o.fleet);

		if (!f.engaged) {
			int present = 0;
			float presentFP = 0f;
			for (ThreatFleetOrders.Order o : orders) {
				// the hunt's term starts when it goes in, not while it gathers
				o.issuedTimestamp = clock.getTimestamp();
				if (atMuster(o, system)) {
					present++;
					presentFP += combatFP(o.fleet);
				}
			}
			if (present > 0 && f.firstArrivalTimestamp == 0L) f.firstArrivalTimestamp = clock.getTimestamp();
			if (present == 0 && clock.getElapsedDaysSince(f.launchedTimestamp) >= ThreatIncConfig.softenDays()) {
				standDownAll(f, orders, "never mustered");
				return;
			}
			boolean all = present == orders.size();
			boolean waited = f.firstArrivalTimestamp != 0L
					&& clock.getElapsedDaysSince(f.firstArrivalTimestamp) >= ThreatIncConfig.softenMusterDays();
			if (!all && !waited) return;
			MarketAPI target = weakest(f.systemId);
			if (target == null) {
				standDownAll(f, orders, "the swarms are gone");
				return;
			}
			float need = garrisonFP(target) * margin;
			// the stragglers would carry it: wait for them, up to three muster spells
			if (presentFP < need && fp >= need && clock.getElapsedDaysSince(f.firstArrivalTimestamp)
					< 3f * ThreatIncConfig.softenMusterDays()) {
				return;
			}
			if (presentFP < need) {
				standDownAll(f, orders, "outmatched at the muster, " + (int) presentFP + " FP against "
						+ (int) need + " over " + target.getName());
				return;
			}
			f.engaged = true;
			f.baseFP = fp;
			sendIn(f, orders, target);
			ThreatIncConfig.log("Hunting force " + f.factionId + " goes in over " + target.getName() + ": "
					+ present + "/" + orders.size() + " fleets mustered, " + (int) presentFP + " FP against "
					+ (int) garrisonFP(target));
			return;
		}

		if (f.baseFP > 0f && fp / f.baseFP < ThreatIncConfig.softenRetreatStrength()) {
			standDownAll(f, orders, "badly hurt, " + (int) fp + " of " + (int) f.baseFP + " FP");
			return;
		}
		ThreatFleetOrders.Order lead = null;
		for (ThreatFleetOrders.Order o : orders) {
			if (o.fleet.getId().equals(f.leadFleetId)) lead = o;
		}
		MarketAPI hive = orders.get(0).targetId != null
				? Global.getSector().getEconomy().getMarket(orders.get(0).targetId) : null;
		// the lead is gone (or a force from before leads): the slowest left leads on
		if (lead == null && hive != null && hive.getPrimaryEntity() != null) {
			sendIn(f, orders, hive);
			return;
		}
		if (lead != null && ThreatIncConfig.softenMerge()) {
			for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(orders)) {
				if (o != lead && mergeInto(lead, o)) orders.remove(o);
			}
		}
		if (!f.close && lead != null && hive != null && hive.getPrimaryEntity() != null
				&& lead.fleet.getContainingLocation() == hive.getPrimaryEntity().getContainingLocation()
				&& Misc.getDistance(lead.fleet, hive.getPrimaryEntity()) <= CLOSE_RANGE) {
			f.close = true;
			// the lead picks the fights; the rest stay blinkered on its heels.
			// Unblinkered, each follower chased a swarm of its own and fought
			// it alone (16 fleets over Alpha Novy Tayvay I, one per battle)
			lead.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			ThreatIncConfig.log("Hunting force " + f.factionId + " closes on " + hive.getName() + ": "
					+ orders.size() + " fleets, " + (int) fp + " FP against " + (int) garrisonFP(hive));
		}
		if (hive != null && isHive(hive) && garrisonFP(hive) > 0f) return;
		MarketAPI next = weakest(f.systemId);
		if (next == null) {
			standDownAll(f, orders, "the swarms are gone");
			return;
		}
		float need = garrisonFP(next) * margin;
		if (fp < need) {
			standDownAll(f, orders, "outmatched by " + next.getName() + ", " + (int) fp + " FP against " + (int) need);
			return;
		}
		f.baseFP = fp;
		IncursionManager.huntThinned(f.systemId);
		sendIn(f, orders, next);
		ThreatIncConfig.log("Hunting force " + f.factionId + " moves on to " + next.getName() + " with "
				+ (int) fp + " FP against " + (int) garrisonFP(next));
	}

	/**
	 * Where a faction's forces muster outside a hive system: off its hyperspace
	 * anchor, each faction on its own bearing. One shared point had hostile
	 * factions' forces fighting each other there (a Diktat force against a
	 * League one, both hunting Isirah).
	 */
	protected static Vector2f musterPoint(StarSystemAPI system, String factionId) {
		List<String> ids = new ArrayList<String>(ThreatWarState.warFactionIds());
		Collections.sort(ids);
		int slot = Math.max(0, ids.indexOf(factionId));
		float angle = 360f * slot / Math.max(1, ids.size());
		Vector2f dir = Misc.getUnitVectorAtDegreeAngle(angle);
		Vector2f at = system.getLocation();
		return new Vector2f(at.x + dir.x * MUSTER_OFFSET, at.y + dir.y * MUSTER_OFFSET);
	}

	/** At the force's muster point: in hyperspace near it, or in the system for a base inside it. */
	protected static boolean atMuster(ThreatFleetOrders.Order o, StarSystemAPI system) {
		MarketAPI base = baseOf(o);
		if (base != null && base.getStarSystem() == system) return o.fleet.getContainingLocation() == system;
		if (!o.fleet.isInHyperspace()) return false;
		Force f = forces().get(o.forceId);
		Vector2f at = f != null && (f.musterX != 0f || f.musterY != 0f) ? new Vector2f(f.musterX, f.musterY)
				: system.getLocation();
		return Misc.getDistance(o.fleet.getLocation(), at) <= MUSTER_RANGE;
	}

	/**
	 * Sends the force at a colony AS ONE: the slowest fleet leads with the hunt
	 * orders, the rest FOLLOW it, and all stay blinkered until the lead is over
	 * the target. Sent in each on its own heading, the fleets strung out by burn
	 * speed and route and fought the garrisons they passed alone (999 FP of
	 * Independents met Fjalar's 746 FP with 271 FP of their own, 5,000 units
	 * short of Corb).
	 */
	protected static void sendIn(Force f, List<ThreatFleetOrders.Order> orders, MarketAPI target) {
		ThreatFleetOrders.Order lead = null;
		float slowest = Float.MAX_VALUE;
		for (ThreatFleetOrders.Order o : orders) {
			float burn = o.fleet.getFleetData().getMinBurnLevel();
			if (burn < slowest) {
				slowest = burn;
				lead = o;
			}
		}
		if (lead == null) return;
		f.leadFleetId = lead.fleet.getId();
		f.close = false;
		for (ThreatFleetOrders.Order o : orders) {
			o.targetId = target.getId();
			o.targetName = target.getName();
			// en route again until it is over the colony (the board reads arrived)
			if (o.arrived) o.credited = true;
			o.arrived = false;
			MarketAPI base = baseOf(o);
			if (o == lead) {
				ThreatFleetOrders.engageHunt(o.fleet, base, target, o.daysLeft());
			} else {
				o.fleet.clearAssignments();
				o.fleet.addAssignment(FleetAssignment.FOLLOW, lead.fleet, Math.max(1f, o.daysLeft()),
						"hunting the swarms over " + target.getName());
				if (base != null && base.getPrimaryEntity() != null) {
					o.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, base.getPrimaryEntity(), 1000f,
							"returning to " + base.getName());
				}
			}
			o.fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_IGNORES_OTHER_FLEETS, true);
		}
	}

	protected static MarketAPI baseOf(ThreatFleetOrders.Order o) {
		return o.baseMarketId != null ? Global.getSector().getEconomy().getMarket(o.baseMarketId) : null;
	}

	protected static void standDownAll(Force f, List<ThreatFleetOrders.Order> orders, String why) {
		forces().remove(f.id);
		// a force that fought leaves the swarms thinner than the monthly siege tick will find them
		if (f.engaged) IncursionManager.huntThinned(f.systemId);
		for (ThreatFleetOrders.Order o : orders) {
			o.fleet.getMemoryWithoutUpdate().unset(MemFlags.FLEET_IGNORES_OTHER_FLEETS);
			// a force that never went in spent nothing: provisions back in full now, so the
			// return rule's refund cut finds nothing left to cut
			MarketAPI home = baseOf(o);
			if (!f.engaged && home != null) refundShort(o.fleet, home, 1f);
			ThreatFleetOrders.standDown(o, why);
		}
	}

	/**
	 * A hunt with no force: moves on when its colony's swarms are gone (an NPC
	 * one only while it still beats the next garrison by the margin), and goes
	 * home when the whole system is clear or it is badly hurt.
	 */
	protected static void advanceSingle(ThreatFleetOrders.Order o) {
		boolean player = Global.getSector().getPlayerFaction().getId().equals(o.factionId);
		if (ThreatReturns.orderHealth(o.fleet) < ThreatIncConfig.softenRetreatStrength()) {
			if (player) {
				ThreatColonyManager.announceAlways(o.fleet.getName() + " is badly hurt and breaks off "
						+ "the hunt over " + o.targetName + ".", Misc.getNegativeHighlightColor());
			}
			ThreatFleetOrders.standDown(o, "badly hurt");
			return;
		}
		MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		if (hive != null && isHive(hive) && garrisonFP(hive) > 0f) return;
		// the system from the order: a colony a siege destroyed meanwhile
		// is out of the economy, and its siblings may still field swarms
		String systemId = huntSystemId(o);
		MarketAPI next = systemId != null ? weakest(systemId) : null;
		if (next == null) {
			if (player) {
				ThreatColonyManager.announceAlways(o.fleet.getName() + " has hunted down the Defense "
						+ "Swarms and is heading home.", Misc.getHighlightColor());
			}
			ThreatFleetOrders.standDown(o, "the swarms are gone");
			IncursionManager.huntThinned(systemId);
			return;
		}
		IncursionManager.huntThinned(systemId);
		if (!player && combatFP(o.fleet) < garrisonFP(next) * Math.max(0f, ThreatIncConfig.softenMargin())) {
			ThreatFleetOrders.standDown(o, "outmatched by " + next.getName());
			return;
		}
		if (player) {
			ThreatColonyManager.announceAlways(o.fleet.getName() + " moves on to hunt the swarms "
					+ "over " + next.getName() + ".", Misc.getHighlightColor());
		}
		ThreatFleetOrders.retargetHunt(o, next);
		ThreatIncConfig.log("Hunting fleet moves on to " + next.getName());
	}

	protected static boolean isHive(MarketAPI market) {
		return Factions.THREAT.equals(market.getFactionId());
	}
}
