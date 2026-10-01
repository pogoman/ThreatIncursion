package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * THE FACTIONS' STANCE (2026-10-01, user's call: the factions get the hive's
 * reach and stance systems). Each mobilised NPC faction keeps one of the
 * hive's three stances (ThreatStance), read from what is true of it:
 *
 * <ul>
 * <li>CONSOLIDATE - the swarm presses it (it was struck within PRESSURE_DAYS,
 * or a Threat front stands on one of its worlds) and it is losing the
 * exchange or outweighed by the swarm facing it: it founds no forward bases,
 * and its sieges go only at hive systems that face it - spoiling blows.</li>
 * <li>PRESS - not pressed, it outweighs the swarm facing it by
 * stancePressRatio, and one of its bases can take and pay for a siege of a
 * hive system now: that system is weighed first and its bases sail first
 * (tryPurgeBombardments); forward bases get stanceSecondaryShare of its
 * founding passes.</li>
 * <li>EXPAND - otherwise: forward bases toward the hive, sieges as they come.</li>
 * </ul>
 *
 * <p>Read every EVAL_DAYS. A stance holds stanceDwellDays before it changes,
 * except into CONSOLIDATE, as the hive's does. Priorities, never limits:
 * nothing here caps a fleet or a launch. Primitive state only
 * (threatinc_factionStance, threatinc_factionTrend); the picked targets are
 * rebuilt on the first read after a load.
 */
public class ThreatFactionStance {

	public static final int EXPAND = ThreatStance.EXPAND, PRESS = ThreatStance.PRESS,
			CONSOLIDATE = ThreatStance.CONSOLIDATE;
	protected static final String[] NAMES = { "EXPAND", "PRESS", "CONSOLIDATE" };

	/** Faction id -> float[]{stance, day entered}. */
	public static final String KEY_STATE = "threatinc_factionStance";
	/** Faction id -> float[]{its FP lost, Threat FP it sank, day}; decays over TREND_DAYS. */
	public static final String KEY_TREND = "threatinc_factionTrend";

	/** Days between reads. */
	protected static final float EVAL_DAYS = 7f;
	/** A strike on the faction this recent presses it. */
	protected static final float PRESSURE_DAYS = 60f;
	/** Days over which the exchange ledger falls by a factor of e (ThreatStance's). */
	protected static final float TREND_DAYS = 60f;

	/** Faction id -> the hive system id it presses. Not saved: rebuilt each read. */
	protected static final Map<String, String> TARGET = new HashMap<String, String>();
	/** Faction id -> day last read. Not saved: a load reads afresh. */
	protected static final Map<String, Float> READ_AT = new HashMap<String, Float>();

	public static boolean enabled() {
		return ThreatIncConfig.factionStanceEnabled() && ThreatWarState.enabled();
	}

	/** Called on load: the picks are the abandoned game's. */
	public static void forget() {
		TARGET.clear();
		READ_AT.clear();
	}

	protected static Map<String, float[]> map(String key) {
		return ThreatIncData.map(key);
	}

	protected static float today() {
		return Global.getSector().getClock().getTimestamp() / 86400000f;
	}

	/** The faction's stance; EXPAND when off, unread or the player's. */
	public static int stance(String factionId) {
		if (!enabled() || factionId == null) return EXPAND;
		float[] s = map(KEY_STATE).get(factionId);
		return s != null && s.length >= 2 ? (int) s[0] : EXPAND;
	}

	public static String stanceName(String factionId) {
		return NAMES[stance(factionId)];
	}

	// ------------------------------------------------------------------
	// what the rest of the war reads
	// ------------------------------------------------------------------

	/** Whether the faction founds a forward base this pass: expanding always, pressing on stanceSecondaryShare of passes, consolidating never. */
	public static boolean foundsLinks(FactionAPI faction, Random random) {
		if (faction == null) return true;
		int s = stance(faction.getId());
		if (s == CONSOLIDATE) return false;
		if (s == PRESS) {
			float share = Math.max(0f, Math.min(1f, ThreatIncConfig.stanceSecondaryShare()));
			return (random != null ? random.nextFloat() : (float) Math.random()) < share;
		}
		return true;
	}

	/** Whether the faction sieges this hive system: always, but consolidating only one that faces it. */
	public static boolean siegeAllowed(FactionAPI faction, StarSystemAPI system) {
		if (faction == null || system == null) return true;
		if (stance(faction.getId()) != CONSOLIDATE) return true;
		return faction.getId().equals(ThreatReach.facedFaction(system));
	}

	/** The hive system the faction presses, or null. */
	public static String target(String factionId) {
		return stance(factionId) == PRESS ? TARGET.get(factionId) : null;
	}

	/** The hives with a pressed system first, each group in its order (easiestFirst's). */
	public static List<MarketAPI> pressedFirst(List<MarketAPI> hives) {
		if (!enabled() || TARGET.isEmpty()) return hives;
		java.util.Set<String> pressed = new java.util.HashSet<String>();
		for (Map.Entry<String, String> e : TARGET.entrySet()) {
			if (stance(e.getKey()) == PRESS) pressed.add(e.getValue());
		}
		List<MarketAPI> first = new ArrayList<MarketAPI>(), rest = new ArrayList<MarketAPI>();
		for (MarketAPI m : hives) {
			if (m.getStarSystem() != null && pressed.contains(m.getStarSystem().getId())) first.add(m);
			else rest.add(m);
		}
		first.addAll(rest);
		return first;
	}

	/** The bases of factions pressing this system first, each group in its order (cheapestFirst's). */
	public static List<MarketAPI> pressingFirst(StarSystemAPI system, List<MarketAPI> bases) {
		if (!enabled() || system == null || TARGET.isEmpty()) return bases;
		List<MarketAPI> first = new ArrayList<MarketAPI>(), rest = new ArrayList<MarketAPI>();
		for (MarketAPI b : bases) {
			if (system.getId().equals(target(b.getFactionId()))) first.add(b);
			else rest.add(b);
		}
		first.addAll(rest);
		return first;
	}

	// ------------------------------------------------------------------
	// the exchange (ThreatAbstractBattle.fought, ThreatPosture.noteBattle)
	// ------------------------------------------------------------------

	/** Books a fight: the faction's FP lost and the Threat FP it sank. */
	public static void noteTrend(String factionId, float lost, float killed) {
		if (factionId == null || Factions.THREAT.equals(factionId) || (lost <= 0f && killed <= 0f)) return;
		float day = today();
		float[] t = trend(factionId, day);
		map(KEY_TREND).put(factionId, new float[] { t[0] + Math.max(0f, lost), t[1] + Math.max(0f, killed), day });
	}

	/** {lost, killed} decayed to this day. */
	protected static float[] trend(String factionId, float day) {
		float[] t = map(KEY_TREND).get(factionId);
		if (t == null || t.length < 3 || t[2] > day) return new float[] { 0f, 0f };
		float k = (float) Math.exp(-Math.max(0f, day - t[2]) / TREND_DAYS);
		return new float[] { t[0] * k, t[1] * k };
	}

	// ------------------------------------------------------------------
	// the read
	// ------------------------------------------------------------------

	/** Reads every mobilised faction whose last read is EVAL_DAYS old (or never, since the load). */
	public static void refresh() {
		if (!enabled()) return;
		float day = today();
		for (String fid : ThreatWarState.warFactionIds()) {
			if (Factions.PLAYER.equals(fid) || Factions.THREAT.equals(fid) || ThreatWarState.excluded(fid)) continue;
			Float at = READ_AT.get(fid);
			if (at != null && day - at < EVAL_DAYS && day >= at) continue;
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null) continue;
			READ_AT.put(fid, day);
			evaluate(faction, day);
		}
	}

	/**
	 * One faction's read: how pressed it is, the exchange, its force against
	 * the swarm facing it, and the weakest hive system it could take now; then
	 * the pick, logged with its reasons when it changes.
	 */
	protected static void evaluate(FactionAPI faction, float day) {
		String fid = faction.getId();
		// pressed: struck lately, or a Threat front on one of its worlds
		ThreatWarState.FactionWar war = ThreatWarState.get(fid);
		float sinceStruck = war != null && war.lastStruckTimestamp > 0L
				? Global.getSector().getClock().getElapsedDaysSince(war.lastStruckTimestamp) : Float.MAX_VALUE;
		int fronts = 0;
		for (ThreatGroundFronts.GroundFront f : ThreatGroundFronts.fronts().values()) {
			if (f == null || !Factions.THREAT.equals(ThreatGroundFronts.ownerOf(f))) continue;
			MarketAPI m = Global.getSector().getEconomy().getMarket(f.marketId);
			if (m != null && fid.equals(m.getFactionId())) fronts++;
		}
		boolean pressed = sinceStruck < PRESSURE_DAYS || fronts > 0;

		// the exchange
		float[] t = trend(fid, day);
		float ours = forceFP(faction);
		boolean losing = t[0] > 0f && t[0] > t[1] && t[0] >= 0.05f * Math.max(1f, ours);

		// the swarm facing it: every hive system that would strike it first
		float theirs = 0f;
		java.util.Set<String> seen = new java.util.HashSet<String>();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || !seen.add(sys.getId())) continue;
			if (!fid.equals(ThreatReach.facedFaction(sys))) continue;
			for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(sys.getId())) {
				theirs += ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
			}
		}
		float ratio = theirs > 0f ? ours / theirs : (ours > 0f ? Float.MAX_VALUE : 0f);

		// the weakest hive system one of its bases could take and pay for now
		Object[] best = weakestTarget(faction);

		float[] st = map(KEY_STATE).get(fid);
		boolean hadState = st != null && st.length >= 2 && st[1] <= day;
		int was = hadState ? (int) st[0] : EXPAND;
		float since = hadState ? st[1] : day;
		float pressEnter = Math.max(0f, ThreatIncConfig.stancePressRatio());
		float pressNeed = was == PRESS ? pressEnter * ThreatStance.LEAVE : pressEnter;
		boolean wantConsolidate = pressed && (losing || ratio < 1f);
		boolean wantPress = !pressed && !losing && best != null && ratio >= pressNeed;
		int next = wantConsolidate ? CONSOLIDATE : wantPress ? PRESS : EXPAND;
		if (next != was && next != CONSOLIDATE && hadState
				&& day - since < Math.max(0f, ThreatIncConfig.stanceDwellDays())) {
			next = was;
		}
		if (next == PRESS && best != null) TARGET.put(fid, (String) best[0]);
		else TARGET.remove(fid);
		if (next != was || !hadState) {
			if (next != was) since = day;
			ThreatIncConfig.log("Faction stance: " + fid + " " + NAMES[was] + "->" + NAMES[next] + " - "
					+ (pressed ? "pressed (struck " + (sinceStruck < Float.MAX_VALUE ? (int) sinceStruck + " d ago" : "never")
							+ ", " + fronts + " Threat fronts)" : "not pressed")
					+ "; exchange lost " + (int) t[0] + " sank " + (int) t[1]
					+ "; force " + (int) ours + " vs " + (int) theirs + " FP facing it ("
					+ (ratio >= Float.MAX_VALUE ? "inf" : String.format("%.2f", ratio)) + ")"
					+ (best != null ? "; weakest target " + best[1] + " (score " + String.format("%.2f", (Float) best[2]) + ")"
							: "; no siege it can pay for"));
		}
		map(KEY_STATE).put(fid, new float[] { next, since });
	}

	/**
	 * The FP the faction could field: its fleets out (anything provisioned
	 * from a base, ThreatReturns) and what its depots' supplies above their
	 * floors pay for at a siege's price a fleet point - the hulls' deposit and
	 * a siege's stay of upkeep (siegeOrbitDays).
	 */
	protected static float forceFP(FactionAPI faction) {
		String fid = faction.getId();
		float fp = 0f;
		for (LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI f : loc.getFleets()) {
				if (f == null || !f.isAlive() || f.isStationMode() || f.getFaction() == null) continue;
				if (!fid.equals(f.getFaction().getId()) || ThreatReturns.homeOf(f) == null) continue;
				fp += f.getFleetPoints();
			}
		}
		float perFP = ThreatIncConfig.expeditionSuppliesPerPoint() / ThreatGroundFronts.ABSTRACT_FP_PER_POINT
				+ ThreatReach.suppliesPerFP(fid) * ThreatIncConfig.siegeOrbitDays() / 30f;
		if (perFP <= 0f) return fp;
		float supplies = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
			if (ThreatReserves.get(m.getId()) == null) continue;
			supplies += ThreatReserves.available(m, Commodities.SUPPLIES);
		}
		return fp + supplies / perFP;
	}

	/**
	 * {system id, name, score} of the hive system a base of the faction in
	 * reach - the cheapest that can - takes and pays for now
	 * (IncursionManager.siegeAffordable) with
	 * the most worth for the least odds per day away: its targets' sizes x (1 -
	 * the swarms weighed over the flotilla) over the siege's days
	 * (siegeTripDays). Null when no siege of any hive is in its means.
	 */
	protected static Object[] weakestTarget(FactionAPI faction) {
		Object[] best = null;
		float bestScore = 0f;
		java.util.Set<String> seen = new java.util.HashSet<String>();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || !seen.add(sys.getId())) continue;
			if (!ThreatScouts.sectorKnows(hive)) continue;
			List<MarketAPI> mine = new java.util.ArrayList<MarketAPI>();
			for (MarketAPI b : IncursionManager.siegeBasesFor(sys)) {
				if (faction.getId().equals(b.getFactionId())) mine.add(b);
			}
			if (mine.isEmpty()) continue;
			// every base it could sail from, cheapest first, as tryPurgeBombardments
			// tries them: read from the nearest alone, PRESS all but never fired
			// while a farther base launched (review, 2026-10-01)
			MarketAPI base = null;
			List<MarketAPI> targets = null;
			for (MarketAPI b : IncursionManager.cheapestFirst(sys, mine)) {
				List<MarketAPI> t = IncursionManager.siegeTargets(b, faction, sys);
				if (t.isEmpty() || !IncursionManager.anySiegeReady(t)) continue;
				if (!IncursionManager.siegeAffordable(b, faction, sys, t)) continue;
				base = b;
				targets = t;
				break;
			}
			if (base == null) continue;
			List<Integer> sizes = IncursionManager.siegeSizesFor(base, faction, targets, 0f);
			float fp = ThreatAidCapacity.expeditionPoints(sizes);
			if (fp <= 0f) continue;
			float odds = Math.min(1f, IncursionManager.siegeOrbitWeighed(faction, targets) / fp);
			float value = 0f;
			for (MarketAPI t : targets) value += t.getSize();
			List<MarketAPI> land = IncursionManager.landTargets(targets,
					IncursionManager.razeWorlds(base, faction, sys, targets));
			float days = Math.max(1f, IncursionManager.siegeTripDays(base, sys,
					IncursionManager.siegeStayDays(land, fp)));
			float score = value * (1f - odds) / days;
			if (best == null || score > bestScore) {
				bestScore = score;
				best = new Object[] { sys.getId(), sys.getName(), score };
			}
		}
		return best;
	}
}
