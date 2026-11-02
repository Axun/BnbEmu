package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler

// C→S BoxItemSpawnReport (EN op 154 / 0x9A, CA.exe 0x0053DD08 / 0x00461399 / 0x0046D5C3)
// 历史曾误判为 BomberDieReq。客户端在三处发送 op 154:
//   1. 0x0053DDD1 (水泡爆炸炸开箱子生成地面道具列表上报, action = 4):
//      Decode2(subType = 0) + Decode2(action = 4) + Decode2(slotId) + Decode2(count)
//      + count * [Decode2(itemSeq) + Decode2(gridY) + Decode2(gridX) + Decode1(flag)]
//   2. 0x0046D5C3 (炸弹/实体状态同步, action = 1):
//      Decode2(slotId) + Decode2(action = 1) + Decode4(token)
//   3. 0x00461399 (角色状态/道具脱困如自救针 Item/Niddle, action = 0):
//      Decode2(slotId) + Decode2(action = 0) + Decode2(arg1) + Decode2(arg2)
val BoxItemSpawnReport = PKTHandler { c ->
    val first = Decode2
    val action = Decode2
    when (action) {
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
        else -> {
            val arg1 = Decode2
            val arg2 = Decode2
        }
    }
}
