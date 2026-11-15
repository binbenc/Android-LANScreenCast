package com.benchan.wireless.screencasting.protocol.dlna

import java.io.File

/**
 * 本机 DMR 上次成功监听的 HTTP 端口（只存本机 noBackupFilesDir）。
 *
 * DMR 每次随机端口时，发送端记住的设备描述地址（`DlnaRendererStore.location`）在接收端重启后就失效，
 * SSDP 不通的网络里“最近推送过的电视”直接探测会失败。下次启动优先复用这里的端口；被占用时由
 * `LanHttpServer` 回退到随机端口，成功后再把新端口写回。
 */
internal class DlnaPortStore(private val file: File) {

    /** 上次的端口；没有记录 / 内容无效时为 null（按随机端口启动）。 */
    fun read(): Int? = runCatching { file.readText().trim() }.getOrNull()?.let(::parse)

    /** 记下本次实际监听的端口（与已存值相同时不写盘）。 */
    fun write(port: Int) {
        if (!valid(port) || read() == port) return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(port.toString())
        }
    }

    companion object {
        /** 只复用非特权端口；0 / 越界 / 非数字一律视为没有记录。 */
        fun valid(port: Int): Boolean = port in 1024..65535

        fun parse(text: String): Int? = text.toIntOrNull()?.takeIf(::valid)
    }
}
