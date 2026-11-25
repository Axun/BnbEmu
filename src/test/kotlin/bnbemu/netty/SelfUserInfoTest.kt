package bnbemu.netty

import bnbemu.data.ItemDefCatalog
import bnbemu.domain.AppearanceRules
import bnbemu.domain.PlayerProfile
import bnbemu.domain.UserItemEntry
import bnbemu.packet.lobby.SelfUserInfoEntry
import bnbemu.packet.lobby.SelfUserInfo
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * S→C op 254 字段布局回归。
 *
 * 基准 = `cap/chan_s2c_op254_56B_0.bin` 去掉 2B opcode 后的 54B body，
 * 客户端解析链 `0x004E09D6 → sub_4E061C`（见 packet/lobby/SelfUserInfo.kt 字段表）。
 * 这里钉死两件事：
 *  1. 19 个字段的编码顺序/宽度不能变（否则 54B 立刻不等长，2P 时后续字段全部错位）；
 *  2. 名牌 (+0xD4) 与经验千分比 (+0x17C) 落在正确位置。
 */
class SelfUserInfoTest {

    private fun payload(entry: SelfUserInfoEntry): ByteArray {
        val buf = Unpooled.buffer()
        SelfUserInfo(entry).encode(SendPacketBase(buf, encrypted = true))
        return ByteArray(buf.readableBytes()).also { buf.readBytes(it) }
    }

    @Test
    fun `54 byte body matches captured packet`() {
        // 抓包样本: 等级 1 / 名牌 0 / 经验 0 / 其余全 0
        val entry = SelfUserInfoEntry(
            session = byteArrayOf(0xF0.toByte(), 0xAE.toByte(), 0x35, 0xF3.toByte()),
            level = 1,
        )
        assertEquals(
            "5ba56df0ae35f35a5a96ca53955a96ca5395a56896ca53a7" +
                "96ca539596ca539596ca53955a96ca5395" +
                "a569a569a569a56996ca53955a",
            payload(entry).toHex(),
        )
    }

    @Test
    fun `nameplate and exp sit at 0xD4 and 0x17C`() {
        val entry = SelfUserInfoEntry(
            session = byteArrayOf(0xF0.toByte(), 0xAE.toByte(), 0x35, 0xF3.toByte()),
            level = 326,
            nameplate = 16,
            expPercent = 999,
        )
        val p = payload(entry)
        assertEquals(54, p.size)

        var i = 1 + 2 + 4 // hasUser + E2(len) + 4B token
        fun u1() = (p[i++].toInt() and 0xFF) xor 0x5A
        fun u2(): Int {
            val v = (((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)) xor 0xA569
            i += 2; return v
        }
        fun u4(): Int {
            val v = (((p[i].toInt() and 0xFF) shl 24) or ((p[i + 1].toInt() and 0xFF) shl 16) or
                ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)) xor 0x96CA5395.toInt()
            i += 4; return v
        }

        assertEquals(0, u1())              // +0x020
        assertEquals(0, u1())              // +0x02C 段位
        assertEquals(0, u4())              // +0x038
        assertEquals(0, u1())              // +0x044
        assertEquals(0, u4())              // +0x050
        assertEquals(326, u2())            // +0x05C 等级
        assertEquals(50, u4())             // +0x068 等级图标特效组
        assertEquals(16, u4())             // +0x0D4 名牌
        assertEquals(0, u4())              // +0x100
        assertEquals(0, u4())              // +0x104
        assertEquals(0, u1())              // +0x110
        assertEquals(0, u4())              // +0x11C
        assertEquals(0, u2())              // +0x138
        assertEquals(0, u2())              // +0x13C 段位子档
        assertEquals(0, u2())              // +0x140
        assertEquals(0, u2())              // +0x16C
        assertEquals(999, u4())            // +0x17C 经验千分比
        assertEquals(0, u1())              // +0x188
        assertEquals(54, i)
    }

    @Test
    fun `nameplate style comes from equipped category 18 item`() {
        val plate = ItemDefCatalog.find(2441)
        assertNotNull(plate, "基础物品表应含 2441 (Icon/ItemIDdeco_C001)")
        assertEquals(18, plate!!.category)

        fun profile(vararg items: UserItemEntry) = PlayerProfile(
            userId = 9500, username = "plate", inventory = items.toList(),
        )

        val equipped = profile(UserItemEntry(2441, equipSlot = 216))
        assertEquals(plate.skinId, AppearanceRules.namePlateStyle(equipped))

        // 放在背包里 (equipSlot = null) 不算穿戴
        assertEquals(0, AppearanceRules.namePlateStyle(profile(UserItemEntry(2441))))

        // 经验千分比钳在 0..999
        val p = profile()
        p.exp = 1500
        assertEquals(999, AppearanceRules.expPercent(p))
        p.exp = -1
        assertEquals(0, AppearanceRules.expPercent(p))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
