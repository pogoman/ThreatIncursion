package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.util.Misc;

/**
 * THE GRAND FLEET (the user, 2026-10-26, docs/grand-fleet.md): an NPC faction's whole offence is one
 * grand fleet. It musters at the base nearest the nearest known Threat world to a share of the navy
 * (the board's Navy figure), waits for the hulls and for the fuel and supplies of a cruise, sails
 * with every marine and armament the base and its donors hold - nothing sized to the target - and
 * destroys the nearest Threat world (ships against whatever holds the orbit, fuel razing strata,
 * marines landing when the beachhead holds), then the next nearest from where it stands, until fuel
 * or supplies fall to the passage home plus a reserve. Each leg is a {@link ThreatPurgeFGI}; a hop
 * hands everything aboard - the live fleets, the marines, the ordnance, the hold - to the next leg,
 * which sails from the system just fought in ({@link ThreatPurgeFGI#hopFrom}). Knob npcGrandFleet;
 * off, the council's sized sieges run as before.
 */
public class ThreatGrandFleets {

	protected static final String KEY = "threatinc_grandFleets";

	/** One faction's grand fleet: what it musters toward, or the leg it is on. Persisted. */
	public static class GrandFleet {
		public String factionId;
		public String baseId;
		public float sizeFP;
		public String targetSystemId;
		public String why = "";
		public ThreatPurgeFGI purge;
		public int leg;
		public int destroyed;
		public long sailed;
		/** A day's burn as banked at launch: the hold over the cruise, the ordnance over the cruise. */
		public float dailySupplies, dailyFuel;
		/** The systems the muster banked fuel for after the target, in order (chainFrom): the hops try them first. */
		public List<String> chainIds;
		/** System id -> timestamp a leg was called off over it (calledOff); null on an older save. */
		public Map<String, Long> retryAfter;
		public GrandFleet() {}
	}

	@SuppressWarnings("unchecked")
	protected static Map<String, GrandFleet> all() {
		Object v = Global.getSector().getPersistentData().get(KEY);
		if (!(v instanceof Map)) {
			v = new LinkedHashMap<String, GrandFleet>();
			Global.getSector().getPersistentData().put(KEY, v);
		}
		return (Map<String, GrandFleet>) v;
	}

	public static GrandFleet of(String factionId) {
		GrandFleet g = all().get(factionId);
		if (g == null) {
			g = new GrandFleet();
			g.factionId = factionId;
			all().put(factionId, g);
		}
		return g;
	}

	/** Once a day (ThreatWarCouncil.advance): every NPC faction at war musters or sends its grand fleet. */
	public static void daily(Random random) {
		if (!ThreatIncConfig.npcGrandFleet()) return;
		for (String fid : new ArrayList<String>(ThreatWarState.warFactionIds())) {
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null || faction.isPlayerFaction() || !ThreatWarState.isAtWar(fid)) continue;
			muster(faction, random);
		}
	}

	protected static boolean out(GrandFleet g) {
		if (g.purge == null) return false;
		if (g.purge.isEnded() || g.purge.isEnding() || g.purge.isAborted()) {
			g.purge = null;
			return false;
		}
		return true;
	}

	/** The faction's military score, the board's Navy figure: hulls free and at sea. */
	public static float navy(String factionId) {
		return ThreatHulls.freeFP(factionId) + ThreatHulls.committedFP(factionId);
	}

	/**
	 * What the faction's grand fleet musters to: the share of its navy, within the floor and cap. The floor is a
	 * floor (run gf1, 2026-10-26: the Diktat sailed its whole 243-FP navy, then 75 FP, into a 1,214-FP swarm):
	 * a navy below it waits for its yards.
	 */
	public static float musterFP(String factionId) {
		float navy = navy(factionId);
		float size = Math.max(ThreatIncConfig.grandFleetShare() * navy, ThreatIncConfig.grandFleetMinFP());
		return Math.max(0f, Math.min(size, ThreatIncConfig.grandFleetMaxFP()));
	}

	/**
	 * The fuel that razes every hive of the targets to nothing as they stand on arrival (ThreatStrata.atArrival,
	 * ThreatGroundFronts.fuelPerStratum), x grandFleetOrdnanceMargin for what grows under the guns. Run gf1
	 * (2026-10-26) banked grandFleetCruiseDays of razing at the fleet's full rate instead: 723,000 fuel for the
	 * Hegemony's 3,000 FP, and no grand fleet but the Diktat's sailed in a year.
	 */
	public static float razeFuel(List<MarketAPI> targets) {
		float strata = 0f;
		if (targets != null) {
			for (MarketAPI t : targets) {
				if (t != null && t.isInEconomy() && ThreatStrata.has(t)) strata += Math.max(0f, ThreatStrata.atArrival(t));
			}
		}
		return strata * ThreatGroundFronts.fuelPerStratum() * Math.max(1f, ThreatIncConfig.grandFleetOrdnanceMargin());
	}

	/** The swarm fleet points the observer last saw over the system's worlds (IncursionManager.systemSwarms) x grandFleetOutweigh: what a grand fleet must bring to pick it. */
	public static float swarmsSeen(String observer, StarSystemAPI system) {
		return IncursionManager.systemSwarms(observer, system) * Math.max(0f, ThreatIncConfig.grandFleetOutweigh());
	}

	/** Whether a leg called off over the system within grandFleetRetryDays: it is not picked again until then. */
	public static boolean onCooldown(GrandFleet g, StarSystemAPI system) {
		if (g == null || g.retryAfter == null || system == null) return false;
		Long at = g.retryAfter.get(system.getId());
		if (at == null) return false;
		if (Global.getSector().getClock().getElapsedDaysSince(at) < Math.max(0f, ThreatIncConfig.grandFleetRetryDays())) return true;
		g.retryAfter.remove(system.getId());
		return false;
	}

	/** A leg called off over a world (ThreatPurgeFGI.callOff, outweighed by the swarms it met): its system waits grandFleetRetryDays. */
	public static void calledOff(ThreatPurgeFGI purge, MarketAPI at) {
		if (purge == null || !purge.isGrandFleet() || purge.getFaction() == null || at == null || at.getStarSystem() == null) return;
		GrandFleet g = of(purge.getFaction().getId());
		if (g.retryAfter == null) g.retryAfter = new HashMap<String, Long>();
		g.retryAfter.put(at.getStarSystem().getId(), Global.getSector().getClock().getTimestamp());
	}

	/** Difficulty points, grandFleetPointsPerFleet a fleet, summing to about fp / 25. */
	public static List<Integer> sizesFor(float fp) {
		List<Integer> sizes = new ArrayList<Integer>();
		int points = Math.round(fp / IncursionManager.FP_PER_RESPONSE_DIFFICULTY);
		int per = ThreatIncConfig.grandFleetPointsPerFleet();
		while (points >= per) {
			sizes.add(per);
			points -= per;
		}
		if (points >= 3 || sizes.isEmpty()) sizes.add(Math.max(3, points));
		return sizes;
	}

	/** The known Threat worlds, by star system, as the observer knows them. */
	protected static Map<StarSystemAPI, List<MarketAPI>> knownHives(String observer) {
		Map<StarSystemAPI, List<MarketAPI>> by = new LinkedHashMap<StarSystemAPI, List<MarketAPI>>();
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			if (m == null || !m.isInEconomy() || !ThreatGroundFronts.isHiveTarget(m)) continue;
			StarSystemAPI s = m.getStarSystem();
			if (s == null || !ThreatIntel.known(observer, s.getId())) continue;
			List<MarketAPI> l = by.get(s);
			if (l == null) by.put(s, l = new ArrayList<MarketAPI>());
			l.add(m);
		}
		return by;
	}

	/** The faction's bases (its own, and allies' staging hosts) in reach of the system, nearest first. */
	protected static List<MarketAPI> basesFor(String fid, StarSystemAPI s) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		for (MarketAPI b : IncursionManager.siegeBasesFor(s)) {
			if (b == null || !b.isInEconomy()) continue;
			if (fid.equals(b.getFactionId()) || ThreatCoalition.stagingHost(b, fid)) out.add(b);
		}
		return out;
	}

	protected static float ly(StarSystemAPI a, StarSystemAPI b) {
		if (a == null || b == null) return Float.MAX_VALUE;
		return Misc.getDistanceLY(a.getLocation(), b.getLocation());
	}

	protected static void muster(FactionAPI faction, Random random) {
		String fid = faction.getId();
		GrandFleet g = of(fid);
		if (out(g)) return;
		float size = musterFP(fid);
		g.sizeFP = size;
		if (size <= 0f) {
			g.why = "no navy";
			return;
		}
		// the nearest known Threat world a base of ours reaches, and that base
		String observer = ThreatIntel.observerOf(faction);
		StarSystemAPI target = null;
		MarketAPI base = null;
		float best = Float.MAX_VALUE;
		Map<StarSystemAPI, List<MarketAPI>> hives = knownHives(observer);
		String refused = null;
		float refusedAt = Float.MAX_VALUE;
		for (StarSystemAPI s : hives.keySet()) {
			List<MarketAPI> bases = basesFor(fid, s);
			if (bases.isEmpty()) continue;
			float d = ly(bases.get(0).getStarSystem(), s);
			if (d >= best) continue;
			// run gf1 (2026-10-26): a 250-FP grand fleet sailed into Sindria's 1,214-FP swarm four times over
			if (onCooldown(g, s)) {
				if (d < refusedAt) { refusedAt = d; refused = "called off over " + s.getName() + " lately"; }
				continue;
			}
			float swarms = swarmsSeen(observer, s);
			if (swarms > size) {
				if (d < refusedAt) {
					refusedAt = d;
					refused = Misc.getWithDGS(Math.round(swarms)) + " FP of swarms seen at " + s.getName() + " against " + (int) size;
				}
				continue;
			}
			best = d;
			target = s;
			base = bases.get(0);
		}
		if (target == null || base == null) {
			g.why = refused != null ? "outweighed - " + refused : "no known Threat world in reach";
			g.baseId = null;
			g.targetSystemId = null;
			ThreatIncConfig.logQuiet("grand:" + fid, "Grand fleet " + fid + ": " + (refused != null
					? "no Threat world it outweighs - " + refused : "no known Threat world in reach of a base"));
			return;
		}
		g.baseId = base.getId();
		g.targetSystemId = target.getId();
		List<MarketAPI> targets = hives.get(target);
		// the hulls
		float free = ThreatHulls.enabled() ? ThreatHulls.freeFP(fid) : size;
		if (free < size) {
			g.why = "hulls " + (int) free + " of " + (int) size + " FP";
			ThreatFactionStock.noteHullShort(fid, size - free);
			ThreatIncConfig.logQuiet("grand:" + fid, "Grand fleet " + fid + ": musters at " + base.getName() + " for "
					+ target.getName() + " - " + g.why);
			return;
		}
		// the cruise: the passage out, the fuel that razes the targets (razeFuel), grandFleetCruiseDays of the hold
		List<Integer> sizes = sizesFor(size);
		float fp = ThreatAidCapacity.expeditionPoints(sizes);
		int points = 0;
		for (Integer s : sizes) points += s;
		float cruise = Math.max(1f, ThreatIncConfig.grandFleetCruiseDays());
		float passage = IncursionManager.expeditionPassage(base, target, sizes);
		float ordnance = razeFuel(targets);
		// the bank is grandFleetKills kills (the user, 2026-10-27, after gf2's one-kill bank paid no hop): from the
		// target, the next nearest worlds it outweighs, their razing, the passages between them and the one home
		List<StarSystemAPI> chain = heldChain(g, observer, target, size, hives, ThreatIncConfig.grandFleetKills() - 1);
		g.chainIds = new ArrayList<String>();
		for (StarSystemAPI s : chain) g.chainIds.add(s.getId());
		if (!chain.isEmpty()) {
			float rate = ThreatIncConfig.expeditionFuelPerPointLY();
			StarSystemAPI last = target;
			for (StarSystemAPI s : chain) {
				ordnance += points * ly(last, s) * rate + razeFuel(hives.get(s));
				last = s;
			}
			ordnance += points * ly(last, base.getStarSystem()) * rate;
		}
		float fuelWant = passage + ordnance;
		float suppliesWant = IncursionManager.siegeSuppliesPerPoint(base, faction, target, cruise) * points;
		List<MarketAPI> donors = IncursionManager.siegeDonors(base, faction, target);
		List<MarketAPI> pool = ThreatIncConfig.siegePoolProvisions() ? donors : new ArrayList<MarketAPI>();
		List<MarketAPI> marinePool = ThreatIncConfig.siegePoolMarines() ? donors : new ArrayList<MarketAPI>();
		float haveFuel = IncursionManager.siegePooled(base, pool, Commodities.FUEL);
		float haveSupplies = Math.max(0f, IncursionManager.siegePooled(base, pool, Commodities.SUPPLIES)
				- ThreatFactionStock.yardWant(fid));
		float marines = IncursionManager.siegePooled(base, marinePool, Commodities.MARINES);
		float arms = IncursionManager.siegePooled(base, marinePool, Commodities.HAND_WEAPONS);
		if (haveFuel < fuelWant || haveSupplies < suppliesWant) {
			g.why = haveFuel < fuelWant ? "fuel " + Misc.getWithDGS(Math.round(haveFuel)) + " of " + Misc.getWithDGS(Math.round(fuelWant))
					: "supplies " + Misc.getWithDGS(Math.round(haveSupplies)) + " of " + Misc.getWithDGS(Math.round(suppliesWant));
			if (haveFuel < fuelWant) ThreatFactionStock.noteShort(fid, Commodities.FUEL, fuelWant - haveFuel);
			if (haveSupplies < suppliesWant) ThreatFactionStock.noteShort(fid, Commodities.SUPPLIES, suppliesWant - haveSupplies);
			// the convoys fill the base for it, as they fill a staged hammer's
			ThreatConvoys.stageForPlay(base, "grand:" + fid, fid, target,
					new float[] { marines, arms, fuelWant, suppliesWant });
			ThreatIncConfig.logQuiet("grand:" + fid, "Grand fleet " + fid + ": musters at " + base.getName() + " for "
					+ target.getName() + " (" + (int) size + " FP, " + (int) best + " ly" + chainNames(chain) + ") - " + g.why);
			return;
		}
		launch(g, faction, base, target, targets, sizes, fp, passage, ordnance, suppliesWant, cruise, random);
	}

	/**
	 * The next {@code n} known Threat systems a grand fleet of {@code size} would hop to from {@code from}: each the
	 * nearest from the one before that it outweighs and was not called off over lately (the muster's bank prices them).
	 */
	protected static List<StarSystemAPI> chainFrom(GrandFleet g, String observer, StarSystemAPI from, float size,
			Map<StarSystemAPI, List<MarketAPI>> hives, int n, List<StarSystemAPI> skip) {
		List<StarSystemAPI> out = new ArrayList<StarSystemAPI>();
		StarSystemAPI at = from;
		while (out.size() < n) {
			StarSystemAPI next = null;
			float best = Float.MAX_VALUE;
			for (StarSystemAPI s : hives.keySet()) {
				if (s == from || out.contains(s) || (skip != null && skip.contains(s))) continue;
				float d = ly(at, s);
				if (d >= best || onCooldown(g, s) || swarmsSeen(observer, s) > size) continue;
				best = d;
				next = s;
			}
			if (next == null) break;
			out.add(next);
			at = next;
		}
		return out;
	}

	/**
	 * The chain the muster banks for. The worlds already in it stay while they stand and were not called off over
	 * ({@link #onCooldown}) - a sighting of swarms above the fleet's size is weighed at the hop (legDone), not
	 * here: run gf5, a day's sighting at Yma dropped it from the bank the day the Hegemony sailed, and three
	 * cruises running came up a few thousand fuel short of it (the user, 2026-10-27: hold the chain) - and
	 * {@link #chainFrom} fills the rest from the last of them.
	 */
	protected static List<StarSystemAPI> heldChain(GrandFleet g, String observer, StarSystemAPI target, float size,
			Map<StarSystemAPI, List<MarketAPI>> hives, int n) {
		List<StarSystemAPI> out = new ArrayList<StarSystemAPI>();
		if (g.chainIds != null) {
			for (String id : g.chainIds) {
				if (out.size() >= n) break;
				StarSystemAPI s = Global.getSector().getStarSystem(id);
				if (s == null || s == target || out.contains(s) || !hives.containsKey(s) || onCooldown(g, s)) continue;
				out.add(s);
			}
		}
		if (out.size() < n) {
			List<StarSystemAPI> skip = new ArrayList<StarSystemAPI>(out);
			skip.add(target);
			StarSystemAPI from = out.isEmpty() ? target : out.get(out.size() - 1);
			out.addAll(chainFrom(g, observer, from, size, hives, n - out.size(), skip));
		}
		return out;
	}

	/** ", then X, Y" for the log, or nothing. */
	protected static String chainNames(List<StarSystemAPI> chain) {
		if (chain == null || chain.isEmpty()) return "";
		StringBuilder b = new StringBuilder(", then ");
		for (int i = 0; i < chain.size(); i++) {
			if (i > 0) b.append(", ");
			b.append(chain.get(i).getName());
		}
		return b.toString();
	}

	/** The first leg sails: every marine and armament pooled, the passage and the cruise's ordnance, the cruise's hold. */
	protected static void launch(GrandFleet g, FactionAPI faction, MarketAPI base, StarSystemAPI target,
			List<MarketAPI> targets, List<Integer> sizes, float fp, float passage, float ordnance, float suppliesWant,
			float cruise, Random random) {
		String fid = faction.getId();
		GenericRaidParams params = params(faction, base, target, targets, sizes, cruise, random);
		List<MarketAPI> donors = IncursionManager.siegeDonors(base, faction, target);
		List<MarketAPI> pool = ThreatIncConfig.siegePoolProvisions() ? donors : new ArrayList<MarketAPI>();
		List<MarketAPI> marinePool = ThreatIncConfig.siegePoolMarines() ? donors : new ArrayList<MarketAPI>();
		float marines = IncursionManager.siegeDraw(base, marinePool, Commodities.MARINES,
				IncursionManager.siegePooled(base, marinePool, Commodities.MARINES), "marines");
		float arms = IncursionManager.siegeDraw(base, marinePool, Commodities.HAND_WEAPONS,
				IncursionManager.siegePooled(base, marinePool, Commodities.HAND_WEAPONS), "armaments");
		float fuel = IncursionManager.siegeDraw(base, pool, Commodities.FUEL, passage + ordnance, "fuel");
		float supplies = IncursionManager.siegeDraw(base, pool, Commodities.SUPPLIES, suppliesWant, "supplies");
		ThreatConvoys.clearPlayStaging("grand:" + fid);
		ThreatConvoys.clearCommitment(base);
		ThreatPurgeFGI purge = new ThreatPurgeFGI(params, false);
		purge.setCargoAllotment(Math.round(marines), arms);
		float paidPassage = Math.min(fuel, passage);
		float perPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		int points = 0;
		for (Integer s : sizes) points += s;
		float deposit = Math.min(supplies, points * perPoint);
		purge.setProvisions(paidPassage, deposit);
		purge.setHold(supplies - deposit);
		purge.setOrdnance(fuel - paidPassage, 0f);
		if (perPoint > 0f && deposit > 0f) {
			purge.setLedger(base.getId(), deposit / perPoint * IncursionManager.FP_PER_RESPONSE_DIFFICULTY);
		}
		purge.setGrandFleet(1);
		Global.getSector().getIntelManager().addIntel(purge);
		IncursionManager.getPurgeList().add(purge);
		g.purge = purge;
		g.leg = 1;
		g.destroyed = 0;
		g.sailed = Global.getSector().getClock().getTimestamp();
		g.dailySupplies = Math.max(0f, supplies - deposit) / cruise;
		g.dailyFuel = ThreatGroundFronts.hiveRazePerDay(fp) * ThreatGroundFronts.fuelPerStratum();
		g.why = "";
		ThreatNotice.titled("Grand Fleet Sails").icon(faction)
				.line("%s grand fleet of %s FP sails from %s for %s.", ThreatNotice.faction(faction),
						ThreatNotice.hl(Misc.getWithDGS(Math.round(fp))), ThreatNotice.market(base),
						ThreatNotice.hl(target.getNameWithLowercaseType()))
				.line("%s marines, %s fuel, %s supplies aboard.", ThreatNotice.hl(Misc.getWithDGS(Math.round(marines))),
						ThreatNotice.hl(Misc.getWithDGS(Math.round(fuel))), ThreatNotice.hl(Misc.getWithDGS(Math.round(supplies))))
				.send();
		ThreatIncConfig.log("Grand fleet " + fid + " sails from " + base.getName() + " for " + target.getName() + ": "
				+ (int) fp + " FP in " + sizes.size() + " fleets, " + (int) marines + " marines, " + (int) arms
				+ " armaments, " + (int) fuel + " fuel (passage " + (int) paidPassage + ", razing " + (int) ordnance + "), " + (int) supplies
				+ " supplies (hold " + (int) (supplies - deposit) + "); " + targets.size() + " worlds");
	}

	/** The leg's expedition params: a purge of every hive in the system, holding its orbit the whole cruise. */
	protected static GenericRaidParams params(FactionAPI faction, MarketAPI base, StarSystemAPI system,
			List<MarketAPI> targets, List<Integer> sizes, float cruise, Random random) {
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);
		params.factionId = faction.getId();
		params.source = base;
		params.prepDays = 3f + 4f * random.nextFloat();
		params.payloadDays = cruise;
		params.raidParams.maxDurationIfSpawnedFleetsConcurrent = cruise;
		params.raidParams.maxDurationIfSpawnedFleetsPerSequentialStage = cruise;
		params.raidParams.where = system;
		params.raidParams.type = FGRaidType.SEQUENTIAL;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.addAll(targets);
		params.raidParams.allowNonHostileTargets = true;
		params.raidParams.raidApproachText = "sailing to destroy";
		params.raidParams.raidActionText = "destroying";
		params.noun = "grand fleet";
		params.forcesNoun = faction.getDisplayName() + " grand fleet";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;
		params.makeFleetsHostile = false;
		params.fleetSizes.addAll(sizes);
		params.raidParams.raidsPerColony = IncursionManager.expeditionPasses(sizes.size());
		return params;
	}

	/**
	 * A leg is done with its system - its raid finished, or ({@code forced}) it can do nothing more there.
	 * With the fuel and supplies for the passage to the next nearest known Threat world, the passage home
	 * from there and the reserve, it hops; otherwise it turns home (forced: at once).
	 */
	public static void legDone(ThreatPurgeFGI purge, boolean forced) {
		if (purge == null || !purge.isGrandFleet() || purge.hopped()) return;
		FactionAPI faction = purge.getFaction();
		if (faction == null) return;
		String fid = faction.getId();
		GrandFleet g = of(fid);
		StarSystemAPI here = purge.getParams() != null && purge.getParams().raidParams != null
				? purge.getParams().raidParams.where : null;
		MarketAPI base = purge.getParams() != null ? purge.getParams().source : null;
		float fp = purge.fightingFP();
		// the worlds this leg destroyed
		if (purge.getParams() != null && purge.getParams().raidParams != null) {
			for (MarketAPI t : purge.getParams().raidParams.allowedTargets) {
				// only the worlds this leg razed (gf4: a leg arriving to a system another faction had emptied took the credit)
				if (t != null && (!t.isInEconomy() || !ThreatGroundFronts.isHiveTarget(t)) && purge.razed(t.getId())) g.destroyed++;
			}
		}
		if (here == null || base == null || base.getStarSystem() == null || fp <= 0f) {
			ThreatIncConfig.log("Grand fleet " + fid + " leg " + g.leg + " done" + (here != null ? " at " + here.getName() : "")
					+ ": nothing left to fight with (" + (int) fp + " FP), " + g.destroyed + " worlds destroyed");
			if (forced) home(purge, g, "nothing left to fight with");
			return;
		}
		String observer = ThreatIntel.observerOf(faction);
		Map<StarSystemAPI, List<MarketAPI>> hives = knownHives(observer);
		int points = 0;
		for (Integer s : sizesFor(fp)) points += s;
		float rate = ThreatIncConfig.expeditionFuelPerPointLY();
		float fuelLeft = purge.ordnanceLeft();
		float suppliesLeft = purge.hold();
		float reserveDays = Math.max(0f, ThreatIncConfig.grandFleetReserveDays());
		StarSystemAPI next = null;
		float best = Float.MAX_VALUE;
		String refused = null;
		// the banked chain first (the user, 2026-10-27): the world the muster saved the fuel for, while it still
		// stands, is outweighed and the fuel and supplies aboard pay it; gone, called off over or outweighing the
		// fleet it is dropped from the chain, unaffordable it waits there and the nearest affordable is tried
		if (g.chainIds != null) {
			for (java.util.Iterator<String> it = g.chainIds.iterator(); it.hasNext();) {
				StarSystemAPI s = Global.getSector().getStarSystem(it.next());
				if (s == null || s == here || !hives.containsKey(s) || onCooldown(g, s)) {
					it.remove();
					continue;
				}
				float swarms = swarmsSeen(observer, s);
				if (swarms > fp) {
					refused = Misc.getWithDGS(Math.round(swarms)) + " FP of swarms seen at " + s.getName() + " against " + (int) fp;
					it.remove();
					continue;
				}
				float[] need = hopNeed(g, here, s, base, points, rate, hives.get(s), reserveDays);
				if (fuelLeft < need[0]) {
					refused = "fuel " + (int) fuelLeft + " of " + (int) need[0] + " for " + s.getName() + " (banked)";
					break;
				}
				if (suppliesLeft < need[1]) {
					refused = "supplies " + (int) suppliesLeft + " of " + (int) need[1] + " for " + s.getName() + " (banked)";
					break;
				}
				next = s;
				it.remove();
				ThreatIncConfig.log("Grand fleet " + fid + " leg " + g.leg + " done at " + here.getName() + ": the banked "
						+ s.getName() + " next");
				break;
			}
		}
		if (next == null) {
		for (StarSystemAPI s : hives.keySet()) {
			if (s == here) continue;
			float d = ly(here, s);
			if (d >= best) continue;
			if (onCooldown(g, s)) continue;
			float swarms = swarmsSeen(observer, s);
			if (swarms > fp) {
				refused = Misc.getWithDGS(Math.round(swarms)) + " FP of swarms seen at " + s.getName() + " against " + (int) fp;
				continue;
			}
			float[] need = hopNeed(g, here, s, base, points, rate, hives.get(s), reserveDays);
			if (fuelLeft < need[0]) {
				refused = "fuel " + (int) fuelLeft + " of " + (int) need[0] + " for " + s.getName();
				continue;
			}
			if (suppliesLeft < need[1]) {
				refused = "supplies " + (int) suppliesLeft + " of " + (int) need[1] + " for " + s.getName();
				continue;
			}
			best = d;
			next = s;
		}
		}
		if (next == null) {
			ThreatIncConfig.log("Grand fleet " + fid + " leg " + g.leg + " done at " + here.getName() + ": turns home"
					+ (refused != null ? " - " + refused : " - no other known Threat world"));
			if (forced) home(purge, g, refused != null ? refused : "no other known Threat world");
			return;
		}
		// the passage priced from the systems, not the nearest-search's best: a banked hop leaves best unset (gf4: passage 2147483647, 0 fuel aboard)
		hop(purge, g, faction, base, here, next, hives.get(next), fp, points * ly(here, next) * rate);
	}

	/** What a hop from {@code here} to {@code s} needs aboard: {fuel for the passage there, that system's razing and the passage home; supplies for those days and the reserve}. */
	protected static float[] hopNeed(GrandFleet g, StarSystemAPI here, StarSystemAPI s, MarketAPI base, int points,
			float rate, List<MarketAPI> targets, float reserveDays) {
		float d = ly(here, s);
		float home = ly(s, base.getStarSystem());
		float fuel = points * d * rate + points * home * rate + razeFuel(targets);
		float supplies = (ThreatReach.days(d) + ThreatReach.days(home) + reserveDays) * g.dailySupplies;
		return new float[] { fuel, supplies };
	}

	/** The next leg: a new expedition from the system just fought in, everything aboard handed over. */
	protected static void hop(ThreatPurgeFGI old, GrandFleet g, FactionAPI faction, MarketAPI base, StarSystemAPI here,
			StarSystemAPI next, List<MarketAPI> targets, float fp, float passage) {
		String fid = faction.getId();
		Random random = new Random(Global.getSector().getClock().getTimestamp() ^ fid.hashCode());
		List<Integer> sizes = sizesFor(fp);
		float cruise = Math.max(1f, ThreatIncConfig.grandFleetCruiseDays());
		GenericRaidParams params = params(faction, base, next, targets, sizes, cruise, random);
		ThreatPurgeFGI.hopFrom = here.getCenter();
		ThreatPurgeFGI nu;
		try {
			nu = new ThreatPurgeFGI(params, false);
		} finally {
			ThreatPurgeFGI.hopFrom = null;
		}
		nu.setCargoAllotment(Math.round(old.getMarinesAllotted()), old.getArmamentsAllotted());
		nu.setProvisions(0f, 0f);
		nu.setHold(old.hold());
		nu.setOrdnance(Math.max(0f, old.ordnanceLeft() - passage), 0f);
		nu.setGrandFleet(g.leg + 1);
		nu.adoptFleetsFrom(old);
		old.markHopped();
		// the old leg is ended without vanilla's abort: its route would stand in the manager until it expired
		if (old.getRoute() != null) {
			com.fs.starfarer.api.impl.campaign.fleets.RouteManager.getInstance().removeRoute(old.getRoute());
		}
		Global.getSector().getIntelManager().addIntel(nu);
		IncursionManager.getPurgeList().add(nu);
		g.purge = nu;
		g.leg++;
		g.targetSystemId = next.getId();
		old.endImmediately();
		ThreatIncConfig.log("Grand fleet " + fid + " leg " + g.leg + ": " + (int) fp + " FP sails on from " + here.getName()
				+ " to " + next.getName() + " (" + (int) ly(here, next) + " ly, passage " + (int) passage + " fuel); "
				+ (int) nu.getMarinesAllotted() + " marines, " + (int) nu.ordnanceLeft() + " fuel, " + (int) nu.hold()
				+ " supplies aboard; " + g.destroyed + " worlds destroyed so far");
	}

	/** The leg turns home at once (ThreatPurgeFGI.callOffShort's shape), saying why. */
	protected static void home(ThreatPurgeFGI purge, GrandFleet g, String why) {
		FactionAPI faction = purge.getFaction();
		if (faction == null || purge.isEnding() || purge.isEnded() || purge.isAborted()) return;
		purge.setFailedButNotDefeated(true);
		purge.abort();
		ThreatIncConfig.log("Grand fleet " + faction.getId() + " leg " + g.leg + " turns home: " + why);
	}

	/** The grand fleet is home or gone (ThreatPurgeFGI.notifyEnding, not a hop). */
	public static void ended(ThreatPurgeFGI purge, float keep) {
		if (purge == null || !purge.isGrandFleet()) return;
		FactionAPI faction = purge.getFaction();
		if (faction == null) return;
		GrandFleet g = of(faction.getId());
		if (g.purge == purge) g.purge = null;
		MarketAPI base = purge.getParams() != null ? purge.getParams().source : null;
		// destroyed outright, or vanilla's abort line crossed (its route damage; the notice is the only word of it)
		boolean lost = keep < purge.getGroupAbortsMissionFPFraction();
		int strength = Math.round(Math.max(0f, Math.min(1f, keep)) * 100f);
		ThreatNotice n = ThreatNotice.titled(lost ? "Grand Fleet Lost" : "Grand Fleet Returns").icon(faction);
		if (lost) {
			n.line("%s grand fleet was destroyed after %s legs.", ThreatNotice.faction(faction), ThreatNotice.hl(g.leg));
		} else {
			n.line("%s grand fleet returns to %s after %s legs.", ThreatNotice.faction(faction),
					base != null ? ThreatNotice.market(base) : ThreatNotice.hl("home"), ThreatNotice.hl(g.leg));
		}
		n.line("%s Threat worlds destroyed, %s of its strength left.", ThreatNotice.hl(g.destroyed),
				ThreatNotice.hl(strength + "%")).send();
		ThreatIncConfig.log("Grand fleet " + faction.getId() + (lost ? " lost" : " home") + " after " + g.leg + " legs, "
				+ g.destroyed + " worlds destroyed, strength " + strength + "%");
	}

	/** The faction tab's one line: {text, highlights...}, or null with nothing to say. */
	public static String[] boardLine(String factionId) {
		if (factionId == null) return null;
		GrandFleet g = all().get(factionId);
		if (g == null) return null;
		if (out(g)) {
			StarSystemAPI s = g.purge.getParams() != null && g.purge.getParams().raidParams != null
					? g.purge.getParams().raidParams.where : null;
			String fp = Misc.getWithDGS(Math.round(g.purge.fightingFP()));
			String where = s != null ? s.getNameWithLowercaseType() : "at sea";
			return new String[] { "Grand fleet: %s FP at the " + where + ", leg %s, %s worlds destroyed, %s marines aboard",
					fp, String.valueOf(g.leg), String.valueOf(g.destroyed), Misc.getWithDGS(Math.round(g.purge.getMarinesAllotted())) };
		}
		MarketAPI base = g.baseId != null ? Global.getSector().getEconomy().getMarket(g.baseId) : null;
		String free = Misc.getWithDGS(Math.round(ThreatHulls.freeFP(factionId)));
		String size = Misc.getWithDGS(Math.round(g.sizeFP));
		if (base == null) {
			return new String[] { "Grand fleet: %s of %s FP mustered - " + g.why, free, size };
		}
		return new String[] { "Grand fleet: %s of %s FP mustered at " + base.getName() + " - " + g.why, free, size };
	}
}
