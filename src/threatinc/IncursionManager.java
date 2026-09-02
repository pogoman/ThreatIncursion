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
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
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
 * converting the dead ones into new colonies. Colonies die by siege alone:
 * no bombardment reduces a hive's population - suppress or starve its organs
 * until decline grinds it to collapse at size 1.
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

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}

	public void advance(float amount) {
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
		sweepOrphanedExpeditions();
		upgradeInFlightStrikes();
		dedupDecivIntel();
		ThreatColonyManager.checkWaveArrivals(random);
		ThreatColonyManager.clearSeedingSwarmIntel();
		ThreatColonyManager.clearThreatMissionIntel();
		ThreatColonyManager.maintainGarrisons(random);
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
		for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin curr : intel) {
			if (curr instanceof EveryFrameScript) {
				((EveryFrameScript) curr).advance(amount);
			}
		}
		ThreatIncursionIntel.checkEradicated();
	}

	protected boolean isTriggered() {
		if (ThreatIncConfig.debugForceStart()) return true;
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
			ThreatIncConfig.log("Granted Threat detection sensor mods on incursion start.");
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
		ThreatColonyManager.maintainPorts();
		ThreatColonyManager.maintainHomeRelics();
		ThreatColonyManager.maintainHiveEconomy();
		trySpread();
		tryConversions();
		tryStrikes();
		tryPurgeBombardments();
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
				ThreatColonyManager.announce("Dense Threat swarms have been detected in "
						+ "transit toward the " + system.getNameWithLowercaseType()
						+ " - multiple fabricator fleets, a coordinated colonization effort.",
						Misc.getNegativeHighlightColor());
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
				ThreatColonyManager.announce("A dense Threat swarm has been detected in transit "
						+ "toward the " + system.getNameWithLowercaseType() + ".",
						Misc.getNegativeHighlightColor());
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
			if (!planet.getMarket().isPlanetConditionMarketOnly()) continue;
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
					ThreatColonyManager.announce(
							"The Remnant Nexus in the " + target.getNameWithLowercaseType()
							+ " has gone silent. Salvors report wreckage of both Remnant and unknown "
							+ "manufacture - a war between machines, and the Remnant lost.",
							Misc.getNegativeHighlightColor());
					ThreatIncData.setStage(target.getId(), ThreatIncData.STAGE_SEEDED);
					ThreatIncConfig.log("Machine war won at " + target.getName() + "; nexus destroyed, system seeded.");
				} else {
					ThreatColonyManager.announce(
							"Fierce fighting between Remnant forces and unidentified constructs has been "
							+ "reported in the " + target.getNameWithLowercaseType()
							+ ". The Remnant Nexus holds - for now.",
							Misc.getHighlightColor());
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
					|| !market.isPlanetConditionMarketOnly()
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
				ThreatColonyManager.announce("Something is moving through the ruins of "
						+ planet.getName() + " - a dense Threat swarm is inbound to claim "
						+ "the world it killed.", Misc.getNegativeHighlightColor());
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
			// the raid intel names its origin: that system is now known
			ThreatIncData.markDiscovered(systemId);
			if (countActiveStrikes() >= ThreatIncConfig.maxConcurrentStrikes()) break;
		}
	}

	protected void launchStrike(MarketAPI colony, StarSystemAPI source, MarketAPI target) {
		GenericRaidParams params = new GenericRaidParams(new Random(random.nextLong()), true);

		params.factionId = Factions.THREAT;
		params.makeFleetsHostile = false; // threat fleets are hostile by construction

		// the colony itself is the staging market - no fake-market hack needed
		params.source = colony;

		params.prepDays = 7f + 7f * random.nextFloat();
		params.payloadDays = 40f + 10f * random.nextFloat();

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
			if (other == target || other.getPrimaryEntity() == null || other.isHidden()) continue;
			if (Factions.THREAT.equals(other.getFactionId())) continue;
			if (other.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
			if (!coreAllowed && other.getSize() >= 6) continue;
			if (other.isPlayerOwned() && !playerAllowed) continue;
			if (isActiveStrikeTarget(other)) continue;
			if (!ThreatIncConfig.destroyStoryCritical() && Misc.isStoryCritical(other)) continue;
			params.raidParams.allowedTargets.add(other);
		}
		params.raidParams.allowNonHostileTargets = true;
		// payload authority: each world in the sweep is bombarded AT MOST ONCE
		// per expedition - a single visit must not erase any world start to
		// finish. Frontier staging (size <= strikeMinSize+1) only harasses with
		// tactical bombardment; developed staging delivers one saturation pass
		// per world - wounds, or kills a world already ground to the destroy
		// threshold. Erasing a large world therefore takes repeated
		// expeditions (or the player's own campaign), not one visit.
		if (colony.getSize() <= ThreatIncConfig.strikeMinSize() + 1) {
			params.raidParams.setBombardment(BombardType.TACTICAL);
		} else {
			params.raidParams.setBombardment(BombardType.SATURATION);
		}
		params.raidParams.raidsPerColony = strikeSatPasses(colony.getSize());
		params.noun = "Threat incursion";
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
		if (mustered.isEmpty()) return;
		// each expedition fleet is EXACTLY the swarm that left orbit (its
		// fabrication tier rides the fleet's memory); the strength multiplier
		// up- or down-tiers the re-embodiment for players who want it
		float mult = ThreatIncConfig.strikeStrengthMult();
		for (int size : mustered) {
			int adjusted = size;
			if (mult >= 2f) adjusted += 2;
			else if (mult >= 1.25f) adjusted += 1;
			if (mult <= 0.5f) adjusted -= 2;
			else if (mult <= 0.8f) adjusted -= 1;
			params.fleetSizes.add(Math.max(3, Math.min(9, adjusted)));
		}

		ThreatStrikeFGI strike = new ThreatStrikeFGI(params);
		Global.getSector().getIntelManager().addIntel(strike);
		getStrikeList().add(strike);

		if (target.isPlayerOwned()) {
			ThreatIncData.setPlayerStruck();
		} else {
			// an NPC colony was struck: its faction fights back, sending a task
			// force against the attacking colony's garrison
			dispatchFactionResponse(target, source, colony);
		}

		ThreatIncConfig.log("Strike launched from " + source.getName() + " at " + target.getName()
				+ " (" + mustered.size() + " swarm(s) mustered, sweeping "
				+ params.raidParams.allowedTargets.size()
				+ " world(s) in " + target.getStarSystem().getName() + ")");
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
	protected void tryPurgeBombardments() {
		if (!ThreatIncConfig.responsePurgeEnabled()) return;

		for (MarketAPI colony : ThreatIncData.getAllLiveColonyMarkets()) {
			if (countActivePurges() >= ThreatIncConfig.responseMaxConcurrent()) return;

			StarSystemAPI system = colony.getStarSystem();
			if (system == null) continue;
			// a purge launches at a colony whose garrison is gone; PREEMPTIVELY
			// at a foothold still small enough to stomp; or - rarest and
			// heaviest - as a full assault on a defended entrenched hive, with
			// extra escorts and on a stretched cooldown. Waiting for a hive to
			// disarm itself is how it gets to size 8.
			boolean defended = ThreatColonyManager.countLiveGarrison(colony.getId()) > 0;
			boolean preemptive = defended
					&& colony.getSize() <= ThreatIncConfig.purgePreemptMaxSize();
			boolean heavyAssault = defended && !preemptive;

			float cooldown = ThreatIncConfig.purgeCooldownDays() * timeScale();
			if (heavyAssault) cooldown *= ThreatIncConfig.purgeDefendedCooldownMult();
			// FOLLOW-UP PRESSURE: a wounded colony - decline meter open, or key
			// organs still disrupted - draws the next expedition on a short
			// cooldown. Navies press an advantage; without this, the decline
			// recovery between full-interval visits erases everything a lone
			// expedition achieved.
			boolean wounded = ThreatIncData.declineProgress(colony.getId()) > 0f
					|| ThreatColonyManager.anyOrganDisrupted(colony);
			if (wounded) {
				cooldown = Math.min(cooldown,
						ThreatIncConfig.purgeFollowUpDays() * timeScale());
			}
			Long last = ThreatIncData.lastPurgeTimes().get(colony.getId());
			if (last != null && Global.getSector().getClock().getElapsedDaysSince(last)
					< cooldown) {
				continue;
			}

			// nearest military world of any NPC faction within response range
			MarketAPI base = null;
			float bestDist = Float.MAX_VALUE;
			for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
				if (market.getFaction() == null || market.getFaction().isPlayerFaction()) continue;
				if (Factions.THREAT.equals(market.getFactionId())) continue;
				if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
				if (!hasMilitary(market)) continue;
				float d = Misc.getDistanceLY(market.getStarSystem().getLocation(), system.getLocation());
				if (d > expeditionRangeLY(market)) continue;
				if (d < bestDist) {
					bestDist = d;
					base = market;
				}
			}
			if (base == null) continue;

			FactionAPI faction = base.getFaction();
			// strength at the base, not the hive system - see dispatchFactionResponse
			float strength = WarSimScript.getFactionStrength(faction, base.getStarSystem());
			int difficulty = ThreatIncConfig.responseMinDifficulty()
					+ Math.round(strength / ThreatIncConfig.responseStrengthDivisor());
			if (difficulty > ThreatIncConfig.responseMaxDifficulty()) {
				difficulty = ThreatIncConfig.responseMaxDifficulty();
			}

			java.util.List<MarketAPI> targets = collectSiegeTargets(colony, system);
			boolean anyGarrisoned = anyTargetGarrisoned(targets);
			java.util.List<Integer> fleetSizes = siegeFleetSizes(difficulty, anyGarrisoned,
					heavyAssault, targets);
			ThreatIncConfig.log("Siege sizing vs " + system.getName() + ": " + fleetSizes.size()
					+ " fleets, ground str ~" + (int) siegeRaidStrEstimate(fleetSizes)
					+ " against " + (int) siegeRaidStrNeeded(targets) + " needed");

			launchSiegeExpedition(base, faction, system, targets, fleetSizes, false, random);

			if (preemptive) {
				ThreatColonyManager.announce(faction.getDisplayName() + " has launched a "
						+ "preemptive purge expedition into the "
						+ system.getNameWithLowercaseType() + " - besieging the swarm's "
						+ (targets.size() > 1 ? targets.size() + " colonies" : "young foothold")
						+ " before they entrench.", Misc.getHighlightColor());
			} else if (heavyAssault) {
				ThreatColonyManager.announce(faction.getDisplayName() + " has committed to a "
						+ "full siege of the " + system.getNameWithLowercaseType()
						+ " - fighting through the swarm's Defense Swarms to put tactical "
						+ "bombardments and commandos on its entrenched colonies.",
						Misc.getHighlightColor());
			} else {
				ThreatColonyManager.announce(faction.getDisplayName() + " has launched a siege "
						+ "expedition into the " + system.getNameWithLowercaseType()
						+ (targets.size() > 1
								? " - all " + targets.size() + " Threat colonies there are marked "
										+ "for tactical bombardment and commando raids."
								: " against the undefended Threat colony there."),
						Misc.getHighlightColor());
			}
			ThreatIncConfig.log(faction.getId()
					+ (preemptive ? " preemptive" : heavyAssault ? " heavy-assault" : "")
					+ " purge expedition vs " + system.getName() + " (" + targets.size()
					+ " colonies, difficulty " + difficulty + ")");
		}
	}

	/**
	 * Every Threat colony in the system, trigger colony first - the expedition
	 * purges the SYSTEM, not one world, worked through sequentially until the
	 * system is clean or the expedition is dead.
	 */
	public static java.util.List<MarketAPI> collectSiegeTargets(MarketAPI trigger,
			StarSystemAPI system) {
		java.util.List<MarketAPI> targets = new ArrayList<MarketAPI>();
		if (trigger != null) targets.add(trigger);
		for (MarketAPI other : ThreatIncData.getLiveColonyMarkets(system.getId())) {
			if (other == trigger || other.getPrimaryEntity() == null) continue;
			targets.add(other);
		}
		return targets;
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

	/**
	 * What the tactical pass leaves of an intact hive's defenses before the
	 * raids go in: the Nexus bonus halves (1.5 -> 1.25) and the batteries fire
	 * at half effect (x3 -> x2, x2 -> x1.5), so a size-6 world behind Heavy
	 * Batteries drops from 10,800 to 6,000 and a size-4 world behind Ground
	 * Defenses from 4,800 to 3,000. Sizing against the intact figure would
	 * double the flotilla for nothing.
	 */
	public static final float SIEGE_SUPPRESSED_DEFENSE_FRACTION = 0.6f;

	/**
	 * The ground strength a siege must put on the surface to disrupt anything.
	 * Vanilla's raid effectiveness is raidStr / (raidStr + defenderStr) and an
	 * industry raid needs MarketCMD.DISRUPTION_THRESHOLD (0.25) of it, so the
	 * landing force must be at least a third of the strongest target's
	 * defender strength as it will stand AFTER the tactical pass - plus
	 * headroom for the preparedness bump. Hive defenses run to thousands
	 * (hiveDefensePerSize x size, multiplied by the Nexus and the batteries),
	 * which is exactly why a flotilla sized by difficulty alone spent its
	 * raids being repulsed.
	 */
	public static float siegeRaidStrNeeded(java.util.List<MarketAPI> targets) {
		float def = 0f;
		for (MarketAPI target : targets) {
			if (target == null) continue;
			float d = com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.getDefenderStr(target);
			// an already-suppressed world reads its suppressed figure; an intact one will be
			if (!ThreatColonyManager.anyOrganDisrupted(target)) d *= SIEGE_SUPPRESSED_DEFENSE_FRACTION;
			def = Math.max(def, d);
		}
		float threshold = com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.DISRUPTION_THRESHOLD;
		return def * threshold / Math.max(0.01f, 1f - threshold) * SIEGE_RAID_HEADROOM;
	}

	/**
	 * What a flotilla of these difficulty points lands: NPC raid strength is a
	 * quarter of the fleets' crew capacity, and crew capacity tracks fleet
	 * size, so difficulty points times siegeRaidStrPerPoint (measured, not
	 * derived - see the setting).
	 */
	public static float siegeRaidStrEstimate(java.util.List<Integer> fleetSizes) {
		int points = 0;
		for (Integer size : fleetSizes) points += size;
		return points * ThreatIncConfig.siegeRaidStrPerPoint();
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
	 */
	public static java.util.List<Integer> siegeFleetSizes(int difficulty, boolean anyGarrisoned,
			boolean heavyAssault, java.util.List<MarketAPI> targets) {
		java.util.List<Integer> sizes = new ArrayList<Integer>();
		sizes.add(Math.min(10, difficulty));
		sizes.add(Math.max(5, difficulty - 2));
		if (anyGarrisoned) sizes.add(Math.min(10, difficulty));
		if (heavyAssault) sizes.add(Math.min(10, difficulty));
		if (targets.size() >= 3) sizes.add(Math.max(5, difficulty - 2));
		float needed = siegeRaidStrNeeded(targets);
		int maxFleets = Math.max(sizes.size(), ThreatIncConfig.siegeMaxFleets());
		int extra = Math.min(10, Math.max(6, difficulty));
		while (siegeRaidStrEstimate(sizes) < needed && sizes.size() < maxFleets) {
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
		GenericRaidParams params = new GenericRaidParams(
				new Random(random.nextLong()), !playerCommissioned);
		params.factionId = faction.getId();
		params.source = base;
		params.prepDays = 7f + 7f * random.nextFloat();
		// enough on-station time to actually work through the list - siege
		// doctrine makes several passes per colony, not one bombardment
		params.payloadDays = 45f + 10f * random.nextFloat() + 25f * (targets.size() - 1);
		params.raidParams.where = system;
		params.raidParams.type = FGRaidType.SEQUENTIAL;
		params.raidParams.tryToCaptureObjectives = false;
		params.raidParams.allowedTargets.addAll(targets);
		params.raidParams.allowNonHostileTargets = true;
		// no params.bombardment: the custom raid action in ThreatPurgeFGI
		// runs the siege doctrine per pass - tactical bombardment while the
		// war-strata stand, commando raids once they're suppressed; never
		// saturation (bombardment cannot reduce hive population)
		params.raidParams.raidsPerColony = 3;
		params.raidParams.raidApproachText = "moving to besiege";
		params.raidParams.raidActionText = "conducting siege operations against";
		params.noun = "purge expedition";
		// vanilla writes "the <forcesNoun> are withdrawing", so no "Your" here
		params.forcesNoun = playerCommissioned ? "commissioned forces"
				: faction.getDisplayName() + " forces";
		params.style = FleetStyle.STANDARD;
		params.repImpact = ComplicationRepImpact.NONE;
		if (playerCommissioned) {
			// default true: createFleet would triggerMakeHostile() and turn the
			// fleets the player just paid for against them
			params.makeFleetsHostile = false;
		}
		params.fleetSizes.addAll(fleetSizes);

		ThreatPurgeFGI purge = new ThreatPurgeFGI(params, playerCommissioned);
		Global.getSector().getIntelManager().addIntel(purge);
		getPurgeList().add(purge);
		// stamp every targeted colony so siblings don't each trigger their own
		// duplicate purge of the same system while this one is in flight (a
		// commissioned expedition suppresses NPC duplication the same way)
		for (MarketAPI target : targets) {
			ThreatIncData.lastPurgeTimes().put(target.getId(),
					Global.getSector().getClock().getTimestamp());
		}
		return purge;
	}

	// ------------------------------------------------------------------
	// player-commissioned expeditions
	// ------------------------------------------------------------------

	/** Nearest player military colony in range of the target system, or null. */
	public static MarketAPI findPlayerExpeditionBase(StarSystemAPI system) {
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI market : Misc.getPlayerMarkets(false)) {
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (!hasMilitary(market)) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(),
					system.getLocation());
			if (d > expeditionRangeLY(market)) continue;
			if (d < bestDist) {
				bestDist = d;
				best = market;
			}
		}
		return best;
	}

	/**
	 * A commissioned expedition is sized to the JOB, not to a sponsor's navy:
	 * the biggest colony in the target system sets the tempo, live Defense
	 * Swarms add a point, clamped to the response difficulty band.
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

	/**
	 * The bill: fleet size (sum of per-fleet difficulty points) plus distance,
	 * rounded to the nearest thousand credits.
	 */
	public static int commissionCost(MarketAPI base, StarSystemAPI system,
			java.util.List<Integer> fleetSizes) {
		int points = 0;
		for (Integer size : fleetSizes) points += size;
		float dist = Misc.getDistanceLY(base.getStarSystem().getLocation(),
				system.getLocation());
		float cost = points * ThreatIncConfig.commissionCostPerPoint()
				+ dist * ThreatIncConfig.commissionCostPerLY();
		return Math.max(1000, Math.round(cost / 1000f) * 1000);
	}

	/** The player's live commissioned expedition against a system, or null. */
	public static ThreatPurgeFGI findPlayerExpeditionAgainst(String systemId) {
		for (Object curr : getPurgeList()) {
			if (!(curr instanceof ThreatPurgeFGI)) continue;
			ThreatPurgeFGI fgi = (ThreatPurgeFGI) curr;
			if (fgi.isEnded() || fgi.isEnding()) continue;
			if (!fgi.isPlayerCommissioned()) continue;
			StarSystemAPI where = fgi.getParams() != null && fgi.getParams().raidParams != null
					? fgi.getParams().raidParams.where : null;
			if (where != null && where.getId().equals(systemId)) return fgi;
		}
		return null;
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
		abortStrikesFrom(market.getId(), market.getName(), "the bombardment");
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
			if (!Misc.flagHasReason(market.getMemoryWithoutUpdate(),
					MemFlags.RECENTLY_BOMBARDED, Factions.THREAT)) continue;

			if (!ThreatIncData.decivTargets().contains(planetId)) {
				ThreatIncData.decivTargets().add(planetId);
				ThreatColonyManager.announce(entity.getName() + " has fallen silent under Threat "
						+ "bombardment... and the swarm does not abandon its kills. Expect them "
						+ "to come for the ruins.", Misc.getNegativeHighlightColor());
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
			if (market.isHidden()) continue;
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

	protected MarketAPI pickStrikeTarget(MarketAPI staging, StarSystemAPI source) {
		boolean coreAllowed = getPhase() >= 3;
		boolean playerAllowed = ThreatIncData.daysSincePlayerStruck() >= ThreatIncConfig.playerGraceDays();

		// reach is fuel: how far the staging colony can actually project, from
		// the fuel the hive network delivers to it (accessibility-mediated)
		float rangeLY = ThreatColonyManager.fuelRangeLY(staging);
		if (rangeLY <= 0f) return null;

		WeightedRandomPicker<MarketAPI> picker = new WeightedRandomPicker<MarketAPI>(random);
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			if (market.isHidden()) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			if (market.getMemoryWithoutUpdate().getBoolean(ThreatColonyManager.COLONY_FLAG)) continue;
			if (market.getSize() < 3) continue;
			// size 6+ markets are "core worlds" - phase 3 only
			if (!coreAllowed && market.getSize() >= 6) continue;
			if (market.isPlayerOwned() && !playerAllowed) continue;
			if (isActiveStrikeTarget(market)) continue; // one strike per world
			// the engine refuses the killing blow on story-critical worlds, and
			// an annihilation doctrine has no use for a target it cannot kill -
			// unless the player has opted into breaking vanilla storylines
			if (!ThreatIncConfig.destroyStoryCritical() && Misc.isStoryCritical(market)) continue;

			float d = Misc.getDistanceLY(source.getLocation(), market.getStarSystem().getLocation());
			if (d > rangeLY) continue;

			// the swarm hunts concentration: the bigger the world, the more
			// biomass and technology to erase - distance costs are already paid
			// in fuel (the range gate), so desirability is size alone
			float w = market.getSize() * market.getSize();
			picker.add(market, w);
		}
		return picker.pick();
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
		// near-nominal hull economy. Both can REGRESS: burn their forges, cut
		// their fuel, shrink their colonies, and the sector's danger level
		// genuinely drops - the escalation is theirs to earn and yours to undo.
		boolean canStrike = false;
		boolean canStrikeCore = false;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (market.getSize() < ThreatIncConfig.strikeMinSize()) continue;
			if (ThreatColonyManager.getForge(market) == null) continue;
			if (!ThreatColonyManager.hasOperationalFuel(market)) continue;
			if (!ThreatColonyManager.hasOperationalNexus(market)) continue;
			canStrike = true;
			if (market.getSize() >= 6
					&& ThreatColonyManager.shipSupplyMult(market)
							>= ThreatColonyManager.STABLE_SHIP_SUPPLY_MULT) {
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
			MessageIntel msg = new MessageIntel(
					"Threat incursion fleets have been sighted on approach vectors toward the core "
					+ "worlds. Nowhere in the sector is beyond their reach any longer.",
					Misc.getNegativeHighlightColor());
			setThreatIcon(msg);
			Global.getSector().getCampaignUI().addMessage(msg);
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
			if (!hasMilitary(market)) continue;
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

	/** Static so the mission board can ask the same question the purge logic does. */
	protected static boolean hasMilitary(MarketAPI market) {
		return market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.PATROLHQ)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.MILITARYBASE)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.HIGHCOMMAND);
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
			ThreatColonyManager.announce("The Threat expedition being fabricated at " + marketName
					+ " is stillborn: " + cause + " has broken the forge before the "
					+ "fleets could depart.", Misc.getPositiveHighlightColor());
			ThreatIncConfig.log("Strike recalled in preparation (" + cause + "): staged from "
					+ marketName);
		}
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
			ThreatColonyManager.announce(purge.getFaction().getDisplayName()
					+ " purge expedition is standing down: " + cause + " means no Threat "
					+ "colony it was sent to burn still exists.", Misc.getHighlightColor());
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
	 * Clamps in-flight strikes to the sweep doctrine's one pass per world. The
	 * serialized FGRaidAction reads its params object live - the very instance
	 * getParams().raidParams holds - so lowering the quota here changes the
	 * expedition's behavior mid-flight. Only ever clamps DOWN: saturation
	 * strikes launched under earlier doctrines (vanilla's punitive 2, the
	 * annihilation cap of 10, the exact-kill retrofit, or the size-tiered
	 * pass counts) are cut to one pass per world; tactical strikes are
	 * already the low tier and are left alone.
	 */
	protected static void upgradeInFlightStrikes() {
		for (Object curr : getStrikeList()) {
			if (!(curr instanceof ThreatStrikeFGI)) continue;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) curr;
			if (strike.isEnded() || strike.isEnding() || strike.isAborted()) continue;
			if (strike.getParams() == null || strike.getParams().raidParams == null) continue;
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
		// actually DISCOVERED the infestation - visited the system, or seen it
		// named by swarm-transit or strike intel. Debug mode shows everything.
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

	public static void setThreatIcon(MessageIntel msg) {
		try {
			String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
			if (crest != null) msg.setIcon(crest);
		} catch (Throwable t) {
			// no icon is fine
		}
	}
}
