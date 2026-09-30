package threatinc;

import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.loading.IndustrySpecAPI;

/**
 * What a structure costs to build (2026-09-30, user's call): its vanilla build
 * cost in credits, paid in supplies at the supplies base price, times
 * structureSuppliesMult - for the hive's planner (ThreatColonyManager.buyStructure)
 * and the factions' forward bases (ThreatFrontlines.startNew, upgrade) alike.
 * Credits were ruled out: nothing the player can hit. Supplies sit on the
 * worlds that make them, so a forge is worth raiding. Founding a colony or a
 * forward base is paid separately (ThreatOutposts.npcCost), for its first
 * structures included.
 */
public class ThreatBuildCost {

	/** The hive's own structures, priced as the vanilla ones they stand in for. */
	protected static String pricedAs(String industryId) {
		if (ThreatColonyManager.THREAT_GROUND_DEFENSES.equals(industryId)) return Industries.GROUNDDEFENSES;
		if (ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(industryId)) return Industries.HEAVYBATTERIES;
		return industryId;
	}

	public static boolean enabled() {
		return ThreatIncConfig.structuresCostSupplies();
	}

	/** Build cost in credits: the spec's, scaled from the csv's cost units when the spec has not been. */
	public static float credits(String industryId) {
		IndustrySpecAPI spec = spec(industryId);
		if (spec == null) return 0f;
		float cost = spec.getCost();
		// industries.csv holds cost units; a spec below one unit's worth of credits is unscaled
		float perUnit = setting("creditsPerCostUnit", 1000f) * setting("industryBuildCostMult", 5f);
		if (cost > 0f && cost < perUnit) cost *= perUnit;
		return cost;
	}

	/** Supplies the structure costs; 0 while structures are free (structuresCostSupplies off). */
	public static float supplies(String industryId) {
		if (!enabled()) return 0f;
		float price = Global.getSettings().getCommoditySpec(Commodities.SUPPLIES).getBasePrice();
		if (price <= 0f) return 0f;
		float cost = credits(industryId) / price * Math.max(0f, ThreatIncConfig.structureSuppliesMult());
		logOnce(industryId, cost);
		return cost;
	}

	/** Days the structure takes to build: its spec's (0 for the hive's own, which have none). */
	public static float buildDays(String industryId) {
		IndustrySpecAPI spec = spec(industryId);
		return spec != null ? spec.getBuildTime() : 0f;
	}

	protected static IndustrySpecAPI spec(String industryId) {
		if (industryId == null) return null;
		try {
			return Global.getSettings().getIndustrySpec(pricedAs(industryId));
		} catch (RuntimeException e) {
			return null;
		}
	}

	protected static float setting(String key, float fallback) {
		try {
			return Global.getSettings().getFloat(key);
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	private static final Set<String> LOGGED = new HashSet<String>();

	protected static void logOnce(String industryId, float cost) {
		if (!LOGGED.add(industryId)) return;
		IndustrySpecAPI spec = spec(industryId);
		ThreatIncConfig.log("Build cost: " + industryId + " " + (int) cost + " supplies (spec cost "
				+ (spec != null ? (int) spec.getCost() : 0) + ", " + (int) buildDays(industryId) + " days)");
	}
}
