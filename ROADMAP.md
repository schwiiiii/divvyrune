# Roadmap

Planned work, roughly in order. Nothing here has a date; items move to the [changelog](CHANGELOG.md) when they ship.

## Next
- [ ] **1.0.0 release** on GitHub, after the current round of playtesting.
- [ ] **F-Droid**: submit the recipe in `fdroid/`; F-Droid ships the same signed APK, so copies from F-Droid and
      GitHub update each other.

## Later
- [ ] **Several inputs at different rates**: targets given as rates or shares of the total input.
- [ ] **Coloured marbles**: each input its own colour, with outputs asking for colours in set ratios.
- [ ] **Colour sorter block**, to go with coloured marbles.
- [ ] **Levels sheet opens at the current level** instead of the top.

## Open questions
- **How splitters treat colours.** If round robin ignores colour, a red-blue-red-blue stream into a 2-way splitter
  sends every red one way and every blue the other, so the exact maths and the measured run disagree. Either each
  colour gets its own round robin (the maths stays exact) or sorting by timing becomes part of the puzzle (Check
  then relies on the measured run). To be tried in the browser prototype before it reaches the app.
