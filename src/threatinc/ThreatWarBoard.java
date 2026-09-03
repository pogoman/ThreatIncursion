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
 * "The Threat War Effort": the sector-wide board drawn as the large
 * description of {@link ThreatIncursionIntel}. One screen for everything the
 * player knows about the incursion: a header with the phase bar, a one-line
 * strip of sector totals, one custom-drawn ledger row per KNOWN infested
 * system in priority order, and for the selected system a grid of colony
 * cards (organ icons, vitality bar, garrison, fuel bill, reach) beside a table
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
 */
public class ThreatWarBoard {

	/** Rough hyperspace pace used for ETA estimates, matching the other intel. */
	public static final float EST_LY_PER_DAY = 0.5f;

	public static final String BUTTON_MAP = "threatinc_board_map:";
	/** The card's Colony button: opens vanilla's colony screen for the world (ThreatColonyScreenDialog). */
	public static final String BUTTON_COLONY = "threatinc_board_colony:";
	public static final float COLONY_BUTTON_W = 58f;
	public static final String BUTTON_COMMISSION = "threatinc_board_commission:";
	public static final String BUTTON_MISSION = "threatinc_board_mission:";

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
		/** Whether the player has found this system; false hides its name and place. */
		public boolean known = true;
		public String stage;
		public List<MarketAPI> markets = new ArrayList<MarketAPI>();
		public int mass;
		/** Size-weighted average colony health, 0..1; -1 without colonies. */
		public float health = -1f;
		/** +1 growing, 0 stalled, -1 declining. */
		public int trend;
		public int swarmsLive, swarmsDesired, swarmsMustered;
		/** +1 a nexus here is growing swarms, -1 one needs to but is silenced, 0 garrisons full. */
		public int swarmTrend;
		public MarketAPI staging;
		public float reachLY;
		public List<String> inReach = new ArrayList<String>();
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

		/** The system's name, or "Unknown" for one the player has not found. */
		public String displayName() {
			return known ? system.getNameWithNoType() : "Unknown";
		}

		public int strikeCount() {
			int n = 0;
			for (Op op : outbound) {
				if ("strike".equals(op.kind)) n++;
			}
			return n;
		}
	}

	/** Every system the swarm holds, best target first; unfound ones unnamed. */
	public static List<Entry> buildEntries() {
		List<Entry> result = new ArrayList<Entry>();
		hiveSupply = computeHiveSupply();
		boolean debug = ThreatIncConfig.debugMode();
		for (String systemId : new ArrayList<String>(ThreatIncData.stages().keySet())) {
			Entry entry = build(systemId);
			if (entry == null) continue;
			entry.known = debug || ThreatIncData.discoveredSystems().contains(systemId);
			result.add(entry);
		}
		Collections.sort(result, new Comparator<Entry>() {
			public int compare(Entry a, Entry b) {
				return Float.compare(b.score, a.score);
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

		float weightedHealth = 0f;
		boolean anyDeclining = false;
		boolean anyGrowing = false;
		float declineT = ThreatIncConfig.declineHealthThreshold();
		for (MarketAPI market : e.markets) {
			e.mass += market.getSize();
			float health = ThreatColonyManager.computeHealth(market);
			weightedHealth += health * market.getSize();
			if (health < declineT) anyDeclining = true;
			else if (ThreatColonyManager.growthMultFor(health) > 0f) anyGrowing = true;
			int live = ThreatColonyManager.countLiveGarrison(market.getId());
			int desired = ThreatColonyManager.desiredGarrisonCount(market);
			e.swarmsLive += live;
			e.swarmsDesired += desired;
			e.swarmsMustered += IncursionManager.preparingStrikeFleetCount(market);
			// the nexus fabricates while the garrison is short and it is not disrupted
			// (see ThreatColonyManager.maintainGarrisons); a silenced nexus with a
			// short garrison is the strategic opening
			if (live < desired) {
				if (ThreatColonyManager.hasOperationalNexus(market)) {
					if (e.swarmTrend == 0) e.swarmTrend = 1;
				} else {
					e.swarmTrend = -1;
				}
			}
		}
		if (e.mass > 0) e.health = weightedHealth / e.mass;
		e.trend = anyDeclining ? -1 : (anyGrowing ? 1 : 0);

		e.staging = ThreatColonyManager.pickStrikeStaging(systemId, false);
		if (e.staging != null) {
			e.reachLY = ThreatColonyManager.fuelRangeLY(e.staging);
			e.inReach = systemsInReach(system, e.reachLY);
		}

		collectStrikes(e);
		collectSeeding(e);
		collectSieges(e);
		collectResponses(e);
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
			op.detail = "Threat incursion against " + target + " - " + fleetsText(fleets) + ", "
					+ op.status + (op.eta.equals("-") ? "" : " (" + op.eta + ")") + "."
					+ (strike.isPreparing()
							? " Recall window open: disrupt the staging colony's forge or Swarm "
									+ "Nexus before departure and the operation is stillborn."
							: " Departed - autonomous; it can only be met in space.");
			e.outbound.add(op);
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
					+ (op.eta.equals("-") ? "" : " (" + op.eta + ")") + ". Tactical bombardment "
					+ "while the war-strata stand, commando raids on the organs once they are "
					+ "suppressed.";
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
			if (other.isHidden() || other.isPlanetConditionMarketOnly()) continue;
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
				if (other.isPlayerOwned() || other.isHidden()) continue;
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
	 * strike reach; a colony already declining that the sector should press;
	 * the network's decisive link (the defense boards' impact figure); then
	 * sheer mass and proximity to the core. The chip names the top
	 * contributor so the ordering explains itself.
	 */
	protected static void score(Entry e) {
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
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
		if (!e.inReach.isEmpty()) {
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
				chip = "DECLINING";
				reason = "A colony here is already declining - press the siege.";
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
				chip = "STALLED";
				reason = "Growth stalled - its supply lines are choked.";
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
		IntelUIAPI ui = main.getIntelUI();
		ThreatIncConfig.log("war board render width=" + width
				+ " narrow=" + (width - 24f < NARROW_WIDTH) + " stack=" + (width < STACK_WIDTH));
		List<Entry> entries = buildEntries();
		Entry selected = findEntry(entries, intel.getSelectedSystemId());
		// an unfound system has no detail to show - its cards would name its planets
		if (selected != null && !selected.known) selected = null;
		if (selected == null) {
			for (Entry e : entries) {
				if (e.known) { selected = e; break; }
			}
		}

		addHeader(main, width);
		addStrip(main, width, entries);
		Ledger ledger = addLedger(intel, ui, main, width, opad, entries, selected);
		nameWidths.clear();
		List<Object[]> cards = new ArrayList<Object[]>();
		if (selected != null) {
			addDetail(intel, main, width, opad, selected, cards);
		}
		// Floating buttons are anchored to siblings already laid out and added
		// LAST: the tooltip continues its flow from the most recent component,
		// so anything added after a moved button would land beside it.
		float heightBefore = main.getHeightSoFar();
		if (ledger != null) {
			int n = ledger.rows.size();
			for (int i = 0; i < n; i++) {
				Entry e = ledger.rows.get(i);
				float up = (n - i) * ROW_H - (ROW_H - 20f) / 2f;
				if (!e.known) continue; // nothing to commission or read against a place unfound
				if (e.isColony()) addPurgeButton(intel, main, ledger.table, up, e);
				if (!e.missions.isEmpty()) addMissionButton(intel, main, ledger.table, up, e, ledger.cols);
			}
		}
		for (Object[] pair : cards) {
			CustomPanelAPI card = (CustomPanelAPI) pair[0];
			MarketAPI market = (MarketAPI) pair[1];
			Entry entry = (Entry) pair[2];
			// Map and Colony sit flush with the card's right edge on the name line; the
			// size forecast moved down to line A to make room
			com.fs.starfarer.api.ui.ButtonAPI colony = intel.addGenericButton(main, COLONY_BUTTON_W,
					"Colony", BUTTON_COLONY + market.getId());
			colony.getPosition().aboveRight(card, -24f).setXAlignOffset(-8f);
			// vanilla's own colony screen, the way "View colony info" opens it - every
			// figure the card used to carry lives there, maintained by vanilla
			com.fs.starfarer.api.ui.ButtonAPI button = intel.addGenericButton(main, MAP_BUTTON_W, "Map",
					BUTTON_MAP + market.getId());
			button.getPosition().aboveRight(card, -24f).setXAlignOffset(-8f - COLONY_BUTTON_W - 6f);
		}
		main.setHeightSoFar(heightBefore);
	}

	/** What the ledger leaves behind for the floating buttons. */
	protected static class Ledger {
		com.fs.starfarer.api.ui.UIPanelAPI table;
		Cols cols;
		List<Entry> rows = new ArrayList<Entry>();
	}

	/** Open missions (accepted first, then offers) against a system, for the intel button. */
	public static List<IntelInfoPlugin> missionsForSystem(String systemId) {
		List<IntelInfoPlugin> result = new ArrayList<IntelInfoPlugin>();
		List<ThreatMissionIntel> open = new ArrayList<ThreatMissionIntel>();
		open.addAll(ThreatMissionIntel.getAccepted());
		open.addAll(ThreatMissionIntel.getPosted());
		for (ThreatMissionIntel mission : open) {
			MarketAPI market = ThreatIncData.resolveColonyMarket(mission.getMarketId());
			if (market != null && market.getStarSystem() != null
					&& systemId.equals(market.getStarSystem().getId()) && !result.contains(mission)) {
				result.add(mission);
			}
		}
		return result;
	}

	protected static void addMissionButton(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI table, float upFromBottom, final Entry e, Cols c) {
		com.fs.starfarer.api.ui.ButtonAPI button = intel.addGenericButton(main, MISSION_BUTTON_W,
				"View", BUTTON_MISSION + e.systemId);
		// the Missions column sits just left of Actions; centre the button in it
		float inset = 6f + c.w[ACTIONS] + (c.w[MISSIONS] - MISSION_BUTTON_W) / 2f;
		button.getPosition().belowRight(table, -upFromBottom).setXAlignOffset(-inset);
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 400f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				Color text = Misc.getTextColor();
				for (ThreatMissionIntel b : e.missions) {
					MarketAPI market = ThreatIncData.resolveColonyMarket(b.getMarketId());
					String target = ThreatMissionIntel.typeNoun(b.getType()) + " on "
							+ (market != null ? market.getName() : "a hive world");
					if (b.isAccepted()) {
						tooltip.addPara("Accepted " + ThreatMissionIntel.tierName(b.getTier()).toLowerCase()
								+ " - " + target + ": %s, %s days left to complete.", 3f, text, h,
								Misc.getDGSCredits(b.getReward()), "" + (int) b.daysRemaining());
					} else {
						tooltip.addPara(ThreatMissionIntel.tierName(b.getTier()) + " offer, priority "
								+ b.currentRank() + " - " + target + ": %s, %s days left to accept.", 3f,
								text, h, Misc.getDGSCredits(b.getReward()), "" + (int) b.daysRemaining());
					}
				}
				tooltip.addPara("Click to open in the intel list.", Misc.getGrayColor(), 6f);
			}
		}, button, TooltipLocation.BELOW);
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
			glColor(Misc.getPositiveHighlightColor(), alpha);
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
		title.addPara("THE THREAT WAR EFFORT", player, 0f);
		panel.addUIElement(title).inTL(48f, 4f);

		int days = (int) ThreatIncData.daysSinceStart();
		text(panel, 48f, 29f, width * 0.68f, "Cycle " + Global.getSector().getClock().getCycle()
				+ " - day " + days + " of the incursion - only what you have found is listed",
				gray, null, true);

		float bx = width - barW;
		text(panel, bx, 6f, barW * 0.5f, "Awakened", phase >= 1 ? threat : gray, Alignment.LMID, true);
		text(panel, bx + barW * 0.4f, 6f, barW * 0.6f, "Core worlds in reach",
				phase >= 3 ? neg : gray, Alignment.RMID, true);
		String label = phase >= 3 ? "PHASE 3 - Core worlds in reach"
				: phase == 2 ? "PHASE 2 - Strike-capable" : "PHASE 1 - Entrenching";
		text(panel, bx, 36f, barW, label, phase >= 3 ? neg : threat, Alignment.MID, true);

		main.addCustom(panel, 0f);
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 400f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara("Phases are capability, not calendar. Phase 2: some hive can "
						+ "stage an expedition (strike-sized, forged, fuelled, nexus intact). "
						+ "Phase 3: some hive can field a full armada against the core worlds. "
						+ "Burn their forges, cut their fuel or shrink their colonies and the "
						+ "danger level genuinely regresses.", 0f);
			}
		}, panel, TooltipLocation.BELOW);
	}

	// ---- sector strip ----

	protected static void addStrip(TooltipMakerAPI main, float width, List<Entry> entries) {
		int worlds = 0, mass = 0, swarms = 0, outbound = 0, inbound = 0;
		for (Entry e : entries) {
			worlds += e.markets.size();
			mass += e.mass;
			swarms += e.swarmsLive;
			outbound += e.outbound.size();
			inbound += e.inbound.size();
		}
		int missions = ThreatMissionIntel.getOpen().size();
		int burned = ThreatIncData.getCleansedCount();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();

		String[] labels = {"Known systems", "Hive worlds", "Mass", "Swarms",
				"Expeditions out", "Sieges in", "Missions", "Hives burned"};
		String[] values = {"" + entries.size(), "" + worlds, "" + mass, "" + swarms,
				"" + outbound, "" + inbound, "" + missions, "" + burned};
		Color[] colors = {h, h, h, h, outbound > 0 ? neg : h, inbound > 0 ? pos : h, h,
				burned > 0 ? pos : h};

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
	}

	// ---- the ledger ----

	protected static final int RANK = 0, SYSTEM = 1, THREAT = 2, WORLDS = 3, MASS = 4, VIT = 5,
			SWARMS = 6, REACH = 7, OUT = 8, IN = 9, SUPPLY = 10, CORE = 11, MISSIONS = 12, ACTIONS = 13;
	protected static final int COLS = 14;

	/** Column widths (and their offsets within a row) for a given table width. */
	protected static class Cols {
		float[] x = new float[COLS];
		float[] w = new float[COLS];
		float total;
		boolean narrow;

		Cols(float width) {
			narrow = width < NARROW_WIDTH;
			float[] frac = narrow
					? new float[] {.03f, .14f, 0f, .09f, .05f, .11f, .08f, .06f, .05f, .09f, .17f, 0f, .06f, .07f}
					: new float[] {.028f, .14f, 0f, .09f, .045f, .09f, .07f, .055f, .045f, .09f, .15f, .047f, .08f, .07f};
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
	protected static final float PURGE_BUTTON_W = 68f;
	protected static final float MISSION_BUTTON_W = 68f;

	protected static Ledger addLedger(final ThreatIncursionIntel intel, final IntelUIAPI ui,
			TooltipMakerAPI main, float width, float opad, List<Entry> entries, Entry selected) {
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		Color dark = threat.getDarkUIColor();
		Color bright = threat.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();

		main.addSectionHeading("Known infested systems - priority order", bright, dark,
				Alignment.MID, opad);
		if (entries.isEmpty()) {
			main.addPara("You have confirmed no Threat infestations. Where the swarm is - and "
					+ "how far it has spread - is %s.", opad, neg, "unknown");
			return null;
		}

		// the stock table carries the text, the clicks and the tooltips
		final Cols c = new Cols(width - 24f);
		String[] names = {"#", "System", "Threat", "Worlds", "Mass", "Vitality", "Swarms",
				"Reach", "Strikes", "Purges", "Supply", "Core", "Missions", "Actions"};
		List<Object> columns = new ArrayList<Object>();
		for (int i = 0; i < COLS; i++) {
			if (!c.has(i)) continue;
			columns.add(names[i]);
			columns.add(c.w[i]);
		}
		com.fs.starfarer.api.ui.UIPanelAPI table = main.beginTable2(threat, ROW_H, true, true, columns.toArray());
		main.makeTableItemsClickable();
		main.addTableHeaderTooltip(c.visibleIndex(RANK), "Priority: an expedition in flight, living "
				+ "systems inside its strike reach, a colony already declining, its place in the "
				+ "hive's supply chain, then mass and proximity to the core. The reason is the first "
				+ "line of the row tooltip.");
		main.addTableHeaderTooltip(c.visibleIndex(WORLDS), "The size of every hive colony in the "
				+ "system, largest first.");
		main.addTableHeaderTooltip(c.visibleIndex(MASS), "Combined size of the hive colonies - "
				+ "the swarm's fabrication mass.");
		main.addTableHeaderTooltip(c.visibleIndex(VIT), "Size-weighted hive vitality (fabrication x "
				+ "supply); the exact figure is in the row tooltip. Ticks mark the decline threshold "
				+ "and the full-growth mark. Below the first a colony declines - the only way a "
				+ "Threat colony dies.");
		main.addTableHeaderTooltip(c.visibleIndex(SWARMS), "Defense Swarms in orbit / the garrison "
				+ "the nexus builds toward; '+n' are swarms mustered for an expedition, still "
				+ "fabricating in orbit. Green arrow: a nexus here is growing replacements. Red "
				+ "arrow: a garrison is short and its nexus is silenced - nothing replaces the "
				+ "losses. Dash: every garrison is full.");
		main.addTableHeaderTooltip(c.visibleIndex(REACH), "How far its expeditions reach, bought "
				+ "with the fuel the staging colony draws from the hive network.");
		main.addTableHeaderTooltip(c.visibleIndex(OUT), "Threat expeditions staged from this "
				+ "system: strikes in flight and seeding swarms in transit. Details in the row tooltip.");
		main.addTableHeaderTooltip(c.visibleIndex(IN), "Navies sailing against it - the crests of "
				+ "siege expeditions, task forces and your own commissioned expedition. Details in "
				+ "the row tooltip.");
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
		if (c.has(CORE)) {
			main.addTableHeaderTooltip(c.visibleIndex(CORE), "Light-years to the nearest major "
					+ "inhabited world.");
		}
		main.addTableHeaderTooltip(c.visibleIndex(MISSIONS), "Defense-board missions against this "
				+ "system - offers and the ones you hold. The button opens them in the intel list; "
				+ "details on hover.");
		main.addTableHeaderTooltip(c.visibleIndex(ACTIONS), "Purge: commission a siege expedition "
				+ "against this system from your nearest colony with a military structure. Hover "
				+ "the button for the price.");

		final List<Object> rowObjects = new ArrayList<Object>();
		final List<Entry> rowEntries = new ArrayList<Entry>();
		int rank = 0;
		for (final Entry e : entries) {
			rank++;
			boolean isSelected = selected != null && selected.systemId.equals(e.systemId);
			List<Object> cells = new ArrayList<Object>();
			cell(cells, Alignment.MID, h, "" + rank);
			cell(cells, Alignment.LMID, isSelected ? Misc.getBasePlayerColor() : (e.known ? bright : gray),
					main.shortenString(e.displayName(), c.w[SYSTEM] - 2f * CELL_PAD));
			if (c.has(THREAT)) cell(cells, Alignment.LMID, e.reasonColor, e.chip);

			if (!e.isColony()) {
				cell(cells, Alignment.MID, gray,
						ThreatIncData.STAGE_COLONIZING.equals(e.stage) ? "colonizing" : "seeded");
				cell(cells, Alignment.MID, gray, "-");
				String eta = e.inbound.isEmpty() ? "-" : e.inbound.get(0).eta.equals("-")
						? e.inbound.get(0).status : "planetfall " + e.inbound.get(0).eta;
				cell(cells, Alignment.LMID, gray, eta);
				cell(cells, Alignment.MID, gray, "-");
			} else {
				cell(cells, Alignment.MID, h, sizeDigits(e, c));
				cell(cells, Alignment.MID, h, "" + e.mass);
				cell(cells, Alignment.LMID, gray, ""); // the bar is drawn by the overlay
				String swarms = e.swarmsLive + "/" + e.swarmsDesired
						+ (e.swarmsMustered > 0 ? " +" + e.swarmsMustered : "");
				cell(cells, Alignment.MID, e.swarmsLive == 0 ? pos
						: (e.swarmsMustered > 0 ? neg : text), swarms);
			}
			cell(cells, Alignment.MID, e.reachLY > 0f ? (e.inReach.isEmpty() ? h : neg) : gray,
					e.reachLY > 0f ? (int) e.reachLY + " ly" : "-");
			cell(cells, Alignment.MID, e.outbound.isEmpty() ? gray : neg,
					e.outbound.isEmpty() ? "-" : "" + e.outbound.size());
			cell(cells, Alignment.MID, gray, e.inbound.isEmpty() ? "-" : ""); // crests by the overlay
			cell(cells, Alignment.MID, gray, e.supplies.isEmpty() ? "-" : ""); // icons by the overlay
			if (c.has(CORE)) {
				cell(cells, Alignment.MID, text, e.known && e.coreLY >= 0f ? (int) Math.ceil(e.coreLY) + " ly" : "-");
			}
			cell(cells, Alignment.MID, gray, e.missions.isEmpty() ? "-" : ""); // the Missions button sits here
			cell(cells, Alignment.MID, gray, ""); // the Purge button sits here

			Object row = isSelected ? main.addRowWithGlow(cells.toArray()) : main.addRow(cells.toArray());
			main.addTooltipToAddedRow(new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 440f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					addRowTooltip(tooltip, e, c.narrow);
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
		return ledger;
	}

	protected static void addPurgeButton(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI table, float upFromBottom, final Entry e) {
		final InfestedSystemIntel.CommissionQuote q = InfestedSystemIntel.quote(e.systemId);
		com.fs.starfarer.api.ui.ButtonAPI button = intel.addGenericButton(main, PURGE_BUTTON_W,
				"Purge", BUTTON_COMMISSION + e.systemId);
		int credits = (int) Global.getSector().getPlayerFleet().getCargo().getCredits().get();
		final boolean enabled = ThreatIncConfig.commissionEnabled() && q != null && q.base != null
				&& q.existing == null && credits >= q.cost;
		button.setEnabled(enabled);
		button.setShowTooltipWhileInactive(true);
		button.getPosition().belowRight(table, -upFromBottom).setXAlignOffset(-6f);
		final int creditsNow = credits;
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 380f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				Color neg = Misc.getNegativeHighlightColor();
				Color gray = Misc.getGrayColor();
				if (!ThreatIncConfig.commissionEnabled()) {
					tooltip.addPara("Commissioned expeditions are disabled in the mod settings.", gray, 0f);
					return;
				}
				if (q == null) {
					tooltip.addPara("Nothing to besiege here yet.", gray, 0f);
					return;
				}
				if (q.base == null) {
					tooltip.addPara("None of your colonies with a military structure (Patrol HQ, "
							+ "Military Base, or High Command) can reach this system - expeditions "
							+ "range %s light-years per unit of fuel they carry, the smaller of the "
							+ "fuel available at the colony and what its fleets can lift, the same "
							+ "rule the swarm's strikes run on.", 0f, h,
							"" + (int) ThreatIncConfig.strikeLYPerFuel());
					return;
				}
				float dist = Misc.getDistanceLY(q.base.getStarSystem().getLocation(),
						q.system.getLocation());
				tooltip.addPara("Commission a %s siege expedition from %s (" + (int) Math.ceil(dist)
						+ " light-years out) against the " + q.targets.size() + " Threat "
						+ (q.targets.size() > 1 ? "colonies" : "colony") + " here for %s. "
						+ (q.anyGarrisoned ? "Includes escorts to fight through the live Defense "
								+ "Swarms. " : "")
						+ "It runs the siege playbook autonomously and reports back when done; "
						+ "the fee is paid up front and not refunded.", 0f, h,
						q.fleetSizes.size() + "-fleet", q.base.getName(), Misc.getDGSCredits(q.cost));
				if (q.existing != null) {
					tooltip.addPara("An expedition you commissioned is already operating against "
							+ "this system.", gray, 6f);
				} else if (creditsNow < q.cost) {
					tooltip.addPara("You cannot afford the fee - you have %s.", 6f, neg,
							Misc.getDGSCredits(creditsNow));
				}
			}
		}, button, TooltipLocation.BELOW);
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
	 * the vitality bar and trend glyph, and the inbound crests. Uses each row
	 * component's live position and scales the column budget to the drawn row
	 * width, so it follows however the table lays itself out and never
	 * intercepts the table's clicks.
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

				// inbound crests, centred in the Purges column
				int crests = Math.min(3, e.inbound.size());
				if (crests > 0) {
					float colX = x + c.x[IN] * scale;
					float colW = c.w[IN] * scale;
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

				if (!e.isColony()) continue;

				// swarm production glyph at the right edge of the Swarms column
				trend(x + (c.x[SWARMS] + c.w[SWARMS]) * scale - 10f, midY, e.swarmTrend, alphaMult);

				// vitality bar fills the column, trend glyph at its right end
				float vx = x + c.x[VIT] * scale + CELL_PAD;
				float vw = c.w[VIT] * scale - 2f * CELL_PAD - 18f;
				bar(vx, midY - 4f, vw, 8f, e.health, healthColor(e.health),
						new float[] {ThreatIncConfig.declineHealthThreshold(),
								ThreatIncConfig.growthFullHealth()}, alphaMult);
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
		if (e.known) {
			tooltip.addSectorMap(w, Math.round(w / 1.8f), e.system, 0f);
			tooltip.addSpacer(10f);
		}
		String title = e.known ? e.system.getNameWithNoType()
				: "Unknown system - not yet found; enter it to place it on the map";
		if (e.isColony()) {
			title += " - " + e.markets.size() + (e.markets.size() == 1 ? " hive world" : " hive worlds")
					+ ", mass " + e.mass + ", vitality " + (int) (e.health * 100f) + "%";
		}
		tooltip.addPara(title, Global.getSector().getFaction(Factions.THREAT).getBrightUIColor(), 6f);
		tooltip.addPara(e.chip + " - " + e.reason, e.reasonColor, 4f);
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
		if (!e.inReach.isEmpty()) {
			tooltip.addPara("Within strike reach: %s.", 4f, text, h, join(
					e.inReach.size() > 6 ? e.inReach.subList(0, 6) : e.inReach)
					+ (e.inReach.size() > 6 ? " and " + (e.inReach.size() - 6) + " more" : ""));
		}
		List<String> geo = new ArrayList<String>();
		// distances place a system on the map as surely as its name; none for one unfound
		if (e.known && e.coreLY >= 0f) geo.add((int) Math.ceil(e.coreLY) + " ly from the nearest major world");
		if (e.known && e.playerLY >= 0f) geo.add((int) Math.ceil(e.playerLY) + " ly from your nearest colony");
		geo.add((int) ThreatIncData.daysInStage(e.systemId) + " days in its current stage");
		tooltip.addPara(join(geo) + ".", gray, 4f);
		tooltip.addPara("Click to select", gray, 6f);
	}

	// ---- the drill-down: colony cards across the full width ----

	protected static void addDetail(ThreatIncursionIntel intel, TooltipMakerAPI main,
			float width, float opad, Entry e, List<Object[]> cardsOut) {
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		Color dark = threat.getDarkUIColor();
		Color bright = threat.getBrightUIColor();

		StringBuilder title = new StringBuilder(e.system.getNameWithNoType());
		if (e.isColony()) {
			title.append(" - ").append(e.markets.size())
					.append(e.markets.size() == 1 ? " hive world" : " hive worlds")
					.append(" - mass ").append(e.mass);
		} else {
			title.append(" - ").append(ThreatIncData.STAGE_COLONIZING.equals(e.stage)
					? "seeding swarm in transit" : "marked for seeding");
		}
		if (e.coreLY >= 0f) title.append(" - ").append((int) Math.ceil(e.coreLY)).append(" ly from the core");
		if (e.playerLY >= 0f) title.append(" - ").append((int) Math.ceil(e.playerLY)).append(" ly from your nearest colony");
		main.addSectionHeading(title.toString(), bright, dark, Alignment.MID, opad);

		if (e.isColony()) {
			addCards(main, width, e, cardsOut);
		} else {
			addNonColonyBlock(main, opad, e);
		}
		main.addSpacer(opad);
	}

	protected static void addNonColonyBlock(TooltipMakerAPI info, float opad, Entry e) {
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
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
		final float health = ThreatColonyManager.computeHealth(market);
		final float declineT = ThreatIncConfig.declineHealthThreshold();
		final List<Industry> organs = organsOf(market);
		final boolean declining = health < declineT;
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
				outline(x, y, w, h, declining ? Misc.getPositiveHighlightColor()
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
		Color pos = Misc.getPositiveHighlightColor();
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
		String[] forecast = forecastParts(market, health);
		// size forecast, right-aligned on line B beside the fuel bill (line A needs its full width)
		textHl(card, cardW - rightW - 8f, 45f, rightW, forecast[0], gray,
				new Color[] {h, declining ? neg : health >= ThreatIncConfig.growthFullHealth() ? pos : h},
				new String[] {forecast[1], forecast[2]}, Alignment.RMID, true);

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

		// line A, above the organs: vitality, garrison, reach or decline
		int live = ThreatColonyManager.countLiveGarrison(market.getId());
		int desired = ThreatColonyManager.desiredGarrisonCount(market);
		int nominal = ThreatColonyManager.desiredGarrison(market.getSize()).length;
		List<String> hlA = new ArrayList<String>();
		List<Color> hlcA = new ArrayList<Color>();
		StringBuilder a = new StringBuilder("Vitality %s    Swarms %s");
		hlA.add((int) (health * 100f) + "%");
		hlcA.add(healthColor(health));
		// "3/3 of 5": the hull shortage caps what the nexus grows toward, and a
		// full garrison at the cap must not read as a full garrison. "(+2
		// inbound)": reinforcements flying in from sibling colonies - on station
		// counts only what has landed, so a bare colony with help en route must
		// not read as abandoned
		int inbound = ThreatColonyManager.inboundReinforcements(market.getId());
		hlA.add(live + "/" + desired + (desired < nominal ? " of " + nominal : "")
				+ (inbound > 0 ? " (+" + inbound + " inbound)" : ""));
		hlcA.add(live == 0 ? pos : desired < nominal ? neg : text);
		if (declining) {
			a.append("    Decline %s");
			hlA.add((int) (ThreatIncData.declineProgress(market.getId()) * 100f) + "%");
			hlcA.add(neg);
		} else if (market.getSize() >= ThreatIncConfig.strikeMinSize()) {
			float range = ThreatColonyManager.fuelRangeLY(market);
			a.append("    Reach %s");
			hlA.add(range > 0f ? (int) range + " ly" : "grounded");
			hlcA.add(range > 0f ? neg : pos);
		}
		textHl(card, 8f, 27f, cardW - 16f, a.toString(), gray, hlcA.toArray(new Color[0]),
				hlA.toArray(new String[0]), Alignment.LMID, true);

		// line B: the fuel bill
		int baseCost = MarketCMD.getBombardmentCost(market, Global.getSector().getPlayerFleet());
		int defense = (int) MarketCMD.getDefenderStr(market, true);
		int sat = Math.max(2, Math.round(baseCost * ThreatIncConfig.hiveBombardCostMult()));
		int tac = Math.max(2, Math.round(baseCost * ThreatIncConfig.hiveTacCostFraction()));
		textHl(card, 8f, 45f, cardW - 16f - rightW, "Def %s   sat %s fuel   tac %s", gray,
				new Color[] {text, neg, text},
				new String[] {Misc.getWithDGS(defense), Misc.getWithDGS(sat), Misc.getWithDGS(tac)},
				Alignment.LMID, true);

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

	/** "s6 in 266 d" / "stalled"		return card;
	}

	/**
	 * The size line, right-aligned on the card: {format, from, to} with the two
	 * highlights - "s5 -> s6 ~105 d", "s5 -> s4 ~22 d" (declining), "s8 max",
	 * "s5 stalled".
	 */
	protected static String[] forecastParts(MarketAPI market, float health) {
		String id = market.getId();
		String from = "s" + market.getSize();
		if (health < ThreatIncConfig.declineHealthThreshold()) {
			float[] projection = ThreatColonyManager.projectDecline(market, health);
			String to = "s" + (market.getSize() - 1)
					+ (projection[0] > 0f ? " ~" + (int) projection[0] + " d" : "");
			return new String[] {"%s -> %s", from, to};
		}
		float growthMult = ThreatColonyManager.growthMultFor(health);
		int cap = Math.min(ThreatIncConfig.colonyMaxSize(), Misc.getMaxMarketSize(market));
		if (market.getSize() >= cap) return new String[] {"%s %s", from, "max"};
		if (growthMult <= 0f) return new String[] {"%s %s", from, "stalled"};
		float daysPerLevel = ThreatIncConfig.colonyGrowthBaseDays() * market.getSize()
				* IncursionManager.timeScale();
		float remaining = (daysPerLevel - ThreatIncData.growthProgressDays(id)) / growthMult;
		return new String[] {"%s -> %s", from, "s" + (market.getSize() + 1) + " ~"
				+ Math.max(1, (int) remaining) + " d"};
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
		if (health < ThreatIncConfig.declineHealthThreshold()) return Misc.getNegativeHighlightColor();
		if (health < ThreatIncConfig.growthFullHealth()) return Misc.getHighlightColor();
		return Misc.getPositiveHighlightColor();
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
