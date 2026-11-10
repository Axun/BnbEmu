package bnbemu.packet

import bnbemu.netty.PKT
import bnbemu.server.Room
import bnbemu.server.bomber

// ============================================================================
// 进练习房 (op 75) 回应链中的逐槽位包 — 按 tst.pcap stream4 真实字节逆向出的公式
// ============================================================================
// 权威线序 (seq): 先 [108, 106×8], 然后对槽位 0..7 各发一组 [114, 104, 105],
//   其中槽位 0..6 再各带一个 op 103 (槽位 7 无 op 103)。
// 各 op 的 body 公式 (已用 tst.pcap 样本逐字节验证):
//   op 106 (1B) = E1((slot<<4) | 2)                   槽 0..7 → 02,12,22,32,42,52,62,72
//   op 104 (1B) = E1(if slot==0 0 else (slot<<4)|8)   槽 0→00, 1..7→18,28,38,48,58,68,78
//   op 105 (2B) = E1(slot) E1(bomber)                 (默认取当前槽位/个人信息中实际角色)
//   op 114 (6B) = E1(slot) E1(5) E1(5) E1(0) E2(0)
//   op 103 (7B) = E2(slotIdx) E1(isAI) E2(refSlot) E2(aiLevel)   槽 0..6 → 01,02,...07 (槽 7 无此包)
// 这些公式是"服务器把 8 个 AI 槽位 + 玩家槽位填进房间"的结构化编码。

// ---- op 106 单槽 1B: (slot<<4)|state ----
fun PracticeSlotReady(slot: Int, state: Int = 2) = PKT {
    Encode1((slot shl 4) or (state and 0x0F))
}

// ---- op 104 单槽 1B: slot0→0, 其余 (slot<<4)|8 ----
fun PracticeSlot104(slot: Int) = PKT {
    Encode1(if (slot == 0) 0 else (slot shl 4) or 8)
}

// ---- op 105 单槽 2B: E1(slot) E1(bomber) ----
fun PracticeSetPlayer(
    slot: Int,
    bomber: Int = Room.slots.getOrNull(slot)?.bomber?.toInt() ?: (if (slot == 0) 0 else 5)
) = PKT {
    Encode1(slot)
    Encode1(bomber)
}

// ---- op 114 单槽 6B ----
fun PracticeSlot114(slot: Int) = PKT {
    Encode1(slot)
    Encode1(5)
    Encode1(5)
    Encode1(0)
    Encode2(0)
}

// ---- op 103 单槽 7B: 设置/移除槽位 AI 敌人 (isAI=false 即踢出该槽 AI) ----
// 字段 = E2(slotIdx) + E1Bool(isAI) + E2(refSlot) + E2(aiLevel)，不是「等级 + flags」。
//
// CA.exe 依据 (2026-11-10 capstone 复核 reference/CA.exe.c):
//   · 三个等价接收点 0x52B39A / 0x52DDB7 / 0x597192 都把第 4 个 Decode2 结果作为
//     a3 传给 0x685E9B，写成全局槽位结构 +8；0x686A4C 再读 +8 当作「当前 AI 等级」。
//     所以第 4 字段是 AI 难度档的绝对值 (0..4)，不是增减方向。
//   · 0x59973E 用 0..4 索引每个 NPC 的 5 档 AI 表 (+36*a3)；0x599D3F 在 >2 时
//     给 Shield/Needle、=4 再加 Spring；0x686B57 把 0..4 映射为 3/10/52/109/163。
//   · 第 3 字段在 0x52B3E8 被 `cmp esi,7 / ja 0x52B47D` 限定为 0..7，然后交给
//     0x687CBB(GetPlayerInfo_Plus32) 查玩家信息；超范围会提前 return，
//     连 0x52B476 的 `and [esi+0x3250],0` 解锁都不执行 → 房间界面卡等待锁。
//   · C→S op 88 的 0x52B8B4 从 0x686A4C 取当前等级，点 + 则 inc、点 − 则 dec，
//     夹到 0..4 后写进第 4 个 E1。默认等级 1 ⇒ 减发 0、加发 2，
//
// refSlot / aiLevel 在此统一夹到客户端可接受范围，避免上层状态异常把房间卡死。
// isAI=false 时客户端 0x52B450 直接走 0x685ED9 移除槽位、不读后两字段，
// 抓包基准同样是 0，所以这里统一归零，调用方不必再显式传 0。
fun PracticeSetSlotAI(
    slot: Int,
    isAI: Boolean = true,
    refSlot: Int = slot,
    aiLevel: Int = 1,
) = PKT {
    Encode2(slot)
    Encode1Bool(isAI)
    if (isAI) {
        Encode2(refSlot.coerceIn(0, 7))
        Encode2(aiLevel.coerceIn(0, 4))
    } else {
        Encode2(0)
        Encode2(0)
    }
}
