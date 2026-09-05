package threatinc;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;

/**
 * The War footing's DEMAND (docs/economy-coherence.md rule 2): a hidden
 * structure on every colony of a mobilised faction that wants marines, heavy
 * armaments, fuel and supplies at the colony's highest existing demand for
 * each plus warFootingDemandUnits (scaled by size). Vanilla's demand for a
 * commodity is the highest single industry's figure, not a sum
 * (CommodityOnMarket.updateMaxSupplyAndDemand), so "add N units" has to be
 * declared this way, and a market condition - which has no demand of its
 * own - cannot do it. {@link WarFootingCondition} is the visible face; this
 * is the plumbing: never shown, never buildable, never disrupted, never
 * raided, no upkeep. Added and removed by {@link ThreatReserves#syncWarFooting}.
 */
public class WarFootingDemand extends BaseIndustry {

	/** Modifier id of this structure's demand on each commodity. */
	public static final String MOD_ID = "threatinc_war_footing";

	/** Units the War footing adds at this colony: the knob at size 5, scaled by size and rounded up. */
	public static int unitsFor(MarketAPI market) {
		float knob = ThreatIncConfig.warFootingDemandUnits();
		if (market == null || knob <= 0f) return 0;
		return (int) Math.ceil(knob * market.getSize() / 5f);
	}

	@Override
	public void apply() {
		super.apply(true);
		declare();
	}

	@Override
	public void unapply() {
		super.unapply();
	}

	/** Re-declares the demand from the colony's current figures; returns whether anything changed. */
	public boolean refresh() {
		return declare();
	}

	protected boolean declare() {
		if (market == null) return false;
		int units = unitsFor(market);
		boolean changed = false;
		for (String c : ThreatReserves.COMMODITIES) {
			int wanted = units <= 0 ? 0 : othersMax(c) + units;
			StatMod mod = getDemand(c).getQuantity().getFlatStatMod(MOD_ID);
			int current = mod == null ? 0 : (int) mod.value;
			if (wanted == current) continue;
			demand(MOD_ID, c, wanted, "War footing");
			changed = true;
		}
		return changed;
	}

	/** The highest demand any OTHER industry here declares for the commodity. */
	protected int othersMax(String commodityId) {
		int max = 0;
		for (Industry ind : market.getIndustries()) {
			if (ind == this) continue;
			for (MutableCommodityQuantity q : ind.getAllDemand()) {
				if (!commodityId.equals(q.getCommodityId())) continue;
				max = Math.max(max, q.getQuantity().getModifiedInt());
			}
		}
		return max;
	}

	@Override
	public boolean isHidden() {
		return true;
	}

	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}

	@Override
	public boolean canBeDisrupted() {
		return false;
	}

	@Override
	public float getPatherInterest() {
		return 0f;
	}
}
