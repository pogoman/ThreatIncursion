package threatinc;

import java.util.Map;

import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

/**
 * Opens vanilla's own colony screen for a hive world from the war board, and
 * closes again the moment the player leaves it. Vanilla's "View colony info"
 * dialog option is nothing more than the core Cargo tab opened on the planet
 * in trade mode NONE ({@code MakeOptionOpenCore ... CARGO} in rules.csv), so
 * this does exactly that through {@code IntelUIAPI.showDialog}: industries,
 * commodities with their full breakdown, accessibility, the lot - with no
 * custom UI of ours to maintain. The dialog itself never shows an option
 * panel: init hands straight over to the core screen and the core listener
 * dismisses the dialog on the way out.
 */
public class ThreatColonyScreenDialog implements InteractionDialogPlugin {

	protected final SectorEntityToken entity;
	protected InteractionDialogAPI dialog;

	public ThreatColonyScreenDialog(SectorEntityToken entity) {
		this.entity = entity;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		final InteractionDialogAPI d = dialog;
		dialog.getVisualPanel().showCore(CoreUITabId.CARGO, entity, CoreUITradeMode.NONE,
				new CoreInteractionListener() {
			public void coreUIDismissed() {
				d.dismiss();
			}
		});
	}

	@Override
	public void optionSelected(String optionText, Object optionData) {
	}

	@Override
	public void optionMousedOver(String optionText, Object optionData) {
	}

	@Override
	public void advance(float amount) {
	}

	@Override
	public void backFromEngagement(EngagementResultAPI battleResult) {
	}

	@Override
	public Object getContext() {
		return null;
	}

	@Override
	public Map<String, MemoryAPI> getMemoryMap() {
		return null;
	}
}
