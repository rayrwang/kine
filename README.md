
# Kine

<p align="center">
<table>
<tr>
<td><img alt="Screenshot of elytra autopilot HUD" src="https://github.com/user-attachments/assets/aa474395-50ae-4a19-839d-e4dad5192867"></td>
<td><img alt="Screenshot of aimbot targeting" src="https://github.com/user-attachments/assets/704d95d7-d264-4377-90c2-b2a50971053b"></td>
</tr>
</table>
</p>

Minecraft elytra autopilot, aimbot, movement tweaks, and HUD displays.

This mod only uses vanilla legal mechanics, the only "cheating" is having a computer instead of a monkey at the controls.

<p align="center">
	<a href="https://github.com/rayrwang/kine/releases">Download the Mod</a>
</p>

Make sure to install [Fabric API](https://modrinth.com/mod/fabric-api).

## Current Features

- **Fly long distances without fireworks.**
	- Start high enough, then press J to engage autopilot.
	- Terrain aware autopilot with fully optimized flight profile.
	- Press N to enter a destination, with auto landing.
	- Visualizations: flight director bars, path guiderails, and flight path vector.
- **Aimbot and targeting reticles for weapons.**
	- Enable in settings (press K to open).
- **Water bucket clutch automatically.**
- **Kill aura.**
	- Takes into account number of enemies (spam for more, cooldown for fewer).
- **Dodge arrows and other projectiles.**
- **AFK failsafes.**
	- Logs you out if you take damage while AFK, autopilot disconnects, or elytra has low durability.
- **Fall protection.**
	- The mod will prevent you from walking off ledges greater than 3 blocks high; you can override it by jumping then walking off.
- Block breaking percentage and time left indicator.
- Elytra low durability protection, auto swap and endurance/range display.
- Display player speed and altitude.
- Show health of mobs.
- Projectiles red outline.
- Settings menu (press K to open).

## Planned

- Above ground altitude display change color
- Expand configurability especially aimbot, kill aura
- Improve projectile dodging (predict, avoid edges)
- Kill aura only on player attack or being attacked, decouple client & server rotation
- Elytra autopilot:
	- Per block pathing instead of heightmap highest block
	- Fly climbing pattern if boxed in
	- Descend gradually before reaching destination
	- Fly landing instead of disengaging on unable to escape terrain?
	- Support fireworks?
	- Warnings?
- Fix:
	- Elytra durability protection should be cumulative, not single
	- Range estimate isn't resetting on landing
	- Autopilot movement should stop when paused singleplayer, and should work when window unfocused (both currently opposite)
	- Damage auto kick not working when window unfocused
	- Low altitude warning should count as autopilot disconnect
	- Flight director turn even in manual flight?
- Update pictures, add logo
- Vertical speed indicator? Move speed to left?
- Spear elytra
- Mace fall
- Auto eat & totem
- Activate elytra if fall (could be annoying)
- Creeper explosion avoidance
- Warning sounds
- More settings for enabling/disabling & finer control
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

Built for Minecraft 26.1.2 (unobfuscated, Mojang names) on Fabric. Client-side only. Cheats are off by default; everything is switched on and off in the settings screen (K), and the switches are saved in `config/kine.json`.

### Elytra autopilot

No fireworks. The autopilot flies a porpoise: it dives to build speed, then pulls up to trade that speed for height, which the elytra physics rewards. Its two profiles were tuned in a model of the game's elytra code (`LivingEntity.updateFallFlyingMovement`): a climb profile that pulls up at a set speed, and an altitude-hold profile that dives down to the target altitude before each pull-up, so a descent from any height ends in one dive. Magenta flight director bars show the commanded pitch and heading; the autopilot (J) follows them with rate-limited control every frame, and A/D move a heading bug. Moving the mouse hands control back, and if the autopilot drops out in flight and you don't take over within 1.5 s, you are disconnected.

The flight path rails, drawn in the world, and the trace on the side-on profile map show the path the flight model predicts, turns included.

### Terrain avoidance

On by default. While engaged, the autopilot rolls the flight model forward over the loaded heightmap every tick: it flies straight when that path clears, raises the porpoise's low point to climb over terrain, turns toward a clear heading, and reverses hard from a wall inside its turn radius. When reversing doesn't get past a wall it hands control back. It also arms only when it finds a path. Terrain is the heightmap's top surface, so overhangs and caves count as solid.

### Navigation and landing

N opens the nav screen: *Selected* holds a compass heading, *Managed* flies to an X/Z coordinate, *Off* does neither. A green beam marks a managed target (drawn over terrain, like Baritone's), and the HUD shows distance and ETA. Within 24 blocks of the target the autopilot picks the nearest safe column (no lava, fire, cactus, magma, powder snow, berry bushes, campfires, wither roses or water), circles it and descends, steeply when high and flaring near the ground.

### Range and endurance

While you wear an elytra, endurance adds up the flight time of every elytra you carry (worn, inventory, offhand; not in shulker boxes), each scaled by Unbreaking, less a 5% contingency and a reserve for gliding down from your height. Range is endurance times your average ground speed over whole porpoise cycles. Range and ETA count down with the clock and drift toward the raw estimate, so they don't jump with each durability point.

### Durability failsafe and auto-swap

Below the reserve you get a land-now warning and a hot-swap to a spare that clears it; with 3 durability left, to any spare with more life. On autopilot it disconnects you after 5 s without input, or at once at 2 durability. Swaps are ordinary inventory clicks, made only with no container open.

### Combat

The targeting reticle simulates the readied projectile tick by tick (gravity, drag, your own motion) and rings where it lands. The aimbot finds the launch pitch from a closed-form start and secant steps and the yaw allowing for your motion, leading moving targets. The kill aura attacks hostile mobs, and neutral mobs such as endermen after they hit you (piglins also while you wear no gold), and chooses between waiting for a full charge, spamming a crowd and sweeping a cluster; it turns the player toward the target for the one tick of the attack and back, so the server sees the turn and your view doesn't. Auto weapon holds the best sword or axe by damage per second. Projectile dodge presses movement keys to step off the line of projectiles whose flight, with gravity and drag, passes close. Projectiles glow red through walls.

### Other features

Water-bucket clutch, AFK damage protection (logs you out if you take damage after 15 s with no input), fall prevention (sneaks at the edge of drops of more than 3 blocks), block-breaking progress, speed readouts, the radio altimeter, the flight path vector, and mob health and names.

### A note on anti-cheat

HUD readouts are invisible to servers. Everything the server receives is something a precisely controlled vanilla client could send: turns in whole mouse steps, attacks and item uses aimed by the crosshair at the rotation the server is sent, movement from pressed keys, and inventory clicks between ticks followed by the close a real inventory sends. That makes the mod hard to tell from a fast, precise player, not from one: the aimbot and the kill aura are still the features most likely to get you banned. Use accordingly.

---

kinematics
