package threatinc;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

/**
 * DEPLOY (the user, 2026-10-29: "deploy should deploy a fleet sized based on hulls modifier (if
 * none then deploy is greyed out), with fuel sized on its modifers, supplies on its etc."; 2026-10-30
 * 22:00: "it should never redeploy/reprovision, just let the player deploy new instances. if they
 * want to amend one already deployed they can recall etc. so the redeploy logic was an
 * overcomplicaiton"): the Domain table's Deploy button. Every press raises a NEW fleet over the
 * row from a colony's free hulls, sized by the Fleet lever - None / Med / Max = nothing / half / all
 * of the hulls free, at most the lever's share of the fleet cap (ThreatIncConfig.fleetMaxFP) - and
 * loaded from that colony's reserve at the other four levers: marines and heavy armaments as cargo
 * (as many as its berths and holds take), fuel and supplies as figures the fleet carries
 * (ThreatReturns.MEM_FUEL_ABOARD, the hold). The fleet is a staged guard over the row
 * (ThreatFleetOrders.KIND_GUARD, until recalled); its hulls stay the colony's (ThreatAidCapacity,
 * rehome) and the Hulls cell reads what is left free. Fleets deployed already are left alone - the
 * Staging group's Recall folds them home (ThreatFleetOrders.fold); nothing is ever resized.
 *
 * <p>The source: the row's colony while its own free hulls at the lever make {@link #minPoints},
 * else the nearest colony of the player's that can ({@link #nearestSource}; the user, 2026-10-30:
 * "cant deploy to arkoman despite having 5k+ hulls at haven") - an outpost's or a hive's row
 * always the nearest - the fleet built and loaded there and sailed over.
 *
 * <p>What is deployed is what an Attack sails (IncursionManager.attackPlan: the nearest colony
 * with a fleet deployed, everything aboard it - the passage out of the fuel first, the rest
 * razes; the Attack button is off while the fleet carries less fuel than the passage or fewer
 * supplies than the expedition's hold). Nothing of this draws for a voyage: the levers are the
 * cost, chosen by the player.
 */
public class ThreatDeploy {

	/** What a Deploy at a row would do at the colonies table's levers. */
	public static class Plan {
		/** Where the fleet stages: the row's colony, or the player's outpost. */
		public ThreatBases.Base target;
		/** Whose hulls and reserve it is built and loaded from: the colony itself, or the nearest colony that can. */
		public MarketAPI source;
		/** Whole-fleet points the new fleet is built to at the Fleet lever. */
		public float targetPts;
		/** The source's own free hulls, whole-fleet points. */
		public float ownFree;
		/** The load at the levers: marines, heavy armaments, fuel, supplies (ThreatReserves.COMMODITIES order). */
		public float[] load = new float[4];
		/** Supplies the fleet's ships eat for the voyage and the stay (ThreatReach.sortieHold): the least the hold is loaded to. */
		public float holdNeed;
		/** The Fleet lever asked for more than its share of one fleet (ThreatIncConfig.fleetMaxFP): targetPts is the cap, the rest stays as hulls. */
		public boolean capped;
		/** Why not, one fact per line; null when the order would go. */
		public ThreatNotice.Reason reason;
		/** The colonies table's levers the plan was made at (ThreatIncursionIntel.colonyLevers). */
		public int[] levers;

		public boolean ok() {
			return reason == null && source != null && target != null;
		}
	}

	/** Smallest fleet a Deploy makes, whole-fleet points (aidGuardMinFP in the ledger's unit). */
	public static float minPoints() {
		return ThreatAidCapacity.taskForcePoints(ThreatIncConfig.aidGuardMinFP());
	}

	/** The Fleet lever's tier of a colonies-table lever set (ThreatIncursionIntel.colonyLevers, the sixth entry), 0 when it has none. */
	protected static int hullsTier(int[] levers) {
		// the sixth lever since 2026-10-31 (the user: "add the fleet as an extra toggle ... it deploys the fleet via
		// transfer button"); the Hulls lever (the fifth) is the convoy's hull cargo
		return levers != null && levers.length > 5 ? levers[5] : 0;
	}

	/**
	 * The plan for a Deploy at the target at the levers: a NEW fleet every time, whatever is deployed
	 * there already. The source: the row's colony while its own free hulls at the Fleet lever make
	 * {@link #minPoints}, else the nearest colony that can ({@link #nearestSource}; the user,
	 * 2026-10-30: "cant deploy to arkoman despite having 5k+ hulls at haven"); an outpost's or a
	 * hive's the nearest. The hulls: the lever's share of the source's free hulls, at most the
	 * lever's share of one fleet (ThreatIncConfig.fleetMaxFP); under {@link #minPoints} the plan is
	 * refused with the figures. Each good: the lever's share of what the source spares
	 * (ThreatConvoys.handSpare) - marines and arms are then cut to the fleet's berths and holds
	 * when it is loaded.
	 */
	public static Plan plan(ThreatBases.Base target, int[] levers) {
		Plan p = new Plan();
		p.target = target;
		p.levers = levers != null ? levers : new int[] { 1, 1, 1, 1, 1, 1 };
		if (target == null || target.entity() == null) {
			p.reason = ThreatNotice.Reason.of("No target");
			return p;
		}
		if (!ThreatAidCapacity.enabled()) {
			p.reason = ThreatNotice.Reason.of("Player aid is disabled in the mod settings");
			return p;
		}
		int hulls = hullsTier(levers);
		if (hulls <= 0) {
			p.reason = ThreatNotice.Reason.of("The Fleet lever is at None")
					.line("Med or Max deploys half or all of the hulls free");
			return p;
		}
		float share = ThreatConvoys.share(hulls);
		MarketAPI colony = target.market != null && target.market.isPlayerOwned() ? target.market : null;
		float colonyFree = colony != null ? Math.max(0f, ThreatAidCapacity.ownFreeFP(colony)) : 0f;
		if (colony != null && share * colonyFree >= minPoints() - 0.5f) {
			p.source = colony;
		} else {
			// an outpost's, a hive the player's landing attacks (the Domain table's row), or a colony short
			// of hulls of its own: built at the nearest colony that can field the fleet and sailed over
			p.source = nearestSource(target, levers, colony);
		}
		if (p.source == null) {
			p.reason = colony != null
					? ThreatNotice.Reason.of("%s has %s fleet points free", ThreatNotice.market(colony),
							Misc.getWithDGS((int) colonyFree))
							.line("No colony of yours can field a fleet for it at the Fleet lever")
					: ThreatNotice.Reason.of("No colony of yours can field a fleet for %s at the Fleet lever",
							target.name());
			p.reason.line("A fleet needs at least %s fleet points", Misc.getWithDGS((int) Math.ceil(minPoints())));
			return p;
		}
		p.ownFree = Math.max(0f, ThreatAidCapacity.ownFreeFP(p.source));
		p.targetPts = (float) Math.floor(share * p.ownFree);
		// THE FLEET CAP (2026-10-29): one fleet holds at most fleetMaxFP, the rest stays as hulls - a Deploy at
		// Haven came out at 7,325 FP on the capped jar, the one build outside the cap; and the cap is the
		// lever's share of it (the user, 2026-10-30 21:00: "med capped 1.2k full capped 2.4k" - Med and Max
		// both asked for the whole cap at a colony with more than it free)
		float cap = (float) Math.floor(share * ThreatIncConfig.fleetMaxFP());
		if (p.targetPts > cap) {
			p.targetPts = cap;
			p.capped = true;
		}
		if (p.targetPts < minPoints() - 0.5f) {
			p.reason = ThreatNotice.Reason.of("%s has %s fleet points free", ThreatNotice.market(p.source),
					Misc.getWithDGS((int) p.ownFree))
					.line("The Fleet lever makes a fleet of %s", Misc.getWithDGS((int) p.targetPts))
					.line("A fleet needs at least %s", Misc.getWithDGS((int) Math.ceil(minPoints())));
			return p;
		}
		for (int i = 0; i < 4 && i < p.levers.length; i++) {
			float spare = ThreatConvoys.handSpare(p.source, ThreatReserves.COMMODITIES[i]);
			p.load[i] = (float) Math.floor(ThreatConvoys.share(p.levers[i]) * spare);
		}
		// THE HOLD (the user, 2026-10-31: "i ordered task force to deploy to ogre and it just gave up and went
		// back for no reason" - built at Arkoman, whose front spares none of its supplies to a lever, it sailed
		// "Aboard: nothing" and stood down "supplies low, 0 aboard" in hyperspace): the Supplies lever never
		// loads less than the ships eat for the voyage and the stay (ThreatReach.sortieHold, as every sortie
		// carries, docs/fleet-provisions.md), drawn from the source's stock above its floor
		float ly = ThreatFleetOrders.sortieLY(p.source, target.hyperLoc());
		float fp = p.targetPts / ThreatAidCapacity.TASK_FORCE_HULL_MULT;
		// IN ITS OWN SYSTEM (the user, 2026-10-31, Deploy at Haven greyed "Arkoman has 993 supplies / The fleet's
		// ships eat 2,688 on the way and there" - the two worlds share a system: "it would take the fleets 5 seconds
		// to move to the other planet"): the hold is the hop alone, a day's burn - the fleet restocks from the
		// system's depots as it eats (ThreatUpkeep.restockFleet, Haven's and Arkoman's alike), so the stay is never
		// priced up front; the voyage-and-stay hold is for a fleet that sails beyond, where nothing restocks it
		float hold = ThreatReach.sortieHold(p.source.getFactionId(), fp, ly);
		if (ly < 0.01f && ThreatIncConfig.fleetResupply()) {
			hold = Math.min(hold, ThreatReach.tripSupplies(p.source.getFactionId(), fp, 1f));
		}
		p.holdNeed = (float) Math.ceil(hold);
		if (p.load[3] < p.holdNeed) {
			float have = ThreatReserves.available(p.source, Commodities.SUPPLIES);
			if (have < p.holdNeed - 0.5f) {
				p.reason = ThreatNotice.Reason.of("%s has %s supplies", ThreatNotice.market(p.source),
						Misc.getWithDGS((int) have))
						.line("The fleet's ships eat %s on the way and there", Misc.getWithDGS((int) p.holdNeed));
				return p;
			}
			p.load[3] = p.holdNeed;
		}
		return p;
	}

	/**
	 * The colony of the player's a Deploy builds at when the target has no hulls of its own (an
	 * outpost, a hive, a colony short of them): the nearest, {@code not} excepted, whose own free
	 * hulls at the Fleet lever's share make at least {@link #minPoints}, or null.
	 */
	protected static MarketAPI nearestSource(ThreatBases.Base target, int[] levers, MarketAPI not) {
		if (target == null || target.hyperLoc() == null) return null;
		float share = ThreatConvoys.share(hullsTier(levers));
		if (share <= 0f) return null;
		MarketAPI best = null;
		float bestDist = Float.MAX_VALUE;
		for (MarketAPI m : ThreatReserves.marketsOf(Global.getSector().getPlayerFaction().getId())) {
			if (m == not || m.getStarSystem() == null || !ThreatAidCapacity.applies(m)) continue;
			if (share * ThreatAidCapacity.ownFreeFP(m) < minPoints() - 0.5f) continue;
			float d = Misc.getDistanceLY(m.getStarSystem().getLocation(), target.hyperLoc());
			if (d < bestDist) {
				bestDist = d;
				best = m;
			}
		}
		return best;
	}

	/** The button's one-line tooltip while it is off: the plan's reason, or the order gate's. */
	public static String offReason(Plan p) {
		return p == null ? null : ThreatNotice.text(p.reason);
	}

	/** "320 marines, 400 arms, 1,200 fuel and 3,000 supplies" - a load's non-zero parts, or "nothing". */
	public static String loadText(float[] load) {
		List<String> parts = new ArrayList<String>();
		if (load[0] > 0.5f) parts.add(Misc.getWithDGS((int) load[0]) + " marines");
		if (load[1] > 0.5f) parts.add(Misc.getWithDGS((int) load[1]) + " arms");
		if (load[2] > 0.5f) parts.add(Misc.getWithDGS((int) load[2]) + " fuel");
		if (load[3] > 0.5f) parts.add(Misc.getWithDGS((int) load[3]) + " supplies");
		return parts.isEmpty() ? "nothing" : Misc.getAndJoined(parts);
	}

	/**
	 * Carries the plan out for the player: a new fleet built at the source, loaded to the levers
	 * and sent to the target as a staged guard, the notice sent. Fleets deployed there already are
	 * left as they are. Returns a word for the log, or null when nothing happened (a refusal
	 * notice says why).
	 */
	public static String execute(Plan p, FactionAPI faction) {
		if (p == null || faction == null || !faction.isPlayerFaction() || p.target == null) return null;
		if (!p.ok()) {
			ThreatNotice.titled("Deploy Refused").bad().icon(faction).lines(p.reason).send();
			return null;
		}
		MarketAPI source = p.source;
		String label = "fleet deployed over " + p.target.name();
		float[] got = new float[] {0f};
		CampaignFleetAPI lead = build(source, faction, p.targetPts / ThreatAidCapacity.TASK_FORCE_HULL_MULT, null,
				p.target, label, got);
		if (lead == null) {
			ThreatNotice.titled("Deploy Refused").bad().icon(faction)
					.line("No fleet could be raised at %s", ThreatNotice.market(source)).send();
			return null;
		}
		// the colony's staged guard, until recalled; over its own colony it is on station now
		// (ThreatFleetOrders.poll would say so at the next pass)
		ThreatFleetOrders.Order order = ThreatFleetOrders.guardOrder(lead, faction, p.target, source,
				ThreatIncConfig.guardOwnDays(), false);
		boolean self = p.target.market != null && p.target.market == source;
		if (self) {
			order.arrived = true;
			ThreatFleetOrders.rehome(order);
		}
		float[] back = new float[4];
		float[] now = load(lead, source, p.levers, p.holdNeed, back);
		float live = ThreatAidCapacity.liveFP(lead);
		ThreatNotice n = ThreatNotice.titled("Fleet Deployed").icon(faction)
				.line(self ? "Over %s: %s fleet points" : "For %s: %s fleet points", ThreatNotice.base(p.target),
						Misc.getWithDGS((int) live));
		if (!self) n.line("From %s's hulls and reserve", ThreatNotice.market(source));
		n.line("Aboard: %s", loadText(now));
		n.send();
		ThreatIncConfig.log("Deploy at " + p.target.name() + " from " + source.getName() + ": " + (int) live
				+ " FP (" + (int) got[0] + " built), aboard " + loadText(now));
		ThreatAidCapacity.logHulls("after deploy at " + p.target.name());
		return "deploy";
	}

	/**
	 * Builds {@code combat} combat points at the source as fleets of one fleet's size
	 * (ThreatSoftening.fleetCapFor; vanilla's factory stops at maxShipsInAIFleet ships) and folds
	 * each into {@code lead} - or the first built, when there is none - with its ledger points
	 * committed to the source and riding the lead (ThreatAidCapacity.commit). No draw: the levers
	 * load the fleet ({@link #load}). Returns the lead, or null when nothing could be built;
	 * {@code builtOut}[0] takes the points built.
	 */
	protected static CampaignFleetAPI build(MarketAPI source, FactionAPI faction, float combat, CampaignFleetAPI lead,
			ThreatBases.Base target, String label, float[] builtOut) {
		float got = 0f;
		int fleets = 0;
		while (got < combat - 1f && fleets < 40) {
			float left = combat - got;
			float ask = Math.min(left, ThreatSoftening.fleetCapFor(source, faction.getId(), left));
			if (fleets > 0 && ask < ThreatIncConfig.aidGuardMinFP()) break;
			CampaignFleetAPI fleet = ThreatFleetOrders.buildTaskForce(source, faction, ask, target.hyperLoc(), false);
			if (fleet == null) break;
			float fp = ThreatSoftening.combatFP(fleet);
			if (fp < 1f) {
				Misc.fadeAndExpire(fleet);
				break;
			}
			ThreatSoftening.learnFleetCap(faction.getId(), fleet, ask);
			float points = ThreatFleetOrders.builtPoints(fleet, ask);
			fleets++;
			got += fp;
			if (lead == null) {
				lead = fleet;
				ThreatAidCapacity.commit(source, points, lead, label);
				builtOut[0] += points;
				continue;
			}
			ThreatAidCapacity.commit(source, points, lead, label);
			builtOut[0] += points;
			ThreatFleetOrders.absorb(lead, fleet);
		}
		return lead;
	}


	/**
	 * Loads the fleet to the plan's levers from the source's reserve: each good's figure at the
	 * lever less what is aboard is drawn (ThreatReserves.drawAbove) or put back
	 * (ThreatReserves.deposit) - marines within the berths, heavy armaments within the holds
	 * - a new fleet carries nothing, so nothing goes back unless the berths or holds are short. Returns what is aboard after;
	 * {@code backOut} takes what went back. The hold is never under {@code holdNeed} ({@link Plan#holdNeed}),
	 * drawn above the source's floor whatever its front spares.
	 */
	protected static float[] load(CampaignFleetAPI fleet, MarketAPI source, int[] levers, float holdNeed, float[] backOut) {
		float[] now = new float[4];
		if (fleet == null || source == null || levers == null) return now;
		// the levers again, against the fleet as built
		CargoAPI cargo = fleet.getCargo();
		float[] aboard = new float[] { cargo.getMarines(), cargo.getCommodityQuantity(Commodities.HAND_WEAPONS),
				ThreatReturns.fuelAboard(fleet), ThreatReturns.hold(fleet) };
		float[] want = new float[4];
		for (int i = 0; i < 4; i++) {
			float spare = ThreatConvoys.handSpare(source, ThreatReserves.COMMODITIES[i]);
			want[i] = (float) Math.floor(ThreatConvoys.share(i < levers.length ? levers[i] : 0) * (spare + aboard[i]));
		}
		want[3] = Math.max(want[3], holdNeed);
		// marines within the berths, arms within the holds (supplies and fuel ride as figures)
		want[0] = Math.max(0f, Math.min(want[0], aboard[0] + cargo.getFreeCrewSpace()));
		want[1] = Math.max(0f, Math.min(want[1], aboard[1] + cargo.getSpaceLeft()));
		for (int i = 0; i < 4; i++) {
			String good = ThreatReserves.COMMODITIES[i];
			float delta = want[i] - aboard[i];
			float after = aboard[i];
			if (delta >= 1f) {
				float got = (float) Math.floor(ThreatReserves.drawAbove(source, good, delta));
				after += got;
			} else if (delta <= -1f) {
				float back = Math.min(aboard[i], (float) Math.floor(-delta));
				if (back < 1f) continue;
				ThreatReserves.deposit(source.getId(), good, back);
				backOut[i] += back;
				after -= back;
			}
			switch (i) {
				case 0:
					if (after > aboard[0]) cargo.addMarines((int) (after - aboard[0]));
					else if (after < aboard[0]) cargo.removeMarines((int) (aboard[0] - after));
					break;
				case 1:
					if (after > aboard[1]) cargo.addCommodity(Commodities.HAND_WEAPONS, after - aboard[1]);
					else if (after < aboard[1]) cargo.removeCommodity(Commodities.HAND_WEAPONS, aboard[1] - after);
					break;
				case 2:
					ThreatReturns.setFuelAboard(fleet, after);
					break;
				default:
					ThreatReturns.setHold(fleet, after);
					break;
			}
			now[i] = after;
		}
		return now;
	}

	/** Whether the guard order is a fleet deployed over the player's own colony (the fleets table's "Deployed"). */
	public static boolean isDeployed(ThreatFleetOrders.Order o) {
		if (o == null || !ThreatFleetOrders.KIND_GUARD.equals(o.kind) || o.aid || o.recipientFactionId != null) return false;
		if (!Global.getSector().getPlayerFaction().getId().equals(o.factionId)) return false;
		MarketAPI host = o.targetId != null ? Global.getSector().getEconomy().getMarket(o.targetId) : null;
		return host != null && host.isPlayerOwned();
	}
}
