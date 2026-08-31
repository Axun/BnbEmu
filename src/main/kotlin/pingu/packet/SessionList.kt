package pingu.packet

import pingu.ACP
import pingu.netty.PKT
import pingu.netty.SendPacketBase

// 频道在线用户列表 — op 72 (enter-channel.pcap 线序 seq=73)
// 条目 = E2(len)+角色名令牌(encodeName) + 16 个固定栏位; JoinSessionResult 以同一令牌指名进房玩家
fun SessionList(entries: List<SessionEntry> = EN_SAMPLE_ENTRIES) = PKT {
    Encode2(0)
    Encode1(entries.size)
    entries.forEach { it.Encode(this) }
}

// 角色名令牌编码: 4B 一块 LE 读 ^0x96CA5395 按 BE 写; 尾部 2B LE 读 ^0xA569 按 BE 写, 1B ^0x5A;
// 3B 尾 = 2B 尾 + 1B 尾 (2026-08-30 由 kcworm1 7B 名对出, 与 4/5/6B 名样本回编码逐字节一致)
// (op 81 JoinSessionResult 的名冊令牌同款, internal 供共用)
internal fun encodeName(name: String): ByteArray {
    val src = name.toByteArray(ACP)
    val dst = ByteArray(src.size)
    var i = 0
    while (i + 4 <= src.size) {
        val le = (src[i].toInt() and 0xFF) or
                ((src[i + 1].toInt() and 0xFF) shl 8) or
                ((src[i + 2].toInt() and 0xFF) shl 16) or
                ((src[i + 3].toInt() and 0xFF) shl 24)
        val enc = le xor 0x96CA5395.toInt()
        dst[i] = (enc ushr 24).toByte()
        dst[i + 1] = (enc ushr 16).toByte()
        dst[i + 2] = (enc ushr 8).toByte()
        dst[i + 3] = enc.toByte()
        i += 4
    }
    while (i < src.size) {
        if (src.size - i >= 2) {
            val le = (src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8)
            val enc = le xor 0xA569
            dst[i] = (enc ushr 8).toByte()
            dst[i + 1] = enc.toByte()
            i += 2
        } else {
            dst[i] = (src[i].toInt() xor 0x5A).toByte()
            i += 1
        }
    }
    return dst
}

class SessionEntry(
    val name: String,
    val level: Int,
    val maxCount: Int = 50,
    val listItemBg: Int = 0,
    val grade: Int = 0,
    val state2: Int = 0,
) {
    fun Encode(p: SendPacketBase) {
        val token = encodeName(name)
        p.Encode2(token.size)
        p.EncodeBuffer(token)
        p.Encode1(0)
        p.Encode1(grade)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode2(level)
        p.Encode4(maxCount)
        p.Encode4(listItemBg)
        p.Encode4(0)
        p.Encode4(0)
        p.Encode1(0)
        p.Encode4(0)
        p.Encode2(0)
        p.Encode2(state2)
        p.Encode2(0)
        p.Encode2(0)
    }
}

// 抓包样本 (8 玩家) — 重编码须与原载荷逐字节一致 (replay_test enter 用例断言 op 72)
private val EN_SAMPLE_ENTRIES = listOf(
    SessionEntry("Kuso", level = 326, listItemBg = 16),
    SessionEntry("Lotso", level = 317, listItemBg = 17),
    SessionEntry("Jacko", level = 112),
    SessionEntry("suan", level = 87),
    SessionEntry("Hello", level = 69),
    SessionEntry("Mirage", level = 15, grade = 1, state2 = 1),
    SessionEntry("Static", level = 1),
    SessionEntry("ffdf", level = 1),
)
