package com.benchan.wireless.screencasting.core.ui.components

import androidx.compose.ui.res.stringResource
import com.benchan.wireless.screencasting.core.ui.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** “已信任”：tertiary 盾牌，区别于“已连接”的绿色（交互稿 08 图标规则）。 */
@Composable
fun TrustedBadge(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.background(c.tertiaryContainer, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(12.dp), tint = c.onTertiaryContainer)
        Text(stringResource(R.string.core_ui_badge_trusted), style = MaterialTheme.typography.labelSmall, color = c.onTertiaryContainer)
    }
}

@Composable
fun NewDeviceBadge(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.border(1.dp, c.outline, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(stringResource(R.string.core_ui_badge_new_device), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
}

/** 协议用 图标 + 等宽文字标签 双重区分（WSC / DLNA），不依赖颜色。 */
@Composable
fun ProtocolTag(label: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        label,
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

/** “🔒 已加密 · WSC”。 */
@Composable
fun EncryptedLabel(protocol: String, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = c.tertiary)
        Text(stringResource(R.string.core_ui_encrypted, protocol), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** 设备头像（primaryContainer 底 + 图标）。 */
@Composable
fun DeviceAvatar(icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.size(40.dp).background(if (enabled) c.primaryContainer else c.surfaceContainerHigh, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) c.onPrimaryContainer else c.onSurfaceVariant)
    }
}
