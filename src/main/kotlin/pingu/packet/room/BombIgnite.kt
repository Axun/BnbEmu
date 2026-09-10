package pingu.packet.room

import pingu.isEN
import pingu.isJP
import pingu.netty.PKT

// S→C BombIgnite / DetonateBomb (Opcode 141 in EN / Opcode 352 in TW_5)
// EN 结构 (CA.exe 0x54BD14):
//   Decode1: ((action << 4) | (slotId & 0x0F)) (action: 0 = 生成, 4 = 引爆)
//   Decode2: bombId
//   Decode1: old_x
//   Decode1: old_y / bombAttr
//   Decode1: isSpecial / unk
//   Decode1: x (放置的目标 X 坐标)
//   Decode1: y (放置的目标 Y 坐标)
fun BombIgnite(slotId: Int, x: Int, y: Int, bombId: Int, unk: Int = 0, action: Int = 0) = PKT {
    Encode1((action shl 4) or (slotId and 0x0F))
    Encode2(bombId)
    Encode1(x)
    Encode1(y)
    Encode1(unk)
    Encode1(x)
    Encode1(y)
}