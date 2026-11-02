package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C BomberDie (Opcode 159 in EN / CA.exe 0x54C378)
// 结构 (1 字节):
//   Decode1: slotId
fun BomberDie(slotId: Int) = PKT {
    Encode1(slotId)
}
