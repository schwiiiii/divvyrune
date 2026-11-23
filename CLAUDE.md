# Divvywood

Open-source (GPL-3.0-or-later) Android puzzle game for F-Droid. Package `com.schwii.divvywood` (final).

## The game
Walnut and maple blocks on an open grid. Belts, splitters, convergers and bypasses are unlimited; the input and each output are placed once. Belts carry marbles; splitters (one input on a marked side, up to 3 outputs,
strict round robin) divide them evenly; convergers (one output on a marked side, up to 3 inputs, fair round robin)
join them. The input, final outputs (F) and bypasses (O) each take or send on exactly one side. Every rate is a share of a full belt (1 marble/tick): a level has up to 3 inputs, each with its rate, and asks for
exact target rates at up to 3 outputs; loops are allowed (they reach 1/5, 1/17, 1/30…). Check = exact rational solve
plus a 3000-tick simulation measured after 600 ticks warm-up. Metrics (`Metrics.kt`): pieces (every cell but inputs/outputs), footprint area (w×h of the rectangle around the machine,
inputs/outputs aside), flow 0–100 (`Sim.kt` `flowOf`: share of marble-ticks spent waiting over one loop, incl. inputs unable to send; 100 only with no waiting at all), I/O layout 0–100 (40% ports with a clear line out from their open face, 30% inputs grouped and
outputs+bypasses grouped, 30% inputs and outputs facing opposite ways); cycle (not a metric, it's set by the level's rates; drives the step counter and flow; `Sim.kt`: `CycleFinder` fingerprints the board state each tick
— marbles' cells, splitter/converger turns, jam counts capped at JAM_TICKS, inputs' pending marbles, schedule phase —
and the first repeat gives settling `start` and loop `length`; over one loop, output counts are exact rates). A splitter treats an output as jammed only after 4 blocked ticks in a row
(JAM_TICKS), then skips it until it frees. The sim takes pieces in board order (row by row, left to right; `buildGraph`), so results never depend on placement order.

## Source of truth
The browser prototype Brett played is the spec for every rule, behaviour and piece of wording:
https://claude.ai/artifact/6psEUKVPFSUNveWVJdvd2g (read it with the Artifact tool, action "read"). Its
`ENGINE START`/`ENGINE END` section is ported to `engine/`; the UI section to `app/`. Wood look reference:
https://claude.ai/artifact/JXxqz55jj7Nxgy8mvNbFgH

## Layout
- `engine/` — pure Kotlin: rules, exact solver (BigInteger fractions), sim, judge/check, groups, text codes (`I`/`I2`/`I3` inputs, `F`/`F2`/`F3` outputs), 20 levels (`Level(name, inputs, out)`; the `Level(name, t, shares)` constructor writes a one-input level in older terms; players see only numbers; names are stable ids that saves and tests use; no hints). JUnit tests incl. every level's reference solution.
- `solver/` — dev tool (JVM, on `engine/`): `synthesize` builds a random splitter/converger network meeting a level's shares exactly (2^a·3^b split trees, loops, bypass dumps), `layOut` places and routes it on the grid, `solveLevel` keeps the layouts that pass Check. `gradle :solver:baselines` rewrites `app/src/main/assets/baselines.json` (100 solutions per level).
- `app/` — Compose app, minSdk 26, target/compile 36, no permissions.
  - `wood/` textures, `Painter` (android.graphics.Canvas), `Fonts` (Nunito for block labels)
  - `board/` `BoardState` (camera, frame clock, sim, Check run, sparkline history, wait heat, single step), `BoardRenderer`, `Board` composable, `Editor` (gesture state machine, undo), `Readout`
  - `game/` `Baselines` (`assets/baselines.json`: many solutions' metrics per level, for the histograms; shown from 5 solutions), `Game` (levels = numbered `LEVELS` + the player's custom levels + the level builder last; sheets, Check, builder draft / save / delete), `Saves` (SharedPreferences + kotlinx-serialization JSON, keyed by level name; per level: build, best = fewest pieces / smallest area / highest I/O / highest flow (each on its own), solving build, last Check's metrics; custom levels = `LevelDef` (input rates, target rates; older `t`-based designs normalized on load) + proving solution, and the builder's draft)
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
- Shallow clone: `git config --add remote.origin.fetch "+refs/heads/<branch>:refs/remotes/origin/<branch>"`, push with `git -c push.negotiate=true push`.
