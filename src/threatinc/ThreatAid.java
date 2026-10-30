package threatinc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * PLAYER AID (docs/player-aid.md sections 2 and 3): what the player sends
 * from their own colonies to other factions, and what they earn for it.
 *
 * <p>Every aid is a real fleet launched from a player colony through the war
 * board - a guard task force ({@link ThreatFleetOrders}, flagged aid) or a
 * convoy ({@link ThreatConvoys}, flagged aid) - built from what that colony
 * can field ({@link ThreatAidCapacity}) and stock ({@link ThreatReserves}).
 * The source is picked here: the closest player colony with a military
 * structure in reach that covers the whole need, else the one that can send
 * the most. Credits pay for the fleet (per point and per light-year), never
 * for capability. The player's personal fleet takes no part in board aid; it
 * has its own route, the station commander ({@link ThreatincAidCMD}).
 *
 * <p>Reputation with the receiving faction is earned on arrival: by the
 * goods' value at the receiving market's price for a delivery, flat amounts
 * for a guard on station and a guard that serves its term, and a split among
 * every faction in a hive's strike reach for a task force at its door - each delivery weighted
 * by the purpose it serves on the ledger of needs ({ ThreatNeeds}, 2026-10-23).
 */
public class ThreatAid {

	/** What one aid button would do right now: source, size, price - or why not. */
	public static class Quote {
		public MarketAPI source;
		/** Combat fleet points of a task force. */
		public float fp;
		/** Ledger points the fleet holds. */
		public float points;
		/** Whole-fleet points the source has free to send - the pool {@link #points} is drawn from. */
		public float free;
		/** Fleets the force sails as (one, or several at the Med / Max tiers). */
		public int fleets = 1;
		/** {fuel, supplies} the force draws from its source (ThreatFleetOrders.forceProvisions), less what the staged forces folding in bring. */
		public float[] provisions;
		/** {fuel, supplies} the task forces folding into the force bring with them (ThreatAidCapacity.foldedProvisions). */
		public float[] carried;
		public String commodityId;
		/** Convoy load in ThreatReserves.COMMODITIES order. */
		public float[] load;
		public int quantity;
		public int need;
		public float distLY;
		/** Why not, one fact per line (a notice's bullets, a tooltip's lines); null when the quote is good. */
		public ThreatNotice.Reason reason;
		/** The need this aid serves (ThreatNeeds.HOLD and the rest); null for a Defend or Strike. */
		public String purpose;
		/** A second convoy of ship hulls from the player's hull port, when the goods donor is not the port ({@link ThreatAid#quoteAid} at the Hulls lever); null otherwise. */
		public MarketAPI hullSource;
		/** Units the hull convoy carries. */
		public int hullUnits;
		/** What a force sized for an outcome is sized against, one line for its prompt ("Against N fleet points of Defense Swarms seen"); null for a hull-sized force. */
		public String sized;

		public boolean ok() {
			return reason == null && source != null;
		}
	}

	// ------------------------------------------------------------------
	// who may receive
	// ------------------------------------------------------------------

	/** Why the player cannot aid this faction, or null if they can. */
	public static ThreatNotice.Reason canAid(FactionAPI faction) {
		if (faction == null) return ThreatNotice.Reason.of("No faction");
		if (!ThreatAidCapacity.enabled()) {
			return ThreatNotice.Reason.of("Player aid is disabled in the mod settings");
		}
		if (faction.isPlayerFaction()) {
			return ThreatNotice.Reason.of("That is your own faction - order its fleets directly");
		}
		ThreatNotice.Reason self = playerNotMobilised();
		if (self != null) return self;
		if (!ThreatWarState.isAtWar(faction)) {
			return ThreatNotice.Reason.of("%s is not mobilised", ThreatNotice.faction(faction))
					.line("The Threat has not struck it");
		}
		FactionAPI player = Global.getSector().getPlayerFaction();
		if (faction.isHostileTo(player)) {
			return ThreatNotice.Reason.of("%s is at war with you", ThreatNotice.faction(faction))
					.line("Its patrols would fire on anything you sent");
		}
		// any faction not at war with the player takes aid (the user, 2026-10-31: "player should be able to send aid
		// to unfavourable factions to increase rep, they just wont send back unless favourable"); aidMinRelation
		// stays a knob, -1 by default
		if (faction.getRelToPlayer().getRel() < ThreatIncConfig.aidMinRelation()) {
			return ThreatNotice.Reason.of("%s will not accept your help at this standing",
					ThreatNotice.faction(faction));
		}
		return null;
	}

	/** Why the player may send no aid at all: not mobilised. Null if they may. */
	public static ThreatNotice.Reason playerNotMobilised() {
		if (ThreatWarState.isAtWar(Factions.PLAYER)) return null;
		return ThreatNotice.Reason.of("Your faction is not mobilised");
	}

	// ------------------------------------------------------------------
	// sources
	// ------------------------------------------------------------------

	/**
	 * Player colonies with a military structure and a Waystation, nearest
	 * the spot first. No range (2026-09-05, the user's call): a far colony
	 * may send, and pays in fuel drawn by the distance and in the time the
	 * fleet takes to get there and back. Under the hull pool a colony with
	 * hulls to send and a Waystation counts too, military structure or not:
	 * the hull port keeps the built navy, and a navy of 2,111 points sat at
	 * Arkoman unable to sail while the Fleet button read "no colony of yours
	 * can field it" (2026-10-11).
	 */
	public static List<MarketAPI> sources(final Vector2f hyperLoc) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		if (hyperLoc == null) return result;
		for (MarketAPI m : Misc.getPlayerMarkets(false)) {
			if (m.getStarSystem() == null || m.getPrimaryEntity() == null) continue;
			if (!IncursionManager.isBase(m) && !hullsToSend(m)) continue;
			result.add(m);
		}
		Collections.sort(result, new Comparator<MarketAPI>() {
			public int compare(MarketAPI a, MarketAPI b) {
				return Float.compare(Misc.getDistanceLY(a.getStarSystem().getLocation(), hyperLoc),
						Misc.getDistanceLY(b.getStarSystem().getLocation(), hyperLoc));
			}
		});
		return result;
	}

	/**
	 * Combat points a task force from this colony sails with: EVERYTHING it
	 * has free, staged task forces included (2026-09-05 evening, the user's
	 * call - a task force capped at guardFleetFP "is a pretty shit fleet and
	 * will never have any capitals"). guardFleetFP is now only the NPC size
	 * and the bar for picking a source first (pickTaskForceSource).
	 */
	public static float taskForceFP(MarketAPI base) {
		return taskForceFP(base, false);
	}

	/** As above; {@code ownOnly} sizes by the colony's own free points - a guard over itself, which nothing staged there folds into. */
	public static float taskForceFP(MarketAPI base, boolean ownOnly) {
		if (base == null) return 0f;
		if (!ThreatAidCapacity.enabled()) return ThreatIncConfig.guardFleetFP();
		float free = Math.max(0f, (ownOnly ? ThreatAidCapacity.ownFreeFP(base) : ThreatAidCapacity.freeFP(base))
				/ ThreatAidCapacity.TASK_FORCE_HULL_MULT);
		// one order is one fleet, and vanilla builds at most maxShipsInAIFleet ships of it: the ask stops
		// at the size its yards were seen to build (2026-10-11: the whole free pool asked, 302 built)
		return Math.min(free, ThreatSoftening.fleetCapFor(base, base.getFactionId(), free));
	}

	/**
	 * Combat points the player's force at the board's tier asks for in all (the colony table's
	 * Amount for Fleet and Defend, the hives table's Force for Hunt and Support; the user,
	 * 2026-10-11: "min med max should apply to all"): one fleet (0, {@link #taskForceFP}), half
	 * the hulls free (1), every hull free (2). Built as several fleets of one fleet's size each
	 * (ThreatFleetOrders.buildPlayerForce).
	 */
	public static float forceFP(MarketAPI base, boolean ownOnly, int tier) {
		float one = taskForceFP(base, ownOnly);
		if (tier <= 0 || base == null || !ThreatAidCapacity.enabled()) return one;
		float all = freePoints(base, ownOnly) / ThreatAidCapacity.TASK_FORCE_HULL_MULT;
		return tier == 1 ? Math.max(one, all / 2f) : Math.max(one, all);
	}

	/** {@link #forceFP} in whole-fleet points: the figure the board quotes for the force. */
	public static float forcePoints(MarketAPI base, boolean ownOnly, int tier) {
		float one = taskForcePoints(base, ownOnly);
		if (tier <= 0 || base == null || !ThreatAidCapacity.enabled()) return one;
		float all = freePoints(base, ownOnly);
		return tier == 1 ? Math.max(one, all / 2f) : Math.max(one, all);
	}

	/** Fleets a force of {@code points} sails as: one of a fleet's size each (ThreatSoftening.builtCapPoints), at least one. */
	public static int forceFleets(MarketAPI base, float points) {
		if (base == null || points <= 0f) return 1;
		float cap = ThreatSoftening.builtCapPoints(base.getFactionId());
		if (cap >= Float.MAX_VALUE || cap <= 0f) return 1;
		return Math.max(1, (int) Math.ceil(points / cap - 0.05f));
	}

	/**
	 * Combat fleet points the board's Hunt asks for at its Force tier against the hive system as
	 * the player's reports show it (the user, 2026-10-24: "min med max for aggressive actions should
	 * be sized for outcomes ... hunt med would be equal to known force"): Min beats the weakest
	 * garrison seen by the siege's margin (ThreatSoftening.musterFloorFP, the rule a hunt moves on
	 * by), Med matches every swarm point seen in the system (ThreatIntel.systemFP), Max that by
	 * npcSiegeOrbitMargin; each at least the one below. 0 with no swarm seen there.
	 */
	public static float huntAskFP(StarSystemAPI hive, int tier) {
		if (hive == null) return 0f;
		String observer = ThreatIntel.observerOf(Global.getSector().getPlayerFaction());
		MarketAPI target = ThreatSoftening.huntTarget(observer, hive.getId());
		if (target == null) return 0f;
		float min = ThreatSoftening.musterFloorFP(observer, target);
		if (tier <= 0) return min;
		float seen = ThreatIntel.systemFP(observer, hive.getId());
		float med = Math.max(min, seen);
		if (tier == 1) return med;
		return Math.max(med, seen * ThreatSoftening.margin());
	}

	/** The swarm a Hunt at the tier is sized against, for its prompt: "Against the N fleet points of the weakest Defense Swarm seen" (Min) or "... of Defense Swarms seen in the system". */
	public static String huntSized(StarSystemAPI hive, int tier) {
		if (hive == null) return null;
		String observer = ThreatIntel.observerOf(Global.getSector().getPlayerFaction());
		MarketAPI target = ThreatSoftening.huntTarget(observer, hive.getId());
		if (target == null) return null;
		if (tier <= 0) {
			return "Against the " + Misc.getWithDGS((int) ThreatIntel.worldFP(observer, target))
					+ " fleet points of the weakest Defense Swarm seen, over " + target.getName() + ", by x"
					+ ThreatFactionView.trimZero(ThreatSoftening.margin()) + ".";
		}
		return "Against the " + Misc.getWithDGS((int) ThreatIntel.systemFP(observer, hive.getId()))
				+ " fleet points of Defense Swarms seen in the system" + (tier >= 2 ? ", by x"
				+ ThreatFactionView.trimZero(ThreatSoftening.margin()) : "") + ".";
	}

	/**
	 * Combat fleet points the board's Support or Defend over a world asks for at its Force tier
	 * (the user, 2026-10-24: tiers sized for outcomes): the swarm over the world - a hive's as the
	 * player's reports show it (ThreatIntel.worldFP), an own world's as it stands
	 * (ThreatGroundFronts.pointsNear) - matched at Min, by orbitTierMedMargin at Med,
	 * orbitTierMaxMargin at Max. One fleet ({@link #taskForceFP} from {@code source}) with no
	 * swarm over it: there is nothing to outweigh.
	 */
	public static float orbitAskFP(MarketAPI world, MarketAPI source, int tier) {
		float swarm = orbitSwarmFP(world);
		if (swarm <= 0f) return taskForceFP(source, false);
		float margin = tier >= 2 ? ThreatIncConfig.orbitTierMaxMargin()
				: tier == 1 ? ThreatIncConfig.orbitTierMedMargin() : 1f;
		return swarm * Math.max(1f, margin);
	}

	/** The swarm over the world a Support or Defend is sized against: a hive's as the player's reports show it, an own world's live. */
	public static float orbitSwarmFP(MarketAPI world) {
		if (world == null) return 0f;
		if (ThreatGroundFronts.isHiveTarget(world)) {
			return ThreatIntel.worldFP(ThreatIntel.observerOf(Global.getSector().getPlayerFaction()), world);
		}
		return ThreatGroundFronts.pointsNear(world, Factions.THREAT, true);
	}

	/** The swarm a Support or Defend at the tier is sized against, for its prompt; null with none over the world. */
	public static String orbitSized(MarketAPI world, int tier) {
		float swarm = orbitSwarmFP(world);
		if (swarm <= 0f) return null;
		float margin = tier >= 2 ? ThreatIncConfig.orbitTierMaxMargin()
				: tier == 1 ? ThreatIncConfig.orbitTierMedMargin() : 1f;
		return "Against the " + Misc.getWithDGS((int) swarm) + " fleet points of the swarm over " + world.getName()
				+ (margin > 1f ? ", by x" + ThreatFactionView.trimZero(margin) : "") + ".";
	}

	/** Whole-fleet points the base has free to send - the pool a task force is drawn from ({@code ownOnly}: a guard over itself). */
	public static float freePoints(MarketAPI base, boolean ownOnly) {
		if (base == null) return 0f;
		if (!ThreatAidCapacity.enabled()) return ThreatAidCapacity.taskForcePoints(ThreatIncConfig.guardFleetFP());
		return Math.max(0f, ownOnly ? ThreatAidCapacity.ownFreeFP(base) : ThreatAidCapacity.freeFP(base));
	}

	/** "about 302 of the 2,365 fleet points free", or "about 302 fleet points" when the order takes all that is free. */
	public static String forceText(float points, float free) {
		return forceText(points, free, 1);
	}

	/** As above, "in N fleets" appended for a force of several. */
	public static String forceText(float points, float free, int fleets) {
		String p = Misc.getWithDGS((int) points);
		String tail = fleets > 1 ? " in " + fleets + " fleets" : "";
		if (free > points + 1f) return "about " + p + " of the " + Misc.getWithDGS((int) free) + " fleet points free" + tail;
		return "about " + p + " fleet points" + tail;
	}

	/** {@link #forceText} as a prompt's format and highlights: {format, highlight...}. */
	public static String[] forcePrompt(float points, float free) {
		return forcePrompt(points, free, 1);
	}

	/** As above, "in N fleets" appended (highlighted) for a force of several. */
	public static String[] forcePrompt(float points, float free, int fleets) {
		java.util.List<String> out = new ArrayList<String>();
		if (free > points + 1f) {
			out.add("about %s of the %s fleet points free");
			out.add(Misc.getWithDGS((int) points));
			out.add(Misc.getWithDGS((int) free));
		} else {
			out.add("about %s fleet points");
			out.add(Misc.getWithDGS((int) points));
		}
		if (fleets > 1) {
			out.set(0, out.get(0) + " in %s fleets");
			out.add("" + fleets);
		}
		return out.toArray(new String[out.size()]);
	}

	/**
	 * As {@link #taskForceFP} but in whole-fleet points, support hulls
	 * included - the FP column's unit, and the only figure the board quotes
	 * for a task force (a prompt that said 199 beside a cell that said 239
	 * was the combat points, 2026-09-05 evening).
	 */
	public static float taskForcePoints(MarketAPI base, boolean ownOnly) {
		float points = ThreatAidCapacity.taskForcePoints(taskForceFP(base, ownOnly));
		// never more than one fleet of the faction's was seen to sail with (ThreatSoftening.builtCapPoints)
		return base != null ? Math.min(points, ThreatSoftening.builtCapPoints(base.getFactionId())) : points;
	}

	/**
	 * The colony to send a task force from: the closest with at least
	 * guardFleetFP free (it sails with all it has free), else the one with
	 * the most free points if that makes at least aidGuardMinFP, else null.
	 */
	public static MarketAPI pickTaskForceSource(Vector2f hyperLoc) {
		return pickTaskForceSource(hyperLoc, null);
	}

	/**
	 * As above; {@code self} is the colony being guarded when it is one of the player's own: its
	 * own free hulls made real over it when they field a full guard, else the nearest other colony
	 * that can, else whichever has the most.
	 */
	public static MarketAPI pickTaskForceSource(Vector2f hyperLoc, MarketAPI self) {
		MarketAPI best = null;
		float bestFP = 0f;
		// A GUARD OVER THE PLAYER'S OWN COLONY IS ITS OWN HULLS FIRST (the user, 2026-10-29, at
		// Haven's Fleet button with Haven's Hulls cell full and Arkoman's 0: "it says it's sending
		// fleets from ark???"). From 2026-10-11 to 2026-10-29 the nearest OTHER colony came first, a
		// Fleet order being the way hulls reached a base; the Supplies button's Hulls lever moves
		// hulls between colonies now (ThreatConvoys.stageLoad, 2026-10-28), and the task forces on
		// station at Arkoman counted as its free points while its Hulls cell read 0, so Haven's
		// order sailed - or was refused - from a colony the player read as empty.
		if (self != null && taskForceFP(self, true) >= ThreatIncConfig.guardFleetFP()) return self;
		// Past its own hulls: staging bases first (IncursionManager.isBase), nearest first, then the
		// colonies that only hold hulls (the hull port): a hunt sails from the base the task forces
		// were staged at, not from the port beside it (the user, 2026-10-11 evening: "staging base
		// still not being used", a Max hunt quoting 1,045 from Arkoman beside Haven's 605 staged)
		List<MarketAPI> candidates = sources(hyperLoc);
		for (int pass = 0; pass < 2; pass++) {
			for (MarketAPI m : candidates) {
				if (m == self) continue;
				if (IncursionManager.isBase(m) != (pass == 0)) continue;
				float fp = taskForceFP(m, false);
				if (fp >= ThreatIncConfig.guardFleetFP()) return m;
				if (fp > bestFP) {
					bestFP = fp;
					best = m;
				}
			}
		}
		if (self != null) {
			float fp = taskForceFP(self, true);
			if (fp >= ThreatIncConfig.guardFleetFP()) return self;
			if (fp > bestFP) {
				bestFP = fp;
				best = self;
			}
		}
		if (best != null && bestFP >= ThreatIncConfig.aidGuardMinFP()) return best;
		return null;
	}

	/** Under the hull pool: the colony holds hulls (its own share, or task forces staged over it) and a Waystation to sail them from. */
	protected static boolean hullsToSend(MarketAPI m) {
		return ThreatHulls.enabled() && m != null && ThreatReserves.hasDepot(m)
				&& (ThreatAidCapacity.capacityFP(m) > 0f || ThreatAidCapacity.freeFP(m) > 0f);
	}

	/** Why no colony can send a task force: none qualifies, or each one's free points. */
	protected static ThreatNotice.Reason noSourceReason(Vector2f hyperLoc) {
		return noSourceReason(hyperLoc, null);
	}

	/**
	 * As above for a guard over the player's own colony {@code self}: the task forces already on
	 * station there are not "free" to be sent to it (the user, 2026-10-11 evening, at "No colony
	 * of yours can field it / Haven has 605 fleet points free": "so i cant use the 605 hulls at
	 * haven ever"), so its line says they are here already; the other colonies' lines as before.
	 */
	protected static ThreatNotice.Reason noSourceReason(Vector2f hyperLoc, MarketAPI self) {
		List<MarketAPI> bases = sources(hyperLoc);
		if (bases.isEmpty()) {
			return ThreatNotice.Reason.of(ThreatHulls.enabled()
					? "No colony of yours has both hulls and a Waystation"
					: "None of your colonies has both a military structure "
					+ "(Patrol HQ, Military Base or High Command) and a Waystation");
		}
		ThreatNotice.Reason reason = self != null
				? ThreatNotice.Reason.of("No colony of yours can send a task force to %s", ThreatNotice.market(self))
				: ThreatNotice.Reason.of("No colony of yours can field it");
		for (MarketAPI m : bases) {
			if (m == self) {
				float staged = ThreatAidCapacity.stagedFP(m);
				float own = Math.max(0f, ThreatAidCapacity.ownFreeFP(m));
				if (staged > 0.5f) reason.line("%s fleet points are on station over %s already",
						(int) staged, ThreatNotice.market(m));
				if (own > 0.5f) reason.line("%s has %s of its own fleet points free",
						ThreatNotice.market(m), (int) own);
				continue;
			}
			reason.line("%s can sail %s fleet points", ThreatNotice.market(m),
					(int) Math.max(0f, ThreatAidCapacity.freeFP(m)));
		}
		return reason;
	}

	// ------------------------------------------------------------------
	// quotes
	// ------------------------------------------------------------------

	public static Quote quoteDefend(MarketAPI target) {
		return quoteDefend(ThreatBases.of(target), 0);
	}

	/** As above at the board's Force tier. */
	public static Quote quoteDefend(MarketAPI target, int tier) {
		return quoteDefend(ThreatBases.of(target), tier);
	}

	/** As above for either kind of base: a colony, or the player's outpost. */
	public static Quote quoteDefend(ThreatBases.Base target) {
		return quoteDefend(target, 0);
	}

	/** As above at the board's Force tier. */
	public static Quote quoteDefend(ThreatBases.Base target, int tier) {
		Quote q = new Quote();
		if (target == null || target.starSystem() == null) {
			q.reason = ThreatNotice.Reason.of("No target");
			return q;
		}
		return quoteTaskForce(q, target.hyperLoc(), target.name(),
				target.market != null && target.market.isPlayerOwned() ? target.market : null, tier);
	}

	public static Quote quoteStrike(StarSystemAPI hive) {
		return quoteStrike(hive, 0);
	}

	/** As above at the board's Force tier. */
	public static Quote quoteStrike(StarSystemAPI hive, int tier) {
		Quote q = new Quote();
		if (hive == null) {
			q.reason = ThreatNotice.Reason.of("No target");
			return q;
		}
		// a hunt needs something to hunt (2026-09-24, Hunt replaced Intercept):
		// swarms the player's reports show there (ThreatIntel)
		if (ThreatSoftening.huntTarget(ThreatIntel.observerOf(Global.getSector().getPlayerFaction()),
				hive.getId()) == null) {
			q.reason = ThreatNotice.Reason.of("No Defense Swarms in the %s to hunt",
					hive.getNameWithLowercaseTypeShort());
			return q;
		}
		// sized for the outcome (huntAskFP), from the nearest colony that can field it
		q = quoteTaskForce(q, hive.getLocation(), "the " + hive.getNameWithLowercaseType(), null,
				huntAskFP(hive, tier), "hunt the swarms seen");
		q.sized = huntSized(hive, tier);
		return q;
	}

	/** The board's Support or Defend over a world at its Force tier: a task force sized to the swarm over it ({@link #orbitAskFP}) from the nearest colony that can field it. */
	public static Quote quoteOrbit(MarketAPI world, String kind, int tier) {
		Quote q = new Quote();
		if (world == null) {
			q.reason = ThreatNotice.Reason.of("No target");
			return q;
		}
		MarketAPI source = pickTaskForceSource(world.getLocationInHyperspace(), null);
		q = quoteTaskForce(q, world.getLocationInHyperspace(), world.getName(), null,
				orbitAskFP(world, source, tier), ThreatFleetOrders.orbitVerb(kind) + " " + world.getName()
				+ " against its swarm");
		q.sized = orbitSized(world, tier);
		return q;
	}

	/**
	 * As {@link #quoteTaskForce(Quote, Vector2f, String, MarketAPI, int)} for a force of {@code askFP}
	 * combat points (an outcome's size: huntAskFP, orbitAskFP), from the nearest colony whose free
	 * hulls field it ({@link #pickTaskForceSource(Vector2f, MarketAPI, float)}); refused with the
	 * figures when none can - the whole job or nothing, as a bombing's fuel is (the user,
	 * 2026-10-11). At least the smallest task force (aidGuardMinFP).
	 */
	protected static Quote quoteTaskForce(Quote q, Vector2f hyperLoc, String what, MarketAPI self, float askFP,
			String outcome) {
		float ask = Math.max(askFP, ThreatIncConfig.aidGuardMinFP());
		q.source = pickTaskForceSource(hyperLoc, self, ask);
		if (q.source == null) {
			q.reason = noSourceReason(hyperLoc, self).line("A task force needs at least %s fleet points",
					(int) (ThreatIncConfig.aidGuardMinFP() * ThreatAidCapacity.TASK_FORCE_HULL_MULT));
			return q;
		}
		boolean own = q.source == self;
		float all = forceFP(q.source, own, 2);
		if (ask > all + 0.5f) {
			q.reason = ThreatNotice.Reason.of("%s has %s fleet points free", ThreatNotice.market(q.source),
					Misc.getWithDGS((int) Math.max(0f, freePoints(q.source, own))))
					.line("To " + outcome + " takes %s", Misc.getWithDGS((int) Math.ceil(ThreatAidCapacity.taskForcePoints(ask))));
			return q;
		}
		q.fp = ask;
		q.points = Math.min(freePoints(q.source, own), ThreatAidCapacity.taskForcePoints(ask));
		q.free = freePoints(q.source, own);
		q.fleets = forceFleets(q.source, q.points);
		q.distLY = Misc.getDistanceLY(q.source.getStarSystem().getLocation(), hyperLoc);
		// an own guard folds nothing: null, as buildPlayerForce's gate and the prompt pass it
		q.carried = own ? null : ThreatAidCapacity.foldedProvisions(q.source, q.points);
		q.provisions = ThreatFleetOrders.forceProvisions(q.source, q.fp, hyperLoc, q.carried);
		ThreatNotice.Reason unpaid = ThreatFleetOrders.provisionsShort(q.source, q.fp, hyperLoc, q.carried);
		if (unpaid != null) q.reason = unpaid;
		return q;
	}

	/**
	 * The colony to send a force of {@code askFP} combat points from: the colony's own hulls for a
	 * guard over itself, else the nearest whose hulls free field it all (staging bases first, as
	 * {@link #pickTaskForceSource(Vector2f, MarketAPI)} orders them), else that pick - whose free
	 * points the refusal then quotes.
	 */
	public static MarketAPI pickTaskForceSource(Vector2f hyperLoc, MarketAPI self, float askFP) {
		if (self != null && forceFP(self, true, 2) >= askFP) return self;
		List<MarketAPI> candidates = sources(hyperLoc);
		for (int pass = 0; pass < 2; pass++) {
			for (MarketAPI m : candidates) {
				if (m == self) continue;
				if (IncursionManager.isBase(m) != (pass == 0)) continue;
				if (forceFP(m, false, 2) >= askFP) return m;
			}
		}
		return pickTaskForceSource(hyperLoc, self);
	}

	protected static Quote quoteTaskForce(Quote q, Vector2f hyperLoc, String what, MarketAPI self, int tier) {
		q.source = pickTaskForceSource(hyperLoc, self);
		if (q.source == null) {
			q.reason = noSourceReason(hyperLoc, self).line("A task force needs at least %s fleet points",
					(int) (ThreatIncConfig.aidGuardMinFP() * ThreatAidCapacity.TASK_FORCE_HULL_MULT));
			return q;
		}
		boolean own = q.source == self;
		q.fp = forceFP(q.source, own, tier);
		q.points = forcePoints(q.source, own, tier);
		q.free = freePoints(q.source, own);
		q.fleets = forceFleets(q.source, q.points);
		q.distLY = Misc.getDistanceLY(q.source.getStarSystem().getLocation(), hyperLoc);
		// the provisions the force draws, and the gate on them: a force its source cannot fuel and
		// supply is not offered (the user, 2026-10-11); an own guard folds nothing (null)
		q.carried = own ? null : ThreatAidCapacity.foldedProvisions(q.source, q.points);
		q.provisions = ThreatFleetOrders.forceProvisions(q.source, q.fp, hyperLoc, q.carried);
		ThreatNotice.Reason unpaid = ThreatFleetOrders.provisionsShort(q.source, q.fp, hyperLoc, q.carried);
		if (unpaid != null) q.reason = unpaid;
		return q;
	}

	/**
	 * A convoy at the colonies table's levers (the user, 2026-10-28: "aid and request etc uses
	 * all these levers as well"): from the player's colony whose {@link ThreatConvoys#leverLoad}
	 * - each lever's share of its spare - is worth the most (ThreatConvoys.cargoValue), any
	 * colony (a convoy needs no military structure or Waystation to sail). Ship hulls at the
	 * Hulls lever sail out of the player's built navy from their hull port: aboard when that is
	 * the donor, else as a second convoy ({@link Quote#hullSource}). The quote's purpose is the
	 * colony's most urgent need, for the notice; standing follows what lands
	 * (ThreatNeeds.purposeWeights). Until 2026-10-28 it served the most urgent need at the
	 * Amount ladder's tier.
	 */
	public static Quote quoteAid(MarketAPI target, int[] levers) {
		Quote q = new Quote();
		if (target == null || target.getStarSystem() == null) {
			q.reason = ThreatNotice.Reason.of("No target");
			return q;
		}
		ThreatNeeds.Need need = ThreatNeeds.mostUrgent(ThreatNeeds.of(target), true);
		if (need != null && need.suspended) {
			q.reason = ThreatNotice.Reason.of("The swarm holds the orbit of %s: no convoy lands", ThreatNotice.market(target))
					.line("Defend lifts it; your own fleet can hand over at the dock");
			return q;
		}
		q.purpose = need != null ? need.purpose : "war materiel";
		if (ThreatConvoys.allNone(levers)) {
			q.reason = ThreatNotice.Reason.of("Every lever is at None");
			return q;
		}
		String me = Global.getSector().getPlayerFaction().getId();
		MarketAPI port = ThreatHulls.hullPort(me);
		int hullUnits = levers.length > 4
				? (int) Math.floor(ThreatConvoys.share(levers[4]) * ThreatHulls.sendableUnits(me)) : 0;
		Vector2f loc = target.getLocationInHyperspace();
		MarketAPI best = null;
		float[] bestLoad = null;
		float bestValue = 0f;
		List<MarketAPI> donors = new ArrayList<MarketAPI>();
		for (MarketAPI m : Misc.getPlayerMarkets(false)) {
			if (m.getStarSystem() != null && m.getPrimaryEntity() != null) donors.add(m);
		}
		for (MarketAPI m : donors) {
			float[] load = ThreatConvoys.leverLoad(m, levers, false);
			if (hullUnits > 0 && m == port) load[4] = hullUnits;
			float value = ThreatConvoys.cargoValue(load);
			if (value <= bestValue) continue;
			best = m;
			bestLoad = load;
			bestValue = value;
		}
		if (best == null) {
			if (donors.isEmpty()) {
				q.reason = ThreatNotice.Reason.of("You have no colony to send from");
			} else {
				q.reason = ThreatNotice.Reason.of("No colony of yours can spare anything at the levers for %s",
						ThreatNotice.market(target));
				for (MarketAPI m : donors) {
					q.reason.line("%s has %s marines, %s heavy armaments, %s fuel and %s supplies spare, %s fleet points free",
							ThreatNotice.market(m), (int) ThreatConvoys.handSpare(m, Commodities.MARINES),
							(int) ThreatConvoys.handSpare(m, Commodities.HAND_WEAPONS),
							(int) ThreatConvoys.handSpare(m, Commodities.FUEL),
							(int) ThreatConvoys.handSpare(m, Commodities.SUPPLIES),
							(int) Math.max(0f, ThreatAidCapacity.ownFreeFP(m)));
				}
			}
			return q;
		}
		q.source = best;
		q.load = bestLoad;
		int first = firstGood(bestLoad);
		q.commodityId = first >= 0 ? ThreatReserves.AID_COMMODITIES[first] : Commodities.MARINES;
		q.quantity = first >= 0 ? Math.round(bestLoad[first]) : 0;
		q.points = ThreatAidCapacity.convoyPoints(bestLoad);
		q.distLY = Misc.getDistanceLY(best.getStarSystem().getLocation(), loc);
		if (hullUnits > 0 && port != null && port != best && ThreatConvoys.hullsIn(bestLoad) <= 0f) {
			q.hullSource = port;
			q.hullUnits = hullUnits;
		}
		return q;
	}

	/** The index of the first good a load carries a unit of (AID_COMMODITIES order), -1 for an empty load. */
	protected static int firstGood(float[] load) {
		if (load == null) return -1;
		for (int i = 0; i < load.length && i < ThreatReserves.AID_COMMODITIES.length; i++) {
			if (load[i] >= 1f) return i;
		}
		return -1;
	}

	/** The commodity's index in a load (ThreatReserves.AID_COMMODITIES order, ship hulls 5th); -1 for none. */
	public static int index(String commodityId) {
		for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length; i++) {
			if (ThreatReserves.AID_COMMODITIES[i].equals(commodityId)) return i;
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// dispatch (the board's buttons). No credits change hands: the fleet is
	// paid by the source colony's reserve and its capacity (decided
	// 2026-09-05, docs/player-aid.md section 2).
	// ------------------------------------------------------------------

	public static boolean dispatchDefend(MarketAPI target) {
		return dispatchDefend(target, 0);
	}

	/** As above at the board's Force tier. */
	public static boolean dispatchDefend(MarketAPI target, int tier) {
		if (target == null) return false;
		ThreatNotice.Reason blocked = canAid(target.getFaction());
		if (blocked != null) {
			ThreatNotice.titled("Defence Refused").bad().icon(Global.getSector().getPlayerFaction())
					.lines(blocked).send();
			return false;
		}
		Quote q = quoteDefend(target, tier);
		if (!q.ok()) {
			ThreatNotice.titled("Defence Refused").bad().icon(Global.getSector().getPlayerFaction())
					.lines(q.reason).send();
			return false;
		}
		ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuard(
				Global.getSector().getPlayerFaction(), ThreatBases.of(target), q.source, true, tier);
		if (o == null) {
			ThreatNotice.titled("Defence Refused").bad().icon(Global.getSector().getPlayerFaction())
					.line("No task force could be raised at %s", ThreatNotice.market(q.source)).send();
			return false;
		}
		o.recipientFactionId = target.getFactionId();
		ThreatNotice.titled("Task Force Sails").icon(Global.getSector().getPlayerFaction())
				.line("From %s, %s fleet points", ThreatNotice.market(q.source), (int) q.points)
				.line("Defending %s for %s", ThreatNotice.market(target),
						ThreatNotice.faction(target.getFaction()))
				.send();
		return true;
	}

	public static boolean dispatchStrike(StarSystemAPI hive) {
		return dispatchStrike(hive, null);
	}

	/**
	 * As above, sent from {@code recipientFactionId}'s view: the hunt is listed
	 * there, where it can be recalled. With no recipient, a hunt sent before the
	 * player mobilised had no row in any view for its 60 days (rc1 review).
	 */
	public static boolean dispatchStrike(StarSystemAPI hive, String recipientFactionId) {
		return dispatchStrike(hive, recipientFactionId, 0);
	}

	/** As above at the board's Force tier. */
	public static boolean dispatchStrike(StarSystemAPI hive, String recipientFactionId, int tier) {
		if (hive == null) return false;
		if (playerNotMobilised() != null) return false;
		Quote q = quoteStrike(hive, tier);
		if (!q.ok()) {
			ThreatNotice.titled("Hunt Refused").bad().icon(Global.getSector().getPlayerFaction())
					.lines(q.reason).send();
			return false;
		}
		ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchHunt(
				Global.getSector().getPlayerFaction(), hive, q.source, true, tier);
		if (o == null) {
			ThreatNotice.titled("Hunt Refused").bad().icon(Global.getSector().getPlayerFaction())
					.line("No task force could be raised at %s", ThreatNotice.market(q.source)).send();
			return false;
		}
		if (recipientFactionId != null && !Factions.PLAYER.equals(recipientFactionId)) {
			o.recipientFactionId = recipientFactionId;
		}
		ThreatNotice.titled("Task Force Sails").icon(Global.getSector().getPlayerFaction())
				.line("From %s, %s fleet points", ThreatNotice.market(q.source), (int) q.points)
				.line("Hunting the Defense Swarms in the %s", hive.getNameWithLowercaseType())
				.send();
		return true;
	}

	/**
	 * REINFORCE an ally's colony under a Threat army (user, 2026-10-17: "theres also no way
	 * to aid allied worlds under attack like reinforce with more marines"): a convoy of marines
	 * and heavy armaments into its stockpile, sized by ThreatConvoys.reinforceWants at the
	 * board's Supply run tier, from the player colony that can spare and carry the most of
	 * it. Lands as any aid (standing on arrival), and holds off while the Threat blockades
	 * the world (ThreatConvoys.holdsOff).
	 */
	public static Quote quoteReinforce(MarketAPI target, int[] levers) {
		Quote q = new Quote();
		if (target == null || target.getStarSystem() == null) {
			q.reason = ThreatNotice.Reason.of("No target");
			return q;
		}
		if (!ThreatGroundFronts.isThreatOwned(ThreatGroundFronts.getFront(target.getId()))) {
			q.reason = ThreatNotice.Reason.of("No Threat army on %s", ThreatNotice.market(target));
			return q;
		}
		q.commodityId = Commodities.MARINES;
		q.purpose = ThreatNeeds.PUSH_BACK;
		return quoteBattle(target, levers, q);
	}

	/**
	 * A convoy of marines and heavy armaments into a colony's stockpile against the Threat army on
	 * it, at the fronts table's levers ({marines, arms}; the user, 2026-10-28): from the player
	 * colony whose {@link ThreatConvoys#leverLoad} is worth the most (any colony: a convoy needs no
	 * military structure or Waystation, as the board's own-colony convoys). With the Marines lever
	 * on, a colony whose marines are committed (its own front) or clipped out of the hold offers
	 * arms alone, which is no push (sd1 test 2026-10-24: "push back: 376 arms from Arkoman, of
	 * the 752 asked") - it is no donor. The quote's quantity is the marines, or the armaments
	 * with the Marines lever at None.
	 */
	protected static Quote quoteBattle(MarketAPI target, int[] levers, Quote q) {
		if (ThreatConvoys.allNone(levers)) {
			q.reason = ThreatNotice.Reason.of("Marines and Arms are both at None");
			return q;
		}
		boolean marines = levers[0] > 0;
		Vector2f loc = target.getLocationInHyperspace();
		MarketAPI best = null;
		float[] bestLoad = null;
		float bestValue = 0f;
		List<MarketAPI> donors = new ArrayList<MarketAPI>();
		for (MarketAPI m : Misc.getPlayerMarkets(false)) {
			if (m.getStarSystem() != null && m.getPrimaryEntity() != null) donors.add(m);
		}
		for (MarketAPI m : donors) {
			float[] fitted = ThreatConvoys.leverLoad(m, levers, false);
			if (marines && fitted[0] < 1f) continue;
			float value = ThreatConvoys.cargoValue(fitted[0], fitted[1], 0f, 0f);
			if (value <= bestValue) continue;
			best = m;
			bestLoad = fitted;
			bestValue = value;
		}
		if (best == null) {
			if (donors.isEmpty()) {
				q.reason = ThreatNotice.Reason.of("You have no colony to send from");
			} else {
				q.reason = ThreatNotice.Reason.of("No colony of yours can spare and carry %s for %s",
						marines ? "marines" : "heavy armaments", ThreatNotice.market(target));
				for (MarketAPI m : donors) {
					q.reason.line("%s has %s marines and %s heavy armaments spare, %s fleet points free",
							ThreatNotice.market(m), (int) ThreatConvoys.handSpare(m, Commodities.MARINES),
							(int) ThreatConvoys.handSpare(m, Commodities.HAND_WEAPONS),
							(int) Math.max(0f, ThreatAidCapacity.ownFreeFP(m)));
				}
			}
			return q;
		}
		q.source = best;
		q.load = bestLoad;
		q.quantity = (int) (marines ? bestLoad[0] : bestLoad[1]);
		q.points = ThreatAidCapacity.convoyPoints(bestLoad);
		q.distLY = Misc.getDistanceLY(best.getStarSystem().getLocation(), loc);
		return q;
	}

	/** The board's Reinforce aid order at the fronts table's levers: the convoy sails, or a refusal notice says why not. */
	public static boolean dispatchReinforce(MarketAPI target, int[] levers, Random random) {
		if (target == null) return false;
		ThreatNotice.Reason blocked = canAid(target.getFaction());
		if (blocked != null) {
			ThreatNotice.titled("Reinforcement Refused").bad().icon(Global.getSector().getPlayerFaction())
					.lines(blocked).send();
			return false;
		}
		Quote q = quoteReinforce(target, levers);
		if (!q.ok()) {
			ThreatNotice.titled("Reinforcement Refused").bad().icon(Global.getSector().getPlayerFaction())
					.lines(q.reason).send();
			return false;
		}
		ThreatConvoys.Convoy c = ThreatConvoys.dispatch(q.source, target,
				Global.getSector().getPlayerFaction(), q.load, random, target.getFactionId(), true);
		if (c == null) {
			ThreatNotice.titled("Reinforcement Refused").bad().icon(Global.getSector().getPlayerFaction())
					.line("No convoy could be raised at %s", ThreatNotice.market(q.source)).send();
			return false;
		}
		ThreatNotice.titled("Reinforcements Sail").icon(Global.getSector().getPlayerFaction())
				.line("From %s, %s marines and %s heavy armaments", ThreatNotice.market(q.source),
						Misc.getWithDGS((int) q.load[0]), Misc.getWithDGS((int) q.load[1]))
				.line("Bound for %s of the %s", ThreatNotice.market(target),
						ThreatNotice.faction(target.getFaction()))
				.send();
		return true;
	}

	/** The board's Aid order (2026-10-23; at the levers since 2026-10-28): the convoy sails - and a hull convoy from the port when the goods donor is not it - or a refusal notice says why not. */
	public static boolean dispatchAid(MarketAPI target, int[] levers, Random random) {
		if (target == null) return false;
		FactionAPI player = Global.getSector().getPlayerFaction();
		ThreatNotice.Reason blocked = canAid(target.getFaction());
		if (blocked != null) {
			ThreatNotice.titled("Aid Refused").bad().icon(player).lines(blocked).send();
			return false;
		}
		Quote q = quoteAid(target, levers);
		if (!q.ok()) {
			ThreatNotice.titled("Aid Refused").bad().icon(player).lines(q.reason).send();
			return false;
		}
		ThreatConvoys.Convoy c = ThreatConvoys.dispatch(q.source, target, player, q.load, random,
				target.getFactionId(), true);
		if (c == null) {
			ThreatNotice.titled("Aid Refused").bad().icon(player)
					.line("No convoy could be raised at %s", ThreatNotice.market(q.source)).send();
			return false;
		}
		ThreatNotice n = ThreatNotice.titled("Convoy Sails").icon(player)
				.line("From %s, %s", ThreatNotice.market(q.source),
						ThreatFactionView.cargoText(q.load[0], q.load[1], q.load[2], q.load[3], ThreatConvoys.hullsIn(q.load)));
		if (q.hullSource != null && q.hullUnits > 0) {
			float[] hulls = new float[ThreatReserves.AID_COMMODITIES.length];
			hulls[4] = q.hullUnits;
			ThreatConvoys.Convoy h = ThreatConvoys.dispatch(q.hullSource, target, player, hulls, random,
					target.getFactionId(), true);
			if (h != null) {
				n.line("From %s, %s", ThreatNotice.market(q.hullSource),
						ThreatFactionView.cargoText(0f, 0f, 0f, 0f, h.ships));
			}
		}
		n.line("Bound for %s of the %s: %s", ThreatNotice.market(target),
				ThreatNotice.faction(target.getFaction()), q.purpose).send();
		return true;
	}

	// ------------------------------------------------------------------
	// arrivals: reputation and contracts
	// ------------------------------------------------------------------

	/**
	 * One unit's price to the receiving market, times the quantity. A unit of ship hulls is
	 * vanilla's econ unit of the ships good (200 items) at the market's price, as any other good
	 * - or unitFP x hullCreditsPerFP with the knob set. Priced as warships for a day (2026-10-10):
	 * 3.67M credits for Nova Maxios's 4 units - "too much" (the user, 2026-10-11).
	 */
	public static float valueAt(MarketAPI market, String commodityId, float quantity) {
		if (market == null || commodityId == null || quantity <= 0f) return 0f;
		if (Commodities.SHIPS.equals(commodityId)) {
			float perFP = ThreatHulls.creditsPerFP();
			if (perFP > 0f) return quantity * ThreatHulls.unitFP() * perFP;
			quantity *= ThreatHulls.itemsPerUnit();
		}
		float perUnit = 0f;
		try {
			perUnit = market.getDemandPrice(commodityId, 1.0, true);
		} catch (Throwable t) {
			perUnit = 0f;
		}
		if (perUnit <= 0f && market.getCommodityData(commodityId) != null) {
			perUnit = market.getCommodityData(commodityId).getCommodity().getBasePrice();
		}
		return perUnit * quantity;
	}

	/** Standing with the faction: points are hundredths of the -1..1 scale. */
	public static void rep(String factionId, float points, TextPanelAPI text) {
		if (factionId == null || points <= 0f) return;
		if (Factions.PLAYER.equals(factionId)) return;
		CustomRepImpact impact = new CustomRepImpact();
		impact.delta = points * 0.01f;
		Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, text != null),
				factionId);
	}

	/**
	 * STANDING, ONE RATE BOTH WAYS (the user, 2026-10-31: "should be parity on rep for sending and
	 * requesting" - 12,900 hulls sent read +10, the per-delivery cap, while 700 received cost 15 at
	 * the Max rate a unit): the points an amount of a good is worth at the market, earned when the
	 * player's aid lands there and charged when a faction's aid lands at the player's - its value
	 * there (valueAt) over aidRepPerCredits, no tier multiplier, no purpose weight; a defence fleet
	 * aidRepGuardArrived flat. aidRepMaxPerDelivery caps it only when above 0 (0 by default now).
	 */
	public static float standingFor(MarketAPI market, String good, float amount) {
		if (amount <= 0f || good == null) return 0f;
		if (ThreatNeeds.FLEETS.equals(good)) return ThreatIncConfig.aidRepGuardArrived();
		if (market == null) return 0f;
		return capped(valueAt(market, good, amount) / Math.max(1f, ThreatIncConfig.aidRepPerCredits()));
	}

	/** As above for a load in AID_COMMODITIES order, hull units in the fifth slot or {@code hullUnits}: one figure for the whole delivery. */
	public static float standingFor(MarketAPI market, float[] load, int hullUnits) {
		if (market == null) return 0f;
		float value = 0f;
		for (int i = 0; i < ThreatReserves.AID_COMMODITIES.length; i++) {
			float q = load != null && i < load.length ? Math.max(0f, load[i]) : 0f;
			if (i == ThreatReserves.AID_COMMODITIES.length - 1 && hullUnits > 0) q += hullUnits;
			if (q > 0f) value += valueAt(market, ThreatReserves.AID_COMMODITIES[i], q);
		}
		return capped(value / Math.max(1f, ThreatIncConfig.aidRepPerCredits()));
	}

	/** aidRepMaxPerDelivery above 0 caps a delivery's points; 0 leaves them to the rate. */
	protected static float capped(float points) {
		float cap = ThreatIncConfig.aidRepMaxPerDelivery();
		return cap > 0f ? Math.min(cap, points) : points;
	}

	/** The NPC tabs' Standing column: what a Send of the load would earn ({@link #standingFor}). */
	public static float rewardEstimate(MarketAPI market, float[] load, int hullUnits) {
		return standingFor(market, load, hullUnits);
	}

	/** As above for an amount of one good. */
	public static float rewardEstimate(MarketAPI market, String good, float amount) {
		return standingFor(market, good, amount);
	}

	/**
	 * Goods landed at another faction's colony, by convoy or by hand. Standing is {@link #standingFor}
	 * of the whole load at the receiving market - the same figure a faction charges the player for
	 * the same goods (parity, 2026-10-31). The purpose weights ({@code weights}, ThreatNeeds.purposeWeights;
	 * docs/war-effort-rework.md section 4) and the per-delivery cap were retired with it: both made
	 * giving cheaper than asking. Until 2026-10-23 a delivery under an open contract was doubled instead.
	 */
	public static void onDelivered(MarketAPI base, String recipientFactionId, int marines,
			int armaments, int fuel, int supplies, int ships, boolean viaFleet, TextPanelAPI text, float[] weights) {
		if (base == null || recipientFactionId == null) return;
		if (Factions.PLAYER.equals(recipientFactionId)) return;
		float[] load = {marines, armaments, fuel, supplies, ships};
		float value = 0f;
		for (int i = 0; i < load.length; i++) if (load[i] > 0f) value += valueAt(base, ThreatReserves.AID_COMMODITIES[i], load[i]);
		float points = standingFor(base, load, 0);
		rep(recipientFactionId, points, text);
		if (viaFleet) {
			FactionAPI faction = Global.getSector().getFaction(recipientFactionId);
			ThreatNotice.titled("Aid Landed").icon(Global.getSector().getPlayerFaction())
					.line("%s at %s", ThreatFactionView.cargoText(marines, armaments, fuel, supplies, ships),
							ThreatNotice.market(base))
					.line("Standing with %s %s", faction != null ? ThreatNotice.faction(faction) : recipientFactionId,
							ThreatNotice.hl("+" + Misc.getRoundedValueMaxOneAfterDecimal(points)))
					.send();
		}
		ThreatIncConfig.log("Aid delivered at " + base.getName() + " (" + recipientFactionId + "): "
				+ marines + " marines, " + armaments + " armaments, " + fuel + " fuel, " + supplies
				+ " supplies, " + ships + " ship hulls - value " + (int) value + ", rep +" + points);
	}

	/**
	 * The player's guard reached the colony it defends: aidRepGuardArrived standing points, the figure in
	 * the notice (the user, 2026-10-31, of a guard over Nomios: "i dont think i got any rep" - it was
	 * credited silently). Once credited the guard serves its term: the fleets table's Recall is off.
	 */
	public static void onGuardArrived(ThreatFleetOrders.Order o) {
		if (o == null || o.recipientFactionId == null) return;
		FactionAPI faction = Global.getSector().getFaction(o.recipientFactionId);
		float points = ThreatIncConfig.aidRepGuardArrived();
		rep(o.recipientFactionId, points, null);
		ThreatIncConfig.log("Guard on station over " + o.targetName + " for " + o.recipientFactionId + ": rep +" + points);
		ThreatNotice.titled("Task Force On Station").icon(Global.getSector().getPlayerFaction())
				.line("Over %s", target(o))
				.line("Standing with %s %s", ThreatNotice.faction(faction), ThreatNotice.hl("+" + Misc.getRoundedValueMaxOneAfterDecimal(points)))
				.send();
	}

	public static void onGuardCompleted(ThreatFleetOrders.Order o) {
		if (o == null || o.recipientFactionId == null) return;
		FactionAPI faction = Global.getSector().getFaction(o.recipientFactionId);
		float points = ThreatIncConfig.aidRepGuardCompleted();
		rep(o.recipientFactionId, points, null);
		ThreatIncConfig.log("Guard term served over " + o.targetName + " for " + o.recipientFactionId + ": rep +" + points);
		ThreatNotice.titled("Task Force Returning").icon(Global.getSector().getPlayerFaction())
				.line("Term served over %s", target(o))
				.line("Standing with %s %s", ThreatNotice.faction(faction), ThreatNotice.hl("+" + Misc.getRoundedValueMaxOneAfterDecimal(points)))
				.send();
	}

	/** The order's target for a line: the colony in its owner's colour or the outpost, else the name the order recorded. */
	protected static Object target(ThreatFleetOrders.Order o) {
		ThreatBases.Base base = ThreatBases.of(o.targetId);
		return base != null ? ThreatNotice.base(base) : o.targetName;
	}

	/** A task force at a hive's door: every faction with a colony in the hive's strike reach shares the credit. */
	public static void onStrikeArrived(ThreatFleetOrders.Order o) {
		if (o == null) return;
		// a hunt targets a colony; an older intercept, the system
		boolean hunt = ThreatFleetOrders.KIND_HUNT.equals(o.kind);
		MarketAPI colony = hunt && o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		StarSystemAPI hive = hunt ? (colony != null ? colony.getStarSystem() : null)
				: ThreatWarBoard.getSystem(o.targetId);
		Set<String> factions = factionsInStrikeReach(hive);
		if (factions.isEmpty()) return;
		float each = ThreatIncConfig.aidRepFrontTotal() / factions.size();
		for (String id : factions) rep(id, each, null);
		// each faction its own argument, in its own colour
		Object[] noted = new Object[factions.size()];
		StringBuilder format = new StringBuilder("Noted by ");
		int i = 0;
		for (String id : factions) {
			if (i > 0) format.append(i == noted.length - 1 ? " and " : ", ");
			format.append("%s");
			noted[i++] = ThreatNotice.faction(Global.getSector().getFaction(id));
		}
		ThreatNotice.titled(hunt ? "Hunt Begins" : "Door Held").icon(Global.getSector().getPlayerFaction())
				.line(hunt ? "Task force hunting the swarms in the %s" : "Task force holds the door of the %s",
						hive != null ? hive.getNameWithLowercaseType() : "hive system")
				.line(format.toString(), noted)
				.send();
	}

	/**
	 * Non-Threat, non-player factions with a colony within any of the hive
	 * system's colonies' strike reach - billed reach (ThreatReach), no farther
	 * than the world the hive would strike first.
	 */
	public static Set<String> factionsInStrikeReach(StarSystemAPI hive) {
		Set<String> result = new LinkedHashSet<String>();
		if (hive == null) return result;
		float range = 0f;
		if (ThreatReach.enabled()) {
			float first = ThreatReach.facedLY(hive);
			range = first >= 0f ? first + ThreatFrontlines.FRONT_TOLERANCE_LY : 0f;
		} else {
			for (MarketAPI colony : ThreatIncData.getLiveColonyMarkets(hive.getId())) {
				range = Math.max(range, IncursionManager.expeditionRangeLY(colony));
			}
		}
		if (range <= 0f) return result;
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (m.getStarSystem() == null || ThreatMapFog.hidden(m)) continue;
			if (m.getFaction() == null || m.isPlayerOwned()) continue;
			if (Factions.THREAT.equals(m.getFactionId())) continue;
			if (Misc.getDistanceLY(m.getStarSystem().getLocation(), hive.getLocation()) > range) continue;
			result.add(m.getFactionId());
		}
		return result;
	}

	// ------------------------------------------------------------------
	// presence, for the defence contract
	// ------------------------------------------------------------------

	/** The player's fleet, or a player guard on station, in the colony's system. */
	public static boolean playerAssetIn(MarketAPI market) {
		if (market == null || market.getContainingLocation() == null) return false;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && player.getContainingLocation() == market.getContainingLocation()) {
			return true;
		}
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.aid || !ThreatFleetOrders.KIND_GUARD.equals(o.kind)) continue;
			if (!market.getId().equals(o.targetId)) continue;
			if (o.fleet == null || !o.fleet.isAlive()) continue;
			if (o.fleet.getContainingLocation() == market.getContainingLocation()) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// the board's lists
	// ------------------------------------------------------------------

	/** Player aid task forces bound for this faction's colonies (or, with null, the front). */
	public static List<ThreatFleetOrders.Order> aidOrdersFor(String recipientFactionId) {
		List<ThreatFleetOrders.Order> result = new ArrayList<ThreatFleetOrders.Order>();
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (!o.aid) continue;
			if (recipientFactionId == null ? o.recipientFactionId == null
					: recipientFactionId.equals(o.recipientFactionId)) result.add(o);
		}
		return result;
	}

	public static List<ThreatConvoys.Convoy> aidConvoysFor(String recipientFactionId) {
		List<ThreatConvoys.Convoy> result = new ArrayList<ThreatConvoys.Convoy>();
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (!c.aid) continue;
			if (recipientFactionId != null && recipientFactionId.equals(c.recipientFactionId)) result.add(c);
		}
		return result;
	}

	/** Unused-import guard. */
	@SuppressWarnings("unused")
	private static void keep(Commodities c) {
	}
}
