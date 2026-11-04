package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SelectColor
import bnbemu.server.Room
import bnbemu.server.color

// C→S op 86 (EN, CA.exe 0x4F677B / 0x51C69F): E1((slot<<4)|color)
// ⚠️ 客户端发包成功后立即把等待锁置为 6 (CSlotStage [esi+0x2f64] / CPrepareStage [esi+0x2f78]),
//    只有收到 S→C op 104 才会清零并重新启用队伍按钮。旧实现回 op 108 (实为 SelectMap),
//    客户端不会解锁 → 表现为"点颜色没反应且之后一直点不动"。
// EN 客户端的 op 98 (0x4F0634) 是一个带记录列表的上报包, 与颜色无关。
val SelectColorReq = PKTHandler { c ->
    val data = Decode1
    val slotIdx = (data shr 4) and 0x0F
    val colorIdx = data and 0x0F

    if (slotIdx in Room.slots.indices) {
        Room.slots[slotIdx].color = colorIdx
        // 广播给房内所有人 (含请求者, 请求者的等待锁靠本包解除)。
        // 练习房/AI 房走 CSlotStage, 1B 包体与 cap/practice_s2c_op104_3B_*.bin 一致;
        // 若日后接入 CPrepareStage 普通房, 改为 SelectColor(slotIdx, colorIdx, extended = true)。
        Room BC SelectColor(slot = slotIdx, color = colorIdx)
    }
}
