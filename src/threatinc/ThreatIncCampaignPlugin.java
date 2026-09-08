package threatinc;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;

/**
 * Picks this mod's dialogs for its own entities. Registered transient on
 * every load ({@link ThreatIncModPlugin}). Today: the player's outpost
 * station opens {@link ThreatOutpostDialog}; everything else falls through
 * to vanilla.
 */
public class ThreatIncCampaignPlugin extends BaseCampaignPlugin {

	public static final String ID = "threatinc_campaignPlugin";

	@Override
	public String getId() {
		return ID;
	}

	@Override
	public boolean isTransient() {
		return true;
	}

	@Override
	public PluginPick<InteractionDialogPlugin> pickInteractionDialogPlugin(SectorEntityToken target) {
		if (target == null) return null;
		ThreatOutposts.Outpost o = ThreatOutposts.byStockId(target.getId());
		if (o == null || !Global.getSector().getPlayerFaction().getId().equals(o.factionId)) return null;
		return new PluginPick<InteractionDialogPlugin>(new ThreatOutpostDialog(o),
				PickPriority.MOD_SPECIFIC);
	}
}
