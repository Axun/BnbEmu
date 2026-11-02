package bnbemu.packet.room

import bnbemu.netty.PKT

fun AILevelChange(slotIdx: Int, aiLevel: Int) = PKT {
    Encode1(slotIdx)
    Encode2(aiLevel)
}