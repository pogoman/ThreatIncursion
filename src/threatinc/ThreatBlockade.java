package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * BLOCKADED BY THE THREAT (docs/ground-war.md "Blockade"): Threat warships
 * holding the space over a human colony cut its shipping the way the Persean
 * League's blockade does - vanilla's Blockaded condition, per colony instead
 * of per system. Accessibility only: vanilla turns it into lost import
 * capacity (accessibilityPerUnitShipping) and export income by itself.
 *
 * <p>Strength follows vanilla's BlockadeFGI.getAccessibilityPenalty: Threat
 * points within ORBIT_HOLD_RANGE against the points of every fleet there
 * not hostile to the colony (station, patrols, a Guard, the player). Under
 * 0.75 of them nothing, under 1.25 half, else the full blockadeAccessPenalty.
 * Refreshed on the ground-front poll ({@link #sweep}).
 */
public class ThreatBlockade extends BaseMarketConditionPlugin {

	public static final String ID = "threatinc_blockaded";
	/** Market memory: the penalty in force (0..1). */
	public static final String KEY = "$threatinc_blockadePenalty";

	/** Every human colony: add, resize or lift its blockade. */
	public static void sweep() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market == null || !market.isInEconomy() || market.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			sync(market);
		}
	}

	public static void sync(MarketAPI market) {
		float penalty = ThreatMapFog.hidden(market) ? 0f : penalty(market);
		float had = market.getMemoryWithoutUpdate().getFloat(KEY);
		boolean has = market.hasCondition(ID);
		if (penalty <= 0f) {
			market.getMemoryWithoutUpdate().unset(KEY);
			if (has) market.removeCondition(ID);
			return;
		}
		market.getMemoryWithoutUpdate().set(KEY, penalty);
		if (!has) market.addCondition(ID);
		else if (had != penalty) market.reapplyConditions();
	}

	/** Vanilla's blockade penalty for the Threat's points over the world against its defenders'. */
	public static float penalty(MarketAPI market) {
		float full = ThreatIncConfig.blockadeAccessPenalty();
		if (full <= 0f) return 0f;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return 0f;
		FactionAPI owner = market.getFaction();
		float threat = 0f;
		float defence = 0f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (Misc.isTrader(fleet) || Misc.isSmuggler(fleet) || Misc.isScavenger(fleet)) continue;
			if (!ThreatGroundFronts.nearWorld(fleet, market)) continue;
			if (Factions.THREAT.equals(fleet.getFaction().getId())) threat += fleet.getFleetPoints();
			else if (!fleet.getFaction().isHostileTo(owner)) defence += fleet.getFleetPoints();
		}
		if (threat <= 0f) return 0f;
		if (threat < defence * 0.75f) return 0f;
		if (threat < defence * 1.25f) return full * 0.5f;
		return full;
	}

	@Override
	public void apply(String id) {
		float penalty = market.getMemoryWithoutUpdate().getFloat(KEY);
		if (penalty <= 0f) {
			market.getAccessibilityMod().unmodifyFlat(id);
			return;
		}
		market.getAccessibilityMod().modifyFlat(id, -penalty, "Blockaded by the Threat");
	}

	@Override
	public void unapply(String id) {
		market.getAccessibilityMod().unmodifyFlat(id);
	}

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		float penalty = market.getMemoryWithoutUpdate().getFloat(KEY);
		Color neg = Misc.getNegativeHighlightColor();
		tooltip.addPara("%s accessibility.", 10f, neg, "-" + Math.round(penalty * 100f) + "%");
	}
}
