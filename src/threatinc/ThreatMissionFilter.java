package threatinc;

import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.GenericMissionManager;
import com.fs.starfarer.api.impl.campaign.intel.GenericMissionManager.GenericMissionCreator;

/**
 * Hive worlds post no vanilla missions (2026-09-24). Vanilla's generic
 * missions - survey, analyze, procurement - pick their issuer from every
 * market that is not hidden, and a hive market cannot be hidden without
 * leaving the economy. So every creator in {@link GenericMissionManager} is
 * wrapped: a mission whose issuer is the Threat is ended on creation, which
 * the manager already discards (a done intel counts as "no mission") before
 * the player can ever receive it. Frequency weights are passed through.
 */
public class ThreatMissionFilter implements GenericMissionCreator {

	protected GenericMissionCreator delegate;

	public ThreatMissionFilter(GenericMissionCreator delegate) {
		this.delegate = delegate;
	}

	/** Wraps every creator not yet wrapped. Idempotent; run on every load. */
	public static void install() {
		GenericMissionManager manager = GenericMissionManager.getInstance();
		if (manager == null) return;
		List<GenericMissionCreator> creators = manager.getCreators();
		for (int i = 0; i < creators.size(); i++) {
			GenericMissionCreator c = creators.get(i);
			if (c == null || c instanceof ThreatMissionFilter) continue;
			creators.set(i, new ThreatMissionFilter(c));
		}
	}

	@Override
	public float getMissionFrequencyWeight() {
		return delegate.getMissionFrequencyWeight();
	}

	@Override
	public EveryFrameScript createMissionIntel() {
		EveryFrameScript intel = delegate.createMissionIntel();
		if (intel instanceof BaseIntelPlugin && postedByThreat((BaseIntelPlugin) intel)) {
			((BaseIntelPlugin) intel).endImmediately();
			ThreatIncConfig.log("Vanilla mission from a hive world dropped: "
					+ intel.getClass().getSimpleName());
		}
		return intel;
	}

	protected static boolean postedByThreat(BaseIntelPlugin intel) {
		SectorEntityToken at = intel.getPostingLocation();
		MarketAPI market = at != null ? at.getMarket() : null;
		if (market != null && Factions.THREAT.equals(market.getFactionId())) return true;
		FactionAPI faction = intel.getFactionForUIColors();
		return faction != null && Factions.THREAT.equals(faction.getId());
	}
}
