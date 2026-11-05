package bnbemu.packet.room

import bnbemu.netty.PKT

// S→C ReviveBomber (Opcode 160 in EN / CA.exe 0x55345D)
//
//   分发表 0x55A6A2 里 0xA0 → 0x55A719 → call 0x55345D，而 0x55345D 做的事是"复活某个 bomber":
//     · Decode1(slot) → 0x5347EE(session, slot) → 0x533D5C 按槽位取 bomber
//     · 0x5347EE: vtable+0xC(1) 置活 → 0x4606C2 清陷阱/水泡、vtable+0x5C(0x2B) 置状态 43（复活动作）
//     · 用字符串表 key "RebirthToGhost"（0x8A4CD0；fx.idd 文案 "%s has been resurrected!"）拼出
//       "XXX has been resurrected!" → 0x4E3CBE → vtable+0x130(0, msg) 打到聊天/公告栏
//     · 遍历 UI 观察者 [this+0x3BF8] 回调 vtable+0x38(slot)，让头顶名字/队伍板恢复
//   旁证：另一处 key "SomeoneResurecction"（0x8A8558，0x57BE4x 的救援币路径）打的是同一句文案。
//
// 结构 (1 字节):
//   Decode1: slotId —— 要复活的 bomber 槽位 (0 = 玩家, 1..7 = AI)，不是队伍号
//
// 用途：给"复活币"类道具用（服务端把某个已阵亡的槽位拉回场上时下发）。
fun ReviveBomber(slotId: Int = 0) = PKT {
    Encode1(slotId)
}
