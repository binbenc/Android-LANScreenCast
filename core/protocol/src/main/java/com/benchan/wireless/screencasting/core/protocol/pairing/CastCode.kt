package com.benchan.wireless.screencasting.core.protocol.pairing

/**
 * 8 位投屏码（决策 #19）= 接收端 IPv4 最后一段（3 位，不足补零）+ 一次性 PIN（5 位）。
 * 发送端用自己的 /24 网段前缀补全地址；输对即视为完成配对（SPAKE2 用这 5 位作口令）。
 */
data class CastCode(val lastOctet: Int, val pin: String) {
    init {
        require(lastOctet in 1..254) { "last octet out of range" }
        require(pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }) { "pin must be $PIN_LENGTH digits" }
    }

    /** 8 位数字串，例如 "12843709"。 */
    val digits: String get() = lastOctet.toString().padStart(3, '0') + pin

    /** 交互稿的 3+5 分组显示：“128 43709”。 */
    val display: String get() = lastOctet.toString().padStart(3, '0') + " " + pin

    companion object {
        const val LENGTH = 8
        const val PIN_LENGTH = 5

        fun of(receiverIpv4: ByteArray, pin: String): CastCode {
            require(receiverIpv4.size == 4)
            return CastCode(receiverIpv4[3].toInt() and 0xFF, pin)
        }

        /** 解析用户输入（允许空格），格式不对返回 null。 */
        fun parse(input: String): CastCode? {
            val d = input.filter { !it.isWhitespace() }
            if (d.length != LENGTH || !d.all { it in '0'..'9' }) return null
            val octet = d.substring(0, 3).toInt()
            if (octet !in 1..254) return null
            return CastCode(octet, d.substring(3))
        }

        /**
         * 前 3 位输入后即可实时预览目标地址（交互稿 05B）。
         * @return 目标 IPv4 字符串；本机不在 /24（或更小）网段、或末段不合法时返回 null（UI 提示改用扫码）。
         */
        fun resolveHost(localIpv4: ByteArray, prefixLength: Int, octetDigits: String): String? {
            if (localIpv4.size != 4 || prefixLength < 24 || prefixLength > 30) return null
            if (octetDigits.length != 3 || !octetDigits.all { it in '0'..'9' }) return null
            val octet = octetDigits.toInt()
            if (octet !in 1..254) return null
            val a = localIpv4.map { it.toInt() and 0xFF }
            return "${a[0]}.${a[1]}.${a[2]}.$octet"
        }

        fun supportsNetwork(prefixLength: Int): Boolean = prefixLength in 24..30
    }
}
