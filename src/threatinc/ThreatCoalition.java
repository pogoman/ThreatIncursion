package threatinc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * COALITION - factions acting together, on their own initiative
 * (docs/design-theory.md 8.7, docs/player-aid.md section 5).
 *
 * <p>Two things happen on the slow tick. When a mobilised faction launches a
 * siege against a hive system it posts a CALL for coalitionCallDays; every
 * OTHER mobilised faction with a base in reach that has not yet answered
 * rolls coalitionSupportChance and, on success, sends an Intercept task force
 * to the hive's jump-point so the siege lands under cover.
 *
 * <p>And allies help each other's colonies. Every mobilised faction looks at
 * every other mobilised faction's colonies with a need the sector would post
 * a request for ({@link ThreatAidRequests}): a strike coming that the
 * defenders are outmatched by, or a commodity the depot is exhausted for or
 * has been short of for missionAidShortageDays. Its willingness is its
 * standing with the needy faction - Cooperative 1.0, Friendly 0.75, Welcoming
 * 0.5, Favourable 0.25, anything less nothing - times allyAidChance per tick
 * per need; its means are what its own bases and donor colonies can spare.
 * Aid is a real guard task force or a real convoy, paid from the helper's
 * reserves, that can be lost on the way. The player never helps here - the
 * player helps by choice, from the war board - but IS a possible recipient:
 * an ally will guard a struck player colony or land a convoy at it.
 */
public class ThreatCoalition {

	public static final String KEY_CALLS = "threatinc_coalitionCalls";

	public static class Call {
		public String systemId;
		public String callerFactionId;
		public long issuedTimestamp;
		public List<String> answered = new ArrayList<String>();

		public float daysLeft() {
			return Math.max(0f, ThreatIncConfig.coalitionCallDays()
					- Global.getSector().getClock().getElapsedDaysSince(issuedTimestamp));
		}
	}

	@SuppressWarnings("unchecked")
	public static List<Call> all() {
		Object val = Global.getSector().getPersistentData().get(KEY_CALLS);
		if (!(val instanceof List)) {
			val = new ArrayList<Call>();
			Global.getSector().getPersistentData().put(KEY_CALLS, val);
		}
		return (List<Call>) val;
	}

	public static Call callFor(String systemId) {
		for (Call c : all()) {
			if (systemId.equals(c.systemId)) return c;
		}
		return null;
	}

	/** A mobilised faction's siege posts (or renews) the call for its target system. */
	public static void post(FactionAPI faction, StarSystemAPI system) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.coalitionEnabled()) return;
		if (faction == null || system == null || !ThreatWarState.isAtWar(faction)) return;
		Call c = callFor(system.getId());
		if (c == null) {
			c = new Call();
			c.systemId = system.getId();
			all().add(c);
		}
		c.callerFactionId = faction.getId();
		c.issuedTimestamp = Global.getSector().getClock().getTimestamp();
		c.answered.clear();
		c.answered.add(faction.getId());
		ThreatIncConfig.log("Coalition call posted by " + faction.getId() + " for "
				+ system.getName());
	}

	/** Slow tick: allies answer open calls, then help each other's colonies. */
	public static void tick(Random random) {
		answerCalls(random);
		allyAid(random);
	}

	protected static void answerCalls(Random random) {
		if (all().isEmpty()) return;
		for (Call c : new ArrayList<Call>(all())) {
			if (c.daysLeft() <= 0f) {
				all().remove(c);
				continue;
			}
			StarSystemAPI system = Global.getSector().getStarSystem(c.systemId);
			if (system == null || ThreatIncData.getLiveColonyMarkets(c.systemId).isEmpty()) {
				all().remove(c);
				continue;
			}
			for (String factionId : ThreatWarState.warFactionIds()) {
				if (c.answered.contains(factionId)) continue;
				FactionAPI faction = Global.getSector().getFaction(factionId);
				if (faction == null || faction.isPlayerFaction()) continue; // the player answers by hand
				MarketAPI base = ThreatFleetOrders.pickBase(faction, system.getLocation());
				if (base == null) continue;
				if (random.nextFloat() >= ThreatIncConfig.coalitionSupportChance()) continue;
				ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchIntercept(faction, system);
				c.answered.add(factionId);
				if (o != null) {
					ThreatIncConfig.log("Coalition: " + factionId + " answers " + c.callerFactionId
							+ "'s call at " + system.getName());
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// allies helping each other (docs/player-aid.md section 5)
	// ------------------------------------------------------------------

	/** How far a faction will go for another, 0..1, by its standing with it. */
	public static float willingness(FactionAPI helper, String needyFactionId) {
		if (helper == null || needyFactionId == null) return 0f;
		RepLevel level = helper.getRelationshipLevel(needyFactionId);
		if (level == null) return 0f;
		if (level.isAtWorst(RepLevel.COOPERATIVE)) return 1f;
		if (level.isAtWorst(RepLevel.FRIENDLY)) return 0.75f;
		if (level.isAtWorst(RepLevel.WELCOMING)) return 0.5f;
		if (level.isAtWorst(RepLevel.FAVORABLE)) return 0.25f;
		return 0f;
	}

	/** Whether a guard task force is already bound for or over the colony. */
	protected static boolean guardBoundFor(MarketAPI market) {
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			if (ThreatFleetOrders.KIND_GUARD.equals(o.kind) && market.getId().equals(o.targetId)
					&& o.fleet != null && o.fleet.isAlive()) return true;
		}
		return false;
	}

	protected static boolean convoyBoundFor(MarketAPI market) {
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			if (market.getId().equals(c.toMarketId)) return true;
		}
		return false;
	}

	/**
	 * Each mobilised colony with a need, each other mobilised NPC faction: by
	 * willingness and chance, one helper sends a guard or a convoy. One
	 * helper per need per tick.
	 */
	public static void allyAid(Random random) {
		if (!ThreatWarState.enabled() || !ThreatIncConfig.allyAidEnabled()) return;
		List<String> ids = ThreatWarState.warFactionIds();
		if (ids.size() < 2) return;
		float chance = ThreatIncConfig.allyAidChance();
		for (String needyId : ids) {
			FactionAPI needy = Global.getSector().getFaction(needyId);
			if (needy == null) continue;
			for (MarketAPI market : ThreatReserves.marketsOf(needyId)) {
				if (market.getStarSystem() == null || market.getPrimaryEntity() == null) continue;
				if (ThreatAidRequests.needsDefence(market) && !guardBoundFor(market)) {
					for (String helperId : ids) {
						if (helperId.equals(needyId)) continue;
						FactionAPI helper = Global.getSector().getFaction(helperId);
						if (helper == null || helper.isPlayerFaction()) continue;
						float w = willingness(helper, needyId);
						if (w <= 0f || random.nextFloat() >= w * chance) continue;
						MarketAPI base = ThreatFleetOrders.pickBase(helper, market.getLocationInHyperspace());
						if (base == null) continue;
						ThreatFleetOrders.Order o = ThreatFleetOrders.dispatchGuard(helper, market, base, false);
						if (o == null) continue;
						report(helper, needy, "sends a task force to guard " + market.getName());
						break;
					}
				}
				if (convoyBoundFor(market)) continue;
				for (String c : ThreatReserves.COMMODITIES) {
					if (!ThreatAidRequests.shortageStanding(market, c)) continue;
					int need = ThreatAidRequests.needItems(market, c);
					if (need <= 0) continue;
					boolean sent = false;
					for (String helperId : ids) {
						if (helperId.equals(needyId)) continue;
						FactionAPI helper = Global.getSector().getFaction(helperId);
						if (helper == null || helper.isPlayerFaction()) continue;
						float w = willingness(helper, needyId);
						if (w <= 0f || random.nextFloat() >= w * chance) continue;
						MarketAPI donor = ThreatConvoys.pickAllyDonor(helper, market, c);
						if (donor == null) continue;
						float amount = Math.min(need, Math.min(ThreatConvoys.spare(donor, c),
								ThreatConvoys.capacityFor(c)));
						if (amount < 1f) continue;
						float[] load = new float[ThreatReserves.COMMODITIES.length];
						load[ThreatAid.index(c)] = amount;
						ThreatConvoys.Convoy convoy = ThreatConvoys.dispatch(donor, market, helper, load,
								random, needyId, false);
						if (convoy == null) continue;
						report(helper, needy, "sends " + Misc.getWithDGS((int) amount) + " "
								+ ThreatReserves.label(c) + " from " + donor.getName() + " to "
								+ market.getName());
						sent = true;
						break;
					}
					if (sent) break;
				}
			}
		}
	}

	/** An ally's convoy landed at another faction's colony. */
	public static void onAllyDelivered(ThreatConvoys.Convoy c, MarketAPI base) {
		FactionAPI helper = Global.getSector().getFaction(c.factionId);
		if (helper == null) return;
		String who = base.isPlayerOwned() ? "your colony " + base.getName()
				: base.getName() + " (" + ThreatWarState.displayName(base.getFactionId()) + ")";
		ThreatColonyManager.announce(Misc.ucFirst(helper.getDisplayNameWithArticle())
				+ " convoy has landed " + ThreatFactionView.cargoText(c.marines, c.armaments, c.fuel,
						c.supplies) + " at " + who + ".", Misc.getHighlightColor());
	}

	protected static void report(FactionAPI helper, FactionAPI needy, String what) {
		String standing = helper.getRelationshipLevel(needy.getId()) != null
				? helper.getRelationshipLevel(needy.getId()).getDisplayName() : "";
		ThreatColonyManager.announce(Misc.ucFirst(helper.getDisplayNameWithArticle()) + " " + what
				+ " for " + (needy.isPlayerFaction() ? "you" : needy.getDisplayName())
				+ (standing.isEmpty() ? "" : " - " + standing) + ".", Misc.getHighlightColor());
		ThreatIncConfig.log("Ally aid: " + helper.getId() + " " + what + " for " + needy.getId());
	}
}
