package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Stats;

/**
 * Ground defenses for a machine hive: the same defensive math as vanilla
 * {@link GroundDefenses} (whose raid-danger and image plumbing it inherits),
 * but run on the hive's own supply chain. No marines, no supplies, no small
 * arms - weapon growths and combat frames are extruded from heavy machinery
 * and metals, and starving the hive of either weakens the defense the same
 * way human logistics shortages weaken vanilla defenses.
 *
 * Serves both the base tier and heavy batteries (two industries.csv rows,
 * one class, vanilla-style - vanilla's GroundDefenses also branches on id).
 * Vanilla's apply() is skipped entirely (it hard-codes the human demands and
 * keys the defense mult to their deficits); the small body is replicated
 * here with hive inputs. apply(true) reaches BaseIndustry's plumbing
 * directly since GroundDefenses only overrides the no-arg apply().
 */
public class ThreatGroundDefenses extends GroundDefenses {

	// NOTE on disruption: vanilla defensive industries unapply ENTIRELY while
	// disrupted - and since bombardment fuel cost IS defender ground strength
	// (MarketCMD.getBombardmentCost), that collapse is what made repeat
	// saturation passes nearly free. Machines don't rout: the weapon growths
	// keep firing at ThreatIncConfig.disruptedDefenseFraction() effect.

	@Override
	public void apply() {
		apply(true);

		int size = market.getSize();
		boolean hb = ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(getId());

		demand(Commodities.HEAVY_MACHINERY, size - 2);
		demand(Commodities.METALS, size - 2);

		modifyStabilityWithBaseMod();

		float mult = getDeficitMult(Commodities.HEAVY_MACHINERY, Commodities.METALS);
		String extra = "";
		if (mult != 1f) {
			String com = getMaxDeficit(Commodities.HEAVY_MACHINERY, Commodities.METALS).one;
			extra = " (" + getDeficitText(com).toLowerCase() + ")";
		}
		float bonus = hb ? ThreatIncConfig.heavyBatteriesBonus()
				: ThreatIncConfig.groundDefensesBonus();

		// no unapply-on-disruption - the contribution degrades instead, wearing
		// down with the disruption days on the clock (see
		// ThreatColonyManager.disruptedDefenseResilience), and the deficit mult
		// still applies, so starving the hive of machinery/metals weakens the
		// guns too
		float resilience = ThreatColonyManager.disruptedDefenseResilience(this);
		if (isDisrupted()) {
			extra += " (disrupted: " + Math.round(resilience * 100f) + "% effect)";
		}

		// the groundDefenseMult config knob is applied by SwarmNexus (every
		// colony has one) - applying it here too would square it
		market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD)
				.modifyMult(getModId(), 1f + bonus * mult * resilience,
						getNameForModifier() + extra);
	}

	// hive-only organ: never offered in the player's construction picker
	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}
}
