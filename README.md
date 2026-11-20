# Divvywood

A puzzle game about splitting a stream of marbles into exact fractions.

Lay walnut and maple blocks on an open table: belts carry marbles, splitters divide them evenly between their
exits in turn, and convergers join up to three lines into one. Each level asks for target shares at its outputs
(1/2, 3/8, 1/17…). Loops are allowed, and they are how you reach fractions that halves and thirds alone can't.
Press **Check** to have the exact flow worked out and the build run, then chase fewer parts and a smaller footprint.

- Offline, no permissions, no ads, no tracking.
- Builds and layouts can be shared as short text codes, e.g. `IR SL FL / .. FT ..` (a splitter halving the input between two outputs).

## Install

Divvywood is headed for [F-Droid](https://f-droid.org). Until it's there, install the signed APK from the
[releases](https://github.com/schwiiiii/divvywood/releases): add `https://github.com/schwiiiii/divvywood` to
[Obtainium](https://github.com/ImranR98/Obtainium) to get each new version as an update.

Releases (`v1.0.0`) and the development builds published after every merge to `main` (prereleases, `1.0.0.3`) are
signed with the same key and numbered so that either kind updates the other; turn on *Include prereleases* in Obtainium
to follow development builds. F-Droid builds each release from source and, since the build is reproducible, ships the
same signed APK, so copies from F-Droid and from GitHub update each other too.

## Building

Requirements: JDK 17 or newer and the Android SDK (platform 36).

```
./gradlew :engine:test          # rules, exact solver and simulation tests
./gradlew :app:testDebugUnitTest # controls, game, saves and screenshot tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # unsigned release APK, byte for byte what the signed release contains
```

The project has two modules:

| Module | What |
|---|---|
| `engine` | Pure Kotlin rules: connections, the exact flow solver (fractions), the tick simulation, Check, text codes, levels. No Android dependencies. |
| `app` | The Android app (Jetpack Compose): board rendering, touch controls, tray, sheets and saves. |

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

The [Nunito](https://github.com/googlefonts/nunito) font is by Vernon Adams and the Nunito Project Authors, under the
SIL Open Font License 1.1 ([app/src/main/assets/licenses/Nunito-OFL.txt](app/src/main/assets/licenses/Nunito-OFL.txt)).
