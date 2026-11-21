package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import bnbemu.*
import bnbemu.crypto.CRC8
import org.tinylog.kotlin.Logger

// EN 真實伺服器 (66.135.19.7, PopTag 英文客戶端) 幀格式, 由 cap/*.pcap 逆向並以 CRC8 oracle 全量驗證 (188/188):
//   S→C 握手: [0x00][len:2 明文][payload 明文]          seq=40, 無 hc/無 CRC/無加密/無 opcode
//   S→C 加密: [hc][0x00][Encode4(len)][payload][CRC8]   seq 從 43 起 (banner 佔 40), hc = 231^(102+seq)
//   C→S 加密: [hc][uid:2][len^0x69:1][payload][CRC8]    seq 從 40 起, hc = 231^(192+seq)
//   payload = [Encode2(opcode)][欄位...], opcode = BE16 ^ 0xA569
private const val RECV_HEADER_LEN = 4
private const val OPCODE_LEN = 2
private const val CRC_LEN = 1
private const val MINIMUM_RECV_LEN = RECV_HEADER_LEN + OPCODE_LEN + CRC_LEN

// CPacketSocket::SetHeaderType / SetHeaderCode / SetSequence
private const val HEADER_TYPE = 0
private const val HEADER_CODE_RCV_BASE = 192
private const val HEADER_CODE_SND_BASE = 102
private const val HEADER_CODE_MODIFIER = 231
private const val SEQ_DELTA = 3

class Decoder : ByteToMessageDecoder() {
    var rcvSeq = 40

    // CReceivedPacketBase::DecodePacket
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.readableBytes() >= MINIMUM_RECV_LEN) {
            buf.markReaderIndex()

            // CPacketSocket::CheckCode
            val headerCode = (HEADER_TYPE + HEADER_CODE_MODIFIER) xor (HEADER_CODE_RCV_BASE + rcvSeq)
            if (buf.readByte() != headerCode.toByte()) {
                println("HeaderCode 錯誤，斷開連接: ${ctx.channel().remoteAddress()}")
                buf.clear()
                ctx.close()
                return
            }

            // 真實伺服器 C→S 標頭: [hc][uid:2][len^0x69:1]
            // uid 為每連線固定值, 由客戶端帶入 (抓包 13/198/230, LE16 ^ 0xA569), 這裡只跳過
            buf.skipBytes(2)
            val payloadLen = (buf.readByte().toInt() and 0xFF) xor 0x69

            if (buf.readableBytes() < payloadLen + CRC_LEN) {
                buf.resetReaderIndex()
                return
            }

            // (Zero-Copy Slice, 因為要傳給 Handler 所以要retain )
            val payload = buf.readRetainedSlice(payloadLen)
            payload.simpleStreamDecrypt3(rcvSeq) // 解密

            val isCrcValid = buf.readByte() ==
                CRC8.UpdateCRC(dwCrcKey = rcvSeq, payload).toByte()

            if (!isCrcValid) {
                println("CRC 錯誤，斷開連接: ${ctx.channel().remoteAddress()}")
                payload.release()
                ctx.close()
                return
            }

            out += payload

            rcvSeq += SEQ_DELTA
        }
    }
}

class Encoder : MessageToByteEncoder<PKT>() {
    private var handshakeSent = false
    var sndSeq = 40

    override fun encode(ctx: ChannelHandlerContext, pkt: PKT, out: ByteBuf) {
        val opcode = OpcodeManager.getSendOp(pkt.javaClass)
        val encrypted = handshakeSent
        val headerLen = if (encrypted) 6 else 3

        SendPacketBase(out, encrypted).apply { // 操作buf = 操作out
            // 把writerIndex設在header結束的位置 來寫入payload
            buf.writerIndex(headerLen)

            if (encrypted) {
                Encode2(opcode)
            }
            pkt.encode(this)

            val payloadLen = buf.writerIndex() - headerLen

            if (encrypted) {
                // 根據明文payload產生CRC8
                buf.writeByte(CRC8.UpdateCRC(dwCrcKey = sndSeq, buf, headerLen, payloadLen))
            }

            val totalLen = buf.writerIndex()

            if (debugMode) {
                pkt.logPacket(opcode)
            }

            // 把writerIndex設在0 來寫入header
            buf.writerIndex(0)

            if (encrypted) {
                val headerCode = (HEADER_TYPE + HEADER_CODE_MODIFIER) xor (HEADER_CODE_SND_BASE + sndSeq)
                buf.writeByte(headerCode)
                buf.writeByte(0)
                Encode4(payloadLen)
                buf.simpleStreamEncrypt3(sndSeq, headerLen, payloadLen)
            } else { // 握手 banner: 固定 0x00 開頭 + 2B 明文長度, 無 headerCode/CRC/加密
                buf.writeByte(0)
                Encode2(payloadLen)
                handshakeSent = true
            }

            // 將writerIndex還原
            buf.writerIndex(totalLen)
        }

        sndSeq += SEQ_DELTA
    }
}

fun PKT.logPacket(opcode: Int) {
    Logger.debug(
        "[" + "${
            javaClass.name.substringAfter('$').substringBefore('$')
        }] " + "$opcode | 0x${opcode.toString(16).uppercase()} | 發送"
    )
}