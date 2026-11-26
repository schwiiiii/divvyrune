package com.schwii.divvyrune.wood

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How the game looks: wood (walnut and maple blocks on linen, the default) or wireframe (white lines and dots on a
 * dark ground, the whole app dark). Compose reads it, so changing it redraws everything.
 */
object Look {
    var wire by mutableStateOf(false)
}
