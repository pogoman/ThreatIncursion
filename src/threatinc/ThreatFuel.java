package threatinc;

import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
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
 * x the commodity's econ unit x reserveSurplusMult, a month - summed over the
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

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	public static boolean enabled() {
		return ThreatIncConfig.threatPaysPassage();
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

	/** Fuel the hive banks a month. */
	public static float perMonth() {
		return perMonth(Commodities.FUEL);
	}

	/**
	 * What the hive banks of the commodity a month. While structures cost
	 * supplies (ThreatBuildCost) the hive earns what it makes: every plant's
	 * and forge's whole output, getSizeMult of it x the econ unit x
	 * reserveSurplusMult, summed. No Spaceport demand is taken off - vanilla's
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
		if (own) return made * unit * ThreatIncConfig.reserveSurplusMult();
		float budget = Math.max(made, BaseIndustry.getSizeMult(foreign) * ThreatIncConfig.reserveBankImportsMult());
		return Math.min(banked, budget) * unit * ThreatIncConfig.reserveSurplusMult();
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
		}
	}

	/** Light-years between two star systems (0 for the same one or a null). */
	public static float ly(StarSystemAPI from, StarSystemAPI to) {
		if (from == null || to == null || from == to) return 0f;
		return Misc.getDistanceLY(from.getLocation(), to.getLocation());
	}

	/** Fuel a fleet of {@code fp} draws over {@code ly}: both ways, or the way out only. */
	public static float passage(float fp, float ly, boolean roundTrip) {
		if (!enabled() || fp <= 0f || ly <= 0f) return 0f;
		float fuel = fp / IncursionManager.FP_PER_RESPONSE_DIFFICULTY * ly
				* ThreatIncConfig.expeditionFuelPerPointLY();
		return roundTrip ? fuel : fuel * (1f - ThreatReturns.RETURN_LEG_SHARE);
	}

	public static boolean canPay(float fuel) {
		return canPay(Commodities.FUEL, fuel);
	}

	public static boolean canPay(String commodityId, float amount) {
		return amount <= 0f || stock(commodityId) >= amount;
	}

	/** Draws {@code fuel} from the stock; false (nothing drawn) if the stock is short. */
	public static boolean pay(float fuel) {
		return pay(Commodities.FUEL, fuel);
	}

	public static boolean pay(String commodityId, float amount) {
		if (amount <= 0f) return true;
		float have = stock(commodityId);
		if (have < amount) return false;
		setStock(commodityId, have - amount);
		add(spentKey(commodityId), amount);
		return true;
	}

	/** Puts {@code amount} back in the stock (a withdrawing wave's cargo). */
	public static void deposit(String commodityId, float amount) {
		if (amount <= 0f) return;
		setStock(commodityId, stock(commodityId) + amount);
		add(spentKey(commodityId), -amount);
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

	/** Whether both stocks hold a founding and the wave's fuel on top of it; notes the one short (noteShort). */
	public static boolean canFound(float passageFuel) {
		float[] cost = foundingCost();
		boolean supplies = canPay(Commodities.SUPPLIES, cost[0]);
		boolean fuel = canPay(Commodities.FUEL, cost[1] + passageFuel);
		if (!supplies) noteShort(Commodities.SUPPLIES);
		if (!fuel) noteShort(Commodities.FUEL);
		return supplies && fuel;
	}

	/** Days a shortage stays noted: what the hive planner answers (shortOf). */
	protected static final float SHORT_DAYS = 30f;

	/**
	 * Notes that the stock of the commodity fell short (2026-09-30): a send it
	 * could not fuel, upkeep it could not pay. The hive planner builds another
	 * plant for it while it is noted (ThreatColonyManager.planHiveEconomy).
	 */
	public static void noteShort(String commodityId) {
		data().put("shortAt_" + commodityId, Global.getSector().getClock().getTimestamp());
	}

	/** Whether the stock of the commodity fell short within SHORT_DAYS. */
	public static boolean shortOf(String commodityId) {
		Object v = data().get("shortAt_" + commodityId);
		return v instanceof Long && Global.getSector().getClock().getElapsedDaysSince((Long) v) < SHORT_DAYS;
	}

	/**
	 * Whether the hive planner may answer the shortage with another plant: it
	 * is short, and the last plant built for it has had SHORT_DAYS to show in
	 * the month's take. h29a built 20 fuel plants in one tick for one noted
	 * shortage.
	 */
	public static boolean mayAnswer(String commodityId) {
		if (!shortOf(commodityId)) return false;
		Object at = data().get("answeredAt_" + commodityId);
		return !(at instanceof Long) || Global.getSector().getClock().getElapsedDaysSince((Long) at) >= SHORT_DAYS;
	}

	/** Notes that the planner built a plant for the shortage (mayAnswer). */
	public static void answered(String commodityId) {
		data().put("answeredAt_" + commodityId, Global.getSector().getClock().getTimestamp());
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
		mem.unset(MEM_FOUND_SUPPLIES);
		mem.unset(MEM_FOUND_FUEL);
		if (returned && fleet.isAlive()) {
			deposit(Commodities.SUPPLIES, carried[0]);
			deposit(Commodities.FUEL, carried[1]);
		}
		return carried;
	}

	/** Notes a send the stock could not fuel, for the month's census line. */
	public static void held(String what) {
		add(HELD, 1f);
		// every caller holds a send its fuel could not pay (a wave's founding
		// notes its own shortfall, canFound)
		if (!what.startsWith("a Seeding Swarm")) noteShort(Commodities.FUEL);
		ThreatIncConfig.logQuiet("threatfuel_" + what, "Hive stock: " + what + " held, "
				+ (int) stock() + " fuel and " + (int) stock(Commodities.SUPPLIES) + " supplies in stock");
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
		return s.toString();
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
