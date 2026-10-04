package threatinc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.group.FleetGroupIntel;
import com.fs.starfarer.api.impl.combat.threat.DisposableThreatFleetManager.FabricatorEscortStrength;
import com.fs.starfarer.api.util.Misc;

/**
 * STANCE - where the swarm's surplus goes, sector-wide (2026-09-29). Posture
 * (ThreatPosture) decides what each system holds; the stance decides what
 * the rest is FOR, from what the swarm actually knows:
 *
 * <ul>
 * <li>PRESS - it is stronger than a rival in reach, it knows a world of that
 * rival it could take cheaply, and it is not pressed at home. The staging
 * colony facing that world builds the strike it needs and the strike goes
 * for the weak worlds first; expansion gets the smaller share.</li>
 * <li>EXPAND - quiet and not clearly stronger, or outmatched but given
 * breathing room, or losing the exchange: it diversifies, seeding systems
 * away from its strongest rival. Strikes as they come.</li>
 * <li>CONSOLIDATE - pressed across much of the hive, or losing hives under
 * attack: no new claims, quiet colonies feed pressed systems, and the only
 * strikes are spoiling blows at bases staging against it.</li>
 * </ul>
 *
 * <p>Read once a posture pass (ThreatPosture.poll hands it the pass). A
 * stance holds stanceDwellDays before it changes, except into CONSOLIDATE;
 * PRESS and CONSOLIDATE are left only below LEAVE of the figure that entered
 * them. Priorities, never limits: nothing here caps a fleet or a launch.
 * Primitive state only (threatinc_stance, _stanceTrend, _stanceHives).
 */
public class ThreatStance {

	public static final int EXPAND = 0, PRESS = 1, CONSOLIDATE = 2;
	protected static final String[] NAMES = { "EXPAND", "PRESS", "CONSOLIDATE" };

	/** "sector" -> float[]{stance, day entered, sector pressure at the last pass}. */
	public static final String KEY_STATE = "threatinc_stance";
	/** "sector" -> float[]{Threat FP lost, enemy FP sunk, day}; decays over TREND_DAYS. */
	public static final String KEY_TREND = "threatinc_stanceTrend";
	/** "sector" -> float[]{day, live colonies, day, live colonies, ...} over HIVE_WINDOW_DAYS. */
	public static final String KEY_HIVES = "threatinc_stanceHives";
	protected static final String SECTOR = "sector";

	/** Days over which the exchange ledger falls by a factor of e. */
	protected static final float TREND_DAYS = 60f;
	/** How far back hives gained or lost are counted. */
	protected static final float HIVE_WINDOW_DAYS = 90f;
	/** PRESS and CONSOLIDATE hold while their figure stays above this share of what entered them. */
	protected static final float LEAVE = 0.8f;
	/** Losses below this share of the hive's held fleets are too few to call the exchange lost. */
	protected static final float SIGNIFICANT_LOSS = 0.05f;
	/** Pressing, the target the stance picked outweighs any other in the strike's pick by this. */
	protected static final float TARGET_WEIGHT = 10f;

	/** One posture pass, as ThreatPosture.poll gathers it. */
	public static class Pass {
		/** System id -> {held FP, pressure, mode, attacked 0/1}. */
		protected final Map<String, float[]> systems = new HashMap<String, float[]>();
		/** Faction id -> FP it has in reach of the hive: staged capacity, attacks running, forward guards. */
		protected final Map<String, Float> force = new HashMap<String, Float>();
		/** System id -> the factions staging against it, attacking it or guarding forward bases facing it. */
		protected final Map<String, Set<String>> presence = new HashMap<String, Set<String>>();

		public void addForce(String factionId, String systemId, float fp) {
			if (factionId == null || fp <= 0f || Factions.THREAT.equals(factionId)) return;
			Float had = force.get(factionId);
			force.put(factionId, (had != null ? had : 0f) + fp);
			if (systemId == null) return;
			Set<String> here = presence.get(systemId);
			if (here == null) {
				here = new HashSet<String>();
				presence.put(systemId, here);
			}
			here.add(factionId);
		}

		public void addSystem(String systemId, float held, float pressure, int mode, boolean attacked) {
			systems.put(systemId, new float[] { held, pressure, mode, attacked ? 1f : 0f });
		}
	}

	/** A weak world one hive system could strike: what the pass found. */
	protected static class Target {
		MarketAPI market;
		MarketAPI staging;
		String sourceId;
		float odds, value, needFP, musterFP, ly;
		/** Value x (1 - odds), per day away under billed reach (ThreatReach.strikeDays). */
		float score;
	}

	/** Staging market id -> FP of strike it builds past its want while pressing. Not saved: rebuilt each pass. */
	protected static final Map<String, Float> EXTRA = new HashMap<String, Float>();
	/** Hive system id -> the market id its strikes go for while pressing. */
	protected static final Map<String, String> TARGET = new HashMap<String, String>();
	/** Locations of the strongest rival's known worlds, for spreadMult. */
	protected static final List<Vector2f> RIVAL_AT = new ArrayList<Vector2f>();
	protected static String summary = "";

	public static boolean enabled() {
		return ThreatIncConfig.stanceEnabled() && ThreatPosture.enabled();
	}

	/** Called on load: the per-pass picks are the abandoned game's. */
	public static void forget() {
		EXTRA.clear();
		TARGET.clear();
		RIVAL_AT.clear();
		summary = "";
	}

	protected static Map<String, float[]> map(String key) {
		return ThreatIncData.map(key);
	}

	/** The sector's stance; EXPAND when off or unread. */
	public static int stance() {
		if (!enabled()) return EXPAND;
		float[] s = map(KEY_STATE).get(SECTOR);
		return s != null && s.length >= 3 ? (int) s[0] : EXPAND;
	}

	public static String stanceName() {
		return NAMES[stance()];
	}

	// ------------------------------------------------------------------
	// what the rest of the hive reads
	// ------------------------------------------------------------------

	/**
	 * The share of the posture's appetite trySpread commits: all expanding,
	 * stanceSecondaryShare pressing, stanceConsolidateSpreadShare consolidating
	 * (1 since 2026-10-04; it was none - hw6: no founding from the month the
	 * humans attacked, and income fell with every hive lost). The defence has
	 * first call either way: a forge launches only what it holds above the need.
	 */
	public static float expansionShare() {
		int s = stance();
		if (s == CONSOLIDATE) return Math.max(0f, Math.min(1f, ThreatIncConfig.stanceConsolidateSpreadShare()));
		return threatinc.rules.StanceRules.expansionShare(
				s == CONSOLIDATE ? threatinc.rules.StanceRules.CONSOLIDATE
						: s == PRESS ? threatinc.rules.StanceRules.PRESS : threatinc.rules.StanceRules.EXPAND,
				ThreatIncConfig.stanceSecondaryShare());
	}

	/** The strike a pressing staging colony builds past its want; 0 otherwise. */
	public static float extraWantFP(MarketAPI market) {
		if (market == null || stance() != PRESS) return 0f;
		Float fp = EXTRA.get(market.getId());
		return fp != null ? fp : 0f;
	}

	/** Consolidating, a quiet colony feeds a THREATENED system too, not only one under attack. */
	public static boolean feedsPressed() {
		return stance() == CONSOLIDATE;
	}

	/**
	 * The strike pick's weight for this world from this hive system, given the
	 * odds the strike gate reads (defence over strike times siegeBreakOffRatio).
	 * Pressing: the weaker, the likelier, and the world the stance picked for
	 * the system above all. Consolidating: the weaker, the likelier, and a
	 * spoiling blow - a base staging against a hive, or a forward base - above
	 * all (2026-10-04, stanceConsolidateStrikes; it was a spoiling blow at weak
	 * odds or nothing, and the strike gate already refuses the outweighed).
	 */
	public static float strikeTargetMult(MarketAPI market, StarSystemAPI source, float odds) {
		// no defence figure (a world the swarm's fog has never seen, targetDefence): no strike
		if (odds >= Float.MAX_VALUE && ThreatSwarmIntel.enabled()) return 0f;
		int s = stance();
		if (s == EXPAND || market == null) return 1f;
		float weakness = Math.max(0f, 1f - odds);
		if (s == PRESS) {
			float mult = Math.max(0.05f, weakness);
			String picked = source != null ? TARGET.get(source.getId()) : null;
			if (market.getId().equals(picked)) mult *= TARGET_WEIGHT;
			return mult;
		}
		boolean staging = ThreatFrontlines.isOutpost(market) || stagingAgainstHive(market);
		if (ThreatIncConfig.stanceConsolidateStrikes()) {
			float mult = Math.max(0.05f, weakness);
			return staging ? mult * TARGET_WEIGHT : mult;
		}
		if (odds > ThreatIncConfig.stanceWeakOdds()) return 0f;
		return staging ? Math.max(0.05f, weakness) : 0f;
	}

	/**
	 * Whether the base stages against a hive: the human's own pick
	 * (ThreatConvoys.stagingHive), or in the swarm's fog what it last saw the
	 * base staging for (ThreatSwarmIntel.Place.stagesFor).
	 */
	protected static boolean stagingAgainstHive(MarketAPI market) {
		if (!ThreatSwarmIntel.enabled()) return ThreatConvoys.stagingHive(market) != null;
		ThreatSwarmIntel.Place seen = ThreatSwarmIntel.place(market.getId());
		return seen != null && seen.stagesFor != null;
	}

	/** Expanding, a spread candidate far from the strongest rival's worlds weighs more; 1 otherwise. */
	public static float spreadMult(StarSystemAPI system) {
		if (system == null || stance() != EXPAND || RIVAL_AT.isEmpty()) return 1f;
		float best = Float.MAX_VALUE;
		for (Vector2f at : RIVAL_AT) best = Math.min(best, Misc.getDistanceLY(system.getLocation(), at));
		return threatinc.rules.StanceRules.spreadMult(best);
	}

	// ------------------------------------------------------------------
	// the attrition ledger (ThreatPosture.noteBattle)
	// ------------------------------------------------------------------

	/** Books a battle's Threat FP lost and enemy FP sunk. */
	public static void noteTrend(float lost, float killed) {
		if (lost <= 0f && killed <= 0f) return;
		float day = ThreatPosture.today();
		float[] t = trend(day);
		map(KEY_TREND).put(SECTOR, new float[] { t[0] + lost, t[1] + killed, day });
	}

	/** {lost, killed} decayed to this day; nothing for a ledger stamped after it. */
	protected static float[] trend(float day) {
		float[] t = map(KEY_TREND).get(SECTOR);
		if (t == null || t.length < 3 || t[2] > day) return new float[] { 0f, 0f };
		float k = (float) Math.exp(-Math.max(0f, day - t[2]) / TREND_DAYS);
		return new float[] { t[0] * k, t[1] * k };
	}

	/** Records the live colony count and returns the change over HIVE_WINDOW_DAYS. */
	protected static int hiveTrend(float day, int now) {
		float[] old = map(KEY_HIVES).get(SECTOR);
		List<Float> keep = new ArrayList<Float>();
		if (old != null) {
			for (int i = 0; i + 1 < old.length; i += 2) {
				if (old[i] > day || day - old[i] > HIVE_WINDOW_DAYS) continue;
				keep.add(old[i]);
				keep.add(old[i + 1]);
			}
		}
		int first = keep.isEmpty() ? now : Math.round(keep.get(1));
		keep.add(day);
		keep.add((float) now);
		float[] out = new float[keep.size()];
		for (int i = 0; i < out.length; i++) out[i] = keep.get(i);
		map(KEY_HIVES).put(SECTOR, out);
		return now - first;
	}

	// ------------------------------------------------------------------
	// the pass
	// ------------------------------------------------------------------

	/**
	 * Reads the stance from a posture pass: the exchange and hive trend, how
	 * widely the hive is pressed, how strong it stands against each rival in
	 * reach, and the weak worlds each quiet system could strike; then picks,
	 * logs a change with its reasons, and - pressing - sets the strikes the
	 * staging colonies build and aim.
	 */
	public static void evaluate(Pass p, float day, float sumHeld) {
		EXTRA.clear();
		TARGET.clear();
		RIVAL_AT.clear();
		if (!enabled()) return;

		// trend
		float[] t = trend(day);
		int hivesNow = ThreatIncData.getAllLiveColonyMarkets().size();
		int hiveDelta = hiveTrend(day, hivesNow);
		boolean losing = t[0] >= SIGNIFICANT_LOSS * Math.max(1f, sumHeld) && t[1] < t[0];

		// pressure at home
		int n = p.systems.size(), pressed = 0, attacked = 0;
		float pressure = 0f;
		for (float[] s : p.systems.values()) {
			if (s[2] >= ThreatPosture.THREATENED) pressed++;
			if (s[3] > 0f) attacked++;
			pressure += s[1];
		}
		float pressedShare = n > 0 ? pressed / (float) n : 0f;
		float[] st = map(KEY_STATE).get(SECTOR);
		boolean hadState = st != null && st.length >= 3 && st[1] <= day;
		int was = hadState ? (int) st[0] : EXPAND;
		float since = hadState ? st[1] : day;
		boolean breathing = attacked == 0 && (!hadState || pressure <= st[2]);

		// what the swarm knows of the worlds around it, and each hive system's reach
		List<MarketAPI> known = new ArrayList<MarketAPI>();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (m.getStarSystem() == null || m.getFaction() == null) continue;
			if (!IncursionManager.isStrikeableWorld(m) || !ThreatSwarmScouts.swarmKnows(m)) continue;
			known.add(m);
		}
		// billed reach (ThreatReach) has no radius: a hive system faces the
		// faction it would strike first; off, every faction in its fuel reach
		boolean billed = ThreatReach.enabled();
		Map<String, Float> reach = new HashMap<String, Float>();
		Map<String, StarSystemAPI> hives = new HashMap<String, StarSystemAPI>();
		for (String systemId : p.systems.keySet()) {
			StarSystemAPI system = Global.getSector().getStarSystem(systemId);
			if (system == null) continue;
			float r = 0f;
			if (!billed) {
				for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) {
					r = Math.max(r, ThreatColonyManager.fuelRangeLY(c));
				}
			}
			reach.put(systemId, r);
			hives.put(systemId, system);
		}

		// strength against each rival: what the hive holds in the systems it
		// faces them from, against what they have in reach of it
		Map<String, Set<String>> facing = new HashMap<String, Set<String>>();
		for (Map.Entry<String, Set<String>> e : p.presence.entrySet()) {
			if (!hives.containsKey(e.getKey())) continue;
			for (String f : e.getValue()) facing(facing, f).add(e.getKey());
		}
		if (billed) {
			for (Map.Entry<String, StarSystemAPI> h : hives.entrySet()) {
				String f = ThreatReach.facedFaction(h.getValue());
				if (f != null) facing(facing, f).add(h.getKey());
			}
		} else {
			for (MarketAPI m : known) {
				for (Map.Entry<String, StarSystemAPI> h : hives.entrySet()) {
					if (Misc.getDistanceLY(h.getValue().getLocation(), m.getStarSystem().getLocation())
							<= reach.get(h.getKey())) {
						facing(facing, m.getFactionId()).add(h.getKey());
					}
				}
			}
		}
		final Map<String, Float> ratios = new HashMap<String, Float>();
		String strongest = null;
		float strongestForce = 0f;
		for (Map.Entry<String, Set<String>> e : facing.entrySet()) {
			float ours = 0f;
			for (String systemId : e.getValue()) ours += p.systems.get(systemId)[0];
			Float theirs = p.force.get(e.getKey());
			float r = theirs != null && theirs > 0f ? ours / theirs : (ours > 0f ? Float.MAX_VALUE : 0f);
			ratios.put(e.getKey(), r);
			if (theirs != null && theirs > strongestForce) {
				strongestForce = theirs;
				strongest = e.getKey();
			}
		}

		// weak worlds: from each hive system not pressed at home, the best
		// target of a rival it outweighs that its surplus could take cheaply
		float pressEnter = Math.max(0f, ThreatIncConfig.stancePressRatio());
		float pressNeed = was == PRESS ? pressEnter * LEAVE : pressEnter;
		Map<String, Target> picks = new HashMap<String, Target>();
		Target best = null, bestAny = null;
		if (IncursionManager.getPhase() >= 2) {
			Map<String, float[]> defMemo = new HashMap<String, float[]>();
			for (String systemId : p.systems.keySet()) {
				if (p.systems.get(systemId)[2] >= ThreatPosture.THREATENED) continue;
				Target[] found = weakTargets(systemId, known, defMemo, ratios, pressNeed);
				if (found[0] != null) {
					picks.put(systemId, found[0]);
					if (best == null || found[0].score > best.score) best = found[0];
				}
				if (found[1] != null && (bestAny == null || found[1].score > bestAny.score)) {
					bestAny = found[1];
				}
			}
		}

		// the pick
		float consolidateEnter = Math.max(0f, ThreatIncConfig.stanceConsolidateShare());
		float consolidateNeed = was == CONSOLIDATE ? consolidateEnter * LEAVE : consolidateEnter;
		boolean wantConsolidate = !breathing
				&& (threatinc.rules.StanceRules.pressedEnough(pressed, n, consolidateNeed,
						ThreatIncConfig.stanceConsolidateMinPressed()) || (hiveDelta < 0 && attacked > 0));
		boolean wantPress = !losing && best != null && pressedShare < consolidateEnter / 2f;
		int next = wantConsolidate ? CONSOLIDATE : wantPress ? PRESS : EXPAND;
		// a stance holds its dwell, but defence never waits
		if (next != was && next != CONSOLIDATE && hadState
				&& day - since < Math.max(0f, ThreatIncConfig.stanceDwellDays())) {
			next = was;
		}

		StringBuilder why = new StringBuilder();
		why.append("pressed ").append(pressed).append("/").append(n).append(", attacked ").append(attacked)
				.append(", pressure ").append((int) pressure)
				.append(hadState ? " (was " + (int) st[2] + ")" : "")
				.append("; exchange lost ").append((int) t[0]).append(" killed ").append((int) t[1])
				.append("; hives ").append(hivesNow).append(" (").append(hiveDelta >= 0 ? "+" : "").append(hiveDelta)
				.append(" in ").append((int) HIVE_WINDOW_DAYS).append("d); rivals");
		List<String> rivalIds = new ArrayList<String>(ratios.keySet());
		java.util.Collections.sort(rivalIds, new java.util.Comparator<String>() {
			public int compare(String a, String b) {
				return Float.compare(ratios.get(b), ratios.get(a));
			}
		});
		for (String f : rivalIds) {
			float r = ratios.get(f);
			Float theirs = p.force.get(f);
			why.append(" ").append(f).append(" ").append(r >= Float.MAX_VALUE ? "inf" : String.format("%.2f", r))
					.append(" (").append((int) (theirs != null ? theirs : 0f)).append(")");
		}
		Target shown = best != null ? best : bestAny;
		if (shown != null) {
			why.append("; best weak target ").append(shown.market.getName()).append(" (")
					.append(shown.market.getFactionId()).append(", ").append((int) shown.ly).append(" ly")
					.append(", odds ").append(String.format("%.2f", shown.odds))
					.append(", needs ").append((int) shown.needFP).append(" of ").append((int) shown.musterFP)
					.append(" FP musterable at ").append(shown.staging.getName()).append(")");
		} else {
			why.append(billed ? "; no weak target" : "; no weak target in reach");
		}
		summary = why.toString();

		if (next != was || !hadState) {
			if (next != was) since = day;
			ThreatIncConfig.log("Stance: " + NAMES[was] + "->" + NAMES[next] + " - " + summary);
		}
		map(KEY_STATE).put(SECTOR, new float[] { next, since, pressure });

		// pressing: each staging colony builds the strike its target needs
		if (next == PRESS) {
			for (Map.Entry<String, Target> e : picks.entrySet()) {
				Target tg = e.getValue();
				TARGET.put(e.getKey(), tg.market.getId());
				float extra = Math.max(0f, tg.needFP - ThreatPosture.stockFP(tg.staging));
				if (extra > 0f) {
					Float had = EXTRA.get(tg.staging.getId());
					EXTRA.put(tg.staging.getId(), Math.max(extra, had != null ? had : 0f));
				}
			}
		}
		// expanding: away from the strongest rival's worlds
		if (strongest != null) {
			for (MarketAPI m : known) {
				if (strongest.equals(m.getFactionId())) RIVAL_AT.add(m.getStarSystem().getLocation());
			}
		}
	}

	protected static Set<String> facing(Map<String, Set<String>> facing, String factionId) {
		Set<String> out = facing.get(factionId);
		if (out == null) {
			out = new HashSet<String>();
			facing.put(factionId, out);
		}
		return out;
	}

	/**
	 * The best weak world a hive system could strike: {of a rival it outweighs
	 * by {@code pressNeed}, of any rival}. Weak is odds - defence over what the
	 * system could muster (its colonies' garrisons above their minimums, and
	 * what their banks would build) times siegeBreakOffRatio - no worse than
	 * stanceWeakOdds; the best is the most value for the least odds
	 * (IncursionManager.strikeValue). A target carries the swarms of its
	 * staging colony's heaviest row it takes to bring the odds to that.
	 */
	protected static Target[] weakTargets(String systemId, List<MarketAPI> known, Map<String, float[]> defMemo,
			Map<String, Float> ratios, float pressNeed) {
		Target[] out = new Target[2];
		StarSystemAPI system = Global.getSector().getStarSystem(systemId);
		MarketAPI staging = ThreatColonyManager.pickStrikeStaging(systemId, false);
		if (system == null || staging == null) return out;
		int[] row = heaviestRow(staging);
		if (row == null) return out;
		float rowFP = ThreatColonyManager.swarmCostEstimate(row);
		if (rowFP <= 0f) return out;
		float muster = 0f;
		for (MarketAPI c : ThreatIncData.getLiveColonyMarkets(systemId)) {
			float held = ThreatColonyManager.ownedFleetFP(c, ThreatIncData.garrisonsFor(c.getId()));
			muster += Math.max(0f, held - ThreatPosture.minimumFP(c)) + Math.max(0f, ThreatColonyManager.bankedFP(c));
		}
		// billed reach (ThreatReach): no more than the colonies' spare supplies and
		// the stock keep away for a strike at the system's first target, any
		// distance whose passage the stock pays, worth per day away
		boolean billed = ThreatReach.enabled();
		if (billed) {
			muster = Math.min(muster,
					ThreatReach.sustainableFP(ThreatReach.strikeDays(Math.max(0f, ThreatReach.facedLY(system)))));
		}
		int swarms = (int) Math.floor(muster / rowFP);
		if (swarms < 1) return out;
		int perSwarm = IncursionManager.strikeFleetSize(expeditionSize(row));
		float strength = strength(swarms * perSwarm);
		float ratio = IncursionManager.breakOffRatio();
		float weakOdds = Math.max(0f, ThreatIncConfig.stanceWeakOdds());
		float range = billed ? Float.MAX_VALUE : ThreatColonyManager.fuelRangeLY(staging);
		for (MarketAPI m : known) {
			float ly = Misc.getDistanceLY(system.getLocation(), m.getStarSystem().getLocation());
			if (ly > range) continue;
			if (!IncursionManager.strikeAllowed(m)) continue;
			float def = IncursionManager.targetDefence(m, defMemo);
			// a world the swarm's fog has never seen has no figure: not weak
			if (def >= Float.MAX_VALUE) continue;
			float odds = strength > 0f ? def / (strength * ratio) : Float.MAX_VALUE;
			if (odds > weakOdds) continue;
			float value = IncursionManager.strikeValue(m);
			Target tg = new Target();
			tg.market = m;
			tg.staging = staging;
			tg.sourceId = systemId;
			tg.odds = odds;
			tg.value = value;
			tg.musterFP = muster;
			// the fewest of these swarms that bring the odds to weak: no more
			// than the muster that found it weak
			int k = 1;
			while (k < swarms && def / (strength(k * perSwarm) * ratio) > weakOdds) k++;
			tg.needFP = k * rowFP;
			tg.ly = ly;
			if (billed && !ThreatFuel.canPay(ThreatFuel.passage(tg.needFP, ly, true))) continue;
			tg.score = value * (1f - odds) / (billed ? ThreatReach.strikeDays(ly) : 1f);
			if (out[1] == null || tg.score > out[1].score) out[1] = tg;
			Float r = ratios.get(m.getFactionId());
			if (r == null || r < pressNeed) continue;
			if (out[0] == null || tg.score > out[0].score) out[0] = tg;
		}
		return out;
	}

	protected static float strength(int points) {
		return FleetGroupIntel.getApproximateStrengthForTotalDifficultyPoints(Factions.THREAT, points);
	}

	/** The staging colony's costliest size-table row: what its musters send first. */
	protected static int[] heaviestRow(MarketAPI market) {
		int[] best = null;
		float bestFP = -1f;
		for (int[] row : ThreatColonyManager.desiredGarrison(market.getSize())) {
			float fp = ThreatColonyManager.swarmCostEstimate(row);
			if (fp > bestFP) {
				bestFP = fp;
				best = row;
			}
		}
		return best;
	}

	/** The expedition size a swarm of this row re-embodies as (ThreatColonyManager.expeditionSizeFor's tiers). */
	protected static int expeditionSize(int[] row) {
		if (row[0] > 0) return 9;
		if (row[1] <= FabricatorEscortStrength.LOW.ordinal()) return 4;
		if (row[1] == FabricatorEscortStrength.MEDIUM.ordinal()) return 6;
		if (row[1] == FabricatorEscortStrength.HIGH.ordinal()) return 8;
		return 9;
	}

	/** The monthly line's figure: the stance the last pass read. */
	public static String monthLine() {
		if (!enabled()) return "";
		return "; stance " + stanceName();
	}
}
