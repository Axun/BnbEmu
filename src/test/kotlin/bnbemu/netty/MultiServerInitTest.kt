package bnbemu.netty

import bnbemu.packet.EN_LOGIN_SERVERS
import bnbemu.packet.LoginServerEntry
import bnbemu.packet.MultiServerEntry
import bnbemu.packet.MultiServerInit
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S→C op 1 MultiServerInit（multisvr.ssd 缓存体）程序化生成的回归基准。
 *
 * 包体 = Encode1Bool(true) + Encode4(块长) + SSD 块 + Encode1(0)：
 *   SSD 块 = ssdEncode(buildSsdBlock(info 流))，info 流 =
 *   u8 mCnt + mCnt×[str name][str label][u32 param] + u16 lCnt + lCnt×[4B IPv4][u16 port][u8 flag] + u8 current
 * 抓包基准 cap/firstconn_s2c_op1.bin（54 B，含 2 B opcode）：66.135.19.7 + 3838，info 流 40 B、块 46 B。
 * 模拟器默认只把 IPv4 换成 127.0.0.1，因此以「抓包的 IP」重建时必须与基准逐字节一致。
 */
class MultiServerInitTest {

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    private fun body(multiServers: List<MultiServerEntry>, loginServers: List<LoginServerEntry>) =
        encode(MultiServerInit(multiServers = multiServers, loginServers = loginServers))

    @Test
    fun `抓包 IP 重建时与基准逐字节一致`() {
        val actual = body(
            listOf(MultiServerEntry(name = "PopTag", label = "Main Server")),
            listOf(LoginServerEntry(ip = "66.135.19.7", port = 3838, flag = 0)),
        )
        assertEquals(52, actual.size, "1 + 4 + 46 + 1 = 52 B 包体")
        assertArrayEquals(CAPTURE_BODY, actual, "程序化重建必须与 cap/firstconn_s2c_op1.bin 的 body 一致")
    }

    @Test
    fun `抓包文件在仓库内时与内联基准互证`() {
        val cap = File("cap/firstconn_s2c_op1.bin").takeIf { it.isFile } ?: return
        assertArrayEquals(CAPTURE_BODY, cap.readBytes().drop(2).toByteArray(), "内联基准应与抓包一致")
    }

    @Test
    fun `默认下发只把 info 流的 IPv4 换成本机配置`() {
        val actual = encode(MultiServerInit())
        assertEquals(52, actual.size, "块长不随 IP 变化")
        // 除 info 流偏移 32..35 的 IPv4 外，其余字段必须与抓包完全一致（按明文比，编码偏移随 IP 漂移）
        val cap = decode(CAPTURE_BODY)
        val mine = decode(actual)
        assertEquals(cap.servers, mine.servers, "服务器组条目与抓包一致")
        assertEquals(cap.currentServer, mine.currentServer)
        assertEquals(cap.logins.single().port, mine.logins.single().port)
        assertEquals(cap.logins.single().flag, mine.logins.single().flag)
        // 默认地址是配置项（切局域网时要改），这里只校验它确实按 EN_LOGIN_SERVERS 落地
        assertEquals(EN_LOGIN_SERVERS.single().ip, mine.logins.single().ip)
    }

    @Test
    fun `包体可逆解析`() {
        val parsed = decode(encode(MultiServerInit()))
        assertEquals(1, parsed.servers.size, "mCnt = E1")
        assertEquals("PopTag", parsed.servers[0].name, "strName 在登录界面下拉框显示")
        assertEquals("Main Server", parsed.servers[0].label, "strIp 栏是显示文案，不是 IP")
        assertEquals(0, parsed.servers[0].param)
        assertEquals(1, parsed.logins.size, "lCnt = u16 LE")
        assertEquals(EN_LOGIN_SERVERS.single().ip, parsed.logins[0].ip)
        assertEquals(3838, parsed.logins[0].port)
        assertEquals(0, parsed.logins[0].flag)
        assertEquals(0, parsed.currentServer)
    }

    @Test
    fun `改地址与端口只影响对应字段`() {
        val a = body(listOf(MultiServerEntry("PopTag", "Main Server")),
            listOf(LoginServerEntry(ip = "192.168.50.4", port = 3838)))
        val b = body(listOf(MultiServerEntry("PopTag", "Main Server")),
            listOf(LoginServerEntry(ip = "192.168.50.4", port = 4848)))
        assertEquals(a.size, b.size, "端口不改变块长")
        val diff = a.indices.filter { a[it] != b[it] }
        assertEquals(2, diff.size, "u16 端口与相邻字节同处一个 dword → 编码后仅 2 字节变化")
        assertEquals(4848, decode(b).logins.single().port)
        assertEquals(3838, decode(a).logins.single().port)
    }

    @Test
    fun `多条登录服务器时块长与条数同步`() {
        val two = body(
            listOf(MultiServerEntry("PopTag", "Main Server")),
            listOf(LoginServerEntry("127.0.0.1", 3838), LoginServerEntry("192.168.50.4", 3838)),
        )
        val parsed = decode(two)
        assertEquals(2, parsed.logins.size)
        assertEquals("192.168.50.4", parsed.logins[1].ip)
        assertEquals(52 + 7, two.size, "每条 login 记录 7 B")
    }

    // ---- 独立解码器：块头 / ssdEncode 逆变换 / info 流字段，与生成的代码互不引用 ----

    private data class Parsed(
        val servers: List<MultiServerEntry>,
        val logins: List<LoginServerEntry>,
        val currentServer: Int,
    )

    private fun decode(body: ByteArray): Parsed {
        var p = 0
        assertEquals(1, (body[p++].toInt() and 0xFF) xor 0x5A, "E1 info 块存在标志")
        val blockLen = int(body, p) xor 0x96CA5395.toInt(); p += 4
        val block = unSsdEncode(body.copyOfRange(p, p + blockLen)); p += blockLen
        assertEquals(0, (body[p].toInt() and 0xFF) xor 0x5A, "E1 notice 块缺失标志")
        assertEquals(body.size - 1, p, "notice 标志是包体最后一字节")

        val plain = unBlock(block)
        var q = 0
        fun str(): String {
            val len = le32(plain, q); q += 4
            val s = String(plain, q, len, Charsets.ISO_8859_1); q += len
            return s
        }
        val servers = (0 until (plain[q++].toInt() and 0xFF)).map {
            val name = str()
            val label = str()
            val param = le32(plain, q); q += 4
            MultiServerEntry(name, label, param)
        }
        val loginCount = le16(plain, q); q += 2
        val logins = (0 until loginCount).map { _ ->
            val ip = "${plain[q].toInt() and 0xFF}.${plain[q + 1].toInt() and 0xFF}." +
                "${plain[q + 2].toInt() and 0xFF}.${plain[q + 3].toInt() and 0xFF}"; q += 4
            val port = le16(plain, q); q += 2
            val flag = plain[q++].toInt() and 0xFF
            LoginServerEntry(ip, port, flag)
        }
        assertEquals(plain.size - 1, q, "currentServer 是 info 流最后一字节")
        return Parsed(servers, logins, plain[q].toInt() and 0xFF)
    }

    /** SSD 容器 → 明文：`[0x18][u32 LE payloadLen][compress]...`（compress=0 分支，本包恒未压缩） */
    private fun unBlock(block: ByteArray): ByteArray {
        assertEquals(0x18.toByte(), block[0], "SSD 块头恒 0x18")
        val payloadLen = le32(block, 1)
        assertEquals(block.size.toLong(), payloadLen + 5L, "块长字段 = len - 5")
        assertEquals(0x00.toByte(), block[5], "info 流压不小 → compress=0")
        return block.copyOfRange(6, block.size)
    }

    /** 0x75DAF0 逆变换：4B 组 BE 读 ^0x96CA5395 按 LE 写，余 2B BE 读 ^0xA569，余 1B ^0x5A */
    private fun unSsdEncode(raw: ByteArray): ByteArray {
        val out = ByteArray(raw.size)
        var i = 0
        while (i + 4 <= raw.size) {
            val v = int(raw, i) xor 0x96CA5395.toInt()
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i + 3] = ((v ushr 24) and 0xFF).toByte()
            i += 4
        }
        if (i + 2 <= raw.size) {
            val v = (((raw[i].toInt() and 0xFF) shl 8) or (raw[i + 1].toInt() and 0xFF)) xor 0xA569
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v ushr 8) and 0xFF).toByte()
            i += 2
        }
        if (i < raw.size) out[i] = ((raw[i].toInt() and 0xFF) xor 0x5A).toByte()
        return out
    }

    private fun int(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun le16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int = le16(b, off) or (le16(b, off + 2) shl 16)

    private companion object {
        /** cap/firstconn_s2c_op1.bin 去掉 2 B opcode 前缀后的 52 B 包体（IP = 66.135.19.7） */
        val CAPTURE_BODY: ByteArray = """
            5B 96 CA 53 BB 96 CA 7A 8D 90 CB 53 95 C6 CA 53
            95 F7 9E 23 FA 96 CA 58 F2 FF AB 1E 95 F3 99 73
            FB E4 AF 25 E7 96 CA 53 95 11 88 53 94 98 34 54
            86 A5 69 5A
        """.toBA()
    }
}
