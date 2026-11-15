package com.benchan.wireless.screencasting.protocol.dlna.dmc

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 读取选择器给的 content:// 地址（PRD 8.1.1 E：只用临时读取授权，不申请存储权限、不申请持久授权）。
 * 大小取 AssetFileDescriptor 长度或 OpenableColumns.SIZE；只有普通文件才可 seek（云盘类提供方可能给管道）。
 */
class ContentUriSource private constructor(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val sizeBytes: Long?,
    override val seekable: Boolean,
) : ShareSource {

    override fun open(offset: Long): InputStream {
        val afd = resolver.openAssetFileDescriptor(uri, "r") ?: throw FileNotFoundException("cannot open")
        val input = afd.createInputStream()
        if (offset > 0) {
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) break
                left -= n
            }
        }
        return input
    }

    companion object {
        /** 探测大小与是否可 seek；读不到返回 null（授权失效 / 文件被删）。 */
        fun probe(resolver: ContentResolver, uri: Uri): ContentUriSource? = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val len = afd.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it >= 0 } ?: querySize(resolver, uri)
                val regular = runCatching { OsConstants.S_ISREG(Os.fstat(afd.fileDescriptor).st_mode) }.getOrDefault(false)
                ContentUriSource(resolver, uri, len, seekable = regular && len != null)
            }
        }.getOrNull()

        private fun querySize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it >= 0 } else null
            }
        }.getOrNull()

        /** 选择器给的显示名（只在手机界面用，不写日志）。 */
        fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        }.getOrNull()
    }
}
