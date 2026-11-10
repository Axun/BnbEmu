@file:JvmName("CreateSessionReqKt")

package bnbemu.handler

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeCipherStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.ClientStage
import bnbemu.server.*
import org.tinylog.kotlin.Logger

// ============================================================================
// 创建 / 进入会话 (C→S op 75) — EN 客户端所有建房路径共用的通用请求
// ============================================================================
//    反编译里 CSendPacket_CtorWithOp(..., 75)
//    共 8 处 (CA.exe.c 269546 / 269568 / 270030 / 270840 / 271682 / 272279 / 273058 / 530200),
//    普通房/怪物模式等等和练习房共用，练习房只是其中一条参数退化的路径;
//
// 字段顺序由 CA.exe 0x00517233 (sub_517233) 钉死:
//   EncodeStr(房间名)  ← CSendPacket::EncodeStr 的 3 度混淆 (0x75DAF0), 不是明文
//   EncodeStr(密码)    ← 同上
//   E1(a4)             模式: 0 = 练习 / AI 房, 1 = 普通房
//   E1(玩家数)         GetLocalPlayerCount
//   E1 × 玩家数        每个本地玩家的 bomber id (不是固定 1 个!)
//   E1(a5)             房型: 15 = 练习房, 0 = 普通房 (CFxContext[0x570])
//   E4(规则 id)        sub_516C5A 按房型查表: 15 → 66, 0 → 0
// 调用点取值: 271986 练习房 (..., 0, 15); 272008 普通房 (..., 1, a2);
//             272093 建房对话框 (..., v8, v9); 272279 / 530200 硬编码 E1(1) ... E1(0) E4(0)。
//
// 两种实包 (body, 不含 2B opcode):
//   练习房 tst.pcap sq=79 (12B) = E2(0) E2(0) E1(0) E1(1) E1(5) E1(15) E4(66)
//   普通房 (23B) = E2(11) + 11B 加密房名 "Come quick~" + E2(0) E1(1) E1(1) E1(0) E1(0) E4(0)
//   ⚠️ 练习房恰好没有房名内容, 旧实现把「空串 / 15 / 66」这些定值当成了字段定义,
//      普通房到达时 Decode2 只读长度不消费内容 → 后面 6 个字段全部错位
//      (房型读成 229, 又被 takeIf{1..31} 退回 15, 等于把普通房当练习房回)。
//
// 字符串用 PKT.kt 的 DecodeCipherStr (4B BE^0x96CA5395 → LE; 余 2B ^0xA569; 余 1B ^0x5A);
// 反向写回要用 EncodeCipherStr。⚠️ 但 op 87 RoomList 的房名是另一套容器
// (E4 名长 + dword 对齐 + ssdEncode, 见 RoomList.kt), 不适用 EncodeCipherStr 的 E2 长度格式,
// 所以这里只把房名记进 Room.name, 暂不动态改写 RoomList (会破坏 replay_test 逐字节基准)。
//
// 回应链 (seq 顺序, 见 Practice.kt 注释) 目前仍是练习房基准:
//   [87 RoomList, 83 EnterSessionResult, 4(13B), 4(65B), 113, 100(78B), 108,
//    106×8, 每槽(114,104,105,103), 198, 背包, WelcomeNotice]
//   普通房走 CPrepareStage (与练习房的 CSlotStage 读法不同, 见 docs/agents-notes-room.md
//   的 op 104 3B 变体), 需另抓一次普通房 S→C 包才能分叉; 在此之前两条路径共用这条链,
//   仅按请求里的实际房型 / 规则 / 房名做记录与回显。
val CreateSessionReq = PKTHandler { c ->
    val roomName = DecodeCipherStr
    val password = DecodeCipherStr
    val mode = Decode1                          // a4: 0 练习房 / 1 普通房
    val playerCount = Decode1.coerceIn(0, 8)
    val bombers = List(playerCount) { Decode1 } // 每个本地玩家一个, 不能固定读 1 个
    val sessionType = Decode1                   // a5: 15 练习房 / 0 普通房
    val ruleId = Decode4

    val isPractice = mode == 0
    Room.sessionMode = mode
    Room.sessionType = sessionType
    Room.ruleId = ruleId
    Room.name = roomName
    Room.password = password
    Room.localBombers = bombers

    Logger.info(
        "[CreateSession] mode=$mode (${if (isPractice) "practice" else "normal"}) " +
            "type=$sessionType rule=$ruleId name='$roomName' pw=${password.isNotEmpty()} " +
            "players=$playerCount bombers=$bombers"
    )
    if (!isPractice) {
        // 分流点: 普通房的槽位初始化与 S→C 回应链尚未抓包, 暂沿用练习房基准。
        Logger.info("[CreateSession] normal room still uses the practice reply chain; split here after S->C capture")
    }

    // 玩家 (op 7 已把 c.users[0] 填好) 放 slot 0, 其余 7 槽为 AI。
    // ⚠️ 普通房真实应为「其余槽留空等真人加入」, 但回应链里 103/105/114 仍是 AI 槽推送,
    //    两者必须一起改; 等普通房 S→C 抓包钉死线序后再在 isPractice 处分叉。
    val player = c.users.firstOrNull()
    if (player != null) {
        val requestedBomber = bombers.firstOrNull() ?: player.bomber
        val chosenBomber = if (requestedBomber in 0..15) requestedBomber else player.bomber
        val effectiveBomber = if (chosenBomber == 4) 10 else chosenBomber
        player.setBomber(effectiveBomber)

        Room.slots[0].apply {
            this.user = player
            state = FLAG_CHIEF
            bomber = effectiveBomber
            color = 0
        }
        for (i in 1..7) {
            Room.slots[i].apply {
                this.user = null
                isAI = true
                // 新建房是本局设置的起点：难度档回到抓包默认 1，避免全局 Room 单例
                // 把上一次房间设置的 aILevel 带进新房 (sendRoomEntryChain 现按 slots 回灌)。
                aILevel = 1
                bomber = 5
                color = 7
            }
        }
    }

    sendRoomEntryChain(c)
    // op 83 EnterSessionResult 会让客户端 SetStage(12) 进入房间舞台。
    // 记下来: 之后在房间内点商城, 退出时服务端要回 ChangeStage(12) 而不是回大厅。
    c.stage = ClientStage.Room.value
}
