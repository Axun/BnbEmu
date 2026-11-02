package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C BomberTrapped (Opcode 156 in EN / CA.exe 0x54987D)
// 结构 (3 字节):
//   Decode1: trapType
//   Decode1: slotId
//   Decode1: flags
fun BomberTrapped(slotId: Int, trapType: Int = 0, flags: Int = 0) = PKT {
    Encode1(trapType)
    Encode1(slotId)
    Encode1(flags)
}
