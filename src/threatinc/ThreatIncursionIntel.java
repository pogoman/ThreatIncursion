package threatinc;

import java.util.LinkedHashSet;
import java.util.Map;
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

	public static ThreatIncursionIntel get() {
		return (ThreatIncursionIntel) Global.getSector().getMemoryWithoutUpdate().get(KEY);
	}

	public static void ensureAdded() {
		if (get() != null) return;
		ThreatIncursionIntel intel = new ThreatIncursionIntel();
		Global.getSector().getMemoryWithoutUpdate().set(KEY, intel);
		Global.getSector().getIntelManager().addIntel(intel);
	}

	@Override
	public String getName() {
		return "Threat Incursion";
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
		tags.add(Tags.INTEL_MAJOR_EVENT);
		tags.add(TAG_THREAT);
		return tags;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		float initPad = 3f;

		int infested = ThreatIncData.countInfested();
		int phase = IncursionManager.getPhase();
		info.addPara("Infested systems: %s", initPad, t, h, "" + infested);
		info.addPara("Incursion phase: %s", 0f, t, h, "" + phase);
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		info.addPara("The Threat - the ancient fabricator swarms of the Abyss - is spreading "
				+ "into the sector. Seeded systems become fabrication hives; hives saturate "
				+ "and launch incursions against inhabited worlds.", opad);

		int phase = IncursionManager.getPhase();
		String phaseDesc;
		if (phase >= 3) {
			phaseDesc = "converging on the core worlds";
		} else if (phase == 2) {
			phaseDesc = "striking at outlying colonies";
		} else {
			phaseDesc = "expanding through the uninhabited fringe";
		}
		info.addPara("Incursion phase: %s - the swarm is %s.", opad, h, "" + phase, phaseDesc);

		Map<String, String> stages = ThreatIncData.stages();
		if (stages.isEmpty()) {
			info.addPara("No known infested systems. The swarm has been contained - for now.", opad);
		} else {
			info.addPara("Known infested systems:", opad);
			for (Map.Entry<String, String> entry : stages.entrySet()) {
				StarSystemAPI system = null;
				for (StarSystemAPI curr : Global.getSector().getStarSystems()) {
					if (curr.getId().equals(entry.getKey())) {
						system = curr;
						break;
					}
				}
				String name = system != null ? system.getNameWithLowercaseTypeShort() : entry.getKey();
				String stage = entry.getValue();
				Color c = ThreatIncData.STAGE_SEEDED.equals(stage) ? h : neg;
				info.addPara(BULLET + name + ": %s", 3f, c, stage);
			}
		}

		int cleansed = ThreatIncData.getCleansedCount();
		if (cleansed > 0) {
			info.addPara("Systems cleansed by hive destruction: %s.", opad,
					Misc.getPositiveHighlightColor(), "" + cleansed);
		}

		info.addPara("Destroying a system's Fabrication Hive fleet collapses the local "
				+ "infestation and slows the spread everywhere - the swarm's growth scales "
				+ "with how much of it exists.", opad, Misc.getPositiveHighlightColor(),
				"Destroying a system's Fabrication Hive fleet");
	}
}
