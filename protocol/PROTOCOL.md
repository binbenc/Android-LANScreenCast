# WSC 协议 v1（与 Electron / Web 端共享）

权威实现：`core/protocol`（Kotlin）。本目录的 `vectors/` 是 Kotlin 与 TS 两套实现必须 100% 通过的测试向量（技术方案 3.11）。

## 1. 发现

- mDNS 服务类型 `_wsc._tcp`，TXT：`v`=协议版本、`id`=设备 ID（随机 UUID）、`n`=设备名（UTF-8，≤200 字节）、`caps`=`h264,opus`（HELLO 里的 `caps` 另可带 `ctl`，见第 6 节）、`fp`=证书 SHA-256 指纹前 8 字节（base64url）。
- SSDP（DLNA DMR 的响应 / NOTIFY）附带扩展头：`X-WSC-PORT`、`X-WSC-ID`、`X-WSC-FP`、`X-WSC-NAME`（URL 编码）、`X-WSC-V`。
- 二维码：`wsc://IP:port?fp=<完整指纹 base64url>&n=<16 字节随机数 base64url>&v=1&name=<URL 编码设备名>`，`n` 一次有效、60 秒过期。
- 投屏码：8 位数字 = 接收端 IPv4 末段（3 位补零）+ 5 位一次性 PIN；只在 /24~/30 网段可用。

## 2. 信令通道

WebSocket over TLS 1.3，路径 `/wsc`，首选端口 8443（占用时随机）。双向自签 ECDSA P-256 证书，指纹 = SHA-256(DER)。
客户端按完整指纹 / TXT 前缀钉扎，或在首次 PIN / 投屏码配对时不钉扎、由 SPAKE2 的 AAD 绑定指纹。心跳：5 秒 ping，约 15 秒无响应判定断开。

消息外壳：`{"t":"<TYPE>","id":<uint32>,"sid":"<sessionId>","v":1,"p":{...}}`，未知字段忽略（向后兼容）。单条消息 ≤ 64 KiB。

| t | 方向 | p |
|---|---|---|
| HELLO | 双向 | `ver, deviceId, name, model, role, caps[], resume, trusted?`；接收端回复另带 `confirmRequired, resumeOk`（`trusted` 见 2.1）。`caps` 取值：`h264`、`h265`、`opus`、`ctl`（反向控制，第 6 节）、`game`（接收端：支持游戏模式与媒体重建，4.3）；未知能力忽略 |
| PAIR_REQ | 发→收 | `name, model, method`（PIN / CODE / QR / CONFIRM） |
| PAIR_PIN | 双向 | `point`：SPAKE2 公开值（SEC1 未压缩点，base64url） |
| PAIR_CONFIRM | 发→收 | `mac`：发送端确认 MAC |
| PAIR_OK | 收→发 | `mac?`：接收端确认 MAC（PIN / CODE 方式） |
| PAIR_QR | 发→收 | `mac` = HMAC-SHA256(n, fpA ‖ fpB ‖ sessionId) |
| OFFER / ANSWER | 发→收 / 收→发 | `sdp`；OFFER 另带 `iceRestart`、`profile?`（发送端当前画质档位名）、`rebuild?`（新建了 PeerConnection，见 4.3） |
| ICE | 双向 | `candidate, sdpMid, sdpMLineIndex`（只允许 host 候选） |
| BYE | 双向 | `reason`：USER_STOP / PROJECTION_STOPPED / SCREEN_LOCKED / RECONNECT_TIMEOUT / REPLACED / RECEIVER_EXIT / NO_DISPLAY（接收端界面未打开，约 10 秒内没有画面渲染） |
| ERROR | 双向 | `code, message, min?, max?, attemptsLeft?`；PIN_MISMATCH 非致命，其余致命 |

### 2.1 可信直连（双方都信任才免配对）

- 发送端 HELLO（非 resume）带 `trusted`：发送端本地可信列表里是否有本次 TLS 看到的接收端证书指纹。
- 接收端判定可信 = 本地信任对方指纹 **且** 发送端 `trusted != false`（字段缺失视为旧版本，沿用仅看本地的旧行为）。
  可信且无需确认 → 回 `HELLO{trusted:true}` 并进入 AUTHENTICATED；否则回 `HELLO{trusted:false}` 进入 PAIRING。
- 因此任一方移除了信任，双方都会重新走 PIN / 投屏码 / 二维码配对；接收端在 AUTHENTICATED 收到 `PAIR_REQ` 仍按非法序列处理（`ERROR{BAD_MESSAGE}` 并断开）。
  这一规则只会让认证更严格，不会让未经带外认证的设备获得信任。参考向量 `receiver-trusted-rejects-pair-req`、`receiver-trusted-but-sender-untrusted-repairs`。

### 2.2 未知枚举值（前向兼容）

新版本可能增加枚举值。解码规则：

| 字段 | 未知值降级为 | 理由 |
|---|---|---|
| `BYE.reason` | `USER_STOP` | 对方已经主动结束会话，界面显示通用的“已断开” |
| `ERROR.code` | `INTERNAL`（致命） | 不用 `BAD_MESSAGE`（那表示“你发的消息不合法”）；按致命处理最安全，界面显示通用的“连接出错了” |
| `PAIR_REQ.method`、消息类型 `t` 等其他枚举 | 不降级，按畸形消息处理 | 无法安全猜测语义（配对方式决定认证强度） |

值不是字符串（例如数字）时仍按畸形消息处理。参考向量 `bye-unknown-reason-falls-back`、`error-unknown-code-falls-back`、`bad-enum-pair-method`。

状态机与非法序列（例如认证前收到 OFFER 必须 `ERROR{UNAUTHENTICATED}` 并断开）见 `vectors/state/sequences.json`。

## 3. 配对：SPAKE2（RFC 9382，P256-SHA256-HKDF-HMAC）

- A = 发送端，B = 接收端；`idA/idB` = deviceId 的 UTF-8。
- `w = HKDF-SHA256(ikm = PIN, salt = sessionId, info = "WSC-SPAKE2-w", L = 40) mod p`。
- `AAD = fpA ‖ fpB ‖ sessionId`（指纹取自本次 TLS 会话实际看到的证书），用于派生确认密钥：中间人会导致确认 MAC 失败。
- 同一 PIN 错 3 次锁定该来源 IP 60 秒；每个 IP 30 秒内最多 5 次 PAIR_REQ。
- 测试向量：`vectors/spake2/rfc9382-p256.json`（RFC 附录 B，AAD 为空）。

## 4. 媒体

WebRTC（Unified Plan、BUNDLE、rtcp-mux），不配置 STUN/TURN，只交换 host 候选。视频 H.264 优先；音频 Opus（`stereo=1; sprop-stereo=1; maxaveragebitrate=128000; useinbandfec=1; usedtx=0`）。

**DataChannel 上未知的 `t` 一律忽略**（与信令通道把未知 `t` 当畸形消息不同）：DataChannel 消息都是提示或可重发的状态，丢一条不影响安全。
畸形、缺字段、越界的消息同样忽略。未知枚举值的降级见各字段说明。参考向量 `vectors/datachannel/messages.json`。

### 4.1 `wsc` 通道（发送端创建，有序、可靠）

| t | 方向 | 字段 |
|---|---|---|
| STATS | 发→收 | 每秒一次：`bitrateKbps, fps, width, height, rttMs?, encodeMs?, latencyMs?, lossPercent, codec?, profile?, pacerMs?, capFps?`（`pacerMs` = 每包平均排队发送耗时；`capFps` = 游戏模式下因过热 / 跟不上已降到的帧率，没降时省略） |
| RSTATS | 收→发 | 每秒一次：`jitterBufferMs?, decodeMs?, framesDropped, framesDecoded, fps, decoder?, hardwareDecoder?, procMs?, lowLatency?`（`procMs` = 每帧收到首包到解码完成的平均耗时；`lowLatency` = 解码器是否开启低延迟特性） |
| REBUILT | 收→发 | `pauseMs`：`OFFER{rebuild}` 建立的新连接**首帧已上屏**（从收到 OFFER 到首帧的停顿，毫秒）。发送端以此判定档位切换成功，见 4.3 |
| SET_QUALITY | 双向 | `profile`：SMOOTH / BALANCED / HD / GAME（未知档位：整条忽略）。只改分辨率 / 帧率 / 码率；切进 / 切出 GAME 走 4.3 的媒体重建 |
| APP_SWITCHED | 发→收 | `visible`：单应用共享时被共享应用是否在前台 |
| PROTECTED | 发→收 | `suspected`：画面可能受保护（黑帧推断） |
| AUDIO | 发→收 | `state`：ON / OFF / NO_PERMISSION / SILENT_SUSPECTED |
| CTL_REQ | 收→发 | `rid`：请求号；`want[]?`：期望能力（`tap`、`stroke`、`global`、`wheel`、`dpad`、`text`、`media`；C 期另有 `multi`） |
| CTL_STATE | 发→收 | 幂等的完整控制状态，DataChannel 打开时与任何变化时发送：`state, rid?, reason?, feat[]?, g?, remainMs?, guide?`（见 6.2；`guide` 只在 UNAVAILABLE + SERVICE_OFF 时出现，表示发送端是否已显示无障碍开启步骤） |
| CTL_END | 双向 | `reason?`（缺省 `RECEIVER_END`）：接收端取消请求 / 退出控制 |
| GEOM | 发→收 | 采集几何：`g, cw, ch, sw, sh, mode?`（见 6.3） |

### 4.2 `wsc-input` 通道（反向控制输入）

`negotiated=true`、`id=100`、有序、可靠。双方各自创建（发送端随 `wsc` 一起创建，接收端在设置远端描述后创建），不走 DCEP、不需要重新协商 SDP，ICE restart 后保持。
旧版本对端不创建该通道时，发到该流的数据被对方丢弃；是否可用由 `caps` 与 `CTL_STATE` 决定。
有序可靠的理由：DOWN / UP 与续接顺序丢失会让指针“卡住”，局域网丢包率很低，队头阻塞代价可以接受；高频的 MOVE 由接收端合并、发送端追帧处理。

| t | 方向 | 字段 |
|---|---|---|
| P | 收→发 | 指针：`s`（序号，uint32 递增）、`g`（几何代号）、`a`（`d` / `m` / `u` / `c`）、`x, y`（相对解码帧的 0..1 归一化坐标，保留 4 位小数）、`dt`（距本次手势 DOWN 的毫秒）、`i?`（指针 id，0..9，缺省 0）、`h?`（本条之前未发送的历史采样 `[[x, y, dt], …]`，从旧到新，≤32 个，每个 `dt` ≤ 本条 `dt`） |
| K | 收→发 | 按键 / 全局动作：`s`、`k`：BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / DPAD_UP / DPAD_DOWN / DPAD_LEFT / DPAD_RIGHT / DPAD_CENTER / ENTER / DEL / MENU / PLAY_PAUSE；未知值解码为 `UNKNOWN`。发送端执行的白名单与能力要求见 6.4 |
| W | 收→发 | 滚动（B 期）：`s, g, x, y`（归一化指针位置）、`dy`（-1..1，不为 0，单位 = 内容高度，> 0 = 向下滚动 / 手指上滑）。鼠标滚轮每格 0.08、50 ms 内多格合并（单条 \|dy\| ≤ 0.6）；TV 光标顶 / 底边继续按每次 ±1/3。发送端以指针为中心合成一次滑动，整体平移到屏幕上下 12% 安全区内，末段 1 px 微动后抬起（无惯性） |
| TXT | 收→发 | 文字（B 期，发送端 Android 13+）：`s, text`（1..1000 字符）。键盘逐字输入 50 ms 合并；“输入文字”整段发送，超长分多条。发送端提交到当前输入框（AccessibilityService.InputMethod），密码框回 `IREJ{SECURE_FIELD}`、没有输入框回 `IREJ{NO_EDITOR}` |
| PINCH | 收→发 | 双指缩放（C 期，PINCH 宏）：`s, g, x, y`（两指中点，归一化）、`from / to`（起止两指间距，相对内容宽度，0 < v ≤ 2，二者不等）、`dur`（50..1000 ms）、`ang?`（两指连线角度，弧度，缺省 0）。接收端在两指之一抬起时一次性发送（间距变化 < 5% 不发）；鼠标 Ctrl + 滚轮每格 ×1.25（向上 = 放大）、50 ms 合并。发送端以两笔同时开始 / 结束的多笔手势一次派发，整体平移 / 等比缩小到安全区内；不使用多指续接 |
| IREJ | 发→收 | 发送端丢弃了序号 `s` 的输入：`why` = PAUSED / GEOM_STALE / LOCAL_TOUCH / BLOCKED_TARGET / UNSUPPORTED / LOCKED / SECURE_FIELD / NO_EDITOR（未知值 → `UNKNOWN`）。每种原因每秒最多一条 |

规则：

- 单条消息 ≤ 4 KiB；坐标越界、`dt` 为负、`h` 超长或格式不对、`i` 越界、`s` 超出 uint32 → 忽略。
- 发送端只在 `CTL_STATE.state = ACTIVE` 时执行输入，其余一律丢弃；`s` 不递增的事件丢弃（允许空洞）。
- 接收端：MOVE 至多每 16 ms 发一条（期间采样放进 `h`）；按住不动时每 250 ms 发一条保活 MOVE；发送缓冲 > 16 KiB 时丢弃 MOVE，DOWN / UP / CANCEL / K 从不丢。
- 发送端：按下后 1 秒收不到任何事件，在最后位置自动抬起；几何代号不符或状态离开 ACTIVE 时取消进行中的手势。

### 4.3 画质档位与媒体重建（PRD F18 游戏模式）

接收端的播放缓冲（`WebRTC-ForcePlayoutDelay` 字段试验）和解码器在建立 PeerConnectionFactory 时固定，音视频是否同组在 `addTrack` 时固定，
会话中都不能原地修改。因此：

- **档位随 OFFER 下发**：发送端每条 OFFER 带 `profile`（档位名字符串）。接收端在建立接收链路时据此选择参数：`GAME` → 低缓冲 + 低延迟解码器 + 音频快速追赶；
  其他值、缺失（旧版发送端）或**未知值**（新版本加的档位）→ 普通参数。`profile` 是字符串而非枚举，未知值不会让整条 OFFER 解码失败。
- **媒体重建**：会话中切进 / 切出 GAME 时，发送端保留采集、编码源和音视频轨道，**新建 PeerConnection**（新的 ICE ufrag / DTLS 证书，音频按新档位放进同一个或独立的 stream），
  发送 `OFFER{profile, rebuild:true}`（`iceRestart` 为 false）。接收端收到后**先关闭**当前 PeerConnection（释放硬件解码器），按 `profile` 新建工厂与 PeerConnection，
  再应答。发送端收到 ANSWER 时才关闭旧 PeerConnection，再应用应答。两条 DataChannel（`wsc`、`wsc-input`）随新连接重建，`wsc` 打开后发送端重发 `CTL_STATE` / `GEOM` 等幂等状态。
  这一过程不经过重连（不重新 HELLO / 配对 / 授权录屏），信令状态机按普通的“STREAMING 中再次 OFFER”处理（向量 `sender-media-rebuild-while-streaming`、`receiver-media-rebuild-while-streaming`）。
  双方都按 PeerConnection 代号丢弃旧连接的回调，旧连接的关闭不算断线。
- **能力协商**：只有接收端 HELLO 的 `caps` 含 `game` 时发送端才会发 `rebuild`。没有时（旧版接收端）会话中切到 GAME 只通过 `SET_QUALITY` 改分辨率与帧率，
  发送端提示“对方版本较旧”；旧版接收端忽略 OFFER 里的未知字段，行为与以前一致。
- **切换成功的判定**：接收端在新连接的**首帧上屏**后，在新连接的 `wsc` 通道上回 `REBUILT{pauseMs}`（通道尚未打开时排队，打开后立即发送）。发送端只以收到 `REBUILT` 为切换成功，ICE 连通不算（低端盒子可能连通了却解不出画面）。
- **超时回退**：发送端 5 秒内没有收到 `REBUILT` 就以同样方式重建回原档位；回退也失败则按普通断线进入重连（ICE 不通时仍以 `rebuild` 重建，任何状态下都安全）。
  信令在重建途中断开、重连 `resumeOk` 后，发送端直接按当前档位再重建一次，而不是 ICE restart。
- `rebuild` 只在已认证的会话中出现；接收端没有活动会话时把它当成新会话的首个 OFFER。同档位的重建（发送端的恢复手段）接收端不显示“正在切换”。

## 5. 重连

信令断开后发送端保留采集会话，按 0.5 / 1 / 2 / 4 / 4… 秒退避（总计 30 秒）重连，发送 `HELLO{resume:true}`（同一 sid），收到 `resumeOk` 后 ICE restart。

## 6. 反向控制（PRD F23）

接收端（手机 / 平板 / TV）操作发送端。发送端通过无障碍服务**只注入**点击、手势与白名单全局动作，不读取屏幕内容。只用于 WSC 镜像会话：DLNA 与兼容 Apple 设备的会话没有 `wsc-input` 通道。

### 6.1 能力与安全前提

- HELLO `caps` 带 `ctl`：发送端 = 本构建能被控制；接收端 = 能发起请求。是否真的可用看 `CTL_STATE`。
- 所有控制消息都走 DataChannel：它只在已认证的 WSS 信令交换 SDP 之后建立，DTLS 指纹来自认证后的信令，所以 DataChannel 继承信令的认证（未认证设备无法建立 DataChannel）。
- 授权**只由发送端决定**：接收端只能请求；每次会话都要发送端本人在确认页点“允许本次”，可信设备也不跳过（PRD 决策 #16）。

### 6.2 授权状态机（发送端，向量 `vectors/state/control-sequences.json`）

| state | 含义 |
|---|---|
| UNAVAILABLE | 不能被控制：`reason` = SETTING_OFF（设置未开启）/ SERVICE_OFF（无障碍服务未开启，发送端已显示开启步骤）/ AAPM（Android 17 高级保护模式）/ ELDER_MODE（发送端开着长辈模式，F24；接收端提示“对方手机开着长辈模式，不能远程操作”，不指向任何设置入口；旧版接收端按 UNKNOWN 处理） |
| IDLE | 可以请求；`reason` 为上一次结束的原因：USER_DENIED / TIMEOUT / CANCELLED / COOLDOWN / REVOKED / RECEIVER_END / LOCKED / SESSION_END |
| PENDING | 等待发送端本人确认，`remainMs` = 剩余毫秒（30 秒超时 = 拒绝） |
| ACTIVE | 已授权，正在注入 |
| PAUSED | 已授权但不注入，`reason` = RECONNECTING / PROTECTED / APP_HIDDEN / WINDOWED / OWN_APP（发送端正在使用本应用自己的界面，D9）/ SENSITIVE_APP（发送端前台是系统安装器或无障碍设置，C 期）；条件消失后自动回到 ACTIVE |

- 同时有多个暂停原因时，`CTL_STATE.reason` 按固定优先级给出一个：OWN_APP > SENSITIVE_APP > PROTECTED > WINDOWED > APP_HIDDEN > RECONNECTING（向量 `pause-priority-own-app-over-sensitive`）。
- 冷却：被拒、超时、接收端取消、发送端收回、回复 UNAVAILABLE 后 10 秒；**同一会话第二次被本人拒绝起 60 秒**。冷却期内的 CTL_REQ 回 `IDLE{reason: COOLDOWN, remainMs}`（仍不可用时回 `UNAVAILABLE{reason, remainMs}`）。
- 接收端同样按 `remainMs`（缺省 10 秒）在本地冷却，包括**对方主动收回（REVOKED）之后**，冷却期内“请求控制”不可用。
- 锁屏 / 熄屏：请求或授权立即结束（`IDLE{LOCKED}`），解锁后**不**自动恢复；锁屏时收到请求直接回 `IDLE{LOCKED}`，不弹确认页。
- 同一会话（同一 `sid`）30 秒内断线重连：只是 RECONNECTING 暂停，`resumeOk` 后自动回到 ACTIVE，不再确认；新会话必须重新请求。
- 未知 `state` 降级为 UNAVAILABLE（接收端停止发送输入），未知 `reason` 降级为 UNKNOWN。

### 6.3 坐标与几何

- 接收端：触点去掉自身 ASPECT_FIT 黑边，换算为相对**解码帧**（已应用旋转）的 0..1 坐标；落在黑边上的按下丢弃，移动 / 抬起夹到视频边缘。
- `GEOM`：`cw×ch` = 采集帧（16 对齐的 VirtualDisplay 尺寸），`sw×sh` = 被采集内容（整屏 = 屏幕逻辑像素；单应用共享 = 被共享窗口），`mode` = FULL / APP。每次采集尺寸、旋转、被采集内容尺寸变化时 `g` 加一。
- 发送端：内容在帧里按原比例居中缩放（16 对齐带来几像素黑边），归一化坐标 → 帧像素 → 去黑边 → 内容像素 → 屏幕坐标。
- 接收端为每个事件选择“最近一次宽高比与当前渲染帧一致的 `g`”（容差 1.5%：WebRTC 自适应降分辨率只等比缩放）；没有匹配（旋转瞬间）不发送。发送端只接受当前 `g`，否则回 `IREJ{GEOM_STALE}` 并取消手势。
- 被采集内容不是整块屏幕（单应用共享的分屏 / 小窗）时拿不到窗口在屏幕上的偏移：PAUSED{WINDOWED}。

### 6.4 发送端注入约束

- 按键白名单：BACK / HOME / RECENTS（全部版本）；DPAD_*（GLOBAL_ACTION_DPAD_*）与 ENTER / DEL（发给当前输入框）需要 Android 13+；PLAY_PAUSE（GLOBAL_ACTION_MEDIA_PLAY_PAUSE）需要 Android 16+。不满足回 `IREJ{UNSUPPORTED}`。NOTIFICATIONS、QUICK_SETTINGS、MENU、LOCK_SCREEN、POWER_DIALOG 等不开放。
- 能力 `CTL_STATE.feat` 按发送端系统版本：29+ = tap / stroke / global / wheel / multi；33+ 另加 dpad / text；36+ 另加 media（`ControlFeatures.forSdk`）。旧版本发送端没有 multi 时接收端不发送 PINCH 并提示更新。接收端据此降级（例如对方低于 13 时“方向键模式”“输入文字”不可用并说明原因）。
- 敏感界面（C 期）：发送端无障碍服务只订阅 `TYPE_WINDOW_STATE_CHANGED`，只取事件元数据中的前台包名 / 类名（不读取窗口内容）。前台为系统 / ROM 安装器、权限控制器或无障碍设置时 PAUSED{SENSITIVE_APP}；系统界面中的录屏授权 / USB 与无线调试授权也算敏感；通知栏 / 音量条、输入法、Toast、`android` 框架包与本应用窗口不改变判断（失败向收紧）。本机用户可在暂停胶囊 / 投屏页点“继续控制”临时放行当前敏感界面。已知缺口：从设置首页进入的无障碍页面（通用 SubSettings）识别不到。
- 发送端本应用自己的任何界面在前台时暂停（PAUSED{OWN_APP}，确认页除外），远端不能操作投屏应用本身。
- 接收端输入映射（实现约定，不影响线上格式）：TV 按 keycode 区分——DPAD_* / DPAD_CENTER 一律跟随当前 TV 模式（光标 → P；方向键 → K{DPAD_*}），即**外接键盘的方向键在 TV 上也移动光标**；字符（TXT）、Esc（K{BACK}）、Meta（K{HOME}）、Alt+Tab（K{RECENTS}）、Backspace（K{DEL}）、KEYCODE_ENTER（K{ENTER}）用键盘语义。手机 / 平板接收端：键盘方向键 = K{DPAD_*}。离散按键只在首个按下发送，按住不重复。
- 手势开始（DOWN）落在以下区域时丢弃并回 `IREJ{BLOCKED_TARGET}`：屏幕边缘的“控制中”胶囊、状态栏区域（**不能从状态栏下拉通知栏**；通知栏若已被其他方式拉下，A 期不拦截其中内容）、发送端的取消吸收窗口；发送端确认页显示期间丢弃一切输入。
- 本机有人触摸屏幕导致注入被系统取消：放弃该手势并回 `IREJ{LOCAL_TOUCH}`（本地操作优先）。
- **中止不产生点击**：发送端发起的中止（收回、锁屏、会话结束、暂停、几何变化、1 秒无事件超时、新的 DOWN 打断）不在最后位置抬起，而是派发一个新手势让系统给进行中的笔画发 ACTION_CANCEL；该手势落在发送端自己的透明吸收窗口上。接收端的 `c` 同样按中止处理。
