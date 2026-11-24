package bnbemu.handler.room

import bnbemu.netty.Decode2
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.NpcEvent
import bnbemu.server.Room
import org.tinylog.kotlin.Logger

/**
 * C→S op 156: 激战模式 NPC 对话 / 策略事件上报 (CA.exe `0x52D423` 等 4 处发送点)。
 *
 * EN 客户端 op 156 固定发送 6B: `E2(slot) + E2(event) + E2(parameter)`。
 * 在目标槽位为激战模式活跃 NPC (`4..7`) 时广播 S→C op 270 [NpcEvent]。
 */
val NpcEventReq = PKTHandler { c ->
    if (readableBytes() != 6) {
        Logger.warn("[NpcEventReq] 非预期载荷长度=${readableBytes()}B, 跳过消费")
        skipBytes(readableBytes())
        return@PKTHandler
    }

    val slotIdx = Decode2
    val event = Decode2
    val parameter = Decode2

    val room = Room.current ?: return@PKTHandler

    if (!room.isAIBattle) {
        Logger.info("[NpcEventReq] 非激战模式忽略 slot=$slotIdx event=$event param=$parameter")
        return@PKTHandler
    }

    val slot = room.slots.getOrNull(slotIdx)
    if (slotIdx !in 4..7 || slot == null || !slot.isAI) {
        Logger.warn("[NpcEventReq] 非活跃 AI 槽位 slot=$slotIdx event=$event param=$parameter")
        return@PKTHandler
    }

    val pkt = NpcEvent(slot = slotIdx, event = event, parameter = parameter)
    if (room.uniqueClients.any()) {
        room BC pkt
    } else {
        c send pkt
    }
}
