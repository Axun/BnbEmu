package pingu.packet

import pingu.netty.PKT
import pingu.netty.SendPacketBase
import pingu.netty.toBA

// ============================================================================
// 进练习房 (C→S op 75) 回应链 — 按 tst.pcap stream 4 真实线序 (TCP seq) 原样重放
// ============================================================================
// 客户端在练习房界面点"开始"发出 op 75 (body = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)),
// 服务器连发房间设置; 随后客户端自动跟发 297/302 → 服务器回 [108, 106×8];
// 客户端再发 op 92 → 服务器回 [111, 112 SelectMap, 100(77B), 136] 启动游戏;
// 之后服务器周期性广播 op 172 ItemThrow / op 139 (电脑敌人自动行动)。
// 工具: tools/extract_op75.py / tools/gen_practice_kotlin.py; 基准 cap/practice_s2c_op*.bin

// ---- op 83 SessionSimpleInfo (6B): E1(0) E1(0) E2(75) E1(15) E1(0) ----
fun SessionSimpleInfo() = PKT {
    EncodeBuffer(EN_OP83)
}

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

// ---- op 108 练习房版 (8B): E2(0) E2(0) E4(172) ----
fun PracticeColor() = PKT {
    EncodeBuffer(EN_OP108)
}

// ---- op 111 AILevelChange 空体 (1B): E1(0) —— 练习房开局时的 AI 等级重置 ----
fun PracticeAiLevel() = PKT {
    EncodeBuffer(EN_OP111)
}

// ---- op 112 SelectMap 练习房版 (165B, 含 8 槽出生点) ----
fun PracticeSelectMap() = PKT {
    EncodeBuffer(EN_OP112)
}

// ---- op 136 棋盘/开局数据块 (178B) — 原样重放 ----
fun PracticeBoard() = PKT {
    EncodeBuffer(EN_OP136)
}

// ---- op 139 (24B) — 游戏内 AI/回合推进包, 原样重放 (6 样本逐字节相同) ----
fun PracticeAiAck() = PKT {
    EncodeBuffer(EN_OP139)
}

// ---- 练习房专用 198 / 423 变体 (与进频道握手推送的 198/423 略异) ----
fun PracticeUnk198() = PKT {
    EncodeBuffer(EN_OP198_PRACTICE)
}
fun PracticeUnk423() = PKT {
    EncodeBuffer(EN_OP423_PRACTICE)
}
