
# Kine

<p align="center">
<table>
<tr>
<td><img alt="Screenshot of elytra autopilot HUD" src="https://github.com/user-attachments/assets/aa474395-50ae-4a19-839d-e4dad5192867"></td>
<td><img alt="Screenshot of aimbot targeting" src="https://github.com/user-attachments/assets/704d95d7-d264-4377-90c2-b2a50971053b"></td>
</tr>
</table>
</p>

Minecraft elytra autopilot from takeoff to landing, walking pathfinder with parkour, aimbot, kill and bow auras and combat movement, safety features, and HUD displays.

This mod only uses vanilla legal mechanics, the only "cheating" is having a computer instead of a monkey at the controls (verified against [Grim](https://github.com/GrimAnticheat/Grim)).

<p align="center">
	<a href="https://github.com/rayrwang/kine/releases">Download the Mod</a>
</p>

Make sure to install [Fabric API](https://modrinth.com/mod/fabric-api).

## Current Features

- **Fly long distances without fireworks.**
	- Take off from ground with rockets, or start high enough and press `J` to engage autopilot.
	- Terrain aware autopilot with fully optimized flight profile.
	- Collision avoidance: if the path you are on would hurt you, by hand or on the autopilot, it takes over at the last moment, turns or pulls up into clear air, then hands back.
	- Press `N` to enter a destination, with auto landing.
	- Or look at a spot while gliding and left click to land there.
	- Visualizations: flight director bars, path guiderails, flight path vector, and side profile map.
- **Walking pathfinder.**
	- Press `N` and enter a destination (X, Z, optional Y) in the Walk tab; it walks you there while you look around freely.
	- Plans a straight, taut route, then picks your keys each tick by simulating the game's own movement: sprint-jumps, swims (coming up for air), climbs out of water.
	- Parkour (off by default): gap jumps up to 4 blocks, diagonals, and neos.
	- Shows the distance and arrival time; an unreachable destination gets you as near as it can.
- **Aimbot and targeting reticles for weapons.**
	- Enable in settings (press `K` to open).
	- Shows the chance the shot hits, and aims at the point on the target most likely to hit.
	- Marks targets projectiles can't hurt (endermen, breezes, raised shields, etc.).
- **Water bucket clutch automatically** (survival only).
- **Auto totem**: keeps a totem of undying in your offhand.
- **Auto eat**: eats the best food you carry once it fits without waste, sooner when hurt.
- **Kill aura.**
	- Times each swing for the most damage by weapon and crowd, hits mobs about to reach you first, and leaves neutral mobs alone until they hit you.
- **Bow aura.**
	- Shoots hostile mobs with a bow, crossbow or Loyalty trident, picking the one that threatens you most for the shots it takes, only when the shot is likely to hit and nothing else is near its path; the kill aura goes first.
	- Held only (the weapon in your hand) or On (switches to whichever deals the most damage per second, throwing tridents while the bow shoots); a range slider.
- **Combat movement.**
	- With most hostile mobs, moves you around the fight between the kill aura's swings so you take less damage: out of reach, off projectile lines, clear of creepers about to blow; only while you press no movement key.
	- A slider from survive to kill fast.
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
- Freecam (`F4`): fly the camera on its own while your player stays put.
- Settings menu (press `K` to open); every feature can be switched off, by section, or all at once.

## Planned

- Fix:
	- Elytra:
		- Flight director turn even in manual flight? (terrain avoidance's turns; the bar already follows your nav heading)
		- Emergency landing with nothing safe within 32 blocks goes straight down, even onto lava
		- AFK damage kick can log you out mid-air during a night hold (phantoms)
		- Takeoff's last rocket can overshoot the cruise altitude
		- Takeoff stalls if a screen (e.g. chat) opens mid-climb: no more rockets, nose held up (collision avoidance now catches the stall)
		- Terrain avoidance passes wall corners with no margin to the side (collision avoidance catches the clip)
		- Check terrain avoidance and hold steering for the per-frame stutter fixed in the autopilot
	- Walking pathfinder:
		- Route tightening over water
		- Occasional fall damage from the movement search
		- Elytra hops unreliable
		- Rarely stuck at the top of steep drops
		- Long-range search display needs rework
		- Rails still a bit rough
	- Kill aura turning to hit while the pathfinder walks puts the walk's keys off its heading (up to 22.5 degrees, sprint drops)
	- Aimbot:
		- A held target out of reach blocks the others until the mouse moves
		- Check auto aim's turns for the same stutter
	- Kine's world drawing (rails, pillars, route lines) with Iris shaders
- Elytra:
	- Autopilot:
		- Per block pathing instead of heightmap highest block
		- Fly climbing pattern if boxed in
		- Descend gradually before reaching destination
		- Fly on, land, or rockets, instead of tripping off after a collision avoidance rescue?
		- Support fireworks? (rockets in flight, not only the takeoff)
		- Warnings?
		- Faster porpoise profile (law2)?
	- Takeoff and landing:
		- Suicide burn: land when the durability left is less than the landing needs
		- Better landing from low height
		- Terrain aware glide landing, shared with the takeoff; pole landings
		- Takeoff from under a block
		- Takeoff levelling off below 350 for short trips or flat ground
	- Activate elytra if fall, instead of the water bucket (could be annoying)
	- Range and endurance counting height less
	- Nav screen: a map in Managed mode; saved per world and dimension, with waypoints and a home
- Walking pathfinder:
	- Breaking and placing blocks, bridging included, preferring common, easy to get blocks
	- Gait setting (walk, sprint, sprint-jump, elytra hops), allowed faster when chased
	- Setting for how much damage the walk may take
	- Neo port and the triple neo for long routes
	- Parkour risk setting
	- Opening doors
	- Combat aware routes, and pausing a walk for a fight
	- Handing off between walking and elytra flight
	- Saved map of seen chunks, for routes past render distance
	- Destination targeting by right clicking with empty hand (uses Baritone if available, otherwise fall back to native pathfinding)
	- Benchmarks against Baritone on random seeds, and a comparison video
	- Publish the parkour test world
- Combat:
	- Kill aura only on player attack or being attacked
	- Expand configurability especially aimbot, kill aura
	- Combat movement more cautious than 0, and better against brutes and ravagers
	- Creeper explosion avoidance
	- Auto eat working with the kill aura and combat movement
	- Spear elytra (rails, repositioning before attacking)
	- Mace fall
	- Aimbot:
		- Collision detection
		- Zoomed in targeting view
		- Ender pearl target selection & protection
		- Finer selection options: priority, whitelist
		- Hit chance for mobs moving erratically
		- Arrows slowing in water
		- Better aim at mobs in flight
- HUD:
	- Above ground altitude display change color
	- Vertical speed indicator? Move speed to left?
	- Warning sounds
	- Entity highlights?
- General:
	- Defaults reviewed, each setting's ban risk in its description, settings ordered by risk
	- Black box log behind a setting; kine's log lines switchable
	- User guide / help: controls & displays list and explanations
	- Update pictures, add logo
	- Support multiple versions & forge (and a backport for 2b2t and 6b6t)

## Technical Details

Built for Minecraft 26.1.2 (unobfuscated, Mojang names) on Fabric. Client-side only. Cheats are off by default; everything is switched on and off in the settings screen (`K`), one feature at a time, a section at a time, or all at once, and the elytra and speed displays can show always, only while you wear an elytra, only in flight (gliding or taking off), or never. The choices are saved in `config/kine.json`.

### Elytra autopilot

No fireworks in flight (rockets only for the takeoff, below). The autopilot flies a porpoise: it dives to build speed, levels off at the bottom so the elytra turns the sink into forward speed, then pulls up hard to trade that speed for height, eases the nose back down and glides before the next dive. Its two profiles were searched for in a model of the game's elytra code (`LivingEntity.updateFallFlyingMovement`): a climb profile that pulls up at a set speed (about 1.5 m/s of climb at 22.5 m/s), and an altitude-hold profile that dives down to the target altitude before each pull-up (about 32 m/s), so a descent from any height ends in one dive. The pitch moves at up to 15 degrees a tick, inside a fast human mouse flick. Magenta flight director bars show the commanded pitch and heading; the autopilot (`J`) works out its command once a tick, as the model steps it, and turns the view its share of the way there every frame, so it moves smoothly and the server gets the planned rotation each tick (stopping while singleplayer is paused); `A`/`D` move a heading bug. Moving the mouse hands control back, and if the autopilot drops out in flight and you don't take over within 1.5 s, you are disconnected.

The flight path rails, drawn in the world, and the trace on the side-on profile map show the path the flight model predicts, turns included.

### Rocket takeoff

Standing with an elytra on and rockets in hand, the warning row offers a takeoff in green (LEFT CLICK TO TAKE OFF), or says in red why it can't (blocked above, not enough rockets, elytra too worn, rockets with firework stars, standing in water, autopilot switched off). A left click looks straight up, jumps, opens the elytra and fires a rocket. It climbs straight up until a leaning climb would clear the terrain, then leans to 70 degrees, which climbs about as fast and builds speed. As each rocket burns out it fires another unless the autopilot, taking over in altitude hold, would hold the cruise without it; then the autopilot engages. The rockets counted are all those you carry: when the stack in your hand runs out, another is selected from the hotbar, or moved into your hand from the inventory. Any mouse movement or movement key stops it. The rails show its climb once it starts.

### Terrain avoidance

On by default. While engaged, the autopilot rolls the flight model forward over the loaded heightmap, replanning every half second: it flies straight when that path clears, raises the porpoise's low point to climb over terrain, and turns toward a clear heading; boxed in, it turns toward the heading with the most room, and collision avoidance (below) is the emergency path. It also arms only when it finds a path. Terrain is the heightmap's top surface, so overhangs and caves count as solid.

### Collision avoidance

On by default, in survival and adventure, whenever you glide: by hand, on the autopilot, in a takeoff or a landing. Each tick, on a worker thread, it flies the path you will take if nothing changes, in an exact copy of the game's movement code over a copy of the blocks within 160 blocks (their real shapes: overhangs, arches, canopies and the Nether roof are what they are), with the game's damage rules: a wall hit while gliding (the horizontal speed lost times 10, less 3, in blocks a tick), a landing's fall (cut by hay, honey, beds, slime, powder snow and water, and to a block while sinking slowly), and blocks that hurt to touch. Nothing changing means your look held still when flying by hand; the autopilot's own laws on the autopilot (the porpoise, terrain avoidance, the hold, a landing's spiral); a takeoff's own climb and rockets, with none while a screen such as chat is open. If that path takes damage, it waits for the last moment a rescue still works with a second to spare: it acts once a second's freeze of the controls, a tick from now, would leave no rescue. Then it engages the autopilot (past its arming gate), flies the best rescue a search finds, and shows a red TERRAIN. The search tries sequences of looks, at most 28 degrees a tick, starting from named moves (turning back either way, level, climbing or diving; other turns; flaring; diving then pulling out) and from shapes it found best in tests, and scores them by damage first, then by how the rescue ends: on the way back along the air you have just flown through, with open air ahead, at the height you flew, with speed. Once the rescue is done and a hands-off glide from there stays clear for 4 s, it hands back with AUTOPILOT OFF and the 1.5 s disconnect, unless you take over. A small bump of the mouse is taken back; a real turn, a movement key or `J` takes over at once, and it stays out of that danger until your path is clear again. Flying by hand, it acts only after a cooldown with no input from you (mouse or keys; a slider, 1 s by default), so it leaves you alone while you fly close to terrain yourself; on the autopilot it always acts.

### Navigation and landing

`N` opens the nav screen: *Selected* holds a compass heading, *Managed* flies to an X/Z coordinate, *Off* does neither. A green pillar rises from the ground at a managed target, drawn in the world so terrain hides it, with a faint copy through terrain; its top stays at least 45 degrees above your eye, so it looks as tall from far away as up close. The HUD shows distance and ETA. Within 24 blocks of the target the autopilot picks the nearest safe column (no lava, fire, cactus, magma, powder snow, berry bushes, campfires, wither roses or water) and lands on it.

Aimed landing: while gliding by hand, a reticle marks a spot you could land on, the flat surface you look at, or for a wall or pole the nearer of its top and the ground at its foot. It shows only for a safe spot in a loaded chunk up to 1024 blocks away that the landing can reach within 1.5 blocks without damage. Left click (LEFT CLICK TO LAND) and the autopilot lands there, with the landing block's top face outlined in green; afterwards your trip's destination comes back.

The landing glides toward the spot at 5 degrees nose down, spirals down around it at 60 degrees, brakes nose up from 60 blocks above, spirals again at 25 degrees to 10 above, then settles onto it nose up at about 3 m/s. A 1 s freeze of the controls anywhere costs no health; a small bump of the mouse is taken back, a larger move hands control back. Creative flight cancels it.

### Range and endurance

Endurance (END) is the expected gliding time on every elytra you carry (worn, inventory, offhand; not in shulker boxes): each one's durability above 3 points, one point a second times Unbreaking + 1 on average (elytra take the tool rule for Unbreaking). It holds back a landing reserve on the last elytra to be flown: one point for each second the autopilot's landing would take from your height down to the bottom of the world, at worst a point a second, read from a curve precomputed with the landing law. So END reaching 0 means the landing must start now. An Unbreakable elytra shows ∞. Range (RNG) is endurance times your average ground speed over whole porpoise cycles, less 10%, and starts afresh each flight; navigation's INSUFFICIENT DURABILITY compares it with the distance left. END, RNG and ETA count down with the clock and drift toward the raw estimate, so they don't jump with each durability point. END turns amber in its last minute and red in its last 20 s.

Beside them, the firework count is the burn of every rocket you carry without firework stars (inventory and offhand), counted in flight-duration-1 rockets, with a rocket icon. While a rocket boosts you, a burn bar shows how far through it you are: bright up to the shortest burn the rocket can have, then fading with the chance it is still burning, since the server rolls each rocket's length and never sends it.

### Durability failsafe and auto-swap

An elytra counts as spent at 3 durability or less (the game itself stops gliding at 1). While you glide, by hand or on autopilot, a spent elytra is swapped for the spare with the most durability left, and after a swap onto the last usable one LAST ELYTRA shows for a few seconds. When endurance reaches 0, the autopilot lands near where you are with its landing law, on the nearest dry, safe column within 32 blocks, or on water only if there is none, under a red warning. Flying by hand you get a red warning instead, and if you give no input for 5 s (input as the AFK protection counts it) you are disconnected. Both warnings show the gliding time the reserve still holds, on average. A rocket takeoff is offered only if endurance would still be at least 60 s at the cruise. Swaps are inventory clicks made as a player makes them: one tick with the inventory opened (every key up), the clicks, then the close, only with no container open.

### Walking pathfinder

The nav screen's Walk tab (`N`) has its own destination for each dimension: an X and Z, and an optional Y naming the floor to reach (left blank, any floor of that column). Go plans a route on foot over a table of every loaded column's floors: A* with straight, diagonal and knight's moves (the knight's moves cut the worst detour against the straight line from 8.2% to 2.7%), costs in ticks fitted from the movement search's own runs (Baritone's with the movement search off), then the grid path pulled taut by straight-walk checks against the blocks' own shapes, keeping a set margin from walls, hazards and drops. Its moves: walking, sprinting above 6 food, steps up slabs and stairs, one-block jumps, drops of up to 3 (any height into water), ladders and vines, swimming; it never breaks or places blocks, and treats doors and gates as walls. A destination beyond the loaded area is headed for where the straight line leaves the loaded area, replanning as chunks load. The search runs on a worker thread within a time budget set by this machine's measured speed: a quick pass first, so you start walking at once, then better passes while the time lasts. If the destination can't be reached, it walks to the reachable floor nearest it and stops there ("stopped as near as it gets").

Along the route the movement search (on by default) chooses your keys. Every tick, on a worker thread, it tries short runs of movement keys (eleven headings; walking, sprinting, sprint-jumping, jumping or sneaking; held a few ticks and chained) in an exact copy of the game's movement code, water, climbing and gliding included, and presses the first tick of the fastest safe one. So it sprint-jumps on open ground, swims under the surface, climbs out at a bank, and keeps enough air to come up before it would drown, sprint-swimming with its eyes just out of the water on long crossings. With no route to a destination within 48 blocks, it searches toward it on its own. Elytra hops (off by default, since gliding wears the elytra) let it glide off ledges and over gaps.

Parkour (off by default: a jump that falls short lands in the gap) adds gap jumps to the route, each measured in the movement copy: straight across up to 4 empty blocks (the longest from a block of run-up), diagonals to 3 by 3, the knight's and other lines between, one up across up to 3, one down. Near a jump the search also tries any heading to the degree and the jump on any tick, turning toward the landing in the air. Tricks a route can't hold, such as a neo round a wall, come from a jump search in the copy for the run, jump and turn that land. When a far destination's route stops short of a crossing, it walks without a route to the next floor past it (within 8 blocks) and plans on from there, ending as near as it gets after three failed tries in a row. Air above a column's highest floor counts as open sky, so it sees gaps over drops of any depth.

It walks by pressing your movement keys. The camera stays yours; only the server sees you face where you walk, turned at most 30 degrees a tick. Pressing a movement key, the tab's Pause or arriving pauses it and hands control back, with the route still drawn so you can walk back along it; Cancel clears it, and a new destination cancels the old route. The route draws as a magenta line between rails near you (a fixed width apart, on the surface of water and snow), the destination as a green pillar, and the movement search's tries as thin lines of the feet, the chosen run in gold; replaying the route search in the world is a setting, off by default. While it walks, the HUD's nav block shows the distance left along the route and the arrival time in cyan, as the Walk tab does; on the ground it takes the flight readout's place. The arrival time is the plan's time left, corrected by how the walk has gone so far, and counts hunger stopping the sprint. Fall prevention lets the walk's own steps into water through.

### Combat

The targeting reticle simulates the readied projectile tick by tick (gravity, drag, your own motion) and rings where it lands, meeting moving mobs where they will be. Beside the crosshair it shows the chance the shot hits, from the game's own launch spread over a fixed set of rolls, with blocks in the way counted. Auto aim picks up the target the reticle covers or the one the view is nearest to aiming at, holds it, and steers for the bow's full draw: the pitch from secant steps with a bracketing fallback, the yaw allowing for your motion, at the point on the target with the best chance, led by the time until the shot reaches the server and the network delay, along the target's course (falling under gravity when it's off the ground). Targets projectiles can't hurt (endermen, breezes, a shut shulker, an armoured or spawning wither, a raised shield) are marked amber with the reason and never picked. The ender dragon is aimed at by its parts, its head first when in view, led along its turning flight. The kill aura attacks hostile mobs, and neutral mobs such as endermen after they hit you (piglins also while you wear no gold, spiders where it is bright, never the warden unprovoked). It hits a mob about to reach you first, whatever the charge, since each hit knocks it back; otherwise it times its swings on the game's damage rules: one mob, a full charge each time; a crowd, a quick hit on every mob as soon as it can take one, then a charged hit, in cycles of at least the 10 ticks a struck mob ignores a second full hit (a dynamic program over the charge and each mob's 10 ticks found that shape best); a sword's full-charge sweep when it catches enough of the crowd to pay more; and a crit when a jump puts one a few ticks away. The rotation it sends the server is its own, turned from your view at most 30 degrees a tick (no faster than a quick flick) and undone before each frame, so your view doesn't move; while it is turned, your movement keys are swapped for the ones that walk where you meant, and your clicks wait for it to come back. Auto weapon holds the best sword or axe by damage per second.

The bow aura (Off, Held only or On; off by default) shoots the mobs the kill aura would attack, at range: within its range (a slider, 8 to 128 blocks, 48 by default), in sight, never your pets or a mob the shot can't hurt, and only with a hit chance of at least 30%. The kill aura goes first: while it has a mob in reach, or one that could walk into reach before the bow aura's shot is off (the ticks the shot still needs and two more, at the mob's closing speed or its walking pace, whichever is faster), it takes no shot; a bow's or trident's draw under way is loosed at once if the shot would still likely hit and the mob isn't yet in reach, else cancelled by scrolling the hotbar away and back, a crossbow's load is let go, your slot comes back, and the rotation it sends turns toward the mob coming, so the kill aura's first swing doesn't wait for a turn. It also stands down while combat movement fights (it works at melee range), while the pathfinder walks you, in elytra flight (and in the air with an elytra on, so its rotation is back on your view before a glide opens), off your feet, while you click or use an item, and for a second after your last click. Among the mobs it can hit it shoots first the one with the most threat to you times the chance the shot hits, over the shots it takes to kill (the threat: a ranged mob's damage rate while you are in its range, a creeper's blast over the time until it could go off, a melee mob's damage rate weighted by how soon it reaches you), counting its own shots still in the air and the 10 ticks a struck mob takes only a bigger hit's excess. Held only shoots with the bow, crossbow or trident in your selected slot and never changes your slot. On picks, for each shot, the weapon with the most damage for the ticks it keeps you busy times its hit chance on that mob, from the game's rules: a bow's full draw (23 ticks with the release and the server's word; Power, Flame), a crossbow's load (25 ticks, 5 less a level of Quick Charge, and 6; none when it is loaded already, so a loaded crossbow fires first), a Loyalty trident's throw (13 ticks, 8 damage, Impaling on aquatic mobs; it flies back by itself, so the bow shoots meanwhile, and several tridents go one after another); Infinity and durability break near ties. It selects that weapon (a number key), or moves it up from your inventory (into an empty hotbar slot, else in place of the largest stack), and gives your slot back when it is done. A bow or trident is held for its draw (20 ticks for a bow's full power and crit, 10 for a trident) while the rotation it sends the server turns toward the shot a release next tick would fire (the release carries no rotation; the server shoots along the last one sent), led as auto aim leads, at the point with the best chance; it lets go once the shot as aimed hits at least half the time. A crossbow is loaded by holding the use key until the game says it is loaded, then fired by a press along that tick's rotation, which the use packet carries; never with firework rockets, and Multishot's side arrows and Piercing's way past the mob are kept clear of bystanders too. A trident without Loyalty is never thrown (it would be lost), nor a Riptide one, nor a Channeling one in a thunderstorm. Like the kill aura's, the rotation it sends is its own, so your view stays put. Every use starts only when the crosshair at that rotation rests on nothing or a hostile, so no block, villager or pet is used instead, and waits for the server's word that the last use has ended, which otherwise, arriving after the new one began, would stop it.

Combat movement fights alongside the kill aura with most hostile mobs, each modelled from its own code: the zombie family (drowned with tridents too), skeletons and wither skeletons, creepers, spiders, pillagers and crossbow piglins, witches and other melee mobs, slimes and magma cubes, blazes, phantoms, breezes, evokers and vexes. Near a mob it doesn't model (ghasts, guardians, shulkers) or a warden, it lets go. Each tick a worker thread searches short plans of moves (sixteen compass headings, straight away from the nearest mob or the crowd's middle, each walked or sprinted, or standing still) in an exact copy of the game's movement code, with the mobs, their projectiles and the kill aura's own swing timing, and takes the first step of the best one by pressing movement keys. A slider weighs your health against a quick kill: 0 (the default) keeps out of reach of mobs about to attack and never takes a hit to end a fight sooner; 100 closes in to end fights fastest. With a creeper it goes in to hit and back out past where the fuse stops before it can blow; while you run from a lit creeper the kill aura lets it be and turns your sent facing toward the way out, so you can sprint. It moves you only while you press no movement key, avoids drops and lava, and stands down while the pathfinder walks you. The kill aura, the bow aura, auto weapon, combat movement and projectile dodge act only in survival and adventure.

### Other features

Water-bucket clutch (survival only; it turns toward where you will land through the fall, places the water in reach and scoops it back up), auto totem (when your offhand holds no totem and you carry one, it is swapped in through an inventory opening like a player's; whatever was in the offhand takes its slot), auto eat (the best food you carry once all its hunger and saturation fit, anything that fills you while hearts are missing, golden apples only then, bad food only as a last resort; it waits while you fight and backs off when you click), AFK damage protection (logs you out if you take damage after 15 s with no input, focused or not), fall prevention (sneaks at the edge of drops of more than 3 blocks; jumping gets past it; survival and adventure only), block-breaking progress, speed readouts, the radio altimeter, the flight path vector, and mob health and names. The Visibility settings are drawing only: fullbright lights the lightmap from a white base, the fog switches clear each kind of fog the game draws (and draw the sky in lava and powder snow), and the effect and overlay switches remove Darkness, Blindness and the full-screen overlays; their switches open in two folders on the right of the settings screen. Freecam (`F4`, switched on in the Visibility settings) flies the camera through blocks while your player stands still: your movement keys and mouse move the camera, attacks and item uses are blocked, caves are drawn as in spectator mode, and kine's features keep acting on your player.

### A note on anti-cheat

HUD readouts are invisible to servers. Everything the server receives is something a precisely controlled vanilla client could send: turns in whole mouse steps, attacks and item uses aimed by the crosshair at the rotation the server is sent, movement from pressed keys, and inventory clicks after a tick with every key let go, as opening a real inventory does, followed by the close it sends. That makes the mod hard to tell from a fast, precise player, not from one: the aimbot and the kill and bow auras are still the features most likely to get you banned. Use accordingly.

---

kinematics
