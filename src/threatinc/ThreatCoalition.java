package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * COALITION - a light form of factions acting together (docs/design-theory.md
 * 8.7, the Stellaris lesson). When a mobilised faction launches a siege
 * against a hive system it posts a CALL for coalitionCallDays; on each slow
 * tick every OTHER mobilised faction with a base in reach that has not yet
 * answered rolls coalitionSupportChance and, on success, sends an Intercept
 * task force to the hive's jump-point (ThreatFleetOrders.dispatchIntercept,
 * provisioned from its own base) so the siege lands under cover.
 *
 * <p>The player coordinates the same way from the war board: RALLY on a hive
 * row batches Intercept orders to every ally that takes the player's orders
 * and a Siege from the best-supplied of them.
 */
public class ThreatCoalition {

	public static final String KEY_CALLS = "threatinc_coalitionCalls";

	public static class Call {
		public String systemId;
		public String callerFactionId;
		public long issuedTimestamp;
		public List<String> answered = new ArrayList<String>();

		public float daysLeft() {
			return Math.max(0f, ThreatIncConfig.coalitionCallDays()
					- Global.getSector().getClock().getElapsedDaysSince(issuedTimestamp));
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Call> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CALLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Call>();
			Global.getSector().getPersistentData().put(KEY_CALLS, val);
		}
		return (List<Call>) val;
	}

	public static Call callFor(String systemId) {
		for (Call c : all()) {
			if (systemId.equals(c.systemId)) return c;
		}
		return null;
	}

	/** A mobilised faction's siege posts (or renews) the call for its target system. */
	public static void post(FactionAPI faction, StarSystemAPI system) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.coalitionEnabled()) return;
		if (faction == null || system == null || !ThreatWarState.isAtWar(faction)) return;
		Call c = callFor(system.getId());
		if (c == null) {
			c = new Call();
			c.systemId = system.getId();
			all().add(c);
		}
		c.callerFactionId = faction.getId();
		c.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		c.answered.clear();
		c.answered.add(faction.getId());
		ThreatIncConfig.log("Coalition call posted by " + faction.getId() + " for "
				+ system.getName());
	}

	/** Slow tick: allies answer open calls; expired calls are dropped. */
	public static void tick(Random random) {
		if (all().isEmpty()) return;
		for (Call c : new ArrayList<Call>(all())) {
			if (c.daysLeft() <= 0f) {
				all().remove(c);
				continue;
			}
			StarSystemAPI system = Global.getSector().getStarSystem(c.systemId);
			if (system == null || ThreatIncData.getLiveColonyMarkets(c.systemId).isEmpty()) {
				all().remove(c);
				continue;
			}
			for (String factionId : ThreatWarState.warFactionIds()) {
				if (c.answered.contains(factionId)) continue;
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue; // the player answers by hand
				MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
				if (base == null) continue;
				if (random.nextFloat() >= ThreatIncConfig.coalitionSupportChance()) continue;
				ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchIntercept(faction, system);
				c.answered.add(factionId);
				if (o != null) {
					ThreatIncConfig.log("Coalition: " + factionId + " answers " + c.callerFactionId
							+ "'s call at " + system.getName());
				}
			}
		}
	}

	/**
	 * The player's RALLY: every ally that takes the player's orders and has a
	 * base in reach sends an Intercept; the one whose base holds the most
	 * marines above its floor also sends a Siege. Returns a short report.
	 */
	public static String rally(StarSystemAPI system, Random random) {
		if (system == null) return null;
		List<String> intercepts = new ArrayList<String>();
		String sieger = null;
		MarketAPI siegeBase = null;
		float bestMarines = 0f;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || !ThreatFleetOrders.canPlayerOrder(faction)) continue;
			MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
			if (base == null) continue;
			if (ThreatFleetOrders.dispatchIntercept(faction, system) != null) {
				intercepts.add(ThreatWarState.displayName(factionId));
			}
			float marines = ThreatReserves.available(base,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
			if (marines > bestMarines) {
				bestMarines = marines;
				sieger = factionId;
				siegeBase = base;
			}
		}
		String siegeReport = "";
		if (sieger != null && siegeBase != null) {
			FactionAPI faction = Global.getSector().getFaction(sieger);
			List<MarketAPI> targets = IncursionManager.collectSiegeTargets(null, system);
			if (!targets.isEmpty()) {
				boolean garrisoned = IncursionManager.anyTargetGarrisoned(targets);
				int difficulty = IncursionManager.computeSiegeDifficulty(targets, garrisoned);
				List<Integer> sizes = IncursionManager.siegeFleetSizes(difficulty, garrisoned,
						false, targets);
				ThreatPurgeFGI purge = IncursionManager.launchSiegeExpedition(siegeBase, faction,
						system, targets, sizes, faction.isPlayerFaction(), random);
				siegeReport = purge != null
						? " " + ThreatWarState.displayName(sieger) + " sends a siege from "
								+ siegeBase.getName() + "."
						: " " + ThreatWarState.displayName(sieger) + " could not raise a landing "
								+ "force at " + siegeBase.getName() + ".";
			}
		}
		if (intercepts.isEmpty() && siegeReport.isEmpty()) return null;
		Call c = callFor(system.getId());
		if (c == null) {
			c = new Call();
			c.systemId = system.getId();
			all().add(c);
		}
		c.callerFactionId = com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER;
		c.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		String report = "Rally for the " + system.getNameWithLowercaseType() + ": "
				+ (intercepts.isEmpty() ? "no ally could send a task force."
						: ThreatWarBoard.join(intercepts) + " send task forces to the jump-point.")
				+ siegeReport;
		ThreatColonyManager.announceAlways(report, Misc.getHighlightColor());
		ThreatIncConfig.log("Rally: " + report);
		return report;
	}

	/** Allies the player could rally against a system right now (for the button's prompt). */
	public static List<String> ralliable(StarSystemAPI system) {
		List<String> result = new ArrayList<String>();
		if (system == null) return result;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || !ThreatFleetOrders.canPlayerOrder(faction)) continue;
			if (ThreatFleetOrders.pickBase(faction, system.getLocation()) == null) continue;
			result.add(ThreatWarState.displayName(factionId));
		}
		return result;
	}
}
