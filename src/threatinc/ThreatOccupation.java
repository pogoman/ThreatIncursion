package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
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
			{ "com.fs.starfarer.api.impl.campaign.econ.impl.FuelProduction", "threatinc.OccupiedFuelProduction" } };
	/**
	 * The vanilla industry images with a dark-green copy, by file name - every variant vanilla
	 * swaps in by condition (Haven's Farming drew farming_med and stayed plain, 2026-10-27).
	 * Made by tools/occupied-images.ps1.
	 */
	protected static final String[] IMAGES = { "farming", "farming_low", "farming_med", "farming_high",
			"aquaculture", "mining", "mining_low", "gas_giant_mining", "techmining", "refining",
			"light_industry", "light_industry_low", "light_industry_high", "light_industry_orbital",
			"light_industry_orbital_low", "light_industry_orbital_high", "heavy_industry", "orbital_works",
			"fuel_production", "advanced_fuel_production" };

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
				"lightindustry", "heavyindustry", "orbitalworks", "fuelprod" }) {
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
	 * The world's industries the swarm can claim, in a fixed order (by id) so the
	 * clock takes the same next one from poll to poll: the PRODUCERS only (the user,
	 * Haven 2026-10-27: "they are infesting the battle station next???") - vanilla's
	 * industries (the "industry" tag: Mining, Farming, Refining, the yards, Fuel
	 * Production...) that make something; never a structure (ports, waystation,
	 * stations, the defences the siege wears), never Population, never ours.
	 */
	public static List<Industry> claimable(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		List<Industry> key = ThreatGroundFronts.colonyKeyStructures(market);
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getSpec() == null || ind.getId() == null) continue;
			if (key.contains(ind)) continue;
			if (ind.getId().startsWith("threatinc_")) continue;
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (!ind.isIndustry()) continue;
			if (ind.getAllSupply().isEmpty()) continue;
			list.add(ind);
		}
		Collections.sort(list, new Comparator<Industry>() {
			public int compare(Industry a, Industry b) {
				return a.getId().compareTo(b.getId());
			}
		});
		return list;
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

	/** A district retaken releases the newest claim ({@link ThreatGroundFronts#loseStratum}). */
	protected static void release(GroundFront front, MarketAPI market) {
		if (!occupies(front, market)) return;
		List<String> claims = claims(front);
		if (claims.isEmpty()) return;
		String id = claims.remove(claims.size() - 1);
		Industry ind = market.getIndustry(id);
		String name = ind != null ? ind.getCurrentName() : id;
		ThreatNotice.titled("Industry Reclaimed").good().icon(market.getFaction())
				.line("%s on %s is out of the swarm's hold.", name, ThreatNotice.market(market))
				.line("%s of %s industries claimed.", "" + claims.size(), "" + claimable(market).size())
				.send();
		ThreatIncConfig.log("Occupation: " + market.getName() + " reclaims " + name
				+ ", " + claims.size() + " still claimed");
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

	/** The share of the world's producers the swarm has claimed: claimed of claimable, 0-1. */
	public static float share(MarketAPI market, GroundFront front) {
		if (market == null || front == null) return 0f;
		List<Industry> all = claimable(market);
		if (all.isEmpty()) return 0f;
		return Math.max(0f, Math.min(1f, claimed(market, front).size() / (float) all.size()));
	}

	/**
	 * Strata a day the occupation grows: THE HIVE POOL (the user, 2026-10-27, docs/occupation.md
	 * section 6) - the rate a hive of the world's size grows when fed in full
	 * (coreStrataPerSizeMonth x size a month) x the share of its producers claimed x
	 * occupationRateMult, in game days; 0 at the cap.
	 */
	public static float growthPerDay(GroundFront front) {
		if (front == null) return 0f;
		MarketAPI market = Global.getSector().getEconomy().getMarket(front.marketId);
		if (market == null) return 0f;
		float share = share(market, front);
		if (share <= 0f || held(front, market) >= cap(market)) return 0f;
		return Math.max(0f, ThreatIncConfig.coreStrataPerSizeMonth()) * Math.max(1, market.getSize()) / 30f
				* share * Math.max(0f, ThreatIncConfig.occupationRateMult())
				/ Math.max(0.01f, IncursionManager.timeScale());
	}

	/** The most the occupation holds, all told: the strata a hive of the world's size begins at (ThreatStrata.sizeAt). */
	public static float cap(MarketAPI market) {
		return ThreatStrata.sizeAt(Math.max(1, market.getSize()));
	}

	/** What the occupation holds: the pool, the strata rising, the troops on the ground and the swarms above, as strata. */
	public static float held(GroundFront front, MarketAPI market) {
		return Math.max(0f, front.budPool) + Math.max(0f, front.budRising) + Math.max(0f, front.marines)
				+ ThreatStrata.strataOf(standingFP(market));
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
			if (front.budRising + front.budPool < wantStrata) {
				ThreatIncConfig.logQuiet("occshort:" + market.getId(), "Occupation: " + name + " cannot match "
						+ (int) hostile + " FP over it (" + (int) standing + " FP standing, "
						+ (int) ThreatStrata.fpOf(front.budPool + front.budRising)
						+ " FP in the ground); the swarm stays in the ground");
				return;
			}
			float move = Math.min(flow, Math.min(front.budPool, wantStrata - front.budRising));
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
		if (front.orbitQuietDays >= ThreatIncConfig.defendLingerDays()
				&& absorb(front, market, ThreatStrata.fpOf(flow))) return;
		if (holdsAll(front, market) && ThreatGroundFronts.holdGap(front, market) <= 0f
				&& ThreatGroundFronts.counterGap(front, market) <= 0f) {
			int spare = (int) Math.min(flow, front.marines - groundWant(front, market));
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
	 * ThreatBudding.absorbHulls); whether anything was absorbed.
	 */
	protected static boolean absorb(GroundFront front, MarketAPI market, float fp) {
		if (fp <= 0f) return false;
		CampaignFleetAPI pick = null;
		for (ThreatSwarmDefend.Entry e : ThreatSwarmDefend.all()) {
			if (e.fleet == null || !e.fleet.isAlive() || e.fleet.isExpired() || e.fleet.getBattle() != null) continue;
			if (!market.getId().equals(e.marketId) || !Factions.THREAT.equals(e.factionId)) continue;
			if (!ThreatGroundFronts.nearWorld(e.fleet, market)) continue;
			if (pick == null || e.fleet.getFleetPoints() < pick.getFleetPoints()) pick = e.fleet;
		}
		if (pick == null) return false;
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
