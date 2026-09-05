package threatinc;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.util.Misc;

/**
 * The station commander's HAND-OVER (docs/player-aid.md section 4): docked at
 * a colony with an open request for goods, the player hands over what they
 * carry. What is handed over leaves the fleet's cargo, lands exactly as a
 * convoy does (reserve deposit and trade modifier), counts toward the
 * contract and earns the same standing. The player's own holds are the only
 * gate on this route.
 *
 * <p>Wired from rules.csv: the option appears on the market's main menu while
 * the market memory carries {@link ThreatAidMissionIntel#MEM_REQUEST}.
 * Commands: {@code menu}, {@code deliver <commodityId>}, {@code back}.
 */
public class ThreatincAidCMD extends BaseCommandPlugin {

	public static final String OPTION_MENU = "threatincAidDeliver";
	public static final String OPTION_HAND = "threatincAidHand_";
	public static final String OPTION_BACK = "threatincAidBack";

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
			Map<String, MemoryAPI> memoryMap) {
		if (dialog == null || params == null || params.isEmpty()) return false;
		String command = params.get(0).getString(memoryMap);
		MarketAPI market = dialog.getInteractionTarget() != null
				? dialog.getInteractionTarget().getMarket() : null;
		if ("back".equals(command)) {
			dialog.getOptionPanel().clearOptions();
			FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
			return true;
		}
		if (market == null) return false;
		if ("menu".equals(command)) {
			menu(dialog, market);
			return true;
		}
		if ("deliver".equals(command) && params.size() > 1) {
			deliver(dialog, market, params.get(1).getString(memoryMap));
			return true;
		}
		return false;
	}

	protected static float carried(CargoAPI cargo, String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return cargo.getMarines();
		if (Commodities.FUEL.equals(commodityId)) return cargo.getFuel();
		if (Commodities.SUPPLIES.equals(commodityId)) return cargo.getSupplies();
		return cargo.getCommodityQuantity(commodityId);
	}

	protected static void remove(CargoAPI cargo, String commodityId, int quantity) {
		if (Commodities.MARINES.equals(commodityId)) cargo.removeMarines(quantity);
		else if (Commodities.FUEL.equals(commodityId)) cargo.removeFuel(quantity);
		else if (Commodities.SUPPLIES.equals(commodityId)) cargo.removeSupplies(quantity);
		else cargo.removeCommodity(commodityId, quantity);
	}

	protected void menu(InteractionDialogAPI dialog, MarketAPI market) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		String faction = market.getFaction() != null ? market.getFaction().getDisplayName() : "port";
		boolean any = false;
		for (ThreatAidMissionIntel m : ThreatAidMissionIntel.openAt(market)) {
			if (m.getKind() != ThreatAidMissionIntel.KIND_AID || m.remaining() <= 0) continue;
			any = true;
			String c = m.getCommodityId();
			int have = (int) carried(cargo, c);
			int hand = Math.min(have, m.remaining());
			text.addPara("The " + faction + " asks for " + Misc.getWithDGS(m.remaining()) + " more "
					+ ThreatReserves.label(c) + " here" + (m.isAccepted() ? " under your contract"
					: "") + ". You carry " + Misc.getWithDGS(have) + ".");
			text.highlightInLastPara(Misc.getHighlightColor(), Misc.getWithDGS(m.remaining()),
					Misc.getWithDGS(have));
			options.addOption("Hand over " + Misc.getWithDGS(hand) + " " + ThreatReserves.label(c),
					OPTION_HAND + c);
			options.setEnabled(OPTION_HAND + c, hand > 0);
			if (hand <= 0) {
				options.setTooltip(OPTION_HAND + c, "You carry none.");
			}
		}
		if (!any) {
			text.addPara("The station commander has nothing outstanding to ask of you.");
		}
		options.addOption("Back", OPTION_BACK);
		options.setShortcut(OPTION_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
	}

	protected void deliver(InteractionDialogAPI dialog, MarketAPI market, String commodityId) {
		TextPanelAPI text = dialog.getTextPanel();
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		ThreatAidMissionIntel m = null;
		for (ThreatAidMissionIntel curr : ThreatAidMissionIntel.openAt(market)) {
			if (curr.getKind() == ThreatAidMissionIntel.KIND_AID
					&& commodityId.equals(curr.getCommodityId()) && curr.remaining() > 0) {
				m = curr;
				break;
			}
		}
		int have = (int) carried(cargo, commodityId);
		int qty = m != null ? Math.min(have, m.remaining()) : 0;
		if (qty <= 0) {
			text.addPara("Nothing changes hands.");
			menu(dialog, market);
			return;
		}
		remove(cargo, commodityId, qty);
		ThreatReserves.deposit(market.getId(), commodityId, qty);
		ThreatConvoys.landed(market, commodityId, qty);
		int marines = Commodities.MARINES.equals(commodityId) ? qty : 0;
		int armaments = Commodities.HAND_WEAPONS.equals(commodityId) ? qty : 0;
		int fuel = Commodities.FUEL.equals(commodityId) ? qty : 0;
		int supplies = Commodities.SUPPLIES.equals(commodityId) ? qty : 0;
		text.addPara("Lost: " + Misc.getWithDGS(qty) + " " + ThreatReserves.label(commodityId));
		text.highlightInLastPara(Misc.getNegativeHighlightColor(), Misc.getWithDGS(qty));
		ThreatAid.onDelivered(market, market.getFactionId(), marines, armaments, fuel, supplies,
				false, text);
		text.addPara("The station commander's people take delivery; the war depot logs it.");
		menu(dialog, market);
	}
}
