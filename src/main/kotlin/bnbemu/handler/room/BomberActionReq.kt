package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// 局内角色操作/碰撞交互请求 (C→S op 112 / 0x70)
// 逆向定位:
//   CA.exe 0x0054C8B3 / 0x0054D1F6 / 0x0054EAE2 / 0x00650065 / 0x0054A812
//   客户端在角色移动、使用道具 (Action 0x20)、触碰/踢水泡 (Action 0x40)、
//   以及"只剩一支队伍存活" (Action 0x60) 时发送。
// 基础结构:
//   Decode1(actionSlot): 高 4 位为 action (0x40 碰/踢水泡, 0x20 道具, 0x60 只剩一队, 0x30/0x70/0x80/0xB0/0xD0 移动与动作)
//                        低 4 位为 slotId
//   Decode2(posX): 角色像素 X 坐标
//   Decode2(posY): 角色像素 Y 坐标
// 扩展字段 (Action 0x40 交互实体列表):
//   Decode1(param) + Decode1(count) + count * [Decode1(gridY) + Decode1(gridX) + Decode2(bombId)]
//
// ⚠️ Action 0x60 = **对局结束信号**（不是普通动作），详见 bnbemu.server.Room.onLastTeamStanding:
//   客户端帧更新 0x54B380~0x54B450 每帧统计 9 个队伍桶 (bomber+0x84),
//   非空队伍数 == 1 时对每个存活 bomber 发 E1(0x60|slot)+E2(x)+E2(y)，
//   然后**每帧重发**直到服务端下发 op160/169/150 —— 实测杀光 AI 后客户端会连发数百条，
//   且其它局内包全部停摆。所以这里是"玩家赢"的唯一可达信号。
val BomberActionReq = PKTHandler { c ->
    if (readableBytes() < 5) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val actionSlot = Decode1
    val slotId = actionSlot and 0x0F
    val action = actionSlot and 0xF0
    val posX = Decode2
    val posY = Decode2

    when (action) {
        // "只剩一支队伍" 上报 → 结算。slotId 是存活方, 决定玩家胜还是 AI 胜。
        // 客户端每帧重发, Room 内部用 gameOverSent 去重。
        0x60 -> Room.onLastTeamStanding(slotId)

        0x40 -> if (readableBytes() >= 2) {
            val param = Decode1
            val count = Decode1
            repeat(count.coerceIn(0, 16)) {
                if (readableBytes() >= 4) {
                    val gridY = Decode1
                    val gridX = Decode1
                    val bombId = Decode2
                }
            }
        }
    }
    // 消费所有剩余字节，防止未预期的扩展导致越界
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
