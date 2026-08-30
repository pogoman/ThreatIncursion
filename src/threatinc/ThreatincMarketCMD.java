package threatinc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;

/**
 * MarketCMD override that waives the saturation-bombardment atrocity penalty
 * when the bombed colony belongs to the Threat.
 *
 * <p>Vanilla {@code bombardSaturation} builds {@code temp.willBecomeHostile}
 * (and the "will make the following factions hostile" warning) from each
 * faction's {@code caresAboutAtrocities} flag, never checking who owned the
 * target; {@code bombardConfirm} then applies the reputation hit to that same
 * list. Exterminating the machine swarm therefore branded the player a war
 * criminal with every civilized faction.
 *
 * <p>Wired from this mod's {@code rules.csv}: higher-scored overrides of the
 * vanilla {@code mktBombardSaturation} / {@code mktBombardConfirm} rules invoke
 * this class instead ({@code DialogOptionSelected} fires only the best-scoring
 * rule). For non-Threat targets - or with the feature off - everything
 * delegates to vanilla, so ordinary bombardments are untouched. This replaces
 * an earlier attempt that toggled the caresAboutAtrocities faction-custom flag
 * around the dialog: mutating {@code getCustom()} showed no observable effect
 * in-game (the flag is likely cached at load), so the list is now edited
 * directly - pure API data, no engine internals assumed.
 *
 * <p>{@code temp} is shared state stored in market memory ($MarketCMD_temp), so
 * the list this class builds in the menu step is exactly what confirm reads,
 * even across separate rule invocations.
 */
public class ThreatincMarketCMD extends MarketCMD {

	/**
	 * The Fragment Fabricator shield: while a Threat colony's nexus still
	 * holds its fabricator, orbital bombardment - tactical or saturation -
	 * simply does not land. The bombardment menu is replaced with the failed
	 * attempt and its lesson: the screen must be taken offline from the
	 * ground. Stealing the fabricator (a ground raid at EXTREME danger) drops
	 * the shield permanently. NPC purge armadas are deliberately unaffected -
	 * they bombard through the abstract path at fleet-formation scale no
	 * single player fleet can match, which the text acknowledges.
	 */
	/** Option id for firing a bombardment into the fragment screen anyway. */
	public static final String FRAG_FIRE_OPTION = "threatincFragFire";

	protected boolean fragmentShielded() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.fragmentShieldEnabled()
				&& market != null && Factions.THREAT.equals(market.getFactionId())
				&& ThreatColonyManager.hasFragmentFabricator(market);
	}

	@Override
	public boolean execute(String ruleId, com.fs.starfarer.api.campaign.InteractionDialogAPI dialog,
			List<Misc.Token> params, java.util.Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> memoryMap) {
		String command = params.get(0).getString(memoryMap);
		if ("fragmentBombardFire".equals(command)) {
			// run the base dispatch with our (unknown-to-it) command purely
			// for its field initialization - the chain falls through harmlessly
			super.execute(ruleId, dialog, params, memoryMap);
			fragmentBombardFire();
			return true;
		}
		return super.execute(ruleId, dialog, params, memoryMap);
	}

	@Override
	protected void bombardMenu() {
		if (fragmentShielded()) {
			dialog.getVisualPanel().showImagePortion("illustrations", "bombard_prepare",
					640, 400, 0, 0, 480, 300);

			text.addPara("Even from orbit, the firing solution refuses to settle. Targeting "
					+ "resolves a glittering haze suspended above " + market.getName()
					+ " - millions of Threat fragments in a slow, patient churn. Motes "
					+ "tumble out of the layer constantly, flaring as they burn up in the "
					+ "atmosphere below - and the layer never thins. %s.",
					Misc.getNegativeHighlightColor(),
					"Something on the surface is replacing them as fast as they fail");

			text.addPara("Your ordnance officers are blunt: nothing will reach the ground "
					+ "while that screen stands, and the screen will not run dry before your "
					+ "magazines do. It can only be taken offline from the ground: %s, find "
					+ "the fabricator, and tear it out. Expect the hive to defend its heart "
					+ "above all else.", Misc.getHighlightColor(), "send your marines down");

			int cost = getBombardmentCost(market, playerFleet);
			int fuel = (int) playerFleet.getCargo().getFuel();
			text.addPara("A bombardment attempt would expend %s fuel. You have %s fuel.",
					Misc.getHighlightColor(), "" + cost, "" + fuel);

			options.clearOptions();
			options.addOption("Commence the bombardment anyway", FRAG_FIRE_OPTION);
			if (fuel < cost) {
				options.setEnabled(FRAG_FIRE_OPTION, false);
			}
			options.addOption("Go back", GO_BACK);
			return;
		}
		super.bombardMenu();
	}

	/**
	 * The futile bombardment: full fuel cost, zero effect on the colony - the
	 * ordnance dies in the upper atmosphere against the fragment screen. The
	 * player was warned in the menu; some lessons are bought.
	 */
	protected void fragmentBombardFire() {
		int cost = getBombardmentCost(market, playerFleet);
		playerFleet.getCargo().removeFuel(cost);
		com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity.addCommodityLossText(
				com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, cost, text);

		addBombardVisual(market.getPrimaryEntity());

		text.addPara("The bombardment begins - and ends - in the upper atmosphere. As the "
				+ "first munitions descend, the haze over " + market.getName() + " condenses "
				+ "to meet them: warhead after warhead is met, matched, and unmade - shattered "
				+ "into a thousand inert splinters that fall glinting into the storm below. "
				+ "Not one detonation reaches the surface.");

		text.addPara("For every fragment your ordnance burned through, the screen is already "
				+ "thicker. %s", Misc.getNegativeHighlightColor(),
				"The fabricator on the surface simply outbuilt you.");

		options.clearOptions();
		options.addOption("Go back", GO_BACK);
	}

	protected boolean waiveAtrocity() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.bombardNoAtrocity()
				&& market != null && market.getFaction() != null
				&& Factions.THREAT.equals(market.getFaction().getId());
	}

	@Override
	protected void bombardSaturation() {
		if (!waiveAtrocity()) {
			super.bombardSaturation();
			return;
		}

		// vanilla body, minus the caresAboutAtrocities sweep: only the owner -
		// the swarm itself - has any grievance about being exterminated
		temp.bombardType = BombardType.SATURATION;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);

		int dur = getBombardDisruptDuration();
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (!ind.getSpec().hasTag(Industries.TAG_NO_SATURATION_BOMBARDMENT)) {
				if (ind.getDisruptedDays() >= dur * 0.8f) continue;
				targets.add(ind);
			}
		}
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);

		boolean destroy = market.getSize() <= getBombardDestroyThreshold();
		if (Misc.isStoryCritical(market)) destroy = false;

		int fuel = (int) playerFleet.getCargo().getFuel();
		if (destroy) {
			text.addPara("A saturation bombardment of a colony this size will destroy it utterly.");
		} else {
			text.addPara("A saturation bombardment will destabilize the colony, reduce its population, " +
					"and disrupt all operations for a long time.");
		}

		text.addPara("An atrocity by any other measure - but no power in the civilized sector " +
				"mourns the swarm. Only the machines themselves will mark the loss.");

		text.addPara("The bombardment requires %s fuel. " +
					 "You have %s fuel.",
					 Misc.getHighlightColor(), "" + temp.bombardCost, "" + fuel);

		addBombardConfirmOptions();
	}

	@Override
	protected void bombardConfirm() {
		// defense in depth: even if some other path populated the hostile list
		// (e.g. vanilla bombardSaturation ran via a mod conflict), strip every
		// third party before the reputation hit is applied
		if (waiveAtrocity() && temp.bombardType == BombardType.SATURATION
				&& temp.willBecomeHostile != null) {
			for (Iterator<FactionAPI> it = temp.willBecomeHostile.iterator(); it.hasNext();) {
				FactionAPI curr = it.next();
				if (curr == null || !Factions.THREAT.equals(curr.getId())) it.remove();
			}
			if (temp.willBecomeHostile.isEmpty()) {
				temp.willBecomeHostile.add(Global.getSector().getFaction(Factions.THREAT));
			}
			ThreatIncConfig.log("Waived atrocity penalty for saturation bombardment of "
					+ market.getName() + ".");
		}
		super.bombardConfirm();
	}
}
