package bnbemu.packet.room

import bnbemu.netty.PKT
import bnbemu.server.Encode
import bnbemu.server.Slot

fun SetSlotState(slotId: Int, slot: Slot) = PKT {
    Encode1(slotId)

    slot.Encode(this)

    Encode1Bool(slot.isAI)
    if (slot.isAI)
        Encode2(slot.AILevel)
}