package com.benchan.wireless.screencasting.core.data

import com.benchan.wireless.screencasting.core.common.WscLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 小型 JSON 文件存储（原子写入：先写临时文件再 rename）。
 * 文件放在 noBackupFilesDir，备份规则也已排除（PRD 8.5）。
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun read(): T = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default()
    } catch (e: Exception) {
        WscLog.w(TAG, "corrupted ${file.name}, resetting", e)
        default()
    }

    @Synchronized
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun update(block: (T) -> T): T {
        val next = block(read())
        write(next)
        return next
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object {
        const val TAG = "JsonFileStore"
    }
}
