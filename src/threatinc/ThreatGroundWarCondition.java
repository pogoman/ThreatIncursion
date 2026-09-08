package threatinc;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.campaign.econ.Industry;
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
		Color pos = Misc.getPositiveHighlightColor();

		int size = market.getSize();
		int held = Math.max(0, Math.min(size, front.strataHeld));
		tooltip.addPara("Districts held by the invaders: %s of %s.", opad, held > 0 ? neg : h,
				"" + held, "" + size);
		if (held > 0) {
			tooltip.addPara("Stability %s, accessibility %s while they hold them.", 3f, neg,
					"-" + fmt(ThreatIncConfig.districtStabilityPenalty() * held),
					"-" + Math.round(ThreatIncConfig.districtAccessPenalty() * held * 100f) + "%");
			List<Industry> seized = ThreatGroundFronts.seizedIndustries(market);
			if (!seized.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (Industry ind : seized) {
					if (names.length() > 0) names.append(", ");
					names.append(ind.getCurrentName());
				}
				tooltip.addPara("Seized: %s.", 3f, neg, names.toString());
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
		float holdNeed = ThreatGroundFronts.holdRequirement(market);
		tooltip.addPara("Invaders %s effective against the colony's %s; holding needs %s.", 3f, h,
				Misc.getWithDGS(Math.round(attacker)), Misc.getWithDGS(Math.round(defender)),
				Misc.getWithDGS(Math.round(holdNeed)));
		if (attacker >= holdNeed) {
			tooltip.addPara("The invaders are %s.", 3f, neg, "holding and pressing on");
		} else {
			tooltip.addPara("The invaders are %s.", 3f, pos, "contained");
		}

		// What that defence figure is actually made of (2026-09-08). Vanilla's
		// own Ground defenses readout on this screen shows the garrison stat
		// alone and never counts marines, so without this line the two numbers
		// disagree with nothing to explain them.
		float garrison = ThreatGroundFronts.colonyGarrison(market);
		float armed = ThreatReserves.armedMarines(market);
		if (armed > 0f) {
			tooltip.addPara("Of that, %s garrison and %s armed marines, %s.", 3f, h,
					Misc.getWithDGS(Math.round(garrison)), Misc.getWithDGS(Math.round(armed)),
					ThreatMarineXP.colonyRank(market).toLowerCase());
		} else {
			tooltip.addPara("Of that, %s garrison and %s armed marines.", 3f, h,
					Misc.getWithDGS(Math.round(garrison)), "no");
		}
		float arming = ThreatReserves.stock(market.getId(),
				com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES) - armed;
		if (arming > 1f) {
			tooltip.addPara("%s more marines are still being armed.", 3f, h,
					Misc.getWithDGS(Math.round(arming)));
		}
		float bleed = ThreatGroundFronts.defenderLossPer30Days(market, front);
		if (bleed > 0f) {
			tooltip.addPara("The defence is losing %s marines a day at this pressure.", 3f,
					neg, ThreatGroundFronts.perDay(bleed));
		}

		float days = ThreatGroundFronts.daysToCounterAttack(front, market);
		tooltip.addPara("Next garrison counter-attack: %s.", 3f, pos,
				days <= 0f ? "imminent" : days(days));

		if (!ThreatGroundFronts.needsArms(front)) {
			// the swarm does not fight on armaments (2026-09-08); what tells the
			// player how this ends now is what pressing costs them
			tooltip.addPara("Invaders' losses: %s troops a day at their current stance.", 3f, h,
					ThreatGroundFronts.perDay(ThreatGroundFronts.attritionPer30Days(front, market)));
			return;
		}
		float supply = ThreatGroundFronts.supplyDaysLeft(front);
		if (front.armaments > 0f) {
			tooltip.addPara("Invaders' heavy armaments: %s.", 3f, h, days(Math.min(supply, 999f)));
		} else if (front.finalPush) {
			tooltip.addPara("Invaders' heavy armaments: %s - this is their final push.", 3f, neg,
					"exhausted");
		} else {
			float finalIn = ThreatGroundFronts.daysToFinalPush(front);
			tooltip.addPara("Invaders' heavy armaments: %s - dug in for the next expedition, "
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
