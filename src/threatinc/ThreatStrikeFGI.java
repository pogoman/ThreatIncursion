package threatinc;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
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

		return fleet;
	}
}
