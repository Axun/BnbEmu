package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C GameOver (Opcode 160 in EN / CA.exe 0x55345D)
// 结构 (1 字节):
//   Decode1: winnerTeam / slotId
fun GameOver(winnerSlotId: Int = 0) = PKT {
    Encode1(winnerSlotId)
}
