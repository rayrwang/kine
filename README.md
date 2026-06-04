
# Kine

<p align="center">
<table>
<tr>
<td><img alt="Screenshot of elytra autopilot HUD" src="https://github.com/user-attachments/assets/2761a9ba-2d2c-406c-8f71-3f0ebf8993e8"></td>
<td><img alt="Screenshot of aimbot targeting" src="https://github.com/user-attachments/assets/704d95d7-d264-4377-90c2-b2a50971053b"></td>
</tr>
</table>
</p>

Minecraft utility mod with elytra autopilot, aimbot, movement tweaks, and HUD displays.

Make sure to install [Fabric API](https://modrinth.com/mod/fabric-api).

\*heavy assistance from Opus 4.8

## Current Features

- **Fly long distances without fireworks.**
	- Start high enough, then press J to engage autopilot.
	- Terrain aware autopilot with fully optimized flight profile.
	- Press N to enter a destination, with auto landing.
	- Visializations: flight director bars, path guiderails, and flight path vector.
- **Aimbot and targeting reticles for weapons.**
	- Enable in settings (press K to open).
- **Water bucket clutch automatically.**
- **Dodge arrows and other projectiles.**
- **AFK failsafes.**
	- Logs you out if you gake damage while AFK, autopilot disconnects, or elytra has low durability.
- **Fall protection.**
	- The mod will prevent you from walking off ledges greater than 3 blocks high; you can override it by jumping then walking off.
- Block breaking percentage and time left indicator.
- Elytra low durability and collision protections, auto swap and endurance/range display.
- Display player speed and altitude.
- Show health of mobs.
- Projectiles red outline.
- Settings menu (press K to open).

## Planned

- Elytra autopilot:
	- Cool side view of path with terrain
	- Per block pathing instead of heightmap highest block
	- Fly climbing pattern if boxed in
	- Descend gradually before reaching destination
	- Fly landing instead of disengaging on unable to escape terrain?
	- Warnings?
- Fix:
	- Elytra durability protection should be cumulative, not single
	- ETA estimate isn't resetting on landing when above anchor
	- Autopilot movement should stop when paused singleplayer
	- Damage auto kick not working when window unfocused
	- Autopilot no longer responds to heading command, or turning with AD keys
	- Collision avoidance throttling sprinting jumping with elytra
	- Autopilot visually move flight director when turning
	- Autopilot resume heading after turn
	- Low altitude warning should count as autopilot disconnect.
- Update readme technical, make more concise; change pictures, add logo
- Vertical speed indicator? Move speed to left?
- Spear elytra
- Mace fall
- Kill aura
	- Takes into account number of enemies (spam for more, cooldown for fewer)
- Activate elytra if fall (could be annoying)
- Warning sounds
- More settings for enabling/disabling & finer control
- User guide / help: controls & displays list and explanations
- Aimbot:
	- Correctness (still unreliable at very high pitch angles)
	- Collision detection
	- Efficiency: solver start with analytical estimate
	- Zoomed in targeting view
	- Ender pearl target selection & protection
	- Finer selection options: priority, whitelist
- Auto combat
- Destination targeting by right clicking with empty hand (uses Baritone if available, otherwise fall back to native pathfinding)
- Support multiple versions & forge

## Technical Details*

\*written by Opus 4.8

Built for Minecraft 26.1.2 — the first unobfuscated release — against Mojang mappings on Fabric. Client-side only; every cheat is off by default and toggled from the settings menu (K). State is read live from the client each tick, so the numbers below are real in-game behaviour.

### Elytra autopilot

No fireworks. The autopilot flies a fixed pitch oscillation — a "porpoise" — trading altitude and speed to sustain roughly level cruise: dive ~34° to build airspeed, then at ~44 m/s snap the nose up ~48° and hold briefly (elytra physics make the climb outvalue the dive), then ease back to the dive angle over ~1 s and repeat.

A flight director draws the commanded pitch as a bar; the autopilot (P to engage) follows it with smoothed, rate-limited pitch so it never snaps. A/D nudge heading; any real mouse movement hands control back instantly. Engagement uses hysteresis — ~120 blocks of clear air below to arm, staying armed until you drop below ~48 (a "TOO LOW" advisory otherwise). Altitude is a downward block scan (radio altimeter), shown as height above ground.

### Navigation and landing

Press N to set a course: *Selected* holds a compass heading (set on a dial), *Managed* flies to an X/Z coordinate (two fields), or *Off*. In Managed mode a translucent green beam marks the target column in the world, the heading director bar and autopilot steer toward it, and the HUD shows distance and ETA.

On arrival (within ~24 blocks) it runs a landing program: it picks the nearest safe touchdown column — skipping lava, fire, cactus, magma, powder snow and the like — orbits it tightly rather than overshooting, and descends on an altitude-scheduled pitch: a near-vertical 80° dive when high, easing to a gentle 10° flare by ~12 blocks up (so 1000 m+ is essentially straight down, soft at the bottom). Flying back out past ~36 blocks cancels it; touching down ends it.

### Range and endurance

While wearing an elytra the HUD estimates how long you can stay up and how far you can go. Endurance sums the worn wing's durability plus every spare you can reach in the air — inventory and offhand, not shulker boxes — each scaled by its Unbreaking level; XP bottles and Mending aren't counted (no XP source aloft yet). Reserves follow aviation fuel planning: a 5% contingency plus a final reserve sized to glide down from your current altitude, taken off a smoothed altitude so the porpoise doesn't make the readouts pulse — the failsafe itself reads true instantaneous height. Endurance hits zero just as that failsafe would trigger.

Range is endurance times your measured cruise speed — a rolling mean over whole porpoise cycles, so the fast dives and slow climbs cancel rather than making it wander — falling back to a nominal figure until a full cycle of samples exists. Range and ETA are both eased into a steady decline rather than left to twitch with each durability step: smoothed, but without the optimistic "reach further than you can" bias a naive average would add.

### Elytra durability failsafe and auto-swap

When the worn wing drops below the safe-glide reserve for your altitude you get a land-immediately warning and a two-tier auto-swap:

- **At the warning:** hot-swap to a spare with enough flight time — durability × Unbreaking — to clear the warning outright. If your best spare wouldn't, nothing happens — the warning stays up so you land, rather than shuffling you onto another near-dead wing.
- **About to break:** once durability is critically low, swap to *any* spare with more flight time left. The only priority left is not dying.

Swaps go through real container-click packets and only run with no screen open and an empty cursor, so they can't strand an item. With nothing left to swap, on autopilot — if you don't take control within five seconds — it disconnects you, logging out before the wing breaks so you don't fall to your death. Flying manually it only warns.

### Terrain crash protection

A movement hook bleeds off only the velocity component heading *into* a surface — ground, ceiling, or wall along your actual descending path — and never steers you, so it can't itself cause a crash. Ground approaches are flared to a survivable descent rate and fall damage cushioned. It reduces impacts but can't guarantee zero damage, since damage is ultimately server-authoritative.

### Water-bucket clutch

An automatic MLG. While you're free-falling hard enough to take damage — including after a broken elytra drops you into a plain fall — it predicts where you'll land and, once the spot is within reach, places a water source in the block you're about to hit, then scoops it back up once you're safely down.

To get it right it sweeps your hitbox footprint against the real block collision shapes to find the *highest* surface you'll actually rest on, so a fence post tucked under an edge still counts, and aims there allowing for horizontal drift. It places while sneaking so the water lands on top of the surface instead of waterlogging it — the sneak is sent through the input packet so the client prediction and the server agree — and the pickup aims back at the exact spot it placed. A water bucket is staged from your inventory into the hotbar the moment the fall starts, and it skips dimensions where water would evaporate.

### AFK damage protection

A dead-man switch for when you've stepped away. It tracks your last real input — a movement or action key, the mouse actually turning the view (the autopilot moving the camera for you doesn't count), or an open screen. If you take any damage after fifteen seconds idle, it logs you out before something finishes you off, naming the source by attacker or damage type — read straight off the client and worded as a precaution rather than an obituary. It complements the autopilot's own dead-man switch (which only fires on disengage), covering damage taken while it's still flying you, or while you're on foot.

### Combat and movement

Projectile targeting predicts where an incoming or outgoing projectile is going and either draws a lead reticle or aims for you (aimbot, off by default), with optional glowing outlines. A dodge feature reads each incoming projectile's closest approach and nudges you laterally clear. Fall prevention stops you walking off drops over three blocks unless you jump first.

### A note on anti-cheat

HUD readouts are invisible to servers. The movement and inventory features — autopilot, fall prevention, crash protection, dodge, auto-swap, the water clutch — send normal-looking inputs and packets and are lower risk, though a strict anti-cheat could still flag something like a mid-flight armor swap. The aimbot is the one feature that's reliably bannable. Use accordingly.

---

kinematics
