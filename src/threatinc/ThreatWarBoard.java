package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.econ.ResourceDepositsCondition;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.IconRenderMode;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipLocation;
import com.fs.starfarer.api.util.Misc;

/**
 * "The Abyssal War": the sector-wide board drawn as the large
 * description of {@link ThreatIncursionIntel}. One screen for everything the
 * player knows about the incursion: a header with the phase bar, a one-line
 * strip of sector totals, one custom-drawn ledger row per KNOWN infested
 * system in priority order, and for the selected system a grid of colony
 * cards (organ icons, supplies paid, garrison, fuel bill, reach) beside a table
 * of every operation touching it.
 *
 * Design rules (see design/war-effort/Kit.dc.html): nothing on a row or card
 * is a sentence - chips, digits, bars, pips, counts and crests; every column
 * has a fixed budget so nothing truncates; all prose lives in hover tooltips.
 * Rows and cards are custom panels ({@link CustomPanelAPI}) drawn with GL and
 * carrying positioned text elements, since the stock table only takes text.
 *
 * Reads only. Respects the fog of war: every system the swarm holds is listed
 * (the count of systems is known from the strip anyway), but one the player
 * has not found ({@link ThreatIncData#discoveredSystems()}) is named "Unknown",
 * carries no map in its tooltip, and cannot be selected for detail - its
 * figures are readable, its place is not. Debug mode names everything.
 *
 * The swarm figures - the Swarm FP column and total, the cards' Swarm FP -
 * are the player's reports ({@link ThreatIntel}, the fog of war, 2026-10-01):
 * the figure and its age, "Unknown" for a system never seen. Planet facts and
 * the fronts stay live.
 */
public class ThreatWarBoard {

	/** Rough hyperspace pace used for ETA estimates, matching the other intel. */
	public static final float EST_LY_PER_DAY = 0.5f;

	public static final String BUTTON_MAP = "threatinc_board_map:";
	/** The card's Colony button: opens vanilla's colony screen for the world (ThreatColonyScreenDialog). */
	public static final String BUTTON_COLONY = "threatinc_board_colony:";
	public static final float COLONY_BUTTON_W = 58f;

	/** The font the stock intel table uses for its cells; every text the board places itself matches it. */
	public static final String BOARD_FONT = "graphics/fonts/insignia15LTaa.fnt";

	/** A positioned text element wraps this much before its nominal width; shorten against it. */
	public static final float TEXT_MARGIN = 10f;

	/** Panels narrower than this fold the contract and core columns into tooltips. */
	public static final float NARROW_WIDTH = 1050f;
	/** Panels narrower than this stack the operations table under the colony cards. */
	public static final float STACK_WIDTH = 1050f;

	public static final float ROW_H = 28f;

	/** The commodities the hive economy actually runs on; everything else is vanilla noise. */
	public static final String[] RELEVANT = {Commodities.ORE, Commodities.RARE_ORE, Commodities.VOLATILES,
			Commodities.METALS, Commodities.RARE_METALS, Commodities.HEAVY_MACHINERY, Commodities.FUEL,
			Commodities.SHIPS};

	public static boolean isRelevant(String commodityId) {
		for (String id : RELEVANT) {
			if (id.equals(commodityId)) return true;
		}
		return false;
	}

	/**
	 * Hive-wide totals per relevant commodity, computed once per render over
	 * the whole network, known or not - the same footing the defense boards
	 * already reason on. Only demand is read today (a colony's output nobody
	 * wants is drawn dim): vanilla's economy is not a flow, so shares of a
	 * total say nothing about who feeds whom - see docs/hive-economy.md.
	 */
	public static class HiveSupply {
		public Map<String, Integer> production = new LinkedHashMap<String, Integer>();
		public Map<String, Integer> demand = new LinkedHashMap<String, Integer>();

		public int production(String id) {
			Integer v = production.get(id);
			return v != null ? v : 0;
		}

		public int demand(String id) {
			Integer v = demand.get(id);
			return v != null ? v : 0;
		}

		public static int produced(String id, List<MarketAPI> markets) {
			int sum = 0;
			for (MarketAPI market : markets) {
				CommodityOnMarketAPI com = market.getCommodityData(id);
				if (com != null) sum += Math.max(0, com.getMaxSupply());
			}
			return sum;
		}
	}

	protected static HiveSupply hiveSupply;

	protected static HiveSupply computeHiveSupply() {
		HiveSupply hs = new HiveSupply();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			for (String id : RELEVANT) {
				CommodityOnMarketAPI com = market.getCommodityData(id);
				if (com == null) continue;
				hs.production.put(id, hs.production(id) + Math.max(0, com.getMaxSupply()));
				hs.demand.put(id, hs.demand(id) + Math.max(0, com.getMaxDemand()));
			}
		}
		return hs;
	}
	/** Card height: name line, two stat lines, organ icons with their day counts. */
	public static final float CARD_H = 124f;
	/** Top of the organ icon row on a card; the stat lines sit above it. */
	public static final float ORGANS_Y = 66f;
	public static final float MAP_BUTTON_W = 46f;
	public static final float OPS_W = 400f;

	// ------------------------------------------------------------------
	// data
	// ------------------------------------------------------------------

	/** One operation touching a system - an expedition out, a siege in, a swarm in transit. */
	public static class Op {
		/** "strike", "seeding", "siege", "taskforce", "player". */
		public String kind;
		/** Table cell: "Strike vs Magec - 2 fleets". */
		public String who;
		/** Table cell, fixed vocabulary: mustering - recall / en route / bombarding / engaging / withdrawing. */
		public String status;
		/** Table cell: "~10 d" or "-". */
		public String eta = "-";
		/** Full sentence for tooltips. */
		public String detail;
		public Color color;
		public FactionAPI faction;
		/** Something to jump the intel list to, if any. */
		public IntelInfoPlugin intel;
	}

	public static class Entry {
		public String systemId;
		public StarSystemAPI system;
		public String stage;
		public List<MarketAPI> markets = new ArrayList<MarketAPI>();
		public int mass;
		/** Size-weighted average colony health, 0..1; -1 without colonies. Size upkeep off only. */
		public float health = -1f;
		/** Size upkeep: the share of its worlds' supplies upkeep paid at the last feed, 0..1; -1 with nothing billed. */
		public float fed = -1f;
		/** The Fabrication Cores' state over the system's worlds (fedState): starving before growing before holding. */
		public int core = HOLDING;
		/** The first flow running over the system's worlds (ThreatBudding.flowText); null when nothing moves. */
		public String flow;
		/** Size upkeep: a world here is starving, the first to lose a size in {@code loseDays}. */
		public boolean starving;
		public float loseDays = Float.MAX_VALUE;
		/** +1 growing, 0 stalled (holding), -1 declining or starving. */
		public int trend;
		/**
		 * The player's report of the system's swarms (ThreatIntel, the fog of
		 * war): what their eyes or Cooperative allies last saw; null when
		 * never seen. The board shows no live swarm figure.
		 */
		public ThreatIntel.Report seen;
		public MarketAPI staging;
		/** Billed reach off: the staging colony's fuel radius, and the living systems inside it. */
		public float reachLY;
		public List<String> inReach = new ArrayList<String>();
		/** Billed reach: the faction whose world the system would strike first (null: none known), and light-years to it. */
		public String facedFaction;
		public float facedLY = -1f;
		/** Billed reach: the hive cannot keep one swarm away from here (swarmSource), or has no fuel. */
		public boolean grounded;
		public List<Op> outbound = new ArrayList<Op>();
		public List<Op> inbound = new ArrayList<Op>();
		/** Open defense-board contracts on this system's worlds, accepted ones first. */
		public List<ThreatMissionIntel> missions = new ArrayList<ThreatMissionIntel>();
		public float coreLY = -1f;
		public float playerLY = -1f;
		public float score;
		/** Every relevant commodity this system produces. */
		public Set<String> supplies = new LinkedHashSet<String>();
		/** Chip text, upper case, at most ~14 characters. */
		public String chip;
		/** One line explaining the rank, for tooltips. */
		public String reason;
		public Color reasonColor;

		public boolean isColony() {
			return ThreatIncData.STAGE_COLONY.equals(stage) && !markets.isEmpty();
		}

		public String displayName() {
			return system.getNameWithNoType();
		}

		public int strikeCount() {
			int n = 0;
			for (Op op : outbound) {
				if ("strike".equals(op.kind)) n++;
			}
			return n;
		}
	}

	/** Every system the swarm holds that the player has found, best target first. */
	public static List<Entry> buildEntries() {
		List<Entry> result = new ArrayList<Entry>();
		hiveSupply = computeHiveSupply();
		boolean debug = ThreatIncConfig.debugMode();
		for (String systemId : new ArrayList<String>(ThreatIncData.stages().keySet())) {
			if (!debug && !ThreatIncData.discoveredSystems().contains(systemId)) continue;
			Entry entry = build(systemId);
			if (entry == null) continue;
			result.add(entry);
		}
		Collections.sort(result, new Comparator<Entry>() {
			public int compare(Entry a, Entry b) {
				// nearest first (the user, 2026-10-30: "sort by closest first, rename that column distance"); never seen a core last
				float da = a.coreLY >= 0f ? a.coreLY : Float.MAX_VALUE, db = b.coreLY >= 0f ? b.coreLY : Float.MAX_VALUE;
				int c = Float.compare(da, db);
				return c != 0 ? c : Float.compare(b.score, a.score);
			}
		});
		return result;
	}

	public static Entry findEntry(List<Entry> entries, String systemId) {
		if (systemId == null) return null;
		for (Entry entry : entries) {
			if (systemId.equals(entry.systemId)) return entry;
		}
		return null;
	}

	protected static Entry build(String systemId) {
		StarSystemAPI system = getSystem(systemId);
		if (system == null) return null;
		Entry e = new Entry();
		e.systemId = systemId;
		e.system = system;
		e.stage = ThreatIncData.stages().get(systemId);
		e.markets = ThreatIncData.getLiveColonyMarkets(systemId);

		boolean anyDeclining = false;
		boolean anyGrowing = false;
		for (MarketAPI market : e.markets) {
			e.mass += market.getSize();
			// "declining" since the ground-war rework: a front is on the ground
			// taking the colony apart, or saturation is razing it from orbit
			boolean besieged = ThreatGroundFronts.hasFront(market) || ThreatRazing.saturated(market);
			// the Fabrication Core's state (2026-10-25): growing, holding or starving on its inputs
			int state = fedState(market);
			if (state == STARVING) {
				e.starving = true;
				e.loseDays = Math.min(e.loseDays, ThreatColonyManager.daysToLoseSize(market));
			}
			if (besieged) anyDeclining = true;
			else if (state == GROWING) anyGrowing = true;
		}
		// the swarms as the player last saw them, never the live garrisons
		e.seen = ThreatIntel.report(Factions.PLAYER, systemId);
		e.core = e.starving ? STARVING : anyGrowing ? GROWING : HOLDING;
		for (MarketAPI market : e.markets) {
			if (e.flow == null) e.flow = ThreatBudding.flowText(market);
		}
		e.trend = anyDeclining || e.starving ? -1 : (anyGrowing ? 1 : 0);

		e.staging = ThreatColonyManager.pickStrikeStaging(systemId, false);
		if (ThreatReach.enabled()) {
			// billed reach (2026-09-30): no radius - the world the system would
			// strike first, and whether the hive can keep a swarm away at all
			if (e.isColony()) {
				e.facedFaction = ThreatReach.facedFaction(system);
				e.facedLY = ThreatReach.facedLY(system);
			}
			MarketAPI source = swarmSource(e.markets, e.staging);
			if (source != null) e.grounded = grounded(source);
		} else if (e.staging != null) {
			e.reachLY = ThreatColonyManager.fuelRangeLY(e.staging);
			e.inReach = systemsInReach(system, e.reachLY);
		}

		collectStrikes(e);
		collectRaiders(e);
		collectSeeding(e);
		collectSieges(e);
		collectResponses(e);
		collectOrders(e);
		collectFronts(e);
		collectFrontRuns(e);
		e.missions = missionsIn(e);

		e.coreLY = distanceToCore(system);
		e.playerLY = distanceToPlayerColony(system);
		for (String id : RELEVANT) {
			if (HiveSupply.produced(id, e.markets) > 0) e.supplies.add(id);
		}

		score(e);
		return e;
	}

	// ---- operations ----

	protected static String fleetsText(int n) {
		return n + (n == 1 ? " fleet" : " fleets");
	}

	protected static void collectStrikes(Entry e) {
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			// an unseen strike is not on the board either (docs/frontlines.md)
			if (!strike.isDetected()) continue;
			if (strike.getParams() == null || strike.getParams().source == null) continue;
			StarSystemAPI from = strike.getParams().source.getStarSystem();
			if (from == null || !from.getId().equals(e.systemId)) continue;
			StarSystemAPI where = strike.getParams().raidParams != null
					? strike.getParams().raidParams.where : null;
			String target = where != null ? where.getNameWithNoType() : "unknown";
			int fleets = strike.getParams().fleetSizes.size();

			Op op = new Op();
			op.kind = "strike";
			op.color = Misc.getNegativeHighlightColor();
			op.faction = strike.getFaction();
			op.intel = strike;
			statusOf(strike, op, true);
			op.who = "Strike vs " + target + " - " + fleetsText(fleets);
			op.detail = "Threat strike against " + target + " - " + fleetsText(fleets) + ", "
					+ op.status + (op.eta.equals("-") ? "" : " (" + op.eta + ")") + "."
					+ (strike.isPreparing()
							? " Recall window open: starve the staging colony's Core before departure and the operation is stillborn."
							: " Departed - autonomous; it can only be met in space.");
			e.outbound.add(op);
		}
	}

	/** The ids of this system's hive worlds. */
	protected static Set<String> marketIds(Entry e) {
		Set<String> here = new LinkedHashSet<String>();
		for (MarketAPI market : e.markets) here.add(market.getId());
		return here;
	}

	/**
	 * Hunt task forces (ThreatFleetOrders: NPC hunting forces, coalition
	 * answers or the player's orders) over this system's worlds, Intercepts
	 * still holding its door in older saves, and Support and Defend sorties
	 * over a besieged world here.
	 */
	protected static void collectOrders(Entry e) {
		Set<String> here = marketIds(e);
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			boolean intercept = ThreatFleetOrders.KIND_INTERCEPT.equals(o.kind)
					&& e.systemId.equals(o.targetId);
			boolean escort = ThreatFleetOrders.KIND_SUPPORT.equals(o.kind)
					&& o.targetId != null && here.contains(o.targetId);
			boolean defend = ThreatFleetOrders.KIND_DEFEND.equals(o.kind)
					&& o.targetId != null && here.contains(o.targetId);
			boolean hunt = ThreatFleetOrders.KIND_HUNT.equals(o.kind)
					&& o.targetId != null && here.contains(o.targetId);
			if (!intercept && !escort && !defend && !hunt) continue;
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			FactionAPI faction = Global.getSector().getFaction(o.factionId);
			String name = ThreatWarState.displayName(o.factionId);
			Op op = new Op();
			op.kind = "taskforce";
			op.faction = faction;
			op.color = faction != null ? faction.getBaseUIColor() : Misc.getHighlightColor();
			op.status = intercept ? "on station" : hunt ? (o.arrived ? "hunting" : "en route")
					: defend ? "holding the orbit" : o.raid ? (o.arrived ? "raiding" : "en route")
					: "supporting the siege";
			op.eta = o.indefinite() ? "-" : (int) Math.ceil(o.daysLeft()) + " d left";
			String term = o.indefinite() ? " until the front there is gone."
					: " for " + (int) Math.ceil(o.daysLeft()) + " more days.";
			if (hunt) {
				op.who = name + " hunt";
				op.detail = name + " task force hunting the Defense Swarms over " + o.targetName + term;
			} else if (intercept) {
				op.who = name + " intercept";
				op.detail = name + " task force holding the " + o.targetName + term;
			} else if (defend) {
				op.who = name + " defend";
				op.detail = name + " task force holding the orbit of " + o.targetName
						+ ", bombarding only while its front cannot hold," + term;
			} else if (o.raid) {
				op.who = name + " raid";
				op.detail = name + " task force bombing " + o.targetName + term;
			} else {
				op.who = name + " support";
				op.detail = name + " task force holding the orbit of " + o.targetName
						+ " and suppressing its defences" + term;
			}
			e.inbound.add(op);
		}
	}

	/** Ground fronts on this system's worlds (ThreatGroundFronts): a landing is a crest in the Activity column. */
	protected static void collectFronts(Entry e) {
		for (MarketAPI market : e.markets) {
			ThreatGroundFronts.GroundFront f = ThreatGroundFronts.getFront(market.getId());
			if (f == null) continue;
			String ownerId = ThreatGroundFronts.ownerOf(f);
			FactionAPI faction = Global.getSector().getFaction(ownerId);
			String who = f.isPlayerOwned() ? "Your" : ThreatWarState.displayName(ownerId);
			String state = ThreatGroundFronts.stateWord(f, market, true); // the line from the landing's side
			Op op = new Op();
			op.kind = "front";
			op.faction = faction;
			op.color = f.isPlayerOwned() ? Misc.getBasePlayerColor()
					: faction != null ? faction.getBaseUIColor() : Misc.getHighlightColor();
			op.status = state;
			op.who = who + " front on " + market.getName();
			op.detail = who + " ground front on " + market.getName() + " - "
					+ Misc.getWithDGS(Math.round(f.marines)) + " troops, " + state + ", "
					+ heldLabel(f, market) + ", "
					+ ThreatGroundFronts.stanceLabel(f) + ".";
			e.inbound.add(op);
		}
	}

	/** Supply and evacuation runs bound for a front here (ThreatConvoys). */
	protected static void collectFrontRuns(Entry e) {
		Set<String> here = marketIds(e);
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (c.frontMarketId == null || !here.contains(c.frontMarketId)) continue;
			if (c.fleet == null || !c.fleet.isAlive()) continue;
			FactionAPI faction = Global.getSector().getFaction(c.factionId);
			MarketAPI world = ThreatGroundFronts.resolveMarket(c.frontMarketId);
			String who = ThreatWarState.displayName(c.factionId)
					+ (c.pickup ? " evacuation run" : " supply run");
			Op op = new Op();
			op.kind = "convoy";
			op.faction = faction;
			op.color = faction != null ? faction.getBaseUIColor() : Misc.getHighlightColor();
			op.status = c.runningIn ? "running in"
					: c.waitSinceTimestamp > 0 ? "waiting at the jump-point" : "en route";
			op.who = who;
			op.detail = who + " to the front on " + (world != null ? world.getName() : "a world here")
					+ " - " + op.status + (c.pickup ? "" : ", carrying "
					+ Misc.getWithDGS(Math.round(c.marines)) + " marines and "
					+ Misc.getWithDGS(Math.round(c.armaments)) + " heavy armaments") + ".";
			e.inbound.add(op);
		}
	}

	/** Defense Swarms detached from this system's colonies to hunt convoys (ThreatRaiders). */
	protected static void collectRaiders(Entry e) {
		for (MarketAPI market : e.markets) {
			for (ThreatRaiders.Raider r : ThreatRaiders.raidersFrom(market.getId())) {
				if (r.fleet == null || !r.fleet.isAlive()) continue;
				String who = ThreatWarState.displayName(r.targetFactionId);
				Op op = new Op();
				op.kind = "raid";
				op.color = Misc.getNegativeHighlightColor();
				op.faction = Global.getSector().getFaction(Factions.THREAT);
				op.status = r.returning ? "returning" : "hunting";
				op.who = "Interdiction vs " + who + " convoy";
				op.detail = "A Defense Swarm from " + market.getName() + " is "
						+ (r.returning ? "returning from hunting " : "hunting ") + who
						+ "'s supply convoy. The garrison is one swarm thinner while it is out.";
				e.outbound.add(op);
			}
		}
	}

	protected static void collectSeeding(Entry e) {
		for (Object curr : Global.getSector().getIntelManager().getIntel(SeedingSwarmIntel.class)) {
			SeedingSwarmIntel intel = (SeedingSwarmIntel) curr;
			if (!intel.isInTransit()) continue;
			CampaignFleetAPI fleet = ThreatIncData.waveFleets().get(intel.getPlanetId());
			if (fleet == null || !fleet.isAlive()) continue;
			boolean inbound = e.systemId.equals(intel.getSystemId());
			boolean outbound = false;
			if (!inbound && intel.getSourceName() != null) {
				for (MarketAPI market : e.markets) {
					if (intel.getSourceName().equals(market.getName())) outbound = true;
				}
			}
			if (!inbound && !outbound) continue;

			StarSystemAPI target = getSystem(intel.getSystemId());
			Op op = new Op();
			op.kind = "seeding";
			op.color = Misc.getNegativeHighlightColor();
			op.faction = Global.getSector().getFaction(Factions.THREAT);
			op.intel = intel;
			if (target != null && fleet.getContainingLocation() == target) {
				op.status = "terminal approach";
			} else if (target != null) {
				float dist = Misc.getDistanceLY(fleet.getLocationInHyperspace(),
						target.getLocation());
				op.status = "in transit";
				op.eta = "~" + Math.max(1, Math.round(dist / EST_LY_PER_DAY)) + " d";
			} else {
				op.status = "in transit";
			}
			String targetName = target != null ? target.getNameWithNoType() : "unknown";
			String from = intel.getSourceName() != null ? intel.getSourceName() : "the Abyss";
			if (inbound) {
				op.who = "Seeding swarm from " + from;
				op.detail = "A Seeding Swarm from " + from + " is bound for "
						+ (intel.getPlanetName() != null ? intel.getPlanetName() : "this system")
						+ " - " + op.status + (op.eta.equals("-") ? "" : ", planetfall " + op.eta)
						+ ". Destroy it before planetfall and no colony takes root.";
				e.inbound.add(op);
			} else {
				op.who = "Seeding swarm to " + targetName;
				op.detail = "A Seeding Swarm launched from " + from + " is carrying a fabricator "
						+ "core to " + targetName + " - " + op.status
						+ (op.eta.equals("-") ? "" : ", planetfall " + op.eta) + ".";
				e.outbound.add(op);
			}
		}
	}

	protected static void collectSieges(Entry e) {
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding()) continue;
			StarSystemAPI where = purge.getParams() != null && purge.getParams().raidParams != null
					? purge.getParams().raidParams.where : null;
			if (where == null || !where.getId().equals(e.systemId)) continue;
			boolean player = purge instanceof ThreatPurgeFGI
					&& ((ThreatPurgeFGI) purge).isPlayerCommissioned();
			FactionAPI faction = purge.getFaction();
			String who = player ? "Your expedition"
					: (faction != null ? faction.getDisplayName() : "Faction") + " siege";
			int fleets = purge.getParams().fleetSizes.size();

			Op op = new Op();
			op.kind = player ? "player" : "siege";
			op.color = player ? Misc.getBasePlayerColor()
					: (faction != null ? faction.getBaseUIColor() : Misc.getHighlightColor());
			op.faction = faction;
			op.intel = purge;
			statusOf(purge, op, false);
			op.who = who + " - " + fleetsText(fleets);
			op.detail = who + " - " + fleetsText(fleets) + ", " + op.status
					+ (op.eta.equals("-") ? "" : " (" + op.eta + ")") + ".";
			e.inbound.add(op);
		}
	}

	protected static void collectResponses(Entry e) {
		Set<String> here = new LinkedHashSet<String>();
		for (MarketAPI market : e.markets) here.add(market.getId());
		for (Object curr : IncursionManager.getResponseList()) {
			if (!(curr instanceof ThreatResponseIntel)) continue;
			ThreatResponseIntel response = (ThreatResponseIntel) curr;
			if (!response.isFleetActive()) continue;
			if (response.getTargetMarketId() == null
					|| !here.contains(response.getTargetMarketId())) continue;
			FactionAPI faction = response.getFaction();
			String who = (faction != null ? faction.getDisplayName() : "Faction") + " task force";
			int living = response.countLivingFleets();
			Op op = new Op();
			op.kind = "taskforce";
			op.color = faction != null ? faction.getBaseUIColor() : Misc.getHighlightColor();
			op.faction = faction;
			op.intel = response;
			if (response.isEngaging()) {
				op.status = "engaging";
			} else {
				op.status = "en route";
				op.eta = "~" + Math.max(1, (int) Math.ceil(response.etaDays())) + " d";
			}
			op.who = who + " - " + fleetsText(living);
			op.detail = who + " - " + fleetsText(living) + ", " + op.status
					+ (op.eta.equals("-") ? "" : " (" + op.eta + ")") + ". Hunting the Defense "
					+ "Swarms in orbit" + (response.getTargetColonyName() != null
							? " over " + response.getTargetColonyName() : "") + ".";
			e.inbound.add(op);
		}
	}

	protected static List<ThreatMissionIntel> missionsIn(Entry e) {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		Set<String> here = new LinkedHashSet<String>();
		for (MarketAPI market : e.markets) here.add(market.getId());
		// the player's own accepted contracts lead; offers follow
		for (ThreatMissionIntel mission : ThreatMissionIntel.getAccepted()) {
			if (here.contains(mission.getMarketId())) result.add(mission);
		}
		for (ThreatMissionIntel mission : ThreatMissionIntel.getPosted()) {
			if (here.contains(mission.getMarketId())) result.add(mission);
		}
		return result;
	}

	/** Fills op.status / op.eta from the fleet group's current action. */
	protected static void statusOf(GenericRaidFGI fgi, Op op, boolean strike) {
		FGAction action = fgi.getCurrentAction();
		String id = action != null ? action.getId() : null;
		if (fgi.isInPreLaunchDelay() || GenericRaidFGI.PREPARE_ACTION.equals(id)) {
			int days = Math.max(1, (int) Math.ceil(fgi.getETAUntil(GenericRaidFGI.TRAVEL_ACTION)));
			op.status = strike ? "mustering - recall" : "mustering";
			op.eta = "~" + days + " d";
		} else if (GenericRaidFGI.TRAVEL_ACTION.equals(id)) {
			int days = Math.max(1, (int) Math.ceil(fgi.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION)));
			op.status = "en route";
			op.eta = "~" + days + " d";
		} else if (GenericRaidFGI.PAYLOAD_ACTION.equals(id)) {
			op.status = strike ? "bombarding" : "engaging";
		} else if (GenericRaidFGI.RETURN_ACTION.equals(id)) {
			op.status = "withdrawing";
		} else {
			op.status = "under way";
		}
	}

	// ---- geography ----

	protected static List<String> systemsInReach(StarSystemAPI from, float rangeLY) {
		List<String> result = new ArrayList<String>();
		if (rangeLY <= 0f) return result;
		final Map<String, Float> dist = new LinkedHashMap<String, Float>();
		Map<String, String> names = new LinkedHashMap<String, String>();
		for (MarketAPI other : Global.getSector().getEconomy().getMarketsCopy()) {
			if (Factions.THREAT.equals(other.getFactionId())) continue;
			if (ThreatMapFog.hidden(other) || ThreatMapFog.conditionOnly(other)) continue;
			StarSystemAPI system = other.getStarSystem();
			if (system == null || system == from || other.getSize() < 3) continue;
			if (system.getNameWithNoType() == null || system.getNameWithNoType().trim().isEmpty()) continue;
			float d = Misc.getDistanceLY(from.getLocation(), system.getLocation());
			if (d > rangeLY) continue;
			if (!dist.containsKey(system.getId())) {
				dist.put(system.getId(), d);
				names.put(system.getId(), system.getNameWithNoType()
						+ (other.isPlayerOwned() ? " (yours)" : ""));
			} else if (other.isPlayerOwned() && !names.get(system.getId()).endsWith("(yours)")) {
				names.put(system.getId(), system.getNameWithNoType() + " (yours)");
			}
		}
		List<String> ids = new ArrayList<String>(dist.keySet());
		Collections.sort(ids, new Comparator<String>() {
			public int compare(String a, String b) {
				return Float.compare(dist.get(a), dist.get(b));
			}
		});
		for (String id : ids) result.add(names.get(id));
		return result;
	}

	/** Light-years to the nearest major (size 6+) non-Threat, non-player world; falls back to size 5. */
	protected static float distanceToCore(StarSystemAPI from) {
		float best = -1f;
		for (int minSize = 6; minSize >= 5 && best < 0f; minSize--) {
			for (MarketAPI other : Global.getSector().getEconomy().getMarketsCopy()) {
				if (Factions.THREAT.equals(other.getFactionId())) continue;
				if (other.isPlayerOwned() || ThreatMapFog.hidden(other)) continue;
				if (other.getStarSystem() == null || other.getSize() < minSize) continue;
				float d = Misc.getDistanceLY(from.getLocation(), other.getStarSystem().getLocation());
				if (best < 0f || d < best) best = d;
			}
		}
		return best;
	}

	protected static float distanceToPlayerColony(StarSystemAPI from) {
		float best = -1f;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			if (market.getStarSystem() == null) continue;
			float d = Misc.getDistanceLY(from.getLocation(), market.getStarSystem().getLocation());
			if (best < 0f || d < best) best = d;
		}
		return best;
	}

	// ---- priority ----

	/**
	 * What makes a system the one to deal with, most urgent first: an
	 * expedition actually staging or in flight; living systems inside its
	 * strike reach (billed reach, which has no radius: a strike the hive can
	 * pay for at all - CAN STRIKE, +200 flat); a colony already declining or
	 * starving that the sector should press; the network's decisive link (the
	 * defense boards' impact figure); then sheer mass and proximity to the
	 * core. The chip names the top contributor so the ordering explains itself.
	 */
	protected static void score(Entry e) {
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color h = Misc.getHighlightColor();
		Color threat = Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();

		if (!e.isColony()) {
			boolean swarmInbound = !e.inbound.isEmpty();
			e.score = swarmInbound ? 60f : 20f;
			if (e.coreLY >= 0f) e.score += Math.max(0f, 30f - e.coreLY);
			e.chip = swarmInbound ? "SEEDING" : "MARKED";
			e.reason = swarmInbound ? "A Seeding Swarm is inbound - intercept it before planetfall."
					: "Marked for seeding - no colony yet.";
			e.reasonColor = neg;
			return;
		}

		float score = 0f;
		String chip = null;
		String reason = null;
		Color color = h;

		int strikes = e.strikeCount();
		String strikeTarget = null;
		for (Op op : e.outbound) {
			if ("strike".equals(op.kind)) {
				strikeTarget = op.who.substring("Strike vs ".length(), op.who.indexOf(" - "));
				break;
			}
		}
		float impact = 0f;
		int impactType = -1;
		for (MarketAPI market : e.markets) {
			for (int type = 0; type < ThreatMissionIntel.TYPE_COUNT; type++) {
				float value = ThreatMissionIntel.networkImpact(type, market);
				if (value > impact) {
					impact = value;
					impactType = type;
				}
			}
		}
		boolean declining = e.trend < 0;
		boolean home = ThreatIncData.isOGSystem(e.systemId);

		if (strikes > 0) {
			score += 1000f + 50f * strikes;
			chip = strikes > 1 ? "STRIKING x" + strikes : "STRIKING";
			reason = strikes > 1
					? strikes + " expeditions staged from here are in flight - the first against "
							+ strikeTarget + "."
					: "An expedition staged from here is in flight against " + strikeTarget + ".";
			color = neg;
		}
		if (ThreatReach.enabled()) {
			// every world is in reach of a paid trip, so a count of systems in
			// reach means nothing: the tier goes to a system that can strike at
			// all - a staging colony, a swarm the hive can keep away, a target
			if (e.staging != null && !e.grounded && e.facedFaction != null) {
				score += 200f;
				if (chip == null) {
					chip = "CAN STRIKE";
					reason = "The hive can keep a swarm away from here.";
					color = neg;
				}
			}
		} else if (!e.inReach.isEmpty()) {
			score += 200f + 40f * e.inReach.size();
			if (chip == null) {
				chip = "IN REACH " + e.inReach.size();
				reason = e.inReach.size() + (e.inReach.size() == 1 ? " living system lies"
						: " living systems lie") + " inside its strike reach.";
				color = neg;
			}
		}
		if (declining) {
			score += 60f;
			if (chip == null) {
				if (e.starving) {
					chip = "STARVING";
					reason = "Starving - a size lost in ~" + Math.max(1, (int) e.loseDays) + " d.";
				} else {
					chip = "DECLINING";
					reason = "A colony here is already declining - press the siege.";
				}
				color = pos;
			}
		}
		score += 100f * impact;
		if (chip == null && impactType >= 0 && (home || impact >= 1f)) {
			chip = home ? "HOME HIVE" : "NETWORK LINK";
			reason = (home ? "The home hive that founded the network - " : "A decisive link - ")
					+ ThreatMissionIntel.typeNoun(impactType) + " the rest of the hive depends on.";
			color = threat;
		}
		score += 8f * e.mass;
		if (e.coreLY >= 0f) score += Math.max(0f, 30f - e.coreLY);
		if (chip == null) {
			if (e.mass < ThreatIncConfig.strikeMinSize()) {
				chip = "FOOTHOLD";
				reason = "A young foothold, below strike size.";
			} else if (e.trend == 0) {
				chip = "HOLDING";
				reason = "Holding its size - no world here is growing.";
			} else {
				chip = "ENTRENCHED";
				reason = "Entrenched and growing; not yet staging expeditions.";
			}
			color = h;
		}
		e.score = score;
		e.chip = chip;
		e.reason = reason;
		e.reasonColor = color;
	}

	// ------------------------------------------------------------------
	// rendering
	// ------------------------------------------------------------------

	public static void render(ThreatIncursionIntel intel, TooltipMakerAPI main, float width) {
		float opad = 10f;
		// a board order can change a player base's staging target with the
		// clock stopped, and an industry built or shut down the peacetime
		// demand; every tab reads both, so the memos empty here
		ThreatConvoys.forgetStagingTargets();
		WarFootingDemand.forgetPeacetimeDemand();
		IntelUIAPI ui = main.getIntelUI();
		ThreatIncConfig.log("war board render width=" + width
				+ " narrow=" + (width - 24f < NARROW_WIDTH) + " stack=" + (width < STACK_WIDTH));
		List<Entry> entries = buildEntries();
		Entry selected = findEntry(entries, intel.getSelectedSystemId());
		if (selected == null && !entries.isEmpty()) selected = entries.get(0);

		addHeader(main, width);
		CustomPanelAPI strip = addStrip(main, width, entries);
		// the faction selector (docs/strategy-layer.md): the hive's war, or a
		// mobilised faction's - colonies, reserves, fleets and orders. Its
		// buttons are floating (added last, anchored to the strip); only the
		// row's space is reserved here
		String view = intel.getSelectedFactionId();
		boolean selector = ThreatFactionView.reserveSelectorRow(main);
		// the debug board (the user, 2026-10-30): everything the swarm is doing and planning, debug mode only
		if (ThreatDebugBoard.VIEW_DEBUG.equals(view) && ThreatIncConfig.debugMode()) {
			nameWidths.clear();
			ThreatDebugBoard.render(intel, main, width, opad);
			float h = main.getHeightSoFar();
			if (selector) ThreatFactionView.addSelector(intel, main, width, view, strip);
			main.setHeightSoFar(h);
			return;
		}
		// the player's own tab shows before they mobilise too: that page is
		// where the Mobilise button lives
		if (view != null && !ThreatFactionView.VIEW_THREAT.equals(view)
				&& (ThreatWarState.isAtWar(view)
						|| (Factions.PLAYER.equals(view) && ThreatWarState.playerMayMobilise()))) {
			nameWidths.clear();
			ThreatFactionView.render(intel, main, width, opad, view);
			float h = main.getHeightSoFar();
			if (selector) ThreatFactionView.addSelector(intel, main, width, view, strip);
			main.setHeightSoFar(h);
			return;
		}
		// every front, from the swarm's side, above the hive list (user,
		// 2026-10-18: "if you are on the threat tab you see all the fronts from
		// the context of the threat"); the selected system's own used to sit
		// under its heading
		Fronts fronts = addFronts(intel, main, width, opad, frontRows(),
				Global.getSector().getFaction(Factions.THREAT));
		Ledger ledger = addLedger(intel, ui, main, width, opad, entries, selected);
		nameWidths.clear();
		// no drill-down under the ledger since 2026-10-30 15:45 (the user: "get rid of the planet tiles"): the
		// heading and the colony cards (addDetailHeading / addDetailBody, kept for their helpers) are not drawn
		// Floating buttons are anchored to siblings already laid out and added
		// LAST: the tooltip continues its flow from the most recent component,
		// so anything added after a moved button would land beside it.
		float heightBefore = main.getHeightSoFar();
		if (selector) ThreatFactionView.addSelector(intel, main, width, view, strip);
		addFrontButtons(intel, main, fronts);
		addLedgerButtons(intel, main, ledger);
		main.setHeightSoFar(heightBefore);
	}

	/** What the ledger leaves behind for the floating buttons. */
	protected static class Ledger {
		com.fs.starfarer.api.ui.UIPanelAPI table;
		Cols cols;
		List<Entry> rows = new ArrayList<Entry>();
		/** Whether a row was reserved above the table for the Attack's Fleet and Days levers (ThreatFactionView.addLedgerLevers). */
		boolean levers;
	}

	/**
	 * Attack and Map on every ledger row (the user, 2026-10-30: "new action column at the end attack
	 * which does the same as what it does on the player board ... and also map button that shows the
	 * system on the map"). Attack is the player board's order (ThreatFactionView.BUTTON_SIEGE: the
	 * fleet deployed at the nearest player colony sails, IncursionManager.attackBase); Map selects the
	 * system on the map (BUTTON_MAP, ThreatIncursionIntel.buttonPressConfirmed). Floating buttons,
	 * added after everything they anchor to.
	 */
	public static void addLedgerButtons(ThreatIncursionIntel intel, TooltipMakerAPI main, Ledger ledger) {
		if (ledger == null || ledger.table == null) return;
		FactionAPI player = Global.getSector().getPlayerFaction();
		boolean mayOrder = ThreatFleetOrders.canPlayerOrder(player);
		String blocked = ThreatFleetOrders.orderBlockReason(player);
		// the Attack: built at the Colony toggle's colony at the ledger's Fleet and Days levers, loaded at the
		// colony levers (ThreatFactionView.attackSource, ThreatIncursionIntel.ledgerLevers, 2026-10-31 evening)
		int[] tiers = intel.ledgerLevers();
		int[] levers = intel.colonyLevers();
		MarketAPI base = ThreatFactionView.attackSource(intel);
		int n = ledger.rows.size();
		for (int i = 0; i < n; i++) {
			Entry e = ledger.rows.get(i);
			float up = (n - i) * ROW_H - (ROW_H - 20f) / 2f;
			com.fs.starfarer.api.ui.ButtonAPI attack = intel.addGenericButton(main,
					ThreatFactionView.SMALL_BUTTON_W + 10f, "Attack",
					ThreatFactionView.BUTTON_SIEGE + Factions.PLAYER + ":" + e.systemId);
			attack.getPosition().belowRight(ledger.table, -up).setXAlignOffset(-6f);
			String why = e.system != null ? IncursionManager.attackBlockReason(base, player, e.system, tiers[0], tiers[1], levers)
					: "Nothing there to attack yet.";
			boolean ok = mayOrder && e.isColony() && why == null;
			ThreatFactionView.disableWith(main, attack, ok, blocked != null ? blocked
					: why != null ? why : "Nothing there to attack yet.",
					"A fleet built at " + (base != null ? base.getName() : "the colony chosen") + " at the Fleet lever, loaded at the"
					+ " colony levers, holds the orbit up to " + (int) ThreatIncConfig.attackTermDays(tiers[1])
					+ " days a world, razes the strata with the fuel aboard past the passage and lands its marines.");
			com.fs.starfarer.api.ui.ButtonAPI map = intel.addGenericButton(main, MAP_BUTTON_W, "Map",
					BUTTON_MAP + e.systemId);
			map.getPosition().belowRight(ledger.table, -up)
					.setXAlignOffset(-6f - ThreatFactionView.SMALL_BUTTON_W - 10f - 6f);
			// SCOUT (the user, 2026-10-30 16:00: "lets add it back here: scout button"): a party from the nearest colony
			// that can raise one; what it sees is the row's Notes when it is home (ThreatScouts.playerRecon)
			com.fs.starfarer.api.ui.ButtonAPI scout = intel.addGenericButton(main, ThreatFactionView.SMALL_BUTTON_W, "Scout",
					ThreatFactionView.BUTTON_SCOUT + Factions.PLAYER + ":" + e.systemId);
			scout.getPosition().belowRight(ledger.table, -up)
					.setXAlignOffset(-6f - ThreatFactionView.SMALL_BUTTON_W - 10f - 6f - MAP_BUTTON_W - 6f);
			String scoutWhy = e.system != null ? ThreatScouts.scoutOffReason(e.system) : "No target.";
			ThreatFactionView.disableWith(main, scout, scoutWhy == null, scoutWhy != null ? scoutWhy : "",
					"Sends a scouting party of " + (int) ThreatIncConfig.scoutFleetPoints()
					+ " fleet points from your nearest colony that can raise one. What it sees is this row's Notes once it is home.");
		}
		if (ledger.levers) ThreatFactionView.addLedgerLevers(intel, main, ledger.table);
	}

	// ------------------------------------------------------------------
	// ground fronts (docs/ground-war.md "Reading a front"): the table both
	// views draw - the hive view for its selected system, a faction view for
	// the fronts that faction is party to
	// ------------------------------------------------------------------

	/** One row of the fronts table: a landing and the world it is fighting on. */
	public static class FrontRow {
		public ThreatGroundFronts.GroundFront front;
		public MarketAPI market;
	}

	/** What the fronts table leaves behind for its floating buttons. */
	public static class Fronts {
		public com.fs.starfarer.api.ui.UIPanelAPI table;
		public List<FrontRow> rows = new ArrayList<FrontRow>();
		/** Every row the table holds: the entries (row i is rows[i]) and the Total row. */
		public int rowCount;
		/** The Threat tab's table: no Actions column, no buttons. */
		public boolean threatView;
		/** Whether a row was reserved above the table for the supply-load selector. */
		public boolean tierSelector;
	}

	protected static final float FRONT_STANCE_W = 56f;
	protected static final float FRONT_DEFEND_W = 58f;

	/** The landing's next objective (the user, 2026-10-31): the industry its next district claims, "Consume" (a human landing: "Extermination") once it holds every layer, else the layer itself. */
	protected static String landingObjective(ThreatGroundFronts.GroundFront f, MarketAPI m, Industry claim) {
		if (claim != null) return claim.getCurrentName();
		if (f.strataHeld >= Math.max(1, m.getSize())) {
			// the swarm holding every district consumes the world, never takes it (the user, 2026-10-31:
			// "threat final objective should just be consume not extermination")
			return ThreatGroundFronts.isThreatOwned(f) && !ThreatGroundFronts.isHiveTarget(m) ? "Consume" : "Extermination";
		}
		return Misc.ucFirst(ThreatGroundFronts.layerName(m));
	}

	/** The world's next objective: the industry its next district reclaims, "Extermination" while the landing holds no layer (Arkoman 5/5, the user, 2026-10-31: "why does it still say district"), else the layer itself. */
	protected static String worldObjective(ThreatGroundFronts.GroundFront f, MarketAPI m, Industry release) {
		if (release != null) return release.getCurrentName();
		if (f.strataHeld <= 0) return "Extermination";
		return Misc.ucFirst(ThreatGroundFronts.layerName(m));
	}

	/** Every live front, the player's own first, then by world name. */
	public static List<FrontRow> frontRows() {
		List<FrontRow> rows = new ArrayList<FrontRow>();
		for (ThreatGroundFronts.GroundFront front : ThreatGroundFronts.fronts().values()) {
			if (front == null) continue;
			// generic resolver: a Threat front on a human world has no hive market
			MarketAPI market = ThreatGroundFronts.resolveMarket(front.marketId);
			if (market == null) continue;
			FrontRow r = new FrontRow();
			r.front = front;
			r.market = market;
			rows.add(r);
		}
		Collections.sort(rows, new Comparator<FrontRow>() {
			public int compare(FrontRow a, FrontRow b) {
				boolean pa = a.front.isPlayerOwned();
				boolean pb = b.front.isPlayerOwned();
				if (pa != pb) return pa ? -1 : 1;
				return a.market.getName().compareTo(b.market.getName());
			}
		});
		return rows;
	}

	/** The fronts on one system's worlds - the hive view's selected system. */
	public static List<FrontRow> frontRowsIn(Entry e) {
		List<FrontRow> rows = new ArrayList<FrontRow>();
		if (e == null || e.systemId == null) return rows;
		for (FrontRow r : frontRows()) {
			if (r.market.getStarSystem() != null
					&& e.systemId.equals(r.market.getStarSystem().getId())) {
				rows.add(r);
			}
		}
		return rows;
	}

	/** The fronts a faction is party to: its own landings, and the swarm's landings on its worlds. */
	public static List<FrontRow> frontRowsFor(String factionId) {
		List<FrontRow> rows = new ArrayList<FrontRow>();
		if (factionId == null) return rows;
		for (FrontRow r : frontRows()) {
			boolean own = factionId.equals(ThreatGroundFronts.ownerOf(r.front));
			boolean invaded = ThreatGroundFronts.isThreatOwned(r.front)
					&& factionId.equals(r.market.getFactionId());
			if (own || invaded) rows.add(r);
		}
		return rows;
	}

	/** Whether the player's row buttons apply to this front: their own, while their faction may order. */
	protected static boolean playerCanOrder(FrontRow r) {
		return r.front.isPlayerOwned()
				&& ThreatFleetOrders.canPlayerOrder(Global.getSector().getPlayerFaction());
	}

	/**
	 * Whether the player has orders on the world this front stands on (2026-10-17): a Threat
	 * army on a human colony - the player's own while their faction may order (the defenders'
	 * Push / Hold), or an ally's while the player may aid it (ThreatAid.canAid; Defend at the Fleet
	 * lever since 2026-10-29 evening). The button itself carries its gate.
	 */
	protected static boolean playerCanReinforce(FrontRow r) {
		if (!ThreatGroundFronts.isThreatOwned(r.front) || ThreatGroundFronts.isHiveTarget(r.market)) return false;
		if (r.market.isPlayerOwned()) {
			return ThreatFleetOrders.canPlayerOrder(Global.getSector().getPlayerFaction());
		}
		return ThreatAid.canAid(r.market.getFaction()) == null;
	}

	/** The State cell's colour for a line word: gaining ground good, losing it bad, grinding highlighted, stalled plain. */
	protected static Color lineColor(String line, Color pos, Color neg, Color h, Color text) {
		if (ThreatGroundFronts.LINE_GAINING.equals(line) || ThreatGroundFronts.LINE_OVERRUNNING.equals(line)
				|| ThreatGroundFronts.LINE_ADVANCING.equals(line)) return pos;
		if (ThreatGroundFronts.LINE_LOSING.equals(line) || ThreatGroundFronts.LINE_OVERRUN.equals(line)
				|| ThreatGroundFronts.LINE_RETREATING.equals(line)) return neg;
		if (ThreatGroundFronts.LINE_GRINDING.equals(line) || ThreatGroundFronts.LINE_CHECKED.equals(line)
				|| ThreatGroundFronts.LINE_BREAKING.equals(line)) return h;
		return text; // stalled, entrenched, dug in, holding
	}

	/**
	 * The Held / Strata cell: the strata a hive still stands on (held/size read as
	 * a reset when starvation took a size off the hive - user, 2026-10-11), the
	 * districts held of a colony's size.
	 */
	protected static String heldCell(ThreatGroundFronts.GroundFront f, MarketAPI m) {
		if (ThreatGroundFronts.Theatre.of(m) == ThreatGroundFronts.HIVE) {
			// taken of the hive's strata (the user, 2026-10-30 evening: "held number is wrong and should be 0/3")
			return f.strataHeld + "/" + m.getSize();
		}
		// the swarm's front on a colony eats what it holds and grows by it (phase E): the growth a day
		float feed = ThreatGroundFronts.infestGrowthPerDay(m, f);
		return f.strataHeld + "/" + m.getSize() + (feed > 0f ? " +" + ThreatStrata.fmt(feed) + "/d" : "");
	}

	/** heldCell in words: "3 strata standing" or "1 of 4 districts". */
	protected static String heldLabel(ThreatGroundFronts.GroundFront f, MarketAPI m) {
		if (ThreatGroundFronts.Theatre.of(m) == ThreatGroundFronts.HIVE) {
			return f.strataHeld + " of " + m.getSize() + " strata";
		}
		return f.strataHeld + " of " + m.getSize() + " districts";
	}

	/**
	 * The fronts table: one row per landing in {@code rows}, nothing when there
	 * are none. The columns are the figures a push-or-dig-in decision needs;
	 * the row tooltip is the arithmetic behind them, one line per fact
	 * (ThreatGroundFronts' readouts, so the table quotes what the tick does).
	 * On a faction's tab the last column is Actions, where {@link #addFrontButtons} floats
	 * the row's one button (2026-10-30 evening; until then a front the player could order
	 * took two rows, its five buttons over the second, and a System column sat after World).
	 */
	public static Fronts addFronts(ThreatIncursionIntel intel, TooltipMakerAPI main,
			float width, float opad, List<FrontRow> rows, FactionAPI tableFaction) {
		if (rows == null || rows.isEmpty()) return null;

		FactionAPI colours = tableFaction != null ? tableFaction
				: Global.getSector().getFaction(Factions.THREAT);
		// the tab's side (user, 2026-10-18): the swarm's on the Threat tab, the
		// marines' on a faction's. Every cell reads from it; the enemy's figure
		// is the right-hand half of Force, the rest of its arithmetic the tooltip
		final boolean threatView = tableFaction == null || Factions.THREAT.equals(tableFaction.getId());
		Color dark = colours.getDarkUIColor();
		Color bright = colours.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();

		main.addSectionHeading("Ground fronts", bright, dark, Alignment.MID, opad);
		// the supply-load selector sits in a reserved row under the heading,
		// only where a front of the player's carries orders; its buttons float
		// in later (addFrontButtons -> addSupplyTierSelector)
		// no orders on the Threat tab (the user, 2026-10-24): a faction is aided from its own tab
		// the Fleet lever sizes an ally's Defend alone since 2026-10-30 evening (the player's own
		// fronts are served from the Domain table at its levers)
		boolean tierSelector = false;
		for (FrontRow r : rows) {
			if (!threatView && playerCanReinforce(r) && !r.market.isPlayerOwned()) tierSelector = true;
		}
		if (tierSelector) main.addSpacer(ThreatFactionView.SELECTOR_ROW_H);
		float tw = width - 24f;
		// the columns share the width by fraction; a front's orders float on
		// the row beneath its entry
		List<String> names = new ArrayList<String>();
		List<Float> fracs = new ArrayList<Float>();
		// no crest column: whose side the row reads from is the tab's (2026-10-18)
		names.add("World"); fracs.add(.13f);
		names.add("Force"); fracs.add(.12f);
		names.add("State"); fracs.add(.075f);
		names.add("Held"); fracs.add(.055f);
		names.add("Stance"); fracs.add(.085f);
		names.add("Losses"); fracs.add(.075f);
		names.add("ETA"); fracs.add(.055f);
		names.add("Objective"); fracs.add(.11f);
		names.add("Arms"); fracs.add(.055f);
		names.add("Victory"); fracs.add(.07f);
		names.add("Space"); fracs.add(.09f);
		// the one button a row floats over the last cell (the user, 2026-10-30 evening: "move the
		// push/hold button to actions column right most side"); no orders on the Threat tab
		if (!threatView) { names.add("Actions"); fracs.add(.075f); }
		float sum = 0f;
		for (Float fr : fracs) sum += fr;
		float[] w = new float[names.size()];
		List<Object> columns = new ArrayList<Object>();
		for (int i = 0; i < names.size(); i++) {
			w[i] = (float) Math.floor(tw * fracs.get(i) / sum);
			columns.add(names.get(i));
			columns.add(w[i]);
		}
		com.fs.starfarer.api.ui.UIPanelAPI table = main.beginTable2(colours, ROW_H, true, true,
				columns.toArray());
		main.makeTableItemsClickable();
		int strengthCol = 1;
		String side = threatView ? "the swarm's" : "this faction's";
		main.addTableHeaderTooltip(strengthCol, "Force: " + side + " strength, then the enemy's. "
				+ "-> " + side + " attacking, <- the enemy attacking, <-> both, - neither. "
				+ "A side gains ground by what it outweighs the other's cover by.");
		main.addTableHeaderTooltip(strengthCol + 1, "State of " + side + " side: pushing - overrunning, gaining, "
				+ "grinding, stalled, checked; holding - entrenched, dug in, holding; pushed back - breaking, retreating, overrun.");
		main.addTableHeaderTooltip(strengthCol + 2, "Districts or strata: taken by " + side + " landing, "
				+ "or still standing on " + side + " world.");
		main.addTableHeaderTooltip(strengthCol + 3, "Stance of " + side + " side: push moves the line, "
				+ "hold defends from cover, regrouping awaits orders.");
		main.addTableHeaderTooltip(strengthCol + 4, "Lost a day by " + side + " side at the current "
				+ "stances and supply.");
		main.addTableHeaderTooltip(strengthCol + 5, "Days until " + side + " side takes the next district "
				+ "or stratum at the line's current rate; - while the line stands or moves against it.");
		main.addTableHeaderTooltip(strengthCol + 6, "What the next district decides for " + side + " side: the industry "
				+ "a landing claims next, or a world takes back next.");
		main.addTableHeaderTooltip(strengthCol + 7, "Days of heavy armaments left to " + side + " side "
				+ "at the current burn; the swarm fights on none.");
		main.addTableHeaderTooltip(strengthCol + 8, "Days until " + side + " side wins the world at the "
				+ "line's current rate: the last layer taken, or the landing pushed off.");
		main.addTableHeaderTooltip(strengthCol + 9, "Whose warships hold the space over the world.");

		Fronts result = new Fronts();
		result.table = table;
		result.threatView = threatView;
		result.tierSelector = tierSelector;
		result.rows = rows;
		int rowIndex = 0;
		int totalOurs = 0;
		for (FrontRow r : rows) {
			final ThreatGroundFronts.GroundFront f = r.front;
			final MarketAPI m = r.market;
			boolean threatFront = ThreatGroundFronts.isThreatOwned(f);
			// did this side land here, or is it this side's world under a landing
			boolean weLanded = threatView == threatFront;
			// both sides as they fight now, cover included: the world's figure carries
			// its dug-in cover, so the landing's must too (the user, Volturn 2026-10-24:
			// 801 <- 441 read as a rout while the marines' push met 661)
			float eff = ThreatGroundFronts.effectiveStrength(f) * ThreatGroundFronts.coverMult(f);
			float defender = ThreatGroundFronts.defenderStrength(m);
			float ours = weLanded ? eff : defender;
			float theirs = weLanded ? defender : eff;
			totalOurs += Math.round(ours);
			List<Object> cells = new ArrayList<Object>();
			int i = 0;
			// the world in its owner's colour: on the Threat tab that is whose
			// world the swarm stands on, on a faction tab a hive reads Threat
			FactionAPI worldOwner = m.getFaction();
			cell(cells, Alignment.LMID, m.isPlayerOwned() ? Misc.getBasePlayerColor()
					: worldOwner != null ? worldOwner.getBaseUIColor() : bright,
					main.shortenString(m.getName(), w[i++] - 10f));
			// Force: ours first, the arrow from whoever is attacking (the user, Sindria
			// 2026-10-24: "sindria are pushing 402 to 198 yet the arrow points at sindria" -
			// it had pointed from the landing), each figure in its side's colour
			boolean landingAttacks = ThreatGroundFronts.STANCE_PUSH.equals(f.stance);
			boolean worldAttacks = ThreatGroundFronts.defendersPush(f, m);
			boolean oursAttack = weLanded ? landingAttacks : worldAttacks;
			boolean theirsAttack = weLanded ? worldAttacks : landingAttacks;
			String arrow = oursAttack && theirsAttack ? " <-> " : oursAttack ? " -> " : theirsAttack ? " <- " : " - ";
			String landerId = ThreatGroundFronts.ownerOf(f);
			FactionAPI lander = Global.getSector().getFaction(landerId);
			Color landerColour = Factions.PLAYER.equals(landerId) ? Misc.getBasePlayerColor()
					: lander != null ? lander.getBaseUIColor() : text;
			Color worldColour = m.isPlayerOwned() ? Misc.getBasePlayerColor()
					: worldOwner != null ? worldOwner.getBaseUIColor() : text;
			String oursText = Misc.getWithDGS(Math.round(ours));
			String theirsText = Misc.getWithDGS(Math.round(theirs));
			LabelAPI force = main.createLabel(oursText + arrow + theirsText, text);
			force.setHighlight(oursText, theirsText);
			force.setHighlightColors(weLanded ? landerColour : worldColour, weLanded ? worldColour : landerColour);
			cells.add(Alignment.MID);
			cells.add(text);
			cells.add(force); i++;
			String line = ThreatGroundFronts.stateWord(f, m, weLanded); // THE STATE WORD (2026-10-28), from this side
			cell(cells, Alignment.MID, lineColor(line, pos, neg, h, text), line); i++;
			// Held from this side: what our landing has taken, or what still stands on our world
			String held = weLanded ? heldCell(f, m)
					: Math.max(0, m.getSize() - f.strataHeld) + "/" + m.getSize();
			cell(cells, Alignment.MID, h, held); i++;
			// this side's stance, losses and armaments; the side-based readouts
			// (marine.../swarm...) pick the landing's or the garrison's as the row needs
			cell(cells, Alignment.MID, text, shortStance(threatView
					? ThreatGroundFronts.swarmStanceLabel(f, m) : ThreatGroundFronts.marineStanceLabel(f, m))); i++;
			boolean marinesDry = threatFront ? ThreatGroundFronts.defendersDry(m) : ThreatGroundFronts.isDry(f);
			boolean oursDry = threatView ? (threatFront && ThreatGroundFronts.isDry(f)) : marinesDry;
			float oursLost = threatView ? ThreatGroundFronts.swarmLossPer30Days(f, m)
					: ThreatGroundFronts.marineLossPer30Days(f, m);
			cell(cells, Alignment.MID, oursDry ? neg : text, ThreatGroundFronts.perDay(oursLost) + " / day"); i++;
			// ETA (Objective until 2026-10-31): days until this side takes the next layer;
			// a dash while the line stands or moves against it
			float change = ThreatGroundFronts.daysToLineChange(f, m);
			boolean ourWay = (ThreatGroundFronts.lineRate(f, m) > 0f) == weLanded;
			if (change < 0f || !ourWay) {
				cell(cells, Alignment.MID, gray, "-");
			} else {
				cell(cells, Alignment.MID, pos, (int) Math.ceil(change) + " d");
			}
			i++;
			// Objective (Target at birth, renamed the same day): the industry the next district decides - the swarm's next
			// claim (easiest to consume, a rebuild resumed) in the Threat's colour while the line moves
			// its way, the world's next reclaim (military first, soonest lost) in the world's while it
			// moves the world's; standing, the swarm's if one waits; "-" off an occupation
			// THIS side's, like every column (the user, Epiphany on the Threat tab 2026-10-31, the swarm at
			// 0/4 reading the world's "Extermination": "they hold 0 districts and are fighting an
			// overwhelming force they cant exterminate shit"): our landing's next claim, or our world's
			// next reclaim; with no structure decided the layer itself, "Extermination" for the side
			// holding every layer
			{
				String obj = weLanded ? landingObjective(f, m, ThreatOccupation.nextClaim(m, f))
						: worldObjective(f, m, ThreatOccupation.nextRelease(f, m));
				cell(cells, Alignment.MID, weLanded ? landerColour : worldColour, main.shortenString(obj, w[i] - 10f));
				i++;
			}
			// armaments read as what they buy: days of fighting left. The swarm
			// fights on none, so the Threat tab's column is empty; a faction's is
			// its landing's own, or its colony stockpile's against a Threat landing
			if (threatView) {
				cell(cells, Alignment.MID, gray, "-"); i++;
			} else if (threatFront) {
				boolean on = ThreatIncConfig.defenderArmsEnabled() && !ThreatGroundFronts.isHiveTarget(m);
				float days = on ? ThreatGroundFronts.defenderArmsDaysLeft(m, f) : 0f;
				cell(cells, Alignment.MID, !on ? gray : marinesDry ? neg : text,
						!on ? "-" : marinesDry ? "dry" : days >= 999f ? "999+ d" : (int) days + " d"); i++;
			} else {
				float days = ThreatGroundFronts.supplyDaysLeft(f);
				cell(cells, Alignment.MID,
						!ThreatGroundFronts.needsArms(f) ? gray : marinesDry ? neg : text,
						!ThreatGroundFronts.needsArms(f) ? "-" : marinesDry ? "dry" : (int) days + " d"); i++;
			}
			// Victory (the user, 2026-10-24): days until this side wins the world - the
			// landing takes the last layer, or the world pushes the landing off
			float victory = ThreatGroundFronts.victoryDays(f, m, weLanded);
			cell(cells, Alignment.MID, victory < 0f ? gray : pos,
					victory < 0f ? "-" : "~" + (int) Math.ceil(victory) + " d"); i++;
			String spaceId = ThreatGroundFronts.spaceHolder(m);
			FactionAPI space = spaceId != null ? Global.getSector().getFaction(spaceId) : null;
			boolean spacePlayer = Factions.PLAYER.equals(spaceId);
			cell(cells, Alignment.MID,
					spaceId == null ? gray : spacePlayer ? Misc.getBasePlayerColor()
							: space != null ? space.getBaseUIColor() : text,
					spaceId == null ? "Empty" : main.shortenString(spacePlayer ? "You"
							: ThreatWarState.displayName(spaceId), w[i] - 10f)); i++;
			if (!threatView) { cell(cells, Alignment.MID, gray, ""); i++; } // Actions: the button floats here
			main.addRow(cells.toArray());
			TooltipCreator facts = new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 460f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					addFrontTooltip(tooltip, f, m);
				}
			};
			main.addTooltipToAddedRow(facts, TooltipLocation.LEFT, false);
			main.setIdForAddedRow(ThreatFactionView.ROW_MARKET + m.getId());
			rowIndex++;
		}

		List<Object> totals = new ArrayList<Object>();
		for (int i = 0; i < w.length; i++) {
			if (i == 0) cell(totals, Alignment.LMID, h, "Total - " + rows.size()
					+ (rows.size() == 1 ? " front" : " fronts"));
			else if (i == strengthCol) cell(totals, Alignment.MID, h, Misc.getWithDGS(totalOurs));
			else cell(totals, Alignment.MID, gray, "");
		}
		main.addRow(totals.toArray());
		rowIndex++;
		result.rowCount = rowIndex;
		main.addTable("None", -1, 0f);

		LabelAPI key = main.addPara("holding   grinding   foothold", gray, 4f);
		key.setHighlight("holding", "grinding", "foothold");
		key.setHighlightColors(pos, h, neg);
		main.addSpacer(opad);

		return result;
	}

	/** A stance word short enough for the two-sided Stance cell: "regrouping" -> "regroup". */
	protected static String shortStance(String stance) {
		return "regrouping".equals(stance) ? "regroup" : stance;
	}

	/** The arithmetic behind a front's row, one line per fact, in the Marines / Swarm words (2026-10-17). */
	protected static void addFrontTooltip(TooltipMakerAPI tooltip, ThreatGroundFronts.GroundFront f,
			MarketAPI m) {
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		String ownerId = ThreatGroundFronts.ownerOf(f);
		FactionAPI owner = Global.getSector().getFaction(ownerId);
		Color ownerColor = f.isPlayerOwned() ? Misc.getBasePlayerColor()
				: owner != null ? owner.getBaseUIColor() : gray;
		String who = f.isPlayerOwned() ? "Your" : ThreatWarState.displayName(ownerId);
		String system = m.getStarSystem() != null ? ", " + m.getStarSystem().getNameWithNoType() : "";
		boolean pushing = ThreatGroundFronts.STANCE_PUSH.equals(f.stance);
		boolean regrouping = ThreatGroundFronts.STANCE_CONSOLIDATE.equals(f.stance);
		boolean dry = ThreatGroundFronts.isDry(f);
		// the sides by name, whichever landed (user, 2026-10-17: "just say
		// marines and the swarm")
		boolean threatFront = ThreatGroundFronts.isThreatOwned(f);
		String Army = threatFront ? "Swarm" : "Marines";
		String World = m.getName(); // the world by name (the user's sketch, 2026-10-24: "Sindria 257 = ...")

		// THE MATH (the user, 2026-10-24: "the exact numbers/breakdown/formula for each
		// force number, and every other number there - no fluff or flavour text"; then
		// the shape: one lay sentence, one line a side with its Force figure's breakdown,
		// "cover" the one word for dug in / over-extended, "Needs x to <next tier>")
		tooltip.addPara(who + " front on " + m.getName() + system + ".", ownerColor, 0f);

		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		float dryMult = ThreatIncConfig.frontDryEffectivenessMult();
		boolean hive = ThreatGroundFronts.isHiveTarget(m);
		String layer = ThreatGroundFronts.layerName(m);
		String layers = ThreatGroundFronts.layerPlural(m);
		boolean worldPushes = ThreatGroundFronts.defendersPush(f, m);
		float rate = ThreatGroundFronts.lineRate(f, m);
		float band = ThreatIncConfig.frontGrindBand();
		float overrun = ThreatIncConfig.frontOverrunRate();
		boolean moves = ThreatGroundFronts.lineMoves(rate);

		// the lay sentence, led by the day (the user's sketch, 2026-10-24: "Day 277 - Sindrian forces hold the line")
		float change = ThreatGroundFronts.daysToLineChange(f, m);
		String day = "Day %s - ";
		String dayN = "" + (int) ThreatGroundFronts.daysSinceLanding(f);
		if (!moves) {
			tooltip.addPara(day + World + " holds the line.", 4f, text, h, dayN);
		} else {
			String gainer = rate > 0f ? Army : World;
			String verb = Math.abs(rate) >= overrun ? " overrunning" : " gaining ground";
			if (change < 0f) {
				tooltip.addPara(day + gainer + verb + "; the last " + layer + " is protected.", 4f, text, h, dayN);
			} else {
				tooltip.addPara(day + gainer + verb + ": next " + layer + " in %s d.", 4f, text, h, dayN,
						"" + (int) Math.ceil(change));
			}
		}
		// control of the contested layer: pushProgress runs -base..+base, the landing takes
		// the layer at +base and loses one at -base, so 0 is half each
		float baseDays = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
		int shareA = Math.round(Math.max(0f, Math.min(1f, (f.pushProgress + baseDays) / (2f * baseDays))) * 100f);
		int held = Math.max(0, Math.min(m.getSize(), f.strataHeld));
		// the holder is the faction, not the world (the user, Volturn 2026-10-24: "volturn doesnt hold 50%
		// sindria does. use faction name there"), each side in its colour
		String holder = ThreatWarState.displayName(m.getFactionId());
		Color holderC = factionColor(m.getFactionId());
		Color armyC = threatFront ? ThreatNotice.threatColor() : factionColor(ownerId);
		LabelAPI control = tooltip.addPara("Control of the " + layer + ": " + holder + " %s, " + Army + " %s; " + Army
				+ (threatFront ? " holds" : " hold") + " %s of %s " + layers + ".", 4f, text, h, (100 - shareA) + "%", shareA + "%", "" + held, "" + m.getSize());
		control.setHighlight(holder, (100 - shareA) + "%", Army, shareA + "%", Army, "" + held, "" + m.getSize());
		control.setHighlightColors(holderC, h, armyC, h, armyC, h, h);

		// the landing: its Force figure, every factor named
		float eff = ThreatGroundFronts.effectiveStrength(f);
		float coverA = ThreatGroundFronts.coverMult(f);
		float forceA = eff * coverA;
		float coverFactorA = ThreatGroundFronts.entrenchMult(f) * coverA;
		float xpA = ThreatMarineXP.frontEffectMult(f);
		float skills = f.isPlayerOwned() ? ThreatMarineXP.playerGroundSkillMult() : 1f;
		String extraA = (Math.abs(xpA - 1f) >= 0.005f ? " x " + f2(xpA) + " veteran" : "")
				+ (dry ? " x " + f1(dryMult) + " dry" : "")
				+ (Math.abs(skills - 1f) >= 0.005f ? " x " + f2(skills) + " skills" : "");
		String coverA_ = ThreatGroundFronts.coverWord(f.entrenchDays)
				+ " (" + (int) Math.max(-days, Math.min(days, f.entrenchDays)) + "/" + (int) days + " d)";
		float sA = moves ? rate : 0f; // the line from the landing's side
		String needA = needLine(f, m, true, sA, band, overrun, pushing, worldPushes, "troops");
		tooltip.addPara(Army + " %s = %s troops" + extraA + " x %s " + coverA_ + "." + needA, 4f, text, h,
				n(forceA), n(f.marines), f2(coverFactorA));

		// the world: its Force figure
		float defender = ThreatGroundFronts.defenderStrength(m);
		float push = ThreatGroundFronts.defenderPressure(f, m);
		float coverD = hive ? 1f : ThreatGroundFronts.defenderCoverMult(f);
		float d;
		float armed = 0f;
		float xpLossD = 1f;
		String needD = needLine(f, m, false, -sA, band, overrun, pushing, worldPushes, hive ? "troops" : "marines");
		if (hive) {
			// the strata are the troops (ThreatStrata, 2026-10-24): the defence is the strata standing, multiplied by its structures
			float strata = ThreatStrata.standing(m);
			float pace = ThreatGroundFronts.hiveCounterAttackPace(m);
			d = defender;
			// its attacks are paced by what it holds and whether its Core eats (hiveCounterAttackPace), each factor named
			List<String> wa = new ArrayList<String>();
			wa.add(n(defender));
			wa.add(n(strata));
			String paceS = "";
			if (ThreatColonyManager.coreState(m).starving()) { paceS += " x %s starving"; wa.add(f2(0.5f)); }
			if (held > 0) { paceS += " x %s strata standing"; wa.add((m.getSize() - held) + "/" + m.getSize()); }
			if (pace < 0.995f && paceS.isEmpty()) { paceS += " x %s"; wa.add(f2(pace)); }
			if (!paceS.isEmpty()) { paceS = "; attacks at" + paceS + " = %s"; wa.add(n(push)); }
			tooltip.addPara(World + " %s = %s strata" + paceS + "." + needD, 4f, text, h, wa.toArray(new String[0]));
		} else {
			float garrisonAll = ThreatGroundFronts.colonyGarrison(m);
			armed = ThreatReserves.armedMarines(m);
			float garrison = ThreatGroundFronts.mannedGarrison(garrisonAll, armed); // manned defences (2026-10-28)
			String mannedWord = garrison < garrisonAll - 0.5f ? " of " + n(garrisonAll) : "";
			float crew = ThreatGroundFronts.manningCrew(garrisonAll, armed); // crew first, the rest fight (2026-10-29)
			float spare = ThreatGroundFronts.spareMarines(garrisonAll, armed);
			float stock = ThreatReserves.stock(m.getId(), Commodities.MARINES);
			float overD = ThreatGroundFronts.defenderOverExtendMult(f);
			float xpD = ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(m));
			xpLossD = ThreatMarineXP.lossMult(ThreatMarineXP.colonyLevel(m));
			boolean dryD = ThreatGroundFronts.defendersDry(m);
			String extraD = (Math.abs(xpD - 1f) >= 0.005f ? " x " + f2(xpD) + " veteran" : "")
					+ (dryD ? " x " + f1(dryMult) + " dry" : "");
			d = (garrison + ThreatGroundFronts.colonyMarineDefense(m, 1f)) * overD;
			String coverD_ = ThreatGroundFronts.coverWord(f.defenderEntrenchDays)
					+ " (" + (int) Math.max(-days, Math.min(days, f.defenderEntrenchDays)) + "/" + (int) days + " d)";
			tooltip.addPara(World + " %s = (%s" + mannedWord + " ground defence manned by %s + %s marines x %s armed" + extraD + ") x %s " + coverD_ + "."
					+ needD, 4f, text, h, n(defender), n(garrison), n(crew), n(spare), f2(ThreatIncConfig.reserveDefenseMult()),
					f2(coverD * overD));
			// the marines not deployed: reserves arming, and any staging for a siege (the user, 2026-10-24: "don't call
			// them stock when they aren't deployed. Call them reserves. And any being used for a siege are staging for x")
			float stagingM = ThreatReserves.stagingMarines(m);
			float reserve = Math.max(0f, stock - armed - stagingM);
			if (reserve > 0.5f || stagingM > 0.5f) {
				StarSystemAPI forS = stagingM > 0.5f ? ThreatConvoys.stagingHive(m) : null;
				List<String> ra = new ArrayList<String>();
				String line = World + ":";
				if (reserve > 0.5f) {
					// the ramp's pace is the whole stock over marineArmingDays (ThreatReserves.tickMarineArming),
					// so a delivery onto a big stock is posted in days - say so (the user, 2026-10-28: "should
					// say something about x deploying")
					float perDay = stock / Math.max(0.01f, ThreatIncConfig.marineArmingDays());
					line += " %s marines in reserve, arming at %s a day (%s d)";
					ra.add(n(reserve));
					ra.add(perDay >= 10f ? n(perDay) : f1(perDay));
					ra.add("" + (int) Math.ceil(reserve / Math.max(0.01f, perDay)));
				}
				if (stagingM > 0.5f) {
					line += (reserve > 0.5f ? "; " : " ") + "%s marines staging for %s";
					ra.add(n(stagingM));
					ra.add(forS != null ? forS.getName() : "a siege");
				}
				tooltip.addPara(line + ".", 4f, text, h, ra.toArray(new String[0]));
			}
		}

		// Objective and Victory
		float base = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
		boolean toA = rate > 0f;
		String side = toA ? Army.toLowerCase() : World;
		if (change < 0f) {
			tooltip.addPara("ETA: none.", text, 4f);
		} else {
			tooltip.addPara("ETA: next " + layer + " to " + side + " in %s d.", 4f, text, h, "" + (int) Math.ceil(change));
		}
		// Target: what the next district decides, both ways (the user, 2026-10-31)
		Industry tClaim = ThreatOccupation.nextClaim(m, f);
		Industry tRelease = ThreatOccupation.nextRelease(f, m);
		String army = Army.toLowerCase();
		if (tClaim != null) {
			tooltip.addPara("Objective of the " + army + ": %s, %s d to consume" + (ThreatOccupation.rebuilding(tClaim) ? " (resumed from the rebuild)" : "") + ".",
					4f, text, h, tClaim.getCurrentName(), "" + (int) Math.ceil(ThreatOccupation.consumeDaysLeftOf(tClaim)));
		} else {
			tooltip.addPara("Objective of the " + army + ": %s.", 4f, text, h, landingObjective(f, m, null));
		}
		if (tRelease != null) {
			tooltip.addPara("Objective of " + World + ": reclaim %s, consumed in %s d.", 4f, text, h,
					tRelease.getCurrentName(), "" + (int) Math.ceil(ThreatOccupation.consumeDaysLeftOf(tRelease)));
		} else {
			tooltip.addPara("Objective of " + World + ": %s.", 4f, text, h, worldObjective(f, m, null));
		}
		float victory = ThreatGroundFronts.victoryDays(f, m, toA);
		if (victory < 0f) {
			tooltip.addPara("Victory: none" + (change < 0f ? "." : " - the last " + layer + " is protected."), text, 4f);
		} else {
			int after = toA ? Math.max(0, m.getSize() - f.strataHeld - 1) : Math.max(0, f.strataHeld);
			tooltip.addPara("Victory: " + side + " in %s d (%s more " + layers + " after this one).", 4f, text, h,
					"" + (int) Math.ceil(victory), "" + after);
		}

		// Losses: THE ATTRITION RULE (the user, 2026-10-24), the same line for both sides
		float per = Math.max(1f, ThreatIncConfig.attritionTroopsPer());
		float xpL = ThreatMarineXP.frontLossMult(f);
		float orbit = ThreatGroundFronts.reliefBombardPer30Days(f, m);
		String vetA = Math.abs(xpL - 1f) >= 0.005f ? " x " + f2(xpL) + " veteran" : "";
		List<String> lossArgs = new ArrayList<String>();
		lossArgs.add(ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(f, m)));
		lossArgs.add(f2(f.marines / per));
		lossArgs.add(n(defender));
		lossArgs.add(n(forceA));
		lossArgs.add(f2(coverFactorA));
		if (orbit > 0f) lossArgs.add(ThreatGroundFronts.perDay(orbit));
		tooltip.addPara(Army + " attrition %s/d = %s size x (" + World + " %s / " + Army + " %s) x 1/%s cover" + vetA
				+ (orbit > 0f ? " + %s/d from orbit" : "") + ".", 4f, text, h, lossArgs.toArray(new String[0]));
		float feed = ThreatGroundFronts.infestGrowthPerDay(m, f);
		if (feed > 0f) {
			tooltip.addPara(Army + " grows %s/d = %s a month x " + World + " size %s x %s held / 30, on what it eats of the world.", 4f, text, h,
					ThreatStrata.fmt(feed), ThreatStrata.fmt(ThreatIncConfig.coreStrataPerSizeMonth()), "" + m.getSize(),
					f.strataHeld + "/" + m.getSize());
		}
		float worldTroops = hive ? ThreatStrata.standing(m) : armed;
		if (worldTroops > 0f) {
			float coverFactorD = hive ? 1f : ThreatGroundFronts.defenderCoverMult(f) * ThreatGroundFronts.defenderOverExtendMult(f);
			String vetD = !hive && Math.abs(xpLossD - 1f) >= 0.005f ? " x " + f2(xpLossD) + " veteran" : "";
			tooltip.addPara(World + " attrition %s/d = %s size x (" + Army + " %s / " + World
					+ " %s) x 1/%s cover" + vetD + ".", 4f, text, h,
					ThreatGroundFronts.perDay(ThreatGroundFronts.worldLossPerDay(m, f, worldTroops) * 30f), f2(worldTroops / per),
					n(forceA), n(defender), f2(coverFactorD));
		}

		// Arms
		float perMarine = ThreatIncConfig.frontArmamentsPerMarinePer30Days();
		if (!ThreatGroundFronts.needsArms(f)) {
			// nothing to show (the user, 2026-10-24: "then don't show the line")
		} else if (dry) {
			tooltip.addPara(Army + " arms %s: strength x %s dry.", 4f, text, neg, "0", f1(dryMult));
		} else {
			float burn = ThreatGroundFronts.dailyUpkeep(f) * (pushing ? ThreatIncConfig.frontPushUpkeepMult() : 1f);
			tooltip.addPara(Army + " arms %s d = %s stock / (%s troops x %s each per 30 days" + (pushing ? " x %s attacking" : "") + " = %s/d).", 4f, text, h,
					pushing ? new String[] { "" + (int) ThreatGroundFronts.supplyDaysLeft(f), n(f.armaments), n(f.marines), f2(perMarine),
							f1(ThreatIncConfig.frontPushUpkeepMult()), f1(burn) }
							: new String[] { "" + (int) ThreatGroundFronts.supplyDaysLeft(f), n(f.armaments), n(f.marines), f2(perMarine), f1(burn) });
		}
		if (!hive && ThreatIncConfig.defenderArmsEnabled()) {
			if (ThreatGroundFronts.defendersDry(m)) {
				tooltip.addPara(World + " arms %s: strength x %s dry.", 4f, text, neg, "0", f1(dryMult));
			} else if (armed > 0f) {
				float defBurn = ThreatGroundFronts.defenderDailyUpkeep(m, f);
				float defDays = ThreatGroundFronts.defenderArmsDaysLeft(m, f);
				boolean defPush = ThreatGroundFronts.defendersPush(f, m);
				tooltip.addPara(World + " arms %s d = %s stock / (%s armed marines x %s each per 30 days" + (defPush ? " x %s attacking" : "")
						+ " = %s/d).", 4f, text, h, defPush
								? new String[] { defDays >= 999f ? "999+" : "" + (int) defDays, n(ThreatReserves.stock(m.getId(), Commodities.HAND_WEAPONS)),
										n(armed), f2(perMarine), f1(ThreatIncConfig.frontPushUpkeepMult()), f1(defBurn) }
								: new String[] { defDays >= 999f ? "999+" : "" + (int) defDays, n(ThreatReserves.stock(m.getId(), Commodities.HAND_WEAPONS)),
										n(armed), f2(perMarine), f1(defBurn) });
			}
		}

		// the rest: facts with a number each
		float finalIn = ThreatGroundFronts.daysToFinalPush(f);
		if (f.finalPush) {
			tooltip.addPara("Final push: arms 0, waited %s d.", 4f, text, neg, "" + (int) ThreatIncConfig.frontDryFinalPushDays());
		} else if (finalIn >= 0f) {
			tooltip.addPara("Final push in %s d without resupply.", 4f, text, h, "" + (int) Math.ceil(finalIn));
		}
		ThreatGroundFronts.Theatre theatre = ThreatGroundFronts.Theatre.of(m);
		if (theatre == ThreatGroundFronts.HIVE) {
			int starved = f.sizeAtLanding > 0 ? Math.max(0, f.sizeAtLanding - m.getSize()) : 0;
			if (starved > 0) tooltip.addPara("%s strata starved since the landing.", 4f, text, h, "" + starved);
		}
		if (regrouping) {
			tooltip.addPara(Army + " regrouping %s d, then " + (ThreatGroundFronts.doctrinePushes(f, m) ? "push" : "hold") + " by doctrine.",
					4f, text, h, "" + (int) Math.ceil(f.consolidateDaysLeft));
		}

		if (f.isPlayerOwned()) {
			float[] wants = ThreatConvoys.frontWants(f, m);
			if (wants[0] > 0f || wants[1] > 0f) {
				tooltip.addPara("Wants %s marines and %s heavy armaments.", 4f, text, h,
						Misc.getWithDGS((int) wants[0]), Misc.getWithDGS((int) wants[1]));
			} else {
				tooltip.addPara("At strength and stocked for %s days.", 4f, text, h,
						"" + (int) ThreatIncConfig.frontResupplyDays());
			}
		}
		tooltip.addPara("Click for the colony screen.", gray, 6f);
	}

	/**
	 * The fronts table's Actions column (2026-10-30 evening, the user: "one button for push and
	 * hold not two, and it toggles between them ... move the push/hold button to actions column
	 * right most side"): one button a row, floated over the entry's last cell, added last and
	 * anchored to the table (platform traps 1 and 2). The player's own landing: the order it is not
	 * under - Push while it holds or regroups, Hold while it pushes (the label is the action, not the
	 * state: the user, 21:00; BUTTON_PUSH / BUTTON_ENTRENCH, no strength gate). The player's own
	 * colony under a Threat landing: the defenders' order the same way (BUTTON_DEFEND_PUSH /
	 * BUTTON_DEFEND_ENTRENCH). An ally's sieged world: Defend at the Fleet lever (BUTTON_AID_DEFEND).
	 * Supply, Support, Defend and Pull out went: the Domain table's row for the attacked world holds
	 * Deploy, Transfer and Recall (ThreatFactionView). The orders route through
	 * ThreatFactionView.executeOrder, one code path.
	 */
	public static void addFrontButtons(ThreatIncursionIntel intel, TooltipMakerAPI main, Fronts fronts) {
		if (fronts == null || fronts.table == null || fronts.threatView) return;
		int[] fl = intel.frontLevers();
		int n = fronts.rows.size();
		for (int i = 0; i < n; i++) {
			FrontRow r = fronts.rows.get(i);
			// the row's distance up from the table's foot, centred in it; the button sits in the Actions cell
			float up = (fronts.rowCount - i) * ROW_H - (ROW_H - 20f) / 2f;
			if (playerCanReinforce(r)) {
				if (r.market.isPlayerOwned()) {
					// one button, its label the ORDER it gives - the stance the defenders are not in (the user,
					// 2026-10-30 evening: "one button for push and hold not two, and it toggles between them";
					// 21:00: "the label should indicate the action result not the current state")
					boolean pushing = ThreatGroundFronts.defendersPush(r.front, r.market);
					String dkey = Factions.PLAYER + ":" + r.market.getId();
					com.fs.starfarer.api.ui.ButtonAPI stance = intel.addGenericButton(main, FRONT_STANCE_W,
							pushing ? "Hold" : "Push",
							(pushing ? ThreatFactionView.BUTTON_DEFEND_ENTRENCH : ThreatFactionView.BUTTON_DEFEND_PUSH) + dkey);
					stance.getPosition().belowRight(fronts.table, -up).setXAlignOffset(-6f);
					if (pushing) {
						String why = ThreatGroundFronts.defenderEntrenchBlockReason(r.front, r.market);
						ThreatFactionView.disableWith(main, stance, why == null, why,
								"Hold: no push; the cover rises to x"
								+ String.format("%.1f", ThreatIncConfig.frontEntrenchDefenseBonus()) + " over "
								+ (int) ThreatIncConfig.frontEntrenchDays() + " days, lost with a "
								+ ThreatGroundFronts.layerName(r.market) + ".");
					} else {
						String why = ThreatGroundFronts.defenderPushBlockReason(r.front, r.market);
						ThreatFactionView.disableWith(main, stance, why == null, why,
								ThreatGroundFronts.defenderPushQuote(r.front, r.market));
					}
					continue;
				}
				// DEFEND an ally's sieged world (2026-10-29 evening): the player's task force over it at the
				// Fleet lever - one fleet / half / all of the hulls free at staging (ThreatAid.quoteDefend ->
				// forcePoints), as a Transfer to an own world
				int orbitTier = fl.length > 2 ? fl[2] : 0;
				com.fs.starfarer.api.ui.ButtonAPI defend = intel.addGenericButton(main,
						FRONT_DEFEND_W, "Defend",
						ThreatFactionView.BUTTON_AID_DEFEND + r.market.getFactionId() + ":" + r.market.getId());
				defend.getPosition().belowRight(fronts.table, -up).setXAlignOffset(-6f);
				// for the Days lever's term, the hold priced on it (2026-10-31)
				float term = ThreatIncConfig.guardTermDays(fl.length > 3 ? fl[3] : 1);
				ThreatAid.Quote d = ThreatAid.quoteDefend(r.market, orbitTier, term);
				ThreatFactionView.disableWith(main, defend, d.ok(), d.ok() ? null : ThreatNotice.text(d.reason),
						d.ok() ? "A task force of " + ThreatFactionView.forceText(d) + " from " + d.source.getName()
								+ " holds this orbit for " + (int) term
								+ " days. Paid from that colony's reserve; earns standing on arrival."
								+ (d.provisions != null ? "\nTakes " + Misc.getWithDGS((int) Math.ceil(d.provisions[0]))
										+ " fuel and " + Misc.getWithDGS((int) Math.ceil(d.provisions[1])) + " supplies." : "")
								+ (d.sized != null ? "\n" + d.sized : "") : null);
				continue;
			}
			if (!playerCanOrder(r)) continue;
			// the player's own landing: one button, its label the ORDER - Hold while it pushes, Push while
			// it holds or regroups (the label is the action, not the state: the user, 2026-10-30 21:00);
			// no strength gate (the user, that evening: "should use same mechanics as colony defence i can
			// push and hold whenever i want"). Supply, Support, Defend and Pull out went the same evening:
			// the Domain table's row for the world holds Deploy, Transfer and Recall (ThreatFactionView)
			String key = Factions.PLAYER + ":" + r.market.getId();
			boolean pushing = ThreatGroundFronts.STANCE_PUSH.equals(r.front.stance);
			com.fs.starfarer.api.ui.ButtonAPI stance = intel.addGenericButton(main,
					FRONT_STANCE_W, pushing ? "Hold" : "Push",
					(pushing ? ThreatFactionView.BUTTON_ENTRENCH : ThreatFactionView.BUTTON_PUSH) + key);
			stance.getPosition().belowRight(fronts.table, -up).setXAlignOffset(-6f);
			if (pushing) {
				String why = ThreatGroundFronts.entrenchBlockReason(r.front);
				ThreatFactionView.disableWith(main, stance, why == null, why,
						"Hold: break off the assault; losses fall to ~"
						+ ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(
								r.front, r.market, false)) + " troops a day; it defends at up to x"
						+ String.format("%.1f", ThreatIncConfig.frontEntrenchDefenseBonus())
						+ " from cover once dug in.");
			} else {
				String why = ThreatGroundFronts.pushBlockReason(r.front, r.market);
				ThreatFactionView.disableWith(main, stance, why == null, why,
						"Push: the assault on " + ThreatGroundFronts.layerName(r.market) + " "
						+ (r.front.strataHeld + 1) + ": ~"
						+ (int) Math.ceil(ThreatGroundFronts.pushDaysEstimate(r.front, r.market))
						+ " days and ~" + Misc.getWithDGS(ThreatGroundFronts.pushCasualtyEstimate(
								r.front, r.market)) + " troops at this pace; armaments burn x"
						+ String.format("%.0f", ThreatIncConfig.frontPushUpkeepMult()) + ".");
			}
		}
		if (fronts.tierSelector) ThreatFactionView.addFrontLevers(intel, main, fronts.table);
	}

	// ---- shared drawing helpers ----

	protected static void glColor(Color c, float alpha) {
		GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f,
				c.getAlpha() / 255f * alpha);
	}

	protected static void glBegin() {
		GL11.glPushMatrix();
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
	}

	protected static void glEnd() {
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glPopMatrix();
	}

	protected static void quad(float x, float y, float w, float h, Color c, float alpha) {
		glColor(c, alpha);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(x, y);
		GL11.glVertex2f(x + w, y);
		GL11.glVertex2f(x + w, y + h);
		GL11.glVertex2f(x, y + h);
		GL11.glEnd();
	}

	protected static void outline(float x, float y, float w, float h, Color c, float alpha) {
		glColor(c, alpha);
		GL11.glLineWidth(1f);
		GL11.glBegin(GL11.GL_LINE_LOOP);
		GL11.glVertex2f(x + 0.5f, y + 0.5f);
		GL11.glVertex2f(x + w - 0.5f, y + 0.5f);
		GL11.glVertex2f(x + w - 0.5f, y + h - 0.5f);
		GL11.glVertex2f(x + 0.5f, y + h - 0.5f);
		GL11.glEnd();
	}

	protected static void hline(float x1, float x2, float y, Color c, float alpha) {
		glColor(c, alpha);
		GL11.glLineWidth(1f);
		GL11.glBegin(GL11.GL_LINES);
		GL11.glVertex2f(x1, y + 0.5f);
		GL11.glVertex2f(x2, y + 0.5f);
		GL11.glEnd();
	}

	protected static void vline(float x, float y1, float y2, Color c, float alpha) {
		glColor(c, alpha);
		GL11.glLineWidth(1f);
		GL11.glBegin(GL11.GL_LINES);
		GL11.glVertex2f(x + 0.5f, y1);
		GL11.glVertex2f(x + 0.5f, y2);
		GL11.glEnd();
	}

	/** A vitality-style bar: dark trough, colored fill, threshold ticks, outline. */
	protected static void bar(float x, float y, float w, float h, float frac, Color fill,
			float[] markers, float alpha) {
		Color dark = Global.getSector().getFaction(Factions.THREAT).getDarkUIColor();
		Color base = Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();
		quad(x, y, w, h, dark, 0.55f * alpha);
		if (frac > 0f) quad(x, y, w * Math.min(1f, frac), h, fill, 0.85f * alpha);
		if (markers != null) {
			for (float m : markers) {
				if (m <= 0f || m >= 1f) continue;
				vline(x + w * m, y - 2f, y + h + 2f, base, 0.9f * alpha);
			}
		}
		outline(x, y, w, h, base, 0.7f * alpha);
	}

	/** Small triangle: up (growing), down (declining) or a dash (stalled). */
	protected static void trend(float cx, float cy, int dir, float alpha) {
		if (dir > 0) {
			glColor(ThreatNotice.goodColor(), alpha);
			GL11.glBegin(GL11.GL_TRIANGLES);
			GL11.glVertex2f(cx - 4f, cy - 3f);
			GL11.glVertex2f(cx + 4f, cy - 3f);
			GL11.glVertex2f(cx, cy + 4f);
			GL11.glEnd();
		} else if (dir < 0) {
			glColor(Misc.getNegativeHighlightColor(), alpha);
			GL11.glBegin(GL11.GL_TRIANGLES);
			GL11.glVertex2f(cx - 4f, cy + 3f);
			GL11.glVertex2f(cx + 4f, cy + 3f);
			GL11.glVertex2f(cx, cy - 4f);
			GL11.glEnd();
		} else {
			quad(cx - 4f, cy - 1f, 8f, 2f, Misc.getGrayColor(), alpha);
		}
	}

	protected static SpriteAPI sprite(String path) {
		if (path == null) return null;
		try {
			SpriteAPI s = Global.getSettings().getSprite(path);
			if (s == null || s.getWidth() <= 0f) return null;
			return s;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Draws a sprite scaled into a square, bottom-left at x,y. */
	protected static void icon(String path, float x, float y, float size, Color tint, float alpha) {
		SpriteAPI s = sprite(path);
		if (s == null) return;
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		s.setSize(size, size);
		s.setColor(tint != null ? tint : Color.WHITE);
		s.setAlphaMult(alpha);
		s.render(x, y);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
	}

	/**
	 * A positioned text element inside a custom panel. Coordinates are from the
	 * panel's top-left. The string is shortened to the element width so it
	 * never wraps onto a second line.
	 */
	protected static TooltipMakerAPI text(CustomPanelAPI panel, float x, float y, float w,
			String str, Color color, Alignment align, boolean small) {
		TooltipMakerAPI tm = panel.createUIElement(w, 20f, false);
		tm.setTextWidthOverride(w);
		tm.setParaFont(BOARD_FONT);
		String shown = tm.computeStringWidth(str) > w - 6f ? tm.shortenString(str, w - 6f) : str;
		LabelAPI label = tm.addPara(shown, color, 0f);
		if (align != null) label.setAlignment(align);
		panel.addUIElement(tm).inTL(x, y);
		return tm;
	}

	/** As {@link #text} but with per-highlight colors. */
	protected static TooltipMakerAPI textHl(CustomPanelAPI panel, float x, float y, float w,
			String format, Color base, Color[] hlColors, String[] hl, Alignment align,
			boolean small) {
		TooltipMakerAPI tm = panel.createUIElement(w, 20f, false);
		tm.setTextWidthOverride(w);
		tm.setParaFont(BOARD_FONT);
		LabelAPI label = tm.addPara(format, 0f, base, base, hl);
		label.setHighlightColors(hlColors);
		if (align != null) label.setAlignment(align);
		panel.addUIElement(tm).inTL(x, y);
		return tm;
	}

	/** Y (screen, bottom-left origin) of a box `fromTop` below the panel's top edge. */
	protected static float topY(PositionAPI pos, float fromTop, float h) {
		return pos.getY() + pos.getHeight() - fromTop - h;
	}

	// ---- header ----

	protected static void addHeader(TooltipMakerAPI main, float width) {
		final int phase = IncursionManager.getPhase();
		final float barW = Math.min(300f, width * 0.28f);
		final float h = 58f;
		CustomPanelAPI panel = Global.getSettings().createCustom(width, h,
				new BaseCustomUIPanelPlugin() {
			PositionAPI pos;
			public void positionChanged(PositionAPI position) { pos = position; }
			public void render(float alphaMult) {
				if (pos == null) return;
				FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
				glBegin();
				icon(threat.getCrest(), pos.getX() + 2f, topY(pos, 5f, 36f), 36f, null, 0.9f * alphaMult);
				float bx = pos.getX() + pos.getWidth() - barW;
				float frac = phase >= 3 ? 1f : (phase == 2 ? 0.5f : 0.1f);
				bar(bx, topY(pos, 27f, 8f), barW, 8f, frac,
						phase >= 3 ? Misc.getNegativeHighlightColor() : threat.getBaseUIColor(),
						new float[] {0.5f}, alphaMult);
				glEnd();
			}
		});
		Color player = Misc.getBasePlayerColor();
		Color gray = Misc.getGrayColor();
		Color threat = Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();
		Color neg = Misc.getNegativeHighlightColor();

		TooltipMakerAPI title = panel.createUIElement(width * 0.5f, 30f, false);
		title.setTextWidthOverride(width * 0.5f);
		title.setParaOrbitronLarge();
		title.addPara("THE ABYSSAL WAR", player, 0f);
		panel.addUIElement(title).inTL(48f, 4f);

		int days = (int) ThreatIncData.daysSinceStart();
		text(panel, 48f, 29f, width * 0.68f, "Cycle " + Global.getSector().getClock().getCycle()
				+ " - day " + days + " of the war - only what you have found is listed",
				gray, null, true);

		float bx = width - barW;
		text(panel, bx, 6f, barW * 0.5f, "Awakened", phase >= 1 ? threat : gray, Alignment.LMID, true);
		text(panel, bx + barW * 0.4f, 6f, barW * 0.6f, "Core worlds in reach",
				phase >= 3 ? neg : gray, Alignment.RMID, true);
		String label = phase >= 3 ? "PHASE 3 - Core worlds in reach"
				: phase == 2 ? "PHASE 2 - Strike-capable" : "PHASE 1 - Entrenching";
		text(panel, bx, 36f, barW, label, phase >= 3 ? neg : threat, Alignment.MID, true);
		// escalation readout (ThreatAlarm): the swarm's answer to being hurt,
		// in the open - sits under the cycle line, left of the phase bar
		final float alarm = ThreatAlarm.alarm();
		if (ThreatAlarm.enabled()) {
			text(panel, 48f, 43f, width * 0.5f, ThreatAlarm.headerLabel(),
					alarm > 0f ? neg : gray, null, true);
		}

		main.addCustom(panel, 0f);
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 420f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("Phases are capability, not calendar. Phase 2: some hive can "
						+ "stage an expedition (strike-sized, its Core standing). "
						+ "Phase 3: some hive can field a full armada and a core world sits "
						+ "within its reach. "
						+ "Burn their forges, cut their fuel or shrink their colonies and the "
						+ "danger level genuinely regresses.", 0f);
				if (!ThreatAlarm.enabled()) return;
				Color h = Misc.getHighlightColor();
				// (2026-09-29: the alarm no longer speeds fabrication - the bank
				// pays for every swarm - so the tooltip is the grudges, one a line)
				float pad = 10f;
				for (Map.Entry<String, Float> g : ThreatAlarm.grudges().entrySet()) {
					if (g.getValue() == null || g.getValue() <= 0f) continue;
					tooltip.addPara(ThreatWarState.displayName(g.getKey()) + ": grudge %s "
							+ "(strike weight x%s)", pad, h, String.format("%.1f", g.getValue()),
							String.format("%.1f", ThreatAlarm.targetMult(g.getKey())));
					pad = 3f;
				}
			}
		}, panel, TooltipLocation.BELOW);
	}

	// ---- sector strip ----

	/** Returns the strip panel so the faction selector can anchor below it (a sibling). */
	protected static CustomPanelAPI addStrip(TooltipMakerAPI main, float width, List<Entry> entries) {
		int worlds = 0, mass = 0, outbound = 0, inbound = 0;
		for (Entry e : entries) {
			worlds += e.markets.size();
			mass += e.mass;
			outbound += e.outbound.size();
			inbound += e.inbound.size();
		}
		int burned = ThreatIncData.getCleansedCount();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();

		// no Missions figure since 2026-10-30: the contracts are retired, the targets table lists the links
		// no Mass either (2026-10-30: real-time information)
		// no Swarm fleet either (the user, 2026-10-30: "never accurate given defense fleets are absorbed when idle")
		String[] labels = {"Known systems", "Hive worlds", "Expeditions out", "Sieges in", "Hives burned"};
		String[] values = {"" + entries.size(), "" + worlds, "" + outbound, "" + inbound, "" + burned};
		Color[] colors = {h, h, outbound > 0 ? neg : h, inbound > 0 ? pos : h, burned > 0 ? pos : h};

		final float stripH = 42f;
		CustomPanelAPI panel = Global.getSettings().createCustom(width, stripH,
				new BaseCustomUIPanelPlugin() {
			PositionAPI pos;
			public void positionChanged(PositionAPI position) { pos = position; }
			public void render(float alphaMult) {
				if (pos == null) return;
				Color base = Global.getSector().getFaction(Factions.THREAT).getBaseUIColor();
				glBegin();
				hline(pos.getX(), pos.getX() + pos.getWidth(), pos.getY(), base, 0.35f * alphaMult);
				glEnd();
			}
		});
		float cellW = width / 4f;
		for (int i = 0; i < labels.length; i++) {
			textHl(panel, (i % 4) * cellW + 4f, 2f + (i / 4) * 20f, cellW - 6f, labels[i] + " %s", gray,
					new Color[] {colors[i]}, new String[] {values[i]}, null, true);
		}
		main.addCustom(panel, 6f);
		return panel;
	}

	// ---- the ledger ----

	protected static final int RANK = 0, SYSTEM = 1, THREAT = 2, WORLDS = 3, MASS = 4, VIT = 5,
			SWARMS = 6, REACH = 7, OUT = 8, IN = 9, SUPPLY = 10, NOTES = 11, AGE = 12, ACT = 13;
	protected static final int COLS = 14;

	/** Column widths (and their offsets within a row) for a given table width. */
	protected static class Cols {
		float[] x = new float[COLS];
		float[] w = new float[COLS];
		float total;
		boolean narrow;

		Cols(float width) {
			narrow = width < NARROW_WIDTH;
			// Reach holds "grounded" (billed reach, 2026-09-30): a point off
			// Activity, which fits the same number of crests either way. Swarm FP
			// holds a report's figure and age, "12,400 (141 d)" (the fog of war,
			// 2026-10-01): its points came off System, Worlds, Fed and Activity
			// THE LEDGER IS WHAT THE SCOUTS KNOW (the user, 2026-10-30: "a lot of the information here
			// should not be shown as it is real time information"): Mass, the Cores' state, Reach and
			// Flow are gone (0 width); Worlds is a count; # is the priority score. Actions (the user,
			// 2026-10-30: "new action column at the end attack ... and also map button") holds Attack +
			// Map, 64 + 46 px and their gaps
			// DISTANCE FIRST (the user, 2026-10-30): the first column is light-years to the core, the ledger
			// sorted nearest first; the priority score and the Swarm fleet column are gone (0 width), the
			// Notes column (15:30, in the old Core (ly) slot) says what to hit
			float[] frac = narrow
					? new float[] {.07f, .11f, 0f, 0f, 0f, 0f, 0f, 0f, .06f, .12f, .12f, .35f, 0f, .17f}
					: new float[] {.07f, .12f, 0f, 0f, 0f, 0f, 0f, 0f, .06f, .12f, .12f, .36f, 0f, .15f};
			float cursor = 0f;
			for (int i = 0; i < COLS; i++) {
				w[i] = (float) Math.floor(width * frac[i]);
				x[i] = cursor;
				cursor += w[i];
			}
			total = cursor;
		}

		boolean has(int col) {
			return w[col] > 0f;
		}

		/** Index of a column among the visible ones (for header tooltips). */
		int visibleIndex(int col) {
			int index = 0;
			for (int i = 0; i < col; i++) {
				if (has(i)) index++;
			}
			return index;
		}
	}

	/** Text inset the stock table applies inside a cell; the overlay draws from here. */
	protected static final float CELL_PAD = 5f;
	protected static final float CREST = 18f;
	protected static final float CREST_STEP = 22f;

	protected static Ledger addLedger(final ThreatIncursionIntel intel, final IntelUIAPI ui,
			TooltipMakerAPI main, float width, float opad, List<Entry> entries, Entry selected) {
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		Color dark = threat.getDarkUIColor();
		Color bright = threat.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();

		main.addSectionHeading("Known infested systems - nearest first", bright, dark,
				Alignment.MID, opad);
		if (entries.isEmpty()) {
			main.addPara("You have confirmed no Threat infestations. Where the swarm is - and "
					+ "how far it has spread - is %s.", opad, neg, "unknown");
			return null;
		}

		// the Attack's Fleet and Days levers sit in a reserved row under the heading (the user, 2026-10-31
		// evening); their buttons float in later (addLedgerButtons -> ThreatFactionView.addLedgerLevers)
		main.addSpacer(ThreatFactionView.SELECTOR_ROW_H);
		// the stock table carries the text, the clicks and the tooltips
		final Cols c = new Cols(width - 24f);
		// the Core column (2026-10-25) is the Fabrication Cores' state; reach the world struck first
		final boolean billed = ThreatReach.enabled();
		String[] names = {"Distance", "System", "Threat", "Worlds", "Mass", "Core", "Swarm fleet",
				"Reach", "Strikes", "Activity", "Supply", "Notes", "Age", "Actions"};
		List<Object> columns = new ArrayList<Object>();
		for (int i = 0; i < COLS; i++) {
			if (!c.has(i)) continue;
			columns.add(names[i]);
			columns.add(c.w[i]);
		}
		com.fs.starfarer.api.ui.UIPanelAPI table = main.beginTable2(threat, ROW_H, true, true, columns.toArray());
		main.makeTableItemsClickable();
		main.addTableHeaderTooltip(c.visibleIndex(RANK), "Light-years to the nearest major inhabited world.");
		if (c.has(WORLDS)) main.addTableHeaderTooltip(c.visibleIndex(WORLDS), "Hive worlds in the system.");
		if (c.has(MASS)) main.addTableHeaderTooltip(c.visibleIndex(MASS), "Combined size of the hive colonies - "
				+ "the swarm's fabrication mass.");
		if (c.has(VIT)) main.addTableHeaderTooltip(c.visibleIndex(VIT), "The Fabrication Cores' state: growing, holding or starving.");
		if (c.has(AGE)) main.addTableHeaderTooltip(c.visibleIndex(AGE), "Days since the last look at the system - your scouts', an ally's or your own - or since a party sent there was lost.");
		if (c.has(SWARMS)) main.addTableHeaderTooltip(c.visibleIndex(SWARMS), "Threat fleet points last seen in the "
				+ "system, and days since.");
		if (c.has(REACH)) main.addTableHeaderTooltip(c.visibleIndex(REACH), billed
				? "Light-years to the world it would strike first."
				: "How far its expeditions reach, bought with the fuel the staging colony draws from "
						+ "the hive network.");
		main.addTableHeaderTooltip(c.visibleIndex(OUT), "Threat expeditions staged from this "
				+ "system: strikes in flight and seeding swarms in transit. Details in the row tooltip.");
		main.addTableHeaderTooltip(c.visibleIndex(IN), "Everything against it, by crest: siege "
				+ "expeditions, task forces, intercepts, Support sorties, front runs and ground fronts. "
				+ "Details in the row tooltip.");
		main.addTableHeaderTooltip(c.visibleIndex(SUPPLY), new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 420f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("Every resource the system produces. Any one hive world making a "
						+ "resource feeds every hive world that can reach it, so what counts is how "
						+ "many systems make each - a resource made in only one is the hive's weak "
						+ "point.", 0f);
			}
		});
		if (c.has(NOTES)) main.addTableHeaderTooltip(c.visibleIndex(NOTES), "As your scouts last saw it: the hive worlds, their strata, and the remark - "
				+ "a lost party, a strike massing, starving Cores, the largest producer, Cores fed in full.");

		final List<Object> rowObjects = new ArrayList<Object>();
		final List<Entry> rowEntries = new ArrayList<Entry>();
		int rank = 0;
		for (final Entry e : entries) {
			rank++;
			boolean isSelected = selected != null && selected.systemId.equals(e.systemId);
			List<Object> cells = new ArrayList<Object>();
			// the distance to the core (the user, 2026-10-30: "move core column to first column position")
			cell(cells, Alignment.MID, text, e.coreLY >= 0f ? (int) Math.ceil(e.coreLY) + " ly" : "-");
			cell(cells, Alignment.LMID, isSelected ? Misc.getBasePlayerColor() : bright,
					main.shortenString(e.displayName(), c.w[SYSTEM] - 2f * CELL_PAD));
			if (c.has(THREAT)) cell(cells, Alignment.LMID, e.reasonColor, e.chip);

			if (!e.isColony()) {
				// the Worlds column went 2026-10-30 16:30 (the user): the count opens the Notes cell
				if (c.has(WORLDS)) cell(cells, Alignment.MID, gray,
						ThreatIncData.STAGE_COLONIZING.equals(e.stage) ? "colonizing" : "seeded");
				if (c.has(MASS)) cell(cells, Alignment.MID, gray, "-");
				String eta = e.inbound.isEmpty() ? "-" : e.inbound.get(0).eta.equals("-")
						? e.inbound.get(0).status : "planetfall " + e.inbound.get(0).eta;
				if (c.has(VIT)) cell(cells, Alignment.LMID, gray, eta);
				// the seeding swarm's planetfall sits where the swarm figure would
				if (c.has(SWARMS)) cell(cells, Alignment.MID, gray, c.has(VIT) ? "-" : eta);
			} else {
				// a count (the user, 2026-10-30: "worlds should just show total count")
				if (c.has(WORLDS)) cell(cells, Alignment.MID, h, "" + e.markets.size());
				if (c.has(MASS)) cell(cells, Alignment.MID, h, "" + e.mass);
				// the Cores' state over the system's worlds: starving before growing before holding
				if (c.has(VIT)) cell(cells, Alignment.MID, stateColor(e.core), fedStateName(e.core));
				// the player's report: its figure and age, "Unknown" never seen
				if (c.has(SWARMS)) {
					float seenFP = e.seen != null ? e.seen.totalFP() : 0f;
					cell(cells, Alignment.MID, e.seen == null ? gray : seenFP <= 0f ? pos : text,
							ThreatIntel.figure(e.seen, seenFP));
				}
			}
			if (!c.has(REACH)) {
				// no Reach column (2026-10-30: a strike sails as far as its fuel allows)
			} else if (billed) {
				// grounded shows whatever else stops it; otherwise no staging
				// colony strikes nothing, as with the old radius
				boolean shown = e.grounded || e.staging != null;
				String reach = shown ? reachText(e.grounded, e.facedFaction, e.facedLY) : "-";
				cell(cells, Alignment.MID, shown ? reachColor(e.grounded, e.facedFaction) : gray,
						main.shortenString(reach, c.w[REACH] - 2f * CELL_PAD));
			} else {
				cell(cells, Alignment.MID, e.reachLY > 0f ? (e.inReach.isEmpty() ? h : neg) : gray,
						e.reachLY > 0f ? (int) e.reachLY + " ly" : "-");
			}
			cell(cells, Alignment.MID, e.outbound.isEmpty() ? gray : neg,
					e.outbound.isEmpty() ? "-" : "" + e.outbound.size());
			cell(cells, Alignment.MID, gray, e.inbound.isEmpty() ? "-" : ""); // crests by the overlay
			cell(cells, Alignment.MID, gray, e.supplies.isEmpty() ? "-" : ""); // icons by the overlay
			// NOTES (the user, 2026-10-30 15:30): the system's objectives - a strike staging, a top producer (ThreatObjectives)
			if (c.has(NOTES)) {
				cell(cells, Alignment.LMID, ThreatObjectives.noteColor(e, bright, h, gray, neg),
						main.shortenString(ThreatObjectives.cellText(e), c.w[NOTES] - 2f * CELL_PAD));
			}
			// the Age column (16:00-17:50) is 0 width: the age ends the note (the user, 17:50)
			if (c.has(AGE)) {
				String age = ThreatObjectives.ageText(e);
				cell(cells, Alignment.MID, "-".equals(age) ? gray : text, age);
			}
			cell(cells, Alignment.MID, gray, ""); // Attack + Map float here (addLedgerButtons)

			Object row = isSelected ? main.addRowWithGlow(cells.toArray()) : main.addRow(cells.toArray());
			main.addTooltipToAddedRow(new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 440f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					addRowTooltip(tooltip, e, c.narrow);
					ThreatObjectives.addTooltip(tooltip, e, 10f);
				}
			}, TooltipLocation.LEFT, false);
			main.setIdForAddedRow(e.systemId);
			rowObjects.add(row);
			rowEntries.add(e);
		}
		main.addTable("None", -1, 0f);

		// the graphics ride over the table, drawn at each row's live position
		LedgerOverlay overlay = new LedgerOverlay(rowObjects, rowEntries, c,
				selected != null ? selected.systemId : null);
		CustomPanelAPI overlayPanel = Global.getSettings().createCustom(2f, 2f, overlay);
		main.addCustomDoNotSetPosition(overlayPanel).getPosition().inTL(0f, 0f);

		main.addSpacer(opad);
		Ledger ledger = new Ledger();
		ledger.table = table;
		ledger.cols = c;
		ledger.rows = rowEntries;
		ledger.levers = true;
		return ledger;
	}

	protected static void cell(List<Object> cells, Alignment align, Color color, String text) {
		cells.add(align);
		cells.add(color);
		cells.add(text);
	}

	/** "5 4 4 4 4 4", largest first, capped to what the column fits; "+n" for the rest. */
	protected static String sizeDigits(Entry e, Cols c) {
		int max = Math.max(1, (int) ((c.w[WORLDS] - 2f * CELL_PAD) / 14f));
		List<Integer> sizes = new ArrayList<Integer>();
		for (MarketAPI market : e.markets) sizes.add(market.getSize());
		Collections.sort(sizes, Collections.reverseOrder());
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sizes.size(); i++) {
			if (i == max - 1 && sizes.size() > max) {
				sb.append(" +").append(sizes.size() - i);
				break;
			}
			if (i > 0) sb.append(" ");
			sb.append(sizes.get(i));
		}
		return sb.toString();
	}

	protected static String rewardShort(int reward) {
		if (reward >= 1000000) {
			float m = reward / 1000000f;
			return (m >= 10f ? "" + Math.round(m) : String.format("%.1f", m)) + "M";
		}
		return Math.round(reward / 1000f) + "k";
	}

	/** S/I for the board, then the offer's priority on it - or * once the player holds it. */
	protected static String missionCode(ThreatMissionIntel b) {
		return (b.getTier() == ThreatMissionIntel.TIER_STRATEGIC ? "S" : "I")
				+ (b.isAccepted() ? "*" : "" + b.currentRank());
	}

	/**
	 * Paints the ledger's graphics over the stock table: the selection marker,
	 * the Fed bar (vitality with size upkeep off) and trend glyph, and the
	 * inbound crests. Uses each row component's live position and scales the
	 * column budget to the drawn row width, so it follows however the table
	 * lays itself out and never intercepts the table's clicks.
	 */
	protected static class LedgerOverlay extends BaseCustomUIPanelPlugin {
		protected List<Object> rows;
		protected List<Entry> entries;
		protected Cols c;
		protected String selectedId;

		LedgerOverlay(List<Object> rows, List<Entry> entries, Cols c, String selectedId) {
			this.rows = rows;
			this.entries = entries;
			this.c = c;
			this.selectedId = selectedId;
		}

		@Override
		public void render(float alphaMult) {
			glBegin();
			for (int i = 0; i < rows.size(); i++) {
				if (!(rows.get(i) instanceof UIComponentAPI)) continue;
				PositionAPI pos = ((UIComponentAPI) rows.get(i)).getPosition();
				Entry e = entries.get(i);
				float x = pos.getX();
				float y = pos.getY();
				float h = pos.getHeight();
				float midY = y + h / 2f;
				float scale = c.total > 0f ? pos.getWidth() / c.total : 1f;

				if (selectedId != null && selectedId.equals(e.systemId)) {
					quad(x, y, 2f, h, Misc.getBasePlayerColor(), 0.9f * alphaMult);
				}

				// inbound crests, centred in the Activity column, as many as fit
				float colWIn = c.w[IN] * scale;
				int fitIn = Math.max(1, (int) ((colWIn - 2f * CELL_PAD + (CREST_STEP - CREST)) / CREST_STEP));
				int crests = Math.min(fitIn, e.inbound.size());
				if (crests > 0) {
					float colX = x + c.x[IN] * scale;
					float colW = colWIn;
					float groupW = crests * CREST_STEP - (CREST_STEP - CREST);
					float cx0 = colX + (colW - groupW) / 2f;
					for (int k = 0; k < crests; k++) {
						Op op = e.inbound.get(k);
						String crest = op.faction != null ? op.faction.getCrest() : null;
						float cx = cx0 + k * CREST_STEP;
						if (crest != null) {
							icon(crest, cx, midY - CREST / 2f, CREST, null, alphaMult);
						} else {
							outline(cx, midY - CREST / 2f, CREST, CREST, op.color, alphaMult);
						}
					}
				}

				// produced commodity icons, centred in the Supply column
				float colWSupply = c.w[SUPPLY] * scale;
				int fit = Math.max(1, (int) ((colWSupply - 2f * CELL_PAD + (CREST_STEP - CREST)) / CREST_STEP));
				int icons = Math.min(fit, e.supplies.size());
				if (icons > 0) {
					float colX = x + c.x[SUPPLY] * scale;
					float groupW = icons * CREST_STEP - (CREST_STEP - CREST);
					float ix0 = colX + (colWSupply - groupW) / 2f;
					int k = 0;
					for (String id : e.supplies) {
						if (k >= icons) break;
						icon(commodityIcon(id), ix0 + k * CREST_STEP, midY - CREST / 2f, CREST, null, alphaMult);
						k++;
					}
				}

				if (!e.isColony() || !c.has(VIT)) continue;

				// the Core column reads its state (the cell); the trend glyph at its right end
				float vx = x + c.x[VIT] * scale + CELL_PAD;
				float vw = c.w[VIT] * scale - 2f * CELL_PAD - 18f;
				trend(vx + vw + 10f, midY, e.trend, alphaMult);
			}
			glEnd();
		}
	}

	protected static void addRowTooltip(TooltipMakerAPI tooltip, Entry e, boolean narrow) {
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		float w = tooltip.getWidthSoFar();
		tooltip.addSectorMap(w, Math.round(w / 1.8f), e.system, 0f);
		tooltip.addSpacer(10f);
		String title = e.system.getNameWithNoType();
		if (e.isColony()) {
			title += " - " + e.markets.size() + (e.markets.size() == 1 ? " hive world" : " hive worlds");
		}
		tooltip.addPara(title, Global.getSector().getFaction(Factions.THREAT).getBrightUIColor(), 6f);
		tooltip.addPara(e.chip + " - " + e.reason, e.reasonColor, 4f);
		// no growth states or starving lines since 2026-10-30 16:00: live figures; the Notes lines below carry the report
		if (!e.supplies.isEmpty()) {
			tooltip.addPara("Makes:", text, 6f);
			List<String> ids = new ArrayList<String>(e.supplies);
			List<Integer> nums = new ArrayList<Integer>();
			List<IconRenderMode> modes = new ArrayList<IconRenderMode>();
			for (int i = 0; i < ids.size(); i++) {
				nums.add(1);
				modes.add(IconRenderMode.NORMAL);
			}
			iconRow(tooltip, ids, nums, modes);
		}
		for (Op op : e.outbound) tooltip.addPara(op.detail, op.color, 4f);
		for (Op op : e.inbound) tooltip.addPara(op.detail, op.color, 4f);
		for (ThreatMissionIntel b : e.missions) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(b.getMarketId());
			String target = ThreatMissionIntel.typeNoun(b.getType()) + " on "
					+ (market != null ? market.getName() : "a hive world");
			if (b.isAccepted()) {
				tooltip.addPara("Accepted " + ThreatMissionIntel.tierName(b.getTier()).toLowerCase()
						+ " contract - " + target + ": %s, %s days left to complete.",
						4f, text, h, Misc.getDGSCredits(b.getReward()), "" + (int) b.daysRemaining());
			} else {
				tooltip.addPara(ThreatMissionIntel.tierName(b.getTier()) + " contract offered, priority "
						+ b.currentRank() + " - " + target + ": %s, %s days left to accept.",
						4f, text, h, Misc.getDGSCredits(b.getReward()), "" + (int) b.daysRemaining());
			}
		}
		if (ThreatReach.enabled()) {
			if (e.grounded) {
				tooltip.addPara("%s: " + (ThreatFuel.stock() <= 0f ? "the hive has no fuel."
						: "the hive can keep no swarm away."), 4f, text, ThreatNotice.goodColor(), "Grounded");
			} else if (e.staging != null && e.facedFaction != null) {
				tooltip.addPara("First target: %s, %s.", 4f,
						new Color[] {factionColor(e.facedFaction), h},
						ThreatWarState.displayName(e.facedFaction), (int) Math.ceil(e.facedLY) + " ly");
			}
		} else if (!e.inReach.isEmpty()) {
			tooltip.addPara("Within strike reach: %s.", 4f, text, h, join(
					e.inReach.size() > 6 ? e.inReach.subList(0, 6) : e.inReach)
					+ (e.inReach.size() > 6 ? " and " + (e.inReach.size() - 6) + " more" : ""));
		}
		List<String> geo = new ArrayList<String>();
		// distances place a system on the map as surely as its name; none for one unfound
		if (e.coreLY >= 0f) geo.add((int) Math.ceil(e.coreLY) + " ly from the nearest major world");
		if (e.playerLY >= 0f) geo.add((int) Math.ceil(e.playerLY) + " ly from your nearest colony");
		geo.add((int) ThreatIncData.daysInStage(e.systemId) + " days in its current stage");
		tooltip.addPara(join(geo) + ".", gray, 4f);
		tooltip.addPara("Click to select", gray, 6f);
	}

	// ---- the drill-down: colony cards across the full width ----

	/** The selected system's heading line; its fronts table and cards follow separately. */
	protected static void addDetailHeading(TooltipMakerAPI main, float opad, Entry e) {
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		Color dark = threat.getDarkUIColor();
		Color bright = threat.getBrightUIColor();

		StringBuilder title = new StringBuilder(e.system.getNameWithNoType());
		if (e.isColony()) {
			title.append(" - ").append(e.markets.size())
					.append(e.markets.size() == 1 ? " hive world" : " hive worlds");
		} else {
			title.append(" - ").append(ThreatIncData.STAGE_COLONIZING.equals(e.stage)
					? "seeding swarm in transit" : "marked for seeding");
		}
		if (e.coreLY >= 0f) title.append(" - ").append((int) Math.ceil(e.coreLY)).append(" ly from the core");
		if (e.playerLY >= 0f) title.append(" - ").append((int) Math.ceil(e.playerLY)).append(" ly from your nearest colony");
		main.addSectionHeading(title.toString(), bright, dark, Alignment.MID, opad);
	}

	/** The selected system's colony cards (or the seeding note), after its fronts. */
	protected static void addDetailBody(TooltipMakerAPI main, float width, float opad, Entry e,
			List<Object[]> cardsOut) {
		if (e.isColony()) {
			addCards(main, width, e, cardsOut);
		} else {
			addNonColonyBlock(main, opad, e);
		}
		main.addSpacer(opad);
	}

	protected static void addNonColonyBlock(TooltipMakerAPI info, float opad, Entry e) {
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		String name = e.system.getNameWithLowercaseType();
		if (ThreatIncData.STAGE_COLONIZING.equals(e.stage)) {
			info.addPara("A Threat %s is in transit to the " + name + ", carrying the fabricator "
					+ "core of a new colony.", opad, neg, "Seeding Swarm");
		} else {
			info.addPara("Threat fabrication signatures have been detected in the " + name + ". "
					+ "The system has been marked by the swarm - a %s will be dispatched to found "
					+ "a colony here.", opad, neg, "Seeding Swarm");
		}
		info.addPara("No colony exists yet. %s", opad, pos,
				"Destroy the Seeding Swarm before it makes planetfall and no colony will take root.");
	}

	/** Cards three across on a wide panel, two on a narrow one; Map buttons are added by the caller. */
	protected static void addCards(TooltipMakerAPI main, float width, final Entry e,
			List<Object[]> cardsOut) {
		float gap = 8f;
		int perRow = width >= NARROW_WIDTH ? 3 : 2;
		float cardW = (float) Math.floor((width - gap * (perRow - 1)) / perRow);
		for (int i = 0; i < e.markets.size(); i += perRow) {
			CustomPanelAPI first = null;
			CustomPanelAPI prev = null;
			for (int k = i; k < Math.min(i + perRow, e.markets.size()); k++) {
				MarketAPI market = e.markets.get(k);
				CustomPanelAPI card = buildCard(main, market, cardW, e);
				if (first == null) {
					main.addCustom(card, i == 0 ? 6f : gap);
					first = card;
				} else {
					main.addCustomDoNotSetPosition(card).getPosition().rightOfTop(prev, gap);
				}
				cardsOut.add(new Object[] {card, market, e});
				prev = card;
			}
		}
	}

	/** The organ icons a card shows, in a fixed order; population is not an organ. */
	protected static List<Industry> organsOf(MarketAPI market) {
		List<Industry> result = new ArrayList<Industry>();
		String[] order = {ThreatColonyManager.FABRICATION_CORE, ThreatColonyManager.SWARM_NEXUS,
				Industries.MEGAPORT, Industries.SPACEPORT, Industries.ORBITALWORKS,
				Industries.HEAVYINDUSTRY, Industries.REFINING, Industries.FUELPROD, Industries.MINING,
				ThreatColonyManager.THREAT_HEAVY_BATTERIES, ThreatColonyManager.THREAT_GROUND_DEFENSES};
		for (String id : order) {
			Industry ind = market.getIndustry(id);
			if (ind != null) result.add(ind);
		}
		for (Industry ind : market.getIndustries()) {
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (!result.contains(ind)) result.add(ind);
		}
		return result;
	}

	protected static String commodityIcon(String commodityId) {
		try {
			CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(commodityId);
			return spec != null ? spec.getIconName() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * A vanilla icon group: {@code nums} is drawn as a STACK of that many icons
	 * (the game's idiom for units), so pass 1 where only the tint matters.
	 */
	protected static void iconRow(TooltipMakerAPI tm, List<String> ids, List<Integer> nums,
			List<IconRenderMode> modes) {
		tm.beginIconGroup();
		for (int i = 0; i < ids.size(); i++) {
			CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(ids.get(i));
			if (spec == null) continue;
			tm.addIcons(spec, Math.max(1, Math.min(10, nums.get(i))), modes.get(i));
		}
		tm.addIconGroup(24f, 0f);
	}

	/** Measured name widths from the last card build, so the Map button can sit right of the name. */
	protected static Map<String, Float> nameWidths = new LinkedHashMap<String, Float>();

	protected static CustomPanelAPI buildCard(TooltipMakerAPI main, final MarketAPI market,
			float cardW, Entry e) {
		// the Fabrication Core's state (2026-10-25): growing, holding or starving on its inputs
		final int core = fedState(market);
		final float health = 0f;
		final List<Industry> organs = organsOf(market);
		// "declining" = a ground front or saturation is taking this colony
		// apart (the card border lights up)
		final boolean declining = ThreatGroundFronts.hasFront(market) || ThreatRazing.saturated(market);
		final float iconStep = 44f;

		CustomPanelAPI card = Global.getSettings().createCustom(cardW, CARD_H,
				new BaseCustomUIPanelPlugin() {
			PositionAPI pos;
			public void positionChanged(PositionAPI position) { pos = position; }
			public void render(float alphaMult) {
				if (pos == null) return;
				float x = pos.getX();
				float y = pos.getY();
				float w = pos.getWidth();
				float h = pos.getHeight();
				FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
				glBegin();
				quad(x, y, w, h, Color.BLACK, 0.35f * alphaMult);
				outline(x, y, w, h, declining ? ThreatNotice.goodColor()
						: threat.getBaseUIColor(), (declining ? 0.9f : 0.6f) * alphaMult);
				// organ icons along the bottom: 30 px squares, red-tinted while disrupted
				float ix = x + 8f;
				float iy = topY(pos, ORGANS_Y, 30f);
				for (Industry ind : organs) {
					if (ix + 30f > x + w - 8f) break;
					boolean down = ind.isDisrupted();
					Color frame = down ? Misc.getNegativeHighlightColor() : threat.getBaseUIColor();
					quad(ix, iy, 30f, 30f, down ? Misc.getNegativeHighlightColor() : Color.BLACK,
							(down ? 0.25f : 0.4f) * alphaMult);
					icon(ind.getCurrentImage(), ix + 1f, iy + 1f, 28f,
							down ? new Color(255, 140, 110) : null, alphaMult);
					outline(ix, iy, 30f, 30f, frame, 0.8f * alphaMult);
					ix += iconStep;
				}
				glEnd();
			}
		});

		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		Color bright = Global.getSector().getFaction(Factions.THREAT).getBrightUIColor();

		// line 1: name and size | vitality and forecast (role lives in the tooltip)
		// line 1: name (the Map button follows it, placed by the caller) | size forecast, right-aligned
		float rightW = Math.min(118f, cardW * 0.3f);
		// the name has the line up to the right-aligned Map and Colony buttons
		float leftW = cardW - MAP_BUTTON_W - COLONY_BUTTON_W - 30f;
		TooltipMakerAPI probe = card.createUIElement(leftW, 20f, false);
		probe.setTextWidthOverride(leftW);
		probe.setParaFont(BOARD_FONT);
		String name = market.getName();
		if (probe.computeStringWidth(name) > leftW - TEXT_MARGIN) {
			name = probe.shortenString(name, Math.max(40f, leftW - TEXT_MARGIN));
		}
		probe.addPara(name, bright, 0f);
		card.addUIElement(probe).inTL(8f, 5f);
		nameWidths.put(market.getId(), probe.computeStringWidth(name));
		// no size forecast since 2026-10-30 (the user: the tiles keep the strata and the scouts' report, nothing real-time)

		// disruption day counts centred under offline organs, and a hover target
		// over each tile carrying the industry's name, state, output and inputs
		float ix = 8f;
		for (Industry ind : organs) {
			if (ix + 30f > cardW - 8f) break;
			TooltipMakerAPI tile = card.createUIElement(30f, 30f, false);
			card.addUIElement(tile).inTL(ix, ORGANS_Y);
			main.addTooltipTo(industryTooltip(market, ind), tile, TooltipLocation.BELOW);
			if (ind.isDisrupted()) {
				String days = (int) ind.getDisruptedDays() + "d";
				TooltipMakerAPI probe2 = card.createUIElement(iconStep, 20f, false);
				probe2.setParaFont(BOARD_FONT);
				float tw = probe2.computeStringWidth(days);
				probe2.setTextWidthOverride(iconStep);
				probe2.addPara(days, neg, 0f);
				card.addUIElement(probe2).inTL(ix + 15f - tw / 2f - 5f, ORGANS_Y + 30f);
			}
			ix += iconStep;
		}

		// line A, above the organs (the user, 2026-10-30: "remove all that info from the tiles but keep
		// strata just add the brackets days indicating last update time"): the strata, then the swarms
		// as last seen - each with the age of the player's report of the system (ThreatIntel, the fog
		// of war); "Unknown" never seen. No Core state, forecast, reach, front or fuel bill: the
		// tiles show what the scouts know, nothing real-time
		List<String> hlA = new ArrayList<String>();
		List<Color> hlcA = new ArrayList<Color>();
		StringBuilder a = new StringBuilder();
		ThreatIntel.Report over = seenOver(e.seen, market);
		// the strata as the report recorded them (never the live figure); no Swarm fleet since 2026-10-30
		// (the user: "never accurate given defense fleets are absorbed when idle")
		if (ThreatGroundFronts.strataTarget(market)) {
			a.append("Strata %s");
			float strata = over != null ? over.worldStrata(market.getId()) : -1f;
			hlA.add(strata >= 0f ? ThreatIntel.figure(over, strata) : "Unknown");
			hlcA.add(strata >= 0f ? text : gray);
		}
		if (a.length() > 0) textHl(card, 8f, 27f, cardW - 16f, a.toString(), gray, hlcA.toArray(new Color[0]),
				hlA.toArray(new String[0]), Alignment.LMID, true);
		return card;
	}

	/**
	 * What vanilla's colony screen says about an industry tile: name, state,
	 * what it makes and what it wants, with the wanted stacks red where the
	 * market is short of that input.
	 */
	protected static TooltipCreator industryTooltip(final MarketAPI market, final Industry ind) {
		return new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 360f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				Color neg = Misc.getNegativeHighlightColor();
				Color gray = Misc.getGrayColor();
				Color text = Misc.getTextColor();
				tooltip.addPara(ind.getCurrentName(), h, 0f);
				if (ind.isDisrupted()) {
					tooltip.addPara("Disrupted - %s days until it resumes.", 4f, neg,
							"" + (int) Math.ceil(ind.getDisruptedDays()));
				} else if (ind.isBuilding() && !ind.isUpgrading()) {
					tooltip.addPara("Under construction.", gray, 4f);
				} else if (ind.isUpgrading()) {
					tooltip.addPara("Upgrading.", gray, 4f);
				}
				// production: units made, then faded the units its own input
				// shortfalls cost it (the negative terms on the supply stat)
				List<String> ids = new ArrayList<String>();
				List<Integer> nums = new ArrayList<Integer>();
				List<IconRenderMode> modes = new ArrayList<IconRenderMode>();
				for (MutableCommodityQuantity q : ind.getAllSupply()) {
					int made = q.getQuantity().getModifiedInt();
					int lost = 0;
					for (com.fs.starfarer.api.combat.MutableStat.StatMod mod
							: q.getQuantity().getFlatMods().values()) {
						if (mod.value < 0f) lost += Math.round(-mod.value);
					}
					if (made <= 0 && lost <= 0) continue;
					if (made > 0) {
						ids.add(q.getCommodityId());
						nums.add(made);
						modes.add(IconRenderMode.NORMAL);
					}
					if (lost > 0) {
						ids.add(q.getCommodityId());
						nums.add(lost);
						modes.add(IconRenderMode.DIM_RED);
					}
				}
				if (!ids.isEmpty()) {
					tooltip.addPara("Production - faded what its own shortfalls cost it:", text, 8f);
					iconRow(tooltip, ids, nums, modes);
				}
				// demand: units on hand at this world, then faded the units missing
				ids.clear(); nums.clear(); modes.clear();
				for (MutableCommodityQuantity q : ind.getAllDemand()) {
					int n = q.getQuantity().getModifiedInt();
					if (n <= 0) continue;
					CommodityOnMarketAPI com = market.getCommodityData(q.getCommodityId());
					int have = Math.min(n, com != null ? com.getAvailable() : 0);
					if (have > 0) {
						ids.add(q.getCommodityId());
						nums.add(have);
						modes.add(IconRenderMode.NORMAL);
					}
					if (n - have > 0) {
						ids.add(q.getCommodityId());
						nums.add(n - have);
						modes.add(IconRenderMode.DIM_RED);
					}
				}
				if (!ids.isEmpty()) {
					tooltip.addPara("Demand - on hand, then faded what is missing:", text, 8f);
					iconRow(tooltip, ids, nums, modes);
				}
			}
		};
	}

	/**
	 * The size line, right-aligned on the card: {format, from, to} with the two
	 * highlights - "s5 -> s6 ~105 d", "s5 -> s4 ~22 d" (starving), "s8 max",
	 * "s5 holding" ("s5 stalled" with size upkeep off, which alone reads
	 * {@code health}).
	 */
	protected static String[] forecastParts(MarketAPI market, float health) {
		String id = market.getId();
		String from = "s" + market.getSize();
		// a front on the ground: forecast the drive to the Core instead of
		// growth - remaining strata at the current per-stratum pace
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(id);
		if (front != null) {
			int left = Math.max(1, market.getSize() - front.strataHeld);
			float perStratum = ThreatGroundFronts.pushDaysEstimate(front, market);
			if (perStratum > 0f && ThreatGroundFronts.effectiveStrength(front)
					>= ThreatGroundFronts.holdRequirement(market)) {
				return new String[] {"%s -> %s", from,
						"core ~" + (int) Math.ceil(left * perStratum) + " d"};
			}
			return new String[] {"%s %s", from, "contested"};
		}
		{
			// the Core's own clocks (2026-10-25): a starving hive counts down to the size it
			// loses; one holding, at its largest or under saturation, holds
			int size = market.getSize();
			float lose = ThreatColonyManager.daysToLoseSize(market);
			if (lose < Float.MAX_VALUE) {
				return new String[] {"%s -> %s", from, "s" + (size - 1) + " ~" + Math.max(1, (int) lose) + " d"};
			}
			if (size >= ThreatColonyManager.maxColonySize(market)) return new String[] {"%s %s", from, "max"};
			float next = ThreatColonyManager.daysToNextSize(market);
			if (next < Float.MAX_VALUE) {
				return new String[] {"%s -> %s", from, "s" + (size + 1) + " ~" + Math.max(1, (int) next) + " d"};
			}
			return new String[] {"%s %s", from, "holding"};
		}
	}

	/** Plain-text form of the size line for tooltips. */
	protected static String forecastShort(MarketAPI market, float health) {
		String[] p = forecastParts(market, health);
		return p[0].replaceFirst("%s", p[1]).replaceFirst("%s", p[2]);
	}

	protected static String roleLabel(MarketAPI market) {
		if (market.hasIndustry(Industries.ORBITALWORKS)) return "forge world";
		if (market.hasIndustry(Industries.HEAVYINDUSTRY)) return "forge";
		if (market.hasIndustry(Industries.FUELPROD)) return "fuel world";
		if (market.hasIndustry(Industries.REFINING)) return "refinery";
		if (market.hasIndustry(Industries.MINING)) {
			for (MarketConditionAPI cond : market.getConditions()) {
				if (Commodities.RARE_ORE.equals(
						ResourceDepositsCondition.COMMODITY.get(cond.getId()))) {
					return "rare ore mine";
				}
			}
			return "mining world";
		}
		return "foothold";
	}

	protected static String commodityName(String commodityId) {
		try {
			return Global.getSettings().getCommoditySpec(commodityId).getName().toLowerCase();
		} catch (Throwable t) {
			return commodityId;
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Comma-joins non-blank strings. */
	/**
	 * " Needs 520 troops to push." - the count that side needs for the next tier its
	 * way (losing: hold, the line standing; standing: push, gaining past the band;
	 * gaining: overrun), solved by ThreatGroundFronts.troopsFor at the stances as
	 * they stand except that pushing or overrunning means that side attacks.
	 * Empty once overrunning or when no number reaches it.
	 */
	protected static String needLine(ThreatGroundFronts.GroundFront f, MarketAPI m, boolean forLanding, float s,
			float band, float overrun, boolean pushing, boolean worldPushes, String unit) {
		if (s >= overrun) return "";
		String tier;
		float target; // from the landing's side
		boolean lp = pushing;
		boolean wp = worldPushes;
		if (s < 0f) {
			tier = "hold";
			target = -band;
			if (forLanding) lp = true; else wp = true; // only a push back holds a push (the share rule, 2026-10-28)
		} else if (s <= 0f) {
			tier = "push";
			target = band;
			if (forLanding) lp = true; else wp = true;
		} else {
			tier = "overrun";
			target = overrun;
			if (forLanding) lp = true; else wp = true;
		}
		if (!forLanding) target = -target;
		float need = ThreatGroundFronts.troopsFor(f, m, forLanding, target, lp, wp);
		if (need < 0f) return "";
		if ("hold".equals(tier)) return " Needs " + n(need) + " " + unit + " pushing to hold.";
		return " Needs " + n(need) + " " + unit + " to " + tier + ".";
	}

	/** A figure with digit grouping, rounded. */
	protected static String n(float v) { return Misc.getWithDGS(Math.round(v)); }
	/** Two decimals. */
	protected static String f2(float v) { return String.format("%.2f", v); }
	/** One decimal. */
	protected static String f1(float v) { return String.format("%.1f", v); }

	public static String join(List<String> parts) {
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part == null || part.trim().isEmpty()) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(part);
		}
		return sb.toString();
	}

	public static Color healthColor(float health) {
		if (health < ThreatColonyManager.CRITICAL_HEALTH) return Misc.getNegativeHighlightColor();
		if (health < ThreatIncConfig.growthFullHealth()) return Misc.getHighlightColor();
		return ThreatNotice.goodColor();
	}

	/** A hive world's growth under size upkeep (fedState). */
	public static final int STARVING = -1, HOLDING = 0, GROWING = 1;

	/**
	 * A hive world's Fabrication Core state (2026-10-25), on the engine's own
	 * clocks: STARVING while its strata fall (a size lost in
	 * ThreatColonyManager.daysToLoseSize), GROWING while they rise toward its
	 * next size, else HOLDING - an input short, at its largest, or held by a
	 * front or saturation.
	 */
	public static int fedState(MarketAPI market) {
		if (ThreatColonyManager.daysToLoseSize(market) < Float.MAX_VALUE) return STARVING;
		if (ThreatColonyManager.daysToNextSize(market) < Float.MAX_VALUE) return GROWING;
		return HOLDING;
	}

	public static String fedStateName(int state) {
		return state == STARVING ? "starving" : state == GROWING ? "growing" : "holding";
	}

	/** A Core state in the reserve key's colours: growing white, holding yellow, starving red. */
	public static Color stateColor(int state) {
		if (state == STARVING) return Misc.getNegativeHighlightColor();
		if (state == HOLDING) return Misc.getHighlightColor();
		return Misc.getTextColor();
	}

	/**
	 * A share of the supplies upkeep paid in the reserve key's colours: white
	 * paid in full, yellow short of it, red below the break-even share
	 * (starving), grey for nothing billed (a negative share).
	 */
	public static Color fedColor(float fed) {
		if (fed < 0f) return Misc.getGrayColor();
		if (fed < ThreatColonyUpkeep.breakEven()) return Misc.getNegativeHighlightColor();
		// a share paid in full can land a rounding short of 1
		if (fed < 0.995f) return Misc.getHighlightColor();
		return Misc.getTextColor();
	}

	/** A share as a whole percentage, rounded. */
	public static String pct(float share) {
		return Math.round(share * 100f) + "%";
	}

	/**
	 * A report's view of one world (ThreatIntel): the report when it covers the
	 * world; null when there is none or it predates the world (a hive founded
	 * since), whose swarms were never seen.
	 */
	public static ThreatIntel.Report seenOver(ThreatIntel.Report r, MarketAPI world) {
		return r != null && world != null && r.worlds.containsKey(world.getId()) ? r : null;
	}

	/** Billed reach: the hive cannot keep the colony's one swarm away, or has no fuel to send it. */
	public static boolean grounded(MarketAPI market) {
		float days = ThreatReach.strikeDays(Math.max(0f, ThreatReach.facedLY(market.getStarSystem())));
		return !ThreatReach.canSustain(ThreatPosture.oneSwarmFP(market), days) || ThreatFuel.stock() <= 0f;
	}

	/**
	 * The world a system's swarm is judged by for billed reach: its staging
	 * colony, or with none - an empty fuel stock leaves pickStrikeStaging
	 * nothing - its biggest world of strike size; null when it has none.
	 */
	public static MarketAPI swarmSource(List<MarketAPI> markets, MarketAPI staging) {
		if (staging != null) return staging;
		MarketAPI best = null;
		for (MarketAPI m : markets) {
			if (m.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (best == null || m.getSize() > best.getSize()) best = m;
		}
		return best;
	}

	/** Billed reach's figure: "grounded", light-years to the world struck first, or "-" with none known. */
	protected static String reachText(boolean grounded, String facedFaction, float facedLY) {
		if (grounded) return "grounded";
		if (facedFaction == null || facedLY < 0f) return "-";
		return (int) Math.ceil(facedLY) + " ly";
	}

	/** Its colour: the good colour grounded, the faced world's owner's colour, grey with none. */
	protected static Color reachColor(boolean grounded, String facedFaction) {
		if (grounded) return ThreatNotice.goodColor();
		return factionColor(facedFaction);
	}

	/** A faction's own colour; grey for none. */
	public static Color factionColor(String factionId) {
		FactionAPI faction = factionId != null ? Global.getSector().getFaction(factionId) : null;
		return faction != null ? faction.getBaseUIColor() : Misc.getGrayColor();
	}

	public static StarSystemAPI getSystem(String systemId) {
		if (systemId == null) return null;
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	public static InfestedSystemIntel findMarker(String systemId) {
		for (Object curr : Global.getSector().getIntelManager().getIntel(InfestedSystemIntel.class)) {
			InfestedSystemIntel marker = (InfestedSystemIntel) curr;
			if (systemId.equals(marker.getSystemId())) return marker;
		}
		return null;
	}
}
