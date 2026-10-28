package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.LightIndustry;

/**
 * Vanilla's LightIndustry with the occupation's image (docs/occupation.md fix 4, 2026-10-27): a claimed
 * industry draws its dark-green image. No fields, XStream-aliased to vanilla's name and set as
 * the spec's plugin at load ({@link ThreatOccupation#registerPlugins}), so saves stay portable.
 */
public class OccupiedLightIndustry extends LightIndustry {

	@Override
	public String getCurrentImage() {
		return ThreatOccupation.image(this, super.getCurrentImage());
	}
	// CONSUMPTION (2026-10-28, docs/occupation.md 7): the card draws vanilla's build bar as the swarm's consumption
	@Override
	public boolean isBuilding() {
		return super.isBuilding() || ThreatOccupation.consuming(this);
	}
	/** Fix 1 (hw304): consumed is not building - vanilla clears a non-functional industry's supply, which emptied ThreatOccupation.producers and dropped the claims. */
	@Override
	public boolean isFunctional() {
		return !isDisrupted() && !super.isBuilding() && !super.isUpgrading();
	}
	/** Fix 2 (the user, 2026-10-28: "any way to drop the building text?"): the card writes its big Building label only when building and not upgrading, so a consumed industry reads as upgrading too - the bar and its text are ours either way. */
	@Override
	public boolean isUpgrading() {
		return super.isUpgrading() || (!super.isBuilding() && ThreatOccupation.consuming(this));
	}
	@Override
	public float getBuildOrUpgradeProgress() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeProgress(this);
		return super.getBuildOrUpgradeProgress();
	}
	@Override
	public String getBuildOrUpgradeProgressText() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeText(this);
		return super.getBuildOrUpgradeProgressText();
	}
	@Override
	public String getBuildOrUpgradeDaysText() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeDaysText(this);
		return super.getBuildOrUpgradeDaysText();
	}
}
