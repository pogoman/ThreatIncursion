package threatinc;

import java.util.ArrayList;
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
		/**
		 * The largest cap this depot has banked towards, per commodity - the
		 * basis of its floor once the colony is in deficit and the live cap
		 * reads zero (see {@link #floor}). Null on older saves.
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

	/** The militia trickle: marines every colony raises per 30 days regardless of industry. */
	public static float militiaPer30(MarketAPI market, String commodityId) {
		if (market == null || !Commodities.MARINES.equals(commodityId)) return 0f;
		return ThreatIncConfig.reserveBaselinePerSize() * market.getSize();
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

	/** Takes up to {@code amount}; returns what was actually taken. */
	public static float draw(String marketId, String commodityId, float amount) {
		if (amount <= 0f) return 0f;
		CargoAPI cargo = backing(marketId);
		if (cargo != null) {
			float taken = Math.min(cargo.getCommodityQuantity(commodityId), amount);
			if (taken > 0f) cargo.removeCommodity(commodityId, taken);
			return Math.max(0f, taken);
		}
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
	 * The player's own colonies have their own fraction,
	 * playerReserveFloorFraction, default 0: the player's orders may commit
	 * the whole stock (2026-09-05 evening, the user: "totally fine for them
	 * to commit their whole regiment if I want them to").
	 */
	public static float available(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		if (committed(market, commodityId)) return 0f;
		return Math.max(0f, stock(market.getId(), commodityId) - floor(market, commodityId));
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
			if (market.isPlanetConditionMarketOnly()) continue;
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
	 * The home garrison's stock: reserveFloorFraction of the cap. The live cap
	 * is the colony's CURRENT surplus times the cap months, and a colony in
	 * deficit has no surplus - so the moment a colony is short, the cap it
	 * banked towards reads zero and a floor taken from it alone would vanish
	 * exactly when it matters (a struck world's depot could be drawn to
	 * nothing by a sortie or its own shortage covers). The floor therefore
	 * stands on the largest cap the depot has seen, which {@link #poll}
	 * records; a colony that never banked a commodity has no floor for it.
	 */
	public static float floor(MarketAPI market, String commodityId) {
		if (market == null) return 0f;
		float basis = cap(market, commodityId);
		ColonyReserve r = get(market.getId());
		if (r != null && r.capSeen != null) {
			Float seen = r.capSeen.get(commodityId);
			if (seen != null && seen > basis) basis = seen;
		}
		return basis * (market.isPlayerOwned() ? ThreatIncConfig.playerReserveFloorFraction()
				: ThreatIncConfig.reserveFloorFraction());
	}

	/** Remembers the cap a depot banked towards, so its floor survives the colony falling into deficit. */
	protected static void noteCap(ColonyReserve r, String c, float capValue) {
		if (r == null || capValue <= 0f) return;
		if (r.capSeen == null) r.capSeen = new LinkedHashMap<String, Float>();
		Float seen = r.capSeen.get(c);
		if (seen == null || capValue > seen) r.capSeen.put(c, capValue);
	}

	/** Takes up to {@code amount}, never below the floor; returns what was taken. */
	public static float drawAbove(MarketAPI market, String commodityId, float amount) {
		if (market == null || amount <= 0f) return 0f;
		return draw(market.getId(), commodityId, Math.min(amount, available(market, commodityId)));
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
		float baseline = militiaPer30(market, commodityId);
		// a player colony's stockpile is filled by vanilla at vanilla's rate;
		// the mod adds only the militia (poll)
		if (isBacked(market)) return baseline + vanillaStockpilePer30(market, commodityId);
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

	/**
	 * The most of a commodity the colony keeps: months of its own banking.
	 * A backed colony's cap is vanilla's stockpile limit plus months of the
	 * militia; what the player leaves there above it is kept (vanilla never
	 * trims a resource it should have), it just stops accruing.
	 */
	public static float cap(MarketAPI market, String commodityId) {
		if (isBacked(market)) {
			return vanillaStockpileLimit(market, commodityId)
					+ militiaPer30(market, commodityId) * ThreatIncConfig.reserveCapMonths();
		}
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
		int deficit = deficitUnits(com);
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
		/** Short, no cover, and what the depot may spend cannot buy one unit (not necessarily empty). */
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
			s.surplus = surplusUnits(com);
			s.per30 = accrualPer30(market, c);
			s.cap = cap(market, c);
			s.deficit = Math.max(0, s.demand - Math.max(0, s.available));
			s.econUnit = com.getCommodity().getEconUnit();
			s.floor = floor(market, c);
			// vanilla's own cover lifts availability by the deficit while it draws
			StatMod lr = com.getAvailableStat().getFlatStatMod(Submarkets.LOCAL_RESOURCES);
			s.covering = lr != null && lr.value > 0f;
			s.stockpilesOff = s.deficit > 0 && !market.isUseStockpilesForShortages();
			return s;
		}
		s.available = com.getAvailable();
		s.demand = com.getMaxDemand();
		s.surplus = surplusUnits(com);
		s.per30 = accrualPer30(market, c);
		s.cap = cap(market, c);
		s.deficit = deficitUnits(com);
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
		} else if (s.deficit > 0 && days > 0f && fraction > 0f && s.econUnit > 0f) {
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
				// an NPC faction's mobilisation builds the depot its bases need:
				// a Waystation at every military world with a spaceport (only
				// one vanilla market ships with one); the player builds their own
				if (ThreatIncConfig.mobilisationBuildsWaystation() && !market.isPlayerOwned()
						&& IncursionManager.hasMilitary(market) && market.hasSpaceport()
						&& !market.hasIndustry(Industries.WAYSTATION)) {
					market.addIndustry(Industries.WAYSTATION);
					reapply = true;
					ThreatIncConfig.log("War footing: " + market.getName() + " (" + factionId
							+ ") builds a Waystation - its depot for the war");
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
				// the garrison works up whether or not anyone has landed yet
				tickMarineArming(market);
				CargoAPI cargo = backing(market);
				if (cargo != null) {
					// the stockpile: vanilla fills it and spends it on shortages
					// under the player's toggle; the mod adds the militia
					ColonyReserve r = getOrCreate(market.getId());
					for (String c : COMMODITIES) {
						float capValue = cap(market, c);
						noteCap(r, c, capValue);
						float militia = militiaPer30(market, c);
						if (militia <= 0f) continue;
						float have = cargo.getCommodityQuantity(c);
						if (have >= capValue) continue;
						cargo.addCommodity(c, Math.min(capValue - have, militia * elapsedDays / 30f));
					}
					continue;
				}
				ColonyReserve r = get(market.getId());
				for (String c : COMMODITIES) {
					float per30 = accrualPer30(market, c);
					if (per30 > 0f) {
						if (r == null) r = getOrCreate(market.getId());
						float capValue = per30 * ThreatIncConfig.reserveCapMonths();
						noteCap(r, c, capValue);
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
			// a player colony's stockpile has been filling since it was founded
			if (isBacked(market)) continue;
			ColonyReserve r = null;
			for (String c : COMMODITIES) {
				float per30 = accrualPer30(market, c);
				if (per30 <= 0f) continue;
				if (r == null) r = getOrCreate(market.getId());
				noteCap(r, c, per30 * ThreatIncConfig.reserveCapMonths());
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
