package com.schwii.divvywood

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.schwii.divvywood.board.Board
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.wood.Palette

/** Milestone 2: shows the whiteboard 1/30 build running on the sandbox board. Controls and panels come next. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val density = LocalDensity.current.density
            val state = remember {
                BoardState(LEVELS.last(), decodeGrid(DEMO).tiles!!, density).apply { z = 64f * density }
            }
            Board(state, Modifier.fillMaxSize().background(Color(Palette.page)))
        }
    }

    companion object {
        const val DEMO = "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT .."
    }
}
