package threatinc;

import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * The hive's fuel (2026-09-30): the Threat pays passage the way the factions
 * do. A fleet sent across hyperspace draws fleet points / 25 x light-years x
 * expeditionFuelPerPointLY - the factions' own rate - from one stock the whole
 * hive shares, as vanilla's broadcast availability shares one fuel plant's
 * output with every hive world. The factions' rate is the round trip; a fleet
 * that stays where it is sent (a Seeding Swarm, a reinforcement) pays the way
 * out only (ThreatReturns.RETURN_LEG_SHARE).
 *
 * <p>The stock fills as a faction's reserve does (ThreatReserves.accrualPer30,
 * productionShare): each world's fuel, BaseIndustry.getSizeMult of it, x the
 * fuel econ unit x reserveSurplusMult, a month - summed over the hive, and no
 * more than it makes above its own demand or, if larger, the sector's best
 * exporter it imports from times reserveBankImportsMult. A faction world
 * banks only what is left over its peacetime demand, which stands for its
 * trade and civilian traffic; a hive world runs no trade fleets
 * (maintainGarrisons suppresses them), so its fuel is all its fleets'. Read
 * as surplus, the imports that meet a Megaport's demand banked 0 and grounded
 * every fleet (first test, 2026-09-30). Before this the swarm moved for free inside its
 * fuel range while the factions paid for every light-year, and in the
 * 75-month test it shipped 1,015 swarms between systems and grew to ~100k FP
 * while no faction could fuel a hunt against it.
 */
public class ThreatFuel {

	public static final String KEY = "threatinc_hiveFuel";
	private static final String STOCK = "stock";
	private static final String AT = "at";
	private static final String SPENT = "spentMonth";
	private static final String HELD = "heldMonth";

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	public static boolean enabled() {
		return ThreatIncConfig.threatPaysPassage();
	}

	/** Fuel in the hive's stock. */
	public static float stock() {
		Object v = data().get(STOCK);
		return v instanceof Float ? (Float) v : 0f;
	}

	protected static void setStock(float fuel) {
		data().put(STOCK, Math.max(0f, fuel));
	}

	/**
	 * Fuel the hive banks a month, by a faction reserve's rule
	 * (ThreatReserves.accrualPer30 x productionShare): every world's fuel (a hive
	 * runs no trade, so none of it is spoken for), held to the better of what the
	 * hive makes and what it can import.
	 */
	public static float perMonth() {
		float banked = 0f, made = 0f, foreign = 0f, unit = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			CommodityOnMarketAPI com = m.getCommodityData(Commodities.FUEL);
			if (com == null) continue;
			unit = com.getCommodity().getEconUnit();
			float have = ThreatReserves.structuralAvailable(com);
			if (have > 0f) banked += BaseIndustry.getSizeMult(have);
			float own = Math.min(com.getMaxSupply(), ThreatReserves.structuralAvailable(com))
					- WarFootingDemand.peacetimeDemand(m, com);
			if (own > 0f) made += BaseIndustry.getSizeMult(own);
			if (com.getCommodityMarketData() != null) {
				foreign = Math.max(foreign, com.getCommodityMarketData().getMaxExportGlobal());
			}
		}
		float budget = Math.max(made, BaseIndustry.getSizeMult(foreign) * ThreatIncConfig.reserveBankImportsMult());
		return Math.min(banked, budget) * unit * ThreatIncConfig.reserveSurplusMult();
	}

	/**
	 * Banks the fuel made since the last call; once a poll. The first sight of
	 * the stock (a new game, or a save from before it) starts it at
	 * FAB_ENDOWMENT_DAYS of production, as the FP banks were endowed.
	 */
	public static void accrue() {
		long now = Global.getSector().getClock().getTimestamp();
		Object last = data().get(AT);
		data().put(AT, now);
		if (!(last instanceof Long)) {
			setStock(perMonth() * ThreatColonyManager.FAB_ENDOWMENT_DAYS / 30f);
			return;
		}
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) last);
		if (days <= 0f) return;
		setStock(stock() + perMonth() * days / 30f);
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
		return fuel <= 0f || stock() >= fuel;
	}

	/** Draws {@code fuel} from the stock; false (nothing drawn) if the stock is short. */
	public static boolean pay(float fuel) {
		if (fuel <= 0f) return true;
		float have = stock();
		if (have < fuel) return false;
		setStock(have - fuel);
		add(SPENT, fuel);
		return true;
	}

	/** Notes a send the stock could not fuel, for the month's census line. */
	public static void held(String what) {
		add(HELD, 1f);
		ThreatIncConfig.logQuiet("threatfuel_" + what, "Hive fuel: " + what + " held, "
				+ (int) stock() + " fuel in stock");
	}

	protected static void add(String key, float v) {
		Object o = data().get(key);
		data().put(key, (o instanceof Float ? (Float) o : 0f) + v);
	}

	/** The census's fuel clause, and the month's tallies reset. */
	public static String monthSummary() {
		if (!enabled()) return "";
		Object spent = data().get(SPENT), held = data().get(HELD);
		String s = "; fuel " + (int) stock() + " (+" + (int) perMonth() + "/mo, spent "
				+ (int) (spent instanceof Float ? (Float) spent : 0f) + ", sends held "
				+ (int) (held instanceof Float ? (Float) held : 0f) + ")";
		data().put(SPENT, 0f);
		data().put(HELD, 0f);
		return s;
	}
}
