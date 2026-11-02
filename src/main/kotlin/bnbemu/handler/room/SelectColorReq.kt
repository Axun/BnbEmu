package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SelectColor
import bnbemu.server.Room
import bnbemu.server.color

val SelectColorReq = PKTHandler { c ->
    val data = Decode1
    val selfIdx = data shr 4
    val colorIdx = data and 0x0F

    Room.slots[selfIdx].color = colorIdx
    Room BC SelectColor(data)
}