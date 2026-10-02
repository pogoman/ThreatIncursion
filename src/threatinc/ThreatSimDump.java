package threatinc;

import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.impl.campaign.intel.group.GenericRaidFGI;
import com.fs.starfarer.api.util.Misc;

/**
 * What the offline simulator needs from a run (docs/war-sim.md 5): a dated
 * line in the log once a game day, and, behind threatinc_debugSimDump, the
 * map once and the war's state every 30 days as JSON in saves/common.
 */
public class ThreatSimDump {

	protected static final String KEY_LAST_DUMP = "threatinc_simDumpDay";
	protected static final float DUMP_DAYS = 30f;

	protected static long clockDay = -1L;
	protected static boolean mapWritten;

	public static void forget() {
		clockDay = -1L;
		mapWritten = false;
	}

	public static void poll() {
		long day = ThreatReach.today();
		if (day == clockDay) return;
		clockDay = day;
		ThreatIncConfig.log("Clock: day " + day + " war " + (int) ThreatIncData.daysSinceStart() + " ("
				+ Global.getSector().getClock().getDateString() + ")");
		if (!ThreatIncConfig.debugSimDump()) return;
		Object last = Global.getSector().getPersistentData().get(KEY_LAST_DUMP);
		if (last instanceof Long && day - (Long) last < DUMP_DAYS) return;
		Global.getSector().getPersistentData().put(KEY_LAST_DUMP, day);
		try {
			if (!mapWritten) {
				Global.getSettings().writeTextFileToCommon("threatinc_simmap.json", map().toString(1));
				mapWritten = true;
			}
			Global.getSettings().writeTextFileToCommon(String.format("threatinc_simdump_d%06d.json", day),
					state(day).toString(1));
			ThreatIncConfig.log("Sim dump: day " + day);
		} catch (Throwable t) {
			Global.getLogger(ThreatSimDump.class).warn("[ThreatInc] Sim dump failed", t);
		}
	}

	/** Every star system, in light years. */
	protected static JSONObject map() throws Exception {
		JSONArray systems = new JSONArray();
		float unit = Misc.getUnitsPerLightYear();
		for (StarSystemAPI sys : Global.getSector().getStarSystems()) {
			int planets = 0;
			for (PlanetAPI p : sys.getPlanets()) if (!p.isStar()) planets++;
			JSONObject o = new JSONObject();
			o.put("id", sys.getId());
			o.put("name", sys.getName());
			o.put("x", sys.getLocation().x / unit);
			o.put("y", sys.getLocation().y / unit);
			o.put("planets", planets);
			systems.put(o);
		}
		JSONObject out = new JSONObject();
		out.put("systems", systems);
		return out;
	}

	protected static JSONObject state(long day) throws Exception {
		JSONObject out = new JSONObject();
		out.put("day", day);
		out.put("warDay", (int) ThreatIncData.daysSinceStart());
		out.put("date", Global.getSector().getClock().getDateString());
		out.put("swarm", swarm());
		out.put("hives", hives());
		out.put("worlds", worlds());
		out.put("forwardBases", forwardBases());
		out.put("factions", factions());
		// dump v2: one failing section leaves the rest of the dump whole
		try { out.put("fleets", fleets()); } catch (Throwable t) { out.put("fleetsError", String.valueOf(t)); }
		try { out.put("knowledge", knowledge()); } catch (Throwable t) { out.put("knowledgeError", String.valueOf(t)); }
		try { out.put("strategy", strategy()); } catch (Throwable t) { out.put("strategyError", String.valueOf(t)); }
		try { out.put("war", war()); } catch (Throwable t) { out.put("warError", String.valueOf(t)); }
		return out;
	}

	protected static JSONObject swarm() throws Exception {
		JSONObject o = new JSONObject();
		o.put("stance", ThreatStance.stanceName());
		o.put("fuel", ThreatFuel.stock(Commodities.FUEL));
		o.put("fuelPerMonth", ThreatFuel.perMonth(Commodities.FUEL));
		o.put("supplies", ThreatFuel.stock(Commodities.SUPPLIES));
		o.put("suppliesPerMonth", ThreatFuel.perMonth(Commodities.SUPPLIES));
		JSONArray posture = new JSONArray();
		for (Map.Entry<String, float[]> e : ThreatPosture.state().entrySet()) {
			StarSystemAPI sys = Global.getSector().getStarSystem(e.getKey());
			float[] s = e.getValue();
			JSONObject p = new JSONObject();
			p.put("system", e.getKey());
			p.put("pressure", s != null && s.length > ThreatPosture.S_PRESSURE ? s[ThreatPosture.S_PRESSURE] : 0f);
			if (sys != null) p.put("mode", ThreatPosture.MODE_NAMES[ThreatPosture.mode(sys)]);
			posture.put(p);
		}
		o.put("posture", posture);
		return o;
	}

	protected static JSONArray hives() throws Exception {
		JSONArray out = new JSONArray();
		for (String systemId : ThreatIncData.colonyMarkets().keySet()) {
			for (MarketAPI m : ThreatIncData.getLiveColonyMarkets(systemId)) {
				List<CampaignFleetAPI> fleets = ThreatIncData.garrisons().get(m.getId());
				int alive = 0;
				if (fleets != null) for (CampaignFleetAPI f : fleets) if (f != null && f.isAlive()) alive++;
				JSONObject o = new JSONObject();
				o.put("id", m.getId());
				o.put("name", m.getName());
				o.put("system", systemId);
				o.put("size", m.getSize());
				o.put("growthDays", ThreatIncData.growthProgressDays(m.getId()));
				o.put("bank", ThreatColonyManager.fpBank(m.getId()));
				o.put("garrisonFP", fleets == null ? 0f : ThreatColonyManager.garrisonFP(fleets));
				o.put("garrisonFleets", alive);
				o.put("ownedFP", fleets == null ? 0f : ThreatColonyManager.ownedFleetFP(m, fleets));
				o.put("wantFP", ThreatPosture.wantFP(m));
				o.put("needFP", ThreatPosture.needFP(m));
				o.put("forge", organ(ThreatColonyManager.getForge(m)));
				o.put("fuelPlant", organ(m.getIndustry(Industries.FUELPROD)));
				o.put("nexus", organ(m.getIndustry(ThreatColonyManager.SWARM_NEXUS)));
				o.put("core", organ(m.getIndustry(ThreatColonyManager.FABRICATION_CORE)));
				o.put("tier", SwarmBastion.tier(m));
				o.put("fortification", ThreatGroundFronts.fortificationCondition(m));
				o.put("siegeClock", ThreatGroundFronts.siegeClock(m));
				o.put("daysSinceGrowth", finite(ThreatIncData.daysSinceGrowth(m.getId())));
				Industry forge = ThreatColonyManager.getForge(m);
				o.put("nanoforge", forge == null || forge.getSpecialItem() == null ? "" : forge.getSpecialItem().getId());
				o.put("defence", ThreatGroundFronts.defenderStrength(m));
				front(o, m);
				out.put(o);
			}
		}
		return out;
	}

	/** "none", "up", or the days it stays disrupted. */
	protected static Object organ(Industry ind) {
		if (ind == null) return "none";
		if (ind.isDisrupted()) return ind.getDisruptedDays();
		return "up";
	}

	protected static void front(JSONObject o, MarketAPI m) throws Exception {
		ThreatGroundFronts.GroundFront f = ThreatGroundFronts.getFront(m.getId());
		if (f == null) return;
		JSONObject j = new JSONObject();
		j.put("faction", f.factionId);
		j.put("marines", f.marines);
		j.put("state", f.state);
		j.put("strataHeld", f.strataHeld);
		j.put("armaments", f.armaments);
		j.put("stance", f.stance);
		o.put("front", j);
	}

	/** Every human world, forward bases included (flagged). */
	protected static JSONArray worlds() throws Exception {
		JSONArray out = new JSONArray();
		for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
			if (m.isHidden() || m.isPlanetConditionMarketOnly() || m.getPrimaryEntity() == null) continue;
			if (Factions.THREAT.equals(m.getFactionId()) || m.getStarSystem() == null) continue;
			JSONObject o = new JSONObject();
			o.put("id", m.getId());
			o.put("name", m.getName());
			o.put("faction", m.getFactionId());
			o.put("system", m.getStarSystem().getId());
			o.put("size", m.getSize());
			o.put("forwardBase", ThreatFrontlines.isOutpost(m));
			o.put("base", IncursionManager.isBase(m));
			o.put("defence", ThreatGroundFronts.defenderStrength(m));
			ThreatReserves.ColonyReserve r = ThreatReserves.get(m.getId());
			if (r != null) {
				JSONObject stock = new JSONObject();
				JSONObject accrual = new JSONObject();
				for (String c : ThreatReserves.COMMODITIES) {
					stock.put(c, ThreatReserves.stock(m.getId(), c));
					accrual.put(c, ThreatReserves.accrualPer30(m, c));
				}
				o.put("stock", stock);
				o.put("accrualPer30", accrual);
			}
			front(o, m);
			out.put(o);
		}
		return out;
	}

	protected static JSONArray forwardBases() throws Exception {
		JSONArray out = new JSONArray();
		for (ThreatFrontlines.Outpost b : ThreatFrontlines.all()) {
			JSONObject o = new JSONObject();
			o.put("market", b.marketId);
			o.put("faction", b.factionId);
			o.put("hiveSystem", b.hiveSystemId == null ? "" : b.hiveSystemId);
			out.put(o);
		}
		return out;
	}

	protected static JSONArray factions() throws Exception {
		JSONArray out = new JSONArray();
		for (String fid : ThreatWarState.warFactionIds()) {
			if (Factions.THREAT.equals(fid)) continue;
			ThreatWarState.FactionWar w = ThreatWarState.get(fid);
			JSONObject o = new JSONObject();
			o.put("id", fid);
			o.put("strikesSuffered", w == null ? 0 : w.strikesSuffered);
			String strategy = ThreatWarCouncil.strategy(fid);
			o.put("strategy", strategy == null ? "" : strategy);
			out.put(o);
		}
		return out;
	}
	// ---- what is in motion and what is remembered: a mid-war save as a start (docs/war-sim.md 12) ----

	protected static final float LY_PER_DAY = 0.5f;

	protected static float finite(float v) {
		return Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 1e12f ? -1f : v;
	}

	protected static String systemOf(MarketAPI m) {
		return m == null || m.getStarSystem() == null ? "" : m.getStarSystem().getId();
	}

	protected static String systemOf(String marketId) {
		return marketId == null ? "" : systemOf(Global.getSector().getEconomy().getMarket(marketId));
	}

	protected static StarSystemAPI system(String id) {
		if (id == null) return null;
		for (StarSystemAPI s : Global.getSector().getStarSystems()) if (id.equals(s.getId())) return s;
		return null;
	}

	/** Where a fleet is now: its system, or "" in hyperspace. */
	protected static String systemOf(CampaignFleetAPI f) {
		return f != null && f.getContainingLocation() instanceof StarSystemAPI
				? ((StarSystemAPI) f.getContainingLocation()).getId() : "";
	}

	protected static float etaDays(CampaignFleetAPI f, String toSystemId) {
		StarSystemAPI to = system(toSystemId);
		if (f == null || to == null) return -1f;
		return Misc.getDistanceLY(f.getLocationInHyperspace(), to.getLocation()) / LY_PER_DAY;
	}

	protected static JSONObject fleet(String owner, String kind, float fp, String from, String to, String target,
			float etaDays) throws Exception {
		JSONObject j = new JSONObject();
		j.put("owner", owner);
		j.put("kind", kind);
		j.put("fp", finite(fp));
		j.put("from", from == null ? "" : from);
		j.put("to", to == null ? "" : to);
		j.put("target", target == null ? "" : target);
		j.put("etaDays", finite(etaDays));
		return j;
	}

	protected static String targetsOf(List<MarketAPI> targets) {
		StringBuilder b = new StringBuilder();
		if (targets != null) for (MarketAPI t : targets) b.append(b.length() == 0 ? "" : ",").append(t.getId());
		return b.toString();
	}

	/** Every fleet in flight or on station, either side, in the simulator's terms. A bad entry is skipped, not fatal. */
	protected static JSONArray fleets() {
		JSONArray out = new JSONArray();
		for (Object o : IncursionManager.getStrikeList()) {
			try {
				if (!(o instanceof ThreatStrikeFGI)) continue;
				ThreatStrikeFGI k = (ThreatStrikeFGI) o;
				if (k.isEnded() || k.isEnding()) continue;
				StarSystemAPI where = k.getParams().raidParams.where;
				JSONObject j = fleet(Factions.THREAT, "STRIKE", k.fightingFP(), systemOf(k.getParams().source),
						where == null ? "" : where.getId(), targetsOf(k.getParams().raidParams.allowedTargets),
						k.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION));
				j.put("troops", finite(k.troopsAboard));
				j.put("spawned", k.isSpawnedFleets());
				out.put(j);
			} catch (Throwable t) { skipped("strike", t); }
		}
		for (Object o : IncursionManager.getPurgeList()) {
			try {
				if (!(o instanceof ThreatPurgeFGI)) continue;
				ThreatPurgeFGI k = (ThreatPurgeFGI) o;
				if (k.isEnded() || k.isEnding()) continue;
				StarSystemAPI where = k.getParams().raidParams.where;
				JSONObject j = fleet(k.getFaction().getId(), k.razesAll() ? "SATURATION" : "SIEGE", k.fightingFP(),
						systemOf(k.sourceBase()), where == null ? "" : where.getId(),
						targetsOf(k.getParams().raidParams.allowedTargets), k.getETAUntil(GenericRaidFGI.PAYLOAD_ACTION));
				j.put("base", k.sourceBase() == null ? "" : k.sourceBase().getId());
				j.put("marines", finite(k.getMarinesAllotted()));
				j.put("armaments", finite(k.getArmamentsAllotted()));
				j.put("fuel", finite(k.razeFuelLeft()));
				j.put("spawned", k.isSpawnedFleets());
				j.put("play", k.getPlayId() == null ? "" : k.getPlayId());
				out.put(j);
			} catch (Throwable t) { skipped("siege", t); }
		}
		for (Map.Entry<String, CampaignFleetAPI> e : ThreatIncData.waveFleets().entrySet()) {
			try {
				CampaignFleetAPI f = e.getValue();
				if (f == null || !f.isAlive()) continue;
				String to = ThreatIncData.waveTargets().get(e.getKey());
				out.put(fleet(Factions.THREAT, "WAVE", f.getFleetPoints(), systemOf(f), to, e.getKey(), etaDays(f, to)));
			} catch (Throwable t) { skipped("wave", t); }
		}
		for (CampaignFleetAPI f : ThreatIncData.reinforcementFleets().values()) {
			try {
				if (f == null || !f.isAlive()) continue;
				String target = f.getMemoryWithoutUpdate().getString("$threatinc_reinforceTarget");
				String to = systemOf(target);
				out.put(fleet(Factions.THREAT, "REINFORCEMENT", f.getFleetPoints(), systemOf(f), to, target, etaDays(f, to)));
			} catch (Throwable t) { skipped("reinforcement", t); }
		}
		for (ThreatRaiders.Raider r : ThreatRaiders.all()) {
			try {
				if (r.fleet == null || !r.fleet.isAlive()) continue;
				JSONObject j = fleet(Factions.THREAT, "RAIDER", r.fleet.getFleetPoints(), systemOf(r.homeMarketId),
						systemOf(r.fleet), "", -1f);
				j.put("returning", r.returning);
				out.put(j);
			} catch (Throwable t) { skipped("raider", t); }
		}
		for (ThreatSoftening.Force f : ThreatSoftening.forces().values()) {
			try {
				JSONObject j = fleet(f.factionId, "MUSTER", ThreatSoftening.forceFP(f.id), "", f.systemId, "", -1f);
				j.put("id", f.id);
				j.put("engaged", f.engaged);
				j.put("hold", f.hold);
				j.put("play", f.playId == null ? "" : f.playId);
				out.put(j);
			} catch (Throwable t) { skipped("force", t); }
		}
		for (ThreatFleetOrders.Order o : ThreatFleetOrders.all()) {
			try {
				if (o.fleet == null || !o.fleet.isAlive()) continue;
				String kind = o.raid ? "SQUADRON" : "hunt".equals(o.kind) ? "HUNT" : "guard".equals(o.kind) ? "GUARD" : "RELIEF";
				JSONObject j = fleet(o.factionId, kind, o.fleet.getFleetPoints(), systemOf(o.baseMarketId),
						o.systemId != null ? o.systemId : systemOf(o.targetId), o.targetId, o.arrived ? 0f : -1f);
				j.put("base", o.baseMarketId == null ? "" : o.baseMarketId);
				j.put("arrived", o.arrived);
				j.put("daysLeft", finite(o.daysLeft()));
				j.put("force", o.forceId == null ? "" : o.forceId);
				j.put("at", systemOf(o.fleet));
				out.put(j);
			} catch (Throwable t) { skipped("order", t); }
		}
		for (ThreatConvoys.Convoy c : ThreatConvoys.all()) {
			try {
				if (c.fleet == null || !c.fleet.isAlive()) continue;
				String to = systemOf(c.toMarketId);
				JSONObject j = fleet(c.factionId, "CONVOY", c.fleet.getFleetPoints(), systemOf(c.fromMarketId), to,
						c.toMarketId, etaDays(c.fleet, to));
				j.put("marines", finite(c.marines));
				j.put("armaments", finite(c.armaments));
				j.put("fuel", finite(c.fuel));
				j.put("supplies", finite(c.supplies));
				out.put(j);
			} catch (Throwable t) { skipped("convoy", t); }
		}
		return out;
	}

	protected static void skipped(String what, Throwable t) {
		ThreatIncConfig.logQuiet("simdump-" + what, "Sim dump: skipped a " + what + " (" + t + ")");
	}

	/** What each side knows of the other, as ages in days. */
	protected static JSONObject knowledge() throws Exception {
		float now = ThreatPosture.today();
		JSONObject out = new JSONObject();
		JSONArray reports = new JSONArray();
		for (Map.Entry<String, Map<String, ThreatIntel.Report>> e : ThreatIntel.store().entrySet()) {
			for (ThreatIntel.Report r : e.getValue().values()) {
				float fp = r.nearFP + r.looseFP;
				if (r.worlds != null) for (float[] w : r.worlds.values()) if (w != null && w.length > 0) fp += w[0];
				JSONObject j = new JSONObject();
				j.put("faction", e.getKey());
				j.put("system", r.systemId);
				j.put("source", r.source == null ? "" : r.source);
				j.put("ageDays", finite(now - r.day));
				j.put("fp", finite(fp));
				reports.put(j);
			}
		}
		out.put("reports", reports);
		out.put("found", new JSONArray(ThreatIncData.discoveredSystems()));
		JSONArray places = new JSONArray();
		for (ThreatSwarmIntel.Place p : ThreatSwarmIntel.places()) {
			JSONObject j = new JSONObject();
			j.put("world", p.marketId);
			j.put("faction", p.factionId == null ? "" : p.factionId);
			j.put("ageDays", finite(now - p.day));
			j.put("stagedFP", finite(p.stagedFP));
			j.put("guardsFP", finite(p.guardsFP));
			j.put("defenceFP", finite(p.defenceFP));
			places.put(j);
		}
		out.put("swarmPlaces", places);
		JSONObject grudges = new JSONObject();
		for (Map.Entry<String, Float> e : ThreatAlarm.grudges().entrySet()) grudges.put(e.getKey(), finite(e.getValue()));
		out.put("grudges", grudges);
		return out;
	}

	/** Councils and their plays in progress. */
	protected static JSONObject strategy() throws Exception {
		float now = ThreatPosture.today();
		JSONObject out = new JSONObject();
		JSONArray councils = new JSONArray();
		for (ThreatWarCouncil.Council c : ThreatWarCouncil.councils().values()) {
			JSONObject j = new JSONObject();
			j.put("faction", c.factionId);
			j.put("strategy", c.strategy == null ? "" : c.strategy);
			j.put("focus", c.focusId == null ? "" : c.focusId);
			j.put("band", c.band);
			j.put("ratio", finite(c.ratio));
			j.put("heldDays", finite(now - c.since));
			j.put("reviewInDays", finite(c.reviewDue - now));
			JSONObject learned = new JSONObject();
			if (c.learned != null) for (Map.Entry<String, Float> e : c.learned.entrySet()) learned.put(e.getKey(), finite(e.getValue()));
			j.put("learned", learned);
			councils.put(j);
		}
		out.put("councils", councils);
		JSONArray plays = new JSONArray();
		for (ThreatPlays.Play p : ThreatPlays.plays().values()) {
			if (p.outcome != null) continue;
			JSONObject j = new JSONObject();
			j.put("id", p.id);
			j.put("type", p.type);
			j.put("faction", p.factionId);
			j.put("system", p.systemId == null ? "" : p.systemId);
			j.put("phase", p.phase == null ? "" : p.phase);
			j.put("phaseDueInDays", finite(p.phaseDue - now));
			j.put("base", p.baseId == null ? "" : p.baseId);
			plays.put(j);
		}
		out.put("plays", plays);
		return out;
	}

	protected static JSONObject war() throws Exception {
		JSONObject o = new JSONObject();
		o.put("phase", IncursionManager.getPhase());
		o.put("home", String.valueOf(ThreatIncData.getOGSystem()));
		float[] trend = ThreatStance.trend(ThreatPosture.today());
		if (trend != null && trend.length >= 2) {
			o.put("threatLostFP", finite(trend[0]));
			o.put("threatKilledFP", finite(trend[1]));
		}
		JSONObject stages = new JSONObject();
		for (Map.Entry<String, String> e : ThreatIncData.stages().entrySet()) stages.put(e.getKey(), e.getValue());
		o.put("stages", stages);
		return o;
	}
}
