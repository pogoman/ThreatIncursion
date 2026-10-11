package threatinc;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Fleet upkeep (2026-09-30; carried since 2026-10-11, docs/fleet-provisions.md):
 * a fleet the strategy layer sent out - a hunt, a siege, a sortie, a raid, a
 * scouting party, the player's task forces as much as an NPC navy's - burns its
 * ships' vanilla supplies per month (ThreatFrontlines.maintenancePerMonth:
 * maintenance only, no repair or CR recovery) for every day it is out, from the
 * HOLD it sailed with (ThreatReturns.MEM_HOLD): its maintenance for the voyage
 * and its stay, drawn beside the hull deposit at launch. Nothing is drawn from
 * home while it is away. Below what the leg home burns it restocks to its
 * launch load from a base of its faction in the system it is in
 * (ThreatBases.inSystem) and, with none to restock from, turns home on what it
 * has left (ThreatFleetOrders.standDown, ThreatPurgeFGI.shortOfSupplies). A
 * fleet that still runs dry owes its upkeep, and a month owed starves it
 * ({@link #starve}). A siege's hold rides the expedition and burns at its
 * fighting strength, spawned or not; its fleets are not billed twice. Convoys
 * paid their escort's trip at launch (ThreatConvoys.escortRate) and burn no
 * more; a forward base's garrison pays through its link
 * (ThreatFrontlines.payUpkeep); the Threat pays in fleet points.
 *
 * <p>Before this the fleet's home depot was billed every pass wherever the
 * fleet was, then the faction's markets whose stock reached the home - supplies
 * wired to a fleet ten light-years out, as the user put it. The launch draw
 * all comes back with the surviving hulls (ThreatReturns.suppliesBack).
 */
public class ThreatUpkeep {

	public static final String KEY_LAST = "threatinc_upkeepLast";
	/** Fleet memory: when the fleet's upkeep was last charged, and what it still owes. */
	public static final String MEM_AT = "$threatinc_upkeepAt";
	public static final String MEM_OWED = "$threatinc_upkeepOwed";
	/** Fleet memory: sent home for unpaid upkeep ({@link #starve}); once. */
	public static final String MEM_STARVED = "$threatinc_upkeepStarved";
	/** Days between passes; each charges the days since the fleet's last. */
	protected static final float PASS_DAYS = 5f;

	public static boolean enabled() {
		return ThreatIncConfig.fleetUpkeep();
	}

	public static void poll() {
		if (!enabled()) return;
		long now = Global.getSector().getClock().getTimestamp();
		Map<String, Object> d = ThreatIncData.map(KEY_LAST);
		Object last = d.get("at");
		if (last instanceof Long && Global.getSector().getClock().getElapsedDaysSince((Long) last) < PASS_DAYS) return;
		d.put("at", now);
		// faction -> {fleets, wanted, paid, owed, restocked}
		Map<String, float[]> tally = new LinkedHashMap<String, float[]>();
		// a siege's fleets burn the expedition's hold, not their own
		Set<CampaignFleetAPI> siegeFleets = new HashSet<CampaignFleetAPI>();
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i
				: Global.getSector().getIntelManager().getIntel(ThreatPurgeFGI.class)) {
			ThreatPurgeFGI siege = (ThreatPurgeFGI) i;
			if (siege.isEnded() || siege.isEnding() || siege.isAborted()) continue;
			if (siege.getFleets() != null) siegeFleets.addAll(siege.getFleets());
			chargeSiege(siege, now, tally);
		}
		for (LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI f : loc.getFleets()) {
				if (!siegeFleets.contains(f)) charge(f, now, tally);
			}
		}
		for (Map.Entry<String, float[]> e : tally.entrySet()) {
			float[] t = e.getValue();
			ThreatIncConfig.log("Fleet upkeep: " + e.getKey() + " " + (int) t[0] + " fleets out, paid " + (int) t[2]
					+ " of " + (int) t[1] + " supplies from their holds" + (t[3] >= 1f ? ", " + (int) t[3] + " owed" : "")
					+ (t[4] >= 1f ? ", restocked " + (int) t[4] : ""));
		}
	}

	/**
	 * A fleet that owes a month of upkeep goes home (2026-09-30): a hunt or
	 * sortie stands down (ThreatFleetOrders.standDown), a siege is called off
	 * (ThreatPurgeFGI.outOfSupplies). Anything else - a convoy, a fleet already
	 * on its way home - runs on. Once per fleet. h26a's fleets ran ~25% of
	 * their upkeep unpaid and fought on at full strength.
	 */
	protected static void starve(CampaignFleetAPI f, float owed) {
		MemoryAPI mem = f.getMemoryWithoutUpdate();
		if (mem.getBoolean(MEM_STARVED)) return;
		ThreatFleetOrders.Order o = orderOf(f);
		if (o != null) {
			mem.set(MEM_STARVED, true);
			ThreatFleetOrders.standDown(o, "out of supplies, " + (int) owed + " owed");
			return;
		}
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin i
				: Global.getSector().getIntelManager().getIntel(ThreatPurgeFGI.class)) {
			ThreatPurgeFGI siege = (ThreatPurgeFGI) i;
			if (siege.isEnded() || siege.isEnding() || siege.isAborted()) continue;
			if (siege.getFleets() == null || !siege.getFleets().contains(f)) continue;
			mem.set(MEM_STARVED, true);
			siege.outOfSupplies(owed);
			return;
		}
	}

	/** The order this fleet sails under, or null. */
	protected static ThreatFleetOrders.Order orderOf(CampaignFleetAPI f) {
		for (ThreatFleetOrders.Order o : new java.util.ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (o.fleet == f) return o;
		}
		return null;
	}

	/**
	 * A siege's upkeep, spawned or not: its fighting strength's supplies a
	 * month (ThreatReach.tripSupplies) for the days since its last pass, from
	 * its hold; restocked at a base of its faction in the target system when
	 * below its leg home; a month nobody could pay turns it home
	 * (ThreatPurgeFGI.outOfSupplies), and a hold below the leg home with
	 * nothing to restock from does too (shortOfSupplies). Sieges from before
	 * the hold (hold 0, never loaded) restock first, so a save carries on.
	 */
	protected static void chargeSiege(ThreatPurgeFGI siege, long now, Map<String, float[]> tally) {
		if (siege == null) return;
		FactionAPI faction = siege.getFaction();
		if (faction == null || Factions.THREAT.equals(faction.getId())) return;
		long at = siege.upkeepAt;
		siege.upkeepAt = now;
		if (at <= 0L) return;
		float days = Global.getSector().getClock().getElapsedDaysSince(at);
		if (days <= 0f) return;
		float fp = siege.fightingFP();
		float perMonth = ThreatReach.tripSupplies(faction.getId(), fp, 30f);
		float want = perMonth * days / 30f;
		float ask = want + siege.upkeepOwed;
		// a siege that sailed before the hold (a save in flight) is billed at home as it was
		if (siege.holdFull() <= 0f) {
			com.fs.starfarer.api.campaign.econ.MarketAPI home = siege.sourceBase();
			if (home == null || faction.isPlayerFaction()) return;
			float paid = ThreatReserves.draw(home.getId(), Commodities.SUPPLIES, ask);
			if (paid < ask) paid += ThreatFrontlines.payFromOthers(home, home, Commodities.SUPPLIES, ask - paid);
			float left = Math.max(0f, ask - paid);
			siege.upkeepOwed = left;
			tally(tally, faction.getId(), want, paid, left, 0f);
			if (left > 0f && left >= perMonth) siege.outOfSupplies(left);
			return;
		}
		float paid = siege.burnHold(ask);
		float restocked = 0f;
		if (paid < ask) {
			restocked = restockSiege(siege, faction.getId());
			paid += siege.burnHold(ask - paid);
		}
		float left = Math.max(0f, ask - paid);
		siege.upkeepOwed = left;
		tally(tally, faction.getId(), want, paid, left, restocked);
		// a month of upkeep nobody could pay: its ships cannot be kept up out here
		if (left > 0f && left >= perMonth) {
			siege.outOfSupplies(left);
			return;
		}
		// home when low: the leg home from the target system
		StarSystemAPI target = siege.targetSystem();
		com.fs.starfarer.api.campaign.econ.MarketAPI home = siege.sourceBase();
		if (target == null || home == null) return;
		float ly = Misc.getDistanceLY(target.getLocation(), home.getLocationInHyperspace());
		float reserve = ThreatReach.tripSupplies(faction.getId(), fp, ThreatReach.days(ly) + 1f);
		if (siege.hold() >= reserve) return;
		restockSiege(siege, faction.getId());
		if (siege.hold() < reserve) siege.shortOfSupplies(siege.hold());
	}

	/** Fills a siege's hold back to its launch load from its faction's bases in the target system; returns what it got. */
	protected static float restockSiege(ThreatPurgeFGI siege, String factionId) {
		if (!ThreatIncConfig.fleetResupply()) return 0f;
		float want = siege.holdFull() - siege.hold();
		if (want <= 0f) return 0f;
		float got = restock(factionId, siege.targetSystem(), want, "the siege of "
				+ (siege.targetSystem() != null ? siege.targetSystem().getName() : "?"));
		siege.stockHold(got);
		return got;
	}

	/**
	 * Draws up to {@code want} supplies from the faction's bases in the system
	 * (ThreatBases.inSystem: colonies above their floor, outposts whole),
	 * nearest the fleet's business first in list order. Returns what was drawn.
	 */
	protected static float restock(String factionId, StarSystemAPI system, float want, String forWhom) {
		if (!ThreatIncConfig.fleetResupply() || system == null || want <= 0f) return 0f;
		float got = 0f;
		for (ThreatBases.Base base : ThreatBases.inSystem(factionId, system)) {
			if (got >= want) break;
			float taken = ThreatBases.draw(base, Commodities.SUPPLIES, want - got);
			if (taken > 0f) {
				got += taken;
				ThreatIncConfig.log("Restocked at " + base.name() + ": " + (int) taken + " supplies for " + forWhom);
			}
		}
		return got;
	}

	protected static void tally(Map<String, float[]> tally, String factionId, float want, float paid, float left,
			float restocked) {
		float[] t = tally.get(factionId);
		if (t == null) {
			t = new float[5];
			tally.put(factionId, t);
		}
		if (want > 0f) t[0] += 1f;
		t[1] += want;
		t[2] += paid;
		t[3] += left;
		t[4] += restocked;
	}

	/**
	 * One fleet's pass: its maintenance for the days since its last, from its
	 * hold; restocked in its system when short; owed when nothing could pay.
	 * Then home when low: a fleet under orders whose hold is below the leg
	 * home, with nothing to restock from, stands down.
	 */
	protected static void charge(CampaignFleetAPI f, long now, Map<String, float[]> tally) {
		if (f == null || !f.isAlive() || f.isDespawning() || f.isPlayerFleet()) return;
		String home = ThreatReturns.homeOf(f);
		if (home == null) return;
		FactionAPI faction = f.getFaction();
		if (faction == null || Factions.THREAT.equals(faction.getId())) return;
		// a forward base's garrison pays through its link (ThreatFrontlines.payUpkeep);
		// a convoy paid its escort's trip at launch (ThreatConvoys.payEscort)
		if (ThreatFrontlines.isGuard(f) || ThreatConvoys.isConvoy(f)) return;
		MemoryAPI mem = f.getMemoryWithoutUpdate();
		Object at = mem.get(MEM_AT);
		mem.set(MEM_AT, now);
		// first sight starts the clock: nothing is billed for before it
		if (!(at instanceof Long)) return;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
		if (days <= 0f) return;
		float perMonth = ThreatFrontlines.maintenancePerMonth(f);
		float want = perMonth * days / 30f;
		float owed = mem.getFloat(MEM_OWED);
		float ask = want + owed;
		// a fleet that sailed before the hold (a save in flight) is billed at home as it was
		if (!mem.contains(ThreatReturns.MEM_HOLD_FULL)) {
			if (faction.isPlayerFaction()) return;
			float paid = ThreatReserves.draw(home, Commodities.SUPPLIES, ask);
			com.fs.starfarer.api.campaign.econ.MarketAPI homeMarket = Global.getSector().getEconomy().getMarket(home);
			if (paid < ask && homeMarket != null) {
				paid += ThreatFrontlines.payFromOthers(homeMarket, homeMarket, Commodities.SUPPLIES, ask - paid);
			}
			float left = Math.max(0f, ask - paid);
			if (left > 0f) mem.set(MEM_OWED, left);
			else mem.unset(MEM_OWED);
			tally(tally, faction.getId(), want, paid, left, 0f);
			if (left > 0f && left >= perMonth) starve(f, left);
			return;
		}
		float paid = ThreatReturns.burnHold(f, ask);
		float restocked = 0f;
		if (paid < ask) {
			restocked = restockFleet(f, faction.getId());
			paid += ThreatReturns.burnHold(f, ask - paid);
		}
		float left = Math.max(0f, ask - paid);
		if (left > 0f) mem.set(MEM_OWED, left);
		else mem.unset(MEM_OWED);
		tally(tally, faction.getId(), want, paid, left, restocked);
		// a month of upkeep nobody could pay: its ships cannot be kept up out here
		if (left > 0f && left >= perMonth) {
			starve(f, left);
			return;
		}
		// home when low: only a fleet under orders turns itself round; a fleet
		// already sailing home, or a response force on vanilla's clock, runs on
		ThreatFleetOrders.Order o = orderOf(f);
		if (o == null || ThreatReturns.tracked(f) || mem.getBoolean(MEM_STARVED)) return;
		ThreatBases.Base base = ThreatBases.of(home);
		if (base == null || base.hyperLoc() == null) return;
		float ly = Misc.getDistanceLY(f.getLocationInHyperspace(), base.hyperLoc());
		float reserve = perMonth * (ThreatReach.days(ly) + 1f) / 30f;
		if (ThreatReturns.hold(f) >= reserve) return;
		restockFleet(f, faction.getId());
		if (ThreatReturns.hold(f) >= reserve) return;
		mem.set(MEM_STARVED, true);
		ThreatFleetOrders.standDown(o, "supplies low, " + (int) ThreatReturns.hold(f) + " aboard");
		if (faction.isPlayerFaction()) {
			ThreatNotice.titled("Task Force Low on Supplies").icon(faction)
					.line("%s turns home, %s", f.getName(), o.task())
					.line("%s supplies aboard, %s days", ThreatNotice.hl(Misc.getWithDGS((int) ThreatReturns.hold(f))),
							ThreatNotice.hl(String.valueOf((int) ThreatReturns.holdDays(f))))
					.send();
		}
	}

	/** Fills a fleet's hold back to its launch load from its faction's bases in its system; returns what it got. */
	protected static float restockFleet(CampaignFleetAPI f, String factionId) {
		if (!ThreatIncConfig.fleetResupply() || f == null) return 0f;
		float want = ThreatReturns.holdFull(f) - ThreatReturns.hold(f);
		if (want <= 0f) return 0f;
		StarSystemAPI system = f.getContainingLocation() instanceof StarSystemAPI
				? (StarSystemAPI) f.getContainingLocation() : null;
		float got = restock(factionId, system, want, f.getNameWithFaction());
		ThreatReturns.stockHold(f, got);
		return got;
	}
}
