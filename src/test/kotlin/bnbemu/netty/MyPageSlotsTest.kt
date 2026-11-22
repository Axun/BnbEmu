package bnbemu.netty

import bnbemu.packet.user.MY_PAGE_COUPLE_SLOT
import bnbemu.packet.user.MY_PAGE_MAX_PAGES
import bnbemu.packet.user.MY_PAGE_SLOTS_PER_PAGE
import bnbemu.packet.user.MyPageSlot
import bnbemu.packet.user.MyPageSlots
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S→C op 200 MyPageSlots（旧名 FxStockInit）结构化生成的回归基准。
 *
 * 抓包基准 cap/chan_s2c_op200_105B_0.bin (decoded_dump.txt:147, sq=106) 含 2 B opcode 前缀，
 * 本包体 = 去掉 opcode 后的 103 B：
 *   E1(2) + page0 8 槽 + page1 8 槽，每槽 [E4 itemId][E2 长度 + 0x75DAF0 令牌变换内容]
 *   槽 7 = (itemId 1, "Dao")，其余槽 (0, "")
 */
class MyPageSlotsTest {

    private fun encode(pkt: PKT): ByteArray {
        val buf = Unpooled.buffer()
        pkt.encode(SendPacketBase(buf))
        val out = ByteArray(buf.readableBytes())
        buf.readBytes(out)
        return out
    }

    @Test
    fun `默认表全部为空槽`() {
        val actual = encode(MyPageSlots())
        assertEquals(97, actual.size, "2 页 × 8 空槽 → 1 + 16×6 = 97 B")
        val slots = decode(actual)
        assertEquals(MY_PAGE_MAX_PAGES, slots.size)
        slots.forEach { page ->
            assertEquals(MY_PAGE_SLOTS_PER_PAGE, page.size)
            assertTrue(page.all { it.itemId == 0 && it.name.isEmpty() }, "默认无快捷表情与伪角色卡占位")
        }
    }

    @Test
    fun `显式构造槽 7 占位与抓包逐字节一致`() {
        val capPage = List(MY_PAGE_SLOTS_PER_PAGE) { i ->
            if (i == MY_PAGE_COUPLE_SLOT) MyPageSlot(1, "Dao") else MyPageSlot.EMPTY
        }
        val actual = encode(MyPageSlots(listOf(capPage, capPage)))
        assertEquals(103, actual.size, "2 页 × 8 槽、仅两槽非空 → 1 + 14×6 + 2×9 = 103 B")
        assertArrayEquals(CAPTURE_BODY, actual, "op 200 包体结构与 cap/chan_s2c_op200_105B_0.bin 逐字节一致")
    }

    @Test
    fun `抓包文件在仓库内时与内联基准互证`() {
        val cap = File("cap/chan_s2c_op200_105B_0.bin").takeIf { it.isFile } ?: return
        assertArrayEquals(CAPTURE_BODY, cap.readBytes().drop(2).toByteArray(), "内联基准应与抓包一致")
    }

    private companion object {
        /** cap/chan_s2c_op200_105B_0.bin 去掉 2 B opcode 前缀后的包体 */
        val CAPTURE_BODY: ByteArray = """
            58 96 CA 53 95 A5 69 96 CA 53 95 A5 69 96 CA 53 95 A5 69
            96 CA 53 95 A5 69 96 CA 53 95 A5 69 96 CA 53 95 A5 69
            96 CA 53 95 A5 69 96 CA 53 94 A5 6A C4 2D 35
            96 CA 53 95 A5 69 96 CA 53 95 A5 69 96 CA 53 95 A5 69
            96 CA 53 95 A5 69 96 CA 53 95 A5 69 96 CA 53 95 A5 69
            96 CA 53 95 A5 69 96 CA 53 94 A5 6A C4 2D 35
        """.toBA()
    }

    @Test
    fun `包体布局可逆解析`() {
        val capPage = List(MY_PAGE_SLOTS_PER_PAGE) { i ->
            if (i == MY_PAGE_COUPLE_SLOT) MyPageSlot(1, "Dao") else MyPageSlot.EMPTY
        }
        val body = encode(MyPageSlots(listOf(capPage, capPage)))
        val slots = decode(body)
        assertEquals(MY_PAGE_MAX_PAGES, slots.size, "页数 = E1")
        slots.forEach { page ->
            assertEquals(MY_PAGE_SLOTS_PER_PAGE, page.size, "每页恒 8 槽")
            page.forEachIndexed { i, slot ->
                if (i == MY_PAGE_COUPLE_SLOT) {
                    assertEquals(1, slot.itemId, "显式填充槽 7 = itemId 1")
                    assertEquals("Dao", slot.name, "名字取自 op 60 定义表")
                } else {
                    assertEquals(0, slot.itemId, "其余槽为空")
                    assertEquals("", slot.name)
                }
            }
        }
    }

    @Test
    fun `显式槽位表按页槽展开且越界安全`() {
        val page0 = listOf(
            MyPageSlot.of(30000),
            MyPageSlot.of(30001),
            MyPageSlot(31000, "Marid"),
        )
        // 第二页故意多给 10 项：前 8 项生效，多余忽略
        val page1 = (0 until 10).map { MyPageSlot(30000 + it, "Dao") }
        val body = encode(MyPageSlots(listOf(page0, page1)))
        val slots = decode(body)
        assertEquals(2, slots.size)
        // 未给满的槽自动补空
        assertEquals(30000, slots[0][0].itemId)
        assertEquals("Dao", slots[0][0].name, "MyPageSlot.of 应自动解析模型名")
        assertEquals(31000, slots[0][2].itemId)
        assertEquals("Marid", slots[0][2].name)
        (3 until 8).forEach { assertEquals(0, slots[0][it].itemId, "缺省槽 $it 必须补 0") }
        assertEquals(30007, slots[1][7].itemId, "第 8 槽起截断")
        assertTrue(body.size > 0)
    }

    @Test
    fun `超过两页的部分被丢弃`() {
        val page = List(8) { MyPageSlot.EMPTY }
        val body = encode(MyPageSlots(listOf(page, page, page)))
        assertEquals(2, (body[0].toInt() and 0xFF) xor 0x5A, "E1 必须是 clamp 后的页数 2")
    }

    @Test
    fun `空表只写页数 0`() {
        val body = encode(MyPageSlots(emptyList()))
        assertArrayEquals(byteArrayOf(0x5A), body, "页数 0 → 单个 E1(0) 字节")
    }

    // ---- 与 packet/shop/MyPageSlots.kt 注释等价的解码器，独立实现以避免自证 ----

    private fun decode(body: ByteArray): List<List<MyPageSlot>> {
        var p = 0
        val pages = body[p++].toInt() and 0xFF xor 0x5A
        return (0 until pages).map {
            (0 until MY_PAGE_SLOTS_PER_PAGE).map {
                val itemId = int(body, p) xor 0x96CA5395.toInt(); p += 4
                val len = ((body[p].toInt() and 0xFF) shl 8 or (body[p + 1].toInt() and 0xFF)) xor 0xA569; p += 2
                val raw = body.copyOfRange(p, p + len); p += len
                MyPageSlot(itemId, decryptName(raw))
            }
        }
    }

    private fun int(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** 0x75DAF0 逆变换：4B 组 BE 读 ^0x96CA5395 按 LE 写，余 2B BE 读 ^0xA569，余 1B ^0x5A。 */
    private fun decryptName(raw: ByteArray): String {
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
        return String(out, Charsets.ISO_8859_1)
    }
}
