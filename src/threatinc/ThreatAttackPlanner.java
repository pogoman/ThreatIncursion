package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE ATTACK PLANNER (2026-10-01, user's call; docs/attack-planner.md section
 * 2). Each mobilised NPC faction plans its blows from its REPORTS of the swarm
 * (ThreatIntel) - never the live garrisons - every planIntervalDays and on
 * news: a report that moved a world's swarms by half, a prong's arrival, a
 * raid's end, a hunt that thinned a system.
 *
 * <p>SIEGES (the monthly pass's job until now, IncursionManager
 * .tryPurgeBombardments): the easiest worlds the faction can take and pay for
 * (siegeTargets, siegeAffordable), its pressed system first. A siege lands as
 * planned only if the picture it sailed on still holds when it arrives: its
 * TRUST at arrival, 0.5 ^ ((age + days to arrive) / intelHalfLifeDays). The
 * planner SPREADS - adds sieges at other worlds - until the chance that at
 * least one of those in flight lands, 1 - the product of (1 - trust), reaches
 * planConfidence. Then it stops; enlarging a prong is not built (v1). Every
 * base in reach is tried, cheapest first, until one sails, and the launch's own
 * gates postpone what the pools cannot pay and post the swarm bounty; a system
 * held back once the chance is met still gets its bounty when its swarms
 * outweigh any siege the faction can field (IncursionManager.orbitBounty).
 *
 * <p>RAIDS: a task force that holds one hive's orbit and bombs it, with no
 * troops and no front (ThreatFleetOrders.dispatchRaid). Sized by the target,
 * never a constant: the least fleet whose day buys a day down past the guns
 * (ThreatGroundFronts.dailyGain), and more than twice the swarms the faction
 * last saw over the world (the contest rule). Ranked by cost - passage,
 * ordnance and supplies at base prices - per day down bought times trust,
 * and funded best first until the pools say no. A raid at a world of a
 * system the faction sieges sails to arrive no earlier than the siege.
 *
 * <p>RECON: a system with no report, or one older than the half-life, that
 * the faction could act on gets a scouting party (ThreatScouts.recon), and a
 * siege or raid there waits for it while it is on its way.
 *
 * <p>The stance gates sieges and raids alike (ThreatFactionStance.siegeAllowed:
 * consolidating, only a system facing the faction) - priority, never a cap.
 */
public final class ThreatAttackPlanner {

	private ThreatAttackPlanner() {
	}

	public static final String KEY_PLANS = "threatinc_attackPlans";

	public static final String SIEGE = "siege";
	public static final String RAID = "raid";

	/** Days a raid's term runs past its passage both ways and its planned stay: the time to fight for the orbit and settle. */
	public static final float RAID_SLACK_DAYS = 10f;

	/** Days a raid held back to arrive with a siege may wait past its day for the pools before it is dropped. */
	public static final float PENDING_GRACE_DAYS = 5f;

	/** One faction's plan: when it last planned, the news waiting, and its prongs. */
	public static class Plan {
		public String factionId;
		/** The calendar's days are negative (today() is about -642,000), so "never" is -Float.MAX_VALUE, not 0. */
		public float lastPlanned = -Float.MAX_VALUE;
		public boolean news;
		public String newsWhat;
		public List<Prong> prongs = new ArrayList<Prong>();
	}

	/** One blow of a plan: a siege in flight, or a raid waiting to sail beside one. */
	public static class Prong {
		public String kind;
		public String systemId;
		/** The world: a siege's first target, a raid's world. */
		public String targetId;
		public String baseId;
		/** The trust of the picture it was planned on, at arrival. */
		public float trust;
		public float plannedDay;
		/** A raid held back: the day it sails. */
		public float departDay;
		public float arriveDay;
		public float fp;
		/** A raid's term. */
		public float days;
		public String fallbackId;
		public boolean launched;
	}

	static Map<String, Plan> plans() {
		return ThreatIncData.map(KEY_PLANS);
	}

	protected static Plan planOf(String factionId) {
		Plan p = plans().get(factionId);
		if (p == null) {
			p = new Plan();
			p.factionId = factionId;
			plans().put(factionId, p);
		}
		if (p.prongs == null) p.prongs = new ArrayList<Prong>();
		return p;
	}

	protected static float today() {
		return ThreatPosture.today();
	}

	/** The planner runs: on, and the war council (which replaces it) off. */
	public static boolean active() {
		return ThreatIncConfig.attackPlanner() && !ThreatIncConfig.warCouncil();
	}

	/** Whether the faction plans: a mobilised NPC faction. */
	protected static boolean planner(String factionId) {
		if (factionId == null || Factions.PLAYER.equals(factionId) || Factions.THREAT.equals(factionId)) return false;
		if (ThreatWarState.excluded(factionId)) return false;
		return ThreatWarState.isAtWar(factionId);
	}

	/** Something the faction learned may change its plan: it re-plans at its next daily check. */
	public static void news(String factionId, String what) {
		if (!active() || !planner(factionId)) return;
		Plan p = planOf(factionId);
		if (!p.news) p.newsWhat = what;
		p.news = true;
	}

	/** News for every planning faction. */
	public static void newsForAll(String what) {
		if (!active()) return;
		for (String id : ThreatWarState.warFactionIds()) news(id, what);
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	/** The calendar day the planner last checked; not saved - a reload checks once more, which only re-plans what is due. */
	private static long checkedDay = Long.MIN_VALUE;

	/** From the war poll: once a calendar day, every faction resolves its prongs, sails the raids whose day has come, and plans when due. */
	public static void poll(Random random) {
		if (!active()) return;
		long day = ThreatReach.today();
		if (day == checkedDay) return;
		checkedDay = day;
		float today = today();
		ThreatFactionStance.refresh();
		for (String fid : new ArrayList<String>(ThreatWarState.warFactionIds())) {
			if (!planner(fid)) continue;
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null) continue;
			Plan p = planOf(fid);
			resolve(p, today);
			sailPending(p, faction, today);
			// a last plan "after" today is the old -1000 sentinel of a save from the first build: due
			if (!p.news && today >= p.lastPlanned && today - p.lastPlanned < Math.max(1f, ThreatIncConfig.planIntervalDays())) continue;
			plan(p, faction, today, random);
		}
	}

	/** Prongs that are done drop out: a siege arrived or ended (news - its verdict is in), a held raid whose day passed unpaid. */
	protected static void resolve(Plan p, float today) {
		for (Prong pr : new ArrayList<Prong>(p.prongs)) {
			if (SIEGE.equals(pr.kind)) {
				boolean live = siegeLive(p.factionId, pr.targetId);
				if (live && today < pr.arriveDay) continue;
				p.prongs.remove(pr);
				ThreatIncConfig.log("Plan " + p.factionId + ": siege at " + name(pr.targetId)
						+ (live ? " has arrived" : " has ended") + " (planned on trust " + two(pr.trust) + ")");
				p.news = true;
				if (p.newsWhat == null) p.newsWhat = "a siege prong resolved";
			} else if (!pr.launched && today > pr.departDay + PENDING_GRACE_DAYS) {
				p.prongs.remove(pr);
				ThreatIncConfig.log("Plan " + p.factionId + ": raid on " + name(pr.targetId)
						+ " dropped - not paid by its sailing day");
			}
		}
	}

	/** Whether a live siege of the faction is taking the world. */
	protected static boolean siegeLive(String factionId, String worldId) {
		for (Object curr : IncursionManager.getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (!factionId.equals(purge.getFaction().getId())) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			for (MarketAPI m : purge.getParams().raidParams.allowedTargets) {
				// a daily siege done with the world has its verdict there (ThreatPurgeFGI.takes)
				if (m != null && m.getId().equals(worldId) && ThreatPurgeFGI.takes(purge, m)) return true;
			}
		}
		return false;
	}

	/** The chance at least one of the faction's sieges in flight lands: 1 - the product of (1 - trust at arrival). */
	public static float chance(Plan p) {
		float miss = 1f;
		for (Prong pr : p.prongs) {
			if (SIEGE.equals(pr.kind)) miss *= 1f - Math.max(0f, Math.min(1f, pr.trust));
		}
		return 1f - miss;
	}

	/** Held raids whose sailing day has come sail now, if the pools still pay and nothing took their world since the plan. */
	protected static void sailPending(Plan p, FactionAPI faction, float today) {
		for (Prong pr : new ArrayList<Prong>(p.prongs)) {
			if (!RAID.equals(pr.kind) || pr.launched || today < pr.departDay) continue;
			MarketAPI world = market(pr.targetId);
			MarketAPI base = market(pr.baseId);
			String gone = null;
			if (world == null || !Factions.THREAT.equals(world.getFactionId())) gone = "its world is no longer the swarm's";
			else if (base == null || !faction.getId().equals(base.getFactionId())) gone = "its base is lost";
			else if (!ThreatIncConfig.supportEnabled()) gone = "raids are off";
			else if (ThreatFleetOrders.hasRaid(faction.getId(), world.getId())) gone = "a raid is there already";
			// a siege booked it after the plan (a held raid is no order, so siegeTargets cannot see it)
			else if (IncursionManager.bookedWorlds().contains(world)) gone = "a siege took the world";
			else {
				ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(world.getId());
				if (front != null && faction.getId().equals(ThreatGroundFronts.ownerOf(front))) {
					gone = "its own front holds the orbit";
				}
			}
			if (gone != null) {
				p.prongs.remove(pr);
				ThreatIncConfig.log("Plan " + faction.getId() + ": raid on " + name(pr.targetId) + " dropped - " + gone);
				continue;
			}
			// relief before offensives (user, 2026-09-27): it waits, and drops when its grace passes (resolve)
			if (ThreatFleetOrders.reliefOwed(faction)) continue;
			if (ThreatFleetOrders.sortieReachFP(base, world.getLocationInHyperspace()) < pr.fp) continue;
			if (ThreatFleetOrders.dispatchRaid(faction, world, base, pr.fp, pr.days, pr.fallbackId) == null) continue;
			p.prongs.remove(pr);
			ThreatIncConfig.log("Plan " + faction.getId() + ": raid on " + world.getName() + " sails from "
					+ base.getName() + " on its day, " + (int) pr.fp + " FP");
		}
	}

	// ------------------------------------------------------------------
	// planning
	// ------------------------------------------------------------------

	/** A siege the faction could sail now: its lead base's (ranked by it), and every base in reach to try in turn. */
	protected static class SiegeOption {
		MarketAPI base;
		StarSystemAPI system;
		List<MarketAPI> targets;
		float trust;
		float travel;
		float age;
		boolean pressed;
		float landing;
		float orbit;
		/** Whether the lead base can take the orbit and pay (siegeAffordable): cheapestFirst puts any that can first. */
		boolean affordable;
		/** The bases with worlds ready to take, cheapest first (cheapestFirst), and each one's targets. */
		List<MarketAPI> bases = new ArrayList<MarketAPI>();
		List<List<MarketAPI>> targetsBy = new ArrayList<List<MarketAPI>>();
	}

	/** A raid the faction could sail. */
	protected static class RaidOption {
		MarketAPI world;
		MarketAPI base;
		float fp;
		float reported;
		float trust;
		float travel;
		float daysDown;
		float stay;
		float fuel;
		float supplies;
		float score;

		/** Its term: there and back, its planned stay, and the slack to fight for the orbit and settle. */
		float days() {
			return 2f * travel + stay + RAID_SLACK_DAYS;
		}
	}

	/** One plan for the faction: recon where the picture is weak, sieges until the chance is met, raids best first until the pools say no. */
	protected static void plan(Plan p, FactionAPI faction, float today, Random random) {
		String fid = faction.getId();
		String why = p.news ? (p.newsWhat != null ? p.newsWhat : "news") : "the week";
		p.lastPlanned = today;
		p.news = false;
		p.newsWhat = null;
		float half = Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
		List<StarSystemAPI> systems = knownSystems();
		StringBuilder out = new StringBuilder();

		// recon first: a siege or raid on a weak picture waits for the scout
		List<String> scouted = new ArrayList<String>();
		for (StarSystemAPI s : systems) {
			if (ThreatFleetOrders.pickBase(faction, s.getLocation()) == null) continue;
			if (ThreatIntel.age(fid, s.getId()) < half) continue;
			if (prongBound(p, s.getId()) || ThreatScouts.reconInFlight(fid, s.getId())) continue;
			if (ThreatScouts.recon(fid, s) != null) scouted.add(s.getName());
		}

		// sieges, easiest first, until the chance is met
		float before = chance(p);
		float chance = before;
		int held = 0, postponed = 0, outweighed = 0, waiting = 0;
		List<String> sailed = new ArrayList<String>();
		if (ThreatIncConfig.responsePurgeEnabled() && !ThreatFleetOrders.reliefOwed(faction)) {
			List<SiegeOption> options = new ArrayList<SiegeOption>();
			for (StarSystemAPI s : systems) {
				if (!ThreatFactionStance.siegeAllowed(faction, s)) continue;
				if (ThreatIntel.report(fid, s.getId()) == null) continue;
				float age = ThreatIntel.age(fid, s.getId());
				if (age >= half && ThreatScouts.reconInFlight(fid, s.getId())) {
					waiting++;
					continue;
				}
				SiegeOption o = siegeOption(faction, s, age);
				if (o != null) options.add(o);
			}
			Collections.sort(options, new Comparator<SiegeOption>() {
				public int compare(SiegeOption a, SiegeOption b) {
					if (a.pressed != b.pressed) return a.pressed ? -1 : 1;
					int c = Float.compare(a.landing, b.landing);
					return c != 0 ? c : Float.compare(a.orbit, b.orbit);
				}
			});
			for (SiegeOption o : options) {
				if (chance >= ThreatIncConfig.planConfidence()) {
					held++;
					// swarms no siege of the faction can take still draw the bounty, as the monthly pass posted it
					if (!o.affordable && IncursionManager.orbitBounty(o.base, faction, o.system, o.targets)) outweighed++;
					continue;
				}
				// every base in reach, cheapest first, until one sails (the monthly pass's rule): the
				// launch's own gates postpone what the pools cannot pay, post the bounty, and sail a
				// base short of marines that lands after softening (landsAfterSoftening)
				ThreatPurgeFGI purge = null;
				int i = 0;
				while (purge == null && i < o.bases.size()) {
					purge = IncursionManager.launchPlanned(o.bases.get(i), faction, o.system, o.targetsBy.get(i), random);
					i++;
				}
				if (purge == null) {
					postponed++;
					continue;
				}
				MarketAPI base = o.bases.get(i - 1);
				List<MarketAPI> targets = o.targetsBy.get(i - 1);
				float travel = base == o.base ? o.travel : IncursionManager.razeArrivalDays(base, o.system);
				float trust = base == o.base ? o.trust : ThreatIntel.trust(fid, o.system.getId(), travel);
				Prong pr = new Prong();
				pr.kind = SIEGE;
				pr.systemId = o.system.getId();
				pr.targetId = targets.get(0).getId();
				pr.baseId = base.getId();
				pr.trust = trust;
				pr.plannedDay = today;
				pr.departDay = today;
				pr.arriveDay = today + travel;
				pr.launched = true;
				p.prongs.add(pr);
				chance = chance(p);
				sailed.add(o.system.getName() + " (" + names(targets) + ") from " + base.getName() + ", trust "
						+ two(trust) + " (report " + (int) o.age + " d old, " + (int) travel + " d to arrive), "
						+ (int) IncursionManager.siegeOrbitFaced(fid, targets) + " FP reported over the strongest, "
						+ (int) purge.abstractFull() + " FP sent");
			}
		}
		if (!sailed.isEmpty() || before > 0f || postponed > 0 || outweighed > 0) {
			out.append(" sieges ").append(sailed.isEmpty() ? "none new" : Misc.getAndJoined(sailed))
					.append(", chance ").append(two(before)).append(" -> ").append(two(chance));
			if (postponed > 0) out.append(", ").append(postponed).append(" postponed");
			if (held > 0) out.append(", ").append(held).append(" more held");
			if (outweighed > 0) out.append(" (").append(outweighed).append(" outweighed: bounty)");
		}
		if (waiting > 0) out.append("; ").append(waiting).append(" system(s) wait on recon");

		// raids, cheapest per day down first, until the pools say no; relief before offensives
		// (user, 2026-09-27) holds them as it holds sieges
		if (ThreatIncConfig.supportEnabled() && !ThreatFleetOrders.reliefOwed(faction)) {
			planRaids(p, faction, today, systems, half, out);
		}

		if (!scouted.isEmpty()) out.append("; recon ").append(Misc.getAndJoined(scouted));
		String body = out.length() > 0 ? out.toString() : " nothing to do";
		if (body.startsWith("; ")) body = " " + body.substring(2);
		// most plans on news change nothing (h50a: 71% repeated the last line): log a plan when it differs
		if (body.equals(LAST_LINE.get(fid))) {
			Integer n = REPEATS.get(fid);
			REPEATS.put(fid, n == null ? 1 : n + 1);
			return;
		}
		Integer repeats = REPEATS.remove(fid);
		LAST_LINE.put(fid, body);
		ThreatIncConfig.log("Plan " + fid + " (" + why + "):" + body
				+ (repeats != null ? " [after " + repeats + " unchanged plans]" : ""));
	}

	/** Each faction's last logged plan and the unchanged plans since, so a repeat is not logged; not saved. */
	private static final Map<String, String> LAST_LINE = new HashMap<String, String>();
	private static final Map<String, Integer> REPEATS = new HashMap<String, Integer>();

	/**
	 * The siege the faction could sail at the system: every base of its in reach
	 * with worlds ready to take, cheapest first (cheapestFirst: those that can
	 * take the orbit and pay first), led and ranked by the first. Null when no
	 * base has a world to take - one that cannot pay still counts, so the launch's
	 * gates post the bounty (or sail it after softening) as the monthly pass did.
	 */
	protected static SiegeOption siegeOption(FactionAPI faction, StarSystemAPI system, float age) {
		String fid = faction.getId();
		List<MarketAPI> bases = new ArrayList<MarketAPI>();
		for (MarketAPI b : IncursionManager.siegeBasesFor(system)) {
			if (fid.equals(b.getFactionId())) bases.add(b);
		}
		if (bases.isEmpty()) return null;
		SiegeOption o = null;
		for (MarketAPI b : IncursionManager.cheapestFirst(system, bases)) {
			List<MarketAPI> targets = IncursionManager.siegeTargets(b, faction, system);
			if (targets.isEmpty() || !IncursionManager.anySiegeReady(fid, targets)) continue;
			if (o == null) {
				o = new SiegeOption();
				o.base = b;
				o.system = system;
				o.targets = targets;
				o.affordable = IncursionManager.siegeAffordable(b, faction, system, targets);
				o.travel = IncursionManager.razeArrivalDays(b, system);
				o.trust = ThreatIntel.trust(fid, system.getId(), o.travel);
				o.age = age;
				o.pressed = system.getId().equals(ThreatFactionStance.target(fid));
				o.landing = IncursionManager.siegeRaidStrNeeded(targets);
				o.orbit = IncursionManager.siegeOrbitFaced(fid, targets);
			}
			o.bases.add(b);
			o.targetsBy.add(targets);
		}
		return o;
	}

	/** The raids of one plan: every world the faction could raid, ranked, each sailing now or held to arrive with a siege. */
	protected static void planRaids(Plan p, FactionAPI faction, float today, List<StarSystemAPI> systems,
			float half, StringBuilder out) {
		String fid = faction.getId();
		Set<MarketAPI> booked = IncursionManager.bookedWorlds();
		List<RaidOption> options = new ArrayList<RaidOption>();
		// the worlds this plan leaves unraided, paid or not: where a raid turned back may strike instead
		List<RaidOption> spare = new ArrayList<RaidOption>();
		int unpaid = 0;
		for (StarSystemAPI s : systems) {
			if (!ThreatFactionStance.siegeAllowed(faction, s)) continue;
			ThreatIntel.Report r = ThreatIntel.report(fid, s.getId());
			if (r == null) continue;
			if (ThreatIntel.age(fid, s.getId()) >= half && ThreatScouts.reconInFlight(fid, s.getId())) continue;
			for (MarketAPI w : ThreatIncData.getLiveColonyMarkets(s.getId())) {
				if (w.getPrimaryEntity() == null || booked.contains(w)) continue;
				// a world founded since the report has never been seen: unseen is not undefended
				if (!r.worlds.containsKey(w.getId())) continue;
				if (ThreatFleetOrders.hasRaid(fid, w.getId()) || pendingRaid(p, w.getId())) continue;
				// its own front's Support and Defend hold that orbit already
				ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(w.getId());
				if (front != null && fid.equals(ThreatGroundFronts.ownerOf(front))) continue;
				RaidOption o = raidOption(faction, w, r);
				if (o == null) continue;
				if (o.base == null) {
					unpaid++;
					spare.add(o);
					continue;
				}
				options.add(o);
			}
		}
		Collections.sort(options, new Comparator<RaidOption>() {
			public int compare(RaidOption a, RaidOption b) {
				return Float.compare(a.score, b.score);
			}
		});
		List<String> sailed = new ArrayList<String>();
		List<String> heldFor = new ArrayList<String>();
		List<ThreatFleetOrders.Order> leads = new ArrayList<ThreatFleetOrders.Order>();
		List<RaidOption> leadOptions = new ArrayList<RaidOption>();
		List<Prong> heldProngs = new ArrayList<Prong>();
		List<RaidOption> heldOptions = new ArrayList<RaidOption>();
		for (RaidOption o : options) {
			StarSystemAPI s = o.world.getStarSystem();
			// beside a siege: sail to arrive no earlier than it does
			float siegeArrives = siegeArrival(p, s.getId());
			if (siegeArrives > today + o.travel) {
				Prong pr = new Prong();
				pr.kind = RAID;
				pr.systemId = s.getId();
				pr.targetId = o.world.getId();
				pr.baseId = o.base.getId();
				pr.trust = o.trust;
				pr.plannedDay = today;
				pr.departDay = siegeArrives - o.travel;
				pr.arriveDay = siegeArrives;
				pr.fp = o.fp;
				pr.days = o.days();
				p.prongs.add(pr);
				heldProngs.add(pr);
				heldOptions.add(o);
				heldFor.add(o.world.getName() + " (sails in " + (int) (pr.departDay - today) + " d)");
				continue;
			}
			if (ThreatFleetOrders.sortieReachFP(o.base, o.world.getLocationInHyperspace()) < o.fp) {
				unpaid++;
				spare.add(o);
				continue;
			}
			ThreatFleetOrders.Order lead = ThreatFleetOrders.dispatchRaid(faction, o.world, o.base, o.fp, o.days(), null);
			if (lead == null) {
				unpaid++;
				spare.add(o);
				continue;
			}
			leads.add(lead);
			leadOptions.add(o);
			sailed.add(o.world.getName() + " " + (int) o.fp + " FP from " + o.base.getName() + " (" + (int) o.reported
					+ " reported, " + String.format("%.1f", o.daysDown) + " d down over " + (int) o.stay
					+ " d, trust " + two(o.trust) + ", " + (int) o.fuel + " fuel, " + (int) o.supplies + " supplies)");
		}
		// fallbacks last, from the worlds left unraided: a world a raid of this plan strikes is
		// never free to divert to (ThreatFleetOrders.raidedByAnother)
		for (int i = 0; i < leads.size(); i++) {
			ThreatFleetOrders.setRaidFallback(leads.get(i), fallbackFor(leadOptions.get(i), spare));
		}
		for (int i = 0; i < heldProngs.size(); i++) {
			heldProngs.get(i).fallbackId = fallbackFor(heldOptions.get(i), spare);
		}
		if (!sailed.isEmpty()) out.append("; raids ").append(Misc.getAndJoined(sailed));
		if (!heldFor.isEmpty()) out.append("; raids beside its sieges ").append(Misc.getAndJoined(heldFor));
		if (unpaid > 0) out.append("; ").append(unpaid).append(" raid(s) the pools do not pay");
	}

	/**
	 * A raid on the world: sized by its guns and twice the swarms the faction
	 * last saw over it, from its nearest base, priced and valued by the
	 * commander's stop (bombardPlan, a third lost). Null when no fleet's day
	 * there buys a day down; base null when no base can pay for it.
	 */
	protected static RaidOption raidOption(FactionAPI faction, MarketAPI world, ThreatIntel.Report r) {
		String fid = faction.getId();
		float guns = leastForGain(world);
		if (guns >= Float.MAX_VALUE) return null;
		float reported = r.worldFP(world.getId());
		float orbit = reported > 0f ? reported / Math.max(0.01f, ThreatIncConfig.orbitContestFraction()) + 1f : 0f;
		float fp = Math.max(Math.max(guns, orbit), Math.max(1f, ThreatIncConfig.guardFleetFP()));
		float floor = 1f - Math.max(0f, Math.min(1f, ThreatIncConfig.raidLossFraction()));
		float[] run = ThreatGroundFronts.bombardPlan(world, fp, ThreatIncConfig.siegeOrbitDays(), 0f, false, 0f, floor);
		if (run[4] < 1f) return null;
		RaidOption o = new RaidOption();
		o.world = world;
		o.fp = fp;
		o.reported = reported;
		o.daysDown = run[4];
		o.stay = run[0];
		MarketAPI base = ThreatFleetOrders.pickBase(faction, world.getLocationInHyperspace());
		if (base == null || ThreatFleetOrders.sortieReachFP(base, world.getLocationInHyperspace()) < fp) return o;
		o.base = base;
		float ly = base.getStarSystem() != null && world.getStarSystem() != null
				? Misc.getDistanceLY(base.getStarSystem().getLocation(), world.getStarSystem().getLocation()) : 0f;
		o.travel = ThreatReach.days(ly);
		o.trust = ThreatIntel.trust(fid, world.getStarSystem().getId(), o.travel);
		float[] wants = ThreatFleetOrders.sortieWants(base, fp, world.getLocationInHyperspace());
		o.fuel = wants[0] + ThreatGroundFronts.bombardFuelPerDay(fp) * run[0];
		o.supplies = wants[1];
		float cost = o.fuel * IncursionManager.basePrice(Commodities.FUEL)
				+ o.supplies * IncursionManager.basePrice(Commodities.SUPPLIES);
		o.score = cost / Math.max(0.01f, o.daysDown * Math.max(0.01f, o.trust));
		return o;
	}

	/** A raid's fallback among the worlds left unraided: the most days down in its own system, else anywhere; null when none. */
	protected static String fallbackFor(RaidOption o, List<RaidOption> spare) {
		RaidOption same = null, any = null;
		for (RaidOption q : spare) {
			if (q.world == o.world) continue;
			if (any == null || q.daysDown > any.daysDown) any = q;
			if (q.world.getStarSystem() == o.world.getStarSystem() && (same == null || q.daysDown > same.daysDown)) {
				same = q;
			}
		}
		RaidOption fb = same != null ? same : any;
		return fb != null ? fb.world.getId() : null;
	}

	/** leastForGain per world for the calendar day: it reads only the world, so every faction's plan shares it. */
	private static final Map<String, Float> GAIN_MEMO = new HashMap<String, Float>();
	private static long gainMemoDay = Long.MIN_VALUE;

	/** The least fleet points whose day of bombardment over the world buys a day down (dailyGain >= 1); Float.MAX_VALUE when none would. */
	protected static float leastForGain(MarketAPI world) {
		long day = ThreatReach.today();
		if (day != gainMemoDay) {
			GAIN_MEMO.clear();
			gainMemoDay = day;
		}
		Float memo = GAIN_MEMO.get(world.getId());
		if (memo != null) return memo;
		float least = leastForGainNow(world);
		GAIN_MEMO.put(world.getId(), least);
		return least;
	}

	protected static float leastForGainNow(MarketAPI world) {
		float hi = 1000000f;
		if (ThreatGroundFronts.dailyGain(world, hi) < 1f) return Float.MAX_VALUE;
		float lo = 1f;
		if (ThreatGroundFronts.dailyGain(world, lo) >= 1f) return lo;
		for (int i = 0; i < 40 && hi - lo > 1f; i++) {
			float mid = (lo + hi) * 0.5f;
			if (ThreatGroundFronts.dailyGain(world, mid) >= 1f) hi = mid;
			else lo = mid;
		}
		return (float) Math.ceil(hi);
	}

	/** The day the faction's latest siege in flight at the system arrives; -Float.MAX_VALUE none (the calendar's days are negative). */
	protected static float siegeArrival(Plan p, String systemId) {
		float day = -Float.MAX_VALUE;
		for (Prong pr : p.prongs) {
			if (SIEGE.equals(pr.kind) && systemId.equals(pr.systemId)) day = Math.max(day, pr.arriveDay);
		}
		return day;
	}

	protected static boolean pendingRaid(Plan p, String worldId) {
		for (Prong pr : p.prongs) {
			if (RAID.equals(pr.kind) && !pr.launched && worldId.equals(pr.targetId)) return true;
		}
		return false;
	}

	/** Whether a prong of the plan, or a raid of the faction, is bound for the system: its eyes will refresh the picture. */
	protected static boolean prongBound(Plan p, String systemId) {
		for (Prong pr : p.prongs) {
			if (systemId.equals(pr.systemId)) return true;
		}
		return ThreatFleetOrders.raidInSystem(p.factionId, systemId);
	}

	/** Every found Threat system with a live hive. */
	protected static List<StarSystemAPI> knownSystems() {
		List<StarSystemAPI> out = new ArrayList<StarSystemAPI>();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			if (ThreatIncData.getLiveColonyMarkets(systemId).isEmpty()) continue;
			if (!ThreatScouts.sectorKnows(systemId)) continue;
			StarSystemAPI system = ThreatScoutRoute.systemById(systemId);
			if (system != null) out.add(system);
		}
		return out;
	}

	protected static MarketAPI market(String id) {
		return id != null ? Global.getSector().getEconomy().getMarket(id) : null;
	}

	protected static String name(String marketId) {
		MarketAPI m = market(marketId);
		return m != null ? m.getName() : String.valueOf(marketId);
	}

	protected static String names(List<MarketAPI> worlds) {
		List<String> out = new ArrayList<String>();
		for (MarketAPI w : worlds) out.add(w.getName());
		return Misc.getAndJoined(out);
	}

	protected static String two(float x) {
		return String.format("%.2f", x);
	}

	/** Called on load: the not-saved daily check and memo go. */
	public static void forget() {
		checkedDay = Long.MIN_VALUE;
		GAIN_MEMO.clear();
		gainMemoDay = Long.MIN_VALUE;
		LAST_LINE.clear();
		REPEATS.clear();
	}

	/** A new campaign: every plan goes. */
	public static void reset() {
		Global.getSector().getPersistentData().remove(KEY_PLANS);
		forget();
	}
}
