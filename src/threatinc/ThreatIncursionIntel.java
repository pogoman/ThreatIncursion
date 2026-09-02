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
	public static final String TAG_THREAT = "Threat Incursion";

	/** The system whose drill-down the war board shows; null = the top-ranked one. */
	protected String selectedSystemId;

	public String getSelectedSystemId() {
		return selectedSystemId;
	}

	public void setSelectedSystemId(String systemId) {
		selectedSystemId = systemId;
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

	@Override
	public String getName() {
		return "The Threat War Effort";
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	/**
	 * A major event, and only that: the war board lives beside the colony
	 * crises, not in the per-system Threat Incursion tab it summarizes.
	 */
	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MAJOR_EVENT);
		return tags;
	}

	/**
	 * The systems the player actually knows are infested: visited in person,
	 * named in a contract, or the origin of a strike. This intel deliberately shows
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
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		info.addPara("Known infested systems: %s", 3f, t, h,
				"" + knownInfestedSystemIds().size());
	}

	// ------------------------------------------------------------------
	// The Threat War Effort - the full-width board (see ThreatWarBoard)
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
		if (data.rowId instanceof String) {
			selectedSystemId = (String) data.rowId;
			ui.updateUIForItem(this);
		}
	}

	@Override
	public boolean doesButtonHaveConfirmDialog(Object buttonId) {
		if (buttonId instanceof String
				&& ((String) buttonId).startsWith(ThreatWarBoard.BUTTON_COMMISSION)) return true;
		return super.doesButtonHaveConfirmDialog(buttonId);
	}

	@Override
	public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
		if (buttonId instanceof String
				&& ((String) buttonId).startsWith(ThreatWarBoard.BUTTON_COMMISSION)) {
			InfestedSystemIntel.addCommissionPrompt(prompt,
					((String) buttonId).substring(ThreatWarBoard.BUTTON_COMMISSION.length()));
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
		if (id.startsWith(ThreatWarBoard.BUTTON_MISSION)) {
			// show just this system's missions in the list and select the first: selecting an
			// item hidden by the current tab filter is a no-op, a custom subset is not
			java.util.List<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin> missions =
					ThreatWarBoard.missionsForSystem(id.substring(ThreatWarBoard.BUTTON_MISSION.length()));
			if (!missions.isEmpty()) {
				ui.updateIntelList(false, missions);
				ui.selectItem(missions.get(0));
			}
			return;
		}
		if (id.startsWith(ThreatWarBoard.BUTTON_COMMISSION)) {
			InfestedSystemIntel.commissionExpedition(
					id.substring(ThreatWarBoard.BUTTON_COMMISSION.length()));
			ui.updateUIForItem(this);
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
				+ "sector. Where its fabrication strata take root, a machine colony rises that "
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
					+ "incursion is unknown.", opad, h, "unknown");
		}

		int cleansed = ThreatIncData.getCleansedCount();
		if (cleansed > 0) {
			info.addPara("Threat colonies you have burned from the sector: %s.", opad,
					Misc.getPositiveHighlightColor(), "" + cleansed);
		}

		info.addPara("Counterplay: defeat the Defense Swarms orbiting a colony, then saturation "
				+ "bombardment burns it down - each pass shrinks it, and small colonies are "
				+ "destroyed outright. The hive is one economy: its supply convoys and colonies "
				+ "are all targets, and every world it loses starves the rest.", opad,
				Misc.getPositiveHighlightColor(), "saturation bombardment");
	}
}
