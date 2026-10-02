package warsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import threatinc.rules.ReachRules;
import threatinc.rules.ReserveRules;

/**
 * The reserve pools (ThreatReserves) and what a base can call on from its faction's other
 * depots (IncursionManager.siegeDonors, ThreatConvoys' haul): accrual, floor, spendable,
 * the seed at mobilisation, pooled payment and refunds.
 */
final class HumanPools {
	private HumanPools() {}

	/** The accrual the dump did not give: by size for a colony, by rate and surplus for a forward base. */
	static void ensure(State s, World w) {
		if (w.forwardBase) {
			int units = w.size >= 6 ? 2 : 1;
			w.accrualPer30[World.MARINES] = HumanFit.BASE_RATE[World.MARINES] * units;
			w.accrualPer30[World.ARMAMENTS] = HumanFit.BASE_RATE[World.ARMAMENTS] * units;
			w.accrualPer30[World.FUEL] = HumanFit.BASE_RATE[World.FUEL] * (w.size + 1);
			w.accrualPer30[World.SUPPLIES] = HumanFit.BASE_RATE[World.SUPPLIES] * units;
			w.pooled = true;
			return;
		}
		if (w.pooled) return;
		w.pooled = true;
		float sum = 0f;
		for (int c = 0; c < 4; c++) sum += w.accrualPer30[c];
		if (sum > 0f) return;
		float[] by = HumanFit.ACCRUAL_BY_SIZE[Math.max(3, Math.min(8, w.size)) - 3];
		System.arraycopy(by, 0, w.accrualPer30, 0, 4);
	}

	static boolean military(World w) {
		if (w.forwardBase) return true;
		if (w.military != null) return w.military;
		return w.base || w.size >= HumanFit.MILITARY_MIN_SIZE;
	}

	/** ThreatWarState.mobilise: reserves seeded, military worlds become bases (mobilisationBuildsWaystation). */
	static void mobilise(State s, Faction f) {
		f.mobilised = true;
		f.mobilisedDay = s.day;
		float months = s.knobs.f("threatinc_reserveInitialMonths");
		boolean waystations = s.knobs.b("threatinc_mobilisationBuildsWaystation", true);
		for (World w : s.worldsOf(f.id)) {
			ensure(s, w);
			w.hasReserve = true;
			for (int c = 0; c < 4; c++) w.stock[c] = ReserveRules.seeded(w.stock[c], w.accrualPer30[c], months);
			if (waystations && military(w)) w.base = true;
		}
		s.count("factionsMobilised", 1);
		s.log("War footing: " + f.id + " mobilised");
	}

	static float basis(State s, World w, int c) {
		return ReserveRules.monthsBasis(w.accrualPer30[c], s.knobs.f("threatinc_reserveCapMonths"));
	}

	static float floor(State s, World w, int c) {
		float f = s.knobs.f("threatinc_reserveFloorFraction")
				* ReserveRules.floorBasis(basis(s, w, c), w.capSeen[c], 0f, 1f);
		if (w.forwardBase && c == World.SUPPLIES) {
			// IncursionManager.siegeDonors: a forward base keeps its garrison's upkeep back
			f = Math.max(f, s.knobs.f("threatinc_siegeOutpostKeepMonths") * w.guardFP * HumanFit.GUARD_UPKEEP_PER_FP);
		}
		return f;
	}

	static float available(State s, World w, int c) {
		return ReserveRules.available(w.stock[c], floor(s, w, c));
	}

	/**
	 * What a depot gives a base: a siege calls on every depot down to its floor (siegeDonors); anything
	 * else takes the base's own above the floor and only the spendable of the rest (ThreatReserves.spendable).
	 */
	static float gives(State s, World d, World base, int c, boolean siege) {
		if (siege || d == base) return available(s, d, c);
		return ReserveRules.spendable(d.stock[c], floor(s, d, c), basis(s, d, c), s.knobs.f("threatinc_donorKeepFraction"), 0f);
	}

	/** One day's banking for every depot of a mobilised faction. */
	static void daily(State s) {
		for (World w : s.worlds) {
			if (w.lost || !w.hasReserve) continue;
			ensure(s, w);
			for (int c = 0; c < 4; c++) {
				float cap = basis(s, w, c);
				if (cap > w.capSeen[c]) w.capSeen[c] = ReserveRules.basisAtFullShare(cap, 0f, 1f);
				// no ceiling: pd9a's census has hegemony's marines at 12644 on 12 colonies banking about 50 a month each
				w.stock[c] += w.accrualPer30[c] / 30f;
			}
		}
	}

	/** The faction's depots, the base first and then the nearest. */
	static List<World> donors(State s, final World base) {
		List<World> out = new ArrayList<World>();
		for (World w : s.worldsOf(base.faction)) if (w.hasReserve) out.add(w);
		Collections.sort(out, new Comparator<World>() {
			public int compare(World a, World b) { return Float.compare(a.sys.ly(base.sys), b.sys.ly(base.sys)); }
		});
		return out;
	}

	static float perUnit(State s, World donor, World base, int c) {
		if (donor == base || donor.sys == base.sys) return 0f;
		float haul = ReachRules.haulFuel(s.knobs.f("threatinc_convoyEscortFP"), donor.sys.ly(base.sys),
				s.knobs.f("threatinc_expeditionFuelPerPointLY"));
		float load = c == World.MARINES ? s.knobs.f("threatinc_convoyMarineCapacity")
				: s.knobs.f("threatinc_convoyCargoCapacity");
		return ReachRules.haulPerUnit(haul, load);
	}

	/** What the base can call on of one commodity: its own above the floor, and each donor's net of the haul. */
	static float payable(State s, World base, int c, boolean siege) { return payable(s, base, c, siege, donors(s, base)); }

	/** With the donors already listed (round 9: a range or a canPay asked for the same sorted list two to four times). */
	static float payable(State s, World base, int c, boolean siege, List<World> donors) {
		float sum = 0f;
		for (World d : donors) {
			float have = gives(s, d, base, c, siege);
			sum += ReachRules.netOfHaul(c == World.FUEL, have, gives(s, d, base, World.FUEL, siege), perUnit(s, d, base, c));
		}
		return sum;
	}

	static boolean canPay(State s, World base, float[] wants, boolean siege) { return canPay(s, base, wants, siege, donors(s, base)); }

	static boolean canPay(State s, World base, float[] wants, boolean siege, List<World> donors) {
		for (int c = 0; c < 4; c++) if (wants[c] > 0f && payable(s, base, c, siege, donors) < wants[c]) return false;
		return true;
	}

	/** Draws the wants at the base, nearest depots first, each paying its haul in fuel. False (and nothing drawn) if short. */
	static boolean pay(State s, World base, float[] wants, boolean siege) {
		List<World> donors = donors(s, base);
		if (!canPay(s, base, wants, siege, donors)) return false;
		// fuel last: the other commodities' hauls are paid out of the donors' fuel
		for (int c : new int[] { World.MARINES, World.ARMAMENTS, World.SUPPLIES, World.FUEL }) {
			float need = wants[c];
			for (World d : donors) {
				if (need <= 0f) break;
				float per = perUnit(s, d, base, c);
				float net = ReachRules.netOfHaul(c == World.FUEL, gives(s, d, base, c, siege), gives(s, d, base, World.FUEL, siege), per);
				float take = Math.min(need, net);
				if (take <= 0f) continue;
				d.stock[c] -= take;
				d.stock[World.FUEL] = Math.max(0f, d.stock[World.FUEL] - take * per);
				s.count("haulFuel", take * per);
				need -= take;
			}
		}
		return true;
	}

	/**
	 * ThreatUpkeep.charge: as much of `amount` as the faction holds above its floors, the home depot first and
	 * then the rest (ThreatFrontlines.payFromOthers); no haul. Returns what was paid.
	 */
	static float drain(State s, World home, int c, float amount) {
		float paid = 0f;
		for (World d : donors(s, home)) {
			if (paid >= amount) break;
			float take = Math.min(amount - paid, available(s, d, c));
			if (take <= 0f) continue;
			d.stock[c] -= take;
			paid += take;
		}
		return paid;
	}

	static void deposit(World w, float[] gives) {
		if (w == null || w.lost) return;
		for (int c = 0; c < 4; c++) if (gives[c] > 0f) w.stock[c] += gives[c];
	}

	/** The faction's nearest standing base to a system (a forward base included unless colonyOnly). */
	static World nearestBase(State s, String faction, StarSys sys, boolean colonyOnly) {
		World best = null;
		for (World w : s.worldsOf(faction)) {
			if (!w.base || !w.hasReserve || (colonyOnly && w.forwardBase)) continue;
			if (best == null || w.sys.ly(sys) < best.sys.ly(sys)) best = w;
		}
		return best;
	}

	/** ThreatReach.baseRangeLY on what the base can call on. */
	static float rangeLY(State s, World base) {
		List<World> donors = donors(s, base);
		return ReachRules.baseRangeLY(payable(s, base, World.FUEL, true, donors), payable(s, base, World.SUPPLIES, true, donors),
				s.knobs.f("threatinc_expeditionFuelPerPointLY"), s.knobs.f("threatinc_expeditionSuppliesPerPoint"),
				ReachRules.DEFAULT_SUPPLIES_PER_FP, HumanFit.PREP_MIN_DAYS + HumanFit.PREP_SPAN_DAYS, State.LY_PER_DAY);
	}
}
