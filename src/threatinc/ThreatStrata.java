package threatinc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * THE STRATA ARE THE THREAT (the user, 2026-10-25, docs/strata-are-the-threat.md): a hive
 * world's one figure, in market memory - its size by the doubling table ({@link #sizeAt}:
 * strataSizeBase 400 at size 1, x strataSizeRatio 2 a size - 800, 1,600 ... 51,200 at 8), its
 * ground defence (SwarmNexus tops vanilla's base up to the strata standing), its troops (a
 * landing bleeds them, a break-up lands them) and its navy (a fleet point is strataPerFP 5
 * strata, drawn off a world when the fleet is built and credited back when it comes home -
 * ThreatColonyManager's bank is a view of this figure since phase A). The Fabrication Core alone
 * grows it (ThreatColonyManager.coreState: fed in full a world grows coreStrataPerSizeMonth x
 * size a month, one unit short it holds, two or more it starves a size per starveDaysPerSize /
 * (units - 1) days); bombardment razes it; at 0 the hive is gone ({@link #collapse}). Until
 * 2026-10-24 the figure was the size anchor alone (400 x size, which bombing never wore); until
 * 2026-10-25 the table was linear and the fleets were paid from a bank apart from it - a figure
 * written under the linear table is moved to this one the first time it is read ({@link #of}).
 */
public final class ThreatStrata {

	private ThreatStrata() {}

	/** Market memory: the hive's strata. */
	public static final String KEY = "$threatinc_strata";
	/** Market memory: the figure the hive's industries were last applied at (the Nexus top-up is recomputed when it has moved a point). */
	protected static final String APPLIED_KEY = "$threatinc_strataApplied";
	/** Market memory: the size table the figure was written under (2 = the doubling table; absent = 400 a size). */
	protected static final String TABLE_KEY = "$threatinc_strataTable";
	protected static final float TABLE_VERSION = 2f;
	/** The linear table's strata a size, for a figure written under it (hiveDefensePerSize, retired at 400). */
	protected static final float LEGACY_PER_SIZE = 400f;

	/** A stratum - the unit a ground front holds and saturation takes: strataSizeBase (400), the strata of a size-1 world. */
	public static float perSize() {
		return Math.max(1f, ThreatIncConfig.strataSizeBase());
	}

	/** The strata a size begins at: strataSizeBase x strataSizeRatio^(size - 1) - 400, 800, 1,600, 3,200, 6,400, 12,800, 25,600, 51,200. */
	public static float sizeAt(int size) {
		float ratio = Math.max(1f, ThreatIncConfig.strataSizeRatio());
		return perSize() * (float) Math.pow(ratio, Math.max(1, size) - 1);
	}

	/** The size this many strata make: 1 at least, {@code cap} at most. */
	public static int sizeOf(float strata, int cap) {
		int size = 1;
		while (size < cap && strata >= sizeAt(size + 1)) size++;
		return Math.max(1, size);
	}

	/** The strata a world of this size grows through before the next: sizeAt(size + 1) - sizeAt(size). */
	public static float span(int size) {
		return sizeAt(size + 1) - sizeAt(size);
	}

	/** A size's worth to lose at this size: the span below it; a size-1 world's whole 400. */
	public static float spanBelow(int size) {
		return size <= 1 ? sizeAt(1) : sizeAt(size) - sizeAt(size - 1);
	}

	/** Strata a fleet point: strataPerFP (5), one exchange both ways. */
	public static float strataPerFP() {
		return Math.max(0.01f, ThreatIncConfig.strataPerFP());
	}

	/** Fleet points this many strata bud. */
	public static float fpOf(float strata) {
		return strata / strataPerFP();
	}

	/** Strata this many fleet points are. */
	public static float strataOf(float fp) {
		return fp * strataPerFP();
	}

	/** Whether the market carries strata: a live hive world. */
	public static boolean has(MarketAPI market) {
		return market != null && ThreatGroundFronts.Theatre.of(market) == ThreatGroundFronts.HIVE;
	}

	/**
	 * The hive's strata now. A hive from before the figure, or one just founded, starts at its
	 * size's strata plus the growth it had banked on the old clock; a figure written under the
	 * linear table keeps its size and how far it was into the next.
	 */
	public static float of(MarketAPI market) {
		if (market == null) return 0f;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		int size = Math.max(1, market.getSize());
		float strata;
		if (mem.contains(KEY)) {
			strata = Math.max(0f, mem.getFloat(KEY));
			float table = mem.contains(TABLE_KEY) ? mem.getFloat(TABLE_KEY) : 1f;
			if (table < TABLE_VERSION) {
				float frac = Math.max(0f, Math.min(0.99f, (strata - LEGACY_PER_SIZE * size) / LEGACY_PER_SIZE));
				float moved = sizeAt(size) + frac * span(size);
				ThreatIncConfig.log("Strata: " + market.getName() + " moved to the doubling table, " + (int) strata
						+ " -> " + (int) moved + " (size " + size + ")");
				strata = moved;
				mem.set(KEY, strata);
				mem.set(TABLE_KEY, TABLE_VERSION);
			}
		} else {
			float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * size * IncursionManager.timeScale();
			float banked = daysPerLevel > 0f
					? Math.max(0f, Math.min(0.99f, ThreatIncData.growthProgressDays(market.getId()) / daysPerLevel)) : 0f;
			strata = sizeAt(size) + banked * span(size);
			mem.set(KEY, strata);
			mem.set(TABLE_KEY, TABLE_VERSION);
		}
		// THE STRATA ARE THE TROOPS (the user, 2026-10-24): the bioforms an older save landed on the
		// hive from ships broken up over it (ThreatGroundFronts.HIVE_TROOPS_KEY) are strata now
		if (mem.contains(ThreatGroundFronts.HIVE_TROOPS_KEY)) {
			float troops = Math.max(0f, mem.getFloat(ThreatGroundFronts.HIVE_TROOPS_KEY));
			mem.unset(ThreatGroundFronts.HIVE_TROOPS_KEY);
			if (troops > 0f) {
				strata += troops;
				mem.set(KEY, strata);
				ThreatIncConfig.log("Strata: " + market.getName() + " folds " + (int) troops + " landed troops into its strata (" + (int) strata + ")");
			}
		}
		return strata;
	}

	/** The strata standing against a landing: the figure less a whole stratum for each the front on the world holds. */
	public static float standing(MarketAPI market) {
		if (market == null) return 0f;
		return Math.max(0f, of(market) - perSize() * ThreatGroundFronts.strataHeld(market.getId()));
	}

	/** The strata the hive's next size needs. */
	public static float nextSizeAt(MarketAPI market) {
		return sizeAt(Math.max(1, market.getSize()) + 1);
	}

	/** The most a hive holds: the top size's strata plus a size's worth (the span below it), so a fortress at its cap has a buffer before it desizes. */
	public static float cap(MarketAPI market) {
		int max = Math.max(1, ThreatColonyManager.maxColonySize(market));
		return sizeAt(max) + spanBelow(max);
	}

	/** The strata the world could still take: cap less the figure. */
	public static float room(MarketAPI market) {
		return market == null ? 0f : Math.max(0f, cap(market) - of(market));
	}

	/**
	 * Adds strata (takes them, negative) and steps the size with the figure. {@code what} names
	 * the cause for the log ("Grew", "Starved a level of", "Razed a stratum of", "Budded from",
	 * "Absorbed into"); {@code razerFactionId} the faction whose bombardment it was (null:
	 * hunger, or the hive's own spending). Returns the strata left, 0 once the hive is gone.
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
		reapplyIfMoved(market);
		return after;
	}

	/** The defence the strata are lives in SwarmNexus.apply: recomputed once the figure has moved a point since the industries last ran. */
	protected static void reapplyIfMoved(MarketAPI market) {
		if (market == null || !market.isInEconomy()) return;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float applied = mem.contains(APPLIED_KEY) ? mem.getFloat(APPLIED_KEY) : Float.NaN;
		float now = of(market);
		if (!Float.isNaN(applied) && Math.abs(now - applied) < 1f) return;
		mem.set(APPLIED_KEY, now);
		ThreatGroundFronts.reapply(market.getId());
	}

	/** The strata the vanilla size says, kept in step when something else resizes the hive (growColony, ThreatRazing.reduceSize): the floor on a size up, the ceiling on a size down. */
	public static void sizeChanged(MarketAPI market, int oldSize) {
		if (market == null || !market.getMemoryWithoutUpdate().contains(KEY)) return;
		int size = market.getSize();
		float strata = of(market);
		if (size > oldSize && strata < sizeAt(size)) {
			market.getMemoryWithoutUpdate().set(KEY, sizeAt(size));
		} else if (size < oldSize && strata >= sizeAt(size + 1)) {
			market.getMemoryWithoutUpdate().set(KEY, sizeAt(size + 1) - 1f);
		}
	}

	/**
	 * The size the strata say, stepped one at a time so each step's bookkeeping and notices run:
	 * up by ThreatColonyManager.growColony at each size's strata reached, down by
	 * ThreatRazing.reduceSize when the figure falls under the size's own. Strata bombed out from
	 * under a front may leave it holding all there is: the front wins.
	 */
	protected static void syncSize(MarketAPI market, String what, String razerFactionId) {
		int cap = ThreatColonyManager.maxColonySize(market);
		for (int guard = 0; guard < 12; guard++) {
			int size = market.getSize();
			int wants = sizeOf(of(market), cap);
			if (wants > size) {
				if (!ThreatColonyManager.growColony(market, cap)) break;
				continue;
			}
			if (wants < size) {
				ThreatRazing.reduceSize(market, what);
				if (market.getSize() >= size) break;
				announceShrink(market, size, razerFactionId, what);
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

	/** A size off: "Hive Starving" for hunger, "Hive Thins" for the hive's own spending, "Stratum Razed" under the razer's crest for a bombardment, "Stratum Bled" for a front's attrition ({@code what} starts "Bled"). */
	protected static void announceShrink(MarketAPI market, int before, String razerFactionId, String what) {
		if (razerFactionId == null) {
			boolean spent = what != null && (what.startsWith("Budded") || what.startsWith("Absorbed") || what.startsWith("Shifted"));
			ThreatColonyManager.announce(ThreatNotice.titled(spent ? "Hive Thins" : "Hive Starving").good()
					.line("%s has shrunk to size %s", ThreatNotice.market(market), "" + market.getSize()));
			return;
		}
		boolean bled = what != null && what.startsWith("Bled");
		String deed = bled ? "front has bled" : "bombardment has razed";
		FactionAPI razer = Global.getSector().getFaction(razerFactionId);
		ThreatNotice n = ThreatNotice.titled(bled ? "Stratum Bled" : "Stratum Razed").good().icon(razer);
		if (Factions.PLAYER.equals(razerFactionId)) {
			n.line("Your " + deed + " a stratum of %s.", ThreatNotice.market(market));
		} else if (razer != null) {
			n.line("%s's " + deed + " a stratum of %s.", ThreatNotice.faction(razer), ThreatNotice.market(market));
		} else {
			n.line("The " + deed + " a stratum of %s.", ThreatNotice.market(market));
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
