package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CustomCampaignEntityAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
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
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
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
 *
 * <p>An outpost has a STOCKPILE - a {@link ThreatReserves} entry keyed by its
 * station entity id - so it is a base the war layer can ship from and to
 * ({@link ThreatBases}): a front's survivors are banked in it, front runs in
 * its own system load and unload there, and when the world is colonised the
 * stock comes ashore with the station.
 *
 * <p>The PLAYER'S outpost keeps that stockpile in a real cargo (decided
 * 2026-09-06): a storage-only market on the station, vanilla's abandoned-
 * station recipe ({@code Misc.setAbandonedStationMarket}) - no economy, no
 * industries, not in the economy list, just a Storage submarket the player
 * docks at ({@link ThreatOutpostDialog}). {@link ThreatReserves#backing}
 * treats it exactly as it treats a colony's resource stockpile, so the board,
 * convoys and fronts read and write the same cargo the player sees. The
 * station is its own depot: convoys land and task forces hold its orbit with
 * no Waystation asked ({@link ThreatReserves#hasDepot(ThreatBases.Base)}).
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

	/** Whether a living outpost or frontline forward base stands over this planet (blocks seeding). */
	public static boolean holds(SectorEntityToken planet) {
		if (planet == null) return false;
		Outpost o = outpostAt(planet.getId());
		return (o != null && o.alive()) || ThreatFrontlines.hostsLink(planet);
	}

	public static List<Outpost> outpostsOf(String factionId) {
		List<Outpost> result = new ArrayList<Outpost>();
		for (Outpost o : all()) {
			if (factionId == null || factionId.equals(o.factionId)) result.add(o);
		}
		return result;
	}

	/** A living outpost of this faction in this system, or null. */
	public static Outpost outpostIn(String factionId, StarSystemAPI system) {
		if (system == null) return null;
		for (Outpost o : all()) {
			if (!o.alive() || o.entity == null) continue;
			if (factionId != null && !factionId.equals(o.factionId)) continue;
			if (system.getId().equals(o.systemId)) return o;
		}
		return null;
	}

	// ------------------------------------------------------------------
	// the stockpile (docs/strategy-layer.md): an outpost is a base
	// ------------------------------------------------------------------

	/**
	 * An outpost's stockpile is an ordinary {@link ThreatReserves} entry keyed
	 * by its station ENTITY id instead of a market id - it has no market, but
	 * the reserve map never needed one. It banks nothing on its own (no
	 * economy to bank from) and carries no War footing, so it holds only what
	 * was carried or won there, and it has no garrison floor: all of it is
	 * available. {@link ThreatBases} is the handle that treats it as a base.
	 */
	public static String stockId(Outpost o) {
		return o == null || o.entity == null ? null : o.entity.getId();
	}

	/** The outpost whose stockpile this reserve key belongs to, or null. */
	public static Outpost byStockId(String entityId) {
		if (entityId == null) return null;
		for (Outpost o : all()) {
			if (entityId.equals(stockId(o))) return o;
		}
		return null;
	}

	/** What the outpost holds of a reserve commodity. */
	public static float stock(Outpost o, String commodityId) {
		return ThreatReserves.stock(stockId(o), commodityId);
	}

	public static void deposit(Outpost o, String commodityId, float amount) {
		ThreatReserves.deposit(stockId(o), commodityId, amount);
	}

	/** Takes up to {@code amount} from the stockpile (no floor); returns what was taken. */
	public static float draw(Outpost o, String commodityId, float amount) {
		String id = stockId(o);
		return id == null ? 0f : ThreatReserves.draw(id, commodityId, amount);
	}

	/** Whether the outpost holds anything at all. */
	public static boolean hasStock(Outpost o) {
		for (String c : ThreatReserves.COMMODITIES) {
			if (stock(o, c) > 0f) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// the player's storage: the stockpile the player can dock at
	// ------------------------------------------------------------------

	/** The storage-only market on the station (player outposts), or null. */
	public static MarketAPI storeMarket(Outpost o) {
		if (o == null || o.entity == null) return null;
		MarketAPI m = o.entity.getMarket();
		if (m == null || m.getSubmarket(Submarkets.SUBMARKET_STORAGE) == null) return null;
		return m;
	}

	/**
	 * The cargo that IS this outpost's stockpile (the station's Storage), or
	 * null when the ledger holds it (an NPC outpost). Stock the ledger still
	 * carries for a player outpost - a save from before the storage existed -
	 * moves into the cargo the first time it is asked for.
	 */
	public static CargoAPI storage(Outpost o) {
		MarketAPI m = storeMarket(o);
		if (m == null) return null;
		SubmarketAPI sub = m.getSubmarket(Submarkets.SUBMARKET_STORAGE);
		if (sub == null || sub.getCargo() == null) return null;
		CargoAPI cargo = sub.getCargo();
		ThreatReserves.ColonyReserve r = ThreatReserves.get(stockId(o));
		if (r != null) {
			for (String c : ThreatReserves.COMMODITIES) {
				float held = ThreatReserves.read(r, c);
				if (held <= 0f) continue;
				cargo.addCommodity(c, held);
				ThreatReserves.write(r, c, 0f);
				ThreatIncConfig.log("Outpost: " + o.planetName() + " moves " + (int) held + " "
						+ ThreatReserves.label(c) + " from the ledger into its storage");
			}
			ThreatReserves.clear(stockId(o));
		}
		return cargo;
	}

	/**
	 * Gives a player outpost its storage if it has none: vanilla's
	 * abandoned-station market (neutral, size 0, never in the economy, one
	 * Storage submarket already paid for) on the station entity. Idempotent;
	 * nothing for an NPC outpost, whose stock stays on the ledger.
	 */
	public static void ensureStorage(Outpost o) {
		if (o == null || o.entity == null || !o.alive()) return;
		if (!Global.getSector().getPlayerFaction().getId().equals(o.factionId)) return;
		if (storeMarket(o) != null) return;
		try {
			String name = o.entity.getName();
			MarketAPI market = Global.getFactory().createMarket(o.entity.getId() + "_store", name, 0);
			market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
			market.setPrimaryEntity(o.entity);
			market.setFactionId(Factions.NEUTRAL);
			market.addSubmarket(Submarkets.SUBMARKET_STORAGE);
			market.setPlanetConditionMarketOnly(false);
			SubmarketAPI sub = market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
			if (sub != null && sub.getPlugin() instanceof StoragePlugin) {
				((StoragePlugin) sub.getPlugin()).setPlayerPaidToUnlock(true);
			}
			o.entity.setMarket(market);
			ThreatIncConfig.log("Outpost storage opened at " + o.planetName());
		} catch (Throwable t) {
			ThreatIncConfig.log("Outpost: could not open storage at " + o.planetName() + ": " + t);
		}
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
			if (market.getStarSystem() == null || !IncursionManager.isBase(market)) continue;
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
		return raise(faction, planet, "paid from " + base.getName());
	}

	/**
	 * An NPC faction holds a world with a frontline forward base, never an
	 * outpost (2026-09-26): a real market that grows and builds
	 * (ThreatFrontlines). Cost is the caller's; null when it cannot stand here.
	 */
	public static MarketAPI raiseForwardBase(FactionAPI faction, SectorEntityToken planet, String note) {
		if (faction == null || faction.isPlayerFaction() || !ThreatIncConfig.frontlinesEnabled()) return null;
		if (!(planet instanceof PlanetAPI) || !eligible((PlanetAPI) planet)) return null;
		if (ThreatFrontlines.linkTaken(((PlanetAPI) planet).getStarSystem())) return null;
		MarketAPI market = ThreatFrontlines.found(faction, (PlanetAPI) planet,
				ThreatFrontlines.hiveNear(planet));
		if (market != null) {
			ThreatIncConfig.log("Outpost: " + faction.getId() + " holds " + planet.getName()
					+ " with a forward base (" + note + ")");
		}
		return market;
	}

	/**
	 * An NPC outpost from before forward bases (2026-09-26) becomes one where
	 * it stands, its stock carried into the new market's reserve. False when
	 * it cannot (frontlines off, world gone): the old outpost stays.
	 */
	protected static boolean convertToForwardBase(Outpost o) {
		FactionAPI faction = Global.getSector().getFaction(o.factionId);
		if (faction == null || faction.isPlayerFaction() || !ThreatIncConfig.frontlinesEnabled()) return false;
		SectorEntityToken planet = Global.getSector().getEntityById(o.planetId);
		if (!(planet instanceof PlanetAPI)) return false;
		String from = stockId(o);
		java.util.Map<String, Float> stock = new java.util.LinkedHashMap<String, Float>();
		if (from != null) {
			for (String c : ThreatReserves.COMMODITIES) {
				float amount = ThreatReserves.draw(from, c, ThreatReserves.stock(from, c));
				if (amount > 0f) stock.put(c, amount);
			}
		}
		remove(o, "converting to a forward base");
		MarketAPI market = raiseForwardBase(faction, planet, "converted outpost");
		// the old station is gone either way; with no base raised (a link
		// already holds the system) its stock goes to the nearest base
		if (market == null) {
			StarSystemAPI sys = planet.getStarSystem();
			ThreatIncConfig.log("Outpost: no forward base raised at " + o.planetName()
					+ (sys != null && ThreatFrontlines.linkTaken(sys) ? " - " + sys.getName() + " already holds a link" : ""));
			market = ThreatFrontlines.nearestBase(faction, planet);
		}
		if (market == null) {
			java.util.List<MarketAPI> own = ThreatReserves.marketsOf(faction.getId());
			if (!own.isEmpty()) market = own.get(0);
		}
		if (market == null) {
			ThreatIncConfig.log("Outpost: " + o.planetName() + "'s stock lost - " + o.factionId + " has no market left");
			return true;
		}
		for (java.util.Map.Entry<String, Float> e : stock.entrySet()) {
			ThreatReserves.deposit(market.getId(), e.getKey(), e.getValue());
		}
		if (!stock.isEmpty()) {
			ThreatIncConfig.log("Outpost: " + o.planetName() + "'s stock carried to " + market.getName() + " " + stock);
		}
		return true;
	}

	/**
	 * Raises the station itself, the payment already settled (or waived).
	 * Vanilla's OrbitalStation.spawnStation recipe.
	 */
	protected static Outpost raise(FactionAPI faction, PlanetAPI planet, String paidNote) {
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
		ensureStorage(o);

		ThreatNotice n = ThreatNotice.titled("Outpost Built").good().icon(faction);
		if (faction.isPlayerFaction()) {
			n.line("Your %s stands over %s", fleetName.toLowerCase(), planet.getName());
		} else {
			n.line("%s %s stands over %s", ThreatNotice.faction(faction), fleetName.toLowerCase(),
					planet.getName());
		}
		n.send();
		ThreatIncConfig.log("Outpost built: " + faction.getId() + " " + specId + " at "
				+ planet.getName() + " (" + paidNote + ")");
		return o;
	}

	/** Removes an outpost (decommissioned by order, or its station died). Its stockpile is lost with it. */
	public static void remove(Outpost o, String why) {
		all().remove(o);
		ThreatReserves.clear(stockId(o));
		if (o.entity != null && o.entity.getMarket() != null) o.entity.setMarket(null);
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
			if (o.alive() && convertToForwardBase(o)) continue;
			if (o.alive()) {
				ensureStorage(o); // a player outpost from before the storage existed
				continue;
			}
			FactionAPI faction = Global.getSector().getFaction(o.factionId);
			ThreatNotice n = ThreatNotice.titled("Outpost Destroyed").bad().icon(faction);
			if (faction != null && faction.isPlayerFaction()) {
				n.line("Your outpost over %s", o.planetName());
			} else {
				n.line("%s outpost over %s", ThreatNotice.faction(faction), o.planetName());
			}
			ThreatColonyManager.announce(n);
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
		// the stockpile comes ashore with the station: whatever the outpost was
		// holding is the new colony's reserve (remove() would otherwise drop it)
		String from = stockId(o);
		int moved = 0;
		if (from != null) {
			for (String c : ThreatReserves.COMMODITIES) {
				float amount = ThreatReserves.draw(from, c, ThreatReserves.stock(from, c));
				if (amount <= 0f) continue;
				ThreatReserves.deposit(market.getId(), c, amount);
				moved += (int) amount;
			}
		}
		ThreatNotice n = ThreatNotice.titled("Outpost Absorbed").good().icon(market.getFaction());
		if (market.getFaction().isPlayerFaction()) {
			n.line("Your outpost over %s is part of the new colony", ThreatNotice.market(market));
		} else {
			n.line("%s outpost over %s is part of the new colony", ThreatNotice.faction(market.getFaction()),
					ThreatNotice.market(market));
		}
		if (!hasStation) n.line("It serves as the colony's %s", specId.replace('_', ' '));
		if (moved > 0) n.line("%s units of stock pass to the colony's reserve", Misc.getWithDGS(moved));
		n.send();
		remove(o, "colony founded - station inherited" + (hasStation ? " (colony already had one)" : "")
				+ (moved > 0 ? ", " + moved + " units of stock carried over" : ""));
		return true;
	}

	/**
	 * Slow tick: each mobilised NPC faction rolls outpostChance to hold one
	 * open purged world within reach of a base that can pay, with a forward
	 * base - only one a found live hive lies within frontlineKeepLY of, or it
	 * would be abandoned as soon as it stood. One per tick.
	 */
	public static void planNPC(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.frontlinesEnabled()) return;
		List<PlanetAPI> open = openPurgedWorlds();
		if (open.isEmpty()) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			FactionAPI faction = Global.getSector().getFaction(factionId);
			if (faction == null || faction.isPlayerFaction()) continue;
			if (random.nextFloat() >= ThreatIncConfig.outpostChance()) continue;
			for (PlanetAPI planet : open) {
				if (holds(planet) || ThreatFrontlines.hiveNear(planet) == null) continue;
				if (ThreatFrontlines.linkTaken(planet.getStarSystem())) continue; // one faction's links per system
				MarketAPI base = payingBase(faction, planet);
				if (base == null) continue;
				// no paper bases: only where a base can spare it a garrison
				MarketAPI guardBase = ThreatIncConfig.frontlineGarrisonEnabled()
						? ThreatFrontlines.garrisonBase(faction, planet) : null;
				if (ThreatIncConfig.frontlineGarrisonEnabled() && guardBase == null) continue;
				float[] cost = npcCost();
				ThreatReserves.drawAbove(base, Commodities.SUPPLIES, cost[0]);
				ThreatReserves.drawAbove(base, Commodities.FUEL, cost[1]);
				MarketAPI link = raiseForwardBase(faction, planet, "paid from " + base.getName());
				if (link != null) {
					if (guardBase != null) ThreatFrontlines.garrisonNow(link, guardBase);
					break;
				}
			}
		}
	}
}
