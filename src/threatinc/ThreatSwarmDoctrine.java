package threatinc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.combat.threat.ThreatCombatStrategyAI;
import com.fs.starfarer.api.impl.combat.threat.ThreatShipConstructionScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * The swarm's battle doctrine, chosen by what it has deployed (the user, 2026-11-08: "the doctrine
 * should be chosen based on the fleet composition"). Vanilla's {@link ThreatCombatStrategyAI}
 * anchors a fleet on its fabricators and hives and defends them; a fleet with none - every small
 * swarm - gets no orders from it at all: the admiral is muted, the defend waypoints and captures
 * are dropped, and the ships fight one by one on the doctrine's cautious personality, unable to
 * retreat, and end the battle pinned on their own edge.
 * <p>
 * This plugin fills that branch. Each second, on the enemy side of a battle against the Threat:
 * a fabricator or hive deployed - nothing, vanilla's defence stands (and any ship this plugin
 * touched earlier is given back its default AI); none - the swarm doctrine: every Threat combat
 * unit gets a new default ship AI built on the knob's personality
 * ({@code threatinc_swarmDoctrinePersonality}, aggressive) with offensive strafing on and
 * backing-off disabled - the configuration vanilla left commented out in
 * {@code ThreatCombatStrategyAI}, which does nothing set on a live AI's config - is ordered
 * to search and destroy, and carries every second the AI flags vanilla's own aggro mode uses
 * (DO_NOT_BACK_OFF; DO_NOT_VENT behind {@code threatinc_swarmDoctrineNoVent}) plus, behind
 * {@code threatinc_swarmDoctrineRange}, a forced engagement range from its target.
 * Overseers keep vanilla's escort behaviour; fighters and ships under construction are left
 * alone. Retreat stays off (vanilla's {@code ThreatFIDConfig}). The player's side is never
 * touched, so a converted hull the player flies keeps its officer's personality.
 * <p>
 * Registered in data/config/settings.json ("plugins"); off with {@code threatinc_swarmDoctrine}.
 */
public class ThreatSwarmDoctrine extends BaseEveryFrameCombatPlugin {

	private static final Logger log = Global.getLogger(ThreatSwarmDoctrine.class);

	private CombatEngineAPI engine;
	private final IntervalUtil tick = new IntervalUtil(0.8f, 1.2f);
	/** How long each tick's AI flags last - longer than the tick, so they never lapse. */
	private static final float FLAG_SECONDS = 2f;
	/** Ships whose AI this plugin replaced, with the AI they had, to restore if a fabricator arrives. */
	private final Map<ShipAPI, ShipAIPlugin> touched = new HashMap<ShipAPI, ShipAIPlugin>();
	private boolean swarming = false;
	private boolean threatBattle = true;
	private boolean checkedFaction = false;

	@Override
	public void init(CombatEngineAPI engine) {
		this.engine = engine;
		touched.clear();
		swarming = false;
		checkedFaction = false;
		threatBattle = true;
	}

	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		if (engine == null || engine.isPaused()) return;
		if (Global.getCurrentState() != GameState.COMBAT) return;
		if (!threatBattle) return;
		tick.advance(amount);
		if (!tick.intervalElapsed()) return;
		if (!ThreatIncConfig.swarmDoctrine()) return;

		if (!checkedFaction) {
			checkedFaction = true;
			// a campaign battle against anyone but the Threat is not this plugin's; the simulator
			// and missions have no context and are judged by the hullmod alone
			if (engine.getContext() != null) {
				CampaignFleetAPI other = engine.getContext().getOtherFleet();
				if (other != null && other.getFaction() != null
						&& !Factions.THREAT.equals(other.getFaction().getId())) {
					threatBattle = false;
					return;
				}
			}
		}

		CombatFleetManagerAPI fm = engine.getFleetManager(FleetSide.ENEMY);
		if (fm == null) return;
		List<DeployedFleetMemberAPI> deployed = fm.getDeployedCopyDFM();
		if (deployed.isEmpty()) return;

		int anchors = 0;
		int threatShips = 0;
		for (DeployedFleetMemberAPI m : deployed) {
			ShipAPI ship = m.getShip();
			if (m.isFighterWing() || ship == null || ship.getVariant() == null) continue;
			if (!ship.getVariant().hasHullMod(HullMods.THREAT_HULLMOD)) continue;
			threatShips++;
			if (ThreatCombatStrategyAI.isFabricator(ship) || ThreatCombatStrategyAI.isHive(ship)) anchors++;
		}
		if (threatShips == 0) return;

		if (anchors > 0) {
			if (swarming) {
				log.info("Swarm doctrine off: a fabricator or hive is deployed, " + touched.size() + " ships restored");
				swarming = false;
			}
			restore();
			return;
		}

		String personality = personality();
		CombatTaskManagerAPI tasks = fm.getTaskManager(false);
		int ordered = 0;
		for (DeployedFleetMemberAPI m : deployed) {
			ShipAPI ship = m.getShip();
			if (m.isFighterWing() || ship == null || ship.getVariant() == null || ship.getAI() == null) continue;
			if (!ship.getVariant().hasHullMod(HullMods.THREAT_HULLMOD)) continue;
			if (ship.hasTag(ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) continue;
			if (ThreatCombatStrategyAI.isOverseer(ship)) continue;
			if (!ThreatCombatStrategyAI.isCombatUnit(ship)) continue;

			// the ship AI reads its personality when it is built, not from its config each frame
			// (tested 2026-11-08: setting personalityOverride on the live config changed nothing),
			// so the ship gets a new default AI with the swarm configuration baked in, once
			if (!touched.containsKey(ship)) {
				ShipAIConfig config = new ShipAIConfig();
				config.personalityOverride = personality;
				config.alwaysStrafeOffensively = true;
				config.backingOffWhileNotVentingAllowed = false;
				config.turnToFaceWithUndamagedArmor = false;
				ShipAIPlugin old = ship.getShipAI();
				ShipAIPlugin swarm = Global.getSettings().createDefaultShipAI(ship, config);
				if (swarm == null) continue;
				ship.setShipAI(swarm);
				touched.put(ship, old);
			}
			// the flags vanilla's own "aggro mode" sets when enemies close on a fabricator - the one
			// recipe in its Threat script that makes these ships attack (second test 2026-11-08: a
			// rebuilt aggressive AI on search and destroy still stood off in a ring at 2-3k, the ship
			// AI backing off against a heavier enemy); refreshed each tick, they outlast the interval
			ship.getAIFlags().setFlag(AIFlags.DO_NOT_BACK_OFF, FLAG_SECONDS);
			if (ThreatIncConfig.swarmDoctrineNoVent()) ship.getAIFlags().setFlag(AIFlags.DO_NOT_VENT, FLAG_SECONDS);
			float range = ThreatIncConfig.swarmDoctrineRange();
			if (range > 0f) ship.getAIFlags().setFlag(AIFlags.MANEUVER_RANGE_FROM_TARGET, FLAG_SECONDS, range);
			if (tasks != null) {
				AssignmentInfo a = tasks.getAssignmentFor(ship);
				if (a == null || a.getType() != CombatAssignmentType.SEARCH_AND_DESTROY) {
					tasks.orderSearchAndDestroy(m, false);
					ordered++;
				}
			}
		}
		if (!swarming) {
			log.info("Swarm doctrine on: " + threatShips + " Threat ships, no fabricator or hive; personality "
					+ personality + ", " + ordered + " ordered to search and destroy");
			swarming = true;
		}
	}

	/** Gives every ship this plugin touched its original AI back. */
	private void restore() {
		if (touched.isEmpty()) return;
		for (Map.Entry<ShipAPI, ShipAIPlugin> e : touched.entrySet()) {
			ShipAPI ship = e.getKey();
			if (ship.isHulk() || ship.isAlive() == false) continue;
			if (e.getValue() != null) {
				ship.setShipAI(e.getValue());
			} else {
				ship.setShipAI(Global.getSettings().createDefaultShipAI(ship, new ShipAIConfig()));
			}
		}
		touched.clear();
	}

	/** The knob's personality id, aggressive unless it names one of vanilla's. */
	static String personality() {
		String p = ThreatIncConfig.swarmDoctrinePersonality();
		if (p == null) return Personalities.AGGRESSIVE;
		p = p.trim().toLowerCase();
		if (p.equals(Personalities.RECKLESS) || p.equals(Personalities.AGGRESSIVE)
				|| p.equals(Personalities.STEADY) || p.equals(Personalities.CAUTIOUS)
				|| p.equals(Personalities.TIMID)) return p;
		return Personalities.AGGRESSIVE;
	}
}
