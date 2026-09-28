package threatinc;

import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI.SurveyLevel;
import com.fs.starfarer.api.campaign.listeners.CoreUITabListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Keeps hives the player has not found off vanilla's map and intel screens.
 *
 * <p>Vanilla's map reads markets, not {@link ThreatIncData#discoveredSystems}:
 * a hive is a real, unhidden market, so from the day it was founded the galaxy
 * map listed it under the system's Markets, counted its planet fully surveyed,
 * and coloured and named it in the system view. Hiding it for real
 * ({@code setHidden}, as vanilla hides pirate bases) would cut its imports.
 *
 * <p>So the veil goes on only while a core tab is open - the game is paused
 * and the economy is not stepping: every Threat market in an unfound system is
 * hidden, turned back into a condition-only planet at the survey level the
 * player had before the hive, and its planet made neutral. It comes off the
 * frame the tab closes. The mod's own reads go through {@link #hidden} and
 * {@link #conditionOnly}, which see through it.
 *
 * <p>The frame a tab closes, vanilla's economy advances before any script can
 * lift the veil; a hidden market takes no imports in an economy step that
 * lands on that one frame. The survey level to restore is kept in the
 * market's memory, so a save taken veiled still unveils on load.
 */
public class ThreatMapFog implements EveryFrameScript, CoreUITabListener {

	/** On a veiled market: the survey level to put back. */
	public static final String KEY_VEIL = "$threatinc_veilSurvey";
	/** Set when the hive is founded: the planet's survey level before it. */
	public static final String KEY_PRIOR_SURVEY = "$threatinc_priorSurvey";

	protected static final Set<String> veiled = new HashSet<String>();
	protected static boolean active = false;

	/** market.isHidden() as the war reads it: the veil is not a hiding. */
	public static boolean hidden(MarketAPI market) {
		return market.isHidden() && !veiled.contains(market.getId());
	}

	/** market.isPlanetConditionMarketOnly() as the war reads it. */
	public static boolean conditionOnly(MarketAPI market) {
		return market.isPlanetConditionMarketOnly() && !veiled.contains(market.getId());
	}

	/** On load, before anything reads a market: lifts a veil a save was taken under. */
	public static void onGameLoad() {
		veiled.clear();
		active = false;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getMemoryWithoutUpdate().contains(KEY_VEIL)) unveil(market);
		}
	}

	public void reportAboutToOpenCoreTab(CoreUITabId tab, Object param) {
		veil();
	}

	public void advance(float amount) {
		boolean open = Global.getSector().getCampaignUI().getCurrentCoreTab() != null;
		if (open && !active) veil();
		if (!open && active) unveilAll();
	}

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return true;
	}

	protected static void veil() {
		if (active) return;
		active = true;
		// debug mode shows the player everything
		if (ThreatIncConfig.debugMode()) return;
		SectorEntityToken player = Global.getSector().getPlayerFleet();
		for (String systemId : ThreatIncData.colonyMarkets().keySet()) {
			if (ThreatIncData.discoveredSystems().contains(systemId)) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
			if (system == null) continue;
			// the player is there: found on the next tick, and a dialog may be open on the hive
			if (player != null && player.getContainingLocation() == system) continue;
			for (MarketAPI market : Global.getSector().getEconomy().getMarkets(system)) {
				if (!Factions.THREAT.equals(market.getFactionId())) continue;
				if (market.isHidden() || market.isPlanetConditionMarketOnly()) continue;
				veil(market);
			}
		}
	}

	protected static void veil(MarketAPI market) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		mem.set(KEY_VEIL, market.getSurveyLevel().name());
		veiled.add(market.getId());
		market.setSurveyLevel(level(mem.getString(KEY_PRIOR_SURVEY), SurveyLevel.NONE));
		// the Markets list shows only when some market in the system is not hidden
		market.setHidden(true);
		// the planet hover names any market that is not condition-only
		market.setPlanetConditionMarketOnly(true);
		// the planet's map colour is its own faction, not its market's
		SectorEntityToken entity = market.getPrimaryEntity();
		if (entity != null) entity.setFaction(Factions.NEUTRAL);
	}

	protected static void unveilAll() {
		active = false;
		for (String id : new HashSet<String>(veiled)) {
			MarketAPI market = Global.getSector().getEconomy().getMarket(id);
			if (market != null) unveil(market);
		}
		veiled.clear();
	}

	protected static void unveil(MarketAPI market) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		market.setPlanetConditionMarketOnly(false);
		market.setHidden(false);
		market.setSurveyLevel(level(mem.getString(KEY_VEIL), SurveyLevel.FULL));
		SectorEntityToken entity = market.getPrimaryEntity();
		if (entity != null) entity.setFaction(market.getFactionId());
		mem.unset(KEY_VEIL);
		veiled.remove(market.getId());
	}

	protected static SurveyLevel level(String name, SurveyLevel fallback) {
		if (name == null) return fallback;
		try {
			return SurveyLevel.valueOf(name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
