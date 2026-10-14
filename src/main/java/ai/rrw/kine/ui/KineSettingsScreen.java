package ai.rrw.kine.ui;

import ai.rrw.kine.Settings;
import ai.rrw.kine.hud.ThinOutlines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * Kine's settings (K). A switch for the whole mod at the top, one on each section's header, and a button per
 * feature: on/off, or for the elytra, speed and altitude displays off, in flight, elytra only or always, or a slider. A feature
 * whose section or the whole mod is switched off greys out and keeps its own setting, as does one that needs
 * another feature switched on. Hovering a row shows its description on the right. A folder row (the Visibility
 * section's) lists its settings in the right pane when clicked, and closes when clicked again.
 */
public class KineSettingsScreen extends Screen {

    private static final int MARGIN  = 16;
    private static final int DIVIDER = 0xFF555555;
    private static final int HEADER  = 0xFFBBBBBB;
    private static final int ROW_H    = 20;
    private static final int ROW_STEP = 22;
    private static final int GAP      = 8;     // between sections
    private static final int SWITCH_W = 44;    // a section header's on/off button

    private final Screen parent;

    // layout: every scrolling widget with its content-space y and the text shown when it's hovered
    private static final int ALL = -2, OPTION = -1;   // Row.switchOf: the whole mod, a feature, else a section's index
    private record Row(AbstractWidget button, int baseY, String desc, Opt opt, int switchOf) {}
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> paneRows = new ArrayList<>();   // the open folder's settings, in the right pane (not scrolled)
    private Opt openFolder;                                  // the folder whose settings the right pane shows, or null
    private int paneTextTop;                                 // where the description text starts while a folder is open
    private int[] baseHeaderY;
    private int rowX, colW, headH;
    private int viewTop, viewBottom, contentHeight, maxScroll, scrollOffset;
    private int descTextTop;   // top of the description text on the right, below the reset button

    public KineSettingsScreen(Screen parent) {
        super(Component.literal("Kine settings"));
        this.parent = parent;
    }

    /** A setting's row, or a folder of them (option null, children set) shown in the right pane when opened; when it
     *  can be used (besides its section being on); for a level, the text for its value. */
    private record Opt(String name, String desc, Settings.Option option, Opt[] children, BooleanSupplier needs,
                       IntFunction<String> levelText) {
        static Opt of(String n, String d, Settings.Option o) { return new Opt(n, d, o, null, () -> true, null); }
        static Opt of(String n, String d, Settings.Option o, BooleanSupplier needs, IntFunction<String> levelText) {
            return new Opt(n, d, o, null, needs, levelText);
        }
        static Opt folder(String n, String d, Opt... children) { return new Opt(n, d, null, children, () -> true, null); }
        boolean isFolder() { return children != null; }
        Settings.Section section() { return isFolder() ? children[0].option().section : option().section; }
    }

    private record Section(Settings.Section section, Opt[] opts) {}

    // Descriptions: concise, most important first, similar lengths.
    private static final String DESC_SPEED =
        "Shows your total speed in m/s above the hotbar, vertical motion included, measured from your actual "
        + "movement each tick.";
    private static final String DESC_GROUND =
        "Shows your horizontal speed in m/s above the hotbar, ignoring vertical motion. Range and ETA "
        + "estimates use this speed.";
    private static final String DESC_HEALTH =
        "Shows the current and maximum health above every mob, player and armour stand within 30 blocks, "
        + "scaled by distance and visible through walls.";
    private static final String DESC_NAMES =
        "Shows the name of every mob, player and armour stand within 30 blocks at the middle of its body, "
        + "in smaller text, visible through walls.";

    private static final String DESC_FLIGHT =
        "While gliding high enough to arm the autopilot, magenta bars show the pitch and heading it would "
        + "fly, with the climb mode beside them. Guidance only.";
    private static final String DESC_FPV =
        "Marks where your motion is taking you, not where you are looking; put it on the spot you want to "
        + "reach. Shows while gliding or after falling a few blocks.";
    private static final String DESC_PROFILE =
        "While gliding, a side-on cutaway in the top-right corner shows the terrain ahead out to your "
        + "render distance. With the autopilot engaged it follows and traces the path it predicts, turns included.";
    private static final String DESC_RANGE =
        "While wearing an elytra, shows how long and how far you can still glide on every elytra you carry, "
        + "keeping back what a landing from your height needs. Range uses your recent average speed, less 10%.";
    private static final String DESC_RIBBON =
        "While the autopilot is engaged, draws the path it predicts as two rails in the world: blue seen "
        + "from above, orange from below.";
    private static final String DESC_TERRAIN =
        "The autopilot reads the terrain ahead: it climbs over hills, turns around obstacles, banks away "
        + "from close walls, and hands back control if boxed in. It only arms when it finds a safe path.";

    private static final String DESC_COLLISION =
        "Gliding by hand or on the autopilot, it predicts where you will fly if nothing changes; if that would hurt you, "
        + "the autopilot takes over at the last moment, turns or pulls up into clear air, then hands back with AUTOPILOT "
        + "OFF. Move the mouse or press a key to take over. Needs the autopilot on. Survival and adventure only.";
    private static final String DESC_COLLISION_COOLDOWN =
        "Flying by hand, collision avoidance acts only after this long with no input from you (mouse or keys), in "
        + "seconds, so it leaves you alone while you fly close to terrain yourself; 0 lets it act any time. On the "
        + "autopilot it always acts.";

    private static final String DESC_ELYTRA =
        "Leave this on; turn it off only if a bug of ours keeps getting in your way. Swaps in a fresh elytra "
        + "when the worn one is down to 3 durability. When the last one runs low, the autopilot lands; flying "
        + "by hand, you're warned, and logged out if you don't respond.";
    private static final String DESC_FALL =
        "Sneaks for you at the edge of a drop of more than three blocks, so you stop there. Jump to get past "
        + "it. Water or hay below still counts as a drop, except a walk's own step into water. Survival and adventure only.";
    private static final String DESC_CLUTCH =
        "When you fall far enough to get hurt, sneaks, places water where you will land and scoops it back "
        + "up. Uses a bucket from your hotbar or offhand, or moves one up from your inventory. Survival mode only.";
    private static final String DESC_AFK =
        "Logs you out if you take damage after fifteen seconds with no input. Autopilot camera motion "
        + "doesn't count as input; an open menu does while the game window is in front.";

    private static final String DESC_RETICLE =
        "Draws a ring where your bow, crossbow, trident or thrown item will land, allowing for gravity, drag "
        + "and your own motion. Turns green and boxes the target when it covers one.";
    private static final String DESC_GLOW =
        "Outlines every projectile in red, visible through walls, in flight and after it lands, including "
        + "other players' projectiles.";
    private static final String DESC_AUTOAIM =
        "Turns your view so bow, crossbow and trident shots hit the target nearest your aim, and stays on it "
        + "as it moves, leading it. Move the mouse to break the lock. An aimbot: servers may ban it.";
    private static final String DESC_DODGE =
        "Presses movement keys to step you off a projectile's line when it is about to hit you. On foot only; "
        + "can't dodge point-blank or very fast shots. Survival and adventure only. Lower ban risk than the aimbot.";
    private static final String DESC_AURA =
        "Attacks hostile mobs in reach, and neutral ones like endermen after they hit you (piglins also while "
        + "you wear no gold). Hits mobs about to reach you first, and times swings for the most damage. Your view "
        + "stays put; the server sees turns no faster than a quick flick. Survival and adventure only. Servers may ban it.";
    private static final String DESC_BOW_AURA =
        "Shoots hostile mobs with a bow, a crossbow or a Loyalty trident when the shot is likely to hit and nothing else "
        + "is near its path; never your pets. Held only: just the one in your selected slot. On: switches to the one that "
        + "deals the most damage per second (from your hotbar or inventory) and gives your slot back. The kill aura comes "
        + "first: stands down while it fights or a mob is about to come into its reach, and while combat movement fights, "
        + "the pathfinder walks you or you fly. Your view stays put; the server sees turns no faster than a quick flick. "
        + "Survival and adventure only. Servers may ban it.";
    private static final String DESC_BOW_RANGE =
        "The farthest mob the bow aura shoots at, in blocks. Farther shots miss more and pull mobs that hadn't seen you; "
        + "nearer than 8 the kill aura has them anyway, and past 128 the game doesn't show you mobs.";
    private static final String DESC_COMBAT_MOVE =
        "In fights with most hostile mobs (zombies, skeletons, creepers, spiders, witches, slimes, blazes, "
        + "phantoms, pillagers, piglins and more), moves you with your movement keys so you take less damage: out "
        + "of reach between the kill aura's swings, off the lines of projectiles, clear of creepers about to blow. "
        + "Only while you press no movement key; press any to take over. Lets go near mobs it doesn't know (ghasts, "
        + "guardians, shulkers, wardens). Avoids drops and lava. Stands down while the pathfinder walks you. "
        + "Survival and adventure only. Servers may ban it.";
    private static final String DESC_WALK_PATHFINDER =
        "Walks you to the destination in the nav screen's Walk tab (N), pressing your movement keys along a route it "
        + "plans on foot. Your view stays yours; the server sees you face where you walk. Any movement key hands back "
        + "control; the route stays drawn until you cancel it in the tab. Servers may ban it.";
    private static final String DESC_WALK_PATH =
        "Draws the walking route: rails on the ground near you, a thin line further on and past what's loaded, and the "
        + "destination as a green pillar. Faint through terrain.";
    private static final String DESC_WALK_SEARCH =
        "Replays the search when a walk starts or its way changes, in about two seconds: in orange the best way so far "
        + "reaching out toward the destination and the area searched outlined on the ground, then in magenta the grid "
        + "path pulled taut into the route.";
    private static final String DESC_WALK_SEARCH_DETAILED =
        "The search display shows every place the search looked at instead, one short line each from where it was "
        + "reached, coloured by cost so far, then the grid path in white and the route over it.";
    private static final String DESC_WALK_LAYER2 =
        "How the walk moves along the route. On: each tick it tries runs of your movement keys in a copy of the game's "
        + "own movement and presses the fastest, so it sprint-jumps, swims under the top, climbs out of water and jumps "
        + "gaps. Off: it steers along the route with simpler rules.";
    private static final String DESC_WALK_LAYER2_DISPLAY =
        "Shows the runs the movement search tries as thin lines of where your feet would go, jumps as arcs, the ones it "
        + "kept brighter and the one it chose in gold; they fade over a second.";
    private static final String DESC_WALK_PARKOUR =
        "Lets the walk jump across gaps (pits, ravines, lava) where the jump is faster than the way round. Off by "
        + "default: a jump that falls short lands in the gap. Off, the walk goes round, and the movement search never "
        + "jumps a gap it would fall into.";
    private static final String DESC_WALK_DIRECT =
        "When the pathfinder finds no way to a destination within 48 blocks, the movement search tries on its own, "
        + "heading for the destination in its copy of the game's movement (it may find a way the route planner has no "
        + "move for). It keeps Parkour's setting. Off: the walk stops with no path.";
    private static final String DESC_WALK_FREE_JUMPS =
        "With Parkour on, near a jump the movement search also tries any heading to the degree and the jump on any tick, "
        + "turning in the air toward the landing, with or without a 45 strafe, beside its usual moves.";
    private static final String DESC_WALK_ELYTRA_HOPS =
        "With an elytra worn, the movement search may open it in the air to glide short hops, off ledges and over gaps "
        + "where that is faster. Off by default: gliding wears the elytra (a point of durability a second).";
    private static final String DESC_WALK_READOUTS =
        "While the walk takes you (or is paused with its route), the HUD's nav block gives the walk's destination, the "
        + "distance left along the route and the arrival time, in cyan. On the ground it takes the place of the "
        + "flight's readout; in the air the flight's shows. The arrival time counts the ground ahead, what the walk has "
        + "learned on the way and hunger. It is also on the Walk tab.";
    private static final String DESC_WALK_MARGIN =
        "How far the route keeps from walls, hazards and big drops, in blocks. Wider is safer and a little longer; zero "
        + "hugs corners.";
    private static final String DESC_COMBAT_BIAS =
        "How combat movement weighs your health against a quick kill. 0 keeps out of reach of mobs about to attack and "
        + "never takes a hit to end a fight sooner, more careful as your health drops; 100 closes in to end fights "
        + "fastest. Needs the kill aura on; greyed out otherwise.";
    private static final String DESC_WEAPON =
        "While the kill aura fights, holds the best sword or axe for the target, moving one up from your "
        + "inventory if needed (into an empty hotbar slot, else in place of the largest stack).";
    private static final String DESC_MINING =
        "While you break a block, shows how far through it you are and the time left, just below the "
        + "crosshair.";
    private static final String DESC_HEADING =
        "A compass arc across the top of the screen with your heading boxed above its middle, and a green "
        + "bug at the heading selected in the nav screen.";
    private static final String DESC_ALTITUDE =
        "Your height (Y level) in green, level with the crosshair to its right, clear of the flight "
        + "director bars.";
    private static final String DESC_AGL =
        "Your height above whatever is straight below you, in amber above the hotbar. Water and leaves "
        + "count as ground; over the void it hides.";
    private static final String DESC_NAV_READOUTS =
        "The selected heading or destination with its distance and ETA, the autopilot status (AP), a beam "
        + "over the destination, and a warning if your elytras can't make the distance.";
    private static final String DESC_AUTOPILOT =
        "Press J while gliding high enough to engage the autopilot, which flies the efficient climb and "
        + "cruise for you. Off: J does nothing.";
    private static final String DESC_TAKEOFF =
        "Standing with an elytra on and rockets in hand, left click to take off: straight up, a 70 degree rocket "
        + "climb, then the autopilot at its cruise level, using the fewest rockets. Counts every rocket you carry and "
        + "refills your hand from them. Needs open sky above you.";
    private static final String DESC_LANDING =
        "Gliding by hand, a green square marks a safe spot you look at; left click and the autopilot lands there: "
        + "a fast spiral down, a brake well above the ground, a slow finish. Walls give their top or their foot.";
    private static final String DESC_NAV =
        "Press N for the nav screen: fly a heading or to coordinates, with landing at the destination. "
        + "Off: N does nothing and the autopilot keeps its heading.";
    private static final String DESC_OUTLINES =
        "Keeps the red projectile outlines thin and at full colour. It changes every outline in the game "
        + "while on; switching it reloads the game's resources.";
    private static final String DESC_FUEL =
        "Counts your rockets' burn in flight-duration-1 rockets, a green number with a rocket icon beside END "
        + "and RNG, and while one boosts you, a bar of how far through its burn it likely is.";
    private static final String DESC_TOTEM =
        "Keeps a totem of undying in your offhand: when it holds none, one moves there from your inventory, through "
        + "the inventory as a player would (your keys let go for a tick). Whatever was in the offhand takes its place.";
    private static final String DESC_EAT =
        "Eats for you: the best food you carry as soon as all its hunger and saturation fit, and whenever it can while "
        + "hearts are missing, picking what fills you most; a golden apple only when hearts are missing. Bad food (raw "
        + "chicken, rotten flesh) only when nothing else is left and you are too hungry to sprint, or hurt below 18 "
        + "hunger, where healing stops; several in a row so their effect runs once. Poison food and suspicious stew only at "
        + "0 hunger. Never pufferfish, chorus fruit or enchanted golden apples. Waits while you fight, and backs off when "
        + "you click. "
        + "Survival and adventure only.";
    private static final String DESC_FULLBRIGHT =
        "Lights everything at full brightness, as if every block had light level 15: see in caves and at night "
        + "without torches. Only your view changes; mobs still spawn in the dark.";
    private static final String DESC_FREECAM =
        "Press F4 to fly the camera on its own, through blocks, while your player stands still where it is. Your "
        + "movement keys and mouse move the camera, not you; F4 again to come back. Kine's features keep acting on "
        + "your player meanwhile. You can't attack or use items in it. Only your view changes.";
    private static final String DESC_FOG_FOLDER =
        "Fog you can switch off: under water, in lava and powder snow, in the Nether, in rain, in a boss fight, and "
        + "the fade at your render distance. Click to list them on the right.";
    private static final String DESC_OVERLAY_FOLDER =
        "Effects and overlays you can switch off: Darkness, Blindness, and the fire, underwater, pumpkin, frost and "
        + "portal overlays. Click to list them on the right.";
    private static final String DESC_NO_WATER_FOG =
        "Removes the fog under water, so you see as far as you do on land.";
    private static final String DESC_NO_LAVA_FOG =
        "Removes the fog inside lava, so you can see around you while you're in it, the sky included.";
    private static final String DESC_NO_POWDER_SNOW_FOG =
        "Removes the fog inside powder snow, so you can see out while you're in it.";
    private static final String DESC_NO_NETHER_FOG =
        "Removes the Nether's thick fog, so you see out to your render distance there.";
    private static final String DESC_NO_RAIN_FOG =
        "Keeps rain and thunderstorms from pulling the fog in closer.";
    private static final String DESC_NO_BOSS_FOG =
        "Removes the fog of the Ender Dragon fight and the darkening of the world while a Wither's health bar shows.";
    private static final String DESC_NO_DISTANCE_FOG =
        "Removes the fade at the edge of your render distance: far terrain ends in a hard edge instead of fading out.";
    private static final String DESC_NO_DARKNESS =
        "Removes the Darkness effect from wardens and sculk shriekers: no dimming pulses, no short fog, and the "
        + "sky stays visible.";
    private static final String DESC_NO_BLINDNESS =
        "Removes the Blindness effect's short black fog and lets the sky show.";
    private static final String DESC_NO_FIRE_OVERLAY =
        "Hides the flames drawn over your view while you're burning. You still burn.";
    private static final String DESC_NO_UNDERWATER_OVERLAY =
        "Hides the dark water texture drawn over your view while your head is under water.";
    private static final String DESC_NO_PUMPKIN_OVERLAY =
        "Hides the blur drawn over your view while you wear a carved pumpkin.";
    private static final String DESC_NO_FROST_OVERLAY =
        "Hides the frost drawn around your view while you freeze in powder snow. You still freeze.";
    private static final String DESC_NO_PORTAL_OVERLAY =
        "Hides the purple swirl drawn over your view while you stand in a nether portal.";
    private static final String DESC_ALL =
        "Switches every Kine feature off at once, and back on, keeping each one's own setting. K still opens "
        + "this menu.";
    private static final String DESC_SECTION =
        "Switches every feature in this section off at once, and back on, keeping each one's own setting.";
    private static final String HINT =
        "Hover an option for details. IN FLIGHT shows a display only while you glide or take off; ELYTRA ONLY "
        + "while you wear an elytra; ALWAYS everywhere.";


    private static final Section[] SECTIONS = {
        new Section(Settings.Section.HUD, new Opt[]{
            Opt.of("Display mob healths", DESC_HEALTH, Settings.DISPLAY_MOB_HEALTHS),
            Opt.of("Display mob names", DESC_NAMES, Settings.DISPLAY_MOB_NAMES),
            Opt.of("Block breaking progress", DESC_MINING, Settings.DISPLAY_MINING_PROGRESS),
            Opt.of("Display speed", DESC_SPEED, Settings.DISPLAY_SPEED),
            Opt.of("Display ground speed", DESC_GROUND, Settings.DISPLAY_GROUND_SPEED),
        }),
        new Section(Settings.Section.VISIBILITY, new Opt[]{
            Opt.of("Fullbright", DESC_FULLBRIGHT, Settings.FULLBRIGHT),
            Opt.of("Freecam", DESC_FREECAM, Settings.FREECAM),
            Opt.folder("Fog", DESC_FOG_FOLDER,
                Opt.of("No water fog", DESC_NO_WATER_FOG, Settings.NO_WATER_FOG),
                Opt.of("No lava fog", DESC_NO_LAVA_FOG, Settings.NO_LAVA_FOG),
                Opt.of("No powder snow fog", DESC_NO_POWDER_SNOW_FOG, Settings.NO_POWDER_SNOW_FOG),
                Opt.of("No Nether fog", DESC_NO_NETHER_FOG, Settings.NO_NETHER_FOG),
                Opt.of("No rain fog", DESC_NO_RAIN_FOG, Settings.NO_RAIN_FOG),
                Opt.of("No boss fog", DESC_NO_BOSS_FOG, Settings.NO_BOSS_FOG),
                Opt.of("No distance fog", DESC_NO_DISTANCE_FOG, Settings.NO_DISTANCE_FOG)),
            Opt.folder("Effects & overlays", DESC_OVERLAY_FOLDER,
                Opt.of("No darkness", DESC_NO_DARKNESS, Settings.NO_DARKNESS),
                Opt.of("No blindness", DESC_NO_BLINDNESS, Settings.NO_BLINDNESS),
                Opt.of("No fire overlay", DESC_NO_FIRE_OVERLAY, Settings.NO_FIRE_OVERLAY),
                Opt.of("No underwater overlay", DESC_NO_UNDERWATER_OVERLAY, Settings.NO_UNDERWATER_OVERLAY),
                Opt.of("No pumpkin overlay", DESC_NO_PUMPKIN_OVERLAY, Settings.NO_PUMPKIN_OVERLAY),
                Opt.of("No frost overlay", DESC_NO_FROST_OVERLAY, Settings.NO_FROST_OVERLAY),
                Opt.of("No portal overlay", DESC_NO_PORTAL_OVERLAY, Settings.NO_PORTAL_OVERLAY)),
        }),
        new Section(Settings.Section.FLIGHT, new Opt[]{
            Opt.of("Heading arc", DESC_HEADING, Settings.DISPLAY_HEADING_ARC),
            Opt.of("Altitude", DESC_ALTITUDE, Settings.DISPLAY_ALTITUDE),
            Opt.of("Height above ground", DESC_AGL, Settings.DISPLAY_HEIGHT_ABOVE_GROUND),
            Opt.of("Nav & AP readouts", DESC_NAV_READOUTS, Settings.DISPLAY_NAV_READOUTS),
            Opt.of("Flight directors", DESC_FLIGHT, Settings.DISPLAY_FLIGHT_DIRECTORS),
            Opt.of("Flight path vector", DESC_FPV, Settings.DISPLAY_FLIGHT_PATH_VECTOR),
            Opt.of("Flight profile map", DESC_PROFILE, Settings.DISPLAY_FLIGHT_PROFILE),
            Opt.of("Range & endurance", DESC_RANGE, Settings.DISPLAY_RANGE_ENDURANCE),
            Opt.of("Firework fuel", DESC_FUEL, Settings.DISPLAY_FIREWORK_FUEL),
            Opt.of("Flight path rails", DESC_RIBBON, Settings.FLIGHT_PATH_RAILS),
            Opt.of("Autopilot", DESC_AUTOPILOT, Settings.AUTOPILOT),
            Opt.of("Rocket takeoff", DESC_TAKEOFF, Settings.TAKEOFF),
            Opt.of("Aimed landing", DESC_LANDING, Settings.LANDING),
            Opt.of("Navigation", DESC_NAV, Settings.NAVIGATION),
            Opt.of("Terrain avoidance", DESC_TERRAIN, Settings.TERRAIN_AVOIDANCE),
            Opt.of("Collision avoidance", DESC_COLLISION, Settings.COLLISION_AVOIDANCE),
            Opt.of("Avoidance cooldown", DESC_COLLISION_COOLDOWN, Settings.COLLISION_COOLDOWN, () -> Settings.collisionAvoidance,
                   v -> String.format(java.util.Locale.ROOT, "%.1f s", v / 10.0)),
        }),
        new Section(Settings.Section.WALK, new Opt[]{
            Opt.of("Pathfinder", DESC_WALK_PATHFINDER, Settings.WALK_PATHFINDER),
            Opt.of("Path display", DESC_WALK_PATH, Settings.WALK_PATH_DISPLAY),
            Opt.of("Search display", DESC_WALK_SEARCH, Settings.WALK_SEARCH_DISPLAY),
            Opt.of("Detailed search display", DESC_WALK_SEARCH_DETAILED, Settings.WALK_SEARCH_DETAILED, () -> Settings.walkSearchDisplay, null),
            Opt.of("Movement search", DESC_WALK_LAYER2, Settings.WALK_LAYER2),
            Opt.of("Movement search display", DESC_WALK_LAYER2_DISPLAY, Settings.WALK_LAYER2_DISPLAY),
            Opt.of("Parkour", DESC_WALK_PARKOUR, Settings.WALK_PARKOUR),
            Opt.of("Movement search without a route", DESC_WALK_DIRECT, Settings.WALK_DIRECT),
            Opt.of("Free moves near jumps", DESC_WALK_FREE_JUMPS, Settings.WALK_FREE_JUMPS, () -> Settings.walkParkour, null),
            Opt.of("Elytra hops", DESC_WALK_ELYTRA_HOPS, Settings.WALK_ELYTRA_HOPS),
            Opt.of("Distance and ETA", DESC_WALK_READOUTS, Settings.WALK_READOUTS),
            Opt.of("Safety margin", DESC_WALK_MARGIN, Settings.WALK_MARGIN, () -> Settings.walkPathfinder,
                   v -> String.format(java.util.Locale.ROOT, "%.2f", v / 100.0)),
        }),
        new Section(Settings.Section.SAFETY, new Opt[]{
            Opt.of("Elytra durability failsafe", DESC_ELYTRA, Settings.ELYTRA_DURA_FAILSAFE),
            Opt.of("Fall prevention", DESC_FALL, Settings.FALL_PREVENTION),
            Opt.of("Water bucket clutch", DESC_CLUTCH, Settings.WATER_BUCKET_CLUTCH),
            Opt.of("Auto totem", DESC_TOTEM, Settings.AUTO_TOTEM),
            Opt.of("Auto eat", DESC_EAT, Settings.AUTO_EAT),
            Opt.of("AFK damage protection", DESC_AFK, Settings.AFK_DAMAGE_PROTECTION),
        }),
        // ordered by ban risk, the riskiest last: client-side only, then movement keys, then fighting
        new Section(Settings.Section.COMBAT, new Opt[]{
            Opt.of("Projectile targeting reticle", DESC_RETICLE, Settings.PROJECTILE_RETICLE),
            Opt.of("Projectile glow", DESC_GLOW, Settings.PROJECTILE_GLOW),
            Opt.of("Thin projectile outlines", DESC_OUTLINES, Settings.THIN_OUTLINES),
            Opt.of("Projectile dodge", DESC_DODGE, Settings.PROJECTILE_DODGE),
            Opt.of("Auto weapon", DESC_WEAPON, Settings.AUTO_WEAPON),
            Opt.of("Auto aim", DESC_AUTOAIM, Settings.AUTO_AIM),
            Opt.of("Kill aura", DESC_AURA, Settings.KILL_AURA),
            Opt.of("Bow aura", DESC_BOW_AURA, Settings.BOW_AURA),
            Opt.of("Bow aura range", DESC_BOW_RANGE, Settings.BOW_AURA_RANGE, () -> Settings.bowAura,
                   v -> Math.max(Settings.BOW_RANGE_MIN, v) + " blocks"),
            Opt.of("Combat movement", DESC_COMBAT_MOVE, Settings.COMBAT_MOVEMENT),
            Opt.of("Survive or kill fast", DESC_COMBAT_BIAS, Settings.COMBAT_BIAS,
                   () -> Settings.killAura && Settings.combatMovement, KineSettingsScreen::biasText),
        }),
    };

    @Override
    protected void init() {
        // the outline pack can also be switched in the game's resource pack screen: take its state as the setting
        if (Settings.enabled() && Settings.sectionOn(Settings.Section.COMBAT)
            && Settings.thinOutlines != ThinOutlines.selected(this.minecraft)) Settings.THIN_OUTLINES.cycle();

        rowX = MARGIN;
        colW = Math.min(280, this.width / 2 - MARGIN - 8);
        headH = ROW_H + 4;
        viewTop = 30;
        int doneH = 18;
        viewBottom = this.height - doneH - 12;
        rows.clear();
        paneRows.clear();
        baseHeaderY = new int[SECTIONS.length];

        int cY = 0;
        Button all = Button.builder(allLabel(), b -> { Settings.setEnabled(!Settings.enabled()); changed(); })
            .bounds(rowX, viewTop, colW, ROW_H).build();
        rows.add(new Row(addRenderableWidget(all), cY, DESC_ALL, null, ALL));
        cY += ROW_STEP + GAP;
        for (int s = 0; s < SECTIONS.length; s++) {
            Settings.Section sec = SECTIONS[s].section();
            baseHeaderY[s] = cY;
            Button sw = Button.builder(onOff(Settings.sectionOn(sec)), b -> { Settings.setSectionOn(sec, !Settings.sectionOn(sec)); changed(); })
                .bounds(rowX + colW - SWITCH_W, viewTop + cY, SWITCH_W, ROW_H).build();
            rows.add(new Row(addRenderableWidget(sw), cY, DESC_SECTION, null, s));
            cY += headH;
            for (Opt opt : SECTIONS[s].opts()) {
                AbstractWidget b = !opt.isFolder() && opt.option().isLevel()
                    ? new LevelSlider(rowX, viewTop + cY, colW, ROW_H, opt)
                    : Button.builder(label(opt), btn -> {
                            if (opt.isFolder()) { openFolder = openFolder == opt ? null : opt; rebuildWidgets(); }
                            else { opt.option().cycle(); changed(); }
                        })
                        .bounds(rowX, viewTop + cY, colW, ROW_H).build();
                rows.add(new Row(addRenderableWidget(b), cY, opt.desc(), opt, OPTION));
                cY += ROW_STEP;
            }
            cY += GAP;
        }
        contentHeight = cY;
        maxScroll = Math.max(0, contentHeight - (viewBottom - viewTop));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        refresh();
        layout();

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
            .bounds(rowX, this.height - doneH - 6, colW, doneH).build());

        // reset to defaults, pinned to the top right of the scrolling area (it doesn't scroll)
        int resetH = 18, resetW = 150;
        int minResetX = rowX + colW + 8;
        int resetX = this.width - MARGIN - resetW;
        if (resetX < minResetX) { resetX = minResetX; resetW = Math.max(60, this.width - MARGIN - minResetX); }
        descTextTop = viewTop + resetH + 6;
        addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> { Settings.resetDefaults(); changed(); })
            .bounds(resetX, viewTop, resetW, resetH).build());

        // the open folder's settings, in the right pane under its name; the description goes below them
        paneRows.clear();
        int paneX = rowX + colW + MARGIN;
        int paneW = Math.min(240, this.width - paneX - MARGIN);
        int py = descTextTop + this.font.lineHeight + 6;
        if (openFolder != null && openFolder.isFolder() && paneW >= 80) {
            // rows shrink (down to 16 high) on a short screen so at least four lines of description fit below them
            int n = openFolder.children().length;
            int room = this.height - 8 - py - 4 * (this.font.lineHeight + 2);
            int step = Math.max(16, Math.min(ROW_STEP, room / n));
            for (Opt opt : openFolder.children()) {
                Button b = Button.builder(label(opt), btn -> { opt.option().cycle(); changed(); })
                    .bounds(paneX, py, paneW, step - 2).build();
                paneRows.add(new Row(addRenderableWidget(b), py, opt.desc(), opt, OPTION));
                py += step;
            }
        }
        paneTextTop = paneRows.isEmpty() ? descTextTop : py + 6;
        refresh();
    }

    /** After any change: relabel every button, and switch the outline pack if its effective setting moved. */
    private void changed() {
        refresh();
        ThinOutlines.sync(this.minecraft);
    }

    private void refresh() {
        for (Row r : paneRows) {
            r.button().setMessage(label(r.opt()));
            r.button().active = Settings.enabled() && Settings.sectionOn(r.opt().section()) && r.opt().needs().getAsBoolean();
        }
        for (Row r : rows) {
            if (r.switchOf() == ALL) {
                r.button().setMessage(allLabel());
            } else if (r.switchOf() >= 0) {
                r.button().setMessage(onOff(Settings.sectionOn(SECTIONS[r.switchOf()].section())));
                r.button().active = Settings.enabled();
            } else {
                r.button().setMessage(label(r.opt()));
                r.button().active = Settings.enabled() && Settings.sectionOn(r.opt().section()) && r.opt().needs().getAsBoolean();
            }
        }
    }

    /** Reposition rows for the current scroll offset and hide any not fully inside the viewport. */
    private void layout() {
        for (Row r : rows) {
            int sy = viewTop + r.baseY() - scrollOffset;
            r.button().setY(sy);
            r.button().visible = sy >= viewTop && sy + ROW_H <= viewBottom;
        }
    }

    private static Component allLabel() {
        return Component.literal("All features: " + (Settings.enabled() ? "ON" : "OFF"));
    }

    private static Component onOff(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }

    private Component label(Opt opt) {
        if (opt.isFolder()) {
            int on = 0;
            for (Opt c : opt.children()) if (c.option().value() != 0) on++;
            return Component.literal(opt.name() + ": " + on + " of " + opt.children().length + " on  " + (openFolder == opt ? "<" : ">"));
        }
        if (opt.levelText() != null) return Component.literal(opt.name() + ": " + opt.levelText().apply(opt.option().value()));
        return Component.literal(opt.name() + ": " + opt.option().label());
    }

    /** The survive or kill fast slider's value (the description says which end is which; words don't fit the row). */
    private static String biasText(int v) {
        return String.valueOf(v);
    }

    /** A slider for a level setting: drags from 0 to its maximum, saved as it moves. */
    private final class LevelSlider extends AbstractSliderButton {
        private final Opt opt;

        LevelSlider(int x, int y, int w, int h, Opt opt) {
            super(x, y, w, h, label(opt), (double) opt.option().value() / opt.option().max());
            this.opt = opt;
        }

        @Override
        protected void updateMessage() {
            setMessage(label(opt));
        }

        @Override
        protected void applyValue() {
            opt.option().setLevel((int) Math.round(this.value * opt.option().max()));
            updateMessage();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (maxScroll > 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (dy * 18)));
            layout();
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        layout();
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        // section headers + dividers, clipped to the scrolling viewport
        graphics.enableScissor(0, viewTop, this.width, viewBottom);
        for (int s = 0; s < SECTIONS.length; s++) {
            int hy = viewTop + baseHeaderY[s] - scrollOffset;
            int ty = hy + (ROW_H - this.font.lineHeight) / 2;
            boolean on = Settings.enabled() && Settings.sectionOn(SECTIONS[s].section());
            graphics.text(this.font, Component.literal(SECTIONS[s].section().title), rowX, ty, on ? HEADER : 0xFF777777);
            int dy = hy + ROW_H + 1;
            graphics.fill(rowX, dy, rowX + colW, dy + 1, DIVIDER);
        }
        graphics.disableScissor();

        // scrollbar
        if (maxScroll > 0) {
            int barX = rowX + colW + 4, viewH = viewBottom - viewTop;
            int thumbH = Math.max(20, viewH * viewH / contentHeight);
            int thumbY = viewTop + (int) ((long) (viewH - thumbH) * scrollOffset / maxScroll);
            graphics.fill(barX, viewTop, barX + 2, viewBottom, 0xFF2A2A2A);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbH, 0xFF8A8A8A);
        }

        // description of the hovered row, on the right
        int descX = rowX + colW + MARGIN;
        int descW = this.width - descX - MARGIN;
        if (descW < 80) return;

        if (openFolder != null && !paneRows.isEmpty()) {
            boolean on = Settings.enabled() && Settings.sectionOn(openFolder.section());
            graphics.text(this.font, Component.literal(openFolder.name()), descX, descTextTop + 2, on ? HEADER : 0xFF777777);
        }

        String hovered = null;
        for (List<Row> list : List.of(paneRows, rows)) {
            for (Row r : list) {
                AbstractWidget b = r.button();
                if (b.visible && mouseX >= b.getX() && mouseX <= b.getX() + b.getWidth()
                    && mouseY >= b.getY() && mouseY <= b.getY() + b.getHeight()) { hovered = r.desc(); break; }
            }
            if (hovered != null) break;
        }

        String idle = openFolder != null && !paneRows.isEmpty() ? openFolder.desc() : HINT;
        String text = hovered != null ? hovered : idle;
        int color = hovered != null ? 0xFFFFFFFF : 0xFF909090;
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), descW);
        int ty = paneTextTop;
        for (FormattedCharSequence line : lines) {
            graphics.text(this.font, line, descX, ty, color);
            ty += this.font.lineHeight + 2;
        }
    }

    @Override
    public void onClose() {
        Settings.save();
        this.minecraft.setScreen(parent);
    }
}
