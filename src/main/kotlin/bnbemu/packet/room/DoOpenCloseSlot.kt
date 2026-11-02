package bnbemu.packet.room

import bnbemu.netty.PKT

fun DoOpenCloseSlot(slotIdx: Int) = PKT {
    Encode1(slotIdx)
}
