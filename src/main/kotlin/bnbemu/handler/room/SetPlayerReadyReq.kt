package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.Decode4
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SetPlayerReadyResult
import bnbemu.server.FLAG_CHIEF
import bnbemu.server.FLAG_READY
import bnbemu.server.Room
import bnbemu.server.state
import bnbemu.service.player

// ============================================================================
// 玩家准备 / 取消准备请求 (C→S op 84 SetPlayerReadyReq)
//
// CA.exe 发包点：
//   - CPrepareStage::sub_51F8FC (5B): E1(ready: 0=取消/1=准备) + E4(u32)
//     如调试日志实测：5A 96 CA 53 95 -> ready=0, u32=0
//   - CSlotStage::sub_4EFDBF (1B): E1(ready)
//
// 发包后客户端立刻将等待锁置为 4 ([this+3038] / [this+3033]) 并禁用按钮，
// 服务端必须广播 S→C op 102 (SetPlayerReadyResult) 才可解锁。
// ============================================================================
val SetPlayerReadyReq = PKTHandler { c ->
    val ready = Decode1 != 0
    if (readableBytes() >= 4) Decode4
    if (readableBytes() > 0) skipBytes(readableBytes())

    val player = c.player
    val slotIdx = if (player != null) {
        Room.slots.indexOfFirst { it.client === c || it.playerId == player.userId }
            .takeIf { it >= 0 } ?: 0
    } else {
        0
    }

    val slot = Room.slots[slotIdx]
    val prevState = slot.state.toInt() and 0xFF
    // 房主位 (FLAG_CHIEF) 在客户端 sub_524EA0 约定下绝不保持 FLAG_READY (0x04)
    slot.state = if (ready && (prevState and FLAG_CHIEF) == 0) {
        prevState or FLAG_READY
    } else {
        prevState and FLAG_READY.inv()
    }

    val pkt = SetPlayerReadyResult(slotId = slotIdx, slot = slot)
    if (Room.uniqueClients.any()) {
        Room.BC(pkt)
    } else {
        c.send(pkt)
    }
}
