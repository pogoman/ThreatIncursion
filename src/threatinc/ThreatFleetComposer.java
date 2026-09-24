package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.ShipRoles;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.ThreatFleetCreationParams;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * Every Threat fleet the mod builds comes through here. Vanilla's
 * createThreatFleet sizes the fleet; an archetype picked for the fleet's job
 * (data/config/threatinc_fleets.json) then re-spends the escorts' fleet points
 * on its own hull mix and favoured variants. Fabricators, and any hull the
 * mix does not name, are left as vanilla built them, so a fleet's strength
 * does not change - only what it is made of. See docs/fleet-archetypes.md.
 */
public class ThreatFleetComposer {

	public static final String CONFIG_PATH = "data/config/threatinc_fleets.json";

	/** Archetype id stamped on every fleet built here. */
	public static final String ARCHETYPE_KEY = "$threatinc_archetype";

	public static final String JOB_STRIKE = "strike";
	public static final String JOB_GARRISON = "garrison";
	public static final String JOB_SEEDING = "seeding";
	public static final String JOB_SCOUT = "scout";

	public static final String HOST = "host";
	public static final String HUNTER = "hunter";
	public static final String SCOUT = "scout";

	protected static class Archetype {
		String id;
		Map<String, Float> mix = new LinkedHashMap<String, Float>();
		Map<String, Float> favour = new HashMap<String, Float>();
	}

	// loaded once per game launch; the file does not change under a running game
	private static Map<String, Archetype> archetypes = null;
	private static Map<String, Map<String, Float>> jobs = null;
	private static Map<String, List<String>> variantsByHull = null;
	private static boolean loadFailed = false;

	// ------------------------------------------------------------------
	// entry points
	// ------------------------------------------------------------------

	/** A vanilla-sized Threat fleet, composed as an archetype picked for the job. */
	public static CampaignFleetAPI create(String job, int fabricators,
			FabricatorEscortStrength escorts, Random random) {
		if (random == null) random = new Random();
		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				fabricators, 0, 0, escorts, random);
		if (fleet == null) return null;
		String archetype = pickArchetype(job, random);
		if (archetype != null) recompose(fleet, archetype, random);
		return fleet;
	}

	/**
	 * A scouting party of about the given fleet points, from the "scout" job.
	 * Carries every flag createThreatFleet gives a Threat fleet (hostile,
	 * aggressive, long pursuit, the Threat interaction dialog) and is named
	 * "Scouting Swarm"; the caller places it and gives it orders. Never null
	 * while the Threat faction exists - with archetypes off it is vanilla
	 * frigates.
	 */
	public static CampaignFleetAPI createScouts(float fp, Random random) {
		if (random == null) random = new Random();
		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				new ThreatFleetCreationParams(), random);
		String archetype = pickArchetype(JOB_SCOUT, random);
		if (archetype == null) archetype = SCOUT;
		Archetype a = archetypes != null ? archetypes.get(archetype) : null;
		if (a != null) {
			fill(fleet, a, fp, random);
			fleet.getMemoryWithoutUpdate().set(ARCHETYPE_KEY, archetype);
		} else {
			// archetypes off or the file failed: vanilla skirmish picks
			int n = Math.max(1, Math.round(fp / 7f));
			DisposableThreatFleetManager.addShips(fleet, n, ShipRoles.COMBAT_SMALL, random);
		}
		finish(fleet);
		fleet.setName("Scouting Swarm");
		return fleet;
	}

	/** The archetype a fleet was built as, or null for a fleet not built here. */
	public static String archetypeOf(CampaignFleetAPI fleet) {
		if (fleet == null) return null;
		Object v = fleet.getMemoryWithoutUpdate().get(ARCHETYPE_KEY);
		return v instanceof String ? (String) v : null;
	}

	/** Weighted pick from the job's archetype pool; null when archetypes are off. */
	public static String pickArchetype(String job, Random random) {
		if (!ThreatIncConfig.fleetArchetypes() || !load()) return null;
		Map<String, Float> pool = jobs.get(job);
		if (pool == null) return null;
		WeightedRandomPicker<String> picker = new WeightedRandomPicker<String>(random);
		for (Map.Entry<String, Float> e : pool.entrySet()) {
			if (archetypes.containsKey(e.getKey())) picker.add(e.getKey(), e.getValue());
		}
		return picker.isEmpty() ? null : picker.pick();
	}

	/**
	 * Strips the hulls the archetype's mix names and spends their fleet
	 * points again on its mix. Everything else - fabricators, foreign hulls -
	 * stays.
	 */
	public static void recompose(CampaignFleetAPI fleet, String archetypeId, Random random) {
		if (!load()) return;
		Archetype a = archetypes.get(archetypeId);
		if (a == null) return;
		float budget = 0f;
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (!a.mix.containsKey(m.getHullSpec().getBaseHullId())) continue;
			budget += m.getFleetPointCost();
			fleet.getFleetData().removeFleetMember(m);
		}
		fill(fleet, a, budget, random);
		finish(fleet);
		fleet.getMemoryWithoutUpdate().set(ARCHETYPE_KEY, archetypeId);
		ThreatIncConfig.log("Composed " + fleet.getName() + " as " + archetypeId
				+ " (" + Math.round(budget) + " escort FP)");
	}

	// ------------------------------------------------------------------
	// composition
	// ------------------------------------------------------------------

	/**
	 * Adds ships until the budget is spent. A hull's share of the picks is
	 * its mix weight over its fleet points, so the mix reads as a share of FP;
	 * a hull may be picked while at least half its FP is left, so the fleet
	 * lands within half a ship of the budget either way.
	 */
	private static void fill(CampaignFleetAPI fleet, Archetype a, float budget, Random random) {
		Map<String, Float> hullFP = new HashMap<String, Float>();
		for (String hull : a.mix.keySet()) {
			List<String> variants = variantsByHull.get(hull);
			if (variants == null || variants.isEmpty()) continue;
			hullFP.put(hull, (float) Global.getSettings().getVariant(variants.get(0))
					.getHullSpec().getFleetPoints());
		}
		float left = budget;
		int added = 0;
		while (left > 0f) {
			WeightedRandomPicker<String> picker = new WeightedRandomPicker<String>(random);
			for (Map.Entry<String, Float> e : hullFP.entrySet()) {
				float fp = Math.max(1f, e.getValue());
				float w = a.mix.get(e.getKey());
				if (w > 0f && left >= fp * 0.5f) picker.add(e.getKey(), w / fp);
			}
			if (picker.isEmpty()) break;
			String hull = picker.pick();
			fleet.getFleetData().addFleetMember(pickVariant(hull, a, random));
			left -= hullFP.get(hull);
			added++;
		}
		if (added == 0 && budget > 0f) {
			// too small for any hull at half cost: one of the cheapest
			String cheapest = null;
			for (Map.Entry<String, Float> e : hullFP.entrySet()) {
				if (a.mix.get(e.getKey()) <= 0f) continue;
				if (cheapest == null || e.getValue() < hullFP.get(cheapest)) cheapest = e.getKey();
			}
			if (cheapest != null) fleet.getFleetData().addFleetMember(pickVariant(cheapest, a, random));
		}
	}

	private static String pickVariant(String hull, Archetype a, Random random) {
		WeightedRandomPicker<String> picker = new WeightedRandomPicker<String>(random);
		for (String id : variantsByHull.get(hull)) {
			Float w = a.favour.get(id);
			picker.add(id, w != null ? w : 1f);
		}
		return picker.pick();
	}

	/** What createThreatFleet does after adding ships. */
	private static void finish(CampaignFleetAPI fleet) {
		fleet.getFleetData().setSyncNeeded();
		fleet.getFleetData().syncIfNeeded();
		fleet.getFleetData().sort();
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			m.getRepairTracker().setCR(m.getRepairTracker().getMaxCR());
		}
	}

	// ------------------------------------------------------------------
	// loading
	// ------------------------------------------------------------------

	private static boolean load() {
		if (archetypes != null) return true;
		if (loadFailed) return false;
		try {
			JSONObject json = Global.getSettings().getMergedJSON(CONFIG_PATH);
			float favourWeight = (float) json.optDouble("favourWeight", 4.0);

			Map<String, Archetype> arch = new LinkedHashMap<String, Archetype>();
			JSONObject aj = json.getJSONObject("archetypes");
			for (Iterator<?> it = aj.keys(); it.hasNext();) {
				String id = (String) it.next();
				JSONObject o = aj.getJSONObject(id);
				Archetype a = new Archetype();
				a.id = id;
				a.mix = floats(o.getJSONObject("mix"));
				JSONArray fav = o.optJSONArray("favour");
				if (fav != null) {
					for (int i = 0; i < fav.length(); i++) a.favour.put(fav.getString(i), favourWeight);
				}
				arch.put(id, a);
			}
			Map<String, Map<String, Float>> jb = new HashMap<String, Map<String, Float>>();
			JSONObject jj = json.getJSONObject("jobs");
			for (Iterator<?> it = jj.keys(); it.hasNext();) {
				String job = (String) it.next();
				jb.put(job, floats(jj.getJSONObject(job)));
			}
			variantsByHull = threatVariants();
			jobs = jb;
			archetypes = arch;
			return true;
		} catch (Exception e) {
			loadFailed = true;
			Global.getLogger(ThreatFleetComposer.class).error(
					"[ThreatInc] could not load " + CONFIG_PATH + " - Threat fleets use vanilla's mix", e);
			return false;
		}
	}

	private static Map<String, Float> floats(JSONObject o) throws Exception {
		Map<String, Float> out = new LinkedHashMap<String, Float>();
		for (Iterator<?> it = o.keys(); it.hasNext();) {
			String k = (String) it.next();
			out.put(k, (float) o.getDouble(k));
		}
		return out;
	}

	/**
	 * Every variant the Threat's ship roles name, by base hull - vanilla's
	 * and this mod's (data/world/factions/default_ship_roles.json), so a new
	 * variant only has to be added to the roles to be picked here.
	 */
	private static Map<String, List<String>> threatVariants() {
		FactionAPI faction = Global.getSector().getFaction(Factions.THREAT);
		String[] roles = {ShipRoles.COMBAT_SMALL, ShipRoles.COMBAT_MEDIUM, ShipRoles.COMBAT_LARGE,
				ShipRoles.COMBAT_CAPITAL, ShipRoles.THREAT_FABRICATOR, ShipRoles.THREAT_HIVE,
				ShipRoles.THREAT_OVERSEER};
		Map<String, List<String>> out = new HashMap<String, List<String>>();
		for (String role : roles) {
			for (String id : faction.getVariantsForRole(role)) {
				if (!Global.getSettings().doesVariantExist(id)) continue;
				ShipVariantAPI v = Global.getSettings().getVariant(id);
				if (v.getHullSpec() == null || !v.getHullSpec().hasTag("threat")) continue;
				String hull = v.getHullSpec().getBaseHullId();
				List<String> list = out.get(hull);
				if (list == null) out.put(hull, list = new ArrayList<String>());
				if (!list.contains(id)) list.add(id);
			}
		}
		return out;
	}
}
