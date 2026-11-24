package bnbemu.handler.room

import bnbemu.domain.CharacterRules
import bnbemu.netty.Decode1
import bnbemu.netty.PKTHandler
import bnbemu.packet.room.SlotBomber
import bnbemu.server.Room
import bnbemu.server.bomber
import bnbemu.service.PlayerService
import bnbemu.service.player

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

    val player = c.player
    val effectiveBomber = CharacterRules.resolveEffectiveBomber(bomberIdx, player)
    // ⚠️ 值没变就不写档案：这个包在实际流程里有两种来源——
    //    ① 玩家点房间里的角色头像（真正的换角，2B = 槽位 + 角色）；
    //    ② 客户端「刷新本机槽位」时自动重发（CA.exe 0x4EF96A 只在目标是本机槽时发，
    //       0x4F69E2 是无条件版本），取的是 0x006E50F0 读出的当前角色。
    //    ② 在**未打补丁**的客户端上取的是 `CUDI_bomber & 7`（25 → 1），
    //    无条件写就会把 char 命令/进阶卡设出来的角色洗回低位角色；打完
    //    tools/patch_char_mask.py 的掩码补丁后它报的是真实 id（0..31），这里只做幂等判断。
    if (player != null && slotIdx == 0 && player.bomber != effectiveBomber) {
        PlayerService.selectBomber(player.userId, effectiveBomber)
    }
    val room = Room.current
    room?.slots?.getOrNull(slotIdx)?.bomber = effectiveBomber

    val pkt = SlotBomber(slotIdx, effectiveBomber)
    if (room != null && room.uniqueClients.any()) {
        room.BC(pkt)
    } else {
        c.send(pkt)
    }
}
