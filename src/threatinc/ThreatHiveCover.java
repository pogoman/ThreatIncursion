package threatinc;

import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;

/**
 * Hive cover (2026-10-18, the user, after the audit "are the humans cheating"):
 * the hive's one stock covers a hive world's vanilla shortages exactly as a
 * human colony's depot covers its own (ThreatReserves.coverShortage). Until
 * now only the humans had it: a mobilised colony short of a commodity had
 * its depot issue a 30-day trade modifier, so its industries kept full
 * output and its guns their multiplier, while a blockaded hive world's
 * Ground Defenses went dead for want of heavy machinery (facts, 2026-10-12)
 * and nothing covered it - hw198a: 344k supplies, 840k fuel, 19k marines and
 * 9.6k heavy armaments issued as cover on the human side, 0 on the hive's.
 *
 * Each poll, every live hive world with a commodity in deficit (demand above
 * availability, the cover's own modifier ignored) and no cover in force gets
 * one: whole units, applied as a trade modifier for reserveShortageCoverDays,
 * never more than reserveShortageCoverFraction of the stock it is paid from.
 * Fuel is paid from the fuel stock, everything else from the supplies stock
 * at vanilla's base-price ratio (heavy machinery 150 : supplies 100), so the
 * hive keeps its one ledger - the pool is the hive's depot, as a hive mind's
 * is. Tallied under "cover" in the census. Knob threatinc_hiveCover.
 */
public class ThreatHiveCover {

	/**
	 * What the cover pays for: the war stocks and the guns' inputs only. Never a
	 * hive world's vanilla population demand - hw200 (2026-10-18 18:11): the first
	 * cut covered every deficit and spent 636k / 563k / 583k supplies on
	 * recreational drugs in a round, the stock fell from 80k to 5k and the hives
	 * stalled at 27 / 51 / 43. The humans' cover is as narrow (ThreatReserves.COMMODITIES).
	 */
	protected static final String[] COVERED = { Commodities.FUEL, Commodities.SUPPLIES, Commodities.HEAVY_MACHINERY,
			Commodities.HAND_WEAPONS };

	protected static boolean covered(String commodityId) {
		for (String c : COVERED) {
			if (c.equals(commodityId)) return true;
		}
		return false;
	}

	public static boolean enabled() {
		return ThreatIncConfig.hiveCover() && ThreatFuel.enabled();
	}

	/** The fast poll (IncursionManager, beside ThreatReserves.poll): one cover issue per short commodity per hive world. */
	public static void poll(float days) {
		if (!enabled() || days <= 0f) return;
		float coverDays = ThreatIncConfig.reserveShortageCoverDays();
		float fraction = ThreatIncConfig.reserveShortageCoverFraction();
		if (coverDays <= 0f || fraction <= 0f) return;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market == null || !market.isInEconomy()) continue;
			for (CommodityOnMarketAPI com : market.getCommoditiesCopy()) {
				if (com == null || com.getMaxDemand() <= 0 || !covered(com.getId())) continue;
				if (ThreatReserves.coverQuantity(com) > 0f) continue;
				int deficit = ThreatReserves.deficitUnits(com);
				if (deficit <= 0) continue;
				cover(market, com, deficit, coverDays, fraction);
			}
		}
	}

	/** The stock a commodity's cover is paid from: fuel its own, everything else supplies. */
	protected static String stockFor(String commodityId) {
		return Commodities.FUEL.equals(commodityId) ? Commodities.FUEL : Commodities.SUPPLIES;
	}

	/** Units of the paying stock one unit of the commodity costs: vanilla's base-price ratio, 1 for the stock's own commodity. */
	protected static float priceRatio(CommodityOnMarketAPI com, String stockId) {
		if (stockId.equals(com.getId())) return 1f;
		float price = com.getCommodity().getBasePrice();
		float stockPrice = com.getMarket().getCommodityData(stockId) != null
				? com.getMarket().getCommodityData(stockId).getCommodity().getBasePrice() : 0f;
		if (price <= 0f || stockPrice <= 0f) return 1f;
		return price / stockPrice;
	}

	protected static void cover(MarketAPI market, CommodityOnMarketAPI com, int deficit, float coverDays, float fraction) {
		String stockId = stockFor(com.getId());
		float ratio = priceRatio(com, stockId);
		float unit = com.getCommodity().getEconUnit();
		if (unit <= 0f || ratio <= 0f) return;
		float stock = ThreatFuel.stock(stockId);
		float spendable = Math.max(0f, stock * fraction);
		int units = (int) Math.min((double) deficit, Math.floor(spendable / (unit * ratio)));
		String name = com.getCommodity().getName().toLowerCase();
		if (units <= 0) {
			ThreatIncConfig.logQuiet("hivecover:" + market.getId() + ":" + com.getId(), "Hive cover: " + market.getName()
					+ " short " + deficit + " units of " + name + ", the " + stockId + " stock (" + (int) stock
					+ ") cannot cover one");
			return;
		}
		float qty = com.getQuantityForModValue(units);
		if (qty <= 0f) qty = units * unit;
		float cost = Math.min(qty * ratio, spendable);
		if (!ThreatFuel.pay(stockId, cost, "cover")) return;
		com.addTradeModPlus(ThreatReserves.COVER_SOURCE, qty, coverDays);
		ThreatIncConfig.log("Hive cover: " + market.getName() + " issues " + (int) qty + " " + name + " against a "
				+ deficit + "-unit shortage (" + units + " of " + deficit + " units covered for " + (int) coverDays
				+ " days) for " + (int) cost + " " + stockId + "; " + (int) (stock - cost) + " left in stock");
	}
}
