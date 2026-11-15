package com.benchan.wireless.screencasting.core.protocol

import com.benchan.wireless.screencasting.core.protocol.control.InputCodec
import com.benchan.wireless.screencasting.core.protocol.control.InputMessage
import com.benchan.wireless.screencasting.core.protocol.control.PointerAction
import com.benchan.wireless.screencasting.core.protocol.control.RemoteKey
import com.benchan.wireless.screencasting.core.protocol.datachannel.DcMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataChannelVectorsTest {

    @Test
    fun sharedDataChannelVectors() {
        val cases = Vectors.load("datachannel/messages.json")["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 20)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val text = c["json"]!!.jsonPrimitive.content
            val input = c["channel"]!!.jsonPrimitive.content == "wsc-input"
            if (c["valid"]!!.jsonPrimitive.boolean) {
                val expected = c["canonical"]?.jsonPrimitive?.content ?: text
                val (type, encoded) = if (input) {
                    val m = InputCodec.decodeOrNull(text)
                    assertNotNull(name, m)
                    typeOf(InputCodec.encode(m!!)) to InputCodec.encode(m)
                } else {
                    val m = DcMessage.decodeOrNull(text)
                    assertNotNull(name, m)
                    typeOf(DcMessage.encode(m!!)) to DcMessage.encode(m)
                }
                assertEquals(name, c["type"]!!.jsonPrimitive.content, type)
                assertEquals(name, normalize(Json.parseToJsonElement(expected)), normalize(Json.parseToJsonElement(encoded)))
            } else {
                if (input) assertNull(name, InputCodec.decodeOrNull(text)) else assertNull(name, DcMessage.decodeOrNull(text))
            }
        }
    }

    @Test
    fun pointerRoundTripAndRounding() {
        val p = InputMessage.Pointer(
            s = 9, g = 2, a = PointerAction.MOVE, x = InputCodec.round4(0.123456), y = InputCodec.round4(1.2), dt = 33,
            h = listOf(listOf(0.1, 0.2, 16.0)),
        )
        assertEquals(0.1235, p.x, 0.0)
        assertEquals(1.0, p.y, 0.0)
        assertEquals(p, InputCodec.decodeOrNull(InputCodec.encode(p)))
        assertEquals(RemoteKey.HOME, (InputCodec.decodeOrNull(InputCodec.encode(InputMessage.Key(1, RemoteKey.HOME))) as InputMessage.Key).k)
    }

    @Test
    fun oversizedInputIgnored() {
        assertNull(InputCodec.decodeOrNull("{\"t\":\"K\",\"s\":1,\"k\":\"" + "x".repeat(InputCodec.MAX_BYTES) + "\"}"))
    }

    private fun typeOf(encoded: String): String = Json.parseToJsonElement(encoded).jsonObject["t"]!!.jsonPrimitive.content

    /** 数字按数值比较（8 == 8.0），字符串 / 布尔原样比较。 */
    private fun normalize(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { normalize(it.value) })
        is JsonArray -> JsonArray(e.map(::normalize))
        is JsonNull -> e
        is JsonPrimitive -> if (!e.isString && e.doubleOrNull != null && e.content != "true" && e.content != "false") JsonPrimitive(e.doubleOrNull) else e
    }
}
