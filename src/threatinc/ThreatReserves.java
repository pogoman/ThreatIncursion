package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SubmarketPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.LocalResourcesSubmarketPlugin;
import com.fs.starfarer.api.util.Misc;

/**
 * Per-colony faction RESERVES (docs/strategy-layer.md): the stock of marines,
 * heavy armaments, fuel and supplies a mobilised faction's colony holds for
 * the war. Everything the faction commits - expedition landing forces, task
 * force provisioning, convoy cargo - is drawn from here.
 *
 * <p>A PLAYER colony's reserve IS its vanilla resource stockpile (the
 * local-resources submarket the colony screen shows, 2026-09-05): stock is
 * read from and written to that cargo, so what the player leaves there is
 * the reserve and what a sortie takes leaves it. Vanilla fills it from the
 * colony's production and excess (a Waystation raises the fuel, supply and
 * crew rates) and spends it on shortages under the player's own "use
 * stockpiles" toggle; the mod adds only the militia marines. NPC colonies
 * have no visible stockpile, so theirs stays the ledger below, banked from
 * surplus by the same rule vanilla stockpiles by. {@link #backing} decides.
 *
 * <p>The reserve is ONE truth with vanilla's colony screen
 * (docs/economy-coherence.md, the seven rules). It is banked only from the
 * colony's vanilla SURPLUS - availability above demand - at vanilla's own
 * stockpiling scale (rule 1); mobilisation is vanilla demand, the War footing
 * condition (rule 2); the depot is spent on the colony's own vanilla shortage
 * before anything sails (rule 3); a player's sale does what a sale always
 * does, ending the shortage and feeding the banking (rule 4); convoys land in
 * the depot only, never on the open market (rule 5); the board shows
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
	/** Trade-modifier source prefix older builds gave a convoy landing (rule 5); stripped on load. */
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
		/**
		 * The largest months basis this depot has banked at, per commodity, at
		 * full production share ({@link #noteBasis}) - the basis of its floor
		 * once the colony is in deficit and the live basis reads zero (see
		 * {@link #floor}). A reference, never a ceiling: the field keeps its old
		 * name so saves load. Null on older saves.
		 */
		public Map<String, Float> capSeen;
		/**
		 * Veterancy pool of the colony's marines, on vanilla's shape: an
		 * absolute figure clamped to the armed headcount, so the level is
		 * {@code marineXp / armedMarines} (see {@link ThreatMarineXP}). Mod
		 * state even for a player colony, whose STOCK is the vanilla stockpile
		 * - vanilla has no opinion about the quality of marines sitting in a
		 * resource stockpile. 0 on older saves: a green garrison, which is what
		 * one that has never been invaded is.
		 */
		public float marineXp;
		/**
		 * Marines that have actually been called up, armed and posted - the
		 * only ones that defend. Ramps toward the stock over
		 * {@code marineArmingDays}, so a mass of marines shipped in mid-siege
		 * does not turn into a garrison the same day (2026-09-08). Clamped down
		 * the instant stock leaves.
		 */
		public float armedMarines;
		/**
		 * Whether {@link #armedMarines} has been seeded from the stock. False
		 * on older saves and on a fresh object, where it means "assume the
		 * standing stock is already armed" - a colony that has been sitting on
		 * its marines for years is not caught with them in crates.
		 */
		public boolean marineArmSeeded;
		/** Clock stamp of the last arming tick, so the ramp advances on its own clock whoever calls it. 0 on older saves. */
		public long marineArmedAt;
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
		CargoAPI cargo = backing(marketId);
		if (cargo != null) return cargo.getCommodityQuantity(commodityId);
		ColonyReserve r = get(marketId);
		if (r == null) return 0f;
		return read(r, commodityId);
	}

	// ------------------------------------------------------------------
	// backing: a player colony's reserve is its resource stockpile
	// ------------------------------------------------------------------

	/**
	 * The cargo that IS this colony's reserve, or null when the ledger is.
	 * A player-owned market with vanilla's local-resources submarket is
	 * backed by that cargo. Stock the ledger still holds for such a market
	 * (a save from before 2026-09-05, or a colony the player has just taken
	 * whose depot came with it) is moved into the stockpile the first time it
	 * is asked for, so nothing is counted twice or lost.
	 */
	public static CargoAPI backing(String marketId) {
		if (marketId == null) return null;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market != null) return backing(market);
		// a player outpost's stockpile is the storage of its station
		return ThreatOutposts.storage(ThreatOutposts.byStockId(marketId));
	}

	public static CargoAPI backing(MarketAPI market) {
		if (market == null || !market.isPlayerOwned()) return null;
		CargoAPI cargo = Misc.getLocalResourcesCargo(market);
		if (cargo == null) return null;
		ColonyReserve r = all().get(market.getId());
		if (r != null) {
			for (String c : COMMODITIES) {
				float held = read(r, c);
				if (held <= 0f) continue;
				cargo.addCommodity(c, held);
				write(r, c, 0f);
				ThreatIncConfig.log("Reserve: " + market.getName() + " moves " + (int) held + " "
						+ label(c) + " from the ledger into its resource stockpile");
			}
		}
		return cargo;
	}

	/** Whether this colony's reserve is its resource stockpile rather than the ledger. */
	public static boolean isBacked(MarketAPI market) {
		return backing(market) != null;
	}

	/**
	 * What vanilla adds to a backed colony's stockpile per 30 days: the
	 * local-resources plugin's own rate - its stockpile limit (excess at 0.5,
	 * production at 0.25, the Waystation's bonus, times stockpileMaxMonths)
	 * times its add-rate multiplier (1 / stockpileMaxMonths). Zero for a
	 * commodity in deficit, as vanilla's limit is.
	 */
	public static float vanillaStockpilePer30(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		SubmarketPlugin sub = Misc.getLocalResources(market);
		if (!(sub instanceof LocalResourcesSubmarketPlugin)) return 0f;
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return 0f;
		LocalResourcesSubmarketPlugin lr = (LocalResourcesSubmarketPlugin) sub;
		return Math.max(0f, lr.getStockpileLimit(com) * lr.getStockpilingAddRateMult(com));
	}

	/** Vanilla's stockpile limit for a backed colony - what it fills the stockpile up to. */
	public static float vanillaStockpileLimit(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		SubmarketPlugin sub = Misc.getLocalResources(market);
		if (!(sub instanceof LocalResourcesSubmarketPlugin)) return 0f;
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return 0f;
		return Math.max(0f, ((LocalResourcesSubmarketPlugin) sub).getStockpileLimit(com));
	}

	/**
	 * The militia trickle: marines every colony raises per 30 days regardless
	 * of industry. The Path's zealots raise pathMilitiaMult times as many
	 * (user's call 2026-09-27: its strength is people, not industry).
	 */
	public static float militiaPer30(MarketAPI market, String commodityId) {
		if (market == null || !Commodities.MARINES.equals(commodityId)) return 0f;
		float mult = Factions.LUDDIC_PATH.equals(market.getFactionId()) ? ThreatIncConfig.pathMilitiaMult() : 1f;
		return ThreatIncConfig.reserveBaselinePerSize() * market.getSize() * mult;
	}

	/** Tithes per 30 days, [supplies, fuel, marines], as read at the clock instant in titheDay; not saved. */
	private static float[] tithe;
	private static int titheDepots;
	private static long titheDay = Long.MIN_VALUE;
	private static Object titheSector;

	/**
	 * The Path's tithes (user's call 2026-09-27): every vanilla Pather cell on
	 * another faction's world sends the Path supplies, fuel and recruits each
	 * month by the world's size, a sleeper cell pathTitheSleeperFraction of it.
	 * Run 17's Path banked no fuel or supplies at all - two worlds, no surplus -
	 * and never sieged or held a link. Split evenly between the Path's depots.
	 */
	public static float tithePer30(MarketAPI market, String commodityId) {
		if (market == null || !Factions.LUDDIC_PATH.equals(market.getFactionId())) return 0f;
		int i = Commodities.SUPPLIES.equals(commodityId) ? 0 : Commodities.FUEL.equals(commodityId) ? 1
				: Commodities.MARINES.equals(commodityId) ? 2 : -1;
		if (i < 0 || !hasDepot(market)) return 0f;
		// a day's memo, aged like stagingTargets': keyed on the days since
		// timestamp 0, run 18 logged its cells once in three years
		float age = titheDay == Long.MIN_VALUE ? Float.MAX_VALUE
				: Global.getSector().getClock().getElapsedDaysSince(titheDay);
		if (tithe == null || age < 0f || age >= 1f || titheSector != Global.getSector()) {
			titheDay = Global.getSector().getClock().getTimestamp();
			titheSector = Global.getSector();
			tithe = pathTithes();
			titheDepots = 0;
			for (MarketAPI m : marketsOf(Factions.LUDDIC_PATH)) {
				if (hasDepot(m)) titheDepots++;
			}
		}
		return titheDepots > 0 ? tithe[i] / titheDepots : 0f;
	}

	/** The Path's whole tithe per 30 days, [supplies, fuel, marines], from the cells vanilla has placed. */
	protected static float[] pathTithes() {
		float[] out = new float[3];
		if (!ThreatIncConfig.pathTithes()) return out;
		float sizes = 0f;
		int cells = 0, sleepers = 0;
		// read off the markets' Pather Cells condition: vanilla only queues the
		// cells' intel on NPC worlds, so the intel manager lists none of them
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!m.isInEconomy() || Factions.LUDDIC_PATH.equals(m.getFactionId())) continue;
			com.fs.starfarer.api.campaign.econ.MarketConditionAPI mc =
					m.getCondition(com.fs.starfarer.api.impl.campaign.ids.Conditions.PATHER_CELLS);
			if (mc == null || !(mc.getPlugin() instanceof com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCells)) continue;
			com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCellsIntel cell =
					((com.fs.starfarer.api.impl.campaign.intel.bases.LuddicPathCells) mc.getPlugin()).getIntel();
			if (cell == null || cell.isEnded() || cell.isEnding()) continue;
			float w = cell.isSleeper() ? ThreatIncConfig.pathTitheSleeperFraction() : 1f;
			if (cell.isSleeper()) sleepers++;
			cells++;
			sizes += m.getSize() * w;
		}
		out[0] = sizes * ThreatIncConfig.pathTitheSuppliesPerSize();
		out[1] = sizes * ThreatIncConfig.pathTitheFuelPerSize();
		out[2] = sizes * ThreatIncConfig.pathTitheMarinesPerSize();
		ThreatIncConfig.logQuiet("path_tithe", "Path tithes: " + cells + " cells (" + sleepers + " sleeping), "
				+ (int) out[0] + " supplies, " + (int) out[1] + " fuel, " + (int) out[2] + " marines per 30 days");
		return out;
	}

	/**
	 * A base's logistics structure. A player's or NPC colony needs a
	 * functional Waystation - vanilla's stockpiling structure, the one that
	 * fills the resource stockpile with fuel, supplies and crew - to stage,
	 * to sail sorties and convoys, and to receive them; a hive world needs an
	 * operational Swarm Nexus, its equivalent, as its strikes already do.
	 * Disrupting either (a raid) severs the base. Off with the
	 * baseRequiresWaystation knob.
	 */
	public static boolean hasDepot(MarketAPI market) {
		if (market == null) return false;
		if (!ThreatIncConfig.baseRequiresWaystation()) return true;
		if (Factions.THREAT.equals(market.getFactionId())) {
			return ThreatColonyManager.hasOperationalNexus(market);
		}
		Industry w = market.getIndustry(Industries.WAYSTATION);
		return w != null && w.isFunctional() && !w.isDisrupted();
	}

	/** As above for either kind of base: an outpost is its own depot - a station in orbit needs no Waystation. */
	public static boolean hasDepot(ThreatBases.Base base) {
		if (base == null) return false;
		if (base.isOutpost()) return base.outpost.alive();
		return hasDepot(base.market);
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

	/** What a draw took, as demand on the faction's stock plan (ThreatFactionStock.noteDemand). */
	protected static void noteSpent(String marketId, String commodityId, float taken) {
		if (taken <= 0f) return;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market != null) ThreatFactionStock.noteDemand(market.getFactionId(), commodityId, taken);
	}

	/** Takes up to {@code amount}; returns what was actually taken. */
	public static float draw(String marketId, String commodityId, float amount) {
		if (amount <= 0f) return 0f;
		CargoAPI cargo = backing(marketId);
		if (cargo != null) {
			float taken = Math.min(cargo.getCommodityQuantity(commodityId), amount);
			if (taken > 0f) cargo.removeCommodity(commodityId, taken);
			noteSpent(marketId, commodityId, taken);
			return Math.max(0f, taken);
		}
		ColonyReserve r = get(marketId);
		if (r == null) return 0f;
		float have = read(r, commodityId);
		float taken = Math.min(have, amount);
		write(r, commodityId, have - taken);
		noteSpent(marketId, commodityId, taken);
		return taken;
	}

	/**
	 * Stock above the colony's floor - what a sortie may actually take. The
	 * floor (reserveFloorFraction of the months basis) is the home garrison's stock;
	 * expeditions and orders never draw below it, so a small faction cannot
	 * empty its depots on one sortie and go quiet for the rest of the war
	 * (docs/design-theory.md 8.6). Convoys use the donor keep fraction instead.
	 * The player's own colonies have their own fraction,
	 * playerReserveFloorFraction, default 0: the player's orders may commit
	 * the whole stock (2026-09-05 evening, the user: "totally fine for them
	 * to commit their whole regiment if I want them to").
	 */
	public static float available(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		if (committed(market, commodityId)) return 0f;
		return threatinc.rules.ReserveRules.available(stock(market.getId(), commodityId), floor(market, commodityId));
	}

	/**
	 * Stock that is fighting and cannot be shipped: a colony's banked marines
	 * while an enemy army stands on its surface. They count as its defenders
	 * (ThreatGroundFronts.defenderStrength), so no sortie or convoy may carry
	 * them away mid-siege - sorties ask here through {@link #available},
	 * logistics convoys through ThreatConvoys.spare.
	 */
	public static boolean committed(MarketAPI market, String commodityId) {
		return market != null && Commodities.MARINES.equals(commodityId)
				&& ThreatGroundFronts.hasFront(market);
	}

	// ------------------------------------------------------------------
	// the garrison: which marines are actually armed, and how good they are
	// (2026-09-08 - docs/ground-war.md "Marines defend, and they die")
	// ------------------------------------------------------------------

	/**
	 * Marines that are called up, armed and posted: the only ones that count as
	 * defenders. New stock ramps in over {@code marineArmingDays}, so shipping
	 * a regiment into a besieged colony buys a garrison over days rather than
	 * the same afternoon. Never above the stock - marines that leave stop
	 * defending at once.
	 */
	public static float armedMarines(MarketAPI market) {
		if (market == null) return 0f;
		float stocked = stock(market.getId(), Commodities.MARINES);
		if (stocked <= 0f) return 0f;
		ColonyReserve r = get(market.getId());
		// unseen colony, or a save from before the ramp existed: what is
		// standing there is standing there, already armed
		if (r == null || !r.marineArmSeeded) return stocked;
		return Math.max(0f, Math.min(r.armedMarines, stocked));
	}

	/**
	 * Marines arriving already trained and equipped - a front falling back onto
	 * a base, or survivors garrisoning what they took. They join the garrison
	 * at once instead of walking the arming ramp (they are the reason the ramp
	 * exists: raw stock has to be worked up, veterans do not), and their
	 * experience is credited against the headcount they actually arrived in.
	 *
	 * <p>Depositing and then calling {@link #addMarineXp} separately does NOT
	 * work: that clamps the pool to the armed count from BEFORE the arrival,
	 * which is often 0 for a base whose sortie emptied it, and the veterancy is
	 * silently thrown away (review, 2026-09-08).
	 */
	public static void depositArmed(MarketAPI market, float marines, float level) {
		if (market == null || marines <= 0f) return;
		deposit(market.getId(), Commodities.MARINES, marines);
		armReturning(market, marines, level);
	}

	/**
	 * The bookkeeping half of {@link #depositArmed}, for callers that have
	 * already banked the bodies by another route (a returning convoy settles
	 * through {@link ThreatBases}).
	 */
	public static void armReturning(MarketAPI market, float marines, float level) {
		if (market == null || marines <= 0f) return;
		ColonyReserve r = getOrCreate(market.getId());
		float stocked = stock(market.getId(), Commodities.MARINES);
		if (!r.marineArmSeeded) {
			r.marineArmSeeded = true;
			r.marineArmedAt = Global.getSector().getClock().getTimestamp();
			r.armedMarines = stocked; // never seen before: what is there is posted
		} else {
			r.armedMarines = Math.min(stocked, r.armedMarines + marines);
		}
		if (level > 0f) {
			r.marineXp = ThreatMarineXP.addXp(r.marineXp, marines * level, r.armedMarines);
		}
	}

	/**
	 * Seeds the arming ramp for every colony that has not carried one, so a
	 * save upgraded to this build counts the marines it was ALREADY sitting on
	 * as armed - and everything shipped in afterwards has to be worked up.
	 *
	 * <p>This MUST run at load rather than lazily on first sight. Lazily, the
	 * seed captured whatever the stockpile held the first time the colony
	 * happened to be walked, so marines delivered in between were grandfathered
	 * in as a standing garrison and the ramp did nothing at all (user,
	 * 2026-09-08: the counter-attack cadence collapsed to 3 days the moment
	 * marines were added, because the whole delivery counted as armed).
	 *
	 * <p>Player-owned colonies are seeded even holding nothing: they are the
	 * ones marines get hand-delivered to through vanilla's own cargo screen,
	 * which the mod never sees, so their ramp has to exist before the drop.
	 */
	public static void seedMarineArming() {
		long now = Global.getSector().getClock().getTimestamp();
		int seeded = 0;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market == null || Factions.THREAT.equals(market.getFactionId())) continue;
			if (ThreatMapFog.conditionOnly(market)) continue;
			ColonyReserve r = get(market.getId());
			if (r != null && r.marineArmSeeded) continue;
			float stocked = stock(market.getId(), Commodities.MARINES);
			// nothing to remember, and nowhere the player can drop marines
			// unseen: leave it to be seeded when it first matters
			if (stocked <= 0f && r == null && !market.isPlayerOwned()) continue;
			r = getOrCreate(market.getId());
			r.marineArmSeeded = true;
			r.armedMarines = stocked;
			r.marineArmedAt = now;
			seeded++;
		}
		if (seeded > 0) {
			ThreatIncConfig.log("Marine arming: seeded " + seeded
					+ " colonies from the stock they were already standing on");
		}
	}

	/**
	 * Records the ramp at the CURRENT stock before something is added to it, so
	 * the arrival has to be worked up rather than counting as a garrison that
	 * was always there. Every marine delivery goes through {@link #deposit},
	 * so this is where an NPC colony's first one is caught.
	 */
	protected static void ensureMarineSeed(String marketId) {
		if (marketId == null) return;
		ColonyReserve r = get(marketId);
		if (r != null && r.marineArmSeeded) return;
		float stocked = stock(marketId, Commodities.MARINES);
		r = getOrCreate(marketId);
		r.marineArmSeeded = true;
		r.armedMarines = stocked;
		r.marineArmedAt = Global.getSector().getClock().getTimestamp();
	}

	/** The colony's marine veterancy pool ({@link ThreatMarineXP}). */
	public static float marineXp(MarketAPI market) {
		if (market == null) return 0f;
		ColonyReserve r = get(market.getId());
		return r == null ? 0f : Math.max(0f, r.marineXp);
	}

	/** Grants the colony's marines experience, clamped to the armed headcount. */
	public static void addMarineXp(MarketAPI market, float gain) {
		if (market == null || gain <= 0f) return;
		ColonyReserve r = getOrCreate(market.getId());
		r.marineXp = ThreatMarineXP.addXp(r.marineXp, gain, armedMarines(market));
	}

	/**
	 * Marines killed defending. Draws from the stock - the resource stockpile
	 * for a player colony, the ledger for an NPC one - and keeps the garrison's
	 * bookkeeping straight: the armed count falls with the bodies and the
	 * veterancy pool scales so the survivors keep their level rather than being
	 * promoted for surviving. Returns what was actually lost.
	 *
	 * <p>Personal Storage is never touched. The war reads and spends the
	 * resource stockpile only (user, 2026-09-08).
	 */
	public static float spendDefendingMarines(MarketAPI market, float amount) {
		if (market == null || amount <= 0f) return 0f;
		float before = armedMarines(market);
		if (before <= 0f) return 0f;
		float taken = draw(market.getId(), Commodities.MARINES, Math.min(amount, before));
		if (taken <= 0f) return 0f;
		ColonyReserve r = getOrCreate(market.getId());
		float after = Math.max(0f, before - taken);
		r.marineArmSeeded = true;
		r.armedMarines = after;
		r.marineXp = ThreatMarineXP.scaleForLosses(r.marineXp, before, after);
		return taken;
	}

	/**
	 * Advances the arming ramp. Called for every colony the reserve poll walks,
	 * backed or not, so a garrison is worked up in peacetime and only the
	 * marines that arrive mid-siege have to be called up under fire.
	 */
	protected static void tickMarineArming(MarketAPI market) {
		if (market == null) return;
		float stocked = stock(market.getId(), Commodities.MARINES);
		// a garrison of zero is a fact worth remembering: bailing out here left
		// the colony unseeded, so its FIRST delivery counted as already armed
		// and walked straight past the ramp (review, 2026-09-08). This only
		// runs for colonies the poll or a front tick already walks, so it
		// creates no entries for markets nothing has touched.
		ColonyReserve r = getOrCreate(market.getId());
		long now = Global.getSector().getClock().getTimestamp();
		if (!r.marineArmSeeded) {
			// first sight of this colony: what it already has is already armed
			r.marineArmSeeded = true;
			r.armedMarines = stocked;
			r.marineArmedAt = now;
			return;
		}
		// the ramp advances on its OWN clock, not on the caller's elapsed days,
		// so it is safe to call from the reserve poll and the front tick both -
		// a colony under siege is walked by each of them
		float elapsed = r.marineArmedAt == 0 ? 0f
				: Global.getSector().getClock().getElapsedDaysSince(r.marineArmedAt);
		r.marineArmedAt = now;
		if (stocked <= 0f) {
			r.armedMarines = 0f;
			r.marineXp = 0f;
			return;
		}
		if (r.armedMarines > stocked) {
			// Stock shipped out (a sortie, a convoy, vanilla spending the
			// stockpile on a shortage). SCALE the pool with the headcount, do
			// not clamp it: clamping raised the level instead of preserving it,
			// so shipping a garrison away promoted whoever was left to Elite
			// (review, 2026-09-08). Same rule spendDefendingMarines uses.
			r.marineXp = ThreatMarineXP.scaleForLosses(r.marineXp, r.armedMarines, stocked);
			r.armedMarines = stocked;
		}
		if (elapsed > 0f) {
			float days = Math.max(0.01f, ThreatIncConfig.marineArmingDays());
			// the whole stock is what the depot works through, so a big intake
			// arms at the same PACE as a small one rather than the same rate
			r.armedMarines = Math.min(stocked, r.armedMarines + stocked / days * elapsed);
		}
		r.marineXp = Math.min(r.marineXp, r.armedMarines); // safety net; a no-op after the scale
	}

	/**
	 * The home garrison's stock: reserveFloorFraction of the months basis
	 * ({@link #monthsBasis}). The live basis is the colony's CURRENT banking
	 * times reserveCapMonths, and a colony in deficit has no surplus - so the
	 * moment a colony is short, its basis reads zero and a floor taken from it
	 * alone would vanish exactly when it matters (a struck world's depot could
	 * be drawn to nothing by a sortie or its own shortage covers). The floor
	 * therefore stands on the largest basis the depot has seen, which
	 * {@link #poll} records at full production share and the floor scales to
	 * the share in force - the share moves with the faction's market count,
	 * and a peak kept at an old share pinned the floor above the live basis
	 * after a drop; a colony that never banked a commodity has no floor for it.
	 */
	public static float floor(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		float basis = monthsBasis(market, commodityId);
		ColonyReserve r = get(market.getId());
		if (r != null && r.capSeen != null) {
			Float seen = r.capSeen.get(commodityId);
			if (seen != null) {
				float base = baselineMonths(market, commodityId);
				// the share is read only when the record stands above the baseline, as before the lift
				float share = seen > base ? basisShare(market, commodityId) : 1f;
				basis = threatinc.rules.ReserveRules.floorBasis(basis, seen, base, share);
			}
		}
		float floor = basis * (market.isPlayerOwned() ? ThreatIncConfig.playerReserveFloorFraction()
				: ThreatIncConfig.reserveFloorFraction());
		// the shortage's answer first (user, 2026-10-06): supplies held for a
		// producer the market cannot yet pay sit above the floor, so upkeep and
		// hunts leave them (ThreatFactionStock.hold; the builder's own pass
		// releases the hold before it reads the stock)
		if (Commodities.SUPPLIES.equals(commodityId)) floor += ThreatFactionStock.held(market);
		return floor;
	}

	/**
	 * Remembers the months basis a depot banked at, so its floor survives the
	 * colony falling into deficit. Kept at full production share: the share
	 * scales only the surplus banking, so that part is divided by the share in
	 * force (the militia and the tithes are not) and {@link #floor} scales it
	 * back to the share of the day. A record from before the share existed was
	 * made at share 1 and stands as it is.
	 */
	protected static void noteBasis(MarketAPI market, ColonyReserve r, String c, float basis) {
		if (r == null || basis <= 0f) return;
		float base = baselineMonths(market, c);
		float share = basisShare(market, c);
		basis = threatinc.rules.ReserveRules.basisAtFullShare(basis, base, share);
		if (r.capSeen == null) r.capSeen = new LinkedHashMap<String, Float>();
		Float seen = r.capSeen.get(c);
		if (seen == null || basis > seen) r.capSeen.put(c, basis);
	}

	/** Months of the banking the production share does not scale: the militia and the Path's tithes. */
	protected static float baselineMonths(MarketAPI market, String commodityId) {
		return (militiaPer30(market, commodityId) + tithePer30(market, commodityId))
				* ThreatIncConfig.reserveCapMonths();
	}

	/** The production share a colony's banking is scaled by; none for a backed colony, whose stockpile is vanilla's. */
	protected static float basisShare(MarketAPI market, String commodityId) {
		return isBacked(market) ? 1f : productionShare(market.getFactionId(), commodityId);
	}

	/** Takes up to {@code amount}, never below the floor; returns what was taken. */
	public static float drawAbove(MarketAPI market, String commodityId, float amount) {
		if (market == null || amount <= 0f) return 0f;
		return draw(market.getId(), commodityId, Math.min(amount, available(market, commodityId)));
	}

	/**
	 * Stock a hunting force may take from this base: above the floor, the
	 * donor keep share of the months basis AND the staging bank, so what convoys
	 * built up for this base's own siege stays for it. Culann's 55,852 banked
	 * fuel went on one hunt and its siege then postponed (rc1 review). A siege
	 * - the base's own or a sibling's pooling it (IncursionManager.siegeDonors,
	 * 2026-09-26) - draws through {@link #available}.
	 */
	public static float spendable(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		if (committed(market, commodityId)) return 0f;
		return threatinc.rules.ReserveRules.spendable(stock(market.getId(), commodityId), floor(market, commodityId),
				monthsBasis(market, commodityId), ThreatIncConfig.donorKeepFraction(), stagingBank(market, commodityId));
	}

	/** Takes up to {@code amount} of {@link #spendable} stock; returns what was taken. */
	public static float drawSpendable(MarketAPI market, String commodityId, float amount) {
		if (market == null || amount <= 0f) return 0f;
		return draw(market.getId(), commodityId, Math.min(amount, spendable(market, commodityId)));
	}

	/** Adds stock (a convoy arriving, a withdrawn front returning). No cap - what was made is kept. */
	public static void deposit(String marketId, String commodityId, float amount) {
		if (amount <= 0f || marketId == null) return;
		// marines arriving must ramp in, so pin the ramp at what is here NOW
		// before they land (depositArmed re-arms them straight after, which is
		// what makes a front falling back different from a fresh delivery)
		if (Commodities.MARINES.equals(commodityId)) ensureMarineSeed(marketId);
		CargoAPI cargo = backing(marketId);
		if (cargo != null) {
			cargo.addCommodity(commodityId, amount);
			return;
		}
		ColonyReserve r = getOrCreate(marketId);
		write(r, commodityId, read(r, commodityId) + amount);
	}

	// ------------------------------------------------------------------
	// accrual - rule 1: what arrives above peacetime needs banks
	// ------------------------------------------------------------------

	/**
	 * What this colony adds to a reserve per 30 days: its availability of the
	 * commodity above its PEACETIME demand, in econ units - the War footing's
	 * share as far as it is met, plus any surplus beyond it - times the
	 * commodity's econ unit (the item count behind one unit: 1,500 fuel, 750
	 * supplies, 200 heavy armaments, 100 marines) times reserveSurplusMult.
	 * The War footing's demand is the war's supply line, so a colony that
	 * imports it banks it; before 2026-09-24 only availability above the
	 * War footing's demand banked, so mobilising stopped every importer
	 * banking at all and a depot drained by sorties never refilled.
	 * BaseIndustry.getSizeMult is vanilla's own tier-to-multiplier curve, the
	 * one its local-resources submarket stockpiles excess by (at 0.5). A
	 * colony short of its peacetime needs banks nothing. Availability is vanilla's broadcast
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
		float baseline = militiaPer30(market, commodityId) + tithePer30(market, commodityId);
		// a player colony's stockpile is filled by vanilla at vanilla's rate;
		// the mod adds only the militia (poll)
		if (isBacked(market)) return baseline + vanillaStockpilePer30(market, commodityId);
		CommodityOnMarketAPI com = market.getCommodityData(commodityId);
		if (com == null) return baseline;
		float surplus = bankUnits(market, com);
		if (surplus <= 0f) return baseline;
		return threatinc.rules.ReserveRules.accrualPer30(baseline, BaseIndustry.getSizeMult(surplus),
				com.getCommodity().getEconUnit(), surplusMult(commodityId),
				productionShare(market.getFactionId(), commodityId));
	}

	/**
	 * The share of a unit of surplus banked a month: reserveSurplusMult (1.0)
	 * for fuel and supplies; marines and heavy armaments bank at
	 * reserveTroopSurplusMult (0.5, 2026-09-30), vanilla's own rate for a
	 * stockpile's excess - at 1.0 the Hegemony banked 9.1k marines and 12.9k
	 * armaments a month while its sieges landed ~700 a month (h26a).
	 */
	public static float surplusMult(String commodityId) {
		if (Commodities.MARINES.equals(commodityId) || Commodities.HAND_WEAPONS.equals(commodityId)) {
			return ThreatIncConfig.reserveTroopSurplusMult();
		}
		return ThreatIncConfig.reserveSurplusMult();
	}

	/** factionId|commodity -> {share, timestamp}; transient, recomputed daily. */
	private static final Map<String, Object[]> SHARE_CACHE = new HashMap<String, Object[]>();

	/**
	 * The faction banks what it makes (2026-09-26): vanilla's availability is
	 * broadcast - every importer gets its whole share of the same exporter's
	 * output, nothing is used up - so banking each market's surplus on its own
	 * made a faction's banking grow with its market COUNT (the long war test:
	 * the Diktat quadrupled its fuel on three colonies by adding forward
	 * bases). The faction's banking of a commodity is scaled to at most its
	 * own markets' production above their own peacetime demand, shared evenly
	 * across every market that banks it - or, if larger, the sector's best
	 * single exporter's output (times reserveBankImportsMult), what the
	 * faction can buy in. 1 when that covers it, or with
	 * reserveBankFromProduction off.
	 */
	public static float productionShare(String factionId, String commodityId) {
		if (factionId == null || !ThreatIncConfig.reserveBankFromProduction()) return 1f;
		String key = factionId + "|" + commodityId;
		long now = Global.getSector().getClock().getTimestamp();
		Object[] cached = SHARE_CACHE.get(key);
		if (cached != null) {
			float age = Global.getSector().getClock().getElapsedDaysSince((Long) cached[1]);
			if (age >= 0f && age < 1f) return (Float) cached[0];
		}
		float share = shareFigures(factionId, commodityId)[4];
		SHARE_CACHE.put(key, new Object[] { share, now });
		return share;
	}

	/**
	 * The figures behind {@link #productionShare}, in units: {made, the
	 * sector's best single exporter, budget, banked, share}. Logged monthly
	 * by the ledger, to show which term binds.
	 */
	public static float[] shareFigures(String factionId, String commodityId) {
		float made = 0f, banked = 0f, foreign = 0f;
		for (MarketAPI m : marketsOf(factionId)) {
			if (isBacked(m)) continue;
			CommodityOnMarketAPI com = m.getCommodityData(commodityId);
			if (com == null) continue;
			banked += BaseIndustry.getSizeMult(bankUnits(m, com));
			// wartime fuel and supplies: the producer's whole output, its own traffic's share too
			// (supplies: wartimeShare of it)
			float own = Math.min(com.getMaxSupply(), structuralAvailable(com))
					- (1f - wartimeShare(com)) * WarFootingDemand.peacetimeDemand(m, com);
			if (own > 0f) made += BaseIndustry.getSizeMult(own);
			if (com.getCommodityMarketData() != null) {
				foreign = Math.max(foreign, com.getCommodityMarketData().getMaxExportGlobal());
			}
		}
		// what it does not make it buys: vanilla feeds a market from the better
		// of its faction's best exporter and the sector's (getMaxExportGlobal),
		// so the faction as a whole may bank the better of its own making and
		// the sector's best exporter - once, however many markets import it
		float budget = Math.max(made,
				BaseIndustry.getSizeMult(foreign) * ThreatIncConfig.reserveBankImportsMult());
		float share = banked <= 0f ? 1f : Math.min(1f, budget / banked);
		return new float[] { made, foreign, budget, banked, share };
	}

	/** Drops the tithe and production-share memos, so a loaded game never reads the previous campaign's (game load). */
	public static void forgetCaches() {
		tithe = null;
		titheDepots = 0;
		titheDay = Long.MIN_VALUE;
		titheSector = null;
		SHARE_CACHE.clear();
	}

	/**
	 * Units of availability above the colony's peacetime demand (the War
	 * footing's own left out), this mod's own trade modifiers excluded; never
	 * negative. What banks.
	 */
	public static float surplusUnits(MarketAPI market, CommodityOnMarketAPI com) {
		if (com == null) return 0f;
		return Math.max(0f, structuralAvailable(com) - WarFootingDemand.peacetimeDemand(market, com));
	}

	/**
	 * Units a war faction's colony banks: its surplus ({@link #surplusUnits}),
	 * plus {@link #wartimeShare} of its peacetime demand (all of its fuel, and
	 * reserveWartimeSuppliesShare of its supplies) - what its peacetime
	 * demand stands for is its civilian and trade traffic, which a war
	 * requisitions, as a hive's fuel is all its fleets' (ThreatFuel). The
	 * faction's banking stays held to what it makes or buys in
	 * ({@link #productionShare}), where a producer's whole output counts.
	 */
	public static float bankUnits(MarketAPI market, CommodityOnMarketAPI com) {
		if (com == null) return 0f;
		if (com == null) return 0f;
		float share = wartimeShare(com);
		if (share <= 0f) return surplusUnits(market, com);
		return Math.max(0f, structuralAvailable(com) - (1f - share) * WarFootingDemand.peacetimeDemand(market, com));
	}

	/**
	 * The share of the colony's peacetime demand that banks on top of its
	 * surplus: 1 for fuel under {@link #wartimeFuel}, reserveWartimeSuppliesShare
	 * for supplies ({@link #wartimeSupplies}), else 0.
	 */
	public static float wartimeShare(CommodityOnMarketAPI com) {
		if (wartimeFuel(com)) return 1f;
		if (com != null && Commodities.SUPPLIES.equals(com.getId())) {
			return Math.max(0f, Math.min(1f, ThreatIncConfig.reserveWartimeSuppliesShare()));
		}
		return 0f;
	}

	/**
	 * Fuel banks at the war rate (reserveWartimeFuel, 2026-09-30): at the
	 * surplus rate a faction banked ~18.6k fuel a month - its producers' output
	 * above their own spaceports' demand - and its sieges, wanting 40-115k fuel
	 * each, were postponed 4,211 times in 71 months while marines piled up to
	 * 500k.
	 */
	public static boolean wartimeFuel(CommodityOnMarketAPI com) {
		return com != null && Commodities.FUEL.equals(com.getId()) && ThreatIncConfig.reserveWartimeFuel();
	}

	/**
	 * Supplies bank at the war rate too (reserveWartimeSuppliesShare, 2026-10-04,
	 * the share of peacetime demand that banks; 1 = like fuel):
	 * at the surplus rate the human factions banked ~53k supplies a month
	 * against ~40k of fleet upkeep and ~14k of hunts, garrisons and sieges,
	 * and every faction's supplies ran dry by
	 * months 84-120 while its fuel sat at 50k-1M (hw4p, hw4q, hw4r); relief
	 * then could not sail from any base at all.
	 */
	public static boolean wartimeSupplies(CommodityOnMarketAPI com) {
		return com != null && Commodities.SUPPLIES.equals(com.getId()) && ThreatIncConfig.reserveWartimeSuppliesShare() > 0f;
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

	/**
	 * The colony's REFERENCE stock of a commodity - months of its own banking
	 * plus its staging bank - for "how full" readouts. Not a ceiling
	 * (2026-09-29: the depot used to stop banking here, and production above
	 * it was lost; a staging target of tens of thousands then waited on
	 * convoys alone). Stock runs past it freely: what is made is banked.
	 */
	public static float cap(MarketAPI market, String commodityId) {
		return monthsBasis(market, commodityId) + stagingBank(market, commodityId);
	}

	/**
	 * What an NPC staging base holds for the siege it stages (2026-09-24): its
	 * staging target (ThreatConvoys.stagingTargets). A keep, not a bank limit
	 * since 2026-09-29 - banking has no ceiling - so hunts and donors leave it
	 * for the base's own siege ({@link #spendable}, ThreatConvoys.spare). Zero
	 * for any other colony and for the player's (their stockpile is
	 * vanilla's). The floor stays on the months basis, so the siege can spend
	 * what it saved.
	 */
	public static float stagingBank(MarketAPI market, String commodityId) {
		if (market == null || market.isPlayerOwned() || isBacked(market)) return 0f;
		// the sieges' (its own and those it relays for), not a garrison voyage's
		// want: that stock is the voyage's to spend (ThreatConvoys.bankTargets)
		return ThreatConvoys.bankTarget(market, commodityId);
	}

	/**
	 * Months of the colony's own banking (reserveCapMonths of it): the
	 * reference the sortie floor (reserveFloorFraction), the donor keep
	 * (donorKeepFraction) and {@link ColonyReserve#capSeen} are fractions of.
	 * Never a ceiling since 2026-09-29. A backed colony's is vanilla's
	 * stockpile limit plus months of the militia.
	 */
	public static float monthsBasis(MarketAPI market, String commodityId) {
		if (isBacked(market)) {
			return vanillaStockpileLimit(market, commodityId)
					+ militiaPer30(market, commodityId) * ThreatIncConfig.reserveCapMonths();
		}
		return threatinc.rules.ReserveRules.monthsBasis(accrualPer30(market, commodityId), ThreatIncConfig.reserveCapMonths());
	}

	/** Old name of {@link #monthsBasis}, kept for callers not yet moved over; a reference, not a cap. */
	public static float monthsCap(MarketAPI market, String commodityId) {
		return monthsBasis(market, commodityId);
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
	 * Units short of the colony's PEACETIME demand, the depot's own cover
	 * ignored - what the depot covers. A gap only in the War footing's share
	 * is the war's supply not arriving: covering it would spend the reserve
	 * to lift a figure that banks nothing back (the cover is the mod's own
	 * modifier), and no industry here is short for it.
	 */
	public static int localDeficitUnits(MarketAPI market, CommodityOnMarketAPI com) {
		if (com == null) return 0;
		int available = com.getAvailable() - ownModUnits(com, COVER_SOURCE);
		return Math.max(0, WarFootingDemand.peacetimeDemand(market, com) - Math.max(0, available));
	}

	/**
	 * While a mobilised colony is short of a reserve commodity for its own
	 * peacetime needs ({@link #localDeficitUnits}) and no cover is in force,
	 * the governor issues one: the quantity that lifts vanilla's
	 * availability by the deficit's whole units (what a player would have to
	 * sell here to end the shortage) leaves the reserve and is applied as a
	 * trade modifier for reserveShortageCoverDays, exactly as a sale is. One
	 * issue per period, never more than reserveShortageCoverFraction of the
	 * stock at hand, and only whole units - a fraction of a unit changes
	 * nothing on vanilla's screen. Like a sortie's draw it never takes the
	 * depot below its {@link #floor} - the garrison's stock is not spent on
	 * the civilian shortage either (docs/design-theory.md 8.6). When what the
	 * depot may spend cannot buy one unit the shortage stands, and
	 * {@link #status} reports the depot exhausted.
	 */
	protected static void coverShortage(MarketAPI market, ColonyReserve r, String c) {
		float days = ThreatIncConfig.reserveShortageCoverDays();
		float fraction = ThreatIncConfig.reserveShortageCoverFraction();
		if (days <= 0f || fraction <= 0f) return;
		CommodityOnMarketAPI com = market.getCommodityData(c);
		if (com == null || coverQuantity(com) > 0f) return;
		int deficit = localDeficitUnits(market, com);
		if (deficit <= 0) return;
		float unit = com.getCommodity().getEconUnit();
		if (unit <= 0f) return;
		float stock = read(r, c);
		float spendable = coverSpendable(market, stock, c, fraction);
		int units = (int) Math.min((double) deficit, Math.floor(spendable / unit));
		if (units <= 0) return;
		float qty = com.getQuantityForModValue(units);
		if (qty <= 0f) qty = units * unit;
		qty = Math.min(qty, spendable);
		write(r, c, stock - qty);
		com.addTradeModPlus(COVER_SOURCE, qty, days);
		if (r.coverIssued == null) r.coverIssued = new LinkedHashMap<String, Long>();
		r.coverIssued.put(c, Global.getSector().getClock().getTimestamp());
		ThreatIncConfig.log("Reserve cover: " + market.getName() + " issues " + (int) qty + " "
				+ label(c) + " against a " + deficit + "-unit shortage (" + units + " of "
				+ deficit + " units covered for " + (int) days + " days); "
				+ (int) (stock - qty) + " left in reserve");
	}

	/** What one cover issue may spend: the cover fraction of the stock, and never the floor. */
	protected static float coverSpendable(MarketAPI market, float stock, String c, float fraction) {
		return Math.max(0f, Math.min(stock * fraction, stock - floor(market, c)));
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
		/** Units above peacetime demand that bank (this mod's own modifiers excluded). */
		public float surplus;
		/** Items banked per 30 days. */
		public float per30;
		/** The reference stock ({@link ThreatReserves#cap}) for "how full" readouts; stock may exceed it, banking never stops at it. */
		public float cap;
		/** Units short, the depot's cover ignored. */
		public int deficit;
		/** Units short of peacetime demand - what the depot covers; the rest of {@link #deficit} is war supply not arriving. Backed: what the stockpile's cover lifts. */
		public int localDeficit;
		/** A cover is in force. */
		public boolean covering;
		/** Items the cover in force issued. */
		public float coverQty;
		public float coverDaysLeft;
		/** Short of peacetime needs, no cover, and what the depot may spend cannot buy one unit (not necessarily empty). */
		public boolean exhausted;
		public float econUnit;
		/** The garrison's stock - what neither a sortie nor a shortage cover takes. */
		public float floor;
		/** The reserve is the colony's resource stockpile (a player colony). */
		public boolean backed;
		/** Backed, short, and the player's "use stockpiles for shortages" is off. */
		public boolean stockpilesOff;
	}

	public static CommodityStatus status(MarketAPI market, String c) {
		if (market == null) return null;
		CommodityOnMarketAPI com = market.getCommodityData(c);
		if (com == null) return null;
		CommodityStatus s = new CommodityStatus();
		s.commodityId = c;
		s.backed = isBacked(market);
		s.stock = stock(market.getId(), c);
		if (s.backed) {
			s.available = com.getAvailable();
			s.demand = com.getMaxDemand();
			s.surplus = surplusUnits(market, com);
			s.per30 = accrualPer30(market, c);
			s.cap = cap(market, c);
			s.deficit = Math.max(0, s.demand - Math.max(0, s.available));
			s.econUnit = com.getCommodity().getEconUnit();
			s.floor = floor(market, c);
			// vanilla's own cover lifts availability by the deficit while it draws
			StatMod lr = com.getAvailableStat().getFlatStatMod(Submarkets.LOCAL_RESOURCES);
			s.covering = lr != null && lr.value > 0f;
			// deficit reads 0 while the cover draws: the shortage it pays for is what it lifts
			s.localDeficit = s.covering ? Math.round(lr.value) : s.deficit;
			s.stockpilesOff = s.deficit > 0 && !market.isUseStockpilesForShortages();
			return s;
		}
		s.available = com.getAvailable();
		s.demand = com.getMaxDemand();
		s.surplus = bankUnits(market, com);
		s.per30 = accrualPer30(market, c);
		s.cap = cap(market, c);
		s.deficit = deficitUnits(com);
		s.localDeficit = localDeficitUnits(market, com);
		s.econUnit = com.getCommodity().getEconUnit();
		s.floor = floor(market, c);
		s.coverQty = coverQuantity(com);
		s.covering = s.coverQty > 0f;
		float days = ThreatIncConfig.reserveShortageCoverDays();
		float fraction = ThreatIncConfig.reserveShortageCoverFraction();
		if (s.covering) {
			ColonyReserve r = get(market.getId());
			Long issued = r != null && r.coverIssued != null ? r.coverIssued.get(c) : null;
			s.coverDaysLeft = issued == null ? days : Math.max(0f,
					days - Global.getSector().getClock().getElapsedDaysSince(issued));
		} else if (s.localDeficit > 0 && days > 0f && fraction > 0f && s.econUnit > 0f) {
			s.exhausted = coverSpendable(market, s.stock, c, fraction) < s.econUnit;
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
			if (ThreatMapFog.hidden(market) || market.getPrimaryEntity() == null) continue;
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
				// an NPC faction's mobilisation builds the depot its bases need:
				// a Waystation at every military world with a spaceport (only
				// one vanilla market ships with one); the player builds their own.
				// Paid as every structure is (2026-10-01, ThreatBuildCost): its
				// vanilla build cost from the world's war reserve and its build
				// time - short of the supplies, it waits for them
				if (ThreatIncConfig.mobilisationBuildsWaystation() && !market.isPlayerOwned()
						&& IncursionManager.hasMilitary(market) && market.hasSpaceport()
						&& !market.hasIndustry(Industries.WAYSTATION)) {
					float cost = ThreatBuildCost.supplies(Industries.WAYSTATION);
					if (cost <= 0f || available(market, Commodities.SUPPLIES) >= cost) {
						if (cost > 0f) drawAbove(market, Commodities.SUPPLIES, cost);
						market.addIndustry(Industries.WAYSTATION);
						Industry station = market.getIndustry(Industries.WAYSTATION);
						if (cost > 0f && station != null && ThreatBuildCost.buildDays(Industries.WAYSTATION) > 0f) {
							station.startBuilding();
						}
						reapply = true;
						ThreatIncConfig.log("War footing: " + market.getName() + " (" + factionId
								+ ") builds a Waystation for " + (int) cost + " supplies - its depot for the war");
					} else {
						ThreatIncConfig.logQuiet("waystation:" + market.getId(), "War footing: " + market.getName()
								+ " (" + factionId + ") waits on " + (int) cost + " supplies for its Waystation");
					}
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
			if (ThreatMapFog.hidden(market) || market.getPrimaryEntity() == null) continue;
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
				// the garrison works up whether or not anyone has landed yet
				tickMarineArming(market);
				CargoAPI cargo = backing(market);
				if (cargo != null) {
					// the stockpile: vanilla fills it and spends it on shortages
					// under the player's toggle; the mod adds the militia
					ColonyReserve r = getOrCreate(market.getId());
					for (String c : COMMODITIES) {
						noteBasis(market, r, c, monthsBasis(market, c));
						float militia = militiaPer30(market, c);
						if (militia <= 0f) continue;
						// (2026-09-29: the militia stopped at vanilla's limit plus
						// reserveCapMonths of itself - the mod's own ceiling, not
						// vanilla's. Vanilla still fills its share up to its own
						// limit; the recruits the colony raises are all kept.)
						cargo.addCommodity(c, militia * elapsedDays / 30f);
					}
					continue;
				}
				ColonyReserve r = get(market.getId());
				for (String c : COMMODITIES) {
					float per30 = accrualPer30(market, c);
					if (per30 > 0f) {
						if (r == null) r = getOrCreate(market.getId());
						noteBasis(market, r, c, per30 * ThreatIncConfig.reserveCapMonths()); // the floor's basis
						// what is made is banked (2026-09-29): accrual stopped at
						// reserveCapMonths of banking plus the staging target, so a
						// producer lost everything above it and every hunt, garrison
						// and siege paying from spendable stock was throttled by it.
						// productionShare already bounds the banking by what the
						// faction really makes
						write(r, c, read(r, c) + per30 * elapsedDays / 30f);
					}
					if (r != null && read(r, c) > 0f) coverShortage(market, r, c);
				}
			}
		}
		logLedger(warring, elapsedDays);

		for (String marketId : new ArrayList<String>(all().keySet())) {
			// an outpost's stockpile is keyed by its station entity, not a market
			if (ThreatOutposts.byStockId(marketId) != null) continue;
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
			// the faction's banking bound (productionShare): which term binds
			for (String c : new String[] { Commodities.FUEL, Commodities.SUPPLIES }) {
				float[] f = shareFigures(factionId, c);
				ThreatIncConfig.log("Reserve budget: " + factionId + " " + label(c) + " made " + Math.round(f[0])
						+ ", sector's best exporter " + Math.round(f[1]) + ", budget " + Math.round(f[2])
						+ ", banked " + Math.round(f[3]) + " units, share " + Math.round(f[4] * 100f) + "%");
			}
		}
	}

	/**
	 * Mobilisation stock: the moment a faction enters war mode each of its
	 * colonies starts with reserveInitialMonths of its own banking (never
	 * below what it already holds) - the depots a navy
	 * draws its first sortie from. Runs after the War footing's demand has
	 * landed and the economy recomputed (ThreatWarState.mobilise), so an
	 * importer seeds the War footing's share it will bank, not the nothing
	 * its peacetime surplus was. Without this the first expedition would
	 * wait months for accrual alone.
	 */
	public static void seed(String factionId) {
		float months = ThreatIncConfig.reserveInitialMonths();
		if (months <= 0f) return;
		for (MarketAPI market : marketsOf(factionId)) {
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			// a player colony's stockpile has been filling since it was founded
			if (isBacked(market)) continue;
			ColonyReserve r = null;
			for (String c : COMMODITIES) {
				float per30 = accrualPer30(market, c);
				if (per30 <= 0f) continue;
				if (r == null) r = getOrCreate(market.getId());
				noteBasis(market, r, c, per30 * ThreatIncConfig.reserveCapMonths());
				// (2026-09-29: no longer clipped to reserveCapMonths - that is
				// a reference now, not a cap)
				float have = read(r, c);
				float start = threatinc.rules.ReserveRules.seeded(have, per30, months);
				if (have < start) write(r, c, start);
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
		for (ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(factionId)) {
			total += ThreatOutposts.stock(o, commodityId);
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
