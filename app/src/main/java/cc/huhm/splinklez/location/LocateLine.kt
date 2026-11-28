package cc.huhm.splinklez.location

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.ui.Ink
import cc.huhm.splinklez.ui.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

/** 「18:05」, or 「11/28 18:05」 when the fix is from another day. */
internal fun fixTime(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(TAIPEI)
    val sameDay = t.toLocalDate() == LocalDate.now(TAIPEI)
    return t.format(DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "M/d HH:mm"))
}

/**
 * 自動定位 status at the top of the 生活資訊 band. Everything stacks vertically: one message line, then a line of small
 * actions, then (only when useful) the background-permission hint. Tapping the message relocates.
 */
@Composable
fun LocateLine() {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val st by AutoLocate.state.collectAsState()
    val perm by AutoLocate.perm.collectAsState()
    val bgDismissed by AutoLocate.bgDismissed.collectAsState()

    fun relocate() = AutoLocate.relocate(ctx)

    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).animateContentSize(tween(300))) {
        Crossfade(targetState = st, animationSpec = tween(240), label = "locate") { s ->
            Column(Modifier.fillMaxWidth()) {
                when (s) {
                    is AutoLocate.State.Off -> {
                        Msg("自動定位已關閉，使用你選的城市（${Graph.prefs.city}）")
                        Actions { Act("開啟自動定位") { AutoLocate.setEnabled(ctx, true) } }
                    }
                    is AutoLocate.State.NeedPermission -> {
                        Msg("開啟自動定位，天氣和生活資訊會跟著你所在的縣市更新。只用大約位置，不會存精確座標。")
                        Actions {
                            Act("允許定位") { activity?.let { LocateHooks.askLocation(it) } }
                            Act("先不要", dim = true) { AutoLocate.setEnabled(ctx, false) }
                        }
                    }
                    is AutoLocate.State.Idle, is AutoLocate.State.Locating -> {
                        val prev = (s as? AutoLocate.State.Locating)?.prev
                        Pulse { Msg(if (prev != null) "定位中…　上次：${prev.label}" else "定位中…") }
                    }
                    is AutoLocate.State.Ok -> {
                        Text(
                            "已自動定位：${s.fix.label}・${fixTime(s.fix.at)}",
                            style = Type.small.copy(color = Ink.Ink2),
                            modifier = Modifier.fillMaxWidth().clickable { relocate() }.padding(vertical = 4.dp),
                        )
                        Actions {
                            Act("重新定位") { relocate() }
                            Act("關閉", dim = true) { AutoLocate.setEnabled(ctx, false) }
                        }
                    }
                    is AutoLocate.State.Failed -> {
                        Msg("定位失敗：${s.reason}。目前使用「${Graph.prefs.city}」")
                        Actions {
                            Act("重試") { relocate() }
                            Act("關閉", dim = true) { AutoLocate.setEnabled(ctx, false) }
                        }
                    }
                }
            }
        }
        // Hourly updates in the background need 「一律允許」; without it the app still locates every time it opens.
        val showBg = perm.foreground && !perm.background && Build.VERSION.SDK_INT >= 29 && !bgDismissed && st !is AutoLocate.State.Off
        if (showBg) {
            Spacer(Modifier.height(4.dp))
            Msg("想讓 App 每小時在背景自動更新位置，請把位置權限改成「一律允許」；不改的話，每次打開 App 還是會定位。")
            Actions {
                Act("前往設定") { activity?.let { LocateHooks.askBackground(it) } }
                Act("不用了", dim = true) { AutoLocate.dismissBackgroundHint(ctx) }
            }
        }
    }
}

@Composable
private fun Msg(text: String) {
    Text(text, style = Type.small.copy(color = Ink.Ink2), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) { content() }
}

@Composable
private fun Act(label: String, dim: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        style = Type.small.copy(color = if (dim) Ink.Ink2 else Ink.Lacquer),
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

/** A gentle breathing fade while it is working. */
@Composable
private fun Pulse(content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "locating")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Column(Modifier.alpha(a)) { content() }
}
