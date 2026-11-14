package com.benchan.wireless.screencasting.core.protocol.message

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 前向兼容的枚举序列化（PROTOCOL.md 2.2）：新版本加的枚举值，旧版本解码为一个通用值，而不是把整条消息当畸形消息断开。
 * 线上字符串 = 枚举常量名（与 @SerialName 一致）。值类型不是字符串时仍按畸形消息处理。
 */
open class LenientEnumSerializer<E : Enum<E>>(
    serialName: String,
    private val values: Array<E>,
    private val fallback: E,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): E {
        val wire = decoder.decodeString()
        return values.firstOrNull { it.name == wire } ?: fallback
    }
}

/** 未知 BYE 原因 → USER_STOP（对方主动结束，界面显示“已断开”）。 */
object ByeReasonSerializer : LenientEnumSerializer<ByeReason>("ByeReason", ByeReason.entries.toTypedArray(), ByeReason.USER_STOP)

/** 未知错误码 → INTERNAL（致命，界面显示通用的“连接出错了”）。不用 BAD_MESSAGE：那表示“你发的消息不合法”，归因不对。 */
object ErrorCodeSerializer : LenientEnumSerializer<ErrorCode>("ErrorCode", ErrorCode.entries.toTypedArray(), ErrorCode.INTERNAL)
