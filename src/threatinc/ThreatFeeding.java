package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * TANGIBLE FEEDING (the user, 2026-10-18: "make the supply thing tangible so they
 * send convoys both sides to maintain worlds and forward bases. The convoys don't
 * need to return home, they can just be absorbed when they reach their target").
 * Size upkeep (ThreatColonyUpkeep) is paid from a LARDER at the world, and only a
 * sailing fills a larder:
 * <ul>
 * <li>A hive world pays its upkeep from its own forge's output on the spot, then
 * from its larder. What the feed allots it from the hive stock beyond its own
 * output ({@link ThreatColonyUpkeep#feed}, debited from the stock as before) is
 * DOCKED at the hive and sails as a FEEDING SWARM: a Defense Swarm of the nearest
 * hive with one to spare, sent as a reinforcement is
 * (ThreatColonyManager.sendReinforcement - the hive's fuel pays the passage, the
 * swarm flies blinkered and fights where the player holds the orbit), carrying the
 * dock. Seated in the world's garrison on arrival it is absorbed, and its cargo
 * lands in the larder ({@link #landed}). Killed on the way, the cargo is lost.</li>
 * <li>A forward base pays from its depot alone (ThreatReserves) - no instant draw on
 * its home base or the faction's markets, which {@code ThreatFrontlines.feedSize}
 * made until now. When the depot's feeding credit and what is at sea to it hold
 * under feedConvoyReserveMonths of its bill, its home base (or the planner's
 * donor, ThreatConvoys.pickDonor) sends a FEEDING CONVOY of feedConvoyLoadMonths of
 * it (ThreatConvoys.dispatch: escort and passage paid as any convoy's, raiders may
 * take it, a blockade holds it off at the jump-point - holdsOff). Landed, its cargo
 * is the base's feeding credit, its hulls are settled where it stands
 * (ThreatReturns.settle, the leg home never flown) and it expires: absorbed.</li>
 * </ul>
 * A world no sailing reaches starves on the existing rule
 * (ThreatColonyUpkeep.growthRate); a sailing lost is upkeep lost. Both sides on the
 * same knobs: threatinc_feedConvoys, feedConvoyLoadMonths, feedConvoyReserveMonths.
 * Garrison upkeep (ThreatFrontlines.payUpkeep, the hive's navy charge) is untouched
 * on both sides - still paid from the depots and the stock as before.
 * docs/hive-stocks-and-upkeep.md "Tangible feeding".
 */
public class ThreatFeeding {

	/** Market memory: supplies landed at the world for its size upkeep - a hive world's larder, or a base's feeding credit in its depot. */
	public static final String MEM_LARDER = "$threatinc_feedLarder";
	/** Market memory (hive): supplies the feed allotted the world from the stock, waiting at the hive to sail. */
	public static final String MEM_DOCK = "$threatinc_feedDock";
	/** Market memory: the world's imported bill a month as the feed last read it (sizes its sailings). */
	public static final String MEM_MONTHLY = "$threatinc_feedMonthly";
	/** Market memory: the larder was granted its opening months once (no world starves while the first sailing is on its way). */
	public static final String MEM_SEEDED = "$threatinc_feedSeeded";
	/** Fleet memory: the supplies a feeding swarm carries for its target's larder. */
	public static final String MEM_CARGO = "$threatinc_feedCargo";

	public static boolean enabled() {
		return ThreatIncConfig.feedConvoys() && ThreatColonyUpkeep.enabled();
	}

	// ------------------------------------------------------------------
	// the larder
	// ------------------------------------------------------------------

	/** Supplies landed at the world for its upkeep and not yet eaten. */
	public static float larder(MarketAPI market) {
		return market == null ? 0f : Math.max(0f, market.getMemoryWithoutUpdate().getFloat(MEM_LARDER));
	}

	protected static void setLarder(MarketAPI market, float v) {
		market.getMemoryWithoutUpdate().set(MEM_LARDER, Math.max(0f, v));
	}

	/** Supplies the feed allotted a hive world from the stock that have not sailed yet. */
	public static float dock(MarketAPI market) {
		return market == null ? 0f : Math.max(0f, market.getMemoryWithoutUpdate().getFloat(MEM_DOCK));
	}

	/** The world's imported bill a month, as the feed last read it. */
	public static float monthly(MarketAPI market) {
		return market == null ? 0f : Math.max(0f, market.getMemoryWithoutUpdate().getFloat(MEM_MONTHLY));
	}

	/** One grant at first enable: the reserve plus one month of the imported bill, so the world eats while its first sailing is out. */
	protected static void seed(MarketAPI market, float monthly) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (mem.getBoolean(MEM_SEEDED)) return;
		mem.set(MEM_SEEDED, true);
		float grant = (ThreatIncConfig.feedConvoyReserveMonths() + 1f) * Math.max(0f, monthly);
		if (grant <= 0f) return;
		setLarder(market, larder(market) + grant);
		ThreatIncConfig.log("Feeding: " + market.getName() + "'s larder opens with " + (int) grant + " supplies");
	}

	// ------------------------------------------------------------------
	// the hive
	// ------------------------------------------------------------------

	/**
	 * A hive world's feed under tangible feeding (from ThreatColonyUpkeep.feed): the
	 * {@code bank} supplies the stock allotted it beyond its own output are docked to
	 * sail, and the world eats from its larder up to that. Returns what it ate - the
	 * share paid counts this, not the allotment. {@code monthly} is its imported bill
	 * a month, kept for the sailings' size.
	 */
	public static float payHive(MarketAPI market, float bank, float monthly) {
		if (market == null) return 0f;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		mem.set(MEM_MONTHLY, Math.max(0f, monthly));
		seed(market, monthly);
		if (bank > 0f) mem.set(MEM_DOCK, dock(market) + bank);
		float eat = Math.min(Math.max(0f, bank), larder(market));
		if (eat > 0f) setLarder(market, larder(market) - eat);
		return eat;
	}

	/** Whether a feeding swarm is at sea to this world. */
	public static boolean swarmBound(String marketId) {
		for (CampaignFleetAPI f : ThreatIncData.reinforcementFleets().values()) {
			if (f == null || !f.isAlive()) continue;
			MemoryAPI mem = f.getMemoryWithoutUpdate();
			if (marketId.equals(mem.getString(ThreatColonyManager.REINFORCE_TARGET_KEY)) && mem.getFloat(MEM_CARGO) > 0f) return true;
		}
		return false;
	}

	/**
	 * Each poll (IncursionManager, after the reinforcement arrivals): every hive world
	 * with a dock worth sailing gets a feeding swarm. Worth sailing: the dock holds
	 * feedConvoyLoadMonths of its imported bill, or the larder is under
	 * feedConvoyReserveMonths of it and the dock half a month - one swarm bound to a
	 * world at a time. The swarm is the smallest Defense Swarm of the nearest hive that
	 * can spare one (same system first - no passage), sent as a reinforcement; no such
	 * hive, or no fuel for the passage, and the dock waits.
	 */
	public static void shipHive() {
		if (!enabled()) return;
		float load = Math.max(0.5f, ThreatIncConfig.feedConvoyLoadMonths());
		float reserve = Math.max(0f, ThreatIncConfig.feedConvoyReserveMonths());
		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		for (MarketAPI m : colonies) {
			float docked = dock(m);
			if (docked <= 0f) continue;
			float monthly = monthly(m);
			boolean full = monthly <= 0f || docked >= load * monthly;
			boolean low = larder(m) < reserve * monthly && docked >= 0.5f * monthly;
			if (!full && !low) continue;
			if (swarmBound(m.getId())) continue;
			MarketAPI donor = pickHiveDonor(m, colonies);
			if (donor == null) {
				ThreatIncConfig.logQuiet("feedwait:" + m.getId(), "Feeding: no hive can spare a swarm to carry "
						+ (int) docked + " supplies to " + m.getName() + " (larder " + (int) larder(m) + ")");
				continue;
			}
			CampaignFleetAPI pick = ThreatColonyManager.smallestOfTier(donor, 0);
			if (pick == null) continue;
			if (!ThreatColonyManager.sendReinforcement(donor, m, pick)) continue;
			pick.getMemoryWithoutUpdate().set(MEM_CARGO, docked);
			m.getMemoryWithoutUpdate().unset(MEM_DOCK);
			ThreatIncConfig.log("Feeding swarm " + donor.getName() + " -> " + m.getName() + ": " + (int) docked
					+ " supplies aboard " + (int) pick.getFleetPoints() + " FP (larder " + (int) larder(m) + ", bill "
					+ (int) monthly + "/mo, " + String.format("%.1f", ThreatFuel.ly(donor.getStarSystem(), m.getStarSystem())) + " ly)");
		}
	}

	/** The nearest hive world with a Defense Swarm to spare that can reach the target: its own system's worlds first. */
	protected static MarketAPI pickHiveDonor(MarketAPI target, List<MarketAPI> colonies) {
		if (target.getStarSystem() == null) return null;
		MarketAPI best = null;
		float bestD = Float.MAX_VALUE;
		for (MarketAPI c : colonies) {
			if (c == target || c.getStarSystem() == null) continue;
			if (ThreatColonyManager.ownAvailableForLaunch(c) < 1) continue;
			if (ThreatColonyManager.smallestOfTier(c, 0) == null) continue;
			if (!ThreatColonyManager.canReinforce(c, target)) continue;
			float d = c.getStarSystem() == target.getStarSystem() ? 0f
					: Misc.getDistanceLY(c.getStarSystem().getLocation(), target.getStarSystem().getLocation());
			if (d < bestD) {
				bestD = d;
				best = c;
			}
		}
		return best;
	}

	/** A feeding swarm seated at its world (ThreatColonyManager.checkReinforcementArrivals): its cargo lands in the larder. */
	public static void landed(CampaignFleetAPI fleet, MarketAPI target) {
		if (fleet == null || target == null) return;
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float cargo = mem.getFloat(MEM_CARGO);
		if (cargo <= 0f) return;
		mem.unset(MEM_CARGO);
		setLarder(target, larder(target) + cargo);
		ThreatIncConfig.log("Feeding swarm landed at " + target.getName() + ": " + (int) cargo + " supplies, larder "
				+ (int) larder(target) + " (bill " + (int) monthly(target) + "/mo)");
	}

	/** A feeding swarm disbanded or killed with its cargo aboard: the upkeep is lost. */
	public static void lost(CampaignFleetAPI fleet, String why) {
		if (fleet == null) return;
		float cargo = fleet.getMemoryWithoutUpdate().getFloat(MEM_CARGO);
		if (cargo <= 0f) return;
		fleet.getMemoryWithoutUpdate().unset(MEM_CARGO);
		ThreatIncConfig.log("Feeding swarm lost: " + (int) cargo + " supplies (" + why + ")");
	}

	// ------------------------------------------------------------------
	// the forward bases
	// ------------------------------------------------------------------

	/**
	 * A forward base's upkeep for the day out of its depot (ThreatFrontlines.feedSize):
	 * its feeding credit first, below the depot's floor if need be (a feeding convoy's
	 * cargo is the base's to eat), then what the depot holds free above its floor and
	 * staging bank ({@code free}). Returns what was drawn; nothing is drawn on other
	 * markets.
	 */
	public static float payBase(MarketAPI market, float want, float free) {
		if (market == null || want <= 0f) return 0f;
		float credit = larder(market);
		float stock = ThreatReserves.stock(market.getId(), Commodities.SUPPLIES);
		float fromCredit = ThreatReserves.draw(market.getId(), Commodities.SUPPLIES, Math.min(want, Math.min(credit, stock)));
		if (fromCredit > 0f) setLarder(market, credit - fromCredit);
		float rest = want - fromCredit;
		if (rest <= 0f) return fromCredit;
		float own = ThreatReserves.drawAbove(market, Commodities.SUPPLIES, Math.min(rest, Math.max(0f, free - fromCredit)));
		return fromCredit + own;
	}

	/** The base's feeding credit the logistics planner leaves in its depot (ThreatConvoys.spare). */
	public static float credit(MarketAPI market) {
		return enabled() && ThreatFrontlines.isOutpost(market) ? larder(market) : 0f;
	}

	/**
	 * The faction's logistics pass (ThreatConvoys.planLogistics, before the depots):
	 * every forward base whose feeding credit plus the supplies at sea to it hold under
	 * feedConvoyReserveMonths of its size upkeep gets a feeding convoy of
	 * feedConvoyLoadMonths of it, from its home base when that reaches and can spare
	 * a quarter month, else the planner's donor. The convoy is marked feeding: landed,
	 * its cargo is the base's credit and it is settled where it stands.
	 */
	public static void shipBases(FactionAPI faction, List<MarketAPI> markets, Random random) {
		if (!enabled() || faction == null) return;
		float load = Math.max(0.5f, ThreatIncConfig.feedConvoyLoadMonths());
		float reserve = Math.max(0f, ThreatIncConfig.feedConvoyReserveMonths());
		for (MarketAPI base : new ArrayList<MarketAPI>(markets)) {
			if (!ThreatFrontlines.isOutpost(base)) continue;
			float monthly = ThreatColonyUpkeep.perMonth(base.getSize());
			if (monthly <= 0f) continue;
			float have = larder(base) + ThreatConvoys.inbound(base.getId())[3];
			if (have >= reserve * monthly) continue;
			float ask = load * monthly - have;
			if (ask < 0.25f * monthly) continue;
			ThreatFrontlines.Outpost o = ThreatFrontlines.find(base);
			MarketAPI home = o != null ? ThreatFrontlines.homeOf(o) : null;
			MarketAPI donor = null;
			if (home != null && IncursionManager.marketsReaching(faction, base).contains(home)
					&& ThreatConvoys.spare(home, Commodities.SUPPLIES) >= 0.25f * monthly) {
				donor = home;
			}
			if (donor == null) donor = ThreatConvoys.pickDonor(markets, base, Commodities.SUPPLIES);
			if (donor == null || donor == base) {
				ThreatIncConfig.logQuiet("feedwait:" + base.getId(), "Feeding: no market of " + faction.getId()
						+ " can spare supplies for " + base.getName() + " (credit " + (int) larder(base) + ", bill "
						+ (int) monthly + "/mo)");
				continue;
			}
			float send = Math.min(ask, ThreatConvoys.sendable(donor, base, Commodities.SUPPLIES));
			if (send < 0.25f * monthly) continue;
			float[] cargo = new float[ThreatReserves.COMMODITIES.length];
			cargo[3] = send;
			long before = Global.getSector().getClock().getTimestamp();
			ThreatConvoys.Convoy lead = ThreatConvoys.dispatch(donor, base, faction, cargo, random);
			if (lead == null) continue;
			float sailed = 0f;
			for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
				if (c.departedTimestamp < before || !base.getId().equals(c.toMarketId)
						|| !donor.getId().equals(c.fromMarketId)) continue;
				c.feeding = true;
				sailed += c.supplies;
			}
			ThreatIncConfig.log("Feeding convoy " + faction.getId() + " " + donor.getName() + " -> " + base.getName() + ": "
					+ (int) sailed + " supplies (credit " + (int) larder(base) + ", bill " + (int) monthly + "/mo)");
		}
	}

	/**
	 * A feeding convoy landed (ThreatConvoys.arrived, its cargo already in the depot):
	 * the supplies are the base's feeding credit, and the convoy is absorbed - settled
	 * where it stands (its hulls the faction's again, its provisions refunded here) and
	 * expired, the leg home never flown.
	 */
	public static void absorbed(ThreatConvoys.Convoy c, ThreatBases.Base base, float supplies) {
		if (c == null || base == null) return;
		if (base.market != null && supplies > 0f) setLarder(base.market, larder(base.market) + supplies);
		CampaignFleetAPI fleet = c.fleet;
		if (fleet != null && fleet.isAlive()) {
			ThreatReturns.settle(fleet, base, false);
			if (!ThreatReturns.kept(fleet)) {
				fleet.clearAssignments();
				Misc.fadeAndExpire(fleet);
			}
		}
		ThreatIncConfig.log("Feeding convoy absorbed at " + base.name() + ": " + (int) supplies + " supplies, credit "
				+ (base.market != null ? (int) larder(base.market) : 0) + " (" + c.factionId + " from " + c.fromName() + ")");
	}
}
