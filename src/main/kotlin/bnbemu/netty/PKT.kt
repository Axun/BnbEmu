package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCounted
import bnbemu.ACP
import bnbemu.debugMode
import bnbemu.isEN
import bnbemu.showDecValue
import bnbemu.packet.decodeName
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

fun interface PKTHandler {
    fun ReceivedPacketBase.handle(c: ClientSocket)
}

fun interface PKT {
    //    fun SendPacketBase.encode() // 如果用這個每個Packet函數要inline才看的到函數名字 不然會只有object+lambda名字
    fun encode(out: SendPacketBase)
}

inline fun PKT(crossinline block: SendPacketBase.() -> Unit) = PKT { out ->
    block(out)
}

typealias ReceivedPacketBase = ByteBuf

val ReceivedPacketBase.Decode1
    get() = Decode1At()
val ReceivedPacketBase.Decode2
    get() = Decode2At()
val ReceivedPacketBase.Decode4
    get() = Decode4At()
val ReceivedPacketBase.DecodeStr
    get() = DecodeStrAt()
val ReceivedPacketBase.DecodeCipherStr
    get() = DecodeCipherStrAt()
val ReceivedPacketBase.DecodeTokenStr
    get() = DecodeTokenStrAt()

/**
 * 读取经客户端 0x75daf0 / CSendPacket::EncodeStr 令牌加密的原始字节数组（含 2B 长度与越界防御）。
 */
fun ReceivedPacketBase.readTokenBytes(): ByteArray {
    val len = if (readableBytes() >= 2) Decode2 else 0
    return if (len in 1..readableBytes()) ByteArray(len).also { readBytes(it) } else ByteArray(0)
}

/**
 * 解码经客户端 0x75daf0 令牌变换的字符串（角色名 / 聊天文本 / 会话密钥等）。
 */
private fun ReceivedPacketBase.DecodeTokenStrAt(): String {
    val token = readTokenBytes()
    return if (token.isNotEmpty()) decodeName(token) else ""
}

fun ReceivedPacketBase.DecodeEncryptedStr(key: Int) =
    DecodeEncryptedStrAt(key)

private fun ReceivedPacketBase.Decode1At(): Int {
    if (m_nCipherDegreeInit in 1..3) {
        val value = BufferManipulator.Decrypt1(this)
        if (debugMode && showDecValue)
            println("decrypt value = $value")
        return value
    } else {
        return BufferManipulator.Decode1(this)
    }
}

private fun ReceivedPacketBase.Decode2At(): Int {
    if (m_nCipherDegreeInit in 1..3) {
        val value = BufferManipulator.Decrypt2(this)
        if (debugMode && showDecValue)
            println("decrypt value = $value")
        return value
    } else {
        return BufferManipulator.Decode2(this)
    }
}

private fun ReceivedPacketBase.Decode4At(): Int {
    if (m_nCipherDegreeInit in 1..3) {
        val value = BufferManipulator.Decrypt4(this)
        if (debugMode && showDecValue)
            println("decrypt value = $value")
        return value
    } else {
        return BufferManipulator.Decode4(this)
    }
}

private fun ReceivedPacketBase.DecodeStrAt(): String {
    // 3 度地區字串長度為 Encode4, 但真實 EN 伺服器雖為 3 度幀、字串長度仍是 Encode2
    // (抓包 op 7 角色名前綴 a5 6d = Encode2(4))
    val length = if (m_nCipherDegreeInit == 3 && !isEN) Decode4At() else Decode2At()
    return readString(length, ACP)
}

/**
 * 0x75daf0 字串加解密 (CA.exe CInPacket::DecodeStr 當 cipherDegree in 1..3 時調用):
 *   每 4B 以 BE 讀取, XOR 0x96CA5395, 按 LE 寫入
 *   剩餘 2B 以 BE 讀取, XOR 0xA569, 按 LE 寫入
 *   剩餘 1B XOR 0x5A 寫入
 */
private fun ReceivedPacketBase.DecodeCipherStrAt(): String {
    val declared = if (m_nCipherDegreeInit == 3 && !isEN) Decode4At() else Decode2At()
    // 畸形包 / 上游字段错位时声明长度可能远超剩余字节 (例如把房名内容当成 E2 长度读出 22222),
    // 直接按 declared 去 readInt 会越界抛异常 → ClientSocket.exceptionCaught 直接断开连接。
    // clamp 到实际可读长度: 正常包不受影响, 错位包最多读到一个乱码串而不会掉线。
    val length = declared.coerceIn(0, readableBytes())
    if (length <= 0) return ""
    val raw = ByteArray(length)
    if (m_nCipherDegreeInit in 1..3) {
        var idx = 0
        while (idx + 4 <= length) {
            val beVal = readInt()
            val leVal = beVal xor 0x96CA5395.toInt()
            raw[idx] = (leVal and 0xFF).toByte()
            raw[idx + 1] = ((leVal shr 8) and 0xFF).toByte()
            raw[idx + 2] = ((leVal shr 16) and 0xFF).toByte()
            raw[idx + 3] = ((leVal shr 24) and 0xFF).toByte()
            idx += 4
        }
        if (idx + 2 <= length) {
            val beVal = readUnsignedShort()
            val leVal = beVal xor 0xA569
            raw[idx] = (leVal and 0xFF).toByte()
            raw[idx + 1] = ((leVal shr 8) and 0xFF).toByte()
            idx += 2
        }
        if (idx < length) {
            raw[idx] = (readUnsignedByte().toInt() xor 0x5A).toByte()
            idx += 1
        }
    } else {
        readBytes(raw)
    }
    return String(raw, ACP)
}

private fun ReceivedPacketBase.DecodeEncryptedStrAt(key: Int): String {
    val length = if (m_nCipherDegreeInit == 3 && !isEN) Decode4At() else Decode2At()

    simpleStreamDecrypt3(key, readerIndex(), length)

    val decryptString = readString(length, ACP)

    if (debugMode && showDecValue)
        println("decryptString: $decryptString")
    return decryptString
}

class SendPacketBase(
    val buf: ByteBuf,
    private val m_nCipherDegree: Int
) : ReferenceCounted by buf {

    fun Encode1(n: Number = 0) =
        Encode1At(n)

    fun Encode1Bool(b: Boolean = false) =
        Encode1At(if (b) 1 else 0)

    fun Encode2(n: Int = 0) =
        Encode2At(n)

    fun Encode4(n: Int = 0) =
        Encode4At(n)

    fun EncodeStr(s: String = "") {
        val SA = s.toByteArray(ACP)
        Encode2(SA.size)
        buf.writeBytes(SA)
    }

    /**
     * 0x75daf0 字串加解密 (CA.exe CSendPacket::EncodeStr 當 cipherDegree in 1..3 時調用):
     *   以 LE 讀取字串 4B, XOR 0x96CA5395, 按 BE 寫入封包
     *   以 LE 讀取字串 2B, XOR 0xA569, 按 BE 寫入封包
     *   剩餘 1B XOR 0x5A 寫入封包
     */
    fun EncodeCipherStr(s: String = "") {
        val raw = s.toByteArray(ACP)
        Encode2(raw.size)
        if (this.m_nCipherDegree in 1..3) {
            var idx = 0
            while (idx + 4 <= raw.size) {
                val leVal = (raw[idx].toInt() and 0xFF) or
                        ((raw[idx + 1].toInt() and 0xFF) shl 8) or
                        ((raw[idx + 2].toInt() and 0xFF) shl 16) or
                        ((raw[idx + 3].toInt() and 0xFF) shl 24)
                val valEnc = leVal xor 0x96CA5395.toInt()
                buf.writeInt(valEnc)
                idx += 4
            }
            if (idx + 2 <= raw.size) {
                val leVal = (raw[idx].toInt() and 0xFF) or
                        ((raw[idx + 1].toInt() and 0xFF) shl 8)
                val valEnc = leVal xor 0xA569
                buf.writeShort(valEnc)
                idx += 2
            }
            if (idx < raw.size) {
                buf.writeByte((raw[idx].toInt() and 0xFF) xor 0x5A)
                idx += 1
            }
        } else {
            buf.writeBytes(raw)
        }
    }

    fun EncodeBuffer(ba: ByteArray) {
        buf.writeBytes(ba)
    }

    fun EncodeBuffer(s: String) {
        buf.writeBytes(s.toBA())
    }

    fun EncodeBuffer(count: Int) {
        buf.writeZero(count)
    }

    private fun Encode1At(n: Number) {
        if (this.m_nCipherDegree in 1..3) {
            BufferManipulator.Encrypt1(buf, n)
        } else {
            BufferManipulator.Encode1(buf, n)
        }
    }

    private fun Encode2At(n: Int) {
        if (this.m_nCipherDegree in 1..3) {
            BufferManipulator.Encrypt2(buf, n)
        } else {
            BufferManipulator.Encode2(buf, n)
        }
    }

    private fun Encode4At(n: Int) {
        if (this.m_nCipherDegree in 1..3) {
            BufferManipulator.Encrypt4(buf, n)
        } else {
            BufferManipulator.Encode4(buf, n)
        }
    }
}

private object BufferManipulator {
    fun Decode1(buf: ByteBuf): Int = buf.readUnsignedByte().toInt()
    fun Decode2(buf: ByteBuf): Int = buf.readUnsignedShort()
    fun Decode4(buf: ByteBuf): Int = buf.readInt()

    fun Decrypt1(buf: ByteBuf): Int = buf.readUnsignedByte().toInt() xor 0x5A
    fun Decrypt2(buf: ByteBuf): Int = buf.readUnsignedShort() xor 0xA569
    fun Decrypt4(buf: ByteBuf): Int = buf.readInt() xor 0x96CA5395.toInt()

    fun Encode1(buf: ByteBuf, n: Number) = buf.writeByte(n.toInt())
    fun Encode2(buf: ByteBuf, n: Int) = buf.writeShort(n)
    fun Encode4(buf: ByteBuf, n: Int) = buf.writeInt(n)

    fun Encrypt1(buf: ByteBuf, n: Number) = buf.writeByte(n.toInt() xor 0x5A)
    fun Encrypt2(buf: ByteBuf, n: Int) = buf.writeShort(n xor 0xA569)
    fun Encrypt4(buf: ByteBuf, n: Int) = buf.writeInt(n xor 0x96CA5395.toInt())
}

fun String.toBA() =
    replace("|", "").replace("\\s+".toRegex(), "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

// ============================================================================
// 0x75DAF0 SSD 资料块变换 (ByteArray 版本, 与 SendPacketBase.EncodeCipherStr 的字串版本同源)
// ============================================================================
// 客户端 sub_75DAF0 (reference/CA.exe.c:744227) 对整块从 offset 0 一次性反混淆,
// 近 30 处 sub_78F1E0 调用点共用, 不分 opcode。这里是它的逆变换:
//   4B 一组: LE 读明文 ^0x96CA5395 按 BE 写回; 余 2B: LE ^0xA569 按 BE 写; 余 1B: ^0x5A
// 所有 builder (op11/op12/op13/op60/op65/op210 的块, op72/op81 的名令牌) 只产出「纯明文」,
// 尾部不足 4B 的余数一律由本函式统一编码, 呼叫方不需也不得预写线上字节;
// 线上的 a5 69 5a / a5 69 / 5a 都是明文 0 经尾部编码的结果。
// 例外: op1/op2/op51 是硬编码回放块, 不走这里; tools/extract_first_push.py 仍是旧约定。
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
