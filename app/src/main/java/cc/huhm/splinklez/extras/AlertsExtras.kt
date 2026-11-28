package cc.huhm.splinklez.extras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import cc.huhm.splinklez.ui.Ink
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.huhm.splinklez.data.Graph
import cc.huhm.splinklez.ui.SpzHead
import cc.huhm.splinklez.ui.Type

/**
 * Called from the recovered 防災 (AlertsScreen) LazyColumn, right after its typhoon blocks
 * (patched in by recovery/patcher/HookPatch.java). The recovered screen shows nothing when there is no storm;
 * the website says so explicitly, and now the app does too.
 */
fun typhoonEmpty(scope: LazyListScope) {
    scope.item(key = "spz-ty-empty") { TyphoonEmptyNote() }
    scope.item(key = "spz-1.3-tools") { DisasterTools() }
}

/** 1.3.0 entries: typhoon track/strike probability and the shelter/AED/toilet map. Always shown, stacked rows. */
@Composable
private fun DisasterTools() {
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(420)) + expandVertically(tween(420))) {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Entry("🌀 颱風路徑潛勢・侵襲機率", "各鄉鎮七級風暴風圈侵襲機率") { ctx.startActivity(Intent(ctx, TyphoonActivity::class.java)) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Hair))
            Entry("🧭 防災設施地圖", "最近的避難收容處所、AED、公廁") { ctx.startActivity(Intent(ctx, FacilitiesActivity::class.java)) }
        }
    }
}

@Composable
private fun Entry(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.serifBody)
            Text(sub, style = Type.small)
        }
        Text("→", style = Type.serifBody)
    }
}

@Composable
private fun TyphoonEmptyNote() {
    // 0 loading, 1 none, 2 some (the recovered panel shows them), 3 failed
    var state by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        state = runCatching { Graph.api.typhoons() }.fold({ if (it.isEmpty()) 1 else 2 }, { 3 })
    }
    AnimatedVisibility(state == 1 || state == 3, enter = fadeIn(tween(350)) + expandVertically(tween(400))) {
        Column(Modifier.fillMaxWidth()) {
            SpzHead("‧", "颱風路徑", { Text("中央氣象署", style = Type.kicker) })
            Spacer(Modifier.height(8.dp))
            Text(
                if (state == 3) "颱風資料暫時讀不到，稍後再試。" else "目前西北太平洋沒有中央氣象署發布路徑的熱帶性低氣壓或颱風。",
                style = Type.serifBody,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}
