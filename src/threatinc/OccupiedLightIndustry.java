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
}
