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
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
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
 * final stratum destroys the Core and ERADICATES the colony - ground victory
 * is the only way a hive dies; starvation and bombardment only make it
 * cheaper. The hive counter-attacks on a cadence paced by its vitality and
 * can retake strata from a front too weak to hold them.
 *
 * <p>Everything ticks at flat daily rates on the colony poll: armaments burn
 * as upkeep (the stockpile IS the supply countdown, and a dry front fights at
 * reduced effectiveness), marines attrit (worse while pushing or dry), and a
 * strong enough front additionally SUPPRESSES structures by feeding their
 * disruption clocks - the mod's existing wear mechanic.
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

	/** Market memory flag: saturation fallout, blocks landing ground forces. */
	public static final String FALLOUT_FLAG = "$threatinc_fallout";
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
		/** Last state announced, so transitions message exactly once. */
		public String announcedState;
		/** Whether the out-of-supply message has fired for the current dry spell. */
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
		/** Whether the bombardment notice has fired for the current unopposed spell. */
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
		if (market.isPlanetConditionMarketOnly()) return null;
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
		/** Disruption days at which a fortification has worn to nothing: the clock the orbital floor is a fraction of. */
		public abstract float wearDays();
		/** Disruption days an unopposed fleet adds per day at ratio 1. */
		public abstract float suppressDaysPerDay();
		/** The batteries' share of the defence figure: what answers a fleet in orbit. */
		public abstract float batteryShare(MarketAPI market);
		/** The same share with every gun intact: what a fleet pays to fabricate ground troops from its hulls. */
		public abstract float intactBatteryShare(MarketAPI market);
		/** 1 intact .. 0 fully suppressed, from the structure's clock and the orbital floor. */
		public abstract float condition(MarketAPI market, Industry ind);
		/** Whether the space over the world is held against a front of this owner. */
		public abstract boolean orbitHeldAgainst(String ownerFactionId, MarketAPI market);
		/** Whether the strata loss needs a carrier of its own on the market (the siege malus and the tooltip). */
		public abstract boolean carriesSiegeState();
		/** The last stratum has fallen. */
		public abstract void victory(GroundFront front, MarketAPI market);
		/** Who counter-attacks, for the message. */
		public abstract String counterAttacker(MarketAPI market);
	}

	/**
	 * A hive world under siege. Its strata loss lives inside SwarmNexus.apply
	 * and its tooltip is HiveVitalityCondition, so the market carries nothing
	 * extra; its counter-attacks pace on vitality; its orbit is contested by
	 * its Defense Swarms; its fall is eradication.
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
			// starve it and the counterstroke never comes - health still paces
			// the hive underneath - and having the body to spare speeds it up
			// on top, the same as a colony (user, 2026-09-08)
			return ThreatIncConfig.frontCounterAttackDays()
					/ Math.max(0.25f, ThreatColonyManager.computeHealth(market))
					/ counterAttackTempo(front, market);
		}
		public List<Industry> keyStructures(MarketAPI market) {
			// the named organs - Core, Nexus, port and both defense structures
			List<Industry> list = new ArrayList<Industry>();
			Industry core = market.getIndustry(ThreatColonyManager.FABRICATION_CORE);
			if (core != null) list.add(core);
			Industry nexus = market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
			if (nexus != null) list.add(nexus);
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
			// commands them
			List<Industry> list = defenseStructures(market);
			Industry nexus = market.getIndustry(ThreatColonyManager.SWARM_NEXUS);
			if (nexus != null) list.add(nexus);
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
				float bonus = ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(ind.getId())
						? ThreatIncConfig.heavyBatteriesBonus() : ThreatIncConfig.groundDefensesBonus();
				float deficit = ThreatSiegeMalus.deficitMult(ind,
						Commodities.HEAVY_MACHINERY, Commodities.METALS);
				mult *= 1f + bonus * deficit * condition(market, ind);
			}
			return mult <= 1f ? 0f : 1f - 1f / mult;
		}
		public float intactBatteryShare(MarketAPI market) {
			float mult = 1f;
			for (Industry ind : defenseStructures(market)) {
				if (ind.isBuilding() && !ind.isUpgrading()) continue;
				float bonus = ThreatColonyManager.THREAT_HEAVY_BATTERIES.equals(ind.getId())
						? ThreatIncConfig.heavyBatteriesBonus() : ThreatIncConfig.groundDefensesBonus();
				float deficit = ThreatSiegeMalus.deficitMult(ind,
						Commodities.HEAVY_MACHINERY, Commodities.METALS);
				mult *= 1f + bonus * deficit;
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
		public String counterAttacker(MarketAPI market) {
			return "A hive counter-attack";
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
		public String counterAttacker(MarketAPI market) {
			return "A counter-attack by the garrison of " + market.getName();
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
				|| (besieged(market) && ThreatSiegeMalus.anyDisrupted(market)));
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
		front.announcedDry = false;
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

	/** Total loss - the saturation-bombardment case. */
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

	/** Whether saturation fallout currently blocks landings, and for how long. */
	public static float falloutDaysLeft(MarketAPI market) {
		if (market == null) return 0f;
		if (!market.getMemoryWithoutUpdate().getBoolean(FALLOUT_FLAG)) return 0f;
		return Math.max(0f, market.getMemoryWithoutUpdate().getExpire(FALLOUT_FLAG));
	}

	public static void setFallout(MarketAPI market) {
		float days = ThreatIncConfig.falloutDays();
		if (days <= 0f || market == null) return;
		market.getMemoryWithoutUpdate().set(FALLOUT_FLAG, true, days);
	}

	// ------------------------------------------------------------------
	// landings - the one doctrine both expeditions run (docs/ground-war.md).
	// The purge against a hive and the strike against a human world ask the
	// same three questions here, so their answers cannot drift apart.
	// ------------------------------------------------------------------

	/**
	 * Whether the world's defenses would still fight a landing, so the next
	 * pass should be a tactical bombardment. A hive grades on the defense-wear
	 * curve only hive structures carry (a further pass must still be able to
	 * lower resilience, else a wear-off config would bomb forever); a human
	 * colony has vanilla's flat disruption, so any tactical-bombardment
	 * structure still running is the whole story.
	 */
	public static boolean needsSoftening(MarketAPI market) {
		if (market == null) return false;
		// orbit has more to do while any fortification is above the floor
		return !suppressedToFloor(market);
	}

	/**
	 * Why nothing can land on this world for this owner right now, or null:
	 * saturation fallout on the ground, another army already holding it, or -
	 * only with live fleets, since vanilla's autoresolve has already weighed
	 * the expedition against the system's defenders and station before any
	 * pass is delivered - the orbit held against the landing. One gate for a
	 * purge landing, a strike landing and the board's readouts.
	 */
	public static String landingBlocked(String ownerFactionId, MarketAPI market,
			boolean liveFleets) {
		if (market == null) return "no world to land on";
		float fallout = falloutDaysLeft(market);
		if (fallout > 0f) return "saturation fallout, " + (int) Math.ceil(fallout) + " days left";
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
			if (threat) {
				ThreatColonyManager.announceAlways("A fresh Threat wave has come down on "
						+ market.getName() + " - " + Misc.getWithDGS(troops)
						+ " more of them reinforce the ground assault.",
						Misc.getNegativeHighlightColor());
				ThreatAidMissionIntel.strikeLanded(market);
			}
			ThreatIncConfig.log("Front reinforced at " + market.getName() + " (" + ownerFactionId
					+ "): +" + troops + " troops, +" + (int) armaments + " armaments");
			return standing;
		}
		GroundFront front = deploy(market, ownerFactionId, troops, armaments);
		if (threat) {
			ThreatColonyManager.announceAlways("The Threat has landed on " + market.getName()
					+ " - " + Misc.getWithDGS(troops) + " of them are on the surface, and only "
					+ "beating them on the ground will save the colony.",
					Misc.getNegativeHighlightColor());
			ThreatAidMissionIntel.strikeLanded(market);
		} else {
			// a faction's expedition, or the player's commissioned one
			com.fs.starfarer.api.campaign.FactionAPI owner =
					Global.getSector().getFaction(ownerFactionId);
			String who = front.isPlayerOwned() ? "Your"
					: owner != null ? Misc.ucFirst(owner.getDisplayNameWithArticle()) : "An";
			ThreatColonyManager.announceAlways(who + " expedition has landed ground forces on "
					+ market.getName() + " - the campaign for its strata has begun.",
					Misc.getHighlightColor());
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
	 * Days until the colony's next counter-attack goes in. A hive paces on its
	 * vitality (starve it and the counterstroke never comes); a human colony
	 * paces on STABILITY - unrest, shortages and the shock of an invasion are
	 * what stop a garrison organising - and a colony with a military command to
	 * run the operation counter-attacks {@code colonyCounterAttackMilitaryMult}
	 * times as often.
	 */
	public static float counterAttackInterval(GroundFront front, MarketAPI market) {
		if (market == null) return ThreatIncConfig.frontCounterAttackDays();
		return Theatre.of(market).counterAttackInterval(front, market);
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
		if (front == null || market == null) return "No front there.";
		if (STANCE_PUSH.equals(front.stance)) return "Already pushing.";
		if (front.strataHeld >= market.getSize()) return "Nothing left to take.";
		float eff = effectiveStrength(front);
		float need = holdRequirement(market);
		if (eff < need) {
			return "Too weak to push: " + Misc.getWithDGS(Math.round(eff)) + " effective of the "
					+ Misc.getWithDGS(Math.round(need)) + " needed to hold"
					+ (isDry(front) ? " - out of armaments" : "") + ".";
		}
		return null;
	}

	/** Why the board's Dig in order would do nothing now, or null. */
	public static String entrenchBlockReason(GroundFront front) {
		if (front == null) return "No front there.";
		if (STANCE_ENTRENCH.equals(front.stance)) return "Already dug in.";
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
			if (market == null || market.isHidden() || market.isPlanetConditionMarketOnly()) continue;
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
	if (supplied && isDry(front) && !front.announcedDry) {
		front.announcedDry = true;
		front.dryTimestamp = Global.getSector().getClock().getTimestamp();
		// a dry front breaks off its assault and digs in (2026-09-06): the
		// swarm's waits for the next expedition, a faction's for its convoys;
		// the player's fronts take the player's orders
		if (!front.isPlayerOwned() && pushing) {
			orderEntrench(front);
			pushing = false;
		}
		if (threatFront) {
			ThreatColonyManager.announceAlways("The Threat ground forces on "
					+ market.getName() + " have exhausted their heavy armaments - "
					+ "they dig in and signal for the next expedition. Hold the orbit "
					+ "against it, and they wither.", Misc.getPositiveHighlightColor());
		} else {
				ThreatColonyManager.announceAlways("The ground forces on " + market.getName()
						+ " have exhausted their heavy armaments - they fight at reduced "
						+ "effectiveness and casualties will mount until they are resupplied "
						+ "or withdrawn.", Misc.getNegativeHighlightColor());
			}
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
				ThreatColonyManager.announceAlways("The Threat landing on " + market.getName()
						+ " has been ground down to nothing - the surface is clear.",
						Misc.getPositiveHighlightColor());
			} else {
				ThreatColonyManager.announceAlways("The ground front on " + market.getName()
						+ " has collapsed - the surviving positions were overrun.",
						Misc.getNegativeHighlightColor());
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
			if (front.isPlayerOwned()) {
				ThreatColonyManager.announceAlways("The ground forces on " + market.getName()
						+ " break off their assault and dig in - a counter-attack is coming "
						+ "and they hold no ground to give.", Misc.getHighlightColor());
			}
			ThreatIncConfig.log("Front at " + market.getName() + " braces for a counter-attack in "
					+ (int) Math.ceil(daysToCounterAttack(front, market)) + " d");
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
		// would cross it back and forth - and announce every flip. A state
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

		// suppression: feed the disruption clocks. The clock counts down by
		// elapsed naturally, so a rate of 2.0 nets +1 day of clock per day
		// held - and the clock is the wear mechanic, so a held siege grinds
		// the defenses (and a disrupted Core's fabrication) toward nothing.
		float rate = ThreatIncConfig.frontSuppressDaysPerDay();
		boolean suppressedAny = false;
		if (STATE_HOLDING.equals(front.state)) {
			for (Industry ind : theatre.keyStructures(market)) {
				suppressedAny |= suppress(ind, rate * elapsedDays);
			}
			suppressedAny |= suppressShield(market, rate * elapsedDays);
		} else if (STATE_GRINDING.equals(front.state)) {
			float grindRate = rate * ThreatIncConfig.frontGrindSuppressMult();
			// only the defense structures, at half rate
			for (Industry ind : theatre.defenseStructures(market)) {
				suppressedAny |= suppress(ind, grindRate * elapsedDays);
			}
			suppressedAny |= suppressShield(market, grindRate * elapsedDays);
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
					if (front.isPlayerOwned()) {
						ThreatColonyManager.announceAlways("No new orders reached the "
								+ "front on " + market.getName() + " - it resumes the "
								+ "assault on stratum " + (front.strataHeld + 1) + ".",
								Misc.getHighlightColor());
					}
				} else {
					front.stance = STANCE_ENTRENCH;
					if (front.isPlayerOwned()) {
						ThreatColonyManager.announceAlways("The front on "
								+ market.getName() + " is too weak to resume the "
								+ "assault - it entrenches on what it holds, awaiting "
								+ "reinforcement.", Misc.getNegativeHighlightColor());
					}
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

		// the colony counter-attacks on a cadence paced by its vitality (hive)
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
				ThreatColonyManager.announceAlways("The Threat force on " + market.getName()
						+ " has spent itself - out of armaments and too weak to press, "
						+ "the landing is finished.", Misc.getPositiveHighlightColor());
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
		ThreatColonyManager.announceAlways("No expedition has reached the Threat front on "
				+ market.getName() + " - it throws everything it has left at the next "
				+ theatre.layer() + ".", Misc.getHighlightColor());
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

		announceStateChange(front, market);
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
		if (isThreatOwned(front)) {
		ThreatColonyManager.announceAlways("The Threat has taken " + Theatre.of(market).layer()
				+ " " + front.strataHeld + " of " + total + " on " + market.getName()
				+ " - that much of the colony's garrison is gone with it.",
				Misc.getNegativeHighlightColor());
		} else if (front.isPlayerOwned()) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " have taken stratum " + front.strataHeld + " of " + total
					+ " and are consolidating. They request reinforcement and "
					+ "resupply - without new orders they will push on in "
					+ (int) front.consolidateDaysLeft + " days.",
					Misc.getPositiveHighlightColor());
		} else {
			ThreatColonyManager.announceAlways("Expeditionary ground forces on "
					+ market.getName() + " have taken stratum " + front.strataHeld
					+ " of " + total + ".", Misc.getPositiveHighlightColor());
		}
		ThreatIncConfig.log("Front took stratum " + front.strataHeld + "/" + total
				+ " at " + market.getName());
	}

	/**
	 * The final stratum is taken and the Fabrication Core destroyed: the
	 * colony is ERADICATED - the only way a hive dies. The vanilla teardown
	 * runs, the winner raises a free outpost over the dead world
	 * ({@code outpostOnVictory}) so the swarm cannot seed it again, and the
	 * survivors become that outpost's stockpile. pollColonies reacts next poll.
	 *
	 * <p>A THREAT front winning on a human world is the mirror image and shares
	 * none of that bookkeeping - see {@link #colonyGroundVictory}. The theatre
	 * decides which it is.
	 */
	protected static void groundVictory(GroundFront front, MarketAPI market) {
		Theatre.of(market).victory(front, market);
	}

	/** A hive's last stratum has fallen: eradication, the winner's free outpost, the survivors into it, the swarm's answer. */
	protected static void hiveGroundVictory(GroundFront front, MarketAPI market) {
		fronts().remove(front.marketId);
		ThreatColonyManager.announceAlways("The Fabrication Core of " + market.getName()
				+ " has been destroyed - the hive is eradicated. The strata are cold.",
				Misc.getPositiveHighlightColor());
		ThreatIncConfig.log("Ground victory at " + market.getName());
		String winner = ownerOf(front);
		StarSystemAPI where = market.getStarSystem();
		// held before the teardown: the market's entity and position are the
		// only handles on the world once decivilize has run
		SectorEntityToken world = market.getPrimaryEntity();
		Vector2f hyperLoc = market.getLocationInHyperspace();
		ThreatColonyManager.eradicate(market);
		// what was taken is held: the outpost is free (the fleet that won it is
		// already in orbit) and only possible now the market is gone
		ThreatOutposts.Outpost outpost = null;
		if (world != null && ThreatIncConfig.outpostsEnabled()) {
			outpost = ThreatOutposts.outpostAt(world.getId());
			if (outpost != null && !outpost.alive()) outpost = null;
			if (outpost == null && ThreatIncConfig.outpostOnVictory()) {
				outpost = ThreatOutposts.buildFree(
						Global.getSector().getFaction(winner), world);
			}
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
		ThreatColonyManager.announceAlways(market.getName() + " has fallen to the Threat "
				+ "ground assault - the colony is lost.", Misc.getNegativeHighlightColor());
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
				ThreatColonyManager.announceAlways("The swarm has seeded a hive on the ruins of "
						+ hive.getName() + ".", Misc.getNegativeHighlightColor());
				return;
			}
			// the teardown ran but no hive could be seeded: the world is already a
			// ruin flagged for the swarm's return - never decivilise it twice
			if (!market.isInEconomy() || market.isPlanetConditionMarketOnly()) return;
		}
		market.getMemoryWithoutUpdate().set(KILLED_BY_FLAG, Factions.THREAT, 60f);
		com.fs.starfarer.api.impl.campaign.intel.deciv.DecivTracker.decivilize(market, true);
	}

	/**
	 * Strength contest on the counter-attack cadence: the colony's current
	 * defense figure against the front's (entrenchment-boosted) strength.
	 * Losing costs marines and a held stratum; a beachhead beaten twice over
	 * is destroyed outright. Cadence comes from {@link #counterAttackInterval}
	 * - hive vitality on a hive world, stability and military command on a
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
			front.announcedScour = false;
			return;
		}
		front.swarmOrbitDays += elapsedDays;
		float loss = swarmBombardPer30Days(front, market) / 30f * elapsedDays;
		if (loss <= 0f) return;
		if (!front.announcedScour) {
			front.announcedScour = true;
			String who = front.isPlayerOwned() ? "your"
					: ThreatWarState.displayName(front.factionId) + "'s";
			ThreatColonyManager.announceAlways("The swarm holds the orbit over " + who
					+ " front on " + market.getName() + " unopposed and has begun bombarding it - "
					+ perDay(swarmBombardPer30Days(front, market)) + " troops a day. "
					+ "Contest the orbit and it stops.",
					front.isPlayerOwned() ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor());
		}
		ThreatMarineXP.frontLose(front, loss);
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatColonyManager.announceAlways("The ground front on " + market.getName()
					+ " has been bombarded to nothing from orbit.",
					front.isPlayerOwned() ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor());
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
		return false;
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
		return armed * rate;
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
		// The hive's health still paces it underneath; this only scales that.
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
		return armed * ThreatIncConfig.defenderCounterAttackLossFraction()
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
		String who = theatre.counterAttacker(market);
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

		java.awt.Color tone = threatFront ? Misc.getPositiveHighlightColor()
				: Misc.getNegativeHighlightColor();
		// the colony theatre's attacker already names the world
		String where = theatre == HIVE ? " on " + market.getName() : "";
	// thrown back or battered, the front's cover is spoilt (2026-09-06)
	front.entrenchDays *= Math.max(0f, Math.min(1f, ThreatIncConfig.frontEntrenchKeptFraction()));
	if (front.strataHeld > 0) {
		front.strataHeld--;
		front.pushProgress = 0f;
		// a final push does not stop for a setback - it is spent either way
		front.stance = front.finalPush ? STANCE_PUSH : STANCE_ENTRENCH;
		reapply(front.marketId);
		ThreatColonyManager.announceAlways(who + where + " has retaken a " + theatre.layer()
				+ " - " + front.strataHeld + " of " + market.getSize() + " still held by the "
				+ "invaders, " + Math.round(loss) + " of their troops lost.", tone);
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " retook a stratum ("
					+ (int) attack + " vs " + (int) defense + "): " + front.strataHeld
					+ " held, " + Math.round(loss) + " marines lost");
		} else if (odds > 2f) {
			ThreatColonyManager.announceAlways(who + where + " has overrun the beachhead "
					+ (threatFront ? "on " + market.getName() + " " : "")
					+ "- the front is destroyed.", tone);
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " overran the beachhead ("
					+ (int) attack + " vs " + (int) defense + ")");
			fronts().remove(front.marketId);
			reapply(front.marketId);
			return;
		} else {
		ThreatColonyManager.announceAlways(who + where + " has battered the beachhead "
				+ (threatFront ? "on " + market.getName() + " " : "")
				+ "- " + Math.round(loss) + " of their troops lost.", tone);
			ThreatIncConfig.log("Counter-attack at " + market.getName() + " battered the beachhead ("
					+ (int) attack + " vs " + (int) defense + "): " + Math.round(loss)
					+ " marines lost");
		}
		if (front.marines < ThreatIncConfig.frontMinMarines()) {
			ThreatColonyManager.announceAlways(threatFront
					? "The Threat landing on " + market.getName() + " has been destroyed - "
							+ "the surface is clear."
					: "The ground front on " + market.getName()
							+ " has collapsed - the surviving positions were overrun.",
					tone);
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
	 * the guns - and past the orbital floor, which stops applying the moment a
	 * front stands on the world. Kept out of {@code keyStructures} /
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
	/** Market memory: suppression days banked toward the next bombardment burst (one per whole day added). */
	public static final String SIEGE_VISUAL_KEY = "$threatinc_siegeVisualDays";
	/** Fleet memory: this fleet's Support / Defend slice has been logged today. */
	public static final String SLICE_LOG_KEY = "$threatinc_sliceLogged";
	/** Days a first slice stands for - vanilla's own gap between a fleet's passes. */
	public static final float SIEGE_FIRST_SLICE_DAYS = 3f;
	public static final float SIEGE_MAX_SLICE_DAYS = 10f;
	/** Abstract fleet points per difficulty point, for an expedition that never spawned. */
	public static final float ABSTRACT_FP_PER_POINT = 25f;

	/** Disruption days at which this world's fortifications sit at the orbital floor - as far as orbit can push them. */
	public static float siegeFloorDays(MarketAPI market) {
		float floor = Math.max(0f, Math.min(1f, ThreatIncConfig.fortificationOrbitFloor()));
		return Theatre.of(market).wearDays() * (1f - floor);
	}

	/**
	 * The disruption days the siege should read - 0 unless the structure is
	 * ACTUALLY disrupted. Vanilla stores disruption as a market-memory entry:
	 * isDisrupted() reads its value, getDisruptedDays() reads its expire. When
	 * disruption is cleared with setDisrupted(0) the value is unset but the
	 * expire keeps counting down (observed: a Swarm Nexus reading disr=394 with
	 * isDisrupted()=false, memVal=null), so a bare getDisruptedDays() reports a
	 * ghost clock. Reading it directly made the siege skip such a structure as
	 * "already past the orbital floor" forever, so bombardment could never touch
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

	/** One line of what is true now: "Defences at N% effect: C of F days to the floor." */
	public static String siegeClockLine(MarketAPI market) {
		if (market == null) return "";
		Theatre theatre = Theatre.of(market);
		List<Industry> forts = theatre.fortifications(market);
		if (forts.isEmpty()) return "No defence structure to suppress.";
		float cond = 0f;
		for (Industry ind : forts) cond += theatre.condition(market, ind);
		cond /= forts.size();
		return "Defences at " + Math.round(cond * 100f) + "% effect: " + (int) siegeClock(market)
				+ " of " + (int) siegeFloorDays(market) + " days to the floor.";
	}

	/** Whether orbit has done all it can here: every fortification is at or past the floor. */
	public static boolean suppressedToFloor(MarketAPI market) {
		if (market == null) return true;
		float days = siegeFloorDays(market) - 0.01f;
		for (Industry ind : Theatre.of(market).fortifications(market)) {
			if (siegeDisruptDays(ind) < days) return false;
		}
		return true;
	}

	/**
	 * One slice of the orbital siege (docs/ground-war.md "Sieges from orbit"):
	 * {@code fp} fleet points unopposed over a world for {@code days}. The
	 * fleet suppresses the world's fortifications by the theatre's
	 * suppression rate x fleet / (fleet + defence) disruption days per day,
	 * never past the orbital floor, and the batteries answer: the fleet loses
	 * siegeBatteryAttritionPerDay x fp x the batteries' share of the defence
	 * / (fleet + defence) per day. The same duel over a hive and over a human
	 * colony; the theatre says which structures, which clock and which
	 * batteries. The defence figure is the bombard-facing one (no cargo marines, no banked
	 * reserve): orbit fights the fixed defences, the hold test on landing
	 * ({@link #readyToLand}) counts the people too. Returns the fleet points
	 * lost; the caller takes them off a live fleet ({@link #applyFleetLosses})
	 * or off its abstract strength.
	 *
	 * <p>A disruption clock runs down a day per day, so a live fleet's slice
	 * first makes up the {@code days} the clock lost since its last one and
	 * then adds the suppression: the rate is NET progress toward the floor,
	 * and a fleet that stays holds the clock where it is. An instantaneous
	 * slice (the player's bombardment, the abstract siege) makes nothing up.
	 */
	public static float siegeSlice(float fp, MarketAPI market, float days) {
		return siegeSlice(fp, market, days, true, true);
	}

	public static float siegeSlice(float fp, MarketAPI market, float days, boolean elapsed,
			boolean reapply) {
		return siegeSlice(fp, market, days, elapsed, reapply, -1f);
	}

	/**
	 * As {@link #siegeSlice(float, MarketAPI, float, boolean, boolean)}, but with
	 * the defence figure supplied rather than read live. The player's tactical
	 * bombardment ({@link ThreatincMarketCMD#bombardConfirm}) runs this from
	 * inside vanilla's bombard transaction, where {@code getDefenderStr} reads a
	 * transiently-disrupted value (vanilla writes a 365-day disruption, our
	 * listener reapplies the stat, and only the revert at the end restores it);
	 * it passes the pre-bombard figure so the write matches the estimate and the
	 * board. A negative override reads live, as every poll-time caller does.
	 */
	public static float siegeSlice(float fp, MarketAPI market, float days, boolean elapsed,
			boolean reapply, float defenceOverride) {
		if (market == null || days <= 0f || fp <= 0f) return 0f;
		Theatre theatre = Theatre.of(market);
		float weighted = fp * Math.max(0f, ThreatIncConfig.siegeFPWeight());
		float defence = defenceOverride >= 0f ? defenceOverride
				: Math.max(0f, MarketCMD.getDefenderStr(market, true));
		// the batteries answer with what they had when the slice began
		float share = theatre.batteryShare(market);
		float ratio = weighted / Math.max(1f, weighted + defence);
		float add = theatre.suppressDaysPerDay() * ratio * days;
		float cap = siegeFloorDays(market);
		if (theatre.carriesSiegeState()) {
			market.getMemoryWithoutUpdate().set(BESIEGED_FLAG, true, theatre.wearDays());
		}
		// what the planetary shield still turns aside, read before this slice
		// writes anything so every structure under it takes the same cut
		float through = ThreatShield.throughput(market);
		boolean touched = false;
		for (Industry ind : theatre.fortifications(market)) {
			float cur = siegeDisruptDays(ind);
			if (cur >= cap || add <= 0f) continue;
			// what the clock ran down since the last slice, made up first
			float restore = elapsed && cur > 0f ? days : 0f;
			ind.setDisrupted(Math.min(cap, cur + restore + add * through));
			touched = true;
		}
		// the shield stands in the open: it has no cover of its own and takes
		// the pass at full weight, which is what spends it
		if (ThreatShield.soak(market, add, elapsed ? days : 0f, cap)) touched = true;
		float loss = fp * ThreatIncConfig.siegeBatteryAttritionPerDay() * days
				* (defence * share) / Math.max(1f, weighted + defence);
		// DEBUG: one line per slice - fp against the defence figure, the ratio it
		// buys, the days added and the fleet points lost. Remove once the siege
		// balance is nailed down.
		ThreatIncConfig.log("siegeSlice " + market.getName() + " [" + (theatre == HIVE ? "hive" : "colony")
				+ "] fp=" + String.format("%.0f", fp) + " x wt=" + ThreatIncConfig.siegeFPWeight()
				+ " weighted=" + String.format("%.0f", weighted) + " defence=" + String.format("%.0f", defence)
				+ " -> ratio=" + String.format("%.3f", ratio)
				+ " suppress/day=" + theatre.suppressDaysPerDay() + " days=" + String.format("%.2f", days)
				+ " add=" + String.format("%.2f", add) + " cap=" + String.format("%.1f", cap)
				+ " shieldThrough=" + String.format("%.2f", through)
				+ " loss=" + String.format("%.2f", loss));
		if (reapply) {
			boolean synced = syncSiegeState(market);
			if (touched || synced) market.reapplyIndustries();
			// vanilla's own bombardment burst on the planet, for a player in
			// the system - once per whole day of suppression added
			if (touched && market.getPrimaryEntity() != null) {
				// one burst per whole day of suppression added, however many
				// fleets fire: a slice that adds a tenth of a day is not a bombardment
				float banked = market.getMemoryWithoutUpdate().getFloat(SIEGE_VISUAL_KEY) + add;
				if (banked >= 1f) {
					banked = 0f;
					MarketCMD.addBombardVisual(market.getPrimaryEntity());
				}
				market.getMemoryWithoutUpdate().set(SIEGE_VISUAL_KEY, banked, 10f);
			}
		}
		return loss;
	}

	/** What one slice would do, for a prompt: {suppression days added under the shield, fleet points the batteries take, days onto the shield}. */
	public static float[] siegeSliceEstimate(float fp, MarketAPI market, float days) {
		if (market == null || days <= 0f || fp <= 0f) return new float[] { 0f, 0f };
		Theatre theatre = Theatre.of(market);
		float weighted = fp * Math.max(0f, ThreatIncConfig.siegeFPWeight());
		float defence = Math.max(0f, MarketCMD.getDefenderStr(market, true));
		float share = theatre.batteryShare(market);
		float ratio = weighted / Math.max(1f, weighted + defence);
		float add = theatre.suppressDaysPerDay() * ratio * days;
		float loss = fp * ThreatIncConfig.siegeBatteryAttritionPerDay() * days
				* (defence * share) / Math.max(1f, weighted + defence);
		// [0] is what reaches the fortifications - already cut by the shield, so
		// every caller quoting "how much does this suppress" gets the true figure;
		// [2] is what the pass puts on the shield itself
		return new float[] { add * ThreatShield.throughput(market), loss,
				add * Math.max(0f, ThreatIncConfig.shieldSoakMult()) };
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
	 * An expedition that never spawned (vanilla autoresolve) runs its whole
	 * siege of a world in one go: the same slices against the same batteries,
	 * {@code start} abstract fleet points standing in for the fleets, up to
	 * siegeOrbitDays or the group's abort fraction, or until {@code troops}
	 * could land. The disruption it writes is real. Returns the points left.
	 */
	public static float abstractSiege(MarketAPI market, float start, float troops,
			float abortFraction) {
		if (market == null || start <= 0f) return start;
		float fp = start;
		float elapsed = 0f;
		float step = SIEGE_FIRST_SLICE_DAYS;
		float budget = ThreatIncConfig.siegeOrbitDays();
		while (fp > 0f && elapsed < budget && fp > start * abortFraction) {
			if (readyToLand(market, troops)) break;
			// one frame: nothing runs down between steps, one reapply at the end
			fp -= siegeSlice(fp, market, step, false, false);
			elapsed += step;
		}
		syncSiegeState(market);
		market.reapplyIndustries();
		ThreatIncConfig.log("Abstract siege of " + market.getName() + ": " + (int) elapsed
				+ " d, " + (int) start + " -> " + (int) fp + " FP");
		return fp;
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
		List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
		Collections.sort(members, new Comparator<FleetMemberAPI>() {
			public int compare(FleetMemberAPI a, FleetMemberAPI b) {
				return Float.compare(a.getFleetPointCost(), b.getFleetPointCost());
			}
		});
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

	// ------------------------------------------------------------------
	// FABRICATING TROOPS FROM THE FLEET (2026-09-08, the user): a Defend
	// station whose front cannot hold bombards - until the bombardment has
	// nothing left to reach. At the floor the guns are as quiet as orbit can
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
	 * to the floor. It is a PRICE, never the size of the commitment: a world
	 * with no batteries charges nothing and its front is fed for free, which is
	 * right and is why the two figures are not the same number.
	 */
	public static float fabricateCost(float fp, MarketAPI market, float days) {
		if (market == null || fp <= 0f || days <= 0f) return 0f;
		Theatre theatre = Theatre.of(market);
		float weighted = fp * Math.max(0f, ThreatIncConfig.siegeFPWeight());
		float defence = Math.max(0f, MarketCMD.getDefenderStr(market, true));
		float share = theatre.intactBatteryShare(market);
		return fp * ThreatIncConfig.siegeBatteryAttritionPerDay() * days
				* (defence * share) / Math.max(1f, weighted + defence);
	}

	/**
	 * Whether a DEFEND fleet of the faction fabricates troops now - the exact
	 * complement of {@link #defendBombards}: its own front on the ground
	 * cannot hold, and orbit has run out of things to suppress. Both are false
	 * while the front holds, so the moment the ground is safe the fleet stops
	 * cutting itself up (the user, 2026-09-08).
	 */
	public static boolean defendFabricates(String factionId, MarketAPI market) {
		if (!ThreatIncConfig.fabricateEnabled()) return false;
		if (factionId == null || market == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return false;
		if (frontCanHold(front, market)) return false;
		return suppressedToFloor(market);
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
		float want = holdRequirement(market) * Math.max(1f, ThreatIncConfig.fabricateHoldMargin());
		float mult = Math.max(0.01f, entrenchMult(front));
		float gap = want - front.marines * mult;
		if (gap > 0f) return gap / mult;
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
		if (fleet == null || market == null || factionId == null || days <= 0f) return 0f;
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
				announceFabrication(market, factionId, troops);
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
					+ (int) fleet.getFleetPoints() + " FP fabricates - the defences are at the floor and "
					+ "the front is " + (int) (holdRequirement(market) - effectiveStrength(front))
					+ " short of holding; " + String.format("%.1f", wanted) + " FP of hulls wanted, "
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

	/** One sentence when fragments reach the ground, in the owner's voice; the log carries the rest. */
	protected static void announceFabrication(MarketAPI market, String factionId, int troops) {
		if (Factions.THREAT.equals(factionId)) {
			ThreatColonyManager.announceAlways("The swarm over " + market.getName()
					+ " is breaking up its own ships - " + Misc.getWithDGS(troops)
					+ " more of them have come down on the assault.",
					Misc.getNegativeHighlightColor());
		} else if (Factions.PLAYER.equals(factionId)) {
			ThreatColonyManager.announceAlways("Your fleet over " + market.getName()
					+ " has stripped its hulls for the ground - " + Misc.getWithDGS(troops)
					+ " more into the fight.", Misc.getHighlightColor());
		}
	}

	/**
	 * Whether a Defend fleet over this world is committed to the ground and
	 * must NOT stand down on strength (the user, 2026-09-08: it never gives up
	 * because of this) - it can fabricate now, or it already has and the front
	 * it fed still stands. It leaves when the front does, won or lost.
	 */
	public static boolean defendCommitted(CampaignFleetAPI fleet, String factionId, String marketId) {
		if (!ThreatIncConfig.fabricateEnabled() || fleet == null || marketId == null) return false;
		MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
		if (market == null) return false;
		if (defendFabricates(factionId, market)) return true;
		GroundFront front = getFront(marketId);
		return front != null && factionId != null && factionId.equals(ownerOf(front))
				&& fleet.getMemoryWithoutUpdate().getBoolean(FABRICATE_FLAG_KEY);
	}

	/**
	 * Whether an expedition should land on the world now rather than keep
	 * suppressing it from orbit: the fortifications are at the floor (orbit
	 * has done all it can), or the troops could hold as they are. The second
	 * branch is the commander's trade (the user, 2026-09-06, after a round
	 * trip through "always soften first"): every day in orbit costs ships to
	 * the batteries, so a force that can already hold spends marines rather
	 * than hulls - and the Support button is there when that call is wrong.
	 */
	public static boolean readyToLand(MarketAPI market, float troops) {
		if (market == null) return false;
		if (suppressedToFloor(market)) return true;
		return troops * ThreatIncConfig.frontLandingMult() >= holdRequirement(market);
	}

	/**
	 * The expedition's status line over a world it has not landed on, saying
	 * WHY it is landing when it is (the user, 2026-09-06: the early landing
	 * was the part nobody realised): {@code besieging} while orbit still has
	 * work and the troops could not hold; otherwise which branch opened the
	 * gate.
	 */
	public static String landingPhase(MarketAPI market, float troops, String besieging) {
		if (market == null) return besieging;
		if (suppressedToFloor(market)) return "moving to land - defences at the floor";
		if (readyToLand(market, troops)) return "moving to land - the troops can hold, sparing the ships";
		return besieging;
	}

	/** Whether the front can hold as it stands: its effective strength against what holding here needs. */
	public static boolean frontCanHold(GroundFront front, MarketAPI market) {
		return front != null && market != null && effectiveStrength(front) >= holdRequirement(market);
	}

	/**
	 * Whether a DEFEND fleet of the faction bombards this world now
	 * (2026-09-07): only while the faction's own front on the ground cannot
	 * hold, and while orbit can still push the fortifications - at the floor
	 * a slice buys nothing and the batteries still answer. Never with no
	 * front, or with one that holds: keeping the ships is the point of
	 * Defend over Support.
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

	public static boolean defendBombards(String factionId, MarketAPI market) {
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
		return !suppressedToFloor(market);
	}

	/**
	 * SUPPORT sorties besiege too (2026-09-06): every fleet under a Support
	 * order that is on station over a hostile world whose orbit nobody holds
	 * against it delivers a slice of the siege each poll with its live fleet
	 * points, and the batteries answer it like any besieging fleet. A front
	 * on the ground does not stop it - orbit still cannot push a fortification
	 * past the floor, but it keeps it there while the front does the rest.
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
			if (defend && !defendBombards(o.factionId, market)) {
				// bombardment has nothing left to reach and the front still
				// cannot hold: the fleet goes down instead of firing
				if (defendFabricates(o.factionId, market)) {
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
		if (!ThreatIncConfig.fabricateEnabled()) return "the defences are at the floor";
		return "the defences are at the floor, and it has nothing left to send down";
	}

	/**
	 * One supporting fleet's slice for the poll: on station over a hostile
	 * world whose orbit nothing holds against its faction, its live points
	 * besiege and the batteries answer. Nothing while the orbit is contested -
	 * it fights for it first. Returns the points the batteries took.
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
				float[] est = siegeSliceEstimate(fp, market, 1f);
				ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName() + " at "
						+ (int) fp + " FP suppresses ~" + String.format("%.1f", est[0])
						+ " d/day, batteries ~" + String.format("%.1f", est[1]) + " FP/day; clock "
						+ (int) siegeClock(market) + " of " + (int) siegeFloorDays(market) + " d");
			}
		}
		if (contested) return 0f;
		float loss = siegeSlice(fp, market, days);
		float removed = applyFleetLosses(fleet, loss);
		if (removed > 0f) {
			ThreatIncConfig.log(label + " over " + market.getName() + ": " + fleet.getName()
					+ " lost " + (int) removed + " FP to the batteries");
		}
		return removed;
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
		return !frontCanHold(front, market);
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

	protected static void announceStateChange(GroundFront front, MarketAPI market) {
		if (front.state.equals(front.announcedState)) return;
		String was = front.announcedState;
		front.announcedState = front.state;
		// no message for the very first classification unless it's a hold -
		// the deploy dialog already told the player where they stand
		if (was == null && !STATE_HOLDING.equals(front.state)) return;
		if (!front.isPlayerOwned()) return; // NPC campaigns message on strata only
		if (STATE_HOLDING.equals(front.state)) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " are holding - the hive's organs are suppressed and a push "
					+ "on the next stratum is possible.", Misc.getPositiveHighlightColor());
		} else if (STATE_GRINDING.equals(front.state)) {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " have been pushed back to grinding the outer defenses - "
					+ "too weak to hold the deep organs.", Misc.getHighlightColor());
		} else {
			ThreatColonyManager.announceAlways("Ground forces on " + market.getName()
					+ " are reduced to a foothold - too weak to suppress anything.",
					Misc.getNegativeHighlightColor());
		}
	}

	/**
	 * The colony died under the front. There is no market left to dock with,
	 * and stranding the survivors punishes winning.
	 *
	 * <p>When an outpost stands over the dead world - the ground victory's own
	 * prize, or one that was already there - the survivors simply stay: player
	 * and NPC alike, they are the station's garrison stockpile, ready for the
	 * next front run out of it ({@link ThreatConvoys}). With no outpost they
	 * are lifted off by their own support elements and returned to the player
	 * fleet, or - for an NPC front - banked in the reserve of the faction's
	 * nearest base in reach.
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
			ThreatIncConfig.log("Threat front dispersed at " + front.marketId + ": "
					+ marines + " marines, " + armaments + " armaments lost");
			return;
		}
		if (outpost != null) {
			ThreatOutposts.deposit(outpost, Commodities.MARINES, marines);
			ThreatOutposts.deposit(outpost, Commodities.HAND_WEAPONS, armaments);
			if (front.isPlayerOwned()) {
				ThreatColonyManager.announceAlways("The ground forces hold what they took - "
						+ marines + " marines and " + armaments + " heavy armaments are now "
						+ "the stockpile of the outpost over " + outpost.planetName() + ".",
						Misc.getPositiveHighlightColor());
			}
			ThreatIncConfig.log("Front survivors garrison the outpost over "
					+ outpost.planetName() + ": " + marines + " marines, " + armaments
					+ " armaments");
			return;
		}
		if (!front.isPlayerOwned()) {
			com.fs.starfarer.api.campaign.FactionAPI faction =
					Global.getSector().getFaction(front.factionId);
			MarketAPI base = null;
			if (faction != null && hyperLoc != null) {
				base = ThreatFleetOrders.pickBase(faction, hyperLoc);
			}
			if (base == null && faction != null) {
				// the world is already gone from the map: any base of theirs will do
				for (MarketAPI m : ThreatReserves.marketsOf(faction.getId())) {
					if (IncursionManager.isBase(m)) {
						base = m;
						break;
					}
				}
			}
			if (base == null) {
				ThreatIncConfig.log("Front survivors stranded (no base in reach): " + front.marketId);
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
		ThreatColonyManager.announceAlways("The ground forces have lifted off - "
				+ marines + " " + ThreatMarineXP.rankName(level).toLowerCase()
				+ " marines rejoin the fleet.",
				Misc.getPositiveHighlightColor());
		ThreatIncConfig.log("Front evacuated: " + front.marketId);
	}
}
