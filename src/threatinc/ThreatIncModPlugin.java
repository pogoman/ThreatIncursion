package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class ThreatIncModPlugin extends BaseModPlugin {

	@Override
	public void onGameLoad(boolean newGame) {
		// transient: re-added every load, never serialized into the save
		Global.getSector().addTransientScript(new IncursionManager());

		// keep the intel entry alive on saves where the incursion already started
		if (ThreatIncData.isStarted()) {
			ThreatIncursionIntel.ensureAdded();
		}
	}
}
