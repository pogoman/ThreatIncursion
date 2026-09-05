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
		tooltip.addPara(who + " is mobilised against the Threat. Demand for marines, heavy "
				+ "armaments, fuel and supplies is raised by %s here; the war reserve covers "
				+ "this colony's own shortages before anything sails.", opad, h,
				units + (units == 1 ? " unit" : " units"));
		for (String c : ThreatReserves.COMMODITIES) {
			addCommodityLine(tooltip, market, c, 3f);
		}
		tooltip.addPara("Selling here raises availability for %s days, as any sale does; "
				+ "anything above demand banks into the reserve.", opad, h,
				"" + (int) BaseSubmarketPlugin.TRADE_IMPACT_DAYS);
	}

	/**
	 * One commodity's line: the stock banked, then its state in vanilla's
	 * units. What is true now, not how the depot works (docs/design-theory.md
	 * 8.6 and docs/economy-coherence.md have that). Shared with the war
	 * board's faction view (ThreatFactionView.colonyTooltip).
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
			tooltip.addPara(name + ": %s banked. Short %s; depot issuing %s for %s.", pad, h,
					stock, units(s.deficit), Misc.getWithDGS((int) s.coverQty), days(s.coverDaysLeft));
		} else if (s.exhausted && s.floor > 0f && s.stock - s.floor < s.stock
				* ThreatIncConfig.reserveShortageCoverFraction()) {
			// the garrison's floor, not the cover fraction, is what stops the issue
			tooltip.addPara(name + ": %s banked. Short %s; depot too low to issue, %s kept for "
					+ "the garrison.", pad, neg, stock, units(s.deficit),
					Misc.getWithDGS((int) s.floor));
		} else if (s.exhausted) {
			tooltip.addPara(name + ": %s banked. Short %s; depot too low to issue.", pad, neg,
					stock, units(s.deficit));
		} else if (s.deficit > 0) {
			tooltip.addPara(name + ": %s banked. Short %s; cover due.", pad, h, stock,
					units(s.deficit));
		} else if (s.surplus > 0f) {
			tooltip.addPara(name + ": %s banked. Surplus %s; +%s a month, cap %s.", pad, pos,
					stock, units((int) s.surplus), Misc.getWithDGS((int) s.per30),
					Misc.getWithDGS((int) s.cap));
		} else if (s.per30 > 0f) {
			tooltip.addPara(name + ": %s banked. Balanced; militia +%s a month.", pad, gray,
					stock, Misc.getWithDGS((int) s.per30));
		} else {
			tooltip.addPara(name + ": %s banked. Balanced; nothing to bank.", pad, gray, stock);
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
