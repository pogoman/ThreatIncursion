package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * RAZED: the colony screen's face of the razing bar
 * (docs/suppression-balance.md v2 section 4, {@link ThreatRazing}). On any
 * colony saturation has started on and not finished - the dead stay dead, so
 * it stays until the level falls or the colony does
 * ({@link ThreatRazing#syncCondition}). One line per fact: the level the bombs
 * are on and how far it has gone, and the fuel that would end the colony.
 */
public class ThreatRazedCondition extends BaseMarketConditionPlugin {

	public static final String ID = "threatinc_razed";

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		if (ThreatRazing.razeable(market) <= 0) {
			tooltip.addPara("%s cannot be razed any further.", 10f, h, market.getName());
			return;
		}
		int level = ThreatRazing.enemyLayers(market);
		tooltip.addPara("Level %s: %s of %s fuel.", 10f, neg, "" + level,
				Misc.getWithDGS(Math.round(ThreatRazing.progress(market))),
				Misc.getWithDGS(Math.round(ThreatRazing.levelFuel(level))));
		tooltip.addPara("Razed with %s more fuel.", 3f, h,
				Misc.getWithDGS(Math.round(ThreatRazing.fuelToDestroyThrough(market))));
		if (ThreatRazing.saturated(market)) {
			tooltip.addPara("Under saturation: no growth.", 3f, neg);
		}
	}
}
