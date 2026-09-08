package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MutableCommodityQuantity;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.MilitaryResponseScript;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.procgen.themes.WarfleetAssignmentAI;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD.BombardType;
import com.fs.starfarer.api.util.Misc;

/**
 * NPC siege expedition against Threat colonies - the successor to the plain
 * saturation-purge {@code GenericRaidFGI}. Leaves {@code params.bombardment}
 * null so {@code FGRaidAction.performRaid} routes every pass (live-fleet and
 * autoresolve alike) through {@link #doCustomRaidAction}, which applies the
 * siege doctrine:
 *
 * <ul>
 * <li>THE ORBITAL DUEL (2026-09-06, the strike's doctrine mirrored -
 * docs/ground-war.md "Sieges from orbit"): a live fleet over a hive not yet
 * ready to be landed on delivers a slice of the siege instead of a pass
 * ({@link SiegeRaidAction#performRaid}, {@link #siegePass}): its fleet
 * points against the hive's ground-defence figure suppress the war-strata
 * toward the orbital floor, and the weapon growths answer with ships lost.
 * The landing waits until the strata are at the floor or the troops could
 * hold as they are ({@link ThreatGroundFronts#readyToLand}). Orbit alone
 * cannot wear the defenses deeper than the floor; the ground forces landed
 * next do - opening the door for...</li>
 * <li>...COMMANDO RAIDS against whatever on the world takes the most from
 * the hive ({@link #pickRaidTarget}: the Core, the Nexus, a port the world
 * imports through, or an economy industry that is the hive's best source of
 * something), the same targeted disruption a player raid applies - feeding
 * {@link ThreatColonyManager#computeHealth}'s decline engine with no extra
 * plumbing.</li>
 * </ul>
 *
 * <p>No saturation, ever: bombardment cannot reduce a hive's population. Once
 * the war-strata are suppressed the expedition lands its marines as a ground
 * front ({@link ThreatGroundFronts}) - ground victory is the only way a Threat
 * colony dies. Siege slices spend no pass; the pass budget is
 * siegePassesPerColony (default 4: the first lands, the rest reinforce the
 * front with whatever is still aboard - and once the marines are ashore a pass
 * with too few left to crew a raid (frontMinMarines) simply stands down rather
 * than mount a zero-marine commando raid that only gets repulsed).
 *
 * Every pass is recorded; when the expedition ends (for any reason) a
 * {@link ThreatSiegeReportIntel} sitrep is posted detailing what was done to
 * each planet, estimated marine losses, and the colonies' current state.
 */
public class ThreatPurgeFGI extends GenericRaidFGI {

	/** One ground action taken by the expedition, for the after-action report. */
	public static class SiegeActionRecord {
		public String marketId;
		public String marketName;
		/** Player-facing action name: "Tactical bombardment", "Ground landing", "Commando raid". */
		public String action;
		/** Industries hit, comma-joined; the troops landed for a ground landing. */
		public String targets;
		/** Approximate days of disruption inflicted (0 if none). */
		public int disruptDays;
		public boolean success;
		/** Estimated marine casualties - NPC ground actions are abstract. */
		public int estMarinesLost;
		public int sizeBefore;
		public boolean destroyed;
		/** Campaign timestamp of the action; the sitrep posts weeks later, on the fleets' return. */
		public long timestamp;
	}

	protected List<SiegeActionRecord> siegeActions = new ArrayList<SiegeActionRecord>();
	protected boolean reportPosted = false;
	/** True for an expedition the player paid for (player-faction fleets). */
	protected boolean playerCommissioned = false;

	/**
	 * STRATEGY LAYER (docs/strategy-layer.md): a mobilised faction's
	 * expedition carries its landing force as REAL cargo. The allotment is
	 * what was drawn from the base's reserve and is still with the
	 * expedition; while the fleets are spawned it is kept in step with what
	 * is actually aboard (a transport destroyed in space is marines lost),
	 * and while they are abstract (route mode) it is the figure the landing
	 * uses, discounted by the route's damage.
	 */
	protected boolean carriesCargo = false;
	protected float marinesAllotted = 0f;
	protected float armamentsAllotted = 0f;
	/** Loaded so far during the current spawn pass. */
	protected float marinesLoaded = 0f;
	protected float armamentsLoaded = 0f;

	public ThreatPurgeFGI(GenericRaidParams params, boolean playerCommissioned) {
		super(params);
		this.playerCommissioned = playerCommissioned;
	}

	public boolean isPlayerCommissioned() {
		return playerCommissioned;
	}

	public void setCargoAllotment(int marines, float armaments) {
		carriesCargo = true;
		marinesAllotted = Math.max(0, marines);
		armamentsAllotted = Math.max(0f, armaments);
	}

	/** Fuel and supplies drawn from the base at launch (refunded in part on return). */
	protected float fuelDrawn = 0f;
	protected float suppliesDrawn = 0f;

	public void setProvisions(float fuel, float supplies) {
		fuelDrawn = Math.max(0f, fuel);
		suppliesDrawn = Math.max(0f, supplies);
	}

	/** Set once the fleets have become real - one-way; vanilla never re-abstracts them. */
	protected boolean everSpawned = false;

	/**
	 * Hands a live expedition fleet to the returns ledger: it sails home on a
	 * tracked leg and whatever is aboard - marines, armaments - is credited to
	 * the base when it arrives. A fleet lost on the way home returns nothing.
	 */
	protected boolean sendHomeTracked(CampaignFleetAPI fleet) {
		MarketAPI base = params != null ? params.source : null;
		if (fleet == null || base == null || !fleet.isAlive() || fleet.isExpired()) return false;
		String factionId = getFaction() != null ? getFaction().getId() : null;
		return ThreatReturns.sendHome(fleet, factionId, base.getId());
	}

	/**
	 * Every road home vanilla knows - the natural return once the siege is
	 * done, an abort, a fleet cut below fleetAbortsMissionFPFraction and sent
	 * back alone - runs through here. A cargo expedition's fleets take the
	 * tracked leg instead of vanilla's untracked return-and-despawn, so the
	 * marines still aboard reach the base's reserve rather than vanishing with
	 * the hull.
	 */
	@Override
	protected void giveReturnAssignments(CampaignFleetAPI fleet) {
		// a fleet holding a capacity-ledger entry takes the tracked leg too: it
		// settles that entry on arrival (vanilla's despawning return would read
		// as a loss to the ledger)
		if (fleet != null && (carriesCargo || ThreatAidCapacity.heldBy(fleet) != null)) {
			cutLoose(fleet);
			fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);
			if (sendHomeTracked(fleet)) {
				if (trackedHome == null) trackedHome = new ArrayList<CampaignFleetAPI>();
				if (!trackedHome.contains(fleet)) trackedHome.add(fleet);
				return;
			}
		}
		super.giveReturnAssignments(fleet);
	}

	/**
	 * Fleets vanilla dropped from getFleets() while still alive (stragglers,
	 * fleets cut below fleetAbortsMissionFPFraction) and that are on tracked
	 * legs home: they still count as surviving strength for the provisions
	 * refund.
	 */
	protected List<CampaignFleetAPI> trackedHome = new ArrayList<CampaignFleetAPI>();

	/**
	 * The expedition is over, however it ended - natural return, recalled,
	 * standing down because its targets are gone, or beaten. Real fleets go
	 * home on tracked legs through ONE door, {@link #giveReturnAssignments},
	 * which vanilla calls for every fleet when the group finishes; here they
	 * are only counted, for the provisions refund. They settle on arrival:
	 * what is aboard is what survived, a fleet lost on the way returns
	 * nothing, and marines on fleets destroyed in the siege died with them. An
	 * expedition that never became real refunds its un-landed allotment less
	 * the route's damage. Fuel and supplies come back at returnRefundMult
	 * scaled by the surviving strength.
	 */
	protected void refundOnReturn() {
		if (!carriesCargo) return;
		MarketAPI base = params != null ? params.source : null;
		float keep;
		float marines = 0f;
		float armaments = 0f;
		int homebound = 0;
		if (everSpawned) {
			float fp = 0f;
			for (CampaignFleetAPI fleet : getFleets()) {
				if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
				fp += fleet.getFleetPoints();
				if (trackedHome != null && trackedHome.contains(fleet)) homebound++;
			}
			if (trackedHome != null) {
				for (CampaignFleetAPI fleet : trackedHome) {
					if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
					if (getFleets().contains(fleet)) continue;
					fp += fleet.getFleetPoints();
					homebound++;
				}
			}
			keep = baselineFP() > 0f ? Math.max(0f, Math.min(1f, fp / baselineFP())) : 0f;
		} else {
			keep = 1f - routeDamage();
			marines = marinesAllotted * keep;
			armaments = armamentsAllotted * keep;
		}
		float mult = ThreatIncConfig.returnRefundMult() * keep;
		float fuel = fuelDrawn * mult;
		float supplies = suppliesDrawn * mult;
		if (base != null) {
			ThreatReserves.deposit(base.getId(), Commodities.MARINES, marines);
			ThreatReserves.deposit(base.getId(), Commodities.HAND_WEAPONS, armaments);
			ThreatReserves.deposit(base.getId(), Commodities.FUEL, fuel);
			ThreatReserves.deposit(base.getId(), Commodities.SUPPLIES, supplies);
		}
		marinesAllotted = 0f;
		armamentsAllotted = 0f;
		fuelDrawn = 0f;
		suppliesDrawn = 0f;
		ThreatIncConfig.log("Expedition return to " + (base != null ? base.getName() : "nowhere")
				+ ": " + homebound + " fleets on tracked legs home, " + (int) marines
				+ " marines, " + (int) armaments + " armaments, " + (int) fuel + " fuel, "
				+ (int) supplies + " supplies refunded (strength " + (int) (keep * 100f) + "%)");
	}

	/**
	 * Surviving strength, 0..1: live fleet points (tracked legs home
	 * included) over what was spawned, or the route's damage while the
	 * expedition was still abstract - the same figure refundOnReturn scales
	 * by. The capacity ledger restations staged detachments at it when the
	 * expedition is over (ThreatAidCapacity.poll).
	 */
	public float survivingFraction() {
		if (!everSpawned) return Math.max(0f, 1f - routeDamage());
		float fp = 0f;
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			fp += fleet.getFleetPoints();
		}
		if (trackedHome != null) {
			for (CampaignFleetAPI fleet : trackedHome) {
				if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
				if (getFleets().contains(fleet)) continue;
				fp += fleet.getFleetPoints();
			}
		}
		return baselineFP() > 0f ? Math.max(0f, Math.min(1f, fp / baselineFP())) : 0f;
	}

	public boolean carriesCargo() {
		return carriesCargo;
	}

	/**
	 * Takes one fleet out of the expedition (the board's per-fleet Recall and
	 * Intercept, 2026-09-06). The group's spawned-strength baseline drops by
	 * the fleet's own, so the rest is not judged beaten for its absence
	 * (vanilla aborts a group below groupAbortsMissionFPFraction of what it
	 * spawned); an expedition left with no fleet ends. Returns false when the
	 * fleet is not in the group.
	 */
	public boolean detach(CampaignFleetAPI fleet) {
		return detach(fleet, true);
	}

	/**
	 * {@code endIfEmpty} false: an expedition left with no fleet finishes as
	 * vanilla's does (FGRaidAction.directFleets ends its action on an empty
	 * group, and the return action does the same) rather than as a rout - a
	 * landing fleet staying behind on Defend is the expedition succeeding,
	 * not scattering. Its spawn points move to {@link #detachedBaselineFP},
	 * so the refund and surviving-fraction sums still measure it.
	 */
	public boolean detach(CampaignFleetAPI fleet, boolean endIfEmpty) {
		if (fleet == null || !getFleets().contains(fleet)) return false;
		float spawnFP = fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP);
		if (spawnFP <= 0f) spawnFP = fleet.getFleetPoints();
		getFleets().remove(fleet);
		if (trackedHome != null) trackedHome.remove(fleet);
		if (!endIfEmpty && getFleets().isEmpty()) {
			// the last fleet out: everything still on the baseline - the fleets
			// the siege destroyed included - moves over, so baselineFP() stays
			// what was spawned and the refund reads the real losses
			detachedBaselineFP += getTotalFPSpawned();
			setTotalFPSpawned(0f);
		} else {
			if (!endIfEmpty) detachedBaselineFP += Math.min(spawnFP, getTotalFPSpawned());
			setTotalFPSpawned(Math.max(0f, getTotalFPSpawned() - spawnFP));
		}
		cutLoose(fleet);
		if (endIfEmpty && getFleets().isEmpty() && !isEnding() && !isEnded()) abort();
		return true;
	}

	/** Spawned points of fleets that left to defend a landing: still the expedition's for the refund and surviving-fraction sums. */
	protected float detachedBaselineFP = 0f;

	/** The expedition's spawned-strength baseline, fleets that stayed to defend a landing included. */
	protected float baselineFP() {
		return getTotalFPSpawned() + detachedBaselineFP;
	}

	/**
	 * THE LANDING FLEET STAYS (2026-09-07, the user: "after a siege expedition
	 * has dropped off its marines it should hold the space above the sieged
	 * planet"): the fleet whose pass landed or reinforced the front, and every
	 * other expedition fleet in the system with nothing left to land, leave
	 * the expedition for an indefinite Defend order over the world
	 * ({@link ThreatFleetOrders#adoptLandingDefend}) - holding the orbit,
	 * bombarding only while the front cannot hold. A landing takes the
	 * marines off every fleet in the system ({@link #unloadForLanding}), so
	 * for a cargo expedition that is every fleet here; one not yet arrived
	 * still carries its marines, sweeps on, and stays in its turn when it
	 * reinforces. Knob: landingDefendEnabled.
	 */
	protected void stayOnDefend(CampaignFleetAPI passing, MarketAPI market) {
		if (market == null || getFaction() == null) return;
		if (!ThreatIncConfig.landingDefendEnabled()) return;
		MarketAPI base = sourceBase();
		if (base == null) return;
		for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(getFleets())) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet != passing) {
				if (market.getContainingLocation() == null
						|| fleet.getContainingLocation() != market.getContainingLocation()) {
					continue;
				}
				// only a cargo expedition's fleets are known to be empty
				if (!carriesCargo
						|| fleet.getCargo().getMarines() >= ThreatIncConfig.frontMinMarines()) {
					continue;
				}
			}
			if (!detach(fleet, false)) continue;
			if (ThreatFleetOrders.adoptLandingDefend(fleet, getFaction(), market, base) == null) {
				giveReturnAssignments(fleet); // out of the group and refused: home, not adrift
			}
			// still one of the expedition's fleets for the refund sums (detach took it out)
			if (trackedHome == null) trackedHome = new ArrayList<CampaignFleetAPI>();
			if (!trackedHome.contains(fleet)) trackedHome.add(fleet);
		}
	}

	/**
	 * A fleet with nothing left to land joins the defence of the
	 * expedition's own front - this world's if it is ours, else the nearest
	 * of the sweep's - rather than duelling batteries over a world it can
	 * never take (2026-09-07, Gamma Hero I). False when there is no such
	 * front: the pass then goes ahead and spends itself on nothing.
	 */
	protected boolean joinDefend(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || getFaction() == null) return false;
		if (!ThreatIncConfig.landingDefendEnabled()) return false;
		MarketAPI world = ownFrontWorld(market, fleet);
		if (world == null) return false;
		stayOnDefend(fleet, world);
		return !getFleets().contains(fleet);
	}

	/** This world if the expedition's own front stands on it, else the sweep's own-front world nearest the fleet, else null. */
	protected MarketAPI ownFrontWorld(MarketAPI market, CampaignFleetAPI fleet) {
		String ourId = getFaction() != null ? getFaction().getId() : null;
		if (ourId == null) return null;
		if (market != null && ownFrontOn(market, ourId)) return market;
		if (getParams() == null || getParams().raidParams == null) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI target : getParams().raidParams.allowedTargets) {
			if (target == null || !target.isInEconomy() || target.getPrimaryEntity() == null) continue;
			if (!ownFrontOn(target, ourId)) continue;
			float dist = fleet != null && fleet.getContainingLocation() == target.getContainingLocation()
					? Misc.getDistance(fleet, target.getPrimaryEntity()) : Float.MAX_VALUE / 2f;
			if (dist < bestDist) {
				bestDist = dist;
				best = target;
			}
		}
		return best;
	}

	protected static boolean ownFrontOn(MarketAPI market, String factionId) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		return front != null && factionId.equals(ThreatGroundFronts.ownerOf(front));
	}

	/**
	 * A fleet leaving its group takes nothing of the group's mind with it:
	 * vanilla's WarfleetAssignmentAI (which captures objectives and raids on
	 * its own - seen 2026-09-06: every fleet detached to Intercept or
	 * recalled sat on the hive's comm relay instead), the raid's
	 * military-response pull and its assignments, the busy flag the raid
	 * action leaves, and the blinkers. From here on only its own orders move
	 * it, and no response script (a raid's, or a system's fight for its
	 * objectives) may borrow it.
	 */
	public static void cutLoose(CampaignFleetAPI fleet) {
		if (fleet == null) return;
		fleet.removeScriptsOfClass(WarfleetAssignmentAI.class);
		com.fs.starfarer.api.campaign.rules.MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.unset(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED);
		mem.unset(MemFlags.FLEET_MILITARY_RESPONSE);
		Misc.setFlagWithReason(mem, MemFlags.FLEET_BUSY, fleet.getId(), false, 0f);
		mem.unset(MemFlags.FLEET_BUSY);
		mem.set(MemFlags.FLEET_NO_MILITARY_RESPONSE, true);
		if (fleet.getAI() != null) {
			for (FleetAssignmentDataAPI a : fleet.getAI().getAssignmentsCopy()) {
				if (MilitaryResponseScript.RESPONSE_ASSIGNMENT.equals(a.getCustom())) {
					fleet.getAI().removeAssignment(a);
				}
			}
		}
	}

	/** Set once the capacity ledger's group entries have been split among the real fleets. */
	protected boolean ledgerSplit = false;

	/**
	 * Vanilla's incremental spawn (its default) never stamps KEY_SPAWN_FP on
	 * a fleet, so its own "cut below fleetAbortsMissionFPFraction" check
	 * never fires and {@link #detach} had nothing to take off the group's
	 * baseline: three fleets detached from four left the fourth judged
	 * beaten and the expedition aborted (2026-09-06, Gamma Hero). Stamped
	 * here the tick after each fleet becomes real. Once every fleet is real
	 * the capacity ledger's group entries are split among them
	 * ({@link ThreatAidCapacity#splitGroup}), so each settles its own share.
	 */
	protected void noteSpawnFP() {
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null) continue;
			if (fleet.getMemoryWithoutUpdate().getFloat(KEY_SPAWN_FP) <= 0f) {
				fleet.getMemoryWithoutUpdate().set(KEY_SPAWN_FP, (float) fleet.getFleetPoints());
			}
		}
		if (!ledgerSplit && isSpawnedFleets() && !isSpawning() && !getFleets().isEmpty()) {
			ledgerSplit = true;
			ThreatAidCapacity.splitGroup(this);
		}
	}

	/** Detaches one fleet and sends it home on the tracked leg (its cargo refunds on arrival). */
	public boolean recallFleet(CampaignFleetAPI fleet) {
		if (!detach(fleet)) return false;
		giveReturnAssignments(fleet);
		return true;
	}

	/** The base the expedition sailed from, or null for an abstract one. */
	public MarketAPI sourceBase() {
		return params != null ? params.source : null;
	}

	public float getMarinesAllotted() {
		return marinesAllotted;
	}

	public float getArmamentsAllotted() {
		return armamentsAllotted;
	}

	/**
	 * Spawn pass: reload the allotment onto the fresh fleets, then hand back
	 * to the base whatever would not fit - the reserve was drawn for it, and
	 * troops with no berth stay home rather than vanish.
	 */
	@Override
	protected void spawnFleets() {
		marinesLoaded = 0f;
		armamentsLoaded = 0f;
		super.spawnFleets();
		everSpawned = true;
		if (!carriesCargo) return;
		MarketAPI base = params != null ? params.source : null;
		float marinesLeft = marinesAllotted - marinesLoaded;
		float armamentsLeft = armamentsAllotted - armamentsLoaded;
		// a damaged expedition spawns fewer fleets (the missing ones were
		// destroyed): their troops are gone, not home
		if (base != null && routeDamage() <= 0f && (marinesLeft > 0f || armamentsLeft > 0f)) {
			ThreatReserves.deposit(base.getId(), Commodities.MARINES, marinesLeft);
			ThreatReserves.deposit(base.getId(), Commodities.HAND_WEAPONS, armamentsLeft);
			ThreatIncConfig.log("Expedition cargo: " + (int) marinesLeft + " marines, "
					+ (int) armamentsLeft + " armaments did not fit - returned to "
					+ base.getName());
		}
		marinesAllotted = marinesLoaded;
		armamentsAllotted = armamentsLoaded;
	}

	/** Cargo-carrying fleets sail with troop transports in the mix. */
	@Override
	protected void configureFleet(int size,
			com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission m) {
		super.configureFleet(size, m);
		if (carriesCargo && marinesAllotted > 0f) {
			m.triggerSetFleetComposition(0.1f, 0.1f,
					ThreatIncConfig.expeditionTransportMult(), 0f, 0f);
		}
	}

	/** Loads the allotment aboard, as much as each fleet's berths and holds take. */
	@Override
	protected void configureFleet(int size, CampaignFleetAPI fleet) {
		super.configureFleet(size, fleet);
		if (!carriesCargo || fleet == null) return;
		com.fs.starfarer.api.campaign.CargoAPI cargo = fleet.getCargo();
		int marines = (int) Math.min(marinesAllotted - marinesLoaded, cargo.getFreeCrewSpace());
		if (marines > 0) {
			cargo.addMarines(marines);
			marinesLoaded += marines;
		}
		int armaments = (int) Math.min(armamentsAllotted - armamentsLoaded, cargo.getSpaceLeft());
		if (armaments > 0) {
			cargo.addCommodity(Commodities.HAND_WEAPONS, armaments);
			armamentsLoaded += armaments;
		}
	}

	/** Whether any expedition fleet is currently a real, living fleet (not in route mode). */
	protected boolean anyFleetLive() {
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet != null && fleet.isAlive() && !fleet.isExpired()) return true;
		}
		return false;
	}

	/**
	 * While the fleets are real, the allotment IS what is aboard: a transport
	 * lost in space took its marines with it. Never adjusted upward.
	 */
	@Override
	protected void advanceImpl(float amount) {
		super.advanceImpl(amount);
		noteSpawnFP();
		if (!carriesCargo || !anyFleetLive()) return;
		float marines = 0f;
		float armaments = 0f;
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			marines += fleet.getCargo().getMarines();
			armaments += fleet.getCargo().getCommodityQuantity(Commodities.HAND_WEAPONS);
		}
		if (marines < marinesAllotted) marinesAllotted = marines;
		if (armaments < armamentsAllotted) armamentsAllotted = armaments;
	}

	/** Route-mode damage fraction (0 = untouched), for the abstract landing figure. */
	protected float routeDamage() {
		try {
			if (getRoute() != null && getRoute().getExtra() != null
					&& getRoute().getExtra().damage != null) {
				return Math.max(0f, Math.min(1f, getRoute().getExtra().damage));
			}
		} catch (Throwable t) {
			// no route yet
		}
		return 0f;
	}

	/**
	 * Takes the landing force off the fleets in this location (or off the
	 * abstract allotment in route mode). Returns [marines, armaments] landed.
	 */
	protected float[] unloadForLanding(MarketAPI market) {
		float marines = 0f;
		float armaments = 0f;
		if (anyFleetLive()) {
			for (CampaignFleetAPI fleet : getFleets()) {
				if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
				if (market.getContainingLocation() != null
						&& fleet.getContainingLocation() != market.getContainingLocation()) {
					continue;
				}
				com.fs.starfarer.api.campaign.CargoAPI cargo = fleet.getCargo();
				int m = cargo.getMarines();
				int a = (int) cargo.getCommodityQuantity(Commodities.HAND_WEAPONS);
				if (m > 0) cargo.removeMarines(m);
				if (a > 0) cargo.removeCommodity(Commodities.HAND_WEAPONS, a);
				marines += m;
				armaments += a;
			}
		} else {
			float keep = 1f - routeDamage();
			marines = marinesAllotted * keep;
			armaments = armamentsAllotted * keep;
		}
		marinesAllotted = Math.max(0f, marinesAllotted - marines);
		armamentsAllotted = Math.max(0f, armamentsAllotted - armaments);
		return new float[] {marines, armaments};
	}

	@Override
	protected Object readResolve() {
		super.readResolve();
		if (siegeActions == null) siegeActions = new ArrayList<SiegeActionRecord>();
		if (trackedHome == null) trackedHome = new ArrayList<CampaignFleetAPI>();
		if (siegeAnnounced == null) siegeAnnounced = new java.util.HashSet<String>();
		if (siegeResolved == null) siegeResolved = new java.util.HashSet<String>();
		// saves from before everSpawned existed: vanilla's own spawn bookkeeping
		// says whether the fleets were ever real (else a spawned expedition would
		// refund its allotment AND settle the same marines from cargo)
		if (!everSpawned && (spawnedFleets || totalFPSpawned > 0f)) everSpawned = true;
		return this;
	}

	/**
	 * The player faction defines no personNamePrefix, so the vanilla name
	 * ("Your Your purge expedition...") and description come out mangled -
	 * both get bespoke player-facing text.
	 */
	@Override
	public String getBaseName() {
		if (playerCommissioned) return "Commissioned Purge Expedition";
		return super.getBaseName();
	}

	@Override
	protected void addBasicDescription(com.fs.starfarer.api.ui.TooltipMakerAPI info,
			float width, float height, float opad) {
		if (!playerCommissioned) {
			super.addBasicDescription(info, width, height, opad);
			return;
		}
		info.addImage(getFaction().getLogo(), width, 128, opad);
		com.fs.starfarer.api.campaign.StarSystemAPI system =
				params != null && params.raidParams != null ? params.raidParams.where : null;
		String where = system != null ? "the " + system.getNameWithLowercaseTypeShort()
				: "an infested system";
		String from = params != null && params.source != null
				? params.source.getName() : "your colony";
		info.addPara("A %s you commissioned from " + from + ", operating against the Threat "
				+ "colonies of " + where + ". The expedition is autonomous: it fights with "
				+ "your faction's doctrine and blueprints, on troops and provisions drawn "
				+ "from that colony's reserve.",
				opad, com.fs.starfarer.api.util.Misc.getHighlightColor(), getNoun());
	}

	/** What the expedition is doing to this colony right now. */
	protected String siegePhase(MarketAPI market) {
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		if (front != null && getFaction() != null
				&& getFaction().getId().equals(ThreatGroundFronts.ownerOf(front))) {
			return "landed " + Misc.getWithDGS(Math.round(front.marines)) + " troops";
		}
		if (ThreatIncConfig.siegeFightsForOrbit() && getFaction() != null && isSpawnedFleets()
				&& ThreatGroundFronts.orbitContestedFor(getFaction().getId(), market)) {
			return "clearing the orbit";
		}
		return ThreatGroundFronts.landingPhase(market, abstractTroops(market), "besieging from orbit");
	}

	/**
	 * Siege status: while the expedition is working the system, one line per
	 * surviving target - what is being done to it and how much of its pass
	 * budget is spent - and, for a cargo expedition, what is still aboard.
	 */
	@Override
	protected void addStatusSection(com.fs.starfarer.api.ui.TooltipMakerAPI info,
			float width, float height, float opad) {
		super.addStatusSection(info, width, height, opad);
		if (isEnding() || isEnded() || isAborted() || isSucceeded() || isFailed()) return;
		if (getCurrentAction() == null || params == null || params.raidParams == null) return;
		java.awt.Color h = Misc.getHighlightColor();
		if (getCurrentAction() == raidAction) {
			int budget = Math.max(1, params.raidParams.raidsPerColony);
			com.fs.starfarer.api.util.CountingMap<MarketAPI> passes =
					raidAction instanceof com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction
					? ((com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction) raidAction)
							.getRaidCount() : null;
			for (MarketAPI target : params.raidParams.allowedTargets) {
				if (target == null) continue;
				if (ThreatIncData.resolveColonyMarket(target.getId()) == null) continue;
				int used = passes != null ? passes.getCount(target) : 0;
				info.addPara(target.getName() + ": %s, %s passes.", 3f, h,
						siegePhase(target), used + "/" + budget);
			}
		}
		if (carriesCargo) {
			info.addPara("Aboard: %s marines, %s heavy armaments.", 3f, h,
					Misc.getWithDGS((int) getMarinesAllotted()),
					Misc.getWithDGS((int) getArmamentsAllotted()));
		}
	}
	@Override
	public boolean hasCustomRaidAction() {
		return true;
	}

	@Override
	protected GenericPayloadAction createPayloadAction() {
		return new SiegeRaidAction(params.raidParams, params.payloadDays);
	}

	/**
	 * The siege's raid action: vanilla's, plus the orbit. While Defense Swarms
	 * hold the orbit of a world still to be raided, no pass is delivered there
	 * ({@link #canRaid} for a real fleet) and every expedition fleet in the
	 * system is made aggressive and un-blinkered so it hunts the swarms down.
	 * Vanilla's raid fleets are re-flagged every tick not to get sidetracked,
	 * which is exactly why a single swarm ship used to stall a siege for its
	 * whole stay while the passes were spent on commando raids the landing
	 * gate had refused (seen 2026-09-05: pirates and the player alike, four
	 * raids at ~1,200 marines each, no landing). Once the orbit is clear the
	 * passes resume - tactical, then the landing. Bookkeeping calls (null
	 * fleet) are untouched, so the stage and its time limit run as vanilla's.
	 * Knob: {@code siegeFightsForOrbit}.
	 */
	public static class SiegeRaidAction
			extends com.fs.starfarer.api.impl.campaign.intel.group.FGRaidAction {

		public SiegeRaidAction(FGRaidParams params, float raidDays) {
			super(params, raidDays);
		}

		protected boolean orbitHeld(MarketAPI market) {
			if (market == null || intel == null || intel.getFaction() == null) return false;
			return ThreatGroundFronts.orbitContestedFor(intel.getFaction().getId(), market);
		}

		/**
		 * The orbital duel (the strike's AnnihilationAction, mirrored): a live
		 * fleet over a hive not yet ready to be landed on delivers a slice of
		 * the siege instead of a pass, spending none; an abstract expedition
		 * runs its whole siege in one go first.
		 */
		@Override
		public void performRaid(CampaignFleetAPI fleet, MarketAPI market) {
			if (market == null || !market.isInEconomy()) return;
			if (intel instanceof ThreatPurgeFGI && ThreatGroundFronts.isHiveTarget(market)) {
				ThreatPurgeFGI purge = (ThreatPurgeFGI) intel;
				if (fleet != null) {
					if (purge.siegePass(fleet, market)) return;
				} else if (!purge.carriesCargo()
						|| purge.abstractTroops(market) >= ThreatIncConfig.frontMinMarines()) {
					// nothing to land means no siege either (siegePass's rule)
					purge.abstractSiege(market);
					// vanilla counts the pass before it asks us what to do with
					// it: a world still above the floor after the abstract siege
					// waits here, spending nothing
					if (purge.waitsAboveFloor(market)) return;
				}
			}
			super.performRaid(fleet, market);
		}

		@Override
		public boolean canRaid(CampaignFleetAPI fleet, MarketAPI market) {
			if (!super.canRaid(fleet, market)) return false;
			// a real pass asks with its fleet; the stage's bookkeeping asks with null
			if (fleet != null && ThreatIncConfig.siegeFightsForOrbit() && orbitHeld(market)) {
				return false;
			}
			return true;
		}

		/**
		 * The fight for the orbit, scoped to the orbit: a fleet within
		 * siegeHuntRange of a hive world whose Defense Swarms hold it hunts
		 * them, and every other fleet of the expedition keeps vanilla's
		 * blinkers on and stays over its target
		 * ({@link ThreatFleetOrders#siegeLeash}). Stripping the blinkers off
		 * the whole expedition, as this did, sent it after the first swarm
		 * that ran and it never came back.
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
				if (getRaidCount().getCount(target) >= p.raidsPerColony) continue;
				live.add(target);
				if (orbitHeld(target)) contested.add(target);
			}
			boolean hunt = ThreatIncConfig.siegeFightsForOrbit();
			for (CampaignFleetAPI fleet : intel.getFleets()) {
				ThreatFleetOrders.siegeLeash(fleet, contested,
						ThreatFleetOrders.nearestWorld(fleet, live), hunt, "Siege");
			}
		}
	}

	@Override
	public void doCustomRaidAction(CampaignFleetAPI fleet, MarketAPI market, float raidStr) {
		if (market == null || market.getPrimaryEntity() == null || !market.isInEconomy()) return;
		if (ThreatIncData.resolveColonyMarket(market.getId()) == null) return;

		SiegeActionRecord rec = new SiegeActionRecord();
		rec.marketId = market.getId();
		rec.marketName = market.getName();
		rec.sizeBefore = market.getSize();
		rec.timestamp = Global.getSector().getClock().getTimestamp();

		// COMBINED ARMS: the landing draws on every expedition fleet in-system,
		// not just the one whose orbit triggered the pass - one operation, one
		// ground force.
		float groundStr = combinedRaidStr(market, raidStr);

		// THE ORBITAL DUEL has to have done all it can first
		// (SiegeRaidAction.performRaid delivers the slices; a live fleet only
		// gets here once the strata are at the floor or the troops could
		// hold, an abstract expedition once its whole siege has run): a pass
		// over a world still above the floor waits rather than raids
		if (waitsAboveFloor(market)) {
			ThreatIncConfig.log("Siege pass at " + rec.marketName
					+ " waits: the war-strata are still above the floor");
			return;
		}

		// THE LANDING GATE (ThreatGroundFronts.landingBlocked, the same one the
		// strike runs): saturation fallout on the ground, another faction's
		// army holding it, or - with live fleets in the system - Defense
		// Swarms holding the orbit. (Autoresolve has no orbit to contest:
		// vanilla already skipped the colony if its defenders outweighed the
		// expedition.) A blocked pass falls through to a commando raid; the
		// tactical branch above has already taken the pass if softening still
		// helps.
		String ourId = getFaction() != null ? getFaction().getId() : null;
		String landingBlocked = ourId == null ? "no faction to land for"
				: ThreatGroundFronts.landingBlocked(ourId, market, fleet != null);
		ThreatGroundFronts.GroundFront standing = ThreatGroundFronts.getFront(market.getId());

		// REINFORCEMENT: a front of our own already stands on this world (the
		// gate has said so - a foreign one would have blocked the landing), so
		// the expedition feeds it instead of raiding - the troops and
		// armaments aboard go to the men on the ground.
		if (standing != null && landingBlocked == null) {
			float[] landed = unloadForLanding(market);
			if ((landed[0] >= 1f || landed[1] >= 1f) && ThreatGroundFronts.landOrReinforce(
					market, ourId, Math.round(landed[0]), landed[1]) != null) {
				rec.action = "Reinforced ground front";
				rec.targets = Math.round(landed[0]) + " troops, " + (int) landed[1]
						+ " heavy armaments";
				rec.success = true;
				siegeActions.add(rec);
				ThreatIncConfig.log("Siege pass (reinforce) vs " + rec.marketName + ": "
						+ rec.targets);
				stayOnDefend(fleet, market);
				return;
			}
		}
		if (landingBlocked != null) {
			ThreatIncConfig.log("Siege pass at " + rec.marketName + ": no landing - "
					+ landingBlocked + "; raiding instead");
		}

		// GROUND FRONT (docs/ground-war.md): with the war-strata softened and
		// no front on this world yet, the expedition lands a persistent ground
		// force - an NPC-owned front that pushes stratum by stratum toward the
		// Fabrication Core on its own stance AI. Only ground victory kills a
		// colony, so this is how NPC navies erase hives without the player.
		// It carries a finite armaments supply; when that runs dry it withers,
		// and the next expedition lands a fresh one.
		if (ThreatIncConfig.frontsEnabled()
				&& standing == null
				&& landingBlocked == null
				&& groundStr >= ThreatGroundFronts.grindRequirement(market)) {
			float supply = ThreatGroundFronts.landingSupply(groundStr,
					ThreatIncConfig.npcFrontSupplyDays());
			int troops = Math.round(groundStr);
			if (carriesCargo) {
				// the landing force is what is actually aboard, and the
				// armaments are the ones drawn from the base - both leave the
				// fleets for the surface (docs/strategy-layer.md)
				float[] landed = unloadForLanding(market);
				troops = Math.round(landed[0]);
				supply = landed[1];
				ThreatIncConfig.log("Landing from cargo at " + market.getName() + ": "
						+ troops + " marines, " + (int) supply + " armaments");
			}
			if (ThreatGroundFronts.landOrReinforce(market, ourId, troops, supply) != null) {
				rec.action = "Ground landing";
				rec.targets = troops + " troops, campaign against the "
						+ "Fabrication Core";
				rec.success = true;
				siegeActions.add(rec);
				ThreatIncConfig.log("Siege pass (landing) vs " + rec.marketName + ": "
						+ (int) groundStr + " troops");
				stayOnDefend(fleet, market);
				return;
			}
		}

		// A commando raid is a marine assault. Once the landing force is ashore
		// (or a fleet never carried enough), there is nothing to raid WITH -
		// don't mount, and don't report, a raid the expedition can't crew.
		// Before this guard, every pass after the landing fired a zero-marine
		// doIndustryRaid that was repulsed on the spot (user, 2026-09-06).
		// frontMinMarines is the mod's floor for a viable marine force
		// everywhere else - the player ground deploy, strike landings.
		if (groundStr < ThreatIncConfig.frontMinMarines()) {
			ThreatIncConfig.log("Siege pass at " + rec.marketName + ": no commando raid - "
					+ (int) groundStr + " marines aboard (need "
					+ (int) ThreatIncConfig.frontMinMarines() + ")");
			return;
		}

		Industry target = pickRaidTarget(market);
		if (target == null) return;
		float before = target.getDisruptedDays();
		float durMult = Global.getSettings().getFloat("punitiveExpeditionDisruptDurationMult");
		boolean ok = new MarketCMD(market.getPrimaryEntity())
				.doIndustryRaid(getFaction(), groundStr, target, durMult);
		rec.action = "Commando raid";
		rec.targets = target.getCurrentName();
		rec.success = ok;
		rec.disruptDays = ok ? (int) (target.getDisruptedDays() - before) : 0;
		if (ok && getFaction() != null) {
			ThreatAlarm.add(getFaction().getId(), ThreatIncConfig.alarmPerRaid(),
					"raid on " + market.getName());
		}
		rec.estMarinesLost = estimateMarineLosses(market, groundStr, ok);
		siegeActions.add(rec);
		ThreatIncConfig.log("Siege pass (raid) vs " + rec.marketName + ": "
				+ rec.targets + " (ground str " + (int) groundStr + ")"
				+ (ok ? " +" + rec.disruptDays + "d" : " - repulsed"));
	}

	// ---- the orbital siege (docs/ground-war.md "Sieges from orbit") ----

	/** Worlds whose siege has been recorded for the sitrep. */
	protected java.util.Set<String> siegeAnnounced = new java.util.HashSet<String>();
	/** Worlds whose siege has been run abstractly (no live fleets). */
	protected java.util.Set<String> siegeResolved = new java.util.HashSet<String>();

	/**
	 * A live fleet over a hive: a slice of the orbital siege in place of a
	 * pass, spending none. False when the pass should go ahead - a front of
	 * ours already stands here to reinforce, or the world is ready to be
	 * landed on. While Defense Swarms hold the orbit nothing happens either
	 * way: the fleets hunt them ({@link SiegeRaidAction#directFleets}).
	 */
	protected boolean siegePass(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || market == null) return false;
		if (!ThreatIncConfig.frontsEnabled()) return false;
		String ourId = getFaction() != null ? getFaction().getId() : null;
		if (ourId == null) return false;
		float troops = combinedRaidStr(market, MarketCMD.getRaidStr(fleet));
		ThreatGroundFronts.GroundFront front = ThreatGroundFronts.getFront(market.getId());
		// only a cargo expedition knows what it has left to land; the other
		// kind fights on vanilla's raid strength, which never runs out
		if (front != null) {
			// our own front and nothing aboard to reinforce it with: the fleet
			// stays over it on Defend instead of spending the pass
			if (carriesCargo && troops <= 0f && ourId.equals(ThreatGroundFronts.ownerOf(front))) {
				return joinDefend(fleet, market);
			}
			return false;
		}
		// nothing left to land: no duel over a world the expedition can never
		// take (2026-09-07, Gamma Hero I - the batteries were paid for
		// nothing). The fleet joins the defence of the front it did land;
		// with none, the pass goes ahead and spends itself on nothing
		if (carriesCargo && troops < ThreatIncConfig.frontMinMarines()) {
			if (joinDefend(fleet, market)) return true;
			ThreatIncConfig.log("Siege of " + market.getName() + ": nothing left to land, no siege");
			return false;
		}
		if (ThreatGroundFronts.orbitContestedFor(ourId, market)) {
			ThreatIncConfig.log("Siege of " + market.getName() + ": the orbit is contested");
			return true;
		}
		if (ThreatGroundFronts.readyToLand(market, troops)) return false;
		float days = ThreatGroundFronts.siegeSliceDays(fleet);
		float fp = fleet.getFleetPoints();
		float[] est = ThreatGroundFronts.siegeSliceEstimate(fp, market, days);
		float loss = ThreatGroundFronts.siegeSlice(fp, market, days);
		float removed = ThreatGroundFronts.applyFleetLosses(fleet, loss);
		if (siegeAnnounced == null) siegeAnnounced = new java.util.HashSet<String>();
		if (siegeAnnounced.add(market.getId())) {
			SiegeActionRecord rec = new SiegeActionRecord();
			rec.marketId = market.getId();
			rec.marketName = market.getName();
			rec.sizeBefore = market.getSize();
			rec.timestamp = Global.getSector().getClock().getTimestamp();
			rec.action = "Orbital siege";
			rec.targets = "war-strata suppressed from orbit";
			rec.success = true;
			siegeActions.add(rec);
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
	 * its allotted strength less route damage standing in for the fleets.
	 */
	protected void abstractSiege(MarketAPI market) {
		if (siegeResolved == null) siegeResolved = new java.util.HashSet<String>();
		if (market == null || !siegeResolved.add(market.getId())) return;
		if (ThreatGroundFronts.getFront(market.getId()) != null) return;
		float start = 0f;
		if (getParams() != null && getParams().fleetSizes != null) {
			for (Integer size : getParams().fleetSizes) {
				if (size != null) start += size * ThreatGroundFronts.ABSTRACT_FP_PER_POINT;
			}
		}
		start *= Math.max(0f, 1f - routeDamage());
		float left = ThreatGroundFronts.abstractSiege(market, start, abstractTroops(market),
				groupAbortsMissionFPFraction);
		// the batteries' toll comes off what the expedition carries
		if (start > 0f && left < start) {
			float keep = Math.max(0f, left / start);
			marinesAllotted *= keep;
			armamentsAllotted *= keep;
		}
	}

	/** The ground strength an unspawned expedition would land: its cargo, or vanilla's estimate of its raid strength. */
	protected float abstractTroops(MarketAPI market) {
		float fallback = getParams() != null && getParams().fleetSizes != null
				? IncursionManager.siegeRaidStrEstimate(getParams().fleetSizes) : 0f;
		return combinedRaidStr(market, fallback);
	}

	/** Whether a pass here should wait rather than spend itself: no front of ours yet and the world not ready to be landed on. */
	protected boolean waitsAboveFloor(MarketAPI market) {
		if (!ThreatIncConfig.frontsEnabled() || market == null) return false;
		if (ThreatGroundFronts.getFront(market.getId()) != null) return false;
		return !ThreatGroundFronts.readyToLand(market, abstractTroops(market));
	}

	/**
	 * The expedition's total ground strength against a market: summed over
	 * every live expedition fleet in the same location. Falls back to the
	 * per-fleet figure the caller received when no fleets are spawned (the
	 * autoresolve path, whose strength-derived figure is already whole-
	 * expedition scale).
	 */
	protected float combinedRaidStr(MarketAPI market, float fallback) {
		// cargo-carrying expeditions land the marines they actually brought:
		// aboard the live fleets here, or the (damage-discounted) allotment
		// while the fleets are abstract
		if (carriesCargo) {
			if (!anyFleetLive()) return marinesAllotted * (1f - routeDamage());
			float aboard = 0f;
			for (CampaignFleetAPI fleet : getFleets()) {
				if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
				if (market.getContainingLocation() != null
						&& fleet.getContainingLocation() != market.getContainingLocation()) {
					continue;
				}
				aboard += fleet.getCargo().getMarines();
			}
			return aboard;
		}
		float total = 0f;
		for (CampaignFleetAPI fleet : getFleets()) {
			if (fleet == null || !fleet.isAlive()) continue;
			if (market.getContainingLocation() != null
					&& fleet.getContainingLocation() != market.getContainingLocation()) {
				continue;
			}
			total += MarketCMD.getRaidStr(fleet);
		}
		if (total <= 0f) return fallback;
		return Math.max(total, fallback);
	}

	/** Raid value of a world's Fabrication Core: the kill, always the top prize. */
	public static final float RAID_VALUE_CORE = 100f;
	/** Raid value of its Swarm Nexus: silences garrison regrowth and staging. */
	public static final float RAID_VALUE_NEXUS = 60f;
	/** Raid value of its port, per growth input the world ships in (the trickle rule starves it). */
	public static final float RAID_VALUE_PORT_PER_IMPORT = 12f;

	/**
	 * What one unit of hive-wide availability of each commodity is worth
	 * taking away. Fuel is reach and hulls are garrisons; metals feed the
	 * forges; the raw inputs sit further up the chain.
	 */
	protected static float raidWeight(String commodityId) {
		if (Commodities.FUEL.equals(commodityId) || Commodities.SHIPS.equals(commodityId)) return 6f;
		if (Commodities.METALS.equals(commodityId) || Commodities.RARE_METALS.equals(commodityId)) return 4f;
		if (Commodities.HEAVY_MACHINERY.equals(commodityId)) return 2f;
		return 3f; // ore, rare ore, volatiles
	}

	/**
	 * The industry whose disruption takes the most from the hive, scored on
	 * this world right now - the same reasoning the mission board applies:
	 *
	 * <ul>
	 * <li>Fabrication Core: the kill (RAID_VALUE_CORE). Swarm Nexus: the
	 * garrison and staging (RAID_VALUE_NEXUS).</li>
	 * <li>Port: worth what the world ships in - each growth input it demands
	 * but does not make (RAID_VALUE_PORT_PER_IMPORT), since a disrupted port
	 * cuts shipping to a trickle. High on a forge world importing its metals,
	 * nothing on a self-sufficient mine.</li>
	 * <li>Economy industries: worth what they take from the hive. Availability
	 * is best-single-source, so a mine, refinery, fuel plant or forge only
	 * matters when THIS world is the hive's largest producer of something
	 * another hive world wants: value = weight x the gap to the second-best
	 * producer, doubled when there is no second. One of ten equal mines scores
	 * zero and is left alone; the only fuel plant, or the one big one, is hunted
	 * - which is exactly what the hive's redundancy is there to blunt.</li>
	 * </ul>
	 *
	 * Anything already carrying fresh disruption is skipped, so damage spreads
	 * across the world's organs instead of stacking on one; when every
	 * candidate is down, the one closest to recovering is hit again.
	 */
	protected Industry pickRaidTarget(MarketAPI market) {
		Industry best = null;
		float bestScore = -1f;
		Industry soonest = null;
		for (Industry ind : market.getIndustries()) {
			float score = raidValue(market, ind);
			if (score <= 0f) continue;
			if (ind.getDisruptedDays() >= 1f) {
				if (soonest == null || ind.getDisruptedDays() < soonest.getDisruptedDays()) soonest = ind;
				continue;
			}
			if (score > bestScore) {
				bestScore = score;
				best = ind;
			}
		}
		return best != null ? best : soonest;
	}

	/** This industry's raid value on this world, 0 for anything not worth a landing. */
	protected float raidValue(MarketAPI market, Industry ind) {
		if (ind == null || ind.isBuilding()) return 0f;
		String id = ind.getId();
		if (ThreatColonyManager.FABRICATION_CORE.equals(id)) return RAID_VALUE_CORE;
		if (ThreatColonyManager.SWARM_NEXUS.equals(id)) return RAID_VALUE_NEXUS;
		if (Industries.SPACEPORT.equals(id) || Industries.MEGAPORT.equals(id)) {
			int imports = 0;
			for (String input : ThreatColonyManager.growthInputs()) {
				CommodityOnMarketAPI com = market.getCommodityData(input);
				if (com != null && com.getMaxDemand() > 0 && com.getMaxSupply() <= 0) imports++;
			}
			return imports * RAID_VALUE_PORT_PER_IMPORT;
		}
		// economy industries: what the hive loses when this world's output drops out
		float value = 0f;
		for (MutableCommodityQuantity q : ind.getAllSupply()) {
			int made = q.getQuantity().getModifiedInt();
			if (made <= 0) continue;
			String c = q.getCommodityId();
			int secondBest = 0;
			boolean wanted = false;
			for (MarketAPI other : ThreatIncData.getAllLiveColonyMarkets()) {
				if (other == market) continue;
				CommodityOnMarketAPI com = other.getCommodityData(c);
				if (com == null) continue;
				if (com.getMaxDemand() > 0) wanted = true;
				secondBest = Math.max(secondBest, com.getMaxSupply());
			}
			if (!wanted || secondBest >= made) continue;
			float gap = secondBest > 0 ? made - secondBest : made * 2f;
			value += raidWeight(c) * gap;
		}
		return value;
	}

	/**
	 * Casualty ESTIMATE for the after-action report - NPC ground actions are
	 * abstract (no marines are actually simulated), so this mirrors the shape
	 * of the player-side loss math: committed strength, raid effectiveness
	 * against the hive's defenses, hazard, and the hive counter-swarm
	 * multiplier.
	 */
	protected int estimateMarineLosses(MarketAPI market, float raidStr, boolean success) {
		float marines = raidStr * 3f; // marine-equivalents behind this ground strength
		float eff = MarketCMD.getRaidEffectiveness(market, raidStr);
		float frac = 0.16f * Math.max(1f, market.getHazardValue())
				* ThreatIncConfig.hiveMarineLossMult();
		frac *= 1.5f - Math.min(1f, eff); // harder fights bleed more
		if (!success) frac *= 0.5f; // repulsed at the perimeter, not in the depths
		if (frac > 0.8f) frac = 0.8f;
		if (frac < 0f) frac = 0f;
		return Math.round(marines * frac);
	}

	/**
	 * The sitrep goes out the moment the siege operations finish - when the
	 * payload action completes and the fleets turn for home - not when they
	 * arrive weeks later. Posted on return, every clock it described had run
	 * out before anyone read it ("disrupted ~15 days" beside a colony that
	 * was already nominal again). An expedition aborted or destroyed before
	 * finishing still reports from notifyEnding.
	 */
	@Override
	protected void notifyActionFinished(com.fs.starfarer.api.impl.campaign.intel.group.FGAction action) {
		super.notifyActionFinished(action);
		if (action != null && action == raidAction && !isAborted() && !isFailed()) {
			postSiegeReport();
		}
	}

	@Override
	protected void notifyEnding() {
		super.notifyEnding();
		postSiegeReport();
		refundOnReturn();
	}

	/** Posts the after-action sitrep once, however the expedition ended. */
	protected void postSiegeReport() {
		if (reportPosted) return;
		reportPosted = true;

		String outcome;
		if (isAborted()) {
			outcome = "aborted";
		} else if (isFailed()) {
			outcome = "destroyed";
		} else {
			outcome = "completed";
		}

		String systemName = params != null && params.raidParams != null
				&& params.raidParams.where != null
				? params.raidParams.where.getNameWithLowercaseType() : "an infested system";

		ThreatSiegeReportIntel report = new ThreatSiegeReportIntel(
				getFaction() != null ? getFaction().getId() : null, systemName,
				new ArrayList<SiegeActionRecord>(siegeActions), outcome);
		Global.getSector().getIntelManager().addIntel(report);
		ThreatIncConfig.log("Siege expedition " + outcome + " (" + siegeActions.size()
				+ " ground actions) - sitrep posted.");
	}
}
