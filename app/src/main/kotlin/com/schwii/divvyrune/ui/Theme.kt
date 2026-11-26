package com.schwii.divvyrune.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.schwii.divvyrune.R
import com.schwii.divvyrune.wood.Look
import com.schwii.divvyrune.wood.Style

/** The daylight palette, the wireframe look's dark one, or the rune look's slate one ([Look.style]). */
private fun pick(day: Long, dark: Long, rune: Long) = Color(when (Look.style) { Style.WOOD -> day; Style.WIRE -> dark; Style.RUNE -> rune })
val Ink get() = pick(0xFF3B2C20, 0xFFEDEFF2, 0xFFE4ECF7)
val Dim get() = pick(0xFF7D6A58, 0xFF9AA0A8, 0xFF8C96AB)
val Page get() = pick(0xFFEBE5DA, 0xFF14161A, 0xFF14161F)
val Panel get() = pick(0xFFF6F2EA, 0xFF1C1F24, 0xFF1B1E29)
val Raise get() = pick(0xFFFFFDF8, 0xFF262A31, 0xFF252A38)
val On get() = pick(0xFFFFFDF8, 0xFF14161A, 0xFF14161F)
val Line get() = pick(0xFFDDD3C3, 0xFF343942, 0xFF323A4D)
val Bad get() = pick(0xFFC0544A, 0xFFFF6B5E, 0xFFFF6B5E)
val Src get() = pick(0xFFB8862F, 0xFFF2C14E, 0xFFF2C14E)
val Out get() = pick(0xFF5F8A57, 0xFF7BD389, 0xFF6FD3C0)
val Belt get() = pick(0xFFB8935F, 0xFFEDEFF2, 0xFFBFE0FF)
val Scrim get() = pick(0x523B2C20, 0x99000000, 0x99000000)

@OptIn(ExperimentalTextApi::class)
private fun nunito(w: Int) = Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Nunito (SIL Open Font License), one variable font for every weight. */
val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

/** Body text: the prototype's Nunito at 500, the lightest weight it loads. */
val Body get() = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Medium, color = Ink)

/** Body text with figures that line up in columns. */
val BodyTnum get() = Body.copy(fontFeatureSettings = "tnum")
