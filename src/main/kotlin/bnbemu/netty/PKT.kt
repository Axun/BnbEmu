package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.util.ReferenceCounted
import bnbemu.ACP
import bnbemu.debugMode
import bnbemu.isEN
import bnbemu.showDecValue

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
    val length = if (m_nCipherDegreeInit == 3 && !isEN) Decode4At() else Decode2At()
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