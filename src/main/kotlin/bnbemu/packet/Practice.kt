package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.packet.room.SelectMap
import bnbemu.server.Room
import bnbemu.server.bomber

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


// ---- op 113 (2B): E2(0) ----
fun Unk113() = PKT {
    EncodeBuffer(EN_OP113)
}

// ---- op 100 房间设置版 (78B) / 游戏开始版 (77B) — 两版仅 1 处字节差 ----
fun PracticeRoomInfo() = PKT {
    EncodeBuffer(EN_OP100_ROOM)
}
fun PracticeStartInfo() = PKT {
    EncodeBuffer(EN_OP100_START)
}

// ---- op 108 练习房开局选图回应 (8B): E2(result=0) E2(themeId=0) E4(mapId=172) ----
//    CA.exe CPrepareStage::OnPacket (0x52A949) 的 case 108 = 0x51EFE0 是选图结果处理;
//    这里复用结构化 SelectMap 生成, 与 cap/practice_s2c_op108_10B_0.bin 的 body 逐字节一致。
fun PracticeSelectMap() = SelectMap(result = 0, themeId = 0, mapId = 172)

// ---- op 111 AILevelChange 空体 (1B): E1(0) —— 练习房开局时的 AI 等级重置 ----
fun PracticeAiLevel() = PKT {
    EncodeBuffer(EN_OP111)
}

// ---- op 136 棋盘/开局数据块 (178B) — 原样重放 ----
fun PracticeBoard() = PKT {
    EncodeBuffer(EN_OP136)
}

// ---- op 139 已改为按 C→S op 106 字段动态生成 (见 packet/room/SetBomberEvent.kt) ----

// ---- 练习房专用 423 变体 (与进频道握手推送的 423 略异) ----
fun PracticeUnk423() = PKT {
    EncodeBuffer(EN_OP423_PRACTICE)
}
