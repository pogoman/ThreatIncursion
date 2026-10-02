package warsim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A start state read from the mod's dump (ThreatSimDump): the star map and one day's state.
 * Parsed once; fill() builds a fresh State from it for every run.
 */
public final class Start {
	private final Map<String, Object> map, dump;
	/** The home system the opening chain lands in, when the caller knows it (Main.check reads it from a real run's dumps); else the dump's, else SwarmOps.pickOG. */
	public String ogSystem;

	public Start(Path mapFile, Path dumpFile) throws IOException {
		map = Json.obj(Json.parse(new String(Files.readAllBytes(mapFile), StandardCharsets.UTF_8)));
		dump = Json.obj(Json.parse(new String(Files.readAllBytes(dumpFile), StandardCharsets.UTF_8)));
	}

	public void fill(State s) {
		for (Object o : Json.arr(map.get("systems"))) {
			Map<String, Object> j = Json.obj(o);
			StarSys sys = new StarSys();
			sys.id = Json.str(j.get("id"), "");
			sys.name = Json.str(j.get("name"), sys.id);
			sys.x = Json.num(j.get("x"), 0f);
			sys.y = Json.num(j.get("y"), 0f);
			sys.planets = (int) Json.num(j.get("planets"), 0f);
			s.systems.put(sys.id, sys);
		}
		// the distance table, once a run (round 9): every ly() call was a sqrt in the hot loops
		{
			int i = 0;
			for (StarSys sys : s.systems.values()) sys.index = i++;
			int n = s.systems.size();
			for (StarSys a : s.systems.values()) {
				a.dist = new float[n];
				for (StarSys b : s.systems.values()) a.dist[b.index] = a == b ? 0f : a.compute(b);
			}
		}
		s.day = s.startDay = (int) Json.num(dump.get("day"), 0f);

		Map<String, Object> sw = Json.obj(dump.get("swarm"));
		s.swarm.fuel = Json.num(sw.get("fuel"), 0f);
		s.swarm.supplies = Json.num(sw.get("supplies"), 0f);
		String stance = Json.str(sw.get("stance"), "EXPAND");
		s.swarm.stance = stance.equals("PRESS") ? Swarm.PRESS
				: stance.equals("CONSOLIDATE") ? Swarm.CONSOLIDATE : Swarm.EXPAND;

		for (Object o : Json.arr(dump.get("hives"))) {
			Map<String, Object> j = Json.obj(o);
			StarSys sys = s.systems.get(Json.str(j.get("system"), ""));
			if (sys == null) continue;
			Hive h = new Hive();
			h.id = Json.str(j.get("id"), "");
			h.name = Json.str(j.get("name"), h.id);
			h.sys = sys;
			h.size = (int) Json.num(j.get("size"), 1f);
			h.growthDays = Json.num(j.get("growthDays"), 0f);
			h.bank = Json.num(j.get("bank"), 0f);
			h.garrisonFP = Json.num(j.get("garrisonFP"), 0f);
			h.garrisonFleets = (int) Json.num(j.get("garrisonFleets"), 0f);
			h.wantFP = Json.num(j.get("wantFP"), 0f);
			h.needFP = Json.num(j.get("needFP"), 0f);
			h.forge = present(j.get("forge"));
			h.forgeDown = down(j.get("forge"));
			h.fuelPlant = present(j.get("fuelPlant"));
			h.fuelPlantDown = down(j.get("fuelPlant"));
			h.nexus = present(j.get("nexus"));
			h.nexusDown = down(j.get("nexus"));
			h.core = present(j.get("core"));
			h.coreDown = down(j.get("core"));
			h.tier = (int) Json.num(j.get("tier"), 0f);
			h.fortification = Json.num(j.get("fortification"), 1f);
			h.siegeClock = Json.num(j.get("siegeClock"), 0f);
			h.defence = Json.num(j.get("defence"), 0f);
			h.front = front(j.get("front"));
			h.foundedDay = s.day;
			s.hives.add(h);
		}

		for (Object o : Json.arr(dump.get("worlds"))) {
			Map<String, Object> j = Json.obj(o);
			StarSys sys = s.systems.get(Json.str(j.get("system"), ""));
			if (sys == null) continue;
			World w = new World();
			w.id = Json.str(j.get("id"), "");
			w.name = Json.str(j.get("name"), w.id);
			w.faction = Json.str(j.get("faction"), "");
			w.sys = sys;
			w.size = (int) Json.num(j.get("size"), 1f);
			w.forwardBase = Json.bool(j.get("forwardBase"), false);
			w.base = Json.bool(j.get("base"), false);
			w.defence = Json.num(j.get("defence"), 0f);
			// warsim_orbitGate=false: the ground figure at the strike gate, as before the dumps carried the orbit's
			w.gate = s.knobs == null || s.knobs.b("warsim_orbitGate", true) ? Json.num(j.get("gate"), -1f) : -1f;
			Map<String, Object> stock = Json.obj(j.get("stock"));
			Map<String, Object> accrual = Json.obj(j.get("accrualPer30"));
			w.hasReserve = j.get("stock") != null;
			for (int c = 0; c < 4; c++) {
				w.stock[c] = Json.num(stock.get(World.COMMODITIES[c]), 0f);
				w.accrualPer30[c] = Json.num(accrual.get(World.COMMODITIES[c]), 0f);
			}
			w.front = front(j.get("front"));
			w.foundedDay = s.day;
			s.worlds.add(w);
			s.faction(w.faction);
		}

		for (Object o : Json.arr(dump.get("forwardBases"))) {
			Map<String, Object> j = Json.obj(o);
			World w = s.world(Json.str(j.get("market"), ""));
			if (w != null) w.facesHive = s.systems.get(Json.str(j.get("hiveSystem"), ""));
		}

		for (Object o : Json.arr(dump.get("factions"))) {
			Map<String, Object> j = Json.obj(o);
			Faction f = s.faction(Json.str(j.get("id"), ""));
			f.mobilised = true;
			f.mobilisedDay = s.day;
			f.strikesSuffered = (int) Json.num(j.get("strikesSuffered"), 0f);
			f.strategy = Json.str(j.get("strategy"), "");
		}

		s.dump = dump;
		// fleets in flight or on station (dump v2): parcels with no order, which each side reads as a default of its kind
		for (Object o : Json.arr(dump.get("fleets"))) {
			Map<String, Object> j = Json.obj(o);
			Parcel.Kind kind;
			try {
				kind = Parcel.Kind.valueOf(Json.str(j.get("kind"), ""));
			} catch (IllegalArgumentException e) {
				continue;
			}
			StarSys to = s.systems.get(Json.str(j.get("to"), ""));
			StarSys from = s.systems.get(Json.str(j.get("from"), ""));
			if (to == null) to = s.systems.get(Json.str(j.get("at"), ""));
			if (to == null) continue;
			float eta = Json.num(j.get("etaDays"), -1f);
			boolean there = Json.bool(j.get("arrived"), false) || (kind == Parcel.Kind.MUSTER);
			Parcel p = s.send(Json.str(j.get("owner"), ""), kind, from != null ? from : to, to, Json.num(j.get("fp"), 0f), 0);
			p.targetId = Json.str(j.get("target"), "");
			p.marines = Math.max(0f, Json.num(j.get("marines"), 0f));
			p.armaments = Math.max(0f, Json.num(j.get("armaments"), 0f));
			p.fuel = Math.max(0f, Json.num(j.get("fuel"), 0f));
			p.supplies = Math.max(0f, Json.num(j.get("supplies"), 0f));
			if (kind == Parcel.Kind.MUSTER) p.against = to;
			if (there) {
				p.arriveDay = s.day;
				p.arrived = true;
				p.holding = true;
			} else if (eta >= 0f) p.arriveDay = s.day + Math.max(1, (int) Math.ceil(eta));
		}

		for (Object o : Json.arr(sw.get("posture"))) {
			Map<String, Object> j = Json.obj(o);
			String mode = Json.str(j.get("mode"), "QUIET");
			int m = mode.equals("WATCHFUL") ? 1 : mode.equals("THREATENED") ? 2 : mode.equals("BESIEGED") ? 3 : 0;
			s.swarm.posture.put(Json.str(j.get("system"), ""), new float[] { Json.num(j.get("pressure"), 0f), m });
		}
		if (sw.get("ogSystem") != null) s.swarm.ogSystemId = Json.str(sw.get("ogSystem"), null);
		if (ogSystem != null) s.swarm.ogSystemId = ogSystem;
		if (sw.get("nextTickDays") != null) s.swarm.nextTickDay = s.day + Json.num(sw.get("nextTickDays"), 0f);
		HumanSide.load(s, dump);
	}

	/** An organ is "none", "up", or the days it stays disrupted. */
	private static boolean present(Object organ) { return organ != null && !"none".equals(organ); }

	private static float down(Object organ) { return organ instanceof Number ? ((Number) organ).floatValue() : 0f; }

	private static Front front(Object o) {
		if (o == null) return null;
		Map<String, Object> j = Json.obj(o);
		Front f = new Front();
		f.faction = Json.str(j.get("faction"), "");
		f.marines = Json.num(j.get("marines"), 0f);
		f.state = Json.str(j.get("state"), f.state);
		f.strataHeld = (int) Json.num(j.get("strataHeld"), 0f);
		return f;
	}

}
