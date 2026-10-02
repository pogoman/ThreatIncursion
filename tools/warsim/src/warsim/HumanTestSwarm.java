package warsim;

/**
 * A stand-in for the swarm side, for calibrating the human side alone
 * (-Dwarsim.humanTestSwarm=true; -Dwarsim.startWarMonth=N, the war month of the start dump,
 * default 36). It plays back run pd9a's swarm as a schedule, not as rules: the months the
 * factions were first struck, the hives founded per month, garrisons regrowing toward the
 * dump's wantFP, and strikes on forward bases. Nothing here is part of the model.
 */
final class HumanTestSwarm {
	private HumanTestSwarm() {}

	/** pd9a: "War footing: Ancyra (hegemony) mobilised", the war month of its "Clock: day N war M" marker. */
	private static final String[] STRUCK = { "hegemony", "persean", "independent", "tritachyon", "sindrian_diktat",
			"luddic_church", "luddic_path" };
	private static final int[] STRUCK_MONTH = { 39, 37, 79, 78, 106, 107, 105 };
	/** pd9a dumps: mean wantFP by hive size 1-5. */
	private static final float[] WANT_BY_SIZE = { 76f, 200f, 300f, 1200f, 1480f };
	private static final float RELIEF_PER_DAY = Float.parseFloat(System.getProperty("warsim.testRelief", "0.01"));

	static void daily(State s) {
		int month = Integer.getInteger("warsim.startWarMonth", 36) + s.month();
		for (int i = 0; i < STRUCK.length; i++) {
			Faction f = s.factions.get(STRUCK[i]);
			if (f == null || f.strikesSuffered > 0 || month < STRUCK_MONTH[i]) continue;
			f.strikesSuffered = 1;
			f.lastStruckDay = s.day;
			f.lastStrikeFrom = nearestHive(s, f);
		}
		for (Hive h : s.liveHives()) {
			// the swarms over a hive come and go (reinforcements, strikes mustering): the garrison drifts toward its
			// want times a monthly swing of 0.5-2, over about two months
			float swing = 0.5f * (float) Math.pow(4.0, new java.util.Random(h.id.hashCode() * 31L + month).nextFloat());
			float want = (h.wantFP > 0f ? h.wantFP : WANT_BY_SIZE[Math.max(1, Math.min(5, h.size)) - 1]) * swing;
			if (h.garrisonFP < want) h.garrisonFP = Math.min(want, h.garrisonFP + want / 60f);
			else h.garrisonFP = Math.max(want, h.garrisonFP - want / 60f);
			h.garrisonFleets = Math.max(1, (int) Math.ceil(h.garrisonFP / 150f));
			if (h.nexusDown > 0f) h.nexusDown = Math.max(0f, h.nexusDown - 1f);
			if (h.forgeDown > 0f) h.forgeDown = Math.max(0f, h.forgeDown - 1f);
			if (h.coreDown > 0f) h.coreDown = Math.max(0f, h.coreDown - 1f);
		}
		// relief: the swarm sees a siege coming and reinforces the hive it sails for, by RELIEF_PER_DAY of the
		// siege's own fleet points a day in flight (pd9a: 53 of 150 sieges called off on arrival, 33 beaten)
		for (Parcel p : s.parcels) {
			if (p.done || p.threat() || p.kind != Parcel.Kind.SIEGE || p.arrived || p.targetId == null) continue;
			Hive h = s.hive(p.targetId);
			if (h != null && !h.dead && p.to == h.sys) h.garrisonFP += RELIEF_PER_DAY * p.fp;
		}
		if ((s.day - s.startDay) % 30 != 0) return;

		// pd9a's hives: 16 at month 36, 17 at 48 (1 killed), 27 at 60 (6), 28 at 72 (10), 32 at 96 (16), 41 at 108 (20)
		float perMonth = month < 48 ? 0.17f : month < 60 ? 1.25f : month < 96 ? 0.42f : 1.1f;
		if (s.rng.nextFloat() < perMonth) found(s);
		if (s.rng.nextFloat() < perMonth - 1f) found(s);
		// a faction at war is struck about every other month (pd9a: persean 8, hegemony 11 strikes by month 60),
		// from an established hive: the scouts' lead
		for (Faction f : s.factions.values()) {
			if (!f.mobilised || s.rng.nextFloat() >= 0.45f) continue;
			java.util.List<Hive> from = new java.util.ArrayList<Hive>();
			for (Hive h : s.liveHives()) if (h.size >= 3) from.add(h);
			if (from.isEmpty()) continue;
			f.strikesSuffered++;
			f.lastStruckDay = s.day;
			f.lastStrikeFrom = from.get(s.rng.nextInt(from.size())).sys;
		}
		for (Hive h : s.liveHives()) {
			if (h.wantFP <= 0f && h.size < 4 && (s.day - h.foundedDay) > 0 && (s.day - h.foundedDay) % 360 == 0) s.growHive(h);
		}
		// a forward base is struck about once in four months (median life 4 months) by 0.2-0.8 of the strongest
		// garrison of a strike-staging hive (size 4 and up) within 25 ly
		for (World w : s.liveWorlds()) {
			if (!w.forwardBase || s.rng.nextFloat() >= 0.25f) continue;
			float top = 0f;
			for (Hive h : s.liveHives()) if (h.size >= 4 && h.sys.ly(w.sys) <= 25f) top = Math.max(top, h.garrisonFP);
			if (top <= 0f) continue;
			float strike = top * (0.2f + 0.6f * s.rng.nextFloat());
			Faction f = s.faction(w.faction);
			f.strikesSuffered++;
			f.lastStruckDay = s.day;
			if (strike > w.guardFP) {
				w.guardFP = 0f;
				s.loseWorld(w, true, "station destroyed by a Threat strike");
			} else {
				w.guardFP -= strike * 0.5f;
			}
		}
	}

	private static StarSys nearestHive(State s, Faction f) {
		StarSys best = null;
		float bestLY = Float.MAX_VALUE;
		for (Hive h : s.liveHives()) {
			for (World w : s.worldsOf(f.id)) {
				if (w.sys.ly(h.sys) < bestLY) { bestLY = w.sys.ly(h.sys); best = h.sys; }
			}
		}
		return best;
	}

	private static void found(State s) {
		StarSys best = null;
		float bestLY = Float.MAX_VALUE;
		for (StarSys sys : s.systems.values()) {
			if (sys.planets <= 0 || s.hasHive(sys)) continue;
			boolean taken = false;
			for (World w : s.worlds) if (!w.lost && w.sys == sys) { taken = true; break; }
			if (taken) continue;
			float near = Float.MAX_VALUE;
			for (Hive h : s.liveHives()) near = Math.min(near, h.sys.ly(sys));
			near += 3f * s.rng.nextFloat();
			if (near < bestLY) { bestLY = near; best = sys; }
		}
		if (best == null) return;
		s.foundHive(best, null, 1);
	}
}
