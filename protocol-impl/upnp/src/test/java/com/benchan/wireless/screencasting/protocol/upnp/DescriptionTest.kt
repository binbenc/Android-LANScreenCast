package com.benchan.wireless.screencasting.protocol.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptionTest {
    private fun desc(urlBase: String? = null, control: String = "/avt/ctl", event: String = "/avt/evt", rc: Boolean = true, nested: Boolean = false, extra: String = "") = buildString {
        append("""<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:wsc="urn:wsc:device-1-0">""")
        urlBase?.let { append("<URLBase>$it</URLBase>") }
        val renderer = """<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>客厅电视</friendlyName>
            <manufacturer>Brand</manufacturer><modelName>X1</modelName><UDN>uuid:abc-123</UDN>$extra<serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><serviceId>x</serviceId><SCPDURL>avt.xml</SCPDURL><controlURL>$control</controlURL><eventSubURL>$event</eventSubURL></service>
            ${if (rc) "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>_urn:rc_control</controlURL><eventSubURL></eventSubURL></service>" else ""}
            <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm/ctl</controlURL></service>
            </serviceList></device>"""
        if (nested) append("<device><deviceType>urn:x:device:Basic:1</deviceType><UDN>uuid:root</UDN><deviceList>$renderer</deviceList></device>")
        else append(renderer)
        append("</root>")
    }

    @Test
    fun parsesServicesAndResolvesUrls() {
        val d = DeviceDescriptionParser.parse(desc(), "http://192.168.1.20:49152/dev/desc.xml", "192.168.1.20")!!
        assertEquals("abc-123", d.udn)
        assertEquals("客厅电视", d.displayName)
        assertEquals("http://192.168.1.20:49152/avt/ctl", d.avTransport.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", d.avTransport.serviceType)
        // 不带斜杠的相对地址按描述目录解析
        assertEquals("http://192.168.1.20:49152/dev/_urn:rc_control", d.renderingControl!!.controlUrl)
        assertNull(d.renderingControl!!.eventSubUrl)
        assertEquals("http://192.168.1.20:49152/dev/avt.xml", d.avTransport.scpdUrl)
        assertFalse(d.isOwnApp)
    }

    @Test
    fun urlBaseNestedAndFallbackName() {
        val d = DeviceDescriptionParser.parse(desc(urlBase = "http://192.168.1.20:8080/", nested = true), "http://192.168.1.20:49152/d.xml", "192.168.1.20")!!
        assertEquals("http://192.168.1.20:8080/avt/ctl", d.avTransport.controlUrl)
        val noName = DeviceDescriptionParser.parse(desc().replace("<friendlyName>客厅电视</friendlyName>", ""), "http://192.168.1.20:1/d.xml", "192.168.1.20")!!
        assertEquals("Brand X1", noName.displayName)
    }

    @Test
    fun rejectsForeignOrPublicAddresses() {
        // LOCATION 不在 SSDP 来源主机上
        assertNull(DeviceDescriptionParser.parse(desc(), "http://192.168.1.99:1/d.xml", "192.168.1.20"))
        // LOCATION 指向公网
        assertNull(DeviceDescriptionParser.parse(desc(), "http://8.8.8.8/d.xml", "8.8.8.8"))
        // 控制地址指向别的主机 / 公网 → 设备不可用
        assertNull(DeviceDescriptionParser.parse(desc(control = "http://203.0.113.5/ctl"), "http://192.168.1.20:1/d.xml", "192.168.1.20"))
        assertNull(DeviceDescriptionParser.parse(desc(control = "http://192.168.1.21/ctl"), "http://192.168.1.20:1/d.xml", "192.168.1.20"))
        // URLBase 指向公网时忽略 URLBase，退回 LOCATION
        val d = DeviceDescriptionParser.parse(desc(urlBase = "http://example.com/"), "http://192.168.1.20:1/d.xml", "192.168.1.20")!!
        assertEquals("http://192.168.1.20:1/avt/ctl", d.avTransport.controlUrl)
        // 事件地址指向公网 → 只丢弃事件地址（仍可轮询）
        val e = DeviceDescriptionParser.parse(desc(event = "http://1.1.1.1/evt"), "http://192.168.1.20:1/d.xml", "192.168.1.20")!!
        assertNull(e.avTransport.eventSubUrl)
        // 主机名（需要 DNS）不接受
        assertFalse(UrlGuard.isLanHttp("http://tv.local/d.xml"))
        assertFalse(UrlGuard.isLanHttp("https://192.168.1.2/d.xml"))
        assertFalse(UrlGuard.isLanHttp("http://user@192.168.1.2/d.xml"))
        assertTrue(UrlGuard.isLanHttp("http://10.0.0.5:80/d.xml"))
    }

    @Test
    fun noAvTransportMeansNotControllable() {
        val xml = desc().replace("AVTransport:2", "Foo:1")
        assertNull(DeviceDescriptionParser.parse(xml, "http://192.168.1.20:1/d.xml", "192.168.1.20"))
        assertNull(DeviceDescriptionParser.parse("<<garbage", "http://192.168.1.20:1/d.xml", "192.168.1.20"))
        assertNull(DeviceDescriptionParser.parse("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY a \"b\">]><root/>", "http://192.168.1.20:1/d.xml", "192.168.1.20"))
    }

    @Test
    fun recognisesOwnAppReceiver() {
        val d = DeviceDescriptionParser.parse(
            desc(extra = "<wsc:service><wsc:port>8443</wsc:port><wsc:id>dev-9</wsc:id><wsc:fp>AA</wsc:fp></wsc:service>"),
            "http://192.168.1.20:1/d.xml", "192.168.1.20",
        )!!
        assertEquals("dev-9", d.wscId)
        assertTrue(d.isOwnApp)
    }
}
