package bnbemu.netty

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.*
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.ChannelMatchers
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import bnbemu.*
import bnbemu.codec.CRC8
import bnbemu.packet.ConnEstablished
import bnbemu.server.Room
import bnbemu.service.PlayerService
import org.tinylog.kotlin.Logger
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NettyServer {
    private val factory = NioIoHandler.newFactory()

    private val bossGroup = MultiThreadIoEventLoopGroup(1, factory)
    // 单机版统一收敛为单线程 workerGroup：协议处理、Passport、UDP 与 BattleSession 定时器均在同一 EventLoop 上串行执行
    val workerGroup = MultiThreadIoEventLoopGroup(1, factory)

    private val TCP_PORTS = listOf(3838)
    private val UDP_PORTS = listOf(3839, 4849)
    private val boundChannels = CopyOnWriteArrayList<Channel>()
    private val shutdownOnce = AtomicBoolean(false)

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
                NioServerSocketChannel()
            })

            option(ChannelOption.SO_BACKLOG, 64)
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
            tcpBoot.bind(port).addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    boundChannels += future.channel()
                    Logger.info("Bind to TCP port: $port")
                } else {
                    Logger.error(future.cause()) { "Failed to bind TCP port: $port" }
                }
            })
        }
    }

    fun startUdpServer(port: Int, handler: ChannelHandler) {
        Bootstrap().apply {
            group(workerGroup)

            channelFactory(ChannelFactory<Channel> {
                NioDatagramChannel()
            })

            option(ChannelOption.SO_BROADCAST, true)
            option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            handler(handler)
        }.bind(port).addListener(ChannelFutureListener { future ->
            if (future.isSuccess) {
                boundChannels += future.channel()
                Logger.info("Bind to UDP port: $port")
            } else {
                Logger.error(future.cause()) { "Failed to bind UDP port: $port" }
            }
        })
    }

    /** 优雅停机：销毁活跃房间/战斗、关闭监听端口、停止 Netty 线程组并刷新关闭存档仓储。 */
    fun shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) return
        runCatching { Room.leave() }
            .onFailure { println("[NettyServer] 清理房间失败: ${it.message}") }
        runCatching { PassportServer.stop() }
            .onFailure { println("[NettyServer] 停止 PassportServer 失败: ${it.message}") }
        boundChannels.forEach { ch ->
            runCatching { ch.close().syncUninterruptibly() }
        }
        boundChannels.clear()
        runCatching {
            bossGroup.shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
        runCatching {
            workerGroup.shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly()
        }
        PlayerService.shutdown()
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
    const val crcLenUDP = 1

    override fun channelRead0(ctx: ChannelHandlerContext, packet: DatagramPacket) {
        val sender = packet.sender()
        val buf = packet.content()

        if (buf.readableBytes() < 1) return
        val Len = buf.readUnsignedByte().toInt()

        if (buf.readableBytes() < Len) return

        if (buf.readableBytes() < 1) return
        val opcode = buf.readUnsignedByte().toInt()

        if (debugMode) {
            val hexOp = "0x${opcode.toString(16).uppercase()}"
            val payloadLen = buf.readableBytes() - crcLenUDP
            val payloadHex = if (payloadLen > 0) {
                val dumpLen = minOf(payloadLen, MAX_DUMP_LEN)
                val hex = ByteBufUtil.hexDump(buf, buf.readerIndex(), dumpLen).uppercase().chunked(2).joinToString(" ")
                if (payloadLen > MAX_DUMP_LEN) "$hex ..." else hex
            } else ""
            Logger.debug("$opcode | $hexOp | UDP Recv from $sender: $payloadHex")
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
            buf.readByte()
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

            buf.writeByte(opcode) // 寫入 Opcode
            buf.block() // 執行業務寫入邏輯

            val packetSize = buf.readableBytes()
            buf.setByte(0, packetSize) // 回填長度

            if (debugMode) {
                val hexOp = "0x${opcode.toString(16).uppercase()}"
                val payloadStart = 2
                val payloadLen = packetSize - payloadStart
                val payloadHex = if (payloadLen > 0) {
                    val dumpLen = minOf(payloadLen, MAX_DUMP_LEN)
                    val hex = ByteBufUtil.hexDump(buf, payloadStart, dumpLen).uppercase().chunked(2).joinToString(" ")
                    if (payloadLen > MAX_DUMP_LEN) "$hex ..." else hex
                } else ""
                Logger.debug("$opcode | $hexOp | UDP Send to $target: $payloadHex")
            }

            buf.writeByte(CRC8.UpdateCRC(dwCrcKey = 0, buf, Size = buf.readableBytes()))

            writeAndFlush(DatagramPacket(buf, target))
        } catch (e: Exception) {
            if (debugMode) Logger.warn("[UDP] Send to $target error: ${e.message}")
            buf.release()
        }
    }
}