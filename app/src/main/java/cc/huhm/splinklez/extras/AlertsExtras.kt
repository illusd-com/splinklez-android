package cc.huhm.splinklez.extras

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
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
import cc.huhm.splinklez.ui.SectionHead
import cc.huhm.splinklez.ui.Type

/**
 * Called from the recovered 防災 (AlertsScreen) LazyColumn, right after its typhoon blocks
 * (patched in by recovery/patcher/HookPatch.java). The recovered screen shows nothing when there is no storm;
 * the website says so explicitly, and now the app does too.
 */
fun typhoonEmpty(scope: LazyListScope) {
    scope.item(key = "spz-ty-empty") { TyphoonEmptyNote() }
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
            SectionHead("‧", "颱風路徑", Modifier, { Text("中央氣象署", style = Type.Kicker) })
            Spacer(Modifier.height(8.dp))
            Text(
                if (state == 3) "颱風資料暫時讀不到，稍後再試。" else "目前西北太平洋沒有中央氣象署發布路徑的熱帶性低氣壓或颱風。",
                style = Type.SerifBody,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}
