package pingu.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFactory
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.ServerChannel
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import pingu.debugMode
import pingu.isEN

// Nexon passport 登入服 (NMCO nmco_session, TCP 47611) 樁伺服器。
// 真實流程: 客戶端先連本連接埠以帳號密碼換票據, 之後才帶 36B 票據連 3838 (op 2)。
// 協議 (tshark 實測, 見 tools/extract_passport.py): 每命令獨立一條 TCP 連線, 一問一答即斷:
//   幀 = [len:u16 BE][cmd:u16 BE][payload: len bytes], total = len + 4
//   C→S 0x000B 登入(加密憑據 blob, 長度隨帳號 UTF-16 長度變化) → S→C 0x000C
//        成功: 4B 會話值 + UTF-16LE "nmco_session" + UTF-16LE "NGP"+帳號 (失敗為 40B 短幀)
//   C→S 0x002D 會話查詢(加密 blob) → S→C 0x002E
//        4B 會話值 + 帳號數值 + UTF-16LE 角色名(帶 u16 長度前綴) + ... + u32(5000)
// 請求 blob 是 NMCO 私有加密(非遊戲協議的 Encode/ssc 家族, 暴力嘗試未破), 樁不解密:
// 逐字節回放 login.pcap 的真實成功回應 (cap/passport_login_*.bin)。
// ⚠️ 因此回應裡的會話值/身份串固定為抓包當時的內容, 所有帳號在遊戲內都會得到角色名 ffdf。
object PassportServer {
    private const val PORT = 47611

    // cmd 0x000B 成功回應 (86B, login.pcap s0: 帳號 kcworms1)
    internal val REPLY_0C = """
        0052000c1800004e00000042000000005dd70944000000000c006e006d006300
        6f005f00730065007300730069006f006e000b004e00470050006b0063007700
        6f0072006d0073003100000000000000000000000000
    """.toBA()

    // cmd 0x002D 成功回應 (135B, login.pcap s1: 角色名 ffdf)
    internal val REPLY_2E = """
        0083002e1800007f00000073000000009e4f0285000000000000000000000000
        0000000000000001000000000000000000000000000000002f1c000004006600
        6600640066000000000000000000010000000000000000000000000000000001
        0100008813000000000000000000000000000000000000000000000000000000
        00000000000000
    """.toBA()

    fun start() {
        if (!isEN) return

        ServerBootstrap().apply {
            group(NettyServer.workerGroup, NettyServer.workerGroup)
            channelFactory(ChannelFactory<ServerChannel> { NioServerSocketChannel() })
            option(ChannelOption.SO_BACKLOG, 64)
            childOption(ChannelOption.TCP_NODELAY, true)
            childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(PassportDecoder(), PassportHandler)
                }
            })
        }.bind(PORT).addListener { future ->
            if (future.isSuccess) {
                println("Bind to TCP port: $PORT (passport)")
            } else {
                println("Failed to bind TCP port: $PORT (passport)")
            }
        }
    }
}

// 幀: [len:u16 BE][cmd:u16 BE][payload: len bytes]
private class PassportFrame(val cmd: Int, val payload: ByteBuf)

private class PassportDecoder : ByteToMessageDecoder() {
    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        while (buf.readableBytes() >= 2) {
            val len = buf.getUnsignedShort(buf.readerIndex())
            if (buf.readableBytes() < 4 + len) break
            buf.skipBytes(2)
            val cmd = buf.readUnsignedShort().toInt()
            out.add(PassportFrame(cmd, buf.readRetainedSlice(len)))
        }
    }
}

@ChannelHandler.Sharable
private object PassportHandler : SimpleChannelInboundHandler<PassportFrame>() {
    override fun channelRead0(ctx: ChannelHandlerContext, frame: PassportFrame) {
        val hexOp = "0x${frame.cmd.toString(16).padStart(4, '0').uppercase()}"
        if (debugMode) {
            println("[Passport] $hexOp | 接收 | ${ctx.channel().remoteAddress()}")
            if (frame.payload.readableBytes() in 1..99) {
                println(ByteBufUtil.hexDump(frame.payload).uppercase().chunked(2).joinToString(" "))
            }
        }

        val reply = when (frame.cmd) {
            0x000B -> PassportServer.REPLY_0C
            0x002D -> PassportServer.REPLY_2E
            else -> {
                if (debugMode) println("[Passport] $hexOp 未知命令, 忽略")
                null
            }
        } ?: return

        if (debugMode) println("[Passport] $hexOp | 回應 ${reply.size}B")
        ctx.writeAndFlush(Unpooled.wrappedBuffer(reply))
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (debugMode) println("[Passport] ${cause.message}")
        ctx.close()
    }
}
