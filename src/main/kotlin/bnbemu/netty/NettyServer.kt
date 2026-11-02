package bnbemu.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.*
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.ChannelMatchers
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import bnbemu.*
import bnbemu.packet.ConnEstablished
import java.net.InetSocketAddress

object NettyServer {
    private val useEpoll = Epoll.isAvailable() // 檢查是否為 Linux 環境且 Epoll 可用
    private val factory = when {
        useEpoll -> EpollIoHandler.newFactory()
        else -> NioIoHandler.newFactory()
    }

    private val bossGroup = MultiThreadIoEventLoopGroup(1, factory)
    val workerGroup = MultiThreadIoEventLoopGroup(factory)

    private val TCP_PORTS = listOf(3838, 4848, 4849)
    private val UDP_PORTS = listOf(3839, 4849)

    fun start() {
        startTcpServers()
        UDP_PORTS.forEach { port ->
            startUdpServer(port, HandlerRouterUDP)
        }
        // EN 破解客戶端的登入前置: passport(nmco_session) 換票據, 之後才連 3838
        PassportServer.start()
    }

    private fun startTcpServers() {
        val tcpBoot = ServerBootstrap().apply {
            group(bossGroup, workerGroup)

            channelFactory(ChannelFactory<ServerChannel> {
                if (useEpoll) EpollServerSocketChannel() else NioServerSocketChannel()
            })

            option(ChannelOption.SO_BACKLOG, 1024)
            childOption(ChannelOption.TCP_NODELAY, true)
            childOption(ChannelOption.SO_KEEPALIVE, true)
            childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)

            childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(
                        Decoder(),
                        Encoder(),
                        ClientSocket(),
                    )

                    ch.writeAndFlush(ConnEstablished())
                }
            })
        }

        TCP_PORTS.forEach { port ->
            tcpBoot.bind(port).addListener { future ->
                if (future.isSuccess) {
                    println("Bind to TCP port: $port")
                } else {
                    println("Failed to bind TCP port: $port")
                }
            }
        }
    }

    fun startUdpServer(port: Int, handler: ChannelHandler) {
        Bootstrap().apply {
            group(workerGroup)

            channelFactory(ChannelFactory<Channel> {
                if (useEpoll) EpollDatagramChannel() else NioDatagramChannel()
            })

            option(ChannelOption.SO_BROADCAST, true)
            option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            handler(handler)
        }.bind(port).addListener { future ->
            if (future.isSuccess) {
                println("Bind to UDP port: $port")
            } else {
                println("Failed to bind UDP port: $port")
            }
        }
    }
}

fun ChannelGroup.BC(packet: PKT, exclude: Channel? = null) {
    if (exclude == null) {
        writeAndFlush(packet)
    } else {
        writeAndFlush(packet, ChannelMatchers.isNot(exclude))
    }
}

@ChannelHandler.Sharable
object HandlerRouterUDP : SimpleChannelInboundHandler<DatagramPacket>() {
    val crcLenUDP = when {
        isJP -> 4
        else -> 1
    }

    override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
        val sender = packet.sender()
        val buf = packet.content()

        if (buf.readableBytes() < 1) return
        val Len = buf.readUnsignedByte().toInt()

        if (buf.readableBytes() < Len) return

        if (isJP) {
            if (buf.readableBytes() < 4) return
            buf.readInt()
        }

        if (buf.readableBytes() < 1) return
        val opcode = buf.readUnsignedByte().toInt()

        if (debugMode) {
            val hexOp = "0x${opcode.toString(16).uppercase()}"
            val payloadLen = buf.readableBytes() - crcLenUDP
            val payloadHex = if (payloadLen > 0) {
                ByteBufUtil.hexDump(buf, buf.readerIndex(), payloadLen).uppercase().chunked(2).joinToString(" ")
            } else ""
            println("$opcode | $hexOp | UDP Recv from $sender: $payloadHex")
        }

        // CUDPPacketDispatcher::OnUDPPacket
        when (opcode) {
            // CWaitForLoginDlg::OnTimer / UDP handshake probe
            0 -> {
                val userId = if (buf.readableBytes() >= 4 + crcLenUDP) buf.readInt() else 0
                // CWaitForLoginDlg::OnAckUDPCommunication
                ctx.sendUDP(sender, 2) {}
            }

            1 -> {
                val userId = if (buf.readableBytes() >= 4 + crcLenUDP) buf.readInt() else 0
            }

            11 -> {
                val userId = if (buf.readableBytes() >= 4 + crcLenUDP) buf.readInt() else 0
            }

            else -> {}
        }

        // CRC
        if (buf.readableBytes() >= crcLenUDP) {
            when {
                isJP -> buf.readInt()
                else -> buf.readByte()
            }
        }
    }

    fun ChannelHandlerContext.sendUDP(
        target: InetSocketAddress,
        opcode: Int,
        block: ByteBuf.() -> Unit
    ) {
        val buf = alloc().ioBuffer()

        try {
            // 佔位
            buf.writeByte(0) // size

            if (isJP)
                buf.writeInt(0)

            buf.writeByte(opcode) // 寫入 Opcode
            buf.block() // 執行業務寫入邏輯

            val packetSize = buf.readableBytes()
            buf.setByte(0, packetSize) // 回填長度

            if (debugMode) {
                val hexOp = "0x${opcode.toString(16).uppercase()}"
                val payloadStart = if (isJP) 6 else 2
                val payloadLen = packetSize - payloadStart
                val payloadHex = if (payloadLen > 0) {
                    ByteBufUtil.hexDump(buf, payloadStart, payloadLen).uppercase().chunked(2).joinToString(" ")
                } else ""
                println("$opcode | $hexOp | UDP Send to $target: $payloadHex")
            }

            when {
                isJP -> buf.writeInt(CRC32.UpdateCRC(dwCrcKey = 0, buf, Size = buf.readableBytes()))
                else -> buf.writeByte(CRC8.UpdateCRC(dwCrcKey = 0, buf, Size = buf.readableBytes()))
            }

            writeAndFlush(DatagramPacket(buf, target))
        } catch (e: Exception) {
            if (debugMode) println("[UDP] Send to $target error: ${e.message}")
            buf.release()
        }
    }
}