package threatinc;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * GROUND WAR: the colony screen's face of an enemy army on this world
 * (docs/ground-war.md "Sieges against human factions"). The mirror of
 * {@link HiveVitalityCondition}'s front block, for the side being invaded -
 * added to a non-Threat market while a front stands on it and removed with it
 * ({@link ThreatGroundFronts#syncSiegeState}).
 *
 * <p>One line per fact the defender can act on: districts held and what they
 * cost, what the invader has seized, which defences are suppressed and how
 * far, the invader's strength against the garrison, the next counter-attack,
 * and the invader's supply. Nothing hidden - the defender is standing on the
 * ground being fought over.
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

	@Override
	public boolean hasCustomTooltip() {
		return true;
	}

	@Override
	protected void createTooltipAfterDescription(TooltipMakerAPI tooltip, boolean expanded) {
		if (market == null) return;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null) return;

		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = ThreatNotice.goodColor();

		int size = market.getSize();
		int held = Math.max(0, Math.min(size, front.strataHeld));
		tooltip.addPara("Districts held by the swarm: %s of %s.", opad, held > 0 ? neg : h,
				"" + held, "" + size);
		if (ThreatOccupation.occupies(front, market)) {
			// THE OCCUPATION (2026-10-27): the claims, the next, what they feed
			List<Industry> claimed = ThreatOccupation.claimed(market, front);
			if (!claimed.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (Industry ind : claimed) {
					if (names.length() > 0) names.append(", ");
					names.append(ind.getCurrentName());
				}
				tooltip.addPara("Claimed by the swarm: %s.", 3f, neg, names.toString());
			}
			Industry next = ThreatOccupation.next(market, front);
			if (next != null && held > 0) {
				tooltip.addPara("Next claim: %s in %s.", 3f, h, next.getCurrentName(),
						days(ThreatOccupation.daysToNextClaim(front)));
			}
			float grow = ThreatOccupation.growthPerDay(front);
			if (grow > 0f) {
				tooltip.addPara(ThreatOccupation.holdsAll(front, market)
						? "The swarm eats %s units a day: %s strata a day budding over the world."
						: "The swarm eats %s units a day: %s troops a day to its landing.", 3f, neg,
						ThreatStrata.fmt(front.eatenUnits), ThreatStrata.fmt(grow));
			}
		} else if (held > 0) {
			List<Industry> seized = ThreatGroundFronts.seizedIndustries(market);
			if (!seized.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (Industry ind : seized) {
					if (names.length() > 0) names.append(", ");
					names.append(ind.getCurrentName());
				}
				tooltip.addPara("Seized: %s.", 3f, neg, names.toString());
			}
			float share = ThreatGroundFronts.infestShare(market, front);
			if (share > 0f) {
				tooltip.addPara("Threat infestation: every industry's output x%s; the swarm grows %s strata a day on it.", 3f, neg,
						String.format("%.2f", 1f - share), ThreatStrata.fmt(ThreatGroundFronts.infestGrowthPerDay(market, front)));
			}
		}

		List<String> suppressed = ThreatSiegeMalus.suppressedLines(market);
		if (!suppressed.isEmpty()) {
			StringBuilder line = new StringBuilder();
			for (String s : suppressed) {
				if (line.length() > 0) line.append(", ");
				line.append(s);
			}
			tooltip.addPara("Defences suppressed to: %s.", 3f, h, line.toString());
		}

		if (ThreatShield.present(market)) {
			tooltip.addPara("Planetary shield at %s effect: it absorbs %s of incoming disruption.",
					3f, h, Math.round(ThreatShield.integrity(market) * 100f) + "%",
					Math.round(ThreatShield.absorb(market) * 100f) + "%");
		}

		float attacker = ThreatGroundFronts.effectiveStrength(front);
		float defender = ThreatGroundFronts.defenderStrength(market);
		float rate = ThreatGroundFronts.lineRate(front, market);
		float change = ThreatGroundFronts.daysToLineChange(front, market);
		tooltip.addPara("Swarm %s against the marines' %s.", 3f, h,
				Misc.getWithDGS(Math.round(attacker)), Misc.getWithDGS(Math.round(defender)));
		// THE LINE (2026-10-23): the word from the swarm's side and the next district
		tooltip.addPara("The swarm is %s" + (change < 0f ? "" : "; next " + ThreatGroundFronts.layerName(market)
				+ (rate > 0f ? " falls in " : " retaken in ") + days(change)) + ".", 3f,
				rate > 0f ? neg : pos, ThreatGroundFronts.lineState(front, market, true));

		// What that defence figure is actually made of (2026-09-08). Vanilla's
		// own Ground defenses readout on this screen shows the garrison stat
		// alone and never counts marines, so without this line the two numbers
		// disagree with nothing to explain them.
		float garrison = ThreatGroundFronts.colonyGarrison(market);
		float armed = ThreatReserves.armedMarines(market);
		if (armed > 0f) {
			tooltip.addPara("Of that, %s garrison and %s marines deployed, %s.", 3f, h,
					Misc.getWithDGS(Math.round(garrison)), Misc.getWithDGS(Math.round(armed)),
					ThreatMarineXP.colonyRank(market).toLowerCase());
		} else {
			tooltip.addPara("Of that, %s garrison and %s marines deployed.", 3f, h,
					Misc.getWithDGS(Math.round(garrison)), "no");
		}
		// reserves (not yet deployed) and any staging for a siege (the user, 2026-10-24)
		float stagingM = ThreatReserves.stagingMarines(market);
		float arming = ThreatReserves.stock(market.getId(),
				com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES) - armed - stagingM;
		if (arming > 1f) {
			tooltip.addPara("%s marines in reserve, arming.", 3f, h,
					Misc.getWithDGS(Math.round(arming)));
		}
		if (stagingM > 1f) {
			com.fs.starfarer.api.campaign.StarSystemAPI forS = ThreatConvoys.stagingHive(market);
			tooltip.addPara("%s marines staging for %s.", 3f, h,
					Misc.getWithDGS(Math.round(stagingM)), forS != null ? forS.getName() : "a siege");
		}
		float bleed = ThreatGroundFronts.defenderLossPer30Days(market, front);
		if (bleed > 0f) {
			tooltip.addPara("Marines losing %s a day.", 3f,
					neg, ThreatGroundFronts.perDay(bleed));
		}
		// what they fight with (the user, 2026-10-24: "armament consumption rate ... specifically for marines")
		if (armed > 0f && ThreatIncConfig.defenderArmsEnabled() && !ThreatGroundFronts.isHiveTarget(market)) {
			if (ThreatGroundFronts.defendersDry(market)) {
				tooltip.addPara("Marines' armaments 0: defence x%s dry.", 3f, neg,
						String.format("%.1f", ThreatIncConfig.frontDryEffectivenessMult()));
			} else {
				float defBurn = ThreatGroundFronts.defenderDailyUpkeep(market, front);
				float defDays = ThreatGroundFronts.defenderArmsDaysLeft(market, front);
				String push = ThreatGroundFronts.defendersPush(front, market)
						? " x " + String.format("%.1f", ThreatIncConfig.frontPushUpkeepMult()) + " attacking" : "";
				tooltip.addPara("Marines' armaments %s d = %s stock / (%s armed x %s each per 30 days" + push + " = %s/d).", 3f, h,
						defDays >= 999f ? "999+" : "" + (int) defDays,
						Misc.getWithDGS(Math.round(ThreatReserves.stock(market.getId(), com.fs.starfarer.api.impl.campaign.ids.Commodities.HAND_WEAPONS))),
						Misc.getWithDGS(Math.round(armed)), String.format("%.2f", ThreatIncConfig.frontArmamentsPerMarinePer30Days()),
						String.format("%.1f", defBurn));
			}
		}

		if (ThreatGroundFronts.defendersHoldFire(front, market)) {
			tooltip.addPara("Marines dug in: defence x%s, no push.", 3f, pos,
					String.format("%.2f", ThreatGroundFronts.defenderCoverMult(front)));
		} else {
			tooltip.addPara("Marines push with %s.", 3f, pos,
					Misc.getWithDGS(Math.round(ThreatGroundFronts.defenderPressure(front, market))));
		}

		if (!ThreatGroundFronts.needsArms(front)) {
			// the swarm does not fight on armaments (2026-09-08); what tells the
			// player how this ends now is what pressing costs them
			tooltip.addPara("Swarm losing %s troops a day at its current stance.", 3f, h,
					ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(front, market)));
			return;
		}
		float supply = ThreatGroundFronts.supplyDaysLeft(front);
		if (front.armaments > 0f) {
			tooltip.addPara("The landing's heavy armaments: %s.", 3f, h, days(Math.min(supply, 999f)));
		} else if (front.finalPush) {
			tooltip.addPara("The landing's heavy armaments: %s - this is its final push.", 3f, neg,
					"exhausted");
		} else {
			float finalIn = ThreatGroundFronts.daysToFinalPush(front);
			tooltip.addPara("The landing's heavy armaments: %s - dug in for the next expedition, "
					+ "final push in %s if none comes.", 3f, pos, "exhausted",
					days(Math.max(0f, finalIn)));
		}
	}

	protected static String days(float d) {
		int n = (int) Math.ceil(d);
		return n + (n == 1 ? " day" : " days");
	}

	protected static String fmt(float f) {
		if (f == Math.floor(f)) return "" + (int) f;
		return String.format("%.1f", f);
	}
}
