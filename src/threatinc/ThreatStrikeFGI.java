package threatinc;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;

/**
 * A raid fleet-group whose fleets are authentic Threat swarms (built via the
 * vanilla threat fleet factory) instead of doctrine-generated faction fleets.
 * The base class handles travel, raid actions, and defeat/abort reporting.
 */
public class ThreatStrikeFGI extends GenericRaidFGI {

	public ThreatStrikeFGI(GenericRaidParams params) {
		super(params);
	}

	/**
	 * Whether the expedition is still at its staging colony - the pre-launch
	 * planning window plus the fleets mustering in orbit. This is the recall
	 * window (same rule as Commerce Wars enforcement strikes): sabotage the
	 * source while the operation is being fabricated and it is stillborn, but
	 * once the fleets depart the expedition is autonomous - the swarm's
	 * fleets need no home to keep flying.
	 */
	public boolean isPreparing() {
		if (isInPreLaunchDelay()) return true;
		com.fs.starfarer.api.impl.campaign.intel.group.FGAction curr = getCurrentAction();
		return curr != null && PREPARE_ACTION.equals(curr.getId());
	}

	/**
	 * The payload sweeps every eligible world in the target system, each
	 * bombarded at most once per expedition (see IncursionManager.launchStrike):
	 * frontier staging harasses with tactical bombardment, developed staging
	 * delivers one saturation pass per world. An earlier "annihilation
	 * doctrine" let any expedition bombard until the target died - one strike
	 * could erase a size-8 world start to finish, which made strikes the only
	 * event that mattered. The custom action remains for its dead-target guard
	 * and story-critical handling; a kill still feeds the deciv-conversion
	 * machinery ("the swarm does not abandon its kills"). Passes stay
	 * affordable because {@link #createFleet} zeroes the fleet bombardment
	 * fuel cost - launch fuel was paid via payLaunchCost.
	 */
	@Override
	protected GenericPayloadAction createPayloadAction() {
		return new AnnihilationAction(getParams().raidParams, getParams().payloadDays);
	}

	public static class AnnihilationAction
			extends com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction {
		public AnnihilationAction(FGRaidParams params, float raidDays) {
			super(params, raidDays);
		}

		/**
		 * Autoresolve loops the full pass count blindly; skip passes at a
		 * target that is already dead so the husk isn't re-bombarded.
		 *
		 * Story-critical worlds need their own handling because vanilla
		 * doBombardment hard-refuses the killing blow on them (destroy is
		 * forced false): without it, the surplus annihilation passes just
		 * stacked stability damage on an unkillable world (observed: -100
		 * stability on Chalcedon). With destruction disallowed the passes
		 * stop once the world is ground to the destroy threshold - nothing
		 * more can be achieved; with it allowed, the killing blow the engine
		 * refused is dealt directly.
		 */
		@Override
		public void performRaid(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.econ.MarketAPI market) {
			if (market == null || !market.isInEconomy()) return;

			boolean storyCritical = com.fs.starfarer.api.util.Misc.isStoryCritical(market);
			boolean atFloor = market.getSize()
					<= com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD
							.getBombardDestroyThreshold();
			if (storyCritical && atFloor && !ThreatIncConfig.destroyStoryCritical()) return;

			super.performRaid(fleet, market);

			if (storyCritical && atFloor && ThreatIncConfig.destroyStoryCritical()
					&& market.isInEconomy()) {
				// the swarm does not care whose story a world matters to
				com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker
						.decivilize(market, true);
			}
		}
	}

	/**
	 * File the strike with the rest of the incursion intel, not just under
	 * the generic Military tab where nobody thinks to look for it.
	 */
	@Override
	public java.util.Set<String> getIntelTags(com.fs.starfarer.api.ui.SectorMapAPI map) {
		java.util.Set<String> tags = super.getIntelTags(map);
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		return tags;
	}

	/**
	 * Tell the player the counterplay exists - the hive equivalent of
	 * vanilla's "disrupting the military facilities ... will abort the raid"
	 * line, which never shows for strikes because hive colonies run forges,
	 * not military bases. See {@link IncursionManager#abortStrikesFrom}.
	 */
	@Override
	protected void addStatusSection(com.fs.starfarer.api.ui.TooltipMakerAPI info,
			float width, float height, float opad) {
		super.addStatusSection(info, width, height, opad);
		if (!ThreatIncConfig.strikeRecallEnabled()) return;
		if (isEnding() || isEnded() || isAborted() || isSucceeded() || isFailed()) return;
		com.fs.starfarer.api.campaign.econ.MarketAPI source =
				getParams() != null ? getParams().source : null;
		if (source == null) return;
		if (!com.fs.starfarer.api.impl.campaign.ids.Factions.THREAT
				.equals(source.getFactionId())) return;
		if (isPreparing()) {
			info.addPara("The expedition is still being fabricated and fueled at "
					+ "%s. Disrupt the colony's forge or its Swarm Nexus - a raid will do - "
					+ "or bombard or destroy the colony before the fleets depart, and the "
					+ "operation is stillborn.", opad,
					com.fs.starfarer.api.util.Misc.getHighlightColor(), source.getName());
		} else {
			info.addPara("The expedition has departed and is %s - nothing done to its staging "
					+ "colony will turn it back now. It can only be met in space, or its "
					+ "target defended.", opad,
					com.fs.starfarer.api.util.Misc.getNegativeHighlightColor(), "autonomous");
		}
	}

	@Override
	protected CampaignFleetAPI createFleet(int size, float damage) {
		FabricatorEscortStrength strength;
		int fabricators = 0;

		if (size <= 4) {
			strength = FabricatorEscortStrength.LOW;
		} else if (size <= 6) {
			strength = FabricatorEscortStrength.MEDIUM;
		} else if (size <= 8) {
			strength = FabricatorEscortStrength.HIGH;
		} else {
			strength = FabricatorEscortStrength.HIGH;
			fabricators = 1; // the armada brings a fabricator
		}

		// heavy pre-spawn damage downgrades the swarm a tier
		if (damage > 0.4f && strength != FabricatorEscortStrength.LOW) {
			strength = FabricatorEscortStrength.values()[strength.ordinal() - 1];
			fabricators = 0;
		}

		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				fabricators, 0, 0, strength, getRandom());

		// provision the swarm to actually bombard on arrival rather than idling
		// until the operation times out: vanilla gates live bombardment behind
		// fuel the fleet doesn't carry, so grant a large (summed per member)
		// FLEET_BOMBARD_COST_REDUCTION to zero that cost
		if (fleet != null) {
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				member.getStats().getDynamic().getMod(Stats.FLEET_BOMBARD_COST_REDUCTION)
						.modifyFlat("threatinc_strike", 100000f);
			}
			// let the player see the incursion coming rather than only when it
			// arrives (our fleets lack the vanilla behavior script that would
			// otherwise restore detection range for a sensor-mod-equipped player)
			ThreatColonyManager.makeDetectable(fleet);
		}

		return fleet;
	}
}
