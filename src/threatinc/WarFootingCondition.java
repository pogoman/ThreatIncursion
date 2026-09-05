package threatinc;

import java.awt.Color;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.impl.campaign.submarkets.BaseSubmarketPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * WAR FOOTING (docs/economy-coherence.md): the colony screen's face of a
 * mobilised faction's war. Its demand rides on the hidden
 * {@link WarFootingDemand} structure; this condition explains it, in
 * vanilla's own units beside the reserve's item counts (rule 7): the war
 * reserve, what banks and why, what the depot is covering (rule 3), and that
 * selling here does the same thing a sale always does (rule 4). Added and
 * removed by {@link ThreatReserves#syncWarFooting}.
 */
public class WarFootingCondition extends BaseMarketConditionPlugin {

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		int units = WarFootingDemand.unitsFor(market);
		FactionAPI faction = market.getFaction();
		String who = faction == null ? "The faction" : faction.isPlayerFaction() ? "Your faction"
				: Misc.ucFirst(faction.getDisplayNameWithArticle());
		tooltip.addPara(who + " is mobilised against the Threat. This colony's demand for "
				+ "marines, heavy armaments, fuel and supplies is raised by %s above what its "
				+ "industries want, and its war reserve - banked from whatever it has above "
				+ "demand - is spent on its own shortages before anything sails.", opad, h,
				units + (units == 1 ? " unit" : " units"));
		for (String c : ThreatReserves.COMMODITIES) {
			addCommodityLine(tooltip, market, c, 3f);
		}
		tooltip.addPara("Selling any of these here raises its availability for %s days, the way "
				+ "every sale does: it ends a shortage, spares the reserve, and anything above "
				+ "demand is banked into the war effort. Buying does the reverse.", opad, h,
				"" + (int) BaseSubmarketPlugin.TRADE_IMPACT_DAYS);
	}

	/**
	 * One commodity's line, vanilla's units beside the reserve's count. Shared
	 * with the war board's faction view (ThreatFactionView.colonyTooltip).
	 */
	public static void addCommodityLine(TooltipMakerAPI tooltip, MarketAPI market, String c,
			float pad) {
		ThreatReserves.CommodityStatus s = ThreatReserves.status(market, c);
		if (s == null) return;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();
		String name = Misc.ucFirst(ThreatReserves.label(c));
		String stock = Misc.getWithDGS((int) s.stock);
		if (s.covering) {
			tooltip.addPara(name + ": %s in reserve. The depot covers a shortage of %s (%s "
					+ "available with it, %s demanded): %s issued, %s left.", pad, h, stock,
					units(s.deficit), "" + s.available, "" + s.demand,
					Misc.getWithDGS((int) s.coverQty), days(s.coverDaysLeft));
		} else if (s.exhausted && s.floor > 0f && s.stock - s.floor < s.stock
				* ThreatIncConfig.reserveShortageCoverFraction()) {
			// the garrison's floor, not the cover fraction, is what stops the issue
			tooltip.addPara(name + ": %s in reserve. Short %s (%s available, %s demanded): "
					+ "%s - a unit is %s and the depot keeps %s for the garrison, so the "
					+ "shortage stands.", pad, neg, stock, units(s.deficit),
					"" + s.available, "" + s.demand, "depot too low to issue",
					Misc.getWithDGS((int) s.econUnit), Misc.getWithDGS((int) s.floor));
		} else if (s.exhausted) {
			// the stock may not be zero: the depot spends at most the cover
			// fraction of it per issue, and a unit costs the econ unit
			tooltip.addPara(name + ": %s in reserve. Short %s (%s available, %s demanded): "
					+ "%s - a unit is %s and the depot spends at most %s of its stock per "
					+ "issue, so the shortage stands.", pad, neg, stock, units(s.deficit),
					"" + s.available, "" + s.demand, "depot too low to issue",
					Misc.getWithDGS((int) s.econUnit),
					(int) Math.round(ThreatIncConfig.reserveShortageCoverFraction() * 100f) + "%");
		} else if (s.deficit > 0) {
			tooltip.addPara(name + ": %s in reserve. Short %s (%s available, %s demanded): "
					+ "the depot is about to cover it.", pad, h, stock, units(s.deficit),
					"" + s.available, "" + s.demand);
		} else if (s.surplus > 0f) {
			tooltip.addPara(name + ": %s in reserve. %s surplus (%s available, %s demanded): "
					+ "banking %s a month, cap %s.", pad, pos, stock, units((int) s.surplus),
					"" + s.available, "" + s.demand, Misc.getWithDGS((int) s.per30),
					Misc.getWithDGS((int) s.cap));
		} else if (s.per30 > 0f) {
			tooltip.addPara(name + ": %s in reserve. Balanced (%s available, %s demanded): only "
					+ "the militia trickle banks, %s a month.", pad, gray, stock,
					"" + s.available, "" + s.demand, Misc.getWithDGS((int) s.per30));
		} else {
			tooltip.addPara(name + ": %s in reserve. Balanced (%s available, %s demanded): "
					+ "nothing to bank.", pad, gray, stock, "" + s.available, "" + s.demand);
		}
	}

	protected static String units(int n) {
		return n + (n == 1 ? " unit" : " units");
	}

	protected static String days(float d) {
		int n = (int) Math.ceil(d);
		return n + (n == 1 ? " day" : " days");
	}
}
