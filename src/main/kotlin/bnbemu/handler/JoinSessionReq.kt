package bnbemu.handler

import bnbemu.netty.PKTHandler
import bnbemu.packet.ChannelsGameState
import bnbemu.packet.DynamicMapInfo
import bnbemu.packet.FxConfig
import bnbemu.packet.ItemDef
import bnbemu.packet.JoinSessionResult
import bnbemu.packet.RoomList
import bnbemu.packet.SessionList
import bnbemu.packet.ShopCatalog
import bnbemu.packet.ShopVersion
import bnbemu.packet.Unk204
import bnbemu.packet.Unk247
import bnbemu.packet.Unk423
import bnbemu.packet.Unk66
import bnbemu.packet.UserDynamicInfo
import bnbemu.packet.UserDynamicInfoValue
import bnbemu.packet.UserInventory
import bnbemu.packet.bomberCharCard
import bnbemu.packet.defaultUserInventoryItems
import bnbemu.packet.inventoryEntries
import bnbemu.packet.UserInventoryInfo
import bnbemu.packet.UserItemUpdate
import bnbemu.packet.shop.FxStockInit

// C→S op 81 進頻道自動空包 —— ⚠️ 不是「進房」請求
// =================================================
// 位置: enter-channel.pcap stream1 sq=61, 在批次 297/302/310/64/293/78 之後、274/281/276 之前;
//       用戶進頻道後無任何操作也會發出, 屬進頻道握手流程的一環 (與 274/281/276 同批, 見 AGENTS.md 步驟7)
// 載荷: 空 (ln=2 僅 opcode)
// 行為: 真實伺服器不回 81 的逐包回應, 而是跟發整條「進頻道握手推送」(2026-08-31 時序對齊確認:
//       客戶端發 81 後會等這條推送才繼續發 274/281/276, 缺它客戶端卡「正在連接」對話框):
//         seq=100~121: [413 UserInventoryInfo, 198, 200 FxStockInit, 274 UserItemUpdate×2, 122 UserDynamicInfo, 212 FxConfig, 210 DynamicMapInfo]
//         seq=124~127: [87 RoomList, 65 ShopCatalog(50KB)]          ← 客戶端 274/274/281 之後
//         seq=130~148: [204, 66, 14(9B 變體), 20, 261 ShopVersion, 247, 423]
//         seq=151~154: [81 名冊, 72 用戶列表]            ← 之後大廳期週期重發 (週期未實作)
// S→C op 81 JoinSessionResult 名冊 (大廳期週期推送, 非 81 請求的回應)
// ==================================================================
// 證據: login.pcap 登入連線從無 C→S 81 請求, 但 S→C op 81 名冊仍有 4 次週期推送
//       (sq=112/121/184/199); 結構已由 4 個 131B + 4 個 101B 樣本全量解出:
//
//   E4(人數 N) + N × [ E1(槽位序號 0..N-1)  E4(帳號數值)  E2(名長)  角色名令牌 ]
//
//   與 op 72 SessionList 同玩家集合但**結構不同**: op 81 無等級/頻道容量欄,
//   多帳號數值與槽位序號, 按槽位排序而非等級; 名冊長度隨房間人數變化.
//   帳號數值與 passport 0x2E 回應的 u32(帳號數值) 一致 (ffdf = 7215).
//   令牌編碼 = SessionList.kt encodeName 同款 (4B 塊 LE 讀 ^0x96CA5395 按 BE 寫,
//   尾 2B LE 讀 ^0xA569 按 BE 寫, 尾 1B ^0x5A). 結構化實作見 packet/JoinSessionResult.kt
// server = C???::On??? (原始名稱未知, 綁定見 EN_18_recv.yaml JoinSessionReq)
val JoinSessionReq = PKTHandler { c ->
    val user = c.users.firstOrNull()
    val lucci = user?.lucci ?: 1234567

    // op 60 (ItemDef) 必须最先: 198 里的物品要靠它查定义表
    // 先按当前角色生成: 角色卡 itemId/skinId 与所有 cat=3 表情/动作模型名都在里面。不匹配也不会更改当前选中的角色类型
    c send ItemDef(user?.bomber ?: 0)

    // 服务端认账的背包（商城购买 + 273 上报）必须并入 op 198 的清单里下发：
    //   客户端 CUserInventoryMan::ReadEquippedInventory (0x67601D) 会先清空两位玩家的
    //   背包/装备，再由 ApplyEquippedInventory (0x6757A6) 逐条 sub_65D55F 重建 —— 也就是说
    //   op 198 是「全量真相」，没写进这份清单的物品一律被清掉。
    //   背包物品用伪槽位 0xFF（BAG_ONLY_SLOT），客户端只塞进背包向量、不占装备槽。
    //   C→S op 273 记录下来的装备（背景/旗帜/边框/气泡/特效…）用**真实槽位**下发，
    //   否则回大厅时会被这次清空重建抹掉（表现为「背景自动取消装备」）。
    val bag = user?.items.orEmpty()
    val equips = user?.equips.orEmpty()
    val bomber = user?.bomber ?: 0
    val defaults = defaultUserInventoryItems(bomber)
    val inventory = inventoryEntries(defaults, bag, equips)
    println(
        "[JoinSessionReq] op 198 清单: 默认 ${defaults.size} 条 + 背包 ${bag.size} 件" +
            " + 装备 ${equips.size} 件 → 共 ${inventory.size} 条"
    )
    val (charItemId, charToken) = bomberCharCard(bomber)
    println(
        "[JoinSessionReq] 角色: bomber=$bomber → item $charItemId ($charToken)" +
            " [op 198 角色卡 + op 274 玩家槽 0/1 + op 20 玩家槽 0]"
    )
    if (bag.isNotEmpty()) {
        println("[JoinSessionReq] op 198 携带背包物品: ${bag.map { it.itemId }}")
    }
    if (equips.isNotEmpty()) {
        println(
            "[JoinSessionReq] op 198 携带装备: " + equips.entries.joinToString { (slot, itemId) ->
                "0x${slot.toString(16).uppercase()}(大类${slot / 24}/子槽${slot % 24})=$itemId"
            }
        )
    }

    // 先补一发 413 把背包容量抬到 8×50=400：
    //   op 198 重建背包时会先清空库存，随后 push 物品要过 IsBagFull (0x659520) 检查；
    //   容量为 0 时（= 8×等级 0）物品会被当成「背包满」直接丢掉。
    //   批次末尾还会再发一次 413 —— 因为 198 会把等级重置，必须在其之后重新抬高。
    c send UserInventoryInfo()

    c.send(
        // ⚠️ 进频道推送顺序里 413 必须排在 198 之后:
        //    op 413 的 50 个 10007 槽位是"背包等级 +50"（见 UserInventoryInfo.kt），
        //    但紧跟其后的 op 198 走 ApplyEquippedInventory (0x6757A6 → 0x673CF4)，
        //    会把这个等级覆盖回 0 → 容量 = 8×0 = 0 → IsBagFull (0x659520, 已用 >= 容量) 恒真
        //    → 商城点购买恒弹 "Your item bag is full. Purchase an item bag?"。
        //    把 413 挪到 198 之后，运行时直读 dyn+0x1EC = 50 → 容量 400，购买可正常进入流程。
        //    另: op 60 (ItemDef) 仍需在 413 之前，客户端按 itemId 查定义表，查不到整条丢弃。
        UserInventory(listOf(inventory, inventory)), FxStockInit(),
        UserItemUpdate(0, charItemId), UserItemUpdate(1, charItemId),
        UserDynamicInfoValue(0, lucci), FxConfig(), DynamicMapInfo(),
        RoomList(), ShopCatalog(),
        Unk204(), Unk66(), ChannelsGameState(tailOnes = 2),
        // ⚠️ op 20 (UserDynamicInfo) 是本機玩家動態檔案快照, 第 12 欄 = 角色 ID, 必須按當前角色生成!
        UserDynamicInfo(bomber), ShopVersion(), Unk247(), Unk423(),
        UserInventoryInfo(),
        JoinSessionResult(), SessionList(),
    )
}
