package threatinc;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;

/**
 * The planetary shield as a military structure.
 *
 * <p>Vanilla's shield does nothing against a bombardment and instead multiplies
 * the garrison by three - backwards on both counts for a war fought as a siege,
 * where orbit grinds a world down over weeks and the marines are not what the
 * shield is there to protect. Here it is simply another structure the siege
 * suppresses - it wears on the theatre's own disruption clock, exactly as a
 * battery or a war-stratum does - except that what its condition buys is not
 * ground defence but <i>cover</i>: the fraction of incoming disruption that
 * never reaches anything else on the world. The ground-defence bonus is gone
 * with the plugin swap ({@link ThreatPlanetaryShield}, and the
 * {@code threatinc_shieldDefenseBonus} knob if you want it back).
 *
 * <p>So a bombardment against a shielded world spends itself twice over. The
 * shield stands in the open and takes every pass at full weight; everything
 * under it takes {@link #throughput} of what was aimed at it. An intact shield
 * turns most of a strike aside, and each pass buys less cover for the next.
 * Grind it to nothing and the world is bare.
 *
 * <p>The orbital floor applies to the shield like anything else, so a fleet in
 * orbit can never quite spend one: {@code fortificationOrbitFloor} of its cover
 * survives any siege. Land a front and the floor is gone - boots finish what
 * orbit started, here as everywhere else.
 *
 * <p><b>Useful Planetary Shield, if it is also loaded.</b> Nothing is overridden
 * and nothing needs to be. UPS gates its own mitigation on the shield having
 * been functional at its previous poll, so the first strike on an intact shield
 * gets its binary absorb and that same strike starts the shield's clock; from
 * then on UPS is inert and this class's proportional cover is the whole story.
 * The two mods do contest the {@code planetaryshield} industries.csv row,
 * though, and which one wins is load-order dependent - with UPS installed the
 * tooltip and the ground-defence rule may be its, not ours. Running one or the
 * other is the supported arrangement.
 *
 * @see ThreatGroundFronts#siegeSlice the orbital duel every bombardment runs through
 */
public class ThreatShield {

	private ThreatShield() {}

	/** The world's planetary shield, built and not still going up; null with none. */
	public static Industry get(MarketAPI market) {
		if (market == null) return null;
		Industry shield = market.getIndustry(Industries.PLANETARYSHIELD);
		if (shield == null) return null;
		if (shield.isBuilding() && !shield.isUpgrading()) return null;
		return shield;
	}

	/** Whether there is a shield to fight through here at all. */
	public static boolean present(MarketAPI market) {
		return ThreatIncConfig.shieldAbsorbEnabled() && get(market) != null;
	}

	/**
	 * 1 intact .. 0 spent, read off the shield's own disruption clock through
	 * the theatre's condition curve - the same curve every fortification wears
	 * on, orbital floor included.
	 */
	public static float integrity(MarketAPI market) {
		Industry shield = get(market);
		if (shield == null) return 0f;
		float cond = ThreatGroundFronts.Theatre.of(market).condition(market, shield);
		return Math.max(0f, Math.min(1f, cond));
	}

	/** The fraction of incoming disruption the shield turns aside: its cap times what is left of it. */
	public static float absorb(MarketAPI market) {
		if (!ThreatIncConfig.shieldAbsorbEnabled() || get(market) == null) return 0f;
		float max = Math.max(0f, Math.min(1f, ThreatIncConfig.shieldAbsorbMax()));
		return Math.max(0f, Math.min(1f, max * integrity(market)));
	}

	/** What a structure under the shield actually takes: the disruption aimed at it times this. */
	public static float throughput(MarketAPI market) {
		return Math.max(0f, 1f - absorb(market));
	}

	/**
	 * The shield's own share of one orbital slice. It has no cover of its own,
	 * so it takes the slice at full weight (times its soak rate), on the same
	 * cap and the same make-up-the-run-down rule as a fortification.
	 *
	 * @param restore the days the clock ran down since the last slice, or 0 for
	 *                an instantaneous pass
	 * @return whether anything was written
	 */
	public static boolean soak(MarketAPI market, float add, float restore, float cap) {
		if (!present(market)) return false;
		Industry shield = get(market);
		if (shield == null || add <= 0f) return false;
		float rate = Math.max(0f, ThreatIncConfig.shieldSoakMult());
		if (rate <= 0f) return false;
		float cur = ThreatGroundFronts.siegeDisruptDays(shield);
		if (cur >= cap) return false;
		float made = cur > 0f ? restore : 0f;
		shield.setDisrupted(Math.min(cap, cur + made + add * rate));
		return true;
	}

	/**
	 * The shield's share of a raise-to pass - a saturation strike, a danger-close
	 * barrage - raised to the full figure it would have taken, never shortened.
	 *
	 * @return whether anything was written
	 */
	public static boolean soakTo(MarketAPI market, float dur) {
		if (!present(market)) return false;
		Industry shield = get(market);
		if (shield == null || dur <= 0f) return false;
		float rate = Math.max(0f, ThreatIncConfig.shieldSoakMult());
		if (rate <= 0f) return false;
		shield.setDisrupted(Math.max(ThreatGroundFronts.siegeDisruptDays(shield), dur * rate));
		return true;
	}

	/** One line of what is true now: "Planetary shield at 60% effect: absorbs 45% of incoming disruption." */
	public static String line(MarketAPI market) {
		if (!present(market)) return "";
		return "Planetary shield at " + Math.round(integrity(market) * 100f)
				+ "% effect: absorbs " + Math.round(absorb(market) * 100f)
				+ "% of incoming disruption.";
	}
}
