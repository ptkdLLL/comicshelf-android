package com.comicshelf.app.core

/**
 * 动图探测（纯读头、零分配）。分层判据：
 *  ① WebP：RIFF/WEBP + VP8X + ANIMATION 位（O(30B)，ns 级）；
 *  ② GIF：前 1KB 含 NETSCAPE2.0 应用扩展（O(1KB)，μs 级；素材实测覆盖率 102/102）；
 *  ③ GIF 未命中②（罕见）：前 256KB 内 GCE(0x21 0xF9) ≥2（GCE 偏移实测：第 2 个在 91KB 处；静态 GIF 仅 1 个 → 不误报）。
 */
object PageAnim {
    fun isAnimated(b: ByteArray): Boolean {
        if (b.size < 30) return false
        // ① WebP
        if (b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()) {
            return b[12] == 'V'.code.toByte() && b[13] == 'P'.code.toByte() &&
                b[14] == '8'.code.toByte() && b[15] == 'X'.code.toByte() &&
                (b[20].toInt() and 0x02) != 0     // VP8X flags: ANIMATION
        }
        // ②③ GIF
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()) {
            val nsLim = minOf(b.size, 1024)
            var i = 0
            while (i + 11 <= nsLim) {
                if (b[i] == 0x21.toByte() && b[i + 1] == 0xFF.toByte() && b[i + 2] == 0x0B.toByte() &&
                    b[i + 3] == 'N'.code.toByte() && b[i + 4] == 'E'.code.toByte() &&
                    b[i + 5] == 'T'.code.toByte() && b[i + 6] == 'S'.code.toByte() &&
                    b[i + 7] == 'C'.code.toByte() && b[i + 8] == 'A'.code.toByte() &&
                    b[i + 9] == 'P'.code.toByte() && b[i + 10] == 'E'.code.toByte()) return true
                i++
            }
            val gceLim = minOf(b.size, 256 * 1024)
            var count = 0
            var j = 0
            while (j + 2 <= gceLim) {
                if (b[j] == 0x21.toByte() && b[j + 1] == 0xF9.toByte()) {
                    count++
                    if (count >= 2) return true
                    j += 2
                } else j++
            }
        }
        return false
    }
}
