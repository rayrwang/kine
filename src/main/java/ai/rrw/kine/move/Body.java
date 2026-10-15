package ai.rrw.kine.move;

/**
 * The player's movement numbers at the time of the snapshot, read from its attributes and options so the copy uses
 * the live values (potions, armour and enchantments change several): its walking and sprinting speed (getSpeed()
 * with and without the sprint modifier), the sneaking speed, the jump strength and any Jump Boost, gravity, the step
 * height, the movement efficiency, the box's size standing and crouching, whether it can sprint (food), the
 * double-tap sprint window, and whether it wears a glider.
 */
public final class Body {
    public float walkSpeed = 0.1F, sprintSpeed = 0.13F;
    public float sneakSpeed = 0.3F;
    public float jumpStrength = 0.42F, jumpBoost = 0.0F;
    public double gravity = 0.08;
    public float stepHeight = 0.6F;
    public float movementEfficiency = 0.0F;
    public float width = 0.6F, height = 1.8F, crouchHeight = 1.5F;
    /** The swimming pose's box height and the eye heights of the three poses (EntityDimensions). */
    public float swimHeight = 0.6F, eyeHeight = 1.62F, crouchEyeHeight = 1.27F, swimEyeHeight = 0.4F;
    /** Attributes.WATER_MOVEMENT_EFFICIENCY (Depth Strider), and Dolphin's Grace. */
    public float waterMovementEfficiency = 0.0F;
    public boolean dolphinsGrace = false;
    public boolean canSprint = true;
    /** The two halves of canSprint, which the sprint rules up to 1.21.4 read apart (Rules.KEY_IMPULSES: blindness
     *  blocks a start but stops no sprint; 1.21.4's shouldStopSprinting stops one on blindness): food enough to sprint
     *  (hasEnoughFoodToSprint: above 6, or able to fly), and Blindness. */
    public boolean enoughFood = true, blind = false;
    public int sprintWindow = 7;
    public boolean canGlide = false;
    /** Copy gliding (a jump pressed in the air with a glider worn starts it) instead of only marking it (wouldGlide):
     *  for the walk's elytra hops; the combat model leaves it off. */
    public boolean glide = false;
    /** Ticks on the ground before a landed glide ends on the client (the server ends it on landing and tells the
     *  client: measured in a lockstep test; on a real server add the ping). */
    public int glideLandTicks = 3;
    /** Air: the most (Entity.getMaxAirSupply), Respiration's oxygen bonus (Attributes.OXYGEN_BONUS: a point of air is
     *  kept with chance 1 - 1 / (bonus + 1)), and a turtle helmet worn (water breathing for 10 s after the head was out
     *  of water). */
    public int maxAir = 300;
    public double oxygenBonus = 0.0;
    public boolean turtleHelmet = false;
    /** Ticks the server's air rule sees the eyes late by, against the client's own eyes test (measured in a lockstep
     *  test: coming up, the air starts to refill this many ticks after the client's eyes are out). */
    public int airEyeLag = 2;
    public double knockbackResistance = 0.0;

    /** The defaults of a plain survival player. */
    public Body() {}
}
