package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.AnalyzeEntityIntelCreator;
import com.fs.starfarer.api.impl.campaign.intel.ProcurementMissionCreator;
import com.fs.starfarer.api.impl.campaign.intel.SurveyPlanetIntelCreator;
import com.thoughtworks.xstream.XStream;

public class ThreatIncModPlugin extends BaseModPlugin {

	/**
	 * Keeps our plugin swaps out of the save file: a class of ours that stands in for one of
	 * vanilla's is aliased to vanilla's name, so a save never names it and still loads with
	 * this mod removed. That holds only while the subclass declares no instance fields.
	 *
	 * <p>The Planetary Shield is no longer one of them (2026-11-09, {@link #retireShields}):
	 * with no alias, a shield an older jar wrote under vanilla's class name loads as vanilla's.
	 */
	@Override
	public void configureXStream(XStream x) {
		// the producing industries: ours over vanilla's for the occupation's image (2026-10-27)
		ThreatOccupation.aliasPlugins(x);
		x.alias(AnalyzeEntityIntelCreator.class.getName(), ThreatMissionFilter.AnalyzeEntity.class);
		x.alias(SurveyPlanetIntelCreator.class.getName(), ThreatMissionFilter.SurveyPlanet.class);
		x.alias(ProcurementMissionCreator.class.getName(), ThreatMissionFilter.Procurement.class);
		// link fields dropped in the 2026-09-27 review; saves written before still carry them
		x.omitField(ThreatFrontlines.Outpost.class, "guardFP");
		x.omitField(ThreatFrontlines.Outpost.class, "founded");
		x.omitField(ThreatFrontlines.Outpost.class, "entityId");
		x.omitField(ThreatFrontlines.Outpost.class, "lastRelief");
	}

	@Override
	public void onApplicationLoad() {
		// LunaLib keeps stored values over changed defaults; move the ones this release changed
		if (ThreatIncConfig.lunaAvailable()) LunaConfigBridge.migrateStoredDefaults();
		// the producing industries' plugins are ours (the occupation's image) and the green images are loaded
		ThreatOccupation.registerPlugins();
	}

	@Override
	public void onGameLoad(boolean newGame) {
		// before anything reads a market: a save taken with the map veil on
		ThreatMapFog.onGameLoad();

		// the Planetary Shield is vanilla's again (2026-11-09): a shield still on our retired stub is rebuilt
		retireShields();

		// before the first merc spawns: routes to links dismantled by older builds
		ThreatFrontlines.repairMercRoutes();

		// Saves from before the siege rework (data v4) may still carry the
		// retired Fragment Fabricator item installed in hive industries; its
		// InstallableItemEffect stub is gone, and the colony UI fatals on the
		// null the moment it renders an industry holding one. Strip it FIRST,
		// synchronously, before any dialog can open. (The v4 data migration
		// repeats the strip durably; both are idempotent.)
		if (ThreatIncData.isStarted() && ThreatIncData.getDataVersion() < 4) {
			ThreatColonyManager.stripFragmentFabricators();
		}

		// Pin every colony's marine arming ramp to the stock it is ALREADY
		// standing on, before anything can be delivered to it this session.
		// Seeded lazily instead, the first delivery after a save upgrade was
		// grandfathered in as a standing garrison and skipped the ramp
		// entirely (2026-09-08). Idempotent, and cheap - one pass, no writes
		// for colonies that hold nothing.
		ThreatReserves.seedMarineArming();

		// convoys no longer land as trade modifiers (docs/economy-coherence.md
		// rule 5): lift the ones an older build left, or the military's stock
		// sells as market excess for another 120 days
		ThreatConvoys.stripLandedMods();

		// a mid-war save's hive worlds start with a handful of reclaimed shapes, once (converted hulls)
		ThreatReclaimed.backfillOnLoad();

		// and its big swarm fleets carry a fabricator from this load, not from their next bud
		ThreatFleetComposer.anchorFleets();

		// hive worlds post no vanilla missions (survey, analyze, procurement)
		ThreatMissionFilter.install();
		// and vanilla's academy fleets send nothing, rather than fatal, once the
		// swarm has taken every market that could send one
		ThreatAcademyFleetGuard.install();

		// (2026-09-29: closed economy) the garrison respawn clock is gone - the
		// FP bank paces the swarm - so its per-colony timestamps are dropped. A
		// plain map under a string key: nothing in the save refers to it
		Global.getSector().getPersistentData().remove("threatinc_garrisonSpawnTimes");

		// a save reloaded at the same clock instant must not read the last
		// session's staging targets
		ThreatConvoys.forgetStagingTargets();
		// nor any other not-saved state of the game this session left: a system
		// thinned in the abandoned timeline, a fleet cap learned in another save,
		// debug lines held quiet on the old clock, a bounty's standing owed for
		// one of its battles, the front set and depot shares cached against the
		// old sector (holding it keeps the whole abandoned campaign in memory)
		IncursionManager.forgetThinned();
		ThreatSoftening.forgetFleetCaps();
		ThreatIncConfig.forgetQuiet();
		ThreatSwarmBountyIntel.forgetPending();
		ThreatFrontlines.forgetCaches();
		ThreatReserves.forgetCaches();
		ThreatMissionIntel.forgetCaches();
		// and the posture's per-colony wants, its pass clock and month's tallies,
		// the reach's fronts and trips committed on the old timeline
		ThreatPosture.forget();
		ThreatStance.forget();
		ThreatReach.forget();
		ThreatFactionStance.forget();
		// and a stock's unmet bill held over from the session left (ThreatFuel.held)
		ThreatFuel.forget();
		ThreatIntel.forget();
		ThreatSwarmIntel.forget();
		ThreatSimDump.forget();
		ThreatAttackPlanner.forget();
		ThreatWarCouncil.forget();

		// colonyMarkets keys that read lookups created before 0.7.0 made in-system
		// expansion seed hives into inhabited core systems (rc1 review)
		if (ThreatIncData.isStarted()) ThreatIncData.dropPhantomSystems();

		// transient: re-added every load, never serialized into the save
		IncursionManager manager = new IncursionManager();
		Global.getSector().addTransientScript(manager);
		// transient listener too - hears colony decivilizations so the swarm
		// can claim the worlds its bombardments kill, player bombardments so
		// it can waive the atrocity penalty for exterminating the swarm, and
		// pre-raid marine-loss computation so hive worlds chew up marines
		Global.getSector().getListenerManager().addListener(manager, true);
		// v3 (2026-10-27): raids are vanilla's (ThreatFortificationRaids deleted with phase 1b)
		// hears the player's battles for the swarm bounties
		Global.getSector().addTransientListener(new ThreatSwarmBountyIntel.Kills());
		// hears every battle the swarm wins for the reclaimed-hull ledger (converted hulls)
		Global.getSector().addTransientListener(new ThreatReclaimed.Listener());
		// unfound hives veiled on the map and intel screens while a core tab is open
		ThreatMapFog fog = new ThreatMapFog();
		Global.getSector().addTransientScript(fog);
		Global.getSector().getListenerManager().addListener(fog, true);
		// E inside a colony screen opened from the board closes back to the board
		ThreatColonyScreenDialog.Guard colonyGuard = new ThreatColonyScreenDialog.Guard();
		Global.getSector().addTransientScript(colonyGuard);
		Global.getSector().getListenerManager().addListener(colonyGuard, true);
		// the player's outpost stations open their own dialog (storage, decommission)
		Global.getSector().registerPlugin(new ThreatIncCampaignPlugin());

		// keep the intel entry alive on saves where the incursion already started
		if (ThreatIncData.isStarted()) {
			ThreatIncursionIntel.ensureAdded();
		}
	}

	/**
	 * Rebuilds any Planetary Shield a save still holds on the retired {@link ThreatPlanetaryShield}
	 * stub on the spec's plugin - vanilla's, or another mod's - carrying over AI core, improvement,
	 * special item and disruption. The user, 2026-11-09: the disruption mechanics the swap existed
	 * for went on 2026-10-27, and it crashed any other mod that replaces the shield.
	 *
	 * <p>A shield still under construction is left alone - the in-progress instance cannot be
	 * swapped without losing build progress - and is picked up on the load after it finishes.
	 */
	private void retireShields() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			Industry old = market.getIndustry(Industries.PLANETARYSHIELD);
			if (!(old instanceof ThreatPlanetaryShield)) continue;
			if (old.isBuilding()) continue;

			String aiCore = old.getAICoreId();
			boolean improved = old.isImproved();
			SpecialItemData special = old.getSpecialItem();
			float disrupted = old.getDisruptedDays();

			market.removeIndustry(Industries.PLANETARYSHIELD, null, false);
			market.addIndustry(Industries.PLANETARYSHIELD);
			Industry fresh = market.getIndustry(Industries.PLANETARYSHIELD);
			if (fresh == null) continue;
			if (aiCore != null) fresh.setAICoreId(aiCore);
			if (improved) fresh.setImproved(true);
			if (special != null) fresh.setSpecialItem(special);
			if (disrupted > 0f) fresh.setDisrupted(disrupted, false);
			market.reapplyIndustries();
			ThreatIncConfig.log("Planetary Shield on " + market.getName() + " rebuilt on "
					+ fresh.getClass().getName() + " (was this mod's retired plugin).");
		}
	}
}
