package bnbemu.netty

import bnbemu.data.FxdynMapInfo
import bnbemu.packet.DynamicMapInfo
import bnbemu.packet.FxConfig
import bnbemu.packet.ItemDef
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * op 210 由 fxdynmapinfo.json 实时构造后的回归基准。
 * 2026-11-12 起 records 扩展为 1573 条（仅保留网格<=51x51、maxPlayer<=8 的国服地图，见 tools/data/convert_cn_maps.py + add_missing_maps.py），
 * 硬编码指纹改为扩展后的产物，文件比对指向 cap/rebuilt/（由 build_fxdynmapinfo.py --emit-only 生成）。
 * cap/ 下 2026-08/09 的原始抓包基准仍保留，仅对应前 342 条记录，作历史对照。
 */
class FxdynMapInfoPacketTest {

    private fun u32le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun zlibDecompress(b: ByteArray, off: Int, len: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(b, off, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    @Test
    fun `records 可重建扩展后的明文`() {
        val plain = FxdynMapInfo.plain
        assertEquals(1573, FxdynMapInfo.records.size)
        assertTrue(plain.size > 100_000, "1573 条地图明文应超过 100 KB")
        assertEquals(1573, ((plain[1].toInt() and 0xFF) shl 8) or (plain[0].toInt() and 0xFF))
    }

    @Test
    fun `op210 默认启用 zlib 压缩且可无损解压`() {
        val plain = FxdynMapInfo.plain
        val block = FxdynMapInfo.buildBlock(plain)
        assertEquals(0x18, block[0].toInt() and 0xFF)
        assertEquals(1, block[5].toInt() and 0xFF, "compress=1 标志位")
        val payloadLen = u32le(block, 1)
        assertEquals(block.size - 5, payloadLen, "SSD 块头长度须等于 payload 长度")
        val rawSize = u32le(block, 6)
        assertEquals(plain.size, rawSize, "compress=1 须在 +6 写入解压后明文长度")
        assertArrayEquals(plain, zlibDecompress(block, 10, block.size - 10), "zlib 解压须还原原始明文")

        val uncompressed = FxdynMapInfo.buildBlock(plain, compress = false)
        assertEquals(0, uncompressed[5].toInt() and 0xFF)
        assertArrayEquals(plain, uncompressed.copyOfRange(6, uncompressed.size))

        val full = FxdynMapInfo.buildFullPacket()
        assertTrue(full.size < 20_000, "zlib 压缩后 op210 完整包应小于 20 KB，实际=${full.size}")
    }

    @Test
    fun `op60 ItemDef 默认启用 zlib 压缩且可无损解压`() {
        val rawBlock = ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS, compress = false)
        val compressedBlock = ItemDef.buildBlock(ItemDef.DEFAULT_ITEMS)
        assertEquals(0x18, compressedBlock[0].toInt() and 0xFF)
        assertEquals(1, compressedBlock[5].toInt() and 0xFF, "ItemDef compress=1")
        assertEquals(rawBlock.size - 6, u32le(compressedBlock, 6))
        assertArrayEquals(
            rawBlock.copyOfRange(6, rawBlock.size),
            zlibDecompress(compressedBlock, 10, compressedBlock.size - 10),
        )
        assertTrue(ItemDef.blobFor(0).size < 20_000, "压缩后 op60 块应小于 20 KB，实际=${ItemDef.blobFor(0).size}")
    }

    @Test
    fun `DynamicMapInfo 编码结果等于动态构造的 payload`() {
        val buf = Unpooled.buffer()
        DynamicMapInfo().encode(SendPacketBase(buf, 3))
        val actual = ByteArray(buf.readableBytes())
        buf.readBytes(actual)
        assertEquals(FxdynMapInfo.buildFullPacket().size - 2, actual.size)
        assertTrue(actual.size < 20_000, "压缩后包体应远低于客户端 131070B 收包缓冲上限")
        assertArrayEquals(FxdynMapInfo.packetBlob, actual)
    }

    @Test
    fun `改 records 会同步改变下发字节与 mapId 派生`() {
        val text = FxdynMapInfo.readSourceText()
        val original = FxdynMapInfo.parseRecords(text)
        assertArrayEquals(FxdynMapInfo.plain, FxdynMapInfo.serializePlain(original), "解析→序列化须往返一致")

        assertEquals("Cemetery04", original.filter { it.flag == 0 }[176].name)
        val line = text.lines().first { it.contains("\"flag\":0") && it.contains("\"name\":\"Cemetery04\"") }
        val changed = text.replaceFirst(line, line.replace("\"name\":\"Cemetery04\"", "\"name\":\"CemeteryXX\""))
        assertNotEquals(text, changed)

        val recs = FxdynMapInfo.parseRecords(changed)
        val newPlain = FxdynMapInfo.serializePlain(recs)
        assertFalse(newPlain.contentEquals(FxdynMapInfo.plain), "改记录后下发明文必须变化")
        assertFalse(FxdynMapInfo.buildPacket(newPlain).contentEquals(FxdynMapInfo.buildFullPacket()), "压缩包须同步变化")
        assertEquals("CemeteryXX", recs.filter { it.flag == 0 }[176].name, "mapId 派生须来自同一份 records")
    }

    @Test
    fun `ssdStreamChecksum 复现客户端 Adler-32 变体`() {
        // [0x18][u24 4][0][0]['a','b','c']，客户端只从 offset 5 起算：00 61 62 63
        val tiny = FxdynMapInfo.buildBlock(byteArrayOf(0x61, 0x62, 0x63))
        assertArrayEquals(byteArrayOf(0x18, 4, 0, 0, 0, 0, 0x61, 0x62, 0x63), tiny)
        assertEquals(0x024A0126, FxdynMapInfo.ssdStreamChecksum(tiny), "a/b 初值必须为 0")

        // 2026-08 抓包旧表基准：24111 B 未压缩明文 → op212 首字段 0x36CEE587
        File("cap/fxdynmapinfo_plain.bin").takeIf { it.isFile }?.let {
            assertEquals(24111, it.length().toInt())
            assertEquals(
                0x36CEE587,
                FxdynMapInfo.ssdStreamChecksum(FxdynMapInfo.buildBlock(it.readBytes(), compress = false)),
                "抓包常量必须与反编译算法复算一致",
            )
        }
    }

    @Test
    fun `op212 首字段由当前压缩 SSD 块实时计算`() {
        val block = FxdynMapInfo.buildBlock(FxdynMapInfo.plain)
        assertEquals(1, block[5].toInt() and 0xFF)
        val expected = FxdynMapInfo.ssdStreamChecksum(block)
        assertEquals(expected, FxdynMapInfo.checksum, "实时值必须来自同一份默认压缩块")
        assertNotEquals(
            FxdynMapInfo.ssdStreamChecksum(FxdynMapInfo.buildBlock(FxdynMapInfo.plain, compress = false)),
            expected,
            "压缩流校验和与明文块不同，必须按实际下发的压缩流计算",
        )
        assertNotEquals(0x36CEE587, expected, "表扩展后不应再发抓包旧值")
    }

    @Test
    fun `FxConfig 只有首 4 字节随校验和改变`() {
        val buf = Unpooled.buffer()
        FxConfig().encode(SendPacketBase(buf, 3))
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        assertEquals(20, bytes.size)

        val wire = bytes.copyOfRange(0, 4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        assertEquals(FxdynMapInfo.checksum xor 0x96CA5395.toInt(), wire, "Encode4 写入实时校验戳")
        assertArrayEquals(
            "5a a5 7f 96 ca 53 94 52 5a 5b 58 59 5e 5c 52 53".toBA(),
            bytes.copyOfRange(4, 20),
            "macroDetect / 两个保留字段 / 角色列表仍与抓包逐字节一致",
        )
    }
}
