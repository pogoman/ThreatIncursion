package threatinc;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Nexerelin compatibility (docs/nexerelin.md): no Nexerelin invasion lands on
 * a world the swarm is besieging. Soft on both ends - Nexerelin's own market
 * memory flag and reflection, nothing links against ExerelinCore, so the mod
 * loads and builds without it and a Nexerelin update can only switch this off.
 *
 * <p>Nexerelin's AI never targets a hive (Threat is not a playable faction in
 * its config), so only human colonies under a swarm siege need covering. The
 * player's Invade option is greyed in rules.csv (NexPostShowDefenses), not by
 * Nexerelin's uninvadable flag: its liveness check skips flagged markets, so a
 * faction with every world besieged would count as eliminated.
 */
public class ThreatNexCompat {

	/** Nexerelin market memory: NPC invasions, raids and rebellions skip the market. */
	public static final String NEX_NPC_NO_INVADE = "$nex_npc_no_invade";
	/** Market memory: we set NEX_NPC_NO_INVADE, so we clear it; a flag someone else set is left alone. */
	public static final String OUR_MARK = "$threatinc_nexNoInvade";

	private static Boolean nexEnabled = null;

	public static boolean nexEnabled() {
		if (nexEnabled == null) {
			nexEnabled = Global.getSettings().getModManager().isModEnabled("nexerelin");
		}
		return nexEnabled;
	}

	/** A human colony the swarm is besieging: a front on the ground, or its orbit contested. */
	public static boolean besieged(MarketAPI market) {
		if (market == null || !ThreatIncConfig.enabled()) return false;
		if (Factions.THREAT.equals(market.getFactionId())) return false;
		return ThreatGroundFronts.hasFront(market) || ThreatGroundFronts.besieged(market);
	}

	/** From the colony poll: keep the flag on exactly the besieged worlds, then call off what is already under way. */
	public static void poll() {
		if (!nexEnabled()) return;
		Set<MarketAPI> besieged = new HashSet<MarketAPI>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market == null) continue;
			MemoryAPI mem = market.getMemoryWithoutUpdate();
			boolean ours = mem.getBoolean(OUR_MARK);
			if (besieged(market)) {
				besieged.add(market);
				if (ours || mem.contains(NEX_NPC_NO_INVADE)) continue;
				mem.set(NEX_NPC_NO_INVADE, true);
				mem.set(OUR_MARK, true);
				log(market.getName() + " is besieged - closed to invasion.");
			} else if (ours) {
				mem.unset(NEX_NPC_NO_INVADE);
				mem.unset(OUR_MARK);
				log(market.getName() + " is no longer besieged - open to invasion.");
			}
		}
		if (besieged.isEmpty()) return;
		try {
			cancelInvasions(besieged);
			cancelBattles(besieged);
		} catch (Throwable t) {
			// an API change in a later Nexerelin: the flag still stops new ones
			log("could not call off invasions - " + t);
		}
	}

	/**
	 * Invasion fleets already on their way: Nexerelin checks the flag when it
	 * picks a target and never again, so a fleet launched before the siege
	 * still lands. Ended the way Nexerelin ends its own (OTHER outcome).
	 */
	protected static void cancelInvasions(Set<MarketAPI> besieged) throws Exception {
		Class<?> intelClass = Class.forName("exerelin.campaign.intel.invasion.InvasionIntel");
		Class<?> outcomeClass = Class.forName("exerelin.campaign.intel.fleets.OffensiveFleetIntel$OffensiveOutcome");
		Method getTarget = intelClass.getMethod("getTarget");
		Method terminate = intelClass.getMethod("terminateEvent", outcomeClass);
		Object other = enumValue(outcomeClass, "OTHER");
		List<IntelInfoPlugin> list = Global.getSector().getIntelManager().getIntel(intelClass);
		for (IntelInfoPlugin intel : list) {
			if (intel.isEnding() || intel.isEnded()) continue;
			MarketAPI target = (MarketAPI) getTarget.invoke(intel);
			if (!besieged.contains(target)) continue;
			log("calling off an invasion of " + target.getName()
					+ " - the swarm is besieging it.");
			terminate.invoke(intel, other);
		}
	}

	/**
	 * Ground battles the AI started before the siege: cancelled (no transfer,
	 * Nexerelin's own CANCELLED path). A battle the player started or joined is
	 * left to run - their marines are on the ground.
	 */
	protected static void cancelBattles(Set<MarketAPI> besieged) throws Exception {
		Class<?> intelClass = Class.forName("exerelin.campaign.intel.groundbattle.GroundBattleIntel");
		Class<?> outcomeClass = Class.forName("exerelin.campaign.intel.groundbattle.GroundBattleIntel$BattleOutcome");
		Method getMarket = intelClass.getMethod("getMarket");
		Method playerInitiated = intelClass.getMethod("isPlayerInitiated");
		Method playerAttacker = intelClass.getMethod("isPlayerAttacker");
		Method endBattle = intelClass.getMethod("endBattle", outcomeClass);
		Object cancelled = enumValue(outcomeClass, "CANCELLED");
		List<IntelInfoPlugin> list = Global.getSector().getIntelManager().getIntel(intelClass);
		for (IntelInfoPlugin intel : list) {
			if (intel.isEnding() || intel.isEnded()) continue;
			MarketAPI market = (MarketAPI) getMarket.invoke(intel);
			if (!besieged.contains(market)) continue;
			if (Boolean.TRUE.equals(playerInitiated.invoke(intel))) continue;
			if (playerAttacker.invoke(intel) != null) continue;
			log("cancelling the ground battle on " + market.getName()
					+ " - the swarm is besieging it.");
			endBattle.invoke(intel, cancelled);
		}
	}

	/** Always on, unlike ThreatIncConfig.log: these are rare, and they are what a Nexerelin player's bug report needs. */
	private static void log(String msg) {
		Global.getLogger(ThreatNexCompat.class).info("[ThreatInc] Nexerelin compat: " + msg);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static Object enumValue(Class<?> enumClass, String name) {
		return Enum.valueOf((Class<? extends Enum>) enumClass, name);
	}
}
