package pingu.handler.room

import pingu.isEN
import pingu.netty.Decode2
import pingu.netty.PKTHandler
import pingu.packet.room.BomberDie
import pingu.packet.room.BomberTrapped
import pingu.server.Room

// C→S BomberDieReq / BomberTrappedReq (Opcode 154 in EN / CA.exe 0x00461399)
// EN 载荷 (8 字节):
//   Decode2: slotId
//   Decode2: killer
//   Decode2: posX
//   Decode2: posY
val BomberDieReq = PKTHandler { c ->
    val slotId = Decode2
    val killer = Decode2
    val posX = Decode2
    val posY = Decode2

    if (isEN) {
        Room.BC(BomberTrapped(slotId))
        Room.BC(BomberDie(slotId))
    }
}
