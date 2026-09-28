package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.listeners.ColonyDecivListener;
import com.fs.starfarer.api.campaign.listeners.ColonyPlayerHostileActListener;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.events.RemnantHostileActivityFactor;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction.FGRaidType;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI.GenericRaidParams;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission.FleetStyle;
import com.fs.starfarer.api.impl.campaign.missions.hub.HubMissionWithTriggers.ComplicationRepImpact;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.TempData;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * The incursion clock. Once the Threat is woken (vanilla story flags), seeds
 * infestations on the uninhabited fringe, dispatches Seeding Swarms that found
 * real fabrication colonies, grows those colonies into a self-contained hive
 * economy, and - in later phases - launches strikes against inhabited worlds,
 * converting the dead ones into new colonies. Colonies die two ways only: a
 * siege won on the ground, or saturation razing them from orbit level by
 * level (ThreatRazing) - never a timer.
 */
public class IncursionManager implements EveryFrameScript, ColonyDecivListener,
		ColonyPlayerHostileActListener,
		com.fs.starfarer.api.campaign.listeners.MarineLossesStatModifier {

	public static final String KEY_STRIKES = "threatinc_activeStrikes";
	public static final String KEY_PURGES = "threatinc_activePurges";
	public static final String TRIGGER_SENSOR_MODS = "$hasThreatDetectionSensorMods";
	public static final String TRIGGER_ENCOUNTERED = "$encounteredThreat";
	public static final String TRIGGER_ONESLAUGHT = "$foundOneslaught";

	protected IntervalUtil interval = new IntervalUtil(0.4f, 0.6f); // days between checks
	protected float daysSinceTick = 999f; // run the first tick immediately on start
	/**
	 * Fleet points per point of response difficulty: the 0-10 difficulty scale
	 * kept for config compatibility, but paid out in real FP - max difficulty
	 * is a ~250 FP battle group, not a vanilla "standard fleet".
	 */
	public static final float FP_PER_RESPONSE_DIFFICULTY = 25f;

	protected Random random = new Random();

	// Last pre-interaction player reputation with each faction that cares about
	// atrocities, captured every unpaused frame while no dialog is open. When the
	// player saturation-bombs a Threat colony, vanilla MarketCMD has already
	// applied the atrocity penalty by the time our listener fires, so we restore
	// these snapshotted values to undo it. Transient - only meaningful within the
	// frames immediately before a bombardment, never serialized.
	protected transient Map<String, Float> atrocityRepSnapshot;
	/** Set once the bootstrap heal has run this session (transient: every load runs it). */
	protected transient boolean bootstrapHealed;

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}

	/** The live manager (transient, re-created each load), for static callers such as retaliation. */
	protected static IncursionManager instance;

	public void advance(float amount) {
		instance = this;
		float days = Global.getSector().getClock().convertToDays(amount);

		// The engine renders intel but never advances it: an IntelInfoPlugin's
		// advance() only runs if some script drives it. Vanilla self-advancing
		// intel registers itself via Global.getSector().addScript(this) (see
		// RaidIntel) or is driven by its owning manager (BaseEventManager);
		// this mod's intel does neither, so every lifecycle that lives in
		// advanceImpl - mission payouts, expiry, endAfterDelay cleanup - would
		// otherwise never run. Driven here, manager-style, every frame; intel
		// already standing in old saves is picked up with no migration.
		advanceModIntel(amount);

		// per-frame: Defense Swarms snap back to their colony when lured out
		// of position - the interval throttle below is far too slow to stop a
		// kiting run (see GARRISON_LEASH_RADIUS)
		if (ThreatIncConfig.enabled()) {
			ThreatColonyManager.enforceGarrisonLeash();
			// Support / Defend fleets and the swarm's stations keep to their orbit the same way
			ThreatFleetOrders.enforceLeash();
			ThreatSwarmDefend.enforceLeash();
		}

		// Every-frame, ahead of the interval throttle: keep a fresh snapshot of
		// player reputation with the factions that punish atrocities. When the
		// player saturation-bombs a Threat colony, vanilla MarketCMD applies the
		// atrocity penalty before our listener can run, so we restore these
		// pre-bombardment values afterward. Frozen while a dialog is open (see
		// updateAtrocityRepSnapshot) so it always holds the pre-strike numbers.
		if (ThreatIncConfig.enabled() && ThreatIncConfig.bombardNoAtrocity()) {
			updateAtrocityRepSnapshot();
		}

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

		// old-layout saves must be migrated before anything touches colony data
		if (ThreatIncData.isStarted()) {
			ThreatColonyManager.migrateLegacyData(random);
			// backfill the rare-economy flag for saves started before it existed
			if (Global.getSector().getPersistentData().get(ThreatIncData.KEY_RARE_ECONOMY) == null) {
				ThreatIncData.setUsesRareEconomy(ThreatColonyManager.ogSystemHasRareOre());
			}
			// a hive frozen by an unfinished chain (docs/hive-economy.md, "The
			// bootstrap must not depend on growth") is finished on load, not a
			// tick later; transient flag, so once per session
			if (!bootstrapHealed) {
				bootstrapHealed = true;
				ThreatColonyManager.healStalledBootstrap();
				ThreatColonyManager.adoptUnbookedConquests();
			}
		}

		// debug: full reset back to pre-incursion state. Latched so it fires
		// once per toggle-on; the incursion then restarts fresh via the normal
		// triggers (turn the setting off again afterward)
		String resetLatchKey = "threatinc_resetLatched";
		if (ThreatIncConfig.debugReset()) {
			boolean latched = Boolean.TRUE.equals(
					Global.getSector().getPersistentData().get(resetLatchKey));
			if (!latched && ThreatIncData.isStarted()) {
				ThreatColonyManager.resetIncursion();
				Global.getSector().getPersistentData().put(resetLatchKey, true);
				return;
			}
		} else {
			Global.getSector().getPersistentData().remove(resetLatchKey);
		}

		if (!ThreatIncData.isStarted()) {
			if (isTriggered()) {
				start();
			}
			return;
		}

		// debug: instant war (ThreatDebugWar, docs/testing-harness.md) - latched
		// like the reset above, fires once per toggle-on; the poll below then
		// picks the new colonies up in the same pass
		ThreatDebugWar.poll(this, random);
		ThreatDebugWar.pollFloor();

		daysSinceTick += interval.getIntervalDuration();

		// discovery: physically entering an infested system reveals it
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && player.getContainingLocation() instanceof StarSystemAPI) {
			String hereId = ((StarSystemAPI) player.getContainingLocation()).getId();
			if (ThreatIncData.stages().containsKey(hereId)) {
				ThreatIncData.markDiscovered(hereId);
			}
		}

		// fast-cadence upkeep so deaths/arrivals feel immediate
		// (also migrates pre-Fabrication-Core saves: adds the structure, strips
		// the retired pop-machinery supply mod)
		// the swarm is a machine menace at war with all life: keep it pinned
		// vengeful with every faction so it never drifts back toward neutral
		enforceThreatHostility();
		ThreatColonyManager.ensureFabricationCores();
		// likewise the Core-distance accessibility offset: re-apply so it tracks
		// the shifting economy COM and survives save load and econ recompute
		ThreatColonyManager.applyHiveAccessibility();
		// worn defenses track their disruption clock daily, not monthly
		ThreatColonyManager.refreshWornDefenses();
		ThreatColonyManager.pollColonies();
		// vitality accrues CONTINUOUSLY at this poll cadence (~half-day), not
		// on the 30-day tick - tick-quantized decline left the meter stale for
		// up to a month and made short disruption windows a matter of phase luck
		ThreatColonyManager.updateColonyVitality(interval.getIntervalDuration());
		// ground fronts tick at the same continuous cadence: upkeep, attrition,
		// entrenchment, and organ suppression (docs/ground-war.md)
		ThreatGroundFronts.poll(interval.getIntervalDuration());
		// Nexerelin (optional): no invasion lands on a world the swarm besieges
		ThreatNexCompat.poll();
		// the strategy layer (docs/strategy-layer.md): war-mode stand-downs,
		// per-colony reserve accrual, convoy arrivals and losses
		ThreatWarState.poll();
		ThreatReserves.poll(interval.getIntervalDuration());
		ThreatConvoys.poll();
		ThreatRaiders.poll();
		ThreatScouts.poll(random);
		ThreatSwarmScouts.poll(random);
		// until a hive is found the sector only hears it (docs/omens.md)
		ThreatOmens.poll();
		ThreatFleetOrders.poll();
		// hunting forces muster, move on and break off on the poll, not the monthly tick
		ThreatSoftening.advanceHunts();
		// a hunt just thinned a hive system: the sieges look now, not at the
		// next monthly tick, by when the swarms have grown back
		if (siegePassPending) {
			siegePassPending = false;
			java.util.List<String> names = new ArrayList<String>();
			for (String id : THINNED.keySet()) {
				StarSystemAPI thinned = Global.getSector().getStarSystem(id);
				if (thinned != null && recentlyThinned(id)) names.add(thinned.getName());
			}
			ThreatIncConfig.log("Siege pass after a hunt thinned " + names);
			tryPurgeBombardments();
		}
		ThreatReturns.poll();
		ThreatAidCapacity.poll();
		ThreatOutposts.poll();
		ThreatFrontlines.poll(random);
		detectStrikes();
		sweepOrphanedExpeditions();
		upgradeInFlightStrikes();
		dedupDecivIntel();
		ThreatColonyManager.checkWaveArrivals(random);
		ThreatColonyManager.clearSeedingSwarmIntel();
		ThreatColonyManager.clearThreatMissionIntel();
		ThreatColonyManager.maintainGarrisons(random);
		// colonies cover each other: landed reinforcements join their new
		// garrison first (so they count as on-station), then worn-down colonies
		// draw fresh help from siblings - same system, then fuel range
		ThreatColonyManager.checkReinforcementArrivals();
		ThreatColonyManager.redistributeGarrisons();
		processPendingDecivChecks();
		syncSystemMarkers();

		float tickDays = ThreatIncConfig.tickDays();
		if (ThreatIncConfig.debugFastClock()) tickDays = Math.max(1f, tickDays / 10f);

		if (daysSinceTick >= tickDays) {
			daysSinceTick = 0f;
			tick();
		}
	}

	/**
	 * Drives the advance() of every intel plugin this mod owns - the engine
	 * only ever renders them. Copies the lists because a payout or expiry can
	 * end an intel mid-iteration.
	 */
	protected void advanceModIntel(float amount) {
		List<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin> intel =
				new ArrayList<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin>();
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatMissionIntel.class));
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatBountyIntel.class)); // legacy stub
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatResponseIntel.class));
		intel.addAll(Global.getSector().getIntelManager().getIntel(InfestedSystemIntel.class));
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatSiegeReportIntel.class));
		// help requests (2026-09-24: missing until now - posted ones never
		// withdrew, Defend windows never closed, accepted terms never ran)
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatAidMissionIntel.class));
		intel.addAll(Global.getSector().getIntelManager().getIntel(ThreatSwarmBountyIntel.class));
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr : intel) {
			if (curr instanceof EveryFrameScript) {
				((EveryFrameScript) curr).advance(amount);
			}
		}
		ThreatIncursionIntel.checkEradicated();
	}

	protected boolean isTriggered() {
		if (ThreatIncConfig.debugForceStart()) return true;
		// the instant war needs a started incursion to build on (ThreatDebugWar)
		if (ThreatIncConfig.debugInstantWar()) return true;
		if (Global.getSector().getPlayerFleet() == null) return false;
		// optional: the swarm was always coming - no story trigger required
		if (ThreatIncConfig.startAtGameStart()) return true;
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
		ThreatIncData.setDataVersion(ThreatIncData.CURRENT_DATA_VERSION);

		// grant the Threat-detection sensor mods (10x detection vs the swarm's
		// heavy stealth) if the player doesn't already have them. A normal
		// playthrough earns these before ever provoking the Threat, but the
		// colony-size trigger can start the incursion for a player who never did
		// the Abyss mission - without this they'd be effectively blind to it.
		CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
		if (playerFleet != null
				&& !playerFleet.getMemoryWithoutUpdate().getBoolean(TRIGGER_SENSOR_MODS)) {
			playerFleet.getMemoryWithoutUpdate().set(TRIGGER_SENSOR_MODS, true);
			ThreatIncConfig.log("Granted Threat detection sensor mods on war start.");
		}

		// the incursion begins from one resource-complete home system - the
		// swarm's OG base, chosen because it alone can support the whole
		// production chain (ore + volatiles mining, refining, heavy industry).
		// The bootstrap swarms from the Abyss found the full chain there; every
		// later colony is fed from this heart via the shared hive economy.
		StarSystemAPI og = pickOGSystem();
		if (og == null) og = pickFringeSeedTarget(); // no ideal system; take the best fringe
		if (og != null) {
			ThreatIncData.setStage(og.getId(), ThreatIncData.STAGE_SEEDED);
			ThreatIncData.bootstrapSeeds().add(og.getId());
			ThreatIncData.setOGSystem(og.getId());
			// rare ore is a permanent economic chokepoint whenever the home
			// system was built on it (normal starts always are)
			ThreatIncData.setUsesRareEconomy(
					ThreatColonyManager.systemHasDeposit(og, com.fs.starfarer.api.impl.campaign.ids.Commodities.RARE_ORE));
			ThreatIncConfig.log("OG home system: " + og.getName()
					+ " (deposit wealth " + (int) ThreatColonyManager.systemDepositWealth(og)
					+ ", " + ThreatColonyManager.countColonizablePlanets(og) + " planets)");
		}

		// optional extra fringe footholds beyond the OG (single colonies, fed
		// by the OG economy once its forge is online)
		int extra = Math.max(0, ThreatIncConfig.initialSeeds() - 1);
		for (int i = 0; i < extra; i++) {
			StarSystemAPI target = pickFringeSeedTarget();
			if (target != null) {
				ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
				ThreatIncData.bootstrapSeeds().add(target.getId());
				ThreatIncConfig.log("Extra initial seed: " + target.getName());
			}
		}

		ThreatIncursionIntel.ensureAdded();

		ThreatNotice.titled("The Abyss Stirs").bad()
				.line("Deep-space listening posts have picked up anomalous fabrication "
						+ "signatures from the darkest fringes of the sector.")
				.line("Whatever was woken in the %s is no longer content to stay there.", "Abyss")
				.send();

		ThreatIncConfig.log("Abyssal War started.");
	}

	// ------------------------------------------------------------------
	// tick logic
	// ------------------------------------------------------------------

	protected void tick() {
		advanceStages();
		ThreatColonyManager.maintainPorts();
		ThreatColonyManager.maintainHomeRelics();
		ThreatColonyManager.maintainHiveEconomy();
		trySpread();
		tryConversions();
		tryStrikes();
		tryPurgeBombardments();
		// bases with no siege to fight hunt the swarms of bountied hives
		// (after the sieges, which always come first)
		ThreatSoftening.tick(random);
		// mobilised factions ship war materiel to their staging bases (and
		// marines to their own worlds under Threat invasion)
		ThreatConvoys.planLogistics(random);
		// grudges fade unless renewed
		ThreatAlarm.decay();
		// allies answer open coalition calls and help each other's colonies
		ThreatCoalition.tick(random);
		// mobilised factions ask the sector for help (docs/player-aid.md)
		ThreatAidRequests.tick(random);
		// mobilised factions fortify purged worlds
		ThreatOutposts.planNPC(random);
		manageMissions();
		checkPhaseAnnouncements();
		// importers see this tick's new industries, ports and relics now, not
		// on vanilla's next monthly economy step
		ThreatColonyManager.flushEconomy();
	}

	// ------------------------------------------------------------------
	// defense-board contracts: the sector teaches the player how to fight back
	// ------------------------------------------------------------------

	/**
	 * From phase 3, the colonial defense boards offer contracts against the
	 * hive network - ordinary missions, taken or ignored the way a survey or
	 * derelict-analysis posting is. Every tick they re-score every link the
	 * swarm runs by what severing it would cost the swarm (share of that link's
	 * output, how redundant it is, how much of the network hangs off it, how
	 * close it sits to worlds still alive) against what severing it would cost
	 * a fleet (garrisons, ground defenses, the burn out there). See
	 * {@link ThreatMissionIntel#tierValue}.
	 *
	 * Several OFFERS stand at once, up to the configured cap, spread across the
	 * four levers - rare mining, fuel, refining, nexus - so the player picks a
	 * front instead of being handed one. Accepting one starts the player's
	 * completion clock; an accepted contract is theirs, sits outside the cap,
	 * is never withdrawn, and only keeps its own target from being re-offered.
	 * The board of offers evolves: when the swarm brings a genuinely more
	 * valuable target online, the weakest offer is withdrawn to make room.
	 *
	 * Churn is bounded on purpose. At most one supersession per tick; a new
	 * objective must beat the one it displaces by the configured margin, not
	 * merely edge it out; and nothing is pulled inside its minimum stand period
	 * or while the player is in the target's system (see
	 * {@link ThreatMissionIntel#canBeSuperseded}).
	 */
	protected void manageMissions() {
		if (!ThreatIncConfig.missionsEnabled()) return;
		if (!boardsMobilized()) {
			ThreatIncConfig.log("Mission board: dormant (phase " + getPhase()
					+ ", never reached phase 3)");
			return;
		}
		// an offer outlives its sponsor's war footing only until the next tick
		if (ThreatWarState.enabled()) {
			for (ThreatMissionIntel curr : ThreatMissionIntel.getPosted()) {
				if (!ThreatWarState.isAtWar(curr.getFactionId())) curr.standDown();
			}
		}
		// the boards speak in a mobilised faction's name; nobody at war, no contract
		if (ThreatWarState.enabled() && ThreatWarState.warFactionIds().isEmpty()) {
			ThreatIncConfig.log("Mission board: dormant (no faction mobilised)");
			return;
		}

		int cap = Math.max(1, ThreatIncConfig.maxPostedMissions());
		List<ThreatMissionIntel> open = ThreatMissionIntel.getOpen();
		List<ThreatMissionIntel> posted = ThreatMissionIntel.getPosted();
		ThreatIncConfig.log("Mission board: phase " + getPhase() + ", offered "
				+ posted.size() + "/" + cap + " (strategic "
				+ inTier(posted, ThreatMissionIntel.TIER_STRATEGIC).size()
				+ ", immediate "
				+ inTier(posted, ThreatMissionIntel.TIER_IMMEDIATE).size() + "), accepted "
				+ (open.size() - posted.size()));

		// 1. cover both boards first. Whatever else is offered, the sector
		// always names its decisive target AND something a captain can act on
		// now - the whole point of running two tiers.
		for (int tier = 0; tier < ThreatMissionIntel.TIER_COUNT && posted.size() < cap; tier++) {
			if (!inTier(posted, tier).isEmpty()) continue;
			ThreatMissionIntel.Objective best =
					ThreatMissionIntel.bestObjective(tier, open, null);
			if (best != null) {
				ThreatMissionIntel mission = postMission(best);
				posted.add(mission);
				open.add(mission);
			}
		}

		// 2. spare slots go to whichever board is thinner, so a cap of 3 reads
		// as 2 strategic + 1 immediate rather than drifting to one tier. Tiers
		// score on unrelated scales, so they are never compared directly.
		while (posted.size() < cap) {
			int tier = inTier(posted, ThreatMissionIntel.TIER_IMMEDIATE).size()
					< inTier(posted, ThreatMissionIntel.TIER_STRATEGIC).size()
					? ThreatMissionIntel.TIER_IMMEDIATE : ThreatMissionIntel.TIER_STRATEGIC;
			ThreatMissionIntel.Objective best =
					ThreatMissionIntel.bestObjective(tier, open, null);
			if (best == null) {
				// that board is exhausted; try the other before giving up
				best = ThreatMissionIntel.bestObjective(
						tier == ThreatMissionIntel.TIER_IMMEDIATE
								? ThreatMissionIntel.TIER_STRATEGIC
								: ThreatMissionIntel.TIER_IMMEDIATE, open, null);
			}
			if (best == null) {
				ThreatIncConfig.log("Mission board: no further candidate objectives ("
						+ ThreatIncData.getAllLiveColonyMarkets().size() + " live colonies)");
				break;
			}
			ThreatMissionIntel mission = postMission(best);
			posted.add(mission);
			open.add(mission);
		}

		if (posted.size() < cap) return;

		// 3. a board left with no offers at all while the other is full is a
		// structural fault, not a close call - correct it before considering any
		// ordinary value swap, and take only one action per tick either way.
		if (ensureTierCoverage(posted, open)) return;

		trySupersede(posted, open);
	}

	/**
	 * Guarantees the "one of each, always" rule when every offer slot is taken.
	 * Filling and superseding cannot do this between them: filling only runs
	 * while slots are free, and supersession is deliberately locked within a
	 * tier, so a board that reaches cap on one tier alone would otherwise never
	 * open the other - which happens any time one board runs dry while the
	 * other is filling.
	 *
	 * Makes room by withdrawing the weakest offer on the over-full board. This
	 * waives the minimum stand period, since the board is malformed rather than
	 * merely out of date, but never the hard protections in
	 * {@link ThreatMissionIntel#canBeWithdrawn} - an accepted contract or a
	 * target the player is standing on is left alone even at the cost of
	 * coverage.
	 *
	 * @param posted the offers awaiting acceptance (the board proper)
	 * @param open every open contract, offered or accepted, for target exclusion
	 * @return true if it restructured the board this tick
	 */
	protected boolean ensureTierCoverage(List<ThreatMissionIntel> posted,
			List<ThreatMissionIntel> open) {
		for (int tier = 0; tier < ThreatMissionIntel.TIER_COUNT; tier++) {
			if (!inTier(posted, tier).isEmpty()) continue;

			// the weakest withdrawable offer, never one that would empty the
			// board it sits on in the process
			ThreatMissionIntel victim = null;
			float worst = Float.MAX_VALUE;
			for (ThreatMissionIntel curr : posted) {
				if (!curr.canBeWithdrawn()) continue;
				if (inTier(posted, curr.getTier()).size() < 2) continue;
				float value = curr.currentValue();
				if (value < worst) {
					worst = value;
					victim = curr;
				}
			}
			if (victim == null) continue;

			List<ThreatMissionIntel> others = new ArrayList<ThreatMissionIntel>(open);
			others.remove(victim);
			ThreatMissionIntel.Objective best =
					ThreatMissionIntel.bestObjective(tier, others, victim.getMarketId());
			if (best == null) continue;

			ThreatIncConfig.log("Mission board: " + ThreatMissionIntel.tierName(tier)
					+ " board empty at cap - withdrawing weakest "
					+ ThreatMissionIntel.tierName(victim.getTier()) + " offer to cover it");
			victim.withdraw(best.describe());
			posted.remove(victim);
			open.remove(victim);
			ThreatMissionIntel mission = postMission(best);
			posted.add(mission);
			open.add(mission);
			return true;
		}
		return false;
	}

	/**
	 * At most one swap per tick, and always within a tier. Keeping supersession
	 * tier-local is what preserves the "one of each, always" guarantee - an
	 * immediate offer can never be displaced by a strategic one and leave that
	 * board empty. Since the two tiers score on unrelated scales, each is judged
	 * by its own ratio of challenger to incumbent, and the board with the most
	 * lopsided ratio gets the single swap. Only OFFERS are ever swapped out;
	 * accepted contracts are the player's.
	 */
	protected void trySupersede(List<ThreatMissionIntel> posted, List<ThreatMissionIntel> open) {
		float margin = ThreatIncConfig.missionSupersedeMargin();

		ThreatMissionIntel bestWeakest = null;
		ThreatMissionIntel.Objective bestChallenger = null;
		float bestRatio = margin;

		for (int tier = 0; tier < ThreatMissionIntel.TIER_COUNT; tier++) {
			ThreatMissionIntel weakest = null;
			float weakestValue = Float.MAX_VALUE;
			for (ThreatMissionIntel curr : inTier(posted, tier)) {
				if (!curr.canBeSuperseded()) continue;
				float value = curr.currentValue();
				if (value < weakestValue) {
					weakestValue = value;
					weakest = curr;
				}
			}
			if (weakest == null) continue;

			// score the challenger against a board with that slot vacated, so
			// the outgoing offer's own type no longer suppresses its kind
			List<ThreatMissionIntel> others = new ArrayList<ThreatMissionIntel>(open);
			others.remove(weakest);
			ThreatMissionIntel.Objective challenger =
					ThreatMissionIntel.bestObjective(tier, others, weakest.getMarketId());
			if (challenger == null) continue;

			// a collapsed incumbent (its link went redundant) is always beaten
			float ratio = weakestValue <= 0f ? Float.MAX_VALUE : challenger.value / weakestValue;
			if (ratio > bestRatio) {
				bestRatio = ratio;
				bestWeakest = weakest;
				bestChallenger = challenger;
			}
		}

		if (bestWeakest == null || bestChallenger == null) return;
		bestWeakest.withdraw(bestChallenger.describe());
		postMission(bestChallenger);
	}

	protected static List<ThreatMissionIntel> inTier(List<ThreatMissionIntel> missions, int tier) {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		for (ThreatMissionIntel curr : missions) {
			if (curr.getTier() == tier) result.add(curr);
		}
		return result;
	}

	/**
	 * Whether the defense boards are running an objective board at all. They
	 * mobilize when the swarm first fields a full armada (phase 3) and stay
	 * mobilized after that, even if the phase later regresses.
	 *
	 * The latch is not cosmetic. Phase 3 requires a size-6+ colony whose hull
	 * supply is near nominal, and the hive's economy fluctuates as the player
	 * and the sector tear at it. Gating the board on a live phase-3 test
	 * switched it off exactly when the swarm was most active, and the sector
	 * stopped naming targets in the middle of a colonization wave. Escalation
	 * still regresses and still matters - it gates strikes and core-world
	 * targeting - but an admiralty that has seen one armada does not forget it.
	 *
	 * Read-only on purpose: the flag is owned by checkPhaseAnnouncements, which
	 * runs later in the same tick. On the tick phase 3 is first reached the live
	 * test below carries the board, and the announcement sets the flag right
	 * after - so the escalation message is never swallowed.
	 */
	protected boolean boardsMobilized() {
		return getPhase() >= 3 || ThreatIncData.isPhase3Announced();
	}

	/**
	 * Offers a contract. Queued, not added: like every vanilla mission posting
	 * it reaches the player the next time they are in comm relay range, and
	 * until then it sits in the comm queue - which {@link ThreatMissionIntel#getOpen}
	 * counts, so the board never double-posts while an offer is in transit.
	 */
	protected ThreatMissionIntel postMission(ThreatMissionIntel.Objective objective) {
		ThreatMissionIntel mission = new ThreatMissionIntel(
				objective.tier, objective.type, objective.market.getId());
		Global.getSector().getIntelManager().queueIntel(mission);
		// an offer names the world publicly: its system now counts as known
		if (objective.market.getStarSystem() != null) {
			ThreatIncData.markDiscovered(objective.market.getStarSystem().getId());
		}
		ThreatIncConfig.log("Mission offered ["
				+ ThreatMissionIntel.tierName(objective.tier) + "]: type " + objective.type
				+ " vs " + objective.market.getName() + " (impact " + objective.impact
				+ ", difficulty " + objective.difficulty + ", value " + objective.value
				+ ", sponsor " + mission.getFactionId() + ")");
		return mission;
	}

	/**
	 * Legacy: the old round-robin type counter, from when one bounty stood at a
	 * time and the lever was picked by rotation rather than by value. Kept only
	 * so a debug reset still purges it out of existing saves.
	 */
	public static final String KEY_BOUNTY_ROTATION = "threatinc_bountyRotation";

	/**
	 * Debug fast-clock scales every duration in the incursion, not just the
	 * tick cadence - otherwise stage timers still take months of real game time.
	 */
	public static float timeScale() {
		return ThreatIncConfig.debugFastClock() ? 0.1f : 1f;
	}

	/** Matured seeds get a Seeding Swarm dispatched at them. */
	protected void advanceStages() {
		for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(
				ThreatIncData.stages().entrySet())) {
			if (!ThreatIncData.STAGE_SEEDED.equals(entry.getValue())) continue;
			String systemId = entry.getKey();
			if (ThreatIncData.daysInStage(systemId)
					< ThreatIncConfig.seedToColonyDays() * timeScale()) continue;

			StarSystemAPI system = getSystem(systemId);
			if (system == null) {
				ThreatIncData.clearSystem(systemId);
				continue;
			}

			// the OG home system stands up its full production chain at once:
			// several bootstrap swarms from the Abyss, one per chain planet
			if (ThreatIncData.isOGSystem(systemId)) {
				ThreatColonyManager.launchOGChain(system, random);
				// always announced: this is the incursion's opening move, and
				// the player must know SEVERAL swarms are coming - killing one
				// does not stop the chain
				ThreatColonyManager.announce(ThreatNotice.titled("Swarms Inbound").bad()
						.line("Dense Threat swarms are in transit toward the %s.",
								system.getNameWithLowercaseType())
						.line("Multiple fabricator fleets: a coordinated colonization effort."));
				continue;
			}

			PlanetAPI planet = pickWaveTarget(system);
			if (planet == null) {
				ThreatIncData.clearSystem(systemId);
				ThreatIncConfig.log("No colonizable planet in " + system.getName() + "; claim abandoned.");
				continue;
			}

			// one colonization attempt at a time (initial Abyss seeds exempt):
			// a matured claim waits its turn while another wave is in flight
			if (ThreatColonyManager.anyWaveInFlight()
					&& !ThreatIncData.bootstrapSeeds().contains(systemId)) continue;

			MarketAPI source = pickWaveSource(system);
			if (source == null) {
				// no colony can source a wave. Only the initial seeds may fall
				// back to the Abyss itself - the one-time arrival event; every
				// other claim stays dormant until the hive can afford it
				if (!ThreatIncData.bootstrapSeeds().contains(systemId)) continue;
			}
			boolean launched = ThreatColonyManager.launchColonizationWave(
					source, system, planet, random);
			if (launched && getPhase() >= 2) {
				ThreatColonyManager.announce(ThreatNotice.titled("Swarm Inbound").bad()
						.line("A dense Threat swarm is in transit toward the %s.",
								system.getNameWithLowercaseType()));
			}
		}
	}

	/**
	 * In an uninhabited system any planet will do; in an inhabited one (deciv
	 * conversion) only worlds the swarm killed are claimable.
	 */
	protected PlanetAPI pickWaveTarget(StarSystemAPI system) {
		boolean inhabited = false;
		for (MarketAPI market : Misc.getMarketsInLocation(system)) {
			if (!Factions.THREAT.equals(market.getFactionId())) {
				inhabited = true;
				break;
			}
		}
		if (!inhabited) return ThreatColonyManager.pickColonyPlanet(system);

		for (PlanetAPI planet : system.getPlanets()) {
			if (planet.isStar() || planet.getMarket() == null) continue;
			if (!ThreatMapFog.conditionOnly(planet.getMarket())) continue;
			if (planet.getMarket().hasCondition(
					com.fs.starfarer.api.impl.campaign.ids.Conditions.DECIVILIZED)) {
				return planet;
			}
		}
		return null;
	}

	/** Nearest healthy colony big enough to source a wave; null if none can. */
	protected MarketAPI pickWaveSource(StarSystemAPI target) {
		return ThreatColonyManager.pickForgeSource(target, true);
	}

	protected void trySpread() {
		// no colonies means no spread; existing seeds still mature into waves
		if (ThreatIncData.totalColonySize() <= 0) return;

		// consolidate before reaching outward: unclaimed resource planets in
		// systems the swarm already holds come first, each wave paid for with
		// a Defense Swarm mustered from the source colony's garrison
		ThreatColonyManager.tryExpandInSystem(random);

		// outward claims are commitments against real standing forces: one
		// pending claim per stable colony with a swarm to spare. No dice -
		// launch throughput IS the garrison surplus the hive's nexuses grow
		int freeForges = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.spreadMinSize()) continue;
			if (!ThreatColonyManager.hasReadyForge(market)) continue;
			if (!ThreatColonyManager.isStableForExpansion(market)) continue;
			freeForges++;
			ThreatIncConfig.log("Spread-capable forge: " + market.getName() + " (size "
					+ market.getSize()
					+ ", ships " + ThreatColonyManager.shipsAvailable(market)
					+ ", shipMult " + ThreatColonyManager.shipSupplyMult(market) + ")");
		}
		if (freeForges <= 0) return;

		// one outward claim at a time, no matter how many forges the hive has -
		// expansion is serial and deliberate, not a parallel flood
		for (Map.Entry<String, String> entry : ThreatIncData.stages().entrySet()) {
			if (ThreatIncData.STAGE_SEEDED.equals(entry.getValue())
					&& !ThreatIncData.bootstrapSeeds().contains(entry.getKey())) {
				return;
			}
		}

		if (ThreatIncData.countInfested() >= ThreatIncConfig.maxInfestedSystems()) return;

		StarSystemAPI target = pickSpreadTarget();
		if (target == null) return;

		// the Remnant network resists the swarm
		if (ThreatIncConfig.remnantResists()) {
			CampaignFleetAPI nexus = RemnantHostileActivityFactor.getRemnantNexus(target);
			if (nexus != null) {
				if (random.nextFloat() < ThreatIncConfig.machineWarWinChance()) {
					nexus.despawn();
					ThreatColonyManager.announce(ThreatNotice.titled("Machine War").bad()
							.line("The %s Nexus in the %s has gone silent.",
									ThreatNotice.faction(Global.getSector().getFaction(Factions.REMNANTS)),
									target.getNameWithLowercaseType())
							.line("Salvors report wreckage of both Remnant and unknown manufacture: "
									+ "a war between machines, and the Remnant %s.",
									ThreatNotice.red("lost")));
					ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
					ThreatIncConfig.log("Machine war won at " + target.getName() + "; nexus destroyed, system seeded.");
				} else {
					ThreatColonyManager.announce(ThreatNotice.titled("Machine War")
							.line("Fierce fighting between %s forces and unidentified constructs "
									+ "in the %s.",
									ThreatNotice.faction(Global.getSector().getFaction(Factions.REMNANTS)),
									target.getNameWithLowercaseType())
							.line("The Remnant Nexus %s - for now.", ThreatNotice.green("holds")));
					ThreatIncConfig.log("Machine war lost at " + target.getName() + "; nexus holds.");
				}
				return;
			}
		}

		ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
		ThreatIncConfig.log("Spread to: " + target.getName());
	}

	/**
	 * The swarm moves into the graveyards it creates: worlds decivilized by
	 * Threat bombardment get colonization waves of their own, even inside
	 * inhabited systems. One launch per tick.
	 */
	protected void tryConversions() {
		if (!ThreatIncConfig.convertDecivWorlds()) return;
		if (ThreatIncData.decivTargets().isEmpty()) return;
		if (ThreatColonyManager.anyWaveInFlight()) return; // one attempt at a time
		// graveyard grabs are opportunism, not necessity - a strained hive
		// doesn't add mouths it can't feed
		if (!ThreatColonyManager.anyNominalColony()) return;
		if (ThreatIncData.countInfested() >= ThreatIncConfig.maxInfestedSystems()) return;

		for (String planetId : new ArrayList<String>(ThreatIncData.decivTargets())) {
			SectorEntityToken entity = Global.getSector().getEntityById(planetId);
			if (!(entity instanceof PlanetAPI)) {
				ThreatIncData.decivTargets().remove(planetId);
				continue;
			}
			PlanetAPI planet = (PlanetAPI) entity;
			StarSystemAPI system = planet.getStarSystem();
			MarketAPI market = planet.getMarket();
			if (system == null || market == null
					|| !ThreatMapFog.conditionOnly(market)
					|| !market.hasCondition(
							com.fs.starfarer.api.impl.campaign.ids.Conditions.DECIVILIZED)
					|| Misc.isStoryCritical(market)) {
				ThreatIncData.decivTargets().remove(planetId);
				continue;
			}
			// systems awaiting their first colony keep their claim; systems
			// the swarm already holds can still absorb their local ruins
			String sysStage = ThreatIncData.stages().get(system.getId());
			if (ThreatIncData.STAGE_SEEDED.equals(sysStage)
					|| ThreatIncData.STAGE_COLONIZING.equals(sysStage)) continue;
			if (ThreatIncData.waveFleets().containsKey(planetId)) continue;

			// fuel range is the only reach limit - enforced in pickForgeSource
			MarketAPI source = ThreatColonyManager.pickForgeSource(system, true);
			if (source == null) continue; // out of reach or unaffordable; keep the target

			if (ThreatColonyManager.launchColonizationWave(source, system, planet, random)) {
				ThreatIncData.decivTargets().remove(planetId);
				ThreatColonyManager.announce(ThreatNotice.titled("Swarm Inbound").bad()
						.line("Something is moving through the ruins of %s.", planet.getName())
						.line("A dense Threat swarm is inbound to claim the world it killed."));
				return;
			}
		}
	}

	protected void tryStrikes() {
		if (getPhase() < 2) return;
		if (countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) return;

		// shuffled so the oldest hive can't monopolize the concurrency cap -
		// iteration used to run in seeding order, which let the founding system
		// fill every strike slot before younger systems were even considered
		List<String> systemIds = new ArrayList<String>(ThreatIncData.colonyMarkets().keySet());
		java.util.Collections.shuffle(systemIds, random);
		for (String systemId : systemIds) {
			// a strike is staged by the system's biggest colony with the means:
			// hulls delivered by the hive network, fuel economy intact, Swarm
			// Nexus ready, and - the real cost - a FULL garrison with swarms to
			// spare above the defensive reserve. The expedition is those swarms,
			// mustered and sent; the nexus regrows them at its usual cadence, so
			// strike tempo is paced by swarm production, not a timer. Strikes
			// and expansion draw on the same garrison pool: an aggressive hive
			// spreads slower, and vice versa.
			MarketAPI colony = ThreatColonyManager.pickStrikeStaging(systemId, true);
			if (colony == null) continue;

			StarSystemAPI source = getSystem(systemId);
			if (source == null) continue;

			MarketAPI target = pickStrikeTarget(colony, source);
			if (target == null) continue;

			launchStrike(colony, source, target);
			// under the fog a strike hides its origin (ThreatScouts); without
			// it the raid intel names its origin and the system is known
			if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(systemId);
			if (countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) break;
		}
	}

	protected ThreatStrikeFGI launchStrike(MarketAPI colony, StarSystemAPI source, MarketAPI target) {
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);

		params.factionId = Factions.THREAT;
		params.makeFleetsHostile = false; // threat fleets are hostile by construction

		// the colony itself is the staging market - no fake-market hack needed
		params.source = colony;

		params.prepDays = 7f + 7f * random.nextFloat();
		// the siege holds orbit for siegeOrbitDays (2026-09-06): suppressing a
		// colony's fortifications from orbit is a campaign, not a pass
		params.payloadDays = ThreatIncConfig.siegeOrbitDays() * (0.9f + 0.2f * random.nextFloat());
		// vanilla re-times the payload stage once the fleets spawn
		// (FGRaidAction.computeSubstages): every world in the sweep gets this long
		params.raidParams.maxDurationIfSpawnedFleetsConcurrent = ThreatIncConfig.siegeOrbitDays();
		params.raidParams.maxDurationIfSpawnedFleetsPerSequentialStage = ThreatIncConfig.siegeOrbitDays();

		params.raidParams.where = target.getStarSystem();
		params.raidParams.type = FGRaidType.SEQUENTIAL;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.add(target);
		// sweep doctrine: the expedition works through EVERY eligible world in
		// the target system, not just the picked one - a strike contests a
		// system, not a planet. Secondary targets pass the same filters that
		// gated the primary pick (see pickStrikeTarget), minus the size floor:
		// once the swarm commits to a system, its outposts burn too.
		boolean coreAllowed = getPhase() >= 3;
		boolean playerAllowed = ThreatIncData.daysSincePlayerStruck()
				>= ThreatIncConfig.playerGraceDays();
		for (MarketAPI other : Global.getSector().getEconomy().getMarkets(target.getStarSystem())) {
			if (other == target || other.getPrimaryEntity() == null || ThreatMapFog.hidden(other)) continue;
			if (Factions.THREAT.equals(other.getFactionId())) continue;
			if (other.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
			if (!coreAllowed && other.getSize() >= 6) continue;
			if (other.isPlayerOwned() && !playerAllowed) continue;
			if (isActiveStrikeTarget(other)) continue;
			if (!ThreatIncConfig.destroyStoryCritical() && Misc.isStoryCritical(other)) continue;
			params.raidParams.allowedTargets.add(other);
		}
		params.raidParams.allowNonHostileTargets = true;
		// PAYLOAD AUTHORITY (docs/ground-war.md "Threat ground assaults"): the
		// swarm besieges, it does not annihilate. Leaving bombardment unset is
		// what routes every pass through ThreatStrikeFGI.doCustomRaidAction -
		// soften the defenses, land a Threat ground front, then reinforce it -
		// so a world is only ever lost the way a hive is: on the ground.
		//
		// The bombardment doctrine is one knob away: strikeSaturationEnabled
		// restores the bombardment payload, flown by the rules every besieger
		// flies (ThreatStrikeFGI.AnnihilationAction): frontier staging harasses
		// with a tactical slice, developed staging razes each world from orbit,
		// its one pass spent only once the world is gone.
		if (ThreatIncConfig.strikeSaturationEnabled()) {
			if (colony.getSize() <= ThreatIncConfig.strikeMinSize() + 1) {
				params.raidParams.setBombardment(BombardType.TACTICAL);
			} else {
				params.raidParams.setBombardment(BombardType.SATURATION);
			}
			params.raidParams.raidsPerColony = strikeSatPasses(colony.getSize());
		} else {
			params.raidParams.bombardment = null;
			params.raidParams.raidApproachText = "moving to assault";
			params.raidParams.raidActionText = "assaulting";
			params.raidParams.raidsPerColony =
					Math.max(1, ThreatIncConfig.strikePassesPerColony());
		}
		params.noun = "Threat strike";
		params.forcesNoun = "Threat forces";

		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;

		// the expedition IS the colony's mustered Defense Swarms: everything
		// comes from somewhere. The colony sends what stands above its
		// defensive reserve, and each expedition fleet is sized to the actual
		// swarm that left orbit. The nexus keeps growing replacements at its
		// usual cadence, so strike tempo is bought with real fleets - and
		// killing a colony's swarms directly starves its next strike.
		int sendable = ThreatColonyManager.garrisonAvailableForLaunch(colony);
		java.util.List<Integer> mustered = ThreatColonyManager.consumeGarrison(colony, sendable);
		if (mustered.isEmpty()) return null;
		// each expedition fleet is EXACTLY the swarm that left orbit (its
		// fabrication tier rides the fleet's memory); the strength multiplier
		// up- or down-tiers the re-embodiment for players who want it
		for (int size : mustered) {
			params.fleetSizes.add(strikeFleetSize(size));
		}

		ThreatStrikeFGI strike = new ThreatStrikeFGI(params);
		Global.getSector().getIntelManager().addIntel(strike);
		getStrikeList().add(strike);

		if (target.isPlayerOwned()) {
			ThreatIncData.setPlayerStruck();
		}
		// the struck faction's scouts and task force answer when the strike is
		// SEEN (detectStrikes), not when it is launched - without strike
		// detection that is the same moment
		if (strike.isDetected()) onStrikeDetected(strike);
		else ThreatOmens.onStrikeLaunched(target);

		ThreatIncConfig.log("Strike launched from " + source.getName() + " at " + target.getName()
				+ " (" + mustered.size() + " swarm(s) mustered, sweeping "
				+ params.raidParams.allowedTargets.size()
				+ " world(s) in " + target.getStarSystem().getName() + ")");
		return strike;
	}


	/**
	 * Strike warning (docs/frontlines.md): a strike nobody has seen is hidden.
	 * Each poll, a strike whose fleets are in sight of the player, share a
	 * system with a colony or outpost, or fly within strikeDetectLY of a
	 * military world or frontline outpost in hyperspace, is spotted - and the
	 * struck faction answers from then on.
	 */
	protected void detectStrikes() {
		for (Object curr : new ArrayList<Object>(getStrikeList())) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isDetected() || strike.isEnded() || strike.isEnding()) continue;
			String by = null;
			if (strike.isSpawnedFleets()) {
				for (CampaignFleetAPI fleet : strike.getFleets()) {
					by = ThreatFrontlines.detectedBy(fleet);
					if (by != null) break;
				}
			} else if (strike.getRoute() != null) {
				// far from the player the group flies as an abstract route
				// (vanilla spawns fleets only near the player): read its place
				com.fs.starfarer.api.impl.campaign.fleets.RouteManager.RouteData route = strike.getRoute();
				com.fs.starfarer.api.campaign.LocationAPI loc = route.getCurrent() != null
						? route.getCurrent().getCurrentContainingLocation() : null;
				by = ThreatFrontlines.detectedAt(loc, route.getInterpolatedHyperLocation());
			}
			// the payload has begun: it is at the target, seen or not
			if (by == null && strike.getCurrentAction() != null
					&& strike.getCurrentAction() == strike.getRaidAction()) {
				by = "its target";
			}
			if (by == null) continue;
			strike.markDetected(by);
			onStrikeDetected(strike);
		}
	}

	/** The struck NPC faction's scouts get a lead and its task force sails; outposts call relief. */
	protected void onStrikeDetected(ThreatStrikeFGI strike) {
		if (strike.getParams() == null || strike.getParams().raidParams == null) return;
		MarketAPI colony = strike.getParams().source;
		StarSystemAPI source = colony != null ? colony.getStarSystem() : null;
		java.util.List<MarketAPI> targets = strike.getParams().raidParams.allowedTargets;
		MarketAPI target = targets.isEmpty() ? null : targets.get(0);
		// seen late, the world may already be gone
		if (target != null && !target.isInEconomy()) target = null;
		// the struck faction mobilises (docs/strategy-layer.md): from here on
		// its colonies stock reserves, its logistics run and its fleets take
		// orders. NPC and player alike - once the strike is seen
		if (target != null) ThreatWarState.recordStrike(target);
		// no task force against a hive that died while the strike flew unseen
		boolean hiveAlive = colony != null && colony.isInEconomy()
				&& Factions.THREAT.equals(colony.getFactionId());
		if (target != null && source != null && hiveAlive && !target.isPlayerOwned()) {
			// an NPC colony was struck: its scouts go looking for where the
			// strike came from. A task force goes against the attacking colony's
			// garrison only if its hive is already found - none follows later
			ThreatScouts.addLead(target.getFactionId(), source.getId());
			dispatchFactionResponse(target, source, colony);
		}
		ThreatFrontlines.sendRelief(strike, random);
	}

	/**
	 * Reactive defense: the struck colony's faction musters a task force from its
	 * nearest military world and sends it against the Threat colony that launched
	 * the strike - it fights the Defense Swarms orbiting the colony planet.
	 * Breaking the garrison opens the colony to bombardment (theirs or yours).
	 */
	protected void dispatchFactionResponse(MarketAPI struckColony, StarSystemAPI hiveSystem, MarketAPI threatColony) {
		if (!ThreatIncConfig.responseEnabled()) return;
		if (threatColony == null || threatColony.getPrimaryEntity() == null) return;
		if (countActiveResponses() >= ThreatIncConfig.responseMaxConcurrent()) return;

		FactionAPI faction = struckColony.getFaction();
		if (faction == null || faction.isPlayerFaction()) return;
		// an excluded faction (pirates) runs no military operations at all
		if (ThreatWarState.excluded(faction.getId())) return;
		// no counter-attack on a hive no one has found: the scouts go first
		if (!ThreatScouts.sectorKnows(hiveSystem.getId())) return;

		MarketAPI base = findResponseBase(faction, hiveSystem);
		if (base == null) return; // no military world in reach: the faction can't respond

		// measured at the BASE's system: the hive system is enemy territory where
		// the faction has no assets, so strength there is ~0 and every task force
		// would collapse to the minimum difficulty
		float strength = WarSimScript.getFactionStrength(faction, base.getStarSystem());
		int minDiff = ThreatIncConfig.responseMinDifficulty();
		int maxDiff = ThreatIncConfig.responseMaxDifficulty();
		// the faction's whole strength converts into a FLOTILLA, not one fleet:
		// difficulty points beyond the single-fleet cap spill into extra fleets
		// (up to 4) - a real navy answers a hive system with a battle group,
		// a backwater militia still sends its one gunboat squadron
		int budget = Math.max(minDiff,
				minDiff + Math.round(strength / ThreatIncConfig.responseStrengthDivisor()));

		StarSystemAPI baseSystem = base.getStarSystem();
		SectorEntityToken baseEntity = base.getPrimaryEntity();
		if (baseSystem == null || baseEntity == null) return;

		java.util.List<CampaignFleetAPI> fleets = new ArrayList<CampaignFleetAPI>();
		int spent = 0;
		while (budget > 0 && fleets.size() < 4) {
			int difficulty = Math.min(budget, maxDiff);
			// vanilla's 0-10 "standard fleet" scale tops out around a bounty
			// fleet - a rounding error against a hive garrison. Build with
			// direct fleet points instead: a max-difficulty fleet is a genuine
			// ~250 FP battle group, quality and doctrine drawn from the base
			float fp = difficulty * FP_PER_RESPONSE_DIFFICULTY;
			FleetParamsV3 fleetParams = new FleetParamsV3(
					base,
					base.getLocationInHyperspace(),
					faction.getId(),
					null,
					FleetTypes.TASK_FORCE,
					fp,           // combat
					fp * 0.1f,    // freighters
					fp * 0.1f,    // tankers
					0f, 0f, 0f,   // transports/liners/utility
					0f);
			CampaignFleetAPI fleet = FleetFactoryV3.createFleet(fleetParams);
			if (fleet == null || fleet.isEmpty()) break;

			baseSystem.addEntity(fleet);
			fleet.setLocation(baseEntity.getLocation().x, baseEntity.getLocation().y);
			// the fleet name gets the faction prefix from the game, so the name
			// itself must not repeat it - "Tri-Tachyon Tri-Tachyon Task Force"
			fleet.setName("Task Force");
			fleet.setNoFactionInName(false);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, true);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_WAR_FLEET, true);
			// under orders: no response script may borrow it (ThreatFleetOrders.buildTaskForce)
			fleet.getMemoryWithoutUpdate().set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
			fleet.getMemoryWithoutUpdate().set("$threatinc_response", true);

			fleet.addAssignment(FleetAssignment.ATTACK_LOCATION, threatColony.getPrimaryEntity(), 120f,
					"attacking the Threat colony in the " + hiveSystem.getNameWithLowercaseType());
			fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, baseEntity, 1000f,
					"returning to " + base.getName());

			fleets.add(fleet);
			spent += difficulty;
			budget -= difficulty;
			// leftover too small to be worth a straggler fleet
			if (budget < minDiff) break;
		}
		if (fleets.isEmpty()) return;

		// a mobilised faction pays for the sortie out of its base's reserves:
		// fuel for the distance, supplies for the fleets (a cost, not a gate -
		// the reactive defense always sails; running the depot dry is what
		// makes the NEXT expedition wait for convoys)
		if (ThreatWarState.isAtWar(faction)) {
			float dist = Misc.getDistanceLY(baseSystem.getLocation(), hiveSystem.getLocation());
			float fuel = ThreatReserves.drawAbove(base,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL,
					spent * dist * ThreatIncConfig.expeditionFuelPerPointLY());
			float supplies = ThreatReserves.drawAbove(base,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES,
					spent * ThreatIncConfig.expeditionSuppliesPerPoint());
			ThreatIncConfig.log("Expedition draw (task force) at " + base.getName() + ": "
					+ (int) fuel + " fuel, " + (int) supplies + " supplies");
			// each fleet remembers its share, for the refund when it is recalled
			for (CampaignFleetAPI fleet : fleets) {
				ThreatReturns.provision(fleet, base.getId(), fuel / fleets.size(),
						supplies / fleets.size());
			}
		}

		ThreatResponseIntel intel = new ThreatResponseIntel(fleets, faction, base.getName(),
				threatColony, hiveSystem.getNameWithLowercaseTypeShort());
		Global.getSector().getIntelManager().addIntel(intel);
		getResponseList().add(intel);

		ThreatIncConfig.log(faction.getId() + " dispatched a task force from " + base.getName()
				+ " (" + fleets.size() + " fleet(s), total difficulty " + spent
				+ ") against the Threat in " + hiveSystem.getName());
	}

	/**
	 * Siege expeditions against hive colonies (see ThreatPurgeFGI for the
	 * doctrine - tactical bombardment and commando raids feeding the decline
	 * engine; bombardment never reduces hive population). Three triggers: a
	 * colony whose garrison has been wiped out draws a siege at any size (the
	 * window of opportunity); a colony still small (purgePreemptMaxSize) draws
	 * a preemptive siege garrison or not - navies do not politely wait for a
	 * foothold to grow into a fortress; and a defended larger colony draws the
	 * rare full assault, with extra escorts, on a stretched cooldown.
	 */
	/**
	 * Hive system id -> when a hunting force last thinned it (ThreatSoftening:
	 * a colony's swarms gone, or a force done). Not saved. A hunt took Alpha
	 * Novy Tayvay I's swarms to 355 FP and opened the orbit gate, but sieges
	 * are weighed on the monthly tick and the hive had regrown to 3,233 FP
	 * before the next one; now a thinned system is looked at on the next poll,
	 * on the follow-up cooldown of a wounded colony.
	 */
	protected static final java.util.Map<String, Long> THINNED = new java.util.HashMap<String, Long>();
	protected static boolean siegePassPending;

	/** A hunting force has thinned the system's swarms: weigh its sieges on the next poll. */
	public static void huntThinned(String systemId) {
		if (systemId == null) return;
		THINNED.put(systemId, Global.getSector().getClock().getTimestamp());
		siegePassPending = true;
	}

	/** Whether a hunt thinned the system within purgeFollowUpDays. */
	protected static boolean recentlyThinned(String systemId) {
		Long when = THINNED.get(systemId);
		if (when == null) return false;
		float days = Global.getSector().getClock().getElapsedDaysSince(when);
		// negative: stamped in a timeline an earlier save was loaded over
		return days >= 0f && days < ThreatIncConfig.purgeFollowUpDays() * timeScale();
	}

	/** Forgets the not-saved siege state (thinned systems, strength reads): a load must not inherit the abandoned timeline's. */
	public static void forgetThinned() {
		THINNED.clear();
		siegePassPending = false;
		SIEGE_STRENGTH.clear();
		// a reload at the same clock instant would otherwise hand out the old session's markets
		BASES_MEMO.clear();
		DONORS_MEMO.clear();
		basesMemoStamp = Long.MIN_VALUE;
		TARGETS_MEMO.clear();
		targetsMemoStamp = Long.MIN_VALUE;
		NEED_MEMO.clear();
		needMemoStamp = Long.MIN_VALUE;
		RAZE_MEMO.clear();
		razeMemoStamp = Long.MIN_VALUE;
		WarFootingDemand.forgetPeacetimeDemand();
	}

	protected void tryPurgeBombardments() {
		if (!ThreatIncConfig.responsePurgeEnabled()) return;

		// a siege takes the whole system, so each system is weighed once a pass:
		// its sibling colonies re-ran the same sizing after a postponement
		java.util.Set<String> weighed = new java.util.HashSet<String>();
		// the low-hanging fruit first (user's rule 2026-09-27): the easiest hives
		// claim the marines and the concurrency slots before the hard ones
		for (MarketAPI colony : easiestFirst(ThreatIncData.getAllLiveColonyMarkets())) {
			if (countActivePurges() >= ThreatIncConfig.responseMaxConcurrent()) return;
			// no siege of a hive no one has found (ThreatScouts)
			if (!ThreatScouts.sectorKnows(colony)) continue;

			StarSystemAPI system = colony.getStarSystem();
			if (system == null) continue;
			if (onPurgeCooldown(colony)) continue;
			if (!weighed.add(system.getId())) continue;

			// nearest military world of a MOBILISED NPC faction within response
			// range. Only a faction in war mode keeps a reserve ledger, and the
			// expedition's troops, armaments, fuel and supplies are drawn from
			// that base's reserve (launchSiegeExpedition) - a faction the swarm
			// has never struck launches nothing, because it has nothing banked
			// to launch with (decided 2026-09-05: no expedition from nowhere)
			// The nearest base is the system's (it stages, and it alone is barred
			// from hunting there); while it cannot pay, the next nearest in reach
			// may sail instead - every Persean siege of Run 6 came from the same
			// two bases while the rest of the League's depots sat full
			java.util.List<MarketAPI> bases = siegeBasesFor(system);
			if (bases.isEmpty()) continue;
			java.util.List<MarketAPI> targets = null;
			MarketAPI base = null;
			MarketAPI first = null;
			FactionAPI faction = null;
			int difficulty = 0;
			ThreatPurgeFGI purge = null;
			// siegeBaseTries counts the bases weighed: one already besieging the
			// system, or with nothing to take, does not use up a try
			int tries = 0;
			for (int i = 0; i < bases.size() && tries < Math.max(1, ThreatIncConfig.siegeBaseTries()) && purge == null; i++) {
				base = bases.get(i);
				faction = base.getFaction();
				// one siege of a system per faction at a time: a partial siege
				// leaves the harder worlds unstamped
				if (siegeFactionsIn(system.getId()).contains(faction.getId())) continue;
				// relief before offensives (user, 2026-09-27): no new siege while
				// one of the faction's own invaded worlds is owed relief it can send
				if (ThreatFleetOrders.reliefOwed(faction)) continue;
				// ONE sizing (siegeTargets + siegeSizesFor) for the launch, the hunting gate
				// (hasSiegeableHive) and the convoy planner (ThreatConvoys.stagingTargets): a base
				// judged able to take the orbit sails the flotilla that was judged,
				// or the judgement locks it out of both sieging and hunting
				targets = siegeTargets(base, faction, system);
				// the trigger colony is off its cooldown, but need not be a target:
				// siegeTargets falls back to worlds on cooldown so the convoys stage
				// toward one, and nothing sails at them
				if (targets.isEmpty() || !anySiegeReady(targets)) continue;
				tries++;
				if (first == null) first = base;
				difficulty = siegeDifficulty(base, faction, targets, anyTargetGarrisoned(targets));
				java.util.List<Integer> fleetSizes = siegeSizesFor(base, faction, targets, 0f);
				if (tries == 1 && ThreatIncConfig.debugLogging()) {
					java.util.Set<String> raze = razeWorlds(base, faction, system, targets);
					ThreatIncConfig.logQuiet("sizing:" + system.getId(), "Siege sizing vs " + system.getName() + ": " + fleetSizes.size()
							+ " fleets, ground str ~" + (int) siegeRaidStrEstimate(fleetSizes)
							+ " against " + (int) siegeRaidStrNeeded(landTargets(targets, raze)) + " needed, ~"
							+ (int) ThreatAidCapacity.expeditionPoints(fleetSizes) + " FP against "
							+ (int) siegeOrbitFaced(targets) + " FP of Defense Swarms faced"
							+ (raze.isEmpty() ? "" : ", razing " + raze.size() + " of " + targets.size()));
				}
				// a postponed (short of marines) or refused (over free FP) launch
				// returns null WITHOUT stamping the siblings' cooldown - so if we
				// announced regardless, every colony in the system would each fire
				// its own "launched a purge expedition" beat for an expedition that
				// never sailed (2 for a 2-colony system, 4 for a 4-colony one)
				purge = launchSiegeExpedition(base, faction, system,
						targets, fleetSizes, false, random);
				if (purge != null && base != first) {
					ThreatIncConfig.log("Siege of " + system.getName() + " sails from " + base.getName()
							+ ": " + first.getName() + ", the nearest base weighed, cannot pay for it");
				}
			}
			if (purge == null) continue;
			// a purge launches at a colony whose garrison is gone; PREEMPTIVELY
			// at a foothold still small enough to stomp; or - rarest and
			// heaviest - as a full assault on a defended entrenched hive, with
			// extra escorts and on a stretched cooldown. Waiting for a hive to
			// disarm itself is how it gets to size 8. The sizing's own test
			// (siegeHeavyAssault) names the assault: one world both garrisoned and
			// too big to stomp; a garrisoned foothold beside a big empty hive is neither
			boolean defended = anyTargetGarrisoned(targets);
			int biggest = 0;
			for (MarketAPI t : targets) biggest = Math.max(biggest, t.getSize());
			boolean heavyAssault = siegeHeavyAssault(faction, targets);
			boolean preemptive = defended && !heavyAssault && biggest <= ThreatIncConfig.purgePreemptMaxSize();
			int inSystem = ThreatIncData.getLiveColonyMarkets(system.getId()).size();
			// the worlds it razes from orbit rather than lands on (razeWorlds)
			java.util.List<MarketAPI> razed = new ArrayList<MarketAPI>();
			for (MarketAPI t : targets) {
				if (purge.razes(t)) razed.add(t);
			}

			// the launch is the player-facing beat of the whole siege system:
			// always shown, one sentence
			ThreatNotice n;
			if (!razed.isEmpty() && razed.size() == targets.size()) {
				// nothing to land on: the fleets carry fuel, not troops
				n = ThreatNotice.titled("Razing Expedition").icon(faction)
						.line("%s has launched a bombing expedition into the %s.",
								ThreatNotice.faction(faction), system.getNameWithLowercaseType());
			} else if (preemptive) {
				n = ThreatNotice.titled("Preemptive Purge").icon(faction)
						.line("%s has launched a preemptive purge expedition into the %s.",
								ThreatNotice.faction(faction), system.getNameWithLowercaseType())
						.line("Against the swarm's %s before they entrench.",
								targets.size() > 1 ? targets.size() + " colonies" : "young foothold");
			} else if (heavyAssault) {
				n = ThreatNotice.titled("Full Siege").icon(faction)
						.line("%s has committed to a full siege of the %s.",
								ThreatNotice.faction(faction), system.getNameWithLowercaseType())
						.line("Fighting through the Defense Swarms to bombard and land on its "
								+ "entrenched colonies.");
			} else {
				n = ThreatNotice.titled("Siege Expedition").icon(faction)
						.line("%s has launched a siege expedition into the %s.",
								ThreatNotice.faction(faction), system.getNameWithLowercaseType());
				if (targets.size() < inSystem) {
					n.line("Against %s of the %s Threat colonies there.", targets.size(), inSystem);
				} else if (targets.size() > 1) {
					n.line("Against all %s Threat colonies there.", targets.size());
				} else {
					n.line("Against the undefended Threat colony there.");
				}
			}
			if (!razed.isEmpty()) n.line("To raze %s from orbit.", worldNames(razed));
			n.send();
			ThreatIncConfig.log(faction.getId()
					+ (preemptive ? " preemptive" : heavyAssault ? " heavy-assault" : "")
					+ " purge expedition vs " + system.getName() + " (" + targets.size() + " of " + inSystem
					+ " colonies, difficulty " + difficulty
					+ (razed.isEmpty() ? "" : ", razing " + razed.size()) + ")");
		}
	}

	/** Whether any of the worlds is off its siege cooldown: the launch's gate, and the hunting gate's (hasSiegeableHive). */
	public static boolean anySiegeReady(java.util.List<MarketAPI> targets) {
		for (MarketAPI t : targets) {
			if (!onPurgeCooldown(t)) return true;
		}
		return false;
	}

	/** Whether the colony's last siege is too recent for the next one to launch. */
	public static boolean onPurgeCooldown(MarketAPI colony) {
		StarSystemAPI system = colony.getStarSystem();
		boolean defended = ThreatColonyManager.countLiveGarrison(colony.getId()) > 0;
		boolean heavyAssault = defended && colony.getSize() > ThreatIncConfig.purgePreemptMaxSize();
		float cooldown = ThreatIncConfig.purgeCooldownDays() * timeScale();
		if (heavyAssault) cooldown *= ThreatIncConfig.purgeDefendedCooldownMult();
		// FOLLOW-UP PRESSURE: a wounded colony - a ground front on its
		// surface, or key organs still disrupted - draws the next
		// expedition on a short cooldown. Navies press an advantage;
		// without this, the recovery between full-interval visits erases
		// everything a lone expedition achieved.
		boolean wounded = ThreatGroundFronts.hasFront(colony)
				|| ThreatColonyManager.anyOrganDisrupted(colony)
				|| (system != null && recentlyThinned(system.getId()));
		if (wounded) {
			cooldown = Math.min(cooldown,
					ThreatIncConfig.purgeFollowUpDays() * timeScale());
		}
		Long last = ThreatIncData.lastPurgeTimes().get(colony.getId());
		if (last == null) return false;
		float since = Global.getSector().getClock().getElapsedDaysSince(last);
		return since >= 0f && since < cooldown;
	}

	/** The base an NPC siege of this hive system sails from: the nearest military world of a mobilised NPC faction in reach, or null. */
	public static MarketAPI siegeBaseFor(StarSystemAPI system) {
		java.util.List<MarketAPI> bases = siegeBasesFor(system);
		return bases.isEmpty() ? null : bases.get(0);
	}

	/**
	 * The markets a short NPC siege draws on (2026-09-26): every other market of
	 * the siege base's faction in reach of the hive system (expeditionRangeLY,
	 * the rule siegeBasesFor applies) that holds a reserve - bases and plain
	 * colonies alike - nearest the siege base first. Each gives everything
	 * above its floor (ThreatReserves.available: reserveFloorFraction of the
	 * largest cap seen; a market under a ground front gives nothing), with no
	 * donor keep share and no staging hold. Until then only sibling BASES gave,
	 * and only their spendable stock: in Run 5 (2.4 years, no player) the
	 * faction's marines covered the landing in 374 of 375 marine postponements
	 * but the base and its pool held a median tenth of them; supplies bound 220
	 * of 244 provision postponements at a median 299 in the depot against a
	 * 12,600 bill the faction held 2.5x of. Four sieges sailed.
	 *
	 * <p>Sibling forward bases give too (2026-09-29, overnight): run 6's
	 * Hegemony held 19-53k fuel at Alpha Spair I and Calu, both waiting on
	 * supplies, while Temblor next door postponed its siege for fuel 73 times
	 * across the whole run. A base keeps back its garrison's supply upkeep for
	 * siegeOutpostKeepMonths ({@link #donorAvailable}); the rest pays whichever
	 * of the faction's sieges is ready first.
	 */
	public static java.util.List<MarketAPI> siegeDonors(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		java.util.List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (base == null || faction == null || system == null) return result;
		for (MarketAPI m : factionMarketsInReach(faction, system)) {
			// a forward base keeps its garrison's upkeep back (donorAvailable)
			if (m != base) result.add(m);
		}
		final org.lwjgl.util.vector.Vector2f at = base.getStarSystem() != null
				? base.getStarSystem().getLocation() : system.getLocation();
		java.util.Collections.sort(result, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), at),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), at));
			}
		});
		return result;
	}

	/** Faction id + system id -> {@link #factionMarketsInReach}, for the clock instant in basesMemoStamp; not saved. */
	private static final java.util.Map<String, java.util.List<MarketAPI>> DONORS_MEMO =
			new java.util.HashMap<String, java.util.List<MarketAPI>>();

	/** Every market of the faction with a reserve, in reach of the hive system (expeditionRangeLY); memoised per clock instant like siegeBasesFor. */
	protected static java.util.List<MarketAPI> factionMarketsInReach(FactionAPI faction, StarSystemAPI system) {
		long now = Global.getSector().getClock().getTimestamp();
		if (now != basesMemoStamp) {
			BASES_MEMO.clear();
			DONORS_MEMO.clear();
			basesMemoStamp = now;
		}
		String key = faction.getId() + ":" + system.getId();
		java.util.List<MarketAPI> memo = DONORS_MEMO.get(key);
		if (memo == null) {
			memo = new ArrayList<MarketAPI>();
			for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
				if (m.getStarSystem() == null || ThreatReserves.get(m.getId()) == null) continue;
				if (Misc.getDistanceLY(m.getStarSystem().getLocation(), system.getLocation()) > expeditionRangeLY(m)) continue;
				memo.add(m);
			}
			DONORS_MEMO.put(key, memo);
		}
		return new ArrayList<MarketAPI>(memo);
	}

	/**
	 * The Path's markets in reach whose marines join the faction's siege
	 * (user's call 2026-09-27): its strength is its zealots, and hives die to
	 * ground victories. Only while the Path is at war and Welcoming or better
	 * with the besieger (ThreatCoalition.willingness); drawn after the
	 * faction's own donors, each above its floor.
	 */
	protected static java.util.List<MarketAPI> zealotDonors(FactionAPI faction, StarSystemAPI system) {
		java.util.List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (!ThreatIncConfig.pathZealotMarines() || faction == null || system == null) return out;
		if (Factions.LUDDIC_PATH.equals(faction.getId())) return out;
		FactionAPI path = Global.getSector().getFaction(Factions.LUDDIC_PATH);
		String key = "zealots:" + faction.getId() + ":" + system.getId();
		if (path == null || !ThreatWarState.isAtWar(path)) {
			ThreatIncConfig.logQuiet(key, "Zealots: none for " + faction.getId() + " at " + system.getName() + " - the Path is not at war");
			return out;
		}
		float will = ThreatCoalition.willingness(path, faction.getId());
		if (will < 0.5f) {
			ThreatIncConfig.logQuiet(key, "Zealots: none for " + faction.getId() + " at " + system.getName()
					+ " - the Path's willingness is " + String.format("%.2f", will) + " of 0.50");
			return out;
		}
		for (MarketAPI m : factionMarketsInReach(path, system)) {
			if (!ThreatFrontlines.isOutpost(m)) out.add(m);
		}
		float held = siegePooled(null, out, com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
		ThreatIncConfig.logQuiet(key, "Zealots: " + out.size() + " Path world(s) in reach of " + system.getName()
				+ " offer " + faction.getId() + " " + (int) held + " marines");
		return out;
	}

	/** Stock the siege base and its donors hold above their floors, for the launch's gates. */
	protected static float siegePooled(MarketAPI base, java.util.List<MarketAPI> donors, String commodityId) {
		float have = base != null ? ThreatReserves.available(base, commodityId) : 0f;
		for (MarketAPI m : donors) have += donorAvailable(m, commodityId);
		return have;
	}

	/**
	 * What a donor gives a sibling's siege: its stock above the floor, less -
	 * for a forward base - siegeOutpostKeepMonths of its garrison's supply
	 * upkeep (ThreatFrontlines.payUpkeep), so the pool never recalls a garrison.
	 */
	protected static float donorAvailable(MarketAPI m, String commodityId) {
		float have = ThreatReserves.available(m, commodityId);
		if (have <= 0f || !com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES.equals(commodityId) || !ThreatFrontlines.isOutpost(m)) return have;
		return Math.max(0f, have - ThreatFrontlines.garrisonUpkeepAt(m)
				* Math.max(0f, ThreatIncConfig.siegeOutpostKeepMonths()));
	}

	/**
	 * Draws up to {@code amount} of a commodity for a siege: the base first,
	 * then the donors nearest it first, each above its floor; logs what the
	 * donors gave. Returns what was drawn.
	 */
	protected static float siegeDraw(MarketAPI base, java.util.List<MarketAPI> donors, String commodityId,
			float amount, String label) {
		float drawn = ThreatReserves.drawAbove(base, commodityId, amount);
		java.util.List<String> from = new ArrayList<String>();
		for (MarketAPI m : donors) {
			if (drawn >= amount) break;
			float got = ThreatReserves.drawAbove(m, commodityId, Math.min(amount - drawn, donorAvailable(m, commodityId)));
			drawn += got;
			if (got >= 1f) from.add(m.getName() + " " + (int) got);
		}
		if (!from.isEmpty()) {
			ThreatIncConfig.log("Expedition " + label + " pooled for " + base.getName() + ": "
					+ Misc.getAndJoined(from));
		}
		return drawn;
	}

	/** System id -> {@link #siegeBasesFor}, for the clock instant in basesMemoStamp; not saved. */
	private static final java.util.Map<String, java.util.List<MarketAPI>> BASES_MEMO =
			new java.util.HashMap<String, java.util.List<MarketAPI>>();
	private static long basesMemoStamp = Long.MIN_VALUE;

	/**
	 * Every military world of a mobilised NPC faction in reach of the hive
	 * system, nearest first. Memoised per clock instant: the hunting gate asks
	 * it per known hive per base, each time a walk of every market (rc1 review).
	 */
	public static java.util.List<MarketAPI> siegeBasesFor(StarSystemAPI system) {
		if (system == null) return new ArrayList<MarketAPI>();
		long now = Global.getSector().getClock().getTimestamp();
		if (now != basesMemoStamp) {
			BASES_MEMO.clear();
			DONORS_MEMO.clear();
			basesMemoStamp = now;
		}
		java.util.List<MarketAPI> memo = BASES_MEMO.get(system.getId());
		if (memo == null) {
			memo = findSiegeBases(system);
			BASES_MEMO.put(system.getId(), memo);
		}
		return new ArrayList<MarketAPI>(memo);
	}

	protected static java.util.List<MarketAPI> findSiegeBases(StarSystemAPI system) {
		java.util.List<MarketAPI> result = new ArrayList<MarketAPI>();
		final java.util.Map<MarketAPI, Float> dist = new java.util.HashMap<MarketAPI, Float>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getFaction() == null || market.getFaction().isPlayerFaction()) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			if (!ThreatWarState.isAtWar(market.getFactionId())) continue;
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (!isBase(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), system.getLocation());
			if (d > expeditionRangeLY(market)) continue;
			dist.put(market, d);
			result.add(market);
		}
		java.util.Collections.sort(result, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(dist.get(a), dist.get(b));
			}
		});
		return result;
	}

	/**
	 * Whether this base has a hive to siege: a known hive system it is the
	 * siege base for (siegeBaseFor) whose Defense Swarms its fullest flotilla
	 * outweighs. Such a base spends on the siege, not on hunting forces
	 * (ThreatSoftening) - the siege always comes first. The flotilla weighed
	 * is exactly the one tryPurgeBombardments sails (siegeSizes), so a base
	 * this says can siege is never then outweighed at the launch.
	 *
	 * <p>Only while the siege could actually sail (rc1 review): a system on its
	 * siege cooldown or already under this faction's siege, a landing the base
	 * cannot man, arm or provision, or sieges switched off bar nothing. Before,
	 * Nachiketa - nearest base for three hive systems - sat out every hunt and
	 * coalition call of both rc1 runs while it could launch nothing. What the
	 * base banks toward its siege stays safe from hunts either way
	 * (ThreatReserves.spendable).
	 */
	public static boolean hasSiegeableHive(MarketAPI base) {
		if (base == null || base.getFaction() == null) return false;
		if (!ThreatIncConfig.responsePurgeEnabled()) return false;
		FactionAPI faction = base.getFaction();
		java.util.Set<String> seen = new java.util.HashSet<String>();
		for (MarketAPI colony : ThreatIncData.getAllLiveColonyMarkets()) {
			StarSystemAPI system = colony.getStarSystem();
			if (system == null || !seen.add(system.getId())) continue;
			if (!ThreatScouts.sectorKnows(colony)) continue;
			if (siegeBaseFor(system) != base) continue;
			if (siegeFactionsIn(system.getId()).contains(faction.getId())) continue;
			java.util.List<MarketAPI> targets = siegeTargets(base, faction, system);
			if (!anySiegeReady(targets)) continue;
			if (siegeAffordable(base, faction, system, targets)) return true;
		}
		return false;
	}

	/** Whether the flotilla this base would sail against these worlds takes their orbit and is paid for. */
	protected static boolean siegeAffordable(MarketAPI base, FactionAPI faction, StarSystemAPI system,
			java.util.List<MarketAPI> targets) {
		java.util.List<Integer> sizes = siegeSizesFor(base, faction, targets, 0f);
		if (siegeOrbitNeeded(faction, targets) > ThreatAidCapacity.expeditionPoints(sizes)) return false;
		return siegeCanPay(base, faction, system, targets, sizes);
	}

	/**
	 * Whether the base can man, arm and provision this flotilla by the launch's
	 * own gates (launchSiegeExpedition): marines and armaments with the pool, and
	 * the fuel and supplies for the fleets that must sail - the passage, the
	 * ordnance, and the whole of any razing (razeWorlds).
	 */
	protected static boolean siegeCanPay(MarketAPI base, FactionAPI faction, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> sizes) {
		if (sizes.isEmpty() || !ThreatWarState.isAtWar(faction)) return true;
		java.util.Set<String> raze = razeWorlds(base, faction, system, targets);
		float[] wants = expeditionWants(base, system, targets, sizes, raze);
		java.util.List<MarketAPI> donors = !faction.isPlayerFaction()
				? siegeDonors(base, faction, system) : new ArrayList<MarketAPI>();
		java.util.List<MarketAPI> pool = ThreatIncConfig.siegePoolMarines() ? donors : new ArrayList<MarketAPI>();
		for (int i = 0; i < 2; i++) {
			if (wants[i] <= 0f) continue;
			String c = ThreatReserves.COMMODITIES[i];
			float have = siegePooled(base, pool, c);
			// the Path's zealots join the marines here as they do at the launch
			// (zealotDonors), or the gate refuses a landing the launch could pay for
			if (i == 0 && !faction.isPlayerFaction()) have += siegePooled(null, zealotDonors(faction, system), c);
			if (have < wants[i] * minMarinesFraction(faction)) return false;
		}
		if (faction.isPlayerFaction() || base.getStarSystem() == null) return true;
		java.util.List<MarketAPI> provisionPool = ThreatIncConfig.siegePoolProvisions() ? donors
				: new ArrayList<MarketAPI>();
		int points = 0;
		for (Integer size : sizes) points += size;
		// the passage and the ordnance grow with the fleets; the razing fuel is
		// set aside whole (the launch's own gate)
		float[] fuel = expeditionFuel(base, system, targets, sizes, raze);
		float haveFuel = siegePooled(base, provisionPool,
				com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL) - fuel[2];
		if (haveFuel < 0f) return false;
		float fuelPerPoint = points > 0 ? (fuel[0] + fuel[1]) / points : 0f;
		float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
		float payable = Float.MAX_VALUE;
		if (fuelPerPoint > 0f) {
			payable = Math.min(payable, haveFuel / fuelPerPoint);
		}
		if (suppliesPerPoint > 0f) {
			payable = Math.min(payable, siegePooled(base, provisionPool,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES) / suppliesPerPoint);
		}
		int mustPay = 0;
		if (ThreatIncConfig.npcSiegeFullStrength()) {
			float strGoal = Math.min(siegeRaidStrNeeded(landTargets(targets, raze)), siegeRaidStrEstimate(sizes));
			mustPay = Math.max(pointsForStrength(sizes, strGoal),
					pointsForOrbit(sizes, siegeFleetGoal(faction, targets, raze)));
		}
		return payable >= Math.max(mustPay, points * ThreatIncConfig.expeditionMinProvisionsFraction());
	}

	/**
	 * Every Threat colony in the system - the expedition purges the SYSTEM,
	 * not one world, worked through sequentially until the system is clean or
	 * the expedition is dead.
	 */
	public static java.util.List<MarketAPI> collectSiegeTargets(StarSystemAPI system) {
		java.util.List<MarketAPI> targets = new ArrayList<MarketAPI>();
		for (MarketAPI other : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			if (other.getPrimaryEntity() == null) continue;
			targets.add(other);
		}
		return targets;
	}

	/** Base id + system id -> {@link #siegeTargets}, for the clock instant in targetsMemoStamp; not saved. */
	private static final java.util.Map<String, java.util.List<MarketAPI>> TARGETS_MEMO =
			new java.util.HashMap<String, java.util.List<MarketAPI>>();
	private static long targetsMemoStamp = Long.MIN_VALUE;

	/**
	 * The worlds an NPC siege from this base takes, easiest first - the
	 * low-hanging fruit (user's rule 2026-09-27): the whole system when the
	 * base can take and pay for it; short of that, the most of its easiest
	 * worlds it can; short of that, the first world it can take alone; short of
	 * even one, the easiest alone, so its convoys stage toward the nearest win
	 * instead of waiting on marines for the biggest hive while the small ones
	 * grow. Worlds on their siege cooldown are left out (the launch needs one
	 * off it - anySiegeReady - so that last fallback only stages convoys), and
	 * so are worlds another faction's siege is taking: run 19 sent 14 of 37
	 * sieges at Epsilon Qades, and 7 stood down when someone else's landing got
	 * there first. So are worlds another faction's army holds
	 * (heldByOtherArmy): a front outlives its purge, and the landing gate
	 * refuses the ground - run 19's "1 of 2" siege raided Epsilon Qades I for
	 * all four passes. Empty when every world is someone else's. The player's
	 * Siege order takes the whole system, as ordered.
	 */
	public static java.util.List<MarketAPI> siegeTargets(MarketAPI base, FactionAPI faction,
			StarSystemAPI system) {
		java.util.List<MarketAPI> all = system != null
				? collectSiegeTargets(system) : new ArrayList<MarketAPI>();
		if (base == null || faction == null || faction.isPlayerFaction() || all.isEmpty()) return all;
		long now = Global.getSector().getClock().getTimestamp();
		if (now != targetsMemoStamp) {
			TARGETS_MEMO.clear();
			targetsMemoStamp = now;
		}
		String key = base.getId() + ":" + system.getId();
		java.util.List<MarketAPI> memo = TARGETS_MEMO.get(key);
		if (memo != null) return new ArrayList<MarketAPI>(memo);
		java.util.Set<MarketAPI> taken = besiegedByOthers(faction);
		java.util.List<MarketAPI> free = new ArrayList<MarketAPI>();
		for (MarketAPI t : easiestFirst(all)) {
			if (!taken.contains(t) && !heldByOtherArmy(faction, t) && !frontFinishesFirst(base, faction, system, t)) {
				free.add(t);
			}
		}
		if (free.isEmpty()) {
			TARGETS_MEMO.put(key, free);
			return new ArrayList<MarketAPI>();
		}
		java.util.List<MarketAPI> ready = new ArrayList<MarketAPI>();
		for (MarketAPI t : free) {
			if (!onPurgeCooldown(t)) ready.add(t);
		}
		if (ready.isEmpty()) ready = free;
		java.util.List<MarketAPI> pick = null;
		if (!ThreatWarState.isAtWar(faction)) {
			pick = ready;
		} else {
			for (int n = ready.size(); n >= 2; n--) {
				java.util.List<MarketAPI> set = new ArrayList<MarketAPI>(ready.subList(0, n));
				if (siegeAffordable(base, faction, system, set)) {
					pick = set;
					break;
				}
			}
			// no run of them: the first world it can take alone - the easiest
			// landing may sit under swarms it cannot match while a harder one
			// under light swarms is affordable now
			for (int i = 0; pick == null && i < ready.size(); i++) {
				java.util.List<MarketAPI> one = new ArrayList<MarketAPI>(ready.subList(i, i + 1));
				if (siegeAffordable(base, faction, system, one)) pick = one;
			}
		}
		// nothing affordable: the easiest alone, for its convoys to stage toward
		if (pick == null) pick = new ArrayList<MarketAPI>(ready.subList(0, 1));
		TARGETS_MEMO.put(key, pick);
		return new ArrayList<MarketAPI>(pick);
	}

	/** Whether another faction's front stands on the world - the landing gate's test (ThreatGroundFronts.landingBlocked): a siege of it could only raid. */
	protected static boolean heldByOtherArmy(FactionAPI faction, MarketAPI world) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(world.getId());
		return front != null && !ThreatGroundFronts.ownerOf(front).equals(faction.getId());
	}

	/**
	 * Whether the faction's own front takes the world's last stratum before a
	 * siege from base could even arrive (ThreatGroundFronts.daysToLastStratum
	 * against razeArrivalDays): no fleet is sent to reinforce or raze what the
	 * front is about to finish (run 6: a razing sailed for a hive its front
	 * took a day later).
	 */
	protected static boolean frontFinishesFirst(MarketAPI base, FactionAPI faction, StarSystemAPI system,
			MarketAPI world) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(world.getId());
		if (front == null || !faction.getId().equals(ThreatGroundFronts.ownerOf(front))) return false;
		float days = ThreatGroundFronts.daysToLastStratum(front, world);
		return days >= 0f && days <= razeArrivalDays(base, system);
	}

	/** The worlds a live siege by any faction but this one is taking. */
	protected static java.util.Set<MarketAPI> besiegedByOthers(FactionAPI faction) {
		java.util.Set<MarketAPI> out = new java.util.HashSet<MarketAPI>();
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (purge.getFaction() == faction) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			out.addAll(purge.getParams().raidParams.allowedTargets);
		}
		return out;
	}

	/** The hives sorted by the landing each needs alone, then the Defense Swarms over it: easiest first. */
	public static java.util.List<MarketAPI> easiestFirst(java.util.List<MarketAPI> hives) {
		final java.util.Map<MarketAPI, float[]> ease = new java.util.HashMap<MarketAPI, float[]>();
		for (MarketAPI h : hives) {
			java.util.List<MarketAPI> one = java.util.Collections.singletonList(h);
			ease.put(h, new float[] {siegeRaidStrNeeded(one), siegeOrbitFaced(one)});
		}
		java.util.List<MarketAPI> result = new ArrayList<MarketAPI>(hives);
		java.util.Collections.sort(result, new java.util.Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				float[] ea = ease.get(a), eb = ease.get(b);
				int c = Float.compare(ea[0], eb[0]);
				return c != 0 ? c : Float.compare(ea[1], eb[1]);
			}
		});
		return result;
	}

	public static boolean anyTargetGarrisoned(java.util.List<MarketAPI> targets) {
		for (MarketAPI target : targets) {
			if (ThreatColonyManager.countLiveGarrison(target.getId()) > 0) return true;
		}
		return false;
	}

	/**
	 * Headroom on the ground strength a siege brings: vanilla raises defender
	 * preparedness after every raid, so the second and third passes face more
	 * than the first did.
	 */
	public static final float SIEGE_RAID_HEADROOM = 1.25f;

	/** Target ids -> {@link #siegeRaidStrNeeded}, for the clock instant in needMemoStamp; not saved. */
	private static final java.util.Map<String, Float> NEED_MEMO = new java.util.HashMap<String, Float>();
	private static long needMemoStamp = Long.MIN_VALUE;

	/**
	 * The ground strength a siege must put on the surface to disrupt anything.
	 * Vanilla's raid effectiveness is raidStr / (raidStr + defenderStr) and an
	 * industry raid needs MarketCMD.DISRUPTION_THRESHOLD (0.25) of it, so the
	 * landing force must be at least a third of the strongest target's
	 * defender strength as it will stand AFTER the siege's bombardment - plus
	 * headroom for the preparedness bump. Hive defenses run to thousands
	 * (hiveDefensePerSize x size, multiplied by the Nexus and the batteries),
	 * which is exactly why a flotilla sized by difficulty alone spent its
	 * raids being repulsed.
	 *
	 * <p>THE PLANNED BOMBARDMENT (docs/suppression-balance.md v2, 2026-09-28).
	 * Orbit never wears a fortification out now - each day finds fewer targets -
	 * so "after the bombardment" is what bombarding until orbit has done what it
	 * can leaves (ThreatGroundFronts.bombardPlan), and that turns on the fleet
	 * points overhead. They are planned as the flotilla that carries this
	 * landing (siegeRaidStrPerPoint marines and FP_PER_RESPONSE_DIFFICULTY fleet
	 * points a difficulty point), solved together with the landing, since a
	 * bigger flotilla wears the defence further and needs a smaller one. That is
	 * the least any siege of these worlds sails with - siegeFleetSizes grows the
	 * flotilla to carry its landing, then to the orbit and past its baseline -
	 * so a landing is never sized on wear its fleets cannot deliver, and the
	 * figure turns on the worlds alone: the launch, the sizing, the convoys and
	 * the board read the same one. The ordnance is drawn for the flotilla that
	 * actually sails (expeditionFuel), which wears at least this far.
	 */
	public static float siegeRaidStrNeeded(java.util.List<MarketAPI> targets) {
		if (targets == null || targets.isEmpty()) return 0f;
		long now = Global.getSector().getClock().getTimestamp();
		if (now != needMemoStamp) {
			NEED_MEMO.clear();
			needMemoStamp = now;
		}
		StringBuilder key = new StringBuilder();
		for (MarketAPI target : targets) key.append(target != null ? target.getId() : "-").append('|');
		Float memo = NEED_MEMO.get(key.toString());
		if (memo != null) return memo;
		// the flotilla's fleet points per marine it lands
		float carry = FP_PER_RESPONSE_DIFFICULTY / Math.max(1f, ThreatIncConfig.siegeRaidStrPerPoint());
		// the fleets that carry the landing their wear leaves: need falls as the
		// fleets grow, so one crossing. Below it (lo) the fleets are too few to
		// carry what their own wear needs landed
		float lo = 0f;
		float hi = carry * raidStrNeededAt(targets, 0f);
		for (int i = 0; i < 16 && hi - lo > 1f; i++) {
			float mid = (lo + hi) / 2f;
			if (mid >= carry * raidStrNeededAt(targets, mid)) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		// sized at the low side: the fleets that carry it bring at least lo
		float need = raidStrNeededAt(targets, lo);
		NEED_MEMO.put(key.toString(), need);
		return need;
	}

	/** {@link #siegeRaidStrNeeded} after {@code fp} fleet points have bombarded each world as far as orbit goes (0: not at all). */
	protected static float raidStrNeededAt(java.util.List<MarketAPI> targets, float fp) {
		float def = 0f;
		for (MarketAPI target : targets) {
			if (target == null) continue;
			// a young colony reads vanilla's shallow base until its Swarm Nexus goes
			// up, weeks before the siege arrives: sized on that, landings of 300
			// met counter-attacks of 1,180 (Rhesh, Run 7). Size on the Nexus anchor
			// too, worn as far as the plan wears what stands - a bombardment no
			// longer strips the Nexus bonus whole
			float d = Math.max(com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.getDefenderStr(target),
					nexusAnchoredDefense(target));
			def = Math.max(def, d * ThreatGroundFronts.bombardPlan(target, fp)[1]);
		}
		float threshold = com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.DISRUPTION_THRESHOLD;
		float raid = def * threshold / Math.max(0.01f, 1f - threshold) * SIEGE_RAID_HEADROOM;
		return Math.max(raid, beachheadNeeded(def));
	}

	/** A hive's defender strength once its Swarm Nexus stands (SwarmNexus: the size anchor over the strata left, times the Nexus bonus), batteries aside. */
	public static float nexusAnchoredDefense(MarketAPI target) {
		int strataLeft = Math.max(0, target.getSize() - ThreatGroundFronts.strataHeld(target.getId()));
		return ThreatIncConfig.hiveDefensePerSize() * strataLeft
				* (1f + ThreatIncConfig.nexusDefenseBonus()) * ThreatIncConfig.groundDefenseMult();
	}

	/**
	 * The landing that survives the first counter-attack: the hive throws its
	 * whole defender strength at a fresh beachhead (landing effectiveness
	 * frontLandingMult, no cover yet) and overruns it past 2:1 odds
	 * (ThreatGroundFronts.hiveCounterAttack). Sized for the raids alone - a
	 * quarter of the defences - 18 of 19 Persean landings were overrun in
	 * Run 6. siegeBeachheadMargin over the 2:1 line; 0 sizes for the raids only.
	 */
	public static float beachheadNeeded(float defenderStr) {
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (margin <= 0f || defenderStr <= 0f) return 0f;
		float e = Math.max(0.1f, ThreatIncConfig.groundStrengthExponent());
		float odds = (float) Math.pow(2f, 1f / e);
		return defenderStr / (Math.max(0.05f, ThreatIncConfig.frontLandingMult()) * odds) * margin;
	}

	/**
	 * What a flotilla of these difficulty points lands: NPC raid strength is a
	 * quarter of the fleets' crew capacity, and crew capacity tracks fleet
	 * size, so difficulty points times siegeRaidStrPerPoint (measured, not
	 * derived - see the setting).
	 */
	/**
	 * The share of the marines wanted a siege may launch with. An NPC siege
	 * sails at full strength (npcSiegeFullStrength, 2026-09-24): with half,
	 * the flotilla was trimmed to two fleets the target's garrison tore
	 * apart before a single landing (Hegemony at Thrial, twice). The
	 * player's own sieges keep expeditionMinMarinesFraction.
	 */
	public static float minMarinesFraction(FactionAPI faction) {
		if (faction != null && !faction.isPlayerFaction() && ThreatIncConfig.npcSiegeFullStrength()) {
			return 1f;
		}
		return ThreatIncConfig.expeditionMinMarinesFraction();
	}

	/** Difficulty points, taken in order, before the flotilla's estimate reaches the need (all of them if it never does). */
	public static int pointsForStrength(java.util.List<Integer> fleetSizes, float need) {
		int points = 0;
		for (Integer size : fleetSizes) {
			if (points * ThreatIncConfig.siegeRaidStrPerPoint() >= need) break;
			points += size;
		}
		return points;
	}

	/** Difficulty points, taken in order, before the flotilla's fleet points reach the orbit need (all of them if they never do; 0 when the orbit is not weighed). */
	public static int pointsForOrbit(java.util.List<Integer> fleetSizes, float orbitNeed) {
		int points = 0;
		for (Integer size : fleetSizes) {
			if (points * FP_PER_RESPONSE_DIFFICULTY >= orbitNeed) break;
			points += size;
		}
		return points;
	}

	public static float siegeRaidStrEstimate(java.util.List<Integer> fleetSizes) {
		int points = 0;
		for (Integer size : fleetSizes) points += size;
		return points * ThreatIncConfig.siegeRaidStrPerPoint();
	}

	/** Fleet points of the Defense Swarms standing over the targets now. */
	public static float siegeOrbitFP(java.util.List<MarketAPI> targets) {
		float fp = 0f;
		for (MarketAPI target : targets) {
			if (target == null) continue;
			for (CampaignFleetAPI fleet : ThreatIncData.garrisonsFor(target.getId())) {
				if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
				fp += fleet.getFleetPoints();
			}
		}
		return fp;
	}

	/**
	 * The Defense Swarms a siege's flotilla fights at once: the strongest
	 * single world's (npcSiegeOrbitPerWorld), since a siege is SEQUENTIAL -
	 * the whole flotilla takes the system's worlds one at a time - and a
	 * garrison fights over its own world only. Off: every world's summed.
	 */
	public static float siegeOrbitFaced(java.util.List<MarketAPI> targets) {
		if (!ThreatIncConfig.npcSiegeOrbitPerWorld()) return siegeOrbitFP(targets);
		float most = 0f;
		for (MarketAPI target : targets) {
			most = Math.max(most, siegeOrbitFP(java.util.Collections.singletonList(target)));
		}
		return most;
	}

	/**
	 * The fleet points an NPC flotilla must bring to take the targets' orbit
	 * (2026-09-24): the Defense Swarms it faces (siegeOrbitFaced) times
	 * npcSiegeOrbitMargin. A siege sized only by its landing sailed into
	 * Thrial's six garrisons at full marines and came home at 39% without a
	 * landing. Weighing every world's swarms at once instead left the gate shut
	 * for good: 679 postponements and one siege in a 21-month run. 0 for the
	 * player's own sieges and with the gate off.
	 */
	public static float siegeOrbitNeeded(FactionAPI faction, java.util.List<MarketAPI> targets) {
		if (faction == null || faction.isPlayerFaction() || !ThreatIncConfig.npcSiegeOrbitGate()) return 0f;
		return siegeOrbitWeighed(faction, targets) * Math.max(0f, ThreatIncConfig.npcSiegeOrbitMargin());
	}

	/**
	 * The Defense Swarms an NPC siege weighs itself against: the strongest
	 * world's it takes (siegeOrbitFaced), every garrison of the system with
	 * npcSiegeOrbitSystem - they converge on a besieged world
	 * (ThreatColonyManager.redistributeGarrisons) and vanilla's off-screen
	 * fight weighs every hostile fleet in the system - and what its last
	 * called-off siege there met (swarmsMet).
	 */
	public static float siegeOrbitWeighed(FactionAPI faction, java.util.List<MarketAPI> targets) {
		float faced = siegeOrbitFaced(targets);
		java.util.Set<com.fs.starfarer.api.campaign.StarSystemAPI> systems =
				new java.util.LinkedHashSet<com.fs.starfarer.api.campaign.StarSystemAPI>();
		for (MarketAPI t : targets) {
			if (t != null && t.getStarSystem() != null) systems.add(t.getStarSystem());
		}
		for (com.fs.starfarer.api.campaign.StarSystemAPI system : systems) {
			if (faction != null) faced = Math.max(faced, swarmsMet(faction.getId(), system));
			if (ThreatIncConfig.npcSiegeOrbitSystem()) faced = Math.max(faced, systemSwarms(system));
		}
		return faced;
	}

	/** Fleet points of every Defense Swarm over the Threat's worlds in the system. */
	public static float systemSwarms(com.fs.starfarer.api.campaign.StarSystemAPI system) {
		java.util.List<MarketAPI> hives = new ArrayList<MarketAPI>();
		for (MarketAPI m : Misc.getMarketsInLocation(system)) {
			if (Factions.THREAT.equals(m.getFactionId())) hives.add(m);
		}
		return siegeOrbitFP(hives);
	}

	/** System memory key prefix: the swarm weight a faction's siege was called off against there. */
	protected static final String SWARMS_MET_KEY = "$threatinc_swarmsMet_";

	/**
	 * What the last siege of this faction called off in the system met
	 * (ThreatPurgeFGI.callOff, 2026-09-29 overnight): the launch weighs one
	 * world's Defense Swarms, but the hive's garrisons converge on a besieged
	 * world and vanilla's off-screen fight weighs the whole system - run N2's
	 * sieges sailed into 4-7x their weight, Goodfellow twice. A commander does
	 * not sail into the same wall again until the intelligence is
	 * siegeMetMemoryDays old; the bounty the call-off posts sends hunters in.
	 */
	public static float swarmsMet(String factionId, com.fs.starfarer.api.campaign.StarSystemAPI system) {
		if (factionId == null || system == null) return 0f;
		return system.getMemoryWithoutUpdate().getFloat(SWARMS_MET_KEY + factionId);
	}

	public static void noteSwarmsMet(String factionId, com.fs.starfarer.api.campaign.StarSystemAPI system, float fp) {
		float days = ThreatIncConfig.siegeMetMemoryDays();
		if (factionId == null || system == null || fp <= 0f || days <= 0f) return;
		system.getMemoryWithoutUpdate().set(SWARMS_MET_KEY + factionId, Math.max(fp, swarmsMet(factionId, system)), days);
	}

	/**
	 * The flotilla for a siege expedition, sized to the target. The baseline is
	 * the old shape - two core fleets, an escort when any target still fields
	 * Defense Swarms, a second for a heavy assault on a defended entrenched
	 * hive, a fourth for a multi-world campaign - and then fleets are added
	 * until the estimated ground strength clears siegeRaidStrNeeded, up to
	 * siegeMaxFleets. Difficulty still sets the quality of each fleet; the
	 * defenses set how many there are. Shared by the NPC trigger and the
	 * player commission preview so the quoted bill always matches the fleets
	 * that actually sail; if the cap still leaves the force short the caller
	 * can see it (siegeRaidStrEstimate below siegeRaidStrNeeded) and say so.
	 *
	 * <p>The flotilla grows until it can LAND at least {@code marineGoal} - the player's
	 * Extra/All siege tiers ask for a bigger landing than the defenses alone
	 * demand, and the fleets must be big enough to carry it or the surplus never
	 * boards (ThreatPurgeFGI clips marines to crew space). 0 means "the need".
	 */
	public static java.util.List<Integer> siegeFleetSizes(int difficulty, boolean anyGarrisoned,
			boolean heavyAssault, java.util.List<MarketAPI> targets, float marineGoal) {
		return siegeFleetSizes(difficulty, anyGarrisoned, heavyAssault, targets, marineGoal, 0f);
	}

	/** As above, and the flotilla also grows until its fleet points reach {@code orbitGoal} (siegeOrbitNeeded; 0 = not weighed). */
	public static java.util.List<Integer> siegeFleetSizes(int difficulty, boolean anyGarrisoned,
			boolean heavyAssault, java.util.List<MarketAPI> targets, float marineGoal, float orbitGoal) {
		return siegeFleetSizes(difficulty, anyGarrisoned, heavyAssault, targets, targets, marineGoal, orbitGoal);
	}

	/**
	 * As above, the landing sized for {@code landTargets} alone: the worlds the
	 * siege lands on. A world it razes (razeWorlds) needs the orbit's fleets
	 * and no landing, so a siege that razes every world sails for the orbit only.
	 */
	public static java.util.List<Integer> siegeFleetSizes(int difficulty, boolean anyGarrisoned,
			boolean heavyAssault, java.util.List<MarketAPI> targets, java.util.List<MarketAPI> landTargets,
			float marineGoal, float orbitGoal) {
		java.util.List<Integer> sizes = new ArrayList<Integer>();
		sizes.add(Math.min(10, difficulty));
		sizes.add(Math.max(5, difficulty - 2));
		if (anyGarrisoned) sizes.add(Math.min(10, difficulty));
		if (heavyAssault) sizes.add(Math.min(10, difficulty));
		if (targets.size() >= 3) sizes.add(Math.max(5, difficulty - 2));
		float needed = Math.max(siegeRaidStrNeeded(landTargets), marineGoal);
		int maxFleets = Math.max(sizes.size(), ThreatIncConfig.siegeMaxFleets());
		int extra = Math.min(10, Math.max(6, difficulty));
		while ((siegeRaidStrEstimate(sizes) < needed
				|| ThreatAidCapacity.expeditionPoints(sizes) < orbitGoal) && sizes.size() < maxFleets) {
			sizes.add(extra);
		}
		return sizes;
	}

	/**
	 * Builds and launches one siege expedition - the single construction path
	 * for both NPC purges and player-commissioned operations. The player
	 * variant differs only in flags that would otherwise misfire for the
	 * player faction: the fleets must NOT be triggered hostile to the player
	 * (GenericRaidParams.makeFleetsHostile defaults to true), the intel must
	 * not play the colony-threat alarm (playerTargeted=false), and the
	 * faction-name strings come from overrides in ThreatPurgeFGI.
	 */
	public static ThreatPurgeFGI launchSiegeExpedition(MarketAPI base, FactionAPI faction,
			StarSystemAPI system, java.util.List<MarketAPI> targets,
			java.util.List<Integer> fleetSizes, boolean playerCommissioned, Random random) {
		return launchSiegeExpedition(base, faction, system, targets, fleetSizes,
				playerCommissioned, random, 0f);
	}

	/**
	 * As {@link #launchSiegeExpedition(MarketAPI, FactionAPI, StarSystemAPI, java.util.List, java.util.List, boolean, Random)},
	 * but the landing draws up to {@code marineGoal} marines from the base's
	 * reserve rather than only the computed need - the player's Extra/All siege
	 * tiers. 0 keeps the need. The fleetSizes passed in must already be big
	 * enough to lift the goal (see {@link #siegeFleetSizes}); the postpone gate
	 * still measures against the need, so a bigger ask never blocks a viable
	 * landing.
	 */
	public static ThreatPurgeFGI launchSiegeExpedition(MarketAPI base, FactionAPI faction,
			StarSystemAPI system, java.util.List<MarketAPI> targets,
			java.util.List<Integer> fleetSizes, boolean playerCommissioned, Random random,
			float marineGoal) {
		return launchSiegeExpedition(base, faction, system, targets, fleetSizes, playerCommissioned, random,
				marineGoal, null);
	}

	/**
	 * As above, razing the worlds in {@code razeGiven} rather than those the
	 * faction's navy would pick (razeWorlds): the player's Bombard order razes
	 * every world it is sent against. Null: razeWorlds decides.
	 */
	public static ThreatPurgeFGI launchSiegeExpedition(MarketAPI base, FactionAPI faction,
			StarSystemAPI system, java.util.List<MarketAPI> targets,
			java.util.List<Integer> fleetSizes, boolean playerCommissioned, Random random,
			float marineGoal, java.util.Set<String> razeGiven) {
		GenericRaidParams params = new GenericRaidParams(
				new Random(random.nextLong()), !playerCommissioned);
		params.factionId = faction.getId();
		params.source = base;
		params.prepDays = 7f + 7f * random.nextFloat();
		// the siege holds orbit for siegeOrbitDays per world (2026-09-06, the
		// strike's rule): suppressing a hive's war-strata from orbit is a
		// campaign, not a pass; vanilla re-times the payload stage once the
		// fleets spawn (FGRaidAction.computeSubstages)
		params.payloadDays = ThreatIncConfig.siegeOrbitDays() * (0.9f + 0.2f * random.nextFloat());
		params.raidParams.maxDurationIfSpawnedFleetsConcurrent = ThreatIncConfig.siegeOrbitDays();
		params.raidParams.maxDurationIfSpawnedFleetsPerSequentialStage = ThreatIncConfig.siegeOrbitDays();
		params.raidParams.where = system;
		params.raidParams.type = FGRaidType.SEQUENTIAL;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.addAll(targets);
		params.raidParams.allowNonHostileTargets = true;
		// no params.bombardment: ThreatPurgeFGI runs the siege doctrine - the
		// orbital duel while orbit still has work to do (slices spend no pass),
		// a ground landing once it has done what it can or the troops could
		// hold; a world it razes (razeWorlds) takes its own saturation passes
		// instead (ThreatPurgeFGI.razePass), never vanilla's instant one. The
		// passes are the landing and the reinforcements.
		params.raidParams.raidsPerColony = Math.max(1, ThreatIncConfig.siegePassesPerColony());
		params.raidParams.raidApproachText = "moving to besiege";
		params.raidParams.raidActionText = "conducting siege operations against";
		params.noun = "purge expedition";
		// vanilla writes "the <forcesNoun> are withdrawing", so no "Your" here
		params.forcesNoun = playerCommissioned ? "commissioned forces"
				: faction.getDisplayName() + " forces";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;
		// GenericRaidParams.makeFleetsHostile defaults to true, whose bare
		// triggerMakeHostile() call turns the fleets hostile to the PLAYER
		// (Misc reads the flagless $cfai_makeHostile as player-hostility),
		// overriding faction standing - which is why even a Cooperative
		// sponsor's purge would pursue the player. The purge fleets don't need
		// it: the Threat is perma-hostile to everyone (see permaHostile), so
		// they engage the colonies via normal relations regardless. Off for
		// every expedition - NPC and player-commissioned alike.
		params.makeFleetsHostile = false;
		// a player expedition is what its base can field (docs/player-aid.md):
		// fleets dropped, then shrunk, to the colony's free capacity - and
		// refused when even the smallest flotilla is more than that (2026-09-05:
		// five sieges sailed 1,025 FP from a 239 FP colony)
		fleetSizes = ThreatAidCapacity.fitExpedition(base, faction, fleetSizes);
		if (faction.isPlayerFaction() && ThreatAidCapacity.enabled()) {
			float points = ThreatAidCapacity.expeditionPoints(fleetSizes);
			float free = ThreatAidCapacity.freeFP(base);
			if (points > free) {
				ThreatIncConfig.log("Expedition refused at " + base.getName() + ": " + (int) points
						+ " FP wanted, " + (int) free + " FP free");
				return null;
			}
		}
		params.fleetSizes.addAll(fleetSizes);

		// STRATEGY LAYER (docs/strategy-layer.md): a mobilised faction's
		// expedition draws its landing force and provisions from the BASE's
		// reserve - troops that then ride the fleets as real cargo. Short of
		// marines, the expedition waits for the convoys to stage more.
		float[] drawn = null;
		float[] fuelParts = null;
		// the worlds it razes from orbit rather than lands on (razeWorlds, or
		// the caller's): decided once, before anything is drawn, and carried by
		// the expedition
		java.util.Set<String> raze = razeGiven != null ? new java.util.LinkedHashSet<String>(razeGiven)
				: razeWorlds(base, faction, system, targets);
		java.util.List<MarketAPI> land = landTargets(targets, raze);
		if (!raze.isEmpty() && land.isEmpty()) {
			// nothing to land on: its fleets say what they are there for
			params.raidParams.raidApproachText = "moving to raze";
			params.raidParams.raidActionText = "razing";
			// an unspawned expedition acts only when its payload window ends
			// (vanilla autoresolves at the segment's end): a siege's window is
			// siegeOrbitDays, a razing's the days it razes for. At 120 days the
			// hives grew a size before the bombs fell (Qaras, 2026-09-28)
			float days = 0f;
			for (float[] world : razeRun(razeTargets(targets, raze),
					ThreatAidCapacity.expeditionPoints(fleetSizes), -1f)) {
				days += Math.max(1f, world[0]);
			}
			params.payloadDays = Math.max(2f, Math.min(params.payloadDays, (float) Math.ceil(days) + 1f));
		}
		if (ThreatWarState.isAtWar(faction)) {
			// the orbit first (2026-09-24): Defense Swarms that outweigh the
			// largest siege this base can field are the player's to thin - a
			// bounty goes up now, while the base banks its marines and
			// provisions, so whether a siege sails is the garrison's to decide,
			// not the colony's size. Every swarm destroyed brings it closer.
			float orbitNeed = siegeOrbitNeeded(faction, targets);
			// the fleet points the siege sails with at least: the orbit's, and
			// what outlasts the guns of the worlds it razes (siegeFleetGoal)
			float fleetGoal = siegeFleetGoal(faction, targets, raze);
			float fieldable = ThreatAidCapacity.expeditionPoints(params.fleetSizes);
			if (orbitNeed > 0f && fieldable < orbitNeed) {
				float garrison = siegeOrbitWeighed(faction, targets);
				int allowed = (int) (fieldable / Math.max(0.01f, ThreatIncConfig.npcSiegeOrbitMargin()));
				ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
						+ (int) fieldable + " FP against " + (int) garrison + " FP of Defense Swarms over "
						+ system.getName() + " (takes at most " + allowed + ")");
				ThreatSwarmBountyIntel.post(base, system, allowed);
				return null;
			}
			// the ground strength this flotilla sets out with: the target's need, or
			// what siegeMaxFleets allows when the cap left it short - a capped
			// flotilla sailed before the full-strength gate and still does
			float need = siegeRaidStrNeeded(land);
			float strGoal = Math.min(need, siegeRaidStrEstimate(params.fleetSizes));
			float[] wants = expeditionWants(base, system, targets, fleetSizes, raze);
			// the landing the player asked for: the need (wants[0]), or the
			// Extra/All tier's bigger goal - never below the need
			float lift = marineGoal > wants[0] ? marineGoal : wants[0];
			// what the base may actually commit: its stock above the floor
			float haveMarines = ThreatReserves.available(base,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
			// a navy's landing is the navy's: short at the base, every market of
			// the faction in reach puts aboard what it holds above its floor
			// (siegeDonors, 2026-09-26: colonies as well as bases, no donor keep
			// or staging hold - a size-4 hive's beachhead is ~2,400 troops; one
			// full depot holds ~560, and sibling bases' spare stock held a tenth
			// of the faction's marines in Run 5)
			java.util.List<MarketAPI> donors = !faction.isPlayerFaction()
					? siegeDonors(base, faction, system) : new ArrayList<MarketAPI>();
			java.util.List<MarketAPI> pool = ThreatIncConfig.siegePoolMarines() ? donors : new ArrayList<MarketAPI>();
			java.util.List<MarketAPI> marinePool = haveMarines < lift ? new ArrayList<MarketAPI>(pool)
					: new ArrayList<MarketAPI>();
			// the Path's zealots come after the faction's own (zealotDonors)
			java.util.List<MarketAPI> zealots = haveMarines < lift && !faction.isPlayerFaction()
					? zealotDonors(faction, system) : new ArrayList<MarketAPI>();
			marinePool.addAll(zealots);
			for (MarketAPI m : marinePool) {
				haveMarines += ThreatReserves.available(m, com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
			}
			float minMarines = wants[0] * minMarinesFraction(faction);
			// SHORT OF TROOPS, SOFTEN FIRST (2026-09-28, run 4): a base that
			// cannot raise the landing still sails if bombarding on past the
			// ships' worth lets the marines it has land - they sail with all of
			// them, and the ordnance of the longer bombardment
			float shortLanding = 0f;
			if (wants[0] > 0f && haveMarines < minMarines && !faction.isPlayerFaction()
					&& landsAfterSoftening(land, ThreatAidCapacity.expeditionPoints(params.fleetSizes), haveMarines)) {
				shortLanding = haveMarines;
				ThreatIncConfig.logQuiet("short:" + base.getId() + ":" + system.getId(),
						"Expedition at " + base.getName() + " against " + system.getName()
						+ " lands " + (int) haveMarines + " of " + (int) wants[0]
						+ " marines after a longer bombardment");
				wants = expeditionWants(base, system, targets, fleetSizes, raze, shortLanding);
				lift = haveMarines;
			} else if (wants[0] > 0f && haveMarines < minMarines) {
				ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
						+ (int) haveMarines + " of " + (int) wants[0]
						+ " marines available (need " + (int) minMarines + ")");
				return null;
			}
			// SEND WHAT YOU CAN (docs/design-theory.md 8.6): short of the force
			// the tier asked for, the flotilla shrinks to the landing it can
			// lift rather than never sailing - never below two fleets
			if (haveMarines < lift) {
				int before = params.fleetSizes.size();
				while (params.fleetSizes.size() > 2
						&& siegeRaidStrEstimate(params.fleetSizes) > haveMarines) {
					params.fleetSizes.remove(params.fleetSizes.size() - 1);
				}
				if (params.fleetSizes.size() < before) {
					ThreatIncConfig.log("Expedition trimmed at " + base.getName() + ": "
							+ before + " -> " + params.fleetSizes.size() + " fleets for "
							+ (int) haveMarines + " marines");
				}
			}
			// an NPC siege is paid for as well (2026-09-24): the flotilla shrinks
			// to the fuel and supplies its depot holds above the floor, and
			// below expeditionMinProvisionsFraction of what it would burn it
			// waits for convoys and the War footing to refill the depot. The
			// player's expedition pays what it can - cost, never permission.
			// Pooled like the marines (siegePoolProvisions, 2026-09-26): the
			// base's stock above its floor plus every donor's. Read at the base
			// alone, supplies bound 220 of Run 5's 244 provision postponements
			// at a median 299 against a 12,600 bill the faction held 2.5x of.
			java.util.List<MarketAPI> provisionPool = ThreatIncConfig.siegePoolProvisions() ? donors
					: new ArrayList<MarketAPI>();
			if (!faction.isPlayerFaction()) {
				int points = 0;
				for (Integer size : params.fleetSizes) points += size;
				// fuel is the passage and the ordnance (expeditionFuel), both growing
				// with the fleets; the razing fuel is set aside whole, as razeWorlds
				// razes only what the reserve holds over the passage
				float[] fuel = expeditionFuel(base, system, targets, params.fleetSizes, raze, shortLanding);
				float fuelPerPoint = points > 0 ? (fuel[0] + fuel[1]) / points : 0f;
				float suppliesPerPoint = ThreatIncConfig.expeditionSuppliesPerPoint();
				float haveFuel = Math.max(0f, siegePooled(base, provisionPool,
						com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL) - fuel[2]);
				float haveSupplies = siegePooled(base, provisionPool,
						com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES);
				String pooledNote = provisionPool.isEmpty() ? "" : " across " + (provisionPool.size() + 1) + " markets";
				// the fleet points the depot can pay for
				float payable = Float.MAX_VALUE;
				if (fuelPerPoint > 0f) payable = Math.min(payable, haveFuel / fuelPerPoint);
				if (suppliesPerPoint > 0f) payable = Math.min(payable, haveSupplies / suppliesPerPoint);
				// full strength (2026-09-24): the fleets that reach the target's
				// ground strength, its orbit and a razing's guns are not for
				// trimming - the depot pays for them or the siege waits, whatever
				// the floor fraction says. Only the fleets beyond them shrink to
				// what it can pay for.
				int mustPay = 0;
				if (ThreatIncConfig.npcSiegeFullStrength()) {
					mustPay = Math.max(pointsForStrength(params.fleetSizes, strGoal),
							pointsForOrbit(params.fleetSizes, fleetGoal));
				}
				float minProvisions = Math.max(mustPay,
						points * ThreatIncConfig.expeditionMinProvisionsFraction());
				if (points > 0 && payable < minProvisions) {
					ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
							+ (int) haveFuel + "/" + (int) (points * fuelPerPoint) + " fuel"
							+ (fuel[2] > 0f ? " past " + (int) fuel[2] + " for razing" : "") + ", "
							+ (int) haveSupplies + "/" + (int) (points * suppliesPerPoint)
							+ " supplies" + pooledNote + " pay for " + (int) Math.min(points, payable) + " of the "
							+ points + " points (needs " + (int) Math.ceil(minProvisions) + ")");
					return null;
				}
				// payable >= mustPay, and mustPay is the leading fleets, so the
				// trim never reaches them
				int before = params.fleetSizes.size();
				while (params.fleetSizes.size() > 2 && points > payable) {
					points -= params.fleetSizes.remove(params.fleetSizes.size() - 1);
				}
				if (params.fleetSizes.size() < before) {
					ThreatIncConfig.log("Expedition trimmed at " + base.getName() + ": "
							+ before + " -> " + params.fleetSizes.size() + " fleets for "
							+ (int) haveFuel + " fuel, " + (int) haveSupplies + " supplies");
				}
				// the same rule from the other side; holds by construction, kept
				// so a trim below what the target needs can never sail
				if (ThreatIncConfig.npcSiegeFullStrength()
						&& siegeRaidStrEstimate(params.fleetSizes) < strGoal) {
					ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
							+ (int) haveFuel + " fuel, " + (int) haveSupplies + " supplies pay for "
							+ (int) siegeRaidStrEstimate(params.fleetSizes) + " of the "
							+ (int) strGoal + " ground strength the siege sails with");
					return null;
				}
				// the orbit again, and the razing's guns: a flotilla the depot
				// trimmed below them waits for convoys (the base's job, so no request)
				float brings = ThreatAidCapacity.expeditionPoints(params.fleetSizes);
				if (fleetGoal > 0f && brings < fleetGoal) {
					ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
							+ (int) haveFuel + " fuel, " + (int) haveSupplies + " supplies pay for "
							+ (int) brings + " of the " + (int) Math.ceil(fleetGoal) + " FP the "
							+ (fleetGoal > orbitNeed ? "razing" : "orbit") + " needs");
					return null;
				}
			}
			// a short landing rests on the fleets' wear: trimmed for provisions
			// below what softens the worlds enough, it waits
			if (shortLanding > 0f && !landsAfterSoftening(land,
					ThreatAidCapacity.expeditionPoints(params.fleetSizes), shortLanding)) {
				ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
						+ (int) ThreatAidCapacity.expeditionPoints(params.fleetSizes) + " FP cannot soften it enough for "
						+ (int) shortLanding + " marines");
				return null;
			}
			// provisions to what actually sails (the landing's own wants
			// depend on the targets, not the fleets)
			wants = expeditionWants(base, system, targets, params.fleetSizes, raze, shortLanding);
			fuelParts = expeditionFuel(base, system, targets, params.fleetSizes, raze, shortLanding);
			// the landing's arms come with its marines: pooled the same way, and short
			// of them the siege waits as it does for marines. Marines alone were
			// pooled, so fronts landed with 161 of 600 arms and were overrun (rc1 logs)
			String arms = ThreatReserves.COMMODITIES[1];
			if (!faction.isPlayerFaction() && wants[1] > 0f) {
				float haveArms = siegePooled(base, pool, arms);
				float minArms = wants[1] * minMarinesFraction(faction);
				if (haveArms < minArms) {
					ThreatIncConfig.logQuiet("postpone:" + base.getId() + ":" + system.getId(), "Expedition postponed at " + base.getName() + " against " + system.getName() + ": "
							+ (int) haveArms + " of " + (int) wants[1]
							+ " armaments available (need " + (int) minArms + ")");
					return null;
				}
			}
			drawn = new float[ThreatReserves.COMMODITIES.length];
			// marines: draw up to the tier's goal; the rest to the want - each
			// from the base first, then the donors nearest it (siegeDraw)
			float zealotsHeld = siegePooled(null, zealots, com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
			drawn[0] = siegeDraw(base, marinePool,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES, lift, "marines");
			float zealotsGave = zealotsHeld
					- siegePooled(null, zealots, com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
			if (zealotsGave >= 1f) {
				ThreatCoalition.report(Global.getSector().getFaction(Factions.LUDDIC_PATH), faction,
						"Zealots Join Siege", "sends %s marines to the siege of %s",
						Misc.getWithDGS((int) zealotsGave), system.getNameWithLowercaseTypeShort());
			}
			drawn[1] = siegeDraw(base, pool, arms, wants[1], "armaments");
			drawn[2] = siegeDraw(base, provisionPool,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, wants[2], "fuel");
			drawn[3] = siegeDraw(base, provisionPool,
					com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, wants[3], "supplies");
			ThreatIncConfig.log("Expedition draw at " + base.getName() + ": "
					+ (int) drawn[0] + "/" + (int) lift + " marines, "
					+ (int) drawn[1] + "/" + (int) wants[1] + " armaments, "
					+ (int) drawn[2] + "/" + (int) wants[2] + " fuel (passage " + (int) fuelParts[0]
					+ ", ordnance " + (int) fuelParts[1] + ", razing " + (int) fuelParts[2] + "), "
					+ (int) drawn[3] + "/" + (int) wants[3] + " supplies");
		}

		ThreatPurgeFGI purge = new ThreatPurgeFGI(params, playerCommissioned);
		if (drawn != null) {
			purge.setCargoAllotment(Math.round(drawn[0]), drawn[1]);
			// the fuel drawn pays the passage first, then the razing it was set
			// aside for; what is left is the bombardment's ordnance
			float passage = Math.min(drawn[2], fuelParts[0]);
			float razing = Math.min(drawn[2] - passage, fuelParts[2]);
			purge.setProvisions(passage, drawn[3]);
			purge.setOrdnance(drawn[2] - passage - razing, razing);
		}
		purge.setRazeWorlds(raze);
		Global.getSector().getIntelManager().addIntel(purge);
		getPurgeList().add(purge);
		// the draw changed what every other siege can pay for
		TARGETS_MEMO.clear();
		RAZE_MEMO.clear();
		if (faction.isPlayerFaction()) {
			ThreatAidCapacity.commitGroup(base, ThreatAidCapacity.expeditionPoints(params.fleetSizes),
					purge, (land.isEmpty() && !raze.isEmpty() ? "razing" : "purge") + " expedition against the "
							+ system.getNameWithLowercaseType());
		}
		// a mobilised faction's siege calls its allies to the door
		ThreatCoalition.post(faction, system);
		// stamp every targeted colony so siblings don't each trigger their own
		// duplicate purge of the same system while this one is in flight (a
		// commissioned expedition suppresses NPC duplication the same way)
		for (MarketAPI target : targets) {
			ThreatIncData.lastPurgeTimes().put(target.getId(),
					Global.getSector().getClock().getTimestamp());
		}
		return purge;
	}

	/**
	 * What one siege expedition from this base against this system draws
	 * from the base's reserve, in ThreatReserves.COMMODITIES order: marines
	 * (raid strength ~= marines, as vanilla's own player raids - the same
	 * figure siegeRaidStrNeeded sizes the flotilla against), heavy armaments
	 * (npcFrontSupplyDays of the biggest target's front upkeep), fuel (the
	 * passage, the ordnance and any razing - expeditionFuel) and supplies
	 * (fleet points). Shared by the launch and the convoy planner, so what a
	 * staging base stocks is exactly what an expedition will take. The worlds
	 * it razes are the base's faction's call (razeWorlds).
	 */
	public static float[] expeditionWants(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> fleetSizes) {
		return expeditionWants(base, system, targets, fleetSizes,
				razeWorlds(base, base != null ? base.getFaction() : null, system, targets));
	}

	/** As above, razing {@code raze} (razeWorlds): no landing on those worlds, their razing fuel instead. */
	public static float[] expeditionWants(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> fleetSizes, java.util.Set<String> raze) {
		return expeditionWants(base, system, targets, fleetSizes, raze, 0f);
	}

	/** As above for a siege short of troops that lands {@code shortLanding} after a longer bombardment (landsAfterSoftening; 0: the full landing). */
	public static float[] expeditionWants(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> fleetSizes, java.util.Set<String> raze,
			float shortLanding) {
		float marines = shortLanding > 0f ? shortLanding : siegeRaidStrNeeded(landTargets(targets, raze));
		// the landing force arms itself: npcFrontSupplyDays of its own burn
		float armaments = ThreatGroundFronts.landingSupply(marines,
				ThreatIncConfig.npcFrontSupplyDays());
		int points = 0;
		for (Integer size : fleetSizes) points += size;
		float[] fuel = expeditionFuel(base, system, targets, fleetSizes, raze, shortLanding);
		float supplies = points * ThreatIncConfig.expeditionSuppliesPerPoint();
		return new float[] {marines, armaments, fuel[0] + fuel[1] + fuel[2], supplies};
	}

	/**
	 * The fuel one siege expedition draws, {passage, ordnance, razing}
	 * (docs/suppression-balance.md v2: fuel is ordnance). The passage is
	 * expeditionPassage. The ordnance is the tactical bombardment of each world
	 * it lands on, flown until orbit has done what it can
	 * (ThreatGroundFronts.bombardPlan, never past siegeOrbitDays) by the fleet
	 * points of the flotilla that sails, at bombardFuelPerFPDay - none over a
	 * world a front already stands on, where its passes reinforce instead. The
	 * razing is what saturation must pour to destroy each world in
	 * {@code raze}. What is not burned comes home with the refund.
	 */
	public static float[] expeditionFuel(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> fleetSizes, java.util.Set<String> raze) {
		return expeditionFuel(base, system, targets, fleetSizes, raze, 0f);
	}

	/** As above for a siege short of troops: the ordnance of the longer bombardment its {@code shortLanding} needs (0: the full landing). */
	public static float[] expeditionFuel(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> fleetSizes, java.util.Set<String> raze,
			float shortLanding) {
		float fp = ThreatAidCapacity.expeditionPoints(fleetSizes);
		float ordnance = 0f;
		float razing = 0f;
		float arrival = razeArrivalDays(base, system);
		for (MarketAPI target : targets) {
			if (target == null) continue;
			if (raze != null && raze.contains(target.getId())) {
				razing += ThreatRazing.fuelToDestroyThrough(target, arrival);
			} else if (!ThreatGroundFronts.hasFront(target)) {
				ordnance += siegeOrdnance(target, fp, shortLanding);
			}
		}
		return new float[] {expeditionPassage(base, system, fleetSizes), ordnance, razing};
	}

	/** Fuel a flotilla of these sizes draws for its passage: fleet points x light-years x expeditionFuelPerPointLY. */
	public static float expeditionPassage(MarketAPI base, StarSystemAPI system, java.util.List<Integer> fleetSizes) {
		int points = 0;
		for (Integer size : fleetSizes) points += size;
		float dist = base != null && base.getStarSystem() != null && system != null
				? Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation()) : 0f;
		return points * dist * ThreatIncConfig.expeditionFuelPerPointLY();
	}

	/** Days before a siege from base reaches the system: the longest preparation (7-14 d) and the passage at the board's estimated speed. */
	public static float razeArrivalDays(MarketAPI base, StarSystemAPI system) {
		float ly = base != null && base.getStarSystem() != null && system != null
				? Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation()) : 0f;
		return 14f + (float) Math.ceil(ly / ThreatWarBoard.EST_LY_PER_DAY) + 1f;
	}

	/** Fuel the tactical bombardment of one world burns for a flotilla of fp: the days its plan runs (ThreatGroundFronts.bombardPlan) at bombardFuelPerDay. */
	public static float siegeOrdnance(MarketAPI world, float fp) {
		return siegeOrdnance(world, fp, 0f);
	}

	/** As above for a landing of {@code troops} (0: the plan's own), which bombards on while they could not hold. */
	public static float siegeOrdnance(MarketAPI world, float fp, float troops) {
		if (world == null || fp <= 0f) return 0f;
		return ThreatGroundFronts.bombardPlan(world, fp, ThreatIncConfig.siegeOrbitDays(), troops, true)[0]
				* ThreatGroundFronts.bombardFuelPerDay(fp);
	}

	/**
	 * SHORT OF TROOPS, SOFTEN FIRST (2026-09-28, run 4): whether {@code troops},
	 * fewer than the plan's landing, take every world in {@code land} once a
	 * flotilla of {@code fp} bombards on past the ships' worth
	 * (ThreatGroundFronts.bombardPlan) - the gain, the abort line and
	 * siegeOrbitDays still end it. A world a front already stands on is
	 * reinforced, not landed on.
	 */
	public static boolean landsAfterSoftening(java.util.List<MarketAPI> land, float fp, float troops) {
		if (land == null || land.isEmpty() || fp <= 0f || troops <= 0f) return false;
		for (MarketAPI target : land) {
			if (target == null || ThreatGroundFronts.hasFront(target)) continue;
			if (ThreatGroundFronts.bombardPlan(target, fp, ThreatIncConfig.siegeOrbitDays(), troops, true)[3] < 1f) {
				return false;
			}
		}
		return true;
	}

	/** The worlds of a siege it lands on: all but those it razes. */
	public static java.util.List<MarketAPI> landTargets(java.util.List<MarketAPI> targets, java.util.Set<String> raze) {
		if (raze == null || raze.isEmpty()) return targets;
		java.util.List<MarketAPI> land = new ArrayList<MarketAPI>();
		for (MarketAPI target : targets) {
			if (target != null && !raze.contains(target.getId())) land.add(target);
		}
		return land;
	}

	// ------------------------------------------------------------------
	// the raze task (docs/suppression-balance.md v2 section 8)
	// ------------------------------------------------------------------

	/** Base + faction + target ids -> {@link #razeWorlds}, for the clock instant in razeMemoStamp; not saved. */
	private static final java.util.Map<String, java.util.Set<String>> RAZE_MEMO =
			new java.util.HashMap<String, java.util.Set<String>>();
	private static long razeMemoStamp = Long.MIN_VALUE;

	/**
	 * THE RAZE TASK (docs/suppression-balance.md v2 section 8): the worlds of an
	 * NPC siege its navy razes from orbit instead of landing on. A razing costs
	 * the fuel saturation must pour to destroy the world
	 * (ThreatRazing.fuelToDestroyThrough), a landing the marines and heavy
	 * armaments it takes (siegeRaidStrNeeded, landingSupply) - both at vanilla's
	 * base prices - and a world is razed where that is the cheaper, a flotilla
	 * the base can field razes it and outlasts its guns (razeFleetPoints: the
	 * siege's fleets grow to that, and the razing is done within
	 * siegeOrbitDays), and the siege's reserve holds the fuel over that
	 * flotilla's passage (and over the rest of the siege's fuel, when it lands
	 * on other worlds too). Typically a conquest seed or a small hive. Never a
	 * world saturation cannot finish (a story-critical one stops short of its
	 * last level), one another faction's front stands on, the player's siege - the player razes by the Bombard order, or by hand - or
	 * any with npcRazeEnabled off. A world the faction's own front stands on is
	 * razed whenever the flotilla and the fuel allow, whatever the landing
	 * would cost: saturation finishes it faster than the push (2026-09-28) -
	 * unless the front takes the last stratum first (daysToLastStratum).
	 * Ids, in the targets' order: an earlier
	 * world's fuel is set aside, and its guns fought, before a later one is
	 * weighed. Memoised per clock instant.
	 */
	public static java.util.Set<String> razeWorlds(MarketAPI base, FactionAPI faction, StarSystemAPI system,
			java.util.List<MarketAPI> targets) {
		java.util.Set<String> raze = new java.util.LinkedHashSet<String>();
		if (base == null || faction == null || system == null || targets == null || targets.isEmpty()) return raze;
		if (faction.isPlayerFaction() || !ThreatWarState.isAtWar(faction)) return raze;
		if (!ThreatIncConfig.npcRazeEnabled()) return raze;
		long now = Global.getSector().getClock().getTimestamp();
		if (now != razeMemoStamp) {
			RAZE_MEMO.clear();
			razeMemoStamp = now;
		}
		StringBuilder key = new StringBuilder(base.getId()).append(':').append(faction.getId());
		for (MarketAPI target : targets) key.append('|').append(target != null ? target.getId() : "-");
		java.util.Set<String> memo = RAZE_MEMO.get(key.toString());
		if (memo != null) return new java.util.LinkedHashSet<String>(memo);
		boolean anyGarrisoned = anyTargetGarrisoned(targets);
		int difficulty = siegeDifficulty(base, faction, targets, anyGarrisoned);
		boolean heavyAssault = siegeHeavyAssault(faction, targets);
		// the siege's reserve: the base's fuel and, pooled, its donors'
		java.util.List<MarketAPI> pool = ThreatIncConfig.siegePoolProvisions()
				? siegeDonors(base, faction, system) : new ArrayList<MarketAPI>();
		float pooled = siegePooled(base, pool, com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL);
		float fuelPrice = basePrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL);
		float marinePrice = basePrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
		float armsPrice = basePrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.HAND_WEAPONS);
		// the razing fuel of the worlds already razed, set aside whole
		float setAside = 0f;
		float arrival = razeArrivalDays(base, system);
		for (MarketAPI target : targets) {
			if (target == null) continue;
			// a front of our own there: finish it by saturation whatever the
			// landing would cost - razing is faster than the push (2026-09-28);
			// anyone else's front stands on it, not ours to raze
			boolean ours = false;
			ThreatGroundFronts.GroundFront ownFront = null;
			if (ThreatGroundFronts.hasFront(target)) {
				ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(target.getId());
				if (front == null || !faction.getId().equals(ThreatGroundFronts.ownerOf(front))) continue;
				ours = true;
				ownFront = front;
			}
			int layers = ThreatRazing.enemyLayers(target);
			if (layers <= 0 || ThreatRazing.razeable(target) < layers) continue;
			float fuel = ThreatRazing.fuelToDestroyThrough(target, arrival);
			float marines = siegeRaidStrNeeded(java.util.Collections.singletonList(target));
			float arms = ThreatGroundFronts.landingSupply(marines, ThreatIncConfig.npcFrontSupplyDays());
			float razeCost = fuel * fuelPrice;
			float siegeCost = marines * marinePrice + arms * armsPrice;
			// the flotilla if it razed this world after those already razed, and
			// landed nowhere (the least that sails): the orbit's fleets, grown to
			// outlast the guns of every world it razes in turn (siegeFleetGoal)
			java.util.Set<String> with = new java.util.LinkedHashSet<String>(raze);
			with.add(target.getId());
			float survives = razeFleetPoints(razeTargets(targets, with));
			java.util.List<Integer> sizes = siegeFleetSizes(difficulty, anyGarrisoned, heavyAssault, targets,
					new ArrayList<MarketAPI>(), 0f, siegeFleetGoal(faction, targets, with));
			float fp = ThreatAidCapacity.expeditionPoints(sizes);
			// the days that flotilla saturates this world (capped at siegeOrbitDays)
			float days = ThreatGroundFronts.razePlan(target, fp, fuel)[0];
			// its front takes the last stratum before the flotilla could finish
			// the razing: nothing sails to race it (run 6, Beta Vigri I)
			float frontDays = ownFront != null ? ThreatGroundFronts.daysToLastStratum(ownFront, target) : -1f;
			boolean frontFirst = frontDays >= 0f && frontDays <= arrival + Math.max(1f, days);
			// the fuel the siege's reserve holds over that flotilla's passage
			// and the razings already set aside
			float spare = pooled - expeditionPassage(base, system, sizes) - setAside;
			String verdict;
			if (razeCost >= siegeCost && !ours) {
				verdict = "sieges - the landing is cheaper";
			} else if (frontFirst) {
				verdict = "leaves it to its front";
			} else if (fp < survives) {
				verdict = days >= ThreatIncConfig.siegeOrbitDays() - 0.5f
						? "sieges - the razing would outlast the siege"
						: "sieges - the guns would break the flotilla";
			} else if (fuel > spare) {
				verdict = ours ? "reinforces - short of fuel to finish it" : "sieges - short of fuel";
			} else {
				verdict = ours ? "razes - finishing its front" : "razes";
				raze.add(target.getId());
				setAside += fuel;
				spare -= fuel;
			}
			// every pass weighs every target from every base: logged when the verdict changes
			ThreatIncConfig.logOnChange("raze:" + base.getId() + ":" + target.getId(), verdict, "Raze or siege of "
					+ target.getName() + " (size " + target.getSize() + ") from " + base.getName() + ": razing "
					+ Misc.getWithDGS(Math.round(fuel)) + " fuel (" + Misc.getWithDGS(Math.round(razeCost))
					+ " cr, " + (int) days + " d at " + (int) fp + " FP, "
					+ (survives < Float.MAX_VALUE ? "outlasts the guns from " + (int) Math.ceil(survives) + " FP"
							: "no flotilla outlasts the guns")
					+ "), landing "
					+ Misc.getWithDGS(Math.round(marines)) + " marines + " + Misc.getWithDGS(Math.round(arms))
					+ " armaments (" + Misc.getWithDGS(Math.round(siegeCost)) + " cr), "
					+ Misc.getWithDGS(Math.round(Math.max(0f, spare))) + " fuel to spare - " + verdict
					+ (frontFirst ? " (its last stratum falls in " + (int) frontDays + " d, a razing lands in "
							+ (int) (arrival + days) + ")" : ""));
		}
		// the razing must not leave the rest of the siege short: with worlds
		// still to land on, the fleets carrying their landing sail too, and their
		// passage and ordnance come out of the same reserve. A razing that would
		// hold back a siege the reserve could pay for goes back to a siege, the
		// last one decided first - unless besieging it takes more fuel still
		if (!raze.isEmpty() && raze.size() < targets.size()) {
			java.util.List<String> order = new ArrayList<String>(raze);
			float total = siegeFuelTotal(base, faction, system, targets, difficulty, anyGarrisoned, heavyAssault,
					order);
			while (!order.isEmpty() && total > pooled) {
				java.util.List<String> fewer = new ArrayList<String>(order.subList(0, order.size() - 1));
				float less = siegeFuelTotal(base, faction, system, targets, difficulty, anyGarrisoned, heavyAssault,
						fewer);
				if (less >= total) break;
				String back = order.get(order.size() - 1);
				MarketAPI world = Global.getSector().getEconomy().getMarket(back);
				ThreatIncConfig.logQuiet("razeback:" + base.getId() + ":" + back, "Raze or siege of "
						+ (world != null ? world.getName() : back) + " from " + base.getName()
						+ ": sieges - razing it leaves the siege short, " + Misc.getWithDGS(Math.round(total))
						+ " fuel wanted in all, " + Misc.getWithDGS(Math.round(pooled)) + " held");
				order = fewer;
				total = less;
			}
			raze = new java.util.LinkedHashSet<String>(order);
		}
		RAZE_MEMO.put(key.toString(), new java.util.LinkedHashSet<String>(raze));
		return raze;
	}

	/** All the fuel a siege of these worlds razing {@code raze} draws: its flotilla sized for the rest's landing and the razing's guns (siegeFleetGoal), then expeditionFuel. */
	protected static float siegeFuelTotal(MarketAPI base, FactionAPI faction, StarSystemAPI system,
			java.util.List<MarketAPI> targets, int difficulty, boolean anyGarrisoned, boolean heavyAssault,
			java.util.Collection<String> raze) {
		java.util.Set<String> set = new java.util.LinkedHashSet<String>(raze);
		java.util.List<Integer> sizes = siegeFleetSizes(difficulty, anyGarrisoned, heavyAssault, targets,
				landTargets(targets, set), 0f, siegeFleetGoal(faction, targets, set));
		float[] fuel = expeditionFuel(base, system, targets, sizes, set);
		return fuel[0] + fuel[1] + fuel[2];
	}

	/** The worlds of a siege it razes, in the targets' order: the order the expedition razes them in. */
	public static java.util.List<MarketAPI> razeTargets(java.util.List<MarketAPI> targets,
			java.util.Collection<String> raze) {
		java.util.List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (targets == null || raze == null || raze.isEmpty()) return out;
		for (MarketAPI target : targets) {
			if (target != null && raze.contains(target.getId())) out.add(target);
		}
		return out;
	}

	/**
	 * THE fleet points a siege of these worlds sails with at least: what takes
	 * their orbit (siegeOrbitNeeded) and what razes every world in {@code raze}
	 * and outlasts its guns (razeFleetPoints), whichever is more - the goal
	 * siegeFleetSizes grows the flotilla to, and the fleets an NPC launch will
	 * not trim. Every sizing of a flotilla with a raze set reads it
	 * (siegeSizesFor, razeWorlds, siegeFuelTotal, siegeCanPay, the launch, the
	 * Bombard order), so none can disagree with another. Float.MAX_VALUE when
	 * no flotilla outlasts the guns.
	 */
	public static float siegeFleetGoal(FactionAPI faction, java.util.List<MarketAPI> targets,
			java.util.Collection<String> raze) {
		float goal = siegeOrbitNeeded(faction, targets);
		if (raze == null || raze.isEmpty()) return goal;
		return Math.max(goal, razeFleetPoints(razeTargets(targets, raze)));
	}

	/** World ids in razing order -> {@link #razeFleetPoints}, for the clock instant in razeFPMemoStamp; not saved. */
	private static final java.util.Map<String, Float> RAZE_FP_MEMO = new java.util.HashMap<String, Float>();
	private static long razeFPMemoStamp = Long.MIN_VALUE;

	/**
	 * The least fleet points that raze these worlds in turn and outlast their
	 * guns (docs/suppression-balance.md v2 sections 4 and 8): with each world's
	 * razing fuel aboard (ThreatRazing.fuelToDestroyThrough), the flotilla
	 * finishes every one within siegeOrbitDays (ThreatGroundFronts.razePlan) and
	 * never falls below vanilla's abort line, GROUP_ABORT_FRACTION of what set
	 * out, the losses over one world carried to the next (razeRun). The guns
	 * fire at their own rate whatever the fleet, so a fleet too small is sunk
	 * before the fuel is poured - 200 FP over a size-4 hive's Heavy Batteries
	 * lasts about five days - and one this size loses about two thirds of
	 * itself doing it. Found by bisection, as siegeRaidStrNeeded is; the
	 * flotilla rounds it up in whole fleets (siegeFleetSizes). Float.MAX_VALUE
	 * when not even the largest a siege can field - siegeMaxFleets fleets of
	 * the top difficulty - does; 0 with nothing to raze. Memoised per clock
	 * instant.
	 */
	public static float razeFleetPoints(java.util.List<MarketAPI> worlds) {
		if (worlds == null || worlds.isEmpty()) return 0f;
		long now = Global.getSector().getClock().getTimestamp();
		if (now != razeFPMemoStamp) {
			RAZE_FP_MEMO.clear();
			razeFPMemoStamp = now;
		}
		StringBuilder key = new StringBuilder();
		for (MarketAPI world : worlds) key.append(world != null ? world.getId() : "-").append('|');
		Float memo = RAZE_FP_MEMO.get(key.toString());
		if (memo != null) return memo;
		float hi = Math.max(1, ThreatIncConfig.siegeMaxFleets()) * 10f * FP_PER_RESPONSE_DIFFICULTY;
		float need = Float.MAX_VALUE;
		if (razesAll(worlds, hi)) {
			// more fleet points only ever help: the guns fire at their own
			// rate, and a bigger fleet pours faster and silences them sooner
			float lo = 0f;
			for (int i = 0; i < 24 && hi - lo > 1f; i++) {
				float mid = (lo + hi) / 2f;
				if (razesAll(worlds, mid)) {
					hi = mid;
				} else {
					lo = mid;
				}
			}
			need = hi;
		}
		RAZE_FP_MEMO.put(key.toString(), need);
		return need;
	}

	/** Whether a flotilla of fp razes every one of these worlds in turn, each with its own razing fuel aboard, above the abort line throughout (razeRun). */
	protected static boolean razesAll(java.util.List<MarketAPI> worlds, float fp) {
		for (float[] world : razeRun(worlds, fp, -1f)) {
			if (world[3] < 1f) return false;
		}
		return true;
	}

	/**
	 * The razing of these worlds in turn by one flotilla of fp fleet points, as
	 * its expedition flies it: ThreatGroundFronts.razePlan a world at a time,
	 * each on the fleet points and the fuel the last one left. Per world {days,
	 * fuel spent, fleet points lost, 1 when razed, 1 when reached, fuel aboard
	 * on arrival}. {@code fuel} is what it carries for all of them; negative,
	 * each world has its own razing fuel aboard - the sizing's case. The losses
	 * run against one abort line, GROUP_ABORT_FRACTION of what set out: a world
	 * finished only below it is not razed, and once the guns have turned the
	 * fleets for home they reach nothing more. A razing that runs out of days
	 * moves on to the next world, as the expedition's stage does.
	 */
	public static float[][] razeRun(java.util.List<MarketAPI> worlds, float fp, float fuel) {
		float[][] out = new float[worlds.size()][6];
		float start = Math.max(0f, fp);
		float floor = start * ThreatGroundFronts.GROUP_ABORT_FRACTION;
		float budget = ThreatIncConfig.siegeOrbitDays();
		float fleet = start;
		float left = Math.max(0f, fuel);
		boolean turned = false;
		for (int i = 0; i < worlds.size(); i++) {
			MarketAPI world = worlds.get(i);
			if (world == null || turned || fleet <= 0f || fleet < floor) continue;
			float aboard = fuel < 0f ? ThreatRazing.fuelToDestroyThrough(world) : left;
			float[] plan = ThreatGroundFronts.razePlan(world, fleet, aboard, floor);
			boolean finished = plan[3] >= 1f;
			out[i][0] = plan[0];
			out[i][1] = plan[1];
			out[i][2] = fleet - plan[2];
			out[i][3] = finished && plan[2] >= floor ? 1f : 0f;
			out[i][4] = 1f;
			out[i][5] = aboard;
			// stopped short with days and fuel left: the guns stopped it, and
			// the expedition turns for home
			if (!finished && plan[0] < budget - 0.5f && aboard - plan[1] >= 1f) turned = true;
			if (plan[2] < floor) turned = true;
			fleet = plan[2];
			if (fuel >= 0f) left = Math.max(0f, left - plan[1]);
		}
		return out;
	}

	/** A commodity's vanilla base price (credits a unit): what the raze task weighs a razing against a landing by. */
	protected static float basePrice(String commodityId) {
		com.fs.starfarer.api.campaign.econ.CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(commodityId);
		return spec != null ? spec.getBasePrice() : 0f;
	}

	/** The worlds' names for a notice: one in its owner's colour, several joined. */
	protected static ThreatNotice.Hl worldNames(java.util.List<MarketAPI> worlds) {
		if (worlds.size() == 1) return ThreatNotice.market(worlds.get(0));
		java.util.List<String> names = new ArrayList<String>();
		for (MarketAPI world : worlds) names.add(world.getName());
		return ThreatNotice.hl(Misc.getAndJoined(names));
	}

	/**
	 * What the board's Siege button would launch from this base against this
	 * system, by the same path ThreatFactionView.executeOrder takes: the
	 * targets, a difficulty sized to the job, the flotilla trimmed to a
	 * player base's free capacity, and that flotilla's draw in
	 * ThreatReserves.COMMODITIES order. All zeros with nothing to besiege.
	 */
	public static float[] siegeWants(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		java.util.List<MarketAPI> targets = siegeTargets(base, faction, system);
		if (base == null || faction == null || targets.isEmpty()) return new float[] {0f, 0f, 0f, 0f};
		return expeditionWants(base, system, targets, siegeSizesFor(base, faction, targets, 0f),
				razeWorlds(base, faction, system, targets));
	}

	/**
	 * The flotilla the board's Siege button would sail from this base
	 * against this system: sized to the job, trimmed to a player base's free
	 * points (ThreatAidCapacity.fitExpedition). Empty with nothing to besiege.
	 */
	public static java.util.List<Integer> siegeSizes(MarketAPI base, FactionAPI faction,
			StarSystemAPI system) {
		return siegeSizes(base, faction, system, 0f);
	}

	/**
	 * As {@link #siegeSizes(MarketAPI, FactionAPI, StarSystemAPI)}, sized to land
	 * at least {@code marineGoal} (0 = the need) before the free-points trim.
	 * THE sizing of a siege from a base: the NPC launch (tryPurgeBombardments),
	 * the hunting gate (hasSiegeableHive), the convoy planner (ThreatConvoys.stagingTargets)
	 * and the board's quotes all read it, so none can disagree with another.
	 */
	public static java.util.List<Integer> siegeSizes(MarketAPI base, FactionAPI faction,
			StarSystemAPI system, float marineGoal) {
		return siegeSizesFor(base, faction, siegeTargets(base, faction, system), marineGoal);
	}

	/**
	 * As {@link #siegeSizes(MarketAPI, FactionAPI, StarSystemAPI, float)}, against
	 * these worlds - its landing sized for the worlds it lands on, and its
	 * fleet points for the orbit and the guns of those it razes (razeWorlds,
	 * siegeFleetGoal).
	 */
	public static java.util.List<Integer> siegeSizesFor(MarketAPI base, FactionAPI faction,
			java.util.List<MarketAPI> targets, float marineGoal) {
		if (base == null || faction == null || targets.isEmpty()) return new java.util.ArrayList<Integer>();
		boolean anyGarrisoned = anyTargetGarrisoned(targets);
		int difficulty = siegeDifficulty(base, faction, targets, anyGarrisoned);
		java.util.Set<String> raze = razeWorlds(base, faction, targets.get(0).getStarSystem(), targets);
		java.util.List<Integer> sizes = siegeFleetSizes(difficulty, anyGarrisoned,
				siegeHeavyAssault(faction, targets), targets, landTargets(targets, raze), marineGoal,
				siegeFleetGoal(faction, targets, raze));
		return ThreatAidCapacity.fitExpedition(base, faction, sizes, false);
	}

	/**
	 * Why the board's Siege order would raise nothing now, or null if it
	 * would sail: the gates launchSiegeExpedition applies to the PLAYER's own
	 * sieges (a base must have the free fleet points for the smallest
	 * flotilla, staged task forces included; in war mode it must commit
	 * expeditionMinMarinesFraction of the landing's marines), so the button
	 * greys out instead of the order failing after Confirm. The board orders
	 * only the player's sieges (ThreatFleetOrders.canPlayerOrder); an NPC's
	 * orbit and provisions gates live in the launch alone.
	 */
	public static String siegeBlockReason(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		return ThreatNotice.text(siegeBlockFacts(base, faction, system));
	}

	/** {@link #siegeBlockReason} one fact per line, the names and figures highlighted: the refusal notice's. */
	public static ThreatNotice.Reason siegeBlockFacts(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		if (base == null) {
			return ThreatNotice.Reason.of(faction != null && faction.isPlayerFaction()
					? "No military colony of yours has a Waystation" : "No base in reach");
		}
		if (system == null || collectSiegeTargets(system).isEmpty()) {
			return ThreatNotice.Reason.of("Nothing there to besiege yet");
		}
		if (faction == null) return null;
		if (faction.isPlayerFaction() && ThreatAidCapacity.enabled()) {
			float points = ThreatAidCapacity.expeditionPoints(siegeSizes(base, faction, system));
			float free = ThreatAidCapacity.freeFP(base);
			if (points > free) {
				return ThreatNotice.Reason.of("%s has %s FP free", ThreatNotice.market(base),
						(int) Math.max(0f, free))
						.line("The smallest expedition needs %s", (int) Math.ceil(points));
			}
		}
		if (!ThreatWarState.isAtWar(faction)) return null;
		float[] wants = siegeWants(base, faction, system);
		float have = ThreatReserves.available(base,
				com.fs.starfarer.api.impl.campaign.ids.Commodities.MARINES);
		float min = wants[0] * minMarinesFraction(faction);
		if (wants[0] > 0f && have < min) {
			return ThreatNotice.Reason.of("%s can commit %s marines", ThreatNotice.market(base),
					Misc.getWithDGS((int) have))
					.line("The landing needs at least %s of the %s it wants",
							Misc.getWithDGS((int) Math.ceil(min)), Misc.getWithDGS((int) wants[0]));
		}
		return null;
	}

	// ------------------------------------------------------------------
	// the player's Bombard order (docs/suppression-balance.md v2 section 9)
	// ------------------------------------------------------------------

	/**
	 * The worlds the board's Bombard order razes in this system: every hive
	 * saturation can still take (ThreatRazing.razeable), bar one a ground front
	 * stands on - saturation over a front is the in-person menu's, which pays
	 * danger close.
	 */
	public static java.util.List<MarketAPI> bombardTargets(StarSystemAPI system) {
		java.util.List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (system == null) return out;
		for (MarketAPI target : collectSiegeTargets(system)) {
			if (ThreatGroundFronts.hasFront(target) || ThreatRazing.razeable(target) <= 0) continue;
			out.add(target);
		}
		return out;
	}

	/** The worlds' ids in order: the raze set of an expedition that razes every one of them. */
	public static java.util.Set<String> idsOf(java.util.List<MarketAPI> worlds) {
		java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
		for (MarketAPI world : worlds) {
			if (world != null) ids.add(world.getId());
		}
		return ids;
	}

	/**
	 * The flotilla the board's Bombard order asks for against these worlds: a
	 * siege's (siegeFleetSizes) with no landing, grown to raze every one and
	 * outlast its guns (siegeFleetGoal). The launch fits it to the base's free
	 * points (ThreatAidCapacity.fitExpedition), as it does a Siege's.
	 */
	public static java.util.List<Integer> bombardFleetSizes(MarketAPI base, FactionAPI faction,
			java.util.List<MarketAPI> targets) {
		if (targets == null || targets.isEmpty()) return new ArrayList<Integer>();
		boolean anyGarrisoned = anyTargetGarrisoned(targets);
		return siegeFleetSizes(siegeDifficulty(base, faction, targets, anyGarrisoned), anyGarrisoned,
				siegeHeavyAssault(faction, targets), targets, new ArrayList<MarketAPI>(), 0f,
				siegeFleetGoal(faction, targets, idsOf(targets)));
	}

	/** As {@link #bombardFleetSizes}, fitted to the base's free points without a log line: the board's quotes. */
	public static java.util.List<Integer> bombardSizes(MarketAPI base, FactionAPI faction,
			java.util.List<MarketAPI> targets) {
		if (base == null || faction == null) return new ArrayList<Integer>();
		return ThreatAidCapacity.fitExpedition(base, faction, bombardFleetSizes(base, faction, targets), false);
	}

	/**
	 * The fuel an expedition of these fleets would carry, drawn as the launch
	 * draws the player's - from the base's own reserve, the passage first, then
	 * the razing, then the ordnance: {passage, ordnance carried, ordnance
	 * wanted, razing carried, razing wanted, drawn in all, the reserve it is
	 * drawn from}.
	 */
	public static float[] expeditionFuelCarried(MarketAPI base, StarSystemAPI system,
			java.util.List<MarketAPI> targets, java.util.List<Integer> sizes, java.util.Set<String> raze) {
		float[] want = expeditionFuel(base, system, targets, sizes, raze);
		float have = ThreatReserves.available(base, com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL);
		float drawn = Math.min(have, want[0] + want[1] + want[2]);
		float passage = Math.min(drawn, want[0]);
		float razing = Math.min(drawn - passage, want[2]);
		return new float[] {passage, drawn - passage - razing, want[1], razing, want[2], drawn, have};
	}

	/**
	 * Why the board's Bombard order would raise nothing now, or null if it
	 * would sail: the Siege's gates that apply to a razing (a base, the free
	 * fleet points for the smallest flotilla), a hive to raze, and fuel in the
	 * base's reserve past the passage - short of the whole razing's, it sails
	 * with what there is.
	 */
	public static String bombardBlockReason(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		return ThreatNotice.text(bombardBlockFacts(base, faction, system));
	}

	/** {@link #bombardBlockReason} one fact per line, the names and figures highlighted: the refusal notice's. */
	public static ThreatNotice.Reason bombardBlockFacts(MarketAPI base, FactionAPI faction, StarSystemAPI system) {
		if (base == null) {
			return ThreatNotice.Reason.of(faction != null && faction.isPlayerFaction()
					? "No military colony of yours has a Waystation" : "No base in reach");
		}
		java.util.List<MarketAPI> all = system != null ? collectSiegeTargets(system) : new ArrayList<MarketAPI>();
		if (all.isEmpty()) return ThreatNotice.Reason.of("Nothing there to raze yet");
		java.util.List<MarketAPI> targets = bombardTargets(system);
		if (targets.isEmpty()) {
			boolean fronted = true;
			for (MarketAPI hive : all) {
				if (!ThreatGroundFronts.hasFront(hive)) fronted = false;
			}
			return ThreatNotice.Reason.of(fronted ? "A ground front stands on every hive there"
					: "Nothing there to raze");
		}
		if (faction == null) return null;
		java.util.List<Integer> sizes = bombardSizes(base, faction, targets);
		if (faction.isPlayerFaction() && ThreatAidCapacity.enabled()) {
			float points = ThreatAidCapacity.expeditionPoints(sizes);
			float free = ThreatAidCapacity.freeFP(base);
			if (points > free) {
				return ThreatNotice.Reason.of("%s has %s FP free", ThreatNotice.market(base),
						(int) Math.max(0f, free))
						.line("The smallest expedition needs %s", (int) Math.ceil(points));
			}
		}
		float passage = expeditionPassage(base, system, sizes);
		float have = ThreatReserves.available(base, com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL);
		if (have - passage < 1f) {
			return ThreatNotice.Reason.of("%s can commit %s fuel", ThreatNotice.market(base),
					Misc.getWithDGS((int) have))
					.line("The passage takes %s", Misc.getWithDGS((int) Math.ceil(passage)));
		}
		return null;
	}

	// ------------------------------------------------------------------
	// siege sizing (NPC sieges and the board's Siege order alike)
	// ------------------------------------------------------------------

	/**
	 * The quality of each fleet in a siege from this base. An NPC navy sends
	 * what it has: its strength at the BASE's system (the hive system is enemy
	 * territory where it has none - see dispatchFactionResponse), in the
	 * response difficulty band. The player's own siege is sized to the job
	 * (computeSiegeDifficulty). The defenses set how many fleets there are
	 * either way (siegeFleetSizes).
	 */
	public static int siegeDifficulty(MarketAPI base, FactionAPI faction,
			java.util.List<MarketAPI> targets, boolean anyGarrisoned) {
		if (faction == null || faction.isPlayerFaction() || base == null) {
			return computeSiegeDifficulty(targets, anyGarrisoned);
		}
		float strength = siegeStrength(base, faction);
		int difficulty = ThreatIncConfig.responseMinDifficulty()
				+ Math.round(strength / ThreatIncConfig.responseStrengthDivisor());
		if (difficulty > ThreatIncConfig.responseMaxDifficulty()) {
			difficulty = ThreatIncConfig.responseMaxDifficulty();
		}
		return difficulty;
	}

	/** One read of a base's navy strength, when it was taken, and for which owner. */
	protected static class StrengthRead {
		float strength;
		long at;
		String factionId;
	}

	/** Base market id -> its siege strength read; not saved. A base that changed hands reads afresh. */
	protected static final java.util.Map<String, StrengthRead> SIEGE_STRENGTH = new java.util.HashMap<String, StrengthRead>();

	/**
	 * The faction's strength at the base's system, read once per strategy tick.
	 * Read live, the same base's 25-fleet flotilla swung between ~3,550 and ~6,150
	 * FP from one tick to the next on patrol traffic, and the orbit gate, the
	 * hunting bar, the bounty and the staging targets flipped with it (rc1
	 * review): now every one of them reads the same figure for a month.
	 */
	protected static float siegeStrength(MarketAPI base, FactionAPI faction) {
		long now = Global.getSector().getClock().getTimestamp();
		StrengthRead read = SIEGE_STRENGTH.get(base.getId());
		if (read != null && faction.getId().equals(read.factionId)) {
			float age = Global.getSector().getClock().getElapsedDaysSince(read.at);
			if (age >= 0f && age < Math.max(1f, ThreatIncConfig.tickDays())) return read.strength;
		}
		read = new StrengthRead();
		read.strength = WarSimScript.getFactionStrength(faction, base.getStarSystem());
		read.at = now;
		read.factionId = faction.getId();
		SIEGE_STRENGTH.put(base.getId(), read);
		return read.strength;
	}

	/**
	 * Whether an NPC siege of these targets is a full assault - the expedition
	 * purges the SYSTEM, so a defended hive there too big to stomp
	 * preemptively (purgePreemptMaxSize) earns the extra escort whichever
	 * colony's cooldown triggered the launch. The player's flotilla never adds
	 * one; its tiers grow it instead (siegeMarineGoal).
	 */
	public static boolean siegeHeavyAssault(FactionAPI faction, java.util.List<MarketAPI> targets) {
		if (faction == null || faction.isPlayerFaction()) return false;
		for (MarketAPI target : targets) {
			if (target == null) continue;
			if (ThreatColonyManager.countLiveGarrison(target.getId()) > 0
					&& target.getSize() > ThreatIncConfig.purgePreemptMaxSize()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The player's siege is sized to the JOB, not to a navy: the biggest
	 * colony in the target system sets the tempo, live Defense Swarms add a
	 * point, clamped to the response difficulty band.
	 */
	public static int computeSiegeDifficulty(java.util.List<MarketAPI> targets,
			boolean anyGarrisoned) {
		int maxSize = 0;
		for (MarketAPI target : targets) {
			if (target.getSize() > maxSize) maxSize = target.getSize();
		}
		int difficulty = 3 + maxSize + (anyGarrisoned ? 1 : 0);
		if (difficulty < ThreatIncConfig.responseMinDifficulty()) {
			difficulty = ThreatIncConfig.responseMinDifficulty();
		}
		if (difficulty > ThreatIncConfig.responseMaxDifficulty()) {
			difficulty = ThreatIncConfig.responseMaxDifficulty();
		}
		return difficulty;
	}

	// ------------------------------------------------------------------
	// deciv listener: the swarm claims what it kills
	// ------------------------------------------------------------------

	public void reportColonyAboutToBeDecivilized(MarketAPI market, boolean fullyDestroyed) {
	}

	public void reportColonyDecivilized(MarketAPI market, boolean fullyDestroyed) {
		if (market == null) return;
		// our own colony dying is handled by the colony poll
		if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) return;
		if (!ThreatIncConfig.convertDecivWorlds()) return;
		if (!(market.getPrimaryEntity() instanceof PlanetAPI)) return;

		// cause can't be checked here: the RECENTLY_BOMBARDED flag is set after
		// the deciv listeners fire - confirm on the next poll instead
		String planetId = market.getPrimaryEntity().getId();
		if (!ThreatIncData.pendingDecivChecks().contains(planetId)) {
			ThreatIncData.pendingDecivChecks().add(planetId);
		}
	}

	// ------------------------------------------------------------------
	// hostile-act listener: exterminating the swarm is not a war crime
	// ------------------------------------------------------------------

	public void reportRaidForValuablesFinishedBeforeCargoShown(InteractionDialogAPI dialog,
			MarketAPI market, TempData actionData, CargoAPI cargo) {
	}

	/**
	 * Raiding a hive world costs marines beyond what its raw ground-defense
	 * number says: the counter-swarms contest every corridor. Shows up as a
	 * named line in the raid screen's losses breakdown.
	 */
	public void modifyMarineLossesStatPreRaid(MarketAPI market,
			java.util.List<com.fs.starfarer.api.impl.campaign.graid.GroundRaidObjectivePlugin> objectives,
			com.fs.starfarer.api.combat.MutableStat stat) {
		if (!ThreatIncConfig.enabled() || market == null) return;
		if (!Factions.THREAT.equals(market.getFactionId())) return;
		float mult = ThreatIncConfig.hiveMarineLossMult();
		if (mult == 1f) return;
		stat.modifyMult("threatinc_hive", mult, "Hive counter-swarms");
	}

	public void reportRaidToDisruptFinished(InteractionDialogAPI dialog,
			MarketAPI market, TempData actionData, Industry industry) {
		// raiding the FORGE (fabrication) or the SWARM NEXUS (staging) kills a
		// preparing expedition; raiding any other hive industry is not credited
		if (!ThreatIncConfig.enabled() || market == null || industry == null) return;
		if (!Factions.THREAT.equals(market.getFactionId())) return;
		ThreatAlarm.add(Factions.PLAYER, ThreatIncConfig.alarmPerRaid(),
				"raid on " + market.getName());
		if (ThreatColonyManager.SWARM_NEXUS.equals(industry.getId())) {
			abortStrikesFrom(market.getId(), market.getName(), "the raid on its Swarm Nexus");
			return;
		}
		Industry forge = ThreatColonyManager.getForge(market);
		if (forge == null || !forge.getId().equals(industry.getId())) return;
		abortStrikesFrom(market.getId(), market.getName(), "the raid on its forge");
	}

	public void reportTacticalBombardmentFinished(InteractionDialogAPI dialog,
			MarketAPI market, TempData actionData) {
		// a bombardment disrupts every surface industry, the forge included
		if (!ThreatIncConfig.enabled() || market == null) return;
		if (!Factions.THREAT.equals(market.getFactionId())) return;
		ThreatAlarm.add(Factions.PLAYER, ThreatIncConfig.alarmPerRaid(),
				"tactical bombardment of " + market.getName());
		abortStrikesFrom(market.getId(), market.getName(), "the bombardment");
		// recompute defenses now so the freshly-suppressed value is live at once,
		// not on the next colony poll (~half a day of stale ground strength).
		// ThreatincMarketCMD.bombardConfirm already reapplies on our own path;
		// this covers a bombardment routed through the vanilla fallback, which
		// sets disruption but never reapplies. reapply is idempotent.
		market.reapplyIndustries();
	}

	/**
	 * Undo the vanilla saturation-bombardment atrocity penalty when the colony
	 * bombed is a Threat colony. Vanilla {@code MarketCMD.bombardConfirm} has
	 * already dropped every "cares about atrocities" faction to (at worst)
	 * hostile by the time this fires, keyed purely off the witnessing faction -
	 * it never checks who owned the target. We restore each third-party faction
	 * to its pre-bombardment reputation (snapshotted every frame while no dialog
	 * is open) and re-pin the Threat itself to vengeful.
	 */
	public void reportSaturationBombardmentFinished(InteractionDialogAPI dialog,
			MarketAPI market, TempData actionData) {
		if (!ThreatIncConfig.enabled()) return;
		if (market == null || market.getFaction() == null) return;
		if (!Factions.THREAT.equals(market.getFaction().getId())) return; // only Threat colonies

		// strike recall first, independent of the atrocity-waiver setting. If the
		// bombardment decivilized the colony outright the faction is already
		// neutral and this is skipped - the colony poll catches that case.
		abortStrikesFrom(market.getId(), market.getName(), "the saturation bombardment");

		// recompute defenses now so the suppressed value is live immediately, not
		// on the next colony poll. threatSatConfirm already reapplies on our own
		// path; this covers the vanilla fallback, which never does. Idempotent.
		market.reapplyIndustries();

		if (!ThreatIncConfig.bombardNoAtrocity()) return;

		if (actionData != null && actionData.willBecomeHostile != null
				&& atrocityRepSnapshot != null) {
			for (FactionAPI fac : actionData.willBecomeHostile) {
				if (fac == null) continue;
				if (Factions.THREAT.equals(fac.getId())) continue; // stays vengeful, handled below
				Float pre = atrocityRepSnapshot.get(fac.getId());
				if (pre != null) {
					fac.setRelationship(Factions.PLAYER, pre);
				}
			}
		}

		// the swarm does not forgive being bombed; keep it perma-hostile
		enforceThreatHostility();
		ThreatIncConfig.log("Waived atrocity reputation penalty for bombing Threat colony "
				+ market.getName() + ".");
	}

	// ------------------------------------------------------------------
	// faction relations
	// ------------------------------------------------------------------

	/**
	 * Pin the Threat faction to rock-bottom vengeful with every real faction,
	 * including the player. Only rewrites a relationship that has drifted above
	 * the floor, so it fires no reputation-change events once settled.
	 */
	protected void enforceThreatHostility() {
		if (!ThreatIncConfig.permaHostile()) return;
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);
		if (threat == null) return;
		for (FactionAPI other : Global.getSector().getAllFactions()) {
			if (other == null || other == threat) continue;
			if (other.isNeutralFaction()) continue; // the pseudo "neutral" faction
			if (threat.getRelationship(other.getId()) > -1f) {
				threat.setRelationship(other.getId(), -1f); // -1 == deepest vengeful
			}
		}
	}

	/**
	 * Refresh the pre-bombardment reputation snapshot. Frozen while an
	 * interaction dialog is open, so the stored values are always the ones from
	 * before the player entered the bombardment menu.
	 */
	protected void updateAtrocityRepSnapshot() {
		if (Global.getSector().getCampaignUI() != null
				&& Global.getSector().getCampaignUI().getCurrentInteractionDialog() != null) {
			return; // freeze: keep the last pre-interaction values
		}
		if (atrocityRepSnapshot == null) atrocityRepSnapshot = new HashMap<String, Float>();
		for (FactionAPI fac : Global.getSector().getAllFactions()) {
			if (fac == null || fac.isPlayerFaction()) continue;
			if (!fac.getCustomBoolean(Factions.CUSTOM_CARES_ABOUT_ATROCITIES)) continue;
			atrocityRepSnapshot.put(fac.getId(), fac.getRelationship(Factions.PLAYER));
		}
	}

	protected void processPendingDecivChecks() {
		if (ThreatIncData.pendingDecivChecks().isEmpty()) return;
		for (String planetId : new ArrayList<String>(ThreatIncData.pendingDecivChecks())) {
			ThreatIncData.pendingDecivChecks().remove(planetId);

			SectorEntityToken entity = Global.getSector().getEntityById(planetId);
			if (!(entity instanceof PlanetAPI)) continue;
			MarketAPI market = ((PlanetAPI) entity).getMarket();
			if (market == null) continue;
			// a conquest already seeded a hive here: nothing to come back for
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			// the swarm's kill, either way it was made: a ground assault names
			// the Threat in the mod's own flag, a saturation pass in vanilla's
			boolean taken = Factions.THREAT.equals(market.getMemoryWithoutUpdate()
					.getString(ThreatGroundFronts.KILLED_BY_FLAG));
			boolean bombarded = Misc.flagHasReason(market.getMemoryWithoutUpdate(),
					MemFlags.RECENTLY_BOMBARDED, Factions.THREAT);
			if (!taken && !bombarded) continue;

			if (!ThreatIncData.decivTargets().contains(planetId)) {
				ThreatIncData.decivTargets().add(planetId);
				ThreatColonyManager.announce(ThreatNotice.titled("Colony Lost").bad()
						.line(taken ? "%s has fallen to the Threat ground assault."
								: "%s has fallen silent under Threat bombardment.",
								ThreatNotice.red(entity.getName()))
						.line("The swarm does not abandon its kills: expect them to come "
								+ "for the ruins."));
				ThreatIncConfig.log("Deciv conversion target: " + entity.getName());
			}
		}
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

	/**
	 * The swarm's home system: the deepest-fringe uninhabited system that meets
	 * the bare minimum to be self-sufficient (ore + rare ore + volatiles + enough
	 * planets). It crawls in from the Abyssal edge and takes root as far from the
	 * inhabited sector as it can while still standing up a full economy - not the
	 * richest core-adjacent prize, just the most remote viable one.
	 */
	protected StarSystemAPI pickOGSystem() {
		// pass 1: every viable full-chain system, and the deepest fringe distance
		List<StarSystemAPI> viable = new ArrayList<StarSystemAPI>();
		float maxDist = -1f;
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (!isValidSpreadCandidate(system)) continue;
			if (!ThreatColonyManager.canSupportFullChain(system)) continue;
			float d = distanceToNearestInhabited(system);
			if (d <= 0) continue;
			viable.add(system);
			if (d > maxDist) maxDist = d;
		}
		// pass 2: "far enough out" is a threshold, not a maximization - any
		// system in the outer half of the viable fringe qualifies, and the OG is
		// picked at random among the qualifiers so each incursion starts
		// somewhere different. (The outer-half bar always keeps at least the
		// deepest system, so this can't come up empty when anything is viable.)
		WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>(random);
		for (StarSystemAPI system : viable) {
			if (distanceToNearestInhabited(system) < maxDist * 0.5f) continue;
			picker.add(system, 1f);
		}
		return picker.pick();
	}

	protected StarSystemAPI pickSpreadTarget() {
		// what the hive is short of right now - systems whose deposits would
		// relieve those shortfalls get priority (their surplus feeds the whole
		// network via in-group trade)
		Map<String, Integer> needs = ThreatColonyManager.groupMineableDeficits();
		// a hive with no nominal colony doesn't stretch itself thinner: every
		// new world is another mouth on the same starved chain, so a strained
		// hive claims ONLY systems whose deposits would fix its economy
		boolean strainedHive = !ThreatColonyManager.anyNominalColony();

		WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>(random);
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (!isValidSpreadCandidate(system)) continue;

			// reachable = some stable forge colony has the fuel to actually
			// send a wave this far (fuel range enforced in pickForgeSource) -
			// the swarm creeps exactly as far as its fuel carries it
			if (ThreatColonyManager.pickForgeSource(system, true) == null) continue;

			float dInfested = distanceToNearestInfested(system);
			float dInhabited = distanceToNearestInhabited(system);
			if (dInfested < 0 || dInhabited < 0) continue;

			// the swarm ADVANCES: it hunts the sector's biomass and technology,
			// it doesn't colonize wilderness for its own sake. Strong pull
			// toward inhabited space (squared), mild preference for staying
			// near the existing network (short supply lines) - fuel range
			// already hard-limits the jump distance. And it colonizes with
			// PURPOSE: systems whose deposits relieve the hive's current
			// shortfalls weigh far heavier - a rare-starved hive lunges at
			// rare-ore worlds
			float need = ThreatColonyManager.systemNeedScore(system, needs);
			if (strainedHive && need <= 0f) continue;
			float w = (1f + need * 0.01f)
					/ ((1f + dInfested) * (1f + dInhabited * dInhabited));
			picker.add(system, w);
		}
		return picker.pick();
	}

	protected boolean isValidSpreadCandidate(StarSystemAPI system) {
		if (system == null || system.getCenter() == null) return false;
		if (ThreatIncData.stages().containsKey(system.getId())) return false;
		// spread claims uninhabited systems only; inhabited worlds get strikes
		// (and, if those kill them, conversion waves)
		if (!Misc.getMarketsInLocation(system).isEmpty()) return false;
		// a colony needs somewhere to dig in
		if (!ThreatColonyManager.systemHasColonizablePlanet(system)) return false;
		return true;
	}

	protected float distanceToNearestInhabited(StarSystemAPI system) {
		float best = -1f;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getStarSystem() == null) continue;
			if (ThreatMapFog.hidden(market)) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
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

	/**
	 * A mustered swarm's expedition size as the strike fields it: the
	 * strength multiplier up- or down-tiers the re-embodiment, clamped 3-9.
	 */
	public static int strikeFleetSize(int size) {
		float mult = ThreatIncConfig.strikeStrengthMult();
		int adjusted = size;
		if (mult >= 2f) adjusted += 2;
		else if (mult >= 1.25f) adjusted += 1;
		if (mult <= 0.5f) adjusted -= 2;
		else if (mult <= 0.8f) adjusted -= 1;
		return Math.max(3, Math.min(9, adjusted));
	}

	protected MarketAPI pickStrikeTarget(MarketAPI staging, StarSystemAPI source) {
		return pickStrikeTarget(staging, source, null);
	}

	/**
	 * RETALIATION (docs/design-theory.md 8.1): a ground victory draws an
	 * immediate strike at the winning faction from the nearest hive that can
	 * muster one and reach a world of theirs. Same rules as any strike
	 * (phase, concurrency cap, garrison surplus, reach, target filters), so it
	 * is a strike the swarm could have launched anyway - just aimed, now.
	 */
	public static boolean retaliate(String factionId, StarSystemAPI near) {
		if (instance == null || factionId == null || !ThreatIncConfig.retaliationEnabled()) return false;
		// the swarm does not avenge its own victories: a Threat ground front
		// winning on a human world is not a hive being lost
		if (Factions.THREAT.equals(factionId)) return false;
		if (!ThreatAlarm.enabled() || getPhase() < 2) return false;
		if (instance.countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) return false;
		MarketAPI bestColony = null;
		StarSystemAPI bestSource = null;
		MarketAPI bestTarget = null;
		float bestDist = Float.MAX_VALUE;
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			MarketAPI colony = ThreatColonyManager.pickStrikeStaging(systemId, true);
			if (colony == null) continue;
			StarSystemAPI source = instance.getSystem(systemId);
			if (source == null) continue;
			MarketAPI target = instance.pickStrikeTarget(colony, source, factionId);
			if (target == null) continue;
			float d = near != null ? Misc.getDistanceLY(source.getLocation(), near.getLocation()) : 0f;
			if (d < bestDist) {
				bestDist = d;
				bestColony = colony;
				bestSource = source;
				bestTarget = target;
			}
		}
		if (bestColony == null) return false;
		ThreatStrikeFGI strike = instance.launchStrike(bestColony, bestSource, bestTarget);
		// announced to the sector below, so it is seen from the start
		if (strike != null && !strike.isDetected()) {
			strike.markDetected("the swarm's announcement");
			instance.onStrikeDetected(strike);
		}
		if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(bestSource.getId());
		ThreatNotice n = ThreatNotice.titled("Swarm Retaliates").bad()
				.line("The swarm answers the loss of a hive.");
		if (ThreatScouts.sectorKnows(bestSource.getId())) {
			n.line("A Threat expedition is mustering at %s against %s.",
					ThreatNotice.market(bestColony), ThreatNotice.market(bestTarget));
		} else {
			n.line("A Threat expedition is mustering against %s.", ThreatNotice.market(bestTarget));
		}
		n.send();
		ThreatIncConfig.log("Retaliation: " + bestColony.getName() + " -> " + bestTarget.getName()
				+ " (" + factionId + ")");
		return true;
	}

	/**
	 * @param onlyFactionId restrict candidates to this faction's worlds (retaliation), or null
	 */
	protected MarketAPI pickStrikeTarget(MarketAPI staging, StarSystemAPI source, String onlyFactionId) {
		boolean coreAllowed = getPhase() >= 3;
		boolean playerAllowed = ThreatIncData.daysSincePlayerStruck() >= ThreatIncConfig.playerGraceDays();

		// reach is fuel: how far the staging colony can actually project, from
		// the fuel the hive network delivers to it (accessibility-mediated)
		float rangeLY = ThreatColonyManager.fuelRangeLY(staging);
		if (rangeLY <= 0f) return null;

		WeightedRandomPicker<MarketAPI> picker = new WeightedRandomPicker<MarketAPI>(random);
		// relief before offensives, the swarm's as the navies' (2026-09-29): a
		// front of its own losing ground in reach takes the strike
		WeightedRandomPicker<MarketAPI> relief = new WeightedRandomPicker<MarketAPI>(random);
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!isStrikeableWorld(market)) continue;
			// size 6+ markets are "core worlds" - phase 3 only
			if (!coreAllowed && isCoreWorld(market)) continue;
			if (market.isPlayerOwned() && !playerAllowed) continue;
			if (onlyFactionId != null && !onlyFactionId.equals(market.getFactionId())) continue;
			if (isActiveStrikeTarget(market)) continue; // one strike per world
			// the swarm strikes only what it has scouted (ThreatSwarmScouts)
			if (!ThreatSwarmScouts.swarmKnows(market)) continue;

			float d = Misc.getDistanceLY(source.getLocation(), market.getStarSystem().getLocation());
			if (d > rangeLY) continue;

			// the swarm hunts concentration: the bigger the world, the more
			// biomass and technology to erase - distance costs are already paid
			// in fuel (the range gate), so desirability is size alone
			float w = market.getSize() * market.getSize();
			// a frontline outpost is worth what hangs off it: the swarm goes
			// for the link whose loss cuts the most of the chain beyond
			// (docs/frontlines.md, "The Threat breaks the chain")
			if (ThreatFrontlines.isOutpost(market)) w = ThreatFrontlines.strikeWeight(market);
			// the swarm turns on whoever is hurting it (ThreatAlarm grudge)
			w *= ThreatAlarm.targetMult(market.getFactionId());
			// a dry front of the swarm's is signalling: the next expedition
			// answers it (2026-09-06)
			if (ThreatGroundFronts.wantsExpedition(market)) {
				w *= Math.max(1f, ThreatIncConfig.strikeReinforceWeight());
				if (ThreatIncConfig.strikeReliefFirst()) relief.add(market, w);
			}
			picker.add(market, w);
		}
		if (!relief.isEmpty()) return relief.pick();
		return picker.pick();
	}

	/**
	 * A world the swarm could ever send a strike at: inhabited, size 3+, not
	 * the hive's own, and killable. The engine refuses the killing blow on
	 * story-critical worlds, and an annihilation doctrine has no use for a
	 * target it cannot kill - unless the player has opted into breaking
	 * vanilla storylines. Shared by the target picker and the phase gate so
	 * "core worlds in reach" means a world a strike could actually go to.
	 */
	protected static boolean isStrikeableWorld(MarketAPI market) {
		if (market.getStarSystem() == null || market.getPrimaryEntity() == null) return false;
		if (ThreatMapFog.hidden(market)) return false;
		if (Factions.THREAT.equals(market.getFactionId())) return false;
		if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) return false;
		// frontline outposts start at size 1 and are targets from the first day
		if (market.getSize() < 3 && !ThreatFrontlines.isOutpost(market)) return false;
		if (!ThreatIncConfig.destroyStoryCritical() && Misc.isStoryCritical(market)) return false;
		return true;
	}

	/** Size 6+ is a core world: the strike budget it takes rivals core defenses. */
	protected static boolean isCoreWorld(MarketAPI market) {
		return market.getSize() >= 6;
	}

	/**
	 * Whether an armada-capable hive could actually reach a core world: some
	 * strikeable size-6+ market sits within its fuel range - the same gate
	 * pickStrikeTarget applies, so phase 3 is never announced while every
	 * core world is beyond what the hive's fuel buys.
	 */
	protected static boolean coreWorldInReach(MarketAPI staging) {
		StarSystemAPI source = staging.getStarSystem();
		if (source == null) return false;
		float rangeLY = ThreatColonyManager.fuelRangeLY(staging);
		if (rangeLY <= 0f) return false;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (!isCoreWorld(market) || !isStrikeableWorld(market)) continue;
			// pickStrikeTarget's own gate: a world its scouts have not charted is not in reach
			if (!ThreatSwarmScouts.swarmKnows(market)) continue;
			float d = Misc.getDistanceLY(source.getLocation(), market.getStarSystem().getLocation());
			if (d <= rangeLY) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// phases, bookkeeping, helpers
	// ------------------------------------------------------------------

	public static int getPhase() {
		if (!ThreatIncData.isStarted()) return 0;

		// phases are CAPABILITY, not calendar. Phase 2 the moment some colony
		// could actually stage a strike (strike-sized, forged, fueled); phase 3
		// when some colony can field a full-budget armada - core-world-sized
		// (its size-squared strike budget rivals core defenses) with a
		// near-nominal hull economy - AND a core world sits within its fuel
		// range (coreWorldInReach), so the label is literally true. Both can
		// REGRESS: burn their forges, cut their fuel, shrink their colonies,
		// and the sector's danger level genuinely drops - the escalation is
		// theirs to earn and yours to undo.
		boolean canStrike = false;
		boolean canStrikeCore = false;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (ThreatColonyManager.getForge(market) == null) continue;
			if (!ThreatColonyManager.hasOperationalFuel(market)) continue;
			if (!ThreatColonyManager.hasOperationalNexus(market)) continue;
			canStrike = true;
			if (isCoreWorld(market)
					&& ThreatColonyManager.shipSupplyMult(market)
							>= ThreatColonyManager.STABLE_SHIP_SUPPLY_MULT
					&& coreWorldInReach(market)) {
				canStrikeCore = true;
				break;
			}
		}
		if (canStrikeCore && ThreatIncConfig.phase3Enabled()) return 3;
		if (canStrike) return 2;
		return 1;
	}

	protected void checkPhaseAnnouncements() {
		if (getPhase() >= 3 && !ThreatIncData.isPhase3Announced()) {
			ThreatIncData.setPhase3Announced();
			ThreatNotice.titled("Core Worlds in Reach").bad()
					.line("Threat strike fleets have been sighted on approach vectors toward "
							+ "the %s.", "core worlds")
					.line("Nowhere in the sector is beyond their reach any longer.")
					.send();
		}
	}

	public static final String KEY_RESPONSES = "threatinc_activeResponses";

	@SuppressWarnings("unchecked")
	public static List<Object> getResponseList() {
		Object val = Global.getSector().getPersistentData().get(KEY_RESPONSES);
		if (!(val instanceof List)) {
			val = new ArrayList<Object>();
			Global.getSector().getPersistentData().put(KEY_RESPONSES, val);
		}
		return (List<Object>) val;
	}

	public static int countActiveResponses() {
		int count = 0;
		List<Object> list = getResponseList();
		List<Object> dead = new ArrayList<Object>();
		for (Object curr : list) {
			if (curr instanceof ThreatResponseIntel && ((ThreatResponseIntel) curr).isFleetActive()) {
				count++;
			} else {
				dead.add(curr);
			}
		}
		list.removeAll(dead);
		return count;
	}

	protected MarketAPI findResponseBase(FactionAPI faction, StarSystemAPI hiveSystem) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getFaction() != faction) continue;
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (!isBase(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), hiveSystem.getLocation());
			if (d > expeditionRangeLY(market)) continue;
			if (d < bestDist) {
				bestDist = d;
				best = market;
			}
		}
		return best;
	}

	/**
	 * How far a colony can send a task force or siege expedition: the fuel
	 * available there times strikeLYPerFuel - the same rule the hive's strikes
	 * run on (ThreatColonyManager.fuelRangeLY). A size-8 Hegemony world fed by
	 * Sindria reaches deep; a player colony capped at size 6 reaches what its
	 * own fuel supply buys; a world with no fuel sends nothing. Replaced the
	 * flat threatinc_responseRangeLY in Sept 2026 so both sides play by one
	 * rule.
	 */
	public static float expeditionRangeLY(MarketAPI base) {
		// one rule for every side: fuel available, capped by what the base's
		// fleets can carry (ThreatColonyManager.expeditionFuelCapacity)
		return ThreatColonyManager.fuelRangeLY(base);
	}

	/** A military structure: what fields fleets. Static so the mission board can ask the same question the purge logic does. */
	protected static boolean hasMilitary(MarketAPI market) {
		return market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.PATROLHQ)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.MILITARYBASE)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.HIGHCOMMAND);
	}

	/**
	 * A BASE: a military structure to field fleets AND a functional Waystation
	 * to stock and ship them (ThreatReserves.hasDepot). Every pick of a
	 * colony to sail from, stage at or ship to asks this; whether a faction
	 * is in reach of a hive at all (ThreatWarState.hiveInReach) asks only
	 * hasMilitary, since an NPC faction's mobilisation is what builds its
	 * Waystations.
	 */
	protected static boolean isBase(MarketAPI market) {
		return market != null && hasMilitary(market) && ThreatReserves.hasDepot(market);
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
		return countActiveFGIs(getStrikeList());
	}

	/**
	 * Whether an active strike is already aimed at this market. Two staging
	 * systems picking targets independently must not dogpile one world - the
	 * concurrency cap is meant to spread the pressure, not stack it.
	 */
	protected static boolean isActiveStrikeTarget(MarketAPI market) {
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding()) continue;
			if (strike.getParams() != null && strike.getParams().raidParams != null
					&& strike.getParams().raidParams.allowedTargets.contains(market)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether this colony is the staging world of a strike currently in flight
	 * (launchStrike sets params.source to the staging market). The immediate
	 * mission board treats such a world as the sector's problem RIGHT NOW - see
	 * {@link ThreatMissionIntel#immediateThreatMult}.
	 */
	public static boolean isActiveStrikeSource(MarketAPI market) {
		if (market == null) return false;
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding()) continue;
			// unseen (ThreatStrikeFGI.undetected): the mission board reads this
			if (strike.isHidden()) continue;
			if (strike.getParams() != null && strike.getParams().source != null
					&& market.getId().equals(strike.getParams().source.getId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Bombardment passes an expedition may deliver PER WORLD. Always one under
	 * the sweep doctrine (every world in the target system, each at most once);
	 * kept as a method because {@link #upgradeInFlightStrikes} uses it to clamp
	 * strikes launched under earlier, heavier doctrines.
	 */
	public static int strikeSatPasses(int stagingSize) {
		return 1;
	}

	/**
	 * Fleets of a strike currently PREPARING at this colony - the mustered
	 * swarms re-embodying in orbit before departure; 0 if none. Lets the
	 * infestation intel show where the "missing" Defense Swarms went.
	 */
	public static int preparingStrikeFleetCount(MarketAPI market) {
		if (market == null) return 0;
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			// unseen (ThreatStrikeFGI.undetected): the board reads this
			if (strike.isHidden()) continue;
			if (!strike.isPreparing()) continue;
			if (strike.getParams() == null || strike.getParams().source == null) continue;
			if (!market.getId().equals(strike.getParams().source.getId())) continue;
			return strike.getParams().fleetSizes.size();
		}
		return 0;
	}

	/** Whether some strike staged from this colony is still in its recall window. */
	public static boolean hasPreparingStrikeFrom(MarketAPI market) {
		if (market == null) return false;
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			// unseen (ThreatStrikeFGI.undetected): the mission board reads this
			if (strike.isHidden()) continue;
			if (!strike.isPreparing()) continue;
			if (strike.getParams() != null && strike.getParams().source != null
					&& market.getId().equals(strike.getParams().source.getId())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Recalls every in-flight strike staged from the given colony - the
	 * counterplay mirror of the launch. An expedition is fabricated, fueled and
	 * sustained by its staging forge; vanilla offers the same lever for raids
	 * (disrupting a raid's source military market aborts it - see
	 * {@code FleetGroupIntel.isSourceFunctionalMilitaryMarket}), but that check
	 * is keyed to Military Base/High Command industries hive colonies never
	 * run, so it can't fire for strikes. The hive's anchor is the forge colony:
	 * raid its forge into disruption, bombard the colony, or erase it outright,
	 * and the expedition breaks off (vanilla abort - the fleets withdraw).
	 *
	 * Cause is attributed by the hostile-act/deciv hooks that call this rather
	 * than by watching disruption state, so the recall names what actually
	 * happened (a raid, a bombardment, the colony's destruction) instead of
	 * inferring it after the fact.
	 */
	public static void abortStrikesFrom(String marketId, String marketName, String cause) {
		if (marketId == null || !ThreatIncConfig.strikeRecallEnabled()) return;
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			if (strike.isSucceeded()) continue;
			// the recall window is the PREPARATION phase only (same rule as
			// Commerce Wars enforcement strikes): break the forge while the
			// expedition is being fabricated and it is stillborn, but a fleet
			// already in flight is autonomous and flies on regardless
			if (!strike.isPreparing()) continue;
			if (strike.getParams() == null || strike.getParams().source == null) continue;
			if (!marketId.equals(strike.getParams().source.getId())) continue;
			strike.abort();
			ThreatColonyManager.announce(ThreatNotice.titled("Expedition Stillborn").good()
					.line("The Threat expedition being fabricated at %s is stillborn.", marketName)
					.line("%s has broken the forge before the fleets could depart.", cause));
			ThreatIncConfig.log("Strike recalled in preparation (" + cause + "): staged from "
					+ marketName);
		}
	}

	/** Factions with a siege expedition still running against a colony of the system. */
	public static java.util.Set<String> siegeFactionsIn(String systemId) {
		java.util.Set<String> result = new java.util.HashSet<String>();
		if (systemId == null) return result;
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target != null && target.getStarSystem() != null
						&& systemId.equals(target.getStarSystem().getId())) {
					result.add(purge.getFaction().getId());
					break;
				}
			}
		}
		return result;
	}

	/** The worlds the faction's running siege of the system is fighting (its purge's targets); empty with none. */
	public static java.util.List<MarketAPI> siegeTargetsOf(String factionId, String systemId) {
		java.util.List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (factionId == null || systemId == null) return result;
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.getFaction() == null) continue;
			if (!factionId.equals(purge.getFaction().getId())) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target != null && target.getStarSystem() != null
						&& systemId.equals(target.getStarSystem().getId())) {
					result.add(target);
				}
			}
		}
		return result;
	}

	/**
	 * Stands down every in-flight purge expedition whose ENTIRE target list is
	 * dead. A purge campaigns through its whole system: one colony dying -
	 * even to the expedition's own bombardment - is progress, not completion,
	 * so the fleets press on to the next target and only stand down when no
	 * target remains (or they are destroyed). Flying on to saturation-bombard
	 * decivilized husks is nonsense, hence the cleanup. Unconditional (not
	 * gated on strikeRecallEnabled - this is target-validity cleanup, not
	 * player counterplay).
	 */
	public static void abortPurgesAgainst(String marketId, String marketName, String cause) {
		if (marketId == null) return;
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.isAborted()) continue;
			if (purge.isSucceeded()) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			boolean containsDead = false;
			boolean anyAlive = false;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target == null) continue;
				if (marketId.equals(target.getId())) containsDead = true;
				if (ThreatIncData.resolveColonyMarket(target.getId()) != null) anyAlive = true;
			}
			// the campaign continues while any listed colony still lives
			if (!containsDead || anyAlive) continue;
			// abort() -> finish(true) leaves the intel rendering as "Defeated"
			// ("...forces have been defeated and any remaining ships are
			// retreating in disarray") - wrong here: nobody beat these fleets,
			// their target simply ceased to exist. Flag it failed-but-not-defeated
			// first so vanilla titles it "- Failed" and reads "...are withdrawing."
			purge.setFailedButNotDefeated(true);
			purge.abort();
			ThreatNotice.titled("Purge Stands Down").icon(purge.getFaction())
					.line("%s purge expedition is standing down.", ThreatNotice.faction(purge.getFaction()))
					.line("%s leaves it nothing to burn.", cause)
					.send();
			ThreatIncConfig.log("Purge expedition standing down (" + cause + "): last target "
					+ marketName + " destroyed, system purged");
		}
	}

	/**
	 * Catch-all for expeditions orphaned outside the event hooks: purges whose
	 * target colony is already gone, and strikes whose staging colony is
	 * already gone. The hooks in pollColonies fire at the moment of death, but
	 * a save can hold an orphan from before those hooks existed, or from a
	 * death that happened while the mod was disabled - this sweep, run from
	 * the fast-cadence upkeep, retires them on load.
	 */
	protected static void sweepOrphanedExpeditions() {
		for (Object curr : new ArrayList<Object>(getPurgeList())) {
			if (!(curr instanceof GenericRaidFGI)) continue;
			GenericRaidFGI purge = (GenericRaidFGI) curr;
			if (purge.isEnded() || purge.isEnding() || purge.isAborted()) continue;
			if (purge.isSucceeded()) continue;
			if (purge.getParams() == null || purge.getParams().raidParams == null) continue;
			boolean anyAlive = false;
			String deadName = null;
			for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
				if (target == null) continue;
				if (ThreatIncData.resolveColonyMarket(target.getId()) != null) {
					anyAlive = true;
					break;
				}
				deadName = target.getName();
			}
			if (anyAlive || deadName == null) continue;
			abortPurgesAgainst(firstTargetId(purge), deadName, "the colony's destruction");
		}

		if (ThreatIncConfig.strikeRecallEnabled()) {
			for (Object curr : new ArrayList<Object>(getStrikeList())) {
				if (!(curr instanceof ThreatStrikeFGI)) continue;
				ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
				if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
				if (strike.isSucceeded()) continue;
				if (!strike.isPreparing()) continue; // departed = autonomous
				if (strike.getParams() == null || strike.getParams().source == null) continue;
				MarketAPI source = strike.getParams().source;
				if (ThreatIncData.resolveColonyMarket(source.getId()) != null) continue;
				abortStrikesFrom(source.getId(), source.getName(), "the colony's destruction");
			}
		}
	}

	/**
	 * Clamps in-flight SATURATION strikes to the sweep doctrine's one pass per
	 * world. The serialized FGRaidAction reads its params object live - the
	 * very instance getParams().raidParams holds - so lowering the quota here
	 * changes the expedition's behavior mid-flight. Only ever clamps DOWN:
	 * saturation strikes launched under earlier doctrines (vanilla's punitive
	 * 2, the annihilation cap of 10, the exact-kill retrofit, or the
	 * size-tiered pass counts) are cut to one pass per world; tactical strikes
	 * are already the low tier and are left alone.
	 *
	 * <p>Ground-doctrine strikes (no bombardment type set) are left alone too:
	 * their passes are the siege itself - soften, land, reinforce - and are
	 * budgeted by strikePassesPerColony, not by this clamp.
	 */
	protected static void upgradeInFlightStrikes() {
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			if (strike.getParams() == null || strike.getParams().raidParams == null) continue;
			if (strike.getParams().raidParams.bombardment == null) continue;
			if (strike.getParams().raidParams.bombardment == BombardType.TACTICAL) continue;

			MarketAPI source = strike.getParams().source;
			MarketAPI live = source != null
					? ThreatIncData.resolveColonyMarket(source.getId()) : null;
			int stagingSize = live != null ? live.getSize()
					: (source != null ? source.getSize() : 4);
			int passes = strikeSatPasses(stagingSize);
			if (strike.getParams().raidParams.raidsPerColony > passes) {
				strike.getParams().raidParams.raidsPerColony = passes;
				ThreatIncConfig.log("In-flight strike clamped to " + passes
						+ " saturation pass(es) (staging size " + stagingSize + ")");
			}
		}
	}

	/**
	 * Removes duplicate "X - Destroyed" / "X - Decivilized" intel entries:
	 * same entity, same title, posted within days of each other. The
	 * duplicates came from the first in-flight retrofit (surplus bombardment
	 * passes re-ran DecivTracker.decivilize on an already-dead market, and
	 * each call posts its own DecivIntel); this sweep also heals saves that
	 * already carry the spam. The time window keeps a legitimate repeat -
	 * a world recolonized and killed again much later - untouched.
	 */
	protected static void dedupDecivIntel() {
		java.util.Map<String, Long> seen = new HashMap<String, Long>();
		for (Object curr : new ArrayList<Object>(Global.getSector().getIntelManager()
				.getIntel(com.fs.starfarer.api.impl.campaign.intel.deciv.DecivIntel.class))) {
			com.fs.starfarer.api.impl.campaign.intel.deciv.DecivIntel intel =
					(com.fs.starfarer.api.impl.campaign.intel.deciv.DecivIntel) curr;
			SectorEntityToken where = intel.getMapLocation(null);
			String key = (where != null ? where.getId() : "?") + "|" + intel.getName();
			Long ts = intel.getPlayerVisibleTimestamp();
			if (!seen.containsKey(key)) {
				seen.put(key, ts);
				continue;
			}
			Long first = seen.get(key);
			if (ts != null && first != null) {
				float daysApart = Math.abs(
						Global.getSector().getClock().getElapsedDaysSince(first)
						- Global.getSector().getClock().getElapsedDaysSince(ts));
				if (daysApart > 60f) {
					seen.put(key, ts);
					continue;
				}
			}
			Global.getSector().getIntelManager().removeIntel(intel);
			ThreatIncConfig.log("Removed duplicate deciv intel: " + intel.getName());
		}
	}

	protected static String firstTargetId(GenericRaidFGI purge) {
		for (MarketAPI target : purge.getParams().raidParams.allowedTargets) {
			if (target != null) return target.getId();
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> getPurgeList() {
		Object val = Global.getSector().getPersistentData().get(KEY_PURGES);
		if (!(val instanceof List)) {
			val = new ArrayList<Object>();
			Global.getSector().getPersistentData().put(KEY_PURGES, val);
		}
		return (List<Object>) val;
	}

	public static int countActivePurges() {
		return countActiveFGIs(getPurgeList());
	}

	protected static int countActiveFGIs(List<Object> list) {
		int count = 0;
		List<Object> dead = new ArrayList<Object>();
		for (Object curr : list) {
			if (curr instanceof FleetGroupIntel) {
				FleetGroupIntel fgi = (FleetGroupIntel) curr;
				if (!fgi.isEnded() && !fgi.isEnding()) {
					// the player's paid operations stay in the list (for abort/
					// sweep lifecycle) but don't consume the NPC concurrency cap
					boolean playerOp = fgi instanceof ThreatPurgeFGI
							&& ((ThreatPurgeFGI) fgi).isPlayerCommissioned();
					if (!playerOp) count++;
				} else {
					dead.add(curr);
				}
			} else {
				dead.add(curr);
			}
		}
		list.removeAll(dead);
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
		// outside debug mode, a system's marker exists only once the player has
		// actually DISCOVERED the infestation - visited the system, or had it
		// found by any faction (ThreatScouts). Debug mode shows everything.
		boolean debug = ThreatIncConfig.debugMode();
		java.util.Set<String> marked = new java.util.LinkedHashSet<String>();
		List<Object> stale = new ArrayList<Object>();

		for (Object curr : Global.getSector().getIntelManager().getIntel(InfestedSystemIntel.class)) {
			InfestedSystemIntel marker = (InfestedSystemIntel) curr;
			String id = marker.getSystemId();
			if (!ThreatIncData.stages().containsKey(id)
					|| (!debug && !ThreatIncData.discoveredSystems().contains(id))) {
				stale.add(marker);
			} else {
				marked.add(id);
			}
		}
		for (Object curr : stale) {
			Global.getSector().getIntelManager().removeIntel((com.fs.starfarer.api.campaign.comm.IntelInfoPlugin) curr);
		}

		for (String systemId : ThreatIncData.stages().keySet()) {
			if (marked.contains(systemId)) continue;
			if (!debug && !ThreatIncData.discoveredSystems().contains(systemId)) continue;
			InfestedSystemIntel marker = new InfestedSystemIntel(systemId);
			Global.getSector().getIntelManager().addIntel(marker, true);
		}
	}
}
