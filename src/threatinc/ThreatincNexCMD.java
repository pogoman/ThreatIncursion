package threatinc;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;

/**
 * The rules.csv side of Nexerelin compatibility (docs/nexerelin.md).
 * Commands:
 * <ul>
 * <li>{@code yields} - true when Nexerelin runs the military options of this
 * market: it is loaded and the market is not a hive. Our overrides of
 * vanilla's option ids carry {@code !ThreatincNexCMD yields}, so on a human
 * colony Nexerelin's menu (Invade included) wins, and on a hive ours does.</li>
 * <li>{@code besieged} - true when Nexerelin is loaded and the swarm is
 * besieging this colony.</li>
 * <li>{@code blockInvade} - greys Nexerelin's Invade option. Fired from
 * NexPostShowDefenses, the last thing Nexerelin's menu build does, so the
 * state is written after the shape.</li>
 * </ul>
 */
public class ThreatincNexCMD extends BaseCommandPlugin {

	/** Nex_MarketCMD.INVADE */
	public static final String NEX_INVADE = "nex_mktInvade";

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Misc.Token> params,
			Map<String, MemoryAPI> memoryMap) {
		if (dialog == null || params == null || params.isEmpty()) return false;
		if (!ThreatNexCompat.nexEnabled()) return false;
		String command = params.get(0).getString(memoryMap);
		MarketAPI market = dialog.getInteractionTarget() != null
				? dialog.getInteractionTarget().getMarket() : null;
		if (market == null) return false;
		if ("yields".equals(command)) {
			// the same test as ThreatincMarketCMD.isThreatTarget
			return !(ThreatIncConfig.enabled() && Factions.THREAT.equals(market.getFactionId()));
		}
		if ("besieged".equals(command)) {
			return ThreatNexCompat.besieged(market);
		}
		if ("blockInvade".equals(command)) {
			if (!dialog.getOptionPanel().hasOption(NEX_INVADE)) return false;
			dialog.getOptionPanel().setEnabled(NEX_INVADE, false);
			dialog.getOptionPanel().setTooltip(NEX_INVADE, "The swarm is besieging " + market.getName() + ".");
			return true;
		}
		return false;
	}
}
