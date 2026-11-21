package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.packet.room.SetPlayer
import bnbemu.server.User

// ============================================================================
// 进练习房 (C→S op 75) 回应链 — 按 tst.pcap stream 4 真实线序 (TCP seq) 原样重放
// ============================================================================
// 客户端在练习房界面点"开始"发出 op 75 (body = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)),
// 服务器连发房间设置; 随后客户端自动跟发 297/302 → 服务器回 [108, 106×8];
// 客户端再发 op 92 → 服务器回 [111, 112 SelectMap, 100(77B), 136] 启动游戏;
// 之后对局完全由客户端本地 AI 推进 (服务端只在 C→S op 106 时回 S→C op 139 / 2.5s 后 op 138)。
// ⚠️ 真实服务器另会按地图事件时间轴发 S→C op 172 (MapEventSync, 驱动 "Wave/Game/AirPlane1"),
//    它既不是 "ItemThrow" 也与 AI 行动无关, 见 packet/room/MapEventSync.kt 与 tools/rev_op172.py。
// 工具: tools/extract_op75.py / tools/gen_practice_kotlin.py; 基准 cap/practice_s2c_op*.bin


// ---- op 100 房间设置版 (78B) / 游戏开始版 (77B) — 官方命名 MakeSetPlayerPacket / SetPlayer ----
fun PracticeRoomInfo(user: User? = null, level: Int = user?.level ?: 5) =
    SetPlayer(slotId = 0, user = user, level = level, isStart = false)

fun PracticeStartInfo(user: User? = null, level: Int = user?.level ?: 5) =
    SetPlayer(slotId = 0, user = user, level = level, isStart = true)

// ---- op 111 空体 (1B): E1(0) —— 开局时的房间 UI 开关通知 ----
// ⚠️ 历史误标：函数名 PracticeAiLevel / yaml 的 AILevelChange: 111 都沿袭 TW_5 的猜测，
//    它跟 AI 难度档毫无关系。CA.exe sub_5285BD (reference/CA.exe.c:249938) 只读 1B 参数，
//    case 0/1 调虚表 +0x1C4(this, 0) 关掉房间 UI 按钮，完全不碰全局槽位结构
//    (0x685E9B / 0x686A4C，也就是 op 103 写 AI 等级的地方)。
fun PracticeAiLevel() = PKT {
    EncodeBuffer(EN_OP111)
}

// ---- op 136 棋盘/开局数据块 (178B) — 原样重放 ----
fun PracticeBoard() = PKT {
    EncodeBuffer(EN_OP136)
}
