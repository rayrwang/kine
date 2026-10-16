# kine on several Minecraft releases

## How it is laid out

- **One source tree.** Feature code is written once, in `src/main/java`, and runs unchanged on every release kine
  supports.

- **Game code that differs between releases** goes through `ai.rrw.kine.compat`:
  - `Compat`: static calls for feature code, e.g. `Compat.screen(mc)`, `Compat.cameraPos(mc)`,
    `Compat.key("key.kine.nav", "n")`, `Compat.mainItems(inv)`, `Compat.world().quads(...)`.
  - `Epoch`: the interface behind those calls. Each *epoch* implements it once, an epoch being a run of releases
    whose game API is the same for kine. The implementation is in
    `versions/<epoch>/src/main/java/ai/rrw/kine/compat/<epoch>/EpochImpl.java`, compiled against that epoch's release.
  - `Gui2D` and `HudLayer`: drawing on the screen.
    - HUD elements register with `Compat.hud(name, layer)`.
    - Kine's two screens are abstract. Each family subclasses them for its release's render and input methods.
  - `WorldDraw`: drawing in the world.
  - `Rules`: the game's own rules that kine's physics and combat copies follow where they differ between releases.
    An example is 26.2's slime bounce. The copies branch on these flags only.
  - `Mobs.is(e, "id")`: a mob kind that some releases lack, tested by its id.

- **A family folder**, `versions/<family>/` (e.g. `versions/26/`), holds code that every release of a family
  shares and other families lack. Its classes are in `ai.rrw.kine.compat.f<family>` and its mixins in
  `ai.rrw.kine.mixin.f<family>`.
  - Each 26.x `EpochImpl` extends `f26.Base26`.
  - Each 1.21.x `EpochImpl` extends `f121.Base121` (versions/1.21/). An older family's folder is compiled with the
    shared code, so the version check covers it: Base121 holds only the methods whose code links the same on every
    1.21.x release, and a method that differs anywhere lives in the epochs.

- **Mixins whose target differs between releases** live in the epoch or family folders.
  - Each epoch's `src/main/resources/kine-mixins-<epoch>.txt` lists the ones its releases apply, relative to
    `ai.rrw.kine.mixin`, e.g. `v26_3.AfterRotationSentMixin`.
  - `compat/KineMixinPlugin` adds only the running release's.
  - Mixins in `src/main/resources/kine.mixins.json` apply on every release.

- **Built-in resource packs** are folders under `src/main/resources/resourcepacks/` (an older family's folder can
  replace them). When some releases need other files, a whole copy `resourcepacks/<name>_<from>-<to>/` (or
  `<name>_<from>-`) replaces `resourcepacks/<name>/` on those releases; `compat/Packs` picks it. Not the pack format's
  overlays: Fabric API leaves a default-on pack off while one of its overlays is in use.

- **`versions/versions.txt`** is the table of releases, and the one place to add one. Each row gives the release's
  epoch, loader, Fabric API, Java and family. A row starting with `pending` is a release still being ported: it is
  left out unless the build is run with `-Pkine.pending=true`.

- **`gradle.properties` `dev_version`** is the release `./gradlew runClient` runs, and where features are written
  first. It changes nothing in the jars: the shared code links the same on every release (the version check), every
  family's code is compiled against the Mixin and MixinExtras of the oldest loader its jar admits (versions/table.gradle:
  javac writes an annotation's values in the shape the Mixin it compiles against declares, and an older Mixin can't
  read a newer one's: Fabric Mixin 0.17.4 made @Redirect's `at` an array, which 0.17.2 fails on), and the 26.x jar is
  rewritten after Loom with the family's values in its manifest and its entries in name order. The unit tests run
  under `unit_test_release`'s rules, the release their recordings come from, not `dev_version`'s.

- **A family** is the releases one jar covers: 26.x, which is unobfuscated, and 1.21.x, which runs with intermediary
  names.
  - At start the jar picks the running release's epoch from its table (`kine-epochs.txt`, written by the build).
  - `fabric.mod.json` declares the family's range (`>=26.1 <26.4-`, so not the next release's snapshots), and the
    lowest loader, Fabric API and Java in the table.

- **An older family** (1.21.x) builds from a translated copy of the shared code.
  - `versions/renames.txt` lists pure renames between 26.x's names and 1.21.x's: classes that moved,
    `MobEffects.RESISTANCE`, `.handleContainerInput`. Each line says which releases it applies to.
  - The copy is compiled with Mojang mappings and remapped to intermediary.

## Everyday work

- `./gradlew runClient`: as before, on the development release.
- `./gradlew build`:
  - one jar per family in `build/libs`, e.g. `kine-0.8.0+26.1-26.3.jar`;
  - the unit tests;
  - the version check.
- The version check (`versionCheck`, part of `build`) compiles the shared code against every other release (projects
  `check-<release>`, in `build/version-check/`) and requires it to link exactly as the jar's copy does.
  - Within the development family the bytes must be the same.
  - In an older family the remapped code must match the family base's (its newest release).
  - It catches what compiling alone does not: a method made static, a field whose type changed, a method that kept
    its name but changed its intermediary, a constant inlined with another value. It prints the class and both
    releases' code.
- The mixin check (`mixinChecks`, part of `build`; `mixinCheck` in each `check-<release>` project and the root, from
  versions/mixincheck.gradle and versions/MixinCheck.java) checks every mixin a release applies against that release's
  game classes, without starting the game: targets, injection points, handler parameters, accessors, shadows. It also
  holds every Mixin and MixinExtras annotation to the shape the oldest Mixin the jar admits declares (below).
- `./gradlew traceTest -Prelease=26.3 -Ptraces=<dir>` replays a release's recorded traces (`<dir>/move/*_traces*`)
  through the physics and mob copies, with that release's `Rules`.

## A new feature

- Write it in `src/main/java` against the development release, as now.
- If `./gradlew build` then fails, it names the release and the game member that differs there:
  - a compile error in a `check-<release>` project: the member doesn't exist there;
  - a `versionCheck` failure: it exists but links differently.
- A pure rename (a class moved, a method renamed with the same meaning) is a line in `versions/renames.txt`.
- Anything else goes behind `Compat`:
  1. a method in `Epoch` and its static twin in `Compat`;
  2. its body in each `versions/<epoch>/.../EpochImpl.java`, or once in a family's base class. The build fails until
     every epoch has it.
- A mixin whose target differs: one copy per epoch or family folder, each listed in the epoch's `kine-mixins-*.txt`.
- Pure logic needs nothing (planners, models, physics copies). If the game's own rule changed between releases, the
  copy branches on a flag in `compat/Rules.java`, and that release's recorded traces prove it.

## A new release

- **The same game API as an existing epoch:** add a line to `versions/versions.txt` with that epoch. `./gradlew build`
  checks it.
- **A different API:**
  1. copy the nearest epoch folder to `versions/<new epoch>/` and rename its packages;
  2. fix what doesn't compile against the new release;
  3. add its line;
  4. run `tools/mixincheck.sh`;
  5. compare the release's movement code with the copies (the porting folder's physics/ tools), and record its traces.

## Switching the development release

- Set `dev_version` in `gradle.properties` to any release of the newest family in `versions/versions.txt`, e.g.
  `dev_version=26.3`. That is the whole change.
- `runClient` then runs that release and the root project compiles that epoch's folder.
- What it costs:
  - one download of that release on the first build;
  - a fresh `run/` folder is advisable, since a world from a newer release doesn't open in an older one.
- A 1.21.x development release is not supported, because that family is built from a translated copy.

## Releases

- `./gradlew clean build` builds one jar per family, each named by its range.
- The GitHub release carries each family's jar.
