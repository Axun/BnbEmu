package bnbemu.data

import bnbemu.codec.buildSsdBlock
import bnbemu.codec.ss
import bnbemu.codec.ssdEncode
import bnbemu.codec.u16le
import java.io.ByteArrayOutputStream

// ============================================================================
// S→C op 2：csysstr.ssd 系统字串字典（首连推送 #2，start.pcap sq=46，1861 B）
// ============================================================================
// 归属已按客户端反编译钉死：CStringMan（不是 CQuestStringMan，后者是 op 67 / csysqstr.ssd）。
//   reference/CA.exe.c:556772 sub_682B7C = 首连握手，先发 op 0（带 5 个 revision），
//   再**硬编码**顺序等 193 → 2 → 67 → [61] → 1 → 51；:556805 判 opcode==2 后
//   调 sub_6C406B(CStringMan 单例 dword_993E50)。客户端还会校验包序，缺包即判登录失败。
//   :615262 sub_6C406B 收包：ReadUInt8 作 hasNew —— 1 = 后面跟资料块（解析+比对校验和+写盘），
//   0 = 复制本地缓存流(this+17)后解析，不写盘（此时客户端沿用本地 csysstr.ssd）。
//   :615187 sub_6C3ED6 解析：ReadUInt16 取条数，循环 ReadString(key)/ReadString(value)
//   （sub_78CE60，u32 LE 串长），key 经 sub_75C7E0 逐字符 _mbctolower 后进字典 this+3。
//   :615148 sub_6C3E2B 构造 CStringMan：:615164 拼 "csysstr.ssd" → sub_6B9141 读盘。
//   :605273 sub_6B8F7E 写盘：只有块校验和变化才 sub_6B8E3F 覆写文件。
//
// 包体（1859 B payload + 2 B opcode = 基准 cap/firstconn_s2c_op2.bin 1861 B）：
//   Encode1Bool(true)       // hasNew = 1
//   Encode4(块长 1854)      // = 下面 SSD 块字节数
//   EncodeBuffer(1854 B)    // = ssdEncode(buildSsdBlock(plain, compress = false))
// 块（1854 B）= [0x18][u32 LE 1849][compress=0][plain 1848]：
//   plain = u16 LE count(38) + 38 × [ u32 LE keyLen | key | u32 LE valLen | value ]
//   1854 = 4×463 + 2，末 2 字节走 ssdEncode 的 0xA569 分支（sub_75DA10 :709321）：
//   明文 ". " → 线上 `85 47`（0x8547 ^ 0xA569 = 0x202E → LE ". "）。
//   ⚠ 早前笔记/工具把这两字节当「原样保留」，解出来是乱码；客户端一直接 4/2/1 三档解码。
//
// 为什么 compress 固定 false：真服就是发未压缩块（抓包 compress=0）。客户端把这个未压缩块的
// 校验和（[compress..末端] 的 Adler-32 变体，sub_78BB60 :747990）与本地文件比对，而本地文件由
// 客户端自己压成 compress=1 后存盘，两者本来就不会相等 —— 所以真服也永远发 hasNew=1，
// 客户端每次登录重写一次内容相同的 csysstr.ssd。改成 compress=true 会偏离基准且没有收益。
//
// 版本号（供 op 0 / op 55 参考，本模拟器暂不上报）：客户端上报的是 CStringMan+92，
// 即 sub_6B8F7E 写入的块校验和。抓包 op 0 的 5 个 u32 解出 [0, 0x1255CF6D, 0, 0xA8350869,
// 0x380A0583]：第 2 个正是本地 csysstr.ssd 的 compress=1 块校验（已复算一致），
// 第 1、3 个为 0 是因为本地没有 fxdynimginfo.ssd / csysqstr.ssd，
// 第 4、5 个是 multisvr（改动 IP 前）与 filterstr 的版本戳。
//
// 表内容 = 运营可热更的文案子集，不是全部 UI 文本（例如 "ErrCannotLogin" 就不在表内）。
// key 由代码动态拼：CCharacterNameMan 用 "Ch%02d"（:181615）取角色名，
// 频道描述用 "ChannelDesc%d"（:187140）；值里的 %s/%d 由客户端在显示时格式化
// （"JoinSession" = "%s has joined.\n" 就是大厅系统消息）。
// ============================================================================

/** csysstr.ssd 的一条 key→文案；key 在客户端会转小写后建索引，查询大小写不敏感。 */
data class SysStrEntry(val key: String, val value: String)

/** 抓包原表的 38 条（顺序即线上顺序，改动会改变包长，客户端按条数整表替换）。 */
internal val EN_SYS_STR: List<SysStrEntry> = listOf(
    SysStrEntry("Wriggle", "Trap and pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("MissionWriggleMsg", "Pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("Ch14", "Posh Dao"),
    SysStrEntry("Ch18", "Posh Diz"),
    SysStrEntry("Ch17", "Posh Moss"),
    SysStrEntry("LuxuryKephi", "Posh Plunk"),
    SysStrEntry("SuperUni", "Posh Ike"),
    SysStrEntry("RoomGameTypeSelectToolTip", "Filtered Selection"),
    SysStrEntry("ChannelDesc0", "Come on in!"),
    SysStrEntry("WriggleRandom", "Trap and pop goobies to send them to the other team!\nSend the most goobies to win!"),
    SysStrEntry("NoItemFound", "The item you are looking for has not been found or is not currently available. "),
    SysStrEntry("QuestStart", "Start"),
    SysStrEntry("QuestStop", "Stop"),
    SysStrEntry("SquareToolTipGBB", "Rock Paper Scissors"),
    SysStrEntry("SquareToolTipAIBattleGuide", "Battle Mode Tips"),
    SysStrEntry("SquareToolTipPlayGame", "Find Match"),
    SysStrEntry("SquareToolTipFriendSearch", "Friend Search"),
    SysStrEntry("Luca", "Luca is the BEST!!"),
    SysStrEntry("CreateDlgGameTypeAll", "All"),
    SysStrEntry("CreateDlgGameTypeNpcBattle", "Monster"),
    SysStrEntry("WannaLeaveLadderRoom", "CRAZYARCADE orbs collected in this room will be lost. Are you sure you want to leave?"),
    SysStrEntry("ChannelDesc50", "Test your skill!"),
    SysStrEntry("Gather11Balls", "Collect all CRAZYARCADE orbs for a bonus!"),
    SysStrEntry("ShowWaitRoomToolTip", "Only show open rooms"),
    SysStrEntry("ShowAllRoomToolTip", "Show all rooms"),
    SysStrEntry("CreatePracticeRoomToolTip", "Practice your moves!"),
    SysStrEntry("ChatIgnoreToolTip", "Ignore List"),
    SysStrEntry("ShoutToolTip", "Megaphone"),
    SysStrEntry("UserRejectJoinSession", "You cannot join this room."),
    SysStrEntry("SSNMismatched2", "Your inventory is full. Please delete items in order to buy more. "),
    SysStrEntry("SquareMsgDisconnect", "Disconnected from Town. Please try again."),
    SysStrEntry("LobbyButton", "Return To Lobby"),
    SysStrEntry("ConnectedTo", "Online - %s"),
    SysStrEntry("ConnectedTo2", "Online - %s [%s]"),
    SysStrEntry("JoinSession", "%s has joined.\n"),
    SysStrEntry("LeaveSession", "%s has left.\n"),
    SysStrEntry("MasterJoinSession", "[Ladder Grade %d %s has joined.]\n"),
    SysStrEntry("CannotJoinOverflow", "This channel is full. Please join another channel. "),
)

/**
 * csysstr.ssd 单一事实源：38 条文案全部在这里定义，包体实时派生。
 * 条数变化会自动改包长，不需要同步任何 hex 常量。
 */
object CsysStr {

    /** 线上顺序即数组顺序（客户端按条数整表替换，不做增量/合并）。 */
    val entries: List<SysStrEntry> = EN_SYS_STR

    /** csysstr.ssd 缓存体明文（抓包 1848 B）：u16 LE 条数 + 逐条两个 u32 LE 长度前缀串。 */
    val plain: ByteArray by lazy { serializePlain(entries) }

    /** 未编码的 SSD 块（1854 B）：[0x18][u32 LE 块长-5][compress=0][plain]。 */
    val block: ByteArray by lazy { buildSsdBlock(plain, compress = false) }

    /** 上线的块字节（ssdEncode 的 4/2/1 三档变换结果），供 FirstInfoInit 直接下发。 */
    val wireBlock: ByteArray by lazy { ssdEncode(block) }

    /** 序列化缓存体；条数上限是线上 u16，超了客户端会截断。 */
    fun serializePlain(entries: List<SysStrEntry>): ByteArray {
        require(entries.size <= 0xFFFF) { "csysstr 条数 ${entries.size} 超出 u16 上限" }
        val w = ByteArrayOutputStream()
        w.u16le(entries.size)
        for (e in entries) {
            w.ss(e.key)
            w.ss(e.value)
        }
        return w.toByteArray()
    }
}
