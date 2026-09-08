package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The Planetary Shield, replacing vanilla's plugin (swapped in through
 * data/campaign/industries.csv).
 *
 * <p>Vanilla's shield multiplies the garrison by three and does nothing at all
 * about bombardment. In this mod that is backwards on both counts: the shield's
 * whole job is to stand between orbit and the surface, and the marines under it
 * are not the thing it protects. So the ground-defence bonus is gone
 * ({@code threatinc_shieldDefenseBonus}, default 0) and what the shield buys
 * instead is <i>cover</i> - see {@link ThreatShield}, which owns that mechanic.
 *
 * <p>The bonus is a knob rather than a deletion, and if it is turned back on it
 * is applied in proportion to the shield's condition rather than vanilla's
 * all-or-nothing. That matters: vanilla writes the whole x3 while functional and
 * strips every bit of it the instant the shield is disrupted, so with the shield
 * a bombardment target - which it is here and is not in vanilla - one pass would
 * cut a shielded world's defence by two thirds and make it EASIER to besiege
 * than an unshielded one. Nothing in this mod is allowed an on/off cliff.
 *
 * <p><b>Do not add instance fields to this class.</b> {@code configureXStream}
 * in {@link ThreatIncModPlugin} aliases it to vanilla's class name so a save
 * never mentions {@code threatinc.ThreatPlanetaryShield} and stays loadable with
 * this mod removed. That holds only while the subclass carries no state vanilla
 * does not have. Anything that must persist belongs in market memory.
 */
public class ThreatPlanetaryShield extends PlanetaryShield {

	/** Vanilla's own multiplier, the one the knob is expressed in (x3 = a bonus of 2). */
	public static final float VANILLA_BONUS = 2f;

	/** How much the shield adds to the garrison at full condition; 0 by default - it shields the planet, not the marines. */
	protected float defenseBonus() {
		return Math.max(0f, ThreatIncConfig.shieldDefenseBonus());
	}

	protected boolean groundDefEnabled() {
		return defenseBonus() > 0f;
	}

	/**
	 * Vanilla keys disruption on the plugin's simple class name
	 * ({@code "$core_disrupted_" + getClass().getSimpleName()}), so swapping the
	 * class would otherwise move a shield's disruption clock to a new memory key
	 * and silently bring every disrupted shield back online. Pin it to vanilla's
	 * key so the state survives the swap - and survives this mod being removed.
	 */
	@Override
	public String getDisruptedKey() {
		return "$core_disrupted_" + PlanetaryShield.class.getSimpleName();
	}

	@Override
	public void apply() {
		super.apply();
		// vanilla has just written x3 (and the alpha-core / improvement mults).
		// Take them all back, then put ours on in proportion to what is left of
		// the shield - nothing at all at the default bonus of 0.
		com.fs.starfarer.api.combat.StatBonus defense =
				market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		defense.unmodifyMult(getModId());
		defense.unmodifyMult(getModId(1));
		defense.unmodifyMult(getModId(2));

		float bonus = defenseBonus();
		if (bonus <= 0f) return;

		float cond = ThreatShield.integrity(market);
		if (cond <= 0f) return;
		defense.modifyMult(getModId(), 1f + bonus * cond, getCurrentName()
				+ (isDisrupted() ? " (suppressed, " + Math.round(cond * 100f) + "% effect)" : ""));
	}

	@Override
	protected void applyAlphaCoreModifiers() {
		if (groundDefEnabled()) super.applyAlphaCoreModifiers();
	}

	@Override
	protected void applyImproveModifiers() {
		if (groundDefEnabled()) {
			super.applyImproveModifiers();
		} else {
			market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD)
					.unmodifyMult(getModId(2));
		}
	}

	@Override
	public boolean canImprove() {
		// with no ground-defence bonus there is nothing for an improvement to raise
		return groundDefEnabled();
	}

	@Override
	protected boolean hasPostDemandSection(boolean hasDemand, IndustryTooltipMode mode) {
		return true;
	}

	/** What is true now: what the shield is turning aside, and how much of it is left. */
	@Override
	protected void addPostDemandSection(TooltipMakerAPI tooltip, boolean hasDemand,
			IndustryTooltipMode mode) {
		if (groundDefEnabled()) super.addPostDemandSection(tooltip, hasDemand, mode);

		float opad = 10f;
		Color h = Misc.getHighlightColor();

		if (!ThreatIncConfig.shieldAbsorbEnabled()) {
			tooltip.addPara("Bombardment absorption is turned off in this game's settings.", opad);
			return;
		}

		int max = Math.round(Math.max(0f, Math.min(1f, ThreatIncConfig.shieldAbsorbMax())) * 100f);
		tooltip.addPara("Absorbs up to %s of the disruption a bombardment lands on everything "
				+ "else on this world, in proportion to the shield's own condition.",
				opad, h, max + "%");

		if (market != null && ThreatShield.present(market)) {
			int cond = Math.round(ThreatShield.integrity(market) * 100f);
			int absorb = Math.round(ThreatShield.absorb(market) * 100f);
			if (isDisrupted()) {
				tooltip.addPara("Condition %s - it is absorbing %s, and recovers as its "
						+ "disruption runs down.", opad, h, cond + "%", absorb + "%");
			} else {
				tooltip.addPara("Condition %s - it is absorbing %s.", opad, h,
						cond + "%", absorb + "%");
			}
		}

		tooltip.addPara("The shield takes every bombardment at full weight itself, and has no "
				+ "cover of its own. Orbit alone cannot spend it; troops on the ground can.",
				opad);
	}

	@Override
	protected void addAlphaCoreDescription(TooltipMakerAPI tooltip, AICoreDescriptionMode mode) {
		if (groundDefEnabled()) {
			super.addAlphaCoreDescription(tooltip, mode);
			return;
		}

		// vanilla's own text, less the ground-defence claim this shield does not make
		float opad = 10f;
		Color highlight = Misc.getHighlightColor();

		String pre = "Alpha-level AI core currently assigned. ";
		if (mode == AICoreDescriptionMode.MANAGE_CORE_DIALOG_LIST
				|| mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
			pre = "Alpha-level AI core. ";
		}

		if (mode == AICoreDescriptionMode.INDUSTRY_TOOLTIP) {
			CommoditySpecAPI coreSpec = Global.getSettings().getCommoditySpec(aiCoreId);
			TooltipMakerAPI text = tooltip.beginImageWithText(coreSpec.getIconName(), 48);
			text.addPara(pre + "Reduces upkeep cost by %s. Reduces demand by %s unit.", 0f,
					highlight, "" + (int) ((1f - UPKEEP_MULT) * 100f) + "%", "" + DEMAND_REDUCTION);
			tooltip.addImageWithText(opad);
			return;
		}

		tooltip.addPara(pre + "Reduces upkeep cost by %s. Reduces demand by %s unit.", opad,
				highlight, "" + (int) ((1f - UPKEEP_MULT) * 100f) + "%", "" + DEMAND_REDUCTION);
	}
}
