package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

/**
 * CONVOYS - the physical staging layer (docs/strategy-layer.md). A mobilised
 * faction's expedition draws only from the colony it sails from, so the rest
 * of the faction ships war materiel to that STAGING BASE by convoy: a real
 * fleet with the marines, armaments, fuel and supplies aboard, moving through
 * hyperspace like any other. Lose the convoy and the stock is gone; the
 * Threat hunts them, and so can the player.
 *
 * <p>Logistics AI runs on the slow tick, per mobilised faction: every
 * military world within expedition range of a live hive is a staging base;
 * when one is short of what an expedition against its nearest hive would
 * draw, the same-faction colony with the most spare stock within convoy range
 * sends a convoy. Arrivals and losses resolve on the fast poll.
 */
public class ThreatConvoys {

	public static final String KEY_CONVOYS = "threatinc_convoys";
	/** Fleet memory flag marking a war-materiel convoy. */
	public static final String CONVOY_FLAG = "$threatinc_convoy";

	/** One convoy in flight. The cargo is also physically aboard the fleet. */
	public static class Convoy {
		public CampaignFleetAPI fleet;
		public String factionId;
		public String fromMarketId;
		public String toMarketId;
		public float marines;
		public float armaments;
		public float fuel;
		public float supplies;
		public long departedTimestamp;

		public String fromName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(fromMarketId);
			return m != null ? m.getName() : fromMarketId;
		}

		public String toName() {
			MarketAPI m = Global.getSector().getEconomy().getMarket(toMarketId);
			return m != null ? m.getName() : toMarketId;
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Convoy> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CONVOYS);
		if (!(val instanceof List)) {
			val = new ArrayList<Convoy>();
			Global.getSector().getPersistentData().put(KEY_CONVOYS, val);
		}
		return (List<Convoy>) val;
	}

	public static boolean isConvoy(CampaignFleetAPI fleet) {
		return fleet != null && fleet.getMemoryWithoutUpdate().getBoolean(CONVOY_FLAG);
	}

	public static List<Convoy> convoysFor(String factionId) {
		List<Convoy> result = new ArrayList<Convoy>();
		for (Convoy c : all()) {
			if (factionId == null || factionId.equals(c.factionId)) result.add(c);
		}
		return result;
	}

	protected static boolean convoyBoundFor(String marketId) {
		for (Convoy c : all()) {
			if (marketId.equals(c.toMarketId)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// logistics AI (slow tick)
	// ------------------------------------------------------------------

	/**
	 * A staging base is a military world within expedition range of a live
	 * hive; returns the nearest such hive system, or null if the world is not
	 * a staging base.
	 */
	public static StarSystemAPI nearestHiveInRange(MarketAPI base) {
		if (base == null || base.getStarSystem() == null) return null;
		if (!IncursionManager.hasMilitary(base)) return null;
		float range = IncursionManager.expeditionRangeLY(base);
		StarSystemAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
			if (d > range || d >= bestDist) continue;
			bestDist = d;
			best = system;
		}
		return best;
	}

	/** Target stock of each reserve commodity at a staging base, in ThreatReserves.COMMODITIES order. */
	public static float[] stagingTargets(MarketAPI base) {
		StarSystemAPI hive = nearestHiveInRange(base);
		if (hive == null) return new float[] {0f, 0f, 0f, 0f};
		float[] wants = IncursionManager.stagingWants(base, hive);
		float mult = ThreatIncConfig.stagingTargetMult();
		for (int i = 0; i < wants.length; i++) wants[i] *= mult;
		return wants;
	}

	public static float capacityFor(String commodityId) {
		if (Commodities.MARINES.equals(commodityId)) return ThreatIncConfig.convoyMarineCapacity();
		return ThreatIncConfig.convoyCargoCapacity();
	}

	/**
	 * One planning pass: for each mobilised faction, each staging base with
	 * no convoy already inbound, find the commodity it is shortest of (as a
	 * fraction of a convoy load) and the donor colony with the most spare
	 * stock of it, then send one convoy carrying that plus whatever else the
	 * donor can spare that the base also wants.
	 */
	public static void planLogistics(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.convoyEnabled()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null) continue;
			List<MarketAPI> markets = ThreatReserves.marketsOf(factionId);
			for (MarketAPI base : markets) {
				if (convoyBoundFor(base.getId())) continue;
				float[] targets = stagingTargets(base);
				// the commodity the base is shortest of, in convoy loads
				int worst = -1;
				float worstShort = 0f;
				for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
					String c = ThreatReserves.COMMODITIES[i];
					float shortBy = targets[i] - ThreatReserves.stock(base.getId(), c);
					float loads = shortBy / Math.max(1f, capacityFor(c));
					if (loads > worstShort) {
						worstShort = loads;
						worst = i;
					}
				}
				if (worst < 0 || worstShort < ThreatIncConfig.convoyMinLoadFraction()) continue;

				String need = ThreatReserves.COMMODITIES[worst];
				MarketAPI donor = pickDonor(markets, base, need);
				if (donor == null) continue;

				float[] load = new float[ThreatReserves.COMMODITIES.length];
				for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
					String c = ThreatReserves.COMMODITIES[i];
					float shortBy = targets[i] - ThreatReserves.stock(base.getId(), c);
					if (shortBy <= 0f) continue;
					load[i] = Math.min(shortBy, Math.min(spare(donor, c), capacityFor(c)));
				}
				dispatch(donor, base, faction, load, random);
			}
		}
	}

	/**
	 * A player-ordered staging run (the board's Stage button): fill a convoy
	 * for this colony from the same-faction colony that can spare the most
	 * marines (or, failing marines, the most of anything), regardless of
	 * whether the destination is a staging base. Returns the convoy or null.
	 */
	public static Convoy stageTo(MarketAPI target, FactionAPI faction, Random random) {
		if (target == null || faction == null || !ThreatIncConfig.convoyEnabled()) return null;
		List<MarketAPI> markets = ThreatReserves.marketsOf(faction.getId());
		MarketAPI donor = null;
		String best = null;
		float bestSpare = 0f;
		for (String c : ThreatReserves.COMMODITIES) {
			MarketAPI d = pickDonor(markets, target, c);
			if (d == null) continue;
			float loads = spare(d, c) / Math.max(1f, capacityFor(c));
			// marines first when any colony can spare a real load of them
			if (Commodities.MARINES.equals(c) && loads >= ThreatIncConfig.convoyMinLoadFraction()) {
				donor = d;
				best = c;
				break;
			}
			if (loads > bestSpare) {
				bestSpare = loads;
				donor = d;
				best = c;
			}
		}
		if (donor == null || best == null) return null;
		float[] load = new float[ThreatReserves.COMMODITIES.length];
		for (int i = 0; i < ThreatReserves.COMMODITIES.length; i++) {
			String c = ThreatReserves.COMMODITIES[i];
			load[i] = Math.min(spare(donor, c), capacityFor(c));
		}
		return dispatch(donor, target, faction, load, random);
	}

	/** Stock a colony can spare: what it holds above its keep fraction of its own cap. */
	public static float spare(MarketAPI donor, String commodityId) {
		float keep = ThreatReserves.cap(donor, commodityId) * ThreatIncConfig.donorKeepFraction();
		return Math.max(0f, ThreatReserves.stock(donor.getId(), commodityId) - keep);
	}

	protected static MarketAPI pickDonor(List<MarketAPI> markets, MarketAPI base, String commodityId) {
		MarketAPI best = null;
		float bestSpare = 0f;
		float range = ThreatIncConfig.convoyRangeLY();
		for (MarketAPI donor : markets) {
			if (donor == base || donor.getStarSystem() == null) continue;
			if (Misc.getDistanceLY(donor.getStarSystem().getLocation(),
					base.getStarSystem().getLocation()) > range) continue;
			float s = spare(donor, commodityId);
			if (s > bestSpare) {
				bestSpare = s;
				best = donor;
			}
		}
		// not worth a sailing
		if (best != null && bestSpare < capacityFor(commodityId)
				* ThreatIncConfig.convoyMinLoadFraction()) return null;
		return best;
	}

	/**
	 * Builds the convoy fleet at the donor, loads the cargo aboard (clamped to
	 * what the hulls can actually carry), draws exactly that from the donor's
	 * reserve, and sends it to the base.
	 */
	public static Convoy dispatch(MarketAPI donor, MarketAPI base, FactionAPI faction,
			float[] load, Random random) {
		if (donor == null || base == null || faction == null) return null;
		StarSystemAPI system = donor.getStarSystem();
		SectorEntityToken from = donor.getPrimaryEntity();
		SectorEntityToken to = base.getPrimaryEntity();
		if (system == null || from == null || to == null) return null;

		float marines = load[0];
		float cargoUnits = load[1] + load[2] + load[3];
		if (marines <= 0f && cargoUnits <= 0f) return null;

		float escort = ThreatIncConfig.convoyEscortFP();
		// freighters sized to the cargo, transports to the troops: roughly one
		// point of hull per 60 units / 40 marines, so the load actually fits
		float freighterPts = Math.max(10f, cargoUnits / 60f);
		float tankerPts = load[2] > 0f ? Math.max(5f, load[2] / 100f) : 0f;
		float transportPts = marines > 0f ? Math.max(10f, marines / 40f) : 0f;
		FleetParamsV3 params = new FleetParamsV3(donor, donor.getLocationInHyperspace(),
				faction.getId(), null, FleetTypes.SUPPLY_FLEET,
				escort, freighterPts, tankerPts, transportPts, 0f, 0f, 0f);
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) return null;

		system.addEntity(fleet);
		fleet.setLocation(from.getLocation().x, from.getLocation().y);
		fleet.setName("Supply Convoy");
		fleet.setNoFactionInName(false);
		fleet.getMemoryWithoutUpdate().set(CONVOY_FLAG, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_TRADE_FLEET, true);
		fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_LOW_REP_IMPACT, true);

		// load what fits; draw only what was loaded
		CargoAPI cargo = fleet.getCargo();
		Convoy c = new Convoy();
		c.fleet = fleet;
		c.factionId = faction.getId();
		c.fromMarketId = donor.getId();
		c.toMarketId = base.getId();
		c.departedTimestamp = Global.getSector().getClock().getTimestamp();

		int m = (int) Math.min(marines, cargo.getFreeCrewSpace());
		if (m > 0) {
			cargo.addMarines(m);
			c.marines = ThreatReserves.draw(donor.getId(), Commodities.MARINES, m);
		}
		c.armaments = loadCommodity(cargo, donor, Commodities.HAND_WEAPONS, load[1]);
		c.fuel = loadCommodity(cargo, donor, Commodities.FUEL, load[2]);
		c.supplies = loadCommodity(cargo, donor, Commodities.SUPPLIES, load[3]);
		if (c.marines <= 0f && c.armaments <= 0f && c.fuel <= 0f && c.supplies <= 0f) {
			Misc.fadeAndExpire(fleet);
			return null;
		}

		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION, to, 1000f,
				"carrying war materiel to " + base.getName());
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, from, 1000f,
				"returning to " + donor.getName());
		all().add(c);

		ThreatIncConfig.log("Convoy dispatched: " + faction.getId() + " " + donor.getName()
				+ " -> " + base.getName() + " (" + (int) c.marines + " marines, "
				+ (int) c.armaments + " armaments, " + (int) c.fuel + " fuel, "
				+ (int) c.supplies + " supplies)");
		return c;
	}

	protected static float loadCommodity(CargoAPI cargo, MarketAPI donor, String commodityId,
			float wanted) {
		if (wanted <= 0f) return 0f;
		float fits = Math.max(0f, Math.min(wanted, cargo.getSpaceLeft()));
		int units = (int) fits;
		if (units <= 0) return 0f;
		float taken = ThreatReserves.draw(donor.getId(), commodityId, units);
		if (taken > 0f) cargo.addCommodity(commodityId, (int) taken);
		return (int) taken;
	}

	// ------------------------------------------------------------------
	// resolution (fast poll)
	// ------------------------------------------------------------------

	/** Distance from the destination entity at which a convoy counts as arrived. */
	public static final float ARRIVAL_RANGE = 350f;

	public static void poll() {
		if (all().isEmpty()) return;
		for (Convoy c : new ArrayList<Convoy>(all())) {
			CampaignFleetAPI fleet = c.fleet;
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) {
				lost(c);
				continue;
			}
			float days = Global.getSector().getClock().getElapsedDaysSince(c.departedTimestamp);
			if (days > ThreatIncConfig.convoyTimeoutDays()) {
				Misc.fadeAndExpire(fleet);
				lost(c);
				continue;
			}
			MarketAPI base = Global.getSector().getEconomy().getMarket(c.toMarketId);
			if (base == null || base.getPrimaryEntity() == null
					|| !c.factionId.equals(base.getFactionId())) {
				// the destination fell or changed hands: turn around, stock stays aboard
				// until the fleet despawns home, where it is returned to the donor
				returnHome(c);
				continue;
			}
			SectorEntityToken to = base.getPrimaryEntity();
			if (fleet.getContainingLocation() == to.getContainingLocation()
					&& Misc.getDistance(fleet, to) <= ARRIVAL_RANGE) {
				arrived(c, base);
			}
		}
	}

	protected static void arrived(Convoy c, MarketAPI base) {
		CampaignFleetAPI fleet = c.fleet;
		CargoAPI cargo = fleet.getCargo();
		// whatever is still aboard - losses in transit are real losses
		int marines = cargo.getMarines();
		int armaments = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
		int fuel = (int) cargo.getCommodityQuantity(Commodities.FUEL);
		int supplies = (int) cargo.getCommodityQuantity(Commodities.SUPPLIES);
		if (marines > 0) cargo.removeMarines(marines);
		if (armaments > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, armaments);
		if (fuel > 0) cargo.removeCommodity(Commodities.FUEL, fuel);
		if (supplies > 0) cargo.removeCommodity(Commodities.SUPPLIES, supplies);
		ThreatReserves.deposit(base.getId(), Commodities.MARINES, marines);
		ThreatReserves.deposit(base.getId(), Commodities.HAND_WEAPONS, armaments);
		ThreatReserves.deposit(base.getId(), Commodities.FUEL, fuel);
		ThreatReserves.deposit(base.getId(), Commodities.SUPPLIES, supplies);
		all().remove(c);

		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		SectorEntityToken home = donor != null ? donor.getPrimaryEntity() : base.getPrimaryEntity();
		fleet.clearAssignments();
		fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, home, 1000f,
				"returning to " + (donor != null ? donor.getName() : base.getName()));
		ThreatIncConfig.log("Convoy arrived: " + c.factionId + " at " + base.getName() + " ("
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, "
				+ supplies + " supplies)");
	}

	protected static void lost(Convoy c) {
		all().remove(c);
		FactionAPI faction = Global.getSector().getFaction(c.factionId);
		String who = faction == null ? c.factionId : faction.isPlayerFaction() ? "Your"
				: Misc.ucFirst(faction.getDisplayNameWithArticle());
		ThreatColonyManager.announce(who + " supply convoy from " + c.fromName() + " to "
				+ c.toName() + " has been lost with its cargo.", Misc.getNegativeHighlightColor());
		ThreatIncConfig.log("Convoy lost: " + c.factionId + " " + c.fromName() + " -> " + c.toName());
	}

	/**
	 * Recalled, or its destination is gone: the convoy turns for the donor
	 * with the cargo still aboard, and whatever survives the trip home goes
	 * back into the donor's reserve on arrival (ThreatReturns).
	 */
	protected static void returnHome(Convoy c) {
		all().remove(c);
		CampaignFleetAPI fleet = c.fleet;
		MarketAPI donor = Global.getSector().getEconomy().getMarket(c.fromMarketId);
		if (donor != null && donor.getPrimaryEntity() != null
				&& c.factionId.equals(donor.getFactionId())) {
			ThreatReturns.sendHome(fleet, c.factionId, donor.getId());
		} else if (fleet != null) {
			Misc.fadeAndExpire(fleet);
		}
		ThreatIncConfig.log("Convoy recalled: " + c.factionId + " " + c.fromName()
				+ " -> " + c.toName());
	}
}
