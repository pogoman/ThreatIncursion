package warsim;

/** A star system; coordinates in light years. */
public final class StarSys {
	public String id;
	public String name;
	public float x, y;
	/** Non-star planets, what a hive or a forward base can be founded on. */
	public int planets;

	public float ly(StarSys o) {
		if (o == null || o == this) return 0f;
		float dx = x - o.x, dy = y - o.y;
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	@Override public String toString() { return name; }
}
