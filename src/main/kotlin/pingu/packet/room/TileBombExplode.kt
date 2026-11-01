package pingu.packet.room

import pingu.netty.PKT

// S→C TileBombExplode (Opcode 148 in EN / CA.exe 0x54BA1A)
// 结构 (3 字节):
//   Encode1: ((isSpecial << 6) | (slotId & 0x0F))
//   Encode1: x (0..14)
//   Encode1: y (0..12)
fun TileBombExplode(slotId: Int, x: Int, y: Int, isSpecial: Boolean = false) = PKT {
    val flags = (if (isSpecial) 0x40 else 0) or (slotId and 0x0F)
    Encode1(flags)
    Encode1(x)
    Encode1(y)
}
