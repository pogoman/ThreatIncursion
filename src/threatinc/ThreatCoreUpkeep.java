package threatinc;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Size upkeep on the humans' core colonies (2026-10-18, the user, after the
 * audit "are the humans cheating"): the hive's table (ThreatColonyUpkeep.perMonth,
 * sizeUpkeepAt3 x ratio^(size-3) supplies a month, sizes 1-2 free) billed to
 * every colony of a mobilised faction that is no forward base - the links
 * have paid it since 2026-09-30 (ThreatFrontlines.feedSize), the core never
 * did. hw198a end: 144 human size-points at 0 against the hive's 256 paying
 * 30k a month, while the humans' depots grew to ~510k idle supplies.
 *
 * Paid as a forward base pays: out of the colony's own depot, its feeding
 * credit first (ThreatFeeding.payBase - a feeding convoy refills a short
 * depot, ThreatFeeding.shipBases), nothing drawn on other markets. The share
 * paid moves a starve counter as a link's does: at the break-even share or
 * above it recovers (growth stays vanilla's - the hive grows by paying, a
 * human colony by immigration, and this bills the keeping only); below it
 * the colony loses a size per starveDays paid nothing, half a level below
 * the line (ThreatColonyUpkeep.SHRINK_MARGIN), never below size 1. The
 * player's colonies pay only under threatinc_coreSizeUpkeepPlayer (default
 * off: a mechanic the colony screen does not show). Knob threatinc_coreSizeUpkeep.
 */
public class ThreatCoreUpkeep {

	/** The colony's starve counter in days of a level (ThreatFrontlines.Outpost.healthyDays). */
	public static final String MEM_DAYS = "$threatinc_coreUpkeepDays";
	/** The share of its size upkeep the colony paid at the last poll. */
	public static final String MEM_FED = "$threatinc_coreUpkeepFed";

	public static boolean enabled() {
		return ThreatIncConfig.coreSizeUpkeep() && ThreatColonyUpkeep.enabled();
	}

	/** Whether the colony is billed: a mobilised faction's, no forward base, not the Threat's, the player's under its knob. */
	public static boolean pays(MarketAPI market) {
		if (!enabled() || market == null || !market.isInEconomy()) return false;
		if (Factions.THREAT.equals(market.getFactionId()) || ThreatFrontlines.isOutpost(market)) return false;
		if (market.isPlayerOwned() && !ThreatIncConfig.coreSizeUpkeepPlayer()) return false;
		return ThreatWarState.warFactionIds().contains(market.getFactionId());
	}

	/** The fast poll (IncursionManager, beside ThreatReserves.poll): every billed colony pays the day's share. */
	public static void poll(float days) {
		if (!enabled() || days <= 0f) return;
		for (String factionId : ThreatWarState.warFactionIds()) {
			for (MarketAPI market : ThreatReserves.marketsOf(factionId)) {
				if (!pays(market)) continue;
				feed(market, days);
			}
		}
	}

	/** The share of its upkeep the colony paid at its last poll, 1 when it owes nothing. */
	public static float fedShare(MarketAPI market) {
		if (market == null || !pays(market)) return 1f;
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		return mem.contains(MEM_FED) ? mem.getFloat(MEM_FED) : 1f;
	}

	protected static void feed(MarketAPI market, float days) {
		float perMonth = ThreatColonyUpkeep.perMonth(market.getSize());
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float fed = 1f;
		if (perMonth > 0f) {
			float want = perMonth * days / 30f;
			float free = ThreatReserves.available(market, Commodities.SUPPLIES)
					- ThreatReserves.stagingBank(market, Commodities.SUPPLIES);
			float own = ThreatFeeding.enabled() ? ThreatFeeding.payBase(market, want, free)
					: ThreatReserves.drawAbove(market, Commodities.SUPPLIES, Math.min(want, Math.max(0f, free)));
			fed = Math.min(1f, own / want);
		}
		mem.set(MEM_FED, fed);
		float rate = ThreatColonyUpkeep.growthRate(fed);
		float perLevel = Math.max(1f, ThreatIncConfig.frontlineGrowDays());
		float counter = mem.getFloat(MEM_DAYS);
		if (rate >= 0f) {
			// kept: the counter recovers to a banked level; the size is vanilla's to grow
			mem.set(MEM_DAYS, Math.min(perLevel, counter + days * rate));
			return;
		}
		counter += days * rate * perLevel / ThreatColonyUpkeep.starveDays();
		float floor = -ThreatColonyUpkeep.SHRINK_MARGIN * perLevel;
		if (counter >= floor) {
			mem.set(MEM_DAYS, counter);
			ThreatIncConfig.logQuiet("coreupkeep:" + market.getId(), "Core upkeep: " + market.getName() + " ("
					+ market.getFactionId() + ") paid " + Math.round(fed * 100f) + "% of its " + (int) perMonth
					+ "/mo size upkeep; depot " + (int) ThreatReserves.stock(market.getId(), Commodities.SUPPLIES)
					+ " supplies");
			return;
		}
		if (market.getSize() <= 1) {
			mem.set(MEM_DAYS, floor);
			return;
		}
		float below = counter / perLevel;
		ThreatFrontlines.shrink(market);
		mem.set(MEM_DAYS, Math.max(0f, (1f + below) * perLevel));
		ThreatIncConfig.log("Core upkeep: " + market.getName() + " (" + market.getFactionId() + ") starved down to size "
				+ market.getSize() + " (paid " + Math.round(fed * 100f) + "% of its upkeep)");
	}
}
