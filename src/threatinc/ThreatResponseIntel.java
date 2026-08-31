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

	/** The lead fleet; the only fleet in intel from saves older than {@link #fleets}. */
	protected CampaignFleetAPI fleet;
	/** Every fleet of the flotilla. Null in intel deserialized from older saves. */
	protected java.util.List<CampaignFleetAPI> fleets;
	protected String factionId;
	protected String baseName;
	protected String targetSystemName;
	/** Null in intel deserialized from saves older than these fields. */
	protected String targetMarketId;
	protected String targetColonyName;

	public ThreatResponseIntel(java.util.List<CampaignFleetAPI> fleets, FactionAPI faction,
			String baseName, com.fs.starfarer.api.campaign.econ.MarketAPI targetColony,
			String targetSystemName) {
		this.fleets = new java.util.ArrayList<CampaignFleetAPI>(fleets);
		this.fleet = fleets.isEmpty() ? null : fleets.get(0);
		this.factionId = faction.getId();
		this.baseName = baseName;
		this.targetSystemName = targetSystemName;
		if (targetColony != null) {
			this.targetMarketId = targetColony.getId();
			this.targetColonyName = targetColony.getName();
		}
	}

	/** All fleets of the flotilla, tolerating old single-fleet saves. */
	protected java.util.List<CampaignFleetAPI> allFleets() {
		if (fleets != null) return fleets;
		java.util.List<CampaignFleetAPI> result = new java.util.ArrayList<CampaignFleetAPI>();
		if (fleet != null) result.add(fleet);
		return result;
	}

	protected static boolean alive(CampaignFleetAPI curr) {
		return curr != null && curr.isAlive() && curr.getContainingLocation() != null;
	}

	/** The first still-living fleet, for map markers and distance estimates. */
	protected CampaignFleetAPI leadFleet() {
		for (CampaignFleetAPI curr : allFleets()) {
			if (alive(curr)) return curr;
		}
		return null;
	}

	/** The Threat colony the task force was sent against, while it still lives. */
	protected SectorEntityToken targetEntity() {
		com.fs.starfarer.api.campaign.econ.MarketAPI market =
				ThreatIncData.resolveColonyMarket(targetMarketId);
		return market != null ? market.getPrimaryEntity() : null;
	}

	public boolean isFleetActive() {
		return leadFleet() != null;
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
		return leadFleet();
	}

	/**
	 * Vanilla's map-arrow language: an operation underway toward its target.
	 * One arrow, task force to the colony it is sailing against, while the
	 * journey is actually in progress.
	 */
	@Override
	public java.util.List<ArrowData> getArrowData(SectorMapAPI map) {
		SectorEntityToken target = targetEntity();
		CampaignFleetAPI lead = leadFleet();
		if (target == null || lead == null) return null;
		if (lead.getContainingLocation() == target.getContainingLocation()) return null;
		ArrowData arrow = new ArrowData(lead, target);
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
		int total = allFleets().size();
		String force = total > 1 ? "a task force of " + total + " fleets" : "a task force";
		info.addPara(fn + " has dispatched " + force + " from " + baseName + " to strike at "
				+ colony + " in the " + targetSystemName + ", in response to an incursion "
				+ "against its own worlds.", opad);

		info.addPara("These are real fleets. You can intercept them and fight alongside them - or "
				+ "let them make their own way against the swarm. If the colony's Defense Swarms "
				+ "are broken, the colony lies open to bombardment - theirs or yours.", opad, h,
				"fight alongside them");

		CampaignFleetAPI lead = leadFleet();
		if (lead == null) {
			info.addPara("The task force is no longer in the field.", opad, Misc.getGrayColor());
			return;
		}
		int living = 0;
		for (CampaignFleetAPI curr : allFleets()) {
			if (alive(curr)) living++;
		}
		if (living < total) {
			info.addPara("%s of its fleets remain in the field.", opad, h, "" + living);
		}

		SectorEntityToken target = targetEntity();
		if (target == null) {
			if (targetMarketId != null) {
				info.addPara("The colony it was sent against no longer exists; the task force "
						+ "will stand down and return home.", opad, Misc.getGrayColor());
			}
			return;
		}
		if (lead.getContainingLocation() == target.getContainingLocation()) {
			info.addPara("The task force has arrived and is engaging the swarm in the "
					+ targetSystemName + ".", opad, h, "engaging the swarm");
		} else {
			float ly = Misc.getDistanceLY(lead.getLocationInHyperspace(),
					target.getLocationInHyperspace());
			int days = (int) Math.ceil(ly / EST_LY_PER_DAY);
			info.addPara("The task force departed the moment it was mustered and is en route: "
					+ "%s out, arriving in roughly %s days.", opad, h,
					(int) Math.ceil(ly) + " light-years", "" + days);
		}
	}
}
