package bnbemu.packet.room

import bnbemu.netty.PKT

// ============================================================================
// S→C op 111: StartGameResult —— 「開始遊戲」請求 (C→S op 92) 的應答 / 拒絕原因
// ============================================================================
// 历史误标：旧名 PracticeAiLevel（以及 yaml 的 AILevelChange: 111）沿袭 TW_5 的猜测，
// 与 AI 难度档毫无关系；AI 等级走 S→C op 103 PracticeSetSlotAI → sub_685E9B。
//
// 客户端处理入口：CPrepareStage::OnPacket (sub_52A949, reference/CA.exe.c:251708) case 111 →
//   sub_5285BD (0x005285BD, reference/CA.exe.c:249938)。整个 .text 只有这一个 op 111 接收点。
//
// 帧体只有 1 字节：`result = Packet_ReadUInt8(a2); switch (result)`，没有第二个字段。
//
// 为什么确定它是 op 92 的应答（而不是「房间 UI 开关通知」）：
//   sub_5285BD 一进函数就 `if (*(this+3038) == 3) *(this+3038) = 0;`，
//   而 this+3038 是「等待服务端回包」的锁状态枚举；值 3 在整个二进制里只由开局发包器
//   sub_527EE0 (0x00527EE0, CA.exe.c:249638) 在发出 C→S op 92 之后写入，
//   其余 1/2/5/6/7/9 分别由 op 85 / 89 / 87 / 86 / 117 / 天梯匹配 的发包点写入。
//   ⇒ op 111 就是玩家点「开始」后被服务端受理/驳回的结果码。
//
// 取值语义（Encode1，1 字节）：
//   0, 1      = 成功。不弹文案、不报错，只调 vtable[+0x1C4](this, 0) 让房间 UI 继续锁着
//               （马上要切对局场景，不许再动房间）；抓包真实服务端发 0（帧体 0x5A）。
//   2..8, 10, 13, 14, 15, 18, 19, 20, 22, 23, 24 = 失败：先播错误音效
//               sub_4DEDAD(..., 20, ...)，再 sub_5261BF(this, code) 弹本地化失败原因，
//               最后 vtable[+0x1C4](this, 1) 解锁 UI 让玩家改设置。
//   其余值     = 落进 switch 的 default，直接 return：既不提示也不解锁 UI（房间按钮卡灰）。
//               ⇒ 服务端只能发 0/1 或上表那些失败码，切勿发 9/11/12/16/17/21/25 等。
//
// 失败码表（sub_5261BF, 0x005261BF, CA.exe.c:248274；右侧是本地化键）：
//    2 CantDecideMap             3 AllMustReady              4 SmallPlayerNo
//    5 SmallMapPlayer            6 SmallTeamNo               7 TeamDoesntMatch
//    8 PremiumOnly               9 IsNotLadderMap           10 IsNotGradeOK
//   12 CantSelExtMapWithoutPremium                         13 IsNotLucciOK
//   14 CantStartGameForLowerLevel                          15 IsNotGachaponOK
//   16 ExceedTeamNo             17 ExceedPlayerNo          18 DontHaveEnterChannelItemStart
//   19 CannotAutoArrange1       20 CannotAutoArrange2       21 CannotSelectFairMode
//   22 IsNotLevelOK             23 DeferShutdown            24 IsNotGradeSeasonOK
//   25 SuperChiefWaitASecond
//
// 客户端自己也会用同一张表做本地预检：sub_527EE0 → sub_51C200 (房间) /
//   sub_51C104 (练习、单人地图 mapId 43..71 与 80/81/82 走这个分支，正常返回 0)。
//   本地预检通过才发 op 92，所以练习房正常路径上服务端回 0 即可（抓包实测即 0）。
const val START_GAME_RESULT_OK = 0
// 1 与 0 行为完全一致（同一 switch 分支）；语义差异未钉死，推测对应 sub_51C200 的
// 「主管代管等待后放行」返回 1，故命名 DEFERRED。服务端正常无需发它。
const val START_GAME_RESULT_OK_DEFERRED = 1

fun StartGameResult(code: Int = START_GAME_RESULT_OK) = PKT {
    Encode1(code) // code=0 → 0x5A，与 cap/practice_s2c_op111_1B_0.bin 逐字节一致（不是 E2 的 2B）
}
