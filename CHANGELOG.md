# Changelog

What changes in each release of Divvywood. Development builds between releases are numbered after the last
release (`1.0.0.3`); before 1.0.0 they were `0.9.N`. Plans for later releases are in [ROADMAP.md](ROADMAP.md).

## 1.0.0 (unreleased)

First release.

- 20 levels, from halves to one thirtieth, three of them with two inputs to split apart and recombine.
- Every rate is a share of a full belt (one marble a tick): inputs, targets, readouts and Check all use it.
- Walnut and maple blocks: belts, splitters, convergers and bypasses, as many as you like; loops allowed.
- Check works out every block's exact share as a fraction, then runs the build for 3000 ticks and measures it.
- Four metrics for every solve, each with its own best: pieces (every cell but inputs and outputs), footprint (the
  smallest square around the machine), cycle (how many ticks until the whole board repeats) and I/O layout (0–100:
  ports open to the outside, inputs grouped, outputs grouped, facing away from each other). Improving one can cost
  another.
- The step counter counts through the machine's cycle (step 3 of 18) once it settles, instead of growing forever.
- Levels shown as numbered cards: solved ones outlined green with their solution, started ones with the build so far.
  The sheet opens at the level you're on.
- Level builder: set up to three inputs' rates, up to three targets and whether 3-way splits are allowed (or let Surprise me
  pick a target), build a solution, and once it passes Check save it to Your levels to play again.
- Live readouts: the input's rate and each output's measured share, with sparklines.
- Bottlenecks show on the board: blocks where marbles wait glow red, convergers turning marbles away get a red ring,
  and a held-up input is outlined. Pause and Step one tick at a time; tap a piece for how often it waits.
- Touch controls: tap a piece for its numbers, Rotate and Delete; drag it to move it; drag from it to draw a belt;
  drag the table to pan; pinch to zoom; Select for groups; Undo.
- Builds as short text codes to copy and paste.
- Offline, no permissions, no ads, no tracking.
