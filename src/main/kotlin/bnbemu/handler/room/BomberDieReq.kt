package bnbemu.handler.room

import bnbemu.isEN
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.BomberDie
import bnbemu.packet.room.BomberTrapped
import bnbemu.server.Room

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
