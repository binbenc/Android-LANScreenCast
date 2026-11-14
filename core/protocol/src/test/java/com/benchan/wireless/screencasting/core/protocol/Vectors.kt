package com.benchan.wireless.screencasting.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** 读取仓库根目录 protocol/vectors 下的共享测试向量（路径由 Gradle 以系统属性传入）。 */
object Vectors {
    private val dir: File by lazy {
        val prop = System.getProperty("wsc.vectors.dir")
        val candidates = listOfNotNull(prop?.let(::File), File("../../protocol/vectors"), File("protocol/vectors"))
        candidates.firstOrNull { it.isDirectory } ?: error("protocol/vectors not found, tried $candidates")
    }

    fun load(relative: String): JsonObject = Json.parseToJsonElement(File(dir, relative).readText()).jsonObject
}
