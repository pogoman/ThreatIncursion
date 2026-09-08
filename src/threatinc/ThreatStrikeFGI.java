package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.CountingMap;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;

/**
 * A raid fleet-group whose fleets are authentic Threat swarms (built via the
 * vanilla threat fleet factory) instead of doctrine-generated faction fleets.
 * The base class handles travel, raid actions, and defeat/abort reporting.
 */
public class ThreatStrikeFGI extends GenericRaidFGI {

	public ThreatStrikeFGI(GenericRaidParams params) {
		super(params);
	}

	/**
	 * Whether the expedition is still at its staging colony - the pre-launch
	 * planning window plus the fleets mustering in orbit. This is the recall
	 * window (same rule as Commerce Wars enforcement strikes): sabotage the
	 * source while the operation is being fabricated and it is stillborn, but
	 * once the fleets depart the expedition is autonomous - the swarm's
	 * fleets need no home to keep flying.
	 */
	public boolean isPreparing() {
		if (isInPreLaunchDelay()) return true;
		com.fs.starfarer.api.impl.campaign.intel.group.FGAction curr = getCurrentAction();
		return curr != null && PREPARE_ACTION.equals(curr.getId());
	}

	/**
	 * The payload sweeps every eligible world in the target system
	 * (IncursionManager.launchStrike), spending {@code strikePassesPerColony}
	 * passes on each. What a pass DOES is {@link #doCustomRaidAction}: the
	 * swarm mirrors the siege it is subjected to - soften, land, reinforce.
	 *
	 * <p>With {@code strikeSaturationEnabled} the old doctrine comes back
	 * instead: {@code raidParams.bombardment} is set, vanilla's own bombardment
	 * path runs, and this action's dead-target guard and story-critical
	 * handling matter again. Passes stay affordable because
	 * {@link #createFleet} zeroes the fleet bombardment fuel cost - the
	 * expedition was provisioned at its staging colony.
	 */
	@Override
	protected GenericPayloadAction createPayloadAction() {
		return new AnnihilationAction(getParams().raidParams, getParams().payloadDays);
	}

	public static class AnnihilationAction
			extends com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction {
		public AnnihilationAction(FGRaidParams params, float raidDays) {
			super(params, raidDays);
		}

		/**
		 * Autoresolve loops the full pass count blindly; skip passes at a
		 * target that is already dead so the husk isn't re-bombarded.
		 *
		 * Story-critical worlds need their own handling because vanilla
		 * doBombardment hard-refuses the killing blow on them (destroy is
		 * forced false): without it, the surplus annihilation passes just
		 * stacked stability damage on an unkillable world (observed: -100
		 * stability on Chalcedon). With destruction disallowed the passes
		 * stop once the world is ground to the destroy threshold - nothing
		 * more can be achieved; with it allowed, the killing blow the engine
		 * refused is dealt directly.
		 */
		@Override
		public void performRaid(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.econ.MarketAPI market) {
			if (market == null || !market.isInEconomy()) return;

			// ground doctrine: no bombardment type is set, so the base class
			// routes the pass to doCustomRaidAction. None of the bombardment
			// guards below apply - a besieged world is meant to survive its
			// passes and be taken on the ground.
			if (getParams().bombardment == null) {
				// the siege of a human colony (2026-09-06): a live fleet over a
				// world not yet ready to be landed on delivers a slice of the
				// orbital siege instead of a pass, spending none; an abstract
				// expedition runs its whole siege in one go first
				if (intel instanceof ThreatStrikeFGI && !Factions.THREAT.equals(market.getFactionId())
						&& !ThreatGroundFronts.isHiveTarget(market)) {
					ThreatStrikeFGI strike = (ThreatStrikeFGI) intel;
					if (fleet != null) {
						if (strike.siegePass(fleet, market)) return;
					} else if (!ThreatIncConfig.strikeGroundFrontsEnabled()
							|| strike.getTroopsAboard() >= ThreatIncConfig.frontMinMarines()) {
						// (nothing left to land means no siege either - siegePass's rule)
						strike.abstractSiege(market);
						// vanilla counts the pass before it asks us what to do with
						// it: a world still above the floor waits here, spending nothing
						if (ThreatIncConfig.frontsEnabled()
								&& ThreatGroundFronts.getFront(market.getId()) == null
								&& !ThreatGroundFronts.readyToLand(market, strike.worldShare())) {
							return;
						}
					}
				}
				super.performRaid(fleet, market);
				return;
			}

			boolean storyCritical = com.fs.starfarer.api.util.Misc.isStoryCritical(market);
			boolean atFloor = market.getSize()
					<= com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD
							.getBombardDestroyThreshold();
			if (storyCritical && atFloor && !ThreatIncConfig.destroyStoryCritical()) return;

			super.performRaid(fleet, market);
			// a Threat action landed here: any defence contract for the colony fails
			ThreatAidMissionIntel.strikeLanded(market);

			if (storyCritical && atFloor && ThreatIncConfig.destroyStoryCritical()
					&& market.isInEconomy()) {
				// the swarm does not care whose story a world matters to
				com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker
						.decivilize(market, true);
			}
		}

		/**
		 * Under the ground doctrine a pass counts only if it did something -
		 * a held orbit turning every landing back is the swarm failing, not
		 * succeeding. The saturation doctrine keeps vanilla's count.
		 */
		@Override
		public float getSuccessFraction() {
			if (getParams().bombardment != null || !(intel instanceof ThreatStrikeFGI)) {
				return super.getSuccessFraction();
			}
			int totalGoal = Math.max(1, getParams().raidsPerColony
					* Math.max(1, getParams().allowedTargets.size()));
			float achieved = ((ThreatStrikeFGI) intel).effectivePasses;
			return Math.max(0f, Math.min(1f, achieved / totalGoal));
		}

		/**
		 * The siege fights for the orbit (the purge's rule,
		 * ThreatPurgeFGI.SiegeRaidAction): while a defending fleet or station
		 * holds the orbit of a target the swarm has not landed on, the
		 * expedition's fleets at that world hunt instead of idling - and only
		 * there. Everything else about where an expedition fleet may go is
		 * {@link ThreatFleetOrders#siegeLeash}, which runs whatever the
		 * doctrine: a strike that does not fight for the orbit still has to
		 * stay on its target.
		 */
		@Override
		public void directFleets(float amount) {
			super.directFleets(amount);
			if (isActionFinished() || intel == null) return;
			// fleets still mustering at the source are not strays (vanilla's own guard)
			if (intel.isSpawning()) return;
			FGRaidParams p = getParams();
			if (p == null || p.where == null) return;
			List<MarketAPI> live = new ArrayList<MarketAPI>();
			List<MarketAPI> contested = new ArrayList<MarketAPI>();
			for (MarketAPI target : p.allowedTargets) {
				if (target == null || !target.isInEconomy()) continue;
				if (Factions.THREAT.equals(target.getFactionId())) continue;
				live.add(target);
				if (ThreatGroundFronts.getFront(target.getId()) != null) continue;
				if (ThreatGroundFronts.orbitContestedFor(Factions.THREAT, target)) {
					contested.add(target);
				}
			}
			// the saturation doctrine bombards from orbit and never lands, so
			// it has nothing to clear the orbit for
			boolean hunt = ThreatIncConfig.siegeFightsForOrbit() && p.bombardment == null;
			for (CampaignFleetAPI fleet : intel.getFleets()) {
				ThreatFleetOrders.siegeLeash(fleet, contested,
						ThreatFleetOrders.nearestWorld(fleet, live), hunt, "Strike");
			}
		}
	}

	// ------------------------------------------------------------------
	// strike doctrine: soften, land, reinforce (docs/ground-war.md)
	// ------------------------------------------------------------------

	/**
	 * Under the ground doctrine every pass runs through
	 * {@link #doCustomRaidAction} - which the base class only calls when no
	 * bombardment type is set. With {@code strikeSaturationEnabled} the old
	 * saturation path takes over and this goes quiet.
	 */
	@Override
	public boolean hasCustomRaidAction() {
		return getParams() != null && getParams().raidParams != null
				&& getParams().raidParams.bombardment == null;
	}

	// ---- the troop pool ----

	/**
	 * Strike fleets are swarm ships with no marine cargo, so the ground force
	 * is abstract - but it is fixed at LAUNCH from what the expedition is:
	 * {@code strikeTroopsPerPoint} per difficulty point of the swarms mustered
	 * (the mirror of the purge's marinesAllotted), discounted by what the
	 * expedition loses on the way, drawn down by every landing, and shown on
	 * the intel. A strike lands what it brought and nothing more; the
	 * defender's strength decides what happens next, never how big the
	 * invasion is. The pool is split evenly across the worlds in the sweep,
	 * no share below {@code strikeFrontMinTroops}: a small strike lands fewer
	 * worlds, not token forces.
	 */
	protected float troopsAllotted = 0f;
	protected float troopsAboard = 0f;
	protected boolean poolSet = false;
	/** Troops put ashore per world so far - a world never receives more than its share. */
	protected Map<String, Float> landedAt = new HashMap<String, Float>();
	/** Passes that did something - a bombardment, a landing, a reinforcement - for the success fraction. */
	protected int effectivePasses = 0;

	@Override
	protected Object readResolve() {
		super.readResolve();
		if (landedAt == null) landedAt = new HashMap<String, Float>();
		if (siegeAnnounced == null) siegeAnnounced = new HashSet<String>();
		if (siegeResolved == null) siegeResolved = new HashSet<String>();
		return this;
	}

	protected void ensurePool() {
		if (poolSet) return;
		poolSet = true;
		float points = 0f;
		if (getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) points += size;
			}
		}
		troopsAllotted = Math.max(0f, points * ThreatIncConfig.strikeTroopsPerPoint());
		troopsAboard = troopsAllotted;
	}

	public float getTroopsAboard() {
		ensurePool();
		return troopsAboard;
	}

	/** A world's share of the pool: an even split of the sweep, never below the floor. */
	protected float worldShare() {
		ensurePool();
		int worlds = 1;
		if (getParams() != null && getParams().raidParams != null) {
			worlds = Math.max(1, getParams().raidParams.allowedTargets.size());
		}
		return Math.max(ThreatIncConfig.strikeFrontMinTroops(), troopsAllotted / worlds);
	}

	/**
	 * What this pass can put ashore here: the pool, first cut to what the
	 * surviving expedition can still carry, then capped at what is left of
	 * the world's share. Not yet drawn - {@link #commitLanding} takes it once
	 * the landing goes ahead.
	 */
	protected int availableLanding(MarketAPI market, CampaignFleetAPI fleet) {
		ensurePool();
		troopsAboard = Math.min(troopsAboard, troopsAllotted * survivingStrength(fleet));
		Float already = landedAt.get(market.getId());
		float room = worldShare() - (already != null ? already : 0f);
		return Math.max(0, Math.round(Math.min(troopsAboard, room)));
	}

	protected void commitLanding(MarketAPI market, int troops) {
		troopsAboard = Math.max(0f, troopsAboard - troops);
		Float already = landedAt.get(market.getId());
		landedAt.put(market.getId(), (already != null ? already : 0f) + troops);
	}

	// ---- the orbital siege (docs/ground-war.md "Sieges from orbit") ----

	/** Worlds this expedition has announced its siege of. */
	protected Set<String> siegeAnnounced = new HashSet<String>();
	/** Worlds whose siege has been run abstractly (no live fleets). */
	protected Set<String> siegeResolved = new HashSet<String>();

	/**
	 * A live fleet over a human colony: a slice of the orbital siege in place
	 * of a pass, spending none. False when the pass should go ahead - the
	 * swarm already has a front here to reinforce, or the world is ready to be
	 * landed on. While the orbit is held against the swarm nothing happens
	 * either way: the fleets fight for it
	 * ({@link AnnihilationAction#directFleets}).
	 */
	protected boolean siegePass(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || market == null) return false;
		// with landings off the swarm still besieges - it just never comes down
		if (!ThreatIncConfig.frontsEnabled()) return false;
		int troops = availableLanding(market, fleet);
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front != null) {
			// the swarm's own front and nothing left to reinforce it with: the
			// fleet stays over it on Defend instead of spending the pass
			if (troops <= 0 && ThreatGroundFronts.isThreatOwned(front)) return joinDefend(fleet, market);
			return false;
		}
		// nothing left to land here (the world's share, or the pool, spent):
		// no duel over a world the swarm can never take (2026-09-07, the
		// purge's rule). The fleet joins the defence of a front it did land;
		// with none, the pass goes ahead and spends itself on nothing
		if (ThreatIncConfig.strikeGroundFrontsEnabled()
				&& troops < ThreatIncConfig.frontMinMarines()) {
			if (joinDefend(fleet, market)) return true;
			ThreatIncConfig.log("Siege of " + market.getName() + ": nothing left to land, no siege");
			return false;
		}
		if (ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) {
			ThreatIncConfig.log("Siege of " + market.getName() + ": the orbit is contested");
			return true;
		}
		if (ThreatGroundFronts.readyToLand(market, troops)) return false;
		float days = ThreatGroundFronts.siegeSliceDays(fleet);
		float fp = fleet.getFleetPoints();
		float[] est = ThreatGroundFronts.siegeSliceEstimate(fp, market, days);
		float loss = ThreatGroundFronts.siegeSlice(fp, market, days);
		float removed = ThreatGroundFronts.applyFleetLosses(fleet, loss);
		if (siegeAnnounced.add(market.getId())) {
			ThreatColonyManager.announceAlways("Threat swarms are besieging " + market.getName()
					+ " - its defences are being suppressed from orbit, and its batteries "
					+ "are answering.", Misc.getNegativeHighlightColor());
		}
		ThreatIncConfig.log("Siege slice vs " + market.getName() + ": " + (int) fp + " FP for "
				+ String.format("%.1f", days) + " d, +" + String.format("%.1f", est[0])
				+ " d on the clock (" + (int) ThreatGroundFronts.siegeClock(market) + " of "
				+ (int) ThreatGroundFronts.siegeFloorDays(market) + "), batteries cost "
				+ String.format("%.1f", loss) + " FP (" + (int) removed + " removed)");
		return true;
	}

	/**
	 * An expedition that never spawned (vanilla autoresolve) runs its whole
	 * siege of a world in one go ({@link ThreatGroundFronts#abstractSiege}),
	 * its abstract strength standing in for the fleets. The disruption it
	 * writes is real; the passes that follow land if it got there.
	 */
	protected void abstractSiege(MarketAPI market) {
		if (market == null || !siegeResolved.add(market.getId())) return;
		if (ThreatGroundFronts.getFront(market.getId()) != null) return;
		float start = totalFPSpawned > 0f ? totalFPSpawned * survivingStrength(null) : 0f;
		if (start <= 0f && getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) start += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		float left = ThreatGroundFronts.abstractSiege(market, start, worldShare(),
				groupAbortsMissionFPFraction);
		// the batteries' toll comes off the troops the ships were carrying
		if (start > 0f && left < start) troopsAboard *= Math.max(0f, left / start);
	}

	/**
	 * ONE PASS OF THE THREAT'S SIEGE. The swarm no longer erases worlds from
	 * orbit; it does to an inhabited world exactly what a purge expedition does
	 * to a hive, and for the same reason - only a ground victory kills a
	 * colony, in either direction. The three questions every landing asks -
	 * does the world still need softening, can anything land here for this
	 * owner, land or reinforce - are {@link ThreatGroundFronts}' to answer,
	 * the same code the purge runs.
	 *
	 * <ol>
	 * <li><b>Soften.</b> A live fleet over a world not yet ready to be landed on
	 * delivers a slice of the orbital siege instead of a pass ({@link #siegePass}):
	 * the defence structures are suppressed a little, in proportion to fleet
	 * strength against ground defence, and the batteries answer in ships. The
	 * landing waits until the fortification is at its floor or the troops could
	 * hold as they are ({@link ThreatGroundFronts#readyToLand}).</li>
	 * <li><b>Land.</b> With the defenses suppressed and nothing blocking the
	 * landing (fallout, another army, a held orbit), the pass puts the world's
	 * share of the troop pool on the surface as a Threat-owned front with
	 * {@code strikeFrontSupplyDays} of armaments; {@link ThreatGroundFronts}
	 * runs the campaign from there.</li>
	 * <li><b>Reinforce.</b> A pass over the swarm's own front lands whatever of
	 * the world's share is still aboard. Vanilla spends passes as fleets
	 * arrive - all of them at once in autoresolve - so this is a top-up, not a
	 * schedule; a Threat front's real reinforcement is the next expedition.</li>
	 * </ol>
	 *
	 * <p>A pass that lands nothing - the orbit held, or the share spent - is
	 * still a pass spent: the defender's orbit superiority costs the swarm its
	 * passes, which is the whole counterplay.
	 */
	@Override
	public void doCustomRaidAction(CampaignFleetAPI fleet, MarketAPI market, float raidStr) {
		if (market == null || market.getPrimaryEntity() == null || !market.isInEconomy()) return;
		if (Factions.THREAT.equals(market.getFactionId())) return; // the swarm does not besiege itself
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());

		if (!ThreatIncConfig.strikeGroundFrontsEnabled() || !ThreatIncConfig.frontsEnabled()) {
			return; // landings disabled: the swarm can only ever besiege
		}

		// 2 and 3. land, or reinforce our own front - one gate, shared with the purge
		String blocked = ThreatGroundFronts.landingBlocked(Factions.THREAT, market, fleet != null);
		if (blocked != null) {
			ThreatIncConfig.log("Strike landing at " + market.getName() + " refused: " + blocked);
			return;
		}
		int troops = availableLanding(market, fleet);
		// 1. soften: the orbital siege (performRaid) must have done all it can,
		// or the troops must be able to hold as they are, before anything lands
		if (front == null && !ThreatGroundFronts.readyToLand(market, troops)) {
			ThreatIncConfig.log("Strike landing at " + market.getName()
					+ " waits: the defences are still above the floor");
			return;
		}
		if (front == null && troops < ThreatIncConfig.frontMinMarines()) {
			ThreatIncConfig.log("Strike landing at " + market.getName() + " aborted: only "
					+ troops + " troops left aboard for it");
			return;
		}
		if (troops <= 0) return; // the world's share is spent: nothing to reinforce with
		float supply = ThreatGroundFronts.landingSupply(troops,
				ThreatIncConfig.strikeFrontSupplyDays());
		if (ThreatGroundFronts.landOrReinforce(market, Factions.THREAT, troops, supply) == null) {
			return;
		}
		commitLanding(market, troops);
		effectivePasses++;
		ThreatIncConfig.log("Strike pass (" + (front == null ? "landing" : "reinforce") + ") vs "
				+ market.getName() + ": " + troops + " troops, " + (int) supply + " armaments; "
				+ (int) troopsAboard + " still aboard");
		// the fleet that put them down stays over them (ThreatSwarmDefend);
		// the rest of the expedition sweeps on
		stayOnDefend(fleet, market);
	}

	/**
	 * Vanilla's incremental spawn never stamps KEY_SPAWN_FP on a fleet
	 * (ThreatPurgeFGI.noteSpawnFP), so a detached fleet would have nothing to
	 * take off the group's spawned-strength baseline; stamped here the tick
	 * after each fleet becomes real.
	 */
	@Override
	protected void advanceImpl(float amount) {
		super.advanceImpl(amount);
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null) continue;
			if (fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP) <= 0f) {
				fleet.getMemoryWithoutUpdate().set(KEY_SPAWN_FP, (float) fleet.getFleetPoints());
			}
		}
	}

	/**
	 * Takes one fleet out of the expedition (ThreatPurgeFGI.detach, the
	 * swarm's copy): the group's spawned-strength baseline drops by the
	 * fleet's own, so the rest is not judged beaten for its absence; an
	 * expedition left with no fleet finishes as vanilla's does (its payload
	 * action ends on an empty group), not as a rout.
	 */
	public boolean detach(CampaignFleetAPI fleet) {
		if (fleet == null || !getFleets().contains(fleet)) return false;
		float spawnFP = fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP);
		if (spawnFP <= 0f) spawnFP = fleet.getFleetPoints();
		getFleets().remove(fleet);
		setTotalFPSpawned(getFleets().isEmpty() ? 0f
				: Math.max(0f, getTotalFPSpawned() - spawnFP));
		ThreatPurgeFGI.cutLoose(fleet);
		return true;
	}

	/**
	 * THE LANDING FLEET STAYS (2026-09-07, the purge's rule): the fleet whose
	 * pass landed or reinforced the front leaves the expedition for a Defend
	 * station over the world ({@link ThreatSwarmDefend}) - holding the orbit,
	 * bombarding only while the front cannot hold - and the rest sweep on.
	 * Knob: landingDefendEnabled.
	 */
	protected void stayOnDefend(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || market == null) return;
		if (!ThreatIncConfig.landingDefendEnabled()) return;
		if (!detach(fleet)) return;
		if (ThreatSwarmDefend.attach(fleet, market, Factions.THREAT,
				getParams() != null ? getParams().source : null) == null) {
			giveReturnAssignments(fleet); // out of the group and refused: home, not adrift
		}
	}

	/**
	 * A fleet with nothing left to land joins the defence of a front the
	 * swarm did land - this world's, else the nearest of the sweep's -
	 * rather than duelling batteries over a world it can never take. False
	 * when there is no such front.
	 */
	protected boolean joinDefend(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || !ThreatIncConfig.landingDefendEnabled()) return false;
		MarketAPI world = ownFrontWorld(market, fleet);
		if (world == null) return false;
		stayOnDefend(fleet, world);
		return !getFleets().contains(fleet);
	}

	/** This world if a Threat front stands on it, else the sweep's Threat-front world nearest the fleet, else null. */
	protected MarketAPI ownFrontWorld(MarketAPI market, CampaignFleetAPI fleet) {
		if (market != null && ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(market.getId()))) {
			return market;
		}
		if (getParams() == null || getParams().raidParams == null) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI target : getParams().raidParams.allowedTargets) {
			if (target == null || !target.isInEconomy() || target.getPrimaryEntity() == null) continue;
			if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(target.getId()))) continue;
			float dist = fleet != null && fleet.getContainingLocation() == target.getContainingLocation()
					? Misc.getDistance(fleet, target.getPrimaryEntity()) : Float.MAX_VALUE / 2f;
			if (dist < bestDist) {
				bestDist = dist;
				best = target;
			}
		}
		return best;
	}

	/**
	 * The siege as it stands, for the intel: each world's phase and passes
	 * spent, and the troops still aboard - the purge's own readout, mirrored,
	 * so the defender reads the landing off the strike, not off a surprise.
	 */
	protected void addSiegeStatus(TooltipMakerAPI info) {
		if (getParams() == null || getParams().raidParams == null
				|| getParams().raidParams.bombardment != null) return;
		java.awt.Color h = Misc.getHighlightColor();
		if (getCurrentAction() != null && getCurrentAction() == raidAction) {
			int budget = Math.max(1, getParams().raidParams.raidsPerColony);
			CountingMap<MarketAPI> passes = raidAction instanceof FGRaidAction
					? ((FGRaidAction) raidAction).getRaidCount() : null;
			for (MarketAPI target : getParams().raidParams.allowedTargets) {
				if (target == null || !target.isInEconomy()) continue;
				int used = passes != null ? passes.getCount(target) : 0;
				info.addPara(target.getName() + ": %s, %s passes.", 3f, h, phase(target),
						used + "/" + budget);
			}
		}
		info.addPara("Aboard: %s troops.", 3f, h, Misc.getWithDGS((int) getTroopsAboard()));
	}

	/** What the strike is doing to this world right now. */
	protected String phase(MarketAPI market) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (ThreatGroundFronts.isThreatOwned(front)) {
			return "landed " + Misc.getWithDGS(Math.round(front.marines)) + " troops";
		}
		if (front != null) return "another army ashore";
		if (ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) return "fighting for the orbit";
		return ThreatGroundFronts.landingPhase(market, worldShare(), "suppressing the defences");
	}

	/**
	 * What fraction of the expedition is still flying: surviving fleet points
	 * over the points spawned when the fleets are real, or one minus the
	 * route's accumulated damage while they are abstract.
	 */
	protected float survivingStrength(CampaignFleetAPI fleet) {
		if (fleet != null || isSpawnedFleets()) {
			if (totalFPSpawned <= 0f) return 1f;
			float fp = 0f;
			for (CampaignFleetAPI curr : getFleets()) {
				if (curr == null || !curr.isAlive() || curr.isExpired()) continue;
				fp += curr.getFleetPoints();
			}
			return Math.max(0f, Math.min(1f, fp / totalFPSpawned));
		}
		try {
			if (getRoute() != null && getRoute().getExtra() != null
					&& getRoute().getExtra().damage != null) {
				return Math.max(0f, Math.min(1f, 1f - getRoute().getExtra().damage));
			}
		} catch (Throwable t) {
			// no route yet - the expedition is untouched
		}
		return 1f;
	}

	/**
	 * File the strike with the rest of the incursion intel, not just under
	 * the generic Military tab where nobody thinks to look for it.
	 */
	@Override
	public java.util.Set<String> getIntelTags(com.fs.starfarer.api.ui.SectorMapAPI map) {
		java.util.Set<String> tags = super.getIntelTags(map);
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		return tags;
	}

	/**
	 * Tell the player the counterplay exists - the hive equivalent of
	 * vanilla's "disrupting the military facilities ... will abort the raid"
	 * line, which never shows for strikes because hive colonies run forges,
	 * not military bases. See {@link IncursionManager#abortStrikesFrom}.
	 */
	@Override
	protected void addStatusSection(com.fs.starfarer.api.ui.TooltipMakerAPI info,
			float width, float height, float opad) {
		super.addStatusSection(info, width, height, opad);
		if (isEnding() || isEnded() || isAborted() || isSucceeded() || isFailed()) return;
		addSiegeStatus(info);
		if (!ThreatIncConfig.strikeRecallEnabled()) return;
		com.fs.starfarer.api.campaign.econ.MarketAPI source =
				getParams() != null ? getParams().source : null;
		if (source == null) return;
		if (!com.fs.starfarer.api.impl.campaign.ids.Factions.THREAT
				.equals(source.getFactionId())) return;
		if (isPreparing()) {
			info.addPara("The expedition is still being fabricated and fueled at "
					+ "%s. Disrupt the colony's forge or its Swarm Nexus - a raid will do - "
					+ "or bombard or destroy the colony before the fleets depart, and the "
					+ "operation is stillborn.", opad,
					com.fs.starfarer.api.util.Misc.getHighlightColor(), source.getName());
		} else {
			info.addPara("The expedition has departed and is %s - nothing done to its staging "
					+ "colony will turn it back now. It can only be met in space, or its "
					+ "target defended.", opad,
					com.fs.starfarer.api.util.Misc.getNegativeHighlightColor(), "autonomous");
		}
	}

	@Override
	protected CampaignFleetAPI createFleet(int size, float damage) {
		FabricatorEscortStrength strength;
		int fabricators = 0;

		if (size <= 4) {
			strength = FabricatorEscortStrength.LOW;
		} else if (size <= 6) {
			strength = FabricatorEscortStrength.MEDIUM;
		} else if (size <= 8) {
			strength = FabricatorEscortStrength.HIGH;
		} else {
			strength = FabricatorEscortStrength.HIGH;
			fabricators = 1; // the armada brings a fabricator
		}

		// heavy pre-spawn damage downgrades the swarm a tier
		if (damage > 0.4f && strength != FabricatorEscortStrength.LOW) {
			strength = FabricatorEscortStrength.values()[strength.ordinal() - 1];
			fabricators = 0;
		}

		CampaignFleetAPI fleet = DisposableThreatFleetManager.createThreatFleet(
				fabricators, 0, 0, strength, getRandom());

		// provision the swarm to actually bombard on arrival rather than idling
		// until the operation times out: vanilla gates live bombardment behind
		// fuel the fleet doesn't carry, so grant a large (summed per member)
		// FLEET_BOMBARD_COST_REDUCTION to zero that cost
		if (fleet != null) {
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				member.getStats().getDynamic().getMod(Stats.FLEET_BOMBARD_COST_REDUCTION)
						.modifyFlat("threatinc_strike", 100000f);
			}
			// An expedition is on a job. The swarm's fleet factory stamps every
			// Threat fleet ALLOW_LONG_PURSUIT (DisposableThreatFleetManager),
			// which turns one defender breaking off into a crossing of the
			// sector; a human raid fleet has no such reflex and neither does
			// this one. What aggression it gets is the siege leash's to grant,
			// in the orbit it is fighting for and nowhere else.
			fleet.getMemoryWithoutUpdate().unset(com.fs.starfarer.api.impl.campaign.ids.MemFlags
					.MEMORY_KEY_ALLOW_LONG_PURSUIT);
			// let the player see the incursion coming rather than only when it
			// arrives (our fleets lack the vanilla behavior script that would
			// otherwise restore detection range for a sensor-mod-equipped player)
			ThreatColonyManager.makeDetectable(fleet);
		}

		return fleet;
	}
}
