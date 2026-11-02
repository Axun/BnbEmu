package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.Decode4
import bnbemu.netty.DecodeStr
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.EatItemResult
import bnbemu.server.Room

// C→S EatItemReq (EN op 110 / 0x6E, CA.exe 0x00539B63)
// 玩家或 AI 踩到地面道具时发送:
//   Decode1: slotId   (0..7)
//   Decode1: gridX    (0..14)
//   Decode1: gridY    (0..12)
//   Decode2: itemSeq  (地图道具实例唯一序号, 用于 CGameMsgCache::0x00539313 匹配出队)
//   Decode1: itemType (道具类型 ID, 如 0=水泡, 1=溜冰鞋, 2=药水, 7=红魔鬼, 25/41/76=金币袋等)
//   Decode4: arg1     (0)
//   Decode4: arg2     (0)
//   Decode4: arg3     (0)
//   DecodeStr: extra  (空串 E2(0))
// 服务端必须广播 S→C op 143 EatItemResult (CA.exe 0x0054BE87), 客户端才真正拾取道具并应用属性。
val EatItemReq = PKTHandler { c ->
    val slotId = Decode1
    val gridX = Decode1
    val gridY = Decode1
    val itemSeq = Decode2
    val itemType = Decode1
    val arg1 = Decode4
    val arg2 = Decode4
    val arg3 = Decode4
    val extra = DecodeStr
    Room BC EatItemResult(slotId, gridX, gridY, itemSeq, itemType)
}
