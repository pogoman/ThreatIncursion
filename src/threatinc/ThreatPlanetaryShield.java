package threatinc;

import com.fs.starfarer.api.impl.campaign.econ.impl.PlanetaryShield;

/**
 * Retired 2026-11-09: the Planetary Shield is vanilla's again. This class replaced vanilla's
 * plugin (through industries.csv) while the shield was a bombardment target that bought cover;
 * those disruption mechanics went on 2026-10-27, and the swap crashed any other mod that
 * replaces the shield ({@code ClassCastException} at sector generation).
 *
 * <p>Kept as an empty subclass only so a save that names it still loads
 * ({@code ThreatIncModPlugin.retireShields} rebuilds each one on the spec's plugin at load).
 * Do not add to it, and do not delete it without checking late saves for its name.
 */
public class ThreatPlanetaryShield extends PlanetaryShield {

	/** Vanilla's key (it is built from the class's simple name), so a shield's disruption clock reads through until the rebuild. */
	@Override
	public String getDisruptedKey() {
		return "$core_disrupted_" + PlanetaryShield.class.getSimpleName();
	}
}
