package threatinc;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.util.Misc;

/**
 * Saturation bombardment's razing bar (docs/suppression-balance.md, v2
 * section 4, 2026-09-28).
 *
 * <p>A colony stands on one level per size. Saturation pours fuel into the top
 * level its owner still holds; when that level's price is paid it is gone - a
 * size off, as vanilla's saturation takes one - and the rest carries into the
 * next. The last level ends the colony. The price is the built-up area, not
 * the headcount: {@code satFuelSize4} razes a size-4 colony, and each size up
 * costs about 3.2 times the one below (10^(size/2)). The dead stay dead:
 * nothing repairs the bar. The colony still grows between visits, never while
 * it is being saturated ({@link #saturated}).
 *
 * <p>Layers a ground front holds count as size lost (combined arms): the bar
 * is priced on size less layers held, and the bombs fall only on the owner's
 * layers. A razed level is a size off, which also lowers the base defence the
 * front's hold test reads. A story-critical world stops at size 3 and is
 * never destroyed, as vanilla's saturation spares it.
 */
public final class ThreatRazing {

	private ThreatRazing() {}

	/** Market memory: fuel poured into the colony's top standing level. */
	public static final String BAR_KEY = "$threatinc_razeFuel";
	/** Market memory: saturation fell here within the last day or two - the colony does not grow. */
	public static final String SATURATED_FLAG = "$threatinc_saturated";

	/** 10^(k/2) over the four levels a size-4 colony stands on: what the size-4 anchor is split by. */
	private static final double SIZE4_WEIGHT = Math.pow(10, 0.5) + 10 + Math.pow(10, 1.5) + 100;

	/** Fuel that razes the level at this size. */
	public static float levelFuel(int level) {
		if (level <= 0) return 0f;
		return (float) (Math.max(0f, ThreatIncConfig.satFuelSize4()) * Math.pow(10, level / 2.0) / SIZE4_WEIGHT);
	}

	/** The colony's levels still its owner's: its size less the layers a front holds. */
	public static int enemyLayers(MarketAPI market) {
		if (market == null) return 0;
		return Math.max(0, market.getSize() - ThreatGroundFronts.strataHeld(market.getId()));
	}

	/** Levels saturation can still take: every one of the owner's, or on a story-critical world those above size 3 short of its last. */
	public static int razeable(MarketAPI market) {
		if (market == null) return 0;
		int layers = enemyLayers(market);
		if (!Misc.isStoryCritical(market)) return layers;
		return Math.max(0, Math.min(layers - 1, market.getSize() - 3));
	}

	/** Fuel already poured into the top standing level. */
	public static float progress(MarketAPI market) {
		return market == null ? 0f : Math.max(0f, market.getMemoryWithoutUpdate().getFloat(BAR_KEY));
	}

	/** Fuel still to reach the bar before the colony is gone (a story-critical world: down to its floor). */
	public static float fuelToDestroy(MarketAPI market) {
		int layers = enemyLayers(market);
		int levels = razeable(market);
		if (levels <= 0) return 0f;
		float total = 0f;
		for (int k = layers; k > layers - levels; k--) total += levelFuel(k);
		return Math.max(0f, total - progress(market));
	}

	/** As {@link #fuelToDestroy}, before the shield's cut: what a fleet must pour. */
	public static float fuelToDestroyThrough(MarketAPI market) {
		return fuelToDestroy(market) / Math.max(0.01f, ThreatShield.throughput(market));
	}

	/**
	 * As {@link #fuelToDestroyThrough(MarketAPI)} for a razing that arrives in
	 * {@code days}: a hive that grows its next size first stands on one more
	 * level, and the pour starts on that one. Priced at the size on launch, the
	 * Epsilon Qades razings poured 6,186 fuel into a level that had grown to
	 * 6,907 and razed nothing.
	 */
	public static float fuelToDestroyThrough(MarketAPI market, float days) {
		float fuel = fuelToDestroyThrough(market);
		if (market == null || razeable(market) <= 0) return fuel;
		if (ThreatColonyManager.daysToNextSize(market) > days) return fuel;
		return fuel + levelFuel(enemyLayers(market) + 1) / Math.max(0.01f, ThreatShield.throughput(market));
	}

	/**
	 * Whether {@code fuel} poured here would not finish even the top standing
	 * level: the razing is short, and a commander brings the fuel home rather
	 * than burn it on a level that stands. The hive grows while a razing is
	 * under way to it - Qaras and Epsilon Qades I-A each grew a size before
	 * theirs arrived and took every drop of it without losing a level.
	 */
	public static boolean shortOfALevel(MarketAPI market, float fuel) {
		if (market == null || razeable(market) <= 0) return false;
		return Math.max(0f, fuel) * ThreatShield.throughput(market) < fuelForTopLevel(market) - 1f;
	}

	/** Fuel still to reach the bar before the top standing level falls. */
	public static float fuelForTopLevel(MarketAPI market) {
		if (razeable(market) <= 0) return 0f;
		return Math.max(0f, levelFuel(enemyLayers(market)) - progress(market));
	}

	/** What a fleet of fp pours in {@code days} with {@code fuel} aboard: its rate, never more than it carries or the colony needs. */
	public static float deliverable(MarketAPI market, float fp, float days, float fuel) {
		float rate = Math.max(0f, ThreatIncConfig.satFuelPerFPDay()) * Math.max(0f, fp) * Math.max(0f, days);
		return Math.max(0f, Math.min(Math.min(rate, Math.max(0f, fuel)), fuelToDestroyThrough(market)));
	}

	/**
	 * Pours fuel that got past the shield into the bar: each level paid for is
	 * a size off, and the last ends the colony ({@link ThreatGroundFronts#hiveRazed},
	 * {@link ThreatGroundFronts#colonyRazed}). Returns {levels razed, 1 when
	 * the colony is gone}.
	 */
	public static int[] pour(MarketAPI market, float fuel, String razerFactionId) {
		int[] out = new int[2];
		if (market == null || fuel <= 0f) return out;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float bar = progress(market) + fuel;
		while (razeable(market) > 0) {
			float need = levelFuel(enemyLayers(market));
			// a razing priced to the fuel pours the sum of the levels, which
			// rounds a hair under the last one (Qaras, Epsilon Qades I-A razed
			// 3 -> 2 -> 1 and left the last standing): saturationSlice's tolerance
			if (bar < need - 0.5f) break;
			bar = Math.max(0f, bar - need);
			out[0]++;
			if (enemyLayers(market) <= 1) {
				mem.unset(BAR_KEY);
				out[1] = 1;
				if (ThreatGroundFronts.Theatre.of(market) == ThreatGroundFronts.HIVE) {
					ThreatGroundFronts.hiveRazed(market, razerFactionId);
				} else {
					ThreatGroundFronts.colonyRazed(market, razerFactionId);
				}
				return out;
			}
			reduceSize(market);
		}
		// nothing left it may take (a story-critical floor): nothing to carry
		if (razeable(market) <= 0) bar = 0f;
		mem.set(BAR_KEY, bar);
		syncCondition(market);
		return out;
	}

	/** The colony screen shows the bar while any of it stands ({@link ThreatRazedCondition}). */
	public static void syncCondition(MarketAPI market) {
		if (market == null || !market.isInEconomy()) return;
		boolean wants = progress(market) > 0f;
		boolean has = market.hasCondition(ThreatRazedCondition.ID);
		if (wants && !has) market.addCondition(ThreatRazedCondition.ID);
		if (!wants && has) market.removeCondition(ThreatRazedCondition.ID);
	}

	/** A level razed: a size off, by vanilla's own steps - below size 3 too, which vanilla's reduceMarketSize refuses. */
	protected static void reduceSize(MarketAPI market) {
		reduceSize(market, "Razed a level of");
	}

	/** reduceSize, logged as {@code what} (a hive its upkeep starves, ThreatColonyManager.shrinkColony). */
	protected static void reduceSize(MarketAPI market, String what) {
		int old = market.getSize();
		if (old <= 1) return;
		market.removeCondition("population_" + old);
		market.addCondition("population_" + (old - 1));
		market.setSize(old - 1);
		market.getPopulation().setWeight(CoreImmigrationPluginImpl.getWeightForMarketSizeStatic(old - 1));
		market.getPopulation().normalize();
		// a hive's growth toward the next size starts again from nothing
		if (ThreatGroundFronts.Theatre.of(market) == ThreatGroundFronts.HIVE) {
			ThreatIncData.setGrowthProgressDays(market.getId(), 0f);
		}
		market.reapplyConditions();
		market.reapplyIndustries();
		ListenerUtil.reportColonySizeChanged(market, old);
		ThreatIncConfig.log(what + " " + market.getName() + ": size " + old + " -> " + market.getSize());
	}

	/** Whether saturation fell here within the last day or two: the colony does not grow while it lasts. */
	public static boolean saturated(MarketAPI market) {
		return market != null && market.getMemoryWithoutUpdate().getBoolean(SATURATED_FLAG);
	}

	/** Marks a day's saturation, long enough to reach the next one. */
	public static void markSaturated(MarketAPI market, float days) {
		if (market == null) return;
		market.getMemoryWithoutUpdate().set(SATURATED_FLAG, true, Math.max(1f, days) + 1f);
	}
}
