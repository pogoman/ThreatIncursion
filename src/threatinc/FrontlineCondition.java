package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * FRONTLINE OUTPOST (docs/frontlines.md): the colony screen's face of a
 * {@link ThreatFrontlines} link. While the link is connected to a colony
 * through its chain it carries the Forward Relay accessibility bonus, which
 * vanilla lists in the accessibility tooltip; cut off, it does not.
 */
public class FrontlineCondition extends BaseMarketConditionPlugin {

	protected boolean relayed() {
		return market != null && market.getMemoryWithoutUpdate().getBoolean(ThreatFrontlines.RELAY_FLAG);
	}

	@Override
	public void apply(String id) {
		super.apply(id);
		float bonus = ThreatIncConfig.frontlineRelayAccess();
		if (relayed() && bonus > 0f) {
			market.getAccessibilityMod().modifyFlat(id, bonus, "Forward relay");
		} else {
			market.getAccessibilityMod().unmodifyFlat(id);
		}
	}

	@Override
	public void unapply(String id) {
		super.unapply(id);
		market.getAccessibilityMod().unmodifyFlat(id);
	}

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		float opad = 10f;
		int pct = Math.round(ThreatIncConfig.frontlineRelayAccess() * 100f);
		if (relayed()) {
			tooltip.addPara("Forward relay: %s accessibility.", opad, Misc.getHighlightColor(),
					"+" + pct + "%");
		} else {
			tooltip.addPara("Cut off from the chain: no forward relay.", opad,
					Misc.getNegativeHighlightColor());
		}
	}
}
