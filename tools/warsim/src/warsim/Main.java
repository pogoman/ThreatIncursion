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
 * warsim check   -dumps folder [-seeds N]      the simulator beside a real run's monthly dumps
 * Common: -start folder (threatinc_simmap.json + a threatinc_simdump_d*.json; default tools/warsim/start),
 *         -settings file, -set key=value (repeatable), -killWeight x, -sizeExponent x.
 */
public final class Main {

	/** The columns the batch, compare and check tables print. */
	static final String[] SHOWN = { "hives", "hiveSize", "garrisonFP", "bank", "swarmFuel", "swarmSupplies",
			"worlds", "basesHeld", "basesFounded", "basesDestroyed", "hivesFounded", "hiveLevels", "hivesKilled",
			"worldsLost", "siegesSailed", "siegesLanded", "strikesLaunched", "threatScore", "humanScore" };
	static final int[] CHECKPOINTS = { 12, 24, 36, 48, 72, 97 };

	public static void main(String[] args) throws Exception {
		Locale.setDefault(Locale.ROOT);
		if (args.length == 0) { System.out.println("usage: warsim run|batch|compare|check ... (see docs/war-sim.md)"); return; }
		Path root = repoRoot();
		Path settings = root.resolve("data/config/settings.json");
		Path startDir = root.resolve("tools/warsim/start");
		Path dumps = null, out = null;
		long seed = 1;
		int seeds = 100, months = 100;
		float killWeight = 0f, sizeExponent = 1f;
		boolean verbose = false;
		List<String> sets = new ArrayList<String>();
		String a = "", b = "";
		for (int i = 1; i < args.length; i++) {
			String o = args[i];
			if (o.equals("-v")) verbose = true;
			else if (o.equals("-seed")) seed = Long.parseLong(args[++i]);
			else if (o.equals("-seeds")) seeds = Integer.parseInt(args[++i]);
			else if (o.equals("-months")) months = Integer.parseInt(args[++i]);
			else if (o.equals("-out")) out = Paths.get(args[++i]);
			else if (o.equals("-start")) startDir = Paths.get(args[++i]);
			else if (o.equals("-settings")) settings = Paths.get(args[++i]);
			else if (o.equals("-dumps")) dumps = Paths.get(args[++i]);
			else if (o.equals("-set")) sets.add(args[++i]);
			else if (o.equals("-a")) a = args[++i];
			else if (o.equals("-b")) b = args[++i];
			else if (o.equals("-killWeight")) killWeight = Float.parseFloat(args[++i]);
			else if (o.equals("-sizeExponent")) sizeExponent = Float.parseFloat(args[++i]);
			else throw new IllegalArgumentException("unknown option " + o);
		}
		Knobs knobs = Knobs.load(settings);
		for (String s : sets) knobs.set(s);

		String cmd = args[0];
		if (cmd.equals("check")) {
			if (dumps == null) throw new IllegalArgumentException("check needs -dumps <folder>");
			check(dumps, knobs, seeds, killWeight, sizeExponent);
			return;
		}
		Start start = start(startDir);
		if (cmd.equals("run")) {
			Sim.Result r = Sim.run(start, knobs, seed, months, sizeExponent, verbose);
			score(r, killWeight);
			if (out != null) csv(r, out);
			table("seed " + seed, months, Arrays.asList(r));
		} else if (cmd.equals("batch")) {
			table(seeds + " seeds: median [p10 - p90]", months, runs(start, knobs, seeds, months, killWeight, sizeExponent));
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
			m.put("threatScore", m.get("threatSpread") + killWeight * m.get("threatKills"));
			m.put("humanScore", m.get("humanSpread") + killWeight * m.get("humanKills"));
		}
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
	}

	/**
	 * A real run's monthly dumps beside the simulator started from the first of them: the
	 * validation gates of docs/war-sim.md 6. "in" marks the real figure inside p10-p90.
	 */
	static void check(Path dir, Knobs knobs, int seeds, float killWeight, float sizeExponent) throws IOException {
		List<Path> files = dumpFiles(dir);
		if (files.size() < 2) throw new IllegalStateException("need at least two dumps in " + dir);
		Path map = mapFile(dir);
		Start start = new Start(map, files.get(0));
		List<Map<String, Double>> real = new ArrayList<Map<String, Double>>();
		for (Path f : files) {
			State s = new State();
			s.knobs = knobs;
			new Start(map, f).fill(s);
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
		String[] cols = { "hives", "hiveSize", "garrisonFP", "bank", "swarmFuel", "swarmSupplies", "worlds", "basesHeld" };
		System.out.println("real run " + dir.getFileName() + " against " + seeds + " seeds: real | sim median [p10 - p90]");
		int in = 0, cells = 0;
		for (String col : cols) {
			System.out.println(col);
			for (int[] a : at) {
				double r = val(real.get(a[0]), col), lo = pct(runs, a[1], col, 0.1), hi = pct(runs, a[1], col, 0.9);
				boolean ok = r >= lo && r <= hi;
				cells++;
				if (ok) in++;
				System.out.println(String.format("  month %3d %12s | %12s [%s - %s] %s", a[1], fmt(r),
						fmt(pct(runs, a[1], col, 0.5)), fmt(lo), fmt(hi), ok ? "in" : "OUT"));
			}
		}
		System.out.println(in + " of " + cells + " real figures inside the simulator's p10-p90");
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
