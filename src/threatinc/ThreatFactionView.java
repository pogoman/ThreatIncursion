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
 * orders); and the hive systems its bases can reach. For the player's OWN
 * faction every fleet action lives here as a button: guard a colony, stage
 * materiel to it, intercept at a hive's door, send a siege, recall a fleet.
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
	public static final String BUTTON_INTERCEPT = "threatinc_board_intercept:";
	public static final String BUTTON_SIEGE = "threatinc_board_siege:";
	/** Picks the siege-force tier (payload: the tier index 0/1/2); a view toggle, not an order - no Confirm. */
	public static final String BUTTON_SIEGE_TIER = "threatinc_board_siegetier:";
	/** Picks the ground-front supply tier (payload: the tier index 0/1/2); a view toggle, not an order. */
	public static final String BUTTON_SUPPLY_TIER = "threatinc_board_supplytier:";
	/** Picks the colony convoy-load tier (payload: the tier index 0/1/2); a view toggle, not an order. */
	public static final String BUTTON_CONVOY_TIER = "threatinc_board_convoytier:";
	public static final String BUTTON_RECALL = "threatinc_board_recall:";
	/** One fleet of an expedition or task force is detached to intercept (payload: factionId:interceptKey). */
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
	/** The player's own front breaks off and digs in (payload: factionId:marketId). */
	public static final String BUTTON_ENTRENCH = "threatinc_board_entrench:";
	/** Player aid: a task force from a player colony guards another faction's colony (payload: factionId:marketId). */
	public static final String BUTTON_AID_DEFEND = "threatinc_board_aiddefend:";
	/** Player aid: a convoy from a player colony brings what the colony is shortest of (payload: factionId:marketId). */
	public static final String BUTTON_AID_SUPPLY = "threatinc_board_aidsupply:";
	/** Player aid: a task force from a player colony holds a hive system's door (payload: factionId:systemId). */
	public static final String BUTTON_AID_STRIKE = "threatinc_board_aidstrike:";
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
			String label = VIEW_THREAT.equals(id) ? "The Threat" : ThreatWarState.displayName(id);
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
		/** Set on a group fleet's row that can be detached to intercept: the same key. */
		String interceptKey;
		/** The hive system a detached fleet would intercept at. */
		StarSystemAPI interceptSystem;
		/** A player aid fleet shown in another faction's view: the player may recall it. */
		boolean playerAid;
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
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();
		Color text = Misc.getTextColor();
		String name = ThreatWarState.displayName(factionId);
		ThreatWarState.FactionWar war = ThreatWarState.get(factionId);
		boolean mayOrder = ThreatFleetOrders.canPlayerOrder(faction);
		String blocked = ThreatFleetOrders.orderBlockReason(faction);
		// another faction's view is a window, not a console (docs/player-aid.md):
		// its navy is its own, and the buttons offer the player's aid instead
		boolean own = faction.isPlayerFaction();
		String aidBlocked = own ? null : ThreatAid.canAid(faction);
		boolean mayAid = !own && aidBlocked == null;

		// ---- heading and the reserve totals ----
		StringBuilder title = new StringBuilder(name).append(" - mobilised");
		if (war != null) {
			int days = (int) Global.getSector().getClock().getElapsedDaysSince(war.enteredTimestamp);
			// a record with no usable timestamp (injected, or pre-dating the
			// layer) just reads "mobilised"
			if (war.enteredTimestamp > 0 && days >= 0 && days < 36500) {
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

		List<MarketAPI> markets = ThreatReserves.marketsOf(factionId);
		// the totals sit at the foot of the colony table and the rules on the
		// buttons; up here only the one reason nothing can be ordered, if any
		if (own && !mayOrder && blocked != null) main.addPara(blocked, gray, opad);
		else if (!own && aidBlocked != null) main.addPara(aidBlocked, gray, opad);

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

		// ---- colonies ----
		List<ColonyRow> rows = colonyRows(markets);
		UIPanelAPI colonyTable = null;
		// outpost stockpile rows sit between the colonies and the Total row
		int outpostRows = 0;
		List<ThreatOutposts.Outpost> outposts = new ArrayList<ThreatOutposts.Outpost>();
		if (!rows.isEmpty()) {
			main.addSectionHeading("Colonies - staging bases first", bright, dark, Alignment.MID, opad);
			// the convoy-load selector sits in a reserved row under the heading;
			// its buttons float in later (addConvoyTierSelector)
			main.addSpacer(SELECTOR_ROW_H);
			float tw = width - 24f;
			// Actions must hold two floating buttons (Defend + Aid = 114 px with
			// gaps): at .08 the Aid button used to overlap the Strikes column
			float[] frac = {.15f, .05f, .08f, .07f, .09f, .07f, .07f, .07f, .07f, .10f, .06f, .12f};
			String[] names = {"Colony", "Size", "Military", "Def", "FP", "Marines", "Arms", "Fuel",
					"Supplies", "Convoys", "Strikes", "Staging"};
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
			main.addTableHeaderTooltip(4, "Fleet points free to sail from here now (task forces on "
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
			main.addTableHeaderTooltip(9, "Where the colony's spare stock goes by convoy: a staging "
					+ "base receives it, any other colony feeds one.");
			main.addTableHeaderTooltip(10, "Threat expeditions in flight against this world.");
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
				// the distance is the part that must survive: shorten the name
				// around it, not the other way round
				String stagingText = "-";
				Color stagingColor = gray;
				if (!r.depot) {
					stagingText = "No Waystation";
				} else if (r.staging != null) {
					// the hive it stocks for is in the row tooltip
					stagingText = "Staging base";
					stagingColor = h;
				} else if (r.feeds != null) {
					String ly = " " + (int) Math.ceil(r.feedsLY) + " ly";
					stagingText = "to " + main.shortenString(r.feeds.getName(),
							(float) Math.floor(tw * frac[9]) - 10f - main.computeStringWidth("to " + ly))
							+ ly;
					stagingColor = text;
				}
				ThreatWarBoard.cell(cells, Alignment.MID, stagingColor, stagingText);
				ThreatWarBoard.cell(cells, Alignment.MID, r.threats > 0 ? neg : gray,
						r.threats > 0 ? "" + r.threats : "-");
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
			// outposts: a stockpile and nothing else - no floor, no accrual, no
			// War footing - so the Total below sums exactly what is shown. Its
			// station is its own depot: Supplies and Fleet land there like at
			// a colony (2026-09-06)
			for (final ThreatOutposts.Outpost o : ThreatOutposts.outpostsOf(factionId)) {
				if (!o.alive()) continue;
				outpostRows++;
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
				// a forward base while its system holds a hive; afterwards its
				// stock ships home by convoy, and the cell says where
				String convoyText = "-";
				Color convoyColor = gray;
				if (!ThreatIncData.getLiveColonyMarkets(o.systemId).isEmpty()) {
					convoyText = "Forward base";
					convoyColor = h;
				} else {
					MarketAPI home = ThreatConvoys.outpostHome(faction, o);
					if (home != null && home.getStarSystem() != null && o.entity != null) {
						String ly = " " + (int) Math.ceil(Misc.getDistanceLY(
								o.entity.getLocationInHyperspace(), home.getStarSystem().getLocation()))
								+ " ly";
						convoyText = "to " + main.shortenString(home.getName(),
								(float) Math.floor(tw * frac[9]) - 10f - main.computeStringWidth("to " + ly))
								+ ly;
						convoyColor = text;
					}
				}
				ThreatWarBoard.cell(cells, Alignment.MID, convoyColor, convoyText);
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
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			main.addRow(totals.toArray());
			main.addTable("None", -1, 0f);
			// the key to the colours: one word each, in its colour
			LabelAPI key = main.addPara("excess     deficit     critical     empty", gray, 4f);
			key.setHighlight("excess", "deficit", "critical");
			key.setHighlightColors(text, h, neg);
			main.addSpacer(opad);
		}

		// ---- ground fronts: this faction's landings, and the swarm's on its worlds ----
		ThreatWarBoard.Fronts fronts = ThreatWarBoard.addFronts(intel, main, width, opad,
				ThreatWarBoard.frontRowsFor(factionId), faction, true);

		// ---- fleets ----
		List<FleetRow> fleets = fleetRows(factionId);
		UIPanelAPI fleetTable = null;
		main.addSectionHeading(own ? "Fleets and orders" : "Fleets in flight, and your aid to them",
				bright, dark, Alignment.MID, opad);
		if (fleets.isEmpty()) {
			main.addPara("No task forces, expeditions or convoys are in flight.", gray, opad);
		} else {
			float tw = width - 24f;
			// Actions holds Recall + Intercept on a group fleet's row (134 px)
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

		// ---- hives in reach ----
		List<ThreatWarBoard.Entry> reach = hivesInReach(markets);
		UIPanelAPI hiveTable = null;
		main.addSectionHeading(own ? "Known hive systems" : "Hive systems in reach", bright, dark,
				Alignment.MID, opad);
		// the player's siege-force selector sits in a reserved row under the
		// heading; its buttons float in later (addSiegeTierSelector), like the
		// faction selector's do
		boolean siegeSelector = own && !reach.isEmpty();
		if (siegeSelector) main.addSpacer(SELECTOR_ROW_H);
		if (reach.isEmpty()) {
			main.addPara("No known infested system lies within expedition reach of this "
					+ "faction's military worlds.", gray, opad);
		} else {
			float tw = width - 24f;
			// Actions carries Intercept + one Siege button (~132 px); Nearest base
			// holds a shortened colony name and the count columns a single figure,
			// so the width goes to System (the system name) instead
			float[] frac = {.30f, .09f, .09f, .18f, .13f, .21f};
			String[] names = {"System", "Worlds", "Mass", "Nearest base", "Distance", "Actions"};
			List<Object> columns = new ArrayList<Object>();
			for (int i = 0; i < names.length; i++) {
				columns.add(names[i]);
				columns.add((float) Math.floor(tw * frac[i]));
			}
			hiveTable = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
			main.makeTableItemsClickable();
			for (ThreatWarBoard.Entry e : reach) {
				MarketAPI base = nearestBase(markets, e.system);
				float d = base != null ? Misc.getDistanceLY(base.getStarSystem().getLocation(),
						e.system.getLocation()) : -1f;
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, bright,
						main.shortenString(e.displayName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, h, "" + e.markets.size());
				ThreatWarBoard.cell(cells, Alignment.MID, h, "" + e.mass);
				ThreatWarBoard.cell(cells, Alignment.MID, text, base != null
						? main.shortenString(base.getName(), (float) Math.floor(tw * frac[3]) - 10f) : "-");
				ThreatWarBoard.cell(cells, Alignment.MID, text, d >= 0f ? (int) Math.ceil(d) + " ly" : "-");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "");
				main.addRow(cells.toArray());
				main.setIdForAddedRow(e.systemId);
			}
			main.addTable("None", -1, 0f);
			main.addSpacer(opad);
		}

		// ---- purged worlds in reach: outposts ----
		List<com.fs.starfarer.api.campaign.PlanetAPI> purged = purgedInReach(faction);
		UIPanelAPI purgedTable = null;
		if (own && ThreatIncConfig.outpostsEnabled() && !purged.isEmpty()) {
			main.addSectionHeading("Purged worlds in reach - open for an outpost", bright, dark,
					Alignment.MID, opad);
			float tw = width - 24f;
			float[] frac = {.28f, .22f, .22f, .14f, .14f};
			String[] names = {"World", "System", "Nearest base", "Cost", "Actions"};
			List<Object> columns = new ArrayList<Object>();
			for (int i = 0; i < names.length; i++) {
				columns.add(names[i]);
				columns.add((float) Math.floor(tw * frac[i]));
			}
			purgedTable = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
			main.makeTableItemsClickable();
			for (com.fs.starfarer.api.campaign.PlanetAPI p : purged) {
				MarketAPI base = ThreatFleetOrders.pickBase(faction, p.getLocationInHyperspace());
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, text,
						main.shortenString(p.getName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, text, p.getStarSystem() != null
						? main.shortenString(p.getStarSystem().getNameWithNoType(),
								(float) Math.floor(tw * frac[1]) - 10f) : "-");
				ThreatWarBoard.cell(cells, Alignment.MID, text, base != null
						? main.shortenString(base.getName(), (float) Math.floor(tw * frac[2]) - 10f) : "-");
				ThreatWarBoard.cell(cells, Alignment.MID, h, faction.isPlayerFaction()
						? Misc.getDGSCredits(ThreatIncConfig.outpostCredits())
						: (int) ThreatIncConfig.outpostSupplies() + " sup / "
								+ (int) ThreatIncConfig.outpostFuel() + " fuel");
				ThreatWarBoard.cell(cells, Alignment.MID, gray, "");
				main.addRow(cells.toArray());
				main.setIdForAddedRow(p);
			}
			main.addTable("None", -1, 0f);
			main.addSpacer(opad);
		}

		// ---- floating buttons, last ----
		// Anchored to the table panels, one row up per row (platform traps 1
		// and 2); every button carries its own order's gate, the reason as its
		// tooltip while disabled (vanilla's confirm dialog cannot grey Confirm).
		float heightBefore = main.getHeightSoFar();
		int convoyTier = intel.getConvoyTier();
		ThreatWarBoard.addFrontButtons(intel, main, fronts);
		if (purgedTable != null) {
			int n = purged.size();
			for (int i = 0; i < n; i++) {
				com.fs.starfarer.api.campaign.PlanetAPI p = purged.get(i);
				float up = (n - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				ButtonAPI build = intel.addGenericButton(main, SMALL_BUTTON_W + 12f, "Outpost",
						BUTTON_OUTPOST + factionId + ":" + p.getId());
				build.getPosition().belowRight(purgedTable, -up).setXAlignOffset(-6f);
				boolean canPay = ThreatOutposts.payingBase(faction, p) != null
						&& (!faction.isPlayerFaction() || Global.getSector().getPlayerFleet()
								.getCargo().getCredits().get() >= ThreatIncConfig.outpostCredits());
				disableWith(main, build, mayOrder && canPay, blocked != null ? blocked
						: "No base in reach can pay for it right now.",
						"A " + ThreatOutposts.specIdFor(faction).replace('_', ' ') + " over the "
						+ "world keeps the swarm from seeding it again until it is destroyed. "
						+ (faction.isPlayerFaction() ? "Paid in credits."
								: "Paid in supplies and fuel from the nearest base."));
			}
		}
		if (colonyTable != null && own) {
			// the player's outposts: the same two orders, landing at the station
			int ownDays = (int) ThreatIncConfig.guardOwnDays();
			for (int j = 0; j < outposts.size(); j++) {
				ThreatBases.Base b = ThreatBases.of(outposts.get(j));
				if (b == null) continue;
				// only the outposts after this one and the Total row sit below
				float up = (outposts.size() + 1 - j) * ThreatWarBoard.ROW_H
						- (ThreatWarBoard.ROW_H - 20f) / 2f;
				ButtonAPI guard = intel.addGenericButton(main, SMALL_BUTTON_W - 8f, "Fleet",
						BUTTON_GUARD + factionId + ":" + b.id());
				guard.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
				ButtonAPI stage = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Supplies",
						BUTTON_STAGE + factionId + ":" + b.id());
				stage.getPosition().belowRight(colonyTable, -up)
						.setXAlignOffset(-6f - (SMALL_BUTTON_W - 8f) - 4f);
				ThreatAid.Quote q = ThreatAid.quoteDefend(b);
				MarketAPI donor = ThreatConvoys.stageDonor(b, faction, convoyTier);
				float[] load = donor != null ? ThreatConvoys.stageLoad(donor, b, convoyTier) : null;
				disableWith(main, guard, mayOrder && q.ok(), blocked != null ? blocked : q.reason,
						"A task force of about " + (q.ok() ? (int) q.points : 0) + " FP from "
						+ (q.ok() ? q.source.getName() : "the nearest colony") + " holds this "
						+ "orbit " + (ownDays > 0 ? "for " + ownDays + " days" : "until recalled") + ".");
				disableWith(main, stage, mayOrder && donor != null, blocked != null ? blocked
						: !ThreatIncConfig.convoyEnabled() ? "Convoys are disabled in the mod settings."
						: "No colony of yours can spare anything right now.",
						"A convoy from " + (donor != null ? donor.getName() : "the nearest colony")
						+ " brings " + (load != null ? cargoText(load[0], load[1], load[2], load[3])
								: "what can be spared") + " to the station's storage.");
			}
		}
		if (colonyTable != null) {
			int n = rows.size();
			int guardDays = (int) ThreatIncConfig.guardDays();
			int ownDays = (int) ThreatIncConfig.guardOwnDays();
			for (int i = 0; i < n; i++) {
				ColonyRow r = rows.get(i);
				// the outpost rows and the Total row sit below the last colony
				float up = (n + outpostRows + 1 - i) * ThreatWarBoard.ROW_H
						- (ThreatWarBoard.ROW_H - 20f) / 2f;
				if (own) {
					// the user's labels (2026-09-05): a task force is "Fleet", a
					// convoy is "Supplies"
					ButtonAPI guard = intel.addGenericButton(main, SMALL_BUTTON_W - 8f, "Fleet",
							BUTTON_GUARD + factionId + ":" + r.market.getId());
					guard.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
					ButtonAPI stage = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Supplies",
							BUTTON_STAGE + factionId + ":" + r.market.getId());
					stage.getPosition().belowRight(colonyTable, -up)
							.setXAlignOffset(-6f - (SMALL_BUTTON_W - 8f) - 4f);
					ThreatAid.Quote q = ThreatAid.quoteDefend(r.market);
					boolean guardOk = mayOrder && q.ok();
					// the order fails after Confirm otherwise: the gate is on the button
					MarketAPI donor = ThreatConvoys.stageDonor(r.market, faction, convoyTier);
					boolean stageOk = mayOrder && donor != null;
					float[] load = donor != null ? ThreatConvoys.stageLoad(donor, r.market, convoyTier) : null;
					// a guard over an own colony is staging: it stays, and its
					// points are the colony's to send out (docs/strategy-layer.md)
					disableWith(main, guard, guardOk, blocked != null ? blocked : q.reason,
							"A task force of about " + (q.ok() ? (int) q.points : 0) + " FP from "
							+ (q.ok() ? q.source.getName() : "the nearest colony") + " holds this "
							+ "orbit " + (ownDays > 0 ? "for " + ownDays + " days" : "until recalled")
							+ "; while here, its points are this colony's.");
					disableWith(main, stage, stageOk, blocked != null ? blocked
							: !ThreatIncConfig.convoyEnabled() ? "Convoys are disabled in the mod settings."
							: !r.depot ? "No Waystation at " + r.market.getName() + " to land it."
							: "No colony of yours can spare a load for " + r.market.getName()
									+ " at this convoy load.",
							"A convoy from " + (donor != null ? donor.getName() : "the nearest colony")
							+ " brings " + (load != null ? cargoText(load[0], load[1], load[2], load[3])
									: "what this colony is short of") + ".");
				} else {
					ButtonAPI defend = intel.addGenericButton(main, SMALL_BUTTON_W + 6f, "Defend",
							BUTTON_AID_DEFEND + factionId + ":" + r.market.getId());
					defend.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
					ButtonAPI aid = intel.addGenericButton(main, SMALL_BUTTON_W - 10f, "Aid",
							BUTTON_AID_SUPPLY + factionId + ":" + r.market.getId());
					aid.getPosition().belowRight(colonyTable, -up)
							.setXAlignOffset(-6f - SMALL_BUTTON_W - 6f - 4f);
					ThreatAid.Quote d = ThreatAid.quoteDefend(r.market);
					ThreatAid.Quote s = ThreatAid.quoteResupply(r.market, convoyTier);
					disableWith(main, defend, mayAid && d.ok(),
							aidBlocked != null ? aidBlocked : d.reason,
							"A task force of about " + (d.ok() ? (int) d.points : 0) + " FP from "
							+ (d.ok() ? d.source.getName() : "your nearest colony") + " holds "
							+ "this orbit for " + guardDays + " days. Paid from that colony's "
							+ "reserve; earns standing on arrival.");
					disableWith(main, aid, mayAid && s.ok(),
							aidBlocked != null ? aidBlocked : s.reason,
							s.ok() ? "A convoy of " + Misc.getWithDGS(s.quantity) + " "
									+ ThreatReserves.label(s.commodityId) + " from "
									+ s.source.getName() + ", of the " + Misc.getWithDGS(s.need)
									+ " this world is short. Paid from that colony's reserve; "
									+ "earns standing on arrival."
									: null);
				}
			}
		}
		if (fleetTable != null) {
			int n = fleets.size();
			for (int i = 0; i < n; i++) {
				FleetRow f = fleets.get(i);
				if (f.recallKey == null && f.interceptKey == null) continue;
				float up = (n - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				float right = -6f;
				if (f.recallKey != null) {
					ButtonAPI recall = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Recall",
							BUTTON_RECALL + factionId + ":" + f.recallKey);
					recall.getPosition().belowRight(fleetTable, -up).setXAlignOffset(right);
					disableWith(main, recall, mayOrder || f.playerAid, blocked,
							"This fleet breaks off and returns to base. A convoy brings its cargo "
							+ "home; the rest of an expedition or task force fights on.");
					right -= (SMALL_BUTTON_W + 8f) + 4f;
				}
				if (f.interceptKey != null) {
					ButtonAPI detach = intel.addGenericButton(main, SMALL_BUTTON_W + 14f, "Intercept",
							BUTTON_DETACH + factionId + ":" + f.interceptKey);
					detach.getPosition().belowRight(fleetTable, -up).setXAlignOffset(right);
					disableWith(main, detach, mayOrder && ThreatFleetOrders.interceptPoint(
							f.interceptSystem) != null, blocked != null ? blocked
							: "No jump-point to hold there.",
							"This fleet leaves its group and holds the "
							+ f.interceptSystem.getNameWithLowercaseTypeShort() + " jump-point for "
							+ (int) ThreatIncConfig.interceptDays() + " days, then goes home.");
				}
			}
		}
		if (hiveTable != null) {
			int n = reach.size();
			for (int i = 0; i < n; i++) {
				ThreatWarBoard.Entry e = reach.get(i);
				float up = (n - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				if (!own) {
					ButtonAPI strike = intel.addGenericButton(main, SMALL_BUTTON_W + 6f, "Strike",
							BUTTON_AID_STRIKE + factionId + ":" + e.systemId);
					strike.getPosition().belowRight(hiveTable, -up).setXAlignOffset(-6f);
					ThreatAid.Quote q = ThreatAid.quoteStrike(e.system);
					disableWith(main, strike, ThreatAidCapacity.enabled() && q.ok(),
							!ThreatAidCapacity.enabled() ? "Player aid is disabled in the mod settings."
							: q.reason,
							"A task force of about " + (q.ok() ? (int) q.points : 0) + " FP from "
							+ (q.ok() ? q.source.getName() : "your nearest colony") + " holds "
							+ "this hive's jump-point for " + (int) ThreatIncConfig.interceptDays()
							+ " days. Paid from that colony's reserve; every faction in the "
							+ "hive's reach notes it.");
					continue;
				}
				// each order's own gate, so a button never opens a Confirm the
				// order would then fail. Siege commits the force the tier selector
				// above the table is set to (siegeMarineGoal); its Confirm names it.
				MarketAPI siegeBase = nearestBase(markets, e.system);
				String siegeWhy = IncursionManager.siegeBlockReason(siegeBase, faction, e.system);
				boolean siegeOk = mayOrder && e.isColony() && siegeWhy == null;
				String siegeBlock = blocked != null ? blocked : siegeWhy != null ? siegeWhy
						: "Nothing there to besiege yet.";
				float right = -6f;
				ButtonAPI siege = intel.addGenericButton(main, SMALL_BUTTON_W, "Siege",
						BUTTON_SIEGE + factionId + ":" + e.systemId);
				siege.getPosition().belowRight(hiveTable, -up).setXAlignOffset(right);
				disableWith(main, siege, siegeOk, siegeBlock,
						"A purge expedition sails from "
						+ (siegeBase != null ? siegeBase.getName() : "the nearest base")
						+ ", its landing the marines the siege-force selector is set to.");
				right -= SMALL_BUTTON_W + 4f;
				ButtonAPI intercept = intel.addGenericButton(main, SMALL_BUTTON_W + 14f, "Intercept",
						BUTTON_INTERCEPT + factionId + ":" + e.systemId);
				intercept.getPosition().belowRight(hiveTable, -up).setXAlignOffset(right);
				ThreatAid.Quote iq = ThreatAid.quoteStrike(e.system);
				disableWith(main, intercept, mayOrder && iq.ok(), blocked != null ? blocked : iq.reason,
						"A task force of about " + (iq.ok() ? (int) iq.points : 0) + " FP from "
						+ (iq.ok() ? iq.source.getName() : "the nearest colony") + " holds this "
						+ "hive's jump-point for " + (int) ThreatIncConfig.interceptDays()
						+ " days, meeting the swarm's reinforcements and expeditions at the door.");
			}
		}
		if (colonyTable != null) {
			addConvoyTierSelector(intel, main, colonyTable);
		}
		if (hiveTable != null && own) {
			addSiegeTierSelector(intel, main, hiveTable, faction);
		}
			main.setHeightSoFar(heightBefore);
	}

	/**
	 * The Min/Med/Max siege-force selector floated into the row reserved
	 * under the "Known hive systems" heading. Every Siege button on the rows
	 * below reads this one choice.
	 */
	protected static void addSiegeTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor, FactionAPI faction) {
		String factor = trimZero(ThreatIncConfig.siegeExtraMarinesFactor());
		addTierSelector(intel, main, anchor, "Siege force", TIER_LABEL_W, BUTTON_SIEGE_TIER,
				intel.getSiegeTier(), new String[] {
				"The siege lands the marines the assault needs.",
				"The siege lands " + factor + "x the marines the assault needs.",
				"The siege lands every marine in the base's reserve above its floor."});
	}

	/**
	 * The Min/Med/Max supply-load selector over the ground-fronts table (both
	 * views): every Supply button on the rows below runs this much.
	 */
	protected static void addSupplyTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor) {
		String factor = trimZero(ThreatIncConfig.convoyExtraLoadFactor());
		addTierSelector(intel, main, anchor, "Supply run", TIER_LABEL_W, BUTTON_SUPPLY_TIER,
				intel.getSupplyTier(), new String[] {
				"The run carries what the front wants.",
				"The run carries " + factor + "x what the front wants.",
				"The run carries a full hull load, wanted or not."});
	}

	/**
	 * The Min/Med/Max convoy-load selector over the colonies table: every
	 * Supplies and Aid button on the rows below carries this much.
	 */
	protected static void addConvoyTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
		UIComponentAPI anchor) {
		String factor = trimZero(ThreatIncConfig.convoyExtraLoadFactor());
		addTierSelector(intel, main, anchor, "Convoy load", TIER_LABEL_W, BUTTON_CONVOY_TIER,
				intel.getConvoyTier(), new String[] {
				"The convoy carries what the colony is short of.",
				"The convoy carries " + factor + "x what the colony is short of.",
				"The convoy carries a full hull load of everything the source can spare."});
	}

	/**
	 * A ladder: three buttons in the row reserved under a table's heading,
	 * mirroring the faction selector - the current tier's button is drawn
	 * disabled, the others pick a new tier with no Confirm (handled in
	 * ThreatIncursionIntel). Every order button in the table below reads the
	 * one choice, so the row quotes what it will actually send.
	 */
	protected static void addTierSelector(ThreatIncursionIntel intel, TooltipMakerAPI main,
			UIComponentAPI anchor, String label, float labelW, String buttonPrefix,
			int tier, String[] tips) {
		LabelAPI text = main.addPara(label, Misc.getGrayColor(), 0f);
		text.getPosition().aboveLeft(anchor, 4f).setXAlignOffset(2f);
		ButtonAPI prev = null;
		for (int t = 0; t < TIER_LABELS.length; t++) {
			ButtonAPI b = intel.addGenericButton(main, SMALL_BUTTON_W - 8f, TIER_LABELS[t],
					buttonPrefix + t);
			b.setEnabled(tier != t);
			b.setShowTooltipWhileInactive(true);
			if (prev == null) {
				// clear the label to the left, then chain rightward
				b.getPosition().aboveLeft(anchor, 3f).setXAlignOffset(labelW);
			} else {
				b.getPosition().rightOfTop(prev, 4f);
			}
			final String tip = tips[t];
			main.addTooltipTo(new TooltipCreator() {
				public boolean isTooltipExpandable(Object p) { return false; }
				public float getTooltipWidth(Object p) { return 300f; }
				public void createTooltip(TooltipMakerAPI tt, boolean ex, Object p) { tt.addPara(tip, 0f); }
			}, b, TooltipLocation.BELOW);
			prev = b;
		}
	}

	protected static void disableWith(TooltipMakerAPI main, ButtonAPI button, boolean enabled,
			final String reason) {
		disableWith(main, button, enabled, reason, null);
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
			if (s.exhausted) uncovered += s.deficit;
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
			if (fgi.isEnded() || fgi.isEnding()) continue;
			if (fgi.getParams() == null || fgi.getParams().raidParams == null) continue;
			if (fgi.getParams().raidParams.allowedTargets.contains(market)) n++;
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
	protected static void fleetCell(List<Object> cells, MarketAPI m, Color text) {
		if (!onLedger(m)) {
			ThreatWarBoard.cell(cells, Alignment.MID, Misc.getGrayColor(), "-");
			return;
		}
		int[] f = ThreatAidCapacity.figures(m);
		ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(f[4], text),
				Misc.getWithDGS(f[4]) + "/" + Misc.getWithDGS(f[0]));
	}

	protected static boolean onLedger(MarketAPI m) {
		return m != null && m.isPlayerOwned() && ThreatAidCapacity.enabled()
				&& ThreatAidCapacity.capacityFP(m) > 0f;
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
		ThreatWarBoard.cell(cells, Alignment.MID, fleetColor(best, plain),
				Misc.getWithDGS(sum) + "/" + Misc.getWithDGS(cap));
	}

	protected static void colonyTooltip(TooltipMakerAPI tooltip, ColonyRow r) {
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		MarketAPI m = r.market;
		tooltip.addPara(m.getName() + " - size " + m.getSize() + ", "
				+ (r.military ? militaryLabel(m) : "no military structure")
				+ (r.depot ? ", Waystation." : ", no Waystation."), 0f);
		if (!r.depot) {
			tooltip.addPara("No Waystation: nothing sails from here or lands here.",
					Misc.getNegativeHighlightColor(), 3f);
		}
		// rule 7 (docs/economy-coherence.md): vanilla's units beside the item
		// counts - surplus, availability and demand, the bank rate, and what the
		// depot is covering; the same lines the War footing condition shows
		for (String c : ThreatReserves.COMMODITIES) {
			WarFootingCondition.addCommodityLine(tooltip, m, c, 3f);
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
			tooltip.addPara("No staging base within convoy range ("
					+ (int) ThreatIncConfig.convoyRangeLY() + " ly): its reserve stays home.", gray, 10f);
		}
		// what the Defend and Aid buttons would send is on the buttons themselves
		if (m.isPlayerOwned()) {
			float pad = 10f;
			for (String line : ThreatAidCapacity.describe(m)) {
				tooltip.addPara(line, h, pad);
				pad = 3f;
			}
		}
		tooltip.addPara("Click to open the colony screen.", gray, 10f);
	}

	protected static List<FleetRow> fleetRows(String factionId) {
		List<FleetRow> rows = new ArrayList<FleetRow>();
		Color h = Misc.getHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
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
					f.interceptKey = f.recallKey;
					f.interceptSystem = hive;
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
			String task = where != null ? "besieging the " + where.getNameWithLowercaseTypeShort() : "-";
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
						? Misc.getWithDGS((int) p.getMarinesAllotted()) + " - ~" + abstractFP(p) + " FP"
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
					f.interceptKey = f.recallKey;
					f.interceptSystem = where;
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
			f.task = cargoText(c.marines, c.armaments, c.fuel, c.supplies);
			if (c.isFrontRun()) {
				f.kind = c.pickup ? "Evacuation" : "Supply run";
				f.task = (c.pickup ? "lifting the front off " : "to the front on ") + c.toName()
						+ (c.waitSinceTimestamp != 0L && !c.runningIn ? " - waiting at the door" : "");
			}
			int hunters = ThreatRaiders.huntersOf(c.fleet);
			f.status = c.fleet == null || !c.fleet.isAlive() ? "lost"
					: hunters > 0 ? "HUNTED" : "in transit";
			if (hunters > 0) f.color = neg;
			f.strength = "-";
			int days = (int) Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
			f.eta = days + " d out";
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
			f.kind = ThreatFleetOrders.KIND_SUPPORT.equals(o.kind) ? "Support"
					: ThreatFleetOrders.KIND_DEFEND.equals(o.kind) ? "Defend"
					: (o.aid ? "Aid " : "") + (ThreatFleetOrders.KIND_GUARD.equals(o.kind)
					? (o.aid ? "guard" : "Guard") : (o.aid ? "intercept" : "Intercept"));
			f.color = o.fleet != null && o.fleet.isAlive() ? pos : neg;
			f.name = (o.fleet != null ? o.fleet.getName() : "-") + fromColony(o.baseMarketId);
			f.task = o.task();
			f.status = o.fleet != null && o.fleet.isAlive()
					? (o.arrived ? "on station" : "en route") : "lost";
			f.strength = fleetStrength(o.fleet);
			f.eta = o.indefinite() ? "-" : (int) Math.ceil(o.daysLeft()) + " d left";
			f.rowId = o.fleet;
			f.recallKey = "order:" + i;
			rows.add(f);
		}
		// fleets on tracked legs home (2026-09-06: an expedition's fleets
		// vanished from the board the moment it stood down, though they were
		// still weeks out); Intercept turns one back to hold the door of the
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
			if (origin != null) {
				f.interceptKey = "returnfleet:" + i + ":" + r.fleet.getId();
				f.interceptSystem = origin;
			}
			rows.add(f);
		}
		// the player's aid bound for this faction (docs/player-aid.md): listed
		// here so the player can watch it land, and recall it
		if (!com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER.equals(factionId)) {
			for (int i = 0; i < orders.size(); i++) {
				ThreatFleetOrders.Order o = orders.get(i);
				if (!o.aid || !factionId.equals(o.recipientFactionId)) continue;
				FleetRow f = new FleetRow();
				f.kind = "Your guard";
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
				rows.add(f);
			}
			for (int i = 0; i < convoys.size(); i++) {
				ThreatConvoys.Convoy c = convoys.get(i);
				if (!c.aid || !factionId.equals(c.recipientFactionId)) continue;
				FleetRow f = new FleetRow();
				f.kind = "Your convoy";
				f.color = h;
				f.name = c.fromName() + " -> " + c.toName();
				f.task = cargoText(c.marines, c.armaments, c.fuel, c.supplies);
				int hunters = ThreatRaiders.huntersOf(c.fleet);
				f.status = c.fleet == null || !c.fleet.isAlive() ? "lost"
						: hunters > 0 ? "HUNTED" : "in transit";
				if (hunters > 0) f.color = neg;
				f.strength = "-";
				int days = (int) Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
				f.eta = days + " d out";
				f.rowId = c.fleet;
				f.recallKey = "aidconvoy:" + i;
				f.playerAid = true;
				rows.add(f);
			}
		}
		return rows;
	}

	/** "<marines> - <fp> FP" for the Marines - Fleet column - the marines a fleet carries over its fleet points. */
	protected static String fleetStrength(CampaignFleetAPI fleet) {
		if (fleet == null) return "-";
		return Misc.getWithDGS((int) fleet.getCargo().getMarines())
				+ " - " + (int) fleet.getFleetPoints() + " FP";
	}

	/** The FP an unspawned expedition will field on arrival, estimated from its planned fleet sizes. */
	protected static int abstractFP(ThreatPurgeFGI p) {
		float fp = 0f;
		if (p.getParams() != null && p.getParams().fleetSizes != null) {
			for (Integer size : p.getParams().fleetSizes) {
				if (size != null) fp += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		return Math.round(fp);
	}

	/**
	 * The marines a siege tier's landing draws from the base's reserve: the need
	 * (Siege), the need x siegeExtraMarinesFactor (Extra), or every marine above
	 * the reserve floor (All). The flotilla is then grown to carry it and
	 * trimmed to the base's free fleet points, so the marines that actually land
	 * can be less when the base is small.
	 */
	protected static float siegeMarineGoal(int tier, MarketAPI base, List<MarketAPI> targets) {
		float need = IncursionManager.siegeRaidStrNeeded(targets);
		if (tier == 1) {
			return need * ThreatIncConfig.siegeExtraMarinesFactor();
		}
		if (tier == 2) {
			return Math.max(need, ThreatReserves.available(base, Commodities.MARINES));
		}
		return need;
	}

	/** Every ladder's three labels, index = tier: what is asked for, more, everything sparable. */
	protected static final String[] TIER_LABELS = {"Min", "Med", "Max"};

	/** Where a ladder's first button sits: clear of the widest selector label. */
	protected static final float TIER_LABEL_W = 92f;

	/** "2" for 2.0, "1.5" for 1.5 - a factor without a trailing zero. */
	protected static String trimZero(float f) {
		return f == Math.floor(f) ? "" + (int) f : "" + f;
	}

	protected static String cargoText(float marines, float armaments, float fuel, float supplies) {
		List<String> parts = new ArrayList<String>();
		if (marines > 0f) parts.add((int) marines + " marines");
		if (armaments > 0f) parts.add((int) armaments + " arms");
		if (fuel > 0f) parts.add((int) fuel + " fuel");
		if (supplies > 0f) parts.add((int) supplies + " supplies");
		return parts.isEmpty() ? "empty" : ThreatWarBoard.join(parts);
	}

	/** Known infested colony systems within expedition reach of any of the faction's military worlds - for the player, every known one (no range). */
	protected static List<ThreatWarBoard.Entry> hivesInReach(List<MarketAPI> markets) {
		List<ThreatWarBoard.Entry> result = new ArrayList<ThreatWarBoard.Entry>();
		for (ThreatWarBoard.Entry e : ThreatWarBoard.buildEntries()) {
			if (!e.known || e.system == null) continue;
			if (nearestBase(markets, e.system) != null) result.add(e);
		}
		return result;
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
		main.addPara("Mobilise: every colony goes on War footing, banking marines, heavy "
				+ "armaments, fuel and supplies from its own surplus (seeded with %s months' "
				+ "worth) and carrying %s units of extra demand at size 5, scaled by size. "
				+ "Stand down here any time; reserves are kept.",
				opad, h, "" + (int) ThreatIncConfig.reserveInitialMonths(),
				"" + (int) ThreatIncConfig.warFootingDemandUnits());
		ButtonAPI mobilise = intel.addGenericButton(main, SELECTOR_BUTTON_W, "Mobilise",
				BUTTON_MOBILISE + faction.getId());
		disableWith(main, mobilise, ThreatWarState.playerMayMobilise(),
				"You need a colony to mobilise.");
	}

	/** Whether this button id is one of the faction view's order buttons. */
	public static boolean isOrderButton(String id) {
		return id.startsWith(BUTTON_GUARD) || id.startsWith(BUTTON_STAGE)
				|| id.startsWith(BUTTON_INTERCEPT) || id.startsWith(BUTTON_SIEGE)
				|| id.startsWith(BUTTON_RECALL) || id.startsWith(BUTTON_DETACH)
				|| id.startsWith(BUTTON_SUPPLY)
				|| id.startsWith(BUTTON_PULLOUT) || id.startsWith(BUTTON_SUPPORT)
				|| id.startsWith(BUTTON_DEFEND)
				|| id.startsWith(BUTTON_PUSH) || id.startsWith(BUTTON_ENTRENCH)
				|| id.startsWith(BUTTON_OUTPOST)
				|| id.startsWith(BUTTON_AID_DEFEND) || id.startsWith(BUTTON_AID_SUPPLY)
				|| id.startsWith(BUTTON_AID_STRIKE) || id.startsWith(BUTTON_MOBILISE)
				|| id.startsWith(BUTTON_STAND_DOWN);
	}

	/** Splits "prefix" + "factionId:target" into [prefix, factionId, target]. */
	protected static String[] parse(String id) {
		String[] prefixes = {BUTTON_GUARD, BUTTON_STAGE, BUTTON_INTERCEPT, BUTTON_SIEGE,
				BUTTON_RECALL,
				BUTTON_DETACH,
				BUTTON_SUPPLY, BUTTON_PULLOUT, BUTTON_SUPPORT, BUTTON_DEFEND, BUTTON_PUSH, BUTTON_ENTRENCH,
				BUTTON_OUTPOST, BUTTON_AID_DEFEND, BUTTON_AID_SUPPLY,
				BUTTON_AID_STRIKE, BUTTON_MOBILISE, BUTTON_STAND_DOWN};
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
		int siegeTier = intel != null ? intel.getSiegeTier() : 0;
		int supplyTier = intel != null ? intel.getSupplyTier() : 0;
		int convoyTier = intel != null ? intel.getConvoyTier() : 0;
		FactionAPI faction = Global.getSector().getFaction(parts[1]);
		String who = faction == null ? parts[1] : faction.isPlayerFaction() ? "your"
				: faction.getDisplayNameWithArticle();
		Color h = Misc.getHighlightColor();
		if (BUTTON_MOBILISE.equals(parts[0])) {
			int colonies = Misc.getPlayerMarkets(false).size();
			prompt.addPara("Mobilise your faction? Every colony of yours (%s) goes on War footing "
					+ "now, seeded with %s months' reserve and carrying %s units of extra demand "
					+ "at size 5, scaled by size.", 0f, h,
					"" + colonies, "" + (int) ThreatIncConfig.reserveInitialMonths(),
					"" + (int) ThreatIncConfig.warFootingDemandUnits());
			return;
		}
		if (BUTTON_STAND_DOWN.equals(parts[0])) {
			prompt.addPara("Stand your faction down? War footing and its demand are lifted, "
					+ "reserves are kept as they stand, and fleets already out finish their "
					+ "runs.", 0f);
			return;
		}
		if (BUTTON_GUARD.equals(parts[0])) {
			// a colony, or the player's outpost
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			// an own colony guards itself when it has own points free (staging)
			boolean own = faction != null && faction.isPlayerFaction() && target != null
					&& faction.getId().equals(target.factionId());
			MarketAPI base = target != null
					? ThreatAid.pickTaskForceSource(target.hyperLoc(), own ? target.market : null)
					: null;
			boolean self = base != null && target != null && base == target.market;
			float days = own ? ThreatIncConfig.guardOwnDays() : ThreatIncConfig.guardDays();
			// whole-fleet points, the FP column's unit
			String fp = "" + (int) (base != null ? ThreatAid.taskForcePoints(base, self)
					: ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP()));
			String from = base != null ? base.getName() : "the nearest base";
			String task = self ? "to hold its own orbit"
					: "to take the orbit of " + (target != null ? target.name() : "the world");
			if (days > 0f) {
				prompt.addPara("Order a task force of about %s fleet points from " + from + " "
						+ task + " for %s days? Fuel and supplies come from its reserve.", 0f, h,
						fp, "" + (int) days);
			} else {
				prompt.addPara("Order a task force of about %s fleet points from " + from + " "
						+ task + " until recalled? Fuel and supplies come from its reserve.", 0f, h, fp);
			}
		} else if (BUTTON_STAGE.equals(parts[0])) {
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			MarketAPI donor = ThreatConvoys.stageDonor(target, faction, convoyTier);
			if (donor == null) {
				prompt.addPara("No colony of " + (faction != null && faction.isPlayerFaction()
						? "yours" : who) + " can spare a load for "
						+ (target != null ? target.name() : "the world")
						+ " at this convoy load.", 0f);
			} else {
				float[] load = ThreatConvoys.stageLoad(donor, target, convoyTier);
				prompt.addPara("Order a convoy from " + donor.getName() + " to " + target.name()
						+ " with %s? It can be intercepted on the way.", 0f, h,
						cargoText(load[0], load[1], load[2], load[3]));
			}
		} else if (BUTTON_INTERCEPT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = system != null ? ThreatAid.pickTaskForceSource(system.getLocation()) : null;
			prompt.addPara("Order a task force of about %s fleet points from "
					+ (base != null ? base.getName() : "the nearest base") + " to hold the jump-point "
					+ "of the " + (system != null ? system.getNameWithLowercaseType() : "hive system")
					+ " for %s days? Fuel and supplies come from its reserve.", 0f, h,
					"" + (int) (base != null ? ThreatAid.taskForcePoints(base, false)
							: ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP())),
					"" + (int) ThreatIncConfig.interceptDays());
		} else if (BUTTON_SIEGE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = null;
			if (faction != null && system != null) {
				base = nearestBase(ThreatReserves.marketsOf(faction.getId()), system);
			}
			String why = IncursionManager.siegeBlockReason(base, faction, system);
			if (why != null) {
				prompt.addPara(why, 0f);
			} else {
				// the selected tier's landing goal, and the flotilla grown to
				// carry it then trimmed to the base's free points (siegeMarineGoal/siegeSizes)
				List<MarketAPI> targets = IncursionManager.collectSiegeTargets(null, system);
				float goal = siegeMarineGoal(siegeTier, base, targets);
				float have = ThreatReserves.available(base, Commodities.MARINES);
				float commit = Math.min(goal, have);
				List<Integer> sizes = IncursionManager.siegeSizes(base, faction, system, goal);
				if (faction.isPlayerFaction() && ThreatAidCapacity.enabled()) {
					prompt.addPara("Order a purge expedition from " + base.getName() + " against the "
							+ system.getNameWithLowercaseType() + "? %s fleets holding %s of its %s FP "
							+ "free; commits %s marines of %s in its reserve.",
							0f, h, "" + sizes.size(),
							Misc.getWithDGS((int) ThreatAidCapacity.expeditionPoints(sizes)),
							Misc.getWithDGS((int) Math.max(0f, ThreatAidCapacity.freeFP(base))),
							Misc.getWithDGS((int) commit), Misc.getWithDGS((int) have));
				} else {
					prompt.addPara("Order a purge expedition from " + base.getName() + " against the "
							+ system.getNameWithLowercaseType() + "? %s fleets; commits %s marines "
							+ "of %s in its reserve.", 0f, h,
							"" + sizes.size(), Misc.getWithDGS((int) commit),
							Misc.getWithDGS((int) have));
				}
			}
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
			com.fs.starfarer.api.campaign.SectorEntityToken point = hive != null
					? ThreatFleetOrders.interceptPoint(hive) : null;
			if (fleet == null || hive == null || point == null) {
				prompt.addPara("That fleet is no longer with its group.", 0f);
			} else {
				prompt.addPara("Detach " + fleet.getName() + " to hold %s for %s days? It leaves "
						+ "its group, meets the swarm's traffic at the door, and goes home when "
						+ "the order runs out"
						+ (fleet.getCargo().getMarines() > 0 ? " - the marines aboard stay "
								+ "aboard." : "."), 0f, h,
						point.getName() != null ? point.getName()
								: "the " + hive.getNameWithLowercaseTypeShort() + " jump-point",
						"" + (int) ThreatIncConfig.interceptDays());
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
		} else if (BUTTON_AID_DEFEND.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			ThreatAid.Quote q = ThreatAid.quoteDefend(target);
			if (!q.ok() || target == null) {
				prompt.addPara(q.reason != null ? q.reason : "The situation has changed.", 0f);
			} else {
				prompt.addPara("Send a task force of about %s fleet points from " + q.source.getName()
						+ " to take the orbit of " + target.getName() + " for %s days, on behalf of "
						+ who + "? Fuel and supplies come from " + q.source.getName()
						+ "'s reserve, and its hulls are held against that colony's capacity "
						+ "until they are home. Standing with "
						+ (faction != null ? faction.getDisplayName() : "the faction")
						+ " is earned when it arrives and again when it serves its term.", 0f, h,
						"" + (int) q.points, "" + (int) ThreatIncConfig.guardDays());
			}
		} else if (BUTTON_AID_SUPPLY.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			ThreatAid.Quote q = ThreatAid.quoteResupply(target, convoyTier);
			if (!q.ok() || target == null) {
				prompt.addPara(q.reason != null ? q.reason : "The situation has changed.", 0f);
			} else {
				boolean request = ThreatAidMissionIntel.find(target.getId(),
						ThreatAidMissionIntel.KIND_AID, q.commodityId) != null;
				prompt.addPara("Send %s " + ThreatReserves.label(q.commodityId) + " from "
						+ q.source.getName() + " to " + target.getName() + ", for " + who + "? It is "
						+ "short of about %s. The goods leave " + q.source.getName() + "'s reserve, "
						+ "the hulls are held against its capacity until home, and the convoy can "
						+ "be intercepted on the way. Standing follows the goods' value at "
						+ target.getName() + "'s prices"
						+ (request ? ", doubled under the open request" : "") + ".",
						0f, h, Misc.getWithDGS(q.quantity), Misc.getWithDGS(q.need));
			}
		} else if (BUTTON_AID_STRIKE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			ThreatAid.Quote q = ThreatAid.quoteStrike(system);
			if (!q.ok() || system == null) {
				prompt.addPara(q.reason != null ? q.reason : "The situation has changed.", 0f);
			} else {
				prompt.addPara("Send a task force of about %s fleet points from " + q.source.getName()
						+ " to hold the jump-point of the " + system.getNameWithLowercaseType()
						+ " for %s days, meeting the swarm's reinforcements and expeditions at the "
						+ "door? Fuel and supplies come from " + q.source.getName() + "'s reserve "
						+ "and its hulls are held against that colony's capacity until home. "
						+ "Every faction with a colony in the hive's reach notes it.", 0f, h,
						"" + (int) q.points, "" + (int) ThreatIncConfig.interceptDays());
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
			String why = ThreatFleetOrders.orbitBlockReason(faction, hive, kind);
			ThreatFleetOrders.Reassignable near = why == null && faction != null && hive != null
					? ThreatFleetOrders.nearestReassignable(faction, hive, kind) : null;
			if (why != null) {
				prompt.addPara(why, 0f);
			} else if (near != null) {
				prompt.addPara("Detach %s (" + near.duty + ", " + near.where() + ") to " + verb + " "
						+ hive.getName() + " for %s days?", 0f, h, near.fleet.getName(), days);
				prompt.addPara(ThreatFleetOrders.orbitEffect(hive, near.fleet.getFleetPoints(), kind), 10f);
			} else {
				float points = base != null ? ThreatAid.taskForcePoints(base, false)
						: ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP());
				prompt.addPara("Order a task force of about %s fleet points from "
						+ (base != null ? base.getName() : "the nearest base") + " to " + verb + " "
						+ (hive != null ? hive.getName() : "the world") + " for %s days?", 0f, h,
						"" + (int) points, days);
				prompt.addPara(ThreatFleetOrders.orbitEffect(hive, points, kind), 10f);
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
				prompt.addPara("Order the front on " + world.getName() + " to break off and dig in? "
						+ "Losses fall to about %s marines a day and it defends at x%s from "
						+ "cover; its progress on stratum %s is lost.", 0f, h,
						ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(front, world, false)),
						String.format("%.1f", ThreatIncConfig.frontEntrenchDefenseBonus()),
						"" + (front.strataHeld + 1));
			}
		} else if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = hive != null
					? ThreatGroundFronts.getFront(hive.getId()) : null;
			MarketAPI base = faction != null && hive != null
					? ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace()) : null;
			String refused = BUTTON_SUPPLY.equals(parts[0])
					? ThreatConvoys.supplyBlockReason(faction, hive, supplyTier)
					: ThreatConvoys.pullOutBlockReason(faction, hive);
			if (hive == null || front == null) {
				prompt.addPara("There is no front there any more.", 0f);
			} else if (refused != null) {
				prompt.addPara(refused, 0f);
			} else if (BUTTON_SUPPLY.equals(parts[0])) {
				float[] asked = ThreatConvoys.supplyAsk(front, hive, supplyTier);
				prompt.addPara("Send a supply run from " + (base != null ? base.getName()
						: "the nearest base") + " to the front on " + hive.getName() + "? It "
						+ "asks for %s marines and %s heavy armaments, or what the base can "
						+ "spare of that; the run waits at the jump-point while Defense Swarms "
						+ "hold the orbit, and can be intercepted on the way.", 0f, h,
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

	/** Executes an order button. Returns a message for the log, or null if nothing happened. */
	public static String executeOrder(String id, ThreatIncursionIntel intel) {
		String[] parts = parse(id);
		if (parts == null) return null;
		int siegeTier = intel != null ? intel.getSiegeTier() : 0;
		int supplyTier = intel != null ? intel.getSupplyTier() : 0;
		int convoyTier = intel != null ? intel.getConvoyTier() : 0;
		Random random = new Random();
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
		if (BUTTON_AID_DEFEND.equals(parts[0])) {
			return ThreatAid.dispatchDefend(Global.getSector().getEconomy().getMarket(parts[2]))
					? "aid-defend" : null;
		}
		if (BUTTON_AID_SUPPLY.equals(parts[0])) {
			return ThreatAid.dispatchResupply(Global.getSector().getEconomy().getMarket(parts[2]),
					convoyTier, random) ? "aid-supply" : null;
		}
		if (BUTTON_AID_STRIKE.equals(parts[0])) {
			return ThreatAid.dispatchStrike(ThreatWarBoard.getSystem(parts[2])) ? "aid-strike" : null;
		}
		if (BUTTON_RECALL.equals(parts[0])
				&& (parts[2].startsWith("aidorder:") || parts[2].startsWith("aidconvoy:"))) {
			return recall(parts[1], parts[2]) ? "recall" : null;
		}
		if (!ThreatFleetOrders.canPlayerOrder(faction)) return null;
		if (BUTTON_GUARD.equals(parts[0])) {
			// a colony, or the player's outpost
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			return ThreatFleetOrders.dispatchGuard(faction, target) != null ? "guard" : null;
		}
		if (BUTTON_STAGE.equals(parts[0])) {
			ThreatBases.Base target = ThreatBases.of(parts[2]);
			if (target == null) return null;
			ThreatConvoys.Convoy c = ThreatConvoys.stageTo(target, faction, convoyTier, random);
			if (c == null) {
				ThreatColonyManager.announceAlways("No colony of "
						+ (faction.isPlayerFaction() ? "yours" : faction.getDisplayName())
						+ " within convoy range can spare materiel for " + target.name()
						+ " right now.", Misc.getNegativeHighlightColor());
				return null;
			}
			return "stage";
		}
		if (BUTTON_INTERCEPT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			return ThreatFleetOrders.dispatchIntercept(faction, system) != null ? "intercept" : null;
		}
		if (BUTTON_SIEGE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			if (system == null) return null;
			MarketAPI base = nearestBase(ThreatReserves.marketsOf(faction.getId()), system);
			if (base == null) return null;
			List<MarketAPI> targets = IncursionManager.collectSiegeTargets(null, system);
			if (targets.isEmpty()) return null;
			boolean anyGarrisoned = IncursionManager.anyTargetGarrisoned(targets);
			int difficulty = IncursionManager.computeSiegeDifficulty(targets, anyGarrisoned);
			// the selected tier's landing goal: the need, the need x a knob, or
			// every marine the base can spare (docs/player-aid.md) - the flotilla
			// is grown to carry it, then trimmed to the base's free fleet points
			float marineGoal = siegeMarineGoal(siegeTier, base, targets);
			List<Integer> sizes = IncursionManager.siegeFleetSizes(difficulty,
					anyGarrisoned, false, targets, marineGoal);
			ThreatPurgeFGI purge = IncursionManager.launchSiegeExpedition(base, faction, system,
					targets, sizes, faction.isPlayerFaction(), random, marineGoal);
			if (purge == null) {
				// the launch's own gates (fleet points, then marines) say why
				String why = IncursionManager.siegeBlockReason(base, faction, system);
				ThreatColonyManager.announceAlways(why != null ? why
						: "No expedition could be raised at " + base.getName() + " right now.",
						Misc.getNegativeHighlightColor());
				return null;
			}
			ThreatColonyManager.announceAlways(Misc.ucFirst(faction.isPlayerFaction() ? "your"
					: faction.getDisplayNameWithArticle()) + " purge expedition is mustering at "
					+ base.getName() + ", bound for the " + system.getNameWithLowercaseType()
					+ ".", Misc.getHighlightColor());
			return "siege";
		}
		if (BUTTON_RECALL.equals(parts[0])) {
			return recall(parts[1], parts[2]) ? "recall" : null;
		}
		if (BUTTON_DETACH.equals(parts[0])) {
			return detachToIntercept(faction, parts[2]) ? "detach" : null;
		}
		if (BUTTON_OUTPOST.equals(parts[0])) {
			com.fs.starfarer.api.campaign.SectorEntityToken planet =
					Global.getSector().getEntityById(parts[2]);
			if (!(planet instanceof com.fs.starfarer.api.campaign.PlanetAPI)) return null;
			ThreatOutposts.Outpost o = ThreatOutposts.build(faction,
					(com.fs.starfarer.api.campaign.PlanetAPI) planet);
			if (o == null) {
				ThreatColonyManager.announceAlways("No outpost could be built over "
						+ planet.getName() + ": no base of "
						+ (faction.isPlayerFaction() ? "yours" : faction.getDisplayName())
						+ " is in reach, or it cannot pay.", Misc.getNegativeHighlightColor());
				return null;
			}
			return "outpost";
		}
		if (BUTTON_PUSH.equals(parts[0]) || BUTTON_ENTRENCH.equals(parts[0])) {
			// the player's own front only: NPC fronts run their stance AI
			if (!faction.isPlayerFaction()) return null;
			MarketAPI world = ThreatGroundFronts.resolveMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = world != null
					? ThreatGroundFronts.getFront(world.getId()) : null;
			if (front == null || !front.isPlayerOwned()) return null;
			boolean push = BUTTON_PUSH.equals(parts[0]);
			String why = push ? ThreatGroundFronts.pushBlockReason(front, world)
					: ThreatGroundFronts.entrenchBlockReason(front);
			if (why != null) {
				ThreatColonyManager.announceAlways(why, Misc.getNegativeHighlightColor());
				return null;
			}
			if (push) {
				ThreatGroundFronts.orderPush(front);
				ThreatColonyManager.announceAlways("The order goes down to the front on "
						+ world.getName() + ": take stratum " + (front.strataHeld + 1) + ".",
						Misc.getHighlightColor());
				return "push";
			}
			ThreatGroundFronts.orderEntrench(front);
			ThreatColonyManager.announceAlways("The order goes down to the front on "
					+ world.getName() + ": break off and dig in.", Misc.getHighlightColor());
			return "entrench";
		}
		if (BUTTON_SUPPORT.equals(parts[0]) || BUTTON_DEFEND.equals(parts[0])) {
			String kind = BUTTON_DEFEND.equals(parts[0]) ? ThreatFleetOrders.KIND_DEFEND
					: ThreatFleetOrders.KIND_SUPPORT;
			// a front can stand on a human world too: the same resolver the prompt uses
			MarketAPI hive = ThreatGroundFronts.resolveMarket(parts[2]);
			if (hive == null) return null;
			String why = ThreatFleetOrders.orbitBlockReason(faction, hive, kind);
			if (why == null && ThreatFleetOrders.dispatchOrbit(faction, hive, kind) != null) {
				return ThreatFleetOrders.orbitName(kind).toLowerCase();
			}
			ThreatColonyManager.announceAlways(why != null ? why
					: "No task force could be raised for the orbit of " + hive.getName() + ".",
					Misc.getNegativeHighlightColor());
			return null;
		}
		if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			if (hive == null) return null;
			String refused = ThreatConvoys.canRunTo(faction, hive);
			if (refused != null) {
				ThreatColonyManager.announceAlways(refused, Misc.getNegativeHighlightColor());
				return null;
			}
			boolean pull = BUTTON_PULLOUT.equals(parts[0]);
			ThreatConvoys.Convoy c = pull ? ThreatConvoys.pullOutFront(hive, faction, random)
					: ThreatConvoys.supplyFront(hive, faction, supplyTier, random);
			if (c == null) {
				ThreatColonyManager.announceAlways("No " + (pull ? "evacuation" : "supply")
						+ " run could be raised for " + hive.getName() + ": no base of "
						+ (faction.isPlayerFaction() ? "yours" : faction.getDisplayName())
						+ " is in reach with anything to send, or a run is already bound there.",
						Misc.getNegativeHighlightColor());
				return null;
			}
			return pull ? "pullout" : "supply";
		}
		return null;
	}

	/** Purged worlds within expedition reach of one of the faction's military worlds. */
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

	/** One fleet leaves its expedition or task force and holds the hive's door (ThreatFleetOrders). */
	protected static boolean detachToIntercept(FactionAPI faction, String key) {
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
			if (ThreatFleetOrders.adoptIntercept(fleet, faction, hive, base) == null) {
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
			if (ThreatFleetOrders.adoptIntercept(fleet, faction, hive, base) == null) {
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
		if (ThreatFleetOrders.adoptIntercept(fleet, faction, hive, base) == null) {
			if (home != null) ThreatReturns.sendHome(fleet, faction.getId(), home);
			return false;
		}
		return true;
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
