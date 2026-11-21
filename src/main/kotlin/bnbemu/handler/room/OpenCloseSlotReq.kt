package bnbemu.handler.room

import bnbemu.domain.CharacterType
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.PracticeSetPlayer
import bnbemu.packet.room.PracticeSetSlotAI
import bnbemu.packet.room.PracticeSlotReady
import bnbemu.packet.room.SetSlotColor
import bnbemu.packet.room.SetSlotFanfare
import bnbemu.packet.room.DoOpenCloseSlot
import bnbemu.server.DEFAULT_AI_LEVEL
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
//       S→C op 103 (PracticeSetSlotAI, isAI=false, refSlot=0, aiLevel=0)
//     action & 1 == 0: 开放槽位 / 自动加入 AI (FLAG_CLOSED=0)
//       S→C op 106 (state=2), 114, 104, 105, 103 (与进房槽位设置一致)
// ⚠️ op 103 字段 = E2(slot) + E1Bool(isAI) + E2(refSlot≤7) + E2(aiLevel 0..4)：
//    第 3 字段是槽位引用、第 4 字段才是难度档
// ============================================================================
val OpenCloseSlotReq = PKTHandler { c ->
    val raw = Decode1
    val slotIdx = (raw shr 4) and 0x0F
    val action = raw and 0x0F
    val isClosing = (action and 1) != 0

    val slot = Room.slots.getOrNull(slotIdx)
    if (slot != null) {
        if (Room.isAIBattle && slotIdx in 0..3) {
            // 激战模式 0..3 是真人玩家槽位（支持房主开放等待其他玩家加入或关闭，但绝不自动填 AI）。
            // 必须回 S→C op 106 才能清掉 CPrepareStage[+3038]==2 的等待锁，否则客户端会卡死。
            if (slot.player != null) {
                val pkt106 = PracticeSlotReady(slot = slotIdx, state = 2)
                if (Room.uniqueClients.any()) Room.BC(pkt106) else c.send(pkt106)
                return@PKTHandler
            }

            slot.isAI = false
            slot.clearHuman()
            slot.state = if (isClosing) FLAG_CLOSED else 0
            val pkt106 = PracticeSlotReady(slot = slotIdx, state = if (isClosing) 1 else 2)
            if (Room.uniqueClients.any()) {
                Room BC pkt106
            } else {
                c send pkt106
            }
            return@PKTHandler
        }
        if (isClosing) {
            // 踢出电脑敌人 / 关闭槽位 (FLAG_CLOSED = 1)
            slot.isAI = false
            slot.clearHuman()
            slot.state = FLAG_CLOSED

            val pkt106 = PracticeSlotReady(slot = slotIdx, state = 1)
            val pkt103 = PracticeSetSlotAI(slot = slotIdx, isAI = false, refSlot = 0, aiLevel = 0)

            if (Room.uniqueClients.any()) {
                Room.BC(pkt106, pkt103)
            } else {
                c.send(pkt106, pkt103)
            }
        } else {
            // 开放槽位 / 自动加入电脑敌人 (FLAG_CLOSED = 0)
            slot.isAI = true
            slot.clearHuman()
            val bomberId = if (Room.isAIBattle) {
                val prev = slot.bomber.toInt()
                if (Room.isBattleNpcBomber(prev)) prev else Room.npcDefaultBomberForSlot(slotIdx)
            } else {
                CharacterType.Boz.value
            }
            slot.bomber = bomberId
            slot.color = 7
            // AILevel 是难度档 0..4 (抓包默认 1)，不是槽号/角色 id；
            if (slot.aILevel !in 0..4) {
                slot.aILevel = DEFAULT_AI_LEVEL
            }
            slot.state = 0

            val refSlot = if (Room.isAIBattle) Room.chiefSlotIndex() else slotIdx
            val pkt106 = PracticeSlotReady(slot = slotIdx, state = 2)
            val pkt114 = SetSlotFanfare(slot = slotIdx, bomber = bomberId)
            val pkt104 = SetSlotColor(slot = slotIdx)
            val pkt105 = PracticeSetPlayer(slot = slotIdx, bomber = bomberId)
            val pkt103 = PracticeSetSlotAI(slot = slotIdx, isAI = true, refSlot = refSlot, aiLevel = slot.aILevel)

            if (Room.uniqueClients.any()) {
                Room.BC(pkt106, pkt114, pkt104, pkt105, pkt103)
            } else {
                c.send(pkt106, pkt114, pkt104, pkt105, pkt103)
            }
        }
    }
}
