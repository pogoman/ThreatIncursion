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
 * {@link ThreatIncCampaignPlugin}. The options are vanilla's own for an
 * orbital storage (the user, 2026-10-31: "outposts can we just have same
 * options as vanilla"), plus Decommission:
 *
 * <ul>
 * <li>TRANSFER CARGO [I] - vanilla's cargo screen on the station's storage
 * market ({@link ThreatOutposts#ensureStorage}), the same cargo the board's
 * outpost row and every convoy and front run read.
 * <li>TRANSFER SHIPS [F] - vanilla's fleet screen on the same storage.
 * <li>USE THE LOCAL FACILITIES TO REFIT YOUR FLEET [R] - vanilla's refit
 * screen docked at the station, its storage a source for hulls and weapons.
 * <li>DECOMMISSION - scuttles the station after a prompt that says what is
 * lost with it. This is what the fleets-table Recall used to do silently.
 * <li>LEAVE.
 * </ul>
 */
public class ThreatOutpostDialog implements InteractionDialogPlugin {

	public static final String OPT_STORAGE = "threatinc_outpost_storage";
	public static final String OPT_SHIPS = "threatinc_outpost_ships";
	public static final String OPT_REFIT = "threatinc_outpost_refit";
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
			// vanilla's orbitalStorageInteraction options, wording and keys as rules.csv has them
			options.addOption("Transfer cargo", OPT_STORAGE);
			options.setShortcut(OPT_STORAGE, org.lwjgl.input.Keyboard.KEY_I, false, false, false, true);
			options.addOption("Transfer ships", OPT_SHIPS);
			options.setShortcut(OPT_SHIPS, org.lwjgl.input.Keyboard.KEY_F, false, false, false, true);
			options.addOption("Use the local facilities to refit your fleet", OPT_REFIT);
			options.setShortcut(OPT_REFIT, org.lwjgl.input.Keyboard.KEY_R, false, false, false, true);
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
		} else if (OPT_SHIPS.equals(optionData) && outpost.entity != null) {
			// vanilla's "Transfer ships": the fleet screen with the storage as the other side
			dialog.getVisualPanel().showCore(CoreUITabId.FLEET, outpost.entity, CoreUITradeMode.OPEN,
					new CoreInteractionListener() {
				public void coreUIDismissed() {
					showMain();
				}
			});
		} else if (OPT_REFIT.equals(optionData) && outpost.entity != null) {
			// the refit screen docked at the station: its storage is a source for hulls and weapons
			dialog.getVisualPanel().showCore(CoreUITabId.REFIT, outpost.entity, CoreUITradeMode.OPEN,
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
