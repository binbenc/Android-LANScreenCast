# WSC 协议 v1（与 Electron / Web 端共享）

权威实现：`core/protocol`（Kotlin）。本目录的 `vectors/` 是 Kotlin 与 TS 两套实现必须 100% 通过的测试向量（技术方案 3.11）。

## 1. 发现

- mDNS 服务类型 `_wsc._tcp`，TXT：`v`=协议版本、`id`=设备 ID（随机 UUID）、`n`=设备名（UTF-8，≤200 字节）、`caps`=`h264,opus`、`fp`=证书 SHA-256 指纹前 8 字节（base64url）。
- SSDP（DLNA DMR 的响应 / NOTIFY）附带扩展头：`X-WSC-PORT`、`X-WSC-ID`、`X-WSC-FP`、`X-WSC-NAME`（URL 编码）、`X-WSC-V`。
- 二维码：`wsc://IP:port?fp=<完整指纹 base64url>&n=<16 字节随机数 base64url>&v=1&name=<URL 编码设备名>`，`n` 一次有效、60 秒过期。
- 投屏码：8 位数字 = 接收端 IPv4 末段（3 位补零）+ 5 位一次性 PIN；只在 /24~/30 网段可用。

## 2. 信令通道

WebSocket over TLS 1.3，路径 `/wsc`，首选端口 8443（占用时随机）。双向自签 ECDSA P-256 证书，指纹 = SHA-256(DER)。
客户端按完整指纹 / TXT 前缀钉扎，或在首次 PIN / 投屏码配对时不钉扎、由 SPAKE2 的 AAD 绑定指纹。心跳：5 秒 ping，约 15 秒无响应判定断开。

消息外壳：`{"t":"<TYPE>","id":<uint32>,"sid":"<sessionId>","v":1,"p":{...}}`，未知字段忽略（向后兼容）。单条消息 ≤ 64 KiB。

| t | 方向 | p |
|---|---|---|
| HELLO | 双向 | `ver, deviceId, name, model, role, caps[], resume, trusted?`；接收端回复另带 `confirmRequired, resumeOk`（`trusted` 见 2.1） |
| PAIR_REQ | 发→收 | `name, model, method`（PIN / CODE / QR / CONFIRM） |
| PAIR_PIN | 双向 | `point`：SPAKE2 公开值（SEC1 未压缩点，base64url） |
| PAIR_CONFIRM | 发→收 | `mac`：发送端确认 MAC |
| PAIR_OK | 收→发 | `mac?`：接收端确认 MAC（PIN / CODE 方式） |
| PAIR_QR | 发→收 | `mac` = HMAC-SHA256(n, fpA ‖ fpB ‖ sessionId) |
| OFFER / ANSWER | 发→收 / 收→发 | `sdp`（OFFER 另带 `iceRestart`） |
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
DataChannel 标签 `wsc`，JSON 消息以 `t` 区分：`STATS`（发送端统计）、`RSTATS`（接收端统计）、`SET_QUALITY`、`APP_SWITCHED`、`PROTECTED`、`AUDIO`。

## 5. 重连

信令断开后发送端保留采集会话，按 0.5 / 1 / 2 / 4 / 4… 秒退避（总计 30 秒）重连，发送 `HELLO{resume:true}`（同一 sid），收到 `resumeOk` 后 ICE restart。
