package bnbemu.netty

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32

class PassportTest {

    private val S_TABLE = intArrayOf(
        0x40FC1578, 0x113B6C1F, 0x8389CA19.toInt(), 0xE2196CD8.toInt(),
        0x74901489, 0x4AAB1566, 0x7B8C12A0, 0x0018FFCD,
        0xCCAB704B.toInt(), 0x7B5A8C0F, 0xAA13B891.toInt(), 0xDE419807.toInt(),
        0x12FFBCAE, 0x5F5FBA34, 0x10F5AC99, 0xB1C1DD01.toInt()
    )

    /**
     * 抓包裡 0x002E 回包尾部的 65B 固定塊（兩個帳號逐位元組相同）。
     *
     * 客戶端 0x002E 的反序列化器（nmconew.dll!0x1004E810）只消費前 37B，尾塊不參與解析，
     * 因此 [PassportServer.buildReply2E] 已不再下發它；此常數僅用於把「生產 payload」
     * 與「真實抓包 payload」逐位元組對齊，作為回歸基準保留。
     */
    private val HISTORICAL_2E_TAIL = (
        "000000000000000001000000000000000000000000000000000101000088130000" +
        "0000000000000000000000000000000000000000000000000000000000000000"
    ).toBA()

    /** 幀頭 [len:u16 BE][cmd:u16 BE] 裡的 cmd */
    private fun frameCmd(frame: ByteArray): Int =
        ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    /**
     * 取出 S→C 幀裡的明文 payload，並順帶斷言幀/容器自述的長度、魔數、flags、key 與 Adler-32 全部自洽。
     * 只適用於本測試涉及的明文容器（flags=0）。
     */
    private fun containerPayload(frame: ByteArray): ByteArray {
        val contLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        assertEquals(frame.size - 4, contLen, "幀頭自述長度與實際長度不符")

        val c = frame.copyOfRange(4, frame.size)
        assertEquals(0x18, c[0].toInt() and 0xFF, "容器魔數應為 0x18")
        assertEquals(0, c[4].toInt() and 0xFF, "S→C 回應應為明文容器 (flags=0)")

        val bodyLen = ((c[1].toInt() and 0xFF) shl 16) or ((c[2].toInt() and 0xFF) shl 8) or (c[3].toInt() and 0xFF)
        assertEquals(c.size - 4, bodyLen, "容器自述長度 (=12B 頭 + payload) 與實際長度不符")

        val uncompLen = ((c[5].toInt() and 0xFF) shl 16) or ((c[6].toInt() and 0xFF) shl 8) or (c[7].toInt() and 0xFF)
        assertEquals(0, ByteBuffer.wrap(c, 8, 4).order(ByteOrder.BIG_ENDIAN).int, "明文容器 key 應為 0")

        val payload = c.copyOfRange(16, c.size)
        assertEquals(uncompLen, payload.size, "uncompLen 與 payload 長度不符")
        assertEquals(
            Adler32().apply { update(payload) }.value.toInt(),
            ByteBuffer.wrap(c, 12, 4).order(ByteOrder.BIG_ENDIAN).int,
            "Adler-32 校驗值不符"
        )
        return payload
    }

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

    private fun buildContainer(payload: ByteArray): ByteArray {
        val adler = Adler32().apply { update(payload) }
        val checksum = adler.value.toInt()
        val uncompLen = payload.size
        val bodyLen = 12 + uncompLen

        val buf = ByteBuffer.allocate(4 + bodyLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x18.toByte())
        buf.put((bodyLen ushr 16).toByte())
        buf.put((bodyLen ushr 8).toByte())
        buf.put(bodyLen.toByte())

        // 12B header: flags(1B 0), uncompLen(3B), key(4B 0), cksum(4B)
        buf.put(0.toByte())
        buf.put((uncompLen ushr 16).toByte())
        buf.put((uncompLen ushr 8).toByte())
        buf.put(uncompLen.toByte())
        buf.putInt(0)
        buf.putInt(checksum)

        buf.put(payload)
        return buf.array()
    }

    private fun buildFrame(cmd: Int, payload: ByteArray): ByteArray {
        val container = buildContainer(payload)
        val buf = ByteBuffer.allocate(4 + container.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(container.size.toShort())
        buf.putShort(cmd.toShort())
        buf.put(container)
        return buf.array()
    }

    private fun encodeStr(s: String): ByteArray {
        val chars = s.toByteArray(Charsets.UTF_16LE)
        val buf = ByteBuffer.allocate(2 + chars.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(s.length.toShort())
        buf.put(chars)
        return buf.array()
    }

    fun buildReply0C(returnCode: Int = 0, sessionType: String = "nmco_session", passportId: String = "NGPkcworms1"): ByteArray {
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

    @Test
    fun testReplay0CMatchesCapture() {
        val wantLogin = File("cap/passport_login_s0_s2c.bin").readBytes()
        val gotLogin = buildReply0C(0, "nmco_session", "NGPkcworms1")
        assertArrayEquals(wantLogin, gotLogin)

        val wantTst1 = File("cap/passport_tst_s1_s2c.bin").readBytes()
        val gotTst1 = buildReply0C(0, "nmco_session", "NGPkcworm1")
        assertArrayEquals(wantTst1, gotTst1)

        val wantFail = File("cap/passport_tst_s0_s2c.bin").readBytes()
        val gotFail = buildReply0C(20015, "", "")
        assertArrayEquals(wantFail, gotFail)
    }

    @Test
    fun testReplay2EMatchesCapture() {
        // 直接測生產實現（不再複製一份帶尾塊的 buildReply2E，否則測不到真實路徑）。
        // 生產端已不下發 65B 尾塊，故斷言「生產 payload == 抓包 payload - 歷史尾塊」：
        // 漏發/多發任何一個字節都會立刻失敗，同時保留與真實抓包逐位元組對照的價值。
        val tail = HISTORICAL_2E_TAIL
        val cases = listOf(
            Triple("cap/passport_login_s1_s2c.bin", 7215, "ffdf"),
            Triple("cap/passport_tst_s2_s2c.bin", 7307, "kcworm1"),
        )

        for ((file, userId, charName) in cases) {
            val wantFrame = File(file).readBytes()
            val gotFrame = PassportServer.buildReply2E(0, userId, charName)

            assertEquals(0x002E, frameCmd(wantFrame), "$file: 抓包 cmd 應為 0x002E")
            assertEquals(0x002E, frameCmd(gotFrame), "$file: 生產回包 cmd 應為 0x002E")

            val wantPayload = containerPayload(wantFrame)
            val gotPayload = containerPayload(gotFrame)

            assertTrue(gotPayload.size <= wantPayload.size, "$file: 生產 payload 不應長於抓包 payload")
            assertEquals(
                wantPayload.size, gotPayload.size + tail.size,
                "$file: 生產 payload 與抓包 payload 只應相差歷史固定尾塊"
            )
            assertArrayEquals(wantPayload, gotPayload + tail, "$file: payload 逐位元組對照")
        }
    }

    @Test
    fun testDecryptC2S() {
        val c2s = File("cap/passport_login_s0_c2s.bin").readBytes()
        val keyBe = ByteBuffer.wrap(c2s, 12, 4).order(ByteOrder.BIG_ENDIAN).int
        val cipher = c2s.copyOfRange(20, c2s.size)
        val plain = decryptPcbc(cipher, keyBe)

        val plainBuf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        val uLen = plainBuf.short.toInt()
        val uBytes = ByteArray(uLen * 2).apply { plainBuf.get(this) }
        val username = String(uBytes, Charsets.UTF_16LE)

        val pLen = plainBuf.short.toInt()
        val pBytes = ByteArray(pLen * 2).apply { plainBuf.get(this) }
        val password = String(pBytes, Charsets.UTF_16LE)

        assertEquals("kcworms1", username)
        assertEquals("mendouhaiki1", password)
    }

    @Test
    fun testParseLogoutReq() {
        // 實測客戶端點「登出」送出的 cmd 0x000D 幀 (PCBC 加密，明文 = "NGPdffdff")
        val frame = File("cap/passport_logout_s0_c2s.bin").readBytes()
        val cmd = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(0x000D, cmd)
        val container = frame.copyOfRange(4, frame.size)
        assertEquals("NGPdffdff", PassportServer.parseLogoutReq(Unpooled.wrappedBuffer(container)))
    }

    @Test
    fun testParseSessionQueryReqAfterRefactor() {
        // 0x002D 與 0x000D 共用同一個容器解析器，確認重構未改變既有行為
        val frame = File("cap/passport_login_s1_c2s.bin").readBytes()
        val container = frame.copyOfRange(4, frame.size)
        assertEquals(
            "NGPkcworms1",
            PassportServer.parseSessionQueryReq(Unpooled.wrappedBuffer(container))
        )
    }

    @Test
    fun testBuildReply0EFrameShape() {
        val frame = PassportServer.buildReply0E(0)
        val contLen = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val cmd = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, contLen)
        assertEquals(0x000E, cmd)

        val c = frame.copyOfRange(4, frame.size)
        assertEquals(0x18, c[0].toInt() and 0xFF)                 // 容器魔數
        assertEquals(0, c[4].toInt() and 0xFF)                    // flags = 明文
        assertEquals(0, ByteBuffer.wrap(c, 8, 4).order(ByteOrder.BIG_ENDIAN).int)  // key = 0
        val payload = c.copyOfRange(16, c.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), payload)       // returnCode = 0
        val checksum = ByteBuffer.wrap(c, 12, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(Adler32().apply { update(payload) }.value.toInt(), checksum)

        // 錯誤碼也應正確編碼 (u32 LE)
        val fail = PassportServer.buildReply0E(20015)
        assertArrayEquals(byteArrayOf(0x2F, 0x4E, 0, 0), fail.copyOfRange(20, 24))
    }

    @Test
    fun testLogoutSessionLifecycle() {
        val account = PassportServer.PassportAccount("logouttest", "logouttest", 9999)
        val session = PassportServer.openSession(account)
        assertEquals("NGPlogouttest", session.passportId)
        assertTrue(PassportServer.isOnline("NGPlogouttest"))
        assertTrue(PassportServer.isOnline("ngplogouttest"))       // key 大小寫不敏感

        val closed = PassportServer.closeSession("NGPlogouttest")
        assertNotNull(closed)
        assertEquals(9999, closed!!.account.userId)
        assertFalse(PassportServer.isOnline("NGPlogouttest"))
        assertNull(PassportServer.closeSession("NGPlogouttest"))    // 重複登出冪等
        assertNull(PassportServer.closeSession(""))                 // 空目標不誤刪
    }

    @Test
    fun testLogoutViaChannelEndToEnd() {
        // 不需綁定 47611：用 Netty 記憶體通道跑完整鏈路
        // 真實 0x000D 幀 -> PassportDecoder -> PassportHandler -> 0x000E 回包
        val ch = EmbeddedChannel(PassportDecoder(), PassportHandler)
        val frame = File("cap/passport_logout_s0_c2s.bin").readBytes()
        ch.writeInbound(Unpooled.wrappedBuffer(frame))

        val out = ch.readOutbound<ByteBuf>()
        assertNotNull(out)
        val reply = ByteArray(out!!.readableBytes()).apply { out.readBytes(this) }
        val cmd = ((reply[2].toInt() and 0xFF) shl 8) or (reply[3].toInt() and 0xFF)
        assertEquals(0x000E, cmd)
        assertArrayEquals(PassportServer.buildReply0E(0), reply)
        ch.finishAndReleaseAll()
    }
}
