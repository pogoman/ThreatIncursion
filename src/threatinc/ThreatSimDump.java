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
}
