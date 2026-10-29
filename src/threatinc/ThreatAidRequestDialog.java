package threatinc;

import java.awt.Color;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE CALL-FOR-AID PROMPT (the user, 2026-10-29 evening: "When transfer or deploy are clicked and
 * there is not enough of a resource/FP to meet the demanded amount, a prompt should show asking if
 * the player wants to send a request for aid for the selected source types"): opened over the war
 * board (IntelUIAPI.showDialog, as the colony screen is) after a Transfer or Deploy whose load at
 * the levers is under what the colony needs - the shortfall, one line a good, then the question:
 * call the sector for it (ThreatAidRequests.request: Max asks all a colony of each faction spares,
 * Med half; a Deploy's shortfall asks a relief task force) or not now. The board is redrawn behind
 * it either way (ThreatIncursionIntel.refreshBoard).
 */
public class ThreatAidRequestDialog implements InteractionDialogPlugin {

	protected static final String YES = "threatinc_aid_request_yes";
	protected static final String NO = "threatinc_aid_request_no";

	protected final MarketAPI market;
	protected final List<ThreatAidRequests.Part> shorts;
	protected final Runnable after;
	protected InteractionDialogAPI dialog;

	/** {@code after} runs once the player has answered (the board's redraw); may be null. */
	public ThreatAidRequestDialog(MarketAPI market, List<ThreatAidRequests.Part> shorts, Runnable after) {
		this.market = market;
		this.shorts = shorts;
		this.after = after;
	}

	@Override
	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		TextPanelAPI text = dialog.getTextPanel();
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		text.addPara(market.getName() + " is short of %s.", h, ThreatAidRequests.shortText(shorts));
		String why = ThreatAidRequests.requestOffReason(market);
		if (why != null) {
			text.addPara(why, gray);
		} else {
			text.addPara("A request asks every faction at Favourable or better for %s. Each looks at it daily and "
					+ "sends what it has; standing with the sender drops by a whole number of points when it lands. "
					+ "It stands until answered or withdrawn from the war board.", h, ThreatAidRequests.askText(shorts));
			for (com.fs.starfarer.api.campaign.FactionAPI helper : ThreatAidRequests.candidates()) {
				text.addPara(ThreatAidRequests.standLine(helper), gray);
			}
		}
		dialog.getOptionPanel().clearOptions();
		if (why == null) dialog.getOptionPanel().addOption("Call for aid", YES);
		dialog.getOptionPanel().addOption("Not now", NO);
		dialog.setOptionOnEscape("Not now", NO);
	}

	@Override
	public void optionSelected(String optionText, Object optionData) {
		if (YES.equals(optionData)) ThreatAidRequests.request(market, shorts);
		dialog.dismiss();
		if (after != null) after.run();
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
