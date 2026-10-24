package threatinc;

import java.util.Map;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.CampaignUIAPI.CoreUITradeMode;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.listeners.CoreUITabListener;
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

	/** The colony screen open over the board, or null. */
	protected static ThreatColonyScreenDialog open = null;
	/** Set when the player asked for Intel from inside the colony screen. */
	protected static boolean closeAsked = false;

	protected final SectorEntityToken entity;
	protected InteractionDialogAPI dialog;

	public ThreatColonyScreenDialog(SectorEntityToken entity) {
		this.entity = entity;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		final InteractionDialogAPI d = dialog;
		open = this;
		closeAsked = false;
		dialog.getVisualPanel().showCore(CoreUITabId.CARGO, entity, CoreUITradeMode.NONE,
				new CoreInteractionListener() {
			public void coreUIDismissed() {
				if (open == ThreatColonyScreenDialog.this) open = null;
				closeAsked = false;
				d.dismiss();
			}
		});
	}

	/** Closes the colony screen back to the board when the player asked for Intel inside it. */
	protected static void closeIfAsked() {
		if (!closeAsked || open == null || open.dialog == null) return;
		closeAsked = false;
		open.dialog.getVisualPanel().closeCoreUI();
	}

	/**
	 * E inside the colony screen opened a second Intel screen inside it, over
	 * the board's, and each colony click from there nested another (the user,
	 * 2026-10-24); the board is already underneath, so close back to it.
	 */
	public static class Guard implements EveryFrameScript, CoreUITabListener {
		/** Made on every load: nothing of a previous game's screen carries over. */
		public Guard() {
			open = null;
			closeAsked = false;
		}

		public void reportAboutToOpenCoreTab(CoreUITabId tab, Object param) {
			if (open != null && tab == CoreUITabId.INTEL) closeAsked = true;
		}

		public void advance(float amount) {
			closeIfAsked();
		}

		public boolean isDone() {
			return false;
		}

		public boolean runWhilePaused() {
			return true;
		}
	}

	@Override
	public void optionSelected(String optionText, Object optionData) {
	}

	@Override
	public void optionMousedOver(String optionText, Object optionData) {
	}

	@Override
	public void advance(float amount) {
		closeIfAsked();
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
