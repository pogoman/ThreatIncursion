package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.ShipRoles;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.util.Misc;

/**
 * CONVOYS - the physical staging layer (docs/strategy-layer.md). A mobilised
 * faction's expedition draws only from the colony it sails from, so the rest
 * of the faction ships war materiel to that STAGING BASE by convoy: a real
 * fleet with the marines, armaments, fuel and supplies aboard, moving through
 * hyperspace like any other. Lose the convoy and the stock is gone; the
 * Threat hunts them, and so can the player.
 *
 * <p>Logistics AI runs on the slow tick, per mobilised faction: every
 * military world within expedition range of a live hive is a staging base;
 * when one is short of what an expedition against its nearest hive would
 * draw, net of what is already at sea to it, the same-faction colonies with
 * spare stock in reach each send a convoy of what they can spare of it - as
 * many in parallel as the shortfall and the donors support, each fleet grown
 * to carry its load (no per-convoy load cap and no one-convoy-per-base rule
 * since 2026-09-29) up to vanilla's maxShipsInAIFleet - a load past that
 * sails in several fleets. Arrivals and losses resolve on the fast poll.
 */
public class ThreatConvoys {

	public static final String KEY_CONVOYS = "threatinc_convoys";
	/** Fleet memory flag marking a war-materiel convoy. */
	public static final String CONVOY_FLAG = "$threatinc_convoy";
	/** The smallest load worth a front run or a relief convoy; below it the sailing waits. */
	public static final float FRONT_RUN_MIN_MARINES = 50f;
	public static final float FRONT_RUN_MIN_ARMAMENTS = 20f;

	/** One convoy in flight. The cargo is also physically aboard the fleet. */
	public static class Convoy {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String fromMarketId;
		public String toMarketId;
		public float marines;
		public float armaments;
		public float fuel;
		public float supplies;
		public long departedTimestamp;
		/** Set for a FRONT RUN: the hive world whose friendly front this convoy supplies or picks up. */
		public String frontMarketId;
		/** A withdrawal run: lands empty, lifts the front off, carries it home. */
		public boolean pickup;
		/** Whether the run has been ordered from the jump-point in to the planet. */
		public boolean runningIn;
		/** When it started waiting at the jump-point for the orbit to clear; 0 = not waiting. */
		public long waitSinceTimestamp;
		/** The faction whose colony receives the cargo when it is not the sender's own; null otherwise. */
		public String recipientFactionId;
		/** A player aid convoy: paid in credits, on the capacity ledger, earning standing on landing. */
		public boolean aid;
		/**
		 * On the first fleet of a sailing split across several (a load past
		 * vanilla's maxShipsInAIFleet): what all of them carry, in
		 * ThreatReserves.COMMODITIES order ({@link ThreatConvoys#carried}).
		 * Transient - the planner reads it the tick it sails; null otherwise.
		 */
		public transient float[] sailing;

		public boolean isFrontRun() {
			return frontMarketId != null;
		}

		/** The sender: a colony, or - for a front run out of one - an outpost. */
		public String fromName() {
			return ThreatBases.nameOf(fromMarketId);
		}

		/** The destination: a colony, a hive world (front run), or an outpost. */
		public String toName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(toMarketId);
			if (m != null) return m.getName();
			// a hive world a siege destroyed has left the economy; its planet has not
			return ThreatBases.nameOf(toMarketId);
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Convoy> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CONVOYS);
		if (!(val instanceof List)) {
			val = new ArrayList<Convoy>();
			Global.getSector().getPersistentData().put(KEY_CONVOYS, val);
		}
		return (List<Convoy>) val;
	}

	public static boolean isConvoy(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(CONVOY_FLAG);
	}

	public static List<Convoy> convoysFor(String factionId) {
		List<Convoy> result = new ArrayList<Convoy>();
		for (Convoy c : all()) {
			if (factionId == null || factionId.equals(c.factionId)) result.add(c);
		}
		return result;
	}

	/** Whether this fleet is a convoy the layer is still tracking (not arrived, lost or recalled). */
	public static boolean isTracked(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		for (Convoy c : all()) {
			if (c.fleet == fleet) return true;
		}
		return false;
	}

	/** Cargo value for escort sizing: marines weigh most, provisions least. */
	public static float cargoValue(float marines, float armaments, float fuel, float supplies) {
		return marines * 1f + armaments * 0.5f + fuel * 0.1f + supplies * 0.1f;
	}

	/** What a sailing carries, in ThreatReserves.COMMODITIES order: every fleet of a split one (Convoy.sailing), else the convoy's own load. */
	public static float[] carried(Convoy c) {
		if (c == null) return new float[ThreatReserves.COMMODITIES.length];
		if (c.sailing != null) return c.sailing.clone();
		return new float[] {c.marines, c.armaments, c.fuel, c.supplies};
	}

	// ------------------------------------------------------------------
	// the escort's voyage (2026-09-29: closed economy - an NPC escort is
	// paid like any other NPC fleet, and never sails free)
	// ------------------------------------------------------------------

	/**
	 * {fuel, supplies} one escort point's voyage of {@code ly} costs: the
	 * sortie rate (ThreatFleetOrders.sortieWants) - expeditionSuppliesPerPoint
	 * per FP_PER_RESPONSE_DIFFICULTY points, fuel for the distance.
	 */
	protected static float[] escortRate(float ly) {
		float points = 1f / IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return new float[] {points * Math.max(0f, ly) * ThreatIncConfig.expeditionFuelPerPointLY(),
				points * ThreatIncConfig.expeditionSuppliesPerPoint()};
	}

	/** What the donor may spend on an escort: a colony's spendable stock (ThreatReserves.spendable), an outpost's whole stockpile. */
	protected static float escortStock(ThreatBases.Base donor, String commodityId) {
		if (donor.isOutpost()) return ThreatReserves.stock(donor.id(), commodityId);
		return ThreatReserves.spendable(donor.market, commodityId);
	}

	/**
	 * The escort points the donor pays a voyage of {@code ly} for, at most
	 * {@code want}: the escort shrinks to what is paid, to none on a depot
	 * with nothing to spare. The fuel and supplies the convoy ships as cargo
	 * ({@code fuelLoad}, {@code suppliesLoad}) are not the escort's to spend.
	 * A player convoy's escort is its ledger's business and is not charged here.
	 */
	protected static float paidEscort(ThreatBases.Base donor, float want, float ly, float fuelLoad,
			float suppliesLoad) {
		if (want <= 0f) return 0f;
		float[] rate = escortRate(ly);
		float fp = want;
		if (rate[0] > 0f) {
			fp = Math.min(fp, Math.max(0f, escortStock(donor, Commodities.FUEL) - fuelLoad) / rate[0]);
		}
		if (rate[1] > 0f) {
			fp = Math.min(fp, Math.max(0f, escortStock(donor, Commodities.SUPPLIES) - suppliesLoad) / rate[1]);
		}
		return Math.max(0f, fp);
	}

	/**
	 * Draws an escort of {@code escort} points' voyage from the donor and
	 * records it on the fleet (ThreatReturns.provision), so the convoy's
	 * return re-banks its hulls at what survived. Call after the hulls are
	 * final: the launch strength is read here.
	 */
	protected static void payEscort(CampaignFleetAPI fleet, ThreatBases.Base donor, float escort, float ly) {
		float[] rate = escortRate(ly);
		float fuel = drawEscort(donor, Commodities.FUEL, escort * rate[0]);
		float supplies = drawEscort(donor, Commodities.SUPPLIES, escort * rate[1]);
		ThreatReturns.provision(fleet, donor.id(), fuel, supplies);
	}

	protected static float drawEscort(ThreatBases.Base donor, String commodityId, float amount) {
		if (amount <= 0f) return 0f;
		if (donor.isOutpost()) return ThreatReserves.draw(donor.id(), commodityId, amount);
		return ThreatReserves.drawSpendable(donor.market, commodityId, amount);
	}

	/** A convoy that never sailed: its escort's voyage back to the donor in full. */
	protected static void refundEscort(CampaignFleetAPI fleet, ThreatBases.Base donor) {
		if (fleet == null || donor == null) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		ThreatBases.deposit(donor, Commodities.FUEL, mem.getFloat(ThreatReturns.MEM_FUEL));
		ThreatBases.deposit(donor, Commodities.SUPPLIES, mem.getFloat(ThreatReturns.MEM_SUPPLIES));
		mem.unset(ThreatReturns.MEM_FUEL);
		mem.unset(ThreatReturns.MEM_SUPPLIES);
	}

	/**
	 * Cargo still at sea to this base, in ThreatReserves.COMMODITIES order:
	 * every logistics convoy bound for it that has not landed, read off the
	 * fleet's hold (what a fight left aboard) while it is alive. The planners
	 * size the next sailing to the shortfall net of this, so several convoys
	 * can sail to one base at once without over-shipping (2026-09-29: one
	 * convoy per base at a time made a staging target of tens of thousands a
	 * queue of sequential round trips).
	 */
	protected static float[] inbound(String marketId) {
		float[] out = new float[ThreatReserves.COMMODITIES.length];
		if (marketId == null) return out;
		for (Convoy c : all()) {
			if (c.isFrontRun() || !marketId.equals(c.toMarketId)) continue;
			CargoAPI cargo = c.fleet != null && c.fleet.isAlive() ? c.fleet.getCargo() : null;
			out[0] += cargo != null ? cargo.getMarines() : c.marines;
			out[1] += cargo != null ? cargo.getCommodityQuantity(Commodities.HAND_WEAPONS) : c.armaments;
			out[2] += cargo != null ? cargo.getCommodityQuantity(Commodities.FUEL) : c.fuel;
			out[3] += cargo != null ? cargo.getCommodityQuantity(Commodities.SUPPLIES) : c.supplies;
		}
		return out;
	}

	// ------------------------------------------------------------------
	// logistics AI (slow tick)
	// ------------------------------------------------------------------

	/**
	 * A STAGING BASE is the military world a siege sails from: the faction's
	 * nearest base in expedition range of a live hive (the same pick the
	 * Siege button makes, ThreatFleetOrders.pickBase). Returns the nearest
	 * hive this world is the staging base for, or null - a military world
	 * that is in range of hives but never the nearest base for any is not a
	 * staging base, it is a donor. Before 2026-09-05 every military world in
	 * range of any hive counted, so with one hive cluster all five of the
	 * player's colonies were "staging", all wanted 4,700 marines, nobody had
	 * spare, and nothing ever concentrated anywhere. The player's base stages
	 * for the nearest hive the PLAYER HAS FOUND, at any range (the Siege
	 * button has none for the player): until 2026-09-05 evening it stocked
	 * for the nearest hive full stop, and the board named a system the
	 * player could not find on the map.
	 */
	public static StarSystemAPI stagingHive(MarketAPI base) {
		if (base == null || base.getStarSystem() == null || base.getFaction() == null) return null;
		if (!IncursionManager.isBase(base)) return null;
		boolean player = base.isPlayerOwned();
		boolean debug = ThreatIncConfig.debugMode();
		float range = player ? Float.MAX_VALUE : IncursionManager.expeditionRangeLY(base);
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			if (player && !debug && !ThreatIncData.discoveredSystems().contains(systemId)) continue;
			if (!player && !ThreatScouts.sectorKnows(systemId)) continue;
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
			if (d > range || d >= bestDist) continue;
			if (ThreatFleetOrders.pickBase(base.getFaction(), system.getLocation()) != base) continue;
			bestDist = d;
			best = system;
		}
		return best;
	}

	/**
	 * How far a market's stock reaches another market: convoyRangeLY, or as far
	 * as its fuel does (IncursionManager.expeditionRangeLY) - the donor rule of
	 * the staging planner, relief runs and the siege and hunt pools. The
	 * player's at any range.
	 */
	public static float stockReachLY(MarketAPI donor) {
		if (donor == null) return 0f;
		if (donor.getFaction() != null && donor.getFaction().isPlayerFaction()) return Float.MAX_VALUE;
		return Math.max(ThreatIncConfig.convoyRangeLY(), IncursionManager.expeditionRangeLY(donor));
	}

	/**
	 * The faction's nearest staging base within convoy range of this colony
	 * (not itself), or null. The player's colonies feed a base at any range
	 * (2026-09-05 evening: Diggers, a fuel world 20 ly out, showed a dash
	 * while the Supplies button could sail from it - "distance costs time,
	 * never permission" holds for the planner too).
	 */
	public static MarketAPI stagingBaseFor(MarketAPI colony) {
		if (colony == null || colony.getStarSystem() == null || colony.getFaction() == null) return null;
		// an NPC colony feeds a staging base as far as its fuel reaches, the range
		// the base itself stages at (stagingHive); the flat convoyRangeLY kept
		// Hegemony's marines circling its core worlds while its forward staging
		// base got two convoys in three years (run 9)
		float range = stockReachLY(colony);
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(colony.getFaction().getId())) {
			if (m == colony || m.getStarSystem() == null) continue;
			float d = Misc.getDistanceLY(colony.getStarSystem().getLocation(),
					m.getStarSystem().getLocation());
			if (d > range || d >= bestDist) continue;
			if (stagingHive(m) == null) continue;
			bestDist = d;
			best = m;
		}
		return best;
	}

	/**
	 * Target stock of each reserve commodity at a staging base, in
	 * ThreatReserves.COMMODITIES order: what the siege the Siege button would
	 * launch from here against its hive draws (IncursionManager.siegeWants -
	 * the same figures the button and its prompt show), or a hunting force's
	 * while that siege is past the faction's means ({@link #siegeStock}), times
	 * stagingTargetMult. All zeros for a world that is not a staging base.
	 * Memoised per base for a day: each call is a
	 * stagingHive sweep plus a full siegeSizes, and the planner asks for a
	 * donor's (through ThreatReserves.stagingBank, per donor per commodity
	 * per base) and the board for a colony's (per commodity) many times over
	 * in one tick or render. The memo empties after a day, on every
	 * war board render and on game load ({@link #forgetStagingTargets}): a
	 * player base's target follows its free fleet points, which a board order
	 * changes with the clock stopped. A RELAY's target is added on top
	 * ({@link #relayTargets}): the stock it passes on to a staging base its
	 * donors cannot reach.
	 */
	public static float[] stagingTargets(MarketAPI base) {
		return withVoyage(base, bankTargets(base));
	}

	/**
	 * What the base keeps banked for sieges, its own and those it relays for:
	 * {@link #stagingTargets} without a garrison voyage's want. The staging bank
	 * (ThreatReserves.stagingBank) - a voyage's stock must stay spendable, as
	 * the voyage draws only that.
	 */
	public static float[] bankTargets(MarketAPI base) {
		float[] wants = siegeStock(base);
		float[] relay = relayTargets(base);
		for (int i = 0; i < wants.length; i++) wants[i] += relay[i];
		return wants;
	}

	/**
	 * The targets {@code bank} raised to what a garrison's voyage from the base
	 * waits on (ThreatFrontlines.garrisonWants, 2026-09-29): the voyage's fuel
	 * and supplies above the stock a voyage never draws (ThreatReserves.spendable's
	 * keep), so the stock convoys bring is stock the voyage can spend.
	 */
	protected static float[] withVoyage(MarketAPI base, float[] bank) {
		if (base == null || base.isPlayerOwned()) return bank;
		float[] voyage = ThreatFrontlines.garrisonWants(base);
		String[] cs = {Commodities.FUEL, Commodities.SUPPLIES};
		for (int k = 0; k < cs.length; k++) {
			int i = ThreatAid.index(cs[k]);
			if (i < 0 || voyage[k] <= 0f) continue;
			float keep = Math.max(ThreatReserves.floor(base, cs[k]),
					ThreatReserves.monthsBasis(base, cs[k]) * ThreatIncConfig.donorKeepFraction() + bank[i]);
			bank[i] = Math.max(bank[i], keep + voyage[k]);
		}
		return bank;
	}

	/** One commodity of {@link #bankTargets}. */
	public static float bankTarget(MarketAPI base, String commodityId) {
		int i = ThreatAid.index(commodityId);
		return i >= 0 ? bankTargets(base)[i] : 0f;
	}

	/** A market's own targets, relays aside: its siege's stock, raised to any garrison voyage's want - what a relay plan weighs a market's need and keep by. */
	protected static float[] ownWants(MarketAPI m) {
		return withVoyage(m, siegeStock(m));
	}

	/** Drops the day's memos when a day has passed since they were drawn. */
	protected static void ageTargetsMemo() {
		long now = Global.getSector().getClock().getTimestamp();
		// a day's memo: rebuilt at every clock instant, the half-day reserve poll
		// re-sized every staging base's siege on every pass (rc1 review)
		float age = targetsMemoStamp == Long.MIN_VALUE ? Float.MAX_VALUE
				: Global.getSector().getClock().getElapsedDaysSince(targetsMemoStamp);
		if (age < 0f || age >= 1f) {
			targetsMemo.clear();
			huntStaged.clear();
			relayMemo.clear();
			targetsMemoStamp = now;
		}
	}

	/**
	 * {@link #stagingTargets} for the base's own siege alone, relays aside: what
	 * the siege from here draws, times stagingTargetMult - or, while the faction
	 * cannot pay for that siege ({@link #siegeFundable}), what a hunting force
	 * from here against the same hive draws (ThreatSoftening.stagingWants,
	 * 2026-09-29). Staged for Pelephanar's siege, weighed on 14,288 FP of
	 * system swarms, Hegemony's staging bases and relays banked 100-560k fuel
	 * against a faction total of 59.6k: none of it was ever spendable, and the
	 * hunts that would have thinned the system went unpaid. Once hunts thin it enough the siege fits and the
	 * target returns to it; relays follow either way (ownWants). The verdict holds
	 * until it is clearly wrong ({@link #HUNT_VERDICTS}).
	 */
	protected static float[] siegeStock(MarketAPI base) {
		if (base == null) return new float[] {0f, 0f, 0f, 0f};
		ageTargetsMemo();
		float[] memo = targetsMemo.get(base.getId());
		if (memo != null) return memo.clone();
		float[] wants;
		StarSystemAPI hive = stagingHive(base);
		Map<String, String> verdicts = ThreatIncData.map(HUNT_VERDICTS);
		if (hive == null) {
			wants = new float[] {0f, 0f, 0f, 0f};
			verdicts.remove(base.getId());
		} else {
			wants = IncursionManager.siegeWants(base, base.getFaction(), hive);
			// the player orders their own sieges; only an NPC base stages for its hunt
			if (!base.isPlayerOwned()) {
				float[] have = fundingInReach(base);
				// hysteresis: a base staging for a hunt goes back to its siege only
				// with headroom, one staging for its siege drops it only once it no
				// longer fits at all
				boolean wasHunt = hive.getId().equals(verdicts.get(base.getId()));
				boolean fits = siegeFundable(wants, have, wasHunt ? SIEGE_RETURN_SHARE : 1f);
				if (fits) verdicts.remove(base.getId());
				else verdicts.put(base.getId(), hive.getId());
				int fuel = ThreatAid.index(Commodities.FUEL), supplies = ThreatAid.index(Commodities.SUPPLIES);
				ThreatIncConfig.logOnChange("staging:" + base.getId(), fits ? "siege" : "hunt", "Staging: "
						+ base.getName() + " stocks for " + (fits ? "its siege of " : "a hunting force against ")
						+ hive.getName() + " - the siege wants " + (int) wants[fuel] + " fuel, " + (int) wants[supplies]
						+ " supplies; in reach with " + (int) ThreatIncConfig.stagingHorizonMonths()
						+ " months' banking: " + (int) have[fuel] + " fuel, " + (int) have[supplies] + " supplies");
				if (!fits) {
					wants = ThreatSoftening.stagingWants(base, hive);
					huntStaged.add(base.getId());
				}
			}
			float mult = ThreatIncConfig.stagingTargetMult();
			for (int i = 0; i < wants.length; i++) wants[i] *= mult;
		}
		targetsMemo.put(base.getId(), wants.clone());
		return wants;
	}

	/**
	 * Whether the base stages for a hunting force, its siege being past what
	 * the faction can pay for ({@link #siegeStock}): its staging bank is then
	 * any hunt's in reach, not only one in its own staging hive
	 * (ThreatSoftening.huntSpendable).
	 */
	public static boolean stagesForHunt(MarketAPI base) {
		if (base == null) return false;
		// fills the day's memo, and this set with it
		siegeStock(base);
		return huntStaged.contains(base.getId());
	}

	/**
	 * Persistent: staging base id -> the hive whose hunt it stages for, while
	 * its siege there is past the faction's means ({@link #siegeStock}). Epsilon
	 * Mengryla I Forward Base, its siege needing 115-136k fuel against 117-120k
	 * in reach, flipped between the two four times in one run, and each flip
	 * turned its convoys around.
	 */
	protected static final String HUNT_VERDICTS = "threatinc_huntStagingVerdicts";
	/** The share of the funding in reach a siege may need for a base staging for a hunt to go back to it. */
	protected static final float SIEGE_RETURN_SHARE = 0.8f;

	/**
	 * Whether a siege's fuel and supplies ({@code wants}, COMMODITIES order) fit
	 * in {@code have} ({@link #fundingInReach}). Marines and armaments are not
	 * weighed: they are not what a hunt spends.
	 */
	protected static boolean siegeFundable(float[] wants, float[] have) {
		return siegeFundable(wants, have, 1f);
	}

	/** As above, the siege needing at most {@code share} of each. */
	protected static boolean siegeFundable(float[] wants, float[] have, float share) {
		String[] cs = {Commodities.FUEL, Commodities.SUPPLIES};
		for (String c : cs) {
			int i = ThreatAid.index(c);
			if (i >= 0 && wants[i] > have[i] * share) return false;
		}
		return true;
	}

	/**
	 * What the faction can bring to the base within stagingHorizonMonths, in
	 * COMMODITIES order: the stock of every market in its convoy network
	 * ({@link #stockNetwork}) plus that many months of what each banks
	 * (ThreatReserves.accrualPer30).
	 */
	protected static float[] fundingInReach(MarketAPI base) {
		float[] out = new float[ThreatReserves.COMMODITIES.length];
		float months = Math.max(0f, ThreatIncConfig.stagingHorizonMonths());
		for (MarketAPI m : stockNetwork(base)) {
			for (int i = 0; i < out.length; i++) {
				String c = ThreatReserves.COMMODITIES[i];
				out[i] += ThreatReserves.stock(m.getId(), c) + ThreatReserves.accrualPer30(m, c) * months;
			}
		}
		return out;
	}

	/**
	 * The base and every market of its faction whose stock can reach it by
	 * convoy, directly (IncursionManager.marketsReaching) or passed on through
	 * others - the reach relays give it ({@link #relayPlan}).
	 */
	protected static List<MarketAPI> stockNetwork(MarketAPI base) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (base == null || base.getFaction() == null) return out;
		java.util.Set<String> seen = new java.util.HashSet<String>();
		out.add(base);
		seen.add(base.getId());
		// each market joins once, from the faction's finite list: the walk ends
		for (int k = 0; k < out.size(); k++) {
			for (MarketAPI m : IncursionManager.marketsReaching(base.getFaction(), out.get(k))) {
				if (seen.add(m.getId())) out.add(m);
			}
		}
		return out;
	}

	/** One commodity of {@link #stagingTargets}. */
	public static float stagingTarget(MarketAPI base, String commodityId) {
		int i = ThreatAid.index(commodityId);
		return i >= 0 ? stagingTargets(base)[i] : 0f;
	}

	/** {@link #siegeStock} by market id, good for a day from targetsMemoStamp. */
	private static final Map<String, float[]> targetsMemo = new HashMap<String, float[]>();
	/** Ids of the bases whose memoised {@link #siegeStock} is a hunting force's ({@link #stagesForHunt}); emptied with targetsMemo. */
	private static final java.util.Set<String> huntStaged = new java.util.HashSet<String>();
	private static long targetsMemoStamp = Long.MIN_VALUE;

	/** Drops the stagingTargets memos; the faction view calls it as it renders, so a board order's effect shows on the same paused frame. */
	public static void forgetStagingTargets() {
		targetsMemo.clear();
		huntStaged.clear();
		relayMemo.clear();
	}

	// ------------------------------------------------------------------
	// relays: stock carried on toward a staging base past its donors' reach
	// ------------------------------------------------------------------

	/** One faction's relays for the day: relay market id -> its target, and -> the ids of the markets it passes stock on to. */
	protected static class RelayPlan {
		final Map<String, float[]> target = new HashMap<String, float[]>();
		final Map<String, java.util.Set<String>> serves = new HashMap<String, java.util.Set<String>>();
	}

	/** Faction id -> {@link RelayPlan}, good for a day from targetsMemoStamp; not saved. */
	private static final Map<String, RelayPlan> relayMemo = new HashMap<String, RelayPlan>();
	/** Set while a plan is drawn: a nested ask (through a donor's keep) reads no relays rather than recursing. */
	private static boolean drawingRelays = false;

	/**
	 * What a market holds to pass on, in ThreatReserves.COMMODITIES order: its
	 * relay target ({@link #relayPlan}). Zeros for the player's (their convoys
	 * reach any range) and for a market that relays for nothing.
	 */
	public static float[] relayTargets(MarketAPI market) {
		float[] out = new float[ThreatReserves.COMMODITIES.length];
		if (market == null || market.getFaction() == null || market.isPlayerOwned()) return out;
		float[] t = relayPlan(market.getFaction()).target.get(market.getId());
		if (t != null) System.arraycopy(t, 0, out, 0, out.length);
		return out;
	}

	/**
	 * RELAYS (2026-09-29). A market's stock reaches another only within
	 * stockReachLY - convoyRangeLY, or a military world's fuel range - so a
	 * forward staging base drew on the two to six markets near it while the
	 * core's fifteen could not reach it at all, and its sieges sailed from the
	 * core at two to three times the passage (Chicomoztoc to Damar's Star, 31.9
	 * ly, while Gamma Shero stood 11.4 ly out). Stock does not jump that gap: a
	 * RELAY carries it. A market short of what its donors in reach can give
	 * names one relay - a depot whose stock reaches it, reached itself by more
	 * of the faction's markets, the most of them new - and the relay takes the
	 * rest as a target of its own. Convoys stock the relay from its donors
	 * like any staging base (planLogistics), and it sends what it holds for
	 * the market on ({@link #sendable}); hunts and other staging bases leave it
	 * (the staging bank, {@link #spare}). A relay short in turn names its own,
	 * so a chain climbs toward the core a convoy hop at a time.
	 *
	 * <p>Every market is weighed once, fewest markets reaching it first. A
	 * relay is always reached by more markets than the one it serves, so it is
	 * weighed after everything that relays through it, and no chain can loop:
	 * the plan ends within the faction's market count.
	 */
	protected static RelayPlan relayPlan(FactionAPI faction) {
		ageTargetsMemo();
		RelayPlan plan = relayMemo.get(faction.getId());
		if (plan != null) return plan;
		plan = new RelayPlan();
		if (faction.isPlayerFaction() || drawingRelays) return plan;
		drawingRelays = true;
		try {
			drawRelays(faction, plan);
		} finally {
			drawingRelays = false;
		}
		relayMemo.put(faction.getId(), plan);
		return plan;
	}

	protected static void drawRelays(FactionAPI faction, RelayPlan plan) {
		List<MarketAPI> markets = ThreatReserves.marketsOf(faction.getId());
		final Map<MarketAPI, Integer> reached = new HashMap<MarketAPI, Integer>();
		Map<MarketAPI, float[]> want = new java.util.LinkedHashMap<MarketAPI, float[]>();
		for (MarketAPI m : markets) {
			if (m.getStarSystem() == null) continue;
			reached.put(m, IncursionManager.marketsReaching(faction, m).size());
			// a siege's stock, or a garrison voyage's (ownWants)
			float[] own = ownWants(m);
			for (float w : own) {
				if (w > 0f) {
					want.put(m, own);
					break;
				}
			}
		}
		int n = ThreatReserves.COMMODITIES.length;
		java.util.Set<MarketAPI> weighed = new java.util.HashSet<MarketAPI>();
		// one market marked weighed a pass, from a finite list: at most one
		// pass per market
		while (true) {
			MarketAPI needy = null;
			for (MarketAPI m : want.keySet()) {
				if (weighed.contains(m)) continue;
				if (needy == null || reached.get(m) < reached.get(needy)) needy = m;
			}
			if (needy == null) break;
			weighed.add(needy);
			MarketAPI relay = pickRelay(faction, needy, reached);
			if (relay == null) continue;
			// short net of its stock, what is at sea to it and what every donor
			// in reach but the relay could send
			float[] w = want.get(needy);
			float[] at = inbound(needy.getId());
			float[] unmet = new float[n];
			boolean any = false;
			for (int i = 0; i < n; i++) {
				String c = ThreatReserves.COMMODITIES[i];
				float u = w[i] - ThreatReserves.stock(needy.getId(), c) - at[i];
				for (MarketAPI d : IncursionManager.marketsReaching(faction, needy)) {
					if (d == needy || d == relay || u <= 0f) continue;
					u -= ownSpare(plan, d, i);
				}
				unmet[i] = Math.max(0f, u);
				if (worthSailing(c, unmet[i], w[i])) any = true;
			}
			if (!any) continue;
			float[] t = plan.target.get(relay.getId());
			if (t == null) t = new float[n];
			float[] rw = want.containsKey(relay) ? want.get(relay) : ownWants(relay);
			for (int i = 0; i < n; i++) {
				t[i] += unmet[i];
				rw[i] += unmet[i];
			}
			plan.target.put(relay.getId(), t);
			// weighed later: it is reached by more markets than the needy
			want.put(relay, rw);
			java.util.Set<String> ids = plan.serves.get(relay.getId());
			if (ids == null) {
				ids = new java.util.HashSet<String>();
				plan.serves.put(relay.getId(), ids);
			}
			ids.add(needy.getId());
			ThreatIncConfig.logOnChange("relay:" + needy.getId(), relay.getId(), "Relay: " + faction.getId() + " "
					+ relay.getName() + " passes stock on to " + needy.getName() + " ("
					+ (int) unmet[0] + " marines, " + (int) unmet[1] + " armaments, " + (int) unmet[2]
					+ " fuel, " + (int) unmet[3] + " supplies past its donors' reach)");
		}
	}

	/**
	 * The relay for a market its donors cannot keep stocked, or null: a depot
	 * of the faction whose stock reaches it (stockReachLY), not under a ground
	 * front, reached by more of the faction's markets than it is, the most of
	 * them markets that cannot reach it themselves; the nearest breaks a tie.
	 */
	protected static MarketAPI pickRelay(FactionAPI faction, MarketAPI needy, Map<MarketAPI, Integer> reached) {
		List<MarketAPI> near = IncursionManager.marketsReaching(faction, needy);
		java.util.Set<MarketAPI> reachesNeedy = new java.util.HashSet<MarketAPI>(near);
		Integer own = reached.get(needy);
		if (own == null) return null;
		MarketAPI best = null;
		int bestFresh = 0;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : near) {
			if (m == needy || m.isPlayerOwned() || ThreatGroundFronts.hasFront(m)) continue;
			if (!ThreatReserves.hasDepot(m)) continue;
			Integer r = reached.get(m);
			if (r == null || r <= own) continue;
			int fresh = 0;
			for (MarketAPI s : IncursionManager.marketsReaching(faction, m)) {
				if (!reachesNeedy.contains(s)) fresh++;
			}
			if (fresh <= 0) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), needy.getStarSystem().getLocation());
			if (fresh > bestFresh || (fresh == bestFresh && d < bestDist)) {
				best = m;
				bestFresh = fresh;
				bestDist = d;
			}
		}
		return best;
	}

	/** What a donor could send of commodity {@code i} by the plan so far: its stock above its keep, its own needs (ownKeep) and any relay target already set. */
	protected static float ownSpare(RelayPlan plan, MarketAPI donor, int i) {
		if (ThreatGroundFronts.hasFront(donor) || donor.isPlayerOwned()) return 0f;
		String c = ThreatReserves.COMMODITIES[i];
		float[] relay = plan.target.get(donor.getId());
		float keep = ownKeep(donor, i) + (relay != null ? relay[i] : 0f);
		return Math.max(0f, ThreatReserves.stock(donor.getId(), c) - keep);
	}

	/** The stock a market keeps of commodity {@code i} for itself, relays aside: its donor keep and its siege's stock, or a garrison voyage's whole want when that is more (ownWants). */
	protected static float ownKeep(MarketAPI m, int i) {
		String c = ThreatReserves.COMMODITIES[i];
		return Math.max(ThreatReserves.monthsBasis(m, c) * ThreatIncConfig.donorKeepFraction() + siegeStock(m)[i],
				ownWants(m)[i]);
	}

	/**
	 * What a relay holds for the market it passes stock on to: its stock above
	 * its keep and its own siege's needs, up to its relay target. 0 unless it
	 * is that market's relay ({@link #relayPlan}), or under a ground front.
	 */
	protected static float relayHold(MarketAPI relay, MarketAPI to, String commodityId) {
		if (relay == null || to == null || relay.getFaction() == null || relay.isPlayerOwned()) return 0f;
		// a backed depot banks no staging keep (ThreatReserves.stagingBank), so
		// {@link #spare} already offers the whole of it
		if (ThreatGroundFronts.hasFront(relay) || ThreatReserves.isBacked(relay)) return 0f;
		RelayPlan plan = relayPlan(relay.getFaction());
		java.util.Set<String> ids = plan.serves.get(relay.getId());
		if (ids == null || !ids.contains(to.getId())) return 0f;
		int i = ThreatAid.index(commodityId);
		float[] t = plan.target.get(relay.getId());
		if (i < 0 || t == null) return 0f;
		float above = ThreatReserves.stock(relay.getId(), commodityId) - ownKeep(relay, i);
		return Math.max(0f, Math.min(t[i], above));
	}

	/**
	 * A REFERENCE load (convoyMarineCapacity / convoyCargoCapacity): the unit
	 * the "worth a sailing" floors are measured in ({@link #minLoad}, the
	 * planner's shortfall test, {@link #pickAllyDonor}) and the Min / Med size
	 * of the player's hand-ordered run to an outpost. Not a ceiling on any
	 * load since 2026-09-29: a convoy carries its whole shortfall or its
	 * donor's whole spare, and {@link #fitHulls} grows the fleet to carry it.
	 */
	public static float capacityFor(String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return ThreatIncConfig.convoyMarineCapacity();
		return ThreatIncConfig.convoyCargoCapacity();
	}

	/** What a base is short of its targets, net of its stock and of what is already at sea to it ({@link #inbound}). */
	protected static float[] shortfall(MarketAPI base, float[] targets) {
		float[] at = inbound(base.getId());
		float[] out = new float[ThreatReserves.COMMODITIES.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = targets[i] - ThreatReserves.stock(base.getId(), ThreatReserves.COMMODITIES[i]) - at[i];
		}
		return out;
	}

	/** Whether a shortfall is worth a sailing: convoyMinLoadFraction of a reference load, or of the whole target when that is smaller. */
	protected static boolean worthSailing(String commodityId, float shortBy, float target) {
		return shortBy > 0f
				&& shortBy >= ThreatIncConfig.convoyMinLoadFraction() * Math.min(capacityFor(commodityId), target);
	}

	/**
	 * One planning pass: for each mobilised faction, each staging base short
	 * of its targets net of what is already at sea to it, neediest first (as
	 * a fraction of the target); then, for as long as it is still short and a
	 * donor it has not drawn on this pass can spare some - the colony with the
	 * most of its neediest commodity, another staging base's stock above its
	 * own siege's needs included - a convoy of everything that donor can
	 * spare that the base still wants. Several donors mean several convoys in
	 * parallel (2026-09-29: one convoy per base, each capped at a hull load,
	 * turned a staging target of tens of thousands into a queue of round
	 * trips - the main throttle on staging).
	 */
	public static void planLogistics(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.convoyEnabled()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null) continue;
			List<MarketAPI> markets = ThreatReserves.marketsOf(factionId);
			// the bases short of the most (as a share of their target) sail
			// first, so the donors' stock goes to them first. No cap on
			// sailings: every one needs a donor with the stock to spare (the
			// per-tick cap of 2 held Hegemony's fronts back, 2026-09-27)
			List<Object[]> wants = new ArrayList<Object[]>();
			for (MarketAPI base : markets) {
				float[] targets = stagingTargets(base);
				float[] shortBy = shortfall(base, targets);
				// every commodity worth a sailing, neediest first: a base whose
				// worst need no donor holds still takes the next - before
				// 2026-09-24 Chicomoztoc, short of fuel nobody banked, got no
				// convoy at all while donors sat on marines it needed
				final float[] fractionShort = new float[ThreatReserves.COMMODITIES.length];
				List<Integer> order = new ArrayList<Integer>();
				for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
					if (!worthSailing(ThreatReserves.COMMODITIES[i], shortBy[i], targets[i])) continue;
					fractionShort[i] = shortBy[i] / Math.max(1f, targets[i]);
					order.add(Integer.valueOf(i));
				}
				if (order.isEmpty()) continue;
				java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
					public int compare(Integer a, Integer b) {
						return Float.compare(fractionShort[b], fractionShort[a]);
					}
				});
				wants.add(new Object[] {base, targets, order, Float.valueOf(fractionShort[order.get(0)])});
			}
			java.util.Collections.sort(wants, new java.util.Comparator<Object[]>() {
				public int compare(Object[] a, Object[] b) {
					return Float.compare((Float) b[3], (Float) a[3]);
				}
			});
			// fronts first: an army in the field outranks a depot for the
			// donors' stock (seen in-game 2026-09-04 - staging traffic drew the
			// donors dry and the front starved)
			planFrontRuns(faction, random);
			// then an own world under Threat invasion, then outposts shipping a
			// purged system's stock home - all before any depot
			planRelief(faction, random);
			planOutpostReturns(faction, random);
			for (Object[] w : wants) {
				MarketAPI base = (MarketAPI) w[0];
				float[] targets = (float[]) w[1];
				@SuppressWarnings("unchecked")
				List<Integer> order = (List<Integer>) w[2];
				// each donor sails once per pass, so the loop ends within the
				// faction's market count however the draws fall
				java.util.Set<MarketAPI> drawn = new java.util.HashSet<MarketAPI>();
				while (drawn.size() < markets.size()) {
					// net of everything at sea to it, the convoys just sailed
					// and any relief or outpost return planned above included
					float[] shortBy = shortfall(base, targets);
					MarketAPI donor = null;
					for (Integer i : order) {
						String c = ThreatReserves.COMMODITIES[i];
						if (!worthSailing(c, shortBy[i], targets[i])) continue;
						donor = pickDonor(markets, base, c, drawn);
						if (donor != null) break;
					}
					if (donor == null) break;
					drawn.add(donor);
					float[] load = new float[ThreatReserves.COMMODITIES.length];
					for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
						if (shortBy[i] <= 0f) continue;
						load[i] = Math.min(shortBy[i], sendable(donor, base, ThreatReserves.COMMODITIES[i]));
					}
					dispatch(donor, base, faction, load, random);
				}
			}
		}
	}

	/**
	 * RELIEF: an own colony with a Threat army on its surface comes before
	 * every depot. Its banked marines already fight
	 * (ThreatGroundFronts.defenderStrength), so every marine landed there is
	 * defence. The want is what the colony's counter-attack needs to beat the
	 * army (ThreatGroundFronts.reliefNeed) less what is already at sea to it;
	 * every colony in reach that can spare marines sends what it can of that,
	 * the richest first, in parallel. Until 2026-09-29 it was one hull load
	 * (convoyMarineCapacity) from one donor at a time per invaded world, and
	 * an NPC donor had to be within the flat convoyRangeLY - now it reaches as
	 * far as its fuel, as the staging planner's donors do.
	 */
	protected static void planRelief(FactionAPI faction, Random random) {
		List<MarketAPI> markets = ThreatReserves.marketsOf(faction.getId());
		for (MarketAPI besieged : markets) {
			if (besieged.getStarSystem() == null || besieged.getPrimaryEntity() == null) continue;
			if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(besieged.getId()))) {
				continue;
			}
			float need = ThreatGroundFronts.reliefNeed(besieged) - inbound(besieged.getId())[0];
			if (need < FRONT_RUN_MIN_MARINES) continue;
			final Map<MarketAPI, Float> spares = new HashMap<MarketAPI, Float>();
			for (MarketAPI d : markets) {
				if (d == besieged || d.getStarSystem() == null || d.getPrimaryEntity() == null) continue;
				float reach = faction.isPlayerFaction() ? Float.MAX_VALUE
						: Math.max(ThreatIncConfig.convoyRangeLY(), IncursionManager.expeditionRangeLY(d));
				if (Misc.getDistanceLY(d.getStarSystem().getLocation(),
						besieged.getStarSystem().getLocation()) > reach) continue;
				float s = spare(d, Commodities.MARINES);
				if (s >= FRONT_RUN_MIN_MARINES) spares.put(d, s);
			}
			List<MarketAPI> donors = new ArrayList<MarketAPI>(spares.keySet());
			java.util.Collections.sort(donors, new java.util.Comparator<MarketAPI>() {
				public int compare(MarketAPI a, MarketAPI b) {
					return Float.compare(spares.get(b), spares.get(a));
				}
			});
			int sailed = 0;
			float carried = 0f;
			MarketAPI first = null;
			for (MarketAPI donor : donors) {
				if (need < FRONT_RUN_MIN_MARINES) break;
				float[] load = new float[ThreatReserves.COMMODITIES.length];
				load[0] = Math.min(need, spares.get(donor));
				Convoy c = dispatch(donor, besieged, faction, load, random);
				if (c == null) continue;
				// every fleet of the sailing, when the load was split across several
				float lifted = carried(c)[0];
				need -= lifted;
				carried += lifted;
				sailed++;
				if (first == null) first = donor;
				ThreatIncConfig.log("Relief convoy: " + faction.getId() + " " + (int) lifted + " marines "
						+ donor.getName() + " -> " + besieged.getName());
			}
			// only the player's own: another faction's relief is progress, not news
			if (sailed > 0 && faction.isPlayerFaction()) {
				ThreatNotice n = ThreatNotice.titled(sailed > 1 ? "Relief Convoys Sail" : "Relief Convoy Sails")
						.icon(faction);
				if (sailed > 1) {
					n.line("Your %s convoys carry %s marines", "" + sailed, Misc.getWithDGS((int) carried));
				} else {
					n.line("Your convoy carries %s marines from %s", Misc.getWithDGS((int) carried),
							ThreatNotice.market(first));
				}
				n.line("To the defence of %s", ThreatNotice.market(besieged)).send();
			}
		}
	}

	/**
	 * An outpost's stockpile is the forward base for front runs in its own
	 * system; once that system holds no hive there is nothing left to run to,
	 * so the stock ships home to the faction's nearest base - the outflow that
	 * keeps a ground victory's survivors in the war instead of in a station
	 * nothing can draw from. The whole stock sails at once, the fleet grown to
	 * carry it (2026-09-29: one hull load per convoy, one convoy per outpost
	 * at a time, left most of a big garrison sitting in the station for
	 * months); whatever the hulls could not take sails on the next pass.
	 */
	protected static void planOutpostReturns(FactionAPI faction, Random random) {
		for (ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(faction.getId())) {
			if (!o.alive() || o.entity == null || !ThreatOutposts.hasStock(o)) continue;
			if (!ThreatIncData.getLiveColonyMarkets(o.systemId).isEmpty()) continue; // still a forward base
			ThreatBases.Base from = ThreatBases.of(o);
			if (from == null) continue;
			MarketAPI home = outpostHome(faction, o);
			if (home == null) continue;
			float[] load = new float[ThreatReserves.COMMODITIES.length];
			for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
				load[i] = ThreatReserves.stock(from.id(), ThreatReserves.COMMODITIES[i]);
			}
			Convoy c = dispatch(from, home, faction, load, random, null, false);
			if (c == null) continue;
			ThreatIncConfig.log("Outpost stock shipping home: " + from.name() + " -> "
					+ home.getName());
		}
	}

	/** Where an outpost's stock ships home once its system holds no hive: the nearest base, else the nearest colony. */
	public static MarketAPI outpostHome(FactionAPI faction, ThreatOutposts.Outpost o) {
		if (faction == null || o == null || o.entity == null) return null;
		MarketAPI home = ThreatFleetOrders.pickBase(faction, o.entity.getLocationInHyperspace());
		if (home == null) home = nearestColony(faction, o.entity.getLocationInHyperspace());
		return home;
	}

	/** The faction's nearest colony to a hyperspace location, military or not. */
	protected static MarketAPI nearestColony(FactionAPI faction, Vector2f hyperLoc) {
		if (hyperLoc == null) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), hyperLoc);
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// front runs: supply and withdrawal (docs/design-theory.md 8.3)
	// ------------------------------------------------------------------

	protected static boolean convoyBoundForFront(String hiveMarketId) {
		for (Convoy c : all()) {
			if (hiveMarketId.equals(c.frontMarketId)) return true;
		}
		return false;
	}

	/** Whether a withdrawal run is already on its way to this front. */
	protected static boolean pickupBoundFor(String hiveMarketId) {
		for (Convoy c : all()) {
			if (c.pickup && hiveMarketId.equals(c.frontMarketId)) return true;
		}
		return false;
	}

	/** [marines, armaments] aboard the supply runs already at sea to this front (the hold, while the fleet lives). */
	protected static float[] inboundFront(String hiveMarketId) {
		float[] out = new float[2];
		for (Convoy c : all()) {
			if (c.pickup || !hiveMarketId.equals(c.frontMarketId)) continue;
			CargoAPI cargo = c.fleet != null && c.fleet.isAlive() ? c.fleet.getCargo() : null;
			out[0] += cargo != null ? cargo.getMarines() : c.marines;
			out[1] += cargo != null ? cargo.getCommodityQuantity(Commodities.HAND_WEAPONS) : c.armaments;
		}
		return out;
	}

	/** What a friendly front wants brought: [marines, armaments]; a withdrawal call wants a pickup instead. */
	public static float[] frontWants(ThreatGroundFronts.GroundFront front, MarketAPI hive) {
		// the whole peak back, or what holds it, whichever is more (2026-09-29:
		// runs reinforced only to frontReinforceFraction, 0.8, of the peak - an
		// army was never let back to the strength it landed at)
		float marines = Math.max(ThreatGroundFronts.landedStrength(front) - front.marines,
				ThreatGroundFronts.holdGap(front, hive));
		// armaments for the army the run leaves behind, not the depleted one:
		// a front reinforced back toward its peak burns at the peak's rate
		float strength = Math.max(ThreatGroundFronts.landedStrength(front),
				front.marines + Math.max(0f, marines));
		// at the rate it actually burns: a pushing front burns frontPushUpkeepMult
		// times as fast, and runs sized at the dug-in rate carried ~20 days of a
		// push that the next run took 22-45 days to follow (run 11, Beta Vigri I
		// dry six times)
		float burn = ThreatGroundFronts.dailyUpkeep(strength);
		if (ThreatGroundFronts.STANCE_PUSH.equals(front.stance)) burn *= ThreatIncConfig.frontPushUpkeepMult();
		float armaments = ThreatIncConfig.frontResupplyDays() * burn - front.armaments;
		return new float[] {Math.max(0f, marines), Math.max(0f, armaments)};
	}

	/**
	 * Where a front run sails from. An OUTPOST of the faction in the hive's
	 * own system is the forward base: it is already there, and a ground
	 * victory next door leaves its garrison stockpile behind, so a run that
	 * the outpost can actually cover loads there instead of crossing
	 * light-years - and a pickup lands the front in it rather than shipping it
	 * home. An outpost too thin to cover the run falls through to the nearest
	 * military colony in reach, as before - never a link: its stock dies with
	 * its station (run 16 lost a withdrawn front's survivors that way), so a
	 * link that is nearest gives way to the nearest colony base, as
	 * {@code ThreatGroundFronts.evacuate} falls back.
	 */
	protected static ThreatBases.Base pickFrontBase(FactionAPI faction, MarketAPI hive,
			boolean pickup, float[] wants) {
		return pickFrontBase(faction, hive, pickup, wants, null);
	}

	/** As above, passing over the bases in {@code skip} (ids of those already drawn on this pass). */
	protected static ThreatBases.Base pickFrontBase(FactionAPI faction, MarketAPI hive,
			boolean pickup, float[] wants, java.util.Set<String> skip) {
		ThreatOutposts.Outpost o = ThreatOutposts.outpostIn(faction.getId(), hive.getStarSystem());
		ThreatBases.Base forward = o != null ? ThreatBases.of(o) : null;
		if (forward != null && (skip == null || !skip.contains(forward.id()))) {
			if (pickup) return forward;
			float marines = Math.min(wants[0], ThreatOutposts.stock(o, Commodities.MARINES));
			float armaments = Math.min(wants[1],
					ThreatOutposts.stock(o, Commodities.HAND_WEAPONS));
			if (marines >= FRONT_RUN_MIN_MARINES || armaments >= FRONT_RUN_MIN_ARMAMENTS) {
				return forward;
			}
		}
		MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
		// a link in the hive's own system is always nearest, and pickBase takes it
		if (ThreatFrontlines.isOutpost(base)) base = colonyBase(faction, hive.getLocationInHyperspace(), base);
		if (base != null && skip != null && skip.contains(base.getId())) base = null;
		if (pickup || faction.isPlayerFaction() || hive.getStarSystem() == null) return ThreatBases.of(base);
		// an NPC front loads where the most of what it wants is: the nearest base,
		// or any of the faction's markets in reach of the hive (the siege pool,
		// IncursionManager.siegeDonors). One base alone let Hegemony's Loka front
		// go dry and fall with 15k marines banked elsewhere (run 9)
		MarketAPI best = base;
		float bestScore = base != null ? frontScore(base, wants) : -1f;
		for (MarketAPI m : IncursionManager.factionMarketsInReach(faction, hive.getStarSystem())) {
			if (m == base || ThreatFrontlines.isOutpost(m)) continue;
			if (skip != null && skip.contains(m.getId())) continue;
			float s = frontScore(m, wants);
			if (s > bestScore) {
				bestScore = s;
				best = m;
			}
		}
		return ThreatBases.of(best);
	}

	/**
	 * The faction's nearest colony base by hyperspace distance, at any range
	 * and never a link; else any colony of theirs, else {@code link} itself -
	 * the fallback {@code ThreatGroundFronts.evacuate} lands survivors by.
	 */
	protected static MarketAPI colonyBase(FactionAPI faction, Vector2f hyperLoc, MarketAPI link) {
		MarketAPI base = null, colony = null;
		float best = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (ThreatFrontlines.isOutpost(m)) continue;
			if (colony == null) colony = m;
			if (!IncursionManager.isBase(m) || m.getPrimaryEntity() == null) continue;
			float d = Misc.getDistanceLY(hyperLoc, m.getLocationInHyperspace());
			if (d < best) {
				best = d;
				base = m;
			}
		}
		return base != null ? base : colony != null ? colony : link;
	}

	/** How much of a front run's wants a market covers, 0-2 (marines + armaments, each as a share). */
	protected static float frontScore(MarketAPI m, float[] wants) {
		float marines = wants[0] > 0f
				? Math.min(1f, ThreatReserves.available(m, Commodities.MARINES) / wants[0]) : 0f;
		float arms = wants[1] > 0f
				? Math.min(1f, ThreatReserves.available(m, Commodities.HAND_WEAPONS) / wants[1]) : 0f;
		return marines + arms;
	}

	/**
	 * Every friendly front of a mobilised faction with no pickup already bound
	 * for it: a withdrawal call gets a pickup (once any supply run at sea has
	 * landed - its landing may call the withdrawal off), a hungry front gets
	 * supply runs for what it wants net of the runs already at sea to it -
	 * from the faction's forward outpost or best base in reach first, then
	 * the next best for whatever that one could not cover, each out of its
	 * stock above a colony's floor. Until 2026-09-29 a front took one run at
	 * a time, each capped at a hull load (convoyMarineCapacity /
	 * convoyCargoCapacity).
	 */
	protected static void planFrontRuns(FactionAPI faction, Random random) {
		if (!ThreatIncConfig.frontRunsEnabled()) return;
		for (ThreatGroundFronts.GroundFront front
				: new ArrayList<ThreatGroundFronts.GroundFront>(ThreatGroundFronts.fronts().values())) {
			String owner = front.factionId != null ? front.factionId
					: com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER;
			if (!faction.getId().equals(owner)) continue;
			if (pickupBoundFor(front.marketId)) continue;
			MarketAPI hive = ThreatIncData.resolveColonyMarket(front.marketId);
			if (hive == null || hive.getStarSystem() == null) continue;
			// a contested orbit is a closed door: the navy clears it first
			// (one Support sortie per world at a time), and next tick's run goes in
			if (canRunTo(faction, hive) != null) {
				ThreatIncConfig.logQuiet("fr_door_" + front.marketId, "Front run for " + hive.getName()
						+ " held back: orbit contested and no cover");
				supportFor(faction, hive);
				continue;
			}
			if (front.withdrawRequested) {
				if (convoyBoundForFront(front.marketId)) continue; // a supply run lands first
				ThreatBases.Base to = pickFrontBase(faction, hive, true, new float[] {0f, 0f});
				if (to == null) continue;
				dispatchFrontRun(to, hive, front, faction, new float[] {0f, 0f}, true, random);
				continue;
			}
			float[] wants = frontWants(front, hive);
			float[] atSea = inboundFront(front.marketId);
			wants[0] = Math.max(0f, wants[0] - atSea[0]);
			wants[1] = Math.max(0f, wants[1] - atSea[1]);
			float upkeepDays = ThreatGroundFronts.dailyUpkeep(front) > 0f
					? wants[1] / ThreatGroundFronts.dailyUpkeep(front) : 0f;
			// not worth a sailing for less than a few days of armaments or a handful of marines
			// - unless it cannot hold without them (and nothing at sea is bringing it)
			boolean covered = atSea[0] > 0f || atSea[1] > 0f;
			if (upkeepDays < 10f && wants[0] < 100f
					&& (ThreatGroundFronts.frontCanHold(front, hive) || covered)) continue;
			// each base sails once per pass, so the loop ends within the
			// faction's market count (plus its forward outpost)
			java.util.Set<String> drawn = new java.util.HashSet<String>();
			int bases = ThreatReserves.marketsOf(faction.getId()).size() + 1;
			for (int n = 0; n < bases; n++) {
				ThreatBases.Base base = pickFrontBase(faction, hive, false, wants, drawn);
				if (base == null) {
					if (n == 0) {
						ThreatIncConfig.logQuiet("fr_nobase_" + front.marketId, "Front run for " + hive.getName()
								+ ": no base of " + faction.getId() + " in reach");
					}
					break;
				}
				drawn.add(base.id());
				float[] load = new float[] {
						Math.min(wants[0], ThreatBases.available(base, Commodities.MARINES)),
						Math.min(wants[1], ThreatBases.available(base, Commodities.HAND_WEAPONS))};
				if (load[0] < FRONT_RUN_MIN_MARINES && load[1] < FRONT_RUN_MIN_ARMAMENTS) {
					if (n == 0) {
						ThreatIncConfig.logQuiet("fr_thin_" + front.marketId, "Front run for " + hive.getName()
								+ ": wants " + (int) wants[0] + " marines, " + (int) wants[1]
								+ " armaments; the best source (" + base.name() + ") has " + (int) load[0]
								+ " and " + (int) load[1]);
					}
					break;
				}
				Convoy c = dispatchFrontRun(base, hive, front, faction, load, false, random);
				if (c == null) continue;
				float[] sent = carried(c);
				wants[0] = Math.max(0f, wants[0] - sent[0]);
				wants[1] = Math.max(0f, wants[1] - sent[1]);
				if (wants[0] < FRONT_RUN_MIN_MARINES && wants[1] < FRONT_RUN_MIN_ARMAMENTS) break;
			}
		}
	}

	/**
	 * Why no run can sail to this world now, or null. A run is REFUSED
	 * outright while Defense Swarms hold the orbit and nothing friendly is
	 * there to clear it - the fleet does not sail to idle at the jump-point.
	 * The in-flight wait in {@link #pollFrontRun} stays as the fallback for
	 * runs already at sea when the orbit closes behind them.
	 */
	public static String canRunTo(FactionAPI faction, MarketAPI hive) {
		if (faction == null || hive == null) return "No front there.";
		if (!ThreatGroundFronts.orbitContested(hive.getId())) return null;
		if (ThreatFleetOrders.friendlyOrbit(faction.getId(), hive.getId())) return null;
		// an autoresolved siege's flotilla holding the orbit over its own front
		// (run 9: Loka's run waited at the door while 5,900 FP of cover held it)
		if (ThreatGroundFronts.coverHolds(hive, faction.getId())) return null;
		return "Orbit contested - send Support or Defend first.";
	}

	/**
	 * Sends one Support sortie to clear a contested orbit, if none is out
	 * already (a Defend on its way there counts). Sized to the orbit by
	 * ThreatFleetOrders.buildSortie; held back only while the base's stock
	 * cannot provision that much (sortieReachFP), which saves a build and a
	 * refund every tick. (2026-09-29: it was refused outright whenever the
	 * orbit needed more than one guardFleetFP task force - the sortie was the
	 * fixed size, not the need - so a front under a strong swarm never got
	 * its door opened and starved.)
	 */
	protected static boolean supportFor(FactionAPI faction, MarketAPI hive) {
		if (!ThreatIncConfig.supportEnabled()) return false;
		if (ThreatFleetOrders.hasSupport(faction.getId(), hive.getId())) return false;
		if (ThreatFleetOrders.hasDefend(faction.getId(), hive.getId())) return false;
		float need = IncursionManager.siegeOrbitNeeded(faction, java.util.Collections.singletonList(hive));
		MarketAPI base = ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
		if (base != null && need > 0f) {
			float reach = ThreatFleetOrders.sortieReachFP(base, hive.getLocationInHyperspace());
			if (need > reach) {
				ThreatIncConfig.logQuiet("fr_nosupport_" + hive.getId(), "Support for " + hive.getName()
						+ " waits: " + (int) need + " FP of orbit to clear, " + base.getName()
						+ " can provision " + (int) reach);
				return false;
			}
		}
		return ThreatFleetOrders.dispatchSupport(faction, hive) != null;
	}

	/**
	 * What the board's Supply order asks the base for at a load tier: what
	 * the front wants, floored to a worthwhile run (Min); that times
	 * convoyExtraLoadFactor (Med); everything the base can spare above its
	 * floor, wanted or not (Max - a hull load until 2026-09-29, when the
	 * per-convoy cap went). The ask is then capped only by the base's stock
	 * above its floor; the run's hulls grow to carry it.
	 */
	public static float[] supplyAsk(ThreatGroundFronts.GroundFront front, MarketAPI hive, int tier) {
		if (tier >= 2) {
			FactionAPI owner = front != null
					? Global.getSector().getFaction(ThreatGroundFronts.ownerOf(front)) : null;
			ThreatBases.Base base = owner != null && hive != null
					? pickFrontBase(owner, hive, false, frontWants(front, hive)) : null;
			if (base == null) return new float[] {0f, 0f};
			return new float[] {ThreatBases.available(base, Commodities.MARINES),
					ThreatBases.available(base, Commodities.HAND_WEAPONS)};
		}
		float[] wants = frontWants(front, hive);
		// a hand order always sends a worthwhile load, wanted or not
		float[] asked = new float[] {Math.max(wants[0], 200f),
				Math.max(wants[1], ThreatGroundFronts.dailyUpkeep(front) * 30f)};
		if (tier == 1) {
			float mult = ThreatIncConfig.convoyExtraLoadFactor();
			asked[0] *= mult;
			asked[1] *= mult;
		}
		return asked;
	}

	/**
	 * The board's Supply order: a run to this front now, from the faction's
	 * outpost in the hive's system or the nearest base, carrying what the load tier asks for. Null if the orbit is
	 * contested with nothing friendly holding it, no base is in reach, or it
	 * has nothing above its floor to send.
	 */
	public static Convoy supplyFront(MarketAPI hive, FactionAPI faction, int tier, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		if (canRunTo(faction, hive) != null) return null;
		float[] asked = supplyAsk(front, hive, tier);
		ThreatBases.Base base = pickFrontBase(faction, hive, false, asked);
		if (base == null) return null;
		float[] load = new float[] {
				Math.min(asked[0], ThreatBases.available(base, Commodities.MARINES)),
				Math.min(asked[1], ThreatBases.available(base, Commodities.HAND_WEAPONS))};
		if (load[0] <= 0f && load[1] <= 0f) return null;
		return dispatchFrontRun(base, hive, front, faction, load, false, random);
	}

	/** Why the board's Supply order at this load tier would send nothing now, or null if a run would sail. */
	public static String supplyBlockReason(FactionAPI faction, MarketAPI hive, int tier) {
		ThreatGroundFronts.GroundFront front = hive != null
				? ThreatGroundFronts.getFront(hive.getId()) : null;
		if (front == null || faction == null) return "No front there.";
		if (!ThreatIncConfig.frontRunsEnabled()) return "Front runs are disabled in the mod settings.";
		if (convoyBoundForFront(hive.getId())) return "A run is already on its way there.";
		String refused = canRunTo(faction, hive);
		if (refused != null) return refused;
		float[] wants = frontWants(front, hive);
		// a run only sails for what the front is short of: marines back toward
		// its peak, armaments up to frontResupplyDays. Max is the player asking
		// for everything the base can spare whether the front wants it or not,
		// so it always sails.
		if (tier < 2 && wants[0] <= 0f && wants[1] <= 0f) {
			return "The front wants nothing: at strength and stocked for "
					+ (int) ThreatIncConfig.frontResupplyDays() + " days.";
		}
		float[] asked = supplyAsk(front, hive, tier);
		ThreatBases.Base base = pickFrontBase(faction, hive, false, asked);
		boolean marinesOk = asked[0] > 0f && base != null
				&& ThreatBases.available(base, Commodities.MARINES) > 0f;
		boolean armsOk = asked[1] > 0f && base != null
				&& ThreatBases.available(base, Commodities.HAND_WEAPONS) > 0f;
		if (!marinesOk && !armsOk) {
			return "No base in reach has marines or heavy armaments above its floor to send.";
		}
		return null;
	}

	/** Why the board's Pull out order would send nothing now, or null if a run would sail. */
	public static String pullOutBlockReason(FactionAPI faction, MarketAPI hive) {
		ThreatGroundFronts.GroundFront front = hive != null
				? ThreatGroundFronts.getFront(hive.getId()) : null;
		if (front == null || faction == null) return "No front there.";
		if (!ThreatIncConfig.frontRunsEnabled()) return "Front runs are disabled in the mod settings.";
		if (convoyBoundForFront(hive.getId())) return "A run is already on its way there.";
		String refused = canRunTo(faction, hive);
		if (refused != null) return refused;
		if (pickFrontBase(faction, hive, true, new float[] {0f, 0f}) == null) return "No base in reach.";
		return null;
	}

	/** The board's Pull out order: a pickup run for this front now. Refused on a contested orbit. */
	public static Convoy pullOutFront(MarketAPI hive, FactionAPI faction, Random random) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(hive.getId());
		if (front == null || faction == null || !ThreatIncConfig.frontRunsEnabled()) return null;
		if (convoyBoundForFront(hive.getId())) return null;
		if (canRunTo(faction, hive) != null) return null;
		ThreatBases.Base base = pickFrontBase(faction, hive, true, new float[] {0f, 0f});
		if (base == null) return null;
		front.withdrawRequested = true;
		return dispatchFrontRun(base, hive, front, faction, new float[] {0f, 0f}, true, random);
	}

	/**
	 * Builds the run at the base: transports and freighters sized to the load
	 * (or, for a pickup, to the front it will lift), escorted by cargo value.
	 * Sails for the hive system's jump-point; poll() takes it from there. The
	 * base may be a colony or an outpost - a run out of an outpost spawns at
	 * the station and draws from its stockpile, which has no floor.
	 *
	 * <p>A supply load past what one fleet under vanilla's maxShipsInAIFleet
	 * carries sails as several runs in parallel ({@link #nextHulls}), each
	 * paying its own escort; the first is returned, with the whole sailing on
	 * {@link Convoy#sailing}. A pickup is one fleet, grown to the limit: the
	 * front is lifted whole by whichever run lands, and what its berths
	 * cannot hold rides home aboard it rather than being split off a front
	 * still fighting.
	 */
	protected static Convoy dispatchFrontRun(ThreatBases.Base base, MarketAPI hive,
			ThreatGroundFronts.GroundFront front, FactionAPI faction, float[] load, boolean pickup,
			Random random) {
		StarSystemAPI system = base.starSystem();
		SectorEntityToken from = base.entity();
		SectorEntityToken door = ThreatFleetOrders.interceptPoint(hive.getStarSystem());
		if (system == null || from == null || door == null) return null;

		// (2026-09-29: closed economy) an NPC run's escort is what the base pays for
		boolean paysEscort = !faction.isPlayerFaction();
		float ly = Misc.getDistanceLY(base.hyperLoc(), hive.getLocationInHyperspace());
		if (pickup) {
			// the run grows to the whole front it will lift (2026-09-29), up to the limit
			float[] ask = {Math.max(100f, front.marines), Math.max(100f, front.armaments), 0f, 0f};
			Hulls h = buildHulls(base, faction, ask, ask, ly, paysEscort, true, true, random);
			return h != null ? sailFrontRun(h, base, hive, faction, true, paysEscort, ly, door, random) : null;
		}
		float[] whole = {load[0], load[1], 0f, 0f};
		float[] remaining = whole.clone();
		Split split = new Split(whole);
		Convoy lead = null;
		float[] sailed = new float[whole.length];
		int fleets = 0;
		// ends: every run that sails carries its whole ask of at least one unit
		// or the loop stops (sailed())
		while (true) {
			Hulls h = nextHulls(split, remaining, base, faction, ly, paysEscort, true, random);
			if (h == null) break;
			Convoy c = sailFrontRun(h, base, hive, faction, false, paysEscort, ly, door, random);
			if (c == null) break;
			if (lead == null) lead = c;
			fleets++;
			if (!sailed(h, c, remaining, sailed)) break;
		}
		if (lead != null && fleets > 1) {
			lead.sailing = sailed;
			ThreatIncConfig.log("Supply run split: " + faction.getId() + " " + base.name() + " -> "
					+ hive.getName() + " in " + fleets + " fleets (" + (int) sailed[0] + " marines, "
					+ (int) sailed[1] + " armaments)");
		}
		return lead;
	}

	/** One front run of the sailing: the fleet paid for, placed at the base, loaded (a supply run) and sent for the door. */
	protected static Convoy sailFrontRun(Hulls h, ThreatBases.Base base, MarketAPI hive, FactionAPI faction,
			boolean pickup, boolean paysEscort, float ly, SectorEntityToken door, Random random) {
		CampaignFleetAPI fleet = h.fleet;
		float[] load = h.ask;
		float escort = builtEscort(h);
		if (paysEscort) payEscort(fleet, base, escort, ly);
		StarSystemAPI system = base.starSystem();
		SectorEntityToken from = base.entity();

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName(pickup ? "Evacuation Convoy" : "Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = base.id();
		c.toMarketId = hive.getId();
		c.frontMarketId = hive.getId();
		c.pickup = pickup;
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();
		if (!pickup) {
			CargoAPI cargo = fleet.getCargo();
			int m = (int) Math.min(load[0], cargo.getFreeCrewSpace());
			if (m > 0) {
				int taken = (int) ThreatBases.draw(base, Commodities.MARINES, m);
				if (taken > 0) cargo.addMarines(taken);
				c.marines = taken;
			}
			int a = (int) Math.min(load[1], cargo.getSpaceLeft());
			if (a > 0) {
				int taken = (int) ThreatBases.draw(base, Commodities.HAND_WEAPONS, a);
				if (taken > 0) cargo.addCommodity(Commodities.HAND_WEAPONS, taken);
				c.armaments = taken;
			}
			if (c.marines <= 0f && c.armaments <= 0f) {
				refundEscort(fleet, base);
				Misc.fadeAndExpire(fleet);
				return null;
			}
		}
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, door, 1000f,
				(pickup ? "withdrawal run to " : "supply run to ") + hive.getName());
		all().add(c);
		ThreatIncConfig.log((pickup ? "Withdrawal run dispatched: " : "Supply run dispatched: ")
				+ faction.getId() + " " + base.name() + " -> " + hive.getName() + " ("
				+ (int) c.marines + " marines, " + (int) c.armaments + " armaments; escort "
				+ (int) escort + " FP, " + fleet.getFleetData().getNumMembers() + " ships)");
		ThreatRaiders.consider(c, random);
		return c;
	}

	/**
	 * A front run inside the hive system: wait at the door while Defense
	 * Swarms hold the orbit (up to frontRunWaitDays, then home), run in when
	 * it clears, and on arrival either land the cargo on the front or lift
	 * the front off. Returns true when the convoy was handled (or removed).
	 */
	protected static void pollFrontRun(Convoy c) {
		CampaignFleetAPI fleet = c.fleet;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(c.frontMarketId);
		MarketAPI hive = ThreatIncData.resolveColonyMarket(c.frontMarketId);
		if (front == null || hive == null || hive.getPrimaryEntity() == null) {
			// the front died, won, or was pulled out by hand: nothing to do there
			returnHome(c);
			return;
		}
		SectorEntityToken planet = hive.getPrimaryEntity();
		if (fleet.getContainingLocation() != planet.getContainingLocation()) return; // en route
		if (!c.runningIn) {
			// the same door canRunTo keeps: our own escort or orbit cover over the
			// planet lets the run in
			if (canRunTo(fleet.getFaction(), hive) != null) {
				long now = Global.getSector().getClock().getTimestamp();
				if (c.waitSinceTimestamp == 0L) {
					c.waitSinceTimestamp = now;
					ThreatIncConfig.log("Front run waiting at the door of " + hive.getName()
							+ ": orbit contested");
				} else if (Global.getSector().getClock().getElapsedDaysSince(c.waitSinceTimestamp)
						> ThreatIncConfig.frontRunWaitDays()) {
					ThreatIncConfig.log("Front run gave up at " + hive.getName()
							+ ": orbit still contested after " + (int) ThreatIncConfig.frontRunWaitDays()
							+ " days");
					returnHome(c);
				}
				return;
			}
			c.runningIn = true;
			fleet.clearAssignments();
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, planet, 1000f,
					"running in to " + hive.getName());
			return;
		}
		if (!ThreatReturns.onLeg(fleet, planet, "running in to " + hive.getName())) return;
		CargoAPI cargo = fleet.getCargo();
		FactionAPI faction = Global.getSector().getFaction(c.factionId);
		if (c.pickup) {
			// the level has to be read before the front is taken apart, and rides
			// home on the fleet so the base it lands at is seasoned by it
			float level = ThreatMarineXP.frontLevel(front);
			int[] rec = ThreatGroundFronts.withdraw(hive.getId());
			if (rec[0] > 0) {
				cargo.addMarines(rec[0]);
				fleet.getMemoryWithoutUpdate().set(ThreatReturns.MEM_MARINE_LEVEL, level);
			}
			if (rec[1] > 0) cargo.addCommodity(Commodities.HAND_WEAPONS, rec[1]);
			ThreatNotice n = ThreatNotice.titled("Ground Forces Lifted Off").icon(faction);
			if (faction != null && faction.isPlayerFaction()) {
				n.line("Your ground forces have left %s", ThreatNotice.market(hive));
			} else {
				n.line("%s ground forces have left %s", ThreatNotice.faction(faction),
						ThreatNotice.market(hive));
			}
			n.line("%s marines and %s heavy armaments are on their way home",
					Misc.getWithDGS(rec[0]), Misc.getWithDGS(rec[1])).send();
			ThreatIncConfig.log("Front withdrawn to fleet at " + hive.getName() + ": " + rec[0]
					+ " marines, " + rec[1] + " armaments");
		} else {
			int marines = cargo.getMarines();
			int armaments = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
			if (marines > 0) cargo.removeMarines(marines);
			if (armaments > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, armaments);
			ThreatGroundFronts.resupply(front, marines, armaments);
			// only the player's own runs: another faction's resupply is progress, not news
			if (faction != null && faction.isPlayerFaction()) {
				ThreatNotice.titled("Supplies Landed").good().icon(faction)
						.line("%s marines and %s heavy armaments reach the front on %s",
								Misc.getWithDGS(marines), Misc.getWithDGS(armaments), ThreatNotice.market(hive))
						.send();
			}
			ThreatIncConfig.log("Supply run delivered at " + hive.getName() + ": " + marines
					+ " marines, " + armaments + " armaments");
		}
		all().remove(c);
		// home on the tracked leg: survivors and any undelivered cargo return to
		// the base - an NPC's nearest base when its own changed hands, not into
		// another faction's depot
		ThreatBases.Base home = homeBase(c);
		ThreatReturns.sendHome(fleet, c.factionId, home != null ? home.id() : c.fromMarketId);
	}

	/**
	 * A player-ordered staging run (the board's Supplies button): fill a
	 * convoy for this colony from the same-faction colony that can spare the
	 * most, at the board's load tier ({@link #stageLoad}), regardless of
	 * whether the destination is a staging base. Returns the convoy or null.
	 */
	public static Convoy stageTo(MarketAPI target, FactionAPI faction, int tier, Random random) {
		return stageTo(ThreatBases.of(target), faction, tier, random);
	}

	/** As above to either kind of base - a colony, or the player's outpost (its station is the depot). */
	public static Convoy stageTo(ThreatBases.Base target, FactionAPI faction, int tier, Random random) {
		MarketAPI donor = stageDonor(target, faction, tier);
		if (donor == null) return null;
		return dispatch(ThreatBases.of(donor), target, faction, stageLoad(donor, target, tier),
				random, null, false);
	}

	/**
	 * What a hand-ordered convoy from this donor carries to this target at a
	 * load tier: what the target is short of its staging target, or of its
	 * reference stock (Min), that times convoyExtraLoadFactor (Med), or
	 * everything the donor can spare (Max - the 2026-09-05 rule, kept as the
	 * override the player reaches for when the shortfall is not the point). A
	 * run to an OUTPOST banks nothing, so nothing there is short of anything:
	 * its Min is a reference load, Med that times convoyExtraLoadFactor.
	 * Every tier is capped by what the donor holds above its sortie floor
	 * (ThreatReserves.available) - no longer by a hull load (2026-09-29).
	 * The planner's rules (the donor keep, a worthwhile load, a
	 * staging base's own siege needs, convoy range) are for the automatic
	 * traffic, not the override. A player donor's load is fitted to its own
	 * free hulls here, so the board quotes exactly what dispatch will carry.
	 */
	public static float[] stageLoad(MarketAPI donor, MarketAPI target, int tier) {
		return stageLoad(donor, ThreatBases.of(target), tier);
	}

	/** As above to either kind of base. */
	public static float[] stageLoad(MarketAPI donor, ThreatBases.Base target, int tier) {
		float[] load = new float[ThreatReserves.COMMODITIES.length];
		if (donor == null || target == null) return load;
		float[] targets = target.market != null ? stagingTargets(target.market)
				: new float[] {0f, 0f, 0f, 0f};
		for (int i = 0; i < load.length; i++) {
			String c = ThreatReserves.COMMODITIES[i];
			float want;
			if (tier >= 2) {
				want = Float.MAX_VALUE; // everything above the floor
			} else if (target.isOutpost()) {
				want = capacityFor(c) * (tier == 1 ? ThreatIncConfig.convoyExtraLoadFactor() : 1f);
			} else {
				float toward = targets[i] > 0f ? targets[i] : ThreatReserves.cap(target.market, c);
				float shortBy = Math.max(0f, toward - ThreatReserves.stock(target.id(), c));
				if (tier == 1) shortBy *= ThreatIncConfig.convoyExtraLoadFactor();
				want = shortBy;
			}
			load[i] = Math.min(want, ThreatReserves.available(donor, c));
		}
		if (donor.isPlayerOwned()) {
			load = ThreatAidCapacity.fitLoad(ThreatAidCapacity.ownFreeFP(donor), load);
		}
		return load;
	}

	/**
	 * The colony a Stage order to this target would load at: the same-faction
	 * colony whose {@link #stageLoad} is worth the most (by cargo value -
	 * marines first), preferring colonies that are not staging bases
	 * themselves; null when nothing anywhere can be spared. The board's
	 * Stage button greys out on null instead of the order failing after
	 * Confirm. No range for the player's hand orders (2026-09-05, the user's
	 * call: fuel reach is too many variables to model; distance costs time
	 * and fuel, not permission) - convoyRangeLY bounds only NPC hand orders,
	 * which no longer exist, and the planner.
	 */
	public static MarketAPI stageDonor(MarketAPI target, FactionAPI faction, int tier) {
		return stageDonor(ThreatBases.of(target), faction, tier);
	}

	/** As above to either kind of base. */
	public static MarketAPI stageDonor(ThreatBases.Base target, FactionAPI faction, int tier) {
		if (target == null || faction == null || !ThreatIncConfig.convoyEnabled()) return null;
		if (target.starSystem() == null) return null;
		if (!ThreatReserves.hasDepot(target)) return null; // nowhere to land it
		float range = faction.isPlayerFaction() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		MarketAPI best = null;
		float bestValue = 0f;
		boolean bestIsStaging = true;
		for (MarketAPI donor : ThreatReserves.marketsOf(faction.getId())) {
			if (donor == target.market || donor.getStarSystem() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					target.starSystem().getLocation()) > range) continue;
			float[] load = stageLoad(donor, target, tier);
			float value = cargoValue(load[0], load[1], load[2], load[3]);
			if (value <= 0f) continue;
			boolean staging = stagingHive(donor) != null;
			// a donor that is not itself a staging base beats one that is
			if (best != null && staging && !bestIsStaging) continue;
			if (best != null && staging == bestIsStaging && value <= bestValue) continue;
			best = donor;
			bestValue = value;
			bestIsStaging = staging;
		}
		return best;
	}

	/** Stock a colony can spare: what it holds above its keep fraction of its own months basis (ThreatReserves.monthsBasis), plus a staging base's own siege needs. */
	public static float spare(MarketAPI donor, String commodityId) {
		// a colony under a ground front gives nothing, as it gives no siege
		// (IncursionManager.siegeDonors): its depot arms the defence and
		// provisions its relief. Nachiketa shipped 2,287 fuel to its staging
		// base with a swarm on it and relief then had nothing to sail on (2026-09-29)
		if (ThreatGroundFronts.hasFront(donor)) return 0f;
		// a staging base keeps its own siege's needs, then gives like any donor.
		// A player base has no staging bank (its stockpile is vanilla's), so its
		// staging target is kept here: without it two player staging bases each
		// shipped the other everything above half its cap, every month (rc1 review)
		float bank = donor.isPlayerOwned() ? stagingTarget(donor, commodityId)
				: ThreatReserves.stagingBank(donor, commodityId);
		float keep = ThreatReserves.monthsBasis(donor, commodityId) * ThreatIncConfig.donorKeepFraction() + bank;
		// and what a garrison's voyage from it waits on (stagingTargets), which
		// is outside the bank so the voyage can spend it
		if (!donor.isPlayerOwned()) keep = Math.max(keep, stagingTarget(donor, commodityId));
		return Math.max(0f, ThreatReserves.stock(donor.getId(), commodityId) - keep);
	}

	/**
	 * What a donor may send a base of one commodity: all it can spare
	 * (2026-09-29: it was clipped to a hull load, convoyMarineCapacity /
	 * convoyCargoCapacity; the fleet now grows to the load in
	 * {@link #fitHulls}). The old EQUALISATION rule (never more than half the gap
	 * between the two stocks, 2026-09-04) is gone: it existed because every
	 * military world was a staging base and they shipped the same goods past
	 * each other, and it made concentrating at one base impossible - the
	 * receiver could never hold more than its donors. Now only the faction's
	 * nearest base for a hive stages ({@link #stagingHive}) and a staging
	 * base donates only what it holds above its own siege's needs
	 * ({@link #spare}; until 2026-09-24 it never donated), so no colony is
	 * both short of a commodity and sparing it: the traffic in each
	 * commodity has one direction and needs no damping. A relay adds what it
	 * holds for this base ({@link #relayHold}): the stock it was stocked with
	 * to pass on, which it spares no one else.
	 */
	public static float sendable(MarketAPI donor, MarketAPI base, String commodityId) {
		return spare(donor, commodityId) + relayHold(donor, base, commodityId);
	}

	/**
	 * The least worth a sailing from this donor (a floor, not a cap):
	 * convoyMinLoadFraction of a reference load, or of what the donor spares
	 * over its months basis (its basis above its keep) when that is smaller.
	 * Before 2026-09-05 it was the fraction of a hull load alone - 1,000
	 * marines or 3,000 units at the defaults - which no colony's reserve ever
	 * reached, so nothing sailed.
	 */
	public static float minLoad(MarketAPI donor, String commodityId) {
		float fullSpare = ThreatReserves.monthsBasis(donor, commodityId)
				* (1f - ThreatIncConfig.donorKeepFraction());
		return ThreatIncConfig.convoyMinLoadFraction()
				* Math.min(capacityFor(commodityId), Math.max(0f, fullSpare));
	}

	/** The planner's donor: the colony in convoy range (the player's at any range) with the most to send; a staging base counts what it holds above its own siege's needs. */
	protected static MarketAPI pickDonor(List<MarketAPI> markets, MarketAPI base, String commodityId) {
		return pickDonor(markets, base, commodityId, null);
	}

	/** As above, passing over the donors in {@code skip} (those already drawn on this pass). */
	protected static MarketAPI pickDonor(List<MarketAPI> markets, MarketAPI base, String commodityId,
			java.util.Set<MarketAPI> skip) {
		MarketAPI best = null;
		float bestSend = 0f;
		float range = base.isPlayerOwned() ? Float.MAX_VALUE : ThreatIncConfig.convoyRangeLY();
		for (MarketAPI donor : markets) {
			if (donor == base || donor.getStarSystem() == null) continue;
			if (skip != null && skip.contains(donor)) continue;
			// a donor reaches as far as its own fuel does (stagingBaseFor)
			float reach = base.isPlayerOwned() ? range
					: Math.max(range, IncursionManager.expeditionRangeLY(donor));
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					base.getStarSystem().getLocation()) > reach) continue;
			float s = sendable(donor, base, commodityId);
			if (s > bestSend) {
				bestSend = s;
				best = donor;
			}
		}
		// not worth a sailing
		if (best != null && bestSend < minLoad(best, commodityId)) return null;
		return best;
	}

	/**
	 * Builds the convoy fleet at the donor, loads the cargo aboard (clamped to
	 * what the hulls can actually carry), draws exactly that from the donor's
	 * reserve, and sends it to the base.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random) {
		return dispatch(donor, base, faction, load, random, null, false);
	}

	/**
	 * As above, bound for another faction's colony: an ally's help, or the
	 * player's aid (docs/player-aid.md). A player convoy is fitted to the
	 * donor's free capacity first and held on the ledger once loaded.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		return dispatch(ThreatBases.of(donor), ThreatBases.of(base), faction, load, random,
				recipientFactionId, aid);
	}

	public static Convoy dispatch(ThreatBases.Base donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		return dispatch(donor, ThreatBases.of(base), faction, load, random, recipientFactionId, aid);
	}

	/**
	 * The convoy itself, from either kind of base: a colony, or an outpost
	 * shipping its stockpile home ({@link #planOutpostReturns}). An outpost has
	 * no capacity ledger and no floor, so its whole stock is loadable, and no
	 * economy, so its hulls are built without a market's quality or size.
	 *
	 * <p>A load past what one fleet under vanilla's maxShipsInAIFleet carries
	 * sails as several convoys in parallel (2026-09-29: fitHulls grew one
	 * fleet to 50-100 ships for a whole shortfall), each paying its own
	 * escort, tracked, and settled like any other; the first is returned,
	 * with the whole sailing on {@link Convoy#sailing}. The total shipped is
	 * the load - only its division into fleets changes.
	 */
	public static Convoy dispatch(ThreatBases.Base donor, ThreatBases.Base base, FactionAPI faction,
			float[] load, Random random, String recipientFactionId, boolean aid) {
		if (donor == null || base == null || faction == null) return null;
		// a player colony's convoy stays the hulls its free points bought - the
		// load is clamped to what they carry, the fleet is never grown past the
		// ledger (nothing sails over-extended), and it is never split; NPC
		// convoys and outpost returns grow to fit their load
		boolean ledger = faction.isPlayerFaction() && donor.market != null;
		if (ledger) {
			// the colony's own hulls: staged warships never fold into a convoy
			load = ThreatAidCapacity.fitLoad(ThreatAidCapacity.ownFreeFP(donor.market), load);
		}
		if (donor.starSystem() == null || donor.entity() == null || base.entity() == null) return null;
		if (load[0] <= 0f && load[1] + load[2] + load[3] <= 0f) return null;

		// (2026-09-29: closed economy) an NPC convoy's escort is what the donor
		// pays for beside the cargo; the player's is the ledger's, as before
		boolean paysEscort = !faction.isPlayerFaction();
		float ly = Misc.getDistanceLY(donor.hyperLoc(), base.hyperLoc());
		if (ledger) {
			Hulls h = buildHulls(donor, faction, load, load, ly, paysEscort, false, false, random);
			return h != null ? sail(h, donor, base, faction, paysEscort, ly, recipientFactionId, aid, random) : null;
		}
		float[] remaining = load.clone();
		Split split = new Split(load);
		Convoy lead = null;
		float[] sailed = new float[load.length];
		int fleets = 0;
		// ends: every convoy that sails carries its whole ask of at least one
		// unit or the loop stops (sailed()), and the load is finite
		while (true) {
			Hulls h = nextHulls(split, remaining, donor, faction, ly, paysEscort, false, random);
			if (h == null) break;
			Convoy c = sail(h, donor, base, faction, paysEscort, ly, recipientFactionId, aid, random);
			if (c == null) break;
			if (lead == null) lead = c;
			fleets++;
			if (!sailed(h, c, remaining, sailed)) break;
		}
		if (lead != null && fleets > 1) {
			lead.sailing = sailed;
			ThreatIncConfig.log("Convoy split: " + faction.getId() + " " + donor.name() + " -> " + base.name()
					+ " in " + fleets + " fleets (" + (int) sailed[0] + " marines, " + (int) sailed[1]
					+ " armaments, " + (int) sailed[2] + " fuel, " + (int) sailed[3] + " supplies)");
		}
		return lead;
	}

	/**
	 * Books what one fleet of a sailing loaded: into {@code sailed}, off
	 * {@code remaining}. True while another fleet should follow - this one
	 * took its whole ask and something is left; a fleet that came up short
	 * (the donor ran dry, or no hull of the role was to be had) ends the
	 * sailing, and the rest waits for the next pass.
	 */
	protected static boolean sailed(Hulls h, Convoy c, float[] remaining, float[] sailed) {
		float[] got = {c.marines, c.armaments, c.fuel, c.supplies};
		boolean whole = true;
		boolean left = false;
		for (int i = 0; i < remaining.length; i++) {
			sailed[i] += got[i];
			remaining[i] = Math.max(0f, remaining[i] - got[i]);
			// loads are whole units: an ask of 40.6 is met by 40
			if (got[i] < (float) Math.floor(h.ask[i])) whole = false;
			if (remaining[i] >= 1f) left = true;
		}
		return whole && left;
	}

	/** One convoy of the sailing: the escort paid for, the fleet placed at the donor, loaded, and sent. */
	protected static Convoy sail(Hulls h, ThreatBases.Base donor, ThreatBases.Base base, FactionAPI faction,
			boolean paysEscort, float ly, String recipientFactionId, boolean aid, Random random) {
		CampaignFleetAPI fleet = h.fleet;
		float[] load = h.ask;
		StarSystemAPI system = donor.starSystem();
		SectorEntityToken from = donor.entity();
		SectorEntityToken to = base.entity();
		// paid before the cargo is drawn: it was sized on the stock beside the load
		float escort = builtEscort(h);
		if (paysEscort) payEscort(fleet, donor, escort, ly);

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName(aid ? "Aid Convoy" : "Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		// load what fits; draw only what was loaded
		CargoAPI cargo = fleet.getCargo();
		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = donor.id();
		c.toMarketId = base.id();
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();
		c.recipientFactionId = recipientFactionId;
		c.aid = aid;

		int m = (int) Math.min(load[0], cargo.getFreeCrewSpace());
		if (m > 0) {
			// aboard is what was drawn: a donor holding less than asked sails less
			int taken = (int) ThreatReserves.draw(donor.id(), Commodities.MARINES, m);
			if (taken > 0) cargo.addMarines(taken);
			c.marines = taken;
		}
		c.armaments = loadCommodity(cargo, donor.id(), Commodities.HAND_WEAPONS, load[1]);
		c.fuel = loadCommodity(cargo, donor.id(), Commodities.FUEL, load[2]);
		c.supplies = loadCommodity(cargo, donor.id(), Commodities.SUPPLIES, load[3]);
		if (c.marines <= 0f && c.armaments <= 0f && c.fuel <= 0f && c.supplies <= 0f) {
			refundEscort(fleet, donor);
			Misc.fadeAndExpire(fleet);
			return null;
		}

		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f,
				"carrying war materiel to " + base.name());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, from, 1000f,
				"returning to " + donor.name());
		all().add(c);
		if (faction.isPlayerFaction() && donor.market != null) {
			float[] loaded = {c.marines, c.armaments, c.fuel, c.supplies};
			ThreatAidCapacity.commit(donor.market, ThreatAidCapacity.convoyPoints(loaded), fleet,
					"convoy to " + base.name());
		}

		ThreatIncConfig.log("Convoy dispatched: " + faction.getId() + " " + donor.name()
				+ " -> " + base.name() + " (" + (int) c.marines + " marines, "
				+ (int) c.armaments + " armaments, " + (int) c.fuel + " fuel, "
				+ (int) c.supplies + " supplies; escort " + (int) escort + " FP, "
				+ fleet.getFleetData().getNumMembers() + " ships)");
		// the hive may come for it
		ThreatRaiders.consider(c, random);
		return c;
	}

	/**
	 * After an off-screen fight vanilla removes hulls but not their cargo:
	 * trim what is aboard to what the surviving ships can carry, so losses
	 * are a fraction of the load (Blackett's constant loss per attack), not
	 * nothing-or-everything. Marines beyond the berths, then provisions
	 * beyond the holds, cheapest first.
	 */
	protected static void trimToHulls(Convoy c) {
		CargoAPI cargo = c.fleet.getCargo();
		int overCrew = -cargo.getFreeCrewSpace();
		if (overCrew > 0) {
			int drop = Math.min(cargo.getMarines(), overCrew);
			if (drop > 0) {
				cargo.removeMarines(drop);
				ThreatIncConfig.log("Convoy mauled: " + drop + " marines lost with their transports ("
						+ c.fromName() + " -> " + c.toName() + ")");
			}
		}
		float over = cargo.getSpaceUsed() - cargo.getMaxCapacity();
		if (over <= 0f) return;
		String[] order = {Commodities.SUPPLIES, Commodities.FUEL, Commodities.HAND_WEAPONS};
		for (String id : order) {
			if (over <= 0f) break;
			float have = cargo.getCommodityQuantity(id);
			float drop = Math.min(have, over);
			if (drop > 0f) {
				cargo.removeCommodity(id, drop);
				over -= drop;
				ThreatIncConfig.log("Convoy mauled: " + (int) drop + " " + id + " lost with their "
						+ "freighters (" + c.fromName() + " -> " + c.toName() + ")");
			}
		}
	}

	/**
	 * Adds the faction's own personnel, freighter and tanker hulls until the
	 * load fits (2026-09-24). The fleet is built to about a point of hull per
	 * 40 marines / 60 units, but an NPC navy's fleet-size multiplier and the
	 * hulls vanilla happens to pick left convoys with a few dozen free
	 * berths: 300 marines planned, 19 to 76 loaded, the rest left behind.
	 * A bigger load now means a bigger, slower convoy, as it should. Not
	 * for a player colony's logistics convoy: its hulls are what its free
	 * fleet points bought (ThreatAidCapacity.fitLoad), and growing them would
	 * sail more than the ledger holds. Front runs, which the ledger does not
	 * hold, grow for everyone (2026-09-29). Never past vanilla's
	 * maxShipsInAIFleet ({@link #fleetShipLimit}): a load that needs more
	 * sails in several fleets ({@link #nextHulls}). The dispatch log carries
	 * the ship count; the split search builds fleets it drops, so nothing is
	 * logged here.
	 */
	protected static void fitHulls(CampaignFleetAPI fleet, FactionAPI faction, float marines,
			float cargoUnits, float fuel, Random random) {
		CargoAPI cargo = fleet.getCargo();
		int limit = fleetShipLimit();
		// each pass runs until the load fits or the fleet is at the ship limit
		// (2026-09-29: MAX_FIT_HULLS, 12 a pass, silently cut a big load to what
		// 12 more hulls held). It also stops when no hull of the role can be
		// added, or when one was added and the room did not grow - so it always
		// ends: every turn that continues adds a ship toward the limit
		while (cargo.getFreeCrewSpace() < marines && fleet.getFleetData().getNumMembers() < limit) {
			float room = cargo.getFreeCrewSpace();
			if (!addHull(fleet, faction, marines - room, 400f, 150f, ShipRoles.PERSONNEL_LARGE,
					ShipRoles.PERSONNEL_MEDIUM, ShipRoles.PERSONNEL_SMALL, random)) break;
			if (cargo.getFreeCrewSpace() <= room) break;
		}
		while (cargo.getSpaceLeft() < cargoUnits && fleet.getFleetData().getNumMembers() < limit) {
			float room = cargo.getSpaceLeft();
			if (!addHull(fleet, faction, cargoUnits - room, 1000f, 300f, ShipRoles.FREIGHTER_LARGE,
					ShipRoles.FREIGHTER_MEDIUM, ShipRoles.FREIGHTER_SMALL, random)) break;
			if (cargo.getSpaceLeft() <= room) break;
		}
		while (cargo.getFreeFuelSpace() < fuel && fleet.getFleetData().getNumMembers() < limit) {
			float room = cargo.getFreeFuelSpace();
			if (!addHull(fleet, faction, fuel - room, 1000f, 300f, ShipRoles.TANKER_LARGE,
					ShipRoles.TANKER_MEDIUM, ShipRoles.TANKER_SMALL, random)) break;
			if (cargo.getFreeFuelSpace() <= room) break;
		}
	}

	// ------------------------------------------------------------------
	// splitting a load across fleets (2026-09-29: vanilla's ship limit)
	// ------------------------------------------------------------------

	/** The most ships an AI fleet may have: vanilla's maxShipsInAIFleet, an engine limit. */
	public static int fleetShipLimit() {
		return Math.max(1, Global.getSettings().getInt("maxShipsInAIFleet"));
	}

	/** One convoy fleet built for an ask, not yet paid for, placed or loaded. */
	protected static class Hulls {
		CampaignFleetAPI fleet;
		/** What this fleet is to carry, in ThreatReserves.COMMODITIES order. */
		float[] ask;
		/** The escort points it was built with. */
		float escort;
	}

	/**
	 * Builds one convoy fleet at {@code from} for {@code ask}: freighters
	 * sized to the cargo, transports to the troops (roughly one point of hull
	 * per 60 units / 40 marines, so the load actually fits; a front run's at
	 * least ten points each, as it always sailed), and an escort by cargo value
	 * (docs/design-theory.md 8.2, Blackett: a rich convoy is a real fleet, a
	 * trickle sails with a picket) - an NPC's only as much as the donor pays
	 * for beside the {@code reserve} of fuel and supplies it ships as cargo.
	 * With {@code grow}, fitHulls adds hulls up to the ship limit. Nothing is
	 * paid, placed or drawn here: a fleet the split search passes over is
	 * simply dropped.
	 */
	protected static Hulls buildHulls(ThreatBases.Base from, FactionAPI faction, float[] ask, float[] reserve,
			float ly, boolean paysEscort, boolean frontRun, boolean grow, Random random) {
		float escort = ThreatIncConfig.convoyEscortFP() + escortForValue(ask);
		if (paysEscort) escort = paidEscort(from, escort, ly, reserve[2], reserve[3]);
		float cargoUnits = ask[1] + ask[2] + ask[3];
		float freighterPts = Math.max(10f, cargoUnits / 60f);
		float tankerPts = ask[2] > 0f ? Math.max(5f, ask[2] / 100f) : 0f;
		float transportPts = ask[0] > 0f || frontRun ? Math.max(10f, ask[0] / 40f) : 0f;
		FleetParamsV3 params = new FleetParamsV3(from.sourceMarket(), from.hyperLoc(),
				faction.getId(), null, FleetTypes.SUPPLY_FLEET,
				escort, freighterPts, tankerPts, transportPts, 0f, 0f, 0f);
		// a convoy is exactly the hulls the ledger sized and the escort it pays
		// for: vanilla's own fleet-size scaling is off for NPC navies too
		// (2026-09-29: closed economy - the multiplier's share of the escort was
		// unpaid; it was on even for the player's front runs)
		params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;
		if (grow) fitHulls(fleet, faction, ask[0], ask[1] + ask[3], ask[2], random);
		Hulls h = new Hulls();
		h.fleet = fleet;
		h.ask = ask;
		h.escort = escort;
		return h;
	}

	/** The escort points a load's cargo value calls for, over the convoyEscortFP every convoy sails with. */
	protected static float escortForValue(float[] ask) {
		return cargoValue(ask[0], ask[1], ask[2], ask[3]) / 1000f * ThreatIncConfig.convoyEscortPerThousand();
	}

	/**
	 * The escort points the donor pays for: those asked, or - vanilla pruned
	 * the fleet to maxShipsInAIFleet - only what was built (as
	 * ThreatFleetOrders' relief pays, ThreatSoftening.BUILT_SHORT).
	 */
	protected static float builtEscort(Hulls h) {
		float built = ThreatSoftening.combatFP(h.fleet);
		return built < h.escort * ThreatSoftening.BUILT_SHORT ? built : h.escort;
	}

	/** The smallest share of its ask the fleet's berths, hold or tanks take; 1 or more when it takes the whole. */
	protected static float holds(CampaignFleetAPI fleet, float[] ask) {
		CargoAPI cargo = fleet.getCargo();
		float share = Float.MAX_VALUE;
		if (ask[0] >= 1f) share = Math.min(share, cargo.getFreeCrewSpace() / ask[0]);
		float hold = ask[1] + ask[3];
		if (hold >= 1f) share = Math.min(share, cargo.getSpaceLeft() / hold);
		if (ask[2] >= 1f) share = Math.min(share, cargo.getFreeFuelSpace() / ask[2]);
		return Math.max(0f, share);
	}

	/** How close the split search brings the share it sails to the share known not to fit. */
	protected static final float SPLIT_PRECISION = 1.25f;

	/**
	 * The split search's state across one sailing: shares of the whole load
	 * one fleet was seen to carry within the ship limit, and not to. The next
	 * fleet starts from the share the last one carried, so a long sailing
	 * builds once per fleet after the first.
	 */
	protected static class Split {
		final float[] load;
		/** The largest share seen to fit one fleet; 0 = none yet. */
		float fits = 0f;
		/** The smallest share seen not to; Float.MAX_VALUE = none yet. */
		float over = Float.MAX_VALUE;

		Split(float[] load) {
			this.load = load.clone();
		}
	}

	/**
	 * The next fleet of a sailing: built for the largest share of the load
	 * that one fleet carries within vanilla's ship limit, as near as
	 * SPLIT_PRECISION - the whole of {@code remaining} when that fits, as it
	 * nearly always does. A share that does not fit is dropped unpaid; the
	 * search narrows from what the fleet held (a fleet that held 40% of the
	 * ask tries 40%), then halves the gap between the share that fits and the
	 * one that did not. A fleet under the limit that still cannot hold its
	 * ask found no more hulls of the role, and sails with what fits, as
	 * before the split. Null if nothing could be built.
	 *
	 * <p>Ends: while nothing fits, each probe asks at most 0.8 of the last,
	 * and an ask under one unit counts as a fit; once one fits, each probe
	 * halves the log of over/fits until it is within SPLIT_PRECISION.
	 */
	protected static Hulls nextHulls(Split split, float[] remaining, ThreatBases.Base from,
			FactionAPI faction, float ly, boolean paysEscort, boolean frontRun, Random random) {
		int limit = fleetShipLimit();
		Hulls best = null;
		float share = split.fits > 0f ? split.fits : 1f;
		while (true) {
			float[] ask = new float[remaining.length];
			boolean whole = true;
			float units = 0f;
			for (int i = 0; i < ask.length; i++) {
				ask[i] = Math.min(remaining[i], share * split.load[i]);
				if (ask[i] < remaining[i]) whole = false;
				units += ask[i];
			}
			// the escort pays beside everything still to ship, not only this fleet's share
			Hulls h = buildHulls(from, faction, ask, remaining, ly, paysEscort, frontRun, true, random);
			if (h == null) return best;
			float held = holds(h.fleet, ask);
			boolean fits = held >= 1f
					|| h.fleet.getFleetData().getNumMembers() < limit
					|| units < 1f
					// the escort alone fills the fleet, and no smaller ask sheds a ship of it
					|| (held <= 0f && escortForValue(ask) < 1f);
			if (fits) {
				// every fit is at a larger share than the last: the search climbs from it
				best = h;
				split.fits = share;
				if (split.over <= share) split.over = Float.MAX_VALUE;
			} else {
				split.over = share;
				if (split.fits >= share) split.fits = 0f;
			}
			if (best != null) {
				if (whole || split.over == Float.MAX_VALUE || split.over <= split.fits * SPLIT_PRECISION) {
					return best;
				}
				share = (float) Math.sqrt(split.fits * split.over);
			} else {
				share = held > 0f ? Math.min(share * held, share * 0.8f) : share * 0.5f;
			}
		}
	}

	/** One hull of the size the shortfall calls for, falling back to smaller ones; false if none could be added. */
	protected static boolean addHull(CampaignFleetAPI fleet, FactionAPI faction, float missing,
			float largeAt, float mediumAt, String large, String medium, String small, Random random) {
		String[] roles = missing >= largeAt ? new String[] {large, medium, small}
				: missing >= mediumAt ? new String[] {medium, small, large}
				: new String[] {small, medium, large};
		for (String role : roles) {
			float fp = faction.pickShipAndAddToFleet(role, FactionAPI.ShipPickParams.priority(),
					fleet, random);
			if (fp > 0f) {
				fleet.getFleetData().setSyncNeeded();
				fleet.getFleetData().syncIfNeeded();
				return true;
			}
		}
		return false;
	}

	protected static float loadCommodity(CargoAPI cargo, String donorId, String commodityId,
			float wanted) {
		if (wanted <= 0f) return 0f;
		// fuel rides in the tanks, everything else in the hold
		float room = Commodities.FUEL.equals(commodityId) ? cargo.getFreeFuelSpace() : cargo.getSpaceLeft();
		float fits = Math.max(0f, Math.min(wanted, room));
		int units = (int) fits;
		if (units <= 0) return 0f;
		float taken = ThreatReserves.draw(donorId, commodityId, units);
		if (taken > 0f) cargo.addCommodity(commodityId, (int) taken);
		return (int) taken;
	}

	// ------------------------------------------------------------------
	// resolution (fast poll)
	// ------------------------------------------------------------------

	/** Distance from the destination entity at which a convoy counts as arrived (ThreatReturns.arrived). */
	public static final float ARRIVAL_RANGE = ThreatReturns.ARRIVAL_RANGE;

	public static void poll() {
		if (all().isEmpty()) return;
		for (Convoy c : new ArrayList<Convoy>(all())) {
			CampaignFleetAPI fleet = c.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
				lost(c);
				continue;
			}
			float days = Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
			if (days > ThreatIncConfig.convoyTimeoutDays()) {
				// (2026-09-29: closed economy) a convoy still afloat past its time is
				// not written off with its cargo: it turns for home and settles there
				// (returnHome). That is its last leg - ThreatReturns gives it
				// convoyTimeoutDays more, then despawns it - so it cannot loop
				ThreatIncConfig.log("Convoy timed out after " + (int) days + " days: " + c.factionId + " "
						+ c.fromName() + " -> " + c.toName() + ", sent home");
				returnHome(c);
				continue;
			}
			trimToHulls(c);
			if (c.isFrontRun()) {
				pollFrontRun(c);
				continue;
			}
			ThreatBases.Base base = ThreatBases.of(c.toMarketId);
			if (base == null || base.entity() == null || !boundFor(c, base)) {
				// the destination fell or changed hands: turn around, stock stays aboard
				// until the fleet despawns home, where it is returned to the donor
				returnHome(c);
				continue;
			}
			SectorEntityToken to = base.entity();
			// vanilla ends the leg at the entity's edge and the queue falls
			// through to the despawning return: the convoy is kept on it
			if (ThreatReturns.onLeg(fleet, to, "carrying war materiel to " + base.name())) {
				arrived(c, base);
			}
		}
	}

	protected static void arrived(Convoy c, ThreatBases.Base base) {
		CampaignFleetAPI fleet = c.fleet;
		CargoAPI cargo = fleet.getCargo();
		// whatever is still aboard - losses in transit are real losses
		int marines = cargo.getMarines();
		int armaments = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		int fuel = (int) cargo.getCommodityQuantity(Commodities.FUEL);
		int supplies = (int) cargo.getCommodityQuantity(Commodities.SUPPLIES);
		if (marines > 0) cargo.removeMarines(marines);
		if (armaments > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, armaments);
		if (fuel > 0) cargo.removeCommodity(Commodities.FUEL, fuel);
		if (supplies > 0) cargo.removeCommodity(Commodities.SUPPLIES, supplies);
		ThreatReserves.deposit(base.id(), Commodities.MARINES, marines);
		ThreatReserves.deposit(base.id(), Commodities.HAND_WEAPONS, armaments);
		ThreatReserves.deposit(base.id(), Commodities.FUEL, fuel);
		ThreatReserves.deposit(base.id(), Commodities.SUPPLIES, supplies);
		// rule 5 (docs/economy-coherence.md): war stock lands in the depot only.
		// It used to land as a trade modifier too, and vanilla's market screens
		// sold the military's shipment as cheap excess (2026-09-27).
		all().remove(c);

		// another faction's colony: the player's standing, an ally's word
		FactionAPI sender = Global.getSector().getFaction(c.factionId);
		if (c.recipientFactionId != null && sender != null && base.market != null) {
			if (sender.isPlayerFaction()) {
				ThreatAid.onDelivered(base.market, c.recipientFactionId, marines, armaments, fuel,
						supplies, true, null);
			} else {
				ThreatCoalition.onAllyDelivered(c, base.market);
			}
		}

		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		ThreatBases.Base npcHome = sender != null && !sender.isPlayerFaction() ? ThreatBases.of(c.fromMarketId) : null;
		if (sender != null && sender.isPlayerFaction() && donor != null
				&& donor.getPrimaryEntity() != null) {
			// a player fleet comes home on the tracked leg: the ledger gets its points back
			ThreatReturns.sendHome(fleet, c.factionId, donor.getId());
		} else if (npcHome != null && npcHome.entity() != null && c.factionId.equals(npcHome.factionId())) {
			// (2026-09-29: closed economy) an NPC convoy comes home on the tracked
			// leg too: its escort's hulls are re-banked at what survived (ThreatReturns)
			ThreatReturns.sendHome(fleet, c.factionId, npcHome.id());
		} else {
			// an NPC sender whose donor is gone or changed hands settles at its
			// faction's nearest base instead (2026-09-29: closed economy - the
			// despawning return below ended its escort unsettled)
			ThreatBases.Base fallback = sender != null && !sender.isPlayerFaction()
					? fallbackHome(fleet, c.factionId) : null;
			if (fallback == null || !ThreatReturns.sendHome(fleet, c.factionId, fallback.id())) {
				SectorEntityToken home = donor != null ? donor.getPrimaryEntity() : base.entity();
				fleet.clearAssignments();
				fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 1000f,
						"returning to " + (donor != null ? donor.getName() : base.name()));
			}
		}
		ThreatIncConfig.log("Convoy arrived: " + c.factionId + " at " + base.name() + " ("
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, "
				+ supplies + " supplies)");
	}

	/** Whether the convoy's destination still belongs to whom it was sent to. */
	protected static boolean boundFor(Convoy c, ThreatBases.Base base) {
		if (base.isOutpost() && !base.outpost.alive()) return false; // the station died
		if (c.factionId.equals(base.factionId())) return true;
		return c.recipientFactionId != null && c.recipientFactionId.equals(base.factionId());
	}

	/**
	 * The helper's colony in reach of another faction's colony that can spare
	 * the most of a commodity, or null. An NPC helper reaches as far as its
	 * donor's fuel does, as the staging planner's donors (2026-09-29: the flat
	 * convoyRangeLY, 15 ly, kept allies' stock out of reach of sieges their
	 * fuel could reach); the player's at any range.
	 */
	public static MarketAPI pickAllyDonor(FactionAPI helper, MarketAPI needy, String commodityId) {
		if (helper == null || needy == null || needy.getStarSystem() == null) return null;
		MarketAPI best = null;
		float bestSpare = 0f;
		for (MarketAPI donor : ThreatReserves.marketsOf(helper.getId())) {
			if (donor.getStarSystem() == null || donor.getPrimaryEntity() == null) continue;
			float reach = helper.isPlayerFaction() ? Float.MAX_VALUE
					: Math.max(ThreatIncConfig.convoyRangeLY(), IncursionManager.expeditionRangeLY(donor));
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					needy.getStarSystem().getLocation()) > reach) continue;
			float s = spare(donor, commodityId);
			if (s > bestSpare) {
				bestSpare = s;
				best = donor;
			}
		}
		if (best != null && bestSpare < capacityFor(commodityId)
				* ThreatIncConfig.convoyMinLoadFraction()) return null;
		return best;
	}

	/** Strips the landing trade modifiers older builds left on markets (rule 5). Idempotent. */
	public static void stripLandedMods() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			for (CommodityOnMarketAPI com : market.getAllCommodities()) {
				for (String source : new ArrayList<String>(com.getTradeModPlus().getFlatMods().keySet())) {
					if (source.startsWith(ThreatReserves.CONVOY_SOURCE_PREFIX)) {
						com.getTradeModPlus().unmodifyFlat(source);
					}
				}
			}
		}
	}

	protected static void lost(Convoy c) {
		all().remove(c);
		FactionAPI faction = Global.getSector().getFaction(c.factionId);
		ThreatNotice n = ThreatNotice.titled("Convoy Lost").bad().icon(faction);
		if (faction != null && faction.isPlayerFaction()) {
			n.line("Your supply convoy from %s to %s", c.fromName(), c.toName());
		} else {
			n.line("%s supply convoy from %s to %s",
					faction != null ? ThreatNotice.faction(faction) : ThreatNotice.gray(c.factionId),
					c.fromName(), c.toName());
		}
		ThreatColonyManager.announce(n.line("Lost with its cargo"));
		ThreatIncConfig.log("Convoy lost: " + c.factionId + " " + c.fromName() + " -> " + c.toName());
	}

	/**
	 * Where an NPC convoy whose donor is gone or changed hands goes home to
	 * settle: its faction's nearest base to the fleet (ThreatFleetOrders.pickBase),
	 * else its nearest colony. Null for the player's - the ledger holds it for
	 * its own colony - and for a faction with nowhere left.
	 */
	protected static ThreatBases.Base fallbackHome(CampaignFleetAPI fleet, String factionId) {
		if (fleet == null || factionId == null) return null;
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction == null || faction.isPlayerFaction()) return null;
		Vector2f at = fleet.getLocationInHyperspace();
		MarketAPI home = ThreatFleetOrders.pickBase(faction, at);
		if (home == null || home.getPrimaryEntity() == null) home = nearestColony(faction, at);
		return home != null && home.getPrimaryEntity() != null ? ThreatBases.of(home) : null;
	}

	/** Where a convoy settles: its donor while still the sender's and there, else {@link #fallbackHome}; null with neither. */
	protected static ThreatBases.Base homeBase(Convoy c) {
		// the donor may be an outpost (a front run out of one): still theirs, still there
		ThreatBases.Base donor = ThreatBases.of(c.fromMarketId);
		if (donor != null && donor.entity() != null && c.factionId.equals(donor.factionId())) return donor;
		return fallbackHome(c.fleet, c.factionId);
	}

	/**
	 * Recalled, timed out, or its destination is gone: the convoy turns for
	 * the donor with the cargo still aboard, and whatever survives the trip
	 * home goes back into the donor's reserve on arrival (ThreatReturns). An
	 * NPC donor that is gone or changed hands gives way to the faction's
	 * nearest base ({@link #fallbackHome}); with none, the fleet fades out.
	 */
	protected static void returnHome(Convoy c) {
		all().remove(c);
		CampaignFleetAPI fleet = c.fleet;
		ThreatBases.Base home = homeBase(c);
		if (home != null) {
			ThreatReturns.sendHome(fleet, c.factionId, home.id());
		} else if (fleet != null) {
			Misc.fadeAndExpire(fleet);
		}
		ThreatIncConfig.log("Convoy recalled: " + c.factionId + " " + c.fromName()
				+ " -> " + c.toName());
	}
}
