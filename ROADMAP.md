# Roadmap

Planned work, roughly in order. Nothing here has a date; items move to the [changelog](CHANGELOG.md) when they ship.

## Next
- [ ] **1.0.0 release** on GitHub, after the current round of playtesting.
      Before tagging: redraw the store screenshots with `StoreAssets` (the metrics and levels changed since they
      were drawn), and fill `AllowedAPKSigningKeys` in `fdroid/com.schwii.divvywood.yml` with the SHA-256 of the
      release signing certificate (Brett supplies it from his keystore).
- [ ] **F-Droid**: submit the recipe in `fdroid/`; F-Droid ships the same signed APK, so copies from F-Droid and
      GitHub update each other.

## Soon
- [ ] **A level generator** (a developer tool) to grow the set from 20 to about 100, from simple to complex, with
      more levels using several inputs. Players see them as ordinary numbered levels. Builds on the solver, which
      proves each new level solvable.

## Later
- [ ] **Share custom levels**: send a level made in the builder as a short code or link, and play levels others made.
- [ ] **Leaderboards** feeding the histograms with players' results. Needs a server and the network permission, so it
      should stay optional.
- [ ] **Coloured marbles**: each input its own colour, with outputs asking for colours in set ratios.
- [ ] **Colour sorter block**, to go with coloured marbles.

## Open questions
- **How splitters treat colours.** If round robin ignores colour, a red-blue-red-blue stream into a 2-way splitter
  sends every red one way and every blue the other, so the exact maths and the measured run disagree. Either each
  colour gets its own round robin (the maths stays exact) or sorting by timing becomes part of the puzzle (Check
  then relies on the measured run). To be tried in the browser prototype before it reaches the app.
