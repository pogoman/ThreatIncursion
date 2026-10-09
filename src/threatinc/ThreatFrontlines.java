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
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;

import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteSegment;
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
 * every {@code frontlineGrowDays} it runs without war shortages, up to
 * vanilla's own max market size.</li>
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
		public String factionId;
		/** The hive system the link was founded toward (the log's and the board's reason). */
		public String hiveSystemId;
		/** Growth toward the next size, in days of the full pace (under size upkeep it runs down while the link starves). */
		public float healthyDays;
		public float starvedDays;
		/** Under size upkeep: the share of its upkeep the link was paid on its last day (feedSize). */
		public float fedShare;
		public float idleDays;
		/** The station fleet the loss listener is on (vanilla respawns it after repairs). */
		public String stationFleetId;
		/** The garrison holding the link for as long as it stands (ThreatFrontlines.garrison). */
		public List<CampaignFleetAPI> guards;
		/** The base the garrison sailed from, whose strength it is committed out of. */
		public String guardBaseId;
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
	/** Fleet memory: when the fleet joined a link's guard, the day its upkeep is billed from. */
	public static final String GUARD_JOINED_KEY = "$threatinc_frontlineGuardJoined";

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
		n.line("%s %s fell with its station", ThreatNotice.faction(faction), ThreatNotice.market(market));
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

	/**
	 * Called on IncursionManager's fast poll; does its work once a day. The
	 * frontlinesEnabled knob gates founding only: the links standing are kept
	 * up, paid and pruned with it off.
	 */
	public static void poll(Random random) {
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
		homeYards();

		Object lastPlan = data.get(KEY_LAST_PLAN);
		float sincePlan = lastPlan instanceof Long
				? Global.getSector().getClock().getElapsedDaysSince((Long) lastPlan) : 9999f;
		if (sincePlan >= ThreatIncConfig.frontlinePlanDays() && ThreatIncConfig.frontlinesEnabled()) {
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
					+ ", supplies " + (int) ThreatReserves.factionStock(fid, Commodities.SUPPLIES)
					+ (ThreatFactionStance.enabled() ? ", stance " + ThreatFactionStance.stanceName(fid)
							+ (ThreatFactionStance.target(fid) != null ? " at " + ThreatFactionStance.target(fid) : "")
							: ""));
		}
		int hives = 0, hiveSizes = 0, known = 0;
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			hives++;
			hiveSizes += hive.getSize();
			if (ThreatScouts.sectorKnows(hive)) known++;
		}
		ThreatIncConfig.log("Census: threat hives " + hives + " (size " + hiveSizes + "), found "
				+ known + ", " + ThreatColonyManager.hiveLedgerSummary() + ThreatFuel.monthSummary());
		ThreatColonyUpkeep.logMonth();
		ThreatIncConfig.log(ThreatColonyManager.awayFleetsLine());
		ThreatReach.logMonth();
		// the month's upkeep and posture lines ride the census's own 30-day beat
		ThreatColonyManager.flushUpkeepMonth();
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
			if (!factionId.equals(m.getFactionId()) || m == without || ThreatMapFog.hidden(m)) continue;
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

		// under size upkeep the link grows and starves on the supplies it is paid
		// (feedSize), not on its commodity shortages
		boolean sized = ThreatColonyUpkeep.enabled();
		if (!sized) {
			boolean short_ = warShortage(market);
			if (short_) {
				o.starvedDays += days;
				o.healthyDays = 0f;
			} else {
				o.healthyDays += days;
				o.starvedDays = Math.max(0f, o.starvedDays - days);
			}
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

		if (sized) {
			feedSize(o, market, days);
			build(market);
			return;
		}

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

		// up to vanilla's max market size: supply gates the growth (2026-09-29: the
		// frontlineMaxSize knob held every fed link at 4)
		if (o.healthyDays >= ThreatIncConfig.frontlineGrowDays()
				&& market.getSize() < Misc.getMaxMarketSize(market)) {
			o.healthyDays = 0f;
			CoreImmigrationPluginImpl.increaseMarketSize(market);
			ThreatIncConfig.log("Frontline: " + market.getName() + " grew to size " + market.getSize());
		}

		build(market);
	}

	/**
	 * The link's size upkeep for the day (ThreatColonyUpkeep, 2026-09-30):
	 * its own stock pays all of it it can; its home base and the faction's
	 * other markets in reach top it up to the break-even share - a link grows
	 * on what it holds, and its faction sends what keeps it standing. What they
	 * send arrives cut by the Threat's blockade over it (ThreatBlockade.cutOf),
	 * and they pay only for what arrives. The share paid moves its growth as a
	 * hive's does: a size per frontlineGrowDays paid in full, held at the
	 * break-even share, a size lost per starveDaysPerSize paid nothing. Sizes 1
	 * and 2 cost nothing, so no link starves out of existence.
	 */
	protected static void feedSize(Outpost o, MarketAPI market, float days) {
		float perMonth = ThreatColonyUpkeep.perMonth(market.getSize());
		float fed = 1f;
		if (perMonth > 0f) {
			float want = perMonth * days / 30f;
			float free = ThreatReserves.available(market, Commodities.SUPPLIES)
					- ThreatReserves.stagingBank(market, Commodities.SUPPLIES);
			float own = ThreatReserves.drawAbove(market, Commodities.SUPPLIES, Math.min(want, Math.max(0f, free)));
			float sent = 0f;
			float sustain = ThreatColonyUpkeep.breakEven() * want;
			float through = 1f - ThreatBlockade.cutOf(market);
			if (own < sustain && through > 0f) {
				float ask = (sustain - own) * through;
				MarketAPI home = homeOf(o);
				boolean homeReaches = home != null && Misc.getDistanceLY(home.getLocationInHyperspace(),
						market.getLocationInHyperspace()) <= ThreatConvoys.stockReachLY(home);
				if (homeReaches) sent += drawGiven(home, market, Commodities.SUPPLIES, ask);
				if (sent < ask) sent += payFromOthers(market, home, Commodities.SUPPLIES, ask - sent);
			}
			fed = Math.min(1f, (own + sent) / want);
		}
		o.fedShare = fed;
		float rate = ThreatColonyUpkeep.growthRate(fed);
		float perLevel = Math.max(1f, ThreatIncConfig.frontlineGrowDays());
		if (rate > 0f) {
			o.healthyDays += days * rate;
			if (market.getSize() >= Misc.getMaxMarketSize(market)) {
				// nothing left to grow into: a fed link banks its top level
				o.healthyDays = Math.min(perLevel, o.healthyDays);
				return;
			}
			if (o.healthyDays < perLevel) return;
			// the next size only when a month of its upkeep can be had: a link that
			// outgrows its stock starves straight back (h35a: Alpha Shero I, 4 <-> 5)
			float next = ThreatColonyUpkeep.perMonth(market.getSize() + 1);
			if (next > 0f && buildFunds(market) < next) {
				o.healthyDays = perLevel;
				return;
			}
			o.healthyDays = 0f;
			CoreImmigrationPluginImpl.increaseMarketSize(market);
			ThreatIncConfig.log("Frontline: " + market.getName() + " grew to size " + market.getSize()
					+ " (paid " + Math.round(fed * 100f) + "% of its upkeep)");
			return;
		}
		if (rate == 0f) return;
		o.healthyDays += days * rate * perLevel / ThreatColonyUpkeep.starveDays();
		// the size goes half a level below it (ThreatColonyUpkeep.SHRINK_MARGIN)
		float floor = -ThreatColonyUpkeep.SHRINK_MARGIN * perLevel;
		if (o.healthyDays >= floor) return;
		if (market.getSize() <= 1) {
			o.healthyDays = floor;
			return;
		}
		float below = o.healthyDays / perLevel;
		shrink(market);
		o.healthyDays = Math.max(0f, (1f + below) * perLevel);
		ThreatIncConfig.log("Frontline: " + market.getName() + " starved down to size " + market.getSize()
				+ " (paid " + Math.round(fed * 100f) + "% of its upkeep)");
	}

	// ------------------------------------------------------------------
	// the garrison (2026-09-26): no paper bases
	// ------------------------------------------------------------------

	/** Whether the fleet guards a link: its upkeep is the link's (payUpkeep), not ThreatUpkeep's. */
	public static boolean isGuard(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		for (Outpost o : all()) {
			if (o.guards != null && o.guards.contains(fleet)) return true;
		}
		return false;
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

	/**
	 * Whether a guard sent home is still on its tracked leg (recallGarrison):
	 * one that arrived was settled - its hulls re-banked - and is the base's
	 * again, not a guard to turn back (2026-09-29).
	 */
	protected static boolean sailingHome(CampaignFleetAPI f) {
		return f != null && f.isAlive() && !f.isDespawning() && ThreatReturns.tracked(f);
	}

	/** [fuel, supplies] a garrison of this size costs to sail this far, as the response task force pays. */
	protected static float[] voyageCost(float fp, float ly) {
		return threatinc.rules.ReachRules.voyageCost(fp, ly, ThreatIncConfig.expeditionFuelPerPointLY(),
				ThreatIncConfig.expeditionSuppliesPerPoint());
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
	 * The strike a hive world would send once its garrison is full, as the
	 * observer last saw that garrison (its report, ThreatIntel - the fog of
	 * war, 2026-10-01): its largest Defense Swarms above the reserve it keeps
	 * home - the fleets seen over it, or its own target count if more
	 * (reinforcement can fill it past that) - a strike is those swarms
	 * re-embodied. Each is weighed as vanilla's autoresolve weighs a strike far
	 * from the player, where the fights happen: its route's strength
	 * (FleetGroupIntel, 50 per size point - run 5's 27-point strikes weighed
	 * 1,350). Not the swarm's own fleet strength: run 6 sized on that, ~4x the
	 * route figure (a 1,350 strike read ~5,500), and no faction could afford
	 * one garrison. A report gives fleet points, not tiers: each fleet seen
	 * counts as their average, read as swarms of the world's heaviest
	 * size-table row (what musters send first, ThreatStance.heaviestRow) - at
	 * least one, several for a grown fleet. With none seen, a size-9 fleet each.
	 */
	protected static float strikeOf(String observer, MarketAPI hive) {
		int seen = ThreatIntel.worldFleets(observer, hive);
		int send = Math.max(ThreatColonyManager.garrisonTargetCount(hive), seen)
				- ThreatColonyManager.garrisonReserve(hive);
		if (send <= 0) return 0f;
		float each = STRIKE_FLEET_STRENGTH;
		int[] row = seen > 0 ? ThreatStance.heaviestRow(hive) : null;
		float rowFP = row != null ? ThreatColonyManager.swarmCostEstimate(row) : 0f;
		if (rowFP > 0f) {
			int size = IncursionManager.strikeFleetSize(ThreatStance.expeditionSize(row));
			float swarms = Math.max(1f, ThreatIntel.worldFP(observer, hive) / seen / rowFP);
			each = FleetGroupIntel.getApproximateStrengthForTotalDifficultyPoints(hive.getFactionId(), size) * swarms;
		}
		return send * each;
	}

	/**
	 * The strongest strike the Threat could send at a faction's site: every
	 * found hive world big enough to stage strikes whose fuel reaches it (the
	 * range IncursionManager.pickStrikeTarget uses) - or, billed reach
	 * (ThreatReach), every one it stands at the front of: a hive world that
	 * would strike the faction first, the site as near it as the faction's
	 * nearest market (frontOf's rule). Each weighed as the faction last saw it
	 * (strikeOf, its reports).
	 */
	public static float strikeAt(SectorEntityToken site, String factionId) {
		boolean billed = ThreatReach.enabled();
		List<MarketAPI> mine = billed && factionId != null ? ThreatReserves.marketsOf(factionId) : null;
		float worst = 0f;
		for (MarketAPI hive : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI sys = hive.getStarSystem();
			if (sys == null || hive.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (!ThreatScouts.sectorKnows(hive)) continue;
			float d = Misc.getDistanceLY(sys.getLocation(), site.getLocationInHyperspace());
			if (billed) {
				if (factionId == null || !factionId.equals(ThreatReach.facedFaction(sys))) continue;
				float best = d;
				for (MarketAPI m : mine) {
					if (m.getPrimaryEntity() == null) continue;
					best = Math.min(best, Misc.getDistanceLY(sys.getLocation(), m.getLocationInHyperspace()));
				}
				if (d > best + FRONT_TOLERANCE_LY) continue;
			} else if (d > ThreatColonyManager.fuelRangeLY(hive)) {
				continue;
			}
			// the site's faction weighs it: a faction's id is its observer id ("player" for the player)
			worst = Math.max(worst, strikeOf(factionId, hive));
		}
		return worst;
	}

	/**
	 * The raid strength a link's garrison must bring: the strongest strike in
	 * reach x frontlineGarrisonMargin, less what the link's station weighs -
	 * vanilla's autoresolve adds the two - and at least frontlineGarrisonFP.
	 */
	protected static float guardNeed(SectorEntityToken site, MarketAPI link, String factionId) {
		float need = strikeAt(site, factionId) * ThreatIncConfig.frontlineGarrisonMargin();
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
	/** Ms of game time in a day, the clock's timestamp unit. */
	protected static final long DAY_MS = 86400000L;
	protected static Object frontSector;
	protected static long frontDay = -1;
	protected static Map<String, Set<String>> frontCache = new HashMap<String, Set<String>>();

	/**
	 * The links a faction holds at the front: for every found hive world that
	 * stages strikes, the faction's market nearest it, when that is a link and
	 * the hive's fuel reaches it - billed reach (ThreatReach), when the hive
	 * world would strike this faction first - and every link in a system with
	 * a hive world or Threat fleets.
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
		for (MarketAPI m : mine) {
			if (!isOutpost(m) || m.getStarSystem() == null) continue;
			if (threatStrength(m.getStarSystem()) > 0f) front.add(m.getId());
		}
		if (extra != null && extra.getContainingLocation() instanceof StarSystemAPI
				&& threatSeen(factionId, (StarSystemAPI) extra.getContainingLocation()) > 0f) {
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
			if (ThreatReach.enabled() ? !factionId.equals(ThreatReach.facedFaction(sys))
					: best > ThreatColonyManager.fuelRangeLY(hive)) continue;
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

	/**
	 * The Threat in a site's system as the faction raising a link there knows
	 * it (fog of war, 2026-10-01): with its eyes there - a victory base's fleets
	 * - the live strength (threatStrength), else the swarm FP of its report of
	 * the system (ThreatIntel.systemFP; none, 0). With the fog off, live.
	 */
	protected static float threatSeen(String factionId, StarSystemAPI sys) {
		String observer = ThreatIntel.observerOf(Global.getSector().getFaction(factionId));
		if (!ThreatIntel.enabled() || ThreatIntel.eyesIn(sys).contains(observer)) return threatStrength(sys);
		return ThreatIntel.systemFP(observer, sys.getId());
	}

	/**
	 * The Threat's strength in a system as WarSimScript weighs it, less its
	 * Scouting Swarms: a scout visits every uncharted system holding a
	 * strikeable world, and is no presence a link must stand guard against.
	 * Defense Swarms and strikes count.
	 */
	protected static float threatStrength(StarSystemAPI sys) {
		float str = WarSimScript.getFactionStrength(Global.getSector().getFaction(Factions.THREAT), sys);
		for (CampaignFleetAPI f : sys.getFleets()) {
			if (f.isStationMode() || !Factions.THREAT.equals(f.getFaction().getId())) continue;
			if (f.getMemoryWithoutUpdate().getBoolean(ThreatSwarmScouts.SCOUT_FLAG)) str -= f.getEffectiveStrength();
		}
		return Math.max(0f, str);
	}

	/** Whether the link stands at its faction's front (frontOf), worked out once a day per faction. */
	public static boolean isFront(MarketAPI link) {
		long day = Global.getSector().getClock().getTimestamp() / DAY_MS;
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

	/** On load: the front set is the last campaign's, and frontSector would keep that campaign in memory. */
	public static void forgetCaches() {
		frontCache.clear();
		frontDay = -1;
		frontSector = null;
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
			if (boundFor(strike, market)) out.add(strike);
		}
		return out;
	}

	/**
	 * Whether the humans read the strike as bound for the market: for its
	 * system, every world there alike (2026-10-05, the user: "humans shouldn't
	 * know the exact world just the system"). With strikeSeenBySystem off, for
	 * the worlds on its list, as before.
	 */
	public static boolean boundFor(GenericRaidFGI strike, MarketAPI market) {
		if (strike == null || market == null || strike.getParams() == null
				|| strike.getParams().raidParams == null) return false;
		StarSystemAPI where = strike.getParams().raidParams.where;
		if (ThreatIncConfig.strikeSeenBySystem() && where != null) return market.getStarSystem() == where;
		return strike.getParams().raidParams.allowedTargets.contains(market);
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

	/**
	 * The rest of what vanilla's autoresolve puts up for the link's system
	 * (FGRaidAction: WarSimScript.getEnemyStrength of the raider - the fleets
	 * and routes of every faction holding a market there that is hostile to the
	 * Threat): its patrols, and a third party's fleets where that party holds a
	 * market. The faction's link guards in the system are counted by the caller,
	 * so they come out. An ally's task force in a system where it holds no
	 * market is not counted, because vanilla does not count it.
	 */
	protected static float otherDefenders(MarketAPI link) {
		StarSystemAPI sys = link.getStarSystem();
		if (sys == null) return 0f;
		float all = WarSimScript.getEnemyStrength(Global.getSector().getFaction(Factions.THREAT), sys, false);
		float ours = 0f;
		for (Outpost o : all()) {
			if (!link.getFactionId().equals(o.factionId)) continue;
			for (CampaignFleetAPI f : liveGuards(o)) {
				if (f.getContainingLocation() == sys) ours += f.getEffectiveStrength();
			}
		}
		return Math.max(0f, all - ours);
	}

	/** What the link's guard must weigh today: the front's standing need, else what is coming at it. */
	protected static float needNow(MarketAPI link) {
		float need = onCallNeed(link);
		if (isFront(link)) need = Math.max(need, guardNeed(link.getPrimaryEntity(), link, link.getFactionId()));
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
	 * Supplies a month the faction's garrisons cost: the front's standing ones,
	 * {@code front} (frontOf), and the guards strikes called to the rear. A
	 * called guard is never refused for the budget, but while it is out the
	 * faction founds and stands fewer front garrisons (user's call 2026-09-27:
	 * run 17's Hegemony paid ~5,065 a month on a 3,750 budget, and its sieges
	 * starved). A rear guard with no strike on it is on its way home, and is
	 * not counted.
	 */
	protected static float garrisonUpkeep(String factionId, Set<String> front) {
		float sum = 0f;
		for (Outpost o : all()) {
			if (!factionId.equals(o.factionId)) continue;
			if (!front.contains(o.marketId)) {
				MarketAPI m = marketOf(o);
				if (m == null || strikesOn(m).isEmpty()) continue;
			}
			for (CampaignFleetAPI f : liveGuards(o)) sum += maintenancePerMonth(f);
		}
		return sum;
	}

	/** Supplies a month the garrison standing at this forward base costs (0 for any other market). */
	public static float garrisonUpkeepAt(MarketAPI market) {
		Outpost o = find(market);
		if (o == null) return 0f;
		float sum = 0f;
		for (CampaignFleetAPI f : liveGuards(o)) sum += maintenancePerMonth(f);
		return sum;
	}

	/**
	 * What the faction may spend on garrisons a month: its whole supply
	 * banking, plus its stock above the floors spread over
	 * frontlineUpkeepStockMonths (2026-09-29: frontlineUpkeepShare held the
	 * garrisons to half the banking, an arbitrary share; the staging banks
	 * below are what keep the sieges fed). Run 18's Hegemony was refused a garrison at
	 * 4,439 a month on a 3,375 budget while it held 37,000 supplies; a stock
	 * drawn down shrinks the budget back to the banking. The sieges come first:
	 * only the stock beyond what the staging bases are banking for their
	 * sieges (ThreatConvoys.stagingTargets) counts - run 19 counted all of it,
	 * and its sieges' fuel-and-supply postponements went from 36 to 141. Each
	 * market's stock over its own target counts: netted faction-wide, one
	 * staging base's unmet target (~24k) zeroed every other depot's surplus and
	 * pinned Hegemony's budget at its banking for the whole long test
	 * (2026-09-29).
	 */
	protected static float upkeepBudget(String factionId) {
		float income = 0f, spare = 0f;
		int supplies = java.util.Arrays.asList(ThreatReserves.COMMODITIES).indexOf(Commodities.SUPPLIES);
		for (MarketAPI m : ThreatReserves.marketsOf(factionId)) {
			income += ThreatReserves.accrualPer30(m, Commodities.SUPPLIES);
			// the siege's bank and any relay's: not a garrison voyage's want, which is this budget's
			float siege = supplies >= 0 && !m.isPlayerOwned() ? ThreatConvoys.bankTargets(m)[supplies] : 0f;
			spare += Math.max(0f, ThreatReserves.available(m, Commodities.SUPPLIES) - siege);
		}
		float months = ThreatIncConfig.frontlineUpkeepStockMonths();
		return income + (months > 0f ? spare / months : 0f);
	}

	/** Why the last garrisonBase found none, for the planner's log. */
	protected static String noGarrisonWhy = "";

	/**
	 * For a site about to be raised: the faction's base that will answer for
	 * it, or null - and then nothing is raised there. At the front (frontOf)
	 * that is the nearest base that can pay the voyage and upkeep of the
	 * standing garrison the site needs: a front base the faction cannot hold
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
		return garrisonBase(faction, site, guardNeed(site, null, faction.getId()) / STRENGTH_PER_FP, front);
	}

	/**
	 * The nearest base that can pay the voyage of {@code needFP}, or null.
	 * With {@code front} given, only while the standing garrisons' upkeep
	 * stays within the budget; without, a guard called by a strike, which the
	 * budget does not hold back. No navy share (2026-09-29): a garrison is
	 * spawned fresh (spawnForce) and takes nothing from the navy's ships, so
	 * navySpareFP - vanilla's faction strength / responseStrengthDivisor - was
	 * an arbitrary proxy; in the last test it refused every Persean guard
	 * ("can spare 0 of 878 FP") and 22 Persean links fell undefended. The
	 * depots paying for it are the real gate.
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
	 * Whether the faction can pay a garrison's voyage from this base: its stock,
	 * then the markets whose stock reaches it (payFromOthers). Short, the base
	 * notes what it lacks, and convoys stock it toward that (noteVoyageWant).
	 */
	protected static boolean canPayVoyage(MarketAPI base, float fp, float ly) {
		float[] cost = voyageCost(fp, ly);
		boolean pays = pooled(base, Commodities.FUEL) >= cost[0] && pooled(base, Commodities.SUPPLIES) >= cost[1];
		if (!pays) noteVoyageWant(base, cost);
		return pays;
	}

	/**
	 * A commodity at the base and at every market of its faction whose stock
	 * reaches it (IncursionManager.marketsReaching, the rule of a siege's and a
	 * hunt's donors) but the links, as much of each as a hunt may take
	 * (ThreatReserves.spendable: above the floor, the donor keep and the
	 * staging bank). A garrison's home is often a hive's staging base, and what
	 * convoys banked for the siege stays. Until 2026-09-29 every market of the
	 * faction paid, at any range - stock that never sailed.
	 */
	protected static float pooled(MarketAPI base, String commodityId) {
		return ThreatReserves.spendable(base, commodityId) + othersPay(base, commodityId);
	}

	/** What the markets reaching the base but the base and the links can give a voyage ({@link #pooled} without the base). */
	protected static float othersPay(MarketAPI base, String commodityId) {
		float sum = 0f;
		for (MarketAPI m : IncursionManager.marketsReaching(base.getFaction(), base)) {
			if (m != base && !isOutpost(m)) sum += gives(m, base, commodityId);
		}
		return sum;
	}

	/**
	 * What {@code m} gives a draw at {@code to}: what a hunt may take of it
	 * (ThreatReserves.spendable), net of the haul there
	 * (ThreatConvoys.netOfHaul, 2026-10-01 - with no radius left, stock
	 * pooled from afar pays its passage). The haul is read from the fuel it
	 * is paid from, above the floor (payHaul): read from the spendable fuel,
	 * a donor whose fuel sat in a staging bank gave no supplies at all, and
	 * fleets out of supplies went 17 -> 47 a test (h45a).
	 */
	protected static float gives(MarketAPI m, MarketAPI to, String commodityId) {
		return ThreatConvoys.netOfHaul(commodityId, ThreatReserves.spendable(m, commodityId),
				ThreatReserves.available(m, Commodities.FUEL), ThreatConvoys.haulRate(m, to, commodityId));
	}

	/** Draws up to {@code want} of what {@code m} gives a draw at {@code to} ({@link #gives}), the haul paid first; returns what was drawn. */
	protected static float drawGiven(MarketAPI m, MarketAPI to, String commodityId, float want) {
		float can = Math.min(want, gives(m, to, commodityId));
		if (can <= 0f) return 0f;
		ThreatConvoys.payHaul(m, can, ThreatConvoys.haulRate(m, to, commodityId));
		return ThreatReserves.drawSpendable(m, commodityId, can);
	}

	/** Base memory: the fuel and supplies a garrison's voyage from it lacked past what the markets reaching it give (garrisonWants). */
	protected static final String VOYAGE_FUEL_KEY = "$threatinc_voyageFuelWant";
	protected static final String VOYAGE_SUPPLIES_KEY = "$threatinc_voyageSuppliesWant";
	/** Days a voyage want stands unless a garrison sails first. */
	protected static final float VOYAGE_WANT_DAYS = 30f;

	/**
	 * A garrison's voyage from the base could not be paid: notes, for
	 * VOYAGE_WANT_DAYS, what the base itself must hold for it - the cost past
	 * what the markets reaching it give (othersPay), the most asked. Convoys
	 * stock the base toward it like a staging base (ThreatConvoys.stagingTargets),
	 * and the relays carry it on past its donors' reach. A garrison that sails
	 * clears it (clearVoyageWant). NPC bases only: the player's convoys are the
	 * player's.
	 */
	protected static void noteVoyageWant(MarketAPI base, float[] cost) {
		if (base == null || base.isPlayerOwned()) return;
		MemoryAPI mem = base.getMemoryWithoutUpdate();
		String[][] keys = { { VOYAGE_FUEL_KEY, Commodities.FUEL }, { VOYAGE_SUPPLIES_KEY, Commodities.SUPPLIES } };
		for (int i = 0; i < keys.length; i++) {
			float want = cost[i] - othersPay(base, keys[i][1]);
			if (want <= 0f) continue;
			float had = mem.contains(keys[i][0]) ? mem.getFloat(keys[i][0]) : 0f;
			if (want > had) mem.set(keys[i][0], want, VOYAGE_WANT_DAYS);
		}
	}

	protected static void clearVoyageWant(MarketAPI base) {
		if (base == null) return;
		base.getMemoryWithoutUpdate().unset(VOYAGE_FUEL_KEY);
		base.getMemoryWithoutUpdate().unset(VOYAGE_SUPPLIES_KEY);
	}

	/** {fuel, supplies} a garrison's voyage from the base waits on it holding, above its keep ({@link #noteVoyageWant}); zeros with none. */
	public static float[] garrisonWants(MarketAPI base) {
		if (base == null || base.isPlayerOwned()) return new float[] {0f, 0f};
		MemoryAPI mem = base.getMemoryWithoutUpdate();
		return new float[] {mem.contains(VOYAGE_FUEL_KEY) ? mem.getFloat(VOYAGE_FUEL_KEY) : 0f,
				mem.contains(VOYAGE_SUPPLIES_KEY) ? mem.getFloat(VOYAGE_SUPPLIES_KEY) : 0f};
	}

	/**
	 * The most fleet points whose voyage of {@code ly} the base and the
	 * markets reaching it can pay (pooled, voyageCost): the budget a
	 * garrison is built to, a hair under so float rounding never tips the
	 * paid-in-full check. Unbounded when the voyage costs nothing.
	 */
	protected static float payableFP(MarketAPI base, float ly) {
		float[] per = voyageCost(1f, ly);
		float fp = Float.MAX_VALUE;
		if (per[0] > 0f) fp = Math.min(fp, pooled(base, Commodities.FUEL) / per[0]);
		if (per[1] > 0f) fp = Math.min(fp, pooled(base, Commodities.SUPPLIES) / per[1]);
		fp = fp == Float.MAX_VALUE ? fp : Math.max(0f, fp * 0.999f);
		return ThreatHulls.cap(base, fp); // and the faction's free hulls (ThreatHulls, 2026-10-06)
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
	 * for the distance, supplies for the fleets) - in full. Sized on STRENGTH_PER_FP,
	 * then weighed for real and topped up until it holds (2026-09-29: two
	 * passes at most left a garrison short whatever the depots could pay),
	 * within the points the depots can pay the voyage of ({@link #payableFP}):
	 * spawnForce builds nothing past that budget. Until 2026-09-29 the whole
	 * force was spawned, then weighed against the depots and all of it
	 * despawned if they fell short - and callGuard asked again every week.
	 * Joins any garrison already there.
	 */
	protected static boolean sendGarrison(Outpost o, MarketAPI market, MarketAPI base, float str) {
		FactionAPI faction = market.getFaction();
		float ly = ly(base, market);
		float payable = payableFP(base, ly);
		List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		float got = 0f;
		// ends: each pass adds strength or breaks, and every fleet spends at
		// least a point of a finite budget (spawnForce), which below 30 builds nothing
		while (got < str * 0.95f) {
			float rate = got > 0f ? got / Math.max(1f, fpOf(fleets)) : STRENGTH_PER_FP;
			float budget = Math.min((str - got) / rate, payable - fpOf(fleets));
			List<CampaignFleetAPI> more = spawnForce(faction, base, market, budget, "Garrison",
					100000f, "garrisoning " + market.getName());
			if (more.isEmpty()) break; // the rest is under the smallest fleet, past what is paid, or nothing could be built
			float before = got;
			fleets.addAll(more);
			got = strengthOf(fleets);
			if (got <= before) break; // no progress: the yards build nothing that weighs
		}
		if (fleets.isEmpty()) {
			if (payable < 30f) {
				ThreatIncConfig.logQuiet("fl_unpaid_" + market.getId(), "Frontline: " + base.getName()
						+ " cannot pay a voyage to " + market.getName() + " (" + (int) payable + " FP payable)");
				noteVoyageWant(base, voyageCost(str / STRENGTH_PER_FP, ly));
			}
			return false;
		}
		float fp = fpOf(fleets);
		float[] cost = voyageCost(fp, ly);
		// paid in full: the base first, then the markets reaching it
		// (pooled), the same stock the draw takes. The force was built within
		// what they pay, so this is a guard that should never trip
		if (pooled(base, Commodities.FUEL) < cost[0] || pooled(base, Commodities.SUPPLIES) < cost[1]) {
			for (CampaignFleetAPI f : fleets) f.despawn();
			ThreatIncConfig.log("Frontline: " + base.getName() + " cannot pay the voyage of " + (int) fp + " FP to "
					+ market.getName() + " (" + (int) cost[0] + " fuel, " + (int) cost[1] + " supplies)");
			return false;
		}
		float fuel = ThreatReserves.drawSpendable(base, Commodities.FUEL, cost[0]);
		if (fuel < cost[0]) fuel += payFromOthers(base, null, Commodities.FUEL, cost[0] - fuel);
		float supplies = ThreatReserves.drawSpendable(base, Commodities.SUPPLIES, cost[1]);
		if (supplies < cost[1]) supplies += payFromOthers(base, null, Commodities.SUPPLIES, cost[1] - supplies);
		// the voyage it waited on has sailed
		clearVoyageWant(base);
		for (CampaignFleetAPI f : fleets) {
			ThreatReturns.provision(f, base.getId(), fuel / fleets.size(), supplies / fleets.size());
		}
		long now = Global.getSector().getClock().getTimestamp();
		boolean topUp = !liveGuards(o).isEmpty();
		if (!topUp) {
			o.guards = new ArrayList<CampaignFleetAPI>();
			o.guardPaid = now;
		}
		for (CampaignFleetAPI f : fleets) f.getMemoryWithoutUpdate().set(GUARD_JOINED_KEY, now);
		o.guards.addAll(fleets);
		o.guardBaseId = base.getId();
		o.unguardedDays = 0f;
		ThreatIncConfig.log("Frontline: " + base.getName() + (topUp ? " reinforces " : " garrisons ")
				+ market.getName() + " with " + fleets.size() + " fleet(s), " + (int) fp + " FP, strength "
				+ (int) got + " of " + (int) str + (isFront(market)
						? " (front; strike in reach " + (int) strikeAt(market.getPrimaryEntity(), market.getFactionId()) + ")"
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

	/**
	 * Sends the garrison home (the link is gone, or it went unpaid) on the
	 * tracked leg (2026-09-29: closed economy - it despawned on arrival with
	 * nothing back): the base re-banks its hulls at what survived, the rule
	 * every sortie comes home by (ThreatReturns.settle). A home that fell
	 * sends it to the faction's nearest base instead.
	 */
	protected static void recallGarrison(Outpost o, String why) {
		List<CampaignFleetAPI> live = liveGuards(o);
		for (CampaignFleetAPI f : live) sendGuardHome(o, f);
		if (!live.isEmpty()) {
			ThreatIncConfig.log("Frontline: garrison of " + o.marketId + " recalled (" + why + ")");
		}
		o.guards = null;
		o.guardBaseId = null;
	}

	/** One guard home on the tracked leg, to its base or the faction's nearest; despawned only with no base left. */
	protected static void sendGuardHome(Outpost o, CampaignFleetAPI f) {
		MarketAPI to = homeOf(o);
		FactionAPI faction = Global.getSector().getFaction(o.factionId);
		if ((to == null || to.getPrimaryEntity() == null) && faction != null) to = nearestBase(faction, f);
		if (to == null || to.getPrimaryEntity() == null || !ThreatReturns.sendHome(f, o.factionId, to.getId())) {
			f.clearAssignments();
			f.despawn(); // no base left: nowhere to go home to
		}
	}

	/**
	 * A guard whose order ran out with nothing after it (ThreatReturns.orderRanOut:
	 * its station gone from under it, or the term run) leaves the guard and goes
	 * home to settle. Nothing despawning is queued behind a guard's order
	 * (2026-09-29: closed economy - that return ended it unsettled); a guard
	 * from an older save still carrying one is caught as it comes up.
	 */
	protected static void sendHomeRanOut(Outpost o) {
		if (o.guards == null) return;
		int sent = 0;
		for (CampaignFleetAPI f : new ArrayList<CampaignFleetAPI>(o.guards)) {
			if (!ThreatReturns.orderRanOut(f)) continue;
			o.guards.remove(f);
			sendGuardHome(o, f);
			sent++;
		}
		if (sent > 0) {
			ThreatIncConfig.log("Frontline: " + sent + " guard(s) of " + o.marketId + " ran out of orders - home");
		}
		if (o.guards.isEmpty()) {
			o.guards = null;
			o.guardBaseId = null;
		}
	}

	/**
	 * The daily garrison step. False when the link was dismantled.
	 * <ul>
	 * <li>Garrisons off: any garrison goes home.</li>
	 * <li>A garrison on station: its upkeep, its ships' maintenance
	 * (maintenancePerMonth), is paid monthly (payUpkeep), each fleet from the
	 * day it joined. Unpaid by half, it goes home, turned back if a strike is
	 * seen while it sails; paid, a front garrison the strikes in reach outgrew
	 * is reinforced (topUp).</li>
	 * <li>No garrison (lost in battle, recalled, or a link raised without one):
	 * one is sent if a base can pay for it, at most every 30 days; a link
	 * unguarded for frontlineAbandonDays is given up.</li>
	 * <li>The rear (isFront false, user's call 2026-09-27): no standing
	 * garrison. A guard sails when a strike is seen coming (sendRelief, then
	 * this step weekly while it comes), outside the upkeep budget, and goes home
	 * once no seen strike is bound for the link. An unguarded rear link is never
	 * given up for it.</li>
	 * </ul>
	 */
	protected static boolean garrison(Outpost o, MarketAPI market, float days) {
		// a star fortress alone does not hold (run 5 lost all seven the month
		// their garrisons went home): every link needs one while garrisons are on
		if (!ThreatIncConfig.frontlineGarrisonEnabled()) {
			if (o.guards != null) recallGarrison(o, "garrisons are off");
			o.homebound = null;
			o.unguardedDays = 0f;
			return true;
		}
		boolean front = isFront(market);
		o.rearDays = front ? 0f : o.rearDays + days;
		if (o.homebound != null) {
			boolean sailing = false;
			for (CampaignFleetAPI f : o.homebound) sailing |= sailingHome(f);
			if (!sailing) o.homebound = null; // home: the save keeps no dead fleets
		}
		sendHomeRanOut(o);
		List<CampaignFleetAPI> live = liveGuards(o);
		long now = Global.getSector().getClock().getTimestamp();
		if (!live.isEmpty()) {
			if (o.guards != null && live.size() < o.guards.size()) {
				ThreatIncConfig.log("Frontline: the garrison of " + market.getName() + " lost "
						+ (o.guards.size() - live.size()) + " fleet(s); " + live.size() + " left, strength "
						+ (int) strengthOf(live));
			}
			o.guards = live; // the dead and despawned are not kept in the save
			o.unguardedDays = 0f;
			// behind the front for a while (a hive's fuel range drifts) and nothing
			// seen coming - run 17: 4 of 7 went home with a strike launched but not
			// yet seen (seen a median 16 days after launch, up to 59)
			if (!front && o.rearDays >= ThreatIncConfig.frontlineRearGraceDays() && strikesOn(market).isEmpty()) {
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
				// each fleet from the day it joined: a guard called on payday is
				// not billed the month before it
				float want = 0f;
				for (CampaignFleetAPI f : live) {
					want += maintenancePerMonth(f) * Math.min(since, daysOnStation(f, o)) / 30f;
				}
				o.guardPaid = now;
				float paid = payUpkeep(o, market, want);
				if (paid < want * 0.5f) {
					recallGarrison(o, "unpaid: " + (int) paid + " of " + (int) want + " supplies");
					o.homebound = live; // a strike seen while it sails turns it back (callGuard)
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
		}
		// a seen strike on it calls a guard, front or rear - also after a front
		// garrison went home unpaid (run 15: Eps Golgotha I's seen strike landed
		// 17 days after its recall, with no guard called)
		if (callGuard(o, market, 0f, false)) return true;
		if (!front) {
			o.unguardedDays = 0f; // the rear stands unguarded until a strike is seen coming
			return true;
		}
		// the call may have turned back or borrowed a guard and still said no (its
		// week's wait, or the rest unpaid): that guard counts toward
		// the standing need, and only the rest is sent
		float need = guardNeed(market.getPrimaryEntity(), market, market.getFactionId()) - strengthOf(liveGuards(o));
		if (need < 30f * STRENGTH_PER_FP) {
			o.unguardedDays = 0f; // held, or nothing in reach it must be guarded against
			return true;
		}
		o.unguardedDays += days;
		if (o.unguardedDays >= ThreatIncConfig.frontlineAbandonDays()) {
			dismantle(o, market, "no garrison to hold it");
			return false;
		}
		if (Global.getSector().getClock().getElapsedDaysSince(o.guardSent) >= 30f) {
			o.guardSent = now; // one try a month, sent or not
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
	 * The hives grow: a front garrison that no longer outweighs the strongest
	 * strike in reach (guardNeed) by the margin - under 80% of it - is
	 * reinforced from its home base, if the faction can pay its voyage and
	 * its upkeep within the budget. The seen strikes are callGuard's,
	 * daily, with its netting, timing and throttle: sized against their gross
	 * figure here, a rear guard netted at the call was topped up on payday,
	 * after the strike.
	 */
	protected static void topUp(Outpost o, MarketAPI market, List<CampaignFleetAPI> live) {
		if (!isFront(market)) return;
		MarketAPI home = homeOf(o);
		if (home == null || home.getPrimaryEntity() == null || !IncursionManager.isBase(home)) return;
		float need = guardNeed(market.getPrimaryEntity(), market, market.getFactionId());
		float have = strengthOf(live);
		if (have >= need * 0.8f) return;
		float shortFP = (need - have) / STRENGTH_PER_FP;
		FactionAPI faction = market.getFaction();
		float budget = upkeepBudget(faction.getId());
		float upkeep = garrisonUpkeep(faction.getId(), frontOf(faction.getId(), null)) + shortFP * UPKEEP_PER_FP;
		String why = null;
		if (upkeep > budget) why = "upkeep would be " + (int) upkeep + " of " + (int) budget + " supplies a month";
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

	/** Days the fleet has stood on the guard, from the stamp it got as it joined (sendGarrison, takeOver); unstamped, from the last payday. */
	protected static float daysOnStation(CampaignFleetAPI fleet, Outpost o) {
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		long joined = mem.contains(GUARD_JOINED_KEY) ? mem.getLong(GUARD_JOINED_KEY) : o.guardPaid;
		return Math.max(0f, Global.getSector().getClock().getElapsedDaysSince(joined));
	}

	/**
	 * Pays a garrison's upkeep: the link's reserve first, then its home base
	 * if the home's stock reaches the link, then the faction's other markets whose stock reaches the link, nearest
	 * first (payFromOthers) - the faction pays for
	 * the fleet, not one depot, out of the same markets upkeepBudget counts the
	 * banking of. Other links keep theirs for their own garrisons. The link's
	 * own stock pays down to its floor plus its staging bank; the home base and
	 * the others give what a hunt may take (ThreatReserves.spendable) - never
	 * what convoys banked for a siege. Drawn to the floor alone, the long test's
	 * staging links fed ~1,600 a month of garrison out of a ~441 banking and
	 * Alpha Mesh's siege sat at 233 of 8,040 supplies (2026-09-29). Returns what
	 * was paid.
	 */
	protected static float payUpkeep(Outpost o, MarketAPI market, float want) {
		float free = ThreatReserves.available(market, Commodities.SUPPLIES)
				- ThreatReserves.stagingBank(market, Commodities.SUPPLIES);
		float link = ThreatReserves.drawAbove(market, Commodities.SUPPLIES, Math.min(want, Math.max(0f, free)));
		MarketAPI home = homeOf(o);
		// the home base pays only when its stock reaches the link, like any other market
		boolean homeReaches = home != null && Misc.getDistanceLY(home.getLocationInHyperspace(),
				market.getLocationInHyperspace()) <= ThreatConvoys.stockReachLY(home);
		float fromHome = link < want && homeReaches ? drawGiven(home, market, Commodities.SUPPLIES, want - link) : 0f;
		float paid = link + fromHome;
		if (paid < want) paid += payFromOthers(market, home, Commodities.SUPPLIES, want - paid);
		ThreatIncConfig.log("Frontline upkeep of " + market.getName() + "'s garrison: paid " + (int) paid + " of "
				+ (int) want + " supplies (link " + (int) link + ", home " + (int) fromHome
				+ ", other markets " + (int) (paid - link - fromHome) + ")");
		return paid;
	}

	/**
	 * Draws from the faction's other markets whose stock reaches {@code market}
	 * (IncursionManager.marketsReaching - 2026-09-29, it was every market at any
	 * range), nearest it first, links and {@code home} left out, what a hunt may
	 * take of each (ThreatReserves.spendable).
	 */
	protected static float payFromOthers(MarketAPI market, MarketAPI home, String commodityId, float want) {
		float paid = 0f;
		List<MarketAPI> others = new ArrayList<MarketAPI>();
		for (MarketAPI m : IncursionManager.marketsReaching(market.getFaction(), market)) {
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
			paid += drawGiven(m, market, commodityId, want - paid);
		}
		return paid;
	}

	/** What {@link #payFromOthers} could draw for {@code market} now. */
	protected static float othersSpendable(MarketAPI market, MarketAPI home, String commodityId) {
		float sum = 0f;
		for (MarketAPI m : IncursionManager.marketsReaching(market.getFaction(), market)) {
			if (m != market && m != home && !isOutpost(m)) sum += gives(m, market, commodityId);
		}
		return sum;
	}

	/**
	 * Whether a base can pay a link's founding {@code cost} ({supplies, fuel}):
	 * its stock above the floor, the rest from what the faction's markets that
	 * reach it can spare (payFromOthers), as a hive's founding draws the whole
	 * hive's stock. From one base alone (2026-10-01, the kit added), 29 tries
	 * found no payer while the Persean League held ~22k supplies across its
	 * markets (review).
	 */
	public static boolean canFund(MarketAPI base, float[] cost) {
		String[] ids = { Commodities.SUPPLIES, Commodities.FUEL };
		for (int i = 0; i < 2; i++) {
			float have = ThreatReserves.available(base, ids[i]);
			if (have < cost[i] && have + othersSpendable(base, base, ids[i]) < cost[i]) return false;
		}
		return true;
	}

	/** Draws a founding {@link #canFund} passed: the base's stock above its floor, then the others'. Returns {supplies, fuel} drawn. */
	public static float[] drawFounding(MarketAPI base, float[] cost) {
		String[] ids = { Commodities.SUPPLIES, Commodities.FUEL };
		float[] paid = new float[2];
		for (int i = 0; i < 2; i++) {
			paid[i] = ThreatReserves.drawAbove(base, ids[i], cost[i]);
			if (paid[i] < cost[i]) paid[i] += payFromOthers(base, base, ids[i], cost[i] - paid[i]);
		}
		return paid;
	}

	/**
	 * Real fleets from a base to hold a market's orbit: task forces summing
	 * {@code fp}, on DEFEND_LOCATION for {@code days}, then home to settle
	 * ({@link #sendHomeRanOut}). Each fleet
	 * is up to softenFleetFP, the mod's per-fleet size, or the largest the
	 * faction's yards were seen to build whole under vanilla's
	 * maxShipsInAIFleet (ThreatSoftening.fleetCap). No fleet count cap
	 * (2026-09-29): 16 fleets of responseMaxDifficulty x 25 (250 FP) held a
	 * garrison to 4,000 FP whatever the depots could pay. {@code fp} is a
	 * budget the built points never pass - the caller's payable points
	 * (sendGarrison, payableFP): a fleet built over what is left is dropped
	 * before it is placed, never spawned and despawned. The loop ends as the
	 * budget is spent: each fleet takes its built points off it.
	 */
	protected static List<CampaignFleetAPI> spawnForce(FactionAPI faction, MarketAPI base, MarketAPI target,
			float fp, String name, float days, String what) {
		List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		float budget = fp;
		while (budget >= 30f) {
			float perFleet = Math.max(50f, Math.min(ThreatIncConfig.softenFleetFP(),
					ThreatSoftening.fleetCap(faction.getId())));
			// the warships plus a tenth each of freighters and tankers on top
			// (the params below): the last fleet is asked what fits what is left
			float size = Math.min(budget / (1f + 2f * SUPPORT_SHARE), perFleet);
			CampaignFleetAPI fleet = buildForce(faction, base, size);
			if (fleet == null) break;
			// pruned to vanilla's ship cap: ask smaller from now, and count what was built
			ThreatSoftening.learnFleetCap(faction.getId(), fleet, size);
			if (fleet.getFleetPoints() > budget) {
				// vanilla's picks ran over what is paid for: once more at the size
				// that fits, else the budget's rest stays unspent
				fleet = buildForce(faction, base, size * budget / fleet.getFleetPoints() * 0.95f);
				if (fleet == null || fleet.getFleetPoints() > budget) break;
			}
			base.getStarSystem().addEntity(fleet);
			SectorEntityToken home = base.getPrimaryEntity();
			fleet.setLocation(home.getLocation().x, home.getLocation().y);
			fleet.setName(name);
			fleet.setNoFactionInName(false);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
			// nothing queued behind the order: when it runs out the garrison step
			// sends the fleet home through ThreatReturns, to settle (sendHomeRanOut)
			fleet.addAssignment(FleetAssignment.DEFEND_LOCATION, target.getPrimaryEntity(), days, what);
			budget -= fleet.getFleetPoints(); // at least 1: the loop always shrinks the budget
			fleets.add(fleet);
		}
		return fleets;
	}

	/** Freighter and tanker points a garrison task force is built with, each as a share of its warship points. */
	protected static final float SUPPORT_SHARE = 0.1f;

	/** One garrison task force of {@code size} warship points, built and not placed; null if nothing came out. */
	protected static CampaignFleetAPI buildForce(FactionAPI faction, MarketAPI base, float size) {
		FleetParamsV3 params = new FleetParamsV3(base, base.getLocationInHyperspace(),
				faction.getId(), null, FleetTypes.TASK_FORCE, size, size * SUPPORT_SHARE, size * SUPPORT_SHARE,
				0f, 0f, 0f, 0f);
		// the size asked for, not the base's fleet-size multiplier on top: the
		// long test's 400-point garrisons sailed at 756 on average
		params.ignoreMarketFleetSizeMult = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty() || fleet.getFleetPoints() < 1) return null;
		ThreatFleetComposer.beltSafe(fleet);
		return fleet;
	}

	/**
	 * A shortage of anything the link lives on, as vanilla's industries
	 * measure it (BaseIndustry.getMaxDeficit: demand over availability) -
	 * not the trade stockpile, which the player's buying and selling moves.
	 * The demand is the peacetime one: the War Footing on every link declares
	 * one unit over the other industries' (WarFootingDemand), so vanilla's max
	 * demand reads a fully supplied link short every day.
	 */
	protected static boolean warShortage(MarketAPI market) {
		for (String c : WAR_NEEDS) {
			CommodityOnMarketAPI com = market.getCommodityData(c);
			if (com == null) continue;
			int demand = WarFootingDemand.peacetimeDemand(market, com);
			if (demand > 0 && demand > com.getAvailable()) return true;
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
		// the market stays on the fading entity, as vanilla's pirate base keeps
		// it: merc routes pick links as destinations and read the entity's
		// market name when their fleet spawns
		if (entity != null) Misc.fadeAndExpire(entity);
		ThreatIncConfig.log("Frontline: " + o.factionId + " dismantled " + market.getName() + " (" + why + ")");
	}

	/**
	 * Save repair: links dismantled before the market stayed on the entity left
	 * merc routes pointing at an entity with no market, and vanilla's merc AI
	 * fatals reading its name. Those legs are sent home instead.
	 */
	public static void repairMercRoutes() {
		for (RouteData route : RouteManager.getInstance().getRoutesForSource("mercs_global")) {
			MarketAPI home = route.getMarket();
			SectorEntityToken homeEntity = home != null ? home.getPrimaryEntity() : null;
			if (homeEntity == null) continue;
			for (RouteSegment seg : route.getSegments()) {
				if (seg.from != null && seg.from.getMarket() == null && !(seg.from instanceof PlanetAPI)) {
					ThreatIncConfig.log("Frontline: merc route leg from " + seg.from.getName() + " sent home");
					seg.from = homeEntity;
				}
				if (seg.to != null && seg.to.getMarket() == null && !(seg.to instanceof PlanetAPI)) {
					ThreatIncConfig.log("Frontline: merc route leg to " + seg.to.getName() + " sent home");
					seg.to = homeEntity;
				}
			}
		}
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
	 * Whether vanilla's inputs for the producer can be had here: Fuel
	 * Production volatiles at the market's size and heavy machinery at size -
	 * 2; Heavy Industry metals at size and rare metals at size - 2.
	 */
	protected static boolean canSupplyProducer(MarketAPI market, int s, String producer) {
		if (Industries.FUELPROD.equals(producer)) {
			return canSupply(market, Commodities.VOLATILES, s) && canSupply(market, Commodities.HEAVY_MACHINERY, s - 2);
		}
		return canSupply(market, Commodities.METALS, s) && canSupply(market, Commodities.RARE_METALS, s - 2);
	}

	/** Whether the knob for the producer is on and the link has none of it (an Orbital Works counts as a Heavy Industry). */
	protected static boolean wantsProducer(MarketAPI market, String producer) {
		if (Industries.FUELPROD.equals(producer)) {
			return ThreatIncConfig.frontlineFuelProduction() && !market.hasIndustry(Industries.FUELPROD);
		}
		return ThreatIncConfig.frontlineHeavyIndustry() && !market.hasIndustry(Industries.HEAVYINDUSTRY)
				&& !market.hasIndustry(Industries.ORBITALWORKS);
	}

	/**
	 * Starts the producer in a free industry slot if its inputs can be had
	 * (canSupplyProducer) and its supplies are paid (startNew). Fuel
	 * Production (frontlineFuelProduction, 2026-09-30) makes size - 2 fuel: no
	 * forward base made fuel, so a faction's fuel was capped by the sector's
	 * best exporter while its sieges wanted 40-115k each. Heavy Industry
	 * (frontlineHeavyIndustry, user's call 2026-09-27) makes supplies, heavy
	 * armaments and ships - the yards that rebuild the hull pool. True when it
	 * started.
	 */
	protected static boolean buildProducer(MarketAPI market, int s, String producer) {
		if (!wantsProducer(market, producer) || Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) {
			return false;
		}
		if (!canSupplyProducer(market, s, producer)) return false;
		return startNew(market, producer);
	}

	/**
	 * With no slot free, the link tears a producer of a stock its faction has
	 * in surplus down for the producer of the one it runs dry of
	 * (ThreatFactionStock, the hive's convertSurplus for a faction): a fuel
	 * plant for a Heavy Industry while supplies or hulls are short and fuel
	 * is spare, a Heavy Industry for a fuel plant the other way; failing
	 * that, with hulls to spare, its Patrol HQ (the hive's retireMilitary).
	 * The new structure is paid first (a refusal tears nothing down) and
	 * takes its vanilla build time - never instant. One a faction a month.
	 * Never a producer still building, nor one carrying an item or a core.
	 * True when it converted.
	 */
	protected static boolean convertFor(MarketAPI market, int s, String want, String producer) {
		String fid = market.getFactionId();
		if (!ThreatFactionStock.mayConvert(fid) || !wantsProducer(market, producer)) return false;
		if (!canSupplyProducer(market, s, producer) || !canPayBuild(market, producer)) return false;
		String spareStock = Industries.FUELPROD.equals(producer) ? Commodities.SUPPLIES : Commodities.FUEL;
		Industry spare = ThreatFactionStock.producerOn(market, spareStock);
		String retired = null;
		if (spare != null && !spare.isBuilding() && !spare.isUpgrading() && spare.getSpecialItem() == null
				&& spare.getAICoreId() == null && ThreatFactionStock.surplusProducer(market, spareStock)) {
			retired = spare.getId();
		} else if (market.hasIndustry(Industries.PATROLHQ) && ThreatFactionStock.hullsSurplus(fid) && ThreatHulls.debt(fid) <= 0f) {
			Industry hq = market.getIndustry(Industries.PATROLHQ);
			if (hq != null && !hq.isBuilding() && !hq.isUpgrading()) retired = hq.getId();
		}
		if (retired == null) return false;
		market.removeIndustry(retired, null, false);
		if (!startNew(market, producer)) return false;
		ThreatFactionStock.converted(fid);
		ThreatFactionStock.answered(fid, want);
		ThreatIncConfig.log("Frontline: " + market.getName() + " turns its " + retired + " into a " + producer
				+ " (" + want + " short) - " + fid + ": " + ThreatFactionStock.describe(fid));
		return true;
	}

	/**
	 * One project at a time, in order: Patrol HQ, battlestation, the producer
	 * of what the faction is shortest of (ThreatFactionStock.shortest: hulls,
	 * then the drier of fuel and supplies) in a free slot or by conversion
	 * (convertFor), then Heavy Industry and Fuel Production in a free slot,
	 * Military Base and star fortress (4) - each only if every commodity it
	 * demands can be had here (canSupply). Demands are vanilla's
	 * (industries.csv / the industry classes), s being the market size. The
	 * hive's planner for a faction (user's decision 2026-10-06): hw31's links
	 * built fuel first on 350k-1.1M fuel while every expedition waited on hulls.
	 *
	 * <p>The shortage's answer first (user, 2026-10-06): the pass reads the
	 * whole stock (the hold released), and if the shortage's producer is wanted
	 * here and cannot be paid, the market holds the price still wanted above
	 * its floor until the next pass (ThreatFactionStock.hold) - upkeep and
	 * hunts no longer draw what the yard is saving for, and an ally's convoy
	 * lands where it is held (ThreatCoalition.aidStockPlans).
	 */
	protected static void build(MarketAPI market) {
		ThreatFactionStock.release(market);
		buildStep(market);
		int s = market.getSize();
		String fid = market.getFactionId();
		String want = s >= 3 ? ThreatFactionStock.shortest(fid) : null;
		if (want == null) return;
		String producer = ThreatFactionStock.producerId(want);
		if (!wantsProducer(market, producer) || !canSupplyProducer(market, s, producer)) return;
		if (Misc.getNumIndustries(market) >= Misc.getMaxIndustries(market)) return;
		float cost = ThreatBuildCost.supplies(producer);
		float funds = buildFunds(market);
		if (cost <= 0f || funds >= cost) return;
		ThreatFactionStock.hold(market, producer, cost - funds);
		ThreatIncConfig.logQuiet("fl_hold_" + market.getId(), "Frontline: " + market.getName() + " holds "
				+ (int) (cost - funds) + " supplies for " + producer + " (" + want + " short)");
	}

	/**
	 * YARDS AT HOME (the user, 2026-10-08, "trial your recommended human fix"): a faction short of
	 * hulls (ThreatFactionStock.shortest == hulls) builds the shortage's answer at its largest core
	 * world with a free slot and no yard, not only at a size-3+ link (buildStep). The yards the swarm
	 * razes or takes in the core were never replaced (hw63-hw72: yards at 0-500 FP a month at every
	 * end, hulls unrebuilt in the thousands), and the one faction with its yards running was the one
	 * that held (hw70a). A link's rules: the producer's inputs importable (canSupplyProducer), paid
	 * from the world's reserves and what reaches it (payBuild) or held for (ThreatFactionStock.hold),
	 * one answer a faction a SHORT_DAYS (mayAnswer), never while a project runs there. Daily from
	 * poll; knob homeYards.
	 */
	protected static void homeYards() {
		if (!ThreatIncConfig.homeYards() || !ThreatHulls.enabled()) return;
		for (String fid : ThreatWarState.warFactionIds()) {
			if (!ThreatFactionStock.mayAnswer(fid, ThreatFactionStock.HULLS)) continue;
			if (!ThreatFactionStock.HULLS.equals(ThreatFactionStock.shortest(fid))) continue;
			MarketAPI best = null;
			for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
				if (m == null || isOutpost(m) || m.isPlayerOwned() || m.getSize() < 3) continue;
				if (!wantsProducer(m, Industries.HEAVYINDUSTRY)) continue;
				if (Misc.getNumIndustries(m) >= Misc.getMaxIndustries(m)) continue;
				if (projectRunning(m) || !canSupplyProducer(m, m.getSize(), Industries.HEAVYINDUSTRY)) continue;
				if (best == null || m.getSize() > best.getSize()
						|| (m.getSize() == best.getSize() && buildFunds(m) > buildFunds(best))) best = m;
			}
			if (best == null) continue;
			ThreatFactionStock.release(best);
			if (startNew(best, Industries.HEAVYINDUSTRY)) {
				ThreatFactionStock.answered(fid, ThreatFactionStock.HULLS);
				ThreatIncConfig.log("Home yard: " + best.getName() + " builds a Heavy Industry for " + fid
						+ " (hulls short) - " + ThreatFactionStock.describe(fid));
			} else {
				float cost = ThreatBuildCost.supplies(Industries.HEAVYINDUSTRY), funds = buildFunds(best);
				if (cost > funds) {
					ThreatFactionStock.hold(best, Industries.HEAVYINDUSTRY, cost - funds);
					ThreatIncConfig.logQuiet("hy_hold_" + best.getId(), "Home yard: " + best.getName() + " holds "
							+ (int) (cost - funds) + " supplies for a Heavy Industry (" + fid + " hulls short)");
				}
			}
		}
	}

	/** Whether a structure is building or upgrading at the market (Population's growth bar is not a project). */
	protected static boolean projectRunning(MarketAPI market) {
		for (Industry ind : market.getIndustries()) {
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (ind.isBuilding() || ind.isUpgrading()) return true;
		}
		return false;
	}

	protected static void buildStep(MarketAPI market) {
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
		String fid = market.getFactionId();
		ThreatFactionStock.watch(fid);
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
		// the shortage's answer: in a free slot, else by conversion
		String want = s >= 3 ? ThreatFactionStock.shortest(fid) : null;
		if (want != null && ThreatFactionStock.mayAnswer(fid, want)) {
			String producer = ThreatFactionStock.producerId(want);
			if (wantsProducer(market, producer)) {
				if (Misc.getNumIndustries(market) < Misc.getMaxIndustries(market)) {
					if (buildProducer(market, s, producer)) {
						ThreatFactionStock.answered(fid, want);
						ThreatIncConfig.log("Frontline: " + market.getName() + " answers " + want + " - " + fid + ": "
								+ ThreatFactionStock.describe(fid));
						return;
					}
				} else if (convertFor(market, s, want, producer)) {
					return;
				}
			}
		}
		// a war industry in a free slot (user's call 2026-09-27): run 17's
		// links took in 242k supplies and sent 8.5k back, and their upkeep
		// stalled Hegemony's sieges for 16 months. Yards first: the hull pool
		if (s >= 3 && buildProducer(market, s, Industries.HEAVYINDUSTRY)) return;
		if (s >= 3 && buildProducer(market, s, Industries.FUELPROD)) return;
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

	/** Starts the structure if its supplies are paid (payBuild); false if it waits on them. */
	protected static boolean startNew(MarketAPI market, String id) {
		if (!payBuild(market, id)) return false;
		market.addIndustry(id);
		Industry ind = market.getIndustry(id);
		if (ind != null) ind.startBuilding();
		ThreatIncConfig.log("Frontline: " + market.getName() + " builds " + id);
		return true;
	}

	protected static void upgrade(MarketAPI market, Industry ind) {
		if (ind == null || ind.getSpec().getUpgrade() == null) return;
		if (!payBuild(market, ind.getSpec().getUpgrade())) return;
		ind.startUpgrading();
		ThreatIncConfig.log("Frontline: " + market.getName() + " upgrades " + ind.getId()
				+ " to " + ind.getSpec().getUpgrade());
	}

	/**
	 * What the link can put toward a structure (2026-09-30, ThreatBuildCost):
	 * its own supplies above its floor and staging bank, then what a hunt may
	 * take of the faction's other markets whose stock reaches it - the markets
	 * that pay its garrison's upkeep (payUpkeep).
	 */
	protected static float buildFunds(MarketAPI market) {
		float funds = Math.max(0f, ThreatReserves.available(market, Commodities.SUPPLIES)
				- ThreatReserves.stagingBank(market, Commodities.SUPPLIES));
		for (MarketAPI m : IncursionManager.marketsReaching(market.getFaction(), market)) {
			if (m != market && !isOutpost(m)) funds += gives(m, market, Commodities.SUPPLIES);
		}
		return funds;
	}

	/** Whether the structure's supplies can be paid (buildFunds); free while structuresCostSupplies is off. */
	protected static boolean canPayBuild(MarketAPI market, String id) {
		float cost = ThreatBuildCost.supplies(id);
		if (cost <= 0f || buildFunds(market) >= cost) return true;
		ThreatIncConfig.logQuiet("fl_build_" + market.getId(), "Frontline: " + market.getName() + " waits on "
				+ (int) cost + " supplies for " + id + " (" + (int) buildFunds(market) + " to hand)");
		return false;
	}

	/** Pays the structure's supplies, the link first, then the others nearest first; false if it cannot. */
	protected static boolean payBuild(MarketAPI market, String id) {
		if (!canPayBuild(market, id)) return false;
		float cost = ThreatBuildCost.supplies(id);
		if (cost <= 0f) return true;
		float free = Math.max(0f, ThreatReserves.available(market, Commodities.SUPPLIES)
				- ThreatReserves.stagingBank(market, Commodities.SUPPLIES));
		float link = ThreatReserves.drawAbove(market, Commodities.SUPPLIES, Math.min(cost, free));
		float others = link < cost ? payFromOthers(market, null, Commodities.SUPPLIES, cost - link) : 0f;
		ThreatIncConfig.log("Frontline: " + market.getName() + " pays " + (int) (link + others) + " of "
				+ (int) cost + " supplies for " + id + " (link " + (int) link + ", other markets " + (int) others + ")");
		return true;
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

		ThreatFactionStance.refresh();
		for (String fid : ThreatWarState.warFactionIds()) {
			if (Factions.PLAYER.equals(fid) || Factions.THREAT.equals(fid)) continue;
			if (ThreatWarState.excluded(fid)) continue;
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null) continue;
			// its stance (ThreatFactionStance): consolidating founds nothing,
			// pressing a share of its passes
			if (!ThreatFactionStance.foundsLinks(faction, random)) continue;
			// no cap: founding is paid from the reserves, and that is the limit
			planFor(faction, hiveSystems);
		}
	}

	protected static void planFor(FactionAPI faction, List<StarSystemAPI> hiveSystems) {
		String fid = faction.getId();
		Set<String> conn = connected(fid, null);
		List<MarketAPI> anchors = new ArrayList<MarketAPI>();   // colonies and connected links
		List<MarketAPI> reaching = new ArrayList<MarketAPI>();  // bases and every link
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!fid.equals(m.getFactionId()) || ThreatMapFog.hidden(m) || m.getPrimaryEntity() == null) continue;
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
		// every pair (2026-09-29: the first 24 only, and a faction whose one open
		// way lay further down the list never built toward it). It runs once per
		// frontlinePlanDays, and returns at the first site founded. A site no
		// base can garrison (2026-09-30: Epsilon Shero I, beside a big hive,
		// wanted a 1.6-4k FP guard) moves on to the next pair - it used to
		// return, and the Hegemony tried that one site for 40 months while
		// hives 20-35 ly from any base never got a link toward them
		int tries = 0;
		Set<String> unguardable = new HashSet<String>();
		for (Object[] pair : pairs) {
			tries++;
			StarSystemAPI hive = (StarSystemAPI) pair[0];
			MarketAPI anchor = (MarketAPI) pair[1];
			PlanetAPI site = pickSite(faction, anchor, hive, hop, reach);
			if (site != null && unguardable.contains(site.getId())) continue;
			if (site != null) {
				// bases bound by hulls (2026-10-08, after hw63: 111 / 145 links founded, 126 / 132 lost, 70 to
				// strikes of ~650 FP, with every faction at 0 free hulls - the voyage gate below reads fuel
				// and supplies, which they held for 24-226 months, and a rear link needs no guard at all):
				// a link is founded only while the faction's free hulls would hold it against the strike
				// in reach, as a hive grows no swarm its supplies cannot keep. A base no hull can guard
				// or relieve is a world handed to the swarm's score
				float guardFP = guardNeed(site, null, fid) / STRENGTH_PER_FP;
				if (ThreatHulls.enabled() && ThreatHulls.freeFP(fid) < guardFP) {
					ThreatIncConfig.logQuiet("fl_nohulls_" + fid, "Frontline: " + fid + " founds no link at "
							+ site.getName() + " - " + (int) ThreatHulls.freeFP(fid) + " FP of hulls free, its guard needs "
							+ (int) guardFP);
					return;
				}
				MarketAPI payer = payer(faction, site);
				if (payer == null) {
					ThreatIncConfig.log("Frontline: " + fid + " cannot pay for a link at "
							+ site.getName() + " - no base holds the stock");
					return;
				}
				// the founding is paid before the garrison is weighed: its voyage
				// check reads the pool the founding draws from - the structures it
				// stands up with included (ThreatOutposts.linkCost, 2026-10-01)
				float[] cost = ThreatOutposts.linkCost(faction);
				float[] paid = drawFounding(payer, cost);
				// no paper bases: a link is founded only with a garrison to hold it
				// against the strikes in reach, for as long as it stands
				MarketAPI guardBase = null;
				if (ThreatIncConfig.frontlineGarrisonEnabled()) {
					guardBase = garrisonBase(faction, site);
					if (guardBase == null) {
						ThreatReserves.deposit(payer.getId(), Commodities.SUPPLIES, paid[0]);
						ThreatReserves.deposit(payer.getId(), Commodities.FUEL, paid[1]);
						ThreatIncConfig.logQuiet("fl_cannotguard_" + fid, "Frontline: " + fid
								+ " cannot garrison a link at " + site.getName() + " - " + noGarrisonWhy);
						unguardable.add(site.getId());
						continue;
					}
				}
				MarketAPI link = found(faction, site, hive);
				ThreatIncConfig.log("Frontline: paid " + (int) paid[0] + " supplies and "
						+ (int) paid[1] + " fuel from " + payer.getName());
				if (guardBase != null) sendGarrison(find(link), link, guardBase);
				return;
			}
		}
		if (!unguardable.isEmpty()) return;   // sites exist; each was logged unguardable
		Object[] first = pairs.get(0);
		ThreatIncConfig.log("Frontline: " + fid + " has no site toward "
				+ ((StarSystemAPI) first[0]).getName() + " (tried " + tries + " anchor(s))");
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
	 * The faction's base nearest the site that can fund the founding
	 * (linkCost, {@link #canFund}: its reserve and what the markets reaching it
	 * spare), or null. No range: the stock sails up the chain, and a tip far
	 * past every expedition range still gets built.
	 */
	protected static MarketAPI payer(FactionAPI faction, PlanetAPI site) {
		float[] cost = ThreatOutposts.linkCost(faction);
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			float d = Misc.getDistanceLY(m.getLocationInHyperspace(), site.getLocationInHyperspace());
			if (d >= bestDist) continue;
			if (!canFund(m, cost)) continue;
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

	/** No hive and no hostile market in the system, and no link of this faction or another (linkTaken). */
	protected static boolean siteSystemOk(FactionAPI faction, StarSystemAPI sys) {
		if (sys == null || !ThreatIncData.getLiveColonyMarkets(sys.getId()).isEmpty()) return false;
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
		o.factionId = faction.getId();
		o.hiveSystemId = hive != null ? hive.getId() : null;
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
		// patrols are the humans' eyes in hyperspace now (ThreatScouts.sight, 2026-10-05)
		float range = ThreatIncConfig.patrolsEnabled() ? 0f : ThreatIncConfig.strikeDetectLY();
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
		if (m == null || ThreatMapFog.hidden(m) || m.getPrimaryEntity() == null || !m.isInEconomy()) return false;
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
		if (!ThreatIncConfig.frontlineReliefEnabled() || !ThreatIncConfig.frontlineGarrisonEnabled()) return;
		if (strike.getParams() == null || strike.getParams().raidParams == null) return;
		// every link the strike is read as bound for: those of its system (boundFor)
		for (Outpost o : new ArrayList<Outpost>(all())) {
			MarketAPI target = marketOf(o);
			if (target == null || !target.isInEconomy() || !boundFor(strike, target)) continue;
			relieve(o, target);
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
	 * striking now), or -1 with none. Spawned fleets fight on arrival, and so
	 * does a route strike with abstractResolveOnArrival (ThreatPurgeFGI.resolveOnArrival,
	 * a day into its payload). Only with that knob off does vanilla autoresolve
	 * a route at the end of its payload stage - siegeOrbitDays after it arrives.
	 * Timed on the payload's end with the knob on, no guard sailed in a 3.7-year
	 * test: the links fell 42-69 days after launch while the ETA still read
	 * 100+ (2026-09-29).
	 */
	protected static float strikeEta(MarketAPI market) {
		float best = -1f;
		for (ThreatStrikeFGI strike : strikesOn(market)) {
			float eta;
			if (strike.isSpawnedFleets() || ThreatIncConfig.abstractResolveOnArrival()) {
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
			if (sailingHome(f)) back.add(f);
		}
		o.homebound = null;
		if (back.isEmpty() || market.getPrimaryEntity() == null) return 0f;
		MarketAPI home = nearestBase(market.getFaction(), market.getPrimaryEntity());
		if (home == null || home.getPrimaryEntity() == null) return 0f;
		takeOver(o, market, back, home);
		float str = strengthOf(back);
		ThreatIncConfig.log("Frontline: the guard sailing home from " + market.getName() + " turned back, "
				+ back.size() + " fleet(s), " + (int) fpOf(back) + " FP, strength " + (int) str
				+ " (rear; strike on its way " + (int) strikesWeight(market) + ")");
		return str;
	}

	/** Puts fleets already out on the link's guard: on station there, then home to {@code home}. */
	protected static void takeOver(Outpost o, MarketAPI market, List<CampaignFleetAPI> fleets, MarketAPI home) {
		long now = Global.getSector().getClock().getTimestamp();
		for (CampaignFleetAPI f : fleets) {
			// off its tracked leg home, or ThreatReturns steers it back onto it
			ThreatReturns.untrack(f);
			f.clearAssignments();
			f.addAssignment(FleetAssignment.DEFEND_LOCATION, market.getPrimaryEntity(), 100000f,
					"garrisoning " + market.getName());
			f.getMemoryWithoutUpdate().set(GUARD_JOINED_KEY, now);
		}
		if (liveGuards(o).isEmpty()) {
			o.guards = new ArrayList<CampaignFleetAPI>();
			o.guardPaid = now;
			o.guardBaseId = home.getId();
		}
		o.guards.addAll(fleets);
		o.unguardedDays = 0f;
	}

	/**
	 * Guards the faction has out behind the front with no seen strike on their
	 * own link - on station through its rear grace, or sailing home from one -
	 * answer a strike on this link before the navy is asked (user's call
	 * 2026-09-27: run 18's six calls all found the navy with 0 FP to spare).
	 * They are the navy's already. Nearest first, and only fleets that make it
	 * before the strike. The strength borrowed.
	 */
	protected static float borrowRear(Outpost o, MarketAPI market, float need, float eta) {
		if (market.getPrimaryEntity() == null || need <= 0f) return 0f;
		MarketAPI home = nearestBase(market.getFaction(), market.getPrimaryEntity());
		if (home == null || home.getPrimaryEntity() == null) return 0f;
		final Map<CampaignFleetAPI, Float> dist = new HashMap<CampaignFleetAPI, Float>();
		final Map<CampaignFleetAPI, Outpost> from = new HashMap<CampaignFleetAPI, Outpost>();
		for (Outpost d : all()) {
			if (d == o || !o.factionId.equals(d.factionId)) continue;
			MarketAPI m = marketOf(d);
			if (m == null || isFront(m) || !strikesOn(m).isEmpty()) continue;
			List<CampaignFleetAPI> pool = liveGuards(d);
			if (d.homebound != null) {
				for (CampaignFleetAPI f : d.homebound) {
					if (sailingHome(f)) pool.add(f);
				}
			}
			for (CampaignFleetAPI f : pool) {
				float ly = Misc.getDistanceLY(f.getLocationInHyperspace(), market.getLocationInHyperspace());
				if (ly * GUARD_DAYS_PER_LY > eta) continue; // too late
				dist.put(f, ly);
				from.put(f, d);
			}
		}
		List<CampaignFleetAPI> near = new ArrayList<CampaignFleetAPI>(dist.keySet());
		java.util.Collections.sort(near, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				return Float.compare(dist.get(a), dist.get(b));
			}
		});
		List<CampaignFleetAPI> moved = new ArrayList<CampaignFleetAPI>();
		Set<String> names = new java.util.LinkedHashSet<String>();
		float got = 0f;
		for (CampaignFleetAPI f : near) {
			if (got >= need) break;
			Outpost d = from.get(f);
			if (d.guards != null && d.guards.remove(f) && d.guards.isEmpty()) { // lent, not lost
				d.guards = null;
				d.guardBaseId = null;
			}
			if (d.homebound != null) d.homebound.remove(f);
			moved.add(f);
			got += f.getEffectiveStrength();
			names.add(marketOf(d).getName());
		}
		if (moved.isEmpty()) return 0f;
		takeOver(o, market, moved, home);
		ThreatIncConfig.log("Frontline: " + market.getFactionId() + " sent " + market.getName() + " the guards behind the front at "
				+ Misc.getAndJoined(new ArrayList<String>(names)) + ", " + moved.size() + " fleet(s), "
				+ (int) fpOf(moved) + " FP, strength " + (int) got + " (strike on its way " + (int) strikesWeight(market) + ")");
		return got;
	}

	/**
	 * Calls a guard against the seen strikes bound for the link, outside the
	 * upkeep budget: what they weigh x frontlineGarrisonMargin, less the
	 * link's station, the guards already there ({@code have}) and those of the
	 * faction's other links in the system. It sails only once the first
	 * strike is due (strikeEta) within the voyage from the
	 * nearest colony base plus GUARD_LEAD_DAYS - run 16's strikes took 159-203
	 * days from launch to target, and guards called at detection sat on
	 * station for months. The daily step asks again until then; a refusal
	 * waits a week. A guard sent home from behind the front and still sailing
	 * turns back first (turnBack). A guard on station is reinforced only under 80% of what it
	 * must weigh. The guard is what is needed, if the depots can pay its
	 * voyage. True if a guard sailed or turned back;
	 * nothing with the Called Guards knob (frontlineReliefEnabled) off.
	 */
	protected static boolean callGuard(Outpost o, MarketAPI market, float have, boolean atDetection) {
		if (!ThreatIncConfig.frontlineReliefEnabled()) return false;
		// nothing coming on almost every link-day: that test before the other
		// links' guards and the system's defenders are summed
		float call = onCallNeed(market);
		if (call - have < 30f * STRENGTH_PER_FP) return false;
		String key = "fl_guard_" + market.getId();
		float want = call - neighbourGuards(o, market) - otherDefenders(market);
		float need = want - have;
		if (need < 30f * STRENGTH_PER_FP) {
			ThreatIncConfig.logQuiet(key, "Frontline: no guard for " + market.getName() + " - the strike ("
					+ (int) call + ") is covered by its neighbours' guards and defenders");
			return false;
		}
		// a guard on station is reinforced only once it falls under 80% of what
		// it must weigh, as topUp does - run 17's Akron took 9 top-ups of 37-524
		// FP in 80 days
		if (have > 0f && have >= want * 0.8f) return false;
		FactionAPI faction = market.getFaction();
		MarketAPI near = nearestBase(faction, market.getPrimaryEntity());
		float eta = strikeEta(market);
		float voyage = near != null ? ly(near, market) * GUARD_DAYS_PER_LY + GUARD_LEAD_DAYS : 0f;
		if (near != null && eta > voyage) {
			ThreatIncConfig.logQuiet(key, "Frontline: guard for " + market.getName() + " waits - strike due in "
					+ (int) eta + " days, the voyage from " + near.getName() + " takes " + (int) voyage);
			return false; // not yet
		}
		// a guard still sailing home turns back rather than a new one sailing, and
		// the guards behind the front come before the navy
		need -= turnBack(o, market);
		if (need < 30f * STRENGTH_PER_FP) return true;
		need -= borrowRear(o, market, need, eta);
		if (need < 30f * STRENGTH_PER_FP) return true;
		if (Global.getSector().getClock().getElapsedDaysSince(o.guardCalled) < 7f) return false;
		o.guardCalled = Global.getSector().getClock().getTimestamp();
		// what is needed, gated only by the depots paying its voyage (2026-09-29:
		// the navy's spare share - vanilla's faction strength / responseStrengthDivisor,
		// and a partial guard of what it could spare - refused every Persean
		// call at "0 of 878 FP"; a guard is spawned fresh and takes no navy ships)
		float send = need;
		MarketAPI base = garrisonBase(faction, market.getPrimaryEntity(), send / STRENGTH_PER_FP, null);
		if (base != null && sendGarrison(o, market, base, send)) return true;
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
