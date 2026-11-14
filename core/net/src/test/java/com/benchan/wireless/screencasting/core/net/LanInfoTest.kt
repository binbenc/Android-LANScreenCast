package com.benchan.wireless.screencasting.core.net

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class LanInfoTest {
    private fun lan(ip: String, prefix: Int) = LanInfo("wlan0", InetUtils.parseIpv4(ip)!!, prefix, null, LanInfo.Transport.WIFI, false)

    @Test
    fun subnetMath() {
        val l = lan("192.168.1.23", 24)
        assertEquals("192.168.1.x", l.subnetHint)
        assertTrue(l.sameSubnet(InetAddress.getByName("192.168.1.200")))
        assertFalse(l.sameSubnet(InetAddress.getByName("192.168.2.1")))
        assertEquals("10.0.0.0/16", lan("10.0.3.4", 16).subnetHint)
    }

    @Test
    fun parsing() {
        assertNull(InetUtils.parseIpv4("256.1.1.1"))
        assertNull(InetUtils.parseIpv4("example.com"))
        assertEquals("192.168.1.2" to 8443, InetUtils.parseHostPort("192.168.1.2:8443", 1))
        assertEquals("192.168.1.2" to 1, InetUtils.parseHostPort(" 192.168.1.2 ", 1))
        assertNull(InetUtils.parseHostPort("192.168.1.2:99999", 1))
    }

    @Test
    fun lanAddressClassification() {
        assertTrue(InetUtils.isLanAddress(InetAddress.getByName("192.168.1.1")))
        assertTrue(InetUtils.isLanAddress(InetAddress.getByName("10.1.2.3")))
        assertTrue(InetUtils.isLanAddress(InetAddress.getByName("239.255.255.250")))
        assertTrue(InetUtils.isLanAddress(InetAddress.getByName("169.254.1.1")))
        assertFalse(InetUtils.isLanAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun ktorServerStartsAndServes() = runBlocking {
        val server = LanHttpServer("test") { routing { get("/ping") { call.respondText("pong") } } }
        val port = kotlinx.coroutines.withTimeout(10_000) { server.start() }
        println("ktor port=$port")
        try {
            assertEquals("pong", URL("http://127.0.0.1:$port/ping").readText())
        } finally {
            server.stop()
        }
    }
}
