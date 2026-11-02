package bnbemu.handler.room

import bnbemu.isJP
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler

import bnbemu.packet.room.EatItemResult
import bnbemu.packet.room.EatItemResult_JP
import bnbemu.server.Room

// server = CGameSession::OnRequestEatItem
val EatItemReq = PKTHandler { c ->
    val slotId = Decode1
    val ItemID = Decode1
    val ItemType = Decode1

    if (isJP) {
        val v4 = Decode1
        Room BC EatItemResult_JP(slotId, ItemID, ItemType, v4)
    } else {
        Room BC EatItemResult(slotId, ItemID, ItemType)
    }
}