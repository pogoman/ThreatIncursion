package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * BLOCKADED, on a hive world (docs/ground-war.md "Blockade"): human warships
 * hold its orbit. Display only - the cut is ThreatBlockade.syncHive's, taken
 * off the world's imports by ThreatColonyUpkeep and off vanilla's shipping by
 * ThreatColonyManager.applyPortDisruption.
 */
public class HiveBlockadeCondition extends BaseMarketConditionPlugin {

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		float cut = market.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY);
		Color h = Misc.getHighlightColor();
		tooltip.addPara("Imports and exports cut %s.", 10f, h, Math.round(cut * 100f) + "%");
	}
}
