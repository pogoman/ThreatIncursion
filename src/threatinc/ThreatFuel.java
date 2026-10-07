package threatinc;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.util.Misc;

/**
 * The hive's fuel and supplies (2026-09-30): the Threat pays passage and
 * founds colonies the way the factions do. A fleet sent across hyperspace
 * draws fleet points / 25 x light-years x expeditionFuelPerPointLY - the
 * factions' own rate - from one fuel stock the whole hive shares, as vanilla's
 * broadcast availability shares one fuel plant's output with every hive
 * world. The factions' rate is the round trip; a fleet that stays where it is
 * sent (a Seeding Swarm, a reinforcement) pays the way out only
 * (ThreatReturns.RETURN_LEG_SHARE). A Seeding Swarm also carries what a
 * faction's forward base costs (ThreatOutposts.npcCost: outpostSupplies,
 * outpostFuel) from the two stocks - a single swarm founded a hive for its
 * structures' fleet points alone.
 *
 * <p>Each stock fills as a faction's reserve does (ThreatReserves.accrualPer30,
 * productionShare): each world's availability, BaseIndustry.getSizeMult of it,
 * x the commodity's econ unit x hiveSurplusMult (the swarm's own rate since 2026-10-02), a month - summed over the
 * hive, and no more than it makes above its own demand or, if larger, the
 * sector's best exporter it imports from times reserveBankImportsMult. A
 * faction world banks only what is left over its peacetime demand, which
 * stands for its trade and civilian traffic; a hive world runs no trade
 * fleets (maintainGarrisons suppresses them), so its stock is all its fleets'.
 * Read as surplus, the imports that meet a Megaport's demand banked 0 and
 * grounded every fleet (first test, 2026-09-30). Before this the swarm moved
 * for free inside its fuel range while the factions paid for every
 * light-year, and in the 75-month test it shipped 1,015 swarms between
 * systems and grew to ~100k FP while no faction could fuel a hunt against it.
 */
public class ThreatFuel {

	public static final String KEY = "threatinc_hiveFuel";
	private static final String AT = "at";
	private static final String HELD = "heldMonth";
	/** The two stocks: fuel keeps the keys it was saved under before supplies joined it. */
	private static final String[] STOCKED = { Commodities.FUEL, Commodities.SUPPLIES };

	/** Fleet memory: the supplies and fuel a Seeding Swarm carries to found its colony. */
	public static final String MEM_FOUND_SUPPLIES = "$threatinc_foundSupplies";
	public static final String MEM_FOUND_FUEL = "$threatinc_foundFuel";
	/** Fleet memory: when that cargo was drawn (a refund takes back only what its demand has left). */
	public static final String MEM_FOUND_AT = "$threatinc_foundAt";

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	public static boolean enabled() {
		return ThreatIncConfig.threatPaysPassage();
	}

	/**
	 * Whether the swarm's bombardment burns the fuel stock (2026-10-01,
	 * threatPaysOrdnance): tactical days at bombardFuelPerFPDay, saturation's
	 * pour at satFuelPerFPDay, as every besieger pays them
	 * (ThreatGroundFronts.payOrdnance, ThreatStrikeFGI.saturationPass).
	 */
	public static boolean paysOrdnance() {
		return enabled() && ThreatIncConfig.threatPaysOrdnance();
	}

	protected static String stockKey(String commodityId) {
		return Commodities.FUEL.equals(commodityId) ? "stock" : "stock_" + commodityId;
	}

	protected static String spentKey(String commodityId) {
		return Commodities.FUEL.equals(commodityId) ? "spentMonth" : "spentMonth_" + commodityId;
	}

	/** Fuel in the hive's stock. */
	public static float stock() {
		return stock(Commodities.FUEL);
	}

	/** The hive's stock of fuel or supplies. */
	public static float stock(String commodityId) {
		Object v = data().get(stockKey(commodityId));
		return v instanceof Float ? (Float) v : 0f;
	}

	protected static void setStock(String commodityId, float amount) {
		data().put(stockKey(commodityId), Math.max(0f, amount));
	}

	/** Market id -> the supplies reserved for the forge the planner waits to build there (reserve). */
	public static final String KEY_RESERVED = "threatinc_hiveSuppliesReserved";

	protected static Map<String, Float> reservedMap() {
		return ThreatIncData.map(KEY_RESERVED);
	}

	/**
	 * THE SHORTAGE'S ANSWER IS PAID FIRST (2026-10-07, after hw60): a supplies producer the planner waits to
	 * build or convert to has its price reserved, and the fleets' upkeep, the colonies' sustenance and a
	 * founding draw only on the stock above it (free). hw60c made 197k supplies a month and spent 207k with
	 * the stock at 0-7k, 2.5M fuel banked and 294k FP idle: 72 "waiting build" turns and two forge
	 * conversions in 77 months, because everything else drew first and a forge's 5k was never there. The
	 * humans' yards-first rule (ThreatFactionStock), the same for the hive. Capped at a month's production.
	 */
	public static float reserved(String commodityId) {
		if (!Commodities.SUPPLIES.equals(commodityId) || !planned()) return 0f;
		float sum = 0f;
		for (Float v : reservedMap().values()) if (v != null) sum += v;
		return Math.min(sum, Math.max(0f, perMonth(commodityId)));
	}

	/** The stock above what is reserved for the planner's answer (reserved): what everything else may draw. */
	public static float free(String commodityId) {
		return Math.max(0f, stock(commodityId) - reserved(commodityId));
	}

	/** Reserves {@code supplies} for the producer the market waits to build; 0 or less releases it. */
	public static void reserve(String marketId, float supplies) {
		if (marketId == null) return;
		if (supplies > 0f) reservedMap().put(marketId, supplies);
		else reservedMap().remove(marketId);
	}

	/** Fuel the hive banks a month. */
	public static float perMonth() {
		return perMonth(Commodities.FUEL);
	}

	/**
	 * What the hive banks of the commodity a month. While structures cost
	 * supplies (ThreatBuildCost) the hive earns what it makes: every plant's
	 * and forge's whole output, getSizeMult of it x the econ unit x
	 * hiveSurplusMult, summed. No Spaceport demand is taken off - vanilla's
	 * stands for trade and civilian traffic, and a hive runs none (its fleets pay
	 * passage from the stock instead) - and nothing comes from imports, as a
	 * hive does not trade. With it off, a faction reserve's rule
	 * (ThreatReserves.accrualPer30 x productionShare): each world's output over
	 * its own demand, held to the better of that and the sector's best exporter
	 * x reserveBankImportsMult. That demand is each hive Spaceport's size-2,
	 * exactly a same-size plant's or forge's output, so the hive banked the
	 * exporter figure whatever it built (h33a: 21 fuel plants, 0 of their fuel;
	 * 4 forges making 24 units of supplies against 199 wanted).
	 */
	public static float perMonth(String commodityId) {
		float banked = 0f, made = 0f, foreign = 0f, unit = 0f;
		boolean own = ThreatBuildCost.enabled();
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			CommodityOnMarketAPI com = m.getCommodityData(commodityId);
			if (com == null) continue;
			unit = com.getCommodity().getEconUnit();
			if (own) {
				// what a blockade or a dead port keeps from leaving never reaches it (ThreatColonyUpkeep)
				if (com.getMaxSupply() > 0) {
					made += ThreatColonyUpkeep.reachesStock(m, commodityId, BaseIndustry.getSizeMult(com.getMaxSupply()));
				}
				continue;
			}
			float have = ThreatReserves.structuralAvailable(com);
			if (have > 0f) banked += BaseIndustry.getSizeMult(have);
			float left = Math.min(com.getMaxSupply(), have) - WarFootingDemand.peacetimeDemand(m, com);
			if (left > 0f) made += BaseIndustry.getSizeMult(left);
			if (com.getCommodityMarketData() != null) {
				foreign = Math.max(foreign, com.getCommodityMarketData().getMaxExportGlobal());
			}
		}
		if (own) return made * unit * ThreatIncConfig.hiveSurplusMult();
		float budget = Math.max(made, BaseIndustry.getSizeMult(foreign) * ThreatIncConfig.reserveBankImportsMult());
		return Math.min(banked, budget) * unit * ThreatIncConfig.hiveSurplusMult();
	}

	/**
	 * Banks what was made since the last call; once a poll. The first sight of
	 * a stock (a new game, or a save from before it) starts it at
	 * FAB_ENDOWMENT_DAYS of production, as the FP banks were endowed.
	 */
	public static void accrue() {
		long now = Global.getSector().getClock().getTimestamp();
		Object last = data().get(AT);
		data().put(AT, now);
		float days = last instanceof Long ? Global.getSector().getClock().getElapsedDaysSince((Long) last) : 0f;
		for (String c : STOCKED) {
			if (!data().containsKey(stockKey(c))) {
				setStock(c, perMonth(c) * ThreatColonyManager.FAB_ENDOWMENT_DAYS / 30f);
			} else if (days > 0f) {
				setStock(c, stock(c) + perMonth(c) * days / 30f);
			}
			// the days observed run on whether anything is spent or not: a month
			// with no demand is a month of none (demandPerMonth)
			ageDemand(c);
		}
	}

	/** Light-years between two star systems (0 for the same one or a null). */
	public static float ly(StarSystemAPI from, StarSystemAPI to) {
		if (from == null || to == null || from == to) return 0f;
		return Misc.getDistanceLY(from.getLocation(), to.getLocation());
	}

	/** Fuel a fleet of {@code fp} draws over {@code ly}: both ways, or the way out only. */
	public static float passage(float fp, float ly, boolean roundTrip) {
		if (!enabled()) return 0f;
		return threatinc.rules.SpreadRules.passageFuel(fp, ly, roundTrip, IncursionManager.FP_PER_RESPONSE_DIFFICULTY,
				ThreatIncConfig.expeditionFuelPerPointLY(), ThreatReturns.RETURN_LEG_SHARE);
	}

	public static boolean canPay(float fuel) {
		return canPay(Commodities.FUEL, fuel);
	}

	/** Whether the stock holds {@code amount}; what it cannot pay is kept for held() to book as demand. */
	public static boolean canPay(String commodityId, float amount) {
		if (amount <= 0f || stock(commodityId) >= amount) return true;
		UNMET.put(commodityId, amount);
		return false;
	}

	/** Draws {@code fuel} from the stock; false (nothing drawn) if the stock is short. */
	public static boolean pay(float fuel) {
		return pay(Commodities.FUEL, fuel);
	}

	/** Draws {@code amount} from the stock, booked as demand on it (noteDemand); false (nothing drawn) if the stock is short. */
	public static boolean pay(String commodityId, float amount) {
		if (amount <= 0f) return true;
		float have = stock(commodityId);
		if (have < amount) {
			UNMET.put(commodityId, amount);
			return false;
		}
		setStock(commodityId, have - amount);
		add(spentKey(commodityId), amount);
		noteDemand(commodityId, amount);
		return true;
	}

	/**
	 * Puts {@code amount} back in the stock (a withdrawing wave's cargo):
	 * neither spent nor demanded after all. The bill was paid {@code daysAgo}
	 * and its demand has decayed since, so only what is left of it is taken
	 * back - the whole of it would take unrelated demand with it (review
	 * 2026-10-01).
	 */
	public static void deposit(String commodityId, float amount, float daysAgo) {
		if (amount <= 0f) return;
		setStock(commodityId, stock(commodityId) + amount);
		add(spentKey(commodityId), -amount);
		noteDemand(commodityId, -amount * (float) Math.exp(-Math.max(0f, daysAgo) / buildDays(commodityId)));
	}

	/**
	 * What founding a colony costs in stock: a faction's forward base
	 * (ThreatOutposts.npcCost), {supplies, fuel}, and under size upkeep the
	 * structures the colony is founded with (ThreatBuildCost.foundingKit);
	 * nothing while the Threat pays no passage.
	 */
	public static float[] foundingCost() {
		if (!enabled()) return new float[] { 0f, 0f };
		float[] cost = ThreatOutposts.npcCost();
		return new float[] { cost[0] + ThreatBuildCost.foundingKit(), cost[1] };
	}

	/**
	 * Whether both stocks hold a founding and the wave's fuel on top of it. What
	 * either cannot pay is left for held() to book as demand - the wave's caller
	 * holds it - never a shortage of its own (2026-10-01).
	 */
	public static boolean canFound(float passageFuel) {
		float[] cost = foundingCost();
		UNMET.clear();
		// above the planner's reserve (reserved): a founding never takes the forge's price
		boolean supplies = cost[0] <= 0f || free(Commodities.SUPPLIES) >= cost[0];
		if (!supplies) UNMET.put(Commodities.SUPPLIES, cost[0]);
		boolean fuel = canPay(Commodities.FUEL, cost[1] + passageFuel);
		return supplies && fuel;
	}

	/**
	 * Days a colony-upkeep shortfall stays noted (shortOf), and the planner's
	 * pace: one answer to a stock (mayAnswer) and one conversion (mayConvert) a
	 * hive per this many days - a month, the planner's own tick.
	 */
	protected static final float SHORT_DAYS = 30f;

	/**
	 * Notes that the colonies' sustenance went unpaid (ThreatColonyUpkeep.feed):
	 * the one shortage the planner answers on its own, a forge a SHORT_DAYS
	 * while it stands. Everything else a stock could not pay is demand on it
	 * (noteDemand), weighed against what it holds and makes (runsDry). Until
	 * 2026-10-01 any held trip and any build the stock could not pay noted a
	 * month's shortage and the planner built a plant for each: the overnight
	 * tests' logs count 59 fuel plants built "for fuel short" and 26 as
	 * spares, none ever retired, and ng5a ended on 538k fuel.
	 */
	public static void noteShort(String commodityId) {
		data().put("shortAt_" + commodityId, Global.getSector().getClock().getTimestamp());
	}

	/** Whether the colonies' sustenance went unpaid within SHORT_DAYS (noteShort). */
	public static boolean shortOf(String commodityId) {
		Object v = data().get("shortAt_" + commodityId);
		return v instanceof Long && Global.getSector().getClock().getElapsedDaysSince((Long) v) < SHORT_DAYS;
	}

	/**
	 * Whether the hive planner may answer the commodity with another plant or
	 * forge: the hive wants one (wanted), and the last one built for it has had
	 * SHORT_DAYS to show in the month's take. h29a built 20 fuel plants in one
	 * tick for one noted shortage.
	 */
	public static boolean mayAnswer(String commodityId) {
		if (!wanted(commodityId)) return false;
		Object at = data().get("answeredAt_" + commodityId);
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Whether a SHORT_DAYS has passed since the planner last built a producer of the commodity for its stock (answered): the pacing of mayAnswer without its shortage. */
	public static boolean mayInvest(String commodityId) {
		Object at = data().get("answeredAt_" + commodityId);
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that the planner built a plant for the shortage (mayAnswer). */
	public static void answered(String commodityId) {
		data().put("answeredAt_" + commodityId, Global.getSector().getClock().getTimestamp());
	}

	// ------------------------------------------------------------------
	// trailing demand: what the planner builds and retires producers by
	// (2026-10-01, user's call; docs/hive-economy.md "Idle stock")
	// ------------------------------------------------------------------

	/** The industry that makes the commodity's stock: Fuel Production for fuel, a forge for supplies. */
	public static String producerId(String commodityId) {
		return Commodities.FUEL.equals(commodityId) ? Industries.FUELPROD : Industries.HEAVYINDUSTRY;
	}

	/** The world's producer of the commodity, built or building; null without one. */
	public static Industry producerOn(MarketAPI market, String commodityId) {
		if (market == null) return null;
		return Commodities.FUEL.equals(commodityId) ? market.getIndustry(Industries.FUELPROD)
				: ThreatColonyManager.getForge(market);
	}

	/**
	 * Days a new producer of the commodity takes to stand: its vanilla build
	 * time (Fuel Production and Heavy Industry, 120 each), never under
	 * SHORT_DAYS. Every rule below is read over this span, and the trailing
	 * demand looks back over it (its e-folding time).
	 */
	public static float buildDays(String commodityId) {
		return Math.max(SHORT_DAYS, ThreatBuildCost.buildDays(producerId(commodityId)));
	}

	protected static float num(String key) {
		Object v = data().get(key);
		return v instanceof Float ? (Float) v : 0f;
	}

	/**
	 * Brings the commodity's trailing sums up to now: the demand and the days
	 * observed both decay by e^(-days / buildDays), and the days observed grow
	 * by the days gone, so their ratio is the flow's mean weighted toward the
	 * last build time.
	 * <p>
	 * The window opens full, at a build time of what the stock makes then (a
	 * new game: nothing, before the hive lands), so demand reads as the
	 * production until spending says otherwise - no producer built nor retired
	 * on no evidence. Opened empty (review 2026-10-01), the first days read as a
	 * month many times over: the lt save's 160k of founding fuel in its first
	 * month read 717k a month, and "runs dry" for three months while the stock
	 * rose. Opened at none, a save loaded with no history read every plant as
	 * surplus.
	 */
	protected static void ageDemand(String commodityId) {
		Map<String, Object> d = data();
		long now = Global.getSector().getClock().getTimestamp();
		if (!(d.get("demandSince_" + commodityId) instanceof Long)) {
			float tau = buildDays(commodityId);
			d.put("demandSince_" + commodityId, now);
			d.put("demandAt_" + commodityId, now);
			d.put("demand_" + commodityId, perMonth(commodityId) / 30f * tau);
			d.put("demandDays_" + commodityId, tau);
			return;
		}
		Object at = d.get("demandAt_" + commodityId);
		d.put("demandAt_" + commodityId, now);
		if (!(at instanceof Long)) return;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
		if (days <= 0f) return;
		float tau = buildDays(commodityId);
		float k = (float) Math.exp(-days / tau);
		d.put("demand_" + commodityId, num("demand_" + commodityId) * k);
		d.put("demandDays_" + commodityId, num("demandDays_" + commodityId) * k + tau * (1f - k));
	}

	/**
	 * Demand on the stock: what it paid (pay), or what it was asked for and
	 * could not pay (held, unmet). A refund (deposit) takes it back.
	 */
	public static void noteDemand(String commodityId, float amount) {
		if (amount == 0f) return;
		ageDemand(commodityId);
		data().put("demand_" + commodityId, Math.max(0f, num("demand_" + commodityId) + amount));
	}

	/** The trailing demand on the stock a month: spent, and held for lack of it, over the last buildDays. */
	public static float demandPerMonth(String commodityId) {
		float a = num("demand_" + commodityId), b = num("demandDays_" + commodityId);
		Object at = data().get("demandAt_" + commodityId);
		if (at instanceof Long) {
			float days = Global.getSector().getClock().getElapsedDaysSince((Long) at);
			if (days > 0f) {
				float tau = buildDays(commodityId);
				float k = (float) Math.exp(-days / tau);
				a *= k;
				b = b * k + tau * (1f - k);
			}
		}
		return b > 0f ? a / b * 30f : 0f;
	}

	/** Whether the stock's demand has been watched at least this many days. */
	public static boolean observed(String commodityId, float days) {
		Object since = data().get("demandSince_" + commodityId);
		if (!(since instanceof Long)) return false;
		return Global.getSector().getClock().getElapsedDaysSince((Long) since) >= days;
	}

	/** What the world puts in the stock of the commodity a month: its producer's output as perMonth counts it. */
	public static float outputOf(MarketAPI market, String commodityId) {
		CommodityOnMarketAPI com = market != null ? market.getCommodityData(commodityId) : null;
		if (com == null || com.getMaxSupply() <= 0) return 0f;
		return ThreatColonyUpkeep.reachesStock(market, commodityId, BaseIndustry.getSizeMult(com.getMaxSupply()))
				* com.getCommodity().getEconUnit() * ThreatIncConfig.hiveSurplusMult();
	}

	/** The most any one hive world puts in the stock a month. */
	public static float largestOutput(String commodityId) {
		float best = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) best = Math.max(best, outputOf(m, commodityId));
		return best;
	}

	/**
	 * What the producers under construction will put in the stock a month once
	 * they stand: size - 2 units each, vanilla's figure for both industries. The
	 * planner counts them, so it does not stack a second plant on one that has
	 * not had its build time to show.
	 */
	public static float comingPerMonth(String commodityId) {
		float unit = Global.getSettings().getCommoditySpec(commodityId).getEconUnit()
				* ThreatIncConfig.hiveSurplusMult();
		float sum = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			Industry p = producerOn(m, commodityId);
			if (p == null || !p.isBuilding() || p.isUpgrading()) continue;
			sum += ThreatColonyUpkeep.reachesStock(m, commodityId, Math.max(0, m.getSize() - 2)) * unit;
		}
		return sum;
	}

	/** Whether a stock-paying economy runs the rules below: passage paid and structures bought, the hive earning what it makes. */
	protected static boolean planned() {
		return enabled() && ThreatBuildCost.enabled();
	}

	/**
	 * Whether the hive runs dry of the commodity before a new producer could
	 * stand: its stock plus its production over buildDays - the producers being
	 * built counted - fall short of the trailing demand over the same days,
	 * stock < (demand - production) x months. Read once a month of demand has
	 * been watched. A trip held for want of a stock that next month's
	 * production refills is no shortage.
	 */
	public static boolean runsDry(String commodityId) {
		return runsDryWithout(commodityId, 0f);
	}

	/** runsDry with {@code lost} a month of production gone. */
	protected static boolean runsDryWithout(String commodityId, float lost) {
		if (!planned() || !observed(commodityId, SHORT_DAYS)) return false;
		float months = buildDays(commodityId) / 30f;
		float production = perMonth(commodityId) + comingPerMonth(commodityId) - lost;
		return stock(commodityId) < (demandPerMonth(commodityId) - production) * months;
	}

	/** What the planner answers with a producer: the hive runs dry of it, or (supplies) its colonies went unfed lately (noteShort). */
	public static boolean wanted(String commodityId) {
		if (Commodities.SUPPLIES.equals(commodityId) && shortOf(commodityId)) return true;
		if (!planned()) return shortOf(commodityId);
		return runsDry(commodityId);
	}

	/**
	 * Whether the stock is in surplus: production covers the trailing demand,
	 * and the stock covers it over buildDays. Never before buildDays of demand
	 * has been watched. The redundancy and bigger-copy steps add no producer of
	 * a stock in surplus, and only a stock in surplus gives one up
	 * (surplusProducer).
	 */
	public static boolean surplus(String commodityId) {
		if (!planned() || !observed(commodityId, buildDays(commodityId))) return false;
		float demand = demandPerMonth(commodityId);
		return perMonth(commodityId) >= demand && stock(commodityId) >= demand * buildDays(commodityId) / 30f;
	}

	/**
	 * Whether the world's producer can go: the stock is in surplus and the
	 * production less this producer's output still covers the trailing demand.
	 * Retiring it never opens a gap - the stock alone covers buildDays of
	 * demand, the time a replacement takes - and it is never wanted straight
	 * back: runsDry needs the stock under (demand - production) x months, and
	 * with the stock at demand x months and production at demand or more that
	 * takes a demand more than doubled, or a build time and more of a demand
	 * above what is left.
	 */
	public static boolean surplusProducer(MarketAPI market, String commodityId) {
		if (!surplus(commodityId)) return false;
		return perMonth(commodityId) - outputOf(market, commodityId) >= demandPerMonth(commodityId);
	}

	/**
	 * Whether a spare producer of the commodity is worth its slot: losing the
	 * hive's biggest would leave it running dry (runsDry without it). Spares used
	 * to go up until every hive system held one, whatever the stock - 26 fuel
	 * plants in the overnight tests. Always, outside a stock-paying economy (the old rule);
	 * never before buildDays of demand has been watched.
	 */
	public static boolean wantsSpare(String commodityId) {
		if (!planned()) return true;
		if (!observed(commodityId, buildDays(commodityId))) return false;
		return runsDryWithout(commodityId, largestOutput(commodityId));
	}

	/** Whether the planner may retire a surplus producer now: one a SHORT_DAYS, hive-wide (ThreatColonyManager.convertSurplus). */
	public static boolean mayConvert() {
		Object at = data().get("convertedAt");
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that the planner retired a producer (mayConvert). */
	public static void converted() {
		data().put("convertedAt", Global.getSector().getClock().getTimestamp());
	}

	/** Loads a Seeding Swarm with its founding: drawn from the stocks, carried in the fleet's memory. */
	public static void loadFounding(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet) {
		float[] cost = foundingCost();
		float supplies = Math.min(cost[0], stock(Commodities.SUPPLIES));
		float fuel = Math.min(cost[1], stock(Commodities.FUEL));
		pay(Commodities.SUPPLIES, supplies);
		pay(Commodities.FUEL, fuel);
		fleet.getMemoryWithoutUpdate().set(MEM_FOUND_SUPPLIES, supplies);
		fleet.getMemoryWithoutUpdate().set(MEM_FOUND_FUEL, fuel);
		fleet.getMemoryWithoutUpdate().set(MEM_FOUND_AT, Global.getSector().getClock().getTimestamp());
	}

	/**
	 * A wave's founding cargo: back in the stocks if it withdraws alive,
	 * spent if it founded its colony, lost with it if it was shot down.
	 * Returns {supplies, fuel} it carried.
	 */
	public static float[] unloadFounding(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet, boolean returned) {
		if (fleet == null) return new float[] { 0f, 0f };
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float[] carried = { mem.getFloat(MEM_FOUND_SUPPLIES), mem.getFloat(MEM_FOUND_FUEL) };
		// a wave loaded before the date was kept: taken back whole, as it was
		Object at = mem.get(MEM_FOUND_AT);
		float days = at instanceof Long ? Global.getSector().getClock().getElapsedDaysSince((Long) at) : 0f;
		mem.unset(MEM_FOUND_SUPPLIES);
		mem.unset(MEM_FOUND_FUEL);
		mem.unset(MEM_FOUND_AT);
		if (returned && fleet.isAlive()) {
			deposit(Commodities.SUPPLIES, carried[0], days);
			deposit(Commodities.FUEL, carried[1], days);
		}
		return carried;
	}

	/** What the last canPay or pay of each stock could not pay, for held() to book. Transient (forget). */
	protected static final Map<String, Float> UNMET = new HashMap<String, Float>();

	/** Send -> when its hold was last booked as demand (held): once a SHORT_DAYS a send. */
	public static final String KEY_HELD = "threatinc_hiveHeld";

	/** Fleet memory: a day of this fleet's bombardment the stock could not pay is booked (groundedOrdnance). */
	public static final String MEM_ORDNANCE_HELD = "$threatinc_ordnanceHeld";

	/** On load: no unmet bill of the session left behind is booked against the loaded one. */
	public static void forget() {
		UNMET.clear();
	}

	/**
	 * Notes a send the stock could not pay, for the month's census line, and
	 * books its unmet bill as demand on the stock (noteDemand): the fuel the
	 * last canPay or pay that failed asked for, and for a Seeding Swarm the
	 * supplies of its founding too (canFound), for a strike the supplies
	 * shortfall. A send held poll after poll is
	 * booked once a SHORT_DAYS, as the trip it would have flown that month.
	 * Before 2026-10-01 every hold noted a month's fuel shortage, which the
	 * planner answered with a plant whatever the stock and production.
	 */
	public static void held(String what) {
		add(HELD, 1f);
		boolean wave = what.startsWith("a Seeding Swarm");
		Float fuel = UNMET.remove(Commodities.FUEL);
		Float supplies = UNMET.remove(Commodities.SUPPLIES);
		if (bookHold(what)) {
			if (fuel != null) noteDemand(Commodities.FUEL, fuel);
			if (wave && supplies != null) noteDemand(Commodities.SUPPLIES, supplies);
			// a strike books the shortfall alone, as heldShort books its fuel
			// (2026-10-06, hw36a: its supplies were never booked, so the planner
			// never saw the strike the garrison's upkeep starved)
			if (!wave && supplies != null && supplies > stock(Commodities.SUPPLIES)) {
				noteDemand(Commodities.SUPPLIES, supplies - stock(Commodities.SUPPLIES));
			}
		}
		// structures free or passage off: the old rule, a hold is a month's shortage
		if (!planned()) {
			if (fuel != null) noteShort(Commodities.FUEL);
			if (wave && supplies != null) noteShort(Commodities.SUPPLIES);
		}
		ThreatIncConfig.logQuiet("threatfuel_" + what, "Hive stock: " + what + " held, "
				+ (int) stock() + " fuel and " + (int) stock(Commodities.SUPPLIES) + " supplies in stock");
	}

	/**
	 * A send held for a fuel bill the stock falls short of, booking the
	 * shortfall alone (IncursionManager.pickStrikeTarget's waiting muster):
	 * a whole muster's passage grows with the garrison, and booking all of it
	 * each SHORT_DAYS made the planner build plants without end - run hw4d,
	 * fuel made 63k a month against 31k spent and 271k in stock at month 60,
	 * strikes still waiting. The shortfall shrinks as the stock fills.
	 */
	public static void heldShort(String what, float bill) {
		float shortfall = bill - stock();
		if (shortfall <= 0f) return;
		UNMET.put(Commodities.FUEL, shortfall);
		held(what);
	}

	/**
	 * A build the planner needs and the supplies stock cannot pay - a chain
	 * link's first copy, a shortage's answer (ThreatColonyManager.tryBuildLink):
	 * its price is demand on the stock, once a SHORT_DAYS while it waits. An
	 * optional build that waits books nothing. {@code what} is the industry:
	 * one need books once, however many worlds ask for it (keyed by world, 15
	 * forge-less colonies each booked the same forge, review 2026-10-01).
	 */
	public static void heldBuild(String what, float supplies) {
		if (supplies > 0f && bookHold("build " + what)) noteDemand(Commodities.SUPPLIES, supplies);
	}

	/** Whether the send's hold is booked now: not booked in the last SHORT_DAYS. */
	protected static boolean bookHold(String what) {
		Map<String, Long> booked = ThreatIncData.map(KEY_HELD);
		Long at = booked.get(what);
		if (at != null) {
			float days = Global.getSector().getClock().getElapsedDaysSince(at);
			if (days >= 0f && days < SHORT_DAYS) return false;
		}
		booked.put(what, Global.getSector().getClock().getTimestamp());
		return true;
	}

	/**
	 * A bill the stock could not pay as it fell due - the swarm's bombardment
	 * ordnance (ThreatGroundFronts.payOrdnance, ThreatStrikeFGI.saturationPass):
	 * booked as demand at once, a flow and not a send to repeat.
	 */
	public static void unmet(float fuel, String what) {
		unmet(Commodities.FUEL, fuel, what);
	}

	/** unmet, of either stock. */
	public static void unmet(String commodityId, float amount, String what) {
		if (amount <= 0f) return;
		noteDemand(commodityId, amount);
		ThreatIncConfig.logQuiet("threatfuel_unmet_" + what, "Hive stock: " + what + " short of " + (int) amount
				+ " " + commodityId + ", " + (int) stock(commodityId) + " in stock");
	}

	/**
	 * A swarm fleet that would bombard with the fuel stock empty stands down
	 * (ThreatGroundFronts.ordnanceAvailable, orbitDoneFor): its day of ordnance
	 * is demand the stock did not meet, booked once a day a fleet. A stock
	 * that pays part of a day books its shortfall where it is paid
	 * (payOrdnance), so only an empty one books here.
	 */
	public static void groundedOrdnance(CampaignFleetAPI fleet, float perDay) {
		if (fleet == null || perDay <= 0f || stock() >= 1f) return;
		if (fleet.getMemoryWithoutUpdate().getBoolean(MEM_ORDNANCE_HELD)) return;
		fleet.getMemoryWithoutUpdate().set(MEM_ORDNANCE_HELD, true, 1f);
		unmet(Commodities.FUEL, perDay, "bombardment over " + (fleet.getContainingLocation() != null
				? fleet.getContainingLocation().getName() : "nowhere"));
	}

	protected static void add(String key, float v) {
		Object o = data().get(key);
		data().put(key, (o instanceof Float ? (Float) o : 0f) + v);
	}

	/** The census's stock clause, and the month's tallies reset. */
	public static String monthSummary() {
		if (!enabled()) return "";
		Object held = data().get(HELD);
		StringBuilder s = new StringBuilder();
		for (String c : STOCKED) {
			Object spent = data().get(spentKey(c));
			s.append("; ").append(c).append(" ").append((int) stock(c)).append(" (+").append((int) perMonth(c))
					.append("/mo, spent ").append((int) (spent instanceof Float ? (Float) spent : 0f)).append(")");
			data().put(spentKey(c), 0f);
		}
		s.append(", sends held ").append((int) (held instanceof Float ? (Float) held : 0f));
		data().put(HELD, 0f);
		for (String c : STOCKED) ThreatIncConfig.log(sourcesLine(c));
		for (String c : STOCKED) ThreatIncConfig.log(planLine(c));
		// holds booked longer ago than a SHORT_DAYS are forgotten (bookHold)
		Map<String, Long> booked = ThreatIncData.map(KEY_HELD);
		for (String what : new java.util.ArrayList<String>(booked.keySet())) {
			float days = Global.getSector().getClock().getElapsedDaysSince(booked.get(what));
			if (days < 0f || days >= SHORT_DAYS) booked.remove(what);
		}
		return s.toString();
	}

	/**
	 * The planner's reading of the stock, for the log: the trailing demand
	 * against production and the producers building, the months the stock
	 * covers, and the verdict (runsDry, surplus, wantsSpare).
	 */
	protected static String planLine(String commodityId) {
		float demand = demandPerMonth(commodityId);
		float made = perMonth(commodityId);
		float coming = comingPerMonth(commodityId);
		float stock = stock(commodityId);
		return "Hive stock plan: " + commodityId + " " + (int) stock + " in stock, demand " + (int) demand
				+ "/mo trailing over " + (int) buildDays(commodityId) + " d, made " + (int) made + "/mo (+"
				+ (int) coming + " building), covers " + (demand > 0f ? String.format("%.1f", stock / demand) : "-")
				+ " months; " + (runsDry(commodityId) ? "runs dry" : surplus(commodityId) ? "surplus" : "holds")
				+ (wantsSpare(commodityId) ? ", wants a spare" : "")
				+ (observed(commodityId, buildDays(commodityId)) ? "" : ", still watching");
	}

	/** Where the month's stock of the commodity comes from, in units, for the log. */
	protected static String sourcesLine(String commodityId) {
		int makers = 0;
		float output = 0f, avail = 0f, demand = 0f, own = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			CommodityOnMarketAPI com = m.getCommodityData(commodityId);
			if (com == null) continue;
			if (com.getMaxSupply() > 0) makers++;
			output += com.getMaxSupply();
			avail += ThreatReserves.structuralAvailable(com);
			float d = WarFootingDemand.peacetimeDemand(m, com);
			demand += d;
			own += Math.max(0f, Math.min(com.getMaxSupply(), ThreatReserves.structuralAvailable(com)) - d);
		}
		if (ThreatBuildCost.enabled()) own = output;
		return "Hive stock sources: " + commodityId + " makers " + makers + ", output " + (int) output
				+ ", available " + (int) avail + ", port demand " + (int) demand + ", banked from " + (int) own;
	}
}
