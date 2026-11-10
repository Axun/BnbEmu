package bnbemu.handler.room

import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SlotBomber
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.server.setBomber

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
// 角色进阶卡系统 (Skin / Evolution):
//   当玩家在选角列表点击进阶角色时, 客户端通过 0x006B424B (GetBaseCharacterBySkin)
//   查表将进阶角色转换回基础角色后上报 (如蓝妹妹 10 转换为基础角色 4 蓝蓝 Marid)。
//   服务端在应答 op 105 时, 若玩家已装备进阶卡 (Item 2082 对应角色 4 进阶为 10),
//   必须将槽位 bomber 设为 10 并回发 10, 否则客户端无法触发 0x4ef7fd -> 0x4731c4
//   的高级角色精灵加载逻辑, 导致槽位与出战角色回退为普通基础角色。
//
val SelectBomberReq = PKTHandler { c ->
    val slotIdx = Decode1
    val bomberIdx = Decode1

    val effectiveBomber = if (bomberIdx == 4) 10 else bomberIdx

    val user = c.users.firstOrNull()
    if (user != null && slotIdx == 0) {
        user.setBomber(effectiveBomber)
    }
    Room.slots.getOrNull(slotIdx)?.bomber = effectiveBomber

    val pkt = SlotBomber(slotIdx, effectiveBomber)
    if (Room.uniqueClients.any()) {
        Room.BC(pkt)
    } else {
        c.send(pkt)
    }
}
