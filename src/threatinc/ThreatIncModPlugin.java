package threatinc;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

public class ThreatIncModPlugin extends BaseModPlugin {

	@Override
	public void onApplicationLoad() {
		registerFragmentFabricatorEffect();
	}

	/**
	 * The vanilla Fragment Fabricator item has no InstallableItemEffect
	 * registered (it was never meant to be installed in an industry), and the
	 * colony UI fatals on the null the moment it renders a nexus holding one.
	 * Register ours once per launch; defer to any effect another mod (or a
	 * future vanilla version) may have provided. Also called from onGameLoad
	 * as a belt-and-braces for hot-swapped jars.
	 */
	public static void registerFragmentFabricatorEffect() {
		String id = com.fs.starfarer.api.impl.campaign.ids.Items.FRAGMENT_FABRICATOR;
		if (!com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo.ITEM_EFFECTS
				.containsKey(id)) {
			com.fs.starfarer.api.impl.campaign.econ.impl.ItemEffectsRepo.ITEM_EFFECTS
					.put(id, new FragmentScreenEffect(id));
		}
	}

	@Override
	public void onGameLoad(boolean newGame) {
		registerFragmentFabricatorEffect();
		// transient: re-added every load, never serialized into the save
		IncursionManager manager = new IncursionManager();
		Global.getSector().addTransientScript(manager);
		// transient listener too - hears colony decivilizations so the swarm
		// can claim the worlds its bombardments kill, and player bombardments so
		// it can waive the atrocity penalty for exterminating the swarm
		Global.getSector().getListenerManager().addListener(manager, true);

		// keep the intel entry alive on saves where the incursion already started
		if (ThreatIncData.isStarted()) {
			ThreatIncursionIntel.ensureAdded();
		}
	}
}
