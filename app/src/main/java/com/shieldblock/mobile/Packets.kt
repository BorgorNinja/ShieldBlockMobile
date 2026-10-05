package com.shieldblock.mobile

/** Minimal IPv4/UDP/DNS helpers. Pure Kotlin, no Android dependencies. */
object Packets {
    class Udp(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    class Query(val name: String, val type: Int, val questionEnd: Int)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    private fun sum(b: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init
        var i = off
        val end = off + len
        while (i + 1 < end) { s += u16(b, i); i += 2 }
        if (i < end) s += (u8(b, i) shl 8)
        return s
    }

    private fun fold(s0: Long): Int {
        var s = s0
        while ((s shr 16) != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** Parses an IPv4 UDP packet read from the TUN device. Returns null for anything else. */
    fun parseUdp(b: ByteArray, len: Int): Udp? {
        if (len < 28) return null
        if ((u8(b, 0) shr 4) != 4) return null
        val ihl = (u8(b, 0) and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if (u8(b, 9) != 17) return null
        if ((u16(b, 6) and 0x3FFF) != 0) return null // fragments are not supported
        val total = minOf(len, u16(b, 2))
        if (total < ihl + 8) return null
        val plen = minOf(u16(b, ihl + 4) - 8, total - ihl - 8)
        if (plen < 0) return null
        return Udp(
            b.copyOfRange(12, 16),
            b.copyOfRange(16, 20),
            u16(b, ihl),
            u16(b, ihl + 2),
            b.copyOfRange(ihl + 8, ihl + 8 + plen)
        )
    }

    fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000) // DF
        p[8] = 64
        p[9] = 17
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        put16(p, 10, fold(sum(p, 0, 20, 0)))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, udpLen)
        System.arraycopy(payload, 0, p, 28, payload.size)
        var c = fold(sum(p, 20, udpLen, sum(p, 12, 8, (17 + udpLen).toLong())))
        if (c == 0) c = 0xFFFF
        put16(p, 26, c)
        return p
    }

    /** Parses a DNS message holding exactly one plain (uncompressed) question. */
    fun parseQuery(p: ByteArray, len: Int): Query? {
        if (len < 12) return null
        if ((u8(p, 2) and 0x80) != 0) return null // already a response
        if (u16(p, 4) != 1) return null
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= len) return null
            val l = u8(p, i)
            if (l == 0) { i++; break }
            if ((l and 0xC0) != 0 || i + 1 + l > len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append(u8(p, i + k).toChar())
            i += 1 + l
        }
        if (i + 4 > len) return null
        return Query(sb.toString().lowercase(), u16(p, i), i + 4)
    }

    /** A -> 0.0.0.0, AAAA -> ::, anything else -> empty NOERROR answer. */
    fun blockedResponse(p: ByteArray, q: Query): ByteArray {
        val hasAnswer = q.type == 1 || q.type == 28
        val rdLen = if (q.type == 28) 16 else 4
        val out = ByteArray(q.questionEnd + if (hasAnswer) 12 + rdLen else 0)
        System.arraycopy(p, 0, out, 0, q.questionEnd)
        out[2] = ((u8(p, 2) and 0x79) or 0x80).toByte() // keep opcode + RD, set QR
        out[3] = 0x80.toByte()                          // RA, RCODE=0
        put16(out, 4, 1)
        put16(out, 6, if (hasAnswer) 1 else 0)
        put16(out, 8, 0)
        put16(out, 10, 0)
        if (hasAnswer) {
            var o = q.questionEnd
            out[o++] = 0xC0.toByte(); out[o++] = 0x0C // name pointer to question
            put16(out, o, q.type); o += 2
            put16(out, o, 1); o += 2                  // class IN
            put16(out, o, 0); o += 2
            put16(out, o, 60); o += 2                 // TTL 60s
            put16(out, o, rdLen)                      // RDATA stays zero
        }
        return out
    }
}
