
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
- **Kill aura.**
	- Takes into account number of enemies (spam for more, cooldown for fewer).
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

## Technical Details*

\*written by Opus 4.8

Built for Minecraft 26.1.2 — the first unobfuscated release — against Mojang mappings on Fabric. Client-side only; every cheat is off by default and toggled from the settings menu (K). State is read live from the client each tick.

### Elytra autopilot

No fireworks. The autopilot flies a "porpoise" — diving to build airspeed, then snapping the nose up to trade that speed back for altitude (elytra physics make the climb outvalue the dive) — to sustain roughly level cruise or to descend to a set altitude. A flight director draws the commanded pitch, and the commanded heading while steering, as bars; the autopilot (J to engage) follows them with smoothed, rate-limited control so nothing snaps. A/D set a heading bug it holds and tracks; any real mouse movement hands control straight back. Engagement is hysteretic — enough clear air below to arm, a "TOO LOW" advisory if you sink too far — and altitude comes from a downward block scan (radio altimeter), shown as height above ground.

Two displays render the trajectory the flight model predicts: guiderails laid along it in the world (depth-sorted against terrain, so they pass behind hills and clouds correctly), and a side-profile cutaway plotting terrain and path out to render distance.

### Terrain avoidance (opt-in)

Turned on in settings, the autopilot rolls the real flight model forward each tick across the loaded heightmap and steers on the result — a receding-horizon search that flies straight when it can, climbs when the way ahead is clear above, turns toward open air when it isn't, and hard-banks from an imminent wall. With no recoverable path it hands control back rather than flying you into the ground. The same model feeds the guiderails, so the drawn path is the predicted one.

### Navigation and landing

Press N to set a course: *Selected* holds a compass heading, *Managed* flies to an X/Z coordinate, or *Off*. In Managed mode a translucent green beam marks the target column, the director and autopilot steer to it, and the HUD shows distance and ETA. On arrival (~24 blocks) a landing program picks the nearest safe touchdown column — skipping lava, fire, cactus, magma, powder snow — orbits it tightly instead of overshooting, and descends on an altitude-scheduled pitch: near-vertical when high, easing to a gentle flare by ~12 blocks up. Flying back out cancels it; touchdown ends it.

### Range and endurance

While wearing an elytra the HUD estimates flight time and reach. Endurance sums the worn wing plus every spare you can reach airborne (inventory and offhand, not shulkers), each scaled by Unbreaking, minus aviation-style reserves: a 5% contingency plus a final reserve sized to glide down from your current altitude — so it reads zero just as the durability failsafe would fire. Range is endurance × measured cruise speed (a rolling mean over whole porpoise cycles, so dives and climbs cancel). Both range and ETA are eased into a steady decline rather than left to twitch with each durability step, without the optimism a naive average would add.

### Durability failsafe and auto-swap

When the worn wing drops below the safe-glide reserve for your altitude you get a land-now warning and a two-tier hot-swap: first to a spare with enough flight time to clear the warning outright (and nothing if none would, so you land rather than shuffle onto another near-dead wing); then, once critically low, to any spare with more life left. Swaps use real container packets and only run with no screen open and an empty cursor. With nothing left and the autopilot flying, it disconnects you after five seconds of no input — out before the wing breaks. Manual flight only warns.

### Terrain crash protection

A movement hook bleeds off only the velocity heading *into* a surface — ground, ceiling, or wall along your path — and never steers, so it can't itself cause a crash. Ground approaches are flared to a survivable descent rate and fall damage cushioned. It reduces impacts but can't guarantee zero, since damage is server-authoritative.

### Water-bucket clutch

An automatic MLG. While falling hard enough to take damage it sweeps your hitbox against real block collision shapes to find the highest surface you'll actually land on, places a water source there — sneaking, so it sits on top rather than waterlogging — and scoops it back once you're down. A bucket is staged to the hotbar when the fall starts; it skips dimensions where water would evaporate.

### AFK damage protection

A dead-man switch for when you've stepped away: it tracks real input (keys, the mouse turning the view — not the autopilot moving it — or an open screen), and if you take damage after ~15 s idle it logs you out, naming the source. It complements the autopilot's own dead-man switch, covering damage taken while it's still flying you or while you're on foot.

### Combat and movement

Projectile targeting predicts where an incoming or outgoing projectile is going and either draws a lead reticle or aims for you (aimbot, off by default), with optional glowing outlines; a dodge reads each incoming projectile's closest approach and nudges you laterally clear. Fall prevention stops you walking off drops over three blocks unless you jump first.

### A note on anti-cheat

HUD readouts are invisible to servers. The movement and inventory features send normal-looking inputs and packets and are lower risk, though a strict anti-cheat could still flag something like a mid-flight armor swap. The aimbot is the one reliably bannable feature. Use accordingly.

---

kinematics
