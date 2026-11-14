package com.benchan.wireless.screencasting.core.net.ssdp

import com.benchan.wireless.screencasting.core.common.WscLog
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** 收到的 SSDP 报文及来源地址。 */
data class SsdpPacket(val message: SsdpMessage, val from: InetAddress, val fromPort: Int)

/**
 * SSDP UDP 工具。调用方需在使用期间持有 MulticastLock（技术方案 3.6）。
 * [bindPort] = 1900 用于 DMR 监听 M-SEARCH / NOTIFY；= 0 用于发送端主动搜索（单播收响应）。
 */
class SsdpSocket(private val localAddress: Inet4Address?, bindPort: Int) : AutoCloseable {
    private val socket = MulticastSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindPort))
        timeToLive = 4
        soTimeout = 500
        val nif = localAddress?.let { NetworkInterface.getByInetAddress(it) }
        if (nif != null) networkInterface = nif
        if (bindPort == SsdpMessage.PORT) {
            val group = InetSocketAddress(InetAddress.getByName(SsdpMessage.ADDRESS), SsdpMessage.PORT)
            runCatching { joinGroup(group, nif) }.onFailure { WscLog.w(TAG, "joinGroup failed", it) }
        }
    }

    val localPort: Int get() = socket.localPort

    fun sendMulticast(message: SsdpMessage) {
        val data = message.encode().toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(data, data.size, InetAddress.getByName(SsdpMessage.ADDRESS), SsdpMessage.PORT))
    }

    fun sendTo(message: SsdpMessage, address: InetAddress, port: Int) {
        val data = message.encode().toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    /** 阻塞接收一条报文，超时（500ms）返回 null。 */
    fun receive(): SsdpPacket? {
        val buf = ByteArray(8192)
        val packet = DatagramPacket(buf, buf.size)
        return try {
            socket.receive(packet)
            val msg = SsdpMessage.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: return null
            SsdpPacket(msg, packet.address, packet.port)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    val isClosed: Boolean get() = socket.isClosed

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "SsdpSocket"
    }
}
