package threatinc;

import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.InstallableIndustryItemPlugin.InstallableItemDescriptionMode;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseInstallableItemEffect;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Installed-item effect for the (vanilla) Fragment Fabricator when it sits in
 * a Fabrication Core. The vanilla item ships with NO entry in
 * ItemEffectsRepo.ITEM_EFFECTS - it was never meant to be industry-installed -
 * and the industry tooltip fatals on the null effect the moment the colony UI
 * renders it ("Cannot invoke ... addItemDescription ... because effect is
 * null"). Registered by ThreatIncModPlugin.
 *
 * The mechanical teeth live elsewhere (ThreatincMarketCMD.bombardMenu checks
 * ThreatColonyManager.hasFragmentFabricator); this effect only has to exist,
 * describe itself, and stay out of the way.
 */
public class FragmentScreenEffect extends BaseInstallableItemEffect {

	public FragmentScreenEffect(String id) {
		super(id);
	}

	@Override
	public void apply(Industry industry) {
	}

	@Override
	public void unapply(Industry industry) {
	}

	@Override
	protected void addItemDescriptionImpl(Industry industry, TooltipMakerAPI text,
			SpecialItemData data, InstallableItemDescriptionMode mode, String pre, float pad) {
		text.addPara(pre + "Continuously extrudes a screen of point-defense fragments dense "
				+ "enough to unmake ordnance in the upper atmosphere: %s while the fabricator "
				+ "runs. It cannot be silenced from orbit - only a ground raid can tear it out.",
				pad, Misc.getNegativeHighlightColor(),
				"orbital bombardment of this colony is impossible");
	}
}
