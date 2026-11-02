package bnbemu.packet.room

import bnbemu.netty.PKT

fun SelectColor(data: Int) = PKT {
    Encode1(data)
}