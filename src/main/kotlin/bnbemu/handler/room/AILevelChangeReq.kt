package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.PracticeSetSlotAI
import bnbemu.packet.room.SlotBomber
import bnbemu.server.Room
import bnbemu.server.bomber
import org.tinylog.kotlin.Logger

// ============================================================================
// 设置电脑敌人等级 / 角色 (AILevelChangeReq)
// EN: C→S op 88 (0x58), 载荷 4B =
//       E1(chiefSlot) + E1(slotIdx) + E1(charId) + E1(aiLevel)
//     S→C op 103 (PracticeSetSlotAI), 载荷 7B =
//       E2(slotIdx) + E1Bool(isAI) + E2(refSlot) + E2(aiLevel)
//
// 字段语义由 CA.exe 反汇编钉死 (2026-11-10, 完整地址链见 packet/PracticeSlot.kt):
//   · 第 1 字段 = 0x688446 取的本机玩家序号 (flag & 2)；第 2 字段 = 目标槽位。
//   · 第 3 字段 charId = 槽位控件的 AI 角色/头像 id (0x4D5F18, 13=随机)，
//     或 NPC 选择器 0x51B158 的选中值 (20..23)。旧实现把它当 newLevel
//     存进 slot.AILevel，是错的；角色切换另由 op 87 SelectBomberReq 处理。
//   · 第 4 字段 aiLevel = 难度档绝对值 0..4。客户端 0x52B8FB 从 0x686A4C 读
//     槽位 +8 得当前等级，点 + 则 inc、点 − 则 dec (0x52B909 / 0x52B90E)，
//     夹到 0..4 后发出。默认等级 1 ⇒ 减发 0、加发 2，
//     这就是「0=减少 / 2=增加」错觉的成因：两个值都是结果档位，不是方向位。
//   · 服务端必须回 op 103 才能清 [esi+0x3250] 等待锁；op 103 的第 3 字段被
//     0x52B3E8 `cmp esi,7 / ja 0x52B47D` 限定为 ≤7，因此回显时填目标槽位号，
// ============================================================================
val AILevelChangeReq = PKTHandler { c ->
    val chiefSlot = Decode1     // 0x688446 本机玩家序号
    val slotIdx = Decode1
    val charId = Decode1        // NPC 选择器选中的角色 id (20..23) 或当前角色 id
    val aiLevel = Decode1       // AI 难度档 0..4
    val level = aiLevel.coerceIn(0, 4)

    val slot = Room.slots.getOrNull(slotIdx)
    if (Room.isAIBattle) {  // 激战模式可以设置电脑的角色类型
        if (slotIdx !in 4..7 || slot == null) {
            Logger.warn("[AILevelChangeReq] 激战模式忽略越界/真人槽 slot=$slotIdx")
            return@PKTHandler
        }
        val refSlot = chiefSlot.takeIf { it in 0..3 && Room.slots[it].user != null }
            ?: Room.chiefSlotIndex()
        slot.isAI = true
        slot.user = null
        slot.aILevel = level
        if (Room.isBattleNpcBomber(charId) || charId in 0..15) {
            slot.bomber = charId
        }
        val effectiveBomber = slot.bomber.toInt()
        Logger.info("激战模式设置AI槽位${slotIdx} 角色=${effectiveBomber} 难度=${level} refSlot=${refSlot}")

        val pktBomber = SlotBomber(slotIdx, effectiveBomber)
        val pktAi = PracticeSetSlotAI(slot = slotIdx, isAI = true, refSlot = refSlot, aiLevel = level)
        if (Room.uniqueClients.any()) {
            Room.BC(pktBomber, pktAi)
        } else {
            c.send(pktBomber, pktAi)
        }
        return@PKTHandler
    }

    if (slot != null) {
        slot.isAI = true
        slot.aILevel = level
    }
    Logger.info("设置AI槽位${slotIdx}等级到${level}")

    val pkt = PracticeSetSlotAI(slot = slotIdx, isAI = true, refSlot = slotIdx, aiLevel = level)
    if (Room.uniqueClients.any()) {
        Room BC pkt
    } else {
        c send pkt
    }
}