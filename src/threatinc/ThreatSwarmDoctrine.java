package threatinc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * unit's ship AI is set to the knob's personality ({@code threatinc_swarmDoctrinePersonality},
 * aggressive) with offensive strafing on and backing-off disabled - the configuration vanilla
 * left commented out in {@code ThreatCombatStrategyAI} - and ordered to search and destroy.
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
	/** Ships whose AI config this plugin changed, to restore if a fabricator arrives. */
	private final Set<ShipAPI> touched = new HashSet<ShipAPI>();
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
			if (!(ship.getAI() instanceof ShipAIPlugin)) continue;

			ShipAIConfig config = ((ShipAIPlugin) ship.getAI()).getConfig();
			if (config != null && !personality.equals(config.personalityOverride)) {
				config.personalityOverride = personality;
				config.alwaysStrafeOffensively = true;
				config.backingOffWhileNotVentingAllowed = false;
				config.turnToFaceWithUndamagedArmor = false;
				touched.add(ship);
			}
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

	/** Gives every ship this plugin touched its default AI configuration back. */
	private void restore() {
		if (touched.isEmpty()) return;
		ShipAIConfig def = new ShipAIConfig();
		for (ShipAPI ship : touched) {
			if (ship.getAI() instanceof ShipAIPlugin) {
				ShipAIConfig config = ((ShipAIPlugin) ship.getAI()).getConfig();
				if (config == null) continue;
				config.personalityOverride = null;
				config.alwaysStrafeOffensively = def.alwaysStrafeOffensively;
				config.backingOffWhileNotVentingAllowed = def.backingOffWhileNotVentingAllowed;
				config.turnToFaceWithUndamagedArmor = def.turnToFaceWithUndamagedArmor;
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
