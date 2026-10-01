package threatinc;

import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.econ.impl.MilitaryBase;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The hive's military tier (2026-10-01, user's call; docs/hive-economy.md
 * "Idle stock"): the Swarm Bastion and its upgrade, Swarm Command - the hive's
 * Military Base and High Command, two industries.csv rows on this one class.
 * Vanilla's own do nothing for a hive (patrols it does not run, commodity
 * demand it cannot meet). These are bought with fleet points a colony's
 * garrison no longer needs, at the vanilla structure's build cost converted
 * at what a fleet point costs in supplies (ThreatBuildCost.fleetPoints), and
 * take vanilla's build time (ThreatColonyManager.maintainMilitaryTier,
 * convertSurplus).
 *
 * <p>Standing, it keeps more of the colony's Defense Swarms at home: twice its
 * reserve under a Bastion, three times under Swarm Command
 * (ThreatColonyManager.garrisonReserve) - swarms its bank fabricates and then
 * pays the garrison's fleet-point upkeep on. And it raises the world's ground
 * defence by vanilla's own Military Base and High Command figures (x1.2,
 * x1.3), worn by disruption as the Swarm Nexus's bonus is. A front holding
 * the world suppresses it and orbit wears it like any hive fortification
 * (ThreatGroundFronts.Theatre.HIVE). It needs the Swarm Nexus; the player can
 * never build one.
 */
public class SwarmBastion extends BaseIndustry {

	public static final String BASTION = "threatinc_swarmbastion";
	public static final String COMMAND = "threatinc_swarmcommand";

	/** Whether the industry is one of the tier's. */
	public static boolean isTier(String industryId) {
		return BASTION.equals(industryId) || COMMAND.equals(industryId);
	}

	/** The structure's ground-defence bonus at full condition: vanilla's Military Base and High Command figures. */
	public static float defenseBonus(String industryId) {
		if (COMMAND.equals(industryId)) return MilitaryBase.DEFENSE_BONUS_COMMAND;
		if (BASTION.equals(industryId)) return MilitaryBase.DEFENSE_BONUS_MILITARY;
		return 0f;
	}

	/**
	 * The colony's tier: 0 with none standing (none, or a Bastion still being
	 * grown), 1 a Bastion, 2 Swarm Command. A Bastion growing into Swarm
	 * Command is still a Bastion. Disruption wears the defence bonus, not this.
	 */
	public static int tier(MarketAPI market) {
		if (market == null) return 0;
		Industry command = market.getIndustry(COMMAND);
		if (command != null && (!command.isBuilding() || command.isUpgrading())) return 2;
		Industry bastion = market.getIndustry(BASTION);
		if (bastion != null && (!bastion.isBuilding() || bastion.isUpgrading())) return 1;
		return 0;
	}

	/** The tier's structure on the colony, standing or building; null without one. */
	public static Industry of(MarketAPI market) {
		if (market == null) return null;
		Industry ind = market.getIndustry(COMMAND);
		return ind != null ? ind : market.getIndustry(BASTION);
	}

	/** Whether the structure stands: built, or an upgrade under way on it. */
	protected boolean stands() {
		return !isBuilding() || isUpgrading();
	}

	@Override
	public void apply() {
		super.apply(true);
		StatBonus defense = market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD);
		if (!stands()) {
			defense.unmodifyMult(getModId());
			return;
		}
		// wears down with the disruption days on the clock, like the Nexus. The
		// groundDefenseMult knob is the Nexus's to apply (applied here too it
		// would be squared)
		float resilience = ThreatColonyManager.disruptedDefenseResilience(this);
		defense.modifyMult(getModId(), 1f + defenseBonus(getId()) * resilience,
				getNameForModifier() + (isDisrupted() ? " (in refit)" : ""));
	}

	@Override
	public void unapply() {
		super.unapply();
		market.getStats().getDynamic().getMod(Stats.GROUND_DEFENSES_MOD).unmodifyMult(getModId());
	}

	@Override
	protected boolean hasPostDemandSection(boolean hasDemand, IndustryTooltipMode mode) {
		return stands();
	}

	@Override
	protected void addPostDemandSection(TooltipMakerAPI tooltip, boolean hasDemand, IndustryTooltipMode mode) {
		if (!stands()) return;
		tooltip.addPara("Defense Swarms kept at home: %s", 10f, Misc.getHighlightColor(),
				"" + ThreatColonyManager.garrisonReserve(market));
		addGroundDefensesImpactSection(tooltip,
				defenseBonus(getId()) * ThreatColonyManager.disruptedDefenseResilience(this));
	}

	// hive-only: never offered in the player's construction picker
	@Override
	public boolean isAvailableToBuild() {
		return false;
	}

	@Override
	public boolean showWhenUnavailable() {
		return false;
	}
}
