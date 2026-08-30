package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.econ.ResourceDepositsCondition;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A standing military objective against a specific piece of hive infrastructure,
 * posted by the colonial defense boards from phase 3. Each bounty is a lesson: it
 * names a target world, explains - with the hive's real numbers - what cutting it
 * does to the swarm's war machine, and pays out when the player does it.
 * Disrupting the industry (a raid) earns half; erasing the colony outright
 * (saturation bombardment to decivilization) earns full.
 *
 * Objectives are not static. The boards score every candidate link in the hive
 * network by what severing it would cost the swarm and what it would cost a
 * fleet to do, and re-issue the standing list as the network shifts - so a
 * bounty posted against a lone refinery is withdrawn once the swarm's only forge
 * world comes online. See {@link #tierValue} and
 * {@link IncursionManager#manageBounties}.
 */
public class ThreatBountyIntel extends BaseIntelPlugin {

	public static final int TYPE_RARE_MINING = 0;
	public static final int TYPE_FUEL = 1;
	public static final int TYPE_REFINING = 2;
	/**
	 * The military lever: the Swarm Nexus that fabricates a colony's Defense
	 * Swarms and stages its expeditions. Value 3 was formerly the forge type -
	 * bounties deserialized from older saves retarget to the same market's
	 * nexus, which every colony carries, so nothing dangles.
	 */
	public static final int TYPE_NEXUS = 3;
	public static final int TYPE_COUNT = 4;

	/**
	 * Deliberately NOT scaled by the debug fast clock: the clock accelerates
	 * the swarm, but this window is the PLAYER'S time to act on the bounty -
	 * cracking a size-8 hive world is a campaign, not an errand.
	 */
	public static float durationDays() {
		return ThreatIncConfig.bountyDurationDays();
	}

	protected int type;
	protected String marketId;
	protected int reward;
	protected long postedTimestamp;
	protected String outcome; // null active, "disrupted", "destroyed", "expired", "withdrawn"
	/** For withdrawn objectives: the higher-value target that took the slot. */
	protected String supersededBy;
	/** Which board issued this. Absent in pre-tier saves, which read as strategic. */
	protected int tier;

	public ThreatBountyIntel(int tier, int type, String marketId) {
		this.tier = tier;
		this.type = type;
		this.marketId = marketId;
		MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
		int size = market != null ? market.getSize() : 4;
		// the strategic board pays a premium: it names the job nobody wants,
		// and it is the expensive tier precisely because it is the hard one
		float mult = tier == TIER_STRATEGIC ? ThreatIncConfig.bountyStrategicRewardMult() : 1f;
		this.reward = (int) (ThreatIncConfig.bountyBaseReward() * size * mult);
		this.postedTimestamp = Global.getSector().getClock().getTimestamp();
	}

	public int getType() {
		return type;
	}

	public int getTier() {
		return tier;
	}

	public String getMarketId() {
		return marketId;
	}

	// ------------------------------------------------------------------
	// target selection
	// ------------------------------------------------------------------

	protected static boolean hasRareDeposit(MarketAPI market) {
		for (MarketConditionAPI cond : market.getConditions()) {
			if (Commodities.RARE_ORE.equals(
					ResourceDepositsCondition.COMMODITY.get(cond.getId()))) return true;
		}
		return false;
	}

	protected static String industryIdFor(int type, MarketAPI market) {
		switch (type) {
			case TYPE_RARE_MINING: return Industries.MINING;
			case TYPE_FUEL: return Industries.FUELPROD;
			case TYPE_REFINING: return Industries.REFINING;
			case TYPE_NEXUS:
				return ThreatColonyManager.SWARM_NEXUS;
		}
		return null;
	}

	/** Whether this colony actually runs the link of the chain the type names. */
	public static boolean isValidTarget(int type, MarketAPI market) {
		if (market == null) return false;
		switch (type) {
			case TYPE_RARE_MINING:
				return market.hasIndustry(Industries.MINING) && hasRareDeposit(market);
			case TYPE_FUEL:
				return market.hasIndustry(Industries.FUELPROD);
			case TYPE_REFINING:
				return market.hasIndustry(Industries.REFINING);
			case TYPE_NEXUS:
				// military value: the nexus of a world big enough to matter as a
				// staging platform - every colony HAS one, so scale is the filter
				return market.hasIndustry(ThreatColonyManager.SWARM_NEXUS)
						&& market.getSize() >= ThreatIncConfig.strikeMinSize();
		}
		return false;
	}

	/** Every live colony running the targeted link. */
	public static List<MarketAPI> providersOf(int type) {
		List<MarketAPI> result = new ArrayList<MarketAPI>();
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (isValidTarget(type, market)) result.add(market);
		}
		return result;
	}

	// ------------------------------------------------------------------
	// strategic weighting: what severing a link costs the swarm, against what
	// severing it costs a fleet
	// ------------------------------------------------------------------

	/**
	 * How much the hive network as a whole loses if this colony's link is cut,
	 * in abstract units. Built from the hive's real structure, not flavour:
	 *
	 * - SHARE: vanilla industry output scales with market size, so a colony's
	 *   size relative to every other provider of the same link is its share of
	 *   that link's output.
	 * - SCARCITY: cutting one of two refineries is a wound; one of six is an
	 *   inconvenience; cutting the only one severs the chain.
	 * - LEVER: how much of the network hangs off this link at all - how many
	 *   colonies consume it, and how much they are consuming right now.
	 *
	 * Then weighted for the colonies that matter most: the OG home system that
	 * founded the whole economy, colonies that can actually stage expeditions
	 * rather than merely supply them, and worlds whose fuel range genuinely
	 * reaches living systems.
	 *
	 * And weighted UP for anything that has crept inside a faction navy's
	 * operating radius: a hive world a few jumps from a High Command is the
	 * sector's immediate problem, whatever its place in the supply chain.
	 *
	 * Note this is the impact figure only. It is what the two boards in
	 * {@link #tierValue} disagree about how to price - the strategic board
	 * almost ignores what taking the target costs, the immediate board is
	 * dominated by it.
	 */
	public static float networkImpact(int type, MarketAPI market) {
		if (!isValidTarget(type, market)) return 0f;

		List<MarketAPI> colonies = ThreatIncData.getAllLiveColonyMarkets();
		if (colonies.isEmpty()) return 0f;

		List<MarketAPI> providers = providersOf(type);
		int capacity = 0;
		for (MarketAPI provider : providers) capacity += provider.getSize();
		if (capacity <= 0) return 0f;

		float share = (float) market.getSize() / capacity;
		float scarcity = 1f + 2f / Math.max(1, providers.size());

		int forges = ThreatColonyManager.groupCountIndustry(Industries.HEAVYINDUSTRY)
				+ ThreatColonyManager.groupCountIndustry(Industries.ORBITALWORKS);
		int refineries = ThreatColonyManager.groupCountIndustry(Industries.REFINING);

		float lever;
		switch (type) {
			case TYPE_RARE_MINING:
				// rare ore feeds every refinery's rare-metals output, and a TOTAL
				// cutoff freezes growth network-wide (isEconomicallyHealthy). Worth
				// almost nothing if this incursion's economy never ran on rare ore.
				if (!ThreatIncData.usesRareEconomy()) { lever = 0.2f; break; }
				lever = 0.9f + 0.15f * refineries;
				// the last rare mine standing: killing it is permanent strangulation
				if (providers.size() == 1) lever *= 1.6f;
				break;
			case TYPE_FUEL:
				// fuel is reach - it carries every colonization wave and every
				// strike. Worth what the swarm currently has to move.
				lever = 0.8f + 0.25f * countStrikeCapable();
				break;
			case TYPE_REFINING:
				// metals gate both forge output and every colony's growth
				lever = 0.8f + 0.12f * colonies.size() + 0.2f * forges;
				break;
			case TYPE_NEXUS:
				// the nexus turns hulls into FLEETS - garrisons and expeditions
				// both; one fed by a nominal hull economy fabricates far more
				// than one starving on a broken supply chain
				lever = (0.9f + 0.15f * colonies.size())
						* (0.4f + 0.6f * ThreatColonyManager.shipSupplyMult(market));
				break;
			default:
				return 0f;
		}

		float value = lever * share * scarcity;

		StarSystemAPI system = market.getStarSystem();
		if (system != null && ThreatIncData.isOGSystem(system.getId())) value *= 1.25f;

		if (isStrikePlatform(market)) {
			boolean armada = market.getSize() >= 6
					&& ThreatColonyManager.shipSupplyMult(market)
							>= ThreatColonyManager.STABLE_SHIP_SUPPLY_MULT;
			value *= armada ? 1.4f : 1.15f;
		}

		// what this world can reach raises it; what can reach this world lowers it
		// urgency, both ways round: what this world can reach, and how close it
		// has crept to defended space. A hive world inside a navy's operating
		// radius is the sector's immediate problem, not a distant one.
		value *= 1f + 0.5f * strikeUrgency(market);
		value *= 1f + ThreatIncConfig.bountyProximityBonus() * factionReachFactor(market);
		return value;
	}

	/** A colony that can stage expeditions, not merely supply them. */
	protected static boolean isStrikePlatform(MarketAPI market) {
		return market.getSize() >= ThreatIncConfig.strikeMinSize()
				&& ThreatColonyManager.getForge(market) != null
				&& ThreatColonyManager.hasOperationalFuel(market)
				&& ThreatColonyManager.hasOperationalNexus(market);
	}

	protected static int countStrikeCapable() {
		int count = 0;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (isStrikePlatform(market)) count++;
		}
		return count;
	}

	/** Light-years from this colony to the nearest live inhabited world of strike size. */
	protected static float nearestInhabitedLY(MarketAPI market) {
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return Float.MAX_VALUE;
		float nearest = Float.MAX_VALUE;
		for (MarketAPI other : Global.getSector().getEconomy().getMarketsCopy()) {
			if (Factions.THREAT.equals(other.getFactionId())) continue;
			if (other.isHidden() || other.isPlanetConditionMarketOnly()) continue;
			if (other.getStarSystem() == null || other.getSize() < 3) continue;
			float d = Misc.getDistanceLY(system.getLocation(),
					other.getStarSystem().getLocation());
			if (d < nearest) nearest = d;
		}
		return nearest;
	}

	/**
	 * 0..1 by how comfortably this world can actually put an expedition on a
	 * living system - the same reach test pickStrikeTarget launches on: the
	 * colony's fuel range against the distance to the nearest inhabited world.
	 * Zero if it cannot reach anyone at all.
	 *
	 * Fuel range is the hive economy's group-wide figure, not local production,
	 * so this moves with the whole network's logistics: cut their fuel and their
	 * staging worlds visibly stop being urgent, which is the same lever the fuel
	 * bounty type teaches. The margin matters rather than the raw distance - a
	 * world that can barely stretch to one neighbour is a lesser threat than one
	 * with the sector's core inside its comfortable radius.
	 */
	protected static float strikeUrgency(MarketAPI market) {
		float range = ThreatColonyManager.fuelRangeLY(market);
		if (range <= 0f) return 0f;
		float nearest = nearestInhabitedLY(market);
		if (nearest == Float.MAX_VALUE || nearest > range) return 0f;
		return Math.max(0f, Math.min(1f, 1f - nearest / range));
	}

	/**
	 * Light-years to the nearest non-player faction military world - a Patrol HQ,
	 * Military Base or High Command. Exactly the bases tryPurgeBombardments
	 * musters saturation purges from.
	 */
	protected static float nearestFactionMilitaryLY(MarketAPI market) {
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return Float.MAX_VALUE;
		float nearest = Float.MAX_VALUE;
		for (MarketAPI base : Global.getSector().getEconomy().getMarketsCopy()) {
			if (base.getFaction() == null || base.getFaction().isPlayerFaction()) continue;
			if (Factions.THREAT.equals(base.getFactionId())) continue;
			if (base.getStarSystem() == null || base.getPrimaryEntity() == null) continue;
			if (!IncursionManager.hasMilitary(base)) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(),
					system.getLocation());
			if (d < nearest) nearest = d;
		}
		return nearest;
	}

	/**
	 * 0..1 by how deeply this world already sits inside somebody else's navy's
	 * reach. 1 when a military world is effectively on top of it, tapering to 0
	 * at the response-range boundary and beyond.
	 *
	 * This cuts two ways and both favour the near target. A hive world that has
	 * crept inside a standing fleet's operating radius is the sector's immediate
	 * problem - it is about to be somebody's front line - so it is weighted UP.
	 * And it is genuinely cheaper to kill: task forces already grind at its
	 * garrison, and once that garrison is gone a purge expedition finishes the
	 * colony, which still pays the bounty in full because advanceImpl only asks
	 * whether the market died, never who killed it. So it also buys difficulty
	 * relief in {@link #difficulty}.
	 */
	protected static float factionReachFactor(MarketAPI market) {
		float range = ThreatIncConfig.responseRangeLY();
		if (range <= 0f) return 0f;
		float nearest = nearestFactionMilitaryLY(market);
		if (nearest == Float.MAX_VALUE || nearest >= range) return 0f;
		return Math.max(0f, Math.min(1f, (range - nearest) / range));
	}

	/**
	 * What taking this objective would cost a fleet, in abstract units: the
	 * garrison to break, the colony's own size and ground defenses, the burn out
	 * to it - and, for nexus targets, the fact that only full decivilization is
	 * credited, so there is no half-measure available.
	 */
	public static float difficulty(int type, MarketAPI market) {
		if (market == null) return 1f;

		float d = market.getSize();
		int own = ThreatColonyManager.countLiveGarrison(market.getId());
		d += 1.5f * own;

		// sibling colonies in the same system: their Defense Swarms are in reach
		StarSystemAPI system = market.getStarSystem();
		if (system != null) {
			int inSystem = ThreatColonyManager.countLiveGarrisonInSystem(system.getId());
			d += 0.5f * Math.max(0, inSystem - own);
		}

		if (market.hasIndustry(Industries.HEAVYBATTERIES)
				|| market.hasIndustry(ThreatColonyManager.THREAT_HEAVY_BATTERIES)) d += 3f;
		else if (market.hasIndustry(Industries.GROUNDDEFENSES)
				|| market.hasIndustry(ThreatColonyManager.THREAT_GROUND_DEFENSES)) d += 1.5f;

		// deciv-only: the hive disrupts its own nexus with every launch, so a
		// raid proves nothing and is never credited (see advanceImpl)
		if (type == TYPE_NEXUS) d += 4f;

		d += Math.min(6f, distanceFromPlayerLY(market) / 8f);

		// help is real: inside a navy's radius, task forces wear the garrison
		// down and a purge expedition can finish the colony outright - and a
		// bounty pays full whoever lands the last bombardment
		d -= REACH_RELIEF * factionReachFactor(market);

		return Math.max(1f, d);
	}

	/**
	 * Difficulty knocked off a target sitting fully inside a faction navy's
	 * reach. Sized against the garrison term (1.5 per Defense Swarm): roughly
	 * "two fewer fleets to break yourself".
	 */
	public static final float REACH_RELIEF = 3f;

	protected static float distanceFromPlayerLY(MarketAPI market) {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || market.getStarSystem() == null) return 0f;
		return Misc.getDistanceLY(player.getLocationInHyperspace(),
				market.getStarSystem().getLocation());
	}

	// ------------------------------------------------------------------
	// the two boards
	// ------------------------------------------------------------------

	/**
	 * The decisive target, whatever it costs. Ranked on network damage alone,
	 * with effort barely damping it - this is the world that, cut, actually
	 * breaks the swarm, and the boards name it even if taking it is a campaign.
	 *
	 * Value 0 so bounties deserialized from saves written before tiers existed
	 * default to this, which is what the old single-bounty board was.
	 */
	public static final int TIER_STRATEGIC = 0;

	/**
	 * The low-hanging fruit: what a captain can act on now. Ranked on damage per
	 * unit of effort with effort weighted hard, so it lands on near, lightly
	 * held worlds already inside somebody's navy radius - the immediate problem
	 * rather than the decisive one.
	 *
	 * One thing overrides cheapness on this board: what the world is DOING.
	 * A colony with an expedition in flight, or with living systems inside its
	 * strike reach, IS the immediate problem - it outranks any amount of cheap,
	 * unreachable infrastructure. See {@link #immediateThreatMult}.
	 */
	public static final int TIER_IMMEDIATE = 1;

	public static final int TIER_COUNT = 2;

	public static String tierName(int tier) {
		return tier == TIER_IMMEDIATE ? "Immediate" : "Strategic";
	}

	/**
	 * How a given board ranks a candidate. The two tiers differ in how hard
	 * effort is weighted, so they are reading the same world through different
	 * eyes rather than measuring different things:
	 *
	 * - Strategic damps difficulty to a quarter power, so a target four times
	 *   harder still wins on ~1.4x the network damage. Decisive worlds stay top
	 *   of that board however well defended they are.
	 * - Immediate raises it to 1.5, so difficulty dominates outright and the
	 *   cheapest worthwhile cut wins.
	 *
	 * The immediate board additionally weights what the world is doing right
	 * now - callers apply {@link #immediateThreatMult} on top of this figure
	 * (see {@link #valueFor} and {@link #bestObjective}).
	 *
	 * Scores are only ever compared WITHIN a tier - the two scales are unrelated.
	 */
	public static float tierValue(int tier, float impact, float difficulty) {
		if (impact <= 0f) return 0f;
		if (tier == TIER_IMMEDIATE) {
			return impact * 100f / (float) Math.pow(difficulty, 1.5f);
		}
		return impact * 10f / (float) Math.pow(difficulty, 0.25f);
	}

	/**
	 * How hard the immediate board weights active menace, applied to worlds by
	 * what they can currently reach: a colony whose fuel range comfortably
	 * covers a living system scores up to 1 + this. Sized so genuine reach
	 * (~7x at full urgency) decisively beats the difficulty edge a small,
	 * harmless mining world enjoys under the damage-per-effort ranking.
	 */
	public static final float IMMEDIATE_URGENCY_WEIGHT = 6f;

	/**
	 * Extra multiplier on a world whose expedition is actually IN FLIGHT.
	 * Raiding beats "could raid": stacked on the urgency weight this is ~28x,
	 * enough to out-rank every sibling colony in the same system too, so the
	 * bounty lands on the staging world itself rather than its cheapest
	 * neighbour.
	 */
	public static final float IMMEDIATE_RAIDER_MULT = 4f;

	/**
	 * The immediate board's answer to "what is this world doing to the sector
	 * right now": 1 for inert infrastructure (the plain damage-per-effort
	 * ranking stands), scaling steeply for worlds with living systems in
	 * strike reach, and dominant for a world with an expedition in flight.
	 *
	 * A raider's urgency is floored at full: launching pays the launch cost -
	 * forge disrupted, fuel drawn down - so the live reach test can read zero
	 * precisely BECAUSE the expedition just left. The flight is the proof of
	 * reach.
	 *
	 * Immediate-tier only. The strategic board keeps ranking on network
	 * damage: what a world is doing this month doesn't change what cutting it
	 * costs the swarm.
	 */
	public static float immediateThreatMult(MarketAPI market) {
		float urgency = strikeUrgency(market);
		boolean raiding = IncursionManager.isActiveStrikeSource(market);
		if (raiding && urgency < 1f) urgency = 1f;
		float mult = 1f + IMMEDIATE_URGENCY_WEIGHT * urgency;
		if (raiding) mult *= IMMEDIATE_RAIDER_MULT;
		return mult;
	}

	public static float valueFor(int tier, int type, MarketAPI market) {
		float impact = networkImpact(type, market);
		if (impact <= 0f) return 0f;
		float value = tierValue(tier, impact, difficulty(type, market));
		if (tier == TIER_IMMEDIATE) value *= immediateThreatMult(market);
		return value;
	}

	/** One scored candidate objective. Transient - never persisted. */
	public static class Objective {
		public final int tier;
		public final int type;
		public final MarketAPI market;
		public final float impact;
		public final float difficulty;
		public final float value;

		public Objective(int tier, int type, MarketAPI market, float impact,
				float difficulty, float value) {
			this.tier = tier;
			this.type = type;
			this.market = market;
			this.impact = impact;
			this.difficulty = difficulty;
			this.value = value;
		}

		public String describe() {
			return typeNoun(type) + " on " + market.getName();
		}
	}

	/**
	 * The best objective the given board could issue right now, skipping any
	 * world already covered by a standing bounty of either tier. Objectives of a
	 * type already on the board are discounted: the four levers are a
	 * curriculum, and three simultaneous forge bounties teach nothing the first
	 * one didn't.
	 */
	public static Objective bestObjective(int tier, List<ThreatBountyIntel> standing,
			String excludeMarketId) {
		Set<String> taken = new LinkedHashSet<String>();
		Set<Integer> typesOnBoard = new LinkedHashSet<Integer>();
		for (ThreatBountyIntel curr : standing) {
			taken.add(curr.getMarketId());
			typesOnBoard.add(curr.getType());
		}
		if (excludeMarketId != null) taken.add(excludeMarketId);

		Objective best = null;
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (taken.contains(market.getId())) continue;
			for (int type = 0; type < TYPE_COUNT; type++) {
				if (!isValidTarget(type, market)) continue;
				float impact = networkImpact(type, market);
				if (impact <= 0f) continue;
				float difficulty = difficulty(type, market);
				float value = tierValue(tier, impact, difficulty);
				if (tier == TIER_IMMEDIATE) value *= immediateThreatMult(market);
				if (typesOnBoard.contains(type)) value *= 0.6f;
				if (best == null || value > best.value) {
					best = new Objective(tier, type, market, impact, difficulty, value);
				}
			}
		}
		return best;
	}

	/** This bounty's value on its own board today, not when it was posted. */
	public float currentValue() {
		MarketAPI market = getMarket();
		if (market == null) return 0f;
		return valueFor(getTier(), type, market);
	}

	/** All standing (posted, unresolved) objectives. */
	public static List<ThreatBountyIntel> getStanding() {
		List<ThreatBountyIntel> result = new ArrayList<ThreatBountyIntel>();
		for (Object curr : Global.getSector().getIntelManager().getIntel(ThreatBountyIntel.class)) {
			ThreatBountyIntel bounty = (ThreatBountyIntel) curr;
			if (bounty.outcome != null || bounty.isEnded() || bounty.isEnding()) continue;
			result.add(bounty);
		}
		return result;
	}

	/** Standing objectives on one board. */
	public static List<ThreatBountyIntel> getStanding(int tier) {
		List<ThreatBountyIntel> result = new ArrayList<ThreatBountyIntel>();
		for (ThreatBountyIntel curr : getStanding()) {
			if (curr.getTier() == tier) result.add(curr);
		}
		return result;
	}

	/** Where this objective sits on its own board: 1 is that board's top entry. */
	public int currentRank() {
		float mine = currentValue();
		int rank = 1;
		for (ThreatBountyIntel curr : getStanding(getTier())) {
			if (curr == this) continue;
			if (curr.currentValue() > mine) rank++;
		}
		return rank;
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	@Override
	protected void advanceImpl(float amount) {
		if (outcome != null) return;

		MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
		if (market == null) {
			// colony gone - burned to decivilization: full payout
			outcome = "destroyed";
			pay(reward);
			return;
		}

		// disruption counts for every link except the nexus (the hive disrupts
		// its own nexus with every expedition it launches via payLaunchCost -
		// only the colony's outright destruction can be credited there)
		if (type != TYPE_NEXUS) {
			Industry industry = market.getIndustry(industryIdFor(type, market));
			if (industry != null && industry.isDisrupted()) {
				outcome = "disrupted";
				pay(reward / 2);
				return;
			}
		}

		if (Global.getSector().getClock().getElapsedDaysSince(postedTimestamp)
				> durationDays()) {
			outcome = "expired";
			sendUpdateIfPlayerHasIntel(null, false);
			endAfterDelay();
		}
	}

	protected void pay(int amount) {
		Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);
		sendUpdateIfPlayerHasIntel(null, false);
		endAfterDelay();
		ThreatIncConfig.log("Bounty paid: " + amount + " (" + outcome + ")");
	}

	/**
	 * Whether the boards are willing to pull this objective. They will not do it
	 * inside the minimum stand period - an objective that evaporates while the
	 * player is three jumps out is not an objective - and never while the player
	 * is standing in the target's system.
	 *
	 * One emergency waives the stand period: the immediate board holding a
	 * bounty on inert infrastructure while a hive world with an expedition IN
	 * FLIGHT stands uncovered. A launch is a discrete, loud event - the board
	 * re-issues promptly rather than lecturing about a mining world for two
	 * months while an armada burns toward someone's colony. Churn stays
	 * bounded: the waiver needs an actual uncovered raider, the challenger
	 * must still clear the supersede margin, and once the raider's bounty is
	 * posted it is itself the raider and immune to the waiver.
	 */
	public boolean canBeSuperseded() {
		if (!canBeWithdrawn()) return false;
		if (Global.getSector().getClock().getElapsedDaysSince(postedTimestamp)
				>= ThreatIncConfig.bountyMinStandDays()) return true;

		if (tier != TIER_IMMEDIATE) return false;
		MarketAPI market = getMarket();
		if (market != null && IncursionManager.isActiveStrikeSource(market)) return false;
		return uncoveredRaiderExists();
	}

	/** A live colony staging an in-flight strike with no bounty (either tier) on it. */
	protected static boolean uncoveredRaiderExists() {
		Set<String> covered = new LinkedHashSet<String>();
		for (ThreatBountyIntel curr : getStanding()) {
			covered.add(curr.getMarketId());
		}
		for (MarketAPI market : ThreatIncData.getAllLiveColonyMarkets()) {
			if (covered.contains(market.getId())) continue;
			if (IncursionManager.isActiveStrikeSource(market)) return true;
		}
		return false;
	}

	/**
	 * The protections that hold no matter what, separate from the anti-churn
	 * stand period above: never withdraw a payout the player has already earned,
	 * and never pull an objective out from under a player standing in its
	 * system. Restructuring the board to keep both tiers covered may waive the
	 * stand period - an empty board is a structural fault worth correcting
	 * promptly - but it may never waive these.
	 */
	public boolean canBeWithdrawn() {
		if (outcome != null || isEnded() || isEnding()) return false;

		// advanceImpl resolves earned payouts on the intel's own advance, which
		// may not have run since the player finished the job - so check the same
		// two conditions it does rather than racing it
		MarketAPI market = getMarket();
		if (market == null) return false; // colony erased: full payout pending
		if (type != TYPE_NEXUS) {
			Industry industry = market.getIndustry(industryIdFor(type, market));
			if (industry != null && industry.isDisrupted()) return false;
		}

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && market.getStarSystem() != null
				&& market.getStarSystem() == player.getContainingLocation()) return false;
		return true;
	}

	/** Pulled from the board because a higher-value objective took its slot. */
	public void withdraw(String replacement) {
		if (outcome != null) return;
		outcome = "withdrawn";
		supersededBy = replacement;
		sendUpdateIfPlayerHasIntel(null, false);
		endAfterDelay();
		ThreatIncConfig.log("Bounty withdrawn (superseded by " + replacement + "): "
				+ typeNoun() + " on " + targetName());
	}

	// ------------------------------------------------------------------
	// presentation
	// ------------------------------------------------------------------

	protected MarketAPI getMarket() {
		return ThreatIncData.resolveColonyMarket(marketId);
	}

	protected String targetName() {
		MarketAPI market = getMarket();
		return market != null ? market.getName() : "a destroyed hive world";
	}

	public static String typeNoun(int type) {
		switch (type) {
			case TYPE_RARE_MINING: return "rare ore mining";
			case TYPE_FUEL: return "fuel production";
			case TYPE_REFINING: return "refining";
			case TYPE_NEXUS: return "swarm nexus";
		}
		return "infrastructure";
	}

	protected String typeNoun() {
		return typeNoun(type);
	}

	/** Plain-language read on how hard the objective is, from {@link #difficulty}. */
	protected static String resistanceWord(float difficulty) {
		if (difficulty < 10f) return "moderate";
		if (difficulty < 16f) return "serious";
		if (difficulty < 22f) return "severe";
		return "extreme";
	}

	@Override
	public String getName() {
		if ("destroyed".equals(outcome)) return "Bounty Paid - " + typeNoun() + " erased";
		if ("disrupted".equals(outcome)) return "Bounty Paid - " + typeNoun() + " disrupted";
		if ("expired".equals(outcome)) return "Bounty Expired - Threat " + typeNoun();
		if ("withdrawn".equals(outcome)) return "Bounty Withdrawn - Threat " + typeNoun();
		return "Bounty (" + tierName(tier) + ") - Threat " + typeNoun() + " on " + targetName();
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		tags.add(TAG_BOUNTY);
		return tags;
	}

	public static final String TAG_BOUNTY = "Bounties";

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		MarketAPI market = getMarket();
		return market != null ? market.getPrimaryEntity() : null;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		if (outcome != null) return;

		int onBoard = getStanding(tier).size();
		if (onBoard > 1) {
			info.addPara("%s priority %s of %s - reward: %s (disruption pays half)", 3f,
					Misc.getTextColor(), Misc.getHighlightColor(), tierName(tier),
					"" + currentRank(), "" + onBoard, Misc.getDGSCredits(reward));
		} else {
			info.addPara("%s objective - reward: %s (disruption pays half)", 3f,
					Misc.getTextColor(), Misc.getHighlightColor(), tierName(tier),
					Misc.getDGSCredits(reward));
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();

		if (outcome != null) {
			if ("destroyed".equals(outcome)) {
				info.addPara("The target was burned to decivilization. The bounty of %s has "
						+ "been paid in full.", opad, pos, Misc.getDGSCredits(reward));
			} else if ("disrupted".equals(outcome)) {
				info.addPara("The target's operations were disrupted. Half the bounty - %s - "
						+ "has been paid. Erasing the colony outright would have paid in full.",
						opad, pos, Misc.getDGSCredits(reward / 2));
			} else if ("withdrawn".equals(outcome)) {
				info.addPara("The defense boards have reassessed the swarm's network and %s "
						+ "this objective. The infrastructure it named still runs - it is "
						+ "simply no longer the most valuable thing in the sector to cut.",
						opad, neg, "withdrawn");
				if (supersededBy != null) {
					info.addPara("The %s board's priority has moved to the Threat %s. Nothing "
							+ "is owed on a withdrawn objective.", opad, h,
							tierName(tier).toLowerCase(), supersededBy);
				}
				info.addPara("Should the swarm's network shift again, this target can be "
						+ "re-issued.", opad);
			} else {
				info.addPara("The bounty expired unclaimed. The hive infrastructure it named "
						+ "still runs at full capacity.", opad, neg, "expired unclaimed");
			}
			return;
		}

		MarketAPI market = getMarket();
		String sysName = market != null && market.getStarSystem() != null
				? market.getStarSystem().getNameWithLowercaseType() : "an infested system";
		int garrison = market != null
				? ThreatColonyManager.countLiveGarrison(market.getId()) : 0;

		info.addPara("The colonial defense boards have posted a bounty against the Threat "
				+ typeNoun() + " on " + targetName() + ", in the " + sysName + ".", opad,
				neg, typeNoun());

		// the lesson: what cutting this link actually does, in the hive's own numbers
		switch (type) {
			case TYPE_RARE_MINING:
				info.addPara("Analysis: this world feeds rare ore to every refinery the hive "
						+ "runs. Cut it and their rare metals collapse - hull quality and "
						+ "output crater across the entire swarm, and every refinery world "
						+ "stops growing until the flow is re-established. Erase every rare "
						+ "mine they hold and the strangulation is permanent.", opad, h,
						"rare metals collapse");
				break;
			case TYPE_FUEL:
				info.addPara("Analysis: fuel is the swarm's reach. This plant powers their "
						+ "colonization waves and strike expeditions - cut it and infested "
						+ "systems lose light-years of range, grounding raids and freezing "
						+ "expansion until production recovers.", opad, h,
						"fuel is the swarm's reach");
				if (market != null) {
					float range = ThreatColonyManager.fuelRangeLY(market);
					info.addPara("Current strike reach from this colony: %s.", opad, neg,
							(int) range + " light-years");
				}
				break;
			case TYPE_REFINING:
				info.addPara("Analysis: this refinery turns the hive's ore into the metals "
						+ "its forges consume. Cut it and the forge worlds starve - ship "
						+ "hull output drops, garrisons thin, and metals-starved colonies "
						+ "stop growing.", opad, h, "the forge worlds starve");
				break;
			case TYPE_NEXUS:
				info.addPara("Analysis: this Swarm Nexus is the colony's military organ - "
						+ "every Defense Swarm it fields and every expedition it stages is "
						+ "fabricated there. Erase the colony and the hive loses the organ, "
						+ "not just the hardware: %s. Note: only full decivilization is "
						+ "credited for nexus targets - the hive disrupts its own nexus with "
						+ "every launch, so disruption proves nothing.", opad, h,
						"nothing new is ever fabricated here again");
				if (market != null) {
					int pct = (int) (ThreatColonyManager.shipSupplyMult(market) * 100);
					info.addPara("Current fleet fabrication rate at this world: %s of nominal.",
							opad, neg, pct + "%");
				}
				break;
		}

		if (market != null) {
			info.addPara("The target is a size %s colony defended by %s Defense Swarms. "
					+ "Defeat them, then raid to disrupt - or saturation bombard until "
					+ "nothing remains.", opad, h, "" + market.getSize(), "" + garrison);
			if (ThreatIncConfig.fragmentShieldEnabled()
					&& ThreatColonyManager.hasFragmentFabricator(market)) {
				info.addPara("Note: this colony's %s still runs - your bombardment cannot land "
						+ "until a ground raid tears the fabricator out of the Fabrication Core.",
						opad, neg, "Fragment Fabricator");
			}
		}

		info.addPara("Reward: %s for permanent destruction, half for disruption.", opad,
				pos, Misc.getDGSCredits(reward));

		// the board's own scoring, shown live: this is the figure that decides
		// whether the objective keeps its slot
		if (market != null) {
			if (tier == TIER_STRATEGIC) {
				info.addPara("This is a %s. The boards rank it on what cutting it does to the "
						+ "swarm and very nearly ignore what taking it costs - it is named "
						+ "because it is decisive, not because it is achievable. It pays "
						+ "accordingly.", opad, h, "strategic objective");
			} else {
				info.addPara("This is an %s. The boards rank it on damage per unit of effort, "
						+ "so it names the cheapest cut still worth making - a near, lightly "
						+ "held world rather than the swarm's keystone. It pays less than the "
						+ "strategic board for the same reason. One thing jumps this board's "
						+ "queue: a world that is actually striking, or holds living systems "
						+ "in its strike reach, outranks any amount of cheap, inert "
						+ "infrastructure.", opad, h, "immediate objective");
			}

			info.addPara("Board assessment: %s priority %s of %s. This world is one of %s "
					+ "running this link for the swarm, and expected resistance is %s.",
					opad, h, tierName(tier).toLowerCase(), "" + currentRank(),
					"" + getStanding(tier).size(), "" + providersOf(type).size(),
					resistanceWord(difficulty(type, market)));

			// why it ranks where it does: what it is doing, what it can reach,
			// and what can reach it
			float reach = ThreatColonyManager.fuelRangeLY(market);
			float toLiving = nearestInhabitedLY(market);
			if (IncursionManager.isActiveStrikeSource(market)) {
				info.addPara("An expedition staged from this world is %s. This is the colony "
						+ "fabricating and fueling the fleets currently burning toward "
						+ "inhabited space - cutting it is the immediate board's whole "
						+ "purpose, and it holds the top of that board for as long as the "
						+ "expedition flies.", opad, neg, "in flight right now");
				if (ThreatIncConfig.strikeRecallEnabled()) {
					if (IncursionManager.hasPreparingStrikeFrom(market)) {
						info.addPara("The expedition is still being fabricated: break this "
								+ "colony's forge or Swarm Nexus - a disruption raid, a "
								+ "bombardment, or the colony's destruction - %s and the "
								+ "operation is stillborn.",
								opad, Misc.getPositiveHighlightColor(),
								"before the fleets depart");
					} else {
						info.addPara("The expedition has already departed and is %s - killing "
								+ "this colony still collects the bounty and stops the NEXT "
								+ "launch, but the fleets in flight will not turn back.", opad,
								neg, "autonomous");
					}
				}
			} else if (reach > 0f && toLiving != Float.MAX_VALUE && toLiving <= reach) {
				info.addPara("The swarm can put an expedition on a living system from here: "
						+ "the nearest lies %s out, inside this colony's %s of fuel reach.",
						opad, neg, (int) toLiving + " light-years",
						(int) reach + " light-years");
			} else {
				info.addPara("This colony cannot presently reach a living system with an "
						+ "expedition - it is infrastructure, not a staging world.", opad, h,
						"infrastructure, not a staging world");
			}

			float military = nearestFactionMilitaryLY(market);
			if (factionReachFactor(market) > 0f) {
				info.addPara("The swarm has pushed to within %s of standing naval forces, "
						+ "which raises this target's priority and lowers its price. Expect "
						+ "task forces already worrying at the Defense Swarms - and once that "
						+ "garrison is broken, a purge expedition may finish the colony for "
						+ "you. %s.", opad, h, (int) military + " light-years",
						"The bounty pays in full whoever lands the last bombardment");
			} else {
				info.addPara("No fleet the sector can muster operates within reach of this "
						+ "system. There will be no task force worrying at the garrison and no "
						+ "purge expedition to finish the world: %s.", opad, neg,
						"whatever is done here, you do alone");
			}
			info.addPara("Standing objectives are re-issued as the swarm's network shifts. If "
					+ "a higher-value target emerges, this bounty is %s.", opad, neg,
					"withdrawn unpaid");
		}

		float days = durationDays()
				- Global.getSector().getClock().getElapsedDaysSince(postedTimestamp);
		if (days > 0) {
			info.addPara("The bounty stands for another %s days.", opad, h, "" + (int) days);
		}
	}
}
