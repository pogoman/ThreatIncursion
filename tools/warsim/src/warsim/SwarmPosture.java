package warsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import threatinc.rules.PostureRules;
import threatinc.rules.StrikeRules;

/**
 * The defensive posture and the stance, every postureDays (ThreatPosture.poll,
 * ThreatStance.evaluate): pressure per hive system, what each colony wants to hold, the
 * modes, the appetite for expansion, surplus recycling and reinforcement. The decay, claim
 * cap, releasable and break-even formulas are threatinc.rules calls; the reading is modelled.
 */
final class SwarmPosture {
	private SwarmPosture() {}

	/** Garrison FP lost in a system: feeds its pressure and the stance's exchange ledger. */
	static void noteLoss(State s, StarSys sys, float fp) {
		if (fp <= 0f) return;
		float[] l = s.swarm.losses.get(sys.id);
		float had = l == null ? 0f : l[0] * PostureRules.decay(s.day - l[1], SwarmFit.DECAY_DAYS);
		s.swarm.losses.put(sys.id, new float[] { had + fp, s.day });
		noteTrend(s, fp, 0f);
	}

	static void noteTrend(State s, float lost, float killed) {
		float[] t = trend(s);
		s.swarm.trendLost = t[0] + lost;
		s.swarm.trendKilled = t[1] + killed;
		s.swarm.trendDay = s.day;
	}

	static float[] trend(State s) {
		float k = (float) Math.exp(-Math.max(0f, s.day - s.swarm.trendDay) / SwarmFit.TREND_DAYS);
		return new float[] { s.swarm.trendLost * k, s.swarm.trendKilled * k };
	}

	static boolean attackKind(Parcel.Kind kind) {
		return kind == Parcel.Kind.SIEGE || kind == Parcel.Kind.SATURATION || kind == Parcel.Kind.HUNT
				|| kind == Parcel.Kind.SQUADRON;
	}

	/**
	 * The swarm's sweep for attacks (ThreatSwarmIntel.sweep, simplified): a siege, hunt or squadron bound for
	 * a hive system is seen by eyes once it is there and by radar inside swarmRadarRangeLY of it (every hive
	 * system taken as having radar, as SwarmOps.radar does); a contact counts for swarmContactDays after.
	 */
	static void sight(State s, SwarmKnobs k, List<StarSys> systems) {
		Swarm sw = s.swarm;
		float contactDays = s.knobs.f("threatinc_swarmContactDays");
		for (Parcel p : s.parcels) {
			if (p.done || p.threat() || !attackKind(p.kind) || !systems.contains(p.to)) continue;
			if (p.order instanceof HumanOrder && ((HumanOrder) p.order).returning) continue;
			boolean seen = p.holding || p.arrived || !k.fog;
			if (!seen) {
				float span = Math.max(1f, p.arriveDay - p.departDay);
				float left = p.from.ly(p.to) * Math.max(0f, Math.min(1f, (p.arriveDay - s.day) / span));
				seen = left <= k.radarLY;
			}
			if (!seen) continue;
			Swarm.Contact c = sw.contacts.get(p.id);
			if (c == null) sw.contacts.put(p.id, c = new Swarm.Contact());
			c.sys = p.to;
			c.day = s.day;
			c.fp = p.fp;
		}
		for (Iterator<Swarm.Contact> it = sw.contacts.values().iterator(); it.hasNext();) {
			if (s.day - it.next().day > contactDays) it.remove();
		}
	}

	static StarSys nearestHiveSystem(State s, List<StarSys> systems, StarSys from) {
		StarSys best = null;
		for (StarSys sys : systems) {
			if (!s.foundHiveSystems.contains(sys)) continue;
			if (best == null || sys.ly(from) < best.ly(from)) best = sys;
		}
		return best;
	}

	/** ThreatConvoys.stagingHive: the nearest found hive system in the base's reach that no nearer base of its faction serves. */
	static StarSys stagingHive(State s, World base) {
		List<World> bases = new ArrayList<World>();
		for (World w : s.worldsOf(base.faction)) if (w.base && w.hasReserve) bases.add(w);
		return stagingHive(s, base, bases);
	}

	/** With the faction's bases (HumanPools.nearestBase's candidates, in s.worlds order) already listed. */
	static StarSys stagingHive(State s, World base, List<World> bases) {
		float range = HumanPools.rangeLY(s, base);
		StarSys best = null;
		for (StarSys sys : s.foundHiveSystems) {
			if (!s.hasHive(sys)) continue;
			float d = base.sys.ly(sys);
			if (d > range || (best != null && d >= base.sys.ly(best))) continue;
			World nearest = null;
			for (World w : bases) if (nearest == null || w.sys.ly(sys) < nearest.sys.ly(sys)) nearest = w;
			if (nearest != base) continue;
			best = sys;
		}
		return best;
	}

	static void poll(State s, SwarmKnobs k) {
		Swarm sw = s.swarm;
		List<StarSys> systems = SwarmEconomy.hiveSystems(s);

		// what the enemy lost over our systems, for the exchange ledger
		Map<Integer, Float> now = new HashMap<Integer, Float>();
		float killed = 0f;
		for (Parcel p : s.parcels) {
			if (p.done || p.threat() || !p.holding || !systems.contains(p.to)) continue;
			now.put(p.id, p.fp);
			Float was = sw.hostileFP.get(p.id);
			if (was != null && was > p.fp) killed += was - p.fp;
		}
		sw.hostileFP.clear();
		sw.hostileFP.putAll(now);
		if (killed > 0f) noteTrend(s, 0f, killed);

		float sectorHeld = 0f, sectorBase = 0f;
		Map<String, float[]> sums = new HashMap<String, float[]>();
		Map<String, Float> inbound = SwarmEconomy.inboundMap(s);
		for (StarSys sys : systems) {
			float held = 0f, bank = 0f, base = 0f, floor = 0f;
			for (Hive h : s.hivesIn(sys)) {
				held += SwarmEconomy.held(h, inbound);
				bank += Math.max(0f, h.bank);
				base += SwarmEconomy.baseFP(s, k, h);
				floor += SwarmEconomy.floorFP(s, h);
			}
			sums.put(sys.id, new float[] { held, bank, base, floor });
			sectorHeld += held;
			sectorBase += base;
		}

		for (Iterator<String> it = sw.post.keySet().iterator(); it.hasNext();) {
			String id = it.next();
			boolean live = false;
			for (StarSys sys : systems) if (sys.id.equals(id)) live = true;
			if (!live) it.remove();
		}
		sw.posture.clear();

		sight(s, k, systems);
		float contactDays = s.knobs.f("threatinc_swarmContactDays");
		float half = Math.max(1f, s.knobs.f("threatinc_intelHalfLifeDays"));

		float quietSpare = 0f, quietWant = 0f, sumPressure = 0f;
		int forges = 0, forgesCovered = 0, pressed = 0, attackedSystems = 0;
		float bill = SwarmFit.FOUNDING_BILL_STRUCTURES * k.foundingFPPerStructure;
		// per seen world, once a poll and not once per system (round 9): its nearest hive system and the hive it stages for
		Map<World, StarSys> nearestHive = new java.util.IdentityHashMap<World, StarSys>();
		Map<World, StarSys> stagingOf = new java.util.IdentityHashMap<World, StarSys>();
		Map<String, List<World>> basesOf = new HashMap<String, List<World>>();
		for (World w : s.worlds) {
			if (w.lost || !w.base || !w.hasReserve) continue;
			List<World> bases = basesOf.get(w.faction);
			if (bases == null) basesOf.put(w.faction, bases = new ArrayList<World>());
			bases.add(w);
		}
		for (World w : s.worlds) {
			if (w.lost || !sw.seen.containsKey(w.id)) continue;
			if (w.forwardBase && w.guardFP > 0f) nearestHive.put(w, nearestHiveSystem(s, systems, w.sys));
			if (!w.base || !w.hasReserve) continue;
			Faction f = s.factions.get(w.faction);
			if (f == null || !f.mobilised) continue;
			stagingOf.put(w, stagingHive(s, w, basesOf.get(w.faction)));
		}
		for (StarSys sys : systems) {
			float[] sum = sums.get(sys.id);
			float held = sum[0], bank = sum[1], base = sum[2], floor = sum[3];
			// ThreatPosture.read: max(attacks, staged) + losses + hostiles + forward
			float attacks = 0f, staged = 0f, hostiles = 0f, forward = 0f;
			// attacks: what the swarm has seen bound for the system within swarmContactDays (ThreatSwarmIntel.contactsOn)
			for (Swarm.Contact c : sw.contacts.values()) {
				if (c.sys == sys && s.day - c.day <= contactDays) attacks += c.fp;
			}
			for (Parcel p : s.parcels) {
				if (p.done || p.threat() || !p.holding) continue;
				if (p.kind == Parcel.Kind.MUSTER && p.against == sys) staged += p.fp;
				// hostiles: fleets in the system no attack counted (guards, relief, convoys, scouts)
				else if (p.to == sys && !attackKind(p.kind)) hostiles += p.fp;
			}
			// staged: per faction, the most any one seen base staging for this system could pay a siege
			// here from its own stock, by the sighting's trust (stagedBy / siegeCapacityFP, no donors)
			Map<String, Float> byFaction = new HashMap<String, Float>();
			for (World w : s.worlds) {
				if (w.lost) continue;
				float[] seen = sw.seen.get(w.id);
				if (seen == null) continue;
				float trust = (float) Math.pow(0.5, Math.max(0f, s.day - seen[0]) / half);
				if (w.forwardBase && w.guardFP > 0f && w.sys != sys && nearestHive.get(w) == sys
						&& w.sys.ly(sys) <= s.knobs.f("threatinc_frontlineKeepLY")) {
					// forward: a forward base's guards count toward its nearest hive system only (ThreatFrontlines.hiveNear)
					forward += w.guardFP * trust;
				}
				if (!w.base || !w.hasReserve) continue;
				Faction f = s.factions.get(w.faction);
				if (f == null || !f.mobilised || stagingOf.get(w) != sys) continue;
				float ly = w.sys.ly(sys);
				float cap = threatinc.rules.ReachRules.payablePoints(HumanPools.available(s, w, World.FUEL),
						HumanPools.available(s, w, World.SUPPLIES), ly * s.knobs.f("threatinc_expeditionFuelPerPointLY"),
						s.knobs.f("threatinc_expeditionSuppliesPerPoint")) * threatinc.rules.ReachRules.FP_PER_POINT * trust;
				if (cap >= Float.MAX_VALUE / 2f || cap <= 0f) continue;
				Float had = byFaction.get(w.faction);
				if (had == null || cap > had) byFaction.put(w.faction, cap);
			}
			float stagedCap = 0f;
			for (Float v : byFaction.values()) stagedCap += v;
			staged = Math.max(staged, stagedCap);
			float[] l = sw.losses.get(sys.id);
			float losses = l == null ? 0f : l[0] * PostureRules.decay(s.day - l[1], SwarmFit.DECAY_DAYS);
			float raw = Math.max(attacks, staged) + losses + hostiles + forward;
			if (s.verbose) {
				s.count("pressure.attacks", attacks);
				s.count("pressure.staged", staged);
				s.count("pressure.losses", losses);
				s.count("pressure.hostiles", hostiles);
				s.count("pressure.forward", forward);
			}
			Swarm.Post post = sw.post.get(sys.id);
			if (post == null) sw.post.put(sys.id, post = new Swarm.Post());
			float carried = post.day == Integer.MIN_VALUE ? 0f
					: post.pressure * PostureRules.decay(s.day - post.day, SwarmFit.DECAY_DAYS);
			float pressure = Math.max(raw, carried);
			if (pressure < 1f) pressure = 0f;
			float threat = pressure / k.orbitMargin;
			float need = threat * k.postureMargin;
			float gatherable = held + bank + Math.max(0f, (sectorHeld - held) - (sectorBase - base));
			if (threat > gatherable) need = 0f;

			boolean wounded = false;
			float want = 0f, surplus = 0f;
			for (Hive h : s.hivesIn(sys)) {
				float b = SwarmEconomy.baseFP(s, k, h);
				float share = floor > 0f ? need * SwarmEconomy.floorFP(s, h) / floor : 0f;
				h.needFP = share;
				h.wantFP = Math.max(b, share);
				want += h.wantFP;
				if (h.front != null) wounded = true;
				surplus += Math.max(0f, PostureRules.releasableFP(SwarmEconomy.held(s, h), h.wantFP, k.postureBand,
						SwarmEconomy.oneSwarmFP(s, h)));
			}
			float ratio = held > 0f ? threat / held : (threat > 0f ? Float.MAX_VALUE : 0f);
			int was = post.mode, mode;
			if (wounded) mode = 3;
			else if (ratio >= SwarmFit.THREATENED_ENTER || (was >= 2 && ratio >= SwarmFit.THREATENED_LEAVE)) mode = 2;
			else if (ratio >= SwarmFit.WATCHFUL_ENTER || (was >= 1 && ratio >= SwarmFit.WATCHFUL_LEAVE)) mode = 1;
			else mode = 0;

			post.pressure = pressure;
			post.want = want;
			post.need = need;
			post.held = held;
			post.bank = bank;
			post.mode = mode;
			post.day = s.day;
			post.attacked = attacks > 0f || hostiles > 0f;
			if (surplus > 0f) {
				if (Float.isNaN(post.surplusSince)) post.surplusSince = s.day;
			} else {
				post.surplusSince = Float.NaN;
			}
			sw.posture.put(sys.id, new float[] { pressure, mode });

			sumPressure += pressure;
			if (mode >= 2) pressed++;
			if (post.attacked) attackedSystems++;
			for (Hive h : s.hivesIn(sys)) {
				if (h.forge) forges++;
				if (mode > 1) continue;
				quietSpare += Math.max(0f, SwarmEconomy.held(s, h) - h.wantFP) + Math.max(0f, h.bank - h.wantFP);
				quietWant += h.wantFP;
				if (h.forge && SwarmEconomy.poolable(s, k, h) >= bill) forgesCovered++;
			}
		}
		sw.appetite = systems.isEmpty() ? -1f
				: (quietWant > 0f ? quietSpare / quietWant : 0f) + (forges > 0 ? forgesCovered / (float) forges : 0f);

		if (s.verbose && (s.day - s.startDay) % 30 < Math.max(1, (int) k.postureDays)) {
			float wantAll = 0f, bankAll = 0f;
			for (Hive h : s.liveHives()) {
				wantAll += h.wantFP;
				bankAll += h.bank;
			}
			s.log("Posture: held " + (int) sectorHeld + " want " + (int) wantAll + " bank " + (int) bankAll + " appetite "
					+ sw.appetite + " claims " + sw.claims.size() + " fuel " + (int) sw.fuel + " supplies " + (int) sw.supplies
					+ " spare " + (int) sw.spare + " away " + (int) sw.awayPerMonth);
		}
		if (k.stanceEnabled) stance(s, k, systems, sectorHeld, sumPressure, pressed, attackedSystems);
		recycleSurplus(s, k, systems);
		redistribute(s, k);
	}

	// ------------------------------------------------------------------
	// stance (ThreatStance.evaluate), the weak-target test simplified
	// ------------------------------------------------------------------

	static int phase(State s) {
		boolean big = false, forge = false, fuel = false, four = false;
		for (Hive h : s.hives) {
			if (h.dead) continue;
			if (h.size >= 6) big = true;
			if (h.size >= 4 && h.nexus) four = true;
			if (h.forge) forge = true;
			if (h.fuelPlant) fuel = true;
		}
		return big ? 3 : four && forge && fuel ? 2 : s.hives.isEmpty() ? 0 : 1;
	}

	static void stance(State s, SwarmKnobs k, List<StarSys> systems, float sumHeld, float pressure, int pressed,
			int attacked) {
		Swarm sw = s.swarm;
		float[] t = trend(s);
		int hivesNow = s.liveHives().size();
		for (Iterator<int[]> it = sw.hiveHistory.iterator(); it.hasNext();) {
			if (s.day - it.next()[0] > SwarmFit.HIVE_WINDOW_DAYS) it.remove();
		}
		int hiveDelta = sw.hiveHistory.isEmpty() ? 0 : hivesNow - sw.hiveHistory.get(0)[1];
		sw.hiveHistory.add(new int[] { s.day, hivesNow });
		boolean losing = t[0] >= SwarmFit.SIGNIFICANT_LOSS * Math.max(1f, sumHeld) && t[1] < t[0];

		int n = systems.size();
		float pressedShare = n > 0 ? pressed / (float) n : 0f;
		boolean hadState = sw.stanceSince != Integer.MIN_VALUE;
		int was = hadState ? sw.stance : Swarm.EXPAND;
		boolean breathing = attacked == 0 && (!hadState || sw.lastPressure < 0f || pressure <= sw.lastPressure);

		float pressNeed = was == Swarm.PRESS ? k.pressRatio * SwarmFit.STANCE_LEAVE : k.pressRatio;
		World best = null;
		float bestScore = 0f;
		if (phase(s) >= 2) {
			Map<String, Float> force = new HashMap<String, Float>();
			for (Parcel p : s.parcels) {
				if (p.done || p.threat()) continue;
				Float f = force.get(p.owner);
				force.put(p.owner, (f == null ? 0f : f) + p.fp);
			}
			for (StarSys sys : systems) {
				Swarm.Post post = sw.post.get(sys.id);
				if (post != null && post.mode >= 2) continue;
				float muster = SwarmOps.spares(s, k, sys).size() * SwarmFit.STRIKE_UNITS_PER_SWARM;
				if (muster <= 0f) continue;
				for (World w : s.worlds) {
					if (!SwarmOps.strikeable(s, w)) continue;
					float[] seen = sw.seen.get(w.id);
					if (seen == null) continue;
					Float theirs = force.get(w.faction);
					float theirFP = (theirs == null ? 0f : theirs) + seen[1] / SwarmFit.STRIKE_UNITS_PER_FP;
					float ratio = theirFP > 0f ? sumHeld / theirFP : Float.MAX_VALUE;
					if (ratio < pressNeed) continue;
					float odds = seen[1] / Math.max(1f, muster * k.breakOff);
					if (odds > k.weakOdds) continue;
					float score = SwarmOps.strikeValue(k, w) * (1f - odds) / k.strikeDays(sys.ly(w.sys));
					if (best == null || score > bestScore) {
						best = w;
						bestScore = score;
					}
				}
			}
		}

		float consolidateNeed = was == Swarm.CONSOLIDATE ? k.consolidateShare * SwarmFit.STANCE_LEAVE : k.consolidateShare;
		boolean wantConsolidate = !breathing && (pressedShare >= consolidateNeed || (hiveDelta < 0 && attacked > 0));
		boolean wantPress = !losing && best != null && pressedShare < k.consolidateShare / 2f;
		int next = wantConsolidate ? Swarm.CONSOLIDATE : wantPress ? Swarm.PRESS : Swarm.EXPAND;
		if (next != was && next != Swarm.CONSOLIDATE && hadState && s.day - sw.stanceSince < Math.max(0f, k.dwellDays)) {
			next = was;
		}
		if (next != was || !hadState) {
			sw.stanceSince = s.day;
			if (next != was) s.log("Stance: " + was + "->" + next + " - pressed " + pressed + "/" + n + ", attacked "
					+ attacked + ", pressure " + (int) pressure);
		}
		sw.stance = next;
		sw.lastPressure = pressure;
		sw.pressTarget = next == Swarm.PRESS && best != null ? best.id : null;
	}

	// ------------------------------------------------------------------
	// surplus and reinforcement
	// ------------------------------------------------------------------

	/** recycleSurplus: a surplus that has stood past the break-even recycles one fleet a colony a pass. */
	static void recycleSurplus(State s, SwarmKnobs k, List<StarSys> systems) {
		float breakEven = PostureRules.breakEvenDays(k.hullShare, k.upkeepPerMonth);
		for (StarSys sys : systems) {
			Swarm.Post post = s.swarm.post.get(sys.id);
			if (post == null || Float.isNaN(post.surplusSince) || s.day - post.surplusSince < breakEven) continue;
			for (Hive h : s.hivesIn(sys)) {
				float surplus = PostureRules.releasableFP(SwarmEconomy.held(s, h), h.wantFP, k.postureBand,
						SwarmEconomy.oneSwarmFP(s, h));
				int at = -1;
				for (int i = 0; i < h.swarms.size(); i++) {
					float fp = h.swarms.get(i);
					if (fp <= surplus && (at < 0 || fp > h.swarms.get(at))) at = i;
				}
				if (at < 0 || h.swarms.size() <= SwarmEconomy.reserve(h)) continue;
				float fp = h.swarms.remove(at);
				h.book();
				h.bank += fp * k.hullShare;
				s.count("swarmsRecycled", 1);
				// the clock starts again: the next fleet goes a break-even later (probe: one swarm in 150 days)
				post.surplusSince = s.day;
			}
		}
	}

	/** redistributeByPressure: a colony below its want is sent a spare swarm, else one fabricated for it by a colony at its want. */
	static void redistribute(final State s, final SwarmKnobs k) {
		List<Hive> receivers = new ArrayList<Hive>();
		// held once per hive for the ranking (round 9): the sort read inbound's parcel scan per comparison
		final Map<Hive, Float> heldNow = new java.util.IdentityHashMap<Hive, Float>();
		Map<String, Float> inbound = SwarmEconomy.inboundMap(s);
		for (Hive h : s.liveHives()) {
			float held = SwarmEconomy.held(h, inbound);
			heldNow.put(h, held);
			if (held < h.wantFP - SwarmEconomy.rowsFP(s, h, 0, 1) * 0.5f) receivers.add(h);
		}
		Collections.sort(receivers, new Comparator<Hive>() {
			public int compare(Hive a, Hive b) {
				float sa = (a.wantFP - heldNow.get(a)) / Math.max(1f, a.wantFP);
				float sb = (b.wantFP - heldNow.get(b)) / Math.max(1f, b.wantFP);
				return Float.compare(sb, sa);
			}
		});
		for (final Hive to : receivers) {
			Swarm.Post toPost = s.swarm.post.get(to.sys.id);
			boolean attacked = toPost != null && (toPost.attacked || (s.swarm.stance == Swarm.CONSOLIDATE && toPost.mode >= 2));
			List<Hive> donors = s.liveHives();
			Collections.sort(donors, new Comparator<Hive>() {
				public int compare(Hive a, Hive b) { return Float.compare(a.sys.ly(to.sys), b.sys.ly(to.sys)); }
			});
			boolean sent = false;
			for (Hive from : donors) {
				if (from == to || from.swarms.size() < 2) continue;
				if (from.lastReceivedDay != Integer.MIN_VALUE && s.day - from.lastReceivedDay < 30) continue;
				float held = SwarmEconomy.held(from, inbound);
				if (held < from.wantFP) continue;
				Swarm.Post fromPost = s.swarm.post.get(from.sys.id);
				boolean quiet = fromPost == null || fromPost.mode == 0;
				float spare = PostureRules.releasableFP(held, from.wantFP, k.postureBand, SwarmEconomy.oneSwarmFP(s, from));
				if (attacked && quiet) spare = Math.max(spare, held - SwarmEconomy.minimumFP(s, from));
				int at = -1;
				for (int i = 0; i < from.swarms.size(); i++) {
					float fp = from.swarms.get(i);
					if (fp <= spare && (at < 0 || fp > from.swarms.get(at))) at = i;
				}
				if (at < 0) continue;
				float fp = from.swarms.get(at);
				if (!SwarmEconomy.pay(s, Swarm.FUEL, k.passage(fp, from.sys.ly(to.sys), false))) continue;
				from.swarms.remove(at);
				from.book();
				dispatch(s, from, to, fp);
				inbound = SwarmEconomy.inboundMap(s);
				sent = true;
				break;
			}
			if (sent) continue;
			// fabricatorFor: the nearest colony at its want whose bank covers its own want and the swarm
			int[][] table = SwarmEconomy.table(to);
			int[] spec = table[0];
			for (int[] row : table) if (SwarmEconomy.estimate(s, row) < SwarmEconomy.estimate(s, spec)) spec = row;
			float cost = SwarmEconomy.estimate(s, spec);
			for (Hive from : donors) {
				if (from == to || !from.nexusUp() || from.coreDown > 0f) continue;
				if (SwarmEconomy.held(from, inbound) < from.wantFP) continue;
				if (from.bank - from.wantFP - cost < 0f) continue;
				float fp = SwarmFit.builtFP(spec[0], spec[1], s.rng);
				if (!SwarmEconomy.pay(s, Swarm.FUEL, k.passage(fp, from.sys.ly(to.sys), false))) continue;
				from.bank -= fp;
				SwarmEconomy.learn(s, spec, fp);
				s.count("swarmsBuilt", 1);
				dispatch(s, from, to, fp);
				inbound = SwarmEconomy.inboundMap(s);
				break;
			}
		}
	}

	static void dispatch(State s, Hive from, Hive to, float fp) {
		to.lastReceivedDay = s.day;
		s.count("reinforcementsSent", 1);
		if (from.sys == to.sys) {
			to.swarms.add(fp);
			to.book();
			return;
		}
		Parcel p = s.send(Parcel.THREAT, Parcel.Kind.REINFORCEMENT, from.sys, to.sys, fp, 0);
		p.targetId = to.id;
	}

	/** StrikeRules.sizeValue, kept reachable for the stance's score. */
	static float sizeValue(int size) { return StrikeRules.sizeValue(size); }
}
