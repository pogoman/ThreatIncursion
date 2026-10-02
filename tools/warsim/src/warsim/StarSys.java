package warsim;

/** A star system; coordinates in light years. */
public final class StarSys {
	public String id;
	public String name;
	public float x, y;
	/** Non-star planets, what a hive or a forward base can be founded on. */
	public int planets;
	/** This system's row in its State's distance table (Start.fill): the ly to every system by index, the same figure ly computes. */
	public int index = -1;
	public float[] dist;

	public float ly(StarSys o) {
		if (o == null || o == this) return 0f;
		if (dist != null && o.index >= 0 && o.index < dist.length) return dist[o.index];
		return compute(o);
	}

	float compute(StarSys o) {
		float dx = x - o.x, dy = y - o.y;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	@Override public String toString() { return name; }
}
