# Divvywood

Open-source (GPL-3.0-or-later) Android puzzle game for F-Droid. Package `com.schwii.divvywood` (final).

## The game
Walnut and maple blocks on an open grid. Belts carry marbles; splitters (one input on a marked side, up to 3 outputs,
strict round robin) divide them evenly; convergers (one output on a marked side, up to 3 inputs, fair round robin)
join them. The input, final outputs (F) and bypasses (O) each take or send on exactly one side. Each level asks for
exact target fractions at its outputs; loops are allowed (they reach 1/5, 1/17, 1/30…). Check = exact rational solve
plus a 3000-tick simulation measured after 600 ticks warm-up. Score: parts (belts + splitters + convergers) and
footprint area (bounding box incl. I/O). A splitter treats an output as jammed only after 4 blocked ticks in a row
(JAM_TICKS), then skips it until it frees.

## Source of truth
The browser prototype Brett played is the spec for every rule, behaviour and piece of wording:
https://claude.ai/artifact/6psEUKVPFSUNveWVJdvd2g (read it with the Artifact tool, action "read"). Its
`ENGINE START`/`ENGINE END` section is ported to `engine/`; the UI section to `app/`. Wood look reference:
https://claude.ai/artifact/JXxqz55jj7Nxgy8mvNbFgH

## Layout
- `engine/` — pure Kotlin: rules, exact solver (BigInteger fractions), sim, judge/check, groups, text codes, 18 levels (saves and tests refer to them by name: names are stable). JUnit tests incl. every level's reference solution.
- `app/` — Compose app, minSdk 26, target/compile 36, no permissions.
  - `wood/` textures, `Painter` (android.graphics.Canvas), `Fonts` (Nunito for block labels)
  - `board/` `BoardState` (camera, frame clock, sim, Check run, sparkline history), `BoardRenderer`, `Board` composable, `Editor` (gesture state machine, undo), `Readout`
  - `game/` `Game` (level, sheets, Check, hint), `Saves` (SharedPreferences + kotlinx-serialization JSON, keyed by level name; builds stored as text codes, best = parts / smallest / largest area)
  - `ui/` `GameScreen`, `TopStrip` (readouts + sparklines), `Sheets` (result, levels, build as text), `Theme`, `Icons`
- Font: `app/src/main/res/font/nunito.ttf` (variable, from github.com/googlefonts/nunito), OFL in `app/src/main/assets/licenses/`.

## Build
- SDK at /opt/android-sdk (`local.properties`). If missing: cmdline-tools 9862592, `platform-tools`, `platforms;android-36`, `build-tools;35.0.0`.
- System `gradle` 8.14.3 (= wrapper) or `./gradlew`; JDK 21 (bytecode 17, no toolchains). Maven Central sometimes 429s — retry.
- `gradle :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
- Pinned on purpose: Compose BOM 2026.06.01, core-ktx 1.17.0, activity-compose 1.11.0 (newer need compileSdk 37, unsupported by AGP 8.13.0). Kotlin 2.4.10, Robolectric 4.17, kotlinx-serialization 1.12.0. New dependency versions must be at least two weeks old.
- No Roborazzi; keep test deps minimal. Screenshot tests (Robolectric NATIVE graphics) draw the decorView into a Bitmap and save PNGs to `app/src/test/screenshots/` — look at them after UI changes. `GameScreen(animate = false)` in tests.
- Allowed hosts here: dl.google.com, downloads.gradle.org, Maven Central mirror, raw.githubusercontent.com, package registries. Ask Brett before adding hosts.

## Release
- Version: `versionName`/`versionCode` in `app/build.gradle.kts` are the release (1.0.0 / 1000000 = major·10⁶ + minor·10⁴ + patch·100). To release: bump both, add `fastlane/metadata/android/en-US/changelogs/<code>.txt`, merge, tag `v<name>` on main.
- `release.yml`: a `v*` tag builds from source unchanged, signs with Brett's key (Actions secrets DIVVYWOOD_KEYSTORE_BASE64 / _KEYSTORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD) and publishes `divvywood-<name>.apk`. Each merge to main publishes a prerelease `<last release>.K`, code = last release + K (commits since its tag, ≤ 99). Brett updates via Obtainium.
- Reproducible: two clean builds at different paths give identical unsigned APKs (no vcsInfo, engine compiled with -Xjdk-release=17). F-Droid's recipe draft is `fdroid/com.schwii.divvywood.yml` (Binaries + AllowedAPKSigningKeys).
- Store listing: `fastlane/metadata/android/en-US`. Icon (Brett's logo build `..,IB,..,..,../BR,CB,FB,FB2,FB3/RT,ST,SL,SL,LT`), feature graphic and screenshots are drawn by `StoreAssets` (`DIVVYWOOD_ASSETS=1 gradle :app:testDebugUnitTest --tests '*StoreAssets*'`; move `res/mipmap-anydpi/ic_launcher.xml` aside the first time, it needs the PNGs to link).

## Workflow
- One PR per milestone-sized chunk on a branch; Brett merges (merge = approval). Report verified results with real output and show screenshots of UI changes.
- Ask before side effects (new hosts, secrets, anything outside the repo). Mobile first, touch only. Concise answers; no needless choices.
- Commit messages and PR descriptions describe what IS, in the present tense.
- Shallow clone: `git config --add remote.origin.fetch "+refs/heads/<branch>:refs/remotes/origin/<branch>"`, push with `git -c push.negotiate=true push`.
