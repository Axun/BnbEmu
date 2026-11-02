package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C EatObstacleResult (EN op 144 / 0x90, CA.exe 0x0055A65E -> 0x0054A684)
// 载荷 (3 字节):
//   Encode1(slotId)
//   Encode1(gridX)
//   Encode1(gridY)
fun EatObstacleResult(slotId: Int, gridX: Int, gridY: Int) = PKT {
    Encode1(slotId)
    Encode1(gridX)
    Encode1(gridY)
}
