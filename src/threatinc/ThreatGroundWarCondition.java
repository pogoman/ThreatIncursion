package threatinc;

import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;

/**
 * GROUND WAR: the infestation's output cut on a world an enemy army stands on
 * (docs/ground-war.md "Sieges against human factions") - added to a non-Threat
 * market while a front stands on it and removed with it
 * ({@link ThreatGroundFronts#syncSiegeState}). Hidden from the colony screen
 * (the user, 2026-10-31): the war board's fronts table shows the front.
 */
public class ThreatGroundWarCondition extends BaseMarketConditionPlugin {

	public static final String ID = "threatinc_ground_war";

	/**
	 * THE INFESTATION. Under THE OCCUPATION (2026-10-27, ThreatOccupation) every industry
	 * the swarm's front has claimed runs for the swarm: its output x0 to the market, the
	 * units it made read into the front (eatenUnits) for the growth. With the occupation
	 * off, phase E's shape: every industry's output x (1 - the seized share).
	 */
	@Override
	public void apply(String id) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (ThreatOccupation.occupies(front, market)) {
			List<String> claims = ThreatOccupation.claims(front);
			float units = 0f;
			for (Industry ind : market.getIndustries()) {
				boolean claimed = claims.contains(ind.getId());
				float mine = 0f;
				for (MutableCommodityQuantity q : ind.getAllSupply()) {
					if (ThreatOccupation.MARK.equals(q.getCommodityId())) continue;
					q.getQuantity().unmodifyMult(id);
					if (!claimed) continue;
					mine += Math.max(0f, q.getQuantity().getModifiedValue());
					q.getQuantity().modifyMult(id, 0f, "Threat infestation");
				}
				units += mine;
				// THE MARKER (the user, 2026-10-27, "it shows its producing threat swarm"): a claimed
				// industry produces ThreatOccupation.MARK at the units it feeds the swarm - the
				// Fabrication Core icon in its production list (the real outputs, cut to 0, drop
				// off the list: BaseIndustry.clearUnmodified)
				if (claimed) {
					ind.getSupply(ThreatOccupation.MARK).getQuantity().modifyFlat(id, Math.max(1f, Math.round(mine)), "Threat infestation");
				} else {
					unmark(ind, id);
				}
			}
			// a conditions-only reapply finds the cut outputs already dropped: keep the last full read
			if (units > 0f || claims.isEmpty()) front.eatenUnits = units;
			return;
		}
		float share = ThreatGroundFronts.infestShare(market, front);
		for (Industry ind : market.getIndustries()) {
			for (MutableCommodityQuantity q : ind.getAllSupply()) {
				if (share > 0f) q.getQuantity().modifyMult(id, 1f - share, "Threat infestation");
				else q.getQuantity().unmodifyMult(id);
			}
		}
	}

	@Override
	public void unapply(String id) {
		for (Industry ind : market.getIndustries()) {
			for (MutableCommodityQuantity q : ind.getAllSupply()) q.getQuantity().unmodifyMult(id);
			unmark(ind, id);
		}
	}

	/** Takes the marker's production off an industry without creating the entry (clearUnmodified drops it). */
	protected static void unmark(Industry ind, String id) {
		for (MutableCommodityQuantity q : ind.getAllSupply()) {
			if (ThreatOccupation.MARK.equals(q.getCommodityId())) q.getQuantity().unmodifyFlat(id);
		}
	}

	/** No icon on the colony screen (the user, 2026-10-31): the war board shows the front. */
	@Override
	public boolean showIcon() {
		return false;
	}
}
