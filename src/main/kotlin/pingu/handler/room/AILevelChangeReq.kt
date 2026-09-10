package pingu.handler.room

import pingu.isEN
import pingu.netty.Decode1
import pingu.netty.PKTHandler
import pingu.packet.PracticeSlot103
import pingu.packet.room.AILevelChange
import pingu.packet.room.SetSlotState
import pingu.server.Room

// ============================================================================
// 设置电脑敌人等级 (AILevelChangeReq)
// EN: C→S op 88 (0x58), 载荷 4B = E1(chief/f1) E1(slotIdx) E1(newLevel) E1(flags)
//     S→C op 103 (PracticeSlot103), 载荷 7B = E2(slotIdx) E1Bool(isAI) E2(newLevel) E2(flags)
// ============================================================================
val AILevelChangeReq = PKTHandler { c ->
    val chief = Decode1
    val slotIdx = Decode1
    val newLevel = Decode1
    val flags = Decode1

    val slot = Room.slots.getOrNull(slotIdx)
    if (slot != null) {
        slot.isAI = true
        slot.AILevel = newLevel
    }

    val pkt = PracticeSlot103(slot = slotIdx, isAI = true, aiLevel = newLevel, flag = 0)
    if (Room.uniqueClients.any()) {
        Room BC pkt
    } else {
        c send pkt
    }
}