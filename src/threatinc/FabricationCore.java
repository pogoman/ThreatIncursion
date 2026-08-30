package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;

/**
 * The population-as-machinery organ of a Threat colony: supplies heavy
 * machinery equal to colony size, covering the largest vanilla machinery
 * demand at that size (size-2, from refining/fuel production) with surplus
 * that feeds sibling colonies through in-group trade. This is what makes the
 * hive's input chain acyclic (population -> machinery -> mining -> ore ->
 * metals -> hulls), which the growth gate in ThreatColonyManager relies on.
 *
 * A real industry rather than an external supply mod because supply must be
 * declared inside apply() to survive the economy's per-recompute wipe
 * (BaseIndustry.updateSupplyAndDemandModifiers unmodifies the stats before
 * every pass; a script-injected modifier loses that race). Being a real
 * structure also gives it the vanilla surface: visible in the colony UI, and
 * disruptable by raids like any other structure - a disrupted core supplies
 * nothing, so knocking it out machinery-starves the whole hive chain.
 */
public class FabricationCore extends BaseIndustry {

	@Override
	public void apply() {
		super.apply(true);
		supply(Commodities.HEAVY_MACHINERY, market.getSize());
		if (!isFunctional()) {
			supply.clear();
		}
	}

	@Override
	public void unapply() {
		super.unapply();
	}

	/**
	 * The Fragment Fabricator installed here is the single most consequential
	 * theft in the sector - it is what makes the colony unbombardable (see
	 * ThreatincMarketCMD.bombardMenu) - and it lives in the fabrication organ
	 * because that is what it IS: the strata that extrude the fragment screen.
	 * The hive defends its heart accordingly: EXTREME raid danger, a tier
	 * beyond any nanoforge.
	 */
	@Override
	public com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidDangerLevel
			adjustItemDangerLevel(String itemId, String data,
			com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidDangerLevel level) {
		if (com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR.equals(itemId)) {
			return com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidDangerLevel.EXTREME;
		}
		return super.adjustItemDangerLevel(itemId, data, level);
	}

	// hive-only organ: never offered in the player's construction picker
	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}
}
