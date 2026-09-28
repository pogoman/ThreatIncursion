package threatinc;

import java.awt.Color;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.GroundRaidObjectivesListener;
import com.fs.starfarer.api.campaign.listeners.MarineLossesStatModifier;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.impl.campaign.graid.DisruptIndustryRaidObjectivePluginImpl;
import com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidDangerLevel;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidType;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A human colony's fortifications (Ground Defenses, Heavy Batteries, Patrol
 * HQ, Military Base, High Command) on the player's disrupt-raid list
 * (docs/ground-war.md "Raiding the fortifications", 2026-09-25). Vanilla tags
 * them unraidable because its tactical bombardment knocks them out for a
 * year; ours is a siege slice scaled by the odds, so raiding is a second way
 * to wear them - marines rather than hulls. Not with Nexerelin
 * loaded - its own bombardment runs human colonies' menus and still writes
 * vanilla's year. Hive defences are raidable in industries.csv already.
 *
 * <p>Wearing them down beats knocking them out: each raid's days add in full
 * (no vanilla diminishing on a clock already running), the world counts as
 * besieged so the structure loses its bonus in proportion rather than whole,
 * and the marines lost grow with the tokens on one fortification (to the power
 * {@code fortificationRaidDepthLoss}) scaled by the defence's share of the
 * fight and by what the structure adds to that defence - against a strong
 * garrison's batteries one deep raid costs far more than the same days in
 * shallow ones; against a weak one, or a Patrol HQ, depth costs little.
 *
 * <p>A hive's Swarm Nexus and Fabrication Core take the same depth toll and
 * their days add in full too (2026-09-27/28): vanilla lists them already, so
 * its objective is swapped for an OrganRaid.
 */
public class ThreatFortificationRaids implements GroundRaidObjectivesListener, MarineLossesStatModifier {

	public void modifyRaidObjectives(MarketAPI market, SectorEntityToken entity,
			List<GroundRaidObjectivePlugin> objectives, RaidType type, int marineTokens, int priority) {
		// after vanilla's own list (priority 0), so anything it already offers is left alone
		if (priority != 1 || type != RaidType.DISRUPT || market == null) return;
		if (!ThreatIncConfig.enabled()) return;
		// the hive's organs: vanilla's objective, swapped for one that quotes the toll
		for (int i = 0; i < objectives.size(); i++) {
			GroundRaidObjectivePlugin curr = objectives.get(i);
			if (curr instanceof OrganRaid || !(curr instanceof DisruptIndustryRaidObjectivePluginImpl)) continue;
			Industry ind = ((DisruptIndustryRaidObjectivePluginImpl) curr).getSource();
			if (isOrgan(ind)) objectives.set(i, new OrganRaid(market, ind));
		}
		if (ThreatNexCompat.nexEnabled()) return;
		if (ThreatGroundFronts.Theatre.of(market) != ThreatGroundFronts.COLONY) return;
		for (Industry ind : ThreatSiegeMalus.fortifications(market)) {
			if (ind.getSpec() == null || !ind.getSpec().hasTag(Industries.TAG_UNRAIDABLE)) continue;
			if (listed(objectives, ind)) continue;
			FortificationRaid raid = new FortificationRaid(market, ind);
			if (raid.getBaseDisruptDuration(marineTokens) <= 0f) continue;
			objectives.add(raid);
		}
	}

	public void reportRaidObjectivesAchieved(RaidResultData data, InteractionDialogAPI dialog,
			java.util.Map<String, MemoryAPI> memoryMap) {
	}

	/**
	 * The depth toll: vanilla averages the objectives' danger per token, so
	 * five tokens on one objective cost what one does. Here a fortification
	 * objective's weight grows by (tokens ^ depthLoss - 1) x the defence's
	 * share of the fight - defender / (raid + defender), the same odds vanilla
	 * sets the tokens by - so holding the guns down longer costs dear against
	 * a strong garrison and little against a weak one.
	 */
	public void modifyMarineLossesStatPreRaid(MarketAPI market, List<GroundRaidObjectivePlugin> objectives,
			MutableStat stat) {
		if (market == null || objectives == null) return;
		float base = 0f;
		float deep = 0f;
		for (GroundRaidObjectivePlugin curr : objectives) {
			int n = curr.getMarinesAssigned();
			if (n <= 0 || curr.getDangerLevel() == null) continue;
			float w = curr.getDangerLevel().marineLossesMult * n;
			base += w;
			Industry ind = curr instanceof DisruptIndustryRaidObjectivePluginImpl
					? ((DisruptIndustryRaidObjectivePluginImpl) curr).getSource() : null;
			deep += curr instanceof FortificationRaid || isOrgan(ind) ? w * depthMult(market, ind, n) : w;
		}
		if (base <= 0f || deep <= base * 1.001f) return;
		stat.modifyMult("threatinc_fortDepth", deep / base, "Deep raid on one structure");
	}

	/**
	 * What n tokens on one fortification multiply its danger by: 1 + (n ^
	 * depthLoss - 1) x the defence's share of the fight x what the structure
	 * adds to the defence now, against Ground Defenses' x2 intact - so Heavy
	 * Batteries weigh double, a Military Base a fifth, and a worn or starved
	 * structure less than a whole one.
	 */
	public static float depthMult(MarketAPI market, Industry ind, int n) {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (market == null || ind == null || player == null || n <= 1) return 1f;
		float k = Math.max(0f, ThreatIncConfig.fortificationRaidDepthLoss());
		float pressure = Math.max(0f, Math.min(1f, 1f - MarketCMD.getRaidEffectiveness(market, player)));
		return 1f + ((float) Math.pow(n, k) - 1f) * pressure * depthWeight(market, ind);
	}

	/**
	 * The structure's weight in the toll, Ground Defenses intact = 1. The
	 * Nexus by its defence bonus as worn (x1.5 intact: half); the Core adds
	 * no defence, so a knob stands in, worn with its fabrication.
	 */
	private static float depthWeight(MarketAPI market, Industry ind) {
		String id = ind.getId();
		if (ThreatColonyManager.SWARM_NEXUS.equals(id)) {
			return Math.max(0f, ThreatIncConfig.nexusDefenseBonus()) * ThreatColonyManager.disruptedDefenseResilience(ind);
		}
		if (ThreatColonyManager.FABRICATION_CORE.equals(id)) {
			return ThreatColonyManager.wornDownFactor(ind, Math.max(0f, ThreatIncConfig.coreRaidDepthWeight()));
		}
		return ThreatSiegeMalus.bonusOf(id) * ThreatSiegeMalus.condition(market, ind)
				* ThreatSiegeMalus.deficitMult(ind) / ThreatSiegeMalus.bonusOf(Industries.GROUNDDEFENSES);
	}

	/** A hive organ the depth toll covers. */
	private static boolean isOrgan(Industry ind) {
		return ind != null && (ThreatColonyManager.SWARM_NEXUS.equals(ind.getId())
				|| ThreatColonyManager.FABRICATION_CORE.equals(ind.getId()));
	}

	private static boolean listed(List<GroundRaidObjectivePlugin> objectives, Industry ind) {
		for (GroundRaidObjectivePlugin curr : objectives) {
			if (curr instanceof DisruptIndustryRaidObjectivePluginImpl
					&& ((DisruptIndustryRaidObjectivePluginImpl) curr).getSource() == ind) {
				return true;
			}
		}
		return false;
	}

	/** The raid's danger: vanilla gives these structures none, so the knob does. */
	public static RaidDangerLevel danger() {
		try {
			return RaidDangerLevel.valueOf(ThreatIncConfig.fortificationRaidDanger().trim().toUpperCase());
		} catch (Throwable t) {
			return RaidDangerLevel.HIGH;
		}
	}

	/** The depth toll, in this garrison's numbers (depthMult). */
	private static void addDepthTooltip(TooltipMakerAPI t, MarketAPI market, Industry source, int marines) {
		int n = Math.max(1, marines);
		int next = n + 1;
		Color h = Misc.getHighlightColor();
		if (n > 1) {
			t.addPara("%s tokens on this structure: its danger x%s. Raiding it a token at a time loses fewer marines.",
					10f, Misc.getNegativeHighlightColor(), "" + n, fmt(depthMult(market, source, n)));
		} else {
			t.addPara("Each token added here raises its danger: x%s at %s, x%s at %s.", 10f, h,
					fmt(depthMult(market, source, next)), "" + next,
					fmt(depthMult(market, source, next + 2)), "" + (next + 2));
		}
	}

	private static String fmt(float mult) {
		return String.format("%.1f", mult);
	}

	/**
	 * Vanilla's disrupt objective with the days added in full and every read
	 * of the clock gated on the real state (ThreatGroundFronts.siegeDisruptDays).
	 * Vanilla shrinks a raid on a clock already running (dur x dur / (dur +
	 * already)), so a structure carrying a long clock took a few percent of
	 * the raid's days; and the tactical bombardment's revert leaves a ghost
	 * expire on a structure it did not touch, which vanilla's raw read would
	 * add the raid on top of.
	 */
	public static class FullDaysRaid extends DisruptIndustryRaidObjectivePluginImpl {

		public FullDaysRaid(MarketAPI market, Industry target) {
			super(market, target);
		}

		@Override
		public String getQuantityString(int marines) {
			float days = ThreatGroundFronts.siegeDisruptDays(source);
			if (days > 0 && days < 1) days = 1;
			days = Math.round(days);
			return days > 0 ? "" + (int) days : "";
		}

		@Override
		public void createTooltip(TooltipMakerAPI t, boolean expanded) {
			super.createTooltip(t, expanded);
			addDepthTooltip(t, market, source, marinesAssigned);
		}

		@Override
		public float getBaseDisruptDuration(int marines) {
			RaidDangerLevel level = getDangerLevel();
			if (marines <= 0 || level == null) return 0f;
			return marines * level.disruptionDays;
		}

		@Override
		public int performRaid(CargoAPI loot, Random random, float lootMult, TextPanelAPI text) {
			if (marinesAssigned <= 0) return 0;
			float dur = getBaseDisruptDuration(marinesAssigned);
			dur *= lootMult;
			dur *= StarSystemGenerator.getNormalRandom(random, 1f, 1.1f);
			if (dur < 2) dur = 2;
			float total = ThreatGroundFronts.siegeDisruptDays(source) + dur;
			source.setDisrupted(total);
			addedDisruptionDays = dur;

			MarketAPI market = source.getMarket();
			if (market != null) {
				afterRaid(market, total);
				market.reapplyIndustries();
			}

			text.addPara("The raid was successful in disrupting " + source.getCurrentName() + " operations."
					+ " It will take at least %s days for normal operations to resume.",
					Misc.getHighlightColor(), "" + (int) Math.round(source.getDisruptedDays()));
			return (int) (dur * DISRUPTION_DAYS_XP_MULT);
		}

		/** What the raid does to the world beyond the structure's clock. */
		protected void afterRaid(MarketAPI market, float total) {
		}
	}

	/** A hive's Swarm Nexus or Fabrication Core: vanilla's danger, the days in full, the depth toll quoted. */
	public static class OrganRaid extends FullDaysRaid {

		public OrganRaid(MarketAPI market, Industry target) {
			super(market, target);
		}
	}

	/** A human colony's fortification: the knob's danger in place of the spec's empty one. */
	public static class FortificationRaid extends FullDaysRaid {

		public FortificationRaid(MarketAPI market, Industry target) {
			super(market, target);
		}

		@Override
		public RaidDangerLevel getDangerLevel() {
			return danger();
		}

		@Override
		protected void afterRaid(MarketAPI market, float total) {
			MemoryAPI mem = market.getMemoryWithoutUpdate();
			// a raid on the guns is a siege: the structure loses its bonus in
			// proportion to its clock, not whole as vanilla strips it
			ThreatGroundFronts.Theatre theatre = ThreatGroundFronts.Theatre.of(market);
			if (mem.getExpire(ThreatGroundFronts.BESIEGED_FLAG) < theatre.wearDays()) {
				mem.set(ThreatGroundFronts.BESIEGED_FLAG, true, theatre.wearDays());
			}
			ThreatGroundFronts.syncSiegeState(market);
		}
	}
}
