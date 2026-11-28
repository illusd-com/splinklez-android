package cc.huhm.splinklez.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Small building blocks for the 1.2.3 source UI. The recovered jar has no .kotlin_module, so Kotlin source can see its
 * classes (Ink, Type, AppViewModel…) but not its top-level functions (SectionHead, Kicker… in CommonKt). These match
 * their look: section heads with a lacquer mark and a rule, mono kickers, a quiet "unavailable" box, a count-up number.
 */

@Composable
internal fun SpzHead(no: String, title: String, aside: String = "") {
    // Title on its own line, never squeezed by the aside; the aside (更新時間、城市…) sits under it, then a rule.
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(no, style = Type.section.copy(color = Ink.Lacquer))
            Spacer(Modifier.width(8.dp))
            Text(title, style = Type.section, modifier = Modifier.weight(1f))
        }
        if (aside.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(aside, style = Type.kicker, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Ink))
    }
}

/** Variant with a short trailing action (e.g. 「防災頁 →」) at the right of the title row. */
@Composable
internal fun SpzHead(no: String, title: String, trailing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(no, style = Type.section.copy(color = Ink.Lacquer))
            Spacer(Modifier.width(8.dp))
            Text(title, style = Type.section, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Ink))
    }
}

@Composable
internal fun SpzKicker(label: String, rest: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Ink.Lacquer, fontWeight = FontWeight.Bold)) { append(label) }
            if (rest.isNotBlank()) append("\u3000$rest")
        },
        style = Type.kicker,
    )
}

@Composable
internal fun SpzUnavailable(title: String, message: String, onRetry: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Ink.Hair, shape).padding(16.dp)) {
        Text(title, style = Type.serifBody.copy(fontWeight = FontWeight.Bold))
        if (message.isNotBlank() && message != title) {
            Spacer(Modifier.height(4.dp))
            Text(message, style = Type.small)
        }
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            PressChip("重試", selected = true, onClick = onRetry)
        }
    }
}

/** Counts up from 0 to [value] the first time it shows, then eases between values. */
@Composable
internal fun SpzCountUp(value: Double, style: TextStyle, decimals: Int, color: Color) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(value) { a.animateTo(value.toFloat(), tween(800, easing = FastOutSlowInEasing)) }
    Text(String.format("%.${decimals}f", a.value), style = style.copy(color = color))
}
