# Changelog

What changes in each release of Divvywood. Development builds between releases are numbered after the last
release (`1.0.0.3`); before 1.0.0 they were `0.9.N`. Plans for later releases are in [ROADMAP.md](ROADMAP.md).

## 1.0.0 (unreleased)

First release.

- 17 levels, from a quarter to one thirtieth, two of them with two inputs to split apart and recombine.
- Marbles enter and leave on the sides of the build: an input sits at the edge and feeds the first piece in its row
  or column, and whatever points out of the build with a clear line to the edge leaves there. Each side takes one
  input or one way out, so a build has at most four; what no output needs leaves on a side of its own. Outputs ask
  for rates and Check matches them to the ways out.
- An empty board shows a dashed start cell: an input dropped beside it faces into it, ready for the first piece.
- Splitters and convergers can close sides: drag along a join or belt again to undo it, and drag a splitter's side
  out to the edge to open or close it.
- Bridges: draw a belt straight across another and they cross without meeting.
- A build needs at least two pieces to pass; levels a single piece could solve are gone.
- Machines: a level's best builds (fewest pieces, smallest area, best flow) become machines you can place in other
  levels as a single piece, from the Machines button in the tray. A machine keeps the sides it was built with and
  turns like any piece; every piece inside counts. When its level gets a better build it shows an amber dot, and the
  next Check moves it to the new build if the sides still match. A level can't be solved with its own machine.
- A machine shows its whole build inside its cell and marbles run through it in view. Only builds whose inputs and
  outputs line up across the middle (left and right on one row, top and bottom in one column) become machines, so
  their lines meet the middle of each side, straight on to the lines around them.
- Belts draw out of a machine's ways out and into its inputs like any other piece, and retrace away the same; a
  belt drawn to the wrong side of a machine says which way that side goes.
- Settings (the gear in the top strip): switch between the wood look and a wireframe look, white lines and dots on
  dark across the whole app, with no arrows, just faint dots where grid lines meet (machines show their own finer grid): lines keep one width on screen at every zoom, and each splitter
  lights only the branch its next marble takes.
- A new icon: the logo build's lines in the wireframe look, splitting, joining and winding out.
- Blocks fill their cells, so neighbouring pieces meet without gaps.
- The wood look follows the wireframe: wide carved channels and glass marbles that keep their size at every zoom, lit
  softly from the top left; no arrows; machines as one maple slab with their channels carved in. As in the
  wireframe, a splitter dims the branches its next marble won't take; convergers take whatever comes, so none dim.
- Levels solved before machines arrived offer their solutions as machines too.
- Build codes name machines by a hash of their build and carry those builds with them, so a shared code builds
  anywhere and adds its machines to yours.
- Every rate is a share of a full belt (one marble a tick): inputs, targets, readouts and Check all use it.
- Walnut and maple blocks: belts, splitters, convergers and bridges, as many as you like; loops allowed.
- Check works out every block's exact share as a fraction, then runs the build for 3000 ticks and measures it.
- Three metrics for every solve, each with its own best: pieces (every cell but the inputs), footprint (the
  area of the rectangle around the machine, so 3×5 beats 4×4) and flow (100 when no marble ever waits; backups and
  bottlenecks lower it, by how much time marbles spend stuck). Improving one can cost another. Each metric's histogram compares your build with 100 solutions the solver found for the level.
- The step counter counts through the machine's cycle (step 3 of 18) once it settles, instead of growing forever.
- Levels shown as numbered cards: solved ones outlined green with their solution, started ones with the build so far.
  The sheet opens at the level you're on.
- Level builder: set up to three inputs' rates, up to three targets and whether 3-way splits are allowed (or let Surprise me
  pick a target), build a solution, and once it passes Check save it to Your levels to play again.
- A build behaves the same however its pieces were placed, and the same after the app restarts.
- Live readouts: the input's rate and each output's measured share, with sparklines.
- Bottlenecks show on the board: blocks where marbles wait glow red, convergers turning marbles away get a red ring,
  and a held-up input is outlined. Pause and Step one tick at a time; tap a piece for how often it waits.
- Touch controls: tap a piece for its numbers, Rotate and Delete; drag it to move it; drag from it to draw a belt;
  drag the table to pan; pinch to zoom; Select for groups; Undo.
- Builds as short text codes to copy and paste.
- Offline, no permissions, no ads, no tracking.
