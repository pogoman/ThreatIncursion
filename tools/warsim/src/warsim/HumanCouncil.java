package warsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import threatinc.rules.BattleRules;
import threatinc.rules.CouncilRules;
import threatinc.rules.ReachRules;

/**
 * The war council and its plays (ThreatWarCouncil, ThreatPlays; docs/war-council.md 16), in the
 * mod's order: a monthly picture from the faction's reports and planet facts, the band, the
 * strategy drawn by CouncilRules and held to its review, then one major play at a time on the
 * focus (hammer, feint and strike, starve then invade), recon on a stale picture and bombers of
 * opportunity, each sized as a share of the faction's means and judged by the damage done. The
 * verdicts teach the weights (learn). The council sets the faction's stance (setStance).
 * Not mirrored: the relief itself (it is owed while a Threat front stands), a partner's joint force
 * (inviteJoint), a play's staging and its decoy (stage), the muster at a bearing (a held force
 * waits at its base and sails with the siege).
 */
final class HumanCouncil {
	private HumanCouncil() {}

	static final String[] STRATEGIES = { "HOLD", "STARVE", "ROLLBACK", "DECAPITATE" };
	static final String[] BANDS = { "outmatched", "even", "ahead" };
	static final String RECON = "RECON", HAMMER = "HAMMER", FEINT = "FEINT", STARVE = "STARVE", BOMBERS = "BOMBERS";
	static final String SUCCESS = "success", FAILURE = "failure", NEUTRAL = "neutral";
	/** Round 19: days between a waiting hammer's siege re-tries (warsim_hammerWaitsForSiege). */
	static final int WAIT_RETRY_DAYS = 10;
	static final int NEVER = Integer.MIN_VALUE / 2, HELD = Integer.MAX_VALUE / 2;
	/** ThreatWarCouncil.STRIKE_WINDOW_DAYS, HISTORY; ThreatPlays.MAX_EXTENSIONS, STARVE_MAX_CHECKS, DREW, FRESH_DAYS, RECON_FRESH_DAYS, ALL_IN. */
	static final float STRIKE_WINDOW_DAYS = 90f, DREW = 1.2f, ALL_IN = 0.95f;
	static final int HISTORY = 24, MAX_EXTENSIONS = 2, STARVE_MAX_CHECKS = 6, FRESH_DAYS = 10, RECON_FRESH_DAYS = 2;

	static final class Cluster {
		StarSys sys;
		final List<Hive> hives = new ArrayList<Hive>();
		float weight, ly = Float.MAX_VALUE;
		boolean core, frontier, production, stale, threatens, small;
		final List<World> bases = new ArrayList<World>();

		boolean inReach() { return !bases.isEmpty(); }

		String targetClass() { return production ? "production" : core ? "core" : frontier ? "frontier" : "cluster"; }
	}

	static final class Picture {
		final List<Cluster> clusters = new ArrayList<Cluster>();
		float swarmWeight, ourWeight, allyWeight, ratio, fuel;
		int band, colonies, strikes, fronts, partners;
		boolean pressed, relief;

		Cluster cluster(StarSys sys) {
			for (Cluster k : clusters) if (k.sys == sys) return k;
			return null;
		}
	}

	static final class Council {
		String strategy;
		StarSys focus;
		int assessed = NEVER, reviewDue = NEVER, band = -1, colonies = -1, partners = -1, nextPlay, oppMonth = NEVER;
		String earlyWhy;
		float oppSpent;
		Picture picture;
		final Map<String, Float> learned = new LinkedHashMap<String, Float>();
		final List<float[]> history = new ArrayList<float[]>();
		final Map<StarSys, Integer> reconDay = new IdentityHashMap<StarSys, Integer>();
		final Map<Hive, Integer> repulsed = new IdentityHashMap<Hive, Integer>();
		final List<Play> plays = new ArrayList<Play>();
	}

	static final class Play {
		String id, type, phase, strategy, targetClass;
		Faction f;
		StarSys sys, feint;
		World base;
		List<Hive> targets = new ArrayList<Hive>();
		Play from;
		/** Round 19 (warsim_hammerWaitsForSiege): the day the mustered hunts first waited at the base on an unpaid siege. */
		int waitSince = NEVER;
		int started, musterDay, phaseDue = NEVER, checks, raids, drivenOff, offInRow, extensions, landed, landedAtCheck, taken, feintArrived = NEVER;
		boolean feintDrew;
		float plannedFP, nexusDownDays, nexusStreak, orbitDays, orbitAtCheck, fuelBudget, fuelSpent, fuelTotal;
		float seenAtStart = -1f, seenAtCheck, seenLast = -1f, feintSeen;
		boolean sieged;
		/** Round 18: the play reached STRIKE (its hunts were released, with or without a siege). */
		boolean struck;
		Parcel siege;
		final List<Parcel> forces = new ArrayList<Parcel>(), squadrons = new ArrayList<Parcel>();
	}

	static boolean on(State s) { return s.knobs.b("threatinc_warCouncil", true); }

	static Council council(Faction f) {
		if (!(f.council instanceof Council)) f.council = new Council();
		return (Council) f.council;
	}

	/** HumanSide's hook: a siege a play sent put troops down. */
	static void landed(HumanOrder o) {
		if (o.play instanceof Play) ((Play) o.play).landed++;
	}

	// ---- ThreatWarCouncil.daily ----

	static void daily(State s, Faction f) {
		Council c = council(f);
		boolean due = c.assessed == NEVER || s.day - c.assessed >= Math.max(1, s.knobs.i("threatinc_councilAssessDays"));
		if (due || c.picture == null) c.picture = assess(s, f, c);
		Picture p = c.picture;
		if (due) {
			c.assessed = s.day;
			if (c.colonies >= 0 && p.colonies < c.colonies && c.earlyWhy == null) c.earlyWhy = (c.colonies - p.colonies) + " colony lost";
			if (c.partners >= 0 && c.partners != p.partners && c.earlyWhy == null) c.earlyWhy = "the coalition changed";
			c.colonies = p.colonies;
			c.partners = p.partners;
			c.history.add(new float[] { s.day, p.clusters.size(), 0f, p.swarmWeight, p.ourWeight, f.strikesSuffered, p.colonies });
			while (c.history.size() > HISTORY) c.history.remove(0);
		}
		if (c.strategy == null || s.day >= c.reviewDue || c.earlyWhy != null) review(s, f, c, p);
		plan(s, f, c, p);
		setStance(s, f, c);
		advance(s, f, c);
		reserveSiege(s, f, c);
		if (due) {
			// the mod's monthly "Council f: picture ..." line names the strategy: one a faction a month
			s.count("council.months." + c.strategy, 1);
			s.count("council.band." + BANDS[Math.max(0, p.band)], 1);
			if (p.pressed) s.count("council.pressedMonths", 1);
		}
	}

	/** ThreatWarCouncil.assess: reports, planet facts, our means - never swarm FP. */
	static Picture assess(State s, Faction f, Council c) {
		Picture p = new Picture();
		float half = Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));
		float threatLY = s.knobs.f("threatinc_councilThreatLY");
		List<World> ours = s.worldsOf(f.id);
		List<World> bases = new ArrayList<World>();
		final Map<World, Float> range = new IdentityHashMap<World, Float>();
		for (World w : ours) {
			p.ourWeight += w.size;
			if (w.hasReserve) p.fuel += w.stock[World.FUEL];
			if (w.base && w.hasReserve) {
				bases.add(w);
				range.put(w, HumanPools.rangeLY(s, w));
			}
		}
		p.colonies = ours.size();
		// ThreatCoalition.partners: the factions at war that would help each other (HumanFit.COALITION_PAIRS)
		for (Faction o : s.factions.values()) {
			if (o == f || !o.mobilised || HumanIntel.excluded(s, o.id) || !partner(f.id, o.id)) continue;
			p.partners++;
			for (World w : s.worldsOf(o.id)) p.allyWeight += w.size;
		}
		for (Map.Entry<StarSys, HumanIntel.Report> e : f.reports.entrySet()) {
			final StarSys sys = e.getKey();
			Cluster k = new Cluster();
			k.sys = sys;
			int tier2 = 0;
			List<Hive> rest = new ArrayList<Hive>();
			for (Hive h : s.hivesIn(sys)) {
				if (h.dead) continue;
				k.weight += h.size + 2f * h.tier;
				if (h.tier >= 2) tier2++;
				if (h.forge || h.fuelPlant) k.production = true;
				if (h.nexusUp()) k.hives.add(h);
				else rest.add(h);
			}
			k.hives.addAll(rest);
			if (k.hives.isEmpty()) continue;
			k.core = k.hives.size() >= 3 || tier2 > 0;
			k.frontier = k.hives.size() == 1 && k.hives.get(0).tier == 0;
			k.small = CouncilRules.scale(k.weight) == 0;
			k.stale = s.day - e.getValue().day >= half;
			for (World w : ours) k.ly = Math.min(k.ly, w.sys.ly(sys));
			k.threatens = k.ly <= threatLY;
			// IncursionManager.siegeBasesFor: the faction's bases in expedition range, nearest first
			for (World w : bases) if (w.sys.ly(sys) <= range.get(w)) k.bases.add(w);
			Collections.sort(k.bases, new Comparator<World>() {
				public int compare(World a, World b) { return Float.compare(a.sys.ly(sys), b.sys.ly(sys)); }
			});
			p.clusters.add(k);
			p.swarmWeight += k.weight;
		}
		// pressure: strikes in the window (the lifetime count, diffed against the history), Threat fronts on our worlds
		float before = -1f;
		for (float[] h : c.history) if (h[0] <= s.day - STRIKE_WINDOW_DAYS) before = h[5];
		if (before < 0f) {
			if (!c.history.isEmpty()) before = c.history.get(0)[5];
			else before = f.strikesSuffered > 0 && s.day - f.lastStruckDay < STRIKE_WINDOW_DAYS ? f.strikesSuffered - 1 : f.strikesSuffered;
		}
		p.strikes = Math.max(0, f.strikesSuffered - (int) before);
		for (World w : ours) if (s.swarm.landings.containsKey(w.id)) p.fronts++;
		p.pressed = p.strikes > 0 || p.fronts > 0;
		p.relief = reliefOwed(s, f);
		p.ratio = CouncilRules.ratio(p.ourWeight, p.allyWeight, p.swarmWeight);
		p.band = CouncilRules.band(c.band, p.ratio, s.knobs.f("threatinc_councilEvenRatio"), s.knobs.f("threatinc_councilAheadRatio"),
				s.knobs.f("threatinc_councilBandHysteresis"));
		c.band = p.band;
		return p;
	}

	static boolean partner(String a, String b) {
		for (String[] pair : HumanFit.COALITION_PAIRS) {
			if (pair[0].equals(a) && pair[1].equals(b) || pair[1].equals(a) && pair[0].equals(b)) return true;
		}
		return false;
	}

	/**
	 * ThreatFleetOrders.reliefOwed: a Threat army stands on one of the faction's worlds and a base of the faction
	 * can provision a relief fleet (reliefFleetFP) to it. The simulator sends no relief, so it is owed for as long
	 * as the front stands; in the mod it ends once the guards bound there meet the goal.
	 */
	static boolean reliefOwed(State s, Faction f) {
		float fleet = s.knobs.f("threatinc_reliefFleetFP");
		for (World w : s.worldsOf(f.id)) {
			if (!s.swarm.landings.containsKey(w.id)) continue;
			// warsim_reliefOwedShort (with warsim_reliefToInvaded): owed while the guards there are short of the goal
			// (ThreatFleetOrders.reliefShort); on, the councils sailed 70 sieges on hw4d against the game's 13
			if (s.knobs.b("warsim_reliefToInvaded", false) && s.knobs.b("warsim_reliefOwedShort", false) && w.guardFP >= HumanBases.reliefGoal(s, w)) continue;
			for (World b : s.worldsOf(f.id)) {
				if (b.base && b.hasReserve && HumanPlanner.payableFP(s, b, w.sys) >= fleet) return true;
			}
		}
		return false;
	}

	/** threatinc_councilPersonalities: the faction's figure for the key, its "default" entry's, or 1 (ThreatWarCouncil.personality). */
	static float personality(State s, String fid, String key) {
		Map<String, Object> all = Json.obj(s.knobs.raw("threatinc_councilPersonalities"));
		Object p = all.get(fid);
		if (!(p instanceof Map) || !Json.obj(p).containsKey(key)) p = all.get("default");
		if (!(p instanceof Map) || !Json.obj(p).containsKey(key)) return 1f;
		return Math.max(0f, Json.num(Json.obj(p).get(key), 1f));
	}

	static float learned(Council c, String key) {
		Float w = c.learned.get(key);
		return w != null ? w : 1f;
	}

	static void learn(State s, Council c, String key, boolean ok) {
		if (s.knobs.b("threatinc_councilLearning", true)) c.learned.put(key, CouncilRules.learn(learned(c, key), ok));
	}

	static float jitter(State s) { return CouncilRules.jitter(s.knobs.f("threatinc_councilJitter"), s.rng); }

	static int draw(State s, float[] w) { return CouncilRules.draw(w, s.knobs.f("threatinc_councilTemperature"), s.rng); }

	static int indexOf(String strategy) {
		for (int i = 0; strategy != null && i < STRATEGIES.length; i++) if (STRATEGIES[i].equals(strategy)) return i;
		return -1;
	}

	/** ThreatWarCouncil.scores and review. */
	static void review(State s, Faction f, Council c, Picture p) {
		boolean any = false, frontier = false, core = false, rich = false;
		for (Cluster k : p.clusters) {
			if (!k.inReach()) continue;
			any = true;
			if (k.frontier || k.small) frontier = true;
			if (k.core) core = true;
			if (k.core || k.production) rich = true;
		}
		float[] sc = CouncilRules.scores(any, frontier, core, rich, p.pressed, p.relief, p.band, p.partners > 0);
		for (int i = 0; i < sc.length; i++) {
			sc[i] *= personality(s, f.id, STRATEGIES[i].toLowerCase()) * learned(c, "strategy:" + STRATEGIES[i]);
		}
		float[] w = sc.clone();
		int current = indexOf(c.strategy);
		if (current >= 0) w[current] *= 1f + Math.max(0f, s.knobs.f("threatinc_councilSwitchMargin")) * personality(s, f.id, "switchMult");
		int pick = Math.max(0, draw(s, w));
		String next = STRATEGIES[pick];
		if (!next.equals(c.strategy)) {
			s.log("Council " + f.id + ": strategy " + c.strategy + " -> " + next + " (" + (c.earlyWhy != null ? c.earlyWhy
					: c.strategy == null ? "first council" : "review") + String.format("; hold %.2f, starve %.2f, rollback %.2f, decapitate %.2f; ",
							sc[0], sc[1], sc[2], sc[3]) + BANDS[Math.max(0, p.band)] + String.format(" %.2f)", p.ratio));
			s.count("council.switches", 1);
			c.strategy = next;
			f.strategy = next;
			c.focus = null;
		}
		Cluster focus = p.cluster(c.focus);
		if (focus == null || !fits(next, focus)) c.focus = focus(s, c, p, next);
		c.reviewDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilReviewDays")) * jitter(s));
		c.earlyWhy = null;
	}

	static boolean fits(String strategy, Cluster k) {
		if ("HOLD".equals(strategy)) return k.threatens;
		if (!k.inReach()) return false;
		if ("ROLLBACK".equals(strategy)) return k.frontier || k.small;
		if ("DECAPITATE".equals(strategy)) return k.core;
		return true;
	}

	static StarSys focus(State s, Council c, Picture p, String strategy) {
		List<Cluster> fit = new ArrayList<Cluster>();
		for (Cluster k : p.clusters) if (fits(strategy, k)) fit.add(k);
		if (fit.isEmpty()) return null;
		float[] w = new float[fit.size()];
		for (int i = 0; i < w.length; i++) {
			Cluster k = fit.get(i);
			w[i] = CouncilRules.focusWeight(indexOf(strategy), k.ly, k.weight, k.production, k.core);
		}
		int pick = draw(s, w);
		return pick >= 0 ? fit.get(pick).sys : null;
	}

	/** ThreatWarCouncil.setStance: Hold consolidates, a major play presses, else expand. */
	static void setStance(State s, Faction f, Council c) {
		int next = "HOLD".equals(c.strategy) ? HumanStance.CONSOLIDATE : major(c) != null ? HumanStance.PRESS : HumanStance.EXPAND;
		if (next != f.stance) {
			f.stance = next;
			f.stanceSince = s.day;
		}
		s.count("stanceDays." + HumanStance.NAMES[f.stance], 1);
	}

	// ---- ThreatPlays.plan ----

	static Play major(Council c) {
		for (Play pl : c.plays) if (pl.type == HAMMER || pl.type == STARVE || pl.type == FEINT) return pl;
		return null;
	}

	static boolean running(Council c, String type, StarSys sys) {
		for (Play pl : c.plays) if (pl.type == type && pl.sys == sys) return true;
		return false;
	}

	static int age(State s, Faction f, StarSys sys) {
		HumanIntel.Report r = f.reports.get(sys);
		return r == null ? HELD : s.day - r.day;
	}

	static float seen(Faction f, StarSys sys) {
		HumanIntel.Report r = f.reports.get(sys);
		return r == null ? 0f : r.total();
	}

	/** ThreatIntel.worldFP: the swarms the faction's report shows over that world. */
	static float worldFP(Faction f, Hive h) {
		HumanIntel.Report r = f.reports.get(h.sys);
		Float fp = r == null ? null : r.over.get(h);
		return fp == null ? 0f : fp;
	}

	static boolean scoutInFlight(State s, StarSys sys) {
		for (Parcel p : s.parcels) if (!p.done && p.kind == Parcel.Kind.SCOUT && p.to == sys && !p.threat()) return true;
		return false;
	}

	static boolean reconDue(State s, Council c, StarSys sys) {
		Integer last = c.reconDay.get(sys);
		return last == null || s.day - last >= Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));
	}

	static float share(State s, Faction f, String knob) {
		return Math.max(0f, s.knobs.f(knob)) * personality(s, f.id, "shareMult");
	}

	static void plan(State s, Faction f, Council c, Picture p) {
		if (c.strategy == null || p == null) return;
		// relief before offensives: no new play while it is owed
		if (reliefOwed(s, f)) {
			s.count("council.reliefDays", 1);
			return;
		}
		opportunity(s, f, c, p);
		float half = Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));
		// warsim_councilAlwaysSiege (round 13 trial 1, war-council-runs.md 5 A): the strategy picks where, not whether -
		// Hold sieges the hive that threatens it, Starve runs a hammer beside its campaign; a faction always has a
		// hammer running when its means pay one
		boolean always = s.knobs.b("warsim_councilAlwaysSiege", false);
		if ("HOLD".equals(c.strategy)) {
			// Hold: recon on the hives that threaten us, one a day; no invasions
			for (Cluster k : p.clusters) {
				if (!k.threatens || age(s, f, k.sys) < half) continue;
				if (running(c, RECON, k.sys) || scoutInFlight(s, k.sys) || !reconDue(s, c, k.sys)) continue;
				if (startRecon(s, f, c, k, "Hold: it threatens us") != null) break;
			}
			if (!always) return;
		}
		// ThreatPlays.majorLimit (the mod since bc623ed, from round 13's option B): one major play per councilMajorPlayFP of
		// the faction's siege capacity, at least one; warsim_councilMajorPlays raises the floor for an experiment
		int majors = 0, hammers = 0, starves = 0;
		boolean atFocus = false;
		for (Play pl : c.plays) {
			if (pl.type != HAMMER && pl.type != STARVE && pl.type != FEINT) continue;
			majors++;
			if (pl.type == STARVE) starves++; else hammers++;
			if (pl.sys == c.focus) atFocus = true;
		}
		int limit = Math.max(1, (int) s.knobs.f("warsim_councilMajorPlays", 1f));
		float perFP = s.knobs.f("threatinc_councilMajorPlayFP", 0f);
		if (perFP > 0f) limit = Math.max(limit, (int) (siegeCapacity(s, f, p) / perFP));
		s.count("council.majorLimitDays", limit);
		boolean starveOnly = false;
		if (always) {
			// the hammer slots count hammers and feints; one starve campaign may run beside them
			if (hammers >= limit) {
				if (!"STARVE".equals(c.strategy) || starves > 0) return;
				starveOnly = true;
			}
		} else if (majors >= limit) return;
		Cluster focus = atFocus ? null : p.cluster(c.focus);
		if (atFocus) {
			// the next play goes elsewhere: a few draws for a focus no play is running at
			for (int i = 0; i < 5 && focus == null; i++) {
				Cluster k = p.cluster(focus(s, c, p, c.strategy));
				boolean busy = k == null;
				for (Play pl : c.plays) if (k != null && pl.sys == k.sys) busy = true;
				if (!busy) focus = k;
			}
			if (focus == null) return;
		} else if (focus == null || !fits(c.strategy, focus)) {
			c.focus = focus(s, c, p, c.strategy);
			focus = p.cluster(c.focus);
			if (focus == null) return;
		}
		if (running(c, RECON, focus.sys)) return;
		// a big play waits for a fresh picture; after one recon that brought none back it goes on the picture it has
		if (age(s, f, focus.sys) >= half && reconDue(s, c, focus.sys)) {
			if (scoutInFlight(s, focus.sys)) return;
			if (startRecon(s, f, c, focus, "the picture is old") != null) return;
		}
		String cls = focus.targetClass();
		Object[] feint = feintPlan(s, f, p, focus);
		String[] types;
		float[] w;
		if (always) {
			// every strategy sieges: the hammer family fills the slot; the starve campaign when only that is open
			types = starveOnly ? new String[] { STARVE } : new String[] { HAMMER, FEINT };
			w = starveOnly ? new float[] { 1f } : new float[] { 1.5f, feint != null ? 0.5f : 0f };
		} else if ("STARVE".equals(c.strategy)) {
			types = new String[] { STARVE, HAMMER };
			w = new float[] { 1f, nexusesDown(focus) ? 2f : 0f };
		} else if ("ROLLBACK".equals(c.strategy)) {
			types = new String[] { HAMMER, FEINT };
			w = new float[] { 1f, feint != null ? 1f : 0f };
		} else {
			types = new String[] { HAMMER, FEINT };
			w = new float[] { 1.5f, feint != null ? 0.5f : 0f };
		}
		for (int i = 0; i < w.length; i++) w[i] *= personality(s, f.id, types[i].toLowerCase()) * learned(c, types[i] + ":" + cls);
		int pick = draw(s, w);
		if (pick < 0) return;
		Play started = null;
		String why = c.strategy + " on " + focus.sys;
		if (types[pick] == STARVE) started = startStarve(s, f, c, p, focus, why);
		else if (types[pick] == FEINT) started = startFeint(s, f, c, focus, (Cluster) feint[0], (World) feint[1], why);
		if (started == null && types[pick] != STARVE) started = startHammer(s, f, c, focus, why, null);
		if (started == null) {
			s.count("council.noStart." + types[pick], 1);
			// another cluster tomorrow, not a wait until the review
			if (!atFocus) c.focus = null;
		}
	}

	static boolean nexusesDown(Cluster k) {
		boolean any = false;
		for (Hive h : k.hives) {
			if (h.dead || !h.nexus) continue;
			if (h.nexusUp()) return false;
			any = true;
		}
		return any;
	}

	/** ThreatPlays.liveTargets: hives no other siege has booked (this play's and the one it follows aside) and no other army's front stands on. */
	static List<Hive> liveTargets(State s, Faction f, List<Hive> hives, Play own) {
		List<Hive> out = new ArrayList<Hive>();
		for (Hive h : hives) {
			if (h.dead || (h.front != null && !f.id.equals(h.front.faction))) continue;
			boolean booked = false;
			for (Parcel p : s.parcels) {
				if (p.done || p.threat() || !(p.order instanceof HumanOrder)) continue;
				HumanOrder o = (HumanOrder) p.order;
				if (o.returning || o.target != h) continue;
				Parcel.Kind k = p.kind == Parcel.Kind.MUSTER ? o.sailAs : p.kind;
				if (k != Parcel.Kind.SIEGE && k != Parcel.Kind.SATURATION) continue;
				if (own != null && o.play != null && (o.play == own || o.play == own.from)) continue;
				booked = true;
			}
			if (!booked) out.add(h);
		}
		return out;
	}

	static Play newPlay(State s, Faction f, Council c, String type, Cluster k) {
		Play pl = new Play();
		pl.id = f.id + "#" + (++c.nextPlay);
		pl.type = type;
		pl.f = f;
		pl.sys = k.sys;
		pl.targetClass = k.targetClass();
		pl.strategy = c.strategy;
		pl.started = s.day;
		pl.targets.addAll(k.hives);
		c.plays.add(pl);
		return pl;
	}

	static void cancel(Council c, Play pl) {
		c.plays.remove(pl);
		c.nextPlay--;
	}

	static void phase(State s, Play pl, String next, String why) {
		s.log("Play " + pl.id + " " + pl.type + " " + pl.f.id + " at " + pl.sys + ": " + (pl.phase != null ? pl.phase : "start")
				+ " -> " + next + " (" + why + ")");
		if (pl.phase == null) s.count("plays." + pl.type, 1);
		// check reads these against the game's "Play .. TYPE ..: muster -> strike" lines
		s.count("phase." + pl.type + "." + next.toLowerCase(java.util.Locale.ROOT), 1);
		pl.phase = next;
	}

	static Hive firstLive(Play pl) {
		for (Hive h : pl.targets) if (!h.dead) return h;
		return null;
	}

	static Play startRecon(State s, Faction f, Council c, Cluster k, String why) {
		Play pl = newPlay(s, f, c, RECON, k);
		if (!reconInForce(s, f, c, pl, "SCOUT", why)) {
			cancel(c, pl);
			return null;
		}
		return pl;
	}

	/**
	 * The recon in force: a scout and one probing raid at the doctrine size, paid from the month's opportunity share;
	 * the play's next phase is due when they could have reported. False when neither could go.
	 */
	static boolean reconInForce(State s, Faction f, Council c, Play pl, String next, String why) {
		World base = HumanPools.nearestBase(s, f.id, pl.sys, false);
		int travel = base != null ? State.travelDays(base.sys.ly(pl.sys)) : 30;
		boolean scout = !scoutInFlight(s, pl.sys) && HumanIntel.scout(s, f, pl.sys, true);
		Hive world = firstLive(pl);
		boolean probe = false;
		if (world != null) {
			float fp = s.knobs.f("threatinc_councilSquadronFP");
			World from = squadronBase(s, f, world, fp);
			float cost = from != null ? fuelCost(s, from, world, fp, 5f) : Float.MAX_VALUE;
			if (cost <= oppLeft(s, f, c) && squadron(s, pl, world, fp, 5f, 0) != null) {
				c.oppSpent += cost;
				probe = true;
			}
		}
		if (!scout && !probe) return false;
		c.reconDay.put(pl.sys, s.day);
		pl.phaseDue = s.day + travel + 15;
		phase(s, pl, next, why + (scout ? "; scout sent" : "") + (probe ? "; probing raid" : ""));
		return true;
	}

	/**
	 * warsim_councilPlannerSizing (the user's decision of 2026-10-02, default on): a play's siege is sized as the
	 * planner sizes one, on the faction's report of the target (HumanPlanner.size with no play share), not as a
	 * share of the means; a play with no report of its system starts with the recon in force and sizes when the
	 * report arrives.
	 */
	static boolean plannerSizing(State s) { return s.knobs.b("warsim_councilPlannerSizing", true); }

	/** Under planner sizing, a play without a report of its system goes to RECON first; true when it did. */
	static boolean reconFirst(State s, Council c, Play pl, String why) {
		if (!plannerSizing(s) || pl.f.reports.get(pl.sys) != null || "RECON".equals(pl.phase)) return false;
		if (!reconInForce(s, pl.f, c, pl, "RECON", why + "; no report of " + pl.sys)) return false;
		s.count("playsReconFirst", 1);
		return true;
	}

	static void reconCheck(State s, Council c, Play pl) {
		if (pl.f.reports.get(pl.sys) != null) {
			pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilPrepareDays")) * jitter(s));
			phase(s, pl, "PREPARE", "the report is in; stages at " + pl.base.name);
		} else if (s.day >= pl.phaseDue) {
			standDownForces(s, pl);
			end(s, c, pl, NEUTRAL, "no report of its system by its day");
		}
	}

	static World baseOf(Cluster k, Faction f) {
		for (World w : k.bases) if (!w.lost && f.id.equals(w.faction)) return w;
		return null;
	}

	static Play startHammer(State s, Faction f, Council c, Cluster k, String why, Play from) {
		World base = baseOf(k, f);
		if (base == null) return null;
		Play pl = newPlay(s, f, c, HAMMER, k);
		pl.from = from;
		pl.targets = liveTargets(s, f, k.hives, pl);
		if (pl.targets.isEmpty()) {
			cancel(c, pl);
			return null;
		}
		pl.base = base;
		if (reconFirst(s, c, pl, why)) return pl;
		float half = Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));
		boolean scout = age(s, f, k.sys) >= 0.5f * half && !scoutInFlight(s, k.sys) && HumanIntel.scout(s, f, k.sys, true);
		pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilPrepareDays")) * jitter(s));
		phase(s, pl, "PREPARE", why + "; stages at " + base.name + (scout ? "; scout sent" : ""));
		return pl;
	}

	/**
	 * ThreatPlays.pausable (the mod since bc623ed): relief owed holds only a play whose forces are not yet under way -
	 * PREPARE, a MUSTER under councilMusterFloor x its planned FP, a campaign or watch with no squadron out and no
	 * siege sailed. A strike, exploit or withdrawal runs on; recon is never held.
	 */
	static boolean pausable(State s, Play pl) {
		String ph = pl.phase;
		if ("PREPARE".equals(ph)) return true;
		if ("MUSTER".equals(ph)) {
			float mustered = 0f;
			for (Parcel p : pl.forces) if (live(p)) mustered += p.fp;
			return pl.plannedFP <= 0f || mustered < Math.max(0f, s.knobs.f("threatinc_councilMusterFloor")) * floorBase(s, pl);
		}
		if ("BOMB".equals(ph) || "WATCH".equals(ph)) {
			if (pl.sieged) return false;
			for (Parcel p : pl.squadrons) if (live(p)) return false;
			return true;
		}
		return false;
	}

	/** ThreatPlays.siegeCapacityFP: each distinct base of the picture's clusters that is the faction's, against its nearest cluster. */
	static float siegeCapacity(State s, Faction f, Picture p) {
		float sum = 0f;
		java.util.Set<World> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<World, Boolean>());
		for (Cluster k : p.clusters) {
			for (World w : k.bases) {
				if (w == null || w.lost || !f.id.equals(w.faction) || !seen.add(w)) continue;
				Cluster near = null;
				for (Cluster o : p.clusters) if (near == null || w.sys.ly(o.sys) < w.sys.ly(near.sys)) near = o;
				if (near != null) sum += capacityFP(s, w, near.sys);
			}
		}
		return sum;
	}

	/** Round 14 trial a (warsim_siegeFirstCall): true while the faction has a hammer or feint staging or mustering - its siege has first call on the pools. */
	static boolean siegeStaging(State s, Faction f) {
		if (!s.knobs.b("warsim_siegeFirstCall", false) || !on(s)) return false;
		for (Play pl : council(f).plays) {
			if ((pl.type == HAMMER || pl.type == FEINT) && ("RECON".equals(pl.phase) || "PREPARE".equals(pl.phase) || "MUSTER".equals(pl.phase))) return true;
		}
		return false;
	}

	/**
	 * Round 15 trials a and e: what the faction's staging hammers' sieges want today. For each hammer or feint in PREPARE
	 * or MUSTER the first live world's siege is sized as strike will size it (planner sizing on the faction's report); the
	 * supplies wanted sum to Faction.siegeWant and any unaffordable one sets Faction.siegeUnpaid. With
	 * warsim_siegeReserve the want is spread over the faction's depots by their callable supplies (World.siegeReserve),
	 * which HumanPools.reserve keeps from the forward-base line.
	 */
	static void reserveSiege(State s, Faction f, Council c) {
		boolean reserve = s.knobs.b("warsim_siegeReserve", false), wait = s.knobs.b("warsim_linkWaitsForSiege", false);
		f.siegeWant = 0f;
		f.siegeUnpaid = false;
		List<World> depots = s.worldsOf(f.id);
		if (reserve) for (World w : depots) w.siegeReserve = 0f;
		if (!reserve && !wait) return;
		for (Play pl : c.plays) {
			if ((pl.type != HAMMER && pl.type != FEINT) || !("PREPARE".equals(pl.phase) || "MUSTER".equals(pl.phase))) continue;
			if (pl.base == null || pl.base.lost || f.reports.get(pl.sys) == null) continue;
			List<Hive> targets = liveTargets(s, f, pl.targets, pl);
			if (targets.isEmpty()) continue;
			HumanPlanner.Option o = plannerSizing(s) ? HumanPlanner.size(s, f, targets.get(0), pl.base)
					: HumanPlanner.size(s, f, targets.get(0), pl.base, share(s, f, "threatinc_councilHammerShare") * capacityFP(s, pl.base, pl.sys));
			f.siegeWant += o.wants[World.SUPPLIES];
			if (!o.affordable) f.siegeUnpaid = true;
		}
		if (f.siegeWant <= 0f) return;
		s.count(f.siegeUnpaid ? "siegeWant.unpaidDays" : "siegeWant.paidDays", 1);
		if (!reserve) return;
		float callable = 0f;
		for (World w : depots) if (w.hasReserve) callable += HumanPools.available(s, w, World.SUPPLIES);
		if (callable <= 0f) return;
		for (World w : depots) if (w.hasReserve) w.siegeReserve = f.siegeWant * HumanPools.available(s, w, World.SUPPLIES) / callable;
		s.count("siegeReserveDays", 1);
	}

	/** Round 15 trial d (warsim_strategyReserve): the share of callable supplies the council keeps for sieges and saturation, by strategy. */
	static float reserveShare(State s, String faction) {
		Faction f = s.faction(faction);
		if (f == null || !on(s) || !(f.council instanceof Council)) return 0f;
		String st = ((Council) f.council).strategy;
		if ("DECAPITATE".equals(st)) return 0.5f;
		if ("STARVE".equals(st) || "ROLLBACK".equals(st)) return 1f / 3f;
		return 0f;
	}

	/** ThreatPosture.siegeCapacityFP: what the base and its donors could send against the system at the siege's rates per point. */
	static float capacityFP(State s, World base, StarSys sys) {
		float points = ReachRules.payablePoints(HumanPools.payable(s, base, World.FUEL, true), HumanPools.payable(s, base, World.SUPPLIES, true),
				base.sys.ly(sys) * s.knobs.f("threatinc_expeditionFuelPerPointLY"), s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		return points >= Float.MAX_VALUE ? 0f : points * ReachRules.FP_PER_POINT;
	}

	static Play startStarve(State s, Faction f, Council c, Picture p, Cluster k, String why) {
		// richestBase: the base whose pools field the largest siege
		World base = null;
		float most = -1f;
		for (World w : k.bases) {
			if (w.lost || !f.id.equals(w.faction)) continue;
			float cap = capacityFP(s, w, k.sys);
			if (cap > most) { most = cap; base = w; }
		}
		if (base == null) return null;
		Play pl = newPlay(s, f, c, STARVE, k);
		pl.targets = liveTargets(s, f, k.hives, null);
		if (pl.targets.isEmpty()) {
			cancel(c, pl);
			return null;
		}
		pl.base = base;
		pl.fuelBudget = share(s, f, "threatinc_councilStarveShare") * p.fuel;
		pl.seenAtStart = pl.seenAtCheck = seen(f, k.sys);
		pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilStarveCheckDays")) * jitter(s));
		saturate(s, pl);
		// a campaign that can pay neither a squadron nor a saturation expedition is not started
		if (!pl.sieged && cheapestSquadron(s, pl) > pl.fuelBudget) {
			cancel(c, pl);
			return null;
		}
		phase(s, pl, "BOMB", why + "; " + (int) pl.fuelBudget + " fuel a month to burn" + (pl.sieged ? "; a saturation expedition sails" : ""));
		return pl;
	}

	/** {A's cluster, the striking base}: A the nearest known system within councilThreatLY of B a base reaches, a small one first. */
	static Object[] feintPlan(State s, Faction f, Picture p, Cluster b) {
		Cluster a = null;
		float best = 0f;
		for (Cluster k : p.clusters) {
			if (k == b || !k.inReach()) continue;
			float d = k.sys.ly(b.sys);
			if (d > s.knobs.f("threatinc_councilThreatLY")) continue;
			float score = (k.small ? 2f : 1f) / (1f + d);
			if (score > best) { best = score; a = k; }
		}
		if (a == null) return null;
		World base = null;
		for (World w : b.bases) {
			if (w.lost || !f.id.equals(w.faction)) continue;
			if (State.travelDays(w.sys.ly(b.sys)) > s.knobs.f("threatinc_councilStrikeMaxDays")) continue;
			if (base == null || w.sys.ly(b.sys) < base.sys.ly(b.sys)) base = w;
		}
		return base != null ? new Object[] { a, base } : null;
	}

	static Play startFeint(State s, Faction f, Council c, Cluster b, Cluster a, World base, String why) {
		Hive aim = null;
		for (Hive h : liveTargets(s, f, a.hives, null)) if (aim == null || h.size < aim.size) aim = h;
		if (aim == null) return null;
		Play pl = newPlay(s, f, c, FEINT, b);
		pl.targets = liveTargets(s, f, b.hives, pl);
		if (pl.targets.isEmpty()) {
			cancel(c, pl);
			return null;
		}
		pl.feint = a.sys;
		pl.base = base;
		World from = HumanPools.nearestBase(s, f.id, a.sys, false);
		float reach = from != null ? HumanPlanner.payableFP(s, from, a.sys) : 0f;
		float fp = Math.max(s.knobs.f("threatinc_councilSquadronFP"), share(s, f, "threatinc_councilFeintShare") * reach);
		int watch = (int) (Math.max(1f, s.knobs.f("threatinc_councilFeintWatchDays")) * jitter(s));
		Parcel lead = squadron(s, pl, aim, fp, watch + 10f, 0);
		if (lead == null) {
			cancel(c, pl);
			return null;
		}
		pl.feintSeen = seen(f, a.sys);
		pl.phaseDue = lead.arriveDay + watch;
		phase(s, pl, "WATCH", why + "; feint at " + aim.name + " in " + a.sys + ", " + (int) fp + " FP; strikes from " + base.name);
		return pl;
	}

	// ---- squadrons ----

	/** ThreatPlays.bombable: the report shows no swarm over the world, or our flotilla holds the orbit. */
	static boolean bombable(State s, Faction f, Hive h) {
		if (worldFP(f, h) <= 0f) return true;
		float ours = 0f;
		for (Parcel p : s.parcels) if (!p.done && p.holding && p.to == h.sys && p.owner.equals(f.id) && p.kind != Parcel.Kind.MUSTER) ours += p.fp;
		return ours > 0f && !BattleRules.orbitContested(HumanSiege.enemyAt(s, h), ours, s.knobs.f("threatinc_orbitContestFraction"));
	}

	/** ThreatPlays.squadronFP: the doctrine size, or what buys a day down. */
	static float squadronFP(State s, Hive h) {
		float least = HumanSiege.leastForGain(s, h);
		return least >= Float.MAX_VALUE ? Float.MAX_VALUE : Math.max(s.knobs.f("threatinc_councilSquadronFP"), least);
	}

	/** ThreatPlays.squadronBase: the nearest base in range that pays the squadron whole. */
	static World squadronBase(State s, Faction f, Hive h, float fp) {
		if (fp >= Float.MAX_VALUE) return null;
		World best = null;
		for (World w : s.worldsOf(f.id)) {
			if (!w.base || !w.hasReserve) continue;
			float d = w.sys.ly(h.sys);
			if (best != null && d >= best.sys.ly(h.sys)) continue;
			if (d > HumanPools.rangeLY(s, w) || HumanPlanner.payableFP(s, w, h.sys) < fp) continue;
			best = w;
		}
		return best;
	}

	/** ThreatPlays.fuelCost: the sortie's passage and the ordnance of its stay. */
	static float fuelCost(State s, World base, Hive h, float fp, float stay) {
		return ReachRules.voyageCost(fp, 2f * base.sys.ly(h.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"))[0] + BattleRules.bombardFuelPerDay(fp, s.knobs.f("threatinc_bombardFuelPerFPDay")) * stay;
	}

	static float cheapestSquadron(State s, Play pl) {
		float stay = Math.max(1f, s.knobs.f("threatinc_councilStarveCheckDays")), least = Float.MAX_VALUE;
		for (Hive h : pl.targets) {
			if (h.dead || !h.nexusUp() || !bombable(s, pl.f, h)) continue;
			float fp = squadronFP(s, h);
			World base = squadronBase(s, pl.f, h, fp);
			if (base != null) least = Math.min(least, fuelCost(s, base, h, fp, stay));
		}
		return least;
	}

	/** ThreatPlays.squadron: a raid (ThreatFleetOrders.dispatchRaid) of fp at the world for `stay` days, booked to the play. */
	static Parcel squadron(State s, Play pl, Hive h, float fp, float stay, int delay) {
		if (h == null || h.dead || fp <= 0f || fp >= Float.MAX_VALUE) return null;
		if (HumanPlanner.booked(s, pl.f, h, Parcel.Kind.SQUADRON)) return null;
		World base = squadronBase(s, pl.f, h, fp);
		if (base == null) return null;
		fp = HumanPlanner.roundUp(fp);
		float[] cost = ReachRules.voyageCost(fp, 2f * base.sys.ly(h.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		float ordnance = stay * BattleRules.bombardFuelPerDay(fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		if (!HumanPools.pay(s, base, new float[] { 0f, 0f, cost[0] + ordnance, cost[1] }, false, "squadron")) return null;
		Parcel p = s.send(pl.f.id, Parcel.Kind.SQUADRON, base.sys, h.sys, fp, delay);
		p.targetId = h.id;
		p.fuel = ordnance;
		HumanOrder o = new HumanOrder();
		o.home = base;
		o.target = h;
		o.trust = 1f;
		o.deposit = cost[1];
		o.play = pl;
		o.stayDays = Math.max(1, (int) stay);
		p.order = o;
		pl.squadrons.add(p);
		pl.raids++;
		s.count("squadronsSailed", 1);
		s.count("playSquadrons", 1);
		return p;
	}

	/** ThreatPlays.raidEnded: a squadron home or gone is booked once - driven off (orbit contested, called off, a third lost, destroyed) or not. */
	static int raidsEnded(State s, Play pl) {
		int ended = 0;
		for (Parcel p : new ArrayList<Parcel>(pl.squadrons)) {
			HumanOrder o = (HumanOrder) p.order;
			if (!p.done && !o.returning) continue;
			pl.squadrons.remove(p);
			ended++;
			boolean driven = o.drivenOff || p.fp < BattleRules.raidLossLine(p.fp0, s.knobs.f("threatinc_raidLossFraction"));
			if (driven) {
				pl.drivenOff++;
				pl.offInRow++;
				s.count("playSquadronsDrivenOff", 1);
			} else if (o.arrivedDay >= 0) {
				pl.offInRow = 0;
			}
		}
		return ended;
	}

	static float oppLeft(State s, Faction f, Council c) {
		if (c.oppMonth == NEVER || s.day - c.oppMonth >= 30) {
			c.oppMonth = s.day;
			c.oppSpent = 0f;
		}
		if (c.picture == null) return 0f;
		return share(s, f, "threatinc_councilOpportunityShare") * personality(s, f.id, "bombers") * c.picture.fuel - c.oppSpent;
	}

	/** ThreatPlays.opportunity: a squadron at a hive with a Nexus the fresh report shows unguarded, within the month's fuel share. */
	static void opportunity(State s, Faction f, Council c, Picture p) {
		if ("DECAPITATE".equals(c.strategy)) return;
		float budget = oppLeft(s, f, c);
		if (budget <= 0f) return;
		List<Hive> aimed = new ArrayList<Hive>();
		for (Play pl : c.plays) aimed.addAll(pl.targets);
		List<Hive> cand = new ArrayList<Hive>();
		List<Float> w = new ArrayList<Float>();
		float half = Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));
		for (Cluster k : p.clusters) {
			if ("HOLD".equals(c.strategy) && !k.threatens) continue;
			if (!k.inReach() || age(s, f, k.sys) > FRESH_DAYS) continue;
			for (Hive h : k.hives) {
				if (h.dead || aimed.contains(h) || worldFP(f, h) > 0f || !h.nexusUp()) continue;
				if (HumanPlanner.booked(s, f, h, Parcel.Kind.SQUADRON)) continue;
				// a world that drove a squadron off rests a report half-life (REPULSED_FLAG)
				Integer off = c.repulsed.get(h);
				if (off != null && s.day - off < half) continue;
				cand.add(h);
				w.add(h.size * learned(c, BOMBERS + ":" + k.targetClass()));
			}
		}
		if (cand.isEmpty()) return;
		float[] arr = new float[w.size()];
		for (int i = 0; i < arr.length; i++) arr[i] = w.get(i);
		int pick = draw(s, arr);
		if (pick < 0) return;
		Hive world = cand.get(pick);
		float fp = squadronFP(s, world);
		World base = squadronBase(s, f, world, fp);
		if (base == null) return;
		float stay = Math.max(1f, s.knobs.f("threatinc_councilStarveCheckDays"));
		float cost = fuelCost(s, base, world, fp, stay);
		if (cost > budget) return;
		Cluster k = p.cluster(world.sys);
		Play pl = newPlay(s, f, c, BOMBERS, k);
		pl.targets = new ArrayList<Hive>();
		pl.targets.add(world);
		if (squadron(s, pl, world, fp, stay, 0) == null) {
			cancel(c, pl);
			return;
		}
		c.oppSpent += cost;
		phase(s, pl, "SORTIE", "bombers of opportunity at " + world.name + ", " + (int) fp + " FP from " + base.name + ", " + (int) cost + " fuel");
	}

	// ---- ThreatPlays.advance ----

	static boolean live(Parcel p) {
		return p != null && !p.done && !((HumanOrder) p.order).returning;
	}

	static boolean anyForceAlive(Play pl) {
		for (Parcel p : pl.forces) if (live(p)) return true;
		return false;
	}

	static boolean anyAlive(List<Hive> hives) {
		for (Hive h : hives) if (!h.dead) return true;
		return false;
	}

	static void advance(State s, Faction f, Council c) {
		boolean relief = reliefOwed(s, f);
		for (Play pl : new ArrayList<Play>(c.plays)) {
			if (!c.plays.contains(pl)) continue;
			sample(s, pl);
			int ended = raidsEnded(s, pl);
			// relief before offensives: the next phase waits, the play is paused, not cancelled (ThreatPlays.pausable)
			String ph = pl.phase;
			if (relief && pl.type != RECON && pausable(s, pl)) {
				if (pl.phaseDue != NEVER) pl.phaseDue++;
				s.count("council.heldPlayDays", 1);
				continue;
			}
			if (pl.type == RECON) {
				if (s.day > pl.started + 1 && age(s, f, pl.sys) < RECON_FRESH_DAYS) end(s, c, pl, NEUTRAL, "the picture is fresh");
				else if (s.day >= pl.phaseDue) end(s, c, pl, NEUTRAL, "no fresh report by its day");
			} else if (pl.type == BOMBERS) {
				if (ended > 0) end(s, c, pl, pl.drivenOff > 0 ? FAILURE : SUCCESS, "raid over");
				else if (pl.squadrons.isEmpty()) end(s, c, pl, NEUTRAL, "its squadron is gone");
			} else if (pl.type == STARVE) {
				advanceStarve(s, c, pl);
			} else {
				advanceStrike(s, c, pl);
			}
		}
	}

	/** ThreatPlays.sample: the day's damage at the target - Nexus-days down, orbit-days held, the swarms its reports show. */
	static void sample(State s, Play pl) {
		String ph = pl.phase;
		if (!("STRIKE".equals(ph) || "EXPLOIT".equals(ph) || "WITHDRAW".equals(ph) || "BOMB".equals(ph))) return;
		int down = 0;
		float ours = 0f;
		for (Parcel p : s.parcels) {
			if (!p.done && p.holding && p.to == pl.sys && p.owner.equals(pl.f.id) && p.kind != Parcel.Kind.MUSTER) ours += p.fp;
		}
		for (Hive h : pl.targets) {
			if (h.dead) continue;
			if (h.nexus && h.nexusDown > 0f) down++;
			if (ours > 0f && !BattleRules.orbitContested(HumanSiege.enemyAt(s, h), ours, s.knobs.f("threatinc_orbitContestFraction"))) pl.orbitDays += 1f;
		}
		pl.nexusDownDays += down;
		pl.nexusStreak = down > 0 ? pl.nexusStreak + 1f : 0f;
		pl.seenLast = seen(pl.f, pl.sys);
	}

	static void advanceStrike(State s, Council c, Play pl) {
		String ph = pl.phase;
		if ("RECON".equals(ph)) {
			reconCheck(s, c, pl);
		} else if ("PREPARE".equals(ph)) {
			if (s.day >= pl.phaseDue) toMuster(s, c, pl);
		} else if ("MUSTER".equals(ph)) {
			musterCheck(s, c, pl);
		} else if ("WATCH".equals(ph)) {
			watchCheck(s, c, pl);
		} else if ("STRIKE".equals(ph)) {
			strikeCheck(s, c, pl);
		} else if ("EXPLOIT".equals(ph)) {
			exploitCheck(s, c, pl);
		} else if ("WITHDRAW".equals(ph)) {
			if (!live(pl.siege)) finish(s, c, pl, "its siege is over");
			else if (s.day >= pl.phaseDue) finish(s, c, pl, "its siege fights on alone");
		}
	}

	/** ThreatSoftening.sendPlay: the play's hunting force at share x what the base pays for, held at its muster until released. */
	static Parcel force(State s, Play pl, boolean hold) {
		float fp = share(s, pl.f, "threatinc_councilHammerShare") * HumanPlanner.payableFP(s, pl.base, pl.sys);
		fp = (float) Math.floor(fp / ReachRules.FP_PER_POINT) * ReachRules.FP_PER_POINT;
		Hive target = firstLive(pl);
		if (fp < 25f || target == null) return null;
		float[] cost = ReachRules.voyageCost(fp, 2f * pl.base.sys.ly(pl.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		if (!HumanPools.pay(s, pl.base, new float[] { 0f, 0f, cost[0], cost[1] }, false, "huntForce")) return null;
		Parcel p = HumanPlanner.muster(s, pl.f, pl.base, target, fp, hold ? HELD - s.day : 0, Parcel.Kind.HUNT);
		HumanOrder o = (HumanOrder) p.order;
		o.deposit = cost[1];
		o.play = pl;
		pl.forces.add(p);
		pl.plannedFP += fp;
		s.count("playForces", 1);
		return p;
	}

	static void toMuster(State s, Council c, Play pl) {
		if (pl.base.lost || !pl.f.id.equals(pl.base.faction)) { end(s, c, pl, FAILURE, "its base is lost"); return; }
		if (!anyAlive(pl.targets)) { finish(s, c, pl, "its worlds are the swarm's no longer"); return; }
		if (liveTargets(s, pl.f, pl.targets, pl).isEmpty()) { end(s, c, pl, NEUTRAL, "another siege has its worlds"); return; }
		force(s, pl, true);
		if (pl.plannedFP <= 0f) {
			strike(s, c, pl, "no hunting force paid");
			return;
		}
		// round 18 addendum (c) warsim_huntMinShare: the hunt share must reach this share of the strongest reported world's FP
		float minShare = s.knobs.f("warsim_huntMinShare", 0f);
		if (minShare > 0f) {
			float strongest = 0f;
			for (Hive h : pl.targets) strongest = Math.max(strongest, worldFP(pl.f, h));
			if (strongest > 0f && pl.plannedFP < minShare * strongest) {
				standDownForces(s, pl);
				s.count("hammer.huntUnderShare", 1);
				end(s, c, pl, FAILURE, "hunt share " + (int) pl.plannedFP + " FP under " + minShare + " of " + (int) strongest + " reported");
				return;
			}
		}
		pl.musterDay = s.day;
		// the voyage to the bearing, then the muster's own days
		pl.phaseDue = s.day + State.travelDays(pl.base.sys.ly(pl.sys)) + (int) (Math.max(1f, s.knobs.f("threatinc_councilMusterDays"))
				* personality(s, pl.f.id, "musterMult") * jitter(s));
		phase(s, pl, "MUSTER", (int) pl.plannedFP + " FP sent to the muster");
	}

	/**
	 * What the muster floor is a share of: the FP sent (plannedFP; the mod's ThreatPlays and this mirror, so the floor can
	 * only fail a play through losses en route), or with warsim_musterFloorVsWant (round 18 addendum a) the play's want -
	 * the report-sized siege of its first live world (HumanPlanner.size) plus the hunt share sent.
	 */
	static float floorBase(State s, Play pl) {
		if (!s.knobs.b("warsim_musterFloorVsWant", false)) return pl.plannedFP;
		Hive h = firstLive(pl);
		if (h == null || pl.base == null || pl.base.lost || pl.f.reports.get(pl.sys) == null) return pl.plannedFP;
		return HumanPlanner.size(s, pl.f, h, pl.base).fp + pl.plannedFP;
	}

	static void standDownForces(State s, Play pl) {
		for (Parcel p : pl.forces) {
			if (!live(p)) continue;
			HumanOrder o = (HumanOrder) p.order;
			// a muster that never went in goes home, provisions back in full
			if (p.kind == Parcel.Kind.MUSTER) HumanSide.settle(s, p, o);
			else HumanSide.home(s, p, o);
		}
	}

	static void musterCheck(State s, Council c, Play pl) {
		float mustered = 0f;
		for (Parcel p : pl.forces) if (live(p)) mustered += p.fp;
		// the force is all in once its voyage to the bearing is done (it waits at its base here)
		boolean allIn = mustered >= ALL_IN * pl.plannedFP && s.day >= pl.musterDay + State.travelDays(pl.base.sys.ly(pl.sys));
		if (!allIn && s.day < pl.phaseDue && mustered > 0f) return;
		// round 19: waiting at the base on an unpaid siege, the siege is re-tried on its day, not daily
		if (pl.waitSince != NEVER && s.day < pl.phaseDue) return;
		float against = floorBase(s, pl);
		float floor = Math.max(0f, s.knobs.f("threatinc_councilMusterFloor")) * against;
		if (mustered < floor) {
			standDownForces(s, pl);
			s.count("hammer.underFloor", 1);
			end(s, c, pl, FAILURE, "mustered " + (int) mustered + " of " + (int) against + " FP by its day");
			return;
		}
		strike(s, c, pl, (allIn ? "all in, " : "its day, ") + (int) mustered + " of " + (int) pl.plannedFP + " FP at the muster");
	}

	static void watchCheck(State s, Council c, Play pl) {
		HumanIntel.Report r = pl.f.reports.get(pl.feint);
		if (pl.feintArrived == NEVER) {
			for (Parcel p : pl.squadrons) if (p.holding) pl.feintArrived = s.day;
			if (pl.feintArrived != NEVER) pl.feintSeen = seen(pl.f, pl.feint);
		}
		float seen = seen(pl.f, pl.feint);
		if (pl.feintArrived != NEVER && r != null && r.day > pl.feintArrived && seen >= DREW * Math.max(1f, pl.feintSeen)) {
			s.count("feintsDrew", 1);
			pl.feintDrew = true;
			strike(s, c, pl, "the feint drew: " + (int) pl.feintSeen + " -> " + (int) seen + " FP reported");
		} else if (s.day >= pl.phaseDue) {
			strike(s, c, pl, "the feint drew nothing seen");
		}
	}

	/** ThreatPlays.strike: the siege sails from the play's base at the play's share of what the base and its donors field. */
	static void strike(State s, Council c, Play pl, String why) {
		if (pl.base.lost || !pl.f.id.equals(pl.base.faction)) {
			standDownForces(s, pl);
			end(s, c, pl, FAILURE, "its base is lost");
			return;
		}
		if (!anyAlive(pl.targets)) {
			standDownForces(s, pl);
			finish(s, c, pl, "its worlds are the swarm's no longer");
			return;
		}
		List<Hive> targets = liveTargets(s, pl.f, pl.targets, pl);
		if (targets.isEmpty()) {
			standDownForces(s, pl);
			end(s, c, pl, NEUTRAL, "another siege has its worlds");
			return;
		}
		// planner sizing: no report of the target system yet - the recon in force first, the forces stand by
		if (reconFirst(s, c, pl, why)) return;
		if (pl.type == FEINT && pl.forces.isEmpty()) force(s, pl, true);
		boolean planner = plannerSizing(s);
		float siegeFP = share(s, pl.f, "threatinc_councilHammerShare") * capacityFP(s, pl.base, pl.sys);
		// one siege a hive here: the first world the pools pay the landing for (the mod drops worlds from the end until it can)
		for (Hive h : targets) {
			// planner sizing: the orbit the report shows at npcSiegeOrbitMargin and the landing the world needs, as
			// HumanPlanner.plan sizes a siege (no widening by trust: the planner spreads prongs instead); unaffordable
			// posts the bounty as the planner's provisions gate does and is not trimmed below what the report needs
			HumanPlanner.Option o = planner ? HumanPlanner.size(s, pl.f, h, pl.base) : HumanPlanner.size(s, pl.f, h, pl.base, siegeFP);
			// IncursionManager.launchSiegeExpedition's provisions gate: the fleets beyond those the landing needs
			// are trimmed to what the depot pays for ("Expedition trimmed at"); under them the siege waits
			for (float fp = siegeFP * 0.8f; !planner && !o.affordable && o.fp > HumanFit.MIN_SIEGE_FP; fp *= 0.8f) {
				float was = o.fp;
				o = HumanPlanner.size(s, pl.f, h, pl.base, fp);
				if (o.fp >= was) break;
				if (o.affordable) s.count("playSiegesTrimmed", 1);
			}
			if (!o.affordable) s.count("playSiege.short." + o.shortOf, 1);
			// round 16 trial b (warsim_hammerSailsPartial): a hammer whose provisions pay at least councilMusterFloor of the
			// report-sized orbit sails with the fleets they pay for and judges on arrival (HumanSiege.orbitDay's call-off);
			// short of marines it still waits
			// round 17: the mod's own gate with threatinc_npcSiegeFullStrength=false sails what the pools provision at or above
			// expeditionMinProvisionsFraction of the points, trimmed (IncursionManager.siegeCanPay's mustPay; "Expedition trimmed")
			boolean fullStrength = s.knobs.b("threatinc_npcSiegeFullStrength", true);
			float floor = !fullStrength ? s.knobs.f("threatinc_expeditionMinProvisionsFraction") : s.knobs.f("threatinc_councilMusterFloor");
			if (!o.affordable && planner && (!fullStrength || s.knobs.b("warsim_hammerSailsPartial", false)) && !World.COMMODITIES[World.MARINES].equals(o.shortOf)
					&& o.paysFP >= Math.max(HumanFit.MIN_SIEGE_FP, floor * o.fp)) {
				HumanPlanner.Option part = HumanPlanner.size(s, pl.f, h, pl.base, o.paysFP);
				if (part.affordable && part.fp < o.fp) {
					s.count(fullStrength ? "playSiegesPartial" : "playSiegesTrimmed", 1);
					s.count("playSiegesPartial.fpShare", part.fp / o.fp);
					o = part;
				}
			}
			if (!o.affordable && planner && o.orbitUnpaid) HumanPlanner.postBounty(s, h.sys, pl.f.id);
			if (!o.affordable && h == targets.get(0)) {
				// round 14: what the first world's siege was short of, by how much, against what the base could call on
				java.util.List<World> donors = HumanPools.donors(s, pl.base, true);
				for (int ci = 0; ci < 4; ci++) {
					float can = HumanPools.payable(s, pl.base, ci, true, donors);
					s.count("strike.held." + World.COMMODITIES[ci], can);
					if (o.wants[ci] > can) s.count("strike.shortBy." + World.COMMODITIES[ci], o.wants[ci] - can);
				}
				s.count("strike.needFP", o.fp);
				s.count("strike.paysFP", HumanPlanner.payableFP(s, pl.base, pl.sys));
			}
			if (!o.affordable) continue;
			float[] draw = o.wants.clone();
			draw[World.SUPPLIES] = o.fp / ReachRules.FP_PER_POINT * s.knobs.f("threatinc_expeditionSuppliesPerPoint");
			if (!HumanPools.pay(s, pl.base, draw, true, "playSiege")) continue;
			pl.siege = HumanPlanner.launch(s, pl.f, o);
			((HumanOrder) pl.siege.order).play = pl;
			pl.sieged = true;
			s.count("playSieges", 1);
			break;
		}
		pl.seenAtStart = pl.seenAtCheck = seen(pl.f, pl.sys);
		s.count(pl.siege != null ? "strike.paid" : "strike.unpaid", 1);
		// the hunts are let go to arrive with the siege: from the same base, they sail on its day
		int sail = pl.siege != null ? ((HumanOrder) pl.siege.order).sailDay : s.day;
		for (Parcel p : pl.forces) if (live(p) && p.kind == Parcel.Kind.MUSTER) ((HumanOrder) p.order).sailDay = sail;
		if (pl.type == FEINT) {
			// bombers on B's Nexus, arriving with the main force
			for (Hive h : targets) {
				if (!h.nexusUp()) continue;
				squadron(s, pl, h, squadronFP(s, h), Math.max(1f, s.knobs.f("threatinc_councilExploitDays")), Math.max(0, sail - s.day));
				break;
			}
		}
		if (pl.siege == null && !anyForceAlive(pl)) {
			end(s, c, pl, FAILURE, why + "; neither the siege nor a hunting force could be paid (" + (int) siegeFP + " FP siege share)");
			return;
		}
		// round 19 (warsim_hammerWaitsForSiege N): no siege paid, the mustered hunts hold at the base (upkeep still paid) and the
		// siege is re-tried every WAIT_RETRY_DAYS until N days have passed, then the play stands down
		int wait = (int) s.knobs.f("warsim_hammerWaitsForSiege", 0f);
		if (pl.siege == null && pl.type == HAMMER && wait > 0 && anyForceAlive(pl)) {
			if (pl.waitSince == NEVER) pl.waitSince = s.day;
			if (s.day - pl.waitSince < wait) {
				for (Parcel p : pl.forces) if (live(p) && p.kind == Parcel.Kind.MUSTER) ((HumanOrder) p.order).sailDay = HELD;
				pl.phaseDue = s.day + WAIT_RETRY_DAYS;
				s.count("hammer.waitRetries", 1);
				s.count("hammer.waitDays", WAIT_RETRY_DAYS);
				s.log("Play " + pl.id + " MUSTER: no siege paid, the hunts wait at " + pl.base.name + " (" + (s.day - pl.waitSince) + " of " + wait + " d)");
				return;
			}
			standDownForces(s, pl);
			s.count("hammer.waitExpired", 1);
			end(s, c, pl, FAILURE, why + "; no siege paid in " + wait + " days of waiting, the hunts stand down");
			return;
		}
		if (pl.siege != null && pl.waitSince != NEVER) s.count("hammer.waitPaid", 1);
		// round 18 addendum (b) warsim_noHuntsAlone: no siege paid, the play stands down rather than sending the hunts alone
		if (pl.siege == null && pl.type == HAMMER && s.knobs.b("warsim_noHuntsAlone", false)) {
			standDownForces(s, pl);
			s.count("hammer.stoodDownUnpaid", 1);
			end(s, c, pl, FAILURE, why + "; no siege paid, the hunts stand down");
			return;
		}
		pl.struck = true;
		phase(s, pl, "STRIKE", why + "; " + (pl.siege != null ? "siege of " + (int) pl.siege.fp + " FP sails from " + pl.base.name
				: "no siege paid (" + (int) siegeFP + " FP share), the hunts go alone"));
	}

	static void strikeCheck(State s, Council c, Play pl) {
		boolean siege = live(pl.siege), forces = anyForceAlive(pl);
		if (!siege && !forces) {
			finish(s, c, pl, "its forces are gone before the strike");
			return;
		}
		boolean there = siege && pl.siege.holding && pl.siege.kind == Parcel.Kind.SIEGE;
		for (Parcel p : pl.forces) if (live(p) && p.holding && p.kind == Parcel.Kind.HUNT) there = true;
		if (!there) return;
		pl.orbitAtCheck = pl.orbitDays;
		pl.landedAtCheck = pl.landed;
		pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilExploitDays")) * jitter(s));
		phase(s, pl, "EXPLOIT", "the hunts go in" + (pl.sieged ? " as the siege nears" : ""));
	}

	static void exploitCheck(State s, Council c, Play pl) {
		boolean siege = live(pl.siege), forces = anyForceAlive(pl);
		if (!siege && !forces) {
			finish(s, c, pl, "its forces are spent or home");
			return;
		}
		if (s.day < pl.phaseDue) return;
		float held = pl.orbitDays - pl.orbitAtCheck;
		pl.orbitAtCheck = pl.orbitDays;
		boolean thinning = pl.seenAtCheck > 0f && pl.seenLast >= 0f && pl.seenLast < 0.8f * pl.seenAtCheck;
		boolean landing = pl.landed > pl.landedAtCheck;
		pl.landedAtCheck = pl.landed;
		pl.seenAtCheck = pl.seenLast;
		if (pl.extensions < MAX_EXTENSIONS && (held > 0f || landing || thinning)) {
			pl.extensions++;
			pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilExploitDays")) * jitter(s));
			return;
		}
		standDownForces(s, pl);
		if (siege) {
			pl.phaseDue = s.day + (int) Math.max(1f, s.knobs.f("threatinc_councilExploitDays"));
			phase(s, pl, "WITHDRAW", "withdraws, the siege fights on alone");
		} else {
			finish(s, c, pl, "withdraws");
		}
	}

	static void advanceStarve(State s, Council c, Play pl) {
		if (!anyAlive(pl.targets)) {
			finish(s, c, pl, "its worlds are the swarm's no longer");
			return;
		}
		final Faction f = pl.f;
		float stay = Math.max(1f, s.knobs.f("threatinc_councilStarveCheckDays"));
		// squadrons in rotation over the Nexuses, one sent a day, the one its reports show least guarded first
		List<Hive> nexuses = new ArrayList<Hive>();
		for (Hive h : pl.targets) {
			if (h.dead || !h.nexusUp() || HumanPlanner.booked(s, f, h, Parcel.Kind.SQUADRON)) continue;
			if (!bombable(s, f, h)) {
				s.count("starve.guardedDays", 1);
				continue;
			}
			nexuses.add(h);
		}
		Collections.sort(nexuses, new Comparator<Hive>() {
			public int compare(Hive a, Hive b) { return Float.compare(worldFP(f, a), worldFP(f, b)); }
		});
		for (Hive h : nexuses) {
			float fp = squadronFP(s, h);
			World base = squadronBase(s, f, h, fp);
			if (base == null) continue;
			float cost = fuelCost(s, base, h, fp, stay);
			if (pl.fuelSpent + cost > pl.fuelBudget) continue;
			if (squadron(s, pl, h, fp, stay, 0) == null) continue;
			pl.fuelSpent += cost;
			break;
		}
		if (s.day < pl.phaseDue) return;
		pl.checks++;
		float seen = seen(f, pl.sys);
		boolean regrowing = pl.seenAtCheck > 0f && seen > 1.1f * pl.seenAtCheck;
		pl.seenAtCheck = seen;
		String line = "check " + pl.checks + ": Nexus down " + (int) pl.nexusDownDays + " world-days (streak " + (int) pl.nexusStreak
				+ " d), " + pl.raids + " raids, " + pl.drivenOff + " driven off (" + pl.offInRow + " in a row)";
		if (pl.offInRow >= Math.max(1, s.knobs.i("threatinc_councilStarveAbortRaids"))) {
			end(s, c, pl, FAILURE, "driven off " + pl.offInRow + " times in a row; " + line);
			return;
		}
		if (pl.nexusStreak >= s.knobs.f("threatinc_councilInvadeNexusDays") && !regrowing) {
			Cluster k = c.picture != null ? c.picture.cluster(pl.sys) : null;
			// warsim_councilStarveToHammer (round 13 trial 3, option C): the starved campaign turns to the hammer in the
			// same play - its base, its targets, straight to the muster, no fresh start and no PREPARE wait
			if (s.knobs.b("warsim_councilStarveToHammer", false)) {
				learn(s, c, STARVE + ":" + pl.targetClass, true);
				pl.type = HAMMER;
				pl.targets = liveTargets(s, f, pl.targets, pl);
				s.count("starveToHammer", 1);
				phase(s, pl, "PREPARE", "starved; the campaign turns to the hammer; " + line);
				toMuster(s, c, pl);
				return;
			}
			end(s, c, pl, SUCCESS, "starved, invasion next; " + line);
			// the invasion follows on: the campaign's own saturation siege is not in its way
			if (k != null && startHammer(s, f, c, k, "invade the starved system", pl) != null) s.count("starveInvasions", 1);
			return;
		}
		if (pl.checks >= STARVE_MAX_CHECKS) {
			end(s, c, pl, pl.nexusDownDays >= s.knobs.f("threatinc_councilInvadeNexusDays") ? SUCCESS : FAILURE, "the campaign ran its course; " + line);
			return;
		}
		s.log("Play " + pl.id + " STARVE " + line);
		// the next month's fuel: a share of the means as they stand now
		pl.fuelTotal += pl.fuelSpent;
		pl.fuelSpent = 0f;
		if (c.picture != null) pl.fuelBudget = share(s, f, "threatinc_councilStarveShare") * c.picture.fuel;
		saturate(s, pl);
		pl.phaseDue = s.day + (int) (Math.max(1f, s.knobs.f("threatinc_councilStarveCheckDays")) * jitter(s));
	}

	/**
	 * ThreatPlays.saturate: the bombing campaign's saturation expedition, once, when the pools pay: it razes, it
	 * lands nothing. Here a SATURATION parcel over the campaign's first world for siegeOrbitDays.
	 */
	static void saturate(State s, Play pl) {
		if (pl.sieged || pl.base == null || pl.base.lost) return;
		List<Hive> targets = liveTargets(s, pl.f, pl.targets, pl);
		if (targets.isEmpty()) return;
		Hive h = targets.get(0);
		float fp = share(s, pl.f, "threatinc_councilStarveShare") * capacityFP(s, pl.base, pl.sys);
		fp = (float) Math.floor(fp / ReachRules.FP_PER_POINT) * ReachRules.FP_PER_POINT;
		if (fp < HumanFit.MIN_SIEGE_FP && !s.knobs.b("warsim_saturationGameGate", false)) return;
		float stay = s.knobs.f("threatinc_siegeOrbitDays");
		float[] cost = ReachRules.voyageCost(fp, 2f * pl.base.sys.ly(pl.sys), s.knobs.f("threatinc_expeditionFuelPerPointLY"),
				s.knobs.f("threatinc_expeditionSuppliesPerPoint"));
		float ordnance = stay * BattleRules.bombardFuelPerDay(fp, s.knobs.f("threatinc_bombardFuelPerFPDay"));
		// round 23, the game's price (IncursionManager.expeditionFuel over the raze set): each world's whole saturation
		// (razingFuel: a squadron pouring satFuelPerFPDay to the commander's stop - hw4's "Saturate or siege" lines read
		// 47k fuel at size 4, 59-75k at 5, 150-188k at 7, about 3,000 x size squared) for EVERY world of the play, set
		// aside whole or the expedition does not sail. warsim_saturationFuelSize2 0 is the old price (a tactical stay
		// over the first world); warsim_saturateAffordable sails for the worlds the pools pay, first to last, not all or none
		float size2 = s.knobs.f("warsim_saturationFuelSize2", 3000f);
		List<Hive> raze = new ArrayList<Hive>();
		float deposit = cost[1];
		if (s.knobs.b("warsim_saturationGameGate", false) && size2 > 0f) {
			// round 30 (2026-10-04), the game's gate (ThreatPlays.saturate -> siegeSizesFor with the raze set ->
			// launchSiegeExpedition): the flotilla is sized on the reported orbit x the margin, not on the pools' share;
			// it stays over the raze set in turn (siegeStayDays, about warsim_saturationDaysPerWorld a world: hw4s's
			// "Saturate or siege" lines read 110 d), its supplies billed for that whole trip, and every world's razing fuel
			// set aside - all of it paid, or it waits (hw4s: 429 of its 432 postponements, 0 fuel past the razing)
			HumanIntel.Report r = pl.f.reports.get(pl.sys);
			float orbit = r == null ? 0f : r.total() * s.knobs.f("threatinc_npcSiegeOrbitMargin");
			ordnance = 0f;
			for (Hive x : targets) ordnance += saturationFuel(x, size2);
			// razeFleetPoints (the least fleet that razes them in turn and outlasts the guns) as the razing fuel over
			// warsim_saturationFuelPerFP: hw4s's postponements need 2,650-7,450 FP for 270k-765k fuel, 86-176 a point, median ~100
			float razeFP = ordnance / Math.max(1f, s.knobs.f("warsim_saturationFuelPerFP", 100f));
			fp = HumanPlanner.roundUp(Math.max(HumanFit.MIN_SIEGE_FP, Math.max(orbit, razeFP)));
			float points = fp / ReachRules.FP_PER_POINT;
			float ly = pl.base.sys.ly(pl.sys);
			float days = Math.max(stay, s.knobs.f("warsim_saturationDaysPerWorld", 110f) * targets.size());
			float supplies = points * ReachRules.siegeSuppliesPerPoint(s.knobs.f("threatinc_expeditionSuppliesPerPoint"),
					HumanPlanner.suppliesPerFP(s), ReachRules.siegeTripDays(ly, days, State.LY_PER_DAY));
			cost = new float[] { ReachRules.passageFuel(points, ly, s.knobs.f("threatinc_expeditionFuelPerPointLY")), supplies };
			deposit = points * s.knobs.f("threatinc_expeditionSuppliesPerPoint");
			raze.addAll(targets);
			h = raze.remove(0);
		} else if (size2 > 0f) {
			boolean affordable = s.knobs.b("warsim_saturateAffordable", false);
			raze.addAll(targets);
			while (true) {
				ordnance = 0f;
				for (Hive x : raze) ordnance += saturationFuel(x, size2);
				if (!affordable || raze.size() <= 1
						|| HumanPools.canPay(s, pl.base, new float[] { 0f, 0f, cost[0] + ordnance, cost[1] }, true, "saturation")) break;
				raze.remove(raze.size() - 1);
			}
			h = raze.remove(0);
		}
		s.count("saturate.tried", 1);
		if (!HumanPools.pay(s, pl.base, new float[] { 0f, 0f, cost[0] + ordnance, cost[1] }, true, "saturation")) {
			s.count("saturate.unpaid", 1);
			s.log("Play " + pl.id + " STARVE: no saturation expedition the pools pay (" + (int) fp + " FP, " + (int) (cost[0] + ordnance)
					+ " fuel of " + (int) HumanPools.payable(s, pl.base, World.FUEL, true) + ", " + (int) cost[1] + " supplies of "
					+ (int) HumanPools.payable(s, pl.base, World.SUPPLIES, true) + ")");
			return;
		}
		Parcel p = s.send(pl.f.id, Parcel.Kind.SATURATION, pl.base.sys, pl.sys, fp, 0);
		p.targetId = h.id;
		p.fuel = ordnance;
		HumanOrder o = new HumanOrder();
		o.razeNext = raze;
		o.satFuelSize2 = size2;
		s.count("saturationWorlds", 1 + raze.size());
		o.home = pl.base;
		o.target = h;
		o.trust = 1f;
		o.deposit = deposit;
		o.play = pl;
		o.stayDays = Math.max(1, (int) stay);
		p.order = o;
		pl.siege = p;
		pl.sieged = true;
		s.count("saturationsSailed", 1);
		s.log("Play " + pl.id + " STARVE: saturation expedition of " + (int) fp + " FP sails from " + pl.base.name);
	}

	/** The fuel one hive's whole saturation burns in the game (round 23): size2 x its size squared. */
	static float saturationFuel(Hive h, float size2) {
		return size2 * h.size * h.size;
	}

	// ---- ending ----

	/** The verdict by the damage done: a world taken or landed on, or a Nexus down councilInvadeNexusDays. */
	static void finish(State s, Council c, Play pl, String why) {
		pl.taken = 0;
		for (Hive h : pl.targets) if (h.dead) pl.taken++;
		boolean ok = pl.taken > 0 || pl.landed > 0 || pl.nexusDownDays >= s.knobs.f("threatinc_councilInvadeNexusDays");
		end(s, c, pl, ok ? SUCCESS : FAILURE, why);
	}

	static void end(State s, Council c, Play pl, String outcome, String why) {
		if (!c.plays.remove(pl)) return;
		for (Parcel p : pl.forces) {
			if (live(p) && p.kind == Parcel.Kind.MUSTER) HumanSide.settle(s, p, (HumanOrder) p.order);
		}
		s.count("plays." + pl.type + "." + outcome, 1);
		s.count("playsEnded." + outcome, 1);
		s.count("playDays." + pl.type, s.day - pl.started);
		// round 18 addendum: what the hunts alone achieve - a hammer that reached STRIKE split by whether its siege sailed
		if (pl.type == HAMMER && pl.struck) {
			String kind = pl.sieged ? "hammer.sieged" : "hammer.huntsAlone";
			float lost = 0f;
			for (Parcel p : pl.forces) if (p.fp0 > 0f) lost += Math.max(0f, p.fp0 - (p.done && p.fp <= 0f ? 0f : p.fp));
			s.count(kind, 1);
			s.count(kind + ".taken", pl.taken);
			s.count(kind + ".huntFPLost", lost);
			s.count(kind + "." + outcome, 1);
		}
		s.log("Play " + pl.id + ": " + outcome + " (" + why + "; Nexus down " + (int) pl.nexusDownDays + " world-days, orbit held "
				+ (int) pl.orbitDays + ", landed " + pl.landed + ", taken " + pl.taken + (pl.raids > 0 ? ", raids " + pl.raids + " ("
						+ pl.drivenOff + " driven off)" : "") + ", " + (s.day - pl.started) + " d)");
		// the feint's own success test (war-council.md 9): did A's reports rise after the feint, and did the strike then land
		if (pl.type == FEINT && pl.feintArrived != NEVER) {
			s.count(pl.feintDrew ? "feint.drew" : "feint.notDrew", 1);
			if (pl.landed > 0 || pl.taken > 0) s.count(pl.feintDrew ? "feint.drew.landed" : "feint.notDrew.landed", 1);
		}
		boolean decisive = outcome != NEUTRAL;
		if (!decisive || pl.type == RECON) return;
		boolean ok = outcome == SUCCESS;
		learn(s, c, pl.type + ":" + pl.targetClass, ok);
		if (pl.type == BOMBERS) {
			if (!ok) for (Hive h : pl.targets) c.repulsed.put(h, s.day);
			return;
		}
		learn(s, c, "strategy:" + pl.strategy, ok);
		if (c.earlyWhy == null) c.earlyWhy = "play " + pl.id + " " + pl.type + " " + outcome;
		if ((!ok || pl.taken >= pl.targets.size()) && c.focus == pl.sys) c.focus = null;
	}
}
