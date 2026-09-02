package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.ReputationActionResponsePlugin.ReputationAdjustmentResult;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.MissionCompletionRep;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepRewards;
import com.fs.starfarer.api.impl.campaign.econ.ResourceDepositsCondition;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseMissionIntel;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * A military contract against a specific piece of hive infrastructure, offered
 * by a colonial defense board from phase 3. Each one is a lesson: it names a
 * target world, explains - with the hive's real numbers - what cutting it does
 * to the swarm's war machine, and pays out when the player does it.
 *
 * These are ordinary missions in the vanilla sense - the same accept-or-ignore
 * flow as a survey or derelict-analysis posting. A contract is offered through
 * the comm relay network, stands for a limited posting window, and may be
 * withdrawn at any time until it is accepted. Accepting it starts the clock:
 * the player then has {@link #durationDays} to disrupt the industry (a raid,
 * half pay) or erase the colony outright (full pay). Failing the deadline or
 * abandoning the contract costs reputation with the sponsoring faction, exactly
 * as vanilla missions do.
 *
 * Offers are not static. The boards score every candidate link in the hive
 * network by what severing it would cost the swarm and what it would cost a
 * fleet to do, and re-issue the standing list as the network shifts - so an
 * offer against a lone refinery is withdrawn once the swarm's only forge world
 * comes online. An ACCEPTED contract is the player's and is never withdrawn.
 * See {@link #tierValue} and {@link IncursionManager#manageMissions}.
 */
public class ThreatMissionIntel extends BaseMissionIntel {

	public static final int TYPE_RARE_MINING = 0;
	public static final int TYPE_FUEL = 1;
	public static final int TYPE_REFINING = 2;
	/**
	 * The military lever: the Swarm Nexus that fabricates a colony's Defense
	 * Swarms and stages its expeditions.
	 */
	public static final int TYPE_NEXUS = 3;
	public static final int TYPE_COUNT = 4;

	/**
	 * Days the player has to complete the contract once accepted. Deliberately
	 * NOT scaled by the debug fast clock: the clock accelerates the swarm, but
	 * this window is the PLAYER'S time to act.
	 */
	public static float durationDays() {
		return ThreatIncConfig.missionDurationDays();
	}

	/** Days an offer stays posted before it is withdrawn unaccepted. */
	public static float postingDays() {
		return ThreatIncConfig.missionPostingDays();
	}

	protected int type;
	protected String marketId;
	protected int reward;
	protected long postedTimestamp;
	/** Which board issued this. */
	protected int tier;
	/** The faction whose defense board sponsors the contract; pays reputation. */
	protected String factionId;
	/** The sponsoring faction's world nearest the target, for flavour. Nullable. */
	protected String sponsorMarketId;
	/** Why a posted offer was withdrawn: "expired", "neutralized" or "superseded". */
	protected String cancelReason;
	/** For superseded offers: the higher-value target that took the slot. */
	protected String supersededBy;

	public ThreatMissionIntel(int tier, int type, String marketId) {
		this.tier = tier;
		this.type = type;
		this.marketId = marketId;
		MarketAPI market = ThreatIncData.resolveColonyMarket(marketId);
		int size = market != null ? market.getSize() : 4;
		// the strategic board pays a premium: it names the job nobody wants,
		// and it is the expensive tier precisely because it is the hard one
		float mult = tier == TIER_STRATEGIC ? ThreatIncConfig.missionStrategicRewardMult() : 1f;
		this.reward = (int) (ThreatIncConfig.missionBaseReward() * size * mult);
		this.postedTimestamp = Global.getSector().getClock().getTimestamp();

		MarketAPI sponsor = market != null ? pickSponsor(market) : null;
		if (sponsor != null) {
			factionId = sponsor.getFactionId();
			sponsorMarketId = sponsor.getId();
		} else {
			factionId = Factions.INDEPENDENT;
		}

		setDuration(durationDays());
		// No posting location on purpose: these are sector-wide defense
		// contracts, received anywhere the player is in comm relay range,
		// rather than local postings heard only near the sponsoring world.
	}

	public int getReward() {
		return reward;
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

	public String getFactionId() {
		return factionId;
	}

	public FactionAPI getFaction() {
		FactionAPI faction = Global.getSector().getFaction(factionId);
		if (faction == null) faction = Global.getSector().getFaction(Factions.INDEPENDENT);
		return faction;
	}

	/** Days the offer still stands before it is withdrawn unaccepted. */
	public float postingDaysRemaining() {
		return Math.max(0f, postingDays()
				- Global.getSector().getClock().getElapsedDaysSince(postedTimestamp));
	}

	/** Days left to complete an accepted contract. */
	public float completionDaysRemaining() {
		if (duration == null) return 0f;
		return Math.max(0f, duration - elapsedDays);
	}

	/** Days remaining on whichever clock is running: acceptance or completion. */
	public float daysRemaining() {
		return isAccepted() ? completionDaysRemaining() : postingDaysRemaining();
	}

	// ------------------------------------------------------------------
	// sponsor
	// ------------------------------------------------------------------

	/**
	 * The faction whose navy is closest to the target speaks for the boards: a
	 * hive world is somebody's front line, and that somebody posts the job.
	 * Prefers a military world (the same bases purge expeditions muster from),
	 * falls back to any inhabited world, and never asks pirates or the player's
	 * own faction to sponsor.
	 */
	protected static MarketAPI pickSponsor(MarketAPI target) {
		StarSystemAPI system = target.getStarSystem();
		if (system == null) return null;
		MarketAPI bestMilitary = null, bestAny = null;
		float nearestMilitary = Float.MAX_VALUE, nearestAny = Float.MAX_VALUE;
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			FactionAPI faction = market.getFaction();
			if (faction == null || faction.isPlayerFaction()) continue;
			if (Factions.THREAT.equals(market.getFactionId())) continue;
			if (Misc.isPirateFaction(faction) || !faction.isShowInIntelTab()) continue;
			if (market.isHidden() || market.isPlanetConditionMarketOnly()) continue;
			if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
			float d = Misc.getDistanceLY(market.getStarSystem().getLocation(),
					system.getLocation());
			if (IncursionManager.hasMilitary(market) && d < nearestMilitary) {
				nearestMilitary = d;
				bestMilitary = market;
			}
			if (d < nearestAny) {
				nearestAny = d;
				bestAny = market;
			}
		}
		return bestMilitary != null ? bestMilitary : bestAny;
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

		// urgency, both ways round: what this world can reach, and how close it
		// has crept to defended space. A hive world inside a navy's operating
		// radius is the sector's immediate problem, not a distant one.
		value *= 1f + 0.5f * strikeUrgency(market);
		value *= 1f + ThreatIncConfig.missionProximityBonus() * factionReachFactor(market);
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
	 * contract type teaches. The margin matters rather than the raw distance - a
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
	 * colony, which still completes the contract because advanceMission only
	 * asks whether the market died, never who killed it. So it also buys
	 * difficulty relief in {@link #difficulty}.
	 */
	protected static float factionReachFactor(MarketAPI market) {
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return 0f;
		// each military world reaches as far as its own fuel buys
		// (IncursionManager.expeditionRangeLY); the deepest coverage counts
		float best = 0f;
		for (MarketAPI base : Global.getSector().getEconomy().getMarketsCopy()) {
			if (base.getFaction() == null || base.getFaction().isPlayerFaction()) continue;
			if (Factions.THREAT.equals(base.getFactionId())) continue;
			if (base.getStarSystem() == null || base.getPrimaryEntity() == null) continue;
			if (!IncursionManager.hasMilitary(base)) continue;
			float range = IncursionManager.expeditionRangeLY(base);
			if (range <= 0f) continue;
			float d = Misc.getDistanceLY(base.getStarSystem().getLocation(), system.getLocation());
			if (d >= range) continue;
			best = Math.max(best, Math.min(1f, (range - d) / range));
		}
		return best;
	}

	/**
	 * What taking this objective would cost a fleet, in abstract units: the
	 * garrison to break, the colony's own size and ground defenses, the burn out
	 * to it - and, for nexus targets, that the organ is buried deepest.
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

		// the nexus is the colony's military organ and the hardest to reach
		if (type == TYPE_NEXUS) d += 4f;

		d += Math.min(6f, distanceFromPlayerLY(market) / 8f);

		// help is real: inside a navy's radius, task forces wear the garrison
		// down and a purge expedition can finish the colony outright - and the
		// contract completes whoever lands the last bombardment
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
	 * offer lands on the staging world itself rather than its cheapest
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
	 * world already covered by an open contract of either tier - posted or
	 * accepted. Objectives of a type already open are discounted: the four
	 * levers are a curriculum, and three simultaneous nexus contracts teach
	 * nothing the first one didn't.
	 */
	public static Objective bestObjective(int tier, List<ThreatMissionIntel> open,
			String excludeMarketId) {
		Set<String> taken = new LinkedHashSet<String>();
		Set<Integer> typesOnBoard = new LinkedHashSet<Integer>();
		for (ThreatMissionIntel curr : open) {
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

	/** This contract's value on its own board today, not when it was posted. */
	public float currentValue() {
		MarketAPI market = getMarket();
		if (market == null) return 0f;
		return valueFor(getTier(), type, market);
	}

	/**
	 * Every open contract - posted (including offers still sitting in the comm
	 * queue, not yet received by the player) and accepted.
	 */
	public static List<ThreatMissionIntel> getOpen() {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		List<IntelInfoPlugin> all = new ArrayList<IntelInfoPlugin>();
		all.addAll(Global.getSector().getIntelManager().getIntel(ThreatMissionIntel.class));
		all.addAll(Global.getSector().getIntelManager().getCommQueue(ThreatMissionIntel.class));
		for (IntelInfoPlugin curr : all) {
			ThreatMissionIntel mission = (ThreatMissionIntel) curr;
			if (mission.isEnded() || mission.isEnding()) continue;
			if (!mission.isPosted() && !mission.isAccepted()) continue;
			if (result.contains(mission)) continue;
			result.add(mission);
		}
		return result;
	}

	/** Offers on the board awaiting acceptance. These are what the cap counts. */
	public static List<ThreatMissionIntel> getPosted() {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		for (ThreatMissionIntel curr : getOpen()) {
			if (curr.isPosted()) result.add(curr);
		}
		return result;
	}

	/** Contracts the player has accepted and is working. */
	public static List<ThreatMissionIntel> getAccepted() {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		for (ThreatMissionIntel curr : getOpen()) {
			if (curr.isAccepted()) result.add(curr);
		}
		return result;
	}

	/** Offers awaiting acceptance on one board. */
	public static List<ThreatMissionIntel> getPosted(int tier) {
		List<ThreatMissionIntel> result = new ArrayList<ThreatMissionIntel>();
		for (ThreatMissionIntel curr : getPosted()) {
			if (curr.getTier() == tier) result.add(curr);
		}
		return result;
	}

	/**
	 * Where this offer sits on its own board: 1 is that board's top entry.
	 * Ranked against the other OFFERS only - accepted contracts are the
	 * player's, and are off the board.
	 */
	public int currentRank() {
		float mine = currentValue();
		int rank = 1;
		for (ThreatMissionIntel curr : getPosted(getTier())) {
			if (curr == this) continue;
			if (curr.currentValue() > mine) rank++;
		}
		return rank;
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	protected boolean isTargetDisrupted(MarketAPI market) {
		Industry industry = market.getIndustry(industryIdFor(type, market));
		return industry != null && industry.isDisrupted();
	}

	@Override
	public void advanceImpl(float amount) {
		if (isPosted()) {
			// an offer against a target that is already dead or already
			// disrupted is moot: nobody pays for work done before the contract
			MarketAPI market = getMarket();
			if (market == null || isTargetDisrupted(market)) {
				cancelWithReason("neutralized", null);
				return;
			}
			if (postingDaysRemaining() <= 0f) {
				cancelWithReason("expired", null);
				return;
			}
			return;
		}
		// accepted: the base class runs the completion deadline and calls
		// advanceMission; ended states are inert
		super.advanceImpl(amount);
	}

	@Override
	public void advanceMission(float amount) {
		MarketAPI market = getMarket();
		if (market == null) {
			// colony gone - burned to decivilization: full payout
			complete(reward, "destroyed");
			return;
		}
		// disruption counts for every link, the nexus included: the hive no
		// longer disrupts its own organs at launch (expeditions are paid for
		// in mustered Defense Swarms), so any disruption is enemy action
		if (isTargetDisrupted(market)) {
			complete(reward / 2, "disrupted");
		}
	}

	protected void complete(int payment, String how) {
		Global.getSector().getPlayerFleet().getCargo().getCredits().add(payment);
		MissionCompletionRep rep = new MissionCompletionRep(RepRewards.HIGH, RepLevel.WELCOMING,
				-RepRewards.TINY, RepLevel.INHOSPITABLE);
		ReputationAdjustmentResult result = Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.MISSION_SUCCESS, rep, null, null, true, false),
				factionId);
		MissionResult mr = new MissionResult(payment, result);
		mr.custom = how;
		setMissionResult(mr);
		setMissionState(MissionState.COMPLETED);
		endMission();
		sendUpdateIfPlayerHasIntel(mr, false);
		ThreatIncConfig.log("Mission completed (" + how + "): " + typeNoun() + " on "
				+ targetName() + ", paid " + payment);
	}

	/** How the contract was completed: "destroyed" or "disrupted". Null otherwise. */
	public String getCompletionKind() {
		if (missionResult == null || !(missionResult.custom instanceof String)) return null;
		return (String) missionResult.custom;
	}

	@Override
	public void missionAccepted() {
		MarketAPI market = getMarket();
		if (market != null && market.getPrimaryEntity() != null) {
			Misc.setFlagWithReason(market.getPrimaryEntity().getMemoryWithoutUpdate(),
					MemFlags.ENTITY_MISSION_IMPORTANT, "threatinc_mission", true, getDuration());
			if (market.getStarSystem() != null) {
				ThreatIncData.markDiscovered(market.getStarSystem().getId());
			}
		}
		ThreatIncConfig.log("Mission accepted [" + tierName(tier) + "]: " + typeNoun()
				+ " on " + targetName());
	}

	@Override
	public void endMission() {
		MarketAPI market = getMarket();
		if (market != null && market.getPrimaryEntity() != null) {
			Misc.setFlagWithReason(market.getPrimaryEntity().getMemoryWithoutUpdate(),
					MemFlags.ENTITY_MISSION_IMPORTANT, "threatinc_mission", false, 0f);
		}
		endAfterDelay();
	}

	@Override
	protected MissionResult createAbandonedResult(boolean withPenalty) {
		if (withPenalty) {
			MissionCompletionRep rep = new MissionCompletionRep(RepRewards.HIGH, RepLevel.WELCOMING,
					-RepRewards.TINY, RepLevel.INHOSPITABLE);
			ReputationAdjustmentResult result = Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.MISSION_FAILURE, rep, null, null, true, false),
					factionId);
			return new MissionResult(0, result);
		}
		return new MissionResult();
	}

	@Override
	protected MissionResult createTimeRanOutFailedResult() {
		return createAbandonedResult(true);
	}

	/** Withdraws a posted offer. No-op once accepted: the contract is the player's. */
	protected void cancelWithReason(String reason, String replacement) {
		if (!isPosted()) return;
		cancelReason = reason;
		supersededBy = replacement;
		cancel();
		ThreatIncConfig.log("Mission offer withdrawn (" + reason
				+ (replacement != null ? ", superseded by " + replacement : "") + "): "
				+ typeNoun() + " on " + targetName());
	}

	/** Pulled from the board because a higher-value objective took its slot. */
	public void withdraw(String replacement) {
		cancelWithReason("superseded", replacement);
	}

	/**
	 * Whether the boards are willing to pull this offer. They will not do it
	 * inside the minimum stand period - an offer that evaporates while the
	 * player is three jumps out is not an offer - and never while the player
	 * is standing in the target's system.
	 *
	 * One emergency waives the stand period: the immediate board holding an
	 * offer on inert infrastructure while a hive world with an expedition IN
	 * FLIGHT stands uncovered. A launch is a discrete, loud event - the board
	 * re-issues promptly rather than lecturing about a mining world for two
	 * months while an armada burns toward someone's colony. Churn stays
	 * bounded: the waiver needs an actual uncovered raider, the challenger
	 * must still clear the supersede margin, and once the raider's offer is
	 * posted it is itself the raider and immune to the waiver.
	 */
	public boolean canBeSuperseded() {
		if (!canBeWithdrawn()) return false;
		if (Global.getSector().getClock().getElapsedDaysSince(postedTimestamp)
				>= ThreatIncConfig.missionMinStandDays()) return true;

		if (tier != TIER_IMMEDIATE) return false;
		MarketAPI market = getMarket();
		if (market != null && IncursionManager.isActiveStrikeSource(market)) return false;
		return uncoveredRaiderExists();
	}

	/** A live colony staging an in-flight strike with no open contract on it. */
	protected static boolean uncoveredRaiderExists() {
		Set<String> covered = new LinkedHashSet<String>();
		for (ThreatMissionIntel curr : getOpen()) {
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
	 * stand period above: an accepted contract belongs to the player and is
	 * never withdrawn, and an offer is never pulled out from under a player
	 * standing in its system. Restructuring the board to keep both tiers
	 * covered may waive the stand period - an empty board is a structural
	 * fault worth correcting promptly - but it may never waive these.
	 */
	public boolean canBeWithdrawn() {
		if (!isPosted() || isEnded() || isEnding()) return false;
		MarketAPI market = getMarket();
		if (market == null) return false; // moot already; advanceImpl cancels it
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player != null && market.getStarSystem() != null
				&& market.getStarSystem() == player.getContainingLocation()) return false;
		return true;
	}

	@Override
	public boolean shouldRemoveIntel() {
		// an offer still sitting unreceived in the comm queue never advances,
		// so its posting window is enforced here - the queue polls this. Once
		// received, advanceImpl withdraws it with a proper notice instead.
		if (isPosted() && postingDaysRemaining() <= 0f
				&& Global.getSector().getIntelManager().hasIntelQueued(this)) return true;
		return super.shouldRemoveIntel();
	}

	// ------------------------------------------------------------------
	// presentation
	// ------------------------------------------------------------------

	protected MarketAPI getMarket() {
		return ThreatIncData.resolveColonyMarket(marketId);
	}

	protected MarketAPI getSponsorMarket() {
		if (sponsorMarketId == null) return null;
		return Global.getSector().getEconomy().getMarket(sponsorMarketId);
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
	protected String getMissionTypeNoun() {
		return "contract";
	}

	@Override
	public String getName() {
		return "Cripple Threat " + typeNoun() + " at " + targetName() + getPostfixForState();
	}

	@Override
	public FactionAPI getFactionForUIColors() {
		return getFaction();
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.THREAT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = super.getIntelTags(map);
		tags.add(ThreatIncursionIntel.TAG_THREAT);
		tags.add(factionId);
		return tags;
	}

	@Override
	public SectorEntityToken getMapLocation(SectorMapAPI map) {
		MarketAPI market = getMarket();
		return market != null ? market.getPrimaryEntity() : null;
	}

	@Override
	public String getSmallDescriptionTitle() {
		return getName();
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);
		addBulletPoints(info, mode);
	}

	@Override
	protected void addBulletPoints(TooltipMakerAPI info, ListInfoMode mode) {
		Color h = Misc.getHighlightColor();
		float pad = 3f;
		float opad = 10f;
		float initPad = mode == ListInfoMode.IN_DESC ? opad : pad;
		Color tc = getBulletColorForMode(mode);
		FactionAPI faction = getFaction();

		bullet(info);
		boolean isUpdate = getListInfoParam() != null;

		if (isUpdate) {
			// possible updates: withdrawn, failed, completed
			if (isCompleted() && missionResult != null) {
				if (missionResult.payment > 0) {
					info.addPara("%s received", initPad, tc, h,
							Misc.getDGSCredits(missionResult.payment));
					initPad = 0f;
				}
				if (missionResult.rep1 != null) {
					CoreReputationPlugin.addAdjustmentMessage(missionResult.rep1.delta, faction,
							null, null, null, info, tc, isUpdate, initPad);
				}
			} else if ((isFailed() || isAbandoned()) && missionResult != null
					&& missionResult.rep1 != null) {
				CoreReputationPlugin.addAdjustmentMessage(missionResult.rep1.delta, faction,
						null, null, null, info, tc, isUpdate, initPad);
			}
		} else if (missionResult != null) {
			if (missionResult.payment > 0) {
				info.addPara("%s received", initPad, tc, h,
						Misc.getDGSCredits(missionResult.payment));
				initPad = 0f;
			}
			if (missionResult.rep1 != null) {
				CoreReputationPlugin.addAdjustmentMessage(missionResult.rep1.delta, faction,
						null, null, null, info, tc, isUpdate, initPad);
			}
		} else {
			if (mode != ListInfoMode.IN_DESC) {
				info.addPara("Faction: " + faction.getDisplayName(), initPad, tc,
						faction.getBaseUIColor(), faction.getDisplayName());
				initPad = 0f;
			}
			if (mode != ListInfoMode.IN_DESC) {
				info.addPara("%s objective", initPad, tc, h, tierName(tier));
				initPad = 0f;
			}
			info.addPara("%s reward", initPad, tc, h, Misc.getDGSCredits(reward));
			info.addPara("%s for disruption", 0f, tc, h, Misc.getDGSCredits(reward / 2));
			if (isAccepted()) {
				addDays(info, "remaining", completionDaysRemaining(), tc, 0f);
			} else {
				addDays(info, "remaining to accept", postingDaysRemaining(), tc, 0f);
				addDays(info, "to complete once accepted", durationDays(), tc, 0f);
			}
		}

		unindent(info);
	}

	/** This world's share of the hive's capacity for the targeted link, 0..100. */
	protected int sharePercent(MarketAPI market) {
		int capacity = 0;
		for (MarketAPI provider : providersOf(type)) capacity += provider.getSize();
		if (capacity <= 0) return 0;
		return Math.round(100f * market.getSize() / capacity);
	}

	/** Whether the world can currently put an expedition on a living system. */
	protected boolean canReachLiving(MarketAPI market) {
		float reach = ThreatColonyManager.fuelRangeLY(market);
		float toLiving = nearestInhabitedLY(market);
		return reach > 0f && toLiving != Float.MAX_VALUE && toLiving <= reach;
	}

	protected void addTargetSection(TooltipMakerAPI info, MarketAPI market, FactionAPI faction) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();

		info.addSectionHeading("Target", faction.getBaseUIColor(), faction.getDarkUIColor(),
				Alignment.MID, opad);

		String batteries = "";
		if (market.hasIndustry(Industries.HEAVYBATTERIES)
				|| market.hasIndustry(ThreatColonyManager.THREAT_HEAVY_BATTERIES)) {
			batteries = " and heavy batteries";
		} else if (market.hasIndustry(Industries.GROUNDDEFENSES)
				|| market.hasIndustry(ThreatColonyManager.THREAT_GROUND_DEFENSES)) {
			batteries = " and ground defenses";
		}
		info.addPara(market.getName() + " is a size %s hive colony defended by %s Defense Swarms"
				+ batteries + ". Its population is buried beyond the reach of bombardment; only "
				+ "decline kills it.", opad, h, "" + market.getSize(),
				"" + ThreatColonyManager.countLiveGarrison(market.getId()));

		if (IncursionManager.isActiveStrikeSource(market)) {
			String line = "An expedition staged from this world is in flight.";
			if (ThreatIncConfig.strikeRecallEnabled()) {
				line += IncursionManager.hasPreparingStrikeFrom(market)
						? " It is still being fabricated: break the colony's forge or Swarm Nexus "
								+ "before the fleets depart and the operation is stillborn."
						: " It has already departed and is autonomous; killing this colony stops "
								+ "the next launch, not this one.";
			}
			info.addPara(line, opad);
		} else if (canReachLiving(market)) {
			info.addPara("The swarm can put an expedition on a living system from here. The "
					+ "nearest lies %s light-years out, inside this colony's %s light-year fuel "
					+ "reach.", opad, h, "" + (int) nearestInhabitedLY(market),
					"" + (int) ThreatColonyManager.fuelRangeLY(market));
		} else {
			info.addPara("This colony cannot presently reach a living system with an expedition.",
					opad);
		}

		float decline = ThreatIncData.declineProgress(market.getId());
		if (decline > 0f) {
			info.addPara("Its strata are already failing: %s of the way to the next population "
					+ "stratum lost.", opad, h, (int) (decline * 100f) + "%");
		}
	}

	protected void addAssessmentSection(TooltipMakerAPI info, MarketAPI market, FactionAPI faction) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();

		info.addSectionHeading("Assessment", faction.getBaseUIColor(), faction.getDarkUIColor(),
				Alignment.MID, opad);

		String share = sharePercent(market) + "%";
		String providers = "" + providersOf(type).size();
		switch (type) {
			case TYPE_RARE_MINING:
				info.addPara("This world mines %s of the hive's rare ore, one of %s such mines. "
						+ "Cutting it degrades hull quality and output across the swarm and halts "
						+ "growth on every refinery world." + (providersOf(type).size() == 1
								? " It is the last rare mine the hive holds; erasing it strangles "
										+ "their refining permanently." : ""),
						opad, h, share, providers);
				break;
			case TYPE_FUEL:
				info.addPara("This plant produces %s of the hive's fuel, one of %s. Fuel is the "
						+ "swarm's reach: cutting it shortens every colony's range, grounding "
						+ "raids and freezing expansion.", opad, h, share, providers);
				break;
			case TYPE_REFINING:
				info.addPara("This refinery produces %s of the hive's metals, one of %s. Cutting "
						+ "it starves the forge worlds: fewer hulls, thinner garrisons, stalled "
						+ "growth.", opad, h, share, providers);
				break;
			case TYPE_NEXUS:
				info.addPara("This Swarm Nexus is the colony's military organ. Every Defense Swarm "
						+ "it fields and every expedition it stages is fabricated here, currently "
						+ "at %s of nominal rate.", opad, h,
						(int) (ThreatColonyManager.shipSupplyMult(market) * 100) + "%");
				break;
		}

		StarSystemAPI system = market.getStarSystem();
		if (system != null && ThreatIncData.isOGSystem(system.getId())) {
			info.addPara("It is the home hive that founded the network.", opad);
		} else if (isStrikePlatform(market)) {
			info.addPara("It is a staging platform, able to launch expeditions of its own.", opad);
		}

		info.addPara("Expected resistance: %s.", opad, h,
				resistanceWord(difficulty(type, market)));

		if (factionReachFactor(market) > 0f) {
			info.addPara("Naval forces operate within %s light-years of the target. Expect task "
					+ "forces to wear at the garrison, and possibly a purge expedition to finish "
					+ "the colony; the contract completes whoever lands the last blow.", opad, h,
					"" + (int) nearestFactionMilitaryLY(market));
		} else {
			info.addPara("No fleet the sector can muster operates within reach of this system. "
					+ "Whatever is done here, you do alone.", opad);
		}

		if (isPosted()) {
			String basis = tier == TIER_STRATEGIC
					? "on what cutting it costs the swarm rather than what taking it costs a fleet"
					: "on damage per unit of effort, with worlds actively striking jumping the queue";
			info.addPara("The " + tierName(tier).toLowerCase() + " board ranks this objective %s of "
					+ "%s offered, " + basis + ". The offer may be withdrawn for a better target "
					+ "until accepted.", opad, h, "" + currentRank(), "" + getPosted(tier).size());
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		FactionAPI faction = getFaction();
		FactionAPI threat = Global.getSector().getFaction(Factions.THREAT);

		if (faction.getLogo() != null) info.addImage(faction.getLogo(), width, 128, opad);

		MarketAPI market = getMarket();
		MarketAPI sponsor = getSponsorMarket();
		String sysName = market != null && market.getStarSystem() != null
				? market.getStarSystem().getNameWithLowercaseType() : "an infested system";

		String board = sponsor != null
				? " defense board " + sponsor.getOnOrAt() + " " + sponsor.getName()
				: " defense boards";
		info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + board
				+ " has posted a contract against the " + threat.getDisplayName() + " " + typeNoun()
				+ " on " + targetName() + ", in the " + sysName + ".", opad,
				new Color[] {faction.getBaseUIColor(), threat.getBaseUIColor()},
				faction.getDisplayNameWithArticleWithoutArticle(), threat.getDisplayName());

		if (!isPosted() && !isAccepted()) {
			String kind = getCompletionKind();
			if ("destroyed".equals(kind)) {
				info.addPara("The target was burned to decivilization and the contract paid in full.",
						opad);
			} else if ("disrupted".equals(kind)) {
				info.addPara("The target's operations were disrupted and half the contract paid. "
						+ "Erasing the colony outright would have paid in full.", opad);
			} else if (isCancelled()) {
				if ("superseded".equals(cancelReason)) {
					info.addPara("The defense boards have reassessed the swarm's network and "
							+ "withdrawn this offer" + (supersededBy != null
									? "; the " + tierName(tier).toLowerCase()
											+ " board's priority has moved to the "
											+ threat.getDisplayName() + " " + supersededBy
									: "") + ". The infrastructure it named still runs.", opad);
				} else if ("neutralized".equals(cancelReason)) {
					info.addPara("The target was neutralized before the contract was accepted. "
							+ "Nothing is owed on work done before signing.", opad);
				} else {
					info.addPara("The offer expired unaccepted. The infrastructure it named still "
							+ "runs at full capacity.", opad);
				}
			} else if (isFailed()) {
				info.addPara("The deadline passed with the target still operating.", opad);
			} else if (isAbandoned()) {
				info.addPara("You abandoned this contract. The infrastructure it named still runs.",
						opad);
			}
			addGenericMissionState(info);
			addBulletPoints(info, ListInfoMode.IN_DESC);
			return;
		}

		if (market != null) {
			addTargetSection(info, market, faction);
			addAssessmentSection(info, market, faction);
		}

		info.addPara(Misc.ucFirst(faction.getDisplayNameWithArticle()) + " pays in full for the "
				+ "destruction of the colony, and half for the disruption of its " + typeNoun()
				+ ".", opad, faction.getBaseUIColor(),
				faction.getDisplayNameWithArticleWithoutArticle());
		addBulletPoints(info, ListInfoMode.IN_DESC);

		addGenericMissionState(info);
		addAcceptOrAbandonButton(info, width, "Accept", "Abandon");
	}
}
