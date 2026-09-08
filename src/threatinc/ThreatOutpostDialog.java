package threatinc;

import java.util.Map;

import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * THE PLAYER'S OUTPOST, docked at (decided 2026-09-06, after the user found
 * the station on the board's fleets table with a Recall that scuttled it and
 * no way to see what it held). Picked for the station entity by
 * {@link ThreatIncCampaignPlugin}. Three options, one line of fact:
 *
 * <ul>
 * <li>STORAGE - vanilla's cargo screen on the station's storage market
 * ({@link ThreatOutposts#ensureStorage}), the same cargo the board's
 * outpost row and every convoy and front run read. Take the marines, leave
 * the fuel: it is the resource stockpile a colony has, without the colony.
 * <li>DECOMMISSION - scuttles the station after a prompt that says what is
 * lost with it. This is what the fleets-table Recall used to do silently.
 * <li>LEAVE.
 * </ul>
 */
public class ThreatOutpostDialog implements InteractionDialogPlugin {

	public static final String OPT_STORAGE = "threatinc_outpost_storage";
	public static final String OPT_DECOMMISSION = "threatinc_outpost_decommission";
	public static final String OPT_CONFIRM = "threatinc_outpost_confirm";
	public static final String OPT_BACK = "threatinc_outpost_back";
	public static final String OPT_LEAVE = "threatinc_outpost_leave";

	protected final ThreatOutposts.Outpost outpost;
	protected InteractionDialogAPI dialog;

	public ThreatOutpostDialog(ThreatOutposts.Outpost outpost) {
		this.outpost = outpost;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		if (outpost.fleet != null) {
			dialog.getVisualPanel().showFleetInfo(outpost.entity != null ? outpost.entity.getName()
					: "Outpost", outpost.fleet, null, null);
		}
		showMain();
	}

	protected String name() {
		return outpost.entity != null ? outpost.entity.getName() : outpost.planetName() + " Outpost";
	}

	protected String storageText() {
		return ThreatFactionView.cargoText(ThreatOutposts.stock(outpost, Commodities.MARINES),
				ThreatOutposts.stock(outpost, Commodities.HAND_WEAPONS),
				ThreatOutposts.stock(outpost, Commodities.FUEL),
				ThreatOutposts.stock(outpost, Commodities.SUPPLIES));
	}

	protected void showMain() {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();
		if (!outpost.alive()) {
			text.addPara("The station is gone.");
			options.addOption("Leave", OPT_LEAVE);
			dialog.setOptionOnEscape("Leave", OPT_LEAVE);
			return;
		}
		String station = outpost.specId != null ? outpost.specId.replace('_', ' ') : "station";
		text.addPara(name() + " - your " + station + " over " + outpost.planetName()
				+ ". Storage: " + storageText() + ".");
		text.highlightInLastPara(Misc.getHighlightColor(), storageText());
		if (ThreatOutposts.storeMarket(outpost) != null) {
			options.addOption("Open the storage", OPT_STORAGE);
			options.setShortcut(OPT_STORAGE, org.lwjgl.input.Keyboard.KEY_I, false, false, false, true);
		}
		options.addOption("Decommission the outpost", OPT_DECOMMISSION);
		options.addOption("Leave", OPT_LEAVE);
		dialog.setOptionOnEscape("Leave", OPT_LEAVE);
	}

	protected void showDecommission() {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();
		text.addPara("Scuttle the station over " + outpost.planetName() + "? The swarm can seed "
				+ "the world again. Lost with it: " + storageText() + ".");
		text.highlightInLastPara(Misc.getNegativeHighlightColor(), storageText());
		options.addOption("Confirm - scuttle it", OPT_CONFIRM);
		options.addOption("Back", OPT_BACK);
		dialog.setOptionOnEscape("Back", OPT_BACK);
	}

	@Override
	public void optionSelected(String optionText, Object optionData) {
		if (optionText != null) dialog.getTextPanel().addPara(optionText, Misc.getButtonTextColor());
		if (OPT_LEAVE.equals(optionData)) {
			dialog.dismiss();
		} else if (OPT_STORAGE.equals(optionData) && outpost.entity != null) {
			// the same recipe as vanilla's "Inspect the cargo storage areas"
			dialog.getVisualPanel().showCore(CoreUITabId.CARGO, outpost.entity, CoreUITradeMode.OPEN,
					new CoreInteractionListener() {
				public void coreUIDismissed() {
					showMain();
				}
			});
		} else if (OPT_DECOMMISSION.equals(optionData)) {
			showDecommission();
		} else if (OPT_BACK.equals(optionData)) {
			showMain();
		} else if (OPT_CONFIRM.equals(optionData)) {
			ThreatOutposts.remove(outpost, "decommissioned by order");
			dialog.getTextPanel().addPara("The station is scuttled.", Misc.getNegativeHighlightColor());
			OptionPanelAPI options = dialog.getOptionPanel();
			options.clearOptions();
			options.addOption("Leave", OPT_LEAVE);
			dialog.setOptionOnEscape("Leave", OPT_LEAVE);
		}
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
