package threatinc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.AnalyzeEntityIntelCreator;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.GenericMissionManager;
import com.fs.starfarer.api.impl.campaign.intel.GenericMissionManager.GenericMissionCreator;
import com.fs.starfarer.api.impl.campaign.intel.ProcurementMissionCreator;
import com.fs.starfarer.api.impl.campaign.intel.SurveyPlanetIntelCreator;

/**
 * Hive worlds post no vanilla missions (2026-09-24). Vanilla's generic
 * missions - survey, analyze, procurement - pick their issuer from every
 * market that is not hidden, and a hive market cannot be hidden without
 * leaving the economy. So each vanilla creator in {@link GenericMissionManager}
 * is replaced by a subclass ({@link AnalyzeEntity}, {@link SurveyPlanet},
 * {@link Procurement}) that ends a mission whose issuer is the Threat on
 * creation; the manager discards a done intel (it counts as "no mission")
 * before the player can ever receive it. Frequency weights are inherited.
 *
 * <p>Subclasses, not wrappers. Vanilla's {@code addScriptsIfNeeded} re-adds a
 * creator on every load unless {@code hasMissionCreator(Class)} - an
 * {@code isInstance} test - finds one. This class used to WRAP the creators
 * (2026-09-24, same day), which failed that test, so vanilla appended a fresh
 * creator per load and the wrapper wrapped it too: the persisted list grew by
 * two per load and those missions' weights with it. A subclass passes the
 * test. This class stays a loadable creator only so XStream can read the
 * wrappers such a save holds; {@link #install} unwraps them and never
 * registers one again.
 *
 * <p>The subclasses declare no fields and are aliased to the vanilla class
 * names in {@link ThreatIncModPlugin#configureXStream}, like the shield, so
 * the save never names a threatinc class for them.
 */
public class ThreatMissionFilter implements GenericMissionCreator {

	/** Analyze-entity missions, less the hive worlds'. */
	public static class AnalyzeEntity extends AnalyzeEntityIntelCreator {
		@Override
		public EveryFrameScript createMissionIntel() {
			return dropIfPostedByThreat(super.createMissionIntel());
		}
	}

	/** Survey missions, less the hive worlds'. */
	public static class SurveyPlanet extends SurveyPlanetIntelCreator {
		@Override
		public EveryFrameScript createMissionIntel() {
			return dropIfPostedByThreat(super.createMissionIntel());
		}
	}

	/** Procurement missions (retired by vanilla; a save may still hold the creator), less the hive worlds'. */
	public static class Procurement extends ProcurementMissionCreator {
		@Override
		public EveryFrameScript createMissionIntel() {
			return dropIfPostedByThreat(super.createMissionIntel());
		}
	}

	/** Legacy wrapper state: read from saves made with the wrapper, never written again. */
	protected GenericMissionCreator delegate;

	public ThreatMissionFilter(GenericMissionCreator delegate) {
		this.delegate = delegate;
	}

	/**
	 * Puts the manager's creator list in its intended shape. Idempotent; run
	 * on every load. Legacy wrappers are unwrapped, duplicates of a filtered
	 * vanilla class beyond the first are dropped (the wrapper let one accrue
	 * per load), and a plain vanilla creator becomes its filtering subclass.
	 * Creators of any other class - another mod's - are kept as they are.
	 */
	public static void install() {
		GenericMissionManager manager = GenericMissionManager.getInstance();
		if (manager == null) return;
		List<GenericMissionCreator> creators = manager.getCreators();
		List<GenericMissionCreator> rebuilt = new ArrayList<GenericMissionCreator>();
		Set<Class<?>> seen = new HashSet<Class<?>>();
		int dropped = 0;
		for (GenericMissionCreator c : creators) {
			while (c instanceof ThreatMissionFilter) c = ((ThreatMissionFilter) c).delegate;
			if (c == null) continue;
			Class<?> filtered = filteredClass(c);
			if (filtered != null) {
				if (!seen.add(filtered)) {
					dropped++;
					continue;
				}
				c = filtering(c);
			}
			rebuilt.add(c);
		}
		if (rebuilt.equals(creators)) return;
		creators.clear();
		creators.addAll(rebuilt);
		ThreatIncConfig.log("Vanilla mission creators filtered: " + rebuilt.size() + " kept, "
				+ dropped + " duplicate(s) dropped");
	}

	/** The vanilla creator class {@code c} is filtered under, or null for one this mod leaves alone. */
	static Class<?> filteredClass(GenericMissionCreator c) {
		if (c instanceof AnalyzeEntityIntelCreator) return AnalyzeEntityIntelCreator.class;
		if (c instanceof SurveyPlanetIntelCreator) return SurveyPlanetIntelCreator.class;
		if (c instanceof ProcurementMissionCreator) return ProcurementMissionCreator.class;
		return null;
	}

	/**
	 * The filtering subclass standing in for a plain vanilla creator; {@code c}
	 * itself when it already is one, or another mod's subclass.
	 */
	static GenericMissionCreator filtering(GenericMissionCreator c) {
		Class<?> exact = c.getClass();
		if (exact == AnalyzeEntityIntelCreator.class) return new AnalyzeEntity();
		if (exact == SurveyPlanetIntelCreator.class) return new SurveyPlanet();
		if (exact == ProcurementMissionCreator.class) return new Procurement();
		return c;
	}

	/** Ends {@code intel} on the spot when the Threat posted it; the manager then discards it. */
	static EveryFrameScript dropIfPostedByThreat(EveryFrameScript intel) {
		if (intel instanceof BaseIntelPlugin && postedByThreat((BaseIntelPlugin) intel)) {
			((BaseIntelPlugin) intel).endImmediately();
			ThreatIncConfig.log("Vanilla mission from a hive world dropped: "
					+ intel.getClass().getSimpleName());
		}
		return intel;
	}

	protected static boolean postedByThreat(BaseIntelPlugin intel) {
		SectorEntityToken at = intel.getPostingLocation();
		MarketAPI market = at != null ? at.getMarket() : null;
		if (market != null && Factions.THREAT.equals(market.getFactionId())) return true;
		FactionAPI faction = intel.getFactionForUIColors();
		return faction != null && Factions.THREAT.equals(faction.getId());
	}

	// Legacy wrapper behaviour; install() retires every instance before the
	// manager can call these, so they only matter to a save mid-migration.

	@Override
	public float getMissionFrequencyWeight() {
		return delegate != null ? delegate.getMissionFrequencyWeight() : 0f;
	}

	@Override
	public EveryFrameScript createMissionIntel() {
		return delegate != null ? dropIfPostedByThreat(delegate.createMissionIntel()) : null;
	}
}
