package threatinc;

import java.util.List;

import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.misc.MiscAcademyFleetCreator;
import com.fs.starfarer.api.impl.campaign.fleets.misc.MiscFleetCreatorPlugin;
import com.fs.starfarer.api.impl.campaign.fleets.misc.MiscFleetRouteManager;
import com.fs.starfarer.api.impl.campaign.fleets.misc.MiscFleetRouteManager.MiscRouteData;
import com.fs.starfarer.api.impl.campaign.ids.Tags;

/**
 * Vanilla's academy fleets survive the swarm emptying the core (2026-10-08).
 * {@link MiscAcademyFleetCreator#createRouteParams} picks a source market
 * from every unhidden market with a spaceport that has not sent one lately,
 * and dereferences the pick unguarded; once the swarm has taken the core
 * (hw84b: two human worlds left, both on the timeout) the pick is null and
 * the game fatals from {@code CampaignEngine.advance} at the moment of the
 * swarm's victory. The pilgrim creator beside it returns null on the same
 * pick. {@link Academy} does the same for the academy, and {@link #install}
 * puts it in the route manager's static creator list in vanilla's place,
 * under vanilla's id, so routes a save already holds still find their creator.
 */
public class ThreatAcademyFleetGuard {

	/** The academy fleet creator, sending nothing when no market can send. */
	public static class Academy extends MiscAcademyFleetCreator {
		@Override
		public String getId() {
			// saved routes name the creator by vanilla's simple class name
			return MiscAcademyFleetCreator.class.getSimpleName();
		}

		@Override
		public MiscRouteData createRouteParams(MiscFleetRouteManager manager, java.util.Random random) {
			MarketAPI from = pickSourceMarket(manager);
			if (from == null) return null;
			SectorEntityToken to = getAcademy();
			if (to == null || to.getContainingLocation() == null
					|| to.getContainingLocation().hasTag(Tags.SYSTEM_CUT_OFF_FROM_HYPER)) return null;
			return createData(from, to);
		}
	}

	/** Replaces vanilla's academy creator with {@link Academy} in the route manager's static list. Idempotent. */
	public static void install() {
		List<MiscFleetCreatorPlugin> creators = MiscFleetRouteManager.CREATORS;
		if (creators == null) return;
		for (int i = 0; i < creators.size(); i++) {
			MiscFleetCreatorPlugin c = creators.get(i);
			if (c instanceof Academy) return;
			if (c != null && c.getClass() == MiscAcademyFleetCreator.class) {
				creators.set(i, new Academy());
				return;
			}
		}
	}
}
