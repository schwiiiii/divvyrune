package com.schwii.divvyrune.engine

/**
 * How a build measures by its layout, ways that pull against each other (with [Flow], measured by running it):
 * - [pieces]: every cell but the inputs (belts, splitters, convergers, bridges); a machine counts every piece of its
 *   own build. Fewer is better.
 * - [area]: of the rectangle [w] × [h] around the machine, the inputs aside, as it lies on the board: a machine is
 *   one cell of it. Smaller is better, so 3×5 beats 4×4.
 */
class Metrics(val pieces: Int, val belts: Int, val splits: Int, val merges: Int, val w: Int, val h: Int, val bridges: Int = 0, val machines: Int = 0) {
    val area get() = w * h
}

fun metricsOf(tiles: Map<Cell, Tile>, lib: MachineLib = MachineLib.EMPTY): Metrics {
    if (tiles.isEmpty()) return Metrics(0, 0, 0, 0, 0, 0)
    val v = tiles.values
    val belts = v.count { it.kind == Kind.BELT }
    val splits = v.count { it.kind == Kind.SPLIT }
    val merges = v.count { it.kind == Kind.MERGE }
    val bridges = v.count { it.kind == Kind.BRIDGE }
    val machines = v.filter { it.kind == Kind.MACHINE }
    val inMachines = machines.sumOf { t -> t.ref?.let { machinePieces(it, lib) } ?: 1 }
    // the footprint is the machine alone: the inputs don't count
    val body = tiles.filterValues { it.kind != Kind.SRC }.keys
    val w = if (body.isEmpty()) 0 else body.maxOf { it.x } - body.minOf { it.x } + 1
    val h = if (body.isEmpty()) 0 else body.maxOf { it.y } - body.minOf { it.y } + 1
    return Metrics(belts + splits + merges + bridges + inMachines, belts, splits, merges, w, h, bridges, machines.size)
}
