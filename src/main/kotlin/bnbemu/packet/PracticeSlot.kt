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
//   op 103 (7B) = E2(slot+1) E1(1) E2(slot+1) E2(1)   槽 0..6 → 01,02,...07 (槽 7 无此包)
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
fun PracticeSetSlotAI(slot: Int, isAI: Boolean = true, aiLevel: Int = slot, flag: Int = 1) = PKT {
    Encode2(slot)
    Encode1Bool(isAI)
    Encode2(aiLevel)
    Encode2(flag)
}
