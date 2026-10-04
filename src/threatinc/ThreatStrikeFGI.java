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
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.fleets.RouteManager;
import com.fs.starfarer.api.impl.campaign.intel.group.FGAction;
import com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
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
		hideOrigin();
		undetected = ThreatIncConfig.strikeDetection() && !ThreatIncConfig.debugMode();
	}

	/**
	 * Nobody has seen this strike yet (docs/frontlines.md, "Strike warning"):
	 * the intel stays hidden - no entry, no updates, no board row - until a
	 * fleet of it is spotted (IncursionManager.detectStrikes). Negative so a
	 * strike from an older save, which never had the field, stays visible.
	 */
	protected boolean undetected = false;

	public boolean isDetected() {
		return !undetected;
	}

	/** Spotted: the intel appears with vanilla's "new intel" message. */
	public void markDetected(String by) {
		if (!undetected) return;
		undetected = false;
		if (!isEnding() && !isEnded()) {
			// "new" counts from now, not from the unseen launch
			setPlayerVisibleTimestamp(Global.getSector().getClock().getTimestamp());
			Global.getSector().getCampaignUI().addMessage(this,
					com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction.INTEL_TAB, this);
		}
		ThreatIncConfig.log("Strike on " + (getParams().raidParams.where != null
				? getParams().raidParams.where.getName() : "?") + " detected by " + by);
	}

	/** Threat news titles in the Threat's colour; ended entries keep vanilla's grey. */
	@Override
	public java.awt.Color getTitleColor(com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ListInfoMode mode) {
		java.awt.Color c = super.getTitleColor(mode);
		if (isEnded() || Misc.getGrayColor().equals(c)) return c;
		return ThreatNotice.threatColor();
	}

	@Override
	public boolean isHidden() {
		return undetected || super.isHidden();
	}

	/**
	 * Vanilla's return leg reads "returning to <source world>" on the fleets:
	 * under the fog of war that named a hive nobody had found (rc1 review).
	 */
	protected void hideOrigin() {
		if (getReturnAction() != null) getReturnAction().setTravelText("returning to the hive");
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
	 * (IncursionManager.launchStrike), spending up to
	 * IncursionManager.expeditionPasses passes on each - one a mustered swarm,
	 * and two more: what is aboard bounds what they do. What a pass DOES is {@link #doCustomRaidAction}: the
	 * swarm mirrors the siege it is subjected to - soften, land, reinforce.
	 *
	 * <p>With {@code strikeSaturationEnabled} the bombardment doctrine comes
	 * back instead: {@code raidParams.bombardment} is set, and each pass flies
	 * the bombardment every besieger flies (docs/suppression-balance.md v2) -
	 * a tactical slice for a frontier strike, saturation razing the world level
	 * by level for the rest ({@link #saturationPass}) - never vanilla's instant
	 * bombardment. Every day of it, tactical or saturation, is paid from the
	 * hive's fuel stock (2026-10-01, ThreatFuel.paysOrdnance; it poured
	 * without limit).
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
		 * The bombardment doctrine's pass is the day's slice by the rules
		 * every besieger flies (docs/suppression-balance.md v2): saturation
		 * razes a world level by level ({@link ThreatStrikeFGI#saturationPass})
		 * and spends no pass until it is gone - or razed as far as saturation
		 * goes, where destroyStoryCritical decides the killing blow as it did
		 * over vanilla's bombardment - then all of them at once, so the stage
		 * moves on; a frontier strike's tactical harassment is one slice a pass
		 * ({@link ThreatStrikeFGI#harassPass}).
		 */
		@Override
		public void performRaid(com.fs.starfarer.api.campaign.CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.econ.MarketAPI market) {
			if (market == null || !market.isInEconomy()) return;
			// an unspawned strike whose every fleet stayed over its landings has nothing left to pass with
			if (fleet == null && intel instanceof ThreatStrikeFGI && ((ThreatStrikeFGI) intel).abstractSpent()) return;

			// a forward base is a station, as vanilla's pirate base is: no
			// landing, no bombardment - the station is the base (2026-09-26)
			if (intel instanceof ThreatStrikeFGI && ThreatFrontlines.isOutpost(market)) {
				ThreatStrikeFGI strike = (ThreatStrikeFGI) intel;
				if (fleet != null) strike.stationPass(market);
				else strike.stationAssault(market);
				return;
			}

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
						// it: a world not yet ready to land on waits here, spending nothing
						if (ThreatIncConfig.frontsEnabled()
								&& ThreatGroundFronts.getFront(market.getId()) == null
								&& !ThreatGroundFronts.readyToLand(market, strike.availableLanding(market, null),
										strike.abstractOrbitDone(market))) {
							return;
						}
					}
				}
				super.performRaid(fleet, market);
				return;
			}

			if (!(intel instanceof ThreatStrikeFGI)) {
				super.performRaid(fleet, market);
				return;
			}
			// the swarm does not bombard its own
			if (Factions.THREAT.equals(market.getFactionId())) return;
			ThreatStrikeFGI strike = (ThreatStrikeFGI) intel;
			boolean done = getParams().bombardment == BombardType.SATURATION
					? strike.saturationPass(fleet, market)
					: strike.harassPass(fleet, market);
			if (done) passesSpent(market);
		}

		/**
		 * An off-screen fight costs the defenders too ({@link ThreatAbstractBattle}):
		 * both strengths read before vanilla's half of it, which charges the
		 * strike alone.
		 */
		@Override
		public void autoresolve() {
			ThreatStrikeFGI strike = !isActionFinished() && intel instanceof ThreatStrikeFGI && getParams() != null
					&& getParams().where != null && ThreatAbstractBattle.enabled() ? (ThreatStrikeFGI) intel : null;
			float str = 0f, def = 0f, before = 0f;
			java.util.Set<String> defenders = null;
			if (strike != null) {
				str = ThreatAbstractBattle.attackerStrength(strike.getFaction(), getParams().where, strike.getRoute());
				def = ThreatAbstractBattle.defenderStrength(strike.getFaction(), getParams().where);
				defenders = ThreatAbstractBattle.defenders(strike.getFaction(), getParams().where);
				before = strike.fightingFP();
			}
			super.autoresolve();
			if (strike != null) {
				ThreatAbstractBattle.fought(strike.getFaction(), getParams().where, str, def,
						before - strike.fightingFP(), "strike", defenders);
			}
		}

		/**
		 * Every pass the world has left, spent at once, and one bombardment
		 * delivered for vanilla's success fraction: the stage takes the world
		 * as done, a razed one included - vanilla would otherwise hold the
		 * fleets over a decivilized world for the stage's whole duration.
		 */
		protected void passesSpent(MarketAPI market) {
			int left = getParams().raidsPerColony - getRaidCount().getCount(market);
			if (left > 0) getRaidCount().add(market, left);
			bombardCount++;
		}

		/**
		 * The bombardment doctrine's live passes: every fleet of the strike
		 * over the world bombards, its points adding to the others' - vanilla
		 * lets only the biggest fleet in reach deliver its one instant
		 * bombardment. Otherwise vanilla's test, unchanged.
		 */
		@Override
		public boolean canRaid(CampaignFleetAPI fleet, MarketAPI market) {
			FGRaidParams p = getParams();
			if (p == null || p.bombardment == null || fleet == null) return super.canRaid(fleet, market);
			if (market == null || !market.isInEconomy()) return false;
			if (!p.allowedTargets.contains(market) && !p.allowedTargets.isEmpty() && !p.allowAnyHostileMarket) {
				return false;
			}
			if (getRaidCount().getCount(market) >= p.raidsPerColony) return false;
			if (!intel.getFleets().contains(fleet)) return false;
			boolean hostile = market.getFaction().isHostileTo(fleet.getFaction())
					|| Misc.isFleetMadeHostileToFaction(fleet, market.getFaction());
			return (p.allowNonHostileTargets || hostile) && !isActionFinished();
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
			// every doctrine clears the orbit first: a bombardment, as a landing,
			// is flown only over an orbit nothing holds against the swarm
			boolean hunt = ThreatIncConfig.siegeFightsForOrbit();
			for (CampaignFleetAPI fleet : intel.getFleets()) {
				ThreatFleetOrders.siegeLeash(fleet, contested,
						ThreatFleetOrders.anchorWorld(fleet, live), hunt, "Strike");
			}
		}
	}

	// ------------------------------------------------------------------
	// strike doctrine: soften, land, reinforce (docs/ground-war.md)
	// ------------------------------------------------------------------

	/**
	 * Under the ground doctrine every pass runs through
	 * {@link #doCustomRaidAction} - which the base class only calls when no
	 * bombardment type is set. With {@code strikeSaturationEnabled} the
	 * bombardment doctrine's passes (AnnihilationAction.performRaid) take over
	 * and this goes quiet.
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
	 * invasion is. Each world is owed an even split of the sweep, no share
	 * below {@code strikeFrontMinTroops} - a small strike lands fewer worlds,
	 * not token forces - and a front takes what it needs from the pool aboard
	 * past that (2026-09-29: the even split capped it, and a front short of
	 * holding watched the troops that would save it sail on to the next world).
	 */
	protected float troopsAllotted = 0f;
	protected float troopsAboard = 0f;
	protected boolean poolSet = false;
	/** Troops put ashore per world so far: what of its even share it has had. */
	protected Map<String, Float> landedAt = new HashMap<String, Float>();
	/** Passes that did something - a bombardment, a landing, a reinforcement - for the success fraction. */
	protected int effectivePasses = 0;

	@Override
	protected Object readResolve() {
		super.readResolve();
		hideOrigin();
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
	 * surviving expedition can still carry, then to what is left of the
	 * world's share or what its front needs now (worldNeed), whichever is
	 * more. Not yet drawn - {@link #commitLanding} takes it once the landing
	 * goes ahead.
	 */
	protected int availableLanding(MarketAPI market, CampaignFleetAPI fleet) {
		ensurePool();
		troopsAboard = Math.min(troopsAboard, troopsAllotted * survivingStrength(fleet));
		Float already = landedAt.get(market.getId());
		float room = Math.max(worldShare() - (already != null ? already : 0f), worldNeed(market));
		return Math.max(0, Math.round(Math.min(troopsAboard, room)));
	}

	/**
	 * What the world's front needs of the pool now: a beachhead that outlasts
	 * the first counter-attack (ThreatGroundFronts.beachheadTroops), or what
	 * puts the swarm's own front back over its hold line
	 * (ThreatGroundFronts.fabricateNeed). Nothing under another army.
	 */
	protected float worldNeed(MarketAPI market) {
		if (market == null) return 0f;
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front == null) return ThreatGroundFronts.beachheadTroops(market);
		if (ThreatGroundFronts.isThreatOwned(front)) return ThreatGroundFronts.fabricateNeed(front, market);
		return 0f;
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
			ThreatIncConfig.logQuiet("contested:" + market.getId(), "Siege of " + market.getName()
					+ ": the orbit is contested");
			return true;
		}
		float fp = fleet.getFleetPoints();
		// the swarm over the world bombards as one: one ratio, one answer from the guns
		float orbit = ThreatGroundFronts.orbitPoints(Factions.THREAT, market, fp);
		// its ordnance comes out of the hive's fuel stock (2026-10-01, it was
		// free): a stock that will not buy half a day ends the siege, and the
		// troops land on what orbit left if they can hold - an abstract siege's rule
		boolean dry = ThreatFuel.paysOrdnance()
				&& ThreatGroundFronts.bombardDaysFor(ThreatFuel.stock(), fp) < 0.5f;
		if (ThreatGroundFronts.readyToLand(market, troops, Factions.THREAT, dry
				|| ThreatGroundFronts.orbitSpent(market, orbit, 0f, false, ThreatGroundFronts.swarmWorth()))) return false;
		float days = ThreatGroundFronts.siegeSliceDays(fleet);
		float want = ThreatGroundFronts.bombardFuelPerDay(fp) * days;
		float paid = ThreatGroundFronts.payOrdnance(fleet, Factions.THREAT, want);
		// short of the day's ordnance it bombards the share it could pay
		if (want > 0f && paid < want) days *= paid / want;
		if (days < 0.01f) {
			ThreatIncConfig.logQuiet("siegefuel:" + market.getId(), "Siege of " + market.getName()
					+ ": no fuel in the hive's stock");
			return true;
		}
		ThreatGroundFronts.BombardDay est = ThreatGroundFronts.bombardDay(fp, market, false);
		float loss = ThreatGroundFronts.siegeSlice(fp, orbit, market, days, true, true, -1f, "Orbital bombardment");
		float removed = ThreatGroundFronts.applyFleetLosses(fleet, loss);
		if (siegeAnnounced.add(market.getId())) {
			ThreatNotice.titled("Under Siege").bad()
					.line("Threat swarms are besieging %s.", ThreatNotice.market(market))
					.line("Its defences are being suppressed from orbit.")
					.line("Its batteries are answering.")
					.send();
		}
		ThreatIncConfig.log("Siege slice vs " + market.getName() + ": " + (int) fp + " FP for "
				+ String.format("%.1f", days) + " d at " + String.format("%.1f", est.rate)
				+ " d/day, defences " + Math.round(ThreatGroundFronts.fortificationCondition(market) * 100f)
				+ "%, batteries cost " + String.format("%.1f", loss) + " FP (" + (int) removed + " removed)");
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
		float start = abstractStrength();
		// the swarm's gate (beachheadSurvives, as a purge's): it bombards until
		// the landing it can make outlasts the first counter-attack or orbit is
		// done - run N1's abstract strikes landed after 0 d and 37 of 42
		// beachheads were overrun. Each day's ordnance comes out of the hive's
		// fuel stock (2026-10-01, payOrdnance's rule), and the days it ran dry
		// short of are demand the stock did not meet
		boolean pays = ThreatFuel.paysOrdnance();
		float fuel = pays ? ThreatFuel.stock() : Float.MAX_VALUE;
		float[] out = ThreatGroundFronts.abstractSiege(market, start, Math.max(worldShare(), troopsAboard),
				groupAbortsMissionFPFraction, Factions.THREAT, fuel);
		float left = out[0];
		if (pays) {
			ThreatFuel.pay(Math.min(Math.max(0f, fuel - out[1]), ThreatFuel.stock()));
			if (out[3] > 0f) {
				ThreatFuel.unmet(ThreatGroundFronts.bombardFuelPerDay(left)
						* Math.max(0f, ThreatIncConfig.siegeOrbitDays() - out[2]), "siege of " + market.getName());
			}
		}
		abstractLeft = left;
		// the batteries' toll comes off the troops the ships were carrying
		if (start > 0f && left < start) troopsAboard *= Math.max(0f, left / start);
	}

	/** The expedition's strength in fleet points: what survives of the spawned fleets, else its planned sizes, less the hulls it broke up into troops. */
	protected float abstractStrength() {
		float start = totalFPSpawned > 0f ? totalFPSpawned * survivingStrength(null) : 0f;
		if (start <= 0f && getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) start += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		return Math.max(0f, start - fabricatedFP);
	}

	/** Abstract fleet points an unspawned strike broke up into troops for a beachhead ({@link #beachheadLanding}). */
	protected float fabricatedFP;

	/**
	 * A strike's first landing on a world, sized to outlast the world's first
	 * counter-attack (2026-09-29, overnight run N1: 200-300 troops against
	 * counter-attacks of 440-680, 37 of 42 beachheads overrun, no colony
	 * taken). Short of {@link ThreatGroundFronts#beachheadTroops}, it lands more
	 * of what it carries than the world's even share - one world taken beats
	 * three beachheads lost - then breaks hulls up into the rest at
	 * fabricateTroopsPerFP, the rate a Defend fleet feeds its front with. A
	 * strike that cannot reach the line even so keeps its troops aboard.
	 * Returns the troops to land, or -1 when it holds back.
	 */
	protected int beachheadLanding(MarketAPI market, CampaignFleetAPI fleet, int troops) {
		int need = ThreatGroundFronts.beachheadTroops(market);
		if (need <= troops) return troops;
		int carried = Math.max(troops, Math.min(Math.round(troopsAboard), need));
		int shortfall = need - carried;
		if (shortfall <= 0) return carried;
		float perFP = Math.max(0.01f, ThreatIncConfig.fabricateTroopsPerFP());
		float fp = shortfall / perFP;
		float spare = fleet != null ? fleet.getFleetPoints() - fleetFlagshipFP(fleet) : abstractStrength();
		if (!ThreatIncConfig.fabricateEnabled() || fp > spare) {
			ThreatIncConfig.log("Strike landing at " + market.getName() + " held back: " + carried
					+ " troops and " + (int) spare + " FP of hulls cannot make the " + need
					+ " a beachhead needs to outlast the first counter-attack");
			return -1;
		}
		float removed;
		if (fleet != null) {
			removed = ThreatGroundFronts.applyFleetLosses(fleet, fp);
		} else {
			fabricatedFP += fp;
			removed = fp;
		}
		int made = (int) (removed * perFP);
		// the fragments join the pool, so a landing that does not go ahead
		// keeps them aboard (availableLanding clips to the allotment)
		troopsAboard += made;
		troopsAllotted += made;
		ThreatIncConfig.log("Strike at " + market.getName() + " broke up " + (int) removed + " FP of hulls into "
				+ made + " troops: the beachhead needs " + need + ", " + carried + " aboard for it");
		// the flagship and the last hull are spared, so a live fleet can come up
		// short: it keeps what it made aboard rather than land under the line
		if (carried + made < need) return -1;
		return carried + made;
	}

	protected static float fleetFlagshipFP(CampaignFleetAPI fleet) {
		if (fleet == null || fleet.getFlagship() == null) return 0f;
		return fleet.getFlagship().getFleetPointCost();
	}

	/** Fleet points the last abstract siege left (abstractSiege); kept for saves that carry it. */
	protected float abstractLeft;

	/**
	 * Whether orbit is done for an unspawned strike: its siege of the world
	 * ran once and its commander stopped it - the days, or the guns about to
	 * break it - so the troops land on what orbit left. Read as orbitSpent
	 * alone, a siege stopped short of spent held the landing back for good:
	 * Eventide and Mazalot lost 42% and 31% of the strike and nothing landed.
	 */
	protected boolean abstractOrbitDone(MarketAPI market) {
		if (market != null && siegeResolved.contains(market.getId())) return true;
		return ThreatGroundFronts.orbitSpent(market, abstractStrength(), 0f, false, ThreatGroundFronts.swarmWorth());
	}

	// ---- the bombardment doctrine (strikeSaturationEnabled) ----

	/** The unrest the swarm's bombardment raises is put down to it. */
	protected static final String BOMBARD_REASON = "Threat bombardment";

	/**
	 * One pass of the saturation doctrine (docs/suppression-balance.md v2
	 * sections 4 and 8): the swarm razes a world by the rule every razer does -
	 * a live fleet's saturation for the days since its last pass
	 * (ThreatGroundFronts.saturationSlice: every building suppressed, the guns'
	 * answer, what it pours taking the colony a level at a time), an unspawned
	 * strike's whole razing in one go (ThreatPurgeFGI.razeAbstract). It pays
	 * what it pours from the hive's fuel stock (2026-10-01,
	 * ThreatFuel.paysOrdnance; it poured without limit): short of a tactical
	 * day it bombards the share it can pay, with none it stands, and what the
	 * stock could not pay is demand it did not meet (ThreatFuel.unmet). Nothing
	 * while the orbit is held against it: the fleets fight for it first. True
	 * once the world is done with: razed, or razed as far as saturation goes -
	 * a story-critical world stops short of its last level, where
	 * destroyStoryCritical deals the killing blow the razing refuses, as it did
	 * over vanilla's bombardment.
	 */
	protected boolean saturationPass(CampaignFleetAPI fleet, MarketAPI market) {
		if (ThreatRazing.razeable(market) <= 0) return razedAsFarAsItGoes(market);
		if (fleet != null && ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) {
			ThreatIncConfig.log("Razing of " + market.getName() + ": the orbit is contested");
			return false;
		}
		boolean pays = ThreatFuel.paysOrdnance();
		if (pays && ThreatFuel.stock() < 1f) {
			// nothing to pour: no bombardment this pass. A live fleet's day of it
			// is demand the stock did not meet (once a day); an unspawned strike
			// keeps its one go, its whole razing held as a send is
			if (fleet != null) {
				float fp = fleet.getFleetPoints();
				ThreatFuel.groundedOrdnance(fleet, Math.max(ThreatGroundFronts.bombardFuelPerDay(fp),
						ThreatRazing.deliverable(market, fp, 1f, Float.MAX_VALUE)));
			} else {
				ThreatFuel.canPay(ThreatRazing.fuelToDestroyThrough(market));
				ThreatFuel.held("a razing of " + market.getName());
			}
			ThreatIncConfig.logQuiet("razefuel:" + market.getId(), "Razing of " + market.getName()
					+ ": no fuel in the hive's stock");
			return false;
		}
		// an unspawned strike razes each world once, in one go
		if (fleet == null && !siegeResolved.add(market.getId())) return false;
		if (siegeAnnounced.add(market.getId())) {
			ThreatNotice.titled("Under Bombardment").bad()
					.line("Threat swarms are razing %s from orbit.", ThreatNotice.market(market))
					.send();
		}
		boolean destroyed;
		if (fleet != null) {
			String name = market.getName();
			float fp = fleet.getFleetPoints();
			float days = ThreatGroundFronts.siegeSliceDays(fleet);
			float fuel = Float.MAX_VALUE;
			if (pays) {
				fuel = ThreatFuel.stock();
				// the day's want: its pour, never less than the tactical day it also flies
				float tactical = ThreatGroundFronts.bombardFuelPerDay(fp) * days;
				float want = Math.max(ThreatRazing.deliverable(market, fp, days, Float.MAX_VALUE), tactical);
				ThreatFuel.unmet(want - fuel, "razing of " + name);
				// short of the tactical day it bombards the share it can pay
				if (tactical > fuel) days *= fuel / tactical;
			}
			float[] out = ThreatGroundFronts.saturationSlice(fp,
					ThreatGroundFronts.orbitPoints(Factions.THREAT, market, fp), market, days, fuel,
					true, true, -1f, Factions.THREAT, BOMBARD_REASON);
			if (pays) ThreatFuel.pay(Math.min(out[1], ThreatFuel.stock()));
			float removed = ThreatGroundFronts.applyFleetLosses(fleet, out[0]);
			destroyed = out[3] > 0f;
			ThreatIncConfig.log("Razing slice vs " + name + ": " + (int) fp + " FP for "
					+ String.format("%.1f", days) + " d poured " + (int) out[1] + " fuel, " + (int) out[2]
					+ " levels razed" + (destroyed ? ", destroyed" : "") + "; batteries cost "
					+ String.format("%.1f", out[0]) + " FP (" + (int) removed + " removed)");
		} else {
			float fuel = pays ? ThreatFuel.stock() : Float.MAX_VALUE;
			float[] out = ThreatPurgeFGI.razeAbstract(market, abstractStrength(), groupAbortsMissionFPFraction,
					fuel, Factions.THREAT, BOMBARD_REASON);
			destroyed = out[2] > 0f;
			if (pays) {
				ThreatFuel.pay(Math.min(Math.max(0f, fuel - out[1]), ThreatFuel.stock()));
				// it ran dry before the world was done: what was left to pour went unmet
				if (!destroyed && out[1] < 1f && ThreatRazing.razeable(market) > 0) {
					ThreatFuel.unmet(ThreatRazing.fuelToDestroyThrough(market), "razing of " + market.getName());
				}
			}
		}
		// razed: the teardown has run (ThreatGroundFronts.colonyRazed) and
		// nothing else on the world is touched
		if (destroyed) return true;
		markBombarded(market);
		return ThreatRazing.razeable(market) <= 0 && razedAsFarAsItGoes(market);
	}

	/**
	 * A world saturation can raze no further (story-critical, at its floor):
	 * with destroyStoryCritical the swarm deals the killing blow - the swarm
	 * does not care whose story a world matters to. Done with either way.
	 */
	protected boolean razedAsFarAsItGoes(MarketAPI market) {
		if (market.isInEconomy() && Misc.isStoryCritical(market) && ThreatIncConfig.destroyStoryCritical()) {
			ThreatGroundFronts.colonyRazed(market, Factions.THREAT);
		}
		return true;
	}

	/**
	 * One pass of a frontier strike's tactical harassment: the tactical slice
	 * (ThreatGroundFronts.siegeSlice) for the days since the fleet's last pass,
	 * or vanilla's gap between passes for an unspawned strike, and the pass is
	 * spent - a harassment is a visit, not a siege. Nothing while the orbit is
	 * held against the swarm.
	 */
	protected boolean harassPass(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet != null && ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) return false;
		float fp = fleet != null ? fleet.getFleetPoints() : abstractStrength();
		float days = fleet != null ? ThreatGroundFronts.siegeSliceDays(fleet)
				: ThreatGroundFronts.SIEGE_FIRST_SLICE_DAYS;
		float orbit = fleet != null ? ThreatGroundFronts.orbitPoints(Factions.THREAT, market, fp) : fp;
		// its ordnance from the hive's fuel stock (payOrdnance, 2026-10-01): the
		// share it can pay, and with the stock empty the visit is spent on nothing
		float want = ThreatGroundFronts.bombardFuelPerDay(fp) * days;
		float paid = ThreatGroundFronts.payOrdnance(fleet, Factions.THREAT, want);
		if (want > 0f && paid < want) days *= paid / want;
		if (days < 0.01f) {
			ThreatIncConfig.logQuiet("harassfuel:" + market.getId(), "Harassment of " + market.getName()
					+ ": no fuel in the hive's stock");
			return true;
		}
		float loss = ThreatGroundFronts.siegeSlice(fp, orbit, market, days, fleet != null, true, -1f,
				BOMBARD_REASON);
		float removed = fleet != null ? ThreatGroundFronts.applyFleetLosses(fleet, loss) : 0f;
		markBombarded(market);
		ThreatIncConfig.log("Harassment slice vs " + market.getName() + ": " + (int) fp + " FP for "
				+ String.format("%.1f", days) + " d, defences "
				+ Math.round(ThreatGroundFronts.fortificationCondition(market) * 100f) + "%, batteries cost "
				+ String.format("%.1f", loss) + " FP (" + (int) removed + " removed)");
		return true;
	}

	/** The swarm's mark on a world it bombarded (the deciv claim reads it), and any defence contract for it failed. */
	protected static void markBombarded(MarketAPI market) {
		Misc.setFlagWithReason(market.getMemoryWithoutUpdate(), MemFlags.RECENTLY_BOMBARDED,
				Factions.THREAT, true, 30f);
		ThreatAidMissionIntel.strikeLanded(market);
	}

	/**
	 * A live pass over a forward base. The fleets fight its station for real
	 * (directFleets hunts while it flies) and a station beaten in battle ends
	 * the base (ThreatFrontlines.StationListener). A station already down -
	 * disrupted, not flying - leaves the base nothing to hold it with.
	 */
	protected void stationPass(MarketAPI market) {
		if (ThreatFrontlines.stationUp(market)) return;
		effectivePasses++;
		ThreatFrontlines.stationDestroyed(market, "a Threat strike (station down)");
	}

	/**
	 * An expedition that never spawned against a forward base: vanilla has
	 * already fought it. FGRaidAction.autoresolve weighs the strike's strength
	 * against every hostile fleet in the system - the garrison and any relief
	 * included - plus the station, skips a target whose defenders are as
	 * strong, and only otherwise disrupts the station and calls performRaid.
	 * So reaching here IS the station beaten, and the base goes with it (the
	 * disruption vanilla just wrote is why an earlier version read every
	 * station here as already down). Logged in vanilla's strength units.
	 */
	protected void stationAssault(MarketAPI market) {
		if (market == null || !siegeResolved.add(market.getId())) return;
		if (market.getStarSystem() != null && market.getFaction() != null) {
			float str = com.fs.starfarer.api.impl.campaign.command.WarSimScript.getFactionStrength(
					getFaction(), market.getStarSystem());
			float def = com.fs.starfarer.api.impl.campaign.command.WarSimScript.getEnemyStrength(
					getFaction(), market.getStarSystem(), false)
					+ com.fs.starfarer.api.impl.campaign.command.WarSimScript.getStationStrength(
							market.getFaction(), market.getStarSystem(), market.getPrimaryEntity());
			ThreatIncConfig.log("Station assault on " + market.getName() + ": the strike beat its defenders"
					+ " (strength " + (int) str + " vs " + (int) def + " left after the fight)");
		}
		effectivePasses++;
		ThreatFrontlines.stationDestroyed(market, "a Threat strike");
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
	 * landing waits until orbit has done what it can - a day buys less than
	 * the defenders repair - or the troops could hold as they are
	 * ({@link ThreatGroundFronts#readyToLand}).</li>
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
		if (front == null && !ThreatGroundFronts.readyToLand(market, troops, Factions.THREAT, fleet != null
				? ThreatGroundFronts.orbitSpent(market,
						ThreatGroundFronts.orbitPoints(Factions.THREAT, market, fleet.getFleetPoints()),
						0f, false, ThreatGroundFronts.swarmWorth())
				: abstractOrbitDone(market))) {
			ThreatIncConfig.log("Strike landing at " + market.getName()
					+ " waits: bombardment still has work to do");
			return;
		}
		if (front == null && troops < ThreatIncConfig.frontMinMarines()) {
			ThreatIncConfig.log("Strike landing at " + market.getName() + " aborted: only "
					+ troops + " troops left aboard for it");
			return;
		}
		if (front == null) {
			troops = beachheadLanding(market, fleet, troops);
			if (troops < 0) return;
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
		ThreatPurgeFGI.resolveOnArrival(this);
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

	// ------------------------------------------------------------------
	// recalled to defend (ThreatPosture.recallStrikes)
	// ------------------------------------------------------------------

	/**
	 * Whether the strike can still be turned for home: not over, not
	 * stillborn, and not yet at its work - preparing at its colony or
	 * travelling out. One that has begun its payload, left a guard over a
	 * landing, or is on its way home is left alone.
	 */
	public boolean recallable() {
		if (isEnded() || isEnding() || isAborted() || isSucceeded() || isFailed() || stillborn) return false;
		if (guarded != null && !guarded.isEmpty()) return false;
		if (getParams() == null || getParams().fleetSizes == null) return false;
		if (isInPreLaunchDelay()) return true;
		FGAction curr = getCurrentAction();
		return curr != null && (PREPARE_ACTION.equals(curr.getId()) || TRAVEL_ACTION.equals(curr.getId()));
	}

	/** Where the strike is now, in hyperspace: its first live fleet's place, else - never spawned - its route's; null with neither. */
	public org.lwjgl.util.vector.Vector2f hyperLocation() {
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet != null && fleet.isAlive() && fleet.getContainingLocation() != null) {
				return fleet.getLocationInHyperspace();
			}
		}
		if (isSpawnedFleets()) return null;
		RouteManager.RouteData route = getRoute();
		if (route == null || route.getCurrent() == null) return null;
		try {
			return route.getInterpolatedHyperLocation();
		} catch (RuntimeException e) {
			// a segment with neither end: nowhere
			return null;
		}
	}

	/** The first world it is bound for, or null. */
	public MarketAPI firstTarget() {
		if (getParams() == null || getParams().raidParams == null) return null;
		for (MarketAPI target : getParams().raidParams.allowedTargets) {
			if (target != null && target.getPrimaryEntity() != null) return target;
		}
		return null;
	}

	/**
	 * RECALLED TO DEFEND (2026-10-04, the user): the strike turns for a hive
	 * system under attack. Its fleets leave the expedition, the heaviest
	 * first, each for the world still shortest of its need ({@code shortBy},
	 * counted down as they go), and join its garrison on arrival
	 * (ThreatColonyManager.sendToGarrison). A strike that never spawned is
	 * built where its route stands first, the bank settling the difference
	 * as at a spawn (spawnFleets). The expedition then ends as a withdrawal
	 * with nothing left to re-bank. Returns the fleet points sent; 0, and the
	 * strike untouched, when it could not be turned.
	 */
	public float recallTo(Map<MarketAPI, Float> shortBy) {
		if (!recallable() || shortBy == null || shortBy.isEmpty()) return 0f;
		org.lwjgl.util.vector.Vector2f at = hyperLocation();
		if (!isSpawnedFleets()) {
			if (getRoute() == null || getRoute().getCurrent() == null || getParams().fleetSizes.isEmpty()) return 0f;
			spawnFleets();
			setSpawnedFleets(true);
		}
		List<CampaignFleetAPI> turning = new ArrayList<CampaignFleetAPI>();
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet != null && fleet.isAlive()) turning.add(fleet);
		}
		java.util.Collections.sort(turning, new java.util.Comparator<CampaignFleetAPI>() {
			public int compare(CampaignFleetAPI a, CampaignFleetAPI b) {
				return b.getFleetPoints() - a.getFleetPoints();
			}
		});
		float sent = 0f;
		for (CampaignFleetAPI fleet : turning) {
			MarketAPI to = null;
			float most = -Float.MAX_VALUE;
			for (Map.Entry<MarketAPI, Float> e : shortBy.entrySet()) {
				if (e.getKey() == null || e.getKey().getPrimaryEntity() == null || e.getValue() == null) continue;
				if (e.getValue() > most) {
					most = e.getValue();
					to = e.getKey();
				}
			}
			if (to == null) break;
			// vanilla places a built fleet on its route; one it could not place starts where the route stood
			if (fleet.getContainingLocation() == null) {
				if (at == null) at = to.getLocationInHyperspace();
				Global.getSector().getHyperspace().addEntity(fleet);
				fleet.setLocation(at.x, at.y);
			}
			if (!detach(fleet)) continue;
			float fp = fleet.getFleetPoints();
			if (!ThreatColonyManager.sendToGarrison(fleet, to)) {
				giveReturnAssignments(fleet); // refused: home by vanilla's road, not adrift
				continue;
			}
			ThreatPosture.noteTransfer(to, fleet);
			shortBy.put(to, most - fp);
			sent += fp;
		}
		// a withdrawal, not a defeat (addHiddenOriginStatus, vanilla's status)
		setFailedButNotDefeated(true);
		abort();
		return sent;
	}

	/**
	 * THE LANDING FLEET STAYS (2026-09-07, the purge's rule): the fleet whose
	 * pass landed or reinforced the front leaves the expedition for a Defend
	 * station over the world ({@link ThreatSwarmDefend}) - holding the orbit,
	 * bombarding only while the front cannot hold - and the rest sweep on.
	 * An unspawned strike leaves one of its fleets the same way (guardUnspawned).
	 * Knob: landingDefendEnabled.
	 */
	protected void stayOnDefend(CampaignFleetAPI fleet, MarketAPI market) {
		if (market == null) return;
		if (!ThreatIncConfig.landingDefendEnabled()) return;
		if (fleet == null) {
			guardUnspawned(market);
			return;
		}
		if (!detach(fleet)) return;
		if (ThreatSwarmDefend.attach(fleet, market, Factions.THREAT,
				getParams() != null ? getParams().source : null) == null) {
			giveReturnAssignments(fleet); // out of the group and refused: home, not adrift
		}
	}

	/** Worlds an unspawned strike has left a guard over (guardUnspawned): one each. */
	protected Set<String> guarded;

	/**
	 * Whether an unspawned strike has left every fleet it had over its landings
	 * (guardUnspawned): its remaining passes are nobody's, as a spawned strike's
	 * whose last fleet stayed.
	 */
	public boolean abstractSpent() {
		return !isSpawnedFleets() && guarded != null && !guarded.isEmpty()
				&& getParams() != null && getParams().fleetSizes != null && getParams().fleetSizes.isEmpty();
	}

	/**
	 * Every faction guards its landing on screen or off (the user, 2026-10-02):
	 * a strike far from the player never spawns, so before this its landing was
	 * left with no fleet over it and the strike went home (hw4: 117-174
	 * "ended unspawned" a run, Swarm defend only where the test save's parked
	 * fleet spawned strikes). Its first fleet - one params.fleetSizes entry,
	 * the pack spawnFleets would build - is built now over the world and put on
	 * Defend, as the spawned fleet that landed is. The entry leaves the strike,
	 * which keeps the rest of what it held; the bank pays the guard's points
	 * beyond its share or takes back what it fell short, as at a spawn, and the
	 * guard re-banks itself on despawn (bound to the ledger, finishFleet).
	 *
	 * <p>Its share of the fleets, not one (2026-10-04, strikeGuardWhole; guardShare):
	 * a spawned strike ends with every fleet over a front (joinDefend), and an
	 * unspawned one took the rest home - hw6b, a strike of 4,219 FP left 291 FP
	 * over Chicomoztoc and 325 over Coatl, a relief of 714 FP took each orbit,
	 * and 7-12 of 10-17 landings a run were overrun.
	 */
	protected void guardUnspawned(MarketAPI market) {
		if (isSpawnedFleets() || stillborn || ledgerHome == null) return;
		if (market.getPrimaryEntity() == null || market.getPrimaryEntity().getContainingLocation() == null) return;
		if (getParams() == null || getParams().fleetSizes == null || getParams().fleetSizes.isEmpty()) return;
		if (guarded != null && guarded.contains(market.getId())) return;
		int leave = ThreatIncConfig.strikeGuardWhole() ? guardShare(market) : 1;
		for (int n = 0; n < leave && !getParams().fleetSizes.isEmpty(); n++) {
			if (!guardOne(market)) break;
		}
	}

	/**
	 * Fleets an unspawned strike leaves over this landing: an even share of
	 * what it has left over the worlds it may still land on, this one among
	 * them, rounded up - and all of them once too few troops are aboard to land
	 * anywhere else, as a spawned strike's fleets with nothing left to land
	 * join a front it did land (joinDefend).
	 */
	protected int guardShare(MarketAPI market) {
		int fleets = getParams().fleetSizes.size();
		if (troopsAboard < ThreatIncConfig.frontMinMarines() || getParams().raidParams == null) return fleets;
		int worlds = 0;
		for (MarketAPI target : getParams().raidParams.allowedTargets) {
			if (target == null || !target.isInEconomy() || target.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(target.getFactionId())) continue;
			if (target != market && guarded != null && guarded.contains(target.getId())) continue;
			worlds++;
		}
		return Math.max(1, (int) Math.ceil(fleets / (float) Math.max(1, worlds)));
	}

	/** One fleet of an unspawned strike - its first entry - built over the world and put on Defend (guardUnspawned); false when none was. */
	protected boolean guardOne(MarketAPI market) {
		List<Integer> sizes = getParams().fleetSizes;
		int planned = 0;
		for (Integer s : sizes) if (s != null) planned += s;
		Integer entry = sizes.get(0);
		float share0 = ledgerShare();
		float held = ledgerPaid * share0;
		if (entry == null || planned <= 0 || held <= 0f) return false;
		Float damage = getRoute() != null && getRoute().getExtra() != null ? getRoute().getExtra().damage : null;

		// the pack spawnFleets would have built for this entry
		List<Integer> pack = null;
		if (packs != null) {
			for (List<Integer> p : packs) {
				if (packSize(p) == entry) {
					pack = p;
					break;
				}
			}
		}
		// its share of what the strike holds, by what its swarms are expected to
		// weigh against the rest's: by size points the first, largest pack came
		// out at 1.35 times its share (run hw4d, 191 guards), the bank paying the rest
		float weight = entry, total = planned;
		if (pack != null) {
			float all = 0f;
			for (List<Integer> p : packs) all += estimateFP(p);
			float mine = estimateFP(pack);
			if (all > 0f && mine > 0f) {
				weight = mine;
				total = all;
			}
		}
		float share = held * weight / total;
		float before = ledgerBuilt;
		packsLeft = new ArrayList<List<Integer>>();
		if (pack != null) packsLeft.add(pack);
		overflow = new ArrayList<CampaignFleetAPI>();
		List<CampaignFleetAPI> built = new ArrayList<CampaignFleetAPI>();
		CampaignFleetAPI fleet = createFleet(entry, damage != null ? damage : 0f);
		if (fleet != null) built.add(fleet);
		for (CampaignFleetAPI extra : overflow) {
			finishFleet(extra);
			built.add(extra);
		}
		packsLeft = null;
		overflow = null;
		if (built.isEmpty()) return false;

		// the entry leaves the strike; fabricatedFP scales with the planned
		// points, so ledgerShare - and what the rest holds - is unchanged
		sizes.remove(0);
		if (pack != null) packs.remove(pack);
		if (planned > 0) fabricatedFP *= (planned - entry) / (float) planned;
		ledgerPaid = share0 > 0f ? Math.max(0f, ledgerPaid - share / share0) : ledgerPaid;
		float diff = (ledgerBuilt - before) - share;
		if (diff > 0f) {
			ThreatColonyManager.drawFP(ledgerHome, diff);
		} else if (diff < 0f) {
			ThreatColonyManager.creditHome(ledgerHome, -diff, ledgerNear());
		}
		if (guarded == null) guarded = new HashSet<String>();
		guarded.add(market.getId());

		com.fs.starfarer.api.campaign.SectorEntityToken world = market.getPrimaryEntity();
		for (CampaignFleetAPI guard : built) {
			world.getContainingLocation().addEntity(guard);
			guard.setLocation(world.getLocation().x, world.getLocation().y);
			if (ThreatSwarmDefend.attach(guard, market, Factions.THREAT,
					getParams().source) == null) {
				giveReturnAssignments(guard); // refused: home, not adrift
			}
		}
		ThreatIncConfig.log("Strike guard over " + market.getName() + ": the unspawned strike leaves "
				+ built.size() + " fleet(s), " + (int) (ledgerBuilt - before) + " FP, on Defend ("
				+ (int) share + " of its " + (int) held + " held); " + sizes.size() + " fleet(s) left");
		return true;
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
	 * The bombardment doctrine carries no troops, and its razing spends no
	 * pass until the world is gone: its phase alone.
	 */
	protected void addSiegeStatus(TooltipMakerAPI info) {
		if (getParams() == null || getParams().raidParams == null) return;
		boolean bombard = getParams().raidParams.bombardment != null;
		java.awt.Color h = Misc.getHighlightColor();
		if (getCurrentAction() != null && getCurrentAction() == raidAction) {
			int budget = Math.max(1, getParams().raidParams.raidsPerColony);
			CountingMap<MarketAPI> passes = raidAction instanceof FGRaidAction
					? ((FGRaidAction) raidAction).getRaidCount() : null;
			for (MarketAPI target : getParams().raidParams.allowedTargets) {
				if (target == null || !target.isInEconomy()) continue;
				if (bombard) {
					info.addPara(target.getName() + ": %s.", 3f, h, phase(target));
					continue;
				}
				int used = passes != null ? passes.getCount(target) : 0;
				info.addPara(target.getName() + ": %s, %s passes.", 3f, h, phase(target),
						used + "/" + budget);
			}
		}
		if (!bombard) info.addPara("Aboard: %s troops.", 3f, h, Misc.getWithDGS((int) getTroopsAboard()));
	}

	/** What the strike is doing to this world right now. */
	protected String phase(MarketAPI market) {
		BombardType bombard = getParams() != null && getParams().raidParams != null
				? getParams().raidParams.bombardment : null;
		if (bombard != null) {
			boolean razing = bombard == BombardType.SATURATION;
			if (passesDone(market)) return razing ? "razed as far as it goes" : "bombarded";
			if (ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) return "fighting for the orbit";
			return razing ? "razing from orbit" : "bombarding its defences";
		}
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (ThreatGroundFronts.isThreatOwned(front)) {
			return "landed " + Misc.getWithDGS(Math.round(front.marines)) + " troops";
		}
		if (front != null) return "another army ashore";
		if (ThreatGroundFronts.orbitContestedFor(Factions.THREAT, market)) return "fighting for the orbit";
		return ThreatGroundFronts.landingPhase(market, worldShare(), "suppressing the defences",
				abstractOrbitDone(market));
	}

	/** Whether every pass the world had is spent. */
	protected boolean passesDone(MarketAPI market) {
		if (!(raidAction instanceof FGRaidAction) || getParams() == null || getParams().raidParams == null) return false;
		return ((FGRaidAction) raidAction).getRaidCount().getCount(market) >= getParams().raidParams.raidsPerColony;
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
		if (originKnown()) {
			super.addStatusSection(info, width, height, opad);
		} else {
			addHiddenOriginStatus(info, width, height, opad);
		}
		if (isEnding() || isEnded() || isAborted() || isSucceeded() || isFailed()) return;
		addSiegeStatus(info);
		if (!ThreatIncConfig.strikeRecallEnabled()) return;
		com.fs.starfarer.api.campaign.econ.MarketAPI source =
				getParams() != null ? getParams().source : null;
		if (source == null) return;
		// the counterplay needs the forge found first
		if (isPreparing() && !originKnown()) return;
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

	// ------------------------------------------------------------------
	// the fog (ThreatScouts): a strike does not say where it came from
	// ------------------------------------------------------------------

	/** Whether the player may be told where this strike came from. */
	protected boolean originKnown() {
		if (ThreatIncConfig.debugMode() || !ThreatIncConfig.hiveFogOfWar()) return true;
		MarketAPI source = getParams() != null ? getParams().source : null;
		if (source == null || source.getStarSystem() == null) return true;
		return ThreatIncData.discoveredSystems().contains(source.getStarSystem().getId());
	}

	@Override
	public com.fs.starfarer.api.campaign.SectorEntityToken getMapLocation(
			com.fs.starfarer.api.ui.SectorMapAPI map) {
		if (!originKnown()) return getDestination();
		return super.getMapLocation(map);
	}

	@Override
	public List<com.fs.starfarer.api.campaign.comm.IntelInfoPlugin.ArrowData> getArrowData(
			com.fs.starfarer.api.ui.SectorMapAPI map) {
		if (!originKnown()) return null;
		return super.getArrowData(map);
	}

	@Override
	protected void addETABulletPoints(String destName, java.awt.Color destHL, boolean withDepartedText,
			float eta, ETAType type, TooltipMakerAPI info, java.awt.Color tc, float initPad) {
		if (type == ETAType.RETURNING && !originKnown()) {
			destName = "its hive";
			destHL = null;
		}
		super.addETABulletPoints(destName, destHL, withDepartedText, eta, type, info, tc, initPad);
	}

	/** Vanilla's status section with the staging world left out. */
	protected void addHiddenOriginStatus(TooltipMakerAPI info, float width, float height, float opad) {
		com.fs.starfarer.api.impl.campaign.intel.group.FGAction curr = getCurrentAction();
		if (curr == null && !isEnding() && !isSucceeded()) return;
		info.addSectionHeading("Status", faction.getBaseUIColor(), faction.getDarkUIColor(),
				com.fs.starfarer.api.ui.Alignment.MID, opad);
		String noun = getNoun();
		String forces = getForcesNoun();
		if (isEnding() && !isSucceeded()) {
			if ((isFailed() || isAborted()) && !isFailedButNotDefeated()) {
				info.addPara("The " + forces + " have been defeated and any "
						+ "remaining ships are retreating in disarray.", opad);
			} else {
				info.addPara("The " + forces + " are withdrawing.", opad);
			}
		} else if (isEnding() || isSucceeded()) {
			info.addPara("The " + noun + " was successful and the " + forces + " are withdrawing.", opad);
		} else if (isInPreLaunchDelay() || PREPARE_ACTION.equals(curr.getId())) {
			info.addPara("The " + noun + " is being prepared at an unknown hive world.", opad);
		} else if (TRAVEL_ACTION.equals(curr.getId())) {
			info.addPara("Traveling to the " + raidAction.getWhere().getNameWithLowercaseTypeShort()
					+ " from an unknown hive world.", opad);
		} else if (RETURN_ACTION.equals(curr.getId())) {
			info.addPara("Returning to its hive.", opad);
		} else if (PAYLOAD_ACTION.equals(curr.getId())) {
			addPayloadActionStatus(info, width, height, opad);
		}
	}

	// ------------------------------------------------------------------
	// the fabrication ledger (2026-09-29: closed economy)
	// ------------------------------------------------------------------
	//
	// The strike is paid for: the mustered swarms' fleet points, plus what
	// the source colony's bank drew for the re-embodied fleets weighing more
	// (IncursionManager.launchStrike). When the fleets spawn, the bank settles
	// the difference between what they came out at and what the strike still
	// held (its route damage and hulls broken into troops taken off). What
	// survives goes back into the bank: each spawned fleet as it despawns
	// home (ThreatColonyManager.bindToLedger, once per fleet), or - a strike
	// that never spawned - its abstract remainder when it ends (notifyEnding).
	// Losses are real. A strike from before the ledger (ledgerHome null) is
	// left as it was.

	/** Market id whose bank paid for the strike; null for a strike from before the ledger. */
	protected String ledgerHome;
	/** Fleet points the strike was paid: the mustered swarms plus any excess drawn. */
	protected float ledgerPaid;
	/** Fleet points createFleet built. */
	protected float ledgerBuilt;
	/** The spawn-time settle has run. */
	protected boolean ledgerSpawned;
	/** The end-time settle has run. */
	protected boolean ledgerClosed;

	/** Books the strike on its source colony's bank: paid is what the swarms and the excess drawn came to. */
	public void setLedger(String homeMarketId, float paid) {
		ledgerHome = homeMarketId;
		ledgerPaid = Math.max(0f, paid);
	}

	public float getLedgerPaid() {
		return ledgerPaid;
	}

	/** Fleet points the strike holds while it flies unspawned - an abstract route, or still mustering - and 0 once spawned or over. */
	public float abstractFP() {
		if (ledgerHome == null || stillborn || isSpawnedFleets() || isEnding() || isEnded()) return 0f;
		return ledgerPaid * ledgerShare();
	}

	/** {fabricators, escort tier} of the fleet createFleet builds for an expedition size, at the damage it spawns with. */
	public static int[] specFor(int size, float damage) {
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
		return new int[] { fabricators, strength.ordinal() };
	}

	/**
	 * What swarms of these expedition sizes (one size a swarm - never a packed
	 * fleet's total) are expected to come out at: the strike job's learned
	 * mean (ThreatColonyManager.swarmCostEstimate).
	 */
	public static float estimateFP(List<Integer> sizes) {
		float fp = 0f;
		if (sizes == null) return fp;
		for (Integer size : sizes) {
			if (size != null) {
				fp += ThreatColonyManager.swarmCostEstimate(ThreatFleetComposer.JOB_STRIKE, specFor(size, 0f));
			}
		}
		return fp;
	}

	// ------------------------------------------------------------------
	// packing: fewer, fuller fleets (2026-09-29 review)
	// ------------------------------------------------------------------
	//
	// A strike used to fly a fleet a mustered swarm - dozens of fleets from a
	// rich hive. Its swarms now fly packed into fleets of up to
	// maxShipsInAIFleet ships: params.fleetSizes holds one entry a FLEET, the
	// sum of its swarms' sizes (so every reader of the sizes' sum - troops,
	// route strength, the board's FP - reads the same total as before, and
	// every reader of the count reads real fleets), and packs holds which
	// swarms each entry is. createFleet builds the entry's swarms and merges
	// them; a swarm the fleet has no room for after all (an archetype's hull
	// mix ran to more ships than the estimate) takes the field as a fleet of
	// its own. Nothing is added or lost: the same swarms at the same sizes.

	/** The swarms (expedition sizes) each fleet embodies: one list a params.fleetSizes entry, summing to it. Null for a strike from before packing (a fleet a swarm). */
	protected List<List<Integer>> packs;
	/** packs not yet built by this spawn; not saved. */
	protected transient List<List<Integer>> packsLeft;
	/** Swarms a packed fleet had no room for in this spawn, fleets of their own placed after the rest; not saved. */
	protected transient List<CampaignFleetAPI> overflow;

	public void setPacks(List<List<Integer>> packs) {
		this.packs = packs;
	}

	/**
	 * The most ships a swarm of this spec rolls: vanilla's createThreatFleet
	 * escort ranges at their tops (7 / 11 / 27 / 26 at LOW / MEDIUM / HIGH /
	 * MAXIMUM) plus its fabricators. spec[1] is the FabricatorEscortStrength
	 * ordinal, which starts at NONE. What a launch packs by, so a pack fits
	 * whatever vanilla rolls; createFleet checks the real count.
	 */
	public static int shipsEstimate(int[] spec) {
		int[] byTier = { 0, 7, 11, 27, 26 }; // NONE, LOW, MEDIUM, HIGH, MAXIMUM
		return byTier[Math.max(0, Math.min(byTier.length - 1, spec[1]))] + Math.max(0, spec[0]);
	}

	/**
	 * A strike's swarms (an expedition size each) packed into as few fleets as
	 * maxShipsInAIFleet allows: biggest first, each into the first fleet with
	 * room for its ships (shipsEstimate), else a fleet of its own. Every swarm
	 * goes into exactly one fleet, so it ends.
	 */
	public static List<List<Integer>> pack(List<Integer> sizes) {
		int max = ThreatColonyManager.maxShipsPerFleet();
		List<Integer> sorted = new ArrayList<Integer>();
		for (Integer size : sizes) {
			if (size != null) sorted.add(size);
		}
		java.util.Collections.sort(sorted, java.util.Collections.reverseOrder());
		List<List<Integer>> out = new ArrayList<List<Integer>>();
		List<Integer> ships = new ArrayList<Integer>();
		for (int size : sorted) {
			int s = shipsEstimate(specFor(size, 0f));
			int at = -1;
			for (int i = 0; i < out.size() && at < 0; i++) {
				if (ships.get(i) + s <= max) at = i;
			}
			if (at < 0) {
				out.add(new ArrayList<Integer>());
				ships.add(0);
				at = out.size() - 1;
			}
			out.get(at).add(size);
			ships.set(at, ships.get(at) + s);
		}
		return out;
	}

	/** A pack's params.fleetSizes entry: its swarms' sizes summed. */
	public static int packSize(List<Integer> pack) {
		int sum = 0;
		for (Integer size : pack) {
			if (size != null) sum += size;
		}
		return sum;
	}

	/** Takes the next unbuilt pack whose entry is this size; null for a strike from before packing. */
	protected List<Integer> takePack(int size) {
		if (packsLeft == null) return null;
		for (int i = 0; i < packsLeft.size(); i++) {
			if (packSize(packsLeft.get(i)) == size) return packsLeft.remove(i);
		}
		return null;
	}

	// ------------------------------------------------------------------
	// stillborn (IncursionManager.abortStrikesFrom)
	// ------------------------------------------------------------------

	/** Broken in preparation: what was paid for it is forfeit. */
	protected boolean stillborn;

	/**
	 * The forge building the strike was broken before it departed: it is
	 * stillborn, and the hive loses what it mustered and paid - nothing is
	 * re-banked when it ends (notifyEnding), and fleets already embodied in
	 * orbit are unbound before vanilla's abort sends them off to despawn.
	 * Call before abort().
	 */
	public void markStillborn() {
		stillborn = true;
		for (CampaignFleetAPI fleet : getFleets()) ThreatColonyManager.unbindLedger(fleet);
		if (spawning != null) {
			for (CampaignFleetAPI fleet : spawning) ThreatColonyManager.unbindLedger(fleet);
		}
	}

	public boolean isStillborn() {
		return stillborn;
	}

	/**
	 * Share of what was paid the strike still holds while it has not spawned:
	 * one less its route damage, less the hulls broken into troops
	 * (fabricatedFP, on the planned sizes' scale).
	 */
	/** What the strike fights with now: its live fleets' points, or, never spawned, its planned points less route damage. */
	protected float fightingFP() {
		float fp = 0f;
		if (isSpawnedFleets()) {
			for (CampaignFleetAPI fleet : getFleets()) {
				if (fleet != null && fleet.isAlive()) fp += fleet.getFleetPoints();
			}
			return fp;
		}
		if (getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) fp += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		float damage = getRoute() != null && getRoute().getExtra() != null && getRoute().getExtra().damage != null
				? getRoute().getExtra().damage : 0f;
		return fp * Math.max(0f, 1f - damage);
	}

	protected float ledgerShare() {
		float planned = 0f;
		if (getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) planned += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		float share = 1f;
		try {
			if (getRoute() != null && getRoute().getExtra() != null && getRoute().getExtra().damage != null) {
				share -= getRoute().getExtra().damage;
			}
		} catch (Throwable t) {
			// no route: untouched
		}
		if (planned > 0f) share -= fabricatedFP / planned;
		return Math.max(0f, Math.min(1f, share));
	}

	/** The source's primary entity, to find the nearest live colony when the source is gone. */
	protected com.fs.starfarer.api.campaign.SectorEntityToken ledgerNear() {
		return getParams() != null && getParams().source != null ? getParams().source.getPrimaryEntity() : null;
	}

	/**
	 * Vanilla spawns the whole group at once, near the player: the fleets
	 * createFleet built are settled against what the strike still held - the
	 * bank pays what they weigh beyond it, and takes back what they fell short.
	 * A packed fleet's swarms that had no room in it take the field after the
	 * rest as fleets of their own, and the passes grow with them.
	 */
	@Override
	protected void spawnFleets() {
		ledgerBuilt = 0f;
		packsLeft = packs != null ? new ArrayList<List<Integer>>(packs) : null;
		overflow = new ArrayList<CampaignFleetAPI>();
		super.spawnFleets();
		for (CampaignFleetAPI fleet : overflow) {
			// placed as vanilla places the rest (GenericRaidFGI.spawnFleets)
			finishFleet(fleet);
			if (route != null) {
				setLocationAndCoordinates(fleet, route.getCurrent());
				fleets.add(fleet);
			}
		}
		if (!overflow.isEmpty() && getParams() != null && getParams().raidParams != null
				&& getParams().raidParams.bombardment == null) {
			// a pass for every fleet's cargo (IncursionManager.expeditionPasses)
			getParams().raidParams.raidsPerColony = Math.max(getParams().raidParams.raidsPerColony,
					IncursionManager.expeditionPasses(fleets.size()));
		}
		ThreatIncConfig.log("Strike spawned as " + fleets.size() + " fleet(s)"
				+ (overflow.isEmpty() ? "" : ", " + overflow.size() + " of them swarms a packed fleet had no room for"));
		packsLeft = null;
		overflow = null;
		// stillborn, what it was paid is forfeit already: nothing drawn or given back
		if (ledgerHome == null || ledgerSpawned || stillborn) return;
		ledgerSpawned = true;
		float held = ledgerPaid * ledgerShare();
		float diff = ledgerBuilt - held;
		if (diff > 0f) {
			ThreatColonyManager.drawFP(ledgerHome, diff);
		} else if (diff < 0f) {
			ThreatColonyManager.creditHome(ledgerHome, -diff, ledgerNear());
		}
		ThreatIncConfig.log("Strike ledger: spawned " + (int) ledgerBuilt + " FP against " + (int) held
				+ " held (" + (int) ledgerPaid + " paid); bank " + (diff >= 0f ? "drew " : "got back ")
				+ (int) Math.abs(diff));
	}

	/**
	 * The strike is over. Spawned, its fleets settle themselves as they come
	 * home; any still waiting to be placed (vanilla's incremental spawn, cut
	 * short) never flew and settle here. Never spawned, what it still held
	 * goes back into the bank now. Stillborn, nothing does (markStillborn).
	 * Once.
	 */
	@Override
	protected void notifyEnding() {
		super.notifyEnding();
		if (ledgerHome == null || ledgerClosed) return;
		ledgerClosed = true;
		if (stillborn) {
			ThreatIncConfig.log("Strike ledger: stillborn, " + (int) (ledgerPaid * ledgerShare()) + " of "
					+ (int) ledgerPaid + " FP forfeit");
			return;
		}
		if (isSpawnedFleets()) {
			// vanilla takes a fleet off this list as it places it
			if (spawning != null) {
				for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(spawning)) {
					ThreatColonyManager.settleLedger(fleet, false);
				}
			}
			return;
		}
		float fp = ledgerPaid * ledgerShare();
		String to = ThreatColonyManager.creditHome(ledgerHome, fp, ledgerNear());
		ThreatIncConfig.log("Strike ledger: ended unspawned, " + (int) fp + " of " + (int) ledgerPaid
				+ " FP re-banked" + (to == null ? " - no hive left, lost" : ""));
	}

	/**
	 * One params.fleetSizes entry: its pack's swarms (takePack; a strike from
	 * before packing, the one swarm the size is) built one by one and merged
	 * into one fleet while it has room under maxShipsInAIFleet. A swarm it has
	 * no room for goes to the overflow (spawnFleets).
	 */
	@Override
	protected CampaignFleetAPI createFleet(int size, float damage) {
		List<Integer> pack = takePack(size);
		if (pack == null) pack = java.util.Collections.singletonList(size);
		int max = ThreatColonyManager.maxShipsPerFleet();
		CampaignFleetAPI fleet = null;
		for (Integer swarmSize : pack) {
			CampaignFleetAPI swarm = createSwarm(swarmSize, damage);
			if (swarm == null) continue;
			if (fleet == null) {
				fleet = swarm;
			} else if (fleet.getFleetData().getNumMembers() + swarm.getFleetData().getNumMembers() <= max) {
				ThreatColonyManager.mergeInto(fleet, swarm);
			} else {
				stowOverflow(swarm, max);
			}
		}
		if (fleet != null) finishFleet(fleet);
		return fleet;
	}

	/** A swarm a packed fleet had no room for: into an overflow fleet with room, else a fleet of its own. */
	protected void stowOverflow(CampaignFleetAPI swarm, int max) {
		if (overflow == null) overflow = new ArrayList<CampaignFleetAPI>();
		int ships = swarm.getFleetData().getNumMembers();
		for (CampaignFleetAPI curr : overflow) {
			if (curr.getFleetData().getNumMembers() + ships <= max) {
				ThreatColonyManager.mergeInto(curr, swarm);
				return;
			}
		}
		overflow.add(swarm);
	}

	/** One swarm of an expedition size, at the damage the strike spawns with; its cost is learned for the strike job. */
	protected CampaignFleetAPI createSwarm(int size, float damage) {
		int[] spec = specFor(size, damage);
		CampaignFleetAPI swarm = ThreatFleetComposer.create(ThreatFleetComposer.JOB_STRIKE,
				spec[0], FabricatorEscortStrength.values()[spec[1]], getRandom());
		if (swarm != null) {
			ThreatColonyManager.learnSwarmCost(ThreatFleetComposer.JOB_STRIKE, spec, swarm.getFleetPoints());
		}
		return swarm;
	}

	/** A built strike fleet, whole: booked on the ledger, and fitted out for the job. */
	protected void finishFleet(CampaignFleetAPI fleet) {
		// a stillborn strike's fleets fly home unbound: what it was paid is forfeit
		if (ledgerHome != null && !stillborn) {
			ledgerBuilt += fleet.getFleetPoints();
			ThreatColonyManager.bindToLedger(fleet, ledgerHome);
		}

		// the swarm has no fuel economy: the doctrine's slices pay no fuel
		// (saturationPass), and wherever vanilla's own bombardment still asks,
		// it gates on fuel the fleet doesn't carry - a large (summed per member)
		// FLEET_BOMBARD_COST_REDUCTION zeroes that cost
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
}
