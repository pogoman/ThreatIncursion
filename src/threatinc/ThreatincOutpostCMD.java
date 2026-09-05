package threatinc;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.util.Misc;

/**
 * BUILD AN OUTPOST from the planet dialog (docs/strategy-layer.md, outposts):
 * the player in orbit of any uncolonised world can raise their faction's
 * station over it for outpostCredits, provided one of their colonies with a
 * military structure is within expedition reach. The war board's own Outpost
 * button stays the shortlist for purged worlds; this is the route to every
 * other planet, decided 2026-09-05.
 *
 * <p>Wired from rules.csv. Commands: {@code canBuild} (a condition: whether
 * the option is offered here), {@code standing} (a condition: the player's
 * outpost already holds the orbit), {@code menu} (the brief with Confirm and
 * Back), {@code build}, {@code back}.
 */
public class ThreatincOutpostCMD extends BaseCommandPlugin {

	public static final String OPTION_MENU = "threatincOutpostBuild";
	public static final String OPTION_CONFIRM = "threatincOutpostConfirm";
	public static final String OPTION_BACK = "threatincOutpostBack";

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
			Map<String, MemoryAPI> memoryMap) {
		if (dialog == null || params == null || params.isEmpty()) return false;
		String command = params.get(0).getString(memoryMap);
		PlanetAPI planet = planetOf(dialog);
		if ("canBuild".equals(command)) return canBuild(planet);
		if ("standing".equals(command)) return standing(planet);
		if ("back".equals(command)) {
			dialog.getOptionPanel().clearOptions();
			FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
			return true;
		}
		if (planet == null) return false;
		if ("menu".equals(command)) {
			menu(dialog, planet);
			return true;
		}
		if ("build".equals(command)) {
			build(dialog, planet, memoryMap);
			return true;
		}
		return false;
	}

	protected static PlanetAPI planetOf(InteractionDialogAPI dialog) {
		SectorEntityToken target = dialog.getInteractionTarget();
		return target instanceof PlanetAPI ? (PlanetAPI) target : null;
	}

	/** Whether the option is offered: outposts on, the world open, a base of the player's in reach. */
	public static boolean canBuild(PlanetAPI planet) {
		if (planet == null || !ThreatWarState.enabled() || !ThreatIncConfig.outpostsEnabled()) return false;
		if (!ThreatOutposts.eligible(planet)) return false;
		return ThreatOutposts.payingBase(Global.getSector().getPlayerFaction(), planet) != null;
	}

	/** Whether the player's own outpost already holds this orbit. */
	public static boolean standing(PlanetAPI planet) {
		if (planet == null) return false;
		ThreatOutposts.Outpost o = ThreatOutposts.outpostAt(planet.getId());
		return o != null && o.alive() && Global.getSector().getPlayerFaction().getId().equals(o.factionId);
	}

	protected void menu(InteractionDialogAPI dialog, PlanetAPI planet) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();
		FactionAPI player = Global.getSector().getPlayerFaction();
		MarketAPI base = ThreatOutposts.payingBase(player, planet);
		String station = ThreatOutposts.specIdFor(player).replace('_', ' ');
		int cost = (int) ThreatIncConfig.outpostCredits();
		float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
		text.addPara("Your engineers can raise a " + station + " over " + planet.getName() + " for "
				+ Misc.getDGSCredits(cost) + ", supported from " + (base != null ? base.getName()
				: "a colony of yours") + ". No market, no economy - a fortress. While it stands "
				+ "the swarm cannot seed this world; it has to destroy the station first. You have "
				+ Misc.getDGSCredits((int) credits) + ".");
		text.highlightInLastPara(Misc.getHighlightColor(), Misc.getDGSCredits(cost),
				Misc.getDGSCredits((int) credits));
		options.addOption("Confirm - build the outpost", OPTION_CONFIRM);
		if (credits < cost) {
			options.setEnabled(OPTION_CONFIRM, false);
			options.setTooltip(OPTION_CONFIRM, "You cannot afford it.");
		} else if (base == null) {
			options.setEnabled(OPTION_CONFIRM, false);
			options.setTooltip(OPTION_CONFIRM, "None of your colonies with a military structure is in "
					+ "reach of this world.");
		}
		options.addOption("Back", OPTION_BACK);
		options.setShortcut(OPTION_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
	}

	protected void build(InteractionDialogAPI dialog, PlanetAPI planet, Map<String, MemoryAPI> memoryMap) {
		TextPanelAPI text = dialog.getTextPanel();
		int cost = (int) ThreatIncConfig.outpostCredits();
		ThreatOutposts.Outpost o = ThreatOutposts.build(Global.getSector().getPlayerFaction(), planet);
		if (o == null) {
			text.addPara("The station cannot be raised here after all.");
		} else {
			text.addPara("Lost: " + Misc.getDGSCredits(cost));
			text.highlightInLastPara(Misc.getNegativeHighlightColor(), Misc.getDGSCredits(cost));
			text.addPara("The " + ThreatOutposts.specIdFor(Global.getSector().getPlayerFaction())
					.replace('_', ' ') + " takes up its orbit over " + planet.getName() + ".");
		}
		dialog.getOptionPanel().clearOptions();
		FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
	}
}
