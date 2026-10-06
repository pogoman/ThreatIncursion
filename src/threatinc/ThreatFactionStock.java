package threatinc;

import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;

/**
 * A human faction's stock plan: the hive's rules (ThreatFuel - trailing
 * demand, runs dry, surplus, one answer and one conversion a month) read per
 * faction over its war reserves, with hulls as a third stock (the hull pool's
 * free hulls and its debt against its yards). The forward-base builder
 * (ThreatFrontlines.build) builds the producer of what the faction runs dry
 * of and, with no slot free, tears down a producer of what it has in surplus
 * for it - never instantly: the new structure takes its vanilla build time.
 * User's decision 2026-10-06: "human and threat build logic should be
 * identical - build to bridge the shortage, and ... decommission industry or
 * structure in favour of new structure to address shortage, but this shouldnt
 * be instant". hw31 built fuel plants first on 350k-1.1M fuel while every
 * expedition waited on hulls (game-runs-2.md 31).
 */
public class ThreatFactionStock {

	/** Persistent map: factionId|commodity|field -> value. */
	public static final String KEY = "threatinc_factionStock";
	/** The hulls stock's id in the plan's keys. */
	public static final String HULLS = "hulls";
	/** A month: an answer or a conversion a faction has had to show. */
	protected static final float SHORT_DAYS = 30f;

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	protected static String key(String factionId, String commodityId, String field) {
		return factionId + "|" + commodityId + "|" + field;
	}

	protected static float num(String key) {
		Object v = data().get(key);
		return v instanceof Float ? (Float) v : 0f;
	}

	/** The industry a forward base builds for the stock: Fuel Production for fuel, Heavy Industry for supplies and hulls. */
	public static String producerId(String commodityId) {
		return Commodities.FUEL.equals(commodityId) ? Industries.FUELPROD : Industries.HEAVYINDUSTRY;
	}

	/** The market's producer of the stock, built or building; null without one. */
	public static Industry producerOn(MarketAPI market, String commodityId) {
		if (market == null) return null;
		if (Commodities.FUEL.equals(commodityId)) return market.getIndustry(Industries.FUELPROD);
		Industry p = market.getIndustry(Industries.HEAVYINDUSTRY);
		return p != null ? p : market.getIndustry(Industries.ORBITALWORKS);
	}

	/** Days a new producer takes to stand (vanilla's build time), never under SHORT_DAYS; the span every rule is read over. */
	public static float buildDays(String commodityId) {
		return Math.max(SHORT_DAYS, ThreatBuildCost.buildDays(producerId(commodityId)));
	}

	// ------------------------------------------------------------------
	// trailing demand: what the reserves paid out (ThreatReserves.draw), decayed
	// over a build time - ThreatFuel.ageDemand for one faction
	// ------------------------------------------------------------------

	/** Brings the faction's trailing sums up to now; a window opened reads the month's accrual as its demand until spending says otherwise. */
	protected static void ageDemand(String f, String c) {
		Map<String, Object> d = data();
		long now = Global.getSector().getClock().getTimestamp();
		float tau = buildDays(c);
		if (!(d.get(key(f, c, "since")) instanceof Long)) {
			d.put(key(f, c, "since"), now);
			d.put(key(f, c, "at"), now);
			d.put(key(f, c, "demand"), perMonth(f, c) / 30f * tau);
			d.put(key(f, c, "days"), tau);
			return;
		}
		Object at = d.get(key(f, c, "at"));
		d.put(key(f, c, "at"), now);
		if (!(at instanceof Long)) return;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
		if (days <= 0f) return;
		float k = (float) Math.exp(-days / tau);
		d.put(key(f, c, "demand"), num(key(f, c, "demand")) * k);
		d.put(key(f, c, "days"), num(key(f, c, "days")) * k + tau * (1f - k));
	}

	/** Opens or ages the faction's windows (ThreatFrontlines.build, each pass): a faction that spends nothing is still watched, so its stocks can read as surplus. */
	public static void watch(String factionId) {
		if (factionId == null) return;
		ageDemand(factionId, Commodities.FUEL);
		ageDemand(factionId, Commodities.SUPPLIES);
	}

	/** Demand on the faction's reserves of the commodity: what they paid out (ThreatReserves.draw). */
	public static void noteDemand(String factionId, String commodityId, float amount) {
		if (factionId == null || amount <= 0f) return;
		if (!Commodities.FUEL.equals(commodityId) && !Commodities.SUPPLIES.equals(commodityId)) return;
		ageDemand(factionId, commodityId);
		data().put(key(factionId, commodityId, "demand"), num(key(factionId, commodityId, "demand")) + amount);
	}

	/** The trailing demand on the faction's reserves a month, over the last build time. */
	public static float demandPerMonth(String f, String c) {
		float a = num(key(f, c, "demand")), b = num(key(f, c, "days"));
		Object at = data().get(key(f, c, "at"));
		if (at instanceof Long) {
			float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
			if (days > 0f) {
				float tau = buildDays(c);
				float k = (float) Math.exp(-days / tau);
				a *= k;
				b = b * k + tau * (1f - k);
			}
		}
		return b > 0f ? a / b * 30f : 0f;
	}

	/** Whether the faction's demand has been watched at least this many days. */
	public static boolean observed(String f, String c, float days) {
		Object since = data().get(key(f, c, "since"));
		return since instanceof Long && Global.getSector().getClock().getElapsedDaysSince((Long) since) >= days;
	}

	// ------------------------------------------------------------------
	// the stocks
	// ------------------------------------------------------------------

	/** The faction's war reserves of the commodity, every market summed. */
	public static float stock(String f, String c) {
		float sum = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(f)) sum += ThreatReserves.stock(m.getId(), c);
		return sum;
	}

	/** What the market banks of the commodity a month (ThreatReserves.accrualPer30). */
	public static float outputOf(MarketAPI m, String c) {
		return ThreatReserves.accrualPer30(m, c);
	}

	/** What the faction's reserves take in a month, every market summed. */
	public static float perMonth(String f, String c) {
		float sum = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(f)) sum += outputOf(m, c);
		return sum;
	}

	/**
	 * What the producers under construction on the faction's markets will bank
	 * a month once they stand: size - 2 units each (vanilla's figure for both
	 * industries) at the reserves' rate, so the planner does not stack a second
	 * on one that has not had its build time to show.
	 */
	public static float comingPerMonth(String f, String c) {
		float unit = Global.getSettings().getCommoditySpec(c).getEconUnit() * ThreatReserves.surplusMult(c);
		float sum = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(f)) {
			Industry p = producerOn(m, c);
			if (p == null || !p.isBuilding() || p.isUpgrading()) continue;
			sum += BaseIndustry.getSizeMult(Math.max(0, m.getSize() - 2)) * unit;
		}
		return sum;
	}

	/**
	 * Whether the faction runs dry of the commodity before a new producer could
	 * stand: stock < (demand - production - coming) x build months, read once
	 * a month of demand has been watched. For hulls: hullsShort.
	 */
	public static boolean runsDry(String f, String c) {
		if (HULLS.equals(c)) return hullsShort(f);
		if (!observed(f, c, SHORT_DAYS)) return false;
		float months = buildDays(c) / 30f;
		float production = perMonth(f, c) + comingPerMonth(f, c);
		return stock(f, c) < (demandPerMonth(f, c) - production) * months;
	}

	/** Whether the stock is in surplus: production covers the trailing demand and the stock covers it over a build time. For hulls: hullsSurplus. */
	public static boolean surplus(String f, String c) {
		if (HULLS.equals(c)) return hullsSurplus(f);
		if (!observed(f, c, buildDays(c))) return false;
		float demand = demandPerMonth(f, c);
		return perMonth(f, c) >= demand && stock(f, c) >= demand * buildDays(c) / 30f;
	}

	/** Whether the market's producer of the commodity can go: the stock is in surplus and the faction's banking less this market's still covers the demand. */
	public static boolean surplusProducer(MarketAPI m, String c) {
		if (m == null) return false;
		String f = m.getFactionId();
		if (!surplus(f, c)) return false;
		if (Commodities.SUPPLIES.equals(c) && hullsShort(f)) return false;
		return perMonth(f, c) - outputOf(m, c) >= demandPerMonth(f, c);
	}

	// ------------------------------------------------------------------
	// hulls: the hull pool read as a stock
	// ------------------------------------------------------------------

	/**
	 * Whether the faction is short of hulls: nothing free to send, or a debt
	 * its yards cannot rebuild within a Heavy Industry's build time. Answered
	 * by yards (Heavy Industry) and by the military structures the standing
	 * hulls come from (ThreatFrontlines.build). Never with the pool off.
	 */
	public static boolean hullsShort(String f) {
		if (f == null || !ThreatHulls.enabled()) return false;
		float debt = ThreatHulls.debt(f);
		if (debt > 0f && debt > ThreatHulls.productionFP(f) * buildDays(Commodities.SUPPLIES) / 30f) return true;
		return ThreatHulls.freeFP(f) <= 0f && ThreatHulls.standingFP(f) > 0f;
	}

	/** Whether the faction has hulls to spare: no debt and free hulls standing. */
	public static boolean hullsSurplus(String f) {
		if (f == null || !ThreatHulls.enabled()) return false;
		return ThreatHulls.debt(f) <= 0f && ThreatHulls.freeFP(f) > 0f;
	}

	// ------------------------------------------------------------------
	// what a faction wants built, and the pacing
	// ------------------------------------------------------------------

	/**
	 * The stock the faction is shortest of - hulls first (they bound every
	 * expedition in hw31), then of fuel and supplies the one with fewer months
	 * of stock against its trailing demand - or null when nothing runs dry.
	 */
	public static String shortest(String f) {
		if (f == null) return null;
		if (hullsShort(f)) return HULLS;
		boolean fuel = runsDry(f, Commodities.FUEL), supplies = runsDry(f, Commodities.SUPPLIES);
		if (fuel && supplies) {
			return months(f, Commodities.FUEL) <= months(f, Commodities.SUPPLIES) ? Commodities.FUEL : Commodities.SUPPLIES;
		}
		return fuel ? Commodities.FUEL : supplies ? Commodities.SUPPLIES : null;
	}

	/** Months the stock covers its trailing demand; large with no demand. */
	public static float months(String f, String c) {
		float demand = demandPerMonth(f, c);
		return demand > 0f ? stock(f, c) / demand : Float.MAX_VALUE;
	}

	/** Whether the faction may answer the stock with another producer: the last one has had SHORT_DAYS to show. */
	public static boolean mayAnswer(String f, String c) {
		Object at = data().get(key(f, c, "answered"));
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that a forward base started a producer for the stock. */
	public static void answered(String f, String c) {
		data().put(key(f, c, "answered"), Global.getSector().getClock().getTimestamp());
	}

	/** Whether the faction may tear a surplus producer down now: one a SHORT_DAYS, faction-wide. */
	public static boolean mayConvert(String f) {
		Object at = data().get(key(f, "*", "converted"));
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that a forward base tore a producer down. */
	public static void converted(String f) {
		data().put(key(f, "*", "converted"), Global.getSector().getClock().getTimestamp());
	}

	// ------------------------------------------------------------------
	// the shortage's answer held (user, 2026-10-06, hw33b): a producer a
	// market cannot pay for yet reserves its price there - upkeep, hunts and
	// other markets' builds draw the market only above it (ThreatReserves.floor)
	// ------------------------------------------------------------------

	/** Days a hold stands unrenewed: the builder renews it every pass (ThreatFrontlines.build), so a lapsed one is a base that stopped building. */
	protected static final float HOLD_DAYS = 10f;

	protected static String holdKey(MarketAPI market, String field) {
		return "hold|" + market.getId() + "|" + field;
	}

	/**
	 * Holds supplies at the market for the producer it cannot pay for: the
	 * price less what it has to hand. hw33b's Hegemony lost its yards with
	 * Chicomoztoc and queued Heavy Industry on four bases for 1,200 days, each
	 * read "5,000 wanted, 1,008 to hand" while its fleets' upkeep took the rest
	 * - the navy starving the yards meant to rebuild it.
	 */
	public static void hold(MarketAPI market, String industryId, float wanted) {
		if (market == null || industryId == null || wanted <= 0f) return;
		Map<String, Object> d = data();
		d.put(holdKey(market, "industry"), industryId);
		d.put(holdKey(market, "wanted"), Float.valueOf(wanted));
		d.put(holdKey(market, "at"), Global.getSector().getClock().getTimestamp());
	}

	/** Lifts the market's hold (the builder's own pass, which reads the whole stock, and a build that paid). */
	public static void release(MarketAPI market) {
		if (market == null) return;
		Map<String, Object> d = data();
		d.remove(holdKey(market, "industry"));
		d.remove(holdKey(market, "wanted"));
		d.remove(holdKey(market, "at"));
	}

	/** Supplies the market holds for a producer it cannot yet pay; 0 without a live hold. */
	public static float held(MarketAPI market) {
		if (market == null) return 0f;
		Object at = data().get(holdKey(market, "at"));
		if (!(at instanceof Long)) return 0f;
		if (Global.getSector().getClock().getElapsedDaysSince((Long) at) > HOLD_DAYS) return 0f;
		return num(holdKey(market, "wanted"));
	}

	/** The producer the market holds for, or null. */
	public static String heldIndustry(MarketAPI market) {
		if (market == null || held(market) <= 0f) return null;
		Object v = data().get(holdKey(market, "industry"));
		return v instanceof String ? (String) v : null;
	}

	/** The faction's market holding supplies for a producer, the one holding the most; null without one. */
	public static MarketAPI heldMarket(String f) {
		MarketAPI best = null;
		float most = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(f)) {
			float h = held(m);
			if (h > most) {
				most = h;
				best = m;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// trade through the war (user, 2026-10-06): what an ally's convoy brings
	// ------------------------------------------------------------------

	/**
	 * What an ally's convoy should bring the faction of the commodity: a held
	 * producer's price still wanted (supplies), else what the stock runs dry
	 * by before a producer could stand - (demand - production - coming) x
	 * build months, less the stock; 0 when the plan reads neither.
	 */
	public static float aidNeed(String f, String c) {
		if (f == null || c == null) return 0f;
		float need = 0f;
		if (Commodities.SUPPLIES.equals(c)) {
			MarketAPI m = heldMarket(f);
			if (m != null) need = held(m);
		}
		if (runsDry(f, c)) {
			float months = buildDays(c) / 30f;
			float gap = (demandPerMonth(f, c) - perMonth(f, c) - comingPerMonth(f, c)) * months - stock(f, c);
			need = Math.max(need, gap);
		}
		return Math.max(0f, need);
	}

	/** Where the convoy lands: the market holding for a producer, else the faction's depot with the least of the commodity. */
	public static MarketAPI aidTarget(String f, String c) {
		if (f == null || c == null) return null;
		if (Commodities.SUPPLIES.equals(c)) {
			MarketAPI m = heldMarket(f);
			if (m != null && ThreatReserves.hasDepot(m)) return m;
		}
		MarketAPI best = null;
		float least = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(f)) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null || !ThreatReserves.hasDepot(m)) continue;
			float s = ThreatReserves.stock(m.getId(), c);
			if (s < least) {
				least = s;
				best = m;
			}
		}
		return best;
	}

	/** Whether allies may sail the faction another convoy of the stock: the last has had SHORT_DAYS to land. */
	public static boolean mayAid(String f, String c) {
		Object at = data().get(key(f, c, "aided"));
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that allies sailed the faction a convoy of the stock. */
	public static void aided(String f, String c) {
		data().put(key(f, c, "aided"), Global.getSector().getClock().getTimestamp());
	}

	/** One line of the faction's plan for the log: each stock, its months of cover and verdict. */
	public static String describe(String f) {
		StringBuilder b = new StringBuilder();
		for (String c : new String[] { Commodities.FUEL, Commodities.SUPPLIES }) {
			float m = months(f, c);
			b.append(c).append(' ').append((int) stock(f, c)).append(" (+").append((int) perMonth(f, c)).append("/mo, ")
					.append((int) demandPerMonth(f, c)).append("/mo spent, covers ")
					.append(m == Float.MAX_VALUE ? "-" : String.format("%.1f", m)).append(" months, ")
					.append(runsDry(f, c) ? "runs dry" : surplus(f, c) ? "surplus" : "holds").append("); ");
		}
		if (ThreatHulls.enabled()) {
			b.append("hulls ").append((int) ThreatHulls.freeFP(f)).append(" free of ").append((int) ThreatHulls.standingFP(f))
					.append(", ").append((int) ThreatHulls.debt(f)).append(" to rebuild at ")
					.append((int) ThreatHulls.productionFP(f)).append("/mo (")
					.append(hullsShort(f) ? "short" : hullsSurplus(f) ? "surplus" : "holds").append(')');
		}
		return b.toString();
	}
}
