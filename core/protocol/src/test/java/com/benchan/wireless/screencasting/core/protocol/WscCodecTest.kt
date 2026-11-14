package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.message.Bye
import com.benchan.wireless.screencasting.core.protocol.message.ByeReason
import com.benchan.wireless.screencasting.core.protocol.message.MessageIdGenerator
import com.benchan.wireless.screencasting.core.protocol.message.MessageType
import com.benchan.wireless.screencasting.core.protocol.message.Offer
import com.benchan.wireless.screencasting.core.protocol.message.WscCodec
import com.benchan.wireless.screencasting.core.protocol.message.WscDecodeException
import com.benchan.wireless.screencasting.core.protocol.message.toMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WscCodecTest {

    @Test
    fun sharedMessageVectors() {
        val cases = Vectors.load("messages/messages.json")["cases"]!!.jsonArray.map { it.jsonObject }
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val text = c["json"]!!.jsonPrimitive.content
            if (c["valid"]!!.jsonPrimitive.boolean) {
                val msg = WscCodec.decode(text)
                assertEquals(name, c["type"]!!.jsonPrimitive.content, msg.type.wire)
                val expected = c["canonical"]?.jsonPrimitive?.content ?: text
                assertEquals(name, Json.parseToJsonElement(expected), Json.parseToJsonElement(WscCodec.encode(msg)))
            } else {
                try {
                    WscCodec.decode(text)
                    fail("$name should be rejected")
                } catch (_: WscDecodeException) {
                }
            }
        }
    }

    @Test
    fun roundTripTypedPayload() {
        val ids = MessageIdGenerator()
        val msg = Offer("v=0", iceRestart = true).toMessage(ids.next(), "sid")
        val decoded = WscCodec.decode(WscCodec.encode(msg))
        assertEquals(msg, decoded)
        assertEquals(MessageType.OFFER, decoded.type)
        assertEquals(2L, ids.next())
        val bye = WscCodec.decode(WscCodec.encode(Bye(ByeReason.SCREEN_LOCKED).toMessage(7, null)))
        assertEquals(ByeReason.SCREEN_LOCKED, (bye.payload as Bye).reason)
    }

    @Test(expected = WscDecodeException::class)
    fun oversizedMessageRejected() {
        WscCodec.decode("{\"t\":\"OFFER\",\"id\":1,\"p\":{\"sdp\":\"" + "x".repeat(WscCodec.MAX_MESSAGE_BYTES) + "\"}}")
    }

    @Test
    fun versionNegotiation() {
        assertEquals(1, VersionNegotiation.negotiate(1, 1))
        assertEquals(1, VersionNegotiation.negotiate(1, 2))
        assertEquals(null, VersionNegotiation.negotiate(3, 1, localMin = 2))
        assertEquals(null, VersionNegotiation.negotiate(1, 3))
    }
}
