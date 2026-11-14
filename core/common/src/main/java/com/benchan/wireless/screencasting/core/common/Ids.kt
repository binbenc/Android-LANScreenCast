package com.benchan.wireless.screencasting.core.common

import java.security.SecureRandom
import java.util.UUID

object Ids {
    private val random = SecureRandom()

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    /** 生成 [digits] 位的随机数字串（允许前导零），用于 PIN 与投屏码。 */
    fun randomDigits(digits: Int): String = buildString(digits) {
        repeat(digits) { append(random.nextInt(10)) }
    }
}
