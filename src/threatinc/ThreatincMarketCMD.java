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
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
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
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * MarketCMD override: the player's bombardment of any world, and the ground
 * operations and defender rule of a Threat colony's military options
 * (docs/suppression-balance.md "v2", 2026-09-28).
 *
 * <ul>
 * <li><b>Tactical</b>: a day of the siege slice every besieger flies
 * ({@link ThreatGroundFronts#siegeSlice}): the fleet's points against the
 * defence figure suppress the fortifications by what still stands of them,
 * the guns answer with ships lost, fuel is the ordnance (per fleet point),
 * and the world's unrest is raised to what the bombardment has broken.</li>
 * <li><b>Saturation</b>: the same day on every building, and the fuel poured
 * into the colony's razing bar ({@link ThreatRazing}) - a level a size, the
 * last ends the colony. A hive has no bar ({@link ThreatRazing#razes}): over
 * it saturation is the day alone, and takes nothing off its size.</li>
 * <li>Both share a once-a-day lock, like vanilla's raid cooldown.</li>
 * </ul>
 *
 * <p>Also waives the saturation-bombardment atrocity penalty when the bombed
 * colony belongs to the Threat: vanilla {@code bombardSaturation} builds
 * {@code temp.willBecomeHostile} from each faction's caresAboutAtrocities
 * flag, never checking who owned the target.
 *
 * <p>Wired from this mod's {@code rules.csv}: higher-scored overrides of the
 * vanilla {@code mktBombard*} rules invoke this class instead
 * ({@code DialogOptionSelected} fires only the best-scoring rule). With the
 * mod off everything delegates to vanilla; with it on, the bombardment is this
 * class's on every world whose menu it runs (Nexerelin runs human colonies').
 *
 * <p>{@code temp} is shared state stored in market memory ($MarketCMD_temp), so
 * what one rule invocation writes (e.g. the day's fuel set in
 * {@code bombardTactical}) is exactly what a later invocation's
 * {@code bombardConfirm} reads. Re-selecting either bombardment type always
 * re-enters our overrides, which recompute it.
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
	 * until they arrive. But whether they CLOSE raid and bombardment is the
	 * navy's own question, asked the navy's own way (2026-10-13 evening, the
	 * user: "if my AI navy can bombard, I should be able to as well"):
	 * ThreatGroundFronts.orbitContestedFor(player) - armed swarm fleets within
	 * ORBIT_HOLD_RANGE weigh orbitContestFraction of the player faction's
	 * warships there, the player's own fleet and the navy alike. Held: raid and
	 * bombardment closed. Not held: vanilla's own verdict stands, raid cooldown
	 * included, busy or not (two scraps of 7 and 12 FP kept off by six
	 * bombarding navy fleets had locked the menu at Delta Mengryla I).
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
		int far = 0;
		float fp = 0f;
		float nearest = Float.MAX_VALUE;
		float farthest = Float.MAX_VALUE; // the nearest of the fleets too far out to count
		float range = ThreatIncConfig.defendInboundRange();
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
			} else if (range > 0f && dist > range) {
				// not in the fight: beyond the inbound range it neither closes the
				// menu nor can be engaged (2026-10-13: engaged reinforcements drawn
				// 13-15,000 units off locked raids and bombardment for weeks)
				far++;
				farthest = Math.min(farthest, dist);
				state = "far";
			} else {
				inbound++;
				nearest = Math.min(nearest, dist);
				state = "inbound";
			}
			why.append(" [").append(fleet.getName()).append(" ").append((int) fleet.getFleetPoints())
					.append(" FP ").append((int) dist).append(" su ").append(state).append("]");
		}
		// the navy's gate, verbatim (ThreatPurgeFGI.SiegeRaidAction.orbitHeld)
		float friendlyFP = ThreatGroundFronts.friendlyPointsNear(Factions.PLAYER, market);
		float swarmFP = ThreatGroundFronts.hostilePointsNear(Factions.PLAYER, market);
		boolean held = ThreatGroundFronts.orbitContestedFor(Factions.PLAYER, market);
		ThreatIncConfig.log("Military options at " + market.getName() + ": near " + near
				+ ", inbound " + inbound + ", busy " + busy + ", far " + far + why
				+ " - swarm " + (int) swarmFP + " FP vs ours " + (int) friendlyFP
				+ " at the orbit: " + (held ? "held" : "not held"));
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
			} else if (far > 0) {
				text.addPara("The swarm's nearest fleet is %s units out: not in the fight.",
						Misc.getHighlightColor(), Misc.getWithDGS((int) farthest));
			}
			if (!held && (near > 0 || inbound > 0 || busy > 0)) {
				text.addPara("Your side holds the orbit: %s fleet points against the swarm's %s.",
						Misc.getHighlightColor(), Misc.getWithDGS((int) friendlyFP),
						Misc.getWithDGS((int) swarmFP));
			}
		}
		if (DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(RAID, true);
			options.setEnabled(BOMBARD, true);
			options.setEnabled(ENGAGE, true);
			return;
		}
		boolean defended = held;
		if (defended) {
			temp.canRaid = false;
			temp.canBombard = false;
		}
		options.setEnabled(RAID, temp.canRaid);
		if (defended) {
			options.setTooltip(RAID, "The presence of enemy fleets that are willing "
					+ "to offer battle makes a raid impossible.");
		}
		if (isThreatTarget()) {
			// nothing to raid on a hive (phase D): the strata are its everything, and a landing takes them
			options.setEnabled(RAID, false);
			options.setTooltip(RAID, "Nothing to raid: the hive is its strata. Land ground forces to take them.");
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
		} else if (inbound > 0 && held) {
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
			if (!temp.canRaid) {
				options.setEnabled(GROUND_DEPLOY, false);
				options.setTooltip(GROUND_DEPLOY,
						"Defending fleets must be dealt with before landing ground forces.");
			} else if (marines < (int) ThreatIncConfig.frontMinMarines()) {
				options.setEnabled(GROUND_DEPLOY, false);
				options.setTooltip(GROUND_DEPLOY, "Too few marines aboard to hold any "
						+ "ground (need at least "
						+ (int) ThreatIncConfig.frontMinMarines() + ").");
			} else {
				int toll = Math.round(ThreatGroundFronts.dropLoss(market, marines));
				if (toll > 0) options.setTooltip(GROUND_DEPLOY, "The batteries will take about "
						+ Misc.getWithDGS(toll) + " of the " + Misc.getWithDGS(marines) + " marines on the drop.");
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
				state = "GAINING GROUND - the hive's organs are suppressed";
			} else if (ThreatGroundFronts.STATE_GRINDING.equals(front.state)) {
				state = "GRINDING - the line stands; the outer defenses are harassed";
			} else {
				state = "LOSING GROUND - too weak to suppress anything";
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
		int marines = (int) playerFleet.getCargo().getMarines();
		int arms = (int) playerFleet.getCargo()
				.getCommodityQuantity(Commodities.HAND_WEAPONS);
		if (!ThreatIncConfig.frontsEnabled() || !temp.canRaid
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
		marines = ThreatGroundFronts.dropped(market, marines, Factions.PLAYER); // the guns' toll (2026-10-18)
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.deploy(market,
				Factions.PLAYER, marines, arms, landingLevel);
		// a landing is an act of war (docs/suppression-balance.md v2 section 7):
		// vanilla's bombardment impact, and never covert
		CustomRepImpact impact = new CustomRepImpact();
		impact.delta = market.getSize() * -0.01f;
		impact.ensureAtBest = RepLevel.HOSTILE;
		Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, true),
				market.getFactionId());
		// classify now, so the board reads the front's state before its first poll
		front.state = ThreatGroundFronts.lineSuppressionState(ThreatGroundFronts.lineRate(front, market));
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
		marines = ThreatGroundFronts.dropped(market, marines, Factions.PLAYER); // the guns' toll (2026-10-18)
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

	/** Fuel a day of tactical bombardment by the player's fleet costs: its points' worth, less vanilla's specialised bombardment capability. */
	protected int tacticalFuel() {
		float fuel = ThreatGroundFronts.bombardFuelPerDay(market, playerFleet.getFleetPoints()) - bombardBonus();
		return Math.max(0, Math.round(fuel));
	}

	/** Vanilla's specialised fleet bombardment capability: fuel off each bombardment. */
	protected float bombardBonus() {
		return Misc.getFleetwideTotalMod(playerFleet, Stats.FLEET_BOMBARD_COST_REDUCTION, 0f);
	}

	/** Fuel a day of saturation pours: the fleet's rate, what it carries, never more than the colony needs. */
	protected float saturationPour() {
		return ThreatRazing.deliverable(market, playerFleet.getFleetPoints(), 1f,
				playerFleet.getCargo().getFuel());
	}

	/** What that day costs from the tanks: the pour, never less than a tactical day unless it finishes the razing, less the bombardment capability. */
	protected int saturationFuel(float pour) {
		boolean finishes = ThreatRazing.razes(market)
				&& pour >= ThreatRazing.fuelToDestroyThrough(market) - 0.5f;
		float spent = finishes ? pour
				: Math.max(pour, ThreatGroundFronts.bombardFuelPerDay(playerFleet.getFleetPoints()));
		spent = Math.min(spent, playerFleet.getCargo().getFuel()) - bombardBonus();
		return Math.max(0, Math.round(spent));
	}

	protected static String pct(float fraction) {
		return Math.round(fraction * 100f) + "%";
	}

	/** Whether the player's fleet may organize a bombardment now (the once-a-day lock). */
	protected boolean bombardReady() {
		return DebugFlags.MARKET_HOSTILITIES_DEBUG || !ThreatGroundFronts.bombardLocked();
	}

	protected static final String LOCKED = "Your forces need a day to organize another bombardment.";

	/**
	 * The bombardment menu (docs/suppression-balance.md v2): vanilla's brief of
	 * the defence figure, then one line per fact - what a day of each kind
	 * burns, what the fleet carries - and the once-a-day lock both kinds share.
	 * A bombardment is a day of sorties: fuel is the ordnance, per fleet point.
	 */
	@Override
	protected void bombardMenu() {
		if (!ThreatIncConfig.enabled()) {
			super.bombardMenu();
			return;
		}
		if (isThreatTarget()) {
			strataBombardMenu();
			return;
		}
		Color h = Misc.getHighlightColor();
		dialog.getVisualPanel().showImagePortion("illustrations", "bombard_prepare", 640, 400, 0, 0, 480, 300);

		StatBonus defender = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		temp.defenderStr = Math.round(defender.computeEffective(0f));
		TooltipMakerAPI info = text.beginTooltip();
		info.setParaSmallInsignia();
		float initPad = 0f;
		if (!faction.isHostileTo(Factions.PLAYER)) {
			info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " " + faction.getDisplayNameIsOrAre()
					+ " not currently hostile. A bombardment can't be concealed, whatever the transponder says.",
					initPad, faction.getBaseUIColor(), faction.getDisplayNameWithArticleWithoutArticle());
			initPad = 10f;
		}
		info.addPara("Ground defense strength: %s", initPad, h, "" + (int) temp.defenderStr);
		info.addStatModGrid(350f, 50f, 10f, 5f, defender, true, statPrinter(true));
		text.addTooltip();

		int fuel = (int) playerFleet.getCargo().getFuel();
		int tac = tacticalFuel();
		float satRate = Math.max(0f, ThreatIncConfig.satFuelPerFPDay()) * playerFleet.getFleetPoints();
		text.addPara("Tactical bombardment: %s fuel a day.", h, Misc.getWithDGS(tac));
		text.addPara("Saturation bombardment: up to %s fuel a day.", h, Misc.getWithDGS(Math.round(satRate)));
		text.addPara("You have %s fuel.", h, Misc.getWithDGS(fuel));
		boolean ready = bombardReady();
		if (!ready) text.addPara("Your forces will be able to organize another bombardment within a day or so.");

		options.clearOptions();
		options.addOption("Prepare a tactical bombardment", BOMBARD_TACTICAL);
		options.addOption("Prepare a saturation bombardment", BOMBARD_SATURATION);
		options.addOption("Go back", RAID_GO_BACK);
		options.setShortcut(RAID_GO_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
		// state after shape (CLAUDE.md "Option panels")
		boolean debug = DebugFlags.MARKET_HOSTILITIES_DEBUG;
		if (!ready) {
			options.setEnabled(BOMBARD_TACTICAL, false);
			options.setTooltip(BOMBARD_TACTICAL, LOCKED);
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, LOCKED);
			return;
		}
		if (!ThreatGroundFronts.bombardable(market)) {
			options.setEnabled(BOMBARD_TACTICAL, false);
			options.setTooltip(BOMBARD_TACTICAL, "No defence structure to bombard.");
		} else if (fuel < tac && !debug) {
			options.setEnabled(BOMBARD_TACTICAL, false);
			options.setTooltip(BOMBARD_TACTICAL, "Not enough fuel.");
		}
		if (fuel < Math.max(1, tac) && !debug) {
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, "Not enough fuel.");
		}
	}

	/**
	 * A day of tactical bombardment, before it is flown: each fortification's
	 * condition now and after the day, the shield's, the ships the return fire
	 * would take by name, the unrest the day raises, the fuel. The same
	 * figures {@link ThreatGroundFronts#siegeSlice} writes on the confirm.
	 */
	@Override
	protected void bombardTactical() {
		if (!ThreatIncConfig.enabled()) {
			super.bombardTactical();
			return;
		}
		if (!isThreatTarget()) { // v3 (the user, 2026-10-27): a human world is bombarded as vanilla does it
			super.bombardTactical();
			return;
		}
		temp.bombardType = BombardType.TACTICAL;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);
		temp.bombardmentTargets.clear();
		if (!ThreatGroundFronts.bombardable(market)) {
			text.addPara("There is nothing here for a tactical bombardment to suppress.");
			addBombardNeverMindOption();
			return;
		}
		Color h = Misc.getHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		ThreatGroundFronts.Theatre theatre = ThreatGroundFronts.Theatre.of(market);
		List<Industry> forts = theatre.fortifications(market);
		temp.bombardmentTargets.addAll(forts);
		// recomputed on every entry (see the class doc on the never-mind reset)
		temp.bombardCost = tacticalFuel();
		ThreatGroundFronts.BombardDay day = ThreatGroundFronts.bombardDay(playerFleet.getFleetPoints(), market, false);

		text.addPara("A day of tactical bombardment:");
		for (Industry ind : forts) {
			text.addPara("    " + ind.getCurrentName() + " %s to %s", h, pct(theatre.condition(market, ind)),
					pct(ThreatGroundFronts.conditionAfterDay(market, ind, day.rate, day.through)));
		}
		if (day.shieldNow >= 0f) {
			text.addPara("    " + ThreatShield.get(market).getCurrentName() + " %s to %s, turning aside %s of the day.",
					h, pct(day.shieldNow), pct(day.shieldAfter), pct(ThreatShield.absorb(market)));
		}
		for (String line : ThreatGroundFronts.lossLines(playerFleet, day.returnFire)) text.addPara(line, bad);
		if (day.unrest > 0) text.addPara("Unrest raised to %s.", h, "" + day.unrest);
		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("The bombardment requires %s fuel. You have %s fuel.", h,
				"" + temp.bombardCost, "" + fuel);
		addBombardConfirmOptions();
		gateConfirm(fuel >= temp.bombardCost);
	}

	/**
	 * The bombardment menu over a hive (phase D, docs/strata-are-the-threat.md 6): one bombardment,
	 * strata for fuel, one line per fact - no tactical, no saturation, no return fire, no unrest.
	 */
	protected void strataBombardMenu() {
		Color h = Misc.getHighlightColor();
		dialog.getVisualPanel().showImagePortion("illustrations", "bombard_prepare", 640, 400, 0, 0, 480, 300);
		float fp = playerFleet.getFleetPoints();
		ThreatGroundFronts.BombardDay day = ThreatGroundFronts.bombardDay(fp, market, true);
		int fuel = (int) playerFleet.getCargo().getFuel();
		text.addPara("Strata: %s, size %s.", h, ThreatStrata.fmt(day.strataNow), "" + market.getSize());
		text.addPara("A day of bombardment by your %s fleet points razes %s strata for %s fuel.", h,
				Misc.getWithDGS((int) fp), ThreatStrata.fmt(day.strata), Misc.getWithDGS(Math.round(day.fuel)));
		text.addPara("You have %s fuel.", h, Misc.getWithDGS(fuel));
		boolean ready = bombardReady();
		if (!ready) text.addPara("Your forces will be able to organize another bombardment within a day or so.");
		options.clearOptions();
		options.addOption("Prepare a bombardment", BOMBARD_SATURATION);
		options.addOption("Go back", RAID_GO_BACK);
		options.setShortcut(RAID_GO_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
		// state after shape (CLAUDE.md "Option panels")
		boolean debug = DebugFlags.MARKET_HOSTILITIES_DEBUG;
		if (!ready) {
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, LOCKED);
		} else if (!temp.canBombard && !debug) {
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, "Defending fleets must be dealt with before bombarding.");
		} else if (day.strata <= 0f) {
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, "Nothing left to raze.");
		} else if (fuel < Math.max(1, Math.round(ThreatGroundFronts.fuelPerStratum())) && !debug) {
			options.setEnabled(BOMBARD_SATURATION, false);
			options.setTooltip(BOMBARD_SATURATION, "Not enough fuel.");
		}
	}

	/** The day before it is flown, over a hive: the strata it razes, the size they leave, the fuel from the tanks. */
	/**
	 * THE SAT BOMB over a hive (the user, 2026-10-27, docs/suppression-balance.md 10): what it razes,
	 * what it disrupts, what it costs. No atrocity, no stability.
	 */
	protected void satBombPrompt() {
		temp.bombardType = BombardType.SATURATION;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);
		temp.bombardmentTargets.clear();
		Color h = Misc.getHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		float fp = playerFleet.getFleetPoints();
		int fuel = (int) playerFleet.getCargo().getFuel();
		float strata = ThreatSatBomb.strata(market, fp);
		float now = ThreatStrata.of(market);
		temp.bombardCost = Math.max(0, Math.round(ThreatSatBomb.price(market, fp)));
		float left = now - strata;
		text.addPara("A saturation bombardment:");
		if (left <= 0f) {
			text.addPara("    " + market.getName() + " is destroyed.", bad);
		} else {
			int sizeAfter = ThreatStrata.sizeOf(left, ThreatColonyManager.maxColonySize(market));
			if (sizeAfter < market.getSize()) {
				text.addPara("    Razes %s of %s strata: size %s to %s.", h, ThreatStrata.fmt(strata),
						ThreatStrata.fmt(now), "" + market.getSize(), "" + sizeAfter);
			} else {
				text.addPara("    Razes %s of %s strata.", h, ThreatStrata.fmt(strata), ThreatStrata.fmt(now));
			}
			String names = ThreatSatBomb.targetNames(market);
			if (names.length() > 0) {
				text.addPara("    Disrupts " + names + " for %s days.", h, "" + (int) ThreatSatBomb.disruptDays());
			}
		}
		text.addPara("The bombardment requires %s fuel. You have %s fuel.", h,
				Misc.getWithDGS(temp.bombardCost), Misc.getWithDGS(fuel));
		addBombardConfirmOptions();
		gateConfirm(fuel >= Math.max(1, temp.bombardCost));
	}

	protected void strataBombardPrompt() {
		temp.bombardType = BombardType.SATURATION;
		temp.willBecomeHostile.clear();
		temp.willBecomeHostile.add(faction);
		temp.bombardmentTargets.clear();
		Color h = Misc.getHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		float fp = playerFleet.getFleetPoints();
		int fuel = (int) playerFleet.getCargo().getFuel();
		ThreatGroundFronts.BombardDay day = ThreatGroundFronts.bombardDay(fp, market, true);
		// what the tanks pay for: the day's strata, or what the fuel aboard buys of them
		float strata = day.strata;
		if (ThreatGroundFronts.fuelPerStratum() > 0f) {
			strata = Math.min(strata, fuel / ThreatGroundFronts.fuelPerStratum());
		}
		temp.bombardCost = Math.max(0, Math.round(strata * ThreatGroundFronts.fuelPerStratum()));
		float left = day.strataNow - strata;
		text.addPara("A day of bombardment:");
		if (left <= 0f) {
			text.addPara("    " + market.getName() + " is destroyed.", bad);
		} else {
			int sizeAfter = ThreatStrata.sizeOf(left, ThreatColonyManager.maxColonySize(market));
			if (sizeAfter < market.getSize()) {
				text.addPara("    Razes %s of %s strata: size %s to %s.", h, ThreatStrata.fmt(strata),
						ThreatStrata.fmt(day.strataNow), "" + market.getSize(), "" + sizeAfter);
			} else {
				text.addPara("    Razes %s of %s strata.", h, ThreatStrata.fmt(strata), ThreatStrata.fmt(day.strataNow));
			}
		}
		text.addPara("The bombardment requires %s fuel. You have %s fuel.", h,
				Misc.getWithDGS(temp.bombardCost), Misc.getWithDGS(fuel));
		addBombardConfirmOptions();
		gateConfirm(fuel >= Math.max(1, temp.bombardCost));
	}

	/** The confirm's state, set after its shape: the day's lock, then the fuel. */
	protected void gateConfirm(boolean fuelEnough) {
		if (!bombardReady()) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, LOCKED);
		} else if (!fuelEnough && !DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			options.setEnabled(BOMBARD_CONFIRM, false);
			options.setTooltip(BOMBARD_CONFIRM, "Not enough fuel.");
		}
	}

	/**
	 * A day of saturation bombardment, before it is flown
	 * (docs/suppression-balance.md v2 section 4): what the fuel does to the
	 * colony's levels - the bombs fall on the owner's, a front's layers count
	 * as already lost - how long the rest would take at this rate, the
	 * defences the day wears, the ships the guns would take by name, the
	 * unrest, and who it makes hostile.
	 */
	@Override
	protected void bombardSaturation() {
		if (!ThreatIncConfig.enabled()) {
			super.bombardSaturation();
			return;
		}
		if (isThreatTarget()) {
			if (ThreatSatBomb.enabled()) satBombPrompt(); else strataBombardPrompt();
			return;
		}
		// v3 (the user, 2026-10-27): a human world is bombarded as vanilla does it
		super.bombardSaturation();
		return;
	}

	@Override
	protected void bombardConfirm() {
		if (!ThreatIncConfig.enabled()) {
			super.bombardConfirm();
			return;
		}
		if (!isThreatTarget()) { // v3: vanilla's confirm for vanilla's bombardment of a human world
			super.bombardConfirm();
			return;
		}
		if (temp.bombardType == null) {
			bombardNeverMind();
			return;
		}
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
		}
		bombardDay(temp.bombardType == BombardType.SATURATION);
	}

	/** Market memory: this saturation campaign has been counted as an atrocity (a month without saturation ends it). */
	public static final String ATROCITY_KEY = "$threatinc_satAtrocity";

	/**
	 * One day of the player's bombardment (docs/suppression-balance.md v2):
	 * vanilla's confirm - hostility timeouts, the military response, the
	 * reputation hit, pollution, the listeners - around the day itself: the
	 * slice (tactical) or the saturation slice with its razing, the ships the
	 * guns took by name, the unrest raised rather than stacked, and the
	 * once-a-day lock.
	 */
	protected void bombardDay(boolean saturation) {
		Color h = Misc.getHighlightColor();
		Color bad = Misc.getNegativeHighlightColor();
		dialog.getVisualPanel().showImagePortion("illustrations",
				saturation ? "bombard_saturation_result" : "bombard_tactical_result",
				640, 400, 0, 0, 480, 300);
		if (!DebugFlags.MARKET_HOSTILITIES_DEBUG) {
			float timeout = saturation ? SATURATION_BOMBARD_TIMEOUT_DAYS : TACTICAL_BOMBARD_TIMEOUT_DAYS;
			Misc.increaseMarketHostileTimeout(market, timeout);
			timeout *= 0.7f;
			for (MarketAPI curr : Global.getSector().getEconomy().getMarkets(market.getContainingLocation())) {
				if (curr == market) continue;
				boolean cares = saturation
						&& curr.getFaction().getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES);
				if (curr.getFaction().isNeutralFaction()) continue;
				if (curr.getFaction().isPlayerFaction()) continue;
				if (curr.getFaction().isHostileTo(market.getFaction()) && !cares) continue;
				Misc.increaseMarketHostileTimeout(curr, timeout);
			}
		}
		addMilitaryResponse();

		// read before the day writes anything
		float fp = playerFleet.getFleetPoints();
		float defence = Math.max(0f, getDefenderStr(market, true));
		float suppression = ThreatGroundFronts.suppressionRate(market, fp, defence);
		float fuelAboard = playerFleet.getCargo().getFuel();
		int unrestBefore = RecentUnrest.getPenalty(market);
		String name = market.getName();
		int sizeBefore = market.getSize();

		int cost = Math.min((int) fuelAboard, temp.bombardCost);
		playerFleet.getCargo().removeFuel(cost);
		AddRemoveCommodity.addCommodityLossText(Commodities.FUEL, cost, text);

		for (FactionAPI curr : temp.willBecomeHostile) {
			CustomRepImpact impact = new CustomRepImpact();
			impact.delta = market.getSize() * -0.01f;
			impact.ensureAtBest = RepLevel.HOSTILE;
			if (saturation && curr == faction) impact.ensureAtBest = RepLevel.VENGEFUL;
			Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, true), curr.getId());
		}
		boolean satBomb = saturation && isThreatTarget() && ThreatSatBomb.enabled();
		if (saturation && !satBomb) countAtrocity(); // the sat bomb on a hive is no atrocity (the user, 2026-10-27)
		if (!isThreatTarget() && market.hasCondition(Conditions.HABITABLE) && !market.hasCondition(Conditions.POLLUTION)) {
			market.addCondition(Conditions.POLLUTION);
		}

		String reason = Misc.isPlayerFactionSetUp() ? playerFaction.getDisplayName() + " bombardment"
				: "Recently bombarded";
		float loss;
		boolean destroyed = false;
		float strataRazed = 0f;
		int disrupted = 0;
		if (satBomb) {
			float[] out = ThreatSatBomb.drop(market, fp, Factions.PLAYER, "Player bombardment");
			loss = 0f;
			strataRazed = out[0];
			disrupted = (int) out[1];
			destroyed = out[2] > 0f;
		} else if (saturation) {
			// over a hive the fuel is the prompt's price (phase D): strata for fuel, and no more than paid
			float[] out = ThreatGroundFronts.saturationSlice(fp, market, 1f, isThreatTarget() ? cost : fuelAboard, true, false,
					defence, Factions.PLAYER, reason);
			loss = out[0];
			destroyed = out[3] > 0f;
			if (isThreatTarget() && ThreatGroundFronts.fuelPerStratum() > 0f) strataRazed = out[1] / ThreatGroundFronts.fuelPerStratum();
		} else {
			loss = ThreatGroundFronts.siegeSlice(fp, market, 1f, true, false, defence, reason);
		}
		List<FleetMemberAPI> lost = new ArrayList<FleetMemberAPI>();
		ThreatGroundFronts.applyFleetLosses(playerFleet, loss, lost);
		if (!lost.isEmpty()) {
			StringBuilder names = new StringBuilder();
			for (FleetMemberAPI member : lost) {
				if (names.length() > 0) names.append(", ");
				names.append(ThreatGroundFronts.shipName(member));
			}
			text.addPara("Lost to return fire: " + names + ".", bad);
		}

		if (destroyed) {
			text.addPara(name + " destroyed.");
		} else {
			if (strataRazed > 0f) {
				text.addPara("Razed %s strata; %s stand.", h, ThreatStrata.fmt(strataRazed), ThreatStrata.fmt(ThreatStrata.of(market)));
			}
			if (disrupted > 0) {
				text.addPara("Disrupted %s industries for %s days.", h, "" + disrupted, "" + (int) ThreatSatBomb.disruptDays());
			}
			if (market.getSize() < sizeBefore) {
				text.addPara("Colony size reduced to %s.", bad, "" + market.getSize());
			}
			int raised = RecentUnrest.getPenalty(market) - unrestBefore;
			if (raised > 0) {
				text.addPara("Stability of " + name + " reduced by %s.", h, "" + raised);
			}
			if (!ThreatGroundFronts.Theatre.of(market).fortifications(market).isEmpty()) {
			}
			// the suppressed defences in force now, not on the next colony poll
			ThreatGroundFronts.syncSiegeState(market);
			market.reapplyIndustries();
		}
		ThreatGroundFronts.lockBombard();

		if (saturation) {
			ListenerUtil.reportSaturationBombardmentFinished(dialog, market, temp);
		} else {
			ListenerUtil.reportTacticalBombardmentFinished(dialog, market, temp);
		}
		if (dialog != null && dialog.getPlugin() instanceof RuleBasedDialog) {
			if (dialog.getInteractionTarget() != null
					&& dialog.getInteractionTarget().getMarket() != null) {
				Global.getSector().setPaused(false);
				dialog.getInteractionTarget().getMarket().getMemoryWithoutUpdate().advance(0.0001f);
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
	 * Vanilla's atrocity bookkeeping, once a campaign rather than once a day: a
	 * razing is one atrocity however many days it takes (a month without
	 * saturation ends the campaign). Nothing for exterminating the swarm under
	 * the waiver.
	 */
	protected void countAtrocity() {
		if (waiveAtrocity()) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = market.getMemoryWithoutUpdate();
		boolean counted = mem.getBoolean(ATROCITY_KEY);
		mem.set(ATROCITY_KEY, true, 30f);
		if (counted) return;
		com.fs.starfarer.api.campaign.rules.MemoryAPI player = Global.getSector().getCharacterData()
				.getMemoryWithoutUpdate();
		player.set(MemFlags.PLAYER_ATROCITIES, (int) player.getFloat(MemFlags.PLAYER_ATROCITIES) + 1);
		if (market.getFaction() != null) {
			com.fs.starfarer.api.campaign.rules.MemoryAPI fmem = market.getFaction().getMemoryWithoutUpdate();
			fmem.set(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER,
					fmem.getInt(MemFlags.FACTION_SATURATION_BOMBARED_BY_PLAYER) + 1);
		}
	}
}
