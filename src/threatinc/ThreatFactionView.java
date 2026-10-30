package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipLocation;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.ui.UIPanelAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The war board's FACTION VIEW (docs/strategy-layer.md): pick a mobilised
 * faction from the selector row and the board shows that faction's war
 * instead of the hive's - its colonies with their reserves, defenses and
 * strikes inbound; its fleets (task forces, expeditions, convoys, standing
 * orders). For the player's OWN
 * faction every fleet action lives here as a button: guard a colony, stage
 * materiel to it, hunt a hive's swarms, send a siege, recall a fleet.
 * Another faction's view is a window, not a console (docs/player-aid.md): its
 * navy is its own, and the buttons send the player's AID from the player's
 * colonies - Defend, Aid, Strike - paid from those colonies' reserves and
 * sized by what they can field; no credits change hands.
 *
 * <p>Follows the platform rules in docs/intel-ui-platform.md: stock tables
 * for text and clicks, floating buttons anchored to the table panels and
 * added LAST, custom panels for drawing only.
 */
public class ThreatFactionView {

	public static final String BUTTON_FACTION = "threatinc_board_faction:";
	public static final String BUTTON_GUARD = "threatinc_board_guard:";
	public static final String BUTTON_STAGE = "threatinc_board_stage:";
	public static final String BUTTON_HUNT = "threatinc_board_hunt:";
	public static final String BUTTON_SIEGE = "threatinc_board_siege:";
	/** A razing expedition against a hive system's worlds (payload: factionId:systemId). */
	public static final String BUTTON_BOMBARD = "threatinc_board_bombard:";
	/** Sends the player's own scouting party to a known hive system (ThreatScouts.playerRecon; payload: factionId:systemId). */
	public static final String BUTTON_SCOUT = "threatinc_board_scout:";
	/** Picks the siege-force tier (payload: the tier index 0/1/2); a view toggle, not an order - no Confirm. */
	public static final String BUTTON_SIEGE_TIER = "threatinc_board_siegetier:";
	/** The Attack's Fuel ladder (phase D): the passage, half the fuel above the floor, all. */
	public static final String BUTTON_FUEL_TIER = "threatinc_board_fueltier:";
	/** The Attack's Marines ladder: none, half the pool, all. */
	public static final String BUTTON_MARINE_TIER = "threatinc_board_marinetier:";
	/** Sets one of THE LEVERS (payload: ThreatIncursionIntel.LEVER_ index, then the tier 0/1/2; 2026-10-28); a view toggle, not an order. */
	public static final String BUTTON_LEVER = "threatinc_board_lever:";
	public static final String BUTTON_RECALL = "threatinc_board_recall:";
	/**
	 * The Staging group's Recall (the user, 2026-10-30 21:00): on a colony row, every fleet, expedition,
	 * task force and convoy sent from it comes home; on a Front row, the front is lifted and the fleets
	 * over the hive come home (payload: factionId:marketId). The fleets table keeps its per-fleet Recall.
	 */
	public static final String BUTTON_RECALL_ALL = "threatinc_board_recall_all:";
	/** One fleet of an expedition or task force is detached to hunt (payload: factionId:huntKey). */
	public static final String BUTTON_DETACH = "threatinc_board_detach:";
	/** Supply run to the faction's front on a hive world (payload: factionId:hiveMarketId). */
	public static final String BUTTON_SUPPLY = "threatinc_board_supply:";
	/** Withdrawal run for the faction's front on a hive world. */
	public static final String BUTTON_PULLOUT = "threatinc_board_pullout:";
	/** Support sortie holding and besieging a world's orbit (payload: factionId:marketId; the id keeps the order's old name). */
	public static final String BUTTON_SUPPORT = "threatinc_board_escort:";
	/** Defend sortie holding a world's orbit, bombarding only while the front cannot hold (payload: factionId:marketId). */
	public static final String BUTTON_DEFEND = "threatinc_board_defend:";
	/** The player's own front pushes on the next stratum (payload: factionId:marketId). */
	public static final String BUTTON_PUSH = "threatinc_board_push:";
	/** The player's own front breaks off and holds (payload: factionId:marketId). */
	public static final String BUTTON_ENTRENCH = "threatinc_board_entrench:";
	/** Player aid: a task force from a player colony guards another faction's colony (payload: factionId:marketId). */
	public static final String BUTTON_AID_DEFEND = "threatinc_board_aiddefend:";
	/** The Send button on an NPC faction's need row (2026-10-30): "factionId:marketId:good" - a convoy of the good at its lever, or Defend for a fleets need. */
	public static final String BUTTON_AID_NEED = "threatinc_board_aidneed:";
	/** The Requests toggles' Colony button (2026-10-31): the next Domain entry's id; no Confirm, ThreatIncursionIntel sets it. */
	public static final String BUTTON_REQ_TARGET = "threatinc_board_reqtarget:";
	/** A Requests toggle (2026-10-31): "good:targetId" - flips the call for the good at the entry; no Confirm. */
	public static final String BUTTON_REQ_TOGGLE = "threatinc_board_reqtoggle:";
	/** Accepts an offer of aid (payload player:offerId): the convoy sails now. */
	public static final String BUTTON_REQ_ACCEPT = "threatinc_board_reqaccept:";
	/** Dismisses an offer of aid (payload offerId; the button reads Dismiss, 2026-10-31): cleared, the faction offers nothing more on that call; no Confirm. */
	public static final String BUTTON_REQ_REJECT = "threatinc_board_reqreject:";
	/** Cancels an accepted offer at sea (payload player:offerId): the convoy turns home, nothing charged. */
	public static final String BUTTON_REQ_CANCEL = "threatinc_board_reqcancel:";
	/** Player aid: a task force from a player colony holds a hive system's door (payload: factionId:systemId). */
	public static final String BUTTON_AID_STRIKE = "threatinc_board_aidstrike:";
	// the colony rows' Aid (BUTTON_AID_SUPPLY), the fronts' Reinforce / Aid (BUTTON_REINFORCE) and the
	// Request (BUTTON_REQUEST) went on 2026-10-29 evening: a Transfer reinforces an own world, its
	// shortfall asks for aid (a confirm box, ThreatIncursionIntel.buttonPressConfirmed), an ally's world is held by Defend on its front row
	/** The marines of the player's own colony push: counter-attacks on the cadence (payload player:marketId; 2026-10-17). */
	public static final String BUTTON_DEFEND_PUSH = "threatinc_board_defend_push:";
	/** The marines of the player's own colony hold (no push, cover rises). */
	public static final String BUTTON_DEFEND_ENTRENCH = "threatinc_board_defend_entrench:";
	/** Build an outpost over a purged world (payload: factionId:planetId). */
	public static final String BUTTON_OUTPOST = "threatinc_board_outpost:";
	/** The player mobilises their own faction (payload: factionId). */
	public static final String BUTTON_MOBILISE = "threatinc_board_mobilise:";
	/** The player stands their own faction down (payload: factionId). */
	public static final String BUTTON_STAND_DOWN = "threatinc_board_standdown:";

	/** Selector value for the hive view. */
	public static final String VIEW_THREAT = "threat";

	/** Table row id prefix for a faction colony (click = the colony screen). */
	public static final String ROW_MARKET = "market:";

	public static final float SMALL_BUTTON_W = 54f;
	public static final float SELECTOR_BUTTON_W = 130f;

	// ------------------------------------------------------------------
	// selector row (both views)
	// ------------------------------------------------------------------

	/**
	 * The factions the selector offers: the hive, the player's own faction
	 * whenever they hold a colony (mobilised or not - it is where they
	 * mobilise from), then every mobilised NPC faction.
	 */
	public static List<String> selectorIds() {
		List<String> ids = new ArrayList<String>();
		ids.add(VIEW_THREAT);
		List<String> warring = ThreatWarState.warFactionIds();
		if (warring.contains(Factions.PLAYER) || ThreatWarState.playerMayMobilise()) {
			ids.add(Factions.PLAYER);
		}
		for (String id : warring) {
			if (Factions.PLAYER.equals(id)) continue;
			FactionAPI faction = Global.getSector().getFaction(id);
			if (faction == null || faction.isNeutralFaction()) continue; // no tab for a pseudo-faction
			ids.add(id);
		}
		// the debug board (the user, 2026-10-30): what the swarm is doing and planning, in debug mode only
		if (ThreatIncConfig.debugMode()) ids.add(ThreatDebugBoard.VIEW_DEBUG);
		return ids;
	}

	/** Height reserved in the flow for the selector row (button plus padding). */
	public static final float SELECTOR_ROW_H = 30f;

	/**
	 * Reserves the selector row's space in the flow, right after the strip.
	 * Returns whether a row will be drawn (only once somebody has mobilised).
	 */
	public static boolean reserveSelectorRow(TooltipMakerAPI main) {
		if (selectorIds().size() <= 1) return false;
		main.addSpacer(SELECTOR_ROW_H);
		return true;
	}

	/**
	 * The selector buttons, added LAST like every floating button (platform
	 * trap 2: an in-flow button moved sideways drags everything after it
	 * along - verified in-game 2026-09-04, twice). Anchored below the totals
	 * strip, a sibling, into the space reserveSelectorRow left; the caller
	 * restores the flow height afterward. The current selection is drawn
	 * disabled, with a tooltip saying so.
	 */
	public static void addSelector(ThreatIncursionIntel intel, TooltipMakerAPI main, float width,
			String selected, UIComponentAPI anchor) {
		List<String> ids = selectorIds();
		if (ids.size() <= 1 || anchor == null) return;
		float w = Math.min(SELECTOR_BUTTON_W, (width - 4f * (ids.size() - 1)) / ids.size());
		ButtonAPI prev = null;
		for (String id : ids) {
			String label = VIEW_THREAT.equals(id) ? "The Threat"
					: ThreatDebugBoard.VIEW_DEBUG.equals(id) ? "Debug" : ThreatWarState.displayName(id);
			ButtonAPI button = intel.addGenericButton(main, w, main.shortenString(label, w - 12f),
					BUTTON_FACTION + id);
			boolean current = id.equals(selected == null ? VIEW_THREAT : selected);
			button.setEnabled(!current);
			button.setShowTooltipWhileInactive(true);
			if (prev == null) {
				button.getPosition().belowLeft(anchor, 6f);
			} else {
				button.getPosition().rightOfTop(prev, 4f);
			}
			final String fid = id;
			main.addTooltipTo(new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 360f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					if (VIEW_THREAT.equals(fid)) {
						tooltip.addPara("The hive's war: known infested systems, their colonies "
								+ "and the operations against them.", 0f);
						return;
					}
					if (ThreatDebugBoard.VIEW_DEBUG.equals(fid)) {
						tooltip.addPara("Debug mode: what the swarm is doing and planning - stance, "
								+ "offensive, shifts, strikes, every system and world. Nothing is hidden by the fog.", 0f);
						return;
					}
					ThreatWarState.FactionWar war = ThreatWarState.get(fid);
					tooltip.addPara(ThreatWarState.displayName(fid) + " - mobilised against the "
							+ "Threat" + (war != null ? " after " + war.strikesSuffered
							+ (war.strikesSuffered == 1 ? " strike" : " strikes") : "")
							+ ". Colonies, reserves, fleets and orders.", 0f);
				}
			}, button, TooltipLocation.BELOW);
			prev = button;
		}
	}

	// ------------------------------------------------------------------
	// the view
	// ------------------------------------------------------------------

	/** One colony row's data. */
	protected static class ColonyRow {
		MarketAPI market;
		boolean military;
		/** A functional Waystation (ThreatReserves.hasDepot): without it nothing sails from or to it. */
		boolean depot;
		/** The hive this world is the staging base for (ThreatConvoys.stagingHive), else null. */
		StarSystemAPI staging;
		float stagingLY;
		/** For a world that is not a staging base: the staging base in convoy range its spare ships to. */
		MarketAPI feeds;
		float feedsLY;
		int threats;
	}

	/** One fleet row's data. */
	protected static class FleetRow {
		String kind;
		String name;
		String task;
		String status;
		String strength;
		String eta = "-";
		Color color;
		Object rowId;
		/**
		 * Recall payload: "tf:i", "purge:i", "convoy:i", "order:i", "aidorder:i",
		 * "aidconvoy:i" - or, for one fleet of a group, "tffleet:i:fleetId" /
		 * "purgefleet:i:fleetId" (2026-09-06: a row per fleet, so some can be
		 * recalled and others left).
		 */
		String recallKey;
		/** Set on a group fleet's row that can be detached to hunt: the same key. */
		String huntKey;
		/** The hive system a detached fleet would hunt in. */
		StarSystemAPI huntSystem;
		/** A player aid fleet shown in another faction's view: the player may recall it. */
		boolean playerAid;
		/** A player aid guard credited on arrival: it serves its term, no Recall (the user, 2026-10-31). */
		boolean paid;
	}

	/** Renders the whole faction view, floating buttons included; returns nothing more to add. */
	public static void render(ThreatIncursionIntel intel, TooltipMakerAPI main, float width,
			float opad, String factionId) {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction != null && faction.isPlayerFaction() && !ThreatWarState.isAtWar(factionId)) {
			renderUnmobilised(intel, main, opad, faction);
			return;
		}
		if (faction == null || !ThreatWarState.isAtWar(factionId)) {
			main.addPara("That faction is no longer mobilised.", Misc.getGrayColor(), opad);
			return;
		}
		Color dark = faction.getDarkUIColor();
		Color bright = faction.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		String name = ThreatWarState.displayName(factionId);
		ThreatWarState.FactionWar war = ThreatWarState.get(factionId);
		boolean mayOrder = ThreatFleetOrders.canPlayerOrder(faction);
		String blocked = ThreatFleetOrders.orderBlockReason(faction);
		// another faction's view is a window, not a console (docs/player-aid.md):
		// its navy is its own, and the buttons offer the player's aid instead
		boolean own = faction.isPlayerFaction();
		ThreatNotice.Reason aidBlocked = own ? null : ThreatAid.canAid(faction);
		boolean mayAid = !own && aidBlocked == null;
		// the swarm as this faction last saw it (ThreatIntel, the fog of war): its
		// hunts pick their worlds from its reports
		String observer = ThreatIntel.observerOf(faction);

		// ---- heading and the reserve totals ----
		StringBuilder title = new StringBuilder(name).append(" - mobilised");
		if (war != null) {
			int days = (int) Global.getSector().getClock().getElapsedDaysSince(war.enteredTimestamp);
			// a record with no usable timestamp (injected, or pre-dating the
			// layer) just reads "mobilised"
			if (war.enteredTimestamp != 0 && days >= 0 && days < 36500) {
				title.append(" ").append(days).append(days == 1 ? " day ago" : " days ago");
			}
			title.append(" - ").append(war.strikesSuffered)
					.append(war.strikesSuffered == 1 ? " strike suffered" : " strikes suffered");
		}
		float grudge = ThreatAlarm.grudge(factionId);
		if (grudge > 0f) {
			title.append(" - swarm grudge ").append(String.format("%.1f", grudge))
					.append(" (strike weight x").append(String.format("%.1f",
							ThreatAlarm.targetMult(factionId))).append(")");
		}
		main.addSectionHeading(title.toString(), bright, dark, Alignment.MID, opad);

		// the faction's overall strength in fleet points, the calculation in the tooltip (the user,
		// 2026-10-24: "an overall score on each human factions board indicating their overall strength")
		final Strength str = strengthOf(factionId);
		// the grand fleet (the user, 2026-10-26): one line, what is true now
		if (ThreatIncConfig.npcGrandFleet() && !com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER.equals(factionId)) {
			String[] gf = ThreatGrandFleets.boardLine(factionId);
			if (gf != null) main.addPara(gf[0], opad, text, h, java.util.Arrays.copyOfRange(gf, 1, gf.length));
		}
		main.addPara("Strength %s = %s navy + %s army + %s depots", opad, text, h,
				ThreatWarBoard.n(str.total()), ThreatWarBoard.n(str.navy()), ThreatWarBoard.n(str.army()), ThreatWarBoard.n(str.depots()));
		main.addTooltipToPrevious(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 520f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color h = Misc.getHighlightColor();
				tooltip.addPara("Navy %s FP = %s free hulls + %s at sea.", 0f, h,
						ThreatWarBoard.n(str.navy()), ThreatWarBoard.n(str.freeFP), ThreatWarBoard.n(str.atSeaFP));
				tooltip.addPara("Army %s = %s ground strength (%s manned garrisons + %s marines beyond their crews on %s colonies) / %s troops a point.", 4f, h,
						ThreatWarBoard.n(str.army()), ThreatWarBoard.n(str.garrisons + str.marineDefence), ThreatWarBoard.n(str.garrisons),
						ThreatWarBoard.n(str.marineDefence), ThreatWarBoard.n(str.colonies), ThreatWarBoard.n(str.troopsPerPoint));
				tooltip.addPara("Depots %s FP of sieges = the lesser of %s supplies / %s a point = %s and %s fuel / (%s a point-ly x %s ly) = %s.", 4f, h,
						ThreatWarBoard.n(str.depots()), ThreatWarBoard.n(str.supplies), ThreatWarBoard.n(str.suppliesPerPoint),
						ThreatWarBoard.n(str.suppliesFP()), ThreatWarBoard.n(str.fuel), ThreatWarBoard.n(str.fuelPerPointLY),
						ThreatWarBoard.n(str.rangeLY), ThreatWarBoard.n(str.fuelFP()));
			}
		}, TooltipLocation.BELOW);

		List<MarketAPI> markets = ThreatReserves.marketsOf(factionId);
		// the totals sit at the foot of the colony table and the rules on the
		// buttons; up here only the one reason nothing can be ordered, if any
		if (own && !mayOrder && blocked != null) main.addPara(blocked, gray, opad);
		else if (!own && aidBlocked != null) main.addPara(aidBlocked.toString(), gray, opad);
		// an NPC's war council: its strategy, the focus and how long it has held it
		String strategy = ThreatWarCouncil.strategyLine(factionId);
		if (strategy != null) {
			String sname = ThreatWarCouncil.strategyName(ThreatWarCouncil.strategy(factionId));
			LabelAPI line = main.addPara(strategy, text, opad);
			line.setHighlight(sname);
			line.setHighlightColors(h);
		}

		if (own) {
			// the player's faction stands down by choice alone (docs/strategy-layer.md)
			ButtonAPI standDown = intel.addGenericButton(main, SELECTOR_BUTTON_W, "Stand down",
					BUTTON_STAND_DOWN + factionId);
			standDown.setShowTooltipWhileInactive(true);
			main.addTooltipTo(new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 360f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					tooltip.addPara("Lift the War footing from every colony of yours. Reserves stop "
							+ "banking and are kept as they stand. Mobilise again here any time.", 0f);
				}
			}, standDown, TooltipLocation.BELOW);
		}

		// ---- requests for aid (the user, 2026-10-29 evening: "a table at the top of each faction board
		// other than threat, listing outstanding requests"): the player's standing calls, a row a part -
		// every answer on the player's tab, this faction's own on its tab; the toggles and the offers' buttons float in last ----
		Requests requests = addRequests(intel, main, width, opad, faction, own);

		// ---- ground fronts: this faction's landings, and the swarm's on its worlds,
		// from its own side, above its world list (user, 2026-10-18) ----
		ThreatWarBoard.Fronts fronts = ThreatWarBoard.addFronts(intel, main, width, opad,
				ThreatWarBoard.frontRowsFor(factionId), faction);

		// ---- the Domain (the user, 2026-10-30 evening: "colony reserves tables should just be renamed to
		// Domain"): the faction's colonies, then - on the player's tab - the hive worlds its landings are
		// attacking (Deploy and Transfer serve the front), the purged worlds in reach (an Outpost button
		// raises one; their own table under the board until then), its outposts, and the Total row ----
		List<ColonyRow> rows = colonyRows(markets);
		UIPanelAPI colonyTable = null;
		List<ThreatWarBoard.FrontRow> attacked = own ? attackedRows() : new ArrayList<ThreatWarBoard.FrontRow>();
		List<com.fs.starfarer.api.campaign.PlanetAPI> purged = own && ThreatIncConfig.outpostsEnabled()
				? purgedInReach(faction) : new ArrayList<com.fs.starfarer.api.campaign.PlanetAPI>();
		List<ThreatOutposts.Outpost> outposts = new ArrayList<ThreatOutposts.Outpost>();
		// every row the table holds, the Total row included: the floating buttons count up from its foot
		int tableRows = 0;
		if (!rows.isEmpty()) {
			main.addSectionHeading("Domain", bright, dark, Alignment.MID, opad);
			// the convoy-load selector sits in a reserved row under the heading;
			// its buttons float in later (addConvoyTierSelector)
			main.addSpacer(SELECTOR_ROW_H);
			float tw = width - 24f;
			// Actions must hold two floating buttons (Defend + Aid = 114 px with
			// gaps): at .08 the Aid button used to overlap the Strikes column
			// Needs (2026-10-23, ThreatNeeds): the colony's most urgent purpose; the player's own rows hold
			// three buttons (Request + Supplies + Fleet = 186 px with gaps), an ally's two
			// the user (2026-10-30): Hulls, and Fleet - the points deployed over the colony - after Status;
			// the Convoys column went (its staging route is in the row tooltip); the buttons column holds
			// Recall + Transfer since 2026-10-31 (Deploy went: the Fleet lever makes Transfer deploy), so it
			// narrows and Status widens
			float[] frac = own
					? new float[] {.11f, .04f, .07f, .06f, .07f, .06f, .06f, .06f, .06f, .04f, .17f, .07f, .13f}
					: new float[] {.13f, .04f, .07f, .06f, .07f, .06f, .06f, .06f, .06f, .05f, .17f, .07f, .10f};
			// the user (2026-10-11): under the hull pool the column is the hulls available, nothing else
			String[] names = {"Colony", "Size", "Military", "Def", ThreatHulls.enabled() ? "Hulls" : "Free",
					"Marines", "Arms", "Fuel", "Supplies", "Strikes", "Status", "Fleet", "Actions"};
			List<Object> columns = new ArrayList<Object>();
			for (int i = 0; i < names.length; i++) {
				columns.add(names[i]);
				columns.add((float) Math.floor(tw * frac[i]));
			}
			colonyTable = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
			main.makeTableItemsClickable();
			main.addTableHeaderTooltip(2, "Expeditions, task forces and convoys sail from military "
					+ "worlds with a Waystation.");
			main.addTableHeaderTooltip(3, "Ground defense - what a hive landing must beat.");
			main.addTableHeaderTooltip(4, ThreatHulls.enabled()
					? "The colony's own warship hulls not yet sent out, in fleet points. Task forces on "
					+ "station over it are in the fleets table and sail with its orders."
					: "Fleet points free to sail from here now (task forces on "
					+ "station here included) / the colony's own fleet capacity.");
			if (own) {
				main.addTableHeaderTooltip(5, "In the colony's resource stockpile, militia included.");
				main.addTableHeaderTooltip(6, "In the colony's resource stockpile.");
				main.addTableHeaderTooltip(7, "In the colony's resource stockpile. Sorties burn it.");
				main.addTableHeaderTooltip(8, "In the colony's resource stockpile. Sorties use it.");
			} else {
				main.addTableHeaderTooltip(5, "Banked from the colony's surplus above demand, militia included.");
				main.addTableHeaderTooltip(6, "Banked from the colony's surplus above demand.");
				main.addTableHeaderTooltip(7, "Banked from the colony's surplus above demand. Sorties burn it.");
				main.addTableHeaderTooltip(8, "Banked from the colony's surplus above demand. Sorties use it.");
			}
			main.addTableHeaderTooltip(9, "Threat expeditions in flight against this world.");
			main.addTableHeaderTooltip(10, "What the colony is short of, by purpose; the row tooltip has the figures.");
			main.addTableHeaderTooltip(11, "Fleet points deployed over the colony now, at their live strength.");
			for (final ColonyRow r : rows) {
				MarketAPI m = r.market;
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, r.military ? bright : text,
						main.shortenString(m.getName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, h, "" + m.getSize());
				// a military world without a Waystation is not a base: deficit yellow
				ThreatWarBoard.cell(cells, Alignment.MID, !r.military ? gray : r.depot ? pos : h,
						r.military ? militaryLabel(m) : "-");
				// the engine's own figure, not vanilla's raw one (2026-09-08):
				// vanilla's counts personal Storage, which the war never sees,
				// so this cell used to contradict the Marines cell four along
				ThreatWarBoard.cell(cells, Alignment.MID, text,
						Misc.getWithDGS((int) ThreatGroundFronts.defenderStrength(m)));
				fleetCell(cells, m, text);
				for (String c : ThreatReserves.COMMODITIES) stockCell(cells, m, c, text);
				ThreatWarBoard.cell(cells, Alignment.MID, r.threats > 0 ? neg : gray,
						r.threats > 0 ? "" + r.threats : "-");
				List<ThreatNeeds.Need> needs = ThreatNeeds.of(m);
				ThreatWarBoard.cell(cells, Alignment.MID, ThreatNeeds.cellColor(needs),
						main.shortenString(ThreatNeeds.cellText(needs), (float) Math.floor(tw * frac[10]) - 6f));
				deployedCell(cells, ThreatAidCapacity.stagedFP(m), text);
				ThreatWarBoard.cell(cells, Alignment.MID, gray, ""); // buttons sit here
				main.addRow(cells.toArray());
				main.addTooltipToAddedRow(new TooltipCreator() {
					public boolean isTooltipExpandable(Object tooltipParam) { return false; }
					public float getTooltipWidth(Object tooltipParam) { return 420f; }
					public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
						colonyTooltip(tooltip, r);
					}
				}, TooltipLocation.LEFT, false);
				main.setIdForAddedRow(ROW_MARKET + m.getId());
			}
			// the worlds the player's landings are attacking (2026-10-30 evening): the front's figures in
			// the colony's columns - Marines and Arms the landing's, Def the hive's standing strata, Status
			// the line's state word, Fleet what is deployed over the hive; the row tooltip is the front's
			// arithmetic. Deploy raises a fleet at the nearest colony at the levers and sails it to hold the
			// orbit with the front; Transfer is the supply run at the Marines and Arms levers
			for (final ThreatWarBoard.FrontRow fr : attacked) {
				final MarketAPI hm = fr.market;
				final ThreatGroundFronts.GroundFront ff = fr.front;
				List<Object> cells = new ArrayList<Object>();
				FactionAPI hiveOwner = hm.getFaction();
				ThreatWarBoard.cell(cells, Alignment.LMID, hiveOwner != null ? hiveOwner.getBaseUIColor() : text,
						main.shortenString(hm.getName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, h, "" + hm.getSize());
				ThreatWarBoard.cell(cells, Alignment.MID, h, "Front");
				ThreatWarBoard.cell(cells, Alignment.MID, text,
						Misc.getWithDGS((int) ThreatGroundFronts.defenderStrength(hm)));
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, ff.marines > 0f ? text : neg,
						Misc.getWithDGS((int) ff.marines));
				ThreatWarBoard.cell(cells, Alignment.MID, ThreatGroundFronts.isDry(ff) ? neg : text,
						Misc.getWithDGS((int) ff.armaments));
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				String line = ThreatGroundFronts.stateWord(ff, hm, true);
				ThreatWarBoard.cell(cells, Alignment.MID, ThreatWarBoard.lineColor(line, pos, neg, h, text), line);
				deployedCell(cells, ThreatAidCapacity.stagedFP(hm), text);
				ThreatWarBoard.cell(cells, Alignment.MID, gray, ""); // Deploy and Transfer sit here
				main.addRow(cells.toArray());
				main.addTooltipToAddedRow(new TooltipCreator() {
					public boolean isTooltipExpandable(Object tooltipParam) { return false; }
					public float getTooltipWidth(Object tooltipParam) { return 460f; }
					public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
						ThreatWarBoard.addFrontTooltip(tooltip, ff, hm);
					}
				}, TooltipLocation.LEFT, false);
				main.setIdForAddedRow(ROW_MARKET + hm.getId());
			}
			// the purged worlds in reach: the row stays once the hive is gone, its Outpost button raising the
			// station that keeps the swarm from seeding the world again (ThreatOutposts)
			for (final com.fs.starfarer.api.campaign.PlanetAPI p : purged) {
				final MarketAPI payer = ThreatFleetOrders.pickBase(faction, p.getLocationInHyperspace());
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, text,
						main.shortenString(p.getName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "Purged");
				for (int k = 0; k < 7; k++) ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, h, main.shortenString(
						Misc.getDGSCredits(ThreatIncConfig.outpostCredits()), (float) Math.floor(tw * frac[10]) - 6f));
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, ""); // Outpost sits here
				main.addRow(cells.toArray());
				main.addTooltipToAddedRow(new TooltipCreator() {
					public boolean isTooltipExpandable(Object tooltipParam) { return false; }
					public float getTooltipWidth(Object tooltipParam) { return 420f; }
					public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
						tooltip.addPara("Purged: nothing lives there. An outpost over it costs "
								+ Misc.getDGSCredits(ThreatIncConfig.outpostCredits())
								+ (payer != null ? ", raised from " + payer.getName() : "") + ".", 0f);
					}
				}, TooltipLocation.LEFT, false);
				main.setIdForAddedRow(p);
			}
			// outposts: a stockpile and nothing else - no floor, no accrual, no
			// War footing - so the Total below sums exactly what is shown. Its
			// station is its own depot: Supplies and Fleet land there like at
			// a colony (2026-09-06)
			for (final ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(factionId)) {
				if (!o.alive()) continue;
				outposts.add(o);
				final String oName = o.planetName() + " Outpost";
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, text,
						main.shortenString(oName, (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, pos, "Outpost");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				for (String c : ThreatReserves.COMMODITIES) {
					float stock = ThreatOutposts.stock(o, c);
					ThreatWarBoard.cell(cells, Alignment.MID, stock <= 0f ? gray : text,
							stock <= 0f ? "-" : Misc.getWithDGS((int) stock));
				}
				// a forward base while its system holds a hive; afterwards its stock ships home by
				// convoy, and the row tooltip says where (the Convoys column went 2026-10-30)
				String convoyLine = null;
				if (!ThreatIncData.getLiveColonyMarkets(o.systemId).isEmpty()) {
					convoyLine = "Forward base while its system holds a hive.";
				} else {
					MarketAPI home = ThreatConvoys.outpostHome(faction, o);
					if (home != null && home.getStarSystem() != null && o.entity != null) {
						convoyLine = "Its stock ships home to " + home.getName() + ", " + (int) Math.ceil(Misc.getDistanceLY(
								o.entity.getLocationInHyperspace(), home.getStarSystem().getLocation())) + " ly.";
					}
				}
				final String convoyText = convoyLine;
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "");
				main.addRow(cells.toArray());
				main.addTooltipToAddedRow(new TooltipCreator() {
					public boolean isTooltipExpandable(Object tooltipParam) { return false; }
					public float getTooltipWidth(Object tooltipParam) { return 420f; }
					public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
						tooltip.addPara(oName + " - its storage, all of it available: "
								+ cargoText(ThreatOutposts.stock(o, Commodities.MARINES),
										ThreatOutposts.stock(o, Commodities.HAND_WEAPONS),
										ThreatOutposts.stock(o, Commodities.FUEL),
										ThreatOutposts.stock(o, Commodities.SUPPLIES)) + ".", 0f);
						if (convoyText != null) tooltip.addPara(convoyText, Misc.getGrayColor(), 10f);
					}
				}, TooltipLocation.LEFT, false);
				if (o.entity != null) main.setIdForAddedRow(o.entity);
			}
			// totals row - the faction's whole reserve, coloured by the same key
			List<Object> totals = new ArrayList<Object>();
			ThreatWarBoard.cell(totals, Alignment.LMID, h, "Total");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			fleetTotalCell(totals, rows, h);
			for (String c : ThreatReserves.COMMODITIES) {
				totalCell(totals, rows, c, factionId, h);
			}
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			float deployed = 0f;
			for (ColonyRow r : rows) deployed += ThreatAidCapacity.stagedFP(r.market);
			deployedCell(totals, deployed, h);
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			main.addRow(totals.toArray());
			tableRows = rows.size() + attacked.size() + purged.size() + outposts.size() + 1;
			main.addTable("None", -1, 0f);
			// the key to the colours: one word each, in its colour
			LabelAPI key = main.addPara("excess     deficit     critical     empty", gray, 4f);
			key.setHighlight("excess", "deficit", "critical");
			key.setHighlightColors(text, h, neg);
			main.addSpacer(opad);
		}

		// ---- fleets ----
		List<FleetRow> fleets = fleetRows(factionId);
		UIPanelAPI fleetTable = null;
		main.addSectionHeading(own ? "Fleets and orders" : "Fleets in flight, and your aid to them",
				bright, dark, Alignment.MID, opad);
		if (fleets.isEmpty()) {
			main.addPara("No task forces, expeditions or convoys are in flight.", gray, opad);
		} else {
			float tw = width - 24f;
			// Actions holds Recall + Hunt on a group fleet's row (134 px)
			float[] frac = {.11f, .19f, .23f, .12f, .14f, .08f, .13f};
			String[] names = {"Kind", "Fleet", "Task", "Status", "Marines - Fleet", "ETA", "Actions"};
			List<Object> columns = new ArrayList<Object>();
			for (int i = 0; i < names.length; i++) {
				columns.add(names[i]);
				columns.add((float) Math.floor(tw * frac[i]));
			}
			fleetTable = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
			main.makeTableItemsClickable();
			for (FleetRow f : fleets) {
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, f.color, f.kind);
				ThreatWarBoard.cell(cells, Alignment.LMID, text,
						main.shortenString(f.name, (float) Math.floor(tw * frac[1]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.LMID, text,
						main.shortenString(f.task, (float) Math.floor(tw * frac[2]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, h, f.status);
				ThreatWarBoard.cell(cells, Alignment.MID, text, f.strength);
				ThreatWarBoard.cell(cells, Alignment.MID, text, f.eta);
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "");
				main.addRow(cells.toArray());
				if (f.rowId != null) main.setIdForAddedRow(f.rowId);
			}
			main.addTable("None", -1, 0f);
			main.addSpacer(opad);
		}

		// no hive table on the player's tab since 2026-10-30 (the user: "we then dont need that table on
		// player board"): the Attack order is on the Threat tab's ledger rows (ThreatWarBoard.addLedgerButtons)

		// ---- floating buttons, last ----
		// Anchored to the table panels, one row up per row (platform traps 1
		// and 2); every button carries its own order's gate, the reason as its
		// tooltip while disabled (vanilla's confirm dialog cannot grey Confirm).
		float heightBefore = main.getHeightSoFar();
		int[] levers = intel.colonyLevers();
		addRequestButtons(intel, main, requests);
		ThreatWarBoard.addFrontButtons(intel, main, fronts);
		if (colonyTable != null && own) {
			// the attacked worlds' Deploy and Transfer (2026-10-30 evening): Deploy is ThreatDeploy at the
			// Domain's levers with the hive as its target (built at the nearest colony, besieging like a
			// Support once there - ThreatGroundFronts.guardOverHive); Transfer the front's supply run
			// (BUTTON_SUPPLY) at the Marines and Arms levers
			int[] fl = intel.frontLevers();
			for (int j = 0; j < attacked.size(); j++) {
				ThreatWarBoard.FrontRow fr = attacked.get(j);
				MarketAPI hm = fr.market;
				float up = (tableRows - (rows.size() + j)) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				// since 2026-10-31 (the user: "get rid of deploy button and just add the fleet as an extra toggle"):
				// with the Fleet lever set, Transfer deploys a fleet over the hive (ThreatDeploy); else the supply run
				ThreatDeploy.Plan d = ThreatDeploy.plan(ThreatBases.of(hm), levers);
				boolean deploy = fleetLeverSet(levers);
				ButtonAPI supply = intel.addGenericButton(main, TRANSFER_W, "Transfer",
						BUTTON_SUPPLY + factionId + ":" + hm.getId());
				supply.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
				if (deploy) {
					disableWith(main, supply, mayOrder && d.ok(), blocked != null ? blocked : ThreatDeploy.offReason(d),
							"A fleet of " + Misc.getWithDGS((int) d.targetPts) + " fleet points from "
									+ (d.ok() ? d.source.getName() : "the nearest colony") + "'s hulls holds the orbit of "
									+ hm.getName() + " with the front, carrying " + ThreatDeploy.loadText(d.load) + ".");
				} else {
					String supplyWhy = ThreatConvoys.supplyBlockReason(faction, hm, fl);
					float[] asked = ThreatConvoys.supplyAsk(fr.front, hm, fl);
					disableWith(main, supply, mayOrder && supplyWhy == null, blocked != null ? blocked : supplyWhy,
							"A convoy runs " + Misc.getWithDGS((int) asked[0]) + " marines and "
							+ Misc.getWithDGS((int) asked[1]) + " heavy armaments from your colony with the most to give to the front.");
				}
				addRecallAllButton(intel, main, colonyTable, up, faction, hm, mayOrder, blocked);
			}
			// the purged worlds' Outpost
			for (int j = 0; j < purged.size(); j++) {
				com.fs.starfarer.api.campaign.PlanetAPI p = purged.get(j);
				float up = (tableRows - (rows.size() + attacked.size() + j)) * ThreatWarBoard.ROW_H
						- (ThreatWarBoard.ROW_H - 20f) / 2f;
				ButtonAPI build = intel.addGenericButton(main, SMALL_BUTTON_W + 12f, "Outpost",
						BUTTON_OUTPOST + factionId + ":" + p.getId());
				build.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
				boolean canPay = ThreatOutposts.payingBase(faction, p) != null
						&& Global.getSector().getPlayerFleet().getCargo().getCredits().get() >= ThreatIncConfig.outpostCredits();
				disableWith(main, build, mayOrder && canPay, blocked != null ? blocked
						: "No base in reach can pay for it right now.",
						"A " + ThreatOutposts.specIdFor(faction).replace('_', ' ') + " over the world keeps the swarm "
						+ "from seeding it again until it is destroyed. Paid in credits.");
			}
			// the player's outposts: the same two orders, landing at the station
			int ownDays = (int) ThreatIncConfig.guardOwnDays();
			for (int j = 0; j < outposts.size(); j++) {
				ThreatBases.Base b = ThreatBases.of(outposts.get(j));
				if (b == null) continue;
				float up = (tableRows - (rows.size() + attacked.size() + purged.size() + j)) * ThreatWarBoard.ROW_H
						- (ThreatWarBoard.ROW_H - 20f) / 2f;
				ThreatDeploy.Plan d = ThreatDeploy.plan(b, levers);
				boolean deploy = fleetLeverSet(levers);
				ButtonAPI stage = intel.addGenericButton(main, TRANSFER_W, "Transfer",
						BUTTON_STAGE + factionId + ":" + b.id());
				stage.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
				MarketAPI donor = deploy ? null : ThreatConvoys.stageDonor(b, faction, levers);
				float[] load = donor != null ? ThreatConvoys.stageLoad(donor, b, levers) : null;
				if (deploy) {
					disableWith(main, stage, mayOrder && d.ok(), blocked != null ? blocked : ThreatDeploy.offReason(d),
							"A fleet of " + Misc.getWithDGS((int) d.targetPts) + " fleet points from "
							+ (d.ok() ? d.source.getName() : "the nearest colony") + "'s hulls holds this "
							+ "orbit " + (ownDays > 0 ? "for " + ownDays + " days" : "until recalled")
							+ ", carrying " + ThreatDeploy.loadText(d.load) + ".");
				} else {
					disableWith(main, stage, mayOrder && donor != null, blocked != null ? blocked
							: !ThreatIncConfig.convoyEnabled() ? "Convoys are disabled in the mod settings."
							: "No colony of yours can spare anything right now.",
							"A convoy from " + (donor != null ? donor.getName() : "the nearest colony")
							+ " brings " + (load != null ? cargoText(load[0], load[1], load[2], load[3])
									: "what can be spared") + " to the station's storage.");
				}
			}
		}
		if (colonyTable != null) {
			int n = rows.size();
			int guardDays = (int) ThreatIncConfig.guardDays();
			int ownDays = (int) ThreatIncConfig.guardOwnDays();
			for (int i = 0; i < n; i++) {
				ColonyRow r = rows.get(i);
				float up = (tableRows - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				if (own) {
					// the user's labels (2026-10-29): the colony's own hulls become a fleet over it at
					// the levers - "Deploy", a new fleet every press (ThreatDeploy; Redeploy resized the fleet up
					// to 2026-10-30 22:00); a convoy between colonies is "Transfer" ("Fleet" / "Supplies" from
					// 2026-09-05 to then)
					// since 2026-10-31 the Deploy button is gone: with the Fleet lever set, Transfer IS the Deploy - a
					// new fleet of the source's free hulls carrying the goods (ThreatDeploy); at None, a convoy
					ThreatDeploy.Plan d = ThreatDeploy.plan(ThreatBases.of(r.market), levers);
					boolean deploy = fleetLeverSet(levers);
					ButtonAPI stage = intel.addGenericButton(main, TRANSFER_W, "Transfer",
							BUTTON_STAGE + factionId + ":" + r.market.getId());
					stage.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
					// the order fails after Confirm otherwise: the gate is on the button
					MarketAPI donor = deploy ? null : ThreatConvoys.stageDonor(r.market, faction, levers);
					float[] load = donor != null ? ThreatConvoys.stageLoad(donor, r.market, levers) : null;
					boolean stageOk = mayOrder && (deploy ? d.ok() : donor != null);
					String deployHelp = d.ok() ? "A fleet of " + Misc.getWithDGS((int) d.targetPts) + " fleet points from "
									+ (d.source == r.market ? "this colony's" : d.source.getName() + "'s")
									+ " hulls holds its orbit " + (ownDays > 0 ? "for " + ownDays + " days" : "until recalled")
									+ ", carrying " + ThreatDeploy.loadText(d.load) + ". Attack sails it."
							: "Nothing to deploy: " + ThreatDeploy.offReason(d);
					String stageHelp = deploy ? deployHelp : donor != null ? "A convoy from " + donor.getName() + " brings "
							+ (load != null ? cargoText(load[0], load[1], load[2], load[3], ThreatConvoys.hullsIn(load))
									: "what the levers allow") + "."
							: "Nothing to send: " + ThreatConvoys.stageOffReason(ThreatBases.of(r.market), levers, "yours");
					disableWith(main, stage, stageOk, blocked != null ? blocked : deploy ? ThreatDeploy.offReason(d)
							: ThreatConvoys.stageOffReason(ThreatBases.of(r.market), levers, "yours"), stageHelp);
					addRecallAllButton(intel, main, colonyTable, up, faction, r.market, mayOrder, blocked);
				}
				// an ally's row holds no button since 2026-10-29 evening (the user: "remove defend button
				// from the colony list and remove aid from there as well for human factions"): its
				// sieged worlds are held from the fronts table's Defend, at the Fleet lever
			}
		}
		if (fleetTable != null) {
			int n = fleets.size();
			for (int i = 0; i < n; i++) {
				FleetRow f = fleets.get(i);
				if (f.recallKey == null && f.huntKey == null) continue;
				float up = (n - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				float right = -6f;
				if (f.recallKey != null) {
					ButtonAPI recall = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Recall",
							BUTTON_RECALL + factionId + ":" + f.recallKey);
					recall.getPosition().belowRight(fleetTable, -up).setXAlignOffset(right);
					disableWith(main, recall, (mayOrder || f.playerAid) && !f.paid,
							f.paid ? "Standing was earned on arrival: the task force serves its term." : blocked,
							"This fleet breaks off and returns to base. A convoy brings its cargo "
							+ "home; the rest of an expedition or task force fights on.");
					right -= (SMALL_BUTTON_W + 8f) + 4f;
				}
				if (f.huntKey != null) {
					ButtonAPI detach = intel.addGenericButton(main, SMALL_BUTTON_W + 14f, "Hunt",
							BUTTON_DETACH + factionId + ":" + f.huntKey);
					detach.getPosition().belowRight(fleetTable, -up).setXAlignOffset(right);
					disableWith(main, detach, mayOrder && ThreatSoftening.huntTarget(observer,
							f.huntSystem.getId()) != null, blocked != null ? blocked
							: "No Defense Swarms seen there.",
							"This fleet leaves its group and hunts the Defense Swarms in the "
							+ f.huntSystem.getNameWithLowercaseTypeShort() + " for "
							+ (int) ThreatIncConfig.softenDays() + " days, then goes home.");
				}
			}
		}
		if (colonyTable != null) {
			addColonyLevers(intel, main, colonyTable);
		}
		// the Attack's Fleet / Fuel / Marines ladders over the hives table went on 2026-10-29: the
		// colonies table's levers size the Deploy, and the Attack is what is deployed (ThreatDeploy)
			main.setHeightSoFar(heightBefore);
	}

	/**
	 * THE LEVERS (the user, 2026-10-28; ThreatIncursionIntel.levers): a ladder a good, None / Med /
	 * Max - nothing, half the source's spare, all of it (ThreatConvoys.share) - in the row
	 * reserved under a table's heading. The fronts table holds one since 2026-10-29 evening (the
	 * user: "remove resource selectors from ground fronts table ... defend button for faction under
	 * human faction ground front entry should use the fleet sizing buttons"): the Fleet lever,
	 * Support / Defend's outcome tier - match the swarm over the world / x orbitTierMedMargin /
	 * x orbitTierMaxMargin - for the player's own fronts and an ally's sieged world alike. Its
	 * Marines and Arms went: Supply reads the colonies table's (ThreatIncursionIntel.frontLevers).
	 */
	protected static void addFrontLevers(ThreatIncursionIntel intel, TooltipMakerAPI main, UIComponentAPI anchor) {
		String[] fleetTips = {
				"Support / Defend: match the swarm over the world.",
				"Support / Defend: x" + String.format("%.2f", ThreatIncConfig.orbitTierMedMargin()) + " the swarm.",
				"Support / Defend: x" + String.format("%.2f", ThreatIncConfig.orbitTierMaxMargin()) + " the swarm." };
		addTierSelector(intel, main, anchor, "Fleet", LEVER_LABEL_W,
				BUTTON_LEVER + ThreatIncursionIntel.LEVER_FRONT_FLEET + ":",
				intel.lever(ThreatIncursionIntel.LEVER_FRONT_FLEET), fleetTips, 0f, LEVER_LABELS, LEVER_BUTTON_W);
	}

	/**
	 * The colonies table's five levers - Fuel, Supplies, Marines, Arms, Hulls: its Transfer, the
	 * Deploy (ThreatDeploy, 2026-10-29: the Hulls lever sizes the fleet, the other four load it),
	 * the fronts' Supply (marines and arms), and - since 2026-10-29 evening - what a shortfall's
	 * request asks each faction for: Max all of what one colony of its spares, Med half
	 * (ThreatAidRequests). They replace the Amount ladder (the shortfall / x2 / everything, and
	 * one fleet / half / all the hulls).
	 */
	protected static void addColonyLevers(ThreatIncursionIntel intel, TooltipMakerAPI main, UIComponentAPI anchor) {
		addLever(intel, main, anchor, "Fuel", ThreatIncursionIntel.LEVER_COLONY_FUEL, "fuel", 0);
		addLever(intel, main, anchor, "Supplies", ThreatIncursionIntel.LEVER_COLONY_SUPPLIES, "supplies", 1);
		addLever(intel, main, anchor, "Marines", ThreatIncursionIntel.LEVER_COLONY_MARINES, "marines", 2);
		addLever(intel, main, anchor, "Arms", ThreatIncursionIntel.LEVER_COLONY_ARMS, "heavy armaments", 3);
		addLever(intel, main, anchor, "Hulls", ThreatIncursionIntel.LEVER_COLONY_HULLS, "ship hulls", 4);
		// the Fleet lever (the user, 2026-10-31: "get rid of deploy button and just add the fleet as an extra toggle
		// as well, when that is set it deploys the fleet via transfer button"): None, Transfer is a convoy; Med /
		// Max, Transfer is the Deploy - a fleet of half / all the source's free hulls carrying the goods
		String[] fleetTips = { "No fleet: Transfer sends a convoy.",
				"Transfer deploys a fleet of half the source's free hulls, carrying the goods at the levers.",
				"Transfer deploys a fleet of all the source's free hulls, carrying the goods at the levers." };
		addTierSelector(intel, main, anchor, "Fleet", LEVER_LABEL_W,
				BUTTON_LEVER + ThreatIncursionIntel.LEVER_DOMAIN_FLEET + ":", intel.lever(ThreatIncursionIntel.LEVER_DOMAIN_FLEET),
				fleetTips, 5 * LEVER_PITCH, LEVER_LABELS, LEVER_BUTTON_W);
	}

	/** One lever: its label and None / Med / Max at the {@code slot}th pitch along the reserved row. */
	protected static void addLever(ThreatIncursionIntel intel, TooltipMakerAPI main, UIComponentAPI anchor,
			String label, int which, String good, int slot) {
		String[] tips = { "No " + good + ".", "Half the " + good + " the source can spare.",
				"All the " + good + " the source can spare." };
		addTierSelector(intel, main, anchor, label, LEVER_LABEL_W, BUTTON_LEVER + which + ":", intel.lever(which),
				tips, slot * LEVER_PITCH, LEVER_LABELS, LEVER_BUTTON_W);
	}

	/**
	 * A ladder: ONE button in the row reserved under a table's heading (the user, 2026-10-30
	 * evening: "just one button that toggles between 3 labels/states"; three buttons, the set
	 * tier's greyed, until then). It reads the tier set; pressing it sets the next round the
	 * ladder with no Confirm (ThreatIncursionIntel's BUTTON_LEVER handler, which re-renders in
	 * place). Every order button in the table below reads the one choice, so the row quotes
	 * what it will actually send.
	 */
	protected static void addTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor, String label, float labelW, String buttonPrefix,
			int tier, String[] tips) {
		addTierSelector(intel, main, anchor, label, labelW, buttonPrefix, tier, tips, 0f);
	}

	/** As above, the ladder shifted right by {@code xOff}: several ladders share one row (the Attack's). */
	protected static void addTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor, String label, float labelW, String buttonPrefix,
			int tier, String[] tips, float xOff) {
		addTierSelector(intel, main, anchor, label, labelW, buttonPrefix, tier, tips, xOff, TIER_LABELS,
				SMALL_BUTTON_W - 8f);
	}

	/** As above with the buttons' own labels and width (a lever's None / Med / Max). */
	protected static void addTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor, String label, float labelW, String buttonPrefix,
			int tier, String[] tips, float xOff, String[] labels, float buttonW) {
		LabelAPI text = main.addPara(label, Misc.getGrayColor(), 0f);
		text.getPosition().aboveLeft(anchor, 4f).setXAlignOffset(2f + xOff);
		int cur = Math.max(0, Math.min(labels.length - 1, tier));
		int next = (cur + 1) % labels.length;
		ButtonAPI b = intel.addGenericButton(main, buttonW, labels[cur], buttonPrefix + next);
		// clear the label to the left
		b.getPosition().aboveLeft(anchor, 3f).setXAlignOffset(labelW + xOff);
		// the tooltip says what pressing does: the next tier
		final String tip = labels[next] + ": " + tips[next];
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object p) { return false; }
			public float getTooltipWidth(Object p) { return 300f; }
			public void createTooltip(TooltipMakerAPI tt, boolean ex, Object p) { tt.addPara(tip, 0f); }
		}, b, TooltipLocation.BELOW);
	}

	/** The Aid order's confirmation: what sails from where at the levers, and what it costs. */
	protected static void aidPrompt(TooltipMakerAPI prompt, MarketAPI target, ThreatAid.Quote q, String who, Color h) {
		if (!q.ok() || target == null) {
			prompt.addPara(q.reason != null ? q.reason.toString() : "The situation has changed.", 0f);
			return;
		}
		prompt.addPara("Send %s from " + q.source.getName() + hullConvoyText(q) + " to " + target.getName()
				+ ", for " + who + "? " + q.purpose + ". The goods leave " + q.source.getName()
				+ "'s reserve and the hulls your built navy; the convoy waits at the jump-point while the "
				+ "Threat blockades the world, and can be intercepted on the way. Standing follows the goods' "
				+ "value at " + target.getName() + "'s prices, weighted by the need.", 0f, h, aidCargo(q));
	}

	/** An aid convoy's cargo: "320 marines, 1,200 arms, 400 ship hulls", or the one good. */
	protected static String aidCargo(ThreatAid.Quote q) {
		if (q == null || !q.ok()) return "";
		if (q.load == null) return Misc.getWithDGS(q.quantity) + " " + ThreatReserves.label(q.commodityId);
		return cargoText(q.load[0], q.load[1], q.load[2], q.load[3], ThreatConvoys.hullsIn(q.load));
	}

	/** ", and 400 ship hulls from Haven" when the Hulls lever sends a second convoy from the hull port (ThreatAid.Quote.hullSource); "" otherwise. */
	protected static String hullConvoyText(ThreatAid.Quote q) {
		if (q == null || q.hullSource == null || q.hullUnits <= 0) return "";
		return ", and " + ThreatHulls.shown(Commodities.SHIPS, q.hullUnits) + " ship hulls from " + q.hullSource.getName();
	}

	/** The Requests table as drawn, for the buttons floated in last: the toggles under its heading, Accept / Reject / Cancel on its rows. */
	protected static class Requests {
		UIPanelAPI table;
		/** The reserved row under the heading the toggle row floats in (the player's table). */
		UIComponentAPI anchor;
		/** The Domain entry the toggles read (the player's table). */
		ThreatBases.Base target;
		/** Every row the table holds. */
		int rowCount;
		/** The player's table: a row an offer - its id, its row, and whether it sails (Cancel) or waits on the player (Accept / Reject). A waiting row holds no button. */
		List<String> offerIds = new ArrayList<String>();
		List<Boolean> sailing = new ArrayList<Boolean>();
		List<Integer> firstRows = new ArrayList<Integer>();
		/** An NPC faction's needs table (2026-10-30): a row a need - its market id and good; the rows hold Send (BUTTON_AID_NEED). */
		List<String> marketIds = new ArrayList<String>();
		List<String> goods = new ArrayList<String>();
		/** Why the row's button is off, null while it can be pressed. */
		List<String> offReasons = new ArrayList<String>();
		/** The button's tooltip while it can be pressed (the needs table). */
		List<String> helps = new ArrayList<String>();
		boolean aid;
		String factionId;
	}

	/** Accept / Reject / Cancel on an offer's row. */
	protected static final float OFFER_BUTTON_W = SMALL_BUTTON_W + 8f;
	/** The Colony button of the Requests toggles: the entry's name. */
	protected static final float TARGET_BUTTON_W = 120f;
	/** The five goods a call can ask for, in the toggle row's order. */
	protected static final String[] CALL_LABELS = {"Fuel", "Supplies", "Marines", "Arms", "Hulls", "Fleet"};
	protected static final String[] CALL_GOODS = {Commodities.FUEL, Commodities.SUPPLIES, Commodities.MARINES,
			Commodities.HAND_WEAPONS, Commodities.SHIPS, ThreatNeeds.FLEETS};

	/**
	 * THE REQUESTS TABLE (the user, 2026-10-31: "buttons below Requests table header for each type
	 * like we have on domains, but these are just on/off toggles. when toggle active that counts as
	 * you asking the sector for the given commodity. the table then will have responses from other
	 * factions which you can accept or ignore to clear"): under the heading a toggle row - Colony
	 * cycles the Domain's entries (the player's colonies, the hive worlds their landings attack, their
	 * outposts; {@link #requestTargets}), then Fuel / Supplies / Marines / Arms / Hulls, each On or Off
	 * for that entry (ThreatAidRequests.toggle) - and a table of the offers the standing calls drew
	 * (ThreatAidRequests.offersFor): World, Commodity, Age (of the call), Answer (who offers what from
	 * where; "waiting" on a call nobody has answered), Standing (charged on receipt), ETA (once
	 * accepted) and Actions (Accept / Dismiss on an offer, Cancel on one at sea). On the player's tab
	 * only: an NPC faction's tab lists its own needs, {@link #addFactionNeeds}. Nothing drawn while
	 * the player has no colony.
	 */
	protected static Requests addRequests(ThreatIncursionIntel intel, TooltipMakerAPI main, float width,
			float opad, final FactionAPI faction, final boolean own) {
		if (!own) return addFactionNeeds(intel, main, width, opad, faction);
		List<ThreatBases.Base> targets = requestTargets();
		if (targets.isEmpty()) return null;
		Color dark = faction.getDarkUIColor();
		Color bright = faction.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		Requests out = new Requests();
		out.target = requestTarget(intel, targets);
		main.addSectionHeading("Requests for aid", bright, dark, Alignment.MID, opad);
		// the toggles sit in a reserved row under the heading; they float in later (addRequestButtons)
		out.anchor = main.addSpacer(SELECTOR_ROW_H);
		// the rows first: a row an offer, none for a call nobody has answered, and with no offer at all no
		// table either (the user, 2026-10-31: "no answers and there are still multiple rows. get rid of the whitespace")
		List<Object[]> rows = new ArrayList<Object[]>();
		for (ThreatAidRequests.Call c : ThreatAidRequests.open()) {
			ThreatBases.Base base = ThreatAidRequests.resolveTarget(c.targetId);
			if (base == null) continue;
			for (ThreatAidRequests.Offer o : ThreatAidRequests.offersFor(c)) rows.add(new Object[] { c, base, o });
		}
		// offers at sea whose call was toggled off: still sailing, still cancellable
		for (ThreatAidRequests.Offer o : ThreatAidRequests.strays()) {
			ThreatBases.Base base = ThreatAidRequests.resolveTarget(o.targetId);
			if (base != null) rows.add(new Object[] { null, base, o });
		}
		if (rows.isEmpty()) {
			main.addSpacer(opad);
			return out;
		}
		final float tw = width - 24f;
		String[] names = {"World", "Commodity", "Age", "Answer", "Standing", "ETA", "Actions"};
		final float[] frac = {.13f, .13f, .06f, .30f, .08f, .07f, .23f};
		List<Object> columns = new ArrayList<Object>();
		for (int i = 0; i < names.length; i++) {
			columns.add(names[i]);
			columns.add((float) Math.floor(tw * frac[i]));
		}
		out.table = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
		main.makeTableItemsClickable();
		main.addTableHeaderTooltip(1, "What the world calls for.");
		main.addTableHeaderTooltip(2, "Days the call has stood.");
		main.addTableHeaderTooltip(3, "Who offers what, from where; waiting until someone does.");
		main.addTableHeaderTooltip(4, "Standing with the sender, charged when it lands.");
		main.addTableHeaderTooltip(5, "Days until it lands; the passage quoted until then.");
		int rowIndex = 0;
		for (Object[] row : rows) {
			addOfferRow(main, out, (ThreatAidRequests.Call) row[0], (ThreatBases.Base) row[1], (ThreatAidRequests.Offer) row[2],
					rowIndex++, tw, frac, text, gray, h, neg);
		}
		out.rowCount = rowIndex;
		main.addTable("", -1, 0f);
		main.addSpacer(opad);
		return out;
	}

	/** One row of the Requests table: a call waiting ({@code o} null), or an offer under it. */
	protected static void addOfferRow(TooltipMakerAPI main, Requests out, final ThreatAidRequests.Call c,
			final ThreatBases.Base base, final ThreatAidRequests.Offer o, int rowIndex, float tw, float[] frac,
			Color text, Color gray, Color h, Color neg) {
		final String good = o != null ? o.good : c.good;
		long since = c != null ? c.since : o.offeredAt;
		int days = (int) Global.getSector().getClock().getElapsedDaysSince(since);
		List<Object> cells = new ArrayList<Object>();
		ThreatWarBoard.cell(cells, Alignment.LMID, text, main.shortenString(base.name(), (float) Math.floor(tw * frac[0]) - 10f));
		ThreatWarBoard.cell(cells, Alignment.LMID, text,
				main.shortenString(ThreatAidRequests.goodLabel(good), (float) Math.floor(tw * frac[1]) - 10f));
		ThreatWarBoard.cell(cells, Alignment.MID, text, days + " d");
		FactionAPI helper = o != null ? Global.getSector().getFaction(o.factionId) : null;
		String answer = o == null ? "waiting" : ThreatAidRequests.offerText(o);
		Color answerColor = o == null ? gray : helper != null ? helper.getBaseUIColor() : text;
		ThreatWarBoard.cell(cells, Alignment.LMID, answerColor, main.shortenString(answer, (float) Math.floor(tw * frac[3]) - 10f));
		ThreatWarBoard.cell(cells, Alignment.MID, o == null ? gray : neg, o == null ? "-" : "-" + o.price);
		// the passage on an open offer too (the user, 2026-10-31: "show eta column even if not accepted to give an idea of transit time")
		String eta = "-";
		if (o != null && o.accepted()) {
			float left = ThreatAidRequests.etaLeft(o);
			eta = left <= 0f ? "due" : (int) Math.ceil(left) + " d";
		} else if (o != null) {
			eta = (int) Math.ceil(o.etaDays) + " d";
		}
		ThreatWarBoard.cell(cells, Alignment.MID, o != null && o.accepted() ? h : o != null ? text : gray, eta);
		ThreatWarBoard.cell(cells, Alignment.MID, gray, ""); // the buttons sit here
		main.addRow(cells.toArray());
		if (base.market != null && base.market.isPlayerOwned()) main.setIdForAddedRow(ROW_MARKET + base.market.getId());
		main.addTooltipToAddedRow(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 460f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				Color tx = Misc.getTextColor();
				tooltip.addPara(base.name() + (c != null ? " calls for " : " called for ") + callWord(good) + ".", tx, 0f);
				if (o != null) {
					tooltip.addPara(ThreatWarState.displayName(o.factionId) + ": " + ThreatAidRequests.amountText(o) + " from "
							+ o.fromName + (o.accepted() ? ", sailing, due in " + (int) Math.ceil(ThreatAidRequests.etaLeft(o)) + " d."
							: ", ETA " + (int) Math.ceil(o.etaDays) + " d once accepted.") + " Standing -" + o.price + " on receipt.", tx, 4f);
				} else {
					tooltip.addPara("No offer yet.", tx, 4f);
				}
				if (c != null && ThreatIncConfig.debugMode()) {
					for (FactionAPI helper : ThreatAidRequests.candidates()) {
						float next = ThreatAidRequests.nextCheckIn(c, helper);
						tooltip.addPara("[debug] " + ThreatAidRequests.standLine(helper) + " - "
								+ (c.declined().contains(helper.getId()) ? "declined" : "looks "
								+ (next <= 0f ? "today" : "in " + (int) Math.ceil(next) + " d")) + ".", Misc.getGrayColor(), 4f);
					}
				}
				if (base.market != null && base.market.isPlayerOwned()) tooltip.addPara("Click for the colony screen.", Misc.getGrayColor(), 6f);
			}
		}, TooltipLocation.BELOW);
		if (o != null) {
			out.offerIds.add(o.id);
			out.sailing.add(o.accepted());
			out.firstRows.add(rowIndex);
		}
	}

	/** The Domain's entries a call can be made for: the player's colonies, the hive worlds their landings attack, their outposts. */
	protected static List<ThreatBases.Base> requestTargets() {
		List<ThreatBases.Base> out = new ArrayList<ThreatBases.Base>();
		for (MarketAPI m : ThreatReserves.marketsOf(Factions.PLAYER)) {
			ThreatBases.Base b = ThreatBases.of(m);
			if (b != null) out.add(b);
		}
		for (ThreatWarBoard.FrontRow r : attackedRows()) {
			ThreatBases.Base b = ThreatBases.of(r.market);
			if (b != null) out.add(b);
		}
		for (ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(Factions.PLAYER)) {
			ThreatBases.Base b = ThreatBases.of(o);
			if (b != null) out.add(b);
		}
		return out;
	}

	/** The entry the toggles read: the one the Colony button last set, else the first. */
	protected static ThreatBases.Base requestTarget(ThreatIncursionIntel intel, List<ThreatBases.Base> targets) {
		String id = intel != null ? intel.requestTarget() : null;
		for (ThreatBases.Base b : targets) if (b.id().equals(id)) return b;
		return targets.get(0);
	}

	/** The entry after the current one, round the list. */
	protected static ThreatBases.Base nextRequestTarget(ThreatBases.Base current, List<ThreatBases.Base> targets) {
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i).id().equals(current.id())) return targets.get((i + 1) % targets.size());
		}
		return targets.get(0);
	}

	/**
	 * AN NPC FACTION'S CALLS (the user, 2026-10-31: "other human faction request boards should have
	 * the same table as player one ... they only have one entry per commodity and if no answer yet
	 * it does say waiting"): the faction's calls are its colonies' needs on the ledger (ThreatNeeds.of
	 * - what the allies' goods pass, ThreatCoalition.allyAid, and the player's dock hand-over answer),
	 * one row a world and good (the most urgent need of the good), in the player's table's columns -
	 * World, Commodity (the full ask: "1,200 marines", "defence fleet of 900 FP"), Age (how long the
	 * shortage has stood, ThreatAidRequests.shortageSince; a dash for a battle's need), Answer (the
	 * convoy at sea with the good and whose it is, a task force bound for a fleets need, else waiting),
	 * Standing (what Send would earn when it lands, ThreatAid.rewardEstimate / aidRepGuardArrived -
	 * the user, 2026-10-31: "show exact amount requested, standing reward and estimated eta"), ETA
	 * (the convoy's `convoyEta`; the Send quote's passage while waiting) and **Send** (BUTTON_AID_NEED: a convoy of that good alone at the colonies table's
	 * lever for it, Med when the lever is at None, from the player's colony sparing the most,
	 * ThreatAid.quoteAid; a fleets need sends Defend at the fronts' Fleet lever, as BUTTON_AID_DEFEND).
	 * A need that clears leaves the table - the call withdraws itself. Nothing drawn with no need.
	 */
	protected static Requests addFactionNeeds(ThreatIncursionIntel intel, TooltipMakerAPI main, float width,
			float opad, final FactionAPI faction) {
		List<Object[]> rows = new ArrayList<Object[]>();
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			// one entry a good: the most urgent need of it, the largest ask on a tie
			List<ThreatNeeds.Need> kept = new ArrayList<ThreatNeeds.Need>();
			for (ThreatNeeds.Need n : ThreatNeeds.of(m)) {
				ThreatNeeds.Need same = null;
				for (ThreatNeeds.Need k : kept) if (k.good != null && k.good.equals(n.good)) same = k;
				if (same == null) {
					kept.add(n);
				} else if (n.urgency > same.urgency || (n.urgency == same.urgency && n.max > same.max)) {
					kept.set(kept.indexOf(same), n);
				}
			}
			for (ThreatNeeds.Need n : kept) rows.add(new Object[] { m, n });
		}
		if (rows.isEmpty()) return null;
		int[] levers = intel.colonyLevers();
		int[] frontLevers = intel.frontLevers();
		ThreatNotice.Reason blocked = ThreatAid.canAid(faction);
		Requests out = new Requests();
		out.aid = true;
		out.factionId = faction.getId();
		Color dark = faction.getDarkUIColor();
		Color bright = faction.getBrightUIColor();
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		main.addSectionHeading("Requests for aid", bright, dark, Alignment.MID, opad);
		float tw = width - 24f;
		String[] names = {"World", "Commodity", "Age", "Answer", "Standing", "ETA", "Actions"};
		float[] frac = {.13f, .13f, .06f, .30f, .08f, .07f, .23f};
		List<Object> columns = new ArrayList<Object>();
		for (int i = 0; i < names.length; i++) {
			columns.add(names[i]);
			columns.add((float) Math.floor(tw * frac[i]));
		}
		out.table = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
		main.makeTableItemsClickable();
		main.addTableHeaderTooltip(1, "What the world calls for; the row tooltip has the purpose and the figures.");
		main.addTableHeaderTooltip(2, "Days the shortage has stood.");
		main.addTableHeaderTooltip(3, "What sails to answer it; waiting until something does.");
		main.addTableHeaderTooltip(4, "Standing earned when what you send lands.");
		main.addTableHeaderTooltip(5, "Days until it lands; while waiting, the passage from your colony.");
		int rowIndex = 0;
		for (Object[] row : rows) {
			final MarketAPI m = (MarketAPI) row[0];
			final ThreatNeeds.Need n = (ThreatNeeds.Need) row[1];
			// the Send quote first: its figures feed the Standing and ETA cells
			String off = blocked != null ? ThreatNotice.text(blocked) : null;
			String help;
			ThreatAid.Quote q;
			float reward;
			if (n.isFleets()) {
				q = ThreatAid.quoteDefend(m, frontLevers.length > 2 ? frontLevers[2] : 0);
				reward = ThreatIncConfig.aidRepGuardArrived();
				help = q.ok() ? "Sends a task force of " + Misc.getWithDGS(Math.round(q.points)) + " fleet points from "
						+ q.source.getName() + " to take the orbit, at the Fleet lever." : "Sends a task force to take the orbit.";
			} else {
				q = ThreatAid.quoteAid(m, needLevers(n.good, levers));
				reward = q.ok() ? ThreatAid.rewardEstimate(m, q.load, q.hullUnits) : ThreatAid.rewardEstimate(m, n.good, n.max);
				help = q.ok() ? "Sends " + aidCargo(q) + " from " + q.source.getName() + ", at the " + ThreatReserves.label(n.good)
						+ " lever." : "Sends a convoy of " + ThreatReserves.label(n.good) + ".";
			}
			if (off == null && !q.ok()) off = ThreatNotice.text(q.reason);
			List<Object> cells = new ArrayList<Object>();
			ThreatWarBoard.cell(cells, Alignment.LMID, text,
					main.shortenString(m.getName(), (float) Math.floor(tw * frac[0]) - 10f));
			// the full ask (the user, 2026-10-31: "exact amount requested")
			String asked = n.isFleets() ? "defence fleet of " + n.shown(n.max) : n.shown(n.max) + " " + n.goodLabel();
			ThreatWarBoard.cell(cells, Alignment.LMID, n.color(),
					main.shortenString(asked + (n.suspended ? " - blockaded" : ""), (float) Math.floor(tw * frac[1]) - 10f));
			Long since = n.isFleets() ? null : ThreatAidRequests.shortageSince().get(m.getId() + ":" + n.good);
			ThreatWarBoard.cell(cells, Alignment.MID, since != null ? text : gray, since != null
					? (int) Global.getSector().getClock().getElapsedDaysSince(since) + " d" : "-");
			final List<ThreatConvoys.Convoy> sailing = n.isFleets() ? new ArrayList<ThreatConvoys.Convoy>()
					: convoysWith(m, n.good);
			String answer = "waiting";
			Color answerColor = gray;
			String eta = "-";
			Color etaColor = gray;
			if (n.isFleets()) {
				if (ThreatCoalition.guardBoundFor(m)) {
					answer = "a task force is bound here";
					answerColor = text;
				}
			} else if (!sailing.isEmpty()) {
				ThreatConvoys.Convoy c = sailing.get(0);
				FactionAPI sender = Global.getSector().getFaction(c.factionId);
				answer = (sender != null ? ThreatWarState.displayName(sender.getId()) : "A convoy") + ": "
						+ Misc.getWithDGS(Math.round(cargoOf(c, n.good))) + " " + ThreatReserves.label(n.good)
						+ " from " + c.fromName() + (sailing.size() > 1 ? " +" + (sailing.size() - 1) : "");
				answerColor = sender != null ? sender.getBaseUIColor() : text;
				float left = convoyEta(c, m);
				eta = left <= 0f ? "due" : (int) Math.ceil(left) + " d";
				etaColor = h;
			}
			// while waiting, the passage your Send would take (the user, 2026-10-31: "estimated eta")
			if ("-".equals(eta) && q.ok() && q.source != null) {
				eta = (int) Math.ceil(ThreatAidRequests.eta(q.source, m)) + " d";
				etaColor = text;
			}
			ThreatWarBoard.cell(cells, Alignment.LMID, answerColor,
					main.shortenString(answer, (float) Math.floor(tw * frac[3]) - 10f));
			ThreatWarBoard.cell(cells, Alignment.MID, reward > 0f ? ThreatNotice.goodColor() : gray,
					reward > 0f ? "+" + Misc.getRoundedValueMaxOneAfterDecimal(reward) : "-");
			ThreatWarBoard.cell(cells, Alignment.MID, etaColor, eta);
			ThreatWarBoard.cell(cells, Alignment.MID, gray, ""); // Send sits here
			main.addRow(cells.toArray());
			main.setIdForAddedRow(ROW_MARKET + m.getId());
			out.marketIds.add(m.getId());
			out.goods.add(n.good);
			out.firstRows.add(rowIndex);
			out.offReasons.add(off);
			out.helps.add(help);
			rowIndex++;
			main.addTooltipToAddedRow(new TooltipCreator() {
				public boolean isTooltipExpandable(Object tooltipParam) { return false; }
				public float getTooltipWidth(Object tooltipParam) { return 460f; }
				public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
					Color tx = Misc.getTextColor();
					tooltip.addPara(m.getName() + ": " + n.line() + ".", tx, 0f);
					for (ThreatConvoys.Convoy c : sailing) {
						FactionAPI sender = Global.getSector().getFaction(c.factionId);
						float left = convoyEta(c, m);
						tooltip.addPara((sender != null ? ThreatWarState.displayName(sender.getId()) : "A convoy") + ": "
								+ Misc.getWithDGS(Math.round(cargoOf(c, n.good))) + " " + ThreatReserves.label(n.good)
								+ " from " + c.fromName() + ", ETA " + Math.max(0, (int) Math.ceil(left)) + " d.", tx, 4f);
					}
					if (sailing.isEmpty() && !n.isFleets()) tooltip.addPara("No answer yet.", tx, 4f);
					tooltip.addPara("Click for the colony screen.", Misc.getGrayColor(), 6f);
				}
			}, TooltipLocation.BELOW);
		}
		out.rowCount = rowIndex;
		main.addTable("None", -1, 0f);
		main.addSpacer(opad);
		return out;
	}

	/**
	 * The colonies table's levers cut to one good for a need row's Send (2026-10-30): that good's
	 * lever, Med when it stands at None, every other at None.
	 */
	protected static int[] needLevers(String good, int[] levers) {
		int[] single = new int[ThreatReserves.AID_COMMODITIES.length];
		int i = ThreatAid.index(good);
		if (i >= 0) single[i] = levers != null && i < levers.length && levers[i] > 0 ? levers[i] : 1;
		return single;
	}

	/** A Send on a fleets need is the fronts' Defend (BUTTON_AID_DEFEND); any other id passes through. */
	protected static String[] aidNeedParts(String[] parts) {
		if (parts == null || !BUTTON_AID_NEED.equals(parts[0])) return parts;
		String[] wt = withdrawTarget(parts[2]);
		if (ThreatNeeds.FLEETS.equals(wt[1])) return new String[] {BUTTON_AID_DEFEND, parts[1], wt[0]};
		return parts;
	}

	/** The convoys at sea to the market carrying some of the good, whoever sent them. */
	protected static List<ThreatConvoys.Convoy> convoysWith(MarketAPI m, String good) {
		List<ThreatConvoys.Convoy> out = new ArrayList<ThreatConvoys.Convoy>();
		if (m == null || good == null) return out;
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (c.fleet == null || !c.fleet.isAlive() || c.pickup) continue;
			if (!m.getId().equals(c.toMarketId)) continue;
			if (cargoOf(c, good) > 0.5f) out.add(c);
		}
		return out;
	}

	/** Units of the good aboard a convoy (hulls as ThreatHulls shows them). */
	protected static float cargoOf(ThreatConvoys.Convoy c, String good) {
		if (Commodities.MARINES.equals(good)) return c.marines;
		if (Commodities.HAND_WEAPONS.equals(good)) return c.armaments;
		if (Commodities.FUEL.equals(good)) return c.fuel;
		if (Commodities.SUPPLIES.equals(good)) return c.supplies;
		if (Commodities.SHIPS.equals(good)) return ThreatHulls.shown(good, c.ships);
		return 0f;
	}

	/** Days until the convoy lands at the market by the passage it was sent on, 0 once overdue. */
	protected static float convoyEta(ThreatConvoys.Convoy c, MarketAPI to) {
		MarketAPI from = c.fromMarketId != null ? Global.getSector().getEconomy().getMarket(c.fromMarketId) : null;
		float days = ThreatAidRequests.eta(from, to)
				- Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
		return Math.max(0f, days);
	}

	/** A convoy row's Status: lost, hunted, held off at the jump-point by the Threat's blockade of its destination (ThreatConvoys.holdsOff / the front-run door), else in transit. */
	protected static String convoyStatus(ThreatConvoys.Convoy c, int hunters) {
		if (c.fleet == null || !c.fleet.isAlive()) return "lost";
		if (hunters > 0) return "HUNTED";
		if (c.waitSinceTimestamp != 0L && !c.runningIn) return "held off - orbit blockaded";
		return "in transit";
	}

	/** A convoy row's ETA: days until it lands by its passage; while held off, how long it has waited (it gives up at convoyBlockadeWaitDays). */
	protected static String convoyEtaText(ThreatConvoys.Convoy c) {
		if (c.waitSinceTimestamp != 0L && !c.runningIn) {
			int held = (int) Global.getSector().getClock().getElapsedDaysSince(c.waitSinceTimestamp);
			return "held " + held + " d";
		}
		MarketAPI to = c.toMarketId != null ? Global.getSector().getEconomy().getMarket(c.toMarketId) : null;
		if (to == null) {
			int days = (int) Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
			return days + " d out";
		}
		return (int) Math.ceil(convoyEta(c, to)) + " d";
	}

	/**
	 * The Requests table's buttons, floated in last (platform traps 1 and 2): on the player's tab the
	 * toggle row under the heading (Colony, then a good a button reading On / Off for that entry -
	 * BUTTON_REQ_TARGET / BUTTON_REQ_TOGGLE, no Confirm, handled in ThreatIncursionIntel) and, on
	 * each offer's row, Accept and Dismiss - Cancel once it sails; on an NPC faction's needs table,
	 * Send on each row (BUTTON_AID_NEED), off with its reason as the tooltip.
	 */
	protected static void addRequestButtons(ThreatIncursionIntel intel, TooltipMakerAPI main, Requests requests) {
		if (requests == null) return;
		if (requests.table == null) {
			// no offers, no table: only the toggle row
		} else if (requests.aid) {
			for (int i = 0; i < requests.marketIds.size(); i++) {
				String marketId = requests.marketIds.get(i);
				int row = requests.firstRows.get(i);
				String off = requests.offReasons.get(i);
				float up = (requests.rowCount - row) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				ButtonAPI button = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Send",
						BUTTON_AID_NEED + requests.factionId + ":" + marketId + ":" + requests.goods.get(i));
				button.getPosition().belowRight(requests.table, -up).setXAlignOffset(-6f);
				disableWith(main, button, off == null, off, requests.helps.get(i));
			}
			return;
		}
		// the offers' Accept / Reject, or Cancel
		for (int i = 0; i < requests.offerIds.size(); i++) {
			String offerId = requests.offerIds.get(i);
			int row = requests.firstRows.get(i);
			float up = (requests.rowCount - row) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
			if (requests.sailing.get(i)) {
				ButtonAPI cancel = intel.addGenericButton(main, OFFER_BUTTON_W, "Cancel",
						BUTTON_REQ_CANCEL + Factions.PLAYER + ":" + offerId);
				cancel.getPosition().belowRight(requests.table, -up).setXAlignOffset(-6f);
				disableWith(main, cancel, true, null, "Turns the convoy home. Nothing is charged.");
				continue;
			}
			ButtonAPI reject = intel.addGenericButton(main, OFFER_BUTTON_W, "Dismiss",
					BUTTON_REQ_REJECT + offerId);
			reject.getPosition().belowRight(requests.table, -up).setXAlignOffset(-6f);
			disableWith(main, reject, true, null, "Dismisses the offer. The faction offers nothing more on this call until it is toggled again.");
			ButtonAPI accept = intel.addGenericButton(main, OFFER_BUTTON_W, "Accept",
					BUTTON_REQ_ACCEPT + Factions.PLAYER + ":" + offerId);
			accept.getPosition().belowRight(requests.table, -up).setXAlignOffset(-6f - OFFER_BUTTON_W - 4f);
			disableWith(main, accept, true, null, "The convoy sails now; the standing is charged when it lands.");
		}
		// the toggle row: Colony, then a good a button
		if (requests.anchor == null || requests.target == null) return;
		final ThreatBases.Base t = requests.target;
		List<ThreatBases.Base> targets = requestTargets();
		final ThreatBases.Base next = nextRequestTarget(t, targets);
		Color gray = Misc.getGrayColor();
		LabelAPI pickLabel = main.addPara("Colony", gray, 0f);
		pickLabel.getPosition().belowLeft(requests.anchor, -SELECTOR_ROW_H + 8f).setXAlignOffset(2f);
		ButtonAPI pick = intel.addGenericButton(main, TARGET_BUTTON_W,
				main.shortenString(t.name(), TARGET_BUTTON_W - 12f), BUTTON_REQ_TARGET + next.id());
		pick.getPosition().belowLeft(requests.anchor, -SELECTOR_ROW_H + 4f).setXAlignOffset(LEVER_LABEL_W);
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object p) { return false; }
			public float getTooltipWidth(Object p) { return 300f; }
			public void createTooltip(TooltipMakerAPI tt, boolean ex, Object p) {
				tt.addPara("The calls are " + t.name() + "'s. Next: " + next.name() + "; switching withdraws every call.", 0f);
			}
		}, pick, TooltipLocation.BELOW);
		String off = ThreatAidRequests.requestOffReason(t);
		float x0 = LEVER_LABEL_W + TARGET_BUTTON_W + 14f;
		for (int i = 0; i < CALL_GOODS.length; i++) {
			String good = CALL_GOODS[i];
			float x = x0 + i * LEVER_PITCH;
			LabelAPI label = main.addPara(CALL_LABELS[i], gray, 0f);
			label.getPosition().belowLeft(requests.anchor, -SELECTOR_ROW_H + 8f).setXAlignOffset(2f + x);
			boolean on = ThreatAidRequests.isCalling(t.id(), good);
			ButtonAPI b = intel.addGenericButton(main, LEVER_BUTTON_W, on ? "On" : "Off",
					BUTTON_REQ_TOGGLE + good + ":" + t.id());
			b.getPosition().belowLeft(requests.anchor, -SELECTOR_ROW_H + 4f).setXAlignOffset(x + LEVER_LABEL_W);
			String why = off != null ? off : ThreatAidRequests.goodOffReason(t, good);
			disableWith(main, b, why == null || on, why,
					on ? "Withdraws " + t.name() + "'s call for " + callWord(good) + "."
							: "Asks the sector for " + callWord(good) + " for " + t.name() + ".");
		}
	}

	/** Whether the Domain's Fleet lever (the sixth of ThreatIncursionIntel.colonyLevers) is at Med or Max: Transfer deploys a fleet. */
	protected static boolean fleetLeverSet(int[] levers) {
		return levers != null && levers.length > 5 && levers[5] > 0;
	}

	/** "a defence fleet" for the fleets call, else the good's label - a call's wording. */
	protected static String callWord(String good) {
		return ThreatNeeds.FLEETS.equals(good) ? "a defence fleet" : ThreatReserves.label(good);
	}

	/** A Withdraw button's "marketId:good" - the market id, then the good (null when the id carries none). */
	protected static String[] withdrawTarget(String rest) {
		if (rest == null) return new String[] {null, null};
		int colon = rest.indexOf(':');
		if (colon < 0) return new String[] {rest, null};
		return new String[] {rest.substring(0, colon), rest.substring(colon + 1)};
	}

	protected static String forceText(ThreatAid.Quote q) {
		return q != null && q.ok() ? ThreatAid.forceText(q.points, q.free, q.fleets) : "about 0 fleet points";
	}

	/**
	 * A prompt's provisions lines for a player force of {@code combatFP} from the base to {@code dest}
	 * (ThreatFleetOrders.forceProvisions): what the reserve is drawn for, and what the staged forces
	 * folding in bring ({@code self}: a colony's own guard, nothing folds).
	 */
	protected static void addProvisionsLine(TooltipMakerAPI prompt, MarketAPI base, float combatFP,
			org.lwjgl.util.vector.Vector2f dest, boolean self) {
		if (base == null) return;
		float[] carried = self ? null
				: ThreatAidCapacity.foldedProvisions(base, ThreatAidCapacity.taskForcePoints(combatFP));
		float[] need = ThreatFleetOrders.forceProvisions(base, combatFP, dest, carried);
		addTakesLine(prompt, base, need[0], need[1]);
		if (carried != null && (carried[0] > 0.5f || carried[1] > 0.5f)) {
			prompt.addPara("The fleets at " + base.getName() + " already carry %s.", 3f, Misc.getHighlightColor(),
					ThreatFleetOrders.carriedText(carried));
		}
	}

	/** "Takes N fuel and M supplies from X's F fuel and S supplies." - what an order draws from its base's reserve. */
	protected static void addTakesLine(TooltipMakerAPI prompt, MarketAPI base, float fuel, float supplies) {
		if (base == null) return;
		prompt.addPara("Takes %s fuel and %s supplies from " + base.getName() + "'s %s fuel and %s supplies.", 3f,
				Misc.getHighlightColor(), Misc.getWithDGS((int) Math.ceil(fuel)), Misc.getWithDGS((int) Math.ceil(supplies)),
				Misc.getWithDGS((int) Math.max(0f, ThreatReserves.available(base, Commodities.FUEL))),
				Misc.getWithDGS((int) Math.max(0f, ThreatReserves.available(base, Commodities.SUPPLIES))));
	}

	/** A quote's size as a prompt's format and highlights, {@code more} highlights appended (ThreatAid.forcePrompt). */
	protected static String[] forcePrompt(float points, float free, String... more) {
		return forcePrompt(points, free, 1, more);
	}

	/** As above for a force of several fleets. */
	protected static String[] forcePrompt(float points, float free, int fleets, String... more) {
		String[] f = ThreatAid.forcePrompt(points, free, fleets);
		String[] all = java.util.Arrays.copyOf(f, f.length + more.length);
		System.arraycopy(more, 0, all, f.length, more.length);
		return all;
	}

	/** The highlights of a {@link #forcePrompt} array: everything after its format. */
	protected static String[] highlights(String[] f) {
		return java.util.Arrays.copyOfRange(f, 1, f.length);
	}

	protected static void disableWith(TooltipMakerAPI main, ButtonAPI button, boolean enabled,
			final String reason) {
		disableWith(main, button, enabled, reason, null);
	}

	/**
	 * A faction's overall strength (the user, 2026-10-24), in fleet points: its
	 * navy (hulls free and at sea, ThreatHulls), its army (every colony's ground
	 * strength - garrison and armed marines - at scoreTroopsPerPoint troops a
	 * point) and its depots (the fleet points of sieges its banked supplies and
	 * fuel pay, at the expedition rates over scoreSiegeRangeLY). Read as the
	 * board renders, never stored.
	 */
	public static class Strength {
		public float freeFP, atSeaFP;
		public float garrisons, marineDefence, troopsPerPoint;
		public int colonies;
		public float supplies, fuel, suppliesPerPoint, fuelPerPointLY, rangeLY;
		public float navy() { return freeFP + atSeaFP; }
		public float army() { return troopsPerPoint > 0f ? (garrisons + marineDefence) / troopsPerPoint : 0f; }
		public float suppliesFP() { return suppliesPerPoint > 0f ? supplies / suppliesPerPoint : 0f; }
		public float fuelFP() { return fuelPerPointLY * rangeLY > 0f ? fuel / (fuelPerPointLY * rangeLY) : 0f; }
		public float depots() { return Math.min(suppliesFP(), fuelFP()); }
		public float total() { return navy() + army() + depots(); }
	}

	/** The faction's {@link Strength} now. */
	public static Strength strengthOf(String factionId) {
		Strength s = new Strength();
		if (factionId == null) return s;
		s.freeFP = ThreatHulls.freeFP(factionId);
		s.atSeaFP = ThreatHulls.committedFP(factionId);
		s.troopsPerPoint = Math.max(0f, ThreatIncConfig.scoreTroopsPerPoint());
		for (MarketAPI m : ThreatReserves.marketsOf(factionId)) {
			if (m == null || !m.isInEconomy()) continue;
			s.colonies++;
			s.garrisons += ThreatGroundFronts.mannedGarrison(m); // the defences the marines man (2026-10-28)
			s.marineDefence += ThreatGroundFronts.colonyMarineDefense(m, 1f); // the marines beyond the crews (2026-10-29)
		}
		s.supplies = ThreatReserves.factionStock(factionId, Commodities.SUPPLIES);
		s.fuel = ThreatReserves.factionStock(factionId, Commodities.FUEL);
		s.suppliesPerPoint = Math.max(0f, ThreatIncConfig.expeditionSuppliesPerPoint());
		s.fuelPerPointLY = Math.max(0f, ThreatIncConfig.expeditionFuelPerPointLY());
		s.rangeLY = Math.max(0f, ThreatIncConfig.scoreSiegeRangeLY());
		return s;
	}

	/**
	 * A button's tooltip is the one place its rule is written: {@code help}
	 * (what pressing it does, in a line) while it is enabled, {@code reason}
	 * (why it cannot be pressed) while it is not.
	 */
	protected static void disableWith(TooltipMakerAPI main, ButtonAPI button, boolean enabled,
			String reason, String help) {
		button.setEnabled(enabled);
		final String line = enabled ? help : reason;
		if (line == null) return;
		if (!enabled) button.setShowTooltipWhileInactive(true);
		main.addTooltipTo(new TooltipCreator() {
			public boolean isTooltipExpandable(Object tooltipParam) { return false; }
			public float getTooltipWidth(Object tooltipParam) { return 320f; }
			public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
				tooltip.addPara(line, 0f);
			}
		}, button, TooltipLocation.BELOW);
	}

	/**
	 * The totals cell, by the same rule as {@link #stockCell}: the faction's
	 * banked stock (colonies and outposts); yellow while some colony is
	 * short; a red negative - the uncovered shortfall summed - when nothing
	 * is banked anywhere and a colony's depot is too low to issue; a grey
	 * dash when nothing is banked and nothing is short.
	 */
	protected static void totalCell(List<Object> cells, List<ColonyRow> rows, String commodityId,
			String factionId, Color plain) {
		boolean anyShort = false;
		int uncovered = 0;
		float stock = ThreatReserves.factionStock(factionId, commodityId);
		for (ColonyRow r : rows) {
			ThreatReserves.CommodityStatus s = ThreatReserves.status(r.market, commodityId);
			if (s == null) continue;
			if (s.deficit > 0 || s.covering || s.exhausted) anyShort = true;
			// the peacetime gap "exhausted" and the tooltips read; the War footing's share is the war's supply line
			if (s.exhausted) uncovered += s.localDeficit;
		}
		if (stock <= 0f && uncovered > 0) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getNegativeHighlightColor(), "-" + uncovered);
		} else if (anyShort) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getHighlightColor(),
					Misc.getWithDGS((int) stock));
		} else if (stock <= 0f) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
		} else {
			ThreatWarBoard.cell(cells, Alignment.MID, plain, Misc.getWithDGS((int) stock));
		}
	}

	// ------------------------------------------------------------------
	// data
	// ------------------------------------------------------------------

	/** " (Earth)" - the colony a fleet sailed from, after its name in the Fleet column; "" when unknown. */
	protected static String fromColony(String marketId) {
		MarketAPI m = marketId != null ? Global.getSector().getEconomy().getMarket(marketId) : null;
		return m != null ? " (" + m.getName() + ")" : "";
	}

	protected static List<ColonyRow> colonyRows(List<MarketAPI> markets) {
		List<ColonyRow> rows = new ArrayList<ColonyRow>();
		for (MarketAPI m : markets) {
			ColonyRow r = new ColonyRow();
			r.market = m;
			r.military = IncursionManager.hasMilitary(m);
			r.depot = ThreatReserves.hasDepot(m);
			r.staging = ThreatConvoys.stagingHive(m);
			if (r.staging != null && m.getStarSystem() != null) {
				r.stagingLY = Misc.getDistanceLY(m.getStarSystem().getLocation(),
						r.staging.getLocation());
			} else if (m.getStarSystem() != null) {
				r.feeds = ThreatConvoys.stagingBaseFor(m);
				if (r.feeds != null) {
					r.feedsLY = Misc.getDistanceLY(m.getStarSystem().getLocation(),
							r.feeds.getStarSystem().getLocation());
				}
			}
			r.threats = strikesAgainst(m);
			rows.add(r);
		}
		Collections.sort(rows, new Comparator<ColonyRow>() {
			public int compare(ColonyRow a, ColonyRow b) {
				boolean sa = a.staging != null, sb = b.staging != null;
				if (sa != sb) return sa ? -1 : 1;
				if (a.military != b.military) return a.military ? -1 : 1;
				return b.market.getSize() - a.market.getSize();
			}
		});
		return rows;
	}

	protected static int strikesAgainst(MarketAPI market) {
		int n = 0;
		for (Object curr : IncursionManager.getStrikeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI fgi = (GenericRaidFGI) curr;
			if (fgi.isEnded() || fgi.isEnding() || fgi.isHidden()) continue;
			if (ThreatFrontlines.boundFor(fgi, market)) n++;
		}
		return n;
	}

	protected static String militaryLabel(MarketAPI m) {
		if (m.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.HIGHCOMMAND)) return "High Cmd";
		if (m.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.MILITARYBASE)) return "Mil Base";
		if (m.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.PATROLHQ)) return "Patrol HQ";
		return "-";
	}

	/**
	 * A reserve cell: the number says what is true and the colour repeats it.
	 * A positive number is the stock banked - white = excess (no shortage),
	 * yellow = deficit (the colony is short and the depot covers it or is
	 * about to, so the stock is being spent). A red negative is critical: the
	 * colony's uncovered shortfall in units, the depot too low to issue. A
	 * grey dash is empty: nothing banked, nothing short. The row tooltip has
	 * the figures.
	 */
	protected static void stockCell(List<Object> cells, MarketAPI m, String commodityId, Color text) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(m, commodityId);
		float stock = ThreatReserves.stock(m.getId(), commodityId);
		// always the stock; the colour is the state (red: short and the depot
		// cannot cover it - the units short are in the tooltip)
		if (s != null && (s.exhausted || s.stockpilesOff)) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getNegativeHighlightColor(),
					Misc.getWithDGS((int) stock));
		} else if (s != null && (s.covering || s.deficit > 0)) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getHighlightColor(),
					Misc.getWithDGS((int) stock));
		} else if (stock <= 0f) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
		} else {
			ThreatWarBoard.cell(cells, Alignment.MID, text, Misc.getWithDGS((int) stock));
		}
	}

	/**
	 * The fleet-points cell, "free / capacity" (ThreatAidCapacity.figures:
	 * what can sail from the colony now, task forces on station here at
	 * their live strength included, over its own capacity), coloured by the
	 * free figure by the reserve key: white while a full task force
	 * (guardFleetFP and its support hulls) can sail from it, yellow while only
	 * a reduced one (aidGuardMinFP) can, red while none can. A grey dash off
	 * the ledger: no military structure, or not the player's colony.
	 */
	/** The colonies table's Fleet cell (2026-10-30): the fleet points deployed over the colony (ThreatAidCapacity.stagedFP), a dash with none. */
	protected static void deployedCell(List<Object> cells, float pts, Color text) {
		if (pts < 0.5f) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
			return;
		}
		ThreatWarBoard.cell(cells, Alignment.MID, text, Misc.getWithDGS(Math.round(pts)));
	}

	protected static void fleetCell(List<Object> cells, MarketAPI m, Color text) {
		if (!onLedger(m)) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
			return;
		}
		if (ThreatHulls.enabled()) {
			// the hull pool: the colony's share of the faction's fleet - its patrols, and at the
			// hull port the navy the yards built (the user, 2026-10-11: with the built navy only in
			// the Total row, 153 at Haven under a 1,392 total "looks like a bug or something") -
			// as free / standing, the free share pro rata to the faction's (the user, 16:55: the
			// rows read 522 and 2,194 with eleven task forces out and 22 free, "that table is meant
			// to show what is available ... it needs to be consistent")
			// (the user, 17:10: "Hulls ... no x / y. the column is just Hulls available. if all have
			// been spent then just dashes"; 17:30: a base sails its own share plus what is staged
			// over it, not the faction's pool - ThreatAidCapacity.ownFreeFP; 18:05: a task force on
			// station over the colony is hulls already out, the fleets table's - the cell is the
			// colony's own, "they are there so why arent hulls consumed it should say zero")
			float free = ThreatAidCapacity.ownFreeFP(m);
			if (free < 0.5f) {
				ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
				return;
			}
			ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(free, text), Misc.getWithDGS(Math.round(free)));
			return;
		}
		int[] f = ThreatAidCapacity.figures(m);
		ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(f[4], text),
				Misc.getWithDGS(f[4]) + "/" + Misc.getWithDGS(f[0]));
	}

	protected static boolean onLedger(MarketAPI m) {
		if (m == null || !ThreatAidCapacity.applies(m)) return false;
		if (ThreatHulls.enabled()) return ThreatAidCapacity.capacityFP(m) > 0f || ThreatAidCapacity.freeFP(m) > 0f;
		return ThreatAidCapacity.capacityFP(m) > 0f;
	}

	protected static Color fleetColor(float free, Color text) {
		if (free >= ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP())) return text;
		if (free >= ThreatAidCapacity.taskForcePoints(ThreatIncConfig.aidGuardMinFP())) {
			return Misc.getHighlightColor();
		}
		return Misc.getNegativeHighlightColor();
	}

	/**
	 * The totals cell: free points summed over the colonies on the ledger,
	 * coloured by the single richest colony - a task force sails from one
	 * colony and points pool only by staging guards there, so that is the
	 * colour that says whether one can sail at all. Blank when no colony is
	 * on the ledger.
	 */
	protected static void fleetTotalCell(List<Object> cells, List<ColonyRow> rows, Color plain) {
		int sum = 0;
		int cap = 0;
		int best = 0;
		boolean any = false;
		for (ColonyRow r : rows) {
			if (!onLedger(r.market)) continue;
			any = true;
			int[] f = ThreatAidCapacity.figures(r.market);
			sum += f[4];
			cap += f[0];
			best = Math.max(best, f[4]);
		}
		if (!any) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "");
			return;
		}
		if (ThreatHulls.enabled()) {
			// the hull pool: what the rows show summed - each colony's own hulls not yet sent out;
			// 0 with the fleet out, on station over a colony or not
			float free = 0f;
			for (ColonyRow r : rows) {
				if (onLedger(r.market)) free += ThreatAidCapacity.ownFreeFP(r.market);
			}
			ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(free, plain), Misc.getWithDGS(Math.round(free)));
			return;
		}
		ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(best, plain),
				Misc.getWithDGS(sum) + "/" + Misc.getWithDGS(cap));
	}

	/**
	 * The hulls line beside the resource lines, the same shape: standing fleet
	 * points and what the yards add a month (ThreatHulls.shipUnits x fabFPPerShipUnit).
	 */
	protected static void hullLine(TooltipMakerAPI tooltip, MarketAPI m) {
		if (!ThreatHulls.enabled()) return;
		int standing = Math.round(ThreatAidCapacity.capacityFP(m));
		int per30 = Math.round(ThreatHulls.shipUnits(m) * ThreatIncConfig.fabFPPerShipUnit());
		if (per30 > 0) {
			tooltip.addPara("Hulls: %s fleet points. +%s a month.", 3f, ThreatNotice.goodColor(),
					Misc.getWithDGS(standing), Misc.getWithDGS(per30));
		} else {
			tooltip.addPara("Hulls: %s fleet points. No shipyard here.", 3f, Misc.getGrayColor(),
					Misc.getWithDGS(standing));
		}
	}

	protected static void colonyTooltip(TooltipMakerAPI tooltip, ColonyRow r) {
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		MarketAPI m = r.market;
		tooltip.addPara(m.getName() + " - size " + m.getSize() + ", "
				+ (r.military ? militaryLabel(m) : "no military structure")
				+ (r.depot ? ", Waystation." : ", no Waystation."), 0f);
		if (!r.depot) {
			tooltip.addPara("No Waystation: nothing sails from here.",
					Misc.getNegativeHighlightColor(), 3f);
		}
		// rule 7 (docs/economy-coherence.md): vanilla's units beside the item
		// counts - surplus, availability and demand, the bank rate, and what the
		// depot is covering; the same lines the War footing condition shows
		for (String c : ThreatReserves.COMMODITIES) {
			WarFootingCondition.addCommodityLine(tooltip, m, c, 3f);
		}
		hullLine(tooltip, m);
		// the ledger of needs (2026-10-23): one line each, in the urgency's colour
		for (ThreatNeeds.Need n : ThreatNeeds.of(m)) {
			tooltip.addPara(n.line() + ".", n.color(), 3f);
		}
		if (r.staging != null) {
			float[] wants = ThreatConvoys.stagingTargets(m);
			tooltip.addPara("Staging base for the siege of " + r.staging.getNameWithNoType() + ", "
					+ (int) Math.ceil(r.stagingLY) + " ly: convoys stock it toward %s marines, "
					+ "%s armaments, %s fuel, %s supplies.", 10f, h,
					Misc.getWithDGS((int) wants[0]), Misc.getWithDGS((int) wants[1]),
					Misc.getWithDGS((int) wants[2]), Misc.getWithDGS((int) wants[3]));
		} else if (r.feeds != null) {
			tooltip.addPara("Convoys carry what it can spare to " + r.feeds.getName() + ", "
					+ (int) Math.ceil(r.feedsLY) + " ly.", gray, 10f);
		} else if (m.isPlayerOwned()) {
			tooltip.addPara("No staging base of yours: its reserve stays home.", gray, 10f);
		} else {
			tooltip.addPara("No staging base its fuel reaches: its reserve stays home.", gray, 10f);
		}
		tooltip.addPara("Click to open the colony screen.", gray, 10f);
	}

	protected static List<FleetRow> fleetRows(String factionId) {
		List<FleetRow> rows = new ArrayList<FleetRow>();
		Color h = Misc.getHighlightColor();
		Color pos = ThreatNotice.goodColor();
		Color neg = Misc.getNegativeHighlightColor();
		// the war council's plays, the reason the fleets below fly (docs/war-council.md)
		if (ThreatWarCouncil.governs(factionId)) {
			for (ThreatPlays.Play pl : ThreatPlays.of(factionId)) {
				FleetRow f = new FleetRow();
				f.kind = ThreatPlays.typeName(pl.type);
				f.color = h;
				f.name = ThreatPlays.boardTarget(pl);
				f.task = ThreatPlays.phaseText(pl);
				f.status = pl.held ? "held" : "under way";
				f.strength = pl.plannedFP > 0f ? (int) pl.plannedFP + " fleet points" : "-";
				int days = ThreatPlays.daysToNext(pl);
				if (days >= 0) f.eta = days + " d";
				rows.add(f);
			}
		}
		// task forces
		List<Object> responses = IncursionManager.getResponseList();
		for (int i = 0; i < responses.size(); i++) {
			if (!(responses.get(i) instanceof ThreatResponseIntel)) continue;
			ThreatResponseIntel r = (ThreatResponseIntel) responses.get(i);
			if (r.isEnded() || r.isEnding() || !r.isFleetActive()) continue;
			if (r.getFaction() == null || !factionId.equals(r.getFaction().getId())) continue;
			// one row per fleet (2026-09-06): each can be recalled or detached
			// on its own; the group's task, status and ETA on every row
			String task = r.getTargetColonyName() != null ? "attacking " + r.getTargetColonyName()
					: "standing down";
			String status = r.isEngaging() ? "engaging" : "en route";
			float eta = r.etaDays();
			String etaText = eta > 0f ? "~" + (int) Math.ceil(eta) + " d" : "-";
			MarketAPI target = ThreatIncData.resolveColonyMarket(r.getTargetMarketId());
			StarSystemAPI hive = target != null ? target.getStarSystem() : null;
			for (CampaignFleetAPI fleet : r.livingFleets()) {
				FleetRow f = new FleetRow();
				f.kind = "Task force";
				f.color = h;
				f.name = fleet.getName();
				f.task = task;
				f.status = status;
				f.strength = fleetStrength(fleet);
				f.eta = etaText;
				f.rowId = fleet;
				f.recallKey = "tffleet:" + i + ":" + fleet.getId();
				if (hive != null) {
					f.huntKey = f.recallKey;
					f.huntSystem = hive;
				}
				rows.add(f);
			}
		}
		// expeditions
		List<Object> purges = IncursionManager.getPurgeList();
		for (int i = 0; i < purges.size(); i++) {
			if (!(purges.get(i) instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI p = (ThreatPurgeFGI) purges.get(i);
			if (p.isEnded() || p.isEnding()) continue;
			if (p.getFaction() == null || !factionId.equals(p.getFaction().getId())) continue;
			String kind = p.isPlayerCommissioned() ? "Commissioned" : "Expedition";
			StarSystemAPI where = p.getParams() != null && p.getParams().raidParams != null
					? p.getParams().raidParams.where : null;
			String task = where != null ? (razesEvery(p) ? "bombarding the " : "besieging the ")
					+ where.getNameWithLowercaseTypeShort() : "-";
			ThreatWarBoard.Op op = new ThreatWarBoard.Op();
			ThreatWarBoard.statusOf(p, op, false);
			// real fleets in the open: one row per fleet (2026-09-06), each
			// with the marines it carries, so some can be recalled or detached
			// and the rest left to the siege
			List<CampaignFleetAPI> live = new ArrayList<CampaignFleetAPI>();
			if (p.isSpawnedFleets()) {
				for (CampaignFleetAPI fleet : p.getFleets()) {
					if (fleet != null && fleet.isAlive() && !fleet.isExpired()) live.add(fleet);
				}
			}
			if (live.isEmpty()) {
				// still on its route: it travels as one abstract group (no
				// fleets spawned yet), so it is one row carrying the marines
				// and the FP it will field on arrival, estimated from its
				// planned fleet sizes
				FleetRow f = new FleetRow();
				f.kind = kind;
				f.color = pos;
				f.name = "Expedition";
				f.task = task;
				f.status = op.status;
				f.eta = op.eta;
				f.strength = p.carriesCargo()
						? Misc.getWithDGS((int) p.getMarinesAllotted()) + " - ~" + abstractFP(p)
						: "abstract";
				f.rowId = p;
				f.recallKey = "purge:" + i;
				rows.add(f);
				continue;
			}
			for (CampaignFleetAPI fleet : live) {
				FleetRow f = new FleetRow();
				f.kind = kind;
				f.color = pos;
				f.name = fleet.getName();
				f.task = task;
				f.status = op.status;
				f.eta = op.eta;
				f.strength = fleetStrength(fleet);
				f.rowId = fleet;
				f.recallKey = "purgefleet:" + i + ":" + fleet.getId();
				if (where != null) {
					f.huntKey = f.recallKey;
					f.huntSystem = where;
				}
				rows.add(f);
			}
		}
		// convoys
		List<ThreatConvoys.Convoy> convoys = ThreatConvoys.all();
		for (int i = 0; i < convoys.size(); i++) {
			ThreatConvoys.Convoy c = convoys.get(i);
			if (!factionId.equals(c.factionId)) continue;
			FleetRow f = new FleetRow();
			f.kind = c.aid ? "Aid convoy" : "Convoy";
			f.color = h;
			f.name = c.fromName() + " -> " + c.toName();
			f.task = cargoText(c.marines, c.armaments, c.fuel, c.supplies, c.ships);
			if (c.isFrontRun()) {
				f.kind = c.pickup ? "Evacuation" : "Supply run";
				f.task = (c.pickup ? "lifting the front off " : "to the front on ") + c.toName();
			}
			int hunters = ThreatRaiders.huntersOf(c.fleet);
			f.status = convoyStatus(c, hunters);
			if (hunters > 0) f.color = neg;
			f.strength = "-";
			f.eta = convoyEtaText(c);
			f.rowId = c.fleet;
			f.recallKey = "convoy:" + i;
			rows.add(f);
		}
		// standing orders
		List<ThreatFleetOrders.Order> orders = ThreatFleetOrders.all();
		for (int i = 0; i < orders.size(); i++) {
			ThreatFleetOrders.Order o = orders.get(i);
			if (!factionId.equals(o.factionId)) continue;
			FleetRow f = new FleetRow();
			f.kind = ThreatFleetOrders.KIND_SUPPORT.equals(o.kind) ? (o.raid ? "Raid" : "Support")
					: ThreatFleetOrders.KIND_DEFEND.equals(o.kind) ? "Defend"
					: ThreatFleetOrders.KIND_HUNT.equals(o.kind) ? (o.aid ? "Aid hunt" : "Hunt")
					: ThreatDeploy.isDeployed(o) ? "Deployed"
					: (o.aid ? "Aid " : "") + (ThreatFleetOrders.KIND_GUARD.equals(o.kind)
					? (o.aid ? "guard" : "Guard") : (o.aid ? "intercept" : "Intercept"));
			f.color = o.fleet != null && o.fleet.isAlive() ? pos : neg;
			f.name = (o.fleet != null ? o.fleet.getName() : "-") + fromColony(o.baseMarketId);
			f.task = o.task();
			f.status = o.fleet != null && o.fleet.isAlive()
					? (o.arrived ? "on station" : "en route") : "lost";
			f.strength = fleetStrength(o.fleet);
			// a fleet with no term shows what its hold keeps it out for (docs/fleet-provisions.md)
			float holdDays = o.fleet != null && ThreatIncConfig.fleetUpkeep() ? ThreatReturns.holdDays(o.fleet) : Float.MAX_VALUE;
			f.eta = !o.indefinite() ? (int) Math.ceil(o.daysLeft()) + " d left"
					: holdDays < Float.MAX_VALUE ? (int) Math.floor(holdDays) + " d aboard" : "-";
			f.rowId = o.fleet;
			f.recallKey = "order:" + i;
			rows.add(f);
		}
		// fleets on tracked legs home (2026-09-06: an expedition's fleets
		// vanished from the board the moment it stood down, though they were
		// still weeks out); Hunt turns one back to hunt the swarms of the
		// hive it is returning FROM, en route or not (as long as that hive still
		// lives) - not only while it happens to sit in a hive system
		List<ThreatReturns.Return> returns = ThreatReturns.all();
		for (int i = 0; i < returns.size(); i++) {
			ThreatReturns.Return r = returns.get(i);
			if (!factionId.equals(r.factionId)) continue;
			if (r.fleet == null || !r.fleet.isAlive() || r.fleet.isExpired()) continue;
			ThreatBases.Base home = ThreatBases.of(r.homeMarketId);
			FleetRow f = new FleetRow();
			f.kind = "Returning";
			f.color = h;
			f.name = r.fleet.getName();
			f.task = "returning to " + (home != null ? home.name() : "-");
			f.status = "en route";
			f.strength = fleetStrength(r.fleet);
			float eta = ThreatReturns.etaDays(r);
			f.eta = eta > 0f ? "~" + (int) Math.ceil(eta) + " d" : "-";
			f.rowId = r.fleet;
			StarSystemAPI origin = returnOriginSystem(r);
			// a convoy going home (an evacuation with a front's marines aboard among them) is no hunter
			if (origin != null && !ThreatConvoys.isConvoy(r.fleet)
					&& ThreatSoftening.combatFP(r.fleet) >= ThreatIncConfig.aidGuardMinFP()) {
				f.huntKey = "returnfleet:" + i + ":" + r.fleet.getId();
				f.huntSystem = origin;
			}
			rows.add(f);
		}
		// scouting parties and patrols (ThreatScouts; 2026-10-24, the user: "how
		// come i cant see scouts under fleets in flight"): no order, so no Recall
		for (ThreatScouts.Scout s : ThreatScouts.all()) {
			if (!factionId.equals(s.factionId)) continue;
			if (s.fleet == null || !s.fleet.isAlive() || s.fleet.isExpired()) continue;
			MarketAPI home = Global.getSector().getEconomy().getMarket(s.homeMarketId);
			String homeName = home != null ? home.getName() : "-";
			FleetRow f = new FleetRow();
			f.kind = s.patrol ? "Patrol" : s.recon ? "Recon" : "Scout";
			f.color = h;
			f.name = homeName;
			if (s.returning) {
				f.task = "returning to " + homeName;
			} else {
				StarSystemAPI at = s.leg < s.route.size() ? ThreatScoutRoute.systemById(s.route.get(s.leg)) : null;
				f.task = at != null ? "sweeping the " + at.getNameWithLowercaseTypeShort()
						+ (s.route.size() > 1 ? " (" + (s.leg + 1) + " of " + s.route.size() + ")" : "") : "sweeping";
			}
			f.status = s.carried != null && !s.carried.isEmpty() ? "sighting aboard" : s.returning ? "en route" : "out";
			f.strength = fleetStrength(s.fleet);
			int days = s.launchedTimestamp != 0L
					? (int) Global.getSector().getClock().getElapsedDaysSince(s.launchedTimestamp) : 0;
			f.eta = days + " d out";
			f.rowId = s.fleet;
			rows.add(f);
		}
		// the player's aid bound for this faction (docs/player-aid.md): listed
		// here so the player can watch it land, and recall it
		if (!com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER.equals(factionId)) {
			for (int i = 0; i < orders.size(); i++) {
				ThreatFleetOrders.Order o = orders.get(i);
				if (!o.aid || !factionId.equals(o.recipientFactionId)) continue;
				FleetRow f = new FleetRow();
				f.kind = ThreatFleetOrders.KIND_HUNT.equals(o.kind) ? "Your hunt" : "Your guard";
				f.color = o.fleet != null && o.fleet.isAlive() ? pos : neg;
				f.name = (o.fleet != null ? o.fleet.getName() : "-") + fromColony(o.baseMarketId);
				f.task = o.task();
				f.status = o.fleet != null && o.fleet.isAlive()
						? (o.arrived ? "on station" : "en route") : "lost";
				f.strength = fleetStrength(o.fleet);
				f.eta = (int) Math.ceil(o.daysLeft()) + " d left";
				f.rowId = o.fleet;
				f.recallKey = "aidorder:" + i;
				f.playerAid = true;
				f.paid = o.credited;
				rows.add(f);
			}
			for (int i = 0; i < convoys.size(); i++) {
				ThreatConvoys.Convoy c = convoys.get(i);
				if (!c.aid || !factionId.equals(c.recipientFactionId)) continue;
				FleetRow f = new FleetRow();
				f.kind = "Your convoy";
				f.color = h;
				f.name = c.fromName() + " -> " + c.toName();
				f.task = cargoText(c.marines, c.armaments, c.fuel, c.supplies, c.ships);
				int hunters = ThreatRaiders.huntersOf(c.fleet);
				f.status = convoyStatus(c, hunters);
				if (hunters > 0) f.color = neg;
				f.strength = "-";
				f.eta = convoyEtaText(c);
				f.rowId = c.fleet;
				f.recallKey = "aidconvoy:" + i;
				f.playerAid = true;
				rows.add(f);
			}
		}
		return rows;
	}

	/** Whether the expedition razes every world it was sent against - the Bombard order's, or an NPC's razing expedition - and lands on none. */
	protected static boolean razesEvery(ThreatPurgeFGI p) {
		if (p.getParams() == null || p.getParams().raidParams == null) return false;
		List<MarketAPI> targets = p.getParams().raidParams.allowedTargets;
		if (targets == null || targets.isEmpty()) return false;
		for (MarketAPI target : targets) {
			if (!p.razes(target)) return false;
		}
		return true;
	}

	/** "<marines> - <fp>" for the Marines - Fleet column - the marines a fleet carries over its fleet points. */
	protected static String fleetStrength(CampaignFleetAPI fleet) {
		if (fleet == null) return "-";
		return Misc.getWithDGS((int) fleet.getCargo().getMarines())
				+ " - " + (int) fleet.getFleetPoints();
	}

	/** The FP an unspawned expedition fields, estimated from its planned fleet sizes: a daily siege's less the damage booked, as its marines. */
	protected static int abstractFP(ThreatPurgeFGI p) {
		return Math.round(p.abstractNow());
	}

	/**
	 * The marines a siege tier's landing draws from the base's reserve, an outcome each
	 * (IncursionManager.siegeTierMarines; the user, 2026-10-24): to hold the landing (Min),
	 * push the line back (Med), overrun the hive (Max). The flotilla is then grown to carry it
	 * and trimmed to the base's free fleet points.
	 */
	protected static float siegeMarineGoal(int tier, MarketAPI base, List<MarketAPI> targets) {
		return IncursionManager.siegeTierMarines(targets, tier);
	}

	/** Every ladder's three labels, index = tier: what is asked for, more, everything sparable. */
	protected static final String[] TIER_LABELS = {"Min", "Med", "Max"};

	/** Where a ladder's first button sits: clear of the widest selector label. */
	protected static final float TIER_LABEL_W = 92f;

	/** Where an Attack ladder's first button sits: clear of "Marines". */
	protected static final float ATTACK_LABEL_W = 60f;

	/** A lever's three labels, index = tier: nothing, half the source's spare, all of it (2026-10-28). */
	protected static final String[] LEVER_LABELS = {"None", "Med", "Max"};

	/** Where a lever's first button sits: clear of "Supplies". */
	protected static final float LEVER_LABEL_W = 62f;

	/** A lever button's width ("None" fits). */
	protected static final float LEVER_BUTTON_W = 46f;

	/** The pitch between two levers on one row: the label, three buttons with their gaps, a gutter. */
	protected static final float LEVER_PITCH = LEVER_LABEL_W + LEVER_BUTTON_W + 14f;

	// ALL_HULLS (an ally's Defend at every hull free, 2026-10-28) went on 2026-10-29 evening: Defend sits
	// on the ally's front row at the Fleet lever's outcome (ThreatWarBoard.addFrontButtons)

	/** The Deploy button's width. */
	protected static final float DEPLOY_W = SMALL_BUTTON_W + 10f;

	/** The Transfer button's width. */
	protected static final float TRANSFER_W = SMALL_BUTTON_W + 8f;
	/** The Staging group's Recall (2026-10-30 21:00). */
	protected static final float RECALL_W = SMALL_BUTTON_W + 8f;

	/** "2" for 2.0, "1.5" for 1.5 - a factor without a trailing zero. */
	protected static String trimZero(float f) {
		return f == Math.floor(f) ? "" + (int) f : "" + f;
	}

	protected static String cargoText(float marines, float armaments, float fuel, float supplies) {
		return cargoText(marines, armaments, fuel, supplies, 0f);
	}

	/** "N marines, N arms, N fuel, N supplies, N ship hulls" - the parts that are there, or "empty". */
	protected static String cargoText(float marines, float armaments, float fuel, float supplies, float ships) {
		List<String> parts = new ArrayList<String>();
		if (marines > 0f) parts.add((int) marines + " marines");
		if (armaments > 0f) parts.add((int) armaments + " arms");
		if (fuel > 0f) parts.add((int) fuel + " fuel");
		if (supplies > 0f) parts.add((int) supplies + " supplies");
		if (ships > 0f) parts.add(ThreatHulls.shown(Commodities.SHIPS, ships) + " ship hulls");
		return parts.isEmpty() ? "empty" : ThreatWarBoard.join(parts);
	}

	/** The nearest base among these within its expedition range - a player colony at any range (docs/strategy-layer.md "Ranges"). */
	protected static MarketAPI nearestBase(List<MarketAPI> markets, StarSystemAPI system) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : markets) {
			if (m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), system.getLocation());
			if (d >= bestDist) continue;
			if (!m.isPlayerOwned() && d > IncursionManager.expeditionRangeLY(m)) continue;
			bestDist = d;
			best = m;
		}
		return best;
	}

	// ------------------------------------------------------------------
	// order buttons: prompt text and execution (called from ThreatIncursionIntel)
	// ------------------------------------------------------------------

	/**
	 * The player's own tab before they mobilise: what mobilising does, and
	 * the button that does it. The selector only offers this tab while the
	 * player holds a colony, so there is always something to put on footing.
	 */
	protected static void renderUnmobilised(ThreatIncursionIntel intel, TooltipMakerAPI main,
			float opad, FactionAPI faction) {
		Color h = Misc.getHighlightColor();
		String name = ThreatWarState.displayName(faction.getId());
		main.addSectionHeading(name + " - not mobilised", faction.getBrightUIColor(),
				faction.getDarkUIColor(), Alignment.MID, opad);
		int colonies = Misc.getPlayerMarkets(false).size();
		main.addPara("Your %s " + (colonies == 1 ? "colony is" : "colonies are") + " not "
				+ "mobilised: nothing is stocked for the war, no convoys run, and your aid to "
				+ "allies has no reserve to draw on.", opad, h, "" + colonies);
		// no seeding clause: a player colony's reserve is its vanilla stockpile, which mobilising does not fill
		main.addPara("Mobilise: every colony goes on War footing, banking marines, heavy "
				+ "armaments, fuel and supplies from its own surplus and carrying %s units of "
				+ "extra demand at size 5, scaled by size. Stand down here any time; reserves are kept.",
				opad, h, "" + (int) ThreatIncConfig.warFootingDemandUnits());
		ButtonAPI mobilise = intel.addGenericButton(main, SELECTOR_BUTTON_W, "Mobilise",
				BUTTON_MOBILISE + faction.getId());
		disableWith(main, mobilise, ThreatWarState.playerMayMobilise(),
				"You need a colony to mobilise.");
	}

	/** Whether this button id is one of the faction view's order buttons. */
	public static boolean isOrderButton(String id) {
		return id.startsWith(BUTTON_GUARD) || id.startsWith(BUTTON_STAGE)
				|| id.startsWith(BUTTON_HUNT) || id.startsWith(BUTTON_SIEGE)
				|| id.startsWith(BUTTON_BOMBARD) || id.startsWith(BUTTON_SCOUT)
				|| id.startsWith(BUTTON_RECALL) || id.startsWith(BUTTON_RECALL_ALL) || id.startsWith(BUTTON_DETACH)
				|| id.startsWith(BUTTON_SUPPLY)
				|| id.startsWith(BUTTON_PULLOUT) || id.startsWith(BUTTON_SUPPORT)
				|| id.startsWith(BUTTON_DEFEND)
				|| id.startsWith(BUTTON_PUSH) || id.startsWith(BUTTON_ENTRENCH)
				|| id.startsWith(BUTTON_OUTPOST)
				|| id.startsWith(BUTTON_AID_DEFEND) || id.startsWith(BUTTON_AID_NEED)
				|| id.startsWith(BUTTON_AID_STRIKE) || id.startsWith(BUTTON_MOBILISE)
				|| id.startsWith(BUTTON_STAND_DOWN)
				|| id.startsWith(BUTTON_DEFEND_PUSH) || id.startsWith(BUTTON_DEFEND_ENTRENCH)
				|| id.startsWith(BUTTON_REQ_ACCEPT) || id.startsWith(BUTTON_REQ_CANCEL);
	}

	/** Splits "prefix" + "factionId:target" into [prefix, factionId, target]. */
	protected static String[] parse(String id) {
		String[] prefixes = {BUTTON_GUARD, BUTTON_STAGE, BUTTON_HUNT, BUTTON_SIEGE, BUTTON_BOMBARD, BUTTON_SCOUT,
				BUTTON_RECALL, BUTTON_RECALL_ALL,
				BUTTON_DETACH,
				BUTTON_SUPPLY, BUTTON_PULLOUT, BUTTON_SUPPORT, BUTTON_DEFEND, BUTTON_PUSH, BUTTON_ENTRENCH,
				BUTTON_OUTPOST, BUTTON_AID_DEFEND, BUTTON_AID_NEED,
				BUTTON_AID_STRIKE, BUTTON_MOBILISE, BUTTON_STAND_DOWN,
				BUTTON_DEFEND_PUSH, BUTTON_DEFEND_ENTRENCH, BUTTON_REQ_ACCEPT, BUTTON_REQ_CANCEL};
		for (String p : prefixes) {
			if (!id.startsWith(p)) continue;
			String rest = id.substring(p.length());
			int colon = rest.indexOf(':');
			if (colon < 0) return new String[] {p, rest, ""};
			return new String[] {p, rest.substring(0, colon), rest.substring(colon + 1)};
		}
		return null;
	}

	/** The confirm-dialog text for an order button. */
	public static void addOrderPrompt(TooltipMakerAPI prompt, String id, ThreatIncursionIntel intel) {
		String[] parts = parse(id);
		if (parts == null) return;
		addOrderPromptBody(prompt, id, parts, intel);
	}

	/** The order's own confirm text, by button. */
	protected static void addOrderPromptBody(TooltipMakerAPI prompt, String id, String[] parts, ThreatIncursionIntel intel) {
		parts = aidNeedParts(parts);
		int siegeTier = intel != null ? intel.getSiegeTier() : 0;
		int[] frontLevers = intel != null ? intel.frontLevers() : new int[] {0, 0, 0};
		int[] levers = intel != null ? intel.colonyLevers() : new int[ThreatReserves.AID_COMMODITIES.length];
		FactionAPI faction = Global.getSector().getFaction(parts[1]);
		String who = faction == null ? parts[1] : faction.isPlayerFaction() ? "your"
				: faction.getDisplayNameWithArticle();
		Color h = Misc.getHighlightColor();
		if (BUTTON_MOBILISE.equals(parts[0])) {
			int colonies = Misc.getPlayerMarkets(false).size();
			prompt.addPara("Mobilise your faction? Every colony of yours (%s) goes on War footing "
					+ "now, carrying %s units of extra demand at size 5, scaled by size.", 0f, h,
					"" + colonies, "" + (int) ThreatIncConfig.warFootingDemandUnits());
			return;
		}
		if (BUTTON_STAND_DOWN.equals(parts[0])) {
			prompt.addPara("Stand your faction down? War footing and its demand are lifted, "
					+ "reserves are kept as they stand, and fleets already out finish their "
					+ "runs.", 0f);
			return;
		}
		if (BUTTON_GUARD.equals(parts[0])) {
			// DEPLOY (ThreatDeploy, 2026-10-29): a colony, the player's outpost or a hive its landing attacks - a new fleet every press
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			addDeployPrompt(prompt, ThreatDeploy.plan(target, levers));
		} else if (BUTTON_STAGE.equals(parts[0]) && fleetLeverSet(levers) && faction != null && faction.isPlayerFaction()) {
			// Transfer at the Fleet lever is the Deploy (2026-10-31)
			addDeployPrompt(prompt, ThreatDeploy.plan(ThreatBases.of(parts[2]), levers));
		} else if (BUTTON_STAGE.equals(parts[0])) {
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			MarketAPI donor = ThreatConvoys.stageDonor(target, faction, levers);
			if (donor == null) {
				prompt.addPara(ThreatConvoys.stageOffReason(target, levers,
						faction != null && faction.isPlayerFaction() ? "yours" : who), 0f);
			} else {
				float[] load = ThreatConvoys.stageLoad(donor, target, levers);
				prompt.addPara("Order a convoy from " + donor.getName() + " to " + target.name()
						+ " with %s? It can be intercepted on the way.", 0f, h,
						cargoText(load[0], load[1], load[2], load[3], ThreatConvoys.hullsIn(load)));
			}
		} else if (BUTTON_HUNT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			// sized for the outcome (ThreatAid.huntAskFP), from the colony that can field it
			ThreatAid.Quote q = ThreatAid.quoteStrike(system, siegeTier);
			if (!q.ok() || system == null) {
				prompt.addPara(q.reason != null ? q.reason.toString() : "The situation has changed.", 0f);
			} else {
				String[] f = forcePrompt(q.points, q.free, q.fleets, "" + (int) ThreatIncConfig.softenDays());
				prompt.addPara("Order a task force of " + f[0] + " from " + q.source.getName()
						+ " to hunt the Defense Swarms in the " + system.getNameWithLowercaseType()
						+ " for %s days?", 0f, h, highlights(f));
				if (q.sized != null) prompt.addPara(q.sized, 3f);
				addProvisionsLine(prompt, q.source, q.fp, system.getLocation(), false);
			}
		} else if (BUTTON_SCOUT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			String why = system != null ? ThreatScouts.scoutOffReason(system) : "No target.";
			MarketAPI home = why == null ? ThreatScouts.playerScoutHome(system) : null;
			if (why != null || home == null) {
				prompt.addPara(why != null ? why : "The situation has changed.", 0f);
			} else {
				float[] cost = ThreatScouts.playerScoutCost(home, system);
				prompt.addPara("Send a scouting party of %s fleet points from " + home.getName() + " to the "
						+ system.getNameWithLowercaseType() + "?", 0f, h, "" + (int) ThreatIncConfig.scoutFleetPoints());
				addTakesLine(prompt, home, cost[0], cost[1]);
				float age = ThreatIntel.age(ThreatIntel.observerOf(Global.getSector().getPlayerFaction()), system.getId());
				if (age < Float.MAX_VALUE / 2f) prompt.addPara("Last seen %s days ago.", 3f, h, "" + (int) age);
				else prompt.addPara("Never seen.", 3f);
			}
		} else if (BUTTON_SIEGE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = IncursionManager.attackBase(faction, system);
			String why = IncursionManager.attackBlockReason(base, faction, system);
			if (why != null) {
				prompt.addPara(why, 0f);
			} else {
				addAttackPrompt(prompt, IncursionManager.attackPlan(base, faction, system));
			}
		} else if (BUTTON_BOMBARD.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = null;
			if (faction != null && system != null) {
				base = nearestBase(ThreatReserves.marketsOf(faction.getId()), system);
			}
			String why = IncursionManager.bombardBlockReason(base, faction, system, siegeTier);
			if (why != null) {
				prompt.addPara(why, 0f);
			} else {
				addBombardPrompt(prompt, base, faction, system, siegeTier);
			}
		} else if (BUTTON_RECALL_ALL.equals(parts[0])) {
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			prompt.addPara(world == null ? "The situation has changed." : recallAllText(faction, world), 0f);
		} else if (BUTTON_RECALL.equals(parts[0])) {
			if (parts[2].startsWith("purgefleet:") || parts[2].startsWith("tffleet:")) {
				CampaignFleetAPI fleet = groupFleet(parts[2]);
				prompt.addPara("Recall " + (fleet != null ? fleet.getName() : "this fleet")
						+ " to its base? The rest of its group fights on"
						+ (fleet != null && fleet.getCargo().getMarines() > 0
								? "; the " + Misc.getWithDGS(fleet.getCargo().getMarines())
								+ " marines aboard come home with it." : "."), 0f);
			} else {
				prompt.addPara("Recall this fleet to its base? A convoy brings its cargo home; an "
						+ "expedition abandons its campaign; a task force breaks off.", 0f);
			}
		} else if (BUTTON_DETACH.equals(parts[0])) {
			CampaignFleetAPI fleet = groupFleet(parts[2]);
			StarSystemAPI hive = groupTargetSystem(parts[2]);
			MarketAPI first = hive != null
					? ThreatSoftening.huntTarget(ThreatIntel.observerOf(faction), hive.getId()) : null;
			if (fleet == null || hive == null) {
				prompt.addPara("That fleet is no longer with its group.", 0f);
			} else if (first == null) {
				prompt.addPara("No Defense Swarms seen there.", 0f);
			} else {
				prompt.addPara("Detach " + fleet.getName() + " to hunt the Defense Swarms in the %s for "
						+ "%s days?", 0f, h,
						hive.getNameWithLowercaseTypeShort(), "" + (int) ThreatIncConfig.softenDays());
			}
		} else if (BUTTON_OUTPOST.equals(parts[0])) {
			com.fs.starfarer.api.campaign.SectorEntityToken planet =
					Global.getSector().getEntityById(parts[2]);
			MarketAPI base = faction != null && planet != null
					? ThreatOutposts.payingBase(faction, planet) : null;
			String station = faction != null ? ThreatOutposts.specIdFor(faction) : "orbitalstation";
			String cost = faction != null && faction.isPlayerFaction()
					? Misc.getDGSCredits(ThreatIncConfig.outpostCredits())
					: (int) ThreatIncConfig.outpostSupplies() + " supplies and "
							+ (int) ThreatIncConfig.outpostFuel() + " fuel from "
							+ (base != null ? base.getName() + "'s reserve" : "a base's reserve");
			prompt.addPara("Build " + who + " " + station.replace('_', ' ') + " over "
					+ (planet != null ? planet.getName() : "the world") + " for %s? The station "
					+ "blocks the swarm from seeding the world again until it is destroyed"
					+ (base == null ? " - but no base in reach can pay for it right now." : "."),
					0f, h, cost);
		} else if (BUTTON_AID_NEED.equals(parts[0])) {
			// SEND on an NPC faction's need row (2026-10-30): a convoy of that good alone at its lever
			String[] wt = withdrawTarget(parts[2]);
			MarketAPI target = wt[0] != null ? Global.getSector().getEconomy().getMarket(wt[0]) : null;
			ThreatAid.Quote q = ThreatAid.quoteAid(target, needLevers(wt[1], levers));
			aidPrompt(prompt, target, q, who, h);
		} else if (BUTTON_AID_DEFEND.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			// DEFEND an ally's sieged world at the fronts' Fleet lever (2026-10-29 evening): match the swarm
			// over it / x1.25 / x1.5 (ThreatAid.orbitAskFP), as the player's own Defend; every hull free before
			int orbitTier = frontLevers.length > 2 ? frontLevers[2] : 0;
			ThreatAid.Quote q = ThreatAid.quoteDefend(target, orbitTier);
			if (!q.ok() || target == null) {
				prompt.addPara(q.reason != null ? q.reason.toString() : "The situation has changed.", 0f);
			} else {
				String[] f = forcePrompt(q.points, q.free, q.fleets, "" + (int) ThreatIncConfig.guardDays());
				prompt.addPara("Send a task force of " + f[0] + " from " + q.source.getName()
						+ " to take the orbit of " + target.getName() + " for %s days, on behalf of "
						+ who + "? Fuel and supplies come from " + q.source.getName()
						+ "'s reserve, and its hulls are held against that colony's capacity "
						+ "until they are home. Standing with "
						+ (faction != null ? faction.getDisplayName() : "the faction")
						+ " is earned when it arrives and again when it serves its term.", 0f, h,
						highlights(f));
				if (q.sized != null) prompt.addPara(q.sized, 3f);
				addProvisionsLine(prompt, q.source, q.fp, target.getLocationInHyperspace(), false);
			}
		} else if (BUTTON_REQ_ACCEPT.equals(parts[0]) || BUTTON_REQ_CANCEL.equals(parts[0])) {
			ThreatAidRequests.Offer o = ThreatAidRequests.offerById(parts[2]);
			ThreatBases.Base target = o != null ? ThreatAidRequests.resolveTarget(o.targetId) : null;
			boolean cancel = BUTTON_REQ_CANCEL.equals(parts[0]);
			if (o == null || target == null || (cancel ? !o.atSea() : o.accepted())) {
				prompt.addPara("The offer is gone.", 0f);
			} else if (cancel) {
				prompt.addPara("Turn " + ThreatWarState.displayName(o.factionId) + "'s %s for " + target.name()
						+ " home? Nothing is charged.", 0f, h, ThreatAidRequests.amountText(o));
			} else {
				prompt.addPara("Accept %s from " + o.fromName + " for " + target.name() + "? Standing with "
						+ ThreatWarState.displayName(o.factionId) + " %s on receipt; the convoy can be intercepted on the way.",
						0f, h, ThreatAidRequests.amountText(o), "-" + o.price);
			}
		} else if (BUTTON_AID_STRIKE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			ThreatAid.Quote q = ThreatAid.quoteStrike(system);
			if (!q.ok() || system == null) {
				prompt.addPara(q.reason != null ? q.reason.toString() : "The situation has changed.", 0f);
			} else {
				String[] f = forcePrompt(q.points, q.free, q.fleets, "" + (int) ThreatIncConfig.softenDays());
				prompt.addPara("Send a task force of " + f[0] + " from " + q.source.getName()
						+ " to hunt the Defense Swarms in the " + system.getNameWithLowercaseType()
						+ " for %s days?", 0f, h, highlights(f));
				addProvisionsLine(prompt, q.source, q.fp, system.getLocation(), false);
			}
		} else if (BUTTON_SUPPORT.equals(parts[0]) || BUTTON_DEFEND.equals(parts[0])) {
			String kind = BUTTON_DEFEND.equals(parts[0]) ? ThreatFleetOrders.KIND_DEFEND
					: ThreatFleetOrders.KIND_SUPPORT;
			String verb = ThreatFleetOrders.orbitVerb(kind);
			String days = "" + (int) ThreatFleetOrders.orbitDays(kind);
			MarketAPI hive = Global.getSector().getEconomy().getMarket(parts[2]);
			MarketAPI base = faction == null || hive == null ? null : faction.isPlayerFaction()
					? ThreatAid.pickTaskForceSource(hive.getLocationInHyperspace())
					: ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace());
			// the fronts' Fleet lever is the outcome tier since 2026-10-29 (the Force ladder went with the Attack's)
			int orbitTier = frontLevers.length > 2 ? frontLevers[2] : siegeTier;
			String why = ThreatFleetOrders.orbitBlockReason(faction, hive, kind, orbitTier);
			ThreatFleetOrders.Reassignable near = why == null && faction != null && hive != null
					? ThreatFleetOrders.nearestReassignable(faction, hive, kind) : null;
			if (why != null) {
				prompt.addPara(why, 0f);
			} else if (near != null) {
				prompt.addPara("Detach %s (" + near.duty + ", " + near.where() + ") to " + verb + " "
						+ hive.getName() + " for %s days?", 0f, h, near.fleet.getName(), days);
				prompt.addPara(ThreatFleetOrders.orbitEffect(hive, near.fleet.getFleetPoints(), kind, near.fleet), 10f);
			} else {
				// sized for the outcome (ThreatAid.orbitAskFP: the swarm over the world by the tier's margin)
				ThreatAid.Quote q = ThreatAid.quoteOrbit(hive, kind, orbitTier);
				if (!q.ok()) {
					prompt.addPara(q.reason != null ? q.reason.toString() : "The situation has changed.", 0f);
				} else {
					String[] f = forcePrompt(q.points, q.free, q.fleets, days);
					prompt.addPara("Order a task force of " + f[0] + " from " + q.source.getName() + " to " + verb + " "
							+ hive.getName() + " for %s days?", 0f, h, highlights(f));
					if (q.sized != null) prompt.addPara(q.sized, 3f);
					prompt.addPara(ThreatFleetOrders.orbitEffect(hive, q.points, kind, null), 3f);
					addProvisionsLine(prompt, q.source, q.fp, hive.getLocationInHyperspace(), false);
				}
			}
		} else if (BUTTON_DEFEND_PUSH.equals(parts[0]) || BUTTON_DEFEND_ENTRENCH.equals(parts[0])) {
			// the defenders' stance on the player's own world (2026-10-17)
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = world != null
					? ThreatGroundFronts.getFront(world.getId()) : null;
			boolean push = BUTTON_DEFEND_PUSH.equals(parts[0]);
			String why = push ? ThreatGroundFronts.defenderPushBlockReason(front, world)
					: ThreatGroundFronts.defenderEntrenchBlockReason(front, world);
			if (why != null) {
				prompt.addPara(why, 0f);
			} else if (push) {
				prompt.addPara("Order the marines of " + world.getName() + " to push? "
						+ ThreatGroundFronts.defenderPushQuote(front, world), 0f);
			} else {
				prompt.addPara("Order the marines of " + world.getName() + " to hold? "
						+ "No push; the defence rises to x%s over %s days, and the cover "
						+ "is lost with a " + ThreatGroundFronts.layerName(world) + ".", 0f, h,
						String.format("%.1f", ThreatIncConfig.frontEntrenchDefenseBonus()),
						"" + (int) ThreatIncConfig.frontEntrenchDays());
			}
		} else if (BUTTON_PUSH.equals(parts[0]) || BUTTON_ENTRENCH.equals(parts[0])) {
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = world != null
					? ThreatGroundFronts.getFront(world.getId()) : null;
			boolean push = BUTTON_PUSH.equals(parts[0]);
			String why = push ? ThreatGroundFronts.pushBlockReason(front, world)
					: ThreatGroundFronts.entrenchBlockReason(front);
			if (world == null || front == null) {
				prompt.addPara("There is no front there any more.", 0f);
			} else if (why != null) {
				prompt.addPara(why, 0f);
			} else if (push) {
				prompt.addPara("Order the front on " + world.getName() + " to push on stratum %s of %s? "
						+ "At its current strength the stratum falls in about %s days at about %s "
						+ "casualties; losses run %s marines a day and armaments burn x%s "
						+ "while it does.", 0f, h, "" + (front.strataHeld + 1), "" + world.getSize(),
						"" + (int) Math.ceil(ThreatGroundFronts.pushDaysEstimate(front, world)),
						Misc.getWithDGS(ThreatGroundFronts.pushCasualtyEstimate(front, world)),
						ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(front, world, true)),
						String.format("%.0f", ThreatIncConfig.frontPushUpkeepMult()));
			} else {
				prompt.addPara("Order the front on " + world.getName() + " to break off and hold? "
						+ "Losses fall to about %s marines a day and it defends at x%s from "
						+ "cover; its progress on stratum %s is lost.", 0f, h,
						ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(front, world, false)),
						String.format("%.1f", ThreatIncConfig.frontEntrenchDefenseBonus()),
						"" + (front.strataHeld + 1));
			}
		} else if (BUTTON_SUPPLY.equals(parts[0]) && fleetLeverSet(levers) && faction != null && faction.isPlayerFaction()) {
			// Transfer at the Fleet lever deploys a fleet over the hive (2026-10-31)
			addDeployPrompt(prompt, ThreatDeploy.plan(ThreatBases.of(ThreatIncData.resolveColonyMarket(parts[2])), levers));
		} else if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = hive != null
					? ThreatGroundFronts.getFront(hive.getId()) : null;
			MarketAPI base = faction != null && hive != null
					? ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace()) : null;
			String refused = BUTTON_SUPPLY.equals(parts[0])
					? ThreatConvoys.supplyBlockReason(faction, hive, frontLevers)
					: ThreatConvoys.pullOutBlockReason(faction, hive);
			if (hive == null || front == null) {
				prompt.addPara("There is no front there any more.", 0f);
			} else if (refused != null) {
				prompt.addPara(refused, 0f);
			} else if (BUTTON_SUPPLY.equals(parts[0])) {
				float[] asked = ThreatConvoys.supplyAsk(front, hive, frontLevers);
				prompt.addPara("Send a supply run from " + (base != null ? base.getName()
						: "the nearest base") + " to the front on " + hive.getName() + "? It "
						+ "carries %s marines and %s heavy armaments; the run waits at the "
						+ "jump-point while Defense Swarms hold the orbit, and can be intercepted "
						+ "on the way.", 0f, h,
						Misc.getWithDGS((int) asked[0]), Misc.getWithDGS((int) asked[1]));
			} else {
				prompt.addPara("Send an evacuation convoy from " + (base != null ? base.getName()
						: "the nearest base") + " to lift the front off " + hive.getName()
						+ "? Its %s marines and %s heavy armaments come home into the base's "
						+ "reserve. The convoy needs the orbit clear to land.", 0f, h,
						Misc.getWithDGS(Math.round(front.marines)),
						Misc.getWithDGS((int) front.armaments));
			}
		}
	}

	/**
	 * The Bombard order's confirm: the question and the numbers, one fact a
	 * line - the fleets and their points of the base's free points, the fuel
	 * it carries of what the saturation takes and what it draws of the
	 * reserve, then each hive as the fleets would saturate it in turn
	 * (IncursionManager.razeRun): the days in orbit, about the fleet points the
	 * guns take, and the days its structures are then down for (a hive has no
	 * bar, ThreatRazing.razes).
	 */
	protected static void addBombardPrompt(TooltipMakerAPI prompt, MarketAPI base, FactionAPI faction,
			StarSystemAPI system, int tier) {
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		List<MarketAPI> targets = IncursionManager.bombardTargets(system);
		// the tier is the outcome (IncursionManager.bombardGoalDays): the flotilla is the need, the
		// task forces on station first (playerExpeditionSizes at Min)
		float downGoal = IncursionManager.bombardGoalDays(tier);
		List<Integer> sizes = IncursionManager.playerExpeditionSizes(base, faction,
				IncursionManager.bombardSizes(base, faction, targets, downGoal), 0, null);
		float points = ThreatAidCapacity.expeditionPoints(sizes);
		float[] fuel = IncursionManager.expeditionFuelCarried(base, system, targets, sizes,
				IncursionManager.idsOf(targets), downGoal);
		prompt.addPara("Order a bombing expedition from " + base.getName() + " against the "
				+ system.getNameWithLowercaseType() + "?", 0f);
		prompt.addPara(downGoal > 0f ? "Bombs each hive until its structures are down %s days." : "Bombs each hive until a day adds less than a day.",
				10f, h, "" + (int) downGoal);
		if (faction.isPlayerFaction() && ThreatAidCapacity.enabled()) {
			prompt.addPara("%s fleets, %s of the %s fleet points free.", 3f, h, "" + sizes.size(),
					Misc.getWithDGS((int) points),
					Misc.getWithDGS((int) Math.max(0f, ThreatAidCapacity.freeFP(base))));
		} else {
			prompt.addPara("%s fleets, %s fleet points.", 3f, h, "" + sizes.size(), Misc.getWithDGS((int) points));
		}
		// the gate (bombardBlockFacts) lets only a fully fuelled bombing through: one line, what it
		// takes from the base (the user, 2026-10-11: "nobody knows wtf carry vs draw means re fuel")
		prompt.addPara("Takes %s fuel from " + base.getName() + "'s %s: %s for the bombing, %s for the passage.", 3f, h,
				Misc.getWithDGS(Math.round(fuel[5])), Misc.getWithDGS(Math.round(fuel[6])),
				Misc.getWithDGS(Math.round(fuel[4])), Misc.getWithDGS(Math.round(fuel[0])));
		float[] takes = IncursionManager.expeditionWants(base, system, targets, sizes, IncursionManager.idsOf(targets),
				0f, downGoal);
		prompt.addPara("Takes %s supplies of its %s.", 3f, h, Misc.getWithDGS((int) Math.ceil(takes[3])),
				Misc.getWithDGS((int) Math.max(0f, ThreatReserves.available(base, Commodities.SUPPLIES))));
		float[][] run = IncursionManager.razeRun(targets, points, fuel[3], faction.getId(), downGoal);
		for (int i = 0; i < targets.size(); i++) {
			String name = targets.get(i).getName();
			float[] r = run[i];
			if (r[4] < 1f) {
				prompt.addPara(name + ": not reached.", 3f);
			} else if (r[5] < 1f) {
				prompt.addPara(name + ": no fuel left for it.", 3f);
			} else if (!ThreatRazing.razes(targets.get(i))) {
				prompt.addPara(name + ": %s days in orbit, %s fleet points bombing, about %s fleet points lost, down %s days.", 3f, h,
						"" + (int) r[0], Misc.getWithDGS((int) r[7]), "" + Math.round(r[2]), "" + (int) r[6]);
			} else {
				boolean razed = r[3] >= 1f;
				LabelAPI line = prompt.addPara(name + ": %s days in orbit, about %s fleet points lost, %s.", 3f, h,
						"" + (int) r[0], "" + Math.round(r[2]), razed ? "razed" : "not razed");
				line.setHighlightColors(h, h, razed ? h : neg);
			}
		}
	}

	/** The Deploy order's confirmation: the new fleet's size and load at the levers, one line a fact (ThreatDeploy.Plan). */
	protected static void addDeployPrompt(TooltipMakerAPI prompt, ThreatDeploy.Plan d) {
		Color h = Misc.getHighlightColor();
		if (d == null || d.target == null || !d.ok()) {
			prompt.addPara(d != null && d.reason != null ? d.reason.toString() : "The situation has changed.", 0f);
			return;
		}
		boolean self = d.target.market != null && d.target.market == d.source;
		prompt.addPara("Deploy a fleet " + (self ? "over " : "from " + d.source.getName() + " to ")
				+ d.target.name() + "?", 0f);
		prompt.addPara("Fleet: %s fleet points of " + d.source.getName() + "'s %s free.", 10f, h,
				Misc.getWithDGS((int) d.targetPts), Misc.getWithDGS((int) d.ownFree));
		if (d.capped) prompt.addPara("Capped at %s at this lever.", 3f, h, Misc.getWithDGS((int) d.targetPts));
		String[] goods = { "Marines", "Arms", "Fuel", "Supplies" };
		for (int i = 0; i < 4; i++) {
			prompt.addPara(goods[i] + ": %s" + (i < 2 ? ", as many as the fleet carries." : "."), 3f, h,
					Misc.getWithDGS((int) d.load[i]));
		}
		prompt.addPara("Attack sails what is deployed; Recall dissolves it.", 3f);
	}

	/** The Attack's confirmation (phase D, docs/strata-are-the-threat.md 8): one line per fact, figures as "N to what it buys". */
	protected static void addAttackPrompt(TooltipMakerAPI prompt, IncursionManager.Attack a) {
		Color h = Misc.getHighlightColor();
		if (a == null || a.base == null || a.system == null) {
			prompt.addPara("The situation has changed.", 0f);
			return;
		}
		prompt.addPara("Attack the " + a.system.getNameWithLowercaseType() + " with the fleet deployed at "
				+ a.base.getName() + "?", 0f);
		prompt.addPara("Fleet: %s fleet points" + (a.sizes.size() > 1 ? " in %s fleets." : "."), 10f, h,
				Misc.getWithDGS((int) a.points), "" + a.sizes.size());
		if (a.razeFuel > 0.5f && a.target != null) {
			float now = ThreatStrata.of(a.target);
			float after = Math.max(0f, now - a.strata);
			int sizeAfter = after <= 0f ? 0 : ThreatStrata.sizeOf(after, ThreatColonyManager.maxColonySize(a.target));
			int down = a.target.getSize() - sizeAfter;
			String outcome = after <= 0f ? a.target.getName() + " razed to nothing"
					: down == 1 ? "a size down from " + ThreatStrata.fmt(now)
					: down > 1 ? down + " sizes down from " + ThreatStrata.fmt(now)
					: "of " + a.target.getName() + "'s " + ThreatStrata.fmt(now);
			prompt.addPara("Fuel %s: %s days of bombardment, %s strata - " + outcome + ".", 3f, h,
					Misc.getWithDGS(Math.round(a.razeFuel)), "" + (int) Math.ceil(a.days), ThreatStrata.fmt(a.strata));
		} else {
			prompt.addPara("Fuel: the passage only, nothing razed.", 3f);
		}
		if (a.marines > 0f && a.target != null) {
			prompt.addPara("Marines %s against %s strata on the ground" + (a.arms > 0.5f ? ", %s arms." : "."), 3f, h,
					Misc.getWithDGS((int) a.marines), ThreatStrata.fmt(ThreatStrata.standing(a.target)),
					Misc.getWithDGS((int) a.arms));
		} else {
			prompt.addPara("Marines: none.", 3f);
		}
		prompt.addPara("Supplies %s aboard, the attack takes %s.", 3f, h, Misc.getWithDGS((int) a.supplies),
				Misc.getWithDGS((int) Math.ceil(a.suppliesWant)));
		if (a.budFP > 0f) {
			prompt.addPara(a.system.getBaseName() + " can bud %s fleet points against it in %s days.", 3f, h,
					Misc.getWithDGS((int) a.budFP), "" + (int) a.budDays);
		} else {
			prompt.addPara("Nothing seen there to bud against it.", 3f);
		}
		prompt.addPara("Passage %s fuel, %s days.", 3f, h, Misc.getWithDGS(Math.round(a.passage)), "" + (int) a.passageDays);
	}

	/** Executes an order button. Returns a message for the log, or null if nothing happened. */
	public static String executeOrder(String id, ThreatIncursionIntel intel) {
		String[] parts = parse(id);
		if (parts == null) return null;
		int siegeTier = intel != null ? intel.getSiegeTier() : 0;
		int[] frontLevers = intel != null ? intel.frontLevers() : new int[] {0, 0, 0};
		int[] levers = intel != null ? intel.colonyLevers() : new int[ThreatReserves.AID_COMMODITIES.length];
		Random random = new Random();
		parts = aidNeedParts(parts);
		FactionAPI faction = Global.getSector().getFaction(parts[1]);
		if (faction == null) return null;
		// the player's own war footing, by choice alone
		if (BUTTON_MOBILISE.equals(parts[0])) {
			if (!faction.isPlayerFaction() || !ThreatWarState.playerMayMobilise()) return null;
			ThreatWarState.mobilisePlayer();
			return "mobilise";
		}
		if (BUTTON_STAND_DOWN.equals(parts[0])) {
			if (!faction.isPlayerFaction() || !ThreatWarState.isAtWar(faction)) return null;
			ThreatWarState.standDownPlayer();
			return "stand-down";
		}
		// the player's aid to another faction (docs/player-aid.md): no order authority needed
		if (BUTTON_AID_NEED.equals(parts[0])) {
			// SEND on a need row (2026-10-30): a convoy of that good alone at its lever (a fleets need became Defend above)
			String[] wt = withdrawTarget(parts[2]);
			MarketAPI target = wt[0] != null ? Global.getSector().getEconomy().getMarket(wt[0]) : null;
			return target != null && ThreatAid.dispatchAid(target, needLevers(wt[1], levers), random) ? "aid-need" : null;
		}
		if (BUTTON_AID_DEFEND.equals(parts[0])) {
			// at the fronts' Fleet lever (2026-10-29 evening), as the player's own Defend
			return ThreatAid.dispatchDefend(Global.getSector().getEconomy().getMarket(parts[2]),
					frontLevers.length > 2 ? frontLevers[2] : 0) ? "aid-defend" : null;
		}
		if (BUTTON_REQ_ACCEPT.equals(parts[0])) {
			ThreatAidRequests.Offer o = ThreatAidRequests.offerById(parts[2]);
			return o != null && ThreatAidRequests.accept(o, random) == null ? "accept" : null;
		}
		if (BUTTON_REQ_CANCEL.equals(parts[0])) {
			return ThreatAidRequests.cancel(ThreatAidRequests.offerById(parts[2])) ? "cancel" : null;
		}
		if (BUTTON_AID_STRIKE.equals(parts[0])) {
			// the aid tab shows no Force selector: the hunt it quotes is Min (quoteStrike at 0 on its button and prompt)
			return ThreatAid.dispatchStrike(ThreatWarBoard.getSystem(parts[2]), parts[1], 0) ? "aid-strike" : null;
		}
		if (BUTTON_RECALL.equals(parts[0])
				&& (parts[2].startsWith("aidorder:") || parts[2].startsWith("aidconvoy:"))) {
			return recall(parts[1], parts[2]) ? "recall" : null;
		}
		if (!ThreatFleetOrders.canPlayerOrder(faction)) return null;
		if (BUTTON_GUARD.equals(parts[0])) {
			// DEPLOY (ThreatDeploy, 2026-10-29): a colony, the player's outpost or a hive its landing attacks - a new fleet every press
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			return ThreatDeploy.execute(ThreatDeploy.plan(target, levers), faction);
		}
		if (BUTTON_STAGE.equals(parts[0])) {
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			if (target == null) return null;
			// Transfer at the Fleet lever is the Deploy (2026-10-31)
			if (fleetLeverSet(levers) && faction.isPlayerFaction()) return ThreatDeploy.execute(ThreatDeploy.plan(target, levers), faction);
			ThreatConvoys.Convoy c = ThreatConvoys.stageTo(target, faction, levers, random);
			if (c == null) {
				ThreatNotice n = ThreatNotice.titled("Stage Refused").bad().icon(faction);
				if (faction.isPlayerFaction()) {
					n.line("No colony within convoy range can spare materiel for %s", ThreatNotice.base(target));
				} else {
					n.line("No %s colony within convoy range can spare materiel for %s",
							ThreatNotice.faction(faction), ThreatNotice.base(target));
				}
				n.send();
				return null;
			}
			return "stage";
		}
		if (BUTTON_HUNT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			return ThreatFleetOrders.dispatchHunt(faction, system, siegeTier) != null ? "hunt" : null;
		}
		if (BUTTON_SCOUT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			if (system == null) return null;
			String why = ThreatScouts.scoutOffReason(system);
			ThreatScouts.Scout s = why == null ? ThreatScouts.playerRecon(system) : null;
			if (s == null) {
				ThreatNotice.titled("Scouts Refused").bad().icon(faction)
						.line(why != null ? why : "No party could be raised for the " + system.getNameWithLowercaseTypeShort())
						.send();
				return null;
			}
			ThreatNotice.titled("Scouts Sail").icon(faction)
					.line("From %s, %s fleet points", ThreatNotice.market(Global.getSector().getEconomy().getMarket(s.homeMarketId)),
							"" + (int) ThreatIncConfig.scoutFleetPoints())
					.line("To look at the %s", system.getNameWithLowercaseType())
					.send();
			return "scout";
		}
		if (BUTTON_SIEGE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			if (system == null) return null;
			MarketAPI base = IncursionManager.attackBase(faction, system);
			// the button's gate again (the board may have been open a while)
			ThreatNotice.Reason why = IncursionManager.attackBlockFacts(base, faction, system);
			if (why != null) {
				ThreatNotice.titled("Attack Refused").bad().icon(faction).lines(why).send();
				return null;
			}
			IncursionManager.Attack a = IncursionManager.attackPlan(base, faction, system);
			ThreatPurgeFGI purge = IncursionManager.launchAttack(a, faction, random);
			if (purge == null) {
				ThreatNotice.titled("Attack Refused").bad().icon(faction)
						.line("No expedition could be raised at %s", ThreatNotice.market(base)).send();
				return null;
			}
			ThreatNotice n = ThreatNotice.titled("Attack Sails").icon(faction)
					.line("The fleet deployed at %s: %s fleet points", ThreatNotice.market(base),
							Misc.getWithDGS((int) a.points))
					.line("Bound for the %s", system.getNameWithLowercaseType());
			if (a.razeFuel > 0.5f) n.line("%s fuel to raze %s strata", Misc.getWithDGS(Math.round(a.razeFuel)), ThreatStrata.fmt(a.strata));
			if (a.marines > 0f) n.line("%s marines to land", Misc.getWithDGS((int) a.marines));
			n.line("%s supplies aboard", Misc.getWithDGS((int) a.supplies));
			n.send();
			return "attack";
		}
		if (BUTTON_BOMBARD.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			if (system == null) return null;
			MarketAPI base = nearestBase(ThreatReserves.marketsOf(faction.getId()), system);
			// the button's gate again: the board may have been open a while
			ThreatNotice.Reason why = IncursionManager.bombardBlockFacts(base, faction, system, siegeTier);
			List<MarketAPI> targets = IncursionManager.bombardTargets(system);
			ThreatPurgeFGI purge = null;
			if (why == null) {
				// saturation only (docs/suppression-balance.md v2 section 9): every
				// target saturated, no marines, the fuel drawn from the base's reserve;
				// the flotilla sized to outlast the guns, then fitted to its free points
				// the tier is the days down (bombardGoalDays), the flotilla the need, the task
				// forces on station as they are (playerExpeditionSizes at Min)
				float downGoal = IncursionManager.bombardGoalDays(siegeTier);
				List<ThreatFleetOrders.Order> staged = new java.util.ArrayList<ThreatFleetOrders.Order>();
				List<Integer> sizes = IncursionManager.playerExpeditionSizes(base, faction,
						IncursionManager.bombardFleetSizes(base, faction, targets, downGoal), 0, staged);
				purge = IncursionManager.launchSiegeExpedition(base, faction, system, targets, sizes,
						faction.isPlayerFaction(), random, 0f, IncursionManager.idsOf(targets), null, staged,
						downGoal);
				if (purge == null) why = IncursionManager.bombardBlockFacts(base, faction, system, siegeTier);
			}
			if (purge == null) {
				ThreatNotice n = ThreatNotice.titled("Bombing Refused").bad().icon(faction);
				if (why != null) n.lines(why);
				else n.line("No expedition could be raised at %s", ThreatNotice.market(base));
				n.send();
				return null;
			}
			ThreatNotice.titled("Bombing Expedition").icon(faction)
					.line("From %s into the %s", ThreatNotice.market(base), system.getNameWithLowercaseType())
					.line("To saturate %s from orbit", IncursionManager.worldNames(targets))
					.send();
			return "bombard";
		}
		if (BUTTON_RECALL_ALL.equals(parts[0])) {
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			if (world == null) return null;
			return recallAll(faction, world, random) > 0 ? "recall-all" : null;
		}
		if (BUTTON_RECALL.equals(parts[0])) {
			return recall(parts[1], parts[2]) ? "recall" : null;
		}
		if (BUTTON_DETACH.equals(parts[0])) {
			return detachToHunt(faction, parts[2]) ? "detach" : null;
		}
		if (BUTTON_OUTPOST.equals(parts[0])) {
			com.fs.starfarer.api.campaign.SectorEntityToken planet =
					Global.getSector().getEntityById(parts[2]);
			if (!(planet instanceof com.fs.starfarer.api.campaign.PlanetAPI)) return null;
			ThreatOutposts.Outpost o = ThreatOutposts.build(faction,
					(com.fs.starfarer.api.campaign.PlanetAPI) planet);
			if (o == null) {
				ThreatNotice n = ThreatNotice.titled("Outpost Refused").bad().icon(faction)
						.line("Over %s", planet.getName());
				if (faction.isPlayerFaction()) n.line("No base in reach, or none that can pay");
				else n.line("No %s base in reach, or none that can pay", ThreatNotice.faction(faction));
				n.send();
				return null;
			}
			return "outpost";
		}
		if (BUTTON_DEFEND_PUSH.equals(parts[0]) || BUTTON_DEFEND_ENTRENCH.equals(parts[0])) {
			// the defenders of the player's own colony only: NPC colonies run
			// their stance AI (ThreatGroundFronts.tickDefenderStance)
			if (!faction.isPlayerFaction()) return null;
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = world != null
					? ThreatGroundFronts.getFront(world.getId()) : null;
			if (world == null || front == null || !world.isPlayerOwned()) return null;
			boolean push = BUTTON_DEFEND_PUSH.equals(parts[0]);
			String why = push ? ThreatGroundFronts.defenderPushBlockReason(front, world)
					: ThreatGroundFronts.defenderEntrenchBlockReason(front, world);
			if (why != null) {
				ThreatNotice.titled(push ? "Cannot Push" : "Cannot Hold").bad().icon(faction)
						.line(why).send();
				return null;
			}
			if (push) {
				ThreatGroundFronts.orderDefenderPush(front);
				ThreatNotice.titled("Push Ordered").icon(faction)
						.line("Marines on %s push: %s", ThreatNotice.market(world),
								ThreatGroundFronts.lineState(ThreatGroundFronts.defenderPushRate(front, world), true, false))
						.send();
				return "defend-push";
			}
			ThreatGroundFronts.orderDefenderEntrench(front);
			ThreatNotice.titled("Hold Ordered").icon(faction)
					.line("Marines on %s hold", ThreatNotice.market(world))
					.send();
			return "defend-entrench";
		}
		if (BUTTON_PUSH.equals(parts[0]) || BUTTON_ENTRENCH.equals(parts[0])) {
			// the player's own front only: NPC fronts run their stance AI
			if (!faction.isPlayerFaction()) return null;
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = world != null
					? ThreatGroundFronts.getFront(world.getId()) : null;
			if (front == null || !front.isPlayerOwned()) return null;
			boolean push = BUTTON_PUSH.equals(parts[0]);
			ThreatNotice.Reason why = push ? ThreatGroundFronts.pushRefusal(front, world)
					: ThreatGroundFronts.entrenchRefusal(front);
			if (why != null) {
				ThreatNotice.titled(push ? "Cannot Push" : "Cannot Hold").bad().icon(faction)
						.lines(why).send();
				return null;
			}
			if (push) {
				ThreatGroundFronts.orderPush(front);
				ThreatNotice.titled("Push Ordered").icon(faction)
						.line("Front on %s to take stratum %s", ThreatNotice.market(world),
								front.strataHeld + 1)
						.send();
				return "push";
			}
			ThreatGroundFronts.orderEntrench(front);
			ThreatNotice.titled("Hold Ordered").icon(faction)
					.line("Front on %s breaks off and holds", ThreatNotice.market(world))
					.send();
			return "entrench";
		}
		if (BUTTON_SUPPORT.equals(parts[0]) || BUTTON_DEFEND.equals(parts[0])) {
			String kind = BUTTON_DEFEND.equals(parts[0]) ? ThreatFleetOrders.KIND_DEFEND
					: ThreatFleetOrders.KIND_SUPPORT;
			// a front can stand on a human world too: the same resolver the prompt uses
			MarketAPI hive = ThreatGroundFronts.resolveMarket(parts[2]);
			if (hive == null) return null;
			int orbitTier = frontLevers.length > 2 ? frontLevers[2] : siegeTier;
			ThreatNotice.Reason why = ThreatFleetOrders.orbitRefusal(faction, hive, kind, orbitTier);
			if (why == null && ThreatFleetOrders.dispatchOrbit(faction, hive, kind, orbitTier) != null) {
				return ThreatFleetOrders.orbitName(kind).toLowerCase();
			}
			ThreatNotice n = ThreatNotice.titled(ThreatFleetOrders.orbitName(kind) + " Refused").bad()
					.icon(faction);
			if (why != null) n.lines(why);
			else n.line("No task force could be raised for the orbit of %s", ThreatNotice.market(hive));
			n.send();
			return null;
		}
		if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			if (hive == null) return null;
			boolean pull = BUTTON_PULLOUT.equals(parts[0]);
			// Transfer at the Fleet lever deploys a fleet over the hive (2026-10-31)
			if (!pull && fleetLeverSet(levers) && faction.isPlayerFaction()) {
				return ThreatDeploy.execute(ThreatDeploy.plan(ThreatBases.of(hive), levers), faction);
			}
			String title = pull ? "Evacuation Refused" : "Supply Run Refused";
			String refused = ThreatConvoys.canRunTo(faction, hive);
			if (refused != null) {
				ThreatNotice.titled(title).bad().icon(faction).lines(refused).send();
				return null;
			}
			ThreatConvoys.Convoy c = pull ? ThreatConvoys.pullOutFront(hive, faction, random)
					: ThreatConvoys.supplyFront(hive, faction, frontLevers, random);
			if (c == null) {
				ThreatNotice n = ThreatNotice.titled(title).bad().icon(faction)
						.line("For %s", ThreatNotice.market(hive));
				if (faction.isPlayerFaction()) {
					n.line("No base in reach with anything to send, or a run already bound there");
				} else {
					n.line("No %s base in reach with anything to send, or a run already bound there",
							ThreatNotice.faction(faction));
				}
				n.send();
				return null;
			}
			return pull ? "pullout" : "supply";
		}
		return null;
	}

	/** Purged worlds within expedition reach of one of the faction's military worlds. */
	/** The Staging group's Recall, left of Transfer (2026-10-30 21:00): greyed with nothing out; the tooltip says what comes home. */
	protected static void addRecallAllButton(ThreatIncursionIntel intel, TooltipMakerAPI main, UIPanelAPI table,
			float up, FactionAPI faction, MarketAPI world, boolean mayOrder, String blocked) {
		ButtonAPI recall = intel.addGenericButton(main, RECALL_W, "Recall",
				BUTTON_RECALL_ALL + faction.getId() + ":" + world.getId());
		recall.getPosition().belowRight(table, -up).setXAlignOffset(-6f - TRANSFER_W - 4f);
		int[] n = recallAllCount(faction, world);
		boolean any = n[0] + n[1] + n[2] > 0;
		disableWith(main, recall, mayOrder && any, blocked != null ? blocked : recallAllText(faction, world),
				recallAllText(faction, world));
	}

	/** The hive worlds the player's landings are attacking - the Domain table's Front rows (2026-10-30 evening). */
	protected static List<ThreatWarBoard.FrontRow> attackedRows() {
		List<ThreatWarBoard.FrontRow> rows = new ArrayList<ThreatWarBoard.FrontRow>();
		for (ThreatWarBoard.FrontRow r : ThreatWarBoard.frontRowsFor(Factions.PLAYER)) {
			if (r.front.isPlayerOwned() && ThreatGroundFronts.isHiveTarget(r.market)) rows.add(r);
		}
		return rows;
	}

	protected static List<com.fs.starfarer.api.campaign.PlanetAPI> purgedInReach(FactionAPI faction) {
		List<com.fs.starfarer.api.campaign.PlanetAPI> result =
				new ArrayList<com.fs.starfarer.api.campaign.PlanetAPI>();
		if (faction == null) return result;
		for (com.fs.starfarer.api.campaign.PlanetAPI p : ThreatOutposts.openPurgedWorlds()) {
			if (ThreatFleetOrders.pickBase(faction, p.getLocationInHyperspace()) != null) result.add(p);
		}
		return result;
	}

	/** The group of a "purgefleet:i:fleetId" / "tffleet:i:fleetId" key, or null. */
	protected static Object group(String key) {
		String[] parts = key.split(":", 3);
		if (parts.length < 3) return null;
		int index;
		try {
			index = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			return null;
		}
		List<Object> list = "purgefleet".equals(parts[0]) ? IncursionManager.getPurgeList()
				: "tffleet".equals(parts[0]) ? IncursionManager.getResponseList()
				: "returnfleet".equals(parts[0]) ? new ArrayList<Object>(ThreatReturns.all()) : null;
		if (list == null || index < 0 || index >= list.size()) return null;
		return list.get(index);
	}

	/** The fleet of a group key, while it still lives with its group. */
	protected static CampaignFleetAPI groupFleet(String key) {
		Object g = group(key);
		String fleetId = key.substring(key.lastIndexOf(':') + 1);
		List<CampaignFleetAPI> fleets = g instanceof ThreatPurgeFGI ? ((ThreatPurgeFGI) g).getFleets()
				: g instanceof ThreatResponseIntel ? ((ThreatResponseIntel) g).livingFleets()
				: g instanceof ThreatReturns.Return
						? java.util.Collections.singletonList(((ThreatReturns.Return) g).fleet) : null;
		if (fleets == null) return null;
		for (CampaignFleetAPI fleet : fleets) {
			if (fleet != null && fleet.isAlive() && fleetId.equals(fleet.getId())) return fleet;
		}
		return null;
	}

	/** The hive system a group key's fleet is sent against. */
	protected static StarSystemAPI groupTargetSystem(String key) {
		Object g = group(key);
		if (g instanceof ThreatPurgeFGI) {
			ThreatPurgeFGI p = (ThreatPurgeFGI) g;
			return p.getParams() != null && p.getParams().raidParams != null
					? p.getParams().raidParams.where : null;
		}
		if (g instanceof ThreatResponseIntel) {
			MarketAPI target = ThreatIncData.resolveColonyMarket(
					((ThreatResponseIntel) g).getTargetMarketId());
			return target != null ? target.getStarSystem() : null;
		}
		if (g instanceof ThreatReturns.Return) {
			return returnOriginSystem((ThreatReturns.Return) g);
		}
		return null;
	}

	/**
	 * The hive system a returning fleet came from, IF it still holds a live
	 * colony worth turning back for - else null. This is where the fleet is
	 * returning FROM (r.fromSystemId), not where it currently sits: a fleet
	 * already in hyperspace can still be sent back to hold the door of the hive
	 * it just left. Resolving through the system's own live colonies gives the
	 * StarSystemAPI and enforces the "there is still something there" gate in
	 * one step.
	 */
	protected static StarSystemAPI returnOriginSystem(ThreatReturns.Return r) {
		if (r == null || r.fromSystemId == null) return null;
		for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(r.fromSystemId)) {
			if (m != null && m.getStarSystem() != null) return m.getStarSystem();
		}
		return null;
	}

	/** One fleet leaves its expedition or task force and hunts the hive's Defense Swarms (ThreatFleetOrders.adoptHunt). */
	protected static boolean detachToHunt(FactionAPI faction, String key) {
		if (faction == null || key == null) return false;
		Object g = group(key);
		CampaignFleetAPI fleet = groupFleet(key);
		StarSystemAPI hive = groupTargetSystem(key);
		if (g == null || fleet == null || hive == null) return false;
		if (g instanceof ThreatPurgeFGI) {
			ThreatPurgeFGI p = (ThreatPurgeFGI) g;
			if (p.getFaction() == null || !faction.getId().equals(p.getFaction().getId())) return false;
			MarketAPI base = p.sourceBase();
			if (!p.detach(fleet)) return false;
			if (ThreatFleetOrders.adoptHunt(fleet, faction, hive, base) == null) {
				// nowhere to hold: the fleet goes home instead of drifting
				ThreatReturns.sendHome(fleet, faction.getId(), base != null ? base.getId() : null);
				return false;
			}
			return true;
		}
		if (g instanceof ThreatReturns.Return) {
			ThreatReturns.Return ret = (ThreatReturns.Return) g;
			if (!faction.getId().equals(ret.factionId)) return false;
			MarketAPI base = Global.getSector().getEconomy().getMarket(ret.homeMarketId);
			ThreatReturns.all().remove(ret);
			if (ThreatFleetOrders.adoptHunt(fleet, faction, hive, base) == null) {
				ThreatReturns.sendHome(fleet, faction.getId(), ret.homeMarketId);
				return false;
			}
			return true;
		}
		ThreatResponseIntel r = (ThreatResponseIntel) g;
		if (r.getFaction() == null || !faction.getId().equals(r.getFaction().getId())) return false;
		String home = ThreatReturns.homeOf(fleet);
		MarketAPI base = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
		if (!r.detach(fleet)) return false;
		if (ThreatFleetOrders.adoptHunt(fleet, faction, hive, base) == null) {
			if (home != null) ThreatReturns.sendHome(fleet, faction.getId(), home);
			return false;
		}
		return true;
	}

	/**
	 * What the Staging group's Recall would call home (2026-10-30 21:00): [fleets, convoys, fronts]. A
	 * colony's: the orders based there, the expeditions and task forces raised there, the convoys from
	 * it. A hive's (a Front row): the orders over it, and its front if a pickup run can sail.
	 */
	protected static int[] recallAllCount(FactionAPI faction, MarketAPI world) {
		int[] n = new int[3];
		if (faction == null || world == null) return n;
		boolean hive = ThreatIncData.resolveColonyMarket(world.getId()) != null;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!faction.getId().equals(o.factionId) || o.fleet == null || !o.fleet.isAlive()) continue;
			if (hive ? world.getId().equals(o.targetId) : world.getId().equals(o.baseMarketId)) n[0]++;
		}
		if (hive) {
			if (ThreatConvoys.pullOutBlockReason(faction, world) == null) n[2] = 1;
			return n;
		}
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (faction.getId().equals(c.factionId) && world.getId().equals(c.fromMarketId)
					&& c.fleet != null && c.fleet.isAlive() && !c.pickup) n[1]++;
		}
		for (Object g : IncursionManager.getPurgeList()) {
			if (!(g instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI p = (ThreatPurgeFGI) g;
			if (p.getFaction() == null || !faction.getId().equals(p.getFaction().getId())) continue;
			if (p.isEnding() || p.isEnded()) continue;
			MarketAPI base = p.sourceBase();
			if (base != null && world.getId().equals(base.getId())) n[0]++;
		}
		for (Object g : IncursionManager.getResponseList()) {
			if (!(g instanceof ThreatResponseIntel)) continue;
			ThreatResponseIntel r = (ThreatResponseIntel) g;
			if (r.getFaction() == null || !faction.getId().equals(r.getFaction().getId())) continue;
			if (world.getName().equals(r.baseName) && !r.livingFleets().isEmpty()) n[0]++;
		}
		return n;
	}

	/** The Recall's tooltip and confirm: "Recalls 2 fleets and 1 convoy sent from Haven." / "Lifts the front off Ogre and brings it home; recalls 1 fleet over it." */
	protected static String recallAllText(FactionAPI faction, MarketAPI world) {
		int[] n = recallAllCount(faction, world);
		boolean hive = ThreatIncData.resolveColonyMarket(world.getId()) != null;
		List<String> parts = new ArrayList<String>();
		if (n[0] > 0) parts.add(n[0] + (n[0] == 1 ? " fleet" : " fleets"));
		if (n[1] > 0) parts.add(n[1] + (n[1] == 1 ? " convoy" : " convoys"));
		if (hive) {
			// the lift's sentence names the base, the men and the door (ThreatConvoys.pullOutText)
			String lift = n[2] > 0 ? ThreatConvoys.pullOutText(faction, world) : "";
			String recalls = parts.isEmpty() ? "" : "Recalls " + Misc.getAndJoined(parts) + " over it.";
			if (lift.isEmpty() && recalls.isEmpty()) return "Nothing to recall.";
			return (lift + " " + recalls).trim();
		}
		if (parts.isEmpty()) return "Nothing sent from " + world.getName() + " is out.";
		return "Recalls " + Misc.getAndJoined(parts) + " sent from " + world.getName() + ".";
	}

	/** The Recall carried out (recallAllCount says what); how many orders went out, with the notice. */
	protected static int recallAll(FactionAPI faction, MarketAPI world, java.util.Random random) {
		if (faction == null || world == null) return 0;
		String what = recallAllText(faction, world);
		boolean hive = ThreatIncData.resolveColonyMarket(world.getId()) != null;
		int n = 0;
		for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			if (!faction.getId().equals(o.factionId) || o.fleet == null || !o.fleet.isAlive()) continue;
			if (hive ? world.getId().equals(o.targetId) : world.getId().equals(o.baseMarketId)) {
				ThreatFleetOrders.recall(o);
				n++;
			}
		}
		if (hive) {
			// the evacuation convoy sails now and waits at a contested door (the user, 2026-10-30: "recall
			// should send convoy here to retrieve marines and arms")
			if (ThreatConvoys.pullOutBlockReason(faction, world) == null) {
				if (ThreatConvoys.pullOutFront(world, faction, random) != null) {
					n++;
				} else {
					ThreatNotice.titled("Evacuation Refused").bad().icon(faction)
							.line("For %s", ThreatNotice.market(world))
							.line("No convoy could be raised at the nearest base").send();
				}
			}
		} else {
			for (ThreatConvoys.Convoy c : new ArrayList<ThreatConvoys.Convoy>(ThreatConvoys.all())) {
				if (faction.getId().equals(c.factionId) && world.getId().equals(c.fromMarketId)
						&& c.fleet != null && c.fleet.isAlive() && !c.pickup) {
					ThreatConvoys.returnHome(c);
					n++;
				}
			}
			for (Object g : new ArrayList<Object>(IncursionManager.getPurgeList())) {
				if (!(g instanceof ThreatPurgeFGI)) continue;
				ThreatPurgeFGI p = (ThreatPurgeFGI) g;
				if (p.getFaction() == null || !faction.getId().equals(p.getFaction().getId())) continue;
				if (p.isEnding() || p.isEnded()) continue;
				MarketAPI base = p.sourceBase();
				if (base != null && world.getId().equals(base.getId())) {
					p.abort();
					n++;
				}
			}
			for (Object g : new ArrayList<Object>(IncursionManager.getResponseList())) {
				if (!(g instanceof ThreatResponseIntel)) continue;
				ThreatResponseIntel r = (ThreatResponseIntel) g;
				if (r.getFaction() == null || !faction.getId().equals(r.getFaction().getId())) continue;
				if (world.getName().equals(r.baseName) && !r.livingFleets().isEmpty()) {
					r.standDown();
					n++;
				}
			}
		}
		if (n > 0) {
			ThreatNotice.titled("Recall Ordered").icon(faction).line(what).send();
		} else {
			ThreatNotice.titled("Nothing To Recall").bad().icon(faction).line("For %s", ThreatNotice.market(world)).send();
		}
		return n;
	}

	protected static boolean recall(String factionId, String key) {
		if (key.startsWith("purgefleet:") || key.startsWith("tffleet:")) {
			Object g = group(key);
			CampaignFleetAPI fleet = groupFleet(key);
			if (g == null || fleet == null) return false;
			if (g instanceof ThreatPurgeFGI) {
				ThreatPurgeFGI p = (ThreatPurgeFGI) g;
				if (p.getFaction() == null || !factionId.equals(p.getFaction().getId())) return false;
				return p.recallFleet(fleet);
			}
			ThreatResponseIntel r = (ThreatResponseIntel) g;
			if (r.getFaction() == null || !factionId.equals(r.getFaction().getId())) return false;
			return r.recallFleet(fleet);
		}
		int colon = key.indexOf(':');
		if (colon < 0) return false;
		String kind = key.substring(0, colon);
		int index;
		try {
			index = Integer.parseInt(key.substring(colon + 1));
		} catch (NumberFormatException e) {
			return false;
		}
		if ("tf".equals(kind)) {
			List<Object> list = IncursionManager.getResponseList();
			if (index < 0 || index >= list.size() || !(list.get(index) instanceof ThreatResponseIntel)) return false;
			ThreatResponseIntel r = (ThreatResponseIntel) list.get(index);
			if (r.getFaction() == null || !factionId.equals(r.getFaction().getId())) return false;
			r.standDown();
			return true;
		}
		if ("purge".equals(kind)) {
			List<Object> list = IncursionManager.getPurgeList();
			if (index < 0 || index >= list.size() || !(list.get(index) instanceof ThreatPurgeFGI)) return false;
			ThreatPurgeFGI p = (ThreatPurgeFGI) list.get(index);
			if (p.getFaction() == null || !factionId.equals(p.getFaction().getId())) return false;
			p.abort();
			return true;
		}
		if ("convoy".equals(kind)) {
			List<ThreatConvoys.Convoy> list = ThreatConvoys.all();
			if (index < 0 || index >= list.size()) return false;
			ThreatConvoys.Convoy c = list.get(index);
			if (!factionId.equals(c.factionId)) return false;
			ThreatConvoys.returnHome(c);
			return true;
		}
		if ("order".equals(kind)) {
			List<ThreatFleetOrders.Order> list = ThreatFleetOrders.all();
			if (index < 0 || index >= list.size()) return false;
			ThreatFleetOrders.Order o = list.get(index);
			if (!factionId.equals(o.factionId)) return false;
			ThreatFleetOrders.recall(o);
			return true;
		}
		if ("aidorder".equals(kind)) {
			List<ThreatFleetOrders.Order> list = ThreatFleetOrders.all();
			if (index < 0 || index >= list.size()) return false;
			ThreatFleetOrders.Order o = list.get(index);
			if (!o.aid || !factionId.equals(o.recipientFactionId)) return false;
			if (o.credited) return false; // the standing is paid: it serves its term (2026-10-31)
			ThreatFleetOrders.recall(o);
			return true;
		}
		if ("aidconvoy".equals(kind)) {
			List<ThreatConvoys.Convoy> list = ThreatConvoys.all();
			if (index < 0 || index >= list.size()) return false;
			ThreatConvoys.Convoy c = list.get(index);
			if (!c.aid || !factionId.equals(c.recipientFactionId)) return false;
			ThreatConvoys.returnHome(c);
			return true;
		}
		return false;
	}

	/** Unused-import guard for CampaignFleetAPI/UIComponentAPI in older compilers. */
	@SuppressWarnings("unused")
	private static void keep(CampaignFleetAPI f, UIComponentAPI c) {
	}
}
