package pingu.packet

import pingu.netty.PKT
import pingu.netty.SendPacketBase

// ============================================================================
// 用户背包与角色进阶卡系统 (S→C op 320) — CA.exe 0x0050375D 分发至 0x004FCCBF
// ============================================================================
// 1. 分发点与 Opcode 修正 (op 320 vs op 264):
//    - 原先将背包物品下发配置为 op 264 存在两处偏差:
//      S→C op 264 (0x005031BB -> 0x004F8A49 -> 0x00671136 -> 0x0065AE2C) 仅读取问号/神秘
//      道具列表写入 [esi + 0x10290]，根本不会调用 0x0065D55F，亦不会向背包向量 [esi + 8] 添加任何物品；
//    - 真正调用 0x0065D55F 填充背包 [esi + 8] 的 S→C 包是 op 320 (0x140)
//      (CA.exe 0x0050375D Case 0x140=320 分发至 0x004FCCBF)。
//
// 2. 背包数据流协议结构 (op 320 UserItemList):
//    - Encode2(count)（2 字节），随后循环 count 次，每项 12 字节:
//      - Encode4(itemId)     (如蓝妹妹进阶卡 2083)
//      - Encode2(arg1)       (标志/状态，传 0，若为 0xFF 会跳过后续处理)
//      - Encode2(count)      (数量/小时数，传 1，0x4FCDA2 乘以 24 换算为小时并调用 0x0065D281 创建 0xC8 字节背包项插入 [esi + 8])
//      - Encode4(expireTime) (到期时间戳，传 0)
//    - 若该物品在 op 60 中 Category == 36 (0x24):
//      0x004FCCBF 会自动触发 0x0065D55F -> 0x0065C81A 预穿戴到角色升级卡槽 [esi + 0xBBA0]。
//
// 3. 依赖顺序:
//    - 客户端必须遵循: 先有 op 60 物品定义 → 再由 op 320 写入背包 [esi + 8] → 再由 op 198 穿戴到 [esi + 0xBBA0]。
//    - 进频道握手 (JoinSessionReq) 与进练习房 (CreatePracticeSessionReq) 均在下发 op 198 之前先下发 op 320。
// ============================================================================

data class UserItemEntry(
    val itemId: Int,
    val arg1: Int = 0,
    val count: Int = 1,
    val expireTime: Int = 0,
)

// 蓝妹妹 (LuxuryMarid / Posh Evie, ID 10) 角色进阶道具卡
// 在 op 60 (ItemDef 表) 中: itemId = 2083, Category = 36 (0x24), TargetCharID = 4 (Marid / 蓝蓝)
val POSH_EVIE_ITEM = UserItemEntry(itemId = 2083, arg1 = 0, count = 1, expireTime = 0)

val DEFAULT_USER_ITEMS = listOf(POSH_EVIE_ITEM)

private fun SendPacketBase.encodeUserItems(items: List<UserItemEntry>) {
    Encode2(items.size)
    for (item in items) {
        Encode4(item.itemId)
        Encode2(item.arg1)
        Encode2(item.count)
        Encode4(item.expireTime)
    }
}

fun UserItemList(items: List<UserItemEntry> = DEFAULT_USER_ITEMS) = PKT {
    encodeUserItems(items)
}
