package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;

/**
 * THE HULL POOL (docs/hull-pool.md; the user's decision 2026-10-06): one hull
 * economy for the player's faction, the NPC factions and the hive, from
 * vanilla's own figures.
 *
 * <p><b>Standing hulls.</b> A market's standing hulls are the patrols vanilla
 * would keep over it: the patrol counts its military structure and size set
 * ({@code PATROL_NUM_LIGHT/MEDIUM/HEAVY_MOD}, MilitaryBase.apply - a Patrol HQ
 * two light, a Military Base by size, a High Command one more medium and heavy;
 * the Swarm Nexus, Bastion and Command set the same) times the mean combat
 * points of each weight (MilitaryBase.getPatrolCombatFP: 20 / 37.5 / 62.5),
 * through Misc.getAdjustedStrength - quality (the faction's best ships
 * producer, so a pristine nanoforge anywhere raises every market's; stability;
 * doctrine), the market's fleet-size multiplier (size, quantity doctrine,
 * stability, shortages) and the officer doctrine. Nothing is invented: a
 * market with no military structure keeps no hulls.
 *
 * <p><b>The pool.</b> A faction's pool is its markets' standing hulls summed.
 * Every fleet the mod sends out holds its fleet points against the pool
 * (ThreatAidCapacity's ledger, now every faction's), gives them back when it is
 * home, and leaves a <b>debt</b> for what did not come back. The debt is
 * rebuilt only by the faction's ships output - Heavy Industry and Orbital
 * Works' SHIPS supply, the hive's forges - at fabFPPerShipUnit a unit a month
 * ({@link #rebuild}), the rule the hive has always built swarms by. No Heavy
 * Industry, no replacement: a faction fields its standing navy once.
 *
 * <p>The hive's garrison want is the same figure (ThreatPosture.minimumFP);
 * its replacement is the forge bank as before, so the hive keeps no debt here.
 */
public class ThreatHulls {

	public static final String KEY_DEBT = "threatinc_hullDebt";
	public static final String KEY_REBUILT = "threatinc_hullRebuiltAt";
	/** Persistent: factionId -> fleet points of hulls its yards built beyond its losses - the war navy. */
	public static final String KEY_BUILT = "threatinc_hullBuilt";

	/** Mean combat points of vanilla's patrol weights (MilitaryBase.getPatrolCombatFP: 15-25, 30-45, 50-75). */
	public static final float LIGHT_FP = 20f, MEDIUM_FP = 37.5f, HEAVY_FP = 62.5f;

	public static boolean enabled() {
		return ThreatIncConfig.hullPool();
	}

	// ------------------------------------------------------------------
	// vanilla's patrol table, for the hive's own structures
	// ------------------------------------------------------------------

	/**
	 * The patrols vanilla's MilitaryBase.apply sets for a market of this size
	 * under a Patrol HQ (tier 0), a Military Base (1) or a High Command (2),
	 * as {light, medium, heavy}. The Swarm Nexus applies it for a hive at the
	 * colony's tier (SwarmBastion.tier), so a hive's standing hulls are read
	 * exactly as a human world's.
	 */
	public static int[] patrolTable(int size, int tier) {
		int light = 2, medium = 0, heavy = 0;
		if (tier > 0) {
			if (size == 5) { light = 2; medium = 1; }
			else if (size == 6) { light = 3; medium = 1; }
			else if (size == 7) { light = 3; medium = 2; }
			else if (size == 8) { light = 3; medium = 3; }
			else if (size >= 9) { light = 4; medium = 3; }
			medium = Math.max(medium + 1, size / 2 - 1);
			heavy = Math.max(heavy, medium - 1);
			if (tier > 1) {
				medium++;
				heavy++;
			}
		}
		return new int[] { light, medium, heavy };
	}

	/** Sets the market's patrol counts to the table's, under the given modifier id. */
	public static void applyPatrols(MarketAPI market, String modId, int[] counts) {
		if (market == null || counts == null) return;
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_LIGHT_MOD).modifyFlat(modId, counts[0]);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD).modifyFlat(modId, counts[1]);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD).modifyFlat(modId, counts[2]);
	}

	public static void unapplyPatrols(MarketAPI market, String modId) {
		if (market == null) return;
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_LIGHT_MOD).unmodifyFlat(modId);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD).unmodifyFlat(modId);
		market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD).unmodifyFlat(modId);
	}

	// ------------------------------------------------------------------
	// standing hulls
	// ------------------------------------------------------------------

	/** Vanilla's patrol count of one weight over the market. */
	public static int patrols(MarketAPI market, String stat) {
		if (market == null) return 0;
		return (int) market.getStats().getDynamic().getMod(stat).computeEffective(0f);
	}

	/**
	 * The combat points of the patrols vanilla would keep over the market,
	 * unadjusted: counts by weight times the weight's mean.
	 */
	public static float patrolFP(MarketAPI market) {
		if (market == null) return 0f;
		return patrols(market, Stats.PATROL_NUM_LIGHT_MOD) * LIGHT_FP
				+ patrols(market, Stats.PATROL_NUM_MEDIUM_MOD) * MEDIUM_FP
				+ patrols(market, Stats.PATROL_NUM_HEAVY_MOD) * HEAVY_FP;
	}

	/**
	 * The market's standing hulls: {@link #patrolFP} through vanilla's
	 * Misc.getAdjustedStrength (quality, fleet size, officers), times
	 * hullPoolMult. 0 for a market with no patrols.
	 */
	public static float standingFP(MarketAPI market) {
		if (market == null) return 0f;
		float fp = patrolFP(market);
		if (fp <= 0f) return 0f;
		return Misc.getAdjustedStrength(fp, market) * ThreatIncConfig.hullPoolMult();
	}

	/** The faction's markets that count: every one it owns in the economy (forward bases are markets). */
	public static List<MarketAPI> marketsOf(String factionId) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (factionId == null) return result;
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (factionId.equals(m.getFactionId())) result.add(m);
		}
		return result;
	}

	/** The faction's standing hulls: its markets' summed, plus what its yards built (built). */
	public static float standingFP(String factionId) {
		float sum = built(factionId);
		for (MarketAPI m : marketsOf(factionId)) sum += standingFP(m);
		return sum;
	}

	// ------------------------------------------------------------------
	// production: what rebuilds a loss
	// ------------------------------------------------------------------

	/**
	 * Hull units the market's shipyard makes a month: its SHIPS supply from a
	 * working Heavy Industry or Orbital Works (the hive's forge is one), as
	 * ThreatColonyManager.forgeOutput reads the hive's. 0 without one.
	 */
	public static float shipUnits(MarketAPI market) {
		if (market == null) return 0f;
		Industry yard = market.getIndustry(Industries.HEAVYINDUSTRY);
		if (yard == null) yard = market.getIndustry(Industries.ORBITALWORKS);
		if (yard == null || yard.isDisrupted() || !yard.isFunctional()) return 0f;
		CommodityOnMarketAPI ships = market.getCommodityData(Commodities.SHIPS);
		if (ships == null) return 0f;
		return Math.max(0f, Math.min(ships.getMaxSupply(), ThreatReserves.structuralAvailable(ships)));
	}

	/** Fleet points of hulls the faction's shipyards make a month: units x fabFPPerShipUnit. */
	public static float productionFP(String factionId) {
		float units = 0f;
		for (MarketAPI m : marketsOf(factionId)) units += shipUnits(m);
		return units * ThreatIncConfig.fabFPPerShipUnit();
	}

	// ------------------------------------------------------------------
	// the debt: hulls lost, not yet rebuilt
	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	protected static Map<String, Float> debts() {
		Object val = Global.getSector().getPersistentData().get(KEY_DEBT);
		if (!(val instanceof Map)) {
			val = new HashMap<String, Float>();
			Global.getSector().getPersistentData().put(KEY_DEBT, val);
		}
		return (Map<String, Float>) val;
	}

	/** Fleet points of hulls the faction has lost and not yet rebuilt. */
	public static float debt(String factionId) {
		Float d = factionId != null ? debts().get(factionId) : null;
		return d != null ? Math.max(0f, d) : 0f;
	}

	/** Books hulls lost: a fleet destroyed, or the part of one that did not come home. */
	public static void lose(String factionId, float fp, String label) {
		if (!enabled() || factionId == null || fp <= 0f) return;
		if (Factions.THREAT.equals(factionId)) return; // the hive's bank rebuilds its own
		float d = debt(factionId) + fp;
		debts().put(factionId, d);
		ThreatIncConfig.log("Hulls: " + ThreatWarState.displayName(factionId) + " lost " + (int) fp + " FP"
				+ (label != null ? " with " + label : "") + "; " + (int) d + " FP to rebuild at "
				+ (int) productionFP(factionId) + " FP/mo");
	}

	// ------------------------------------------------------------------
	// the navy: hulls the yards built beyond the losses (the user, 2026-10-06:
	// "its a wartime economy why would they stop producing"; no caps)
	// ------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	protected static Map<String, Float> builtMap() {
		Object val = Global.getSector().getPersistentData().get(KEY_BUILT);
		if (!(val instanceof Map)) {
			val = new HashMap<String, Float>();
			Global.getSector().getPersistentData().put(KEY_BUILT, val);
		}
		return (Map<String, Float>) val;
	}

	/** Fleet points of hulls the faction's yards have built beyond its losses: standing hulls over vanilla's patrols, lost like any other. */
	public static float built(String factionId) {
		Float b = factionId != null ? builtMap().get(factionId) : null;
		return b != null ? Math.max(0f, b) : 0f;
	}

	/**
	 * Daily: every mobilised faction's shipyards (and any faction's with a
	 * loss to rebuild) make their month's output pro rata to the days since
	 * the last call; it pays the losses down first, and what is left adds to
	 * the faction's built hulls - a navy that grows with its yards for as
	 * long as it is at war, as the hive's grows with its forges. Nothing
	 * caps it; what caps a yard is its market's size and inputs.
	 */
	public static void rebuild() {
		if (!enabled()) return;
		Object at = Global.getSector().getPersistentData().get(KEY_REBUILT);
		long now = Global.getSector().getClock().getTimestamp();
		float days = at instanceof Long ? Global.getSector().getClock().getElapsedDaysSince((Long) at) : 0f;
		Global.getSector().getPersistentData().put(KEY_REBUILT, now);
		if (days <= 0f) return;
		Map<String, Float> debts = debts();
		List<String> ids = new ArrayList<String>(ThreatWarState.warFactionIds());
		for (String id : debts.keySet()) if (!ids.contains(id)) ids.add(id);
		for (String factionId : ids) {
			if (Factions.THREAT.equals(factionId)) continue;
			float made = productionFP(factionId) * days / 30f;
			Float d = debts.get(factionId);
			if (d != null && d <= 0f) {
				debts.remove(factionId);
				d = null;
			}
			if (made <= 0f) continue;
			if (d != null) {
				float left = d - made;
				if (left > 0f) {
					debts.put(factionId, left);
					continue;
				}
				debts.remove(factionId);
				made = -left;
				ThreatIncConfig.log("Hulls: " + ThreatWarState.displayName(factionId) + " rebuilt its losses");
			}
			if (made > 0f && ThreatWarState.isAtWar(factionId)) {
				builtMap().put(factionId, built(factionId) + made);
			}
		}
	}

	// ------------------------------------------------------------------
	// what the pool can pay
	// ------------------------------------------------------------------

	/** Fleet points the faction's fleets out hold against the pool (the ledger's live entries). */
	public static float committedFP(String factionId) {
		float sum = 0f;
		for (ThreatAidCapacity.Commitment c : ThreatAidCapacity.all()) {
			if (c.lostTimestamp != 0L) continue;
			MarketAPI m = c.marketId != null ? Global.getSector().getEconomy().getMarket(c.marketId) : null;
			if (m != null && factionId.equals(m.getFactionId())) sum += c.fp;
		}
		return sum;
	}

	/** Hulls the faction can send now: standing, less out, less lost and unbuilt. Never below 0. */
	public static float freeFP(String factionId) {
		if (factionId == null) return 0f;
		return Math.max(0f, standingFP(factionId) - committedFP(factionId) - debt(factionId));
	}

	/** Caps a reserve's payable fleet points at the faction's free hulls; payable unchanged with the pool off. */
	public static float cap(MarketAPI base, float payable) {
		if (!enabled() || base == null || payable <= 0f) return payable;
		return Math.min(payable, freeFP(base.getFactionId()));
	}

	/** One line for a faction: standing (built by its yards), out, lost, free, and the month's output. */
	public static String describe(String factionId) {
		return ThreatWarState.displayName(factionId) + " hulls " + (int) standingFP(factionId) + " FP (" + (int) built(factionId) + " built): "
				+ (int) committedFP(factionId) + " out, " + (int) debt(factionId) + " lost, "
				+ (int) freeFP(factionId) + " free; yards " + (int) productionFP(factionId) + " FP/mo";
	}

	/** The month line for the log: every mobilised faction and the player's. */
	public static void logMonth() {
		if (!enabled()) return;
		List<String> ids = new ArrayList<String>(ThreatWarState.warFactionIds());
		FactionAPI player = Global.getSector().getPlayerFaction();
		if (player != null && !ids.contains(player.getId())) ids.add(player.getId());
		for (String id : ids) ThreatIncConfig.log("Hulls: " + describe(id));
	}
}
