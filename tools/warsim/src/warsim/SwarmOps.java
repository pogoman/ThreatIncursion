package warsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import threatinc.rules.BattleRules;
import threatinc.rules.HiveRules;
import threatinc.rules.PostureRules;
import threatinc.rules.SpreadRules;
import threatinc.rules.StanceRules;
import threatinc.rules.StrikeRules;

/**
 * What the swarm sends out, on the 30-day tick (IncursionManager.tick): the opening chain,
 * claims and Seeding Swarms, in-system expansion, strikes, scouts; and what happens when a
 * parcel arrives. Claim cap, passage, days and weights are threatinc.rules calls.
 */
final class SwarmOps {
	private SwarmOps() {}

	/** A Seeding Swarm's order. */
	static final class WaveOrder {
		boolean bootstrap;
		/** The opening chain's structure: 0 forge, 1 fuel plant, 2 refining, else mining. */
		int role;
		Hive source;
	}

	/** A strike's order. */
	static final class StrikeOrder {
		World target;
		Hive home;
		/** Swarms mustered: the gate reads a strike at STRIKE_UNITS_PER_SWARM each. */
		int swarms;
		int groundEnd = Integer.MIN_VALUE;
	}

	/** A fleet going home to be banked. */
	static final class Rebank {
		Hive home;
	}

	static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII" };

	/** Whether a parcel burns supplies while away: every fleet out but the opening chain. */
	static boolean burns(State s, Parcel p) {
		if (p.done || !p.threat() || p.departDay > s.day) return false;
		return !(p.order instanceof WaveOrder && ((WaveOrder) p.order).bootstrap);
	}

	// ------------------------------------------------------------------
	// the tick
	// ------------------------------------------------------------------

	static void tick(State s, SwarmKnobs k) {
		for (Hive h : s.hives) h.outputSize = h.size;
		radar(s, k);
		launchClaims(s, k);
		if (s.liveHives().isEmpty()) return;
		SwarmEconomy.tick(s, k);
		expandInSystem(s, k);
		trySpread(s, k);
		if (SwarmPosture.phase(s) >= 2) {
			tryStrike(s, k);
			if (k.scouting) scouts(s, k);
		}
	}

	/** Worlds inside the Bastions' radar are seen as they stand (ThreatSwarmIntel, simplified to every hive system). */
	static void radar(State s, SwarmKnobs k) {
		List<StarSys> systems = SwarmEconomy.hiveSystems(s);
		for (World w : s.worlds) {
			if (w.lost) continue;
			boolean inRange = !k.fog;
			for (StarSys sys : systems) if (sys.ly(w.sys) <= k.radarLY) inRange = true;
			if (inRange) s.swarm.seen.put(w.id, new float[] { s.day, w.defence });
		}
	}

	static void see(State s, StarSys sys) {
		for (World w : s.worlds) if (!w.lost && w.sys == sys) s.swarm.seen.put(w.id, new float[] { s.day, w.defence });
	}

	// ------------------------------------------------------------------
	// spread
	// ------------------------------------------------------------------

	static boolean held(State s, StarSys sys) {
		for (Hive h : s.hives) if (!h.dead && h.sys == sys) return true;
		return false;
	}

	static boolean inhabited(State s, StarSys sys) {
		for (World w : s.worlds) if (!w.lost && w.sys == sys) return true;
		return false;
	}

	static boolean waveBound(State s, StarSys sys) {
		for (Parcel p : s.parcels) if (!p.done && p.threat() && p.kind == Parcel.Kind.WAVE && p.to == sys) return true;
		return false;
	}

	/**
	 * IncursionManager.pickOGSystem: any empty system that can carry the full chain (here: room
	 * for its OG_CHAIN planets) at least half as far from the nearest world as the farthest such
	 * system, picked at random.
	 */
	static StarSys pickOG(State s) {
		if (s.swarm.ogSystemId != null && s.systems.get(s.swarm.ogSystemId) != null) {
			return s.systems.get(s.swarm.ogSystemId);
		}
		List<StarSys> viable = new ArrayList<StarSys>();
		StarSys most = null;
		float maxDist = -1f;
		for (StarSys sys : s.systems.values()) {
			if (inhabited(s, sys)) continue;
			if (most == null || sys.planets > most.planets) most = sys;
			if (sys.planets < SwarmFit.OG_CHAIN) continue;
			viable.add(sys);
			maxDist = Math.max(maxDist, nearestWorldLY(s, sys));
		}
		List<StarSys> open = new ArrayList<StarSys>();
		for (StarSys sys : viable) if (nearestWorldLY(s, sys) >= maxDist * 0.5f) open.add(sys);
		if (open.isEmpty()) return most;
		return open.get(s.rng.nextInt(open.size()));
	}

	static float nearestWorldLY(State s, StarSys sys) {
		float best = Float.MAX_VALUE;
		for (World w : s.worlds) if (!w.lost) best = Math.min(best, sys.ly(w.sys));
		return best;
	}

	static float nearestHiveLY(State s, StarSys sys) {
		float best = Float.MAX_VALUE;
		for (Hive h : s.hives) if (!h.dead) best = Math.min(best, sys.ly(h.sys));
		return best;
	}

	static boolean isSource(State s, SwarmKnobs k, Hive h) {
		return h.size >= k.spreadMinSize && h.forge && h.forgeUp() && h.nexusUp() && !SwarmEconomy.pressed(s, h.sys);
	}

	static int freeForges(State s, SwarmKnobs k) {
		int n = 0;
		for (Hive h : s.hives) if (!h.dead && isSource(s, k, h)) n++;
		return n;
	}

	/** pickForgeSource: the nearest forge colony whose system has a swarm to spare, one that covers the founding's bill first. */
	static Hive pickSource(State s, SwarmKnobs k, StarSys target) {
		float bill = SwarmFit.FOUNDING_BILL_STRUCTURES * k.foundingFPPerStructure;
		Hive best = null;
		boolean bestPays = false;
		for (Hive h : s.hives) {
			if (h.dead || !isSource(s, k, h) || SwarmEconomy.poolAvailable(s, k, h.sys) < 1) continue;
			boolean pays = SwarmEconomy.poolable(s, k, h) >= bill;
			if (best == null || (pays && !bestPays)
					|| (pays == bestPays && h.sys.ly(target) < best.sys.ly(target))) {
				best = h;
				bestPays = pays;
			}
		}
		return best;
	}

	/** Seeded systems whose 120 days are up send their wave; one the stocks cannot pay waits, its demand booked. */
	static void launchClaims(State s, SwarmKnobs k) {
		for (Swarm.Claim c : new ArrayList<Swarm.Claim>(s.swarm.claims)) {
			if (s.day - c.day < k.seedToColonyDays) continue;
			if (c.bootstrap) {
				s.swarm.claims.remove(c);
				s.log("Bootstrap: " + SwarmFit.OG_CHAIN + " waves to " + c.sys);
				for (int i = 0; i < SwarmFit.OG_CHAIN; i++) {
					Parcel p = s.send(Parcel.THREAT, Parcel.Kind.WAVE, c.sys, c.sys, SwarmFit.bootstrapSwarmFP(s.rng),
							SwarmFit.bootstrapTravelDays(s.rng));
					WaveOrder o = new WaveOrder();
					o.bootstrap = true;
					o.role = i;
					p.order = o;
				}
				continue;
			}
			if (inhabited(s, c.sys) && !held(s, c.sys)) {
				s.swarm.claims.remove(c);
				continue;
			}
			if (launchWave(s, k, c.sys)) s.swarm.claims.remove(c);
		}
	}

	static boolean launchWave(State s, SwarmKnobs k, StarSys target) {
		Hive source = pickSource(s, k, target);
		if (source == null) return false;
		float ly = source.sys.ly(target);
		float fuel = k.outpostFuel + k.passage(SwarmFit.WAVE_FP, ly, false);
		float supplies = k.foundSupplies();
		boolean ok = true;
		if (!SwarmEconomy.canPay(s, Swarm.SUPPLIES, supplies)) {
			SwarmEconomy.noteDemand(s, Swarm.SUPPLIES, supplies);
			ok = false;
		}
		if (!SwarmEconomy.canPay(s, Swarm.FUEL, fuel)) {
			SwarmEconomy.noteDemand(s, Swarm.FUEL, fuel);
			ok = false;
		}
		if (ok && !SwarmEconomy.canSustain(s, k, SwarmFit.WAVE_FP, k.days(ly))) ok = false;
		if (!ok) {
			s.count("wavesHeld", 1);
			s.log("Wave held: " + target);
			return false;
		}
		float fp = SwarmEconomy.takeSpare(s, k, source.sys);
		if (fp <= 0f) return false;
		// launchColonizationWave: the bank, its system's topping it up, pays what the wave weighs
		// beyond the swarm that left; its structures are priced in supplies ("0 FP of structures")
		float bill = SwarmFit.WAVE_FP - fp;
		if (bill > 0f && !SwarmEconomy.pool(s, k, source, bill)) {
			source.swarms.add(fp);
			source.book();
			s.count("wavesHeld", 1);
			return false;
		}
		SwarmEconomy.pay(s, Swarm.SUPPLIES, supplies);
		SwarmEconomy.pay(s, Swarm.FUEL, fuel);
		source.bank -= bill;
		Parcel p = s.send(Parcel.THREAT, Parcel.Kind.WAVE, source.sys, target, SwarmFit.WAVE_FP,
				SwarmFit.waveLandingDays(s.rng));
		WaveOrder o = new WaveOrder();
		o.source = source;
		p.order = o;
		s.count("wavesLaunched", 1);
		boolean firstWave = source.firstWaveDay == Integer.MIN_VALUE;
		if (firstWave) source.firstWaveDay = s.day;
		s.log("Seeding Swarm: " + source.name + " -> " + target
				+ (firstWave ? " (its first, " + (s.day - source.foundedDay) + " days after founding)" : ""));
		return true;
	}

	/** tryExpandInSystem: a spare resource planet of a held system takes a wave without a claim's wait. */
	static void expandInSystem(State s, SwarmKnobs k) {
		for (StarSys sys : SwarmEconomy.hiveSystems(s)) {
			Integer n = s.swarm.expandable.get(sys.id);
			if (n == null || n <= 0 || waveBound(s, sys) || SwarmEconomy.pressed(s, sys)) continue;
			if (launchWave(s, k, sys)) s.swarm.expandable.put(sys.id, n - 1);
			return;
		}
	}

	/** trySpread: one claim a tick while fewer are pending than the posture's cap, on the best-weighted system. */
	static void trySpread(State s, SwarmKnobs k) {
		int pending = 0;
		for (Swarm.Claim c : s.swarm.claims) if (!c.bootstrap) pending++;
		int cap = PostureRules.claimCap(freeForges(s, k), s.swarm.appetite,
				StanceRules.expansionShare(s.swarm.stance, k.secondaryShare));
		if (pending >= cap) return;

		// the strongest rival in the field, for the expanding stance's lean away from it
		String rival = null;
		float rivalFP = 0f;
		for (Faction f : s.factions.values()) {
			float fp = 0f;
			for (Parcel p : s.parcels) if (!p.done && f.id.equals(p.owner)) fp += p.fp;
			if (fp > rivalFP) {
				rivalFP = fp;
				rival = f.id;
			}
		}

		StarSys best = null;
		float bestWeight = 0f;
		for (StarSys sys : s.systems.values()) {
			if (sys.planets <= 0 || held(s, sys) || inhabited(s, sys) || waveBound(s, sys)) continue;
			boolean claimed = false;
			for (Swarm.Claim c : s.swarm.claims) if (c.sys == sys) claimed = true;
			if (claimed) continue;
			float dHive = nearestHiveLY(s, sys);
			float dPeace = -1f, dRival = Float.MAX_VALUE;
			for (World w : s.worlds) {
				if (w.lost) continue;
				float ly = sys.ly(w.sys);
				if (!s.faction(w.faction).mobilised && !SwarmFit.neverMobilises(w.faction)) {
					if (dPeace < 0f || ly < dPeace) dPeace = ly;
				}
				if (w.faction.equals(rival)) dRival = Math.min(dRival, ly);
			}
			float weight = SpreadRules.billedWeight(SwarmFit.needScore(s.rng), 1f, k.days(dHive),
					dPeace >= 0f ? k.strikeDays(dPeace) : 1f);
			if (s.swarm.stance == Swarm.EXPAND && rival != null) weight *= StanceRules.spreadMult(dRival);
			if (best == null || weight > bestWeight) {
				best = sys;
				bestWeight = weight;
			}
		}
		if (best == null) return;
		// the first claim waits for the fuel its wave will burn
		float fuel = k.outpostFuel + k.passage(SwarmFit.WAVE_FP, nearestHiveLY(s, best), false);
		if (!SwarmEconomy.canPay(s, Swarm.FUEL, fuel)) return;
		Swarm.Claim c = new Swarm.Claim();
		c.sys = best;
		c.day = s.day;
		s.swarm.claims.add(c);
		s.count("claims", 1);
		s.log("Spread to " + best);
	}

	static void rollExpandable(State s, StarSys sys, int taken) {
		if (s.swarm.expandable.containsKey(sys.id)) return;
		int n = 0;
		for (int i = 0; i < sys.planets - taken; i++) if (s.rng.nextFloat() < SwarmFit.EXPANSION_PLANET_SHARE) n++;
		s.swarm.expandable.put(sys.id, n);
	}

	static String hiveName(State s, StarSys sys) {
		int n = 0;
		for (Hive h : s.hives) if (h.sys == sys) n++;
		return sys.name + " " + (n < ROMAN.length ? ROMAN[n] : String.valueOf(n + 1));
	}

	// ------------------------------------------------------------------
	// strikes and scouts
	// ------------------------------------------------------------------

	static boolean strikeable(State s, World w) {
		if (w.lost || s.swarm.struck.contains(w.id)) return false;
		if (!w.forwardBase && w.size < 3) return false;
		int phase = SwarmPosture.phase(s);
		if (phase < 2) return false;
		if (phase < 3) {
			if (w.size >= 6 && !w.forwardBase) return false;
			if (!s.faction(w.faction).mobilised && !SwarmFit.neverMobilises(w.faction)) return false;
		}
		return true;
	}

	static float strikeValue(SwarmKnobs k, World w) {
		if (w.forwardBase) return StrikeRules.sizeValue(Math.max(3, w.size)) * k.frontlineStrikeWeight;
		return StrikeRules.sizeValue(w.size);
	}

	/** The swarms a system could send, largest first: {hive index, fp}. */
	static List<float[]> spares(State s, SwarmKnobs k, StarSys sys) {
		List<float[]> out = new ArrayList<float[]>();
		for (int i = 0; i < s.hives.size(); i++) {
			Hive h = s.hives.get(i);
			if (h.dead || h.sys != sys || h.size < k.strikeMinSize || !h.nexusUp()) continue;
			int n = SwarmEconomy.available(s, k, h);
			if (n <= 0) continue;
			List<Float> sorted = new ArrayList<Float>(h.swarms);
			Collections.sort(sorted, Collections.reverseOrder());
			for (int j = 0; j < n && j < sorted.size(); j++) out.add(new float[] { i, sorted.get(j) });
		}
		Collections.sort(out, new Comparator<float[]>() {
			public int compare(float[] a, float[] b) { return Float.compare(b[1], a[1]); }
		});
		return out;
	}

	/** What the strike gate reads a strike at, in the defence's units. */
	static float strength(Parcel p) {
		int swarms = p.order instanceof StrikeOrder ? ((StrikeOrder) p.order).swarms : 0;
		if (swarms <= 0 || p.fp0 <= 0f) return p.fp * SwarmFit.STRIKE_UNITS_PER_FP;
		return swarms * SwarmFit.STRIKE_UNITS_PER_SWARM * p.fp / p.fp0;
	}

	/**
	 * tryStrikes: every hive system, in shuffled order, may send one strike a tick - all the
	 * swarms above its reserves, fewer while the fuel or the supplies cannot carry them - at a
	 * world the swarm has seen, picked by worth over the days away and the stance.
	 */
	static void tryStrike(State s, SwarmKnobs k) {
		List<StarSys> systems = SwarmEconomy.hiveSystems(s);
		Collections.shuffle(systems, s.rng);
		for (StarSys from : systems) {
			if (SwarmEconomy.pressed(s, from)) continue;
			List<float[]> spare = spares(s, k, from);
			// pickStrikeStaging: a strike musters at least two swarms above the reserves
			if (spare.size() < 2) continue;
			float full = spare.size() * SwarmFit.STRIKE_UNITS_PER_SWARM;
			List<World> picks = new ArrayList<World>();
			List<Float> weights = new ArrayList<Float>();
			float total = 0f;
			for (World w : s.worlds) {
				if (!strikeable(s, w)) continue;
				float[] seen = s.swarm.seen.get(w.id);
				if (seen == null || seen[1] >= full * k.breakOff) continue;
				float odds = seen[1] / Math.max(1f, full * k.breakOff);
				float mult = 1f;
				if (s.swarm.stance == Swarm.PRESS) {
					mult = Math.max(0.05f, 1f - odds);
					if (w.id.equals(s.swarm.pressTarget)) mult *= SwarmFit.TARGET_WEIGHT;
				} else if (s.swarm.stance == Swarm.CONSOLIDATE) {
					mult = odds <= k.weakOdds && w.forwardBase ? Math.max(0.05f, 1f - odds) : 0f;
				}
				float weight = strikeValue(k, w) / k.strikeDays(from.ly(w.sys)) * mult;
				if (weight <= 0f) continue;
				picks.add(w);
				weights.add(weight);
				total += weight;
			}
			if (picks.isEmpty()) continue;
			float roll = s.rng.nextFloat() * total;
			World w = picks.get(picks.size() - 1);
			for (int i = 0; i < picks.size(); i++) {
				roll -= weights.get(i);
				if (roll <= 0f) {
					w = picks.get(i);
					break;
				}
			}
			float ly = from.ly(w.sys);
			float days = k.strikeDays(ly);
			int count = spare.size();
			float fp = 0f;
			for (; count > 0; count--) {
				fp = 0f;
				for (int i = 0; i < count; i++) fp += spare.get(i)[1];
				if (!SwarmEconomy.canPay(s, Swarm.FUEL, k.passage(fp, ly, true))) continue;
				if (!SwarmEconomy.canSustain(s, k, fp, days)) continue;
				break;
			}
			if (count <= 0) {
				float one = spare.get(spare.size() - 1)[1];
				if (!SwarmEconomy.canPay(s, Swarm.FUEL, k.passage(one, ly, true))) {
					SwarmEconomy.noteDemand(s, Swarm.FUEL, k.passage(one, ly, true));
				}
				s.count("strikesHeld", 1);
				continue;
			}
			SwarmEconomy.pay(s, Swarm.FUEL, k.passage(fp, ly, true));
			Hive home = null;
			for (int i = 0; i < count; i++) {
				Hive h = s.hives.get((int) spare.get(i)[0]);
				h.swarms.remove(Float.valueOf(spare.get(i)[1]));
				h.book();
				if (home == null || h.size > home.size) home = h;
			}
			Parcel p = s.send(Parcel.THREAT, Parcel.Kind.STRIKE, from, w.sys, fp, SwarmFit.strikePrepDays(s.rng));
			p.targetId = w.id;
			StrikeOrder o = new StrikeOrder();
			o.target = w;
			o.home = home;
			o.swarms = count;
			p.order = o;
			s.swarm.struck.add(w.id);
			Faction f = s.faction(w.faction);
			f.strikesSuffered++;
			f.lastStruckDay = s.day;
			s.count("strikesLaunched", 1);
			s.log("Strike: " + (int) fp + " FP in " + count + " swarms from " + from + " at " + w + " ("
					+ (int) ly + " ly, defence " + (int) s.swarm.seen.get(w.id)[1] + ")");
		}
	}

	/**
	 * Scouting Swarms (ThreatSwarmScouts, modelled): each hive system may send one a tick, to an
	 * inhabited system picked at random among those never seen or gone stale, its passage there
	 * and back paid in fuel.
	 */
	static void scouts(State s, SwarmKnobs k) {
		List<StarSys> targets = new ArrayList<StarSys>();
		for (World w : s.worlds) {
			if (w.lost || targets.contains(w.sys)) continue;
			float[] seen = s.swarm.seen.get(w.id);
			if (seen != null && s.day - seen[0] < SwarmFit.STALE_DAYS) continue;
			boolean bound = false;
			for (Parcel p : s.parcels) if (!p.done && p.kind == Parcel.Kind.SWARM_SCOUT && p.to == w.sys) bound = true;
			if (!bound) targets.add(w.sys);
		}
		for (StarSys from : SwarmEconomy.hiveSystems(s)) {
			if (targets.isEmpty()) return;
			if (s.rng.nextFloat() >= SwarmFit.SCOUT_SHARE_PER_SYSTEM) continue;
			boolean nexus = false;
			for (Hive h : s.hivesIn(from)) if (h.nexusUp()) nexus = true;
			if (!nexus) continue;
			StarSys target = targets.get(s.rng.nextInt(targets.size()));
			float ly = from.ly(target);
			if (!SwarmEconomy.canSustain(s, k, k.scoutFP, k.strikeDays(ly))) continue;
			if (!SwarmEconomy.pay(s, Swarm.FUEL, k.passage(k.scoutFP, ly, true))) continue;
			s.send(Parcel.THREAT, Parcel.Kind.SWARM_SCOUT, from, target, k.scoutFP, 0);
			s.count("scoutsSent", 1);
			targets.remove(target);
		}
	}

	/** A month of supplies owed to the fleets away: strikes still outbound turn back. */
	static void starveAway(State s) {
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (p.done || !p.threat() || p.kind != Parcel.Kind.STRIKE || p.arrived) continue;
			s.count("strikesRecalled", 1);
			goHome(s, p, p.from);
		}
	}

	static void release(State s, Parcel p) {
		if (p.targetId != null) s.swarm.struck.remove(p.targetId);
	}

	/** Ends a fleet's errand and sends what is left of it to be banked at its home, or the nearest colony. */
	static void goHome(State s, Parcel p, StarSys at) {
		p.done = true;
		release(s, p);
		if (p.fp < 1f) return;
		Hive home = p.order instanceof StrikeOrder ? ((StrikeOrder) p.order).home : null;
		if (home == null || home.dead) {
			home = null;
			for (Hive h : s.hives) {
				if (h.dead) continue;
				if (home == null || h.sys.ly(at) < home.sys.ly(at)) home = h;
			}
		}
		if (home == null) return;
		Parcel back = s.send(Parcel.THREAT, Parcel.Kind.REINFORCEMENT, at, home.sys, p.fp, 0);
		Rebank r = new Rebank();
		r.home = home;
		back.order = r;
	}

	/** The day's strikes on the ground: a fleet the defenders broke is gone, one whose assault is done takes the world. */
	static void daily(State s, SwarmKnobs k) {
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (!p.threat() || p.kind != Parcel.Kind.STRIKE || !p.arrived || !p.holding) continue;
			StrikeOrder o = orderOf(s, p);
			if (o.target != null && o.groundEnd == Integer.MIN_VALUE) o.groundEnd = s.day + SwarmFit.groundDays(s.rng);
			if (p.done || p.fp < 1f) {
				p.done = true;
				release(s, p);
				continue;
			}
			if (o.target == null || o.target.lost) {
				goHome(s, p, p.to);
				continue;
			}
			if (s.day < o.groundEnd) continue;
			World w = o.target;
			s.loseWorld(w, true, "ground assault");
			if (k.conquestConverts && s.rng.nextFloat() < SwarmFit.CONQUEST_HIVE_SHARE) {
				Hive h = s.foundHive(w.sys, w.name, k.conquestSize);
				rollExpandable(s, w.sys, 1);
				SwarmEconomy.plan(s, k, h);
			}
			goHome(s, p, p.to);
		}
		// a strike that vanished (destroyed in flight by the other side) frees its target
		if (!s.swarm.struck.isEmpty()) {
			for (String id : new ArrayList<String>(s.swarm.struck)) {
				boolean out = false;
				for (Parcel p : s.parcels) if (!p.done && p.threat() && p.kind == Parcel.Kind.STRIKE && id.equals(p.targetId)) out = true;
				if (!out) s.swarm.struck.remove(id);
			}
		}
	}

	// ------------------------------------------------------------------
	// arrivals
	// ------------------------------------------------------------------

	static void arrive(State s, SwarmKnobs k, Parcel p) {
		switch (p.kind) {
		case WAVE: wave(s, k, p); break;
		case STRIKE: strike(s, k, p); break;
		case SWARM_SCOUT:
			see(s, p.to);
			p.done = true;
			break;
		case REINFORCEMENT: reinforcement(s, p); break;
		default:
			// a raider or anything else coming in with no order: banked where it lands
			goHome(s, p, p.to);
			break;
		}
	}

	static void wave(State s, SwarmKnobs k, Parcel p) {
		p.done = true;
		WaveOrder o = p.order instanceof WaveOrder ? (WaveOrder) p.order : new WaveOrder();
		boolean first = !held(s, p.to);
		Hive h = s.foundHive(p.to, hiveName(s, p.to), 1);
		if (o.bootstrap) {
			h.bank = HiveRules.seedEndowment(HiveRules.ENDOWMENT_DAYS, h.size, k.fpUnit);
			h.swarms.add(p.fp);
			h.book();
			SwarmEconomy.place(s, h, o.role == 0 ? Hive.FORGE : o.role == 1 ? Hive.FUELPLANT
					: o.role == 2 ? Hive.REFINING : Hive.MINING);
			h.forgeBuilding = 0f;
			h.fuelPlantBuilding = 0f;
			if (first) rollExpandable(s, p.to, SwarmFit.OG_CHAIN);
		} else {
			if (first) rollExpandable(s, p.to, 1);
			SwarmEconomy.plan(s, k, h);
		}
		see(s, p.to);
	}

	/** A strike's order; one loaded in flight with none gets its kind's default. */
	static StrikeOrder orderOf(State s, Parcel p) {
		if (p.order instanceof StrikeOrder) return (StrikeOrder) p.order;
		StrikeOrder o;
		// a strike loaded in flight: its target by id, else the largest world where it lands
		o = new StrikeOrder();
		o.target = p.targetId != null ? s.world(p.targetId) : null;
		if (o.target == null) {
			for (World w : s.worlds) {
				if (w.lost || w.sys != p.to) continue;
				if (o.target == null || w.size > o.target.size) o.target = w;
			}
		}
		for (Hive h : s.hives) if (!h.dead && h.sys == p.from && (o.home == null || h.size > o.home.size)) o.home = h;
		p.order = o;
		if (o.target != null) {
			p.targetId = o.target.id;
			s.swarm.struck.add(o.target.id);
		}
		return o;
	}

	static void strike(State s, SwarmKnobs k, Parcel p) {
		StrikeOrder o = orderOf(s, p);
		World w = o.target;
		if (w == null || w.lost) {
			goHome(s, p, p.to);
			return;
		}
		see(s, p.to);
		float strength = strength(p);
		if (w.defence >= strength * k.breakOff) {
			s.count("strikesBrokenOff", 1);
			s.log("Strike broke off at " + w);
			goHome(s, p, p.to);
			return;
		}
		float lost = p.fp * BattleRules.lossShare(strength, w.defence);
		w.defence *= 1f - BattleRules.lossShare(w.defence, strength);
		p.fp -= lost;
		SwarmPosture.noteTrend(s, lost, 0f);
		s.count("strikesLanded", 1);
		if (w.forwardBase) {
			s.loseWorld(w, true, "station destroyed by a Threat strike");
			goHome(s, p, p.to);
			return;
		}
		p.holding = true;
		o.groundEnd = s.day + SwarmFit.groundDays(s.rng);
	}

	static void reinforcement(State s, Parcel p) {
		p.done = true;
		if (p.order instanceof Rebank) {
			Hive home = ((Rebank) p.order).home;
			if (home == null || home.dead) {
				home = null;
				for (Hive h : s.hives) {
					if (h.dead) continue;
					if (home == null || h.sys.ly(p.to) < home.sys.ly(p.to)) home = h;
				}
			}
			if (home != null) home.bank += p.fp;
			return;
		}
		Hive to = p.targetId != null ? s.hive(p.targetId) : null;
		if (to == null || to.dead) {
			to = null;
			for (Hive h : s.hivesIn(p.to)) if (to == null || h.size > to.size) to = h;
		}
		if (to == null) {
			// its colony fell while it flew: on to the nearest one still standing
			p.done = false;
			goHome(s, p, p.to);
			return;
		}
		to.swarms.add(p.fp);
		to.book();
		to.lastReceivedDay = s.day;
	}
}
