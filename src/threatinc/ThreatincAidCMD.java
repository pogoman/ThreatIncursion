package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
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
 * The station commander's HAND-OVER (docs/war-effort-rework.md section 5): docked at a
 * mobilised faction's colony, the player sees EVERY need the colony has ({@link ThreatNeeds},
 * the suspended ones included - their own fleet fought its way in) and hands over what they
 * carry toward one: an option per tier the hold covers, plus "all you carry" when that is more
 * than Max or less than Min. What is handed over leaves the fleet's cargo, lands exactly as a
 * convoy does (reserve deposit), and earns standing weighted by the purpose
 * ({@link ThreatAid#onDelivered}). Ship hulls are never in a hold; fleets are the board's.
 *
 * <p>Wired from rules.csv: the option appears on the market's main menu, and in the station or
 * base commander's comm-directory conversation, while the market memory carries
 * {@link ThreatAidMissionIntel#MEM_REQUEST} (every mobilised NPC colony, ThreatNeeds.tick).
 * Back re-fires PopulateOptions without touching $menuState, so it returns to whichever menu
 * the player came from. Commands: {@code menu}, {@code deliver <commodityId> <tier>}, {@code back}.
 */
public class ThreatincAidCMD extends BaseCommandPlugin {

	public static final String OPTION_MENU = "threatincAidDeliver";
	public static final String OPTION_HAND = "threatincAidHand_";
	public static final String OPTION_BACK = "threatincAidBack";
	/** The "all you carry" tier. */
	public static final int TIER_ALL = 3;

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
		if ("deliver".equals(command) && params.size() > 2) {
			int tier = 0;
			try {
				tier = Integer.parseInt(params.get(2).getString(memoryMap));
			} catch (NumberFormatException e) {
				tier = 0;
			}
			deliver(dialog, market, params.get(1).getString(memoryMap), tier);
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

	/** The tier words of a purpose: what each of Min / Med / Max buys. */
	protected static String[] tierWords(ThreatNeeds.Need n) {
		if (ThreatNeeds.HOLD.equals(n.purpose)) return new String[] { "hold the line", "push back", "overrun" };
		if (ThreatNeeds.PUSH_BACK.equals(n.purpose)) return new String[] { "push back", "overrun", "and then some" };
		if (ThreatNeeds.ARM.equals(n.purpose)) return new String[] { "30 days", "60 days", "fill the depot" };
		if (ThreatNeeds.EXPEDITION.equals(n.purpose)) return new String[] { "to sail", "a margin", "the full voyage" };
		return new String[] { "the gap", "a margin", "fill the depot" };
	}

	/** The need's line at the dock: the situation, the three figures, what the player carries. */
	protected static String needLine(MarketAPI market, ThreatNeeds.Need n, int have) {
		String world = market.getName();
		String good = ThreatReserves.label(n.good);
		String goodCap = Character.toUpperCase(good.charAt(0)) + good.substring(1);
		String figures;
		String[] words = tierWords(n);
		if (ThreatNeeds.HOLD.equals(n.purpose) || ThreatNeeds.PUSH_BACK.equals(n.purpose)) {
			figures = n.shown(n.min) + " to " + words[0] + ", " + n.shown(n.med) + " to " + words[1] + ", "
					+ n.shown(n.max) + " to " + words[2];
		} else {
			figures = n.figures();
		}
		String lead;
		if (ThreatNeeds.HOLD.equals(n.purpose)) {
			lead = world + (n.overrun ? " is being overrun by " : " holds the line against ")
					+ Misc.getWithDGS(Math.round(n.troops)) + " Threat troops.";
		} else if (ThreatNeeds.PUSH_BACK.equals(n.purpose)) {
			lead = world + " has " + Misc.getWithDGS(Math.round(n.troops)) + " Threat troops stopped.";
		} else if (ThreatNeeds.ARM.equals(n.purpose)) {
			lead = world + "'s defenders burn " + String.format("%.1f", n.troops) + " heavy armaments a day.";
		} else if (ThreatNeeds.EXPEDITION.equals(n.purpose)) {
			lead = "The " + n.target + " expedition waits at " + world + " for " + good + ".";
		} else {
			lead = world + "'s depot is " + (n.exhausted ? "out of " : "short of ") + good + ".";
		}
		String s = lead + " " + goodCap + ": " + figures + ". You carry " + Misc.getWithDGS(have) + ".";
		if (n.suspended) s += " The swarm holds the orbit: nothing lands but what you bring yourself.";
		return s;
	}

	protected void menu(InteractionDialogAPI dialog, MarketAPI market) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		FactionAPI faction = market.getFaction();
		String factionName = faction != null ? ThreatWarState.displayName(faction.getId()) : "port";
		List<ThreatNeeds.Need> needs = ThreatNeeds.of(market);
		boolean any = false;
		List<String> seen = new ArrayList<String>();
		List<String> enabled = new ArrayList<String>();
		List<String[]> tips = new ArrayList<String[]>();
		for (ThreatNeeds.Need n : needs) {
			any = true;
			if (n.isFleets()) {
				text.addPara(market.getName() + (n.strike ? " is outweighed by a Threat strike in flight: "
						: "'s orbit is held by the swarm: ") + "it asks for warships, not freighters - "
						+ Misc.getWithDGS(Math.round(n.min)) + " fleet points to lift it. Defend on the war board.");
				text.highlightInLastPara(Misc.getHighlightColor(), Misc.getWithDGS(Math.round(n.min)));
				continue;
			}
			// ship hulls are never in a hold (vanilla's meta commodity): they sail from the player's hull port
			if (Commodities.SHIPS.equals(n.good)) {
				text.addPara("The " + factionName + " is short of " + n.shown(n.min) + " ship hulls to rebuild its navy. "
						+ "Ship hulls sail from your hull port by convoy: Aid on the war board.");
				text.highlightInLastPara(Misc.getHighlightColor(), n.shown(n.min));
				continue;
			}
			if (seen.contains(n.good)) continue; // one hand-over a good (an expedition short of both reads twice)
			seen.add(n.good);
			int have = (int) carried(cargo, n.good);
			text.addPara(needLine(market, n, have));
			text.highlightInLastPara(Misc.getHighlightColor(), n.shown(n.min), n.shown(n.med), n.shown(n.max),
					Misc.getWithDGS(have));
			String[] words = tierWords(n);
			float[] tiers = { n.min, n.med, n.max };
			int offered = 0;
			float last = -1f;
			for (int t = 0; t < 3; t++) {
				int qty = Math.round(tiers[t]);
				if (qty < 1 || qty > have || qty == last) continue;
				last = qty;
				options.addOption("Hand over " + Misc.getWithDGS(qty) + " " + ThreatReserves.label(n.good)
						+ " (" + words[t] + ")", OPTION_HAND + n.good + "_" + t);
				enabled.add(OPTION_HAND + n.good + "_" + t);
				offered++;
			}
			String allId = OPTION_HAND + n.good + "_" + TIER_ALL;
			if (have > 0 && (have > Math.round(n.max) || offered == 0)) {
				options.addOption("Hand over " + Misc.getWithDGS(have) + " " + ThreatReserves.label(n.good)
						+ " (all you carry)", allId);
				enabled.add(allId);
			} else if (have <= 0) {
				options.addOption("Hand over " + ThreatReserves.label(n.good), allId);
				tips.add(new String[] { allId, "You carry none." });
			}
		}
		if (!any) {
			text.addPara("The station commander has nothing to ask of you.");
		}
		options.addOption("Back", OPTION_BACK);
		// shape first, state last (CLAUDE.md): every setEnabled / setTooltip after the last addOption
		for (String[] tip : tips) {
			options.setEnabled(tip[0], false);
			options.setTooltip(tip[0], tip[1]);
		}
		options.setShortcut(OPTION_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
	}

	protected void deliver(InteractionDialogAPI dialog, MarketAPI market, String commodityId, int tier) {
		TextPanelAPI text = dialog.getTextPanel();
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		List<ThreatNeeds.Need> needs = ThreatNeeds.of(market);
		ThreatNeeds.Need need = ThreatNeeds.find(needs, null, commodityId);
		int have = (int) carried(cargo, commodityId);
		int qty = tier >= TIER_ALL || need == null ? have : Math.min(have, Math.round(need.tier(tier)));
		if (qty <= 0 || Commodities.SHIPS.equals(commodityId)) {
			text.addPara("Nothing changes hands.");
			menu(dialog, market);
			return;
		}
		int idx = ThreatAid.index(commodityId);
		float[] handed = new float[ThreatReserves.AID_COMMODITIES.length];
		if (idx >= 0) handed[idx] = qty;
		// the standing weight is read before the goods land: what they serve, and what is surplus
		float[] weights = ThreatNeeds.purposeWeights(market, handed);
		boolean overMax = need != null && qty > Math.round(need.max);
		remove(cargo, commodityId, qty);
		ThreatReserves.deposit(market.getId(), commodityId, qty);
		int marines = Commodities.MARINES.equals(commodityId) ? qty : 0;
		int armaments = Commodities.HAND_WEAPONS.equals(commodityId) ? qty : 0;
		int fuel = Commodities.FUEL.equals(commodityId) ? qty : 0;
		int supplies = Commodities.SUPPLIES.equals(commodityId) ? qty : 0;
		text.addPara("Lost: " + Misc.getWithDGS(qty) + " " + ThreatReserves.label(commodityId));
		text.highlightInLastPara(Misc.getNegativeHighlightColor(), Misc.getWithDGS(qty));
		text.addPara(ThreatComms.dockThanks(market.getFaction(), market, qty, commodityId, overMax));
		ThreatAid.onDelivered(market, market.getFactionId(), marines, armaments, fuel, supplies, 0,
				false, text, weights);
		menu(dialog, market);
	}
}
