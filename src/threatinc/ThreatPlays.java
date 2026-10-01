package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * The war council's plays (docs/war-council.md section 4): the operational
 * layer. A play has a goal and a target, a share of the means, and phases on
 * its own clock, checked daily; it does not re-plan on news. It sequences the
 * moves that exist - siege expeditions, raids, hunting forces, scouts - and
 * judges itself by the damage done: Nexus-days down, orbit-days held, worlds
 * landed on and taken, raids driven off. Its forces are shares of the means
 * (section 5): no gate that reads the swarm's fleet points applies to them
 * (IncursionManager.launchSiegeExpedition's playId, ThreatSoftening.sendPlay).
 *
 * <ul>
 * <li>HAMMER: prepare (stage, scout) -> muster (hunting forces held at the
 * bearing, a partner's too) -> strike (the siege sails, the hunts go in as it
 * nears) -> exploit (stay while orbits are held, landings come or the swarm
 * thins; else withdraw).</li>
 * <li>STARVE: bomb (squadrons rotate over the Nexuses, a saturation expedition
 * when the pools pay; checked every councilStarveCheckDays) -> hands over to a
 * hammer once a Nexus has been down councilInvadeNexusDays and the garrison is
 * not regrowing.</li>
 * <li>FEINT: a squadron at a small neighbour A while the strike base stages
 * against A -> watch A's reports rise -> strike B from close, bombers on B's
 * Nexus arriving with the siege -> exploit.</li>
 * <li>RECON: a scout and a probing raid; BOMBERS: one squadron of
 * opportunity; JOINT: a partner's force in another faction's hammer.</li>
 * </ul>
 *
 * <p>Relief owed holds every play's next phase (section 4.5). Saved: {@link Play}
 * in KEY; never rename the class or a field.
 */
public class ThreatPlays {

	public static final String KEY = "threatinc_plays";
	public static final String RECON = "RECON", HAMMER = "HAMMER", STARVE = "STARVE", FEINT = "FEINT",
			BOMBERS = "BOMBERS", JOINT = "JOINT";
	public static final String SCOUT = "scout", PREPARE = "prepare", MUSTER = "muster", STRIKE = "strike",
			EXPLOIT = "exploit", WITHDRAW = "withdraw", BOMB = "bomb", WATCH = "watch", SORTIE = "sortie";
	public static final String SUCCESS = "success", FAILURE = "failure", NEUTRAL = "neutral";
	protected static final float NEVER = -Float.MAX_VALUE;
	/** Days before the siege's arrival its hunting forces are let go from the muster. */
	protected static final float RELEASE_LEAD_DAYS = 3f;
	/** A muster counts as all in at this share of what was sent. */
	protected static final float ALL_IN = 0.95f;
	/** Times an exploit stays on past its first check. */
	protected static final int MAX_EXTENSIONS = 2;
	/** A bombing campaign ends after this many checks without handing over. */
	protected static final int STARVE_MAX_CHECKS = 6;
	/** A feint drew when its decoy's reported swarms rose by this factor. */
	protected static final float DREW = 1.2f;
	/** Bombers of opportunity trust "unguarded" from a report at most this old. */
	protected static final float FRESH_DAYS = 10f;
	/** A recon is done when its system's report is this fresh. */
	protected static final float RECON_FRESH_DAYS = 2f;

	/** One play. Saved: never rename the class or a field. */
	public static class Play {
		public String id;
		public String type;
		public String factionId;
		public String systemId;
		/** A feint's decoy system (A). */
		public String feintId;
		public String targetClass;
		public String strategy;
		public String phase;
		/** The staging and striking base. */
		public String baseId;
		/** A JOINT play's lead play. */
		public String leadId;
		/** The play this one follows on from (a starve's hammer): its siege is not in this one's way. */
		public String fromId;
		public float started = NEVER;
		public float phaseStart = NEVER;
		public float phaseDue = NEVER;
		public float releaseDay = NEVER;
		public float bomberDay = NEVER;
		/** The day a feint's squadron reached the decoy, when the watch's baseline is read. */
		public float feintArrived = NEVER;
		/** The Nexus world bombers strike as the siege arrives (a feint's), until they sail. */
		public String bomberWorldId;
		/** Warship FP the play's forces were sent with, a joint's partners' included. */
		public float plannedFP;
		public boolean sieged;
		public boolean released;
		public int extensions;
		public List<String> forceIds = new ArrayList<String>();
		public List<String> raidIds = new ArrayList<String>();
		public List<String> targetIds = new ArrayList<String>();
		// damage done (principle 3)
		public float nexusDownDays;
		public float nexusStreak;
		public float orbitDays;
		public float orbitAtCheck;
		public int landed, taken, raids, drivenOff, offInRow, checks;
		public float seenAtStart = -1f, seenLast = -1f, seenAtCheck = -1f, feintSeen = -1f;
		/** A bombing campaign's fuel: this check's budget and spend, and the campaign's total. */
		public float fuelBudget, fuelSpent;
		public float fuelTotal;
		public boolean drew;
		public boolean held;
		public String outcome, why;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Play> plays() {
		return ThreatIncData.map(KEY);
	}

	/** The faction's running plays. */
	public static List<Play> of(String fid) {
		List<Play> out = new ArrayList<Play>();
		for (Play pl : plays().values()) {
			if (pl.factionId != null && pl.factionId.equals(fid)) out.add(pl);
		}
		return out;
	}

	/** The faction's major play (a hammer, a bombing campaign, a feint, or its share of a partner's hammer), or null. */
	public static Play major(String fid) {
		for (Play pl : plays().values()) {
			if (fid == null || !fid.equals(pl.factionId)) continue;
			if (HAMMER.equals(pl.type) || STARVE.equals(pl.type) || FEINT.equals(pl.type) || JOINT.equals(pl.type)) return pl;
		}
		return null;
	}

	protected static boolean running(String fid, String type, String systemId) {
		for (Play pl : plays().values()) {
			if (fid.equals(pl.factionId) && type.equals(pl.type) && systemId != null && systemId.equals(pl.systemId)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// planning: which play the strategy runs next (section 3's table)
	// ------------------------------------------------------------------

	public static void plan(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p, float today,
			Random random) {
		String fid = faction.getId();
		if (c.strategy == null || p == null) return;
		// relief before offensives (user, 2026-09-27): no new play while it is owed
		if (ThreatFleetOrders.reliefOwed(faction)) return;
		opportunity(c, faction, p, today, random);
		String observer = ThreatIntel.observerOf(faction);
		float half = Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
		if (ThreatWarCouncil.HOLD.equals(c.strategy)) {
			// Hold: recon on the hives that threaten us, one a day; no invasions
			for (ThreatWarCouncil.Cluster k : p.clusters) {
				if (!k.threatens || ThreatIntel.age(observer, k.systemId) < half) continue;
				if (running(fid, RECON, k.systemId) || ThreatScouts.reconInFlight(fid, k.systemId)) continue;
				if (!reconDue(c, k.systemId, today)) continue;
				if (startRecon(c, faction, k, today, random, "Hold: it threatens us") != null) break;
			}
			return;
		}
		if (major(fid) != null) return;
		ThreatWarCouncil.Cluster focus = p.cluster(c.focusId);
		if (focus == null || !ThreatWarCouncil.fits(c.strategy, focus)) {
			c.focusId = ThreatWarCouncil.focus(c, p, c.strategy, random);
			focus = p.cluster(c.focusId);
			if (focus == null) return;
		}
		if (running(fid, RECON, focus.systemId)) return;
		// a big play waits for a fresh picture (section 4.4); after one recon that
		// brought none back, or when none can be sent, it goes on the picture it has
		if (ThreatIntel.age(observer, focus.systemId) >= half && reconDue(c, focus.systemId, today)) {
			if (ThreatScouts.reconInFlight(fid, focus.systemId)) return;
			if (startRecon(c, faction, focus, today, random, "the picture of " + focus.name + " is "
					+ (int) ThreatIntel.age(observer, focus.systemId) + " d old") != null) return;
		}
		String cls = focus.targetClass();
		Object[] feint = feintPlan(faction, p, focus);
		String[] types;
		float[] w;
		if (ThreatWarCouncil.STARVE.equals(c.strategy)) {
			// starve then invade: once the focus's Nexuses are down, the invasion is the likelier play
			types = new String[] { STARVE, HAMMER };
			w = new float[] { 1f, nexusesDown(focus) ? 2f : 0f };
		} else if (ThreatWarCouncil.ROLLBACK.equals(c.strategy)) {
			types = new String[] { HAMMER, FEINT };
			w = new float[] { 1f, feint != null ? 1f : 0f };
		} else {
			types = new String[] { HAMMER, FEINT };
			w = new float[] { 1.5f, feint != null ? 0.5f : 0f };
		}
		for (int i = 0; i < w.length; i++) {
			w[i] *= ThreatWarCouncil.personality(fid, types[i].toLowerCase()) * ThreatWarCouncil.learned(c, types[i] + ":" + cls);
		}
		int pick = ThreatWarCouncil.draw(w, random);
		if (pick < 0) return;
		Play started = null;
		String why = c.strategy + " on " + focus.name;
		if (STARVE.equals(types[pick])) {
			started = startStarve(c, faction, p, focus, today, random, why);
		} else if (FEINT.equals(types[pick])) {
			started = startFeint(c, faction, p, focus, (ThreatWarCouncil.Cluster) feint[0], (MarketAPI) feint[1], today,
					random, why);
		}
		if (started == null && !STARVE.equals(types[pick])) started = startHammer(c, faction, p, focus, today, random, why);
		if (started == null) {
			ThreatIncConfig.logOnChange("councilIdle:" + fid, c.strategy + ":" + types[pick], "Council " + fid + ": no "
					+ types[pick] + " on " + focus.name + " can start (no base, target or pay)");
			// another cluster tomorrow (focus draws by chance), not a wait until the review
			c.focusId = null;
		}
	}

	protected static Play newPlay(ThreatWarCouncil.Council c, String type, ThreatWarCouncil.Cluster k, float today) {
		Play pl = new Play();
		pl.id = c.factionId + "#" + (++c.nextPlay);
		pl.type = type;
		pl.factionId = c.factionId;
		pl.systemId = k != null ? k.systemId : null;
		pl.targetClass = k != null ? k.targetClass() : "cluster";
		pl.strategy = c.strategy;
		pl.started = today;
		if (k != null) pl.targetIds.addAll(k.hiveIds);
		plays().put(pl.id, pl);
		return pl;
	}

	// ------------------------------------------------------------------
	// starting each play
	// ------------------------------------------------------------------

	protected static Play startRecon(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Cluster k,
			float today, Random random, String why) {
		StarSystemAPI s = system(k.systemId);
		if (s == null) return null;
		Play pl = newPlay(c, RECON, k, today);
		MarketAPI base = ThreatFleetOrders.pickBase(faction, s.getLocation());
		float travel = base != null ? ThreatReach.days(ly(base, s)) : 30f;
		boolean scout = ThreatScouts.recon(faction.getId(), s) != null;
		// one probing raid, at the doctrine size: there, a few days over the world, and
		// back - paid from the month's opportunity share, as bombers of opportunity are
		MarketAPI world = firstHive(pl);
		boolean probe = false;
		if (world != null) {
			float fp = ThreatIncConfig.councilSquadronFP();
			MarketAPI from = squadronBase(faction, world, fp);
			float cost = from != null ? fuelCost(from, world, fp, 5f) : Float.MAX_VALUE;
			if (cost <= oppLeft(c, today) && squadron(pl, faction, world, fp, 2f * travel + 5f) != null) {
				c.oppSpent += cost;
				probe = true;
			}
		}
		if (!scout && !probe) {
			plays().remove(pl.id);
			c.nextPlay--;
			return null;
		}
		c.reconDay.put(k.systemId, today);
		pl.phaseDue = today + travel + 15f;
		phase(pl, SCOUT, why + (scout ? "; scout sent" : "") + (probe ? "; probing raid" : ""), today);
		return pl;
	}

	protected static Play startHammer(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p,
			ThreatWarCouncil.Cluster k, float today, Random random, String why) {
		return startHammer(c, faction, p, k, today, random, why, null);
	}

	/** A hammer; {@code fromId} names the play it follows on from (a starve), whose siege is not in its way. */
	protected static Play startHammer(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p,
			ThreatWarCouncil.Cluster k, float today, Random random, String why, String fromId) {
		StarSystemAPI s = system(k.systemId);
		if (s == null || !k.inReach()) return null;
		MarketAPI base = baseOf(k, faction.getId());
		if (base == null) return null;
		List<String> ownPlays = new ArrayList<String>();
		if (fromId != null) ownPlays.add(fromId);
		List<MarketAPI> targets = liveTargets(faction, k.hiveIds, ownPlays);
		if (targets.isEmpty()) return null;
		Play pl = newPlay(c, HAMMER, k, today);
		pl.fromId = fromId;
		pl.targetIds = ids(targets);
		pl.baseId = base.getId();
		// staging telegraphs (section 9): one time in two, a base that reaches
		// another known system stages against that and turns at the last
		StarSystemAPI stageAt = s;
		List<ThreatWarCouncil.Cluster> decoys = new ArrayList<ThreatWarCouncil.Cluster>();
		for (ThreatWarCouncil.Cluster o : p.clusters) {
			if (o != k && o.bases.contains(base)) decoys.add(o);
		}
		if (!decoys.isEmpty() && random.nextFloat() < 0.5f) {
			StarSystemAPI d = system(decoys.get(random.nextInt(decoys.size())).systemId);
			if (d != null) stageAt = d;
		}
		stage(pl, faction, base, s, targets, stageAt);
		String observer = ThreatIntel.observerOf(faction);
		float half = Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
		boolean scout = ThreatIntel.age(observer, s.getId()) >= 0.5f * half
				&& !ThreatScouts.reconInFlight(faction.getId(), s.getId()) && ThreatScouts.recon(faction.getId(), s) != null;
		pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilPrepareDays()) * ThreatWarCouncil.jitter(random);
		phase(pl, PREPARE, why + "; stages at " + base.getName() + (stageAt != s ? " against " + stageAt.getName()
				+ " (decoy)" : "") + (scout ? "; scout sent" : ""), today);
		return pl;
	}

	protected static Play startStarve(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p,
			ThreatWarCouncil.Cluster k, float today, Random random, String why) {
		if (!k.inReach()) return null;
		StarSystemAPI s = system(k.systemId);
		MarketAPI base = s != null ? richestBase(k, faction, s) : null;
		if (base == null) return null;
		List<MarketAPI> targets = liveTargets(faction, k.hiveIds);
		if (targets.isEmpty()) return null;
		Play pl = newPlay(c, STARVE, k, today);
		pl.targetIds = ids(targets);
		pl.baseId = base.getId();
		pl.fuelBudget = share(faction.getId(), ThreatIncConfig.councilStarveShare()) * p.fuel;
		pl.seenAtStart = pl.seenAtCheck = ThreatIntel.systemFP(ThreatIntel.observerOf(faction), k.systemId);
		pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilStarveCheckDays()) * ThreatWarCouncil.jitter(random);
		saturate(pl, faction, random);
		// a campaign that can pay neither a squadron nor a saturation expedition is not started
		if (!pl.sieged && cheapestSquadron(pl, faction) > pl.fuelBudget) {
			plays().remove(pl.id);
			c.nextPlay--;
			return null;
		}
		phase(pl, BOMB, why + "; " + (int) pl.fuelBudget + " fuel a month to burn"
				+ (pl.sieged ? "; a saturation expedition sails" : ""), today);
		return pl;
	}

	/** Whether a squadron may bomb the world: its reports show no swarm over it, or the faction's flotilla holds the orbit. */
	protected static boolean bombable(String fid, String observer, MarketAPI w) {
		if (ThreatIntel.worldFP(observer, w) <= 0f) return true;
		return ThreatGroundFronts.friendlyPointsNear(fid, w) > 0f && !ThreatGroundFronts.orbitContestedFor(fid, w);
	}

	/**
	 * The cluster's base whose pools field the largest siege (ThreatPosture.siegeCapacityFP):
	 * a saturation expedition sails where the pools pay, not from the nearest outpost
	 * (h53c: a 500 FP share from a forward base paid no siege).
	 */
	protected static MarketAPI richestBase(ThreatWarCouncil.Cluster k, FactionAPI faction, StarSystemAPI s) {
		MarketAPI best = null;
		float most = -1f;
		for (MarketAPI m : k.bases) {
			if (m == null || !faction.getId().equals(m.getFactionId()) || !m.isInEconomy()) continue;
			float cap = ThreatPosture.siegeCapacityFP(m, s, ThreatSoftening.huntDonors(faction, m));
			if (cap > most) {
				most = cap;
				best = m;
			}
		}
		return best;
	}

	/** The fuel of the cheapest squadron the campaign could send to a bombable Nexus now; Float.MAX_VALUE when none. */
	protected static float cheapestSquadron(Play pl, FactionAPI faction) {
		float stay = Math.max(1f, ThreatIncConfig.councilStarveCheckDays());
		float least = Float.MAX_VALUE;
		String observer = ThreatIntel.observerOf(faction);
		for (String id : pl.targetIds) {
			MarketAPI w = market(id);
			if (!ThreatWarCouncil.isHive(w) || !ThreatColonyManager.hasOperationalNexus(w)) continue;
			if (!bombable(pl.factionId, observer, w)) continue;
			float fp = squadronFP(w);
			if (fp >= Float.MAX_VALUE) continue;
			MarketAPI base = squadronBase(faction, w, fp);
			if (base != null) least = Math.min(least, fuelCost(base, w, fp, stay));
		}
		return least;
	}

	/**
	 * A feint against B (section 4.2): A, the nearest known system within
	 * councilThreatLY of B that a base reaches, a small one first; and the
	 * striking base, the nearest within councilStrikeMaxDays of B. Null when
	 * there is none. {A's cluster, the base}.
	 */
	protected static Object[] feintPlan(FactionAPI faction, ThreatWarCouncil.Picture p, ThreatWarCouncil.Cluster b) {
		StarSystemAPI sb = system(b.systemId);
		if (sb == null) return null;
		ThreatWarCouncil.Cluster a = null;
		float best = 0f;
		for (ThreatWarCouncil.Cluster k : p.clusters) {
			if (k == b || !k.inReach()) continue;
			StarSystemAPI sk = system(k.systemId);
			if (sk == null) continue;
			float d = Misc.getDistanceLY(sk.getLocation(), sb.getLocation());
			if (d > ThreatIncConfig.councilThreatLY()) continue;
			float score = ("small".equals(k.scale) ? 2f : 1f) / (1f + d);
			if (score > best) {
				best = score;
				a = k;
			}
		}
		if (a == null) return null;
		MarketAPI base = null;
		float bestLY = Float.MAX_VALUE;
		for (MarketAPI m : b.bases) {
			if (m == null || !faction.getId().equals(m.getFactionId()) || !m.isInEconomy()) continue;
			float d = ly(m, sb);
			if (ThreatReach.days(d) > ThreatIncConfig.councilStrikeMaxDays() || d >= bestLY) continue;
			bestLY = d;
			base = m;
		}
		return base != null ? new Object[] { a, base } : null;
	}

	protected static Play startFeint(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p,
			ThreatWarCouncil.Cluster b, ThreatWarCouncil.Cluster a, MarketAPI base, float today, Random random, String why) {
		StarSystemAPI sb = system(b.systemId), sa = system(a.systemId);
		if (sb == null || sa == null || base == null) return null;
		List<MarketAPI> targets = liveTargets(faction, b.hiveIds);
		List<MarketAPI> decoys = liveTargets(faction, a.hiveIds);
		if (targets.isEmpty() || decoys.isEmpty()) return null;
		// the feint's world: A's smallest
		MarketAPI aim = decoys.get(0);
		for (MarketAPI m : decoys) {
			if (m.getSize() < aim.getSize()) aim = m;
		}
		Play pl = newPlay(c, FEINT, b, today);
		pl.feintId = a.systemId;
		pl.targetIds = ids(targets);
		pl.baseId = base.getId();
		MarketAPI from = ThreatFleetOrders.pickBase(faction, aim.getLocationInHyperspace());
		float reach = from != null ? ThreatFleetOrders.sortieReachFP(from, aim.getLocationInHyperspace()) : 0f;
		float fp = Math.max(ThreatIncConfig.councilSquadronFP(),
				share(faction.getId(), ThreatIncConfig.councilFeintShare()) * reach);
		float travel = from != null ? ThreatReach.days(ly(from, sa)) : 0f;
		float watch = Math.max(1f, ThreatIncConfig.councilFeintWatchDays()) * ThreatWarCouncil.jitter(random);
		ThreatFleetOrders.Order lead = squadron(pl, faction, aim, fp, 2f * travel + watch + 10f);
		if (lead == null) {
			plays().remove(pl.id);
			c.nextPlay--;
			return null;
		}
		// the strike base stages against the decoy: the swarm reads staged stock (section 9)
		stage(pl, faction, base, sb, targets, sa);
		pl.feintSeen = ThreatIntel.systemFP(ThreatIntel.observerOf(faction), a.systemId);
		pl.phaseDue = today + travel + watch;
		MarketAPI raidBase = market(lead.baseMarketId);
		phase(pl, WATCH, why + "; feint at " + aim.getName() + " in " + sa.getName() + ", " + (int) fp + " FP from "
				+ (raidBase != null ? raidBase.getName() : lead.baseMarketId) + "; strikes from " + base.getName()
				+ ", staging against " + sa.getName(), today);
		return pl;
	}

	/**
	 * Bombers of opportunity (section 4.6), inside Starve and Roll back (and
	 * Hold, on the hives that threaten us): a squadron at a hive with a Nexus the
	 * faction's fresh report shows unguarded, bounded by a monthly fuel share
	 * (councilOpportunityShare), at most one sent a day.
	 */
	protected static void opportunity(ThreatWarCouncil.Council c, FactionAPI faction, ThreatWarCouncil.Picture p,
			float today, Random random) {
		if (ThreatWarCouncil.DECAPITATE.equals(c.strategy)) return;
		float budget = oppLeft(c, today);
		if (budget <= 0f) return;
		String fid = c.factionId;
		String observer = ThreatIntel.observerOf(faction);
		java.util.Set<String> aimed = new java.util.HashSet<String>();
		for (Play pl : of(fid)) aimed.addAll(pl.targetIds);
		List<MarketAPI> cand = new ArrayList<MarketAPI>();
		List<Float> w = new ArrayList<Float>();
		for (ThreatWarCouncil.Cluster k : p.clusters) {
			if (ThreatWarCouncil.HOLD.equals(c.strategy) && !k.threatens) continue;
			if (!k.inReach()) continue;
			ThreatIntel.Report r = ThreatIntel.report(observer, k.systemId);
			if (r == null || r.age() > FRESH_DAYS) continue;
			for (String id : k.hiveIds) {
				MarketAPI m = market(id);
				if (!ThreatWarCouncil.isHive(m) || m.getStarSystem() == null || aimed.contains(id)
						|| !r.worlds.containsKey(id)) continue;
				if (r.worldFP(id) > 0f || !ThreatColonyManager.hasOperationalNexus(m)) continue;
				if (ThreatFleetOrders.hasRaid(fid, id)) continue;
				cand.add(m);
				w.add((float) m.getSize());
			}
		}
		if (cand.isEmpty()) return;
		float[] arr = new float[w.size()];
		for (int i = 0; i < arr.length; i++) arr[i] = w.get(i);
		int pick = ThreatWarCouncil.draw(arr, random);
		if (pick < 0) return;
		MarketAPI world = cand.get(pick);
		float fp = squadronFP(world);
		MarketAPI base = squadronBase(faction, world, fp);
		if (base == null) return;
		float stay = Math.max(1f, ThreatIncConfig.councilStarveCheckDays());
		float cost = fuelCost(base, world, fp, stay);
		if (cost > budget) return;
		ThreatWarCouncil.Cluster k = p.cluster(world.getStarSystem().getId());
		Play pl = newPlay(c, BOMBERS, k, today);
		pl.targetIds = new ArrayList<String>();
		pl.targetIds.add(world.getId());
		float travel = ThreatReach.days(ly(base, world.getStarSystem()));
		if (squadron(pl, faction, world, fp, 2f * travel + stay) == null) {
			plays().remove(pl.id);
			c.nextPlay--;
			return;
		}
		c.oppSpent += cost;
		ThreatIntel.Report seen = ThreatIntel.report(observer, world.getStarSystem().getId());
		phase(pl, SORTIE, "bombers of opportunity at " + world.getName() + ", reported unguarded "
				+ (seen != null ? ThreatIntel.when(seen) : "lately") + ", " + (int) fp + " FP from " + base.getName()
				+ ", " + (int) cost + " fuel", today);
	}

	/** What is left of the month's opportunity fuel (bombers of opportunity and probing raids), the month rolled over first. */
	protected static float oppLeft(ThreatWarCouncil.Council c, float today) {
		if (c.oppMonth == NEVER || today < c.oppMonth || today - c.oppMonth >= 30f) {
			c.oppMonth = today;
			c.oppSpent = 0f;
		}
		ThreatWarCouncil.Picture p = ThreatWarCouncil.picture(c.factionId);
		if (p == null) return 0f;
		return share(c.factionId, ThreatIncConfig.councilOpportunityShare())
				* ThreatWarCouncil.personality(c.factionId, "bombers") * p.fuel - c.oppSpent;
	}

	/** Whether a recon on the system may start: one per intel half-life (a recon that saw nothing waits). */
	protected static boolean reconDue(ThreatWarCouncil.Council c, String systemId, float today) {
		Float last = c.reconDay.get(systemId);
		return last == null || today < last || today - last >= Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
	}

	// ------------------------------------------------------------------
	// the daily advance
	// ------------------------------------------------------------------

	public static void advance(float today, Random random) {
		if (plays().isEmpty()) return;
		for (Play pl : new ArrayList<Play>(plays().values())) {
			if (!plays().containsKey(pl.id)) continue;
			// one play's error stops that play, not the others after it
			try {
				advanceOne(pl, today, random);
			} catch (RuntimeException e) {
				ThreatIncConfig.log("Play " + pl.id + ": error " + e);
				Global.getLogger(ThreatPlays.class).error("Play " + pl.id, e);
				try {
					end(pl, NEUTRAL, "error " + e, today);
				} catch (RuntimeException again) {
					plays().remove(pl.id);
				}
			}
		}
	}

	protected static void advanceOne(Play pl, float today, Random random) {
		FactionAPI faction = Global.getSector().getFaction(pl.factionId);
		if (faction == null || !ThreatWarCouncil.governs(pl.factionId)) {
			end(pl, NEUTRAL, "its council is off", today);
			return;
		}
		// a start that failed half-way (an error before its first phase) never runs
		if (pl.phase == null) {
			end(pl, NEUTRAL, "it never started", today);
			return;
		}
		sample(pl, faction, today);
		// relief before offensives: the next phase waits, the play is paused, not cancelled
		if (ThreatFleetOrders.reliefOwed(faction) && pausable(pl)) {
			if (!pl.held) {
				pl.held = true;
				ThreatIncConfig.log("Play " + pl.id + " " + pl.type + ": held in " + pl.phase + " (relief owed)");
			}
			if (pl.phaseDue != NEVER) pl.phaseDue += 1f;
			return;
		}
		pl.held = false;
		if (RECON.equals(pl.type)) advanceRecon(pl, faction, today);
		else if (HAMMER.equals(pl.type) || FEINT.equals(pl.type)) advanceStrike(pl, faction, today, random);
		else if (STARVE.equals(pl.type)) advanceStarve(pl, faction, today, random);
		else if (BOMBERS.equals(pl.type)) {
			if (pl.raidIds.isEmpty() || !raidAlive(pl.raidIds.get(0))) end(pl, NEUTRAL, "its squadron is gone", today);
		} else if (JOINT.equals(pl.type)) {
			if (pl.leadId == null || !plays().containsKey(pl.leadId)) end(pl, NEUTRAL, "its lead play is gone", today);
		}
	}

	protected static boolean pausable(Play pl) {
		return PREPARE.equals(pl.phase) || MUSTER.equals(pl.phase) || BOMB.equals(pl.phase) || WATCH.equals(pl.phase);
	}

	/** The day's damage done at the target: Nexus-days down, orbit-days held, the swarms its reports show. */
	protected static void sample(Play pl, FactionAPI faction, float today) {
		boolean striking = STRIKE.equals(pl.phase) || EXPLOIT.equals(pl.phase) || WITHDRAW.equals(pl.phase)
				|| BOMB.equals(pl.phase);
		if (!striking || pl.systemId == null) return;
		int down = 0;
		for (String id : pl.targetIds) {
			MarketAPI m = market(id);
			if (!ThreatWarCouncil.isHive(m)) continue;
			if (m.hasIndustry(ThreatColonyManager.SWARM_NEXUS) && !ThreatColonyManager.hasOperationalNexus(m)) down++;
			if (ThreatGroundFronts.friendlyPointsNear(pl.factionId, m) > 0f
					&& !ThreatGroundFronts.orbitContestedFor(pl.factionId, m)) {
				pl.orbitDays += 1f;
			}
		}
		pl.nexusDownDays += down;
		pl.nexusStreak = down > 0 ? pl.nexusStreak + 1f : 0f;
		pl.seenLast = ThreatIntel.systemFP(ThreatIntel.observerOf(faction), pl.systemId);
	}

	protected static void advanceRecon(Play pl, FactionAPI faction, float today) {
		String observer = ThreatIntel.observerOf(faction);
		if (today > pl.started + 1f && ThreatIntel.known(observer, pl.systemId)
				&& ThreatIntel.age(observer, pl.systemId) < RECON_FRESH_DAYS) {
			end(pl, NEUTRAL, "the picture is fresh", today);
		} else if (today >= pl.phaseDue) {
			end(pl, NEUTRAL, "no fresh report by its day", today);
		}
	}

	/** A hammer's and a feint's phases. */
	protected static void advanceStrike(Play pl, FactionAPI faction, float today, Random random) {
		if (PREPARE.equals(pl.phase)) {
			if (today >= pl.phaseDue) toMuster(pl, faction, today, random);
		} else if (MUSTER.equals(pl.phase)) {
			musterCheck(pl, faction, today, random);
		} else if (WATCH.equals(pl.phase)) {
			watchCheck(pl, faction, today, random);
		} else if (STRIKE.equals(pl.phase)) {
			strikeCheck(pl, faction, today, random);
		} else if (EXPLOIT.equals(pl.phase)) {
			exploitCheck(pl, faction, today, random);
		} else if (WITHDRAW.equals(pl.phase)) {
			if (!live(purgeOf(pl.id))) finish(pl, today, "its siege is over");
			else if (today >= pl.phaseDue) finish(pl, today, "its siege fights on alone");
		}
	}

	protected static void toMuster(Play pl, FactionAPI faction, float today, Random random) {
		MarketAPI base = market(pl.baseId);
		StarSystemAPI s = system(pl.systemId);
		if (base == null || !pl.factionId.equals(base.getFactionId()) || s == null) {
			end(pl, FAILURE, "its base is lost", today);
			return;
		}
		if (!anyHive(pl.targetIds)) {
			finish(pl, today, "its worlds are the swarm's no longer");
			return;
		}
		List<MarketAPI> targets = liveTargets(faction, pl.targetIds, own(pl));
		if (targets.isEmpty()) {
			end(pl, NEUTRAL, "another siege has its worlds", today);
			return;
		}
		float huntFP = share(pl.factionId, ThreatIncConfig.councilHammerShare())
				* ThreatSoftening.playPayableFP(faction, base, s);
		String forceId = huntFP >= 25f ? ThreatSoftening.sendPlay(faction, base, s, targets, huntFP,
				Math.max(0f, ThreatIncConfig.councilMusterFloor()) * huntFP, pl.id, true) : null;
		if (forceId != null) {
			pl.forceIds.add(forceId);
			pl.plannedFP += ThreatSoftening.forceFP(forceId);
		}
		int partners = inviteJoint(pl, faction, s, targets, today, random);
		if (pl.plannedFP <= 0f) {
			// no hunt paid: the siege goes alone
			strike(pl, faction, today, random, "no hunting force paid (" + (int) huntFP + " FP share)");
			return;
		}
		// the voyage to the bearing, then the muster's own days (h53b: a 30-day muster 39 ly out never filled)
		pl.phaseDue = today + ThreatReach.days(ly(base, s)) + Math.max(1f, ThreatIncConfig.councilMusterDays())
				* ThreatWarCouncil.personality(pl.factionId, "musterMult") * ThreatWarCouncil.jitter(random);
		phase(pl, MUSTER, (int) pl.plannedFP + " FP sent to the muster" + (partners > 0 ? ", " + partners
				+ (partners == 1 ? " partner" : " partners") + " with it" : ""), today);
	}

	/** A partner joins the hammer with its own force, held at its own bearing (section 7). Returns how many joined. */
	protected static int inviteJoint(Play pl, FactionAPI faction, StarSystemAPI s, List<MarketAPI> targets, float today,
			Random random) {
		int joined = 0;
		for (String partner : ThreatCoalition.partners(pl.factionId)) {
			if (partner == null || partner.equals(pl.factionId) || !ThreatWarCouncil.governs(partner)) continue;
			if (major(partner) != null) continue;
			float chance = Math.min(1f, 0.6f * ThreatWarCouncil.personality(partner, "joint")
					* ThreatWarCouncil.personality(pl.factionId, "joint"));
			if (random.nextFloat() >= chance) continue;
			FactionAPI pf = Global.getSector().getFaction(partner);
			MarketAPI pbase = null;
			for (MarketAPI b : IncursionManager.siegeBasesFor(s)) {
				if (partner.equals(b.getFactionId())) {
					pbase = b;
					break;
				}
			}
			if (pf == null || pbase == null) continue;
			float fp = share(partner, ThreatIncConfig.councilHammerShare()) * ThreatSoftening.playPayableFP(pf, pbase, s);
			if (fp < 25f) continue;
			// the partner's force carries the lead's id: the lead's siege counts it beside it (ThreatPurgeFGI.friendsNear)
			String forceId = ThreatSoftening.sendPlay(pf, pbase, s, targets, fp,
					Math.max(0f, ThreatIncConfig.councilMusterFloor()) * fp, pl.id, true);
			if (forceId == null) continue;
			ThreatWarCouncil.Council pc = ThreatWarCouncil.councilOf(partner);
			Play j = newPlay(pc, JOINT, null, today);
			j.systemId = pl.systemId;
			j.targetClass = pl.targetClass;
			j.targetIds = new ArrayList<String>(pl.targetIds);
			j.leadId = pl.id;
			j.forceIds.add(forceId);
			j.plannedFP = ThreatSoftening.forceFP(forceId);
			pl.plannedFP += j.plannedFP;
			phase(j, MUSTER, "joins " + pl.id + " (" + pl.factionId + ") with " + (int) j.plannedFP + " FP from "
					+ pbase.getName(), today);
			joined++;
		}
		return joined;
	}

	/** The forces of the play and of the partners in it. */
	protected static List<String> forcesOf(Play pl) {
		List<String> out = new ArrayList<String>(pl.forceIds);
		for (Play j : plays().values()) {
			if (JOINT.equals(j.type) && pl.id.equals(j.leadId)) out.addAll(j.forceIds);
		}
		return out;
	}

	protected static boolean anyForceAlive(Play pl) {
		for (String f : forcesOf(pl)) {
			if (ThreatSoftening.forceAlive(f)) return true;
		}
		return false;
	}

	protected static void musterCheck(Play pl, FactionAPI faction, float today, Random random) {
		float mustered = 0f, sent = 0f;
		for (String f : forcesOf(pl)) {
			if (!ThreatSoftening.forceAlive(f)) continue;
			mustered += ThreatSoftening.musteredFP(f);
			sent += ThreatSoftening.forceFP(f);
		}
		boolean allIn = sent > 0f && mustered >= ALL_IN * sent;
		if (!allIn && today < pl.phaseDue && sent > 0f) return;
		float floor = Math.max(0f, ThreatIncConfig.councilMusterFloor()) * pl.plannedFP;
		if (mustered < floor) {
			standDownForces(pl, "its muster is short");
			end(pl, FAILURE, "mustered " + (int) mustered + " of " + (int) pl.plannedFP + " FP by its day, under the "
					+ (int) floor + " floor", today);
			return;
		}
		strike(pl, faction, today, random, (allIn ? "all in, " : "its day, ") + (int) mustered + " of "
				+ (int) pl.plannedFP + " FP at the muster");
	}

	protected static void watchCheck(Play pl, FactionAPI faction, float today, Random random) {
		String observer = ThreatIntel.observerOf(faction);
		// the baseline is what the squadron finds when it gets there; only a later look counts
		if (pl.feintArrived == NEVER && feintArrived(pl)) {
			pl.feintArrived = today;
			pl.feintSeen = ThreatIntel.systemFP(observer, pl.feintId);
			ThreatIncConfig.log("Play " + pl.id + " FEINT: over " + systemName(pl.feintId) + ", "
					+ (int) pl.feintSeen + " FP reported there");
		}
		ThreatIntel.Report r = ThreatIntel.report(observer, pl.feintId);
		float seen = ThreatIntel.systemFP(observer, pl.feintId);
		if (pl.feintArrived != NEVER && r != null && r.day > pl.feintArrived
				&& seen >= DREW * Math.max(1f, pl.feintSeen)) {
			pl.drew = true;
			strike(pl, faction, today, random, "the feint drew: " + (int) pl.feintSeen + " -> " + (int) seen
					+ " FP reported at " + systemName(pl.feintId));
		} else if (today >= pl.phaseDue) {
			strike(pl, faction, today, random, "the feint drew nothing seen (" + (int) seen + " FP reported at "
					+ systemName(pl.feintId) + ")");
		}
	}

	/**
	 * The strike (sections 4.1 and 4.2): the siege sails from the play's base,
	 * sized by the play's share of what the base and its donors can field
	 * (ThreatPosture.siegeCapacityFP); every world it can carry marines for,
	 * the most first. A feint sends its hunting force now. The hunts are let go
	 * RELEASE_LEAD_DAYS before the siege arrives, the feint's bombers sail to
	 * arrive with it.
	 */
	protected static void strike(Play pl, FactionAPI faction, float today, Random random, String why) {
		MarketAPI base = market(pl.baseId);
		StarSystemAPI s = system(pl.systemId);
		if (base == null || !pl.factionId.equals(base.getFactionId()) || s == null) {
			standDownForces(pl, "its base is lost");
			end(pl, FAILURE, "its base is lost", today);
			return;
		}
		if (!anyHive(pl.targetIds)) {
			standDownForces(pl, "its worlds are taken");
			finish(pl, today, "its worlds are the swarm's no longer");
			return;
		}
		List<MarketAPI> targets = liveTargets(faction, pl.targetIds, own(pl));
		if (targets.isEmpty()) {
			standDownForces(pl, "another siege has its worlds");
			end(pl, NEUTRAL, "another siege has its worlds", today);
			return;
		}
		if (FEINT.equals(pl.type) && pl.forceIds.isEmpty()) {
			float huntFP = share(pl.factionId, ThreatIncConfig.councilHammerShare())
					* ThreatSoftening.playPayableFP(faction, base, s);
			String forceId = huntFP >= 25f ? ThreatSoftening.sendPlay(faction, base, s, targets, huntFP,
					Math.max(0f, ThreatIncConfig.councilMusterFloor()) * huntFP, pl.id, true) : null;
			if (forceId != null) {
				pl.forceIds.add(forceId);
				pl.plannedFP += ThreatSoftening.forceFP(forceId);
			}
		}
		float siegeFP = share(pl.factionId, ThreatIncConfig.councilHammerShare())
				* ThreatPosture.siegeCapacityFP(base, s, ThreatSoftening.huntDonors(faction, base));
		ThreatPurgeFGI purge = null;
		for (int n = targets.size(); n >= 1 && purge == null; n--) {
			List<MarketAPI> set = new ArrayList<MarketAPI>(targets.subList(0, n));
			List<Integer> sizes = IncursionManager.playSiegeSizes(base, faction, set, siegeFP, null);
			if (sizes.isEmpty()) continue;
			purge = IncursionManager.launchSiegeExpedition(base, faction, s, set, sizes, false, random, 0f,
					new LinkedHashSet<String>(), pl.id);
		}
		ThreatConvoys.clearPlayStaging(pl.id);
		pl.seenAtStart = pl.seenAtCheck = ThreatIntel.systemFP(ThreatIntel.observerOf(faction), pl.systemId);
		float arrive;
		if (purge != null) {
			pl.sieged = true;
			arrive = today + IncursionManager.razeArrivalDays(base, s);
			pl.releaseDay = Math.max(today, arrive - RELEASE_LEAD_DAYS);
		} else {
			arrive = today;
			pl.releaseDay = today;
		}
		if (FEINT.equals(pl.type)) {
			// bombers on B's Nexus, arriving with the main force
			for (MarketAPI m : targets) {
				if (ThreatColonyManager.hasOperationalNexus(m)) {
					pl.bomberWorldId = m.getId();
					MarketAPI from = squadronBase(faction, m, squadronFP(m));
					float travel = from != null ? ThreatReach.days(ly(from, s)) : 0f;
					pl.bomberDay = Math.max(today, arrive - travel);
					break;
				}
			}
		}
		if (purge == null && forcesOf(pl).isEmpty()) {
			end(pl, FAILURE, why + "; neither the siege nor a hunting force could be paid (" + (int) siegeFP
					+ " FP siege share)", today);
			return;
		}
		pl.phaseDue = pl.releaseDay;
		phase(pl, STRIKE, why + "; " + (purge != null ? "siege of " + purge.abstractFull() + " FP sails from "
				+ base.getName() + ", arrives in " + (int) (arrive - today) + " d" : "no siege paid ("
				+ (int) siegeFP + " FP share), the hunts go alone")
				+ (pl.bomberWorldId != null ? "; bombers on " + market(pl.bomberWorldId).getName() + " in "
						+ (int) (pl.bomberDay - today) + " d" : ""), today);
	}

	/** A feint's bombers on B's Nexus sail on their day, in the strike or after the release. */
	protected static void sendBombers(Play pl, FactionAPI faction, float today) {
		if (pl.bomberWorldId == null || today < pl.bomberDay) return;
		MarketAPI world = market(pl.bomberWorldId);
		pl.bomberWorldId = null;
		if (!ThreatWarCouncil.isHive(world)) return;
		float fp = squadronFP(world);
		MarketAPI from = squadronBase(faction, world, fp);
		float travel = from != null ? ThreatReach.days(ly(from, world.getStarSystem())) : 0f;
		boolean sent = fp < Float.MAX_VALUE && squadron(pl, faction, world, fp,
				2f * travel + Math.max(1f, ThreatIncConfig.councilExploitDays())) != null;
		ThreatIncConfig.log("Play " + pl.id + ": bombers on " + world.getName()
				+ (sent ? " sail, " + (int) fp + " FP" : " not paid"));
	}

	protected static void strikeCheck(Play pl, FactionAPI faction, float today, Random random) {
		sendBombers(pl, faction, today);
		if (today < pl.releaseDay) {
			if (!pl.sieged && !anyForceAlive(pl)) end(pl, FAILURE, "its forces are gone before the strike", today);
			return;
		}
		pl.released = true;
		for (String f : forcesOf(pl)) ThreatSoftening.release(f);
		for (Play j : plays().values()) {
			if (JOINT.equals(j.type) && pl.id.equals(j.leadId)) phase(j, EXPLOIT, "released by " + pl.id, today);
		}
		pl.orbitAtCheck = pl.orbitDays;
		pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilExploitDays()) * ThreatWarCouncil.jitter(random);
		phase(pl, EXPLOIT, "the hunts go in" + (pl.sieged ? " as the siege nears" : ""), today);
	}

	protected static void exploitCheck(Play pl, FactionAPI faction, float today, Random random) {
		sendBombers(pl, faction, today);
		ThreatPurgeFGI purge = purgeOf(pl.id);
		boolean siege = live(purge);
		boolean forces = anyForceAlive(pl);
		if (!siege && !forces) {
			finish(pl, today, "its forces are spent or home");
			return;
		}
		if (today < pl.phaseDue) return;
		float held = pl.orbitDays - pl.orbitAtCheck;
		pl.orbitAtCheck = pl.orbitDays;
		int landed = landings(purge);
		boolean thinning = pl.seenAtCheck > 0f && pl.seenLast >= 0f && pl.seenLast < 0.8f * pl.seenAtCheck;
		boolean landing = landed > pl.landed;
		pl.landed = Math.max(pl.landed, landed);
		String damage = "orbit held " + (int) held + " world-days, " + landed + " landings, swarm seen "
				+ (int) pl.seenAtCheck + " -> " + (int) Math.max(0f, pl.seenLast) + " FP";
		pl.seenAtCheck = pl.seenLast;
		if (pl.extensions < MAX_EXTENSIONS && (held > 0f || landing || thinning)) {
			pl.extensions++;
			pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilExploitDays()) * ThreatWarCouncil.jitter(random);
			ThreatIncConfig.log("Play " + pl.id + " " + pl.type + ": stays (" + damage + ")");
			return;
		}
		standDownForces(pl, "its play withdraws");
		if (siege) {
			// the play is judged at the siege's end, or a check later: the siege may bombard on for long
			pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilExploitDays());
			phase(pl, WITHDRAW, "withdraws, the siege fights on alone (" + damage + ")", today);
		} else {
			finish(pl, today, "withdraws (" + damage + ")");
		}
	}

	protected static void advanceStarve(Play pl, FactionAPI faction, float today, Random random) {
		if (!anyHive(pl.targetIds)) {
			finish(pl, today, "its worlds are the swarm's no longer");
			return;
		}
		// squadrons in rotation over the Nexuses, one sent a day, the one its reports
		// show least guarded first (where, not how big: the squadron is the doctrine size)
		float stay = Math.max(1f, ThreatIncConfig.councilStarveCheckDays());
		final String observer = ThreatIntel.observerOf(faction);
		List<MarketAPI> nexuses = new ArrayList<MarketAPI>();
		boolean guarded = false;
		for (String id : pl.targetIds) {
			MarketAPI w = market(id);
			if (!ThreatWarCouncil.isHive(w) || ThreatFleetOrders.hasRaid(pl.factionId, id)) continue;
			if (!ThreatColonyManager.hasOperationalNexus(w)) continue;
			// a squadron bombs under a held orbit (bombing squadron, 2026-10-01): where its
			// reports show no swarm, or our flotilla holds the orbit (h53c: squadrons sent
			// alone into 600-3500 FP of swarms were all driven off)
			if (!bombable(pl.factionId, observer, w)) {
				guarded = true;
				continue;
			}
			nexuses.add(w);
		}
		java.util.Collections.sort(nexuses, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(ThreatIntel.worldFP(observer, a), ThreatIntel.worldFP(observer, b));
			}
		});
		String idle = !nexuses.isEmpty() ? "no base pays a squadron"
				: guarded ? "every Nexus is reported guarded and no orbit is ours" : null;
		for (MarketAPI w : nexuses) {
			float fp = squadronFP(w);
			if (fp >= Float.MAX_VALUE) {
				idle = "a squadron buys no day down at " + w.getName();
				continue;
			}
			MarketAPI base = squadronBase(faction, w, fp);
			if (base == null) continue;
			float cost = fuelCost(base, w, fp, stay);
			if (pl.fuelSpent + cost > pl.fuelBudget) {
				idle = "this month's fuel is spent";
				continue;
			}
			float travel = ThreatReach.days(ly(base, w.getStarSystem()));
			ThreatFleetOrders.Order lead = squadron(pl, faction, w, fp, 2f * travel + stay);
			if (lead == null) continue;
			pl.fuelSpent += cost;
			idle = null;
			ThreatIncConfig.log("Play " + pl.id + " STARVE: squadron of " + (int) fp + " FP to " + w.getName() + " from "
					+ base.getName() + ", " + (int) cost + " fuel (" + (int) pl.fuelSpent + " of " + (int) pl.fuelBudget + ")");
			break;
		}
		if (idle != null) ThreatIncConfig.logOnChange("starveIdle:" + pl.id, idle, "Play " + pl.id + " STARVE: no squadron (" + idle + ")");
		if (today < pl.phaseDue) return;
		pl.checks++;
		float seen = ThreatIntel.systemFP(observer, pl.systemId);
		boolean regrowing = pl.seenAtCheck > 0f && seen > 1.1f * pl.seenAtCheck;
		String line = "check " + pl.checks + ": Nexus down " + (int) pl.nexusDownDays + " world-days (streak "
				+ (int) pl.nexusStreak + " d), " + pl.raids + " raids, " + pl.drivenOff + " driven off (" + pl.offInRow
				+ " in a row), swarm seen " + (int) pl.seenAtCheck + " -> " + (int) seen + " FP";
		pl.seenAtCheck = seen;
		if (pl.offInRow >= Math.max(1, ThreatIncConfig.councilStarveAbortRaids())) {
			end(pl, FAILURE, "driven off " + pl.offInRow + " times in a row; " + line, today);
			return;
		}
		if (pl.nexusStreak >= ThreatIncConfig.councilInvadeNexusDays() && !regrowing) {
			ThreatWarCouncil.Council c = ThreatWarCouncil.councilOf(pl.factionId);
			ThreatWarCouncil.Picture p = ThreatWarCouncil.picture(pl.factionId);
			ThreatWarCouncil.Cluster k = p != null ? p.cluster(pl.systemId) : null;
			end(pl, SUCCESS, "starved, invasion next; " + line, today);
			// the invasion follows on: the campaign's own saturation siege is not in its way.
			// If none can start now, the next plan weighs a hammer while the Nexuses stay down
			Play h = k != null ? startHammer(c, faction, p, k, today, random, "invade the starved system", pl.id) : null;
			if (h == null) {
				ThreatIncConfig.log("Play " + pl.id + ": no invasion of " + systemName(pl.systemId)
						+ " can start yet (no base, target or picture)");
			}
			return;
		}
		if (pl.checks >= STARVE_MAX_CHECKS) {
			boolean ok = pl.nexusDownDays >= ThreatIncConfig.councilInvadeNexusDays();
			end(pl, ok ? SUCCESS : FAILURE, "the campaign ran its course; " + line, today);
			return;
		}
		ThreatIncConfig.log("Play " + pl.id + " STARVE " + line);
		// the next month's fuel: a share of the means as they stand now
		ThreatWarCouncil.Picture now = ThreatWarCouncil.picture(pl.factionId);
		pl.fuelTotal += pl.fuelSpent;
		pl.fuelSpent = 0f;
		if (now != null) pl.fuelBudget = share(pl.factionId, ThreatIncConfig.councilStarveShare()) * now.fuel;
		saturate(pl, faction, random);
		pl.phaseDue = today + Math.max(1f, ThreatIncConfig.councilStarveCheckDays()) * ThreatWarCouncil.jitter(random);
	}

	/** The bombing campaign's saturation expedition, once, when the pools pay (section 4.3): it razes, it lands nothing. */
	protected static void saturate(Play pl, FactionAPI faction, Random random) {
		if (pl.sieged) return;
		MarketAPI base = market(pl.baseId);
		StarSystemAPI s = system(pl.systemId);
		if (base == null || s == null || !pl.factionId.equals(base.getFactionId())) return;
		List<MarketAPI> targets = new ArrayList<MarketAPI>();
		for (MarketAPI m : IncursionManager.bombardTargets(s)) {
			if (pl.targetIds.contains(m.getId()) && !IncursionManager.bookedWorlds(own(pl)).contains(m)) targets.add(m);
		}
		if (targets.isEmpty()) return;
		java.util.Set<String> raze = IncursionManager.idsOf(targets);
		float siegeFP = share(pl.factionId, ThreatIncConfig.councilStarveShare())
				* ThreatPosture.siegeCapacityFP(base, s, ThreatSoftening.huntDonors(faction, base));
		List<Integer> sizes = IncursionManager.playSiegeSizes(base, faction, targets, siegeFP, raze);
		if (sizes.isEmpty()) return;
		ThreatPurgeFGI purge = IncursionManager.launchSiegeExpedition(base, faction, s, targets, sizes, false, random,
				0f, raze, pl.id);
		if (purge != null) {
			pl.sieged = true;
			ThreatIncConfig.log("Play " + pl.id + " STARVE: saturation expedition of " + (int) purge.abstractFull()
					+ " FP sails from " + base.getName() + " to raze " + targets.size() + " worlds");
		} else {
			ThreatIncConfig.logOnChange("playSaturate:" + pl.id, "no", "Play " + pl.id
					+ " STARVE: no saturation expedition the pools pay (" + (int) siegeFP + " FP share)");
		}
	}

	// ------------------------------------------------------------------
	// raids (ThreatFleetOrders.endRaid and the destroyed path)
	// ------------------------------------------------------------------

	/** A raid order ended: the play that sent it books it once - driven off (an orbit contested, a third lost, destroyed) or not. */
	public static void raidEnded(ThreatFleetOrders.Order o, String why) {
		if (o == null || o.raidId == null || Global.getSector() == null || plays().isEmpty()) return;
		for (Play pl : new ArrayList<Play>(plays().values())) {
			if (pl.raidIds == null || !pl.raidIds.remove(o.raidId)) continue;
			boolean driven = why != null && (why.startsWith("orbit contested") || why.startsWith("lost ")
					|| why.equals("destroyed"));
			if (driven) {
				pl.drivenOff++;
				pl.offInRow++;
			} else if (o.arrived) {
				pl.offInRow = 0;
			}
			ThreatIncConfig.log("Play " + pl.id + " " + pl.type + ": raid on " + o.targetName + " over (" + why + ")"
					+ (driven ? ", driven off" : ""));
			if (BOMBERS.equals(pl.type)) end(pl, driven ? FAILURE : SUCCESS, "raid over: " + why, ThreatPosture.today());
			return;
		}
	}

	/** Whether the feint's squadron is over its world. */
	protected static boolean feintArrived(Play pl) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (o.raidId != null && o.arrived && pl.raidIds.contains(o.raidId)) return true;
		}
		return false;
	}

	protected static boolean raidAlive(String raidId) {
		if (raidId == null) return false;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (raidId.equals(o.raidId) && o.fleet != null && o.fleet.isAlive()) return true;
		}
		return false;
	}

	/** A squadron (dispatchRaid) of {@code fp} at the world for {@code days}, from the base that reaches it; booked to the play. */
	protected static ThreatFleetOrders.Order squadron(Play pl, FactionAPI faction, MarketAPI world, float fp, float days) {
		if (world == null || world.getPrimaryEntity() == null || fp <= 0f || fp >= Float.MAX_VALUE) return null;
		if (!ThreatIncConfig.supportEnabled() || ThreatFleetOrders.hasRaid(faction.getId(), world.getId())) return null;
		MarketAPI base = squadronBase(faction, world, fp);
		if (base == null) return null;
		ThreatFleetOrders.Order lead = ThreatFleetOrders.dispatchRaid(faction, world, base, fp, Math.max(1f, days), null);
		if (lead == null) return null;
		pl.raidIds.add(lead.raidId);
		pl.raids++;
		return lead;
	}

	/**
	 * The nearest base of the faction that reaches the world and pays a squadron
	 * of {@code fp} whole (a raid pays from one base, with no donors); null when
	 * none does. The nearest base alone (pickBase) is often an empty forward
	 * base (h53b: a bombing campaign sent nothing for two months).
	 */
	protected static MarketAPI squadronBase(FactionAPI faction, MarketAPI world, float fp) {
		if (world == null || world.getLocationInHyperspace() == null || fp >= Float.MAX_VALUE) return null;
		MarketAPI best = null;
		float bestLY = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
			if (m.getStarSystem() == null || !IncursionManager.isBase(m)) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), world.getLocationInHyperspace());
			if (d >= bestLY || d > IncursionManager.expeditionRangeLY(m)) continue;
			if (ThreatFleetOrders.sortieReachFP(m, world.getLocationInHyperspace()) < fp) continue;
			best = m;
			bestLY = d;
		}
		return best;
	}

	/** A squadron's fleet points at the world: the doctrine size, or what a day there needs to buy a day down (its guns: a planet fact). Float.MAX_VALUE when none would. */
	protected static float squadronFP(MarketAPI world) {
		float least = ThreatAttackPlanner.leastForGain(world);
		if (least >= Float.MAX_VALUE) return Float.MAX_VALUE;
		return Math.max(ThreatIncConfig.councilSquadronFP(), least);
	}

	protected static float fuelCost(MarketAPI base, MarketAPI world, float fp, float stay) {
		float[] w = ThreatFleetOrders.sortieWants(base, fp, world.getLocationInHyperspace());
		return w[0] + ThreatGroundFronts.bombardFuelPerDay(fp) * stay;
	}

	// ------------------------------------------------------------------
	// ending
	// ------------------------------------------------------------------

	/** The verdict by the damage done: a world taken or landed on, or a Nexus down councilInvadeNexusDays. */
	protected static void finish(Play pl, float today, String why) {
		pl.landed = Math.max(pl.landed, landings(purgeOf(pl.id)));
		pl.taken = 0;
		for (String id : pl.targetIds) {
			if (!ThreatWarCouncil.isHive(market(id))) pl.taken++;
		}
		boolean ok = pl.taken > 0 || pl.landed > 0 || pl.nexusDownDays >= ThreatIncConfig.councilInvadeNexusDays();
		end(pl, ok ? SUCCESS : FAILURE, why, today);
	}

	protected static void end(Play pl, String outcome, String why, float today) {
		if (!plays().containsKey(pl.id)) return;
		pl.outcome = outcome;
		pl.why = why;
		plays().remove(pl.id);
		ThreatConvoys.clearPlayStaging(pl.id);
		// a muster that never went in goes home, provisions back in full
		for (String f : forcesOf(pl)) {
			if (ThreatSoftening.forceAlive(f) && !ThreatSoftening.engaged(f)) ThreatSoftening.standDownForce(f, "its play is over");
		}
		for (Play j : new ArrayList<Play>(plays().values())) {
			if (JOINT.equals(j.type) && pl.id.equals(j.leadId)) end(j, outcome, "its lead play " + pl.id + " ended", today);
		}
		ThreatIncConfig.log("Play " + pl.id + ": " + outcome + " (" + why + "; " + damage(pl) + ", "
				+ (int) Math.max(0f, today - pl.started) + " d)");
		boolean decisive = SUCCESS.equals(outcome) || FAILURE.equals(outcome);
		// a partner's joint force learns nothing: the lead's plan and strategy were not its own
		if (decisive && !RECON.equals(pl.type) && !JOINT.equals(pl.type)) {
			boolean ok = SUCCESS.equals(outcome);
			ThreatWarCouncil.learn(pl.factionId, pl.type + ":" + pl.targetClass, ok);
			if (!BOMBERS.equals(pl.type)) {
				ThreatWarCouncil.learn(pl.factionId, "strategy:" + pl.strategy, ok);
				ThreatWarCouncil.reviewSoon(pl.factionId, "play " + pl.id + " " + pl.type + " " + outcome);
				if (!ok || pl.taken >= pl.targetIds.size()) ThreatWarCouncil.dropFocus(pl.factionId, pl.systemId);
			}
		}
	}

	protected static String damage(Play pl) {
		StringBuilder b = new StringBuilder();
		b.append("Nexus down ").append((int) pl.nexusDownDays).append(" world-days, orbit held ")
				.append((int) pl.orbitDays).append(", landed ").append(pl.landed).append(", taken ").append(pl.taken);
		if (pl.raids > 0) b.append(", raids ").append(pl.raids).append(" (").append(pl.drivenOff).append(" driven off)");
		if (pl.seenAtStart >= 0f) {
			b.append(", swarm seen ").append((int) pl.seenAtStart).append(" -> ").append((int) Math.max(0f, pl.seenLast));
		}
		if (pl.fuelBudget > 0f) b.append(", fuel burned ").append((int) (pl.fuelTotal + pl.fuelSpent));
		return b.toString();
	}

	protected static void standDownForces(Play pl, String why) {
		for (String f : forcesOf(pl)) ThreatSoftening.standDownForce(f, why);
	}

	protected static void phase(Play pl, String next, String why, float today) {
		String was = pl.phase;
		pl.phase = next;
		pl.phaseStart = today;
		ThreatIncConfig.log("Play " + pl.id + " " + pl.type + " " + pl.factionId + " at " + targetName(pl) + ": "
				+ (was != null ? was : "start") + " -> " + next + " (" + why + ")");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** The play's staging (section 16): the base stocks what the play's own expedition against {@code s} draws, against {@code stageAt}. */
	protected static void stage(Play pl, FactionAPI faction, MarketAPI base, StarSystemAPI s, List<MarketAPI> targets,
			StarSystemAPI stageAt) {
		float siegeFP = share(pl.factionId, ThreatIncConfig.councilHammerShare()) * fundingFP(base, s);
		List<Integer> sizes = IncursionManager.playSiegeSizes(base, faction, targets, siegeFP, null);
		if (sizes.isEmpty()) return;
		float[] wants = IncursionManager.expeditionWants(base, s, targets, sizes, new LinkedHashSet<String>());
		ThreatConvoys.stageForPlay(base, pl.id, stageAt, wants);
	}

	/** Fleet points the base's network could field against the system with its stock and staging horizon's banking (ThreatConvoys.fundingInReach). */
	protected static float fundingFP(MarketAPI base, StarSystemAPI s) {
		float[] have = ThreatConvoys.fundingInReach(base);
		int fuel = ThreatAid.index(Commodities.FUEL), supplies = ThreatAid.index(Commodities.SUPPLIES);
		float fuelPerPoint = ly(base, s) * ThreatIncConfig.expeditionFuelPerPointLY();
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float points = Float.MAX_VALUE;
		if (fuel >= 0 && fuelPerPoint > 0f) points = Math.min(points, have[fuel] / fuelPerPoint);
		if (supplies >= 0 && suppliesPerPoint > 0f) points = Math.min(points, have[supplies] / suppliesPerPoint);
		return points >= Float.MAX_VALUE ? 0f : points * IncursionManager.FP_PER_RESPONSE_DIFFICULTY;
	}

	/** A share of the means, by the faction's personality (shareMult). */
	protected static float share(String fid, float share) {
		return Math.max(0f, share) * ThreatWarCouncil.personality(fid, "shareMult");
	}

	/** The worlds of the ids still the swarm's, not besieged by anyone and not held by another army, in order. */
	protected static List<MarketAPI> liveTargets(FactionAPI faction, List<String> ids) {
		return liveTargets(faction, ids, null);
	}

	/** The plays whose sieges are not in this one's way: itself, and the play it follows on from. */
	protected static List<String> own(Play pl) {
		List<String> out = new ArrayList<String>();
		if (pl == null) return out;
		out.add(pl.id);
		if (pl.fromId != null) out.add(pl.fromId);
		return out;
	}

	/** Hives no other siege has booked (the plays named aside) and no other faction's front stands on. */
	protected static List<MarketAPI> liveTargets(FactionAPI faction, List<String> ids, List<String> ownPlays) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		java.util.Set<MarketAPI> booked = IncursionManager.bookedWorlds(ownPlays);
		for (String id : ids) {
			MarketAPI m = market(id);
			if (!ThreatWarCouncil.isHive(m) || booked.contains(m) || IncursionManager.heldByOtherArmy(faction, m)) continue;
			out.add(m);
		}
		return out;
	}

	/** Whether the cluster has a Nexus and none of them is operational (a planet fact). */
	protected static boolean nexusesDown(ThreatWarCouncil.Cluster k) {
		boolean any = false;
		for (String id : k.hiveIds) {
			MarketAPI m = market(id);
			if (!ThreatWarCouncil.isHive(m) || !m.hasIndustry(ThreatColonyManager.SWARM_NEXUS)) continue;
			if (ThreatColonyManager.hasOperationalNexus(m)) return false;
			any = true;
		}
		return any;
	}

	/** The worlds that are still the swarm's, whoever else besieges them. */
	protected static boolean anyHive(List<String> ids) {
		for (String id : ids) {
			if (ThreatWarCouncil.isHive(market(id))) return true;
		}
		return false;
	}

	/** The cluster's first base that is still the faction's and in the economy (the picture is up to a month old). */
	protected static MarketAPI baseOf(ThreatWarCouncil.Cluster k, String fid) {
		for (MarketAPI m : k.bases) {
			if (m != null && fid.equals(m.getFactionId()) && m.isInEconomy()) return m;
		}
		return null;
	}

	protected static MarketAPI firstHive(Play pl) {
		for (String id : pl.targetIds) {
			MarketAPI m = market(id);
			if (ThreatWarCouncil.isHive(m)) return m;
		}
		return null;
	}

	protected static List<String> ids(List<MarketAPI> worlds) {
		List<String> out = new ArrayList<String>();
		for (MarketAPI m : worlds) out.add(m.getId());
		return out;
	}

	/** The play's own siege expedition, or null. */
	public static ThreatPurgeFGI purgeOf(String playId) {
		if (playId == null) return null;
		List<Object> list = IncursionManager.getPurgeList();
		for (int i = list.size() - 1; i >= 0; i--) {
			Object o = list.get(i);
			if (o instanceof ThreatPurgeFGI && playId.equals(((ThreatPurgeFGI) o).getPlayId())) return (ThreatPurgeFGI) o;
		}
		return null;
	}

	protected static boolean live(ThreatPurgeFGI purge) {
		return purge != null && !purge.isEnded() && !purge.isEnding();
	}

	protected static int landings(ThreatPurgeFGI purge) {
		if (purge == null || purge.siegeActions == null) return 0;
		int n = 0;
		for (ThreatPurgeFGI.SiegeActionRecord r : purge.siegeActions) {
			if (r != null && r.success && "Ground landing".equals(r.action)) n++;
		}
		return n;
	}

	protected static MarketAPI market(String id) {
		return id != null ? Global.getSector().getEconomy().getMarket(id) : null;
	}

	protected static StarSystemAPI system(String id) {
		return id != null ? Global.getSector().getStarSystem(id) : null;
	}

	protected static String systemName(String id) {
		StarSystemAPI s = system(id);
		return s != null ? s.getName() : String.valueOf(id);
	}

	public static String targetName(Play pl) {
		return systemName(pl.systemId) + (pl.feintId != null ? " (feint at " + systemName(pl.feintId) + ")" : "");
	}

	/** The board's name for a system: its own name, as the board's other rows give it. */
	public static String boardName(String id) {
		StarSystemAPI s = system(id);
		return s != null ? s.getBaseName() : String.valueOf(id);
	}

	public static String boardTarget(Play pl) {
		return boardName(pl.systemId) + (pl.feintId != null ? " (feint at " + boardName(pl.feintId) + ")" : "");
	}

	/** The board's name for a play type. */
	public static String typeName(String type) {
		if (HAMMER.equals(type)) return "Hammer";
		if (STARVE.equals(type)) return "Bombing";
		if (FEINT.equals(type)) return "Feint";
		if (RECON.equals(type)) return "Recon";
		if (BOMBERS.equals(type)) return "Bombers";
		if (JOINT.equals(type)) return "Joint strike";
		return String.valueOf(type);
	}

	/** The board's one line on where a play stands. */
	public static String phaseText(Play pl) {
		MarketAPI base = market(pl.baseId);
		String at = base != null ? " at " + base.getName() : "";
		if (SCOUT.equals(pl.phase)) return "scouting";
		if (PREPARE.equals(pl.phase)) return "staging" + at;
		if (MUSTER.equals(pl.phase)) return "mustering" + at;
		if (STRIKE.equals(pl.phase)) return pl.sieged ? "siege sailing" + at : "striking";
		if (EXPLOIT.equals(pl.phase)) return "holding on";
		if (WITHDRAW.equals(pl.phase)) return "withdrawing";
		if (BOMB.equals(pl.phase)) return "bombing, " + Misc.getWithDGS((int) pl.fuelSpent) + " of "
				+ Misc.getWithDGS((int) pl.fuelBudget) + " fuel this month";
		if (WATCH.equals(pl.phase)) return "feinting at " + boardName(pl.feintId);
		if (SORTIE.equals(pl.phase)) return "raiding";
		return String.valueOf(pl.phase);
	}

	/** Days to the play's next check, or -1. */
	public static int daysToNext(Play pl) {
		if (pl.phaseDue == NEVER) return -1;
		return Math.max(0, (int) Math.ceil(pl.phaseDue - ThreatPosture.today()));
	}

	protected static float ly(MarketAPI base, StarSystemAPI s) {
		if (base == null || base.getStarSystem() == null || s == null) return 0f;
		return Misc.getDistanceLY(base.getStarSystem().getLocation(), s.getLocation());
	}

	/** Whether the market is the swarm's (for callers outside the council). */
	protected static boolean threat(MarketAPI m) {
		return m != null && Factions.THREAT.equals(m.getFactionId());
	}
}
