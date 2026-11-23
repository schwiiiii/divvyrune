package com.schwii.divvywood.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schwii.divvywood.engine.LETTERS
import com.schwii.divvywood.game.Game
import com.schwii.divvywood.game.LevelDef

/**
 * The level builder's settings: how fast the input runs, each output's target, and whether
 * 3-way splits are allowed. Every change applies at once; the design can be saved after its build passes Check.
 */
@Composable
fun ColumnScope.BuilderSheet(game: Game) {
    val d = game.draft
    fun set(nd: LevelDef) = game.updateDraft(nd)
    Text("New level", style = Body, fontWeight = FontWeight.Bold, fontSize = 20.sp)

    Setting("Input") {
        Stepper("1 every ${d.t} tick${if (d.t == 1) "" else "s"}", d.t > 1, d.t < 4, "slower and faster input") { set(d.copy(t = (d.t + it).coerceIn(1, 4))) }
    }
    d.out.forEachIndexed { k, s ->
        val f = s.split('/').map { it.toInt() }
        val (n, q) = f[0] to f[1]
        Setting("Output ${LETTERS[k]}") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stepper("$n", n > 1, n < q, "numerator") { set(d.withOut(k, "${n + it}/$q")) }
                Text("/", style = Body, color = Dim, fontSize = 18.sp)
                Stepper("$q", q > 2 && q > n, q < 60, "denominator") { set(d.withOut(k, "$n/${q + it}")) }
                if (d.out.size > 1) SmallButton("×", "remove output ${LETTERS[k]}") {
                    set(d.copy(out = d.out.filterIndexed { i, _ -> i != k }))
                }
            }
        }
    }
    if (d.out.size < 3) Row(Modifier.fillMaxWidth()) {
        SmallButton("+ Output ${LETTERS[d.out.size]}", "add an output") { set(d.copy(out = d.out + "1/2")) }
    }
    Setting("3-way splits") {
        SmallButton(if (d.no3) "Off" else "On", "3-way splits") { set(d.copy(no3 = !d.no3)) }
    }
    d.problem()?.let { Text(it, style = Body, color = Bad, fontSize = 14.sp) }
    Text(
        "Build a solution and press Check. Once it passes, you can save the level; it joins your levels.",
        style = Body, color = Dim, fontSize = 13.sp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WideButton("Surprise me", Modifier.weight(1f)) { game.surpriseMe() }
        WideButton("Done", Modifier.weight(1f), go = true) { game.closeSheet() }
    }
}

private fun LevelDef.withOut(k: Int, v: String) = copy(out = out.toMutableList().also { it[k] = v })

@Composable
private fun Setting(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        content()
    }
}

/** − value +, each button 44 dp. */
@Composable
private fun Stepper(value: String, canDown: Boolean, canUp: Boolean, what: String, step: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", canDown, "less $what") { step(-1) }
        Text(value, style = BodyTnum, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 36.dp).padding(horizontal = 4.dp))
        StepButton("+", canUp, "more $what") { step(1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Raise).clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.3f).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Body, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
}

@Composable
private fun SmallButton(label: String, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(Raise).clickable(onClick = onClick)
            .semantics { contentDescription = desc }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
private fun WideButton(label: String, modifier: Modifier, go: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(if (go) Out else Raise).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = Body, color = if (go) On else Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}
