package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * THE LEDGER'S NOTES (the user, 2026-10-30 15:00: "tactical objectives table sorting top producers by
 * priority"; 15:30: "just new second last column on the threat colony board, Notes, which indicates the
 * objective if relevant"; 16:00: "notes should be updated by what scout finds ... things of tactical value.
 * If a system is strong then we need to know what it contributes the most of, if a system is weak we want
 * to know how, so we can eliminate early"). Everything here reads the player's report of the system
 * (ThreatIntel: what a scout, an allied fleet or the player's own fleet last saw there), never the live
 * world. The notes, best first ({@link #lines}): "Scout lost" when a party sent there was lost since the
 * last look (ThreatIntel.lostDay); "Never scouted"; "Strike staging" where the report recorded swarms
 * massing for a strike; the system's objectives among the known producers ({@link #rows}: availability is
 * a broadcast of the largest single source, hive-economy.md, so the largest known holder of a fuel plant,
 * refinery or mine is "Top ..." and the next-largest "Next ...", with what breaks when it goes offline -
 * every known importer left to the next-largest known source, starving at two or more units short by
 * ThreatColonyManager.coreDemand, holding at one); the system's best producer ("Size 8 fuel production");
 * its strata mass as seen ("Low strata mass (8,000)" under LOW_MASS); "Isolated" with no other known hive
 * system within ISOLATED_LY. The Age column is days since the newest of the report and a loss
 * ({@link #ageText}). The row tooltip spells each out and prices a saturation bombardment for the
 * player's fleet (ThreatSatBomb).
 */
public class ThreatObjectives {

	/** Strata over a system's worlds under this read as a low mass (a size-5 world and change). */
	public static final float LOW_MASS = 10000f;
	/** No other known hive system within this many light-years: isolated. */
	public static final float ISOLATED_LY = 8f;

	protected static class Row {
		String objective;
		MarketAPI world;
		ThreatWarBoard.Entry entry;
		float score;
		String effect;
		String why;
	}

	/** System id -> its worlds' objectives, best first. */
	public static Map<String, List<Row>> bySystem(List<ThreatWarBoard.Entry> entries) {
		Map<String, List<Row>> result = new HashMap<String, List<Row>>();
		for (Row r : rows(entries)) {
			List<Row> list = result.get(r.entry.systemId);
			if (list == null) {
				list = new ArrayList<Row>();
				result.put(r.entry.systemId, list);
			}
			list.add(r);
		}
		return result;
	}

	/** The system's notes, best first, from the player's report of it; empty only when nothing at all is known. */
	public static List<String> lines(ThreatWarBoard.Entry e, List<Row> rows, List<ThreatWarBoard.Entry> all) {
		List<String> out = new ArrayList<String>();
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		if (lost >= 0f && lost > seenDay) out.add("Scout lost");
		if (e.seen == null) {
			if (out.isEmpty()) out.add("Never scouted");
			return out;
		}
		Set<String> covered = new HashSet<String>();
		if (rows != null) {
			for (Row r : rows) {
				if (r.score >= 100f) {
					out.add(r.objective);
					continue;
				}
				int size = sizeSeen(e, r.world);
				out.add(r.objective + (size > 0 ? " (size " + size + ")" : ""));
				covered.add(r.world.getId() + "|" + r.objective.substring(r.objective.indexOf(' ') + 1));
			}
		}
		String best = bestMade(e, covered);
		if (best != null) out.add(best);
		float mass = 0f;
		boolean any = false;
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			float st = s != null ? s.worldStrata(m.getId()) : -1f;
			if (st < 0f) continue;
			mass += st;
			any = true;
		}
		if (any) out.add(mass < LOW_MASS ? "Low strata mass (" + n(mass) + ")" : "Strata mass " + n(mass));
		if (isolated(e, all)) out.add("Isolated");
		return out;
	}

	/** The cell: the notes joined; "-" with none. */
	public static String noteText(List<String> lines) {
		if (lines == null || lines.isEmpty()) return "-";
		StringBuilder b = new StringBuilder();
		for (String l : lines) {
			if (b.length() > 0) b.append(", ");
			b.append(l);
		}
		return b.toString();
	}

	/** The cell's colour: a lost party in the loss colour, a strike staging in the Threat's, nothing known grey, else the highlight. */
	public static Color noteColor(List<String> lines, Color bright, Color h, Color gray, Color loss) {
		if (lines == null || lines.isEmpty() || "Never scouted".equals(lines.get(0))) return gray;
		if ("Scout lost".equals(lines.get(0))) return loss;
		if (lines.contains("Strike staging")) return bright;
		return h;
	}

	/** The Age cell: days since the newest of the player's report and a lost party, "today", "-" never. */
	public static String ageText(ThreatWarBoard.Entry e) {
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		float latest = Math.max(lost, seenDay);
		if (latest < 0f) return "-";
		int days = (int) Math.max(0f, ThreatPosture.today() - latest);
		return days < 1 ? "today" : days + " d";
	}

	/** The row tooltip's lines: each note, each objective's reasoning and the bomb's price for the player's fleet. */
	public static void addTooltip(TooltipMakerAPI tooltip, ThreatWarBoard.Entry e, List<Row> rows, List<ThreatWarBoard.Entry> all, float pad) {
		List<String> lines = lines(e, rows, all);
		Color h = Misc.getHighlightColor();
		if (!lines.isEmpty()) tooltip.addPara("Notes: %s.", pad, h, noteText(lines));
		float lost = ThreatIntel.lostDay(Factions.PLAYER, e.systemId);
		float seenDay = e.seen != null ? e.seen.day : -1f;
		if (lost >= 0f && lost > seenDay) {
			tooltip.addPara("A scouting party sent there was lost %s days ago; nothing newer is known.", 4f, h,
					"" + (int) Math.max(0f, ThreatPosture.today() - lost));
		}
		if (e.seen != null) tooltip.addPara("Last look " + ThreatIntel.when(e.seen) + ".", 4f);
		if (rows == null || rows.isEmpty()) return;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		float fp = player != null ? player.getFleetPoints() : 0f;
		for (Row r : rows) {
			tooltip.addPara(r.objective + ": %s. " + r.why + " " + r.effect + ".", 4f, h, r.world.getName());
			if (ThreatSatBomb.enabled() && fp > 0f) {
				tooltip.addPara("A saturation bombardment by your fleet costs %s fuel here, razes %s strata and keeps the world's industries offline %s days.",
						2f, h, Misc.getWithDGS(Math.round(ThreatSatBomb.price(r.world, fp))),
						Misc.getWithDGS(Math.round(ThreatSatBomb.strata(r.world, fp))), "" + (int) ThreatSatBomb.disruptDays());
			}
		}
	}

	/** The world's size as the report saw it, 0 unknown. */
	protected static int sizeSeen(ThreatWarBoard.Entry e, MarketAPI world) {
		ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, world);
		return s != null ? s.worldSize(world.getId()) : 0;
	}

	/** "Size 8 fuel production": the largest producer the report saw in the system (fuel before metals before ore at a size), unless an objective line already names it. */
	protected static String bestMade(ThreatWarBoard.Entry e, Set<String> covered) {
		String bestWorld = null, bestInd = null;
		int bestSize = 0, bestRank = 0;
		for (MarketAPI m : e.markets) {
			ThreatIntel.Report s = ThreatWarBoard.seenOver(e.seen, m);
			if (s == null) continue;
			int size = s.worldSize(m.getId());
			for (String ind : s.worldMade(m.getId())) {
				int rank = Industries.FUELPROD.equals(ind) ? 3 : Industries.REFINING.equals(ind) ? 2 : 1;
				if (size > bestSize || (size == bestSize && rank > bestRank)) {
					bestSize = size;
					bestRank = rank;
					bestWorld = m.getId();
					bestInd = ind;
				}
			}
		}
		if (bestInd == null) return null;
		String noun = Industries.FUELPROD.equals(bestInd) ? "fuel plant" : Industries.REFINING.equals(bestInd) ? "refinery" : "mine";
		if (covered.contains(bestWorld + "|" + noun)) return null;
		String what = Industries.FUELPROD.equals(bestInd) ? "fuel production" : Industries.REFINING.equals(bestInd) ? "refining" : "mining";
		return (bestSize > 0 ? "Size " + bestSize + " " : "") + what;
	}

	/** No other known hive system within ISOLATED_LY, among at least two others. */
	protected static boolean isolated(ThreatWarBoard.Entry e, List<ThreatWarBoard.Entry> all) {
		if (e.system == null || all == null) return false;
		int others = 0;
		for (ThreatWarBoard.Entry o : all) {
			if (o == e || !o.isColony() || o.system == null) continue;
			others++;
			if (Misc.getDistanceLY(e.system.getLocation(), o.system.getLocation()) <= ISOLATED_LY) return false;
		}
		return others >= 2;
	}

	protected static String n(float v) {
		return Misc.getWithDGS(Math.round(v));
	}

	/** The rows, best first: the campaign's stagings, then each resource's top producer and the next. */
	protected static List<Row> rows(List<ThreatWarBoard.Entry> entries) {
		List<Row> rows = new ArrayList<Row>();
		Map<String, ThreatWarBoard.Entry> entryOf = new HashMap<String, ThreatWarBoard.Entry>();
		List<MarketAPI> known = new ArrayList<MarketAPI>();
		for (ThreatWarBoard.Entry e : entries) {
			if (!e.isColony()) continue;
			for (MarketAPI m : e.markets) {
				if (m == null || m.getPrimaryEntity() == null) continue;
				known.add(m);
				entryOf.put(m.getId(), e);
			}
		}
		if (known.isEmpty()) return rows;

		// a strike massing, as the scouts saw it (ThreatIntel.Report.worldStaging) - never the offensive's live plan
		// (the user, 2026-10-30 15:45: "unless a scout has detected right. kinda cheating. fog of war")
		for (MarketAPI m : known) {
			ThreatWarBoard.Entry e = entryOf.get(m.getId());
			ThreatIntel.Report seen = ThreatWarBoard.seenOver(e.seen, m);
			float massing = seen != null ? seen.worldStaging(m.getId()) : 0f;
			if (massing <= 0f) continue;
			Row r = new Row();
			r.objective = "Strike staging";
			r.world = m;
			r.entry = e;
			r.score = 100f + massing / 1000f;
			r.effect = Misc.getWithDGS(Math.round(massing)) + " FP massing for a strike, seen " + ThreatIntel.when(seen);
			r.why = "Your scouts saw a strike gathering at " + m.getName() + ": swarms staged and flying in for it. Taking the world offline pushes the strike back to another staging.";
			rows.add(r);
		}

		String[] industries = { Industries.FUELPROD, Industries.REFINING, Industries.MINING };
		String[] nouns = { "fuel plant", "refinery", "mine" };
		String[] goods = { "fuel", "metals", "ore" };
		for (int i = 0; i < industries.length; i++) {
			final String industry = industries[i];
			List<MarketAPI> holders = new ArrayList<MarketAPI>();
			for (MarketAPI m : known) if (m.hasIndustry(industry)) holders.add(m);
			if (holders.isEmpty()) continue;
			final Map<String, ThreatWarBoard.Entry> eo = entryOf;
			Collections.sort(holders, new Comparator<MarketAPI>() {
				public int compare(MarketAPI a, MarketAPI b) {
					int c = b.getSize() - a.getSize();
					if (c != 0) return c;
					ThreatWarBoard.Entry ea = eo.get(a.getId()), eb = eo.get(b.getId());
					return Float.compare(ea != null ? ea.coreLY : 999f, eb != null ? eb.coreLY : 999f);
				}
			});
			for (int k = 0; k < Math.min(2, holders.size()); k++) {
				MarketAPI top = holders.get(k);
				MarketAPI next = k + 1 < holders.size() ? holders.get(k + 1) : null;
				int out = output(industry, top.getSize());
				int nextOut = next != null ? output(industry, next.getSize()) : 0;
				int starve = 0, hold = 0;
				for (MarketAPI m : known) {
					int demand = demand(industry, m);
					if (demand <= 0) continue;
					if (next == null) {
						starve++;
						continue;
					}
					int shortBy = demand - nextOut;
					if (shortBy >= 2) starve++;
					else if (shortBy == 1) hold++;
				}
				Row r = new Row();
				r.objective = (k == 0 ? "Top " : "Next ") + nouns[i];
				r.world = top;
				r.entry = entryOf.get(top.getId());
				r.score = starve * 3f + hold + (Industries.FUELPROD.equals(industry) ? 1f : 0f) - k * 0.5f;
				String unit = Industries.MINING.equals(industry) ? "refineries" : "hive worlds";
				if (next == null) {
					r.effect = "no other " + nouns[i] + " known: " + starve + " " + unit + " starve";
				} else {
					int drop = Math.max(0, out - nextOut);
					r.effect = goods[i] + " -" + drop + ": " + starve + " starve, " + hold + " hold";
				}
				r.why = (k == 0 ? "The largest " : "The next-largest ") + nouns[i] + " the hive is known to run, making " + out + " units of " + goods[i]
						+ " for every hive world that can reach it" + (next != null ? "; the next-largest known, " + next.getName() + " at size "
						+ next.getSize() + ", makes " + nextOut + "." : "; no other is known.")
						+ (Industries.MINING.equals(industry) ? " A refinery short of ore makes fewer metals for everyone." : " A world two or more units short starves a size at a time; one short holds.");
				rows.add(r);
			}
		}
		Collections.sort(rows, new Comparator<Row>() {
			public int compare(Row a, Row b) {
				return Float.compare(b.score, a.score);
			}
		});
		return rows;
	}

	/** Units the broadcast gives from a producer of this size: a refinery and a mine at the size, a fuel plant at size - 2. */
	protected static int output(String industry, int size) {
		if (Industries.FUELPROD.equals(industry)) return Math.max(0, size - 2);
		return Math.max(0, size);
	}

	/** Units the world demands of the producer's good: the Core's metals or fuel (coreDemand); a refinery's ore at its size. */
	protected static int demand(String industry, MarketAPI m) {
		if (Industries.FUELPROD.equals(industry)) return ThreatColonyManager.coreDemand(m, Commodities.FUEL);
		if (Industries.REFINING.equals(industry)) return ThreatColonyManager.coreDemand(m, Commodities.METALS);
		return m.hasIndustry(Industries.REFINING) ? m.getSize() : 0;
	}
}
