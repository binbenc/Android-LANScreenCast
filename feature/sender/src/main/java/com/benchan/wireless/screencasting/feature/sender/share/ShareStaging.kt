package com.benchan.wireless.screencasting.feature.sender.share

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.benchan.wireless.screencasting.core.common.WscLog
import com.benchan.wireless.screencasting.core.data.JsonFileStore
import com.benchan.wireless.screencasting.protocol.dlna.dmc.DmcController
import com.benchan.wireless.screencasting.protocol.dlna.dmc.PushSessionState
import com.benchan.wireless.screencasting.protocol.upnp.MediaKind
import com.benchan.wireless.screencasting.protocol.upnp.MediaMime
import com.benchan.wireless.screencasting.protocol.upnp.dmc.PushError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** 分享进来的一项（到达即检查之后）。显示名只在手机界面用，不写日志。 */
data class SharedItem(
    val index: Int,
    val source: Uri,
    val kind: MediaKind,
    val mime: String,
    val displayName: String?,
    val sizeBytes: Long?,
    val seekable: Boolean,
    val mode: ReadMode,
)

enum class ItemPhase { WAITING, COPYING, READY, FAILED, SKIPPED }

/** 准备过程中某项失败的原因（交互稿 S05 ⑤⑧⑨）。 */
enum class CopyFailure { NO_SPACE, READ_FAILED, GONE, TOO_LARGE }

data class ItemProgress(
    val phase: ItemPhase = ItemPhase.WAITING,
    val copiedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failure: CopyFailure? = null,
)

/**
 * 分享文件的准备（决策 #42，技术方案 3.14.3）：
 * - 照片 / 音乐（以及策略判定为“复制”的视频）按分享顺序复制到 `cache/share/<批次>/`，推送全程读副本；
 *   视频优先把读授权转交给推送前台服务（[ReadMode.TRANSFER]），直接读原文件。
 * - 复制前查空间（[SpacePlanner]），复制中可取消；大小未知的流边写边查余量。
 * - 推送引擎通过 [DmcController.PushInputGate] 等待某项就绪：第 1 项好了就开始播放，其余边放边准备（交互稿 Q15-B）。
 * - 清理：分享页放弃（未推送）立即删；推送结束 / 失败 / 被替换 / 换设备立即删；进程被杀或重启后下次启动清掉残留（[cleanupStale]）。
 * 日志只记录方式、耗时、大小区间和失败类别，不记录文件名和地址。
 */
@Singleton
class ShareStaging @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dmc: DmcController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver: ContentResolver = context.contentResolver
    private val batches = ConcurrentHashMap<String, Batch>()
    private val memory = JsonFileStore(File(context.noBackupFilesDir, "share_copy_sources.json"), ListSerializer(String.serializer())) { emptyList() }

    /** 一次分享的准备批次。 */
    inner class Batch internal constructor(val id: String, val dir: File, val items: List<SharedItem>) {
        private val _progress = MutableStateFlow(items.map { ItemProgress(totalBytes = it.sizeBytes) })
        val progress: StateFlow<List<ItemProgress>> = _progress.asStateFlow()

        /** 交给推送引擎的地址：复制项 = 缓存副本（本应用 FileProvider），转交项 = 原地址。 */
        val pushUris: List<Uri> = items.map { if (it.mode == ReadMode.COPY) cacheUri(this, it) else it.source }

        @Volatile
        var limit: Int = items.size
            internal set

        @Volatile
        var cancelled = false
            internal set

        internal var job: Job? = null
        internal var sessionId: String? = null
        internal var boundAt = 0L
        internal var seen = false
        internal val startedAt = System.currentTimeMillis()

        internal fun set(i: Int, p: ItemProgress) = _progress.update { l -> l.mapIndexed { idx, old -> if (idx == i) p else old } }
        internal fun get(i: Int): ItemProgress = _progress.value[i]
    }

    init {
        // 推送引擎准备本地项前先等它就绪（只影响本批次的地址，其他来源直接放行）
        dmc.inputGate = DmcController.PushInputGate { uri -> await(uri) }
        scope.launch { dmc.session.collect { onSession(it) } }
    }

    // ------------------------------------------------------------------ 批次

    /** 开始准备（到达即开始，与找设备并行，交互稿 S01“准备文件”）。 */
    fun start(items: List<SharedItem>): Batch {
        val id = UUID.randomUUID().toString().take(12)
        val dir = File(cacheRoot(context), id).apply { mkdirs() }
        val batch = Batch(id, dir, items)
        batches[id] = batch
        items.forEachIndexed { i, it -> if (it.mode == ReadMode.TRANSFER) batch.set(i, ItemProgress(ItemPhase.READY, totalBytes = it.sizeBytes)) }
        val copies = items.count { it.mode == ReadMode.COPY }
        WscLog.i(TAG, "staging start: ${items.size} item(s), copy=$copies, transfer=${items.size - copies}")
        batch.job = scope.launch { copyAll(batch) }
        return batch
    }

    /** 空间不足时“只推送前 n 项”（交互稿 S05 ⑧）：后面的项不再复制。 */
    fun restrict(batch: Batch, count: Int) {
        batch.limit = count.coerceIn(0, batch.items.size)
        for (i in batch.limit until batch.items.size) {
            if (batch.get(i).phase != ItemPhase.READY) batch.set(i, ItemProgress(ItemPhase.SKIPPED))
        }
    }

    /** 放弃（分享页关闭 / 取消推送 / 新分享替换旧分享）：停止复制并删除已复制的文件。 */
    fun cancel(batch: Batch) {
        if (batch.cancelled) return
        batch.cancelled = true
        batch.job?.cancel()
        // 让等待中的推送引擎立即得到“失败”
        batch.items.indices.forEach { i -> if (batch.get(i).phase != ItemPhase.READY) batch.set(i, ItemProgress(ItemPhase.SKIPPED)) }
        batches.remove(batch.id)
        scope.launch { batch.dir.deleteRecursively() }
        WscLog.i(TAG, "staging cancelled after ${System.currentTimeMillis() - batch.startedAt}ms")
    }

    /** 推送已开始：此后由推送会话决定何时删除（结束、失败、被替换、换设备）。 */
    fun bind(batch: Batch, sessionId: String) {
        batch.sessionId = sessionId
        batch.boundAt = System.currentTimeMillis()
        batch.seen = false
        // 会话是异步发布的：10 秒内一直没看到本批次被推送（开始失败）也清掉
        scope.launch {
            delay(BIND_GRACE_MS)
            onSession(dmc.session.value)
        }
    }

    /** 分享页“换一台”：推送停止，但批次保留给下一台用。 */
    fun unbind(batch: Batch) {
        batch.sessionId = null
        batch.seen = false
    }

    fun find(id: String?): Batch? = id?.let { batches[it] }

    /** 某项是否已就绪（true）/ 失败（false）；不是本模块的地址直接放行。 */
    private suspend fun await(uri: Uri): Boolean {
        for (b in batches.values) {
            val i = b.pushUris.indexOf(uri)
            if (i < 0) continue
            if (b.cancelled) return false
            val p = b.progress.first { it[i].phase == ItemPhase.READY || it[i].phase == ItemPhase.FAILED || it[i].phase == ItemPhase.SKIPPED || b.cancelled }
            return !b.cancelled && p[i].phase == ItemPhase.READY
        }
        return true
    }

    private fun onSession(s: PushSessionState?) {
        for (b in batches.values) {
            val sid = b.sessionId ?: continue
            val referenced = s != null && s.sessionId == sid && s.isActive && s.items.any { it.uri != null && it.uri in b.pushUris }
            if (referenced) {
                b.seen = true
                rememberTransferFailure(b, s!!)
                continue
            }
            val expired = System.currentTimeMillis() - b.boundAt >= BIND_GRACE_MS
            if (b.seen || expired) {
                WscLog.i(TAG, "staging released (push over / replaced)")
                cancel(b)
            }
        }
    }

    /** 转交授权的视频在推送中读不到了：这个来源下次改为复制（PRD 8.1.2 D“推送过程中授权失效”）。只记提供方，不记文件。 */
    private fun rememberTransferFailure(b: Batch, s: PushSessionState) {
        if (s.engine.failure?.error != PushError.FILE_UNREADABLE) return
        val uri = s.current?.uri ?: return
        val item = b.items.getOrNull(b.pushUris.indexOf(uri))?.takeIf { it.mode == ReadMode.TRANSFER } ?: return
        val key = item.source.authority?.let(::sourceKey) ?: return
        if (key in memory.read()) return
        memory.update { (it + key).distinct().takeLast(MAX_REMEMBERED) }
        WscLog.w(TAG, "transferred grant lost; this provider will be copied next time")
    }

    /** 这个来源以前转交授权后读失败过（下次改为复制）。 */
    fun sourceNeedsCopy(uri: Uri): Boolean = uri.authority?.let { sourceKey(it) in memory.read() } == true

    /** 只存提供方的哈希，不存能看出来源 App 的明文（PM P2-14）。 */
    private fun sourceKey(authority: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(authority.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    // ------------------------------------------------------------------ 复制

    private suspend fun copyAll(batch: Batch) {
        val started = System.currentTimeMillis()
        var copiedTotal = 0L
        for ((i, item) in batch.items.withIndex()) {
            coroutineContext.ensureActive()
            if (item.mode != ReadMode.COPY) continue
            if (i >= batch.limit) {
                batch.set(i, ItemProgress(ItemPhase.SKIPPED))
                continue
            }
            if (copiedTotal + (item.sizeBytes ?: 0) > ShareLimits.MAX_TOTAL_COPY || (item.sizeBytes ?: 0) > ShareLimits.MAX_SINGLE_COPY) {
                batch.set(i, ItemProgress(ItemPhase.FAILED, failure = CopyFailure.TOO_LARGE))
                continue
            }
            val target = cacheFile(batch, item)
            var attempt = 0
            var result: CopyFailure?
            while (true) {
                result = copyOne(batch, i, item, target)
                if (result == null || result == CopyFailure.NO_SPACE || result == CopyFailure.GONE || attempt >= ShareLimits.COPY_RETRIES) break
                attempt++
            }
            if (i >= batch.limit) {
                target.delete()
                batch.set(i, ItemProgress(ItemPhase.SKIPPED))
            } else if (result == null) {
                copiedTotal += target.length()
                batch.set(i, batch.get(i).copy(phase = ItemPhase.READY, copiedBytes = target.length(), totalBytes = target.length()))
            } else {
                target.delete()
                batch.set(i, ItemProgress(ItemPhase.FAILED, failure = result))
                WscLog.w(TAG, "copy item failed: $result")
            }
        }
        WscLog.i(TAG, "staging done in ${System.currentTimeMillis() - started}ms, size=${sizeBucket(copiedTotal)}")
    }

    /** 复制一项；返回 null = 成功。可取消（协程取消后删除半成品）。 */
    private suspend fun copyOne(batch: Batch, i: Int, item: SharedItem, target: File): CopyFailure? = kotlinx.coroutines.coroutineScope {
        batch.set(i, ItemProgress(ItemPhase.COPYING, 0, item.sizeBytes))
        val lastRead = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val input = java.util.concurrent.atomic.AtomicReference<java.io.InputStream?>(null)
        // 看门狗：30 秒没有新数据（云盘流卡住）就关掉输入流，阻塞中的 read 随即抛出 → 读取中断（PM P2-5）
        val watchdog = launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                if (System.currentTimeMillis() - lastRead.get() > ShareLimits.STALL_MS) {
                    stalled.set(true)
                    runCatching { input.get()?.close() }
                    break
                }
            }
        }
        val r = try {
            copyStream(batch, i, item, target, { input.set(it) }, { lastRead.set(System.currentTimeMillis()) })
        } finally {
            watchdog.cancel()
        }
        if (stalled.get()) {
            WscLog.w(TAG, "copy stalled for ${ShareLimits.STALL_MS}ms")
            CopyFailure.READ_FAILED
        } else r
    }

    private suspend fun copyStream(batch: Batch, i: Int, item: SharedItem, target: File, onOpen: (java.io.InputStream) -> Unit, onRead: () -> Unit): CopyFailure? {
        try {
            val input = resolver.openInputStream(item.source) ?: return CopyFailure.READ_FAILED
            onOpen(input)
            input.use { inp ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(BUFFER)
                    var copied = 0L
                    var lastReport = 0L
                    var sinceCheck = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        if (batch.cancelled || i >= batch.limit) return CopyFailure.READ_FAILED
                        val n = inp.read(buf)
                        onRead()
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        sinceCheck += n
                        if (copied > ShareLimits.MAX_SINGLE_COPY) return CopyFailure.TOO_LARGE
                        // 大小未知的流每 32 MB 查一次余量（交互稿 S05“时机”）
                        if (sinceCheck >= SPACE_CHECK_BYTES) {
                            sinceCheck = 0
                            if (!SpacePlanner.streamMayContinue(availableBytes())) return CopyFailure.NO_SPACE
                        }
                        val now = System.nanoTime()
                        if (now - lastReport > REPORT_NS) {
                            lastReport = now
                            batch.set(i, ItemProgress(ItemPhase.COPYING, copied, item.sizeBytes))
                        }
                    }
                }
            }
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            target.delete()
            throw e
        } catch (e: SecurityException) {
            return CopyFailure.READ_FAILED
        } catch (e: FileNotFoundException) {
            return CopyFailure.GONE
        } catch (e: IOException) {
            val enospc = (e.cause as? ErrnoException)?.errno == OsConstants.ENOSPC || availableBytes() < ShareLimits.RESERVE_BYTES / 4
            return if (enospc) CopyFailure.NO_SPACE else CopyFailure.READ_FAILED
        }
    }

    /** 应用缓存所在分区可分配的空间（含系统可清理的缓存）。 */
    fun availableBytes(): Long {
        val dir = cacheRoot(context).apply { mkdirs() }
        return runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.getAllocatableBytes(sm.getUuidForPath(dir))
        }.getOrElse { dir.usableSpace }
    }

    private fun cacheFile(batch: Batch, item: SharedItem): File {
        val ext = MediaMime.extensionFor(item.mime).takeIf { it != "bin" } ?: ShareClassifier.extensionOf(item.displayName) ?: "bin"
        return File(batch.dir, "${item.index}.$ext")
    }

    private fun cacheUri(batch: Batch, item: SharedItem): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", cacheFile(batch, item))

    // ------------------------------------------------------------------ 到达即检查

    sealed interface Probe {
        data class Ok(val sizeBytes: Long?, val seekable: Boolean, val providerMime: String?, val displayName: String?) : Probe
        data class Unreadable(val reason: UnreadableReason) : Probe
    }

    /** 试读一项（能否打开、能否取得大小、能否随机读取）。不记录文件名。 */
    fun probe(uri: Uri): Probe {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return Probe.Unreadable(UnreadableReason.NO_PERMISSION)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return Probe.Unreadable(UnreadableReason.GENERIC)
        // 不读本应用自己的提供方（防止别的 App 借分享入口让我们推出私有缓存 / 导出的日志，PM P2-13）
        if (uri.authority == "${context.packageName}.files") return Probe.Unreadable(UnreadableReason.NO_PERMISSION)
        return try {
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
            var name: String? = null
            var querySize: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) querySize = c.getLong(si).takeIf { it >= 0 }
                    }
                }
            }.onFailure { if (it is SecurityException) throw it }
            val afd = resolver.openAssetFileDescriptor(uri, "r") ?: return Probe.Unreadable(UnreadableReason.GENERIC)
            afd.use {
                val len = it.length.takeIf { l -> l != AssetFileDescriptor.UNKNOWN_LENGTH && l >= 0 } ?: querySize
                val regular = runCatching { OsConstants.S_ISREG(Os.fstat(it.fileDescriptor).st_mode) }.getOrDefault(false)
                Probe.Ok(len, regular && len != null, mime, name)
            }
        } catch (e: SecurityException) {
            Probe.Unreadable(UnreadableReason.NO_PERMISSION)
        } catch (e: Exception) {
            Probe.Unreadable(UnreadableReason.GENERIC)
        }
    }

    companion object {
        private const val TAG = "ShareStaging"
        private const val BUFFER = 256 * 1024
        private const val SPACE_CHECK_BYTES = 32L * 1024 * 1024
        private const val REPORT_NS = 200_000_000L
        private const val BIND_GRACE_MS = 10_000L
        private const val MAX_REMEMBERED = 50
        private const val WATCHDOG_TICK_MS = 5_000L

        fun cacheRoot(context: Context): File = File(context.cacheDir, "share")

        /**
         * App 启动时清理上次进程留下的副本（被杀 / 重启，PRD 8.1.2 D“清理”）。
         * 在 Application.onCreate 里同步列出现有目录（此时还没有任何分享批次），删除放到后台线程。
         */
        fun cleanupStale(context: Context) {
            val stale = cacheRoot(context).listFiles()?.toList().orEmpty()
            if (stale.isEmpty()) return
            Thread({ stale.forEach { runCatching { it.deleteRecursively() } } }, "wsc-share-cleanup").start()
        }

        fun sizeBucket(bytes: Long): String = when {
            bytes < 100L * 1024 * 1024 -> "<100MB"
            bytes < 1024L * 1024 * 1024 -> "<1GB"
            bytes < 4L * 1024 * 1024 * 1024 -> "<4GB"
            else -> ">=4GB"
        }
    }
}
