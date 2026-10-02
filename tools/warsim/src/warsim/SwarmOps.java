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
		/** On a Defend station over the front it landed or reinforced (ThreatSwarmDefend), until that front ends. */
		boolean defending;
		/** warsim_basesHold: holding a forward base's orbit with its guard sunk, and for how many days (a station siege). */
		boolean besieging;
		int siegeDays;
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
		alarmDecay(s, k);
		for (Swarm.Landing l : s.swarm.landings.values()) l.gateOpen = s.rng.nextFloat() < SwarmFit.INVADED_GATE_SHARE;
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

	/**
	 * The swarm's daily sight (ThreatSwarmIntel): a world is seen as it stands while the swarm is in its system - a
	 * hive's garrison, or any Threat fleet there (a wave, a strike, a scout) - and its last picture stands and ages
	 * after (the user's decision of 2026-10-02, no radar). The old Bastion radar (every hive system within
	 * warsim_swarmRadarLY) only while that simulator switch is > 0, kept for comparison.
	 */
	static void radar(State s, SwarmKnobs k) {
		List<StarSys> systems = SwarmEconomy.hiveSystems(s);
		java.util.Set<StarSys> present = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<StarSys, Boolean>());
		present.addAll(systems);
		for (Parcel p : s.parcels) if (!p.done && p.threat() && (p.holding || p.arrived)) present.add(p.to);
		for (World w : s.worlds) {
			if (w.lost) continue;
			boolean inRange = !k.fog || present.contains(w.sys);
			if (!inRange && k.radarLY > 0f) for (StarSys sys : systems) if (sys.ly(w.sys) <= k.radarLY) inRange = true;
			if (inRange) s.swarm.seen.put(w.id, new float[] { s.day, defenceOf(w) });
		}
	}

	static void see(State s, StarSys sys) {
		for (World w : s.worlds) if (!w.lost && w.sys == sys) s.swarm.seen.put(w.id, new float[] { s.day, defenceOf(w) });
	}

	/** What a strike meets: the world's own defence and, at a forward base, the garrison the human side keeps there. */
	static float defenceOf(World w) { return w.defence + w.guardFP * SwarmFit.STRIKE_UNITS_PER_FP; }

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

	/**
	 * The structure the i-th landing of an opening chain of n brings (WaveOrder.role). Five or more: the fitted chain,
	 * forge, fuel plant, refining, then mines. Four: forge, refining, mines. Fewer: a forge and mines - the mod's
	 * landings mine wherever there are deposits, the forge is the one build it forces (SEED_FORGE_KEY), and the first
	 * refinery and fuel plant are bought when a second industry slot opens (SwarmEconomy.choose).
	 */
	static int chainRole(int i, int n) {
		if (n >= SwarmFit.OG_CHAIN) return i;
		if (i == 0) return 0;
		return n == 4 && i == 1 ? 2 : 3;
	}

	/** Seeded systems whose 120 days are up send their wave; one the stocks cannot pay waits, its demand booked. */
	static void launchClaims(State s, SwarmKnobs k) {
		for (Swarm.Claim c : new ArrayList<Swarm.Claim>(s.swarm.claims)) {
			if (s.day - c.day < k.seedToColonyDays) continue;
			if (c.bootstrap) {
				s.swarm.claims.remove(c);
				int chain = k.ogChain > 0 ? k.ogChain : SwarmFit.OG_CHAIN;
				s.log("Bootstrap: " + chain + " waves to " + c.sys);
				for (int i = 0; i < chain; i++) {
					Parcel p = s.send(Parcel.THREAT, Parcel.Kind.WAVE, c.sys, c.sys, SwarmFit.bootstrapSwarmFP(s.rng),
							SwarmFit.bootstrapTravelDays(s.rng));
					WaveOrder o = new WaveOrder();
					o.bootstrap = true;
					o.role = chainRole(i, chain);
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
		s.count("swarmSupplies.founding", supplies);
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
			if (n == null || n <= 0 || SwarmEconomy.pressed(s, sys)) continue;
			// ThreatColonyManager.tryExpandInSystem (since 2026-09-29): a wave per unclaimed planet in every held
			// system, as many as the nearest ready forges have swarms and stock for - not one a pass
			while (n > 0 && launchWave(s, k, sys)) {
				n--;
				s.swarm.expandable.put(sys.id, n);
				s.count("wavesInSystem", 1);
			}
		}
	}

	/** trySpread: one claim a tick while fewer are pending than the posture's cap, on the best-weighted system. */
	static void trySpread(State s, SwarmKnobs k) {
		int pending = 0;
		for (Swarm.Claim c : s.swarm.claims) if (!c.bootstrap) pending++;
		float share = StanceRules.expansionShare(s.swarm.stance, k.secondaryShare);
		// round 20 trial (warsim_consolidateExpansionShare): a consolidating swarm keeps claiming at this share
		boolean leansAway = s.swarm.stance == Swarm.EXPAND;
		if (s.swarm.stance == Swarm.CONSOLIDATE && k.consolidateExpansion > 0f) {
			share = Math.max(share, k.consolidateExpansion);
			leansAway = true;
		}
		int cap = PostureRules.claimCap(freeForges(s, k), s.swarm.appetite, share);
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
			if (leansAway && rival != null) weight *= StanceRules.spreadMult(dRival);
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

	// ---- ThreatAlarm: the swarm turns on whoever is hurting it ----

	/** ThreatAlarm.raise, called by the human side: a stratum taken on a hive, a hive eradicated. */
	static void alarm(State s, String factionId, float points) {
		if (factionId == null || points <= 0f) return;
		Float g = s.swarm.grudge.get(factionId);
		s.swarm.grudge.put(factionId, (g == null ? 0f : g) + points);
	}

	/** ThreatAlarm.targetMult: the strike weight of a faction's worlds, 1 at no grudge. */
	static float targetMult(State s, SwarmKnobs k, String factionId) {
		Float g = s.swarm.grudge.get(factionId);
		return !k.alarm || g == null ? 1f : 1f + g * k.alarmTargetMult;
	}

	/** ThreatAlarm.decay, on the tick. */
	static void alarmDecay(State s, SwarmKnobs k) {
		float keep = 1f - Math.max(0f, Math.min(1f, k.alarmDecayPer30));
		for (String id : new ArrayList<String>(s.swarm.grudge.keySet())) {
			float next = s.swarm.grudge.get(id) * keep;
			if (next < 0.05f) s.swarm.grudge.remove(id);
			else s.swarm.grudge.put(id, next);
		}
	}

	/** The human side's hook for a stratum a front took on a hive (alarmPerStratum). */
	static void stratumTaken(State s, String factionId) {
		alarm(s, factionId, s.knobs.f("threatinc_alarmPerStratum"));
	}

	/**
	 * IncursionManager.retaliate, called by the human side when a hive is eradicated: the grudge,
	 * then an immediate strike at the winning faction from the nearest hive system that can muster
	 * one and reach a world of theirs ("Retaliation: X -> Y", 15 in pd9a for 24 hives lost).
	 */
	static void hiveLost(State s, String factionId, StarSys near) {
		SwarmKnobs k = new SwarmKnobs(s.knobs);
		if (k.alarm) alarm(s, factionId, k.alarmPerEradication);
		if (!k.retaliation || !k.alarm || SwarmPosture.phase(s) < 2) return;
		List<StarSys> systems = SwarmEconomy.hiveSystems(s);
		final StarSys at = near;
		Collections.sort(systems, new Comparator<StarSys>() {
			public int compare(StarSys a, StarSys b) { return Float.compare(a.ly(at), b.ly(at)); }
		});
		for (StarSys from : systems) {
			if (strikeFrom(s, k, from, factionId)) {
				s.count("retaliations", 1);
				return;
			}
		}
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
		for (StarSys from : systems) strikeFrom(s, k, from, null);
	}

	/** One hive system's strike (pickStrikeStaging, pickStrikeTarget, launchStrike); `only` restricts it to a faction's worlds. */
	static boolean strikeFrom(State s, SwarmKnobs k, StarSys from, String only) {
		{
			if (SwarmEconomy.pressed(s, from)) return false;
			List<float[]> spare = spares(s, k, from);
			// pickStrikeStaging: a strike musters at least two swarms above the reserves
			if (spare.size() < 2) return false;
			float full = spare.size() * SwarmFit.STRIKE_UNITS_PER_SWARM;
			List<World> picks = new ArrayList<World>();
			List<Float> weights = new ArrayList<Float>();
			float total = 0f;
			// relief before offensives (strikeReliefFirst): a front of the swarm's losing ground takes the strike
			List<World> relief = new ArrayList<World>();
			List<Float> reliefWeights = new ArrayList<Float>();
			float reliefTotal = 0f;
			for (World w : s.worlds) {
				if (!strikeable(s, w)) continue;
				if (only != null && !only.equals(w.faction)) continue;
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
				float weight = strikeValue(k, w) * targetMult(s, k, w.faction) / k.strikeDays(from.ly(w.sys));
				// ThreatGroundFronts.wantsExpedition: a front the garrison is beating (here: one on a colony at war)
				Swarm.Landing front = s.swarm.landings.get(w.id);
				if (front != null && !front.falls && front.endDay != Integer.MIN_VALUE) {
					// strikeOutweighed: an invaded colony at war is fed marines and relief, and most months the gate passes it over
					if (!front.gateOpen) continue;
					weight *= Math.max(1f, k.reinforceWeight);
					if (k.reliefFirst) {
						relief.add(w);
						reliefWeights.add(weight);
						reliefTotal += weight;
					}
				}
				weight *= mult;
				if (weight <= 0f) continue;
				picks.add(w);
				weights.add(weight);
				total += weight;
			}
			if (!relief.isEmpty()) {
				picks = relief;
				weights = reliefWeights;
				total = reliefTotal;
			}
			if (picks.isEmpty()) return false;
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
			if (k.strikeSizedMargin > 0f) {
				// round 20 trial (warsim_strikeSizedMargin): the swarms the defence last seen calls for, the largest first
				int need = (int) Math.ceil(s.swarm.seen.get(w.id)[1] * k.strikeSizedMargin / SwarmFit.STRIKE_UNITS_PER_SWARM);
				int sized = Math.max(2, Math.min(count, need));
				if (sized < count) s.count("strikesSized", 1);
				count = sized;
			}
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
				return false;
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
			s.count("strikesLaunched", 1);
			s.log("Strike: " + (int) fp + " FP in " + count + " swarms from " + from + " at " + w + " ("
					+ (int) ly + " ly, defence " + (int) s.swarm.seen.get(w.id)[1] + ")");
			return true;
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
			// ThreatSwarmScouts.pickStaging: a hive of strikeMinSize with its nexus up (warsim_scoutsAnySize: the simulator before round 20)
			boolean nexus = false;
			for (Hive h : s.hivesIn(from)) if (h.nexusUp() && (h.size >= k.strikeMinSize || k.scoutsAnySize)) nexus = true;
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

	/**
	 * A strike's landing pass (ThreatStrikeFGI's "Strike pass (landing)" / "(reinforce)"): the troops go down
	 * and the hulls stay over the world while the front lives (defend). Troops are SwarmFit.TROOPS_PER_FP of what reached the orbit; under
	 * LANDING_MIN_TROOPS the landing is called off ("Strike landing at X aborted").
	 */
	static boolean land(State s, Parcel p, World w) {
		float troops = p.fp * SwarmFit.TROOPS_PER_FP;
		if (troops < SwarmFit.LANDING_MIN_TROOPS) {
			s.count("strikeLandingsAborted", 1);
			return false;
		}
		Swarm.Landing l = s.swarm.landings.get(w.id);
		if (l != null) {
			// the next expedition reinforces the front: the garrison needs that much longer to reach 2:1
			l.troops += troops;
			if (!l.falls && !l.engine && l.endDay != Integer.MIN_VALUE) l.endDay += SwarmFit.reinforcedDays(s.rng);
			s.count("threatReinforcePasses", 1);
			s.log("Strike pass (reinforce) vs " + w.name + ": " + (int) troops + " troops");
			return true;
		}
		l = new Swarm.Landing();
		l.troops = troops;
		l.landedDay = s.day;
		s.swarm.landings.put(w.id, l);
		s.count("threatLandings", 1);
		s.log("Front deployed at " + w.name + " (threat): " + (int) troops + " troops");
		return true;
	}

	/**
	 * ThreatSwarmDefend: a strike that landed or reinforced a Threat front stays over the world, no term,
	 * until the front ends; away from home it burns supplies all the while (SwarmEconomy, burns). The
	 * world is free for the next strike's reinforcing pass.
	 */
	static void defend(State s, Parcel p) {
		release(s, p);
		orderOf(s, p).defending = true;
		p.holding = true;
		s.count("defendStations", 1);
	}

	/**
	 * The day of the Threat's fronts (ThreatGroundFronts.tickFront on a Threat-owned front). A
	 * colony whose faction is at war holds an armed reserve and counter-attacks until it has the
	 * 2:1 that overruns the beachhead; one with no reserve (not mobilised, pirates, the Path)
	 * loses its last district after SwarmFit.groundDays. Decided the day after the landing, when
	 * the strike has mobilised whoever it can.
	 */
	static void fronts(State s, SwarmKnobs k) {
		if (s.swarm.landings.isEmpty()) return;
		for (String id : new ArrayList<String>(s.swarm.landings.keySet())) {
			Swarm.Landing l = s.swarm.landings.get(id);
			World w = s.world(id);
			if (w == null || w.lost) {
				s.swarm.landings.remove(id);
				continue;
			}
			if (l.endDay == Integer.MIN_VALUE) {
				if (s.day <= l.landedDay) continue;
				l.falls = !s.faction(w.faction).mobilised;
				if (!l.falls && k.coloniesFall) {
					// warsim_coloniesFall: the front engine instead of the overrun clock; the strike gate reads an open-ended front
					l.engine = true;
					l.endDay = Integer.MAX_VALUE;
					l.pushing = l.troops * s.knobs.f("threatinc_frontLandingMult")
							* BattleRules.overrunOdds(s.knobs.f("threatinc_groundStrengthExponent")) >= colonyDefence(s, w, 0, false);
					s.count("threatFrontsEngine", 1);
				} else {
					l.endDay = l.landedDay + (l.falls ? SwarmFit.groundDays(s.rng) : SwarmFit.overrunDays(s.rng));
				}
			}
			if (l.engine) {
				String end = threatFrontDay(s, w, l);
				if (end == null) continue;
				s.swarm.landings.remove(id);
				if (!end.equals("victory")) {
					s.count(end.equals("overrun") ? "beachheadsOverrun" : "threatFrontsCollapsed", 1);
					SwarmPosture.noteTrend(s, l.troops / SwarmFit.TROOPS_PER_FP * 0.5f, 0f);
					s.log("Threat front at " + w.name + " " + end);
					continue;
				}
				s.log("Threat front took the last district of " + w.name);
				s.count("coloniesFallen", 1);
				s.loseWorld(w, true, "ground assault (front engine)");
				if (k.conquestConverts && s.rng.nextFloat() < SwarmFit.CONQUEST_HIVE_SHARE) {
					Hive h = s.foundHive(w.sys, w.name, k.conquestSize);
					rollExpandable(s, w.sys, 1);
					SwarmEconomy.plan(s, k, h);
				}
				continue;
			}
			if (s.day < l.endDay) continue;
			s.swarm.landings.remove(id);
			if (!l.falls) {
				s.count("beachheadsOverrun", 1);
				SwarmPosture.noteTrend(s, l.troops / SwarmFit.TROOPS_PER_FP * 0.5f, 0f);
				s.log("Counter-attack at " + w.name + " overran the beachhead");
				continue;
			}
			s.loseWorld(w, true, "ground assault");
			if (k.conquestConverts && s.rng.nextFloat() < SwarmFit.CONQUEST_HIVE_SHARE) {
				Hive h = s.foundHive(w.sys, w.name, k.conquestSize);
				rollExpandable(s, w.sys, 1);
				SwarmEconomy.plan(s, k, h);
			}
		}
	}

	/**
	 * warsim_basesHold: a day of a station siege. A guard back on station (HumanBases.garrison's relief) fights the
	 * besiegers as a strike is fought: one that outweighs them lifts the siege, one that does not is sunk. With no guard
	 * the siege counts a day; at SwarmFit.STATION_SIEGE_DAYS the station falls.
	 */
	static void stationSiegeDay(State s, SwarmKnobs k, Parcel p, StrikeOrder o) {
		World w = o.target;
		if (w == null || w.lost) {
			goHome(s, p, p.to);
			return;
		}
		if (w.guardFP >= 1f) {
			float strength = strength(p);
			float defence = defenceOf(w);
			boolean lifted = defence >= strength;
			float lost = p.fp * BattleRules.lossShare(strength, defence);
			float worn = 1f - BattleRules.lossShare(defence, strength);
			w.guardFP *= worn;
			w.reliefFP *= worn;
			p.fp -= lost;
			SwarmPosture.noteTrend(s, lost, 0f);
			if (lifted) {
				s.count("stationSiegesLifted", 1);
				s.log("Station siege of " + w.name + " lifted by its relief");
				goHome(s, p, p.to);
				return;
			}
			w.guardFP = 0f;
			s.count("stationReliefsSunk", 1);
		}
		o.siegeDays++;
		s.count("stationSiegeDays", 1);
		if (o.siegeDays < SwarmFit.STATION_SIEGE_DAYS) return;
		s.loseWorld(w, true, "station destroyed after a " + o.siegeDays + "-day siege");
		goHome(s, p, p.to);
	}

	/**
	 * warsim_coloniesFall: a colony's ground strength over the districts left - its own ground defence
	 * (SwarmFit.COLONY_GROUND_PER_SIZE a size) and the reserve's armed marines, whole when holding the line and at
	 * MARINE_COUNTER_ATTACK_MULT when counter-attacking (ThreatGroundFronts' defenderStrength / counterAttackStrength).
	 */
	static float colonyDefence(State s, World w, int held, boolean counterAttack) {
		float marines = w.hasReserve ? Math.max(0f, w.stock[World.MARINES]) * s.knobs.f("threatinc_reserveDefenseMult") : 0f;
		if (counterAttack) marines *= SwarmFit.MARINE_COUNTER_ATTACK_MULT;
		float d = SwarmFit.COLONY_GROUND_PER_SIZE * w.size + marines;
		return d * Math.max(0, w.size - held) / (float) Math.max(1, w.size) * s.knobs.f("threatinc_groundDefenseMult");
	}

	/** warsim_coloniesFall: the Threat front's troops as fighting strength (HumanSiege.eff without armaments: needsArms false). */
	static float threatEff(State s, Swarm.Landing l) {
		float landing = s.knobs.f("threatinc_frontLandingMult");
		float full = s.knobs.f("threatinc_frontEntrenchMaxMult");
		float dug = Math.min(1f, l.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
		return l.troops * (landing + (full - landing) * dug);
	}

	/**
	 * warsim_coloniesFall: one day of a Threat front on a colony at war, HumanSiege.frontDay mirrored (tickFront on a
	 * Threat-owned front): no armaments, pushing losses x threatPushLossMult, the defenders bleeding
	 * defenderLossPer30Days of the engaged and defenderCounterAttackLossFraction per counter-attack out of the reserve's
	 * marines (ThreatReserves.spendDefendingMarines). Returns "victory", "overrun", "collapsed" or null.
	 */
	static String threatFrontDay(State s, World w, Swarm.Landing l) {
		float exponent = s.knobs.f("threatinc_groundStrengthExponent");
		float hold = s.knobs.f("threatinc_frontHoldFraction");
		float clamp = Math.max(1f, s.knobs.f("threatinc_counterAttackRatioClamp"));
		float odds = BattleRules.overrunOdds(exponent);
		float d = colonyDefence(s, w, l.strataHeld, false);
		float ca = colonyDefence(s, w, l.strataHeld, true);
		float e = threatEff(s, l);
		// shouldBrace: a front holding no ground that the next counter-attack would overrun digs in
		if (l.pushing && l.strataHeld == 0 && ca > e * odds) l.pushing = false;
		boolean exposed = l.pushing && l.checkpointLeft <= 0f;
		float loss = (exposed ? s.knobs.f("threatinc_frontPushLossPer30Days") * s.knobs.f("threatinc_threatPushLossMult")
				: s.knobs.f("threatinc_frontMarineLossPer30Days")) / 30f;
		l.troops -= l.troops * Math.min(1f, loss);
		if (l.troops < s.knobs.f("threatinc_frontMinMarines")) return "collapsed";
		if (!exposed) l.entrenchDays += 1f;
		// the defenders bleed on the frontage (engaged = the smaller force), out of the reserve's marines
		float engaged = Math.min(w.stock[World.MARINES], l.troops);
		if (w.hasReserve && engaged > 0f) {
			w.stock[World.MARINES] = Math.max(0f, w.stock[World.MARINES] - engaged * s.knobs.f("threatinc_defenderLossPer30Days") / 30f);
		}
		e = threatEff(s, l);
		d = colonyDefence(s, w, l.strataHeld, false);
		ca = colonyDefence(s, w, l.strataHeld, true);
		if (l.pushing) {
			if (l.checkpointLeft > 0f) {
				l.checkpointLeft -= 1f;
			} else if (e >= d * hold) {
				float pace = Math.max(0.5f, Math.min(3f, (float) Math.pow(d / Math.max(1f, e), exponent)));
				l.pushDays += 1f / pace;
				if (l.pushDays >= s.knobs.f("threatinc_frontPushBaseDays")) {
					l.pushDays = 0f;
					l.strataHeld++;
					l.checkpointLeft = s.knobs.f("threatinc_frontCheckpointDays");
					s.count("threatStrataTaken", 1);
					if (l.strataHeld >= w.size) return "victory";
					s.log("Threat front took district " + l.strataHeld + "/" + w.size + " at " + w.name);
				}
			}
		}
		// the colony's counter-attack, the sooner the more it outweighs the front
		float tempo = Math.max(1f / clamp, Math.min(clamp, (float) Math.pow(ca / Math.max(1f, e), exponent)));
		float body = Math.max(0, w.size - l.strataHeld) / (float) Math.max(1, w.size);
		l.counterClock += Math.max(0.25f, body) * tempo;
		if (l.counterClock >= s.knobs.f("threatinc_frontCounterAttackDays")) {
			l.counterClock = 0f;
			float cover = exposed ? 1f : 1f + (s.knobs.f("threatinc_frontEntrenchDefenseBonus") - 1f)
					* Math.min(1f, l.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
			float guard = e * cover;
			if (w.hasReserve && engaged > 0f) {
				w.stock[World.MARINES] = Math.max(0f, w.stock[World.MARINES] - engaged * s.knobs.f("threatinc_defenderCounterAttackLossFraction"));
			}
			if (ca > guard) {
				s.count("colonyCounterAttacks", 1);
				l.troops *= 1f - s.knobs.f("threatinc_frontCounterAttackLossFraction");
				l.entrenchDays *= s.knobs.f("threatinc_frontEntrenchKeptFraction");
				if (l.strataHeld > 0) {
					l.strataHeld--;
					l.pushDays = 0f;
					l.pushing = false;
				} else if (ca > guard * odds) {
					return "overrun";
				}
			}
		}
		// the stance: push whenever strong enough, dig in otherwise
		if (!l.pushing && threatEff(s, l) >= d * hold && !(l.strataHeld == 0 && ca > threatEff(s, l) * odds)) l.pushing = true;
		return null;
	}

	/** The day's strikes: the fronts, then any strike a dump loaded already holding lands what it carries. */
	static void daily(State s, SwarmKnobs k) {
		if (!s.liveHives().isEmpty()) {
			s.count(s.swarm.stance == Swarm.CONSOLIDATE ? "monthsConsolidate" : s.swarm.stance == Swarm.PRESS ? "monthsPress"
					: "monthsExpand", 1f / 30f);
		}
		fronts(s, k);
		for (Parcel p : new ArrayList<Parcel>(s.parcels)) {
			if (!p.threat() || p.kind != Parcel.Kind.STRIKE || !p.arrived || !p.holding) continue;
			StrikeOrder o = orderOf(s, p);
			if (p.done || p.fp < 1f) {
				p.done = true;
				release(s, p);
				continue;
			}
			if (o.besieging) {
				stationSiegeDay(s, k, p, o);
				continue;
			}
			if (o.defending) {
				if (o.target == null || o.target.lost || !s.swarm.landings.containsKey(o.target.id)) goHome(s, p, p.to);
				else s.count("defendFPDays", p.fp);
				continue;
			}
			if (o.target != null && !o.target.lost && !o.target.forwardBase && land(s, p, o.target)) defend(s, p);
			else goHome(s, p, p.to);
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
			// warsim_ogChain: a home with no planet to spare (the chain took them all)
			if (first) rollExpandable(s, p.to, k.ogChain > 0 ? Math.max(k.ogChain, p.to.planets) : SwarmFit.OG_CHAIN);
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
		// a strike is hidden until it arrives: the faction suffers it here, and its scouts get the lead
		Faction f = s.faction(w.faction);
		f.strikesSuffered++;
		f.lastStruckDay = s.day;
		f.lastStrikeFrom = p.from;
		float strength = strength(p);
		float defence = defenceOf(w);
		if (defence >= strength * k.breakOff) {
			s.count("strikesBrokenOff", 1);
			s.log("Strike broke off at " + w);
			goHome(s, p, p.to);
			return;
		}
		float lost = p.fp * BattleRules.lossShare(strength, defence);
		float worn = 1f - BattleRules.lossShare(defence, strength);
		// the fight costs the fleets that met it (ThreatAbstractBattle.fought), not the ground defence the gate reads
		w.guardFP *= worn;
		w.reliefFP *= worn;
		p.fp -= lost;
		SwarmPosture.noteTrend(s, lost, 0f);
		s.count("strikesLanded", 1);
		if (w.forwardBase) {
			// the station falls when the strike outmatches what stood there; else the guard has held it
			if (strength > defence) {
				w.guardFP = 0f;
				if (k.basesHold) {
					// warsim_basesHold: the guard is sunk, the station stands; the strike holds the orbit (stationSiegeDay)
					o.besieging = true;
					o.siegeDays = 0;
					p.holding = true;
					s.count("stationSieges", 1);
					s.log("Station siege of " + w.name + " begun by " + (int) p.fp + " FP");
					return;
				}
				s.loseWorld(w, true, "station destroyed by a Threat strike");
			} else s.count("strikesHeldOff", 1);
			goHome(s, p, p.to);
			return;
		}
		if (land(s, p, w)) defend(s, p);
		else goHome(s, p, p.to);
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
