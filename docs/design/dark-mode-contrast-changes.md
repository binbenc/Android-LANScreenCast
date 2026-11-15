# 深色模式文字对比度优化 · Android 修改清单（交互稿 v1.0.1）

- 交互稿：`docs/design/交互稿与色卡-v1.0.html`
  - 第 02 节色卡、第 04 节对比度表（含 APCA 列、"深色模式文字规则"面板、变更前后对照表）、第 14 节变更记录
  - 页面内数值是用 WCAG 2.x 和 APCA 公式实时算出的，**以页面为准**。本文数值为手算，误差约 ±0.02。
- 日期：2026-11-15。只改深色主题，浅色主题不动。最终 token 值与计划一致，没有调整。

## 1. 结论：为什么 token 原本已过 7:1，用户仍觉得"字不明显"

1. **代码缺陷（P0，读代码发现，需要真机确认）**
   - M3 `LocalContentColor` 的默认值是 `Color.Black`。
   - `MainActivity.kt:78` 用的是 `Box(Modifier.background(surface))`，不是 `Surface`；`WscAppTheme` 也没有提供 `LocalContentColor`；`Type.kt` 的样式不带颜色。
   - 结果：不在 Scaffold/Surface 下、又没写 `color` 的 `Text`，以及没写 `tint` 的 M3 `Icon`，会在 `#111418` 上画成黑色，约 1.1:1。
2. **感知**：WCAG 2 公式会高估深底浅字的可读性。用 APCA 复核：
   - 旧 onSurfaceVariant 在 surface 上是 Lc 72，在 surfaceContainerHigh（TV 凭据卡）上是 Lc 69，低于正文 Lc 75 的下限。
   - TV 3 m 观看时，字的视角只有手机的 0.79 倍，要求更高。
3. **语义**：IP / 端口是要抄写的凭据，却用了次要样式（bodySmall 14sp + onSurfaceVariant）。

## 2. Token 新旧值（重新生成 `core/ui/.../theme/Color.kt`）

| Kotlin val | 旧 | 新 | 说明 |
|---|---|---|---|
| `onSurfaceDark` | `0xFFE1E2E8` | `0xFFE6E8EE` | 给层级留空间；依然不是纯白（避免大屏光晕）。TV 焦点底色随之变化 |
| `onSurfaceVariantDark` | `0xFFC2C7CF` | `0xFFD0D5DD` | 次要文字主修项 |
| `onPrimaryContainerDark` | `0xFFD2E4FA` | `0xFFE9F1FD` | 港湾蓝色阶 95；TV 步骤序号、选中项 |
| `inversePrimaryDark` | `0xFF2F5A82` | `0xFF234766` | 港湾蓝色阶 30；Snackbar 操作按钮 |

- 其余 token 不变：surface 系列、primary、各状态色、outline、`inverseSurfaceDark` 仍是 `#E1E2E8`。
- `outline` 值不变，只新增一个用途：**不可用文字 / 图标**。

## 3. 对比度前后（深色，WCAG 2.x；APCA 写的是 |Lc|，仅作参考）

| 前景 / 背景 | 场景 | WCAG 旧 → 新 | APCA 旧 → 新 |
|---|---|---|---|
| onSurface / surface `#111418` | 正文 | 14.29 → 15.07 | 88.6 → 92.2 |
| onSurface / scLow `#191C20` | 卡片正文 | 13.23 → 13.96 | — |
| onSurface / sc `#1D2024` | 导航 / 键盘 | 12.65 → 13.35 | — |
| onSurface / scHigh `#272A2F` | 卡片正文、TV IP | 11.14 → 11.75 | 85.5 → 89.1 |
| onSurface / scHighest `#32353A` | TV 未聚焦按钮 | 9.42 → 9.94 | — |
| onSurfaceVariant / surface | 次要文字、TV 步骤引导语 | 10.87 → 12.53 | 71.7 → 80.1 |
| onSurfaceVariant / scLowest `#0C0E12` | — | 11.37 → 13.10 | — |
| onSurfaceVariant / scLow | 卡片 / 弹层次要文字 | 10.07 → 11.60 | — |
| onSurfaceVariant / sc | — | 9.63 → 11.09 | — |
| onSurfaceVariant / scHigh | 胶囊、TV 凭据卡 | 8.48 → 9.77 | 68.7 → 77.0 |
| onSurfaceVariant / scHighest | 对话框 | 7.17 → 8.26 | 66.1 → 74.5 |
| onSurfaceVariant / surfaceBright `#37393E` | — | 6.80 → 7.84 | — |
| onSurfaceVariant / surfaceVariant `#42474E` | 输入框底（很少放文字，AA 即可） | 5.51 → 6.35 | — |
| onSurfaceVariant / primaryContainer `#234766` | **不应出现**（见 4.2 ChoiceRow） | 5.71 → 6.58 | — |
| onPrimaryContainer / primaryContainer | 选中项、TV 步骤序号 | 7.49 → 8.53 | 77.8 → 86.9 |
| inversePrimary / inverseSurface | Snackbar 按钮 | 5.59 → 7.50 | — |
| inverseOnSurface / onSurface | TV 焦点反相文字 | 10.11 → 10.67 | — |
| onSurface / scHighest（图形） | TV 焦点底 vs 非焦点底 | 9.42 → 9.94 | — |
| onSurfaceVariant / scHigh@90% 叠在纯白画面上 | TV 统计 / 重连浮层（最差情况） | 6.20 → 7.15 | — |
| 不可用：onSurface@38% / surface → outline | 不可用文字 | 3.03 → 5.83 | — |
| 不可用：onSurface@38% / scHighest → outline | 对话框内不可用 | 2.71（不达标） → 3.84 | — |
| 不可用：PinInput 38% / scLowest → outline | PIN 键盘禁用数字 | 3.02 → 6.09 | — |
| 不可用：TV 按钮整体 38% → sc 底 + outline | TV 不可用按钮 | 约 2.7 → 5.16 | — |

未变且都 >= 7 的组合：

| 组合 | 对比度 |
|---|---|
| primary / surface | 10.68 |
| onPrimary / primary | 7.83 |
| onSecondaryContainer | 7.20 |
| onTertiaryContainer | 7.29 |
| error / surface | 10.88 |
| onErrorContainer | 7.24 |
| success / scLow | 10.27 |
| onSuccessContainer | 7.19 |
| warning / scLow | 10.02 |
| onWarningContainer | 7.19 |
| 延迟色 1–4 / scHigh | 8.65 / 8.48 / 8.44 / 8.48 |
| inverseOnSurface / inverseSurface | 10.11 |
| outline（图形）/ scHighest | 3.84 |

浅色主题（本期不改）：

- 所有文字都 >= 4.5（AA）。onSurface 16.2，onSurfaceVariant 8.9（在 scHighest 上 7.2）。
- 以下只达 AA、没到 AAA，可以后续处理：primary / surface 6.86，error 6.14，warning / scLow 5.82，延迟色 1–4 / scHigh 5.21–5.30。
- outline 作为不可用文字：4.28（surface）/ 3.48（scHighest），满足 >= 3。

## 4. 需改代码位置

### 4.1 P0 内容色缺失（一次性修复）

- 推荐做法：在 `core/ui/src/main/java/com/benchan/wireless/screencasting/core/ui/theme/Theme.kt` 的 `WscAppTheme` 里，`MaterialTheme { ... }` 内部包一层 `CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface)`。
- 等价做法：把 `app/src/main/java/com/benchan/wireless/screencasting/MainActivity.kt:78` 的 `Box(...background(surface))` 换成 `Surface(color = MaterialTheme.colorScheme.surface)`。
- TV 组件用的是 `androidx.tv.material3.Text`，由 tv `Button` / `Surface` 自己提供内容色，不受影响。

修复前会画成黑色的位置（文件:行）：

- `feature/receiver/.../ui/StandbyScreen.kt`
  - `:107`：TvButton 的 leading 里用的是 **M3** `Icon`，没写 tint，设置图标是黑色
  - `:135`："iPhone / iPad 用户"
  - `:203`：步骤文字（`Step()`）
- `feature/receiver/.../ui/PlaybackScreen.kt`：`:170` `:188` `:261` `:269` `:313` `:316`
- `feature/receiver/.../ui/AppleCompatScreens.kt:191`
- `feature/settings/.../InstallHelpScreen.kt`：`:87` `:117` `:141` `:147` `:148` `:149`
- `feature/settings/.../DiagnosticsScreens.kt`：`:92` `:99` `:142`
- 发送端各屏都在 `SenderScreen.kt:249` 的 `Scaffold` 内，不受影响。

### 4.2 P1 透明度与 token 用法

| 位置 | 现状 | 改为 |
|---|---|---|
| `core/ui/.../components/PinInput.kt:196` | `c.onSurface.copy(alpha = 0.38f)` | `c.outline` |
| `core/ui/.../components/TvComponents.kt:53-60`（`TvButton`） | 未设置 disabled 颜色 | `disabledContainerColor = c.surfaceContainer`，`disabledContentColor = c.outline`；同步改 `:31` 注释"不可用 38%" |
| `feature/sender/.../ui/CastingScreens.kt:164/:165/:167`（`ChoiceRow` 选中态，底为 primaryContainer） | 标题默认色 / "推荐"用 primary（5.61）/ 说明用 onSurfaceVariant | 选中时标题和说明都用 `onPrimaryContainer`；"推荐"改为 primary 底 + onPrimary 字的小标签（与交互稿 06 一致） |
| `feature/receiver/.../ui/StandbyScreen.kt:173`（IP : 端口） | bodySmall + onSurfaceVariant | `bodyMedium`（TV 16sp）+ `onSurface` + 等宽字体 |
| `feature/receiver/.../ui/StandbyScreen.kt:157`（扫码连接 · 免输入 PIN） | bodySmall | `bodyMedium` |
| `feature/receiver/.../ui/StandbyScreen.kt:200-201`（步骤序号） | primaryContainer / onPrimaryContainer + labelLarge | 保留配色；字重 600（换新 token 后为 8.53:1） |
| `feature/receiver/.../ui/PlaybackScreen.kt:164` `:257` | `surfaceContainerHigh.copy(alpha = 0.9f)` | 可以保留（最差 7.15），**不要再调低** |

以下 alpha 已核对，不用改（都不是文字用途，或已达标）：

- `scrim.copy(alpha = ScrimAlpha.Text)` 配 onScrim：最差 5.74，满足交互稿定的 >= 4.5
- `TvFocus.kt:157` 焦点叠色
- `TvComponents.kt:65/:92` 光晕

### 4.3 规则（之后写代码照此执行）

- 文字不叠加透明度。层级只用 token：onSurface → onSurfaceVariant → outline（不可用）。
- 任何 `*Container` 底上只用对应的 `on*Container`。
- 叠在视频 / 相机画面上的文字用 `onScrim`，背后 scrim >= 60%。
- TV 上需要读或抄写的信息用 onSurface 且 >= 16sp。

## 5. 验收标准

1. `Color.kt` 中 4 个深色 val 与第 2 节一致；`grep "copy(alpha"` 的结果只剩 scrim / 焦点叠色 / 光晕 / PlaybackScreen 浮层底这几类。
2. 文字对比度：深色主题所有文字组合 >= 7:1（surfaceVariant 底除外，>= 4.5）；不可用文字 >= 3:1。可用交互稿第 04 节核对，或用 Android Accessibility Scanner 抽检。
3. 真机 TV，深色，3 m：
   - 待机页步骤文字、"iPhone / iPad 用户"、设置按钮图标都清晰可见（不再是黑色）
   - IP : 端口能准确抄写
   - 步骤序号数字清晰
4. 真机手机深色：
   - 安装帮助页、网络诊断页正文可见
   - PIN 键盘禁用态数字可辨认，且明显弱于可用态
   - 投屏说明卡选中项的说明文字清晰
5. 浅色主题截图与改动前一致（回归）。

---
反向控制（F23）的页面见交互稿第 12 章（R01–R11），本清单不涉及。
