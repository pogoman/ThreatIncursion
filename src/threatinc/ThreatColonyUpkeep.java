package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.impl.BaseIndustry;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Colony upkeep by size (2026-09-30, user's call; docs/hive-economy.md "Size
 * upkeep"). A colony of size 3 or more costs sizeUpkeepAt3 x
 * sizeUpkeepRatio^(size - 3) supplies a month - 100, 250, 625 ... 9,766 at
 * size 8 - a hive world and a forward base alike; sizes 1 and 2 are free, so
 * a seed costs nothing until it is a world. The share of it that arrives sets
 * the colony's growth (growthRate): the break-even share (upkeepBreakEven)
 * holds its size, all of it grows it at the full pace, none of it takes a size
 * off every starveDaysPerSize days.
 *
 * <p>The hive pays out of the production of the days fed, after its fleets
 * away (feed, once a poll):
 * <ol>
 * <li>Sustenance: the break-even share of every colony's upkeep, forge worlds
 * that make more than they eat first, then the worlds nearest the humans - up to the largest stance share
 * of the production (sustainShare, 0.7), never more: the hive always keeps a
 * tithe for forges, waves and fleets. h35a let sustenance take everything and
 * the base save's hive shrank until it did - 26k of 29k a month - with nothing
 * left to buy the forges that would have fed it again. The leeway is the half
 * level a colony's progress runs below its size before the size goes
 * (SHRINK_MARGIN), not the stock: a raided forge stops growth at once and
 * costs sizes only if it stays down.</li>
 * <li>Growth: the rest of each colony's upkeep, out of its stance's share of
 * the production (feedShare: expanding 0.5, pressing 0.7, consolidating 0.5),
 * to a colony only while that share still holds its next size's sustenance.
 * Forges whose next size pays for itself first; then pressing feeds the front,
 * expanding and consolidating the smallest. A size-2 world grows into
 * size 3, where upkeep starts, only as it is fed for it (its growth priced at
 * size 3's upkeep); a size-1 seed and a forge world below size 3 grow free.
 * h35a let size 2 grow free: 76 worlds grew into size 3 unpaid and starved
 * back.</li>
 * </ol>
 * What a colony's own forge does not make it imports, and imports arrive cut
 * by a human blockade over it (half or all, ThreatBlockade.hiveCut) and halved
 * while its port is disrupted (importCut); a blockaded forge world's surplus
 * cannot leave either (reachesStock).
 */
public class ThreatColonyUpkeep {

	public static final String KEY = "threatinc_colonyUpkeep";
	/** Market memory: the share of its upkeep the colony was paid on the last feed (none: 1). */
	public static final String MEM_FED = "$threatinc_fedShare";
	/** The share of its imports a disrupted port lets through. */
	public static final float PORT_DOWN_CUT = 0.5f;

	public static boolean enabled() {
		return ThreatIncConfig.sizeUpkeep() && ThreatFuel.enabled();
	}

	protected static Map<String, Object> data() {
		return ThreatIncData.map(KEY);
	}

	/** Supplies a month a colony of this size costs: 0 below size 3. */
	public static float perMonth(int size) {
		return threatinc.rules.HiveRules.sizeUpkeepPerMonth(size, ThreatIncConfig.sizeUpkeepAt3(),
				ThreatIncConfig.sizeUpkeepRatio());
	}

	/** The share of its upkeep that holds a colony at its size. */
	public static float breakEven() {
		return threatinc.rules.HiveRules.breakEven(ThreatIncConfig.upkeepBreakEven());
	}

	/** Days a colony paid nothing takes to starve through a level. */
	public static float starveDays() {
		return Math.max(1f, ThreatIncConfig.starveDaysPerSize());
	}

	/**
	 * How far below its size, in levels, a starving colony's progress runs
	 * before the size goes: a colony just grown does not lose its size to the
	 * first lean week, and one that loses it lands halfway back to it. A world
	 * at its cap banks a full level above its size while it is fed
	 * (ThreatColonyManager.advanceFedGrowth), so a fortress paid nothing holds
	 * for 1.5 levels' starving. h35a, without it: 23 size-8 worlds of the base
	 * save, their progress 0 at the cap, lost a size in the first week.
	 */
	public static final float SHRINK_MARGIN = 0.5f;

	/**
	 * The share of its upkeep the colony was last paid (a size-2 world: the
	 * break-even share plus what it was fed toward size 3); 1 while it grows
	 * free or has not been fed yet.
	 */
	public static float fedShare(MarketAPI market) {
		if (market == null || !market.getMemoryWithoutUpdate().contains(MEM_FED)) return 1f;
		return Math.max(0f, Math.min(1f, market.getMemoryWithoutUpdate().getFloat(MEM_FED)));
	}

	/**
	 * The pace a share paid gives, of the full growth pace: 0 at the
	 * break-even share, 1 paid in full; below break-even it is negative, -1 at
	 * nothing (a size lost per starveDays).
	 */
	public static float growthRate(float fed) {
		return threatinc.rules.HiveRules.growthRate(fed, breakEven());
	}

	/** The share of a hive world's imports that does not arrive: a human blockade's, or half while its port is down. */
	public static float importCut(MarketAPI market) {
		if (market == null) return 0f;
		float cut = market.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY);
		Industry port = ThreatColonyManager.getPort(market);
		if (port == null || port.isDisrupted()) cut = Math.max(cut, PORT_DOWN_CUT);
		return Math.max(0f, Math.min(1f, cut));
	}

	/** Supplies a month the world's own forge makes, as ThreatFuel banks them. */
	public static float localSupplies(MarketAPI market) {
		CommodityOnMarketAPI com = market.getCommodityData(Commodities.SUPPLIES);
		if (com == null || com.getMaxSupply() <= 0) return 0f;
		return BaseIndustry.getSizeMult(com.getMaxSupply()) * com.getCommodity().getEconUnit()
				* ThreatIncConfig.hiveSurplusMult();
	}

	/**
	 * What of a hive world's monthly output, in the commodity's units, reaches
	 * the hive's stock: all of it, less what a blockade or a dead port keeps
	 * from leaving - of supplies, beyond what the world's own upkeep takes.
	 */
	public static float reachesStock(MarketAPI market, String commodityId, float units) {
		if (!enabled() || units <= 0f) return units;
		float cut = importCut(market);
		if (cut <= 0f) return units;
		float keep = 0f;
		if (Commodities.SUPPLIES.equals(commodityId)) {
			CommodityOnMarketAPI com = market.getCommodityData(commodityId);
			float perUnit = com != null ? com.getCommodity().getEconUnit() * ThreatIncConfig.hiveSurplusMult() : 0f;
			if (perUnit > 0f) keep = Math.min(units, perMonth(market.getSize()) / perUnit);
		}
		return keep + (units - keep) * (1f - cut);
	}

	/** The share of the month's production, after fleets away, the hive lets its colonies' upkeep take (by stance). */
	public static float feedShare() {
		int stance = ThreatStance.stance();
		return threatinc.rules.StanceRules.feedShare(
				stance == ThreatStance.PRESS ? threatinc.rules.StanceRules.PRESS
						: stance == ThreatStance.CONSOLIDATE ? threatinc.rules.StanceRules.CONSOLIDATE
						: threatinc.rules.StanceRules.EXPAND,
				ThreatIncConfig.feedShareExpand(), ThreatIncConfig.feedSharePress(),
				ThreatIncConfig.feedShareConsolidate());
	}

	/**
	 * Supplies a month the production leaves once the fleets away are paid and
	 * every colony's sustenance fits under sustainShare, as the last feed read
	 * it: what a new trip may burn without starving a colony (ThreatReach). The chest full, the
	 * production less what the colonies took; else less their share, the rest kept for expansion.
	 * Unlimited with size upkeep off.
	 */
	public static float spareSupplies() {
		if (!enabled()) return Float.MAX_VALUE;
		Object v = data().get("spare");
		return v instanceof Float ? (Float) v : ThreatFuel.perMonth(Commodities.SUPPLIES);
	}

	/** Supplies a month the fleets away burned at the last feed. */
	public static float fleetsPerMonth() {
		Object v = data().get("fleets");
		return v instanceof Float ? (Float) v : 0f;
	}

	/** Supplies a month the navy above the patrols pays at home, as the last feed read it (navyChargePerMonth). */
	public static float navyPerMonth() {
		Object v = data().get("navy");
		return v instanceof Float ? (Float) v : 0f;
	}

	/** Supplies a month every colony's garrison above its patrols costs now (ThreatColonyManager.payNavySupplies's rate x navyFP). */
	public static float navyChargePerMonth() {
		if (!ThreatIncConfig.threatSuppliesUpkeep()) return 0f;
		float rate = ThreatReach.suppliesPerFP() * ThreatIncConfig.standingUpkeepMult();
		if (rate <= 0f) return 0f;
		float fp = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			fp += ThreatColonyManager.navyFP(m, ThreatIncData.garrisonsFor(m.getId()));
		}
		return fp * rate;
	}

	/**
	 * The colonies' sustenance due at the next feed - every world's break-even share of its upkeep for the days
	 * since the last feed, under sustainShare of the production over the same days - which the fleets away and
	 * the navy at home may not draw (ThreatColonyManager.paySupplies / payNavySupplies, ThreatReach.freeStock):
	 * the forge is the income, the navy the expense (the user, 2026-10-08, after hw68a: sustenance paid last,
	 * the colonies starved first at a 10% overshoot, sizes 179 -> 93 and income 72k -> 25k a month in eight
	 * months while the fleets already out kept drawing; the navy shrank only after the forges were gone).
	 * 0 with sustenanceFirst off.
	 */
	public static float sustenanceDue() {
		if (!enabled() || !ThreatIncConfig.sustenanceFirst()) return 0f;
		Object last = data().get("at");
		if (!(last instanceof Long)) return 0f;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) last);
		if (days <= 0f) return 0f;
		float t = breakEven();
		float due = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			float perMonth = perMonth(m.getSize());
			if (perMonth <= 0f) continue; // a seed or a forge world growing toward its first output: free
			float want = perMonth * days / 30f;
			float own = Math.min(want, localSupplies(m) * days / 30f);
			float cap = own + (want - own) * (1f - importCut(m));
			due += Math.min(t * want, cap);
		}
		float made = ThreatFuel.perMonth(Commodities.SUPPLIES) * days / 30f;
		return Math.max(0f, Math.min(due, Math.min(sustainShare() * made, ThreatFuel.stock(Commodities.SUPPLIES))));
	}

	/** The share of the production, after fleets away, sustenance may take whatever the stance: the largest stance share. */
	public static float sustainShare() {
		float share = Math.max(ThreatIncConfig.feedShareExpand(),
				Math.max(ThreatIncConfig.feedSharePress(), ThreatIncConfig.feedShareConsolidate()));
		return Math.max(0f, Math.min(1f, share));
	}

	protected static class Need {
		MarketAPI market;
		int size;
		float want, cap, sustain, paid, frontLY;
		boolean forge, forgeStepPays, grows;
		/** A size-2 world fed toward size 3: its want is size 3's upkeep, and it has no sustenance to pay. */
		boolean seed;
	}

	/**
	 * Pays every hive world's upkeep for the days since the last feed - once a
	 * poll, after the fleets away are paid (maintainGarrisons), whose supplies
	 * a month {@code fleetsPerMonth} come off the production the growth share
	 * is taken from. Records each world's share paid (MEM_FED).
	 */
	public static void feed(float fleetsPerMonth) {
		if (!enabled()) return;
		long now = Global.getSector().getClock().getTimestamp();
		Object last = data().get("at");
		data().put("at", now);
		if (!(last instanceof Long)) return;
		float days = Global.getSector().getClock().getElapsedDaysSince((Long) last);
		if (days <= 0f) return;

		float t = breakEven();
		List<MarketAPI> humans = humanMarkets();
		List<Need> needs = new ArrayList<Need>();
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			int size = m.getSize();
			float perMonth = perMonth(size);
			float local = localSupplies(m) * days / 30f;
			// a forge world grows free to its first output at size 3 (the opening chain's seed forge)
			boolean seed = perMonth <= 0f && size == 2 && ThreatColonyManager.getForge(m) == null;
			if (perMonth <= 0f && !seed) {
				// a size-1 seed, or a forge world growing toward its first output: free
				m.getMemoryWithoutUpdate().unset(MEM_FED);
				continue;
			}
			Need n = new Need();
			n.market = m;
			n.size = size;
			n.seed = seed;
			n.want = (seed ? perMonth(3) : perMonth) * days / 30f;
			float own = seed ? 0f : Math.min(n.want, local);
			n.cap = own + (n.want - own) * (1f - importCut(m));
			n.sustain = seed ? 0f : Math.min(t * n.want, n.cap);
			// fed first while it makes more than it eats: a forge's size - 2 units
			// of 750 against 0.5 x its upkeep peak at size 6 and fall short at 8
			// (4,500 against 4,883) - the lt save's size-8 forges ate first and made
			// less than they took
			n.forge = local > 0f && local >= n.sustain;
			n.forgeStepPays = n.forge && forgeStepPays(m, t);
			n.grows = size < ThreatColonyManager.maxColonySize(m)
					&& !ThreatGroundFronts.hasFront(m) && !ThreatRazing.saturated(m);
			n.frontLY = frontLY(m, humans);
			needs.add(n);
		}
		// what a new trip may burn a month (ThreatReach): the production less
		// the fleets away and the sustenance at its share - a trip never starves
		// a colony
		float sustainMonth = 0f;
		for (Need n : needs) sustainMonth += n.sustain;
		sustainMonth *= 30f / days;
		float sustainCap = sustainShare();
		// ...and the navy charge the garrisons above the patrols pay at home (ThreatColonyManager.payNavySupplies):
		// a claim on the same stock, so a swarm that leaves on a strike moves its burn rather than adds one
		// (ThreatReach.awayFP). hw48-49: left out, the spare was overstated by the whole charge and three
		// corrections were layered on the symptom
		float navyMonth = navyChargePerMonth();
		data().put("fleets", Math.max(0f, fleetsPerMonth));
		data().put("navy", navyMonth);
		data().put("spare", ThreatFuel.perMonth(Commodities.SUPPLIES) - Math.max(0f, fleetsPerMonth) - navyMonth
				- (sustainCap > 0f ? sustainMonth / sustainCap : sustainMonth));
		ThreatReach.clearCommitted();
		if (needs.isEmpty()) return;

		// sustenance first (sustenanceDue): the colonies' break-even is a claim on the whole stock and on the whole
		// production - the fleets away and the planner's forge reserve come after it, not before; growth still
		// draws only what is free above the reserve, out of the production the fleets away leave
		boolean first = ThreatIncConfig.sustenanceFirst();
		float whole = ThreatFuel.stock(Commodities.SUPPLIES);
		float free = ThreatFuel.free(Commodities.SUPPLIES); // above the planner's reserve (ThreatFuel.reserved)
		float stock = first ? whole : free;
		float made = ThreatFuel.perMonth(Commodities.SUPPLIES) * days / 30f;
		float fleets = Math.max(0f, fleetsPerMonth) * days / 30f;
		float net = Math.max(0f, made - fleets);
		// 1. sustenance, out of the days' production up to sustainShare: forges
		// first, then the front
		Collections.sort(needs, SUSTAIN_ORDER);
		float pool = Math.min(stock, sustainShare() * (first ? made : net));
		// billed reach lets a trip draw on the stock (ThreatReach.canSustain(fp,
		// days)), which takes its burn out of net: the stock above one founding
		// kit makes the colonies' sustenance whole again, or a stock-paid trip
		// would starve them with the supplies still banked (2026-10-01 review)
		if (ThreatReach.enabled()) {
			float sustenance = 0f;
			for (Need n : needs) sustenance += n.sustain;
			if (sustenance > pool) {
				pool += Math.max(0f, Math.min(sustenance - pool, stock - pool - ThreatFuel.foundingCost()[0]));
			}
		}
		float sustained = 0f, short_ = 0f, bill = 0f;
		for (Need n : needs) {
			if (!n.seed) bill += n.want;
			float pay = Math.min(n.sustain, pool);
			n.paid = pay;
			pool -= pay;
			stock -= pay;
			sustained += pay;
			if (pay < n.sustain) short_ += n.sustain - pay;
		}
		// 2. growth, out of the stance's share, to colonies whose next size it still holds - from the free stock
		if (first) stock = Math.max(0f, Math.min(stock, free - sustained));
		float budget = Math.max(0f, feedShare() * net - sustained);
		Collections.sort(needs, growthOrder(ThreatStance.stance()));
		float grown = 0f;
		for (Need n : needs) {
			if (budget <= 0f || stock <= 0f) break;
			if (!n.grows) continue;
			float next = t * (perMonth(n.size + 1) - perMonth(n.size)) * days / 30f;
			if (budget < next) continue;
			float extra = Math.min(n.cap - n.paid, Math.min(budget, stock));
			if (extra <= 0f) continue;
			n.paid += extra;
			budget -= extra;
			stock -= extra;
			grown += extra;
		}
		// the chest full (ThreatStance.chestFull), the tithe above sustenance feeds the fleets: the spare is the
		// production less what the colonies actually took, not less their share (hw62: the fund banked 38k -> 141k FP
		// in every game while the spare sat at zero under the expansion tithe; 2026-10-08)
		if (ThreatStance.chestFull()) {
			float took = Math.max(sustainMonth, (sustained + grown) * 30f / days);
			data().put("spare", ThreatFuel.perMonth(Commodities.SUPPLIES) - Math.max(0f, fleetsPerMonth) - navyMonth - took);
		}
		float draw = Math.min(sustained + grown, first ? ThreatFuel.stock(Commodities.SUPPLIES) : ThreatFuel.free(Commodities.SUPPLIES));
		if (draw > 0f) ThreatFuel.pay(Commodities.SUPPLIES, draw, "feed");
		for (Need n : needs) {
			float share = n.want > 0f ? n.paid / n.want : 1f;
			// a seed has nothing to starve on: unfed it holds, fed it grows
			if (n.seed) share = t + (1f - t) * share;
			n.market.getMemoryWithoutUpdate().set(MEM_FED, share);
		}
		// sustenance unpaid is the one supplies shortage the planner answers on
		// its own (ThreatFuel.noteShort), and it is demand the stock did not meet
		if (short_ > 0f) {
			ThreatFuel.noteShort(Commodities.SUPPLIES);
			ThreatFuel.noteDemand(Commodities.SUPPLIES, short_);
		}
		add("bill", bill);
		add("sustained", sustained);
		add("grown", grown);
		add("short", short_);
	}

	/** Whether the forge world's next size adds more output than sustenance: one unit a size against t x the upkeep step. */
	protected static boolean forgeStepPays(MarketAPI m, float t) {
		CommodityOnMarketAPI com = m.getCommodityData(Commodities.SUPPLIES);
		if (com == null) return false;
		float unit = com.getCommodity().getEconUnit() * ThreatIncConfig.hiveSurplusMult();
		int s = m.getSize();
		return unit >= t * (perMonth(s + 1) - perMonth(s));
	}

	protected static final Comparator<Need> SUSTAIN_ORDER = new Comparator<Need>() {
		public int compare(Need a, Need b) {
			if (a.forge != b.forge) return a.forge ? -1 : 1;
			int c = Float.compare(a.frontLY, b.frontLY);
			return c != 0 ? c : b.size - a.size;
		}
	};

	/** Growth order: forges whose next size pays first; then the front (pressing), the biggest (consolidating) or the smallest (expanding). */
	protected static Comparator<Need> growthOrder(final int stance) {
		return new Comparator<Need>() {
			public int compare(Need a, Need b) {
				if (a.forgeStepPays != b.forgeStepPays) return a.forgeStepPays ? -1 : 1;
				// pressing feeds the front; expanding and consolidating the smallest.
				// Consolidating fed the biggest until 2026-10-01: ng3a's three core
				// forges grew 7 -> 8 on it (each step 2,930 supplies a month more for
				// 750 more made) and the bill went 35k -> 53k while young worlds died
				// cheap - a razing pours ~1k fuel into a size-2 world and 39k into a
				// size 5, so holding is growing the small out of reach of it
				int c;
				if (stance == ThreatStance.PRESS) c = Float.compare(a.frontLY, b.frontLY);
				else c = a.size - b.size;
				return c != 0 ? c : Float.compare(a.frontLY, b.frontLY);
			}
		};
	}

	/** Every market in the economy that is not the Threat's: the humans the front is measured to. */
	protected static List<MarketAPI> humanMarkets() {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (m == null || !m.isInEconomy() || m.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(m.getFactionId())) continue;
			out.add(m);
		}
		return out;
	}

	/** Light-years from the world to the nearest human market. */
	protected static float frontLY(MarketAPI m, List<MarketAPI> humans) {
		float best = Float.MAX_VALUE;
		for (MarketAPI h : humans) {
			best = Math.min(best, Misc.getDistanceLY(m.getLocationInHyperspace(), h.getLocationInHyperspace()));
		}
		return best;
	}

	protected static void add(String key, float v) {
		Object o = data().get(key);
		data().put(key, (o instanceof Float ? (Float) o : 0f) + v);
	}

	protected static float take(String key) {
		Object o = data().get(key);
		data().put(key, 0f);
		return o instanceof Float ? (Float) o : 0f;
	}

	/** The month's upkeep line for the census log, and the month's tallies reset. */
	public static void logMonth() {
		if (!enabled()) return;
		int growing = 0, holding = 0, shrinking = 0, free = 0, blockaded = 0, portsDown = 0;
		float perMonth = 0f;
		for (MarketAPI m : ThreatIncData.getAllLiveColonyMarkets()) {
			float u = perMonth(m.getSize());
			perMonth += u;
			if (m.getMemoryWithoutUpdate().getFloat(ThreatBlockade.HIVE_KEY) > 0f) blockaded++;
			Industry port = ThreatColonyManager.getPort(m);
			if (port != null && port.isDisrupted()) portsDown++;
			if (u <= 0f) {
				free++;
				continue;
			}
			float rate = growthRate(fedShare(m));
			if (rate > 0f) growing++;
			else if (rate < 0f) shrinking++;
			else holding++;
		}
		ThreatIncConfig.log("Colony upkeep: bill " + (int) take("bill") + " (" + (int) perMonth + "/mo), paid "
				+ (int) take("sustained") + " sustenance + " + (int) take("grown") + " growth, short "
				+ (int) take("short") + "; stance " + ThreatStance.stanceName() + " share " + feedShare()
				+ "; growing " + growing + ", holding " + holding + ", shrinking " + shrinking + ", free " + free
				+ "; blockaded " + blockaded + ", ports down " + portsDown + "; fleets away " + (int) fleetsPerMonth()
				+ "/mo, navy " + (int) navyPerMonth() + "/mo, spare " + (int) spareSupplies() + "/mo");
	}
}
