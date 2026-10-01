package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * How far the hive's fleets go (2026-09-30, user's call; docs/hive-economy.md
 * "Reach is the bill"): as far as the hive pays for. No radius. A trip's bill
 * is its passage - fuel out of the hive's stock (ThreatFuel.passage) - and the
 * supplies its fleet burns while away, its hulls' vanilla supplies a month,
 * which must fit in what the colonies' sustenance leaves of the month's
 * production (ThreatColonyUpkeep.spareSupplies): a trip never starves a
 * colony. Both grow with the fleet, so a 30 FP scout crosses the sector on
 * what a 3,000 FP strike burns to go next door, and a hive with nothing spare
 * launches nothing at all.
 *
 * <p>Where to go is a choice, not a wall: a picker weighs each target by what
 * it is worth per day its fleet is away (strikeDays), so the swarm strikes
 * near unless a far world is worth the extra weeks. The factions still read a
 * front off it (facedFaction): the faction a hive system would strike first.
 *
 * <p>Off (billedReach false, or the Threat not paying passage): the old radius,
 * ThreatColonyManager.fuelRangeLY.
 */
public class ThreatReach {

	public static final String KEY = "threatinc_reach";

	/** Mean days a strike musters before it sails: launchStrike's 7-14. */
	public static final float STRIKE_PREP_DAYS = 10.5f;

	/**
	 * Supplies a month per fleet point before the hive has a garrison to
	 * measure: vanilla's Threat escort hulls (skirmish, assault, overseer,
	 * standoff, hive units: 64 supplies a month for 82 FP).
	 */
	public static final float DEFAULT_SUPPLIES_PER_FP = 0.78f;

	/** Game-clock ms in a day. */
	protected static final long DAY_MS = 86400000L;

	public static boolean enabled() {
		return ThreatIncConfig.billedReach() && ThreatFuel.enabled();
	}

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	protected static long today() {
		return Global.getSector().getClock().getTimestamp() / DAY_MS;
	}

	// ------------------------------------------------------------------
	// time
	// ------------------------------------------------------------------

	/** Days a fleet takes to cross {@code ly} at the board's estimated speed. */
	public static float days(float ly) {
		return Math.max(0f, ly) / ThreatWarBoard.EST_LY_PER_DAY;
	}

	/** Days a fleet is away on a trip of {@code ly}: out, back as well if {@code roundTrip}, and {@code stay} besides. */
	public static float daysAway(float ly, boolean roundTrip, float stay) {
		return days(ly) * (roundTrip ? 2f : 1f) + Math.max(0f, stay);
	}

	/** Days a strike at a world {@code ly} out is away from its garrison: its muster, there and back. */
	public static float strikeDays(float ly) {
		return daysAway(ly, true, STRIKE_PREP_DAYS);
	}

	// ------------------------------------------------------------------
	// supplies
	// ------------------------------------------------------------------

	protected static Object perFPSector;
	protected static long perFPDay = Long.MIN_VALUE;
	protected static float perFP = DEFAULT_SUPPLIES_PER_FP;

	/** Supplies a month a fleet point of the hive's fleets burns: its garrisons' hulls, measured once a day. */
	public static float suppliesPerFP() {
		long day = today();
		if (perFPSector == Global.getSector() && perFPDay == day) return perFP;
		float supplies = 0f, fp = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			for (CampaignFleetAPI f : ThreatIncData.garrisonsFor(m.getId())) {
				if (f == null || !f.isAlive()) continue;
				supplies += ThreatFrontlines.maintenancePerMonth(f);
				fp += f.getFleetPoints();
			}
		}
		perFP = fp > 0f && supplies > 0f ? supplies / fp : DEFAULT_SUPPLIES_PER_FP;
		perFPSector = Global.getSector();
		perFPDay = day;
		return perFP;
	}

	/** Supplies a month a fleet of {@code fp} burns away from home. */
	public static float suppliesPerMonth(float fp) {
		return Math.max(0f, fp) * suppliesPerFP();
	}

	/** On game load: nothing held of the campaign left behind, nothing committed on its timeline. */
	public static void forget() {
		perFPSector = null;
		perFPDay = Long.MIN_VALUE;
		facedSector = null;
		facedDay = Long.MIN_VALUE;
		faced.clear();
		committed = 0f;
		ThreatSwarmScouts.lastTank = -1f;
	}

	/** Supplies a month committed to trips since the colonies' last feed recorded the spare. */
	protected static float committed = 0f;

	/** ThreatColonyUpkeep.feed has recorded the spare with every fleet away in it: nothing is pending now. */
	public static void clearCommitted() {
		committed = 0f;
	}

	/** Supplies a month the hive can still send away: the colonies' spare, less what was launched since it was read. */
	public static float spare() {
		if (!ThreatColonyUpkeep.enabled()) return Float.MAX_VALUE;
		return ThreatColonyUpkeep.spareSupplies() - committed;
	}

	/** Whether the hive can keep a fleet of {@code fp} away without starving a colony, on the month's flow alone. Always, billed reach off. */
	public static boolean canSustain(float fp) {
		if (!enabled() || !ThreatColonyUpkeep.enabled()) return true;
		return suppliesPerMonth(fp) <= spare();
	}

	/**
	 * Whether the hive can pay a fleet of {@code fp} away for {@code days}: its
	 * whole bill, the supplies a month over the trip, out of the flow over the
	 * same months (spare, which a trip already out has lowered - negative, it is
	 * a drain on the stock) and the stock above one founding kit (2026-10-01).
	 * On the flow alone a hive with 36k supplies banked held a raider (ng1b):
	 * a stock is what a flow short of a trip has to draw on.
	 */
	public static boolean canSustain(float fp, float days) {
		if (!enabled() || !ThreatColonyUpkeep.enabled()) return true;
		float need = suppliesPerMonth(fp);
		float flow = spare();
		if (need <= flow) return true;
		float months = Math.max(1f, days) / 30f;
		return need * months <= flow * months + freeStock();
	}

	/** The most fleet points canSustain lets away for {@code days}: the flow and the stock spread over the trip. */
	public static float sustainableFP(float days) {
		if (!enabled() || !ThreatColonyUpkeep.enabled()) return Float.MAX_VALUE;
		float months = Math.max(1f, days) / 30f;
		return Math.max(0f, spare() + freeStock() / months) / suppliesPerFP();
	}

	/** Supplies in stock above what one founding takes (ThreatFuel.foundingCost): what trips may draw on. */
	public static float freeStock() {
		return Math.max(0f, ThreatFuel.stock(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES)
				- ThreatFuel.foundingCost()[0]);
	}

	/** A fleet of {@code fp} has left: its supplies a month come off the spare until the next feed counts it. */
	public static void commit(float fp) {
		if (enabled()) committed += suppliesPerMonth(fp);
	}

	/** Whether the hive can pay a trip: its passage from the fuel stock, its supplies away from the spare. */
	public static boolean canPay(float fp, float ly, boolean roundTrip) {
		return ThreatFuel.canPay(ThreatFuel.passage(fp, ly, roundTrip))
				&& canSustain(fp, roundTrip ? strikeDays(ly) : days(ly));
	}

	// ------------------------------------------------------------------
	// the front: what a hive system would strike first
	// ------------------------------------------------------------------

	protected static Object facedSector;
	protected static long facedDay = Long.MIN_VALUE;
	protected static Map<String, Object[]> faced = new HashMap<String, Object[]>();

	/**
	 * {faction id, light-years} of the charted strikeable world a hive system
	 * would strike first - the most worth per day away (strikeValue over
	 * strikeDays) - or null when it knows none. Once a day.
	 */
	protected static Object[] faced(StarSystemAPI system) {
		if (system == null) return null;
		long day = today();
		if (facedSector != Global.getSector() || facedDay != day) {
			faced.clear();
			facedSector = Global.getSector();
			facedDay = day;
		}
		if (faced.containsKey(system.getId())) return faced.get(system.getId());
		Object[] best = null;
		float bestScore = 0f;
		// the strike gate's standing filters (strikeAllowed): no core world before
		// phase 3, no player world in its grace - not the strike already sailing
		// at a world, which would flap the front every launch
		int phase = IncursionManager.getPhase();
		boolean coreOpen = phase >= 3;
		boolean playerGrace = ThreatIncData.daysSincePlayerStruck() < ThreatIncConfig.playerGraceDays();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!IncursionManager.isStrikeableWorld(m) || !ThreatSwarmScouts.swarmKnows(m)) continue;
			if (!coreOpen && IncursionManager.isCoreWorld(m)) continue;
			if (playerGrace && m.isPlayerOwned()) continue;
			if (!IncursionManager.warOpen(m, phase)) continue;
			float ly = Misc.getDistanceLY(system.getLocation(), m.getStarSystem().getLocation());
			float score = IncursionManager.strikeValue(m) / strikeDays(ly);
			if (score > bestScore) {
				bestScore = score;
				best = new Object[] { m.getFactionId(), ly };
			}
		}
		faced.put(system.getId(), best);
		return best;
	}

	/** The faction a hive system would strike first, or null. */
	public static String facedFaction(StarSystemAPI system) {
		Object[] f = faced(system);
		return f != null ? (String) f[0] : null;
	}

	/** Light-years to the world a hive system would strike first, or -1. */
	public static float facedLY(StarSystemAPI system) {
		Object[] f = faced(system);
		return f != null ? (Float) f[1] : -1f;
	}

	// ------------------------------------------------------------------
	// the month's line
	// ------------------------------------------------------------------

	/** A trip launched, for the month's line: kind is strike, wave, scout, raid or send. */
	public static void note(String kind, float ly) {
		if (!enabled()) return;
		Map<String, Object> d = data();
		d.put(kind + ".n", num(d.get(kind + ".n")) + 1f);
		d.put(kind + ".sum", num(d.get(kind + ".sum")) + ly);
		d.put(kind + ".max", Math.max(num(d.get(kind + ".max")), ly));
	}

	protected static float num(Object o) {
		return o instanceof Float ? (Float) o : 0f;
	}

	protected static final String[] KINDS = { "strike", "wave", "send", "raid", "scout" };

	/** The month's reach line for the census log - the spare, the trips flown and how far the hive has spread - and the tallies reset. */
	public static void logMonth() {
		if (!enabled()) return;
		Map<String, Object> d = data();
		StringBuilder sb = new StringBuilder("Reach: spare ");
		float spare = ThreatColonyUpkeep.enabled() ? ThreatColonyUpkeep.spareSupplies() : 0f;
		sb.append((int) spare).append(" supplies/mo (fleets away ").append((int) ThreatColonyUpkeep.fleetsPerMonth())
				.append("/mo, ").append(String.format("%.2f", suppliesPerFP())).append(" a FP)");
		for (String k : KINDS) {
			int n = (int) num(d.get(k + ".n"));
			sb.append("; ").append(k).append("s ").append(n);
			if (n > 0) {
				sb.append(" (mean ").append((int) (num(d.get(k + ".sum")) / n)).append(", max ")
						.append((int) num(d.get(k + ".max"))).append(" ly)");
			}
			d.put(k + ".n", 0f);
			d.put(k + ".sum", 0f);
			d.put(k + ".max", 0f);
		}
		// how far the hive has spread: its widest pair of systems, and its fronts
		List<StarSystemAPI> systems = new ArrayList<StarSystemAPI>();
		for (String id : ThreatIncData.colonyMarkets().keySet()) {
			if (ThreatIncData.getLiveColonyMarkets(id).isEmpty()) continue;
			StarSystemAPI s = Global.getSector().getStarSystem(id);
			if (s != null) systems.add(s);
		}
		float span = 0f;
		for (int i = 0; i < systems.size(); i++) {
			for (int j = i + 1; j < systems.size(); j++) {
				span = Math.max(span, Misc.getDistanceLY(systems.get(i).getLocation(), systems.get(j).getLocation()));
			}
		}
		Map<String, Integer> fronts = new HashMap<String, Integer>();
		for (StarSystemAPI s : systems) {
			String f = facedFaction(s);
			if (f == null) continue;
			Integer had = fronts.get(f);
			fronts.put(f, had != null ? had + 1 : 1);
		}
		sb.append("; hive spans ").append((int) span).append(" ly over ").append(systems.size())
				.append(" systems, facing ").append(fronts);
		ThreatIncConfig.log(sb.toString());
	}
}
