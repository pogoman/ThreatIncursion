package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.RemnantHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * The incursion clock. Once the Threat is woken (vanilla story flags), seeds
 * infestations on the uninhabited fringe, grows them into fabrication hives,
 * spreads system to system, and - in later phases - launches strikes against
 * inhabited worlds, the core included. Killing hive fleets cleanses systems
 * and slows the spread.
 */
public class IncursionManager implements EveryFrameScript {

	public static final String KEY_STRIKES = "threatinc_activeStrikes";
	public static final String TRIGGER_SENSOR_MODS = "$hasThreatDetectionSensorMods";
	public static final String TRIGGER_ENCOUNTERED = "$encounteredThreat";
	public static final String TRIGGER_ONESLAUGHT = "$foundOneslaught";

	protected IntervalUtil interval = new IntervalUtil(0.4f, 0.6f); // days between checks
	protected float daysSinceTick = 999f; // run the first tick immediately on start
	protected Random random = new Random();

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}

	public void advance(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);
		interval.advance(days);
		if (!interval.intervalElapsed()) return;

		if (!ThreatIncConfig.enabled()) return;

		// testing aid: grant the Mk.I sensor mods (10x detection vs Threat) that a
		// normal playthrough would have earned before the incursion ever starts
		if (ThreatIncConfig.debugGrantSensorMods()
				&& Global.getSector().getPlayerFleet() != null
				&& !Global.getSector().getPlayerFleet().getMemoryWithoutUpdate().getBoolean(TRIGGER_SENSOR_MODS)) {
			Global.getSector().getPlayerFleet().getMemoryWithoutUpdate().set(TRIGGER_SENSOR_MODS, true);
			ThreatIncConfig.log("Debug: granted Threat detection sensor mods.");
		}

		if (!ThreatIncData.isStarted()) {
			if (isTriggered()) {
				start();
			}
			return;
		}

		daysSinceTick += interval.getIntervalDuration();

		// hive liveness is checked every interval so cleansing feels immediate
		pollHives();
		syncSystemMarkers();

		float tickDays = ThreatIncConfig.tickDays();
		if (ThreatIncConfig.debugFastClock()) tickDays = Math.max(1f, tickDays / 10f);

		if (daysSinceTick >= tickDays) {
			daysSinceTick = 0f;
			tick();
		}
	}

	protected boolean isTriggered() {
		if (ThreatIncConfig.debugForceStart()) return true;
		if (Global.getSector().getPlayerFleet() == null) return false;
		if (Global.getSector().getPlayerFleet().getMemoryWithoutUpdate().getBoolean(TRIGGER_SENSOR_MODS)) return true;
		if (Global.getSector().getPlayerFleet().getMemoryWithoutUpdate().getBoolean(TRIGGER_ENCOUNTERED)) return true;
		if (Global.getSector().getMemoryWithoutUpdate().getBoolean(TRIGGER_ONESLAUGHT)) return true;

		// alternate trigger: a player colony grown large enough to register as a
		// major concentration of the "technological base" the Threat exists to
		// destroy - you don't have to find them for them to notice you
		if (ThreatIncConfig.colonySizeTrigger()) {
			int threshold = ThreatIncConfig.triggerColonySize();
			for (MarketAPI market : Misc.getPlayerMarkets(false)) {
				if (market.getSize() >= threshold) return true;
			}
		}
		return false;
	}

	protected void start() {
		ThreatIncData.setStarted();

		int seeds = ThreatIncConfig.initialSeeds();
		for (int i = 0; i < seeds; i++) {
			StarSystemAPI target = pickFringeSeedTarget();
			if (target != null) {
				ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
				ThreatIncConfig.log("Initial seed: " + target.getName());
			}
		}

		ThreatIncursionIntel.ensureAdded();

		MessageIntel msg = new MessageIntel(
				"Deep-space listening posts have picked up anomalous fabrication signatures "
				+ "from the darkest fringes of the sector. Whatever was woken in the Abyss "
				+ "is no longer content to stay there.",
				Misc.getNegativeHighlightColor());
		setThreatIcon(msg);
		Global.getSector().getCampaignUI().addMessage(msg);

		ThreatIncConfig.log("Incursion started.");
	}

	// ------------------------------------------------------------------
	// tick logic
	// ------------------------------------------------------------------

	protected void tick() {
		advanceStages();
		trySpread();
		tryStrikes();
		checkPhaseAnnouncements();
	}

	protected void pollHives() {
		List<String> cleansed = new ArrayList<String>();
		for (Map.Entry<String, CampaignFleetAPI> entry : ThreatIncData.hives().entrySet()) {
			CampaignFleetAPI hive = entry.getValue();
			if (hive == null || !hive.isAlive()) {
				cleansed.add(entry.getKey());
			}
		}
		for (String systemId : cleansed) {
			StarSystemAPI system = getSystem(systemId);
			ThreatIncData.clearSystem(systemId);
			ThreatIncData.incrCleansedCount();

			String name = system != null ? system.getName() : "an infested system";
			MessageIntel msg = new MessageIntel(
					"The fabrication hive in " + name + " has been destroyed. "
					+ "The infestation there collapses without it.",
					Misc.getPositiveHighlightColor());
			setThreatIcon(msg);
			Global.getSector().getCampaignUI().addMessage(msg);

			ThreatIncConfig.log("System cleansed: " + name);
		}
	}

	/**
	 * Debug fast-clock scales every duration in the incursion, not just the
	 * tick cadence - otherwise stage timers still take months of real game time.
	 */
	public static float timeScale() {
		return ThreatIncConfig.debugFastClock() ? 0.1f : 1f;
	}

	protected void advanceStages() {
		List<String> toHive = new ArrayList<String>();
		List<String> toSaturated = new ArrayList<String>();

		for (Map.Entry<String, String> entry : ThreatIncData.stages().entrySet()) {
			String systemId = entry.getKey();
			String stage = entry.getValue();
			float days = ThreatIncData.daysInStage(systemId);

			if (ThreatIncData.STAGE_SEEDED.equals(stage)
					&& days >= ThreatIncConfig.seedToHiveDays() * timeScale()) {
				toHive.add(systemId);
			} else if (ThreatIncData.STAGE_HIVE.equals(stage)
					&& days >= ThreatIncConfig.hiveToSaturatedDays() * timeScale()) {
				toSaturated.add(systemId);
			}
		}

		for (String systemId : toHive) {
			establishHive(systemId);
		}
		for (String systemId : toSaturated) {
			ThreatIncData.setStage(systemId, ThreatIncData.STAGE_SATURATED);
			StarSystemAPI system = getSystem(systemId);
			ThreatIncConfig.log("Saturated: " + (system != null ? system.getName() : systemId));
		}
	}

	protected void establishHive(String systemId) {
		StarSystemAPI system = getSystem(systemId);
		if (system == null) {
			ThreatIncData.clearSystem(systemId);
			return;
		}

		CampaignFleetAPI hive = DisposableThreatFleetManager.createThreatFleet(
				3, 0, 0, FabricatorEscortStrength.HIGH, random);
		hive.setName("Fabrication Hive");

		system.addEntity(hive);
		SectorEntityToken anchor = system.getCenter();
		Vector2f loc = Misc.getPointAtRadius(anchor.getLocation(), 3000f + random.nextFloat() * 2000f);
		hive.setLocation(loc.x, loc.y);
		hive.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, anchor, 1000000f);

		ThreatIncData.setStage(systemId, ThreatIncData.STAGE_HIVE);
		ThreatIncData.hives().put(systemId, hive);

		MessageIntel msg = new MessageIntel(
				"Dense fabrication signatures now emanate from the " + system.getNameWithLowercaseType()
				+ ". A Threat hive has taken root there.",
				Misc.getNegativeHighlightColor());
		setThreatIcon(msg);
		Global.getSector().getCampaignUI().addMessage(msg);

		ThreatIncConfig.log("Hive established: " + system.getName());
	}

	protected void trySpread() {
		int hives = ThreatIncData.countHivesAndSaturated();
		if (hives <= 0) {
			// all hives dead but seeds may remain; seeds still grow, no new spread
			return;
		}
		if (ThreatIncData.countInfested() >= ThreatIncConfig.maxInfestedSystems()) return;

		float chance = Math.min(ThreatIncConfig.spreadChanceCap(),
				ThreatIncConfig.spreadChancePerHive() * hives);
		if (random.nextFloat() >= chance) return;

		StarSystemAPI target = pickSpreadTarget();
		if (target == null) return;

		// the Remnant network resists the swarm
		if (ThreatIncConfig.remnantResists()) {
			CampaignFleetAPI nexus = RemnantHostileActivityFactor.getRemnantNexus(target);
			if (nexus != null) {
				if (random.nextFloat() < ThreatIncConfig.machineWarWinChance()) {
					nexus.despawn();
					MessageIntel msg = new MessageIntel(
							"The Remnant Nexus in the " + target.getNameWithLowercaseType()
							+ " has gone silent. Salvors report wreckage of both Remnant and unknown "
							+ "manufacture - a war between machines, and the Remnant lost.",
							Misc.getNegativeHighlightColor());
					setThreatIcon(msg);
					Global.getSector().getCampaignUI().addMessage(msg);
					ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
					ThreatIncConfig.log("Machine war won at " + target.getName() + "; nexus destroyed, system seeded.");
				} else {
					MessageIntel msg = new MessageIntel(
							"Fierce fighting between Remnant forces and unidentified constructs has been "
							+ "reported in the " + target.getNameWithLowercaseType()
							+ ". The Remnant Nexus holds - for now.",
							Misc.getHighlightColor());
					setThreatIcon(msg);
					Global.getSector().getCampaignUI().addMessage(msg);
					ThreatIncConfig.log("Machine war lost at " + target.getName() + "; nexus holds.");
				}
				return;
			}
		}

		ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
		ThreatIncConfig.log("Spread to: " + target.getName());
	}

	protected void tryStrikes() {
		if (getPhase() < 2) return;
		if (countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) return;

		for (Map.Entry<String, String> entry : ThreatIncData.stages().entrySet()) {
			if (!ThreatIncData.STAGE_SATURATED.equals(entry.getValue())) continue;
			String systemId = entry.getKey();

			Long last = ThreatIncData.lastStrikeTimes().get(systemId);
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
					< ThreatIncConfig.strikeIntervalDays() * timeScale()) {
				continue;
			}

			StarSystemAPI source = getSystem(systemId);
			CampaignFleetAPI hive = ThreatIncData.hives().get(systemId);
			if (source == null || hive == null || !hive.isAlive()) continue;

			MarketAPI target = pickStrikeTarget(source);
			if (target == null) continue;

			launchStrike(hive, source, target);
			ThreatIncData.lastStrikeTimes().put(systemId, Global.getSector().getClock().getTimestamp());
			if (countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) break;
		}
	}

	protected void launchStrike(CampaignFleetAPI hive, StarSystemAPI source, MarketAPI target) {
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);

		params.factionId = Factions.THREAT;
		params.makeFleetsHostile = false; // threat fleets are hostile by construction

		MarketAPI fake = Global.getFactory().createMarket("threatinc_source_" + source.getId(),
				"Threat hive, " + source.getName(), 3);
		fake.setPrimaryEntity(hive);
		fake.setFactionId(Factions.THREAT);
		params.source = fake;

		params.prepDays = 7f + 7f * random.nextFloat();
		params.payloadDays = 40f + 10f * random.nextFloat();

		params.raidParams.where = target.getStarSystem();
		params.raidParams.type = FGRaidType.SEQUENTIAL;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.add(target);
		params.raidParams.allowNonHostileTargets = true;
		params.raidParams.setBombardment(BombardType.SATURATION);
		params.noun = "incursion";
		params.forcesNoun = "Threat forces";

		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;

		float totalDifficulty = ThreatIncConfig.strikeStrength();
		totalDifficulty -= 10;
		params.fleetSizes.add(10);
		while (totalDifficulty > 0) {
			int diff = 5 + random.nextInt(4);
			params.fleetSizes.add(diff);
			totalDifficulty -= diff;
		}

		ThreatStrikeFGI strike = new ThreatStrikeFGI(params);
		Global.getSector().getIntelManager().addIntel(strike);
		getStrikeList().add(strike);

		if (target.isPlayerOwned()) {
			ThreatIncData.setPlayerStruck();
		}

		ThreatIncConfig.log("Strike launched from " + source.getName() + " at " + target.getName());
	}

	// ------------------------------------------------------------------
	// target selection
	// ------------------------------------------------------------------

	protected StarSystemAPI pickFringeSeedTarget() {
		WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>(random);
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (!isValidSpreadCandidate(system)) continue;
			float d = distanceToNearestInhabited(system);
			if (d <= 0) continue;
			picker.add(system, d * d); // strongly prefer the deep fringe
		}
		return picker.pick();
	}

	protected StarSystemAPI pickSpreadTarget() {
		WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>(random);
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (!isValidSpreadCandidate(system)) continue;

			float dInfested = distanceToNearestInfested(system);
			if (dInfested < 0 || dInfested > 15f) continue; // creep, don't teleport

			float w = 1f / (1f + dInfested * dInfested);
			picker.add(system, w);
		}
		return picker.pick();
	}

	protected boolean isValidSpreadCandidate(StarSystemAPI system) {
		if (system == null || system.getCenter() == null) return false;
		if (ThreatIncData.stages().containsKey(system.getId())) return false;
		// spread claims uninhabited systems only; inhabited worlds get strikes
		if (!Misc.getMarketsInLocation(system).isEmpty()) return false;
		return true;
	}

	protected float distanceToNearestInhabited(StarSystemAPI system) {
		float best = -1f;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getStarSystem() == null) continue;
			if (market.isHidden()) continue;
			float d = Misc.getDistanceLY(system.getLocation(), market.getStarSystem().getLocation());
			if (best < 0 || d < best) best = d;
		}
		return best;
	}

	protected float distanceToNearestInfested(StarSystemAPI system) {
		float best = -1f;
		for (String systemId : ThreatIncData.stages().keySet()) {
			StarSystemAPI other = getSystem(systemId);
			if (other == null) continue;
			float d = Misc.getDistanceLY(system.getLocation(), other.getLocation());
			if (best < 0 || d < best) best = d;
		}
		return best;
	}

	protected MarketAPI pickStrikeTarget(StarSystemAPI source) {
		boolean coreAllowed = getPhase() >= 3;
		boolean playerAllowed = ThreatIncData.daysSincePlayerStruck() >= ThreatIncConfig.playerGraceDays();

		WeightedRandomPicker<MarketAPI> picker = new WeightedRandomPicker<MarketAPI>(random);
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (market.isHidden()) continue;
			if (market.getSize() < 3) continue;
			// size 6+ markets are "core worlds" - phase 3 only
			if (!coreAllowed && market.getSize() >= 6) continue;
			if (market.isPlayerOwned() && !playerAllowed) continue;

			float d = Misc.getDistanceLY(source.getLocation(), market.getStarSystem().getLocation());
			if (d > ThreatIncConfig.strikeRangeLY()) continue;

			float w = 1f / (1f + d);
			picker.add(market, w);
		}
		return picker.pick();
	}

	// ------------------------------------------------------------------
	// phases, strikes bookkeeping, helpers
	// ------------------------------------------------------------------

	public static int getPhase() {
		if (!ThreatIncData.isStarted()) return 0;
		float days = ThreatIncData.daysSinceStart();
		int hives = ThreatIncData.countHivesAndSaturated();

		if (ThreatIncConfig.phase3Enabled()
				&& days >= ThreatIncConfig.phase3DelayDays() * timeScale()
				&& hives >= ThreatIncConfig.phase3MinHives()) {
			return 3;
		}
		if (days >= ThreatIncConfig.phase2DelayDays() * timeScale()
				&& hives >= ThreatIncConfig.phase2MinHives()) {
			return 2;
		}
		return 1;
	}

	protected void checkPhaseAnnouncements() {
		if (getPhase() >= 3 && !ThreatIncData.isPhase3Announced()) {
			ThreatIncData.setPhase3Announced();
			MessageIntel msg = new MessageIntel(
					"Threat incursion fleets have been sighted on approach vectors toward the core "
					+ "worlds. Nowhere in the sector is beyond their reach any longer.",
					Misc.getNegativeHighlightColor());
			setThreatIcon(msg);
			Global.getSector().getCampaignUI().addMessage(msg);
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Object> getStrikeList() {
		Object val = Global.getSector().getPersistentData().get(KEY_STRIKES);
		if (!(val instanceof List)) {
			val = new ArrayList<Object>();
			Global.getSector().getPersistentData().put(KEY_STRIKES, val);
		}
		return (List<Object>) val;
	}

	public static int countActiveStrikes() {
		int count = 0;
		List<Object> strikes = getStrikeList();
		List<Object> dead = new ArrayList<Object>();
		for (Object curr : strikes) {
			if (curr instanceof FleetGroupIntel) {
				FleetGroupIntel fgi = (FleetGroupIntel) curr;
				if (!fgi.isEnded() && !fgi.isEnding()) {
					count++;
				} else {
					dead.add(curr);
				}
			} else {
				dead.add(curr);
			}
		}
		strikes.removeAll(dead);
		return count;
	}

	protected StarSystemAPI getSystem(String systemId) {
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system.getId().equals(systemId)) return system;
		}
		return null;
	}

	/**
	 * Keeps one map-visible intel marker per infested system: adds missing
	 * markers, removes markers for systems that have been cleansed.
	 */
	protected void syncSystemMarkers() {
		java.util.Set<String> marked = new java.util.LinkedHashSet<String>();
		List<Object> stale = new ArrayList<Object>();

		for (Object curr : Global.getSector().getIntelManager().getIntel(InfestedSystemIntel.class)) {
			InfestedSystemIntel marker = (InfestedSystemIntel) curr;
			if (!ThreatIncData.stages().containsKey(marker.getSystemId())) {
				stale.add(marker);
			} else {
				marked.add(marker.getSystemId());
			}
		}
		for (Object curr : stale) {
			Global.getSector().getIntelManager().removeIntel((com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) curr);
		}

		for (String systemId : ThreatIncData.stages().keySet()) {
			if (marked.contains(systemId)) continue;
			InfestedSystemIntel marker = new InfestedSystemIntel(systemId);
			Global.getSector().getIntelManager().addIntel(marker, true);
		}
	}

	public static void setThreatIcon(MessageIntel msg) {
		try {
			String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
			if (crest != null) msg.setIcon(crest);
		} catch (Throwable t) {
			// no icon is fine
		}
	}
}
