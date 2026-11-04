package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import bnbemu.debugMode
import bnbemu.server.User
import java.io.IOException

const val MAX_DUMP_LEN = 99

fun ByteBuf.hexDumpTruncated(maxLen: Int = MAX_DUMP_LEN): String {
    val remaining = readableBytes()
    if (remaining <= 0) return ""
    val dumpLen = minOf(remaining, maxLen)
    val hex = ByteBufUtil.hexDump(this, readerIndex(), dumpLen)
        .uppercase()
        .chunked(2)
        .joinToString(" ")
    return if (remaining > maxLen) "$hex ..." else hex
}

val handlerArray = arrayOfNulls<PKTHandler>(1000)
class ClientSocket : SimpleChannelInboundHandler<ByteBuf>() {
    lateinit var ctx: ChannelHandlerContext
    lateinit var ch: Channel

//    val users = arrayOfNulls<User>(2)
    val users = mutableListOf<User>()

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
        this.ch = ctx.channel()
    }

    override fun channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
        val opcode = when (m_nCipherDegreeInit) {
            3 -> buf.Decode2
            else -> buf.Decode1
        }

        val handler = handlerArray[opcode]
        val opName = OpcodeManager.recvOps[opcode]
        if (debugMode || opName == null || handler == null) {
            logPacket(opcode, opName, handler, buf)
        }

        if (handler == null) {
            return
        }

        try {
            handler.run {
                buf.handle(this@ClientSocket)
            }
        } catch (e: Throwable) {
            // 必須保留原始例外 (cause): 只轉發 e.message 會丟掉型別與堆疊 ——
            // 例如 NoSuchMethodError (JDK 9+ 訊息形如 '傳回型別 包.類別.函式()') 只看訊息
            // 完全定位不到出錯的呼叫點; 帶上 cause 後 exceptionCaught 的 printStackTrace
            // 會印出完整 Caused by 鏈
            throw IllegalStateException(
                "業務邏輯錯誤 [${OpcodeManager.recvOps[opcode]}] | ${e::class.simpleName}: ${e.message}",
                e
            )
        }
    }

    private fun logPacket(opcode: Int, opName: String?, handler: PKTHandler?, pkt: ByteBuf) {
        val hexOp = "0x${opcode.toString(16).uppercase()}"
        val status = if (handler != null) "接收" else "找不到 Handler"

        println("[${opName ?: "UNKNOWN"}] $opcode | $hexOp | $status | ${ch.remoteAddress()}")

        val hexDump = pkt.hexDumpTruncated()
        if (hexDump.isNotEmpty()) {
            println(hexDump)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (debugMode) {
            println("${cause.message}")
            if (cause !is IOException) {
                cause.printStackTrace()
            }
        }
        ctx.close()
    }

    fun send(vararg packets: PKT) {
        packets.forEach(ctx::write)
        ctx.flush()
    }

    infix fun send(packet: PKT) {
        ctx.writeAndFlush(packet)
    }

    fun close() {
        ctx.close()
    }
}