package bnbemu.packet.lobby

import bnbemu.codec.encodeName
import bnbemu.netty.PKT
import bnbemu.netty.SendPacketBase

// 大廳名冊 — S→C op 81 (enter-channel.pcap seq=151, 101B 載荷; 4 個週期樣本位元組全同)
// 結構: E4(人數 N) + N × [E1(槽位序號) E4(帳號數值) E2(名長) 角色名令牌]
// 與 op 72 SessionList 同玩家集合但結構不同: 無等級/頻道容量欄, 多帳號數值與槽位序號, 按槽位排序;
// 帳號數值 = passport 0x2E 回應的賬號數值 (ffdf = 7215)
// 進頻道握手推送末尾跟一組 [81, 72], 之後大廳期週期重發 (本包僅單次, 週期未實作)
fun JoinSessionResult(entries: List<RosterEntry>) = PKT {
    Encode4(entries.size)
    entries.forEachIndexed { i, e -> e.Encode(this, i) }
}

/**
 * 为指定玩家构建大厅名册：保留示例同频玩家，将当前玩家槽位替换为真实角色名与 userId。
 * （当传入 7215 / "ffdf" 时输出与抓包逐字节一致。）
 */
fun lobbyRosterFor(charName: String, userId: Int): List<RosterEntry> =
    EN_SAMPLE_ROSTER.mapIndexed { idx, entry ->
        if (idx == 6) RosterEntry(charName, userId) else entry
    }

class RosterEntry(val name: String, val accountId: Int) {
    fun Encode(p: SendPacketBase, slot: Int) {
        val token = encodeName(name)
        p.Encode1(slot)
        p.Encode4(accountId)
        p.Encode2(token.size)
        p.EncodeBuffer(token)
    }
}

// 示例同频玩家占位 (槽位 6 由 lobbyRosterFor 替换为真实玩家)
private val EN_SAMPLE_ROSTER = listOf(
    RosterEntry("Static", 6911),
    RosterEntry("Mirage", 6600),
    RosterEntry("Lotso", 3966),
    RosterEntry("Kuso", 3968),
    RosterEntry("Jacko", 3205),
    RosterEntry("Hello", 119),
    RosterEntry("", 0),
    RosterEntry("suan", 5352),
)
