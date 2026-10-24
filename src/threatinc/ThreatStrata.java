package threatinc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * THE STRATA (the user, 2026-10-24: "Bombardment eliminates chunks of strata. Strata grows
 * over time if colony is fed and the colony increases size when it reaches the next 400
 * increment. It also decays when unfed."): a hive's one figure - its ground defence before
 * the structure multipliers (SwarmNexus tops vanilla's base up to it), its growth and its
 * size. hiveDefensePerSize (400) a size: the size is the strata over 400, rounded down, 1 at
 * least, stepped by {@link #syncSize}; at 0 the hive is gone ({@link #collapse}). Fed, the
 * strata thicken at the growth pace (colonyGrowthBaseDays x size a size); starving, they thin
 * (starveDaysPerSize a size; ThreatColonyManager.advanceFedGrowth); saturation takes
 * hiveDefensePerSize per strataSuppressDaysPerSize disruption days it adds
 * (ThreatGroundFronts.saturationSlice). A ground front holds whole strata (strataHeld), which
 * stand off the figure for the defence ({@link #standing}). Until 2026-10-24 the figure was
 * the size anchor alone - 400 x size, which bombing never wore (facts 194, 377) - and growth
 * was a progress clock (ThreatIncData.growthProgressDays, still the vitality path's with size
 * upkeep off).
 */
public final class ThreatStrata {

	private ThreatStrata() {}

	/** Market memory: the hive's strata. */
	public static final String KEY = "$threatinc_strata";

	/** Strata a size: hiveDefensePerSize. */
	public static float perSize() {
		return Math.max(1f, ThreatIncConfig.hiveDefensePerSize());
	}

	/** Whether the market carries strata: a hive world. */
	public static boolean has(MarketAPI market) {
		return market != null && ThreatGroundFronts.Theatre.of(market) == ThreatGroundFronts.HIVE;
	}

	/**
	 * The hive's strata now. A hive from before the figure, or one just founded, starts at its
	 * size's worth plus the growth it had banked on the old clock, and keeps it from there.
	 */
	public static float of(MarketAPI market) {
		if (market == null) return 0f;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (mem.contains(KEY)) return Math.max(0f, mem.getFloat(KEY));
		int size = Math.max(1, market.getSize());
		float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size * IncursionManager.timeScale();
		float banked = daysPerLevel > 0f
				? Math.max(0f, Math.min(0.99f, ThreatIncData.growthProgressDays(market.getId()) / daysPerLevel)) : 0f;
		float strata = perSize() * (size + banked);
		mem.set(KEY, strata);
		return strata;
	}

	/** The strata standing against a landing: the figure less a whole stratum for each the front on the world holds. */
	public static float standing(MarketAPI market) {
		if (market == null) return 0f;
		return Math.max(0f, of(market) - perSize() * ThreatGroundFronts.strataHeld(market.getId()));
	}

	/** The strata the hive's next size needs. */
	public static float nextSizeAt(MarketAPI market) {
		return perSize() * (Math.max(1, market.getSize()) + 1);
	}

	/** The most a hive banks: a size's worth short of the one past its cap. */
	public static float cap(MarketAPI market) {
		return perSize() * (ThreatColonyManager.maxColonySize(market) + 1) - 1f;
	}

	/**
	 * Adds strata (takes them, negative) and steps the size with the figure. {@code what} names
	 * the cause for the log ("Grew", "Starved a level of", "Razed a stratum of");
	 * {@code razerFactionId} the faction whose bombardment it was (null: hunger). Returns the
	 * strata left, 0 once the hive is gone.
	 */
	public static float add(MarketAPI market, float delta, String what, String razerFactionId) {
		if (market == null) return 0f;
		float before = of(market);
		if (delta == 0f) return before;
		float after = Math.max(0f, Math.min(cap(market), before + delta));
		market.getMemoryWithoutUpdate().set(KEY, after);
		if (after <= 0f) {
			collapse(market, razerFactionId);
			return 0f;
		}
		syncSize(market, what, razerFactionId);
		return after;
	}

	/** The strata the vanilla size says, kept in step when something else resizes the hive (growColony, ThreatRazing.reduceSize): the floor on a size up, the ceiling on a size down. */
	public static void sizeChanged(MarketAPI market, int oldSize) {
		if (market == null || !market.getMemoryWithoutUpdate().contains(KEY)) return;
		int size = market.getSize();
		float strata = of(market);
		if (size > oldSize && strata < perSize() * size) {
			market.getMemoryWithoutUpdate().set(KEY, perSize() * size);
		} else if (size < oldSize && strata >= perSize() * (size + 1)) {
			market.getMemoryWithoutUpdate().set(KEY, perSize() * (size + 1) - 1f);
		}
	}

	/**
	 * The size the strata say, stepped one at a time so each step's bookkeeping and notices run:
	 * up by ThreatColonyManager.growColony at each size's worth reached, down by
	 * ThreatRazing.reduceSize when the figure falls under the size's own. Strata bombed out from
	 * under a front may leave it holding all there is: the front wins.
	 */
	protected static void syncSize(MarketAPI market, String what, String razerFactionId) {
		int cap = ThreatColonyManager.maxColonySize(market);
		for (int guard = 0; guard < 12; guard++) {
			int size = market.getSize();
			int wants = Math.max(1, Math.min(cap, (int) Math.floor(of(market) / perSize())));
			if (wants > size) {
				if (!ThreatColonyManager.growColony(market, cap)) break;
				continue;
			}
			if (wants < size) {
				ThreatRazing.reduceSize(market, what);
				if (market.getSize() >= size) break;
				announceShrink(market, size, razerFactionId);
				ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
				if (front != null && front.strataHeld >= market.getSize()) {
					ThreatIncConfig.log("The strata under the front at " + market.getName() + " are gone: the front holds all there is");
					ThreatGroundFronts.groundVictory(front, market);
					return;
				}
				continue;
			}
			break;
		}
	}

	/** A size off: "Hive Starving" for hunger, "Stratum Razed" under the razer's crest for a bombardment. */
	protected static void announceShrink(MarketAPI market, int before, String razerFactionId) {
		if (razerFactionId == null) {
			ThreatColonyManager.announce(ThreatNotice.titled("Hive Starving").good()
					.line("%s has shrunk to size %s", ThreatNotice.market(market), "" + market.getSize()));
			return;
		}
		FactionAPI razer = Global.getSector().getFaction(razerFactionId);
		ThreatNotice n = ThreatNotice.titled("Stratum Razed").good().icon(razer);
		if (Factions.PLAYER.equals(razerFactionId)) {
			n.line("Your bombardment has razed a stratum of %s.", ThreatNotice.market(market));
		} else if (razer != null) {
			n.line("%s's bombardment has razed a stratum of %s.", ThreatNotice.faction(razer), ThreatNotice.market(market));
		} else {
			n.line("Bombardment has razed a stratum of %s.", ThreatNotice.market(market));
		}
		n.line("Size %s to %s.", "" + before, "" + market.getSize());
		ThreatColonyManager.announce(n);
	}

	/**
	 * The strata are gone (the user, 2026-10-24: "hive should shrink to zero and be destroyed if it
	 * reaches 0"): a front standing on the world takes the victory
	 * (ThreatGroundFronts.groundVictory), else "Hive Starved" or "Hive Razed" and
	 * ThreatColonyManager.eradicate.
	 */
	protected static void collapse(MarketAPI market, String razerFactionId) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front != null) {
			ThreatIncConfig.log("Strata gone under a front: " + market.getName());
			ThreatGroundFronts.groundVictory(front, market);
			return;
		}
		ThreatNotice n;
		if (razerFactionId == null) {
			ThreatIncConfig.log("Starved out: " + market.getName());
			n = ThreatNotice.titled("Hive Starved").good().line("%s has starved", ThreatNotice.market(market));
		} else {
			ThreatIncConfig.log("Razed out by " + razerFactionId + ": " + market.getName());
			FactionAPI razer = Global.getSector().getFaction(razerFactionId);
			n = ThreatNotice.titled("Hive Razed").good().icon(razer);
			if (Factions.PLAYER.equals(razerFactionId)) {
				n.line("Your bombardment has razed the last stratum of %s.", ThreatNotice.market(market));
			} else if (razer != null) {
				n.line("%s's bombardment has razed the last stratum of %s.", ThreatNotice.faction(razer),
						ThreatNotice.market(market));
			} else {
				n.line("Bombardment has razed the last stratum of %s.", ThreatNotice.market(market));
			}
		}
		n.line("The strata are cold.");
		ThreatColonyManager.announce(n);
		ThreatColonyManager.eradicate(market);
	}

	/** "1,150" - the figure for a tooltip. */
	public static String fmt(float strata) {
		return Misc.getWithDGS((int) strata);
	}
}
