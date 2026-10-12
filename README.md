
# Kine

<p align="center">
<table>
<tr>
<td><img alt="Screenshot of elytra autopilot HUD" src="https://github.com/user-attachments/assets/aa474395-50ae-4a19-839d-e4dad5192867"></td>
<td><img alt="Screenshot of aimbot targeting" src="https://github.com/user-attachments/assets/704d95d7-d264-4377-90c2-b2a50971053b"></td>
</tr>
</table>
</p>

Minecraft elytra autopilot from takeoff to landing, aimbot and kill aura, safety features, and HUD displays.

This mod only uses vanilla legal mechanics, the only "cheating" is having a computer instead of a monkey at the controls (verified against [Grim](https://github.com/GrimAnticheat/Grim)).

<p align="center">
	<a href="https://github.com/rayrwang/kine/releases">Download the Mod</a>
</p>

Make sure to install [Fabric API](https://modrinth.com/mod/fabric-api).

## Current Features

- **Fly long distances without fireworks.**
	- Take off from ground with rockets, or start high enough and press `J` to engage autopilot.
	- Terrain aware autopilot with fully optimized flight profile.
	- Press `N` to enter a destination, with auto landing.
	- Or look at a spot while gliding and left click to land there.
	- Visualizations: flight director bars, path guiderails, flight path vector, and side profile map.
- **Aimbot and targeting reticles for weapons.**
	- Enable in settings (press `K` to open).
	- Shows the chance the shot hits, and aims at the point on the target most likely to hit.
	- Marks targets projectiles can't hurt (endermen, breezes, raised shields, etc.).
- **Water bucket clutch automatically** (survival only).
- **Auto totem**: keeps a totem of undying in your offhand.
- **Kill aura.**
	- Times each swing for the most damage by weapon and crowd, hits mobs about to reach you first, and leaves neutral mobs alone until they hit you.
- **Dodge arrows and other projectiles.**
- **AFK failsafes.**
	- Logs you out if you take damage while AFK, if the autopilot disconnects and you don't take over, or if your elytra runs low while you fly by hand without input (on autopilot it lands instead).
- **Fall protection.**
	- The mod will prevent you from walking off ledges greater than 3 blocks high; you can override it by jumping then walking off.
- Block breaking percentage and time left indicator.
- Elytra low durability protection, auto swap and endurance/range display.
- Firework rocket count and burn bar for elytra flight.
- Display player speed and altitude.
- Show health of mobs.
- Projectiles red outline.
- Fullbright, and switches for fog (water, lava, powder snow, Nether, rain, boss fight, render distance), Darkness, Blindness and screen overlays (fire, underwater, pumpkin, frost, portal).
- Settings menu (press `K` to open); every feature can be switched off, by section, or all at once.

## Planned

- Above ground altitude display change color
- Expand configurability especially aimbot, kill aura
- Kill aura only on player attack or being attacked
- Elytra autopilot:
	- Per block pathing instead of heightmap highest block
	- Fly climbing pattern if boxed in
	- Descend gradually before reaching destination
	- Fly landing instead of disengaging on unable to escape terrain?
	- Support fireworks?
	- Warnings?
- Fix:
	- Low altitude warning should count as autopilot disconnect
	- Flight director turn even in manual flight?
- Update pictures, add logo
- Vertical speed indicator? Move speed to left?
- Spear elytra
- Mace fall
- Auto eat
- Activate elytra if fall (could be annoying)
- Creeper explosion avoidance
- Warning sounds
- User guide / help: controls & displays list and explanations
- Aimbot:
	- Collision detection
	- Zoomed in targeting view
	- Ender pearl target selection & protection
	- Finer selection options: priority, whitelist
- Auto combat
- Destination targeting by right clicking with empty hand (uses Baritone if available, otherwise fall back to native pathfinding)
- Support multiple versions & forge

## Technical Details

Built for Minecraft 26.1.2 (unobfuscated, Mojang names) on Fabric. Client-side only. Cheats are off by default; everything is switched on and off in the settings screen (`K`), one feature at a time, a section at a time, or all at once, and the elytra and speed displays can show always, only while you wear an elytra, or never. The choices are saved in `config/kine.json`.

### Elytra autopilot

No fireworks in flight (rockets only for the takeoff, below). The autopilot flies a porpoise: it dives to build speed, levels off at the bottom so the elytra turns the sink into forward speed, then pulls up hard to trade that speed for height, eases the nose back down and glides before the next dive. Its two profiles were searched for in a model of the game's elytra code (`LivingEntity.updateFallFlyingMovement`): a climb profile that pulls up at a set speed (about 1.5 m/s of climb at 22.5 m/s), and an altitude-hold profile that dives down to the target altitude before each pull-up (about 32 m/s), so a descent from any height ends in one dive. The pitch moves at up to 15 degrees a tick, inside a fast human mouse flick. Magenta flight director bars show the commanded pitch and heading; the autopilot (`J`) works out its command once a tick, as the model steps it, and turns the view its share of the way there every frame, so it moves smoothly and the server gets the planned rotation each tick (stopping while singleplayer is paused); `A`/`D` move a heading bug. Moving the mouse hands control back, and if the autopilot drops out in flight and you don't take over within 1.5 s, you are disconnected.

The flight path rails, drawn in the world, and the trace on the side-on profile map show the path the flight model predicts, turns included.

### Rocket takeoff

Standing with an elytra on and rockets in hand, the warning row offers a takeoff in green (LEFT CLICK TO TAKE OFF), or says in red why it can't (blocked above, not enough rockets, elytra too worn, rockets with firework stars, standing in water, autopilot switched off). A left click looks straight up, jumps, opens the elytra and fires a rocket. It climbs straight up until a leaning climb would clear the terrain, then leans to 70 degrees, which climbs about as fast and builds speed. As each rocket burns out it fires another unless the autopilot, taking over in altitude hold, would hold the cruise without it; then the autopilot engages. The rockets counted are all those you carry: when the stack in your hand runs out, another is selected from the hotbar, or moved into your hand from the inventory. Any mouse movement or movement key stops it.

### Terrain avoidance

On by default. While engaged, the autopilot rolls the flight model forward over the loaded heightmap, replanning every half second: it flies straight when that path clears, raises the porpoise's low point to climb over terrain, turns toward a clear heading, and reverses hard from a wall inside its turn radius. When reversing doesn't get past a wall it hands control back. It also arms only when it finds a path. Terrain is the heightmap's top surface, so overhangs and caves count as solid.

### Navigation and landing

`N` opens the nav screen: *Selected* holds a compass heading, *Managed* flies to an X/Z coordinate, *Off* does neither. A green beam rises from the ground at a managed target (drawn over terrain, like Baritone's), and the HUD shows distance and ETA. Within 24 blocks of the target the autopilot picks the nearest safe column (no lava, fire, cactus, magma, powder snow, berry bushes, campfires, wither roses or water) and lands on it.

Aimed landing: while gliding by hand, a reticle marks a spot you could land on, the flat surface you look at, or for a wall or pole the nearer of its top and the ground at its foot. It shows only for a safe spot in a loaded chunk up to 1024 blocks away that the landing can reach within 1.5 blocks without damage. Left click (LEFT CLICK TO LAND) and the autopilot lands there, with the landing block's top face outlined in green; afterwards your trip's destination comes back.

The landing glides toward the spot at 5 degrees nose down, spirals down around it at 60 degrees, brakes nose up from 60 blocks above, spirals again at 25 degrees to 10 above, then settles onto it nose up at about 3 m/s. A 1 s freeze of the controls anywhere costs no health; a small bump of the mouse is taken back, a larger move hands control back. Creative flight cancels it.

### Range and endurance

Endurance (END) is the expected gliding time on every elytra you carry (worn, inventory, offhand; not in shulker boxes): each one's durability above 3 points, one point a second times Unbreaking + 1 on average (elytra take the tool rule for Unbreaking). It holds back a landing reserve on the last elytra to be flown: one point for each second the autopilot's landing would take from your height down to the bottom of the world, at worst a point a second, read from a curve precomputed with the landing law. So END reaching 0 means the landing must start now. An Unbreakable elytra shows ∞. Range (RNG) is endurance times your average ground speed over whole porpoise cycles, less 10%, and starts afresh each flight; navigation's INSUFFICIENT DURABILITY compares it with the distance left. END, RNG and ETA count down with the clock and drift toward the raw estimate, so they don't jump with each durability point. END turns amber in its last minute and red in its last 20 s.

Beside them, the firework count is the burn of every rocket you carry without firework stars (inventory and offhand), counted in flight-duration-1 rockets, with a rocket icon. While a rocket boosts you, a burn bar shows how far through it you are: bright up to the shortest burn the rocket can have, then fading with the chance it is still burning, since the server rolls each rocket's length and never sends it.

### Durability failsafe and auto-swap

An elytra counts as spent at 3 durability or less (the game itself stops gliding at 1). While you glide, by hand or on autopilot, a spent elytra is swapped for the spare with the most durability left, and after a swap onto the last usable one LAST ELYTRA shows for a few seconds. When endurance reaches 0, the autopilot lands near where you are with its landing law, on the nearest dry, safe column within 32 blocks, or on water only if there is none, under a red warning. Flying by hand you get a red warning instead, and if you give no input for 5 s (input as the AFK protection counts it) you are disconnected. Both warnings show the gliding time the reserve still holds, on average. A rocket takeoff is offered only if endurance would still be at least 60 s at the cruise. Swaps are inventory clicks made as a player makes them: one tick with the inventory opened (every key up), the clicks, then the close, only with no container open.

### Combat

The targeting reticle simulates the readied projectile tick by tick (gravity, drag, your own motion) and rings where it lands, meeting moving mobs where they will be. Beside the crosshair it shows the chance the shot hits, from the game's own launch spread over a fixed set of rolls, with blocks in the way counted. Auto aim picks up the target the reticle covers or the one the view is nearest to aiming at, holds it, and steers for the bow's full draw: the pitch from secant steps with a bracketing fallback, the yaw allowing for your motion, at the point on the target with the best chance, led by the time until the shot reaches the server and the network delay, along the target's course (falling under gravity when it's off the ground). Targets projectiles can't hurt (endermen, breezes, a shut shulker, an armoured or spawning wither, a raised shield) are marked amber with the reason and never picked. The kill aura attacks hostile mobs, and neutral mobs such as endermen after they hit you (piglins also while you wear no gold, spiders where it is bright, never the warden unprovoked). It hits a mob about to reach you first, whatever the charge, since each hit knocks it back; otherwise it times its swings on the game's damage rules: one mob, a full charge each time; a crowd, a quick hit on every mob as soon as it can take one, then a charged hit, in cycles of at least the 10 ticks a struck mob ignores a second full hit (a dynamic program over the charge and each mob's 10 ticks found that shape best); a sword's full-charge sweep when it catches enough of the crowd to pay more; and a crit when a jump puts one a few ticks away. The rotation it sends the server is its own, turned from your view at most 30 degrees a tick (no faster than a quick flick) and undone before each frame, so your view doesn't move; while it is turned, your movement keys are swapped for the ones that walk where you meant, and your clicks wait for it to come back. Auto weapon holds the best sword or axe by damage per second. Projectile dodge presses movement keys, on foot, to step off the line of projectiles whose flight, with gravity and drag, passes close, never toward a drop of more than 3 blocks. Projectiles glow red through walls.

### Other features

Water-bucket clutch (survival only; it turns toward where you will land through the fall, places the water in reach and scoops it back up), auto totem (when your offhand holds no totem and you carry one, it is swapped in through an inventory opening like a player's; whatever was in the offhand takes its slot), AFK damage protection (logs you out if you take damage after 15 s with no input, focused or not), fall prevention (sneaks at the edge of drops of more than 3 blocks; jumping gets past it), block-breaking progress, speed readouts, the radio altimeter, the flight path vector, and mob health and names. The Visibility settings are drawing only: fullbright lights the lightmap from a white base, the fog switches clear each kind of fog the game draws (and draw the sky in lava and powder snow), and the effect and overlay switches remove Darkness, Blindness and the full-screen overlays; their switches open in two folders on the right of the settings screen.

### A note on anti-cheat

HUD readouts are invisible to servers. Everything the server receives is something a precisely controlled vanilla client could send: turns in whole mouse steps, attacks and item uses aimed by the crosshair at the rotation the server is sent, movement from pressed keys, and inventory clicks after a tick with every key let go, as opening a real inventory does, followed by the close it sends. That makes the mod hard to tell from a fast, precise player, not from one: the aimbot and the kill aura are still the features most likely to get you banned. Use accordingly.

---

kinematics
