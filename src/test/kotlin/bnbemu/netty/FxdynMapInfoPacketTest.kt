package bnbemu.netty

import bnbemu.data.FxdynMapInfo
import bnbemu.packet.DynamicMapInfo
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * op 210 由 fxdynmapinfo.json 实时构造后的回归基准。
 * 硬编码的 SHA-256 来自 2026-08/09 抓包，cap/ 下的原始文件未被 git 跟踪，
 * 因此文件存在时额外做逐字节比对，不存在时仍以指纹保证字节级一致。
 */
class FxdynMapInfoPacketTest {

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun `records 可重建 24111B 明文`() {
        val plain = FxdynMapInfo.plain
        assertEquals(342, FxdynMapInfo.records.size)
        assertEquals(24111, plain.size)
        assertEquals("bee1dc646682ff0b11a80b0f987875f83ffbe33221ea6cd910eccebece8ef900", sha256(plain))
        assertEquals(342, ((plain[1].toInt() and 0xFF) shl 8) or (plain[0].toInt() and 0xFF))
        File("cap/fxdynmapinfo_plain.bin").takeIf { it.isFile }?.let {
            assertArrayEquals(it.readBytes(), plain, "明文须与抓包解码基准一致")
        }
    }

    @Test
    fun `op210 完整包与抓包逐字节一致`() {
        val full = FxdynMapInfo.buildFullPacket()
        assertEquals(24123, full.size)
        assertEquals("151cc5e447e472e9aea3168018092b1c3efc1e8d2a69efeb445da4f98e2f22f1", sha256(full))
        assertEquals(0x5A.toByte(), full.last(), "末字节来自 EncodeBuffer 的 1B 余数 XOR 0x5A")
        File("cap/chan_s2c_op210_24123B_0.bin").takeIf { it.isFile }?.let {
            assertArrayEquals(it.readBytes(), full, "完整包须与抓包基准一致")
        }
    }

    @Test
    fun `DynamicMapInfo 编码结果等于动态构造的 payload`() {
        val buf = Unpooled.buffer()
        DynamicMapInfo().encode(SendPacketBase(buf, 3))
        val actual = ByteArray(buf.readableBytes())
        buf.readBytes(actual)
        assertEquals(24121, actual.size)
        assertEquals("29cb413057e085c51a544ea6c9da33c0aea9f6f53566fa063f0ebe2aa67b192c", sha256(actual))
        assertArrayEquals(FxdynMapInfo.packetBlob, actual)
        File("cap/practice_s2c_op210_24121B_0.bin").takeIf { it.isFile }?.let {
            assertArrayEquals(it.readBytes(), actual, "包体须与旧抓包常量一致")
        }
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
        assertEquals(24123, FxdynMapInfo.buildPacket(newPlain).size, "等长改名不改变包长")
        assertEquals("CemeteryXX", recs.filter { it.flag == 0 }[176].name, "mapId 派生须来自同一份 records")
    }
}
