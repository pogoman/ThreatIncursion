package threatinc;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Fleet upkeep (2026-09-30): an NPC fleet the strategy layer sent out - a
 * hunt, a siege, a sortie, a convoy, anything provisioned from a base
 * (ThreatReturns.MEM_HOME) - burns its ships' vanilla supplies per month
 * (ThreatFrontlines.maintenancePerMonth: maintenance only, no repair or CR
 * recovery) for every day it is out, as a forward base's garrison already
 * did. Its base pays, then the faction's other markets whose stock reaches
 * it (ThreatFrontlines.payFromOthers); what nobody can pay is owed on the
 * fleet and asked again next pass. Before this a sortie paid a flat
 * expeditionSuppliesPerPoint at launch whatever its time out, 80% of it back
 * with the hulls: a 3,000 FP hunt out three months burned ~700 supplies where
 * vanilla ships burn ~8-9k, and the factions' supplies piled up (Persean
 * 11k -> 239k in 71 months). The launch draw now all comes back with the
 * surviving hulls (ThreatReturns.suppliesBack). Vanilla's own patrols pay
 * through their markets' demand, which the reserves never bank. Not the
 * player's fleets (they keep the old rule) nor the Threat's (it pays in
 * fleet points).
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
		// faction -> {fleets, wanted, paid, owed}
		Map<String, float[]> tally = new LinkedHashMap<String, float[]>();
		for (LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI f : loc.getFleets()) charge(f, now, tally);
		}
		for (Map.Entry<String, float[]> e : tally.entrySet()) {
			float[] t = e.getValue();
			ThreatIncConfig.log("Fleet upkeep: " + e.getKey() + " " + (int) t[0] + " fleets out, paid " + (int) t[2]
					+ " of " + (int) t[1] + " supplies" + (t[3] >= 1f ? ", " + (int) t[3] + " owed" : ""));
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
		for (ThreatFleetOrders.Order o : new java.util.ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (o.fleet != f) continue;
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

	protected static void charge(CampaignFleetAPI f, long now, Map<String, float[]> tally) {
		if (f == null || !f.isAlive() || f.isDespawning() || f.isPlayerFleet()) return;
		String home = ThreatReturns.homeOf(f);
		if (home == null) return;
		FactionAPI faction = f.getFaction();
		if (faction == null || faction.isPlayerFaction() || Factions.THREAT.equals(faction.getId())) return;
		// a forward base's garrison pays through its link (ThreatFrontlines.payUpkeep)
		if (ThreatFrontlines.isGuard(f)) return;
		MemoryAPI mem = f.getMemoryWithoutUpdate();
		Object at = mem.get(MEM_AT);
		mem.set(MEM_AT, now);
		// first sight starts the clock: nothing is billed for before it
		if (!(at instanceof Long)) return;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
		if (days <= 0f) return;
		float want = ThreatFrontlines.maintenancePerMonth(f) * days / 30f;
		float owed = mem.getFloat(MEM_OWED);
		float ask = want + owed;
		float paid = ThreatReserves.draw(home, Commodities.SUPPLIES, ask);
		MarketAPI homeMarket = Global.getSector().getEconomy().getMarket(home);
		if (paid < ask && homeMarket != null) {
			paid += ThreatFrontlines.payFromOthers(homeMarket, homeMarket, Commodities.SUPPLIES, ask - paid);
		}
		float left = Math.max(0f, ask - paid);
		if (left > 0f) mem.set(MEM_OWED, left);
		else mem.unset(MEM_OWED);
		// a month of upkeep nobody could pay: its ships cannot be kept up out here
		if (left > 0f && left >= ThreatFrontlines.maintenancePerMonth(f)) starve(f, left);
		float[] t = tally.get(faction.getId());
		if (t == null) {
			t = new float[4];
			tally.put(faction.getId(), t);
		}
		t[0] += 1f;
		t[1] += want;
		t[2] += paid;
		t[3] += left;
	}
}
