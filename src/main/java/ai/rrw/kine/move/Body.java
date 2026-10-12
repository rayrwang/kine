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
    public boolean canSprint = true;
    public int sprintWindow = 7;
    public boolean canGlide = false;
    public double knockbackResistance = 0.0;

    /** The defaults of a plain survival player. */
    public Body() {}
}
