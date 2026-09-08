package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
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
	 */
	@Override
	public void configureXStream(XStream x) {
		x.alias(PlanetaryShield.class.getName(), ThreatPlanetaryShield.class);
	}

	@Override
	public void onGameLoad(boolean newGame) {
		// the alias above normally covers this; this is the fallback for a shield
		// still on the vanilla plugin after load
		migrateExistingShields();

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

		// transient: re-added every load, never serialized into the save
		IncursionManager manager = new IncursionManager();
		Global.getSector().addTransientScript(manager);
		// transient listener too - hears colony decivilizations so the swarm
		// can claim the worlds its bombardments kill, player bombardments so
		// it can waive the atrocity penalty for exterminating the swarm, and
		// pre-raid marine-loss computation so hive worlds chew up marines
		Global.getSector().getListenerManager().addListener(manager, true);
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
