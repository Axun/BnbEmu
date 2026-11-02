package bnbemu.packet.room

import bnbemu.netty.PKT

// AI 道具抛掷/生成广播 (op 172, 练习房/对局进行中周期性广播) — tst.pcap stream4 逆向
// body (15B) = E2(176) E2(29) E1(1) E4(位置) E1(1) E1(0) E2(0) E1(0) E1(flag)
//   E2(176)  道具类型标识 (13 样本恒 176)
//   E2(29)   道具 id (13 样本恒 29, 丢的某种道具)
//   E1(1)    恒 1
//   E4(pos)  目标位置/格子 (13 样本逐次变化: 0x191,0x321,0x129,...)
//   E1(1)    恒 1
//   E1(0)    恒 0
//   E2(0)    恒 0
//   E1(0)    恒 0
//   E1(flag) 变化 (0/1/2/3, 疑似朝向/方向)
// 该包由 Room.aiTick() 周期性广播, 让电脑敌人"自己动起来"。
// ⚠️ EN 真实 opcode = 172 (抓包 sq=343~400 逐包 CRC8 验证), 不是局内分发器的 +200 偏移系列;
//    局内 +200 偏移仅适用于炸弹/角色事件/特效等"状态同步"包 (见 AGENTS.md 局内 opcode 节)。
fun ItemThrow(pos: Int, flag: Int) = PKT {
    Encode2(176)
    Encode2(29)
    Encode1(1)
    Encode4(pos)
    Encode1(1)
    Encode1(0)
    Encode2(0)
    Encode1(0)
    Encode1(flag)
}
