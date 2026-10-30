package threatinc;

import java.util.LinkedHashSet;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The sector-wide incursion status readout: current phase, infested systems and
 * their stages, and what the player can do about it.
 */
public class ThreatIncursionIntel extends BaseIntelPlugin {

	public static final String KEY = "$threatinc_intel";

	/** Custom intel tag: gives the incursion its own tab in the intel screen. */
	public static final String TAG_THREAT = "Abyssal War";

	/** The system whose drill-down the war board shows; null = the top-ranked one. */
	protected String selectedSystemId;

	public String getSelectedSystemId() {
		return selectedSystemId;
	}

	public void setSelectedSystemId(String systemId) {
		selectedSystemId = systemId;
	}

	/** The board's faction view (docs/strategy-layer.md); null or "threat" = the hive view. */
	protected String selectedFactionId;

	public String getSelectedFactionId() {
		return selectedFactionId;
	}

	public void setSelectedFactionId(String factionId) {
		selectedFactionId = factionId;
	}

	/** The siege-force tier the board's Siege buttons commit: 0 min (the need), 1 med, 2 max (docs/player-aid.md). */
	protected int siegeTier;

	/** The Attack's Fuel ladder (phase D): 0 the passage, 1 half the fuel above the floor, 2 all. */
	protected int fuelTier;

	/** The Attack's Marines ladder: 0 none, 1 half the pool, 2 all. */
	protected int marineTier;

	/** Until 2026-10-28 the fronts table's Supply run ladder; kept so older saves load, read by nothing. */
	protected int supplyTier;

	/** Until 2026-10-28 the colonies table's Amount ladder; kept so older saves load, read by nothing. */
	protected int convoyTier;

	/**
	 * THE LEVERS (the user, 2026-10-28: "two sets of the 3 buttons, one for marines one for arms.
	 * None Med and Max, sending 0 half and all respectively ... then the same for the colony list
	 * underneath. one for fuel, one for supplies, one for marines, one for arms" and Hulls): one
	 * per good a hand order can move, each None (0) / Med (half the source's spare) / Max (all of
	 * it), ThreatConvoys.share. The fronts table's Marines and Arms size its Reinforce, Aid and
	 * Supply runs and, with its Fleet lever (2026-10-29), what a Request from a sieged world calls
	 * each faction for, the Fleet lever doubling as Support / Defend's outcome tier; the colonies
	 * table's five size its Transfer convoys and the Deploy (ThreatDeploy: Hulls the fleet, the
	 * others what it carries). An NPC colony's Defend aid takes no lever.
	 * Indexed by the LEVER_ constants; null in a save from before the levers (then {@link #lever}
	 * seeds the defaults: the fronts' Max, the colonies' Med).
	 */
	protected int[] levers;

	/** The Domain entry the Requests toggles read (its reserve key; ThreatFactionView.requestTarget), set by the Colony button (2026-10-31). */
	protected String requestTarget;

	public String requestTarget() {
		return requestTarget;
	}

	public static final int LEVER_FRONT_MARINES = 0, LEVER_FRONT_ARMS = 1, LEVER_COLONY_MARINES = 2,
			LEVER_COLONY_ARMS = 3, LEVER_COLONY_FUEL = 4, LEVER_COLONY_SUPPLIES = 5, LEVER_COLONY_HULLS = 6,
			LEVER_FRONT_FLEET = 7, LEVER_DOMAIN_FLEET = 8;
	protected static final int LEVER_COUNT = 9;
	/** The fronts' Max, the colonies' Med, the fronts' Fleet (2026-10-29, a Request's task force) Max, the Domain's Fleet (2026-10-31: Transfer deploys a fleet with it set) None. */
	protected static final int[] LEVER_DEFAULTS = { 2, 2, 1, 1, 1, 1, 1, 2, 0 };

	public int lever(int which) {
		if (levers == null || levers.length < LEVER_COUNT) {
			// a save from before a lever keeps the ones it has; the new one takes its default
			int[] fresh = LEVER_DEFAULTS.clone();
			if (levers != null) System.arraycopy(levers, 0, fresh, 0, Math.min(levers.length, fresh.length));
			levers = fresh;
		}
		return which >= 0 && which < LEVER_COUNT ? levers[which] : 0;
	}

	public void setLever(int which, int tier) {
		lever(which);
		if (which >= 0 && which < LEVER_COUNT) levers[which] = Math.max(0, Math.min(2, tier));
	}

	/**
	 * The fronts table's levers as {marines, arms, fleet}: Supply's marines and arms are the colonies
	 * table's levers (2026-10-29 evening - the fronts' own two went, "remove resource selectors from
	 * ground fronts table"), the Fleet lever Support / Defend's outcome tier.
	 */
	public int[] frontLevers() {
		return new int[] { lever(LEVER_COLONY_MARINES), lever(LEVER_COLONY_ARMS), lever(LEVER_FRONT_FLEET) };
	}

	/**
	 * The colonies table's levers in ThreatReserves.AID_COMMODITIES order - marines, arms, fuel, supplies,
	 * hulls - then the Fleet lever (2026-10-31: None, Transfer sends a convoy; Med / Max, Transfer deploys a
	 * fleet of half / all the source's free hulls carrying the goods, ThreatDeploy).
	 */
	public int[] colonyLevers() {
		return new int[] { lever(LEVER_COLONY_MARINES), lever(LEVER_COLONY_ARMS), lever(LEVER_COLONY_FUEL),
				lever(LEVER_COLONY_SUPPLIES), lever(LEVER_COLONY_HULLS), lever(LEVER_DOMAIN_FLEET) };
	}

	public int getSiegeTier() {
		return siegeTier;
	}

	public void setSiegeTier(int tier) {
		siegeTier = tier;
	}

	public int getFuelTier() {
		return fuelTier;
	}

	public int getMarineTier() {
		return marineTier;
	}

	/** The tier index a ladder button's id carries, or 0 if it carries nothing readable. */
	protected static int tierOf(String id, String prefix) {
		try {
			return Integer.parseInt(id.substring(prefix.length()));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static ThreatIncursionIntel get() {
		return (ThreatIncursionIntel) Global.getSector().getMemoryWithoutUpdate().get(KEY);
	}

	public static void ensureAdded() {
		if (get() != null) return;
		if (isEradicated()) return; // the war is over; nothing to report on
		ThreatIncursionIntel intel = new ThreatIncursionIntel();
		Global.getSector().getMemoryWithoutUpdate().set(KEY, intel);
		Global.getSector().getIntelManager().addIntel(intel);
	}

	/**
	 * The incursion is over: no infested system remains, no seeding swarm is in
	 * transit, and no expedition is still in flight. The board is permanent
	 * until then - a standing major event for as long as the swarm holds
	 * anything in the sector.
	 */
	public static boolean isEradicated() {
		if (!ThreatIncData.isStarted()) return false;
		if (ThreatIncData.countInfested() > 0) return false;
		if (ThreatColonyManager.anyWaveInFlight()) return false;
		if (IncursionManager.countActiveStrikes() > 0) return false;
		return true;
	}

	/** Retires the board once the swarm is gone; called from the manager's intel sweep. */
	public static void checkEradicated() {
		ThreatIncursionIntel intel = get();
		if (intel == null || intel.isEnding() || intel.isEnded()) return;
		if (!isEradicated()) return;
		intel.endAfterDelay();
		Global.getSector().getMemoryWithoutUpdate().unset(KEY);
		ThreatIncConfig.log("Threat eradicated - retiring the war board.");
	}

	/** Off the intel list until the player knows of at least one hive system. */
	@Override
	public boolean isHidden() {
		return !ThreatIncConfig.debugMode() && knownInfestedSystemIds().isEmpty();
	}

	@Override
	public String getName() {
		return "The Abyssal War";
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	/**
	 * A major event, and only that: the war board lives beside the colony
	 * crises, not in the per-system Abyssal War tab it summarizes.
	 */
	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MAJOR_EVENT);
		return tags;
	}

	/**
	 * The systems the player actually knows are infested: visited in person,
	 * named in a contract, or found by any faction's scouts. This intel deliberately shows
	 * NOTHING else - not the full spread, not colony sizes, not the swarm's
	 * economic state. The player is not meant to be able to gauge the true extent
	 * or strength of the incursion from here.
	 */
	protected static java.util.List<String> knownInfestedSystemIds() {
		java.util.List<String> result = new java.util.ArrayList<String>();
		for (String systemId : ThreatIncData.stages().keySet()) {
			if (ThreatIncData.discoveredSystems().contains(systemId)) result.add(systemId);
		}
		return result;
	}

	protected static StarSystemAPI getSystemById(String systemId) {
		for (StarSystemAPI curr : Global.getSector().getStarSystems()) {
			if (curr.getId().equals(systemId)) return curr;
		}
		return null;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		// the row is the title and nothing else, in vanilla's own list font
		// (BaseEventIntel.createIntelInfo does exactly this), so the entry reads
		// at the same size as Colony Crises beside it
		info.setParaSmallInsignia();
		info.addPara(getName(), getTitleColor(mode), 0f);
		info.setParaFontDefault();
	}

	// ------------------------------------------------------------------
	// The Abyssal War - the full-width board (see ThreatWarBoard)
	// ------------------------------------------------------------------

	@Override
	public boolean hasSmallDescription() {
		return false;
	}

	@Override
	public boolean hasLargeDescription() {
		return true;
	}

	@Override
	public void createLargeDescription(com.fs.starfarer.api.ui.CustomPanelAPI panel,
			float width, float height) {
		TooltipMakerAPI main = panel.createUIElement(width, height, true);
		try {
			ThreatWarBoard.render(this, main, width - 40f);
		} catch (Throwable t) {
			Global.getLogger(ThreatIncursionIntel.class).error("War board failed to render", t);
			main.addPara("The war board failed to render: " + t, Misc.getNegativeHighlightColor(),
					10f);
			createSmallDescription(main, width, height);
		}
		panel.addUIElement(main).inTL(0f, 0f);
		boardMaker = main;
		if (keepScroll != null && main.getExternalScroller() != null) {
			main.getExternalScroller().setYOffset(keepScroll);
		}
		keepScroll = null;
	}

	/** The board's last scroll maker, for {@link #rerenderInPlace}. */
	protected transient TooltipMakerAPI boardMaker;
	/** The scroll offset the next render restores, or null for the top. */
	protected transient Float keepScroll;

	/**
	 * Re-renders the board where the player is reading it: a view toggle or an
	 * order rebuilt the panel at the top (the user, 2026-10-24: "when i click
	 * min med or max the page jumps to the top").
	 */
	protected void rerenderInPlace(com.fs.starfarer.api.ui.IntelUIAPI ui) {
		if (boardMaker != null && boardMaker.getExternalScroller() != null) {
			keepScroll = boardMaker.getExternalScroller().getYOffset();
		}
		ui.updateUIForItem(this);
	}

	/**
	 * Ledger rows carry a system id: clicking one selects it for the drill-down.
	 * Operations rows carry the intel they describe: clicking one shows that
	 * operation on the map (selecting it in the list is a no-op while the list
	 * is filtered to major events, which it is whenever this board is open).
	 */
	@Override
	public void tableRowClicked(com.fs.starfarer.api.ui.IntelUIAPI ui, TableRowClickData data) {
		if (data == null) return;
		if (data.rowId instanceof com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) {
			com.fs.starfarer.api.campaign.comm.IntelInfoPlugin plugin =
					(com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) data.rowId;
			com.fs.starfarer.api.campaign.SectorEntityToken where = null;
			try {
				where = plugin.getMapLocation(null);
			} catch (Throwable t) {
				// an intel that needs the map to answer; fall through
			}
			if (where != null) {
				ui.showOnMap(where);
			} else {
				ui.selectItem(plugin);
			}
			return;
		}
		// faction-view fleet rows carry the fleet itself: show it on the map
		if (data.rowId instanceof com.fs.starfarer.api.campaign.SectorEntityToken) {
			ui.showOnMap((com.fs.starfarer.api.campaign.SectorEntityToken) data.rowId);
			return;
		}
		if (data.rowId instanceof String) {
			String rowId = (String) data.rowId;
			// colony rows (faction view, fronts table): vanilla's colony screen
			// for the world, as the cards' Colony button - the map is a click
			// away from there
			if (rowId.startsWith(ThreatFactionView.ROW_MARKET)) {
				com.fs.starfarer.api.campaign.econ.MarketAPI market = Global.getSector().getEconomy()
						.getMarket(rowId.substring(ThreatFactionView.ROW_MARKET.length()));
				if (market != null && market.getPrimaryEntity() != null) {
					ui.showDialog(market.getPrimaryEntity(),
							new ThreatColonyScreenDialog(market.getPrimaryEntity()));
				}
				return;
			}
			selectedSystemId = rowId;
			// a hive row clicked from the faction view returns to the hive view
			if (selectedFactionId != null
					&& !ThreatFactionView.VIEW_THREAT.equals(selectedFactionId)) {
				selectedFactionId = ThreatFactionView.VIEW_THREAT;
			}
			ui.updateUIForItem(this);
		}
	}

	@Override
	public boolean doesButtonHaveConfirmDialog(Object buttonId) {
		if (buttonId instanceof String && ThreatFactionView.isOrderButton((String) buttonId)) {
			return true;
		}
		return super.doesButtonHaveConfirmDialog(buttonId);
	}

	@Override
	public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
		if (buttonId instanceof String && ThreatFactionView.isOrderButton((String) buttonId)) {
			ThreatFactionView.addOrderPrompt(prompt, (String) buttonId, this);
			return;
		}
		super.createConfirmationPrompt(buttonId, prompt);
	}

	@Override
	public void buttonPressConfirmed(Object buttonId, com.fs.starfarer.api.ui.IntelUIAPI ui) {
		if (!(buttonId instanceof String)) {
			super.buttonPressConfirmed(buttonId, ui);
			return;
		}
		String id = (String) buttonId;
		if (id.startsWith(ThreatFactionView.BUTTON_FACTION)) {
			selectedFactionId = id.substring(ThreatFactionView.BUTTON_FACTION.length());
			ui.updateUIForItem(this);
			return;
		}
		// the ladders are view toggles like the faction selector: set the tier,
		// re-render, no Confirm (doesButtonHaveConfirmDialog leaves it to the default)
		if (id.startsWith(ThreatFactionView.BUTTON_SIEGE_TIER)) {
			siegeTier = tierOf(id, ThreatFactionView.BUTTON_SIEGE_TIER);
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatFactionView.BUTTON_FUEL_TIER)) {
			fuelTier = tierOf(id, ThreatFactionView.BUTTON_FUEL_TIER);
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatFactionView.BUTTON_MARINE_TIER)) {
			marineTier = tierOf(id, ThreatFactionView.BUTTON_MARINE_TIER);
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatFactionView.BUTTON_LEVER)) {
			// "lever:<which>:<tier>"
			String[] p = id.substring(ThreatFactionView.BUTTON_LEVER.length()).split(":");
			try {
				if (p.length == 2) setLever(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
			} catch (NumberFormatException e) {
				// a malformed id sets nothing
			}
			rerenderInPlace(ui);
			return;
		}
		// THE REQUESTS TOGGLES (2026-10-31): the Colony button sets the entry, a good's button flips its call,
		// Reject clears an offer - none with a Confirm, each a re-render in place
		if (id.startsWith(ThreatFactionView.BUTTON_REQ_TARGET)) {
			// the calls are the one entry's: moving on withdraws them all (the user, 2026-10-31)
			ThreatAidRequests.clearCalls();
			requestTarget = id.substring(ThreatFactionView.BUTTON_REQ_TARGET.length());
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatFactionView.BUTTON_REQ_TOGGLE)) {
			String rest = id.substring(ThreatFactionView.BUTTON_REQ_TOGGLE.length());
			int colon = rest.indexOf(':');
			if (colon > 0) {
				ThreatBases.Base target = ThreatAidRequests.resolveTarget(rest.substring(colon + 1));
				boolean on = ThreatAidRequests.toggle(target, rest.substring(0, colon));
				ThreatIncConfig.log("Board call " + rest + " -> " + (on ? "on" : "off"));
			}
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatFactionView.BUTTON_REQ_REJECT)) {
			String offerId = id.substring(ThreatFactionView.BUTTON_REQ_REJECT.length());
			boolean done = ThreatAidRequests.reject(ThreatAidRequests.offerById(offerId));
			ThreatIncConfig.log("Board offer rejected " + offerId + " -> " + done);
			rerenderInPlace(ui);
			return;
		}
		if (ThreatFactionView.isOrderButton(id)) {
			String result = ThreatFactionView.executeOrder(id, this);
			ThreatIncConfig.log("Board order " + id + " -> " + result);
			rerenderInPlace(ui);
			return;
		}
		if (id.startsWith(ThreatWarBoard.BUTTON_COLONY)) {
			// vanilla's colony screen for the world, closing back to the board
			com.fs.starfarer.api.campaign.econ.MarketAPI market = ThreatIncData.resolveColonyMarket(
					id.substring(ThreatWarBoard.BUTTON_COLONY.length()));
			if (market != null && market.getPrimaryEntity() != null) {
				ui.showDialog(market.getPrimaryEntity(),
						new ThreatColonyScreenDialog(market.getPrimaryEntity()));
			}
			return;
		}
		if (id.startsWith(ThreatWarBoard.BUTTON_MAP)) {
			// a colony market id (the Map button on a card) or a system id
			String target = id.substring(ThreatWarBoard.BUTTON_MAP.length());
			com.fs.starfarer.api.campaign.econ.MarketAPI market =
					ThreatIncData.resolveColonyMarket(target);
			if (market != null && market.getPrimaryEntity() != null) {
				ui.showOnMap(market.getPrimaryEntity());
				return;
			}
			StarSystemAPI system = ThreatWarBoard.getSystem(target);
			if (system != null && system.getHyperspaceAnchor() != null) {
				ui.showOnMap(system.getHyperspaceAnchor());
			}
			return;
		}
		super.buttonPressConfirmed(buttonId, ui);
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		info.addPara("The Threat - the ancient fabricator swarms of the Abyss - has come to the "
				+ "sector. Where its swarm takes root, a machine colony rises that "
				+ "cannot be reasoned with or occupied, only burned away.", opad);

		java.util.List<String> known = knownInfestedSystemIds();
		if (known.isEmpty()) {
			info.addPara("You have confirmed no Threat infestations. Where the swarm is - and "
					+ "how far it has spread - is unknown.", opad, neg, "unknown");
		} else {
			info.addPara("Known infested systems:", opad);
			for (String systemId : known) {
				StarSystemAPI system = getSystemById(systemId);
				String name = system != null
						? system.getNameWithLowercaseTypeShort() : systemId;
				info.addPara(BULLET + name, 3f, neg, name);
			}
			info.addPara("Only what you have found is listed here. The full reach of the "
					+ "war is unknown.", opad, h, "unknown");
		}

		int cleansed = ThreatIncData.getCleansedCount();
		if (cleansed > 0) {
			info.addPara("Threat colonies you have burned from the sector: %s.", opad,
					ThreatNotice.goodColor(), "" + cleansed);
		}

		info.addPara("Counterplay: defeat the Defense Swarms orbiting a colony, then saturation "
				+ "bombardment burns it down - each pass shrinks it, and small colonies are "
				+ "destroyed outright. The hive is one economy: its supply convoys and colonies "
				+ "are all targets, and every world it loses starves the rest.", opad,
				ThreatNotice.goodColor(), "saturation bombardment");
	}
}
