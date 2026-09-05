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
	public static final String BUTTON_RECALL = "threatinc_board_recall:";
	/** Supply run to the faction's front on a hive world (payload: factionId:hiveMarketId). */
	public static final String BUTTON_SUPPLY = "threatinc_board_supply:";
	/** Withdrawal run for the faction's front on a hive world. */
	public static final String BUTTON_PULLOUT = "threatinc_board_pullout:";
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

	/** Table row id prefix for a faction colony (click = show on map). */
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
		StarSystemAPI staging;
		float stagingLY;
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
		/** Recall payload: "tf:i", "purge:i", "convoy:i", "order:i", "aidorder:i", "aidconvoy:i". */
		String recallKey;
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
		if (!rows.isEmpty()) {
			main.addSectionHeading("Colonies - staging bases first", bright, dark, Alignment.MID, opad);
			float tw = width - 24f;
			// Actions must hold two floating buttons (Defend + Aid = 114 px with
			// gaps): at .08 the Aid button used to overlap the Strikes column
			float[] frac = {.17f, .05f, .08f, .07f, .08f, .08f, .08f, .08f, .13f, .06f, .12f};
			String[] names = {"Colony", "Size", "Military", "Def", "Marines", "Arms", "Fuel",
					"Supplies", "Staging", "Strikes", "Actions"};
			List<Object> columns = new ArrayList<Object>();
			for (int i = 0; i < names.length; i++) {
				columns.add(names[i]);
				columns.add((float) Math.floor(tw * frac[i]));
			}
			colonyTable = main.beginTable2(faction, ThreatWarBoard.ROW_H, true, true, columns.toArray());
			main.makeTableItemsClickable();
			main.addTableHeaderTooltip(2, "Expeditions, task forces and convoys sail from military worlds.");
			main.addTableHeaderTooltip(3, "Ground defense - what a hive landing must beat.");
			main.addTableHeaderTooltip(4, "Banked from the colony's surplus above demand, militia included.");
			main.addTableHeaderTooltip(5, "Banked from the colony's surplus above demand.");
			main.addTableHeaderTooltip(6, "Banked from the colony's surplus above demand. Sorties burn it.");
			main.addTableHeaderTooltip(7, "Banked from the colony's surplus above demand. Sorties use it.");
			main.addTableHeaderTooltip(8, "Nearest live hive in expedition reach - what this base "
					+ "sails against and is stocked for.");
			main.addTableHeaderTooltip(9, "Threat expeditions in flight against this world.");
			for (final ColonyRow r : rows) {
				MarketAPI m = r.market;
				List<Object> cells = new ArrayList<Object>();
				ThreatWarBoard.cell(cells, Alignment.LMID, r.military ? bright : text,
						main.shortenString(m.getName(), (float) Math.floor(tw * frac[0]) - 10f));
				ThreatWarBoard.cell(cells, Alignment.MID, h, "" + m.getSize());
				ThreatWarBoard.cell(cells, Alignment.MID, r.military ? pos : gray,
						r.military ? militaryLabel(m) : "-");
				ThreatWarBoard.cell(cells, Alignment.MID, text,
						Misc.getWithDGS((int) MarketCMD.getDefenderStr(m)));
				ThreatWarBoard.cell(cells, Alignment.MID, stockColor(m, Commodities.MARINES, text),
						stockText(m, Commodities.MARINES));
				ThreatWarBoard.cell(cells, Alignment.MID, stockColor(m, Commodities.HAND_WEAPONS, text),
						stockText(m, Commodities.HAND_WEAPONS));
				ThreatWarBoard.cell(cells, Alignment.MID, stockColor(m, Commodities.FUEL, text),
						stockText(m, Commodities.FUEL));
				ThreatWarBoard.cell(cells, Alignment.MID, stockColor(m, Commodities.SUPPLIES, text),
						stockText(m, Commodities.SUPPLIES));
				// the distance is the part that must survive: shorten the name
				// around it, not the other way round
				String stagingText = "-";
				if (r.staging != null) {
					String ly = " " + (int) Math.ceil(r.stagingLY) + " ly";
					stagingText = main.shortenString(r.staging.getNameWithNoType(),
							(float) Math.floor(tw * frac[8]) - 10f - main.computeStringWidth(ly)) + ly;
				}
				ThreatWarBoard.cell(cells, Alignment.MID, r.staging != null ? h : gray, stagingText);
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
			// totals row - the faction's whole reserve, coloured like the cells:
			// red when a commodity every colony is short of has nothing banked
			// anywhere, bright when some colony is short, plain otherwise
			List<Object> totals = new ArrayList<Object>();
			ThreatWarBoard.cell(totals, Alignment.LMID, h, "Total");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			for (String c : ThreatReserves.COMMODITIES) {
				ThreatWarBoard.cell(totals, Alignment.MID, totalColor(rows, c, h),
						Misc.getWithDGS((int) ThreatReserves.factionStock(factionId, c)));
			}
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			ThreatWarBoard.cell(totals, Alignment.MID, gray, "");
			main.addRow(totals.toArray());
			main.addTable("None", -1, 0f);
			// the key to the colours, in the colours
			LabelAPI key = main.addPara("banked   short, depot covering   short, depot dry   "
					+ "-  nothing banked", gray, 4f);
			key.setHighlight("banked", "short, depot covering", "short, depot dry");
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
			float[] frac = {.12f, .20f, .26f, .13f, .13f, .08f, .08f};
			String[] names = {"Kind", "Fleet", "Task", "Status", "Strength", "ETA", "Actions"};
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
		main.addSectionHeading("Hive systems in reach", bright, dark, Alignment.MID, opad);
		if (reach.isEmpty()) {
			main.addPara("No known infested system lies within expedition reach of this "
					+ "faction's military worlds.", gray, opad);
		} else {
			float tw = width - 24f;
			float[] frac = {.24f, .12f, .10f, .24f, .10f, .20f};
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
		float heightBefore = main.getHeightSoFar();
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
		if (colonyTable != null) {
			int n = rows.size();
			int guardDays = (int) ThreatIncConfig.guardDays();
			for (int i = 0; i < n; i++) {
				ColonyRow r = rows.get(i);
				// the totals row sits below the last colony: one more row up
				float up = (n + 1 - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				if (own) {
					ButtonAPI guard = intel.addGenericButton(main, SMALL_BUTTON_W, "Guard",
							BUTTON_GUARD + factionId + ":" + r.market.getId());
					guard.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
					ButtonAPI stage = intel.addGenericButton(main, SMALL_BUTTON_W, "Stage",
							BUTTON_STAGE + factionId + ":" + r.market.getId());
					stage.getPosition().belowRight(colonyTable, -up)
							.setXAlignOffset(-6f - SMALL_BUTTON_W - 4f);
					ThreatAid.Quote q = ThreatAid.quoteDefend(r.market);
					boolean guardOk = mayOrder && q.ok();
					boolean stageOk = mayOrder && ThreatIncConfig.convoyEnabled();
					disableWith(main, guard, guardOk, blocked != null ? blocked : q.reason,
							"A task force holds this orbit for " + guardDays + " days, paid from "
							+ "the colony it sails from.");
					disableWith(main, stage, stageOk, blocked != null ? blocked
							: "Convoys are disabled in the mod settings.",
							"A convoy brings what this colony is short of from the colony that "
							+ "can best spare it.");
				} else {
					ButtonAPI defend = intel.addGenericButton(main, SMALL_BUTTON_W + 6f, "Defend",
							BUTTON_AID_DEFEND + factionId + ":" + r.market.getId());
					defend.getPosition().belowRight(colonyTable, -up).setXAlignOffset(-6f);
					ButtonAPI aid = intel.addGenericButton(main, SMALL_BUTTON_W - 10f, "Aid",
							BUTTON_AID_SUPPLY + factionId + ":" + r.market.getId());
					aid.getPosition().belowRight(colonyTable, -up)
							.setXAlignOffset(-6f - SMALL_BUTTON_W - 6f - 4f);
					ThreatAid.Quote d = ThreatAid.quoteDefend(r.market);
					ThreatAid.Quote s = ThreatAid.quoteResupply(r.market);
					disableWith(main, defend, mayAid && d.ok(),
							aidBlocked != null ? aidBlocked : d.reason,
							"A task force of about " + (d.ok() ? (int) d.fp : 0) + " FP from "
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
				if (f.recallKey == null) continue;
				float up = (n - i) * ThreatWarBoard.ROW_H - (ThreatWarBoard.ROW_H - 20f) / 2f;
				ButtonAPI recall = intel.addGenericButton(main, SMALL_BUTTON_W + 8f, "Recall",
						BUTTON_RECALL + factionId + ":" + f.recallKey);
				recall.getPosition().belowRight(fleetTable, -up).setXAlignOffset(-6f);
				disableWith(main, recall, mayOrder || f.playerAid, blocked,
						"The fleet breaks off and returns to base. A convoy brings its cargo "
						+ "home; an expedition abandons its campaign.");
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
							"A task force of about " + (q.ok() ? (int) q.fp : 0) + " FP from "
							+ (q.ok() ? q.source.getName() : "your nearest colony") + " holds "
							+ "this hive's jump-point for " + (int) ThreatIncConfig.interceptDays()
							+ " days. Paid from that colony's reserve; every faction in the "
							+ "hive's reach notes it.");
					continue;
				}
				ButtonAPI siege = intel.addGenericButton(main, SMALL_BUTTON_W, "Siege",
						BUTTON_SIEGE + factionId + ":" + e.systemId);
				siege.getPosition().belowRight(hiveTable, -up).setXAlignOffset(-6f);
				ButtonAPI intercept = intel.addGenericButton(main, SMALL_BUTTON_W + 14f, "Intercept",
						BUTTON_INTERCEPT + factionId + ":" + e.systemId);
				intercept.getPosition().belowRight(hiveTable, -up)
						.setXAlignOffset(-6f - SMALL_BUTTON_W - 4f);
				boolean baseOk = nearestBase(markets, e.system) != null;
				disableWith(main, siege, mayOrder && baseOk && e.isColony(), blocked != null ? blocked
						: "No base in reach, or nothing there to besiege yet.",
						"A full purge expedition sails from the nearest base, its landing force "
						+ "drawn from that base's reserve.");
				disableWith(main, intercept, mayOrder && baseOk, blocked != null ? blocked
						: "No base in reach.",
						"A task force holds this hive's jump-point for "
						+ (int) ThreatIncConfig.interceptDays() + " days, meeting the swarm's "
						+ "reinforcements and expeditions at the door.");
				// a front of this faction fights here: supply and pull-out runs
				MarketAPI frontWorld = frontWorldIn(factionId, e);
				if (frontWorld != null) {
					ButtonAPI supply = intel.addGenericButton(main, SMALL_BUTTON_W + 6f, "Supply",
							BUTTON_SUPPLY + factionId + ":" + frontWorld.getId());
					supply.getPosition().belowRight(hiveTable, -up)
							.setXAlignOffset(-6f - 2f * SMALL_BUTTON_W - 14f - 8f);
					ButtonAPI pull = intel.addGenericButton(main, SMALL_BUTTON_W + 12f, "Pull out",
							BUTTON_PULLOUT + factionId + ":" + frontWorld.getId());
					pull.getPosition().belowRight(hiveTable, -up)
							.setXAlignOffset(-6f - 3f * SMALL_BUTTON_W - 14f - 6f - 12f);
					disableWith(main, supply, mayOrder && baseOk && ThreatIncConfig.frontRunsEnabled(),
							blocked != null ? blocked : "No base in reach, or front runs are disabled.",
							"A convoy runs armaments from the nearest base to your front here.");
					disableWith(main, pull, mayOrder && baseOk && ThreatIncConfig.frontRunsEnabled(),
							blocked != null ? blocked : "No base in reach, or front runs are disabled.",
							"Your front here withdraws and its marines come home.");
				}
			}
		}
		main.setHeightSoFar(heightBefore);
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
	 * The totals row's colour for a commodity: red when some colony is short
	 * of it and the faction has nothing banked anywhere, bright when some
	 * colony is short, plain otherwise.
	 */
	protected static Color totalColor(List<ColonyRow> rows, String commodityId, Color plain) {
		boolean anyShort = false;
		float stock = 0f;
		for (ColonyRow r : rows) {
			ThreatReserves.CommodityStatus s = ThreatReserves.status(r.market, commodityId);
			if (s == null) continue;
			stock += s.stock;
			if (s.deficit > 0 || s.covering || s.exhausted) anyShort = true;
		}
		if (anyShort && stock <= 0f) return Misc.getNegativeHighlightColor();
		if (anyShort) return Misc.getHighlightColor();
		return plain;
	}

	// ------------------------------------------------------------------
	// data
	// ------------------------------------------------------------------

	protected static List<ColonyRow> colonyRows(List<MarketAPI> markets) {
		List<ColonyRow> rows = new ArrayList<ColonyRow>();
		for (MarketAPI m : markets) {
			ColonyRow r = new ColonyRow();
			r.market = m;
			r.military = IncursionManager.hasMilitary(m);
			r.staging = ThreatConvoys.nearestHiveInRange(m);
			if (r.staging != null && m.getStarSystem() != null) {
				r.stagingLY = Misc.getDistanceLY(m.getStarSystem().getLocation(),
						r.staging.getLocation());
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

	protected static String stockText(MarketAPI m, String commodityId) {
		float stock = ThreatReserves.stock(m.getId(), commodityId);
		float per30 = ThreatReserves.accrualPer30(m, commodityId);
		if (stock <= 0f && per30 <= 0f) return "-";
		return Misc.getWithDGS((int) stock);
	}

	/**
	 * Rule 3 on the board: a stock the depot is spending on the colony's own
	 * shortage reads bright; one too low to issue another unit while the
	 * shortage stands reads red (the row tooltip says why).
	 */
	protected static Color stockColor(MarketAPI m, String commodityId, Color text) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(m, commodityId);
		if (s == null) return text;
		if (s.exhausted) return Misc.getNegativeHighlightColor();
		if (s.covering || s.deficit > 0) return Misc.getHighlightColor();
		return text;
	}

	protected static void colonyTooltip(TooltipMakerAPI tooltip, ColonyRow r) {
		Color h = Misc.getHighlightColor();
		Color gray = Misc.getGrayColor();
		MarketAPI m = r.market;
		tooltip.addPara(m.getName() + " - size " + m.getSize() + ", "
				+ (r.military ? militaryLabel(m) : "no military structure") + ".", 0f);
		// rule 7 (docs/economy-coherence.md): vanilla's units beside the item
		// counts - surplus, availability and demand, the bank rate, and what the
		// depot is covering; the same lines the War footing condition shows
		for (String c : ThreatReserves.COMMODITIES) {
			WarFootingCondition.addCommodityLine(tooltip, m, c, 3f);
		}
		if (r.staging != null) {
			float[] wants = ThreatConvoys.stagingTargets(m);
			tooltip.addPara("Staging for " + r.staging.getNameWithNoType() + ", "
					+ (int) Math.ceil(r.stagingLY) + " ly. Stocks toward %s marines, %s armaments, "
					+ "%s fuel, %s supplies.", 10f, h,
					Misc.getWithDGS((int) wants[0]), Misc.getWithDGS((int) wants[1]),
					Misc.getWithDGS((int) wants[2]), Misc.getWithDGS((int) wants[3]));
		} else {
			tooltip.addPara(r.military ? "No hive in reach; supplies the bases that have one."
					: "Not a base; its reserves ship to the staging bases.", gray, 10f);
		}
		// what the Defend and Aid buttons would send is on the buttons themselves
		if (m.isPlayerOwned()) {
			tooltip.addPara(ThreatAidCapacity.describe(m), h, 10f);
		}
		tooltip.addPara("Click to show on the map.", gray, 10f);
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
			FleetRow f = new FleetRow();
			f.kind = "Task force";
			f.color = h;
			f.name = r.countLivingFleets() + (r.countLivingFleets() == 1 ? " fleet" : " fleets");
			f.task = r.getTargetColonyName() != null ? "attacking " + r.getTargetColonyName()
					: "standing down";
			f.status = r.isEngaging() ? "engaging" : "en route";
			f.strength = "-";
			float eta = r.etaDays();
			f.eta = eta > 0f ? "~" + (int) Math.ceil(eta) + " d" : "-";
			f.rowId = r;
			f.recallKey = "tf:" + i;
			rows.add(f);
		}
		// expeditions
		List<Object> purges = IncursionManager.getPurgeList();
		for (int i = 0; i < purges.size(); i++) {
			if (!(purges.get(i) instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI p = (ThreatPurgeFGI) purges.get(i);
			if (p.isEnded() || p.isEnding()) continue;
			if (p.getFaction() == null || !factionId.equals(p.getFaction().getId())) continue;
			FleetRow f = new FleetRow();
			f.kind = p.isPlayerCommissioned() ? "Commissioned" : "Expedition";
			f.color = pos;
			StarSystemAPI where = p.getParams() != null && p.getParams().raidParams != null
					? p.getParams().raidParams.where : null;
			f.name = p.getParams() != null ? p.getParams().fleetSizes.size() + " fleets" : "-";
			f.task = where != null ? "besieging the " + where.getNameWithLowercaseTypeShort() : "-";
			ThreatWarBoard.Op op = new ThreatWarBoard.Op();
			ThreatWarBoard.statusOf(p, op, false);
			f.status = op.status;
			f.eta = op.eta;
			f.strength = p.carriesCargo()
					? Misc.getWithDGS((int) p.getMarinesAllotted()) + " marines"
					: "abstract";
			f.rowId = p;
			f.recallKey = "purge:" + i;
			rows.add(f);
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
		// outposts (standing stations; Recall = decommission)
		List<ThreatOutposts.Outpost> outposts = ThreatOutposts.all();
		for (int i = 0; i < outposts.size(); i++) {
			ThreatOutposts.Outpost o = outposts.get(i);
			if (!factionId.equals(o.factionId)) continue;
			FleetRow f = new FleetRow();
			f.kind = "Outpost";
			f.color = o.alive() ? pos : neg;
			f.name = o.specId != null ? o.specId.replace('_', ' ') : "station";
			f.task = "holding " + o.planetName();
			f.status = o.alive() ? "standing" : "destroyed";
			f.strength = o.fleet != null ? (int) o.fleet.getFleetPoints() + " FP" : "-";
			f.eta = "-";
			f.rowId = o.entity;
			f.recallKey = "outpost:" + i;
			rows.add(f);
		}
		// standing orders
		List<ThreatFleetOrders.Order> orders = ThreatFleetOrders.all();
		for (int i = 0; i < orders.size(); i++) {
			ThreatFleetOrders.Order o = orders.get(i);
			if (!factionId.equals(o.factionId)) continue;
			FleetRow f = new FleetRow();
			f.kind = (o.aid ? "Aid " : "") + (ThreatFleetOrders.KIND_GUARD.equals(o.kind)
					? (o.aid ? "guard" : "Guard") : (o.aid ? "intercept" : "Intercept"));
			f.color = o.fleet != null && o.fleet.isAlive() ? pos : neg;
			f.name = o.fleet != null ? o.fleet.getName() : "-";
			f.task = o.task();
			f.status = o.fleet != null && o.fleet.isAlive()
					? (o.aid && !o.arrived ? "en route" : "on station") : "lost";
			f.strength = o.fleet != null ? (int) o.fleet.getFleetPoints() + " FP" : "-";
			f.eta = (int) Math.ceil(o.daysLeft()) + " d left";
			f.rowId = o.fleet;
			f.recallKey = "order:" + i;
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
				f.name = o.fleet != null ? o.fleet.getName() : "-";
				f.task = o.task();
				f.status = o.fleet != null && o.fleet.isAlive()
						? (o.arrived ? "on station" : "en route") : "lost";
				f.strength = o.fleet != null ? (int) o.fleet.getFleetPoints() + " FP" : "-";
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

	protected static String cargoText(float marines, float armaments, float fuel, float supplies) {
		List<String> parts = new ArrayList<String>();
		if (marines > 0f) parts.add((int) marines + " marines");
		if (armaments > 0f) parts.add((int) armaments + " arms");
		if (fuel > 0f) parts.add((int) fuel + " fuel");
		if (supplies > 0f) parts.add((int) supplies + " supplies");
		return parts.isEmpty() ? "empty" : ThreatWarBoard.join(parts);
	}

	/** Known infested colony systems within expedition reach of any of the faction's military worlds. */
	protected static List<ThreatWarBoard.Entry> hivesInReach(List<MarketAPI> markets) {
		List<ThreatWarBoard.Entry> result = new ArrayList<ThreatWarBoard.Entry>();
		for (ThreatWarBoard.Entry e : ThreatWarBoard.buildEntries()) {
			if (!e.known || e.system == null) continue;
			if (nearestBase(markets, e.system) != null) result.add(e);
		}
		return result;
	}

	protected static MarketAPI nearestBase(List<MarketAPI> markets, StarSystemAPI system) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : markets) {
			if (m.getStarSystem() == null || !IncursionManager.hasMilitary(m)) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), system.getLocation());
			if (d > IncursionManager.expeditionRangeLY(m) || d >= bestDist) continue;
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
				|| id.startsWith(BUTTON_RECALL) || id.startsWith(BUTTON_SUPPLY)
				|| id.startsWith(BUTTON_PULLOUT) || id.startsWith(BUTTON_OUTPOST)
				|| id.startsWith(BUTTON_AID_DEFEND) || id.startsWith(BUTTON_AID_SUPPLY)
				|| id.startsWith(BUTTON_AID_STRIKE) || id.startsWith(BUTTON_MOBILISE)
				|| id.startsWith(BUTTON_STAND_DOWN);
	}

	/** Splits "prefix" + "factionId:target" into [prefix, factionId, target]. */
	protected static String[] parse(String id) {
		String[] prefixes = {BUTTON_GUARD, BUTTON_STAGE, BUTTON_INTERCEPT, BUTTON_SIEGE, BUTTON_RECALL,
				BUTTON_SUPPLY, BUTTON_PULLOUT, BUTTON_OUTPOST, BUTTON_AID_DEFEND, BUTTON_AID_SUPPLY,
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
	public static void addOrderPrompt(TooltipMakerAPI prompt, String id) {
		String[] parts = parse(id);
		if (parts == null) return;
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
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			MarketAPI base = target != null
					? ThreatAid.pickTaskForceSource(target.getLocationInHyperspace()) : null;
			prompt.addPara("Order a task force of about %s fleet points from "
					+ (base != null ? base.getName() : "the nearest base") + " to take the orbit of "
					+ (target != null ? target.getName() : "the world") + " for %s days? Fuel and "
					+ "supplies come from its reserve.", 0f, h,
					"" + (int) (base != null ? ThreatAid.taskForceFP(base) : ThreatIncConfig.guardFleetFP()),
					"" + (int) ThreatIncConfig.guardDays());
		} else if (BUTTON_STAGE.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			prompt.addPara("Order a convoy to " + (target != null ? target.getName() : "the world")
					+ " from whichever of " + who + " colonies can best spare it? Up to %s "
					+ "marines and %s units of cargo; it can be intercepted on the way.", 0f, h,
					"" + (int) ThreatIncConfig.convoyMarineCapacity(),
					"" + (int) ThreatIncConfig.convoyCargoCapacity());
		} else if (BUTTON_INTERCEPT.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = system != null ? ThreatAid.pickTaskForceSource(system.getLocation()) : null;
			prompt.addPara("Order a task force of about %s fleet points from "
					+ (base != null ? base.getName() : "the nearest base") + " to hold the jump-point "
					+ "of the " + (system != null ? system.getNameWithLowercaseType() : "hive system")
					+ " for %s days? Fuel and supplies come from its reserve.", 0f, h,
					"" + (int) (base != null ? ThreatAid.taskForceFP(base) : ThreatIncConfig.guardFleetFP()),
					"" + (int) ThreatIncConfig.interceptDays());
		} else if (BUTTON_SIEGE.equals(parts[0])) {
			StarSystemAPI system = ThreatWarBoard.getSystem(parts[2]);
			MarketAPI base = null;
			if (faction != null && system != null) {
				base = nearestBase(ThreatReserves.marketsOf(faction.getId()), system);
			}
			if (base != null && system != null) {
				float[] wants = IncursionManager.stagingWants(base, system);
				float have = ThreatReserves.stock(base.getId(), Commodities.MARINES);
				prompt.addPara("Order a %s purge expedition from " + base.getName() + " against the "
						+ system.getNameWithLowercaseType() + "? The landing wants %s marines; the "
						+ "base holds %s. It draws its troops, armaments, fuel and supplies from "
						+ "that reserve and campaigns for the Fabrication Core on its own.", 0f, h,
						who, Misc.getWithDGS((int) wants[0]), Misc.getWithDGS((int) have));
			} else {
				prompt.addPara("No base of " + who + " is in reach of that system.", 0f);
			}
		} else if (BUTTON_RECALL.equals(parts[0])) {
			prompt.addPara("Recall this fleet to its base? A convoy brings its cargo home; an "
					+ "expedition abandons its campaign; a task force breaks off.", 0f);
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
						"" + (int) q.fp, "" + (int) ThreatIncConfig.guardDays());
			}
		} else if (BUTTON_AID_SUPPLY.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			ThreatAid.Quote q = ThreatAid.quoteResupply(target);
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
						"" + (int) q.fp, "" + (int) ThreatIncConfig.interceptDays());
			}
		} else if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			ThreatGroundFronts.GroundFront front = hive != null
					? ThreatGroundFronts.getFront(hive.getId()) : null;
			MarketAPI base = faction != null && hive != null
					? ThreatFleetOrders.pickBase(faction, hive.getLocationInHyperspace()) : null;
			if (hive == null || front == null) {
				prompt.addPara("There is no front there any more.", 0f);
			} else if (BUTTON_SUPPLY.equals(parts[0])) {
				float[] wants = ThreatConvoys.frontWants(front, hive);
				prompt.addPara("Send a supply run from " + (base != null ? base.getName()
						: "the nearest base") + " to the front on " + hive.getName() + "? It "
						+ "wants about %s marines and %s heavy armaments; the run waits at the "
						+ "jump-point while Defense Swarms hold the orbit, and can be "
						+ "intercepted on the way.", 0f, h, Misc.getWithDGS((int) wants[0]),
						Misc.getWithDGS((int) wants[1]));
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
	public static String executeOrder(String id) {
		String[] parts = parse(id);
		if (parts == null) return null;
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
					random) ? "aid-supply" : null;
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
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			return ThreatFleetOrders.dispatchGuard(faction, target) != null ? "guard" : null;
		}
		if (BUTTON_STAGE.equals(parts[0])) {
			MarketAPI target = Global.getSector().getEconomy().getMarket(parts[2]);
			if (target == null) return null;
			ThreatConvoys.Convoy c = ThreatConvoys.stageTo(target, faction, random);
			if (c == null) {
				ThreatColonyManager.announceAlways("No colony of "
						+ (faction.isPlayerFaction() ? "yours" : faction.getDisplayName())
						+ " within convoy range can spare materiel for " + target.getName()
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
			int difficulty = IncursionManager.computeSiegeDifficulty(targets,
					IncursionManager.anyTargetGarrisoned(targets));
			List<Integer> sizes = IncursionManager.siegeFleetSizes(difficulty,
					IncursionManager.anyTargetGarrisoned(targets), false, targets);
			ThreatPurgeFGI purge = IncursionManager.launchSiegeExpedition(base, faction, system,
					targets, sizes, faction.isPlayerFaction(), random);
			if (purge == null) {
				float[] wants = IncursionManager.stagingWants(base, system);
				ThreatColonyManager.announceAlways("No expedition could be raised at "
						+ base.getName() + ": its reserve holds "
						+ (int) ThreatReserves.stock(base.getId(), Commodities.MARINES)
						+ " marines against the " + (int) wants[0] + " the landing needs. Stage "
						+ "more there first.", Misc.getNegativeHighlightColor());
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
		if (BUTTON_SUPPLY.equals(parts[0]) || BUTTON_PULLOUT.equals(parts[0])) {
			MarketAPI hive = ThreatIncData.resolveColonyMarket(parts[2]);
			if (hive == null) return null;
			boolean pull = BUTTON_PULLOUT.equals(parts[0]);
			ThreatConvoys.Convoy c = pull ? ThreatConvoys.pullOutFront(hive, faction, random)
					: ThreatConvoys.supplyFront(hive, faction, random);
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

	/** The faction's own front on any hive world of this system, or null. */
	public static MarketAPI frontWorldIn(String factionId, ThreatWarBoard.Entry e) {
		for (MarketAPI m : e.markets) {
			ThreatGroundFronts.GroundFront f = ThreatGroundFronts.getFront(m.getId());
			if (f == null) continue;
			String owner = f.factionId != null ? f.factionId
					: com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER;
			if (factionId.equals(owner)) return m;
		}
		return null;
	}

	protected static boolean recall(String factionId, String key) {
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
		if ("outpost".equals(kind)) {
			List<ThreatOutposts.Outpost> list = ThreatOutposts.all();
			if (index < 0 || index >= list.size()) return false;
			ThreatOutposts.Outpost o = list.get(index);
			if (!factionId.equals(o.factionId)) return false;
			ThreatOutposts.remove(o, "decommissioned by order");
			return true;
		}
		return false;
	}

	/** Unused-import guard for CampaignFleetAPI/UIComponentAPI in older compilers. */
	@SuppressWarnings("unused")
	private static void keep(CampaignFleetAPI f, UIComponentAPI c) {
	}
}
