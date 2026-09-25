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
 * year; ours is a siege slice that stops at the orbital floor, so without
 * this the only way past the floor was a ground front. Not with Nexerelin
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
 */
public class ThreatFortificationRaids implements GroundRaidObjectivesListener, MarineLossesStatModifier {

	/** Market memory + industry id: a raid took this structure's clock past the orbital floor; expires with the clock. */
	public static final String RAIDED_KEY = "$threatinc_raidedPastFloor_";

	public void modifyRaidObjectives(MarketAPI market, SectorEntityToken entity,
			List<GroundRaidObjectivePlugin> objectives, RaidType type, int marineTokens, int priority) {
		// after vanilla's own list (priority 0), so anything it already offers is left alone
		if (priority != 1 || type != RaidType.DISRUPT || market == null) return;
		if (!ThreatIncConfig.enabled() || ThreatNexCompat.nexEnabled()) return;
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
			deep += curr instanceof FortificationRaid ? w * depthMult(market, ((FortificationRaid) curr).getSource(), n) : w;
		}
		if (base <= 0f || deep <= base * 1.001f) return;
		stat.modifyMult("threatinc_fortDepth", deep / base, "Deep raid on the fortifications");
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
		float weight = ThreatSiegeMalus.bonusOf(ind.getId()) * ThreatSiegeMalus.condition(market, ind)
				* ThreatSiegeMalus.deficitMult(ind) / ThreatSiegeMalus.bonusOf(Industries.GROUNDDEFENSES);
		return 1f + ((float) Math.pow(n, k) - 1f) * pressure * weight;
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

	/** Whether a raid put this structure's clock where it is: the orbital floor does not hold it up (ThreatSiegeMalus.condition). */
	public static boolean raided(Industry ind) {
		return ind != null && ind.getMarket() != null
				&& ind.getMarket().getMemoryWithoutUpdate().getBoolean(RAIDED_KEY + ind.getId());
	}

	/**
	 * Vanilla's disrupt objective with the knob's danger in place of the
	 * spec's empty one, the days added in full, and every read of the clock
	 * gated on the real state (ThreatGroundFronts.siegeDisruptDays): the
	 * tactical bombardment's revert leaves a ghost expire on a structure it
	 * did not touch, and vanilla's raw read would add the raid on top of it.
	 */
	public static class FortificationRaid extends DisruptIndustryRaidObjectivePluginImpl {

		public FortificationRaid(MarketAPI market, Industry target) {
			super(market, target);
		}

		@Override
		public RaidDangerLevel getDangerLevel() {
			return danger();
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
			// the depth toll, in this garrison's numbers (ThreatFortificationRaids.depthMult)
			int n = Math.max(1, marinesAssigned);
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

		@Override
		public float getBaseDisruptDuration(int marines) {
			if (marines <= 0) return 0f;
			return marines * danger().disruptionDays;
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
				MemoryAPI mem = market.getMemoryWithoutUpdate();
				if (total > ThreatGroundFronts.siegeFloorDays(market)) {
					mem.set(RAIDED_KEY + source.getId(), true, total);
				}
				// a raid on the guns is a siege: the structure loses its bonus in
				// proportion to its clock, not whole as vanilla strips it
				ThreatGroundFronts.Theatre theatre = ThreatGroundFronts.Theatre.of(market);
				if (mem.getExpire(ThreatGroundFronts.BESIEGED_FLAG) < theatre.wearDays()) {
					mem.set(ThreatGroundFronts.BESIEGED_FLAG, true, theatre.wearDays());
				}
				ThreatGroundFronts.syncSiegeState(market);
				market.reapplyIndustries();
			}

			text.addPara("The raid was successful in disrupting " + source.getCurrentName() + " operations."
					+ " It will take at least %s days for normal operations to resume.",
					Misc.getHighlightColor(), "" + (int) Math.round(source.getDisruptedDays()));
			return (int) (dur * DISRUPTION_DAYS_XP_MULT);
		}
	}
}
