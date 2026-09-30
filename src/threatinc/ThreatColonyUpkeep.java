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
 * first, then the worlds nearest the humans - up to the largest stance share
 * of the production (sustainShare, 0.9), never more: the hive always keeps a
 * tithe for forges, waves and fleets. h35a let sustenance take everything and
 * the base save's hive shrank until it did - 26k of 29k a month - with nothing
 * left to buy the forges that would have fed it again. The leeway is the half
 * level a colony's progress runs below its size before the size goes
 * (SHRINK_MARGIN), not the stock: a raided forge stops growth at once and
 * costs sizes only if it stays down.</li>
 * <li>Growth: the rest of each colony's upkeep, out of its stance's share of
 * the production (feedShare: expanding 0.5, pressing 0.7, consolidating 0.9),
 * to a colony only while that share still holds its next size's sustenance.
 * Forges whose next size pays for itself first; then pressing feeds the front,
 * consolidating the biggest, expanding the smallest. A size-2 world grows into
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
		if (size < 3) return 0f;
		return Math.max(0f, ThreatIncConfig.sizeUpkeepAt3())
				* (float) Math.pow(Math.max(1f, ThreatIncConfig.sizeUpkeepRatio()), size - 3);
	}

	/** The share of its upkeep that holds a colony at its size. */
	public static float breakEven() {
		return Math.max(0.05f, Math.min(0.95f, ThreatIncConfig.upkeepBreakEven()));
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
		float t = breakEven();
		if (fed >= t) return Math.min(1f, (fed - t) / (1f - t));
		return -Math.min(1f, (t - fed) / t);
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
				* ThreatIncConfig.reserveSurplusMult();
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
			float perUnit = com != null ? com.getCommodity().getEconUnit() * ThreatIncConfig.reserveSurplusMult() : 0f;
			if (perUnit > 0f) keep = Math.min(units, perMonth(market.getSize()) / perUnit);
		}
		return keep + (units - keep) * (1f - cut);
	}

	/** The share of the month's production, after fleets away, the hive lets its colonies' upkeep take (by stance). */
	public static float feedShare() {
		int stance = ThreatStance.stance();
		float share = stance == ThreatStance.PRESS ? ThreatIncConfig.feedSharePress()
				: stance == ThreatStance.CONSOLIDATE ? ThreatIncConfig.feedShareConsolidate()
				: ThreatIncConfig.feedShareExpand();
		return Math.max(0f, Math.min(1f, share));
	}

	/**
	 * Supplies a month the production leaves once the fleets away are paid and
	 * every colony's sustenance fits under sustainShare, as the last feed read
	 * it: what a new trip may burn without starving a colony (ThreatReach).
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
			n.forge = local > 0f;
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
		data().put("fleets", Math.max(0f, fleetsPerMonth));
		data().put("spare", ThreatFuel.perMonth(Commodities.SUPPLIES) - Math.max(0f, fleetsPerMonth)
				- (sustainCap > 0f ? sustainMonth / sustainCap : sustainMonth));
		ThreatReach.clearCommitted();
		if (needs.isEmpty()) return;

		float stock = ThreatFuel.stock(Commodities.SUPPLIES);
		float made = ThreatFuel.perMonth(Commodities.SUPPLIES) * days / 30f;
		float fleets = Math.max(0f, fleetsPerMonth) * days / 30f;
		float net = Math.max(0f, made - fleets);
		// 1. sustenance, out of the days' production up to sustainShare: forges
		// first, then the front
		Collections.sort(needs, SUSTAIN_ORDER);
		float pool = Math.min(stock, sustainShare() * net);
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
		// 2. growth, out of the stance's share, to colonies whose next size it still holds
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
		float draw = Math.min(sustained + grown, ThreatFuel.stock(Commodities.SUPPLIES));
		if (draw > 0f) ThreatFuel.pay(Commodities.SUPPLIES, draw);
		for (Need n : needs) {
			float share = n.want > 0f ? n.paid / n.want : 1f;
			// a seed has nothing to starve on: unfed it holds, fed it grows
			if (n.seed) share = t + (1f - t) * share;
			n.market.getMemoryWithoutUpdate().set(MEM_FED, share);
		}
		if (short_ > 0f) ThreatFuel.noteShort(Commodities.SUPPLIES);
		add("bill", bill);
		add("sustained", sustained);
		add("grown", grown);
		add("short", short_);
	}

	/** Whether the forge world's next size adds more output than sustenance: one unit a size against t x the upkeep step. */
	protected static boolean forgeStepPays(MarketAPI m, float t) {
		CommodityOnMarketAPI com = m.getCommodityData(Commodities.SUPPLIES);
		if (com == null) return false;
		float unit = com.getCommodity().getEconUnit() * ThreatIncConfig.reserveSurplusMult();
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
				int c;
				if (stance == ThreatStance.PRESS) c = Float.compare(a.frontLY, b.frontLY);
				else if (stance == ThreatStance.CONSOLIDATE) c = b.size - a.size;
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
				+ "; blockaded " + blockaded + ", ports down " + portsDown);
	}
}
