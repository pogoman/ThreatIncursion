package threatinc;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Vanilla-raid-style transit intel for a Seeding Swarm: appears when the wave
 * launches, tracks distance remaining and a rough ETA while it flies, and
 * resolves when the swarm makes planetfall or dies. This - not narrator
 * messages - is how the player hears about colonization in normal play.
 */
public class SeedingSwarmIntel extends BaseIntelPlugin {

	/** Rough hyperspace pace of a swarm, LY per day, for the ETA estimate. */
	public static final float EST_LY_PER_DAY = 0.5f;

	protected String planetId;
	protected String systemId;
	protected String sourceName; // null = the Abyss itself (bootstrap)
	protected String planetName; // captured at launch; several waves can share a system
	protected String outcome; // null while in transit, else "arrived"/"destroyed"

	public SeedingSwarmIntel(String planetId, String systemId, String sourceName) {
		this.planetId = planetId;
		this.systemId = systemId;
		this.sourceName = sourceName;
		SectorEntityToken planet = Global.getSector().getEntityById(planetId);
		this.planetName = planet != null ? planet.getName() : null;
	}

	public boolean isInTransit() {
		return outcome == null;
	}

	public String getPlanetId() {
		return planetId;
	}

	public String getSystemId() {
		return systemId;
	}

	/** Name of the colony that launched the wave; null for the Abyss bootstrap. */
	public String getSourceName() {
		return sourceName;
	}

	public String getPlanetName() {
		return planetName;
	}

	protected StarSystemAPI getSystem() {
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	protected CampaignFleetAPI getFleet() {
		return ThreatIncData.waveFleets().get(planetId);
	}

	@Override
	protected void advanceImpl(float amount) {
		if (outcome != null) return;
		CampaignFleetAPI fleet = getFleet();
		if (fleet != null && fleet.isAlive()) return;
		resolveNow();
	}

	/** The wave is gone from the books: either it planted its colony or died. */
	protected void resolveNow() {
		SectorEntityToken planet = Global.getSector().getEntityById(planetId);
		boolean tookRoot = planet != null
				&& Factions.THREAT.equals(planet.getFaction() != null
						? planet.getFaction().getId() : null);
		outcome = tookRoot ? "arrived" : "destroyed";
		sendUpdateIfPlayerHasIntel(null, false);
		endAfterDelay();
		ThreatIncConfig.log("Swarm intel resolved (" + outcome + "): " + getName());
	}

	/**
	 * Push-based resolution, called by checkWaveArrivals the moment a wave
	 * lands, dies, or withdraws - doesn't rely on this intel's own advance.
	 */
	public static void resolveFor(String planetId, boolean arrived) {
		for (Object curr : Global.getSector().getIntelManager()
				.getIntel(SeedingSwarmIntel.class)) {
			SeedingSwarmIntel intel = (SeedingSwarmIntel) curr;
			if (!planetId.equals(intel.planetId) || intel.outcome != null) continue;
			intel.outcome = arrived ? "arrived" : "destroyed";
			intel.sendUpdateIfPlayerHasIntel(null, false);
			intel.endAfterDelay();
			ThreatIncConfig.log("Swarm intel resolved (" + intel.outcome + "): "
					+ intel.getName());
		}
	}

	/**
	 * Safety sweep from the fast poll: any tracker whose wave is no longer in
	 * the books gets resolved from the planet's current owner. Cleans up
	 * trackers that missed their moment (e.g. from older jars).
	 */
	public static void sweepStale() {
		for (Object curr : Global.getSector().getIntelManager()
				.getIntel(SeedingSwarmIntel.class)) {
			SeedingSwarmIntel intel = (SeedingSwarmIntel) curr;
			if (intel.outcome != null) continue;
			if (ThreatIncData.waveFleets().containsKey(intel.planetId)) continue;
			intel.resolveNow();
		}
	}

	protected float distanceRemainingLY() {
		CampaignFleetAPI fleet = getFleet();
		StarSystemAPI system = getSystem();
		if (fleet == null || system == null) return -1f;
		return Misc.getDistanceLY(fleet.getLocationInHyperspace(), system.getLocation());
	}

	@Override
	public String getName() {
		// per-planet, not per-system: the OG chain sends several waves at one
		// system at once, and five rows all named for the system are unreadable
		String name = planetName;
		if (name == null) {
			StarSystemAPI system = getSystem();
			name = system != null ? system.getBaseName() : systemId;
		}
		if ("arrived".equals(outcome)) return "Planetfall - " + name;
		if ("destroyed".equals(outcome)) return "Swarm Destroyed - " + name;
		return "Threat Seeding Swarm - " + name;
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		return tags;
	}

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		CampaignFleetAPI fleet = getFleet();
		if (fleet != null && fleet.isAlive() && outcome == null) return fleet;
		StarSystemAPI system = getSystem();
		return system != null ? system.getHyperspaceAnchor() : null;
	}

	@Override
	public java.util.List<ArrowData> getArrowData(SectorMapAPI map) {
		if (outcome != null) return null;
		CampaignFleetAPI fleet = getFleet();
		StarSystemAPI system = getSystem();
		if (fleet == null || !fleet.isAlive() || system == null) return null;
		SectorEntityToken to = system.getHyperspaceAnchor();
		if (to == null) return null;
		java.util.List<ArrowData> arrows = new java.util.ArrayList<ArrowData>();
		ArrowData arrow = new ArrowData(fleet, to);
		arrow.color = Misc.getNegativeHighlightColor();
		arrows.add(arrow);
		return arrows;
	}

	/** Whether the wave has already reached its target star system. */
	protected boolean isInTargetSystem() {
		CampaignFleetAPI fleet = getFleet();
		StarSystemAPI system = getSystem();
		return fleet != null && system != null && fleet.getContainingLocation() == system;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		if (outcome == null) {
			if (isInTargetSystem()) {
				// hyperspace distance reads ~0 once in-system; a fake "1 day"
				// ETA here would just be wrong
				info.addPara("On terminal approach", 3f, t, neg, "terminal approach");
			} else {
				float dist = distanceRemainingLY();
				if (dist >= 0) {
					int eta = Math.max(1, Math.round(dist / EST_LY_PER_DAY));
					info.addPara("Est. arrival: %s days", 3f, t, h, "" + eta);
				}
			}
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();

		StarSystemAPI system = getSystem();
		String sysName = system != null ? system.getNameWithLowercaseType() : systemId;
		String target = planetName != null ? planetName + " in the " + sysName : "the " + sysName;
		String from = sourceName != null ? sourceName
				: "somewhere in the deep Abyss";

		if ("arrived".equals(outcome)) {
			info.addPara("The Seeding Swarm has made planetfall on " + target + ". "
					+ "Fabrication strata are taking root - a Threat colony now exists there, "
					+ "and it will grow.", opad, neg, "made planetfall");
			return;
		}
		if ("destroyed".equals(outcome)) {
			info.addPara("The Seeding Swarm bound for " + target + " has been destroyed. "
					+ "No colony will take root from this wave - though the swarm may "
					+ "fabricate another.", opad, pos, "destroyed");
			return;
		}

		info.addPara("A Threat Seeding Swarm has departed " + from + ", in transit to "
				+ target + " - it carries the fabricator core of a new colony.", opad,
				neg, "Seeding Swarm");

		if (isInTargetSystem()) {
			info.addPara("The swarm has entered the system and is on %s to its target world.",
					opad, neg, "terminal approach");
		} else {
			float dist = distanceRemainingLY();
			if (dist >= 0) {
				int eta = Math.max(1, Math.round(dist / EST_LY_PER_DAY));
				info.addPara("Distance remaining: %s. Estimated arrival in %s days.", opad, h,
						String.format("%.1f ly", dist), "" + eta);
			}
		}

		info.addPara("Destroy the swarm before it makes planetfall and no colony will "
				+ "take root.", opad, pos, "Destroy the swarm");
	}
}
