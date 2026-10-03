package warsim;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * warsim run     [-seed N] [-months N] [-out file.csv] [-v]
 * warsim batch   [-seeds N] [-months N]
 * warsim compare -a k=v[;k=v] -b k=v[;k=v] [-seeds N] [-months N]
 * warsim check   -dumps A [-log a.txt] [-dumps2 B [-log2 b.txt]] [-seeds N]   (two real runs: coverage of both, and bias)
 * warsim check   -dumps folder [-seeds N]      the simulator beside a real run's monthly dumps
 * Common: -show col,col (more columns: any counter),
 *         -start folder (threatinc_simmap.json + a threatinc_simdump_d*.json; default tools/warsim/start),
 *         -settings file, -set key=value (repeatable), -killWeight x, -sizeExponent x.
 */
public final class Main {

	/** The columns the batch, compare and check tables print. */
	static String[] SHOWN = { "hives", "hiveSize", "garrisonFP", "bank", "swarmFuel", "swarmSupplies",
			"worlds", "basesHeld", "basesFounded", "basesDestroyed", "hivesFounded", "hiveLevels", "hivesKilled",
			"worldsLost", "siegesSailed", "siegesLanded", "strikesLaunched", "threatScore", "humanScore", "threatScoreK2", "humanScoreK2",
			"threatKills", "threatKills.worlds", "threatKills.bases", "humanKills", "mutual",
			"turnover", "swings", "reversals", "contested", "deadYears" };
	/**
	 * The run classes (docs/war-sim.md 7; the user's lead measure since 2026-10-02 round 11 is destruction by both
	 * sides): "both sides" when mutual = min(threatKills, humanKills) a year since the first mobilisation reaches
	 * MUTUAL_PER_YEAR; "one-sided" when one side's rate reaches it and the other's is under QUIET_PER_YEAR; "quiet"
	 * when both are under QUIET_PER_YEAR. swings, reversals, contested and deadYears stay as columns.
	 */
	static final double MUTUAL_PER_YEAR = 1.0, QUIET_PER_YEAR = 0.25;
	static final String[] CLASSES = { "decided for the swarm", "decided for the humans", "both sides", "one-sided", "quiet", "other" };
	static final int[] CHECKPOINTS = { 12, 24, 36, 48, 72, 97 };

	public static void main(String[] args) throws Exception {
		Locale.setDefault(Locale.ROOT);
		if (args.length == 0) { System.out.println("usage: warsim run|batch|compare|check ... (see docs/war-sim.md)"); return; }
		Path root = repoRoot();
		Path settings = root.resolve("data/config/settings.json");
		Path startDir = root.resolve("tools/warsim/start");
		Path dumps = null, dumps2 = null, out = null, log = null, log2 = null;
		long seed = 1;
		int seeds = 100, months = 100;
		float killWeight = 0f, sizeExponent = 1f;
		boolean verbose = false;
		List<String> sets = new ArrayList<String>();
		String a = "", b = "", home = null;
		for (int i = 1; i < args.length; i++) {
			String o = args[i];
			if (o.equals("-v")) verbose = true;
			else if (o.equals("-seed")) seed = Long.parseLong(args[++i]);
			else if (o.equals("-seeds")) seeds = Integer.parseInt(args[++i]);
			else if (o.equals("-months")) months = Integer.parseInt(args[++i]);
			else if (o.equals("-out")) out = Paths.get(args[++i]);
			else if (o.equals("-start")) startDir = Paths.get(args[++i]);
			// -home <system id>: the opening chain lands there (a real run's home for a traced single seed, as check does)
			else if (o.equals("-home")) home = args[++i];
			else if (o.equals("-settings")) settings = Paths.get(args[++i]);
			else if (o.equals("-dumps")) dumps = Paths.get(args[++i]);
			else if (o.equals("-log")) log = Paths.get(args[++i]);
			else if (o.equals("-dumps2")) dumps2 = Paths.get(args[++i]);
			else if (o.equals("-log2")) log2 = Paths.get(args[++i]);
			else if (o.equals("-set")) sets.add(args[++i]);
			else if (o.equals("-a")) a = args[++i];
			else if (o.equals("-b")) b = args[++i];
			else if (o.equals("-show")) {
				// more columns (any counter) for the run, batch and compare tables: -show plays.HAMMER,plays.HAMMER.success
				List<String> all = new ArrayList<String>(Arrays.asList(SHOWN));
				all.addAll(Arrays.asList(args[++i].split(",")));
				SHOWN = all.toArray(new String[0]);
			}
			else if (o.equals("-killWeight")) killWeight = Float.parseFloat(args[++i]);
			else if (o.equals("-sizeExponent")) sizeExponent = Float.parseFloat(args[++i]);
			else throw new IllegalArgumentException("unknown option " + o);
		}
		Knobs knobs = Knobs.load(settings);
		// no radar on either side (the user's decision of 2026-10-02, facts.md "No radar; presence is knowledge"; the mod
		// dropped radarRangeLY and swarmRadarRangeLY in 262ac76): the old behaviour for comparison is the simulator's own
		// -set "warsim_radarLY=10;warsim_swarmRadarLY=10" (HumanIntel.sweep, SwarmOps.radar)
		// -set k=v, repeatable; one -set may also hold several, separated by ';' as -a/-b do
		for (String s : sets) for (String t : s.split(";")) if (!t.trim().isEmpty()) knobs.set(t.trim());

		String cmd = args[0];
		if (cmd.equals("check")) {
			if (dumps == null) throw new IllegalArgumentException("check needs -dumps <folder>");
			// the real runs the gates are checked against (pd9a, pd10a) are planner runs: the council is off unless -set says otherwise
			boolean said = false;
			for (String s : sets) if (s.contains("threatinc_warCouncil")) said = true;
			if (!said) knobs.set("threatinc_warCouncil=false");
			if (dumps2 == null) {
				check(dumps, knobs, seeds, killWeight, sizeExponent, log, null);
			} else {
				Map<String, double[]> ra = new java.util.LinkedHashMap<String, double[]>(), rb = new java.util.LinkedHashMap<String, double[]>();
				check(dumps, knobs, seeds, killWeight, sizeExponent, log, ra);
				check(dumps2, knobs, seeds, killWeight, sizeExponent, log2, rb);
				both(dumps.getFileName().toString(), ra, dumps2.getFileName().toString(), rb);
			}
			return;
		}
		Start start = start(startDir);
		if (home != null) start.ogSystem = home;
		if (cmd.equals("run")) {
			Sim.Result r = Sim.run(start, knobs, seed, months, sizeExponent, verbose);
			score(r, killWeight);
			if (out != null) csv(r, out);
			table("seed " + seed, months, Arrays.asList(r));
			System.out.println("outcome: " + CLASSES[outcome(r)] + decidedText(r));
		} else if (cmd.equals("batch")) {
			List<Sim.Result> rs = runs(start, knobs, seeds, months, killWeight, sizeExponent);
			table(seeds + " seeds: median [p10 - p90]", months, rs);
			System.out.println(classes(rs));
		} else if (cmd.equals("compare")) {
			Knobs ka = knobs.copy(), kb = knobs.copy();
			for (String s : a.split(";")) if (!s.trim().isEmpty()) ka.set(s.trim());
			for (String s : b.split(";")) if (!s.trim().isEmpty()) kb.set(s.trim());
			compare(a, runs(start, ka, seeds, months, killWeight, sizeExponent), b,
					runs(start, kb, seeds, months, killWeight, sizeExponent));
		} else throw new IllegalArgumentException("unknown command " + cmd);
	}

	static Path repoRoot() {
		Path p = Paths.get("").toAbsolutePath();
		while (p != null && !Files.exists(p.resolve("data/config/settings.json"))) p = p.getParent();
		if (p == null) throw new IllegalStateException("run from inside the mod folder (data/config/settings.json not found)");
		return p;
	}

	/** The map and the earliest dump in a folder (the game writes them with a .data suffix). */
	static Start start(Path dir) throws IOException {
		List<Path> d = dumpFiles(dir);
		if (d.isEmpty()) throw new IllegalStateException("no threatinc_simdump_d*.json in " + dir);
		return new Start(mapFile(dir), d.get(0));
	}

	static Path mapFile(Path dir) {
		Path m = dir.resolve("threatinc_simmap.json");
		return Files.exists(m) ? m : dir.resolve("threatinc_simmap.json.data");
	}

	static List<Path> dumpFiles(Path dir) throws IOException {
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().startsWith("threatinc_simdump_d")).sorted(java.util.Comparator.comparingLong(Main::dumpDay)).collect(Collectors.toList());
		}
	}

	/** The day in a dump's file name; game days are negative (the clock starts before 1970), so names do not sort. */
	static long dumpDay(Path p) {
		String n = p.getFileName().toString().substring("threatinc_simdump_d".length());
		return Long.parseLong(n.substring(0, n.indexOf('.')));
	}

	static List<Sim.Result> runs(Start start, Knobs knobs, int seeds, int months, float killWeight, float sizeExponent) {
		return IntStream.rangeClosed(1, seeds).parallel().mapToObj(i -> {
			Sim.Result r = Sim.run(start, knobs.copy(), i, months, sizeExponent, false);
			score(r, killWeight);
			return r;
		}).collect(Collectors.toList());
	}

	/** docs/war-sim.md 7: spread plus killWeight x worlds destroyed, per side, per month. */
	static void score(Sim.Result r, float killWeight) {
		for (Map<String, Double> m : r.months) {
			m.put("threatScore", m.get("threatSpread") + killWeight * m.get("threatKillCount"));
			m.put("humanScore", m.get("humanSpread") + killWeight * m.get("humanKillCount"));
			// round 19: the kill-weight 2 sensitivity without a second run
			m.put("threatScoreK2", m.get("threatSpread") + 2.0 * m.get("threatKillCount"));
			m.put("humanScoreK2", m.get("humanSpread") + 2.0 * m.get("humanKillCount"));
		}
		measures(r);
	}

	/**
	 * docs/war-sim.md 7, the whole outcome, as it stands at each month: turnover (worlds changing state a year),
	 * swings (sign changes of the yearly momentum, the swarm's ground gained less the humans'), reversals (the same
	 * per half-year, net of the swarm's peaceful foundings), deadYears (the longest stretch since the first
	 * mobilisation with no hive killed, no world lost and no base destroyed) and decided (1 the humans wiped
	 * out, -1 the swarm, 0 neither) with decidedMonth.
	 */
	static void measures(Sim.Result r) {
		Map<String, Double> first = r.months.get(0);
		int swings = 0, lastSign = 0, reversals = 0, lastHalf = 0, quietSince = -1, decided = 0, decidedMonth = -1, mob = -1;
		double dead = 0;
		boolean hadHives = false, hadWorlds = false;
		for (int i = 0; i < r.months.size(); i++) {
			Map<String, Double> m = r.months.get(i);
			// the first mobilisation (a mid-war start: month 0), the destruction rates' and deadYears' clock
			if (mob < 0 && val(m, "mobilised") > 0) mob = i;
			if (i > 0) {
				Map<String, Double> was = r.months.get(i - 1);
				if (val(m, "hivesKilled") + val(m, "worldsLost") + val(m, "basesDestroyed")
						> val(was, "hivesKilled") + val(was, "worldsLost") + val(was, "basesDestroyed")) quietSince = i;
				// the quiet opening is not a stalemate: the clock starts at the first mobilisation
				if (quietSince < 0 && val(m, "mobilised") > 0) quietSince = val(was, "mobilised") > 0 ? i - 1 : i;
				if (quietSince >= 0) dead = Math.max(dead, (i - quietSince) / 12.0);
				if (i % 6 == 0) {
					// reversals: per half-year, on ground taken from or lost to the enemy alone (no peaceful foundings)
					Map<String, Double> y = r.months.get(i - 6);
					double mom = swarmTaken(m) - swarmTaken(y) - (humanGround(m) - humanGround(y));
					int sign = mom > 0 ? 1 : mom < 0 ? -1 : 0;
					if (sign != 0) {
						if (lastHalf != 0 && sign != lastHalf) reversals++;
						lastHalf = sign;
					}
				}
				if (i % 12 == 0) {
					Map<String, Double> y = r.months.get(i - 12);
					double mom = swarmGround(m) - swarmGround(y) - (humanGround(m) - humanGround(y));
					int sign = mom > 0 ? 1 : mom < 0 ? -1 : 0;
					// a year of zero momentum is no change of side
					if (sign != 0) {
						if (lastSign != 0 && sign != lastSign) swings++;
						lastSign = sign;
					}
				}
			}
			double hives = val(m, "hives"), worlds = val(m, "worlds") + val(m, "basesHeld");
			if (decided == 0 && hadHives && hives == 0) { decided = -1; decidedMonth = i; }
			if (decided == 0 && hadWorlds && worlds == 0) { decided = 1; decidedMonth = i; }
			hadHives |= hives > 0;
			hadWorlds |= worlds > 0;
			double changed = swarmGround(m) - swarmGround(first) + humanGround(m) - humanGround(first);
			m.put("turnover", i == 0 ? 0 : changed / (i / 12.0));
			// destruction a year since the first mobilisation: worlds and bases the swarm destroyed, hives the humans killed
			double years = mob < 0 || i <= mob ? 0 : (i - mob) / 12.0;
			Map<String, Double> m0 = mob < 0 ? m : r.months.get(mob);
			double worldsRate = years <= 0 ? 0 : (val(m, "worldsLost") - val(m0, "worldsLost")) / years;
			double basesRate = years <= 0 ? 0 : (val(m, "basesDestroyed") - val(m0, "basesDestroyed")) / years;
			double hivesRate = years <= 0 ? 0 : (val(m, "hivesKilled") - val(m0, "hivesKilled")) / years;
			m.put("threatKills", worldsRate + basesRate);
			m.put("threatKills.worlds", worldsRate);
			m.put("threatKills.bases", basesRate);
			m.put("humanKills", hivesRate);
			m.put("mutual", Math.min(worldsRate + basesRate, hivesRate));
			m.put("swings", (double) swings);
			m.put("reversals", (double) reversals);
			m.put("deadYears", dead);
			m.put("decided", (double) decided);
			m.put("decidedMonth", (double) decidedMonth);
		}
	}

	/** Ground the swarm gained: hives founded, human worlds and forward bases destroyed. */
	static double swarmGround(Map<String, Double> m) { return val(m, "hivesFounded") + val(m, "worldsLost") + val(m, "basesDestroyed"); }

	/** Ground the swarm took from the humans: worlds and forward bases destroyed. */
	static double swarmTaken(Map<String, Double> m) { return val(m, "worldsLost") + val(m, "basesDestroyed"); }

	/** Ground the humans gained: hives killed, forward bases founded. */
	static double humanGround(Map<String, Double> m) { return val(m, "hivesKilled") + val(m, "basesFounded"); }

	/** The run's class, an index into CLASSES: the first that fits. */
	static int outcome(Sim.Result r) {
		Map<String, Double> m = r.last();
		if (val(m, "decided") > 0) return 0;
		if (val(m, "decided") < 0) return 1;
		double t = val(m, "threatKills"), h = val(m, "humanKills");
		if (Math.min(t, h) >= MUTUAL_PER_YEAR) return 2;
		if ((t >= MUTUAL_PER_YEAR && h < QUIET_PER_YEAR) || (h >= MUTUAL_PER_YEAR && t < QUIET_PER_YEAR)) return 3;
		if (t < QUIET_PER_YEAR && h < QUIET_PER_YEAR) return 4;
		return 5;
	}

	static String decidedText(Sim.Result r) {
		return val(r.last(), "decided") != 0 ? " in month " + (int) val(r.last(), "decidedMonth") : "";
	}

	/** The share of the seeds ending in each class, and when the decided ones were decided. */
	static String classes(List<Sim.Result> runs) {
		int[] n = new int[CLASSES.length];
		List<Double> when = new ArrayList<Double>();
		for (Sim.Result r : runs) {
			n[outcome(r)]++;
			if (val(r.last(), "decided") != 0) when.add(val(r.last(), "decidedMonth"));
		}
		StringBuilder b = new StringBuilder("outcomes:");
		for (int i = 0; i < n.length; i++) b.append(i > 0 ? "," : "").append(" ").append(CLASSES[i]).append(" ").append(Math.round(100.0 * n[i] / runs.size())).append("%");
		if (!when.isEmpty()) {
			java.util.Collections.sort(when);
			b.append("; decided in month ").append(fmt(when.get(when.size() / 2))).append(" (median of ").append(when.size()).append(")");
		}
		return b.toString();
	}

	static double pct(List<Sim.Result> runs, int month, String col, double q) {
		double[] v = runs.stream().mapToDouble(r -> val(r.at(month), col)).sorted().toArray();
		if (v.length == 0) return 0;
		double pos = q * (v.length - 1);
		int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
		return v[lo] + (v[hi] - v[lo]) * (pos - lo);
	}

	static double val(Map<String, Double> row, String col) {
		Double v = row.get(col);
		return v == null ? 0d : v;
	}

	static String fmt(double v) {
		double a = Math.abs(v);
		return a >= 100000 ? String.format("%.0fk", v / 1000) : a >= 100 || v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.1f", v);
	}

	static void table(String title, int months, List<Sim.Result> runs) {
		List<Integer> at = new ArrayList<Integer>();
		for (int c : CHECKPOINTS) if (c < months) at.add(c);
		at.add(months);
		System.out.println(title);
		StringBuilder head = new StringBuilder(String.format("%-16s", "month"));
		for (int m : at) head.append(String.format("%22d", m));
		System.out.println(head);
		for (String col : SHOWN) {
			StringBuilder line = new StringBuilder(String.format("%-16s", col));
			for (int m : at) {
				String cell = fmt(pct(runs, m, col, 0.5));
				if (runs.size() > 1) cell += " [" + fmt(pct(runs, m, col, 0.1)) + " - " + fmt(pct(runs, m, col, 0.9)) + "]";
				line.append(String.format("%22s", cell));
			}
			System.out.println(line);
		}
	}

	/** Same seeds under two knob sets: medians, spreads, and how often B beat A seed for seed. */
	static void compare(String a, List<Sim.Result> ra, String b, List<Sim.Result> rb) {
		int end = ra.get(0).months.size() - 1;
		System.out.println("A: " + (a.isEmpty() ? "(settings.json)" : a));
		System.out.println("B: " + (b.isEmpty() ? "(settings.json)" : b));
		System.out.println(String.format("%-16s%24s%24s%10s%8s", "at month " + end, "A median [p10 - p90]", "B median [p10 - p90]", "B > A", "clear?"));
		for (String col : SHOWN) {
			double ma = pct(ra, end, col, 0.5), mb = pct(rb, end, col, 0.5);
			int wins = 0, ties = 0;
			for (int i = 0; i < ra.size(); i++) {
				double d = val(rb.get(i).at(end), col) - val(ra.get(i).at(end), col);
				if (d > 0) wins++; else if (d == 0) ties++;
			}
			int n = ra.size() - ties;
			// clear: the medians sit outside each other's middle 80%, and the seeds agree 3 to 1
			boolean clear = (mb > pct(ra, end, col, 0.9) || mb < pct(ra, end, col, 0.1))
					&& (ma > pct(rb, end, col, 0.9) || ma < pct(rb, end, col, 0.1))
					&& n > 0 && (wins >= 0.75 * n || wins <= 0.25 * n);
			System.out.println(String.format("%-16s%24s%24s%10s%8s", col,
					fmt(ma) + " [" + fmt(pct(ra, end, col, 0.1)) + " - " + fmt(pct(ra, end, col, 0.9)) + "]",
					fmt(mb) + " [" + fmt(pct(rb, end, col, 0.1)) + " - " + fmt(pct(rb, end, col, 0.9)) + "]",
					n == 0 ? "-" : Math.round(100.0 * wins / n) + "%", clear ? "yes" : ""));
		}
		System.out.println("A " + classes(ra));
		System.out.println("B " + classes(rb));
	}

	/**
	 * A real run's monthly dumps beside the simulator started from the first of them: the
	 * validation gates of docs/war-sim.md 6. "in" marks the real figure inside p10-p90.
	 */
	/**
	 * Two real runs of one recipe against the simulator, each from its own start (-dumps A -dumps2 B): per row and
	 * month they share, whether p10-p90 covers both reals, and whether both fall on the same side of the median
	 * (a bias) or one on each (spread).
	 */
	static void both(String nameA, Map<String, double[]> a, String nameB, Map<String, double[]> b) {
		System.out.println("two real runs, " + nameA + " and " + nameB + ": realA | realB | sim median [p10 - p90] from A's start (B is judged against the runs from its own)");
		int cells = 0, covered = 0, low = 0, high = 0, split = 0;
		Map<String, int[]> rows = new java.util.LinkedHashMap<String, int[]>(); // {cells, both covered, both above, both below}
		for (Map.Entry<String, double[]> e : a.entrySet()) {
			double[] x = e.getValue(), y = b.get(e.getKey());
			if (y == null) continue;
			String row = e.getKey().substring(0, e.getKey().indexOf('@'));
			int[] r = rows.get(row);
			if (r == null) rows.put(row, r = new int[4]);
			boolean cov = x[0] >= x[1] && x[0] <= x[3] && y[0] >= y[1] && y[0] <= y[3];
			// a real above its simulator median on both runs: the simulator reads low
			boolean above = x[0] > x[2] && y[0] > y[2], below = x[0] < x[2] && y[0] < y[2];
			cells++;
			r[0]++;
			if (cov) { covered++; r[1]++; }
			if (above) { low++; r[2]++; } else if (below) { high++; r[3]++; } else split++;
			System.out.println(String.format("  %-18s %12s | %12s | %12s [%s - %s]  %s%s", e.getKey().replace('@', ' '), fmt(x[0]), fmt(y[0]),
					fmt(x[2]), fmt(x[1]), fmt(x[3]), cov ? "both in" : "NOT both", above ? ", sim low on both" : below ? ", sim high on both" : ""));
		}
		System.out.println("by row: cells, both covered, simulator low on both, simulator high on both");
		for (Map.Entry<String, int[]> e : rows.entrySet()) {
			int[] r = e.getValue();
			System.out.println(String.format("  %-18s %2d %2d %2d %2d%s", e.getKey(), r[0], r[1], r[2], r[3],
					r[2] == r[0] ? "  BIAS low" : r[3] == r[0] ? "  BIAS high" : ""));
		}
		System.out.println(covered + " of " + cells + " cells cover both real runs; simulator low on both in " + low
				+ ", high on both in " + high + ", between or level in " + split);
	}

	static void check(Path dir, Knobs knobs, int seeds, float killWeight, float sizeExponent, Path log, Map<String, double[]> out) throws IOException {
		List<Path> files = dumpFiles(dir);
		if (files.size() < 2) throw new IllegalStateException("need at least two dumps in " + dir);
		Path map = mapFile(dir);
		Start start = new Start(map, files.get(0));
		List<Map<String, Double>> real = new ArrayList<Map<String, Double>>();
		for (Path f : files) {
			State s = new State();
			s.knobs = knobs;
			new Start(map, f).fill(s);
			// a run dumped from before the landing: its home system and how many colonies its opening chain landed are
			// read off the first dump that has hives (round 21: tr1a opened in a three-planet system, the simulator in
			// one of its own choosing with the five-world chain), and threatinc_homeWorlds set to what landed
			// -set warsim_checkOwnHome=true: the simulator picks its own home for each seed, as check did before round 21
			if (real.size() > 0 && start.ogSystem == null && !s.liveHives().isEmpty() && val(real.get(0), "hives") == 0
					&& !knobs.b("warsim_checkOwnHome", false)) {
				StarSys home = s.liveHives().get(0).sys;
				int landed = s.hivesIn(home).size();
				start.ogSystem = home.id;
				knobs.set("threatinc_homeWorlds=" + landed);
				System.out.println("home system " + home.name + ", opening chain of " + landed);
			}
			real.add(Sim.row(s));
		}
		State first = new State();
		start.fill(first);
		int months = 0;
		List<int[]> at = new ArrayList<int[]>(); // {dump index, month}
		for (int i = 0; i < files.size(); i++) {
			State s = new State();
			new Start(map, files.get(i)).fill(s);
			int m = Math.round((s.startDay - first.startDay) / 30f);
			months = Math.max(months, m);
			if (m > 0 && (m % 12 == 0 || i == files.size() - 1)) at.add(new int[] { i, m });
		}
		List<Sim.Result> runs = runs(start, knobs, seeds, months, killWeight, sizeExponent);
		String[] cols = { "hives", "hiveSize", "meanHiveSize", "garrisonFP", "bank", "swarmFuel", "swarmFuelPerMonth", "fuelPlants", "forges", "swarmSupplies", "worlds", "basesHeld", "mobilised", "humanMarines", "strategy.HOLD", "strategy.STARVE", "strategy.ROLLBACK" };
		System.out.println("real run " + dir.getFileName() + " against " + seeds + " seeds: real | sim median [p10 - p90]");
		int in = 0, cells = 0;
		for (String col : cols) {
			System.out.println(col);
			for (int[] a : at) {
				double r = val(real.get(a[0]), col), lo = pct(runs, a[1], col, 0.1), hi = pct(runs, a[1], col, 0.9);
				boolean ok = r >= lo && r <= hi;
				if (out != null) out.put(col + "@" + a[1], new double[] { r, lo, pct(runs, a[1], col, 0.5), hi });
				cells++;
				if (ok) in++;
				System.out.println(String.format("  month %3d %12s | %12s [%s - %s] %s", a[1], fmt(r),
						fmt(pct(runs, a[1], col, 0.5)), fmt(lo), fmt(hi), ok ? "in" : "OUT"));
			}
		}
		// the run's dated log beside the dumps (simdump-<name> -> ti-<name>.txt), or -log: events counted by month
		if (log == null) {
			String n = dir.getFileName().toString();
			Path guess = dir.resolveSibling("ti-" + n.substring(n.indexOf('-') + 1) + ".txt");
			if (n.startsWith("simdump-") && Files.exists(guess)) log = guess;
		}
		if (log != null) {
			Map<String, int[]> events = events(log, first.startDay, months);
			System.out.println("events from " + log.getFileName() + ", cumulative: real | sim median [p10 - p90]");
			for (Map.Entry<String, int[]> e : events.entrySet()) {
				if (e.getKey().equals("factionsMobilised")) continue; // dates the destruction rates below; no simulator counter
				System.out.println(e.getKey());
				for (int[] a : at) {
					if (a[1] < 36) continue;
					double r = e.getValue()[a[1]], lo = pct(runs, a[1], e.getKey(), 0.1), hi = pct(runs, a[1], e.getKey(), 0.9);
					boolean ok = r >= lo && r <= hi;
					if (out != null) out.put(e.getKey() + "@" + a[1], new double[] { r, lo, pct(runs, a[1], e.getKey(), 0.5), hi });
					cells++;
					if (ok) in++;
					System.out.println(String.format("  month %3d %12s | %12s [%s - %s] %s", a[1], fmt(r),
							fmt(pct(runs, a[1], e.getKey(), 0.5)), fmt(lo), fmt(hi), ok ? "in" : "OUT"));
				}
			}
			// the real run's destruction a year since its first mobilisation (round 11), against the simulator's rates
			int[] mobs = events.get("factionsMobilised");
			int mob = -1;
			for (int i = 0; mobs != null && i <= months; i++) if (mobs[i] > 0) { mob = i; break; }
			if (mob >= 0 && months > mob) {
				double years = (months - mob) / 12.0;
				double worlds = (events.get("worldsLost")[months] - events.get("worldsLost")[mob]) / years;
				double bases = (events.get("basesDestroyed")[months] - events.get("basesDestroyed")[mob]) / years;
				double hives = (events.get("hivesKilled")[months] - events.get("hivesKilled")[mob]) / years;
				System.out.println("destruction a year since the first mobilisation (month " + mob + ", to month " + months
						+ "): real | sim median [p10 - p90]");
				String[][] rows = { { "threatKills", fmt(worlds + bases) }, { "threatKills.worlds", fmt(worlds) },
						{ "threatKills.bases", fmt(bases) }, { "humanKills", fmt(hives) }, { "mutual", fmt(Math.min(worlds + bases, hives)) } };
				for (String[] row : rows) {
					System.out.println(String.format("  %-20s %8s | %8s [%s - %s]", row[0], row[1], fmt(pct(runs, months, row[0], 0.5)),
							fmt(pct(runs, months, row[0], 0.1)), fmt(pct(runs, months, row[0], 0.9))));
				}
			}
		}
		System.out.println(in + " of " + cells + " real figures inside the simulator's p10-p90");
	}

	/** The log lines each event counter is read from (docs/war-sim.md 7), against the simulator's counter of that name. */
	static final String[][] EVENTS = {
			{ "strikesLaunched", "^Strike launched from " },
			// the posture's transfers between the swarm's own colonies (SwarmPosture.dispatch), each a one-way passage in fuel
			{ "reinforcementsSent", "^Posture: .+ sent \\d+ FP to " },
			{ "threatLandings", "^Front deployed at .* \\(threat\\): " },
			{ "beachheadsOverrun", "^Notice: Beachhead Overrun \\| The garrison of " },
			{ "worldsLost", "^Threat ground victory at " },
			{ "hivesFounded", "^Colony founded" },
			{ "hivesKilled", "^Colony eradicated: " },
			// a siege's draw carries no razing fuel; a draw that does is the STARVE play's saturation expedition
			// (counted as a siege until round 23: hw4's 41 "sieges" were 13 sieges and 29 saturations)
			{ "siegesSailed", "^Expedition draw at .*razing 0\\)" },
			{ "saturationsSailed", " STARVE: saturation expedition of \\d+ FP sails" },
			{ "squadronsArrived", "^Raid arrived: " },
			{ "siege.beaten", "^Daily siege of [^:]*: \\d+ d, .* fight days, beaten$" },
			{ "siege.called off", "^Daily siege of [^:]*: \\d+ d, .* fight days, called off$" },
			{ "siege.landed (ready)", "^Daily siege of [^:]*: \\d+ d, .* fight days, landed \\(ready\\)$" },
			{ "siege.landed (dry)", "^Daily siege of [^:]*: \\d+ d, .* fight days, landed \\(dry\\)$" },
			{ "plays.RECON", "^Play \\S+ RECON .*: start -> " },
			{ "plays.HAMMER", "^Play \\S+ HAMMER .*: start -> " },
			{ "plays.STARVE", "^Play \\S+ STARVE .*: start -> " },
			{ "plays.BOMBERS", "^Play \\S+ BOMBERS .*: start -> " },
			{ "phase.HAMMER.strike", "^Play \\S+ HAMMER .*: muster -> strike" },
			{ "playsEnded.success", "^Play \\S+: success \\(" },
			{ "playsEnded.failure", "^Play \\S+: failure \\(" },
			{ "playsEnded.neutral", "^Play \\S+: neutral \\(" },
			{ "siegesLanded", "^Front deployed at .* \\((?!threat)\\w+\\): " },
			{ "frontsOverrun", "^Notice: Beachhead Overrun \\| A hive counter-attack" },
			// the simulator's hunts are the bounty hunts and the HAMMER plays' forces alike (both HUNT parcels); the
			// game logs a play's force under its play (round 23: hw4's 28 "hunts" were 37 bounty hunts and 33 play forces)
			{ "huntsSailed", "^Hunting force from |^Play \\S+ force \\S+ goes in over " },
			{ "basesFounded", "^Frontline: \\w+ founded " },
			{ "basesDestroyed", "^Frontline: \\w+ dismantled .*\\(station destroyed" },
			{ "basesAbandoned", "^Frontline: \\w+ dismantled .*\\(no " },
			{ "factionsMobilised", "^War footing: .* mobilised, " },
			// the swarm's stance, in months: the monthly "Colony upkeep" line names it
			{ "monthsExpand", "^Colony upkeep: .*stance EXPAND" },
			{ "monthsPress", "^Colony upkeep: .*stance PRESS" },
			{ "monthsConsolidate", "^Colony upkeep: .*stance CONSOLIDATE" },
	};

	/** Cumulative event counts by month since `startDay`, from a log dated by its "Clock: day N war M" lines. */
	static Map<String, int[]> events(Path log, int startDay, int months) throws IOException {
		Map<String, int[]> out = new java.util.LinkedHashMap<String, int[]>();
		java.util.regex.Pattern[] pats = new java.util.regex.Pattern[EVENTS.length];
		for (int i = 0; i < EVENTS.length; i++) {
			out.put(EVENTS[i][0], new int[months + 2]);
			pats[i] = java.util.regex.Pattern.compile(EVENTS[i][1]);
		}
		int day = startDay;
		try (java.io.BufferedReader r = Files.newBufferedReader(log, java.nio.charset.StandardCharsets.ISO_8859_1)) {
			for (String line = r.readLine(); line != null; line = r.readLine()) {
				if (line.startsWith("Clock: day ")) {
					day = Integer.parseInt(line.substring(11, line.indexOf(' ', 11)));
					continue;
				}
				for (int i = 0; i < pats.length; i++) {
					if (!pats[i].matcher(line).find()) continue;
					// a row at month m is taken after m x 30 days: the event counts from the first month that has seen it
					int m = Math.max(0, (day - startDay + 29) / 30);
					int[] c = out.get(EVENTS[i][0]);
					for (int j = m; j < c.length; j++) c[j]++;
				}
			}
		}
		return out;
	}

	static void csv(Sim.Result r, Path out) throws IOException {
		try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
			List<String> cols = new ArrayList<String>(r.last().keySet());
			w.println(String.join(",", cols));
			for (Map<String, Double> m : r.months) {
				StringBuilder b = new StringBuilder();
				for (String c : cols) b.append(b.length() == 0 ? "" : ",").append(val(m, c));
				w.println(b);
			}
		}
		System.out.println("wrote " + out);
	}
}
