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
		float cost, ly, score, def;
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
		List<Prong> prongs = new ArrayList<Prong>();
		for (Prong p : bySystem.values()) {
			IncursionManager.StagedPlan plan = im.stagedPlan(p.staging, p.source, p.target, memo, null, null, Float.MAX_VALUE);
			if (plan == null || plan.bankFP <= 0f) continue; // the fuel or the supplies away do not pay it
			p.cost = plan.bankFP;
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
		List<Prong> campaign = new ArrayList<Prong>();
		float cost = 0f;
		for (Prong p : prongs) {
			if (cost + p.cost > budget) continue;
			campaign.add(p);
			cost += p.cost;
		}
		String where = losing ? "losing, within " + (int) nearLY + " ly" : "all known";
		if (campaign.isEmpty()) {
			ThreatIncConfig.logQuiet("offensive", "Offensive: nothing the fund pays by the deadline - " + prongs.size()
					+ " target(s) " + where + ", fund " + (int) fund + " FP (+" + (int) perMonth + "/mo), "
					+ (int) monthsLeft + " month(s) left");
			if (day >= deadline) setStartDay(day);
			return;
		}
		if (fund < cost && day < deadline) {
			ThreatIncConfig.log("Offensive: saving for " + campaign.size() + " of " + prongs.size() + " target(s) " + where
					+ ", " + (int) cost + " FP; fund " + (int) fund + " FP (+" + (int) perMonth + "/mo), launch in "
					+ (int) Math.ceil(Math.max(0f, cost - fund) / Math.max(1f, perMonth)) + " month(s), deadline in "
					+ (int) monthsLeft + "; first " + names(campaign, 4));
			return;
		}

		// launch: every prong of the campaign the fund pays, the most valuable first
		int sent = 0;
		float sentFP = 0f;
		StringBuilder at = new StringBuilder();
		for (Prong p : campaign) {
			if (p.cost > ThreatColonyManager.strikeFund() + 0.5f) continue;
			if (IncursionManager.isActiveStrikeTarget(p.target)) continue;
			if (im.launchStrike(p.staging, p.source, p.target) == null) continue;
			if (!ThreatIncConfig.hiveFogOfWar()) ThreatIncData.markDiscovered(p.source.getId());
			sent++;
			sentFP += p.cost;
			if (at.length() > 0) at.append(", ");
			at.append(p.target.getName()).append(" (").append(p.target.getFactionId()).append(", ").append((int) p.ly)
					.append(" ly, ").append((int) p.cost).append(" FP)");
		}
		setStartDay(day);
		ThreatIncConfig.log("Offensive launched: " + sent + " strike(s) of " + campaign.size() + " planned, " + (int) sentFP
				+ " FP (" + where + (day >= deadline && fund < cost ? ", at the deadline" : "") + "); fund left "
				+ (int) ThreatColonyManager.strikeFund() + " FP; at " + at);
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
