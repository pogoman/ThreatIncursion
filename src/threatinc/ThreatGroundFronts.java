package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
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
	 * The checkpoint after each stratum falls: the front consolidates and waits
	 * frontCheckpointDays for orders, then takes the doctrine's stance. The window
	 * is 0 by default since 2026-10-27 (the user, Haven: a flat pause every
	 * district for every side bought nothing), so this stance is only seen with
	 * the knob set; a district taken then resolves the stance at once.
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
		/** The world's size when the front landed (0 on fronts saved before 2026-10-11): what a hive has starved away since reads against it. */
		public int sizeAtLanding;
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
		/** The colony's defenders are fighting with no heavy armaments in stock - logged once a transition (burnDefenderArms, 2026-10-18). */
		public boolean defendersDry;
		/** Peak marine strength (landing plus reinforcements) - what supply runs reinforce toward. */
		public float marinesLanded;
		/** The front has asked to be pulled out (NPC stance AI, or the board's Pull out order). */
		public boolean withdrawRequested;
		/**
		 * Days the swarm has held the orbit over this (enemy) front unopposed -
		 * how long it bombarded it until v3 (2026-10-27: the swarm never bombards). Was the
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
		/**
		 * The colony defenders' stance against this army (COLONY theatre only;
		 * user, 2026-10-17: "the same actions should be available regardless of
		 * if you attack or defend"): {@link #STANCE_PUSH} counter-attacks on the
		 * cadence, {@link #STANCE_ENTRENCH} holds fire and digs in. Null on
		 * older saves, which reads as push - what every colony did before.
		 */
		public String defence;
		/** Days the defenders have held fire and dug in: their cover ({@link #defenderCoverMult}), lost with a district. */
		public float defenderEntrenchDays;
		/** THE OCCUPATION (2026-10-27, ThreatOccupation): the industries the swarm's front has claimed, in claim order. */
		public List<String> claims;
		/** Days on the clock toward the next claim. */
		public float claimDays;
		/** Output units a day of the claimed industries, as the ground war condition last read them. */
		public float eatenUnits;
		/** THE HIVE POOL (docs/occupation.md 6): the strata the occupation holds in the ground, fed at a hive's rate. */
		public float budPool;
		/** Strata of the pool rising into a swarm; it spawns whole when they hold the want. */
		public float budRising;
		/** Days the orbit has been quiet: past defendLingerDays the swarms above are absorbed into the pool. */
		public float orbitQuietDays;
		/** The World Occupied notice has been sent. */
		public boolean occupiedAnnounced;
		/** CONSUMPTION (the user, 2026-10-28, docs/occupation.md 7): days each claim has been consumed, by industry id; the oldest claims, as many as districts held, advance (ThreatOccupation.active). Null on older saves. */
		public Map<String, Float> consumeDays;

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
			// nothing for them and the flag stays true for continuity. THE
			// STRATA ARE THE TROOPS (the user, 2026-10-24): the figure is the
			// strata standing (SwarmNexus tops vanilla's base up to them), and
			// what its swarms break up over it lands as strata (breakUpForHive)
			return MarketCMD.getDefenderStr(market, true);
		}
		public float counterAttackStrength(MarketAPI market) {
			// no marines to hold back: the hive throws its whole body at the
			// beachhead, which is what it has always done
			return defenderStrength(market);
		}
		public List<Industry> keyStructures(MarketAPI market) {
			// phase D: nothing stands on a hive but its strata, and the Core cannot be disrupted
			return new ArrayList<Industry>();
		}
		public List<Industry> defenseStructures(MarketAPI market) {
			return new ArrayList<Industry>(); // retired with the military layer (phase D)
		}
		public List<Industry> seizedStructures(MarketAPI market) {
			return new ArrayList<Industry>(); // a hive's strata strip fabrication, not industries
		}
		public String layer() {
			return "stratum";
		}
		public List<Industry> fortifications(MarketAPI market) {
			return new ArrayList<Industry>(); // orbit razes strata here, it suppresses nothing (hiveRaze)
		}
		public float wearDays() {
			return Math.max(1f, ThreatIncConfig.defenseWearDays());
		}
		public float suppressDaysPerDay() {
			return 0f; // phase D: a hive has nothing to suppress - bombardment razes strata (hiveRaze)
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
	 * ({@link #syncSiegeState}); its counter-attacks pace on a military command
	 * and the force ratio, never stability (user, 2026-10-17), and stop while
	 * its defenders are dug in; its orbit is held by its station and any armed fleet
	 * that would fight the invader; its fall is decivilisation in the swarm's
	 * name.
	 */
	public static final Theatre COLONY = new Theatre() {
		public float defenderStrength(MarketAPI market) {
			// dug-in defenders fight from cover they have earned (2026-10-17);
			// over-extended ones are spread thin (2026-10-24)
			GroundFront front = getFront(market.getId());
			return (mannedGarrison(market) + colonyMarineDefense(market, 1f))
					* defenderCoverMult(front) * defenderOverExtendMult(front);
		}
		public float counterAttackStrength(MarketAPI market) {
			// the garrison mounts the counter-attack; the stockpile's marines
			// mostly hold the line they are standing on
			return (mannedGarrison(market)
					+ colonyMarineDefense(market, ThreatIncConfig.marineCounterAttackMult()))
					* defenderOverExtendMult(getFront(market.getId()));
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
			return 365f; // v3: vanilla disrupts for a year; nothing of ours wears a colony's structures
		}
		public float suppressDaysPerDay() {
			return 0f; // v3: nothing of ours suppresses a colony's structures
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
		// colony bombarded but not yet landed on carries it as well - and keeps it
		// for as long as any defence structure is disrupted, siege or no siege (the
		// user, Sindria 2026-10-24: the front gone, the carrier went with it and
		// vanilla stripped 112-day structures whole, 400 x 0.48 = 190)
		boolean wantsIndustry = colony && (front
				|| ThreatSiegeMalus.anyDisrupted(market)
				|| ThreatRazing.saturated(market));
		boolean wantsCondition = front;
		// the occupation's visible card, while the swarm has claimed anything (2026-10-27)
		boolean wantsCard = front && ThreatOccupation.showsCard(market);
		boolean hasIndustry = market.hasIndustry(ThreatSiegeMalus.ID);
		boolean hasCondition = market.hasCondition(ThreatGroundWarCondition.ID);
		boolean hasCard = market.hasIndustry(ThreatInfestation.ID);
		if (wantsIndustry == hasIndustry && wantsCondition == hasCondition && wantsCard == hasCard) return false;
		if (wantsIndustry && !hasIndustry) market.addIndustry(ThreatSiegeMalus.ID);
		if (!wantsIndustry && hasIndustry) market.removeIndustry(ThreatSiegeMalus.ID, null, false);
		if (wantsCard && !hasCard) market.addIndustry(ThreatInfestation.ID);
		if (!wantsCard && hasCard) market.removeIndustry(ThreatInfestation.ID, null, false);
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
		front.sizeAtLanding = market.getSize();
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
		// what the colony's defenders fight with (defenderArmsEnabled; the burn's effect is logged by burnDefenderArms)
		if (isThreatOwned(front) && !isHiveTarget(market) && ThreatIncConfig.defenderArmsEnabled()) {
			float arms = ThreatReserves.stock(market.getId(), Commodities.HAND_WEAPONS);
			float burn = defenderDailyUpkeep(market, front);
			ThreatIncConfig.log("Defenders of " + market.getName() + ": " + (int) ThreatReserves.armedMarines(market)
					+ " armed marines, " + (int) arms + " armaments, burn " + (int) Math.ceil(burn) + " a day ("
					+ (burn > 0f ? (int) (arms / burn) + " days" : "no burn") + ")");
		}
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
			// the entrenchment is the front's, not the men's: it is wiped only by a lost
			// district (the user, 2026-10-24); before, a reinforcement diluted it (2026-09-08)
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
		orderPush(front);
		return true;
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
		float r = lineRate(front, market, true, defendersPush(front, market));
		if (r <= 0f || !lineMoves(r)) return -1f;
		float base = Math.max(0f, ThreatIncConfig.frontLineBaseDays() - front.pushProgress);
		return base / r;
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
		front.pushProgress = Math.min(0f, front.pushProgress); // ground being lost stays lost
		front.consolidateDaysLeft = 0f;
	}

	/** Breaks off the assault (or answers a checkpoint) and digs in. */
	public static void orderEntrench(GroundFront front) {
		if (front == null || STANCE_ENTRENCH.equals(front.stance)) return;
		front.stance = STANCE_ENTRENCH;
		front.pushProgress = Math.min(0f, front.pushProgress);
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
		if (standing != null && !ownerOf(standing).equals(ownerFactionId)) return null;
		// the guns take their toll on the way down (dropLoss, 2026-10-18): what lands is what is left
		troops = dropped(market, troops, ownerFactionId);
		if (standing != null) {
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
	 * penalties. The assault casualty multiplier that replaced that pressure went
	 * with THE ATTRITION RULE (2026-10-24): attacking costs the cover instead.
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
	 * MANNED DEFENCES (the user, 2026-10-28: "what if we made [ground defences] a loss modifier
	 * for the stationed marines ... 50 marines with enough ground defences could hold 1000s at
	 * bay, but once the last falls ground defence score has no impact and battle is lost"): the
	 * garrison counts only as far as the armed marines on the world man it,
	 * groundDefencePerMarine (20) points a marine - Haven's 93 man all of its 1,674, 50 would man
	 * 1,000, none man nothing, and with the last marine gone the landing takes a district every
	 * frontLineBaseDays. The marines' losses need no rule of their own: the attrition rule bills
	 * them on enemy force over their own, and the defences they man are in that figure. Knob 0 =
	 * every point counts, manned or not (the rule before).
	 */
	public static float mannedGarrison(float garrison, float armed) {
		float per = ThreatIncConfig.groundDefencePerMarine();
		if (per <= 0f) return garrison;
		return Math.min(garrison, Math.max(0f, armed) * per);
	}

	/** The colony's garrison as its armed marines man it now ({@link #mannedGarrison(float, float)}). */
	public static float mannedGarrison(MarketAPI market) {
		if (market == null) return 0f;
		return mannedGarrison(colonyGarrison(market), ThreatReserves.armedMarines(market));
	}

	/** Marines that man the colony's whole garrison - the standing stock an NPC colony keeps (ThreatReserves.floor); 0 with the knob off or on a hive. */
	public static float manningNeed(MarketAPI market) {
		if (market == null || isHiveTarget(market)) return 0f;
		float per = ThreatIncConfig.groundDefencePerMarine();
		if (per <= 0f) return 0f;
		return (float) Math.ceil(colonyGarrison(market) / per);
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
		// marines with no heavy armaments to fight with fight as a dry front does (2026-10-17)
		if (defendersDry(market)) mult *= ThreatIncConfig.frontDryEffectivenessMult();
		return armed * mult * ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(market)) * weight;
	}

	/**
	 * Whether a colony's armed marines are fighting an army on its surface with no heavy
	 * armaments in its stockpile (knob {@code defenderArmsEnabled}; user, 2026-10-17: "i dont
	 * like how marines defending human worlds dont consume armaments"). A hive's troops never
	 * are: the swarm fights on bodies, not armaments ({@link #needsArms}).
	 */
	public static boolean defendersDry(MarketAPI market) {
		if (market == null || !ThreatIncConfig.defenderArmsEnabled() || isHiveTarget(market)) return false;
		if (!hasFront(market)) return false;
		return ThreatReserves.stock(market.getId(), Commodities.HAND_WEAPONS) <= 0f;
	}

	/**
	 * Heavy armaments a colony's defenders burn per day against a front: the front rate
	 * ({@link #dailyUpkeep}) on every armed marine - the whole garrison is called up and in
	 * the field, as the whole of a landed army burns whatever it faces (the bleed's frontage
	 * rule, {@link #engaged}, is about who can be killed, not who is eating ammunition;
	 * 2026-10-17 evening, after Arkoman's 750 marines read 1.1 a day), x frontPushUpkeepMult
	 * while they push, as a landing pays (the user, Sindria 2026-10-24: "marines are pushing
	 * but their arms consumption is the same as when not pushing"). 0 on a hive, or with
	 * the knob off.
	 */
	public static float defenderDailyUpkeep(MarketAPI market, GroundFront front) {
		if (market == null || front == null || !ThreatIncConfig.defenderArmsEnabled() || isHiveTarget(market)) return 0f;
		float armed = ThreatReserves.armedMarines(market);
		if (armed <= 0f) return 0f;
		float upkeep = dailyUpkeep(armed);
		if (defendersPush(front, market)) upkeep *= ThreatIncConfig.frontPushUpkeepMult();
		return upkeep;
	}

	/** Days the colony's heavy armaments last its defenders at the current burn (999+ = ample, or none burnt). */
	public static float defenderArmsDaysLeft(MarketAPI market, GroundFront front) {
		float upkeep = defenderDailyUpkeep(market, front);
		if (upkeep <= 0f) return 999f;
		return ThreatReserves.stock(market.getId(), Commodities.HAND_WEAPONS) / upkeep;
	}

	/** One tick of the defenders' burn, out of the colony's stockpile (a player colony's resource stockpile). */
	protected static void burnDefenderArms(MarketAPI market, GroundFront front, float elapsedDays) {
		if (elapsedDays <= 0f) return;
		float burn = defenderDailyUpkeep(market, front) * elapsedDays;
		if (burn <= 0f) return;
		ThreatReserves.draw(market.getId(), Commodities.HAND_WEAPONS, burn);
		// the effect, logged once a transition: dry (fighting at frontDryEffectivenessMult), and rearmed
		float left = ThreatReserves.stock(market.getId(), Commodities.HAND_WEAPONS);
		float perDay = burn / elapsedDays;
		if (left <= 0f && !front.defendersDry) {
			front.defendersDry = true;
			ThreatIncConfig.log("Defenders of " + market.getName() + " dry: " + (int) ThreatReserves.armedMarines(market)
					+ " armed marines fight at x" + ThreatIncConfig.frontDryEffectivenessMult()
					+ " until armaments arrive (burn " + (int) Math.ceil(perDay) + " a day)");
		} else if (left > 0f && front.defendersDry) {
			front.defendersDry = false;
			ThreatIncConfig.log("Defenders of " + market.getName() + " rearmed: " + (int) left + " armaments, "
					+ (int) (left / Math.max(0.01f, perDay)) + " days at the burn");
		}
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

	// ------------------------------------------------------------------
	// THE LINE (the user, 2026-10-23, docs/front-line-proposal.md 7: "do away
	// with counter attack which is a bit random and not reflecting real time
	// status push and pull"). One quantity per front moves every tick by the
	// net of the two sides' pressure; a district changes hands when it reaches
	// frontLineBaseDays either way. The counter-attack cadence, the brace and
	// the hold / grind fractions went with it. Sindria, the save that decided
	// it: 255 marines against 212 troops, seven districts lost in 283 days
	// because the swarm's push was continuous and the marines' answer was one
	// blow every 20 days that never landed before the next district fell.
	// ------------------------------------------------------------------

	/** Whether the world's defenders push the line: a colony's marines unless dug in (their stance), a hive always (paced by its supplies, {@link #defenderPressure}). */
	public static boolean defendersPush(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		if (isHiveTarget(market)) return true;
		return !STANCE_ENTRENCH.equals(front.defence);
	}

	/**
	 * What the world pushes the line with: the counter-attack figure (the
	 * garrison whole, the marines at marineCounterAttackMult) - the force figure
	 * the board shows, nothing invisible on top (the user, Sindria 2026-10-24:
	 * "the force number should represent the final force value"; the military
	 * command x1.5 went that afternoon) - or a hive's times the supplies it is
	 * paid (hiveCounterAttackPace: starve it and it cannot push).
	 */
	public static float defenderPressure(GroundFront front, MarketAPI market) {
		if (market == null) return 0f;
		float p = counterAttackStrength(market);
		if (isHiveTarget(market)) return p * hiveCounterAttackPace(market);
		return p;
	}

	/** A line rate as a doctrine weighs it: 0 inside frontGrindBand (the line stands), itself outside. */
	protected static float standing(float rate) {
		return lineMoves(rate) ? rate : 0f;
	}

	/**
	 * The line as a colony's marines dug in would see it against the swarm's best
	 * reply ({@link #doctrineDefendersPush}): positive and moving means dug in
	 * they lose ground, which is why the doctrine has them push while the line stands.
	 */
	public static float defenderDigInRate(GroundFront front, MarketAPI market) {
		if (front == null || market == null || isHiveTarget(market)) return 0f;
		if (front.isPlayerOwned()) return standing(lineRate(front, market, STANCE_PUSH.equals(front.stance), false));
		return Math.max(standing(lineRate(front, market, true, false)),
				standing(lineRate(front, market, false, false)));
	}

	/** The front's cover as it would stand dug in, whatever its stance now - coverMult without the stance test. */
	protected static float coverIfDugIn(GroundFront front) {
		if (front == null) return 1f;
		return 1f + (ThreatIncConfig.frontEntrenchDefenseBonus() - 1f) * entrenchFraction(front);
	}

	/** The rate the line moves at as both sides stand now: {@link #lineRate(GroundFront, MarketAPI, boolean, boolean)} at their stances. */
	public static float lineRate(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		return lineRate(front, market, STANCE_PUSH.equals(front.stance), defendersPush(front, market));
	}

	/**
	 * The rate the line moves at, -1..1, positive toward the landing: each
	 * side that pushes gains what its covered strength exceeds the other's
	 * by, and the difference is read against the two strengths together.
	 * <pre>
	 * gainA = landingPushes ? max(0, A x coverA - D x coverD) : 0
	 * gainD = worldPushes   ? max(0, D' x coverD - A x coverA) : 0
	 * rate  = (gainA - gainD) / (A + D)
	 * </pre>
	 * Both sides fight at their entrenchment's cover whatever they do (the
	 * user, Volturn 2026-10-24, "Pushers keep their cover": until then a
	 * pushing side fought at cover 1 against the other's full cover, and 819
	 * more marines were asked to push back 603 troops). At the stances given,
	 * not the current ones, so a prompt can quote what an order would do.
	 */
	public static float lineRate(GroundFront front, MarketAPI market, boolean landingPushes, boolean worldPushes) {
		if (front == null || market == null) return 0f;
		float a = effectiveStrength(front);
		float d;
		float coverD;
		if (isHiveTarget(market)) {
			d = defenderStrength(market);
			coverD = 1f;
		} else {
			d = (mannedGarrison(market) + colonyMarineDefense(market, 1f)) * defenderOverExtendMult(front);
			coverD = defenderCoverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
		}
		float coverA = coverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
		return lineRateOf(a, coverA, d, coverD, defenderPressure(front, market), landingPushes, worldPushes);
	}

	/**
	 * The line's arithmetic on bare figures ({@link #lineRate(GroundFront, MarketAPI, boolean, boolean)}):
	 * the landing a in coverA, the world d in coverD pushing with pressure. THE SHARE RULE (the
	 * user, Arkoman 2026-10-28: "pushing as inferior force should still gain ground just less ...
	 * proportionally less given the disadvantage in numbers ... both sides push is just one
	 * subtracted from the other"): a pushing side gains its covered force times its share of the
	 * field, to frontPushShareExponent k.
	 * <pre>
	 * a' = a x coverA, d' = d x coverD, p' = pressure x coverD
	 * gainA = landingPushes ? a' x (a' / (a' + d'))^k : 0
	 * gainD = worldPushes   ? p' x (p' / (p' + a'))^k : 0
	 * rate  = (gainA - gainD) / (a + d)
	 * </pre>
	 * At k 1 with both pushing and p' = d' this is (a'^2 - d'^2) / (a' + d') = a' - d', the rule
	 * of 2026-10-23 unchanged (a hive always pushes, so every hive fight and its sizing stand). A
	 * lone pusher gains a'^2 / (a' + d') where before it gained max(0, a' - d'): an inferior force
	 * takes ground slowly and the attrition rule bills it (equal forces, one pushing: a district in
	 * 24 d; a half-strength pusher 54 d and dead in 33). A dug-in side never holds a push by cover
	 * alone - cover slows it and cuts the losses; only a push back cancels it.
	 */
	public static float lineRateOf(float a, float coverA, float d, float coverD, float pressure,
			boolean landingPushes, boolean worldPushes) {
		float k = Math.max(0f, ThreatIncConfig.frontPushShareExponent());
		float aC = Math.max(0f, a * coverA);
		float dC = Math.max(0f, d * coverD);
		float pC = Math.max(0f, pressure * coverD);
		float gainA = landingPushes && aC > 0f ? aC * (float) Math.pow(aC / (aC + dC), k) : 0f;
		float gainD = worldPushes && pC > 0f ? pC * (float) Math.pow(pC / (pC + aC), k) : 0f;
		float rate = (gainA - gainD) / Math.max(1f, a + d);
		return Math.max(-1f, Math.min(1f, rate));
	}

	/**
	 * PRESSURE BREAKS COVER (the user, Arkoman 2026-10-28: "a dug in force should be breakeable
	 * under enough pressure"): a side's entrench days after a tick. Pushing spends them as before
	 * (past zero the push is over-extended). Dug in under an enemy push they are STRIPPED at the
	 * force ratio a day ({@link #coverPressure}: the pusher's covered push figure over this side's
	 * covered holding figure, x frontCoverStripMult) instead of growing, to 0 and no further -
	 * broken cover is open ground, not over-extension; an over-extended side under a push still
	 * recovers to 0 at a day a day. Dug in with nobody pushing they grow a day a day as before.
	 * Arkoman's 1,248 pushing on the swarm's 1,413 strips its 21 days in 24; a 2:1 push strips 30
	 * in 15; the cover regrows only once the push stops.
	 */
	protected static float coverStep(float days, boolean pushing, float enemyPressure, float elapsedDays) {
		if (pushing) return days - elapsedDays;
		if (enemyPressure <= 0f) return days + elapsedDays;
		if (days > 0f) return Math.max(0f, days - enemyPressure * elapsedDays);
		return Math.min(0f, days + elapsedDays);
	}

	/** The rate an enemy push strips cover at: its covered push figure over the covered figure it pushes against, x frontCoverStripMult; 0 with nothing pushing. */
	protected static float coverPressure(float enemyPush, float ownForce) {
		if (enemyPush <= 0f) return 0f;
		return enemyPush / Math.max(1f, ownForce) * Math.max(0f, ThreatIncConfig.frontCoverStripMult());
	}

	/**
	 * THE BLEED-OUT TEST (the user, 2026-10-28; under the share rule a lone push always gains, so
	 * "does pushing move the line" no longer tells push from dig in): a push is worth its losses
	 * while it takes the next district before the force bleeds out at its push losses -
	 * (frontLineBaseDays - progress) / rate against troops / losses a day x
	 * doctrinePushBleedMargin. A force that loses nothing a day (a garrison with no marines) always
	 * pays; a push that gains nothing its way never does.
	 */
	protected static boolean pushPaysOff(float rate, float progress, float troops, float lossPerDay) {
		if (rate <= 0f) return false;
		float base = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
		float days = Math.max(0f, base - progress) / rate;
		if (lossPerDay <= 0f) return true;
		return days <= troops / lossPerDay * Math.max(0f, ThreatIncConfig.doctrinePushBleedMargin());
	}

	/** The line as the world's marines would push it (an order's quote): the rate at the landing's stance with the defenders pushing. */
	public static float defenderPushRate(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		return lineRate(front, market, STANCE_PUSH.equals(front.stance), true);
	}

	/** One line for the defenders' Push button and prompt: what the marines push with, against what, and where the line goes. */
	public static String defenderPushQuote(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return "";
		float rate = defenderPushRate(front, market);
		String word = lineState(rate, STANCE_PUSH.equals(front.stance), false);
		float change = lineChangeDays(front, rate);
		String where = change < 0f ? "the line stands" : "next " + layerName(market)
				+ (rate < 0f ? " back" : " lost") + " in ~" + (int) Math.ceil(change) + " days";
		return "Marines push with " + Misc.getWithDGS(Math.round(defenderPressure(front, market) * defenderCoverIfDugIn(front)))
				+ " against the swarm's " + Misc.getWithDGS(Math.round(effectiveStrength(front) * coverMult(front))) + ": " + word
				+ ", " + where + ".";
	}

	public static final String LINE_OVERRUNNING = "overrunning";
	public static final String LINE_GAINING = "gaining";
	public static final String LINE_GRINDING = "grinding";
	public static final String LINE_STALLED = "stalled";
	public static final String LINE_LOSING = "losing";
	public static final String LINE_OVERRUN = "overrun";

	/**
	 * The State word from one side ({@code fromLanding}: the landing's, else
	 * the world's): overrunning past frontOverrunRate, gaining past
	 * frontGrindBand, grinding inside the band with both sides pushing,
	 * stalled inside it otherwise, losing and overrun the mirror.
	 */
	public static String lineState(GroundFront front, MarketAPI market, boolean fromLanding) {
		if (front == null || market == null) return LINE_STALLED;
		boolean both = STANCE_PUSH.equals(front.stance) && defendersPush(front, market);
		return lineState(lineRate(front, market), both, fromLanding);
	}

	/** The State word for a rate read from one side ({@code fromLanding}: the landing's, else the world's); {@code bothPush} reads grinding over stalled inside the band. */
	public static String lineState(float rate, boolean bothPush, boolean fromLanding) {
		float r = fromLanding ? rate : -rate;
		float band = Math.max(0f, ThreatIncConfig.frontGrindBand());
		float over = Math.max(band, ThreatIncConfig.frontOverrunRate());
		if (r > over) return LINE_OVERRUNNING;
		if (r > band) return LINE_GAINING;
		if (r < -over) return LINE_OVERRUN;
		if (r < -band) return LINE_LOSING;
		return bothPush ? LINE_GRINDING : LINE_STALLED;
	}

	/** The suppression key the tick writes to front.state from the rate: HOLDING (key structures worn) while the landing gains, GRINDING (defence structures held) inside the band, FOOTHOLD (nothing) while it loses. */
	public static String lineSuppressionState(float rate) {
		float band = Math.max(0f, ThreatIncConfig.frontGrindBand());
		if (rate > band) return STATE_HOLDING;
		if (rate < -band) return STATE_FOOTHOLD;
		return STATE_GRINDING;
	}

	/**
	 * NPC and swarm doctrine (the user, 2026-10-24: both boards read stalled and
	 * the swarm kept pushing - "no point pushing when they are stalled"): push
	 * only while pushing moves the line forward; otherwise dig in for the cover,
	 * unless dug in the line would move against the landing while pushing holds
	 * it. Since the same afternoon each stance is weighed against the OTHER side's
	 * best reply, not its stance of the moment (the log at Sindria: the marines
	 * and the swarm swapped stances every few seconds, each answering the
	 * other's last move); a rate inside frontGrindBand is a standing line, and
	 * a tie digs in - the cover is free and a push bleeds. A
	 * hive world and the player's colonies have a fixed stance, so the swarm reads
	 * that one. A dry swarm front's final push is spent whatever the line says.
	 */
	public static boolean doctrinePushes(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		if (front.finalPush) return true;
		float pushV;
		float digV;
		if (isHiveTarget(market) || market.isPlayerOwned()) {
			boolean worldPushes = defendersPush(front, market);
			pushV = standing(lineRate(front, market, true, worldPushes));
			digV = standing(lineRate(front, market, false, worldPushes));
		} else {
			// the defenders answer with whichever stance moves the line least our way
			pushV = Math.min(standing(lineRate(front, market, true, true)),
					standing(lineRate(front, market, true, false)));
			digV = Math.min(standing(lineRate(front, market, false, true)),
					standing(lineRate(front, market, false, false)));
		}
		if (pushV <= digV) return false;
		if (pushV <= 0f) return true; // pushing slows a loss digging in would not
		// THE BLEED-OUT TEST (2026-10-28): the district before the force is gone
		return pushPaysOff(pushV, front.pushProgress, front.marines, landingLossPerDay(front, market, true));
	}

	/** Whether a line rate moves the line at all: outside frontGrindBand either way; inside it the line stands (stalled / grinding). */
	public static boolean lineMoves(float rate) {
		return Math.abs(rate) > Math.max(0f, ThreatIncConfig.frontGrindBand());
	}

	/** Days until the next district changes hands at the current rate, or -1 while the line stands still. */
	public static float daysToLineChange(GroundFront front, MarketAPI market) {
		return lineChangeDays(front, lineRate(front, market));
	}

	/** As {@link #daysToLineChange} at a given rate (an order's quote). */
	public static float lineChangeDays(GroundFront front, float r) {
		if (front == null || !lineMoves(r)) return -1f;
		float base = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
		float left = r > 0f ? base - front.pushProgress : base + front.pushProgress;
		return Math.max(0f, left / Math.abs(r));
	}

	/**
	 * Days until the side given wins the world at the line's current rate - the
	 * landing takes the last layer, or the world pushes the landing off (every
	 * layer it holds back, then the beachhead) - or -1 while the line stands or
	 * moves the other way, and for a landing barred from a story-critical world.
	 * The fronts table's Victory column (the user, 2026-10-24).
	 */
	public static float victoryDays(GroundFront front, MarketAPI market, boolean fromLanding) {
		if (front == null || market == null) return -1f;
		float r = lineRate(front, market);
		if (!lineMoves(r) || (r > 0f) != fromLanding) return -1f;
		if (fromLanding && lastStratumProtected(front, market)) return -1f;
		float base = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
		float first = fromLanding ? Math.max(0f, base - front.pushProgress) : Math.max(0f, base + front.pushProgress);
		int after = fromLanding ? Math.max(0, market.getSize() - front.strataHeld - 1) : Math.max(0, front.strataHeld);
		return (first + after * base) / Math.abs(r);
	}

	/**
	 * The line's rate (positive toward the landing) with one side's count
	 * changed: the landing at {@code troops}, or the world at {@code armed}
	 * marines (a colony) or troops (a hive) - everything else as it stands,
	 * at the stances given. The arithmetic {@link #troopsFor} halves on.
	 */
	public static float lineRateWith(GroundFront front, MarketAPI market, boolean forLanding, float count,
			boolean landingPushes, boolean worldPushes) {
		if (front == null || market == null) return 0f;
		float a = effectiveStrength(front);
		if (forLanding) a = front.marines > 0f ? a * count / front.marines : 0f;
		float coverA = coverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
		float d;
		float coverD;
		float pressure;
		if (isHiveTarget(market)) {
			// a hive's troops are its strata, in vanilla's figure already (ThreatStrata); the count asked is extra
			float troops = forLanding ? 0f : count;
			d = MarketCMD.getDefenderStr(market, true) + troops;
			coverD = 1f;
			pressure = d * hiveCounterAttackPace(market);
		} else {
			float over = defenderOverExtendMult(front);
			float garrison = colonyGarrison(market);
			float armed = forLanding ? ThreatReserves.armedMarines(market) : count;
			float perMarine = Math.max(0f, ThreatIncConfig.reserveDefenseMult())
					* (defendersDry(market) ? Math.max(0f, ThreatIncConfig.frontDryEffectivenessMult()) : 1f)
					* ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(market));
			float manned = mannedGarrison(garrison, armed); // the defences this many marines man (2026-10-28)
			d = (manned + perMarine * armed) * over;
			coverD = defenderCoverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
			pressure = (manned + perMarine * Math.max(0f, ThreatIncConfig.marineCounterAttackMult()) * armed) * over;
		}
		return lineRateOf(a, coverA, d, coverD, pressure, landingPushes, worldPushes);
	}

	/**
	 * The fewest troops (the landing) or armed marines / hive troops (the
	 * world) that side needs for the line to reach {@code target} its way -
	 * the landing a rate at least target, the world one at most target - at the
	 * stances given, by halving on {@link #lineRateWith}; -1 when no number
	 * reaches it. The row tooltip's "Needs x to hold / push / overrun" (the
	 * user, 2026-10-24).
	 */
	public static float troopsFor(GroundFront front, MarketAPI market, boolean forLanding, float target,
			boolean landingPushes, boolean worldPushes) {
		if (front == null || market == null) return -1f;
		if (forLanding && front.marines <= 0f) return -1f;
		float hi = 1f;
		while (hi < 10000000f && !reaches(front, market, forLanding, hi, target, landingPushes, worldPushes)) hi *= 2f;
		if (!reaches(front, market, forLanding, hi, target, landingPushes, worldPushes)) return -1f;
		float lo = 0f;
		for (int i = 0; i < 40; i++) {
			float mid = (lo + hi) / 2f;
			if (reaches(front, market, forLanding, mid, target, landingPushes, worldPushes)) hi = mid;
			else lo = mid;
		}
		return (float) Math.ceil(hi);
	}

	private static boolean reaches(GroundFront front, MarketAPI market, boolean forLanding, float count, float target,
			boolean landingPushes, boolean worldPushes) {
		float r = lineRateWith(front, market, forLanding, count, landingPushes, worldPushes);
		return forLanding ? r >= target : r <= target;
	}

	/**
	 * The one word for how dug in a side is, by its entrenchment days over
	 * frontEntrenchDays: spread thin (over-extended by pushing), no cover,
	 * poor, some, good or full cover (the user, 2026-10-24: "cover is a better
	 * phrase to use overall rather than entrenched or over extended").
	 */
	public static String coverWord(float entrenchDays) {
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		float frac = entrenchDays / days;
		if (frac <= -0.01f) return "spread thin";
		if (frac < 0.01f) return "no cover";
		if (frac < 0.34f) return "poor cover";
		if (frac < 0.67f) return "some cover";
		if (frac < 0.99f) return "good cover";
		return "full cover";
	}

	/** Effective strength a landing needs to gain ground here, pushing against the world as it stands: its defence in cover. */
	public static float holdRequirement(MarketAPI market) {
		return defenderStrength(market);
	}

	/** Effective strength a landing needs to keep what it holds fully dug in against the world's push: the world's pressure over the best cover. */
	public static float grindRequirement(MarketAPI market) {
		GroundFront front = market != null ? getFront(market.getId()) : null;
		return defenderPressure(front, market) / Math.max(1f, ThreatIncConfig.frontEntrenchDefenseBonus());
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
		float r = lineRate(front, market, true, defendersPush(front, market));
		if (r <= 0f || !lineMoves(r)) return -1f;
		return Math.max(1f, ThreatIncConfig.frontLineBaseDays()) / r;
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

	/** Estimated marines a push on the next stratum costs at current strength. */
	public static int pushCasualtyEstimate(GroundFront front, MarketAPI market) {
		float days = pushDaysEstimate(front, market);
		if (days <= 0f) return 0;
		return Math.round(landingLossPerDay(front, market, true) * days); // as the tick applies it
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
		int size = market.getSize();
		if (size <= 0) return 0f;
		// a starving hive (its Core two inputs short, 2026-10-25) counter-attacks at half pace
		float fed = ThreatColonyManager.coreState(market).starving() ? 0.5f : 1f;
		return fed * Math.max(0, size - strataHeld(market.getId())) / (float) size;
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

	/** How dug in the front is: 0 fresh (or over-extended), 1 at frontEntrenchDays of holding. */
	public static float entrenchFraction(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(0f, Math.min(1f, front.entrenchDays / days));
	}

	/**
	 * How over-extended the front is: 0 at any entrenchment, 1 after frontEntrenchDays
	 * of pushing past zero (the user, 2026-10-24: "pushing for too long should have the
	 * opposite effect - spread thin to the same degree the longer the push goes").
	 */
	public static float overExtension(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(0f, Math.min(1f, -front.entrenchDays / days));
	}

	/** What over-extension costs: strength / (1 + (frontEntrenchDefenseBonus - 1) x over-extension) - the dug-in bonus in reverse. */
	public static float overExtendMult(float overExtension) {
		return 1f / (1f + (Math.max(1f, ThreatIncConfig.frontEntrenchDefenseBonus()) - 1f) * Math.max(0f, Math.min(1f, overExtension)));
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
		return (landing + (ThreatIncConfig.frontEntrenchMaxMult() - landing) * entrenchFraction(front))
				* overExtendMult(overExtension(front)); // and spread thin past zero (2026-10-24)
	}

	/** The cover multiplier on the landing: 1 + (frontEntrenchDefenseBonus - 1) x the days dug in, whatever its stance - digging in earns it, pushing spends it (the user, 2026-10-24: "pushing should reduce cover"; before, a pushing side was exposed at 1 outright). */
	public static float coverMult(GroundFront front) {
		if (front == null) return 1f;
		return 1f + (ThreatIncConfig.frontEntrenchDefenseBonus() - 1f) * entrenchFraction(front);
	}

	// ------------------------------------------------------------------
	// the defenders' stance (2026-10-17, docs/ground-war-defenders.md "The
	// defenders choose too"): a colony with an army on it either PUSHES -
	// counter-attacks on the cadence, each win retaking a district - or DIGS
	// IN: holds fire, earns frontEntrenchDefenseBonus on its defence over
	// frontEntrenchDays, and loses that cover with a district. The player
	// orders their own colonies from the board; NPC colonies push while
	// pushing moves the line toward them and dig in while it does not
	// (doctrineDefendersPush, 2026-10-24). A hive has no stance: it
	// counter-attacks with everything, as before.
	// ------------------------------------------------------------------

	/** The defenders' stance: STANCE_PUSH unless ordered to dig in (null = no order yet, read as push until {@link #tickDefenderStance} picks one on a player colony). */
	public static String defenderStance(GroundFront front) {
		return front != null && STANCE_ENTRENCH.equals(front.defence) ? STANCE_ENTRENCH : STANCE_PUSH;
	}

	/** Whether the colony's defenders are dug in and holding fire: no counter-attack is coming while this is true. */
	public static boolean defendersHoldFire(GroundFront front, MarketAPI market) {
		return front != null && market != null && !isHiveTarget(market)
				&& STANCE_ENTRENCH.equals(front.defence);
	}

	/** How dug in the defenders are: 0 fresh (or over-extended), 1 at frontEntrenchDays of holding fire. */
	public static float defenderEntrenchFraction(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(0f, Math.min(1f, front.defenderEntrenchDays / days));
	}

	/** How over-extended the defenders are: 0 at any cover, 1 after frontEntrenchDays of pushing past zero ({@link #overExtension} for the colony's marines). */
	public static float defenderOverExtension(GroundFront front) {
		if (front == null) return 0f;
		float days = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(0f, Math.min(1f, -front.defenderEntrenchDays / days));
	}

	/** The colony's whole ground figure x this while its marines are over-extended ({@link #overExtendMult}); 1 at any cover. */
	public static float defenderOverExtendMult(GroundFront front) {
		return overExtendMult(defenderOverExtension(front));
	}

	/** Entrenchment days bounded to -frontEntrenchDays..+frontEntrenchDays. */
	protected static float clampEntrench(float days) {
		float cap = Math.max(1f, ThreatIncConfig.frontEntrenchDays());
		return Math.max(-cap, Math.min(cap, days));
	}

	/** The cover multiplier on the colony's defence figure: 1 + (frontEntrenchDefenseBonus - 1) x the days its defenders have dug in, whatever their stance - pushing spends it day by day (the user, 2026-10-24). */
	public static float defenderCoverMult(GroundFront front) {
		if (front == null) return 1f;
		return 1f + (ThreatIncConfig.frontEntrenchDefenseBonus() - 1f) * defenderEntrenchFraction(front);
	}

	/** The defenders' cover as it would stand dug in, whatever their stance now - defenderCoverMult without the stance test (the cover dug so far is kept while they push). */
	public static float defenderCoverIfDugIn(GroundFront front) {
		if (front == null) return 1f;
		return 1f + (ThreatIncConfig.frontEntrenchDefenseBonus() - 1f) * defenderEntrenchFraction(front);
	}

	/**
	 * NPC defenders' doctrine under THE LINE (the user, Sindria 2026-10-24: marines
	 * handed over "to push back" sat dug in for a week - the old test asked whether
	 * one counter-attack would win): push while pushing moves the line toward them
	 * (past frontGrindBand), otherwise dig in for the cover, unless dug in the line
	 * would move against them while pushing holds it. The mirror of
	 * {@link #doctrinePushes}, weighed the same way against the swarm's best reply
	 * (a player landing's stance is the player's, so that one is read as it is);
	 * the player's colonies keep their orders.
	 */
	public static boolean doctrineDefendersPush(GroundFront front, MarketAPI market) {
		if (front == null || market == null || isHiveTarget(market)) return true;
		float pushV;
		float digV;
		if (front.isPlayerOwned()) {
			boolean landingPushes = STANCE_PUSH.equals(front.stance);
			pushV = standing(lineRate(front, market, landingPushes, true));
			digV = standing(lineRate(front, market, landingPushes, false));
		} else {
			// the swarm answers with whichever stance moves the line most its way
			pushV = Math.max(standing(lineRate(front, market, true, true)),
					standing(lineRate(front, market, false, true)));
			digV = Math.max(standing(lineRate(front, market, true, false)),
					standing(lineRate(front, market, false, false)));
		}
		if (pushV >= digV) return false;
		if (pushV >= 0f) return true; // pushing slows a loss digging in would not
		// THE BLEED-OUT TEST (2026-10-28), on the marines - a garrison with none pushes for free
		float armed = ThreatReserves.armedMarines(market);
		return pushPaysOff(-pushV, -front.pushProgress, armed, armed > 0f ? worldLossPerDay(market, front, armed) : 0f);
	}

	/** "push" / "dug in" - the marines' stance against a Threat landing, as the board names it (push = they move the line, dug in = they hold it from cover). */
	public static String defenderStanceLabel(GroundFront front) {
		return STANCE_ENTRENCH.equals(defenderStance(front)) ? "dug in" : "push";
	}

	/**
	 * The stance of the marines on this world, whichever side landed (the
	 * board's Marines / Swarm framing, 2026-10-17): a marine landing's own
	 * stance, or the garrison's against a Threat landing.
	 */
	public static String marineStanceLabel(GroundFront front, MarketAPI market) {
		if (front == null) return "-";
		return isThreatOwned(front) ? defenderStanceLabel(front) : stanceLabel(front);
	}

	/** The swarm's stance on this world: a Threat landing's own; a hive has none ("-"), it counter-attacks with everything. */
	public static String swarmStanceLabel(GroundFront front, MarketAPI market) {
		if (front == null) return "-";
		return isThreatOwned(front) ? stanceLabel(front) : "-";
	}

	/** Marines lost per 30 days on this world: a marine landing's attrition, or the garrison's bleed against a Threat landing. */
	public static float marineLossPer30Days(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		return isThreatOwned(front) ? defenderLossPer30Days(market, front) : attritionPer30Days(front, market);
	}

	/** Swarm troops lost per 30 days on this world: a Threat landing's attrition; a hive's defence is structures and does not bleed (0). */
	public static float swarmLossPer30Days(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return 0f;
		return isThreatOwned(front) ? attritionPer30Days(front, market) : 0f;
	}

	/** The defenders go over to pushing the line. */
	public static void orderDefenderPush(GroundFront front) {
		if (front == null || !STANCE_ENTRENCH.equals(front.defence)) return;
		front.defence = STANCE_PUSH;
	}

	/** The defenders hold fire and dig in; the cover starts accruing this tick. */
	public static void orderDefenderEntrench(GroundFront front) {
		if (front == null || STANCE_ENTRENCH.equals(front.defence)) return;
		front.defence = STANCE_ENTRENCH;
	}

	/** Why the defenders cannot be ordered to push, or null. */
	public static String defenderPushBlockReason(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return "There is no army there any more.";
		if (isHiveTarget(market)) return "A hive counter-attacks with everything it has.";
		if (!STANCE_ENTRENCH.equals(front.defence)) return "The marines are already pushing.";
		return null;
	}

	/** Why the defenders cannot be ordered to dig in, or null. */
	public static String defenderEntrenchBlockReason(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return "There is no army there any more.";
		if (isHiveTarget(market)) return "A hive counter-attacks with everything it has.";
		if (STANCE_ENTRENCH.equals(front.defence)) return "The marines are already dug in.";
		return null;
	}

	/**
	 * NPC colonies' defender stance AI, one tick ({@link #doctrineDefendersPush},
	 * 2026-10-24): push while pushing moves the line toward them, hold fire and
	 * dig in while it does not (humans play to win, 2026-10-04: a garrison does
	 * not throw itself at a beachhead it cannot move). The player's colonies
	 * keep their orders; until the player gives one the doctrine picks ONCE, on
	 * the first tick (the user, Arkoman and Haven 2026-10-27: an unset stance
	 * read as push, so 183 marines pushed 1,252 troops from the day they landed).
	 */
	protected static void tickDefenderStance(GroundFront front, MarketAPI market, float elapsedDays) {
		if (front == null || market == null || isHiveTarget(market)) return;
		if (market.isPlayerOwned()) {
			if (front.defence == null) {
				front.defence = doctrineDefendersPush(front, market) ? STANCE_PUSH : STANCE_ENTRENCH;
				ThreatIncConfig.log("Marines of " + market.getName() + " " + defenderStanceLabel(front)
						+ " until ordered: the line at " + String.format("%.2f", lineRate(front, market)));
			}
		} else {
			boolean wins = doctrineDefendersPush(front, market);
			boolean dugIn = STANCE_ENTRENCH.equals(front.defence);
			if (wins && dugIn) {
				orderDefenderPush(front);
				ThreatIncConfig.log("Marines of " + market.getName() + " push: the line at " + String.format("%.2f", lineRate(front, market)));
			} else if (!wins && !dugIn) {
				orderDefenderEntrench(front);
				ThreatIncConfig.log("Marines of " + market.getName() + " dig in");
			}
		}
		// cover is dug while holding fire and spent going over the top - past zero the
		// marines are over-extended, to the same degree (the user, 2026-10-24); under the
		// landing's push it is stripped at the force ratio instead (coverStep, 2026-10-28)
		float landingPush = STANCE_PUSH.equals(front.stance) ? effectiveStrength(front) * coverIfDugIn(front) : 0f;
		front.defenderEntrenchDays = clampEntrench(coverStep(front.defenderEntrenchDays,
				!STANCE_ENTRENCH.equals(front.defence), coverPressure(landingPush, defenderStrength(market)), elapsedDays));
	}

	/**
	 * THE ATTRITION RULE (the user, 2026-10-24, replacing the per-30-day rates
	 * by stance, the dry / assault / outmatched multipliers, the defenders'
	 * pressure cap and the engaged cap): a side loses, a day,
	 * <pre>
	 * troops / attritionTroopsPer x (enemy force / own force) x 1 / own cover x veteran
	 * </pre>
	 * Forces are the Force column's figures (cover in); cover is the one
	 * factor the row shows (entrench x cover for a landing, cover x
	 * over-extension for a world). 500 v 500 both at full cover: 500 / 100 x 1
	 * x 1/1.5 = 3.3 a day each. 0 with no enemy force.
	 */
	public static float attritionPerDay(float troops, float enemyForce, float ownForce, float ownCover, float veteran) {
		if (troops <= 0f || enemyForce <= 0f || ownForce <= 0f) return 0f;
		float per = Math.max(1f, ThreatIncConfig.attritionTroopsPer());
		return troops / per * (enemyForce / ownForce) / Math.max(0.01f, ownCover) * Math.max(0f, veteran);
	}

	/**
	 * Whether a front has been ground down: no troops left, or fewer than frontCollapseTroops
	 * (default 0 - a landing fights to the last troop; the user, Sindria 2026-10-24, after a
	 * dug-in swarm of ~57 lost its cover with a district and the old 50 floor ended the war the
	 * next day). frontMinMarines stays what it was elsewhere: the size under which a landing
	 * is not attempted or a raid not crewed.
	 */
	public static boolean groundDown(GroundFront front) {
		if (front == null) return true;
		return front.marines <= 0f || front.marines < ThreatIncConfig.frontCollapseTroops();
	}

	/** The landing's ground losses a day at the given stance ({@link #attritionPerDay}); the tick's own figure. */
	public static float landingLossPerDay(GroundFront front, MarketAPI market, boolean pushing) {
		if (front == null || market == null) return 0f;
		float coverA = coverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
		return attritionPerDay(front.marines, defenderStrength(market), effectiveStrength(front) * coverA,
				entrenchMult(front) * coverA, ThreatMarineXP.frontLossMult(front));
	}

	/**
	 * The world's losses a day on {@code troops} of its own (a colony's armed
	 * marines, a hive's landed troops) against the front as it stands
	 * ({@link #attritionPerDay}); the tick's own figure.
	 */
	public static float worldLossPerDay(MarketAPI market, GroundFront front, float troops) {
		if (front == null || market == null) return 0f;
		boolean hive = isHiveTarget(market);
		float cover = hive ? 1f : defenderCoverMult(front) * defenderOverExtendMult(front);
		float veteran = hive ? 1f : ThreatMarineXP.lossMult(ThreatMarineXP.colonyLevel(market));
		return attritionPerDay(troops, effectiveStrength(front) * coverMult(front), defenderStrength(market), cover, veteran);
	}

	/**
	 * Marines the front loses per 30 days at the given stance - the tick's own
	 * rate ({@link #landingLossPerDay} x 30), so the table's Losses column and
	 * the Push / Dig in prompts quote what will actually happen - plus what
	 * orbit is doing to it (2026-09-08).
	 */
	public static float attritionPer30Days(GroundFront front, MarketAPI market, boolean pushing) {
		if (front == null) return 0f;
		return landingLossPerDay(front, market, pushing) * 30f + reliefBombardPer30Days(front, market);
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
	 * As above from what the observer last saw over the world (ThreatIntel, the
	 * fog of war, 2026-10-01): the convoy door's read (ThreatConvoys.canRunTo),
	 * which is remote. A faction with a front there has eyes on it every day.
	 */
	public static boolean orbitContested(String observer, MarketAPI market) {
		return market != null && ThreatIntel.worldFleets(observer, market) > 0;
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

	/**
	 * The fleets {@link #hostilePointsNear} weighs, as a list: an off-screen
	 * fight over the world (ThreatPurgeFGI's daily siege) weighs their points
	 * and strikes exactly these fleets.
	 */
	public static List<CampaignFleetAPI> hostileFleetsNear(String factionId, MarketAPI market) {
		List<CampaignFleetAPI> out = new ArrayList<CampaignFleetAPI>();
		if (market == null || factionId == null) return out;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return out;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (countsNear(fleet, market, factionId, false)) out.add(fleet);
		}
		return out;
	}

	protected static float pointsNear(MarketAPI market, String factionId, boolean friendly) {
		if (market == null || factionId == null) return 0f;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return 0f;
		float fp = 0f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (!countsNear(fleet, market, factionId, friendly)) continue;
			fp += fleet.getFleetPoints();
		}
		return fp;
	}

	/** Whether a fleet counts at the world for {@link #pointsNear} and {@link #hostileFleetsNear}: one filter, so what is weighed is what is struck. */
	protected static boolean countsNear(CampaignFleetAPI fleet, MarketAPI market, String factionId, boolean friendly) {
		if (fleet == null || !fleet.isAlive() || fleet.isExpired()) return false;
		if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) return false;
		// a passing freighter holds nothing
		if (Misc.isTrader(fleet) || Misc.isSmuggler(fleet) || Misc.isScavenger(fleet)) return false;
		// nor does a fleet running from another (the user, 2026-10-26: Threat fleets "fly in and fly away constantly",
		// and each time one was in range it held the orbit against eight Hegemony fleets)
		if (fleeing(fleet)) return false;
		boolean own = factionId.equals(fleet.getFaction().getId());
		if (friendly ? !own : own || !fleet.getFaction().isHostileTo(factionId)) return false;
		return nearWorld(fleet, market);
	}

	/** Whether the fleet is running from another: vanilla's flee state, or the flee target its AI remembers (FleetAIFlags.NEAREST_FLEEING_FROM). A fleeing fleet holds no orbit and is struck by no daily siege. */
	public static boolean fleeing(CampaignFleetAPI fleet) {
		if (fleet == null) return false;
		if (fleet.getAI() != null && fleet.getAI().isFleeing()) return true;
		return fleet.getMemoryWithoutUpdate() != null
				&& fleet.getMemoryWithoutUpdate().contains(com.fs.starfarer.api.campaign.ai.FleetAIFlags.NEAREST_FLEEING_FROM);
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
		tickRelief();
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
			if (!has && !ThreatSiegeMalus.anyDisrupted(market)) continue; // siege or no siege (2026-10-24)
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

		// attrition: THE ATTRITION RULE (attritionPerDay) - the odds, the cover, the men
		ThreatMarineXP.frontLose(front, landingLossPerDay(front, market, pushing) * elapsedDays);
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
		burnDefenderArms(market, front, elapsedDays); // and burn armaments as a front does (2026-10-17)
		tickDefenderStance(front, market, elapsedDays); // and choose: counter-attack or dig in (2026-10-17)
		bleedHiveStrata(market, front, elapsedDays); // a hive's strata too: every troop lost is a stratum point gone for good (2026-10-24)
		ThreatOccupation.tick(front, market, elapsedDays); // and the swarm's front on a human world claims it, eats it and grows (THE OCCUPATION, 2026-10-27)
		if (getFront(front.marketId) == null) return; // the swarm lifted off a gutted world (consumption, 2026-10-28)
		if (groundDown(front)) {
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

	front.daysAlive += elapsedDays;
	// cover is dug while holding, and spent while pushing - past zero the push is
	// over-extended, to the same degree (the user, 2026-10-24); both ways bounded by
	// frontEntrenchDays; under the world's push it is stripped at the force ratio
	// instead (coverStep, 2026-10-28)
	float worldPush = defendersPush(front, market) ? defenderPressure(front, market) * defenderCoverIfDugIn(front) : 0f;
	front.entrenchDays = clampEntrench(coverStep(front.entrenchDays, pushing,
			coverPressure(worldPush, effectiveStrength(front) * coverIfDugIn(front)), elapsedDays));

		// THE LINE (2026-10-23): the rate both sides move it at, and the
		// suppression key read off it - the landing wears the world's structures
		// while it gains ground, holds the defence structures inside the grind
		// band, suppresses nothing while it loses
		float eff = effectiveStrength(front);
		float defender = defenderStrength(market);
		float line = lineRate(front, market);
		front.state = lineSuppressionState(line);

		// v3 (the user, 2026-10-27, docs/suppression-balance.md 10): a landing wears, pins and
		// suppresses nothing - the world's structures are its innate defence, on or off as vanilla has them

		// the checkpoint: consolidating troops count down their orders window;
		// told nothing, doctrine says push on - or stand fast if too weak
		if (STANCE_CONSOLIDATE.equals(front.stance)) {
			front.consolidateDaysLeft -= elapsedDays;
			if (front.consolidateDaysLeft <= 0f || ThreatIncConfig.frontCheckpointDays() <= 0f) {
				front.consolidateDaysLeft = 0f;
			if (doctrinePushes(front, market)) {
				front.stance = STANCE_PUSH;
					front.pushProgress = 0f;
				} else {
					front.stance = STANCE_ENTRENCH;
				}
			}
		}

		// the line moves: toward the landing while it out-pushes the world's
		// cover, back while the world out-pushes its own. frontLineBaseDays of
		// progress takes the next district; the same the other way gives one
		// back, or pushes a beachhead holding none off the world
		// inside frontGrindBand the line stands: stalled / grinding means no ground
		// changes hands (the user, Sindria 2026-10-24: both boards read stalled and
		// the last district fell four days later - the rate inside the band had
		// been adding up all along)
		if (lineMoves(line)) {
			front.pushProgress += line * elapsedDays;
			float base = Math.max(1f, ThreatIncConfig.frontLineBaseDays());
			if (front.pushProgress >= base) {
				if (ThreatOccupation.holdsAll(front, market)) {
					// every district held: nothing left to take, the world is occupied (2026-10-27)
					front.pushProgress = 0f;
				} else if (lastStratumProtected(front, market)) {
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
			} else if (front.pushProgress <= -base) {
				loseStratum(front, market);
				if (getFront(front.marketId) == null) return; // beachhead overrun
			}
		}

		// the swarm's answer to an army on its world: hold the orbit
		// the swarm grinds a front it has the orbit over (2026-09-08)
		tickHiveBreakUp(front, market); // v3: never bombards; its swarms break up into strata when the ground is short
		// ...and the defenders' answer to a Threat army on theirs: a relief that
		// holds the orbit bombards it while they are losing (2026-10-03, the user)
		tickReliefBombard(front, market, elapsedDays);
		if (getFront(front.marketId) == null) return; // bombarded to nothing

		// dry AND too weak even to grind, the campaign is lost. An NPC or player
		// expedition asks for a pickup run (a supply run that lands first clears
		// the call). The SWARM has nowhere to be picked up to and no reserve to
		// bank into - a spent Threat landing simply dies where it stands.
		if (!front.isPlayerOwned() && !front.withdrawRequested && isDry(front)
				&& eff < grindRequirement(market)) {
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

		// NPC stance AI (doctrinePushes, 2026-10-24): push while pushing moves
		// the line, dig in for the cover while it does not - a pushing front that
		// stalls breaks off, a dug-in one that could gain goes. Consolidation
		// paces them like everyone else; player fronts push when troops land
		// (autoPush), when ordered, or by checkpoint doctrine above - a Dig in
		// order holds until then.
		if (!front.isPlayerOwned() && !isDry(front) && !lastStratumProtected(front, market)) {
			if (STANCE_ENTRENCH.equals(front.stance) && doctrinePushes(front, market)) {
				orderPush(front);
			} else if (STANCE_PUSH.equals(front.stance) && !doctrinePushes(front, market)) {
				orderEntrench(front);
				ThreatIncConfig.log((isThreatOwned(front) ? "Threat" : ownerOf(front)) + " front at "
						+ market.getName() + " digs in: the push does not pay (line "
						+ String.format("%.2f", lineRate(front, market)) + ")");
			}
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
	// what each side learns is fixed by the fight it was in, on vanilla's shape (2026-09-08), now per district
	float pressure = defenderPressure(front, market);
	ThreatMarineXP.frontEarn(front, effectiveStrength(front), pressure);
	if (!isHiveTarget(market)) ThreatMarineXP.colonyEarn(market, pressure, defenseStrength(front));
	front.pushProgress = 0f;
	front.strataHeld++;
	ThreatOccupation.onDistrictTaken(front, market); // and an industry claimed with it (the occupation, 2026-10-27)
	// a district changing hands resets both sides to 0, dug in or over-extended (the
	// user, 2026-10-24: "full wiped if the side loses a strata/district", then "same
	// thing if a district is taken - dug in or overextension all reset to 0")
	front.entrenchDays = 0f;
	front.defenderEntrenchDays = 0f;
	int total = market.getSize();
		ThreatAlarm.add(ownerOf(front), ThreatIncConfig.alarmPerStratum(),
				"stratum taken at " + market.getName());
		if (front.strataHeld >= total) {
			groundVictory(front, market);
			return;
		}
		// the checkpoint window is 0 by default since 2026-10-27 (the user, Haven: "what is even the
		// point of regrouping" - a flat pause every district, for every side): the doctrine's
		// stance at once; with a window set, the old regroup
		float window = ThreatIncConfig.frontCheckpointDays();
		front.stance = STANCE_CONSOLIDATE;
		front.consolidateDaysLeft = Math.max(0f, window);
		reapply(front.marketId); // strata strip base defense and fabrication
		if (window <= 0f) {
			// after the strip: the doctrine reads the world's figure as it now stands
			front.stance = doctrinePushes(front, market) ? STANCE_PUSH : STANCE_ENTRENCH;
			front.pushProgress = 0f;
		}
		ThreatIncConfig.log("Front took stratum " + front.strataHeld + "/" + total
				+ " at " + market.getName());
		announceLayer(front, market, true);
	}

	/**
	 * A layer has changed hands (the user, 2026-10-24: "a notification when threat or human
	 * faction take a district/strata"): the swarm's deed under the Threat's crest and colour,
	 * ours under the faction's; the second line is the count held.
	 */
	protected static void announceLayer(GroundFront front, MarketAPI market, boolean taken) {
		boolean threatFront = isThreatOwned(front);
		boolean hive = isHiveTarget(market);
		String layer = layerName(market);
		String Layer = Character.toUpperCase(layer.charAt(0)) + layer.substring(1);
		String layers = hive ? "strata" : "districts";
		String held = "" + front.strataHeld, total = "" + market.getSize();
		ThreatNotice n = ThreatNotice.titled(Layer + (taken ? " Taken" : " Retaken"));
		if (taken) {
			if (threatFront) {
				n.bad().line("The Threat landing on %s has taken a " + layer + ".", ThreatNotice.market(market));
			} else if (front.isPlayerOwned()) {
				n.good().icon(ownerFaction(front)).line("Your front on %s has taken a " + layer + ".", ThreatNotice.market(market));
			} else {
				n.good().icon(ownerFaction(front)).line("%s's front on %s has taken a " + layer + ".",
						ThreatNotice.faction(ownerFaction(front)), ThreatNotice.market(market));
			}
		} else if (hive) {
			n.bad().line("The hive's troops on %s have retaken a " + layer + ".", ThreatNotice.market(market));
		} else {
			n.good().icon(market.getFaction()).line("The garrison of %s has retaken a " + layer + ".", ThreatNotice.market(market));
		}
		if (threatFront) {
			n.line("The swarm holds %s of %s " + layers + ".", held, total);
		} else if (front.isPlayerOwned()) {
			n.line("Your front holds %s of %s " + layers + ".", held, total);
		} else {
			n.line("%s's front holds %s of %s " + layers + ".", ThreatNotice.faction(ownerFaction(front)), held, total);
		}
		n.send();
	}

	/**
	 * The world's push has moved the line a district back (THE LINE,
	 * 2026-10-23): the landing gives up the last district it took, both
	 * covers spoilt by their kept fractions, or - holding none - is pushed off
	 * the world ("Beachhead Overrun", the counter-attack's old outcome).
	 */
	protected static void loseStratum(GroundFront front, MarketAPI market) {
		boolean threatFront = isThreatOwned(front);
		Theatre theatre = Theatre.of(market);
		float pressure = defenderPressure(front, market);
		float defense = defenseStrength(front);
		ThreatMarineXP.frontEarn(front, defense, pressure);
		if (theatre == COLONY) ThreatMarineXP.colonyEarn(market, pressure, defense);
		front.pushProgress = 0f;
		front.entrenchDays = 0f; // a district changing hands resets both sides (2026-10-24)
		front.defenderEntrenchDays = 0f;
		if (front.strataHeld > 0) {
			front.strataHeld--;
			ThreatOccupation.release(front, market); // and the newest claim with it (the occupation, 2026-10-27)
			// a final push does not stop for a setback - it is spent either way
			front.stance = front.finalPush ? STANCE_PUSH : STANCE_ENTRENCH;
			reapply(front.marketId);
			ThreatIncConfig.log("The line at " + market.getName() + ": the defenders retake a " + layerName(market)
					+ " (" + (int) pressure + " against " + (int) defense + "), " + front.strataHeld + " held");
			announceLayer(front, market, false);
			return;
		}
		FactionAPI attacker = theatre == HIVE ? null : market.getFaction();
		String by = theatre == HIVE ? "The hive's troops on %s" : "The garrison of %s";
		ThreatNotice n = ThreatNotice.titled("Beachhead Overrun").icon(attacker);
		if (threatFront) n.good(); else n.bad();
		n.line(by + " have pushed the beachhead off the world.", ThreatNotice.market(market))
				.line("The front is destroyed.")
				.send();
		ThreatIncConfig.log("The line at " + market.getName() + ": the beachhead is overrun ("
				+ (int) pressure + " against " + (int) defense + ")");
		fronts().remove(front.marketId);
		reapply(front.marketId);
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
		// THE OCCUPATION (the user, 2026-10-27): the swarm never takes the world - the
		// front stands on every district, claims and eats its industries, and the owner
		// keeps the market until it pushes the front off (docs/occupation.md)
		if (ThreatOccupation.occupies(front, market)) {
			ThreatOccupation.occupy(front, market);
			return;
		}
		float army = Math.max(0f, front.marines); // the strata that took the world seed the hive (phase E)
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
			MarketAPI hive = ThreatColonyManager.convertConquered((PlanetAPI) world, market, army);
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
	 * v3 (the user, 2026-10-27): THE SWARM NEVER BOMBARDS. Over its own besieged hive every swarm
	 * fleet breaks up what the ground is short into strata ({@link #breakUpForHive}); no holds, no
	 * ordnance, no reserve. Replaces the swarm's bombardment of the front (tickSwarmBombard, deleted with phase 1b).
	 */
	protected static void tickHiveBreakUp(GroundFront front, MarketAPI market) {
		if (front == null || market == null || isThreatOwned(front) || !isHiveTarget(market)) return;
		if (!swarmHoldsOrbit(market)) return;
		for (CampaignFleetAPI f : swarmFleetsNear(market)) breakUpForHive(front, market, f);
	}

	// ------------------------------------------------------------------
	// relief over an invaded world (2026-10-03, the user): breaking hulls up
	// for troops gives up the orbit, and the side that takes it uses it - the
	// relief holds the space until the Threat army is gone (no landing, no
	// bombardment, no break-up while it does) and grinds the army from orbit
	// while the defenders are losing, on fuel it can spare
	// ------------------------------------------------------------------

	/** Memory key (market): the day's relief-bombardment line is logged. */
	protected static final String RELIEF_LOG_KEY = "$threatinc_reliefBombardLogged";

	/**
	 * Keeps each relief with no term on its orbit (a battle can empty its
	 * queue) and fighting the swarm there: the leash's hold-station blinders
	 * come off while Threat fleets are over the world (ThreatFleetOrders.enforceLeash).
	 */
	protected static void tickRelief() {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.relief || !o.indefinite() || o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			MarketAPI market = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
			if (market == null || market.getPrimaryEntity() == null) continue;
			ThreatSwarmDefend.holdOrbit(o.fleet, market, o.task());
			ThreatFleetOrders.fightOrbit(o.fleet, threatOverWorld(market));
		}
	}

	/**
	 * Whether a relief with no term goes home (ThreatFleetOrders.poll): the
	 * Threat army on its world is gone - beaten, or the world fell - or the
	 * relief is worn below defendMinStrength AND the swarm over the world
	 * outweighs it by siegeBreakOffRatio, a navy's Defend's rule
	 * ({@link #navyHoldsOver}). planRelief then sends what the swarm there asks.
	 */
	public static boolean reliefDone(CampaignFleetAPI fleet, String marketId) {
		MarketAPI market = marketId != null ? Global.getSector().getEconomy().getMarket(marketId) : null;
		if (market == null || !isThreatOwned(getFront(marketId))) return true;
		if (fleet == null || fleet.getFaction() == null) return true;
		if (ThreatReturns.orderHealth(fleet) >= ThreatIncConfig.defendMinStrength()) return false;
		float ratio = ThreatIncConfig.siegeBreakOffRatio();
		if (ratio <= 0f) return false;
		float ours = orbitPoints(fleet.getFaction().getId(), market, fleet.getFleetPoints());
		return pointsNear(market, Factions.THREAT, true) >= ours * ratio;
	}

	/** Whether any Threat fleet is over the world: a relief fights it before it bombards anything. */
	protected static boolean threatOverWorld(MarketAPI market) {
		return pointsNear(market, Factions.THREAT, true) > 0f;
	}

	/** Whether any live Threat fleet is anywhere in the world's system: a quiet system sends relief home (ThreatFleetOrders.reliefQuiet). */
	protected static boolean threatInSystem(MarketAPI market) {
		SectorEntityToken world = market != null ? market.getPrimaryEntity() : null;
		if (world == null || world.getContainingLocation() == null) return false;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired() || fleet.getFaction() == null) continue;
			if (fleet.getFleetPoints() <= 0f || !Factions.THREAT.equals(fleet.getFaction().getId())) continue;
			return true;
		}
		return false;
	}

	/**
	 * Whether the defenders are not winning against a Threat army: it is
	 * pushing, or it holds and the next counter-attack would not overrun it -
	 * what keeps a Defend fleet's guns cold over its own front
	 * ({@link #defendBombards}), seen from the other side.
	 */
	public static boolean defendersLosing(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		return lineRate(front, market) >= 0f; // the line is not moving the defenders' way
	}

	/**
	 * The fuel a relief keeps of what it carries: its passage home, a fleet of
	 * its points over its base's distance at expeditionFuelPerPointLY (the
	 * rate relief is provisioned at, ThreatFleetOrders.reliefWants). It
	 * bombards with the rest and its supply line's spare, never the way home.
	 */
	public static float reliefFuelHome(CampaignFleetAPI fleet, MarketAPI market) {
		if (fleet == null || market == null) return 0f;
		String home = ThreatReturns.homeOf(fleet);
		MarketAPI base = home != null ? Global.getSector().getEconomy().getMarket(home) : null;
		if (base == null || base.getStarSystem() == null) return 0f;
		float ly = Misc.getDistanceLY(base.getStarSystem().getLocation(), market.getLocationInHyperspace());
		return fleet.getFleetPoints() / IncursionManager.FP_PER_RESPONSE_DIFFICULTY * ly
				* ThreatIncConfig.expeditionFuelPerPointLY();
	}

	/**
	 * The relief forces that bombard this Threat army now: on station over its
	 * world, no Threat fleet there to fight first, the defenders losing
	 * ({@link #defendersLosing}), each with the fuel for {@code days} above
	 * its passage home ({@link #reliefFuelHome}). Empty otherwise.
	 */
	protected static List<ThreatFleetOrders.Order> reliefBombarding(GroundFront front, MarketAPI market, float days) {
		List<ThreatFleetOrders.Order> out = new ArrayList<ThreatFleetOrders.Order>();
		if (front == null || market == null || !isThreatOwned(front) || isHiveTarget(market)) return out;
		if (ThreatIncConfig.reliefBombardPer30Days() <= 0f || market.getPrimaryEntity() == null) return out;
		if (threatOverWorld(market) || !defendersLosing(front, market)) return out;
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.relief || !market.getId().equals(o.targetId)) continue;
			if (o.fleet == null || !o.fleet.isAlive() || o.fleet.isExpired()) continue;
			if (!ThreatFleetOrders.nearPlanet(o.fleet, market.getPrimaryEntity())) continue;
			float need = bombardFuelPerDay(o.fleet.getFleetPoints()) * days;
			if (ordnanceAvailable(o.fleet, o.factionId, reliefFuelHome(o.fleet, market)) < need) continue;
			out.add(o);
		}
		return out;
	}

	/**
	 * Troops the relief over the world bombards off this Threat army per 30
	 * days - the tick's own arithmetic ({@link #tickReliefBombard}); 0 when no
	 * relief fires. The board quotes it.
	 */
	public static float reliefBombardPer30Days(GroundFront front, MarketAPI market) {
		float fp = 0f;
		for (ThreatFleetOrders.Order o : reliefBombarding(front, market, 1f)) fp += o.fleet.getFleetPoints();
		return reliefBombardPer30Days(front, fp);
	}

	/** The swarm's old bombardment formula (swarmBombardPer30Days, gone with v3) for {@code fp} of relief: reliefBombardPer30Days of the army, by the relief's weight against what the army puts in the way and its veterancy. */
	protected static float reliefBombardPer30Days(GroundFront front, float fp) {
		if (front == null || fp <= 0f) return 0f;
		float ratio = fp / Math.max(1f, fp + defenseStrength(front));
		return front.marines * Math.max(0f, ThreatIncConfig.reliefBombardPer30Days()) * ratio
				* ThreatMarineXP.frontLossMult(front);
	}

	/** A poll of the relief over a Threat army ({@link #reliefBombarding}): each firing fleet pays its fuel, the army loses the troops, and one that falls below frontMinMarines is destroyed. */
	protected static void tickReliefBombard(GroundFront front, MarketAPI market, float elapsedDays) {
		if (elapsedDays <= 0f) return;
		List<ThreatFleetOrders.Order> firing = reliefBombarding(front, market, elapsedDays);
		if (firing.isEmpty()) return;
		float fp = 0f;
		float fuel = 0f;
		for (ThreatFleetOrders.Order o : firing) {
			float f = o.fleet.getFleetPoints();
			fuel += payOrdnance(o.fleet, o.factionId, bombardFuelPerDay(f) * elapsedDays, reliefFuelHome(o.fleet, market));
			fp += f;
		}
		float per30 = reliefBombardPer30Days(front, fp);
		float loss = per30 / 30f * elapsedDays;
		if (loss <= 0f) return;
		ThreatMarineXP.frontLose(front, loss);
		MemoryAPI mem = market.getMemoryWithoutUpdate();
		if (!mem.getBoolean(RELIEF_LOG_KEY)) {
			mem.set(RELIEF_LOG_KEY, true, 1f);
			ThreatIncConfig.log("Relief over " + market.getName() + ": " + firing.size() + " fleet(s) at " + (int) fp
					+ " FP bombard the Threat army - " + perDay(per30) + " troops a day for "
					+ (int) (fuel / elapsedDays) + " fuel a day, " + (int) front.marines + " left");
		}
		if (groundDown(front)) {
			ThreatNotice.titled("Threat Landing Destroyed").good()
					.line("The Threat landing on %s has been bombarded to nothing from orbit.",
							ThreatNotice.market(market))
					.line("The surface is clear.")
					.send();
			ThreatIncConfig.log("Front at " + market.getName() + " (threat) destroyed by relief bombardment");
			fronts().remove(front.marketId);
			reapply(front.marketId);
		}
	}

	/**
	 * Swarm space superiority over a hive world: the swarm is present in force
	 * ({@link #swarmPresent}) and the armed fleets hostile to the Threat within
	 * range weigh under orbitContestFraction of it. The mirror of the colony theatre's orbitHeldAgainst.
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
	 * Armed fleets hostile to the Threat within the swarm orbit radius of the hive
	 * world weigh at least orbitContestFraction of the swarm's there - the swarm's
	 * orbital dominance is being contested. This is what STOPS the swarm bombarding
	 * an enemy front (until v3 - the swarm never bombards now), and it is the whole counterplay:
	 * keep enough armed over the planet. Until 2026-10-12 any armed hostile fleet
	 * contested it (the user, the last fleet in a system the swarm held, dancing
	 * inside the radius: "my presence shouldn't necessarily stop them").
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
		float hostile = 0f;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (Misc.getDistance(fleet.getLocation(), world.getLocation()) > range) continue;
			if (fleet.getFaction().isHostileTo(Factions.THREAT)) hostile += fleet.getFleetPoints();
		}
		// the colony theatre's rule mirrored (orbitHeldAgainst): with no swarm there any
		// armed fleet contests it, otherwise the hostiles must weigh the fraction
		if (hostile > 0f && hostile >= swarmOrbitStrength(market) * Math.max(0f, ThreatIncConfig.orbitContestFraction())) return true;
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

	/** The FP an autoresolved flotilla left holding the orbit over its army on this hive world (coverFP): 0 with no such front, or once the swarm has outweighed it. */
	public static float coverOver(MarketAPI market) {
		if (market == null) return 0f;
		GroundFront front = getFront(market.getId());
		return front != null && !isThreatOwned(front) ? Math.max(0f, front.coverFP) : 0f;
	}

	/** Whether this faction's front on the world holds its orbit with an autoresolved flotilla's cover (coverFP) that still outweighs the swarm there. */
	public static boolean coverHolds(MarketAPI market, String factionId) {
		if (market == null || factionId == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || front.coverFP <= 0f || !factionId.equals(front.factionId)) return false;
		return front.coverFP >= swarmOrbitStrength(market);
	}

	/**
	 * Marines the colony loses per 30 days to a siege standing on its surface:
	 * {@link #worldLossPerDay} on its armed marines x 30 (THE ATTRITION RULE,
	 * 2026-10-24; before it a pressure-capped rate on the engaged frontage).
	 *
	 * <p>Hives are not here: their strength is structural and erodes by losing
	 * strata instead ({@link Theatre#HIVE}); their landed troops bleed by the
	 * same rule on their strata standing in {@link #bleedHiveStrata} (2026-10-24).
	 */
	public static float defenderLossPer30Days(MarketAPI market, GroundFront front) {
		if (market == null || front == null || isHiveTarget(market)) return 0f;
		float armed = ThreatReserves.armedMarines(market);
		if (armed <= 0f) return 0f;
		return worldLossPerDay(market, front, armed) * 30f;
	}



	/** Applies one tick of that; returns the marines actually lost. */
	protected static float bleedDefenders(MarketAPI market, GroundFront front, float elapsedDays) {
		if (elapsedDays <= 0f) return 0f;
		float loss = defenderLossPer30Days(market, front) / 30f * elapsedDays;
		if (loss <= 0f) return 0f;
		return ThreatReserves.spendDefendingMarines(market, loss);
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
	 * Military Base, High Command, Lion's Guard) - the same set an expedition
	 * softens from orbit, now ground down from the surface. Never the port (the
	 * user, 2026-10-24: "megaport is disrupted by sieging ... it should not be";
	 * only a blockade touches a colony's accessibility).
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
		return list;
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
		return 0f; // phase D: no fortification stands on a hive (the knobs this read are retired)
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
		return threatinc.rules.BattleRules.gunDefence(defence, Theatre.of(market).batteryShare(market));
	}

	/** The guns' constant on this world: the hive theatre's (hiveBombardReturnFirePerGunDefence) or the colony's (bombardReturnFirePerGunDefence). */
	public static float returnFireConstant(MarketAPI market) {
		boolean hive = market != null && Theatre.of(market) == HIVE;
		return hive ? 0f : Math.max(0f, ThreatIncConfig.bombardReturnFirePerGunDefence()); // no return fire from a hive (phase D)
	}

	/** A faction commander's worth on this world: the hive theatre's (hiveBombardFPWorth) or the colony's (bombardFPWorth). */
	public static float bombardWorth(MarketAPI market) {
		return Math.max(0f, ThreatIncConfig.bombardFPWorth()); // one worth since phase D: a hive answers with no guns
	}

	/** Fleet points the guns take per day from whatever bombards the world - set by the guns, not the fleet (the theatre's constant, returnFireConstant). */
	public static float returnFirePerDay(MarketAPI market, float defence) {
		return threatinc.rules.BattleRules.returnFirePerDay(returnFireConstant(market),
				gunDefence(market, defence));
	}

	// ------------------------------------------------------------------
	// THE DROP UNDER THE GUNS (2026-10-18, the user: "marines lost to heavy
	// batteries, like FP is lost to bombardment"). The batteries that answer
	// a bombardment fire on the drop ships too: a landing loses
	// dropLossPerGunDefence x the defence they still add (gunDefence - worn
	// guns take less, which is what bombarding first buys), never more than
	// dropLossMaxFraction of it. Both theatres, both sides; the player's
	// prompt quotes it, every landing gate and planner sizes for the
	// survivors (readyToLand, beachheadSurvives, beachheadTroops,
	// troopsToLand, IncursionManager.raidStrNeededAt). Fabricated troops pay
	// the guns through intactBatteryShare already and do not drop.
	// ------------------------------------------------------------------

	/** The drop's constant on this world: the hive theatre's (hiveDropLossPerGunDefence) or the colony's (dropLossPerGunDefence). */
	public static float dropLossConstant(MarketAPI market) {
		boolean hive = market != null && Theatre.of(market) == HIVE;
		return hive ? 0f : Math.max(0f, ThreatIncConfig.dropLossPerGunDefence()); // no guns on the drop at a hive (phase D)
	}

	/** Marines the guns take from a landing of troops on this world now, its batteries as worn as they are. */
	public static float dropLoss(MarketAPI market, float troops) {
		if (market == null || troops <= 0f) return 0f;
		return threatinc.rules.BattleRules.dropLoss(dropLossConstant(market),
				gunDefence(market, defenderStrength(market)), troops, ThreatIncConfig.dropLossMaxFraction());
	}

	/** The troops that reach the ground out of a landing of troops here now. */
	public static float afterDrop(MarketAPI market, float troops) {
		return Math.max(0f, troops - dropLoss(market, troops));
	}

	/** The landing that puts need troops on the ground through this world's drop toll now. */
	public static float landingFor(MarketAPI market, float need) {
		if (market == null) return Math.max(0f, need);
		return landingFor(market, need, gunDefence(market, defenderStrength(market)));
	}

	/** As above at a gun defence already in hand - a planner's, worn as its bombardment will wear it. */
	public static float landingFor(MarketAPI market, float need, float gunDefence) {
		if (market == null || need <= 0f) return Math.max(0f, need);
		return threatinc.rules.BattleRules.landingForSurvivors(need,
				dropLossConstant(market) * Math.max(0f, gunDefence), ThreatIncConfig.dropLossMaxFraction());
	}

	/**
	 * The drop itself: takes the guns' toll off a landing or a reinforcement of
	 * troops and returns the survivors, logging it ("Drop at ...") and telling
	 * the player when the landing is theirs. Called once per landing by
	 * landOrReinforce and the player's ground menu - never inside deploy /
	 * resupply, which the fabrication path shares.
	 */
	public static int dropped(MarketAPI market, int troops, String factionId) {
		if (market == null || troops <= 0) return Math.max(0, troops);
		int lost = Math.min(troops, Math.round(dropLoss(market, troops)));
		if (lost <= 0) return troops;
		ThreatIncConfig.log("Drop at " + market.getName() + " (" + factionId + "): lost " + lost + " of " + troops
				+ " marines to the guns (gun defence " + (int) gunDefence(market, defenderStrength(market))
				+ ", defences " + Math.round(fortificationCondition(market) * 100f) + "%)");
		if (factionId == null || Factions.PLAYER.equals(factionId)) {
			ThreatNotice.titled("Landing Under Fire").bad()
					.line("%s marines lost to the batteries of %s on the drop.", Misc.getWithDGS(lost),
							ThreatNotice.market(market))
					.send();
		}
		return troops - lost;
	}

	/** Fuel a day of tactical bombardment burns for a fleet of fp. */
	public static float bombardFuelPerDay(float fp) {
		return threatinc.rules.BattleRules.bombardFuelPerDay(fp, ThreatIncConfig.bombardFuelPerFPDay());
	}

	/** Days of tactical bombardment this much fuel buys a fleet of fp; with no price, as long as it likes. */
	public static float bombardDaysFor(float fuel, float fp) {
		return threatinc.rules.BattleRules.bombardDaysFor(fuel, bombardFuelPerDay(fp));
	}

	// ------------------------------------------------------------------
	// THE STRATA EXCHANGE (phase D, docs/strata-are-the-threat.md 6): a hive has no structure to
	// suppress and no gun to answer with. A day of bombardment by F fleet points razes
	// bombardStrataPerFPDay x F strata for bombardFuelPerStratum fuel each - the size steps down
	// with them (ThreatStrata.add) and at 0 the hive is gone. No return fire, no unrest. Every
	// path - the player's dialog, an expedition's siege, a Support or Defend sortie, the council's
	// squadrons - runs this one rule on a hive (siegeSlice, saturationSlice, bombardPlan).
	// ------------------------------------------------------------------

	/** Whether the world is bombarded as strata: a hive world with a strata figure. */
	public static boolean strataTarget(MarketAPI market) {
		return market != null && Theatre.of(market) == HIVE && ThreatStrata.has(market);
	}

	/** Strata a day of bombardment by fp razes off a hive. */
	public static float hiveRazePerDay(float fp) {
		return Math.max(0f, fp) * Math.max(0f, ThreatIncConfig.bombardStrataPerFPDay());
	}

	/** Fuel a stratum razed burns. */
	public static float fuelPerStratum() {
		return Math.max(0f, ThreatIncConfig.bombardFuelPerStratum());
	}

	/** Fuel a day of bombardment burns here: a hive's strata rate x the fuel a stratum, else {@link #bombardFuelPerDay(float)}. */
	public static float bombardFuelPerDay(MarketAPI market, float fp) {
		if (strataTarget(market)) return hiveRazePerDay(fp) * fuelPerStratum();
		return bombardFuelPerDay(fp);
	}

	/** Days of bombardment this much fuel buys a fleet of fp here ({@link #bombardFuelPerDay(MarketAPI, float)}); with no price, as long as it likes. */
	public static float bombardDaysFor(MarketAPI market, float fuel, float fp) {
		return threatinc.rules.BattleRules.bombardDaysFor(fuel, bombardFuelPerDay(market, fp));
	}

	/**
	 * {@code days} of bombardment by {@code fp} over a hive with up to {@code fuel} to burn
	 * (Float.MAX_VALUE: unpriced): the strata razed, never more than stand. Returns {strata razed,
	 * fuel burnt, sizes down, 1 when the hive is gone}.
	 */
	public static float[] hiveRaze(MarketAPI market, float fp, float days, float fuel, String razerFactionId) {
		float[] out = new float[4];
		if (!strataTarget(market) || fp <= 0f || days <= 0f) return out;
		float strata = hiveRazePerDay(fp) * days;
		if (fuel < Float.MAX_VALUE && fuelPerStratum() > 0f) {
			strata = Math.min(strata, Math.max(0f, fuel) / fuelPerStratum());
		}
		strata = Math.min(strata, ThreatStrata.of(market));
		if (strata <= 0f) return out;
		int sizeBefore = market.getSize();
		markBesieged(market);
		float left = ThreatStrata.add(market, -strata, "Razed a stratum of",
				razerFactionId != null ? razerFactionId : Factions.PLAYER);
		out[0] = strata;
		out[1] = strata * fuelPerStratum();
		if (fuel < Float.MAX_VALUE) out[1] = Math.min(Math.max(0f, fuel), out[1]);
		out[2] = Math.max(0, sizeBefore - (market.isInEconomy() ? market.getSize() : 0));
		out[3] = left <= 0f ? 1f : 0f;
		ThreatIncConfig.log("hiveRaze " + market.getName() + " fp=" + String.format("%.0f", fp) + " days="
				+ String.format("%.2f", days) + " strata=-" + String.format("%.0f", strata) + " fuel="
				+ String.format("%.0f", out[1]) + " left=" + String.format("%.0f", Math.max(0f, left))
				+ (out[3] > 0f ? " DESTROYED" : ""));
		return out;
	}

	/**
	 * {@link #bombardPlan}'s run over a hive: the days a fleet of fp bombards for - until the troops it
	 * carries could land ({@link #troopsToLand} against the strata as they fall), the strata are gone,
	 * or the budget - and the defence figure then as a fraction of now. No guns: the fleet comes
	 * through whole. {the days, the fraction, the fleet points left, 1 when the troops land, 0}.
	 */
	protected static float[] hivePlan(MarketAPI market, float fp, float budget, float troops, boolean beachhead) {
		float start = Math.max(0f, fp);
		float d0 = Math.max(1f, MarketCMD.getDefenderStr(market, true));
		float s0 = Math.max(0f, ThreatStrata.standing(market));
		float lands0 = troops > 0f && troops >= troopsToLand(market, d0, d0, beachhead) ? 1f : 0f;
		if (start <= 0f || s0 <= 0f || lands0 > 0f) return new float[] { 0f, 1f, start, lands0, 0f };
		float perDay = hiveRazePerDay(start);
		if (perDay <= 0f) return new float[] { 0f, 1f, start, 0f, 0f };
		float s = s0;
		float d = d0;
		int day = 0;
		float lands = 0f;
		while (day < budget && s > 0f) {
			s = Math.max(0f, s - perDay);
			d = d0 * s / s0;
			day++;
			if (troops > 0f && troops >= troopsToLand(market, d, d0, beachhead)) {
				lands = 1f;
				break;
			}
		}
		return new float[] { day, d / d0, start, lands, 0f };
	}

	/** Disruption days a day of bombardment adds to a structure still whole: the theatre's rate x fleet / (fleet + defence). */
	public static float suppressionRate(MarketAPI market, float fp, float defence) {
		if (market == null || fp <= 0f) return 0f;
		return threatinc.rules.BattleRules.suppressionRate(fp, defence, ThreatIncConfig.siegeFPWeight(),
				Theatre.of(market).suppressDaysPerDay());
	}

	/** A structure's condition after one more day at this rate (the clock gains rate x condition x cover). */
	public static float conditionAfterDay(MarketAPI market, Industry ind, float rate, float through) {
		Theatre theatre = Theatre.of(market);
		return threatinc.rules.BattleRules.conditionAfterDay(theatre.condition(market, ind), rate, through,
				theatre.wearDays());
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
		return orbitSpent(market, fp, troops, beachhead, bombardWorth(market));
	}

	/** As above, a day needing {@code worth} defence off the world per fleet point lost ({@link #swarmWorth}). */
	public static boolean orbitSpent(MarketAPI market, float fp, float troops, boolean beachhead, float worth) {
		// a hive's orbit always has strata to raze (phase D): spent only when none stand
		if (strataTarget(market)) return ThreatStrata.standing(market) <= 0f || hiveRazePerDay(fp) <= 0f;
		if (market == null || !bombardable(market)) return true;
		return bombardPlan(market, fp, 1f, troops, beachhead, worth)[0] < 1f;
	}

	/**
	 * What a hull is worth in defence to a side that can break it up into
	 * troops over its own front ({@link #fabricateTroops}): fabricateTroopsPerFP
	 * troops, each fighting at {@code mult}, and a front gains ground past the
	 * defence figure itself (THE LINE, 2026-10-23) - so a defence point bombarded
	 * off is worth 1 / mult troops. A day of bombardment that takes
	 * less than this off per fleet point lost does less for the front than the
	 * same hulls sent down. bombardFPWorth when fabrication is off.
	 */
	public static float hullWorth(float mult) {
		if (!ThreatIncConfig.fabricateEnabled()) return Math.max(0f, ThreatIncConfig.bombardFPWorth());
		return Math.max(0f, ThreatIncConfig.fabricateTroopsPerFP()) * Math.max(0f, mult);
	}

	/**
	 * The swarm's stop (2026-09-28): it buys nothing with credits, so a hull is
	 * worth only what it becomes on the ground - {@link #hullWorth} at the
	 * landing's footing. It bombards while a day beats that and lands the
	 * rest as troops; a landing short of holding is made up by fabrication, so
	 * the shortHanded waiver never applies to it.
	 */
	public static float swarmWorth() {
		return swarmWorth(null);
	}

	/**
	 * As above over the world about to be landed on (2026-10-18, the user: the
	 * swarm "softens when it saves marines"): a defence point bombarded off
	 * spares hold / mult troops on the hold line AND, since the drop under the
	 * guns, dropLossConstant x the batteries' share of it on the drop - so the
	 * hulls a day costs are worth fewer defence points, and the swarm softens
	 * guns it landed under before (hw197: 0 / 20 / 78 bombardment days against
	 * 34 / 32 / 55 landings, a third to a half of its drops on intact guns).
	 * The share is the guns' as worn, so the term shrinks as the bombardment
	 * does its work and the swarm lands once the guns are quiet. Null market:
	 * the hold line alone (defendWorth over a standing front, where nothing drops).
	 */
	public static float swarmWorth(MarketAPI market) {
		if (!ThreatIncConfig.fabricateEnabled()) return Math.max(0f, ThreatIncConfig.bombardFPWorth());
		float mult = Math.max(0f, ThreatIncConfig.frontLandingMult());
		float toll = market != null ? dropLossConstant(market) * Math.max(0f, Theatre.of(market).batteryShare(market)) : 0f;
		return Math.max(0f, ThreatIncConfig.fabricateTroopsPerFP()) * mult / (1f + toll * mult);
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
		float need = Math.max(0f, defenderStrength(market) + moved) / mult; // the line: a landing gains past the defence figure itself
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (beachhead && margin > 0f) {
			float odds = threatinc.rules.BattleRules.beachheadOdds(ThreatIncConfig.groundStrengthExponent(), ThreatIncConfig.beachheadRepels());
			need = Math.max(need, Math.max(0f, counterAttackStrength(market) + moved) * margin / (mult * odds));
		}
		return landingFor(market, need); // plus the guns' toll on the way down (2026-10-18)
	}

	/** Vanilla's stability factor on the ground defence: 0.25 at 0, 1 at 10. */
	protected static float stabilityMult(float stability) {
		return 0.25f + Math.max(0f, Math.min(10f, stability)) * 0.075f;
	}

	/** Vanilla's FleetGroupIntel groupAbortsMissionFPFraction: an expedition cut below this share of what it set out with turns for home. */
	public static final float GROUP_ABORT_FRACTION = threatinc.rules.BattleRules.GROUP_ABORT_FRACTION;

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
		return bombardPlan(market, fp, budget, troops, beachhead, bombardWorth(market));
	}

	/** As above, a day needing {@code worth} defence off per fleet point lost. */
	public static float[] bombardPlan(MarketAPI market, float fp, float budget, float troops,
			boolean beachhead, float worth) {
		return bombardPlan(market, fp, budget, troops, beachhead, worth, GROUP_ABORT_FRACTION);
	}

	/**
	 * As above, stopping before the fleet falls below {@code floorFraction} of
	 * what it started with - a raid's line is a third lost (raidLossFraction,
	 * ThreatAttackPlanner), an expedition's vanilla's abort line. A fifth
	 * element: the days down the days flown buy, the most each day adds
	 * anywhere on the world summed ({@link #dailyGain}'s figure, day by day) -
	 * what a raid is worth.
	 */
	public static float[] bombardPlan(MarketAPI market, float fp, float budget, float troops,
			boolean beachhead, float worth, float floorFraction) {
		float start = Math.max(0f, fp);
		if (market == null) return new float[] { 0f, 1f, start, 0f, 0f };
		if (strataTarget(market)) return hivePlan(market, start, budget, troops, beachhead);
		float d0 = Math.max(1f, MarketCMD.getDefenderStr(market, true));
		float lands0 = troops > 0f && troops >= troopsToLand(market, d0, d0, beachhead) ? 1f : 0f;
		if (start <= 0f) return new float[] { 0f, 1f, start, lands0, 0f };
		Theatre theatre = Theatre.of(market);
		List<Industry> forts = theatre.fortifications(market);
		boolean shield = ThreatShield.present(market);
		if (forts.isEmpty() && !shield) return new float[] { 0f, 1f, start, lands0, 0f };
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
		float firePer = returnFireConstant(market);
		float floor = start * Math.max(0f, Math.min(1f, floorFraction));
		float fleet = start;
		float d = d0;
		float down = 0f;
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
			down += gain;
			day++;
			// the figure the next day meets: the fortifications as they now
			// stand, and the stability the day's unrest leaves
			d = fig.defence(cond, -1f, true);
		}
		float lands = troops > 0f && troops >= troopsToLand(market, d, d0, beachhead) ? 1f : 0f;
		return new float[] { day, Math.min(1f, d / d0), fleet, lands, down };
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
		return razePlan(market, fp, fuel, floorFP, 0f);
	}

	/**
	 * As above over a hive, finished once the least-worn structure saturation
	 * falls on is down {@code downGoal} days (0: the commander's stop) - the
	 * Bombard order's tier (the user, 2026-10-24: "min med max for aggressive
	 * actions should be sized for outcomes ... min is like 30 day disruption,
	 * med is 90", Max the stop). A goal already met is finished on day 0.
	 */
	public static float[] razePlan(MarketAPI market, float fp, float fuel, float floorFP, float downGoal) {
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
		float firePer = returnFireConstant(market);
		float pourPer = Math.max(0f, ThreatIncConfig.satFuelPerFPDay());
		float budget = ThreatIncConfig.siegeOrbitDays();
		float floor = Math.max(0f, floorFP);
		float fleet = start;
		// Float.MAX_VALUE: as much as the stay burns (a hive's price, IncursionManager.razingFuel)
		float left = Math.max(0f, fuel);
		float spent = 0f;
		float reached = 0f;
		boolean stopped = false;
		boolean goalMet = false;
		float d = d0;
		int day = 0;
		while (day < budget && (!bar || reached < need - 0.5f) && left >= 1f) {
			if (!bar && downGoal > 0f && wear * (1f - top) >= downGoal - 0.5f) {
				goalMet = true;
				break;
			}
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
		boolean finished = bar ? reached >= need - 0.5f : goalMet || (stopped && day > 0);
		return new float[] { day, spent, fleet, finished ? 1f : 0f, wear * (1f - top) };
	}

	/** Days the least-worn structure saturation falls on is down for now ({@link #razePlan}'s figure, wear x (1 - condition)); 0 with nothing to fall on. */
	public static float leastDown(MarketAPI market) {
		if (market == null) return 0f;
		Theatre theatre = Theatre.of(market);
		float top = 0f;
		boolean any = false;
		for (Industry ind : saturationTargets(market)) {
			top = Math.max(top, theatre.condition(market, ind));
			any = true;
		}
		return any ? theatre.wearDays() * (1f - top) : 0f;
	}

	/** Whether saturation by fp is done over a hive at a days-down goal (0: {@link #saturationSpent}, the commander's stop): the least-worn structure is down {@code downGoal} days, or the stop. */
	public static boolean saturationDone(MarketAPI market, float fp, float downGoal) {
		if (market != null && downGoal > 0f && leastDown(market) >= downGoal - 0.5f) return true;
		return saturationSpent(market, fp);
	}

	/** Each rung of a squadron ladder is this many times the one below it ({@link #squadronLadder}). */
	public static final float SQUADRON_STEP = 1.5f;

	/**
	 * BOMBING SQUADRON (2026-10-01, user's call): over a hive the flotilla
	 * holds the orbit and only a squadron of it saturates - it drops the
	 * bombs, pays the fuel, takes the guns' answer and turns back at its own
	 * abort line, GROUP_ABORT_FRACTION of what went in ({@link #razePlan} on
	 * the squadron). A small squadron wears a hive nearly as deep as the
	 * whole flotilla for a fraction of the fuel - the fortifications it wears
	 * stop answering, so the defence it meets falls as it goes (h47a, a
	 * size-7 hive: 434 FP took 111 days, 119k fuel, 264 days down; 12,600 FP
	 * 36 days, 1.3M fuel, 289 down) - but it takes longer, and the flotilla's
	 * supplies run all the while. The commander sends the squadron that buys
	 * a day of disruption cheapest: the fuel it pours and the flotilla's
	 * supplies for the stay (ThreatReach.tripSupplies), at base prices, over
	 * the days the hive is then down for - among the rungs of the ladder
	 * ({@link #squadronLadder}) the flotilla holds that reach the stop on the
	 * fuel aboard and leave the flotilla above {@code floorFP}. Short of the
	 * fuel for any, the least squadron pours what there is; a flotilla too
	 * light for the least flies it whole, as razePlan says. A world with a bar
	 * (ThreatRazing.razes) is razed by the whole flotilla: the bar fixes the
	 * fuel, and more fleet points pour it sooner.
	 *
	 * <p>Returns razePlan's figures for the flotilla - {days, fuel spent,
	 * flotilla fleet points left, 1 when finished, days down} - then the
	 * squadron's fleet points.
	 */
	public static float[] squadronPlan(MarketAPI market, float fp, float fuel, float floorFP, String factionId) {
		return squadronPlan(market, fp, fuel, floorFP, factionId, 0f);
	}

	/** As above at a days-down goal ({@link #razePlan}'s {@code downGoal}; 0: the commander's stop): the ladder's rungs reach the goal, and the cheapest day of disruption is priced to it. */
	public static float[] squadronPlan(MarketAPI market, float fp, float fuel, float floorFP, String factionId,
			float downGoal) {
		float flotilla = Math.max(0f, fp);
		if (market == null || flotilla <= 0f || ThreatRazing.razes(market)) {
			return withSquadron(razePlan(market, flotilla, fuel, floorFP, downGoal), flotilla);
		}
		List<float[]> ladder = squadronLadder(market, flotilla, downGoal);
		float fuelPrice = IncursionManager.basePrice(Commodities.FUEL);
		float suppliesPrice = IncursionManager.basePrice(Commodities.SUPPLIES);
		float[] best = null;
		float[] least = null;
		float bestCost = Float.MAX_VALUE;
		for (float[] rung : ladder) {
			if (rung[0] > flotilla + 0.5f) break;
			if (rung[4] < 1f) continue;
			if (least == null) least = rung;
			if (rung[2] > fuel + 0.5f || flotilla - (rung[0] - rung[3]) < floorFP) continue;
			float cost = (rung[2] * fuelPrice
					+ ThreatReach.tripSupplies(factionId, flotilla, rung[1]) * suppliesPrice)
					/ Math.max(1f, rung[5]);
			if (cost < bestCost) {
				bestCost = cost;
				best = rung;
			}
		}
		if (best != null) {
			float squadron = Math.min(flotilla, best[0]);
			return new float[] { best[1], best[2], flotilla - (squadron - best[3]), 1f, best[5], squadron };
		}
		if (least == null) return withSquadron(razePlan(market, flotilla, fuel, floorFP, downGoal), flotilla);
		float squadron = Math.min(flotilla, least[0]);
		float[] plan = razePlan(market, squadron, fuel,
				Math.max(squadron * GROUP_ABORT_FRACTION, floorFP - (flotilla - squadron)), downGoal);
		return new float[] { plan[0], plan[1], flotilla - (squadron - plan[2]), plan[3], plan[4], squadron };
	}

	private static float[] withSquadron(float[] plan, float squadron) {
		return new float[] { plan[0], plan[1], plan[2], plan[3], plan[4], squadron };
	}

	/** Hive market id and days-down goal -> its squadron ladder for the clock instant in squadronStamp; not saved. */
	private static final Map<String, List<float[]>> SQUADRON_LADDERS = new LinkedHashMap<String, List<float[]>>();
	private static long squadronStamp = Long.MIN_VALUE;

	/**
	 * The squadrons a commander weighs over this hive ({@link #squadronPlan}):
	 * the least that reaches the stop ({@link #leastSquadron}), then SQUADRON_STEP
	 * times the rung below, climbed until one is past {@code upTo}. A rung is
	 * {squadron FP, days, fuel, squadron FP left, 1 when finished, days down}:
	 * razePlan on the squadron with the fuel its stay burns. Empty when no
	 * squadron reaches the stop. Memoised per clock instant, so a sizing's
	 * search climbs it once.
	 */
	private static List<float[]> squadronLadder(MarketAPI market, float upTo, float downGoal) {
		long now = Global.getSector().getClock().getTimestamp();
		if (now != squadronStamp) {
			SQUADRON_LADDERS.clear();
			squadronStamp = now;
		}
		String key = market.getId() + "@" + (int) downGoal;
		List<float[]> ladder = SQUADRON_LADDERS.get(key);
		if (ladder == null) {
			ladder = new ArrayList<float[]>();
			SQUADRON_LADDERS.put(key, ladder);
			float least = leastSquadron(market, downGoal);
			if (least > 0f) ladder.add(squadronRung(market, least, downGoal));
		}
		// the guard only stops a runaway climb: 64 rungs is 10^11 times the least
		for (int i = 0; !ladder.isEmpty() && i < 64; i++) {
			float top = ladder.get(ladder.size() - 1)[0];
			if (top >= upTo) break;
			ladder.add(squadronRung(market, top * SQUADRON_STEP, downGoal));
		}
		return ladder;
	}

	private static float[] squadronRung(MarketAPI market, float fp, float downGoal) {
		float[] plan = razePlan(market, fp, Float.MAX_VALUE, Math.max(0f, fp) * GROUP_ABORT_FRACTION, downGoal);
		return new float[] { fp, plan[0], plan[1], plan[2], plan[3], plan[4] };
	}

	/**
	 * The fewest fleet points that saturate this hive to the commander's stop
	 * on their own (razePlan: above their abort line, within siegeOrbitDays):
	 * lighter, a day wears less than a day and it stops before it starts, or
	 * the guns sink it first. Bisected from a squadron as heavy as the
	 * defence, doubled until one gets there; 0 when none does.
	 */
	private static float leastSquadron(MarketAPI market, float downGoal) {
		float hi = Math.max(1f, MarketCMD.getDefenderStr(market, true)
				/ Math.max(0.01f, ThreatIncConfig.siegeFPWeight()));
		float lo = 0f;
		boolean reaches = reachesStop(market, hi, downGoal);
		for (int i = 0; !reaches && i < 24; i++) {
			lo = hi;
			hi *= 2f;
			reaches = reachesStop(market, hi, downGoal);
		}
		if (!reaches) return 0f;
		for (int i = 0; i < 40 && hi - lo > Math.max(1f, hi * 0.01f); i++) {
			float mid = (lo + hi) / 2f;
			if (reachesStop(market, mid, downGoal)) {
				hi = mid;
			} else {
				lo = mid;
			}
		}
		return hi;
	}

	/** Whether fp alone reaches the stop, or the days-down goal (0: the stop), above its own abort line within siegeOrbitDays. */
	private static boolean reachesStop(MarketAPI market, float fp, float downGoal) {
		return razePlan(market, fp, Float.MAX_VALUE, Math.max(0f, fp) * GROUP_ABORT_FRACTION, downGoal)[3] >= 1f;
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

	/** A world the siege rule describes counts as besieged for as long as its clock can run. */
	protected static void markBesieged(MarketAPI market) {
		Theatre theatre = Theatre.of(market);
		if (theatre.carriesSiegeState()) {
			market.getMemoryWithoutUpdate().set(BESIEGED_FLAG, true, theatre.wearDays());
		}
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
		return siegeSlice(fp, orbitFP, market, days, elapsed, reapply, defenceOverride, reason, null);
	}

	/** As above naming the faction whose bombardment it is ({@code razerFactionId}: who razes a hive's strata; null: the player). */
	public static float siegeSlice(float fp, float orbitFP, MarketAPI market, float days, boolean elapsed,
			boolean reapply, float defenceOverride, String reason, String razerFactionId) {
		if (market == null || days <= 0f || fp <= 0f) return 0f;
		// THE STRATA EXCHANGE (phase D): over a hive the day razes strata and nothing answers
		if (strataTarget(market)) {
			hiveRaze(market, fp, days, Float.MAX_VALUE, razerFactionId);
			if (reapply && market.isInEconomy()) market.reapplyIndustries();
			return 0f;
		}
		// v3 (2026-10-27): nothing of ours wears a human colony - vanilla's bombardment is vanilla's; a hive is strata (above)
		return 0f;
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
	 * raised to what it has suppressed (as tactical), its growth paused, and the fuel poured into
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
		// THE STRATA EXCHANGE (phase D): over a hive saturation is the one bombardment - strata for fuel
		if (strataTarget(market)) {
			float[] r = hiveRaze(market, fp, days, fuel, razerFactionId);
			out[1] = r[1];
			out[2] = r[2];
			out[3] = r[3];
			if (out[3] <= 0f && reapply && market.isInEconomy()) market.reapplyIndustries();
			return out;
		}
		// v3 (2026-10-27): no saturation of a human colony by anything of ours; a hive is strata (above)
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
		/** Over a hive (phase D): the strata the day razes, the strata standing now, and the size they leave. */
		public float strata;
		public float strataNow;
		public int sizeAfter;
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
		day.fuel = bombardFuelPerDay(market, fp);
		if (strataTarget(market)) {
			day.rate = 0f;
			day.strataNow = ThreatStrata.of(market);
			day.strata = Math.min(hiveRazePerDay(fp), day.strataNow);
			day.sizeAfter = day.strataNow - day.strata <= 0f ? 0
					: ThreatStrata.sizeOf(day.strataNow - day.strata, ThreatColonyManager.maxColonySize(market));
			return day;
		}
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
		int unrest = Math.round(unrestMax * (1f - day.conditionAfter)); // both kinds, since 2026-10-13
		day.unrest = unrest > RecentUnrest.getPenalty(market) ? unrest : 0;
		return day;
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

	/** Market memory of saves before 2026-10-24: troops the swarm landed on its own hive from ships broken up over it. ThreatStrata.of folds it into the strata and unsets it. */
	public static final String HIVE_TROOPS_KEY = "$threatinc_hiveTroops";

	/** The troops that would let the hive's counter-attack beat the enemy front, with fabricateHoldMargin of daylight; 0 when it already does. */
	public static float hiveTroopNeed(GroundFront front, MarketAPI market) {
		if (front == null || market == null || !isHiveTarget(market) || isThreatOwned(front)) return 0f;
		float want = defenseStrength(front) * (1f + Math.max(0f, ThreatIncConfig.fabricateHoldMargin()));
		return Math.max(0f, want - counterAttackStrength(market));
	}

	/**
	 * SHIPS INTO SOLDIERS OVER A HIVE (the user, 2026-10-13, after hw165-hw171, where five of 21 games fell
	 * to the bombardment draining the fuel stock to 0: "only take fuel for bombard in the first place if
	 * reserves are good. Otherwise they can go with no fuel and just reinforce the front right by breaking
	 * up ships. Conversely a navy that is small but with lots of fuel doesn't break up ships but bombards a
	 * lot instead"). A swarm fleet over its own hive with an enemy front on it (v3: the swarm never bombards) breaks up
	 * what the hive is short to beat
	 * the front (hiveTroopNeed) at fabricateTroopsPerFP, smallest hulls first, the flagship and the last
	 * hull spared (applyFleetLosses on the fabrication bank), into the hive's ground troops (hiveTroops).
	 * No battery toll: the ground is its own. Returns
	 * {fleet points broken up, troops landed}.
	 */
	protected static float[] breakUpForHive(GroundFront front, MarketAPI market, CampaignFleetAPI fleet) {
		float[] none = new float[] { 0f, 0f };
		if (!ThreatIncConfig.hiveBreakUpEnabled() || fleet == null || !fleet.isAlive() || fleet.isExpired()) return none;
		if (fleet.getBattle() != null || fleet.getFleetData().getNumMembers() <= 1) return none;
		float need = hiveTroopNeed(front, market);
		if (need <= 0f) return none;
		float perFP = Math.max(0.01f, ThreatIncConfig.fabricateTroopsPerFP());
		float fpBefore = fleet.getFleetPoints();
		float removed = applyFleetLosses(fleet, need / perFP, null, FABRICATE_BANK_KEY);
		if (removed <= 0f) return none;
		int troops = (int) (removed * perFP);
		if (troops <= 0) return none;
		fleet.getMemoryWithoutUpdate().set(FABRICATE_FLAG_KEY, true);
		// THE STRATA ARE THE TROOPS (the user, 2026-10-24: "if threat reinforce their own planet that is
		// directly increasing strata"): what lands is strata, for good
		ThreatStrata.add(market, troops, "Reinforced", null);
		fabricationVisual(market, Factions.THREAT, troops);
		ThreatIncConfig.log("Swarm breaks up over " + market.getName() + ": " + fleet.getName() + " (" + (int) fpBefore
				+ " FP) lands " + troops + " troops against the " + ownerOf(front) + " front (" + (int) front.marines
				+ " marines, " + (int) defenseStrength(front) + " defence); strata " + (int) ThreatStrata.of(market)
				+ ", counter-attack " + (int) counterAttackStrength(market));
		// told (the user, 2026-10-13: "should say how much fp converted into x troops"): the swarm's doing,
		// under its crest and colour, one fact a line
		ThreatNotice.titled("Swarm Breaks Up").bad()
				.line("%s broke up %s FP of its ships into %s troops on %s.", ThreatNotice.hl(fleet.getName()),
						ThreatNotice.hl((int) removed), ThreatNotice.hl(troops), ThreatNotice.market(market))
				.line("Against the %s front of %s marines.", ThreatNotice.faction(ownerFaction(front)),
						ThreatNotice.hl((int) front.marines))
				.line("Strata %s, counter-attack strength %s.", ThreatNotice.hl((int) ThreatStrata.of(market)),
						ThreatNotice.hl((int) counterAttackStrength(market)))
				.send();
		return new float[] { removed, troops };
	}

	// ------------------------------------------------------------------
	// CONSUMING A HUMAN WORLD (phase E, docs/strata-are-the-threat.md 13): the swarm's landed
	// strata eat the seized share of everything the world makes (ThreatGroundWarCondition's
	// output cut) and grow by it as a hive of the size they hold - coreStrataPerSizeMonth x
	// (size x share) a month, no Core needed: the planet is the Core. A dug-in Threat front is a
	// clock the defenders must push off; the attrition rule still bleeds it, and eating against
	// bleeding is the fight. A ground victory seeds the hive at the army's strata, and the hive
	// grows into the world's size by consumption (ThreatColonyManager.convertConquered).
	// ------------------------------------------------------------------

	/** The seized share of a human world under the swarm's front: districts held of its size; 0 for any other front. */
	public static float infestShare(MarketAPI market, GroundFront front) {
		if (market == null || front == null || !isThreatOwned(front) || isHiveTarget(market)) return 0f;
		return Math.max(0f, Math.min(1f, front.strataHeld / (float) Math.max(1, market.getSize())));
	}

	/**
	 * Strata a day the swarm's front grows on what it eats of the world: under THE
	 * OCCUPATION (2026-10-27) a hive's rate for the world's size x the share of producers claimed
	 * (ThreatOccupation.growthPerDay); with it off, phase E's share of the world's size.
	 */
	public static float infestGrowthPerDay(MarketAPI market, GroundFront front) {
		if (ThreatOccupation.occupies(front, market)) return ThreatOccupation.growthPerDay(front);
		float share = infestShare(market, front);
		if (share <= 0f) return 0f;
		return Math.max(0f, ThreatIncConfig.coreStrataPerSizeMonth()) * market.getSize() * share / 30f
				/ Math.max(0.01f, IncursionManager.timeScale());
	}

	/**
	 * THE STRATA ARE THE TROOPS (the user, 2026-10-24: "I'm expecting a siege's attrition to
	 * permanently lower strata for every troop lost even if siege against threat fails"): the hive's
	 * strata standing bleed as a colony's marines do - {@link #worldLossPerDay} on them (THE
	 * ATTRITION RULE) - and what is bled is gone for good whatever becomes of the landing
	 * (ThreatStrata.add: the size steps down with them, a front holding all that is left wins, 0 is
	 * the end). Until 2026-10-24 only the troops broken up over the hive bled (bleedHiveTroops) and
	 * the strata were a wall. Returns the strata lost.
	 */
	protected static float bleedHiveStrata(MarketAPI market, GroundFront front, float elapsedDays) {
		if (market == null || front == null || elapsedDays <= 0f || !isHiveTarget(market)) return 0f;
		float standing = ThreatStrata.standing(market);
		if (standing <= 0f) return 0f;
		float loss = Math.min(standing, worldLossPerDay(market, front, standing) * elapsedDays);
		if (loss <= 0f) return 0f;
		ThreatStrata.add(market, -loss, "Bled a stratum of", ownerOf(front));
		return loss;
	}


	/** The Threat fleets within the swarm orbit radius of a hive world - the fleets swarmOrbitStrength weighs. */
	public static List<CampaignFleetAPI> swarmFleetsNear(MarketAPI market) {
		List<CampaignFleetAPI> out = new ArrayList<CampaignFleetAPI>();
		if (market == null) return out;
		SectorEntityToken world = market.getPrimaryEntity();
		if (world == null || world.getContainingLocation() == null) return out;
		for (CampaignFleetAPI fleet : world.getContainingLocation().getFleets()) {
			if (fleet == null || !fleet.isAlive() || fleet.isExpired()) continue;
			if (fleet.getFleetPoints() <= 0f || fleet.getFaction() == null) continue;
			if (!Factions.THREAT.equals(fleet.getFaction().getId())) continue;
			if (Misc.getDistance(fleet.getLocation(), world.getLocation()) > 1500f) continue;
			out.add(fleet);
		}
		return out;
	}

	/**
	 * What a fleet's bombardment costs in fuel, paid. The swarm never bombards
	 * (v3, 2026-10-27): nothing to pay. Anyone else pays from its own
	 * provisions ({@link ThreatReturns#MEM_FUEL}) first and then from the
	 * spendable reserve of its supply line ({@link #ordnanceSources}).
	 * Returns the fuel actually paid, which may be short.
	 */
	public static float payOrdnance(CampaignFleetAPI fleet, String factionId, float fuel) {
		return payOrdnance(fleet, factionId, fuel, 0f);
	}

	/** As {@link #payOrdnance(CampaignFleetAPI, String, float)}, never touching the last {@code keep} of what the fleet carries (a relief's passage home, {@link #reliefFuelHome}). */
	public static float payOrdnance(CampaignFleetAPI fleet, String factionId, float fuel, float keep) {
		if (fuel <= 0f) return 0f;
		if (Factions.THREAT.equals(factionId)) return fuel; // v3: the swarm never bombards, nothing to pay
		if (fleet == null) return 0f;
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		float carried = Math.max(0f, mem.getFloat(ThreatReturns.MEM_FUEL));
		float paid = Math.min(Math.max(0f, carried - Math.max(0f, keep)), fuel);
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

	/** The fuel a fleet could put into its bombardment now, without paying it ({@link #payOrdnance}); the swarm's without limit (v3: it never bombards). */
	public static float ordnanceAvailable(CampaignFleetAPI fleet, String factionId) {
		return ordnanceAvailable(fleet, factionId, 0f);
	}

	/** As {@link #ordnanceAvailable(CampaignFleetAPI, String)}, less the last {@code keep} of what the fleet carries ({@link #payOrdnance(CampaignFleetAPI, String, float, float)}). */
	public static float ordnanceAvailable(CampaignFleetAPI fleet, String factionId, float keep) {
		if (Factions.THREAT.equals(factionId)) return Float.MAX_VALUE; // v3: the swarm never bombards
		if (fleet == null) return 0f;
		float fuel = Math.max(0f, fleet.getMemoryWithoutUpdate().getFloat(ThreatReturns.MEM_FUEL) - Math.max(0f, keep));
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
		float budget = ThreatIncConfig.siegeOrbitDays();
		float abortFP = start * abortFraction;
		boolean dry = false;
		while (fp > 0f && elapsed < budget && fp > abortFP) {
			// one frame: nothing runs down between steps
			float[] out = abstractSiegeStep(market, fp, troops, abortFP, factionId, fuel, SIEGE_FIRST_SLICE_DAYS,
					false);
			if (out[3] == SIEGE_STEP_DRY) dry = true;
			if (out[3] != SIEGE_STEP_SLICED) break;
			fp = out[0];
			fuel = out[1];
			elapsed += out[2];
		}
		syncSiegeState(market);
		market.reapplyIndustries();
		ThreatIncConfig.log("Abstract siege of " + market.getName() + ": " + (int) elapsed
				+ " d, " + (int) start + " -> " + (int) fp + " FP"
				+ (fuel < Float.MAX_VALUE ? ", " + (int) fuel + " fuel left" : "") + (dry ? ", ran dry" : ""));
		return new float[] { fp, fuel, elapsed, dry ? 1f : 0f };
	}

	/** {@link #abstractSiegeStep}'s verdict: it bombarded. */
	public static final int SIEGE_STEP_SLICED = 0;
	/** The troops could land now: orbit has done what it can for them, or they could hold as they are. */
	public static final int SIEGE_STEP_READY = 1;
	/** The ordnance buys no more bombardment. */
	public static final int SIEGE_STEP_DRY = 2;
	/** The guns' answer over the step would take the flotilla under its abort line: it lands on what orbit left. */
	public static final int SIEGE_STEP_GUNS = 3;

	/**
	 * One step of an off-screen siege by {@code fp} abstract fleet points: none
	 * when the troops could land ({@link #readyToLand}, {@link #orbitDone}), the
	 * ordnance is spent, or the guns' answer over the step would take the
	 * flotilla under {@code abortFP} ({@link #gunsWouldBreak}'s test over the
	 * step's days); else up to {@code step} days of bombardment paid out of
	 * {@code fuel}, and the world's stats recomputed. {@link #abstractSiege} runs
	 * it in a loop in one frame; an NPC siege that runs a day at a time
	 * (ThreatPurgeFGI's daily siege) runs it once a day with {@code elapsed}
	 * true, so the clocks' run-down between days is made up. Returns {fleet
	 * points left, fuel left, days bombarded, the verdict - SIEGE_STEP_*}.
	 */
	public static float[] abstractSiegeStep(MarketAPI market, float fp, float troops, float abortFP,
			String factionId, float fuel, float step, boolean elapsed) {
		if (readyToLand(market, troops, factionId, orbitDone(market, fp, fuel, troops,
				factionId != null && !Factions.PLAYER.equals(factionId)))) {
			return new float[] { fp, fuel, 0f, SIEGE_STEP_READY };
		}
		float days = Math.min(step, bombardDaysFor(market, fuel, fp));
		if (days <= 0f) return new float[] { fp, fuel, 0f, SIEGE_STEP_DRY };
		// the guns would break the siege within the step: its commander lands
		// on what orbit has left instead of turning for home
		float fire = returnFirePerDay(market, Math.max(0f, MarketCMD.getDefenderStr(market, true))) * days;
		if (fire > 0f && fp - fire < abortFP) return new float[] { fp, fuel, 0f, SIEGE_STEP_GUNS };
		// the stats are recomputed after every step - read once, the defence
		// stayed at its intact figure all siege (Eventide, 1221 for 18 steps
		// from 0.93 to 0.33), overpaying the guns and wearing the world too slowly
		float cost = bombardFuelPerDay(market, fp) * days;
		fp -= siegeSlice(fp, fp, market, days, elapsed, false, -1f, "Orbital bombardment", factionId);
		if (fuel < Float.MAX_VALUE) fuel = Math.max(0f, fuel - cost);
		syncSiegeState(market);
		market.reapplyIndustries();
		return new float[] { fp, fuel, days, SIEGE_STEP_SLICED };
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
		return returnFireConstant(market) * days
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
		if (!ThreatIncConfig.fabricateDefendEnabled()) return false;
		// the swarm's alone: bioships go down as troops, a navy's hulls do not
		if (!Factions.THREAT.equals(factionId) || market == null) return false;
		GroundFront front = getFront(market.getId());
		if (front == null || !factionId.equals(ownerOf(front))) return false;
		// past the hold line, to where it repels the counter-attack (counterGap, 2026-10-09)
		if (frontCanHold(front, market) && counterGap(front, market) <= 0f) return false;
		return orbitDoneFor(market, fleet, factionId);
	}

	/**
	 * What a DEFEND fleet's day must take off per fleet point lost. A faction's:
	 * bombardFPWorth, what the hull cost it. The swarm's: what the hull would
	 * become ({@link #hullWorth}) - at its front's footing over its own front
	 * that cannot hold, where it can go down as troops, else at the landing's (always
	 * the landing's with fabricateDefendEnabled off).
	 */
	public static float defendWorth(String factionId, MarketAPI market) {
		if (!Factions.THREAT.equals(factionId)) return bombardWorth(market);
		GroundFront front = market != null ? getFront(market.getId()) : null;
		if (front == null || !factionId.equals(ownerOf(front)) || frontCanHold(front, market)
				|| !ThreatIncConfig.fabricateDefendEnabled()) return swarmWorth();
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
		float gap = Math.max(holdGap(front, market), counterGap(front, market));
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
					+ " troops short of holding and repelling the counter-attack with margin; " + String.format("%.1f", wanted) + " FP of hulls wanted, "
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
		if (!ThreatIncConfig.fabricateDefendEnabled()) return false;
		if (defendFabricates(factionId, market, fleet)) return true;
		GroundFront front = getFront(marketId);
		return front != null && factionId != null && factionId.equals(ownerOf(front))
				&& fleet.getMemoryWithoutUpdate().getBoolean(FABRICATE_FLAG_KEY);
	}

	/**
	 * A navy's Defend fleet over its own standing front stays however worn
	 * (2026-09-28, the user: troops on the ground are not left to the swarm):
	 * holding the orbit costs it nothing, keeps the swarm from bombarding the
	 * front (until v3) and keeps the door open for the front runs
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
	 * The troops that would let a swarm front repel the world's counter-attack
	 * out of cover, with fabricateHoldMargin of daylight - the line the Defend
	 * fleets feed it to past the hold line (2026-10-09, hw93a). Fed to the hold
	 * line alone (then frontHoldFraction of the defender), a front on a world whose
	 * counter-attack outweighs it is battered every cadence, re-fed to the line
	 * and braced again, and never pushes: Eldfell's front stood 77 months at
	 * 473-737 against 759-957, fed +240 troops a month, with 82 First Strike
	 * fleets over it paying 11k supplies a month. 0 for any front but the swarm's.
	 */
	public static float counterGap(GroundFront front, MarketAPI market) {
		if (front == null || market == null || !isThreatOwned(front)) return 0f;
		float want = counterAttackStrength(market) * Math.max(1f, ThreatIncConfig.fabricateHoldMargin());
		float mult = Math.max(0.01f, entrenchMult(front) * ThreatMarineXP.frontEffectMult(front));
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
		// the guns take theirs on the way down first (dropLoss, 2026-10-18)
		return afterDrop(market, troops) * ThreatIncConfig.frontLandingMult() >= holdRequirement(market);
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
		// the swarm lands to overrun (the user, 2026-10-25); an NPC siege's first landing keeps the beachhead test
		if (Factions.THREAT.equals(factionId)) return landingOverruns(market, troops);
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
		float odds = threatinc.rules.BattleRules.beachheadOdds(ThreatIncConfig.groundStrengthExponent(), ThreatIncConfig.beachheadRepels());
		return (int) Math.ceil(landingFor(market, counterAttackStrength(market) * margin
				/ Math.max(0.01f, ThreatIncConfig.frontLandingMult() * odds)));
	}

	public static boolean beachheadSurvives(MarketAPI market, float troops) {
		float margin = ThreatIncConfig.siegeBeachheadMargin();
		if (market == null || margin <= 0f) return true;
		float odds = threatinc.rules.BattleRules.beachheadOdds(ThreatIncConfig.groundStrengthExponent(), ThreatIncConfig.beachheadRepels());
		return afterDrop(market, troops) * ThreatIncConfig.frontLandingMult() * odds >= counterAttackStrength(market) * margin;
	}

	/**
	 * THE LANDING IS SIZED TO OVERRUN (the user, 2026-10-25: "there are no
	 * counter attacks anymore. Landings should be sized to overrun"): the
	 * marines ashore, exposed as they land (frontLandingMult, no cover yet),
	 * whose line against the world as it stands - its defence in cover,
	 * pushing back with its whole pressure - moves toward them at
	 * frontOverrunRate (IncursionManager.lineMarinesFor, the Force ladder's Max
	 * tier: at 0.5 that is four times the defence before the drop). 0 with
	 * nothing to overrun. The beachhead (outlast the first counter-attack) was
	 * the old model's figure: hw274 landed three of them on Nomios and the
	 * line pushed each one off.
	 */
	public static float overrunNeed(MarketAPI market) {
		if (market == null) return 0f;
		float def = defenderStrength(market);
		if (def <= 0f) return 0f;
		// lineMarinesFor reads the world's push as def x pace
		float pace = defenderPressure(getFront(market.getId()), market) / def;
		return IncursionManager.lineMarinesFor(def, pace, Math.max(0f, ThreatIncConfig.frontOverrunRate()));
	}

	/** The landing that puts {@link #overrunNeed} ashore through the guns' toll ({@link #landingFor}). */
	public static int overrunTroops(MarketAPI market) {
		float need = overrunNeed(market);
		if (need <= 0f) return 0;
		return (int) Math.ceil(landingFor(market, need));
	}

	/** Whether a landing of {@code troops} here now overruns the world: what reaches the ground against {@link #overrunNeed}. */
	public static boolean landingOverruns(MarketAPI market, float troops) {
		float need = overrunNeed(market);
		if (need <= 0f) return true;
		return afterDrop(market, troops) >= need;
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
		if (Factions.THREAT.equals(factionId)) return false; // v3: the swarm never bombards
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
		// a swarm front short of the counter-attack line is fed by the guns first (counterGap, 2026-10-09:
		// hw100b's Nortia held at 150 against counter-attacks of 263, odds under 2, and its Defend fleet
		// stood idle as "the front holds" - no bombardment, and nothing broken up while orbit still paid)
		if (frontCanHold(front, market) && counterGap(front, market) <= 0f
				&& lineRate(front, market) >= 0f) return false;
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
			String label = support ? (o.raid ? "Raid" : "Support") : "Defend";
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
		if (frontCanHold(front, market) && counterGap(front, market) <= 0f) return "the front holds";
		if (!ThreatIncConfig.fabricateDefendEnabled() || !Factions.THREAT.equals(factionId)) {
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
		float want = bombardFuelPerDay(market, fp) * days;
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
				"Orbital bombardment", factionId);
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
		if (!isThreatOwned(front)) return false;
		if (stationOverFront(front, market)) return true;
		if (front.finalPush) return false;
		if (needsArms(front)) return front.armaments <= 0f;
		return !frontCanHold(front, market) || losingGround(front, market);
	}

	/**
	 * A hostile station flying over a world the swarm's army stands on - back from its
	 * repairs, or never beaten - is what the next expedition answers first (the user,
	 * 2026-10-28 evening, Haven's battlestation: "the swarm as a priority should bud fleets
	 * to take that out as it multiplies ground defence by 2x/3x"): vanilla's station
	 * multiplies the ground defence the army fights while it flies (OrbitalStation
	 * DEFENSE_BONUS - x1.5 orbital, x2 battlestation, x3 star fortress), and holds the orbit
	 * against any landing ({@link Theatre#orbitHeldAgainst}). The strike is sized to the
	 * defence seen (IncursionManager.targetDefence counts the station; the army on the
	 * ground is the swarm's eyes there, ThreatSwarmIntel.armyIn) and takes the world past
	 * the player-grace, core-world and war-open gates: it relieves a front that stands,
	 * it opens nothing.
	 */
	public static boolean stationOverFront(GroundFront front, MarketAPI market) {
		return isThreatOwned(front) && market != null && stationFlies(Factions.THREAT, market);
	}

	/**
	 * The world's push is moving the line back on the front (THE LINE,
	 * 2026-10-23): a district comes back every frontLineBaseDays / |rate|, and a
	 * beachhead holding none is overrun. Before the line this read whether one
	 * counter-attack by siegeBeachheadMargin beat the front (2026-09-29, run
	 * N3), while frontCanHold stayed true as relief convoys fed the garrisons
	 * that overran 14 of 17 Threat beachheads.
	 */
	public static boolean losingGround(GroundFront front, MarketAPI market) {
		if (front == null || market == null) return false;
		float r = lineRate(front, market);
		return r < 0f && lineMoves(r); // the world's push is moving the line back (THE LINE, 2026-10-23); inside the band it stands
	}

	/**
	 * Marines an own colony under a Threat army still wants banked to push the
	 * line back at frontPushBackRate ({@link #reliefNeed(MarketAPI, float)}):
	 * what relief convoys (ThreatConvoys.planRelief), the Reinforce button and
	 * a battle request are sized to. 0 with no Threat front.
	 */
	public static float reliefNeed(MarketAPI market) {
		return reliefNeed(market, Math.max(0f, ThreatIncConfig.frontPushBackRate()));
	}

	/**
	 * Marines a colony under a Threat army still wants banked for the line to
	 * move toward its defenders at {@code rate} (THE LINE, 2026-10-23: 0 holds
	 * it where it stands, frontPushBackRate pushes the army back,
	 * frontOverrunRate overruns it), found by halving on {@link #lineRateOf}
	 * with the marines armed at their reserve weight and the defenders pushing
	 * for any rate above 0 (dug in they can only hold). Stock on the world not
	 * yet armed counts, as it will be. Replaces the ask sized to win one
	 * counter-attack (the user, Sindria 2026-10-23: 10 marines asked while the
	 * last district fell in 9 days).
	 */
	public static float reliefNeed(MarketAPI market, float rate) {
		if (market == null) return 0f;
		float want = deployWant(market, rate);
		return want <= 0f ? 0f : Math.max(0f, want - ThreatReserves.stock(market.getId(), Commodities.MARINES));
	}

	/**
	 * Marines a colony under a Threat army wants deployed in all for the line to move its way at
	 * frontPushBackRate ({@link #deployWant(MarketAPI, float)}) - the ground victory's price. What the
	 * colony keeps of its marines while the army stands (ThreatReserves.frontKeep); the rest may stage
	 * for a siege (the user, 2026-10-24: "They can stage an expedition while under siege assuming they
	 * have enough marines to retake the world. Otherwise they should probably deploy enough marines first
	 * to ensure ground victory. Rest can still continue to be staged"). 0 with no Threat front.
	 */
	public static float deployWant(MarketAPI market) {
		return deployWant(market, Math.max(0f, ThreatIncConfig.frontPushBackRate()));
	}

	/** The marines {@link #reliefNeed(MarketAPI, float)} sizes its ask to, in all: stock on the world and the ask together. */
	public static float deployWant(MarketAPI market, float rate) {
		if (market == null || isHiveTarget(market)) return 0f;
		GroundFront front = getFront(market.getId());
		if (!isThreatOwned(front)) return 0f;
		float perMarine = Math.max(0f, ThreatIncConfig.reserveDefenseMult())
				* (defendersDry(market) ? Math.max(0f, ThreatIncConfig.frontDryEffectivenessMult()) : 1f)
				* ThreatMarineXP.effectMult(ThreatMarineXP.colonyLevel(market));
		if (perMarine <= 0f) return 0f; // banked marines do not fight here
		float target = -Math.max(0f, rate);
		float a = effectiveStrength(front);
		boolean landingPushes = STANCE_PUSH.equals(front.stance);
		boolean worldPushes = true; // under the share rule only a push back holds a push (2026-10-28)
		// sized against the swarm at FULL cover whatever it stands at now (the user, Sindria 2026-10-24:
		// marines that met the ask when handed over were short by the time the swarm had dug in for
		// 30 days and the ask had grown) - a met ask stays met; a pushing swarm is exposed, so the
		// ask over-reads it until it digs in, which it does the moment the line turns
		float coverA = Math.max(1f, ThreatIncConfig.frontEntrenchDefenseBonus());
		float coverD = defenderCoverIfDugIn(front); // cover is by days, whatever the stance (2026-10-24)
		float garrison = colonyGarrison(market);
		float counter = Math.max(0f, ThreatIncConfig.marineCounterAttackMult());
		float staff = 1f; // the push is the force figure (2026-10-24)
		float hi = 1f;
		while (hi < 10000000f && reliefRate(a, coverA, coverD, garrison, perMarine, counter, staff, hi,
				landingPushes, worldPushes) > target) hi *= 2f;
		if (reliefRate(a, coverA, coverD, garrison, perMarine, counter, staff, hi, landingPushes, worldPushes) > target) {
			// out of reach at any number (marines that cannot counter-attack): enough to hold
			target = 0f;
			hi = 1f;
			while (hi < 10000000f && reliefRate(a, coverA, coverD, garrison, perMarine, counter, staff, hi,
					landingPushes, worldPushes) > 0f) hi *= 2f;
		}
		float lo = 0f;
		for (int i = 0; i < 40; i++) {
			float mid = (lo + hi) / 2f;
			if (reliefRate(a, coverA, coverD, garrison, perMarine, counter, staff, mid, landingPushes, worldPushes) > target) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		return hi;
	}

	/** The line's rate with {@code armed} marines banked on the world ({@link #reliefNeed(MarketAPI, float)}). */
	private static float reliefRate(float a, float coverA, float coverD, float garrison, float perMarine,
			float counter, float staff, float armed, boolean landingPushes, boolean worldPushes) {
		float manned = mannedGarrison(garrison, armed); // the defences this many marines man (2026-10-28)
		float d = manned + perMarine * armed;
		float pressure = (manned + perMarine * counter * armed) * staff;
		return lineRateOf(a, coverA, d, coverD, pressure, landingPushes, worldPushes);
	}

	/**
	 * HOME UNDER ATTACK (the user, 2026-10-20, after hw240c: "take care of your own back yard first before thinking
	 * of leaving"): the faction's own worlds with a Threat army on the surface - the colonies whose relief outranks
	 * every staging convoy, play and front run while one stands ({@link #homeUnderAttack}).
	 */
	public static List<MarketAPI> invadedWorlds(String factionId) {
		List<MarketAPI> out = new ArrayList<MarketAPI>();
		if (factionId == null) return out;
		for (MarketAPI m : ThreatReserves.marketsOf(factionId)) {
			if (m.getPrimaryEntity() == null || isHiveTarget(m)) continue;
			if (isThreatOwned(getFront(m.getId()))) out.add(m);
		}
		return out;
	}

	/**
	 * Whether the faction has an own world under a Threat army (knob homeUnderAttack): then ThreatConvoys relieves
	 * it first and stages nothing, every colony gives down to its floor (ThreatConvoys.reliefSpare) and ThreatPlays
	 * starts and stages no play. hw240c: the Hegemony held 9,326 marines at Ragnar Complex, committed to its own
	 * landing plays, while Jangala and Yama fell with 200-370 each.
	 */
	public static boolean homeUnderAttack(String factionId) {
		return ThreatIncConfig.homeUnderAttack() && !invadedWorlds(factionId).isEmpty();
	}

	/**
	 * Whether the faction's home fight is in this system: an own world of its stands under a Threat army there.
	 * The one attack {@link #homeUnderAttack} lets through (the user, 2026-10-26: the Diktat, Volturn invaded and
	 * Sindria a hive, struck systems light years away) - ThreatPlays.invade, the planner's siege gates.
	 */
	public static boolean homeFightIn(String factionId, String systemId) {
		if (factionId == null || systemId == null) return false;
		for (MarketAPI m : invadedWorlds(factionId)) {
			if (m.getStarSystem() != null && systemId.equals(m.getStarSystem().getId())) return true;
		}
		return false;
	}


	/**
	 * Heavy armaments an own colony under a Threat army wants banked beside its marines
	 * (2026-10-17): frontResupplyDays of the burn its defenders will have once the marines
	 * {@link #reliefNeed} asks for stand with them - engaged no further than the army's own
	 * headcount - less what it holds. 0 with no Threat front, on a hive, or with the burn off.
	 */
	public static float defenderArmsNeed(MarketAPI market) {
		return Math.max(0f, defenderArmsWant(market) - ThreatReserves.stock(market.getId(), Commodities.HAND_WEAPONS));
	}

	/** The heavy armaments an own colony under a Threat army wants banked: frontResupplyDays of its defenders' burn once relief stands with them (the figure {@link #defenderArmsNeed} nets against stock); 0 with no Threat front, on a hive, or with the burn off. */
	public static float defenderArmsWant(MarketAPI market) {
		if (market == null || isHiveTarget(market) || !ThreatIncConfig.defenderArmsEnabled()) return 0f;
		GroundFront front = getFront(market.getId());
		if (!isThreatOwned(front)) return 0f;
		float standing = ThreatReserves.stock(market.getId(), Commodities.MARINES) + reliefNeed(market);
		float burn = dailyUpkeep(standing);
		if (defendersPush(front, market)) burn *= ThreatIncConfig.frontPushUpkeepMult(); // the bank holds while they push (2026-10-24)
		return burn * ThreatIncConfig.frontResupplyDays();
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
	/**
	 * SAVE COMPATIBILITY ONLY (2026-10-27): the listener the swarm bombard holds put on every swarm fleet
	 * until v3 (HoldRefund, deleted with the holds in phase 1b). Saves made before 22:24 carry one per
	 * fleet and XStream needs the class to load them; it does nothing and arms nothing. Keep until the
	 * next save-breaking release.
	 */
	public static class HoldRefund implements com.fs.starfarer.api.campaign.listeners.FleetEventListener {
		public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
				com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason reason, Object param) {
		}

		public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
				com.fs.starfarer.api.campaign.BattleAPI battle) {
		}
	}
}
