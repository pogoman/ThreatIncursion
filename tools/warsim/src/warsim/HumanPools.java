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
			f = Math.max(f, s.knobs.f("threatinc_siegeOutpostKeepMonths") * w.guardFP * HumanBases.guardUpkeepPerFP(s));
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
	static float gives(State s, World d, World base, int c, boolean siege) { return gives(s, d, base, c, siege, null); }

	/** The same, less what the depot holds back from this purpose (round 15: reserve). */
	static float gives(State s, World d, World base, int c, boolean siege, String what) {
		float have = siege || d == base ? available(s, d, c)
				: ReserveRules.spendable(d.stock[c], floor(s, d, c), basis(s, d, c), s.knobs.f("threatinc_donorKeepFraction"), 0f);
		if (siege || what == null || c != World.SUPPLIES) return have;
		return Math.max(0f, have - reserve(s, d, what));
	}

	/** The forward-base line of the round-14 ledger: links, garrison voyages and upkeep, the base's own upkeep. */
	static boolean forwardLine(String what) {
		return "link".equals(what) || "guardVoyage".equals(what) || "guardUpkeep".equals(what) || "baseUpkeep".equals(what);
	}

	/**
	 * Round 15: the supplies a depot keeps back from a non-siege purpose - the larger of
	 * (a) warsim_siegeReserve: its share of a staging hammer's siege provisions (World.siegeReserve, set daily by
	 *     HumanCouncil.reserveSiege), which only the forward-base line (forwardLine) may not spend;
	 * (b) warsim_siegeSplit: a standing share of its callable supplies, reserved from every non-siege purpose;
	 * (d) warsim_strategyReserve: the same share set by the council's strategy (HumanCouncil.reserveShare).
	 */
	static float reserve(State s, World d, String what) {
		float r = 0f;
		if (forwardLine(what) && s.knobs.b("warsim_siegeReserve", false)) r = d.siegeReserve;
		float share = s.knobs.f("warsim_siegeSplit", 0f);
		if (s.knobs.b("warsim_strategyReserve", false)) share = Math.max(share, HumanCouncil.reserveShare(s, d.faction));
		if (share > 0f) r = Math.max(r, share * available(s, d, World.SUPPLIES));
		return r;
	}

	/** A depot's own supplies above its floor less its reserve from this purpose (HumanBases.cannotGuard's budget). */
	static float spareFor(State s, World d, int c, String what) { return gives(s, d, d, c, false, what); }

	/** One day's banking for every depot of a mobilised faction. */
	static void daily(State s) {
		for (World w : s.worlds) {
			if (w.lost || !w.hasReserve) continue;
			ensure(s, w);
			for (int c = 0; c < 4; c++) {
				float cap = basis(s, w, c);
				if (cap > w.capSeen[c]) w.capSeen[c] = ReserveRules.basisAtFullShare(cap, 0f, 1f);
				// no ceiling: pd9a's census has hegemony's marines at 12644 on 12 colonies banking about 50 a month each
				// warsim_accrualMult (round 14 trial e): the pools' accrual scaled, a ceiling test
				float in = w.accrualPer30[c] / 30f * s.knobs.f("warsim_accrualMult", 1f);
				w.stock[c] += in;
				s.count("income." + World.COMMODITIES[c], in);
			}
		}
	}

	/** The faction's depots, the base first and then the nearest. */
	static List<World> donors(State s, final World base) { return donors(s, base, false); }

	/**
	 * warsim_coalitionPays (round 14 trial c): a siege's provisions are called on across the coalition's depots
	 * (HumanCouncil.partner), not the one faction's; anything else, and with the switch off, the faction's own.
	 */
	static List<World> donors(State s, final World base, boolean siege) {
		boolean coalition = siege && s.knobs.b("warsim_coalitionPays", false);
		List<World> out = new ArrayList<World>();
		for (World w : s.worlds) {
			if (w.lost || !w.hasReserve) continue;
			if (!w.faction.equals(base.faction) && !(coalition && HumanCouncil.partner(base.faction, w.faction))) continue;
			out.add(w);
		}
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
	static float payable(State s, World base, int c, boolean siege) { return payable(s, base, c, siege, donors(s, base, siege)); }

	/** With the donors already listed (round 9: a range or a canPay asked for the same sorted list two to four times). */
	static float payable(State s, World base, int c, boolean siege, List<World> donors) { return payable(s, base, c, siege, donors, null); }

	/** The same for a named purpose (round 15: less each depot's reserve from it). */
	static float payable(State s, World base, int c, boolean siege, String what) { return payable(s, base, c, siege, donors(s, base, siege), what); }

	static float payable(State s, World base, int c, boolean siege, List<World> donors, String what) {
		float sum = 0f;
		for (World d : donors) {
			float have = gives(s, d, base, c, siege, what);
			sum += ReachRules.netOfHaul(c == World.FUEL, have, gives(s, d, base, World.FUEL, siege, what), perUnit(s, d, base, c));
		}
		return sum;
	}

	static boolean canPay(State s, World base, float[] wants, boolean siege) { return canPay(s, base, wants, siege, donors(s, base, siege), null); }

	static boolean canPay(State s, World base, float[] wants, boolean siege, String what) { return canPay(s, base, wants, siege, donors(s, base, siege), what); }

	static boolean canPay(State s, World base, float[] wants, boolean siege, List<World> donors) { return canPay(s, base, wants, siege, donors, null); }

	static boolean canPay(State s, World base, float[] wants, boolean siege, List<World> donors, String what) {
		for (int c = 0; c < 4; c++) if (wants[c] > 0f && payable(s, base, c, siege, donors, what) < wants[c]) return false;
		return true;
	}

	/** Draws the wants at the base, nearest depots first, each paying its haul in fuel. False (and nothing drawn) if short. */
	static boolean pay(State s, World base, float[] wants, boolean siege) { return pay(s, base, wants, siege, "other"); }

	/** The same, booked to the ledger by purpose (round 14): spend.<what>.<commodity> and spendBy.<faction>.<what>.<commodity>. */
	static boolean pay(State s, World base, float[] wants, boolean siege, String what) {
		List<World> donors = donors(s, base, siege);
		if (!canPay(s, base, wants, siege, donors, what)) return false;
		for (int c = 0; c < 4; c++) {
			if (wants[c] <= 0f) continue;
			s.count("spend." + what + "." + World.COMMODITIES[c], wants[c]);
			s.count("spendBy." + base.faction + "." + what + "." + World.COMMODITIES[c], wants[c]);
		}
		// fuel last: the other commodities' hauls are paid out of the donors' fuel
		for (int c : new int[] { World.MARINES, World.ARMAMENTS, World.SUPPLIES, World.FUEL }) {
			float need = wants[c];
			for (World d : donors) {
				if (need <= 0f) break;
				float per = perUnit(s, d, base, c);
				float net = ReachRules.netOfHaul(c == World.FUEL, gives(s, d, base, c, siege, what), gives(s, d, base, World.FUEL, siege, what), per);
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
			s.count("spend.fleetUpkeep." + World.COMMODITIES[c], take);
			s.count("spendBy." + home.faction + ".fleetUpkeep." + World.COMMODITIES[c], take);
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
				HumanPlanner.suppliesPerFP(s), HumanFit.PREP_MIN_DAYS + HumanFit.PREP_SPAN_DAYS, State.LY_PER_DAY);
	}
}
