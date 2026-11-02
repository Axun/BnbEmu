package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.BombKickThrow
import bnbemu.server.Room

// server = CGameSession::OnBombKickThrow
val BombKickThrowReq = PKTHandler { c ->
    val slotId = Decode1
    val bombId = Decode2
    val fromPos =  Decode1

    val targetPos = Decode1
    val moveDuration = Decode2

    Room.getBomb(bombId)?.apply {
        isMoving = true
        expireAt = System.currentTimeMillis() + moveDuration
        Room BC BombKickThrow(slotId, bombId, targetPos)
    }
}