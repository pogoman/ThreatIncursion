package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker;
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.MarketCMD;
import com.fs.starfarer.api.util.Misc;

/**
 * Persistent ground fronts - the siege mechanic (docs/ground-war.md).
 *
 * <p>A front is marines + heavy armaments committed to a hive world's surface.
 * The colony is the fortress: a size-S hive is S strata deep with the
 * Fabrication Core at the center. The front holds ground, and - only when
 * ORDERED to push (player authority; NPC fronts run a simple stance AI) -
 * assaults the next stratum. Strata held subtract their share of the
 * size-anchored base defense (SwarmNexus) and of the colony's fabrication
 * (computeFabricationMult): each stratum taken weakens the hive. Taking the
 * final stratum destroys the Core and ERADICATES the colony. A hive dies
 * only to a ground victory - saturation takes no size off it
 * ({@link ThreatRazing#razes}); starvation and bombardment only make the
 * siege cheaper. The hive counter-attacks on a cadence paced by
 * the supplies it is paid (vitality with size upkeep off) and can retake
 * strata from a front too weak to hold them.
 *
 * <p>Everything ticks at flat daily rates on the colony poll: armaments burn
 * as upkeep (the stockpile IS the supply countdown, and a dry front fights at
 * reduced effectiveness), marines attrit (worse while pushing or dry), and a
 * strong enough front additionally SUPPRESSES structures by feeding their
 * disruption clocks by its advantage - the mod's existing wear mechanic.
 *
 * <p><b>The rule is symmetric.</b> The same engine runs the other way round:
 * a Threat strike softens an inhabited world and lands a front of its own
 * ({@code factionId = Factions.THREAT}, see {@link ThreatStrikeFGI}), which
 * pushes that colony's strata and destroys it only by taking the last one.
 * Where a hive's strata loss is applied inside {@link SwarmNexus#apply} and
 * read by {@link HiveVitalityCondition}, a human colony carries the hidden
 * {@link ThreatSiegeMalus} structure and the {@link ThreatGroundWarCondition}
 * tooltip instead ({@link #syncSiegeState}). Every figure the engine measures
 * against - defender strength, counter-attack cadence, what a front
 * suppresses, what holds the orbit, what the fall of the last stratum means -
 * is asked of the {@link Theatre} the world belongs to ({@link #HIVE} or
 * {@link #COLONY}), never branched on in place. The purge and the strike
 * land through the same three calls ({@link #needsSoftening},
 * {@link #landingBlocked}, {@link #landOrReinforce}).
 *
 * <p>Stateless like ThreatColonyManager - all state lives in persistent data
 * via {@link #fronts()}; IncursionManager drives {@link #poll}.
 */
public class ThreatGroundFronts {

	public static final String KEY_FRONTS = "threatinc_groundFronts";

	/**
	 * Market memory flag: the faction id that took this colony by ground
	 * victory, read by IncursionManager.processPendingDecivChecks to give the
	 * kill its provenance (the swarm comes back for its own ruins). The mod's
	 * own flag, so vanilla's bombardment flag keeps its meaning.
	 */
	public static final String KILLED_BY_FLAG = "$threatinc_killedBy";

	/** Strong enough to suppress every key structure - the siege proper. */
	public static final String STATE_HOLDING = "holding";
	/** Only strong enough to harass the defense structures (reduced rate). */
	public static final String STATE_GRINDING = "grinding";
	/** Dug in and surviving, but suppressing nothing. */
	public static final String STATE_FOOTHOLD = "foothold";

	/** Default stance: dig in, defend what is held. */
	public static final String STANCE_ENTRENCH = "entrench";
	/** Assault the next stratum - ordered explicitly, reverts on completion. */
	public static final String STANCE_PUSH = "push";
	/**
	 * The checkpoint after each stratum falls: the front consolidates, requests
	 * reinforcement, and waits frontCheckpointDays for orders. Told nothing, it
	 * pushes on by doctrine; ordered to entrench it stands fast until told
	 * otherwise; or it can be pulled out entirely (which requires controlling
	 * the space around the planet - the dock gating enforces that physically).
	 */
	public static final String STANCE_CONSOLIDATE = "consolidate";

	/** One deployed front. Serialized into the save via persistent data. */
	public static class GroundFront {
		public String marketId;
		/** Owner faction id; Factions.PLAYER for the player's own front. */
		public String factionId;
		public float marines;
		public float armaments;
		public float entrenchDays;
		public String state = STATE_FOOTHOLD;
		public String stance = STANCE_ENTRENCH;
		/** Strata taken, of the colony's size; the Core falls with the last. */
		public int strataHeld;
		/** Days of push progress toward the next stratum (at ratio-scaled pace). */
		public float pushProgress;
		/** Checkpoint countdown while consolidating; at 0 the front pushes on. */
		public float consolidateDaysLeft;
		public long deployedTimestamp;
		public long lastCounterAttack;
		/** Unused since the progress notices went (2026-09-27); kept so saves load. */
		public String announcedState;
		/** Unused since the progress notices went (2026-09-27); kept so saves load. */
		public boolean announcedDry;
		/** Peak marine strength (landing plus reinforcements) - what supply runs reinforce toward. */
		public float marinesLanded;
		/** The front has asked to be pulled out (NPC stance AI, or the board's Pull out order). */
		public boolean withdrawRequested;
		/**
		 * Days the swarm has held the orbit over this (enemy) front unopposed -
		 * how long it has been bombarding it ({@link #tickSwarmBombard}). Was the
		 * scour countdown before 2026-09-08; the field is kept under its old name
		 * because removing one breaks every save that holds it.
		 */
		public float swarmOrbitDays;
		/**
		 * The fleet points an unspawned expedition leaves over its landing
		 * (vanilla autoresolve spawns no fleets to put on DEFEND): it contests
		 * the orbit while it outweighs the swarm there, and is lost for good
		 * once outweighed (swarmOrbitContested). 0 for none.
		 */
		public float coverFP;
		/** Unused since the progress notices went (2026-09-27); kept so saves load. */
		public boolean announcedScour;
		/** Days the front has stood, by the tick's own clock - what "landed N days ago" reads. */
		public float daysAlive;
		/** When the armaments ran out (0 = supplied): a dry Threat front holds for the next expedition, then makes its final push. */
		public long dryTimestamp;
		public boolean finalPush;
		/**
		 * Veterancy pool of this front's marines, on vanilla's shape: absolute,
		 * clamped to {@link #marines}, so the level is {@code xp / marines}
		 * ({@link ThreatMarineXP}). A player landing inherits the fleet's rank
		 * and writes what it earned back on withdrawal; reinforcement dilutes
		 * it for free. 0 on older saves - a green front, which is what one that
		 * has never been counter-attacked is.
		 */
		public float xp;
		/**
		 * The front broke off its own assault to take cover before an imminent
		 * counter-attack ({@link #shouldBrace}), and resumes once the blow has
		 * landed. False on older saves, which is the right default: a front
		 * that was pushing when the save was written was not bracing.
		 */
		public boolean bracedFromPush;
		/**
		 * Push progress held over a brace. Both {@link #orderPush} and
		 * {@link #orderEntrench} zero {@code pushProgress}, so without this a
		 * front that took cover restarted its assault from nothing every
		 * counter-attack cycle - and with a district push longer than the
		 * cadence it could never finish one at all. A brace is a pause, not an
		 * abandonment.
		 */
		public float bracedPushProgress;

		public boolean isPlayerOwned() {
			return factionId == null || Factions.PLAYER.equals(factionId);
		}
	}

	@SuppressWarnings("unchecked")
	public static Map<String, GroundFront> fronts() {
		Object val = Global.getSector().getPersistentData().get(KEY_FRONTS);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, GroundFront>();
			Global.getSector().getPersistentData().put(KEY_FRONTS, val);
		}
		return (Map<String, GroundFront>) val;
	}

	public static GroundFront getFront(String marketId) {
		if (marketId == null) return null;
		return fronts().get(marketId);
	}

	public static boolean hasFront(MarketAPI market) {
		return market != null && getFront(market.getId()) != null;
	}

	/** Strata the front on this market holds; 0 when no front. */
	public static int strataHeld(String marketId) {
		GroundFront front = getFront(marketId);
		return front != null ? front.strataHeld : 0;
	}

	// ------------------------------------------------------------------
	// whose ground is this: the engine runs on hive worlds AND human ones
	// ------------------------------------------------------------------

	/**
	 * The market a front is standing on, whoever owns it. Fronts used to exist
	 * only on hive worlds, so the engine resolved through
	 * {@link ThreatIncData#resolveColonyMarket} - which returns null for
	 * anything not flying the Threat flag, and would therefore delete a Threat
	 * front on a human world on the next poll. A hive target still goes through
	 * the old path (so a hive that changed hands or left the economy still
	 * counts as gone); anything else is simply the live market.
	 */
	public static MarketAPI resolveMarket(String marketId) {
		if (marketId == null) return null;
		MarketAPI hive = ThreatIncData.resolveColonyMarket(marketId);
		if (hive != null) return hive;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market == null || !market.isInEconomy()) return null;
		if (Factions.THREAT.equals(market.getFactionId())) return null; // hive path said no
		if (ThreatMapFog.conditionOnly(market)) return null;
		return market;
	}

	/** Whether the ground being fought over is a hive's (the original case). */
	public static boolean isHiveTarget(MarketAPI market) {
		return market != null && Factions.THREAT.equals(market.getFactionId());
	}

	/** Whether the swarm itself landed this front (docs/ground-war.md). */
	public static boolean isThreatOwned(GroundFront front) {
		return front != null && Factions.THREAT.equals(front.factionId);
	}

	/** The front's owner as a faction id - never null (fronts from older saves stored the player as null). */
	public static String ownerOf(GroundFront front) {
		return front == null || front.factionId == null ? Factions.PLAYER : front.factionId;
	}

	/** The front's owner as a faction, for a notice's crest and name (the player's for a player front). */
	protected static FactionAPI ownerFaction(GroundFront front) {
		return Global.getSector().getFaction(ownerOf(front));
	}

	/** The front's world by name for the log - its market may already have left the economy. */
	protected static String worldName(GroundFront front) {
		MarketAPI market = Global.getSector().getEconomy().getMarket(front.marketId);
		if (market != null) return market.getName();
		String world = ThreatBases.worldName(front.marketId);
		return world != null ? world : front.marketId;
	}

	/**
	 * THE THEATRE: everything about a front that depends on whose ground it
	 * stands on, in one object, so the tick and the readouts ask it instead of
	 * branching themselves. Two theatres exist - a hive world under a faction's
	 * siege (the original case) and a human colony under the swarm's - and a
	 * third (hive-side fronts on core worlds, say) is a third subclass, not a
	 * third branch at every site. The front's OWNER is the other axis - who
	 * withdraws where, whose voice the messages use, whether the player is
	 * told - and that stays on the front ({@link #ownerOf}).
	 */
	public static abstract class Theatre {
		public static Theatre of(MarketAPI market) {
			return isHiveTarget(market) ? HIVE : COLONY;
		}

		/** What the world fights a landing with - the one figure every requirement, push pace and counter-attack is measured against. */
		public abstract float defenderStrength(MarketAPI market);
		/**
		 * What the world can actually put INTO a counter-attack, which is not
		 * the same as what it defends with (2026-09-08). A colony's marines
		 * hold a line far better than they mount an offensive, so they enter
		 * this figure at {@code marineCounterAttackMult}; its garrison proper
		 * enters whole. A hive counter-attacks with everything it has.
		 */
		public abstract float counterAttackStrength(MarketAPI market);
		/** Days between the world's counter-attacks, for the front facing it. */
		public abstract float counterAttackInterval(GroundFront front, MarketAPI market);
		/** What a HOLDING front suppresses. */
		public abstract List<Industry> keyStructures(MarketAPI market);
		/** What a GRINDING front harasses: the defense structures alone. */
		public abstract List<Industry> defenseStructures(MarketAPI market);
		/** What a front holding districts has seized besides the key structures (pinned, not worn). */
		public abstract List<Industry> seizedStructures(MarketAPI market);
		/** What one layer of the world is called: a hive's stratum, a colony's district. */
		public abstract String layer();

		/** The structures orbit suppresses and the batteries among them answer with (docs/ground-war.md "Sieges from orbit"). */
		public abstract List<Industry> fortifications(MarketAPI market);
		/** Disruption days at which a fortification has worn to nothing. */
		public abstract float wearDays();
		/** Disruption days an unopposed fleet adds per day at ratio 1. */
		public abstract float suppressDaysPerDay();
		/** The batteries' share of the defence figure: what answers a fleet in orbit. */
		public abstract float batteryShare(MarketAPI market);
		/** The same share with every gun intact: what a fleet pays to fabricate ground troops from its hulls. */
		public abstract float intactBatteryShare(MarketAPI market);
		/** 1 intact .. 0 fully suppressed, from the structure's clock. */
		public abstract float condition(MarketAPI market, Industry ind);
		/** Whether the space over the world is held against a front of this owner. */
		public abstract boolean orbitHeldAgainst(String ownerFactionId, MarketAPI market);
		/** Whether the strata loss needs a carrier of its own on the market (the siege malus and the tooltip). */
		public abstract boolean carriesSiegeState();
		/** The last stratum has fallen. */
		public abstract void victory(GroundFront front, MarketAPI market);
	}

	/**
	 * A hive world under siege. Its strata loss lives inside SwarmNexus.apply
	 * and its tooltip is HiveVitalityCondition, so the market carries nothing
	 * extra; its counter-attacks pace on the supplies it is paid
	 * (hiveCounterAttackPace); its orbit is contested by its Defense Swarms;
	 * its fall is eradication.
	 */
	public static final Theatre HIVE = new Theatre() {
		public float defenderStrength(MarketAPI market) {
			// hive worlds have no marine logistics, so forBombard changes
			// nothing for them and the flag stays true for continuity
			return MarketCMD.getDefenderStr(market, true);
		}
		public float counterAttackStrength(MarketAPI market) {
			// no marines to hold back: the hive throws its whole body at the
			// beachhead, which is what it has always done
			return defenderStrength(market);
		}
		public float counterAttackInterval(GroundFront front, MarketAPI market) {
			// starve it and the counterstroke never comes - the supplies it is
			// paid still pace the hive underneath - and having the body to spare
			// speeds it up on top, the same as a colony (user, 2026-09-08)
			return ThreatIncConfig.frontCounterAttackDays()
					/ Math.max(0.25f, hiveCounterAttackPace(market))
					/ counterAttackTempo(front, market);
		}
		public List<Industry> keyStructures(MarketAPI market) {
			// the named organs - Core, Nexus, the military tier, port and both
			// defense structures
			List<Industry> list = new ArrayList<Industry>();
			Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
			if (core != null) list.add(core);
			Industry nexus = market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
			if (nexus != null) list.add(nexus);
			Industry bastion = SwarmBastion.of(market);
			if (bastion != null) list.add(bastion);
			Industry port = ThreatColonyManager.getPort(market);
			if (port != null) list.add(port);
			list.addAll(defenseStructures(market));
			return list;
		}
		public List<Industry> defenseStructures(MarketAPI market) {
			List<Industry> list = new ArrayList<Industry>();
			Industry gd = market.getIndustry(ThreatColonyManager.THREAT_GROUND_DEFENSES);
			if (gd != null) list.add(gd);
			Industry hb = market.getIndustry(ThreatColonyManager.THREAT_HEAVY_BATTERIES);
			if (hb != null) list.add(hb);
			return list;
		}
		public List<Industry> seizedStructures(MarketAPI market) {
			return new ArrayList<Industry>(); // a hive's strata strip fabrication, not industries
		}
		public String layer() {
			return "stratum";
		}
		public List<Industry> fortifications(MarketAPI market) {
			// the war-strata: both defence structures and the Nexus that
			// commands them, and the Swarm Bastion or Swarm Command over it
			// (SwarmBastion, 2026-10-01) - orbit wears them, none of them fires
			List<Industry> list = defenseStructures(market);
			Industry nexus = market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
			if (nexus != null) list.add(nexus);
			Industry bastion = SwarmBastion.of(market);
			if (bastion != null) list.add(bastion);
			for (java.util.Iterator<Industry> it = list.iterator(); it.hasNext();) {
				Industry ind = it.next();
				if (ind.isBuilding() && !ind.isUpgrading()) it.remove();
			}
			return list;
		}
		public float wearDays() {
			return Math.max(1f, ThreatIncConfig.defenseWearDays());
		}
		public float suppressDaysPerDay() {
			return ThreatIncConfig.hiveSiegeSuppressDaysPerDay();
		}
		public float batteryShare(MarketAPI market) {
			// the weapon growths' share of the figure, on the hive's own inputs
			float mult = 1f;
			for (Industry ind : defenseStructures(market)) {
				if (ind.isBuilding() && !ind.isUpgrading()) continue;
				mult *= 1f + hiveFortificationBonus(ind) * condition(market, ind);
			}
			return mult <= 1f ? 0f : 1f - 1f / mult;
		}
		public float intactBatteryShare(MarketAPI market) {
			float mult = 1f;
			for (Industry ind : defenseStructures(market)) {
				if (ind.isBuilding() && !ind.isUpgrading()) continue;
				mult *= 1f + hiveFortificationBonus(ind);
			}
			return mult <= 1f ? 0f : 1f - 1f / mult;
		}
		public float condition(MarketAPI market, Industry ind) {
			return ThreatColonyManager.disruptedDefenseResilience(ind);
		}
		public boolean orbitHeldAgainst(String ownerFactionId, MarketAPI market) {
			// its Defense Swarms at the planet, weighed against what the
			// besieger has there (orbitHeld) - not a head-count of the
			// garrison list, which the hive refills every poll
			return orbitHeld(ownerFactionId, market, hostilePointsNear(ownerFactionId, market));
		}
		public boolean carriesSiegeState() {
			return false;
		}
		public void victory(GroundFront front, MarketAPI market) {
			hiveGroundVictory(front, market);
		}
	};

	/**
	 * A human colony under the swarm's assault. It has no organ to hang the
	 * strata loss on, so it carries {@link ThreatSiegeMalus} and the
	 * {@link ThreatGroundWarCondition} tooltip while the front stands
	 * ({@link #syncSiegeState}); its counter-attacks pace on stability and a
	 * military command; its orbit is held by its station and any armed fleet
	 * that would fight the invader; its fall is decivilisation in the swarm's
	 * name.
	 */
	public static final Theatre COLONY = new Theatre() {
		public float defenderStrength(MarketAPI market) {
			return colonyGarrison(market) + colonyMarineDefense(market, 1f);
		}
		public float counterAttackStrength(MarketAPI market) {
			// the garrison mounts the counter-attack; the stockpile's marines
			// mostly hold the line they are standing on
			return colonyGarrison(market)
					+ colonyMarineDefense(market, ThreatIncConfig.marineCounterAttackMult());
		}
		public float counterAttackInterval(GroundFront front, MarketAPI market) {
			// unrest, shortages and the shock of an invasion are what stop a
			// garrison organising; a staff to run the operation speeds it up
			float interval = ThreatIncConfig.frontCounterAttackDays()
					/ Math.max(0.25f, market.getStabilityValue() / 10f);
			if (hasMilitaryCommand(market)) {
				interval /= Math.max(0.1f, ThreatIncConfig.colonyCounterAttackMilitaryMult());
			}
			// and troops to spare: mustering an offensive is a question of how
			// badly the world outnumbers what is on its soil (user, 2026-09-08)
			return interval / counterAttackTempo(front, market);
		}
		public List<Industry> keyStructures(MarketAPI market) {
			return colonyKeyStructures(market);
		}
		public List<Industry> defenseStructures(MarketAPI market) {
			List<Industry> list = new ArrayList<Industry>();
			Industry gd = market.getIndustry(Industries.GROUNDDEFENSES);
			if (gd != null) list.add(gd);
			Industry hb = market.getIndustry(Industries.HEAVYBATTERIES);
			if (hb != null) list.add(hb);
			return list;
		}
		public List<Industry> seizedStructures(MarketAPI market) {
			return seizedIndustries(market);
		}
		public String layer() {
			return "district";
		}
		public List<Industry> fortifications(MarketAPI market) {
			return ThreatSiegeMalus.fortifications(market);
		}
		public float wearDays() {
			return Math.max(1f, ThreatIncConfig.fortificationDisruptDays());
		}
		public float suppressDaysPerDay() {
			return ThreatIncConfig.siegeSuppressDaysPerDay();
		}
		public float batteryShare(MarketAPI market) {
			return ThreatSiegeMalus.batteryShare(market);
		}
		public float intactBatteryShare(MarketAPI market) {
			return ThreatSiegeMalus.intactBatteryShare(market);
		}
		public float condition(MarketAPI market, Industry ind) {
			return ThreatSiegeMalus.condition(market, ind);
		}
		public boolean orbitHeldAgainst(String ownerFactionId, MarketAPI market) {
			// the colony's own station, if it still flies, and any armed fleet
			// near the planet that would fight the invader - a Guard order, an
			// escort, an ally's task force, the player in person - weighed
			// against what the invader has there (orbitHeld). Hold the orbit
			// and nothing comes down.
			float hostile = hostilePointsNear(ownerFactionId, market);
			CampaignFleetAPI station = Misc.getStationFleet(market);
			// a station still flying holds it whatever it weighs: it is beaten
			// before anything bombards (docs/suppression-balance.md, the defence
			// figure). Weighed as a fleet, a 90-point fortress under 300 points
			// of swarm let the first day in with its x3 still on the figure,
			// and the slice after read a fifth of it (Jangala, 2026-09-28)
			Industry stationInd = Misc.getStationIndustry(market);
			if (station != null && station.isAlive() && !station.isExpired()
					&& station.getFleetPoints() > 0f && station.getFaction().isHostileTo(ownerFactionId)
					&& (stationInd == null || !stationInd.isDisrupted())) {
				return true;
			}
			if (station != null && station.isAlive() && !station.isExpired()
					&& !nearWorld(station, market)) {
				hostile += station.getFleetPoints();
			}
			return orbitHeld(ownerFactionId, market, hostile);
		}
		public boolean carriesSiegeState() {
			return true;
		}
		public void victory(GroundFront front, MarketAPI market) {
			colonyGroundVictory(front, market);
		}
	};

	/**
	 * Everything that must be true of a market while a front stands on it, and
	 * must be undone when the front goes: the theatre says whether the world
	 * needs a carrier for its strata loss (a human colony does - the hidden
	 * {@link ThreatSiegeMalus} structure and the {@link ThreatGroundWarCondition}
	 * tooltip; a hive does not, its loss lives in SwarmNexus.apply). Returns
	 * whether anything changed; the caller reapplies the market's industries
	 * when it did - the one recompute the malus needs, and immediately.
	 */
	public static boolean syncSiegeState(MarketAPI market) {
		if (market == null) return false;
		boolean colony = market.isInEconomy() && Theatre.of(market).carriesSiegeState();
		boolean front = colony && getFront(market.getId()) != null;
		// the siege state carries the fortification rule too (2026-09-06), so a
		// colony bombarded but not yet landed on carries it as well
		boolean wantsIndustry = colony && (front
				|| (besieged(market) && ThreatSiegeMalus.anyDisrupted(market))
				|| ThreatRazing.saturated(market));
		boolean wantsCondition = front;
		boolean hasIndustry = market.hasIndustry(ThreatSiegeMalus.ID);
		boolean hasCondition = market.hasCondition(ThreatGroundWarCondition.ID);
		if (wantsIndustry == hasIndustry && wantsCondition == hasCondition) return false;
		if (wantsIndustry && !hasIndustry) market.addIndustry(ThreatSiegeMalus.ID);
		if (!wantsIndustry && hasIndustry) market.removeIndustry(ThreatSiegeMalus.ID, null, false);
		if (wantsCondition && !hasCondition) market.addCondition(ThreatGroundWarCondition.ID);
		if (!wantsCondition && hasCondition) market.removeCondition(ThreatGroundWarCondition.ID);
		return true;
	}

	// ------------------------------------------------------------------
	// deployment lifecycle (player via ThreatincMarketCMD, NPC via ThreatPurgeFGI)
	// ------------------------------------------------------------------

	public static GroundFront deploy(MarketAPI market, String factionId, int marines,
			float armaments) {
		String owner = factionId != null ? factionId : Factions.PLAYER;
		return deploy(market, factionId, marines, armaments,
				Factions.PLAYER.equals(owner) ? ThreatMarineXP.fleetLevel()
						: ThreatIncConfig.npcLandingVeterancy());
	}

	/**
	 * As above with the landing's veterancy stated explicitly. The player's
	 * landing MUST use this form: the caller takes the marines out of the
	 * fleet's cargo first, and vanilla's tracker recounts the pool on the next
	 * read, so a level read after the loading ramp is always 0 and every
	 * landing would go in green (found in review, 2026-09-08).
	 */
	public static GroundFront deploy(MarketAPI market, String factionId, int marines,
			float armaments, float landingLevel) {
		GroundFront front = new GroundFront();
		front.marketId = market.getId();
		// the owner is always a faction id; isPlayerOwned stays tolerant of the
		// null the player's fronts were stored with before
		front.factionId = factionId != null ? factionId : Factions.PLAYER;
		front.marines = marines;
		front.armaments = armaments;
		front.marinesLanded = marines;
		// the troops that land are the troops that were in the hold: the
		// player's landing inherits the fleet's own marine rank, so a veteran
		// company fights like one the moment it is on the ground (2026-09-08)
		front.xp = marines * Math.max(0f, Math.min(ThreatMarineXP.MAX_LEVEL, landingLevel));
		front.deployedTimestamp = Global.getSector().getClock().getTimestamp();
		front.lastCounterAttack = front.deployedTimestamp;
		fronts().put(market.getId(), front);
		reapply(market.getId()); // installs the siege malus / tooltip on a human world
		autoPush(front, market);
		ThreatIncConfig.log("Front deployed at " + market.getName() + " (" + factionId
				+ "): " + marines + " marines, " + (int) armaments + " armaments"
				+ (STANCE_PUSH.equals(front.stance) ? ", pushing" : ", dug in"));
		return front;
	}

	public static void resupply(GroundFront front, int marines, float armaments) {
		if (front == null) return;
		resupply(front, marines, armaments,
				front.isPlayerOwned() ? ThreatMarineXP.fleetLevel()
						: ThreatIncConfig.npcLandingVeterancy());
	}

	/**
	 * As above with the reinforcement's veterancy stated explicitly - the
	 * player's path must use this form, for the reason given on {@link #deploy}.
	 */
	public static void resupply(GroundFront front, int marines, float armaments,
			float arrivingLevel) {
		if (front == null) return;
		if (marines > 0) {
			float before = front.marines;
			float arriving = marines;
			// the reinforcement brings its own experience - the player's from
			// the fleet pool, anyone else's from their faction's muster - and
			// the merged pool is the weighted average, so raw bodies dilute
			// veterans exactly as recruiting does in vanilla
			float arrivingXp = arriving
					* Math.max(0f, Math.min(ThreatMarineXP.MAX_LEVEL, arrivingLevel));
			front.marines = before + arriving;
			front.xp = ThreatMarineXP.addXp(front.xp, arrivingXp, front.marines);
			// and it has not dug the cover the front it joined has dug, so the
			// entrenchment dilutes in the same proportion (2026-09-08). Before
			// this a reinforcement inherited sixty days of digging the instant
			// it stepped off the ramp.
			front.entrenchDays *= before / Math.max(1f, before + arriving);
		}
		front.armaments += armaments;
		front.marinesLanded = Math.max(front.marinesLanded, front.marines);
		front.withdrawRequested = false;
		if (front.armaments > 0f) {
			front.dryTimestamp = 0;
			front.finalPush = false;
		}
		// troops landing push (2026-09-07); an armaments-only drop keeps the
		// stance the front was ordered to, so a dug-in front stays dug in
		if (marines > 0) autoPush(front, resolveMarket(front.marketId));
	}

	/**
	 * Troops that land push (user, 2026-09-07): a front that can hold assaults
	 * the next stratum as soon as it is on the ground, and a reinforcement that
	 * brings it back to holding strength resumes. The gate is Push's own
	 * ({@link #pushBlockReason}) plus the story-critical hold; a landing too
	 * weak to hold digs in and waits for the next one. Off by knob, the
	 * player's fronts push only when ordered.
	 */
	public static boolean autoPush(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		if (STANCE_PUSH.equals(front.stance)) return true;
		if (!ThreatIncConfig.frontAutoPush()) return false;
		if (isDry(front) || front.strataHeld >= market.getSize()) return false;
		if (effectiveStrength(front) < holdRequirement(market)) return false;
		if (lastStratumProtected(front, market)) return false;
		// do not walk into a counter-attack with no cover and no ground held
		if (shouldBrace(front, market)) return false;
		orderPush(front);
		return true;
	}

	/**
	 * Whether a front should break off its assault - or hold off starting one -
	 * because a counter-attack is close (user, 2026-09-08: "they should prepare
	 * not just blindly keep pushing when it's imminent").
	 *
	 * <p>Pushing costs a front all of its cover ({@link #coverMult} is 1 while
	 * assaulting), and a front holding NO ground is destroyed outright rather
	 * than pushed back - {@link #hiveCounterAttack}'s overrun branch. Those two
	 * together are what kills fresh landings: {@link #autoPush} sends a landing
	 * forward the moment it can hold, and it meets the first counter-attack
	 * exposed, with nothing between it and the overrun test.
	 *
	 * <p>Deliberately only while the front holds nothing. Once it has ground,
	 * the counter-attack takes a district back instead of annihilating it - a
	 * setback, not a death - and bracing for every counter-attack would stall
	 * the stratum campaign for a third of its life. A front with something to
	 * lose but itself presses on and accepts the setback.
	 */
	public static boolean shouldBrace(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		if (!ThreatIncConfig.frontBraceEnabled()) return false;
		if (front.finalPush) return false; // spent either way; nothing to save it for
		if (front.strataHeld > 0) return false;
		// The test is "would an assault get me killed", not "is a blow due
		// soon" (user, 2026-09-08). Cover takes frontEntrenchDays to dig, so a
		// window measured in days was always the wrong shape: a front 22 days
		// from a counter-attack it cannot survive should already be digging.
		//
		// Asked at the cover the front would have WHILE PUSHING, deliberately.
		// Asking at its current cover would flip the moment it took any, then
		// flip back the moment it moved, and the front would oscillate.
		// Entrenchment only accrues while dug in and never decays, so this
		// projection improves monotonically and settles.
		if (!overrunIfPushing(front, market)) return false;
		float due = daysToCounterAttack(front, market);
		// Unless the assault lands first (user, 2026-09-08). Taking the layer
		// pays twice: it strips the defenders of that layer's share, and it
		// puts ground under the front - so the counter-attack that follows
		// takes a layer back instead of overrunning a beachhead outright,
		// which is the whole danger being braced against. The push resolves
		// earlier in the tick than the counter-attack does, so a layer that
		// falls on the same day still counts.
		float left = pushDaysRemaining(front, market);
		if (left >= 0f && left <= due) return false;
		return true;
	}

	/**
	 * Whether the next counter-attack would overrun this front if it were out
	 * of its holes when the blow landed - the question {@link #shouldBrace}
	 * actually turns on. Cover is taken at 1 (an assault leaves it behind,
	 * {@link #coverMult}) whatever the front's current stance, so the answer
	 * does not change just because the front took cover.
	 */
	public static boolean overrunIfPushing(GroundFront front, MarketAPI market) {
		if (front == null || market == null || front.strataHeld > 0) return false;
		float attack = counterAttackStrength(market);
		float exposed = effectiveStrength(front); // x1 cover: pushing
		if (attack <= exposed) return false;
		return ratioPow(attack / Math.max(1f, exposed)) > 2f;
	}

	/**
	 * Days the current assault still needs at the front's own pace, or -1 when
	 * it cannot push at all. A braced front reports what its held-over progress
	 * would need, so the decision to press on and the decision to resume read
	 * the same number.
	 */
	public static float pushDaysRemaining(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return -1f;
		float eff = effectiveStrength(front);
		if (eff <= 0f) return -1f;
		float progress = STANCE_PUSH.equals(front.stance) ? front.pushProgress
				: front.bracedPushProgress;
		float base = Math.max(0f, ThreatIncConfig.frontPushBaseDays() - progress);
		return base * paceRatio(defenderStrength(market), eff);
	}

	/**
	 * Days until this front takes the world's last stratum on its own, at its
	 * current pace with the checkpoint between strata, or -1 when it cannot:
	 * dry, unable to hold, or barred from the last stratum. Later strata come
	 * faster as the defence is stripped, so the figure runs long - a razing
	 * weighed against it gets the benefit of the doubt (run 6, 2026-09-28: a
	 * flotilla sailed to finish a hive its front took a day later).
	 */
	public static float daysToLastStratum(GroundFront front, MarketAPI market) {
		if (front == null || market == null || front.strataHeld >= market.getSize()) return -1f;
		if (isDry(front) || !frontCanHold(front, market) || lastStratumProtected(front, market)) return -1f;
		float per = pushDaysEstimate(front, market);
		if (per <= 0f) return -1f;
		float first = STANCE_PUSH.equals(front.stance) ? pushDaysRemaining(front, market)
				: STANCE_CONSOLIDATE.equals(front.stance) ? front.consolidateDaysLeft + per : per;
		if (first < 0f) return -1f;
		int after = market.getSize() - front.strataHeld - 1;
		return first + after * (ThreatIncConfig.frontCheckpointDays() + per);
	}

	/** Peak strength, falling back to the current figure for fronts from older saves. */
	public static float landedStrength(GroundFront front) {
		if (front == null) return 0f;
		return Math.max(front.marinesLanded, front.marines);
	}

	/** Removes the front; returns [marines, armaments] recovered. */
	public static int[] withdraw(String marketId) {
		GroundFront front = fronts().remove(marketId);
		if (front == null) return new int[] {0, 0};
		reapply(marketId);
		return new int[] {Math.round(front.marines), (int) Math.floor(front.armaments)};
	}

	/** Total loss - a front shelled to nothing by its own side's danger close. */
	public static void destroy(String marketId) {
		fronts().remove(marketId);
		reapply(marketId);
	}

	/** Strata effects live in industry apply(); recompute after they change. */
	protected static void reapply(String marketId) {
		MarketAPI market = resolveMarket(marketId);
		if (market == null) return;
		syncSiegeState(market);
		market.reapplyIndustries();
	}

	/** Orders the assault on the next stratum (also answers a checkpoint). */
	public static void orderPush(GroundFront front) {
		if (front == null || STANCE_PUSH.equals(front.stance)) return;
		front.stance = STANCE_PUSH;
		front.pushProgress = 0f;
		front.consolidateDaysLeft = 0f;
	}

	/** Breaks off the assault (or answers a checkpoint) and digs in. */
	public static void orderEntrench(GroundFront front) {
		if (front == null || STANCE_ENTRENCH.equals(front.stance)) return;
		front.stance = STANCE_ENTRENCH;
		front.pushProgress = 0f;
		front.consolidateDaysLeft = 0f;
	}

	// ------------------------------------------------------------------
	// landings - the one doctrine both expeditions run (docs/ground-war.md).
	// The purge against a hive and the strike against a human world ask the
	// same three questions here, so their answers cannot drift apart.
	// ------------------------------------------------------------------

	/** Whether orbit still has work to do here for a fleet of fp: its commander would fly another day ({@link #orbitSpent}). */
	public static boolean needsSoftening(MarketAPI market, float fp) {
		return market != null && !orbitSpent(market, fp);
	}

	/**
	 * Why nothing can land on this world for this owner right now, or null:
	 * another army already holding it, or -
	 * only with live fleets, since vanilla's autoresolve has already weighed
	 * the expedition against the system's defenders and station before any
	 * pass is delivered - the orbit held against the landing. One gate for a
	 * purge landing, a strike landing and the board's readouts.
	 */
	public static String landingBlocked(String ownerFactionId, MarketAPI market,
			boolean liveFleets) {
		if (market == null) return "no world to land on";
		// a forward base is a station, as vanilla's pirate base is: its station is the base
		if (ThreatFrontlines.isOutpost(market)) return "a station, not a world";
		GroundFront standing = getFront(market.getId());
		if (standing != null && !ownerOf(standing).equals(ownerFactionId)) {
			return "another army holds the ground";
		}
		if (liveFleets && orbitContestedFor(ownerFactionId, market)) {
			return Factions.THREAT.equals(ownerFactionId)
					? "the orbit is held against the landing" : "Defense Swarms hold the orbit";
		}
		return null;
	}

	/**
	 * Puts troops on the ground for an owner: a new front when none stands, a
	 * reinforcement of the owner's own front otherwise (null for a foreign
	 * front - {@link #landingBlocked} should have said so first). Announces
	 * the landing in the owner's voice. A Threat landing fails any Defend
	 * contract for the colony ({@link ThreatAidMissionIntel#strikeLanded}):
	 * that, not a pass that bombards or is turned back from the orbit, is the
	 * event the contract's "asset present" condition exists to prevent.
	 */
	public static GroundFront landOrReinforce(MarketAPI market, String ownerFactionId,
			int troops, float armaments) {
		if (market == null || ownerFactionId == null) return null;
		boolean threat = Factions.THREAT.equals(ownerFactionId);
		GroundFront standing = getFront(market.getId());
		if (standing != null) {
			if (!ownerOf(standing).equals(ownerFactionId)) return null;
			resupply(standing, troops, armaments);
			if (threat) ThreatAidMissionIntel.strikeLanded(market);
			ThreatIncConfig.log("Front reinforced at " + market.getName() + " (" + ownerFactionId
					+ "): +" + troops + " troops, +" + (int) armaments + " armaments");
			return standing;
		}
		GroundFront front = deploy(market, ownerFactionId, troops, armaments);
		if (threat) {
			ThreatNotice.titled("Threat Landing").bad()
					.line("The Threat has landed on %s.", ThreatNotice.market(market))
					.line("%s troops on the surface.", ThreatNotice.red(Misc.getWithDGS(troops)))
					.line("Beat them on the ground to save the colony.")
					.send();
			ThreatAidMissionIntel.strikeLanded(market);
		} else {
			// a faction's expedition, or the player's commissioned one
			FactionAPI owner = Global.getSector().getFaction(ownerFactionId);
			ThreatNotice n = ThreatNotice.titled("Expedition Landed").icon(owner);
			if (front.isPlayerOwned()) {
				n.line("Your expedition has landed on %s.", ThreatNotice.market(market));
			} else {
				n.line("%s's expedition has landed on %s.",
						ThreatNotice.faction(owner), ThreatNotice.market(market));
			}
			n.line("%s troops on the surface.", Misc.getWithDGS(troops)).send();
		}
		return front;
	}

	// ------------------------------------------------------------------
	// derived figures (shared by the tick, the dialog and the board)
	// ------------------------------------------------------------------

	/**
	 * WHETHER ARMAMENTS ARE THIS FRONT'S PROBLEM AT ALL (2026-09-08, the user:
	 * "given the Threat never resupply, can we remove their need for
	 * armaments"). A faction's front is the far end of a logistics chain -
	 * convoys, front runs, a base with a stockpile - so running dry is a
	 * failure of that chain and the penalties are the consequence of it. The
	 * swarm has no chain: `ThreatConvoys` cannot reach a Threat front at all
	 * (it resolves through hive markets, and a Threat front stands on a human
	 * colony), so its only armaments were the ones it landed with. Charging it
	 * for a supply line it can never use was a countdown wearing the costume of
	 * a supply rule.
	 *
	 * <p>So the swarm's fronts do not burn, run dry, or take any of the dry
	 * penalties. What replaces that pressure is casualties on the assault
	 * ({@link #pushLossMult}) - it is paid for pressing, not for waiting.
	 * Knob {@code threatFrontNeedsArms} restores the old rule.
	 */
	public static boolean needsArms(GroundFront front) {
		if (front == null) return false;
		return !isThreatOwned(front) || ThreatIncConfig.threatFrontNeedsArms();
	}

	/**
	 * Whether the front is fighting without heavy armaments - the one test the
	 * dry penalties, the final-push clock and the readouts all go through, so
	 * a front that does not need them is never "dry" anywhere. Replaces the
	 * bare {@code front.armaments <= 0f} that used to be written at each site.
	 */
	public static boolean isDry(GroundFront front) {
		return front != null && needsArms(front) && front.armaments <= 0f;
	}

	/**
	 * The extra casualties the swarm takes while PUSHING (2026-09-08, the
	 * user's other half of the same call: "given they can make more soldiers,
	 * can we increase their losses when pushing to compensate"). It can turn
	 * hulls into troops ({@link #fabricateTroops}) and it no longer starves, so
	 * the price of both is paid on the assault - the swarm buys ground with
	 * bodies, and that is now the honest cost. Only while pushing: a dug-in
	 * Threat front bleeds at the ordinary rate, so the counterplay is to make
	 * them come to you rather than to wait them out.
	 */
	public static float pushLossMult(GroundFront front) {
		return isThreatOwned(front) ? Math.max(0f, ThreatIncConfig.threatPushLossMult()) : 1f;
	}

	/**
	 * Marines x the entrenchment ramp x the supply factor. A dry front fights
	 * at frontDryEffectivenessMult - "not enough heavy armaments" is exactly
	 * the moment to resupply before ordering a push. A front that does not need
	 * armaments ({@link #needsArms}) is never dry.
	 */
	public static float effectiveStrength(GroundFront front) {
		if (front == null) return 0f;
		float mult = entrenchMult(front);
		if (isDry(front)) mult *= ThreatIncConfig.frontDryEffectivenessMult();
		// what the troops themselves are worth: experience (2026-09-08), and
		// for the player's own fronts the ground skills, which the engine did
		// not read at all before that
		mult *= ThreatMarineXP.frontEffectMult(front);
		if (front.isPlayerOwned()) mult *= ThreatMarineXP.playerGroundSkillMult();
		return front.marines * mult;
	}

	/**
	 * Heavy armaments a body of this many marines burns per day. The army is
	 * what eats the armaments, not the world it is fighting on (2026-09-07 -
	 * before this, a 2,000-marine landing and a 200-marine one burned the same
	 * because the rate keyed on the target colony's size, so a staging base
	 * stocked a few hundred armaments against sieges that shipped thousands).
	 */
	public static float dailyUpkeep(float marines) {
		if (marines <= 0f) return 0f;
		return ThreatIncConfig.frontArmamentsPerMarinePer30Days() * marines / 30f;
	}

	/** Heavy armaments the front burns per day at its current strength. */
	public static float dailyUpkeep(GroundFront front) {
		return front == null ? 0f : dailyUpkeep(front.marines);
	}

	/**
	 * The armaments a landing of this many marines carries to keep itself in
	 * the field for the given days - the one figure every landing draws by,
	 * so a front's loadout and its burn are the same rule read twice.
	 */
	public static float landingSupply(float marines, float days) {
		return dailyUpkeep(marines) * days;
	}

	/** Days the armaments stock lasts at the current burn rate (999+ = ample). */
	public static float supplyDaysLeft(GroundFront front) {
		if (front == null) return 0f;
		if (!needsArms(front)) return 999f; // it does not fight on armaments
		float upkeep = dailyUpkeep(front);
		if (STANCE_PUSH.equals(front.stance)) upkeep *= ThreatIncConfig.frontPushUpkeepMult();
		if (upkeep <= 0f) return 999f;
		return front.armaments / upkeep;
	}

	/**
	 * What the world fights a landing WITH - the one figure every requirement,
	 * push pace and counter-attack in the engine is measured against. The
	 * theatre's answer: a hive's bombardment-facing figure, a colony's vanilla
	 * ground figure plus its banked marines ({@code reserveDefenseMult}).
	 */
	public static float defenderStrength(MarketAPI market) {
		if (market == null) return 0f;
		return Theatre.of(market).defenderStrength(market);
	}

	/**
	 * The colony's garrison proper: vanilla's ground-defence stat plus its
	 * raid hardening, and NO cargo marines.
	 *
	 * <p>This deliberately asks vanilla with {@code forBombard = true}. The
	 * {@code false} form adds marines from BOTH the resource stockpile and
	 * personal Storage, flat and at 1:1, and the war has no business in
	 * personal Storage: the stockpile is the war chest the Waystation, staging
	 * and sieges all read, Storage is the player's own (user, 2026-09-08).
	 * Taking the marine term ourselves from {@link ThreatReserves} instead
	 * keeps three things true at once - Storage stays out of the war, the
	 * player and NPC paths are the same arithmetic, and every marine the
	 * figure counts is one the siege can actually kill.
	 */
	public static float colonyGarrison(MarketAPI market) {
		if (market == null) return 0f;
		return MarketCMD.getDefenderStr(market, true);
	}

	/**
	 * What the colony's stockpiled marines add to its defence: the ones that
	 * are actually armed ({@link ThreatReserves#armedMarines}), times
	 * {@code reserveDefenseMult}, times what their experience is worth
	 * ({@link ThreatMarineXP}). {@code weight} is 1 for holding ground and
	 * {@code marineCounterAttackMult} for going over the top.
	 */
	public static float colonyMarineDefense(MarketAPI market, float weight) {
		if (market == null || weight <= 0f) return 0f;
		float mult = ThreatIncConfig.reserveDefenseMult();
		if (mult <= 0f) return 0f;
		float armed = ThreatReserves.armedMarines(market);
		if (armed <= 0f) return 0f;
		return armed * mult * ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(market)) * weight;
	}

	/**
	 * What the world can put into a counter-attack. Not the same as
	 * {@link #defenderStrength} on a human colony since 2026-09-08: holding a
	 * line and mounting an offensive are different jobs, and a stockpile full
	 * of marines is much better at the first.
	 */
	public static float counterAttackStrength(MarketAPI market) {
		if (market == null) return 0f;
		return Theatre.of(market).counterAttackStrength(market);
	}

	/** Effective strength needed to HOLD (suppress everything) here right now. */
	public static float holdRequirement(MarketAPI market) {
		return defenderStrength(market) * ThreatIncConfig.frontHoldFraction();
	}

	/** Effective strength needed to GRIND (harass the defense structures). */
	public static float grindRequirement(MarketAPI market) {
		return defenderStrength(market) * ThreatIncConfig.frontGrindFraction();
	}

	/**
	 * Push pace: days to take the next stratum at the CURRENT strength ratio.
	 * Base days scaled by defense-over-strength, clamped so an overwhelming
	 * front still fights for each stratum and a marginal one crawls rather
	 * than stalls. As held strata strip base defense away, later pushes
	 * genuinely get faster - momentum is real.
	 */
	public static float pushDaysEstimate(GroundFront front, MarketAPI market) {
		float eff = effectiveStrength(front);
		if (eff <= 0f) return -1f;
		float defender = Math.max(1f, defenderStrength(market));
		return ThreatIncConfig.frontPushBaseDays() * paceRatio(defender, eff);
	}

	/**
	 * Every strength RATIO the ground war uses goes through
	 * groundStrengthExponent first (docs/design-theory.md 8.4): at 1.0 the
	 * resolution is linear and legible; above it, concentration pays the way
	 * Lanchester's square law says it should. Deterministic, so the dialog's
	 * quotes stay exact.
	 */
	public static float ratioPow(float ratio) {
		float e = ThreatIncConfig.groundStrengthExponent();
		if (ratio <= 0f) return 0f;
		if (e == 1f) return ratio;
		return (float) Math.pow(ratio, e);
	}

	/** Defense-over-strength, exponent applied, clamped 0.5x-3x: the push-pace and push-attrition factor. */
	public static float paceRatio(float defender, float eff) {
		float ratio = ratioPow(Math.max(1f, defender) / Math.max(1f, eff));
		if (ratio < 0.5f) ratio = 0.5f;
		if (ratio > 3f) ratio = 3f;
		return ratio;
	}

	/** Attrition multiplier while pushing: 1 at exponent 1; the weaker side bleeds proportionally more above it. */
	public static float pushLossFactor(float defender, float eff) {
		float e = ThreatIncConfig.groundStrengthExponent();
		if (e == 1f) return 1f;
		float ratio = Math.max(1f, defender) / Math.max(1f, eff);
		if (ratio < 0.5f) ratio = 0.5f;
		if (ratio > 3f) ratio = 3f;
		return (float) Math.pow(ratio, e - 1f);
	}

	/** Estimated marines a push on the next stratum costs at current strength. */
	public static int pushCasualtyEstimate(GroundFront front, MarketAPI market) {
		float days = pushDaysEstimate(front, market);
		if (days <= 0f) return 0;
		float defender = defenderStrength(market);
		return Math.round(front.marines
				* ThreatIncConfig.frontPushLossPer30Days() / 30f * days
				* pushLossFactor(defender, effectiveStrength(front))
				* pushLossMult(front)
				* ThreatMarineXP.frontLossMult(front)); // as the tick applies it
	}

	/**
	 * Days until the colony's next counter-attack goes in. A hive paces on the
	 * supplies it is paid (hiveCounterAttackPace: starve it and the
	 * counterstroke never comes); a human colony paces on STABILITY - unrest,
	 * shortages and the shock of an invasion are what stop a garrison
	 * organising - and a colony with a military command to run the operation
	 * counter-attacks {@code colonyCounterAttackMilitaryMult} times as often.
	 */
	public static float counterAttackInterval(GroundFront front, MarketAPI market) {
		if (market == null) return ThreatIncConfig.frontCounterAttackDays();
		return Theatre.of(market).counterAttackInterval(front, market);
	}

	/**
	 * What paces a hive world's counter-attacks, 0..1, before the tempo: under
	 * size upkeep the share of its upkeep paid against the break-even share
	 * (capped at 1) times the strata it still holds, (size - held) / size
	 * (2026-09-30: supplies, not vitality; the Fabrication Core no longer
	 * counts). With size upkeep off, vitality (computeHealth), which carries
	 * the same strata factor.
	 */
	public static float hiveCounterAttackPace(MarketAPI market) {
		if (market == null) return 0f;
		if (!ThreatColonyUpkeep.enabled()) return ThreatColonyManager.computeHealth(market);
		int size = market.getSize();
		if (size <= 0) return 0f;
		float fed = Math.min(1f, ThreatColonyUpkeep.fedShare(market) / ThreatColonyUpkeep.breakEven());
		return fed * Math.max(0, size - strataHeld(market.getId())) / (float) size;
	}

	/** Days left on the counter-attack clock (0 = due now). Shown in the tooltips. */
	public static float daysToCounterAttack(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		float since = Global.getSector().getClock()
				.getElapsedDaysSince(front.lastCounterAttack);
		return Math.max(0f, counterAttackInterval(front, market) - since);
	}

	/** A staff that can plan a counter-attack: Patrol HQ, Military Base, High Command, Lion's Guard. */
	public static boolean hasMilitaryCommand(MarketAPI market) {
		if (market == null) return false;
		return market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.PATROLHQ)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.MILITARYBASE)
				|| market.hasIndustry(com.fs.starfarer.api.impl.campaign.ids.Industries.HIGHCOMMAND)
				|| market.hasIndustry("lionsguard");
	}

	/** What the front defends with: dug-in fronts fight from cover (a pushing
	 * front is exposed; consolidating counts as dug in). */
	public static float defenseStrength(GroundFront front) {
		return effectiveStrength(front) * coverMult(front);
	}

	// ------------------------------------------------------------------
	// readouts for the board (docs/ground-war.md "Reading a front"): every
	// figure the fronts table, its row tooltip and its Push / Dig in prompts
	// show comes from here, by the tick's own arithmetic, so what the board
	// quotes is what will happen
	// ------------------------------------------------------------------

	/** "push" / "regrouping" / "dug in" - the stance as the board names it. */
	public static String stanceLabel(GroundFront front) {
		if (front == null) return "-";
		if (STANCE_PUSH.equals(front.stance)) return "push";
		if (STANCE_CONSOLIDATE.equals(front.stance)) return "regrouping";
		return "dug in";
	}

	/** Days since the landing, by the tick's own clock (the front's age, like its entrenchment). */
	public static float daysSinceLanding(GroundFront front) {
		if (front == null) return 0f;
		if (front.daysAlive > 0f) return front.daysAlive;
		if (front.deployedTimestamp <= 0) return 0f;
		return Math.max(0f, Global.getSector().getClock()
				.getElapsedDaysSince(front.deployedTimestamp));
	}

	/** How dug in the front is: 0 fresh, 1 at frontEntrenchDays of holding. */
	public static float entrenchFraction(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(0f, Math.min(1f, front.entrenchDays / days));
	}

	/**
	 * The entrenchment multiplier on strength right now: frontLandingMult on
	 * landing, frontEntrenchMaxMult once dug in (2026-09-06: attackers land
	 * exposed and earn their cover; the defenders' entrenchment is the
	 * ground-defence figure itself).
	 */
	public static float entrenchMult(GroundFront front) {
		if (front == null) return 1f;
		float landing = ThreatIncConfig.frontLandingMult();
		return landing + (ThreatIncConfig.frontEntrenchMaxMult() - landing) * entrenchFraction(front);
	}

	/** The cover multiplier against a counter-attack: 1 while pushing (exposed), frontEntrenchDefenseBonus once dug in. */
	public static float coverMult(GroundFront front) {
		if (front == null || STANCE_PUSH.equals(front.stance)) return 1f;
		return 1f + (ThreatIncConfig.frontEntrenchDefenseBonus() - 1f) * entrenchFraction(front);
	}

	/**
	 * Marines the front loses per 30 days at the given stance and its current
	 * supply - the tick's own rate, so the table's Losses column and the
	 * Push / Dig in prompts quote what will actually happen.
	 */
	public static float attritionPer30Days(GroundFront front, MarketAPI market, boolean pushing) {
		if (front == null) return 0f;
		float rate = pushing ? ThreatIncConfig.frontPushLossPer30Days()
				: ThreatIncConfig.frontMarineLossPer30Days();
		if (isDry(front)) rate *= ThreatIncConfig.frontUnsuppliedLossMult();
		if (pushing) {
			rate *= pushLossFactor(defenderStrength(market), effectiveStrength(front));
			rate *= pushLossMult(front);
		}
		rate *= ThreatMarineXP.frontLossMult(front); // veterans take fewer casualties
		// plus what orbit is doing to it (2026-09-08). The tick applies the
		// bombardment separately from this rate, so a readout that stopped at
		// the ground rate understated what a front under an unopposed swarm
		// actually bleeds - and the Losses column is the at-a-glance number.
		return front.marines * rate + swarmBombardPer30Days(front, market);
	}

	/**
	 * A per-30-days rate as the player watches it tick: the figure a day.
	 * The board, its tooltips and every order prompt quote attrition this way
	 * (2026-09-08) - "33/d" reads against a countdown in days; "1,000 per 30
	 * days" has to be divided first.
	 */
	public static String perDay(float per30) {
		float d = per30 / 30f;
		if (d >= 10f) return Misc.getWithDGS(Math.round(d));
		if (d >= 1f) return String.format("%.1f", d);
		return String.format("%.2f", d);
	}

	/** The same at the front's current stance. */
	public static float attritionPer30Days(GroundFront front, MarketAPI market) {
		return attritionPer30Days(front, market, front != null && STANCE_PUSH.equals(front.stance));
	}

	/** Whether a counter-attack landing now would be repelled at the front's current strength and stance. */
	public static boolean counterAttackRepelled(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return true;
		return counterAttackStrength(market) <= defenseStrength(front);
	}

	/**
	 * Marines a counter-attack landing now would cost - 0 if repelled - by the
	 * arithmetic the counter-attack applies when it lands.
	 */
	public static float counterAttackLoss(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		return counterAttackLoss(front, counterAttackStrength(market), defenseStrength(front));
	}

	/**
	 * The same at two strengths already in hand. The tick calls THIS with the
	 * figures it fought at, so the board's quote and the casualty cannot drift
	 * apart - they did, over the veterancy multiplier, which the readout was
	 * missing (review, 2026-09-08).
	 */
	protected static float counterAttackLoss(GroundFront front, float attack, float defense) {
		if (front == null || attack <= defense) return 0f;
		float odds = ratioPow(attack / Math.max(1f, defense));
		return front.marines * ThreatIncConfig.frontCounterAttackLossFraction()
				* Math.min(2f, (float) Math.pow(Math.max(1f, odds),
						Math.max(0f, ThreatIncConfig.groundStrengthExponent() - 1f)))
				* ThreatMarineXP.frontLossMult(front);
	}

	/** Whether a counter-attack landing now would overrun the front outright: no strata held, beaten better than 2:1. */
	public static boolean counterAttackOverruns(GroundFront front, MarketAPI market) {
		if (front == null || market == null || front.strataHeld > 0) return false;
		float attack = counterAttackStrength(market);
		float defense = defenseStrength(front);
		return attack > defense && ratioPow(attack / Math.max(1f, defense)) > 2f;
	}

	/** The defenses are being worn down by the front (it holds, and feeds their disruption clocks faster than they run). */
	public static final int DEFENSES_WORN = -1;
	/** Nothing is changing: intact and untouched, or disrupted and held there by a grinding front. */
	public static final int DEFENSES_STEADY = 0;
	/** Disrupted structures are recovering: the front is too weak to keep them down. */
	public static final int DEFENSES_RECOVERING = 1;

	/**
	 * Which way the defenders' figure is heading, from the front's side. This is
	 * the whole of the defenders' "reinforcement" in this model: a colony's
	 * ground figure is rebuilt every frame from its structures, its strata and
	 * their disruption, so what it recovers is exactly what the front stops
	 * suppressing. A holding front nets +1 day of disruption per day (worn), a
	 * grinding one holds the defense structures where they are (steady), a
	 * foothold suppresses nothing (recovering).
	 */
	public static int defensesTrend(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return DEFENSES_STEADY;
		Theatre theatre = Theatre.of(market);
		boolean keyDown = anyDisrupted(theatre.keyStructures(market));
		boolean defenseDown = anyDisrupted(theatre.defenseStructures(market));
		if (!keyDown && !defenseDown) return DEFENSES_STEADY;
		if (STATE_HOLDING.equals(front.state)) return DEFENSES_WORN;
		if (STATE_GRINDING.equals(front.state)) return defenseDown ? DEFENSES_STEADY : DEFENSES_RECOVERING;
		return DEFENSES_RECOVERING;
	}

	/** One word for the board: "worn" / "held" / "intact" / "recovering". */
	public static String defensesLabel(GroundFront front, MarketAPI market) {
		int trend = defensesTrend(front, market);
		if (trend == DEFENSES_WORN) return "worn";
		if (trend == DEFENSES_RECOVERING) return "recovering";
		return anyDisrupted(Theatre.of(market).keyStructures(market)) ? "held" : "intact";
	}

	/** "Heavy Batteries back in 41 d" for every disrupted structure the theatre cares about. */
	public static List<String> recoveringStructures(MarketAPI market) {
		List<String> lines = new ArrayList<String>();
		if (market == null) return lines;
		Theatre theatre = Theatre.of(market);
		List<Industry> all = new ArrayList<Industry>(theatre.keyStructures(market));
		for (Industry ind : theatre.defenseStructures(market)) {
			if (!all.contains(ind)) all.add(ind);
		}
		for (Industry ind : all) {
			if (ind == null || !ind.isDisrupted()) continue;
			lines.add(ind.getCurrentName() + " back in "
					+ (int) Math.ceil(ind.getDisruptedDays()) + " d");
		}
		return lines;
	}

	protected static boolean anyDisrupted(List<Industry> list) {
		for (Industry ind : list) {
			if (ind != null && ind.isDisrupted()) return true;
		}
		return false;
	}

	/** Why the board's Push order would do nothing now, or null if the front can be ordered to push. */
	public static String pushBlockReason(GroundFront front, MarketAPI market) {
		return ThreatNotice.text(pushRefusal(front, market));
	}

	/** As above, one fact per line for the refusal notice. */
	public static ThreatNotice.Reason pushRefusal(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return ThreatNotice.Reason.of("No front there");
		if (STANCE_PUSH.equals(front.stance)) return ThreatNotice.Reason.of("Already pushing");
		if (front.strataHeld >= market.getSize()) return ThreatNotice.Reason.of("Nothing left to take");
		float eff = effectiveStrength(front);
		float need = holdRequirement(market);
		if (eff < need) {
			ThreatNotice.Reason reason = ThreatNotice.Reason.of(
					"Too weak to push: %s effective of the %s needed to hold",
					Misc.getWithDGS(Math.round(eff)), Misc.getWithDGS(Math.round(need)));
			if (isDry(front)) reason.line("Out of armaments");
			return reason;
		}
		return null;
	}

	/** Why the board's Dig in order would do nothing now, or null. */
	public static String entrenchBlockReason(GroundFront front) {
		return ThreatNotice.text(entrenchRefusal(front));
	}

	/** As above, one fact per line for the refusal notice. */
	public static ThreatNotice.Reason entrenchRefusal(GroundFront front) {
		if (front == null) return ThreatNotice.Reason.of("No front there");
		if (STANCE_ENTRENCH.equals(front.stance)) return ThreatNotice.Reason.of("Already dug in");
		return null;
	}

	/**
	 * Live Defense Swarms at the colony = the orbit is contested. The dialog's
	 * own raid gating already blocks docking while they will fight, so this is
	 * a readout (board, dialog), not an extra rule.
	 */
	public static boolean orbitContested(String marketId) {
		return ThreatColonyManager.countLiveGarrison(marketId) > 0;
	}

	/**
	 * Is the space over this world held against the front's owner? Symmetric
	 * with {@link #orbitContested}, which asks the question only one way round.
	 *
	 * <p>For a besieger of a HIVE, the contest is the hive's Defense Swarms.
	 * For the SWARM besieging a human world it is the reverse: the colony's own
	 * station, and any armed fleet in the neighbourhood that would fight the
	 * Threat - a Guard task force, an escort, the player. That is the whole
	 * counterplay to a Threat landing: keep a fleet over the planet and nothing
	 * comes down.
	 */
	public static boolean orbitContestedFor(GroundFront front, MarketAPI market) {
		String owner = front != null ? front.factionId : null;
		return orbitContestedFor(owner, market);
	}

	public static boolean orbitContestedFor(String ownerFactionId, MarketAPI market) {
		if (market == null || ownerFactionId == null) return false;
		return Theatre.of(market).orbitHeldAgainst(ownerFactionId, market);
	}

	/**
	 * WHO HOLDS AN ORBIT (2026-09-07): a strength contest at the planet, not a
	 * head-count. {@code hostileFP} points of defenders at the world hold its
	 * orbit against a faction only while they are at least
	 * {@code orbitContestFraction} of that faction's own warships there;
	 * with nothing friendly there, any defender holds it. Before this one
	 * respawned Defense Swarm anywhere in the hive's garrison list kept four
	 * Defend fleets of 850 points from firing a shot (Gamma Hero II) - the
	 * hive refills that list every poll from its siblings.
	 */
	public static boolean orbitHeld(String ownerFactionId, MarketAPI market, float hostileFP) {
		if (hostileFP <= 0f) return false;
		float friendly = friendlyPointsNear(ownerFactionId, market);
		return hostileFP >= friendly * Math.max(0f, ThreatIncConfig.orbitContestFraction());
	}

	/** Points of the faction's own warships within ORBIT_HOLD_RANGE of the world - the player's own fleet counts for the player's faction. */
	public static float friendlyPointsNear(String factionId, MarketAPI market) {
		return pointsNear(market, factionId, true);
	}

	/** Points of armed fleets hostile to the faction within ORBIT_HOLD_RANGE of the world: a hive's Defense Swarms, a colony's patrols and station. */
	public static float hostilePointsNear(String factionId, MarketAPI market) {
		return pointsNear(market, factionId, false);
	}

	protected static float pointsNear(MarketAPI market, String factionId, boolean friendly) {
		if (market == null || factionId == null) return 0f;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return 0f;
		float fp = 0f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			// a passing freighter holds nothing
			if (Misc.isTrader(fleet) || Misc.isSmuggler(fleet) || Misc.isScavenger(fleet)) continue;
			boolean own = factionId.equals(fleet.getFaction().getId());
			if (friendly ? !own : own || !fleet.getFaction().isHostileTo(factionId)) continue;
			if (!nearWorld(fleet, market)) continue;
			fp += fleet.getFleetPoints();
		}
		return fp;
	}

	protected static boolean nearWorld(CampaignFleetAPI fleet, MarketAPI market) {
		SectorEntityToken world = market.getPrimaryEntity();
		if (fleet == null || world == null) return false;
		if (fleet.getContainingLocation() != world.getContainingLocation()) return false;
		return Misc.getDistance(fleet.getLocation(), world.getLocation())
				<= ThreatFleetOrders.ORBIT_HOLD_RANGE;
	}

	/**
	 * WHO HOLDS THE SPACE over a world - the board's Space column. The faction
	 * with the most armed points within ORBIT_HOLD_RANGE: a colony's station
	 * and patrols, a hive's Defense Swarms, a Guard task force, the player's
	 * own fleet. Null when nothing armed is there. Traders are ignored, as
	 * everywhere else in the orbit arithmetic.
	 */
	public static String spaceHolder(MarketAPI market) {
		if (market == null) return null;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return null;
		Map<String, Float> points = new LinkedHashMap<String, Float>();
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (Misc.isTrader(fleet) || Misc.isSmuggler(fleet) || Misc.isScavenger(fleet)) continue;
			if (!nearWorld(fleet, market)) continue;
			String id = fleet.getFaction().getId();
			Float had = points.get(id);
			points.put(id, (had == null ? 0f : had) + fleet.getFleetPoints());
		}
		String best = null;
		float bestFP = 0f;
		for (Map.Entry<String, Float> e : points.entrySet()) {
			if (e.getValue() > bestFP) { bestFP = e.getValue(); best = e.getKey(); }
		}
		return best;
	}

	// ------------------------------------------------------------------
	// the tick
	// ------------------------------------------------------------------

	/**
	 * Driven from IncursionManager's colony poll (~half a day). Deliberately
	 * NOT gated on frontsEnabled: the flag gates new deployments only, so a
	 * front deployed before the setting was turned off keeps ticking (and
	 * stays withdrawable) instead of freezing with marines stranded on it.
	 */
	public static void poll(float elapsedDays) {
		if (elapsedDays <= 0f) return;
		tickSupport(elapsedDays);
		ThreatSwarmDefend.tick(elapsedDays);
		sweepSieges();
		ThreatBlockade.sweep();
		// NPC navies relieve their own invaded worlds (sized to the swarm overhead)
		ThreatFleetOrders.planRelief();
		Map<String, GroundFront> fronts = fronts();
		if (fronts.isEmpty()) return;
		for (String marketId : new ArrayList<String>(fronts.keySet())) {
			GroundFront front = fronts.get(marketId);
			if (front == null) {
				fronts.remove(marketId);
				continue;
			}
			MarketAPI market = resolveMarket(marketId);
			if (market == null) {
				MarketAPI raw = Global.getSector().getEconomy().getMarket(marketId);
				evacuate(front, raw);
				fronts.remove(marketId);
				// a besieged world that changed hands or went condition-only must
				// not keep the siege malus/condition without a front behind them
				if (raw != null && syncSiegeState(raw)) raw.reapplyIndustries();
				continue;
			}
			tickFront(front, market, elapsedDays);
		}
	}

	/**
	 * Every human colony with a suppressed defence structure carries the siege
	 * state ({@link ThreatSiegeMalus}), front or no front, and is reapplied each
	 * poll so a structure's condition tracks its clock rather than the monthly
	 * economy step (the hive side's refreshWornDefenses).
	 */
	protected static void sweepSieges() {
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market == null || ThreatMapFog.hidden(market) || ThreatMapFog.conditionOnly(market)) continue;
			if (!Theatre.of(market).carriesSiegeState()) continue;
			boolean has = market.hasIndustry(ThreatSiegeMalus.ID);
			if (!has && !(besieged(market) && ThreatSiegeMalus.anyDisrupted(market))) continue;
			if (syncSiegeState(market) || has) market.reapplyIndustries();
		}
	}

	protected static void tickFront(GroundFront front, MarketAPI market, float elapsedDays) {
		boolean pushing = STANCE_PUSH.equals(front.stance);
		Theatre theatre = Theatre.of(market);
		boolean threatFront = isThreatOwned(front);
		// a world whose strata loss needs a carrier (the malus structure and
		// the tooltip) has it for exactly as long as the front stands; cheap
		// no-op when already right
		if (theatre.carriesSiegeState() && syncSiegeState(market)) market.reapplyIndustries();
		// keep the garrison's call-up current for this tick's own arithmetic.
		// The reserve poll only walks warring factions, and a colony can be
		// invaded by someone it is not formally at war with (2026-09-08).
		if (!isHiveTarget(market)) ThreatReserves.tickMarineArming(market);

		// upkeep: the stockpile is the supply countdown; assaults burn more.
		// The swarm's fronts burn nothing (2026-09-08) - see needsArms
		boolean supplied = !isDry(front);
		if (needsArms(front)) {
			float upkeep = dailyUpkeep(front);
			if (pushing) upkeep *= ThreatIncConfig.frontPushUpkeepMult();
			front.armaments = Math.max(0f, front.armaments - upkeep * elapsedDays);
		}
	// the tick the armaments ran out: they only rise in deploy and resupply,
	// so this is the edge, and the next tick starts dry
	if (supplied && isDry(front)) {
		front.dryTimestamp = Global.getSector().getClock().getTimestamp();
		// a dry front breaks off its assault and digs in (2026-09-06): the
		// swarm's waits for the next expedition, a faction's for its convoys;
		// the player's fronts take the player's orders
		if (!front.isPlayerOwned() && pushing) {
			orderEntrench(front);
			pushing = false;
		}
		// no notice: a siege announces its landing and its end, nothing between (user, 2026-09-27)
		}

		// attrition: flat fraction per 30 days; pushing bleeds harder, and a
		// dry front harder still
		float lossRate = (pushing ? ThreatIncConfig.frontPushLossPer30Days()
				: ThreatIncConfig.frontMarineLossPer30Days()) / 30f;
		if (!supplied) lossRate *= ThreatIncConfig.frontUnsuppliedLossMult();
		if (pushing) {
			// exponent > 1: an outmatched assault bleeds more than a flat rate
			lossRate *= pushLossFactor(defenderStrength(market),
					effectiveStrength(front));
			// the swarm pays on the assault for the two things it no longer
			// pays for: starving, and being unable to replace its dead
			lossRate *= pushLossMult(front);
		}
		lossRate *= ThreatMarineXP.frontLossMult(front); // veterans take fewer casualties
		ThreatMarineXP.frontLose(front, front.marines * lossRate * elapsedDays);
		// The defenders bleed too (2026-09-08). Before this the garrison was a
		// fixed wall a front below the hold threshold could never reach by
		// fighting; now a siege standing on the surface costs the colony its
		// marines day by day, and the wall comes down as well as up.
		//
		// Scaled by the front's pressure and NOT gated on its state: gating it
		// on holding or grinding meant a colony could switch the cost off by
		// reinforcing past the threshold, which is exactly the move this is
		// meant to charge for. A foothold clinging on still kills a few.
		bleedDefenders(market, front, elapsedDays);
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			if (threatFront) {
				ThreatNotice.titled("Threat Landing Destroyed").good()
						.line("The Threat landing on %s has been ground down to nothing.",
								ThreatNotice.market(market))
						.line("The surface is clear.")
						.send();
			} else {
				announceCollapse(front, market);
			}
			ThreatIncConfig.log("Front collapsed at " + market.getName());
			fronts().remove(front.marketId);
			reapply(front.marketId);
			return;
		}

		// Brace (2026-09-08): a counter-attack is close and this front holds no
		// ground, so it breaks off the assault and digs rather than meeting the
		// blow exposed. Done BEFORE the entrenchment accrual below so the cover
		// starts going in this tick, and `pushing` is updated with it - the
		// local is what the rest of the tick reads.
		if (pushing && shouldBrace(front, market)) {
			front.bracedPushProgress = front.pushProgress; // orderEntrench zeroes it
			orderEntrench(front);
			front.bracedFromPush = true;
			pushing = false;
			int inDays = (int) Math.ceil(daysToCounterAttack(front, market));
			ThreatIncConfig.log("Front at " + market.getName() + " braces for a counter-attack in "
					+ inDays + " d");
		} else if (!pushing && front.bracedFromPush && !shouldBrace(front, market)) {
			// Dug in enough (or the odds moved) that an assault would survive
			// the next blow: back to it, picking up where it stopped. Safe to
			// gate on shouldBrace directly now that it asks the stance-neutral
			// question - it no longer flips just because the front took cover.
			//
			// Not through autoPush: that is the player's landing doctrine and
			// its knob, and an NPC front resuming an assault it was already
			// making is neither. This is the one place the progress is carried
			// back over, so both owners have to come through it.
			front.bracedFromPush = false;
			float resumeAt = front.bracedPushProgress;
			front.bracedPushProgress = 0f;
			if (front.strataHeld < market.getSize() && !isDry(front)
					&& effectiveStrength(front) >= holdRequirement(market)
					&& !lastStratumProtected(front, market)) {
				orderPush(front);
				front.pushProgress = resumeAt;
				pushing = true;
				ThreatIncConfig.log("Front at " + market.getName() + " resumes its assault at "
						+ (int) resumeAt + " d of progress");
			}
		}

	front.daysAlive += elapsedDays;
	// cover is dug while holding; an assault leaves it behind (2026-09-06)
	if (!pushing) front.entrenchDays += elapsedDays;

		// suppression state from effective strength vs the CURRENT (worn,
		// strata-stripped) defense figure - a front that holds keeps lowering
		// its own requirement, which is the dig-in-and-grind loop
		float eff = effectiveStrength(front);
		float defender = defenderStrength(market);
		// Hysteresis (2026-09-08). The defence figure used to be a wall that
		// moved only when a structure was suppressed or a district fell; now
		// the garrison bleeds every tick, so a front sitting near a threshold
		// would cross it back and forth, its suppression with it. A state
		// already held is kept until the front drops a band below the line it
		// crossed to reach it.
		float band = Math.max(0f, Math.min(0.5f, ThreatIncConfig.frontStateHysteresis()));
		float holdLine = defender * ThreatIncConfig.frontHoldFraction();
		float grindLine = defender * ThreatIncConfig.frontGrindFraction();
		boolean wasHolding = STATE_HOLDING.equals(front.state);
		boolean wasGrinding = STATE_GRINDING.equals(front.state);
		if (wasHolding) holdLine *= 1f - band;
		if (wasHolding || wasGrinding) grindLine *= 1f - band;
		if (eff >= holdLine) {
			front.state = STATE_HOLDING;
		} else if (eff >= grindLine) {
			front.state = STATE_GRINDING;
		} else {
			front.state = STATE_FOOTHOLD;
		}

		// suppression: the front wears what it reaches by its advantage,
		// frontWearRate x troops / (troops + defence) days a day, and the
		// clock runs down a day a day - so it breaks even near 0.09 x the
		// defence. Not cut by what orbit left standing: boots clear the
		// hardened points bombardment cannot (docs/suppression-balance.md v2).
		float rate = Math.max(0f, ThreatIncConfig.frontWearRate()) * eff / Math.max(1f, eff + defender);
		boolean suppressedAny = false;
		if (STATE_HOLDING.equals(front.state)) {
			for (Industry ind : theatre.keyStructures(market)) {
				suppressedAny |= suppress(ind, rate * elapsedDays);
			}
			suppressedAny |= suppressShield(market, rate * elapsedDays);
		} else if (STATE_GRINDING.equals(front.state)) {
			// only the defense structures
			for (Industry ind : theatre.defenseStructures(market)) {
				suppressedAny |= suppress(ind, rate * elapsedDays);
			}
			suppressedAny |= suppressShield(market, rate * elapsedDays);
		}
	// districts held seize their share of the colony's industries, pinned while held
	if (front.strataHeld > 0) {
		for (Industry ind : theatre.seizedStructures(market)) {
			suppressedAny |= pin(ind, ThreatIncConfig.districtSeizeDays());
		}
	}
	if (suppressedAny) market.reapplyIndustries();

		// the checkpoint: consolidating troops count down their orders window;
		// told nothing, doctrine says push on - or stand fast if too weak
		if (STANCE_CONSOLIDATE.equals(front.stance)) {
			front.consolidateDaysLeft -= elapsedDays;
			if (front.consolidateDaysLeft <= 0f) {
				front.consolidateDaysLeft = 0f;
			if (eff >= defender * ThreatIncConfig.frontHoldFraction() || front.finalPush) {
				front.stance = STANCE_PUSH;
					front.pushProgress = 0f;
				} else {
					front.stance = STANCE_ENTRENCH;
				}
			}
		}

		// the assault on the next stratum - progress only while strong enough
		// to actually hold what it takes
		if (pushing && eff >= defender * ThreatIncConfig.frontHoldFraction()) {
			float ratio = paceRatio(defender, eff);
			front.pushProgress += elapsedDays / ratio;
			if (front.pushProgress >= ThreatIncConfig.frontPushBaseDays()) {
				if (lastStratumProtected(front, market)) {
					// the killing blow is refused: the swarm holds what it has
					// and the siege stands until its armaments run out
					front.pushProgress = 0f;
					orderEntrench(front);
					ThreatIncConfig.log("Threat front at " + market.getName()
							+ " holds short of the last stratum: story-critical world");
				} else {
					takeStratum(front, market);
					if (getFront(front.marketId) == null) return; // core destroyed
				}
			}
		}

		// the colony counter-attacks on a cadence paced by its supplies (hive)
		// or its stability and military command (human): a starved or unstable
		// world cannot mount them - which is what strangling an economy buys
		hiveCounterAttack(front, market);
		if (getFront(front.marketId) == null) return; // front overrun

		// the swarm's answer to an army on its world: hold the orbit
		// the swarm grinds a front it has the orbit over (2026-09-08)
		tickSwarmBombard(front, market, elapsedDays);
		if (getFront(front.marketId) == null) return; // bombarded to nothing

		// dry AND too weak even to grind, the campaign is lost. An NPC or player
		// expedition asks for a pickup run (a supply run that lands first clears
		// the call). The SWARM has nowhere to be picked up to and no reserve to
		// bank into - a spent Threat landing simply dies where it stands.
		if (!front.isPlayerOwned() && !front.withdrawRequested && isDry(front)
				&& eff < defender * ThreatIncConfig.frontGrindFraction()) {
			if (threatFront) {
				ThreatNotice.titled("Threat Landing Spent").good()
						.line("The Threat force on %s has spent itself.", ThreatNotice.market(market))
						.line("Out of armaments and too weak to press, the landing is finished.")
						.send();
				ThreatIncConfig.log("Threat front at " + market.getName()
						+ " collapsed: dry and below grind strength");
				fronts().remove(front.marketId);
				reapply(front.marketId);
				return;
			}
		front.withdrawRequested = true;
		ThreatIncConfig.log("Front at " + market.getName() + " (" + front.factionId
				+ ") requests withdrawal: dry and below grind strength");
	}

	// a front that was already dry before the clock existed (older saves) starts it now
	if (threatFront && isDry(front) && front.dryTimestamp <= 0) {
		front.dryTimestamp = Global.getSector().getClock().getTimestamp();
	}
	// the swarm does not extract (2026-09-06): a dry Threat front holds for the
	// next expedition frontDryFinalPushDays, then spends itself in a final push
	if (threatFront && isDry(front) && !front.finalPush && front.dryTimestamp > 0
			&& Global.getSector().getClock().getElapsedDaysSince(front.dryTimestamp)
					>= ThreatIncConfig.frontDryFinalPushDays()
			&& !lastStratumProtected(front, market)) {
		front.finalPush = true;
		orderPush(front);
		ThreatIncConfig.log("Threat front at " + market.getName() + " makes its final push");
	}

		// NPC stance AI: push whenever strong enough and supplied, dig in
		// otherwise. Consolidation paces them like everyone else; player
		// fronts push when troops land (autoPush), when ordered, or by
		// checkpoint doctrine above - a Dig in order holds until then.
		//
		// shouldBrace is checked here as well as at the break-off above, and
		// that is not belt-and-braces: this runs LATER IN THE SAME TICK, saw
		// the entrench the brace had just ordered, and pushed the front
		// straight back out of cover. The brace was dead for every NPC and
		// Threat front from the day it was written (2026-09-08).
		if (!front.isPlayerOwned() && STANCE_ENTRENCH.equals(front.stance)
				&& !isDry(front)
				&& effectiveStrength(front) >= holdRequirement(market)
				&& !lastStratumProtected(front, market)
				&& !shouldBrace(front, market)) {
			orderPush(front);
		}
	}

	/**
	 * A Threat front one stratum short of a story-critical world it may not
	 * destroy: destroyStoryCritical is off, which only happens mid-siege (with
	 * it off such worlds are never targeted). It holds instead of pushing.
	 */
	protected static boolean lastStratumProtected(GroundFront front, MarketAPI market) {
		if (!isThreatOwned(front) || isHiveTarget(market)) return false;
		if (ThreatIncConfig.destroyStoryCritical() || !Misc.isStoryCritical(market)) return false;
		return front.strataHeld + 1 >= market.getSize();
	}

	/**
	 * A stratum falls; the last one holds the Fabrication Core. Below the Core
	 * the front CONSOLIDATES at the checkpoint: it requests reinforcement and
	 * resupply, waits frontCheckpointDays for orders, and - told nothing -
	 * pushes on by doctrine.
	 */
protected static void takeStratum(GroundFront front, MarketAPI market) {
	front.pushProgress = 0f;
	front.strataHeld++;
	// the assault leaves the cover behind (2026-09-06)
	front.entrenchDays *= Math.max(0f, Math.min(1f, ThreatIncConfig.frontEntrenchKeptFraction()));
	int total = market.getSize();
		ThreatAlarm.add(ownerOf(front), ThreatIncConfig.alarmPerStratum(),
				"stratum taken at " + market.getName());
		if (front.strataHeld >= total) {
			groundVictory(front, market);
			return;
		}
		front.stance = STANCE_CONSOLIDATE;
		front.consolidateDaysLeft = ThreatIncConfig.frontCheckpointDays();
		reapply(front.marketId); // strata strip base defense and fabrication
		ThreatIncConfig.log("Front took stratum " + front.strataHeld + "/" + total
				+ " at " + market.getName());
	}

	/**
	 * The final stratum is taken and the Fabrication Core destroyed: the
	 * colony is ERADICATED - the only way a hive dies (saturation takes no size
	 * off it, ThreatRazing.razes). The vanilla teardown
	 * runs and the survivors come home; nothing is raised on the dead world
	 * (2026-09-27). pollColonies reacts next poll.
	 *
	 * <p>A THREAT front winning on a human world is the mirror image and shares
	 * none of that bookkeeping - see {@link #colonyGroundVictory}. The theatre
	 * decides which it is.
	 */
	protected static void groundVictory(GroundFront front, MarketAPI market) {
		Theatre.of(market).victory(front, market);
	}

	/** A hive's last stratum has fallen: eradication, the survivors home (or into an outpost already there), the swarm's answer. */
	protected static void hiveGroundVictory(GroundFront front, MarketAPI market) {
		fronts().remove(front.marketId);
		String winner = ownerOf(front);
		FactionAPI winnerFaction = ownerFaction(front);
		ThreatNotice n = ThreatNotice.titled("Hive Eradicated").good().icon(winnerFaction);
		if (front.isPlayerOwned()) {
			n.line("Your front has destroyed the Fabrication Core of %s.", ThreatNotice.market(market));
		} else {
			n.line("%s's front has destroyed the Fabrication Core of %s.",
					ThreatNotice.faction(winnerFaction), ThreatNotice.market(market));
		}
		n.line("The strata are cold.").send();
		ThreatIncConfig.log("Ground victory at " + market.getName());
		StarSystemAPI where = market.getStarSystem();
		// held before the teardown: the market's entity and position are the
		// only handles on the world once decivilize has run
		SectorEntityToken world = market.getPrimaryEntity();
		Vector2f hyperLoc = market.getLocationInHyperspace();
		ThreatColonyManager.eradicate(market);
		// nothing is raised on the freed world (user's call 2026-09-27, player and
		// NPC alike): the front's survivors come home - an NPC's bank into its
		// nearest base's reserve, the player's board the player's fleet
		// (evacuate). Only an outpost already standing there takes them. Runs
		// 10-15's victory bases mostly died within days to the system's other
		// hive worlds or its leftover swarms, the survivors with them
		ThreatOutposts.Outpost outpost = null;
		if (world != null && ThreatIncConfig.outpostsEnabled()) {
			outpost = ThreatOutposts.outpostAt(world.getId());
			if (outpost != null && !outpost.alive()) outpost = null;
		}
		evacuate(front, outpost, hyperLoc);
		// the swarm answers (docs/design-theory.md 8.1): grudge, and a strike
		// at the winner from the nearest hive that can muster one
		ThreatAlarm.add(winner, ThreatIncConfig.alarmPerEradication(),
				"eradication of " + market.getName());
		IncursionManager.retaliate(winner, where);
	}

	/**
	 * The swarm has taken the last stratum of an inhabited world: the colony is
	 * LOST. The symmetric rule in full - only a ground victory kills a colony,
	 * whichever direction it is being fought in.
	 *
	 * <p>None of the hive-side bookkeeping applies. {@code eradicate} is hive
	 * teardown, so the kill goes through vanilla's own
	 * {@link com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker} - but
	 * only after {@link #KILLED_BY_FLAG} names the Threat, because that is what
	 * {@code IncursionManager.processPendingDecivChecks} reads to decide the
	 * swarm may come back for the ruins ("the swarm does not abandon its
	 * kills"). No outpost is raised (the swarm builds none), no alarm or
	 * retaliation fires (those answer a hive being lost, not won), and there is
	 * no reserve anywhere to evacuate the survivors into.
	 *
	 * <p>A story-critical world the player has not opted to lose never gets
	 * here: {@link #lastStratumProtected} keeps the front short of the last
	 * stratum, holding, until it withers.
	 */
	protected static void colonyGroundVictory(GroundFront front, MarketAPI market) {
		fronts().remove(front.marketId);
		if (syncSiegeState(market)) market.reapplyIndustries(); // strip the malus/tooltip before the teardown
		ThreatNotice.titled("Colony Lost").bad()
				.line("%s has fallen to the Threat ground assault.", ThreatNotice.market(market))
				.send();
		ThreatIncConfig.log("Threat ground victory at " + market.getName());
		// any defence contract for this colony has failed for good
		ThreatAidMissionIntel.strikeLanded(market);
		// provenance: processPendingDecivChecks accepts the kill as the swarm's
		// when the world names the Threat here (or carries the Threat's own
		// bombardment flag, the saturation doctrine's mark)
		// the swarm converts what it conquers (2026-09-06): a hive is seeded on
		// the spot. With conquestConverts off, or a world that cannot carry a
		// hive, the kill goes vanilla's deciv way and the swarm comes back for
		// the ruins later.
		SectorEntityToken world = market.getPrimaryEntity();
		if (ThreatIncConfig.conquestConverts() && world instanceof PlanetAPI) {
			MarketAPI hive = ThreatColonyManager.convertConquered((PlanetAPI) world, market);
			if (hive != null) {
				ThreatNotice.titled("Hive Seeded").bad()
						.line("The swarm has seeded a hive on the ruins of %s.", ThreatNotice.market(hive))
						.send();
				return;
			}
			// the teardown ran but no hive could be seeded: the world is already a
			// ruin flagged for the swarm's return - never decivilise it twice
			if (!market.isInEconomy() || ThreatMapFog.conditionOnly(market)) return;
		}
		market.getMemoryWithoutUpdate().set(KILLED_BY_FLAG, Factions.THREAT, 60f);
		com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker.decivilize(market, true);
	}

	/**
	 * Saturation razed a human colony's last level: it is gone, by vanilla's
	 * own teardown, and a front on it dies with it. Razed by the swarm, the
	 * ruin is the swarm's kill, as a ground victory's is.
	 */
	protected static void colonyRazed(MarketAPI market, String razerFactionId) {
		String razer = razerFactionId != null ? razerFactionId : Factions.PLAYER;
		if (getFront(market.getId()) != null) {
			fronts().remove(market.getId());
			if (syncSiegeState(market)) market.reapplyIndustries();
		}
		if (Factions.THREAT.equals(razer)) {
			ThreatNotice.titled("Colony Razed").bad()
					.line("The Threat has razed %s from orbit.", ThreatNotice.market(market))
					.send();
			ThreatAidMissionIntel.strikeLanded(market);
			market.getMemoryWithoutUpdate().set(KILLED_BY_FLAG, Factions.THREAT, 60f);
		} else if (!Factions.PLAYER.equals(razer)) {
			FactionAPI f = Global.getSector().getFaction(razer);
			ThreatNotice.titled("Colony Razed").icon(f)
					.line("%s has razed %s from orbit.", ThreatNotice.faction(f), ThreatNotice.market(market))
					.send();
		}
		ThreatIncConfig.log("Colony razed from orbit: " + market.getName() + " by " + razer);
		DecivTracker.decivilize(market, true);
	}

	/**
	 * Strength contest on the counter-attack cadence: the colony's current
	 * defense figure against the front's (entrenchment-boosted) strength.
	 * Losing costs marines and a held stratum; a beachhead beaten twice over
	 * is destroyed outright. Cadence comes from {@link #counterAttackInterval}
	 * - the supplies a hive world is paid, stability and military command on a
	 * human one.
	 */
	/**
	 * Threat doctrine over its own besieged world. While the swarm holds the
	 * orbit in force (swarmOrbitMinFleetFP of Threat fleet points at the planet -
	 * a bombardment is a fleet operation, not a lone frigate) and no armed
	 * hostile fleet contests it, it bombards the enemy front on the surface:
	 * marines die day by day at swarmFrontBombardPer30Days, scaled by the
	 * swarm's weight against what the front can put in the way.
	 *
	 * <p>This REPLACES the scour (removed 2026-09-08, user): the hive used to
	 * saturation-bomb its own surface after threatScourDays unopposed and
	 * annihilate the front outright, to the last marine, with fallout closing
	 * the ground afterwards. That predated the siege system and cut across all
	 * of it - orbital superiority skipped the ground war rather than feeding it,
	 * and no amount of entrenchment, veterancy or reinforcement mattered against
	 * a guillotine on a timer. A swarm that owns the orbit now does what any
	 * other besieger does: it grinds, and the counter-attacks (paced by
	 * counterAttackTempo, which answers being outnumbered) do the rest.
	 *
	 * <p>The counterplay is unchanged in shape - get a fleet over the planet -
	 * but it is now a question of degree rather than a deadline: contesting the
	 * orbit stops the bleeding, and arriving late costs troops instead of
	 * everything.
	 */
	protected static void tickSwarmBombard(GroundFront front, MarketAPI market, float elapsedDays) {
		if (isThreatOwned(front) || !isHiveTarget(market)) return;
		if (elapsedDays <= 0f) return;
		if (!swarmHoldsOrbit(market)) {
			front.swarmOrbitDays = 0f;
			return;
		}
		front.swarmOrbitDays += elapsedDays;
		float loss = swarmBombardPer30Days(front, market) / 30f * elapsedDays;
		if (loss <= 0f) return;
		ThreatMarineXP.frontLose(front, loss);
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatNotice n = ThreatNotice.titled("Front Destroyed"); // the swarm's doing: its crest and colour
			if (front.isPlayerOwned()) {
				n.line("Your front on %s has been bombarded to nothing from orbit.",
						ThreatNotice.market(market));
			} else {
				n.line("%s's front on %s has been bombarded to nothing from orbit.",
						ThreatNotice.faction(ownerFaction(front)), ThreatNotice.market(market));
			}
			n.send();
			ThreatIncConfig.log("Front at " + market.getName() + " (" + ownerOf(front)
					+ ") destroyed by swarm orbital bombardment");
			fronts().remove(front.marketId);
			reapply(front.marketId);
		}
	}

	/**
	 * Troops the swarm's orbital bombardment costs this front per 30 days: the
	 * base rate scaled by the swarm's weight over the planet against what the
	 * front can put in the way, and by the front's own veterancy. 0 when the
	 * swarm does not hold the orbit - the board quotes this, so it is the tick's
	 * own arithmetic.
	 */
	public static float swarmBombardPer30Days(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		if (isThreatOwned(front) || !isHiveTarget(market)) return 0f;
		if (!swarmHoldsOrbit(market)) return 0f;
		float per30 = ThreatIncConfig.swarmFrontBombardPer30Days();
		if (per30 <= 0f) return 0f;
		float fp = swarmOrbitStrength(market);
		float ratio = fp / Math.max(1f, fp + defenseStrength(front));
		return front.marines * per30 * ratio * ThreatMarineXP.frontLossMult(front);
	}

	/**
	 * Swarm space superiority over a hive world: the swarm is present in force
	 * ({@link #swarmPresent}) and NO armed fleet hostile to the Threat is within
	 * range to contest it. The mirror of the colony theatre's orbitHeldAgainst.
	 */
	public static boolean swarmHoldsOrbit(MarketAPI market) {
		return swarmPresent(market) && !swarmOrbitContested(market);
	}

	/**
	 * Total Threat fleet strength (fleet points) within the swarm orbit radius of a hive
	 * world - garrison Defense Swarms in orbit and any Threat combat fleet alike
	 * (they are the same kind of fleet, faction Threat, orbiting the planet).
	 */
	public static float swarmOrbitStrength(MarketAPI market) {
		if (market == null) return 0f;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return 0f;
		float fp = 0f;
		float range = 1500f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (!Factions.THREAT.equals(fleet.getFaction().getId())) continue;
			if (Misc.getDistance(fleet.getLocation(), world.getLocation()) > range) continue;
			fp += fleet.getFleetPoints();
		}
		return fp;
	}

	/**
	 * Enough Threat strength in orbit to mount a saturation bombardment: total
	 * Threat fleet points at the planet clear swarmOrbitMinFleetFP. A lone
	 * battered frigate is not a bombardment platform (user, 2026-09-06); the
	 * smallest real Defense Swarm is well above the floor.
	 */
	public static boolean swarmPresent(MarketAPI market) {
		return swarmOrbitStrength(market) >= ThreatIncConfig.swarmOrbitMinFleetFP();
	}

	/**
	 * An armed fleet hostile to the Threat is within the swarm orbit radius of the hive
	 * world - the swarm's orbital dominance is being contested. This is what
	 * STOPS the swarm bombarding an enemy front ({@link #tickSwarmBombard}),
	 * and it is the whole counterplay: keep something armed over the planet.
	 *
	 * <p>Note the opposite sense of {@link #orbitContested(String)}, which asks
	 * whether the hive's own Defense Swarms hold the orbit against a besieger;
	 * this asks whether something holds it against the swarm.
	 */
	public static boolean swarmOrbitContested(MarketAPI market) {
		if (market == null) return false;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return false;
		float range = 1500f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (Misc.getDistance(fleet.getLocation(), world.getLocation()) > range) continue;
			if (fleet.getFaction().isHostileTo(Factions.THREAT)) return true;
		}
		// an unspawned expedition's flotilla over its own front (coverFP): runs 6
		// and 7 lost every autoresolved landing on Gamma Golgotha II to an orbit
		// nobody held, because an abstract siege leaves no fleet to put on DEFEND
		GroundFront front = getFront(market.getId());
		if (front != null && front.coverFP > 0f && !isThreatOwned(front)) {
			float swarm = swarmOrbitStrength(market);
			if (front.coverFP >= swarm) return true;
			ThreatIncConfig.log("Orbit cover over " + market.getName() + " lost: " + (int) swarm
					+ " FP of swarms outweigh the " + front.factionId + " flotilla's " + (int) front.coverFP);
			front.coverFP = 0f;
		}
		return false;
	}

	/** Whether this faction's front on the world holds its orbit with an autoresolved flotilla's cover (coverFP) that still outweighs the swarm there. */
	public static boolean coverHolds(MarketAPI market, String factionId) {
		if (market == null || factionId == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || front.coverFP <= 0f || !factionId.equals(front.factionId)) return false;
		return front.coverFP >= swarmOrbitStrength(market);
	}

	/** Days the swarm has held the orbit over this front unopposed; 0 when it does not. */
	public static float swarmOrbitDaysHeld(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		if (isThreatOwned(front) || !isHiveTarget(market)) return 0f;
		return swarmHoldsOrbit(market) ? Math.max(0f, front.swarmOrbitDays) : 0f;
	}

	/**
	 * The swarm is over the planet in force but an armed hostile fleet is
	 * contesting the orbit, so its bombardment is stopped. The counterplay,
	 * seen from the board.
	 */
	public static boolean swarmBombardContested(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		if (isThreatOwned(front) || !isHiveTarget(market)) return false;
		return swarmPresent(market) && swarmOrbitContested(market);
	}

	/**
	 * Marines the colony loses per 30 days to a siege standing on its surface,
	 * at the front's current pressure. The rate is
	 * {@code defenderLossPer30Days} at full pressure - a front strong enough to
	 * hold - and scales down with what the front can actually bring to bear, so
	 * a beachhead that is barely surviving kills nobody.
	 *
	 * <p>Hives are not here: their strength is structural and erodes by losing
	 * strata instead ({@link Theatre#HIVE}).
	 */
	public static float defenderLossPer30Days(MarketAPI market, GroundFront front) {
		if (market == null || front == null || isHiveTarget(market)) return 0f;
		float armed = ThreatReserves.armedMarines(market);
		if (armed <= 0f) return 0f;
		float need = holdRequirement(market);
		float pressure = need <= 0f ? 1f : Math.min(1f, effectiveStrength(front) / need);
		if (pressure <= 0f) return 0f;
		float rate = ThreatIncConfig.defenderLossPer30Days() * pressure;
		rate *= ThreatMarineXP.lossMult(ThreatMarineXP.colonyLevel(market));
		return engaged(armed, front) * rate;
	}

	/**
	 * The defenders a front actually fights: the garrison, but no more than the
	 * beachhead's own headcount - the frontage is the smaller force. Losses on
	 * the whole garrison let a 1,000-marine beachhead bleed a 5,000-marine world
	 * 1,000 a month and 15% per counter-attack more; the Hegemony lost ~39k
	 * marines to fronts that lost ~2k (2026-09-29, ti-h8h), and five worlds
	 * fell once relief convoys had fed the grinder dry.
	 */
	protected static float engaged(float armed, GroundFront front) {
		return front.marines > 0f ? Math.min(armed, front.marines) : armed;
	}

	/**
	 * How much the force ratio speeds up, or slows, a colony's counter-attacks
	 * (user, 2026-09-08). Before this the cadence read stability and nothing
	 * else: a colony with 5,000 marines went over the top exactly as often as
	 * one with 50, so reinforcing a besieged world bought strength but never
	 * tempo.
	 *
	 * <p>The ratio is the one the counter-attack actually fights at -
	 * {@link #counterAttackStrength} against the front's effective strength -
	 * not a raw headcount, so a colony with no marines left still musters its
	 * garrison rather than falling silent. Clamped both ways by
	 * {@code counterAttackRatioClamp}: at 3.0 a world that outmatches the
	 * beachhead threefold hits three times as often, and one being overrun
	 * three-to-one manages a third as many.
	 *
	 * <p>It pairs with {@code defenderCounterAttackLossFraction}: more tempo
	 * means more attempts, and every attempt costs marines. A colony that
	 * hugely outnumbers a front wins quickly and pays for it, which is the
	 * whole shape this was after.
	 */
	public static float counterAttackTempo(GroundFront front, MarketAPI market) {
		float clamp = Math.max(1f, ThreatIncConfig.counterAttackRatioClamp());
		if (front == null || market == null || clamp <= 1f) return 1f;
		// Both theatres (user, 2026-09-08). A hive that outnumbers a beachhead
		// answers it sooner for the same reason a colony does - having enough
		// body to spare is what lets either mount the counterstroke at all.
		// The hive's supplies still pace it underneath; this only scales that.
		float eff = effectiveStrength(front);
		if (eff <= 0f) return clamp;
		float ratio = ratioPow(counterAttackStrength(market) / Math.max(1f, eff));
		return Math.max(1f / clamp, Math.min(clamp, ratio));
	}

	/**
	 * Marines a counter-attack costs the colony that mounts it - win or lose,
	 * and worse when it bounces off a dug-in front. The tick's own arithmetic,
	 * so the board's "it costs them" line quotes what will actually happen.
	 */
	public static float counterAttackDefenderLoss(GroundFront front, MarketAPI market) {
		if (market == null || front == null || isHiveTarget(market)) return 0f;
		float armed = ThreatReserves.armedMarines(market);
		if (armed <= 0f) return 0f;
		float exponent = Math.max(0f, ThreatIncConfig.groundStrengthExponent() - 1f);
		float defOdds = ratioPow(defenseStrength(front)
				/ Math.max(1f, counterAttackStrength(market)));
		return engaged(armed, front) * ThreatIncConfig.defenderCounterAttackLossFraction()
				* Math.min(2f, (float) Math.pow(Math.max(1f, defOdds), exponent))
				* ThreatMarineXP.lossMult(ThreatMarineXP.colonyLevel(market));
	}

	/** Applies one tick of that; returns the marines actually lost. */
	protected static float bleedDefenders(MarketAPI market, GroundFront front, float elapsedDays) {
		if (elapsedDays <= 0f) return 0f;
		float loss = defenderLossPer30Days(market, front) / 30f * elapsedDays;
		if (loss <= 0f) return 0f;
		return ThreatReserves.spendDefendingMarines(market, loss);
	}

	protected static void hiveCounterAttack(GroundFront front, MarketAPI market) {
		boolean threatFront = isThreatOwned(front);
		Theatre theatre = Theatre.of(market);
		float interval = theatre.counterAttackInterval(front, market);
		float since = Global.getSector().getClock()
				.getElapsedDaysSince(front.lastCounterAttack);
		if (since < interval) return;
		front.lastCounterAttack = Global.getSector().getClock().getTimestamp();

		float attack = counterAttackStrength(market);
		float defense = defenseStrength(front);
		float exponent = Math.max(0f, ThreatIncConfig.groundStrengthExponent() - 1f);

		// What each side learns is fixed by the fight it walked into, on the
		// headcount that walked in - vanilla grants raid XP the same way, and
		// on the same shape: the side that was outmatched learns the most and a
		// curbstomp teaches nobody anything (2026-09-08).
		float frontGain = ThreatMarineXP.xpGain(defense, attack, front.marines);
		float armed = ThreatReserves.armedMarines(market);
		float colonyGain = ThreatMarineXP.xpGain(attack, defense, armed);

		// The colony pays for the attempt whether or not it lands. Going over
		// the top costs marines, and bouncing off a dug-in front costs more -
		// before 2026-09-08 a repelled counter-attack was free, so a colony
		// could throw its garrison at a beachhead every cadence forever.
		float defLost = ThreatReserves.spendDefendingMarines(market,
				counterAttackDefenderLoss(front, market));
		ThreatReserves.addMarineXp(market, colonyGain);
		front.xp = ThreatMarineXP.addXp(front.xp, frontGain, front.marines);

		if (attack <= defense) {
			ThreatIncConfig.log("Counter-attack repelled at " + market.getName()
					+ " (" + (int) attack + " vs " + (int) defense + ")"
					+ (defLost > 0f ? ", " + Math.round(defLost) + " defenders lost" : ""));
			return;
		}

		// exponent > 1: a counter-attack that outmatches the front badly costs
		// it more than the flat fraction, and overruns a beachhead sooner
		float odds = ratioPow(attack / Math.max(1f, defense));
		// the board's own figure, at the strengths this counter-attack fought
		// at, so the quote and the casualty are one arithmetic
		float loss = counterAttackLoss(front, attack, defense);
		ThreatMarineXP.frontLose(front, loss);

	// thrown back or battered, the front's cover is spoilt (2026-09-06)
	front.entrenchDays *= Math.max(0f, Math.min(1f, ThreatIncConfig.frontEntrenchKeptFraction()));
	if (front.strataHeld > 0) {
		front.strataHeld--;
		front.pushProgress = 0f;
		// a final push does not stop for a setback - it is spent either way
		front.stance = front.finalPush ? STANCE_PUSH : STANCE_ENTRENCH;
		reapply(front.marketId);
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " retook a stratum ("
					+ (int) attack + " vs " + (int) defense + "): " + front.strataHeld
					+ " held, " + Math.round(loss) + " marines lost");
		} else if (odds > 2f) {
			// a hive's counter-attack is the swarm's (Threat crest); a colony's is its garrison's
			FactionAPI attacker = theatre == HIVE ? null : market.getFaction();
			String by = theatre == HIVE ? "A hive counter-attack on %s" : "The garrison of %s";
			ThreatNotice n = ThreatNotice.titled("Beachhead Overrun").icon(attacker);
			if (threatFront) n.good(); else n.bad();
			n.line(by + " has overrun the beachhead.", ThreatNotice.market(market))
					.line("The front is destroyed.")
					.send();
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " overran the beachhead ("
					+ (int) attack + " vs " + (int) defense + ")");
			fronts().remove(front.marketId);
			reapply(front.marketId);
			return;
		} else {
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " battered the beachhead ("
					+ (int) attack + " vs " + (int) defense + "): " + Math.round(loss)
					+ " marines lost");
		}
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			if (threatFront) {
				ThreatNotice.titled("Threat Landing Destroyed").good()
						.line("The Threat landing on %s has been destroyed.", ThreatNotice.market(market))
						.line("The surface is clear.")
						.send();
			} else {
				announceCollapse(front, market);
			}
			fronts().remove(front.marketId);
			reapply(front.marketId);
		}
	}

	/** What a holding front suppresses - the theatre's list. */
	protected static List<Industry> keyStructures(MarketAPI market) {
		if (market == null) return new ArrayList<Industry>();
		return Theatre.of(market).keyStructures(market);
	}

	/**
	 * What a holding front suppresses on a human colony, derived the way the
	 * rest of the mod derives it: every industry vanilla itself tags
	 * TAG_TACTICAL_BOMBARDMENT (Ground Defenses, Heavy Batteries, Patrol HQ,
	 * Military Base, High Command, Lion's Guard), plus the port - the same set
	 * an expedition softens from orbit, now ground down from the surface.
	 */
	public static List<Industry> colonyKeyStructures(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getSpec() == null) continue;
			if (ThreatSiegeMalus.ID.equals(ind.getId())) continue; // our own carrier
			if (!ind.getSpec().hasTag(com.fs.starfarer.api.impl.campaign.ids.Industries
					.TAG_TACTICAL_BOMBARDMENT)) {
				continue;
			}
			list.add(ind);
		}
		Industry port = ThreatColonyManager.getPort(market);
		if (port != null && !list.contains(port)) list.add(port);
		return list;
	}

	/**
	 * Adds days to a structure's disruption clock, capped so the figure stays
	 * sane (wear maxes out at defenseWearDays anyway). Returns whether the
	 * structure was touched.
	 */
	protected static boolean suppress(Industry ind, float addDays) {
		if (ind == null || addDays <= 0f) return false;
		// the theatre's own clock, a fifth past worn-out
		float cap = (ind.getMarket() != null ? Theatre.of(ind.getMarket()).wearDays()
				: Math.max(1f, ThreatIncConfig.defenseWearDays())) * 1.2f;
		float cur = siegeDisruptDays(ind);
		if (cur >= cap) return true; // already pinned at the cap - still "suppressed"
		ind.setDisrupted(Math.min(cap, cur + addDays));
		return true;
	}

	/**
	 * The shield is a military structure and boots grind it at the same rate as
	 * the guns. Kept out of {@code keyStructures} /
	 * {@code defenseStructures} because those lists also answer "are the
	 * colony's defences held", which the shield does not speak to.
	 */
	protected static boolean suppressShield(MarketAPI market, float addDays) {
		if (!ThreatShield.present(market)) return false;
		return suppress(ThreatShield.get(market), addDays);
	}

	/**
	 * Pins a seized industry's disruption clock at {@code days} at least: it
	 * stays down while held and recovers that fast once the district is
	 * retaken. Returns whether it was touched.
	 */
	protected static boolean pin(Industry ind, float days) {
		if (ind == null || days <= 0f || !ind.canBeDisrupted()) return false;
		if (siegeDisruptDays(ind) >= days) return false;
		ind.setDisrupted(days);
		return true;
	}

	/**
	 * What a front holding districts of a human colony has seized besides the
	 * key structures: held / size of the colony's other disruptable
	 * industries, in a fixed order (by id) so the same districts stay seized
	 * from one poll to the next.
	 */
	public static List<Industry> seizedIndustries(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		int size = Math.max(1, market.getSize());
		int held = Math.max(0, Math.min(size, strataHeld(market.getId())));
		if (held <= 0) return list;
		List<Industry> key = colonyKeyStructures(market);
		List<Industry> rest = new ArrayList<Industry>();
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getSpec() == null || ind.getId() == null) continue;
			if (key.contains(ind)) continue;
			if (ind.getId().startsWith("threatinc_")) continue;
			if (Industries.POPULATION.equals(ind.getId())) continue;
			if (!ind.canBeDisrupted()) continue;
			rest.add(ind);
		}
		Collections.sort(rest, new Comparator<Industry>() {
			public int compare(Industry a, Industry b) {
				return a.getId().compareTo(b.getId());
			}
		});
		int n = Math.min(rest.size(), (int) Math.ceil(rest.size() * held / (float) size));
		list.addAll(rest.subList(0, n));
		return list;
	}

	// ------------------------------------------------------------------
	// the orbital siege of a human colony (docs/ground-war.md "Sieges against
	// human factions", 2026-09-06)
	// ------------------------------------------------------------------

	/** Fleet memory: battery damage banked below the cost of the smallest ship. */
	public static final String SIEGE_DAMAGE_KEY = "$threatinc_siegeDamage";
	/**
	 * Market memory: the swarm has besieged this colony from orbit within
	 * fortificationDisruptDays. The fortification rule applies to besieged
	 * colonies (and any with a front) - not to every raid in the sector.
	 */
	public static final String BESIEGED_FLAG = "$threatinc_besieged";

	/** Whether the world has been besieged from orbit recently enough for the fortification rule to apply. */
	public static boolean besieged(MarketAPI market) {
		return market != null && market.getMemoryWithoutUpdate().getBoolean(BESIEGED_FLAG);
	}

	/** Fleet memory: when this fleet last delivered a slice of a siege. */
	public static final String SIEGE_LAST_KEY = "$threatinc_siegeLast";
	/** Market memory: when a slice last made up the clocks' run-down - once per world, however many fleets slice it. */
	public static final String SIEGE_MADE_UP_KEY = "$threatinc_siegeMadeUp";
	/** Market memory: suppression days banked toward the next bombardment burst (one per whole day added). */
	public static final String SIEGE_VISUAL_KEY = "$threatinc_siegeVisualDays";
	/** Fleet memory: this fleet's Support / Defend slice has been logged today. */
	public static final String SLICE_LOG_KEY = "$threatinc_sliceLogged";
	/** Days a first slice stands for - vanilla's own gap between a fleet's passes. */
	public static final float SIEGE_FIRST_SLICE_DAYS = 3f;
	public static final float SIEGE_MAX_SLICE_DAYS = 10f;
	/** Abstract fleet points per difficulty point, for an expedition that never spawned. */
	public static final float ABSTRACT_FP_PER_POINT = 25f;

	/** Disruption days at which this world's fortifications have worn to nothing - as far as a siege can push them. */
	public static float siegeWornDays(MarketAPI market) {
		return Theatre.of(market).wearDays();
	}

	/**
	 * The disruption days the siege should read - 0 unless the structure is
	 * ACTUALLY disrupted. Vanilla stores disruption as a market-memory entry:
	 * isDisrupted() reads its value, getDisruptedDays() reads its expire. When
	 * disruption is cleared with setDisrupted(0) the value is unset but the
	 * expire keeps counting down (observed: a Swarm Nexus reading disr=394 with
	 * isDisrupted()=false, memVal=null), so a bare getDisruptedDays() reports a
	 * ghost clock. Reading it directly made the siege skip such a structure as
	 * "already worn out" forever, so bombardment could never touch
	 * it though it was not disrupted at all. Gate on the real state.
	 */
	public static float siegeDisruptDays(Industry ind) {
		return ind != null && ind.isDisrupted() ? ind.getDisruptedDays() : 0f;
	}

	/** The siege clock as the board reads it: the least-disrupted fortification's days, 0 with none. */
	public static float siegeClock(MarketAPI market) {
		if (market == null) return 0f;
		float least = Float.MAX_VALUE;
		for (Industry ind : Theatre.of(market).fortifications(market)) {
			least = Math.min(least, siegeDisruptDays(ind));
		}
		return least == Float.MAX_VALUE ? 0f : least;
	}

	/** One line of what is true now: "Defences at N%." */
	public static String siegeClockLine(MarketAPI market) {
		if (market == null) return "";
		if (Theatre.of(market).fortifications(market).isEmpty()) return "No defence structure to suppress.";
		return "Defences at " + Math.round(fortificationCondition(market) * 100f) + "%.";
	}

	/** What still stands of the world's fortifications: their mean condition, 1 with none. */
	public static float fortificationCondition(MarketAPI market) {
		if (market == null) return 1f;
		Theatre theatre = Theatre.of(market);
		List<Industry> forts = theatre.fortifications(market);
		if (forts.isEmpty()) return 1f;
		float cond = 0f;
		for (Industry ind : forts) cond += theatre.condition(market, ind);
		return cond / forts.size();
	}

	/** A hive fortification's defence bonus at full condition: the Nexus's, the military tier's, or a battery's after its deficits (SwarmNexus, SwarmBastion, ThreatGroundDefenses). */
	public static float hiveFortificationBonus(Industry ind) {
		if (ThreatColonyManager.SWARM_NEXUS.equals(ind.getId())) return ThreatIncConfig.nexusDefenseBonus();
		if (SwarmBastion.isTier(ind.getId())) return SwarmBastion.defenseBonus(ind.getId());
		float bonus = ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(ind.getId())
				? ThreatIncConfig.heavyBatteriesBonus() : ThreatIncConfig.groundDefensesBonus();
		return bonus * ThreatSiegeMalus.deficitMult(ind, Commodities.HEAVY_MACHINERY, Commodities.METALS);
	}

	/** A fortification's defence bonus at full condition on either theatre, after its input deficits. */
	public static float fortificationBonus(MarketAPI market, Industry ind) {
		if (Theatre.of(market) == HIVE) return hiveFortificationBonus(ind);
		return ThreatSiegeMalus.bonusOf(ind.getId()) * ThreatSiegeMalus.deficitMult(ind);
	}

	// ------------------------------------------------------------------
	// THE BOMBARDMENT DAY (docs/suppression-balance.md "v2", 2026-09-28).
	// Orbit softens; boots finish. A day of bombardment adds the theatre's
	// rate x fleet / (fleet + defence) x what still stands of each
	// fortification to its clock, so each pass finds fewer targets and the
	// dial falls more slowly the further it goes - no floor, no cap. The guns
	// answer at their own rate until silenced, the fleet burns its ordnance,
	// and the world's unrest is raised to what the bombardment has broken. The
	// player's tactical bombardment, every AI siege fleet and every Support or
	// Defend sortie run this one rule; saturation runs it on every building.
	// ------------------------------------------------------------------

	/** Sector memory: the player's fleet bombarded within bombardCooldownDays (tactical and saturation share it). */
	public static final String BOMBARD_LOCK_KEY = "$threatinc_bombardLock";
	/** Market memory: this mod set vanilla's no-decivilisation key while a siege lasts. */
	public static final String DECIV_HELD_KEY = "$threatinc_decivHeld";

	/** Whether the player's fleet bombarded too recently to organize another. */
	public static boolean bombardLocked() {
		return Global.getSector().getMemoryWithoutUpdate().getBoolean(BOMBARD_LOCK_KEY);
	}

	/** Starts the player's lock after a bombardment: vanilla's raid cooldown, for bombardment. */
	public static void lockBombard() {
		float days = ThreatIncConfig.bombardCooldownDays();
		if (days > 0f) Global.getSector().getMemoryWithoutUpdate().set(BOMBARD_LOCK_KEY, true, days);
	}

	/** Whether orbit has anything to bombard here: a fortification, or a shield. */
	public static boolean bombardable(MarketAPI market) {
		if (market == null) return false;
		return !Theatre.of(market).fortifications(market).isEmpty() || ThreatShield.present(market);
	}

	/** The defence the guns add: the figure times the batteries' share, D x (1 - 1 / their multiplier). */
	public static float gunDefence(MarketAPI market, float defence) {
		if (market == null) return 0f;
		return Math.max(0f, defence) * Theatre.of(market).batteryShare(market);
	}

	/** Fleet points the guns take per day from whatever bombards the world - set by the guns, not the fleet. */
	public static float returnFirePerDay(MarketAPI market, float defence) {
		return Math.max(0f, ThreatIncConfig.bombardReturnFirePerGunDefence()) * gunDefence(market, defence);
	}

	/** Fuel a day of tactical bombardment burns for a fleet of fp. */
	public static float bombardFuelPerDay(float fp) {
		return Math.max(0f, ThreatIncConfig.bombardFuelPerFPDay()) * Math.max(0f, fp);
	}

	/** Days of tactical bombardment this much fuel buys a fleet of fp; with no price, as long as it likes. */
	public static float bombardDaysFor(float fuel, float fp) {
		float perDay = bombardFuelPerDay(fp);
		if (perDay <= 0f) return Float.MAX_VALUE;
		return Math.max(0f, fuel) / perDay;
	}

	/** Disruption days a day of bombardment adds to a structure still whole: the theatre's rate x fleet / (fleet + defence). */
	public static float suppressionRate(MarketAPI market, float fp, float defence) {
		if (market == null || fp <= 0f) return 0f;
		float weighted = fp * Math.max(0f, ThreatIncConfig.siegeFPWeight());
		return Theatre.of(market).suppressDaysPerDay() * weighted
				/ Math.max(1f, weighted + Math.max(0f, defence));
	}

	/** A structure's condition after one more day at this rate (the clock gains rate x condition x cover). */
	public static float conditionAfterDay(MarketAPI market, Industry ind, float rate, float through) {
		Theatre theatre = Theatre.of(market);
		float cond = theatre.condition(market, ind);
		return Math.max(0f, cond - rate * cond * through / theatre.wearDays());
	}

	/**
	 * The most a day of bombardment by fp still adds anywhere on the world: on
	 * the least-worn fortification under the shield's cover, or on the shield.
	 */
	public static float dailyGain(MarketAPI market, float fp) {
		if (market == null) return 0f;
		Theatre theatre = Theatre.of(market);
		float rate = suppressionRate(market, fp, MarketCMD.getDefenderStr(market, true));
		float through = ThreatShield.throughput(market);
		float best = 0f;
		for (Industry ind : theatre.fortifications(market)) {
			best = Math.max(best, rate * theatre.condition(market, ind) * through);
		}
		if (ThreatShield.present(market)) {
			best = Math.max(best, rate * ThreatShield.integrity(market)
					* Math.max(0f, ThreatIncConfig.shieldSoakMult()));
		}
		return best;
	}

	/**
	 * Whether orbit has done what it usefully can here for a fleet of fp: the
	 * commander would not fly another day ({@link #bombardPlan}) - it would
	 * gain less than the day of repair the defenders make on their own, or the
	 * guns would take more off the fleet than it takes off the world. The
	 * commander's stop, not a floor - a bigger fleet may still have work to
	 * do. Nothing to bombard: spent.
	 */
	public static boolean orbitSpent(MarketAPI market, float fp) {
		return orbitSpent(market, fp, 0f, false);
	}

	/**
	 * As above for a fleet carrying {@code troops} to land: while they could
	 * not hold (troopsToLand), the ships' worth is waived - without the
	 * troops, softening is the only way in - and only the gain and the abort
	 * line stop it (bombardPlan).
	 */
	public static boolean orbitSpent(MarketAPI market, float fp, float troops, boolean beachhead) {
		return orbitSpent(market, fp, troops, beachhead, ThreatIncConfig.bombardFPWorth());
	}

	/** As above, a day needing {@code worth} defence off the world per fleet point lost ({@link #swarmWorth}). */
	public static boolean orbitSpent(MarketAPI market, float fp, float troops, boolean beachhead, float worth) {
		if (market == null || !bombardable(market)) return true;
		return bombardPlan(market, fp, 1f, troops, beachhead, worth)[0] < 1f;
	}

	/**
	 * What a hull is worth in defence to a side that can break it up into
	 * troops over its own front ({@link #fabricateTroops}): fabricateTroopsPerFP
	 * troops, each fighting at {@code mult}, and a front holds on
	 * frontHoldFraction of the defence - so a defence point bombarded off is
	 * worth frontHoldFraction / mult troops. A day of bombardment that takes
	 * less than this off per fleet point lost does less for the front than the
	 * same hulls sent down. bombardFPWorth when fabrication is off.
	 */
	public static float hullWorth(float mult) {
		if (!ThreatIncConfig.fabricateEnabled()) return Math.max(0f, ThreatIncConfig.bombardFPWorth());
		float hold = Math.max(0.01f, ThreatIncConfig.frontHoldFraction());
		return Math.max(0f, ThreatIncConfig.fabricateTroopsPerFP()) * Math.max(0f, mult) / hold;
	}

	/**
	 * The swarm's stop (2026-09-28): it buys nothing with credits, so a hull is
	 * worth only what it becomes on the ground - {@link #hullWorth} at the
	 * landing's footing. It bombards while a day beats that and lands the
	 * rest as troops; a landing short of holding is made up by fabrication, so
	 * the shortHanded waiver never applies to it.
	 */
	public static float swarmWorth() {
		return hullWorth(ThreatIncConfig.frontLandingMult());
	}

	/** Orbit has nothing more to give a fleet of fp carrying this much fuel: spent, or the fuel will not buy half a day. */
	public static boolean orbitDone(MarketAPI market, float fp, float fuel) {
		return orbitDone(market, fp, fuel, 0f, false);
	}

	/** As above for a fleet carrying {@code troops} to land ({@link #orbitSpent(MarketAPI, float, float, boolean)}). */
	public static boolean orbitDone(MarketAPI market, float fp, float fuel, float troops, boolean beachhead) {
		return orbitSpent(market, fp, troops, beachhead) || bombardDaysFor(fuel, fp) < 0.5f;
	}

	/**
	 * Troops a landing needs to be ready against the defence figure {@code d}:
	 * {@link #readyToLand}'s hold and, for an NPC siege's first landing
	 * ({@code beachhead}), {@link #beachheadSurvives}, with the garrison read at
	 * d instead of {@code d0}, the figure now. Stockpiled marines do not wear
	 * under bombardment, so only the garrison term moves.
	 */
	public static float troopsToLand(MarketAPI market, float d, float d0, boolean beachhead) {
		if (market == null) return 0f;
		float mult = Math.max(0.01f, ThreatIncConfig.frontLandingMult());
		float moved = d - d0;
		float need = Math.max(0f, defenderStrength(market) + moved) * ThreatIncConfig.frontHoldFraction() / mult;
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (beachhead && margin > 0f) {
			float e = Math.max(0.1f, ThreatIncConfig.groundStrengthExponent());
			float odds = (float) Math.pow(2f, 1f / e);
			need = Math.max(need, Math.max(0f, counterAttackStrength(market) + moved) * margin / (mult * odds));
		}
		return need;
	}

	/** Vanilla's stability factor on the ground defence: 0.25 at 0, 1 at 10. */
	protected static float stabilityMult(float stability) {
		return 0.25f + Math.max(0f, Math.min(10f, stability)) * 0.075f;
	}

	/** Vanilla's FleetGroupIntel groupAbortsMissionFPFraction: an expedition cut below this share of what it set out with turns for home. */
	public static final float GROUP_ABORT_FRACTION = 0.33f;

	/**
	 * What a fleet of fp would make of the world by bombarding for as long as
	 * its commander would, no longer than siegeOrbitDays: {days it takes, the
	 * defence figure then as a fraction of now, the fleet points left, 1 when
	 * the troops it was given could land then}. What an expedition sizes its
	 * landing on.
	 */
	public static float[] bombardPlan(MarketAPI market, float fp) {
		return bombardPlan(market, fp, ThreatIncConfig.siegeOrbitDays());
	}

	/**
	 * As {@link #bombardPlan(MarketAPI, float)}, for at most {@code budget}
	 * days. A forward run of the same day the slice delivers - the shield
	 * wearing with the rest, the unrest it raises taking the stability down,
	 * the guns taking their fleet points off the fleet - with the garrison and
	 * any station left as they are. A day is flown only while it is worth its
	 * hulls (docs/suppression-balance.md v2, principle 1): it must add more
	 * than the day of repair the defenders make on their own, take at least
	 * bombardFPWorth defence off the world for every fleet point the guns take
	 * off the fleet - a hull costs that many marines, and a defence point is
	 * a marine the landing no longer needs - and leave the fleet above
	 * vanilla's abort line.
	 */
	public static float[] bombardPlan(MarketAPI market, float fp, float budget) {
		return bombardPlan(market, fp, budget, 0f, false);
	}

	/**
	 * As above for a fleet carrying {@code troops} to land (0: none weighed).
	 * SHORT OF TROOPS, SOFTEN FIRST (2026-09-28, run 4): while they could not
	 * hold (troopsToLand) a day is flown whatever it costs in hulls - the
	 * alternative is no landing at all - so only the gain and the abort line
	 * stop it. Troops that can hold leave the commander's usual stop in force.
	 */
	public static float[] bombardPlan(MarketAPI market, float fp, float budget, float troops,
			boolean beachhead) {
		return bombardPlan(market, fp, budget, troops, beachhead, ThreatIncConfig.bombardFPWorth());
	}

	/** As above, a day needing {@code worth} defence off per fleet point lost. */
	public static float[] bombardPlan(MarketAPI market, float fp, float budget, float troops,
			boolean beachhead, float worth) {
		float start = Math.max(0f, fp);
		if (market == null) return new float[] { 0f, 1f, start, 0f };
		float d0 = Math.max(1f, MarketCMD.getDefenderStr(market, true));
		float lands0 = troops > 0f && troops >= troopsToLand(market, d0, d0, beachhead) ? 1f : 0f;
		if (start <= 0f) return new float[] { 0f, 1f, start, lands0 };
		Theatre theatre = Theatre.of(market);
		List<Industry> forts = theatre.fortifications(market);
		boolean shield = ThreatShield.present(market);
		if (forts.isEmpty() && !shield) return new float[] { 0f, 1f, start, lands0 };
		int n = forts.size();
		float[] cond = new float[n];
		float[] next = new float[n];
		float[] bonus = new float[n];
		boolean[] gun = new boolean[n];
		float fortMult = 1f;
		for (int i = 0; i < n; i++) {
			Industry ind = forts.get(i);
			cond[i] = theatre.condition(market, ind);
			bonus[i] = fortificationBonus(market, ind);
			gun[i] = isBattery(market, ind);
			fortMult *= 1f + bonus[i] * cond[i];
		}
		PlanFigure fig = new PlanFigure(market, d0 / Math.max(0.01f, fortMult), bonus);
		float s = shield ? ThreatShield.integrity(market) : 0f;
		float absorbMax = Math.max(0f, Math.min(1f, ThreatIncConfig.shieldAbsorbMax()));
		float soak = Math.max(0f, ThreatIncConfig.shieldSoakMult());
		float wear = theatre.wearDays();
		float rate = theatre.suppressDaysPerDay();
		float perPoint = Math.max(0f, ThreatIncConfig.siegeFPWeight());
		worth = Math.max(0f, worth);
		float firePer = Math.max(0f, ThreatIncConfig.bombardReturnFirePerGunDefence());
		float floor = start * GROUP_ABORT_FRACTION;
		float fleet = start;
		float d = d0;
		int day = 0;
		while (day < budget) {
			float weighted = fleet * perPoint;
			float ratio = weighted / Math.max(1f, weighted + d);
			float through = shield ? 1f - absorbMax * s : 1f;
			float gain = 0f;
			for (int i = 0; i < n; i++) gain = Math.max(gain, rate * ratio * cond[i] * through);
			if (shield) gain = Math.max(gain, rate * ratio * s * soak);
			if (gain < 1f) break;
			for (int i = 0; i < n; i++) {
				next[i] = Math.max(0f, cond[i] - rate * ratio * cond[i] * through / wear);
			}
			// the guns answer with what they had when the day began; the day's
			// worth is what it takes off the figure, its unrest at full weight
			float fire = firePer * d * gunShare(cond, bonus, gun);
			float taken = fig.defence(cond, -1f, false) - fig.defence(next, -1f, false);
			boolean shortHanded = troops > 0f && troops < troopsToLand(market, d, d0, beachhead);
			if ((taken < fire * worth && !shortHanded) || fleet - fire < floor) break;
			System.arraycopy(next, 0, cond, 0, n);
			if (shield) s = Math.max(0f, s - rate * ratio * s * soak / wear);
			fleet -= fire;
			day++;
			// the figure the next day meets: the fortifications as they now
			// stand, and the stability the day's unrest leaves
			d = fig.defence(cond, -1f, true);
		}
		float lands = troops > 0f && troops >= troopsToLand(market, d, d0, beachhead) ? 1f : 0f;
		return new float[] { day, Math.min(1f, d / d0), fleet, lands };
	}

	/**
	 * What a fleet of fp carrying {@code fuel} would make of the world by
	 * saturating it until it is razed as far as saturation goes, the fuel is
	 * poured, the guns would take the fleet below vanilla's abort line, or
	 * siegeOrbitDays: {days, fuel spent, fleet points left, 1 when the razing
	 * is finished, the days the least-worn structure is then down for}. The
	 * same day saturationSlice delivers - every structure worn, the fuel
	 * through the shield into the bar, never less spent than a tactical day -
	 * and the unrest raised at once, so after the first day the guns fire with
	 * the figure a stability of what is left allows.
	 *
	 * <p>A hive has no bar (ThreatRazing.razes): its saturation is finished at
	 * the commander's stop ({@link #bombardPlan}'s first test) - when a day
	 * would add less than the day of repair the world makes on its own to
	 * every structure it falls on, the shield included - and the fuel it burns
	 * to get there is its price.
	 */
	public static float[] razePlan(MarketAPI market, float fp, float fuel) {
		return razePlan(market, fp, fuel, Math.max(0f, fp) * GROUP_ABORT_FRACTION);
	}

	/** As above, stopping before the fleet falls below {@code floorFP}: the abort line of an expedition that set out bigger than the fleet it has now. */
	public static float[] razePlan(MarketAPI market, float fp, float fuel, float floorFP) {
		float start = Math.max(0f, fp);
		if (market == null) return new float[] { 0f, 0f, start, 0f, 0f };
		boolean bar = ThreatRazing.razes(market);
		float need = ThreatRazing.fuelToDestroy(market);
		if (bar && need <= 0f) return new float[] { 0f, 0f, start, 1f, 0f };
		if (start <= 0f) return new float[] { 0f, 0f, 0f, 0f, 0f };
		Theatre theatre = Theatre.of(market);
		// the least-worn structure saturation falls on: it sets the stop over a
		// hive, and every structure is down at least as long as it is
		float top = 0f;
		for (Industry ind : saturationTargets(market)) top = Math.max(top, theatre.condition(market, ind));
		List<Industry> forts = theatre.fortifications(market);
		boolean shield = ThreatShield.present(market);
		float d0 = Math.max(1f, MarketCMD.getDefenderStr(market, true));
		int n = forts.size();
		float[] cond = new float[n];
		float[] bonus = new float[n];
		boolean[] gun = new boolean[n];
		float fortMult = 1f;
		for (int i = 0; i < n; i++) {
			Industry ind = forts.get(i);
			cond[i] = theatre.condition(market, ind);
			bonus[i] = fortificationBonus(market, ind);
			gun[i] = isBattery(market, ind);
			fortMult *= 1f + bonus[i] * cond[i];
		}
		PlanFigure fig = new PlanFigure(market, d0 / Math.max(0.01f, fortMult), bonus);
		float unrestMax = Math.max(0f, ThreatIncConfig.bombardUnrestMax());
		float s = shield ? ThreatShield.integrity(market) : 0f;
		float absorbMax = Math.max(0f, Math.min(1f, ThreatIncConfig.shieldAbsorbMax()));
		float soak = Math.max(0f, ThreatIncConfig.shieldSoakMult());
		float wear = theatre.wearDays();
		float rate = theatre.suppressDaysPerDay();
		float perPoint = Math.max(0f, ThreatIncConfig.siegeFPWeight());
		float firePer = Math.max(0f, ThreatIncConfig.bombardReturnFirePerGunDefence());
		float pourPer = Math.max(0f, ThreatIncConfig.satFuelPerFPDay());
		float budget = ThreatIncConfig.siegeOrbitDays();
		float floor = Math.max(0f, floorFP);
		float fleet = start;
		// Float.MAX_VALUE: as much as the stay burns (a hive's price, IncursionManager.razingFuel)
		float left = Math.max(0f, fuel);
		float spent = 0f;
		float reached = 0f;
		boolean stopped = false;
		float d = d0;
		int day = 0;
		while (day < budget && (!bar || reached < need - 0.5f) && left >= 1f) {
			float fire = firePer * d * gunShare(cond, bonus, gun);
			if (fleet - fire < floor) break;
			float through = shield ? 1f - absorbMax * s : 1f;
			float weighted = fleet * perPoint;
			float ratio = weighted / Math.max(1f, weighted + d);
			if (!bar && Math.max(rate * ratio * top * through, shield ? rate * ratio * s * soak : 0f) < 1f) {
				stopped = true;
				break;
			}
			float pour = Math.min(pourPer * fleet, left);
			if (bar) pour = Math.min(pour, (need - reached) / Math.max(0.01f, through));
			// the day that finishes it pours what it takes and no more
			boolean finishes = bar && reached + pour * through >= need - 0.5f;
			float burn = Math.min(left, finishes ? pour : Math.max(pour, bombardFuelPerDay(fleet)));
			left -= burn;
			spent += burn;
			reached += pour * through;
			for (int i = 0; i < n; i++) {
				cond[i] = Math.max(0f, cond[i] - rate * ratio * cond[i] * through / wear);
			}
			top = Math.max(0f, top - rate * ratio * top * through / wear);
			if (shield) s = Math.max(0f, s - rate * ratio * s * soak / wear);
			fleet -= fire;
			day++;
			d = fig.defence(cond, unrestMax, true);
		}
		// a hive's stop on the first day is a fleet too light to wear anything, not a saturation done
		boolean finished = bar ? reached >= need - 0.5f : stopped && day > 0;
		return new float[] { day, spent, fleet, finished ? 1f : 0f, wear * (1f - top) };
	}

	/**
	 * Whether saturation by fp has done what it usefully can over a hive (no
	 * bar, ThreatRazing.razes): a day would add less than the day of repair
	 * the world makes on its own to every structure it falls on and to the
	 * shield - {@link #razePlan}'s stop, read now. Nothing to fall on: done.
	 */
	public static boolean saturationSpent(MarketAPI market, float fp) {
		if (market == null || fp <= 0f) return true;
		Theatre theatre = Theatre.of(market);
		float rate = suppressionRate(market, fp, MarketCMD.getDefenderStr(market, true));
		float through = ThreatShield.throughput(market);
		for (Industry ind : saturationTargets(market)) {
			if (rate * theatre.condition(market, ind) * through >= 1f) return false;
		}
		return !ThreatShield.present(market)
				|| rate * ThreatShield.integrity(market) * Math.max(0f, ThreatIncConfig.shieldSoakMult()) < 1f;
	}

	/** The figure a plan reads for the fortifications' conditions: the base under them, times what stands, at the stability the bombardment's unrest leaves. */
	private static final class PlanFigure {
		final float base;
		final float[] bonus;
		final float stab0;
		final float stabMult0;
		final int unrest0;
		final float unrestMax;

		PlanFigure(MarketAPI market, float base, float[] bonus) {
			this.base = base;
			this.bonus = bonus;
			stab0 = market.getStabilityValue();
			stabMult0 = Math.max(0.01f, stabilityMult(stab0));
			unrest0 = RecentUnrest.getPenalty(market);
			unrestMax = Math.max(0f, ThreatIncConfig.bombardUnrestMax());
		}

		/**
		 * The figure at these conditions. The unrest is what the tactical rule
		 * raises (bombardUnrestMax x what is suppressed) or, when {@code raised}
		 * is not negative, that much outright (saturation); in whole points as
		 * vanilla keeps them, or unrounded ({@code whole} false) for a day's
		 * marginal worth.
		 */
		float defence(float[] cond, float raised, boolean whole) {
			float mult = 1f;
			float mean = 0f;
			for (int i = 0; i < cond.length; i++) {
				mult *= 1f + bonus[i] * cond[i];
				mean += cond[i];
			}
			mean = cond.length > 0 ? mean / cond.length : 1f;
			float target = raised >= 0f ? raised : unrestMax * (1f - mean);
			if (whole) target = Math.round(target);
			float unrest = Math.max(unrest0, target);
			float stab = Math.max(0f, Math.min(10f, stab0 - (unrest - unrest0)));
			return base * mult * stabilityMult(stab) / stabMult0;
		}
	}

	/** The guns' share of the figure at these conditions, D x (1 - 1 / their multiplier) as the theatre's batteryShare reads it. */
	private static float gunShare(float[] cond, float[] bonus, boolean[] gun) {
		float mult = 1f;
		for (int i = 0; i < cond.length; i++) {
			if (gun[i]) mult *= 1f + bonus[i] * cond[i];
		}
		return mult <= 1f ? 0f : 1f - 1f / mult;
	}

	/** Whether a fortification is one of the guns - Ground Defenses or Heavy Batteries, either theatre's - whose share sets the return fire. */
	protected static boolean isBattery(MarketAPI market, Industry ind) {
		if (ind == null) return false;
		if (Theatre.of(market) == HIVE) {
			return ThreatColonyManager.THREAT_GROUND_DEFENSES.equals(ind.getId())
					|| ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(ind.getId());
		}
		return ThreatSiegeMalus.isBattery(ind.getId());
	}

	/**
	 * Whether a day of the guns' answer here would take a group now at
	 * {@code groupFP} below {@code abortFraction} of the {@code spawnedFP} it
	 * set out with - the day an expedition would turn for home. Its commander
	 * lands on what orbit has left instead.
	 */
	public static boolean gunsWouldBreak(MarketAPI market, float groupFP, float spawnedFP, float abortFraction) {
		if (market == null || spawnedFP <= 0f) return false;
		float fire = returnFirePerDay(market, Math.max(0f, MarketCMD.getDefenderStr(market, true)));
		return fire > 0f && groupFP - fire < spawnedFP * abortFraction;
	}

	/**
	 * Raises the world's unrest to {@code target}, never stacking on what is
	 * there already: a bombardment holds the colony at what it has broken,
	 * not ten more points a day. A human world under bombardment does not
	 * collapse on its own either - it falls to a siege or to saturation - so
	 * vanilla's zero-stability decivilisation is held off while it lasts
	 * (never touching the key when something else set it).
	 */
	public static void raiseUnrest(MarketAPI market, int target, String reason) {
		if (market == null || target <= 0) return;
		int now = RecentUnrest.getPenalty(market);
		Theatre theatre = Theatre.of(market);
		if (target > now) {
			RecentUnrest.get(market).add(target - now, reason);
			// the stat now, not on the next economy pass: the defence figure reads it
			market.reapplyConditions();
			if (theatre == HIVE) ThreatColonyManager.applyHiveOrder(market);
		}
		if (theatre == HIVE) return; // a hive carries the key for life
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (!mem.getBoolean(DECIV_HELD_KEY) && mem.contains(DecivTracker.NO_DECIV_KEY)) return;
		mem.set(DecivTracker.NO_DECIV_KEY, true, theatre.wearDays());
		mem.set(DECIV_HELD_KEY, true, theatre.wearDays());
	}

	/** The unrest a tactical bombardment raises the world to: bombardUnrestMax x what it has suppressed. */
	public static int tacticalUnrest(MarketAPI market) {
		return Math.round(Math.max(0f, ThreatIncConfig.bombardUnrestMax())
				* (1f - fortificationCondition(market)));
	}

	/** A world the siege rule describes counts as besieged for as long as its clock can run. */
	protected static void markBesieged(MarketAPI market) {
		Theatre theatre = Theatre.of(market);
		if (theatre.carriesSiegeState()) {
			market.getMemoryWithoutUpdate().set(BESIEGED_FLAG, true, theatre.wearDays());
		}
	}

	/**
	 * The structures' side of {@code days} of bombardment: each target gains
	 * {@code rate} x its condition x the shield's throughput a day, a day at a
	 * time so every pass lands on what the last one left, and the shield takes
	 * its own share at full weight. The clocks' run-down since the last slice
	 * ({@code madeUp}) is made up first, on a clock already running. The cap
	 * is the theatre's wear days, which the x condition only approaches.
	 * Returns whether anything was written.
	 */
	protected static boolean bombardStructures(MarketAPI market, List<Industry> targets, float rate,
			float days, float madeUp) {
		Theatre theatre = Theatre.of(market);
		float cap = siegeWornDays(market);
		boolean touched = false;
		if (madeUp > 0f) {
			for (Industry ind : targets) {
				float cur = siegeDisruptDays(ind);
				if (cur <= 0f || cur >= cap) continue;
				ind.setDisrupted(Math.min(cap, cur + madeUp));
				touched = true;
			}
		}
		if (rate <= 0f) return touched;
		float restore = madeUp;
		for (float left = days; left > 0.001f; left -= 1f) {
			float step = Math.min(1f, left);
			// re-read every day: the shield wears with everything under it
			float through = ThreatShield.throughput(market);
			for (Industry ind : targets) {
				float cur = siegeDisruptDays(ind);
				if (cur >= cap) continue;
				float add = rate * theatre.condition(market, ind) * through * step;
				if (add <= 0f) continue;
				ind.setDisrupted(Math.min(cap, cur + add));
				touched = true;
			}
			if (ThreatShield.soak(market, rate * step, restore, cap)) touched = true;
			restore = 0f;
		}
		return touched;
	}

	/** After a slice: the siege state and the stat recomputed, and vanilla's burst on the planet once per whole day of bombardment. */
	protected static void finishSlice(MarketAPI market, boolean touched, float days) {
		boolean synced = syncSiegeState(market);
		if (touched || synced) market.reapplyIndustries();
		if (!touched || market.getPrimaryEntity() == null) return;
		float banked = market.getMemoryWithoutUpdate().getFloat(SIEGE_VISUAL_KEY) + days;
		if (banked >= 1f) {
			banked = 0f;
			MarketCMD.addBombardVisual(market.getPrimaryEntity());
		}
		market.getMemoryWithoutUpdate().set(SIEGE_VISUAL_KEY, banked, 10f);
	}

	/**
	 * {@code days} of tactical bombardment by {@code fp} fleet points unopposed
	 * over a world (docs/suppression-balance.md v2): each fortification's clock
	 * gains the theatre's rate x fleet / (fleet + defence) x its condition a
	 * day, the shield soaks its share, and the world's unrest is raised to
	 * bombardUnrestMax x what is suppressed. The guns answer with
	 * bombardReturnFirePerGunDefence x the defence they add, a day - whatever
	 * the fleet's size. The defence figure is the bombard-facing one (no cargo
	 * marines, no banked reserve): orbit fights the fixed defences, the hold
	 * test on landing ({@link #readyToLand}) counts the people too. A live
	 * fleet's slice makes up the days the clock ran down since the last slice
	 * on the world ({@code elapsed}), so a fleet that stays holds its ground.
	 * Returns the fleet points the guns take; the caller takes them off a
	 * live fleet ({@link #applyFleetLosses}) or off its abstract strength, and
	 * pays the day's fuel ({@link #bombardFuelPerDay}) from what it carries.
	 */
	public static float siegeSlice(float fp, MarketAPI market, float days) {
		return siegeSlice(fp, market, days, true, true);
	}

	public static float siegeSlice(float fp, MarketAPI market, float days, boolean elapsed,
			boolean reapply) {
		return siegeSlice(fp, market, days, elapsed, reapply, -1f, "Orbital bombardment");
	}

	/**
	 * As above, with the defence figure supplied (negative: read live) and the
	 * unrest's reason named - the player's bombardment names the player's
	 * faction, as vanilla's does.
	 */
	public static float siegeSlice(float fp, MarketAPI market, float days, boolean elapsed,
			boolean reapply, float defenceOverride, String reason) {
		return siegeSlice(fp, fp, market, days, elapsed, reapply, defenceOverride, reason);
	}

	/**
	 * As above for one of the fleets bombarding the world together,
	 * {@code orbitFP} the points of them all ({@link #orbitPoints}): one day
	 * over one world. The structures wear at the rate the combined points earn
	 * and the guns answer once; each fleet delivers, and takes, its share by
	 * points - splitting a fleet neither wears faster nor pays the guns twice.
	 */
	public static float siegeSlice(float fp, float orbitFP, MarketAPI market, float days, boolean elapsed,
			boolean reapply, float defenceOverride, String reason) {
		if (market == null || days <= 0f || fp <= 0f) return 0f;
		Theatre theatre = Theatre.of(market);
		float defence = defenceOverride >= 0f ? defenceOverride
				: Math.max(0f, MarketCMD.getDefenderStr(market, true));
		float orbit = Math.max(fp, orbitFP);
		float share = fp / orbit;
		// the guns answer with what they had when the day began
		float loss = returnFirePerDay(market, defence) * days * share;
		float rate = suppressionRate(market, orbit, defence) * share;
		markBesieged(market);
		// what the clocks ran down since the last slice by ANY fleet, made up
		// first: each fleet counting its own gap gave N fleets over one world
		// N days back per day, and splitting a fleet bought free suppression
		float madeUp = elapsed ? madeUpDays(market, days) : 0f;
		boolean touched = bombardStructures(market, theatre.fortifications(market), rate, days, madeUp);
		raiseUnrest(market, tacticalUnrest(market), reason);
		ThreatIncConfig.log("siegeSlice " + market.getName() + " [" + (theatre == HIVE ? "hive" : "colony")
				+ "] fp=" + String.format("%.0f", fp) + " defence=" + String.format("%.0f", defence)
				+ " rate=" + String.format("%.2f", rate) + " days=" + String.format("%.2f", days)
				+ " condition=" + String.format("%.2f", fortificationCondition(market))
				+ " loss=" + String.format("%.2f", loss));
		if (reapply) finishSlice(market, touched, days);
		return loss;
	}

	/** What saturation falls on: every building vanilla does not spare, bar the shield (it soaks on its own). */
	public static List<Industry> saturationTargets(MarketAPI market) {
		List<Industry> list = new ArrayList<Industry>();
		if (market == null) return list;
		Industry shield = ThreatShield.present(market) ? ThreatShield.get(market) : null;
		for (Industry ind : market.getIndustries()) {
			if (ind == null || ind.getSpec() == null || ind == shield) continue;
			if (ind.getSpec().hasTag(Industries.TAG_NO_SATURATION_BOMBARDMENT)) continue;
			if (!ind.canBeDisrupted()) continue;
			if (ind.isBuilding() && !ind.isUpgrading()) continue;
			list.add(ind);
		}
		return list;
	}

	/**
	 * {@code days} of saturation bombardment by {@code fp} with up to
	 * {@code fuel} to pour (docs/suppression-balance.md v2 section 4): the
	 * tactical rule on every building, the guns' answer, the world's unrest
	 * raised to bombardUnrestMax, its growth paused, and the fuel poured into
	 * the razing bar ({@link ThreatRazing}) less what the shield turns aside.
	 * Returns {fleet points the guns take, fuel spent, levels razed, 1 when the
	 * colony is gone}. Once it is gone nothing else on it is touched. A hive
	 * has no bar (ThreatRazing.razes): the fuel is burnt at the rate for the
	 * day's wear alone, and the per-day rule is all saturation does to it.
	 */
	public static float[] saturationSlice(float fp, MarketAPI market, float days, float fuel,
			boolean elapsed, boolean reapply, float defenceOverride, String razerFactionId, String reason) {
		return saturationSlice(fp, fp, market, days, fuel, elapsed, reapply, defenceOverride, razerFactionId,
				reason);
	}

	/** As above for one of the fleets saturating the world together ({@link #siegeSlice(float, float, MarketAPI, float, boolean, boolean, float, String)}): each pours its own fuel and takes its share of the guns' answer. */
	public static float[] saturationSlice(float fp, float orbitFP, MarketAPI market, float days, float fuel,
			boolean elapsed, boolean reapply, float defenceOverride, String razerFactionId, String reason) {
		float[] out = new float[4];
		if (market == null || days <= 0f || fp <= 0f) return out;
		float defence = defenceOverride >= 0f ? defenceOverride
				: Math.max(0f, MarketCMD.getDefenderStr(market, true));
		float orbit = Math.max(fp, orbitFP);
		float share = fp / orbit;
		out[0] = returnFirePerDay(market, defence) * days * share;
		float rate = suppressionRate(market, orbit, defence) * share;
		// the shield turns its share aside for the whole day's pour
		float through = ThreatShield.throughput(market);
		float pour = ThreatRazing.deliverable(market, fp, days, fuel);
		// never less than the tactical day it also flies - bar the day that
		// finishes the razing, which pours what it takes and no more
		boolean finishes = ThreatRazing.razes(market)
				&& pour * through >= ThreatRazing.fuelToDestroy(market) - 0.5f;
		out[1] = finishes ? pour : Math.max(pour, Math.min(Math.max(0f, fuel), bombardFuelPerDay(fp) * days));
		markBesieged(market);
		ThreatRazing.markSaturated(market, days);
		float madeUp = elapsed ? madeUpDays(market, days) : 0f;
		boolean touched = bombardStructures(market, saturationTargets(market), rate, days, madeUp);
		raiseUnrest(market, Math.round(Math.max(0f, ThreatIncConfig.bombardUnrestMax())), reason);
		int[] razed = ThreatRazing.pour(market, pour * through, razerFactionId);
		out[2] = razed[0];
		out[3] = razed[1];
		ThreatIncConfig.log("saturationSlice " + market.getName() + " fp=" + String.format("%.0f", fp)
				+ " days=" + String.format("%.2f", days) + " poured=" + String.format("%.0f", pour)
				+ " through=" + String.format("%.2f", through) + " razed=" + razed[0]
				+ (razed[1] > 0 ? " DESTROYED" : "") + " loss=" + String.format("%.2f", out[0]));
		if (razed[1] > 0) return out;
		if (reapply) finishSlice(market, touched, days);
		return out;
	}

	/** What a day of bombardment would do here, for a prompt or a tooltip; nothing is written. */
	public static class BombardDay {
		/** The defence figure the day meets. */
		public float defence;
		/** Disruption days the day adds to a structure still whole. */
		public float rate;
		/** The shield's throughput: the share of the day that reaches what is under it. */
		public float through = 1f;
		/** Fleet points the guns take. */
		public float returnFire;
		/** Fuel a day of tactical bombardment burns. */
		public float fuel;
		/** The fortifications' mean condition now and after the day. */
		public float conditionNow = 1f;
		public float conditionAfter = 1f;
		/** The shield's integrity now and after the day; -1 with no shield. */
		public float shieldNow = -1f;
		public float shieldAfter = -1f;
		/** The unrest the day raises the world to; 0 when it raises nothing. */
		public int unrest;
	}

	/** The day a fleet of fp would fly here now, tactical or saturation ({@link #siegeSlice}, {@link #saturationSlice}). */
	public static BombardDay bombardDay(float fp, MarketAPI market, boolean saturation) {
		BombardDay day = new BombardDay();
		if (market == null) return day;
		Theatre theatre = Theatre.of(market);
		day.defence = Math.max(0f, MarketCMD.getDefenderStr(market, true));
		day.rate = suppressionRate(market, fp, day.defence);
		day.through = ThreatShield.throughput(market);
		day.returnFire = returnFirePerDay(market, day.defence);
		day.fuel = bombardFuelPerDay(fp);
		List<Industry> forts = theatre.fortifications(market);
		if (!forts.isEmpty()) {
			float now = 0f;
			float after = 0f;
			for (Industry ind : forts) {
				now += theatre.condition(market, ind);
				after += conditionAfterDay(market, ind, day.rate, day.through);
			}
			day.conditionNow = now / forts.size();
			day.conditionAfter = after / forts.size();
		}
		if (ThreatShield.present(market)) {
			day.shieldNow = ThreatShield.integrity(market);
			day.shieldAfter = Math.max(0f, day.shieldNow - day.rate * day.shieldNow
					* Math.max(0f, ThreatIncConfig.shieldSoakMult()) / theatre.wearDays());
		}
		float unrestMax = Math.max(0f, ThreatIncConfig.bombardUnrestMax());
		int unrest = saturation ? Math.round(unrestMax) : Math.round(unrestMax * (1f - day.conditionAfter));
		day.unrest = unrest > RecentUnrest.getPenalty(market) ? unrest : 0;
		return day;
	}

	/**
	 * The run-down a slice of {@code days} may make up on this world: the days
	 * since any slice last made it up, never more than the slice's own.
	 */
	private static float madeUpDays(MarketAPI market, float days) {
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		float since = days;
		if (mem.contains(SIEGE_MADE_UP_KEY)) {
			since = Global.getSector().getClock().getElapsedDaysSince(mem.getLong(SIEGE_MADE_UP_KEY));
		}
		mem.set(SIEGE_MADE_UP_KEY, Global.getSector().getClock().getTimestamp(), SIEGE_MAX_SLICE_DAYS);
		return Math.max(0f, Math.min(days, since));
	}

	/** Days since this fleet's last slice, clamped; a first slice stands for vanilla's gap between passes. */
	public static float siegeSliceDays(CampaignFleetAPI fleet) {
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		long now = Global.getSector().getClock().getTimestamp();
		float days = SIEGE_FIRST_SLICE_DAYS;
		if (mem.contains(SIEGE_LAST_KEY)) {
			days = Global.getSector().getClock().getElapsedDaysSince(mem.getLong(SIEGE_LAST_KEY));
		}
		mem.set(SIEGE_LAST_KEY, now);
		return Math.max(0.5f, Math.min(SIEGE_MAX_SLICE_DAYS, days));
	}

	/**
	 * What a fleet's bombardment costs in fuel, paid. The swarm carries none:
	 * it pays from the hive's fuel stock at the same rate (ThreatFuel,
	 * 2026-10-01 - it poured without limit), and what the stock cannot pay is
	 * demand it did not meet (ThreatFuel.unmet). Anyone else pays from its own
	 * provisions ({@link ThreatReturns#MEM_FUEL}) first and then from the
	 * spendable reserve of its supply line ({@link #ordnanceSources}).
	 * Returns the fuel actually paid, which may be short.
	 */
	public static float payOrdnance(CampaignFleetAPI fleet, String factionId, float fuel) {
		if (fuel <= 0f) return 0f;
		if (Factions.THREAT.equals(factionId)) {
			if (!ThreatFuel.paysOrdnance()) return fuel;
			float paid = Math.min(fuel, ThreatFuel.stock());
			ThreatFuel.pay(paid);
			ThreatFuel.unmet(Commodities.FUEL, fuel - paid, "bombardment");
			return paid;
		}
		if (fleet == null) return 0f;
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float carried = Math.max(0f, mem.getFloat(ThreatReturns.MEM_FUEL));
		float paid = Math.min(carried, fuel);
		if (paid > 0f) mem.set(ThreatReturns.MEM_FUEL, carried - paid);
		for (MarketAPI m : ordnanceSources(fleet, factionId)) {
			if (paid >= fuel) break;
			// fuel from another system pays its passage to the fleet (2026-10-01)
			float rate = ThreatConvoys.haulRate(m, fleet.getLocationInHyperspace(), Commodities.FUEL);
			float can = Math.min(fuel - paid, ThreatConvoys.netOfHaul(Commodities.FUEL,
					ThreatReserves.spendable(m, Commodities.FUEL), 0f, rate));
			if (can <= 0f) continue;
			ThreatConvoys.payHaul(m, can, rate);
			paid += ThreatReserves.drawSpendable(m, Commodities.FUEL, can);
		}
		return paid;
	}

	/** The fuel a fleet could put into its bombardment now, without paying it ({@link #payOrdnance}): the swarm's, the hive's stock. */
	public static float ordnanceAvailable(CampaignFleetAPI fleet, String factionId) {
		if (Factions.THREAT.equals(factionId)) {
			if (!ThreatFuel.paysOrdnance()) return Float.MAX_VALUE;
			// with the stock empty a swarm fleet stands down (orbitDoneFor), and
			// its day is demand the stock did not meet (once a day)
			if (fleet != null) ThreatFuel.groundedOrdnance(fleet, bombardFuelPerDay(fleet.getFleetPoints()));
			return ThreatFuel.stock();
		}
		if (fleet == null) return 0f;
		float fuel = Math.max(0f, fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_FUEL));
		for (MarketAPI m : ordnanceSources(fleet, factionId)) {
			fuel += ThreatConvoys.netOfHaul(Commodities.FUEL, ThreatReserves.spendable(m, Commodities.FUEL), 0f,
					ThreatConvoys.haulRate(m, fleet.getLocationInHyperspace(), Commodities.FUEL));
		}
		return fuel;
	}

	/**
	 * The markets a fleet's bombardment draws on past its own provisions - its
	 * supply line. At its home base's system, the home base, as ever. Away
	 * from it (2026-09-29), only markets whose stock reaches where the fleet
	 * stands (IncursionManager.marketsReaching, ThreatConvoys.stockReachLY):
	 * the home base first if it does, then the faction's others nearest first.
	 * Until then the home base paid at any range - fuel that never sailed; an
	 * NPC's now pays its passage to the fleet (ThreatConvoys.haulRate,
	 * 2026-10-01). The player's fleets keep their home base alone (its reach
	 * is any range).
	 */
	protected static List<MarketAPI> ordnanceSources(CampaignFleetAPI fleet, String factionId) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		String home = ThreatReturns.homeOf(fleet);
		MarketAPI base = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
		if (base != null && base.getStarSystem() != null && fleet.getStarSystem() == base.getStarSystem()) {
			out.add(base);
			return out;
		}
		com.fs.starfarer.api.campaign.FactionAPI faction = factionId != null
				? Global.getSector().getFaction(factionId) : null;
		if (faction == null) return out;
		List<MarketAPI> reaching = IncursionManager.marketsReaching(faction, fleet.getLocationInHyperspace());
		if (base != null && reaching.contains(base)) out.add(base);
		if (faction.isPlayerFaction()) return out;
		for (MarketAPI m : reaching) {
			if (m != base) out.add(m);
		}
		return out;
	}

	/**
	 * An expedition that never spawned (vanilla autoresolve) runs its whole
	 * siege of a world in one go: the same slices against the same batteries,
	 * {@code start} abstract fleet points standing in for the fleets, up to
	 * siegeOrbitDays or the group's abort fraction, or until {@code troops}
	 * could land. The disruption it writes is real. Returns the points left.
	 */
	public static float abstractSiege(MarketAPI market, float start, float troops,
			float abortFraction) {
		return abstractSiege(market, start, troops, abortFraction, null);
	}

	/** As above for the faction whose siege it is ({@link #readyToLand(MarketAPI, float, String, boolean)}). */
	public static float abstractSiege(MarketAPI market, float start, float troops,
			float abortFraction, String factionId) {
		return abstractSiege(market, start, troops, abortFraction, factionId, Float.MAX_VALUE)[0];
	}

	/**
	 * As above, paying each day's ordnance out of {@code fuel}: the siege stops
	 * bombarding when the fuel will not buy another half day, and the troops
	 * land on what orbit left. Returns {the points left, the fuel left, the
	 * days it bombarded, 1 if it ran dry with the troops not ready to land}.
	 */
	public static float[] abstractSiege(MarketAPI market, float start, float troops,
			float abortFraction, String factionId, float fuel) {
		if (market == null || start <= 0f) return new float[] { start, fuel, 0f, 0f };
		float fp = start;
		float elapsed = 0f;
		float step = SIEGE_FIRST_SLICE_DAYS;
		float budget = ThreatIncConfig.siegeOrbitDays();
		boolean dry = false;
		while (fp > 0f && elapsed < budget && fp > start * abortFraction) {
			if (readyToLand(market, troops, factionId, orbitDone(market, fp, fuel, troops,
					factionId != null && !Factions.PLAYER.equals(factionId)))) break;
			float days = Math.min(step, bombardDaysFor(fuel, fp));
			if (days <= 0f) {
				dry = true;
				break;
			}
			// the guns would break the siege within the step: its commander lands
			// on what orbit has left instead of turning for home
			float fire = returnFirePerDay(market, Math.max(0f, MarketCMD.getDefenderStr(market, true))) * days;
			if (fire > 0f && fp - fire < start * abortFraction) break;
			// one frame: nothing runs down between steps. The stats are
			// recomputed after every step - read once, the defence stayed at its
			// intact figure all siege (Eventide, 1221 for 18 steps from 0.93 to
			// 0.33), overpaying the guns and wearing the world too slowly
			float cost = bombardFuelPerDay(fp) * days;
			fp -= siegeSlice(fp, market, days, false, false);
			if (fuel < Float.MAX_VALUE) fuel = Math.max(0f, fuel - cost);
			elapsed += days;
			syncSiegeState(market);
			market.reapplyIndustries();
		}
		syncSiegeState(market);
		market.reapplyIndustries();
		ThreatIncConfig.log("Abstract siege of " + market.getName() + ": " + (int) elapsed
				+ " d, " + (int) start + " -> " + (int) fp + " FP"
				+ (fuel < Float.MAX_VALUE ? ", " + (int) fuel + " fuel left" : "") + (dry ? ", ran dry" : ""));
		return new float[] { fp, fuel, elapsed, dry ? 1f : 0f };
	}

	/**
	 * Takes {@code fp} fleet points off a live fleet as ships lost to the
	 * batteries, smallest first, banking the remainder in the fleet's memory
	 * until it buys a hull. The flagship and the last ship are spared - the
	 * expedition's own abort rule takes a gutted group home. Returns the
	 * points removed; {@code lost}, when given, collects the ships.
	 */
	public static float applyFleetLosses(CampaignFleetAPI fleet, float fp) {
		return applyFleetLosses(fleet, fp, null);
	}

	public static float applyFleetLosses(CampaignFleetAPI fleet, float fp, List<FleetMemberAPI> lost) {
		return applyFleetLosses(fleet, fp, lost, SIEGE_DAMAGE_KEY);
	}

	/**
	 * As above, against a named bank. Fabrication keeps its own
	 * ({@link #FABRICATE_BANK_KEY}) so a hull the batteries had all but paid
	 * for is never handed to the ground as troops, and the two tolls stay
	 * legible in the log.
	 */
	public static float applyFleetLosses(CampaignFleetAPI fleet, float fp, List<FleetMemberAPI> lost,
			String bankKey) {
		if (fleet == null || fp <= 0f || !fleet.isAlive()) return 0f;
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float bank = mem.getFloat(bankKey) + fp;
		List<FleetMemberAPI> members = smallestFirst(fleet);
		float removed = 0f;
		for (FleetMemberAPI member : members) {
			if (fleet.getFleetData().getNumMembers() <= 1) break;
			if (member == fleet.getFlagship()) continue;
			float cost = Math.max(1f, member.getFleetPointCost());
			if (cost > bank) break;
			fleet.getFleetData().removeFleetMember(member);
			if (lost != null) lost.add(member);
			bank -= cost;
			removed += cost;
		}
		mem.set(bankKey, bank);
		return removed;
	}

	/** The fleet's ships in the order the guns take them: smallest first. */
	protected static List<FleetMemberAPI> smallestFirst(CampaignFleetAPI fleet) {
		List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
		Collections.sort(members, new Comparator<FleetMemberAPI>() {
			public int compare(FleetMemberAPI a, FleetMemberAPI b) {
				return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
			}
		});
		return members;
	}

	/** What {@code fp} of return fire would take from a fleet: {@link #applyFleetLosses} without the writes. */
	public static class LossPreview {
		/** The ships lost, smallest first. */
		public final List<FleetMemberAPI> lost = new ArrayList<FleetMemberAPI>();
		/** The next ship in line, and the damage banked toward it; null when none can go. */
		public FleetMemberAPI next;
		public float banked;
	}

	public static LossPreview previewFleetLosses(CampaignFleetAPI fleet, float fp) {
		LossPreview p = new LossPreview();
		if (fleet == null) return p;
		float bank = fleet.getMemoryWithoutUpdate().getFloat(SIEGE_DAMAGE_KEY) + Math.max(0f, fp);
		int left = fleet.getFleetData().getNumMembers();
		for (FleetMemberAPI member : smallestFirst(fleet)) {
			if (left <= 1) break;
			if (member == fleet.getFlagship()) continue;
			float cost = Math.max(1f, member.getFleetPointCost());
			if (cost > bank) {
				p.next = member;
				break;
			}
			p.lost.add(member);
			bank -= cost;
			left--;
		}
		p.banked = bank;
		return p;
	}

	/** A ship as the dialogs name it: "Hound (Hound-class)". */
	public static String shipName(FleetMemberAPI member) {
		return member.getShipName() + " (" + member.getHullSpec().getHullNameWithDashClass() + ")";
	}

	/**
	 * The day's return fire as the prompts quote it, one fact a line: the
	 * ships it takes by name, then the next in line with the damage banked
	 * toward it. Empty when the guns take nothing.
	 */
	public static List<String> lossLines(CampaignFleetAPI fleet, float fp) {
		List<String> lines = new ArrayList<String>();
		if (fleet == null || fp < 0.05f) return lines;
		LossPreview p = previewFleetLosses(fleet, fp);
		if (!p.lost.isEmpty()) {
			StringBuilder names = new StringBuilder();
			for (FleetMemberAPI member : p.lost) {
				if (names.length() > 0) names.append(", ");
				names.append(shipName(member));
			}
			lines.add("Lost to return fire: " + names + ".");
		}
		if (p.next != null) {
			lines.add("Next to go: " + shipName(p.next) + ", " + String.format("%.1f", p.banked)
					+ " of " + (int) Math.max(1f, p.next.getFleetPointCost()) + " fleet points of damage.");
		}
		return lines;
	}

	// ------------------------------------------------------------------
	// FABRICATING TROOPS FROM THE FLEET (2026-09-08, the user): a Defend
	// station whose front cannot hold bombards - until the bombardment has
	// nothing left to reach. Worn out, the guns are as quiet as orbit can
	// make them and the front is still losing, so the fleet stops firing and
	// starts feeding itself into the ground instead: hulls broken up and sent
	// down as soldiers. It commits only what the front is short, it pays for
	// the drop what fighting UNDISRUPTED batteries would cost, and it never
	// takes the fleet home - a committed fleet keeps giving until the front
	// stands or falls.
	// ------------------------------------------------------------------

	/** Fleet memory: fabrication losses banked below the cost of the smallest hull, kept apart from battery damage. */
	public static final String FABRICATE_BANK_KEY = "$threatinc_fabricateBank";
	/** Fleet memory: this fleet has fed the ground here - it does not stand down on strength while that front stands. */
	public static final String FABRICATE_FLAG_KEY = "$threatinc_fabricated";

	/**
	 * WHAT THE DROP COSTS, on top of the hulls that become troops: exactly the
	 * return fire {@link #siegeSlice} would take if the world's batteries were
	 * UNDISRUPTED (the user's price for it, 2026-09-08). The duel's own toll
	 * formula, read at full condition rather than the suppressed one - the
	 * fragments go down through defended air, so the guns get the shot they
	 * would have had on the first day of the siege even though they are ground
	 * to nothing. It is a PRICE, never the size of the commitment: a world
	 * with no batteries charges nothing and its front is fed for free, which is
	 * right and is why the two figures are not the same number.
	 */
	public static float fabricateCost(float fp, MarketAPI market, float days) {
		if (market == null || fp <= 0f || days <= 0f) return 0f;
		float defence = Math.max(0f, MarketCMD.getDefenderStr(market, true));
		return Math.max(0f, ThreatIncConfig.bombardReturnFirePerGunDefence()) * days
				* defence * Theatre.of(market).intactBatteryShare(market);
	}

	/**
	 * Whether a DEFEND fleet of the faction fabricates troops now - the exact
	 * complement of {@link #defendBombards}: its own front on the ground
	 * cannot hold, and orbit has run out of things to suppress. Both are false
	 * while the front holds, so the moment the ground is safe the fleet stops
	 * cutting itself up (the user, 2026-09-08).
	 */
	public static boolean defendFabricates(String factionId, MarketAPI market, CampaignFleetAPI fleet) {
		if (!ThreatIncConfig.fabricateEnabled()) return false;
		// the swarm's alone: bioships go down as troops, a navy's hulls do not
		if (!Factions.THREAT.equals(factionId) || market == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return false;
		if (frontCanHold(front, market)) return false;
		return orbitDoneFor(market, fleet, factionId);
	}

	/**
	 * What a DEFEND fleet's day must take off per fleet point lost. A faction's:
	 * bombardFPWorth, what the hull cost it. The swarm's: what the hull would
	 * become ({@link #hullWorth}) - at its front's footing over its own front
	 * that cannot hold, where it can go down as troops, else at the landing's.
	 */
	public static float defendWorth(String factionId, MarketAPI market) {
		if (!Factions.THREAT.equals(factionId)) return Math.max(0f, ThreatIncConfig.bombardFPWorth());
		GroundFront front = market != null ? getFront(market.getId()) : null;
		if (front == null || !factionId.equals(ownerOf(front)) || frontCanHold(front, market)) return swarmWorth();
		return hullWorth(entrenchMult(front));
	}

	/** Orbit has nothing more to give this fleet here: spent for the points its faction bombards with ({@link #orbitPoints}), or no ordnance left for its own share. */
	public static boolean orbitDoneFor(MarketAPI market, CampaignFleetAPI fleet, String factionId) {
		if (fleet == null) return orbitDone(market, 0f, 0f);
		float fp = fleet.getFleetPoints();
		return orbitSpent(market, orbitPoints(factionId, market, fp), 0f, false, defendWorth(factionId, market))
				|| bombardDaysFor(ordnanceAvailable(fleet, factionId), fp) < 0.5f;
	}

	/**
	 * The troops that would put this front back over the hold line, with
	 * {@code fabricateHoldMargin} of daylight so it does not sit on the
	 * boundary and oscillate.
	 *
	 * <p>Measured against what the front will be worth AFTER the drop, not what
	 * it is worth now: the fragments land with their own armaments, so a front
	 * that is only weak because it ran dry will not still be fighting at
	 * {@code frontDryEffectivenessMult} when these troops are counted. Reading
	 * {@link #effectiveStrength} here instead would price the dry penalty into
	 * the gap and then cancel it by landing, over-feeding the front by half its
	 * own weight - the opposite of committing proportionally.
	 *
	 * <p>The tail of that: a dry front with plenty of soldiers computes no gap
	 * at all, yet still cannot hold. It is short of guns, not men, and a drop is
	 * the only way this fleet can deliver any - so it gets the smallest one
	 * worth landing rather than nothing, and the armaments that come with it.
	 * Without that it would sit dry for ever, fabricating nothing, because on
	 * paper it was strong enough.
	 */
	public static float fabricateNeed(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		float gap = holdGap(front, market);
		if (gap > 0f) return gap;
		return isDry(front) ? Math.max(0f, ThreatIncConfig.frontMinMarines()) : 0f;
	}

	/**
	 * The armaments a drop lands with: enough to bring the WHOLE front - the
	 * troops already there and the ones coming down - up to
	 * {@code fabricateSupplyDays} of burn, never a flat issue per batch that
	 * would pile up over a long siege. The fragments carry supply for everyone,
	 * which is what keeps a fed front out of the dry penalty between drops.
	 */
	public static float fabricateArms(GroundFront front, int troops) {
		if (front == null || !needsArms(front)) return 0f;
		float target = dailyUpkeep(front.marines + troops) * ThreatIncConfig.fabricateSupplyDays();
		return Math.max(0f, target - front.armaments);
	}

	/**
	 * One poll of a Defend station feeding its front. Two separate figures, and
	 * keeping them separate is the whole design (2026-09-08):
	 *
	 * <ul>
	 * <li><b>What it commits</b> is what the front is SHORT ({@link #fabricateNeed}),
	 * converted at {@code fabricateTroopsPerFP} - never a fleet's worth because
	 * the gap was small. Committing to a shallow deficit costs a shallow bite,
	 * and once the front holds, {@link #defendFabricates} is false and this
	 * stops being called at all.</li>
	 * <li><b>What it costs</b> is {@link #fabricateCost}: the toll UNDISRUPTED
	 * batteries would take for the day, charged on top as ships lost rather
	 * than ships landed, into the same bank the duel uses.</li>
	 * </ul>
	 *
	 * The commitment is deliberately NOT rate-limited by the price: a world
	 * with no batteries charges nothing, and a front there would never be fed
	 * if the price were also the ration. What comes off the fleet comes off as
	 * whole hulls, smallest first, the remainder banked - so fragments go down
	 * a ship at a time. Returns the points landed as troops.
	 */
	public static float fabricateTroops(CampaignFleetAPI fleet, MarketAPI market, String factionId,
			float days, String label) {
		if (fleet == null || market == null || !Factions.THREAT.equals(factionId) || days <= 0f) return 0f;
		if (!fleet.isAlive() || fleet.isExpired()) return 0f;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return 0f;
		// it fights for the orbit before it gives anything to the ground
		if (orbitContestedFor(factionId, market)) {
			ThreatFleetOrders.idleReport(fleet, market, label, "fighting for the orbit");
			return 0f;
		}
		float perFP = Math.max(0.01f, ThreatIncConfig.fabricateTroopsPerFP());
		float wanted = fabricateNeed(front, market) / perFP;
		float price = fabricateCost(fleet.getFleetPoints(), market, days);
		// nothing left to break up: the flagship and the last hull are spared,
		// and a fleet down to them has given everything. It still holds the orbit.
		boolean canGive = wanted > 0f && fleet.getFleetData().getNumMembers() > 1;
		float removed = canGive ? applyFleetLosses(fleet, wanted, null, FABRICATE_BANK_KEY) : 0f;
		int troops = 0;
		if (removed > 0f) {
			fleet.getMemoryWithoutUpdate().set(FABRICATE_FLAG_KEY, true);
			troops = (int) (removed * perFP);
			if (troops > 0) {
				float arms = fabricateArms(front, troops);
				resupply(front, troops, arms);
				fabricationVisual(market, factionId, troops);
			}
		}
		// the guns get their shot at the fragments going down, at the weight
		// they would have had undisrupted - ships lost, not ships landed
		float paid = canGive ? applyFleetLosses(fleet, price, null, SIEGE_DAMAGE_KEY) : 0f;
		if (troops > 0 || paid > 0f) {
			ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName()
					+ " broke up " + (int) removed + " FP of hulls into " + troops + " troops"
					+ (paid > 0f ? ", and lost " + (int) paid + " FP to the batteries doing it" : "")
					+ "; " + (int) fleet.getFleetPoints() + " FP left");
		}
		if (!fleet.getMemoryWithoutUpdate().getBoolean(SLICE_LOG_KEY)) {
			fleet.getMemoryWithoutUpdate().set(SLICE_LOG_KEY, true, 1f);
			ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName() + " at "
					+ (int) fleet.getFleetPoints() + " FP fabricates - bombardment has done what it can and "
					+ "the front is " + (int) Math.ceil(fabricateNeed(front, market))
					+ " troops short of holding with margin; " + String.format("%.1f", wanted) + " FP of hulls wanted, "
					+ String.format("%.2f", price) + " FP/day the batteries charge"
					+ (canGive ? "" : " (nothing left to break up)"));
		}
		return removed;
	}

	/**
	 * The drop, seen from the map (2026-09-08, the bonus ask):
	 * {@link ThreatFabricationVisual} rains glow trails INTO the planet, a
	 * floating label names the state, and a ping pulls the eye. Deliberately
	 * NOT vanilla's bombardment burst - the whole point of this state is that
	 * the fleet has stopped bombarding, so the burst would say the opposite.
	 * The trail count rides on the size of the drop, so a fleet giving up a
	 * cruiser looks like more than one giving up a frigate.
	 */
	protected static void fabricationVisual(MarketAPI market, String factionId, int troops) {
		if (market == null || market.getPrimaryEntity() == null) return;
		java.awt.Color colour = Factions.THREAT.equals(factionId)
				? Misc.getNegativeHighlightColor() : Misc.getHighlightColor();
		com.fs.starfarer.api.campaign.FactionAPI faction = factionId != null
				? Global.getSector().getFaction(factionId) : null;
		if (faction != null && !Factions.THREAT.equals(factionId)) colour = faction.getBaseUIColor();
		int fragments = Math.max(3, Math.min(24, troops / 40));
		ThreatFabricationVisual.play(market.getPrimaryEntity(), colour,
				"Fleet fragments landing", fragments);
	}
	/**
	 * Whether a Defend fleet over this world is committed to the ground and
	 * must NOT stand down on strength (the user, 2026-09-08: it never gives up
	 * because of this) - it can fabricate now, or it already has and the front
	 * it fed still stands. It leaves when the front does, won or lost.
	 */
	public static boolean defendCommitted(CampaignFleetAPI fleet, String factionId, String marketId) {
		if (fleet == null || marketId == null) return false;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market == null) return false;
		if (!Factions.THREAT.equals(factionId)) return navyHoldsOver(fleet, factionId, market);
		if (!ThreatIncConfig.fabricateEnabled()) return false;
		if (defendFabricates(factionId, market, fleet)) return true;
		GroundFront front = getFront(marketId);
		return front != null && factionId != null && factionId.equals(ownerOf(front))
				&& fleet.getMemoryWithoutUpdate().getBoolean(FABRICATE_FLAG_KEY);
	}

	/**
	 * A navy's Defend fleet over its own standing front stays however worn
	 * (2026-09-28, the user: troops on the ground are not left to the swarm):
	 * holding the orbit costs it nothing, keeps the swarm from bombarding the
	 * front (tickSwarmBombard) and keeps the door open for the front runs
	 * that reinforce it (ThreatConvoys.canRunTo). It goes only when worn AND
	 * outweighed - Defense Swarms over the world of at least
	 * siegeBreakOffRatio x its faction's points there - and the runs'
	 * held-back door then sends a Support sortie to clear it (supportFor).
	 */
	public static boolean navyHoldsOver(CampaignFleetAPI fleet, String factionId, MarketAPI market) {
		GroundFront front = market != null ? getFront(market.getId()) : null;
		if (fleet == null || front == null || factionId == null || !factionId.equals(ownerOf(front))) return false;
		float ratio = ThreatIncConfig.siegeBreakOffRatio();
		if (ratio <= 0f) return true;
		float ours = orbitPoints(factionId, market, fleet.getFleetPoints());
		return hostilePointsNear(factionId, market) < ours * ratio;
	}

	/**
	 * The troops that would put this front back over the hold line with
	 * fabricateHoldMargin of daylight, measured at the footing the front
	 * fights at: what a front that cannot hold asks for ({@link #fabricateNeed},
	 * ThreatConvoys.frontWants). 0 once it holds.
	 */
	public static float holdGap(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		float want = holdRequirement(market) * Math.max(1f, ThreatIncConfig.fabricateHoldMargin());
		float mult = Math.max(0.01f, entrenchMult(front));
		return Math.max(0f, want - front.marines * mult) / mult;
	}

	/**
	 * Whether an expedition should land on the world now rather than keep
	 * bombarding it: orbit has done what it can for it ({@code orbitDone} -
	 * {@link #orbitDone}: a day now gains less than a day of repair, or its
	 * ordnance is spent), or the troops could hold as they are. The second
	 * branch is the commander's trade (the user, 2026-09-06, after a round
	 * trip through "always soften first"): every day in orbit costs ships to
	 * the batteries, so a force that can already hold spends marines rather
	 * than hulls - and the Support button is there when that call is wrong.
	 */
	public static boolean readyToLand(MarketAPI market, float troops, boolean orbitDone) {
		if (market == null) return false;
		if (orbitDone) return true;
		return troops * ThreatIncConfig.frontLandingMult() >= holdRequirement(market);
	}

	/**
	 * As {@link #readyToLand(MarketAPI, float, boolean)} for a siege expedition's FIRST
	 * landing: an NPC one also keeps duelling until its beachhead survives the
	 * world's first counter-attack ({@link #beachheadSurvives}), unless orbit has
	 * done all it can. NPC sieges are sized for a defence the tactical pass has
	 * suppressed (IncursionManager.siegeRaidStrNeeded), but the hold test opened
	 * at 0 siege days, so they landed against the intact defence with about 60%
	 * of what the overrun rule asks: 10 of 22 NPC beachheads of Run 7 fell to the
	 * first counter-attack (rc1 review). The player's landing stays the
	 * commander's call (2026-09-06).
	 */
	public static boolean readyToLand(MarketAPI market, float troops, String factionId, boolean orbitDone) {
		if (!readyToLand(market, troops, orbitDone)) return false;
		if (factionId == null || Factions.PLAYER.equals(factionId)) return true;
		if (orbitDone) return true;
		return beachheadSurvives(market, troops);
	}

	/**
	 * Whether a fresh beachhead of {@code troops} outlasts the world's first
	 * counter-attack by siegeBeachheadMargin: the counter-attack overruns it past
	 * 2:1 odds after the strength exponent (IncursionManager.beachheadNeeded, the
	 * same line the landing is sized on). Margin 0 skips the test.
	 */
	/** The smallest landing {@link #beachheadSurvives}: 0 when the test is off. */
	public static int beachheadTroops(MarketAPI market) {
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (market == null || margin <= 0f) return 0;
		float e = Math.max(0.1f, ThreatIncConfig.groundStrengthExponent());
		float odds = (float) Math.pow(2f, 1f / e);
		return (int) Math.ceil(counterAttackStrength(market) * margin
				/ Math.max(0.01f, ThreatIncConfig.frontLandingMult() * odds));
	}

	public static boolean beachheadSurvives(MarketAPI market, float troops) {
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (market == null || margin <= 0f) return true;
		float e = Math.max(0.1f, ThreatIncConfig.groundStrengthExponent());
		float odds = (float) Math.pow(2f, 1f / e);
		return troops * ThreatIncConfig.frontLandingMult() * odds >= counterAttackStrength(market) * margin;
	}

	/**
	 * The expedition's status line over a world it has not landed on, saying
	 * WHY it is landing when it is (the user, 2026-09-06: the early landing
	 * was the part nobody realised): {@code besieging} while orbit still has
	 * work and the troops could not hold; otherwise which branch opened the
	 * gate.
	 */
	public static String landingPhase(MarketAPI market, float troops, String besieging, boolean orbitDone) {
		return landingPhase(market, troops, besieging, null, orbitDone);
	}

	/** As above, for the faction whose landing it is ({@link #readyToLand(MarketAPI, float, String, boolean)}). */
	public static String landingPhase(MarketAPI market, float troops, String besieging, String factionId,
			boolean orbitDone) {
		if (market == null) return besieging;
		if (readyToLand(market, troops, factionId, false)) return "moving to land - the troops can hold, sparing the ships";
		if (orbitDone) return "moving to land - bombardment has done what it can";
		return besieging;
	}

	/** Whether the front can hold as it stands: its effective strength against what holding here needs. */
	public static boolean frontCanHold(GroundFront front, MarketAPI market) {
		return front != null && market != null && effectiveStrength(front) >= holdRequirement(market);
	}

	/**
	 * Whether a DEFEND fleet of the faction bombards this world now
	 * (2026-09-07): only while the faction's own front on the ground cannot
	 * hold, and while orbit still pays for this fleet ({@link #orbitDoneFor}:
	 * a day gains more than a day of repair, and it has ordnance to drop).
	 * Never with no front, or with one that holds: keeping the ships is the
	 * point of Defend over Support.
	 */
	/**
	 * WHETHER A FLEET ON STATION FIGHTS FOR THE ORBIT IT IS SITTING IN
	 * (2026-09-08). Everything that reads the orbit here already refuses while
	 * it is contested - {@link #supportSlice} fires nothing,
	 * {@link #fabricateTroops} sends nothing down - because orbit comes before
	 * ground, the same order {@code siegePass} keeps before a landing. What was
	 * missing is the other half: something that turns that refusal into an
	 * attack. Hold station leaves a Defend fleet blinkered
	 * ({@link ThreatFleetOrders#leash}), which is right for a defender that
	 * runs and wrong for a station, so a siege that outlasted its target's
	 * repairs would sit out the rest of its life beside a star fortress that
	 * had come back on and put its multiplier back on the world's ground
	 * defence. This is what the poll stamps on the fleet to take the blinders
	 * off ({@link ThreatFleetOrders#fightOrbit}); it follows the same knob as
	 * the fight before the landing, so a doctrine that never fights for an
	 * orbit still does not.
	 */
	public static boolean fightsForOrbit(String factionId, MarketAPI market) {
		if (!ThreatIncConfig.siegeFightsForOrbit()) return false;
		if (orbitContestedFor(factionId, market)) return true;
		// ...or a station flies there at all, whatever it weighs. Losing the
		// contest is not the test: a besieger that outweighs a star fortress
		// still holds the space by {@link #orbitHeld} and would leave it alone
		// forever, and that is the case the user hit. A station is the one
		// fortification orbit cannot grind down - {@link ThreatSiegeMalus}
		// suppresses ground works only - so for as long as it is undisrupted
		// it goes on multiplying the world's ground defence and nothing else
		// in the siege can touch it. It is fought, not waited out.
		return stationFlies(factionId, market);
	}

	/**
	 * An enemy station over the world, back from its repairs. Vanilla despawns
	 * the station fleet while the industry is disrupted and respawns it when
	 * the disruption ends, so "it still flies" is the same question as "it is
	 * no longer under repair" - the test {@link Theatre#orbitHeldAgainst}
	 * already uses to decide whether it holds the orbit.
	 */
	public static boolean stationFlies(String factionId, MarketAPI market) {
		if (market == null || factionId == null) return false;
		if (factionId.equals(market.getFactionId())) return false;
		if (market.getFaction() == null || !market.getFaction().isHostileTo(factionId)) return false;
		CampaignFleetAPI station = Misc.getStationFleet(market);
		return station != null && station.isAlive() && !station.isExpired()
				&& station.getFleetPoints() > 0f;
	}

	/**
	 * FINISH BY SATURATION (2026-09-28): an NPC faction's DEFEND fleet over its
	 * own front razes the levels the enemy still holds (ThreatRazing.enemyLayers)
	 * instead of waiting on the push, when its ordnance ({@link #ordnanceAvailable})
	 * covers the whole pour and its faction's ships over the world outlast the
	 * guns doing it ({@link #razePlan}) - faster than a front. Never the swarm
	 * (it razes only as strikeSaturationEnabled says), the player (Bombard or
	 * by hand), with npcRazeEnabled off, a world saturation cannot finish, a
	 * contested orbit, or a front that takes the last stratum before the
	 * razing would ({@link #daysToLastStratum}). Never over a hive either:
	 * saturation takes no size off one (ThreatRazing.razes), so it cannot
	 * finish the front (2026-10-01).
	 */
	public static boolean defendRazes(String factionId, MarketAPI market, CampaignFleetAPI fleet) {
		if (fleet == null || factionId == null || market == null) return false;
		if (Factions.THREAT.equals(factionId) || Factions.PLAYER.equals(factionId)) return false;
		if (!ThreatIncConfig.npcRazeEnabled() || !ThreatRazing.razes(market)) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return false;
		int layers = ThreatRazing.enemyLayers(market);
		if (layers <= 0 || ThreatRazing.razeable(market) < layers) return false;
		if (orbitContestedFor(factionId, market)) return false;
		float fuel = ThreatRazing.fuelToDestroyThrough(market);
		if (ordnanceAvailable(fleet, factionId) < fuel) return false;
		float[] plan = razePlan(market, orbitPoints(factionId, market, fleet.getFleetPoints()), fuel);
		if (plan[3] < 1f) return false;
		// on station already, so only the razing's days race the front's own finish
		float frontDays = daysToLastStratum(front, market);
		return frontDays < 0f || frontDays > Math.max(1f, plan[0]);
	}

	/** A day of {@link #defendRazes}: the fleet saturates the world, pays the fuel poured and takes its share of the guns. */
	public static void defendRazeSlice(CampaignFleetAPI fleet, MarketAPI market, String factionId,
			float days, String label) {
		if (fleet == null || market == null || days <= 0f) return;
		float fp = fleet.getFleetPoints();
		String name = market.getName();
		if (!fleet.getMemoryWithoutUpdate().getBoolean(SLICE_LOG_KEY)) {
			fleet.getMemoryWithoutUpdate().set(SLICE_LOG_KEY, true, 1f);
			ThreatIncConfig.log(label + " over " + name + ": " + fleet.getName() + " at " + (int) fp
					+ " FP finishes it by saturation - " + (int) ThreatRazing.fuelToDestroyThrough(market)
					+ " fuel to pour, " + ThreatRazing.enemyLayers(market) + " levels the enemy holds");
		}
		float[] out = saturationSlice(fp, orbitPoints(factionId, market, fp), market, days,
				ordnanceAvailable(fleet, factionId), true, true, -1f, factionId, "Orbital bombardment");
		payOrdnance(fleet, factionId, out[1]);
		float removed = applyFleetLosses(fleet, out[0]);
		ThreatIncConfig.log(label + " over " + name + ": " + fleet.getName() + " poured " + (int) out[1]
				+ " fuel, " + (int) out[2] + " levels razed" + (out[3] > 0f ? ", destroyed" : "")
				+ "; batteries cost " + (int) removed + " FP");
	}

	public static boolean defendBombards(String factionId, MarketAPI market, CampaignFleetAPI fleet) {
		if (factionId == null || market == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return false;
		// A front that holds is not the same as a front that survives
		// (2026-09-08). "Holding" is the SUPPRESSION threshold - effective
		// strength against holdFraction of the defence - and says nothing
		// about the counter-attack, which is resolved at the defence's full
		// weight. A front could sit in holding at 4:1 down, get overrun
		// outright, and the fleet that was stationed to protect it would have
		// stood by with its guns cold, because the old gate asked "is my front
		// suppressing structures" instead of "is my front about to die".
		//
		// The lever is real: a slice suppresses the fortifications, the siege
		// malus cuts the ground-defence stat, and the counter-attack's own
		// strength comes down with it. So the fleet stays idle only while the
		// front both holds AND would not be destroyed by the next blow -
		// keeping the ships is still the point of Defend over Support.
		// Never over troops in the open (user, 2026-09-08): a bombardment this
		// close to a front that is out of its holes is friendly fire, and the
		// order of operations is the front's, not the fleet's - the troops dig
		// in first ({@link #shouldBrace}), and the guns speak only if cover was
		// not enough on its own. A front that is still assaulting has decided
		// it does not need either.
		if (STANCE_PUSH.equals(front.stance)) return false;
		if (frontCanHold(front, market) && !counterAttackOverruns(front, market)) return false;
		return !orbitDoneFor(market, fleet, factionId);
	}

	/**
	 * SUPPORT sorties besiege too (2026-09-06): every fleet under a Support
	 * order that is on station over a hostile world whose orbit nobody holds
	 * against it delivers a slice of the siege each poll with its live fleet
	 * points, and the batteries answer it like any besieging fleet. A front
	 * on the ground does not stop it - it keeps the fortifications worn while
	 * the front does the rest.
	 * A DEFEND fleet (2026-09-07) slices only while {@link #defendBombards}.
	 */
	protected static void tickSupport(float elapsedDays) {
		for (ThreatFleetOrders.Order o : new ArrayList<ThreatFleetOrders.Order>(ThreatFleetOrders.all())) {
			boolean support = ThreatFleetOrders.KIND_SUPPORT.equals(o.kind);
			boolean defend = ThreatFleetOrders.KIND_DEFEND.equals(o.kind);
			if ((!support && !defend) || o.fleet == null) continue;
			if (!o.fleet.isAlive() || o.fleet.isExpired()) continue; // the orders poll culls it
			MarketAPI market = Global.getSector().getEconomy().getMarket(o.targetId);
			if (market == null) continue;
			// a landing's station has no return leg queued behind its orbit, so
			// it is kept there by hand; a termed sortie keeps vanilla's queue
			if (o.indefinite()) ThreatSwarmDefend.holdOrbit(o.fleet, market, o.task());
			String label = support ? "Support" : "Defend";
			if (market.getPrimaryEntity() == null) continue;
			// the orbit before the ground: stamped whether or not the fleet is
			// on station yet, so it arrives with its reflexes already back
			ThreatFleetOrders.fightOrbit(o.fleet, fightsForOrbit(o.factionId, market));
			if (!ThreatFleetOrders.nearPlanet(o.fleet, market.getPrimaryEntity())) {
				ThreatFleetOrders.stationReport(o.fleet, market, label);
				continue;
			}
			if (defend && defendRazes(o.factionId, market, o.fleet)) {
				defendRazeSlice(o.fleet, market, o.factionId, elapsedDays, label);
				continue;
			}
			if (defend && !defendBombards(o.factionId, market, o.fleet)) {
				// bombardment has nothing left to give and the front still
				// cannot hold: the fleet goes down instead of firing
				if (defendFabricates(o.factionId, market, o.fleet)) {
					fabricateTroops(o.fleet, market, o.factionId, elapsedDays, label);
				} else {
					ThreatFleetOrders.idleReport(o.fleet, market, label, idleReason(o.factionId, market));
				}
				continue;
			}
			supportSlice(o.fleet, market, o.factionId, elapsedDays, label);
		}
	}

	/** Why a Defend fleet at its world is not bombarding now (see {@link #defendBombards}). */
	public static String idleReason(String factionId, MarketAPI market) {
		if (fightsForOrbit(factionId, market)) return "fighting for the orbit";
		GroundFront front = market != null ? getFront(market.getId()) : null;
		if (front == null || factionId == null || !factionId.equals(ownerOf(front))) return "no front of its own";
		if (frontCanHold(front, market)) return "the front holds";
		if (!ThreatIncConfig.fabricateEnabled() || !Factions.THREAT.equals(factionId)) {
			return "bombardment has done what it can";
		}
		return "bombardment has done what it can, and it has nothing left to send down";
	}

	/**
	 * One supporting fleet's slice for the poll: on station over a hostile
	 * world whose orbit nothing holds against its faction, its live points
	 * bombard and the batteries answer. Nothing while the orbit is contested -
	 * it fights for it first - and nothing past its ordnance: the day's fuel
	 * comes out of what it carries and then its base's reserve
	 * ({@link #payOrdnance}). Returns the points the batteries took.
	 */
	public static float supportSlice(CampaignFleetAPI fleet, MarketAPI market, String factionId,
			float days, String label) {
		if (fleet == null || market == null || factionId == null || days <= 0f) return 0f;
		if (!fleet.isAlive() || fleet.isExpired()) return 0f;
		if (!market.isInEconomy() || market.getPrimaryEntity() == null) return 0f;
		if (market.getFaction() == null || factionId.equals(market.getFactionId())) return 0f;
		if (!market.getFaction().isHostileTo(factionId)) return 0f;
		if (!ThreatFleetOrders.nearPlanet(fleet, market.getPrimaryEntity())) return 0f;
		boolean contested = orbitContestedFor(factionId, market);
		float fp = fleet.getFleetPoints();
		// once a day per fleet: the numbers the tooltip quotes against the
		// clock, or why nothing was fired
		if (!fleet.getMemoryWithoutUpdate().getBoolean(SLICE_LOG_KEY)) {
			fleet.getMemoryWithoutUpdate().set(SLICE_LOG_KEY, true, 1f);
			if (contested) {
				ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName()
						+ " fights for the orbit - " + (int) hostilePointsNear(factionId, market)
						+ " FP of defenders against " + (int) friendlyPointsNear(factionId, market)
						+ " FP here");
			} else {
				BombardDay est = bombardDay(fp, market, false);
				ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName() + " at "
						+ (int) fp + " FP bombards at ~" + String.format("%.1f", est.rate)
						+ " d/day, batteries ~" + String.format("%.1f", est.returnFire) + " FP/day; defences "
						+ Math.round(est.conditionNow * 100f) + "%");
			}
		}
		if (contested) return 0f;
		float want = bombardFuelPerDay(fp) * days;
		float paid = payOrdnance(fleet, factionId, want);
		if (want > 0f && paid < want) {
			// short of ordnance: bombard for what it could pay, then stand
			days *= paid / want;
			if (days < 0.01f) {
				ThreatFleetOrders.idleReport(fleet, market, label, "out of fuel to bombard with");
				return 0f;
			}
		}
		float loss = siegeSlice(fp, orbitPoints(factionId, market, fp), market, days, true, true, -1f,
				"Orbital bombardment");
		float removed = applyFleetLosses(fleet, loss);
		if (removed > 0f) {
			ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName()
					+ " lost " + (int) removed + " FP to the batteries");
		}
		return removed;
	}

	/**
	 * The points a faction bombards the world with, this fleet's among them:
	 * its armed fleets over the world ({@link #friendlyPointsNear}), never less
	 * than the fleet's own. The player's own fleet is not among them - it
	 * bombards by hand, from the menu.
	 */
	public static float orbitPoints(String factionId, MarketAPI market, float fp) {
		float points = friendlyPointsNear(factionId, market);
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && Factions.PLAYER.equals(factionId) && market != null && nearWorld(player, market)) {
			points -= player.getFleetPoints();
		}
		return Math.max(fp, points);
	}

	/**
	 * A Threat front signalling for the next expedition
	 * ({@link IncursionManager} weights the world up as a strike target by
	 * {@code strikeReinforceWeight}).
	 *
	 * <p>This used to mean "dry". With the swarm's armaments gone (2026-09-08)
	 * that signal would simply never fire again and a stalled landing would
	 * quietly lose its reinforcement priority - a behaviour change nobody
	 * asked for, hidden inside one that was. So it is re-keyed to the thing
	 * dryness stood for: a front that cannot hold is the one that needs the
	 * next wave. Under {@code threatFrontNeedsArms} the old test is used
	 * unchanged.
	 */
	public static boolean wantsExpedition(MarketAPI market) {
		if (market == null) return false;
		GroundFront front = getFront(market.getId());
		if (!isThreatOwned(front) || front.finalPush) return false;
		if (needsArms(front)) return front.armaments <= 0f;
		return !frontCanHold(front, market) || losingGround(front, market);
	}

	/**
	 * The world's counter-attack, by siegeBeachheadMargin, beats the front as
	 * it stands (2026-09-29, overnight run N3): it takes a stratum back, or
	 * overruns a beachhead holding none. frontCanHold is the fortification
	 * line (frontHoldFraction of the defence) and stayed true while relief
	 * convoys fed the garrisons that overran 14 of 17 Threat beachheads.
	 */
	public static boolean losingGround(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		float margin = Math.max(1f, ThreatIncConfig.siegeBeachheadMargin());
		return counterAttackStrength(market) * margin > defenseStrength(front);
	}

	/**
	 * Marines an own colony under a Threat army still wants banked for its
	 * counter-attack to beat that army by siegeBeachheadMargin
	 * (2026-09-29): what relief convoys are sized to (ThreatConvoys.planRelief),
	 * in place of one flat hull load at a time. Stock already on the world but
	 * not yet armed counts, as it will be. Where banked marines add nothing to
	 * a counter-attack (marineCounterAttackMult 0) they still hold the line, so
	 * the want is then the marines that push the army below its grind line. 0
	 * with no Threat front, or once the garrison is enough.
	 */
	public static float reliefNeed(MarketAPI market) {
		if (market == null || isHiveTarget(market)) return 0f;
		GroundFront front = getFront(market.getId());
		if (!isThreatOwned(front)) return 0f;
		float perMarine = Math.max(0f, ThreatIncConfig.reserveDefenseMult())
				* ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(market));
		if (perMarine <= 0f) return 0f; // banked marines do not fight here
		float margin = Math.max(1f, ThreatIncConfig.siegeBeachheadMargin());
		float armed;
		float counterMult = Math.max(0f, ThreatIncConfig.marineCounterAttackMult());
		if (counterMult > 0f) {
			float gap = defenseStrength(front) * margin - counterAttackStrength(market);
			armed = gap / (perMarine * counterMult);
		} else {
			float grind = Math.max(0.01f, ThreatIncConfig.frontGrindFraction());
			float gap = effectiveStrength(front) * margin / grind - defenderStrength(market);
			armed = gap / perMarine;
		}
		float arming = Math.max(0f, ThreatReserves.stock(market.getId(), Commodities.MARINES)
				- ThreatReserves.armedMarines(market));
		return Math.max(0f, armed - arming);
	}

	/** Days until a dry Threat front's final push, or -1 when it is not waiting. */
	public static float daysToFinalPush(GroundFront front) {
		if (!isThreatOwned(front) || !isDry(front) || front.finalPush) return -1f;
		if (front.dryTimestamp <= 0) return ThreatIncConfig.frontDryFinalPushDays();
		return Math.max(0f, ThreatIncConfig.frontDryFinalPushDays() - Global.getSector()
				.getClock().getElapsedDaysSince(front.dryTimestamp));
	}

	/** "stratum" on a hive, "district" on a colony. */
	public static String layerName(MarketAPI market) {
		if (market == null) return "stratum";
		return Theatre.of(market).layer();
	}

	/** A player's or faction's front is gone: the surviving positions were overrun. */
	protected static void announceCollapse(GroundFront front, MarketAPI market) {
		ThreatNotice n = ThreatNotice.titled("Front Collapsed").bad().icon(ownerFaction(front));
		if (front.isPlayerOwned()) {
			n.line("Your front on %s has collapsed.", ThreatNotice.market(market));
		} else {
			n.line("%s's front on %s has collapsed.",
					ThreatNotice.faction(ownerFaction(front)), ThreatNotice.market(market));
		}
		n.line("The surviving positions were overrun.").send();
	}
	/**
	 * The colony died under the front. There is no market left to dock with,
	 * and stranding the survivors punishes winning.
	 *
	 * <p>When an outpost already stands over the dead world the survivors
	 * simply stay: player and NPC alike, they are the station's garrison
	 * stockpile, ready for the next front run out of it ({@link ThreatConvoys}).
	 * With no outpost they are lifted off by their own support elements and
	 * returned to the player fleet, or - for an NPC front - banked in the
	 * reserve of the faction's nearest base in reach.
	 */
	protected static void evacuate(GroundFront front, MarketAPI market) {
		evacuate(front, null, market != null ? market.getLocationInHyperspace() : null);
	}

	protected static void evacuate(GroundFront front, ThreatOutposts.Outpost outpost,
			Vector2f hyperLoc) {
		int marines = Math.round(front.marines);
		int armaments = (int) Math.floor(front.armaments);
		// what they learned down there comes home with them (2026-09-08)
		float level = ThreatMarineXP.frontLevel(front);
		if (isThreatOwned(front)) {
			// the swarm has no reserve to bank into (ThreatWarState excludes the
			// Threat) and no outpost to garrison. Whatever was on the surface is
			// simply gone with the front.
			ThreatIncConfig.log("Threat front dispersed at " + worldName(front) + ": "
					+ marines + " marines, " + armaments + " armaments lost");
			return;
		}
		if (outpost != null) {
			ThreatOutposts.deposit(outpost, Commodities.MARINES, marines);
			ThreatOutposts.deposit(outpost, Commodities.HAND_WEAPONS, armaments);
			if (front.isPlayerOwned()) {
				ThreatNotice.titled("Outpost Garrisoned").good().icon(Global.getSector().getPlayerFaction())
						.line("Your ground forces hold what they took.")
						.line("%s marines and %s heavy armaments stock the outpost over %s.",
								ThreatNotice.green(marines), ThreatNotice.green(armaments),
								outpost.planetName())
						.send();
			}
			ThreatIncConfig.log("Front survivors garrison the outpost over "
					+ outpost.planetName() + ": " + marines + " marines, " + armaments
					+ " armaments");
			return;
		}
		if (!front.isPlayerOwned()) {
			com.fs.starfarer.api.campaign.FactionAPI faction =
					Global.getSector().getFaction(front.factionId);
			MarketAPI base = faction != null && hyperLoc != null
					? ThreatFleetOrders.pickBase(faction, hyperLoc) : null;
			// a colony, not a forward base: a link that falls takes its stock with
			// it (run 16 banked 3 of 4 victories' survivors into links)
			if (base != null && ThreatFrontlines.isOutpost(base)) base = null;
			if (base == null && faction != null) {
				// the nearest colony base of theirs, else any colony, else a link
				MarketAPI colony = null, link = null;
				float best = Float.MAX_VALUE;
				for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
					if (ThreatFrontlines.isOutpost(m)) {
						if (link == null) link = m;
						continue;
					}
					if (colony == null) colony = m;
					if (!IncursionManager.isBase(m) || m.getPrimaryEntity() == null) continue;
					float d = hyperLoc == null ? 0f : Misc.getDistanceLY(hyperLoc, m.getLocationInHyperspace());
					if (d < best) {
						best = d;
						base = m;
					}
				}
				if (base == null) base = colony != null ? colony : link;
			}
			if (base == null) {
				ThreatIncConfig.log("Front survivors stranded (no base in reach): " + worldName(front));
				return;
			}
			// veterans coming off a front season the garrison they fall back on,
			// and are posted at once rather than walking the arming ramp
			ThreatReserves.depositArmed(base, marines, level);
			ThreatReserves.deposit(base.getId(), Commodities.HAND_WEAPONS, armaments);
			ThreatIncConfig.log("Front evacuated to " + base.getName() + ": " + marines
					+ " marines, " + armaments + " armaments ("
					+ ThreatMarineXP.rankName(level) + ")");
			return;
		}
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null) {
			if (marines > 0) {
				player.getCargo().addMarines(marines);
				// bodies first, then their experience: vanilla clamps the pool
				// to the headcount, so the order is load-bearing
				ThreatMarineXP.fleetReturn(marines, level);
			}
			if (armaments > 0) {
				player.getCargo().addCommodity(Commodities.HAND_WEAPONS, armaments);
			}
		}
		ThreatNotice.titled("Front Lifted Off").good().icon(Global.getSector().getPlayerFaction())
				.line("%s %s marines rejoin the fleet.", ThreatNotice.green(marines),
						ThreatMarineXP.rankName(level).toLowerCase())
				.send();
		ThreatIncConfig.log("Front evacuated: " + worldName(front));
	}
}
