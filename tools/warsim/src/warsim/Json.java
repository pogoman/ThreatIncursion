package warsim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader: objects are Maps, arrays Lists, numbers Doubles. Tolerates # comments and trailing commas. */
public final class Json {

	private final String s;
	private int p;

	private Json(String s) { this.s = s; }

	public static Object parse(String text) {
		Json j = new Json(text);
		Object v = j.value();
		return v;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>(); }

	@SuppressWarnings("unchecked")
	public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<Object>(); }

	public static float num(Object o, float def) { return o instanceof Number ? ((Number) o).floatValue() : def; }

	public static String str(Object o, String def) { return o == null ? def : String.valueOf(o); }

	public static boolean bool(Object o, boolean def) { return o instanceof Boolean ? (Boolean) o : def; }

	private void ws() {
		while (p < s.length()) {
			char c = s.charAt(p);
			if (c == '#') { while (p < s.length() && s.charAt(p) != '\n') p++; }
			else if (Character.isWhitespace(c)) p++;
			else break;
		}
	}

	private Object value() {
		ws();
		if (p >= s.length()) throw new IllegalStateException("unexpected end of JSON");
		char c = s.charAt(p);
		if (c == '{') {
			p++;
			Map<String, Object> m = new LinkedHashMap<String, Object>();
			while (true) {
				ws();
				if (s.charAt(p) == '}') { p++; return m; }
				if (s.charAt(p) == ',') { p++; continue; }
				String k = string();
				ws();
				if (s.charAt(p) != ':') throw new IllegalStateException("expected : at " + p);
				p++;
				m.put(k, value());
			}
		}
		if (c == '[') {
			p++;
			List<Object> l = new ArrayList<Object>();
			while (true) {
				ws();
				if (s.charAt(p) == ']') { p++; return l; }
				if (s.charAt(p) == ',') { p++; continue; }
				l.add(value());
			}
		}
		if (c == '"') return string();
		int q = p;
		while (q < s.length() && ",}]#\n\r\t ".indexOf(s.charAt(q)) < 0) q++;
		String w = s.substring(p, q);
		p = q;
		if (w.equals("true")) return Boolean.TRUE;
		if (w.equals("false")) return Boolean.FALSE;
		if (w.equals("null")) return null;
		try {
			return Double.valueOf(w.endsWith("f") ? w.substring(0, w.length() - 1) : w);
		} catch (NumberFormatException e) {
			return w;
		}
	}

	private String string() {
		ws();
		if (s.charAt(p) != '"') {
			int q = p;
			while (q < s.length() && ":,}] \t\r\n".indexOf(s.charAt(q)) < 0) q++;
			String w = s.substring(p, q);
			p = q;
			return w;
		}
		p++;
		StringBuilder b = new StringBuilder();
		while (s.charAt(p) != '"') {
			char c = s.charAt(p++);
			if (c == '\\') {
				char e = s.charAt(p++);
				if (e == 'n') b.append('\n');
				else if (e == 't') b.append('\t');
				else if (e == 'u') { b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; }
				else b.append(e);
			} else b.append(c);
		}
		p++;
		return b.toString();
	}
}
