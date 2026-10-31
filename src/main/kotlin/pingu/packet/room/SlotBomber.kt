package pingu.packet.room

import pingu.netty.PKT

// ============================================================================
// S→C op 105 单槽角色 (bomber) — 房间内角色切换的回显
// ============================================================================
// body = E1(slotIdx) + E1(bomberIdx)  (2 字节)
// 依据: cap/practice_s2c_op105_2B_*.bin (= 5a 5f → 槽 0 / bomber 5) 与
//       CA.exe 房间阶段分派器 0x4F480C 的 op 105 分支 (虚表 +0x1A0 → 0x4EFBF6):
//       该处理函数只读两个 Encode1, 应用角色后把等待锁 [0x2f64]==5 清零。
// ⚠️ 与 SetPlayer / PracticeSetPlayer 同为 op 105 但体不同: 那两个是整槽完整资料,
//    本包只带角色, 用作 C→S op 87 (切换角色) 的应答。
fun SlotBomber(slot: Int, bomber: Int) = PKT {
    Encode1(slot)
    Encode1(bomber)
}
