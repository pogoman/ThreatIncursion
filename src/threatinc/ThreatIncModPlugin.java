package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class ThreatIncModPlugin extends BaseModPlugin {

	@Override
	public void onGameLoad(boolean newGame) {
		// Saves from before the siege rework (data v4) may still carry the
		// retired Fragment Fabricator item installed in hive industries; its
		// InstallableItemEffect stub is gone, and the colony UI fatals on the
		// null the moment it renders an industry holding one. Strip it FIRST,
		// synchronously, before any dialog can open. (The v4 data migration
		// repeats the strip durably; both are idempotent.)
		if (ThreatIncData.isStarted() && ThreatIncData.getDataVersion() < 4) {
			ThreatColonyManager.stripFragmentFabricators();
		}

		// transient: re-added every load, never serialized into the save
		IncursionManager manager = new IncursionManager();
		Global.getSector().addTransientScript(manager);
		// transient listener too - hears colony decivilizations so the swarm
		// can claim the worlds its bombardments kill, player bombardments so
		// it can waive the atrocity penalty for exterminating the swarm, and
		// pre-raid marine-loss computation so hive worlds chew up marines
		Global.getSector().getListenerManager().addListener(manager, true);

		// keep the intel entry alive on saves where the incursion already started
		if (ThreatIncData.isStarted()) {
			ThreatIncursionIntel.ensureAdded();
		}
	}
}
