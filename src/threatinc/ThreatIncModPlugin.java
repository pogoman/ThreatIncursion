package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.AnalyzeEntityIntelCreator;
import com.fs.starfarer.api.impl.campaign.intel.ProcurementMissionCreator;
import com.fs.starfarer.api.impl.campaign.intel.SurveyPlanetIntelCreator;
import com.thoughtworks.xstream.XStream;

public class ThreatIncModPlugin extends BaseModPlugin {

	/**
	 * Keeps the Planetary Shield swap out of the save file.
	 *
	 * <p>{@link ThreatPlanetaryShield} replaces vanilla's plugin through
	 * industries.csv, and without this alias every save would store the industry
	 * under its real name - so loading that save with this mod disabled would die
	 * with {@code CannotResolveClassException: threatinc.ThreatPlanetaryShield}.
	 * Aliasing our class to vanilla's name makes XStream write it as
	 * {@code com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield}: the
	 * save never mentions a threatinc class for the shield and loads without this
	 * mod as a plain vanilla shield. Reading is the mirror image, so shields in an
	 * existing save are picked up on load with no migration at all.
	 *
	 * <p>This holds only while {@link ThreatPlanetaryShield} declares no instance
	 * fields - a field vanilla's class does not have would break loading without
	 * the mod just as badly.
	 *
	 * <p>The filtering mission creators ({@link ThreatMissionFilter}) take the same
	 * alias, on the same no-fields condition.
	 */
	@Override
	public void configureXStream(XStream x) {
		x.alias(PlanetaryShield.class.getName(), ThreatPlanetaryShield.class);
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
	}

	@Override
	public void onGameLoad(boolean newGame) {
		// before anything reads a market: a save taken with the map veil on
		ThreatMapFog.onGameLoad();

		// the alias above normally covers this; this is the fallback for a shield
		// still on the vanilla plugin after load
		migrateExistingShields();

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

		// hive worlds post no vanilla missions (survey, analyze, procurement)
		ThreatMissionFilter.install();

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
		// a human colony's fortifications on the disrupt-raid list
		Global.getSector().getListenerManager().addListener(new ThreatFortificationRaids(), true);
		// hears the player's battles for the swarm bounties
		Global.getSector().addTransientListener(new ThreatSwarmBountyIntel.Kills());
		// unfound hives veiled on the map and intel screens while a core tab is open
		ThreatMapFog fog = new ThreatMapFog();
		Global.getSector().addTransientScript(fog);
		Global.getSector().getListenerManager().addListener(fog, true);
		// the player's outpost stations open their own dialog (storage, decommission)
		Global.getSector().registerPlugin(new ThreatIncCampaignPlugin());

		// keep the intel entry alive on saves where the incursion already started
		if (ThreatIncData.isStarted()) {
			ThreatIncursionIntel.ensureAdded();
		}
	}

	/**
	 * Rebuilds any Planetary Shield still on the vanilla plugin class so it uses
	 * {@link ThreatPlanetaryShield}, carrying over AI core, improvement, special
	 * item and disruption state. The shield's disruption key is pinned to
	 * vanilla's ({@code ThreatPlanetaryShield.getDisruptedKey}), so the clock
	 * survives the swap on its own; it is re-set here anyway because
	 * removeIndustry/addIndustry builds a fresh instance.
	 *
	 * <p>A shield still under construction is left alone - the in-progress
	 * instance cannot be swapped without losing build progress - and is picked up
	 * by this same check on the next load after it finishes.
	 */
	private void migrateExistingShields() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			Industry old = market.getIndustry(Industries.PLANETARYSHIELD);
			if (old == null || old instanceof ThreatPlanetaryShield) continue;
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
			ThreatIncConfig.log("Migrated the Planetary Shield on " + market.getName()
					+ " to this mod's plugin.");
		}
	}
}
