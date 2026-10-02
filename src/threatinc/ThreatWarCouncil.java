package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.util.Misc;

/**
 * The war council (docs/war-council.md): the strategic layer of a mobilised
 * NPC faction. Once a month it assesses what the faction knows - the hive
 * worlds its reports show, their sizes, Bastion tiers, industries and spread,
 * against its own colonies and its coalition's, never a fleet point
 * (principle 5) - and draws a {@link Picture}. Every ~councilReviewDays it
 * holds or changes its strategy (Hold, Starve, Roll back, Decapitate), drawn
 * at random in proportion to scores weighted by the faction's personality and
 * what it learned, the held one favoured by councilSwitchMargin. The strategy
 * picks the plays (ThreatPlays) and sets the faction stance (decision 9).
 * Replaces the attack planner while threatinc_warCouncil is on.
 *
 * <p>State: one saved {@link Council} per faction (KEY); the picture is not
 * saved and is drawn again after a load. Day stamps are ThreatPosture.today()
 * (negative calendar days; "never" is -Float.MAX_VALUE).
 */
public class ThreatWarCouncil {

	public static final String KEY = "threatinc_warCouncil";
	public static final String HOLD = "HOLD", STARVE = "STARVE", ROLLBACK = "ROLLBACK", DECAPITATE = "DECAPITATE";
	public static final String[] STRATEGIES = { HOLD, STARVE, ROLLBACK, DECAPITATE };
	public static final int OUTMATCHED = 0, EVEN = 1, AHEAD = 2;
	protected static final String[] BANDS = { "outmatched", "even", "ahead" };
	protected static final float NEVER = -Float.MAX_VALUE;
	/** The window "struck lately" counts strikes over. */
	protected static final float STRIKE_WINDOW_DAYS = 90f;
	/** Monthly pictures kept for trends. */
	protected static final int HISTORY = 24;

	/** One faction's council. Saved: never rename the class or a field. */
	public static class Council {
		public String factionId;
		public String strategy;
		/** The system the strategy works (a cluster's), or null. */
		public String focusId;
		public float since = NEVER;
		public float assessed = NEVER;
		public float reviewDue = NEVER;
		/** Why the next day reviews early, or null. */
		public String earlyWhy;
		public int band = -1;
		public float ratio;
		/** "strategy:S" and "TYPE:class" -> learned weight (section 6). */
		public Map<String, Float> learned = new LinkedHashMap<String, Float>();
		/** Monthly {day, systems, hives, swarm weight, our weight, strikes suffered (lifetime), colonies}. */
		public List<float[]> history = new ArrayList<float[]>();
		public int nextPlay;
		public int colonies = -1;
		public String partners;
		/** Bombers of opportunity: fuel spent in the month that began on oppMonth. */
		public float oppSpent;
		public float oppMonth = NEVER;
		/** System id -> the day a recon on it last started: one per intel half-life. */
		public Map<String, Float> reconDay = new LinkedHashMap<String, Float>();
	}

	/** One known hive system, as the faction's reports have it. Not saved. */
	public static class Cluster {
		public String systemId;
		public String name;
		/** Known live hive worlds, a world with a Nexus first. */
		public List<String> hiveIds = new ArrayList<String>();
		public int hives;
		/** Sizes plus twice the Bastion tiers. */
		public float weight;
		public boolean core, frontier, production, nexus;
		public String scale;
		/** Light-years from the faction's nearest colony. */
		public float ly;
		public float age;
		public boolean stale, threatens;
		/** The faction's bases in expedition range, nearest first. */
		public List<MarketAPI> bases = new ArrayList<MarketAPI>();
		public String neighbourId;
		public float neighbourLY = Float.MAX_VALUE;

		public boolean inReach() {
			return !bases.isEmpty();
		}

		/** The target class learning keys on (section 6). */
		public String targetClass() {
			return production ? "production" : core ? "core" : frontier ? "frontier" : "cluster";
		}
	}

	/** A faction's monthly picture. Not saved. */
	public static class Picture {
		public String factionId;
		public float day;
		public List<Cluster> clusters = new ArrayList<Cluster>();
		public float swarmWeight, ourWeight, allyWeight, ratio;
		public int band;
		public int colonies, hives, cores, frontier, stale, inReach, threatening;
		public float fuel, supplies, marines, fuelPer30, suppliesPer30;
		public int strikes;
		public int fronts;
		public boolean pressed, relief;
		public List<String> partners = new ArrayList<String>();

		public Cluster cluster(String systemId) {
			if (systemId == null) return null;
			for (Cluster c : clusters) {
				if (systemId.equals(c.systemId)) return c;
			}
			return null;
		}
	}

	/** Faction id -> its picture; not saved, drawn again after a load. */
	protected static final Map<String, Picture> PICTURES = new LinkedHashMap<String, Picture>();

	@SuppressWarnings("unchecked")
	public static Map<String, Council> councils() {
		return ThreatIncData.map(KEY);
	}

	public static Council councilOf(String fid) {
		Council c = councils().get(fid);
		if (c == null) {
			c = new Council();
			c.factionId = fid;
			councils().put(fid, c);
		}
		if (c.learned == null) c.learned = new LinkedHashMap<String, Float>();
		if (c.history == null) c.history = new ArrayList<float[]>();
		if (c.reconDay == null) c.reconDay = new LinkedHashMap<String, Float>();
		return c;
	}

	/** Whether a council governs the faction: on, and a mobilised NPC faction. */
	public static boolean governs(String fid) {
		if (!ThreatIncConfig.warCouncil() || fid == null) return false;
		if (Factions.PLAYER.equals(fid) || Factions.THREAT.equals(fid) || ThreatWarState.excluded(fid)) return false;
		return ThreatWarState.isAtWar(fid);
	}

	/** The faction's strategy, or null. */
	public static String strategy(String fid) {
		if (!governs(fid)) return null;
		Council c = councils().get(fid);
		return c != null ? c.strategy : null;
	}

	/** The board's name for a strategy. */
	public static String strategyName(String s) {
		if (HOLD.equals(s)) return "Hold";
		if (STARVE.equals(s)) return "Starve";
		if (ROLLBACK.equals(s)) return "Roll back";
		if (DECAPITATE.equals(s)) return "Decapitate";
		return String.valueOf(s);
	}

	/** The board's strategy line, or null when no council governs the faction. */
	public static String strategyLine(String fid) {
		String s = strategy(fid);
		if (s == null) return null;
		Council c = councils().get(fid);
		StringBuilder b = new StringBuilder("Strategy: ").append(strategyName(s));
		if (c.focusId != null) b.append(" (").append(ThreatPlays.boardName(c.focusId)).append(")");
		if (c.since != NEVER) {
			int days = Math.max(0, (int) (ThreatPosture.today() - c.since));
			b.append(" for ").append(days).append(days == 1 ? " day" : " days");
		}
		return b.toString();
	}

	public static Picture picture(String fid) {
		return PICTURES.get(fid);
	}

	/** Called on load: the pictures and the day's check are the abandoned game's. */
	public static void forget() {
		PICTURES.clear();
		checkedDay = Long.MIN_VALUE;
		personalities = null;
	}

	/** A new campaign: every council and play goes. */
	public static void reset() {
		Global.getSector().getPersistentData().remove(KEY);
		Global.getSector().getPersistentData().remove(ThreatPlays.KEY);
		Global.getSector().getPersistentData().remove(ThreatConvoys.KEY_PLAY_STAGING);
		forget();
	}

	// ------------------------------------------------------------------
	// the poll
	// ------------------------------------------------------------------

	private static long checkedDay = Long.MIN_VALUE;

	/** From the war poll, once a calendar day: each council assesses when due, reviews when due, plans and sets its stance; then the plays advance. */
	public static void poll(Random random) {
		if (!ThreatIncConfig.warCouncil()) return;
		long day = ThreatReach.today();
		if (day == checkedDay) return;
		checkedDay = day;
		float today = ThreatPosture.today();
		for (String fid : new ArrayList<String>(ThreatWarState.warFactionIds())) {
			if (!governs(fid)) continue;
			FactionAPI faction = Global.getSector().getFaction(fid);
			if (faction == null) continue;
			try {
				daily(councilOf(fid), faction, today, random);
			} catch (RuntimeException e) {
				ThreatIncConfig.log("Council " + fid + ": error " + e);
				Global.getLogger(ThreatWarCouncil.class).error("War council " + fid, e);
			}
		}
		try {
			ThreatPlays.advance(today, random);
		} catch (RuntimeException e) {
			ThreatIncConfig.log("Plays: error " + e);
			Global.getLogger(ThreatWarCouncil.class).error("War council plays", e);
		}
	}

	protected static void daily(Council c, FactionAPI faction, float today, Random random) {
		String fid = faction.getId();
		boolean due = c.assessed == NEVER || today < c.assessed
				|| today - c.assessed >= Math.max(1f, ThreatIncConfig.councilAssessDays());
		Picture p = PICTURES.get(fid);
		if (due || p == null) {
			p = assess(faction, c, today);
			PICTURES.put(fid, p);
		}
		if (due) {
			c.assessed = today;
			// early review: a colony lost, or the coalition formed or broke (section 3)
			if (c.colonies >= 0 && p.colonies < c.colonies && c.earlyWhy == null) {
				c.earlyWhy = (c.colonies - p.colonies) + " colony lost";
			}
			String partners = Misc.getAndJoined(p.partners);
			if (c.partners != null && !c.partners.equals(partners) && c.earlyWhy == null) {
				c.earlyWhy = "the coalition changed";
			}
			c.colonies = p.colonies;
			c.partners = partners;
			record(c, p, today);
		}
		if (c.strategy == null || today >= c.reviewDue || c.earlyWhy != null) review(c, faction, p, today, random);
		ThreatPlays.plan(c, faction, p, today, random);
		setStance(c, faction);
		if (due) logPicture(c, p, today);
	}

	// ------------------------------------------------------------------
	// assessment (section 2): reports, planet facts, our means - never swarm FP
	// ------------------------------------------------------------------

	protected static Picture assess(FactionAPI faction, Council c, float today) {
		String fid = faction.getId();
		String observer = ThreatIntel.observerOf(faction);
		Picture p = new Picture();
		p.factionId = fid;
		p.day = today;
		float half = Math.max(1f, ThreatIncConfig.intelHalfLifeDays());
		float threatLY = ThreatIncConfig.councilThreatLY();

		// our colonies, and their means
		List<MarketAPI> ours = new ArrayList<MarketAPI>();
		for (MarketAPI m : ThreatReserves.marketsOf(fid)) {
			if (m == null || !m.isInEconomy() || m.getStarSystem() == null) continue;
			ours.add(m);
			p.ourWeight += m.getSize();
			p.marines += ThreatReserves.armedMarines(m);
			p.fuelPer30 += ThreatReserves.accrualPer30(m, Commodities.FUEL);
			p.suppliesPer30 += ThreatReserves.accrualPer30(m, Commodities.SUPPLIES);
		}
		p.colonies = ours.size();
		p.fuel = ThreatReserves.factionStock(fid, Commodities.FUEL);
		p.supplies = ThreatReserves.factionStock(fid, Commodities.SUPPLIES);
		for (String partner : ThreatCoalition.partners(fid)) {
			if (partner == null || partner.equals(fid)) continue;
			p.partners.add(partner);
			for (MarketAPI m : ThreatReserves.marketsOf(partner)) {
				if (m != null && m.isInEconomy()) p.allyWeight += m.getSize();
			}
		}

		// the known swarm: the worlds its reports show that are still the swarm's
		for (StarSystemAPI s : ThreatAttackPlanner.knownSystems()) {
			if (!ThreatIntel.known(observer, s.getId())) continue;
			ThreatIntel.Report r = ThreatIntel.report(observer, s.getId());
			if (r == null) continue;
			Cluster k = new Cluster();
			k.systemId = s.getId();
			k.name = s.getName();
			List<String> withNexus = new ArrayList<String>(), rest = new ArrayList<String>();
			int tier2 = 0;
			for (String id : r.worlds.keySet()) {
				MarketAPI m = Global.getSector().getEconomy().getMarket(id);
				if (!isHive(m)) continue;
				int tier = SwarmBastion.tier(m);
				k.weight += m.getSize() + 2f * tier;
				if (tier >= 2) tier2++;
				if (m.hasIndustry(Industries.ORBITALWORKS) || m.hasIndustry(Industries.HEAVYINDUSTRY)
						|| m.hasIndustry(Industries.FUELPROD)) {
					k.production = true;
				}
				if (ThreatColonyManager.hasOperationalNexus(m)) {
					k.nexus = true;
					withNexus.add(id);
				} else {
					rest.add(id);
				}
			}
			k.hiveIds.addAll(withNexus);
			k.hiveIds.addAll(rest);
			k.hives = k.hiveIds.size();
			if (k.hives == 0) continue;
			k.core = k.hives >= 3 || tier2 > 0;
			if (k.hives == 1) {
				MarketAPI only = Global.getSector().getEconomy().getMarket(k.hiveIds.get(0));
				k.frontier = only != null && SwarmBastion.tier(only) == 0;
			}
			k.scale = k.weight < 8f ? "small" : k.weight < 20f ? "medium" : "large";
			k.age = ThreatIntel.age(observer, s.getId());
			k.stale = k.age >= half;
			k.ly = Float.MAX_VALUE;
			for (MarketAPI m : ours) {
				k.ly = Math.min(k.ly, Misc.getDistanceLY(m.getStarSystem().getLocation(), s.getLocation()));
			}
			k.threatens = k.ly <= threatLY;
			for (MarketAPI b : IncursionManager.siegeBasesFor(s)) {
				if (fid.equals(b.getFactionId())) k.bases.add(b);
			}
			p.clusters.add(k);
			p.swarmWeight += k.weight;
			p.hives += k.hives;
			if (k.core) p.cores++;
			if (k.frontier) p.frontier++;
			if (k.stale) p.stale++;
			if (k.inReach()) p.inReach++;
			if (k.threatens) p.threatening++;
		}
		// each cluster's nearest neighbour (a feint's decoy, section 4.2)
		for (Cluster a : p.clusters) {
			StarSystemAPI sa = Global.getSector().getStarSystem(a.systemId);
			for (Cluster b : p.clusters) {
				if (a == b) continue;
				StarSystemAPI sb = Global.getSector().getStarSystem(b.systemId);
				if (sa == null || sb == null) continue;
				float d = Misc.getDistanceLY(sa.getLocation(), sb.getLocation());
				if (d < a.neighbourLY) {
					a.neighbourLY = d;
					a.neighbourId = b.systemId;
				}
			}
		}

		// pressure on us: strikes in the window (the lifetime count, diffed), Threat fronts on our worlds
		ThreatWarState.FactionWar war = ThreatWarState.get(fid);
		int strikes = war != null ? war.strikesSuffered : 0;
		float before = -1f;
		for (float[] h : c.history) {
			if (h.length >= 6 && h[0] <= today - STRIKE_WINDOW_DAYS) before = h[5];
		}
		if (before < 0f) {
			// no row a window old yet: the oldest row; with none, only a strike inside the window counts
			if (!c.history.isEmpty() && c.history.get(0).length >= 6) {
				before = c.history.get(0)[5];
			} else {
				boolean recent = war != null && strikes > 0 && war.lastStruckTimestamp != 0L
						&& Global.getSector().getClock().getElapsedDaysSince(war.lastStruckTimestamp) < STRIKE_WINDOW_DAYS;
				before = recent ? strikes - 1 : strikes;
			}
		}
		p.strikes = Math.max(0, strikes - (int) before);
		for (ThreatGroundFronts.GroundFront f : ThreatGroundFronts.fronts().values()) {
			if (f == null || !Factions.THREAT.equals(ThreatGroundFronts.ownerOf(f))) continue;
			MarketAPI m = Global.getSector().getEconomy().getMarket(f.marketId);
			if (m != null && fid.equals(m.getFactionId())) p.fronts++;
		}
		p.pressed = p.strikes > 0 || p.fronts > 0;
		p.relief = ThreatFleetOrders.reliefOwed(faction);

		// the band: our weight (allies at half) over the known swarm's, with hysteresis
		p.ratio = threatinc.rules.CouncilRules.ratio(p.ourWeight, p.allyWeight, p.swarmWeight);
		p.band = band(c.band, p.ratio);
		c.band = p.band;
		c.ratio = p.ratio;
		return p;
	}

	protected static boolean isHive(MarketAPI m) {
		return m != null && m.isInEconomy() && m.getPrimaryEntity() != null && Factions.THREAT.equals(m.getFactionId());
	}

	protected static float edge(int band) {
		return band == AHEAD ? ThreatIncConfig.councilAheadRatio() : ThreatIncConfig.councilEvenRatio();
	}

	/** The band for the ratio: it moves only once the ratio is past an edge by councilBandHysteresis. */
	protected static int band(int was, float ratio) {
		return threatinc.rules.CouncilRules.band(was, ratio, edge(EVEN), edge(AHEAD),
				ThreatIncConfig.councilBandHysteresis());
	}

	protected static void record(Council c, Picture p, float today) {
		ThreatWarState.FactionWar war = ThreatWarState.get(c.factionId);
		c.history.add(new float[] { today, p.clusters.size(), p.hives, p.swarmWeight, p.ourWeight,
				war != null ? war.strikesSuffered : 0, p.colonies });
		while (c.history.size() > HISTORY) c.history.remove(0);
	}

	// ------------------------------------------------------------------
	// strategy (section 3)
	// ------------------------------------------------------------------

	/** Each strategy's score from the picture, x personality x learned weight. */
	protected static float[] scores(Council c, Picture p) {
		boolean any = false, frontier = false, core = false, rich = false;
		for (Cluster k : p.clusters) {
			if (!k.inReach()) continue;
			any = true;
			if (k.frontier || "small".equals(k.scale)) frontier = true;
			if (k.core) core = true;
			if (k.core || k.production) rich = true;
		}
		// pressed and outmatched both argue for holding: counted once, not summed (pd2a: pressed
		// in 85% of months, so Hold scored 4.5 against an outmatched Starve's 1.3)
		// starving is the weak side's play too (user, 2026-10-01, after h53b): outmatched, it
		// scores with Hold, so an outmatched faction cuts production rather than sitting a year;
		// and the weak side's play is not halved by the pressure that comes with being weak
		float[] s = threatinc.rules.CouncilRules.scores(any, frontier, core, rich, p.pressed, p.relief, p.band,
				!p.partners.isEmpty());
		for (int i = 0; i < s.length; i++) {
			s[i] *= personality(c.factionId, STRATEGIES[i].toLowerCase()) * learned(c, "strategy:" + STRATEGIES[i]);
		}
		return s;
	}

	protected static void review(Council c, FactionAPI faction, Picture p, float today, Random random) {
		float[] s = scores(c, p);
		int current = indexOf(c.strategy);
		float margin = Math.max(0f, ThreatIncConfig.councilSwitchMargin()) * personality(c.factionId, "switchMult");
		float[] w = s.clone();
		if (current >= 0) w[current] *= 1f + margin;
		int pick = draw(w, random);
		if (pick < 0) pick = 0;
		String next = STRATEGIES[pick];
		String why = c.earlyWhy != null ? c.earlyWhy : c.strategy == null ? "first council" : "review";
		StringBuilder sc = new StringBuilder();
		for (int i = 0; i < s.length; i++) sc.append(i > 0 ? ", " : "").append(STRATEGIES[i].toLowerCase()).append(" ").append(two(s[i]));
		String focusWas = c.focusId;
		if (!next.equals(c.strategy)) {
			ThreatIncConfig.log("Council " + c.factionId + ": strategy " + c.strategy + " -> " + next + " (" + why + "; "
					+ sc + "; " + BANDS[Math.max(0, p.band)] + " " + two(p.ratio) + ")");
			c.strategy = next;
			c.since = today;
			c.focusId = null;
		}
		Cluster focus = p.cluster(c.focusId);
		if (focus == null || !fits(next, focus)) c.focusId = focus(c, p, next, random);
		if (c.focusId != null && !c.focusId.equals(focusWas)) {
			Cluster k = p.cluster(c.focusId);
			ThreatIncConfig.log("Council " + c.factionId + ": " + next + " focus " + (k != null ? k.name : c.focusId)
					+ " (" + why + ")");
		}
		c.reviewDue = today + Math.max(1f, ThreatIncConfig.councilReviewDays()) * jitter(random);
		c.earlyWhy = null;
	}

	protected static int indexOf(String strategy) {
		for (int i = 0; strategy != null && i < STRATEGIES.length; i++) {
			if (STRATEGIES[i].equals(strategy)) return i;
		}
		return -1;
	}

	/** Whether the cluster can be the strategy's focus. */
	protected static boolean fits(String strategy, Cluster k) {
		if (HOLD.equals(strategy)) return k.threatens;
		if (!k.inReach()) return false;
		if (ROLLBACK.equals(strategy)) return k.frontier || "small".equals(k.scale);
		if (DECAPITATE.equals(strategy)) return k.core;
		return true;
	}

	/** The strategy's focus, drawn among the clusters that fit it (section 3). */
	protected static String focus(Council c, Picture p, String strategy, Random random) {
		List<Cluster> fit = new ArrayList<Cluster>();
		List<Float> w = new ArrayList<Float>();
		for (Cluster k : p.clusters) {
			if (!fits(strategy, k)) continue;
			// STRATEGIES' order is CouncilRules' (HOLD 0, STARVE 1, ROLLBACK 2, DECAPITATE 3)
			float score = threatinc.rules.CouncilRules.focusWeight(indexOf(strategy), k.ly, k.weight, k.production, k.core);
			fit.add(k);
			w.add(score);
		}
		if (fit.isEmpty()) return null;
		float[] arr = new float[w.size()];
		for (int i = 0; i < arr.length; i++) arr[i] = w.get(i);
		int pick = draw(arr, random);
		return pick >= 0 ? fit.get(pick).systemId : null;
	}

	/** A council asks for a review at its next day (a play's decisive end, section 3). */
	public static void reviewSoon(String fid, String why) {
		if (!governs(fid)) return;
		Council c = councilOf(fid);
		if (c.earlyWhy == null) c.earlyWhy = why;
	}

	/** The focus is done with (taken, or the play there ended): the next plan draws another. */
	public static void dropFocus(String fid, String systemId) {
		Council c = councils().get(fid);
		if (c != null && systemId != null && systemId.equals(c.focusId)) c.focusId = null;
	}

	// ------------------------------------------------------------------
	// stance (decision 9)
	// ------------------------------------------------------------------

	protected static void setStance(Council c, FactionAPI faction) {
		String fid = faction.getId();
		ThreatPlays.Play major = ThreatPlays.major(fid);
		if (HOLD.equals(c.strategy)) {
			ThreatFactionStance.set(fid, ThreatFactionStance.CONSOLIDATE, null, "Hold");
		} else if (major != null) {
			ThreatFactionStance.set(fid, ThreatFactionStance.PRESS, major.systemId,
					c.strategy + ", " + major.type + " " + major.id);
		} else {
			ThreatFactionStance.set(fid, ThreatFactionStance.EXPAND, null, c.strategy + ", no play running");
		}
	}

	// ------------------------------------------------------------------
	// chance, personality, learning (sections 6 and 7)
	// ------------------------------------------------------------------

	/** An index drawn in proportion to weight^(1/temperature); temperature 0 picks the best. -1 when every weight is 0. */
	public static int draw(float[] weights, Random random) {
		return threatinc.rules.CouncilRules.draw(weights, ThreatIncConfig.councilTemperature(), random);
	}

	/** 1 +- councilJitter. */
	public static float jitter(Random random) {
		return threatinc.rules.CouncilRules.jitter(ThreatIncConfig.councilJitter(), random);
	}

	private static JSONObject personalities;

	/** The faction's personality figure for the key (settings.json threatinc_councilPersonalities), its "default" entry's, or 1. */
	public static float personality(String fid, String key) {
		if (personalities == null) {
			try {
				personalities = Global.getSettings().getJSONObject("threatinc_councilPersonalities");
			} catch (Exception e) {
				personalities = new JSONObject();
			}
		}
		JSONObject p = fid != null ? personalities.optJSONObject(fid) : null;
		if (p == null || !p.has(key)) p = personalities.optJSONObject("default");
		if (p == null || !p.has(key)) return 1f;
		return (float) Math.max(0d, p.optDouble(key, 1d));
	}

	public static float learned(Council c, String key) {
		Float w = c != null && c.learned != null ? c.learned.get(key) : null;
		return w != null ? w : 1f;
	}

	/** A play's verdict teaches its faction: x1.25 for a success, x0.8 for a failure, within [0.25, 4]. */
	public static void learn(String fid, String key, boolean success) {
		if (!ThreatIncConfig.councilLearning() || fid == null || key == null) return;
		Council c = councilOf(fid);
		c.learned.put(key, threatinc.rules.CouncilRules.learn(learned(c, key), success));
	}

	// ------------------------------------------------------------------
	// logging (section 13)
	// ------------------------------------------------------------------

	protected static void logPicture(Council c, Picture p, float today) {
		Cluster focus = p.cluster(c.focusId);
		StringBuilder plays = new StringBuilder();
		for (ThreatPlays.Play pl : ThreatPlays.of(c.factionId)) {
			plays.append(plays.length() > 0 ? ", " : "").append(pl.type).append(" ").append(pl.id).append(" ")
					.append(pl.phase);
		}
		ThreatIncConfig.log("Council " + c.factionId + ": picture " + p.hives + " hives in " + p.clusters.size()
				+ " systems (" + p.cores + " cores, " + p.frontier + " frontier, " + p.stale + " stale, " + p.inReach
				+ " in reach, " + p.threatening + " threatening), means " + (int) p.fuel + " fuel " + (int) p.supplies
				+ " supplies " + (int) p.marines + " marines (+" + (int) p.fuelPer30 + "/+" + (int) p.suppliesPer30
				+ " a month), weight " + (int) p.ourWeight + (p.partners.isEmpty() ? "" : " + allies " + (int) p.allyWeight
						+ " (" + Misc.getAndJoined(p.partners) + ")")
				+ " vs " + (int) p.swarmWeight + ": " + BANDS[Math.max(0, p.band)] + " " + two(p.ratio)
				+ (p.pressed ? "; pressed (" + p.strikes + " strikes in 90 d, " + p.fronts + " fronts)" : "")
				+ (p.relief ? "; relief owed" : "")
				+ "; strategy " + c.strategy + (focus != null ? " (" + focus.name + ")" : "") + " since " + date(c.since)
				+ "; plays " + (plays.length() > 0 ? plays.toString() : "none"));
	}

	public static String date(float day) {
		if (day == NEVER) return "never";
		try {
			return Global.getSector().getClock().createClock((long) (day * 86400000d)).getDateString();
		} catch (RuntimeException e) {
			return "day " + (int) day;
		}
	}

	protected static String two(float x) {
		return String.format("%.2f", x);
	}
}
