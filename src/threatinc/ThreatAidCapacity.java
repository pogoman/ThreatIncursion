package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.util.Misc;

/**
 * CAPACITY LEDGER (docs/player-aid.md section 2): what one of the player's
 * colonies can have at sea at once, in fleet points.
 *
 * <p>Capacity is the colony's vanilla fleet-size stat - the "Fleet size %"
 * line on the colony screen (colony size, doctrine, ship-hull shortage,
 * stability) - times aidBaseFP. Every fleet the player launches from the
 * colony through the war board costs points: the escort's combat FP plus the
 * freighter, tanker and transport hulls its cargo needs. Points come back when
 * the fleet is home ({@link ThreatReturns#settle}); a fleet that is lost frees
 * them only after aidRebuildDays. A purge expedition is a fleet group rather
 * than one fleet: its points are held until the group ends.
 *
 * <p>STAGING (2026-09-05): a task force guarding one of the player's own
 * colonies is staged there ({@link ThreatFleetOrders#stagedAt}). Its points
 * stay charged to the colony that sent it, but while it is on station they
 * count in the host's pool ({@link #freeFP}), and a combat sortie from the
 * host folds staged task forces in when its own points fall short
 * ({@link #commitSortie}, {@link #foldStaged}): the folded fleet leaves the
 * sector and its ledger entry rides the sortie, marked with the host so the
 * ships go back on station there when the sortie is home
 * ({@link ThreatFleetOrders#restation}). Convoys never fold warships in: they
 * size by {@link #ownFreeFP}. No order over-extends a colony: what the pool
 * cannot cover is refused ({@link IncursionManager#siegeBlockReason}).
 *
 * <p>Money never buys capability: a colony that cannot field the hulls cannot
 * send the cargo, whatever the wallet holds. NPC factions are not on the
 * ledger - their navies are their own, sized by vanilla.
 */
public class ThreatAidCapacity {

	public static final String KEY = "threatinc_aidCapacity";

	/** Support hulls a task force sails with, as a multiple of its combat points (see ThreatFleetOrders.buildTaskForce). */
	public static final float TASK_FORCE_HULL_MULT = 1.2f;

	/** One fleet (or fleet group) holding points against a colony. */
	public static class Commitment {
		public String marketId;
		public float fp;
		public CampaignFleetAPI fleet;
		/** A FleetGroupIntel (purge expedition) when the commitment is a group, else null. */
		public Object group;
		public String label;
		/** When the fleet was seen dead; 0 while it lives. */
		public long lostTimestamp;
		/**
		 * Set while the ships are folded into a sortie from another colony of
		 * the player's: the colony they were staged at, where they go back on
		 * station when that sortie is home. Null when the points are simply
		 * the sender's again on return.
		 */
		public String hostMarketId;
		/**
		 * Live fleet points the ships had when they folded into that sortie -
		 * what the host counted and what goes back on station (times the
		 * sortie's surviving fraction). The sender's charge (fp) stays what
		 * sailed from it. 0 when never folded.
		 */
		public float foldedFP;
	}

	@SuppressWarnings("unchecked")
	public static List<Commitment> all() {
		Object val = Global.getSector().getPersistentData().get(KEY);
		if (!(val instanceof List)) {
			val = new ArrayList<Commitment>();
			Global.getSector().getPersistentData().put(KEY, val);
		}
		return (List<Commitment>) val;
	}

	public static boolean enabled() {
		return ThreatWarState.enabled() && (ThreatIncConfig.aidEnabled() || ThreatHulls.enabled());
	}

	/**
	 * Whether the ledger applies to this market's fleets: the player's under
	 * the aid rule; every faction's with the hull pool (ThreatHulls, 2026-10-06).
	 */
	public static boolean applies(MarketAPI market) {
		if (market == null || !enabled()) return false;
		if (ThreatHulls.enabled()) return !Factions.THREAT.equals(market.getFactionId());
		return market.isPlayerOwned() && ThreatIncConfig.aidEnabled();
	}

	// ------------------------------------------------------------------
	// capacity
	// ------------------------------------------------------------------

	/**
	 * Fleet points the colony can have at sea at once; 0 without a military
	 * structure. With the hull pool its share of the faction's hulls - its
	 * standing patrols by vanilla's figures, plus the built navy at the hull
	 * port (ThreatHulls.shareFP, any faction); else the player's aidBaseFP
	 * allowance at the colony's fleet size.
	 */
	public static float capacityFP(MarketAPI market) {
		if (market == null) return 0f;
		if (ThreatHulls.enabled()) return applies(market) ? ThreatHulls.shareFP(market) : 0f;
		if (!market.isPlayerOwned()) return 0f;
		if (!IncursionManager.isBase(market)) return 0f;
		return ThreatIncConfig.aidBaseFP() * ThreatColonyManager.fleetSizeMult(market);
	}

	/** Points held by fleets out and by losses still rebuilding. */
	public static float committedFP(String marketId) {
		float sum = 0f;
		for (Commitment c : all()) {
			if (marketId.equals(c.marketId)) sum += c.fp;
		}
		return sum;
	}

	/**
	 * The colony's own points not at sea: its capacity less what it has out.
	 * Under the hull pool its share of the faction's hulls (capacityFP) less
	 * what sailed from it, and never more than the faction has free in all
	 * (lost hulls are the faction's debt, ThreatHulls.freeFP). From 2026-10-06
	 * to 2026-10-11 it was the whole faction's free pool at every colony -
	 * "ships fly themselves to the base that stages them" - which made a
	 * staging base pointless (the user: "whats the point of staging bases
	 * again given it just pools from all bases?"); hulls reach another colony
	 * by a Fleet order to it, and sail from there as staged task forces.
	 */
	public static float ownFreeFP(MarketAPI market) {
		if (market == null) return 0f;
		if (!enabled()) return Float.MAX_VALUE / 4f;
		if (ThreatHulls.enabled()) {
			if (!applies(market)) return 0f;
			float own = capacityFP(market) - committedFP(market.getId());
			// the player's colony: its own hulls less its own out, nothing else's (the user,
			// 2026-10-12; its losses come off its share, ThreatHulls.lostAt). An NPC colony is
			// still capped at its faction's free - lost hulls are the faction's debt there
			if (market.isPlayerOwned()) return Math.max(0f, own);
			return Math.max(0f, Math.min(own, ThreatHulls.freeFP(market.getFactionId())));
		}
		return capacityFP(market) - committedFP(market.getId());
	}

	/**
	 * Points of the task forces staged at the colony - the player's guards on
	 * station over it, whichever colony sent them - at their LIVE strength
	 * ({@link #liveFP}): a fleet that lost ships on the way counts what it
	 * has, not what its sender was charged.
	 */
	public static float stagedFP(MarketAPI market) {
		if (market == null || !enabled()) return 0f;
		float sum = 0f;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.stagedAt(market)) {
			if (heldBy(o.fleet) != null) sum += liveFP(o.fleet);
		}
		return sum;
	}

	/** What a fleet is worth now: vanilla's fleet points of the ships it has (the fleets table's Strength). */
	public static float liveFP(CampaignFleetAPI fleet) {
		if (fleet == null || !fleet.isAlive()) return 0f;
		return Math.max(0f, fleet.getFleetPoints());
	}

	/**
	 * What a combat sortie from the colony can be sized to: its own free
	 * points plus the task forces staged there, which fold into it. Convoys
	 * size by {@link #ownFreeFP} - freighters are not made of warships.
	 */
	public static float freeFP(MarketAPI market) {
		if (market == null) return 0f;
		if (!enabled()) return Float.MAX_VALUE / 4f;
		return ownFreeFP(market) + stagedFP(market);
	}

	/** The ledger entry this fleet holds, or null. */
	public static Commitment heldBy(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		for (Commitment c : all()) {
			if (c.fleet == fleet) return c;
		}
		return null;
	}

	/** Every ledger entry this fleet holds (a restationed fleet can carry several senders'). */
	public static List<Commitment> heldAllBy(CampaignFleetAPI fleet) {
		List<Commitment> result = new ArrayList<Commitment>();
		if (fleet == null) return result;
		for (Commitment c : all()) {
			if (c.fleet == fleet) result.add(c);
		}
		return result;
	}

	/** What a fleet was worth when its group made it real, else what it is worth now. */
	protected static float spawnFP(CampaignFleetAPI fleet) {
		if (fleet == null) return 0f;
		float fp = fleet.getMemoryWithoutUpdate().getFloat(FleetGroupIntel.KEY_SPAWN_FP);
		return fp > 0f ? fp : liveFP(fleet);
	}

	/**
	 * An expedition's fleets are real: its ledger entries - the base's own
	 * points and every task force folded in - are split among them by
	 * spawned strength and ride the fleets from here (2026-09-06). Each
	 * fleet then settles its own share when it is home, recalled, retasked
	 * or lost. Before, the whole group's came back at once when the
	 * expedition ended: phantom guards were built over the host while the
	 * real ships were still sailing home, and ships home later - recalled
	 * or off an intercept - dissolved with no entry to hand back.
	 */
	public static void splitGroup(ThreatPurgeFGI group) {
		if (group == null) return;
		List<Commitment> mine = new ArrayList<Commitment>();
		for (Commitment c : all()) {
			if (c.group == group) mine.add(c);
		}
		if (mine.isEmpty()) return;
		List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		float total = 0f;
		for (CampaignFleetAPI fleet : group.getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			float fp = spawnFP(fleet);
			if (fp <= 0f) continue;
			fleets.add(fleet);
			total += fp;
		}
		if (fleets.isEmpty() || total <= 0f) return;
		for (Commitment c : mine) {
			all().remove(c);
			for (CampaignFleetAPI fleet : fleets) {
				float share = spawnFP(fleet) / total;
				Commitment part = new Commitment();
				part.marketId = c.marketId;
				part.fp = c.fp * share;
				part.fleet = fleet;
				part.label = c.label;
				part.hostMarketId = c.hostMarketId;
				part.foldedFP = c.foldedFP * share;
				all().add(part);
			}
		}
		ThreatIncConfig.log("Capacity: " + mine.size() + " ledger entries of " + mine.get(0).label
				+ " split over " + fleets.size() + " fleets");
	}

	/** A convoy, or a guard over its own colony: the colony's own points only. */
	public static Commitment commit(MarketAPI market, float fp, CampaignFleetAPI fleet, String label) {
		return commit(market, fp, fleet, null, label, false);
	}

	/**
	 * {fuel, supplies} the task forces staged at the host bring to a sortie of {@code points}
	 * whole-fleet points from it ({@link ThreatReturns#carried} of the forces {@link #stagedToFold}
	 * picks for what the colony's own free points do not cover): the sortie draws that much less.
	 */
	public static float[] foldedProvisions(MarketAPI host, float points) {
		float[] sum = new float[] {0f, 0f};
		if (host == null || !enabled()) return sum;
		float need = points - Math.max(0f, ownFreeFP(host));
		if (need <= 0f) return sum;
		for (ThreatFleetOrders.Order o : stagedToFold(host, need, null)) {
			float[] c = ThreatReturns.carried(o.fleet);
			sum[0] += c[0];
			sum[1] += c[1];
		}
		return sum;
	}

	/**
	 * The task forces staged at the host a sortie short {@code need} points would fold, in order
	 * ({@link #foldStaged}'s pick: the smallest that covers what is left, else the largest), the
	 * fleet {@code into} excluded. Nothing is changed.
	 */
	public static List<ThreatFleetOrders.Order> stagedToFold(MarketAPI host, float need, CampaignFleetAPI into) {
		List<ThreatFleetOrders.Order> picks = new ArrayList<ThreatFleetOrders.Order>();
		List<ThreatFleetOrders.Order> staged = ThreatFleetOrders.stagedAt(host);
		while (need > 0f) {
			ThreatFleetOrders.Order pick = null;
			for (ThreatFleetOrders.Order o : staged) {
				if (o.fleet == into || picks.contains(o)) continue;
				if (heldBy(o.fleet) == null) continue;
				float live = liveFP(o.fleet);
				if (live <= 0f) continue;
				if (pick == null) {
					pick = o;
					continue;
				}
				float pickLive = liveFP(pick.fleet);
				boolean covers = live >= need;
				boolean pickCovers = pickLive >= need;
				if (covers ? (!pickCovers || live < pickLive) : (!pickCovers && live > pickLive)) pick = o;
			}
			if (pick == null) break;
			picks.add(pick);
			need -= liveFP(pick.fleet);
		}
		return picks;
	}

	/** A purge expedition: as a sortie, held until the group ends. */
	public static Commitment commitGroup(MarketAPI market, float fp, Object group, String label) {
		return commit(market, fp, null, group, label, true);
	}

	protected static Commitment commit(MarketAPI market, float fp, CampaignFleetAPI fleet,
			Object group, String label, boolean fold) {
		if (market == null || fp <= 0f || !enabled()) return null;
		float folded = 0f;
		if (fold) {
			folded = foldStaged(market, fp - Math.max(0f, ownFreeFP(market)), fleet, group, label);
		}
		float own = fp - folded;
		if (own <= 0f) return null;
		Commitment c = new Commitment();
		c.marketId = market.getId();
		c.fp = own;
		c.fleet = fleet;
		c.group = group;
		c.label = label;
		all().add(c);
		ThreatIncConfig.log("Capacity: " + market.getName() + " commits " + (int) own + " FP to "
				+ label + " (" + (int) committedFP(market.getId()) + "/" + (int) capacityFP(market) + ")");
		return c;
	}

	/**
	 * Folds task forces staged at the host into a sortie until {@code need}
	 * points are covered: the smallest one that covers what is left, else the
	 * largest - whole fleets, so the last may overshoot. Each folded fleet
	 * leaves the sector ({@link ThreatFleetOrders#fold}) and its ledger entry
	 * rides the sortie, still charged to the colony that sent it and marked
	 * to go back on station here when the sortie is home. Returns the points
	 * folded. Only a purge expedition
	 * (commitGroup) folds this way now: a player sortie re-tasks the staged fleet itself
	 * (ThreatFleetOrders.retask, 2026-10-11 - the fleets vanished and respawned out of the planet).
	 */
	protected static float foldStaged(MarketAPI host, float need, CampaignFleetAPI into,
			Object group, String label) {
		float folded = 0f;
		for (ThreatFleetOrders.Order pick : stagedToFold(host, need, into)) {
			float live = liveFP(pick.fleet);
			// every entry the fleet holds rides along (a restationed fleet can
			// carry several senders'), its live points shared by their charges
			List<Commitment> held = heldAllBy(pick.fleet);
			float charged = 0f;
			for (Commitment c : held) charged += c.fp;
			for (Commitment c : held) {
				String from = name(c.marketId);
				c.fleet = into;
				c.group = group;
				c.hostMarketId = host.getId();
				c.foldedFP = charged > 0f ? live * c.fp / charged : live / held.size();
				c.lostTimestamp = 0L;
				c.label = label;
				ThreatIncConfig.log("Capacity: task force from " + from + " staged at "
						+ host.getName() + " (" + (int) c.foldedFP + " FP live, " + (int) c.fp
						+ " charged to " + from + ") folds into " + label);
			}
			ThreatFleetOrders.fold(pick, host);
			folded += live;
			need -= live;
		}
		return folded;
	}

	/**
	 * The fleet is home at {@code home}. Ships folded in from a staging host
	 * and back at it stay on station there - the same hulls, every entry
	 * they hold re-pointed to a guard of the host
	 * ({@link ThreatFleetOrders#restation(CampaignFleetAPI, List, MarketAPI)});
	 * otherwise its points are the senders' again. Returns whether the fleet
	 * was kept - the caller must not expire it then.
	 */
	public static boolean release(CampaignFleetAPI fleet, MarketAPI home) {
		if (fleet == null) return false;
		List<Commitment> mine = heldAllBy(fleet);
		if (mine.isEmpty()) return false;
		// a fleet home is home (the user, 2026-10-12): from 2026-09-06 to 2026-10-12 a fleet carrying
		// ships staged at the host was kept on station there as a guard (restation), its hulls still
		// out and every sender's charge intact - "i returned fleet to haven and the hull FP split
		// between haven and ark". Staging is an explicit Fleet order now, nothing else.
		// the hull pool: what did not come home is a debt the yards rebuild
		// (ThreatHulls.lose) - the fleet's strength now over its strength at launch
		float health = ThreatHulls.enabled() ? Math.max(0f, Math.min(1f, ThreatReturns.health(fleet))) : 1f;
		for (Commitment c : mine) {
			all().remove(c);
			if (health < 1f) lostPart(c, c.fp * (1f - health));
			// the ships are where they landed: another colony's charge home here is this colony's now
			ThreatHulls.moveHome(c.marketId, home, c.fp * health);
			ThreatIncConfig.log("Capacity: " + (int) (c.fp * health) + " FP back from " + c.label
					+ (health < 1f ? " (" + (int) (c.fp * (1f - health)) + " lost)" : ""));
		}
		if (home != null && home.isPlayerOwned()) logHulls("after " + fleet.getName() + " settled at " + home.getName());
		return false;
	}

	/** Logs every player colony's Hulls cell and what makes it (the user, 2026-10-12: "the hull FP split between haven and ark"). */
	public static void logHulls(String when) {
		if (!ThreatIncConfig.debugLogging() || !ThreatHulls.enabled()) return;
		String pf = Global.getSector().getPlayerFaction().getId();
		StringBuilder sb = new StringBuilder("Hulls " + when + ": faction free " + (int) ThreatHulls.freeFP(pf)
				+ " (standing " + (int) ThreatHulls.standingFP(pf) + ", built " + (int) ThreatHulls.built(pf) + ")");
		for (MarketAPI m : ThreatHulls.marketsOf(pf)) {
			sb.append("; ").append(m.getName()).append(" cell ").append((int) ownFreeFP(m))
					.append(" = share ").append((int) ThreatHulls.shareFP(m))
					.append(" (standing ").append((int) ThreatHulls.standingFP(m))
					.append(m == ThreatHulls.hullPort(pf) ? ", hull port" : "")
					.append(", moved ").append((int) ThreatHulls.moved(m))
					.append(") - committed ").append((int) committedFP(m.getId()));
		}
		ThreatIncConfig.log(sb.toString());
	}

	/** Books the part of an entry that did not come home against its faction's hull debt. */
	protected static void lostPart(Commitment c, float fp) {
		if (!ThreatHulls.enabled() || c == null || fp <= 0f) return;
		MarketAPI m = c.marketId != null ? Global.getSector().getEconomy().getMarket(c.marketId) : null;
		if (m != null) ThreatHulls.lose(m.getFactionId(), fp, c.label);
		ThreatHulls.lostAt(m, fp); // the player's colony's own share drops (no faction-wide cap on its cell)
	}

	/**
	 * Fast poll: losses start the rebuild clock, rebuilt and ended groups
	 * drop off. A group entry only outlives the spawn when the expedition
	 * never became real ({@link #splitGroup}); it comes back at the route's
	 * damage, folded ships as a fresh guard over the host.
	 */
	public static void poll() {
		if (all().isEmpty()) return;
		long now = Global.getSector().getClock().getTimestamp();
		float rebuild = ThreatIncConfig.aidRebuildDays();
		for (Commitment c : new ArrayList<Commitment>(all())) {
			if (c.group != null) {
				if (!(c.group instanceof FleetGroupIntel)
						|| ((FleetGroupIntel) c.group).isEnded()) {
					all().remove(c);
					float health = c.group instanceof ThreatPurgeFGI
							? ((ThreatPurgeFGI) c.group).survivingFraction() : 1f;
					if (health < 1f) lostPart(c, c.fp * (1f - health));
					// home at the base it sailed from: another colony's hulls that came back with it
					// are the base's now (the user, 2026-10-12; until then each sender was refunded,
					// and a staged force was rebuilt over the host as a fresh guard - restation)
					MarketAPI home = c.hostMarketId != null ? Global.getSector().getEconomy().getMarket(c.hostMarketId)
							: c.group instanceof ThreatPurgeFGI ? ((ThreatPurgeFGI) c.group).sourceBase() : null;
					ThreatHulls.moveHome(c.marketId, home, c.fp * health);
					ThreatIncConfig.log("Capacity: " + (int) c.fp + " FP back from " + c.label
							+ " (expedition over)");
				}
				continue;
			}
			if (c.fleet == null) {
				all().remove(c);
				continue;
			}
			boolean dead = !c.fleet.isAlive() || c.fleet.isExpired();
			if (dead && ThreatHulls.enabled()) {
				// the hull pool: a lost fleet is a debt its faction's yards rebuild, not a clock
				all().remove(c);
				lostPart(c, c.fp);
				continue;
			}
			if (dead && c.lostTimestamp == 0L) {
				c.lostTimestamp = now;
				ThreatIncConfig.log("Capacity: " + c.label + " lost - " + (int) c.fp
						+ " FP rebuilding for " + (int) rebuild + " days");
				continue;
			}
			if (c.lostTimestamp != 0L
					&& Global.getSector().getClock().getElapsedDaysSince(c.lostTimestamp) >= rebuild) {
				all().remove(c);
				ThreatIncConfig.log("Capacity: " + (int) c.fp + " FP rebuilt after " + c.label);
			}
		}
	}

	protected static String name(String marketId) {
		MarketAPI m = marketId != null ? Global.getSector().getEconomy().getMarket(marketId) : null;
		return m != null ? m.getName() : String.valueOf(marketId);
	}

	// ------------------------------------------------------------------
	// what a fleet costs in points
	// ------------------------------------------------------------------

	/** Points a task force of this combat strength holds: the warships plus their support hulls. */
	public static float taskForcePoints(float combatFP) {
		return combatFP * TASK_FORCE_HULL_MULT;
	}

	/**
	 * Points a convoy holds for a load in ThreatReserves.COMMODITIES order
	 * (marines, armaments, fuel, supplies): the escort by cargo value plus
	 * the hulls - the same ratios ThreatConvoys.dispatch builds with.
	 */
	public static float convoyPoints(float[] load) {
		float marines = load[0];
		float cargoUnits = load[1] + load[2] + load[3] + ThreatConvoys.hullsIn(load);
		if (marines <= 0f && cargoUnits <= 0f) return 0f;
		float escort = ThreatIncConfig.convoyEscortFP()
				+ ThreatConvoys.cargoValue(marines, load[1], load[2], load[3]) / 1000f
						* ThreatIncConfig.convoyEscortPerThousand();
		float freighterPts = Math.max(10f, cargoUnits / 60f);
		float tankerPts = load[2] > 0f ? Math.max(5f, load[2] / 100f) : 0f;
		float transportPts = marines > 0f ? Math.max(10f, marines / 40f) : 0f;
		return escort + freighterPts + tankerPts + transportPts;
	}

	/** Points a purge expedition of these vanilla-scale fleet sizes holds. */
	public static float expeditionPoints(List<Integer> fleetSizes) {
		float sum = 0f;
		for (Integer size : fleetSizes) sum += size * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return sum;
	}

	/**
	 * The largest fraction of the load whose convoy fits in the free points,
	 * as whole items; all zeros when not even a picket-sized run fits.
	 */
	public static float[] fitLoad(float free, float[] load) {
		float[] result = new float[load.length];
		if (convoyPoints(load) <= free) {
			for (int i = 0; i < load.length; i++) result[i] = (float) Math.floor(load[i]);
			return result;
		}
		float lo = 0f;
		float hi = 1f;
		for (int i = 0; i < 24; i++) {
			float mid = (lo + hi) / 2f;
			for (int j = 0; j < load.length; j++) result[j] = load[j] * mid;
			if (convoyPoints(result) <= free) lo = mid;
			else hi = mid;
		}
		float total = 0f;
		for (int j = 0; j < load.length; j++) {
			result[j] = (float) Math.floor(load[j] * lo);
			total += result[j];
		}
		if (total < 1f) {
			for (int j = 0; j < load.length; j++) result[j] = 0f;
		}
		return result;
	}

	/**
	 * A player expedition trimmed to what its base can field (own free
	 * points plus what is staged there): fleets are dropped (never below
	 * two), then each remaining fleet shrunk (never below
	 * responseMinDifficulty). What is left may still exceed the free points;
	 * the order is then refused ({@link IncursionManager#siegeBlockReason},
	 * {@link IncursionManager#launchSiegeExpedition}) - no colony sails
	 * over-extended (2026-09-05, docs/player-aid.md section 7, item 4).
	 */
	public static List<Integer> fitExpedition(MarketAPI base, FactionAPI faction,
			List<Integer> fleetSizes) {
		return fitExpedition(base, faction, fleetSizes, true);
	}

	/** As above; {@code log} false for the board's quotes, which run every render. */
	public static List<Integer> fitExpedition(MarketAPI base, FactionAPI faction,
			List<Integer> fleetSizes, boolean log) {
		if (faction == null || !faction.isPlayerFaction() || !enabled()) return fleetSizes;
		List<Integer> sizes = new ArrayList<Integer>(fleetSizes);
		float free = freeFP(base);
		int minDiff = ThreatIncConfig.responseMinDifficulty();
		while (sizes.size() > 2 && expeditionPoints(sizes) > free) {
			sizes.remove(sizes.size() - 1);
		}
		boolean changed = true;
		while (changed && expeditionPoints(sizes) > free) {
			changed = false;
			for (int i = sizes.size() - 1; i >= 0; i--) {
				if (sizes.get(i) > minDiff) {
					sizes.set(i, sizes.get(i) - 1);
					changed = true;
					if (expeditionPoints(sizes) <= free) break;
				}
			}
		}
		if (log && !sizes.equals(fleetSizes)) {
			ThreatIncConfig.log("Capacity: expedition at " + base.getName() + " trimmed from "
					+ fleetSizes + " to " + sizes + " (" + (int) free + " FP free)"
					+ (expeditionPoints(sizes) > free ? " - still over, refused" : ""));
		}
		return sizes;
	}

	/**
	 * The board's whole-number figures for a colony, rounded ONCE so that
	 * every line adds up: {capacity, in task forces, at home, on station
	 * here, free}. At home = capacity - in task forces (never below 0); on
	 * station = the live points of the task forces staged here, each
	 * rounded; free = at home + on station. The FP cell shows free /
	 * capacity.
	 */
	public static int[] figures(MarketAPI market) {
		int cap = Math.round(capacityFP(market));
		int out = Math.round(committedFP(market.getId()));
		int home = Math.max(0, cap - out);
		int staged = 0;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.stagedAt(market)) {
			if (heldBy(o.fleet) != null) staged += Math.round(liveFP(o.fleet));
		}
		return new int[] {cap, out, home, staged, home + staged};
	}

}
