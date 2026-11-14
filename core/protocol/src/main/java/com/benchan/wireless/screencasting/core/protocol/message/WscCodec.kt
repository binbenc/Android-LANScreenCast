package com.benchan.wireless.screencasting.core.protocol.message

import com.benchan.wireless.screencasting.core.protocol.WscProtocol
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

/** 消息解析失败（畸形 JSON、未知类型、payload 不匹配、超长）。 */
class WscDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * WSC 消息编解码。只依赖 kotlinx.serialization，与 TS 端共享 protocol/vectors/messages 测试向量。
 */
object WscCodec {
    /** 单条信令消息最大长度（SDP 一般 < 10KB），防止恶意超大消息。 */
    const val MAX_MESSAGE_BYTES = 64 * 1024

    val json: Json = Json {
        ignoreUnknownKeys = true   // 向后兼容：新版本加的字段旧版本忽略
        explicitNulls = false
        encodeDefaults = false
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: MessageType): KSerializer<WscPayload> = when (type) {
        MessageType.HELLO -> Hello.serializer()
        MessageType.PAIR_REQ -> PairRequest.serializer()
        MessageType.PAIR_PIN -> PairPin.serializer()
        MessageType.PAIR_CONFIRM -> PairConfirm.serializer()
        MessageType.PAIR_OK -> PairOk.serializer()
        MessageType.PAIR_QR -> PairQr.serializer()
        MessageType.OFFER -> Offer.serializer()
        MessageType.ANSWER -> Answer.serializer()
        MessageType.ICE -> IceCandidate.serializer()
        MessageType.BYE -> Bye.serializer()
        MessageType.ERROR -> ErrorPayload.serializer()
    } as KSerializer<WscPayload>

    fun typeOf(payload: WscPayload): MessageType = when (payload) {
        is Hello -> MessageType.HELLO
        is PairRequest -> MessageType.PAIR_REQ
        is PairPin -> MessageType.PAIR_PIN
        is PairConfirm -> MessageType.PAIR_CONFIRM
        is PairOk -> MessageType.PAIR_OK
        is PairQr -> MessageType.PAIR_QR
        is Offer -> MessageType.OFFER
        is Answer -> MessageType.ANSWER
        is IceCandidate -> MessageType.ICE
        is Bye -> MessageType.BYE
        is ErrorPayload -> MessageType.ERROR
    }

    fun encode(message: WscMessage): String {
        val payload = json.encodeToJsonElement(serializerFor(message.type), message.payload)
        val obj = buildJsonObject {
            put("t", message.type.wire)
            put("id", message.id)
            message.sessionId?.let { put("sid", it) }
            put("v", message.version)
            put("p", payload)
        }
        return obj.toString()
    }

    fun decode(text: String): WscMessage {
        if (text.length > MAX_MESSAGE_BYTES) throw WscDecodeException("message too large: ${text.length}")
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw WscDecodeException("malformed json", e)
        } catch (e: IllegalArgumentException) {
            throw WscDecodeException("malformed json", e)
        }
        try {
            val wire = root["t"]?.jsonPrimitive?.contentOrNull ?: throw WscDecodeException("missing t")
            val type = MessageType.fromWire(wire) ?: throw WscDecodeException("unknown type $wire")
            val id = root["id"]?.jsonPrimitive?.long ?: throw WscDecodeException("missing id")
            if (id < 0 || id > 0xFFFF_FFFFL) throw WscDecodeException("id out of range")
            val sid = root["sid"]?.jsonPrimitive?.contentOrNull
            val version = root["v"]?.jsonPrimitive?.int ?: WscProtocol.VERSION
            val p = root["p"] as? JsonObject ?: JsonObject(emptyMap())
            val payload = json.decodeFromJsonElement(serializerFor(type), p)
            return WscMessage(type, id, sid, version, payload)
        } catch (e: WscDecodeException) {
            throw e
        } catch (e: SerializationException) {
            throw WscDecodeException("bad payload", e)
        } catch (e: IllegalArgumentException) {
            throw WscDecodeException("bad field", e)
        } catch (e: IllegalStateException) {
            throw WscDecodeException("bad field", e)
        }
    }
}

/** 按连接自增的消息 id 生成器（uint32 回绕）。 */
class MessageIdGenerator {
    private val next = AtomicLong(1)
    fun next(): Long = next.getAndUpdate { if (it >= 0xFFFF_FFFFL) 1 else it + 1 }
}

/** 便捷构造：自动推断类型。 */
fun WscPayload.toMessage(id: Long, sessionId: String?, version: Int = WscProtocol.VERSION): WscMessage =
    WscMessage(WscCodec.typeOf(this), id, sessionId, version, this)
