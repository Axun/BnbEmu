package bnbemu.netty

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
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
import bnbemu.debugMode
import bnbemu.isEN
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Adler32

/**
 * Nexon Passport 登入服 (NMCO nmco_session, TCP 47611) 伺服器實現。
 *
 * 【協議全解析】
 * 1. 連線拓撲：
 *    每命令獨立一條 TCP 連線，客戶端發送 1 個請求，伺服器返回 1 個回應後客戶端主動 FIN 斷開。
 *
 * 2. 幀封裝 (Outer Frame)：
 *    [frame_len: u16 BE][cmd: u16 BE][container: frame_len bytes], total = frame_len + 4
 *
 * 3. 容器封裝 (Stream Container)：
 *    - 魔數：0x18 (1 byte)
 *    - 容器長度：3 bytes (BE)，等於 12B 頭部 + payload 長度
 *    - 12B 頭部：
 *        [flags: u8]             bit 0: 壓縮, bit 1: 加密 (0x02 = 加密, 0x00 = 明文)
 *        [uncomp_len: 3B BE]     payload 未壓縮長度
 *        [key: 4B BE]            PCBC 加密金鑰 (明文下發時為 0)
 *        [checksum: 4B BE]       Adler-32 校驗值 (由 payload 計算，RFC 1950 標準)
 *    - 負載：payload (uncomp_len bytes)
 *
 * 4. NMCO PCBC 加密演算法 (C→S 加密，逆向自 nmconew.dll 0x100EC660 / 0x100EC6E0)：
 *    - 32-bit 明文回饋異或鏈 (PCBC / Plaintext Cipher Block Chaining)
 *    - 靜態 S-Box 表 (0x1017A138)：16 個 32-bit 常數
 *    - 解密公式：
 *        mask[i] = (key ^ S_TABLE[i & 0x0F] ^ prev) & 0xFFFFFFFF
 *        p[i] = c[i] ^ mask[i]
 *        prev = p[i]  (以解密後的明文作為下一輪回饋，初始 prev = 0)
 *    - 加密公式：
 *        mask[i] = (key ^ S_TABLE[i & 0x0F] ^ prev) & 0xFFFFFFFF
 *        c[i] = p[i] ^ mask[i]
 *        prev = p[i]  (以原始明文作為下一輪回饋，初始 prev = 0)
 *
 * 5. 命令業務格式：
 *    - C→S 0x000B (Login)：
 *        [username: String] + [password: String] + [domain/empty: String] + u32(0x0200f01a) + u32(0x51)
 *        字串編碼：[len: u16 LE (字元數)] + [UTF-16LE 位元組流]
 *    - S→C 0x000C (Login Reply)：
 *        [returnCode: u32 LE (0=成功, 20015=密碼錯誤)] +
 *        [sessionType: String ("nmco_session")] +
 *        [passportId: String ("NGP" + username)] +
 *        [f4: u32(0)] + [f5: u32(0)] + [f6: u32(0)]
 *    - C→S 0x002D (Session Query)：
 *        [target: String ("NGP" + username)]
 *    - S→C 0x002E (Session Query Reply)：
 *        [returnCode: u32 LE (0)] +
 *        [prefix: 32 bytes (前15個0 + 0x01 + 16個0)] +
 *        [userId: u32 LE (帳號數值，如 7215 / 7307)] +
 *        [charName: String (遊戲角色名，如 "ffdf")] +
 *        [suffix: 65 bytes (固定尾部欄位，含 u32 5000)]
 *
 * 6. 附註：
 *    - 3838 埠的 op 2 票據 (36B) 與 passport 回應 4B 會話值的映射關係未考證（模擬器不驗證票據，無影響）。
 */
object PassportServer {
    private const val PORT = 47611

    /** 16 個 32-bit 靜態金鑰常數 (nmconew.dll @ 0x1017A138) */
    internal val S_TABLE = intArrayOf(
        0x40FC1578, 0x113B6C1F, 0x8389CA19.toInt(), 0xE2196CD8.toInt(),
        0x74901489, 0x4AAB1566, 0x7B8C12A0, 0x0018FFCD,
        0xCCAB704B.toInt(), 0x7B5A8C0F, 0xAA13B891.toInt(), 0xDE419807.toInt(),
        0x12FFBCAE, 0x5F5FBA34, 0x10F5AC99, 0xB1C1DD01.toInt()
    )

    /** S→C 0x002E 固定 65 位元組尾部 (含 u32 5000) */
    internal val SUFFIX_2E = (
        "000000000000000001000000000000000000000000000000000101000088130000" +
        "0000000000000000000000000000000000000000000000000000000000000000"
    ).toBA()

    data class PassportAccount(val username: String, val charName: String, val userId: Int)

    /** 預設/已註冊帳號列表 (保留抓包帳號以 100% 通過抓包斷言) */
    val ACCOUNTS = ConcurrentHashMap<String, PassportAccount>().apply {
        put("kcworms1", PassportAccount("kcworms1", "ffdf", 7215))
        put("kcworm1", PassportAccount("kcworm1", "kcworm1", 7307))
    }

    @Volatile
    internal var lastAccount: PassportAccount = PassportAccount("kcworms1", "ffdf", 7215)

    // cmd 0x000B 成功回應 (86B, login.pcap s0: 帳號 kcworms1) - 保留常量供回歸與參照
    internal val REPLY_0C = buildReply0C(0, "nmco_session", "NGPkcworms1")

    // cmd 0x002D 成功回應 (135B, login.pcap s1: 角色名 ffdf, userId 7215) - 保留常量供回歸與參照
    internal val REPLY_2E = buildReply2E(0, 7215, "ffdf")

    /**
     * NMCO PCBC 解密演算法 (32-bit 明文回饋)
     */
    fun decryptPcbc(cipher: ByteArray, key: Int): ByteArray {
        val numDwords = cipher.size / 4
        val plain = cipher.copyOf()
        var prev = 0
        for (i in 0 until numDwords) {
            val offset = i * 4
            val c = (plain[offset].toInt() and 0xFF) or
                    ((plain[offset + 1].toInt() and 0xFF) shl 8) or
                    ((plain[offset + 2].toInt() and 0xFF) shl 16) or
                    ((plain[offset + 3].toInt() and 0xFF) shl 24)
            val mask = key xor S_TABLE[i and 0x0F] xor prev
            val p = c xor mask
            plain[offset] = (p and 0xFF).toByte()
            plain[offset + 1] = ((p ushr 8) and 0xFF).toByte()
            plain[offset + 2] = ((p ushr 16) and 0xFF).toByte()
            plain[offset + 3] = ((p ushr 24) and 0xFF).toByte()
            prev = p
        }
        return plain
    }

    /**
     * NMCO PCBC 加密演算法 (32-bit 明文回饋)
     */
    fun encryptPcbc(plain: ByteArray, key: Int): ByteArray {
        val numDwords = plain.size / 4
        val cipher = plain.copyOf()
        var prev = 0
        for (i in 0 until numDwords) {
            val offset = i * 4
            val p = (cipher[offset].toInt() and 0xFF) or
                    ((cipher[offset + 1].toInt() and 0xFF) shl 8) or
                    ((cipher[offset + 2].toInt() and 0xFF) shl 16) or
                    ((cipher[offset + 3].toInt() and 0xFF) shl 24)
            val mask = key xor S_TABLE[i and 0x0F] xor prev
            val c = p xor mask
            cipher[offset] = (c and 0xFF).toByte()
            cipher[offset + 1] = ((c ushr 8) and 0xFF).toByte()
            cipher[offset + 2] = ((c ushr 16) and 0xFF).toByte()
            cipher[offset + 3] = ((c ushr 24) and 0xFF).toByte()
            prev = p
        }
        return cipher
    }

    /**
     * 構造 0x18 串流容器 (含 Adler-32 校驗值)
     */
    fun buildContainer(payload: ByteArray, flags: Int = 0, key: Int = 0): ByteArray {
        val adler = Adler32().apply { update(payload) }
        val checksum = adler.value.toInt()
        val uncompLen = payload.size
        val bodyLen = 12 + uncompLen

        val buf = ByteBuffer.allocate(4 + bodyLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x18.toByte())
        buf.put((bodyLen ushr 16).toByte())
        buf.put((bodyLen ushr 8).toByte())
        buf.put(bodyLen.toByte())

        // 12-byte header: flags(1B), uncompLen(3B), key(4B), checksum(4B)
        buf.put(flags.toByte())
        buf.put((uncompLen ushr 16).toByte())
        buf.put((uncompLen ushr 8).toByte())
        buf.put(uncompLen.toByte())
        buf.putInt(key)
        buf.putInt(checksum)

        buf.put(payload)
        return buf.array()
    }

    /**
     * 構造外層 Passport 網路幀: [frame_len: u16 BE][cmd: u16 BE][container]
     */
    fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
        val container = buildContainer(payload)
        val buf = ByteBuffer.allocate(4 + container.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(container.size.toShort())
        buf.putShort(cmd.toShort())
        buf.put(container)
        return buf.array()
    }

    /**
     * 編碼 UTF-16LE 字串: [len: u16 LE (字元長度)][UTF-16LE 位元組流]
     */
    fun encodeStr(s: String): ByteArray {
        val chars = s.toByteArray(Charsets.UTF_16LE)
        val buf = ByteBuffer.allocate(2 + chars.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(s.length.toShort())
        buf.put(chars)
        return buf.array()
    }

    /**
     * 解碼 UTF-16LE 字串
     */
    fun decodeStr(buf: ByteBuffer): String {
        if (buf.remaining() < 2) return ""
        val charLen = buf.short.toInt() and 0xFFFF
        val byteLen = charLen * 2
        if (buf.remaining() < byteLen) return ""
        val bytes = ByteArray(byteLen).apply { buf.get(this) }
        return String(bytes, Charsets.UTF_16LE)
    }

    /**
     * 動態構造 S→C 0x000C (Login 回應)
     * @param returnCode 0 為成功，20015 (0x4E2F) 為密碼錯誤
     * @param sessionType "nmco_session"
     * @param passportId "NGP" + username
     */
    fun buildReply0C(
        returnCode: Int = 0,
        sessionType: String = "nmco_session",
        passportId: String = "NGPkcworms1"
    ): ByteArray {
        val s1 = encodeStr(sessionType)
        val s2 = encodeStr(passportId)
        val buf = ByteBuffer.allocate(4 + s1.size + s2.size + 12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(returnCode)
        buf.put(s1)
        buf.put(s2)
        buf.putInt(0)
        buf.putInt(0)
        buf.putInt(0)
        return buildFrame(0x000C, buf.array())
    }

    /**
     * 動態構造 S→C 0x002E (Session Query 角色查詢回應)
     * @param returnCode 0 為成功
     * @param userId 帳號數值 (如 7215 / 7307)
     * @param charName 角色名稱 (如 "ffdf")
     */
    fun buildReply2E(
        returnCode: Int = 0,
        userId: Int = 7215,
        charName: String = "ffdf"
    ): ByteArray {
        val prefix = ByteArray(32).apply { this[15] = 1 }
        val nameBytes = encodeStr(charName)
        val buf = ByteBuffer.allocate(4 + prefix.size + 4 + nameBytes.size + SUFFIX_2E.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(returnCode)
        buf.put(prefix)
        buf.putInt(userId)
        buf.put(nameBytes)
        buf.put(SUFFIX_2E)
        return buildFrame(0x002E, buf.array())
    }

    data class LoginRequest(val username: String, val password: String)

    /**
     * 解析 C→S 0x000B (Login 請求)
     */
    fun parseLoginReq(buf: ByteBuf): LoginRequest? {
        if (buf.readableBytes() < 20) return null
        val magic = buf.readByte().toInt() and 0xFF
        if (magic != 0x18) return null
        val contLen = buf.readUnsignedMedium()
        val flags = buf.readByte().toInt() and 0xFF
        val uncompLen = buf.readUnsignedMedium()
        val key = buf.readInt()
        val checksum = buf.readInt()

        val cipherLen = buf.readableBytes()
        val cipherBytes = ByteArray(cipherLen).apply { buf.readBytes(this) }
        val plainBytes = if (flags and 0x02 != 0) {
            decryptPcbc(cipherBytes, key)
        } else {
            cipherBytes
        }

        val plainBuf = ByteBuffer.wrap(plainBytes).order(ByteOrder.LITTLE_ENDIAN)
        val user = decodeStr(plainBuf)
        val pwd = decodeStr(plainBuf)
        return LoginRequest(user, pwd)
    }

    /**
     * 解析 C→S 0x002D (Session Query 請求)
     */
    fun parseSessionQueryReq(buf: ByteBuf): String? {
        if (buf.readableBytes() < 20) return null
        val magic = buf.readByte().toInt() and 0xFF
        if (magic != 0x18) return null
        val contLen = buf.readUnsignedMedium()
        val flags = buf.readByte().toInt() and 0xFF
        val uncompLen = buf.readUnsignedMedium()
        val key = buf.readInt()
        val checksum = buf.readInt()

        val cipherLen = buf.readableBytes()
        val cipherBytes = ByteArray(cipherLen).apply { buf.readBytes(this) }
        val plainBytes = if (flags and 0x02 != 0) {
            decryptPcbc(cipherBytes, key)
        } else {
            cipherBytes
        }

        val plainBuf = ByteBuffer.wrap(plainBytes).order(ByteOrder.LITTLE_ENDIAN)
        return decodeStr(plainBuf)
    }

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
            val cmd = buf.readUnsignedShort()
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
        }

        val reply = when (frame.cmd) {
            0x000B -> {
                val req = PassportServer.parseLoginReq(frame.payload)
                val username = req?.username?.takeIf { it.isNotBlank() } ?: "kcworms1"
                val password = req?.password ?: ""
                println("[Passport] 登入驗證: 帳號=$username, 密碼=$password")

                // 註冊/查詢帳號
                val account = PassportServer.ACCOUNTS.computeIfAbsent(username.lowercase()) {
                    PassportServer.PassportAccount(
                        username = username,
                        charName = username,
                        userId = (username.hashCode() and 0x7FFF).coerceAtLeast(1000)
                    )
                }
                PassportServer.lastAccount = account

                PassportServer.buildReply0C(
                    returnCode = 0,
                    sessionType = "nmco_session",
                    passportId = "NGP$username"
                )
            }
            0x002D -> {
                val target = PassportServer.parseSessionQueryReq(frame.payload) ?: ""
                val username = target.removePrefix("NGP")
                println("[Passport] 會話查詢: 目標=$target (帳號=$username)")

                val account = PassportServer.ACCOUNTS[username.lowercase()] ?: PassportServer.lastAccount
                println("[Passport] 會話返回: 角色名=${account.charName}, 帳號數值=${account.userId}")

                PassportServer.buildReply2E(
                    returnCode = 0,
                    userId = account.userId,
                    charName = account.charName
                )
            }
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
