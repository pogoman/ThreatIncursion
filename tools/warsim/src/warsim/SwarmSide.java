package warsim;

/**
 * The swarm: economy, garrisons, posture, stance, spread and strikes, on the mod's clocks
 * (daily; posture and stance every postureDays; the 30-day tick). docs/war-sim-swarm.md.
 * Starts from whatever the state holds: an empty map (the opening chain is claimed) or a
 * war under way (what the dump does not carry is inferred in init).
 */
public final class SwarmSide implements Side {
	private SwarmKnobs k;

	@Override public void init(State s) {
		k = new SwarmKnobs(s.knobs);
		Swarm sw = s.swarm;
		boolean underWay = !s.liveHives().isEmpty();
		boolean inFlight = false;
		for (Parcel p : s.parcels) if (p.threat()) inFlight = true;

		if (!underWay && !inFlight && sw.claims.isEmpty()) {
			// a new game: the chain out of the Abyss is claimed on day 0 and lands seedToColonyDays later
			StarSys og = SwarmOps.pickOG(s, k.homeWorlds);
			if (og != null) {
				Swarm.Claim c = new Swarm.Claim();
				c.sys = og;
				c.day = s.day;
				c.bootstrap = true;
				sw.claims.add(c);
			}
			sw.nextTickDay = s.day + k.tickDays + SwarmFit.TICK_OVERRUN_DAYS;
			sw.nextPostureDay = s.day + (int) k.postureDays;
			return;
		}

		// a war under way: infer what the dump does not say
		Hive bestForge = null;
		boolean refining = false;
		for (Hive h : s.liveHives()) {
			SwarmEconomy.sync(h);
			if (h.refining) refining = true;
			if (!h.mining && !h.refining) h.mining = !(h.forge || h.fuelPlant) || h.size >= 4;
			if (h.size >= 6) h.batteries = true;
			else if (h.size >= 3) h.groundDef = true;
			if (h.size >= 6 && h.forge) h.orbitalWorks = true;
			if (h.forge && (bestForge == null || h.size > bestForge.size)) bestForge = h;
			SwarmOps.rollExpandable(s, h.sys, s.hivesIn(h.sys).size());
		}
		if (!refining) {
			for (Hive h : s.liveHives()) {
				if (h.forge || h.fuelPlant) continue;
				h.refining = true;
				if (h.size < 4) h.mining = false;
				break;
			}
		}
		if (!sw.pristinePlaced && bestForge != null) {
			sw.pristinePlaced = true;
			for (Hive h : s.liveHives()) {
				if (!h.forge || h.nanoRolled) continue;
				h.nanoRolled = true;
				h.nanoUnits = h == bestForge ? 3 : s.rng.nextFloat() < k.nanoChance ? 1 : 0;
			}
		}
		for (java.util.Map.Entry<String, float[]> e : sw.posture.entrySet()) {
			if (sw.post.containsKey(e.getKey())) continue;
			Swarm.Post post = new Swarm.Post();
			post.pressure = e.getValue()[0];
			post.mode = (int) e.getValue()[1];
			post.day = s.day;
			sw.post.put(e.getKey(), post);
		}
		if (sw.stanceSince == Integer.MIN_VALUE) sw.stanceSince = s.day;
		for (Parcel p : s.parcels) {
			if (p.threat() && p.kind == Parcel.Kind.STRIKE && p.targetId != null) sw.struck.add(p.targetId);
		}
		SwarmOps.radar(s, k);
		if (Float.isNaN(sw.nextTickDay)) {
			sw.nextTickDay = s.day + 1f + s.rng.nextFloat() * (k.tickDays + SwarmFit.TICK_OVERRUN_DAYS);
		}
		if (sw.nextPostureDay == Integer.MIN_VALUE) sw.nextPostureDay = s.day + 1;
	}

	@Override public void daily(State s) {
		SwarmEconomy.daily(s, k);
		SwarmOps.daily(s, k);
		Swarm sw = s.swarm;
		if (s.day >= sw.nextPostureDay) {
			sw.nextPostureDay = s.day + Math.max(1, (int) k.postureDays);
			SwarmPosture.poll(s, k);
		}
		// IncursionManager.advance: redistributeGarrisons runs every half day, on the wants of the last poll
		if (k.postureLoop) SwarmPosture.redistribute(s, k);
		if (s.day >= sw.nextTickDay) {
			sw.nextTickDay += k.tickDays + SwarmFit.TICK_OVERRUN_DAYS;
			SwarmOps.tick(s, k);
		}
	}

	@Override public void arrive(State s, Parcel p) {
		SwarmOps.arrive(s, k, p);
	}
}
