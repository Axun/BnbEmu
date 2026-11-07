package bnbemu.packet

import bnbemu.netty.PKT

// ============================================================================
// 商城单件入包 (S→C op 320) — CA.exe 0x0050375D 分发至 0x004FCCBF
// ============================================================================
// ⚠️ 2026-11-07 修正（原注释的两处结论是错的，保留教训）：
//   - op 320 **不是**「背包列表（count + N 项）」，它一次只塞**一件**商品，包体是
//     E1(playerIdx) + E4(itemId) + E4(param) + E4(hours) + E1(a) + E1(b)；
//   - itemId 的定义是通过 `sub_677912(dword_993D58, itemId)` 在**商城商品管理器**里查的，
//     所以在频道/大厅（商城目录还没加载）发 320 → 查不到定义 → 整条静默丢弃。
// 玩家背包的全量下发走 S→C op 198 + 伪槽位 0xFF（见 packet/UserInventory.kt 的
// bagInventoryEntries）：客户端 ReadEquippedInventory (0x67601D) 会先清空再重建，
// op 198 才是背包的唯一真相源。
// ============================================================================


/**
 * ⚠️ 2026-11-07 修正：op 320 不是「背包列表」，而是**商城专用的单件入包**。
 *
 * `CStage` 分派 case 320 → `sub_4FCCBF` (`0x004FCCBF`) 的实际读包顺序：
 * ```
 * E1(playerIndex)
 * E4(itemId)                 // 用 sub_677912(dword_993D58, itemId) 查**商城商品管理器**的定义
 * E4(param)                  // 写进物品结构 +24
 * E4(hours)                  // 有效小时数，内部 24*hours；为 0 时按 160*24 处理
 * E1(a)                      // 物品结构 +47
 * E1(b)                      // 物品结构 +48
 * // 查不到定义 → 整条丢弃
 * ```
 * 也就是说它**只能**在商城舞台（商城商品管理器已加载）里给玩家塞一件商品，
 * 不能用来下发玩家背包 —— 频道/大厅里 `sub_677912(993D58, ...)` 取不到定义，包会被静默丢掉。
 * 玩家背包的全量同步走 S→C op 198（见 `packet/UserInventory.kt` 的 `bagInventoryEntries`）。
 */
fun UserItemList(
    playerIndex: Int = 0,
    itemId: Int,
    param: Int = 1,
    hours: Int = 0,
    a: Int = 1,
    b: Int = 0,
) = PKT {
    Encode1(playerIndex)
    Encode4(itemId)
    Encode4(param)
    Encode4(hours)
    Encode1(a)
    Encode1(b)
}
