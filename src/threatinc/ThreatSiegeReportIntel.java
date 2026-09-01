package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import threatinc.ThreatPurgeFGI.SiegeActionRecord;

/**
 * After-action sitrep of an NPC siege expedition against a Threat system:
 * what was done to each planet (bombardments, commando raids), industries
 * disrupted and for how long, estimated marine casualties, and the current
 * state of each colony - destroyed, or its size, health, and decline. Posted
 * by {@link ThreatPurgeFGI} when the expedition ends, however it ends.
 *
 * Driven by {@link IncursionManager#advanceModIntel} (the engine never
 * advances intel on its own); expires quietly after a while.
 */
public class ThreatSiegeReportIntel extends BaseIntelPlugin {

	/** Days the report stays in the intel screen before expiring. */
	public static final float REPORT_LIFETIME_DAYS = 120f;

	protected String factionId;
	protected String systemName;
	protected List<SiegeActionRecord> records;
	/** "completed", "destroyed", or "aborted". */
	protected String outcome;
	protected float daysActive = 0f;

	public ThreatSiegeReportIntel(String factionId, String systemName,
			List<SiegeActionRecord> records, String outcome) {
		this.factionId = factionId;
		this.systemName = systemName;
		this.records = records != null ? records : new ArrayList<SiegeActionRecord>();
		this.outcome = outcome;
	}

	protected FactionAPI faction() {
		return factionId != null ? Global.getSector().getFaction(factionId) : null;
	}

	@Override
	protected void advanceImpl(float amount) {
		if (isEnding() || isEnded()) return;
		daysActive += Global.getSector().getClock().convertToDays(amount);
		if (daysActive > REPORT_LIFETIME_DAYS) {
			endAfterDelay();
		}
	}

	@Override
	public String getName() {
		if (faction() != null && faction().isPlayerFaction()) {
			// "Your Siege Report" reads badly; the commissioned op gets a
			// neutral operational title
			return "Siege Expedition Report - " + systemName;
		}
		String fn = faction() != null ? faction().getDisplayName() : "Faction";
		return fn + " Siege Report - " + systemName;
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
		tags.add(Tags.INTEL_MILITARY);
		if (factionId != null) tags.add(factionId);
		return tags;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		int actions = records.size();
		int killed = countDestroyed();
		info.addPara(BULLET + "%s ground actions, %s colonies destroyed", 3f,
				Misc.getTextColor(), Misc.getHighlightColor(),
				"" + actions, "" + killed);
	}

	protected int countDestroyed() {
		Set<String> dead = new LinkedHashSet<String>();
		for (SiegeActionRecord rec : records) {
			if (rec.destroyed) dead.add(rec.marketId);
		}
		return dead.size();
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();

		String fn = faction() != null && faction().isPlayerFaction()
				? "The expedition you commissioned"
				: (faction() != null ? faction().getDisplayName() : "A faction");
		String outcomeText;
		if ("destroyed".equals(outcome)) {
			outcomeText = "The expedition was destroyed in the field.";
		} else if ("aborted".equals(outcome)) {
			outcomeText = "The expedition stood down before completing its objectives.";
		} else {
			outcomeText = "The expedition has completed its operations and withdrawn.";
		}
		info.addPara(fn + " conducted siege operations against the Threat colonies of the "
				+ systemName + ". " + outcomeText, opad);

		if (records.isEmpty()) {
			info.addPara("No ground actions were carried out - the expedition was stopped "
					+ "before reaching the surface.", opad, gray);
			return;
		}

		// group the action log by planet, preserving order
		Map<String, List<SiegeActionRecord>> byMarket =
				new LinkedHashMap<String, List<SiegeActionRecord>>();
		for (SiegeActionRecord rec : records) {
			List<SiegeActionRecord> list = byMarket.get(rec.marketId);
			if (list == null) {
				list = new ArrayList<SiegeActionRecord>();
				byMarket.put(rec.marketId, list);
			}
			list.add(rec);
		}

		int totalLosses = 0;
		for (Map.Entry<String, List<SiegeActionRecord>> entry : byMarket.entrySet()) {
			List<SiegeActionRecord> list = entry.getValue();
			SiegeActionRecord first = list.get(0);

			info.addSectionHeading(first.marketName + " (was size " + first.sizeBefore + ")",
					com.fs.starfarer.api.ui.Alignment.MID, opad);

			for (SiegeActionRecord rec : list) {
				totalLosses += rec.estMarinesLost;
				StringBuilder line = new StringBuilder(BULLET + rec.action);
				List<String> hl = new ArrayList<String>();
				List<Color> hlColors = new ArrayList<Color>();
				if (rec.targets != null) {
					line.append(" - %s");
					hl.add(rec.targets);
					hlColors.add(h);
				}
				if (!rec.success) {
					line.append(": %s");
					hl.add("repulsed");
					hlColors.add(neg);
				} else if (rec.disruptDays > 0) {
					line.append(", disrupted %s");
					hl.add("~" + rec.disruptDays + " days");
					hlColors.add(h);
				}
				if (rec.estMarinesLost > 0) {
					line.append(", est. %s marines lost");
					hl.add("" + rec.estMarinesLost);
					hlColors.add(neg);
				}
				if (rec.destroyed) {
					line.append(". %s");
					hl.add("Colony destroyed.");
					hlColors.add(pos);
				}
				info.addPara(line.toString(), 3f, hlColors.toArray(new Color[0]),
						hl.toArray(new String[0]));
			}

			// current state, read live at view time
			MarketAPI live = ThreatIncData.resolveColonyMarket(entry.getKey());
			if (live == null) {
				info.addPara("Current state: %s", 3f, Misc.getTextColor(), pos,
						"destroyed - the strata are cold");
			} else {
				float health = ThreatIncData.lastHealth(live.getId());
				float decline = ThreatIncData.declineProgress(live.getId());
				String grade;
				if (health < ThreatIncConfig.declineHealthThreshold()) grade = "collapsing";
				else if (health < ThreatIncConfig.growthStallHealth()) grade = "critical";
				else if (health < ThreatIncConfig.growthFullHealth()) grade = "strained";
				else grade = "nominal";
				Color gradeColor = "collapsing".equals(grade) || "critical".equals(grade)
						? neg : h;
				if (decline > 0f) {
					info.addPara("Current state: size %s, hive vitality %s, decline %s "
							+ "toward the next stratum lost.", 3f, Misc.getTextColor(),
							gradeColor, "" + live.getSize(), grade,
							(int) (decline * 100f) + "%");
				} else {
					info.addPara("Current state: size %s, hive vitality %s.", 3f,
							Misc.getTextColor(), gradeColor, "" + live.getSize(), grade);
				}
			}
		}

		if (totalLosses > 0) {
			info.addPara("Estimated total marine casualties across the operation: %s. "
					+ "Ground-action figures are expedition estimates, not confirmed counts.",
					opad, neg, "" + totalLosses);
		}
	}
}
