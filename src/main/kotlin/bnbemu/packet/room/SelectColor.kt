package bnbemu.packet.room

import bnbemu.netty.PKT

// ============================================================================
// S→C op 104 — 房间内切换队伍/颜色的广播回显 (C→S op 86 SelectColorReq 的应答)
// ============================================================================
// 2026-11-04 CA.exe 反编译定论 (详见 docs/agents-notes-room.md):
//   * C→S 发包点: 0x4F677B (CSlotStage, lock [esi+0x2f64]=6) 与
//     0x51C69F (CPrepareStage, lock [esi+0x2f78]=6), 两处都是 push 0x56 (86) +
//     Encode1((arg1<<4)|(arg2&0xF)), arg1=槽位, arg2=颜色/队伍值 (实测 0/3/5/6, 循环模式 0..8)。
//   * S→C 收包点: CSlotStage::OnPacket 0x4F480C 的 case 104 → 0x4EFB48;
//     CPrepareStage::OnPacket 0x52A949 的 case 104 → 0x51F196。
//     两者都解码 (slot<<4)|color, 写回玩家 3B meta[2] 的低 5 位、刷新槽位 UI,
//     并把等待锁 6 清零 —— 缺此包房间会永久卡在点击无反应状态。
//   * ⚠️ 旧表把应答绑到 op 108 是错的: 108 的 CPrepareStage case = 0x51EFE0 (SelectMap,
//     8B = E2(result) E2(themeId) E4(mapId)); tst_flow4.txt 里标成 "op=108 SelectColor"
//     的那一包其实是 E2(0) E2(0) E4(172) 的选图回应。
// 包体 (CSlotStage / 练习房、AI 房, 抓包 cap/practice_s2c_op104_3B_*.bin 实为 1B):
//   E1((slot<<4)|color)   —— 例如槽 2 选色 8 → 0x28 → 密文 0x72
// 包体 (CPrepareStage / 普通房): 该 handler 会连读 3 个 Decode1, 多出的 2 字节经
//   CGameContext 0x6873D5 写 per-slot 属性 (0x1e0 + slot*0x1e4 + 0x14, 疑为房主/队伍标志):
//   E1(attr) + E1((slot2<<4)|attr2)；slot2==0xF 时跳过第二个槽位。
//   Decode1 越界返回 0 不抛异常, 故 1B 包在普通房也能解锁, 只是会把这两个属性清成 0。
//   extended=true 时发出完整的 3B 形式, 供日后实现普通房间使用。
fun SelectColor(
    slot: Int,
    color: Int,
    extended: Boolean = false,
    slotAttr: Int = 0,
    secondSlot: Int = 0xF,
    secondAttr: Int = 0
) = PKT {
    Encode1(((slot and 0x0F) shl 4) or (color and 0x0F))
    if (extended) {
        Encode1(slotAttr)
        Encode1(((secondSlot and 0x0F) shl 4) or (secondAttr and 0x0F))
    }
}
