package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.CommodityMarketDataAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.PersonAPI;

import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.population.CoreImmigrationPluginImpl;
import com.fs.starfarer.api.util.Misc;

/**
 * FRONTLINE OUTPOSTS (docs/frontlines.md). A faction on war footing pushes a
 * chain of small real markets toward every found hive none of its bases can
 * reach; the front line on the map is where the chain ends. Peacetime
 * factions build nothing.
 *
 * <ul>
 * <li>Each link is a vanilla market on a station over an uncolonised world:
 * size 1 with Spaceport, Waystation and an orbital station, growing one size
 * every {@code frontlineGrowDays} it runs without war shortages, to
 * {@code frontlineMaxSize}.</li>
 * <li>The builder adds Patrol HQ (which makes the link a siege base under
 * the existing rules), station upgrades and Military Base by size - vanilla's
 * pirate base, no ground works - but only what vanilla's own import numbers
 * say the market can supply at its accessibility, one project at a time.</li>
 * <li>A link within {@code frontlineLinkLY} of a colony, or of a link that
 * is, carries the Forward Relay condition (+accessibility). Take a middle
 * link and everything beyond it loses the relay: less import capacity, the
 * station runs short, growth stops.</li>
 * <li>A link with no live found hive within {@code frontlineKeepLY}, or of a
 * faction that stood down, is dismantled after {@code frontlineAbandonDays}.
 * A link starved for {@code frontlineStarveDays} shrinks, and at size 1 is
 * abandoned.</li>
 * <li>A link is a station, as vanilla's pirate base is (2026-09-26): nothing
 * lands on it (ThreatGroundFronts.landingBlocked), and when its station is
 * beaten in battle the whole base is gone - vanilla's
 * PirateBaseIntel.reportFleetDespawnedToListener signal, or for a strike
 * resolved far from the player, its strength against the defenders
 * (ThreatStrikeFGI.stationAssault).</li>
 * </ul>
 */
public class ThreatFrontlines {

	public static final String KEY_OUTPOSTS = "threatinc_frontlines";
	public static final String KEY_LAST_PLAN = "threatinc_frontlinesLastPlan";
	public static final String KEY_LAST_UPDATE = "threatinc_frontlinesLastUpdate";
	public static final String KEY_LAST_CENSUS = "threatinc_frontlinesLastCensus";
	/** Market memory: this market is a frontline outpost. */
	public static final String OUTPOST_FLAG = "$threatinc_frontline";
	/** Market memory: the outpost is connected to a colony through the chain. */
	public static final String RELAY_FLAG = "$threatinc_frontlineRelay";
	public static final String CONDITION = "threatinc_frontline";

	/** Commodities whose shortage stops growth and, held long enough, starves the link. */
	protected static final String[] WAR_NEEDS = {
			Commodities.SUPPLIES, Commodities.FUEL, Commodities.CREW, Commodities.FOOD };

	public static class Outpost {
		public String marketId;
		public String entityId;
		public String factionId;
		/** The hive system the link was founded toward (the log's and the board's reason). */
		public String hiveSystemId;
		public long founded;
		public float healthyDays;
		public float starvedDays;
		public float idleDays;
		/** Unused since 2026-09-27 (a strike calls the garrison); kept so older saves load. */
		public long lastRelief;
		/** The station fleet the loss listener is on (vanilla respawns it after repairs). */
		public String stationFleetId;
		/** The garrison holding the link for as long as it stands (ThreatFrontlines.garrison). */
		public List<CampaignFleetAPI> guards;
		/** The base the garrison sailed from, whose strength it is committed out of. */
		public String guardBaseId;
		public float guardFP;
		public long guardSent;
		public long guardPaid;
		/** Days the link has stood without the garrison it needs. */
		public float unguardedDays;
		/** Days the link has stood behind the front; its standing guard goes home after frontlineRearGraceDays. */
		public float rearDays;
		/** When a seen strike last called a guard here (the front's monthly retry keeps guardSent). */
		public long guardCalled;
		/** The standing guard sent home from behind the front, turned back if a strike calls one while it sails. */
		public List<CampaignFleetAPI> homebound;
	}

	/** Market memory: the station was destroyed in battle; the next daily update ends the base. */
	public static final String STATION_LOST_FLAG = "$threatinc_frontlineStationLost";

	/**
	 * On the link's station fleet, as vanilla's PirateBaseIntel is on its
	 * base's: a station destroyed in battle ends the base. It only flags the
	 * market - tearing a market down inside a battle's cleanup is not safe;
	 * PirateBaseIntel defers too (endAfterDelay).
	 */
	public static class StationListener implements com.fs.starfarer.api.campaign.listeners.FleetEventListener {
		protected String marketId;

		public StationListener(String marketId) {
			this.marketId = marketId;
		}

		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason reason, Object param) {
			if (reason != com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason.DESTROYED_BY_BATTLE) {
				return;
			}
			MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
			if (market == null || !isOutpost(market)) return;
			market.getMemoryWithoutUpdate().set(STATION_LOST_FLAG, true);
			ThreatIncConfig.log("Frontline: the station of " + market.getName() + " was destroyed in battle");
		}

		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
				com.fs.starfarer.api.campaign.BattleAPI battle) {
		}
	}

	/** The base's station is gone: the base goes with it, as a pirate base does. */
	public static void stationDestroyed(MarketAPI market, String by) {
		Outpost o = find(market);
		if (o == null) return;
		FactionAPI faction = market.getFaction();
		ThreatNotice n = ThreatNotice.titled("Forward Base Destroyed").bad().icon(faction);
		n.line("%s %s fell with its station", ThreatNotice.faction(faction), market.getName());
		ThreatColonyManager.announce(n);
		dismantle(o, market, "station destroyed by " + by);
	}

	@SuppressWarnings("unchecked")
	public static List<Outpost> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_OUTPOSTS);
		if (!(val instanceof List)) {
			val = new ArrayList<Outpost>();
			Global.getSector().getPersistentData().put(KEY_OUTPOSTS, val);
		}
		return (List<Outpost>) val;
	}

	public static boolean isOutpost(MarketAPI market) {
		return market != null && market.getMemoryWithoutUpdate().getBoolean(OUTPOST_FLAG);
	}

	public static Outpost find(MarketAPI market) {
		if (market == null) return null;
		for (Outpost o : all()) {
			if (market.getId().equals(o.marketId)) return o;
		}
		return null;
	}

	protected static MarketAPI marketOf(Outpost o) {
		MarketAPI m = Global.getSector().getEconomy().getMarket(o.marketId);
		return m != null && m.isInEconomy() ? m : null;
	}

	protected static float ly(MarketAPI a, MarketAPI b) {
		return Misc.getDistanceLY(a.getLocationInHyperspace(), b.getLocationInHyperspace());
	}

	protected static float ly(MarketAPI a, StarSystemAPI b) {
		return Misc.getDistanceLY(a.getLocationInHyperspace(), b.getLocation());
	}

	// ------------------------------------------------------------------
	// poll
	// ------------------------------------------------------------------

	/** Called on IncursionManager's fast poll; does its work once a day. */
	public static void poll(Random random) {
		if (!ThreatIncConfig.frontlinesEnabled()) return;
		Map<String, Object> data = Global.getSector().getPersistentData();
		long now = Global.getSector().getClock().getTimestamp();
		Object last = data.get(KEY_LAST_UPDATE);
		if (!(last instanceof Long)) {
			data.put(KEY_LAST_UPDATE, now);
			data.put(KEY_LAST_PLAN, now);
			return;
		}
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) last);
		if (days < 1f) return;
		data.put(KEY_LAST_UPDATE, now);

		prune();
		updateRelays();
		for (Outpost o : new ArrayList<Outpost>(all())) {
			MarketAPI market = marketOf(o);
			if (market != null) update(o, market, days);
		}

		Object lastPlan = data.get(KEY_LAST_PLAN);
		float sincePlan = lastPlan instanceof Long
				? Global.getSector().getClock().getElapsedDaysSince((Long) lastPlan) : 9999f;
		if (sincePlan >= ThreatIncConfig.frontlinePlanDays()) {
			data.put(KEY_LAST_PLAN, now);
			plan(random);
		}

		Object lastCensus = data.get(KEY_LAST_CENSUS);
		if (!(lastCensus instanceof Long)
				|| Global.getSector().getClock().getElapsedDaysSince((Long) lastCensus) >= 30f) {
			data.put(KEY_LAST_CENSUS, now);
			census();
		}
	}

	/**
	 * Monthly debug log, one line per mobilised faction and one for the
	 * swarm: the figures a long war test reads to see who is running away.
	 */
	protected static void census() {
		if (!ThreatIncConfig.debugLogging()) return;
		for (String fid : ThreatWarState.warFactionIds()) {
			if (Factions.THREAT.equals(fid)) continue;
			int colonies = 0, sizes = 0, links = 0, linkSizes = 0, bases = 0;
			for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
				if (isOutpost(m)) {
					links++;
					linkSizes += m.getSize();
				} else {
					colonies++;
					sizes += m.getSize();
				}
				if (IncursionManager.isBase(m)) bases++;
			}
			int guarded = 0, fortresses = 0, front = 0;
			float guardFP = 0f;
			for (Outpost o : all()) {
				if (!fid.equals(o.factionId)) continue;
				List<CampaignFleetAPI> live = liveGuards(o);
				if (!live.isEmpty()) guarded++;
				for (CampaignFleetAPI f : live) guardFP += f.getFleetPoints();
				MarketAPI m = marketOf(o);
				if (m != null && isFront(m)) front++;
				if (m != null && stationOf(m) != null && stationTier(stationOf(m)) == 3) fortresses++;
			}
			ThreatIncConfig.log("Census: " + fid + " colonies " + colonies + " (size " + sizes
					+ "), links " + links + " (size " + linkSizes + ", front " + front + "), guarded " + guarded + " ("
					+ (int) guardFP + " FP), fortresses " + fortresses + ", bases " + bases
					+ ", reserve marines " + (int) ThreatReserves.factionStock(fid, Commodities.MARINES)
					+ ", arms " + (int) ThreatReserves.factionStock(fid, Commodities.HAND_WEAPONS)
					+ ", fuel " + (int) ThreatReserves.factionStock(fid, Commodities.FUEL)
					+ ", supplies " + (int) ThreatReserves.factionStock(fid, Commodities.SUPPLIES));
		}
		int hives = 0, hiveSizes = 0, known = 0;
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			hives++;
			hiveSizes += hive.getSize();
			if (ThreatScouts.sectorKnows(hive)) known++;
		}
		ThreatIncConfig.log("Census: threat hives " + hives + " (size " + hiveSizes + "), found "
				+ known);
	}

	/** Drops links that are gone (taken, decivilised) or changed hands. */
	protected static void prune() {
		for (Outpost o : new ArrayList<Outpost>(all())) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(o.marketId);
			if (market == null || !market.isInEconomy()) {
				all().remove(o);
				recallGarrison(o, "the link is gone");
				ThreatIncConfig.log("Frontline: outpost " + o.marketId + " (" + o.factionId + ") is gone");
				continue;
			}
			if (!o.factionId.equals(market.getFactionId())) {
				// captured (Nexerelin invasions): no longer a link of anyone's
				all().remove(o);
				recallGarrison(o, "the link changed hands");
				market.getMemoryWithoutUpdate().unset(OUTPOST_FLAG);
				market.getMemoryWithoutUpdate().unset(RELAY_FLAG);
				market.getMemoryWithoutUpdate().unset(DecivTracker.NO_DECIV_KEY);
				if (market.hasCondition(CONDITION)) market.removeCondition(CONDITION);
				market.reapplyConditions();
				ThreatIncConfig.log("Frontline: " + market.getName() + " changed hands to "
						+ market.getFactionId() + " - no longer a frontline outpost");
			}
		}
	}

	// ------------------------------------------------------------------
	// the chain: relay connectivity
	// ------------------------------------------------------------------

	/**
	 * The links of each faction connected to one of its colonies, hop by hop
	 * within frontlineLinkLY. {@code without} is left out, to ask what its
	 * loss would cut.
	 */
	protected static Set<String> connected(String factionId, MarketAPI without) {
		List<MarketAPI> links = new ArrayList<MarketAPI>();
		List<MarketAPI> reached = new ArrayList<MarketAPI>();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!factionId.equals(m.getFactionId()) || m == without || m.isHidden()) continue;
			if (m.getPrimaryEntity() == null) continue;
			if (isOutpost(m)) links.add(m);
			else reached.add(m);
		}
		float hop = ThreatIncConfig.frontlineLinkLY();
		Set<String> result = new HashSet<String>();
		boolean grew = true;
		while (grew) {
			grew = false;
			for (MarketAPI link : new ArrayList<MarketAPI>(links)) {
				for (MarketAPI r : reached) {
					if (ly(link, r) <= hop) {
						result.add(link.getId());
						reached.add(link);
						links.remove(link);
						grew = true;
						break;
					}
				}
			}
		}
		return result;
	}

	/** Sets or clears each link's relay flag; the condition reads it on reapply. */
	protected static void updateRelays() {
		Map<String, Set<String>> byFaction = new HashMap<String, Set<String>>();
		for (Outpost o : all()) {
			MarketAPI market = marketOf(o);
			if (market == null) continue;
			Set<String> conn = byFaction.get(o.factionId);
			if (conn == null) {
				conn = connected(o.factionId, null);
				byFaction.put(o.factionId, conn);
			}
			boolean relay = conn.contains(market.getId());
			boolean had = market.getMemoryWithoutUpdate().getBoolean(RELAY_FLAG);
			if (relay != had) {
				if (relay) market.getMemoryWithoutUpdate().set(RELAY_FLAG, true);
				else market.getMemoryWithoutUpdate().unset(RELAY_FLAG);
				market.reapplyConditions();
				ThreatIncConfig.log("Frontline: " + market.getName() + (relay ? " joined" : " cut off from")
						+ " its chain");
			}
		}
	}

	/** Links of the faction that lose the relay if this one falls. */
	public static int cutBy(MarketAPI market) {
		String fid = market.getFactionId();
		Set<String> now = connected(fid, null);
		Set<String> after = connected(fid, market);
		int n = 0;
		for (String id : now) {
			if (!id.equals(market.getId()) && !after.contains(id)) n++;
		}
		return n;
	}

	/**
	 * The swarm's want for a link (IncursionManager.pickStrikeTarget): at
	 * least a size-3 world's, times frontlineStrikeWeight, times one plus
	 * the links its loss would cut.
	 */
	public static float strikeWeight(MarketAPI market) {
		int size = Math.max(3, market.getSize());
		return size * size * Math.max(0f, ThreatIncConfig.frontlineStrikeWeight()) * (1 + cutBy(market));
	}

	// ------------------------------------------------------------------
	// growth, starvation, abandonment and the builder
	// ------------------------------------------------------------------

	protected static void update(Outpost o, MarketAPI market, float days) {
		if (market.getMemoryWithoutUpdate().getBoolean(STATION_LOST_FLAG)) {
			stationDestroyed(market, "battle");
			return;
		}
		CampaignFleetAPI station = Misc.getStationFleet(market);
		if (station != null && !station.getId().equals(o.stationFleetId)) {
			station.addEventListener(new StationListener(market.getId()));
			o.stationFleetId = station.getId();
		}

		boolean short_ = warShortage(market);
		if (short_) {
			o.starvedDays += days;
			o.healthyDays = 0f;
		} else {
			o.healthyDays += days;
			o.starvedDays = Math.max(0f, o.starvedDays - days);
		}

		if (hasPurpose(o, market)) {
			o.idleDays = 0f;
		} else {
			o.idleDays += days;
			if (o.idleDays >= ThreatIncConfig.frontlineAbandonDays()) {
				dismantle(o, market, "no found hive in reach");
				return;
			}
		}

		if (!garrison(o, market, days)) return;

		if (o.starvedDays >= ThreatIncConfig.frontlineStarveDays()) {
			o.starvedDays = 0f;
			if (market.getSize() <= 1) {
				dismantle(o, market, "starved");
				return;
			}
			shrink(market);
			ThreatIncConfig.log("Frontline: " + market.getName() + " starved down to size "
					+ market.getSize());
		}

		if (o.healthyDays >= ThreatIncConfig.frontlineGrowDays()
				&& market.getSize() < ThreatIncConfig.frontlineMaxSize()) {
			o.healthyDays = 0f;
			CoreImmigrationPluginImpl.increaseMarketSize(market);
			ThreatIncConfig.log("Frontline: " + market.getName() + " grew to size " + market.getSize());
		}

		build(market);
	}

	// ------------------------------------------------------------------
	// the garrison (2026-09-26): no paper bases
	// ------------------------------------------------------------------

	/**
	 * Whether the link needs a garrison: always, while garrisons are on. A star
	 * fortress alone does not hold - run 5 lost all seven the month their
	 * garrisons went home, each to a single strike.
	 */
	public static boolean needsGarrison(MarketAPI market) {
		return ThreatIncConfig.frontlineGarrisonEnabled();
	}

	/** The garrison's fleets still alive. */
	protected static List<CampaignFleetAPI> liveGuards(Outpost o) {
		List<CampaignFleetAPI> live = new ArrayList<CampaignFleetAPI>();
		if (o.guards == null) return live;
		for (CampaignFleetAPI f : o.guards) {
			if (f != null && f.isAlive() && !f.isDespawning()) live.add(f);
		}
		return live;
	}

	/** [fuel, supplies] a garrison of this size costs to sail this far, as the response task force pays. */
	protected static float[] voyageCost(float fp, float ly) {
		float points = fp / IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
		return new float[] { points * ly * ThreatIncConfig.expeditionFuelPerPointLY(),
				points * ThreatIncConfig.expeditionSuppliesPerPoint() };
	}

	/**
	 * Vanilla's raid strength (fleet effective strength) per fleet point of a
	 * human task force, to size a garrison before it exists: run 6's weighed
	 * 1.23, 1.50 and 1.59. The garrison is weighed for real once it spawns,
	 * and topped up if short (sendGarrison).
	 */
	protected static final float STRENGTH_PER_FP = 1.4f;
	/** Supplies a month per garrison fleet point, to plan upkeep: runs 5-6 billed 1.1-1.33. */
	protected static final float UPKEEP_PER_FP = 1.2f;
	/** One strike fleet's raid strength when its hive has no live swarm to weigh: a size-9 fleet's route strength. */
	protected static final float STRIKE_FLEET_STRENGTH = 450f;

	/**
	 * The strike a hive world would send once its garrison is full: its
	 * largest Defense Swarms above the reserve it keeps home
	 * (ThreatColonyManager.garrisonAvailableForLaunch - reinforcement can fill
	 * it past its own desired count) - a strike is those swarms re-embodied.
	 * Each is weighed as vanilla's autoresolve weighs a strike far from the
	 * player, where the fights happen: its route's strength (FleetGroupIntel,
	 * 50 per size point - run 5's 27-point strikes weighed 1,350). Not the
	 * swarm's own fleet strength: run 6 sized on that, ~4x the route figure
	 * (a 1,350 strike read ~5,500), and no faction could afford one garrison.
	 * Swarms not yet regrown count as the average.
	 */
	protected static float strikeOf(MarketAPI hive) {
		int send = Math.max(ThreatColonyManager.desiredGarrisonCount(hive),
				ThreatColonyManager.countLiveGarrison(hive.getId())) - ThreatColonyManager.garrisonReserve(hive);
		if (send <= 0) return 0f;
		List<Float> live = new ArrayList<Float>();
		float sum = 0f;
		for (CampaignFleetAPI f : ThreatIncData.garrisonsFor(hive.getId())) {
			if (f == null || !f.isAlive()) continue;
			int size = IncursionManager.strikeFleetSize(ThreatColonyManager.expeditionSizeFor(f));
			float s = FleetGroupIntel.getApproximateStrengthForTotalDifficultyPoints(hive.getFactionId(), size);
			live.add(s);
			sum += s;
		}
		java.util.Collections.sort(live, java.util.Collections.reverseOrder());
		float each = live.isEmpty() ? STRIKE_FLEET_STRENGTH : sum / live.size();
		float str = 0f;
		for (int i = 0; i < send; i++) str += i < live.size() ? live.get(i) : each;
		return str;
	}

	/**
	 * The strongest strike the Threat could send at a site: every found hive
	 * world big enough to stage strikes whose fuel reaches it (the range
	 * IncursionManager.pickStrikeTarget uses).
	 */
	public static float strikeAt(SectorEntityToken site) {
		float worst = 0f;
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || hive.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (!ThreatScouts.sectorKnows(hive)) continue;
			float d = Misc.getDistanceLY(sys.getLocation(), site.getLocationInHyperspace());
			if (d > ThreatColonyManager.fuelRangeLY(hive)) continue;
			worst = Math.max(worst, strikeOf(hive));
		}
		return worst;
	}

	/**
	 * The raid strength a link's garrison must bring: the strongest strike in
	 * reach x frontlineGarrisonMargin, less what the link's station weighs -
	 * vanilla's autoresolve adds the two - and at least frontlineGarrisonFP.
	 */
	protected static float guardNeed(SectorEntityToken site, MarketAPI link) {
		float need = strikeAt(site) * ThreatIncConfig.frontlineGarrisonMargin();
		if (link != null && link.getStarSystem() != null && link.getPrimaryEntity() != null) {
			need -= WarSimScript.getStationStrength(link.getFaction(), link.getStarSystem(), link.getPrimaryEntity());
		}
		return Math.max(ThreatIncConfig.frontlineGarrisonFP() * STRENGTH_PER_FP, need);
	}

	// ------------------------------------------------------------------
	// front and rear (2026-09-27, user's call): only the front stands guard
	// ------------------------------------------------------------------

	/** Links nearer a hive than this beyond the faction's nearest market still stand at its front (the same system). */
	protected static final float FRONT_TOLERANCE_LY = 0.5f;
	protected static Object frontSector;
	protected static long frontDay = -1;
	protected static Map<String, Set<String>> frontCache = new HashMap<String, Set<String>>();

	/**
	 * The links a faction holds at the front: for every found hive world that
	 * stages strikes, the faction's market nearest it, when that is a link and
	 * the hive's fuel reaches it, and every link in a system with a hive world
	 * or Threat fleets.
	 * Those keep a standing garrison. The rest are
	 * the rear: guarded only while a seen strike is bound for them
	 * (onCallNeed). {@code extra} is a site about to be raised, reported as
	 * "extra" when it would stand at the front.
	 */
	protected static Set<String> frontOf(String factionId, SectorEntityToken extra) {
		Set<String> front = new HashSet<String>();
		List<MarketAPI> mine = ThreatReserves.marketsOf(factionId);
		// Threat fleets in the system - a dead hive's Defense Swarms stay on:
		// run 15's Vlaan-Tone victory base was raised as rear under 732 FP of
		// them, and died two days later with the front's survivors in it
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		for (MarketAPI m : mine) {
			if (!isOutpost(m) || m.getStarSystem() == null) continue;
			if (WarSimScript.getFactionStrength(threat, m.getStarSystem()) > 0f) front.add(m.getId());
		}
		if (extra != null && extra.getContainingLocation() instanceof StarSystemAPI
				&& WarSimScript.getFactionStrength(threat, (StarSystemAPI) extra.getContainingLocation()) > 0f) {
			front.add("extra");
		}
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null) continue;
			// a link sharing a system with any hive world stands at its front - run
			// 10's four unguarded ground-victory bases died to theirs in 2-51 days
			for (MarketAPI m : mine) {
				if (isOutpost(m) && m.getStarSystem() == sys) front.add(m.getId());
			}
			if (extra != null && extra.getContainingLocation() == sys) front.add("extra");
			if (hive.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (!ThreatScouts.sectorKnows(hive)) continue;
			float best = extra != null ? Misc.getDistanceLY(sys.getLocation(), extra.getLocationInHyperspace())
					: Float.MAX_VALUE;
			for (MarketAPI m : mine) {
				if (m.getPrimaryEntity() == null) continue;
				best = Math.min(best, Misc.getDistanceLY(sys.getLocation(), m.getLocationInHyperspace()));
			}
			if (best > ThreatColonyManager.fuelRangeLY(hive)) continue;
			for (MarketAPI m : mine) {
				if (m.getPrimaryEntity() == null || !isOutpost(m)) continue;
				if (Misc.getDistanceLY(sys.getLocation(), m.getLocationInHyperspace()) <= best + FRONT_TOLERANCE_LY) {
					front.add(m.getId());
				}
			}
			if (extra != null && Misc.getDistanceLY(sys.getLocation(), extra.getLocationInHyperspace())
					<= best + FRONT_TOLERANCE_LY) {
				front.add("extra");
			}
		}
		return front;
	}

	/** Whether the link stands at its faction's front (frontOf), worked out once a day per faction. */
	public static boolean isFront(MarketAPI link) {
		long day = (long) Global.getSector().getClock().getElapsedDaysSince(0L);
		if (frontSector != Global.getSector() || frontDay != day) {
			frontSector = Global.getSector();
			frontDay = day;
			frontCache.clear();
		}
		Set<String> front = frontCache.get(link.getFactionId());
		if (front == null) {
			front = frontOf(link.getFactionId(), null);
			frontCache.put(link.getFactionId(), front);
		}
		return front.contains(link.getId());
	}

	/** The seen strikes bound for this market that have not struck yet. */
	protected static List<ThreatStrikeFGI> strikesOn(MarketAPI market) {
		List<ThreatStrikeFGI> out = new ArrayList<ThreatStrikeFGI>();
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted() || !strike.isDetected()) continue;
			// struck and going home: run 15's called guards stayed a median 155
			// days, the strike's whole return leg
			if (strike.isSucceeded() || strike.isFailed() || strike.isCurrent(GenericRaidFGI.RETURN_ACTION)) continue;
			if (strike.getParams() == null || strike.getParams().raidParams == null) continue;
			if (strike.getParams().raidParams.allowedTargets.contains(market)) out.add(strike);
		}
		return out;
	}

	/** A strike's raid strength as vanilla's autoresolve weighs it far from the player: its routes' (strikeOf). */
	protected static float strikeStrength(ThreatStrikeFGI strike) {
		float sum = 0f;
		if (strike.getParams() == null || strike.getParams().fleetSizes == null) return sum;
		for (Integer size : strike.getParams().fleetSizes) {
			if (size != null) sum += FleetGroupIntel.getApproximateStrengthForTotalDifficultyPoints(Factions.THREAT, size);
		}
		return sum;
	}

	/** What the seen strikes bound for the market weigh together (strikeStrength). */
	protected static float strikesWeight(MarketAPI market) {
		float str = 0f;
		for (ThreatStrikeFGI strike : strikesOn(market)) str += strikeStrength(strike);
		return str;
	}

	/**
	 * The raid strength a rear link must be guarded by now: the seen strikes
	 * bound for it x frontlineGarrisonMargin, less its station - 0 with none
	 * coming.
	 */
	protected static float onCallNeed(MarketAPI link) {
		float str = strikesWeight(link);
		if (str <= 0f) return 0f;
		float need = str * ThreatIncConfig.frontlineGarrisonMargin();
		if (link.getStarSystem() != null && link.getPrimaryEntity() != null) {
			need -= WarSimScript.getStationStrength(link.getFaction(), link.getStarSystem(), link.getPrimaryEntity());
		}
		return Math.max(0f, need);
	}

	/**
	 * The raid strength the guards of the faction's other links in the link's
	 * system bring: a strike contests the whole system and vanilla's
	 * autoresolve weighs every hostile fleet in it, so one guard answers for
	 * all of them.
	 */
	protected static float neighbourGuards(Outpost o, MarketAPI link) {
		float sum = 0f;
		for (Outpost other : all()) {
			if (other == o || !o.factionId.equals(other.factionId)) continue;
			MarketAPI m = marketOf(other);
			if (m == null || m.getStarSystem() != link.getStarSystem()) continue;
			sum += strengthOf(liveGuards(other));
		}
		return sum;
	}

	/** Fleet points of standing guards the front would send home: links behind it with no strike on them. */
	protected static float releasedFP(String factionId, Set<String> front) {
		float sum = 0f;
		for (Outpost o : all()) {
			if (!factionId.equals(o.factionId) || front.contains(o.marketId)) continue;
			MarketAPI m = marketOf(o);
			if (m != null && strikesOn(m).isEmpty()) sum += fpOf(liveGuards(o));
		}
		return sum;
	}

	/** What the link's guard must weigh today: the front's standing need, else what is coming at it. */
	protected static float needNow(MarketAPI link) {
		float need = onCallNeed(link);
		if (isFront(link)) need = Math.max(need, guardNeed(link.getPrimaryEntity(), link));
		return need;
	}

	protected static float strengthOf(List<CampaignFleetAPI> fleets) {
		float sum = 0f;
		for (CampaignFleetAPI f : fleets) sum += f.getEffectiveStrength();
		return sum;
	}

	protected static float fpOf(List<CampaignFleetAPI> fleets) {
		float sum = 0f;
		for (CampaignFleetAPI f : fleets) sum += f.getFleetPoints();
		return sum;
	}

	/**
	 * Supplies a month the faction's standing garrisons cost - the front's,
	 * {@code front} (frontOf). A rear link's guard answers one strike and goes
	 * home, so it is not held against the budget.
	 */
	protected static float garrisonUpkeep(String factionId, Set<String> front) {
		float sum = 0f;
		for (Outpost o : all()) {
			if (!factionId.equals(o.factionId) || !front.contains(o.marketId)) continue;
			for (CampaignFleetAPI f : liveGuards(o)) sum += maintenancePerMonth(f);
		}
		return sum;
	}

	/** What the faction may spend on garrisons a month: its supply banking x frontlineUpkeepShare. */
	protected static float upkeepBudget(String factionId) {
		float income = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(factionId)) {
			income += ThreatReserves.accrualPer30(m, Commodities.SUPPLIES);
		}
		return income * ThreatIncConfig.frontlineUpkeepShare();
	}

	/** Why the last garrisonBase found none, for the planner's log. */
	protected static String noGarrisonWhy = "";

	/**
	 * For a site about to be raised: the faction's base that will answer for
	 * it, or null - and then nothing is raised there. At the front (frontOf)
	 * that is the nearest base that can spare the standing garrison the site
	 * needs and pay its voyage and upkeep: a front base the faction cannot hold
	 * against the strikes in reach is a paper base. The garrisons the new site
	 * relieves - the links it puts behind the front - are not counted against
	 * the budget. In the rear it is the nearest base, which sends a guard only
	 * when a strike is seen coming (sendRelief).
	 */
	public static MarketAPI garrisonBase(FactionAPI faction, SectorEntityToken site) {
		Set<String> front = frontOf(faction.getId(), site);
		if (!front.contains("extra")) {
			MarketAPI base = nearestBase(faction, site);
			if (base == null) noGarrisonWhy = "no base";
			return base;
		}
		return garrisonBase(faction, site, guardNeed(site, null) / STRENGTH_PER_FP, front);
	}

	/**
	 * The nearest base that can spare {@code needFP} and pay its voyage, or
	 * null. With {@code front} given, only while the standing garrisons'
	 * upkeep stays within the budget; without, a guard called by a strike,
	 * which the budget does not hold back.
	 */
	protected static MarketAPI garrisonBase(FactionAPI faction, SectorEntityToken site, float needFP,
			Set<String> front) {
		if (front != null) {
			float upkeep = garrisonUpkeep(faction.getId(), front) + needFP * UPKEEP_PER_FP;
			float budget = upkeepBudget(faction.getId());
			if (upkeep > budget) {
				noGarrisonWhy = "upkeep would be " + (int) upkeep + " of " + (int) budget + " supplies a month";
				return null;
			}
		}
		float spare = navySpareFP(faction);
		// the guards the new front sends home are the navy's again
		if (front != null) spare += releasedFP(faction.getId(), front);
		if (spare < needFP) {
			noGarrisonWhy = "its navy can spare " + (int) Math.max(0f, spare) + " of " + (int) needFP + " FP";
			return null;
		}
		MarketAPI best = nearestBase(faction, site);
		if (best == null) {
			noGarrisonWhy = "no base";
			return null;
		}
		float bestDist = Misc.getDistanceLY(best.getLocationInHyperspace(), site.getLocationInHyperspace());
		if (!canPayVoyage(best, needFP, bestDist)) {
			noGarrisonWhy = "cannot pay the voyage of " + (int) needFP + " FP from " + best.getName();
			return null;
		}
		return best;
	}

	/**
	 * The faction's base nearest the site that is not a link, or null. A link
	 * that became a base spawned its own called guard on the spot in run 15 (15
	 * of 19): the guard is the navy's and sails from a colony.
	 */
	protected static MarketAPI nearestBase(FactionAPI faction, SectorEntityToken site) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null || !IncursionManager.isBase(m)) continue;
			if (isOutpost(m)) continue;
			float d = Misc.getDistanceLY(m.getLocationInHyperspace(), site.getLocationInHyperspace());
			if (d >= bestDist) continue;
			bestDist = d;
			best = m;
		}
		return best;
	}

	/**
	 * What the faction's navy can spare for garrisons, in fleet points: vanilla's
	 * strength of the faction in each of its bases' systems (WarSimScript), in
	 * the response task force's fleet points, less every garrison it has out. Run 8
	 * weighed each base's own system alone, so Hegemony - the biggest navy -
	 * "could not spare" 1,200 FP for most of the run whenever the patrols of
	 * the one system asked happened to be out.
	 */
	protected static float navySpareFP(FactionAPI faction) {
		java.util.Set<StarSystemAPI> seen = new java.util.HashSet<StarSystemAPI>();
		float strength = 0f;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			StarSystemAPI sys = m.getStarSystem();
			// not the links' systems: what stands there is mostly the garrisons already counted out
			if (sys == null || !IncursionManager.isBase(m) || isOutpost(m) || !seen.add(sys)) continue;
			strength += WarSimScript.getFactionStrength(faction, sys);
		}
		float committed = 0f;
		for (Outpost o : all()) {
			if (faction.getId().equals(o.factionId)) committed += fpOf(liveGuards(o));
		}
		return strength / Math.max(1f, ThreatIncConfig.responseStrengthDivisor())
				* IncursionManager.FP_PER_RESPONSE_DIFFICULTY - committed;
	}

	/** Whether the faction can pay a garrison's voyage from this base: its stock, then the faction's other markets (payFromOthers). */
	protected static boolean canPayVoyage(MarketAPI base, float fp, float ly) {
		float[] cost = voyageCost(fp, ly);
		return pooled(base, Commodities.FUEL) >= cost[0] && pooled(base, Commodities.SUPPLIES) >= cost[1];
	}

	/** A commodity above the floor at the base and every other market of its faction but the links. */
	protected static float pooled(MarketAPI base, String commodityId) {
		float sum = ThreatReserves.available(base, commodityId);
		for (MarketAPI m : ThreatReserves.marketsOf(base.getFactionId())) {
			if (m != base && !isOutpost(m)) sum += ThreatReserves.available(m, commodityId);
		}
		return sum;
	}

	/** Guards a link just raised, if it stands at the front or a strike is on its way. */
	protected static boolean sendGarrison(Outpost o, MarketAPI market, MarketAPI base) {
		frontDay = -1; // the new link moved the front
		float need = needNow(market);
		if (need < 30f * STRENGTH_PER_FP) return false;
		return sendGarrison(o, market, base, need);
	}

	/**
	 * Sends fleets from the base bringing {@code str} of vanilla's raid
	 * strength to the link: real fleets on DEFEND_LOCATION over it for as long
	 * as it stands, paid out of the base's reserve like a response task force (fuel
	 * for the distance, supplies for the fleets). Sized on STRENGTH_PER_FP,
	 * then weighed for real and topped up once if short. Joins any garrison
	 * already there.
	 */
	protected static boolean sendGarrison(Outpost o, MarketAPI market, MarketAPI base, float str) {
		FactionAPI faction = market.getFaction();
		List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		float got = 0f;
		for (int pass = 0; pass < 2 && got < str * 0.95f; pass++) {
			float rate = got > 0f ? got / Math.max(1f, fpOf(fleets)) : STRENGTH_PER_FP;
			List<CampaignFleetAPI> more = spawnForce(faction, base, market, (str - got) / rate, "Garrison",
					100000f, "garrisoning " + market.getName(), 16);
			if (more.isEmpty()) break;
			fleets.addAll(more);
			got = strengthOf(fleets);
		}
		if (fleets.isEmpty()) return false;
		float fp = fpOf(fleets);
		float[] cost = voyageCost(fp, ly(base, market));
		// the base first, then the faction's other markets (canPayVoyage)
		float fuel = ThreatReserves.drawAbove(base, Commodities.FUEL, cost[0]);
		if (fuel < cost[0]) fuel += payFromOthers(base, null, Commodities.FUEL, cost[0] - fuel);
		float supplies = ThreatReserves.drawAbove(base, Commodities.SUPPLIES, cost[1]);
		if (supplies < cost[1]) supplies += payFromOthers(base, null, Commodities.SUPPLIES, cost[1] - supplies);
		for (CampaignFleetAPI f : fleets) {
			ThreatReturns.provision(f, base.getId(), fuel / fleets.size(), supplies / fleets.size());
		}
		long now = Global.getSector().getClock().getTimestamp();
		boolean topUp = !liveGuards(o).isEmpty();
		if (!topUp) {
			o.guards = new ArrayList<CampaignFleetAPI>();
			o.guardFP = 0f;
			o.guardPaid = now;
		}
		o.guards.addAll(fleets);
		o.guardBaseId = base.getId();
		o.guardFP += fp;
		o.unguardedDays = 0f;
		ThreatIncConfig.log("Frontline: " + base.getName() + (topUp ? " reinforces " : " garrisons ")
				+ market.getName() + " with " + fleets.size() + " fleet(s), " + (int) fp + " FP, strength "
				+ (int) got + " of " + (int) str + (isFront(market)
						? " (front; strike in reach " + (int) strikeAt(market.getPrimaryEntity()) + ")"
						: " (rear; strike on its way " + (int) (onCallNeed(market) / ThreatIncConfig.frontlineGarrisonMargin()) + ")"));
		return true;
	}

	/** Garrisons a link just raised from this base (ThreatOutposts.planNPC). */
	public static void garrisonNow(MarketAPI link, MarketAPI base) {
		Outpost o = find(link);
		if (o != null && base != null) sendGarrison(o, link, base);
	}

	/** The garrison's home base while the link's faction still holds it, else null. */
	protected static MarketAPI homeOf(Outpost o) {
		MarketAPI home = o.guardBaseId != null ? Global.getSector().getEconomy().getMarket(o.guardBaseId) : null;
		return home != null && home.getFactionId().equals(o.factionId) ? home : null;
	}

	/** Sends the garrison home (the link is gone, or it went unpaid). */
	protected static void recallGarrison(Outpost o, String why) {
		List<CampaignFleetAPI> live = liveGuards(o);
		MarketAPI home = homeOf(o);
		for (CampaignFleetAPI f : live) {
			f.clearAssignments();
			if (home != null && home.getPrimaryEntity() != null) {
				f.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
						"returning to " + home.getName());
			} else {
				f.despawn(); // its base is gone: nowhere to go home to
			}
		}
		if (!live.isEmpty()) {
			ThreatIncConfig.log("Frontline: garrison of " + o.marketId + " recalled (" + why + ")");
		}
		o.guards = null;
		o.guardBaseId = null;
		o.guardFP = 0f;
	}

	/**
	 * The daily garrison step. False when the link was dismantled.
	 * <ul>
	 * <li>Garrisons off: any garrison goes home.</li>
	 * <li>A garrison on station: its upkeep, its ships' maintenance
	 * (maintenancePerMonth), is paid monthly (payUpkeep). Unpaid by half, it
	 * goes home; paid, it is reinforced if the strikes in reach outgrew it
	 * (topUp).</li>
	 * <li>No garrison (lost in battle, recalled, or a link raised without one):
	 * one is sent if a base can spare it, at most every 30 days; a link
	 * unguarded for frontlineAbandonDays is given up.</li>
	 * <li>The rear (isFront false, user's call 2026-09-27): no standing
	 * garrison. A guard sails when a strike is seen coming (sendRelief, then
	 * this step weekly while it comes), outside the upkeep budget, and goes home
	 * once no seen strike is bound for the link. An unguarded rear link is never
	 * given up for it.</li>
	 * </ul>
	 */
	protected static boolean garrison(Outpost o, MarketAPI market, float days) {
		if (!needsGarrison(market)) {
			if (o.guards != null) recallGarrison(o, "garrisons are off");
			o.unguardedDays = 0f;
			return true;
		}
		boolean front = isFront(market);
		o.rearDays = front ? 0f : o.rearDays + days;
		if (o.homebound != null) {
			boolean sailing = false;
			for (CampaignFleetAPI f : o.homebound) sailing |= f != null && f.isAlive() && !f.isDespawning();
			if (!sailing) o.homebound = null; // home: the save keeps no dead fleets
		}
		List<CampaignFleetAPI> live = liveGuards(o);
		long now = Global.getSector().getClock().getTimestamp();
		if (!live.isEmpty()) {
			o.guards = live; // the dead and despawned are not kept in the save
			o.unguardedDays = 0f;
			// behind the front for a while (a hive's fuel range drifts) and nothing
			// seen coming - run 17: 4 of 7 went home with a strike launched but not
			// yet seen (seen a median 16 days after launch, up to 59)
			if (o.rearDays >= ThreatIncConfig.frontlineRearGraceDays() && strikesOn(market).isEmpty()) {
				recallGarrison(o, "behind the front, no strike on it");
				o.homebound = live;
				o.guardSent = 0L; // if it comes to the front again, guard it at once
				return true;
			}
			// more coming than the guard on station outweighs (run 16: two strikes,
			// 2,700, met Yami's 1,496 FP garrison and nothing was called)
			callGuard(o, market, strengthOf(live), false);
			float since = Global.getSector().getClock().getElapsedDaysSince(o.guardPaid);
			if (since >= 30f) {
				o.guardPaid = now;
				float want = 0f;
				for (CampaignFleetAPI f : live) want += maintenancePerMonth(f) * since / 30f;
				float paid = payUpkeep(o, market, want);
				if (paid < want * 0.5f) {
					recallGarrison(o, "unpaid: " + (int) paid + " of " + (int) want + " supplies");
					o.guardSent = now; // could not pay: no new one for a month
					return true;
				}
				topUp(o, market, live);
			}
			return true;
		}
		if (o.guards != null) {
			ThreatIncConfig.log("Frontline: the garrison of " + market.getName() + " is gone");
			o.guards = null;
			o.guardBaseId = null;
			o.guardFP = 0f;
		}
		// a seen strike on it calls a guard, front or rear - also after a front
		// garrison went home unpaid (run 15: Eps Golgotha I's seen strike landed
		// 17 days after its recall, with no guard called)
		if (callGuard(o, market, 0f, false)) return true;
		if (!front) {
			o.unguardedDays = 0f; // the rear stands unguarded until a strike is seen coming
			return true;
		}
		o.unguardedDays += days;
		if (o.unguardedDays >= ThreatIncConfig.frontlineAbandonDays()) {
			dismantle(o, market, "no garrison to hold it");
			return false;
		}
		if (Global.getSector().getClock().getElapsedDaysSince(o.guardSent) >= 30f) {
			o.guardSent = now; // one try a month, sent or not
			float need = guardNeed(market.getPrimaryEntity(), market);
			if (need < 30f * STRENGTH_PER_FP) {
				o.unguardedDays = 0f; // nothing in reach it must be guarded against
				return true;
			}
			MarketAPI base = garrisonBase(market.getFaction(), market.getPrimaryEntity(), need / STRENGTH_PER_FP,
					frontOf(market.getFactionId(), null));
			if (base == null || !sendGarrison(o, market, base, need)) {
				ThreatIncConfig.logQuiet("fl_noguard_" + market.getId(), "Frontline: " + market.getFactionId()
						+ " cannot garrison " + market.getName() + " - "
						+ (base == null ? noGarrisonWhy : "no fleet came out of " + base.getName()));
			}
		}
		return true;
	}

	/**
	 * The hives grow: a garrison that no longer outweighs what it must hold
	 * against (needNow) by the margin - under 80% of it - is reinforced from its
	 * home base, if that base can spare it and the faction can pay for it. A
	 * rear link's guard answers a strike, outside the upkeep budget.
	 */
	protected static void topUp(Outpost o, MarketAPI market, List<CampaignFleetAPI> live) {
		MarketAPI home = homeOf(o);
		if (home == null || home.getPrimaryEntity() == null || !IncursionManager.isBase(home)) return;
		float need = needNow(market);
		float have = strengthOf(live);
		if (have >= need * 0.8f) return;
		float shortFP = (need - have) / STRENGTH_PER_FP;
		FactionAPI faction = market.getFaction();
		float budget = upkeepBudget(faction.getId());
		float upkeep = isFront(market)
				? garrisonUpkeep(faction.getId(), frontOf(faction.getId(), null)) + shortFP * UPKEEP_PER_FP : 0f;
		String why = null;
		if (upkeep > budget) why = "upkeep would be " + (int) upkeep + " of " + (int) budget + " supplies a month";
		else if (navySpareFP(faction) < shortFP) why = "its navy cannot spare " + (int) shortFP + " FP";
		else if (!canPayVoyage(home, shortFP, ly(home, market))) why = "cannot pay the voyage from " + home.getName();
		if (why != null) {
			ThreatIncConfig.logQuiet("fl_topup_" + market.getId(), "Frontline: the garrison of " + market.getName()
					+ " weighs " + (int) have + " of " + (int) need + " - no reinforcement: " + why);
			return;
		}
		sendGarrison(o, market, home, need - have);
	}

	/**
	 * A fleet's upkeep: vanilla's supplies per month of each ship (ship_data's
	 * supplies/mo, as modified) - maintenance only. getTotalSuppliesPerDay adds
	 * repair and CR recovery and billed the long test's garrisons up to
	 * 12,901 supplies a month.
	 */
	protected static float maintenancePerMonth(CampaignFleetAPI fleet) {
		float sum = 0f;
		for (com.fs.starfarer.api.fleet.FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			sum += m.getStats().getSuppliesPerMonth().getModifiedValue();
		}
		return sum;
	}

	/**
	 * Pays a garrison's upkeep: the link's reserve first, then its home base,
	 * then the faction's other markets nearest the link - the faction pays for
	 * the fleet, not one depot, out of the same markets upkeepBudget counts the
	 * banking of. Other links keep theirs for their own garrisons. Returns what
	 * was paid.
	 */
	protected static float payUpkeep(Outpost o, MarketAPI market, float want) {
		float link = ThreatReserves.drawAbove(market, Commodities.SUPPLIES, want);
		MarketAPI home = homeOf(o);
		float fromHome = link < want && home != null ? ThreatReserves.drawAbove(home, Commodities.SUPPLIES, want - link) : 0f;
		float paid = link + fromHome;
		if (paid < want) paid += payFromOthers(market, home, Commodities.SUPPLIES, want - paid);
		ThreatIncConfig.log("Frontline upkeep of " + market.getName() + "'s garrison: paid " + (int) paid + " of "
				+ (int) want + " supplies (link " + (int) link + ", home " + (int) fromHome
				+ ", other markets " + (int) (paid - link - fromHome) + ")");
		return paid;
	}

	protected static float payFromOthers(MarketAPI market, MarketAPI home, String commodityId, float want) {
		float paid = 0f;
		List<MarketAPI> others = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(market.getFactionId())) {
			if (m != market && m != home && !isOutpost(m)) others.add(m);
		}
		final MarketAPI at = market;
		java.util.Collections.sort(others, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getLocationInHyperspace(), at.getLocationInHyperspace()),
						Misc.getDistanceLY(b.getLocationInHyperspace(), at.getLocationInHyperspace()));
			}
		});
		for (MarketAPI m : others) {
			if (paid >= want) break;
			paid += ThreatReserves.drawAbove(m, commodityId, want - paid);
		}
		return paid;
	}

	/**
	 * Real fleets from a base to hold a market's orbit: task forces summing
	 * {@code fp} (four at most unless told), on DEFEND_LOCATION for
	 * {@code days}, then home.
	 */
	protected static List<CampaignFleetAPI> spawnForce(FactionAPI faction, MarketAPI base, MarketAPI target,
			float fp, String name, float days, String what) {
		return spawnForce(faction, base, target, fp, name, days, what, 4);
	}

	protected static List<CampaignFleetAPI> spawnForce(FactionAPI faction, MarketAPI base, MarketAPI target,
			float fp, String name, float days, String what, int maxFleets) {
		List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		float perFleet = Math.max(50f, ThreatIncConfig.responseMaxDifficulty()
				* IncursionManager.FP_PER_RESPONSE_DIFFICULTY);
		float budget = fp;
		while (budget >= 30f && fleets.size() < maxFleets) {
			float size = Math.min(budget, perFleet);
			FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(),
					faction.getId(), null, FleetTypes.TASK_FORCE, size, size * 0.1f, size * 0.1f,
					0f, 0f, 0f, 0f);
			// the size asked for, not the base's fleet-size multiplier on top: the
			// long test's 400-point garrisons sailed at 756 on average
			params.ignoreMarketFleetSizeMult = true;
			CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
			if (fleet == null || fleet.isEmpty()) break;
			base.getStarSystem().addEntity(fleet);
			SectorEntityToken home = base.getPrimaryEntity();
			fleet.setLocation(home.getLocation().x, home.getLocation().y);
			fleet.setName(name);
			fleet.setNoFactionInName(false);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
			fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, target.getPrimaryEntity(), days, what);
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 1000f,
					"returning to " + base.getName());
			budget -= size;
			fleets.add(fleet);
		}
		return fleets;
	}

	/**
	 * A shortage of anything the link lives on, as vanilla's industries
	 * measure it (BaseIndustry.getMaxDeficit: demand over availability) -
	 * not the trade stockpile, which the player's buying and selling moves.
	 */
	protected static boolean warShortage(MarketAPI market) {
		for (String c : WAR_NEEDS) {
			CommodityOnMarketAPI com = market.getCommodityData(c);
			if (com == null || com.getMaxDemand() <= 0) continue;
			if (com.getMaxDemand() > com.getAvailable()) return true;
		}
		return false;
	}

	/**
	 * Whether the faction is still at war and the link still leads somewhere:
	 * the hive it was founded toward lives, or a found live hive lies within
	 * frontlineKeepLY (a link whose hive died is adopted by the next one).
	 */
	protected static boolean hasPurpose(Outpost o, MarketAPI market) {
		if (!ThreatWarState.isAtWar(o.factionId)) return false;
		if (o.hiveSystemId != null && !ThreatIncData.getLiveColonyMarkets(o.hiveSystemId).isEmpty()) {
			return true;
		}
		float keep = ThreatIncConfig.frontlineKeepLY();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			if (hive.getStarSystem() == null || !ThreatScouts.sectorKnows(hive)) continue;
			if (ly(market, hive) <= keep) {
				o.hiveSystemId = hive.getStarSystem().getId();
				return true;
			}
		}
		return false;
	}

	protected static void shrink(MarketAPI market) {
		int size = market.getSize();
		for (int i = 0; i <= 10; i++) market.removeCondition("population_" + i);
		market.addCondition("population_" + (size - 1));
		market.setSize(size - 1);
		market.reapplyConditions();
		market.reapplyIndustries();
	}

	/**
	 * Takes the link down the way vanilla tears a market down (DecivTracker,
	 * PirateBaseIntel.notifyEnding): people and industries off - the station
	 * industry takes its fleet with it - then out of the economy, chatter
	 * cleared, and the station entity fades. A link has no storage, so
	 * nothing of the player's goes with it.
	 */
	protected static void dismantle(Outpost o, MarketAPI market, String why) {
		all().remove(o);
		frontDay = -1; // the front moved
		recallGarrison(o, "the link is gone");
		// given up, not destroyed: its stock goes home with the garrison. Run 12
		// lost 2,299 marines a front had just evacuated into a link that starved
		// 15 days later
		if (!why.startsWith("station destroyed")) carryStockHome(o, market);
		SectorEntityToken entity = market.getPrimaryEntity();
		market.setAdmin(null);
		market.getCommDirectory().clear();
		for (PersonAPI person : market.getPeopleCopy()) market.removePerson(person);
		for (Industry ind : new ArrayList<Industry>(market.getIndustries())) {
			market.removeIndustry(ind.getId(), null, false);
		}
		Global.getSector().getEconomy().removeMarket(market);
		Misc.removeRadioChatter(market);
		market.advance(0f);
		if (entity != null) {
			entity.setMarket(null);
			Misc.fadeAndExpire(entity);
		}
		ThreatIncConfig.log("Frontline: " + o.factionId + " dismantled " + market.getName() + " (" + why + ")");
	}

	/** Moves a link's reserve to the faction's nearest other market that is not a link. */
	protected static void carryStockHome(Outpost o, MarketAPI market) {
		MarketAPI home = null;
		float best = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(o.factionId)) {
			if (m == market || isOutpost(m) || m.getPrimaryEntity() == null) continue;
			float d = Misc.getDistanceLY(m.getLocationInHyperspace(), market.getLocationInHyperspace());
			if (d < best) {
				best = d;
				home = m;
			}
		}
		if (home == null) return;
		StringBuilder moved = new StringBuilder();
		for (String c : ThreatReserves.COMMODITIES) {
			float amount = ThreatReserves.stock(market.getId(), c);
			if (amount < 1f) continue;
			float took = ThreatReserves.draw(market.getId(), c, amount);
			ThreatReserves.deposit(home.getId(), c, took);
			if (moved.length() > 0) moved.append(", ");
			moved.append((int) took).append(" ").append(c);
		}
		if (moved.length() > 0) {
			ThreatIncConfig.log("Frontline: " + market.getName() + "'s stock carried to " + home.getName()
					+ ": " + moved);
		}
	}

	/**
	 * One project at a time, in order: Patrol HQ and battlestation (3),
	 * Military Base and star fortress (4) - each only if every commodity it
	 * demands can be had here
	 * (canSupply). Demands are vanilla's (industries.csv / the industry
	 * classes), s being the market size.
	 */
	protected static void build(MarketAPI market) {
		for (Industry ind : market.getIndustries()) {
			// Population reads as upgrading whenever the market is below its
			// max size - vanilla's growth bar, not a project
			// (PopulationAndInfrastructure.isUpgrading)
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (ind.isBuilding() || ind.isUpgrading()) {
				ThreatIncConfig.logQuiet("fl_busy_" + market.getId(), "Frontline: " + market.getName()
						+ " waits on " + ind.getId());
				return;
			}
		}
		int s = market.getSize();
		// no ground works: nothing lands on a station (vanilla's pirate base
		// has none either). What cannot be supplied waits; the steps after it
		// still get their turn
		if (s >= 3 && !market.hasIndustry(Industries.PATROLHQ)
				&& !market.hasIndustry(Industries.MILITARYBASE)) {
			if (canSupplyMilitary(market, s - 1)) {
				startNew(market, Industries.PATROLHQ);
				return;
			}
		}
		Industry station = stationOf(market);
		if (s >= 3 && station != null && stationTier(station) == 1) {
			if (canSupply(market, Commodities.CREW, 5) && canSupply(market, Commodities.SUPPLIES, 5)) {
				upgrade(market, station);
				return;
			}
		}
		if (s >= 4 && market.hasIndustry(Industries.PATROLHQ)) {
			if (canSupplyMilitary(market, s + 1)) {
				upgrade(market, market.getIndustry(Industries.PATROLHQ));
				return;
			}
		}
		if (s >= 4 && station != null && stationTier(station) == 2) {
			if (canSupply(market, Commodities.CREW, 7) && canSupply(market, Commodities.SUPPLIES, 7)) {
				upgrade(market, station);
				return;
			}
		}
	}

	protected static boolean canSupplyMilitary(MarketAPI market, int qty) {
		return canSupply(market, Commodities.SUPPLIES, qty) && canSupply(market, Commodities.FUEL, qty)
				&& canSupply(market, Commodities.SHIPS, qty);
	}

	/**
	 * Whether this market gets {@code qty} of the commodity: local supply, or
	 * the better of the in-faction and global import routes, each the lesser
	 * of what the best exporter ships and what this market's accessibility
	 * can take in - vanilla's own reckoning (PopulationAndInfrastructure.
	 * modifyStability).
	 */
	protected static boolean canSupply(MarketAPI market, String commodity, int qty) {
		if (qty <= 0) return true;
		CommodityOnMarketAPI com = market.getCommodityData(commodity);
		if (com == null) return false;
		int local = com.getMaxSupply();
		if (local >= qty) return true;
		CommodityMarketDataAPI cmd = com.getCommodityMarketData();
		if (cmd == null) return false;
		int inFaction = Math.min(cmd.getMaxShipping(market, true), cmd.getMaxExport(market.getFactionId()));
		int global = Math.min(cmd.getMaxShipping(market, false), cmd.getMaxExportGlobal());
		boolean ok = Math.max(local, Math.max(inFaction, global)) >= qty;
		if (!ok) {
			ThreatIncConfig.logQuiet("fl_supply_" + market.getId() + "_" + commodity, "Frontline: "
					+ market.getName() + " cannot supply " + qty + " " + commodity + " (local " + local
					+ ", in-faction ship " + cmd.getMaxShipping(market, true) + "/export "
					+ cmd.getMaxExport(market.getFactionId()) + ", global ship "
					+ cmd.getMaxShipping(market, false) + "/export " + cmd.getMaxExportGlobal()
					+ ", demand " + com.getMaxDemand() + ", available " + com.getAvailable()
					+ ", access " + (int) (market.getAccessibilityMod().computeEffective(0f) * 100f) + "%)");
		}
		return ok;
	}

	protected static void startNew(MarketAPI market, String id) {
		market.addIndustry(id);
		Industry ind = market.getIndustry(id);
		if (ind != null) ind.startBuilding();
		ThreatIncConfig.log("Frontline: " + market.getName() + " builds " + id);
	}

	protected static void upgrade(MarketAPI market, Industry ind) {
		if (ind == null || ind.getSpec().getUpgrade() == null) return;
		ind.startUpgrading();
		ThreatIncConfig.log("Frontline: " + market.getName() + " upgrades " + ind.getId()
				+ " to " + ind.getSpec().getUpgrade());
	}

	/**
	 * Whether the base's station stands: its station industry, not disrupted.
	 * The station fleet is no witness far from the player - the 2026-09-26
	 * long test read every intact station there as down.
	 */
	public static boolean stationUp(MarketAPI market) {
		Industry station = stationOf(market);
		return station != null && !station.isDisrupted();
	}

	/** The station's fleet points by tier: vanilla's station hulls (station1/2/3 in ship_data.csv). */
	public static float stationFP(MarketAPI market) {
		if (!stationUp(market)) return 0f;
		int tier = stationTier(stationOf(market));
		return tier == 3 ? 400f : tier == 2 ? 200f : 100f;
	}

	protected static Industry stationOf(MarketAPI market) {
		for (Industry ind : market.getIndustries()) {
			if (ind.getSpec().hasTag(Industries.TAG_STATION)) return ind;
		}
		return null;
	}

	/** 1 orbital station, 2 battlestation, 3 star fortress. */
	protected static int stationTier(Industry station) {
		if (station.getSpec().hasTag(Industries.TAG_STARFORTRESS)) return 3;
		if (station.getSpec().hasTag(Industries.TAG_BATTLESTATION)) return 2;
		return 1;
	}

	/** The faction's orbital station line: high tech for Tri-Tachyon, low for the rough ones. */
	protected static String orbitalStationFor(FactionAPI faction) {
		String id = faction.getId();
		if (Factions.TRITACHYON.equals(id)) return Industries.ORBITALSTATION_HIGH;
		if (Factions.HEGEMONY.equals(id) || Factions.LUDDIC_CHURCH.equals(id)
				|| Factions.LUDDIC_PATH.equals(id) || Factions.PIRATES.equals(id)) {
			return Industries.ORBITALSTATION;
		}
		return Industries.ORBITALSTATION_MID;
	}

	// ------------------------------------------------------------------
	// planning: where the chain goes next
	// ------------------------------------------------------------------

	/**
	 * Each mobilised NPC faction under its cap founds at most one link per
	 * pass: toward the nearest found hive that none of its bases or links is
	 * within frontlineReachLY of, from the connected colony or link nearest
	 * that hive, on the uncolonised world within frontlineLinkLY of it that
	 * gets closest to the hive.
	 */
	protected static void plan(Random random) {
		List<StarSystemAPI> hiveSystems = new ArrayList<StarSystemAPI>();
		Set<String> seen = new HashSet<String>();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || seen.contains(sys.getId())) continue;
			if (!ThreatScouts.sectorKnows(hive)) continue;
			seen.add(sys.getId());
			hiveSystems.add(sys);
		}
		if (hiveSystems.isEmpty()) return;

		for (String fid : ThreatWarState.warFactionIds()) {
			if (Factions.PLAYER.equals(fid) || Factions.THREAT.equals(fid)) continue;
			if (ThreatWarState.excluded(fid)) continue;
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null) continue;
			int count = 0;
			for (Outpost o : all()) if (fid.equals(o.factionId)) count++;
			// 0 = no cap: founding is paid from the reserves, and that is the limit
			int max = ThreatIncConfig.frontlineMaxPerFaction();
			if (max > 0 && count >= max) continue;
			planFor(faction, hiveSystems);
		}
	}

	protected static void planFor(FactionAPI faction, List<StarSystemAPI> hiveSystems) {
		String fid = faction.getId();
		Set<String> conn = connected(fid, null);
		List<MarketAPI> anchors = new ArrayList<MarketAPI>();   // colonies and connected links
		List<MarketAPI> reaching = new ArrayList<MarketAPI>();  // bases and every link
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!fid.equals(m.getFactionId()) || m.isHidden() || m.getPrimaryEntity() == null) continue;
			boolean link = isOutpost(m);
			if (!link || conn.contains(m.getId())) anchors.add(m);
			if (link || IncursionManager.isBase(m)) reaching.add(m);
		}
		if (anchors.isEmpty()) return;

		float reach = ThreatIncConfig.frontlineReachLY();
		float hop = ThreatIncConfig.frontlineLinkLY();
		// every (unreached hive, anchor) pair, nearest first: a pair with no
		// site (an anchor boxed in beside a hive, no open world in range)
		// must not stop the faction building toward anything else
		final Map<Object[], Float> dist = new HashMap<Object[], Float>();
		List<Object[]> pairs = new ArrayList<Object[]>();
		for (StarSystemAPI hive : hiveSystems) {
			boolean reached = false;
			for (MarketAPI r : reaching) {
				if (ly(r, hive) <= reach) {
					reached = true;
					break;
				}
			}
			if (reached) continue;
			for (MarketAPI a : anchors) {
				Object[] pair = new Object[] { hive, a };
				dist.put(pair, ly(a, hive));
				pairs.add(pair);
			}
		}
		if (pairs.isEmpty()) return;
		java.util.Collections.sort(pairs, new java.util.Comparator<Object[]>() {
			public int compare(Object[] x, Object[] y) {
				return Float.compare(dist.get(x), dist.get(y));
			}
		});
		int tries = 0;
		for (Object[] pair : pairs) {
			if (++tries > 24) break;
			StarSystemAPI hive = (StarSystemAPI) pair[0];
			MarketAPI anchor = (MarketAPI) pair[1];
			PlanetAPI site = pickSite(faction, anchor, hive, hop, reach);
			if (site != null) {
				MarketAPI payer = payer(faction, site);
				if (payer == null) {
					ThreatIncConfig.log("Frontline: " + fid + " cannot pay for a link at "
							+ site.getName() + " - no base holds the stock");
					return;
				}
				// no paper bases: a link is founded only with a garrison to hold it
				// against the strikes in reach, for as long as it stands
				MarketAPI guardBase = null;
				if (ThreatIncConfig.frontlineGarrisonEnabled()) {
					guardBase = garrisonBase(faction, site);
					if (guardBase == null) {
						ThreatIncConfig.logQuiet("fl_cannotguard_" + fid, "Frontline: " + fid
								+ " cannot garrison a link at " + site.getName() + " - " + noGarrisonWhy);
						return;
					}
				}
				float[] cost = ThreatOutposts.npcCost();
				ThreatReserves.drawAbove(payer, Commodities.SUPPLIES, cost[0]);
				ThreatReserves.drawAbove(payer, Commodities.FUEL, cost[1]);
				MarketAPI link = found(faction, site, hive);
				ThreatIncConfig.log("Frontline: paid " + (int) cost[0] + " supplies and "
						+ (int) cost[1] + " fuel from " + payer.getName()
						+ (link == null ? " (founding failed)" : ""));
				if (link != null && guardBase != null) sendGarrison(find(link), link, guardBase);
				return;
			}
		}
		Object[] first = pairs.get(0);
		ThreatIncConfig.log("Frontline: " + fid + " has no site toward "
				+ ((StarSystemAPI) first[0]).getName() + " (tried " + Math.min(tries, pairs.size())
				+ " anchor(s))");
	}

	/**
	 * The open world within {@code hop} of the anchor that ends up nearest
	 * the hive - accepted if it gets at least 1 LY closer than the anchor,
	 * or lies within {@code reach} of the hive outright.
	 */
	protected static PlanetAPI pickSite(FactionAPI faction, MarketAPI anchor, StarSystemAPI hive,
			float hop, float reach) {
		float anchorToHive = ly(anchor, hive);
		PlanetAPI best = null;
		float bestLeft = Math.max(anchorToHive - 1f, reach + 0.01f);
		for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
			if (sys == hive || sys.hasTag(Tags.THEME_HIDDEN) || sys.hasTag(Tags.SYSTEM_CUT_OFF_FROM_HYPER)) {
				continue;
			}
			if (Misc.getDistanceLY(anchor.getLocationInHyperspace(), sys.getLocation()) > hop) continue;
			float left = Misc.getDistanceLY(sys.getLocation(), hive.getLocation());
			if (left >= bestLeft) continue;
			if (!ThreatIncData.getLiveColonyMarkets(sys.getId()).isEmpty()) continue;
			if (!siteSystemOk(faction, sys)) continue;
			for (PlanetAPI planet : sys.getPlanets()) {
				if (!ThreatOutposts.eligible(planet) || hostsLink(planet)) continue;
				best = planet;
				bestLeft = left;
				break;
			}
		}
		return best;
	}

	/**
	 * The faction's base nearest the site whose war reserve covers the
	 * founding cost (the outpost cost), or null. No range: the stock sails up
	 * the chain, and a tip far past every expedition range still gets built.
	 */
	protected static MarketAPI payer(FactionAPI faction, PlanetAPI site) {
		float[] cost = ThreatOutposts.npcCost();
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			float d = Misc.getDistanceLY(m.getLocationInHyperspace(), site.getLocationInHyperspace());
			if (d >= bestDist) continue;
			if (ThreatReserves.available(m, Commodities.SUPPLIES) < cost[0]) continue;
			if (ThreatReserves.available(m, Commodities.FUEL) < cost[1]) continue;
			bestDist = d;
			best = m;
		}
		return best;
	}

	/** The nearest found live hive's system within frontlineKeepLY of this world, or null. */
	public static StarSystemAPI hiveNear(SectorEntityToken planet) {
		if (planet == null) return null;
		StarSystemAPI best = null;
		float bestDist = ThreatIncConfig.frontlineKeepLY();
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || !ThreatScouts.sectorKnows(hive)) continue;
			float d = Misc.getDistanceLY(planet.getLocationInHyperspace(), sys.getLocation());
			if (d > bestDist) continue;
			bestDist = d;
			best = sys;
		}
		return best;
	}

	/** Whether any faction's link already orbits this planet: one station per world. */
	public static boolean hostsLink(SectorEntityToken planet) {
		if (planet == null) return false;
		for (Outpost o : all()) {
			MarketAPI m = marketOf(o);
			if (m != null && m.getPrimaryEntity() != null
					&& m.getPrimaryEntity().getOrbitFocus() == planet) {
				return true;
			}
		}
		return false;
	}

	/** No hostile market in the system, and no link of this faction or another (linkTaken). */
	protected static boolean siteSystemOk(FactionAPI faction, StarSystemAPI sys) {
		for (MarketAPI m : Global.getSector().getEconomy().getMarkets(sys)) {
			if (m.getFaction() == null) continue;
			if (m.getFaction().isHostileTo(faction)) return false;
		}
		return !linkTaken(sys);
	}

	/**
	 * Whether a system already holds a link, of any faction: one faction's
	 * forward bases per system (user's call 2026-09-27). Run 15's Alpha Vigri
	 * held four factions' links; when vanilla later made three of them hostile,
	 * their garrisons fought and four stations fell with no Threat there.
	 */
	public static boolean linkTaken(StarSystemAPI sys) {
		if (sys == null) return false;
		for (MarketAPI m : Global.getSector().getEconomy().getMarkets(sys)) {
			if (isOutpost(m)) return true;
		}
		return false;
	}

	/** Founds a size-1 link on a station over the planet. */
	public static MarketAPI found(FactionAPI faction, PlanetAPI planet, StarSystemAPI hive) {
		StarSystemAPI system = planet.getStarSystem();
		String name = planet.getName() + " Forward Base";
		// vanilla's pirate-base entity: tagged station and use_station_visual,
		// so the station industry adopts it and dresses it in its own look
		SectorEntityToken entity = system.addCustomEntity(null, name,
				Entities.MAKESHIFT_STATION, faction.getId());
		float orbitRadius = planet.getRadius() + 150f;
		entity.setCircularOrbitWithSpin(planet, (float) Math.random() * 360f, orbitRadius,
				orbitRadius / 10f, 5f, 5f);
		entity.setDiscoverable(false);

		MarketAPI market = Global.getFactory().createMarket("threatinc_fl_" + Misc.genUID(), name, 1);
		market.setFactionId(faction.getId());
		market.setPrimaryEntity(entity);
		if (!market.getConnectedEntities().contains(entity)) market.getConnectedEntities().add(entity);
		entity.setMarket(market);
		entity.setFaction(faction.getId());
		market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
		market.setInvalidMissionTarget(true);
		market.addCondition("population_1");
		market.addCondition(CONDITION);
		market.addIndustry(Industries.POPULATION);
		market.addIndustry(Industries.SPACEPORT);
		market.addIndustry(Industries.WAYSTATION);
		market.addIndustry(orbitalStationFor(faction));
		// no storage: a link can be dismantled, and the player's goods must not go with it
		market.addSubmarket(Submarkets.SUBMARKET_OPEN);
		market.getTariff().modifyFlat("default_tariff", faction.getTariffFraction());
		market.getMemoryWithoutUpdate().set(OUTPOST_FLAG, true);
		// vanilla's slow decivilisation (stability) never takes a link; the
		// swarm's ground victory still does (a full destroy ignores this)
		market.getMemoryWithoutUpdate().set(DecivTracker.NO_DECIV_KEY, true);

		PersonAPI admin = faction.createRandomPerson();
		admin.setRankId(Ranks.SPACE_COMMANDER);
		admin.setPostId(Ranks.POST_STATION_COMMANDER);
		market.setAdmin(admin);
		market.getCommDirectory().addPerson(admin);
		market.addPerson(admin);

		// no orbital junk or chatter: a link may be dismantled, and would leave them behind
		Global.getSector().getEconomy().addMarket(market, false);
		market.reapplyIndustries();

		Outpost o = new Outpost();
		o.marketId = market.getId();
		o.entityId = entity.getId();
		o.factionId = faction.getId();
		o.hiveSystemId = hive != null ? hive.getId() : null;
		o.founded = Global.getSector().getClock().getTimestamp();
		all().add(o);
		updateRelays();

		ThreatIncConfig.log("Frontline: " + faction.getId() + " founded " + name + " in "
				+ system.getName() + (hive != null ? " toward " + hive.getName() : ""));
		return market;
	}

	// ------------------------------------------------------------------
	// strike warning and the called guard
	// ------------------------------------------------------------------

	/**
	 * Who sees this Threat fleet, or null: the player's own sensors; any
	 * colony or outpost sharing its system; in hyperspace, a frontline
	 * outpost or military world within strikeDetectLY. The warning is the
	 * sector's - the Threat is everyone's enemy.
	 */
	public static String detectedBy(CampaignFleetAPI fleet) {
		if (fleet == null || !fleet.isAlive()) return null;
		if (fleet.getVisibilityLevelToPlayerFleet() != VisibilityLevel.NONE) return "your fleet";
		return detectedAt(fleet.getContainingLocation(), fleet.getLocationInHyperspace());
	}

	/**
	 * The same pickets for a place rather than a fleet - a strike far from
	 * the player flies as an abstract route with no fleets spawned.
	 */
	public static String detectedAt(LocationAPI loc, Vector2f hyperLoc) {
		if (loc == null) return null;
		if (!loc.isHyperspace()) {
			for (MarketAPI m : Global.getSector().getEconomy().getMarkets(loc)) {
				if (watches(m)) return m.getName();
			}
			return null;
		}
		float range = ThreatIncConfig.strikeDetectLY();
		if (range <= 0f || hyperLoc == null) return null;
		Vector2f p = hyperLoc;
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!watches(m)) continue;
			if (!isOutpost(m) && !IncursionManager.hasMilitary(m)) continue;
			if (Misc.getDistanceLY(p, m.getLocationInHyperspace()) <= range) return m.getName();
		}
		return null;
	}

	protected static boolean watches(MarketAPI m) {
		if (m == null || m.isHidden() || m.getPrimaryEntity() == null || !m.isInEconomy()) return false;
		if (Factions.THREAT.equals(m.getFactionId())) return false;
		if (m.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) return false;
		if (!m.isPlayerOwned() && ThreatWarState.excluded(m.getFactionId())) return false;
		return true;
	}

	/**
	 * A seen strike against a link calls its guard (user's call 2026-09-27):
	 * a rear link has none standing, and a front link's may be outweighed by
	 * what is coming (callGuard, which also times the voyage). The guard goes
	 * home once no seen strike is bound for a rear link (garrison).
	 */
	public static void sendRelief(ThreatStrikeFGI strike, Random random) {
		if (!ThreatIncConfig.frontlinesEnabled() || !ThreatIncConfig.frontlineReliefEnabled()) return;
		if (!ThreatIncConfig.frontlineGarrisonEnabled()) return;
		if (strike.getParams() == null || strike.getParams().raidParams == null) return;
		for (MarketAPI target : strike.getParams().raidParams.allowedTargets) {
			if (target == null || !target.isInEconomy() || !isOutpost(target)) continue;
			Outpost o = find(target);
			if (o != null) relieve(o, target);
		}
	}

	protected static void relieve(Outpost o, MarketAPI target) {
		FactionAPI faction = target.getFaction();
		if (faction == null || !ThreatWarState.isAtWar(faction)) return;
		callGuard(o, target, strengthOf(liveGuards(o)), true);
	}

	/** Days a guard takes from muster to station per LY sailed (run 16: 13 LY ~18 d, 6.5 LY ~10 d). */
	protected static final float GUARD_DAYS_PER_LY = 1.5f;
	/** Days early a called guard sails beyond its voyage: muster, and the strike's ETA is vanilla's estimate. */
	protected static final float GUARD_LEAD_DAYS = 20f;

	/**
	 * Days until the first seen strike bound for the market strikes (0 if
	 * striking now), or -1 with none. Far from the player a strike flies as a
	 * route, and vanilla autoresolves it when its payload stage ends
	 * (FGRaidAction.notifySegmentFinished) - siegeOrbitDays after it arrives.
	 * Run 17 timed guards on the arrival, and called them ~160 days early.
	 * Spawned fleets fight on arrival.
	 */
	protected static float strikeEta(MarketAPI market) {
		float best = -1f;
		for (ThreatStrikeFGI strike : strikesOn(market)) {
			float eta;
			if (strike.isSpawnedFleets()) {
				eta = strike.isCurrent(GenericRaidFGI.PAYLOAD_ACTION) ? 0f
						: strike.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION);
			} else {
				eta = strike.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION, true);
			}
			eta = Math.max(0f, eta);
			if (best < 0f || eta < best) best = eta;
		}
		return best;
	}

	/**
	 * Turns the guard sent home from behind the front back to the link, while
	 * it still sails: its fleets are the navy's already, and a strike seen
	 * after it left is what they were there for. The strength turned back.
	 */
	protected static float turnBack(Outpost o, MarketAPI market) {
		if (o.homebound == null) return 0f;
		List<CampaignFleetAPI> back = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI f : o.homebound) {
			if (f != null && f.isAlive() && !f.isDespawning()) back.add(f);
		}
		o.homebound = null;
		if (back.isEmpty() || market.getPrimaryEntity() == null) return 0f;
		MarketAPI home = nearestBase(market.getFaction(), market.getPrimaryEntity());
		if (home == null || home.getPrimaryEntity() == null) return 0f;
		for (CampaignFleetAPI f : back) {
			f.clearAssignments();
			f.addAssignment(FleetAssignment.DEFEND_LOCATION, market.getPrimaryEntity(), 100000f,
					"garrisoning " + market.getName());
			f.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home.getPrimaryEntity(), 1000f,
					"returning to " + home.getName());
		}
		boolean topUp = !liveGuards(o).isEmpty();
		if (!topUp) {
			o.guards = new ArrayList<CampaignFleetAPI>();
			o.guardFP = 0f;
			o.guardPaid = Global.getSector().getClock().getTimestamp();
			o.guardBaseId = home.getId();
		}
		o.guards.addAll(back);
		o.guardFP += fpOf(back);
		o.unguardedDays = 0f;
		float str = strengthOf(back);
		ThreatIncConfig.log("Frontline: the guard sailing home from " + market.getName() + " turned back, "
				+ back.size() + " fleet(s), " + (int) fpOf(back) + " FP, strength " + (int) str
				+ " (rear; strike on its way " + (int) strikesWeight(market) + ")");
		return str;
	}

	/**
	 * Calls a guard against the seen strikes bound for the link, outside the
	 * upkeep budget: what they weigh x frontlineGarrisonMargin, less the
	 * link's station, the guards already there ({@code have}) and those of the
	 * faction's other links in the system. A guard sent home from behind the
	 * front and still sailing turns back at once (turnBack). A new one sails
	 * only once the first strike is due (strikeEta) within the voyage from the
	 * nearest colony base plus GUARD_LEAD_DAYS - run 16's strikes took 159-203
	 * days from launch to target, and guards called at detection sat on
	 * station for months. The daily step asks again until then; a refusal
	 * waits a week. A guard on station is reinforced only under 80% of what it
	 * must weigh. True if a guard sailed or turned back.
	 */
	protected static boolean callGuard(Outpost o, MarketAPI market, float have, boolean atDetection) {
		float want = onCallNeed(market) - neighbourGuards(o, market);
		float need = want - have;
		if (need < 30f * STRENGTH_PER_FP) return false;
		// a guard on station is reinforced only once it falls under 80% of what
		// it must weigh, as topUp does - run 17's Akron took 9 top-ups of 37-524
		// FP in 80 days
		if (have > 0f && have >= want * 0.8f) return false;
		// a guard still sailing home turns back at once, whenever the strike is due
		need -= turnBack(o, market);
		if (need < 30f * STRENGTH_PER_FP) return true;
		if (Global.getSector().getClock().getElapsedDaysSince(o.guardCalled) < 7f) return false;
		FactionAPI faction = market.getFaction();
		MarketAPI near = nearestBase(faction, market.getPrimaryEntity());
		float eta = strikeEta(market);
		float voyage = near != null ? ly(near, market) * GUARD_DAYS_PER_LY + GUARD_LEAD_DAYS : 0f;
		if (near != null && eta > voyage) return false; // not yet
		o.guardCalled = Global.getSector().getClock().getTimestamp();
		MarketAPI base = garrisonBase(faction, market.getPrimaryEntity(), need / STRENGTH_PER_FP, null);
		if (base != null && sendGarrison(o, market, base, need)) return true;
		String why = (base == null ? noGarrisonWhy : "no fleet came out of " + base.getName())
				+ " (strike in " + (int) eta + " d, voyage " + (int) voyage + " d)";
		if (atDetection) {
			ThreatIncConfig.log("Frontline: " + market.getName() + " faces the strike " + (int) need
					+ " short (guards on station " + (int) have + ") - no guard called: " + why);
		} else {
			ThreatIncConfig.logQuiet("fl_nocall_" + market.getId(), "Frontline: " + market.getFactionId()
					+ " cannot guard " + market.getName() + " against the strike on it - " + why);
		}
		return false;
	}
}
