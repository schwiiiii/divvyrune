package com.schwii.divvyrune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.schwii.divvyrune.game.Baselines
import com.schwii.divvyrune.game.Game
import com.schwii.divvyrune.game.PrefsStore
import com.schwii.divvyrune.game.Saves
import com.schwii.divvyrune.sound.SoundOut
import com.schwii.divvyrune.ui.GameScreen
import com.schwii.divvyrune.ui.rememberHaptics

/** Opens the level last played, with its saved build; plays its sound while in front. */
class MainActivity : ComponentActivity() {
    private var game: Game? = null
    private var resumed = false

    override fun onResume() { super.onResume(); resumed = true; game?.resume() }
    override fun onPause() { super.onPause(); resumed = false; game?.pause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val density = LocalDensity.current.density
            val haptics = rememberHaptics()
            val game = remember {
                Game(Saves(PrefsStore(this)), density, haptics, Baselines.load(this), SoundOut()).also { game = it; if (resumed) it.resume() }
            }
            GameScreen(game)
        }
    }
}
