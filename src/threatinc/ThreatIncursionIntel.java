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

	/**
	 * The systems the player actually knows are infested: visited in person,
	 * named in a bounty, or the origin of a strike. This intel deliberately shows
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
