package bnbemu.packet.room

import bnbemu.netty.PKT

fun BombKickThrow(slotId: Int, bombId: Int, targetPos: Int) = PKT {
    Encode1(slotId)
    Encode2(bombId)
    Encode1(targetPos)
}