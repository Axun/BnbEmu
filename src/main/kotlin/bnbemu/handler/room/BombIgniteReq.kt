package bnbemu.handler.room

import bnbemu.isJP
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.Room

val BombIgniteReq = PKTHandler { c ->
    val slotId = Decode1 // 1
    val unk = if (isJP) Decode1 else 0
    val pos = Decode1
    val bombAttr = Decode1
    val x = pos shr 4
    val y = pos and 0x0F
    val isSpecial = ((bombAttr shr 6) and 1) == 1

    Room.addBomb(slotId, x, y, isSpecial, unk)
}