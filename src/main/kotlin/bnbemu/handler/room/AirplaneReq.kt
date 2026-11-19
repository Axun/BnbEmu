package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.server.AirplaneItem
import bnbemu.server.Room

// CGameSession::OnRequestAirplane
val AirplaneReq = PKTHandler { c ->
    val battle = Room.battle
    if (battle == null) {
        if (readableBytes() > 0) skipBytes(readableBytes())
        return@PKTHandler
    }

    val count = Decode1

    if (count in 1..3) {
        val airplaneItems = mutableListOf<AirplaneItem>()
        repeat(count) {
            val v1 = Decode1 // 0
            val v2 = Decode1 // 42
            val v3 = Decode1 // 9
            val v4 = Decode1 // 0
            airplaneItems.add(
                AirplaneItem(v1, v2, v3, v4)
            )
        }

        battle.onRequestAirplane(airplaneItems)
    } else {
        error("Strange Request Airplane, nCount=$count")
    }
}