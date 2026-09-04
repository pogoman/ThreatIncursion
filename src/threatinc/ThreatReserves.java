package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Per-colony faction RESERVES (docs/strategy-layer.md): the stock of marines,
 * heavy armaments, fuel and supplies a mobilised faction's colony holds for
 * the war. Everything the faction commits - expedition landing forces, task
 * force provisioning, convoy cargo - is drawn from here, and every unit here
 * was made by the colony's own industries: accrual reads vanilla's production
 * figure for the matching commodity (marines from military structures, hand
 * weapons and supplies from heavy industry, fuel from fuel production), so a
 * colony that makes nothing of something holds none of it. That is what
 * staging and convoys ({@link ThreatConvoys}) are for.
 *
 * <p>Only colonies of factions in war mode ({@link ThreatWarState}) accrue.
 * Stock is kept, frozen, when a faction stands down.
 */
public class ThreatReserves {

	public static final String KEY_RESERVES = "threatinc_colonyReserves";

	/** The four reserve commodities, in display order. */
	public static final String[] COMMODITIES = {Commodities.MARINES, Commodities.HAND_WEAPONS,
			Commodities.FUEL, Commodities.SUPPLIES};

	/** One colony's stock. Serialized into the save via persistent data. */
	public static class ColonyReserve {
		public String marketId;
		public float marines;
		public float armaments;
		public float fuel;
		public float supplies;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, ColonyReserve> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_RESERVES);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, ColonyReserve>();
			Global.getSector().getPersistentData().put(KEY_RESERVES, val);
		}
		return (Map<String, ColonyReserve>) val;
	}

	public static ColonyReserve get(String marketId) {
		if (marketId == null) return null;
		return all().get(marketId);
	}

	protected static ColonyReserve getOrCreate(String marketId) {
		ColonyReserve r = all().get(marketId);
		if (r == null) {
			r = new ColonyReserve();
			r.marketId = marketId;
			all().put(marketId, r);
		}
		return r;
	}

	public static void clear(String marketId) {
		all().remove(marketId);
	}

	// ------------------------------------------------------------------
	// stock access
	// ------------------------------------------------------------------

	public static float stock(String marketId, String commodityId) {
		ColonyReserve r = get(marketId);
		if (r == null) return 0f;
		return read(r, commodityId);
	}

	protected static float read(ColonyReserve r, String c) {
		if (Commodities.MARINES.equals(c)) return r.marines;
		if (Commodities.HAND_WEAPONS.equals(c)) return r.armaments;
		if (Commodities.FUEL.equals(c)) return r.fuel;
		if (Commodities.SUPPLIES.equals(c)) return r.supplies;
		return 0f;
	}

	protected static void write(ColonyReserve r, String c, float value) {
		value = Math.max(0f, value);
		if (Commodities.MARINES.equals(c)) r.marines = value;
		else if (Commodities.HAND_WEAPONS.equals(c)) r.armaments = value;
		else if (Commodities.FUEL.equals(c)) r.fuel = value;
		else if (Commodities.SUPPLIES.equals(c)) r.supplies = value;
	}

	/** Takes up to {@code amount}; returns what was actually taken. */
	public static float draw(String marketId, String commodityId, float amount) {
		if (amount <= 0f) return 0f;
		ColonyReserve r = get(marketId);
		if (r == null) return 0f;
		float have = read(r, commodityId);
		float taken = Math.min(have, amount);
		write(r, commodityId, have - taken);
		return taken;
	}

	/**
	 * Stock above the colony's floor - what a sortie may actually take. The
	 * floor (reserveFloorFraction of the cap) is the home garrison's stock;
	 * expeditions and orders never draw below it, so a small faction cannot
	 * empty its depots on one sortie and go quiet for the rest of the war
	 * (docs/design-theory.md 8.6). Convoys use the donor keep fraction instead.
	 */
	public static float available(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		float floor = cap(market, commodityId) * ThreatIncConfig.reserveFloorFraction();
		return Math.max(0f, stock(market.getId(), commodityId) - floor);
	}

	/** Takes up to {@code amount}, never below the floor; returns what was taken. */
	public static float drawAbove(MarketAPI market, String commodityId, float amount) {
		if (market == null || amount <= 0f) return 0f;
		return draw(market.getId(), commodityId, Math.min(amount, available(market, commodityId)));
	}

	/** Adds stock (a convoy arriving, a withdrawn front returning). No cap - what was made is kept. */
	public static void deposit(String marketId, String commodityId, float amount) {
		if (amount <= 0f || marketId == null) return;
		ColonyReserve r = getOrCreate(marketId);
		write(r, commodityId, read(r, commodityId) + amount);
	}

	// ------------------------------------------------------------------
	// accrual
	// ------------------------------------------------------------------

	/** Reserve units accrued per supply unit per 30 days, per commodity. */
	public static float perUnit(String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return ThreatIncConfig.reserveMarinesPerUnit();
		if (Commodities.HAND_WEAPONS.equals(commodityId)) return ThreatIncConfig.reserveArmamentsPerUnit();
		if (Commodities.FUEL.equals(commodityId)) return ThreatIncConfig.reserveFuelPerUnit();
		if (Commodities.SUPPLIES.equals(commodityId)) return ThreatIncConfig.reserveSuppliesPerUnit();
		return 0f;
	}

	/**
	 * What this colony adds to a reserve per 30 days: vanilla's production
	 * figure for the commodity times the per-unit knob, scaled down by any
	 * shortage of it on the colony (a blockaded forge world stops arming).
	 */
	public static float accrualPer30(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		// militia: every colony raises some marines on its own, so a farming
		// world is never permanently at zero
		float baseline = Commodities.MARINES.equals(commodityId)
				? ThreatIncConfig.reserveBaselinePerSize() * market.getSize() : 0f;
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return baseline;
		float supply = com.getMaxSupply();
		if (supply <= 0f) return baseline;
		float factor = 1f;
		float demand = com.getMaxDemand();
		if (demand > 0f) {
			float available = com.getAvailable();
			factor = Math.max(0f, Math.min(1f, available / demand));
		}
		return baseline + supply * perUnit(commodityId) * factor;
	}

	/** The most of a commodity the colony keeps: months of its own production. */
	public static float cap(MarketAPI market, String commodityId) {
		return accrualPer30(market, commodityId) * ThreatIncConfig.reserveCapMonths();
	}

	/** Every market currently flying this faction's flag (hidden markets excluded). */
	public static List<MarketAPI> marketsOf(String factionId) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (factionId == null) return result;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.getPrimaryEntity() == null) continue;
			if (!factionId.equals(market.getFactionId())) continue;
			result.add(market);
		}
		return result;
	}

	/**
	 * Accrual, on the fast poll, pro-rated per 30 days, for every colony of
	 * every mobilised faction. Also prunes entries whose market is gone or has
	 * changed hands to a faction that is not at war (a captured depot is lost
	 * to its new owner unless they are mobilised too).
	 */
	public static void poll(float elapsedDays) {
		if (!ThreatWarState.enabled() || elapsedDays <= 0f) return;
		List<String> warring = ThreatWarState.warFactionIds();
		if (warring.isEmpty() && all().isEmpty()) return;

		for (String factionId : warring) {
			for (MarketAPI market : marketsOf(factionId)) {
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				ColonyReserve r = null;
				for (String c : COMMODITIES) {
					float per30 = accrualPer30(market, c);
					if (per30 <= 0f) continue;
					if (r == null) r = getOrCreate(market.getId());
					float capValue = per30 * ThreatIncConfig.reserveCapMonths();
					float have = read(r, c);
					if (have >= capValue) continue;
					float add = per30 * elapsedDays / 30f;
					write(r, c, Math.min(capValue, have + add));
				}
			}
		}

		// prune: markets gone, or taken by the swarm. A faction that stood
		// down keeps its stock, frozen; a colony that changed hands keeps it
		// for the new owner (a captured depot is a captured depot)
		for (String marketId : new ArrayList<String>(all().keySet())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
			if (market == null || !market.isInEconomy()
					|| Factions.THREAT.equals(market.getFactionId())) {
				all().remove(marketId);
			}
		}
	}

	/**
	 * Mobilisation stock: the moment a faction enters war mode each of its
	 * colonies starts with reserveInitialMonths of its own production (never
	 * above the cap, never below what it already holds) - the peacetime
	 * depots a navy draws its first sortie from. Without this the first
	 * expedition would wait months for accrual alone.
	 */
	public static void seed(String factionId) {
		float months = ThreatIncConfig.reserveInitialMonths();
		if (months <= 0f) return;
		for (MarketAPI market : marketsOf(factionId)) {
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			ColonyReserve r = null;
			for (String c : COMMODITIES) {
				float per30 = accrualPer30(market, c);
				if (per30 <= 0f) continue;
				if (r == null) r = getOrCreate(market.getId());
				float start = Math.min(per30 * months, per30 * ThreatIncConfig.reserveCapMonths());
				if (read(r, c) < start) write(r, c, start);
			}
		}
		ThreatIncConfig.log("Reserve seed: " + factionId + " mobilised with " + (int) months
				+ " months of production");
	}

	/** Total stock of a commodity across a faction's colonies (for the board). */
	public static float factionStock(String factionId, String commodityId) {
		float total = 0f;
		for (MarketAPI market : marketsOf(factionId)) {
			total += stock(market.getId(), commodityId);
		}
		return total;
	}

	public static String label(String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return "marines";
		if (Commodities.HAND_WEAPONS.equals(commodityId)) return "heavy armaments";
		if (Commodities.FUEL.equals(commodityId)) return "fuel";
		if (Commodities.SUPPLIES.equals(commodityId)) return "supplies";
		return commodityId;
	}
}
