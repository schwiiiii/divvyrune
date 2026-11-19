package com.schwii.divvywood

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.schwii.divvywood.board.BoardState
import com.schwii.divvywood.board.Editor
import com.schwii.divvywood.engine.LEVELS
import com.schwii.divvywood.engine.decodeGrid
import com.schwii.divvywood.ui.GameScreen
import com.schwii.divvywood.ui.rememberHaptics

/** Milestone 3: the sandbox with the whiteboard 1/30 build, fully editable. Levels, Check and saves come next. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val density = LocalDensity.current.density
            val haptics = rememberHaptics()
            val editor = remember {
                Editor(BoardState(LEVELS.last(), decodeGrid(DEMO).tiles!!, density).apply { z = 56f * density }, haptics)
            }
            GameScreen(editor)
        }
    }

    companion object {
        const val DEMO = "OR SR IL ..\nOR ST CB BL\nOR CL ST BT\n.. RT ST LT\n.. .. FT .."
    }
}
