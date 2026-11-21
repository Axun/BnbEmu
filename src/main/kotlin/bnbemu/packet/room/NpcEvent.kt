package bnbemu.packet.room

import bnbemu.netty.PKT

/**
 * S→C op 270: 激战模式 NPC 对话/策略事件回显 (CA.exe `0x52D1EC`)。
 * 载荷 6B: `E2(slot) + E2(event) + E2(parameter)`。
 */
fun NpcEvent(
    slot: Int,
    event: Int,
    parameter: Int = 0,
) = PKT {
    Encode2(slot)
    Encode2(event)
    Encode2(parameter)
}

/**
 * S→C op 271: 激战模式 NPC 解锁级别下发 (CA.exe `0x52D23F`)。
 * `index=0, unlockLevel=2` 时解锁槽位下拉里的 NPC 22 / 23。
 */
fun NpcAvailability(
    index: Int = 0,
    unlockLevel: Int = 2,
) = PKT {
    Encode1(index)
    Encode1(unlockLevel)
}
