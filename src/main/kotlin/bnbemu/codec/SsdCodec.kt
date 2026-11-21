package bnbemu.codec;

import bnbemu.ACP
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

// ============================================================================
// 0x75DAF0 SSD 资料块变换 (ByteArray 版本, 与 SendPacketBase.EncodeCipherStr 的字串版本同源)
// ============================================================================
// 客户端 sub_75DAF0 (reference/CA.exe.c:744227) 对整块从 offset 0 一次性反混淆,
// 近 30 处 sub_78F1E0 调用点共用, 不分 opcode。这里是它的逆变换:
//   4B 一组: LE 读明文 ^0x96CA5395 按 BE 写回; 余 2B: LE ^0xA569 按 BE 写; 余 1B: ^0x5A
// 所有 builder (op1/op11/op12/op13/op51/op60/op65/op210 的块, op72/op81 的名令牌) 只产出「纯明文」,
// 尾部不足 4B 的余数一律由本函式统一编码, 呼叫方不需也不得预写线上字节;
// 线上的 a5 69 5a / a5 69 / 5a 都是明文 0 经尾部编码的结果。
// 例外: op2 (csysstr 1861B 字串表) 仍是硬编码回放块, 不走这里; tools/extract_first_push.py 仍是旧约定。
internal fun ssdEncode(plain: ByteArray): ByteArray {
    val out = ByteArray(plain.size)
    var i = 0
    while (i + 4 <= plain.size) {
        val le = (plain[i].toInt() and 0xFF) or
                ((plain[i + 1].toInt() and 0xFF) shl 8) or
                ((plain[i + 2].toInt() and 0xFF) shl 16) or
                ((plain[i + 3].toInt() and 0xFF) shl 24)
        val enc = le xor 0x96CA5395.toInt()
        out[i] = (enc ushr 24).toByte()
        out[i + 1] = (enc ushr 16).toByte()
        out[i + 2] = (enc ushr 8).toByte()
        out[i + 3] = enc.toByte()
        i += 4
    }
    // 尾部余数: 余 2B/3B 先 Encode2 头 2B, 再 Encode1 最后 1B; 余 1B 只 Encode1
    if (plain.size - i >= 2) {
        val w = (plain[i].toInt() and 0xFF) or ((plain[i + 1].toInt() and 0xFF) shl 8)
        val enc = w xor 0xA569
        out[i] = (enc ushr 8).toByte()
        out[i + 1] = enc.toByte()
        i += 2
    }
    if (i < plain.size) {
        out[i] = ((plain[i].toInt() and 0xFF) xor 0x5A).toByte()
    }
    return out
}

/**
 * 组装 SSD 块（对应客户端 sub_78BC60 / sub_78C120 / sub_78BB60）：
 * - compress=true 且 `compressed.size + 4 < plain.size` 时写入：
 *   `[0x18][u32le(5 + compressed.size)][0x01][u32le(plain.size)][compressed]`
 * - 否则写入未压缩块：
 *   `[0x18][u32le(1 + plain.size)][0x00][plain]`
 */
internal fun buildSsdBlock(plain: ByteArray, compress: Boolean = true): ByteArray {
    val compressed = if (compress && plain.isNotEmpty()) zlibCompress(plain) else null
    val useCompressed = compressed != null && compressed.size + 4 < plain.size
    val payloadLen = if (useCompressed) compressed!!.size + 5 else plain.size + 1
    val out = ByteArray(5 + payloadLen)
    out[0] = 0x18.toByte()
    out[1] = (payloadLen and 0xFF).toByte()
    out[2] = (payloadLen ushr 8 and 0xFF).toByte()
    out[3] = (payloadLen ushr 16 and 0xFF).toByte()
    out[4] = (payloadLen ushr 24 and 0xFF).toByte()
    if (useCompressed) {
        out[5] = 1
        out[6] = (plain.size and 0xFF).toByte()
        out[7] = (plain.size ushr 8 and 0xFF).toByte()
        out[8] = (plain.size ushr 16 and 0xFF).toByte()
        out[9] = (plain.size ushr 24 and 0xFF).toByte()
        System.arraycopy(compressed!!, 0, out, 10, compressed.size)
    } else {
        out[5] = 0
        System.arraycopy(plain, 0, out, 6, plain.size)
    }
    return out
}

/**
 * zlib level-9 压缩（含标准 2B zlib 头 + deflate 流 + 4B Adler-32 尾），
 * 与客户端 sub_74C020(..., 9) 一致。
 */
internal fun zlibCompress(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    return try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        out.toByteArray()
    } finally {
        deflater.end()
    }
}

// 角色名令牌 = 0x75DAF0 变换后的名字字节, 与 SSD 资料块同源, 统一走 [ssdEncode]。
// 3B 尾 = 2B 尾 + 1B 尾 (2026-08-30 由 kcworm1 7B 名对出, 与 4/5/6B 名样本回编码逐字节一致)
// (op 81 JoinSessionResult 的名冊令牌同款, internal 供共用; 逆变换见下方 [decodeName])
internal fun encodeName(name: String): ByteArray = ssdEncode(name.toByteArray(ACP))

/**
 * [encodeName] 的逆變換：4B 一塊 BE 讀 ^0x96CA5395 按 LE 寫回, 尾 2B BE 讀 ^0xA569 按 LE 寫回, 尾 1B ^0x5A。
 *
 * 用途：C→S 的字串欄位（聊天文字 op 18、會話密鑰 op 297 RegisterSessionKey 等）客戶端也是用同一套令牌變換送出的
 * （實測 "asf" → D6 08 3C），伺服器要拿到明文才能處理/轉發。
 */
internal fun decodeName(token: ByteArray): String {
    val dst = ByteArray(token.size)
    var i = 0
    while (i + 4 <= token.size) {
        val be = ((token[i].toInt() and 0xFF) shl 24) or
                ((token[i + 1].toInt() and 0xFF) shl 16) or
                ((token[i + 2].toInt() and 0xFF) shl 8) or
                (token[i + 3].toInt() and 0xFF)
        val dec = be xor 0x96CA5395.toInt()
        dst[i] = dec.toByte()
        dst[i + 1] = (dec ushr 8).toByte()
        dst[i + 2] = (dec ushr 16).toByte()
        dst[i + 3] = (dec ushr 24).toByte()
        i += 4
    }
    while (i < token.size) {
        if (token.size - i >= 2) {
            val be = ((token[i].toInt() and 0xFF) shl 8) or (token[i + 1].toInt() and 0xFF)
            val dec = be xor 0xA569
            dst[i] = dec.toByte()
            dst[i + 1] = (dec ushr 8).toByte()
            i += 2
        } else {
            dst[i] = (token[i].toInt() xor 0x5A).toByte()
            i += 1
        }
    }
    return String(dst, ACP)
}