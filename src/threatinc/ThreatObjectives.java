package threatinc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Industries;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * TACTICAL OBJECTIVES (the user, 2026-10-30 15:00: "tactical objectives table sorting top producers by
 * priority"; 15:30: "get rid of tactical table and just new second last column on the threat colony
 * board, Notes, which indicates the objective if relevant"). The ledger's Notes column and its row
 * tooltip lines: what to hit and why, among the systems the player knows. Availability is a broadcast of the largest single source
 * (hive-economy.md), so the objective per resource is the largest known holder of its producer -
 * a mine, a refinery, a fuel plant - and the row says what breaks when it is taken offline: every
 * importer left to the next-largest known source, counted as starving (two or more units short, or
 * no source left) or holding (one short) by the Core's own rule (ThreatColonyManager.coreDemand).
 * Then the next-largest as the follow-up. A sat bomb takes the plant offline for
 * satBombDisruptDays at ThreatSatBomb.price for the player's fleet. The campaign's staging systems
 * (ThreatOffensive.savingStagings) head the list: that is where the strike against the player buds.
 * Figures the scouts hold come from the player's report (ThreatIntel: strata and size as last seen);
 * the hive's industries show as the colony screen shows them.
 */
public class ThreatObjectives {

	protected static class Row {
		String objective;
		MarketAPI world;
		ThreatWarBoard.Entry entry;
		float score;
		String effect;
		String why;
	}

	/**
	 * The ledger's Notes column (the user, 2026-10-30 15:30: "get rid of tactical table and just new second last
	 * column on the threat colony board, Notes, which indicates the objective if relevant"): system id -> its
	 * worlds' objectives, best first.
	 */
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

	/** The cell: the objectives, best first - "Strike staging, Top fuel plant"; "-" with none. */
	public static String noteText(List<Row> rows) {
		if (rows == null || rows.isEmpty()) return "-";
		StringBuilder b = new StringBuilder();
		for (Row r : rows) {
			if (b.length() > 0) b.append(", ");
			b.append(r.objective);
		}
		return b.toString();
	}

	/** True when a note names a strike staging: the cell takes the Threat's colour. */
	public static boolean staging(List<Row> rows) {
		if (rows == null) return false;
		for (Row r : rows) if (r.score >= 100f) return true;
		return false;
	}

	/** The row tooltip's lines, one objective each: the world, why, what breaks, the bomb's price for the player's fleet. */
	public static void addTooltip(TooltipMakerAPI tooltip, List<Row> rows, float pad) {
		if (rows == null || rows.isEmpty()) return;
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		float fp = player != null ? player.getFleetPoints() : 0f;
		Color h = Misc.getHighlightColor();
		for (Row r : rows) {
			tooltip.addPara(r.objective + ": %s. " + r.why + " " + r.effect + ".", pad, h, r.world.getName());
			if (ThreatSatBomb.enabled() && fp > 0f) {
				tooltip.addPara("A saturation bombardment by your fleet costs %s fuel here, razes %s strata and keeps the world's industries offline %s days.",
						2f, h, Misc.getWithDGS(Math.round(ThreatSatBomb.price(r.world, fp))),
						Misc.getWithDGS(Math.round(ThreatSatBomb.strata(r.world, fp))), "" + (int) ThreatSatBomb.disruptDays());
			}
		}
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

		// the stagings the offensive is saving for: where the strike buds
		Map<String, Float> saving = ThreatOffensive.savingStagings();
		for (Map.Entry<String, Float> s : saving.entrySet()) {
			MarketAPI m = ThreatIncData.resolveColonyMarket(s.getKey());
			if (m == null || !entryOf.containsKey(m.getId())) continue;
			float gathered = ThreatColonyManager.staged(m.getId()) + ThreatBudding.strikeInbound(m)
					+ ThreatColonyManager.ownedFleetFP(m, ThreatIncData.garrisonsFor(m.getId()));
			Row r = new Row();
			r.objective = "Strike staging";
			r.world = m;
			r.entry = entryOf.get(m.getId());
			r.score = 100f + s.getValue() / 1000f;
			r.effect = "A strike of " + Misc.getWithDGS(Math.round(s.getValue())) + " FP buds here";
			r.why = "The swarm's offensive stages its next strike from " + m.getName() + ": " + Misc.getWithDGS(Math.round(s.getValue()))
					+ " FP priced, " + Misc.getWithDGS(Math.round(gathered)) + " FP gathered. Taking the world offline pushes the strike back to the next staging.";
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
