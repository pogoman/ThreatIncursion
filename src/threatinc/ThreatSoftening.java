package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * HUNTING FORCES (2026-09-24, docs/strategy-layer.md "Hunting forces"): a
 * mobilised NPC faction softens a bountied hive (ThreatSwarmBountyIntel)
 * with its own ships. For each hive system with a running swarm bounty, every
 * mobilised NPC faction's nearest base in reach (ThreatFleetOrders.pickBase)
 * sends a hunting force - unless that base has a hive of its own to siege
 * (IncursionManager.hasSiegeableHive): the siege always comes first.
 *
 * <p>A hunting force carries no marines and lands nothing, so every point is a
 * warship: it is sized to beat the whole system's Defense Swarms
 * (softenMargin), up to softenMaxFP - above a siege's own cap - split
 * into fleets of at most softenFleetFP. It is paid for as a siege is, in fuel
 * and supplies from the base's reserve, and it does not sail unless the base
 * can pay for enough to beat the weakest colony's garrison, nor when that
 * garrison alone is beyond softenMaxFP. It works the
 * system's colonies weakest garrison first ({@link ThreatFleetOrders#KIND_HUNT}
 * orders - the player's Hunt flies the same order), moves on when a garrison
 * is gone, and goes home when its
 * softenDays run out, the system's swarms are gone, or it has lost more than
 * softenRetreatStrength of itself. One force per faction per system; a base
 * waits softenIntervalDays between forces.
 */
public class ThreatSoftening {

	public static final String KEY_LAST = "threatinc_softenLast";

	/** Base market id -> when it last sent a hunting force. */
	public static Map<String, Long> lastSent() {
		return ThreatIncData.map(KEY_LAST);
	}

	public static void tick(Random random) {
		if (!ThreatIncConfig.softenEnabled()) return;
		advanceHunts();
		List<ThreatSwarmBountyIntel> bounties = ThreatSwarmBountyIntel.running();
		if (bounties.isEmpty()) return;
		Collections.shuffle(bounties, random);
		for (ThreatSwarmBountyIntel bounty : bounties) {
			StarSystemAPI system = ThreatWarBoard.getSystem(bounty.getSystemId());
			if (system == null) continue;
			for (String factionId : ThreatWarState.warFactionIds()) {
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue;
				if (hunting(factionId, system.getId())) continue;
				MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
				if (base == null) continue;
				Long last = lastSent().get(base.getId());
				if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
						< ThreatIncConfig.softenIntervalDays()) {
					continue;
				}
				if (IncursionManager.hasSiegeableHive(base)) continue;
				send(faction, base, system);
			}
		}
	}

	/** Whether the faction already has a hunting force in the system. */
	protected static boolean hunting(String factionId, String systemId) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind) || !factionId.equals(o.factionId)) continue;
			MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (hive != null && hive.getStarSystem() != null && systemId.equals(hive.getStarSystem().getId())) {
				return true;
			}
		}
		return false;
	}

	/** Defense Swarm points over one colony. */
	protected static float garrisonFP(MarketAPI hive) {
		return IncursionManager.siegeOrbitFP(Collections.singletonList(hive));
	}

	/** Where a hunt in this system starts: the colony with the weakest standing garrison, or null when no colony has one. */
	public static MarketAPI huntTarget(String systemId) {
		return weakest(systemId);
	}

	/** The colony of the system with the weakest standing garrison, or null when none has one. */
	protected static MarketAPI weakest(String systemId) {
		MarketAPI best = null;
		float bestFP = Float.MAX_VALUE;
		for (MarketAPI hive : ThreatIncData.getLiveColonyMarkets(systemId)) {
			if (hive.getPrimaryEntity() == null) continue;
			float fp = garrisonFP(hive);
			if (fp <= 0f) continue;
			if (fp < bestFP) {
				bestFP = fp;
				best = hive;
			}
		}
		return best;
	}

	/** Combat points the base's reserve can pay a force to this system for (fuel for the distance, supplies for the hulls). */
	protected static float payableFP(MarketAPI base, StarSystemAPI system) {
		float dist = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
		float fuelPerPoint = dist * ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float points = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) points = Math.min(points, ThreatReserves.available(base, Commodities.FUEL) / fuelPerPoint);
		if (suppliesPerPoint > 0f) {
			points = Math.min(points, ThreatReserves.available(base, Commodities.SUPPLIES) / suppliesPerPoint);
		}
		return points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
	}

	protected static void send(FactionAPI faction, MarketAPI base, StarSystemAPI system) {
		MarketAPI first = weakest(system.getId());
		if (first == null) return;
		float margin = Math.max(0f, ThreatIncConfig.softenMargin());
		float floor = garrisonFP(first) * margin;
		if (floor > ThreatIncConfig.softenMaxFP()) {
			ThreatIncConfig.log("Hunting force stays at " + base.getName() + ": " + first.getName()
					+ "'s swarms need " + (int) floor + " FP, above softenMaxFP");
			return;
		}
		float want = IncursionManager.siegeOrbitFP(IncursionManager.collectSiegeTargets(null, system)) * margin;
		want = Math.max(floor, Math.min(want, ThreatIncConfig.softenMaxFP()));
		float payable = payableFP(base, system);
		if (payable < floor) {
			ThreatIncConfig.log("Hunting force waits at " + base.getName() + ": pays for " + (int) payable
					+ " FP, " + first.getName() + "'s swarms need " + (int) floor);
			return;
		}
		float fp = Math.min(want, payable);
		int fleets = Math.max(1, (int) Math.ceil(fp / Math.max(50f, ThreatIncConfig.softenFleetFP())));
		int sent = 0;
		for (int i = 0; i < fleets; i++) {
			if (ThreatFleetOrders.dispatchHunt(faction, base, first, fp / fleets) != null) sent++;
		}
		if (sent == 0) return;
		lastSent().put(base.getId(), Global.getSector().getClock().getTimestamp());
		ThreatColonyManager.announceAlways(Misc.ucFirst(faction.getDisplayNameWithArticle())
				+ " has sent a hunting force from " + base.getName() + " against the Defense Swarms in the "
				+ system.getNameWithLowercaseTypeShort() + ".", Misc.getHighlightColor());
		ThreatIncConfig.log("Hunting force from " + base.getName() + " to " + system.getName() + ": " + sent
				+ " fleets, ~" + (int) fp + " FP (wanted " + (int) want + ", pays for " + (int) payable
				+ ") against " + first.getName() + " first");
	}

	/**
	 * Hunting fleets on station move on when their colony's swarms are gone,
	 * and go home when the whole system is clear or they are badly hurt.
	 */
	protected static void advanceHunts() {
		for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (!ThreatFleetOrders.KIND_HUNT.equals(o.kind)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			boolean player = Global.getSector().getPlayerFaction().getId().equals(o.factionId);
			if (ThreatReturns.health(o.fleet) < ThreatIncConfig.softenRetreatStrength()) {
				if (player) {
					ThreatColonyManager.announceAlways(o.fleet.getName() + " is badly hurt and breaks off "
							+ "the hunt over " + o.targetName + ".", Misc.getNegativeHighlightColor());
				}
				ThreatFleetOrders.standDown(o, "badly hurt");
				continue;
			}
			MarketAPI hive = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (hive != null && isHive(hive) && garrisonFP(hive) > 0f) continue;
			String systemId = hive != null && hive.getStarSystem() != null ? hive.getStarSystem().getId() : null;
			MarketAPI next = systemId != null ? weakest(systemId) : null;
			if (next == null) {
				if (player) {
					ThreatColonyManager.announceAlways(o.fleet.getName() + " has hunted down the Defense "
							+ "Swarms and is heading home.", Misc.getHighlightColor());
				}
				ThreatFleetOrders.standDown(o, "the swarms are gone");
			} else {
				if (player) {
					ThreatColonyManager.announceAlways(o.fleet.getName() + " moves on to hunt the swarms "
							+ "over " + next.getName() + ".", Misc.getHighlightColor());
				}
				ThreatFleetOrders.retargetHunt(o, next);
				ThreatIncConfig.log("Hunting fleet moves on to " + next.getName());
			}
		}
	}

	protected static boolean isHive(MarketAPI market) {
		return Factions.THREAT.equals(market.getFactionId());
	}
}
