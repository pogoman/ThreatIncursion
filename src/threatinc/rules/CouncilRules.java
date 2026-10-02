package threatinc.rules;

import java.util.Random;

/**
 * The war council's arithmetic (ThreatWarCouncil; docs/war-council.md 3, 6, 7), shared by the
 * mod and the offline simulator. Pure: knob values are arguments. Lifted for the council job;
 * the simulator's planner mode does not call it.
 */
public final class CouncilRules {
	private CouncilRules() {}

	public static final int HOLD = 0, STARVE = 1, ROLLBACK = 2, DECAPITATE = 3;
	public static final int OUTMATCHED = 0, EVEN = 1, AHEAD = 2;

	/** The picture's ratio: our weight (allies at half) over the known swarm's. */
	public static float ratio(float ourWeight, float allyWeight, float swarmWeight) {
		return (ourWeight + 0.5f * allyWeight) / Math.max(1f, swarmWeight);
	}

	private static float edge(int band, float evenRatio, float aheadRatio) {
		return band == AHEAD ? aheadRatio : evenRatio;
	}

	/** The band for the ratio: it moves only once the ratio is past an edge by the hysteresis. */
	public static int band(int was, float ratio, float evenRatio, float aheadRatio, float hysteresis) {
		int raw = ratio >= edge(AHEAD, evenRatio, aheadRatio) ? AHEAD
				: ratio >= edge(EVEN, evenRatio, aheadRatio) ? EVEN : OUTMATCHED;
		if (was < OUTMATCHED || was > AHEAD) return raw;
		float h = Math.max(0f, hysteresis);
		int b = was;
		while (b < AHEAD && ratio >= edge(b + 1, evenRatio, aheadRatio) * (1f + h)) b++;
		while (b > OUTMATCHED && ratio < edge(b, evenRatio, aheadRatio) * (1f - h)) b--;
		return b;
	}

	/**
	 * Each strategy's score from the picture, {hold, starve, rollback, decapitate}, before the
	 * personality and learned weights. any / frontier / core / rich: a cluster in reach, one that
	 * is a frontier or small, a core, a core or a producer.
	 */
	public static float[] scores(boolean any, boolean frontier, boolean core, boolean rich, boolean pressed,
			boolean relief, int band, boolean hasPartners) {
		float hold = 0.5f + Math.max(pressed ? 2f : 0f, band == OUTMATCHED ? 2f : 0f) + (any ? 0f : 4f)
				+ (relief ? 1f : 0f);
		float starve = !any ? 0f : (band == OUTMATCHED ? 2.5f : band == EVEN ? 2f : 1.5f) * (rich ? 1.3f : 1f);
		float rollback = !frontier ? 0f : band == OUTMATCHED ? 0.3f : band == EVEN ? 1.5f : 2f;
		float decap = !core ? 0f : band == AHEAD ? 2f * (hasPartners ? 1.5f : 1f) : band == EVEN ? 0.3f : 0f;
		if (pressed) {
			if (band != OUTMATCHED) starve *= 0.5f;
			rollback *= 0.5f;
			decap *= 0.5f;
		}
		return new float[] { hold, starve, rollback, decap };
	}

	/** A cluster's weight in the draw of a strategy's focus. */
	public static float focusWeight(int strategy, float ly, float weight, boolean production, boolean core) {
		float near = 1f / (1f + ly / 10f);
		if (strategy == HOLD) return near;
		if (strategy == STARVE) return (weight + (production ? 6f : 0f) + (core ? 4f : 0f)) * near;
		if (strategy == ROLLBACK) return 10f / (1f + weight) * near;
		return weight * near;
	}

	/** An index drawn in proportion to weight^(1/temperature); temperature 0 picks the best. -1 when every weight is 0. */
	public static int draw(float[] weights, float temperature, Random random) {
		float t = Math.max(0f, temperature);
		int best = -1;
		for (int i = 0; i < weights.length; i++) {
			if (weights[i] > 0f && (best < 0 || weights[i] > weights[best])) best = i;
		}
		if (best < 0 || t < 0.01f) return best;
		double[] w = new double[weights.length];
		double sum = 0d;
		for (int i = 0; i < weights.length; i++) {
			w[i] = weights[i] > 0f ? Math.pow(weights[i] / weights[best], 1d / t) : 0d;
			sum += w[i];
		}
		if (sum <= 0d) return best;
		double r = (random != null ? random.nextDouble() : Math.random()) * sum;
		for (int i = 0; i < w.length; i++) {
			r -= w[i];
			if (r <= 0d && w[i] > 0d) return i;
		}
		return best;
	}

	/** 1 +- jitter. */
	public static float jitter(float jitter, Random random) {
		float j = Math.max(0f, Math.min(0.9f, jitter));
		float r = random != null ? random.nextFloat() : (float) Math.random();
		return 1f + (2f * r - 1f) * j;
	}

	/** A play's verdict on a learned weight: x1.25 for a success, x0.8 for a failure, within [0.25, 4]. */
	public static float learn(float weight, boolean success) {
		float w = weight * (success ? 1.25f : 0.8f);
		return Math.max(0.25f, Math.min(4f, w));
	}

	/** A cluster's scale from its weight: 0 small (under 8), 1 medium (under 20), 2 large. */
	public static int scale(float weight) {
		return weight < 8f ? 0 : weight < 20f ? 1 : 2;
	}
}
