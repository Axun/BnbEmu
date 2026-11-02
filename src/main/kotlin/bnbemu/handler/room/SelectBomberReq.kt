package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SelectBomber
import bnbemu.server.Room
import bnbemu.server.bomber

val SelectBomberReq = PKTHandler { c ->
    val data = Decode1
    val selfIdx = data shr 4
    val bomberIdx = data and 0x0F

    Room.slots[selfIdx].bomber = bomberIdx
    Room BC SelectBomber(data)
}