package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Abilities;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Entities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.IndustrySpecAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * OUTPOSTS - forward stations on purged worlds (docs/design-theory.md 8.8).
 *
 * <p>A world the swarm held and lost is a world the swarm will try to seed
 * again. An outpost is a standalone, faction-styled orbital station over such
 * a world - no market, no economy - built the way vanilla's Orbital Station
 * industry builds its station (a hidden station-mode fleet holding the tier's
 * variant, tied to a station entity in orbit), so it fights like one. While
 * it stands, a Seeding Swarm cannot found a colony there: it has to kill the
 * station first.
 *
 * <p>The player pays credits; a mobilised NPC faction pays supplies and fuel
 * from the reserve of its nearest base, and builds them on its own on the
 * slow tick. Style follows the faction: whatever station line its own
 * colonies use, else low-tech for the Hegemony and the Luddics, high-tech for
 * Tri-Tachyon, midline for everyone else.
 */
public class ThreatOutposts {

	public static final String KEY_OUTPOSTS = "threatinc_outposts";
	public static final String KEY_PURGED = "threatinc_purgedWorlds";
	public static final String OUTPOST_FLAG = "$threatinc_outpost";

	public static class Outpost {
		public String factionId;
		public String planetId;
		public String systemId;
		public SectorEntityToken entity;
		public CampaignFleetAPI fleet;
		public String specId;
		public long builtTimestamp;

		public String planetName() {
			SectorEntityToken p = Global.getSector().getEntityById(planetId);
			return p != null ? p.getName() : planetId;
		}

		public boolean alive() {
			return fleet != null && fleet.isAlive() && !fleet.isExpired();
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Outpost> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_OUTPOSTS);
		if (!(val instanceof List)) {
			val = new ArrayList<Outpost>();
			Global.getSector().getPersistentData().put(KEY_OUTPOSTS, val);
		}
		return (List<Outpost>) val;
	}

	/** Planet entity ids of worlds the swarm held and lost to a ground victory. */
	@SuppressWarnings("unchecked")
	public static List<String> purgedWorlds() {
		Object val = Global.getSector().getPersistentData().get(KEY_PURGED);
		if (!(val instanceof List)) {
			val = new ArrayList<String>();
			Global.getSector().getPersistentData().put(KEY_PURGED, val);
		}
		return (List<String>) val;
	}

	/** Called from ThreatColonyManager.eradicate, before the teardown. */
	public static void recordPurged(MarketAPI market) {
		if (market == null || market.getPrimaryEntity() == null) return;
		String id = market.getPrimaryEntity().getId();
		if (!purgedWorlds().contains(id)) purgedWorlds().add(id);
	}

	public static Outpost outpostAt(String planetId) {
		for (Outpost o : all()) {
			if (planetId.equals(o.planetId)) return o;
		}
		return null;
	}

	/** Whether a living outpost stands over this planet (blocks seeding). */
	public static boolean holds(SectorEntityToken planet) {
		if (planet == null) return false;
		Outpost o = outpostAt(planet.getId());
		return o != null && o.alive();
	}

	public static List<Outpost> outpostsOf(String factionId) {
		List<Outpost> result = new ArrayList<Outpost>();
		for (Outpost o : all()) {
			if (factionId == null || factionId.equals(o.factionId)) result.add(o);
		}
		return result;
	}

	/**
	 * Purged worlds still open for an outpost: the planet exists, holds no
	 * live market (a re-colonised world is somebody's colony now), and has no
	 * standing outpost.
	 */
	public static List<PlanetAPI> openPurgedWorlds() {
		List<PlanetAPI> result = new ArrayList<PlanetAPI>();
		for (String id : new ArrayList<String>(purgedWorlds())) {
			SectorEntityToken token = Global.getSector().getEntityById(id);
			if (!(token instanceof PlanetAPI) || token.getStarSystem() == null) {
				purgedWorlds().remove(id);
				continue;
			}
			if (!eligible((PlanetAPI) token)) continue;
			result.add((PlanetAPI) token);
		}
		return result;
	}

	/**
	 * Whether a world can take an outpost: a planet (not a star) in a star
	 * system, with no live market (somebody's colony) and no standing outpost.
	 * Any uncolonised world qualifies (decided 2026-09-05); the purged list
	 * is only the board's shortlist and the NPC factions' targets.
	 */
	public static boolean eligible(PlanetAPI planet) {
		if (planet == null || planet.isStar() || planet.getStarSystem() == null) return false;
		MarketAPI m = planet.getMarket();
		if (m != null && m.isInEconomy() && !m.isPlanetConditionMarketOnly()) return false;
		return !holds(planet);
	}

	// ------------------------------------------------------------------
	// style and cost
	// ------------------------------------------------------------------

	/** The vanilla station industry spec this faction's outpost is built from. */
	public static String specIdFor(FactionAPI faction) {
		String suffix = null;
		if (faction != null) {
			for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
				for (Industry ind : m.getIndustries()) {
					String id = ind.getId();
					if (id.startsWith("orbitalstation") || id.startsWith("battlestation")
							|| id.startsWith("starfortress")) {
						suffix = id.endsWith("_high") ? "_high" : id.endsWith("_mid") ? "_mid" : "";
						break;
					}
				}
				if (suffix != null) break;
			}
			if (suffix == null) {
				String id = faction.getId();
				if (Factions.HEGEMONY.equals(id) || Factions.LUDDIC_CHURCH.equals(id)
						|| Factions.LUDDIC_PATH.equals(id) || Factions.PIRATES.equals(id)) {
					suffix = "";
				} else if (Factions.TRITACHYON.equals(id)) {
					suffix = "_high";
				} else {
					suffix = "_mid";
				}
			}
		} else {
			suffix = "_mid";
		}
		int tier = Math.max(1, Math.min(3, ThreatIncConfig.outpostTier()));
		String base = tier == 3 ? "starfortress" : tier == 2 ? "battlestation" : "orbitalstation";
		return base + suffix;
	}

	/** [supplies, fuel] a mobilised NPC faction's base pays; the player pays outpostCredits instead. */
	public static float[] npcCost() {
		return new float[] {ThreatIncConfig.outpostSupplies(), ThreatIncConfig.outpostFuel()};
	}

	/** Whether the faction could pay for an outpost at this planet right now (and from where). */
	public static MarketAPI payingBase(FactionAPI faction, SectorEntityToken planet) {
		if (faction == null || planet == null) return null;
		MarketAPI nearest = ThreatFleetOrders.pickBase(faction, planet.getLocationInHyperspace());
		if (nearest == null) return null; // nobody in reach at all
		if (faction.isPlayerFaction()) return nearest;
		// any military world in reach that can pay, nearest first - the closest
		// depot is often the emptiest one
		float[] cost = npcCost();
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : ThreatReserves.marketsOf(faction.getId())) {
			if (market.getStarSystem() == null || !IncursionManager.hasMilitary(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(),
					planet.getLocationInHyperspace());
			if (d > IncursionManager.expeditionRangeLY(market) || d >= bestDist) continue;
			if (ThreatReserves.available(market, Commodities.SUPPLIES) < cost[0]) continue;
			if (ThreatReserves.available(market, Commodities.FUEL) < cost[1]) continue;
			bestDist = d;
			best = market;
		}
		return best;
	}

	// ------------------------------------------------------------------
	// building
	// ------------------------------------------------------------------

	/**
	 * Pays and builds. Returns the outpost, or null (no base in reach, cannot
	 * pay, planet already held). Vanilla's OrbitalStation.spawnStation recipe.
	 */
	public static Outpost build(FactionAPI faction, PlanetAPI planet) {
		if (!ThreatIncConfig.outpostsEnabled() || faction == null || planet == null) return null;
		if (!eligible(planet)) return null;
		MarketAPI base = payingBase(faction, planet);
		if (base == null) return null;

		// pay
		if (faction.isPlayerFaction()) {
			float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
			if (credits < ThreatIncConfig.outpostCredits()) return null;
			Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(
					ThreatIncConfig.outpostCredits());
		} else {
			float[] cost = npcCost();
			ThreatReserves.drawAbove(base, Commodities.SUPPLIES, cost[0]);
			ThreatReserves.drawAbove(base, Commodities.FUEL, cost[1]);
		}

		String specId = specIdFor(faction);
		IndustrySpecAPI spec = Global.getSettings().getIndustrySpec(specId);
		String variantId = "station1_Standard";
		float radius = 55f;
		String fleetName = "Orbital Station";
		try {
			JSONObject json = new JSONObject(spec.getData());
			variantId = json.getString("variant");
			radius = (float) json.getDouble("radius");
			fleetName = json.getString("fleetName");
		} catch (Throwable t) {
			ThreatIncConfig.log("Outpost: could not read station spec " + specId + ": " + t);
		}

		StarSystemAPI system = planet.getStarSystem();
		String name = planet.getName() + " Outpost";
		SectorEntityToken entity = system.addCustomEntity(null, name,
				Entities.STATION_BUILT_FROM_INDUSTRY, faction.getId());
		float orbitRadius = planet.getRadius() + 150f;
		entity.setCircularOrbitWithSpin(planet, (float) Math.random() * 360f, orbitRadius,
				orbitRadius / 10f, 5f, 5f);
		entity.getMemoryWithoutUpdate().set(OUTPOST_FLAG, true);

		FleetParamsV3 fParams = new FleetParamsV3(null, null, faction.getId(), 1f,
				FleetTypes.PATROL_SMALL, 0, 0, 0, 0, 0, 0, 0);
		fParams.allWeapons = true;
		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(fParams);
		if (fleet == null) {
			system.removeEntity(entity);
			return null;
		}
		fleet.setNoFactionInName(true);
		fleet.setStationMode(true);
		fleet.clearAbilities();
		fleet.addAbility(Abilities.TRANSPONDER);
		fleet.getAbility(Abilities.TRANSPONDER).activate();
		fleet.getDetectedRangeMod().modifyFlat("gen", 10000f);
		fleet.setAI(null);
		fleet.getMemoryWithoutUpdate().set(OUTPOST_FLAG, true);
		fleet.getFleetData().clear();
		fleet.setName(name);
		FleetMemberAPI member = Global.getFactory().createFleetMember(FleetMemberType.SHIP, variantId);
		member.setShipName(name);
		fleet.getFleetData().addFleetMember(member);
		member.getRepairTracker().setCR(member.getRepairTracker().getMaxCR());
		fleet.getFleetData().setFlagship(member);
		fleet.getFleetData().setSyncNeeded();
		fleet.getFleetData().syncIfNeeded();
		if (entity instanceof CustomCampaignEntityAPI) {
			((CustomCampaignEntityAPI) entity).setFleetForVisual(fleet);
			((CustomCampaignEntityAPI) entity).setRadius(radius);
		}
		fleet.setCircularOrbit(entity, 0, 0, 100);
		fleet.setHidden(true);
		entity.getMemoryWithoutUpdate().set(MemFlags.STATION_FLEET, fleet);
		system.addEntity(fleet);
		fleet.setLocation(entity.getLocation().x, entity.getLocation().y);

		Outpost o = new Outpost();
		o.factionId = faction.getId();
		o.planetId = planet.getId();
		o.systemId = system.getId();
		o.entity = entity;
		o.fleet = fleet;
		o.specId = specId;
		o.builtTimestamp = Global.getSector().getClock().getTimestamp();
		all().add(o);

		String who = faction.isPlayerFaction() ? "Your" : Misc.ucFirst(faction.getDisplayNameWithArticle());
		ThreatColonyManager.announceAlways(who + " " + fleetName.toLowerCase() + " now stands over "
				+ planet.getName() + " - the swarm cannot seed the world again while it holds.",
				Misc.getPositiveHighlightColor());
		ThreatIncConfig.log("Outpost built: " + faction.getId() + " " + specId + " at "
				+ planet.getName() + " (paid from " + base.getName() + ")");
		return o;
	}

	/** Removes an outpost (decommissioned by order, or its station died). */
	public static void remove(Outpost o, String why) {
		all().remove(o);
		if (o.fleet != null && o.fleet.getContainingLocation() != null) {
			o.fleet.getContainingLocation().removeEntity(o.fleet);
		}
		if (o.entity != null && o.entity.getContainingLocation() != null) {
			o.entity.getContainingLocation().removeEntity(o.entity);
		}
		ThreatIncConfig.log("Outpost removed at " + o.planetName() + ": " + why);
	}

	// ------------------------------------------------------------------
	// ticks
	// ------------------------------------------------------------------

	/**
	 * Fast poll: a dead station is a lost outpost; a colony founded on the
	 * world by the outpost's own faction inherits the station.
	 */
	public static void poll() {
		if (all().isEmpty()) return;
		for (Outpost o : new ArrayList<Outpost>(all())) {
			if (o.alive() && carryOver(o)) continue;
			if (o.alive()) continue;
			String who = ThreatWarState.displayName(o.factionId);
			ThreatColonyManager.announce(who + "'s outpost over " + o.planetName()
					+ " has been destroyed.", Misc.getNegativeHighlightColor());
			remove(o, "station destroyed");
		}
	}

	/**
	 * CARRY-OVER (decided 2026-09-05): when the world under an outpost becomes
	 * a live colony of the outpost's own faction, the makeshift station is
	 * struck and the colony gets the same station line as a built industry -
	 * the tier-1 outpost inherited into its Orbital Station slot, from where
	 * vanilla upgrades it. A colony of another faction leaves the station
	 * standing in orbit as it was. Returns true when the outpost was absorbed.
	 */
	protected static boolean carryOver(Outpost o) {
		SectorEntityToken planet = Global.getSector().getEntityById(o.planetId);
		if (planet == null) return false;
		MarketAPI market = planet.getMarket();
		if (market == null || !market.isInEconomy() || market.isPlanetConditionMarketOnly()) return false;
		if (market.getFactionId() == null || !market.getFactionId().equals(o.factionId)) return false;
		boolean hasStation = false;
		for (Industry ind : market.getIndustries()) {
			String id = ind.getId();
			if (id.startsWith("orbitalstation") || id.startsWith("battlestation")
					|| id.startsWith("starfortress")) {
				hasStation = true;
				break;
			}
		}
		String specId = o.specId != null ? o.specId : specIdFor(market.getFaction());
		if (!hasStation && Global.getSettings().getIndustrySpec(specId) != null) {
			market.addIndustry(specId);
			Industry ind = market.getIndustry(specId);
			if (ind != null && ind.isBuilding()) ind.finishBuildingOrUpgrading();
		}
		String who = o.factionId.equals(Global.getSector().getPlayerFaction().getId()) ? "Your"
				: ThreatWarState.displayName(o.factionId) + "'s";
		ThreatColonyManager.announceAlways(who + " outpost over " + o.planetName()
				+ " is absorbed into the new colony" + (hasStation ? "." : " as its "
				+ specId.replace('_', ' ') + "."), Misc.getPositiveHighlightColor());
		remove(o, "colony founded - station inherited" + (hasStation ? " (colony already had one)" : ""));
		return true;
	}

	/**
	 * Slow tick: each mobilised NPC faction rolls outpostChance to fortify one
	 * open purged world within reach of a base that can pay. One per tick.
	 */
	public static void planNPC(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.outpostsEnabled()) return;
		List<PlanetAPI> open = openPurgedWorlds();
		if (open.isEmpty()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			if (random.nextFloat() >= ThreatIncConfig.outpostChance()) continue;
			for (PlanetAPI planet : open) {
				if (holds(planet)) continue;
				if (payingBase(faction, planet) == null) continue;
				if (build(faction, planet) != null) break;
			}
		}
	}
}
