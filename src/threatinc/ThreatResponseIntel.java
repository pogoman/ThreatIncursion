package threatinc;

import java.util.LinkedHashSet;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Tracks a faction task force dispatched to purge the Threat from the system
 * that struck one of its colonies. A real campaign fleet, so the player can
 * intercept and fight alongside it; this intel gives it a map marker and a
 * departure notification. Ends when the fleet is destroyed or returns home.
 */
public class ThreatResponseIntel extends BaseIntelPlugin {

	/** Rough hyperspace travel estimate for the ETA display only. */
	public static final float EST_LY_PER_DAY = 0.5f;

	protected CampaignFleetAPI fleet;
	protected String factionId;
	protected String baseName;
	protected String targetSystemName;
	/** Null in intel deserialized from saves older than these fields. */
	protected String targetMarketId;
	protected String targetColonyName;

	public ThreatResponseIntel(CampaignFleetAPI fleet, FactionAPI faction, String baseName,
			com.fs.starfarer.api.campaign.econ.MarketAPI targetColony, String targetSystemName) {
		this.fleet = fleet;
		this.factionId = faction.getId();
		this.baseName = baseName;
		this.targetSystemName = targetSystemName;
		if (targetColony != null) {
			this.targetMarketId = targetColony.getId();
			this.targetColonyName = targetColony.getName();
		}
	}

	/** The Threat colony the task force was sent against, while it still lives. */
	protected SectorEntityToken targetEntity() {
		com.fs.starfarer.api.campaign.econ.MarketAPI market =
				ThreatIncData.resolveColonyMarket(targetMarketId);
		return market != null ? market.getPrimaryEntity() : null;
	}

	public boolean isFleetActive() {
		return fleet != null && fleet.isAlive() && fleet.getContainingLocation() != null;
	}

	protected FactionAPI faction() {
		return Global.getSector().getFaction(factionId);
	}

	@Override
	protected void advanceImpl(float amount) {
		if (isEnding() || isEnded()) return;
		if (!isFleetActive()) {
			endAfterDelay();
		}
	}

	@Override
	public String getName() {
		String fn = faction() != null ? faction().getDisplayName() : "Faction";
		return fn + " Task Force vs Threat";
	}

	@Override
	public String getIcon() {
		return faction() != null ? faction().getCrest() : super.getIcon();
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return faction();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_FLEET_DEPARTURES);
		if (factionId != null) tags.add(factionId);
		return tags;
	}

	/**
	 * The marker sits on the operation's TARGET, matching how the raid-style
	 * intel presents itself - a marker riding the fleet mid-transit read as
	 * "wrong location". Falls back to the fleet for old saves that predate the
	 * target reference, or once the colony is dead.
	 */
	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		SectorEntityToken target = targetEntity();
		if (target != null) return target;
		if (isFleetActive()) return fleet;
		return null;
	}

	/**
	 * Vanilla's map-arrow language: an operation underway toward its target.
	 * One arrow, task force to the colony it is sailing against, while the
	 * journey is actually in progress.
	 */
	@Override
	public java.util.List<ArrowData> getArrowData(SectorMapAPI map) {
		SectorEntityToken target = targetEntity();
		if (target == null || !isFleetActive()) return null;
		if (fleet.getContainingLocation() == target.getContainingLocation()) return null;
		ArrowData arrow = new ArrowData(fleet, target);
		if (faction() != null) arrow.color = faction().getBaseUIColor();
		java.util.List<ArrowData> result = new java.util.ArrayList<ArrowData>();
		result.add(arrow);
		return result;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		if (targetColonyName != null) {
			info.addPara("Target: %s, " + targetSystemName, 3f, Misc.getTextColor(),
					Misc.getHighlightColor(), targetColonyName);
		} else {
			info.addPara("Target: %s", 3f, Misc.getTextColor(), Misc.getHighlightColor(),
					targetSystemName);
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();

		String fn = faction() != null ? faction().getDisplayName() : "A faction";
		String colony = targetColonyName != null
				? "the Threat colony " + targetColonyName : "the Threat colony";
		info.addPara(fn + " has dispatched a task force from " + baseName + " to strike at "
				+ colony + " in the " + targetSystemName + ", in response to an incursion "
				+ "against its own worlds.", opad);

		info.addPara("This is a real fleet. You can intercept it and fight alongside it - or let it "
				+ "make its own way against the swarm. If the colony's Defense Swarms are broken, "
				+ "the colony lies open to bombardment - theirs or yours.", opad, h,
				"fight alongside it");

		if (!isFleetActive()) {
			info.addPara("The task force is no longer in the field.", opad, Misc.getGrayColor());
			return;
		}

		SectorEntityToken target = targetEntity();
		if (target == null) {
			if (targetMarketId != null) {
				info.addPara("The colony it was sent against no longer exists; the task force "
						+ "will stand down and return home.", opad, Misc.getGrayColor());
			}
			return;
		}
		if (fleet.getContainingLocation() == target.getContainingLocation()) {
			info.addPara("The task force has arrived and is engaging the swarm in the "
					+ targetSystemName + ".", opad, h, "engaging the swarm");
		} else {
			float ly = Misc.getDistanceLY(fleet.getLocationInHyperspace(),
					target.getLocationInHyperspace());
			int days = (int) Math.ceil(ly / EST_LY_PER_DAY);
			info.addPara("The task force departed the moment it was mustered and is en route: "
					+ "%s out, arriving in roughly %s days.", opad, h,
					(int) Math.ceil(ly) + " light-years", "" + days);
		}
	}
}
