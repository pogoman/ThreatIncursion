package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Per-colony faction RESERVES (docs/strategy-layer.md): the stock of marines,
 * heavy armaments, fuel and supplies a mobilised faction's colony holds for
 * the war. Everything the faction commits - expedition landing forces, task
 * force provisioning, convoy cargo - is drawn from here.
 *
 * <p>The reserve is ONE truth with vanilla's colony screen
 * (docs/economy-coherence.md, the seven rules). It is banked only from the
 * colony's vanilla SURPLUS - availability above demand - at vanilla's own
 * stockpiling scale (rule 1); mobilisation is vanilla demand, the War footing
 * condition (rule 2); the depot is spent on the colony's own vanilla shortage
 * before anything sails (rule 3); a player's sale does what a sale always
 * does, ending the shortage and feeding the banking (rule 4); convoys land as
 * trade modifiers the colony screen can see (rule 5); the board shows
 * vanilla's units beside the item counts (rule 7). A colony that has no
 * surplus of something banks none of it - which is what staging and convoys
 * ({@link ThreatConvoys}) are for.
 *
 * <p>Only colonies of factions in war mode ({@link ThreatWarState}) accrue.
 * Stock is kept, frozen, when a faction stands down.
 */
public class ThreatReserves {

	public static final String KEY_RESERVES = "threatinc_colonyReserves";

	/** The four reserve commodities, in display order. */
	public static final String[] COMMODITIES = {Commodities.MARINES, Commodities.HAND_WEAPONS,
			Commodities.FUEL, Commodities.SUPPLIES};

	/** The War footing market condition (rule 2) - the colony screen's face of the war. */
	public static final String WAR_FOOTING_CONDITION = "threatinc_war_footing";
	/** The hidden structure that carries the War footing's vanilla demand ({@link WarFootingDemand}). */
	public static final String WAR_FOOTING_INDUSTRY = "threatinc_war_footing_demand";
	/** Prefix of every trade-modifier source this mod applies; accrual never counts those as surplus. */
	public static final String MOD_SOURCE_PREFIX = "threatinc_";
	/** Trade-modifier source of the depot's shortage cover (rule 3): one per commodity per market. */
	public static final String COVER_SOURCE = MOD_SOURCE_PREFIX + "cover";
	/** Trade-modifier source prefix of a convoy landing (rule 5); each landing gets its own. */
	public static final String CONVOY_SOURCE_PREFIX = MOD_SOURCE_PREFIX + "convoy_";

	/** One colony's stock. Serialized into the save via persistent data. */
	public static class ColonyReserve {
		public String marketId;
		public float marines;
		public float armaments;
		public float fuel;
		public float supplies;
		/** Rule 3: when the depot last issued a cover, per commodity (clock timestamp). Null on older saves. */
		public Map<String, Long> coverIssued;
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
	// accrual - rule 1: only surplus banks
	// ------------------------------------------------------------------

	/**
	 * What this colony adds to a reserve per 30 days: its vanilla SURPLUS of
	 * the commodity - availability above demand, in econ units - times the
	 * commodity's econ unit (the item count behind one unit: 1,500 fuel, 750
	 * supplies, 200 heavy armaments, 100 marines) times reserveSurplusMult.
	 * BaseIndustry.getSizeMult is vanilla's own tier-to-multiplier curve, the
	 * one its local-resources submarket stockpiles excess by (at 0.5). A
	 * colony in deficit banks nothing. Availability is vanilla's broadcast
	 * figure, so a colony banks surplus it imports as well as surplus it
	 * makes, and a player's sale, which raises availability, banks for its
	 * duration (rule 4). The mod's OWN trade modifiers - shortage covers and
	 * convoy landings - are not surplus and are left out, or the depot would
	 * bank what it just issued. Plus the militia trickle for marines.
	 */
	public static float accrualPer30(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		// militia: every colony raises some marines on its own, so a farming
		// world is never permanently at zero
		float baseline = Commodities.MARINES.equals(commodityId)
				? ThreatIncConfig.reserveBaselinePerSize() * market.getSize() : 0f;
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return baseline;
		float surplus = surplusUnits(com);
		if (surplus <= 0f) return baseline;
		return baseline + BaseIndustry.getSizeMult(surplus) * com.getCommodity().getEconUnit()
				* ThreatIncConfig.reserveSurplusMult();
	}

	/** Units of availability above demand, this mod's own trade modifiers excluded; never negative. */
	public static float surplusUnits(CommodityOnMarketAPI com) {
		if (com == null) return 0f;
		return Math.max(0f, structuralAvailable(com) - com.getMaxDemand());
	}

	/** Vanilla's availability (units) less what this mod's own trade modifiers contribute to it. */
	public static float structuralAvailable(CommodityOnMarketAPI com) {
		return Math.max(0f, com.getAvailable() - ownModUnits(com, null));
	}

	/**
	 * Whole econ units this mod's trade modifiers contribute to availability -
	 * every threatinc_ source, or one named source. Vanilla nets every trade
	 * modifier on the market into one quantity (a player's purchases here
	 * count against our issues) and credits whole units of the commodity's
	 * econ unit (CommodityOnMarket.getModValueForQuantity), so ours is the
	 * difference the net makes with and without our quantity - seen in-game
	 * 2026-09-05: 3,000 fuel issued at Chicomoztoc moved availability by one
	 * unit, not two, against the player's recent fuel buying there.
	 */
	public static int ownModUnits(CommodityOnMarketAPI com, String onlySource) {
		float qty = 0f;
		for (StatMod mod : com.getTradeModPlus().getFlatMods().values()) {
			if (mod.source == null || !mod.source.startsWith(MOD_SOURCE_PREFIX)) continue;
			if (onlySource != null && !onlySource.equals(mod.source)) continue;
			qty += mod.value;
		}
		if (qty <= 0f) return 0;
		float combined = com.getCombinedTradeModQuantity();
		float with = com.getModValueForQuantity(combined);
		float without = com.getModValueForQuantity(combined - qty);
		return Math.max(0, Math.round(with - without));
	}

	/** Quantity of the depot's cover in force for this commodity, 0 if none. */
	public static float coverQuantity(CommodityOnMarketAPI com) {
		StatMod mod = com.getTradeModPlus().getFlatStatMod(COVER_SOURCE);
		return mod == null ? 0f : mod.value;
	}

	/** The most of a commodity the colony keeps: months of its own banking. */
	public static float cap(MarketAPI market, String commodityId) {
		return accrualPer30(market, commodityId) * ThreatIncConfig.reserveCapMonths();
	}

	// ------------------------------------------------------------------
	// rule 3: the depot counters the colony's own shortage first
	// ------------------------------------------------------------------

	/** Units the colony is short of the commodity - demand above availability, the depot's own cover ignored. */
	public static int deficitUnits(CommodityOnMarketAPI com) {
		if (com == null) return 0;
		int available = com.getAvailable() - ownModUnits(com, COVER_SOURCE);
		return Math.max(0, com.getMaxDemand() - Math.max(0, available));
	}

	/**
	 * While a mobilised colony is short of a reserve commodity and no cover is
	 * in force, the governor issues one: the quantity that lifts vanilla's
	 * availability by the deficit's whole units (what a player would have to
	 * sell here to end the shortage) leaves the reserve and is applied as a
	 * trade modifier for reserveShortageCoverDays, exactly as a sale is. One
	 * issue per period, never more than reserveShortageCoverFraction of the
	 * stock at hand, and only whole units - a fraction of a unit changes
	 * nothing on vanilla's screen. When the stock cannot buy one unit the
	 * shortage stands, and {@link #status} reports the depot exhausted.
	 */
	protected static void coverShortage(MarketAPI market, ColonyReserve r, String c) {
		float days = ThreatIncConfig.reserveShortageCoverDays();
		float fraction = ThreatIncConfig.reserveShortageCoverFraction();
		if (days <= 0f || fraction <= 0f) return;
		CommodityOnMarketAPI com = market.getCommodityData(c);
		if (com == null || coverQuantity(com) > 0f) return;
		int deficit = deficitUnits(com);
		if (deficit <= 0) return;
		float unit = com.getCommodity().getEconUnit();
		if (unit <= 0f) return;
		float stock = read(r, c);
		int units = (int) Math.min((double) deficit, Math.floor(stock * fraction / unit));
		if (units <= 0) return;
		float qty = com.getQuantityForModValue(units);
		if (qty <= 0f) qty = units * unit;
		qty = Math.min(qty, stock);
		write(r, c, stock - qty);
		com.addTradeModPlus(COVER_SOURCE, qty, days);
		if (r.coverIssued == null) r.coverIssued = new LinkedHashMap<String, Long>();
		r.coverIssued.put(c, Global.getSector().getClock().getTimestamp());
		ThreatIncConfig.log("Reserve cover: " + market.getName() + " issues " + (int) qty + " "
				+ label(c) + " against a " + deficit + "-unit shortage (" + units + " of "
				+ deficit + " units covered for " + (int) days + " days); "
				+ (int) (stock - qty) + " left in reserve");
	}

	// ------------------------------------------------------------------
	// rule 7: one commodity at one colony, in vanilla's units and ours
	// ------------------------------------------------------------------

	/** Everything a tooltip or the board needs to say about one commodity at one colony. */
	public static class CommodityStatus {
		public String commodityId;
		/** Items in reserve. */
		public float stock;
		/** Vanilla's units, as the colony screen shows them (the depot's cover included). */
		public int available;
		/** Vanilla's units. */
		public int demand;
		/** Units above demand that bank (this mod's own modifiers excluded). */
		public float surplus;
		/** Items banked per 30 days. */
		public float per30;
		public float cap;
		/** Units short, the depot's cover ignored. */
		public int deficit;
		/** A cover is in force. */
		public boolean covering;
		/** Items the cover in force issued. */
		public float coverQty;
		public float coverDaysLeft;
		/** Short, no cover, and the stock cannot buy one unit within the cover fraction (not necessarily empty). */
		public boolean exhausted;
		public float econUnit;
	}

	public static CommodityStatus status(MarketAPI market, String c) {
		if (market == null) return null;
		CommodityOnMarketAPI com = market.getCommodityData(c);
		if (com == null) return null;
		CommodityStatus s = new CommodityStatus();
		s.commodityId = c;
		s.stock = stock(market.getId(), c);
		s.available = com.getAvailable();
		s.demand = com.getMaxDemand();
		s.surplus = surplusUnits(com);
		s.per30 = accrualPer30(market, c);
		s.cap = cap(market, c);
		s.deficit = deficitUnits(com);
		s.econUnit = com.getCommodity().getEconUnit();
		s.coverQty = coverQuantity(com);
		s.covering = s.coverQty > 0f;
		float days = ThreatIncConfig.reserveShortageCoverDays();
		float fraction = ThreatIncConfig.reserveShortageCoverFraction();
		if (s.covering) {
			ColonyReserve r = get(market.getId());
			Long issued = r != null && r.coverIssued != null ? r.coverIssued.get(c) : null;
			s.coverDaysLeft = issued == null ? days : Math.max(0f,
					days - Global.getSector().getClock().getElapsedDaysSince(issued));
		} else if (s.deficit > 0 && days > 0f && fraction > 0f && s.econUnit > 0f) {
			s.exhausted = s.stock * fraction < s.econUnit;
		}
		return s;
	}

	// ------------------------------------------------------------------
	// rule 2: mobilisation is vanilla demand - the War footing condition
	// ------------------------------------------------------------------

	/**
	 * Every colony of a mobilised faction carries the War footing condition
	 * (the colony screen's face of the war: a tooltip with the reserve, the
	 * bank rate, what the depot is covering and the sell-here hint) and the
	 * hidden structure that carries its demand. Vanilla's demand for a
	 * commodity is the highest single industry's figure, not a sum, so a
	 * condition cannot add to it; a structure that wants "the colony's
	 * highest demand plus N units" can ({@link WarFootingDemand}). Both are
	 * added where missing and removed from colonies whose faction is not at
	 * war - stood down, changed hands, or the layer switched off. Hive worlds
	 * never carry them. One full economy recompute when anything changed, so
	 * the colony screen shows the war's demand now rather than on vanilla's
	 * next monthly step.
	 */
	public static void syncWarFooting(List<String> warring) {
		boolean changed = false;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.getPrimaryEntity() == null) continue;
			String factionId = market.getFactionId();
			boolean wants = factionId != null && warring.contains(factionId)
					&& !Factions.THREAT.equals(factionId);
			boolean hasCondition = market.hasCondition(WAR_FOOTING_CONDITION);
			boolean hasIndustry = market.hasIndustry(WAR_FOOTING_INDUSTRY);
			if (wants) {
				if (!hasCondition) {
					market.addCondition(WAR_FOOTING_CONDITION);
					changed = true;
				}
				boolean reapply = false;
				if (!hasIndustry) {
					market.addIndustry(WAR_FOOTING_INDUSTRY);
					reapply = true;
					ThreatIncConfig.log("War footing: " + market.getName() + " (" + factionId
							+ ") mobilised, demand +" + WarFootingDemand.unitsFor(market)
							+ " units of each reserve commodity");
				}
				Industry ind = market.getIndustry(WAR_FOOTING_INDUSTRY);
				if (ind instanceof WarFootingDemand && ((WarFootingDemand) ind).refresh()) {
					reapply = true;
				}
				if (reapply) {
					market.reapplyIndustries();
					changed = true;
				}
			} else {
				if (hasCondition) {
					market.removeCondition(WAR_FOOTING_CONDITION);
					changed = true;
				}
				if (hasIndustry) {
					market.removeIndustry(WAR_FOOTING_INDUSTRY, null, false);
					market.reapplyIndustries();
					changed = true;
					ThreatIncConfig.log("War footing: " + market.getName() + " stands down");
				}
			}
		}
		if (changed) {
			ThreatColonyManager.markEconomyDirty();
			ThreatColonyManager.flushEconomy();
		}
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
	 * The fast poll, pro-rated per 30 days, for every colony of every
	 * mobilised faction: the War footing sync, accrual from surplus, the
	 * depot covering the colony's own shortage, and a prune of entries whose
	 * market is gone or was taken by the swarm (a colony that changed hands
	 * keeps its depot for the new owner - a captured depot is a captured
	 * depot; a faction that stood down keeps its stock, frozen).
	 */
	public static void poll(float elapsedDays) {
		if (elapsedDays <= 0f) return;
		List<String> warring = ThreatWarState.warFactionIds();
		syncWarFooting(warring);
		if (warring.isEmpty() && all().isEmpty()) return;

		for (String factionId : warring) {
			for (MarketAPI market : marketsOf(factionId)) {
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				ColonyReserve r = get(market.getId());
				for (String c : COMMODITIES) {
					float per30 = accrualPer30(market, c);
					if (per30 > 0f) {
						if (r == null) r = getOrCreate(market.getId());
						float capValue = per30 * ThreatIncConfig.reserveCapMonths();
						float have = read(r, c);
						if (have < capValue) {
							write(r, c, Math.min(capValue, have + per30 * elapsedDays / 30f));
						}
					}
					if (r != null && read(r, c) > 0f) coverShortage(market, r, c);
				}
			}
		}
		logLedger(warring, elapsedDays);

		for (String marketId : new ArrayList<String>(all().keySet())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
			if (market == null || !market.isInEconomy()
					|| Factions.THREAT.equals(market.getFactionId())) {
				all().remove(marketId);
			}
		}
	}

	/** Days since the ledger was last logged; starts full so the first poll after a load logs one. */
	private static float ledgerTimer = 30f;

	/** Debug logging: one line per mobilised colony, monthly - vanilla's figures beside the reserve's. */
	protected static void logLedger(List<String> warring, float elapsedDays) {
		if (!ThreatIncConfig.debugLogging()) return;
		ledgerTimer += elapsedDays;
		if (ledgerTimer < 30f) return;
		ledgerTimer = 0f;
		for (String factionId : warring) {
			for (MarketAPI market : marketsOf(factionId)) {
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				StringBuilder sb = new StringBuilder("Reserve ledger: " + market.getName()
						+ " (" + factionId + ", size " + market.getSize() + ")");
				for (String c : COMMODITIES) {
					CommodityStatus s = status(market, c);
					if (s == null) continue;
					sb.append(" | ").append(label(c)).append(" ").append((int) s.stock)
							.append(" [").append(s.available).append("/").append(s.demand)
							.append(" surplus ").append((int) s.surplus)
							.append(" +").append((int) s.per30).append("/30d");
					if (s.covering) {
						sb.append(" covering ").append(s.deficit).append("u, ")
								.append((int) s.coverQty).append(" issued, ")
								.append((int) Math.ceil(s.coverDaysLeft)).append("d left");
					} else if (s.exhausted) {
						sb.append(" short ").append(s.deficit).append("u DEPOT TOO LOW");
					} else if (s.deficit > 0) {
						sb.append(" short ").append(s.deficit).append("u");
					}
					sb.append("]");
				}
				ThreatIncConfig.log(sb.toString());
			}
		}
	}

	/**
	 * Mobilisation stock: the moment a faction enters war mode each of its
	 * colonies starts with reserveInitialMonths of its own banking (never
	 * above the cap, never below what it already holds) - the peacetime
	 * depots a navy draws its first sortie from, banked at the peacetime
	 * surplus since the War footing's demand is added right after. Without
	 * this the first expedition would wait months for accrual alone.
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
				+ " months of surplus");
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
