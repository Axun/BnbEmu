package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler

// C→S DestroyBoxReq (EN op 126 / 0x7E, CA.exe 0x0054CE36)
// 水泡炸毁可破坏方块时由客户端上报 (5 字节):
//   Decode1: gridX
//   Decode1: gridY
//   Decode1: slotId
//   Decode2: nextBoxState (-1 = 0xFFFF 表示完全炸毁)
// 客户端在 0x00476F31 / 0x0047739B 内已完成本地消块与爆出隐藏道具 (0x00471F4B), 服务端消费即可。
val DestroyBoxReq = PKTHandler { c ->
    val gridX = Decode1
    val gridY = Decode1
    val slotId = Decode1
    val nextBoxState = Decode2
}
