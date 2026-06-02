
# Kine

TODO add photos

Minecraft utility mod with elytra autopilot, aimbot, movement tweaks, and HUD displays.

Make sure to install [Fabric API](https://modrinth.com/mod/fabric-api).

\*heavy assistance from Opus 4.8

## Current Features

- **Fly long distances without fireworks.**
	- Start high enough, then press P to engage autopilot.
- **Aimbot and targeting reticles for projectiles.**
	- Enable in settings (press K to open).
- **Dodge arrows and other projectiles.**
- **Fall protection.**
	- The mod will prevent you from walking off ledges greater than 3 blocks high; you can override it by jumping then walking off.
- Block breaking percentage and time left indicator.
- Elytra low durability and collision protections, auto swap and endurance/range display.
- Display player speed and altitude.
- Show health of mobs.
- Settings menu (press K to open).

## Planned

- Collision detection for aimbot
- Support multiple versions & forge

## Long Term

- Pathfinding
- Combat

## Technical Details*

\*written by Opus 4.8

Built for Minecraft 26.1.2 — the first unobfuscated release — against Mojang mappings (not Yarn), on the Fabric loader. Client-side only; every cheat is off by default and toggled from the settings menu (press K). All gameplay state is read live from the client each tick and verified against the actual game classes, so the numbers below are the real in-game behaviour rather than estimates.

### Elytra autopilot

There are no fireworks involved. The autopilot flies a fixed pitch oscillation — a "porpoise" — that trades altitude and speed back and forth to sustain roughly level cruise:

1. Nose down to a shallow dive (~34°) to build airspeed.
2. When horizontal speed crosses ~44 m/s, snap the nose up hard (~48° above horizon) and hold briefly. Elytra physics amplify the speed-to-altitude conversion, so the climb more than pays back the dive.
3. Ease the nose back down to the dive angle over about a second, and repeat.

A flight director draws the commanded pitch as a moving bar; the autopilot (P to engage) just follows it, smoothing the pitch changes and capping how fast it can move the nose so it never snaps violently. A/D nudge the heading, and any real mouse movement instantly hands control back to you.

Engagement uses hysteresis so the dive can't accidentally disarm it: you need clear air at least ~120 blocks below to arm, and it stays armed until you drop below ~48 blocks. Below that, a "TOO LOW TO ACTIVATE AUTOPILOT" advisory shows instead. Altitude comes from a downward block scan (a radio altimeter), shown on the HUD as height above ground rather than sea level.

### Range and endurance

While you're wearing an elytra the HUD estimates how much longer you can stay up and how far you can go. Endurance counts the durability left on the worn wing plus every spare you can actually reach in the air — inventory and offhand, but not shulker boxes (you'd have to land) — and scales each one by its Unbreaking level, which multiplies effective flight time. XP bottles and Mending aren't counted: there's no XP source in the air yet.

Reserves are held back the way aviation fuel planning does it: a 5% contingency, plus a final reserve sized to glide down safely from your current altitude (bigger the higher you are). The estimate hits zero right as the durability failsafe would trigger. Range is endurance times your *own* recent average flight speed — a rolling mean of measured horizontal velocity — so it tracks how you actually fly instead of a theoretical number, and stays blank until it has enough samples to be stable.

### Elytra durability failsafe and auto-swap

When the worn wing drops below the safe-glide reserve for your altitude, you get a land-immediately warning, and the mod tries to save the flight with a two-tier auto-swap:

- **At the warning:** if you have a spare elytra fresh enough to clear the warning outright, it hot-swaps it on automatically. If your best spare wouldn't clear it, nothing happens — the warning deliberately stays up so you land, rather than shuffling you onto another nearly-dead wing.
- **About to break:** once durability is critically low, it swaps to *any* fresher spare you have left, just to keep you airborne. The only priority left is not dying.

Swaps go through real container-click packets (the same path the inventory screen uses), and only run with no screen open and an empty cursor so they can't strand an item. If there's nothing left to swap to, then on autopilot — if you don't take control within five seconds — it disconnects you, logging out before the wing breaks so you don't fall to your death. Flying manually it only ever warns.

### Terrain crash protection

A movement hook watches for the ground, ceilings, and walls along your trajectory and bleeds off only the component of velocity heading *into* a surface — it never steers or redirects you, so it can't itself cause a crash. Ground approaches get flared to a survivable descent rate and fall damage is cushioned; walls are caught along the actual descending path rather than just straight ahead. It reduces impacts but can't guarantee zero damage, since damage is ultimately server-authoritative.

### Combat and movement

Projectile targeting predicts where an incoming or outgoing projectile is going and can either draw a lead reticle or aim for you (aimbot, off by default), with optional glowing outlines on projectiles. A separate dodge feature reads each incoming projectile's closest approach and nudges you laterally out of the way. Fall prevention stops you walking off drops taller than three blocks unless you jump first.

### A note on anti-cheat

The HUD readouts are invisible to servers. The movement and inventory features — autopilot, fall prevention, crash protection, dodge, auto-swap — send normal-looking inputs and packets and are lower risk, but a strict anti-cheat could still flag things like an armor swap mid-flight. The aimbot is the one feature that's reliably bannable. Use accordingly.

---

kinematics
