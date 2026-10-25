package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Colony upkeep by size for the factions' FORWARD BASES (2026-09-30, user's
 * call; docs/hive-economy.md "Size upkeep"): a base of size 3 or more costs
 * sizeUpkeepAt3 x sizeUpkeepRatio^(size - 3) supplies a month - 100, 250,
 * 625 ... - and the share of it paid sets its growth (growthRate): the
 * break-even share (upkeepBreakEven) holds its size, all of it grows it at
 * the full pace, none of it takes a size off every starveDaysPerSize days
 * (ThreatFrontlines.feedSize).
 *
 * <p>HIVE WORLDS PAY NONE since 2026-10-25 (phase B of
 * docs/strata-are-the-threat.md): a hive's size is its strata, grown by its
 * Fabrication Core on metals and fuel (ThreatColonyManager.coreState); the
 * swarm keeps no supplies stock and its fleets burn none. What stays here
 * of the hive is the import cut a blockade puts on its fuel reaching the
 * stock (importCut, reachesStock) and the census line.
 */
public class ThreatColonyUpkeep {

	public static final String KEY = "threatinc_colonyUpkeep";
	/** Market memory: the share of its upkeep the colony was paid on the last feed (none: 1). */
	public static final String MEM_FED = "$threatinc_fedShare";
	/** The share of its imports a disrupted port lets through. */
	public static final float PORT_DOWN_CUT = 0.5f;

	public static boolean enabled() {
		return ThreatIncConfig.sizeUpkeep() && ThreatFuel.enabled();
	}

	/** Hive worlds pay no size upkeep since 2026-10-25 (phase B): their size is their strata. enabled() is the forward bases' gate. */
	public static boolean hives() {
		return false;
	}

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	/** Supplies a month a colony of this size costs: 0 below size 3. */
	public static float perMonth(int size) {
		return threatinc.rules.HiveRules.sizeUpkeepPerMonth(size, ThreatIncConfig.sizeUpkeepAt3(),
				ThreatIncConfig.sizeUpkeepRatio());
	}

	/** The share of its upkeep that holds a colony at its size. */
	public static float breakEven() {
		return threatinc.rules.HiveRules.breakEven(ThreatIncConfig.upkeepBreakEven());
	}

	/** Days a colony paid nothing takes to starve through a level. */
	public static float starveDays() {
		return Math.max(1f, ThreatIncConfig.starveDaysPerSize());
	}

	/** RETIRED 2026-10-24: the strata are the figure (ThreatStrata) and the size follows them with no hysteresis; kept for older notes. */
	public static final float SHRINK_MARGIN = 0.5f;

	/**
	 * The share of its upkeep the colony was last paid (a size-2 world: the
	 * break-even share plus what it was fed toward size 3); 1 while it grows
	 * free or has not been fed yet.
	 */
	public static float fedShare(MarketAPI market) {
		if (market == null || !market.getMemoryWithoutUpdate().contains(MEM_FED)) return 1f;
		return Math.max(0f, Math.min(1f, market.getMemoryWithoutUpdate().getFloat(MEM_FED)));
	}

	/**
	 * The pace a share paid gives, of the full growth pace: 0 at the
	 * break-even share, 1 paid in full; below break-even it is negative, -1 at
	 * nothing (a size lost per starveDays).
	 */
	public static float growthRate(float fed) {
		return threatinc.rules.HiveRules.growthRate(fed, breakEven());
	}

	/** The share of a hive world's imports that does not arrive: a human blockade's, or half while its port is down. */
	public static float importCut(MarketAPI market) {
		if (market == null) return 0f;
		float cut = market.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY);
		Industry port = ThreatColonyManager.getPort(market);
		// no port on a hive since phase A (2026-10-25): only one an older save still has counts while disrupted
		if (port != null && port.isDisrupted()) cut = Math.max(cut, PORT_DOWN_CUT);
		return Math.max(0f, Math.min(1f, cut));
	}

	/** Supplies a month the world's own forge makes, as ThreatFuel banks them. */
	public static float localSupplies(MarketAPI market) {
		CommodityOnMarketAPI com = market.getCommodityData(Commodities.SUPPLIES);
		if (com == null || com.getMaxSupply() <= 0) return 0f;
		return BaseIndustry.getSizeMult(com.getMaxSupply()) * com.getCommodity().getEconUnit()
				* ThreatIncConfig.hiveSurplusMult();
	}

	/**
	 * What of a hive world's monthly output, in the commodity's units, reaches
	 * the hive's stock: all of it, less what a blockade or a dead port keeps
	 * from leaving - of supplies, beyond what the world's own upkeep takes.
	 */
	public static float reachesStock(MarketAPI market, String commodityId, float units) {
		if (!enabled() || units <= 0f) return units;
		float cut = importCut(market);
		if (cut <= 0f) return units;
		float keep = 0f;
		if (Commodities.SUPPLIES.equals(commodityId)) {
			CommodityOnMarketAPI com = market.getCommodityData(commodityId);
			float perUnit = com != null ? com.getCommodity().getEconUnit() * ThreatIncConfig.hiveSurplusMult() : 0f;
			if (perUnit > 0f) keep = Math.min(units, perMonth(market.getSize()) / perUnit);
		}
		return keep + (units - keep) * (1f - cut);
	}

	protected static void add(String key, float v) {
		Object o = data().get(key);
		data().put(key, (o instanceof Float ? (Float) o : 0f) + v);
	}

	protected static float take(String key) {
		Object o = data().get(key);
		data().put(key, 0f);
		return o instanceof Float ? (Float) o : 0f;
	}

	/** The month's upkeep line for the census log, and the month's tallies reset. */
	public static void logMonth() {
		int growing = 0, holding = 0, shrinking = 0, free = 0, blockaded = 0, portsDown = 0;
		float perMonth = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			float u = perMonth(m.getSize());
			perMonth += u;
			if (m.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY) > 0f) blockaded++;
			Industry port = ThreatColonyManager.getPort(m);
			if (port != null && port.isDisrupted()) portsDown++;
			if (u <= 0f) {
				free++;
				continue;
			}
			int state = ThreatWarBoard.fedState(m);
			if (state == ThreatWarBoard.GROWING) growing++;
			else if (state == ThreatWarBoard.STARVING) shrinking++;
			else holding++;
		}
		ThreatIncConfig.log("Hive worlds: growing " + growing + ", holding " + holding + ", shrinking " + shrinking
				+ ", free " + free + "; blockaded " + blockaded + ", ports down " + portsDown + " (no upkeep since 2026-10-25)");
	}
}
