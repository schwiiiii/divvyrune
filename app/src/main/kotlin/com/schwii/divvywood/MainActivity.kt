package com.schwii.divvywood

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.PrefsStore
import com.schwii.divvywood.game.Saves
import com.schwii.divvywood.ui.GameScreen
import com.schwii.divvywood.ui.rememberHaptics

/** Opens the level last played, with its saved build. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val density = LocalDensity.current.density
            val haptics = rememberHaptics()
            val game = remember { Game(Saves(PrefsStore(this)), density, haptics, dev = BuildConfig.DEV_TOOLS) }
            GameScreen(game)
        }
    }
}
