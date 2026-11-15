package com.benchan.wireless.screencasting.feature.sender.ui

import com.benchan.wireless.screencasting.feature.sender.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 从本应用以外进入发送端首页的一次性意图（F14 分享页 / Direct Share 桌面快捷方式）。 */
sealed interface SenderEntry {
    /** 分享页 S03 [打开遥控]。 */
    data object OpenPushRemote : SenderEntry

    /** 分享页 S04 [改用屏幕镜像]：列表顶部横幅。 */
    data object MirrorHint : SenderEntry

    /** 为这台 DLNA 设备打开内容面板（P03）。 */
    data class PushTo(val udn: String) : SenderEntry
}

/**
 * 设备列表顶部的常驻提示卡（不自动消失，点按钮关闭）。最后一个动作是主动作（Tonal），其余为文字按钮；
 * [onClose] 不为空时右上角有 ✕（读作“不用了，不再提示”）。
 */
@Composable
fun ListNoticeCard(
    title: String,
    detail: String?,
    actions: List<Pair<String, () -> Unit>>,
    onClose: (() -> Unit)? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .background(c.primaryContainer, RoundedCornerShape(16.dp)).padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 4.dp)
            .semantics(mergeDescendants = false) {},
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            icon?.let { androidx.compose.material3.Icon(it, null, tint = c.onPrimaryContainer); androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp)) }
            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = c.onPrimaryContainer)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer) }
            }
            onClose?.let {
                androidx.compose.material3.IconButton(onClick = it) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Close, contentDescription = androidx.compose.ui.res.stringResource(R.string.sender_notice_dismiss), tint = c.onPrimaryContainer)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            actions.forEachIndexed { i, (label, onClick) ->
                if (i == actions.lastIndex && actions.size > 1) {
                    androidx.compose.material3.FilledTonalButton(onClick = onClick) { Text(label) }
                } else {
                    TextButton(onClick = onClick) { Text(label, color = c.onPrimaryContainer) }
                }
            }
        }
    }
}

@Composable
internal fun MirrorHintCard(onDismiss: () -> Unit) = ListNoticeCard(androidx.compose.ui.res.stringResource(R.string.share_mirror_hint), null, listOf(androidx.compose.ui.res.stringResource(R.string.sender_hint_dismiss) to onDismiss))
