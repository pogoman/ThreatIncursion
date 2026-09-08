package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
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
 * and is one slice of the orbital siege (docs/ground-war.md "Sieges from
 * orbit"): the fleet's points against the defence figure suppress the
 * war-strata toward the orbital floor, and the weapon growths answer with
 * ships lost. The same rule runs against a human colony at vanilla's fuel
 * bill.</li>
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
 * ({@code DialogOptionSelected} fires only the best-scoring rule). With the
 * mod off everything delegates to vanilla; with it on, only tactical
 * bombardment (the siege slice, any world with a fortification to suppress)
 * touches non-Threat targets.
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
	 * The fleets defending a hive, wherever in its system they are: the
	 * colony's own garrison (ThreatColonyManager GARRISON_FLAG), reinforcements
	 * on their way to it (REINFORCE_TARGET_KEY), Defend Swarms stationed over
	 * it (ThreatSwarmDefend) and any other swarm fleet within defendRadius of
	 * the world. Live, visible, crewed, not a station, the market's faction.
	 * One list, one rule, read for the menu draw, for the Engage click and
	 * for the raid/bombard gates alike.
	 *
	 * <p>Vanilla's own scan (getInteractionTargetForFIDPI) reaches only
	 * battleJoinRange - 500 su net of radii, which the garrison orbit (spawned
	 * 400-700 su out) straddles and which a swarm hunting the player leaves
	 * altogether - and skips a fleet whose finished battle still hangs off it,
	 * so the menu could see a defender at draw and none at the click
	 * (2026-09-06 crash, "otherFleet is null"). Its raid/bombard gates then
	 * read the swarm through its fleet AI, which for a fleet that always fights
	 * came back "too small to matter" beside "gives battle": raid and
	 * bombardment open under a full garrison, or with the garrison a few
	 * hundred su out and closing (2026-09-07, Alpha Novy Tayvay I).
	 */
	protected List<CampaignFleetAPI> threatDefenders() {
		List<CampaignFleetAPI> result = new ArrayList<CampaignFleetAPI>();
		if (entity == null || market == null || entity.getContainingLocation() == null) {
			return result;
		}
		float radius = ThreatIncConfig.defendRadius();
		String marketId = market.getId();
		for (CampaignFleetAPI fleet : entity.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired() || fleet.isHidden()) continue;
			if (fleet.isStationMode() || fleet.isPlayerFleet()) continue;
			if (fleet.getFaction() != market.getFaction()) continue;
			if (fleet.getFleetData().getNumMembers() <= 0) continue;
			com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
			boolean ours = marketId.equals(mem.getString(ThreatColonyManager.GARRISON_FLAG))
					|| marketId.equals(mem.getString(ThreatColonyManager.REINFORCE_TARGET_KEY));
			if (!ours) {
				ThreatSwarmDefend.Entry e = ThreatSwarmDefend.of(fleet);
				ours = e != null && marketId.equals(e.marketId);
			}
			if (!ours && Misc.getDistance(fleet, entity) > radius) continue;
			result.add(fleet);
		}
		return result;
	}

	/** A battle the fleet is still fighting (a finished one can linger on the fleet). */
	protected static boolean inLiveBattle(CampaignFleetAPI fleet) {
		return fleet.getBattle() != null && !fleet.getBattle().isDone();
	}

	/** Close enough to the world to be engaged from its orbit. */
	protected boolean inReach(CampaignFleetAPI fleet) {
		return Misc.getDistance(fleet, entity) <= ThreatIncConfig.defendRadius();
	}

	/**
	 * Vanilla's defender pick, taken from threatDefenders for Threat targets:
	 * the nearest swarm fleet in reach and not in a battle, else the nearest
	 * in reach that is (vanilla then offers to join that battle); null while
	 * the defenders are all still inbound. Vanilla's showDefenses and engage
	 * both call this, so the draw and the click agree.
	 */
	@Override
	protected CampaignFleetAPI getInteractionTargetForFIDPI() {
		if (!isThreatTarget()) return super.getInteractionTargetForFIDPI();
		CampaignFleetAPI station = getStationFleet();
		if (station != null) return station;
		CampaignFleetAPI free = null;
		CampaignFleetAPI busy = null;
		float freeDist = Float.MAX_VALUE;
		float busyDist = Float.MAX_VALUE;
		for (CampaignFleetAPI fleet : threatDefenders()) {
			if (!inReach(fleet)) continue;
			float dist = Misc.getDistance(fleet, entity);
			if (inLiveBattle(fleet)) {
				if (dist < busyDist) {
					busy = fleet;
					busyDist = dist;
				}
			} else if (dist < freeDist) {
				free = fleet;
				freeDist = dist;
			}
		}
		return free != null ? free : busy;
	}

	/**
	 * Writes the final state of Engage, raid and bombardment from the swarm
	 * fleets defending the world (threatDefenders), not from vanilla's
	 * fleet-AI reading of them. A free defender in reach: Engage open, raid
	 * and bombardment closed (and with them the ground landing, which reads
	 * temp.canRaid). Defenders alive but none in reach yet: everything closed
	 * until they arrive. Every defender busy in a battle: vanilla's rule - a
	 * raid rides the distraction, a bombardment waits. None: Engage closed,
	 * raid and bombardment on vanilla's own verdict, raid cooldown included.
	 * Logs every fleet weighed, so a wrong menu explains itself.
	 *
	 * <p>MUST BE CALLED LAST, after every addOption/removeOption on the panel:
	 * a structural change rebuilds the options and drops the enabled state set
	 * before it. So this sets all three every time rather than only the ones
	 * it wants closed - there is nothing left standing to inherit.
	 */
	protected void gateOnDefenders(boolean withText) {
		int near = 0;
		int inbound = 0;
		int busy = 0;
		float fp = 0f;
		float nearest = Float.MAX_VALUE;
		StringBuilder why = new StringBuilder();
		for (CampaignFleetAPI fleet : threatDefenders()) {
			float dist = Misc.getDistance(fleet, entity);
			String state;
			if (inLiveBattle(fleet)) {
				busy++;
				state = "busy";
			} else if (inReach(fleet)) {
				near++;
				fp += fleet.getFleetPoints();
				state = "near";
			} else {
				inbound++;
				nearest = Math.min(nearest, dist);
				state = "inbound";
			}
			why.append(" [").append(fleet.getName()).append(" ").append((int) fleet.getFleetPoints())
					.append(" FP ").append((int) dist).append(" su ").append(state).append("]");
		}
		ThreatIncConfig.log("Military options at " + market.getName() + ": near " + near
				+ ", inbound " + inbound + ", busy " + busy + why);
		if (withText) {
			if (near > 0) {
				text.addPara("The swarm holds the orbit: %s, %s fleet points.",
						Misc.getHighlightColor(), near == 1 ? "one fleet" : near + " fleets",
						Misc.getWithDGS((int) fp));
			} else if (inbound > 0) {
				text.addPara("The swarm's defenders are inbound: %s, the nearest %s units out.",
						Misc.getHighlightColor(),
						inbound == 1 ? "one fleet" : inbound + " fleets",
						Misc.getWithDGS((int) nearest));
			} else if (busy > 0) {
				text.addPara("The defending swarms are in battle.");
			}
		}
		if (DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(RAID, true);
			options.setEnabled(BOMBARD, true);
			options.setEnabled(ENGAGE, true);
			return;
		}
		boolean defended = near > 0 || inbound > 0;
		if (defended) {
			temp.canRaid = false;
			temp.canBombard = false;
		} else if (busy > 0) {
			temp.canBombard = false;
		}
		options.setEnabled(RAID, temp.canRaid);
		if (defended) {
			options.setTooltip(RAID, "The presence of enemy fleets that are willing "
					+ "to offer battle makes a raid impossible.");
		}
		options.setEnabled(BOMBARD, temp.canBombard);
		if (!temp.canBombard) {
			options.setTooltip(BOMBARD, "All defenses must be defeated to make a "
					+ "bombardment possible.");
		}
		// the same pick vanilla's engage() will make on the click, so the
		// option is open exactly when there is something to engage
		CampaignFleetAPI target = getInteractionTargetForFIDPI();
		options.setEnabled(ENGAGE, target != null);
		if (target != null) {
			options.setTooltip(ENGAGE, "Engage the swarm fleets holding the orbit.");
		} else if (inbound > 0) {
			options.setTooltip(ENGAGE, "The defenders are still "
					+ Misc.getWithDGS((int) nearest) + " units out.");
		} else {
			options.setTooltip(ENGAGE, "There are no defenders to engage.");
		}
	}

	/**
	 * The military options of a Threat colony: vanilla's menu with "Ground
	 * operations" slotted in above "Go back", and the defender rule
	 * (gateOnDefenders) written over the top of it last.
	 *
	 * <p>That order is the whole fix (2026-09-07). We used to gate first and
	 * slot the option in afterwards, and the removeOption dropped every enabled
	 * state already set - vanilla's own included - so Engage stayed clickable
	 * over an empty orbit and raid and bombardment stayed open under a full
	 * garrison. Only removeOption does that - it rebuilds the panel, and
	 * setEnabled, setTooltip, setShortcut and addOptionConfirmation all attach
	 * to the button widget the rebuild discards (only a tooltip passed into
	 * addOption survives). The escape shortcut re-set below is the same wipe,
	 * papered over long before anyone named it. A plain addOption after
	 * setEnabled is fine, proven by vanilla's own "Go back" and by groundOps.
	 * Vanilla calls removeOption nowhere in a menu build, so nothing in the base
	 * game trips over it. Keep the structure settled before the state, and let
	 * gateOnDefenders write the state last.
	 */
	@Override
	protected void showDefenses(boolean withText) {
		if (!isThreatTarget()) {
			super.showDefenses(withText);
			return;
		}
		// no defender text from vanilla: it reads the swarm through its fleet
		// AI (see threatDefenders); gateOnDefenders says what is true instead
		super.showDefenses(false);
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		// the enabled flag gates NEW deployments; an existing front must stay
		// reachable (resupply/withdraw) even after the setting is turned off
		if (ThreatIncConfig.frontsEnabled() || front != null) {
			options.removeOption(GO_BACK);
			String label = front != null
					? "Ground operations (front deployed)" : "Ground operations";
			options.addOption(label, GROUND_OPS);
			options.addOption("Go back", GO_BACK);
			options.setShortcut(GO_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE,
					false, false, false, true);
		}
		gateOnDefenders(withText);
	}

	/**
	 * Backstop on vanilla's engage: it hands the dialog to a fleet plugin built
	 * on getInteractionTargetForFIDPI(), and with nothing found that plugin
	 * fails to initialise and the next click crashes the game (2026-09-06,
	 * "this.battle is null"). The Threat pick above keeps the draw and the
	 * click in step; this stays for every market (rules.csv
	 * threatinc_engageSel), a no-op whenever a defender is found.
	 */
	@Override
	protected void engage() {
		if (getInteractionTargetForFIDPI() == null) {
			text.addPara("There are no defenders in range to engage.");
			showDefenses(false);
			return;
		}
		super.engage();
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

		if (front == null) {
			int marines = (int) playerFleet.getCargo().getMarines();
			// what the marines aboard would burn once they are the front
			float upkeepDay = ThreatGroundFronts.dailyUpkeep(marines);
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
			int supplyDays = (int) ThreatGroundFronts.supplyDaysLeft(front);
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
		// the rank has to be read while the marines are still in the hold:
		// vanilla recounts the pool once they are gone (review, 2026-09-08)
		float landingLevel = ThreatMarineXP.fleetLevel();
		playerFleet.getCargo().removeMarines(marines);
		playerFleet.getCargo().removeCommodity(Commodities.HAND_WEAPONS, arms);
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.deploy(market,
				Factions.PLAYER, marines, arms, landingLevel);
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
		boolean pushing = ThreatGroundFronts.STANCE_PUSH.equals(front.stance);
		text.addPara("The landers go down through the auspex haze. %s marines and %s "
				+ "heavy armaments are on the ground and "
				+ (pushing ? "moving on stratum 1." : "digging in - too few to hold, "
						+ "so they wait for more."),
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
		float arrivingLevel = ThreatMarineXP.fleetLevel(); // before the hold is emptied
		playerFleet.getCargo().removeMarines(marines);
		playerFleet.getCargo().removeCommodity(Commodities.HAND_WEAPONS, arms);
		boolean wasPushing = ThreatGroundFronts.STANCE_PUSH.equals(front.stance);
		ThreatGroundFronts.resupply(front, marines, arms, arrivingLevel);
		text.addPara("%s marines and %s heavy armaments go down to the front.",
				Misc.getHighlightColor(), Misc.getWithDGS(marines),
				Misc.getWithDGS(arms));
		if (!wasPushing && ThreatGroundFronts.STANCE_PUSH.equals(front.stance)) {
			text.addPara("Reinforced to holding strength, the front moves on stratum "
					+ (front.strataHeld + 1) + ".");
		}
		groundOps();
	}

	protected void groundWithdraw() {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()) {
			groundOps();
			return;
		}
		// the rank has to be read before the front is taken apart
		float level = ThreatMarineXP.frontLevel(front);
		int[] recovered = ThreatGroundFronts.withdraw(market.getId());
		if (recovered[0] > 0) {
			// bodies first, then their experience: vanilla clamps the fleet
			// pool to the headcount, so the order is load-bearing
			playerFleet.getCargo().addMarines(recovered[0]);
			ThreatMarineXP.fleetReturn(recovered[0], level);
		}
		if (recovered[1] > 0) {
			playerFleet.getCargo().addCommodity(Commodities.HAND_WEAPONS, recovered[1]);
		}
		text.addPara("The front folds up its positions and lifts off. %s %s marines and "
				+ "%s heavy armaments return to the fleet.", Misc.getHighlightColor(),
				Misc.getWithDGS(recovered[0]), ThreatMarineXP.rankName(level).toLowerCase(),
				Misc.getWithDGS(recovered[1]));
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
	 * Tactical bombardment is a slice of the orbital siege (docs/ground-war.md
	 * "Sieges from orbit", 2026-09-06) - against a hive and a human colony
	 * alike, the same rules the swarm's strikes and the factions' siege
	 * expeditions fly. The player's fleet points against the world's
	 * ground-defence figure set how many disruption days the pass adds to the
	 * world's fortifications (siegeBombardSliceDays of siege), never past the
	 * orbital floor, and the batteries answer with ships lost, smallest first.
	 * Vanilla's own flow (fuel, reputation, unrest) is kept; only the
	 * disruption it writes is replaced ({@link #bombardConfirm}). The hive's
	 * reduced fuel bill stays.
	 */
	/** Whether the siege slice describes this world: the mod is on and the theatre has a fortification to suppress. */
	protected boolean siegeSliceApplies() {
		if (!ThreatIncConfig.enabled() || market == null) return false;
		if (!ThreatGroundFronts.Theatre.of(market).fortifications(market).isEmpty()) return true;
		// a shielded world with no defence structure is still a siege: the shield
		// is what orbit is fighting through, and grinding it down is progress
		return ThreatShield.present(market);
	}

	@Override
	protected void bombardTactical() {
		if (!siegeSliceApplies()) {
			super.bombardTactical(); // a world with no defence structure: vanilla's targets and days
			return;
		}

		temp.bombardType = BombardType.TACTICAL;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);

		// what orbit can still push: every fortification above the floor
		ThreatGroundFronts.Theatre theatre = ThreatGroundFronts.Theatre.of(market);
		float floorDays = ThreatGroundFronts.siegeFloorDays(market) - 0.01f;
		List<Industry> targets = new ArrayList<Industry>();
		for (Industry ind : theatre.fortifications(market)) {
			if (ThreatGroundFronts.siegeDisruptDays(ind) >= floorDays) continue;
			targets.add(ind);
		}
		// the shield is a target too - knocking it down is what opens the rest
		// of the world to orbit, so a pass that only grinds it is worth flying.
		// It is kept out of `targets` because that list is printed under one
		// shared days figure and the shield takes a different one (it has no
		// cover of its own); it gets its own line below.
		Industry shield = ThreatShield.present(market) ? ThreatShield.get(market) : null;
		float shieldRoom = shield == null ? 0f : Math.max(0f,
				ThreatGroundFronts.siegeFloorDays(market)
						- ThreatGroundFronts.siegeDisruptDays(shield));
		boolean shieldTarget = shield != null && shieldRoom > 0.01f;
		temp.bombardmentTargets.clear();
		temp.bombardmentTargets.addAll(targets);
		if (shieldTarget) temp.bombardmentTargets.add(shield);

		if (targets.isEmpty() && !shieldTarget) {
			text.addPara(market.getName() + "'s defences are suppressed as far as orbit can "
					+ "push them.");
			addBombardNeverMindOption();
			return;
		}

		// the hive's cheap, surgical bill; vanilla's for a colony - recomputed
		// here on every entry (see class doc re the never-mind cost reset)
		temp.bombardCost = isThreatTarget() ? tacCost() : getBombardmentCost(market, playerFleet);

		float days = ThreatIncConfig.siegeBombardSliceDays();
		float[] est = ThreatGroundFronts.siegeSliceEstimate(playerFleet.getFleetPoints(), market, days);
		int floorPct = Math.round(Math.max(0f, Math.min(1f,
				ThreatIncConfig.fortificationOrbitFloor())) * 100f);
		int fuel = (int) playerFleet.getCargo().getFuel();
		if (!targets.isEmpty()) {
			text.addPara("A tactical bombardment suppresses the following for about %s more days "
					+ "each; orbit cannot take them below %s effect:", Misc.getHighlightColor(),
					"" + Math.round(est[0]), floorPct + "%");
			for (Industry ind : targets) {
				text.addPara("    " + ind.getCurrentName() + " "
						+ Math.round(theatre.condition(market, ind) * 100f) + "%");
			}
		}
		if (shield != null) {
			int onShield = Math.round(Math.min(est[2], shieldRoom));
			if (shieldTarget && onShield > 0) {
				text.addPara("    " + shield.getCurrentName() + " "
						+ Math.round(ThreatShield.integrity(market) * 100f)
						+ "% - it turns %s of that away, and this pass puts %s days on it.",
						Misc.getHighlightColor(),
						Math.round(ThreatShield.absorb(market) * 100f) + "%",
						"" + onShield);
			} else {
				text.addPara("    " + shield.getCurrentName() + " "
						+ Math.round(ThreatShield.integrity(market) * 100f)
						+ "% - it turns %s of that away, and orbit cannot spend it further.",
						Misc.getHighlightColor(),
						Math.round(ThreatShield.absorb(market) * 100f) + "%");
			}
		}
		// only when something actually shoots back: a hive whose only fortification
		// is the Swarm Nexus (no Ground Defenses, no Heavy Batteries) has no
		// batteries' share, so the fleet takes nothing and the line would read 0.0
		if (est[1] >= 0.05f) {
			text.addPara("The batteries answer: about %s fleet points of ships lost, smallest first.",
					Misc.getNegativeHighlightColor(), String.format("%.1f", est[1]));
		}
		// danger close: with a front on the ground the strike is target-marked -
		// it also cracks the deep organs - but the barrage lands among your own
		// positions. Warned here, applied in bombardConfirm.
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (isThreatTarget() && front != null && front.isPlayerOwned()
				&& ThreatIncConfig.frontsEnabled() && ThreatIncConfig.frontDangerCloseEnabled()) {
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
				if (ThreatGroundFronts.siegeDisruptDays(ind) >= dur * 0.8f) continue;
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

		if (siegeSliceApplies() && temp.bombardType == BombardType.TACTICAL) {
			// full vanilla confirm flow (fuel, rep, unrest, listener), then take
			// back the 365-day disruption it wrote and deliver the siege slice
			// instead: the clocks advance by what the fleet's points earn against
			// the defence figure, never past the floor, and the batteries answer.
			// reapply below makes the suppressed defences take effect immediately,
			// not on the next colony poll.
			// capture REAL disruption, not the raw clock: a structure whose
			// disruption was cleared can carry a ghost expire (see
			// ThreatGroundFronts.siegeDisruptDays), and restoring that as a value
			// would resurrect it as genuine disruption the siege then can't touch
			Map<Industry, Float> pre = new LinkedHashMap<Industry, Float>();
			for (Industry ind : temp.bombardmentTargets) {
				pre.put(ind, ThreatGroundFronts.siegeDisruptDays(ind));
			}
			// the defence figure BEFORE the transaction: super.bombardConfirm below
			// disrupts the defence industries for 365 days and our listener reapplies
			// the stat, so a live getDefenderStr inside the slice would read a value
			// crushed to a fraction of the board's until the revert-and-reapply at the
			// end of this branch restores it. Captured here, it matches the estimate.
			float defence = Math.max(0f, getDefenderStr(market, true));
			super.bombardConfirm();
			for (Map.Entry<Industry, Float> entry : pre.entrySet()) {
				entry.getKey().setDisrupted(entry.getValue());
			}
			float days = ThreatIncConfig.siegeBombardSliceDays();
			// instantaneous: nothing to make up, the clocks run down from here
			float loss = ThreatGroundFronts.siegeSlice(playerFleet.getFleetPoints(), market, days,
					false, true, defence);
			List<com.fs.starfarer.api.fleet.FleetMemberAPI> lost =
					new ArrayList<com.fs.starfarer.api.fleet.FleetMemberAPI>();
			ThreatGroundFronts.applyFleetLosses(playerFleet, loss, lost);
			if (!lost.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (com.fs.starfarer.api.fleet.FleetMemberAPI member : lost) {
					if (names.length() > 0) names.append(", ");
					names.append(member.getShipName()).append(" (")
							.append(member.getHullSpec().getHullNameWithDashClass()).append(")");
				}
				text.addPara("Lost to the batteries: " + names + ".",
						Misc.getNegativeHighlightColor());
			}
			if (isThreatTarget()) applyDangerClose();
			market.reapplyIndustries();
			return;
		}

		super.bombardConfirm();
	}

	/**
	 * The disruption a hive saturation bombardment lays down: every industry not
	 * tagged {@code TAG_NO_SATURATION_BOMBARDMENT} - mining, population, spaceport,
	 * defenses, all of it, not just the military structures a ground siege was
	 * already suppressing - has its disruption clock raised TO the hive-short sat
	 * pass ({@code hiveSatDisruptDays} x 1..1.25), never shortened below what a
	 * raid or the ground war already earned. No size reduction: bombardment cannot
	 * kill a hive, only decline can. This is the one place that answer lives, so a
	 * player strike ({@link #threatSatConfirm}) - the swarm's own self-scour
	 * was removed 2026-09-08 - disrupts exactly this set: they
	 * are the same bombardment.
	 */
	public static void applySaturationDisruption(MarketAPI market, java.util.Random random) {
		if (market == null) return;
		if (random == null) random = new java.util.Random();
		// what the shield still turns aside, read before the pass writes anything
		float through = ThreatShield.throughput(market);
		// null with the shield mechanic off, so the loop below disrupts it as it
		// would any other structure - an ordinary industry again
		Industry shield = ThreatShield.present(market) ? ThreatShield.get(market) : null;
		for (Industry curr : market.getIndustries()) {
			if (curr == null || curr.getSpec() == null) continue;
			if (curr.getSpec().hasTag(Industries.TAG_NO_SATURATION_BOMBARDMENT)) continue;
			float dur = ThreatIncConfig.hiveSatDisruptDays()
					* StarSystemGenerator.getNormalRandom(random, 1f, 1.25f);
			// the shield has no cover of its own and takes the pass at full weight
			if (curr == shield) {
				ThreatShield.soakTo(market, dur);
				continue;
			}
			curr.setDisrupted(Math.max(ThreatGroundFronts.siegeDisruptDays(curr), dur * through));
		}
		market.reapplyIndustries();
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
		applySaturationDisruption(market, random);

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
	 * pass's disruption also lands on the Fabrication Core and the port, not
	 * just the war-strata (raised to the pass length like everything else a
	 * bombardment touches, never past it). Called from the tactical bombardConfirm branch,
	 * before the reapply.
	 */
	protected void applyDangerClose() {
		if (!ThreatIncConfig.frontsEnabled()
				|| !ThreatIncConfig.frontDangerCloseEnabled()) return;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null || !front.isPlayerOwned()) return;

		int loss = (int) Math.ceil(front.marines
				* ThreatIncConfig.frontDangerCloseLossFraction());
		// through frontLose, or the pool is left dangling above the headcount
		// and shelling your own troops promotes them (review, 2026-09-08)
		ThreatMarineXP.frontLose(front, loss);

		List<Industry> deep = new ArrayList<Industry>();
		Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
		if (core != null) deep.add(core);
		Industry port = ThreatColonyManager.getPort(market);
		if (port != null) deep.add(port);
		float through = ThreatShield.throughput(market);
		for (Industry ind : deep) {
			float dur = ThreatIncConfig.hiveTacDisruptDays()
					* StarSystemGenerator.getNormalRandom(getRandom(), 1f, 1.25f);
			ind.setDisrupted(Math.max(ThreatGroundFronts.siegeDisruptDays(ind), dur * through));
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
