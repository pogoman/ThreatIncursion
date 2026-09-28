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
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidDangerLevel;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.RaidType;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The one raid on a war structure left (docs/suppression-balance.md v2 section
 * 2, 2026-09-28): a hive's Fabrication Core. It is not a defence - the
 * fortifications, the Swarm Nexus and the planetary shield are unraidable, as
 * vanilla has always had a colony's (marines reach the guns only by landing,
 * on a ground front) - so vanilla's disrupt objective on the Core is swapped
 * for one whose days add in full and whose marines lost grow with the tokens
 * put on it: (tokens ^ {@code coreRaidDepthLoss} - 1) x the defence's share of
 * the fight x {@code coreRaidDepthWeight}, worn with the Core's fabrication.
 * Vanilla's item raids elsewhere are untouched.
 */
public class ThreatFortificationRaids implements GroundRaidObjectivesListener, MarineLossesStatModifier {

	public void modifyRaidObjectives(MarketAPI market, SectorEntityToken entity,
			List<GroundRaidObjectivePlugin> objectives, RaidType type, int marineTokens, int priority) {
		// after vanilla's own list (priority 0), so anything it already offers is left alone
		if (priority != 1 || type != RaidType.DISRUPT || market == null) return;
		if (!ThreatIncConfig.enabled()) return;
		// the Core: vanilla's objective, swapped for one that quotes the toll
		for (int i = 0; i < objectives.size(); i++) {
			GroundRaidObjectivePlugin curr = objectives.get(i);
			if (curr instanceof OrganRaid || !(curr instanceof DisruptIndustryRaidObjectivePluginImpl)) continue;
			Industry ind = ((DisruptIndustryRaidObjectivePluginImpl) curr).getSource();
			if (isCore(ind)) objectives.set(i, new OrganRaid(market, ind));
		}
	}

	public void reportRaidObjectivesAchieved(RaidResultData data, InteractionDialogAPI dialog,
			java.util.Map<String, MemoryAPI> memoryMap) {
	}

	/**
	 * The depth toll: vanilla averages the objectives' danger per token, so
	 * five tokens on one objective cost what one does. Here the Core's weight
	 * grows by (tokens ^ depthLoss - 1) x the defence's share of the fight -
	 * defender / (raid + defender), the same odds vanilla sets the tokens by -
	 * so holding it down longer costs dear against a strong garrison and
	 * little against a weak one.
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
			deep += isCore(ind) ? w * depthMult(market, ind, n) : w;
		}
		if (base <= 0f || deep <= base * 1.001f) return;
		stat.modifyMult("threatinc_fortDepth", deep / base, "Deep raid on one structure");
	}

	/**
	 * What n tokens on the Core multiply its danger by: 1 + (n ^ depthLoss - 1)
	 * x the defence's share of the fight x the Core's weight as worn.
	 */
	public static float depthMult(MarketAPI market, Industry ind, int n) {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (market == null || ind == null || player == null || n <= 1) return 1f;
		float k = Math.max(0f, ThreatIncConfig.coreRaidDepthLoss());
		float raid = playerRaidStr(player);
		float defender = MarketCMD.getDefenderStr(market);
		float pressure = defender / Math.max(1f, raid + defender);
		return 1f + ((float) Math.pow(n, k) - 1f) * pressure
				* ThreatColonyManager.wornDownFactor(ind, Math.max(0f, ThreatIncConfig.coreRaidDepthWeight()));
	}

	/**
	 * The player's raid strength as vanilla's raid menu reckons it: the
	 * marines aboard plus ground support up to their number, through the
	 * fleet's planetary-operations bonus. Not {@code MarketCMD.getRaidStr},
	 * which is the AI's figure (a quarter of the fleet's personnel) and read
	 * the fight wrong (asymmetry 5 of docs/suppression-balance.md).
	 */
	public static float playerRaidStr(CampaignFleetAPI fleet) {
		float marines = fleet.getCargo().getMarines();
		float support = Math.min(marines, Misc.getFleetwideTotalMod(fleet, Stats.FLEET_GROUND_SUPPORT, 0f));
		return fleet.getStats().getDynamic().getMod(Stats.PLANETARY_OPERATIONS_MOD)
				.computeEffective(marines + support);
	}

	/** The hive's Fabrication Core, the objective the depth toll covers. */
	private static boolean isCore(Industry ind) {
		return ind != null && ThreatColonyManager.FABRICATION_CORE.equals(ind.getId());
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
	 * the raid's days.
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
			if (market != null) market.reapplyIndustries();

			text.addPara("The raid was successful in disrupting " + source.getCurrentName() + " operations."
					+ " It will take at least %s days for normal operations to resume.",
					Misc.getHighlightColor(), "" + (int) Math.round(source.getDisruptedDays()));
			return (int) (dur * DISRUPTION_DAYS_XP_MULT);
		}
	}

	/** A hive's Fabrication Core: vanilla's danger, the days in full, the depth toll quoted. */
	public static class OrganRaid extends FullDaysRaid {

		public OrganRaid(MarketAPI market, Industry target) {
			super(market, target);
		}
	}
}
