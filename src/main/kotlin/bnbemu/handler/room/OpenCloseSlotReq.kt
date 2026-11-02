package bnbemu.handler.room

import bnbemu.isEN
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.PracticeSetPlayer
import bnbemu.packet.PracticeSlot103
import bnbemu.packet.PracticeSlot104
import bnbemu.packet.PracticeSlot114
import bnbemu.packet.PracticeSlotReady
import bnbemu.packet.room.DoOpenCloseSlot
import bnbemu.server.FLAG_CLOSED
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.server.color
import bnbemu.server.state

// ============================================================================
// 踢出电脑敌人 / 打开关闭槽位 (OpenCloseSlotReq)
// EN: C→S op 89 (0x59), 载荷 1B = E1((slotIdx << 4) | (action & 0x0F))
//     action & 1 == 1: 踢出 AI / 关闭槽位 (FLAG_CLOSED=1)
//       S→C op 106 (PracticeSlotReady, state=1)
//       S→C op 103 (PracticeSlot103, isAI=false)
//     action & 1 == 0: 开放槽位 / 自动加入 AI (FLAG_CLOSED=0)
//       S→C op 106 (state=2), 114, 104, 105, 103 (与进房槽位设置一致)
// TW: C→S op 100 (0x64), 载荷 1B = E1(slotIdx)
//     S→C op 110 (DoOpenCloseSlot), 载荷 1B = E1(slotIdx)
// ============================================================================
val OpenCloseSlotReq = PKTHandler { c ->
    if (isEN) {
        val raw = Decode1
        val slotIdx = (raw shr 4) and 0x0F
        val action = raw and 0x0F
        val isClosing = (action and 1) != 0

        val slot = Room.slots.getOrNull(slotIdx)
        if (slot != null) {
            if (isClosing) {
                // 踢出电脑敌人 / 关闭槽位 (FLAG_CLOSED = 1)
                slot.isAI = false
                slot.user = null
                slot.state = FLAG_CLOSED

                val pkt106 = PracticeSlotReady(slot = slotIdx, state = 1)
                val pkt103 = PracticeSlot103(slot = slotIdx, isAI = false, aiLevel = 0, flag = 0)

                if (Room.uniqueClients.any()) {
                    Room.BC(pkt106, pkt103)
                } else {
                    c.send(pkt106, pkt103)
                }
            } else {
                // 开放槽位 / 自动加入电脑敌人 (FLAG_CLOSED = 0)
                slot.isAI = true
                slot.user = null
                slot.bomber = 5
                slot.color = 7
                if (slot.AILevel !in 1..7) {
                    slot.AILevel = if (slotIdx in 1..7) slotIdx else 1
                }
                slot.state = 0

                val pkt106 = PracticeSlotReady(slot = slotIdx, state = 2)
                val pkt114 = PracticeSlot114(slot = slotIdx)
                val pkt104 = PracticeSlot104(slot = slotIdx)
                val pkt105 = PracticeSetPlayer(slot = slotIdx)
                val pkt103 = PracticeSlot103(slot = slotIdx, isAI = true, aiLevel = slot.AILevel, flag = 1)

                if (Room.uniqueClients.any()) {
                    Room.BC(pkt106, pkt114, pkt104, pkt105, pkt103)
                } else {
                    c.send(pkt106, pkt114, pkt104, pkt105, pkt103)
                }
            }
        }
    } else {
        val slotIdx = Decode1
        val slot = Room.slots.getOrNull(slotIdx) ?: return@PKTHandler

        slot.state = if (slot.state.toInt() and FLAG_CLOSED != 0) 0 else FLAG_CLOSED

        Room BC DoOpenCloseSlot(slotIdx)
    }
}
