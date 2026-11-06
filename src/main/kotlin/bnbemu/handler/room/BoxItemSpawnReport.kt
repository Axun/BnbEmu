package bnbemu.handler.room

import bnbemu.debugMode
import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

// C→S BoxItemSpawnReport (EN op 154 / 0x9A)
// 逆向定位:
//   CA.exe 0x0053DD81 / 0x0053DDD1 / 0x00461399 / 0x0046D5C3 / 0x0046D986 / 0x0046D9F8 / 0x0046DB1D
//   客户端在多种状态下复用 op 154 进行事件/状态上报:
//   1. action = 5 (0x0046DB1D CGOGameBomber::OnDie): 角色死亡上报 (4 字节)
//      Decode2(slotId) + Decode2(action = 5)
//   2. action = 4 (0x0053DDD1): 水泡爆炸炸开箱子生成地面道具列表上报 (变长)
//      Decode2(subType = 0) + Decode2(action = 4) + Decode2(slotId) + Decode2(count)
//      + count * [Decode2(itemSeq) + Decode2(gridY) + Decode2(gridX) + Decode1(flag)]
//   3. action = 1 (0x0046D5C3 / 0x0053DD81): 炸弹/实体状态同步 (8 字节)
//      Decode2(slotId) + Decode2(action = 1) + Decode4(token)
//   4. action = 2 / 3 (0x0046D986 / 0x0046D9F8): 角色受困(水泡中)/被救状态变更 (4 字节)
//      Decode2(slotId) + Decode2(action = 2/3)
//   5. action = 0 (0x00461399): 角色使用道具脱困 (如自救针 Item/Niddle, 8 字节)
//      Decode2(slotId) + Decode2(action = 0) + Decode2(arg1) + Decode2(arg2)
val BoxItemSpawnReport = PKTHandler { c ->
    if (readableBytes() < 4) {
        skipBytes(readableBytes())
        return@PKTHandler
    }
    val first = Decode2
    val action = Decode2
    // 诊断用: 阵亡音效 / 被困音效都依赖这里的 action (见 docs/agents-notes-battle.md)。
    // 目前实战只见过 1 / 4 / 0; 2(被困) 3(被救) 5(阵亡) 对 AI 一律不发, 打日志便于验证。
    if (debugMode) println("[op154] slot=$first action=$action 剩余=${readableBytes()}B")
    when (action) {
        5 -> {
            // 角色死亡上报: first 为 slotId
            val slotId = first
            Room.onBomberDie(slotId)
        }
        4 -> {
            val slotId = Decode2
            val count = Decode2
            repeat(count.coerceIn(0, 64)) {
                val itemSeq = Decode2
                val gridY = Decode2
                val gridX = Decode2
                val flag = Decode1
            }
        }
        1 -> {
            val token = Decode4
        }
        2, 3 -> {
            // 角色受困 / 被救状态同步，仅 4 字节 payload，已读取完毕
        }
        0 -> {
            val arg1 = Decode2
            val arg2 = Decode2
        }
        else -> {
            // 防御式读取剩余字节，防止未预期的变体引发 IndexOutOfBoundsException 导致断线
            if (readableBytes() >= 4) {
                val arg1 = Decode2
                val arg2 = Decode2
            }
        }
    }
    if (readableBytes() > 0) {
        skipBytes(readableBytes())
    }
}
