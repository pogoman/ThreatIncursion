package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The hive's garrison fights as one (2026-10-09, hw89a). Garrison swarms orbit
 * their world 400-700 units out on an aggressive orbit, so two of them stand
 * up to 1,400 units apart - outside the engine's battle-join range (settings
 * battleJoinRange 500) - and a swarm that chases a hunter is caught alone on
 * the way back: hw89a's 487 hunt battles met one swarm in 89% of them, a mean
 * 171 FP against 478, with a mean 839 FP of garrison reported standing by,
 * and 303 of them 600+ units from the world. The human hunt is sized against
 * the whole garrison it reads (ThreatSoftening); this makes it meet it.
 *
 * <p>Per frame (IncursionManager.advance, after the leash): a garrison swarm
 * in a battle within {@link #RANGE} of its world pulls the hive's other swarms
 * - on station, alive, in no battle, within {@link #RANGE} of the world - and
 * the reinforcements bound for the world inside its system into the battle
 * (BattleAPI.join), once per battle. A battle the player is in is left to the
 * engine's own join range, so the player's encounter is not reshaped under
 * the dialog.
 */
public class ThreatGarrisonMuster {

	/** How far from its world a garrison swarm's fight still musters the garrison (units). */
	public static final float RANGE = 1500f;

	/** Battles already mustered to; not saved. */
	protected static final Map<BattleAPI, Boolean> MUSTERED = new WeakHashMap<BattleAPI, Boolean>();

	public static void advance() {
		if (!ThreatIncData.isStarted()) return;
		for (String marketId : new ArrayList<String>(ThreatIncData.garrisons().keySet())) {
			List<CampaignFleetAPI> swarms = ThreatIncData.garrisonsFor(marketId);
			BattleAPI battle = null;
			for (CampaignFleetAPI f : swarms) {
				if (f == null || !f.isAlive() || f.getBattle() == null) continue;
				BattleAPI b = f.getBattle();
				if (b.isDone() || MUSTERED.containsKey(b)) continue;
				battle = b;
				break;
			}
			if (battle == null) continue;
			MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
			if (market == null || market.getPrimaryEntity() == null) continue;
			SectorEntityToken planet = market.getPrimaryEntity();
			Vector2f at = battle.computeCenterOfMass();
			if (at == null || Misc.getDistance(at, planet.getLocation()) > RANGE) continue;
			MUSTERED.put(battle, Boolean.TRUE);
			if (battle.isPlayerInvolved()) continue;
			List<CampaignFleetAPI> pool = new ArrayList<CampaignFleetAPI>(swarms);
			pool.addAll(ThreatPosture.boundFor(market));
			int joined = 0;
			float fp = 0f;
			for (CampaignFleetAPI f : pool) {
				if (f == null || !f.isAlive() || f.isExpired() || f.getBattle() != null) continue;
				if (f.getContainingLocation() != planet.getContainingLocation()) continue;
				if (Misc.getDistance(f, planet) > RANGE) continue;
				if (!battle.canJoin(f) || !battle.join(f)) continue;
				joined++;
				fp += f.getFleetPoints();
			}
			if (joined > 0) {
				ThreatIncConfig.log("Garrison musters at " + market.getName() + ": " + joined + " swarm(s), " + (int) fp
						+ " FP join the fight " + (int) Misc.getDistance(at, planet.getLocation()) + " units out");
			}
		}
	}
}
