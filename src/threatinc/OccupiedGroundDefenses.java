package threatinc;

import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses;

/**
 * Vanilla's GroundDefenses (Ground Defenses and Heavy Batteries) with the occupation's consumption bar (docs/occupation.md 7,
 * 2026-10-28: the military structures are consumed last). No fields, XStream-aliased to vanilla's
 * name and set as the spec's plugin at load ({@link ThreatOccupation#registerPlugins}), so saves stay portable.
 */
public class OccupiedGroundDefenses extends GroundDefenses implements OccupiedIndustry {
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
		// a rebuild from the eaten share runs, its effect scaled by its progress (ThreatOccupation.scaleEffect)
		return !isDisrupted() && !super.isUpgrading() && (!super.isBuilding() || ThreatOccupation.rebuilding(this));
	}
	/** THE EFFECT SCALES WITH WHAT STANDS (the user, 2026-10-28 evening): vanilla's apply, then its ground-defence multiplier x what stands of the structure. */
	@Override
	public void apply() {
		super.apply();
		ThreatOccupation.scaleEffect(this, Stats.GROUND_DEFENSES_MOD, getModId());
	}
	/** Fix 2 (the user, 2026-10-28: "any way to drop the building text?"): the card writes its big Building label only when building and not upgrading, so a consumed industry reads as upgrading too - the bar and its text are ours either way. */
	@Override
	public boolean isUpgrading() {
		return super.isUpgrading() || (!super.isBuilding() && ThreatOccupation.consuming(this))
				// a rebuild from the eaten share reads as upgrading too: "Rebuilding" on the bar, no Building label
				|| (super.isBuilding() && ThreatOccupation.rebuilding(this));
	}
	/** Fix 4: vanilla's own building flag, for ThreatOccupation.producers (never isBuilding, which would recurse). */
	public boolean underConstruction() {
		return super.isBuilding();
	}
	@Override
	public float getBuildOrUpgradeProgress() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeProgress(this);
		return super.getBuildOrUpgradeProgress();
	}
	@Override
	public String getBuildOrUpgradeProgressText() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeText(this);
		if (super.isBuilding() && !isDisrupted() && ThreatOccupation.rebuilding(this)) return ThreatOccupation.rebuildText(this);
		return super.getBuildOrUpgradeProgressText();
	}
	@Override
	public String getBuildOrUpgradeDaysText() {
		if (!super.isBuilding() && ThreatOccupation.consuming(this)) return ThreatOccupation.consumeDaysText(this);
		return super.getBuildOrUpgradeDaysText();
	}
	/** No "Cancel upgrade" on a held card (the user, 2026-10-29): the spec names no upgrade while consumed or rebuilding (ThreatOccupation.heldSpec). Transient: XStream skips it, saves stay portable. */
	private transient IndustrySpecAPI heldSpec;
	@Override
	public IndustrySpecAPI getSpec() {
		if (heldSpec == null) heldSpec = ThreatOccupation.heldSpec(this, super.getSpec());
		return heldSpec;
	}
	/** Refused while held: it would refund the upgrade and, rebuilding, stand the structure up whole. */
	@Override
	public void cancelUpgrade() {
		if (ThreatOccupation.held(this)) return;
		super.cancelUpgrade();
	}
}
