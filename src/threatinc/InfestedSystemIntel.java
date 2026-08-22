package threatinc;

import java.util.LinkedHashSet;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * One marker per infested system: appears under the "Threat Incursion" intel
 * tab and as an icon on the sector map at the system's location, so the
 * spread is visible at a glance.
 */
public class InfestedSystemIntel extends BaseIntelPlugin {

	protected String systemId;

	public InfestedSystemIntel(String systemId) {
		this.systemId = systemId;
	}

	public String getSystemId() {
		return systemId;
	}

	protected StarSystemAPI getSystem() {
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	protected String getStage() {
		String stage = ThreatIncData.stages().get(systemId);
		return stage != null ? stage : "unknown";
	}

	@Override
	public String getName() {
		StarSystemAPI system = getSystem();
		String name = system != null ? system.getBaseName() : systemId;
		return "Threat Infestation - " + name;
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
		StarSystemAPI system = getSystem();
		if (system != null) return system.getHyperspaceAnchor();
		return null;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		String stage = getStage();
		Color c = ThreatIncData.STAGE_SEEDED.equals(stage)
				? Misc.getHighlightColor() : Misc.getNegativeHighlightColor();
		info.addPara("Stage: %s", 3f, t, c, stage);
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		StarSystemAPI system = getSystem();
		String name = system != null ? system.getNameWithLowercaseType() : systemId;
		String stage = getStage();

		if (ThreatIncData.STAGE_SEEDED.equals(stage)) {
			info.addPara("Threat fabrication signatures have been detected in the " + name + ". "
					+ "The infestation is in its early stages - a %s is growing.", opad,
					neg, "Fabrication Hive");
			info.addPara("No hive fleet exists yet; once it forms, destroying it will cleanse "
					+ "the system.", opad);
		} else if (ThreatIncData.STAGE_HIVE.equals(stage)) {
			info.addPara("A Threat %s is active in the " + name + ", replicating and growing "
					+ "toward saturation.", opad, neg, "Fabrication Hive");
			info.addPara("Destroying the hive fleet will cleanse the system and slow the "
					+ "sector-wide spread.", opad, Misc.getPositiveHighlightColor(),
					"Destroying the hive fleet");
		} else {
			info.addPara("The " + name + " is %s - fully claimed by the swarm. Incursion "
					+ "fleets muster here to strike at inhabited worlds.", opad, neg, "saturated");
			info.addPara("Destroying the hive fleet will cleanse the system, end strikes from "
					+ "this staging point, and slow the sector-wide spread.", opad,
					Misc.getPositiveHighlightColor(), "Destroying the hive fleet");
		}

		float days = ThreatIncData.daysInStage(systemId);
		info.addPara("Time in current stage: %s days.", opad, h, "" + (int) days);
	}
}
