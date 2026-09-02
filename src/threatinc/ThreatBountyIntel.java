package threatinc;

import java.util.LinkedHashSet;
import java.util.Set;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Legacy save-compatibility stub. The standing-bounty system this class
 * implemented was replaced by accept-to-start contracts
 * ({@link ThreatMissionIntel}). Saves written before the rework still carry
 * instances of this class; keeping the type and its fields lets them
 * deserialize, and the first advance retires each one silently. The defense
 * boards then re-issue their objectives as ordinary missions on the next tick.
 *
 * Do not extend or post this. Delete once no supported save predates the rework.
 */
public class ThreatBountyIntel extends BaseIntelPlugin {

	// field set of the retired class, kept only so old saves deserialize
	protected int type;
	protected String marketId;
	protected int reward;
	protected long postedTimestamp;
	protected String outcome;
	protected String supersededBy;
	protected int tier;

	@Override
	protected void advanceImpl(float amount) {
		if (!isEnding() && !isEnded()) endImmediately();
	}

	@Override
	public boolean isHidden() {
		return true;
	}

	@Override
	public String getName() {
		return "Threat bounty (retired)";
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		info.addPara(getName(), Misc.getGrayColor(), 0f);
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		return tags;
	}
}
