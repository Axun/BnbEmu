package bnbemu.packet.room

import bnbemu.netty.PKT

fun SelectBomber(data: Int) = PKT {
    Encode1(data)
}