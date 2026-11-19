package com.schwii.divvywood.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.schwii.divvywood.R
import com.schwii.divvywood.wood.Palette

/** The prototype's daylight palette. */
val Ink = Color(Palette.ink)
val Dim = Color(Palette.dim)
val Page = Color(Palette.page)
val Panel = Color(Palette.panel)
val Raise = Color(0xFFFFFDF8)
val On = Color(0xFFFFFDF8)
val Line = Color(0xFFDDD3C3)
val Bad = Color(Palette.bad)
val Src = Color(0xFFB8862F)
val Out = Color(0xFF5F8A57)
val Belt = Color(0xFFB8935F)
val Scrim = Color(0x523B2C20)

@OptIn(ExperimentalTextApi::class)
private fun nunito(w: Int) = Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Nunito (SIL Open Font License), one variable font for every weight. */
val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

/** Body text: the prototype's Nunito at 500, the lightest weight it loads. */
val Body = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Medium, color = Ink)

/** Body text with figures that line up in columns. */
val BodyTnum = Body.copy(fontFeatureSettings = "tnum")
