package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE OFFENSIVE - the swarm's grand strategy (the user, 2026-10-07: "they should go
 * through periods where they are saving and then do a multi pronged attack on all
 * known systems ... if they are losing ... focus on nearby bases only ... it depends
 * on their resources vs the known resources of their enemy").
 *
 * Every monthly pass (IncursionManager.tryStrikes) the hive prices a strike at every
 * target system it knows - one prong a system, from the nearest hive system that can
 * stage it, sized by stagedPlan to outweigh the strongest defence seen there - and
 * reads its means: the strike fund, and what the fund gains a month until the
 * campaign's deadline. The campaign is the most valuable set of prongs the means pay
 * by the deadline (value per fleet point, the stance's weights); nothing sails until
 * the fund holds the whole set's bill, then every prong launches in the same pass.
 * At the deadline the fund pays what it can and a new campaign starts. Losing
 * (ThreatStance.losing), the candidates are only the targets within offensiveNearLY
 * of a live hive, spoiling blows first, on the shorter offensiveLosingMonths horizon.
 * A front of its own short of troops gets its relief strike at once, outside the
 * campaign (strikeReliefFirst), as before. The war's opening strikes keep their floor
 * (strikeStagedMinFP). Off (swarmOffensive): the closest payable target, one a pass.
 * State: threatinc_offensive -> {campaign start day}. docs/strikes-staged.md.
 */
public class ThreatOffensive {

	protected static final String KEY = "threatinc_offensive";

	/** One strike of the campaign: the target, who stages it and what it costs the fund. */
	protected static class Prong {
		MarketAPI target, staging;
		StarSystemAPI source;
		/** The fund's bill, the strike's fleet points, its passage fuel (round trip) and days away. */
		float cost, fp, fuel, days, ly, score, def, expected;
	}

	protected static float startDay() {
		Object v = Global.getSector().getPersistentData().get(KEY);
		return v instanceof float[] && ((float[]) v).length > 0 ? ((float[]) v)[0] : -1f;
	}

	protected static void setStartDay(float day) {
		Global.getSector().getPersistentData().put(KEY, new float[] { day });
	}

	/** The monthly pass: relief strikes at once, then the campaign - saved for, or launched whole. */
	public static void pass(IncursionManager im) {
		float day = ThreatPosture.today();
		Map<String, MarketAPI> stagings = new HashMap<String, MarketAPI>();
		Map<String, StarSystemAPI> sources = new HashMap<String, StarSystemAPI>();
		for (String systemId : new ArrayList<String>(ThreatIncData.colonyMarkets().keySet())) {
			MarketAPI colony = ThreatColonyManager.pickStrikeStaging(systemId, true);
			StarSystemAPI source = im.getSystem(systemId);
			if (colony == null || source == null) continue;
			stagings.put(systemId, colony);
			sources.put(systemId, source);
		}
		if (stagings.isEmpty()) return;
		List<MarketAPI> candidates = im.stagedCandidates(null);
		for (MarketAPI m : new ArrayList<MarketAPI>(candidates)) {
			if (scheduled(m)) candidates.remove(m);
		}
		if (candidates.isEmpty()) return;

		// relief first: a front of the swarm's own short of troops (strikeReliefFirst)
		if (ThreatIncConfig.strikeReliefFirst()) {
			for (MarketAPI target : new ArrayList<MarketAPI>(candidates)) {
				if (!ThreatGroundFronts.wantsExpedition(target)) continue;
				String near = nearest(target, sources);
				if (near == null) continue;
				if (im.launchStrike(stagings.get(near), sources.get(near), target) != null) {
					candidates.remove(target);
					if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(near);
				}
			}
		}

		// the prongs: one a target system, sized for the strongest defence seen in it
		boolean losing = ThreatStance.losing();
		float nearLY = Math.max(0f, ThreatIncConfig.offensiveNearLY());
		Map<String, float[]> memo = new HashMap<String, float[]>();
		Map<String, Prong> bySystem = new HashMap<String, Prong>();
		float margin = Math.max(1f, ThreatIncConfig.strikeStagedMargin());
		for (MarketAPI target : candidates) {
			if (target.getStarSystem() == null) continue;
			String near = nearest(target, sources);
			if (near == null) continue;
			float ly = Misc.getDistanceLY(sources.get(near).getLocation(), target.getStarSystem().getLocation());
			if (losing && nearLY > 0f && ly > nearLY) continue;
			float def = IncursionManager.targetDefence(target, memo);
			if (def >= Float.MAX_VALUE) continue;
			Prong have = bySystem.get(target.getStarSystem().getId());
			if (have != null && have.def >= def) continue;
			Prong p = new Prong();
			p.target = target;
			p.staging = stagings.get(near);
			p.source = sources.get(near);
			p.ly = ly;
			p.def = def;
			bySystem.put(target.getStarSystem().getId(), p);
		}
		// what each faction can answer with, as seen: its navy seen elsewhere is shared among the
		// prongs at it (the user, 2026-10-07: the swarm knows what it has seen, not the total)
		Map<String, Integer> atFaction = new HashMap<String, Integer>();
		for (Prong p : bySystem.values()) {
			Integer n = atFaction.get(p.target.getFactionId());
			atFaction.put(p.target.getFactionId(), n == null ? 1 : n + 1);
		}
		List<Prong> prongs = new ArrayList<Prong>();
		for (Prong p : bySystem.values()) {
			if (!price(im, p, memo, atFaction.get(p.target.getFactionId()))) continue;
			float value = IncursionManager.strikeValue(p.target)
					* ThreatStance.strikeTargetMult(p.target, p.source, 1f / margin);
			// losing: the spoiling blow - a base staging against a hive, a forward base - and the near first
			if (losing && (ThreatFrontlines.isOutpost(p.target) || ThreatStance.stagingAgainstHive(p.target))) value *= 10f;
			p.score = value / Math.max(1f, p.cost) / (losing ? Math.max(1f, p.ly) : 1f);
			prongs.add(p);
		}
		java.util.Collections.sort(prongs, new java.util.Comparator<Prong>() {
			public int compare(Prong a, Prong b) {
				int c = Float.compare(b.score, a.score);
				return c != 0 ? c : Float.compare(a.ly, b.ly);
			}
		});

		// the means by the deadline, and the campaign they pay
		float fund = ThreatColonyManager.strikeFund();
		float perMonth = ThreatColonyManager.strikeFundPerMonth();
		float horizon = Math.max(1f, losing ? ThreatIncConfig.offensiveLosingMonths() : ThreatIncConfig.offensiveHorizonMonths());
		float start = startDay();
		if (start < 0f || start > day) {
			start = day;
			setStartDay(day);
		}
		float deadline = start + horizon * 30f;
		float monthsLeft = Math.max(0f, (deadline - day) / 30f);
		float budget = fund + perMonth * monthsLeft;
		// the fuel and the supplies away are summed over the prongs too (hw42a: priced one at a time, the
		// first prong's passage took the whole stock and the held one could not sail on its day)
		float fuelStock = ThreatFuel.stock(), fuelBudget = fuelStock + Math.max(0f, ThreatFuel.perMonth()) * monthsLeft;
		List<Prong> campaign = new ArrayList<Prong>();
		float cost = 0f, fuel = 0f, away = 0f, longest = 0f;
		for (Prong p : prongs) {
			if (cost + p.cost > budget || fuel + p.fuel > fuelBudget) continue;
			if (away + p.fp > ThreatReach.sustainableFP(Math.max(longest, p.days))) continue;
			campaign.add(p);
			cost += p.cost;
			fuel += p.fuel;
			away += p.fp;
			longest = Math.max(longest, p.days);
		}
		// fewer prongs at a faction than were priced: each meets a bigger share of its navy - re-price
		// the set, and drop from its tail while the means no longer pay it
		Map<String, Integer> inCampaign = new HashMap<String, Integer>();
		for (Prong p : campaign) {
			Integer n = inCampaign.get(p.target.getFactionId());
			inCampaign.put(p.target.getFactionId(), n == null ? 1 : n + 1);
		}
		for (Prong p : new ArrayList<Prong>(campaign)) {
			if (!price(im, p, memo, inCampaign.get(p.target.getFactionId()))) campaign.remove(p);
		}
		while (!campaign.isEmpty()) {
			cost = 0f;
			fuel = 0f;
			away = 0f;
			longest = 0f;
			for (Prong p : campaign) {
				cost += p.cost;
				fuel += p.fuel;
				away += p.fp;
				longest = Math.max(longest, p.days);
			}
			if (cost <= budget && fuel <= fuelBudget && away <= ThreatReach.sustainableFP(longest)) break;
			campaign.remove(campaign.size() - 1);
		}
		String where = losing ? "losing, within " + (int) nearLY + " ly" : "all known";
		if (campaign.isEmpty()) {
			ThreatIncConfig.logQuiet("offensive", "Offensive: nothing the fund pays by the deadline - " + prongs.size()
					+ " target(s) " + where + ", fund " + (int) fund + " FP (+" + (int) perMonth + "/mo), "
					+ (int) monthsLeft + " month(s) left");
			if (day >= deadline) setStartDay(day);
			return;
		}
		if ((fund < cost || fuelStock < fuel) && day < deadline) {
			// the fuel it waits on is demand on the stock: the planner builds the plants (ThreatFuel.heldShort)
			if (fuelStock < fuel && ThreatIncConfig.strikeWaitBooksFuel()) ThreatFuel.heldShort("the offensive", fuel);
			ThreatIncConfig.log("Offensive: saving for " + campaign.size() + " of " + prongs.size() + " target(s) " + where
					+ ", " + (int) cost + " FP and " + (int) fuel + " fuel; fund " + (int) fund + " FP (+" + (int) perMonth
					+ "/mo), fuel " + (int) fuelStock + " (+" + (int) ThreatFuel.perMonth() + "/mo), launch in "
					+ (int) Math.ceil(Math.max(Math.max(0f, cost - fund) / Math.max(1f, perMonth),
							Math.max(0f, fuel - fuelStock) / Math.max(1f, ThreatFuel.perMonth())))
					+ " month(s), deadline in " + (int) monthsLeft + "; first " + names(campaign, 4));
			return;
		}

		// launch: every prong of the campaign the fund pays, the most valuable first - the farthest
		// sails today and the rest wait so all arrive together (the user, 2026-10-07): a prong's
		// bill leaves the fund now and comes back the day it sails (poll)
		float arriveIn = 0f;
		for (Prong p : campaign) arriveIn = Math.max(arriveIn, arrival(p.ly));
		int sent = 0, held = 0;
		float sentFP = 0f;
		StringBuilder at = new StringBuilder();
		for (Prong p : campaign) {
			if (p.cost > ThreatColonyManager.strikeFund() + 0.5f || p.fuel > ThreatFuel.stock() + 0.5f) continue;
			if (IncursionManager.isActiveStrikeTarget(p.target)) continue;
			float wait = arriveIn - arrival(p.ly);
			if (wait >= 1f) {
				// its bill and its passage fuel are set aside until its day (neither spent nor demanded yet)
				ThreatColonyManager.addStrikeFund(-p.cost);
				ThreatFuel.setStock(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, ThreatFuel.stock() - p.fuel);
				schedule().add(p.target.getId() + "|" + p.staging.getId() + "|" + p.source.getId() + "|"
						+ (day + wait) + "|" + p.cost + "|" + p.fuel + "|" + p.expected);
				held++;
			} else {
				if (im.launchStrike(p.staging, p.source, p.target, p.expected) == null) continue;
				if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(p.source.getId());
				sent++;
			}
			sentFP += p.cost;
			if (at.length() > 0) at.append(", ");
			at.append(p.target.getName()).append(" (").append(p.target.getFactionId()).append(", ").append((int) p.ly)
					.append(" ly, ").append((int) p.cost).append(" FP for ").append((int) p.expected).append(" expected").append(wait >= 1f ? ", in " + (int) wait + " d" : "")
					.append(")");
		}
		setStartDay(day);
		ThreatIncConfig.log("Offensive launched: " + sent + " strike(s) now and " + held + " to follow, of " + campaign.size()
				+ " planned, " + (int) sentFP + " FP, arriving together in ~" + (int) arriveIn + " days (" + where
				+ (day >= deadline && fund < cost ? ", at the deadline" : "") + "); fund left "
				+ (int) ThreatColonyManager.strikeFund() + " FP; at " + at);
	}

	/**
	 * Prices the prong for the answer it expects: the day's defence seen there, plus the faction's
	 * navy seen elsewhere (ThreatSwarmIntel.knownNavyFP) shared among the nAtFaction prongs at that
	 * faction, times what the faction's worlds have met earlier strikes with over what they were
	 * sized for (responseRatio); stagedPlan against an unlimited bank. False when nothing pays it.
	 */
	protected static boolean price(IncursionManager im, Prong p, Map<String, float[]> memo, Integer nAtFaction) {
		String f = p.target.getFactionId();
		int n = nAtFaction != null ? Math.max(1, nAtFaction) : 1;
		float navy = ThreatSwarmIntel.knownNavyFP(f, p.target.getStarSystem().getId());
		p.expected = (p.def + navy / n) * ThreatSwarmIntel.responseRatio(f);
		IncursionManager.StagedPlan plan = im.stagedPlan(p.staging, p.source, p.target, memo, null, null, Float.MAX_VALUE,
				p.expected);
		if (plan == null || plan.bankFP <= 0f) return false;
		p.cost = plan.bankFP;
		p.fp = plan.fp;
		p.fuel = ThreatFuel.passage(plan.fp, p.ly, true);
		p.days = ThreatReach.strikeDays(p.ly);
		return true;
	}

	/** Days from launch to the target: the muster and the crossing at the board's estimated speed. */
	protected static float arrival(float ly) {
		return ThreatReach.STRIKE_PREP_DAYS + ThreatReach.days(ly);
	}

	// ------------------------------------------------------------------
	// the prongs held back to arrive with the farthest
	// ------------------------------------------------------------------

	protected static final String KEY_SCHEDULE = "threatinc_offensiveSchedule";

	/** "targetId|stagingId|sourceSystemId|launchDay|cost" per prong waiting its day. */
	@SuppressWarnings("unchecked")
	protected static List<String> schedule() {
		Object v = Global.getSector().getPersistentData().get(KEY_SCHEDULE);
		if (v instanceof List) return (List<String>) v;
		List<String> list = new ArrayList<String>();
		Global.getSector().getPersistentData().put(KEY_SCHEDULE, list);
		return list;
	}

	/** Whether a prong at the world waits its day (no second strike is planned at it). */
	public static boolean scheduled(MarketAPI market) {
		if (market == null) return false;
		for (String e : schedule()) {
			if (e.startsWith(market.getId() + "|")) return true;
		}
		return false;
	}

	/** Daily (IncursionManager.advance): every prong whose day has come sails, its bill back in the fund for launchStrike to draw. */
	public static void poll() {
		if (IncursionManager.instance == null || schedule().isEmpty()) return;
		float day = ThreatPosture.today();
		for (String e : new ArrayList<String>(schedule())) {
			String[] f = e.split("\\|");
			if (f.length < 5) {
				schedule().remove(e);
				continue;
			}
			float launchDay, cost, fuel, expected;
			try {
				launchDay = Float.parseFloat(f[3]);
				cost = Float.parseFloat(f[4]);
				fuel = f.length > 5 ? Float.parseFloat(f[5]) : 0f;
				expected = f.length > 6 ? Float.parseFloat(f[6]) : Float.NaN;
			} catch (NumberFormatException x) {
				schedule().remove(e);
				continue;
			}
			if (day < launchDay) continue;
			schedule().remove(e);
			ThreatColonyManager.addStrikeFund(cost);
			if (fuel > 0f) ThreatFuel.setStock(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, ThreatFuel.stock() + fuel);
			MarketAPI target = Global.getSector().getEconomy().getMarket(f[0]);
			MarketAPI staging = Global.getSector().getEconomy().getMarket(f[1]);
			StarSystemAPI source = Global.getSector().getStarSystem(f[2]);
			String name = target != null ? target.getName() : f[0];
			if (target == null || staging == null || source == null || target.getStarSystem() == null
					|| !IncursionManager.isStrikeableWorld(target) || IncursionManager.isActiveStrikeTarget(target)
					|| !ThreatIncData.colonyMarkets().containsKey(source.getId())) {
				ThreatIncConfig.log("Offensive: the prong at " + name + " is off - target or staging gone; " + (int) cost
						+ " FP back in the fund");
				continue;
			}
			if (IncursionManager.instance.launchStrike(staging, source, target, expected) == null) {
				ThreatIncConfig.log("Offensive: the prong at " + name + " cannot sail today; " + (int) cost
						+ " FP back in the fund");
				continue;
			}
			if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(source.getId());
			ThreatIncConfig.log("Offensive: the prong at " + name + " sails on its day");
		}
	}

	/** The hive system nearest the target that can stage a strike, or null. */
	protected static String nearest(MarketAPI target, Map<String, StarSystemAPI> sources) {
		if (target.getStarSystem() == null) return null;
		String best = null;
		float bestLY = Float.MAX_VALUE;
		for (Map.Entry<String, StarSystemAPI> e : sources.entrySet()) {
			float ly = Misc.getDistanceLY(e.getValue().getLocation(), target.getStarSystem().getLocation());
			if (ly < bestLY) {
				bestLY = ly;
				best = e.getKey();
			}
		}
		return best;
	}

	protected static String names(List<Prong> prongs, int max) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < prongs.size() && i < max; i++) {
			if (i > 0) sb.append(", ");
			sb.append(prongs.get(i).target.getName()).append(" ").append((int) prongs.get(i).cost);
		}
		if (prongs.size() > max) sb.append(" +").append(prongs.size() - max);
		return sb.toString();
	}
}
