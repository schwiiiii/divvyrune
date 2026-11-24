# Divvywood

Open-source (GPL-3.0-or-later) Android puzzle game for F-Droid. Package `com.schwii.divvywood` (final).

## The game
Walnut and maple blocks on an open grid. Belts, splitters, convergers and bridges are unlimited; each input is placed once. Belts carry marbles; splitters (one input on a marked side, outputs on their other open sides,
strict round robin) divide them evenly; convergers (one output on a marked side, inputs on their other open sides, fair round robin)
join them; a bridge (`X`) lets two straight lines cross. Splitter/converger sides can be closed (`Tile.closed` bitmask). Every rate is a share of a full belt (1 marble/tick): a level has up to 3 inputs, each with its rate, and asks for
exact target rates at up to 3 outputs; loops are allowed (they reach 1/5, 1/17, 1/30…).
Ports are the sides of the machine's box (`machineBox`: every piece but the inputs). An input sits just outside it and feeds the first piece in its row/column (`settleInputs` keeps it at the edge); a belt, converger or open splitter side pointing at empty space with a clear straight line to the edge is a way out (a virtual `Kind.OUT` node in the graph). One port per side, so ≤ 4 (`MAX_PORTS`): inputs + targets + one side for the rest if the targets don't take everything. `assignOutputs` matches ways out to targets by rate. A build needs ≥ 2 pieces to pass, and no level may be solvable by one piece (`Level.onePiece`).
Machines (`Machines.kt`): a solved build placed as one piece (`Kind.MACHINE`, `ref` = 6-hex SHA-256 of its one-line code, `out` = quarter turns). `buildGraph` lays its build node by node onto the machine's cell (`Node.machine`, inputs → belts with `inPort`, ways out → belts with `outPort`); it can't hold itself or solve its own level. Pieces count everything inside; area is the board's rectangle. Only builds whose ports line up (`portAxes`: left/right ports on one row, top/bottom in one column) qualify. Drawn: the square centred where the port lines cross fills the cell (`Node.local`/`Node.frame`, `Graph.machines`), so each port meets the middle of its side; marbles visible throughout. The collection (`Saves.machines`) holds each level's best build per metric plus machines read in from codes; codes end with `#hash = <one-line build>` lines (`encodeShare`). An outdated machine moves to its level's current best for the metric it was best at, at Check, if its sides match.
Editor: drawing straight across a straight belt makes a bridge; retracing existing connections undoes them (belts deleted, piece-to-piece joins closed); a drag from a splitter straight out to the first cell past the edge toggles that side.
Check = exact rational solve
plus a 3000-tick simulation measured after 600 ticks warm-up. Metrics (`Metrics.kt`): pieces (every cell but inputs; a bridge is one), footprint area (w×h of the rectangle around the machine,
inputs aside), flow 0–100 (`Sim.kt` `flowOf`: share of marble-ticks spent waiting over one loop, incl. inputs unable to send; 100 only with no waiting at all); cycle (not a metric, it's set by the level's rates; drives the step counter and flow; `Sim.kt`: `CycleFinder` fingerprints the board state each tick
— marbles' cells, splitter/converger turns, jam counts capped at JAM_TICKS, inputs' pending marbles, schedule phase —
and the first repeat gives settling `start` and loop `length`; over one loop, output counts are exact rates). A splitter treats an output as jammed only after 4 blocked ticks in a row
(JAM_TICKS), then skips it until it frees. The sim takes pieces in board order (row by row, left to right; `buildGraph`), so results never depend on placement order.

## Source of truth
The app is the spec now: the browser prototype (https://claude.ai/artifact/6psEUKVPFSUNveWVJdvd2g) is retired; it predates
multiple inputs, flow, the builder and side ports. Planned rules live in `ROADMAP.md`. Wood look reference:
https://claude.ai/artifact/JXxqz55jj7Nxgy8mvNbFgH

## Layout
- `engine/` — pure Kotlin: rules, exact solver (BigInteger fractions), sim, judge/check, groups, text codes (`I`/`I2`/`I3` inputs; `#a1b2c3.1` machines; `S`/`C` + side + open-sides digit 0–7, binary clockwise from the marked side; bare `X` bridges with lanes inferred; old `F`/`O` cells read as empty), 17 levels (`Level(name, inputs, out)`; the `Level(name, t, shares)` constructor writes a one-input level in older terms; players see only numbers; names are stable ids that saves and tests use; no hints). JUnit tests incl. every level's reference solution.
- `solver/` — dev tool (JVM, on `engine/`): `synthesize` builds a random splitter/converger network meeting a level's shares exactly (2^a·3^b split trees, loops, leftover lines), `layOut` places and routes it on the grid (bridging straight belts; unused splitter/converger sides closed; `openPorts` leads each port to its own side and joins leftover lines into one), `solveLevel` keeps the layouts that pass Check. `gradle :solver:baselines` rewrites `app/src/main/assets/baselines.json` (100 solutions per level).
- `app/` — Compose app, minSdk 26, target/compile 36, no permissions.
  - `wood/` textures, `Painter` (android.graphics.Canvas, the wood look), `WirePainter` (the wireframe look: white lines on dark, one screen width at every zoom (`Painter.unit`), no arrows or dots, splitters light their next branch), `Look` (which one; `ui/Theme` colours follow it), `Fonts` (Nunito for block labels)
  - `board/` `BoardState` (camera, frame clock, sim, Check run, sparkline history, wait heat, single step), `BoardRenderer` (`Layout`: each node's centre and size, machines' insides in their own smaller grid, ports flaring from the middle of a side to their lane; `paintBuild` draws blocks, machine borders and insides), `Board` composable, `Editor` (gesture state machine, undo), `Readout`
  - `game/` `Baselines` (`assets/baselines.json`: many solutions' metrics per level, for the histograms; shown from 5 solutions), `Game` (levels = numbered `LEVELS` + the player's custom levels + the level builder last; sheets, Check, builder draft / save / delete, settings: the look), `Saves` (SharedPreferences + kotlinx-serialization JSON, keyed by level name; per level: build, best = fewest pieces / smallest area / highest flow (each on its own), solving build, last Check's metrics; custom levels = `LevelDef` (input rates, target rates; `problem()` refuses designs over 4 ports, one-piece, or leaving over a belt) + proving solution, and the builder's draft; key `save.v2`, older saves were cleared)
  - `ui/` `GameScreen`, `TopStrip` (readouts + sparklines), `Sheets` (result with metric blocks and histograms, level cards with build previews, build as text), `Histogram`, `Builder` (builder settings sheet), `Theme`, `Icons`
- Font: `app/src/main/res/font/nunito.ttf` (variable, from github.com/googlefonts/nunito), OFL in `app/src/main/assets/licenses/`.

## Build
- SDK at /opt/android-sdk (`local.properties`). If missing: cmdline-tools 9862592, `platform-tools`, `platforms;android-36`, `build-tools;35.0.0`.
- System `gradle` 8.14.3 (= wrapper) or `./gradlew`; JDK 21 (bytecode 17, no toolchains). Maven Central sometimes 429s — retry.
- `gradle :engine:test :solver:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Pinned on purpose: Compose BOM 2026.06.01, core-ktx 1.17.0, activity-compose 1.11.0 (newer need compileSdk 37, unsupported by AGP 8.13.0). Kotlin 2.4.10, Robolectric 4.17, kotlinx-serialization 1.12.0. New dependency versions must be at least two weeks old.
- No Roborazzi; keep test deps minimal. Screenshot tests (Robolectric NATIVE graphics) draw the decorView into a Bitmap and save PNGs to `app/src/test/screenshots/` — look at them after UI changes. `GameScreen(animate = false)` in tests.
- Allowed hosts here: dl.google.com, downloads.gradle.org, Maven Central mirror, raw.githubusercontent.com, package registries. Ask Brett before adding hosts.

## Release
- Version: `versionName`/`versionCode` in `app/build.gradle.kts` are the release (1.0.0 / 1000000 = major·10⁶ + minor·10⁴ + patch·100). To release: bump both, add `fastlane/metadata/android/en-US/changelogs/<code>.txt`, merge, tag `v<name>` on main.
- `release.yml`: a `v*` tag builds from source unchanged, signs with Brett's key (Actions secrets DIVVYWOOD_KEYSTORE_BASE64 / _KEYSTORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD) and publishes `divvywood-<name>.apk`. Each merge to main publishes a prerelease `<last release>.K`, code = last release + K (commits since its tag, ≤ 99). Before the first tag: `0.9.N`, code 900000 + N (commits on main). Don't tag until Brett says so. Brett updates via Obtainium.
- Reproducible: two clean builds at different paths give identical unsigned APKs (no vcsInfo, engine compiled with -Xjdk-release=17). F-Droid's recipe draft is `fdroid/com.schwii.divvywood.yml` (Binaries + AllowedAPKSigningKeys).
- Store listing: `fastlane/metadata/android/en-US`. Icon (Brett's logo build `..,IB,..,..,../BR,CB,FB,FB2,FB3/RT,ST,SL,SL,LT`), feature graphic and screenshots are drawn by `StoreAssets` (`DIVVYWOOD_ASSETS=1 gradle :app:testDebugUnitTest --tests '*StoreAssets*'`; move `res/mipmap-anydpi/ic_launcher.xml` aside the first time, it needs the PNGs to link).

## Workflow
- One PR per milestone-sized chunk on a branch; Brett merges (merge = approval). Report verified results with real output and show screenshots of UI changes.
- Ask before side effects (new hosts, secrets, anything outside the repo). Mobile first, touch only. Concise answers; no needless choices.
- Commit messages and PR descriptions describe what IS, in the present tense.
- Every user-visible change adds a line to `CHANGELOG.md` (under the unreleased version, concise, player's view); shipped roadmap items move from `ROADMAP.md` to the changelog. For a release, the changelog's entry is condensed into `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (≤ 500 chars).
- Before every push, check the state of git: fetch `main` and the branch, see whether the branch's PR has merged (its commits are on `main`), what's ahead/behind, and whether the working tree is clean. A merged PR means new work goes in a new PR.
- Shallow clone: `git config --add remote.origin.fetch "+refs/heads/<branch>:refs/remotes/origin/<branch>"`, push with `git -c push.negotiate=true push`.
