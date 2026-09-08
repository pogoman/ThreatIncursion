package threatinc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * ESCALATION - the swarm answers who hurts it (docs/design-theory.md 8.1).
 *
 * <p>Every faction that takes a stratum, eradicates a hive, or lands a raid
 * or bombardment on one earns GRUDGE points; the sum of all grudges is the
 * hive's ALARM. Both are visible on the war board and decay monthly. Two
 * levers, no hidden difficulty:
 *
 * <ul>
 * <li>TEMPO: Swarm Nexuses fabricate replacement garrisons faster by
 * {@link #tempoMult()} - and since strikes and expansion are mustered from
 * garrison surplus, that is the hive's whole military tempo.</li>
 * <li>TARGETING: a strike's target weight is multiplied by
 * {@link #targetMult(String)} for the grudged faction's worlds.</li>
 * </ul>
 *
 * Plus RETALIATION (IncursionManager.retaliate): a ground victory draws an
 * immediate strike at the winner from the nearest hive that can muster one.
 * Causal, instant, legible - the AI War lesson without the hidden number.
 */
public class ThreatAlarm {

	public static final String KEY_GRUDGES = "threatinc_grudges";

	@SuppressWarnings("unchecked")
	public static Map<String, Float> grudges() {
		Object val = Global.getSector().getPersistentData().get(KEY_GRUDGES);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Float>();
			Global.getSector().getPersistentData().put(KEY_GRUDGES, val);
		}
		return (Map<String, Float>) val;
	}

	public static boolean enabled() {
		return ThreatIncConfig.enabled() && ThreatIncConfig.alarmEnabled();
	}

	public static float grudge(String factionId) {
		if (factionId == null || !enabled()) return 0f;
		Float v = grudges().get(factionId);
		return v != null ? v : 0f;
	}

	/** The hive's alarm: every faction's grudge summed. */
	public static float alarm() {
		if (!enabled()) return 0f;
		float total = 0f;
		for (Float v : grudges().values()) {
			if (v != null) total += v;
		}
		return total;
	}

	public static void add(String factionId, float points, String reason) {
		if (!enabled() || factionId == null || points <= 0f) return;
		// the swarm holds no grudge against itself: a Threat front taking strata
		// on a human world is not a hive being hurt (the one choke point, so no
		// caller has to know whose front it is)
		if (Factions.THREAT.equals(factionId)) return;
		float g = grudge(factionId) + points;
		grudges().put(factionId, g);
		ThreatIncConfig.log("Alarm: +" + points + " " + factionId + " (" + reason + ") -> grudge "
				+ String.format("%.1f", g) + ", alarm " + String.format("%.1f", alarm()));
	}

	/** Fabrication-speed multiplier on every Swarm Nexus, 1 at no alarm, capped. */
	public static float tempoMult() {
		if (!enabled()) return 1f;
		float m = 1f + alarm() * ThreatIncConfig.alarmTempoMult();
		return Math.min(ThreatIncConfig.alarmTempoMax(), Math.max(1f, m));
	}

	/** Strike-target weight multiplier for a faction's worlds, 1 at no grudge. */
	public static float targetMult(String factionId) {
		if (!enabled()) return 1f;
		return 1f + grudge(factionId) * ThreatIncConfig.alarmTargetMult();
	}

	/** Monthly decay, on the slow tick: grudges fade unless renewed. */
	public static void decay() {
		if (grudges().isEmpty()) return;
		float keep = 1f - Math.max(0f, Math.min(1f, ThreatIncConfig.alarmDecayPer30()));
		for (String id : new ArrayList<String>(grudges().keySet())) {
			Float v = grudges().get(id);
			if (v == null) {
				grudges().remove(id);
				continue;
			}
			float next = v * keep;
			if (next < 0.05f) grudges().remove(id);
			else grudges().put(id, next);
		}
	}

	/** "Alarm 12 - fabrication x1.6" for the board header. */
	public static String headerLabel() {
		float a = alarm();
		if (a <= 0f) return "Alarm 0";
		return "Alarm " + (int) Math.ceil(a) + " - fabrication x"
				+ String.format("%.1f", tempoMult());
	}
}
