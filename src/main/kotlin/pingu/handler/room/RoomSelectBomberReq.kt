package pingu.handler.room

import pingu.netty.Decode1
import pingu.netty.PKTHandler
import pingu.packet.room.SlotBomber
import pingu.server.Room
import pingu.server.bomber

// ============================================================================
// 房间内切换角色 (C→S op 87) — 点击房间槽位里的角色头像后发出
// ============================================================================
// 载荷 2B = E1(slotIdx) + E1(bomberIdx)
// 实测: 抓包明文 5A 5C → E1(0)=槽位 0, E1(6)=bomber 6。
//
// CA.exe 侧证据 (0x4F67E9, CPrepareStrategyAIBattle 的槽位角色设置):
//   发包后立即 `[esi + 0x2f64] = 5` —— 客户端把该槽位锁成"等待服务器确认"状态,
//   锁未清前 0x4F67F7 的 guard 会直接丢弃后续 op 87。
//   真实服务器的应答 = S→C op 105 (2B = E1(slot) + E1(bomber)), 由房间阶段分派器
//   0x4F480C 的 op 105 分支 (虚表 +0x1A0 → 0x4EFBF6) 处理: 只读两个 Encode1,
//   应用角色后于 0x4EFC96 把 `[+0x2f64] == 5` 清零。
//   ⚠️ 缺这个应答 → 槽位永久卡在等待锁, 后续点击角色无任何反应。
//
// 与 TW 的差异: TW 表里 SelectBomberReq = op 99, 但真实 EN 客户端发的是 op 87;
// op 99 (0x61201C) 在 EN 里另有用途, 故 EN 分支单独绑定。
val RoomSelectBomberReq = PKTHandler { c ->
    val slotIdx = Decode1
    val bomberIdx = Decode1

    Room.slots.getOrNull(slotIdx)?.bomber = bomberIdx

    val pkt = SlotBomber(slotIdx, bomberIdx)
    if (Room.uniqueClients.any()) {
        Room BC pkt
    } else {
        c send pkt
    }
}
