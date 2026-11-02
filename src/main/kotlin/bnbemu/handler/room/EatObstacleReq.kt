package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.EatObstacleResult
import bnbemu.server.Room

// C→S EatObstacleReq (EN op 111 / 0x6F, CA.exe 0x00539C38)
// 玩家或 AI 踩到地面障碍物 (香蕉皮/图钉/地雷/陷阱) 时发送 (3 字节):
//   Decode1: slotId
//   Decode1: gridX
//   Decode1: gridY
// 服务端回显 S→C op 144 EatObstacleResult (CA.exe 0x0054A684) 触发障碍物效果并移除该障碍物。
val EatObstacleReq = PKTHandler { c ->
    val slotId = Decode1
    val gridX = Decode1
    val gridY = Decode1
    Room BC EatObstacleResult(slotId, gridX, gridY)
}
