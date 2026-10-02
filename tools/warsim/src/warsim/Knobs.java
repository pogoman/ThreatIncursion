package warsim;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mod's knobs, read from data/config/settings.json by the same keys the mod uses
 * (ThreatIncConfig), with overrides from the command line. A missing key throws, as
 * ThreatIncConfig.f() does, so a renamed knob is noticed.
 */
public final class Knobs {

	private final Map<String, Object> values = new LinkedHashMap<String, Object>();

	public static Knobs load(Path settingsJson) throws IOException {
		Knobs k = new Knobs();
		String text = new String(Files.readAllBytes(settingsJson), StandardCharsets.UTF_8);
		for (Map.Entry<String, Object> e : Json.obj(Json.parse(text)).entrySet()) k.values.put(e.getKey(), e.getValue());
		return k;
	}

	public Knobs copy() {
		Knobs k = new Knobs();
		k.values.putAll(values);
		return k;
	}

	/** key=value; the value is parsed as JSON (a number, true/false, a quoted string or an object). */
	public void set(String assignment) {
		int eq = assignment.indexOf('=');
		if (eq < 0) throw new IllegalArgumentException("expected key=value: " + assignment);
		String key = assignment.substring(0, eq).trim();
		if (!values.containsKey(key)) throw new IllegalArgumentException("no such knob in settings.json: " + key);
		values.put(key, Json.parse(assignment.substring(eq + 1).trim()));
	}

	public boolean has(String key) { return values.containsKey(key); }

	public float f(String key) {
		Object v = values.get(key);
		if (!(v instanceof Number)) throw new IllegalArgumentException("knob missing or not a number: " + key);
		return ((Number) v).floatValue();
	}

	public int i(String key) { return (int) f(key); }

	public boolean b(String key, boolean def) {
		Object v = values.get(key);
		return v instanceof Boolean ? (Boolean) v : def;
	}

	public String s(String key, String def) {
		Object v = values.get(key);
		return v == null ? def : String.valueOf(v);
	}

	/** A knob holding an object or a list, parsed. */
	public Object raw(String key) { return values.get(key); }
}
