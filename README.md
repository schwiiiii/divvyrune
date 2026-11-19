# Divvywood

A puzzle game about splitting a stream of marbles into exact fractions.

Lay walnut and maple blocks on an open table: belts carry marbles, splitters divide them evenly between their
exits in turn, and convergers join up to three lines into one. Each level asks for target shares at its outputs
(1/2, 3/8, 1/17…). Loops are allowed, and they are how you reach fractions that halves and thirds alone can't.
Press **Check** to have the exact flow worked out and the build run, then chase fewer parts and a smaller footprint.

- Offline, no permissions, no ads, no tracking.
- Builds and layouts can be shared as short text codes, e.g. `IR SL FL / .. FT ..` (a splitter halving the input between two outputs).

## Development builds

Every merge to `main` is published as a [release](https://github.com/schwiiiii/divvywood/releases) with a signed
APK (`divvywood-0.1.N.apk`). To install it and get each new build as an update, add the repository URL
`https://github.com/schwiiiii/divvywood` to [Obtainium](https://github.com/ImranR98/Obtainium) (available on F-Droid).
Every build is signed with the same key, so each one installs over the last.

## Building

Requirements: JDK 17 or newer and the Android SDK (platform 36).

```
./gradlew :engine:test          # rules, exact solver and simulation tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

The project has two modules:

| Module | What |
|---|---|
| `engine` | Pure Kotlin rules: connections, the exact flow solver (fractions), the tick simulation, Check, text codes, levels. No Android dependencies. |
| `app` | The Android app (Jetpack Compose): board rendering, touch controls, tray, sheets and saves. |

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
