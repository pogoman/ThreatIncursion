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
import com.fs.starfarer.api.campaign.econ.MarketAPI;
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
	/** Converted variants by vanilla base hull (the ledger's shapes), filled with variantsByHull. */
	private static Map<String, List<String>> convertedVariantsByBase = new HashMap<String, List<String>>();
	private static boolean loadFailed = false;

	// ------------------------------------------------------------------
	// entry points
	// ------------------------------------------------------------------

	/**
	 * Vanilla's go-slow trap (found 2026-10-09, hw126/hw127). In an asteroid
	 * belt or field (DANGEROUS_UNLESS_GO_SLOW) the fleet AI asks a fleet to go
	 * slow every frame (TacticalModule.slowDown), and CampaignFleet.doGoSlow
	 * brakes it by steering for a point 10,000 units behind it: a whole frame at
	 * full acceleration. A background system is advanced 60 iterations at once
	 * (2 s a frame on fast-forward) and a swarm accelerates by about its top
	 * speed a second, so the brake reverses the fleet at full speed instead of
	 * stopping it, and the next frame reverses it back - it shakes between two
	 * points 6 s of travel apart for good (12 of 12 frozen swarms, every one in
	 * a belt; 6-18% of reinforcements a run). Every fleet the mod builds or
	 * orders, both sides, carries this guard: on a frame long enough for the
	 * brake to overshoot it sets the speed just under the go-slow speed first,
	 * so there is nothing to brake and the fleet crosses at speed. At normal
	 * speed (1 s frames) the brake cannot overshoot and vanilla is untouched;
	 * vanilla's own fleets are left to vanilla.
	 */
	public static void beltGuard(CampaignFleetAPI fleet) {
		if (fleet == null || fleet.hasScriptOfClass(BeltGuard.class)) return;
		fleet.addScript(new BeltGuard(fleet));
	}

	/**
	 * The per-frame half of {@link #beltGuard}. A fleet's scripts run after its
	 * AI (which sets the go-slow request) and before doGoSlow and the movement
	 * module, so this is the one place the request can be met in time.
	 */
	public static class BeltGuard implements com.fs.starfarer.api.EveryFrameScript {
		protected CampaignFleetAPI fleet;
		/** Frames on which the guard met an overshooting brake (read by the overdue line). */
		public int trips;
		public BeltGuard(CampaignFleetAPI fleet) { this.fleet = fleet; }
		public boolean isDone() { return fleet == null || !fleet.isAlive(); }
		public boolean runWhilePaused() { return false; }
		public void advance(float amount) {
			if (amount <= 0f || fleet == null || !fleet.getGoSlowOneFrame()) return;
			// the movement module's velocity, which the brake reads - getVelocity() is the
			// entity's copy, overwritten from the module after the movement step (hw128:
			// scaling that copy tripped 2,000 times a fleet and changed nothing)
			org.lwjgl.util.vector.Vector2f v = fleet.getVelocityFromMovementModule();
			float speed = v.length();
			float slow = fleet.getGoSlowStop() ? 0f
					: com.fs.starfarer.api.util.Misc.getSpeedForBurnLevel(
							com.fs.starfarer.api.util.Misc.getGoSlowBurnLevel(fleet));
			// the brake lands past the go-slow speed in reverse: the trap
			if (fleet.getAcceleration() * amount <= speed + slow) return;
			float k = speed > 0.001f ? slow * 0.95f / speed : 0f;
			fleet.setVelocity(v.x * k, v.y * k);
			trips++;
		}
	}

	/** A vanilla-sized Threat fleet, composed as an archetype picked for the job; no converted hulls (no ledger). */
	public static CampaignFleetAPI create(String job, int fabricators,
			FabricatorEscortStrength escorts, Random random) {
		return create(job, fabricators, escorts, random, null);
	}

	/**
	 * As above, built at a hive world: the archetype's converted_* groups are filled from that
	 * world's reclaimed-hull ledger (ThreatReclaimed), and from nothing with {@code ledger} null.
	 */
	public static CampaignFleetAPI create(String job, int fabricators,
			FabricatorEscortStrength escorts, Random random, MarketAPI ledger) {
		if (random == null) random = new Random();
		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				fabricators, 0, 0, escorts, random);
		if (fleet == null) return null;
		beltGuard(fleet);
		String archetype = pickArchetype(job, random);
		if (archetype != null) recompose(fleet, archetype, random, ledger);
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
		beltGuard(fleet);
		String archetype = pickArchetype(JOB_SCOUT, random);
		// the scout archetype is the default only while archetypes are on: loaded
		// once, the table outlived the knob being turned off mid-session
		if (archetype == null && ThreatIncConfig.fleetArchetypes()) archetype = SCOUT;
		Archetype a = archetype != null && archetypes != null ? archetypes.get(archetype) : null;
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

	/**
	 * A fleet of about the given fleet points for the job (phase C, 2026-10-25: a budded Defend or
	 * shift fleet, ThreatBudding.spawn), built as createScouts builds a scouting party: the job's
	 * archetype filled to the budget, or vanilla picks by weight class with archetypes off. Carries
	 * every flag a Threat fleet gets; unnamed - the caller names, tags and places it.
	 */
	public static CampaignFleetAPI createSized(String job, float fp, Random random) {
		return createSized(job, fp, random, null);
	}

	/** As above, budded at a hive world whose reclaimed-hull ledger fills the converted_* groups. */
	public static CampaignFleetAPI createSized(String job, float fp, Random random, MarketAPI ledger) {
		if (random == null) random = new Random();
		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				new ThreatFleetCreationParams(), random);
		beltGuard(fleet);
		// a fabricator (600 FP) per threatinc_fabricatorPerFP of the budget, at most fabricatorsMax
		// (the user, 2026-11-08: "above 1500 FP"; nothing had bought one since budding, 2026-10-25),
		// paid out of the budget before the mix; tagged so a strike re-embodies the swarm with it
		// (ThreatColonyManager.expeditionSizeFor reads SWARM_FABS_KEY as expedition size 9)
		int fabricators = fabricatorsFor(fp);
		if (fabricators > 0) {
			DisposableThreatFleetManager.addShips(fleet, fabricators, ShipRoles.THREAT_FABRICATOR, random);
			fleet.getFleetData().setSyncNeeded();
			fleet.getFleetData().syncIfNeeded();
			fp = Math.max(0f, fp - fleet.getFleetPoints());
			// the flagship: every troop break-up spares it (ThreatStrikeFGI.embark,
			// ThreatGroundFronts.applyFleetLosses), so the 600 FP are never counted as troops
			for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
				if (isFabricator(m)) {
					fleet.getFleetData().setFlagship(m);
					break;
				}
			}
			fleet.getMemoryWithoutUpdate().set(ThreatColonyManager.SWARM_TIER_KEY, FabricatorEscortStrength.HIGH.ordinal());
			fleet.getMemoryWithoutUpdate().set(ThreatColonyManager.SWARM_FABS_KEY, fabricators);
		}
		String archetype = pickArchetype(job, random);
		Archetype a = archetype != null && archetypes != null ? archetypes.get(archetype) : null;
		if (a != null) {
			fill(fleet, a, fp, random, ledger);
			fleet.getMemoryWithoutUpdate().set(ARCHETYPE_KEY, archetype);
		} else {
			// archetypes off or the file failed: vanilla picks, two fifths of the points in capitals
			DisposableThreatFleetManager.addShips(fleet, Math.max(0, Math.round(fp * 0.4f / 40f)), ShipRoles.COMBAT_LARGE, random);
			DisposableThreatFleetManager.addShips(fleet, Math.max(0, Math.round(fp * 0.35f / 15f)), ShipRoles.COMBAT_MEDIUM, random);
			DisposableThreatFleetManager.addShips(fleet, Math.max(1, Math.round(fp * 0.25f / 6f)), ShipRoles.COMBAT_SMALL, random);
		}
		finish(fleet);
		return fleet;
	}

	/** Fabricators a budded fleet of the points carries: one per threatinc_fabricatorPerFP (0 = none), at most threatinc_fabricatorsMax. */
	public static int fabricatorsFor(float fp) {
		float per = ThreatIncConfig.fabricatorPerFP();
		if (per <= 0f || fp < per) return 0;
		return Math.max(0, Math.min(ThreatIncConfig.fabricatorsMax(), (int) Math.floor(fp / per)));
	}

	/** Whether the member is a fabricator hull (Tags.THREAT_FABRICATOR). */
	public static boolean isFabricator(FleetMemberAPI m) {
		return m != null && m.getHullSpec() != null
				&& m.getHullSpec().hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.THREAT_FABRICATOR);
	}

	/** A fabricator hull's fleet points (fabricator_unit, 600), from its spec. */
	private static float fabricatorFP() {
		try {
			float fp = Global.getSettings().getHullSpec("fabricator_unit").getFleetPoints();
			if (fp > 0f) return fp;
		} catch (Throwable t) {
			// no such hull: vanilla's figure
		}
		return 600f;
	}

	/**
	 * Fabricators for the fleets already flying - daily (IncursionManager) and once on load: every
	 * Threat fleet of fabricatorPerFP points or more with fewer fabricators than fabricatorsFor its
	 * points gets the rest (anchorFleet). The fleets a save already held when the rule arrived, and
	 * fleets grown or packed from smaller swarms, are what this reaches (the user, 2026-11-08, a
	 * mid-war save around size-6 and size-7 worlds: "not a single fabricator in sight"). Returns the
	 * fleets changed.
	 */
	public static int anchorFleets() {
		if (ThreatIncConfig.fabricatorPerFP() <= 0f) return 0;
		int done = 0;
		List<com.fs.starfarer.api.campaign.LocationAPI> locs =
				new java.util.ArrayList<com.fs.starfarer.api.campaign.LocationAPI>(Global.getSector().getStarSystems());
		locs.add(Global.getSector().getHyperspace());
		for (com.fs.starfarer.api.campaign.LocationAPI loc : locs) {
			if (loc == null) continue;
			for (CampaignFleetAPI fleet : new java.util.ArrayList<CampaignFleetAPI>(loc.getFleets())) {
				if (fleet == null || fleet.getFaction() == null
						|| !com.fs.starfarer.api.impl.campaign.ids.Factions.THREAT.equals(fleet.getFaction().getId())) continue;
				if (fleet.getBattle() != null || fleet.isDespawning() || !fleet.isAlive()) continue;
				if (anchorFleet(fleet, null)) done++;
			}
		}
		if (done > 0) ThreatIncConfig.log("Fabricators: " + done + " fleet(s) given theirs");
		return done;
	}

	/**
	 * Trades the fleet's smallest plain Threat hulls, a fabricator's points each, for the fabricators
	 * fabricatorsFor its points says it lacks: FP-neutral, so the strata the fleet embodies are
	 * unchanged. Converted hulls (their shapes sit on a world's ledger), hives, overseers and
	 * fabricators are never traded; a fleet whose tradeable hulls fall short waits. The first
	 * fabricator becomes the flagship, and the fleet is tagged for a strike to re-embody it with one.
	 */
	public static boolean anchorFleet(CampaignFleetAPI fleet, Random random) {
		if (fleet == null || fleet.getFleetData() == null) return false;
		float fp = fleet.getFleetPoints();
		int want = fabricatorsFor(fp);
		if (want <= 0) return false;
		int have = 0;
		List<FleetMemberAPI> pool = new java.util.ArrayList<FleetMemberAPI>();
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (isFabricator(m)) { have++; continue; }
			if (m.getHullSpec() == null || m.isFighterWing()) continue;
			if (m.getHullSpec().hasTag(CONVERTED_TAG)) continue;
			if (m.getHullSpec().hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.THREAT_HIVE)) continue;
			if (m.getHullSpec().hasTag(com.fs.starfarer.api.impl.campaign.ids.Tags.THREAT_OVERSEER)) continue;
			pool.add(m);
		}
		int add = want - have;
		if (add <= 0) return false;
		float need = add * fabricatorFP();
		float poolFP = 0f;
		for (FleetMemberAPI m : pool) poolFP += m.getFleetPointCost();
		if (poolFP < need) return false;
		java.util.Collections.sort(pool, new java.util.Comparator<FleetMemberAPI>() {
			public int compare(FleetMemberAPI a, FleetMemberAPI b) {
				return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
			}
		});
		float removed = 0f;
		int hulls = 0;
		for (FleetMemberAPI m : pool) {
			if (removed >= need) break;
			fleet.getFleetData().removeFleetMember(m);
			removed += m.getFleetPointCost();
			hulls++;
		}
		DisposableThreatFleetManager.addShips(fleet, add, ShipRoles.THREAT_FABRICATOR, random != null ? random : new Random());
		fleet.getFleetData().setSyncNeeded();
		fleet.getFleetData().syncIfNeeded();
		fleet.getFleetData().sort();
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (isFabricator(m)) {
				fleet.getFleetData().setFlagship(m);
				break;
			}
		}
		fleet.getMemoryWithoutUpdate().set(ThreatColonyManager.SWARM_TIER_KEY, FabricatorEscortStrength.HIGH.ordinal());
		fleet.getMemoryWithoutUpdate().set(ThreatColonyManager.SWARM_FABS_KEY, want);
		ThreatIncConfig.log("Fabricators: " + fleet.getName() + " (" + (int) fp + " FP) trades " + hulls + " hulls, "
				+ (int) removed + " FP, for " + add + " fabricator(s); " + (int) fleet.getFleetPoints() + " FP now");
		return true;
	}

	/**
	 * A fleet of about the given fleet points built as one named archetype (debug: the Reclaimed Swarm,
	 * ThreatDebugWar.pollReclaimed), its converted_* groups filled from the world's ledger. Null when
	 * archetypes are off or the archetype is unknown.
	 */
	public static CampaignFleetAPI createArchetype(String archetypeId, float fp, Random random, MarketAPI ledger) {
		if (random == null) random = new Random();
		// the table loads on first use; a debug switch can fire before any fleet has been built this session
		if (!ThreatIncConfig.fleetArchetypes() || !load()) return null;
		Archetype a = archetypeId != null ? archetypes.get(archetypeId) : null;
		if (a == null) return null;
		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				new ThreatFleetCreationParams(), random);
		beltGuard(fleet);
		fill(fleet, a, fp, random, ledger);
		fleet.getMemoryWithoutUpdate().set(ARCHETYPE_KEY, archetypeId);
		finish(fleet);
		return fleet;
	}

	/**
	 * Carves a fleet of about targetFP off {@code from}: its smallest ships first, until the piece
	 * reaches the target, never past capFP and never the last ship. The piece carries every flag a
	 * Threat fleet gets (createThreatFleet) and the belt guard; the caller names, tags and places
	 * it. Null when nothing moved (one ship, or none under the cap).
	 */
	public static CampaignFleetAPI splitOff(CampaignFleetAPI from, float targetFP, float capFP, Random random) {
		if (from == null || targetFP <= 0f) return null;
		if (random == null) random = new Random();
		java.util.List<FleetMemberAPI> members = from.getFleetData().getMembersListCopy();
		if (members.size() < 2) return null;
		java.util.Collections.sort(members, new java.util.Comparator<FleetMemberAPI>() {
			public int compare(FleetMemberAPI a, FleetMemberAPI b) {
				return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
			}
		});
		CampaignFleetAPI piece = DisposableThreatFleetManager.createThreatFleet(
				new ThreatFleetCreationParams(), random);
		if (piece == null) return null;
		float moved = 0f;
		for (FleetMemberAPI m : members) {
			if (moved >= targetFP || from.getFleetData().getNumMembers() <= 1) break;
			float fp = m.getFleetPointCost();
			// sorted smallest first: a ship over the cap means every later one is too
			if (moved + fp > capFP) break;
			from.getFleetData().removeFleetMember(m);
			piece.getFleetData().addFleetMember(m);
			moved += fp;
		}
		if (moved <= 0f) return null;
		from.getFleetData().ensureHasFlagship();
		piece.getFleetData().ensureHasFlagship();
		finish(from);
		finish(piece);
		beltGuard(piece);
		piece.setName(from.getName());
		return piece;
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
		recompose(fleet, archetypeId, random, null);
	}

	/** As above, with the hive world whose reclaimed-hull ledger fills the converted_* groups (null: none). */
	public static void recompose(CampaignFleetAPI fleet, String archetypeId, Random random, MarketAPI ledger) {
		if (!load()) return;
		Archetype a = archetypes.get(archetypeId);
		if (a == null) return;
		float budget = 0f;
		for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
			if (!a.mix.containsKey(mixKey(m.getHullSpec()))) continue;
			budget += m.getFleetPointCost();
			fleet.getFleetData().removeFleetMember(m);
		}
		fill(fleet, a, budget, random, ledger);
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
		fill(fleet, a, budget, random, null);
	}

	/**
	 * As above; a converted_* group in the mix is priced at the ledger world's mean shape of the
	 * class and each pick takes a shape off that ledger (ThreatReclaimed.draw), so a world buds only
	 * the hulls it has reclaimed. Without a ledger, or with none of the class on it, the group is
	 * empty and its share goes to the mix's other hulls.
	 */
	private static void fill(CampaignFleetAPI fleet, Archetype a, float budget, Random random, MarketAPI ledger) {
		// a mix key's price: its hull's FP, or the ledger's mean over a converted group's shapes
		Map<String, Float> hullFP = new HashMap<String, Float>();
		for (String hull : a.mix.keySet()) {
			if (isConvertedGroup(hull)) {
				if (ledger == null || convertedVariantsByBase.isEmpty()) continue;
				float mean = ThreatReclaimed.meanFP(ledger, hull);
				if (mean > 0f) hullFP.put(hull, mean);
				continue;
			}
			List<String> variants = variantsByHull.get(hull);
			if (variants == null || variants.isEmpty()) continue;
			float sum = 0f;
			for (String id : variants) sum += variantFP(id);
			hullFP.put(hull, sum / variants.size());
		}
		float left = budget;
		int added = 0, converted = 0;
		while (left > 0f) {
			WeightedRandomPicker<String> picker = new WeightedRandomPicker<String>(random);
			for (Map.Entry<String, Float> e : hullFP.entrySet()) {
				float fp = Math.max(1f, e.getValue());
				float w = a.mix.get(e.getKey());
				if (w > 0f && left >= fp * 0.5f) picker.add(e.getKey(), w / fp);
			}
			if (picker.isEmpty()) break;
			String key = picker.pick();
			String variant;
			if (isConvertedGroup(key)) {
				variant = pickConverted(ledger, key, a, random);
				if (variant == null) {
					hullFP.remove(key);
					continue;
				}
				float mean = ThreatReclaimed.meanFP(ledger, key);
				if (mean > 0f) hullFP.put(key, mean);
				else hullFP.remove(key);
				converted++;
			} else {
				variant = pickVariant(key, a, random);
			}
			fleet.getFleetData().addFleetMember(variant);
			left -= variantFP(variant);
			added++;
		}
		if (converted > 0 && ledger != null) {
			ThreatIncConfig.log("Reclaimed: " + fleet.getName() + " buds " + converted + " converted of " + added
					+ " hulls off " + ledger.getName() + "'s ledger; " + ThreatReclaimed.total(ledger) + " shapes left");
		}
		if (added == 0 && budget > 0f) {
			// too small for any hull at half cost: one of the cheapest Threat hulls
			String cheapest = null;
			for (Map.Entry<String, Float> e : hullFP.entrySet()) {
				if (isConvertedGroup(e.getKey()) || a.mix.get(e.getKey()) <= 0f) continue;
				if (cheapest == null || e.getValue() < hullFP.get(cheapest)) cheapest = e.getKey();
			}
			if (cheapest != null) fleet.getFleetData().addFleetMember(pickVariant(cheapest, a, random));
		}
	}

	public static boolean isConvertedGroup(String mixKey) {
		return mixKey != null && mixKey.startsWith("converted_");
	}

	/**
	 * A shape of the group off the ledger, as one of its converted variants (the archetype's favour
	 * applies by variant id); null with none of the class on the ledger. A shape with no variant
	 * loaded (the knob turned off mid-game) goes back on the ledger.
	 */
	private static String pickConverted(MarketAPI ledger, String group, Archetype a, Random random) {
		String hull = ThreatReclaimed.draw(ledger, group, random);
		if (hull == null) return null;
		List<String> variants = convertedVariantsByBase.get(hull);
		if (variants == null || variants.isEmpty()) {
			ThreatReclaimed.claimHull(ledger, hull, 0f);
			return null;
		}
		WeightedRandomPicker<String> picker = new WeightedRandomPicker<String>(random);
		for (String id : variants) {
			Float w = a.favour.get(id);
			picker.add(id, w != null ? w : 1f);
		}
		return picker.pick();
	}

	private static float variantFP(String variantId) {
		return Global.getSettings().getVariant(variantId).getHullSpec().getFleetPoints();
	}

	/** Hull tag the generator stamps on every converted skin (tools/converted-hulls/gen.py). */
	public static final String CONVERTED_TAG = "threatinc_converted";

	/**
	 * The key an archetype's mix names a hull by: the base hull id, or for a converted vanilla
	 * hull (a Threat skin of one, 2026-11-07) its weight class as a group - converted_frigate,
	 * converted_destroyer, converted_cruiser, converted_capital - so a mix buys "a converted
	 * cruiser" and the variant picker chooses which.
	 */
	public static String mixKey(com.fs.starfarer.api.combat.ShipHullSpecAPI spec) {
		if (spec.hasTag(CONVERTED_TAG)) {
			switch (spec.getHullSize()) {
				case FRIGATE: return "converted_frigate";
				case DESTROYER: return "converted_destroyer";
				case CRUISER: return "converted_cruiser";
				case CAPITAL_SHIP: return "converted_capital";
				default: break;
			}
		}
		return spec.getBaseHullId();
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

	/** The ship roles the converted hulls' variants are listed under, by weight class. */
	public static final String[] CONVERTED_ROLES = {"threatincConvertedFrigate",
			"threatincConvertedDestroyer", "threatincConvertedCruiser", "threatincConvertedCapital"};

	/**
	 * Every variant the Threat's ship roles name, by mix key (base hull, or a
	 * converted group - mixKey) - vanilla's and this mod's
	 * (data/world/factions/default_ship_roles.json), so a new variant only has
	 * to be added to the roles to be picked here. The converted hulls sit in
	 * their own roles so vanilla's abyss fleets never draw them; they are read
	 * only while the convertedHulls knob is on.
	 */
	private static Map<String, List<String>> threatVariants() {
		FactionAPI faction = Global.getSector().getFaction(Factions.THREAT);
		List<String> roles = new ArrayList<String>(java.util.Arrays.asList(
				ShipRoles.COMBAT_SMALL, ShipRoles.COMBAT_MEDIUM, ShipRoles.COMBAT_LARGE,
				ShipRoles.COMBAT_CAPITAL, ShipRoles.THREAT_FABRICATOR, ShipRoles.THREAT_HIVE,
				ShipRoles.THREAT_OVERSEER));
		if (ThreatIncConfig.convertedHulls()) roles.addAll(java.util.Arrays.asList(CONVERTED_ROLES));
		Map<String, List<String>> out = new HashMap<String, List<String>>();
		for (String role : roles) {
			java.util.Set<String> ids = faction.getVariantsForRole(role);
			if (ids == null) continue;
			for (String id : ids) {
				if (!Global.getSettings().doesVariantExist(id)) continue;
				ShipVariantAPI v = Global.getSettings().getVariant(id);
				if (v.getHullSpec() == null || !v.getHullSpec().hasTag("threat")) continue;
				String hull = mixKey(v.getHullSpec());
				List<String> list = out.get(hull);
				if (list == null) out.put(hull, list = new ArrayList<String>());
				if (!list.contains(id)) list.add(id);
				if (v.getHullSpec().hasTag(CONVERTED_TAG)) {
					String base = v.getHullSpec().getBaseHullId();
					List<String> byBase = convertedVariantsByBase.get(base);
					if (byBase == null) convertedVariantsByBase.put(base, byBase = new ArrayList<String>());
					if (!byBase.contains(id)) byBase.add(id);
				}
			}
		}
		return out;
	}
}
