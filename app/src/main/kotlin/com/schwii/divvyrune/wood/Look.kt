package com.schwii.divvyrune.wood

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The looks the game can take. */
enum class Style(val id: String) {
    /** Walnut and maple blocks on linen, the default. */
    WOOD("wood"),
    /** White lines and dots on a dark ground. */
    WIRE("wire"),
    /** Glowing angular strokes scrawled on dark slate, like runes. */
    RUNE("rune");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: WOOD }
}

/**
 * How the game looks ([Style]); wireframe and runes make the whole app dark. Compose reads it, so changing it
 * redraws everything.
 */
object Look {
    var style by mutableStateOf(Style.WOOD)
    /** The wireframe look. */
    val wire get() = style == Style.WIRE
    /** The rune look. */
    val rune get() = style == Style.RUNE
    /** Either dark look. */
    val dark get() = style != Style.WOOD
}
