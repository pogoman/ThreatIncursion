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
		// warsim_invadedGateShare (SwarmFit.INVADED_GATE_SHARE 0.1, fitted on pd9a before relief guards entered the gate
		// through defenceOf; the hw4 runs reinforced 86 fronts against 114 new ones in hw4g)
		float invadedShare = s.knobs.f("warsim_invadedGateShare", SwarmFit.INVADED_GATE_SHARE);
		for (Swarm.Landing l : s.swarm.landings.values()) l.gateOpen = s.rng.nextFloat() < invadedShare;
		launchClaims(s, k);
		if (s.liveHives().isEmpty()) return;
		// -v: each hive's swarms against its reserve, to set beside the dumps' garrisonFleets (2026-10-03)
		if (s.verbose) for (Hive h : s.liveHives())
			s.log("Hive " + h.name + " in " + h.sys.id + ": size " + h.size + ", swarms " + h.swarms.size() + ", reserve "
					+ SwarmEconomy.reserve(h) + ", spare " + SwarmEconomy.available(s, k, h) + ", held "
					+ (int) h.garrisonFP + " of " + (int) SwarmEconomy.want(s, k, h));
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
			if (inRange) s.swarm.seen.put(w.id, new float[] { s.day, defenceOf(s, w) });
		}
	}

	static void see(State s, StarSys sys) {
		for (World w : s.worlds) if (!w.lost && w.sys == sys) s.swarm.seen.put(w.id, new float[] { s.day, defenceOf(s, w) });
	}

	/**
	 * What a strike meets: the world's own defence and the human guards. The game's gate
	 * (IncursionManager.liveTargetDefence) reads every hostile fleet in the system, so a guard covers each world
	 * there: warsim_gateSystemGuards sums the system's guards (off: the world's own). In the hw4d/f/g dumps a world
	 * with no guard in its system kept its day-5 gate (median ratio 0.9-1.1 to day 3,600), one with a guard read
	 * 1.1-1.5 units per guard FP over it (warsim_guardUnitsPerFP; 2.1 is the Threat strike's rate, the old reading).
	 */
	static float defenceOf(State s, World w) {
		return (w.gate >= 0f ? w.gate : w.defence) + guardsAt(s, w) * s.knobs.f("warsim_guardUnitsPerFP", SwarmFit.STRIKE_UNITS_PER_FP);
	}

	/** The human guard FP a strike at the world meets: its own, or with warsim_gateSystemGuards its system's. */
	static float guardsAt(State s, World w) {
		if (!s.knobs.b("warsim_gateSystemGuards", false)) return w.guardFP;
		float sum = 0f;
		for (World o : s.worlds) if (!o.lost && o.sys == w.sys) sum += o.guardFP;
		return sum;
	}

	/** An arrival fight's wear on the guards that met it: the world's own, or with warsim_gateSystemGuards its system's. */
	static void wearGuards(State s, World w, float worn) {
		boolean system = s.knobs.b("warsim_gateSystemGuards", false);
		for (World o : s.worlds) {
			if (o != w && (!system || o.lost || o.sys != w.sys)) continue;
			o.guardFP *= worn;
			o.reliefFP *= worn;
		}
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
	 * for its homeWorlds planets; the most planets any has, down to three, when none does) at least
	 * half as far from the nearest world as the farthest such system, picked at random.
	 */
	static StarSys pickOG(State s, int homeWorlds) {
		if (s.swarm.ogSystemId != null && s.systems.get(s.swarm.ogSystemId) != null) {
			return s.systems.get(s.swarm.ogSystemId);
		}
		List<StarSys> viable = new ArrayList<StarSys>();
		StarSys most = null;
		float maxDist = -1f;
		for (int worlds = homeWorlds; worlds >= 3 && viable.isEmpty(); worlds--) {
			for (StarSys sys : s.systems.values()) {
				if (inhabited(s, sys)) continue;
				if (most == null || sys.planets > most.planets) most = sys;
				if (sys.planets < worlds) continue;
				viable.add(sys);
				maxDist = Math.max(maxDist, nearestWorldLY(s, sys));
			}
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
	 * refinery and fuel plant are bought when a second industry slot opens (SwarmEconomy.choose). minesOnly
	 * (warsim_homeMinesOnly): a forge and mines whatever n - round 22 found the makeup makes no difference at four.
	 */
	static int chainRole(int i, int n, boolean minesOnly) {
		if (i == 0) return 0;
		if (minesOnly) return 3;
		if (n >= SwarmFit.OG_CHAIN) return i;
		return n == 4 && i == 1 ? 2 : 3;
	}

	/** ThreatColonyManager.pickChainPlanets: the opening lands on homeWorlds planets of the home, or on all it has when it has fewer. */
	static int homeChain(SwarmKnobs k, StarSys home) {
		return home.planets > 0 ? Math.min(k.homeWorlds, home.planets) : k.homeWorlds;
	}

	/** Seeded systems whose 120 days are up send their wave; one the stocks cannot pay waits, its demand booked. */
	static void launchClaims(State s, SwarmKnobs k) {
		for (Swarm.Claim c : new ArrayList<Swarm.Claim>(s.swarm.claims)) {
			if (s.day - c.day < k.seedToColonyDays) continue;
			if (c.bootstrap) {
				s.swarm.claims.remove(c);
				int chain = homeChain(k, c.sys);
				s.log("Bootstrap: " + chain + " waves to " + c.sys);
				for (int i = 0; i < chain; i++) {
					Parcel p = s.send(Parcel.THREAT, Parcel.Kind.WAVE, c.sys, c.sys, SwarmFit.bootstrapSwarmFP(s.rng),
							SwarmFit.bootstrapTravelDays(s.rng));
					WaveOrder o = new WaveOrder();
					o.bootstrap = true;
					o.role = chainRole(i, chain, k.homeMinesOnly);
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
		boolean noSupplies = !SwarmEconomy.canPay(s, Swarm.SUPPLIES, supplies);
		boolean noFuel = !SwarmEconomy.canPay(s, Swarm.FUEL, fuel);
		boolean ok = !noSupplies && !noFuel;
		// ThreatFuel.held("a Seeding Swarm from .."): one booking a source a SHORT_DAYS, of both bills
		if (!ok && SwarmEconomy.bookHold(s, k, "wave " + source.name)) {
			if (noSupplies) SwarmEconomy.noteDemand(s, Swarm.SUPPLIES, supplies);
			if (noFuel) SwarmEconomy.noteDemand(s, Swarm.FUEL, fuel);
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
				if (!s.faction(w.faction).mobilised && !neverMobilises(s, w.faction)) {
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

	/**
	 * A faction the swarm strikes before it is at war with it and whose worlds keep no armed reserve: the game's
	 * threatinc_warExcludedFactions (IncursionManager.warOpen, pirates). warsim_pathNeverMobilises adds the Path, as the
	 * simulator had it before round 24.
	 */
	static boolean neverMobilises(State s, String factionId) {
		if (s.knobs.b("warsim_pathNeverMobilises", false)) return SwarmFit.neverMobilises(factionId);
		return HumanIntel.excluded(s, factionId);
	}

	static boolean strikeable(State s, World w) {
		if (w.lost || s.swarm.struck.contains(w.id)) return false;
		if (!w.forwardBase && w.size < 3) return false;
		int phase = SwarmPosture.phase(s);
		if (phase < 2) return false;
		if (phase < 3) {
			if (w.size >= 6 && !w.forwardBase) return false;
			if (!s.faction(w.faction).mobilised && !neverMobilises(s, w.faction)) return false;
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
			// pickStrikeTarget's walk (warsim_strikeWholeMuster): the muster is the swarms the spare supplies keep away
			// for the days to the nearest world it could strike, and it sails whole or not at all
			int muster = spare.size();
			float musterFP = 0f, unpaid = 0f;
			if (k.wholeMuster) {
				float faced = Float.MAX_VALUE;
				for (World w : s.worlds) if (strikeable(s, w)) faced = Math.min(faced, from.ly(w.sys));
				if (faced == Float.MAX_VALUE) return false;
				muster = 0;
				for (float[] sp : spare) {
					if (!SwarmEconomy.canSustain(s, k, musterFP + sp[1], k.strikeDays(faced))) break;
					musterFP += sp[1];
					muster++;
				}
				if (muster <= 0) {
					s.count("strikesHeld", 1);
					return false;
				}
			}
			float full = muster * SwarmFit.STRIKE_UNITS_PER_SWARM;
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
				if (seen == null) continue;
				if (k.wholeMuster) {
					// the passage there and back for the whole muster comes out of the fuel stock, or the world is no candidate
					float passage = k.passage(musterFP, from.ly(w.sys), true);
					if (!SwarmEconomy.canPay(s, Swarm.FUEL, passage)) {
						// a world the strike would take but for its fuel (past the gate): the cheapest is booked below
						if (seen[1] < full * k.breakOff && (unpaid <= 0f || passage < unpaid)) unpaid = passage;
						continue;
					}
				}
				if (seen[1] >= full * k.breakOff) continue;
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
			if (picks.isEmpty()) {
				if (unpaid > 0f) {
					// every world it would strike waits on fuel: the cheapest passage is demand, once a SHORT_DAYS a
					// source (IncursionManager.pickStrikeTarget, threatinc_strikeWaitBooksFuel, 2026-10-02; before, the
					// game booked nothing - hw4 logged no "strike from .. held" in 115 months). Every poll before round 26
					if (!k.holdsBookMonthly) SwarmEconomy.noteDemand(s, Swarm.FUEL, unpaid);
					else if (k.strikeWaitBooksFuel && only == null && SwarmEconomy.bookHold(s, k, "strike " + from))
						// ThreatFuel.heldShort: the shortfall alone (warsim_strikeWaitBooksWhole: the whole passage, run hw4d's jar)
						SwarmEconomy.noteDemand(s, Swarm.FUEL, k.strikeWaitBooksWhole ? unpaid
								: Math.max(0f, unpaid - SwarmEconomy.stock(s, Swarm.FUEL)));
					s.count("strikesHeldForFuel", 1);
				}
				return false;
			}
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
			int count = muster;
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
				if (!SwarmEconomy.canPay(s, Swarm.FUEL, k.passage(one, ly, true))
						&& SwarmEconomy.bookHold(s, k, "strike " + from)) {
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
		return land(s, p, w, p.fp * SwarmFit.TROOPS_PER_FP, Float.MAX_VALUE) > 0f;
	}

	/**
	 * One world's landing out of a strike's troop pool (ThreatStrikeFGI.availableLanding, then beachheadLanding): it
	 * puts down the world's share of the pool or what its beachhead needs, whichever is more, out of what is still
	 * aboard; a beachhead the troops aboard cannot make is broken out of the hulls or held back. Returns the troops
	 * put down, 0 when none.
	 */
	static float land(State s, Parcel p, World w, float aboard, float share) {
		float troops = Math.min(aboard, share);
		if (troops < SwarmFit.LANDING_MIN_TROOPS) {
			s.count("strikeLandingsAborted", 1);
			return 0f;
		}
		Swarm.Landing l = s.swarm.landings.get(w.id);
		if (l != null) {
			// the next expedition reinforces the front: the garrison needs that much longer to reach 2:1
			reinforceFront(s, l, troops, true);
			if (!l.falls && !l.engine && l.endDay != Integer.MIN_VALUE) l.endDay += SwarmFit.reinforcedDays(s.rng);
			s.count("threatReinforcePasses", 1);
			s.log("Strike pass (reinforce) vs " + w.name + ": " + (int) troops + " troops");
			return troops;
		}
		if (s.knobs.b("warsim_beachheadRule", true)) {
			// ThreatStrikeFGI.beachheadLanding: a first landing is sized to outlast the world's first counter-attack, the
			// shortfall broken out of the strike's hulls; a strike without the hulls for it is held back
			float need = beachheadTroops(s, w);
			if (troops < need) {
				float carried = Math.min(aboard, need);
				if (carried < need) {
					s.count("beachheadsShort", 1);
					s.count("beachheadsShortAboard", carried / Math.max(1f, need));
					s.log("Beachhead at " + w.name + ": needs " + (int) need + ", " + (int) carried + " aboard");
					float perFP = Math.max(0.01f, s.knobs.f("threatinc_fabricateTroopsPerFP"));
					float fp = (need - carried) / perFP;
					if (!s.knobs.b("threatinc_fabricateEnabled", true) || fp > p.fp - GUARD_SPARED_FP) {
						s.count("strikeLandingsHeldBack", 1);
						return 0f;
					}
					p.fp -= fp;
					s.count("fpFabricatedBeachhead", fp);
				}
				troops = need;
			}
		}
		l = new Swarm.Landing();
		l.troops = troops;
		l.wear = landingWear(s);
		if (vet(s)) l.level = landingLevel(s);
		l.landedDay = s.day;
		s.swarm.landings.put(w.id, l);
		s.count("threatLandings", 1);
		s.log("Front deployed at " + w.name + " (threat): " + (int) troops + " troops"
				+ (s.verbose ? " [need " + (int) beachheadTroops(s, w) + ", ca " + (int) colonyDefence(s, w, 0, 1f, true) + ", garrison " + (int) w.garrison + ", marines " + (int) w.stock[World.MARINES] + "]" : ""));
		return troops;
	}

	/**
	 * The strike's sweep (IncursionManager's strike params, FGRaidType.SEQUENTIAL): the expedition works through every
	 * eligible world in the target system, the picked one first, and each takes the world's share of the troop pool -
	 * an even split, never under threatinc_strikeFrontMinTroops - or its beachhead, whichever is more. A strike for a
	 * front of its own goes to it alone (strikeReliefFirst). Returns the first world landed or reinforced, which its
	 * guard stays over, or null. 2026-10-03: the game's fronts started at 720 troops (hw4f median), the simulator's at
	 * 2,000 when it put the whole strike down on its target. Off by default (warsim_strikeSweep): the game's first world
	 * takes the whole pool (median 0 left aboard after a landing in hw4e and hw4f), and with the sweep on the simulator's
	 * larger strikes (1.5-2x the game's swarms late in the war) land 1.5-1.7x the game's landings on all three runs.
	 */
	static World sweep(State s, Parcel p, World target) {
		if (!s.knobs.b("warsim_strikeSweep", false) || s.swarm.landings.containsKey(target.id))
			return land(s, p, target) ? target : null;
		List<World> worlds = new ArrayList<World>();
		worlds.add(target);
		int phase = SwarmPosture.phase(s);
		for (World o : s.worlds) {
			if (o == target || o.lost || o.sys != target.sys || s.swarm.struck.contains(o.id)) continue;
			if (phase < 3 && (o.size >= 6 && !o.forwardBase
					|| !s.faction(o.faction).mobilised && !neverMobilises(s, o.faction))) continue;
			worlds.add(o);
		}
		float aboard = p.fp * SwarmFit.TROOPS_PER_FP;
		float share = Math.max(s.knobs.f("threatinc_strikeFrontMinTroops"), aboard / worlds.size());
		World first = null;
		for (World w : worlds) {
			if (w.forwardBase) continue;
			if (aboard < SwarmFit.LANDING_MIN_TROOPS) break;
			float put = land(s, p, w, aboard, share);
			if (put <= 0f) continue;
			aboard -= put;
			if (first == null) first = w;
		}
		if (worlds.size() > 1) s.count("strikeSweepWorlds", worlds.size());
		return first;
	}

	/**
	 * ThreatSwarmDefend: a strike that landed or reinforced a Threat front stays over the world, no term,
	 * until the front ends; away from home it burns supplies all the while (SwarmEconomy, burns). The
	 * world is free for the next strike's reinforcing pass.
	 */
	static void defend(State s, SwarmKnobs k, Parcel p) {
		release(s, p);
		// the game leaves one fleet - the first, largest pack - over the landing and sends the rest home
		// (ThreatStrikeFGI.stayOnDefend / guardUnspawned); warsim_guardSwarmsPerFleet 0 parks the whole strike
		if (k.guardSwarmsPerFleet > 0f) {
			int swarms = Math.max(1, orderOf(s, p).swarms);
			int fleets = Math.max(1, Math.round(swarms / k.guardSwarmsPerFleet));
			float stay = p.fp * Math.min(1f, k.guardFirstPackMult / fleets);
			if (stay < p.fp - 1f) {
				float rest = p.fp - stay;
				p.fp0 *= stay / p.fp;
				p.fp = stay;
				Hive home = orderOf(s, p).home;
				if (home == null || home.dead) {
					home = null;
					for (Hive h : s.hives) if (!h.dead && (home == null || h.sys.ly(p.to) < home.sys.ly(p.to))) home = h;
				}
				if (home != null) {
					Parcel back = s.send(Parcel.THREAT, Parcel.Kind.REINFORCEMENT, p.to, home.sys, rest, 0);
					Rebank r = new Rebank();
					r.home = home;
					back.order = r;
				}
				s.count("guardFPSentHome", rest);
			}
		}
		s.count("guardFPStayed", p.fp);
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
				// the day after the humans have answered the landing (HumanSide.daily mobilises the struck faction the day
				// after it; warsim_landingRace decides a day early, before it has)
				if (s.day <= l.landedDay + (k.landingRace ? 0 : 1)) continue;
				l.falls = !s.faction(w.faction).mobilised;
				boolean fed = k.guardFeedsFront && !guards(s, w).isEmpty();
				if (!l.falls && (k.coloniesFall || fed)) {
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
				if (k.guardFeedsFront) feed(s, k, w, l);
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
			float defence = defenceOf(s, w);
			boolean lifted = defence >= strength;
			float lost = p.fp * BattleRules.lossShare(strength, defence);
			float worn = 1f - BattleRules.lossShare(defence, strength);
			wearGuards(s, w, worn);
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
		return colonyDefence(s, w, held, 1f, counterAttack);
	}

	/**
	 * The garrison a holding Threat front leaves standing: a front that holds suppresses the world's key structures
	 * (Theatre.keyStructures - ground defences, batteries, the military), and vanilla's ground defence falls with them.
	 * warsim_frontHoldSuppress 0.65 is fitted to the dumps (2026-10-03): a colony under a holding front kept 0.26 of its
	 * pre-war garrison in hw4d and 0.40 in hw4c, under a grinding one about all of it.
	 */
	static float suppressed(State s, Swarm.Landing l) {
		if (l == null) return 1f;
		float most = Math.max(0f, Math.min(1f, s.knobs.f("warsim_frontHoldSuppress", 0.65f)));
		if (wearClock(s)) return 1f - most * Math.min(1f, Math.max(0f, l.wear) / wearDays(s));
		return l.holding ? 1f - most : 1f;
	}

	/**
	 * warsim_wearClock (2026-10-03): the garrison follows the key structures' disruption clock, as the game's does
	 * (fortificationCondition, a structure's condition falling with its clock over defenseWearDays), not the front's
	 * holding state. A holding front wears the key structures and a grinding one the ground defences and batteries
	 * (ThreatGroundFronts' tick, the simulator wearing all of them for either), frontWearRate x e / (e + d) days a day,
	 * and the clock runs down a day a day - so a front that stops holding keeps the garrison down while it grinds, and
	 * after that for as long as the clock takes to run out. Off, suppression lifted the day the front stopped holding:
	 * the garrison came back x2.9 at once and the next counter-attack overran the front (sv-h3: Yama's 532 -> 1,114).
	 */
	static boolean wearClock(State s) {
		return s.knobs.b("warsim_wearClock", false);
	}

	static float wearDays(State s) {
		return Math.max(1f, s.knobs.f("threatinc_defenseWearDays", 300f));
	}

	/** The clock a landing finds: orbit has worn the structures to where the beachhead was sized (warsim_landingSuppress). */
	static float landingWear(State s) {
		float most = Math.max(0.01f, Math.min(1f, s.knobs.f("warsim_frontHoldSuppress", 0.65f)));
		return wearDays(s) * Math.min(1f, s.knobs.f("warsim_landingSuppress", 0.65f) / most);
	}

	/** warsim_wearClock: a day of ThreatGroundFronts' state and suppression on a Threat front. */
	static void wearDay(State s, World w, Swarm.Landing l) {
		if (l.wear < 0f) l.wear = landingWear(s);
		float d = colonyDefence(s, w, l.strataHeld, suppressed(s, l), false);
		float e = threatEff(s, l);
		float band = Math.max(0f, Math.min(0.5f, s.knobs.f("threatinc_frontStateHysteresis")));
		float holdLine = d * s.knobs.f("threatinc_frontHoldFraction") * (l.state == 2 ? 1f - band : 1f);
		float grindLine = d * s.knobs.f("threatinc_frontGrindFraction") * (l.state >= 1 ? 1f - band : 1f);
		l.state = e >= holdLine ? 2 : e >= grindLine ? 1 : 0;
		l.holding = l.state == 2;
		if (l.state > 0) l.wear += s.knobs.f("threatinc_frontWearRate") * e / Math.max(1f, e + d);
		l.wear = Math.max(0f, Math.min(wearDays(s) * 1.2f, l.wear - 1f));
	}

	static float colonyDefence(State s, World w, int held, float garrisonMult, boolean counterAttack) {
		float marines = w.hasReserve ? Math.max(0f, w.stock[World.MARINES]) * s.knobs.f("threatinc_reserveDefenseMult") : 0f;
		if (vet(s)) marines *= 1f + colonyLevel(w) * s.knobs.f("threatinc_marineVeterancyEffectMax", 1f);
		if (counterAttack) marines *= SwarmFit.MARINE_COUNTER_ATTACK_MULT;
		// warsim_colonyGarrison (true): the dump's garrison (ThreatGroundFronts.colonyGarrison), else the old guess of 15 a size
		float garrison = s.knobs.b("warsim_colonyGarrison", true) && w.garrison >= 0f ? w.garrison : SwarmFit.COLONY_GROUND_PER_SIZE * w.size;
		float d = garrison * garrisonMult + marines;
		return d * Math.max(0, w.size - held) / (float) Math.max(1, w.size) * s.knobs.f("threatinc_groundDefenseMult");
	}

	/**
	 * ThreatGroundFronts.beachheadTroops: the troops whose landing strength (frontLandingMult) beats the world's first
	 * counter-attack (colonyDefence's counter-attack figure) by threatinc_siegeBeachheadMargin at the overrun odds.
	 * The game sizes it when the strike lands, after its bombardment has suppressed the key structures (readyToLand
	 * waits for orbitSpent), so vanilla's garrison is read suppressed: warsim_landingSuppress, 0.65 like a holding
	 * front (2026-10-03: Kazeron's 4,300 garrison counter-attacked at about 770 when hw4e landed, 18%; the needs the
	 * game logged were 20-50% of the unsuppressed figure, and the simulator came up short on 69% of its landings
	 * against the game's 26%).
	 */
	static float beachheadTroops(State s, World w) {
		float margin = s.knobs.f("threatinc_siegeBeachheadMargin");
		if (margin <= 0f) return 0f;
		float odds = BattleRules.overrunOdds(s.knobs.f("threatinc_groundStrengthExponent"));
		float left = 1f - Math.max(0f, Math.min(1f, s.knobs.f("warsim_landingSuppress", 0.65f)));
		return colonyDefence(s, w, 0, left, true) * margin / Math.max(0.01f, s.knobs.f("threatinc_frontLandingMult") * odds);
	}

	/**
	 * warsim_reliefToInvaded: a relief arriving over a Threat front fights the swarm's guard there (the game's relief meets
	 * the Defend fleet, on screen or off). The guards' strength is warsim_guardUnitsPerFP a FP against the guard's
	 * strength(); each side loses BattleRules.lossShare as in an arrival fight, and a Threat guard outweighed goes home.
	 */
	static void reliefFight(State s, World w) {
		List<Parcel> gs = guards(s, w);
		if (gs.isEmpty() || w.guardFP < 1f) return;
		float strength = 0f;
		for (Parcel p : gs) strength += strength(p);
		float defence = w.guardFP * s.knobs.f("warsim_guardUnitsPerFP", SwarmFit.STRIKE_UNITS_PER_FP);
		float lostShare = BattleRules.lossShare(strength, defence);
		float worn = 1f - BattleRules.lossShare(defence, strength);
		float lost = 0f;
		for (Parcel p : gs) {
			lost += p.fp * lostShare;
			p.fp -= p.fp * lostShare;
		}
		w.guardFP *= worn;
		w.reliefFP *= worn;
		SwarmPosture.noteTrend(s, lost, 0f);
		if (defence > strength) {
			for (Parcel p : gs) goHome(s, p, p.to);
			s.count("reliefInvaded.lifted", 1);
			s.log("Relief over " + w.name + " drove off the swarm's guard (" + (int) defence + " vs " + (int) strength + ")");
		} else {
			s.count("reliefInvaded.beaten", 1);
		}
	}

	/** The strikes on a Defend station over the world's Threat front (ThreatSwarmDefend). */
	static List<Parcel> guards(State s, World w) {
		List<Parcel> out = new ArrayList<Parcel>();
		for (Parcel p : s.parcels) {
			if (p.done || !p.threat() || p.kind != Parcel.Kind.STRIKE || !p.holding || !(p.order instanceof StrikeOrder)) continue;
			StrikeOrder o = (StrikeOrder) p.order;
			if (o.defending && o.target == w) out.add(p);
		}
		return out;
	}

	/**
	 * ThreatGroundFronts.fabricateTroops: a guard over a Threat front that cannot hold (frontCanHold: effective strength
	 * under the hold line) breaks its hulls into troops, threatinc_fabricateTroopsPerFP a point, up to the hold line x
	 * threatinc_fabricateHoldMargin (holdGap, at the front's footing). It spares a hull or two (the flagship and the last
	 * hull); the battery toll on the drop is not charged. The game also waits until orbit has done what it can (orbitDoneFor).
	 */
	static void feed(State s, SwarmKnobs k, World w, Swarm.Landing l) {
		if (!k.fabricate || !s.knobs.b("threatinc_fabricateDefendEnabled", true)
				|| !s.knobs.b("warsim_guardFabricates", true)) return;
		float want = colonyDefence(s, w, l.strataHeld, suppressed(s, l), false) * s.knobs.f("threatinc_frontHoldFraction");
		float mult = Math.max(0.01f, threatMult(s, l) * vetEffect(s, l));
		if (l.troops * mult >= want) return;
		float gap = Math.max(0f, want * k.fabricateHoldMargin - l.troops * mult) / mult;
		for (Parcel p : guards(s, w)) {
			if (gap <= 0f) break;
			float give = Math.min(gap / k.fabricateTroopsPerFP, p.fp - GUARD_SPARED_FP);
			if (give <= 0f) continue;
			p.fp -= give;
			reinforceFront(s, l, give * k.fabricateTroopsPerFP, false);
			gap -= give * k.fabricateTroopsPerFP;
			s.count("fpFabricated", give);
			s.count("troopsFabricated", give * k.fabricateTroopsPerFP);
		}
	}

	/**
	 * Troops joining a Threat front (ThreatGroundFronts.resupply - a strike's reinforcing pass, or hulls a guard broke
	 * up): they come in at npcLandingVeterancy, the merged level the weighted average. warsim_reinforceDilutes
	 * (2026-10-03): they have not dug the cover the front has, so its entrenchment dilutes in the same proportion, as the
	 * game's does; and the level dilutes on the guard's break-up too, which only a strike's pass did before.
	 */
	static void reinforceFront(State s, Swarm.Landing l, float troops, boolean pass) {
		if (troops <= 0f) return;
		float before = Math.max(0f, l.troops);
		boolean dilutes = s.knobs.b("warsim_reinforceDilutes", false);
		if (dilutes) l.entrenchDays *= before / Math.max(1f, before + troops);
		if (vet(s) && (pass || dilutes)) l.level = (l.level * before + landingLevel(s) * troops) / Math.max(1f, before + troops);
		l.troops = before + troops;
	}

	/** The FP a guard keeps when it breaks up its hulls: the flagship and the last hull (fabricateTroops' canGive). */
	static final float GUARD_SPARED_FP = 8f;

	/** The front's strength per troop: frontLandingMult, rising to frontEntrenchMaxMult as it digs in (entrenchMult). */
	static float threatMult(State s, Swarm.Landing l) {
		float landing = s.knobs.f("threatinc_frontLandingMult");
		float full = s.knobs.f("threatinc_frontEntrenchMaxMult");
		float dug = Math.min(1f, l.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
		return landing + (full - landing) * dug;
	}

	/** warsim_coloniesFall: the Threat front's troops as fighting strength (HumanSiege.eff without armaments: needsArms false). */
	static float threatEff(State s, Swarm.Landing l) {
		float landing = s.knobs.f("threatinc_frontLandingMult");
		float full = s.knobs.f("threatinc_frontEntrenchMaxMult");
		float dug = Math.min(1f, l.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
		return l.troops * (landing + (full - landing) * dug) * vetEffect(s, l);
	}

	/** warsim_veterancy (ThreatMarineXP, 2026-09-08 in the mod): fronts and colony marines carry a level, off: none. */
	static boolean vet(State s) { return s.knobs.b("warsim_veterancy", false); }

	static float landingLevel(State s) { return Math.max(0f, Math.min(1f, s.knobs.f("threatinc_npcLandingVeterancy", 0.15f))); }

	/** ThreatMarineXP.effectMult at the front's level. */
	static float vetEffect(State s, Swarm.Landing l) {
		return vet(s) ? 1f + l.level * s.knobs.f("threatinc_marineVeterancyEffectMax", 1f) : 1f;
	}

	/** ThreatMarineXP.lossMult at a level. */
	static float vetLoss(State s, float level) {
		return vet(s) ? Math.max(0.05f, 1f - level * s.knobs.f("threatinc_marineVeterancyLossReduction", 0.5f)) : 1f;
	}

	/** The colony's marine level, diluted by the raw marines that came in since it was last read. */
	static float colonyLevel(World w) {
		float now = Math.max(0f, w.stock[World.MARINES]);
		if (now > w.marineSeen && now > 0f) w.marineLevel *= w.marineSeen / now;
		w.marineSeen = now;
		return w.marineLevel;
	}

	/** ThreatMarineXP.xpGain as a level: the side that was outmatched learns the most. */
	static float levelGain(State s, float own, float enemy) {
		return (1f - own / Math.max(1f, own + enemy)) * s.knobs.f("threatinc_marineXpPerBattle", 0.2f);
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
		if (l.wear < 0f) l.wear = landingWear(s);
		float d = colonyDefence(s, w, l.strataHeld, suppressed(s, l), false);
		float ca = colonyDefence(s, w, l.strataHeld, suppressed(s, l), true);
		float e = threatEff(s, l);
		// shouldBrace: a front holding no ground that the next counter-attack would overrun digs in. warsim_braceAfterAttrition
		// (2026-10-03): the game asks after the day's attrition and the defenders' bleed (ThreatGroundFronts' tick), so a front
		// at 1.99:1 that bleeds past 2 braces; asked before, it pushed on and the day's counter-attack overran it at 2.03. On since
		// 2026-10-03 05:00: across hw4d-h the check rose 1,735 -> 1,794 inside, the war rows' distance 177 -> 171
		boolean braceAfter = s.knobs.b("warsim_braceAfterAttrition", true);
		if (!braceAfter && l.pushing && l.strataHeld == 0 && ca > e * odds) l.pushing = false;
		boolean exposed = l.pushing && l.checkpointLeft <= 0f;
		float loss = (exposed ? s.knobs.f("threatinc_frontPushLossPer30Days") * s.knobs.f("threatinc_threatPushLossMult")
				: s.knobs.f("threatinc_frontMarineLossPer30Days")) / 30f;
		l.troops -= l.troops * Math.min(1f, loss * vetLoss(s, l.level));
		if (l.troops < s.knobs.f("threatinc_frontMinMarines")) return "collapsed";
		if (!exposed && !braceAfter) l.entrenchDays += 1f;
		// the defenders bleed on the frontage (engaged = the smaller force), out of the reserve's marines
		float engaged = Math.min(w.stock[World.MARINES], l.troops);
		if (w.hasReserve && engaged > 0f) {
			w.stock[World.MARINES] = Math.max(0f, w.stock[World.MARINES] - engaged * s.knobs.f("threatinc_defenderLossPer30Days") / 30f
					* (vet(s) ? vetLoss(s, colonyLevel(w)) : 1f));
		}
		e = threatEff(s, l);
		d = colonyDefence(s, w, l.strataHeld, suppressed(s, l), false);
		ca = colonyDefence(s, w, l.strataHeld, suppressed(s, l), true);
		if (braceAfter) {
			if (l.pushing && l.strataHeld == 0 && ca > e * odds) {
				l.pushing = false;
				exposed = false;
			}
			if (!exposed) l.entrenchDays += 1f;
		}
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
		// warsim_counterAttackPace: the game's colony paces on stability/10 and its military command
		// (Theatre.COLONY.counterAttackInterval), which the simulator does not hold; fitted to the hw4d-g logs, where the
		// later counter-attacks came every 25-28 days (pace 0.9-1.05 backed out at the tempo) and the first after a
		// landing at 57-60 (pace 0.39-0.52: the invasion's shock), against the simulator's 18 and 25 at pace 1.
		// On since 2026-10-03: across hw4d-h the check rose 1,718 -> 1,735 inside and the medians' distance fell 1,111 -> 1,027
		float pace = s.knobs.f("warsim_counterAttackPace", 0.68f) * (l.counterAttacks == 0 ? s.knobs.f("warsim_firstCounterAttackPace", 0.55f) : 1f);
		l.counterClock += Math.max(0.25f, body) * tempo * pace;
		if (l.counterClock >= s.knobs.f("threatinc_frontCounterAttackDays")) {
			l.counterClock = 0f;
			l.counterAttacks++;
			float cover = exposed ? 1f : 1f + (s.knobs.f("threatinc_frontEntrenchDefenseBonus") - 1f)
					* Math.min(1f, l.entrenchDays / Math.max(1f, s.knobs.f("threatinc_frontEntrenchDays")));
			float guard = e * cover;
			if (w.hasReserve && engaged > 0f) {
				w.stock[World.MARINES] = Math.max(0f, w.stock[World.MARINES] - engaged * s.knobs.f("threatinc_defenderCounterAttackLossFraction")
						* (vet(s) ? vetLoss(s, colonyLevel(w)) : 1f));
			}
			if (vet(s)) {
				// what each side learns is fixed by the fight it walked into (ThreatGroundFronts' counter-attack)
				l.level = Math.min(1f, l.level + levelGain(s, guard, ca));
				if (w.hasReserve) w.marineLevel = Math.min(1f, colonyLevel(w) + levelGain(s, ca, guard));
			}
			if (ca > guard) {
				s.count("colonyCounterAttacks", 1);
				if (s.verbose) s.log("Counter-attack at " + w.name + (l.strataHeld == 0 && ca > guard * odds ? " overran" : " battered")
						+ " the beachhead (" + (int) ca + " vs " + (int) guard + ")"
						+ " [troops " + (int) l.troops + ", garrison " + (int) w.garrison + " x " + suppressed(s, l) + ", marines " + (int) w.stock[World.MARINES]
						+ ", holding " + l.holding + ", pushing " + l.pushing + ", dug " + (int) l.entrenchDays + ", day " + (s.day - l.landedDay) + "]");
				l.troops *= 1f - s.knobs.f("threatinc_frontCounterAttackLossFraction") * vetLoss(s, l.level);
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
		if (wearClock(s)) wearDay(s, w, l);
		else l.holding = threatEff(s, l) >= colonyDefence(s, w, l.strataHeld, suppressed(s, l), false) * hold;
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
			// a strike nobody is near never spawns its fleets (the NPC war's every strike), so nothing stays over the
			// landing: it ends and is re-banked at home ("Strike ledger: ended unspawned, N of M FP re-banked")
			World put = o.target != null && !o.target.lost && !o.target.forwardBase ? sweep(s, p, o.target) : null;
			if (put != null && k.strikeDefends) {
				o.target = put;
				defend(s, k, p);
			} else goHome(s, p, p.to);
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
			// the home's planets past the chain are taken later (tryExpandInSystem)
			if (first) rollExpandable(s, p.to, homeChain(k, p.to));
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
		float defence = defenceOf(s, w);
		if (defence >= strength * k.breakOff) {
			s.count("strikesBrokenOff", 1);
			s.log("Strike broke off at " + w);
			goHome(s, p, p.to);
			return;
		}
		float lost = p.fp * BattleRules.lossShare(strength, defence);
		float worn = 1f - BattleRules.lossShare(defence, strength);
		// the fight costs the fleets that met it (ThreatAbstractBattle.fought), not the ground defence the gate reads
		wearGuards(s, w, worn);
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
		// warsim_strikeDefends: only a spawned strike stays over its landing; the NPC war's go home (see daily)
		World put = sweep(s, p, w);
		if (put != null && k.strikeDefends) {
			o.target = put;
			defend(s, k, p);
		} else goHome(s, p, p.to);
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
