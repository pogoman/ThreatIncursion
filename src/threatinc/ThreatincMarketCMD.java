package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.RuleBasedDialog;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ListenerUtil;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.DebugFlags;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;

/**
 * MarketCMD override implementing the hive-siege rules for Threat colonies.
 *
 * <p>Every hive lives deep underground behind ground defenses anchored to
 * colony size (see {@link SwarmNexus}: hiveDefensePerSize per size, immune to
 * unrest, multiplied by the defense industries) - so bombardment is priced off
 * a number that stays punishing for the colony's whole life:
 *
 * <ul>
 * <li><b>Saturation</b>: fuel cost = the full ground-defense strength.
 * Disrupts every industry for only ~hiveSatDisruptDays (the buried strata
 * reknit fast) and NEVER touches colony size - bombardment cannot shrink or
 * destroy a hive. Its role is suppression: keep the organs down and the
 * decline engine (ThreatColonyManager) does the killing.</li>
 * <li><b>Tactical</b>: costs only hiveTacCostFraction of the defense figure
 * and disrupts the exposed war-strata for ~hiveTacDisruptDays - the efficient
 * way to suppress defenses (which fire at half effect while disrupted).</li>
 * <li><b>Marine raids</b>: vanilla - the deepest cut, at a casualty price.</li>
 * </ul>
 *
 * <p>The only way a Threat colony dies is decline: its population falling to
 * size 1 under sustained disruption or shortages.
 *
 * <p>Also waives the saturation-bombardment atrocity penalty when the bombed
 * colony belongs to the Threat: vanilla {@code bombardSaturation} builds
 * {@code temp.willBecomeHostile} from each faction's caresAboutAtrocities
 * flag, never checking who owned the target.
 *
 * <p>Wired from this mod's {@code rules.csv}: higher-scored overrides of the
 * vanilla {@code mktBombard*} rules invoke this class instead
 * ({@code DialogOptionSelected} fires only the best-scoring rule). For
 * non-Threat targets - or with the mod off - everything delegates to vanilla.
 *
 * <p>{@code temp} is shared state stored in market memory ($MarketCMD_temp), so
 * what one rule invocation writes (e.g. the tactical cost set in
 * {@code bombardTactical}) is exactly what a later invocation's
 * {@code bombardConfirm} reads. The confirm-screen "Never mind" routes through
 * VANILLA {@code bombardMenu} (its rule is not overridden), which resets
 * {@code temp.bombardCost} to base - harmless, because re-selecting either
 * bombardment type always re-enters our overrides, which recompute it.
 */
public class ThreatincMarketCMD extends MarketCMD {

	// ground-front options (docs/ground-war.md); each id has a rules.csv row
	public static final String GROUND_OPS = "threatincGroundOps";
	public static final String GROUND_DEPLOY = "threatincGroundDeploy";
	public static final String GROUND_RESUPPLY = "threatincGroundResupply";
	public static final String GROUND_WITHDRAW = "threatincGroundWithdraw";
	public static final String GROUND_PUSH = "threatincGroundPush";
	public static final String GROUND_ENTRENCH = "threatincGroundEntrench";
	public static final String GROUND_BACK = "threatincGroundBack";

	/**
	 * Custom command dispatch. super.execute initializes every field (dialog,
	 * market, text, options, temp) before its command chain, and an unknown
	 * command falls through that chain harmlessly - so our commands piggyback
	 * on the vanilla init and dispatch here afterward.
	 */
	@Override
	public boolean execute(String ruleId,
			com.fs.starfarer.api.campaign.InteractionDialogAPI dialog,
			List<com.fs.starfarer.api.util.Misc.Token> params,
			Map<String, com.fs.starfarer.api.campaign.rules.MemoryAPI> memoryMap) {
		String command = params.get(0).getString(memoryMap);
		boolean result = super.execute(ruleId, dialog, params, memoryMap);
		if ("groundOps".equals(command)) {
			groundOps();
		} else if ("groundDeploy".equals(command)) {
			groundDeploy();
		} else if ("groundResupply".equals(command)) {
			groundResupply();
		} else if ("groundWithdraw".equals(command)) {
			groundWithdraw();
		} else if ("groundPush".equals(command)) {
			groundPush();
		} else if ("groundEntrench".equals(command)) {
			groundEntrench();
		}
		return result;
	}

	protected boolean isThreatTarget() {
		return ThreatIncConfig.enabled() && market != null
				&& Factions.THREAT.equals(market.getFactionId());
	}

	protected boolean waiveAtrocity() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.bombardNoAtrocity()
				&& market != null && market.getFaction() != null
				&& Factions.THREAT.equals(market.getFaction().getId());
	}

	/** Saturation bill: the full defense strength (times the config scale). */
	protected int satCost() {
		int base = getBombardmentCost(market, playerFleet);
		return Math.max(2, Math.round(base * ThreatIncConfig.hiveBombardCostMult()));
	}

	/** Tactical bill: a fraction of the defense strength. */
	protected int tacCost() {
		int base = getBombardmentCost(market, playerFleet);
		return Math.max(2, Math.round(base * ThreatIncConfig.hiveTacCostFraction()));
	}

	/**
	 * Appends "Ground operations" to the military options menu of a Threat
	 * colony, keeping "Go back" last (removeOption + re-add; the escape
	 * shortcut must be re-set with it).
	 */
	@Override
	protected void showDefenses(boolean withText) {
		super.showDefenses(withText);
		if (!isThreatTarget()) return;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		// the enabled flag gates NEW deployments; an existing front must stay
		// reachable (resupply/withdraw) even after the setting is turned off
		if (!ThreatIncConfig.frontsEnabled() && front == null) return;
		options.removeOption(GO_BACK);
		String label = front != null
				? "Ground operations (front deployed)" : "Ground operations";
		options.addOption(label, GROUND_OPS);
		options.addOption("Go back", GO_BACK);
		options.setShortcut(GO_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE,
				false, false, false, true);
	}

	/**
	 * The ground-operations menu: status of the deployed front, or the landing
	 * brief when none is. All figures are the same ones ThreatGroundFronts
	 * ticks on, so what this quotes is exactly what happens.
	 */
	protected void groundOps() {
		options.clearOptions();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		int defender = (int) getDefenderStr(market, true);
		int holdNeed = (int) Math.ceil(ThreatGroundFronts.holdRequirement(market));
		int grindNeed = (int) Math.ceil(ThreatGroundFronts.grindRequirement(market));
		float upkeepDay = ThreatGroundFronts.dailyUpkeep(market);

		if (front == null) {
			int marines = (int) playerFleet.getCargo().getMarines();
			int arms = (int) playerFleet.getCargo()
					.getCommodityQuantity(Commodities.HAND_WEAPONS);
			text.addPara("A landing commits every marine and every heavy armament "
					+ "aboard to the surface - a persistent front that keeps fighting "
					+ "after you break orbit. The hive is %s strata deep with the "
					+ "Fabrication Core at the center: order pushes to take it stratum "
					+ "by stratum, and destroy the Core to eradicate the colony. Each "
					+ "stratum taken strips its share of the base defenses and of the "
					+ "hive's output.", h, "" + market.getSize());
			text.addPara("Ground defenses stand at %s: holding (and pushing) takes an "
					+ "effective strength of %s, grinding the outer defenses %s. You "
					+ "have %s marines and %s heavy armaments aboard; the front burns "
					+ "about %s armaments a day, fights at reduced effectiveness once "
					+ "they run out, and the hive counter-attacks - entrenched troops "
					+ "defend far better than pushing ones.", h,
					Misc.getWithDGS(defender), Misc.getWithDGS(holdNeed),
					Misc.getWithDGS(grindNeed), Misc.getWithDGS(marines),
					Misc.getWithDGS(arms), String.format("%.1f", upkeepDay));

			options.addOption("Land ground forces", GROUND_DEPLOY);
			float fallout = ThreatGroundFronts.falloutDaysLeft(market);
			if (fallout > 0f) {
				text.addPara("The surface is a radiological ruin - saturation fallout "
						+ "makes a landing impossible for another %s days.", neg,
						"" + (int) Math.ceil(fallout));
				options.setEnabled(GROUND_DEPLOY, false);
				options.setTooltip(GROUND_DEPLOY, "Saturation fallout blocks a landing.");
			} else if (!temp.canRaid) {
				options.setEnabled(GROUND_DEPLOY, false);
				options.setTooltip(GROUND_DEPLOY,
						"Defending fleets must be dealt with before landing ground forces.");
			} else if (marines < (int) ThreatIncConfig.frontMinMarines()) {
				options.setEnabled(GROUND_DEPLOY, false);
				options.setTooltip(GROUND_DEPLOY, "Too few marines aboard to hold any "
						+ "ground (need at least "
						+ (int) ThreatIncConfig.frontMinMarines() + ").");
			}
		} else if (!front.isPlayerOwned()) {
			String owner = "An expeditionary force";
			com.fs.starfarer.api.campaign.FactionAPI fac =
					Global.getSector().getFaction(front.factionId);
			if (fac != null) owner = Misc.ucFirst(fac.getDisplayNameWithArticle())
					+ " expeditionary force";
			text.addPara(owner + " already holds ground here - %s troops, %s of %s "
					+ "strata taken. Their campaign is their own; your fleet cannot "
					+ "direct or resupply it.", h,
					Misc.getWithDGS(Math.round(front.marines)),
					"" + front.strataHeld, "" + market.getSize());
		} else {
			int eff = (int) ThreatGroundFronts.effectiveStrength(front);
			int supplyDays = (int) ThreatGroundFronts.supplyDaysLeft(front, market);
			boolean pushing = ThreatGroundFronts.STANCE_PUSH.equals(front.stance);
			String state;
			if (ThreatGroundFronts.STATE_HOLDING.equals(front.state)) {
				state = "HOLDING - the hive's organs are suppressed and a push is possible";
			} else if (ThreatGroundFronts.STATE_GRINDING.equals(front.state)) {
				state = "GRINDING - harassing the outer defenses; too weak to push";
			} else {
				state = "a FOOTHOLD - dug in, but too weak to suppress anything";
			}
			text.addPara("The front is " + state + ". It holds %s of %s strata; the "
					+ "Fabrication Core lies at the center, and taking the last "
					+ "stratum destroys it - and the colony.", h,
					"" + front.strataHeld, "" + market.getSize());
			text.addPara("Strength %s effective (%s marines; entrenchment and supply "
					+ "included) against defenses at %s - pushing takes %s, grinding "
					+ "%s. Heavy armaments for about %s days at the current burn.", h,
					Misc.getWithDGS(eff), Misc.getWithDGS(Math.round(front.marines)),
					Misc.getWithDGS(defender), Misc.getWithDGS(holdNeed),
					Misc.getWithDGS(grindNeed), "" + supplyDays);
			if (pushing) {
				int est = (int) Math.ceil(ThreatGroundFronts.pushDaysEstimate(front, market));
				text.addPara("The assault on stratum " + (front.strataHeld + 1)
						+ " is underway - roughly %s days at current strength, and "
						+ "the troops are exposed to counter-attack while it lasts.",
						h, "" + est);
			} else if (ThreatGroundFronts.STANCE_CONSOLIDATE.equals(front.stance)) {
				text.addPara("The front is consolidating at the checkpoint and "
						+ "requesting reinforcement - without new orders it pushes "
						+ "on in %s days.", h,
						"" + (int) Math.ceil(front.consolidateDaysLeft));
			}
			if (front.armaments <= 0f) {
				text.addPara("The heavy armaments are exhausted - the front fights at "
						+ "reduced effectiveness and casualties are mounting.", neg);
			}
			if (ThreatGroundFronts.orbitContested(market.getId())) {
				text.addPara("Defense Swarms contest the orbit - while they live, "
						+ "nothing lands and nothing leaves.", neg);
			}

			int marines = (int) playerFleet.getCargo().getMarines();
			int arms = (int) playerFleet.getCargo()
					.getCommodityQuantity(Commodities.HAND_WEAPONS);

			if (!pushing) {
				int estDays = (int) Math.ceil(ThreatGroundFronts.pushDaysEstimate(front, market));
				int estLoss = ThreatGroundFronts.pushCasualtyEstimate(front, market);
				options.addOption("Order a push on stratum " + (front.strataHeld + 1)
						+ " (~" + estDays + " days, ~" + estLoss + " casualties)",
						GROUND_PUSH);
				if (eff < holdNeed) {
					options.setEnabled(GROUND_PUSH, false);
					options.setTooltip(GROUND_PUSH, "Too weak to take the next stratum - "
							+ "reinforce the front, resupply its armaments, or soften "
							+ "the defenses further first.");
				}
				if (ThreatGroundFronts.STANCE_CONSOLIDATE.equals(front.stance)) {
					options.addOption("Order the front to entrench and stand fast",
							GROUND_ENTRENCH);
				}
			} else {
				options.addOption("Break off the assault and entrench", GROUND_ENTRENCH);
			}
			options.addOption("Transfer all marines and heavy armaments to the front",
					GROUND_RESUPPLY);
			if (marines <= 0 && arms <= 0) {
				options.setEnabled(GROUND_RESUPPLY, false);
				options.setTooltip(GROUND_RESUPPLY,
						"No marines or heavy armaments aboard.");
			}
			options.addOption("Withdraw the ground forces", GROUND_WITHDRAW);
		}

		options.addOption("Go back", GROUND_BACK);
		options.setShortcut(GROUND_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE,
				false, false, false, true);
	}

	protected void groundDeploy() {
		// re-validate: the option can be reached with stale temp state
		float fallout = ThreatGroundFronts.falloutDaysLeft(market);
		int marines = (int) playerFleet.getCargo().getMarines();
		int arms = (int) playerFleet.getCargo()
				.getCommodityQuantity(Commodities.HAND_WEAPONS);
		if (!ThreatIncConfig.frontsEnabled() || fallout > 0f || !temp.canRaid
				|| marines < (int) ThreatIncConfig.frontMinMarines()
				|| ThreatGroundFronts.getFront(market.getId()) != null) {
			groundOps();
			return;
		}
		playerFleet.getCargo().removeMarines(marines);
		playerFleet.getCargo().removeCommodity(Commodities.HAND_WEAPONS, arms);
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.deploy(market,
				Factions.PLAYER, marines, arms);
		// classify now so the first poll doesn't re-announce what we say here
		float eff = ThreatGroundFronts.effectiveStrength(front);
		if (eff >= ThreatGroundFronts.holdRequirement(market)) {
			front.state = ThreatGroundFronts.STATE_HOLDING;
		} else if (eff >= ThreatGroundFronts.grindRequirement(market)) {
			front.state = ThreatGroundFronts.STATE_GRINDING;
		} else {
			front.state = ThreatGroundFronts.STATE_FOOTHOLD;
		}
		front.announcedState = front.state;
		text.addPara("The landers go down through the auspex haze. %s marines and %s "
				+ "heavy armaments are on the ground and digging in.",
				Misc.getHighlightColor(), Misc.getWithDGS(marines),
				Misc.getWithDGS(arms));
		groundOps();
	}

	protected void groundPush() {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()
				|| ThreatGroundFronts.effectiveStrength(front)
						< ThreatGroundFronts.holdRequirement(market)) {
			groundOps();
			return;
		}
		ThreatGroundFronts.orderPush(front);
		text.addPara("The order goes down: take stratum " + (front.strataHeld + 1) + ".");
		groundOps();
	}

	protected void groundEntrench() {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()) {
			groundOps();
			return;
		}
		ThreatGroundFronts.orderEntrench(front);
		text.addPara("The assault is broken off - the front digs in on what it holds.");
		groundOps();
	}

	protected void groundResupply() {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		int marines = (int) playerFleet.getCargo().getMarines();
		int arms = (int) playerFleet.getCargo()
				.getCommodityQuantity(Commodities.HAND_WEAPONS);
		if (front == null || !front.isPlayerOwned() || (marines <= 0 && arms <= 0)) {
			groundOps();
			return;
		}
		playerFleet.getCargo().removeMarines(marines);
		playerFleet.getCargo().removeCommodity(Commodities.HAND_WEAPONS, arms);
		ThreatGroundFronts.resupply(front, marines, arms);
		text.addPara("%s marines and %s heavy armaments go down to the front.",
				Misc.getHighlightColor(), Misc.getWithDGS(marines),
				Misc.getWithDGS(arms));
		groundOps();
	}

	protected void groundWithdraw() {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()) {
			groundOps();
			return;
		}
		int[] recovered = ThreatGroundFronts.withdraw(market.getId());
		if (recovered[0] > 0) playerFleet.getCargo().addMarines(recovered[0]);
		if (recovered[1] > 0) {
			playerFleet.getCargo().addCommodity(Commodities.HAND_WEAPONS, recovered[1]);
		}
		text.addPara("The front folds up its positions and lifts off. %s marines and "
				+ "%s heavy armaments return to the fleet.", Misc.getHighlightColor(),
				Misc.getWithDGS(recovered[0]), Misc.getWithDGS(recovered[1]));
		groundOps();
	}

	@Override
	protected void bombardMenu() {
		super.bombardMenu();
		if (!isThreatTarget()) return;

		int tac = tacCost();
		text.addPara("Surface returns are thin. Beneath the crust the auspex paints "
				+ "kilometers of fabrication strata - the hive lives %s, and no "
				+ "bombardment can burn it out. A tactical strike on the exposed "
				+ "war-strata would cost only %s fuel and suppress its defenses; "
				+ "saturating the whole world buys days of disruption at the full "
				+ "price above.", Misc.getHighlightColor(), "deep underground",
				"" + tac);

		// vanilla gates both options at the FULL cost; tactical is cheaper
		// against the hive, so re-open it when the fleet can afford that much
		int fuel = (int) playerFleet.getCargo().getFuel();
		if (fuel >= tac || DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_TACTICAL, true);
			options.setTooltip(BOMBARD_TACTICAL, null);
		}
	}

	/**
	 * Tactical bombardment against the hive: reduced fuel cost and hive-specific
	 * disruption duration. Unlike vanilla, there is NO already-disrupted skip
	 * window - already-disrupted war-strata stay valid targets so repeat passes
	 * can grind the defenses down. The disruption STACKS additively (see
	 * bombardConfirm), so each pass drives getDisruptedDays() higher and, via
	 * ThreatColonyManager.disruptedDefenseResilience, wears the defensive bonus
	 * further toward zero. A player with the fuel can plan a sustained
	 * suppression campaign; the size-anchored base defense (SwarmNexus) still
	 * remains, so bombardment never becomes free.
	 */
	@Override
	protected void bombardTactical() {
		if (!isThreatTarget()) {
			super.bombardTactical();
			return;
		}

		temp.bombardType = BombardType.TACTICAL;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);

		int dur = (int) ThreatIncConfig.hiveTacDisruptDays();

		// no already-disrupted skip: repeat passes are the intended way to soften
		// the war-strata, and their disruption stacks (see bombardConfirm)
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (ind.getSpec().hasTag(Industries.TAG_TACTICAL_BOMBARDMENT)) {
				targets.add(ind);
			}
		}
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);

		if (targets.isEmpty()) {
			text.addPara(market.getName() + " does not have any military targets "
					+ "that would be affected by a tactical bombardment.");
			addBombardNeverMindOption();
			return;
		}

		// tactical is the cheap, surgical option against the hive; recomputed
		// here on every entry (see class doc re the never-mind cost reset)
		temp.bombardCost = tacCost();

		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("A tactical bombardment will crater the hive's exposed war-strata, "
				+ "disrupting the following military targets for approximately %s days - "
				+ "and while they are silenced, the colony's ground defenses slacken with "
				+ "them:", Misc.getHighlightColor(), "" + dur);
		for (Industry ind : targets) {
			text.addPara("    " + ind.getCurrentName());
		}
		// danger close: with a front on the ground the strike is target-marked -
		// it also cracks the deep organs - but the barrage lands among your own
		// positions. Warned here, applied in bombardConfirm.
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front != null && front.isPlayerOwned() && ThreatIncConfig.frontsEnabled()
				&& ThreatIncConfig.frontDangerCloseEnabled()) {
			int loss = (int) Math.ceil(front.marines
					* ThreatIncConfig.frontDangerCloseLossFraction());
			text.addPara("Your ground forces are inside the target grid. They will mark "
					+ "targets - the strike's disruption will also land on the "
					+ "Fabrication Core and the port - but a barrage this close will "
					+ "cost the front about %s marines.",
					Misc.getNegativeHighlightColor(), "" + loss);
		}

		text.addPara("The bombardment requires %s fuel. You have %s fuel.",
				Misc.getHighlightColor(), "" + temp.bombardCost, "" + fuel);

		addBombardConfirmOptions();

		if (fuel < temp.bombardCost && !DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, "Not enough fuel.");
		}
	}

	@Override
	protected void bombardSaturation() {
		if (!isThreatTarget()) {
			super.bombardSaturation();
			return;
		}

		temp.bombardType = BombardType.SATURATION;

		// hostile list: owner-only when the atrocity waiver is on; otherwise the
		// vanilla caresAboutAtrocities sweep
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);
		List<FactionAPI> nonHostile = new ArrayList<FactionAPI>();
		if (!waiveAtrocity()) {
			for (FactionAPI other : Global.getSector().getAllFactions()) {
				if (temp.willBecomeHostile.contains(other)) continue;
				if (other.getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES)) {
					temp.willBecomeHostile.add(other);
					if (!other.isHostileTo(Factions.PLAYER)) nonHostile.add(other);
				}
			}
		}

		// disruption from a pass is short against the buried hive, so the
		// already-disrupted skip window must be short too or one pass would
		// blank the target list for a year
		int dur = (int) ThreatIncConfig.hiveSatDisruptDays();
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (!ind.getSpec().hasTag(Industries.TAG_NO_SATURATION_BOMBARDMENT)) {
				if (ind.getDisruptedDays() >= dur * 0.8f) continue;
				targets.add(ind);
			}
		}
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);

		// the full defense bill; recomputed on every entry, which also
		// neutralizes the never-mind cost-reset bypass
		temp.bombardCost = satCost();

		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("The hive is buried too deep for any bombardment to kill or even "
				+ "thin its population. A saturation pass will disrupt every surface "
				+ "operation for a matter of %s - the strata below reknit quickly. To "
				+ "destroy this colony, keep its organs suppressed or its supply lines "
				+ "cut until the hive itself withers.", Misc.getHighlightColor(), "days");

		if (waiveAtrocity()) {
			text.addPara("An atrocity by any other measure - but no power in the civilized "
					+ "sector mourns the swarm. Only the machines themselves will mark the "
					+ "loss.");
		} else if (nonHostile.isEmpty()) {
			text.addPara("An atrocity of this scale can not be hidden, but any factions that "
					+ "would be dismayed by such actions are already hostile to you.");
		} else {
			text.addPara("An atrocity of this scale can not be hidden, and will make the "
					+ "following factions hostile:");
			for (FactionAPI fac : nonHostile) {
				text.addPara("    " + Misc.ucFirst(fac.getDisplayName()), fac.getBaseUIColor());
			}
		}

		if (ThreatIncConfig.frontsEnabled()) {
			ThreatGroundFronts.GroundFront satFront =
					ThreatGroundFronts.getFront(market.getId());
			if (satFront != null && satFront.isPlayerOwned()) {
				text.addPara("YOUR OWN GROUND FORCES ARE DEPLOYED ON THE SURFACE. "
						+ "A saturation pass will annihilate the front - there will "
						+ "be no survivors.", Misc.getNegativeHighlightColor());
			} else if (satFront != null) {
				text.addPara("An allied expeditionary ground force is on the surface - "
						+ "a saturation pass will annihilate it.",
						Misc.getNegativeHighlightColor());
			}
			if (ThreatIncConfig.falloutDays() > 0f) {
				text.addPara("The fallout will keep ground forces from landing for "
						+ "about %s days afterward.", Misc.getHighlightColor(),
						"" + (int) ThreatIncConfig.falloutDays());
			}
		}

		text.addPara("The bombardment requires %s fuel. You have %s fuel.",
				Misc.getHighlightColor(), "" + temp.bombardCost, "" + fuel);

		addBombardConfirmOptions();

		if (fuel < temp.bombardCost && !DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, "Not enough fuel.");
		}
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

		if (isThreatTarget() && temp.bombardType == BombardType.SATURATION) {
			threatSatConfirm();
			return;
		}

		if (isThreatTarget() && temp.bombardType == BombardType.TACTICAL) {
			// full vanilla confirm flow (fuel, rep, unrest, listener), then replace
			// the 365-day disruption it wrote down with an ADDITIVE hive-short pass:
			// each bomb STACKS its duration on top of whatever disruption already
			// stood (a prior pass or a raid), so repeat passes drive the disruption
			// clock - and thus disruptedDefenseResilience - steadily toward zero.
			// Uncapped by design: enough fuel buys total suppression. reapply below
			// makes the softened defenses take effect immediately, not on the next
			// colony poll.
			Map<Industry, Float> pre = new LinkedHashMap<Industry, Float>();
			for (Industry ind : temp.bombardmentTargets) {
				pre.put(ind, ind.getDisruptedDays());
			}
			super.bombardConfirm();
			for (Map.Entry<Industry, Float> entry : pre.entrySet()) {
				float dur = ThreatIncConfig.hiveTacDisruptDays()
						* StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
				entry.getKey().setDisrupted(entry.getValue() + dur);
			}
			applyDangerClose();
			market.reapplyIndustries();
			return;
		}

		super.bombardConfirm();
	}

	/**
	 * Saturation bombardment of a hive. Mirrors vanilla
	 * {@code MarketCMD.bombardConfirm} (0.98a) with the siege differences:
	 * disruption is hive-short (and never erases longer existing disruption),
	 * and there is NO size reduction and NO destroy branch - bombardment
	 * cannot kill a hive; only decline can. The atrocity counters are skipped
	 * under the waiver.
	 */
	protected void threatSatConfirm() {
		if (temp.bombardType == null) {
			bombardNeverMind();
			return;
		}

		dialog.getVisualPanel().showImagePortion("illustrations", "bombard_saturation_result",
				640, 400, 0, 0, 480, 300);

		java.util.Random random = getRandom();

		if (!DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			float timeout = SATURATION_BOMBARD_TIMEOUT_DAYS;
			Misc.increaseMarketHostileTimeout(market, timeout);
			timeout *= 0.7f;
			for (MarketAPI curr : Global.getSector().getEconomy()
					.getMarkets(market.getContainingLocation())) {
				if (curr == market) continue;
				boolean cares = curr.getFaction()
						.getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES);
				if (curr.getFaction().isNeutralFaction()) continue;
				if (curr.getFaction().isPlayerFaction()) continue;
				if (curr.getFaction().isHostileTo(market.getFaction()) && !cares) continue;
				Misc.increaseMarketHostileTimeout(curr, timeout);
			}
		}

		addMilitaryResponse();

		playerFleet.getCargo().removeFuel(temp.bombardCost);
		AddRemoveCommodity.addCommodityLossText(Commodities.FUEL, temp.bombardCost, text);

		for (FactionAPI curr : temp.willBecomeHostile) {
			CustomRepImpact impact = new CustomRepImpact();
			impact.delta = market.getSize() * -0.01f;
			impact.ensureAtBest = RepLevel.HOSTILE;
			if (curr == faction) {
				impact.ensureAtBest = RepLevel.VENGEFUL;
			}
			Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, true),
					curr.getId());
		}

		// no war-crime bookkeeping for exterminating the swarm
		if (!waiveAtrocity()) {
			int atrocities = (int) Global.getSector().getCharacterData()
					.getMemoryWithoutUpdate().getFloat(MemFlags.PLAYER_ATROCITIES);
			atrocities++;
			Global.getSector().getCharacterData().getMemoryWithoutUpdate()
					.set(MemFlags.PLAYER_ATROCITIES, atrocities);
			if (market.getFaction() != null) {
				com.fs.starfarer.api.campaign.rules.MemoryAPI mem =
						market.getFaction().getMemoryWithoutUpdate();
				mem.set(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER,
						mem.getInt(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER) + 1);
			}
		}

		// unrest is flavor only - hive defenses are stability-immune (SwarmNexus)
		int stabilityPenalty = getSaturationBombardmentStabilityPenalty();
		if (stabilityPenalty > 0) {
			String reason = "Recently bombarded";
			if (Misc.isPlayerFactionSetUp()) {
				reason = playerFaction.getDisplayName() + " bombardment";
			}
			RecentUnrest.get(market).add(stabilityPenalty, reason);
		}

		if (market.hasCondition(Conditions.HABITABLE)
				&& !market.hasCondition(Conditions.POLLUTION)) {
			market.addCondition(Conditions.POLLUTION);
		}

		// short disruption, and never shorter than what a raid already earned
		for (Industry curr : temp.bombardmentTargets) {
			float dur = ThreatIncConfig.hiveSatDisruptDays()
					* StarSystemGenerator.getNormalRandom(random, 1f, 1.25f);
			curr.setDisrupted(Math.max(curr.getDisruptedDays(), dur));
		}
		market.reapplyIndustries();

		// theater shaping, never decline progress: the world is silenced for
		// days, and the fallout forfeits ground tempo - no landings for a while,
		// and any front already down there dies under the sky-fall
		if (ThreatIncConfig.frontsEnabled()) {
			ThreatGroundFronts.setFallout(market);
			ThreatGroundFronts.GroundFront satFront =
					ThreatGroundFronts.getFront(market.getId());
			if (satFront != null) {
				boolean own = satFront.isPlayerOwned();
				ThreatGroundFronts.destroy(market.getId());
				text.addPara((own ? "Your ground forces were"
						: "The allied expeditionary ground force was")
						+ " on the surface when the sky fell. There are no survivors.",
						Misc.getNegativeHighlightColor());
			}
		}

		text.addPara("Surface operations disrupted for a handful of days. The deep "
				+ "strata absorb the rest - the hive's population is untouched.");
		if (ThreatIncData.lastHealth(market.getId()) < ThreatColonyManager.CRITICAL_HEALTH) {
			text.addPara("The colony is badly weakened - its garrisons, defenses and "
					+ "counter-attacks all run on its failing vitality. Only a ground "
					+ "victory destroys it; this bought the ground war time.",
					Misc.getNegativeHighlightColor());
		}

		// fired manually since vanilla bombardConfirm was bypassed - this keeps
		// strike recall and the atrocity rep-restore listener working
		ListenerUtil.reportSaturationBombardmentFinished(dialog, market, temp);

		if (dialog != null && dialog.getPlugin() instanceof RuleBasedDialog) {
			if (dialog.getInteractionTarget() != null
					&& dialog.getInteractionTarget().getMarket() != null) {
				Global.getSector().setPaused(false);
				dialog.getInteractionTarget().getMarket().getMemoryWithoutUpdate()
						.advance(0.0001f);
				Global.getSector().setPaused(true);
			}
			((RuleBasedDialog) dialog.getPlugin()).updateMemory();
		}

		Misc.setFlagWithReason(market.getMemoryWithoutUpdate(), MemFlags.RECENTLY_BOMBARDED,
				Factions.PLAYER, true, 30f);

		addBombardVisual(market.getPrimaryEntity());

		addBombardContinueOption();
	}

	/**
	 * Danger close (docs/ground-war.md): a tactical pass with a front deployed
	 * costs it marines - and in exchange the ground forces mark targets, so the
	 * pass's disruption also stacks onto the Fabrication Core and the port,
	 * not just the war-strata. Called from the tactical bombardConfirm branch,
	 * before the reapply.
	 */
	protected void applyDangerClose() {
		if (!ThreatIncConfig.frontsEnabled()
				|| !ThreatIncConfig.frontDangerCloseEnabled()) return;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()) return;

		int loss = (int) Math.ceil(front.marines
				* ThreatIncConfig.frontDangerCloseLossFraction());
		front.marines = Math.max(0f, front.marines - loss);

		List<Industry> deep = new ArrayList<Industry>();
		Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
		if (core != null) deep.add(core);
		Industry port = ThreatColonyManager.getPort(market);
		if (port != null) deep.add(port);
		for (Industry ind : deep) {
			float dur = ThreatIncConfig.hiveTacDisruptDays()
					* StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
			ind.setDisrupted(ind.getDisruptedDays() + dur);
		}

		text.addPara("Ground-marked targets: the strike also cracked the hive's deep "
				+ "organs. The front lost %s marines to the barrage.",
				Misc.getNegativeHighlightColor(), "" + loss);

		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatGroundFronts.destroy(market.getId());
			text.addPara("What was left of the front could not hold its positions "
					+ "afterward - it has been overrun.", Misc.getNegativeHighlightColor());
		}
	}
}
