package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import bnbemu.*
import bnbemu.crypto.CRC32
import bnbemu.crypto.CRC8
import org.tinylog.kotlin.Logger

// ReceivedPacketBase可以省略CipherDegree狀態 所以可以直接用這個
// EN 真實伺服器 (66.135.19.7, PopTag 英文客戶端) 幀格式, 由 cap/*.pcap 逆向並以 CRC8 oracle 全量驗證 (188/188):
//   S→C 握手: [0x00][len:2 明文][payload 明文]          seq=40, 無 hc/無 CRC/無加密
//   S→C 加密: [hc][0x00][Encode4(len)][payload][CRC8]   seq 從 43 起 (banner 佔 40), hc = 231^(102+seq)
//   C→S 加密: [hc][uid:2][len^0x69:1][payload][CRC8]    seq 從 40 起, hc = 231^(192+seq)
//   payload = [Encode2(opcode)][欄位...], opcode = BE16 ^ 0xA569
// 加密 (simpleStream3)、CRC8、headerCode 與 3 度實作完全一致, 故 EN 直接採用 degree 3
val m_nCipherDegreeInit = when {
    isCN -> 2
    isEN -> 3
    else -> 1
}

val headerLen = when (m_nCipherDegreeInit) {
    3 -> 4
    else -> 3
}
val payloadLenIdx = when (m_nCipherDegreeInit) {
    3 -> 2
    else -> 1
}
val opcodeLen = when (m_nCipherDegreeInit) {
    3 -> 2
    else -> 1
}
val crcLen = when (m_nCipherDegreeInit) {
    1 -> 4
    2, 3 -> 1
    else -> 0
}

val minimumLen = headerLen + opcodeLen + crcLen

// CPacketSocket::SetHeaderType
const val m_nHeaderType = 0

// CPacketSocket::SetHeaderCode
const val m_nHeaderCodeRcvBase = 192
const val m_nHeaderCodeSndBase = 102
const val m_nHeaderCodeModifier = 231

// CPacketSocket::SetSequence
const val m_nPacketRcvSeqDelta = 3
const val m_nPacketSndSeqDelta = 3

class Decoder : ByteToMessageDecoder() {
    var m_nPacketRcvSeq = 40

    // CReceivedPacketBase::DecodePacket
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.readableBytes() >= minimumLen) {
            buf.markReaderIndex()

            // CPacketSocket::CheckCode
            val headerCode = (m_nHeaderType + m_nHeaderCodeModifier) xor (m_nHeaderCodeRcvBase + m_nPacketRcvSeq)
            if (buf.readByte() != headerCode.toByte()) {
                println("HeaderCode 錯誤，斷開連接: ${ctx.channel().remoteAddress()}")
                buf.clear()
                ctx.close()
                return
            }

            // 真實伺服器 C→S 標頭: [hc][uid:2][len^0x69:1]
            // uid 為每連線固定值, 由客戶端帶入 (抓包 13/198/230, LE16 ^ 0xA569), 這裡只跳過
            val payloadLen = if (isEN) {
                buf.skipBytes(2)
                (buf.readByte().toInt() and 0xFF) xor 0x69
            } else {
                buf.Decode2
            }

            if (buf.readableBytes() < (payloadLen + crcLen)) {
                buf.resetReaderIndex()
                return
            }

            // (Zero-Copy Slice, 因為要傳給 Handler 所以要retain )
            val payload = buf.readRetainedSlice(payloadLen)
            payload.simpleStreamDecrypt3(m_nPacketRcvSeq) // 解密

            // CRC驗證
            val isCrcValid = when (m_nCipherDegreeInit) {
                1 -> buf.readInt() ==
                        CRC32.UpdateCRC(dwCrcKey = m_nPacketRcvSeq, payload)

                2, 3 -> buf.readByte() ==
                        CRC8.UpdateCRC(dwCrcKey = m_nPacketRcvSeq, payload).toByte()

                else -> true
            }

            if (!isCrcValid) {
                println("CRC 錯誤，斷開連接: ${ctx.channel().remoteAddress()}")
                payload.release()
                ctx.close()
                return
            }

            out += payload

            m_nPacketRcvSeq += m_nPacketRcvSeqDelta
        }
    }
}

//class Encoder : MessageToMessageEncoder<PKT>() {
class Encoder : MessageToByteEncoder<PKT>() {
    var m_nCipherDegree = 0 // 給SendPacketBase用的
    var m_nPacketSndSeq = 40
    inline val headerLen
        get() = when (m_nCipherDegree) {
            3 -> 6
            else -> 3
        }

    override fun encode(ctx: ChannelHandlerContext, pkt: PKT, out: ByteBuf) {
        val opcode = OpcodeManager.getSendOp(pkt.javaClass) // m_nPacketType
        SendPacketBase(out, m_nCipherDegree).apply { // 操作buf = 操作out
            // 把writerIndex設在header結束的位置 來寫入payload
            buf.writerIndex(headerLen)

            when {
                m_nCipherDegree == 3 -> Encode2(opcode)
                m_nCipherDegree == 0 && isEN -> {
                    // 真實 EN banner 無 opcode, 包體即 ConnEstablished 內容
                }
                else -> Encode1(opcode)
            }
            pkt.encode(this)

            val payloadLen = buf.writerIndex() - headerLen

            // 根據明文payload產生CRC
            when (m_nCipherDegree) {
                1 ->
                    buf.writeInt(CRC32.UpdateCRC(dwCrcKey = m_nPacketSndSeq, buf, headerLen, payloadLen))

                2, 3 ->
                    buf.writeByte(CRC8.UpdateCRC(dwCrcKey = m_nPacketSndSeq, buf, headerLen, payloadLen))
            }

            val totalLen = buf.writerIndex()

            if (debugMode) {
                pkt.logPacket(opcode)
            }

            // 把writerIndex設在0 來寫入header
            buf.writerIndex(0)

            val headerCode = (m_nHeaderType + m_nHeaderCodeModifier) xor (m_nHeaderCodeSndBase + m_nPacketSndSeq)
            // 真實 EN banner 無 headerCode, 首位元組固定 0x00
            buf.writeByte(if (m_nCipherDegree == 0 && isEN) 0 else headerCode)

            if (m_nCipherDegree in 1..3) {
                // 寫入長度
                if (m_nCipherDegree == 3) {
                    buf.writeByte(0)
                    Encode4(payloadLen)
                } else {
                    Encode2(payloadLen)
                }
                // 加密
                buf.simpleStreamEncrypt3(m_nPacketSndSeq, headerLen, payloadLen)
            } else { // 握手
                Encode2(payloadLen)
                m_nCipherDegree = m_nCipherDegreeInit
            }

            // 將writerIndex還原
            buf.writerIndex(totalLen)
        }

        m_nPacketSndSeq += m_nPacketSndSeqDelta
    }

    /*    override fun encode(ctx: ChannelHandlerContext, pkt: PKT, out: MutableList<Any>) {
            val opcode = OpcodeManager.getSendOp(pkt.javaClass) // m_nPacketType
            val payload = ctx.alloc().ioBuffer()

            SendPacketBase(payload, m_nCipherDegree).apply {
                if (m_nCipherDegree == 3) {
                    Encode2(opcode)
                } else {
                    Encode1(opcode)
                }
                pkt.encode(this)
            }

            val payloadLen = payload.readableBytes()

            if (debugMode) {
                pkt.logPacket(opcode)
            }

            val header = ctx.alloc().directBuffer(headerLen)

            val headerCode = (m_nHeaderType + m_nHeaderCodeModifier) xor (m_nHeaderCodeSndBase + m_nPacketSndSeq)
            header.writeByte(headerCode)

            if (m_nCipherDegree in 1..3) {
                // 寫入長度
                if (m_nCipherDegree == 3) {
                    header.writeByte(0)
                    header.writeInt(payloadLen xor 0x96CA5395.toInt())
                } else {
                    header.writeShort(payloadLen xor 0xA569)
                }
                // 根據明文產生CRC
                when (m_nCipherDegree) {
                    1 -> payload.writeInt(CRC32.UpdateCRC(dwCrcKey = m_nPacketSndSeq, payload, Size = payloadLen))
                    2, 3 -> payload.writeByte(CRC8.UpdateCRC(dwCrcKey = m_nPacketSndSeq, payload, Size = payloadLen))
                }
                // 加密
                payload.simpleStreamEncrypt3(m_nPacketSndSeq, Size = payloadLen)
            } else { // 握手
                header.writeShort(payloadLen)
                m_nCipherDegree = m_nCipherDegreeInit
            }

            out += header
            out += payload

            m_nPacketSndSeq += m_nPacketSndSeqDelta
        }*/
}

fun PKT.logPacket(opcode: Int) {
    Logger.debug(
        "[" + "${
            javaClass.name.substringAfter('$').substringBefore('$')
        }] " + "$opcode | 0x${opcode.toString(16).uppercase()} | 發送"
    )
}