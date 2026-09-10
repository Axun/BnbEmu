package pingu.packet.room

import pingu.netty.PKT

fun DoOpenCloseSlot(slotIdx: Int) = PKT {
    Encode1(slotIdx)
}
