# Roadmap

Planned work, roughly in order. Nothing here has a date; items move to the [changelog](CHANGELOG.md) when they ship.

## Next
- [ ] **Machines as pieces** (before 1.0). A machine's ports are its sides, and a solved machine can be placed in
      other builds as a single piece.
      - **Ports from the build.** Check draws the box around the build (the footprint rectangle). Each open belt
        end runs in a clear straight line to the box edge; the line may cross belts but no other piece. Each side
        holds at most one port: one input entering, or one belt leaving. Two streams leaving on one side must be
        merged by the player's own converger first. That caps a level at 4 ports.
      - **No output or bypass pieces.** Inputs stay, at the edge. Every marble that enters leaves through a side:
        each target rate needs a side, and anything left over needs one more side of its own. Check matches the
        leaving sides to the targets by rate.
      - **Machines as pieces.** A solved machine takes one cell in a parent build and rotates. Its sides keep the
        mapping from its own build. The sim expands its internals into the parent's graph, ordered by parent
        cell, then the machine's own board order. Its pieces add to the parent's piece count, and its area minus
        one adds to the parent's area. Flow comes from the full sim, so a weak machine makes every build that
        uses it weaker. A machine can't contain itself, directly or through another machine.
      - **Metrics:** the I/O layout metric goes; pieces, area and flow remain.
      - **Knock-on effects:** "Too much to merge" (5 ports) is redesigned; the builder enforces the 4-port cap;
        `layOut` routes to box edges and the baselines are regenerated; saved builds with output or bypass pieces
        are migrated or cleared.
      - **Which builds are machines:** every solved build, numbered or custom level.
      - **Snapshots.** A placed machine keeps the build it had when it was placed. When its level's solving build
        changes later, the parent shows that it uses an old version, but nothing updates by itself: a new build
        can move ports and break the parent outright. Editing and checking the parent switches it to the current
        machine.
      - **Text codes:** a machine is written by its level, e.g. `#001`, and reads in as that level's current
        solving build. A custom level's machine is written by a short stable hash of its rates (e.g. `#c7f3a1`),
        so the same design gets the same id on every device.
      - To be tried in the browser prototype first.
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
