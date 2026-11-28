package cc.huhm.splinklez.extras

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import cc.huhm.splinklez.BuildConfig
import cc.huhm.splinklez.announce.AnnouncementActivity
import cc.huhm.splinklez.announce.UpdateLogActivity
import cc.huhm.splinklez.announce.openLink
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.data.TyphoonBrief
import cc.huhm.splinklez.ui.AppViewModel
import cc.huhm.splinklez.ui.Ink
import cc.huhm.splinklez.ui.SpzHead
import cc.huhm.splinklez.ui.Tab
import cc.huhm.splinklez.ui.Type
import cc.huhm.splinklez.ui.short
import java.time.OffsetDateTime

/** Extra 頭版 pieces the website has: the 颱風動態 card (only while a storm is active) and the footer links. */
object HomeExtras {
    private const val STALE_MS = 18 * 3600_000L

    /** Cached for the process so scrolling back up doesn't refetch; refreshed after 10 minutes. */
    private var cache: Pair<Long, List<TyphoonBrief>>? = null

    private fun fresh(t: TyphoonBrief): Boolean =
        runCatching { System.currentTimeMillis() - OffsetDateTime.parse(t.time).toInstant().toEpochMilli() < STALE_MS }.getOrDefault(true)

    @Composable
    fun TyphoonHome() {
        var list by remember { mutableStateOf(cache?.second.orEmpty()) }
        LaunchedEffect(Unit) {
            val c = cache
            if (c == null || System.currentTimeMillis() - c.first > 10 * 60_000) {
                runCatching { Graph.api.typhoons() }.onSuccess { cache = System.currentTimeMillis() to it; list = it }
            }
        }
        val active = list.filter(::fresh)
        AnimatedVisibility(active.isNotEmpty(), enter = fadeIn(tween(400)) + expandVertically(tween(450))) {
            val ctx = LocalContext.current
            val vm = remember(ctx) { (ctx as? ComponentActivity)?.let { runCatching { ViewModelProvider(it)[AppViewModel::class.java] }.getOrNull() } }
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                SpzHead("‧", "颱風動態", {
                    Text("防災頁 →", style = Type.small.copy(color = Ink.Lacquer), modifier = Modifier.clickable { vm?.tab?.value = Tab.Alerts })
                })
                Spacer(Modifier.height(8.dp))
                active.forEach { t -> StormRow(t) { vm?.tab?.value = Tab.Alerts } }
            }
        }
    }

    @Composable
    private fun StormRow(t: TyphoonBrief, onOpen: () -> Unit) {
        val pulse = rememberInfiniteTransition(label = "storm")
        val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape).border(1.dp, Ink.Hair, shape).clickable(onClick = onOpen).padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 6.dp).size(9.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(Ink.Up))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val title = if (!t.tyNo.isNullOrBlank()) "第 ${t.tyNo} 號颱風 ${t.name}" else "熱帶性低氣壓 ${t.name}"
                Text(title, style = Type.serifBody.copy(fontWeight = FontWeight.Bold))
                if (t.intlName.isNotBlank() && t.intlName != t.name) Text(t.intlName, style = Type.small)
                val facts = listOfNotNull(
                    t.pressure?.let { "中心氣壓 $it 百帕" },
                    t.wind?.let { "近中心最大風速每秒 $it 公尺" },
                    t.r15?.let { "七級風暴風半徑 $it 公里" },
                )
                if (facts.isNotEmpty()) Text(facts.joinToString("・"), style = Type.body, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "位於北緯 ${"%.1f".format(t.lat)}° 東經 ${"%.1f".format(t.lon)}°" + (t.moving?.takeIf { it.isNotBlank() }?.let { "，$it" } ?: "") + "\u3000${short(t.time)} 更新",
                    style = Type.small, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    @Composable
    fun FooterLinks() {
        val ctx = LocalContext.current
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hair))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Link("公告") { ctx.startActivity(Intent(ctx, AnnouncementActivity::class.java)) }
                Link("更新日誌") { ctx.startActivity(Intent(ctx, UpdateLogActivity::class.java)) }
                Link("隱私權政策") { openLink(ctx, BuildConfig.API_BASE + "/privacy") }
                Link("服務條款") { openLink(ctx, BuildConfig.API_BASE + "/terms") }
            }
            Text("Splinklez ${BuildConfig.VERSION_NAME}", style = Type.small, modifier = Modifier.padding(top = 8.dp))
        }
    }

    @Composable
    private fun Link(label: String, onClick: () -> Unit) {
        Text(label, style = Type.small.copy(color = Ink.Ink2), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))
    }
}
