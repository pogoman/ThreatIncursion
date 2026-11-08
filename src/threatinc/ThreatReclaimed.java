package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import org.lwjgl.util.vector.Vector2f;

/**
 * THE RECLAIMED-HULL LEDGER (the user, 2026-11-07: converted hulls are "ships of the fleets they
 * destroyed", so track which). Each hive world remembers the shapes of the human warships the
 * swarm has eaten: a count per vanilla base hull in the market's memory ({@link #KEY}). The ledger
 * is never mass - a claim adds strata to the world (reclaimStrataShare of the hull's fleet points,
 * through ThreatColonyManager.absorb, so a capped world spills to the nearest) and, when the hull has
 * a converted skin (tools/converted-hulls/gen.py, tag ThreatFleetComposer.CONVERTED_TAG), its shape
 * on the ledger. A fleet budded or built at the world takes its converted hulls only from that
 * ledger (ThreatFleetComposer.fill: a converted_* group's pick is {@link #draw}); a converted hull
 * absorbed back into a world returns its shape ({@link #refund}); one destroyed, or broken into
 * troops at a landing, is spent. So a shape is in exactly one place: a ledger, or a fleet.
 *
 * <p>Claims come from both channels: a battle the Threat wins ({@link Listener}: the named hulls
 * vanilla's snapshot says the losers lost, booked to the winning swarm's home world or the nearest
 * hive), and the mod's off-screen fights (ThreatAbstractBattle: the ships its removeShare strikes
 * from real fleets are named; a route's or an expedition's fleet points are shapes drawn from the
 * losing faction's known hulls, {@link #claimFP}). docs/fleet-archetypes.md "Converted hulls".
 */
public final class ThreatReclaimed {

	private ThreatReclaimed() {}

	/** Market memory: HashMap of vanilla base hull id to count. */
	public static final String KEY = "$threatinc_reclaimed";

	private static Map<String, String> convertedByBase;
	private static Map<String, Float> fpByBase;

	private static void index() {
		if (convertedByBase != null) return;
		Map<String, String> m = new HashMap<String, String>();
		Map<String, Float> f = new HashMap<String, Float>();
		for (ShipHullSpecAPI spec : Global.getSettings().getAllShipHullSpecs()) {
			if (spec == null || !spec.hasTag(ThreatFleetComposer.CONVERTED_TAG)) continue;
			m.put(spec.getBaseHullId(), spec.getHullId());
			f.put(spec.getBaseHullId(), (float) spec.getFleetPoints());
		}
		convertedByBase = m;
		fpByBase = f;
	}

	public static boolean enabled() {
		return ThreatIncConfig.convertedHulls();
	}

	/** The converted skin of a vanilla base hull, or null when the generator made none. */
	public static String convertedHull(String baseHullId) {
		index();
		return baseHullId == null ? null : convertedByBase.get(baseHullId);
	}

	/** Fleet points of a base hull with a converted skin (0 without one). */
	public static float hullFP(String baseHullId) {
		index();
		Float v = baseHullId == null ? null : fpByBase.get(baseHullId);
		return v != null ? v : 0f;
	}

	/** The converted group the hull's shape buds as (ThreatFleetComposer.mixKey), or null. */
	public static String groupOf(String baseHullId) {
		String conv = convertedHull(baseHullId);
		if (conv == null) return null;
		ShipHullSpecAPI spec = Global.getSettings().getHullSpec(conv);
		return spec == null ? null : ThreatFleetComposer.mixKey(spec);
	}

	// ------------------------------------------------------------------
	// the ledger
	// ------------------------------------------------------------------

	/** The world's ledger, live (created empty on first use). */
	@SuppressWarnings("unchecked")
	public static Map<String, Integer> ledger(MarketAPI market) {
		if (market == null) return new HashMap<String, Integer>();
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		Object o = mem.get(KEY);
		if (o instanceof Map) return (Map<String, Integer>) o;
		Map<String, Integer> m = new HashMap<String, Integer>();
		mem.set(KEY, m);
		return m;
	}

	protected static void bump(MarketAPI market, String baseHullId, int by) {
		if (market == null || baseHullId == null || by == 0) return;
		Map<String, Integer> l = ledger(market);
		Integer had = l.get(baseHullId);
		int n = (had != null ? had : 0) + by;
		if (n <= 0) l.remove(baseHullId);
		else l.put(baseHullId, n);
	}

	/** Shapes on the world's ledger, all classes. */
	public static int total(MarketAPI market) {
		int n = 0;
		for (Integer v : ledger(market).values()) n += v;
		return n;
	}

	/** Fleet points the ledger's shapes would bud as, all classes. */
	public static float totalFP(MarketAPI market) {
		float fp = 0f;
		for (Map.Entry<String, Integer> e : ledger(market).entrySet()) fp += e.getValue() * hullFP(e.getKey());
		return fp;
	}

	/** The ledger's mean fleet points per shape of the group (the composer's pick price), 0 with none. */
	public static float meanFP(MarketAPI market, String group) {
		float fp = 0f;
		int n = 0;
		for (Map.Entry<String, Integer> e : ledger(market).entrySet()) {
			if (!group.equals(groupOf(e.getKey()))) continue;
			fp += e.getValue() * hullFP(e.getKey());
			n += e.getValue();
		}
		return n > 0 ? fp / n : 0f;
	}

	/** A shape of the group taken off the ledger, by count; null when the ledger has none of the class. */
	public static String draw(MarketAPI market, String group, Random random) {
		if (market == null || group == null) return null;
		Map<String, Integer> l = ledger(market);
		WeightedRandomPicker<String> p = new WeightedRandomPicker<String>(random);
		for (Map.Entry<String, Integer> e : l.entrySet()) {
			if (e.getValue() > 0 && group.equals(groupOf(e.getKey()))) p.add(e.getKey(), e.getValue());
		}
		if (p.isEmpty()) return null;
		String hull = p.pick();
		bump(market, hull, -1);
		return hull;
	}

	/** A converted hull absorbed back into the world returns its shape. */
	public static void refund(MarketAPI market, FleetMemberAPI member) {
		if (market == null || member == null || member.getHullSpec() == null) return;
		if (!member.getHullSpec().hasTag(ThreatFleetComposer.CONVERTED_TAG)) return;
		bump(market, member.getHullSpec().getBaseHullId(), 1);
	}

	public static void refund(MarketAPI market, List<FleetMemberAPI> members) {
		if (members == null) return;
		for (FleetMemberAPI m : members) refund(market, m);
	}

	/** Every converted hull of a fleet absorbed whole. */
	public static void refundAll(MarketAPI market, CampaignFleetAPI fleet) {
		if (market == null || fleet == null) return;
		refund(market, fleet.getFleetData().getMembersListCopy());
	}

	/** "2 Hammerhead, 1 Eagle", most first; "" for an empty ledger. */
	public static String summary(MarketAPI market) {
		List<Map.Entry<String, Integer>> es = new ArrayList<Map.Entry<String, Integer>>(ledger(market).entrySet());
		java.util.Collections.sort(es, new java.util.Comparator<Map.Entry<String, Integer>>() {
			public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
				return b.getValue().compareTo(a.getValue());
			}
		});
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : es) {
			if (sb.length() > 0) sb.append(", ");
			ShipHullSpecAPI spec = Global.getSettings().getHullSpec(e.getKey());
			sb.append(e.getValue()).append(' ').append(spec != null ? spec.getHullName() : e.getKey());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// claims
	// ------------------------------------------------------------------

	/** The strata a claimed wreck adds: reclaimStrataShare of its fleet points, spilling as an absorb does. */
	protected static void strata(MarketAPI world, float fp) {
		float share = ThreatIncConfig.reclaimStrataShare();
		if (world == null || fp <= 0f || share <= 0f) return;
		ThreatColonyManager.absorb(world, ThreatStrata.strataOf(fp) * share);
	}

	/**
	 * A destroyed hull the swarm eats: strata at the world and, with a converted skin, its shape on
	 * the ledger. The swarm's own dead (Threat hulls, converted ones included) are not reclaimed again.
	 * Returns true when a shape was booked.
	 */
	public static boolean claim(MarketAPI world, FleetMemberAPI member) {
		if (world == null || member == null || member.getHullSpec() == null) return false;
		if (member.getHullSpec().hasTag("threat")) return false;
		return claimHull(world, member.getHullSpec().getBaseHullId(), member.getFleetPointCost());
	}

	public static boolean claimHull(MarketAPI world, String baseHullId, float fp) {
		if (world == null) return false;
		strata(world, fp);
		if (!enabled() || convertedHull(baseHullId) == null) return false;
		bump(world, baseHullId, 1);
		return true;
	}

	/**
	 * Fleet points a faction lost with no hulls to name (a route not yet spawned, an expedition's
	 * autoresolve damage): strata at the world, and shapes drawn from the faction's known hulls that
	 * have converted skins, the cheaper the likelier, until the points are spent (the last hull as
	 * often as the remainder reaches into it). Returns the shapes booked.
	 */
	public static int claimFP(MarketAPI world, String factionId, float fp) {
		if (world == null || fp <= 0f) return 0;
		strata(world, fp);
		if (!enabled()) return 0;
		FactionAPI faction = factionId != null ? Global.getSector().getFaction(factionId) : null;
		if (faction == null) return 0;
		Set<String> bases = new HashSet<String>();
		for (String id : faction.getKnownShips()) {
			ShipHullSpecAPI spec = Global.getSettings().getHullSpec(id);
			if (spec == null) continue;
			String base = spec.getBaseHullId();
			if (convertedHull(base) != null) bases.add(base);
		}
		if (bases.isEmpty()) return 0;
		Random random = new Random();
		WeightedRandomPicker<String> p = new WeightedRandomPicker<String>(random);
		for (String base : bases) p.add(base, 1f / Math.max(1f, hullFP(base)));
		float left = fp;
		int booked = 0;
		while (left > 0f && booked < 400) {
			String hull = p.pick();
			float cost = Math.max(1f, hullFP(hull));
			if (cost > left && random.nextFloat() * cost >= left) break;
			bump(world, hull, 1);
			left -= cost;
			booked++;
		}
		return booked;
	}

	/**
	 * Debug (ThreatDebugWar.pollReclaimed): about {@code fp} points of shapes put on the world's ledger,
	 * one of every converted hull in turn until the points are spent - no strata, nothing destroyed.
	 * Returns the shapes booked.
	 */
	public static int seed(MarketAPI world, float fp) {
		index();
		if (world == null || fp <= 0f || convertedByBase.isEmpty()) return 0;
		List<String> bases = new ArrayList<String>(convertedByBase.keySet());
		java.util.Collections.sort(bases);
		float left = fp;
		int booked = 0;
		while (left > 0f && booked < 2000) {
			for (String base : bases) {
				if (left <= 0f) break;
				bump(world, base, 1);
				left -= Math.max(1f, hullFP(base));
				booked++;
			}
		}
		return booked;
	}

	/** Persistent data: the one-time backfill of mid-war saves has run. */
	public static final String KEY_BACKFILLED = "threatinc_reclaimedBackfilled";

	/**
	 * Mid-war saves get straight into it (the user, 2026-11-08): once per save, on the first load with
	 * this build, every live hive world of reclaimBackfillMinSize or more - the worlds old enough to
	 * have been striking - is given reclaimBackfillShapes shapes, drawn from every converted hull the
	 * cheaper the likelier (no strata: nothing was destroyed). A save whose hives are all still small
	 * gets nothing and never runs again; its ledgers fill as the war's losses mount. Returns the shapes
	 * booked.
	 */
	public static int backfillOnLoad() {
		Map<String, Object> data = Global.getSector().getPersistentData();
		if (Boolean.TRUE.equals(data.get(KEY_BACKFILLED))) return 0;
		if (!ThreatIncData.isStarted()) return 0;
		data.put(KEY_BACKFILLED, true);
		if (!enabled()) return 0;
		int per = ThreatIncConfig.reclaimBackfillShapes();
		int minSize = ThreatIncConfig.reclaimBackfillMinSize();
		if (per <= 0) return 0;
		index();
		if (convertedByBase.isEmpty()) return 0;
		Random random = new Random();
		WeightedRandomPicker<String> p = new WeightedRandomPicker<String>(random);
		for (String base : convertedByBase.keySet()) p.add(base, 1f / Math.max(1f, hullFP(base)));
		int booked = 0;
		int worlds = 0;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			if (m.getSize() < minSize) continue;
			for (int i = 0; i < per; i++) bump(m, p.pick(), 1);
			booked += per;
			worlds++;
		}
		if (booked > 0) {
			Global.getLogger(ThreatReclaimed.class).info("[ThreatInc] Reclaimed hulls backfilled: " + booked
					+ " shapes over " + worlds + " hive worlds of size " + minSize + "+");
			ThreatNotice.titled("Reclaimed Hulls")
					.line("%s hive worlds hold the shapes of %s reclaimed warships.", worlds, booked)
					.send();
		}
		return booked;
	}

	// ------------------------------------------------------------------
	// where a claim goes
	// ------------------------------------------------------------------

	/** The world a swarm's claims go to: its own hive world (GARRISON_FLAG), else the nearest live hive world. */
	public static MarketAPI homeOf(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		String id = fleet.getMemoryWithoutUpdate().getString(ThreatColonyManager.GARRISON_FLAG);
		if (id != null) {
			MarketAPI m = Global.getSector().getEconomy().getMarket(id);
			if (m != null && ThreatIncData.getAllLiveColonyMarkets().contains(m)) return m;
		}
		return nearest(fleet.getLocationInHyperspace());
	}

	/** A live hive world in the system, else the nearest to it. */
	public static MarketAPI nearest(StarSystemAPI system) {
		if (system == null) return null;
		List<MarketAPI> here = ThreatIncData.getLiveColonyMarkets(system.getId());
		if (here != null && !here.isEmpty()) return here.get(0);
		return nearest(system.getLocation());
	}

	public static MarketAPI nearest(SectorEntityToken entity) {
		return entity == null ? null : nearest(entity.getLocationInHyperspace());
	}

	/** The live hive world nearest a hyperspace position, or null with none. */
	public static MarketAPI nearest(Vector2f hyper) {
		MarketAPI best = null;
		float bestD = Float.MAX_VALUE;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			Vector2f lm = m.getLocationInHyperspace();
			if (lm == null) continue;
			float d = hyper == null ? 0f : Misc.getDistance(hyper, lm);
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------
	// fought battles
	// ------------------------------------------------------------------

	/**
	 * Hears every battle a Threat fleet wins and claims the named hulls the other side lost
	 * (vanilla's snapshot, Misc.getSnapshotMembersLost), the player's included. Transient, added on
	 * every load (ThreatIncModPlugin).
	 */
	public static class Listener extends BaseCampaignEventListener {
		public Listener() {
			super(false);
		}

		@Override
		public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
			if (battle == null || primaryWinner == null) return;
			List<CampaignFleetAPI> winners = battle.getSnapshotSideFor(primaryWinner);
			if (winners == null || winners.isEmpty()) return;
			CampaignFleetAPI swarm = null;
			for (CampaignFleetAPI f : winners) {
				if (f != null && f.getFaction() != null && Factions.THREAT.equals(f.getFaction().getId())) {
					swarm = f;
					break;
				}
			}
			if (swarm == null) return;
			MarketAPI home = homeOf(swarm);
			if (home == null) return;
			int hulls = 0, shapes = 0;
			float fp = 0f;
			for (CampaignFleetAPI f : battle.getSnapshotBothSides()) {
				if (f == null || winners.contains(f)) continue;
				if (f.getFaction() != null && Factions.THREAT.equals(f.getFaction().getId())) continue;
				for (FleetMemberAPI m : Misc.getSnapshotMembersLost(f)) {
					if (m == null) continue;
					hulls++;
					fp += m.getFleetPointCost();
					if (claim(home, m)) shapes++;
				}
			}
			if (hulls > 0) {
				ThreatIncConfig.log("Reclaimed: " + home.getName() + " claims " + hulls + " hulls (" + (int) fp
						+ " FP, " + shapes + " shapes) from " + swarm.getName() + "'s battle"
						+ (swarm.getContainingLocation() != null ? " in " + swarm.getContainingLocation().getName() : "")
						+ "; ledger " + total(home) + ": " + summary(home));
			}
		}
	}
}
