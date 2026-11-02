package bnbemu.packet.room

import bnbemu.netty.PKT

fun StartGameResult(res: Int) = PKT {
    Encode1(res)
}