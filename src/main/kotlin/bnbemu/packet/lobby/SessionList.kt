package bnbemu.packet.lobby

import bnbemu.codec.encodeName
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

// 频道在线用户列表 — op 72 (enter-channel.pcap 线序 seq=73)
// 条目 = E2(len)+角色名令牌(encodeName) + 16 个固定栏位; JoinSessionResult 以同一令牌指名进房玩家
fun SessionList(entries: List<SessionEntry> = EN_SAMPLE_ENTRIES) = PKT {
    Encode2(0)
    Encode1(entries.size)
    entries.forEach { it.Encode(this) }
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
