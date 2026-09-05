package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * WAR MODE - the gate on the whole strategy layer (docs/strategy-layer.md).
 *
 * <p>NPC factions behave exactly as vanilla until the Threat strikes one of
 * their colonies. That strike mobilises the faction: from then on its
 * colonies accrue visible reserves ({@link ThreatReserves}), its logistics
 * run ({@link ThreatConvoys}), its expeditions draw real troops from those
 * reserves, and its fleets appear on the war board. Nothing about a faction
 * that has not been struck is touched. The player's faction mobilises the
 * same way, when the swarm first strikes a player world.
 *
 * <p>Stateless like the other managers: the record lives in persistent data.
 */
public class ThreatWarState {

	public static final String KEY_WARS = "threatinc_factionWar";

	/** One mobilised faction. Serialized into the save via persistent data. */
	public static class FactionWar {
		public String factionId;
		public long enteredTimestamp;
		public long lastStruckTimestamp;
		public String lastStruckMarketId;
		public int strikesSuffered;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, FactionWar> wars() {
		Object val = Global.getSector().getPersistentData().get(KEY_WARS);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, FactionWar>();
			Global.getSector().getPersistentData().put(KEY_WARS, val);
		}
		return (Map<String, FactionWar>) val;
	}

	/** The master switch: with the layer off nobody is ever "at war". */
	public static boolean enabled() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.strategyEnabled();
	}

	public static boolean isAtWar(String factionId) {
		return enabled() && factionId != null && wars().containsKey(factionId);
	}

	public static boolean isAtWar(FactionAPI faction) {
		return faction != null && isAtWar(faction.getId());
	}

	public static FactionWar get(String factionId) {
		if (factionId == null) return null;
		return wars().get(factionId);
	}

	/** Ids of every mobilised faction, in mobilisation order. */
	public static List<String> warFactionIds() {
		if (!enabled()) return new ArrayList<String>();
		return new ArrayList<String>(wars().keySet());
	}

	/**
	 * A Threat strike has been launched at this colony: its faction is at war
	 * from this moment. Called from IncursionManager.launchStrike for NPC and
	 * player worlds alike. Records the strike either way.
	 */
	public static void recordStrike(MarketAPI struck) {
		if (!enabled() || struck == null) return;
		FactionAPI faction = struck.getFaction();
		if (faction == null || Factions.THREAT.equals(faction.getId())) return;
		String id = faction.getId();
		long now = Global.getSector().getClock().getTimestamp();
		FactionWar war = wars().get(id);
		boolean entering = war == null;
		if (entering) {
			war = new FactionWar();
			war.factionId = id;
			war.enteredTimestamp = now;
			wars().put(id, war);
		}
		war.lastStruckTimestamp = now;
		war.lastStruckMarketId = struck.getId();
		war.strikesSuffered++;
		if (entering) {
			ThreatReserves.seed(id);
			String who = faction.isPlayerFaction() ? "Your faction"
					: Misc.ucFirst(faction.getDisplayNameWithArticle());
			ThreatColonyManager.announceAlways(who + " has mobilised for war against the "
					+ "Threat: its colonies now stock marines, armaments, fuel and "
					+ "supplies for the war effort, and ship them to the front.",
					Misc.getHighlightColor());
			ThreatIncConfig.log("War mode: " + id + " mobilised (struck at "
					+ struck.getName() + ")");
		}
	}

	/** Persistent marker: the one-time backfill below has run for this save. */
	public static final String KEY_BACKFILL = "threatinc_factionWarBackfill";

	/**
	 * Existing saves: war mode is recorded when a strike LAUNCHES, so a save
	 * updated mid-war has expeditions in flight and task forces out against
	 * factions nobody flagged. Once per save, mobilise every faction that is
	 * evidently already fighting - a target of any strike in flight, or the
	 * owner of any task force out - and count those strikes. Also catches a
	 * strike that slipped through while the layer was disabled.
	 */
	public static void backfill() {
		if (!enabled()) return;
		Object done = Global.getSector().getPersistentData().get(KEY_BACKFILL);
		if (done instanceof Boolean && (Boolean) done) return;
		Global.getSector().getPersistentData().put(KEY_BACKFILL, true);
		int mobilised = 0;
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI)) continue;
			com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI fgi =
					(com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI) curr;
			if (fgi.isEnded() || fgi.isEnding()) continue;
			if (fgi.getParams() == null || fgi.getParams().raidParams == null) continue;
			for (MarketAPI target : fgi.getParams().raidParams.allowedTargets) {
				if (target == null || target.getFaction() == null) continue;
				boolean was = isAtWar(target.getFactionId());
				recordStrike(target);
				if (!was && isAtWar(target.getFactionId())) mobilised++;
			}
		}
		for (Object curr : IncursionManager.getResponseList()) {
			if (!(curr instanceof ThreatResponseIntel)) continue;
			ThreatResponseIntel r = (ThreatResponseIntel) curr;
			if (r.isEnded() || r.isEnding() || r.getFaction() == null) continue;
			String id = r.getFaction().getId();
			if (isAtWar(id) || Factions.THREAT.equals(id)) continue;
			// mobilise without a strike record: the task force IS the evidence
			FactionWar war = new FactionWar();
			war.factionId = id;
			war.enteredTimestamp = Global.getSector().getClock().getTimestamp();
			war.lastStruckTimestamp = war.enteredTimestamp;
			wars().put(id, war);
			ThreatReserves.seed(id);
			mobilised++;
		}
		ThreatIncConfig.log("War mode backfill: " + mobilised + " faction(s) mobilised from "
				+ "expeditions and task forces already in flight");
	}

	/**
	 * Stand-down check, on the fast poll. With warModeStandDownDays at 0 a
	 * mobilised faction stays mobilised for the rest of the game; otherwise it
	 * stands down that many days after its last strike, provided no live hive
	 * remains within expedition range of any of its military worlds. Reserves
	 * are kept, frozen, when it does.
	 */
	public static void poll() {
		if (!enabled()) return;
		backfill();
		float days = ThreatIncConfig.warModeStandDownDays();
		if (days <= 0f) return;
		for (String id : new ArrayList<String>(wars().keySet())) {
			FactionWar war = wars().get(id);
			if (war == null) continue;
			float since = Global.getSector().getClock().getElapsedDaysSince(war.lastStruckTimestamp);
			if (since < days) continue;
			FactionAPI faction = Global.getSector().getFaction(id);
			if (faction != null && hiveInReach(faction)) continue;
			wars().remove(id);
			String who = faction == null ? id : faction.isPlayerFaction() ? "Your faction"
					: Misc.ucFirst(faction.getDisplayNameWithArticle());
			ThreatColonyManager.announce(who + " has stood down from war footing - no "
					+ "hive remains in reach and the swarm has not struck for "
					+ (int) days + " days.", Misc.getHighlightColor());
			ThreatIncConfig.log("War mode: " + id + " stood down");
		}
	}

	/** Whether any live hive system lies within expedition range of one of the faction's military worlds. */
	public static boolean hiveInReach(FactionAPI faction) {
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (!IncursionManager.hasMilitary(market)) continue;
			if (market.getStarSystem() == null) continue;
			float range = IncursionManager.expeditionRangeLY(market);
			for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
				StarSystemAPI system = hive.getStarSystem();
				if (system == null) continue;
				if (Misc.getDistanceLY(market.getStarSystem().getLocation(),
						system.getLocation()) <= range) {
					return true;
				}
			}
		}
		return false;
	}

	public static String displayName(String factionId) {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction == null) return factionId;
		return faction.isPlayerFaction() ? "Your faction" : faction.getDisplayName();
	}
}
