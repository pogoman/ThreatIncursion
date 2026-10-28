package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses;

/**
 * Vanilla's GroundDefenses (Ground Defenses and Heavy Batteries) with the occupation's consumption bar (docs/occupation.md 7,
 * 2026-10-28: the military structures are consumed last). No fields, XStream-aliased to vanilla's
 * name and set as the spec's plugin at load ({@link ThreatOccupation#registerPlugins}), so saves stay portable.
 */
public class OccupiedGroundDefenses extends GroundDefenses {
	@Override
	public String getCurrentImage() {
		return ThreatOccupation.image(this, super.getCurrentImage());
	}
	// CONSUMPTION (2026-10-28, docs/occupation.md 7): the card draws vanilla's build bar as the swarm's consumption
	@Override
	public boolean isBuilding() {
		return super.isBuilding() || ThreatOccupation.consuming(this);
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
