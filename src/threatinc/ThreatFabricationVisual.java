package threatinc;

import java.awt.Color;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.campaign.ids.Pings;
import com.fs.starfarer.api.util.Misc;

/**
 * FRAGMENTS GOING DOWN, on the campaign map (2026-09-08, the user's bonus ask:
 * some real-time sign that the fleet is feeding itself into the ground). A
 * short {@link EveryFrameScript} hung on the besieged planet, modelled on
 * vanilla's own {@code MarketCMD.BombardmentAnimation} - the same
 * {@link Misc#addHitGlow} particles it uses - but drawn the other way round,
 * and that is the whole point: a bombardment scatters glows ACROSS the disc,
 * while this one starts them out at {@code START_RADIUS} of the planet's radius
 * and drives them INWARD, so what the player sees is pieces of the fleet
 * falling to the surface rather than fire coming down on it. The state it marks
 * is precisely the one where the fleet has STOPPED bombarding
 * ({@link ThreatGroundFronts#defendFabricates}), so reusing the bombardment
 * burst here would have said the opposite of what is happening.
 *
 * <p>One instance per drop, and a drop is one whole hull broken up, so the
 * cadence on the map is the cadence of the mechanic - not a per-day heartbeat.
 * It runs on the entity, not on the mod's poll, so it animates smoothly at
 * frame rate while the siege itself ticks about twice a game-day.
 *
 * <p>Everything here is cosmetic and every call site guards it: a campaign
 * visual is never worth an exception in the siege tick.
 */
public class ThreatFabricationVisual implements EveryFrameScript {

	/** Where a fragment starts, as a multiple of the planet's radius. */
	public static final float START_RADIUS = 2.4f;
	/** Seconds a fragment takes to reach the surface. */
	public static final float FALL_SECONDS = 1.1f;
	/** Seconds between fragments. */
	public static final float SPACING = 0.09f;

	protected final SectorEntityToken target;
	protected final Color colour;
	protected final int count;
	protected int spawned;
	protected float sinceLast;

	public ThreatFabricationVisual(SectorEntityToken target, Color colour, int count) {
		this.target = target;
		this.colour = colour;
		this.count = Math.max(1, count);
	}

	public boolean isDone() {
		return spawned >= count;
	}

	public boolean runWhilePaused() {
		return false;
	}

	public void advance(float amount) {
		if (isDone() || target == null || target.getContainingLocation() == null) {
			spawned = count;
			return;
		}
		sinceLast += amount;
		if (sinceLast < SPACING) return;
		sinceLast = 0f;
		spawned++;
		try {
			float radius = Math.max(20f, target.getRadius());
			float angle = (float) Math.random() * 360f;
			Vector2f from = Misc.getUnitVectorAtDegreeAngle(angle);
			// out at START_RADIUS, falling in to the surface over FALL_SECONDS
			Vector2f loc = new Vector2f(target.getLocation().x + from.x * radius * START_RADIUS,
					target.getLocation().y + from.y * radius * START_RADIUS);
			float speed = radius * (START_RADIUS - 1f) / FALL_SECONDS;
			Vector2f vel = new Vector2f(-from.x * speed, -from.y * speed);
			// the planet is moving; the fragments fall with it, not behind it
			Vector2f drift = target.getVelocity();
			if (drift != null) Vector2f.add(vel, drift, vel);
			Misc.addHitGlow(target.getContainingLocation(), loc, vel,
					radius * 0.22f, colour);
		} catch (Throwable t) {
			spawned = count; // cosmetic only - never let it keep throwing
		}
	}

	/**
	 * Plays one drop over the world, for a player who is there to see it: the
	 * falling fragments, a floating label so the state is named and not just
	 * lit, and a danger ping to pull the eye to the planet. Silent, and free,
	 * for a player in another system - {@code addHitGlow} draws into a
	 * location nobody is rendering.
	 */
	public static void play(SectorEntityToken planet, Color colour, String label, int fragments) {
		if (planet == null || planet.getContainingLocation() == null) return;
		if (Global.getSector() == null) return;
		com.fs.starfarer.api.campaign.CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getContainingLocation() != planet.getContainingLocation()) return;
		try {
			planet.addScript(new ThreatFabricationVisual(planet, colour, fragments));
			planet.addFloatingText(label, colour, 1f);
			Global.getSector().addPing(planet, Pings.DANGER);
		} catch (Throwable t) {
			// cosmetic; the drop itself has already happened
		}
	}
}
