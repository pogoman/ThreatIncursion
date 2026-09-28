package threatinc;

import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;

/**
 * A clicked {@link ThreatNotice} opens the war board on the notice's faction
 * tab (2026-09-27). Vanilla's INTEL_TAB click selects only an intel that is in
 * the intel manager, and a notice never is, so the notice's click is
 * INTERACTION_DIALOG on a loose token carrying the tab
 * ({@link ThreatIncCampaignPlugin} picks this dialog for it). Like
 * {@link ThreatColonyScreenDialog}, the dialog never shows: init hands over to
 * the core Intel screen with the board selected and dismisses on the way out.
 */
public class ThreatNoticeClickDialog implements InteractionDialogPlugin {

	/** Custom-data key on the click token: the board tab to open. */
	public static final String KEY = "threatinc_noticeBoardTab";

	protected final String factionId;

	public ThreatNoticeClickDialog(String factionId) {
		this.factionId = factionId;
	}

	/** A loose token for the notice's click; never added to a location. */
	public static SectorEntityToken token(String factionId) {
		SectorEntityToken token = Global.getSector().getHyperspace().createToken(0f, 0f);
		token.getCustomData().put(KEY, factionId);
		return token;
	}

	/** The tab a click token carries, or null when the token is not one. */
	public static String tabOf(SectorEntityToken token) {
		if (token == null) return null;
		Object tab = token.getCustomData().get(KEY);
		return tab instanceof String ? (String) tab : null;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		final InteractionDialogAPI d = dialog;
		// load, start and instant war register the board; none here, or a click
		// on a debug notice would register one with no war running. Nothing to
		// show when the war is over and the board with it, or while the board is
		// off the intel list (no hive found yet): vanilla drops a hidden intel
		// from the list, so the click would land on an empty Major events tab
		ThreatIncursionIntel board = ThreatIncursionIntel.get();
		if (board == null || board.isHidden()) {
			d.dismiss();
			return;
		}
		// a faction since stood down, or never on the board, falls back to the hive view
		String tab = ThreatFactionView.selectorIds().contains(factionId)
				? factionId : ThreatFactionView.VIEW_THREAT;
		board.setSelectedFactionId(tab);
		dialog.getVisualPanel().showCore(CoreUITabId.INTEL, Global.getSector().getPlayerFleet(), board,
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
