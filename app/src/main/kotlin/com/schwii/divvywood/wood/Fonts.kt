package com.schwii.divvywood.wood

import android.content.Context
import android.graphics.Typeface
import com.schwii.divvywood.R

/** The typeface burned onto blocks: Nunito ExtraBold once [load]ed, a bold sans until then. */
object Fonts {
    var label: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); private set
    /** Variation settings for [label] (Nunito is one variable font). */
    var labelVariation: String? = null; private set

    fun load(context: Context) {
        if (labelVariation != null) return
        runCatching { context.resources.getFont(R.font.nunito) }.onSuccess { label = it; labelVariation = "'wght' 800" }
    }
}
