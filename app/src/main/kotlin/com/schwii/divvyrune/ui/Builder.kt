package com.schwii.divvyrune.ui

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
import com.schwii.divvyrune.engine.LETTERS
import com.schwii.divvyrune.engine.MAX_PORTS
import com.schwii.divvyrune.game.Game
import com.schwii.divvyrune.game.LevelDef

/**
 * The level builder's settings: each input's rate and each output's target (every rate a share of one full belt,
 * up to three of each and four in all), and whether 3-way splits are allowed. Every change applies at once; the design can be saved
 * after its build passes Check.
 */
@Composable
fun ColumnScope.BuilderSheet(game: Game) {
    val d = game.draft
    fun set(nd: LevelDef) = game.updateDraft(nd)
    Text("New level", style = Body, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text("Rates are shares of a full belt, which carries one marble a tick: 1 is a full belt, and no piece can carry more.", style = Body, color = Dim, fontSize = 13.sp)

    d.inputs.forEachIndexed { k, v ->
        FracSetting(if (d.inputs.size == 1) "Input" else "Input ${k + 1}", v, removable = d.inputs.size > 1,
            onChange = { set(d.copy(inputs = d.inputs.replaced(k, it))) },
            onRemove = { set(d.copy(inputs = d.inputs.filterIndexed { i, _ -> i != k })) })
    }
    // a build has four sides, one per input or output
    val room = d.inputs.size + d.out.size < MAX_PORTS
    if (d.inputs.size < 3 && room) Row(Modifier.fillMaxWidth()) {
        SmallButton("+ Input ${d.inputs.size + 1}", "add an input") { set(d.copy(inputs = d.inputs + "1/1")) }
    }
    d.out.forEachIndexed { k, v ->
        FracSetting("Output ${LETTERS[k]}", v, removable = d.out.size > 1,
            onChange = { set(d.copy(out = d.out.replaced(k, it))) },
            onRemove = { set(d.copy(out = d.out.filterIndexed { i, _ -> i != k })) })
    }
    if (d.out.size < 3 && room) Row(Modifier.fillMaxWidth()) {
        SmallButton("+ Output ${LETTERS[d.out.size]}", "add an output") { set(d.copy(out = d.out + "1/4")) }
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

private fun List<String>.replaced(k: Int, v: String) = toMutableList().also { it[k] = v }

/** A rate n/q, at most a full belt, with steppers for each part and a remove button. */
@Composable
private fun FracSetting(label: String, value: String, removable: Boolean, onChange: (String) -> Unit, onRemove: () -> Unit) {
    val (n, q) = (com.schwii.divvyrune.game.parseFrac(value) ?: com.schwii.divvyrune.engine.Frac(1, 1)).let { it.n to it.d }
    Setting(label) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stepper("$n", n > 1, n < q, "numerator") { onChange("${n + it}/$q") }
            Text("/", style = Body, color = Dim, fontSize = 18.sp)
            Stepper("$q", q > n, q < 60, "denominator") { onChange("$n/${q + it}") }
            if (removable) SmallButton("×", "remove $label") { onRemove() }
        }
    }
}

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
