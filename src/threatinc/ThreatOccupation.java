package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.util.Misc;

import threatinc.ThreatGroundFronts.GroundFront;

/**
 * THE OCCUPATION (the user, 2026-10-27, docs/occupation.md): the swarm never takes a
 * human world. Its landing claims the world's industries one by one on a clock while it
 * holds a district, eats what each claimed industry makes (its output goes x0 to the
 * market, {@link ThreatGroundWarCondition}, the units read into the front) and grows on
 * it: troops on the ground while districts are still contested, a swarm budded over the
 * world once every district is held. Ownership never moves, vanilla's zero-stability
 * decivilisation is held off, and the world is reclaimed by pushing the front off it -
 * a district retaken releases the newest claim. The colony screen shows the
 * {@link ThreatInfestation} card while anything is claimed.
 */
public class ThreatOccupation {

	/** Days the deciv hold is renewed for each tick; it lapses on its own once the front is gone. */
	protected static final float DECIV_HOLD_DAYS = 90f;

	/**
	 * THE MARKER (the user, 2026-10-27: "a marker like the mining drug shortage icon"): a
	 * commodity of ours (commodities.csv, nonecon, the Threat crest) that every claimed
	 * industry demands one unit of and nothing supplies, so vanilla's own shortage icon sits
	 * on the claimed industry's card and a Threat Infestation line in its demand list
	 * ({@link ThreatGroundWarCondition#apply}).
	 */
	public static final String MARK = "threatinc_infested";

	/** Where the dark-green copies of vanilla's producing-industry images live (fix 4; 2x, swarm icon boxed top-right, fix 5). */
	public static final String OCCUPIED_IMAGES = "graphics/threatinc/industry/occupied/";
	protected static final String VANILLA_IMAGES = "graphics/icons/industry/";
	/** Vanilla's producing-industry plugins and ours over them (the image), by vanilla class name. */
	protected static final String[][] PLUGINS = {
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.Farming", "threatinc.OccupiedFarming" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.Mining", "threatinc.OccupiedMining" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.TechMining", "threatinc.OccupiedTechMining" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.Refining", "threatinc.OccupiedRefining" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.LightIndustry", "threatinc.OccupiedLightIndustry" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.HeavyIndustry", "threatinc.OccupiedHeavyIndustry" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.FuelProduction", "threatinc.OccupiedFuelProduction" },
			// consumption (2026-10-28): the military structures draw their consumption bar too
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.MilitaryBase", "threatinc.OccupiedMilitaryBase" },
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.GroundDefenses", "threatinc.OccupiedGroundDefenses" } };
	/**
	 * The vanilla industry images with a dark-green copy, by file name - every variant vanilla
	 * swaps in by condition (Haven's Farming drew farming_med and stayed plain, 2026-10-27).
	 * Made by tools/occupied-images.ps1.
	 */
	protected static final String[] IMAGES = { "farming", "farming_low", "farming_med", "farming_high",
			"aquaculture", "mining", "mining_low", "gas_giant_mining", "techmining", "refining",
			"light_industry", "light_industry_low", "light_industry_high", "light_industry_orbital",
			"light_industry_orbital_low", "light_industry_orbital_high", "heavy_industry", "orbital_works",
			"fuel_production", "advanced_fuel_production",
			// the military structures (2026-10-28 evening, the user: a claimed Ground Defenses drew plain)
			"ground_defenses", "heavy_batteries", "heavy_batteries_orbital", "patrol_hq", "military_base",
			"military_base_orbital", "high_command", "lions_guard" };

	/**
	 * XStream: our plugin classes write as vanilla's names and vanilla's names read as ours,
	 * so every Mining in a save loads as an OccupiedMining and a save stays loadable without
	 * the mod. The name a save stores is the class's SIMPLE name ({@code <Mining z=...>},
	 * the user's save 2026-10-27), not its class name - the class-name alias of fix 4 matched
	 * nothing, which is also why the planetary shield needed its migrate fallback. Holds only
	 * while the subclasses declare no fields; {@link #ensurePlugins} is the fallback.
	 */
	public static void aliasPlugins(com.thoughtworks.xstream.XStream x) {
		for (String[] p : PLUGINS) {
			try {
				x.alias(p[0].substring(p[0].lastIndexOf('.') + 1), Class.forName(p[1]));
			} catch (Throwable t) {
				ThreatIncConfig.log("Occupation: no alias for " + p[1] + ": " + t);
			}
		}
	}

	/**
	 * The alias's fallback: a claimed industry still on vanilla's plugin (loaded before the
	 * alias held, or built while it did not) is rebuilt on ours, the planetary-shield recipe
	 * (ThreatIncModPlugin.migrateExistingShields) - AI core, improvement, special item and
	 * disruption carried over, a build in progress left for a later day. Whether any was swapped.
	 */
	protected static boolean ensurePlugins(MarketAPI market, List<String> claims) {
		boolean any = false;
		for (String id : new ArrayList<String>(claims)) {
			Industry old = market.getIndustry(id);
			if (old == null || old.isBuilding() || old.getSpec() == null) continue;
			String plugin = old.getSpec().getPluginClass();
			if (plugin == null || !plugin.startsWith("threatinc.Occupied")) continue;
			if (plugin.equals(old.getClass().getName())) continue;
			String aiCore = old.getAICoreId();
			boolean improved = old.isImproved();
			SpecialItemData special = old.getSpecialItem();
			float disrupted = old.getDisruptedDays();
			market.removeIndustry(id, null, false);
			market.addIndustry(id);
			Industry fresh = market.getIndustry(id);
			if (fresh == null) continue;
			if (aiCore != null) fresh.setAICoreId(aiCore);
			if (improved) fresh.setImproved(true);
			if (special != null) fresh.setSpecialItem(special);
			if (disrupted > 0f) fresh.setDisrupted(disrupted, false);
			any = true;
			ThreatIncConfig.log("Occupation: " + market.getName() + " " + fresh.getCurrentName() + " rebuilt on "
					+ plugin + " (was " + old.getClass().getName() + ")");
		}
		return any;
	}

	/** At application load: the specs' plugins are ours, so new industries draw the image too, and the images are loaded. */
	public static void registerPlugins() {
		for (String id : new String[] { "farming", "aquaculture", "mining", "techmining", "refining",
				"lightindustry", "heavyindustry", "orbitalworks", "fuelprod",
				"patrolhq", "militarybase", "highcommand", "grounddefenses", "heavybatteries" }) {
			com.fs.starfarer.api.loading.IndustrySpecAPI spec = com.fs.starfarer.api.Global.getSettings().getIndustrySpec(id);
			if (spec == null) continue;
			for (String[] p : PLUGINS) {
				if (p[0].equals(spec.getPluginClass())) spec.setPluginClass(p[1]);
			}
		}
		for (String name : IMAGES) {
			try {
				com.fs.starfarer.api.Global.getSettings().loadTexture(OCCUPIED_IMAGES + name + ".png");
			} catch (Throwable t) {
				ThreatIncConfig.log("Occupation: image not loaded " + name + ": " + t);
			}
		}
	}

	/** Whether the swarm's front on the industry's world has claimed it. */
	public static boolean isClaimed(Industry ind) {
		if (ind == null || ind.getMarket() == null) return false;
		GroundFront front = ThreatGroundFronts.getFront(ind.getMarket().getId());
		return occupies(front, ind.getMarket()) && claims(front).contains(ind.getId());
	}

	/** The image a producing industry draws: its dark-green copy while claimed (where one exists), vanilla's otherwise. */
	public static String image(Industry ind, String base) {
		if (base == null || !isClaimed(ind) || !base.startsWith(VANILLA_IMAGES)) return base;
		String name = base.substring(VANILLA_IMAGES.length());
		for (String n : IMAGES) {
			if ((n + ".png").equals(name)) return OCCUPIED_IMAGES + name;
		}
		return base;
	}

	public static boolean enabled() {
		return ThreatIncConfig.occupationEnabled();
	}

	/** Whether the front is the swarm's on a human world still in the economy: the only fronts that occupy. */
	public static boolean occupies(GroundFront front, MarketAPI market) {
		return enabled() && front != null && market != null && market.isInEconomy()
				&& ThreatGroundFronts.isThreatOwned(front) && !ThreatGroundFronts.isHiveTarget(market);
	}

	/** Whether the swarm holds every district: the fight on the ground is over until the defenders push. */
	public static boolean holdsAll(GroundFront front, MarketAPI market) {
		return occupies(front, market) && front.strataHeld >= Math.max(1, market.getSize());
	}

	/**
	 * Whether the colony screen shows the infestation card: never since 2026-10-27 (the
	 * user, Haven: "why is there a new military base saying threat infestation") - the
	 * marker is on each claimed industry ({@link #MARK}) and the figures are the Ground War
	 * condition's. False keeps {@link ThreatGroundFronts#syncSiegeState} removing any card
	 * a save still holds.
	 */
	public static boolean showsCard(MarketAPI market) {
		return false;
	}

	/** The front's claims, in claim order (older saves hold none). */
	public static List<String> claims(GroundFront front) {
		if (front.claims == null) front.claims = new ArrayList<String>();
		return front.claims;
	}

	/**
	 * The world's PRODUCERS the swarm claims first, in a fixed order (by id) so the clock
	 * takes the same next one from poll to poll (the user, Haven 2026-10-27: "they are
	 * infesting the battle station next???"): vanilla's industries (the "industry" tag:
	 * Mining, Farming, Refining, the yards, Fuel Production...) that make something; never
	 * a structure (ports, waystation, stations), never Population, never ours. What feeds
	 * the pool ({@link #share}).
	 */
	public static List<Industry> producers(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		List<Industry> key = ThreatGroundFronts.colonyKeyStructures(market);
		// a claimed producer stays one whatever its live supply reads (fix 1, hw304: vanilla had cleared the
		// supply of a consumed industry and every claim was dropped as "no longer claimable")
		GroundFront front = ThreatGroundFronts.getFront(market.getId());
		List<String> held = front != null && front.claims != null ? front.claims : new ArrayList<String>();
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getSpec() == null || ind.getId() == null) continue;
			if (key.contains(ind)) continue;
			if (ind.getId().startsWith("threatinc_")) continue;
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (!ind.isIndustry()) continue;
			// a producer under construction has no supply yet and is a producer still (fix 3, hw305: Tigra City's
			// Orbital Works building, "nothing left to claim" after one district, a strike for nothing)
			if (ind.getAllSupply().isEmpty() && !held.contains(ind.getId()) && !underConstruction(ind)) continue;
			list.add(ind);
		}
		sortById(list);
		return list;
	}
	/**
	 * The world's MILITARY structures, claimed after the producers and consumed last (the
	 * user, 2026-10-28: "lets say they are the most well protected"): the key structures a
	 * holding front wears ({@link ThreatGroundFronts#colonyKeyStructures} - Ground Defenses,
	 * Heavy Batteries, Patrol HQ, Military Base, High Command, the Lion's Guard), never the
	 * orbital station, by id. They feed nothing. None with occupationConsumeMilitary off.
	 */
	public static List<Industry> military(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null || !ThreatIncConfig.occupationConsume() || !ThreatIncConfig.occupationConsumeMilitary()) return list;
		Industry station = ThreatFrontlines.stationOf(market);
		for (Industry ind : ThreatGroundFronts.colonyKeyStructures(market)) {
			if (ind == null || ind.getId() == null || ind == station) continue;
			if (ind.getId().startsWith("threatinc_")) continue;
			list.add(ind);
		}
		sortById(list);
		return list;
	}
	/** The world's industries the swarm claims, in claim order: the producers, then the military. */
	public static List<Industry> claimable(MarketAPI market) {
		List<Industry> list = producers(market);
		list.addAll(military(market));
		return list;
	}
	protected static void sortById(List<Industry> list) {
		Collections.sort(list, new Comparator<Industry>() {
			public int compare(Industry a, Industry b) {
				return a.getId().compareTo(b.getId());
			}
		});
	}
	/**
	 * Vanilla's building flag of an industry, never the Occupied plugins' overridden isBuilding (fix 4, 2026-10-28:
	 * producers -> isBuilding -> consuming -> active -> producers recursed without end, the StackOverflowError
	 * swallowed by consuming's catch, and the game hung at a tick - hw306a, hw308b).
	 */
	public static boolean underConstruction(Industry ind) {
		if (ind instanceof OccupiedIndustry) return ((OccupiedIndustry) ind).underConstruction();
		return ind != null && ind.isBuilding();
	}
	/** Whether a strike has anything to eat on the world: something claimable, or consumption off (fix 3: no strike for nothing). */
	public static boolean worthEating(MarketAPI market) {
		return !ThreatIncConfig.occupationConsume() || market == null || !claimable(market).isEmpty();
	}
	/** The industry spec, null for an unknown id (vanilla throws). */
	public static com.fs.starfarer.api.loading.IndustrySpecAPI spec(String id) {
		if (id == null) return null;
		try {
			return Global.getSettings().getIndustrySpec(id);
		} catch (Throwable t) {
			return null;
		}
	}
	/** Whether the industry id is a military structure (vanilla's tactical-bombardment tag), by spec - a consumed one included. */
	public static boolean isMilitary(String id) {
		com.fs.starfarer.api.loading.IndustrySpecAPI spec = spec(id);
		return spec != null && spec.hasTag(Industries.TAG_TACTICAL_BOMBARDMENT);
	}
	/** The claimed industries still on the market, in claim order. */
	public static List<Industry> claimed(MarketAPI market, GroundFront front) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null || front == null) return list;
		for (String id : claims(front)) {
			Industry ind = market.getIndustry(id);
			if (ind != null) list.add(ind);
		}
		return list;
	}

	/** The next industry the clock claims; null when every one is claimed. */
	public static Industry next(MarketAPI market, GroundFront front) {
		if (market == null || front == null) return null;
		List<String> claims = claims(front);
		for (Industry ind : claimable(market)) {
			if (!claims.contains(ind.getId())) return ind;
		}
		return null;
	}

	/** Days until the clock claims the next industry. */
	public static float daysToNextClaim(GroundFront front) {
		if (front == null) return 0f;
		return Math.max(0f, Math.max(1f, ThreatIncConfig.occupationClaimDays()) - front.claimDays);
	}

	/**
	 * One tick of the front ({@link ThreatGroundFronts}'s tick, after the attrition):
	 * the clock claims while a district is held, the eaten output grows the pool at a hive's
	 * rate and the swarm shifts it between the ground and the orbit (#shift); vanilla's deciv is held off.
	 */
	protected static void tick(GroundFront front, MarketAPI market, float elapsedDays) {
		if (!occupies(front, market)) return;
		boolean changed = false;
		if (front.claims == null && front.strataHeld > 0) {
			// a front from before the occupation (the user's save, Arkoman 2026-10-27): the
			// industries its districts had seized and pinned are its claims from the start,
			// and their pins are lifted - a claimed industry runs for the swarm
			List<String> seeded = claims(front);
			for (Industry ind : ThreatGroundFronts.seizedIndustries(market)) {
				seeded.add(ind.getId());
				if (ind.isDisrupted()) ind.setDisrupted(0f);
			}
			changed = true;
			ThreatIncConfig.log("Occupation: " + market.getName() + " starts with " + seeded.size()
					+ " industries claimed from the " + front.strataHeld + " districts held");
		}
		List<String> claims = claims(front);
		// claims of industries gone from the market (shut down, raided away), or that
		// the rule no longer claims (a station claimed by an older jar), are dropped
		List<String> allowed = new ArrayList<String>();
		for (Industry ind : claimable(market)) allowed.add(ind.getId());
		for (Iterator<String> it = claims.iterator(); it.hasNext();) {
			if (!allowed.contains(it.next())) {
				it.remove();
				changed = true;
			}
		}
		// a district held is an industry claimed, at once (the user, Haven 2026-10-27: two
		// districts taken and nothing marked): claims behind the districts held catch up
		// here and in takeStratum; the clock claims the rest while ground is held
		if (catchUp(front, market)) changed = true;
		if (front.strataHeld > 0) {
			float per = Math.max(1f, ThreatIncConfig.occupationClaimDays());
			front.claimDays += elapsedDays;
			while (front.claimDays >= per) {
				Industry nxt = next(market, front);
				if (nxt == null) {
					// everything claimed: the clock waits, full, for the next industry built
					front.claimDays = per;
					break;
				}
				front.claimDays -= per;
				claims.add(nxt.getId());
				changed = true;
				announceClaim(front, market, nxt);
			}
		}
		// CONSUMPTION (the user, 2026-10-28): the oldest claims, as many as districts held, are eaten over
		// their build time and removed; with nothing left to claim the swarm lifts off (section 7)
		if (consume(front, market, elapsedDays)) changed = true;
		if (liftOff(front, market)) return;
		if (holdsAll(front, market)) {
			// nothing left to take: the landing stands, and never spends itself pushing
			front.stance = ThreatGroundFronts.STANCE_ENTRENCH;
			front.pushProgress = Math.min(front.pushProgress, 0f);
		}
		// a claimed industry draws its green image only on our plugin
		if (ensurePlugins(market, claims)) changed = true;
		if (changed) ThreatGroundFronts.reapply(front.marketId);
		holdDeciv(market);
		// THE HIVE POOL (section 6): the eaten output grows the pool at a hive's rate, to the
		// cap; the swarm shifts it between the ground and the orbit over time
		float grow = growthPerDay(front) * elapsedDays;
		if (grow > 0f) front.budPool += Math.min(grow, Math.max(0f, cap(market) - held(front, market)));
		shift(front, market, elapsedDays);
	}

	/** Claims one industry per district held that is not yet claimed; whether anything was claimed. */
	protected static boolean catchUp(GroundFront front, MarketAPI market) {
		boolean any = false;
		List<String> claims = claims(front);
		while (claims.size() < front.strataHeld) {
			Industry nxt = next(market, front);
			if (nxt == null) break;
			claims.add(nxt.getId());
			announceClaim(front, market, nxt);
			any = true;
		}
		return any;
	}

	/**
	 * Every claim left goes (the last district retaken, {@link ThreatGroundFronts#loseStratum}; the user,
	 * Arkoman 2026-10-28 evening, "we regained 5/5 but now mining is still green and stuck on consumed
	 * 34 days left"): a front holding no district holds no industry - each is released as a retaken
	 * district releases one, rebuilt from its eaten share.
	 */
	protected static void releaseAll(GroundFront front, MarketAPI market) {
		if (!occupies(front, market)) return;
		int guard = 64;
		while (!claims(front).isEmpty() && guard-- > 0) release(front, market);
	}

	/** A district taken claims an industry on the spot ({@link ThreatGroundFronts#takeStratum}); the reapply follows there. */
	protected static void onDistrictTaken(GroundFront front, MarketAPI market) {
		if (!occupies(front, market)) return;
		catchUp(front, market);
	}

	/** The last district is the swarm's: the world is occupied, not taken ({@link ThreatGroundFronts#colonyGroundVictory}). */
	protected static void occupy(GroundFront front, MarketAPI market) {
		front.pushProgress = 0f;
		front.stance = ThreatGroundFronts.STANCE_ENTRENCH;
		front.consolidateDaysLeft = 0f;
		holdDeciv(market);
		if (!front.occupiedAnnounced) {
			front.occupiedAnnounced = true;
			ThreatNotice.titled("World Occupied").bad().icon(market.getFaction())
					.line("The swarm holds every district of %s.", ThreatNotice.market(market))
					.line("The world remains %s's.", ThreatNotice.faction(market.getFaction()))
					.send();
		}
		ThreatIncConfig.log("Occupation: the swarm holds every district of " + market.getName()
				+ " (" + claims(front).size() + " industries claimed, " + (int) front.marines + " troops)");
		ThreatGroundFronts.reapply(front.marketId);
	}

	/**
	 * A district retaken releases a claim ({@link ThreatGroundFronts#loseStratum}): the newest one being
	 * consumed (a district held is a consumption slot, and the last slot closes), else the newest claim.
	 * What the swarm had eaten of it is REBUILT (the user, 2026-10-28: "the industry should start
	 * rebuilding from where it was consumed up to ... it shouldnt just instantly be restored"): vanilla's
	 * own build, its progress set so the days left are the eaten share of the build time - unpaid, the
	 * world's own work.
	 */
	protected static void release(GroundFront front, MarketAPI market) {
		if (!occupies(front, market)) return;
		List<String> claims = claims(front);
		if (claims.isEmpty()) return;
		// the slot is read BEFORE the district count drops (ThreatGroundFronts.loseStratum calls this first):
		// Arkoman 2026-10-28 evening, one district held, Mining being eaten - decremented first, active
		// read empty and the newest claim (Orbital Works) was freed, Mining stayed green at "34 days left"
		List<String> active = active(front, market);
		String id = active.isEmpty() ? claims.get(claims.size() - 1) : active.get(active.size() - 1);
		claims.remove(id);
		Float eatenDays = consumeDays(front).remove(id);
		Industry ind = market.getIndustry(id);
		String name = ind != null ? ind.getCurrentName() : id;
		String rebuild = null;
		if (ind != null && eatenDays != null && eatenDays > 0f && ind instanceof BaseIndustry && !underConstruction(ind)) {
			float share = Math.max(0f, Math.min(1f, eatenDays / consumeTime(ind)));
			ind.startBuilding();
			((BaseIndustry) ind).setBuildProgress(Math.max(0f, ind.getBuildTime() * (1f - share)));
			rebuild = Math.round(share * 100f) + "% eaten, " + (int) Math.ceil(ind.getBuildTime() * share) + " days to rebuild";
		}
		ThreatNotice notice = ThreatNotice.titled("Industry Reclaimed").good().icon(market.getFaction())
				.line("%s on %s is out of the swarm's hold.", name, ThreatNotice.market(market))
				.line("%s of %s industries claimed.", "" + claims.size(), "" + claimable(market).size());
		if (rebuild != null) notice.line("Rebuilding: %s.", rebuild);
		notice.send();
		ThreatIncConfig.log("Occupation: " + market.getName() + " reclaims " + name
				+ ", " + claims.size() + " still claimed" + (rebuild != null ? " (rebuilding from " + rebuild + ")" : ""));
		// the front's reapply follows in loseStratum
	}

	protected static void announceClaim(GroundFront front, MarketAPI market, Industry ind) {
		ThreatNotice.titled("Industry Claimed").bad()
				.line("The swarm has claimed %s on %s.", ind.getCurrentName(), ThreatNotice.market(market))
				.line("%s of %s industries claimed.", "" + claims(front).size(), "" + claimable(market).size())
				.send();
		ThreatIncConfig.log("Occupation: the swarm claims " + ind.getCurrentName() + " on "
				+ market.getName() + " (" + claims(front).size() + " claimed)");
	}

	// ------------------------------------------------------------------
	// CONSUMPTION (the user, 2026-10-28, docs/occupation.md 7): "the threat actually full
	// consume each industry which results in it being destroyed/removed ... once the
	// industry is consumed they no longer generate troops from it and if a world has no
	// industries left they lift off and find another"; military last, no lump reward, as
	// many at once as districts held, the owner's builder restores the world.
	// ------------------------------------------------------------------
	/** Market memory: the industry ids the swarm has consumed here that the owner has not rebuilt (ThreatCoreBuild.restoreStep). */
	public static final String CONSUMED_KEY = "$threatinc_consumed";
	/** Market memory (expires, occupationGuttedDays): the swarm lifted off with nothing left to eat - no strike targets the world, vanilla's deciv is held off. */
	public static final String GUTTED_KEY = "$threatinc_gutted";
	/** The industries the swarm has consumed on the world and the owner has not rebuilt; the live list when create, else possibly a copy. */
	@SuppressWarnings("unchecked")
	public static List<String> consumed(MarketAPI market, boolean create) {
		if (market == null) return new ArrayList<String>();
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		Object val = mem.get(CONSUMED_KEY);
		if (val instanceof List) return (List<String>) val;
		List<String> list = new ArrayList<String>();
		if (create) mem.set(CONSUMED_KEY, list);
		return list;
	}
	/** The front's consumption clocks by industry id (older saves hold none). */
	public static Map<String, Float> consumeDays(GroundFront front) {
		if (front.consumeDays == null) front.consumeDays = new HashMap<String, Float>();
		return front.consumeDays;
	}
	/** Days the swarm takes to consume the industry: its vanilla build time x occupationConsumeMult, a day at least. */
	public static float consumeTime(Industry ind) {
		float days = ind != null ? ind.getBuildTime() : 0f;
		return Math.max(1f, days * Math.max(0.01f, ThreatIncConfig.occupationConsumeMult()));
	}
	/**
	 * The claims being consumed now: the oldest, as many as districts held (the user: "the
	 * number of districts held is the number of industries they can consume in parallel").
	 * The producers take the slots first; the military claims fill the slots they leave free
	 * (the user, 2026-10-28 evening, Haven - two producers chewing, a third district's slot
	 * idle over a claimed Ground Defenses: "military buildings arent actually getting
	 * consumed"). Before, a military claim waited until no producer stood at all.
	 */
	public static List<String> active(GroundFront front, MarketAPI market) {
		List<String> list = new ArrayList<String>();
		if (!ThreatIncConfig.occupationConsume() || !occupies(front, market) || front.strataHeld <= 0) return list;
		for (String id : claims(front)) {
			if (list.size() >= front.strataHeld) break;
			if (market.getIndustry(id) == null || isMilitary(id)) continue;
			list.add(id);
		}
		for (String id : claims(front)) {
			if (list.size() >= front.strataHeld) break;
			if (market.getIndustry(id) == null || !isMilitary(id) || list.contains(id)) continue;
			list.add(id);
		}
		return list;
	}
	/** Whether the swarm is consuming the industry now: its card draws vanilla's build bar as the consumption (the Occupied plugins). */
	public static boolean consuming(Industry ind) {
		try {
			if (ind == null || ind.getMarket() == null || !ThreatIncConfig.occupationConsume()) return false;
			if (Global.getSector() == null) return false;
			GroundFront front = ThreatGroundFronts.getFront(ind.getMarket().getId());
			if (front == null || !occupies(front, ind.getMarket())) return false;
			Float days = front.consumeDays != null ? front.consumeDays.get(ind.getId()) : null;
			if (days != null && days > 0f) return true;
			return active(front, ind.getMarket()).contains(ind.getId());
		} catch (StackOverflowError e) {
			throw e; // fix 4: never swallowed - it was the hang
		} catch (RuntimeException e) {
			return false;
		}
	}
	/** How far consumed the industry is, 0-1. */
	public static float consumeProgress(Industry ind) {
		if (ind == null || ind.getMarket() == null) return 0f;
		GroundFront front = ThreatGroundFronts.getFront(ind.getMarket().getId());
		if (front == null || front.consumeDays == null) return 0f;
		Float days = front.consumeDays.get(ind.getId());
		if (days == null) return 0f;
		return Math.max(0f, Math.min(1f, days / consumeTime(ind)));
	}
	/** Days until the industry is consumed. */
	public static int consumeDaysLeft(Industry ind) {
		return (int) Math.ceil(Math.max(0f, consumeTime(ind) * (1f - consumeProgress(ind))));
	}
	/** The card's bar text while consumed, on vanilla's "Building: N days left" shape. */
	public static String consumeText(Industry ind) {
		int d = consumeDaysLeft(ind);
		return "Consumed: " + d + (d == 1 ? " day" : " days") + " left";
	}
	/** The card's days text while consumed. */
	public static String consumeDaysText(Industry ind) {
		int d = consumeDaysLeft(ind);
		return d + (d == 1 ? " day" : " days");
	}
	/**
	 * One tick of the consumption: the active claims advance by the tick; one at its time
	 * is eaten ({@link #consumeIndustry}). Whether any was (the market reapplies).
	 */
	protected static boolean consume(GroundFront front, MarketAPI market, float elapsedDays) {
		if (!ThreatIncConfig.occupationConsume() || elapsedDays <= 0f) return false;
		boolean any = false;
		Map<String, Float> clocks = consumeDays(front);
		for (String id : active(front, market)) {
			Industry ind = market.getIndustry(id);
			if (ind == null) continue;
			Float days = clocks.get(id);
			float now = (days != null ? days : 0f) + elapsedDays;
			if (now < consumeTime(ind)) {
				clocks.put(id, now);
				continue;
			}
			consumeIndustry(front, market, ind);
			any = true;
		}
		return any;
	}
	/** The industry is eaten: removed from the world, remembered for the owner's builder, claimed no more. No lump reward (the user). */
	protected static void consumeIndustry(GroundFront front, MarketAPI market, Industry ind) {
		String id = ind.getId(), name = ind.getCurrentName();
		List<String> gone = consumed(market, true);
		if (!gone.contains(id)) gone.add(id);
		// THE LUMP (the user, 2026-10-28 12:50: "the threat only gets troops after it fully consumes the
		// industry, so it's kinda like a timer for the humans to fight back"): nothing while it chews, then
		// what the stream would have paid over the chew - the industry's share of the hive rate x its
		// consume days - into the pool, to the cap
		float lump = lump(market, ind);
		claims(front).remove(id);
		consumeDays(front).remove(id);
		market.removeIndustry(id, null, false);
		float added = Math.min(lump, Math.max(0f, cap(market) - held(front, market)));
		if (added > 0f) front.budPool += added;
		int left = claimable(market).size();
		ThreatNotice.titled("Industry Consumed").bad()
				.line("The swarm has consumed %s on %s.", name, ThreatNotice.market(market))
				.line("%s industries consumed, %s left to claim.", "" + gone.size(), "" + left)
				.line("%s troops to the swarm's pool.", "" + (int) added)
				.send();
		ThreatIncConfig.log("Occupation: the swarm has consumed " + name + " on " + market.getName()
				+ " (" + gone.size() + " consumed, " + left + " left to claim; +" + (int) added + " of " + (int) lump
				+ " troops to the pool, pool " + (int) front.budPool + ")");
	}
	/**
	 * THE LIFT-OFF (the user, 2026-10-28: "if a world has no industries left they lift off
	 * and find another"): ground held and nothing on the world left to claim or consume -
	 * the troops and the pool re-embark as one swarm bound for the nearest hive's bank
	 * (ThreatColonyManager.bindStrayToNearest: what reaches it is credited, what is shot
	 * down on the way is lost), the swarms above stand down, the world is marked gutted for
	 * occupationGuttedDays (no strike targets it, vanilla's deciv is held off) and the front
	 * is withdrawn. True when it lifted off.
	 */
	protected static boolean liftOff(GroundFront front, MarketAPI market) {
		if (!ThreatIncConfig.occupationConsume() || !occupies(front, market) || front.strataHeld <= 0) return false;
		if (!claims(front).isEmpty() || next(market, front) != null) return false;
		int eaten = consumed(market, false).size();
		float strata = Math.max(0f, front.budPool) + Math.max(0f, front.budRising) + Math.max(0f, front.marines);
		float fp = ThreatStrata.fpOf(strata);
		float sailed = 0f;
		String bound = null;
		SectorEntityToken planet = market.getPrimaryEntity();
		if (fp >= 1f && planet != null && planet.getContainingLocation() != null) {
			CampaignFleetAPI fleet = ThreatFleetComposer.createSized(ThreatFleetComposer.JOB_GARRISON, fp, Misc.random);
			if (fleet != null && fleet.getFleetData().getNumMembers() > 0) {
				fleet.setName("Infestation Swarm");
				ThreatColonyManager.makeDetectable(fleet);
				planet.getContainingLocation().addEntity(fleet);
				fleet.setLocation(planet.getLocation().x, planet.getLocation().y);
				bound = ThreatColonyManager.bindStrayToNearest(fleet);
				MarketAPI home = bound != null ? Global.getSector().getEconomy().getMarket(bound) : null;
				SectorEntityToken to = home != null ? home.getPrimaryEntity() : null;
				fleet.getMemoryWithoutUpdate().set(Misc.FLEET_RETURNING_TO_DESPAWN, true);
				if (to != null) {
					fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, to, 1000f, "returning to the hive");
				} else {
					fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN,
							Global.getSector().getHyperspace().createToken(0f, 0f), 1000f);
				}
				sailed = fleet.getFleetPoints();
			}
		}
		front.budPool = 0f;
		front.budRising = 0f;
		front.marines = 0f;
		front.armaments = 0f;
		standDown(front, market);
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float days = Math.max(1f, ThreatIncConfig.occupationGuttedDays());
		mem.set(GUTTED_KEY, true, days);
		if (mem.getBoolean(ThreatGroundFronts.DECIV_HELD_KEY) || !mem.contains(DecivTracker.NO_DECIV_KEY)) {
			mem.set(DecivTracker.NO_DECIV_KEY, true, days);
			mem.set(ThreatGroundFronts.DECIV_HELD_KEY, true, days);
		}
		ThreatNotice notice = ThreatNotice.titled("Swarm Lifts Off").bad().icon(market.getFaction())
				.line("The swarm has consumed %s industries on %s and lifts off.", "" + eaten, ThreatNotice.market(market));
		if (sailed > 0f) notice.line("%s fleet points re-embark for the nearest hive.", "" + (int) sailed);
		notice.send();
		ThreatIncConfig.log("Occupation: the swarm lifts off " + market.getName() + " - " + eaten + " industries consumed, nothing left to claim; "
				+ (int) strata + " strata re-embark as " + (int) sailed + " FP bound for " + (bound != null ? bound : "nowhere"));
		ThreatGroundFronts.withdraw(market.getId());
		return true;
	}
	/**
	 * Vanilla's zero-stability decivilisation is held off while the swarm stands on
	 * the world (DecivTracker: 3 months at 0 and half the year, then 10% a month): an
	 * occupied world is the owner's until it is reclaimed. Never touches the key when
	 * something else set it (the same guard as {@link ThreatGroundFronts#raiseUnrest}).
	 */
	protected static void holdDeciv(MarketAPI market) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (!mem.getBoolean(ThreatGroundFronts.DECIV_HELD_KEY) && mem.contains(DecivTracker.NO_DECIV_KEY)) return;
		mem.set(DecivTracker.NO_DECIV_KEY, true, DECIV_HOLD_DAYS);
		mem.set(ThreatGroundFronts.DECIV_HELD_KEY, true, DECIV_HOLD_DAYS);
	}

	/**
	 * The share of the world's producers the swarm has claimed, 0-1: the producers claimed over the
	 * producers the world had - a consumed one counts and feeds nothing (the user, 2026-10-28: "once the
	 * industry is consumed they no longer generate troops from it"); the military feed nothing either way.
	 */
	public static float share(MarketAPI market, GroundFront front) {
		if (market == null || front == null) return 0f;
		List<Industry> all = producers(market);
		int eaten = 0;
		for (String id : consumed(market, false)) if (!isMilitary(id)) eaten++;
		if (all.isEmpty()) return 0f;
		int feeding = 0;
		for (Industry ind : claimed(market, front)) if (all.contains(ind)) feeding++;
		return Math.max(0f, Math.min(1f, feeding / (float) (all.size() + eaten)));
	}

	/**
	 * Strata a day the occupation grows: THE HIVE POOL (the user, 2026-10-27, docs/occupation.md
	 * section 6) - the rate a hive of the world's size grows when fed in full
	 * (coreStrataPerSizeMonth x size a month) x the share of its producers claimed x
	 * occupationRateMult, in game days; 0 at the cap.
	 */
	public static float growthPerDay(GroundFront front) {
		if (front == null) return 0f;
		// under consumption nothing flows while the swarm chews: the lump at consumeIndustry replaces the stream
		if (ThreatIncConfig.occupationConsume()) return 0f;
		MarketAPI market = Global.getSector().getEconomy().getMarket(front.marketId);
		if (market == null) return 0f;
		float share = share(market, front);
		if (share <= 0f || held(front, market) >= cap(market)) return 0f;
		return Math.max(0f, ThreatIncConfig.coreStrataPerSizeMonth()) * Math.max(1, market.getSize()) / 30f
				* share * Math.max(0f, ThreatIncConfig.occupationRateMult())
				/ Math.max(0.01f, IncursionManager.timeScale());
	}

	/** The hive rate a day the pool would grow fed in full: coreStrataPerSizeMonth x size x occupationRateMult, in game days. */
	public static float ratePerDay(MarketAPI market) {
		if (market == null) return 0f;
		return Math.max(0f, ThreatIncConfig.coreStrataPerSizeMonth()) * Math.max(1, market.getSize()) / 30f
				* Math.max(0f, ThreatIncConfig.occupationRateMult()) / Math.max(0.01f, IncursionManager.timeScale());
	}
	/**
	 * THE LUMP a consumed industry pays the pool (the user, 2026-10-28: troops only once fully consumed):
	 * the stream it would have fed over the chew - the hive rate x its share of the industries the world had
	 * (claimable now + consumed) x its consume days; uncapped here (consumeIndustry caps at the pool).
	 */
	public static float lump(MarketAPI market, Industry ind) {
		if (market == null || ind == null) return 0f;
		int had = claimable(market).size() + consumed(market, false).size();
		return ratePerDay(market) / Math.max(1, had) * consumeTime(ind);
	}
	/** The most the occupation holds, all told: the strata a hive of the world's size begins at (ThreatStrata.sizeAt). */
	public static float cap(MarketAPI market) {
		return ThreatStrata.sizeAt(Math.max(1, market.getSize()));
	}

	/** What the occupation holds: the pool, the strata rising, the troops on the ground and the swarms above, as strata. */
	public static float held(GroundFront front, MarketAPI market) {
		return Math.max(0f, front.budPool) + Math.max(0f, front.budRising) + Math.max(0f, front.marines)
				+ ThreatStrata.strataOf(buddedFP(market));
	}

	/** Fleet memory: a swarm the pool budded - the only swarms it absorbs back (hw294, 2026-10-27: it ate the strike fleets defending it, pools of 179k strata). */
	public static final String BUD_KEY = "$threatinc_occBud";

	/** Fleet points of the swarms the pool budded that stand over the world. */
	public static float buddedFP(MarketAPI market) {
		float fp = 0f;
		for (ThreatSwarmDefend.Entry e : ThreatSwarmDefend.all()) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired()) continue;
			if (!market.getId().equals(e.marketId) || !e.fleet.getMemoryWithoutUpdate().getBoolean(BUD_KEY)) continue;
			fp += e.fleet.getFleetPoints();
		}
		return fp;
	}

	/** Fleet points of the swarm's Defend fleets over the world (the Infestation Swarms). */
	public static float standingFP(MarketAPI market) {
		float fp = 0f;
		for (ThreatSwarmDefend.Entry e : ThreatSwarmDefend.all()) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired()) continue;
			if (!market.getId().equals(e.marketId) || !Factions.THREAT.equals(e.factionId)) continue;
			fp += e.fleet.getFleetPoints();
		}
		return fp;
	}

	/** Strata a day the occupation moves, either way: the hive's flow (strataFlowPerDay, or strataFlowFractionPerDay of what it holds). */
	public static float flowPerDay(GroundFront front, MarketAPI market) {
		return Math.max(ThreatIncConfig.strataFlowPerDay(),
				ThreatIncConfig.strataFlowFractionPerDay() * held(front, market));
	}

	/**
	 * The troops the ground keeps standing: the hold line and the counter-attack with
	 * fabricateHoldMargin (the gaps' own arithmetic), never under a stratum
	 * (strataSizeBase) a district held nor frontMinMarines.
	 */
	public static float groundWant(GroundFront front, MarketAPI market) {
		float margin = Math.max(1f, ThreatIncConfig.fabricateHoldMargin());
		float mult = Math.max(0.01f, ThreatGroundFronts.entrenchMult(front));
		float hold = ThreatGroundFronts.holdRequirement(market) * margin / mult;
		float counter = ThreatGroundFronts.counterAttackStrength(market) * margin
				/ Math.max(0.01f, mult * ThreatMarineXP.frontEffectMult(front));
		float floor = Math.max(ThreatIncConfig.frontMinMarines(),
				ThreatStrata.perSize() * Math.max(1, front.strataHeld));
		return Math.max(floor, Math.max(hold, counter));
	}

	/**
	 * The swarm shifts its strata between the ground and the orbit over time (the user,
	 * 2026-10-27: "dynamically shift troops from space and back to the siege at will (albeit
	 * over time)"): one direction a tick, at the flow, the ground first. Down: the ground
	 * short (fabricateNeed) draws on the pool. Up: a force over the world the swarms standing
	 * do not outweigh by systemDefenceMargin draws a swarm - whole, once the strata risen
	 * hold the want, and only if the pool can cover it. Back: the orbit quiet for
	 * defendLingerDays absorbs the swarms above; the ground quiet re-embarks the troops
	 * beyond groundWant.
	 */
	protected static void shift(GroundFront front, MarketAPI market, float elapsedDays) {
		float flow = flowPerDay(front, market) * Math.max(0f, elapsedDays);
		if (flow <= 0f) return;
		String name = market.getName();
		float need = ThreatGroundFronts.fabricateNeed(front, market);
		if (need > 0f) {
			int troops = (int) Math.min(need, Math.min(flow, front.budPool));
			if (troops > 0) {
				front.budPool -= troops;
				ThreatGroundFronts.resupply(front, troops, ThreatGroundFronts.fabricateArms(front, troops));
				ThreatGroundFronts.fabricationVisual(market, Factions.THREAT, troops);
				ThreatIncConfig.logQuiet("occdown:" + market.getId(), "Occupation: " + name + " sends " + troops
						+ " troops of its pool to the front (" + (int) need + " short; " + (int) front.marines
						+ " stand, pool " + (int) front.budPool + ")");
				return;
			}
		}
		float hostile = ThreatGroundFronts.hostilePointsNear(Factions.THREAT, market);
		if (hostile > 0f) {
			front.orbitQuietDays = 0f;
			float standing = standingFP(market);
			float want = hostile * Math.max(1f, ThreatIncConfig.systemDefenceMargin()) - standing;
			if (want <= 0f) return;
			float wantStrata = ThreatStrata.strataOf(Math.max(want, ThreatIncConfig.occupationBudFP()));
			float have = front.budRising + front.budPool;
			int lifted = 0;
			if (have < wantStrata) {
				// THE ARMY IS THE STRATA TOO (the user, Haven 2026-10-28 evening: "the threat have 3-4k
				// units and still arent making a swarm to take out the battlestation" - the pool read 0,
				// filled only by the lump under consumption, while thousands stood on the ground): what
				// the front holds beyond what the ground needs (groundWant) rises off it at the flow,
				// only if the army's spare and the pool together can match the force
				float spare = Math.max(0f, front.marines - groundWant(front, market));
				if (have + spare < wantStrata) {
					ThreatIncConfig.logQuiet("occshort:" + market.getId(), "Occupation: " + name + " cannot match "
							+ (int) hostile + " FP over it (" + (int) standing + " FP standing, "
							+ (int) ThreatStrata.fpOf(have) + " FP in the ground, " + (int) spare
							+ " troops spare on the front); the swarm stays in the ground");
					return;
				}
				lifted = (int) Math.min(flow, Math.min(spare, wantStrata - have));
				if (lifted > 0) {
					front.marines -= lifted;
					front.budRising += lifted;
					ThreatBudding.visual(market, "Strata rising", ThreatStrata.fpOf(lifted), true);
					ThreatIncConfig.logQuiet("occlift:" + market.getId(), "Occupation: " + name + " takes " + lifted
							+ " troops off the front toward the orbit (" + (int) hostile + " FP over it; "
							+ (int) front.marines + " stand, " + (int) spare + " were spare)");
				}
			}
			float move = Math.min(Math.max(0f, flow - lifted), Math.min(front.budPool, wantStrata - front.budRising));
			if (move > 0f) {
				front.budPool -= move;
				front.budRising += move;
				ThreatBudding.visual(market, "Strata rising", ThreatStrata.fpOf(move), true);
			}
			if (front.budRising >= wantStrata - 0.5f) bud(front, market, hostile);
			return;
		}
		if (front.budRising > 0f) {
			front.budPool += front.budRising;
			front.budRising = 0f;
		}
		front.orbitQuietDays += elapsedDays;
		if (front.orbitQuietDays >= ThreatIncConfig.defendLingerDays()) {
			if (absorb(front, market, ThreatStrata.fpOf(flow))) return;
			// the pool full (not a tick whose bite rounds below a hull - hw298: 10 swarms sent home at 0 of 3,200): the rest go home
			if (cap(market) - held(front, market) < ThreatStrata.strataOf(1f) && standDown(front, market)) return;
		}
		if (holdsAll(front, market) && ThreatGroundFronts.holdGap(front, market) <= 0f
				&& ThreatGroundFronts.counterGap(front, market) <= 0f) {
			int spare = (int) Math.min(Math.min(flow, Math.max(0f, cap(market) - held(front, market))),
					front.marines - groundWant(front, market)); // never above the cap: the rest stand on the ground
			if (spare > 0) {
				front.marines -= spare;
				front.budPool += spare;
				ThreatBudding.visual(market, "Strata rising", ThreatStrata.fpOf(spare), true);
				ThreatIncConfig.logQuiet("occup:" + market.getId(), "Occupation: " + name + " takes " + spare
						+ " troops off the front into its pool (" + (int) front.marines + " stand; pool "
						+ (int) front.budPool + ")");
			}
		}
	}

	/** The fleet memory bank the absorb's fractions of a hull accumulate in (ThreatGroundFronts.applyFleetLosses). */
	public static final String ABSORB_KEY = "$threatinc_occAbsorbBank";

	/**
	 * The smallest swarm over the world gives up to {@code fp} of its hulls to the pool,
	 * smallest first, the whole fleet when it is down to the bite or one ship (the shape of
	 * ThreatBudding.absorbHulls); whether anything was absorbed. Any swarm of the Threat's on
	 * station here - a parked strike as much as the pool's own bud (the user, 2026-10-27 23:00: "they
	 * should when no threat detected so they help grow colony"); the cap still binds, standDown takes
	 * the rest home.
	 */
	protected static boolean absorb(GroundFront front, MarketAPI market, float fp) {
		if (fp < 1f) return false;
		// The cap binds on what the world holds - but a bud the pool raised is already counted in
		// held, so taking it back costs no room (hw300: "holds 1675 of 3200, pool 180" sent two buds
		// home). The smallest swarm that fits is taken.
		float baseRoom = cap(market) - held(front, market);
		CampaignFleetAPI pick = null;
		float pickRoom = 0f;
		for (ThreatSwarmDefend.Entry e : ThreatSwarmDefend.all()) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired() || e.fleet.getBattle() != null) continue;
			if (!market.getId().equals(e.marketId) || !Factions.THREAT.equals(e.factionId)) continue;
			if (!ThreatGroundFronts.nearWorld(e.fleet, market)) continue;
			float room = baseRoom;
			if (e.fleet.getMemoryWithoutUpdate().getBoolean(BUD_KEY)) room += ThreatStrata.strataOf(e.fleet.getFleetPoints());
			if (ThreatStrata.fpOf(Math.max(0f, room)) < 1f) continue;
			if (pick == null || e.fleet.getFleetPoints() < pick.getFleetPoints()) { pick = e.fleet; pickRoom = room; }
		}
		if (pick == null) return false;
		fp = Math.min(fp, ThreatStrata.fpOf(Math.max(0f, pickRoom))); // never above the cap
		float have = pick.getFleetPoints();
		String fleetName = pick.getName();
		float got;
		if (have <= fp + 0.5f || pick.getFleetData().getNumMembers() <= 1) {
			got = have;
			ThreatSwarmDefend.release(pick);
			pick.despawn();
		} else {
			got = ThreatGroundFronts.applyFleetLosses(pick, fp, null, ABSORB_KEY);
			if (got <= 0f) return false;
		}
		front.budPool += ThreatStrata.strataOf(got);
		ThreatBudding.visual(market, "Swarm absorbed", got, false);
		ThreatIncConfig.logQuiet("occabsorb:" + market.getId() + ":" + pick.getId(), "Occupation: " + market.getName()
				+ " absorbs " + (int) got + " FP of " + fleetName
				+ (pick.isAlive() ? " (" + (int) pick.getFleetPoints() + " FP left)" : " (the whole fleet)")
				+ "; pool " + (int) front.budPool + " strata");
		return true;
	}

	/**
	 * A quiet orbit with a full pool (the user, 2026-10-27 23:00): every swarm of the Threat's still
	 * on station here goes home to its hive, where it despawns and its points are re-banked
	 * (ThreatSwarmDefend.sendHome) - nothing of the swarm's sits over a world nobody contests. True
	 * when any was sent.
	 */
	protected static boolean standDown(GroundFront front, MarketAPI market) {
		int sent = 0;
		float fp = 0f;
		float holds = held(front, market);
		for (ThreatSwarmDefend.Entry e : new ArrayList<ThreatSwarmDefend.Entry>(ThreatSwarmDefend.all())) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired() || e.fleet.getBattle() != null) continue;
			if (!market.getId().equals(e.marketId) || !Factions.THREAT.equals(e.factionId)) continue;
			if (!ThreatGroundFronts.nearWorld(e.fleet, market)) continue;
			fp += e.fleet.getFleetPoints();
			if (!ThreatColonyManager.ledgerBound(e.fleet)) {
				// a bud is on no ledger ("Infestation Swarm is on no ledger", hw305): bound to the nearest hive's
				// bank and sent there, so its points are re-banked on arrival instead of lost (fix 3)
				String bound = ThreatColonyManager.bindStrayToNearest(e.fleet);
				if (bound != null && e.homeMarketId == null) e.homeMarketId = bound;
			}
			ThreatSwarmDefend.release(e.fleet);
			ThreatSwarmDefend.sendHome(e);
			sent++;
		}
		if (sent == 0) return false;
		ThreatIncConfig.log("Occupation: " + market.getName() + " stands down " + sent + " swarm(s), " + (int) fp
				+ " FP, quiet " + (int) front.orbitQuietDays + " d and the pool full (holds " + (int) holds + " of " + (int) cap(market)
				+ " strata, pool " + (int) front.budPool + ")");
		return true;
	}

	/**
	 * The strata risen become a swarm over the world, whole: built as a garrison swarm,
	 * stationed on Defend over the world ({@link ThreatSwarmDefend}) for as long as the
	 * front stands. What the composer could not place goes back to the pool.
	 */
	protected static void bud(GroundFront front, MarketAPI market, float against) {
		float poolFP = ThreatStrata.fpOf(front.budRising);
		if (poolFP < Math.max(1f, ThreatIncConfig.occupationBudFP())) return;
		SectorEntityToken planet = market.getPrimaryEntity();
		if (planet == null || planet.getContainingLocation() == null) return;
		CampaignFleetAPI fleet = ThreatFleetComposer.createSized(ThreatFleetComposer.JOB_GARRISON, poolFP, Misc.random);
		if (fleet == null || fleet.getFleetData().getNumMembers() == 0) {
			ThreatIncConfig.logQuiet("occnobud:" + market.getId(), "Occupation: no swarm could be built over "
					+ market.getName() + " for " + (int) poolFP + " FP; the strata wait");
			return;
		}
		fleet.setName("Infestation Swarm");
		fleet.getMemoryWithoutUpdate().set(BUD_KEY, true);
		ThreatColonyManager.makeDetectable(fleet);
		planet.getContainingLocation().addEntity(fleet);
		fleet.setLocation(planet.getLocation().x, planet.getLocation().y);
		if (ThreatSwarmDefend.attach(fleet, market, Factions.THREAT, null) == null) {
			ThreatSwarmDefend.holdOrbit(fleet, market, "defending the orbit of " + market.getName());
		}
		float spent = Math.min(front.budRising, ThreatStrata.strataOf(fleet.getFleetPoints()));
		front.budPool += Math.max(0f, front.budRising - spent);
		front.budRising = 0f;
		ThreatNotice.titled("Swarm Rising").bad()
				.line("A swarm of %s fleet points has risen over %s.",
						"" + Math.round(fleet.getFleetPoints()), ThreatNotice.market(market))
				.line("Against %s fleet points over it.", "" + Math.round(against))
				.send();
		ThreatIncConfig.log("Occupation: " + market.getName() + " buds " + fleet.getName() + ", "
				+ (int) fleet.getFleetPoints() + " FP (" + fleet.getFleetData().getNumMembers()
				+ " ships) against " + (int) against + " FP over it; pool " + (int) front.budPool + " strata");
	}
}
